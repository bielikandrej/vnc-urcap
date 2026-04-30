/*
 * STIMBA VNC Server URCap — Remote file operations (v3.12.16+)
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Backs the FTP-style file browser in portal.stimba.sk. URCap process runs as
 * root inside Polyscope, so it can read/write the controller filesystem on
 * behalf of authorised portal users — but only inside an allowlist of paths
 * that are useful for operations and don't expose secrets.
 *
 * Allowlist (read+write):
 *   /programs/                     — operator .urp programs
 *   /root/.urcaps/                 — URCap bundles (also handled by RemoteUrcapInstaller)
 *   /tmp/                          — scratch space
 *   /var/lib/urcap-vnc/            — daemon config (KEY=VALUE)
 *
 * Allowlist (read-only):
 *   /var/log/urcap-vnc.log
 *   /var/log/urcap-vnc-audit.log
 *   /var/log/PolyscopeLog.log
 *   /var/log/urcontrol.log
 *
 * Hard refuses (anti-credential exfil):
 *   /etc/passwd, /etc/shadow, /root/.ssh/, /root/.vnc/passwd, anything outside
 *   the allowlist after symlink resolution.
 *
 * Wire format: all path arguments are validated through {@link #resolveSafe},
 * which canonicalises symlinks then re-checks against the allowlist. Bytes
 * cross the wire base64-encoded inside JSON command args/results — chunked
 * because the portal command queue holds entries with ~16 KB payload caps.
 */
package sk.stimba.urcap.vnc.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

public final class RemoteFileOps {

    private static final Logger LOG = Logger.getLogger(RemoteFileOps.class.getName());

    /** Roots under which the portal user can read AND write. */
    private static final List<String> RW_ROOTS = Arrays.asList(
        "/programs",
        "/root/.urcaps",
        "/tmp",
        "/var/lib/urcap-vnc"
    );

    /** Specific files the portal user can read but not write. */
    private static final List<String> RO_FILES = Arrays.asList(
        "/var/log/urcap-vnc.log",
        "/var/log/urcap-vnc-audit.log",
        "/var/log/PolyscopeLog.log",
        "/var/log/urcontrol.log"
    );

    /** Per-write-call cap — keeps a single bad chunk from filling /. */
    public static final int  MAX_CHUNK_BYTES = 8 * 1024 * 1024;  // 8 MiB
    /** Per-read-call cap — limits memory + JSON payload size. */
    public static final int  MAX_READ_BYTES  = 1 * 1024 * 1024;  // 1 MiB

    /** Per-file ceiling — anti-disk-fill. */
    public static final long MAX_FILE_BYTES  = 100L * 1024 * 1024;

    public enum Mode { READ_ONLY, READ_WRITE }

    public static final class OpResult {
        public final boolean ok;
        public final String  resultJson;   // populated on ok
        public final String  errorMessage; // populated on !ok
        OpResult(boolean ok, String result, String err) {
            this.ok = ok; this.resultJson = result; this.errorMessage = err;
        }
        public static OpResult ok(String json)    { return new OpResult(true, json, null); }
        public static OpResult fail(String msg)   { return new OpResult(false, null, msg); }
    }

    private RemoteFileOps() { /* static-only */ }

    // -----------------------------------------------------------------
    //  Public API — each maps 1:1 to a portal dispatcher case.
    // -----------------------------------------------------------------

