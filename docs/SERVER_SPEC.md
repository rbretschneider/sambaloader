# Sambaloader Server — Container Stack Specification

**Version:** 1.2
(1.0 → 1.1: `X-Asset-Filename` is percent-encoded UTF-8;
1.1 → 1.2: **CA provisioning moved from shell scripts into `uploadd`**,
pairing-token alphabet narrowed — see §3)
**Status:** FROZEN — the Android app in this repo is built against this contract.
Changes require a version bump here and a matching client change.
**Source requirements:** [frd.md](../frd.md) v1.0, amended by decisions D1–D5 in
[MILESTONES.md](MILESTONES.md).

This document fully specifies the backend so it can be built in its own repo with
no other context. A minimal reference implementation lives in this repo under
`devserver/` (Milestone 5) and must stay behaviorally identical to this spec.

---

## 1. Overview

A Docker Compose stack that receives photo/video uploads from enrolled Android
devices over mutual TLS and writes them as plain files into a library directory
that an existing Samba server shares on the LAN.

The defining constraint: **exactly one port (443) is exposed to the internet, and
nginx drops any connection that cannot present a certificate signed by the user's
own private CA — during the TLS handshake, before any application code runs.**

```
Internet ──443──▶ router ──443──▶ [container host]
                                    nginx :443  (mTLS enforced, TLS 1.3)
                                    nginx :8443 (enrollment/admin — LAN ONLY, NEVER FORWARDED)
                                      │ plain HTTP, internal docker bridge
                                      ▼
                                    uploadd :8080 (no published ports)
                                      │ writes files
                                      ▼
                                    /library  ──▶ network mount ──▶ NAS ──▶ existing Samba share
```

### 1.1 Deployment topology (decision D5)

The stack does **not** run on the NAS. It runs on a separate server; the NAS
library directory is passed in as a volume via a network mount (NFS or SMB).
Consequences the implementation MUST handle:

- **Atomicity.** `uploadd` writes a temp file, `fsync`s, then `rename`s into
  place. `rename` is atomic on NFS within a directory; **it is NOT guaranteed
  atomic over an SMB mount.** The README must recommend NFS for the library
  mount. If the user only has SMB, the fallback is documented behavior: temp
  files use a `.part` suffix inside a `.incoming/` directory at the library
  root, and the Samba share should exclude `.incoming/` (veto files) so
  partial files are never visible to consumers.
- **Ownership.** Files must land owned by the UID/GID the Samba share expects.
  `uploadd` runs as `PUID:PGID` from the environment; with an NFS mount, the
  export must map those IDs (or use `all_squash` with matching anon ids); with
  an SMB mount, mount with `uid=,gid=` options. The README documents both.
- **Durability.** `fsync` over a network mount is only as good as the mount's
  cache mode. README recommends `hard` NFS mounts (default) and warns against
  `async` exports for the library.

### 1.2 Non-negotiable security requirements (from FRD §4)

These are acceptance criteria for the backend. Each must have a test or a
documented manual verification.

1. Port 445 (SMB) is never exposed by this stack or documented as exposable.
2. Port 8443 is never forwarded on the router. The README states this **in bold**.
3. (Client-side, restated for context) the app trusts only the private CA.
4. (Client-side) device private keys are hardware-backed and non-exportable.
5. `ca.key` is not required for normal server operation — uploads, dedupe and
   library writes never touch it; only enrollment and revocation do.
   **Amended in 1.2 (decision D8):** it is no longer required to be *absent*.
   The default posture keeps it in `CA_DIR` so that setup and enrollment are
   single commands. Operators who need the stronger posture move it offline and
   lose nothing but one-command enrollment (§3.3).
6. `uploadd` has no `ports:` mapping — internal bridge only. The compose file
   carries a comment saying this is a security control.
7. Pairing tokens are single-use with a TTL ≤ 10 minutes.
7a. **Added in 1.2:** the enrollment/admin listener requires an admin
   password (HTTP Basic), enforced in `uploadd` so it survives a
   misconfigured proxy. Reaching port 8443 is not by itself permission to
   enroll a device — otherwise the trust boundary is the entire LAN.
   A blank `ADMIN_PASSWORD` generates a strong one rather than disabling
   the check.
