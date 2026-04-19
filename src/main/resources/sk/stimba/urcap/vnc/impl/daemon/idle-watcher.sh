#!/bin/bash
#
# STIMBA VNC Server — idle-watcher (URCap 3.0.0, C2)
#
# Kicks connected VNC clients off after $IDLE_TIMEOUT_MIN minutes of no
# pointer movement.  Runs as a background loop sibling to x11vnc, spawned
# by run-vnc.sh.  Terminates automatically when x11vnc dies (parent-death
# detection via `kill -0 $X11VNC_PID`).
#
# Why not just x11vnc's own `-timeout`?
# - `-timeout` fires when NO client is connected.  We want the opposite:
#   client connected but not interacting (forgot to close their RealVNC
#   window, walked off for lunch, etc.).
#
# Strategy
# --------
# Poll `xdotool getmouselocation` every 60 s on DISPLAY :0.  If the
# (x,y) tuple is unchanged for $IDLE_TIMEOUT_MIN consecutive minutes AND
# at least one client is connected (via `x11vnc -Q clients`), we send
# `-R disconnect all` over x11vnc's remote control channel.
#
# Fallback if xdotool missing: poll `x11vnc -Q pointer_pos`.
# Fallback if neither works: log + bail — we don't want to falsely kick.
#
# Args
#   $1 = IDLE_TIMEOUT_MIN       (required, integer >=1)
#   $2 = X11VNC_PID             (required, watch for death)
#   $3 = DISPLAY                (optional, default :0)
#
# Exit codes
#   0 - x11vnc exited, we exit cleanly
#   1 - bad args
#   2 - no way to probe pointer (xdotool + x11vnc -Q both missing/failed)
#
set -u

IDLE_MIN="${1:-0}"
X11VNC_PID="${2:-}"
DISPLAY_PROBE="${3:-:0}"
export DISPLAY="${DISPLAY_PROBE}"

LOG_TAG="urcap-vnc-idle"
log() { logger -t "${LOG_TAG}" "$*"; echo "[${LOG_TAG}] $*"; }

if ! [[ "${IDLE_MIN}" =~ ^[0-9]+$ ]] || [ "${IDLE_MIN}" -lt 1 ]; then
    log "bad IDLE_TIMEOUT_MIN=${IDLE_MIN}; exiting"
    exit 1
fi
if [ -z "${X11VNC_PID}" ] || ! kill -0 "${X11VNC_PID}" 2>/dev/null; then
    log "x11vnc pid ${X11VNC_PID} not alive; exiting"
    exit 1
fi

POLL_SEC=60
IDLE_LIMIT_POLLS="${IDLE_MIN}"   # IDLE_MIN minutes × 1 poll/minute

# Choose a pointer-probe strategy at startup
PROBE=""
if command -v xdotool >/dev/null 2>&1; then
    PROBE="xdotool"
elif command -v x11vnc >/dev/null 2>&1; then
    # `-Q pointer_pos` is supported since x11vnc 0.9.13 (2011+) — UR images have it
    PROBE="x11vnc"
else
    log "no pointer probe available (xdotool + x11vnc both missing) — aborting"
    exit 2
fi
log "started: timeout=${IDLE_MIN}min pid=${X11VNC_PID} probe=${PROBE} display=${DISPLAY_PROBE}"

pointer_xy() {
    case "${PROBE}" in
        xdotool)
            # "x:450 y:320 screen:0 window:…" → "450,320"
            xdotool getmouselocation 2>/dev/null | awk '{print $1","$2}' || echo "?,?"
            ;;
        x11vnc)
            # x11vnc -Q pointer_pos → "ans=pointer_pos:450,320"
            x11vnc -display "${DISPLAY_PROBE}" -Q pointer_pos 2>/dev/null \
                | awk -F: '/pointer_pos/{print $NF}' \
                | tr -d ' ' \
                || echo "?,?"
            ;;
    esac
}

client_count() {
    # `-Q clients` emits "ans=clients: ip1 ip2 ..." — count whitespace-separated words
    local c
    c="$(x11vnc -display "${DISPLAY_PROBE}" -Q clients 2>/dev/null \
         | awk -F: '/clients/{print $NF}')"
    echo "${c}" | awk '{print NF}'
}

kick_all_clients() {
    # x11vnc's remote control sends FIN to every client socket
    if x11vnc -display "${DISPLAY_PROBE}" -R "disconnect all" >/dev/null 2>&1; then
        log "kicked all clients after ${IDLE_MIN}min pointer-idle"
    else
        log "WARN: failed to disconnect via -R; falling back to SIGUSR1 on x11vnc"
        kill -USR1 "${X11VNC_PID}" 2>/dev/null || true
    fi
}

LAST_XY="$(pointer_xy)"
IDLE_POLLS=0

while kill -0 "${X11VNC_PID}" 2>/dev/null; do
    sleep "${POLL_SEC}"
    CUR_XY="$(pointer_xy)"
    CNT="$(client_count)"
    if [ "${CUR_XY}" = "${LAST_XY}" ] && [ "${CNT:-0}" -gt 0 ]; then
        IDLE_POLLS=$((IDLE_POLLS + 1))
        log "pointer idle ${IDLE_POLLS}/${IDLE_LIMIT_POLLS}min (xy=${CUR_XY} clients=${CNT})"
        if [ "${IDLE_POLLS}" -ge "${IDLE_LIMIT_POLLS}" ]; then
            kick_all_clients
            IDLE_POLLS=0     # reset, don't hammer
        fi
    else
        IDLE_POLLS=0
        LAST_XY="${CUR_XY}"
    fi
done

log "x11vnc pid ${X11VNC_PID} exited — watcher exiting"
exit 0
