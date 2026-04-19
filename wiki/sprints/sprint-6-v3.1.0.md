# Sprint 6 — v3.1.0 (URCapX container-artifact cutover)

**Plánovaný window:** 2026-07-13 → 2026-07-31 (3 týždne)
**Cieľ verzie:** `stimba-vnc-server-3.1.0.urcapx` (prvý URCapX artefakt, PSX-compatible)
**Portal-side spec:** [`portal-stimba-sk/wiki/sprints/sprint-6-urcapx.md`](../../../portal-stimba-sk/wiki/sprints/sprint-6-urcapx.md) (pre API + device detail UI)

---

## Prečo vôbec Sprint 6

Current prod (`3.0.3` / `3.0.4`) je PS5-only OSGi bundle (`.urcap`). Polyscope X má úplne inú runtime architektúru — TypeScript/React kiosk, žiadna JVM, contribution-nodes nefungujú. URCapX je UR odpoveď: **podpísaný ZIP s `manifest.yaml` + container artifacts**, ktoré Polyscope X rozbalí a spustí ako Docker services cez host robot runtime.

**Architektonický pivot 2026-04-18:** nestaviame vlastný IXON route daemon. Miesto toho:
- `cloudflared` container ako artifact v URCapX → tunel do `*.stimba.sk`
- `stimba/ur-control-agent` container ako artifact → heartbeat, remote ops, VNC proxy
- Portal má endpoint `/api/devices/pair` ktorý tvorí Cloudflare tunnel cez API + vracia agent config

Bývalý plán (bare binary + Java `Runtime.exec()`) by porušil PSX sandbox a ťahal by Native/OSGi závislosti z .urcapx do kontajnerov.

---

## Week 1 — 2026-07-13 → 2026-07-19 (scaffolding)

### URCap-side
- [ ] **Rebuild pipeline** — pridať `mvn package` profile `urcapx` ktorý produkuje `.urcapx` zip okrem `.urcap`.
  - Manifest `manifest.yaml` (YAML, NIE `MANIFEST.MF`) root-level
  - `artifacts/containers/` sub-dir so `image-refs.yaml` pre každý container (lazy-pull z ghcr.io)
  - `ui/` stays — PSX kiosk React snippet (prázdny stub na začiatok)
- [ ] **`manifest.yaml` prvá verzia:**
  ```yaml
  apiVersion: urcapx/v1
  kind: URCapX
  metadata:
    id: sk.stimba.urcap.vnc
    version: 3.1.0
    name: STIMBA Remote Kiosk
    vendor: STIMBA, s. r. o.
  spec:
    polyscope:
      min: "10.7"   # PSX baseline
    artifacts:
      containers:
        - name: cloudflared
          image: cloudflare/cloudflared:2026.4.0
          runtime: robot-host
          ports: []   # žiadne exposed — tunel robí outbound
          env:
            - TUNNEL_TOKEN: ${URCAP_VARS.tunnel_token}
        - name: stimba-agent
          image: ghcr.io/bielikandrej/ur-control-agent:3.1.0
          runtime: robot-host
          env:
            - STIMBA_DEVICE_ID: ${URCAP_VARS.device_id}
            - STIMBA_PORTAL_URL: https://portal.stimba.sk
            - STIMBA_AUTH_KEY: ${URCAP_VARS.auth_key}
  ```
- [ ] **PS5 fallback zachovaný** — `3.0.x` OSGi bundle continues to ship parallel. `.urcap` generate podľa doterajšieho flow, `.urcapx` novým profilom. Repo shipuje oboje v `dist/`.

### Portal-side dependency
- [ ] `/api/devices/pair` endpoint existuje a vracia `{ tunnel_token, auth_key, device_id }` → viď portal sprint-6 doc §Week 1.

---

## Week 2 — 2026-07-20 → 2026-07-26 (URsim PSX)

- [ ] **URsim 10.7 PSX** — stiahnuť z `https://www.universal-robots.com/download/software-polyscope-x-10-7` a rozbehať v Docker lokálne. Pending: UR vraj PSX URsim launch Q3 2026, ak nie je, sunnúť časový plán.
- [ ] Deploy 3.1.0 `.urcapx` do PSX URsim kiosku:
  - File → Upload URCapX → pick `stimba-vnc-server-3.1.0.urcapx`
  - Napriek tomu že toto je `unsigned`, PSX devmode má toggle "allow unsigned URCapX" (inštalačný preflight check)
  - Sledovať PSX logs cez `docker logs ursim-10.7` pre `artifact pull` errory
- [ ] **Smoke test criteria:**
  1. Oba containers pullnú z ghcr.io
  2. `cloudflared` establishne tunnel na `<slug>.stimba.sk`
  3. `stimba-agent` sendne heartbeat do `portal.stimba.sk/api/devices/heartbeat`
  4. PSX kiosk UI stránka zobrazí "Pairing OK — Device ID xxx"
- [ ] Ak zlyhá artefact pull zo ghcr.io (image-public ale robot runtime offline) — plán B: `image-refs.yaml` s tarball (`image: oci-archive://sk-stimba-agent-3.1.0.tar`) ktorý je súčasť URCapX ZIPu (nárast artefaktu ~80 MB, ale žiadna runtime network dependency).

---

## Week 3 — 2026-07-27 → 2026-07-31 (real hardware + ship)

