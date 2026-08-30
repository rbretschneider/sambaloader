# Sambaloader — agent guide

## Read first

- The API/server contract is **frozen** in `docs/SERVER_SPEC.md`. The app and
  the (separate-repo) backend are both built against it. Do not change request
  or response shapes without bumping the spec version and saying so loudly.
- The plan of record is `docs/MILESTONES.md` (stories S0.1–S7.4, decisions
  D1–D6). Work proceeds story by story; each story's acceptance criteria and
  tests must pass before it is called done.
- Certificates are **long-lived by design** (CA 30y / server 10y / device 25y,
  decision D1). Do not add renewal logic, expiry warnings, or short-lived-cert
  "hygiene" — revocation via CRL is the only lifecycle event.

## Load-bearing invariants

- The app trusts ONLY the private CA (`withTrustedRoots = false` semantics).
  Never merge with the platform trust store, never add a fallback trust path.
  All certificate validation errors fail closed.
- Device private keys live in AndroidKeyStore, non-exportable,
  `setUserAuthenticationRequired(false)`, StrongBox first with TEE fallback.
- Content-triggered WorkManager requests are OneTimeWorkRequests and **must
  re-enqueue themselves at the end of every run** — the silent-death bug this
  prevents is the most common failure in this class of app (FRD §8.6).
- Asset state transitions go through `AssetStateMachine.require(...)` — never
  write `AssetState` directly.
- Never key media identity on file path; key on MediaStore `_ID` + SHA-256.

## Conventions

- Package root: `com.nectarmobiledevelopment.sambaloader`. Modules under
  `core/` + `sync/`; shared Gradle config lives in
  `build-logic/AndroidLibraryConventionPlugin.kt`; every version is pinned in
  `gradle/libs.versions.toml`.
- House standards apply (see the `standards` skill): one type per file, braces
  always, constants for meaningful literals, DI everywhere side effects live,
  comments explain *why*.
- Tests: JUnit 5, test names are full sentences in backticks, fixed clocks
  injected via `TimeProvider`, fakes over mocks where practical, test data
  builders with defaulted parameters. The committed corpus in `testdata/` is
  hash-pinned by `TestDataManifestTest` — regen only via
  `tools/generate-testdata.ps1`.
- Commits: Conventional Commits (`feat(sync): ...`, `fix(crypto): ...`,
  `docs: ...`, `chore(release): ...`).

## Building / testing quickstart

```
gradlew assembleDebug testDebugUnitTest detekt koverVerifyDebug
./tools/seed-media.ps1 -Count 10     # seed a device/emulator camera roll
```
