# Sprint 1 — v2.1.0 (UI bridge + Vrstva A + B5) — ✅ SHIPPED 2026-04-17

**Artefakt:** `dist/stimba-vnc-server-2.1.0.urcap` (42 507 B, sha256 `15c0bdf0e4f95f997578382b8403e30618d5859d819ad9b915523e977b3962dc`)

**Build notes:** Maven/Docker cesta zablokovaná — sandbox blokuje `plugins.ur.com`. Prešli sme na **custom ECJ toolchain**:
- `/tmp/compiler/ecj.jar` (Eclipse Compiler 3.33.0, 3 160 927 B)
- `/tmp/urcap-lib/api-1.3.0.jar` (hand-written URCap API stubs, 8 421 B — presne tá API surface ktorú používame: `DaemonContribution`, `DaemonService`, `InstallationNodeContribution`, `ViewAPIProvider`, `CreationContext`, `DataModel`, `SwingInstallationNodeService`, `SwingInstallationNodeView`, `InstallationAPIProvider`, `ContributionConfiguration`, `ScriptWriter`)
- `/tmp/urcap-lib/osgi.core-6.0.0.jar` (Maven Central, 475 256 B — `BundleActivator`, `BundleContext`)
- Assembly: `zip -X -0 <out> META-INF/MANIFEST.MF; zip -X -r <out> META-INF sk -x META-INF/MANIFEST.MF` (MANIFEST ako first entry je OSGi hard requirement)

**Cieľ:** Konfigurácia bez SSH. Operátor robí všetko cez Polyscope Installation tab.

**Definícia hotového:** Pri inštalácii URCap-u na čistom robote operátor:
1. Otvorí Installation → URCaps → STIMBA VNC Server
2. Vidí 5 zelených health dotov (alebo červené s vysvetlením)
3. Zmení port/heslo/IXROUTER_IP/customer label
4. Klikne Apply
5. Klikne Start daemon
6. VNC connection cez IXON funguje bez ďalších zásahov

## Tasks

### A1. UI → daemon bridge ⏱ ~3h

**Súbory:** `VncInstallationNodeContribution.java`, `run-vnc.sh`, `stop-vnc.sh`, NEW `post-install.sh`

**Implementačný plán:**
1. NEW `post-install.sh` (root, beží pri URCap install):
   ```bash
   mkdir -p /var/lib/urcap-vnc
   chown root:polyscope /var/lib/urcap-vnc
   chmod 770 /var/lib/urcap-vnc
   touch /var/lib/urcap-vnc/config
   chown root:polyscope /var/lib/urcap-vnc/config
   chmod 660 /var/lib/urcap-vnc/config
   ```
2. `VncInstallationNodeContribution.writeConfigFile()`:
   ```java
   Path tmp = Files.createTempFile("urcap-vnc-", ".conf");
   String content = String.format(
     "VNC_PORT=%d\nVNC_PASSWORD=%s\nVIEW_ONLY=%b\nIXROUTER_IP=%s\nCUSTOMER_LABEL=%s\nURCAP_VNC_REQUIRE_STRONG_PWD=%d\n",
     port, escapeShell(password), viewOnly, escapeShell(ixrouterIp), escapeShell(label), strongPwd ? 1 : 0
   );
   Files.write(tmp, content.getBytes(UTF_8));
   Files.move(tmp, Paths.get("/var/lib/urcap-vnc/config"), ATOMIC_MOVE);
   ```
3. `run-vnc.sh`:
   ```bash
   CONF="/var/lib/urcap-vnc/config"
   [[ -f "$CONF" ]] && source "$CONF"
   # legacy fallback
   [[ -f "/root/.urcap-vnc.conf" ]] && source "/root/.urcap-vnc.conf"
   : "${VNC_PORT:=5900}"
   : "${IXROUTER_IP:=192.168.0.100}"
   : "${CUSTOMER_LABEL:=unknown}"
   ```
4. `stop-vnc.sh`: rovnaký source pattern

**Acceptance:**
- `cat /var/lib/urcap-vnc/config` po Apply obsahuje očakávané hodnoty
- Daemon restart → x11vnc beží s novým portom/heslom
- Stop daemon → iptables rules pre starý IXROUTER_IP zmiznú

### A2. IXROUTER_IP text field ⏱ 1h

**Súbory:** `VncInstallationNodeView.java`, `VncInstallationNodeContribution.java`

- Pridať `JTextField ixrouterIpField` s validáciou (regex `^(\d{1,3}\.){3}\d{1,3}$`)
- Default: `192.168.0.100`
- DataModel key: `IXROUTER_IP`
- Pri invalid IP → červený border, disable Apply button

### A3. Customer label ⏱ 1h

**Súbory:** `VncInstallationNodeView.java`, `VncInstallationNodeContribution.java`, `run-vnc.sh`

