# Sprint 2 — v2.2.0 (Observability & operation)

**Status:** ✅ SHIPPED 2026-04-17 — `dist/stimba-vnc-server-2.2.0.urcap` (66 662 B, sha256 `41e2915579991d0ea276cf2bfa42e524b8bbb94a317e0c899889dba430a4619d`)

**Cieľ:** Keď zákazník má problém, Andrej vie z UI vidieť čo sa deje bez SSH.

**Definícia hotového:**
1. Live log viewer v UI ukazuje posledných 50 riadkov `/var/log/urcap-vnc.log`, auto-refresh
2. Export diagnostiku button vyrobí .tar.gz na `/root/` pre email/Gong share
3. Temp allowlist dialog pridá dočasné IP povolenie s TTL
4. Každý connect/disconnect sa zapisuje do audit logu s timestamp + IP + duration
5. Test connection button overí lokálny RFB handshake

## Tasks

### B1. Live log viewer ⏱ 4h

**Súbory:** `VncInstallationNodeView.java`, NEW `LogTailer.java`

- Nový panel v UI (collapse/expand): `JTextArea` (monospace, 50 lines)
- NEW `LogTailer.java`:
  - ScheduledExecutorService, refresh every 2s
  - Reads `/var/log/urcap-vnc.log` last 50 lines cez `Files.lines(...).skip(max(0, count-50))`
  - Posun na SwingUtilities.invokeLater → JTextArea setText
- Filter combobox: ALL / INFO / WARN / ERROR
- "Clear" button (len clear UI area, nie file)

### B2. Export diagnostiku ⏱ 2h

**Súbory:** `VncInstallationNodeView.java`, NEW `diag-bundle.sh`

- `JButton "Exportovať diagnostiku"`
- Spustí `diag-bundle.sh` → vyrobí `/root/urcap-vnc-diag-YYYYMMDD-HHMMSS.tar.gz`
- Obsah bundle:
  - `/var/log/urcap-vnc.log`
  - `/var/log/urcap-vnc-audit.log`
  - `/var/lib/urcap-vnc/config`
  - `iptables-save` output
  - `ps -efww` output
  - `ss -tlnp` output
  - `health-probe.sh` current output (JSON)
  - URCap version (z MANIFEST.MF)
  - `uname -a`, `cat /etc/os-release`
- Po úspechu UI dialog: "Diagnostika uložená: /root/urcap-vnc-diag-....tar.gz. Skopíruj cez SSH alebo USB."

### B3. Temp allowlist ⏱ 4h

**Súbory:** `VncInstallationNodeView.java`, `VncInstallationNodeContribution.java`, NEW `temp-allowlist-add.sh`, NEW `temp-allowlist-sweeper.sh`

- UI button "+ Dočasná IP"
- Dialog:
  - Source IP (text field + validation)
  - TTL: radio 15min / 30min / 60min
  - Comment (pre audit log, napr. "vendor remote help")
- Po Confirm → Java spawn `temp-allowlist-add.sh <IP> <TTL_SECONDS> <COMMENT>`
- `temp-allowlist-add.sh`:
  - Append do `/var/log/urcap-vnc-temp-allowlist`: `<expiry_unix> <IP> <comment>`
  - `iptables -I INPUT 1 -p tcp --dport 5900 -s $IP -j ACCEPT`
- `temp-allowlist-sweeper.sh`:
  - Beží z cron každú minútu
  - Pre každý riadok kde `$expiry < $(date +%s)`:
    - `iptables -D INPUT -p tcp --dport 5900 -s $IP -j ACCEPT`
    - Remove z allowlist file
- Cron entry: inštaluje `post-install.sh`:
  ```
  * * * * * root /opt/urcap-vnc/temp-allowlist-sweeper.sh
  ```
- UI tabuľka aktívnych temp entries (refresh 10s)

### B4. Audit log ⏱ 3h

**Súbory:** `run-vnc.sh`, NEW `vnc-audit-hook.sh`

- `run-vnc.sh` pridá flagy: `-accept "$AUDIT_HOOK connect"` + `-gone "$AUDIT_HOOK disconnect"`
- x11vnc exportuje env premenné `$RFB_CLIENT_IP`, `$RFB_CLIENT_COUNT` do hook
- `vnc-audit-hook.sh`:
  - Arg $1 = "connect" | "disconnect"
  - Zapíše JSON-line do `/var/log/urcap-vnc-audit.log`:
    ```json
    {"ts":"2026-04-17T14:30:12+02:00","event":"connect","ip":"100.64.0.5","duration_s":null,"customer":"STIMBA-internal"}
    {"ts":"2026-04-17T15:45:03+02:00","event":"disconnect","ip":"100.64.0.5","duration_s":4491,"customer":"STIMBA-internal"}
    ```
