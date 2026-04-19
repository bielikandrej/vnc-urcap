#!/bin/bash
#
# STIMBA VNC Server — diagnostic bundle (URCap 2.2.0)
#
# Packs everything you'd ask Andrej to grab via SSH into one .tar.gz so the
# operator can email/Gong-share it and Andrej can reproduce the fleet state
# locally.
#
# Output: /root/urcap-vnc-diag-YYYYMMDD-HHMMSS.tar.gz
#
# Called from the Java UI via "Exportovať diagnostiku" button.  Exits 0 on
# success.  stdout: final .tar.gz path.  stderr: progress log.
#
# SENSITIVE INFO REDACTION:
# Passwords (run-vnc.sh may log VNC_PASSWORD echoes during debugging) are
# scrubbed in-memory before archive.  See R1 in wiki/sprints/sprint-2.
#
set -eu

TS="$(date +%Y%m%d-%H%M%S)"
STAGE="$(mktemp -d /tmp/urcap-vnc-diag.XXXXXX)"
OUT="/root/urcap-vnc-diag-${TS}.tar.gz"

log() { printf '[diag-bundle] %s\n' "$*" >&2; }
trap 'rm -rf "${STAGE}"' EXIT

log "stage dir: ${STAGE}"

# --- 1. logs (redacted) -----------------------------------------------------
mkdir -p "${STAGE}/logs"
for f in /var/log/urcap-vnc.log /var/log/urcap-vnc-audit.log; do
    if [ -r "${f}" ]; then
        # Redact VNC_PASSWORD / password= / passwd= occurrences.
        sed -E 's/(VNC_PASSWORD|password|passwd|PASS)[[:space:]]*[:=][[:space:]]*[^[:space:]"]+/\1=***REDACTED***/gi' \
            "${f}" > "${STAGE}/logs/$(basename "${f}")" 2>/dev/null || true
    fi
done

# --- 2. config (password redacted) ------------------------------------------
mkdir -p "${STAGE}/config"
for f in /var/lib/urcap-vnc/config /root/.urcap-vnc.conf; do
    if [ -r "${f}" ]; then
        sed -E 's/(VNC_PASSWORD)=.*/\1=***REDACTED***/g' \
            "${f}" > "${STAGE}/config/$(basename "${f}").redacted" 2>/dev/null || true
    fi
done

# --- 3. firewall state ------------------------------------------------------
mkdir -p "${STAGE}/firewall"
iptables-save 2>/dev/null > "${STAGE}/firewall/iptables-save.txt" || \
    echo "iptables-save failed (not root?)" > "${STAGE}/firewall/iptables-save.txt"
iptables -L INPUT -n --line-numbers 2>/dev/null > "${STAGE}/firewall/iptables-L-INPUT.txt" || true

# --- 4. process + socket snapshot -------------------------------------------
mkdir -p "${STAGE}/runtime"
ps -efww 2>/dev/null > "${STAGE}/runtime/ps-efww.txt" || true
ss -tlnp 2>/dev/null > "${STAGE}/runtime/ss-tlnp.txt" || \
    netstat -tlnp 2>/dev/null > "${STAGE}/runtime/ss-tlnp.txt" || true

# --- 5. live health probe ---------------------------------------------------
HP="$(dirname "$0")/health-probe.sh"
if [ -x "${HP}" ]; then
    "${HP}" > "${STAGE}/runtime/health-probe.json" 2>&1 || true
fi

# --- 6. URCap version + host info -------------------------------------------
mkdir -p "${STAGE}/meta"
{
    echo "=== URCap manifest ==="
    find /root/.urcaps -name MANIFEST.MF 2>/dev/null | head -5 | while read -r m; do
        echo "--- ${m} ---"
        cat "${m}" 2>/dev/null || true
    done
    echo
    echo "=== uname -a ==="
    uname -a
    echo
    echo "=== /etc/os-release ==="
    cat /etc/os-release 2>/dev/null || echo "n/a"
    echo
    echo "=== date ==="
    date
    echo "UTC: $(date -u)"
    echo
    echo "=== uptime ==="
    uptime
    echo
    echo "=== DISPLAY + Xauthority ==="
    echo "DISPLAY=${DISPLAY:-(unset)}"
    ls -la /root/.Xauthority 2>/dev/null || echo "no /root/.Xauthority"
    echo
    echo "=== /var/lib/urcap-vnc listing ==="
    ls -la /var/lib/urcap-vnc 2>/dev/null || echo "dir missing"
    ls -la /var/lib/urcap-vnc/sessions 2>/dev/null || true
} > "${STAGE}/meta/host-info.txt" 2>&1

# --- 7. temp allowlist snapshot ---------------------------------------------
if [ -r /var/log/urcap-vnc-temp-allowlist ]; then
    cp /var/log/urcap-vnc-temp-allowlist "${STAGE}/runtime/temp-allowlist.txt"
fi

# --- pack -------------------------------------------------------------------
tar -C "${STAGE}" -czf "${OUT}" . 2>/dev/null
chmod 600 "${OUT}"

log "wrote ${OUT} ($(stat -c%s "${OUT}" 2>/dev/null || echo '?') bytes)"
printf '%s\n' "${OUT}"
exit 0
