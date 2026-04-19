# STIMBA VNC Server URCap v2

**Čo to je:** URCap pre Universal Robots e-Series (UR3e / UR5e / UR10e / UR16e / UR20)
ktorý spustí `x11vnc` server pripojený na Polyscope DISPLAY :0. Umožňuje vzdialený
náhľad + ovládanie robotickej obrazovky cez IXON Cloud VNC tunel (port 5900).

**Verzia:** 3.0.0 (current prod, Sprint 3 shipped 2026-04-17)
**URCap API:** 1.3.0 (pokrýva Polyscope 5.0 → 5.26 LTS, január 2026)
**Autor:** Andrej Bielik — STIMBA, s. r. o.
**Dátum:** 2026-04-17
**Artefakt:** `dist/stimba-vnc-server-3.0.0.urcap` (81 145 B, SHA-256 `cd9aacbd975735e78c3686b02c608dc16374a053364415e19974c57589f49ac1`)

---

## ⚠️ POVINNÝ bezpečnostný krok PRED nasadením

**Zmeniť predvolené heslo `easybot` na silné heslo.**

Universal Robots vo všetkých verziách Polyscope 5.x (vrátane 5.26 LTS z januára 2026)
dodáva roboty s admin heslom `easybot`. To isté heslo je zároveň heslom `root`
používateľa v Linuxovom OS. Ak ho nezmeníš, akékoľvek iptables + VNC heslo sú
iba kozmetikou — útočník sa cez SSH dostane priamo do systému.

Postup na robote:

1. Na Polyscope teach pendante: **Hamburger menu → Settings → Password → Admin**
2. Zadaj staré heslo: `easybot`
3. Nastav nové heslo: minimálne 8 znakov (STIMBA odporúča 16+ s mixom znakov)
4. Confirm. Od tejto chvíle je to aj nové `root` heslo pre SSH.

**Pozor:** admin heslo nie je obnoviteľné. Pri strate je nutné preflashovanie
SD karty z UR image. Ulož do 1Password / STIMBA vault.

Tento URCap obsahuje tripwire, ktorý pri štarte detekuje zostávajúce
`easybot` heslo a zaloguje viditeľné varovanie do `/var/log/urcap-vnc.log`
a do Polyscope daemon logu. V regulovanom prostredí (NIS2, TISAX, IEC 62443)
nastav `URCAP_VNC_REQUIRE_STRONG_PWD=true` — URCap sa odmietne spustiť, kým
heslo nie je zmenené.

---

## 🔒 Model prístupu (v2.0.2)

```
┌──────────────┐   IXON Cloud     ┌────────────┐   LAN 192.168.0.0/24    ┌──────────┐
│ Operátor     │  (TLS + 2FA)     │ IXrouter   │   OT VLAN               │ UR10e    │
│ /IXON portál │ ────────────────▶│192.168.0.100│ ─────────────────────▶│192.168.0.1│
│ /IXON Vision │                  │            │   tcp/5900              │ x11vnc   │
└──────────────┘                  └────────────┘                         └──────────┘
                                        ▲                                    │
                                        │                                    │
                                        └── jediný povolený zdroj ──────────┘
                                            (iptables ACCEPT from 192.168.0.100)
                                            (všetky ostatné DROP)
```

- x11vnc **bindne 0.0.0.0:5900** (nie 127.0.0.1) — aby IXrouter ako samostatné
  zariadenie na LAN mohol tunel forwardnúť.
- **iptables INPUT chain:** `ACCEPT -s ${IXROUTER_IP} --dport 5900` + `DROP --dport 5900`
  pre všetky ostatné zdroje. Kernel-level whitelist — útočník na OT VLAN-e
  nemá na port vôbec šancu.
- **VNC autentifikácia:** RFB heslo z `Installation → VNC Server → Password`,
  hashované do `/root/.vnc/passwd`, chmod 600.
- **IXON portál:** 2FA + SSO + RBAC na strane IXON Cloud.

### Environment variables (override z Polyscope UI alebo manuálne cez systemd env)

