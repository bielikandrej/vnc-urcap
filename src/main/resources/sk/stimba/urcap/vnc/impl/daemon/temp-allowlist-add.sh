#!/bin/bash
#
# STIMBA VNC Server — temporary allowlist add (URCap 2.2.0)
#
# Usage:  temp-allowlist-add.sh <IP> <TTL_SECONDS> <COMMENT>
#
# Adds a one-off iptables ACCEPT rule for <IP> on tcp/$VNC_PORT and records
# the expiry timestamp in /var/log/urcap-vnc-temp-allowlist.  The sweeper
# (crontab minutely) removes it when it expires.
#
# Designed for "vendor on a call, needs 15 min to look at the HMI" scenarios.
# Caller is the Java UI which has already validated IP / TTL / comment.
#
set -eu

IP="${1:?usage: temp-allowlist-add.sh <IP> <TTL_SEC> <COMMENT>}"
TTL="${2:?missing TTL seconds}"
COMMENT="${3:-}"

ALLOW_FILE="/var/log/urcap-vnc-temp-allowlist"
LOCK_FILE="/var/lock/urcap-vnc-allowlist.lock"

# Source port from same priority chain as run-vnc.sh
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

# Basic sanity: IPv4 dotted quad, TTL digits only.
if ! printf '%s' "${IP}" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
    echo "[temp-allowlist-add] invalid IPv4: ${IP}" >&2; exit 2
fi
if ! printf '%s' "${TTL}" | grep -Eq '^[0-9]+$'; then
    echo "[temp-allowlist-add] invalid TTL: ${TTL}" >&2; exit 2
fi

NOW="$(date +%s)"
EXPIRY=$(( NOW + TTL ))

# flock-serialize concurrent UI + sweeper edits
(
    if command -v flock >/dev/null 2>&1; then
        flock -x 201
    fi
    touch "${ALLOW_FILE}"
    chmod 640 "${ALLOW_FILE}" 2>/dev/null || true
    # Insert BEFORE the default DROP so this ACCEPT actually wins the race.
    iptables -I INPUT 1 -p tcp --dport "${VNC_PORT}" -s "${IP}" -j ACCEPT 2>/dev/null || {
        echo "[temp-allowlist-add] iptables -I failed (not root? no iptables?)" >&2
        exit 3
    }
    printf '%d\t%s\t%s\n' "${EXPIRY}" "${IP}" "${COMMENT}" >> "${ALLOW_FILE}"
    logger -t "urcap-vnc-allowlist" "temp ACCEPT ip=${IP} ttl=${TTL}s expires=$(date -d "@${EXPIRY}" '+%Y-%m-%d %H:%M:%S') comment=${COMMENT}"
) 201>>"${ALLOW_FILE}"

printf '{"status":"ok","ip":"%s","expires":%d,"ttl_s":%s}\n' "${IP}" "${EXPIRY}" "${TTL}"
exit 0
