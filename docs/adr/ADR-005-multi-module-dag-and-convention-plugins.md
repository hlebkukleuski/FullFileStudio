# ADR-005: 10-Module Directed Acyclic Graph & `build-logic` Gradle Convention Plugins

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

Initially, `:core:engine-api` accumulated AIDL contracts, C++20 native sandboxing, media playback, SQL/columnar database engines, and document/vector/legacy parsers into a single monolithic module, while SDK versions, Kotlin compiler flags, and JVM options were duplicated across module `build.gradle.kts` scripts, and split-packages existed across modules.

## Decision

1. **Centralized `build-logic` Included Build:**
   Introduce `build-logic` registering three convention plugins:
   - `fullfilestudio.android.application` (`AndroidApplicationConventionPlugin`)
   - `fullfilestudio.android.library` (`AndroidLibraryConventionPlugin`)
   - `fullfilestudio.quality` (`CodeQualityConventionPlugin`)
   All modules inherit unified `compileSdk = 37`, `minSdk = 30`, Java 21 bytecode target, `allWarningsAsErrors = true` (`-Werror`), Java 25 test JVM module opens, Android Lint configuration, and `verifyModuleArchitecture` checks.
2. **Decomposition into 10 Single-Responsibility Modules with Disjoint Packages:**
   - `:core:engine-api` (`...core.engine.*`) — Pure AIDL contracts (`IIsolatedEngineService`), `DocumentSession`, `FileSignatureDetector`, `LegalLicensePolicy`, `AdaptiveRingBitmapPool`, `ScratchFiles`, and SPI bridges.
   - `:core:security` (`...core.security.*`) — C++20 `libfullfilestudio_security.so` (`seccomp-bpf`, `posix_fadvise`, NEON/SIMD), `ZeroCacheReadableSource`, `MaliciousHeaderSanitizer`, `TextEncodingDetector`.
   - `:core:engine-media` (`...core.media.*`) — Audio/video playback (`MediaPlaybackController`), ADPCM WAV decoder, MKV/EBML & MP4 subtitle/track demuxers.
   - `:core:engine-database` (`...core.database.*`) — SQLite B-tree reader, DuckDB, Realm, DBF, JSONL/BSON, and `UniversalHexInspectorImpl`.
   - `:core:engine-document` (`...core.document.*`) — PDF, Word (`DOC`/`DOCX`/`ODT`/`RTF`), legacy `XLS`/`PPT`/`ONE`, Apache Parquet, SVG (`MPL-2.0`), OpenGL ES 3.0 mesh renderer, images, fonts, and plain text.
   - `:features:office` (`...feature.office.*`) — OOXML/ODF spreadsheets & presentations (`XLSX`/`XLSB`/`ODS`/`PPTX`/`ODP`/`CSV`/`TSV`).
   - `:features:cadvector` (`...feature.cadvector.*`) — Clean-room 3D/CAD (`OpenUSD`, `glTF`/`GLB` + Draco + meshopt, `DAE`/`ZAE`, `STEP`, `IGES`, `DXF`, `STL`, `OBJ`, `3MF`, `PLY`, `OFF`, `3DS`).
   - `:features:archives` (`...feature.archives.*`) — Streaming archive navigation & Zip-Slip/Zip-Bomb-hardened virtual FD extraction (`ZIP`, `7Z`, `RAR4`/`RAR5`, `TAR`, `GZ`, `BZ2`, `XZ`, `ISO`).
   - `:features:gpl-engines` (`...feature.gpl.*`) — Isolated GPL-derived parsers (`BLEND`, `FBX`, `DjVu`, `DWG`/`ACIS`, `MOBI`/`AZW3`, `MDB`/`ACCDB`).
   - `:app` (`me.hlebkukleuski.fullfilestudio.*`) — Compose UI, UDF `ReaderViewModel`, DI `AppContainer`, `IsolatedRenderService`, and ONNX `PaddleOcrManager`.
3. **Zero Split-Packages Gate:**
   Root task `verifyZeroSplitPackages` scans every module's `src/main` tree and fails the build if any package directory is declared in more than one module.

## Consequences

- **Positive:** Parallel Gradle compilation, clean incremental rebuilds, strict encapsulation (`internal` visibility works per-domain), zero compiler warnings (`-Werror`), and zero split-packages across all 10 modules.
- **Trade-off:** Shared low-level primitives must live in `:core:engine-api` or `:core:security` rather than ad-hoc cross-feature imports.
