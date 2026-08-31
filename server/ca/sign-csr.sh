#!/usr/bin/env bash
# Offline device-certificate signing (enrollment mode (b), SERVER_SPEC §3.5).
# Normal enrollment (mode (a)) signs inside uploadd; this script exists for
# users who never want ca.key to touch the server: run it on the machine
# holding the key.
#
#   ./sign-csr.sh <csr-file> <device-label>
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")"

CLIENT_DAYS=9125   # 25 years (decision D1)

if [[ $# -ne 2 ]]; then
    echo "usage: ./sign-csr.sh <csr-file> <device-label>" >&2
    exit 1
fi
CSR="$1"; LABEL="$2"

# Filename label only; the certificate keeps the CSR's own subject CN
# (OpenSSL 1.1.1's `x509 -req` cannot rewrite it — mode (a) can).
OUT=$(echo "$LABEL" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9-]\+/-/g; s/^-\+//; s/-\+$//' | cut -c1-64)
[[ -n "$OUT" ]] || OUT="device"

printf "extendedKeyUsage=clientAuth\nbasicConstraints=CA:FALSE\n" > .client.ext
openssl x509 -req -in "$CSR" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -sha256 -days "$CLIENT_DAYS" -extfile .client.ext -out "${OUT}.crt"
rm -f .client.ext
echo "Signed: ${OUT}.crt"
openssl x509 -in "${OUT}.crt" -noout -serial -enddate
