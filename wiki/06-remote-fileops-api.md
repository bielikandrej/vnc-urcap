# 06 — Remote File Ops & URCap Install API (v3.12.16+)

> Portal-side contract for the URCap dispatcher commands added in v3.12.16. Implements **Path D** from `wiki/03-build-deploy.md` — remote URCap install without SSH/SCP — plus a chunked file-transfer surface to back the FTP-style file browser planned in `portal-stimba-sk`.

## Why

- `Path A (USB)` works but needs a person at the pendant.
- `Path B (SSH/SCP)` is blocked on PS 5.14+ by default — Settings → Security → Secure Shell is off, port 22 is in the inbound block list, and IXON's tunnel doesn't bypass the controller-side firewall. Enabling SSH still requires a one-time pendant trip (no documented remote enable as of UR community thread "[SSH Port blocked on new 5.21 image](https://forum.universal-robots.com/t/ssh-port-blocked-on-new-5-21-image/38683)").
- `Path D` reuses the URCap's already-paired HTTPS channel to portal.stimba.sk. The URCap process runs as root inside Polyscope, so it can write `/root/.urcaps/` directly. After v3.12.16 ships once via USB, every subsequent URCap upgrade is remote.

## Bootstrap

1. Build & USB-install **v3.12.16** once. From this point the dispatcher knows `install_urcap`.
2. Future URCap releases: portal enqueues `install_urcap` → URCap downloads from GitHub release → atomic move to `/root/.urcaps/` → URCap schedules `systemctl restart urcontrol.service` → Polyscope comes back up loading the new bundle.

## Command shapes

All commands flow through the existing `/api/agent/commands` long-poll dispatcher (see `DashboardCommandPoller.java`). Portal enqueues with shape `{ id, tool, args }`. URCap acks with `{ status, result?, error? }`.

### `install_urcap` — remote URCap install

```json
{
  "tool": "install_urcap",
  "args": {
    "url":    "https://github.com/bielikandrej/vnc-urcap/releases/download/v3.12.17/vnc-server-3.12.17.urcap",
    "sha256": "78245ad85838f4288e5f1bb3d20ca53175906980f87d278e49a9143b51c7bf8f",
    "name":   "vnc-server-3.12.17.urcap"
  }
}
```

**Validation (URCap-side, defense in depth):**
- `url` must be HTTPS.
- `url` host must match `URCAP_INSTALL_HOST_ALLOWLIST` env var, default `github.com/bielikandrej/` and `objects.githubusercontent.com/` (GitHub release-asset CDN that github.com redirects to).
- Up to 5 redirect hops, each re-checked against allowlist.
- `sha256` is exactly 64 lower-case hex chars; mismatch → bundle deleted, `Ack(false)`.
- `name` matches `[A-Za-z0-9._-]+\.urcap`, no path separators, ≤200 chars.
- Bundle ≤ 100 MiB (typical urcaps are ~1.9 MB).

**Behaviour:**
1. Download to `/root/.urcaps/.<name>.tmp` while streaming SHA-256.
2. On hash match, atomic `Files.move` to `/root/.urcaps/<name>` (POSIX rename).
3. Spawn daemon thread → sleep 5 s → `systemctl restart urcontrol.service`.
4. Return `Ack(ok=true, result="Installed <name> (<bytes> bytes). Controller restart scheduled in 5s.")` — portal records the success before the restart kills the URCap.

**Behaviour on failure:** tmp file cleaned up; `Ack(ok=false, error=…)` with reason; no restart scheduled.

**Portal-side gating:** treat `install_urcap` as the highest-privilege tool. Recommend an admin-only role check at enqueue time (similar to ai.execute) plus a confirmation modal in the UI ("Install vnc-server-3.12.17.urcap? Robot will restart.").

### `file_list { path }`

Returns the contents of a directory inside the file-ops allowlist.

```json
// args
{ "path": "/root/.urcaps" }

// result
{
  "path": "/root/.urcaps",
  "entries": [
    { "name": "vnc-server-3.12.16.urcap", "type": "file", "size": 1907369, "mtime_ms": 1745000000000 },
    { "name": "another.urcap",            "type": "file", "size":  500000, "mtime_ms": 1745000000000 }
  ]
}
```

### `file_stat { path }`

```json
{ "path": "/root/.urcaps/vnc-server-3.12.16.urcap" }
// →
{ "path": "/root/.urcaps/vnc-server-3.12.16.urcap", "exists": true, "type": "file",
  "size": 1907369, "mtime_ms": 1745000000000, "can_write": true }
```

### `file_read { path, offset, length }`

Reads a chunk of a file. `length` ≤ 1 MiB per call.

```json
// args
{ "path": "/var/log/urcap-vnc.log", "offset": "0", "length": "65536" }

// result
{ "b64": "PGRhdGE+", "eof": false, "total_size": 4194304, "bytes": 65536 }
```

Caller chunks until `eof: true` to pull whole files.

### `file_write { path, offset, b64, total_size?, truncate? }`

Writes a chunk. `b64` decoded ≤ 8 MiB per call.

```json
{ "path": "/programs/myprog.urp", "offset": "0", "b64": "...", "truncate": "true" }
```

- `truncate=true` only honoured at `offset=0`.
- Optional `total_size` lets URCap log a "complete" event when the cumulative writes match.

