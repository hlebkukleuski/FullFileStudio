# Dual Licensing Policy — FullFileStudio

**Copyright (C) 2026 Hleb Kukleuski (<hlebkukleuski@gmail.com>)**

## 1. Public Open-Source License (`GPL-3.0-or-later`)

The complete **FullFileStudio** application and all modules in this repository are publicly licensed under the **GNU General Public License, Version 3.0 or later (`GPL-3.0-or-later`)**. See [LICENSE](LICENSE) for the full text.

Under `GPL-3.0-or-later`, anyone may use, study, modify, and redistribute FullFileStudio or any of its modules freely, provided that any distributed derivative work or combined application is also licensed under `GPL-3.0-or-later` with complete Corresponding Source code.

---

## 2. Alternative / Non-Copyleft Licensing for Clean-Room Modules

In addition to `GPL-3.0-or-later`, the copyright holder (**Hleb Kukleuski**) offers separate, written **alternative (non-copyleft) licenses** for the original clean-room engine modules listed below, allowing licensees to integrate those modules into closed-source or non-GPL applications without copyleft disclosure of the licensee's own application code.

Alternative licenses are granted **exclusively in writing** by the copyright holder, on a case-by-case basis and at the copyright holder's sole discretion. Examples include:

- **Charitable & Humanitarian Organizations:** Special or royalty-free non-copyleft licenses for verified registered charities and non-profit humanitarian initiatives.
- **Healthcare & Medical Organizations:** Special or custom non-copyleft licenses for hospitals, clinics, medical research institutions, and healthcare software providers.
- **Commercial Agreements:** Standard commercial licenses for companies and independent developers integrating one or more clean-room modules into proprietary products.

No implied alternative license is granted by this document. Until a separate written license agreement is signed by the copyright holder, your use of this repository is governed strictly by `GPL-3.0-or-later`.

To request an alternative license, contact **Hleb Kukleuski** at [hlebkukleuski@gmail.com](mailto:hlebkukleuski@gmail.com).

---

## 3. Module Licensing Matrix

FullFileStudio physically separates third-party GPL-derived engines from the author's original clean-room modules at the Gradle module boundary so that clean-room modules can be compiled and linked independently without any GPL-derived code:

| Module | Public License | Eligible for Alternative License? | Notes |
| :--- | :--- | :--- | :--- |
| `:core:engine-api` *(excluding `svg/*.kt`)* | `GPL-3.0-or-later` | **Yes** | AIDL contracts, C++20 `SecurityArena`, PDF, Word (DOC/DOCX/ODT/RTF), PPT/PPTX, XLS/XLSX, Office Crypto, Parquet, DuckDB, Realm, SQLite, DBF, HEX, Media, and permissive codecs. |
| `:features:office` | `GPL-3.0-or-later` | **Yes** | Spreadsheets, presentations, e-books (EPUB/FB2/MHTML/HTML/TXT), and font preview. |
| `:features:archives` | `GPL-3.0-or-later` | **Yes** | Clean-room archive & disk image parsers (ZIP, JAR, 7Z, RAR4/RAR5 via BSD-2-Clause `libarchive` / Public Domain PPMd7, TAR, GZ, BZ2, XZ, ISO). |
| `:features:cadvector` | `GPL-3.0-or-later` | **Yes** | Clean-room 3D/CAD engines (OpenUSD, glTF/GLB + Draco + meshopt, COLLADA/ZAE, STEP, IGES, IFC, DXF, STL, OBJ, 3MF, PLY, OFF, 3DS). |
| `:core:engine-api` (`.../engine/svg/*.kt`) | `GPL-3.0-or-later` / `MPL-2.0` | **File-level `MPL-2.0` only** | Ported from `resvg` (`MPL-2.0`). Under `MPL-2.0`, these specific files may be combined with closed-source code in a larger work, provided the `svg/*.kt` files (and any modifications to them) remain open-source under `MPL-2.0`. |
| `:features:gpl-engines` | **`GPL-3.0-or-later` ONLY** | **No** | **Strictly excluded from alternative licensing.** See Section 4 below. |
| `:app` | `GPL-3.0-or-later` | **Case-by-case** *(only without `:features:gpl-engines`)* | Full application UI & `IsolatedRenderService`. Any build that includes `:features:gpl-engines` is strictly `GPL-3.0-or-later`. |

---

## 4. Strict Exclusions (`:features:gpl-engines`)

The `:features:gpl-engines` module contains engines ported from or derived from upstream third-party GPL-licensed projects:

- **Blender** (`CleanRoomBlend*.kt`) — upstream `GPL-2.0-or-later`
- **Blender FBX I/O** (`CleanRoomFbx.kt`) — upstream `GPL-2.0-or-later` (with `Assimp` BSD-3-Clause / `ufbx` MIT)
- **DjVuLibre** (`CleanRoomDjvuDecoder.kt`) — upstream `GPL-2.0-or-later`
- **GNU LibreDWG / FreeCAD InventorLoader** (`CleanRoomDwgContainer.kt`, `CleanRoomDwgReader.kt`, `CleanRoomAcis.kt`) — upstream `GPL-3.0-or-later` / `LGPL-2.1-or-later`
- **Calibre** (`CleanRoomMobiParser.kt`) — upstream `GPL-3.0-or-later`
- **MDB Tools** (`CleanRoomAccessReader.kt`) — upstream `GPL-2.0-or-later` / `LGPL-2.0-or-later`

Because third-party copyright holders own those upstream works, **`:features:gpl-engines` is NOT available under any non-GPL license**. Licensees operating under an alternative license **must exclude `:features:gpl-engines`** from their build (the clean-room modules discover `:features:gpl-engines` only at runtime via optional SPI bridges and compile and run cleanly without it).

---

## 5. Third-Party Permissive Notices & Bundled Assets

An alternative license from the copyright holder covers only the copyrightable code owned by Hleb Kukleuski. All third-party permissive libraries, ported permissive components (`Apache-2.0`, `MIT`, `BSD-2-Clause`, `BSD-3-Clause`, `0BSD`, `TOST-1.0`), pre-trained PaddleOCR models (`Apache-2.0`), ONNX Runtime (`MIT` + statically linked `MPL-2.0` Eigen), and bundled free fonts (`SIL OFL 1.1`, `GFL-1.0` / `LPPL-1.3c`, `DejaVu`) remain subject to their respective upstream licenses and attribution/NOTICE obligations. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## 6. External Contributions

To preserve the copyright holder's ability to maintain dual licensing across the clean-room modules, all external contributions to FullFileStudio require agreement to the Individual Contributor License Agreement in [CLA.md](CLA.md).