- `JTextField customerLabelField` (max 32 chars, alphanumeric + space + hyphen)
- Default: `STIMBA-internal`
- Zapíše sa do config ako `CUSTOMER_LABEL=...`
- `run-vnc.sh` použije do `LOG_TAG`:
  ```bash
  LOG_TAG="urcap-vnc[${CUSTOMER_LABEL}]"
  exec x11vnc ... 2>&1 | logger -t "$LOG_TAG"
  ```
- Aj v `ps -ef` → daemon command line obsahuje label (pre quick visual ID na multi-robot SSH session)

### A4. Health panel ⏱ 4h

**Súbory:** `VncInstallationNodeView.java`, NEW `health-probe.sh`

5 indikátorov (každý zelený/žltý/červený dot + tooltip):

| ID | Probe | Pass condition |
|---|---|---|
| 1. Daemon | `cat /tmp/urcap-vnc.lock && kill -0 $(cat ..)` | PID alive |
| 2. iptables | `iptables -L INPUT -n \| grep -c 5900` | == 3 (whitelist + loopback + DROP) |
| 3. Port | `ss -tln \| grep -c ':5900'` | >= 1 |
| 4. DISPLAY | `xdpyinfo -display :0 \| grep -c 'name of display'` | >= 1 |
| 5. IXrouter | `ping -c 1 -W 2 $IXROUTER_IP` | exit 0 |

**Implementácia:**
- NEW `health-probe.sh` returns JSON: `{"daemon":"ok","iptables":"ok","port":"ok","display":"ok","ixrouter":"warning"}`
- Java spawn-uje `health-probe.sh` cez `Runtime.exec`, parse JSON, update Swing labels
- Refresh každých 5s cez `javax.swing.Timer`
- Click na dot → Swing dialog s detail logom

### A5. easybot banner + STRONG_PWD toggle ⏱ 2h

**Súbory:** `VncInstallationNodeView.java`, `VncInstallationNodeContribution.java`, `run-vnc.sh` (toggle existuje od 2.0.1)

- Banner: ak `health-probe` zistí že root password je easybot default (známy hash), v UI sa zobrazí červený banner: *"⚠ Root heslo je easybot default. NIKDY nedeploy-uj na production. Zmeň: `passwd root` na SSH."*
- Pod banner: `JCheckBox` "Vyžadovať silné root heslo (URCAP_VNC_REQUIRE_STRONG_PWD)"
- Default: checked = true
- Pri uncheck → confirmation dialog "Naozaj? Toto je security risk."
- Hodnota ide do config ako `URCAP_VNC_REQUIRE_STRONG_PWD=1|0`

### B5. Password strength live indicator ⏱ 2h

**Súbory:** `VncInstallationNodeView.java`

- Pri zmene `passwordField` → live computed strength
- **DÔLEŽITÉ:** entropia sa počíta len z **prvých 8 znakov** (RFB 8-char truncation, viď gotchas G2)
- 3 stavy:
  - 🔴 < 30 bit entropy alebo < 8 chars → "Slabé"
  - 🟡 30-60 bit → "OK"
  - 🟢 > 60 bit → "Silné"
- Pod field: hint *"VNC RFB protokol používa len prvých 8 znakov hesla. Použi silnú kombináciu v týchto 8 znakoch."*

## Build & Ship — ✅ done

1. ✅ Bump `pom.xml` → 2.1.0
2. ✅ Hand-written `MANIFEST.MF` Bundle-Version=2.1.0 (maven-bundle-plugin sa nedal použiť bez `plugins.ur.com`)
3. 🟡 Update `README.md` → odsunuté na ďalší batch (README sa bude aktualizovať súhrne pred final Docker build v task #11)
4. ✅ Update `wiki/00-INDEX.md` → status table 2.1.0 = ✅ shipped
5. ✅ Update `wiki/02-feature-matrix.md` → A1-A5, B5 status = ✅
6. ✅ Update `wiki/progress.md` → ship line
7. ✅ Custom ECJ build (Docker nedostupný v sandboxe)
8. ✅ Output: `dist/stimba-vnc-server-2.1.0.urcap` + `dist/SHA256SUMS-2.1.0`

## Risks

- **R1:** `polyscope` user nemá write na `/var/lib/urcap-vnc/` → A1 zlyhá. Mitigation: `post-install.sh` overiť owner/group, fallback error v UI.
- **R2:** `health-probe.sh` spawn pomalý → UI lag. Mitigation: spawn v background thread, UI update cez `SwingUtilities.invokeLater`.
- **R3:** Config file permission race condition pri concurrent Apply. Mitigation: Files.move ATOMIC_MOVE.
- **R4:** Customer label s special chars (`'`, `"`, `;`, `$`) → shell injection v `LOG_TAG`. Mitigation: regex whitelist `[A-Za-z0-9 _-]`, validate v Java pred zapísaním.
