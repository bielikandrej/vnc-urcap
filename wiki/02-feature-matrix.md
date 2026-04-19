# 02 — Feature Matrix

Status legenda:
- ✅ shipped (v prod alebo stable build)
- 🔨 in progress
- 📋 planned (má sprint ticket)
- ⛔ out of scope (explicitne nechceme — v najbližšej dohľadnej verzii sa to nerobí)

## Vrstva A — UI & základná funkcionalita

| ID | Popis | Status | Verzia | Súbory |
|---|---|---|---|---|
| A1 | UI → daemon bridge (write `/var/lib/urcap-vnc/config` z Javy, reštart daemona) | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeContribution.java`, `run-vnc.sh`, `stop-vnc.sh`, `post-install.sh` |
| A2 | `IXROUTER_IP` text field v UI | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeView.java`, `VncInstallationNodeContribution.java` |
| A3 | Customer label (`CUSTOMER_LABEL` — ide do `LOG_TAG`, ps, log header) | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeView.java`, `run-vnc.sh` (update LOG_TAG) |
| A4 | Health panel: 5 indicators (daemon / iptables / port / DISPLAY / IXrouter ping) | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeView.java`, `health-probe.sh` (new) |
| A5 | easybot banner + `URCAP_VNC_REQUIRE_STRONG_PWD` toggle | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeView.java`, `run-vnc.sh` (tripwire existuje od 2.0.1) |

## Vrstva B — Observability & operation

| ID | Popis | Status | Verzia | Súbory |
|---|---|---|---|---|
| B1 | Live log viewer (posledných 50 riadkov `/var/log/urcap-vnc.log`, refresh 3s opt-in) | ✅ v2.2.0 | 2.2.0 | `VncInstallationNodeView.java` (`buildLogTailPanel`), `VncInstallationNodeContribution.tailLog()` — Deque ring buffer, pure Java (no `tail(1)` subprocess) |
| B2 | "Export diagnostiku" — .tar.gz na `/root/` (redacted) | ✅ v2.2.0 | 2.2.0 | `diag-bundle.sh`, `VncInstallationNodeContribution.runDiagBundle()` — SwingWorker, 30s timeout, success dialog shows `scp` helper |
| B3 | Dočasný allowlist (IP + TTL 15/30/60 min, cron sweeper minutely) | ✅ v2.2.0 | 2.2.0 | `VncInstallationNodeView.buildTempAllowlistPanel()` + JTable, `temp-allowlist-add.sh` + `temp-allowlist-sweeper.sh`, `post-install.sh` (cron + state file bootstrap) |
| B4 | Audit log — JSON-Lines s `event=accept\|gone`, timestamp, src IP, count, duration | ✅ v2.2.0 | 2.2.0 | `vnc-audit-hook.sh` (flock-serialized), `run-vnc.sh` (`-accept` + `-gone` hooks export RFB_CLIENT_IP/COUNT/CONNECT_SEC) |
| B5 | Password strength live indicator (červený/žltý/zelený dot + tooltip) | ✅ v2.1.0 | 2.1.0 | `VncInstallationNodeView.java` |
| B6 | "Test connection" tlačidlo (RFB handshake na localhost:5900) | ✅ v2.2.0 | 2.2.0 | `vnc-test.sh` (bash `/dev/tcp` probe, JSON output), `VncInstallationNodeContribution.testConnection()` — inline result label + protocol/port regex parse |

## Vrstva C — Hardening & polish

| ID | Popis | Status | Verzia | Súbory |
|---|---|---|---|---|
| C1 | TLS wrapper (`x11vnc -ssl SAVE` + self-signed cert auto-gen + fingerprint pinning UI) | ✅ v3.0.0 | 3.0.0 | `run-vnc.sh` (TLS_ENABLED env fork), nový `tls-bootstrap.sh` (openssl req + SHA-256 fingerprint → `/root/.vnc/certs/fingerprint.txt`), `VncInstallationNodeView.buildTlsCheckbox/buildTlsFingerprintRow/showTlsFingerprintDialog`, `post-install.sh` (eager TLS bootstrap) |
| C2 | Session idle timeout (pointer-idle disconnect po 0..120 min, kick cez `-R "disconnect all"`) | ✅ v3.0.0 | 3.0.0 | nový `idle-watcher.sh` (xdotool/`-Q pointer_pos` probe, 60 s poll), `run-vnc.sh` (fork child keď `IDLE_TIMEOUT_MIN > 0`), `VncInstallationNodeView.buildIdleTimeoutField` (JSpinner 0..120 step 5) |
| C4 | Connection limit spinner (max 1..5 súčasných klientov) | ✅ v3.0.0 | 3.0.0 | `VncInstallationNodeView.buildMaxClientsField` (JSpinner 1..5), `VncInstallationNodeContribution.getMaxClients/setMaxClients`, `run-vnc.sh` (mapuje MAX_CLIENTS=1 → `-nevershared -noshared`; N>1 → `-shared`) |
| C7 | In-UI (?) tooltips na každom poli (HTML-formatted SK, modal JOptionPane) | ✅ v3.0.0 | 3.0.0 | `VncInstallationNodeView.rowWithInfo/infoButton` helpers, 10 tooltip stringov (TIP_PORT/PASSWORD/VIEW_ONLY/AUTOSTART/IXROUTER/CUSTOMER_LABEL/STRONG_PWD/TLS/IDLE/MAX_CLIENTS) |

## Explicitne OUT OF SCOPE

| ID | Popis | Dôvod |
|---|---|---|
| C3 | Business hours gating | Duplikácia s IXrouter Service-level Time-of-Day — riešime na IXON portal side |
| C5 | IXON webhook audit integration | Duplikácia s IXON Cloud Audit Log — riešime na ich strane |
| C6 | Multijazyčné UI (SK/EN toggle) | Andrej: "netreba multilanguage" — ostane len SK |
| C8 | Auto-update checker | Air-gap customers by to videli ako von-volajúcu aktivitu — security signal |