8. All certificate validation failures fail closed. There is no unauthenticated
   fallback path anywhere.

---

## 2. Repository layout

```
server/
  docker-compose.yml
  .env.example              # PUID, PGID, LIBRARY_PATH, TZ
  nginx/
    api.conf                # :443, mTLS enforced
    admin.conf              # :8443, no client cert — NEVER FORWARD
  uploadd/
    Dockerfile
    go.mod
    cmd/uploadd/main.go
    internal/...            # handlers, registry, store
  ca/                       # provisioned by uploadd on first run
    revoke.sh               # shell equivalent of "uploadd -revoke"
  admin/
    index.html              # minimal enrollment page served on :8443
  README.md
```

Language: **Go** (decision D3). Single static binary, `FROM scratch` or
`gcr.io/distroless/static` final image.

---

## 3. Certificate authority

All key material is EC P-256, generated by `uploadd` itself (Go `crypto/x509`).
No CA framework, and no `openssl` dependency on the host.

### 3.1 Validity periods (decision D1 — supersedes FRD §5.2)

Certificates behave like WireGuard keys: enroll once, ever. Revocation via CRL
is the only lifecycle event. There is **no renewal flow**.

| Certificate | Validity | Extensions |
|---|---|---|
| CA root | **30 years** | `CA:TRUE, pathlen:0`, `keyCertSign, cRLSign` |
| Server | **10 years** | EKU `serverAuth`; SAN = DDNS hostname **and** LAN IP of the container host |
| Device | **25 years** | EKU `clientAuth`; CN = device label |
| CRL | regenerate on every revocation; `nextUpdate` = 10 years | |

### 3.2 First-run provisioning (**changed in 1.2**)

`uploadd` provisions its own CA at startup. `bootstrap.sh` and `sign-csr.sh`
are **removed**; `docker compose up` is the entire setup, with no `openssl`
on the host and no file for the operator to move.

On start, `uploadd` inspects `CA_DIR`:

| State | Action |
|---|---|
| empty | generate `ca.key`, `ca.crt`, empty `crl.pem`, then the server cert |
| `ca.crt` + `ca.key` present | leave the CA alone; check the server cert |
| `ca.crt` only | leave alone, log that enrollment is disabled (offline-key mode) |
| `ca.key` only | **fatal** — refuse to guess; the operator restores `ca.crt` or empties the directory |

Regenerating a CA would invalidate every enrolled device, so provisioning is
strictly additive and never overwrites.

The **server certificate** is reissued when it no longer covers `PUBLIC_URL`'s
hostname or every name in `EXTRA_SANS`, or when it has expired. Reissuing costs
enrolled phones nothing — they pin the CA, not the leaf. The CA itself is
never reissued.

`nginx` blocks on `ca/server.crt` appearing before starting, because
`depends_on` orders container start but not readiness.

### 3.3 Enrollment signing

`uploadd` signs PKCS#10 CSRs directly (§7.5): 25-year validity, EKU
`clientAuth`, CN forced to the device label. `ca.key` is read fresh per
operation and never cached.

**Default posture (changed in 1.2):** `ca.key` stays in `CA_DIR`, so enrolling
a device is one command. The tradeoff is explicit — read access to that file is
enough to mint a device certificate. Operators who reject it may move `ca.key`
away after first start; `uploadd` runs normally without it and returns
`503 ca_key_absent` on enrollment until it is restored.

### 3.4 Revocation

`uploadd -revoke <serial>` marks the certificate revoked and regenerates
`crl.pem`. nginx reloads within seconds via its CRL watcher — no operator
action. `ca/revoke.sh` remains as a shell equivalent. Requires `ca.key`.

### 3.5 Enrollment signing mode (decision D2, amended by D8 in 1.2)

Mode **(a)** — `uploadd` signs directly — is the only implemented mode, and
since 1.2 `ca.key` simply stays present rather than being shuttled in and out
per enrollment. See §3.3 for the tradeoff and the offline alternative.