| Premenná                         | Default           | Popis                                                 |
| -------------------------------- | ----------------- | ------------------------------------------------------ |
| `VNC_PORT`                       | `5900`            | TCP port x11vnc + iptables whitelist                   |
| `VNC_PASSWORD`                   | `ixon`            | RFB heslo (prepíš cez Installation tab)                |
| `VNC_VIEW_ONLY`                  | `false`           | `true` = iba pozeranie, žiadny vstup                   |
| `IXROUTER_IP`                    | `192.168.0.100`   | IP IXroutera — jediný whitelisted zdroj (STIMBA fleet) |
| `URCAP_VNC_REQUIRE_STRONG_PWD`   | `false`           | `true` = refuse start ak root je stále `easybot`       |

### Per-robot override cez config file

Ak má konkrétny robot IXrouter na inej IP (napr. 10.10.10.2), vytvor na robotovi
ako `root`:

```bash
cat > /root/.urcap-vnc.conf <<'EOF'
IXROUTER_IP=10.10.10.2
VNC_PORT=5901
URCAP_VNC_REQUIRE_STRONG_PWD=true
EOF
chmod 600 /root/.urcap-vnc.conf
```

Súbor sa načíta pri každom štarte daemona PRED vyhodnotením defaultov,
takže prepíše zapečené hodnoty bez rekompilácie URCapu. `stop-vnc.sh`
ho tiež číta — aby pri stope vymazal presne tie iptables pravidlá,
ktoré sa pri štarte vložili.

---

## 🔐 TLS (od v3.0.0) — cert fingerprint pinning

Od v3.0.0 je default `TLS_ENABLED=1` — daemon obaľuje RFB stream self-signed
SSL certifikátom generovaným lokálne do `/root/.vnc/certs/server.pem`.

### Ako to funguje

1. **Inštalácia URCapu:** `post-install.sh` eagerly spustí `tls-bootstrap.sh`
   → `openssl req -new -x509 -days 3650 -nodes -subj "/CN=stimba-urcap-vnc-<hostname>"`
   → kľúč + cert v `server.pem`, SHA-256 fingerprint do `fingerprint.txt`.
2. **Polyscope UI:** sekcia "Šifrovanie a limity session-y (v3.0.0)" obsahuje
   checkbox **TLS šifrovanie** (default checked) + tlačidlo **Zobraziť cert
   fingerprint** → modal dialog s full hex hashom a paste-ready SK inštrukciou.
3. **Prvý remote connect:** klient (RealVNC / TigerVNC / IXON portal) uvidí
   "untrusted cert" warning. Paste-ni fingerprint zo Zobraziť dialógu do
   trust-store klienta. Budúce pripojenia fingerprint verifikujú → tripwire
   pri MITM / cert podvrhu.

### Pinning v RealVNC Viewer 7.x

1. Pri prvom pripojení sa objaví "Identity check failed" dialog.
2. Klik na **Continue** → v Options → Security dialógu sa zobrazí SHA-256 fingerprint.
3. Porovnaj ho znak po znaku s fingerprintom zo URCap UI "Zobraziť cert fingerprint".
4. Ak sa zhodujú: klik **Yes, I trust this signature** → RealVNC si cert uloží
   do `~/.vnc/known_hosts`.
5. Budúce pripojenia zlyhajú s krikľavým warning-om, ak sa cert zmení.

### Pinning v TigerVNC (multi-platform)

```bash
# Prvé pripojenie: TigerVNC vyprinte cert + fingerprint do terminálu.
vncviewer -SecurityTypes VeNCrypt,TLSVnc <IP>:5900

# Ulož server.pem do CA store klienta:
openssl s_client -connect <IP>:5900 -showcerts </dev/null | \
    openssl x509 -outform PEM > ~/.vnc/stimba-<robot-id>.pem

# Pri každom pripojení verifikuj cert:
vncviewer -SecurityTypes VeNCrypt,TLSVnc -X509CA ~/.vnc/stimba-<robot-id>.pem <IP>:5900
```

### Pinning v IXON Cloud portal

IXON portal (Device → Remote Access → VNC service) podporuje fingerprint pinning:
1. **Add Service** → VNC → zapni **"Verify certificate fingerprint"**.
2. Skopíruj SHA-256 fingerprint z URCap UI do poľa **Expected fingerprint**.
3. IXON portal pri každom tunel-connecte porovná fingerprint. Zlyhanie =
   upozornenie v IXON audit logu.

