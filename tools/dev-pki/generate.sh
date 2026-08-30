#!/usr/bin/env bash
# Generates a throwaway development PKI under tools/dev-pki/out/:
#   ca.key/ca.crt        private CA          (EC P-256, 30 years)
#   server.key/server.crt  server identity   (10 years, SAN: localhost + 127.0.0.1 [+ extra])
#   client.key/client.crt  device identity   (25 years, EKU clientAuth)
#   crl.pem              empty CRL
#
# Validity periods and extensions intentionally match docs/SERVER_SPEC.md §3
# (decision D1) so dev behavior matches production behavior. This script is
# the behavioral ancestor of the M5 harness's bootstrap.sh.
#
# Usage: ./generate.sh [extra-hostname-or-ip ...]
set -euo pipefail

# Git Bash (MSYS) rewrites /CN=... arguments into Windows paths; disable that.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")"
OUT=out

CA_DAYS=10950      # 30 years
SERVER_DAYS=3650   # 10 years
CLIENT_DAYS=9125   # 25 years

if [[ -f "$OUT/ca.key" ]]; then
    echo "ERROR: $OUT/ca.key already exists. Refusing to overwrite an existing CA." >&2
    echo "Delete $OUT/ manually if you really want a fresh PKI." >&2
    exit 1
fi
mkdir -p "$OUT"

echo "==> CA (EC P-256, $CA_DAYS days)"
# Explicit config instead of -addext: the default openssl.cnf contributes its
# own basicConstraints, and duplicate extensions make strict parsers (Go)
# reject the certificate outright.
cat > "$OUT/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = Sambaloader Dev CA
[v3_ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF
openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/ca.key"
openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days "$CA_DAYS" \
    -config "$OUT/ca.cnf" -out "$OUT/ca.crt"

SAN="DNS:localhost,IP:127.0.0.1"
for extra in "$@"; do
    if [[ "$extra" =~ ^[0-9.]+$ ]]; then
        SAN="$SAN,IP:$extra"
    else
        SAN="$SAN,DNS:$extra"
    fi
done

echo "==> Server certificate ($SERVER_DAYS days, SAN: $SAN)"
# Real files instead of <(...) — Git Bash's openssl cannot read /dev/fd.
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n" "$SAN" > "$OUT/server.ext"
openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/server.key"
openssl req -new -key "$OUT/server.key" -subj "/CN=sambaloader-dev-server" -out "$OUT/server.csr"
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAcreateserial -sha256 -days "$SERVER_DAYS" \
    -extfile "$OUT/server.ext" -out "$OUT/server.crt"

echo "==> Client certificate ($CLIENT_DAYS days, EKU clientAuth)"
printf "extendedKeyUsage=clientAuth\nbasicConstraints=CA:FALSE\n" > "$OUT/client.ext"
openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/client.key"
openssl req -new -key "$OUT/client.key" -subj "/CN=dev-client" -out "$OUT/client.csr"
openssl x509 -req -in "$OUT/client.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAcreateserial -sha256 -days "$CLIENT_DAYS" \
    -extfile "$OUT/client.ext" -out "$OUT/client.crt"

echo "==> Empty CRL"
CRL_DIR="$OUT/.crldb"
mkdir -p "$CRL_DIR"
touch "$CRL_DIR/index.txt"
echo 1000 > "$CRL_DIR/crlnumber"
cat > "$CRL_DIR/ca.cnf" <<EOF
[ca]
default_ca = dev
[dev]
database = $CRL_DIR/index.txt
crlnumber = $CRL_DIR/crlnumber
default_md = sha256
default_crl_days = 3650
EOF
openssl ca -config "$CRL_DIR/ca.cnf" -gencrl \
    -keyfile "$OUT/ca.key" -cert "$OUT/ca.crt" -out "$OUT/crl.pem" 2>/dev/null

rm -f "$OUT/server.csr" "$OUT/client.csr" "$OUT/server.ext" "$OUT/client.ext" "$OUT/ca.cnf"

echo ""
echo "Done. Files in $OUT/. CA fingerprint:"
openssl x509 -in "$OUT/ca.crt" -noout -fingerprint -sha256
echo ""
echo "Dev PKI only — never use these files outside local development."
