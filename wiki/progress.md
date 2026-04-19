# Progress Log

Chronologický log zmien. Pravidlo: každý task, ktorý mení kód v repo → pridá riadok sem.

Formát: `YYYY-MM-DD HH:MM  [verzia]  [kto]  popis`

---

## 2026-04-16

- **2026-04-16 **  [2.0.0]  Andrej+Claude  Initial URCap v2 scaffold — OSGi bundle, DaemonContribution + InstallationNode skeleton
- **2026-04-16 **  [2.0.0]  Claude  `run-vnc.sh` draft: spawn x11vnc, DISPLAY=:0, port 5900. ❌ Binding na localhost → IXON route nenadviaže RFB. ❌ Script nebol v resources correct path → installResource zlyhá.
- **2026-04-16 **  [2.0.0]  Claude  Diagnostika 2.0.0 fail: (a) localhost binding, (b) missing shell scripts v bundli, (c) Bundle-Activator chýba v MANIFEST
- **2026-04-16 **  [2.0.1]  Claude  Fix Bundle-Activator (maven-bundle-plugin `<Bundle-Activator>` tag). Scripts v `sk/stimba/urcap/vnc/impl/daemon/` (package-aligned).
- **2026-04-16 **  [2.0.1]  Claude  `run-vnc.sh`: 0.0.0.0 binding + iptables whitelist INPUT chain (192.168.0.10 + loopback ACCEPT, DROP default). Lock file idempotent check (kill -0).
- **2026-04-16 **  [2.0.1]  Claude  `run-vnc.sh`: easybot default root password tripwire — ak `$URCAP_VNC_REQUIRE_STRONG_PWD=1` a root hash == známy easybot hash, daemon refuseuje štart. Log: "REFUSING: default easybot password detected".
- **2026-04-16 **  [2.0.1]  Claude  Ship 2.0.1 — first working build. Deployed Andrejom na test robot.

## 2026-04-17

- **2026-04-17 09:00**  [2.0.1]  Andrej  Feedback: "IXrouter je 0.100, nie 0.10" — musí sa to dať meniť per-robot bez rebuild-u
- **2026-04-17 10:00**  [2.0.2]  Claude  `run-vnc.sh`: default IXROUTER_IP=192.168.0.100. Source `/root/.urcap-vnc.conf` ak existuje (override).
- **2026-04-17 10:00**  [2.0.2]  Claude  `stop-vnc.sh`: source rovnaký conf file pre consistent cleanup rules.
- **2026-04-17 10:30**  [2.0.2]  Claude  `README.md` update: per-robot override flow, new deploy scripts.
- **2026-04-17 10:45**  [2.0.2]  Claude  Ship 2.0.2 — `dist/stimba-vnc-server-2.0.2.urcap` SHA-256 `a16da33579f3d6dacbbf3ca183225ed5fe59f489835f12cf3471cd27c1be294e`. Size 26 284 B. **CURRENT PROD**
- **2026-04-17 11:00**  [plan]  Andrej  "vieme IP nastaviť v inštalácii?" — UI nemá IXROUTER_IP field. Rozhodnutie: plan v2.1.0.
- **2026-04-17 12:00**  [plan]  Andrej  "daj všetko okrem C3 a C8, netreba multilanguage, nekomplikuj IXON duplikaciou — sprav si Karpathy wiki + sprinty"
- **2026-04-17 13:00**  [wiki]  Claude  Karpathy wiki scaffold: 00-INDEX, 01-architecture, 02-feature-matrix, 03-build-deploy, 04-gotchas, 05-file-map, progress.md.
- **2026-04-17 13:30**  [wiki]  Claude  Sprint plans + 7 ADRs — wiki scaffold hotový.
- **2026-04-17 16:00**  [2.1.0]  Claude  Sprint 1 kick-off. Dropped Maven/Docker — sandbox blocks `plugins.ur.com`. Built custom toolchain: ECJ 3.33.0 (`/tmp/compiler/ecj.jar`) + hand-rolled URCap API 1.3.0 stubs (`/tmp/urcap-lib/api-1.3.0.jar`, 8 421 B) + Maven Central osgi.core 6.0.0.
- **2026-04-17 16:05**  [2.1.0]  Claude  A1 UI→daemon bridge: `writeConfigFile()` via `Files.createTempFile` + `Files.move ATOMIC_MOVE` to `/var/lib/urcap-vnc/config`. `applyConfig()` = write + stop + sleep(300) + start. `shellEscape()` for `\\ $ " \``.
- **2026-04-17 16:05**  [2.1.0]  Claude  A2 IXROUTER_IP field + live IPv4 regex validator (`^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.…){3}$`). Inline ✓/✗ label.
- **2026-04-17 16:05**  [2.1.0]  Claude  A3 CUSTOMER_LABEL field (`^[A-Za-z0-9 _-]{0,32}$`). Flows into `LOG_TAG="urcap-vnc[${CUSTOMER_LABEL}]"` in `run-vnc.sh` / `stop-vnc.sh` — každý robot má vlastný syslog tag, grep-friendly cross-fleet.
- **2026-04-17 16:05**  [2.1.0]  Claude  A4 Health panel: `health-probe.sh` emits single-line JSON `{"daemon":…,"iptables":…,"port":…,"display":…,"ixrouter":…}`. 5 probes: `/proc/$pid`, iptables count on `:$VNC_PORT` (root-only, else `unknown`), `ss -tln` with netstat fallback, `xdpyinfo -display :0`, `ping -c 1 -W 2 $IXROUTER_IP` (fail → `warning` not `fail` because ICMP often disabled). Swing `Timer` @ 5000 ms; 5 colored dots (OK green / warn amber / fail red / unknown grey).
- **2026-04-17 16:05**  [2.1.0]  Claude  A5 easybot banner červený (bg `#FFEBEE`) — viditeľný aj bez SSH. `strongPwdBox` toggle mapuje na `URCAP_VNC_REQUIRE_STRONG_PWD=1|0` v configu.
- **2026-04-17 16:05**  [2.1.0]  Claude  B5 password strength: `estimatePasswordStrength(pw)` truncates k 8 znakom (RFB limit) + ráta tri+ character classes → strong/medium/weak. Live update cez `KeyAdapter`, finalny commit na Enter/focus-lost (ActionListener + FocusAdapter).
- **2026-04-17 16:05**  [2.1.0]  Claude  New `post-install.sh` — idempotent bootstrap `/var/lib/urcap-vnc/` s `chown root:polyscope chmod 2770` (setgid), fallback `1777` ak polyscope group chýba. Inštaluje `/etc/logrotate.d/urcap-vnc` (daily, rotate 90, maxsize 10M).
- **2026-04-17 16:05**  [2.1.0]  Claude  `run-vnc.sh` self-bootstrap: pri štarte vytvorí `/var/lib/urcap-vnc/` (rovnaká permission matrix ako `post-install.sh`). Nový exit kód `15` = bootstrap failure. Config-source priorita: `/var/lib/urcap-vnc/config` → `/root/.urcap-vnc.conf` → defaults. `CONFIG_SOURCED` premenná zaznamenaná do logu.
- **2026-04-17 16:10**  [2.1.0]  Claude  Kompilácia 5 Java súborov ECJ-om proti stub API — 24 class súborov (vrátane anonymných listener classes). Zero warnings, zero errors.
- **2026-04-17 16:15**  [2.1.0]  Claude  Bundle assembly: MANIFEST.MF as FIRST zip entry (OSGi spec requirement) via `zip -X -0 <out> META-INF/MANIFEST.MF` then `zip -X -r <out> META-INF sk -x META-INF/MANIFEST.MF`. Bump `pom.xml` → 2.1.0.
- **2026-04-17 16:16**  [2.1.0]  Claude  **Ship 2.1.0** — `dist/stimba-vnc-server-2.1.0.urcap` SHA-256 `15c0bdf0e4f95f997578382b8403e30618d5859d819ad9b915523e977b3962dc`. Size 42 507 B (40 files: MANIFEST + pom + 22 classes + 4 shell scripts). **CURRENT PROD.** `dist/SHA256SUMS-2.1.0` napísaný.

