<div align="center">

# FullFileStudio

**High-performance, multi-process secure file reader and inspector for Android**

</div>

---

| Specification | Value |
| :--- | :--- |
| **Minimum SDK** | API 30 (Android 11+) |
| **Target SDK** | API 37 |
| **Runtime & Language** | Kotlin, Java 25, C++20 |
| **Architecture** | Process Isolation (AIDL IPC), Zero-Copy / Zero-GC |
| **License** | GPL v3.0 |

[Features](#features) • [Security & Privacy](#security--privacy) • [Supported Formats](#supported-formats) • [Project Structure](#project-structure) • [Third-Party Licenses](#third-party-licenses) • [License](#license)

---

## About the Project

**FullFileStudio** is a high-performance all-in-one file reader for Android. It eliminates application freezes, memory crashes, and format incompatibility issues when opening heavy documents, CAD models, or complex archives.

By decoupling UI rendering from file parsing using unprivileged Linux process sandboxing and direct disk-reading pipelines, FullFileStudio maintains a fluid native-refresh-rate interface while guaranteeing system stability, even when handling corrupted or untrusted file payloads.

---

## Features

- **Crash-Proof Process Isolation:** Prevents application crashes when opening corrupted files. Heavy parsing operates inside a sandboxed background process (`:render`), ensuring the primary UI remains responsive if a file payload fails.
- **Fluid Native-Refresh Rendering:** Eliminates interface stutter during pan and zoom gestures, matching your display's native refresh rate. Graphics are delivered over read-only kernel shared memory (`SharedMemory`) without copying pixel buffers across process boundaries.
- **On-Device Neural OCR:** Recognizes text, tables, and characters in scans and images without cloud dependencies. The embedded ONNX runtime processes optical recognition 100% locally.
- **On-Demand Modular Delivery:** Reduces initial application size. Specialized rendering engines for CAD, Office, and Archives are installed dynamically only when requested.
- **Clean-Room Open Architecture:** Built without proprietary third-party libraries, relying on open specifications, native Kotlin implementations, and hardware OpenGL ES 3.0 graphics pipelines.

---

## Security & Privacy

FullFileStudio incorporates security mechanisms to protect user privacy and device integrity:

- **Sandbox Memory Isolation:**
  File parsing occurs in an isolated background process (`:render`). Rendered frames are passed to the UI using read-only shared memory (`SharedMemory.mapReadOnly()`), preventing corrupted or malicious file payloads from accessing primary app storage.

- **100% On-Device Processing:**
  All file parsing, 3D rendering, and neural network text recognition execute locally. No document content, text layers, or user files are transmitted to external servers.

- **Cryptographic Package Integrity:**
  Application binaries are protected against unauthorized modification or tampering, signed using **APK Signature Scheme v2 & v3 (SHA-256 / RSA)** and aligned to Android 15 **16 KB page boundaries**.

- **Encrypted Network Transport:**
  All update checks and optional module downloads strictly enforce **HTTPS / TLS 1.3**. Unencrypted cleartext HTTP traffic is blocked at the Android OS kernel level (`usesCleartextTraffic="false"`).

- **Local Storage Protection:**
  Disables unauthorized USB backup extraction (`android:allowBackup="false"`), keeping application settings and cached file data protected from external extraction.

---

## Supported Formats

| Category | Formats |
| :--- | :--- |
| **Documents & E-books** | `PDF`, `DjVu`, `EPUB`, `FB2`, `AZW3`, `MOBI`, `DOCX`, `ODT`, `RTF`, `TXT` |
| **Spreadsheets & Presentations** | `XLSX`, `XLS`, `ODS`, `CSV`, `TSV`, `PPTX`, `PPT`, `ODP` |
| **Graphics & CAD** | `SVG`, `STL`, `OBJ`, `DXF`, `DWG`, `3MF`, `DAE`, `PNG`, `JPG`, `WEBP`, `TIFF`, `HEIC` |
| **Archives & Images** | `ZIP`, `7Z`, `RAR` (v4/v5), `TAR`, `GZ`, `ISO`, `APK` |
| **Databases & Code** | `SQLite`, `Access (MDB/ACCDB)`, `Parquet`, `JSON`, `XML`, `MD`, source code in any language |
| **Engineering Level** | Built-in **HEX Inspector** for viewing raw bytes of any file |

---

## Project Structure

```text
FullFileStudio/
├── app/                        # UI (Jetpack Compose), MainReaderViewModel, IsolatedRenderService, PaddleOcrManager
├── core/
│   └── engine-api/             # AIDL contracts, C++20 SecurityArena (JNI/NEON), base engines (PDF, DjVu, Font, DB, Media, HEX)
├── features/
│   ├── office/                 # Dynamic module for office formats, spreadsheets, presentations, and e-books
│   ├── cadvector/              # Dynamic module for SVG/SVGZ and 3D/CAD models (STL, OBJ, DXF, DWG, GLB, 3DS, PLY, OFF)
│   └── archives/               # Dynamic module for archives and disk images (ZIP, 7Z, RAR4/5, TAR, GZ, BZ2, XZ, ISO)
└── scripts/
    └── prepare_paddleocr_onnx.py  # Script for downloading dictionaries and exporting PaddleOCR models to ONNX
```

---

## Author

**Hleb Kukleuski** - [hlebkukleuski@gmail.com](mailto:hlebkukleuski@gmail.com)

---

## Third-Party Licenses

FullFileStudio utilizes third-party open-source components including PaddleOCR, ONNX Runtime, Apache Commons Compress, Junrar, and AndroidX. Details and copyright notices can be found in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

---
