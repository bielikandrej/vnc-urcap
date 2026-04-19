#!/bin/bash
#
# STIMBA VNC Server — RFB handshake probe (URCap 2.2.0)
#
# One-shot local test: does x11vnc respond with an RFB banner on tcp/$VNC_PORT?
# Output: single-line JSON for Java UI consumption.
#
# Exit codes:
#   0 = RFB banner received (status=ok)
#   1 = connect refused (status=fail, error=connect_refused)
#   2 = connected but no RFB banner in 3s (status=fail, error=no_banner)
#
set -u

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

# Use bash /dev/tcp; falls back to Python if bash's is disabled (rare).
PROTO=""
if exec 3<>/dev/tcp/127.0.0.1/"${VNC_PORT}" 2>/dev/null; then
    # Read up to 12 bytes ("RFB 003.008\n"). 3s timeout.
    if read -r -t 3 -u 3 PROTO; then
        exec 3>&-
        # x11vnc banner looks like "RFB 003.008"
        if printf '%s' "${PROTO}" | grep -Eq '^RFB [0-9]{3}\.[0-9]{3}'; then
            # JSON-escape: banner has no quotes/backslashes, safe to inline
            printf '{"status":"ok","protocol":"%s","port":%s}\n' "${PROTO}" "${VNC_PORT}"
            exit 0
        fi
    fi
    exec 3>&- 2>/dev/null || true
    printf '{"status":"fail","error":"no_banner","port":%s}\n' "${VNC_PORT}"
    exit 2
else
    printf '{"status":"fail","error":"connect_refused","port":%s}\n' "${VNC_PORT}"
    exit 1
fi
