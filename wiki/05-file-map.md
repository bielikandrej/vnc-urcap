# 05 — File Map

Katalóg všetkých file-ov v `vnc-urcap/` s rolou, aktuálnym stavom, a "prečo bol naposledy menený".

## Projekt root

| File | Rola | Naposledy menený | Dôvod |
|---|---|---|---|
| `pom.xml` | Maven bundle config, version source of truth | 2026-04-19 | Bump na 3.0.4 (posledný hotfix 3.5) |
| `README.md` | Operátorský návod (install + override config) | 2026-04-17 | Per-robot IXROUTER_IP override flow dokumentovaný |
| `Makefile` | Developer shortcuts (build / regress / regress-write / clean) | 2026-04-19 | NEW (Task #18) — default `make` = build + regression gate |
| `.gitignore` | Vynechať `target/`, IDE noise, tmp/regress artefakty + explicit `!local-urcap-api/*` allow | 2026-04-19 | NEW (Task #15) — vytvorený spolu s embedom |
| `build-with-docker/Dockerfile` | maven:3.9.6-eclipse-temurin-8 + `install:install-file` zo `local-urcap-api/` | 2026-04-16 | initial |
| `build-with-docker/regress.sh` | Bash wrapper ktorý unpackne `.urcap` a spustí signature diff | 2026-04-19 | NEW (Task #18) |
| `build-with-docker/regress_signatures.py` | Self-contained Python JVM class-file parser (no JDK) | 2026-04-19 | NEW (Task #18) |
| `build-with-docker/local-urcap-api/api-1.3.0.jar` | **Real URCap API 1.3.0 JAR** z URCap SDK 1.18, 168 KB, Universal Robots A/S | 2026-04-19 | NEW (Task #15) — uzatvára Sprint 3.5 stub-drift lineage |
| `build-with-docker/local-urcap-api/api-1.3.0.pom` | Minimálny POM pre `mvn install:install-file -DpomFile=…` | 2026-04-19 | NEW (Task #15) |
| `build-with-docker/local-urcap-api/api-1.3.0-sources.jar` | API sources (developer reference; nie súčasť Dockerfile flow) | 2026-04-19 | NEW (Task #15) |
| `build-with-docker/local-urcap-api/README.md` | Status + licenčná nota; sekcia "kde JAR originálne vzniká" ako fallback | 2026-04-19 | UPDATED (Task #15) — status embedded |
| `.github/workflows/regress.yml` | CI gate — fail build pri public-API signature drift | 2026-04-19 | NEW (Task #18) — blokuje Sprint 3.5 typu hotfix saga |
| `local-repo/com/ur/urcap/api/1.3.0/` | **Deprecated path.** pom.xml má `<repository id="local-urcap">` ktorý sem míri, ale reálny canonical install flow ide cez Dockerfile + local-urcap-api/. Gitignored. | — | legacy fallback |

## Java source (`src/main/java/sk/stimba/urcap/vnc/impl/`)

| File | Rola | Plán (2.1.0) | Plán (2.2.0) | Plán (3.0.0) |
|---|---|---|---|---|
| `Activator.java` | OSGi BundleActivator, registruje services | beze zmeny | beze zmeny | beze zmeny |
| `VncDaemonService.java` | Poskytuje `DaemonContribution` factory + installResource | beze zmeny | beze zmeny | beze zmeny |
| `VncInstallationNodeService.java` | Factory pre InstallationNode | beze zmeny | beze zmeny | beze zmeny |
| `VncInstallationNodeContribution.java` | DataModel + daemon control + config file write | +A1/A2/A3/A5 keys, `writeConfigFile()`, Apply button handler | +B3 handler (temp allowlist), +B6 test conn | +C4 (conn limit), +C7 tooltip wiring |
| `VncInstallationNodeView.java` | Swing UI (tab v Installation) | prerob UI: +IXROUTER_IP field, +customer label, +health panel (5 dots), +easybot banner, +password strength indicator | +log viewer panel (LogTailer), +export diag button, +temp allowlist dialog, +test conn button | +conn limit spinner, +tooltips |
| `LogTailer.java` | tail -f wrapper pre log viewer | — | NEW (B1) | beze zmeny |

## Shell resources (`src/main/resources/sk/stimba/urcap/vnc/impl/daemon/`)

| File | Rola | Plán (2.1.0) | Plán (2.2.0) | Plán (3.0.0) |
|---|---|---|---|---|
| `run-vnc.sh` | Spawn x11vnc, source config, iptables, tripwire | source `/var/lib/urcap-vnc/config`, CUSTOMER_LABEL → LOG_TAG, STRONG_PWD tripwire expand, `-accept/-gone` hooks pripravené | `-accept $AUDIT_HOOK` + `-gone $AUDIT_HOOK` aktivované | `-ssl SAVE`, `-connect_or_exit $MAX`, `-timeout` |
| `stop-vnc.sh` | Kill daemon + cleanup iptables + lock | source `/var/lib/urcap-vnc/config` (consistent CIDR) | + cleanup temp allowlist entries | + reset TLS cert if `--reset-tls` |
| `post-install.sh` | Bootstrap `/var/lib/urcap-vnc/` + pre-install x11vnc | NEW — vytvorí adresár, nastaví group=polyscope, chmod 770 | + vytvorí `/var/log/urcap-vnc-audit.log` rotation | + generuje TLS cert |
| `health-probe.sh` | Jednorázová check 5 indikátorov, JSON output | NEW (A4) | + probe audit log size | + probe TLS cert expiry |
| `diag-bundle.sh` | Zoberie logs, config, iptables stav → tar.gz | — | NEW (B2) | + TLS cert, temp allowlist history |
| `temp-allowlist-add.sh` | Pridá iptables rule s expiry timestamp | — | NEW (B3) | beze zmeny |
| `temp-allowlist-sweeper.sh` | Cron script — remove expired rules | — | NEW (B3) | beze zmeny |
| `vnc-audit-hook.sh` | Handler pre x11vnc `-accept`/`-gone` | — | NEW (B4) | beze zmeny |
| `vnc-test.sh` | RFB handshake localhost:5900 | — | NEW (B6) | beze zmeny |
| `tls-bootstrap.sh` | Generate self-signed TLS cert for x11vnc | — | — | NEW (C1) |
| `idle-watcher.sh` | Monitor client inputs, trigger disconnect on idle | — | — | NEW (C2) |

## Polyscope bundle manifest

| File | Rola | Naposledy menený | Dôvod |
|---|---|---|---|
| `src/main/resources/META-INF/MANIFEST.MF` | OSGi bundle headers | auto-generated by maven-bundle-plugin pri každom builde | — |
| `src/main/resources/daemon/program.xml` | Nie je — máme `InstallationNodeService` mode, nie program-mode | — | — |

## Dist

| File | Rola | SHA-256 | Dátum |
|---|---|---|---|
| `dist/stimba-vnc-server-2.0.0.urcap` | broken, never deploy | (archived) | 2026-04-16 |
| `dist/stimba-vnc-server-2.0.1.urcap` | iptables whitelist + tripwire | (archived) | 2026-04-17 |
| `dist/stimba-vnc-server-2.0.2.urcap` | default IXROUTER_IP=192.168.0.100 + override | `a16da33579f3d6dacbbf3ca183225ed5fe59f489835f12cf3471cd27c1be294e` | 2026-04-17 |
| `dist/stimba-vnc-server-2.1.0.urcap` | UI bridge + Vrstva A + B5 | `15c0bdf0e4f95f997578382b8403e30618d5859d819ad9b915523e977b3962dc` | 2026-04-17 |
| `dist/stimba-vnc-server-2.2.0.urcap` | Observability + temp allowlist + audit | `41e2915579991d0ea276cf2bfa42e524b8bbb94a317e0c899889dba430a4619d` | 2026-04-17 |
| `dist/stimba-vnc-server-3.0.0.urcap` | Sprint 3 TLS + idle + max-clients (pulled — crash) | `cd9aacbd975735e78c3686b02c608dc16374a053364415e19974c57589f49ac1` | 2026-04-17 |
| `dist/stimba-vnc-server-3.0.0-hotfix1.urcap` | MANIFEST javax.swing.* import fix (81 403 B) | `0455e4cfa4d22c948348f94dd938e1b4d7ee2b2c1b1213912e2b0088b774ec2f` | 2026-04-19 |
| `dist/stimba-vnc-server-3.0.1.urcap` | DataModel.get() null-safe cast pattern (pulled, 106 093 B) | `46eee7ca110b59c1b50472e69cae6a3aff69b64d73917a8a01d74bc536cb0843` | 2026-04-19 |
| `dist/stimba-vnc-server-3.0.2.urcap` | DataModel primitive-typed overloads (pulled, 106 006 B) | `14b9f4e75620a0ca80a173d620759784abbfb1ce5b2a88e169aa5b3f6d81cac1` | 2026-04-19 |
| `dist/stimba-vnc-server-3.0.3.urcap` | `isDefined()` → `true` fix (106 134 B) | `1b558b9d905eae06ed89fe316ee9b788021d602ba248023a2428addfca761b2e` | 2026-04-19 |
| `dist/stimba-vnc-server-3.0.4.urcap` | MANIFEST description bump, current prod (106 336 B, bytecode-identický s 3.0.3) | `8828fbe6fe076d72929f4b30be948b3043012aeba4007a6c6aa2f053afba4fdb` | 2026-04-19 |

## Wiki (toto)

| File | Rola |
|---|---|
| `wiki/00-INDEX.md` | Entry point, version status |
| `wiki/01-architecture.md` | ASCII stack + file location table |
| `wiki/02-feature-matrix.md` | A1–C7 features s status/version |
| `wiki/03-build-deploy.md` | Build options + deploy paths + §Regression gate |
| `wiki/04-gotchas.md` | UR-špecifické pasce (G12–G14 stub-drift lessons; G15 URCapX container runtime plánované v Sprint 6) |
| `wiki/05-file-map.md` | Tento súbor |
| `wiki/progress.md` | Running log zmien (vrátane Sprint 3.5 hotfix saga + Task #18 log) |
| `wiki/public-api-baseline.txt` | 72 public-API signatures baseline (pre Task #18 regression gate) |
| `wiki/sprints/sprint-1-v2.1.0.md` | Sprint 1 plan — UI→daemon bridge |
| `wiki/sprints/sprint-2-v2.2.0.md` | Sprint 2 plan — observability + audit |
| `wiki/sprints/sprint-3-v3.0.0.md` | Sprint 3 plan — TLS + timeouts |
| `wiki/sprints/sprint-6-v3.1.0.md` | Sprint 6 plan — URCapX cutover 2026-07 (container artifacts: cloudflared + stimba-agent) |
| `wiki/adr/*.md` | Architectural Decision Records (001–008) |
