# 03 — Build & Deploy

## Build

### Predpoklady

1. **URCap SDK** — URCap API JAR `api-1.3.0.jar`. Tento jar NIE JE na Maven Central.
   Získavaš ho z UR Developer portálu (SDK zip).
   - Stiahni SDK: <https://www.universal-robots.com/developer-program/>
   - Rozbaľ: `sdk/com/ur/urcap/api/1.3.0/api-1.3.0.jar`
   - Polož ho do `vnc-urcap/local-repo/com/ur/urcap/api/1.3.0/api-1.3.0.jar` a pridaj pom:
     ```bash
     mkdir -p local-repo/com/ur/urcap/api/1.3.0
     cp /path/to/api-1.3.0.jar local-repo/com/ur/urcap/api/1.3.0/
     cat > local-repo/com/ur/urcap/api/1.3.0/api-1.3.0.pom <<EOF
     <?xml version="1.0"?>
     <project xmlns="http://maven.apache.org/POM/4.0.0">
       <modelVersion>4.0.0</modelVersion>
       <groupId>com.ur.urcap</groupId>
       <artifactId>api</artifactId>
       <version>1.3.0</version>
       <packaging>jar</packaging>
     </project>
     EOF
     ```

2. **Java 8** (`openjdk@8`) + **Maven 3.6+**

### Option A — Lokálny Maven (odporúčané pre dev)

```bash
cd vnc-urcap
mvn -B clean package
# výstup: target/vnc-server-<version>.urcap
```

### Option B — Docker (odporúčané pre CI / cross-platform)

```bash
cd vnc-urcap
docker build -t stimba/urcap-builder -f build-with-docker/Dockerfile build-with-docker
docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 stimba/urcap-builder
# výstup: target/vnc-server-<version>.urcap
```

### Option C — Sandbox bez Mavenu (emergency repack)

Ak máš rozbalený existujúci `.urcap` a chceš len zmeniť shell script, použi pattern z 2.0.2:

```bash
cd /tmp
rm -rf urcap-repack && mkdir urcap-repack && cd urcap-repack
unzip -q "/path/to/stimba-vnc-server-2.0.2.urcap"
# edit súborov
# update META-INF/MANIFEST.MF Bundle-Version
# update META-INF/maven/*/pom.properties version
# update META-INF/maven/*/pom.xml version
zip -q -X /tmp/out.jar META-INF/MANIFEST.MF
zip -q -r -X /tmp/out.jar . -x META-INF/MANIFEST.MF
cp /tmp/out.jar /path/to/dist/stimba-vnc-server-X.Y.Z.urcap
```

**Pozor:** toto funguje LEN keď sa nemenia .class súbory. Pri Java zmene musíš kompilovať.

### Option D — Sandbox ECJ pipeline (použitý pri v3.0.0)

Ak nemáš Maven ani prístup na `plugins.ur.com` (firewall / airgap dev sandbox), môžeš použiť Eclipse ECJ compiler s lokálnym URCap API stub jarom. Toto je presný postup, ktorý vygeneroval `dist/stimba-vnc-server-3.0.0.urcap`:

**Predpoklady:**
- ECJ: `/tmp/compiler/ecj.jar` (Eclipse JDT 3.33 alebo kompatibilný)
- Stub JARy:
  - `/tmp/urcap-lib/api-1.3.0.jar` (URCap API 1.3.0 — skopírovaný z SDK alebo vyextraktovaný z existujúceho .urcap)
  - `/tmp/urcap-lib/osgi.core-6.0.0.jar` (OSGi core API)

**Kompilácia:**
```bash
cd vnc-urcap
mkdir -p target/classes-vNEW
java -jar /tmp/compiler/ecj.jar \
  -1.8 \
  -cp /tmp/urcap-lib/api-1.3.0.jar:/tmp/urcap-lib/osgi.core-6.0.0.jar \
  -d target/classes-vNEW \
  src/main/java
```