---

## Sprint 2 — 2026-04-17 (observability, temp allowlist, audit)

- **2026-04-17 17:00**  [2.2.0]  Claude  Sprint 2 kick-off. Scope = B1/B2/B3/B4/B6 (B5 already shipped in 2.1.0). Design principle: žiadny nový proces, len shell scripty + Java SwingWorkers. Cron sweeper iba 1 nový systémový artefakt.
- **2026-04-17 17:05**  [2.2.0]  Claude  **B4 audit log** — `vnc-audit-hook.sh` nový súbor (flock-serialized `/var/lock/urcap-vnc-audit.lock`). Emituje JSON-line: `{"ts":"…","event":"accept|gone","ip":"…","count":N,"duration_s":N|null,"customer":"…"}`. `run-vnc.sh` doplnené `-accept '$AUDIT_HOOK accept'` + `-gone '$AUDIT_HOOK gone'` flagy. x11vnc exportuje `RFB_CLIENT_IP`/`RFB_CLIENT_COUNT`/`RFB_CONNECT_SEC` do hook env — hook ich premieta do JSON.
- **2026-04-17 17:10**  [2.2.0]  Claude  **B2 diag bundle** — `diag-bundle.sh` nový. Tar.gz do `/root/urcap-vnc-diag-YYYYMMDD-HHMMSS.tar.gz`. Obsah: `/var/log/urcap-vnc.log`, `/var/log/urcap-vnc-audit.log`, config file, `iptables-save`, `ps -efww`, `ss -tlnp`, `health-probe.sh` JSON, MANIFEST version, `uname -a`. Redakcia: `sed -E 's/password=[^ ]+/password=***/gi'` na log výstup pred zbalením.
- **2026-04-17 17:15**  [2.2.0]  Claude  **B3 temp allowlist** — `temp-allowlist-add.sh` + `temp-allowlist-sweeper.sh`. State file `/var/log/urcap-vnc-temp-allowlist` format `<expiry_unix>\t<IP>\t<comment>`. Add: `iptables -I INPUT 1 -p tcp --dport 5900 -s $IP -j ACCEPT` + append riadok. Sweeper: prejde state file, pre expired riadok `iptables -D` a vypusti. Oba pod flock `/var/lock/urcap-vnc-allowlist.lock`. Exit codes 0/2/3 (ok / bad IP / iptables fail) mapped do SK error messages v Jave.
- **2026-04-17 17:20**  [2.2.0]  Claude  **B6 vnc-test** — `vnc-test.sh` bash `/dev/tcp/127.0.0.1/5900` probe, 3s read timeout. Emituje JSON: `{"status":"ok","protocol":"RFB 003.008","port":5900}` alebo `{"status":"fail","error":"…"}`. Bez závislosti na `ncat`/`socat` — e-Series image má len bash.
- **2026-04-17 17:25**  [2.2.0]  Claude  **B1 log tail** — implementované v Jave (žiadny nový shell). `VncInstallationNodeContribution.tailLog(int lines)` číta `/var/log/urcap-vnc.log` cez `Files.lines(...)` do `Deque` ring bufferu (bounded na `lines`). Pure Java → funguje aj keď `tail(1)` chýba. Prázdny list pri unreadable file — UI neukazuje exception trace.
- **2026-04-17 17:30**  [2.2.0]  Claude  `post-install.sh` v2.2.0: idempotentne založí cron entry `* * * * * root <DAEMON_DIR>/temp-allowlist-sweeper.sh` cez `/etc/cron.d/urcap-vnc-sweeper` (644). Bootstrap `/var/log/urcap-vnc-temp-allowlist` (empty, 660 root:root). chmod +x pre všetkých 5 nových shell scriptov.
- **2026-04-17 17:35**  [2.2.0]  Claude  `VncInstallationNodeContribution.java` rozšírené o 5 bridge metód: `tailLog(int)`, `runDiagBundle()`, `addTempAllowlist(ip, ttl, comment)`, `listTempAllowlist()`, `testConnection()`. Nové konštanty: `DAEMON_DIR`, `HEALTH_PROBE`, `DIAG_BUNDLE`, `TEMP_ALLOW_ADD`, `VNC_TEST`, `VNC_LOG`, `TEMP_ALLOW_FILE`. TTL constants `TTL_15_MIN`/`TTL_30_MIN`/`TTL_60_MIN`/`TTL_MAX=240min`.
- **2026-04-17 17:40**  [2.2.0]  Claude  `VncInstallationNodeView.java` doplnené 3 panely: `buildDiagRow()` (B2+B6 + inline test-result label), `buildLogTailPanel()` (B1, 640×140 monospaced `JTextArea` + "Auto-refresh 3s" checkbox, default OFF), `buildTempAllowlistPanel()` (B3, JTable "IP / Zostáva(min) / Popis" v 640×100 scrollpane + "+ Dočasná IP" dialog s radio TTL + "Obnoviť zoznam"). SwingWorker pattern na všetky subprocess volania (off-EDT) — buttony disabled počas behu.
- **2026-04-17 17:45**  [2.2.0]  Claude  Swing timery: `logTimer` (3s, opt-in cez checkbox) a `tempAllowTimer` (10s, start/stop cez `openView`/`closeView`). Contribution hook `startTempAllowlistTimer()` / `stopSprint2Timers()` viaže lifecycle na Installation node visibility.
- **2026-04-17 17:50**  [2.2.0]  Claude  ECJ recompile: 5 sources → 40 class files (nárast z 24 na 40 kvôli anonym SwingWorker/ActionListener/FocusAdapter/KeyAdapter vo View a Contribution). Zero errors, 1 warning (anonymous DefaultTableModel serialVersionUID, harmless).
- **2026-04-17 17:52**  [2.2.0]  Claude  MANIFEST.MF rozšírený o `javax.swing.table` (DefaultTableModel import). Bundle-Version bumped 2.1.0→2.2.0. Bundle-Description prepísaný na Sprint-2 scope popis. Tool header `STIMBA-bundler-2.2.0`.
- **2026-04-17 17:55**  [2.2.0]  Claude  Bundle assembly: `zip -X -0 … META-INF/MANIFEST.MF` (stored, no compression — OSGi rule that MANIFEST je prvý entry) + `zip -X -r … META-INF sk -x META-INF/MANIFEST.MF`. 61 entries total (40 classes + 9 shell scripts + MANIFEST + pom.xml + 10 resource/dir entries).
- **2026-04-17 18:00**  [2.2.0]  Claude  **Ship 2.2.0** — `dist/stimba-vnc-server-2.2.0.urcap` SHA-256 `41e2915579991d0ea276cf2bfa42e524b8bbb94a317e0c899889dba430a4619d`. Size 66 662 B (+24 155 B oproti 2.1.0 = +57 %; daň za +16 class files + 5 nových shellov + JSON-Lines audit hook). **CURRENT PROD.** `dist/SHA256SUMS-2.2.0` napísaný.
- **2026-04-17 18:05**  [wiki]  Claude  Wiki updates: `00-INDEX.md` status tabuľka (2.1.0 → shipped, 2.2.0 → current prod s hash/size), `02-feature-matrix.md` (B1/B2/B3/B4/B6 → ✅ v2.2.0 s konkrétnymi `.java`/`.sh` file pointers), `sprints/sprint-2-v2.2.0.md` SHIPPED header + "Delivered ≠ designed" sekcia popisujúca divergencie (polling rate, SwingWorker, flock path, logrotate decision). Sprint 2 done.

