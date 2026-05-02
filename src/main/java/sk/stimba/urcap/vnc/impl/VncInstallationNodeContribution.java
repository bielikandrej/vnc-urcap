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

    // --- v3.12.0 VNC Relay (reverse tunnel to Fly.io relay) ---------------
    // Replaces the IXON VNC path for customers whose IXON URCap broke after
    // Polyscope 5.20+ updates. URCap initiates outbound WSS to relay, which
    // then forwards browser noVNC frames to local x11vnc:5900.
    private static final String KEY_RELAY_ENABLED = "relay.enabled";
    private static final String KEY_RELAY_URL     = "relay.url";

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
    private static final int     DEFAULT_MAX_CLIENTS      = 5;                 // v3.12.20 (was 1 in v3.0.0) — multi-viewer default; many ops want >1 simultaneous tabs
    // v3.12.0 — relay opt-in by default (operator can disable per robot)
    private static final boolean DEFAULT_RELAY_ENABLED    = true;
    private static final String  DEFAULT_RELAY_URL        = "wss://stimba-vnc-relay.fly.dev";

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

    // v3.5.0 — token at-rest wrap + command poll
    private TokenCrypto tokenCrypto;
    private DashboardCommandPoller commandPoller;

    // v3.6.0 — Primary Interface client for urscript_send + IO via URScript
    private final PrimaryInterfaceClient primaryClient = new PrimaryInterfaceClient();

    // v3.7.0 — RTDE reader for live TCP pose / joint / force / I/O telemetry
    private RtdeReader rtdeReader;

    // v3.10.0 — auto-discovery probe (one-shot per connection) + log tailer
    private RobotMetadataProbe metadataProbe;
    private PolyscopeLogTailer logTailer;
    private final InstallationAPIProvider apiProviderRef;

    // v3.12.0 — VNC relay tunnel client
    private VncTunnelClient vncTunnel;

    public VncInstallationNodeContribution(InstallationAPIProvider apiProvider,
                                           VncInstallationNodeView view,
                                           DataModel model,
                                           VncDaemonService daemonService,
                                           CreationContext context) {
        this.model = model;
        this.view = view;
        this.daemonService = daemonService;
        this.apiProviderRef = apiProvider;

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
            // v3.12.0 — relay defaults
            model.set(KEY_RELAY_ENABLED,    DEFAULT_RELAY_ENABLED);
            model.set(KEY_RELAY_URL,        DEFAULT_RELAY_URL);
        }

        // Fire up the daemon automatically if autostart is set
        applyDesiredDaemonStatus();

        // v3.12.21 — start the relay tunnel + portal heartbeat at contribution
        // load time, NOT just at openView(). The original v3.4.0 design assumed
        // an operator would always navigate to the URCap installation tab to
        // "wake" the relay; in practice the URCap is fully headless on most
        // robots and no tablet user ever touches the URCap node, so the
        // tunnel was never starting after Polyscope restarts. v3.12.20's
        // watchdog covers a connector that died mid-flight, but it can't
        // help if start() was never called in the first place. Calling
        // startHeartbeatIfPaired() here means: if the URCap was paired with
        // portal.stimba.sk in a prior session, the tunnel comes up the
        // moment Polyscope finishes loading the installation file.
        // openView() still calls it (idempotent — guarded by null checks
        // inside), so a user opening the tab still works as before.
        try {
            startHeartbeatIfPaired();
        } catch (Throwable t) {
            // Bundle activation must NEVER fail because of relay startup —
            // x11vnc and the rest of the URCap should still work even if
            // we somehow can't reach portal/relay yet. Log and move on.
            java.util.logging.Logger
                    .getLogger(VncInstallationNodeContribution.class.getName())
                    .warning("v3.12.21 auto-start heartbeat failed (non-fatal): "
                            + t.getClass().getSimpleName() + " " + t.getMessage());
        }
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
                // v3.12.11 — capture relay state on the same tick so UI sees
                // both shell probes and in-memory tunnel status in one refresh.
                final boolean tunnelRunning = isRelayTunnelRunning();
                final boolean tunnelConnected = isRelayConnected();
                final String  tunnelStatus    = getRelayStatus();
                final String  tunnelLastError = getRelayLastError();
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        view.updateHealth(probed);
                        view.updateRelay(tunnelRunning, tunnelConnected, tunnelStatus, tunnelLastError);
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

    // --- v3.12.0 — VNC relay accessors ------------------------------------
    // Operator can disable the relay per robot; default true. URL accepts
    // only ws:// or wss:// schemes so a typo can't silently point the tunnel
    // at the portal HTTPS origin.

    public boolean isRelayEnabled() { return model.get(KEY_RELAY_ENABLED, DEFAULT_RELAY_ENABLED); }
    public void setRelayEnabled(boolean v) { model.set(KEY_RELAY_ENABLED, v); }

    public String getRelayUrl() { return get(KEY_RELAY_URL, DEFAULT_RELAY_URL); }
    public boolean setRelayUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (!(u.startsWith("wss://") || u.startsWith("ws://"))) return false;
        model.set(KEY_RELAY_URL, u);
        return true;
    }

    public boolean isRelayConnected() {
        return vncTunnel != null && vncTunnel.isConnected();
    }

    // v3.12.11 — surface tunnel state to the view's "Stav démona" panel and
    // (via PortalHeartbeatRunner) to the portal device card.  Each accessor
    // returns "—" / null safely if the tunnel hasn't been wired up yet.
    public boolean isRelayTunnelRunning() {
        return vncTunnel != null && vncTunnel.isRunning();
    }
    public String getRelayStatus() {
        return vncTunnel == null ? "—" : vncTunnel.getCurrentStatus();
    }
    public String getRelayLastError() {
        return vncTunnel == null ? null : vncTunnel.getLastError();
    }
    public long getRelayLastErrorAt() {
        return vncTunnel == null ? 0L : vncTunnel.getLastErrorAt();
    }
    public long getRelayLastConnectedAt() {
        return vncTunnel == null ? 0L : vncTunnel.getLastConnectedAt();
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
        // v3.12.7 — scripts are invoked via `/bin/bash <path>`, so Unix +x
        // bit is irrelevant to ProcessBuilder. Drop the Files.isExecutable
        // precheck that was gating the UI with a misleading red banner on
        // URCap installs where Polyscope extracts helpers at 644.
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

        // v3.12.7 — same rationale as runDiagBundle: /bin/bash ignores +x.
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
        // v3.12.7 — /bin/bash invocation ignores +x, see runDiagBundle.
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

                // v3.5.0 — wrap token with AES-GCM before persisting to DataModel.
                // KDF seed is derived from robot serial + paired-at timestamp so
                // URCap reinstall / different robot can't read the ciphertext.
                String pairedAt = java.time.Instant.now().toString();
                model.set(KEY_PORTAL_PAIRED_AT, pairedAt);
                tokenCrypto = new TokenCrypto(getRobotSerialSafe(), pairedAt);
                String wrapped = tokenCrypto.wrap(resp.token);
                model.set(KEY_PORTAL_TOKEN, wrapped == null ? resp.token : wrapped);
                model.set(KEY_PORTAL_DEVICE_ID, resp.deviceId);
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
        final String unwrappedToken = getUnwrappedToken();
        final String deviceId = get(KEY_PORTAL_DEVICE_ID, "");

        stopHeartbeat();

        if (!unwrappedToken.isEmpty() && !deviceId.isEmpty()) {
            Thread t = new Thread(() -> portalClient.unpair(unwrappedToken, deviceId), "stimba-portal-unpair");
            t.setDaemon(true);
            t.start();
        }

        model.set(KEY_PORTAL_TOKEN, "");
        model.set(KEY_PORTAL_DEVICE_ID, "");
        model.set(KEY_PORTAL_PAIRED_AT, "");
        model.set(KEY_PORTAL_LAST_STATUS, "Odpojené");
        tokenCrypto = null;
        view.updatePortalPaired(false, "", "Odpojené");
    }

    private void startHeartbeatIfPaired() {
        if (!isPaired()) return;
        // v3.7.0 — RTDE reader best-effort. If it fails to start (older CB3
        // without RTDE, firewall), the URCap still works at the v3.6 feature
        // set; the heartbeat reports rtdeConnected=false and the Brain page
        // shows "sensor gone".
        if (rtdeReader == null) {
            try {
                rtdeReader = new RtdeReader();
                rtdeReader.start();
            } catch (Throwable t) {
                rtdeReader = null;
            }
        }
        if (portalHeartbeat == null) {
            portalHeartbeat = new PortalHeartbeatRunner(
                    portalClient,
                    dashboardClient,
                    () -> getUnwrappedToken(),
                    () -> get(KEY_PORTAL_DEVICE_ID, ""),
                    () -> TokenCrypto.sha256Hex(getPassword()),
                    (status) -> {
                        model.set(KEY_PORTAL_LAST_STATUS, status);
                        EventQueue.invokeLater(() -> view.updatePortalStatus(status));
                    }
            );
            portalHeartbeat.attachRtde(rtdeReader);

            // v3.10.0 — auto-discovery probe + log tailer. Both are best-effort:
            // if SystemAPI isn't available (degraded controller) or log files
            // aren't readable (filesystem issue), we skip and the heartbeat
            // continues with v3.7-level payload. No fatal path.
            if (metadataProbe == null && apiProviderRef != null) {
                try {
                    metadataProbe = new RobotMetadataProbe(apiProviderRef);
                } catch (Throwable t) { metadataProbe = null; }
            }
            if (logTailer == null) {
                try {
                    logTailer = new PolyscopeLogTailer();
                } catch (Throwable t) { logTailer = null; }
            }
            portalHeartbeat.attachDiscovery(metadataProbe, logTailer);

            portalHeartbeat.start();
        }
        if (commandPoller == null) {
            commandPoller = new DashboardCommandPoller(
                    portalClient,
                    dashboardClient,
                    () -> getUnwrappedToken(),
                    () -> get(KEY_PORTAL_DEVICE_ID, ""),
                    (toolName, argsJson) -> dispatchAgentCommand(toolName, argsJson)
            );
            commandPoller.start();
        }

        // v3.12.0 — VNC relay reverse tunnel. Runs 24/7 while paired; when no
        // browser is attached on the relay side, the agent socket just sits
        // idle. On browser connect the relay pipes bytes to local x11vnc.
        // Reconnects automatically with exponential backoff on any failure.
        if (vncTunnel == null) {
            vncTunnel = new VncTunnelClient(
                    () -> getUnwrappedToken(),
                    () -> get(KEY_PORTAL_DEVICE_ID, ""),
                    () -> getRelayUrl(),
                    () -> getPort(),
                    () -> isRelayEnabled(),
                    (status) -> java.util.logging.Logger
                            .getLogger(VncTunnelClient.class.getName())
                            .info("[relay-status] " + status)
            );
            vncTunnel.start();
            portalHeartbeat.attachTunnel(vncTunnel);
        }
    }

    private void stopHeartbeat() {
        if (vncTunnel != null) {
            vncTunnel.stop();
            vncTunnel = null;
        }
        if (portalHeartbeat != null) {
            portalHeartbeat.stop();
            portalHeartbeat = null;
        }
        if (commandPoller != null) {
            commandPoller.stop();
            commandPoller = null;
        }
        if (rtdeReader != null) {
            rtdeReader.stop();
            rtdeReader = null;
        }
    }

    /** v3.5.0 — token is stored wrapped (AES-GCM); unwrap lazily on every call. */
    private String getUnwrappedToken() {
        String wrapped = get(KEY_PORTAL_TOKEN, "");
        if (wrapped.isEmpty()) return "";
        if (!wrapped.startsWith("v1.")) {
            // v3.4.0 plaintext token — accept for backward compat, will be re-wrapped on next pair
            return wrapped;
        }
        ensureTokenCrypto();
        return tokenCrypto.unwrap(wrapped).orElse("");
    }

    private void ensureTokenCrypto() {
        if (tokenCrypto == null) {
            String serial = getRobotSerialSafe();
            String installSalt = get(KEY_PORTAL_PAIRED_AT, "urcap-default-install");
            tokenCrypto = new TokenCrypto(serial, installSalt);
        }
    }

    /**
     * v3.5.0 — dispatch an agent command from the portal queue to the robot.
     * Returns an Ack that the poller PATCHes back to portal.
     */
    private DashboardCommandPoller.Ack dispatchAgentCommand(String toolName, String argsJson) {
        try {
            switch (toolName == null ? "" : toolName) {
                case "dashboard_power_on":
                    return ackFromDashboard(dashboardClient.execute("power on"));
                case "dashboard_power_off":
                    // v3.12.9 — paired counterpart to power_on. UR Polyscope Local
                    // Control mode allows this only from a 127.0.0.1 client (which
                    // URCap is). External Dashboard clients (via IXrouter VPN) get
                    // "Command is not allowed due to safety reasons" without
                    // Remote Control mode toggled. Going through URCap unblocks
                    // remote-from-portal power-off without that pendant toggle.
                    return ackFromDashboard(dashboardClient.execute("power off"));
                case "dashboard_brake_release":
                    return ackFromDashboard(dashboardClient.execute("brake release"));
                case "dashboard_close_safety_popup":
                    // v3.12.9 — clears the "Confirm Safety Configuration" popup
                    // that pops up after a safety reset. Idempotent.
                    return ackFromDashboard(dashboardClient.execute("close safety popup"));
                case "dashboard_unlock_protective_stop":
                    // v3.12.9 — releases protective-stop state set by force /
                    // collision triggers. UR best practice: wait 5 s before
                    // re-issuing motion commands. Caller should pace.
                    return ackFromDashboard(dashboardClient.execute("unlock protective stop"));
                case "dashboard_safetymode": {
                    // v3.12.9 — was hardcoded "safetymode normal". Now accepts
                    // an optional `mode` arg: normal | reduced | recovery |
                    // safeguard_stop | system_emergency_stop |
                    // robot_emergency_stop | violation | fault. Defaults to
                    // "normal" for backward compat with v3.6 callers.
                    String mode = extractArgString(argsJson, "mode");
                    if (mode == null || mode.isEmpty()) mode = "normal";
                    // Whitelist accepted modes — Dashboard 'safetymode' actually
                    // ONLY accepts these in command form. Anything else triggers
                    // a verbose error from URControl. Better to reject early.
                    boolean ok = false;
                    String[] allowed = { "normal", "reduced", "recovery" };
                    for (String a : allowed) if (a.equals(mode)) { ok = true; break; }
                    if (!ok) {
                        return new DashboardCommandPoller.Ack(
                            false, null,
                            "dashboard_safetymode: mode must be one of normal | reduced | recovery (got: " + mode + ")"
                        );
                    }
                    return ackFromDashboard(dashboardClient.execute("safetymode " + mode));
                }
                case "dashboard_shutdown":
                    // v3.12.9 — DESTRUCTIVE. Shuts down the controller; needs
                    // physical button to boot back. Use only when the operator
                    // explicitly asks (UI must confirm before sending). Caller
                    // ack arrives almost instantly because URControl just calls
                    // `shutdown -h now` async; the daemon supervisor will tear
                    // the URCap down within ~10 s after.
                    return ackFromDashboard(dashboardClient.execute("shutdown"));
                case "program_load": {
                    String name = extractArgString(argsJson, "program_name");
                    if (name == null || name.isEmpty()) {
                        return new DashboardCommandPoller.Ack(false, null, "missing program_name");
                    }
                    if (!name.endsWith(".urp") && !name.endsWith(".urpx")) name = name + ".urp";
                    return ackFromDashboard(dashboardClient.execute("load " + name));
                }
                case "program_play":
                    return ackFromDashboard(dashboardClient.execute("play"));
                case "program_pause":
                    return ackFromDashboard(dashboardClient.execute("pause"));
                case "program_stop":
                    return ackFromDashboard(dashboardClient.execute("stop"));
                case "set_vnc_password": {
                    String pwd = extractArgString(argsJson, "password");
                    if (pwd == null || pwd.isEmpty()) {
                        return new DashboardCommandPoller.Ack(false, null, "missing password");
                    }
                    setVncPassword(pwd);
                    return new DashboardCommandPoller.Ack(true, "vnc password rotated", null);
                }
                case "io_set_digital_out": {
                    // v3.6.0 — synthesize 1-line URScript + send via Primary
                    String idxStr = extractArgString(argsJson, "index");
                    String valStr = extractArgString(argsJson, "value");
                    Integer idx = null;
                    try { idx = idxStr == null ? null : Integer.parseInt(idxStr.trim()); } catch (Throwable ignored) {}
                    Boolean val = null;
                    if (valStr != null) {
                        String v = valStr.trim().toLowerCase();
                        if ("true".equals(v) || "1".equals(v))  val = Boolean.TRUE;
                        if ("false".equals(v) || "0".equals(v)) val = Boolean.FALSE;
                    }
                    if (idx == null || idx < 0 || idx > 7) {
                        return new DashboardCommandPoller.Ack(false, null, "io_set_digital_out: index must be 0..7");
                    }
                    if (val == null) {
                        return new DashboardCommandPoller.Ack(false, null, "io_set_digital_out: value must be true|false");
                    }
                    try {
                        primaryClient.sendOneLiner("set_digital_out(" + idx + ", " + (val ? "True" : "False") + ")");
                        return new DashboardCommandPoller.Ack(true,
                                "DO" + idx + " = " + val, null);
                    } catch (IOException ioe) {
                        return new DashboardCommandPoller.Ack(false, null,
                                "Primary Interface unreachable: " + ioe.getMessage());
                    }
                }
                case "urscript_send": {
                    // v3.6.0 — raw URScript execution via Primary Interface :30001.
                    // Portal has already gated on ai.execute permission + blacklist +
                    // length limit. We're a dumb pipe here; we do one last sanity
                    // check on length to keep a misbehaving portal from flooding.
                    String script = extractArgString(argsJson, "script");
                    if (script == null || script.isEmpty()) {
                        return new DashboardCommandPoller.Ack(false, null, "urscript_send: missing 'script' arg");
                    }
                    if (script.length() > 16_384) {
                        return new DashboardCommandPoller.Ack(false, null, "urscript_send: script >16KB, refusing");
                    }
                    try {
                        primaryClient.sendScript(script);
                        return new DashboardCommandPoller.Ack(true,
                                "URScript sent (" + script.length() + " bytes)", null);
                    } catch (IOException ioe) {
                        return new DashboardCommandPoller.Ack(false, null,
                                "Primary Interface unreachable: " + ioe.getMessage());
                    }
                }
                case "set_tool_digital_out": {
                    // v3.7.0 — tool flange DO (indices 0..1).
                    String idxStr = extractArgString(argsJson, "pin");
                    if (idxStr == null) idxStr = extractArgString(argsJson, "index");
                    String valStr = extractArgString(argsJson, "value");
                    Integer idx = null;
                    try { idx = idxStr == null ? null : Integer.parseInt(idxStr.trim()); } catch (Throwable ignored) {}
                    Boolean val = null;
                    if (valStr != null) {
                        String v = valStr.trim().toLowerCase();
                        if ("true".equals(v) || "1".equals(v))  val = Boolean.TRUE;
                        if ("false".equals(v) || "0".equals(v)) val = Boolean.FALSE;
                    }
                    if (idx == null || idx < 0 || idx > 1) {
                        return new DashboardCommandPoller.Ack(false, null, "set_tool_digital_out: index must be 0..1");
                    }
                    if (val == null) {
                        return new DashboardCommandPoller.Ack(false, null, "set_tool_digital_out: value must be true|false");
                    }
                    try {
                        primaryClient.sendOneLiner("set_tool_digital_out(" + idx + ", " + (val ? "True" : "False") + ")");
                        return new DashboardCommandPoller.Ack(true,
                                "TDO" + idx + " = " + val, null);
                    } catch (IOException ioe) {
                        return new DashboardCommandPoller.Ack(false, null,
                                "Primary Interface unreachable: " + ioe.getMessage());
                    }
                }
                case "program_list": {
                    // v3.7.0 — list .urp / .urpx programs on the controller.
                    // Uses Dashboard Server 'programState' is too narrow; we read the
                    // Polyscope programs directory directly since URCap runs on the
                    // controller with filesystem access.
                    java.io.File dir = new java.io.File("/programs");
                    if (!dir.exists() || !dir.isDirectory()) {
                        // Fallback for URSim home install
                        dir = new java.io.File(System.getProperty("user.home", "") + "/ursim/programs");
                    }
                    if (!dir.exists() || !dir.isDirectory()) {
                        return new DashboardCommandPoller.Ack(false, null,
                                "program_list: /programs not found on this controller");
                    }
                    java.io.File[] files = dir.listFiles((d, name) ->
                            name.toLowerCase().endsWith(".urp") || name.toLowerCase().endsWith(".urpx"));
                    if (files == null) files = new java.io.File[0];
                    StringBuilder sb = new StringBuilder("{\"programs\":[");
                    for (int i = 0; i < files.length; i++) {
                        if (i > 0) sb.append(",");
                        String nm = files[i].getName();
                        int dot = nm.lastIndexOf('.');
                        if (dot > 0) nm = nm.substring(0, dot);
                        sb.append("\"").append(nm.replace("\"", "")).append("\"");
                    }
                    sb.append("],\"count\":").append(files.length).append("}");
                    return new DashboardCommandPoller.Ack(true, sb.toString(), null);
                }
                case "panic_halt": {
                    // v3.7.0 — composite kill-switch. The portal /api/devices/:id/panic
                    // already enqueues two discrete Dashboard commands
                    // (program_stop + dashboard_safetymode), so this case is only
                    // hit by callers that send panic_halt directly (future MCP /
                    // SDK). Reply success after best-effort sending.
                    StringBuilder out = new StringBuilder();
                    try {
                        String r1 = dashboardClient.execute("stop");
                        out.append("stop: ").append(r1 == null ? "" : r1.trim()).append("; ");
                    } catch (Throwable t) {
                        out.append("stop: ").append(t.getClass().getSimpleName()).append("; ");
                    }
                    try {
                        String r2 = dashboardClient.execute("safetymode normal");
                        out.append("safetymode: ").append(r2 == null ? "" : r2.trim());
                    } catch (Throwable t) {
                        out.append("safetymode: ").append(t.getClass().getSimpleName());
                    }
                    return new DashboardCommandPoller.Ack(true, out.toString(), null);
                }
                case "install_urcap": {
                    // v3.12.16 — remote URCap install. Portal sends:
                    //   { url: "https://github.com/.../<file>.urcap",
                    //     sha256: "<64-hex>", name: "<file>.urcap" }
                    // URCap downloads, verifies, writes to /root/.urcaps/, then
                    // schedules a controller restart so Polyscope reloads URCaps.
                    // See wiki/06-remote-fileops-api.md for the full contract.
                    String url      = extractArgString(argsJson, "url");
                    String sha256   = extractArgString(argsJson, "sha256");
                    String name     = extractArgString(argsJson, "name");
                    RemoteUrcapInstaller.InstallResult ir =
                            RemoteUrcapInstaller.install(url, sha256, name);
                    if (!ir.ok) {
                        return new DashboardCommandPoller.Ack(false, null, ir.message);
                    }
                    // Schedule the restart AFTER we return Ack — the poller has
                    // ~5 s to PATCH the ack back before urcontrol.service goes
                    // down and takes URCap with it.
                    RemoteUrcapInstaller.scheduleControllerRestart();
                    return new DashboardCommandPoller.Ack(true, ir.message, null);
                }
                case "file_list": {
                    // v3.12.16 — list directory under the file-ops allowlist.
                    String path = extractArgString(argsJson, "path");
                    RemoteFileOps.OpResult r = RemoteFileOps.list(path);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                case "file_stat": {
                    String path = extractArgString(argsJson, "path");
                    RemoteFileOps.OpResult r = RemoteFileOps.stat(path);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                case "file_read": {
                    // args: { path, offset (string-int, default 0), length (string-int) }
                    String path     = extractArgString(argsJson, "path");
                    String offStr   = extractArgString(argsJson, "offset");
                    String lenStr   = extractArgString(argsJson, "length");
                    long offset = 0;
                    int  length = RemoteFileOps.MAX_READ_BYTES;
                    try { if (offStr != null) offset = Long.parseLong(offStr.trim()); } catch (Throwable ignored) {}
                    try { if (lenStr != null) length = Integer.parseInt(lenStr.trim()); } catch (Throwable ignored) {}
                    RemoteFileOps.OpResult r = RemoteFileOps.read(path, offset, length);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                case "file_write": {
                    // args: { path, offset (string-int, default 0), b64,
                    //         total_size? (string-int), truncate? (bool) }
                    String path      = extractArgString(argsJson, "path");
                    String offStr    = extractArgString(argsJson, "offset");
                    String b64       = extractArgString(argsJson, "b64");
                    String totalStr  = extractArgString(argsJson, "total_size");
                    String truncStr  = extractArgString(argsJson, "truncate");
                    long offset = 0;
                    try { if (offStr != null) offset = Long.parseLong(offStr.trim()); } catch (Throwable ignored) {}
                    Long expectedTotal = null;
                    try { if (totalStr != null) expectedTotal = Long.parseLong(totalStr.trim()); } catch (Throwable ignored) {}
                    boolean truncate = "true".equalsIgnoreCase(truncStr == null ? "false" : truncStr.trim());
                    RemoteFileOps.OpResult r =
                            RemoteFileOps.write(path, offset, b64, expectedTotal, truncate);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                case "file_delete": {
                    String path = extractArgString(argsJson, "path");
                    RemoteFileOps.OpResult r = RemoteFileOps.delete(path);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                case "file_mkdir": {
                    String path = extractArgString(argsJson, "path");
                    RemoteFileOps.OpResult r = RemoteFileOps.mkdir(path);
                    return r.ok
                        ? new DashboardCommandPoller.Ack(true, r.resultJson, null)
                        : new DashboardCommandPoller.Ack(false, null, r.errorMessage);
                }
                default:
                    return new DashboardCommandPoller.Ack(false, null, "unknown tool: " + toolName);
            }
        } catch (Throwable t) {
            return new DashboardCommandPoller.Ack(false, null, "exception: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static DashboardCommandPoller.Ack ackFromDashboard(String response) {
        if (response == null) {
            return new DashboardCommandPoller.Ack(false, null, "no response from Dashboard Server");
        }
        // PS Dashboard responses: success = prefixed with verb (e.g. "Powering on"),
        // failure = starts with "Failed" or "error".
        String lower = response.toLowerCase();
        boolean ok = !(lower.startsWith("failed") || lower.startsWith("error") ||
                       lower.contains("no program loaded"));
        return new DashboardCommandPoller.Ack(ok, response, ok ? null : response);
    }

    private static String extractArgString(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); continue; }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return null;
    }

    /**
     * v3.5.0 — set VNC password via x11vnc -storepasswd file + daemon restart.
     * Path must match the run-vnc.sh -rfbauth flag; we persist to the same
     * CONFIG_FILE consumed by the daemon.
     */
    private void setVncPassword(String newPassword) {
        // Persist into DataModel so the setting survives reboots.
        model.set(KEY_PASSWORD, newPassword);
        // Write config file (same path daemon reads) and ask it to restart.
        try {
            writeConfigFile();
        } catch (Throwable t) {
            // Best effort — even if write failed, model update is persisted
        }
        // Bounce the daemon so x11vnc picks up the new -rfbauth password.
        // Use stop()/start() methods directly (DaemonContribution API in this
        // URCap stub doesn't expose setDesiredState).
        try {
            DaemonContribution d = daemonService.getDaemon();
            if (d != null) {
                d.stop();
                Thread.sleep(1000);
                d.start();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // If daemon restart fails, next heartbeat will still reflect old
            // password hash — portal will keep weak=true until operator retries.
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
