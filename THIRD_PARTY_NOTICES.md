# FullFileStudio — Third-Party Open Source Notices & Licenses

FullFileStudio is free software licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)**.
See [LICENSE](LICENSE) for the full license text and [LICENSING.md](LICENSING.md) for dual-licensing terms governing the clean-room modules. The complete Corresponding Source code is publicly available at [https://github.com/hlebkukleuski/FullFileStudio](https://github.com/hlebkukleuski/FullFileStudio).

All verbatim third-party license texts and notices are bundled offline inside the APK under `app/src/main/assets/licenses/` and displayed in-app under **Settings → Open Source Licenses**.

---

## 1. Runtime Libraries & Embedded Models

| Component | Version | Copyright | License | Bundled Text |
| :--- | :--- | :--- | :--- | :--- |
| **PaddleOCR (PP-OCRv4 / PP-OCRv5 models & dictionaries)** | PP-OCRv4 / v5 | PaddlePaddle Authors | Apache-2.0 | `Apache-2.0.txt`, `NOTICE-PaddleOCR.txt` |
| **Microsoft ONNX Runtime Android** | 1.31.1 | Microsoft Corporation | MIT + third-party static libs (see below) | `MIT.txt`, `ONNXRuntime-ThirdPartyNotices.txt`, `MPL-2.0.txt` |
| **Apache Commons Compress** | 1.28.0 | The Apache Software Foundation | Apache-2.0 | `Apache-2.0.txt` |
| **Apache Commons IO** | 2.20.0 | The Apache Software Foundation | Apache-2.0 | `Apache-2.0.txt` |
| **Apache Commons Codec** | 1.19.0 | The Apache Software Foundation | Apache-2.0 | `Apache-2.0.txt` |
| **Apache Commons Lang** | 3.18.0 | The Apache Software Foundation | Apache-2.0 | `Apache-2.0.txt` |
| **XZ for Java** (`org.tukaani:xz`) | 1.12 | Tukaani Project (Lasse Collin) | 0BSD | `0BSD.txt` |
| **Brotli Decoder** (`org.brotli:dec`) | 0.1.2 | Google Inc. | MIT | `MIT.txt` |
| **Android Jetpack (AndroidX)** | — | The Android Open Source Project / Google LLC | Apache-2.0 | `Apache-2.0.txt` |
| **Guava ListenableFuture** (`com.google.guava:listenablefuture`) | 1.0 | Google LLC | Apache-2.0 | `Apache-2.0.txt` |
| **Kotlin, Coroutines, Serialization & JetBrains Annotations** | 2.4.21 / 1.11.0 / 1.7.3 / 23.0.0 | JetBrains s.r.o. | Apache-2.0 | `Apache-2.0.txt` |
| **JSpecify Annotations** (`org.jspecify:jspecify`) | 1.0.0 | The JSpecify Authors | Apache-2.0 | `Apache-2.0.txt` |
| **Gradle Wrapper** (build tool) | 9.8.1 | Gradle, Inc. | Apache-2.0 | `Apache-2.0.txt` |

### Apache & TOST-1.0 NOTICE Reproductions
- **PaddleOCR**: This product includes software developed at PaddlePaddle (`https://github.com/PaddlePaddle/PaddleOCR`). Models: PP-OCRv4 (`det/inference.onnx`, `rec/inference.onnx`), PP-OCRv5 (`rec/eslav_ppocrv5_rec.onnx`, `rec/latin_ppocrv5_rec.onnx`), and character dictionaries. These unmodified pre-trained weights are bundled as separate Apache-2.0 data assets alongside FullFileStudio's GPL-3.0 code (`scripts/prepare_paddleocr_onnx.py`).
- **Apache Commons Compress / IO / Codec / Lang**: This product includes software developed at The Apache Software Foundation (`https://www.apache.org/`).
- **OpenSubdiv (`NOTICE.txt`, TOST-1.0 §4(d))**: OpenSubdiv — Copyright 2013 Pixar. All rights reserved. This product includes software developed at: Pixar (`http://www.pixar.com/`), Dreamworks Animation (`http://www.dreamworksanimation.com/`), Autodesk, Inc. (`http://www.autodesk.com/`), Google, Inc. (`http://www.google.com/`), and DigitalFish (`http://digitalfish.com/`).
- **OpenUSD (`NOTICE.txt`, TOST-1.0 §4(d))**: Universal Scene Description — Copyright 2016 Pixar. All rights reserved. This product includes software developed at: Pixar (`http://www.pixar.com/`).

### ONNX Runtime Native Dependencies (`libonnxruntime.so`)
`com.microsoft.onnxruntime:onnxruntime-android:1.31.1` statically links third-party libraries including **Eigen** (**MPL-2.0**, source available at [https://gitlab.com/libeigen/eigen](https://gitlab.com/libeigen/eigen)), **Protocol Buffers / RE2 / XNNPACK / cpuinfo / pthreadpool / Dawn** (**BSD-3-Clause**), **Abseil / FlatBuffers / gemmlowp / nsync / KleidiAI / microsoft/cpp_client_telemetry** (**Apache-2.0**; `TelemetryInitializer` is removed in `AndroidManifest.xml` and `googlePlay` has zero network permissions), and **SafeInt / nlohmann-json / FP16** (**MIT**). The full verbatim `ThirdPartyNotices.txt` extracted from ONNX Runtime 1.31.1 and the full MPL-2.0 license text are bundled in `app/src/main/assets/licenses/ONNXRuntime-ThirdPartyNotices.txt` and `MPL-2.0.txt`.

---

## 2. Ported & Adapted Open-Source Components

| Upstream Project | Copyright | Upstream License | Location in FullFileStudio |
| :--- | :--- | :--- | :--- |
| **Blender** | Blender Foundation & Authors; NaN Holding B.V. (Decimate, IK, noise); Nicholas Bishop (Skin) | GPL-2.0-or-later | `features/gpl-engines/.../CleanRoomBlend*.kt` (all files, including `Skin`, `Boolean`, and multires displacement in `Subdiv`) |
| **FreeBSD / UC Berkeley `qsort.c` & `heapsort.c`** | The Regents of the University of California (1991–1993); Ronnie Kon, Kevin Lew, Elmer Yglesias, Jon Bentley, M. Douglas McIlroy | BSD-3-Clause | `features/gpl-engines/.../CleanRoomBlendBevel.kt` (`darwinQsort`, `heapsort`) |
| **Blender Sub-Components (Penner Easing, Shoemake Euler, SPI Noise, Woop/Embree Ray-Tri, Buss/Kim SDLS IK)** | Robert Penner (2001, BSD-3-Clause); Ken Shoemake (1994); Sony Pictures Imageworks Inc. (2009–2010, BSD-3-Clause); Sven Woop, Carsten Benthin, Ingo Wald / Intel Corp. (Apache-2.0); Samuel R. Buss & Jin-Su Kim | BSD-3-Clause / Apache-2.0 | `features/gpl-engines/.../CleanRoomBlendAnim.kt`, `CleanRoomBlendNoiseTables.kt`, `CleanRoomBlendTexture.kt`, `CleanRoomBlendShrinkwrap.kt`, `CleanRoomBlendRig.kt` |
| **OpenSubdiv** | Pixar / DreamWorks Animation LLC / Autodesk / Google / DigitalFish | Modified Apache-2.0 (TOST 1.0) | `features/gpl-engines/.../CleanRoomBlendSubdiv.kt` |
| **OpenUSD & ASWF MaterialX Node Definitions** | Pixar / Alliance for OpenUSD; MaterialX Contributors (ASWF) | Modified Apache-2.0 (TOST 1.0) / Apache-2.0 | `features/cadvector/.../CleanRoomUsd.kt`, `CleanRoomUsdLayer.kt` |
| **resvg / usvg / svgtypes / simplecss / roxmltree / unicode-vo / ttf-parser / fontdb / tiny-skia / kurbo** | Yevhenii Reizner, The resvg Authors, The Linebender Authors; Google Inc. (2011, Skia / `tiny-skia`); The Kurbo Authors / Raph Levien (2018); Pascal Getreuer (2011, IIR blur); Felix Schütt & Ivan Kutskir (`fastblur`); W3C (`feTurbulence`) | MPL-2.0 / Apache-2.0 / MIT / BSD-3-Clause (`tiny-skia`) / BSD-2-Clause (IIR blur) / Unicode-3.0 | `core/engine-api/.../svg/*.kt` |
| **DjVuLibre** | LizardTech, Inc. / AT&T Labs / Leon Bottou, Yann LeCun, Patrick Haffner, Paul G. Howard, Bill Riemers | GPL-2.0-or-later | `features/gpl-engines/.../CleanRoomDjvuDecoder.kt` |
| **libarchive** | Tim Kientzle, Andres Mejia, Michihiro NAKAJIMA, Grzegorz Antoniak, contributors | BSD-2-Clause | `features/archives/.../Rar4Parser.kt`, `ArchiveNavigatorEngineImpl.kt` (`CleanRoomRar5Parser`) |
| **PPMd7 / PPMd var.H** | Igor Pavlov (2010), Dmitry Shkarin (2001) | Public Domain | `features/archives/.../Rar4Ppmd7Decoder.kt` |
| **Google Draco** | The Draco Authors (2016–2026) | Apache-2.0 | `features/cadvector/.../CleanRoomDraco.kt` |
| **meshoptimizer** | Arseny Kapoulkine (2016–2026) | MIT | `features/cadvector/.../CleanRoomMeshopt.kt` |
| **ECMA-376 Part 1 Annex D (Apache POI Adaptation)** | Ecma International (Bylaws §9.4 / Microsoft OSP); The Apache Software Foundation (Apache POI, 2003–2026) | Open Specification / Apache-2.0 (stripped `<ahLst>`/`<cxnLst>`) | `core/engine-api/src/main/assets/drawingml/presetShapeDefinitions.xml`, `CleanRoomDmlGeometry.kt` |
| **AOSP Minikin (`.hyb` Hyphenation Trie Format)** | The Android Open Source Project (2015) | Apache-2.0 | `core/engine-api/.../word/WordHyphenator.kt` |
| **Apache POI & Apache Parquet Format** | The Apache Software Foundation (2003–2026) | Apache-2.0 | `DocReader.kt`, `DocSprms.kt`, `CleanRoomPpt.kt`, `CleanRoomXlsCharts.kt`, `CleanRoomOfficeCrypto.kt`, `CleanRoomLegacyAndIsoEngine.kt`, `CleanRoomParquetReader.kt` |
| **Assimp, ufbx & Blender FBX I/O** | assimp team (2006–2026); Samuli Raivio (2020–2026); Blender Foundation (2013–2026) | BSD-3-Clause / MIT / GPL-2.0-or-later | `features/gpl-engines/.../CleanRoomFbx.kt` |
| **Calibre (MOBI HUFF/CDIC & Trailing Entries)** | Kovid Goyal & Calibre Contributors (2008–2026) | GPL-3.0-or-later | `features/gpl-engines/.../CleanRoomMobiParser.kt` |
| **GNU LibreDWG, ACadSharp & FreeCAD InventorLoader** | Free Software Foundation, Inc. (2009–2026); DomCR (2020–2026); Jens M. Plonka (2018–2024) | GPL-3.0-or-later / MIT / LGPL-2.1-or-later | `features/gpl-engines/.../CleanRoomDwgContainer.kt`, `CleanRoomDwgReader.kt`, `CleanRoomAcis.kt` |
| **Jackcess & MDB Tools (Jet3/Jet4/ACE Format)** | Health Market Science, Inc. / James Ahlborn (2008–2026); Brian Bruns & contributors (2000–2026) | Apache-2.0 / LGPL-2.0-or-later / GPL-2.0-or-later | `features/gpl-engines/.../CleanRoomAccessReader.kt` |
| **DuckDB, Realm Core & SQLite 3 Formats** | Stichting DuckDB Foundation (2018–2026); Realm Inc. / MongoDB, Inc. (2016–2026); D. Richard Hipp / Hwaci | MIT / Apache-2.0 / Public Domain | `CleanRoomDuckDbReader.kt`, `CleanRoomRealmReader.kt`, `CleanRoomSqliteReader.kt` |
| **Snappy, LZ4 & Zstandard (RFC 8878) Formats** | Google Inc. (2011); Yann Collet (2011–2020); Meta Platforms, Inc. | BSD-3-Clause / BSD-2-Clause | `core/engine-api/.../CompressionCodecs.kt` |
| **Cotangent Frame Normal Mapping (`cotangent_frame`)** | Christian Schüler (*ShaderX 5*, 2007) | Published Algorithm | `core/engine-api/.../Gles30MeshRenderer.kt` |

---

## 3. Bundled Free Open-Source Fonts (Unmodified)

FullFileStudio's application source code and compiled binaries are licensed under **`GPL-3.0-or-later`**. All font binaries under `core/engine-api/src/main/assets/fonts/office/` are redistributed unmodified as discrete, separate data assets ("mere aggregation" under GPL-3.0 §5), governed independently by their respective free font licenses below, with Reserved Font Names (RFNs) preserved intact and verbatim upstream license files alongside the fonts:

| Font Family | Copyright / Author | License | Reserved Font Name |
| :--- | :--- | :--- | :--- |
| **Liberation Sans / Serif / Mono** | Google Corporation (2010) / Red Hat, Inc. (2012) | SIL OFL 1.1 | `Arimo`, `Tinos`, `Cousine`, `Liberation` |
| **Carlito** | The Carlito Project Authors (2013) | SIL OFL 1.1 | `Carlito` |
| **Caladea** | The Caladea Project Authors (2012) | SIL OFL 1.1 | None |
| **Gelasio** | The Gelasio Project Authors (2022) | SIL OFL 1.1 | None |
| **EB Garamond** | The EB Garamond Project Authors (2017) | SIL OFL 1.1 | None |
| **Comic Neue** | The Comic Neue Project Authors (2014) | SIL OFL 1.1 | None |
| **Selawik** | Microsoft Corporation (2015) | SIL OFL 1.1 | `Selawik` |
| **TeX Gyre Adventor / Bonum / Heros Cn / Pagella / Schola** | B. Jackowski, J.M. Nowacki, P. Strzelczyk, J.B. Ludwichowski, M. Woliński (GUST); Han The Thanh (Vietnamese); URW++ Design & Development GmbH (base outlines) | GUST Font License 1.0 (LPPL 1.3c) | Manifest names preserved; LPPL-1.3c §2 & §6 subset notice (20 unmodified `.otf` files only; full upstream package at `gust.org.pl/projects/e-foundry/tex-gyre`) |
| **DejaVu Sans / Sans Mono** | Bitstream, Inc. / DejaVu / Tavmjong Bah (Arev) | DejaVu / Bitstream Vera License | `Bitstream Vera` |

---

## 4. Test-Only Dependencies & Test Fixtures (Not Bundled in APKs)

- **JUnit 4** (EPL-1.0), **Robolectric** (MIT), **XPP3** (Indiana University Extreme! Lab License), **Apache POI** (Apache-2.0), **Jackcess** (Apache-2.0), **SQLite-JDBC** (Apache-2.0), **Zstd-JNI** (BSD-2-Clause), **Snappy / LZ4-Java** (Apache-2.0).
- Test sample files under `*/src/test/resources/` carry per-directory `LICENSE` / `NOTICE` files and are never packaged into release or debug APKs:
  - `core/engine-api/src/test/resources/doc/`: Apache POI test documents (`Apache-2.0`, `LICENSE-APACHE` & `NOTICE`).
  - `core/engine-api/src/test/resources/svg-reference/`: resvg test suite (`Apache-2.0 OR MIT`) and Google Noto test fonts (`SIL OFL 1.1`, `Noto-LICENSE-OFL.txt`).
  - `core/engine-api/src/test/resources/{adpcm,ccitt,mkvsubs,parquet,ppt,pptx,xls}/`: synthetic test fixtures created for FullFileStudio (`GPL-3.0-or-later`).
  - `features/archives/src/test/resources/{rar4,rar5}/`: sanitized libarchive RAR4/RAR5 test fixtures (`BSD-2-Clause`, `LICENSE`).
  - `features/gpl-engines/src/test/resources/blend/`: Khronos glTF-Blender-IO & Blender test scenes (`Apache-2.0` / `GPL-2.0-or-later`, `LICENSE-glTF-Blender-IO.txt`).
  - `features/gpl-engines/src/test/resources/{djvu,mobi,access}/`: synthetic DjVu, MOBI, and Access test fixtures (`GPL-3.0-or-later`).
  - `features/gpl-engines/src/test/resources/fbx/`: Assimp BSD test fixture (`test/models/FBX/huesitos.fbx`, `BSD-3-Clause`) and synthetic FBX scenes (`GPL-3.0-or-later`, `LICENSE-assimp-test-models.txt`).
  - `features/cadvector/src/test/resources/draco/` & `gltf/`: Khronos glTF-Sample-Assets (`CC-BY-4.0` / `CC0-1.0`, `NOTICE` & `LICENSE`).
  - `features/cadvector/src/test/resources/tex/`: synthetic DDS/TGA texture test fixtures (`GPL-3.0-or-later`, `LICENSE`).
  - `features/cadvector/src/test/resources/usd/`: ASWF / Pixar OpenUSD Working Group assets (`Apache-2.0`, `LICENSE-usd-wg-assets.txt`).

---

## 5. Trademarks & Non-Affiliation Notice

All product names, company names, file format designations, and trademarks mentioned in FullFileStudio or its documentation — including Microsoft, Word, Excel, PowerPoint, Access, OneNote, Office, Adobe, PDF, PostScript, Autodesk, AutoCAD, DWG, DXF, FBX, Amazon, Kindle, RAR, WinRAR, Google, Google Play, Android, Blender, Pixar, OpenUSD, OpenSubdiv, and PaddleOCR — are the property of their respective owners and are used solely for nominative identification of file format compatibility and open-source attribution. FullFileStudio is an independent open-source project and is not affiliated with, endorsed by, or sponsored by any of those trademark holders.