### Downgrade na plaintext (kompatibilita s TightVNC 2.7)

Ak máš starý klient ktorý nepodporuje SSL wrap, v UI uncheck **TLS šifrovanie**
→ objaví sa červený warning banner "⚠ VNC traffic bude plaintext. OK len pre LAN
testing." Táto zmena sa zaloguje do audit logu (`event=tls_disabled`) — v
regulovanom prostredí (NIS2, TISAX) je lepšie upgrade-núť klienta.

### Session-level hardening (od v3.0.0)

- **Idle timeout** (JSpinner 0..120 min): po koľkých minútach bez pointer
  aktivity sa všetci pripojení klienti odpoja. Default 30 min. `idle-watcher.sh`
  polluje `xdotool getmouselocation` (fallback `x11vnc -Q pointer_pos`) každých
  60 sekúnd a kick-uje `x11vnc -R "disconnect all"`. Detekuje iba VNC client
  activity — teach pendant touch sa nepočíta.
- **Max klientov** (JSpinner 1..5): `MAX_CLIENTS=1` → `-nevershared -noshared`,
  `MAX_CLIENTS>1` → `-shared`. Pre security default 1 (iba jeden operátor).

---

## Changelog

### v3.0.0 — 2026-04-17 (TLS + session hardening + tooltips, Sprint 3)

1. **C1 TLS wire-level encryption** — x11vnc `-ssl SAVE` s self-signed cert
   autogen, SHA-256 fingerprint pinning workflow (UI "Zobraziť cert fingerprint"
   button), eager bootstrap v post-install, fallback na plaintext cez UI toggle
   (červený warning banner).
2. **C2 Pointer-idle auto-disconnect** — nový `idle-watcher.sh` daemon sibling
   proces (xdotool + `x11vnc -Q pointer_pos` fallback). Range 0..120 min default
   30. Kick cez `-R "disconnect all"` alebo SIGUSR1 fallback.
3. **C4 Max clients gate** — JSpinner 1..5, default 1. `run-vnc.sh` mapuje na
   `-nevershared -noshared` (1) alebo `-shared` (>1).
4. **C7 In-UI (?) tooltips** — 10 HTML-formatted SK explanačných stringov na
   každom fielde (port, heslo, view-only, autostart, IXROUTER_IP, customer label,
   strong-pwd, TLS, idle, max-clients). Modal `JOptionPane.INFORMATION_MESSAGE`.
5. **ADR-008 napísané** — dokumentuje prečo `x11vnc -ssl SAVE` namiesto stunnel
   wrapper (single-process lifecycle, žiadna nová závislosť, airgap-friendly).

### v2.2.0 — 2026-04-17 (observability, Sprint 2)

1. **B1 Live log tail** — posledných 50 riadkov `/var/log/urcap-vnc.log` v UI,
   opt-in 3s refresh (Deque ring buffer, pure Java, žiadny subprocess).
2. **B2 Diag bundle export** — "Export diagnostiku" button → tar.gz do
   `/root/urcap-vnc-diag-*.tar.gz`. Obsah: log + config + iptables-save +
   process list + health probe + MANIFEST. Redakcia hesiel pred zbalením.
3. **B3 Dočasný iptables allowlist** — IP + TTL (15/30/60 min), cron sweeper
   každú minutu. Štátový súbor `/var/log/urcap-vnc-temp-allowlist`.
4. **B4 JSON-Lines audit log** — `-accept`/`-gone` hooky emitujú
   `{"event":"accept|gone","ip":…,"duration_s":…}` do `/var/log/urcap-vnc-audit.log`.
5. **B6 "Test connection" button** — bash `/dev/tcp` probe na localhost:5900,
   emituje JSON s protocol/port.

### v2.0.2 — 2026-04-17 (fleet defaults + per-robot override)

1. **Default `IXROUTER_IP` zmenený na `192.168.0.100`** — overená IP IXroutera
   vo väčšine STIMBA inštalácií (v2.0.1 mal `192.168.0.10`, čo bola chybná
   asumpcia). Drvivá väčšina robotov teraz funguje bez akéhokoľvek zásahu.
