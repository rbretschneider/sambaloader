# Sambaloader — Milestone Plan

**Source:** [frd.md](../frd.md) v1.0
**Repo scope:** Android app only. The server stack is *specified* here (M0) and a minimal **dev server harness** lives in this repo for testing; the production backend is built in a separate repo from that spec.
**Sequencing:** App-first against fakes. The API contract is frozen in M0 so app and backend can proceed independently.

---

## Decisions locked for this plan

These amend or resolve open items in the FRD:

| # | Decision | Rationale |
|---|---|---|
| D1 | **Long-lived certificates: CA root 30 years, server cert 10 years, device certs 25 years.** No renewal flow in v1 (or v2). | Enroll a phone once, ever — WireGuard-key semantics. A private CA has no browser/CT policy forcing short expiry. Revocation via CRL remains the kill switch for a lost device, and is the *only* lifecycle event we handle. FRD §5.2 validity params and §12.3 (renewal) are superseded. |
| D2 | Enrollment signing mode **(a)**: user temporarily places `ca.key` in the CA dir to enroll, removes it after. Mode (b) documented as an option in the server spec. | FRD recommendation. With D1, enrollment happens once per device — the ceremony is rare enough that (a)'s UX cost is near zero. |
| D3 | `uploadd` specified in **Go**. | FRD recommendation; single static binary. |
| D4 | Videos treated identically to photos; whole-file upload with retry. tus deferred to v2. `UploadTransport` interface keeps the seam. | FRD recommendation. |
| D5 | **Container host ≠ NAS.** The compose stack runs on a separate server; the NAS library is passed in as a volume (network mount). | User's actual deployment. Consequences: `Put()` must do temp-write + `fsync` + `rename` *within the mounted filesystem*; the spec must warn that atomic rename is only guaranteed on NFS (SMB mounts rename non-atomically) and must document PUID/PGID mapping through the mount. |
| D6 | Dev/test environment: physical Android device (sideload), Android emulator on this Windows PC, Docker Desktop available. | Shapes every "How you test it" section below. |

---

## Milestone map

```
M0  Contract & scaffolding      ──  spec doc frozen, empty app builds, CI green
M1  Crypto & identity           ──  keystore keys, CSR, mTLS client (vs MockWebServer)
M2  Enrollment                  ──  QR → pair → /health OK (vs dev harness)
M3  Media detection & state     ──  photos become DISCOVERED/HASHED in Room
M4  Upload engine               ──  photo → fake server, full state machine
M5  Dev server harness & integration ── real nginx+mTLS in Docker, E2E emulator test
M6  Backfill & resilience       ──  chaos suite, OEM handling, partial-access guard
M7  Revocation & release polish ──  full matrix green, sideloadable release build
```

Each story lists **AC** (acceptance criteria — a story is not done until these hold and its tests pass) and **How you test it** (manual steps for a human, per D6).

---

## M0 — Contract & scaffolding

Goal: freeze the API/server contract so backend work can start elsewhere; stand up a testable, CI-gated Android skeleton.

### S0.1 — Server container specification document
Write `docs/SERVER_SPEC.md`: the complete definition of the backend repo. Contents: compose file (nginx + uploadd, internal bridge, no `ports:` on uploadd), both nginx configs verbatim (FRD §5.4/§5.5 with the `client_max_body_size 0` / `proxy_request_buffering off` notes), CA scripts (`bootstrap.sh`, `sign-csr.sh`, `revoke.sh`) with **D1 validity periods**, full API spec (§7.1–7.5, unchanged), device-registry and asset schemas (§6.2/§6.3), `Store` interface (§6.4), file layout (§6.5), and a **deployment section for D5**: separate-host topology diagram, NFS-vs-SMB mount guidance (atomic rename caveat), PUID/PGID mapping, and the non-negotiable security requirements (§4) restated as the backend's acceptance criteria.
- **AC:** A developer with no access to this conversation could build the backend from the doc alone. Every request/response has an example. D1/D2/D5 decisions embedded. The eight §4 security requirements appear as a checklist.
- **How you test it:** Read it. Try to find a question the backend implementer would have to ask; if you find one, the story reopens.

### S0.2 — Android project scaffolding
Gradle project with the FRD §8.2 module structure (`:app`, `:core:data`, `:core:media`, `:core:network`, `:core:crypto`, `:sync`, `:core:testing`), Kotlin, Compose, Hilt, version catalog, minSdk 26 / targetSdk 35. Placeholder single-screen app that builds and launches.
- **AC:** `gradlew assembleDebug` succeeds; app installs and shows a home screen on emulator and physical device; each module has one passing placeholder JUnit 5 test; Hilt wired end-to-end (one injected dependency reaches the UI).
- **How you test it:** `gradlew installDebug` on the emulator, open the app.

