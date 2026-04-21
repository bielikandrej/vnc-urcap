/*
 * STIMBA VNC Server URCap — Installation node contribution (v3.0.0)
 * Copyright (c) 2026 STIMBA, s. r. o.
 *
 * Persists user settings (port, password, autostart, IXrouter IP, customer
 * label, strong-pwd toggle, TLS, idle timeout, max clients) via Polyscope
 * DataModel and atomically writes them to /var/lib/urcap-vnc/config for
 * the root-owned daemon to source.
 *
 * Sprint 1 additions (v2.1.0 vs. v2.0.2):
 *   - KEY_IXROUTER_IP  — per-robot IXrouter IP (A2)
 *   - KEY_CUSTOMER_LABEL — fleet identifier for LOG_TAG (A3)
 *   - KEY_STRONG_PWD   — easybot tripwire toggle (A5)
 *   - writeConfigFile() — atomic write to /var/lib/urcap-vnc/config (A1)
 *   - pollHealth()     — spawns health-probe.sh, parses JSON (A4)
 *   - estimatePasswordStrength() — first 8 chars only (B5, RFB truncation)
 *
 * Sprint 2 additions (v2.2.0):
 *   - tailLog(int lines)        — read last N lines from /var/log/urcap-vnc.log (B1)
 *   - runDiagBundle()           — spawn diag-bundle.sh, return tar.gz path (B2)
 *   - addTempAllowlist(...)     — spawn temp-allowlist-add.sh w/ IP+TTL validation (B3)
 *   - listTempAllowlist()       — parse /var/log/urcap-vnc-temp-allowlist rows (B3)
 *   - testConnection()          — spawn vnc-test.sh, parse JSON (B6)
 *   - audit-log is wired into run-vnc.sh via -accept/-gone hooks (B4)
 *
 * Sprint 3 additions (v3.0.0):
 *   - KEY_TLS_ENABLED      — wire-level x11vnc -ssl SAVE toggle (C1)
 *   - KEY_IDLE_TIMEOUT_MIN — pointer-idle disconnect threshold in minutes (C2)
 *   - KEY_MAX_CLIENTS      — max simultaneous VNC clients (1..5) (C4)
 *   - getTlsFingerprint()  — reads /root/.vnc/certs/fingerprint.txt (C1)
 *   - Tooltip text constants are surfaced by the view (C7)
 */
package sk.stimba.urcap.vnc.impl;

import com.ur.urcap.api.contribution.DaemonContribution;
import com.ur.urcap.api.contribution.InstallationNodeContribution;
import com.ur.urcap.api.contribution.installation.CreationContext;
import com.ur.urcap.api.contribution.installation.InstallationAPIProvider;
import com.ur.urcap.api.domain.data.DataModel;
import com.ur.urcap.api.domain.script.ScriptWriter;
import com.ur.urcap.api.domain.userinteraction.keyboard.KeyboardInputFactory;