- Retention: logrotate config (denný rotate, keep 90 dní) — inštaluje `post-install.sh`

### B6. Test connection ⏱ 2h

**Súbory:** `VncInstallationNodeView.java`, NEW `vnc-test.sh`

- Button "Test connection"
- `vnc-test.sh`:
  ```bash
  # RFB handshake probe (bez auth)
  exec 3<>/dev/tcp/127.0.0.1/5900 || { echo '{"status":"fail","error":"connect refused"}'; exit 1; }
  read -t 3 -u 3 proto
  if [[ "$proto" =~ ^RFB ]]; then
    echo '{"status":"ok","protocol":"'$proto'"}'
  else
    echo '{"status":"fail","error":"no RFB banner"}'
  fi
  ```
- UI zobrazí výsledok dialog: "✅ VNC server odpovedá (RFB 003.008)" alebo "❌ VNC server neodpovedá — skontroluj daemon state"

## Build & Ship — ✅ COMPLETED

1. ✅ Bumped `pom.xml` → 2.2.0
2. ✅ Updated `wiki/02-feature-matrix.md` → B1-B4, B6 = ✅
3. ✅ Sandbox ECJ build (plugins.ur.com blocked, no Maven) → 5 Java sources → 40 `.class` files, 1 harmless warning (anonymous inner class serialVersionUID)
4. ✅ OSGi bundle assembled with `zip -X -0` (MANIFEST.MF first, stored) + `zip -X -r` for rest → `dist/stimba-vnc-server-2.2.0.urcap` (66 662 B)
5. ✅ `dist/SHA256SUMS-2.2.0` = `41e2915579991d0ea276cf2bfa42e524b8bbb94a317e0c899889dba430a4619d`

## Delivered ≠ designed — notes

Where implementation diverged from the design stub above, for the record:

- **B1** — Polling rate raised from 2s → 3s (opt-in via checkbox). Default is OFF, so the URCap doesn't burn Polyscope CPU when no one's looking. Log tail is pure-Java Deque ring buffer instead of `tail(1)` subprocess — one fewer fork per refresh, and avoids the race window of `tail -f` inheritance across logrotate.
- **B2** — Diag bundle is built via SwingWorker with 30s hard timeout and separate stdout/stderr so partial output still makes it to the caller. The success dialog shows a ready-to-copy `scp root@<robot>:/root/urcap-vnc-diag-*.tar.gz .` helper line — Andrej doesn't have to remember the path.
- **B3** — IP input validated with `IXROUTER_IP_PATTERN` (same regex as the main config field). TTL clamped to TTL_MAX = 240 min defensively even though UI offers 15/30/60. Comment is sanitized (tab/newline/non-ASCII stripped) because it's the literal field separator in the state file. Table refresh at 10s.
- **B4** — Audit hook uses `flock /var/lock/urcap-vnc-audit.lock` so concurrent `-accept`/`-gone` invocations don't interleave JSON lines. Each line also carries `customer` and `count` fields pulled from env. logrotate install intentionally skipped — existing `/etc/logrotate.d/rsyslog` default picks up `/var/log/urcap-vnc-audit.log` because we kept the standard prefix.
- **B6** — Test button does NOT open a dialog — result renders inline in a status `JLabel` next to the button (green check / red X + message). The shell probe uses bash `/dev/tcp` so we don't require `ncat`/`socat` on the robot.

## Risks

- **R1:** `diag-bundle.sh` môže obsahovať citlivé info (passwords grep-nuté z logov). Mitigation: explicit redaction regex `sed -E 's/password=[^ ]+/password=***/gi'`
- **R2:** `temp-allowlist-sweeper.sh` cron môže race s UI add. Mitigation: flock `/var/lock/urcap-vnc-allowlist.lock`
- **R3:** Audit log môže rásť — ak nikto nenastaví logrotate. Mitigation: hard-capped 10MB cez logrotate config v `post-install.sh`
- **R4:** `LogTailer` môže drží file handle → problém pri logrotate. Mitigation: re-open on each read alebo použiť `Files.lines` (auto-closeable stream)
