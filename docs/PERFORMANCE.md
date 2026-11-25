# FullFileStudio — Performance, Memory & Binary Hygiene

FullFileStudio is engineered for deterministic memory usage, zero main-thread disk I/O, 60/90/120 Hz viewport rendering, and Android 15+ 16 KB page-size compliance across multi-hundred-megabyte documents, archives, databases, and 3D CAD scenes.

---

## 1. Zero-Copy Positional I/O & Linux Page-Cache Discipline

| Subsystem | Implementation | Memory / Latency Guarantee |
| :--- | :--- | :--- |
| **Random-Access Reads** | `ZeroCacheReadableSource` (`Os.pread` + `EINTR` retry + `FileChannel` fallback) | Stateless thread-safe reads without seek locks or temporary `cacheDir` copies on seekable `content://` / `file://` descriptors |
| **Sequential Eviction Hints** | `SecurityArena.safeFadviseDontNeed` (`posix_fadvise(..., POSIX_FADV_DONTNEED)`) | Advises the Linux kernel to drop one-time sequential scan pages instead of evicting hot UI pages |
| **Isolated Scratch Storage** | `ScratchFiles` (`Os.memfd_create` with `MFD_CLOEXEC`) | Zero filesystem footprint inside `isolatedProcess="true"`; descriptors close deterministically when sessions end |
| **Tile Coordinate Keys** | `@JvmInline value class PackedTileKey(val raw: Long)` | Zero heap boxing for `(zoom, pageIndex, tileX, tileY)` keys across high-frequency pan/zoom frames |

---

## 2. Bounded Cross-Process Tile & Mesh Pipeline

Rendering occurs out-of-process inside `:render` (`IsolatedRenderService`) to isolate the UI heap from parser allocations:

1. **Reusable Render Target Pool (`AdaptiveRingBitmapPool`):**
   - Pools `ARGB_8888` bitmaps inside `:render` with a hard ceiling of **24 MB** (`MAX_POOL_BYTES`).
   - Automatically evicts all pooled bitmaps (`trimToZero()`) when the last session closes or when Android dispatches `TRIM_MEMORY_RUNNING_LOW` / `TRIM_MEMORY_UI_HIDDEN`.
2. **16 KB-Aligned `SharedMemory` (`ashmem`) Transfer:**
   - `ProxyDocumentSession` rounds every tile buffer up to a 16 KB boundary:
     ```kotlin
     val alignedSize = ((width * height * 4) + 16383) and -16384
     ```
   - Avoids Android's shared ~1 MB Binder transaction buffer (`TransactionTooLargeException` is structurally impossible for pixel tiles and exported 3D `MeshScene` buffers).
   - 3D meshes (`exportMeshScene`) are serialized once into `SharedMemory`, sealed read-only with `OsConstants.PROT_READ`, and uploaded into OpenGL ES 3.0 VBOs (`Gles30MeshRenderer`).

---

## 3. Jetpack Compose Stability & Compiler Metrics

Compose Compiler reports and metrics are emitted automatically on every `:app` compilation into `app/build/compose_compiler/<variant>/`:

- **Enabled Compiler Optimizations (`app/build.gradle.kts` + Kotlin `2.4.21`):**
  - `StrongSkipping = true`
  - `IntrinsicRemember = true`
  - `OptimizeNonSkippingGroups = true`
  - `PausableComposition = true`
- **Explicit Stability Contracts (`@Immutable` / `@Stable`):**
  - UI UDF models: `@Immutable ReaderUiState`, `@Immutable StructuredUiError`, `@Stable AppContainer`, `@Stable IsolatedRenderClient`
  - Settings, i18n & theming: `@Stable AppSettingsManager`, `@Immutable AppStrings`, `@Immutable AppPalette`, `@Immutable ActiveAudioDeviceCapabilities`
  - Bootstrap & update models: `@Stable FirstLaunchEngineBootstrapper`, `@Stable StandaloneUpdateManager`, `@Immutable StandaloneUpdateInfo`
- **Verified Full-Module Metrics (`googlePlayRelease`):**
  - `skippableComposables`: **171** / `restartableComposables`: **270**
  - `markedStableClasses`: **22** / `effectivelyStableClasses`: **38**
- **High-Refresh Display Negotiation:**
  - `MainActivity` and `AudioDeviceCapabilitiesDetector` query `Display.supportedModes` to unlock 90 Hz / 120 Hz rendering (`window.attributes.preferredDisplayModeId`) when configured by the user.

---

## 4. Verified ART Baseline Profile (`baseline-prof.txt`)

- `app/src/main/baseline-prof.txt` contains **871 verified ART profile rules across 125 startup and hot-path classes** (`FullFileStudioApp`, `MainActivity`, `ReaderViewModel`, `DocumentIntentResolver`, `ProxyDocumentSession`, `TiledZoomableCanvas`, `IsolatedRenderService`, `FileSignatureDetector`, `ZeroCacheReadableSource`, `AdaptiveRingBitmapPool`, and core format sessions).
- **Zero Drift Gate:** Unit test `AppSecurityAndLocaleTest.baselineProfileEntriesMatchCompiledBytecodeDescriptors` parses every class and method descriptor in `baseline-prof.txt` and verifies it against the actual compiled `.class` bytecode across all modules, preventing stale or fabricated profile rules.
- **Release Packaging Gate:** `scripts/verify_play_isolation_and_alignment.py` verifies that `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm` are compiled into `app-googlePlay-release.apk` and `BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof` is present in `app-googlePlay-release.aab`.

---

## 5. Android 15+ 16 KB ELF Page Alignment & Native Hardening

`core/security/src/main/cpp/CMakeLists.txt` compiles `libfullfilestudio_security.so` (C++20) with:

- `-Wl,-z,max-page-size=16384` — enforces 16 KB (`0x4000`) `PT_LOAD` ELF segment alignment for Android 15/16+ 16 KB page-size devices.
- `-Wl,-z,relro,-z,now` and `-Wl,-z,noexecstack` — full RELRO and non-executable stack.
- `-flto` and `-fsanitize=cfi` (64-bit targets) — Link-Time Optimization and Control-Flow Integrity.
- Uncompressed (`ZIP_STORED`) `.so` packaging in APKs for direct kernel `mmap` without extraction overhead, verified both in JUnit (`IsolatedRenderServiceIpcTest.nativeCmakeAndBuiltSharedLibrariesEnforce16KbElfPageAlignment`) and on release APKs (`scripts/verify_play_isolation_and_alignment.py`).

---

## 6. Debug `StrictMode` Enforcement

In debug builds (`BuildConfig.DEBUG && !Process.isIsolated()`), `FullFileStudioApp.installDebugStrictMode()` activates:

- **`StrictMode.ThreadPolicy`:** Detects accidental disk reads, disk writes, network calls, and custom slow calls on the UI main thread (`penaltyLog()`).
- **`StrictMode.VmPolicy`:** Detects leaked `Closeable` / `ParcelFileDescriptor` / SQLite cursors, leaked Activity/Service instances, and `file://` URI exposure (`penaltyLog()`).
