#!/bin/bash
#
# STIMBA VNC Server — TLS bootstrap (URCap 3.0.0, C1)
#
# Generates a self-signed x11vnc TLS certificate if one does not already
# exist, and emits a SHA-256 fingerprint file that the UI reads to show
# the operator which cert the remote client is about to verify.
#
# Why self-signed and not a proper CA?
# - UR e-Series is an air-gap-friendly appliance.  No ACME/Let's Encrypt
#   round-trips, no corporate PKI to bootstrap.  Operator pins the cert
#   fingerprint the first time they connect.  See ADR-008.
# - x11vnc's own `-ssl SAVE` semantic handles cert lookup and negotiation;
#   we only need to ensure the PEM exists and the perms are tight.
#
# Idempotent. Safe to invoke on every daemon start.
#
# Output:
#   /root/.vnc/certs/server.pem         — combined key+cert (x11vnc format)
#   /root/.vnc/certs/fingerprint.txt    — SHA-256 fingerprint, pinnable
#
# Exit codes:
#   0 - cert exists or was generated successfully
#   1 - openssl missing or failed
#   2 - /root/.vnc/certs/ cannot be created/chmod'd (filesystem issue)
#
set -eu

CERT_DIR="/root/.vnc/certs"
CERT_PEM="${CERT_DIR}/server.pem"
FP_FILE="${CERT_DIR}/fingerprint.txt"
HOSTNAME_FALLBACK="$(hostname 2>/dev/null || echo ur-robot)"
SUBJ="/CN=stimba-urcap-vnc-${HOSTNAME_FALLBACK}/O=STIMBA, s. r. o."

log() { logger -t "urcap-vnc-tls" "$*"; echo "[tls-bootstrap] $*"; }

# --- 0. openssl must be available ------------------------------------------
if ! command -v openssl >/dev/null 2>&1; then
    log "ERROR: openssl not found on PATH. Install via 'apt-get install openssl'"
    exit 1
fi

# --- 1. Ensure directory exists with tight perms ---------------------------
mkdir -p "${CERT_DIR}" || { log "ERROR: mkdir ${CERT_DIR}"; exit 2; }
chmod 700 "${CERT_DIR}" 2>/dev/null || { log "ERROR: chmod 700 ${CERT_DIR}"; exit 2; }

# --- 2. Generate cert if missing -------------------------------------------
if [ ! -s "${CERT_PEM}" ]; then
    log "no cert found — generating self-signed (RSA 2048, 10y validity, subj=${SUBJ})"
    # Single PEM carries both the key and the cert — x11vnc -ssl SAVE expects this layout.
    # -nodes = don't encrypt the private key (x11vnc would need interactive passphrase).
    if openssl req -new -x509 -days 3650 -nodes \
            -newkey rsa:2048 \
            -keyout "${CERT_PEM}" \
            -out    "${CERT_PEM}" \
            -subj   "${SUBJ}" >/dev/null 2>&1; then
        chmod 600 "${CERT_PEM}" 2>/dev/null || true
        log "generated ${CERT_PEM}"
    else
        log "ERROR: openssl req failed"
        exit 1
    fi
else
    # Legacy perm-fix — older bootstraps may have left 644
    chmod 600 "${CERT_PEM}" 2>/dev/null || true
fi

# --- 3. Compute + publish SHA-256 fingerprint ------------------------------
# Format: "SHA256 Fingerprint=XX:XX:…:XX\n<subject>\n<notBefore>..<notAfter>"
# UI parses the XX:… line only; other lines are human context.
if FP="$(openssl x509 -in "${CERT_PEM}" -noout -fingerprint -sha256 2>/dev/null)"; then
    {
        echo "${FP}"
        openssl x509 -in "${CERT_PEM}" -noout -subject  2>/dev/null || true
        openssl x509 -in "${CERT_PEM}" -noout -dates    2>/dev/null || true
    } > "${FP_FILE}"
    chmod 644 "${FP_FILE}" 2>/dev/null || true
    log "fingerprint written to ${FP_FILE}"
else
    log "WARN: openssl x509 -fingerprint failed — FP_FILE may be stale"
fi

exit 0
