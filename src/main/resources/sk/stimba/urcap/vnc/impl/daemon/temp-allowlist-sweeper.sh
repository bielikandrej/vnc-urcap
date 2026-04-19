#!/bin/bash
#
# STIMBA VNC Server — temp allowlist sweeper (URCap 2.2.0)
#
# Runs from cron every minute.  Walks /var/log/urcap-vnc-temp-allowlist and
# removes expired iptables ACCEPT rules.  Idempotent — double-deletes are
# silently OK.
#
# See temp-allowlist-add.sh for the write side.
#
set -eu

ALLOW_FILE="/var/log/urcap-vnc-temp-allowlist"

# Port from same priority chain as add script
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

[ -r "${ALLOW_FILE}" ] || exit 0

NOW="$(date +%s)"
TMP="$(mktemp)"

(
    if command -v flock >/dev/null 2>&1; then
        flock -x 202
    fi
    while IFS=$'\t' read -r expiry ip comment; do
        # Skip blanks / malformed rows
        if [ -z "${expiry:-}" ] || [ -z "${ip:-}" ]; then
            continue
        fi
        if [ "${expiry}" -le "${NOW}" ]; then
            # expired → remove rule (may be already gone → ignore rc)
            iptables -D INPUT -p tcp --dport "${VNC_PORT}" -s "${ip}" -j ACCEPT 2>/dev/null || true
            logger -t "urcap-vnc-allowlist" "expired/removed ip=${ip} comment=${comment:-}"
        else
            # still valid → keep
            printf '%s\t%s\t%s\n' "${expiry}" "${ip}" "${comment:-}" >> "${TMP}"
        fi
    done < "${ALLOW_FILE}"
    # Atomic swap — even if file is being read, mv replaces atomically
    mv "${TMP}" "${ALLOW_FILE}"
    chmod 640 "${ALLOW_FILE}" 2>/dev/null || true
) 202>>"${ALLOW_FILE}"

exit 0