- [ ] **Real UR10e PSX** — pairing z portálu. End-to-end:
  1. Andrej v portáli klikne "Pripojiť nový robot" → dostane QR + pairing code
  2. Z robota otvorí Settings → Install URCapX → QR → pull 3.1.0 zo `https://portal.stimba.sk/downloads/3.1.0.urcapx`
  3. Pri install prompt vloží 6-digit pairing code → agent si stiahne `auth_key` + `tunnel_token` z `/api/devices/pair`
  4. Do ~30s sa objaví v portál fleet page ako online
- [ ] **VNC-over-tunnel test** — operátorove remote session. Portál má v device detail "Remote VNC" tlačidlo → otvorí `wss://<slug>.stimba.sk/vnc` → cez noVNC sa pripojí na `stimba-agent` ktorý proxyuje x11vnc lokálne na robote.
- [ ] **Ship `dist/stimba-vnc-server-3.1.0.urcapx` + `dist/SHA256SUMS-3.1.0`.** Update `README.md` s "PS5 → použiť 3.0.3 .urcap, PSX → použiť 3.1.0 .urcapx".
- [ ] **Regression gate** (Task #18) — baseline sa pravdepodobne rozšíri o nové metódy (pairing API call-sites vo `VncInstallationNodeContribution` ak skončíme rozširovať PS5 bundle aj o tunnel variant). Run `make regress-write` po merge-i, commit new baseline v rovnakom PR.

---

## Risks

| # | Risk | Mitigation |
|---|------|------------|
| R1 | PSX URsim 10.7 nie je public v Q3 2026 | Pauza na real UR10e PSX; dev na PS5 zachovaný |
| R2 | `robot-host` runtime spec v URCapX ešte nie stabilný | Sledovať UR Developer fórum, FZI ExternalControl ako canary |
| R3 | Cloudflare Tunnel rate limit na Free tier | Upgrade na Tunnel Enterprise ak >100 robotov; per-robot subdomain accounting |
| R4 | ghcr.io image pull failne cez robot firewall | Fallback plán B (tarball v URCapX) — budget +80 MB/artefakt |
| R5 | Agent container ponechá PID namespace visible → security review | `unprivileged: true` v manifest.yaml, no caps, read-only rootfs |
| R6 | PSX signing requirement pre distribuciu cez UR+ Store | Mimo scope v6; self-hosted download cez portal.stimba.sk |
| R7 | 3.0.x PS5 bundle + 3.1.0 PSX bundle divergujú | Shared Java code v `src/main/java/sk/stimba/urcap/common/`, PS5-specific v `...impl/ps5/`, PSX-specific v `...impl/psx/` |

---

## Acceptance criteria (ship gate)

1. `dist/stimba-vnc-server-3.1.0.urcapx` existuje, zip passuje PSX preflight (`unzip -l` + manifest schema validácia)
2. Jedno real UR10e s PSX 10.7 má zelený heartbeat v portáli ≥24 hodín
3. Remote VNC session cez tunnel + portál funguje z Andrejovho Macu
4. `make regress` zelené proti nového baseline
5. Portal `/api/devices/pair` + fleet page + device detail UI žijú (portal sprint-6 done)
6. 3.0.x PS5 bundle zachovaný — no regresie v PS5 prod test roboth
7. `README.md` + `03-build-deploy.md` explicitne dokumentujú dual-format build
8. `04-gotchas.md` má novú sekciu „G15 — URCapX container runtime difference vs PS5 OSGi"

---

## Otvorené otázky (zatiaľ bez odpovede)

- **Q1:** Cloudflare Tunnel cez `cloudflared` container môže používať `TUNNEL_TOKEN` z Named Tunnel API — ale vytvoriť per-device tunnel treba z portal backend-u pred `devices/pair` odpovedou. Retry/cleanup flow pri partial failure?
- **Q2:** Bude `ghcr.io/bielikandrej/ur-control-agent` public image alebo private s robot-side pull token? Ak private → token rotation flow z portálu.
- **Q3:** `image-refs.yaml` pull-on-demand vs all-bundled tarball — kedy ktoré (closed-network customer vs open-internet edge box)?
- **Q4:** PSX kiosk React snippet v `ui/` má použiť SDK komponenty z `@urc-sdk/react` alebo custom? Pending dev docs z UR.

---

## Dependency chain z predošlých sprintov

- **Sprint 1** (2.1.0) — UI→daemon bridge. **Blocking:** NIE — PSX runtime je úplne iný.
- **Sprint 2** (2.2.0) — audit log + temp allowlist. **Carry-over:** agent container musí rebuildnúť tieto scripts.
- **Sprint 3** (3.0.0) — TLS bootstrap. **Carry-over:** agent používa vlastné TLS (cez Cloudflare tunnel) — pôvodný self-signed pattern deprecated pre PSX.
- **Sprint 3.5** (3.0.3 / 3.0.4 hotfixes) — API stub drift lesson. **Carry-over:** Task #15 (real api jar) musí byť DONE pred 3.1.0 kickoff. Task #18 (regression gate) DONE — protect proti back-slide na PS5 bundli.

---

## Po 3.1.0 — plán pre Sprint 7

[`sprint-7-urcap-update.md`](../../../portal-stimba-sk/wiki/sprints/sprint-7-urcap-update.md) — OTA update flow z portálu (admin klikne "update 3.0.3 → 3.0.4" na vybranom robote, agent stiahne nový .urcap cez tunnel, PS5 re-install cez `ur-daemon-tool`). Sprint 7 je PS5-first; PSX URCapX update pôjde iným flowom (Polyscope X má built-in URCapX manager).
