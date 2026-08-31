# Sambaloader server stack

The container stack behind the Sambaloader Android app, implementing
[docs/SERVER_SPEC.md](../docs/SERVER_SPEC.md) v1.1. This directory is
self-contained — copy it wholesale into its own repo.

```
nginx :443   mTLS enforced — the ONLY port ever forwarded on the router
nginx :8443  enrollment/admin — LAN ONLY, NEVER FORWARDED
uploadd      plain HTTP on the compose-internal bridge, no published ports
/library     your NAS mount; files land there for Samba to share
```

## Deploying from the registry

CI publishes the container on every push touching `server/`:
`ghcr.io/rbretschneider/sambaloader/uploadd:latest` (plus `sha-<commit>`
tags for pinning). To deploy the prebuilt image instead of building
locally, override the service:

```yaml
# docker-compose.override.yml on the deploy host
services:
  uploadd:
    image: ghcr.io/rbretschneider/sambaloader/uploadd:latest
    build: !reset null
```

The package is private by default — authenticate the deploy host once:
`docker login ghcr.io -u rbretschneider` with a token that has
`read:packages`.

## First-time setup

```bash
cp .env.example .env            # set PUID/PGID, LIBRARY_PATH, PUBLIC_URL
./ca/bootstrap.sh nas.example.com 192.168.1.50   # hostname + LAN IP (SANs)
docker compose up -d --build
```

Then **move `ca.key` off the server** (bootstrap prints this loudly).
Forward **only** port 443 on the router. **Never 8443.**

Enroll a phone: restore `ca.key` into `ca/`, open
`https://<server-lan-ip>:8443` from a browser on the LAN, click
*Enroll a device*, scan the QR with the app, compare the fingerprint,
done. Remove `ca.key` again.

Revoke a lost phone: restore `ca.key`, then `./ca/revoke.sh <serial>`
(serials are on the admin page). Remove `ca.key` again.

## Library mount (container host ≠ NAS)

The stack runs on a server; the NAS library is a network mount passed in
via `LIBRARY_PATH`.

- **Use NFS if at all possible.** `uploadd` publishes files with an
  atomic rename out of `<library>/.incoming/`; rename is atomic on NFS
  but **not** over an SMB mount.
- If SMB is all you have: exclude `.incoming` from the Samba share
  (`veto files = /.incoming/` in smb.conf) so partial files are never
  visible to consumers.
- Mount NFS `hard` (default) and avoid `async` exports for the library.
- `PUID`/`PGID` must match what the share expects — `ls -ln` an existing
  file in the share to find them. The mount itself must map those ids
  (NFS export mapping, or `uid=`/`gid=` SMB mount options).

## Using your own nginx in front

If TLS terminates in an nginx you already run, that nginx **is** the
security boundary and must replicate [nginx/api.conf](nginx/api.conf)
exactly. The non-negotiables:

1. `ssl_verify_client on` with `ssl_client_certificate` = this CA's
   `ca.crt` and `ssl_crl` = `crl.pem`. **This is the entire auth model** —
   without it the API is open to the internet.
2. `proxy_set_header X-Device-CN $ssl_client_s_dn_cn;` — uploadd 401s
   without it (that header absent = mTLS was bypassed).
3. `client_max_body_size 0;` and `proxy_request_buffering off;` — or
   videos 413 / spool to proxy disk.
4. Proxy **only** `location /api/` on the public listener. The enrollment
   endpoints (`/enroll/*`, `/qr`, `/admin/*`) belong on a separate
   LAN-only listener (see [nginx/admin.conf](nginx/admin.conf)) with NO
   client-cert requirement — and that listener is never forwarded.
5. Reload nginx after every revocation (`nginx -s reload`) so the fresh
   CRL is read.
6. Publish uploadd's port 8080 to that nginx only (e.g. keep it on a
   shared docker network, or bind it to 127.0.0.1) — never to the world.

If you run the bundled compose file instead, all of this is already done.

## uploadd endpoints

| Route | Listener | Auth |
|---|---|---|
| `GET  /api/v1/health` | :443 | mTLS (nginx) + `X-Device-CN` |
| `POST /api/v1/assets/check` | :443 | mTLS (nginx) + `X-Device-CN` |
| `POST /api/v1/assets` | :443 | mTLS (nginx) + `X-Device-CN` |
| `POST /enroll/begin` | :8443 | none — LAN-only listener |
| `POST /enroll/complete` | :8443 | single-use token (10 min TTL) |
| `GET  /qr`, `GET /admin/devices` | :8443 | none — LAN-only listener |

State lives in `./state/uploadd.db` (SQLite, WAL). Device certs are
signed for 25 years, CA 30, server 10 (decision D1) — enrollment is
once-per-device, ever; revocation is the only lifecycle event.

## Smoke test

```bash
# no client cert -> must die during the TLS handshake, not with an HTTP error
curl -vk https://localhost/api/v1/health 2>&1 | grep -E "alert|error"

# with a client cert (after enrolling one, or sign one with sign-csr.sh)
curl -sk --cert device.crt --key device.key https://localhost/api/v1/health
```
