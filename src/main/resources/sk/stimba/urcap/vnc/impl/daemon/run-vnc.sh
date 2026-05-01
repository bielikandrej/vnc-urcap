#!/bin/bash
#
# STIMBA VNC Server daemon (URCap 3.0.0)
# Started/stopped by Polyscope URCap daemon lifecycle
#
# What this does
# --------------
# 1. Self-bootstraps /var/lib/urcap-vnc/ (chown root:polyscope, chmod 770)
#    so the polyscope-user UI can write its config atomically.
# 2. Reads config in priority order:
#      a) /var/lib/urcap-vnc/config       (UI-owned, v2.1.0+)
#      b) /root/.urcap-vnc.conf           (legacy, v2.0.1–2.0.2)
#    See ADR-003 (legacy bridge) and ADR-004 (UI bridge) in wiki/adr/.
# 3. easybot tripwire: refuses start if root still uses factory "easybot"
#    and URCAP_VNC_REQUIRE_STRONG_PWD=1. See ADR-005.
# 4. Ensures x11vnc installed (apt-get first-boot).
# 5. Writes/refreshes VNC password (first 8 chars count — RFB truncation, G2).
# 6. iptables whitelist: ACCEPT IXROUTER_IP, ACCEPT 127.0.0.1, DROP rest
#    on tcp/VNC_PORT (kernel-level RBAC, see ADR-002).
# 7. v3.0.0 (C1): if TLS_ENABLED, invokes tls-bootstrap.sh and passes
#    `-ssl SAVE` to x11vnc so the wire is AES-encrypted end-to-end.
# 8. v3.12.15 (C4): passes `-nevershared` when MAX_CLIENTS=1 (single-session
#    admin), or `-shared` for 2-5. (Replaces broken v3.0.0 misuse of x11vnc
#    `-connect_or_exit N` which is a reverse-VNC flag, not a client-cap.)
# 9. v3.0.0 (C2): if IDLE_TIMEOUT_MIN>0, spawns idle-watcher.sh in the
#    background to kick idle clients after N minutes of no pointer motion.
#10. exec x11vnc attached to Polyscope DISPLAY :0 on 0.0.0.0:VNC_PORT.
#
# Why x11vnc (not TigerVNC/x0vncserver)
# -------------------------------------
# Polyscope owns DISPLAY :0 via Xorg. We MUST attach to that existing display,
# not spin up a new Xvnc. x11vnc is the canonical tool for this exact pattern.
#
# Security model
# --------------
# x11vnc binds 0.0.0.0 BUT iptables source-IP whitelist restricts port
# to IXROUTER_IP + 127.0.0.1 only. Defence in depth = iptables + VNC
# password + IXON Cloud 2FA on the portal side.
#
# Config keys (v3.0.0)
#   VNC_PORT                     = 5900
#   VNC_PASSWORD                 = (first 8 chars matter — RFB spec)
#   VNC_VIEW_ONLY                = false
#   IXROUTER_IP                  = 192.168.0.100 (STIMBA fleet default)
#   CUSTOMER_LABEL               = "" (appears in LOG_TAG + audit log)
#   URCAP_VNC_REQUIRE_STRONG_PWD = 1 (refuse start on default easybot root pw)
#   TLS_ENABLED                  = 1 (v3.0.0 — C1, wire-level SSL via x11vnc)
#   IDLE_TIMEOUT_MIN             = 30 (v3.0.0 — C2, 0 = disabled)
#   MAX_CLIENTS                  = 1 (v3.0.0 — C4, 1..5)
#
# Exit codes
# ----------
#   0    - daemon started cleanly (exec into x11vnc)
#  10    - apt-get install failed (no network?)
#  11    - /tmp/urcap-vnc.lock exists (already running)
#  12    - DISPLAY :0 not reachable
#  13    - iptables command not available or whitelist setup failed
#  14    - root password is still 'easybot' and URCAP_VNC_REQUIRE_STRONG_PWD=1
#  15    - bootstrap failure (cannot chown/chmod /var/lib/urcap-vnc)
#  16    - TLS enabled but tls-bootstrap.sh failed (v3.0.0)
#
set -eu

LOCK_FILE="/tmp/urcap-vnc.lock"
VNC_PASSWORD_FILE="/root/.vnc/passwd"

# --- 0a. Self-bootstrap /var/lib/urcap-vnc/ (UI-owned config dir) -----------
# Daemon runs as root, so it can create the dir and fix permissions such that
# the polyscope user (UI) can drop config files atomically. See ADR-004.
URCAP_STATE_DIR="/var/lib/urcap-vnc"
if [ ! -d "${URCAP_STATE_DIR}" ]; then
    mkdir -p "${URCAP_STATE_DIR}" || exit 15
fi
# Fix ownership/perms idempotently — chown will silently succeed if already correct.
# Target group 'polyscope' exists on UR e-Series (uid 1000 family).
if getent group polyscope >/dev/null 2>&1; then
    chown root:polyscope "${URCAP_STATE_DIR}" 2>/dev/null || true
    chmod 2770 "${URCAP_STATE_DIR}" 2>/dev/null || true  # 2=setgid so new files inherit group
else
    # UR polyscope group naming varies — degrade to world-writable with sticky bit
    chmod 1777 "${URCAP_STATE_DIR}" 2>/dev/null || true
