# STIMBA VNC Server URCap — Wiki (Lodný denník)

> **Účel:** Living documentation, ktorá umožní Claudovi / Andrejovi / externému developerovi
> naskočiť do projektu kedykoľvek a vedieť presne, kde sme, čo je hotové, čo nie, a prečo
> sme sa pre konkrétne riešenia rozhodli. Pattern inšpirovaný Karpathy's LLM wiki — každý
> súbor je self-contained kontext-reentry point.

---

## Aktuálny stav projektu (2026-04-19, **v3.1.0 — URCap API 1.16 bump**, Sprint 3 + 3.5 hotfix saga SHIPPED, Task #18 regression gate LIVE)

| Verzia | Status | Dátum | Artefakt | Hlavná zmena |
|--------|--------|-------|----------|--------------|
| 2.0.0 | ⚠️ broken (localhost binding + missing scripts) | 2026-04-16 | `dist/stimba-vnc-server-2.0.0.urcap` | initial |
| 2.0.1 | ✅ shipped | 2026-04-17 | `dist/stimba-vnc-server-2.0.1.urcap` | iptables whitelist + easybot tripwire |
| 2.0.2 | ✅ shipped | 2026-04-17 | `dist/stimba-vnc-server-2.0.2.urcap` | default IXROUTER_IP=192.168.0.100 + config file override |
| 2.1.0 | ✅ shipped | 2026-04-17 | `dist/stimba-vnc-server-2.1.0.urcap` (42 507 B, sha256 `15c0bdf0…3962dc`) | UI bridge (IXROUTER_IP/CUSTOMER_LABEL/strong-pwd) + live health panel (5 probes, 5 s) + atomic config @ `/var/lib/urcap-vnc/config` + password strength indicator |
| 2.2.0 | ✅ shipped | 2026-04-17 | `dist/stimba-vnc-server-2.2.0.urcap` (66 662 B, sha256 `41e29155…0a4619d`) | log tail (3 s refresh) + diag bundle export + temp iptables allowlist with TTL + cron sweeper + JSON-Lines audit log (-accept/-gone hooks) + RFB handshake test button |
| 3.0.0 | ✅ shipped (pulled — Polyscope load crash) | 2026-04-17 | `dist/stimba-vnc-server-3.0.0.urcap` (81 145 B, sha256 `cd9aacbd…9f49ac1`) | Sprint 3 hardening: wire-level TLS via `x11vnc -ssl SAVE` + cert fingerprint pinning (C1), pointer-idle auto-disconnect watcher 0..120 min (C2), max-clients gate 1..5 (C4), per-field in-UI (?) tooltips (C7) |
| 3.0.0-hotfix1 | ✅ shipped | 2026-04-19 | `dist/stimba-vnc-server-3.0.0-hotfix1.urcap` (81 403 B, sha256 `0455e4cf…74ec2f`) | MANIFEST-only: Import-Package doplnený o `javax.swing.border` + `javax.swing.text`. Fix `NoClassDefFoundError: javax/swing/border/Border` pri Polyscope load. Viď memory `reference_urcap_osgi_imports`. |
| 3.0.1 | ✅ shipped (pulled — DataModel drift) | 2026-04-19 | `dist/stimba-vnc-server-3.0.1.urcap` (106 093 B, sha256 `46eee7ca…6cb0843`) | Hotfix #2: pokus o null-safe `isSet`+`get`+cast pattern. Fix `NoSuchMethodError: DataModel.get(Ljava/lang/String;)Ljava/lang/Object`. **Chyba**: reálny API nemá žiadny `Object get(String)`, iba primitive-typed overloads. |
| 3.0.2 | ✅ shipped (pulled — AbstractMethodError) | 2026-04-19 | `dist/stimba-vnc-server-3.0.2.urcap` (106 006 B, sha256 `14b9f4e7…81cac1`) | Hotfix #3: stub API opravený na 3 primitive overloads `int get(String,int)` / `boolean get(String,boolean)` / `String get(String,String)` (decompil z 2.0.0 real-API jaru). Call-sites vo `VncInstallationNodeContribution` prerobené. |
| 3.0.3 | ✅ shipped | 2026-04-19 | `dist/stimba-vnc-server-3.0.3.urcap` (106 134 B, sha256 `1b558b9d…4761b2e`) | Hotfix #4: pridaný `public boolean isDefined() { return true; }` vo Contribution — real-API interface ho vyžaduje, Polyscope volal abstract. Fix `AbstractMethodError: VncInstallationNodeContribution.isDefined()Z`. Installation panel sa konečne otvára bez crashu. |
| 3.0.4 | ✅ shipped | 2026-04-19 | `dist/stimba-vnc-server-3.0.4.urcap` (106 336 B, sha256 `8828fbe6…afba4fdb`) | **MANIFEST-only bump** (description clarifikácia pre DaemonService stub drift — bytecode identický s 3.0.3). Pozn.: Task #18 regression gate validovaný — 3.0.3 a 3.0.4 obidva majú identických 72 public signatures proti `wiki/public-api-baseline.txt`. |
| **3.1.0** | 🔨 ready to build (current target) | 2026-04-19 | `dist/stimba-vnc-server-3.1.0.urcap` (pending first build) | **URCap API bump 1.3.0 → 1.16.0** (Task #19). Fleet telemetry potvrdila floor PS 5.20; 1.16 aligns compile target s PS 5.16 TLS/x509 security overhaul. `Import-Package` teraz `[1.16.0,2.0.0)` → URCap sa odmietne nainštalovať na PS <5.16 (mis-deployment guard). Real `com.ur.urcap.api-1.16.0.jar` (331 KB, UR A/S, built 2024-11-19) pridaný do `build-with-docker/local-urcap-api/`; legacy 1.3.0 artefakty retained pre rollback. Bytecode v `sk.stimba.*` nezmenený → regression gate stále 72 signatures, no baseline regen. |

> **Sprint 3.5 (2026-04-18/19):** Polyscope compatibility hotfix saga. Root cause = hand-rolled URCap API 1.3.0 stub drift (sandbox neblokuje `plugins.ur.com`, stub sme si museli napísať sami a driftol). Naprieč 5 hotfixmi ujasnené: `DataModel` má iba primitive-typed overloads, `InstallationNodeContribution` potrebuje `isDefined()`, `DaemonService` má iba 2 interface metódy. Task #18 (javap diff regression gate, `build-with-docker/regress_signatures.py` + `.github/workflows/regress.yml`) DONE 2026-04-19 ako second-line-of-defense. **Task #15 DONE 2026-04-19** — Andrej stiahol URCap SDK 1.18; z `artifacts/api/1.3.0/` sme extrahovali kanonický `com.ur.urcap.api-1.3.0.jar` (168 KB, UR A/S 2016-2018) a commitli ho pod `build-with-docker/local-urcap-api/`. Budúce buildy kompilujú voči oficiálnym interface-om — zero stub drift možné. **Task #19 DONE 2026-04-19** — fleet telemetry potvrdila floor PS 5.20; preto bumpli API z 1.3.0 → 1.16.0 (331 KB, built 2024-11-19). API 1.16 aligns compile target s PS 5.16 TLS/x509 security overhaul, ktorý je presne tá vrstva na ktorej stojí náš `-ssl SAVE` x11vnc path. `Import-Package` teraz `[1.16.0,2.0.0)` → URCap sa odmietne nainštalovať na PS <5.16 (mis-deployment guard). Bytecode v `sk.stimba.*` bez zmeny → regression gate stále 72 sigs, no baseline regen. Viď [progress.md §2026-04-19 22:00](progress.md) + [04-gotchas.md §G12–G14](04-gotchas.md) + [03-build-deploy.md §Regression gate](03-build-deploy.md#regression-gate--public-api-signature-diff). (G15 — URCapX container runtime diff vs PS5 OSGi — prichádza v Sprint 6 acceptance criteria.)

## Kde začať

1. [01-architecture.md](01-architecture.md) — ako to celé funguje dokopy (Polyscope ↔ OSGi ↔ Java ↔ shell ↔ x11vnc ↔ iptables)
2. [02-feature-matrix.md](02-feature-matrix.md) — všetky feature flagy A1–C7, status, impact, súbory
3. [03-build-deploy.md](03-build-deploy.md) — ako z source → .urcap → robot (Docker build, bez Mavenu lokálne)
4. [04-gotchas.md](04-gotchas.md) — UR-špecifické pasce: polyscope user nie je root, DataModel lifecycle, DaemonContribution idempotency, RFB 8-char truncation
5. [05-file-map.md](05-file-map.md) — čo je v ktorom súbore, kedy bol naposledy editovaný a prečo

## Sprinty

- [sprints/sprint-1-v2.1.0.md](sprints/sprint-1-v2.1.0.md) — UI→daemon bridge + Vrstva A + B5
- [sprints/sprint-2-v2.2.0.md](sprints/sprint-2-v2.2.0.md) — observability + temp allowlist + audit
- [sprints/sprint-3-v3.0.0.md](sprints/sprint-3-v3.0.0.md) — TLS + timeouts + tooltips
- Sprint 3.5 (2026-04-18/19) — bez vlastného plan-dokumentu, žilo iba ako reaktívna hotfix séria; viď [progress.md §Sprint 3.5](progress.md)
- [sprints/sprint-6-v3.1.0.md](sprints/sprint-6-v3.1.0.md) — **URCapX cutover (2026-07-13 → 2026-07-31)**: prvý `.urcapx` artefakt pre Polyscope X 10.7, `cloudflared` + `stimba-agent` ako container artifacts v `manifest.yaml`. PS5 bundle (`3.0.x .urcap`) ostáva zachovaný paralelne — dual-format build.

## Architectural Decision Records

- [adr/001-no-localhost-binding.md](adr/001-no-localhost-binding.md) — prečo 0.0.0.0 + iptables namiesto `-localhost`
- [adr/002-iptables-whitelist.md](adr/002-iptables-whitelist.md) — zdôvodnenie kernel-level RBAC
- [adr/003-config-file-pattern.md](adr/003-config-file-pattern.md) — `/root/.urcap-vnc.conf` ako override bridge
- [adr/004-ui-daemon-bridge.md](adr/004-ui-daemon-bridge.md) — ako zapísať config z polyscope usera (group=polyscope, /var/lib/)
- [adr/005-easybot-tripwire.md](adr/005-easybot-tripwire.md) — zdôvodnenie `URCAP_VNC_REQUIRE_STRONG_PWD`
- [adr/006-health-panel-polling.md](adr/006-health-panel-polling.md) — prečo client-side probe a nie server-push
- [adr/007-audit-log-format.md](adr/007-audit-log-format.md) — x11vnc `-accept` / `-gone` hooks + JSON-lines audit log
- [adr/008-tls-via-x11vnc-ssl.md](adr/008-tls-via-x11vnc-ssl.md) — prečo `x11vnc -ssl SAVE` namiesto stunnel alebo OpenVPN wrapper

## Progress log

- [progress.md](progress.md) — bežný running log, kto/kedy/čo zmenil a prečo

## Tooling (regression gate, od 2026-04-19)

- `build-with-docker/regress_signatures.py` — self-contained Python JVM class-file parser. Generuje/diffuje public-API signatures. Žiadny JDK required (sandbox má len JRE).
- `build-with-docker/regress.sh` — wrapper, auto-detectuje najnovší `target/*.urcap`, unpackne ho, spustí parser v `--diff` móde proti baseline. Exit 0/1/2 = ok / env-error / signature drift.
- `wiki/public-api-baseline.txt` — frozen snapshot 72 signatures z 3.0.3 (5 top-level classes: `Activator`, `VncDaemonService`, `VncInstallationNodeContribution`, `VncInstallationNodeService`, `VncInstallationNodeView`). Manuálne promote-nuté cez `make regress-write`.
- `Makefile` — `make` default = `build && regress`; `make regress-write` pre zámerné API change-ami (review diff → commit baseline v rovnakom PR).
- `.github/workflows/regress.yml` — CI gate na push/PR dotýkajúce sa `src/**`, `pom.xml`, baseline, tooling. Fail → job summary s návodom ako regen baseline ak bola zmena zámerná.
- `build-with-docker/local-urcap-api/api-1.16.0.jar` — **active URCap API JAR** (Universal Robots A/S, 331 KB, z URCap SDK 1.18, built 2024-11-19). Spolu s `.pom` a `-sources.jar` priamo v repo — Dockerfile `install:install-file` ho inštaluje do build-container `~/.m2`. Commitnuté v rámci Task #19, `2026-04-19`. **Toto je first-line-of-defense** proti stub drift — druhá úroveň je regression gate, ale bez reálneho JAR-u by sme stále museli dôverovať hand-rolled stubom.
- `build-with-docker/local-urcap-api/api-1.3.0.jar` — legacy JAR (168 KB, Task #15) retained pre git-bisect + rollback path. NIE JE inštalovaný do `~/.m2` — Dockerfile na neho neukazuje. Odstrániteľné `git rm` v budúcom "cleanup" PR.

## Tento súbor sa aktualizuje

Každý task v TodoList-e ktorý mení súbor v repo → pridá riadok do `progress.md` a aktualizuje
status v tabuľke vyššie. Pravidlo: *commit nepíšem, kým wiki nie je aktuálna.*
