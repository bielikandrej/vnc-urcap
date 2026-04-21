# STIMBA VNC URCapX — Polyscope X scaffold (v3.9 preview)

**Status:** scaffold only. Not built, not released. v3.10 will wire in the full
feature set after we have a PolyScope X controller to test on.

## What is this

Universal Robots released **PolyScope X** — a ground-up rewrite of the
teach-pendant software stack. URCaps for PolyScope X are called **URCapX**
and are fundamentally different from the Java OSGi URCaps we ship for
PolyScope 5 e-Series in the parent project:

| | PolyScope 5 URCap (this repo's root) | PolyScope X URCapX (this folder) |
| --- | --- | --- |
| **Frontend** | Java Swing in-process | Angular TypeScript, containerized |
| **Backend** | OSGi bundle (Felix) | Optional Docker container (Python/C++) |
| **Artifact** | `vnc-server-3.x.y.urcap` (.jar renamed) | `vnc-server-3.x.y.urcapx` (zip bundle) |
| **Install** | `/root/.urcaps/` | Per-container via UR's Orchestrator |
| **Lifecycle** | OSGi Activator.start/stop | Angular NgModule + container entrypoint |
| **URScript gen** | `ScriptWriter` in Installation node | `.behavior.worker.ts` with `ScriptBuilder` |
| **Build** | Maven `mvn package` | `npm install && npm run build` |
| **SDK** | `com.ur.urcap:api:1.16.0` | `@universal-robots/contribution-api` |

Official UR docs: <https://docs.universal-robots.com/PolyScopeX_SDK_Documentation/>
SDK GitHub: <https://github.com/UniversalRobots/PolyScopeX_URCap_SDK>

## v3.9 scope (scaffolded)

- `manifest.yaml` — URCapX metadata (id, name, version, contributions list).
- `package.json` — npm dependencies (`@universal-robots/contribution-api`,
  `@angular/core`, etc.).
- `src/app/app.module.ts` — Angular entry module.
- `src/app/components/installation-node/` — Installation node contribution
  mirroring our PS5 `VncInstallationNodeView` (pairing status, portal URL,
  VNC password rotation button).
- `docs/URCAPX_ARCHITECTURE.md` — migration path + feature parity matrix.

## v3.10+ scope (planned)

- Program node contribution with `urscript_send` surface.
- Optional Python backend container for `PrimaryInterfaceClient` (same port
  30001 — URCapX can open local sockets).
- RTDE reader ported from Java `RtdeReader.java`.
- Dual-build GitHub Actions matrix: `[ps5, psx]`.
- Release pairs (v3.x.y.urcap + v3.x.y.urcapx for the same commit).

## Why scaffold now?

Two reasons:

1. **Portal compat bet** — our portal already carries `devices.polyscope_major`
   with `psx` as a valid value. v3.9 wires up the UI hints that tell a PSX
   customer which artifact to install.
2. **Low-risk learning** — reading through UR's SDK tutorial (my-first-urcap)
   and mapping it to our existing PS5 feature set surfaces gaps we want to
   know about before committing to a PSX customer deal.

See `docs/URCAPX_ARCHITECTURE.md` for the full comparison.
