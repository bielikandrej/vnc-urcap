# Sprint 3 — v3.0.0 (Hardening & polish)

> **✅ SHIPPED 2026-04-17** — `dist/stimba-vnc-server-3.0.0.urcap` (81 145 B)
> SHA-256: `cd9aacbd975735e78c3686b02c608dc16374a053364415e19974c57589f49ac1`
> Build: ECJ 3.33, URCap API 1.3.0, manifest tool STIMBA-bundler-3.0.0
> 44 Java classes + 11 daemon scripts (2 new: `tls-bootstrap.sh`, `idle-watcher.sh`)

**Cieľ:** TLS wire-level encryption + session limits + in-UI vysvetlivky pre ne-expertov.

**Definícia hotového:**
1. ✅ VNC komunikácia je TLS-encrypted end-to-end (`x11vnc -ssl SAVE`)
2. ✅ Idle sessions sa auto-odpájajú po X min (pointer-based, 0..120 min, default 30)
3. ✅ Max N súčasných klientov (range 1..5, default 1)
4. ✅ Každé pole v UI má (?) tooltip s 2-3 vetami vysvetlenia (HTML modal)

## Tasks

### C1. TLS wrapper ⏱ 8h

**Súbory:** `run-vnc.sh`, NEW `tls-bootstrap.sh`, `VncInstallationNodeView.java`, `post-install.sh`

**Strategia:** x11vnc má built-in `-ssl SAVE` — self-signed cert persists v `~/.vnc/certs/server.pem`.

**Implementačný plán:**
1. `tls-bootstrap.sh`:
   ```bash
   CERT_DIR=/root/.vnc/certs
   mkdir -p "$CERT_DIR"
   chmod 700 "$CERT_DIR"
   if [[ ! -f "$CERT_DIR/server.pem" ]]; then
     openssl req -new -x509 -days 3650 -nodes \
       -out "$CERT_DIR/server.pem" -keyout "$CERT_DIR/server.pem" \
       -subj "/CN=stimba-urcap-vnc-$(hostname)"
     chmod 600 "$CERT_DIR/server.pem"
   fi
   openssl x509 -in "$CERT_DIR/server.pem" -noout -fingerprint -sha256 \
     > "$CERT_DIR/fingerprint.txt"
   ```
2. `run-vnc.sh` zmena:
   ```bash
   if [[ "${TLS_ENABLED:-1}" == "1" ]]; then
     /opt/urcap-vnc/tls-bootstrap.sh
     SSL_ARGS="-ssl SAVE -sslGenCA /root/.vnc/certs"
   fi
   exec x11vnc $SSL_ARGS ...
   ```
3. UI:
   - `JCheckBox` "TLS šifrovanie" (default checked)
   - "Zobraziť cert fingerprint" button → dialog s SHA-256 fingerprint (z `fingerprint.txt`)
   - Warning banner ak uncheck: "⚠ VNC traffic bude plaintext. OK len pre LAN testing."
4. Dokumentácia v README: ako pridať cert do TightVNC/RealVNC client trust store

**Acceptance:**
- Wireshark kapture na 5900 porte neukáže plaintext keyboard events
- RealVNC viewer s "Use encryption" nastavením sa pripojí

**Compatibility note:** Niektoré staré VNC viewers (TightVNC 2.7.x) nepodporujú SSL. V UI pri uncheck TLS upozornenie.

### C2. Session idle timeout ⏱ 4h

**Súbory:** `run-vnc.sh`, NEW `idle-watcher.sh`, `VncInstallationNodeView.java`

**Strategia:** x11vnc má `-timeout` (disconnect ak *žiadny* klient connected N sekúnd), ale nie idle (klient connected ale žiadny input).

**Implementačný plán:**
1. UI: `JSpinner "Idle timeout (minutes)"` (range 5-120, default 30, 0 = disabled)
2. `run-vnc.sh`:
   ```bash
   if [[ "${IDLE_TIMEOUT_MIN:-0}" -gt 0 ]]; then
     IDLE_ARGS="-input M -noxdamage"  # monitor input events
     IDLE_WATCHER_PID=""
     /opt/urcap-vnc/idle-watcher.sh "$IDLE_TIMEOUT_MIN" &
     IDLE_WATCHER_PID=$!
     trap "kill $IDLE_WATCHER_PID 2>/dev/null" EXIT
   fi
   ```
3. `idle-watcher.sh`:
   - Poll každú minútu
   - Sleduje `last input timestamp` z x11vnc output logu (`-debug_keyboard` + parsed timestamp)
   - Ak `now - last_input > threshold` a `client count > 0`:
     - Log "IDLE TIMEOUT — disconnecting clients"
     - `x11vnc -remote ping_clients` (vynuceny ping s disconnect ak neodpovedajú)
     - Alternatíva: kill celého x11vnc a restart cez systemd (Polyscope daemon contribution to handle)

**Risk:** Polyscope UI na robote nemá keyboard input od operátora (operátor je na pendante, nie cez VNC). Iba VNC klient má input. Idle znamená: operátor pripojený ale neinteraguje. Monitoring cez `XQueryPointer` na DISPLAY :0.

