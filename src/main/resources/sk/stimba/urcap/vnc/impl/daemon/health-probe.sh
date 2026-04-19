#!/bin/bash
#
# STIMBA VNC Server — health probe (URCap 2.1.0)
#
# One-shot JSON output over 5 probes. Called by:
#   - Java UI (VncInstallationNodeContribution.pollHealth()) every 5s
#   - diag-bundle.sh (Sprint 2 B2) — one source of truth
#   - Remote SSH: `ssh root@robot '/path/to/health-probe.sh'`
#
# Runs as whoever invoked it. When called by polyscope user, probes that
# require root silently degrade to "unknown" — Java UI renders these as
# grey dots with tooltip "probe requires root, run via ssh".
#
# Output: single-line JSON (jq-friendly). Keys: daemon, iptables, port,
#         display, ixrouter. Values: ok | warning | fail | unknown.
#
# See ADR-006 in wiki/adr/ for design rationale.
#
set -u

LOCK_FILE="/tmp/urcap-vnc.lock"
CONFIG_FILE_NEW="/var/lib/urcap-vnc/config"
CONFIG_FILE_LEGACY="/root/.urcap-vnc.conf"

# Source config for IXROUTER_IP / VNC_PORT defaults (same order as run-vnc.sh)
if [ -r "${CONFIG_FILE_NEW}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_NEW}"
elif [ -r "${CONFIG_FILE_LEGACY}" ]; then
    # shellcheck disable=SC1090
    . "${CONFIG_FILE_LEGACY}"
fi
VNC_PORT="${VNC_PORT:-5900}"
IXROUTER_IP="${IXROUTER_IP:-192.168.0.100}"

# --- probe: daemon -----------------------------------------------------------
# /tmp/urcap-vnc.lock contains PID. Process exists iff /proc/<PID> dir exists
# (works from any user, unlike `kill -0` which hits EPERM cross-user).
probe_daemon() {
    if [ ! -f "${LOCK_FILE}" ]; then
        echo "fail"; return
    fi
    local pid
    pid="$(cat "${LOCK_FILE}" 2>/dev/null)"
    if [ -z "${pid}" ]; then
        echo "fail"; return
    fi
    if [ -d "/proc/${pid}" ]; then
        echo "ok"
    else
        echo "fail"
    fi
}

# --- probe: iptables ---------------------------------------------------------
# Needs CAP_NET_ADMIN. As polyscope user: unknown. As root: count ACCEPT/DROP
# rules on VNC_PORT and verify 3 (IXrouter, loopback, DROP) are present.
probe_iptables() {
    if [ "$(id -u)" != "0" ]; then
        echo "unknown"; return
    fi
    if ! command -v iptables >/dev/null 2>&1; then
        echo "fail"; return
    fi
    local count
    count="$(iptables -L INPUT -n 2>/dev/null | grep -c ":${VNC_PORT}" || echo 0)"
    if [ "${count}" -ge 3 ]; then
        echo "ok"
    else
        echo "fail"
    fi
}

# --- probe: port -------------------------------------------------------------
# `ss` is readable from any user.
probe_port() {
    if ! command -v ss >/dev/null 2>&1; then
        # fallback to netstat
        if command -v netstat >/dev/null 2>&1; then
            if netstat -tln 2>/dev/null | grep -q ":${VNC_PORT} "; then
                echo "ok"; return
            fi
        fi
        echo "unknown"; return
    fi
    if ss -tln 2>/dev/null | grep -q ":${VNC_PORT} "; then
        echo "ok"
    else
        echo "fail"
    fi
}

# --- probe: display ----------------------------------------------------------
# xdpyinfo needs MIT-MAGIC-COOKIE. polyscope user owns :0 session, so OK.
probe_display() {
    if ! command -v xdpyinfo >/dev/null 2>&1; then
        echo "unknown"; return
    fi
    if xdpyinfo -display :0 >/dev/null 2>&1; then
        echo "ok"
    else
        echo "fail"
    fi
}

# --- probe: ixrouter ---------------------------------------------------------
# ICMP ping usually works via setuid; failure bumps to "warning" not "fail"
# because a non-responsive IXrouter may just have ICMP disabled.
probe_ixrouter() {
    if ! command -v ping >/dev/null 2>&1; then
        echo "unknown"; return
    fi
    if ping -c 1 -W 2 "${IXROUTER_IP}" >/dev/null 2>&1; then
        echo "ok"
    else
        echo "warning"
    fi
}

DAEMON="$(probe_daemon)"
IPTABLES="$(probe_iptables)"
PORT="$(probe_port)"
DISP="$(probe_display)"
IXR="$(probe_ixrouter)"

printf '{"daemon":"%s","iptables":"%s","port":"%s","display":"%s","ixrouter":"%s"}\n' \
    "${DAEMON}" "${IPTABLES}" "${PORT}" "${DISP}" "${IXR}"
