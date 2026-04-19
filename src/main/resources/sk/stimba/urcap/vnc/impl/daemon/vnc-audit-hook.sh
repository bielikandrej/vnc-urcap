#!/bin/bash
#
# STIMBA VNC Server — audit hook (URCap 2.2.0)
#
# Called by x11vnc via -accept / -gone.  Writes one JSON-line event to
# /var/log/urcap-vnc-audit.log per connect / disconnect so Andrej can answer
# "who VNC-ed into what robot, when, for how long" without SSH.
#
# x11vnc exports (see x11vnc(1) -accept docs):
#   RFB_CLIENT_IP        — peer IP address
#   RFB_CLIENT_COUNT     — clients currently attached (AFTER this event)
#   RFB_CONNECT_SEC      — connection duration in seconds (only on -gone)
#   RFB_CLIENT_PORT      — peer TCP port (optional)
#
# Arg $1 selects the event name written to the log: "connect" | "disconnect".
#
# Output is JSON-Lines — one object per line, no pretty-printing, atomic append.
# Any operator (Java UI, grep, jq, SIEM tail -F) can reliably parse this.
#
set -eu

EVENT="${1:-unknown}"
AUDIT_LOG="/var/log/urcap-vnc-audit.log"

CONFIG_FILE_NEW="/var/lib/urcap-vnc/config"
CONFIG_FILE_LEGACY="/root/.urcap-vnc.conf"
if [ -r "${CONFIG_FILE_NEW}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_NEW}"
elif [ -r "${CONFIG_FILE_LEGACY}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_LEGACY}"
fi
CUSTOMER_LABEL="${CUSTOMER_LABEL:-}"

# ISO 8601 with timezone offset, second precision.
TS="$(date --iso-8601=seconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z)"
IP="${RFB_CLIENT_IP:-unknown}"
COUNT="${RFB_CLIENT_COUNT:-0}"

# Duration only makes sense on "disconnect"; emit null otherwise.
if [ "${EVENT}" = "disconnect" ] && [ -n "${RFB_CONNECT_SEC:-}" ]; then
    DURATION_JSON="${RFB_CONNECT_SEC}"
else
    DURATION_JSON="null"
fi

# Minimal JSON escape for values that go in strings (backslash + double-quote).
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

LINE="$(printf '{"ts":"%s","event":"%s","ip":"%s","count":%s,"duration_s":%s,"customer":"%s"}' \
    "$(esc "${TS}")" \
    "$(esc "${EVENT}")" \
    "$(esc "${IP}")" \
    "${COUNT}" \
    "${DURATION_JSON}" \
    "$(esc "${CUSTOMER_LABEL}")")"

# Append atomically; flock prevents interleaved partial writes when x11vnc
# fires two hooks within the same millisecond (rare but possible).
(
    if command -v flock >/dev/null 2>&1; then
        flock -x 200
    fi
    printf '%s\n' "${LINE}" >> "${AUDIT_LOG}"
) 200>>"${AUDIT_LOG}"

exit 0
