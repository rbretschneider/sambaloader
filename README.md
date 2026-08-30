# Sambaloader

Android app that automatically backs up new photos and videos to a self-hosted
server over mutual TLS, which writes them into a NAS directory shared by an
existing Samba server. Android-only, native Kotlin.

The server stack lives in its own repo and is fully specified here in
[docs/SERVER_SPEC.md](docs/SERVER_SPEC.md). A reference dev harness for
integration testing arrives in this repo at Milestone 5.

## Documents

| Doc | Purpose |
|---|---|
| [frd.md](frd.md) | Formal requirements (v1 scope, threat model, acceptance criteria) |
| [docs/MILESTONES.md](docs/MILESTONES.md) | Milestone plan, stories, per-story acceptance criteria |
| [docs/SERVER_SPEC.md](docs/SERVER_SPEC.md) | Frozen backend contract — the app is built against this |

## Security model in one paragraph

The server exposes exactly one port (443). nginx drops any connection that
cannot present a certificate signed by the user's private CA — during the TLS
handshake, before application code runs. The device's private key is generated
inside AndroidKeyStore and never leaves the hardware. The app trusts only the
private CA — never the platform trust store. Certificates are long-lived
(decades, WireGuard-key semantics); a lost device is handled by CRL revocation,
not expiry.

## Building

Requires JDK 17–21 (Android Studio's bundled JBR works; the Studio IDE uses it
automatically) and the Android SDK (platform 35).

```
gradlew assembleDebug
gradlew testDebugUnitTest        # unit tests, JUnit 5
gradlew detekt                   # static analysis
gradlew koverVerifyDebug         # 80% coverage gate on :sync, :core:data, :core:crypto
```

## Module map

```
:app            Compose UI, navigation, Hilt entry point
:core:data      Room, DataStore, repositories, asset state machine
:core:media     MediaStore access behind the MediaSource interface
:core:network   OkHttp, mTLS, API client (UploadTransport)
:core:crypto    AndroidKeyStore, CSR generation, hashing
:sync           WorkManager workers, upload orchestration, backoff
:core:testing   Fakes, fixtures, testdata manifest access
```

## Test data

`testdata/` holds a small committed corpus of JPEGs (including a unicode
filename) with known SHA-256 hashes pinned in `testdata/manifest.json` and
guarded by `TestDataManifestTest`. Regenerate with
`tools/generate-testdata.ps1` (changes every hash — only when the corpus
itself must change).

Seed a connected device or emulator's camera roll:

```
./tools/seed-media.ps1 -Count 10
```

Add `-IncludeVideo` to also record a real MP4 on the device, `-Serial <id>`
when several devices are connected.
