# URCapX architecture — PolyScope X vs PolyScope 5

**Autor:** Andrej Bielik + Claude Opus 4.7
**Dátum:** 2026-04-21
**Status:** scaffold — v3.10+ implementation

---

## 1. TL;DR

Universal Robots vydali PolyScope X ako next-gen teach-pendant OS. Nemá OSGi,
nemá Swing, nemá Java runtime. URCapX = Angular TypeScript frontend +
volitelný Docker container backend. Ship artefakt `.urcapx` = zip bundle.

Pre STIMBA to znamená: celý URCap v3.x code base (Java 8 OSGi v `src/main/java`)
sa prepíše do TypeScript / Python. Dobrá správa — API contract s portálom
(HTTPS + JSON) zostáva identický, takže portal side nemení.

## 2. Feature parity matrix

| STIMBA feature | PS5 (Java) | PSX (Angular + container) | Effort |
| --- | --- | --- | --- |
| VNC daemon (x11vnc) | `VncDaemonService` → bash scripts | Docker sidecar s x11vnc + host DISPLAY mount | S |
| Portal HTTPS client | `PortalClient.java` (HttpsURLConnection + CertPinner) | Python `httpx` + `certifi` pinned | S |
| Cert pinning | Java TrustManager + SHA-256 set | Python ssl.SSLContext + custom CaVerifyCallback | S |
| Token AES-256-GCM | Java javax.crypto | Python `cryptography` | S |
| Heartbeat 30 s | `PortalHeartbeatRunner` ScheduledExecutor | Python `asyncio.sleep(30)` loop | S |
| Command poll + dispatch | `DashboardCommandPoller` | Python poll → URScript builder | M |
| URScript send (Primary :30001) | `PrimaryInterfaceClient.java` | Python `asyncio.open_connection` | S |
| RTDE reader (:30004) | `RtdeReader.java` | [ur-rtde](https://pypi.org/project/ur-rtde/) Python binding | S (lib reuse) |
| Installation node UI | Java Swing + KeyboardInputFactory | Angular component + UR Components | M |
| On-screen keyboard | `setKeyboardInputFactory()` | PolyScope X handles natively | — (free) |
| PS5 config write `/var/lib/urcap-vnc/config` | bash via ProcessBuilder | Container volume write | S |
| systemd daemon unit | URCap `.daemon` file | Docker `CMD` + restart policy | S |

Celkovo: **low-medium effort** port, za predpokladu že `ur-rtde` Python lib
pokryje RTDE v2. Ak ju UR nemajú ako dependency-friendly balík (niekedy je
zlé na PyPI kvôli C++ compile), fallback je reimplementation z `RtdeReader.java`
priamo v Python — ~200 riadkov.

## 3. Side-by-side: "povedz robotovi napri ku home"

### PS5 (dnes)

1. AI v portáli navrhne `movej([0, -1.57, …])` tool call
2. Operátor klikne Approve
3. Portal zapíše row do `ai_commands`
4. Java `DashboardCommandPoller.tick()` poll-ne `/api/agent/commands`
5. Dispatcher `case "urscript_send"` → `primaryClient.sendScript(script)`
6. `PrimaryInterfaceClient.sendScript` otvorí TCP :30001, write + close
7. UR kontroller vykoná movej
8. `client.ackCommand(...)` PATCH-ne `ai_commands` na completed

### PSX (v3.10+)

Identické od kroku 1 do 4. Rozdiel:

5. Python sidecar dispatcher `dispatch_urscript_send(script)` → `PrimaryClient.send(script)`
6. Python `asyncio.open_connection("127.0.0.1", 30001)` + `writer.write(script + "\n")`

Portal strane nič nemení. URCap integrator deploy-ne `.urcapx` namiesto
`.urcap` — to je celý rozdiel pre zákazníka.

## 4. Build pipeline porovnanie

### PS5 (dnes, parent project)
```bash
docker run --rm -v $PWD:/src -v ~/.m2:/root/.m2 stimba/urcap-builder
# → target/vnc-server-3.9.0.urcap  (~150 KB)
```

### PSX (scaffolded)
```bash
cd psx/
npm install
npm run build
# → target/stimba-vnc-server-3.9.0.urcapx  (MB-scale — bundled Angular + container image if any)
```

PSX artefakty sú outward inkompatibilné — oba musia vychádzať z rovnakej
version tag-u, ale users si vyberú podľa kontrolera.

## 5. Dual-build GitHub Actions (v3.10 plán)

```yaml
name: Build URCap + URCapX
on: [push, pull_request]
jobs:
  build-ps5:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 8 }
      - run: mvn -B package
      - uses: actions/upload-artifact@v4
        with: { name: urcap-ps5, path: target/*.urcap }
  build-psx:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: npm --prefix psx install
      - run: npm --prefix psx run build
      - uses: actions/upload-artifact@v4
        with: { name: urcap-psx, path: psx/target/*.urcapx }
```

Na release tag-u (`v3.x.0`) oba joby push-nú svoj asset do GitHub Release —
users si stiahnu ten, čo pasuje ich kontroléru.

## 6. Portal UI hints (v3.9 impl)

V `/devices/new` a `/devices/:id/edit` už máme `polyscope_major` picker:
`ps5` | `psx` | `cb3`. Keď user zvolí `psx`:

- VNC mode automaticky ide na `none` alebo `web_ingress_80` (PSX teach pendant view
  je HTTPS-based, nie VNC)
- Install link ukazuje na `.urcapx` asset z Release page
- Command surface je rovnaký, ale UI warn: "PSX URCap je v preview od v3.10;
  do tej doby prístup iba read-only (heartbeat, state_now)"

## 7. Otvorené otázky pre v3.10 implementation

1. **Permission model** — URCapX manifest má `capabilities.permissions` — čo
   presne musíme deklarovať? Napr. `network.egress: portal.stimba.sk:443` asi ok,
   ale čo `primaryinterface.localhost:30001` + `rtde.localhost:30004`? Treba
   pozrieť docs + ideálne kontaktovať UR developer program.
2. **Container image size** — ak Python sidecar s ur-rtde má byť < 50 MB,
   musíme použiť `python:3.11-slim` + aggressive strip. `.urcapx` bundle >
   100 MB by operátorov otravoval.
3. **Live dev vs prod** — SDK 0.19 má "hot reload" guide. Dev loop pôjde
   cez UR URSim PSX — nebude potrebné fyzické HW.
4. **VNC na PSX** — je to vôbec žiaduce? PSX má natívny HTTPS remote viewer.
   Ak stále chceme lokálny VNC (napr. kvôli IXON Cloud tunelu), x11vnc v
   kontajneri musí mať prístup k host DISPLAY. UR to v SDK 0.18 neskoršie
   buď povolili alebo zakázali — treba overiť.
5. **URScript send** — PSX má možno nový API pre URScript (moonshot:
   REST endpoint namiesto TCP :30001). Treba overiť v dokumentácii.

## 8. Release tajming

- **v3.9** — scaffold only (tento dokument + `psx/` priečinok). Portal pridáva
  `polyscope_major=psx` hint.
- **v3.10** — funkčný build, Dockerfile hotový. Beží na URSim PSX. Žiadny
  fyzický deploy (ešte nemáme zákazníka s PSX hardwarom).
- **v3.11** — dual-release (obe .urcap + .urcapx z jedného tag-u). Pilot zákazník
  s PSX kontrolérom — real deployment.

---

**Teraz sa na to pozerám zo zeme**: v3.9 scaffold existuje ako placeholder.
Nevieme, či bude v3.10 potrebovať týždeň alebo mesiac — závisí od toho ako
rýchlo sa rozbehneme s PSX SDK. Ak užtuvíme zákazníka s PSX hardvérom, dám
prioritu v3.10 pred inými features.
