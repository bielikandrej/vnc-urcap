#!/bin/bash
#
# STIMBA VNC Server — post-install bootstrap (URCap 3.0.0)
#
# Invoked either:
#   a) automatically by run-vnc.sh on first start (inline self-bootstrap), or
#   b) manually by operator via SSH: `bash post-install.sh`
#
# Idempotent — safe to run repeatedly.
#
# Creates /var/lib/urcap-vnc/ with group=polyscope + setgid so the UI
# (polyscope user) can atomically write its config.
# See ADR-004 in wiki/adr/ for the permission-model rationale.
#
# v2.2.0 additions:
#   - logrotate config for /var/log/urcap-vnc-audit.log (daily, 90 day rotate)
#   - cron entry for temp-allowlist-sweeper.sh (every minute)
#   - chmod +x for all daemon scripts (covers filesystems where URCap
#     extraction strips the exec bit — e.g. MSDOS USB sideload)
#
# v3.0.0 additions:
#   - chmod +x tls-bootstrap.sh + idle-watcher.sh (C1, C2)
#   - eager `tls-bootstrap.sh` run so the cert + fingerprint are present
#     BEFORE the UI shows the "Zobraziť cert fingerprint" button — avoids
#     a confusing "fingerprint n/a" state on first Polyscope open.
#
# v3.12.10 additions:
#   - symlink /root/.urcaps/<id>/sk/stimba/urcap/vnc/impl/daemon -> SCRIPT_DIR.
#     Polyscope 5.25.x extracts bundle resources into felix-cache (path
#     ${HOME}/GUI/felix-cache/bundleNNN/data/...) instead of the older
#     /root/.urcaps/<id>/ layout that VncInstallationNodeContribution.java
#     hardcodes in DAEMON_DIR. Without the symlink, every ProcessBuilder
#     diagnostic spawn (vnc-test.sh, health-probe.sh, diag-bundle.sh,
#     temp-allowlist-add.sh) returns exit 127 / "No such file or directory"
#     and the UI status panel reads stale or wrong values.
#
set -eu

URCAP_STATE_DIR="/var/lib/urcap-vnc"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[post-install] Bootstrapping ${URCAP_STATE_DIR}/"

mkdir -p "${URCAP_STATE_DIR}/sessions"

if getent group polyscope >/dev/null 2>&1; then
    chown -R root:polyscope "${URCAP_STATE_DIR}"
    chmod 2770 "${URCAP_STATE_DIR}"              # setgid so new files inherit polyscope group
    chmod 2770 "${URCAP_STATE_DIR}/sessions"
    echo "[post-install] dir=${URCAP_STATE_DIR} owner=root:polyscope mode=2770"
else
    # Fallback for non-standard UR images that don't ship 'polyscope' group.
    # Use sticky-world-writable (+t) so only file owner can delete files.
    chown -R root:root "${URCAP_STATE_DIR}"
    chmod 1777 "${URCAP_STATE_DIR}"
    chmod 1777 "${URCAP_STATE_DIR}/sessions"
    echo "[post-install] WARNING: 'polyscope' group not found — using 1777 fallback"
fi

# --- exec bits on daemon scripts --------------------------------------------
# Some URCap extractors (USB sideload via MSDOS, certain Windows ZIP tools)
# strip the exec bit.  Be defensive.
for s in run-vnc.sh stop-vnc.sh health-probe.sh post-install.sh \
         vnc-audit-hook.sh diag-bundle.sh vnc-test.sh \
         temp-allowlist-add.sh temp-allowlist-sweeper.sh \
         tls-bootstrap.sh idle-watcher.sh; do
    if [ -f "${SCRIPT_DIR}/${s}" ]; then
        chmod 755 "${SCRIPT_DIR}/${s}" 2>/dev/null || true
    fi
done

# --- v3.12.10: bridge /root/.urcaps/<id> path the Java code expects ---------
# See header for context. Idempotent: -sfn replaces stale symlink without
# dereferencing into the target.
URCAP_ID="sk.stimba.urcap.vnc-server"
EXPECTED_DAEMON_DIR="/root/.urcaps/${URCAP_ID}/sk/stimba/urcap/vnc/impl/daemon"
if mkdir -p "$(dirname "${EXPECTED_DAEMON_DIR}")" 2>/dev/null \
   && ln -sfn "${SCRIPT_DIR}" "${EXPECTED_DAEMON_DIR}" 2>/dev/null; then
    echo "[post-install] daemon-dir symlink: ${EXPECTED_DAEMON_DIR} -> ${SCRIPT_DIR}"
else
    echo "[post-install] WARN: could not create ${EXPECTED_DAEMON_DIR} symlink (diagnostics may show exit 127)"