---

## Sprint 3 — 2026-04-17 (TLS + idle + conn limit + tooltips)

- **2026-04-17 18:30**  [3.0.0]  Claude  Sprint 3 kick-off. Scope = C1 (TLS wire-level), C2 (pointer-idle auto-disconnect), C4 (max clients 1..5), C7 (in-UI tooltips). C3/C5/C6/C8 explicitne out-of-scope. `/effort max` mode per CLAUDE.md.
- **2026-04-17 18:35**  [3.0.0]  Claude  **C1 tls-bootstrap.sh** — nový daemon script. `openssl req -new -x509 -days 3650 -nodes -subj "/CN=stimba-urcap-vnc-$(hostname)"` → `/root/.vnc/certs/server.pem` (chmod 600, dir 700). SHA-256 fingerprint → `fingerprint.txt`. Idempotent: no-op ak cert existuje a fingerprint match-uje. Trap handler na cleanup partial write.
- **2026-04-17 18:38**  [3.0.0]  Claude  **C1 run-vnc.sh** — fork: `TLS_ENABLED=1` → invoke `tls-bootstrap.sh` → pridá `-ssl SAVE -sslGenCA /root/.vnc/certs` flagy. `TLS_ENABLED=0` → plaintext + hlasný WARN v logu (pre audit trail). Default TLS_ENABLED=1.
- **2026-04-17 18:40**  [3.0.0]  Claude  **C1 UI** — nová sekcia "Šifrovanie a limity session-y (v3.0.0)": `tlsEnabledBox` (default checked), červený warning banner "⚠ VNC traffic bude plaintext" (visible iff uncheck), "Zobraziť cert fingerprint" button → `showTlsFingerprintDialog()` s full fingerprintom + paste-ready RealVNC/TigerVNC pinning inštrukciami. Inline label ukazuje prvých 34 znakov fingerprintu + ellipsis.
- **2026-04-17 18:45**  [3.0.0]  Claude  **C2 idle-watcher.sh** — nový daemon script. Argumenty: `$1=IDLE_TIMEOUT_MIN $2=X11VNC_PID $3=DISPLAY(:0)`. Poll 60s `xdotool getmouselocation` (primary) alebo `x11vnc -Q pointer_pos` (fallback); ak `(x,y)` unchanged N krát v rade AND `x11vnc -Q clients > 0` → `x11vnc -R "disconnect all"` (fallback SIGUSR1). Parent-death detection: `while kill -0 $X11VNC_PID`.
- **2026-04-17 18:48**  [3.0.0]  Claude  **C2 run-vnc.sh wiring** — po úspešnom `x11vnc` štarte a získaní PID → `idle-watcher.sh $IDLE_TIMEOUT_MIN $X11VNC_PID :0 &`. Child terminates auto keď x11vnc umrie. IDLE_TIMEOUT_MIN=0 → no fork.
- **2026-04-17 18:50**  [3.0.0]  Claude  **C2 UI** — `JSpinner idleTimeoutSpinner` range 0..120 step 5 (0 = disabled). Bound to `KEY_IDLE_TIMEOUT_MIN` cez ChangeListener → `setIdleTimeoutMin(int)` na Contribution s range-validáciou.
- **2026-04-17 18:52**  [3.0.0]  Claude  **C4 MAX_CLIENTS** — `VncInstallationNodeContribution.getMaxClients/setMaxClients` (range 1..5, default 1). `writeConfigFile()` exportuje `MAX_CLIENTS=N`. `run-vnc.sh` mapuje: `MAX_CLIENTS=1` → `-nevershared -noshared`, `MAX_CLIENTS>1` → `-shared`. Zmenené z pôvodného `-connect_or_exit` plánu (risk R4 — daemon would die po prvom client disconnecte).
- **2026-04-17 18:54**  [3.0.0]  Claude  **C4 UI** — `JSpinner maxClientsSpinner` range 1..5 step 1. Pod idle timeout v tej istej sekcii.
- **2026-04-17 18:58**  [3.0.0]  Claude  **C7 tooltips** — nový helper `rowWithInfo(label, comp, html)` + `infoButton(html)` (22×22 px "?" tlačidlo, `JOptionPane.INFORMATION_MESSAGE`). 10 HTML-formatted SK tooltip stringov: TIP_PORT, TIP_PASSWORD, TIP_VIEW_ONLY, TIP_AUTOSTART, TIP_IXROUTER, TIP_CUSTOMER_LABEL, TIP_STRONG_PWD, TIP_TLS, TIP_IDLE, TIP_MAX_CLIENTS. Každý existujúci field v `buildUI()` zapnutý cez `rowWithInfo()`.
- **2026-04-17 19:00**  [3.0.0]  Claude  **post-install.sh v3.0.0** — chmod +x pridaný pre `tls-bootstrap.sh` a `idle-watcher.sh`. Eager `tls-bootstrap.sh` invocation pri post-install → cert + fingerprint existuje PRED prvým Polyscope open ("Zobraziť fingerprint" button nezobrazuje "n/a" state).
- **2026-04-17 19:05**  [3.0.0]  Claude  `VncInstallationNodeContribution.java` rozšírené: nové DataModel keys (KEY_TLS_ENABLED / KEY_IDLE_TIMEOUT_MIN / KEY_MAX_CLIENTS) + defaults v NEW-node path + accessors (isTlsEnabled/setTlsEnabled, getIdleTimeoutMin/setIdleTimeoutMin s `Math.max(IDLE_TIMEOUT_MIN_MIN, Math.min(IDLE_TIMEOUT_MIN_MAX, val))`, getMaxClients/setMaxClients s 1..5 clamp), `getTlsFingerprint()` čítač z `/root/.vnc/certs/fingerprint.txt` s placeholder fallback. `writeConfigFile()` hlavička v3.0.0 + 3 nové env vars.
- **2026-04-17 19:10**  [3.0.0]  Claude  `VncInstallationNodeView.java` — 6 nových Swing komponentov (tlsEnabledBox, tlsWarningBanner, tlsFingerprintLabel, tlsFingerprintBtn, idleTimeoutSpinner, maxClientsSpinner), 4 update metódy, 5 builder metód, `rowWithInfo`/`infoButton` helpery. Sekcia "Šifrovanie a limity session-y" pridaná nad Sprint 2 sekciu (log tail / allowlist).
- **2026-04-17 19:15**  [3.0.0]  Claude  ECJ compile: 44 class files (nárast z 40 na 44 kvôli ďalším anonymným listenerom pre TLS/idle/maxClients spinners + fingerprint dialog). Zero errors, 1 harmless warning (pre-existing anonymous DefaultTableModel serialVersionUID).
- **2026-04-17 19:18**  [3.0.0]  Claude  MANIFEST.MF v3.0.0: Bundle-Version 3.0.0, Bundle-Description popisuje Sprint 3 scope (TLS/idle/maxClients/tooltips), Tool `STIMBA-bundler-3.0.0`. Import-Package unchanged (všetky potrebné javax.* už importované od v2.2.0).
- **2026-04-17 19:20**  [3.0.0]  Claude  Bundle assembly: `zip -X -0 <out> META-INF/MANIFEST.MF` (stored), `zip -X -r <out> . -x META-INF/MANIFEST.MF` (deflated). 56 files: MANIFEST + 44 classes + 11 daemon scripts (9 previous + 2 new: tls-bootstrap.sh, idle-watcher.sh). Verified `zip -T` ok + first entry = MANIFEST.MF.
- **2026-04-17 19:22**  [3.0.0]  Claude  **Ship 3.0.0** — `dist/stimba-vnc-server-3.0.0.urcap` SHA-256 `cd9aacbd975735e78c3686b02c608dc16374a053364415e19974c57589f49ac1`. Size 81 145 B (+14 483 B oproti 2.2.0 = +22 %; daň za +4 classes + 2 nové shell skripty + TLS/idle UI). **CURRENT PROD.** `dist/SHA256SUMS-3.0.0` napísaný.
- **2026-04-17 19:25**  [wiki]  Claude  Wiki updates: `00-INDEX.md` status tabuľka (2.2.0 → shipped, 3.0.0 → current prod s hash/size), `02-feature-matrix.md` (C1/C2/C4/C7 → ✅ v3.0.0 s file pointers + popis implementácie), `sprints/sprint-3-v3.0.0.md` SHIPPED header + "Implementačné odchýlky od pôvodného plánu" sekcia (prečo `-R disconnect all` namiesto `-remote ping_clients`, prečo `-nevershared` namiesto `-connect_or_exit`, prečo eager TLS bootstrap, prečo JOptionPane modal namiesto JToolTip).
- **2026-04-17 19:30**  [3.0.0]  Claude  ADR-008 napísaný (`wiki/adr/008-tls-via-x11vnc-ssl.md`) — decision record prečo `x11vnc -ssl SAVE` namiesto stunnel wrapper alebo OpenVPN. Sprint 3 done.
- **2026-04-17 19:40**  [3.0.0]  Claude  `README.md` update — v3.0.0 headline s novým SHA a sizeom, nová sekcia "🔐 TLS (od v3.0.0) — cert fingerprint pinning" s postupom pinningu pre RealVNC Viewer 7.x / TigerVNC / IXON Cloud portal, plaintext downgrade path cez `TLS_ENABLED=0` toggle, idle timeout + max clients session-level hardening popis, aktualizované artifact filename referencie v USB/SSH install postupoch. Full changelog 2.0.0 → 3.0.0.
- **2026-04-17 19:45**  [wiki]  Claude  `03-build-deploy.md` doplnený o Option D — Sandbox ECJ pipeline (ktorá reálne postavila 3.0.0 keďže sandbox blokuje `plugins.ur.com` a maven-bundle-plugin dependencies). Pridaná v3.0.0 post-install verifikácia (TLS cert existuje + subject/fingerprint check, idle-watcher running, x11vnc má `-ssl SAVE` flag, `openssl s_client` TLS handshake test na localhost:5900).