Mode **(b)** — CSR parked in `./state/pending/` and signed on another machine,
app polls for the result — remains unimplemented. With `sign-csr.sh` removed,
anyone wanting it signs the parked CSR with their own tooling.

---

## 4. nginx configuration

### 4.1 API listener — `nginx/api.conf`

```nginx
# nginx has NO built-in $ssl_client_s_dn_cn variable (the FRD's snippet
# assumed one; nginx refuses to start). Extract the CN from the full DN:
map $ssl_client_s_dn $client_cert_cn {
    default "";
    "~CN=(?<cn>[^,]+)" $cn;
}

server {
    listen 443 ssl;
    http2 on;
    server_name _;

    ssl_certificate     /etc/nginx/ca/server.crt;
    ssl_certificate_key /etc/nginx/ca/server.key;
    ssl_protocols       TLSv1.3;

    ssl_client_certificate /etc/nginx/ca/ca.crt;
    ssl_verify_client      on;
    ssl_verify_depth       1;
    ssl_crl                /etc/nginx/ca/crl.pem;

    # 0 disables the 1 MB default; without it every video 413s.
    client_max_body_size 0;
    # Stream bodies through instead of spooling whole files to proxy disk.
    proxy_request_buffering off;

    location /api/ {
        proxy_pass http://uploadd:8080;
        proxy_set_header X-Device-CN     $client_cert_cn;
        proxy_set_header X-Device-Serial $ssl_client_serial;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}
```

### 4.2 Admin listener — `nginx/admin.conf`

Same server certificate, **no** `ssl_verify_client`. Serves `admin/index.html`
at `/` and proxies `/enroll/` to `uploadd`. The file MUST open with:

```nginx
# ============================================================
# LAN-ONLY LISTENER. NEVER FORWARD PORT 8443 ON THE ROUTER.
# Anyone who can reach this port can enroll a device.
# ============================================================
```

`uploadd` distinguishes admin-origin requests by path prefix (`/enroll/*` and
`/admin/*` are proxied only by this listener; nginx on :443 proxies only
`/api/*`). `uploadd` must additionally reject `/api/*` requests that lack
`X-Device-CN` — its absence means nginx's mTLS layer was bypassed
(misconfiguration or direct access) and the request is unauthenticated.

---

## 5. Compose file

```yaml
services:
  nginx:
    image: nginx:1.27-alpine
    ports:
      - "443:443"
      - "8443:8443"   # LAN only — NEVER forward this port
    volumes:
      - ./nginx:/etc/nginx/conf.d:ro
      - ./ca:/etc/nginx/ca:ro
      - ./admin:/usr/share/nginx/admin:ro
    depends_on: [uploadd]
    restart: unless-stopped

  uploadd:
    build: ./uploadd
    # NO ports: mapping. This is a security control, not an oversight:
    # uploadd must be reachable only through nginx on the internal bridge.
    user: "${PUID}:${PGID}"
    environment:
      - LIBRARY_PATH=/library
      - DB_PATH=/state/uploadd.db
      - CA_DIR=/ca
    volumes:
      - ${LIBRARY_PATH}:/library      # the NAS network mount on the host
      - ./state:/state
      - ./ca:/ca                      # rw only during enrollment (mode a)
    restart: unless-stopped
```

`PUID`/`PGID` in `.env` MUST default to the IDs the user's Samba share expects,
and the README must lead with how to find them (`ls -ln` on an existing file in
the share). Wrong ownership is the top self-hosting support issue.

---

## 6. `uploadd` service

### 6.1 Responsibilities (exhaustive)

1. Serve enrollment endpoints (admin listener only).
2. Maintain the device registry.
3. Accept uploads, deduplicate by content hash, write to the library.
4. Nothing else. No auth of its own on `/api/*` beyond requiring `X-Device-CN`.

### 6.2 SQLite schema