### S0.3 — CI and test infrastructure
GitHub Actions: unit + Robolectric on every push; lint/detekt; coverage via Kover with the 80% gate on `:sync`, `:core:data`, `:core:crypto` (gate activates per-module as each gains real code — configured now, enforced from M1 on `:core:crypto`). `:core:testing` module created with fixture scaffolding.
- **AC:** Pushing a commit runs the workflow; a deliberately failing test blocks it; coverage report published as a build artifact.
- **How you test it:** Look at the Actions tab after the first push.

### S0.4 — Test data seeding tools
Scripts a human can run: `tools/seed-media.ps1` (pushes a bundled set of test photos/videos to a connected device/emulator via `adb push` + `MediaScanner` broadcast, with flags for count, burst-same-timestamp, and large-video variants) and a checked-in `testdata/` set (small JPEGs with known SHA-256s and EXIF dates, a unicode-named file, a short MP4). Known hashes recorded in `testdata/manifest.json` for use as unit-test vectors.
- **AC:** Running the script against a booted emulator makes N photos appear in Google Photos/Gallery within seconds; manifest hashes verified by a unit test in `:core:testing`.
- **How you test it:** `./tools/seed-media.ps1 -Count 10` against the emulator, open the gallery app.

---

## M1 — Crypto & identity

Goal: the app can mint a hardware-backed identity and speak mTLS that trusts *only* a private CA. All against MockWebServer + locally generated test certs — no server needed.

### S1.1 — Dev PKI generator
`tools/dev-pki/` — openssl scripts that generate a throwaway test CA, server cert, and pre-signed client cert (D1 validity periods), used by MockWebServer tests and later by the M5 harness. This is also the first executable draft of the spec's `bootstrap.sh`, kept behaviorally identical to S0.1's spec.
- **AC:** One command produces ca/server/client materials; a JVM test loads them and completes a local mTLS handshake; refuses to overwrite an existing CA.
- **How you test it:** Run the script, see the files, run the test.

### S1.2 — Keystore keypair + CSR generation (`:core:crypto`)
EC P-256 in AndroidKeyStore per FRD §8.3: StrongBox attempted, TEE fallback, `setUserAuthenticationRequired(false)`, non-exportable. BouncyCastle PKCS#10 CSR signed by the keystore key. Behind an interface so JVM tests can use a software keystore.
- **AC:** CSR parses as structurally valid PKCS#10 with correct subject/key (unit-tested against BC's verifier); StrongBox fallback path unit-tested via injected capability flag; instrumented smoke test on device proves key is non-extractable (`getEncoded()` returns null).
- **How you test it:** Debug screen button "Generate identity" shows the public key fingerprint and whether StrongBox or TEE was used — run on your physical device.

### S1.3 — Certificate storage
Encrypted DataStore holding the device cert chain, CA cert, and server URL. Typed repository in `:core:data`.
- **AC:** Round-trip persistence test; survives process restart (instrumented); absent-state modeled explicitly (app knows "not enrolled").
- **How you test it:** Covered by S1.2's debug screen state surviving app kill.

