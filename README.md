<div align="center">

# FullFileStudio

**High-performance, multi-process secure offline file reader and inspector for Android**

</div>

---

| Specification | Value |
| :--- | :--- |
| **Minimum SDK** | API 30 (Android 11+) |
| **Target SDK** | API 37 |
| **Runtime & Language** | Kotlin, Java 25, C++20 |
| **Architecture** | 10-Module DAG, UDF (`ReaderViewModel`), Process Isolation (AIDL IPC) & 16 KB `SharedMemory` |
| **License** | `GPL-3.0-or-later` (Dual Licensing available for clean-room modules — see [LICENSING.md](LICENSING.md)) |

[Features](#features) • [Security & Privacy](#security--privacy) • [Supported Formats](#supported-formats) • [Project Structure](#project-structure) • [Engineering Docs](docs/ARCHITECTURE.md) • [Third-Party Licenses](#third-party-licenses) • [License](#license)

---

## About the Project

**FullFileStudio** is a high-performance, 100% open-source all-in-one file reader for Android. It eliminates application freezes, memory crashes, and format incompatibility issues when opening heavy documents, CAD/3D models, databases, or archives.

By decoupling UI rendering from file parsing using unprivileged Linux process sandboxing (`android:isolatedProcess="true"` + seccomp) and direct disk-reading pipelines, FullFileStudio maintains a fluid native-refresh-rate interface while protecting user data even when opening corrupted or untrusted files.

---

## Features

- **Crash-Proof Process Isolation:** Heavy parsing operates inside an isolated background process (`:render`), ensuring the primary UI remains responsive and isolated from malformed inputs.
- **Fluid Native-Refresh Rendering:** Tiles are rendered into pooled bitmaps (`AdaptiveRingBitmapPool`) and transferred across process boundaries via kernel `SharedMemory` without Binder pixel serialization.
- **On-Device Neural OCR:** Local PP-OCRv4 / PP-OCRv5 optical text recognition via embedded ONNX Runtime with zero cloud dependencies.
- **All-In-One Offline Engines:** Office, CAD/3D, and Archive engines plus 16 free metric-compatible open-source font families (SIL OFL 1.1, GUST Font License, DejaVu) are bundled directly into the app—no proprietary SDKs or online downloads required.
- **100% GPL-3.0-Compatible Open Architecture:** Built exclusively with GPL-3.0-compatible open-source code and open format specifications, without proprietary UnRAR or Google Play Services SDKs.

---

## Security & Privacy

- **Zero Network Permissions (Google Play Build):** The `googlePlay` build strips `INTERNET` and `ACCESS_NETWORK_STATE` entirely and bundles all engines offline. The `standalone` build uses HTTPS/TLS 1.3 exclusively for optional GitHub Releases update checks.
- **Sandbox Memory Isolation:** File parsing runs in an isolated process (`:render`) with seccomp filtering and read-only `SharedMemory` tile transfer.
- **Respect for Passwords & DRM:** Supports user-supplied passwords for encrypted PDF, Office (DOC/DOCX/XLS/XLSX/PPT/PPTX/ODF), DWG, 7Z, and MS Access databases; cleanly rejects unsupported encrypted formats (ZIP, RAR, Parquet, Realm, DuckDB, OneNote) without password bypass or HEX fallback; explicitly refuses DRM-protected containers (Kindle KFX/DRM, Office IRM/RMS, DRM EPUB incl. Adobe ADEPT / Readium LCP / Apple FairPlay, DRM PDF handlers, and DRM MP4/MKV media) and enforces PDF copy-restriction permissions (`/P` bit 5).
- **Cryptographic Package Integrity & 16 KB Alignment:** Signed with APK Signature Scheme v2/v3 and aligned to Android 15+ 16 KB page boundaries.
- **Local Storage Protection:** Disables cloud/USB backup and device transfer (`android:allowBackup="false"`, explicit excludes in `data_extraction_rules.xml` & `backup_rules.xml`) and blocks cleartext HTTP (`android:usesCleartextTraffic="false"`).

---

## Supported Formats

| Category | Formats |
| :--- | :--- |
| **Documents & E-books** | `PDF`, `DjVu`, `EPUB`, `FB2`, `AZW3`, `MOBI`, `DOC`, `DOCX`, `ODT`, `RTF`, `ONE`, `MHTML`, `HTML`, `TXT`, `TTF/OTF/TTC/WOFF/WOFF2` |
| **Spreadsheets & Presentations** | `XLSX`, `XLSB`, `XLS`, `ODS`, `CSV`, `TSV`, `PPTX`, `PPT`, `ODP` |
| **Graphics & 3D / CAD** | `SVG`, `SVGZ`, `STL`, `OBJ`, `DXF`, `DWG`, `ACIS (SAT/SAB)`, `BLEND`, `USD/USDA/USDC/USDZ`, `GLTF/GLB` (incl. Draco), `FBX`, `3MF`, `DAE/ZAE`, `STEP/STP`, `IGES/IGS`, `PLY`, `OFF`, `3DS`, `PNG`, `JPG`, `WEBP`, `GIF`, `BMP`, `ICO`, `TIFF`, `HEIC`, `AVIF` |
| **Audio & Video Media** | `MP3`, `WAV`, `AAC`, `FLAC`, `OGG`, `M4A`, `MP4`, `MKV`, `WEBM`, `AVI` (incl. SRT/VTT/SSA subtitles, audio track selection, variable speed, MediaSession) |
| **Archives & Disk Images** | `ZIP`, `JAR`, `7Z`, `RAR` (v4/v5), `TAR`, `GZ`, `BZ2`, `XZ`, `TGZ`, `ISO` (incl. Rock Ridge) |
| **Databases & Structured Data** | `SQLite`, `MS Access (MDB/ACCDB)`, `Apache Parquet`, `DuckDB`, `Realm`, `dBase (DBF)`, `BSON`, `JSON/JSONL`, `XML`, `MD`, source code |
| **Engineering Level** | Built-in **HEX Inspector** for viewing raw bytes of any non-DRM, unencrypted file |

---

## Project Structure

```text
FullFileStudio/
├── build-logic/                # Gradle Convention Plugins (-Werror, SDK 37, architecture & split-package gates)
├── app/                        # Jetpack Compose UI, UDF ReaderViewModel + SavedStateHandle, AppContainer DI, IsolatedRenderService, PaddleOcrManager
├── core/
│   ├── engine-api/             # Pure AIDL contracts (IIsolatedEngineService), DocumentSession, FileSignatureDetector, SPI bridges
│   ├── security/               # C++20 seccomp-bpf/fadvise/NEON helper, ZeroCacheReadableSource, MaliciousHeaderSanitizer, codecs
│   ├── engine-media/           # Audio/video playback controller, ADPCM WAV decoder, MKV/MP4 subtitle & track demuxers
│   ├── engine-database/        # SQLite, DuckDB, Realm, Apache Parquet, DBF, JSONL/BSON & UniversalHexInspectorImpl
│   └── engine-document/        # PDF, Word (DOC/DOCX/ODT/RTF), XLS/PPT/ONE, SVG (MPL-2.0), GLES30 mesh renderer, images & free Office fonts
├── features/
│   ├── office/                 # OOXML/ODF spreadsheets, presentations, and RFC 4180 CSV/TSV streaming grid
│   ├── cadvector/              # Clean-room 3D/CAD models (USD, GLTF/Draco/meshopt, DAE/ZAE, STEP, IGES, DXF, STL, OBJ, 3MF, PLY, OFF, 3DS)
│   ├── archives/               # Streaming archives & disk images (ZIP, 7Z, RAR4/5, TAR, GZ, BZ2, XZ, ISO)
│   └── gpl-engines/            # Strictly GPL-3.0-or-later module isolating GPL-derived engines (BLEND, FBX, DjVu, DWG/ACIS, MOBI/AZW3, MS Access)
├── docs/                       # System architecture (ARCHITECTURE.md), performance budget (PERFORMANCE.md), ADRs (docs/adr/)
└── scripts/                    # CI release binary compliance & 16 KB ELF verifier + PaddleOCR ONNX export script
```

---

## Author

**Hleb Kukleuski** — [hlebkukleuski@gmail.com](mailto:hlebkukleuski@gmail.com)

---

## Third-Party Licenses

FullFileStudio incorporates GPL-3.0-compatible open-source components including PaddleOCR (PP-OCRv4 / PP-OCRv5), Microsoft ONNX Runtime (and its statically linked dependencies such as Eigen under MPL-2.0), Apache Commons Compress/IO/Codec/Lang, XZ for Java (0BSD), Brotli Decoder (MIT), AndroidX, Kotlin, free open-source fonts (Liberation, Carlito, Caladea, Gelasio, EB Garamond, Comic Neue, Selawik, TeX Gyre, DejaVu), and ported/adapted code from Blender, OpenSubdiv, OpenUSD, resvg/tiny-skia, DjVuLibre, libarchive, Public Domain PPMd7, Google Draco (spec reference), and meshoptimizer. Full attributions and license texts are available in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and inside the app under **Settings → 3. Third-Party Licenses**.

---

## License

This project's application source code and compiled binaries are licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)** — see [LICENSE](LICENSE) for details.

**Dual Licensing (Clean-Room Modules):** The author's original clean-room modules (`:core:engine-api`, `:core:security`, `:core:engine-media`, `:core:engine-database`, `:core:engine-document` excluding file-level `MPL-2.0` `svg/*.kt`, `:features:office`, `:features:archives`, and `:features:cadvector`) are physically separated from `:features:gpl-engines` and are also available under alternative (non-copyleft) written licenses from the copyright holder (for example, for charitable/humanitarian organizations, healthcare/medical organizations, or commercial agreements). The `:features:gpl-engines` module is strictly `GPL-3.0-or-later` only. See [LICENSING.md](LICENSING.md) for full details and [CLA.md](CLA.md) for contributor terms.

Unmodified open-source font assets under `core/engine-document/src/main/assets/fonts/office/` are bundled as separate data assets ("mere aggregation" under GPL-3.0 §5) under their respective free font licenses (SIL OFL 1.1, GUST Font License 1.0 / LPPL 1.3c, and DejaVu / Bitstream Vera License).

**Trademarks:** All third-party product names, file format designations, and trademarks (including Microsoft Word/Excel/PowerPoint/Access/Office, Adobe PDF, Autodesk AutoCAD/DWG/DXF/FBX, Amazon Kindle, RAR/WinRAR, Google Play, Android, Blender, and Pixar OpenUSD/OpenSubdiv) belong to their respective owners and are used solely to indicate file format compatibility. FullFileStudio is an independent open-source project and is not affiliated with or endorsed by them.
