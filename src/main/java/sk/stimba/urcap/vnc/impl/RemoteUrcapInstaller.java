/*
 * STIMBA VNC Server URCap — Remote URCap installer (v3.12.16+)
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Implements the `install_urcap` portal command. The URCap process runs inside
 * Polyscope as root, so it can write into /root/.urcaps/ — the standard URCap
 * deployment directory that wiki/03-build-deploy.md Path B documents for the
 * scp + systemctl restart workflow. We replicate that workflow but driven by a
 * portal command rather than a manual scp; the URCap downloads the new bundle
 * over the same HTTPS channel it already uses for portal heartbeats and then
 * schedules a controller restart so Polyscope reloads URCaps from disk.
 *
 * Security gates:
 *   1. URL must be HTTPS (no plaintext fetch).
 *   2. URL host must match an allowlist (default: github.com/bielikandrej/*),
 *      prevents an unauthorised portal command from pushing arbitrary code.
 *   3. SHA-256 of downloaded bytes must match the expected hash sent by portal.
 *      Defends against in-flight tampering AND against the wrong release being
 *      pushed (cf. v3.12.13/14 prerelease churn).
 *   4. File size capped at MAX_BYTES (100 MiB) to avoid disk-fill DoS.
 *   5. Target filename sanitized — no path traversal, must end in .urcap.
 *
 * Restart strategy:
 *   We can't call `systemctl restart urcontrol.service` synchronously — it
 *   kills the URCap process before the Ack can be PATCHed back to portal. So:
 *   write the file → return Ack(true) → spawn a daemon Thread that sleeps
 *   RESTART_DELAY_MS, then `Runtime.exec("systemctl restart urcontrol.service")`.
 *   The poller has time to ack before the controller goes down.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RemoteUrcapInstaller {

    private static final Logger LOG = Logger.getLogger(RemoteUrcapInstaller.class.getName());

    /** Where Polyscope reads URCaps from at boot (per wiki/01-architecture.md). */
    public static final String URCAPS_DIR = "/root/.urcaps";

    /** Hard cap on bundle size — actual urcaps are ~1.9 MB; 100 MiB is generous. */
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private static final int  CONNECT_TIMEOUT_MS = 15_000;
    private static final int  READ_TIMEOUT_MS    = 60_000;
    private static final int  IO_BUFFER          = 16_384;

    /** Wait this long after returning Ack before kicking the controller restart. */
    private static final long RESTART_DELAY_MS   = 5_000;

    /**
     * Hosts we'll fetch URCap bundles from. Keep tight — anyone who can enqueue
     * an install_urcap command can pick a URL inside these prefixes only.
     * Override per-deployment via the `URCAP_INSTALL_HOST_ALLOWLIST` env var
     * (comma-separated, host[/path-prefix]).
     */
    private static final String[] DEFAULT_ALLOWED_HOST_PREFIXES = {
        "github.com/bielikandrej/",
        "objects.githubusercontent.com/", // GitHub release-asset CDN (signed redirects from github.com)
    };

    private RemoteUrcapInstaller() { /* static-only */ }

    public static final class InstallResult {
        public final boolean ok;
        public final String  message;
        public final String  installedPath;
        public final long    bytesDownloaded;
        InstallResult(boolean ok, String msg, String path, long bytes) {
            this.ok = ok; this.message = msg; this.installedPath = path; this.bytesDownloaded = bytes;
        }
    }

    /**
     * Download `url`, verify SHA-256 against `expectedSha256`, write to
     * /root/.urcaps/`targetName`. Caller is responsible for scheduling the
     * controller restart via {@link #scheduleControllerRestart()}.
     *
     * @param expectedSha256 lower-case hex; must be exactly 64 chars. Required.
     */
    public static InstallResult install(String url, String expectedSha256, String targetName) {
        if (url == null || url.isEmpty()) {
            return new InstallResult(false, "install_urcap: missing 'url'", null, 0);
        }
        if (expectedSha256 == null || expectedSha256.length() != 64) {
            return new InstallResult(false, "install_urcap: 'sha256' must be 64-char lower-case hex", null, 0);
        }
        String safeName = sanitizeTargetName(targetName);
        if (safeName == null) {
            return new InstallResult(false, "install_urcap: 'name' must be a-zA-Z0-9._- and end with .urcap", null, 0);
        }

        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Throwable t) {
            return new InstallResult(false, "install_urcap: invalid URL — " + t.getMessage(), null, 0);
        }
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            return new InstallResult(false, "install_urcap: URL must be https", null, 0);
        }
        if (!hostAllowed(parsed)) {
            return new InstallResult(false,
                "install_urcap: host not in allowlist (host=" + parsed.getHost() + ")", null, 0);
        }

        File urcapsDir = new File(URCAPS_DIR);
        if (!urcapsDir.isDirectory()) {
            // Try to create — Polyscope creates this lazily on first URCap install.
            // If we're running, /root/.urcaps must already exist (we're loaded from
            // there); but be defensive in case of a custom layout.
            if (!urcapsDir.mkdirs()) {
                return new InstallResult(false,
                    "install_urcap: " + URCAPS_DIR + " not a directory and mkdir failed", null, 0);
            }
        }

        File tmp = new File(urcapsDir, "." + safeName + ".tmp");
        File dest = new File(urcapsDir, safeName);
        long total = 0;
        try {
            HttpURLConnection conn = openFollowingRedirects(parsed, 5);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                conn.disconnect();
                return new InstallResult(false,
                    "install_urcap: download HTTP " + code, null, 0);
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_BYTES) {
                conn.disconnect();
                return new InstallResult(false,
                    "install_urcap: declared size " + contentLength + " > cap " + MAX_BYTES, null, 0);
            }

            MessageDigest sha;
            try { sha = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException nsae) {
                return new InstallResult(false, "install_urcap: SHA-256 unavailable on this JRE", null, 0);
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[IO_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("download exceeded cap " + MAX_BYTES);
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                conn.disconnect();
            }

            String actualHex = toLowerHex(sha.digest());
            if (!actualHex.equals(expectedSha256.toLowerCase())) {
                tmp.delete();
                return new InstallResult(false,
                    "install_urcap: SHA-256 mismatch (expected=" + expectedSha256 +
                    " actual=" + actualHex + " bytes=" + total + ")", null, total);
            }

            // Atomic install — Files.move with REPLACE_EXISTING + ATOMIC_MOVE on
            // POSIX gives us a rename(2) which is atomic. Polyscope might be
            // mid-load on URCap re-scan otherwise.
            try {
                Files.move(tmp.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFail) {
                // Some filesystems (overlayfs in URSim Docker) don't support
                // ATOMIC_MOVE — fall back to non-atomic.
                try {
                    Files.move(tmp.toPath(), dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFail) {
                    tmp.delete();
                    return new InstallResult(false,
                        "install_urcap: rename to " + dest.getPath() + " failed — " + moveFail.getMessage(),
                        null, total);
                }
            }

            LOG.info("install_urcap: wrote " + dest.getPath() + " (" + total + " bytes, sha256=" + actualHex + ")");
            return new InstallResult(true,
                "Installed " + dest.getName() + " (" + total + " bytes). Controller restart scheduled in "
                + (RESTART_DELAY_MS / 1000) + "s.",
                dest.getPath(), total);
        } catch (Throwable t) {
            tmp.delete();
            return new InstallResult(false,
                "install_urcap: " + t.getClass().getSimpleName() + " — " + t.getMessage(), null, total);
        }
    }

    /**
     * Spawn a daemon thread that sleeps {@link #RESTART_DELAY_MS} then runs
     * `systemctl restart urcontrol.service`. The current URCap process gets
     * killed by the restart; Polyscope's URCap loader picks up the new bundle
     * on the way back up.
     */
    public static void scheduleControllerRestart() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(RESTART_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                LOG.warning("install_urcap: restarting urcontrol.service now (URCap will reload)");
                try {
                    ProcessBuilder pb = new ProcessBuilder("systemctl", "restart", "urcontrol.service");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    // Best-effort wait so we log the exit, but systemd will tear
                    // us down before this returns in normal operation.
                    p.waitFor();
                } catch (Throwable inner) {
                    LOG.log(Level.SEVERE,
                        "install_urcap: systemctl restart failed — operator must restart Polyscope manually",
                        inner);
                }
            }
        }, "stimba-urcap-restart");
        t.setDaemon(true);
        t.start();
    }

    // ---------- helpers --------------------------------------------------

    private static HttpURLConnection openFollowingRedirects(URL initial, int maxHops) throws IOException {
        URL current = initial;
        for (int i = 0; i <= maxHops; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // we handle redirects so we can re-allowlist
            conn.setRequestProperty("User-Agent", PortalClient.URCAP_VERSION);
            conn.setRequestProperty("Accept", "application/octet-stream, */*");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("redirect with no Location");
                URL next = new URL(current, loc);
                if (!"https".equalsIgnoreCase(next.getProtocol())) {
                    throw new IOException("redirect to non-https rejected: " + next);
                }
                if (!hostAllowed(next)) {
                    throw new IOException("redirect to non-allowlisted host: " + next.getHost());
                }
                current = next;
                continue;
            }
            return conn;
        }
        throw new IOException("too many redirects (>" + maxHops + ")");
    }

    private static boolean hostAllowed(URL u) {
        String hostAndPath = u.getHost() + (u.getPath() == null ? "" : u.getPath());
        String[] prefixes = readAllowlistOverride();
        for (String p : prefixes) {
            if (p == null || p.isEmpty()) continue;
            if (hostAndPath.startsWith(p)) return true;
        }
        return false;
    }

    private static String[] readAllowlistOverride() {
        String env = System.getenv("URCAP_INSTALL_HOST_ALLOWLIST");
        if (env != null && !env.trim().isEmpty()) {
            String[] parts = env.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            return parts;
        }
        return DEFAULT_ALLOWED_HOST_PREFIXES;
    }

    private static String sanitizeTargetName(String name) {
        if (name == null || name.isEmpty()) return null;
        // No path separators, no parent traversal, no leading dot (would be hidden).
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) return null;
        if (!name.matches("[A-Za-z0-9._-]+")) return null;
        if (!name.toLowerCase().endsWith(".urcap")) return null;
        if (name.length() > 200) return null;
        return name;
    }

    private static String toLowerHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2]     = hex[b >>> 4];
            out[i * 2 + 1] = hex[b & 0x0F];
        }
        return new String(out);
    }
}
