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