```sql
CREATE TABLE devices (
  serial        TEXT PRIMARY KEY,
  cn            TEXT NOT NULL,
  label         TEXT NOT NULL,
  enrolled_at   INTEGER NOT NULL,
  last_seen_at  INTEGER,
  revoked_at    INTEGER,
  bytes_total   INTEGER NOT NULL DEFAULT 0,
  assets_total  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE pairing_tokens (
  token       TEXT PRIMARY KEY,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used_at     INTEGER
);

CREATE TABLE assets (
  sha256      TEXT PRIMARY KEY,
  path        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  device_cn   TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
```

### 6.3 Storage writer interface

```go
type AssetMeta struct {
    Sha256     string
    CapturedAt time.Time // client-supplied; server receive time if absent
    Filename   string
    MimeType   string
    Size       int64
    DeviceCN   string
}

type Store interface {
    Has(sha256 string) (bool, error)
    Put(sha256 string, meta AssetMeta, r io.Reader) (path string, err error)
}
```

v1 ships exactly one implementation, `LocalFSStore`. Do not build others; the
interface exists so WebDAV/S3 can be added later without touching handlers.

### 6.4 `LocalFSStore.Put` contract

1. Create temp file in `<library>/.incoming/` (same filesystem as the target).
2. Stream the body to it while computing SHA-256 incrementally.
3. On EOF, compare computed hash to the declared hash. Mismatch → delete temp,
   return the mismatch error (handler maps to `409`).
4. `fsync` the file, `rename` into final path, `fsync` the directory.
5. Final path: `photos/YYYY/MM/YYYY-MM-DD_HHMMSS_<first-6-hex-of-sha256><ext>`
   with date from `CapturedAt`. Extension derived from MIME type, falling back
   to the original filename's extension.
6. Insert the `assets` row and update the device's counters **in one
   transaction**, after the rename succeeds.

A partial upload must never leave a truncated file visible outside `.incoming/`.

---

## 7. API contract

All `/api/*` endpoints require mTLS (enforced by nginx) and the `X-Device-CN`
header (enforced by uploadd — 401 if absent). All bodies are JSON unless stated.
All timestamps are Unix seconds (UTC).

### 7.1 `GET /api/v1/health`

Response `200`:

```json
{ "version": "1.0.0", "device": "pixel-8-mark", "server_time": 1756500000 }
```

`device` echoes `X-Device-CN`. The app uses this to verify connectivity and
certificate validity.

### 7.2 `POST /api/v1/assets/check`

Bulk existence query. Request:

```json
{ "hashes": ["<hex sha256>", "..."] }
```

Response `200`:

```json
{ "have": ["<hex sha256>"], "want": ["<hex sha256>"] }
```

- Every input hash appears in exactly one of the two arrays.
- Batch cap: **500** hashes. `413` above that.
- Malformed hex or wrong length → `400`.

### 7.3 `POST /api/v1/assets`

Uploads one asset. Body is **raw bytes** — not multipart.

Request headers:

| Header | Required | Meaning |
|---|---|---|
| `X-Asset-Sha256` | yes | lowercase hex SHA-256 of the body |
| `X-Asset-Captured-At` | yes | Unix seconds; capture time from EXIF/MediaStore |
| `X-Asset-Filename` | yes | original display name, **percent-encoded UTF-8** (HTTP headers are ASCII-only; server must percent-decode) |
| `Content-Type` | yes | real MIME type |
| `Content-Length` | yes | body size in bytes |

Responses:

| Status | Meaning | Client behavior |
|---|---|---|
| `201` | stored | mark `UPLOADED` |
| `200` | already present; body may be discarded early | mark `UPLOADED` (success) |
| `400` | missing/malformed headers | `FAILED_PERMANENT` |
| `401` | `X-Device-CN` absent (misconfiguration) | retryable, surfaced |
| `409` | body hash ≠ declared hash | retry (recompute + resend) |
| `507` | out of disk space | retryable, surfaced to user |

The server computes the hash **while streaming** and compares before the final
rename; a mismatch discards the temp file. On `200` (duplicate), the server may
close early; the client must treat connection-reset-after-200 as success.

### 7.4 `POST /enroll/begin` — port 8443 only

