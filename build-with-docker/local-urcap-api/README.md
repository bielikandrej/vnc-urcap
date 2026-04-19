# URCap API JAR — embedded in repo

**Status (2026-04-19, v3.1.0):** `api-1.16.0.jar` + `api-1.16.0.pom` +
`api-1.16.0-sources.jar` are the **active** artefakty. The Docker build picks
them up via `COPY local-urcap-api/` + `mvn install:install-file` (see
`../Dockerfile`).

The files were extracted from the official URCap SDK 1.18 distributed by
Universal Robots A/S (`artifacts/api/1.16.0/com.ur.urcap.api-1.16.0.jar`,
built 2024-11-19 per `Bnd-LastModified: 1732005135977`). All three files
carry UR's copyright; we redistribute them here strictly because:
1. UR's SDK does not change the JAR between SDK releases for a given API
   version — once a version is published, it is byte-stable.
2. The `Import-Package` directive in our bundle's manifest means we
   *consume* the API at runtime from Polyscope's own classpath; we never
   ship the JAR inside the `.urcap`.
3. Having the JAR in-tree makes CI (GitHub Actions) and fresh clones work
   without manual SDK scavenging — closes the loophole behind the
   3.0.0 → 3.0.4 stub-drift hotfix storm (see `wiki/04-gotchas.md`).

## Why 1.16.0 (bumped from 1.3.0 in v3.1.0, 2026-04-19)

STIMBA fleet telemetry confirmed floor = Polyscope 5.20, ceiling = 5.25.
API 1.16.0 requires Polyscope 5.16+, giving us **4 minor versions of
headroom** and — crucially — aligns our compile target with the PS 5.16
TLS/x509 security overhaul (stricter cert-chain validation, TLS 1.2
minimum). Since our `-ssl SAVE` x11vnc path depends on that same security
stack, this is the "natural" alignment.

Our code uses only byte-stable interfaces (DaemonService × 2 methods,
DaemonContribution × 4, InstallationNodeContribution × 3, DataModel
primitive overloads) so the regression gate baseline stays at 72
signatures — no code change, no baseline regen needed.

**Rollback:** set `<urcap.api.version>1.3.0</urcap.api.version>` in
`../../pom.xml` and `api-1.3.0.*` file-references in `../Dockerfile`.
The legacy 1.3.0 artefakty remain in this folder for exactly this reason.

## Legacy 1.3.0 artefakty (retained, not active)

Files `api-1.3.0.jar` + `api-1.3.0.pom` + `api-1.3.0-sources.jar` are still
present in this folder but are NOT installed into `~/.m2` by Dockerfile.
They exist for:
- quick `git bisect` without re-fetching JARs from SDK
- documented rollback path (flip one property + one Dockerfile line)
- audit reference ("this is what we shipped 2.0.0 → 3.0.4")

If you are doing fresh repo surgery and want the folder clean, run
`git rm build-with-docker/local-urcap-api/api-1.3.0.*` and commit — the
3.0.x tags in git history remain the rollback source.

---

## Upgrade path (when and how)

Drop newer JAR (+ sources + pom) into this folder, then in one PR:
1. Update `../../pom.xml` → `<urcap.api.version>X.Y.Z</urcap.api.version>`
2. Update `../Dockerfile` → replace all `api-1.16.0` occurrences with `api-X.Y.Z`
3. Update this README status line + compat matrix below
4. Run `make regress-write` if signatures changed (unlikely for our code)
5. Bump `<version>` in pom.xml (minor if API version bump, patch if not)
6. Add wiki/progress.md entry

Available API versions in `sdk-1.18/artifacts/api/`: 1.0.0, 1.1.0, 1.2.56,
1.3.0 – 1.18.0 (19 total). Pick based on fleet floor + feature need.

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
   sdk-x.y.z/artifacts/api/1.16.0/com.ur.urcap.api-1.16.0.jar   <-- this one
   sdk-x.y.z/artifacts/api/1.16.0/com.ur.urcap.api-1.16.0-sources.jar
   ```
4. Copy the .jar (and sources) into THIS folder. Rename by stripping the
   `com.ur.urcap.` prefix so it matches the Dockerfile expectation:
   `com.ur.urcap.api-1.16.0.jar` → `api-1.16.0.jar`.

## Option B — Extract from an already-installed URCap-Samples repo

If you previously built any URCap following the official samples, Maven cached
the JAR at `~/.m2/repository/com/ur/urcap/api/1.16.0/`. Copy it from there.

## Option C — Use the FZI External Control URCap as a reference

The FZI folks redistribute the URCap API via their public Nexus:
https://nexus.fzi.de/. Their `pom.xml` shows how. We avoid pulling from FZI's
Nexus in production because it may disappear; use only as an emergency.

## File layout expected by the Dockerfile

```
build-with-docker/
├── Dockerfile
└── local-urcap-api/
    ├── api-1.16.0.jar              <-- required, active
    ├── api-1.16.0.pom              <-- optional; auto-generated if absent
    ├── api-1.16.0-sources.jar      <-- optional; IDE source lookup only
    ├── api-1.3.0.jar               <-- legacy, NOT installed
    ├── api-1.3.0.pom               <-- legacy, NOT installed
    └── api-1.3.0-sources.jar       <-- legacy, NOT installed
```

## Compatibility matrix (e-Series; CB3 via URCapCompatibility-CB3 flag)

| URCap API version | Requires min. Polyscope | Works on         | STIMBA uses |
|-------------------|------------------------|------------------|-------------|
| 1.3.0             | 5.0                    | 5.0 → 5.25 (ALL) | v2.0.0 → v3.0.4 (legacy) |
| 1.4.0             | 5.1                    | 5.1 → 5.25       | —           |
| 1.13.0            | 5.11                   | 5.11 → 5.25      | —           |
| **1.16.0**        | **5.16**               | **5.16 → 5.25**  | **v3.1.0+ (active)** |
| 1.18.0            | 5.18                   | 5.18 → 5.25      | —           |

Note: "Requires min. Polyscope" is the minimum PS version whose bundled OSGi
`com.ur.urcap.api` export satisfies the URCap's `Import-Package` range. Bump to
a newer API only if you need a feature that specific version introduced; for
STIMBA VNC URCap there is no such need between 1.16 and 1.18.