2. **Per-robot override cez `/root/.urcap-vnc.conf`** — shell file, ktorý
   sa sourcuje PRED evalváciou defaultov. Obsahuje jednoduché `KEY=VALUE`
   páry (`IXROUTER_IP`, `VNC_PORT`, `URCAP_VNC_REQUIRE_STRONG_PWD`).
   Umožňuje zmeniť nastavenie bez rekompilácie URCapu — jeden súbor cez
   SSH alebo USB.
3. **`stop-vnc.sh` tiež číta config file** — garantuje, že `iptables -D`
   pri shutdown maže presne tie pravidlá, ktoré run-vnc.sh vložil
   (inak by sa akumulovali stale ACCEPT rules na defaultnej IP).

### v2.0.1 — 2026-04-17 (security hotfix)

Opravy oproti v2.0.0, ktoré boli nutné pre reálne nasadenie v STIMBA fleete:

1. **Oprava bindingu** — v2.0.0 používal `-localhost` (x11vnc bol viazaný na
   127.0.0.1). To funguje iba ak IXON gateway beží na tom istom hoste (IXagent
   inštalovaný na robotovi). STIMBA používa **IXrouter ako samostatné
   zariadenie** na LAN (typicky 192.168.0.10), ktorý 127.0.0.1 nedosiahne.
   v2.0.1 binduje 0.0.0.0 a bezpečnosť rieši kernel-level iptables whitelistom.
2. **iptables source-IP whitelist** — `ACCEPT` iba z `IXROUTER_IP`, `DROP`
   všetko ostatné na porte 5900. Defence-in-depth: iptables + VNC heslo
   + IXON Cloud 2FA.
3. **Resource packaging fix** — v2.0.0 mal chybný bundle plugin config a
   shell scripty sa neocitli v .urcap. v2.0.1 ich má explicitne zapečené v
   `sk/stimba/urcap/vnc/impl/daemon/` (aj manuálny repack to overí).
4. **`easybot` tripwire** — pri štarte shell script detekuje, či `root`
   heslo v `/etc/shadow` je stále factory-default `easybot` a zaloguje
   viditeľné varovanie; voliteľne refuse-to-start cez
   `URCAP_VNC_REQUIRE_STRONG_PWD=true` (odporúča sa v NIS2/TISAX prostrediach).
5. **stop-vnc.sh čistí iptables** — žiadne hromadenie duplicitných pravidiel
   pri reštarte URCapu.

### v2.0.0 — 2026-04-16 (initial)

1. **Používa URCap API 1.3.0** namiesto novších — zaručene funguje na všetkých
   e-Series kontroléroch bez ohľadu na verziu Polyscope (5.0 → 5.26 LTS).
2. **Inštaluje `x11vnc` cez `apt-get`** (robot musí byť online iba pri prvom
   štarte — vďaka IXrouteru má uplink). Žiadne ručné bundlovanie `.deb` balíkov.
3. **Attachuje na DISPLAY :0** — to je Polyscope Xorg session. Nevytvára nové
   Xvnc — používateľ vidí presne to, čo vidí operátor pri robote.
4. **OSGi bundle manifest je generovaný cez Felix `maven-bundle-plugin`** —
   identický pattern ako FZI External Control URCap (battle-tested od 2019).
5. **Má kompletný životný cyklus** — Activator, DaemonService, InstallationNode,
   Swing UI, stop script.

## Kompatibilita s Polyscope 5.26 LTS (január 2026)

Tento URCap je postavený na URCap API 1.3.0, ktoré UR garantuje forward-compat
až do Polyscope 10.x. V testoch proti PolyScope 5.26 LTS (január 2026)
— kompatibilný, žiadne API regresie, žiadne bezpečnostné zmeny,
ktoré by si vyžadovali úpravu. URCap možno nechávať nasadený aj po update
robota na 5.26 LTS bez rekompilácie.

---

## Štruktúra projektu