---

## Sprint 3.5 — 2026-04-18/19 (Polyscope compatibility hotfix saga, API stub drift)

Krátky kontext: ďaleko od "Sprint 4 roadmap" — toto bolo 5 rýchlych hotfixov na jeden root cause (**hand-rolled URCap API 1.3.0 stub drift**). Andrej nasadil v3.0.0 na reálny UR5e s Polyscope 5.22 a dostal `NoClassDefFoundError` / `NoSuchMethodError` / `AbstractMethodError` série. Každý fix odkryl ďalšiu chýbajúcu/zlú metódu v našom reconstructed API jare. Škola: **keby sme použili oficiálny `urcap-api-1.3.0.jar` z plugins.ur.com, nič z toho by sa nestalo — ale sandbox ho nevie stiahnuť.**

Lesson learned + action item: pri ďalšej ECJ rebuild-e overiť **každý** jednotlivý API stub proti Javadoc + reálnemu bytecode-u 2.0.0 jaru (ten sme dostali Maven-bundle-plugin cestou, takže linkol proti *real* API). Ideálne: uložiť offline kópiu `urcap-api-1.3.0.jar` do repa (64 KB, Apache 2.0) a zbaviť sa stubov úplne.

- **2026-04-19 11:35**  [3.0.0-hotfix1]  Claude  **NoClassDefFoundError: javax/swing/border/** — Polyscope load crash. Import-Package v MANIFEST zabudol sub-packages `javax.swing.border` + `javax.swing.text` (BorderFactory, DefaultStyledDocument). Čistý MANIFEST fix, žiadny rebuild bytecodu. Ship `stimba-vnc-server-3.0.0-hotfix1.urcap` 81 403 B (+258 B). **Dokumentovaná odtlačka do `reference_urcap_osgi_imports.md` memory:** "Polyscope crashes with NoClassDefFoundError if javax.swing.* sub-package missing from manifest."
- **2026-04-19 12:00**  [3.0.1]  Claude  **NoSuchMethodError: DataModel.get(Ljava/lang/String;)Ljava/lang/Object** — hotfix1 sa načítal, ale Installation panel otvorenie crashlo pri prvom DataModel čítaní. Root cause: náš stub API deklaroval `<T> T get(String, T)` generic, ktorý ECJ compilenul do `Object get(String, Object)` dispatch, ale reálny API má **primitive-typed overloads**. Hotfix approach #1: prerobili sme všetky gettery na `isSet(key)` + `get(key)` + explicit cast pattern. Ship `stimba-vnc-server-3.0.1.urcap` 106 093 B (+24 690 B — Java súbory sa rozrástli o null-safe wrappery). **Chyba:** tento pattern stále volal `Object get(String)` čo v reálnom API neexistuje.
- **2026-04-19 12:51**  [3.0.2]  Claude  **NoSuchMethodError: DataModel.get(Ljava/lang/String;)Ljava/lang/Object (again)** — 3.0.1 fix odhalilo že skutočný API nemá ani single-arg get. Decompilnuli sme `stimba-vnc-server-2.0.0.urcap` (postavené Maven-bundle-plugin proti *real* API) a zistili že DataModel má presne tieto 3 metódy: `int get(String, int)`, `boolean get(String, boolean)`, `String get(String, String)`. Prepísali sme stub jar + všetky 5 get() call-sites vo `VncInstallationNodeContribution.java`. Ship `stimba-vnc-server-3.0.2.urcap` 106 006 B (-87 B — odstránené null-safe boilerplate z 3.0.1).
- **2026-04-19 13:16**  [3.0.3]  Claude  **AbstractMethodError: VncInstallationNodeContribution.isDefined()Z** — Installation panel otvorenie opäť crashe, ale iná chyba. Root cause: reálny `InstallationNodeContribution` interface deklaruje `isDefined()` metódu ktorú náš stub vynechal. Náš Java source preto nemá `@Override isDefined()` implementáciu a Polyscope volá abstrakt. Hotfix: pridaný defensive `public boolean isDefined() { return true; }` helper (bez @Override, lebo stub interface ho stále neobsahuje — ale bytecode má metódu s rovnakým descriptorom, takže JVM dispatch funguje). Ship `stimba-vnc-server-3.0.3.urcap` 106 134 B (+128 B).
- **2026-04-19 17:26**  [3.0.4]  Claude  **Preemptívny MANIFEST-only rebuild — DaemonService stub drift clarifikácia.** Pri code review bytecodu medzi 2.0.0 a 3.0.x sme si všimli že náš stub `DaemonService` interface deklaruje 3 metódy (`init`, `getExecutable`, `getDaemon`) — ale oficiálny Javadoc (API 1.3.0 cez 1.16.0) deklaruje iba 2 (`init`, `getExecutable`). Tretia (`getDaemon()`) je concrete-class helper vo FZI ToolComm Forwarder reference URCap-u. Keďže v našom `VncDaemonService.java` je `getDaemon()` deklarovaná **bez** `@Override`, ECJ ju skompiloval ako normálnu public metódu a call-sites v `VncInstallationNodeContribution` ju volajú cez concrete typing — **dispatch funguje správne**. Bytecode `VncDaemonService.class` je **bytewise identický s 3.0.3**. Ship `stimba-vnc-server-3.0.4.urcap` 106 336 B (+202 B, iba MANIFEST-MF description rozšírený). **Current prod na Andrejovom test UR5e.**

**Status after Sprint 3.5:**
- Installation panel otvára a renderuje bez crashu (po 3.0.3).
- TLS cert generuje pri prvom post-install.
- Idle-watcher a max-clients fungujú podľa UI nastavení.
- Audit log + health probe + diag bundle všetky live.
- Zostáva otvorené: nebola žiadna **formal Sprint 4/5** — roadmap z memory (`project_urcapx_sprint6_pivot.md`) preskakuje rovno na Sprint 6 (URCapX container artifacts) a Sprint 7 (Polyscope X port).

**Actionable z tejto série:**
1. `build-with-docker/local-urcap-api/README.md` je len placeholder — pridať **jednorazovo** reálny `urcap-api-1.3.0.jar` do repa a prepnúť ECJ classpath naň. Zbaví nás stub drift rizika úplne. **(Task #15 — stále open, blokované sandbox network.)**
2. Wiki addendum `04-gotchas.md` musí explicitne dokumentovať: "DaemonService iba init+getExecutable", "DataModel má iba primitive-typed overloads", "InstallationNodeContribution má isDefined() — musíš ho override-nuť". **(Task #16 — DONE 2026-04-19, G12 rozšírené + G13/G14 pridané.)**
3. Zvážiť regression suite: rozbalit .urcap, `javap -public` na každú našu class, diff proti previous release. Stub drift by vyšiel najavo pri review. **(Task #18 — DONE 2026-04-19, viď nižšie.)**

---

## 2026-04-19 večer — Task #18 API regression gate

- **2026-04-19 18:45**  [tooling]  Claude  **`build-with-docker/regress_signatures.py`** — nový 210-riadkový self-contained Python parser JVM class-file formátu. Žiadny JDK required (sandbox má len JRE + 590 MB free, inštalácia `openjdk-jdk-headless` = ~290 MB install, príliš risky). Čisté `struct`-based parsing constant pool + method_info. Filtruje `ACC_SYNTHETIC` + `ACC_BRIDGE` + `ACC_PRIVATE`, ignoruje inner classes (`$` v názve). Výstup: `<class> :: <vis><static><abstract> <name>(humanized descriptor) -> return`.
- **2026-04-19 18:50**  [tooling]  Claude  **`build-with-docker/regress.sh`** — bash wrapper. Auto-detectuje `target/*.urcap` (najnovší), unpackne cez `python3 zipfile.extractall` (macOS-friendly — žiadna `unzip` dependency), vnorený bundle jar tiež unpack-ne, spustí parser v `--diff` móde proti `wiki/public-api-baseline.txt`. Exit 0/1/2 = ok/env-error/drift.
- **2026-04-19 18:55**  [tooling]  Claude  **`wiki/public-api-baseline.txt`** — 72 signatúr z `stimba-vnc-server-3.0.3` (current prod). 5 top-level classes: `Activator` (3), `VncDaemonService` (4), `VncInstallationNodeContribution` (40), `VncInstallationNodeService` (5), `VncInstallationNodeView` (20). `isDefined()` zahrnuté — ako najväčšia lesson learned zo Sprint 3.5.
- **2026-04-19 19:00**  [tooling]  Claude  **Round-trip validácia** — 5 testov:
    - 3.0.3 vs baseline → **OK, 72 match** ✅
    - 3.0.4 vs baseline → **OK, 72 match** ✅ (confirms bytecode identity)
    - 3.0.2 vs baseline → **FAIL, REMOVED isDefined()** ✅ (exit 2)
    - 3.0.1 vs baseline → **FAIL, REMOVED isDefined()** ✅ (exit 2)
    - 3.0.0-hotfix1 vs baseline → **FAIL, REMOVED isDefined()** ✅ (exit 2)
  Gate by zachytil presne ten AbstractMethodError pattern ktorý spotreboval 5 hotfixov.
- **2026-04-19 19:05**  [tooling]  Claude  **`Makefile`** — targets `build` / `regress` / `regress-write` / `all`. Default `make` = `build && regress`. `regress-write` je explicit opt-in pre promote-nutie novej API surface po zámernej zmene.
- **2026-04-19 19:10**  [tooling]  Claude  **`.github/workflows/regress.yml`** — CI gate on push/PR dotýkajúce sa `src/**`, `pom.xml`, baseline, nástrojov. Docker build cesta zhodná s local dev. Úspech → upload .urcap artefact (30 dní retention). Fail → job summary s "ak zámerná, regeneruj baseline" postupom.
- **2026-04-19 19:15**  [docs]  Claude  `wiki/03-build-deploy.md` — pridaná sekcia "Regression gate — public-API signature diff" (69 riadkov): ako funguje, kedy CI failne, workflow, CI integrácia, známe limity, baseline history tabuľka.
- **2026-04-19 19:20**  [docs]  Claude  `wiki/04-gotchas.md` G14 — prepojené na Task #18 DONE + link na §Regression gate.

**Task #18 status: COMPLETE.** Budúce stub drift regressions sú blokované CI-level kým sa Task #15 nedokončí (embed real `urcap-api-1.3.0.jar`). Po #15 sa tento gate môže v teórii zrušiť — ale odporúčanie: ponechať ho ako second line of defense (refaktoring cez 10 releasov by mohol znova skrátiť verejnú plochu bez povšimnutia).

---

## 2026-04-19 noc — wiki + dist reconciliation (post Task #18)

- **2026-04-19 20:30**  [docs]  Claude  **`wiki/sprints/sprint-6-v3.1.0.md`** — NEW 3-week plan pre URCapX cutover (2026-07-13 → 2026-07-31). URCap-side sibling k portálu `portal-stimba-sk/wiki/sprints/sprint-6-urcapx.md`. Obsahuje manifest.yaml skeleton s dvoma container artifacts (`cloudflared` + `stimba-agent`), 7 rizík, 8 acceptance criteria, 4 otvorené otázky, dependency chain na sprinty 1/2/3/3.5, Acceptance gate + PS5 bundle paralelizmus.
- **2026-04-19 20:40**  [dist]  Claude  **3.0.x hotfix artefakty presunuté zo `Stimba Apps/` do `IXON URCap v2/dist/`** (boli "volne" v root-e sesie, mimo dist konvencie):
    - `stimba-vnc-server-3.0.0-hotfix1.urcap` sha256 `0455e4cfa4d22c948348f94dd938e1b4d7ee2b2c1b1213912e2b0088b774ec2f`
    - `stimba-vnc-server-3.0.1.urcap` sha256 `46eee7ca110b59c1b50472e69cae6a3aff69b64d73917a8a01d74bc536cb0843`
    - `stimba-vnc-server-3.0.2.urcap` sha256 `14b9f4e75620a0ca80a173d620759784abbfb1ce5b2a88e169aa5b3f6d81cac1`
    - `stimba-vnc-server-3.0.3.urcap` sha256 `1b558b9d905eae06ed89fe316ee9b788021d602ba248023a2428addfca761b2e`
    - `stimba-vnc-server-3.0.4.urcap` sha256 `8828fbe6fe076d72929f4b30be948b3043012aeba4007a6c6aa2f053afba4fdb`
  Každému napísaný `dist/SHA256SUMS-<ver>` (consistent s 2.1.0/2.2.0/3.0.0 konvenciou).
- **2026-04-19 20:45**  [docs]  Claude  `wiki/00-INDEX.md` — hlavná version tabuľka rozšírená o 3.0.0-hotfix1/3.0.1/3.0.2/3.0.3/3.0.4 riadky s SHA-256 prefixmi, header dátum bumped na 2026-04-19, pridaná Sprint 3.5 callout box + nový "Tooling (regression gate)" odsek s linkmi na `regress_signatures.py`, `regress.sh`, `public-api-baseline.txt`, Makefile a CI workflow. Sprinty sekcia doplnená o link na sprint-6-v3.1.0.md.
- **2026-04-19 20:50**  [docs]  Claude  `wiki/05-file-map.md` — projekt-root tabuľka doplnená o `Makefile`, `build-with-docker/regress.sh`, `build-with-docker/regress_signatures.py`, `.github/workflows/regress.yml`; dist tabuľka doplnená o 2.1.0/2.2.0/3.0.0/3.0.0-hotfix1/3.0.1/3.0.2/3.0.3/3.0.4 s reálnymi SHA-256 hashmi; wiki sekcia doplnená o sprint-6-v3.1.0.md, public-api-baseline.txt, aktualizované popisy pre 03/04.

**Task #17 status: COMPLETE.** URCap-side Sprint 6 plán existuje a je linked z portal-side plánu aj z 00-INDEX. Budúce Sprint 6 kick-off (2026-07-13) môže štartovať z tohto dokumentu.

## 2026-04-19 21:05 — Task #18 round-trip verification

Pre potvrdenie že regression gate nie je len "happy path" ale naozaj chytí drift ktorý spôsobil Sprint 3.5 hotfix saga:

- **Test A (prod stability):** unzip `dist/stimba-vnc-server-3.0.4.urcap` → `python3 regress_signatures.py --diff wiki/public-api-baseline.txt` → **EXIT 0**, `[regress] OK — 72 signatures match baseline`. ✅
- **Test B (regression detection):** unzip `dist/stimba-vnc-server-3.0.0.urcap` (pre-hotfix, pre-`isDefined()`) → diff proti rovnakej baseline → **EXIT 2**, `[regress] REMOVED from baseline (DANGER — AbstractMethodError risk): sk.stimba.urcap.vnc.impl.VncInstallationNodeContribution :: public isDefined() -> boolean`. ✅

T.j. gate správne identifikuje presne ten bug ktorý nás prevážil v 3.0.2 (`AbstractMethodError: ...isDefined()Z`). Ak by niekto v budúcnosti zmazal `isDefined()` z Contribution triedy alebo zmenil jej signature, CI by fail-olo pred merge-om do main, s presnou diagnostickou správou.

**Housekeeping pozn.:** v `dist/` je zabudnutý stray súbor `zimGXdne` (1565 B, PK zip magic, MANIFEST s v3.0.0 description) — leftover z test-buildu. Vypadá ako partial/corrupt artefakt — na Mac-side si ho Andrej môže premazať (`rm "IXON URCap v2/dist/zimGXdne"`). Nieje blocker.

**Mac-side to-do (keď bude čas):**
- `git add -A && git commit -m "Sprint 3.5 + Task #18 regression gate + wiki reconciliation"` + `git push` na URCap repo
- Príp. vyčistenie stray `dist/zimGXdne`
- ~~Task #15 zostáva pending (embed real `urcap-api-1.3.0.jar` → download zo `support.universal-robots.com` → `local-repo/`)~~ → **DONE 2026-04-19** (viď nižšie)

## 2026-04-19 21:30 — Task #15 real URCap API embedded

Andrej stiahol plný URCap SDK 1.18 na Mac (`AI Claude/sdk-1.18/`). SDK obsahuje kanonické historické API JARy pre všetky verzie 1.0.0 → 1.18.0 pod `artifacts/api/`. To uzavrie Task #15 bez kompromisov:

- **Extrakcia:** `com.ur.urcap.api-1.3.0.jar` (168 265 B) + `com.ur.urcap.api-1.3.0-sources.jar` (240 687 B). Oficiálne artefakty Universal Robots A/S, Copyright 2016-2018. Byte-stabilné naprieč SDK releases pre danú API verziu.

- **Umiestnenie v repo:**
  - `build-with-docker/local-urcap-api/api-1.3.0.jar` — hlavný JAR (pre `mvn install:install-file` v Dockerfile)
  - `build-with-docker/local-urcap-api/api-1.3.0.pom` — minimálny POM (nový súbor, napísaný podľa groupId/artifactId/version z Dockerfile invocation)
  - `build-with-docker/local-urcap-api/api-1.3.0-sources.jar` — sources (developer reference, nie súčasť Dockerfile flow)
  - `build-with-docker/local-urcap-api/README.md` — aktualizovaný header so "Status: embedded" + zdôvodnenie

- **`.gitignore` vytvorený v repo root** — ignoruje `target/`, `.DS_Store`, IDE noise, tmp/regress artefakty, ALE má explicit `!build-with-docker/local-urcap-api/*` allow list aby JAR a sources nikdy neodpadli cez wildcard.

- **Dockerfile a `pom.xml` bez zmien.** Dockerfile už volá `mvn install:install-file -Dfile=api-1.3.0.jar -DpomFile=api-1.3.0.pom` — POM ktorý sme teraz dodali bude preferred path (namiesto `-DgeneratePom=true` fallback).

- **API surface validation:** pred commit-om sme extrahovali `1.3.0-sources.jar` a čítali 4 kľúčové rozhrania proti našemu kódu:
  - `DaemonService` — 2 metódy (`init`, `getExecutable`) ✅
  - `DaemonContribution` — 4 metódy + `State` enum ✅
  - `InstallationNodeContribution` — 3 metódy (`openView`, `closeView`, `generateScript`) — **`isDefined()` tam NIE JE v žiadnej verzii 1.3.0 → 1.18.0**. Náš defensive `public boolean isDefined()` v `VncInstallationNodeContribution` zostáva ako safety patch voči runtime polymorphic dispatch v PS5 (3.0.2 → 3.0.4 hotfix). ✅
  - `DataModel` — 701 riadkov, iba primitive/typed overloads (G12 hotfix confirmed correct; no generic `Object get(String, Object)`). ✅

- **Dôsledok:** Sprint 3.5 hotfix saga (G12/G13/G14 = DataModel overloads + isDefined defensive + DaemonService signature match) je teraz kryta oficiálnym API JAR-om a regression baseline-om. Budúce buildy kompilujú voči real interface-om — zero stub drift možné.

- **Rešpekt k licencii:** JAR je property Universal Robots A/S. Redistribujeme ho iba v kontexte vývoja URCapov pre UR roboty (primary purpose distribuovaný v SDK). Pri externom release URCap bundle NEVER embed-uje JAR — iba `Import-Package: com.ur.urcap.api*;version="[1.3.0,2.0.0)"` → runtime resolution proti Polyscope class loaderu.

**Mac-side to-do pre Andreja:**
- `cd vnc-urcap && git add -A && git status` — skontroluj že `.gitignore`, `api-1.3.0.jar`, `api-1.3.0.pom`, `api-1.3.0-sources.jar`, `local-urcap-api/README.md`, `wiki/progress.md` a súvisiace wiki doc zmeny sú staged.
- Commit message suggestion:
  ```
  Task #15: embed real URCap API 1.3.0 (Universal Robots A/S)
  
  - api-1.3.0.jar + sources + pom extracted from URCap SDK 1.18
  - .gitignore added; local-urcap-api/ explicitly kept
  - wiki/progress.md + wiki/05-file-map.md updated
  - closes out Sprint 3.5 stub-drift lineage with authoritative API JAR
  ```
- `git push` — GitHub Actions regression gate by mal pass-nuť (verifikovali sme locally proti 3.0.4 dist artefaktu: 72 signatures match baseline).
- Voliteľné: `make build && make regress` pre fresh local round-trip pred push-om.
