# ADR-006: Unidirectional Data Flow (`ReaderViewModel` + `SavedStateHandle`) & 120 Hz Tile Canvas

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

Holding document sessions, archive back-stacks, password prompts, and Binder connections inside `MainActivity` or giant `@Composable` functions makes process-death recovery fragile, couples business logic to Android UI lifecycles, and causes unnecessary Compose recompositions during high-frequency pan/zoom gestures and neural OCR overlays.

## Decision

1. **Pure UDF Architecture with Constructor-Injected `AppContainer`:**
   - `MainActivity` (< 200 LOC) is restricted to system bars, Storage Access Framework (`OpenDocument`) launchers, display refresh-rate preference (`preferredDisplayModeId`), and hosting `ReaderRootScreen`.
   - `ReaderViewModel` extends `androidx.lifecycle.ViewModel` (zero `Application` or `Activity` references) and receives `SavedStateHandle`, `IsolatedRenderClient`, `DocumentIntentResolver`, `FirstLaunchEngineBootstrapper`, `StandaloneUpdateManager`, and `AppSettingsManager` from `AppContainer`.
   - All UI mutations flow through `dispatch(ReaderUiAction)` into a single `StateFlow<ReaderUiState>` marked `@Immutable`.
2. **Process-Death Resilience via `SavedStateHandle`:**
   `ReaderViewModel` persists `last_opened_uri`, `saved_page_index`, and `saved_sheet_index` in `SavedStateHandle`. After OS process recreation, if the persisted URI still holds a valid read grant, `ReaderViewModel` automatically reopens the session and restores the exact page and spreadsheet tab.
3. **Compose Strong Skipping & Stability Contracts:**
   Compose Compiler reports and metrics are generated in `app/build/compose_compiler/`, and UI state/settings/theme models (`ReaderUiState`, `StructuredUiError`, `AppStrings`, `AppPalette`, `AppSettingsManager`, `ActiveAudioDeviceCapabilities`, `StandaloneUpdateInfo`) carry explicit `@Immutable` or `@Stable` guarantees.
4. **Viewport-Culled 120 Hz `TiledZoomableCanvas`:**
   Pan/zoom transforms update draw-phase state without re-laying out the screen tree. Visible tile keys (`PackedTileKey` — zero-allocation `value class` packing zoom, page, tileX, and tileY into a single `Long`) are scheduled asynchronously on background workers with debounced zoom levels and rendered from pooled `ARGB_8888` bitmaps.

## Consequences

- **Positive:** `ReaderViewModel` is 100% unit-testable with fake `IsolatedRenderClient` implementations in milliseconds (`ReaderViewModelAndIntentTest`), including deterministic process-death restoration tests.
- **Positive:** Smooth 60/90/120 Hz pan/zoom rendering with zero `PackedTileKey` boxing allocations and debug `StrictMode` verification on the main thread.
- **Trade-off:** Session-mutating operations (such as archive entry navigation or password unlock) must explicitly model state transitions in `ReaderUiState` rather than mutating local composable state.