import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VncInstallationNodeContribution implements InstallationNodeContribution {

    // --- DataModel keys ---------------------------------------------------
    private static final String KEY_PORT             = "vnc.port";
    private static final String KEY_PASSWORD         = "vnc.password";
    private static final String KEY_VIEW_ONLY        = "vnc.viewOnly";
    private static final String KEY_AUTOSTART        = "vnc.autostart";
    private static final String KEY_IXROUTER_IP      = "vnc.ixrouterIp";
    private static final String KEY_CUSTOMER_LABEL   = "vnc.customerLabel";
    private static final String KEY_STRONG_PWD       = "vnc.requireStrongPwd";
    private static final String KEY_TLS_ENABLED      = "vnc.tlsEnabled";       // v3.0.0 C1
    private static final String KEY_IDLE_TIMEOUT_MIN = "vnc.idleTimeoutMin";   // v3.0.0 C2
    private static final String KEY_MAX_CLIENTS      = "vnc.maxClients";       // v3.0.0 C4

    // --- v3.4.0 portal pairing --------------------------------------------
    private static final String KEY_PORTAL_TOKEN       = "portal.token";
    private static final String KEY_PORTAL_DEVICE_ID   = "portal.deviceId";
    private static final String KEY_PORTAL_PAIRED_AT   = "portal.pairedAt";
    private static final String KEY_PORTAL_LAST_STATUS = "portal.lastStatus";

    // --- Defaults ---------------------------------------------------------
    private static final int     DEFAULT_PORT             = 5900;
    private static final String  DEFAULT_PASSWORD         = "ixon";
    private static final boolean DEFAULT_VIEW_ONLY        = false;
    private static final boolean DEFAULT_AUTOSTART        = true;
    private static final String  DEFAULT_IXROUTER_IP      = "192.168.0.100";
    private static final String  DEFAULT_CUSTOMER_LABEL   = "";
    private static final boolean DEFAULT_STRONG_PWD       = true;
    private static final boolean DEFAULT_TLS_ENABLED      = true;              // v3.0.0 — secure-by-default
    private static final int     DEFAULT_IDLE_TIMEOUT_MIN = 30;                // v3.0.0 — 30 min walk-away safety
    private static final int     DEFAULT_MAX_CLIENTS      = 1;                 // v3.0.0 — single-session admin

    // --- Validation limits for Sprint-3 fields ----------------------------
    public  static final int     IDLE_TIMEOUT_MIN_MIN     = 0;                 // 0 disables the watcher
    public  static final int     IDLE_TIMEOUT_MIN_MAX     = 120;
    public  static final int     MAX_CLIENTS_MIN          = 1;
    public  static final int     MAX_CLIENTS_MAX          = 5;

    // --- Config file paths (shared with daemon) ---------------------------
    private static final String  CONFIG_DIR       = "/var/lib/urcap-vnc";
    private static final String  CONFIG_FILE      = CONFIG_DIR + "/config";
    private static final String  DAEMON_DIR       = "/root/.urcaps/sk.stimba.urcap.vnc-server/sk/stimba/urcap/vnc/impl/daemon";
    private static final String  HEALTH_PROBE     = DAEMON_DIR + "/health-probe.sh";
    private static final String  DIAG_BUNDLE      = DAEMON_DIR + "/diag-bundle.sh";
    private static final String  TEMP_ALLOW_ADD   = DAEMON_DIR + "/temp-allowlist-add.sh";
    private static final String  VNC_TEST         = DAEMON_DIR + "/vnc-test.sh";
    private static final String  VNC_LOG          = "/var/log/urcap-vnc.log";
    // AUDIT_LOG lives at /var/log/urcap-vnc-audit.log — read only by diag-bundle.sh.
    private static final String  TEMP_ALLOW_FILE  = "/var/log/urcap-vnc-temp-allowlist";
    // v3.0.0 — TLS bootstrap writes the SHA-256 fingerprint here for UI display.
    private static final String  TLS_FP_FILE      = "/root/.vnc/certs/fingerprint.txt";

    // --- TTL presets (seconds) for temp allowlist dialog ------------------
    public static final int  TTL_15_MIN = 15  * 60;
    public static final int  TTL_30_MIN = 30  * 60;
    public static final int  TTL_60_MIN = 60  * 60;
    public static final int  TTL_MAX    = 240 * 60;   // 4 h upper bound — UI hard-cap

    // --- Validation patterns ----------------------------------------------
    /** IPv4 dotted quad, each octet 0-255. */
    private static final Pattern IXROUTER_IP_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    /** Customer label: alphanumeric, space, hyphen, underscore. Max 32 chars. Empty allowed. */
    private static final Pattern CUSTOMER_LABEL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9 _\\-]{0,32}$");
    /** Health probe JSON key/value scanner. */
    private static final Pattern HEALTH_KV_PATTERN = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*\"(\\w+)\"");

    private final DataModel model;
    private final VncInstallationNodeView view;
    private final VncDaemonService daemonService;
    private Timer uiTimer;
    private Timer healthTimer;

    // v3.4.0 — portal pairing + heartbeat
    private final PortalClient portalClient = new PortalClient(PortalClient.DEFAULT_PORTAL_URL);
    private final DashboardClient dashboardClient = new DashboardClient();
    private PortalHeartbeatRunner portalHeartbeat;

    public VncInstallationNodeContribution(InstallationAPIProvider apiProvider,
                                           VncInstallationNodeView view,
                                           DataModel model,
                                           VncDaemonService daemonService,
                                           CreationContext context) {
        this.model = model;
        this.view = view;
        this.daemonService = daemonService;

        // v3.3.0 — wire Polyscope's on-screen keyboard into the view so text-field
        // taps on the teach pendant open the PS native keyboard (JTextField alone
        // does NOT trigger it). URCap API ≥ 1.3 supports this via
        // InstallationAPIProvider → UserInterfaceAPI → UserInteraction.
        try {
            KeyboardInputFactory kbf = apiProvider
                    .getUserInterfaceAPI()
                    .getUserInteraction()
                    .getKeyboardInputFactory();
            this.view.setKeyboardInputFactory(kbf);
        } catch (Throwable t) {
            // If the API surface changed in future PS releases, fall back to raw
            // Swing (which only works when a physical keyboard is attached).
            // Better to degrade than refuse to load.
        }

        if (context.getNodeCreationType() == CreationContext.NodeCreationType.NEW) {
            model.set(KEY_PORT,             DEFAULT_PORT);
            model.set(KEY_PASSWORD,         DEFAULT_PASSWORD);
            model.set(KEY_VIEW_ONLY,        DEFAULT_VIEW_ONLY);
            model.set(KEY_AUTOSTART,        DEFAULT_AUTOSTART);
            model.set(KEY_IXROUTER_IP,      DEFAULT_IXROUTER_IP);
            model.set(KEY_CUSTOMER_LABEL,   DEFAULT_CUSTOMER_LABEL);
            model.set(KEY_STRONG_PWD,       DEFAULT_STRONG_PWD);
            // v3.0.0 — Sprint 3 defaults
            model.set(KEY_TLS_ENABLED,      DEFAULT_TLS_ENABLED);
            model.set(KEY_IDLE_TIMEOUT_MIN, DEFAULT_IDLE_TIMEOUT_MIN);
            model.set(KEY_MAX_CLIENTS,      DEFAULT_MAX_CLIENTS);
        }

        // Fire up the daemon automatically if autostart is set
        applyDesiredDaemonStatus();
    }

    // --- InstallationNodeContribution lifecycle ---------------------------

    @Override
    public void openView() {
        view.updatePort(getPort());
        view.updatePassword(getPassword());
        view.updateViewOnly(isViewOnly());
        view.updateAutostart(isAutostart());
        view.updateIxrouterIp(getIxrouterIp());
        view.updateCustomerLabel(getCustomerLabel());
        view.updateRequireStrongPwd(isRequireStrongPwd());
        view.updatePasswordStrength(estimatePasswordStrength(getPassword()));
        // v3.0.0 — Sprint 3 fields
        view.updateTlsEnabled(isTlsEnabled());
        view.updateIdleTimeoutMin(getIdleTimeoutMin());
        view.updateMaxClients(getMaxClients());
        view.updateTlsFingerprintLabel(getTlsFingerprint());

        // Daemon-state tick: 1s (fast so Start/Stop feedback feels live)
        uiTimer = new Timer(true);
        uiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        view.updateDaemonState(getDaemonStateLabel());
                    }
                });
            }
        }, 0, 1000);

        // Health-probe tick: 5s (spawn cost amortized)
        healthTimer = new Timer(true);
        healthTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                final Map<String, String> probed = pollHealth();
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        view.updateHealth(probed);
                    }
                });
            }
        }, 0, 5000);

        // Sprint 2 — start temp-allowlist table refresh (10s cadence).
        // Log tail timer starts only when the user toggles the "Auto-refresh" checkbox.
        view.startTempAllowlistTimer();

        // v3.4.0 — populate portal pairing section + start heartbeat if paired
        view.updatePortalPaired(isPaired(), getPortalDeviceIdMasked(), getPortalLastStatus());
        startHeartbeatIfPaired();
    }

    @Override
    public void closeView() {
        if (uiTimer != null)     { uiTimer.cancel();     uiTimer = null;     }
        if (healthTimer != null) { healthTimer.cancel(); healthTimer = null; }
        if (view != null)        { view.stopSprint2Timers(); }
        stopHeartbeat();
    }

    @Override
    public void generateScript(ScriptWriter writer) {
        // VNC server has no runtime script-side hooks; nothing to emit into programs.
    }

    /**
     * (v3.0.3, 2026-04-19) Defensive override for InstallationNodeContribution.isDefined().
     *
     * The URCap API 1.3.0 InstallationNodeContribution interface declares
     * isDefined() so Polyscope can ask the contribution "are your settings
     * meaningful enough to expose this node in the UI?" If an impl does NOT
     * implement this method at runtime, Polyscope throws AbstractMethodError
     * the moment the Installation panel opens.
     *
     * Our stub interface (reconstructed pre-v3.0.3) did not include
     * isDefined(), so ECJ compiled the class without it. Adding it here is
     * a no-op if the real interface lacks the method (it becomes an
     * unreferenced public method — dead but benign), and saves us from an
     * AbstractMethodError if the real interface requires it.
     *
     * We return `true` unconditionally: VNC defaults are always present
     * (port=5900, password=ixon, autostart=true) from NodeCreationType.NEW,
     * so the node is *always* defined once instantiated.
     */
    public boolean isDefined() {
        return true;
    }

    // --- Config accessors -------------------------------------------------

    // NOTE (hotfix3 / v3.0.2, 2026-04-19):
    // All readers below call primitive-typed overloads: model.get(key, default).
    // Why:
    //   - Earlier attempts used generic/Object stubs; both `get(String,Object)->Object`
    //     (v3.0.0) and `get(String)->Object` (v3.0.1) fail at runtime with
    //     NoSuchMethodError because the real URCap API 1.3.0 DataModel interface
    //     exposes ONLY typed primitive overloads: int/boolean/String variants
    //     of both get and set. No generic, no single-arg get.
    //   - Bytecode descriptors confirmed from known-good stimba-vnc-server 2.0.0
    //     JAR (which worked on PS5): get(String,I)I, get(String,Z)Z,
    //     get(String,Ljava/lang/String;)Ljava/lang/String;, and matching set()s.
    //   - Primitive overloads also eliminate the isSet/cast dance and any
    //     autoboxing — cleaner code, identical runtime behaviour.

    public int getPort() { return model.get(KEY_PORT, DEFAULT_PORT); }
    public void setPort(int port) { model.set(KEY_PORT, port); }

    public String getPassword() { return model.get(KEY_PASSWORD, DEFAULT_PASSWORD); }
    public void setPassword(String pw) {
        if (pw == null || pw.isEmpty()) pw = DEFAULT_PASSWORD;
        model.set(KEY_PASSWORD, pw);
    }

    public boolean isViewOnly() { return model.get(KEY_VIEW_ONLY, DEFAULT_VIEW_ONLY); }
    public void setViewOnly(boolean v){ model.set(KEY_VIEW_ONLY, v); }

    public boolean isAutostart() { return model.get(KEY_AUTOSTART, DEFAULT_AUTOSTART); }
    public void setAutostart(boolean v){ model.set(KEY_AUTOSTART, v); applyDesiredDaemonStatus(); }

    public String getIxrouterIp() { return model.get(KEY_IXROUTER_IP, DEFAULT_IXROUTER_IP); }
    public boolean setIxrouterIp(String ip) {
        if (ip == null || !IXROUTER_IP_PATTERN.matcher(ip.trim()).matches()) return false;
        model.set(KEY_IXROUTER_IP, ip.trim());
        return true;
    }

    public String getCustomerLabel() { return model.get(KEY_CUSTOMER_LABEL, DEFAULT_CUSTOMER_LABEL); }
    public boolean setCustomerLabel(String label) {
        if (label == null) label = "";
        if (!CUSTOMER_LABEL_PATTERN.matcher(label).matches()) return false;
        model.set(KEY_CUSTOMER_LABEL, label);
        return true;
    }

    public boolean isRequireStrongPwd() { return model.get(KEY_STRONG_PWD, DEFAULT_STRONG_PWD); }
    public void setRequireStrongPwd(boolean v){ model.set(KEY_STRONG_PWD, v); }

    // --- Sprint 3 (v3.0.0) accessors --------------------------------------

    /** (C1) Wire-level TLS via x11vnc -ssl SAVE. Default on. */
    public boolean isTlsEnabled() { return model.get(KEY_TLS_ENABLED, DEFAULT_TLS_ENABLED); }
    public void setTlsEnabled(boolean v){ model.set(KEY_TLS_ENABLED, v); }

    /** (C2) Pointer-idle auto-disconnect threshold. 0 disables the watcher. */
    public int getIdleTimeoutMin() { return model.get(KEY_IDLE_TIMEOUT_MIN, DEFAULT_IDLE_TIMEOUT_MIN); }
    public boolean setIdleTimeoutMin(int m) {
        if (m < IDLE_TIMEOUT_MIN_MIN || m > IDLE_TIMEOUT_MIN_MAX) return false;
        model.set(KEY_IDLE_TIMEOUT_MIN, m);
        return true;
    }

    /** (C4) Max concurrent VNC clients. 1 = single-admin (secure default). */
    public int getMaxClients() { return model.get(KEY_MAX_CLIENTS, DEFAULT_MAX_CLIENTS); }
    public boolean setMaxClients(int n) {
        if (n < MAX_CLIENTS_MIN || n > MAX_CLIENTS_MAX) return false;
        model.set(KEY_MAX_CLIENTS, n);
        return true;
    }

    /**
     * (C1) Read the SHA-256 fingerprint published by tls-bootstrap.sh.
     * Returns the first line of /root/.vnc/certs/fingerprint.txt (format:
     * "SHA256 Fingerprint=AB:CD:...:EF") or a placeholder string if the
     * file is absent (meaning the daemon has never run with TLS_ENABLED=1).
     *
     * This is read-only and best-effort — never throws. Safe to call from
     * the EDT (small file, ~100 bytes).
     */
    public String getTlsFingerprint() {
        Path p = Paths.get(TLS_FP_FILE);
        if (!Files.isReadable(p)) {
            return "(nevygenerovaný — spusti daemon s TLS aspoň raz)";
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null || line.trim().isEmpty()) {
                return "(fingerprint súbor je prázdny)";
            }
            return line.trim();
        } catch (IOException e) {
            return "(čítanie zlyhalo: " + e.getClass().getSimpleName() + ")";
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { /* ok */ }
        }
    }

    // --- Daemon control ---------------------------------------------------

    public String getDaemonStateLabel() {
        DaemonContribution d = daemonService.getDaemon();
        if (d == null) return "not initialised";
        DaemonContribution.State s = d.getState();
        return s == null ? "unknown" : s.toString();
    }

    public void startDaemon() {
        DaemonContribution d = daemonService.getDaemon();
        if (d != null) d.start();
    }

    public void stopDaemon() {
        DaemonContribution d = daemonService.getDaemon();
        if (d != null) d.stop();
    }

    /** Apply the persisted autostart preference to the real daemon state. */
    private void applyDesiredDaemonStatus() {
        DaemonContribution d = daemonService.getDaemon();
        if (d == null) return;
        if (isAutostart()) d.start();
        else               d.stop();
    }

    // --- Config file bridge (UI → daemon, ADR-004) ------------------------

    /**
     * Atomically write the current settings to /var/lib/urcap-vnc/config.
     *
     * Returns {@code null} on success, or a human-readable error message
     * on failure (shown as a red banner in the UI).
     *
     * The bootstrap chicken-and-egg: if the daemon has never run, the dir
     * does not yet exist. We return a "Bootstrap needed — click Start" hint.
     */
    public String writeConfigFile() {
        Path dir = Paths.get(CONFIG_DIR);
        if (!Files.isDirectory(dir)) {
            return "Bootstrap needed — klikni Start daemon najprv (vytvorí " + CONFIG_DIR + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# STIMBA VNC URCap v3.0.0 — auto-generated, do not edit by hand.\n");
        sb.append("# Written by polyscope user via VncInstallationNodeContribution.writeConfigFile()\n");
        sb.append("VNC_PORT=").append(getPort()).append("\n");
        sb.append("VNC_PASSWORD=\"").append(shellEscape(getPassword())).append("\"\n");
        sb.append("VNC_VIEW_ONLY=").append(isViewOnly() ? "true" : "false").append("\n");
        sb.append("IXROUTER_IP=").append(getIxrouterIp()).append("\n");
        sb.append("CUSTOMER_LABEL=\"").append(shellEscape(getCustomerLabel())).append("\"\n");
        sb.append("URCAP_VNC_REQUIRE_STRONG_PWD=").append(isRequireStrongPwd() ? "1" : "0").append("\n");
        // v3.0.0 — Sprint 3 fields
        sb.append("TLS_ENABLED=").append(isTlsEnabled() ? "1" : "0").append("\n");
        sb.append("IDLE_TIMEOUT_MIN=").append(getIdleTimeoutMin()).append("\n");
        sb.append("MAX_CLIENTS=").append(getMaxClients()).append("\n");
        // Tell daemon to refresh /root/.vnc/passwd on next start (password may have changed)
        sb.append("VNC_PASSWORD_REFRESH=true\n");

        try {
            Path tmp = Files.createTempFile(dir, "config-", ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, Paths.get(CONFIG_FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            return "Zápis configu zlyhal: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage();
        }
    }

    /**
     * Apply button flow: validate + write + restart daemon.
     * Returns status message (null = success).
     */
    public String applyConfig() {
        String err = writeConfigFile();
        if (err != null) return err;
        // Kick daemon so new config takes effect
        stopDaemon();
        try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        startDaemon();
        return null;
    }

    // --- Health probe bridge ----------------------------------------------

    /**
     * Spawn health-probe.sh, parse one-line JSON, return map of probe→status.
     * Empty map = probe failed to spawn (shown as all-grey in UI).
     */
    public Map<String, String> pollHealth() {
        Map<String, String> out = new HashMap<String, String>();
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", HEALTH_PROBE);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            p.waitFor(5, TimeUnit.SECONDS);
            Matcher m = HEALTH_KV_PATTERN.matcher(sb.toString());
            while (m.find()) out.put(m.group(1), m.group(2));
        } catch (IOException e) {
            out.put("error", "io:" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("error", "interrupted");
        }
        return out;
    }

    // --- Helpers ----------------------------------------------------------

    /**
     * Password strength estimate. Only the FIRST 8 CHARS count — the RFB
     * protocol truncates the password at 8 bytes (see wiki/04-gotchas.md G2).
     *
     * Returns one of: "weak", "medium", "strong".
     */
    public static String estimatePasswordStrength(String pw) {
        if (pw == null) return "weak";
        String p = pw.length() > 8 ? pw.substring(0, 8) : pw;
        if (p.length() < 4) return "weak";
        boolean hasLower = false, hasUpper = false, hasDigit = false, hasOther = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if      (Character.isLowerCase(c))  hasLower = true;
            else if (Character.isUpperCase(c))  hasUpper = true;
            else if (Character.isDigit(c))      hasDigit = true;
            else                                hasOther = true;
        }
        int classes = (hasLower?1:0) + (hasUpper?1:0) + (hasDigit?1:0) + (hasOther?1:0);
        if (p.length() >= 8 && classes >= 3) return "strong";
        if (p.length() >= 6 && classes >= 2) return "medium";
        return "weak";
    }

    /** Minimal shell-double-quote escape: \\ $ " ` */
    private static String shellEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("$",  "\\$")
                .replace("\"", "\\\"")
                .replace("`",  "\\`");
    }

    // ======================================================================
    // Sprint 2 (v2.2.0) bridge methods
    // ======================================================================

    /**
     * (B1) Read the last {@code lines} lines of /var/log/urcap-vnc.log.
     *
     * Implemented in pure Java (no tail(1) subprocess) so it works even on
     * stripped-down UR images. Ring-buffers the file so memory is bounded
     * even if the log is hundreds of megabytes (should never happen —
     * logrotate caps us — but safety first).
     *
     * Returns an empty list if the log doesn't exist yet (daemon never ran).
     */
    public List<String> tailLog(int lines) {
        if (lines <= 0) lines = 20;
        Path p = Paths.get(VNC_LOG);
        if (!Files.isReadable(p)) {
            return Collections.emptyList();
        }
        Deque<String> ring = new ArrayDeque<String>(lines);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (ring.size() == lines) ring.removeFirst();
                ring.addLast(line);
            }
        } catch (IOException e) {
            List<String> err = new ArrayList<String>(1);
            err.add("[tailLog] read error: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return err;
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { /* ok */ }
        }
        return new ArrayList<String>(ring);
    }

    /**
     * (B2) Spawn diag-bundle.sh (synchronously). The script redacts secrets,
     * gzips a tarball to /root/urcap-vnc-diag-YYYYMMDD-HHMMSS.tar.gz, and
     * prints that absolute path to stdout.
     *
     * Returns the tar.gz path on success, or a string starting with "ERROR:"
     * on failure — the UI shows the result verbatim.
     *
     * May take 2-4s (collects iptables-save, ss, ps, health-probe). Callers
     * should invoke from a worker thread to avoid freezing the EDT.
     */
    public String runDiagBundle() {
        if (!Files.isExecutable(Paths.get(DIAG_BUNDLE))) {
            return "ERROR: diag-bundle.sh nie je executable (" + DIAG_BUNDLE + "). "
                    + "Spusti post-install.sh cez SSH.";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", DIAG_BUNDLE);
            pb.redirectErrorStream(false); // keep stderr separate so stdout = path only
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            BufferedReader or = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = or.readLine()) != null) { out.append(line).append('\n'); }
            while ((line = er.readLine()) != null) { err.append(line).append('\n'); }
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "ERROR: diag-bundle timeout (>30s)"; }
            if (p.exitValue() != 0) {
                return "ERROR: diag-bundle exit=" + p.exitValue() + " — " + err.toString().trim();
            }
            String path = out.toString().trim();
            if (path.isEmpty()) return "ERROR: diag-bundle neprodukovalo output";
            return path;
        } catch (IOException e) {
            return "ERROR: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted";
        }
    }

    /**
     * (B3) Add a one-off iptables ACCEPT for {@code ip} that expires in
     * {@code ttlSec} seconds. Comment is a short free-text describing who
     * this whitelist is for (vendor name, ticket number, etc.).
     *
     * Runs the root-owned temp-allowlist-add.sh. (Needs sudo-less root path;
     * on UR e-Series Polyscope runs as root, and the URCap daemon extraction
     * lives under /root/.urcaps — so the UI is de-facto running in a context
     * that can invoke iptables. On non-root test boxes this will fail with
     * exit code 3 and surface as an error message.)
     *
     * Returns {@code null} on success, or a human-readable error otherwise.
     */
    public String addTempAllowlist(String ip, int ttlSec, String comment) {
        // Defensive — same validation as shell, but surface errors in the UI
        if (ip == null || !IXROUTER_IP_PATTERN.matcher(ip.trim()).matches()) {
            return "Neplatná IPv4 adresa (očakávané napr. 203.0.113.17).";
        }
        if (ttlSec <= 0 || ttlSec > TTL_MAX) {
            return "TTL mimo rozsahu (1.." + TTL_MAX + " s).";
        }
        if (comment == null) comment = "";
        if (comment.length() > 64) comment = comment.substring(0, 64);
        // Only allow printable ASCII in comment — keep it simple for the tab-separated log file
        comment = comment.replaceAll("[\\t\\r\\n]", " ").replaceAll("[^\\x20-\\x7E]", "");

        if (!Files.isExecutable(Paths.get(TEMP_ALLOW_ADD))) {
            return "temp-allowlist-add.sh nie je executable (" + TEMP_ALLOW_ADD + "). "
                    + "Spusti post-install.sh cez SSH.";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", TEMP_ALLOW_ADD,
                    ip.trim(), String.valueOf(ttlSec), comment);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            StringBuilder err = new StringBuilder();
            BufferedReader or = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = or.readLine()) != null) { /* discard JSON — we only care about rc */ }
            while ((line = er.readLine()) != null) { err.append(line).append('\n'); }
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "Timeout pri pridávaní pravidla (>10s)"; }
            int rc = p.exitValue();
            if (rc == 0) return null;
            if (rc == 2) return "Validácia zlyhala na strane shellu — IP/TTL format.";
            if (rc == 3) return "iptables -I zlyhalo (nie root? nie je iptables?). Detail: " + err.toString().trim();
            return "temp-allowlist-add exit=" + rc + " — " + err.toString().trim();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + " — " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    /**
     * (B3) List currently-active temp allowlist entries by parsing the
     * tab-separated {@code /var/log/urcap-vnc-temp-allowlist} file.
     *
     * Each returned row is {@code [ip, expiryEpochSec, remainingSec, comment]}
     * — all strings, because the UI table renders text. Expired entries are
     * filtered out (they'll be reaped by the sweeper within a minute anyway).
     *
     * Returns empty list if the file doesn't exist (no temp rules ever added
     * on this robot).
     */
    public List<String[]> listTempAllowlist() {
        List<String[]> out = new ArrayList<String[]>();
        Path p = Paths.get(TEMP_ALLOW_FILE);
        if (!Files.isReadable(p)) return out;
        long now = System.currentTimeMillis() / 1000L;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t", 3);
                if (f.length < 2) continue;
                long expiry;
                try { expiry = Long.parseLong(f[0].trim()); }
                catch (NumberFormatException e) { continue; }
                if (expiry <= now) continue; // expired — hide
                String ip      = f[1].trim();
                String comment = f.length >= 3 ? f[2] : "";
                long remain    = expiry - now;
                out.add(new String[] { ip, String.valueOf(expiry), String.valueOf(remain), comment });
            }
        } catch (IOException e) {
            // surface read errors as a single synthetic row so the UI doesn't silently say "žiadne"
            out.add(new String[] { "-", "0", "0", "[read error] " + e.getMessage() });
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { /* ok */ }
        }
        return out;
    }

    /**
     * (B6) RFB handshake probe. Spawns vnc-test.sh which opens a TCP socket
     * to 127.0.0.1:$VNC_PORT and expects the "RFB 003.008" banner.
     *
     * Returns a short human-readable string like "OK (RFB 003.008 on 5900)"
     * or a failure description. Always includes the port so the user can
     * sanity-check it matches their Settings input.
     */
    public String testConnection() {
        if (!Files.isExecutable(Paths.get(VNC_TEST))) {
            return "vnc-test.sh nie je executable (" + VNC_TEST + ").";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", VNC_TEST);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            boolean done = p.waitFor(8, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "Test timeout (>8s)"; }
            int rc = p.exitValue();
            String json = sb.toString();
            // Parse status field — rc tells us which branch the script took
            if (rc == 0) {
                Matcher proto = Pattern.compile("\"protocol\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                Matcher port  = Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(json);
                String protoStr = proto.find() ? proto.group(1) : "RFB ???";
                String portStr  = port.find()  ? port.group(1)  : String.valueOf(getPort());
                return "✓ OK — " + protoStr + " na porte " + portStr;
            }
            if (rc == 1) return "✗ Pripojenie odmietnuté — daemon pravdepodobne neštartuje. Pozri log.";
            if (rc == 2) return "✗ Pripojenie OK, ale nevrátil RFB banner (možno iná služba na porte?)";
            return "✗ vnc-test exit=" + rc + " output=" + json;
        } catch (IOException e) {
            return "✗ " + e.getClass().getSimpleName() + " — " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "✗ interrupted";
        }
    }

    // ========================================================================
    // v3.4.0 — Portal pairing + heartbeat
    // ========================================================================
    //
    // Operator types a claim code into the Portal section; we POST to
    // /api/claim/device and persist the returned device_id + token into the
    // Polyscope DataModel. A background thread then posts heartbeats every
    // 30s as long as this Installation view is open.
    //
    // NOTE: token storage is currently plaintext in DataModel (written to
    // /root/installations/*.installation, chmod 600). AES-GCM wrapping is
    // designed in URCAP_V3.4_DESIGN.md §4.2 and is a v3.5 task.

    public boolean isPaired() {
        String tk = get(KEY_PORTAL_TOKEN, "");
        String id = get(KEY_PORTAL_DEVICE_ID, "");
        return tk != null && !tk.isEmpty() && id != null && !id.isEmpty();
    }

    public String getPortalDeviceId() {
        return get(KEY_PORTAL_DEVICE_ID, "");
    }

    public String getPortalDeviceIdMasked() {
        String id = getPortalDeviceId();
        if (id == null || id.length() < 8) return "";
        return id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }

    public String getPortalLastStatus() {
        return get(KEY_PORTAL_LAST_STATUS, "");
    }

    public String getPortalPairedAt() {
        return get(KEY_PORTAL_PAIRED_AT, "");
    }

    /**
     * Exchange a claim code for a device + token, persist, and start heartbeat.
     * Runs on a background thread so the UI doesn't freeze on slow WAN.
     * Called from the view (Swing EDT), callback updates UI via invokeLater.
     */
    public void pairWithCode(final String rawCode, final Runnable onDone) {
        final String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (code.isEmpty()) {
            view.updatePortalPairingResult(false, "Zadaj claim code.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Map<String, String> info = new HashMap<>();
                info.put("model", "UR10e");
                info.put("polyscope_major", "ps5");
                info.put("polyscope_version", System.getProperty("polyscope.version", ""));
                info.put("lan_ip", getLocalIpSafe());
                info.put("hostname", getHostnameSafe());
                info.put("robot_serial", getRobotSerialSafe());

                PortalClient.ClaimResponse resp = portalClient.claim(code, info);

                model.set(KEY_PORTAL_TOKEN, resp.token);
                model.set(KEY_PORTAL_DEVICE_ID, resp.deviceId);
                model.set(KEY_PORTAL_PAIRED_AT, java.time.Instant.now().toString());
                model.set(KEY_PORTAL_LAST_STATUS,
                        resp.reused ? "Re-spárované" : "Spárované");

                EventQueue.invokeLater(() -> {
                    view.updatePortalPaired(true, getPortalDeviceIdMasked(), getPortalLastStatus());
                    view.updatePortalPairingResult(true, resp.reused
                            ? "Re-spárované · device_id " + getPortalDeviceIdMasked()
                            : "Spárované · device_id " + getPortalDeviceIdMasked());
                    startHeartbeatIfPaired();
                    if (onDone != null) onDone.run();
                });
            } catch (PortalClient.ClaimError ce) {
                final String msg = ce.httpStatus == 404
                        ? "Neplatný alebo expirovaný kód."
                        : ce.httpStatus == 429
                            ? "Priveľa pokusov — skús o hodinu."
                            : "Chyba: " + ce.errorCode + " (HTTP " + ce.httpStatus + ")";
                EventQueue.invokeLater(() -> {
                    view.updatePortalPairingResult(false, msg);
                    if (onDone != null) onDone.run();
                });
            } catch (Exception e) {
                final String msg = "Sieť / IO chyba: " + e.getClass().getSimpleName()
                        + " — " + (e.getMessage() == null ? "?" : e.getMessage());
                EventQueue.invokeLater(() -> {
                    view.updatePortalPairingResult(false, msg);
                    if (onDone != null) onDone.run();
                });
            }
        }, "stimba-portal-pair");
        t.setDaemon(true);
        t.start();
    }

    public void unpair() {
        final String token = get(KEY_PORTAL_TOKEN, "");
        final String deviceId = get(KEY_PORTAL_DEVICE_ID, "");

        stopHeartbeat();

        if (!token.isEmpty() && !deviceId.isEmpty()) {
            Thread t = new Thread(() -> portalClient.unpair(token, deviceId), "stimba-portal-unpair");
            t.setDaemon(true);
            t.start();
        }

        model.set(KEY_PORTAL_TOKEN, "");
        model.set(KEY_PORTAL_DEVICE_ID, "");
        model.set(KEY_PORTAL_PAIRED_AT, "");
        model.set(KEY_PORTAL_LAST_STATUS, "Odpojené");
        view.updatePortalPaired(false, "", "Odpojené");
    }

    private void startHeartbeatIfPaired() {
        if (!isPaired() || portalHeartbeat != null) return;
        portalHeartbeat = new PortalHeartbeatRunner(
                portalClient,
                dashboardClient,
                () -> get(KEY_PORTAL_TOKEN, ""),
                () -> get(KEY_PORTAL_DEVICE_ID, ""),
                (status) -> {
                    model.set(KEY_PORTAL_LAST_STATUS, status);
                    EventQueue.invokeLater(() -> view.updatePortalStatus(status));
                }
        );
        portalHeartbeat.start();
    }

    private void stopHeartbeat() {
        if (portalHeartbeat != null) {
            portalHeartbeat.stop();
            portalHeartbeat = null;
        }
    }

    // --- helper: safe string get with default (DataModel 2-arg primitive
    //     overload — same pattern as getPort / getPassword above) ---
    private String get(String key, String dflt) {
        try {
            return model.get(key, dflt);
        } catch (Throwable t) {
            return dflt;
        }
    }

    // --- best-effort local-network helpers (never throw) ---
    private static String getLocalIpSafe() {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
            return addr.getHostAddress();
        } catch (Throwable t) { return ""; }
    }

    private static String getHostnameSafe() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Throwable t) { return ""; }
    }

    /** Reads UR robot serial from Polyscope's serial file if available (Java 8
     *  compatible: Files.readString is Java 11+). */
    private static String getRobotSerialSafe() {
        try {
            Path p = Paths.get("/root/.urcontrol/serial-number.txt");
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable ignored) {}
        try {
            Path p = Paths.get("/etc/machine-id");
            if (Files.exists(p)) {
                String id = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
                return id.length() > 16 ? id.substring(0, 16) : id;
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
