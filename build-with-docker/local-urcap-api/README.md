# URCap API JAR — embedded in repo

**Status (2026-04-19):** `api-1.3.0.jar` + `api-1.3.0.pom` + `api-1.3.0-sources.jar`
are committed alongside this README. The Docker build picks them up via
`COPY local-urcap-api/` + `mvn install:install-file` (see `../Dockerfile`).

The files were extracted from the official URCap SDK 1.18 distributed by
Universal Robots A/S (`artifacts/api/1.3.0/` inside the SDK zip). All three
files carry UR's copyright; we redistribute them here strictly because:
1. UR's SDK does not change the JAR between SDK releases for a given API
   version — 1.3.0 has been byte-stable since 2018.
2. The `Import-Package` directive in our bundle's manifest means we
   *consume* the API at runtime from Polyscope's own classpath; we never
   ship the JAR inside the `.urcap`.
3. Having the JAR in-tree makes CI (GitHub Actions) and fresh clones work
   without manual SDK scavenging — closes the loophole behind the
   3.0.0→3.0.4 stub-drift hotfix storm (see `wiki/04-gotchas.md`).

If you ever need to upgrade to a newer API (e.g. 1.4.0 for FZI parity,
1.13.0 for Polyscope 5.11+ features), drop the new JAR in this folder,
update `../Dockerfile` + `../../pom.xml` (`urcap.api.version`) together,
regenerate `../../wiki/public-api-baseline.txt` via `make regress-write`,
and open a PR.

---

## Where the JAR originally comes from

The Universal Robots URCap API JAR is **not** published to Maven Central. You
have three ways to obtain it:

## Option A — Extract from the official URCap SDK zip

1. Go to https://www.universal-robots.com/download/software-e-series/ (section
   "URCap software platform" → "URCap Software Development Kit").
2. Download `URCap-x.y.z.zip` (the repo inside the zip is also known as
   `sdk-x.y.z`).
3. Unzip, then find the API JAR:
   ```
   URCap-x.y.z/com/
   URCap-x.y.z/doc/
   URCap-x.y.z/sdk/com/ur/urcap/api/1.3.0/api-1.3.0.jar   <-- this one
   URCap-x.y.z/sdk/com/ur/urcap/api/1.3.0/api-1.3.0.pom
   ```
4. Copy `api-1.3.0.jar` (and the .pom if present) into THIS folder.

## Option B — Extract from an already-installed URCap-Samples repo

If you previously built any URCap following the official samples, Maven cached
the JAR at `~/.m2/repository/com/ur/urcap/api/1.3.0/`. Copy it from there.

## Option C — Use the FZI External Control URCap as a reference

The FZI folks redistribute the URCap API via their public Nexus:
https://nexus.fzi.de/. Their `pom.xml` shows how. We avoid pulling from FZI's
Nexus in production because it may disappear; use only as an emergency.

## File layout expected by the Dockerfile

```
build-with-docker/
├── Dockerfile
└── local-urcap-api/
    ├── api-1.3.0.jar      <-- required
    └── api-1.3.0.pom      <-- optional; auto-generated if absent
```

## Compatibility matrix

| URCap API version | Targets Polyscope e-Series | Works on                    |
|-------------------|-----------------------------|------------------------------|
| 1.3.0             | 5.0.x                       | ALL 5.x releases (5.0–5.25)  |
| 1.4.0             | 5.1.x                       | 5.1 → 5.25                   |
| 1.13.0            | 5.11.x                      | 5.11 → 5.25                  |
| 1.18.0            | 5.25.x                      | 5.25 only                    |

We ship with **1.3.0** for maximum fleet compatibility. Bump to a newer API
only if you need a feature that specific version introduced.