### S1.4 — mTLS OkHttp client (`:core:network`)
FRD §8.4: custom `X509ExtendedKeyManager` over the keystore key, trust manager containing **only** the private CA, TLS 1.3, timeouts per spec. `UploadTransport` interface defined; `MtlsHttpTransport` implements `health()` only for now; `FakeTransport` in `:core:testing`.
- **AC (all vs MockWebServer with S1.1 certs):** handshake succeeds with correct client cert; server rejecting the client cert surfaces a typed error; **server presenting a public-CA-signed cert for the right hostname is rejected** (the FRD's non-negotiable test); wrong private-CA server cert rejected; no fallback to platform trust store under any error (fail-closed test).
- **How you test it:** `gradlew :core:network:test` — plus the M2 debug screen exercises it live.

---

## M2 — Enrollment

Goal: scan QR → confirm fingerprint → CSR → store cert → green `/health`. Tested against MockWebServer now; against the real harness in M5.

### S2.1 — Enrollment API client
`/enroll/complete` call (port 8443, trusting only the QR-supplied CA), response parsing, error taxonomy (expired token, used token, network, fingerprint mismatch).
- **AC:** MockWebServer tests for success + each error; the client refuses to proceed if the presented server cert isn't signed by the QR's CA.
- **How you test it:** Unit tests; live in S2.3.

### S2.2 — QR scan + fingerprint confirmation UI
Camera permission flow, ML Kit (or ZXing) scan of the enrollment payload, then a **mandatory** screen showing the CA fingerprint that the user must confirm against the admin page (FRD §8.3 step 3 — not skippable). Label entry for the device name.
- **AC:** Malformed QR rejected with a clear message; fingerprint screen has no bypass; Compose UI tests for the flow; payload parsing unit-tested including expired `expires_at`.
- **How you test it:** Generate a QR from a JSON fixture (tool script renders it as PNG to scan off your monitor), scan with the physical device, see the fingerprint screen.

### S2.3 — End-to-end enrollment wiring + status screen
Full flow: scan → confirm → keygen (S1.2) → CSR → enroll → store (S1.3) → automatic `/health` call → "Paired with <server>, certificate valid until <year>" status screen. Un-enrolled app always lands on "Pair with server".
- **AC:** Flow integration-tested with `FakeTransport`; failure at any step leaves the app cleanly un-enrolled (no half-state — verified by test); health result and device CN shown.
- **How you test it:** Against MockWebServer running on this PC (tool script `tools/dev-enroll-server.ps1` serves a canned enrollment + health using S1.1 certs): pair your physical phone over LAN Wi-Fi, watch it go green. This is the first full-device demo.

---

## M3 — Media detection & state

Goal: new photos reliably become `DISCOVERED` → `HASHED` rows in Room, on device and under test.

### S3.1 — Room schema & DAO (`:core:data`)
`AssetEntity` + `AssetState` per FRD §8.7, DAO queries the workers need (pending-by-state, stale-UPLOADING, counts), DataStore-persisted scan cursor (`DATE_ADDED` watermark + MediaStore generation).
- **AC:** In-memory Room tests: CRUD, state-transition guard rejects illegal transitions (exhaustive table test over all state pairs), cursor survives restart, query performance sane at 100k seeded rows.
- **How you test it:** `gradlew :core:data:test`.

### S3.2 — `MediaSource` abstraction (`:core:media`)
Interface + `MediaStoreSource` (query by `DATE_ADDED > ?`, key on `_ID` never path, `openInputStream` for bytes, generation check on API 30+) + `FakeMediaSource`.
- **AC:** Robolectric suite per FRD §9.3: empty library, single photo, 50-photo burst with identical `DATE_ADDED`, deleted-between-discovery-and-read, permission revoked mid-scan, clock-moved-backwards.
- **How you test it:** `gradlew :core:media:test`.

### S3.3 — Hashing pipeline
Streaming SHA-256 over content URIs, recorded on the entity (`DISCOVERED → HASHED`). Handles: file vanished (row removed), storage errors, large video streams without OOM.
- **AC:** Hashes match `testdata/manifest.json` vectors; vanished-file and IO-error paths tested; memory bounded (streams, no full-file buffers).
- **How you test it:** `gradlew :sync:test`; debug screen shows per-asset hash matching the manifest after seeding.

### S3.4 — Detection workers
Content-URI-triggered `OneTimeWorkRequest` per FRD §8.6 — **which re-enqueues itself every run** (the FRD's called-out highest-value test) — plus the 6-hour periodic reconciliation worker, generation-based cheap skip, 10s/5min trigger delays.
- **AC (work-testing + TestDriver):** re-enqueue asserted directly; reconciliation catches assets the trigger missed (fake source injects them); two concurrent workers don't double-insert; cancellation leaves recoverable state.
- **How you test it:** Seed 10 photos on the emulator (`seed-media.ps1`), open the app's debug asset list, watch them appear as `HASHED` within ~15 seconds. Take a real photo on your phone, same result.

---

## M4 — Upload engine

Goal: complete state machine through `UPLOADED` against fake/mock servers, including the foreground service.

### S4.1 — Transport: check + upload
`MtlsHttpTransport` gains `check()` (≤500-hash batching handled client-side) and `upload()` (raw body, spec headers, progress callback).
- **AC (MockWebServer, per FRD §9.5):** 201/200/400/409/507/500 each map to the correct typed result; connection dropped at 50% of body → retryable error; slow-loris → timeout fires; batch >500 split automatically.
- **How you test it:** `gradlew :core:network:test`.

### S4.2 — Upload orchestrator + state machine enforcement
The `:sync` engine: dedupe-check then upload pending assets, transitions per FRD §8.7, exponential backoff with 10-attempt cap, `FAILED_PERMANENT` on 400/cap, stale-`UPLOADING` reset to `HASHED` on start (process-death recovery).
- **AC:** Exhaustive transition tests incl. every illegal transition; backoff schedule unit-tested; process-death recovery tested (stale reset); same asset never uploaded twice concurrently (locking tested with parallel fake workers); error→state mapping table tested.
- **How you test it:** `gradlew :sync:test`; debug screen has a "Simulate" panel wired to `FakeTransport` (force 507, force network error, etc.) so you can watch states move on-device.

### S4.3 — Foreground upload worker + notification
Long-running WorkManager worker promoted to foreground (`dataSync` type), FRD §8.8 permissions, notification with remaining count, current file, and a working pause action. Work chunked so no single run needs >6h (Android 15 cap) — chunking logic shared with M6 backfill.
- **AC:** work-testing suite: constraint gating, cancellation mid-upload leaves recoverable state, pause action stops cleanly; notification content unit-tested via injected formatter.
- **How you test it:** With the dev enrollment server from S2.3 extended to accept uploads to a local folder (`tools/dev-upload-server.ps1`, still MockWebServer-grade — the real stack is M5): take a photo on your phone, watch the notification, find the file in the folder on this PC. **This is the "it actually works" demo.**

---

## M5 — Dev server harness & real integration

Goal: the repo's own docker-compose stack — real nginx doing real mTLS with a minimal Go `uploadd` implementing the S0.1 contract — plus the integration and E2E suites against it. This harness is the executable form of the server spec and doubles as the backend repo's reference implementation.

### S5.1 — Dev harness compose stack
`devserver/` in this repo: nginx (both listeners, verbatim spec configs), minimal `uploadd` (health, check, upload, enroll, SQLite, `LocalFSStore` with temp+fsync+rename), CA scripts from S1.1 promoted to spec-complete (`bootstrap.sh`, `sign-csr.sh`, `revoke.sh`, D1 validities). One command up on Docker Desktop. Library volume is a local folder standing in for the NAS mount.
- **AC:** `docker compose up` after `bootstrap.sh` serves 443/8443; curl with client cert reaches `/health`; curl without one fails **during handshake**; uploadd unreachable except via nginx; missing `X-Device-CN` rejected by uploadd.
- **How you test it:** Run the two commands, run the two curls (provided in `devserver/README.md`).

### S5.2 — Testcontainers integration suite
JVM tests driving the real stack with the real OkHttp client, per FRD §9.6: full enrollment (CSR → cert → authenticated health), byte-identical JPEG upload, duplicate → 200 + single file, revoke + CRL reload → handshake failure, expired/reused pairing token rejected, no-cert connection refused at TLS, 500 MB upload passes (no 413), PUID/PGID ownership on written files.
- **AC:** Suite green locally on Docker Desktop and in CI (PR workflow).
- **How you test it:** `gradlew :integration:test` with Docker Desktop running.

### S5.3 — Instrumented E2E on emulator
Emulator (with reverse port mapping to the harness) runs the real app: inject an image via ContentResolver, assert the file appears byte-identical in the harness library folder. CI job on PRs for API 26/30/33/34/35 (local run targets one API).
- **AC:** E2E green locally; CI matrix job exists and passes on at least API 34 (full matrix gated to M7 if CI minutes demand).
- **How you test it:** `gradlew connectedE2eTest` with the harness up — or manually: enroll the emulator against the harness via a real QR from the harness admin page, seed a photo, watch it land in the folder.

### S5.4 — Physical-device staging pass
Documented runbook (`docs/TESTING.md`): enroll your physical phone against the harness on this PC over LAN, take photos, verify arrival; simulate "internet" path by toggling Wi-Fi→cellular with the harness port-forwarded (or via your actual server if you stand it up from the spec at this point).
- **AC:** You have personally completed the runbook once; every gap you hit is either fixed or filed as an M6/M7 story.
- **How you test it:** You *are* the test.

---

## M6 — Backfill & resilience

Goal: the trust milestone — first-run backlogs, hostile OEMs, and the chaos suite.

### S6.1 — Chunked backfill
First-enrollment scan of the full camera roll, processed in bounded worker runs with progress persisted in Room (survives the 6h `dataSync` cap, reboots, process death). Progress UI ("12,400 of 40,000").
- **AC:** Simulated 10,000-asset backfill (FakeMediaSource) completes across forced worker interruptions with zero duplicates and zero misses; resumes after process kill; progress accurate.
- **How you test it:** Seed 500 photos on the emulator, enroll fresh, watch the progress UI drain into the harness folder; kill the app twice mid-run; verify 500 files, no dupes (`tools/verify-library.ps1` compares manifest hashes to the folder).

### S6.2 — Permission & partial-access guard
FRD §8.9: correct permission requests per SDK level, and explicit detection of `READ_MEDIA_VISUAL_USER_SELECTED` partial access on 14+ with a **blocking** explanation screen — the app must never look like it's syncing when it can't see the library.
- **AC:** Robolectric tests per SDK level; partial-access state renders the blocker (Compose test); grant-full path deep-links to system settings.
- **How you test it:** On your physical phone (14+), grant "Select photos" instead of full access — app must block and explain, not half-work.

### S6.3 — OEM battery handling + sync-health watchdog
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` onboarding step with explanation, per-vendor autostart deep links (dontkillmyapp intents behind a capability table), last-successful-sync timestamp with an in-app warning + troubleshooting screen past 24h.
- **AC:** Vendor table unit-tested (intent resolution guarded, falls back gracefully); watchdog state machine tested with a fake clock; onboarding shows on first run only.
- **How you test it:** Fresh install on your phone: onboarding asks for battery exemption; debug screen lets you fake the last-sync clock to trigger the warning.

### S6.4 — Chaos suite
FRD §9.8 as automated tests wherever the harness + fakes allow: airplane-mode mid-upload (network fault injection in transport tests) → resume without duplication; kill mid-upload → reset, no dupe; device storage full during hashing; server 507 → surfaced, recovers when space freed (harness disk-quota toggle); server unreachable 7 days → backlog drains on reconnect (fake clock); ±48h clock skew; two devices uploading identical photos → one file, both `UPLOADED` (integration); app upgrade mid-backlog → state survives (Room migration test scaffold established here).
- **AC:** Every scenario is a repeatable automated test or, where genuinely device-manual (airplane-mode toggle on hardware), a runbook entry in `docs/TESTING.md` you've executed.
- **How you test it:** `gradlew chaosSuite` + the short manual runbook on your phone.

---

## M7 — Revocation & release polish

### S7.1 — Revoked/invalid-cert UX
A revoked cert (handshake failure post-CRL) must surface as a clear, actionable "This device's access was revoked — re-pair to continue" state, not a silent stall or retry loop. Distinguished from generic network failure by error classification (handshake-rejected vs unreachable). Re-pair flow wipes identity cleanly and returns to enrollment.
- **AC:** Classification unit-tested; integration test: revoke on harness → app shows revoked state within one sync cycle → re-enrollment succeeds and uploads resume; retry storm capped (revoked devices must not hammer the server).
- **How you test it:** Revoke your phone via `revoke.sh` on the harness, watch the app tell you plainly, re-pair, confirm sync resumes.

### S7.2 — Main UI polish
Home status screen (sync state, last sync, backlog count), failed-asset list with per-item and bulk manual retry (`FAILED_PERMANENT` surfacing per FRD §8.7), settings (pause sync, cellular on/off toggle, server info, device label).
- **AC:** Compose UI tests for each screen state; cellular toggle actually gates worker network constraints (tested); retry moves `FAILED_PERMANENT → HASHED`.
- **How you test it:** Poke every screen on your phone; force failures from the debug panel and retry them.

### S7.3 — Full-matrix green + coverage gates
Entire suite (unit, Robolectric, work-testing, transport, Testcontainers, instrumented E2E) green on API 26, 30, 33, 34, 35 in CI. 80% line-coverage gate enforced on `:sync`, `:core:data`, `:core:crypto`.
- **AC:** One CI run showing all matrix legs green; coverage report ≥80% on the three modules.
- **How you test it:** The Actions badge.

### S7.4 — Release build & install runbook
Signed release APK (sideload — no Play Store per FRD), R8 rules verified (keystore/BC/OkHttp reflection survivors), `docs/INSTALL.md` covering: stand up backend from `SERVER_SPEC.md`, bootstrap CA, forward only 443 (**8443 never — in bold**), enroll each phone, move `ca.key` offline.
- **AC:** Release APK enrolls and syncs identically to debug (S5.4 runbook re-run on release build); install doc executed start-to-finish by you against your real server.
- **How you test it:** Install the release APK on your wife's phone. If that goes smoothly once, D1 guarantees you never do it again.

---

## Acceptance (system-level, from FRD §11, amended)

1. Photo taken on device appears on the NAS within 2 minutes on a normal network.
2. 40,000-photo backfill completes without intervention, surviving reboots and FGS interruptions.
3. `nmap` shows only 443; no-cert requests die in the handshake.
4. App rejects a public-CA cert for the correct hostname.
5. Revoking a device blocks it on the next request, and the app explains why.
6. Kill mid-upload: no duplicate, no corrupt file.
7. Files land with correct ownership, readable over existing Samba.
8. Full suite green on API 26/30/33/34/35.
9. `ca.key` absent during normal operation.
10. *(replaces renewal)* Certificates outlive the hardware: no re-enrollment is ever required for a healthy device.
