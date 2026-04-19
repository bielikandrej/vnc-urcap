#!/bin/bash
#
# STIMBA VNC Server daemon — clean stop script (URCap 2.1.0)
# Invoked by Polyscope's URCap daemon lifecycle on shutdown.
#
# Tears down the iptables whitelist rules installed by run-vnc.sh
# so the firewall table doesn't accumulate stale entries across restarts.
#
# Sources config in the same priority order as run-vnc.sh to ensure the
# IXROUTER_IP used for -D (delete) matches the one used for -I (insert).
#
set -eu

LOCK_FILE="/tmp/urcap-vnc.lock"

# --- Source config (same order as run-vnc.sh) ------------------------------
CONFIG_FILE_NEW="/var/lib/urcap-vnc/config"
CONFIG_FILE_LEGACY="/root/.urcap-vnc.conf"
if [ -r "${CONFIG_FILE_NEW}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_NEW}"
elif [ -r "${CONFIG_FILE_LEGACY}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_LEGACY}"
fi

VNC_PORT="${VNC_PORT:-5900}"
# Default matches run-vnc.sh (verified Apr 2026 STIMBA fleet default).
IXROUTER_IP="${IXROUTER_IP:-192.168.0.100}"
CUSTOMER_LABEL="${CUSTOMER_LABEL:-}"
if [ -n "${CUSTOMER_LABEL}" ]; then
    LOG_TAG="urcap-vnc[${CUSTOMER_LABEL}]"
else
    LOG_TAG="urcap-vnc"
fi

log() { logger -t "${LOG_TAG}" "$*"; echo "[${LOG_TAG}] $*"; }

if [ -e "${LOCK_FILE}" ]; then
    PID="$(cat "${LOCK_FILE}")"
    log "stopping x11vnc PID=${PID}"
    kill "${PID}" 2>/dev/null || true
    sleep 1
    kill -9 "${PID}" 2>/dev/null || true
    rm -f "${LOCK_FILE}"
fi

# belt-and-braces: any leftover x11vnc bound to our tag
pkill -f 'x11vnc.*-tag urcap-vnc' 2>/dev/null || true

# Tear down iptables whitelist. Loop -D until it returns non-zero (rule gone).
if command -v iptables >/dev/null 2>&1; then
    log "removing iptables whitelist for tcp/${VNC_PORT}"
    while iptables -D INPUT -p tcp --dport "${VNC_PORT}" -s "${IXROUTER_IP}" -j ACCEPT 2>/dev/null; do :; done
    while iptables -D INPUT -p tcp --dport "${VNC_PORT}" -s 127.0.0.1 -j ACCEPT 2>/dev/null; do :; done
    while iptables -D INPUT -p tcp --dport "${VNC_PORT}" -j DROP 2>/dev/null; do :; done
fi

log "stop complete"
exit 0
