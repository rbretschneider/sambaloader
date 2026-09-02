#!/bin/sh
# nginx loads ssl_crl at config-load time, so a freshly written crl.pem
# has no effect until a reload. Watching the file closes the gap between
# "operator clicked Revoke" and "the device actually stops working" —
# without it, revocation silently waits for the next restart.
#
# Polling (not inotify) keeps this to plain BusyBox sh with no extra
# packages in the nginx image.
set -eu

CRL=/etc/nginx/ca/crl.pem
STAMP=/tmp/crl.stamp
INTERVAL="${CRL_POLL_SECONDS:-10}"
SERVER_CERT=/etc/nginx/ca/server.crt

# On a first run uploadd is still generating the CA when nginx starts.
# depends_on only orders container start, not readiness, so without this
# wait nginx exits immediately on a missing ssl_certificate and the whole
# stack looks broken on the very first `docker compose up`.
waited=0
while [ ! -f "$SERVER_CERT" ] || [ ! -f "$CRL" ]; do
    if [ "$waited" -ge 60 ]; then
        echo "timed out waiting for uploadd to provision $SERVER_CERT" >&2
        exit 1
    fi
    [ "$waited" -eq 0 ] && echo "waiting for uploadd to provision CA material..."
    sleep 1
    waited=$((waited + 1))
done

touch "$STAMP"

watch_crl() {
    while true; do
        sleep "$INTERVAL"
        if [ -f "$CRL" ] && [ "$CRL" -nt "$STAMP" ]; then
            touch "$STAMP"
            echo "crl.pem changed — reloading nginx to apply revocations"
            nginx -s reload || echo "nginx reload failed; will retry on next change"
        fi
    done
}

watch_crl &
exec nginx -g 'daemon off;'
