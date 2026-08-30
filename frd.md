# Private Photo Backup — Engineering Handoff

**Version:** 1.0 (v1 scope)
**Target platform:** Android only
**Deliverables:** (1) a Docker Compose server stack, (2) a native Android application

---

## 1. What this is

A self-hosted photo and video backup system. The user runs a small container stack on their NAS. An Android app watches the device camera roll and uploads new media to that stack automatically, over the public internet, authenticated with a client certificate.

The defining constraint: **the server exposes exactly one port to the internet, and that port drops any connection that cannot present a certificate signed by the user's own certificate authority.** No password, no bearer token, no public certificate authority is trusted by either end.

### Why this design

Existing options each fail one requirement:

| Approach | Fails because |
|---|---|
| SMB over the internet | Port 445 is one of the most attacked ports in existence. Chatty, latency-sensitive, unusable on cellular. |
| System-wide VPN | Must be toggled on leaving home and off on arriving; conflicts with any other VPN; unreliable in practice. |
| HTTPS + password or token | A leaked or brute-forced credential grants access. Server responds to every scanner on the internet. |
| Public CA (Let's Encrypt) | Hostname is published to Certificate Transparency logs, which is a primary discovery vector for homelab domains. Requires port 80 or ACME DNS plumbing. |

Mutual TLS with a private CA solves all four. The private key never leaves the phone's secure element. Rejection happens during the TLS handshake, so the application layer is never reached by an unauthenticated party.

---

## 2. Scope

### In scope for v1

- Docker Compose stack: reverse proxy, upload service, CA tooling
- One-command CA bootstrap
- LAN-only device enrollment via QR code
- Certificate signing request flow with hardware-backed keys
- Android background detection of new camera media
- Reliable resumable-on-retry upload with deduplication
- Certificate revocation for a lost device
- Full automated test suite

### Explicitly out of scope for v1

- iOS
- Web UI for browsing uploaded media
- Album, face, or metadata organisation
- Sharing, multi-user accounts, quotas
- Chunked upload of individual files (whole-file with retry only; see §7.6)
- Download or two-way sync
- Play Store publication

### Deliberately deferred, but do not architect against

These are planned follow-ons. Keep interfaces clean enough to add them without a rewrite, but **do not build them now**:

- Alternative storage backends (WebDAV, S3-compatible)
- OIDC authentication as an alternative to mTLS
- Chunked and resumable single-file upload

Concretely: the upload transport lives behind an interface (§7.5) and the storage writer lives behind an interface (§6.4). That is the entire accommodation required.

---

## 3. Architecture

```
┌─────────────────────────────────────────────┐
│ Android device                              │
│                                             │
│  MediaStore ──> SyncWorker ──> UploadEngine │
│                     │              │        │
│                  Room DB      AndroidKeyStore
│                              (private key,  │
│                               non-extractable)
└──────────────────────┬──────────────────────┘
                       │ HTTPS + client cert
                       │ port 443
┌──────────────────────▼──────────────────────┐
│ Router — forwards ONLY 443                  │
└──────────────────────┬──────────────────────┘
                       │
┌──────────────────────▼──────────────────────┐
│ NAS                                         │
│                                             │
│  nginx :443   ssl_verify_client on          │
│    │          (rejects at handshake)        │
│    │ plain HTTP, internal bridge only       │
│    ▼                                        │
│  uploadd     writes files                   │
│    │                                        │
│    ▼                                        │
│  /library/photos  ◄── shared by existing    │
│                        Samba, LAN only      │
│                                             │
│  nginx :8443  enrollment + admin            │
│               NEVER forwarded               │
└─────────────────────────────────────────────┘
```

Port allocation is load-bearing:

- **443** — the mTLS API. The only port forwarded on the router.
- **8443** — enrollment and admin. Plain TLS, no client certificate required. Reachable on the LAN only because the user never forwards it. This is what makes enrollment possible without a chicken-and-egg certificate problem.

SMB is untouched. The upload service writes ordinary files to an ordinary directory that Samba already shares. Samba never learns that HTTPS was involved.

---

## 4. Threat model

### Defended against

| Threat | Mitigation |
|---|---|
| Internet-wide port scanning | Handshake fails without a client certificate. No application code runs. |
| Credential stuffing / brute force | There is no password to guess. |
| Stolen phone | Key is in hardware, gated behind device unlock. Certificate revocable via CRL. |
| Hostile or compromised public CA | Client trusts only the private CA. `withTrustedRoots = false`. |
| Corporate or ISP TLS interception | Same. An interception proxy's certificate is not signed by the private CA. |
| Passive discovery of the hostname | No Certificate Transparency entry, because no public CA is involved. |
| Lateral movement after proxy compromise | Proxy container has no route to the LAN; only to the upload service on an internal bridge. |

### Accepted residual risk

- Port 443 answers with a TLS ServerHello, so a scanner can tell *something* is listening. It cannot tell what, and cannot proceed.
- The attack surface is nginx's TLS terminator — small, heavily audited, but not zero.
- CA root key compromise is total compromise. Mitigation in §5.3.
- A rooted device with an unlocked bootloader may be able to invoke the key for signing (though not extract it). Out of scope.

### Non-negotiable security requirements

These are acceptance criteria, not suggestions:

1. Port 445 (SMB) must never be exposed or documented as exposable.
2. Port 8443 must never be forwarded, and the documentation must state this in bold.
3. The client must set `withTrustedRoots = false` — no public CA in the app's trust store.
4. The device private key must be generated inside AndroidKeyStore and must never be exportable.
5. The CA root key must not be stored inside the running container's writable volume.
6. The upload service must never be published to the host network — internal bridge only.
7. Pairing tokens must be single-use with a TTL of 10 minutes or less.
8. All certificate validation errors must fail closed. Never fall back to an unauthenticated path.

---

## 5. Server: setup and CA

### 5.1 Repository layout

```
server/
  docker-compose.yml
  .env.example
  nginx/
    api.conf              # :443, mTLS enforced
    admin.conf            # :8443, no client cert
  uploadd/
    Dockerfile
    main.go               # or main.py
    ...
  ca/
    bootstrap.sh          # one-command CA + server cert
    sign-csr.sh
    revoke.sh
  README.md
```

### 5.2 Bootstrap script

`ca/bootstrap.sh` must be runnable exactly once, with a single argument, and must be idempotent-safe (refuse to overwrite an existing CA):

```
./bootstrap.sh nas.example.com
```

It produces:

| File | Purpose | Where it must live |
|---|---|---|
| `ca.key` | CA root private key | **Offline.** Printed path with a loud warning to move it off the NAS. |
| `ca.crt` | CA certificate | Mounted read-only into nginx. Embedded in enrollment QR as a fingerprint. |
| `server.key` / `server.crt` | Server TLS identity, signed by the CA | Mounted read-only into nginx. |
| `crl.pem` | Empty initial revocation list | Mounted read-only into nginx. |

Parameters:

- CA: EC P-256, 10-year validity, `CA:TRUE, pathlen:0`, `keyCertSign, cRLSign`
- Server cert: EC P-256, 2-year validity, SAN must include both the DDNS hostname and the LAN IP, EKU `serverAuth`
- Device certs: EC P-256, 1-year validity, EKU `clientAuth`, CN set to the device label

Use `openssl` directly. Do not introduce a CA framework dependency for v1.

### 5.3 CA key handling

The script must, on completion, print an unmissable message instructing the user to move `ca.key` to offline storage and delete it from the NAS. Signing new devices requires temporarily restoring it. This is correct and should not be engineered away — a CA root key sitting permanently on an internet-connected NAS defeats the design.

Server operation does not require `ca.key`. Only `ca.crt` and `crl.pem` are needed at runtime.

### 5.4 nginx — API listener (`:443`)

```nginx
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

    client_max_body_size 0;
    proxy_request_buffering off;

    location /api/ {
        proxy_pass http://uploadd:8080;
        proxy_set_header X-Device-CN     $ssl_client_s_dn_cn;
        proxy_set_header X-Device-Serial $ssl_client_serial;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}
```

Notes that will otherwise cost the implementer a day each:

- `client_max_body_size 0` disables the 1 MB default. Without it every video fails with a 413.
- `proxy_request_buffering off` streams the body through instead of spooling the entire file to proxy disk first.
- TLS 1.3 only is acceptable here because both ends are controlled. It removes a large amount of legacy surface.

### 5.5 nginx — admin listener (`:8443`)

Same server certificate, **no** `ssl_verify_client`. Serves the enrollment endpoints and a minimal setup page. Must carry a comment stating that this port is never to be forwarded.

### 5.6 Compose

```yaml
services:
  nginx:
    image: nginx:1.27-alpine
    ports:
      - "443:443"
      - "8443:8443"
    volumes:
      - ./nginx:/etc/nginx/conf.d:ro
      - ./ca:/etc/nginx/ca:ro
    depends_on: [uploadd]
    restart: unless-stopped

  uploadd:
    build: ./uploadd
    # NO ports: section. Internal bridge only.
    environment:
      - LIBRARY_PATH=/library
      - DB_PATH=/state/devices.db
      - PUID=${PUID}
      - PGID=${PGID}
    volumes:
      - ${LIBRARY_PATH}:/library
      - ./state:/state
    restart: unless-stopped
```

`uploadd` having no `ports:` mapping is a security control, not an oversight. Add a comment saying so.

`PUID`/`PGID` must be settable and must default to the values that the user's Samba share expects. Files written with the wrong owner will land on the NAS in a state the user's desktop cannot open — this is the single most common self-hosted-container support complaint and it must be handled in v1.

---

## 6. Server: upload service (`uploadd`)

Language: Go preferred (single static binary, trivial container, strong stdlib HTTP). Python with FastAPI is acceptable.

### 6.1 Responsibilities

1. Serve enrollment endpoints on the admin path
2. Maintain a device registry
3. Accept uploads, deduplicate, and write to the library
4. Nothing else

It performs **no authentication of its own** for `/api/` routes. nginx has already guaranteed that any request arriving there presented a valid, unrevoked client certificate. The service reads `X-Device-CN` for attribution only. It must, however, reject requests where that header is absent — that indicates a misconfiguration in which the service is reachable directly.

### 6.2 Device registry

SQLite. One table:

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
```

### 6.3 Deduplication

Keyed on SHA-256 of file content, stored in a table:

```sql
CREATE TABLE assets (
  sha256      TEXT PRIMARY KEY,
  path        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  device_cn   TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
```

A client can ask which of a batch of hashes the server already holds, and skip those entirely. This is what makes a re-install or a factory reset cheap rather than catastrophic.

### 6.4 Storage writer interface

Write behind a small interface so a future WebDAV or S3 backend can be dropped in:

```go
type Store interface {
    Has(sha256 string) (bool, error)
    Put(sha256 string, meta AssetMeta, r io.Reader) (path string, err error)
}
```

v1 ships exactly one implementation, `LocalFSStore`. Do not build others.

### 6.5 File layout on disk

```
/library/photos/2026/08/2026-08-30_142317_a3f9c1.jpg
```

Date comes from the client-supplied capture timestamp, falling back to server receive time. The short hex suffix is the first 6 characters of the SHA-256 and guarantees no collision on identical filenames from different devices.

Write to a temporary file in the same filesystem, `fsync`, then `rename` into place. A partial upload must never leave a truncated file visible to Samba.

---

## 7. API specification

### 7.1 `GET /api/v1/health`

mTLS required. Returns `200` with server version and the authenticated device CN. Used by the app to verify connectivity and certificate validity.

```json
{ "version": "1.0.0", "device": "pixel-8-mark", "server_time": 1756... }
```

### 7.2 `POST /api/v1/assets/check`

mTLS required. Bulk existence query.

Request:
```json
{ "hashes": ["a3f9...", "b721...", ...] }
```

Response:
```json
{ "have": ["a3f9..."], "want": ["b721..."] }
```

Cap the batch at 500 hashes. Return `413` above that.

### 7.3 `POST /api/v1/assets`

mTLS required. Uploads one asset.

Headers:
- `X-Asset-Sha256` — hex SHA-256 of the body
- `X-Asset-Captured-At` — Unix seconds, capture time from EXIF or MediaStore
- `X-Asset-Filename` — original display name
- `Content-Type` — the real MIME type
- `Content-Length` — required

Body: raw bytes. Not multipart. Multipart adds encoding overhead and buys nothing here.

Responses:
- `201` — stored
- `200` — already present, body discarded (client treats as success)
- `400` — missing or malformed headers
- `409` — body hash did not match `X-Asset-Sha256`; client must retry
- `507` — out of disk space

The server must compute the hash while streaming and compare before the final rename. A mismatch discards the temporary file.

### 7.4 `POST /enroll/begin` (port 8443, no client cert)

Called by the admin page, not the app. Generates a pairing token and returns the QR payload.

```json
{
  "url": "https://nas.example.com",
  "ca_fingerprint": "SHA256:9f86d0...",
  "ca_cert": "-----BEGIN CERTIFICATE-----\n...",
  "token": "K7M2-9QXR-4TBL",
  "expires_at": 1756...
}
```

The full CA certificate is included so the app can pin without a second round trip. The fingerprint is displayed on screen so the user can visually confirm it matches what the app shows after scanning — this defends the enrollment step itself.

### 7.5 `POST /enroll/complete` (port 8443, no client cert)

Called by the app.

Request:
```json
{
  "token": "K7M2-9QXR-4TBL",
  "label": "Pixel 8",
  "csr": "-----BEGIN CERTIFICATE REQUEST-----\n..."
}
```

Server: validates the token is unexpired and unused, marks it used **atomically before signing**, signs the CSR with the CA, inserts the device row, returns the chain.

```json
{
  "certificate": "-----BEGIN CERTIFICATE-----\n...",
  "ca_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "serial": "0x4a2f...",
  "expires_at": 1788...
}
```

Signing requires `ca.key`, which lives offline per §5.3. Two acceptable resolutions — pick one and document it:

- **(a)** The admin page instructs the user to temporarily place `ca.key` in the CA directory before enrolling a device, and remove it after.
- **(b)** `enroll/complete` writes the CSR to `./state/pending/` and the user runs `ca/sign-csr.sh` on a machine holding the key, then the app polls for the result.

**(a)** is far better UX and is the recommended default. **(b)** should exist as a documented option for users who want the key to never touch the NAS.

### 7.6 On resumability

v1 uploads whole files with retry. A failed transfer restarts from zero. This is acceptable because photos are small and the retry is automatic and invisible.

It is **not** acceptable for large videos on cellular. Note this as the top v2 item. When it is built, adopt the **tus** protocol rather than inventing chunking — it is a mature open standard with a maintained server (`tusd`) and existing Kotlin clients. Design `UploadTransport` (§8.5) so tus can be added as a second implementation.

---

## 8. Android client

### 8.1 Stack

- **Native Kotlin. Not Flutter.** Every hard component is Android-platform-specific: WorkManager, MediaStore, foreground services, AndroidKeyStore, OkHttp's `X509KeyManager`. A Flutter layer would add a platform-channel boundary that must be mocked in every reliability test while contributing nothing, and the app is Android-only by decision.
- Jetpack Compose for UI
- WorkManager for scheduling
- Room for state
- OkHttp for transport
- Hilt for injection (matters for test substitution)
- Kotlin coroutines and Flow
- BouncyCastle (`bcpkix-jdk18on`) for CSR generation only
- ZXing or ML Kit for QR scanning
- minSdk 26, targetSdk 35

### 8.2 Module structure

```
:app            Compose UI, navigation
:core:data      Room, DataStore, repositories
:core:media     MediaStore access behind an interface
:core:network   OkHttp, mTLS, API client
:core:crypto    AndroidKeyStore, CSR, cert storage
:sync           WorkManager workers, upload orchestration
:core:testing   Fakes, fixtures, test rules
```

Module boundaries exist so tests can substitute `:core:media` and `:core:network` with fakes. This is the main structural requirement for testability.

### 8.3 Enrollment flow

1. User opens the app, taps *Pair with server*.
2. App requests camera permission, scans the QR from the admin page.
3. App displays the CA fingerprint from the payload. **User must confirm it matches the fingerprint shown on the admin page.** Do not skip this — it is the only protection for the enrollment step.
4. App generates an EC P-256 keypair inside AndroidKeyStore:

```kotlin
val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    .setUserAuthenticationRequired(false)
    .setIsStrongBoxBacked(true)   // catch StrongBoxUnavailableException, retry false
    .build()
```

`setUserAuthenticationRequired(false)` is required — background upload cannot prompt for unlock. StrongBox must be attempted first and gracefully fall back to TEE.

5. App builds a PKCS#10 CSR with BouncyCastle, signing with the KeyStore-resident key.
6. App POSTs to `{url}:8443/enroll/complete`, trusting only the CA from the QR payload.
7. App stores the returned certificate chain in encrypted DataStore. The private key never leaves the keystore.

### 8.4 mTLS client construction

```kotlin
val keyManager = object : X509ExtendedKeyManager() {
    override fun getPrivateKey(alias: String) =
        keyStore.getKey(KEY_ALIAS, null) as PrivateKey
    override fun getCertificateChain(alias: String) = storedChain
    override fun chooseClientAlias(k: Array<String>?, i: Array<Principal>?, s: Socket?) = KEY_ALIAS
    // remaining members: return null / empty
}

val trustManager = trustManagerFor(storedCaCertificate)  // ONLY the private CA

val ctx = SSLContext.getInstance("TLS").apply {
    init(arrayOf(keyManager), arrayOf(trustManager), null)
}

OkHttpClient.Builder()
    .sslSocketFactory(ctx.socketFactory, trustManager)
    .connectTimeout(15, SECONDS)
    .writeTimeout(0, SECONDS)     // large uploads
    .retryOnConnectionFailure(true)
    .build()
```

The trust manager must contain **only** the private CA. Never merge with the platform trust store.

### 8.5 Transport interface

```kotlin
interface UploadTransport {
    suspend fun health(): HealthResult
    suspend fun check(hashes: List<String>): CheckResult
    suspend fun upload(asset: LocalAsset, progress: (Long) -> Unit): UploadResult
}
```

One implementation in v1: `MtlsHttpTransport`. Tests use `FakeTransport`.

### 8.6 Media detection

Primary trigger — WorkManager content URI observation:

```kotlin
val constraints = Constraints.Builder()
    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
    .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
```

Critical detail: **content-trigger constraints only work on `OneTimeWorkRequest`, and the worker must re-enqueue itself at the end of every run.** If it does not, detection silently stops after the first photo. This is the single most common implementation bug in this class of app.

The update delay batches a burst of twenty shots into one run instead of twenty.

Safety net — a `PeriodicWorkRequest` every 6 hours that performs a full reconciliation scan. Content triggers do not survive force-stop or aggressive OEM task-killing, and the periodic worker recovers the backlog.

Cheap change detection on API 30+:

```kotlin
val gen = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
if (gen == lastKnownGeneration) return Result.success()
```

Query with `DATE_ADDED > ?`. Persist the cursor in DataStore. **Never key on file path** — the `DATA` column has been unreliable since scoped storage. Key on MediaStore `_ID` plus content hash.

Read bytes via `contentResolver.openInputStream(uri)`.

### 8.7 State machine

Room entity:

```kotlin
@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val mediaStoreId: Long,
    val sha256: String?,
    val sizeBytes: Long,
    val capturedAt: Long,
    val displayName: String,
    val mimeType: String,
    val state: AssetState,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val lastError: String?
)

enum class AssetState {
    DISCOVERED, HASHED, SKIPPED_REMOTE_HAS, UPLOADING,
    UPLOADED, FAILED_RETRYABLE, FAILED_PERMANENT
}
```

Transition rules:

- `DISCOVERED → HASHED` after SHA-256 computation
- `HASHED → SKIPPED_REMOTE_HAS` if `/assets/check` reports the server already has it
- `HASHED → UPLOADING → UPLOADED` on success
- Any state `→ FAILED_RETRYABLE` on network error, 5xx, or 409; exponential backoff, cap at 10 attempts
- `→ FAILED_PERMANENT` on 400, or after the attempt cap; surfaced in the UI with a manual retry action
- `UPLOADING` on app start with a stale `lastAttemptAt` is reset to `HASHED` (recovers from process death mid-upload)

### 8.8 Foreground service

Uploads run inside a WorkManager long-running worker promoted to foreground:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge"/>
```

**Android 15 caps `dataSync` foreground services at 6 hours per 24-hour period.** Fine for incremental sync; fatal for a first-run backlog of 40,000 photos. The initial backfill must therefore be chunked across multiple worker runs with progress persisted in Room, never attempted as one long service.

Notification shows count remaining and current file, with a pause action.

### 8.9 Permissions

| SDK | Permission |
|---|---|
| ≤ 32 | `READ_EXTERNAL_STORAGE` |
| 33+ | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |
| 34+ | Must detect `READ_MEDIA_VISUAL_USER_SELECTED` |

Partial media access on Android 14+ is a silent failure mode: the app appears to work but only ever sees a handful of user-picked photos. Detect it explicitly and show a blocking explanation screen directing the user to full access. Do not let the app run in a state where it looks like it is syncing but is not.

### 8.10 OEM battery management

Xiaomi, Samsung, OnePlus, Oppo, and Huawei terminate background work far more aggressively than stock Android, and correct WorkManager usage does not save you.

Required:

- Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` during onboarding, with a clear explanation
- Detect the manufacturer and deep-link into the OEM autostart settings screen where an intent is known
- Track the timestamp of the last successful sync; if it exceeds 24 hours, show an in-app warning with troubleshooting steps

Consult `dontkillmyapp.com` for per-vendor intents. Budget real time for this — it is the difference between an app that works and an app that gets one-star reviews.

---

## 9. Testing

The app is a background process handling irreplaceable data. It must be tested as such.

### 9.1 Unit — JUnit 5, MockK, Turbine

- State machine transitions, exhaustively, including every illegal transition
- Backoff schedule computation
- Filename and path derivation, including collisions and unicode names
- Hash computation against known vectors
- CSR generation produces a structurally valid PKCS#10
- Error mapping: HTTP status → `AssetState`

### 9.2 Room — in-memory database

- Migrations, every version pair
- Concurrent writes from multiple workers
- Cursor persistence survives process restart
- Query correctness with 100,000 rows

### 9.3 MediaStore — Robolectric

`:core:media` exposes `MediaSource`; production is `MediaStoreSource`, tests use `FakeMediaSource`. Cover:

- Empty library
- Single new photo
- Burst of 50 photos with identical `DATE_ADDED`
- Photo deleted between discovery and upload
- Permission revoked mid-scan
- Partial media access granted
- Clock moved backwards (`DATE_ADDED` regression)

### 9.4 WorkManager — `androidx.work:work-testing`

Use `TestDriver` to simulate constraint satisfaction:

```kotlin
testDriver.setAllConstraintsMet(request.id)
testDriver.setInitialDelayMet(request.id)
```

Must cover:

- **Worker re-enqueues itself** — assert directly, this is the highest-value single test in the suite
- Periodic reconciliation catches assets the content trigger missed
- Concurrent workers do not double-upload the same asset
- Worker cancellation mid-upload leaves recoverable state

### 9.5 Transport — MockWebServer

- 201, 200, 400, 409, 507, 500 each map to the correct state
- Connection dropped at 50% of body
- TLS handshake failure with a wrong client certificate
- **Server presents a certificate from a public CA → must be rejected.** Non-negotiable test.
- Slow-loris server, timeouts fire correctly

### 9.6 Integration — Testcontainers

Run the real Compose stack. Real nginx, real `uploadd`, real certificates generated by `bootstrap.sh` in test setup. Exercise the real OkHttp client against it.

- Full enrollment: CSR → signed cert → authenticated `/health`
- Upload a real JPEG, assert bytes on disk are byte-identical
- Upload the same file twice, assert `200` and a single file on disk
- Revoke the certificate, regenerate the CRL, reload nginx, assert the next request fails at handshake
- Expired pairing token rejected
- Pairing token reuse rejected
- Request to `/api/` with no client certificate → connection refused at TLS layer
- 500 MB file uploads without a 413 (validates `client_max_body_size`)
- File written with the configured PUID/PGID

### 9.7 End-to-end — instrumented, emulator

Inject images via `MediaStore.Images.Media.insertImage` or a `ContentResolver` write, then assert files appear in the container volume. Run on API 26, 30, 33, 34, 35.

### 9.8 Chaos and reliability

These are the tests that decide whether the app is trustworthy:

- Airplane mode toggled mid-upload → resumes without duplication
- Process killed mid-upload → `UPLOADING` reset to `HASHED` on next run, no duplicate
- Device storage full during hash computation
- Server disk full → `507` → retryable, surfaced to user, recovers when space is freed
- Certificate expired → clear actionable error, not a silent stall
- Server unreachable for 7 days → backlog uploads correctly on reconnection
- 10,000-asset backfill across simulated foreground-service interruptions
- Clock skew of ±48 hours between device and server
- Two devices uploading identical photos concurrently → one file, both marked uploaded
- App upgraded mid-backlog → state survives

### 9.9 CI

GitHub Actions. Unit and Robolectric on every push. Testcontainers integration and instrumented emulator tests on pull requests. Fail the build below 80% line coverage on `:sync`, `:core:data`, and `:core:crypto`.

---

## 10. Build order

Each phase ends with tests passing and something demonstrable.

| Phase | Deliverable | Done when |
|---|---|---|
| 1 | CA bootstrap + nginx + stub `uploadd` | `curl` with a manually generated client cert reaches `/health`; without one, the handshake fails |
| 2 | `uploadd` upload, dedupe, storage | Integration tests in §9.6 pass |
| 3 | Enrollment endpoints + admin page | QR generated; a `curl`-driven CSR returns a valid signed certificate |
| 4 | Android crypto and enrollment | App pairs; `/health` returns 200 with the device CN |
| 5 | MediaStore detection and Room | Photos appear as `DISCOVERED` and `HASHED`; §9.3 and §9.4 pass |
| 6 | Upload engine and foreground service | Photo taken on device lands on the NAS within a minute |
| 7 | Backfill, error UI, OEM handling | §9.8 chaos suite passes |
| 8 | Revocation, cert renewal, polish | Full suite green on all target API levels |

---

## 11. Acceptance criteria

The system is complete when all of the following hold:

1. A photo taken on the device appears on the NAS within 2 minutes on a normal network.
2. A 40,000-photo backfill completes without user intervention, surviving reboots and foreground-service interruptions.
3. `nmap` against the public IP shows only 443 open; a request without a client certificate fails during the handshake.
4. The app rejects a server presenting a valid public-CA certificate for the same hostname.
5. Revoking a device certificate blocks it on the next request.
6. Killing the app mid-upload produces no duplicate and no corrupt file.
7. Files land on the NAS with correct ownership and are readable over the existing Samba share without intervention.
8. Full test suite green on API 26, 30, 33, 34, and 35.
9. `ca.key` is not required for and not present during normal server operation.

---

## 12. Open decisions for the implementer

1. **Enrollment signing mode** — §7.5(a) or (b). Recommendation: implement (a), document (b).
2. **`uploadd` language** — Go recommended. Python/FastAPI acceptable if the implementer is faster in it.
3. **Certificate renewal** — v1 may require re-pairing at 1-year expiry. An in-band renewal endpoint over existing mTLS is better and is a small addition; build it if time allows, otherwise warn the user at 30 days remaining.
4. **Video handling** — v1 treats videos identically to photos. If whole-file upload of multi-gigabyte videos proves unworkable in testing, pull tus forward from v2 rather than inventing chunking.