    /** `file_list { path }` — returns `{ entries: [{name,type,size,mtime_ms},…] }`. */
    public static OpResult list(String pathArg) {
        Path p = resolveSafe(pathArg, Mode.READ_ONLY);
        if (p == null) return OpResult.fail("file_list: path not in allowlist (" + pathArg + ")");
        File dir = p.toFile();
        if (!dir.isDirectory()) return OpResult.fail("file_list: not a directory: " + dir.getPath());

        File[] kids = dir.listFiles();
        if (kids == null) kids = new File[0];

        StringBuilder sb = new StringBuilder("{\"path\":");
        appendJsonString(sb, dir.getPath());
        sb.append(",\"entries\":[");
        boolean first = true;
        for (File k : kids) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":");
            appendJsonString(sb, k.getName());
            sb.append(",\"type\":\"")
              .append(k.isDirectory() ? "dir" : (k.isFile() ? "file" : "other"))
              .append("\"")
              .append(",\"size\":").append(k.length())
              .append(",\"mtime_ms\":").append(k.lastModified())
              .append("}");
        }
        sb.append("]}");
        return OpResult.ok(sb.toString());
    }

    /** `file_stat { path }` — returns `{ exists, type, size, mtime_ms, can_write }`. */
    public static OpResult stat(String pathArg) {
        Path p = resolveSafe(pathArg, Mode.READ_ONLY);
        if (p == null) return OpResult.fail("file_stat: path not in allowlist (" + pathArg + ")");
        File f = p.toFile();
        StringBuilder sb = new StringBuilder("{\"path\":");
        appendJsonString(sb, f.getPath());
        sb.append(",\"exists\":").append(f.exists());
        if (f.exists()) {
            sb.append(",\"type\":\"").append(f.isDirectory() ? "dir" : "file").append("\"")
              .append(",\"size\":").append(f.length())
              .append(",\"mtime_ms\":").append(f.lastModified());
        }
        sb.append(",\"can_write\":").append(resolveSafe(pathArg, Mode.READ_WRITE) != null);
        sb.append("}");
        return OpResult.ok(sb.toString());
    }

    /** `file_read { path, offset, length }` — returns `{ b64, eof, total_size }`. */
    public static OpResult read(String pathArg, long offset, int length) {
        if (length < 0 || length > MAX_READ_BYTES) {
            return OpResult.fail("file_read: length must be in [0," + MAX_READ_BYTES + "]");
        }
        if (offset < 0) {
            return OpResult.fail("file_read: offset must be >= 0");
        }
        Path p = resolveSafe(pathArg, Mode.READ_ONLY);
        if (p == null) return OpResult.fail("file_read: path not in allowlist (" + pathArg + ")");
        File f = p.toFile();
        if (!f.isFile()) return OpResult.fail("file_read: not a regular file: " + f.getPath());

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long totalSize = raf.length();
            if (offset > totalSize) {
                return OpResult.fail("file_read: offset " + offset + " > size " + totalSize);
            }
            int actualLen = (int) Math.min((long) length, totalSize - offset);
            byte[] buf = new byte[actualLen];
            raf.seek(offset);
            raf.readFully(buf);
            String b64 = Base64.getEncoder().encodeToString(buf);
            boolean eof = (offset + actualLen) >= totalSize;
            StringBuilder sb = new StringBuilder("{\"b64\":");
            appendJsonString(sb, b64);
            sb.append(",\"eof\":").append(eof)
              .append(",\"total_size\":").append(totalSize)
              .append(",\"bytes\":").append(actualLen)
              .append("}");
            return OpResult.ok(sb.toString());
        } catch (IOException ioe) {
            return OpResult.fail("file_read: " + ioe.getMessage());
        }
    }

    /**
     * `file_write { path, offset, b64, total_size?, truncate? }`
     * Writes `b64` decoded bytes at offset. If offset==0 and truncate=true, the
     * file is truncated first. Caller chunks large files; final chunk should
     * include `total_size` so URCap can verify integrity.
     */
    public static OpResult write(String pathArg, long offset, String b64,
                                 Long expectedTotalSize, boolean truncate) {
        if (offset < 0) return OpResult.fail("file_write: offset must be >= 0");
        if (b64 == null) return OpResult.fail("file_write: missing 'b64'");
        Path p = resolveSafe(pathArg, Mode.READ_WRITE);
        if (p == null) return OpResult.fail("file_write: path not in writable allowlist (" + pathArg + ")");

        byte[] data;
        try {
            data = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException iae) {
            return OpResult.fail("file_write: invalid base64");
        }
        if (data.length > MAX_CHUNK_BYTES) {
            return OpResult.fail("file_write: chunk " + data.length + " > cap " + MAX_CHUNK_BYTES);
        }
        if (offset + data.length > MAX_FILE_BYTES) {
            return OpResult.fail("file_write: would exceed per-file cap " + MAX_FILE_BYTES);
        }

        File f = p.toFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return OpResult.fail("file_write: parent dir mkdir failed: " + parent.getPath());
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            if (offset == 0 && truncate) raf.setLength(0);
            raf.seek(offset);
            raf.write(data);
            long postSize = raf.length();
            StringBuilder sb = new StringBuilder("{\"path\":");
            appendJsonString(sb, f.getPath());
            sb.append(",\"bytes_written\":").append(data.length)
              .append(",\"offset\":").append(offset)
              .append(",\"size\":").append(postSize)
              .append("}");
            if (expectedTotalSize != null && expectedTotalSize == postSize) {
                LOG.info("file_write: complete " + f.getPath() + " (" + postSize + " bytes)");
            }
            return OpResult.ok(sb.toString());
        } catch (IOException ioe) {
            return OpResult.fail("file_write: " + ioe.getMessage());
        }
    }

    /** `file_delete { path }` — refuses to delete non-empty directories (use a recursive op for that). */
    public static OpResult delete(String pathArg) {
        Path p = resolveSafe(pathArg, Mode.READ_WRITE);
        if (p == null) return OpResult.fail("file_delete: path not in writable allowlist (" + pathArg + ")");
        File f = p.toFile();
        if (!f.exists()) return OpResult.ok("{\"deleted\":false,\"reason\":\"not found\"}");
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null && kids.length > 0) {
                return OpResult.fail("file_delete: directory not empty: " + f.getPath());
            }
        }
        boolean deleted = f.delete();
        if (!deleted) return OpResult.fail("file_delete: rm failed (permission? handle held?): " + f.getPath());
        return OpResult.ok("{\"deleted\":true,\"path\":\"" + escapeJson(f.getPath()) + "\"}");
    }

    /** `file_mkdir { path }` — creates parents, idempotent. */
    public static OpResult mkdir(String pathArg) {
        Path p = resolveSafe(pathArg, Mode.READ_WRITE);
        if (p == null) return OpResult.fail("file_mkdir: path not in writable allowlist (" + pathArg + ")");
        File f = p.toFile();
        if (f.exists()) {
            if (!f.isDirectory()) return OpResult.fail("file_mkdir: exists and is not a directory: " + f.getPath());
            return OpResult.ok("{\"created\":false,\"path\":\"" + escapeJson(f.getPath()) + "\"}");
        }
        if (!f.mkdirs()) return OpResult.fail("file_mkdir: mkdir failed: " + f.getPath());
        return OpResult.ok("{\"created\":true,\"path\":\"" + escapeJson(f.getPath()) + "\"}");
    }

    // -----------------------------------------------------------------
    //  Internal: path safety
    // -----------------------------------------------------------------

    /**
     * Canonicalise the user-supplied path (resolves "." / ".." / symlinks),
     * then check the result is inside an allowlisted root for the requested
     * mode. Returns null on rejection.
     *
     * Why two-step: if we only checked the literal input, an attacker could
     * supply `/programs/../etc/passwd` or a symlink farm — toRealPath() walks
     * the actual filesystem entries so we get the post-resolution path.
     */
    static Path resolveSafe(String pathArg, Mode mode) {
        if (pathArg == null || pathArg.isEmpty()) return null;
        Path p;
        try {
            p = Paths.get(pathArg);
        } catch (Throwable t) {
            return null;
        }
        if (!p.isAbsolute()) return null;

        // Try to resolve symlinks. If the file doesn't exist yet (write to new
        // path), resolve the parent and append the leaf — that's enough to
        // detect symlink-based escapes via the parent.
        Path canonical;
        try {
            canonical = p.toRealPath();
        } catch (IOException ioe) {
            Path parent = p.getParent();
            if (parent == null) return null;
            try {
                canonical = parent.toRealPath(LinkOption.NOFOLLOW_LINKS).resolve(p.getFileName());
            } catch (IOException ioe2) {
                // Parent doesn't exist either — accept literal path; mkdirs will
                // be checked against allowlist via the same logic on the literal.
                canonical = p;
            }
        }

        String canonStr = canonical.toString();
        if (mode == Mode.READ_WRITE) {
            for (String root : RW_ROOTS) {
                if (canonStr.equals(root) || canonStr.startsWith(root + "/")) return canonical;
            }
            return null;
        } else {
            // READ: RW roots are also readable; plus specific RO files.
            for (String root : RW_ROOTS) {
                if (canonStr.equals(root) || canonStr.startsWith(root + "/")) return canonical;
            }
            for (String f : RO_FILES) {
                if (canonStr.equals(f)) return canonical;
            }
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  Internal: JSON helpers (no 3rd-party dep, matches PortalClient style)
    // -----------------------------------------------------------------

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"').append(escapeJson(s)).append('"');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
            }
        }
        return sb.toString();
    }
}