**Bundle assembly (MANIFEST-first zip, OSGi requirement):**
```bash
# 1) Pripraviť pracovný adresár so skriptmi + META-INF
STAGE=/tmp/urcap-stage
rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -r target/classes-vNEW/* "$STAGE/"
cp -r src/main/resources/* "$STAGE/"   # daemon/*.sh + post-install.sh
# Manifest s Bundle-Version X.Y.Z (viď template nižšie)
mkdir -p "$STAGE/META-INF"
cat > "$STAGE/META-INF/MANIFEST.MF" <<'MF'
Manifest-Version: 1.0
... (viď target/classes-v30/META-INF/MANIFEST.MF v3.0.0)
MF

# 2) Zip s MANIFEST.MF ako PRVÝ entry, STORED (no compression)
cd "$STAGE"
zip -X -0 /tmp/out.urcap META-INF/MANIFEST.MF
zip -X -r /tmp/out.urcap . -x META-INF/MANIFEST.MF

# 3) Pipe do dist/ (bypass rename-replace permission quirk v sandboxe)
cat /tmp/out.urcap > "/path/to/dist/stimba-vnc-server-X.Y.Z.urcap"

# 4) SHA-256
cd /path/to/dist
sha256sum stimba-vnc-server-X.Y.Z.urcap > SHA256SUMS-X.Y.Z
```

**Verifikácia:**
```bash
unzip -t dist/stimba-vnc-server-X.Y.Z.urcap           # no errors
unzip -l dist/stimba-vnc-server-X.Y.Z.urcap | head -5  # MANIFEST.MF FIRST
unzip -p dist/stimba-vnc-server-X.Y.Z.urcap META-INF/MANIFEST.MF | head -20
```

Prvý zip entry MUSÍ byť `META-INF/MANIFEST.MF` — inak Polyscope URCap loader bundle odmietne.

## Deploy

### Path A — USB (customer site, no SSH)

1. Skopíruj `dist/stimba-vnc-server-X.Y.Z.urcap` na USB (FAT32/exFAT).
2. Na Polyscope teach pendante: **Hamburger → Settings → System → URCaps → + (Install)** → vyber z USB.
3. Polyscope vyžiada **Restart** — potvrď.
4. **Installation → URCaps → STIMBA VNC Server** — nastav polia (IXROUTER_IP, heslo, customer label), Apply.

### Path B — SSH (laboratory / STIMBA internal)

```bash
# 0) Overiť, že easybot heslo je zmenené!
scp dist/stimba-vnc-server-X.Y.Z.urcap root@<robot-ip>:/root/.urcaps/
ssh root@<robot-ip> "systemctl restart urcontrol.service"
```

### Path C — `mvn -Premote`

```bash
mvn -B -Premote install -Dur.host=192.168.1.101 -Dur.pass=<new-password>
```

## Po-inštalačná verifikácia

```bash
ssh root@<robot-ip> 'bash -s' <<'EOF'
echo "=== URCap installed? ==="
ls -la /root/.urcaps/
echo ""
echo "=== Daemon running? ==="
cat /tmp/urcap-vnc.lock && ps -p "$(cat /tmp/urcap-vnc.lock)" -o pid,cmd
echo ""
echo "=== iptables whitelist ==="
iptables -L INPUT -n --line-numbers | grep 5900
echo ""
echo "=== Port listening? ==="
ss -tlnp | grep 5900
echo ""
echo "=== Log tail ==="
tail -30 /var/log/urcap-vnc.log
EOF
```

Úspech = vidíš `LISTEN 0.0.0.0:5900`, iptables má 2 ACCEPT (100 + loopback) a 1 DROP, log nemá ERROR.

## Post-inštalačná verifikácia (v3.0.0 rozšírenie)

Od v3.0.0 pridaj tieto additional checks:

```bash
ssh root@<robot-ip> 'bash -s' <<'EOF'
echo "=== TLS cert (C1) ==="
ls -la /root/.vnc/certs/ 2>/dev/null || echo "NO CERT DIR (TLS_ENABLED=0?)"
openssl x509 -in /root/.vnc/certs/server.pem -noout -subject -dates -fingerprint -sha256 2>/dev/null

echo ""
echo "=== Fingerprint file ==="
cat /root/.vnc/certs/fingerprint.txt 2>/dev/null

echo ""
echo "=== Idle watcher (C2) ==="
pgrep -af idle-watcher.sh || echo "no idle watcher (IDLE_TIMEOUT_MIN=0?)"

echo ""
echo "=== x11vnc args (C4 max_clients + C1 -ssl) ==="
ps -ef | grep x11vnc | grep -v grep

echo ""
echo "=== TLS handshake test (RFB over TLS) ==="
# Pozor: musíš byť na allowlisted IP. Inak skoč cez loopback:
echo Q | timeout 3 openssl s_client -connect 127.0.0.1:5900 -verify_return_error -servername stimba-urcap-vnc 2>&1 | grep -E "(subject|CN|verify error)"
EOF
```