### `file_delete { path }`

Refuses non-empty directories. Idempotent on missing paths (returns `{deleted:false, reason:"not found"}`).

### `file_mkdir { path }`

Creates parents, idempotent on existing dirs of the right type.

## Path allowlist

Implemented in `RemoteFileOps.resolveSafe`. Symlinks resolved via `Path.toRealPath()` before allowlist check, so `/programs/foo -> /etc/passwd` style escapes are caught.

| Path | Read | Write |
|---|---|---|
| `/programs/` | ✓ | ✓ |
| `/root/.urcaps/` | ✓ | ✓ |
| `/tmp/` | ✓ | ✓ |
| `/var/lib/urcap-vnc/` | ✓ | ✓ |
| `/var/log/urcap-vnc.log` | ✓ | ✗ |
| `/var/log/urcap-vnc-audit.log` | ✓ | ✗ |
| `/var/log/PolyscopeLog.log` | ✓ | ✗ |
| `/var/log/urcontrol.log` | ✓ | ✗ |
| Everything else (incl. `/etc/passwd`, `/root/.ssh/`, `/root/.vnc/passwd`) | ✗ | ✗ |

Per-call caps: read ≤ 1 MiB, write chunk ≤ 8 MiB, per-file ≤ 100 MiB.

## Portal-side implementation notes

### API endpoints to add

```
POST /api/devices/[id]/files/list?path=<p>     → enqueue file_list, await ack
POST /api/devices/[id]/files/stat?path=<p>     → file_stat
POST /api/devices/[id]/files/read              → file_read (body: {path, offset, length})
POST /api/devices/[id]/files/write             → file_write (body: {path, offset, b64, …})
POST /api/devices/[id]/files/delete            → file_delete
POST /api/devices/[id]/files/mkdir             → file_mkdir
POST /api/devices/[id]/urcap/install           → install_urcap (body: {url, sha256, name}) — admin-only
```

Each endpoint enqueues a command, polls the ack table until completion (timeout ≥ 35 s for chunked file ops), returns the URCap's result/error JSON.

### UI (FTP-style file browser)

Suggested layout in portal `app/devices/[id]/files/page.tsx`:

- Tree view of allowlisted roots (`/programs`, `/root/.urcaps`, `/tmp`, `/var/lib/urcap-vnc`, log files).
- Right-pane file listing (powered by `file_list`).
- Upload dropzone — chunks files into ≤ 8 MiB pieces, calls `file_write` per chunk with progress bar.
- Download button — chunks via `file_read` until `eof:true`, assembles into a Blob, triggers browser download.
- "Install URCap" button (separate flow, admin-only) — accepts a GitHub release URL, fetches asset metadata to compute SHA-256 (or asks user to paste), enqueues `install_urcap`, shows toast with restart countdown.

### Throughput sketch

- Long-poll cycle is ~25 s upper bound on command latency.
- 10 MiB file at 8 MiB/chunk = 2 chunks ≈ 50 s round-trip.
- Bigger files: scale linearly, or in v3.12.17+ add a streaming over the existing VNC relay WS.

## Security posture

- All file ops gated by allowlist + symlink resolution.
- `install_urcap` URL allowlist limits attacker pivot from compromised portal to arbitrary code execution.
- SHA-256 verification protects against in-flight tampering even on HTTPS-broken middleboxes.
- No auth-bypass: URCap trusts the queue, portal gates the queue. All access checks are portal-side.
- No new ports opened on the robot. No SSH enabled. No firewall holes punched.

## Test plan

After v3.12.16 USB-installs:

```bash
# From Mac, while portal admin:
curl -X POST -H "Content-Type: application/json" \
  -d '{"args":{"path":"/root/.urcaps"}}' \
  https://portal.stimba.sk/api/devices/<id>/files/list
# → { entries: [{ name: "vnc-server-3.12.16.urcap", … }] }

# Remote install of v3.12.17 over the same channel:
curl -X POST -H "Content-Type: application/json" \
  -d '{"args":{"url":"https://github.com/bielikandrej/vnc-urcap/releases/download/v3.12.17/vnc-server-3.12.17.urcap","sha256":"…","name":"vnc-server-3.12.17.urcap"}}' \
  https://portal.stimba.sk/api/devices/<id>/urcap/install
# Robot restarts. After ~30 s, /healthz on stimba-vnc-relay shows agent reconnect.
```

## Limits & known issues

- Robot must be **not running a program** when `install_urcap` is invoked — the controller restart will halt any active motion. Portal UI should pre-flight `dashboard_safetymode normal` + `program_stop` and confirm with operator.
- If new bundle is broken (e.g. another `-connect_or_exit` style bug) the daemon may respawn-loop after restart. Mitigation: **keep both .urcap files in `/root/.urcaps/` until the new one is verified healthy** — Polyscope picks the latest by Bundle-Version, but the older one remains as fallback. The URCap installer keeps existing files in place; only writes the new one.
- File ops are RPC-style (request/response in the long-poll cadence). Streaming uploads >50 MiB will be perceptibly slow. If a streaming workflow is needed, extend `stimba-vnc-relay` with a `/file/<deviceId>` WS endpoint (parallel to `/agent/<deviceId>` for VNC).