**Mitigation:** Zjednodušiť — idle = last client pointer activity > N min. x11vnc logs pozíciu kurzora pri `-debug_pointer`.

### C4. Connection limit ⏱ 2h

**Súbory:** `run-vnc.sh`, `VncInstallationNodeView.java`

- UI: `JSpinner "Max súčasných klientov"` (range 1-5, default 1)
- `run-vnc.sh`:
  ```bash
  MAX_CLIENTS="${MAX_CLIENTS:-1}"
  if [[ "$MAX_CLIENTS" -eq 1 ]]; then
    exec x11vnc -connect_or_exit 1 ...
  else
    exec x11vnc -connect $MAX_CLIENTS ...
  fi
  ```
- x11vnc má `-connect N` (keep N, refuse N+1) a `-shared` (allow multiple). Default `-nevershared` refuse second client po prvom.

### C7. In-UI tooltips ⏱ 3h

**Súbory:** `VncInstallationNodeView.java`

- Každé pole / toggle dostane `(?)` icon button
- Click → modal dialog s 2-3 vetami vysvetlenia + link na `wiki/04-gotchas.md` ak relevant
- Tooltip texty (SK):

| Pole | Tooltip |
|---|---|
| VNC Port | "Štandard je 5900. Meniť len ak potrebuješ viac VNC inštancií na jednom robote (rare)." |
| VNC Password | "Používa sa na každý VNC connect. Pozor: RFB protokol obmedzuje heslo na prvých 8 znakov — použi silnú kombináciu v rámci týchto 8." |
| View only | "Keď checked, VNC klient môže len pozerať — nie klikať/písať. Pre remote diagnostiku často stačí." |
| IXROUTER IP | "IP adresa IXrouter zariadenia na LAN. Default 192.168.0.100 — zmeň len ak tvoja sieť používa iný subnet." |
| Customer label | "Názov zákazníka/projektu. Zobrazí sa v logoch — pomáha pri fleet diagnostike." |
| Require strong password | "Ak checked, daemon odmietne naštartovať pokial je root heslo easybot default. Odporúčané pre production." |
| TLS šifrovanie | "Šifruje VNC traffic self-signed SSL certifikátom. Odporúčané pre internet-accessible deployment (cez IXON)." |
| Idle timeout | "Po koľkých minútach neaktivity sa VNC connection ukončí. 0 = vypnuté." |
| Max clients | "Koľko súčasných VNC klientov môže byť pripojených. Pre security obvykle 1." |

## Build & Ship (ZÁZNAM)

1. ✅ `pom.xml` bumped 2.2.0 → 3.0.0 (2026-04-17)
2. ✅ `wiki/02-feature-matrix.md` — C1/C2/C4/C7 → ✅ v3.0.0 (2026-04-17)
3. 📋 README TLS pinning guide — v ďalšom commite (Task #11)
4. ✅ ECJ build → `dist/stimba-vnc-server-3.0.0.urcap` 81 145 B (2026-04-17)

## Implementačné odchýlky od pôvodného plánu

- **C2 idle-watcher:** pôvodný plán počítal s `-debug_keyboard` timestamp parsingom. Miesto toho použitý `xdotool getmouselocation` ako primary probe + `x11vnc -Q pointer_pos` ako fallback (xdotool býva na UR images, ale nie vždy). `kick_all_clients()` používa `x11vnc -R "disconnect all"` namiesto `-remote ping_clients` — čistejší FIN než ping/timeout cycle.
- **C4 connection limit:** `-connect_or_exit` použitie by killlo celý daemon pri prvom disconnecte (R4 risk); namiesto toho pre `MAX_CLIENTS=1` použité `-nevershared -noshared`, pre `N>1` `-shared`. Hard cap `N<=5` v UI spinneri aj v Java validácii.
- **C1 TLS:** pridaný `eager TLS bootstrap` v `post-install.sh`, aby "Zobraziť cert fingerprint" button fungoval hneď po inštalácii URCapu, ešte pred prvým `run-vnc.sh` štartom. Bez toho by prvý otvorený Polyscope UI videl "fingerprint n/a".
- **C7 tooltips:** namiesto Swing `JToolTip` použitý `JOptionPane` modal (Polyscope 5 UI je touchscreen-first, hover tooltipy tam nefungujú spoľahlivo). 10 tooltip stringov, HTML-formatted, triggered cez 22×22 px "?" button.

## Risks

- **R1:** Self-signed cert pin-nutý v IXON portal môže expirovať alebo sa zmeniť (cert regen). Mitigation: fingerprint v UI, alert pri každej zmene.
- **R2:** Niektoré VNC viewers nepodporujú `-ssl` (starý TightVNC 2.x). Mitigation: `TLS_ENABLED=0` toggle s warning.
- **R3:** `idle-watcher.sh` pomocou `XQueryPointer` môže detekovať robot movement (teaching) ako "input" a nikdy neodpojiť. Mitigation: clarify — detekujeme len vzdialené klient-side events, nie physical teach pendant.
- **R4:** `-connect_or_exit` spadne x11vnc ak klient disconnect-ne → Polyscope restart → výpadok. Mitigation: DaemonContribution auto-restart + supervisor wrapper.
