#!/usr/bin/env bash
# Revoke a device certificate and regenerate crl.pem (SERVER_SPEC §3.4).
# Requires ca.key temporarily restored to this directory, like enrollment.
#
#   ./revoke.sh <serial-hex>          e.g. ./revoke.sh 0x4a2f91...
#
# The serial is shown on the admin page's device list, in uploadd logs at
# enrollment, or via: openssl x509 -in <device.crt> -noout -serial
#
# uploadd regenerates the CRL from its device registry (Go-signed); this
# script drives that and reloads nginx so the revocation bites immediately.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -ne 1 ]]; then
    echo "usage: ./revoke.sh <serial-hex>" >&2
    exit 1
fi

docker compose run --rm uploadd -revoke "$1"
docker compose exec nginx nginx -s reload
echo "Revoked $1. The device fails its next TLS handshake."
echo "Remember to move ca.key back offline."