```
vnc-urcap/
├── pom.xml                      Maven build (Felix bundle plugin + exec rename)
├── README.md                    ← tento súbor
├── src/main/java/sk/stimba/urcap/vnc/impl/
│   ├── Activator.java                        OSGi bootstrap
│   ├── VncDaemonService.java                 procesový supervízor (ProcessBuilder)
│   ├── VncInstallationNodeService.java       factory pre UI node
│   ├── VncInstallationNodeContribution.java  persistencia + actions
│   └── VncInstallationNodeView.java          Swing formulár (port/heslo/state)
├── src/main/resources/daemon/
│   ├── run-vnc.sh               bash entrypoint (apt-get install + exec x11vnc)
│   └── stop-vnc.sh              čistý shutdown
└── build-with-docker/
    ├── Dockerfile               reprodukovateľný build container
    └── local-urcap-api/
        └── README.md            kde získať api-1.3.0.jar
```

---

## Build — 3 spôsoby

### 1) Docker (odporúčaný, funguje kdekoľvek)

```bash
# Jednorázovo si stiahni URCap API JAR podľa návodu v
# build-with-docker/local-urcap-api/README.md a polož ho tam.

cd vnc-urcap
docker build -t stimba/urcap-builder -f build-with-docker/Dockerfile build-with-docker
docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 stimba/urcap-builder

# Výstup:
#   target/vnc-server-2.0.0.urcap
```

### 2) Lokálny Maven na Macu

```bash
# Predpoklady:
#   - Homebrew: brew install openjdk@8 maven
#   - URCap API JAR nainštalovaný do lokálneho Maven repo:

mvn install:install-file \
    -Dfile=/path/to/URCap-x.y.z/sdk/com/ur/urcap/api/1.3.0/api-1.3.0.jar \
    -DgroupId=com.ur.urcap \
    -DartifactId=api \
    -Dversion=1.3.0 \
    -Dpackaging=jar \
    -DgeneratePom=true

# Potom build:
cd vnc-urcap
mvn -B clean package

# Výstup:
#   target/vnc-server-2.0.0.urcap
```

### 3) Priamy deploy z Macu na robot (single-shot)

Tento profil zabalí URCap a rovno ho nahrá cez SSH:

```bash
# Predpoklad: máš nainštalovaný sshpass (brew install hudochenkov/sshpass/sshpass)
mvn -B -Premote install \
    -Dur.host=192.168.1.101 \
    -Dur.pass=easybot
```

Profil `-Premote` urobí:
1. `scp vnc-server-2.0.0.urcap root@192.168.1.101:/root/.urcaps/`
2. `chmod 644`
3. `systemctl restart urcontrol.service` (reštart Polyscope — načíta URCap)

---

## Manuálny deploy (bez Mavenu)

Artefakt v dist adresári:
`dist/stimba-vnc-server-3.0.0.urcap` (81 145 B, SHA-256 `cd9aacbd975735e78c3686b02c608dc16374a053364415e19974c57589f49ac1`).

### USB inštalácia (bez SSH)

1. Skopíruj `stimba-vnc-server-3.0.0.urcap` na USB kľúč (FAT32/exFAT).
2. Na Polyscope teach pendante: **Hamburger menu → Settings → System → URCaps**.
3. **+ (Install)** → vyber súbor z USB → **Open**.
4. Polyscope si vyžiada **Restart** — potvrď.
5. Po štarte: **Installation → URCaps → STIMBA VNC Server**.
6. Nastav **Heslo** (to je VNC/RFB heslo, nie admin!) → **Apply**.
7. Autostart je defaultne zapnutý — daemon sa spustí automaticky.

### SSH inštalácia (ak máš prístup cez LAN)

```bash
# 0) BEZPEČNOSTNÝ KROK — ak si ešte nezmenil easybot, zmeň TERAZ
#    (Polyscope → Hamburger → Settings → Password → Admin)

# 1) copy na robot (použiť NOVÉ silné heslo, nie easybot)
scp dist/stimba-vnc-server-3.0.0.urcap root@192.168.0.1:/root/.urcaps/

# 2) (ak máš staršiu verziu) odstráň ju
ssh root@192.168.0.1 "rm -f /root/.urcaps/stimba-vnc-server-2.*.urcap"

# 3) reboot Polyscope aby URCap načítal
ssh root@192.168.0.1 "systemctl restart urcontrol.service"

# 4) V Polyscope GUI:
#    Installation → URCaps → VNC Server (STIMBA)
#    Nastaviť VNC heslo (nie admin heslo!) → Apply & Start

# 5) Verifikovať iptables whitelist
ssh root@192.168.0.1 "iptables -L INPUT -n --line-numbers | grep 5900"
# Výstup by mal obsahovať:
#   1    ACCEPT  tcp  --  127.0.0.1      0.0.0.0/0    tcp dpt:5900
#   2    ACCEPT  tcp  --  192.168.0.100  0.0.0.0/0    tcp dpt:5900
#   3    DROP    tcp  --  0.0.0.0/0      0.0.0.0/0    tcp dpt:5900
```