Úspech = `server.pem` existuje s CN=`stimba-urcap-vnc-$(hostname)`, fingerprint súbor má SHA-256, `x11vnc` má `-ssl SAVE` flag, TLS handshake vráti self-signed cert subject.


---

## Regression gate — public-API signature diff

Pridané **2026-04-19** po 3.0.0→3.0.4 hotfix ságe (5 releasov za 24 hodín pre to isté API stub drift). Cieľ: zachytiť `NoSuchMethodError` / `AbstractMethodError` pred tým než sa URCap dostane na real robota. Detaily v [`04-gotchas.md §G12–G14`](./04-gotchas.md).

### Ako funguje

`build-with-docker/regress_signatures.py` je self-contained Python parser JVM class-file formátu (žiadny JDK). Prejde všetky `sk/stimba/**/*.class` v .urcap, extrahuje signatúry **public/protected non-synthetic non-bridge** metód a diffne ich proti baseline `wiki/public-api-baseline.txt`.

Ak metóda zmizne → **exit 2 + hlásenie „DANGER — AbstractMethodError risk"**. Ak pribudne → **exit 2 + „commit baseline if intentional"**.

### Kedy CI failne

Každá z týchto zmien spôsobí fail:

- **Metóda bola zmazaná** — napr. zabudneš `@Override isDefined()` v `VncInstallationNodeContribution` a Polyscope padne s `AbstractMethodError`. Toto bolo 3.0.3.
- **Metóda zmenila typ parametra** — napr. `DataModel.get(String, int)` vs `DataModel.get(String, Object)`. JVM match descriptory rieši striktne podľa descriptor stringu. Toto bolo 3.0.1→3.0.2.
- **Metóda sa premenovala** — refaktor bez adaptácie baseline.

Baseline sleduje iba `sk.stimba.*` — nie URCap API stubs ani externé závislosti.

### Workflow

**Bežný build:**
```bash
make build && make regress
# alebo jednoducho:
make
```

**Po zámernej API zmene** (pridal si novú metódu v `VncInstallationNodeContribution`):
```bash
make build
make regress        # fail — new surface detected
make regress-write  # promote to baseline
git diff wiki/public-api-baseline.txt   # sanity check
git add wiki/public-api-baseline.txt
git commit -m "api: add setFoo(int) to VncInstallationNodeContribution"
```

**Ručne proti konkrétnemu .urcap:**
```bash
./build-with-docker/regress.sh path/to/stimba-vnc-server-3.0.3.urcap
# exit 0 = OK, exit 2 = drift, exit 1 = env/usage
```

### CI integrácia

`.github/workflows/regress.yml` beží na každý push/PR ktorý sa dotkne `src/**`, `pom.xml`, baseline, alebo nástrojov. Pipeline:

1. Docker build .urcap (rovnaký obraz ako local dev)
2. `regress.sh` proti committed baseline
3. Upload .urcap ako GH Actions artifact iba ak passne

Pri fail-e `job summary` obsahuje návod "ak bola zmena zámerná, regeneruj baseline".

### Známe limity

- Regression detekuje **signature changes**, nie **semantic changes**. Ak zmeníš telo `isDefined()` aby vracalo `false`, test prejde, ale Polyscope sa začne správať inak. Pre sémantické testy je nutný PS5/PSX URsim smoke test (Sprint 7).
- Nepokrýva inner classes (`VncInstallationNodeView$12.class` atď.) — tie sa menia každým refaktoringom Swing listenerov a bolo by to flaky.
- Anonymné lambda syntéza (`lambda$0`) je skipnutá cez `ACC_SYNTHETIC` filter — nepokryjeme ich, ale ani nie sú reálna API surface.

### Baseline history

| Baseline revízia | Generovaná z | Počet signatúr | Commit |
|---|---|---|---|
| v1 | stimba-vnc-server-3.0.3 | 72 | 2026-04-19 (initial) |

Nezvyšuj číslo verzie URCapu bez re-runu `make regress-write` ak si niečo v public API zmenil.
