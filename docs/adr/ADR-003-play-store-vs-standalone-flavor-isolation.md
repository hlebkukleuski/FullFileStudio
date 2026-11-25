# ADR-003: Source-Set Isolation Between `googlePlay` and `standalone` Product Flavors

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

Google Play's *Device and Network Abuse* policy strictly prohibits Play-distributed apps from downloading or installing executable APKs from outside Google Play or bundling self-updater mechanisms (`REQUEST_INSTALL_PACKAGES`). Conversely, users installing the open-source APK directly from GitHub Releases (`standalone` distribution) benefit from an optional, cryptographically verified update check.

Using runtime `if (BuildConfig.IS_PLAY)` branches inside shared `src/main` code is insufficient: dead-code strings, `REQUEST_INSTALL_PACKAGES` manifest entries, `FileProvider` update paths, or networking classes can still remain visible to static scanners and auditors.

## Decision

Separate distribution capabilities at **compile time** via the `distribution` flavor dimension (`googlePlay` vs `standalone`):

1. **`googlePlay` Flavor (`app/src/googlePlay/`):**
   - `AndroidManifest.xml` uses `tools:node="remove"` to strip `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`, and `android.permission.REQUEST_INSTALL_PACKAGES` even if declared by transitive AARs (such as ONNX Runtime).
   - `StandaloneUpdateManager.kt` in `app/src/googlePlay/` is a pure offline no-op (`isSupported = false`, zero URLs, zero sockets, zero `PackageInstaller` references).
   - Zero `file_paths.xml` update provider resources exist in `src/main` or `src/googlePlay`.
2. **`standalone` Flavor (`app/src/standalone/`):**
   - Contains the sole declarations of `INTERNET`, `REQUEST_INSTALL_PACKAGES`, `FileProvider` (`res/xml/file_paths.xml`), `Tls13Https.kt` (strict TLS 1.3-only `SSLSocketFactory`), and the active `StandaloneUpdateManager.kt`.
   - Enforces SHA-256 digest verification of downloaded APKs **plus** Android `PackageManager.GET_SIGNING_CERTIFICATES` public-key comparison against the currently installed app's signing certificate before launching `ACTION_VIEW` installation.
3. **Automated Source & Binary Verification:**
   - `CodeQualityConventionPlugin` (`verifyModuleArchitecture`) fails the Gradle build if `src/main` or `src/googlePlay` ever references `Tls13Https`, `REQUEST_INSTALL_PACKAGES`, `releases/download`, or `version.json`.
   - `scripts/verify_play_isolation_and_alignment.py` inspects the compiled `app-googlePlay-release.apk` and `.aab` in CI, asserting zero forbidden DEX strings, zero network/install permissions via `aapt2 dump permissions`, and zero `file_paths` resources.

## Consequences

- **Positive:** The Google Play artifact is provably 100% offline with zero network permissions and zero self-update bytecode in `classes*.dex`.
- **Positive:** Standalone GitHub users retain a secure, TLS 1.3 + SHA-256 + signing-cert-pinned update workflow without compromising Play Store compliance.
- **Trade-off:** Both flavor unit test suites (`testGooglePlayDebugUnitTest` and `testStandaloneDebugUnitTest`) and both release artifacts must be built in CI.