### Per-robot override (ak IXrouter nie je na 192.168.0.100)

```bash
ssh root@<robot-ip> 'cat > /root/.urcap-vnc.conf <<EOF
IXROUTER_IP=10.10.10.2
EOF
chmod 600 /root/.urcap-vnc.conf'
# Reštartuj URCap (Installation → VNC Server → Stop, Start) — novú IP si vezme zo súboru.
```

---

## Test cez IXON portal

1. **Overiť** že IXrouter má v Services publikovaný **VNC** servis pre IP
   UR10e a port 5900. (IXON portal → Device → Services → Add Service.)
2. **Otvoriť** IXON Cloud portal → Device → Web Access.
3. **Vybrať** VNC službu → klient si stiahne VNC connection (alebo použije
   built-in web VNC klienta).
4. **Heslo** = to, ktoré si zadal v URCap Installation tab (default `ixon`).

Pozrieš presne to, čo vidí operátor pri robote. Môžeš klikať, ovládať Polyscope.

---

## Troubleshooting

### URCap sa načíta ale daemon nebeží

1. SSH na robot: `ssh root@<IP>`
2. Logy: `tail -100 /var/log/urcap-vnc.log`
3. Overiť lock: `cat /tmp/urcap-vnc.lock && ps -p $(cat /tmp/urcap-vnc.lock)`
4. Overiť iptables: `iptables -L INPUT -n | grep 5900`
5. Manuálny štart: `bash /tmp/urcap-vnc-run.sh`

### apt-get install x11vnc failed

Robot nemá uplink. Over IXrouter:
```bash
ping -c 2 8.8.8.8
ping -c 2 deb.debian.org
```
Ak ping prechádza ale apt nie, pravdepodobne sú zastarané certifikáty — inštaluj
`x11vnc` ručne z lokálneho `.deb`:
```bash
scp x11vnc_*.deb root@<IP>:/root/
ssh root@<IP> "dpkg -i /root/x11vnc_*.deb"
```

### Pripojenie cez IXON sa ukončí hneď po pripojení

Najpravdepodobnejšie:
- **IXrouter má inú IP ako default 192.168.0.10.** Zisti skutočnú IP
  IXroutera (IXON portál → Device → LAN settings) a nastav `IXROUTER_IP`
  env variable pred štartom URCap-u. iptables DROP odmieta pakety zo zlej
  zdrojovej IP — to je presne tento symptóm.
- `x11vnc` beží, ale DISPLAY :0 nie je ešte inicializovaný (Polyscope ešte
  nenaštartoval). Počkaj 30s po zapnutí robota a skús znova.
- IXrouter Service mieri na zlý port. Mal by smerovať na `<UR-IP>:5900`
  (napr. `192.168.0.1:5900`), NIE na `127.0.0.1:5900`.

### Ako overiť že heslo `easybot` bolo zmenené

```bash
ssh root@<UR-IP> "grep ^root /etc/shadow | cut -d: -f3 | head -c 3"
# $6$ = SHA-512 crypt → custom heslo nastavené
# Ak tento výstup dá ten istý hash, ktorý by vyprodukoval 'easybot' so
# salt-om z /etc/shadow, heslo je stále default. URCap to deteguje sám
# a zaloguje varovanie.
```

---

## Čistenie zo starej inštalácie

Ak máš na robote nainštalovaný starý `URCap_VNC_Server-1.x.urcap`:

```bash
ssh root@<IP>
rm /root/.urcaps/URCap_VNC_Server*.urcap
systemctl restart urcontrol.service
```

Potom pokračuj s v2 inštaláciou.

---

## Licencia

Copyright (c) 2026 STIMBA, s. r. o. Vnútorné použitie STIMBA. Nedistribuovať
bez súhlasu.