Called by the admin page (button click), not the app. `uploadd -pair` mints an
identical payload and renders it as a QR in the terminal. Creates a pairing
token (single use, **TTL 10 minutes**) and returns the QR payload:

**Auth (added in 1.2):** HTTP Basic, user `admin`, password from
`ADMIN_PASSWORD` or generated on first run. Applies to `/enroll/begin`, `/qr`
and `/admin/*`. **Not** to `/enroll/complete` — the app calls that one, and it
already carries a single-use token that `/enroll/begin` only issues after auth.
`uploadd -pair` bypasses it: running it requires access to the server itself.

**Token format (changed in 1.2):** `XXXX-XXXX-XXXX` over the alphabet
`ABCDEFGHJKMNPQRSTVWXYZ23456789` — no `I`, `L`, `O`, `U`, `0`, `1`, and
critically no hyphen *inside* a group. The previous uppercased base64url
alphabet contained `-` and produced unreadable codes like `20XK-EHM--LKHH`.
Tokens are opaque to the client; this is a server-side generation rule.

```json
{
  "v": 1,
  "url": "https://nas.example.com",
  "ca_fingerprint": "SHA256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "ca_cert": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
  "token": "K7M2-9QXR-4TBH",
  "expires_at": 1756500600
}
```

- `ca_fingerprint` is `SHA256:` + lowercase hex SHA-256 of the CA cert **DER**.
- The admin page renders this JSON as a QR code and displays the fingerprint as
  text beside it — the user visually compares it to what the app shows after
  scanning. This comparison is the enrollment step's only defense.
- `url` is the public API base (port 443 implied). The app derives the
  enrollment host from the QR's origin: enrollment calls go to `<host>:8443`.

### 7.5 `POST /enroll/complete` — port 8443 only

Called by the app. Request:

```json
{
  "token": "K7M2-9QXR-4TBH",
  "label": "Pixel 8",
  "csr": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----"
}
```

Server behavior, in order:

1. Validate token exists, unexpired, unused. Failure → `403` with
   `{ "error": "token_expired" | "token_used" | "token_unknown" }`.
2. **Mark the token used atomically before signing** (UPDATE ... WHERE used_at
   IS NULL, check rows affected) — a race must burn the token, not double-sign.
3. Validate the CSR (parseable PKCS#10, EC P-256 key, valid self-signature).
   Failure → `400`.
4. Sign with the CA (25-year validity, EKU `clientAuth`, CN = sanitized label,
   unique serial). If `ca.key` is absent → `503` with
   `{ "error": "ca_key_absent" }` and the admin page explains mode (a).
5. Insert the device row. Return `201`:

```json
{
  "certificate": "-----BEGIN CERTIFICATE-----\n...",
  "ca_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "serial": "0x4a2f...",
  "expires_at": 2544800000
}
```

Label sanitization: CN = label lowercased, spaces → `-`, restricted to
`[a-z0-9-]`, max 64 chars, uniqued with a numeric suffix on collision.

---

## 8. Backend test requirements

From FRD §9.6 — the backend repo's integration suite must cover:

1. Full enrollment: CSR → signed cert → authenticated `/api/v1/health`.
2. Upload a real JPEG; bytes on disk are byte-identical.
3. Upload the same file twice → `200`, single file on disk.
4. Revoke + CRL regenerate + nginx reload → next request fails **at handshake**.
5. Expired pairing token rejected; reused token rejected; token race burns once.
6. `/api/` with no client cert → connection fails at TLS layer.
7. 500 MB upload succeeds (no 413).
8. Files owned by configured PUID/PGID.
9. Direct request to uploadd without `X-Device-CN` → `401`.
10. Hash-mismatch upload → `409`, no file outside `.incoming/`, temp cleaned.

---

## 9. Explicitly out of scope (v1)

iOS, web media browsing, albums/metadata, sharing/multi-user/quotas, chunked or
resumable single-file upload (tus is the designated v2 path — keep `Store` and
the HTTP handlers separable so a `tusd` sidecar can be added), download/sync,
OIDC, WebDAV/S3 backends, certificate renewal (D1 makes it unnecessary).
