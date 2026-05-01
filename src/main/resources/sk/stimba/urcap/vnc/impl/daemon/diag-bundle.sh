#!/bin/bash
#
# STIMBA VNC Server — diagnostic bundle (URCap 2.2.0+, USB autodetect v3.12.17)
#
# Packs everything you'd ask Andrej to grab via SSH into one .tar.gz so the
# operator can email/Gong-share it and Andrej can reproduce the fleet state
# locally.
#
# Output (in priority order, v3.12.17):
#   1. USB stick auto-mounted under /programs/<label>/ — preferred. Operator
#      plugs the stick into the teach pendant before clicking the button and
#      the bundle lands directly on it; no SSH required.
#   2. /media/<label>/, /mnt/<label>/, /run/media/<user>/<label>/ — older
#      firmwares + URSim setups.
#   3. Fallback /root/urcap-vnc-diag-YYYYMMDD-HHMMSS.tar.gz when no removable
#      filesystem is writable. Same SSH-fetch flow as before v3.12.17.
#
# Called from the Java UI via "Exportovať diagnostiku" button. Exits 0 on
# success. stdout: final .tar.gz path (UI shows it verbatim — operator sees
# `/programs/<USB>/...` for USB output vs `/root/...` for SSH-fetch case).
# stderr: progress log including which path was picked and why.
#
# SENSITIVE INFO REDACTION:
# Passwords (run-vnc.sh may log VNC_PASSWORD echoes during debugging) are
# scrubbed in-memory before archive.  See R1 in wiki/sprints/sprint-2.
#
set -eu

TS="$(date +%Y%m%d-%H%M%S)"
STAGE="$(mktemp -d /tmp/urcap-vnc-diag.XXXXXX)"

log() { printf '[diag-bundle] %s\n' "$*" >&2; }
trap 'rm -rf "${STAGE}"' EXIT

log "stage dir: ${STAGE}"

# ---------------------------------------------------------------------------
# USB output path detection (v3.12.17)
# ---------------------------------------------------------------------------
# Polyscope auto-mounts USB sticks at /programs/<volume-label>/ on e-Series so
# they show up in the file browser. Generic Linux paths (/media, /mnt,
# /run/media) cover older firmwares + URSim setups. We pick the FIRST writable
# removable filesystem in this priority order, falling back to /root/ if none.
#
# Filesystem types matched: vfat / exfat / ntfs / fuseblk / msdos — common on
# consumer USB sticks. We deliberately don't match ext4 etc., which would
# pick up internal mounts the operator can't physically remove.
detect_usb_mount() {
    local candidate
    while IFS= read -r candidate; do
        if [ -d "${candidate}" ] && [ -w "${candidate}" ]; then
            # Probe writability — `mountpoint -w` isn't portable. We touch a
            # marker file. If it works, the mount is healthy + writable.
            if : > "${candidate}/.urcap-diag.probe" 2>/dev/null; then
                rm -f "${candidate}/.urcap-diag.probe"
                printf '%s\n' "${candidate}"
                return 0
            fi
        fi
    done < <(awk '
        $2 ~ /^\/programs\//   && $3 ~ /^(vfat|exfat|ntfs|fuseblk|msdos)$/ {print $2}
        $2 ~ /^\/media\//      && $3 ~ /^(vfat|exfat|ntfs|fuseblk|msdos)$/ {print $2}
        $2 ~ /^\/mnt\//        && $3 ~ /^(vfat|exfat|ntfs|fuseblk|msdos)$/ {print $2}
        $2 ~ /^\/run\/media\// && $3 ~ /^(vfat|exfat|ntfs|fuseblk|msdos)$/ {print $2}
    ' /proc/mounts 2>/dev/null)
    return 1
}

if USB_MNT="$(detect_usb_mount)"; then
    OUT="${USB_MNT}/urcap-vnc-diag-${TS}.tar.gz"
    log "USB stick detected at ${USB_MNT} — bundle will land there (no SSH needed)"
else
    OUT="/root/urcap-vnc-diag-${TS}.tar.gz"
    log "no writable USB mount under /programs|/media|/mnt|/run/media — falling back to /root (fetch via scp)"
fi

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
