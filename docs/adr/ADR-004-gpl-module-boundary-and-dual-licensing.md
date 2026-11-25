# ADR-004: Physical Module Isolation of GPL-Derived Engines & Dual-Licensing Architecture

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

FullFileStudio is publicly distributed under `GPL-3.0-or-later`, while the author's original clean-room modules are also offered under separate written non-copyleft licenses (see `LICENSING.md`). Several specialized formats (`BLEND`, `FBX`, `DjVu`, `DWG/ACIS`, `MOBI/AZW3`, and `MS Access MDB/ACCDB`) rely on algorithms ported from upstream third-party GPL/LGPL projects (`Blender`, `DjVuLibre`, `GNU LibreDWG` / `FreeCAD`, `Calibre`, `MDB Tools`).

Mixing third-party GPL-derived parsers into the same Gradle modules or compile-time dependencies as clean-room engines would prevent clean-room modules from being compiled, tested, and licensed independently without third-party copyleft code.

## Decision

1. **Strict Physical Isolation in `:features:gpl-engines`:**
   All third-party GPL/LGPL-derived parsers live exclusively inside `:features:gpl-engines` under `me.hlebkukleuski.fullfilestudio.feature.gpl.*` with clean, honest naming (`BlendReader`, `FbxLoader`, `DjvuDecoder`, `DwgReader`, `AcisParser`, `MobiParser`, `AccessReader`—never `CleanRoom*`).
2. **One-Way Dependency Direction via SPI Bridges:**
   - `:core:engine-api` defines pure Kotlin SPI bridge interfaces (`GplCoreEngineBridge`, `GplCadEngineBridge` in `me.hlebkukleuski.fullfilestudio.core.engine.bridge`) and lightweight reflective loaders (`GplCoreEngineBridgeLoader`, `GplCadEngineBridgeLoader`).
   - Clean-room modules (`:core:*`, `:features:office`, `:features:cadvector`, `:features:archives`) **never** depend on `:features:gpl-engines`.
   - `:features:gpl-engines` depends inward on `:core:*` / `:features:cadvector` and implements the SPI bridges. If `:features:gpl-engines` is omitted from `app/build.gradle.kts`, every clean-room module compiles cleanly and gracefully reports unavailable GPL formats via `UnavailableGplDocumentSession`.
3. **File-Level `MPL-2.0` Isolation for SVG:**
   The `resvg`-derived SVG engine resides in its own dedicated package (`me.hlebkukleuski.fullfilestudio.core.document.svg`) with explicit `MPL-2.0` headers, satisfying Mozilla Public License 2.0 file-level copyleft boundaries.

## Consequences

- **Positive:** Clean-room vs third-party GPL provenance is auditable at the Gradle module graph level (`settings.gradle.kts`) and enforced by `verifyModuleArchitecture` (which forbids `CleanRoom*` naming inside `:features:gpl-engines`).
- **Positive:** Licensees of clean-room modules can drop `:features:gpl-engines` with a single dependency line change without breaking compilation.
- **Trade-off:** Cross-module extension requires defining narrow SPI interfaces in `:core:engine-api` rather than direct class imports from `:features:gpl-engines`.