fi

# --- eager TLS bootstrap (C1) ----------------------------------------------
# Generate the cert + fingerprint now so the UI's "Zobraziť fingerprint"
# button works even before the first daemon start.  Idempotent — no-op if
# the cert already exists.
if [ -x "${SCRIPT_DIR}/tls-bootstrap.sh" ]; then
    if "${SCRIPT_DIR}/tls-bootstrap.sh"; then
        echo "[post-install] TLS bootstrap ok"
    else
        echo "[post-install] WARN: TLS bootstrap failed (openssl missing?); daemon can still start with TLS_ENABLED=0"
    fi
fi

# --- logrotate --------------------------------------------------------------
# v3.12.20 — also cover x11vnc.log + websockify.log (added when those
# get used during debug), and add a SECOND config that caps the system
# logs (user.log, syslog, messages) which rsyslog's default rotates
# weekly with NO size limit. During the 2026-04-30 respawn-loop incident
# /var/log/user.log grew to 141 MB before next rsyslog rotation — that
# alone filled the 1.7 GB SD card. Installing both files at install
# time means fresh deploys are protected even on the very first day.
LOGROTATE_CONF="/etc/logrotate.d/urcap-vnc"
if [ -w /etc/logrotate.d ]; then
    cat > "${LOGROTATE_CONF}" <<'EOF'
/var/log/urcap-vnc.log /var/log/urcap-vnc-audit.log /var/log/urcap-vnc-temp-allowlist /var/log/x11vnc.log /var/log/websockify.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    create 0644 root root
    maxsize 5M
}
EOF
    echo "[post-install] installed ${LOGROTATE_CONF}"
fi

LOGROTATE_OVR="/etc/logrotate.d/urcap-vnc-overrides"
if [ -w /etc/logrotate.d ]; then
    cat > "${LOGROTATE_OVR}" <<'EOF'
# STIMBA VNC URCap v3.12.20 — bound rsyslog system logs that have no default
# size cap. The robot's 1.7 GB SD card filled to 100% in 2026-04-30 because
# /var/log/user.log was 141 MB after a 10-hour respawn loop (rsyslog routes
# 'logger -t urcap-vnc' messages to user.log; default rotation is weekly +
# uncapped). 20 MB cap with daily rotation prevents recurrence.
/var/log/user.log
/var/log/syslog
/var/log/messages
{
    daily
    maxsize 20M
    rotate 4
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    sharedscripts
    postrotate
        /usr/lib/rsyslog/rsyslog-rotate >/dev/null 2>&1 || \
            kill -HUP $(cat /var/run/rsyslogd.pid 2>/dev/null) 2>/dev/null || true
    endscript
}
EOF
    echo "[post-install] installed ${LOGROTATE_OVR}"

    # Force one rotation now if any of our logs is already > 5 MB.
    # logrotate will pick up the new config on next cron tick anyway,
    # but kicking it now releases pressure on a near-full SD card faster.
    /usr/sbin/logrotate -f /etc/logrotate.d/urcap-vnc 2>/dev/null || true
    /usr/sbin/logrotate -f /etc/logrotate.d/urcap-vnc-overrides 2>/dev/null || true
fi

# --- cron entry for temp-allowlist sweeper ----------------------------------
# Runs every minute as root.  Idempotent: writes only if the file differs.
CRON_FILE="/etc/cron.d/urcap-vnc-allowlist"
SWEEPER="${SCRIPT_DIR}/temp-allowlist-sweeper.sh"
if [ -x "${SWEEPER}" ] && [ -d /etc/cron.d ] && [ -w /etc/cron.d ]; then
    CRON_LINE="* * * * * root ${SWEEPER} >/dev/null 2>&1"
    NEW_CRON="# STIMBA VNC Server — temp allowlist expiry sweeper (installed by URCap)
${CRON_LINE}
"
    if [ ! -f "${CRON_FILE}" ] || [ "$(cat "${CRON_FILE}" 2>/dev/null)" != "${NEW_CRON}" ]; then
        printf '%s' "${NEW_CRON}" > "${CRON_FILE}"
        chmod 644 "${CRON_FILE}"
        echo "[post-install] installed ${CRON_FILE}"
    else
        echo "[post-install] ${CRON_FILE} already up to date"
    fi
elif [ ! -x "${SWEEPER}" ]; then
    echo "[post-install] skipping cron: sweeper not executable at ${SWEEPER}"
fi

# --- audit log init ---------------------------------------------------------
touch /var/log/urcap-vnc-audit.log 2>/dev/null || true
chmod 640 /var/log/urcap-vnc-audit.log 2>/dev/null || true

echo "[post-install] done"
exit 0
