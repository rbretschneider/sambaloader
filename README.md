# Sambaloader

Your phone's photos, backed up to your own NAS, automatically — over a
connection nobody else can open.

Android app + self-hosted server. New photos and videos upload the moment
you take them, from anywhere, authenticated by **mutual TLS** against a
private CA you own. No cloud account, no subscription, no third party.

Files land as plain files in a directory your existing Samba server
shares. Nothing proprietary: if you delete this app tomorrow, your photos
are still sitting there as ordinary JPEGs.

**License:** MIT · **Platform:** Android 8+ · **Server:** Docker Compose

---

## Setup

Two steps. Really.

### 1. The server

```bash
git clone https://github.com/rbretschneider/sambaloader.git
cd sambaloader/server
cp .env.example .env      # set PUBLIC_URL and EXTRA_SANS
docker compose up -d
```

On first start the server generates its own certificate authority. There
is no script to run, no `openssl` on your machine, and **nothing to copy
to your phone**.

Only `PUBLIC_URL` really needs editing — the hostname your phone will
dial. Set `EXTRA_SANS` to your server's LAN IP too, so pairing works
before DNS does.

### 2. The app

**[⬇ Download the latest APK](https://github.com/rbretschneider/sambaloader/releases/latest/download/sambaloader.apk)**

Android will ask you to allow installing from your browser or file
manager — that permission is per-app and you can revoke it afterwards.

Then pair:

```bash
docker compose exec uploadd /uploadd -pair
```

That prints a QR code straight into your terminal. Open the app, tap
**Pair with server**, scan it, and check that the fingerprint the app
shows matches the one printed above it. Done.

That fingerprint check is the whole security ceremony. The QR carries
your CA and its fingerprint together, and the app refuses any CA whose
fingerprint does not match — which is why no certificate file ever has
to travel by USB stick.

### Then what

The app backs up your camera folders by default. In **Settings** you can
choose folders, hold new photos for a few minutes before they upload (so
a bad shot can be deleted first), decide when mobile data may be used,
and optionally delete local copies once the server confirms it has them.

The home screen shows a **Device permissions** card. Android will
silently throttle background uploads unless you grant unrestricted
battery use, so fix anything red there — it is one tap each.

You can also share images into Sambaloader from any app: **Share →
Back up to server** works from your gallery, email, or a chat.

---

## How it protects you

Exactly one port faces the internet: **443**. nginx drops any connection
that cannot present a certificate signed by your private CA — during the
TLS handshake, before a single line of application code runs. Scanners
and bots see a closed door.

Your phone's private key is generated inside AndroidKeyStore, hardware
backed where available, and **cannot be extracted** — not by this app,
not by malware, not by someone with your unlocked phone.

The app trusts **only** your CA. Never the platform trust store. A
compromised public certificate authority cannot impersonate your server.

Certificates are long-lived on purpose — decades, like WireGuard keys.
Enrol a phone once, ever. A lost phone is handled by revoking it, not by
waiting for something to expire:

```bash
docker compose exec uploadd /uploadd -revoke <serial>
```

It stops working within seconds.

**The enrollment page is password-protected** and must never be exposed
to the internet. It lives on port 8443, bound to your LAN, and anything
that can reach it plus the password can add a device.

---

## What it does not do

Being straight about the edges, so you can decide before installing:

- **Android only.** No iOS, no plans for it.
- **One shared admin password**, not user accounts. Anyone holding it can
  enroll a device. Fine for a household; not an identity system.
- **`ca.key` lives on the server** by default, because the alternative was
  shuffling a file around for every enrollment. Read access to it is
  enough to mint a device certificate. You can move it offline; see
  [server/README.md](server/README.md).
- **Whole-file uploads.** An interrupted 2 GB video restarts rather than
  resuming. Resumable upload is a v2 item.
- **Facebook-style "share album" links do not work** — those share a URL,
  not the images. Multi-selecting photos and sharing those does.

---

## Documentation

| Doc | What it covers |
|---|---|
| [server/README.md](server/README.md) | Running the server, own-nginx mode, NFS vs SMB, revocation |
| [docs/SERVER_SPEC.md](docs/SERVER_SPEC.md) | The full backend contract — build your own server against it |
| [docs/MILESTONES.md](docs/MILESTONES.md) | Plan of record, and the decisions (D1–D8) behind the design |
| [frd.md](frd.md) | Original requirements, threat model, acceptance criteria |

---

## Building from source

Requires JDK 17–21 (Android Studio's bundled JBR works) and Android SDK
platform 35.

```
gradlew assembleDebug
gradlew testDebugUnitTest        # unit tests
gradlew detekt                   # static analysis
gradlew koverVerifyDebug         # 80% coverage gate on :sync, :core:data, :core:crypto
```

Release builds need signing config in `key.properties`; without it,
`assembleDebug` is all you need.

### Module map

```
:app            Compose UI, navigation, Hilt entry point
:core:data      Room, DataStore, repositories, asset state machine
:core:media     MediaStore access behind the MediaSource interface
:core:network   OkHttp, mTLS, API client (UploadTransport)
:core:crypto    AndroidKeyStore, CSR generation, hashing
:core:system    OS permission and battery-exemption checks
:sync           WorkManager workers, upload orchestration, backoff
:core:testing   Fakes, fixtures, testdata manifest access
server/         Go upload service + nginx + compose stack
```

### Test data

`testdata/` holds a small committed corpus of JPEGs (including a unicode
filename) with SHA-256 hashes pinned in `testdata/manifest.json` and
guarded by `TestDataManifestTest`. Regenerate with
`tools/generate-testdata.ps1` — it changes every hash, so only when the
corpus itself must change.

Seed a connected device or emulator's camera roll:

```
./tools/seed-media.ps1 -Count 10
```

Add `-IncludeVideo` to record a real MP4 on the device, `-Serial <id>`
when several devices are connected.
