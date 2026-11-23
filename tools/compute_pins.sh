#!/usr/bin/env bash
# compute_pins.sh — recompute the SPKI SHA-256 certificate pins for Calendar Alarm.
#
# Hardening plan 2026-11-22, phase 3, item 1: this script reproduces the pin values
# in app/src/main/java/io/github/calendaralarm/sync/CertificatePins.kt:
#   1. The LIVE chain of a host (openssl s_client) → leaf + intermediates.
#   2. The root CAs from the local trust store (/etc/ssl/certs) per CA family.
#
# Usage:
#   ./scripts/compute_pins.sh                 # live chain of alarm.example.com + all roots
#   ./scripts/compute_pins.sh --host HOST     # other host (default: alarm.example.com)
#   ./scripts/compute_pins.sh --offline        # only roots from /etc/ssl/certs, no live call
#
# Output: per cert subject + SPKI SHA-256 (base64), ready to paste into CertificatePins.kt.
set -euo pipefail

HOST="alarm.example.com"
PORT="443"
OFFLINE=0
for arg in "$@"; do
    case "$arg" in
        --host) shift; HOST="$1" ;;
        --offline) OFFLINE=1 ;;
        -h|--help)
            sed -n '2,12p' "$0"; exit 0 ;;
    esac
done

spki_of_pem() {
    # Read a PEM cert from stdin, print SPKI SHA-256 as base64.
    openssl x509 -noout -pubkey 2>/dev/null \
        | openssl pkey -pubin -outform DER 2>/dev/null \
        | openssl dgst -sha256 -binary 2>/dev/null \
        | openssl base64 -A 2>/dev/null
}

subject_of_pem() {
    openssl x509 -noout -subject 2>/dev/null | sed 's/^subject=//'
}

echo "==================================================================="
echo " compute_pins.sh — host=$HOST (offline=$OFFLINE) — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "==================================================================="

if [ "$OFFLINE" -eq 0 ]; then
    echo
    echo "--- LIVE CHAIN of $HOST:$PORT (do NOT pin the leaf — it rotates) ---"
    CHAIN="$(openssl s_client -connect "$HOST:$PORT" -servername "$HOST" -showcerts </dev/null 2>/dev/null || true)"
    if [ -z "$CHAIN" ]; then
        echo "WARNING: could not fetch a live chain from $HOST — skipping." >&2
    else
        # Split the chain into separate PEM blocks
        awk '/-----BEGIN CERTIFICATE-----/{n++} {print > ("/tmp/calalarm_pin_cert_" n ".pem")}' <<<"$CHAIN" 2>/dev/null || true
        for f in /tmp/calalarm_pin_cert_*.pem; do
            [ -s "$f" ] || continue
            SUBJ="$(subject_of_pem < "$f")"
            PIN="$(spki_of_pem < "$f")"
            [ -n "$PIN" ] || continue
            echo "SUBJECT: $SUBJ"
            echo "SPKI256: $PIN"
            echo
        done
        rm -f /tmp/calalarm_pin_cert_*.pem
    fi
fi

    echo "--- ROOT CAs from /etc/ssl/certs (per CA family) ---"
# Mapping: filename pattern → label. Only the families that Cloudflare
# Universal SSL can use for example.com (GTS, ISRG, SSL.com, GlobalSign).
declare -A FAMILIES=(
    ["GTS_Root_R1"]="GTS Root R1"
    ["GTS_Root_R2"]="GTS Root R2"
    ["GTS_Root_R3"]="GTS Root R3"
    ["GTS_Root_R4"]="GTS Root R4"
    ["GlobalSign_Root_CA"]="GlobalSign Root CA"
    ["ISRG_Root_X1"]="ISRG Root X1"
    ["ISRG_Root_X2"]="ISRG Root X2"
    ["SSL.com_Root_Certification_Authority_RSA"]="SSL.com Root CA RSA"
    ["SSL.com_Root_Certification_Authority_ECC"]="SSL.com Root CA ECC"
    ["SSL.com_TLS_RSA_Root_CA_2022"]="SSL.com TLS RSA Root 2022"
    ["SSL.com_TLS_ECC_Root_CA_2022"]="SSL.com TLS ECC Root 2022"
)

CERTS_DIR="${CERTS_DIR:-/etc/ssl/certs}"
for key in "${!FAMILIES[@]}"; do
    label="${FAMILIES[$key]}"
    pem="$CERTS_DIR/$key.pem"
    [ -f "$pem" ] || pem="$CERTS_DIR/$key.crt"
    [ -f "$pem" ] || { echo "(root not found in $CERTS_DIR: $key — skipping)"; continue; }
    SUBJ="$(subject_of_pem < "$pem")"
    PIN="$(spki_of_pem < "$pem")"
    EXPIRY="$(openssl x509 -noout -enddate < "$pem" 2>/dev/null | sed 's/^notAfter=//')"
    [ -n "$PIN" ] || { echo "(could not compute SPKI for $key)"; continue; }
    echo "CA:      $label"
    echo "SUBJECT: $SUBJ"
    echo "EXPIRES: $EXPIRY"
    echo "SPKI256: $PIN"
    echo
done | sort

echo "==================================================================="
echo "Done. Compare with CertificatePins.kt and update on differences."
echo "NEVER pin the leaf (it rotates on every renewal) — only intermediate/root."
echo "==================================================================="