fi
mkdir -p "${URCAP_STATE_DIR}/sessions" 2>/dev/null || true

# --- 0a2. v3.12.2 — make sibling helper scripts executable ------------------
# The URCap `.urcap` bundle preserves Unix mode bits only when it was
# repackaged with them set. On fresh extraction via Polyscope's URCap
# installer some sibling scripts (diag-bundle.sh, vnc-test.sh,
# health-probe.sh, tls-bootstrap.sh, idle-watcher.sh,
# temp-allowlist-*.sh, vnc-audit-hook.sh) may land 644. The UI sees this
# as "nie je executable" banners on Diag + Test rows. Fix idempotently
# every daemon start — no-op when already +x.
SCRIPT_SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
chmod +x "${SCRIPT_SELF_DIR}"/*.sh 2>/dev/null || true

# --- 0a3. v3.12.14 — bridge /root/.urcaps/<id> path the Java code expects ---
# Polyscope 5.25.x extracts URCap bundle resources into felix-cache instead
# of the /root/.urcaps/<id>/ layout VncInstallationNodeContribution.java
# hardcodes for ProcessBuilder spawns of vnc-test.sh, health-probe.sh,
# diag-bundle.sh, temp-allowlist-add.sh. Without this symlink every
# diagnostic spawn returns exit 127 / "No such file or directory".
#
# v3.12.10 first attempted this in post-install.sh, but post-install.sh
# is never invoked at runtime (no caller in run-vnc.sh or Java code), so
# the symlink never appeared on the robot. Moving it here means it's
# created on every daemon start (Polyscope DaemonContribution invokes
# run-vnc.sh on URCap install, on Polyscope reboot, and on daemon
# supervisor restart — covering every realistic install path).
URCAP_DAEMON_LINK="/root/.urcaps/sk.stimba.urcap.vnc-server/sk/stimba/urcap/vnc/impl/daemon"
mkdir -p "$(dirname "${URCAP_DAEMON_LINK}")" 2>/dev/null || true
# `ln -sfn` does NOT replace a real directory with a symlink — it would create
# a nested link inside it. If a previous URCap install left a real directory
# at this path (Polyscope used to extract here on older firmware), wipe it
# first. Only ever wipes our own URCap's daemon directory, never customer data.
if [ ! -L "${URCAP_DAEMON_LINK}" ] && [ -e "${URCAP_DAEMON_LINK}" ]; then
    rm -rf "${URCAP_DAEMON_LINK}" 2>/dev/null || true
fi
ln -sfn "${SCRIPT_SELF_DIR}" "${URCAP_DAEMON_LINK}" 2>/dev/null || true

# --- 0b. Source config — v2.1.0 first, legacy fallback ----------------------
CONFIG_FILE_NEW="/var/lib/urcap-vnc/config"
CONFIG_FILE_LEGACY="/root/.urcap-vnc.conf"
CONFIG_SOURCED="none"
if [ -r "${CONFIG_FILE_NEW}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_NEW}"
    CONFIG_SOURCED="${CONFIG_FILE_NEW}"
elif [ -r "${CONFIG_FILE_LEGACY}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_LEGACY}"
    CONFIG_SOURCED="${CONFIG_FILE_LEGACY}"
fi

# --- Defaults (compiled-in) -------------------------------------------------
VNC_PORT="${VNC_PORT:-5900}"
VNC_PASSWORD="${VNC_PASSWORD:-ixon}"
VNC_VIEW_ONLY="${VNC_VIEW_ONLY:-false}"
# Verified Apr 2026 across the STIMBA fleet: most installs use 192.168.0.100.
IXROUTER_IP="${IXROUTER_IP:-192.168.0.100}"
CUSTOMER_LABEL="${CUSTOMER_LABEL:-}"
URCAP_VNC_REQUIRE_STRONG_PWD="${URCAP_VNC_REQUIRE_STRONG_PWD:-1}"
# v3.0.0 Sprint 3 knobs
TLS_ENABLED="${TLS_ENABLED:-1}"
IDLE_TIMEOUT_MIN="${IDLE_TIMEOUT_MIN:-30}"
MAX_CLIENTS="${MAX_CLIENTS:-5}"  # v3.12.20 (was 1) — multi-viewer default
DISPLAY="${DISPLAY:-:0}"
export DISPLAY

# Normalise truthy/numeric values early so downstream checks can rely on them
case "${TLS_ENABLED}" in
    1|true|TRUE|yes|on)  TLS_ENABLED=1 ;;
    *)                   TLS_ENABLED=0 ;;
esac
[[ "${IDLE_TIMEOUT_MIN}" =~ ^[0-9]+$ ]] || IDLE_TIMEOUT_MIN=0
[[ "${MAX_CLIENTS}" =~ ^[1-9][0-9]*$ ]] || MAX_CLIENTS=1
[ "${MAX_CLIENTS}" -gt 5 ] && MAX_CLIENTS=5

# LOG_TAG carries customer label for fleet-wide grep on journalctl
if [ -n "${CUSTOMER_LABEL}" ]; then
    LOG_TAG="urcap-vnc[${CUSTOMER_LABEL}]"
else
    LOG_TAG="urcap-vnc"
fi

# v3.12.3 — also append every log line to /var/log/urcap-vnc.log so the UI's
# "Log tail" section sees it. Before this, only x11vnc's own `-logappend`
# wrote there — but that only runs AFTER x11vnc binds the port. If x11vnc
# crashes during bootstrap (missing binary, bad Xauth, TLS cert fail),
# nothing ever reached the log file and the user was blind.
DAEMON_LOG="/var/log/urcap-vnc.log"
touch "${DAEMON_LOG}" 2>/dev/null || true
chmod 644 "${DAEMON_LOG}" 2>/dev/null || true

# v3.12.20 — defense-in-depth log size cap. The robot's SD card is 1.7 GB;
# under a respawn loop our log filled to 134 MB in ~10 hours (2026-04-30
# incident). post-install.sh installs logrotate but if it's missing or a
# day's rotation hasn't fired yet, trim here at startup so we never see
# the 100% disk again. 10 MB threshold; keep last 5 MB.
if [ -s "${DAEMON_LOG}" ] && [ "$(stat -c%s "${DAEMON_LOG}" 2>/dev/null || echo 0)" -gt $((10*1024*1024)) ]; then
    tail -c 5242880 "${DAEMON_LOG}" > "${DAEMON_LOG}.tmp" 2>/dev/null \
        && mv "${DAEMON_LOG}.tmp" "${DAEMON_LOG}" 2>/dev/null \
        && echo "$(date '+%Y-%m-%d %H:%M:%S') [urcap-vnc] startup-trim: capped ${DAEMON_LOG} at 5MB" >> "${DAEMON_LOG}"
fi
log() {
    local msg="$*"
    logger -t "${LOG_TAG}" "${msg}" 2>/dev/null || true
    local line="$(date '+%Y-%m-%d %H:%M:%S') [${LOG_TAG}] ${msg}"
    echo "${line}"
    echo "${line}" >> "${DAEMON_LOG}" 2>/dev/null || true
}

log "v3.0.0 starting — config source: ${CONFIG_SOURCED}"
log "IXROUTER_IP=${IXROUTER_IP} VNC_PORT=${VNC_PORT} VIEW_ONLY=${VNC_VIEW_ONLY} STRONG_PWD=${URCAP_VNC_REQUIRE_STRONG_PWD}"
log "TLS_ENABLED=${TLS_ENABLED} IDLE_TIMEOUT_MIN=${IDLE_TIMEOUT_MIN} MAX_CLIENTS=${MAX_CLIENTS}"

#
# 0c. Default-password tripwire (see ADR-005)
#
# UR ships Polyscope 5.x with Admin password "easybot" which is ALSO the
# Debian root password. We refuse start if unchanged and strong-pwd toggle
# is on; operator sees red banner in UI + non-zero exit in daemon status.
#
if [ "${URCAP_VNC_REQUIRE_STRONG_PWD}" = "1" ] || [ "${URCAP_VNC_REQUIRE_STRONG_PWD}" = "true" ]; then
    if [ -r /etc/shadow ]; then
        ROOT_HASH_LINE="$(awk -F: '$1=="root"{print $2}' /etc/shadow 2>/dev/null || echo '')"
        if [ -n "${ROOT_HASH_LINE}" ]; then
            SALT="$(printf '%s' "${ROOT_HASH_LINE}" | awk -F'$' '{print "$"$2"$"$3"$"}')"
            if [ -n "${SALT}" ] && [ "${SALT}" != '$$$' ]; then
                EASYBOT_HASH="$(python3 -c "import crypt; print(crypt.crypt('easybot','${SALT}'))" 2>/dev/null || echo '')"
                if [ -n "${EASYBOT_HASH}" ] && [ "${EASYBOT_HASH}" = "${ROOT_HASH_LINE}" ]; then
                    log "##########################################################"
                    log "# REFUSAL: root password is factory default 'easybot'."
                    log "# Polyscope admin = Linux root — change via:"
                    log "#   ssh root@<robot> 'passwd root'"
                    log "# Or disable tripwire in Installation → VNC Server (NOT recommended)."
                    log "##########################################################"
                    exit 14
                fi
            fi
        fi
    fi
fi

#
# 0d. Prevent double-start
#
if [ -e "${LOCK_FILE}" ]; then
    EXISTING_PID="$(cat "${LOCK_FILE}" 2>/dev/null || echo '')"
    if [ -n "${EXISTING_PID}" ] && kill -0 "${EXISTING_PID}" 2>/dev/null; then
        log "already running as PID ${EXISTING_PID}; exiting"
        exit 11
    else
        log "stale lock file; removing"
        rm -f "${LOCK_FILE}"
    fi
fi

echo "$$" > "${LOCK_FILE}"
trap 'rm -f "${LOCK_FILE}"' EXIT

#
# 1. Install x11vnc if not present (robot must be online on first start)
#
# v3.12.4 — UR e-Series ships with Debian 9 (Stretch) or 10 (Buster), both EOL.
# deb.debian.org + security.debian.org stop serving EOL dists, so a fresh
# `apt-get update` fails with 404s even on a robot with perfectly working
# internet. Rewrite apt sources to archive.debian.org (which still serves
# EOL) before the first attempt, then retry. Idempotent — runs every daemon
# boot but only writes sources files if they still point at the live
# mirrors.
fix_apt_sources() {
    # v3.12.5 — log the actual system state first so when the install still
    # fails we have enough in /var/log/urcap-vnc.log to diagnose without
    # SSH. UR firmware varies; previous codename detection relied on
    # /etc/os-release VERSION_CODENAME which is empty on at least one
    # Stimba fleet robot.
    log "--- apt environment dump ---"
    if [ -r /etc/os-release ]; then
        log "/etc/os-release:"
        sed 's/^/    /' /etc/os-release | tee -a "${DAEMON_LOG}"
    else
        log "/etc/os-release missing"
    fi
    if [ -r /etc/debian_version ]; then
        log "/etc/debian_version: $(cat /etc/debian_version)"
    fi
    log "/etc/apt/sources.list:"
    if [ -r /etc/apt/sources.list ]; then
        sed 's/^/    /' /etc/apt/sources.list | tee -a "${DAEMON_LOG}"
    else
        log "    (missing)"
    fi
    for f in /etc/apt/sources.list.d/*.list; do
        [ -f "$f" ] || continue
        log "${f}:"
        sed 's/^/    /' "$f" | tee -a "${DAEMON_LOG}"
    done
    log "--- end apt dump ---"

    # Detect codename via three methods: os-release → debian_version →
    # grep sources.list for known keyword. First hit wins.
    CODENAME=""
    if [ -r /etc/os-release ]; then
        . /etc/os-release
        CODENAME="${VERSION_CODENAME:-}"
    fi
    if [ -z "${CODENAME}" ] && [ -r /etc/debian_version ]; then
        case "$(cat /etc/debian_version)" in
            8.*|jessie/*)   CODENAME="jessie" ;;
            9.*|stretch/*)  CODENAME="stretch" ;;
            10.*|buster/*)  CODENAME="buster" ;;
            11.*|bullseye/*) CODENAME="bullseye" ;;
            12.*|bookworm/*) CODENAME="bookworm" ;;
        esac
    fi
    if [ -z "${CODENAME}" ] && [ -r /etc/apt/sources.list ]; then
        for kw in jessie stretch buster bullseye bookworm; do
            if grep -q "\\b${kw}\\b" /etc/apt/sources.list 2>/dev/null; then
                CODENAME="$kw"
                break
            fi
        done
    fi
    log "Debian codename detected: ${CODENAME:-unknown}"

    # Drop our own sources list pointing at archive.debian.org (serves EOL).
    # Keep UR's factory sources.list untouched — we ADD a second source so
    # x11vnc becomes available without clobbering whatever UR put there.
    # If codename detection failed, try stretch first (most UR e-Series),
    # then fall through to buster.
    mkdir -p /etc/apt/sources.list.d /etc/apt/apt.conf.d
    echo 'Acquire::Check-Valid-Until "false";' \
        > /etc/apt/apt.conf.d/99-urcap-vnc-archive

    # v3.12.6 — use [trusted=yes] on the new archive source. For very old
    # dists (Jessie / Wheezy) the InRelease file doesn't exist on
    # archive.debian.org and the GPG keys in the factory keyring have
    # expired. Without trusted=yes, apt refuses to fetch the package
    # indexes. With trusted=yes, apt skips signature verification for
    # this one source — safe enough: the whole point of the relay is
    # that traffic rides an outbound TLS tunnel, the apt archive is
    # HTTP but pinned to a specific Debian mirror.
    #
    # Also drop debian-security for jessie — archive.debian.org never
    # carried a complete jessie-security set and apt errors on Err
    # InRelease there.
    case "${CODENAME}" in
        jessie)
            log "adding archive.debian.org jessie main to apt sources [trusted=yes]"
            cat > /etc/apt/sources.list.d/urcap-vnc-archive.list <<EOF
deb [trusted=yes] http://archive.debian.org/debian jessie main contrib
EOF
            ;;
        stretch|buster)
            log "adding archive.debian.org ${CODENAME} main + security to apt sources [trusted=yes]"
            cat > /etc/apt/sources.list.d/urcap-vnc-archive.list <<EOF
deb [trusted=yes] http://archive.debian.org/debian ${CODENAME} main contrib
deb [trusted=yes] http://archive.debian.org/debian-security ${CODENAME}/updates main contrib
EOF
            ;;
        bullseye|bookworm)
            # Still live mirrors; just make sure main is present. Don't
            # touch sources.list — the live dist should have x11vnc already.
            log "dist ${CODENAME} is live — no source rewrite, relying on existing mirrors"
            ;;
        *)
            log "codename still unknown — adding jessie/stretch/buster archive sources as blind fallback [trusted=yes]"
            cat > /etc/apt/sources.list.d/urcap-vnc-archive.list <<EOF
deb [trusted=yes] http://archive.debian.org/debian jessie main contrib
deb [trusted=yes] http://archive.debian.org/debian stretch main contrib
deb [trusted=yes] http://archive.debian.org/debian buster main contrib
EOF
            ;;
    esac

    # Also rewrite existing sources from EOL mirrors to archive — some UR
    # firmware has /etc/apt/sources.list entries that 404 on deb.debian.org.
    # This is additive with the new sources.list.d file above. Idempotent.
    #
    # v3.12.6 — use '#' as sed delimiter. '|' collides with the
    # alternation group (deb|security|...) and triggers
    #   sed: -e expression #1, char 26: unknown option to 's'
    # on BSD/busybox sed (which UR's image uses). '#' is not otherwise
    # used in apt source URLs so it's a safe delimiter here.
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list; do
        [ -f "$f" ] || continue
        [ "$(basename "$f")" = "urcap-vnc-archive.list" ] && continue
        [ ! -f "${f}.pre-urcap" ] && cp "$f" "${f}.pre-urcap" 2>/dev/null || true
        sed -i -E \
            -e 's#https?://(deb|security|ftp|httpredir)\.debian\.org#http://archive.debian.org#g' \
            -e '/deb-src/d' \
            "$f" 2>>"${DAEMON_LOG}" || true
    done
}

if ! command -v x11vnc >/dev/null 2>&1; then
    log "x11vnc not found; attempting install"
    export DEBIAN_FRONTEND=noninteractive

    # v3.12.7 — prefer bundled .deb files shipped inside the .urcap so UR
    # robots with throttled / restricted / archive-only apt paths don't
    # need to fetch ~1.7 MB over a 310 B/s LTE link. dpkg -i is orders of
    # magnitude faster (local file I/O) and has no GPG / mirror dance.
    BUNDLED_DEBS="${SCRIPT_SELF_DIR}/x11vnc-debs"
    if [ -d "${BUNDLED_DEBS}" ] && ls "${BUNDLED_DEBS}"/*.deb >/dev/null 2>&1; then
        log "installing bundled x11vnc .debs from ${BUNDLED_DEBS}"
        # Install libs before the x11vnc binary so dependency order is
        # satisfied on a best-effort basis. --force-confnew just picks
        # newer conffiles without prompting — x11vnc has no interactive
        # conffiles so this is essentially a no-op but keeps dpkg quiet.
        dpkg -i --force-confnew \
            "${BUNDLED_DEBS}"/libavahi-common3_*.deb \
            "${BUNDLED_DEBS}"/libavahi-client3_*.deb \
            "${BUNDLED_DEBS}"/libjpeg62-turbo_*.deb \
            "${BUNDLED_DEBS}"/libvncserver0_*.deb \
            "${BUNDLED_DEBS}"/libvncclient0_*.deb \
            "${BUNDLED_DEBS}"/x11vnc-data_*.deb \
            "${BUNDLED_DEBS}"/x11vnc_*.deb \
            2>&1 | tee -a "${DAEMON_LOG}" || true
        # Any already-installed-newer-version warnings are fine — check
        # for the final artefact instead of dpkg's exit code.
        if command -v x11vnc >/dev/null 2>&1; then
            log "bundled x11vnc installed successfully"
        else
            log "bundled dpkg left x11vnc still missing — falling through to apt path"
        fi
    fi
fi

if ! command -v x11vnc >/dev/null 2>&1; then
    log "falling back to apt-get install (bundled debs missing or partial)"
    export DEBIAN_FRONTEND=noninteractive

    fix_apt_sources

    # Run update + install and mirror their output into the daemon log so
    # the UI's Log tail carries the REAL apt error instead of just our
    # generic "failed" line.
    log "running: apt-get update"
    if ! apt-get update 2>&1 | tee -a "${DAEMON_LOG}"; then
        log "ERROR: apt-get update failed — see lines above in Log tail"
        log "Fallback: place x11vnc .deb at /root/x11vnc*.deb and run dpkg -i manually"
        exit 10
    fi
    log "running: apt-get install -y x11vnc"
    if ! apt-get install -y x11vnc 2>&1 | tee -a "${DAEMON_LOG}"; then
        log "ERROR: apt-get install x11vnc failed — see lines above in Log tail"
        log "Fallback: place x11vnc .deb at /root/x11vnc*.deb and run dpkg -i manually"
        exit 10
    fi
    log "x11vnc installed successfully"
fi

X11VNC_BIN="$(command -v x11vnc)"
X11VNC_VERSION="$(${X11VNC_BIN} -version 2>&1 | head -1 || echo 'unknown')"
log "using ${X11VNC_BIN} (${X11VNC_VERSION})"

#
# 2. Write / refresh VNC password (first 8 chars count — RFB truncation, G2)
#
mkdir -p "$(dirname "${VNC_PASSWORD_FILE}")"
if [ ! -s "${VNC_PASSWORD_FILE}" ] || [ "${VNC_PASSWORD_REFRESH:-false}" = "true" ]; then
    log "writing VNC password file (${VNC_PASSWORD_FILE})"
    # v3.12.19 — stdin path on x11vnc 0.9.13 produces a BROKEN encoding that
    # x11vnc itself then can't validate against client RFB DES challenges.
    # Reproduced on Stimba 2 (PolyScope 5.25.1, Debian 8 jessie). Manually:
    #
    #   printf '%s' "test1234" | x11vnc -storepasswd - /tmp/p_stdin
    #   x11vnc -storepasswd test1234 /tmp/p_arg
    #   xxd /tmp/p_stdin   # 92c2bee34d4330c2  <-- broken
    #   xxd /tmp/p_arg     # 7068506980 2a 5e92  <-- valid
    #
    # Direct RFB auth from a Python client (DES with bit-reversed key, per
    # RFB spec) succeeds against `_arg` and FAILS against `_stdin`. The
    # G7-era stdin pipe was the wrong fix — original positional-arg path
    # works and was always correct, the TTY concern was misdiagnosed.
    "${X11VNC_BIN}" -storepasswd "${VNC_PASSWORD}" "${VNC_PASSWORD_FILE}"
    chmod 600 "${VNC_PASSWORD_FILE}"
fi

#
# 3. Probe DISPLAY :0 and locate the owning Xauthority file.
#
# v3.12.2 — On Polyscope 5 e-Series the Xorg server for DISPLAY :0 is
# owned by the `ur` user (uid 1000). x11vnc running as root can't attach
# to that display without an -auth pointing at the owner's Xauthority
# cookie. `-auth guess` reads /proc/<xorg-pid>/environ to find
# XAUTHORITY, which silently fails on hardened /proc mounts (noticed on
# Polyscope 5.25.1.130388). Do our own walk through the likely paths.
#
# Stash the result in XAUTH_FILE — the arg block below picks it up;
# empty string means "fall back to -auth guess + hope for the best".
XAUTH_FILE=""
XORG_OWNER=""
if [ -r /tmp/.X0-lock ]; then
    XORG_OWNER="$(stat -c %U /tmp/.X0-lock 2>/dev/null || echo '')"
    [ -n "${XORG_OWNER}" ] && log "Xorg :0 owned by user '${XORG_OWNER}'"
fi

# v3.12.8 — owner-aware candidate ordering. UR e-Series Polyscope on
# Stimba 2 was observed (2026-04-25) running Xorg as root, so /root/...
# wins; on robots where Xorg runs as `ur`, /home/ur/... wins. The
# XORG_HOME helper resolves the owner's home directory robustly via
# getent (busybox-safe; `id -u name` is NOT portable on busybox and was
# triggering set -e exits in v3.12.2-7).
XORG_HOME=""
if [ -n "${XORG_OWNER}" ]; then
    if [ "${XORG_OWNER}" = "root" ]; then
        XORG_HOME="/root"
    else
        XORG_HOME="$(getent passwd "${XORG_OWNER}" 2>/dev/null | cut -d: -f6 || true)"
        [ -z "${XORG_HOME}" ] && XORG_HOME="/home/${XORG_OWNER}"
    fi
    log "Xorg owner home: ${XORG_HOME}"
fi

# Path candidates in priority order. First readable one wins.
for candidate in \
        "${XORG_HOME:+${XORG_HOME}/.Xauthority}" \
        /root/.Xauthority \
        /home/ur/.Xauthority \
        /home/polyscope/.Xauthority \
        /var/lib/lightdm/.Xauthority \
        /tmp/.X0-auth ; do
    [ -z "${candidate}" ] && continue
    if [ -r "${candidate}" ]; then
        XAUTH_FILE="${candidate}"
        log "Xauthority located: ${XAUTH_FILE}"
        break
    fi
done

# Last resort — scan /tmp for a per-session xauth cookie created by the
# Xorg launcher (e.g. /tmp/serverauth.ABC123 or /tmp/.xauth-XXXXX).
# v3.12.8 — fix `done` -> `fi` typo (was syntax error inside `if [ -z
# XAUTH_FILE ]` block). On the UR busybox bash this exited the script
# with non-zero on parse; combined with set -e and Polyscope daemon
# supervisor restart, the daemon went into a tight respawn loop after
# the "Xorg :0 owned by ..." line. Hours of head-scratching solved by
# one keyword.
if [ -z "${XAUTH_FILE}" ]; then
    FOUND="$(find /tmp -maxdepth 2 \( -name 'serverauth.*' -o -name '.xauth*' -o -name '.Xauth*' \) -readable 2>/dev/null | head -1)"
    if [ -n "${FOUND}" ]; then
        XAUTH_FILE="${FOUND}"
        log "Xauthority located via /tmp scan: ${XAUTH_FILE}"
    fi
fi

# v3.12.18 — explicit HOME export. runsv invokes us with a near-empty env
# (no HOME). The xauth tool internally calls getpwuid()/getenv("HOME") to
# choose where to write the cookie file; with no HOME it prints
#   "xauth: unable to generate an authority file name"
# and exits non-zero. x11vnc then loses access to DISPLAY :0 and silently
# exits, runsv restarts the daemon, repeat ad nauseam (Stimba 2 robot saw
# 60 856 respawn cycles in ~10 hours per the diag bundle 2026-05-01).
# Setting HOME up-front lets xauth + x11vnc agree on the cookie location.
export HOME="${XORG_HOME:-/root}"

# v3.12.18 — bootstrap a writable Xauthority when none was discovered.
# On UR e-Series PolyScope 5.25.1 (kernel 4.9.65-rt57ur, Debian 8 jessie)
# the X server :0 runs as root with no /root/.Xauthority on disk, only the
# in-memory cookie. `-auth guess` falls back to `xauth generate` which
# fails as above. We pre-touch the file so xauth has somewhere to write
# AND we ask xauth to extract the running cookie via `xauth -f <file>
# generate :0 . trusted` (best-effort — silent if X server doesn't
# co-operate, but x11vnc's `-find`/`-auth guess` path will still work
# with the writable file present).
if [ -z "${XAUTH_FILE}" ]; then
    XAUTH_FILE="${HOME}/.Xauthority"
    if [ ! -e "${XAUTH_FILE}" ]; then
        : > "${XAUTH_FILE}" 2>/dev/null || true
        chmod 600 "${XAUTH_FILE}" 2>/dev/null || true
    fi
    # Try to populate the file with a valid cookie. Best-effort: many UR
    # setups will have an X server that auto-trusts local root anyway, in
    # which case the empty Xauthority + `-auth file` is enough to unblock
    # x11vnc.
    if command -v xauth >/dev/null 2>&1; then
        xauth -f "${XAUTH_FILE}" generate "${DISPLAY}" . trusted >/dev/null 2>&1 \
            || xauth -f "${XAUTH_FILE}" add "${DISPLAY}" . "$(dd if=/dev/urandom bs=16 count=1 2>/dev/null | hexdump -e '16/1 "%02x"')" >/dev/null 2>&1 \
            || true
    fi
    log "Xauthority bootstrapped at ${XAUTH_FILE} (HOME=${HOME})"
fi

export XAUTHORITY="${XAUTH_FILE}"

# Now probe with the best auth we've got.
if ! DISPLAY="${DISPLAY}" xdpyinfo -auth "${XAUTH_FILE}" >/dev/null 2>&1; then
    log "WARNING: xdpyinfo could not probe DISPLAY=${DISPLAY} (auth=${XAUTH_FILE})"
    log "         continuing — x11vnc has its own -findauth path as a fallback"
fi

#
# 4. iptables whitelist — allow IXROUTER_IP, drop everyone else on VNC_PORT
#
if command -v iptables >/dev/null 2>&1; then
    log "installing iptables whitelist: ACCEPT ${IXROUTER_IP} -> tcp/${VNC_PORT}, DROP others"

    # v3.12.18 — `iptables -C` check is broken on UR e-Series PolyScope's
    # busybox iptables 1.4.21 (Debian 8 jessie). Even after a rule is
    # successfully inserted, the -C lookup returns non-zero ("No such
    # rule" / "Bad rule"). Result: every run-vnc.sh restart appends a
    # duplicate ACCEPT rule. The Stimba 2 diag bundle showed >100 000
    # duplicate ACCEPT entries from the v3.12.13–17 respawn loop. We
    # parse `iptables-save` instead — that returns canonical rule
    # strings we can match exactly with grep -F.
    iptables_rule_exists() {
        # args: chain spec... → return 0 if a matching -A line is in iptables-save
        local needle
        needle="-A $*"
        iptables-save 2>/dev/null | grep -qxF "${needle}"
    }

    # ACCEPT from IXrouter (idempotent — survives the 1.4.21 -C bug)
    if ! iptables_rule_exists "INPUT -s ${IXROUTER_IP}/32 -p tcp -m tcp --dport ${VNC_PORT} -j ACCEPT"; then
        if ! iptables -I INPUT 1 -p tcp --dport "${VNC_PORT}" -s "${IXROUTER_IP}" -j ACCEPT; then
            log "ERROR: iptables INSERT ACCEPT failed (EUID=$(id -u)?)"
            exit 13
        fi
    fi

    # Always accept loopback (local vncviewer diagnostics + URCap relay tunnel
    # connecting to 127.0.0.1:${VNC_PORT})
    if ! iptables_rule_exists "INPUT -s 127.0.0.1/32 -p tcp -m tcp --dport ${VNC_PORT} -j ACCEPT"; then
        iptables -I INPUT 1 -p tcp --dport "${VNC_PORT}" -s 127.0.0.1 -j ACCEPT || true
    fi

    # DROP everything else (last resort; anything not matched by the
    # ACCEPTs above gets dropped on this port).
    if ! iptables_rule_exists "INPUT -p tcp -m tcp --dport ${VNC_PORT} -j DROP"; then
        if ! iptables -A INPUT -p tcp --dport "${VNC_PORT}" -j DROP; then
            log "WARNING: iptables APPEND DROP failed; port ${VNC_PORT} may be open to LAN"
        fi
    fi
else
    log "WARNING: iptables not found; skipping whitelist. Port ${VNC_PORT} is wide-open on LAN!"
fi

#
# 4b. TLS bootstrap (v3.0.0 C1 — see ADR-008)
#
# x11vnc's `-ssl SAVE` loads (or lazily generates) /root/.vnc/certs/server.pem.
# We want deterministic generation + fingerprint publication, so we run
# tls-bootstrap.sh ourselves instead of letting x11vnc auto-gen silently.
# Fingerprint file is exposed to the UI so the operator can pin it in
# their VNC viewer's trust store before first connect.
#
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TLS_BOOTSTRAP="${SCRIPT_DIR}/tls-bootstrap.sh"
TLS_CERT="/root/.vnc/certs/server.pem"

if [ "${TLS_ENABLED}" = "1" ]; then
    if [ -x "${TLS_BOOTSTRAP}" ]; then
        if ! "${TLS_BOOTSTRAP}"; then
            log "ERROR: tls-bootstrap.sh failed — refusing to start (TLS was requested)"
            exit 16
        fi
    else
        log "WARN: TLS_ENABLED=1 but ${TLS_BOOTSTRAP} not executable; falling through to plaintext"
        TLS_ENABLED=0
    fi
fi

#
# 5. Build x11vnc arguments
#
# v2.2.0: -accept / -gone fire audit hook per client connect/disconnect.
# x11vnc exports RFB_CLIENT_IP / RFB_CLIENT_COUNT / RFB_CONNECT_SEC into
# the hook's environment; vnc-audit-hook.sh writes one JSON-line per event
# into /var/log/urcap-vnc-audit.log.  See ADR-007.
#
# v3.0.0 adds: -ssl, plus an idle-watcher that invokes `x11vnc -R disconnect
# all` when the pointer has been stationary for IDLE_TIMEOUT_MIN minutes.
# v3.12.15 fixes broken `-connect_or_exit "1"` which mis-used x11vnc's
# reverse-VNC flag and caused x11vnc to exit at startup → respawn loop.
#
AUDIT_HOOK="${SCRIPT_DIR}/vnc-audit-hook.sh"
IDLE_WATCHER="${SCRIPT_DIR}/idle-watcher.sh"

ARGS=(
    "-display"   "${DISPLAY}"
    "-rfbauth"   "${VNC_PASSWORD_FILE}"
    "-rfbport"   "${VNC_PORT}"
    "-forever"
    "-noxdamage"
    "-nopw"
    "-logappend" "/var/log/urcap-vnc.log"
    "-tag"       "urcap-vnc"
)

# v3.12.2 — prefer the Xauthority file we located in step 3 over x11vnc's
# own `guess` heuristic (which fails silently on hardened /proc mounts).
# If we didn't find a file, keep `-auth guess` and add `-findauth` so
# x11vnc scans /tmp and /home for a cookie itself.
if [ -n "${XAUTH_FILE}" ]; then
    ARGS+=( "-auth" "${XAUTH_FILE}" )
else
    ARGS+=( "-auth" "guess" "-findauth" )
fi

# --- Max clients (C4) -------------------------------------------------------
# v3.12.15 — fix: original code used `-connect_or_exit "1"` thinking it caps
# clients at 1. That flag is for REVERSE VNC (string is a hostname). With "1"
# x11vnc would try to reverse-connect to host literally named "1", fail, and
# (per `_or_exit` semantics) shut down immediately — hence the daemon respawn
# loop and `127.0.0.1:5900` ConnectException seen by the relay tunnel client.
# Same bug in the else branch with `-connect "${MAX_CLIENTS}"`.
#
# x11vnc has no native "max N concurrent clients" cap. `-nevershared` already
# enforces single-client mode (incoming viewer disconnects the existing one).
# `-shared` allows multiple concurrent viewers. That is the closest match to
# the original intent without the bogus reverse-connect side-effect.
if [ "${MAX_CLIENTS}" -eq 1 ]; then
    ARGS+=( "-nevershared" )
else
    ARGS+=( "-shared" )
fi
log "max-clients policy: ${MAX_CLIENTS} (mode=$([ "${MAX_CLIENTS}" -eq 1 ] && echo single || echo shared))"

# --- TLS (C1) --------------------------------------------------------------
if [ "${TLS_ENABLED}" = "1" ] && [ -s "${TLS_CERT}" ]; then
    ARGS+=( "-ssl" "${TLS_CERT}" )
    log "TLS enabled; cert=${TLS_CERT}"
else
    log "TLS disabled — wire is plaintext (OK for LAN + IXON tunnel, NOT for public)"
fi

# Audit hooks — only wire up if script exists + is executable.  Missing
# hook must not break x11vnc start, so we degrade gracefully.
if [ -x "${AUDIT_HOOK}" ]; then
    ARGS+=(
        "-accept" "${AUDIT_HOOK} connect"
        "-gone"   "${AUDIT_HOOK} disconnect"
    )
    log "audit hooks wired: ${AUDIT_HOOK}"
else
    log "WARN: audit hook missing at ${AUDIT_HOOK}; connect/disconnect events will not be logged"
fi

if [ "${VNC_VIEW_ONLY}" = "true" ]; then
    ARGS+=("-viewonly")
fi

log "starting: ${X11VNC_BIN} ${ARGS[*]}"

# v3.12.2 — capture x11vnc stderr into the log file as well. x11vnc's
# -logappend writes its own log, but FATAL pre-init errors (can't
# connect to display, bad auth, port taken) print to stderr before the
# logger is set up. Without this redirect those errors are lost and
# daemon goes to ERROR with no trace.
X11VNC_STDERR_LOG="/var/log/urcap-vnc.log"

#
# 6. Fork idle-watcher sibling (v3.0.0 C2) — it will self-terminate when
#    x11vnc dies.  We grab the x11vnc PID *after* exec via a tiny shim:
#    exec replaces us, so we use "exec" style only when no watcher is needed;
#    otherwise we run x11vnc in the foreground and relay signals.
#
if [ "${IDLE_TIMEOUT_MIN}" -gt 0 ] && [ -x "${IDLE_WATCHER}" ]; then
    "${X11VNC_BIN}" "${ARGS[@]}" 2>>"${X11VNC_STDERR_LOG}" &
    X11VNC_PID=$!
    log "started x11vnc pid=${X11VNC_PID}; spawning idle-watcher (${IDLE_TIMEOUT_MIN}min)"
    "${IDLE_WATCHER}" "${IDLE_TIMEOUT_MIN}" "${X11VNC_PID}" "${DISPLAY}" &
    WATCHER_PID=$!
    trap 'kill ${WATCHER_PID} 2>/dev/null; kill ${X11VNC_PID} 2>/dev/null; rm -f "${LOCK_FILE}"' EXIT INT TERM
    wait "${X11VNC_PID}"
    RC=$?
    kill "${WATCHER_PID}" 2>/dev/null || true
    exit "${RC}"
else
    # No watcher → exec replacement so Polyscope daemon supervision tracks
    # the real PID. Redirect stderr so pre-init x11vnc errors reach the log.
    exec "${X11VNC_BIN}" "${ARGS[@]}" 2>>"${X11VNC_STDERR_LOG}"
fi
