# ADR-001: Unprivileged Isolated Process & `seccomp-bpf` Sandbox for Untrusted Parsers

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

FullFileStudio opens arbitrary, untrusted binary containers (`PDF`, `DOCX/DOC`, `XLSX/XLS`, `PPTX/PPT`, `DWG`, `BLEND`, `USD`, `GLTF/Draco`, `RAR4/RAR5`, `7Z`, `SQLite`, `Parquet`, `DuckDB`, `Realm`, `DjVu`, `MOBI`, images, fonts) directly from external intents, email attachments, messaging apps, and nested archives. Complex binary format parsers—even in memory-safe Kotlin—still interact with native image/PDF/compression codecs and large allocations where malformed inputs can trigger:

1. Out-of-memory kills (`OutOfMemoryError` or native `SIGABRT`) that would otherwise terminate the user's UI process.
2. Path traversal, file-system writes, or unintended socket creation if a parser or transitive library contains a vulnerability.
3. Main-thread or heap contention against the 120 Hz Compose UI renderer.

## Decision

1. **Dedicated `:render` Process (`android:isolatedProcess="true"`):**
   `IsolatedRenderService` runs in a separate Android process (`:render`) with `android:isolatedProcess="true"` and `android:allowBackup="false"`. Android assigns `:render` an ephemeral, unprivileged UID with **zero filesystem permissions**, **zero network access**, and **zero access to app-private directories** (`filesDir`, `databases`, `shared_prefs`).
2. **Defense-in-Depth `seccomp-bpf` Syscall Filter:**
   Upon `IsolatedRenderService.onCreate()` inside an isolated process, `SecurityArena.safeSetupSeccomp()` (`core/security/src/main/cpp/native_security_arena.cpp`) sets `PR_SET_NO_NEW_PRIVS` and installs a classic BPF filter (`SECCOMP_SET_MODE_FILTER`) validated against `AUDIT_ARCH_AARCH64` / `AUDIT_ARCH_X86_64` that explicitly traps (`SECCOMP_RET_ERRNO(EPERM)`) process execution and outbound socket syscalls (`execve`, `execveat`, `socket`, `connect`, `bind`, `listen`, `accept4`, `sendto`, `ptrace`).
3. **Read-Only File-Descriptor Handoff & Anti-Exploit Gatekeeping:**
   - `DocumentIntentResolver` in `:app` resolves incoming `Uri`s strictly in read-only mode, canonicalizing `file://` paths and `proc/self/fd` links to block symlink/hardlink escapes into the app sandbox (`context.applicationInfo.dataDir` or `deviceProtectedDataDir`).
   - Only a `ParcelFileDescriptor` (`MODE_READ_ONLY`) is passed over Binder (`IIsolatedEngineService`) to `:render`.
   - Before invoking any format parser, `IsolatedRenderService` evaluates `FileSignatureDetector`, `LegalLicensePolicy` (blocking DRM containers), and `MaliciousHeaderSanitizer` (blocking `ELF`/`DEX`/Mach-O/PE executables, PDF polyglots, and XML `<!ENTITY`/`<!DOCTYPE` XXE payloads, routing raw binaries to the read-only `UniversalHexInspectorImpl`).
   - Scratch extraction inside `:render` uses anonymous in-RAM `memfd_create` descriptors (`ScratchFiles`) rather than filesystem paths.

## Consequences

- **Positive:** Parser crashes, native signals, or `OutOfMemoryError` inside `:render` never crash `:app`; `AndroidIsolatedRenderClient` observes `onServiceDisconnected` / `DeadObjectException`, surfaces a structured recoverable error (`ReaderUiState.errorDialog`), and automatically rebinds.
- **Positive:** Even a hypothetical parser RCE inside `:render` has no UID privileges, no app storage visibility, and kernel-blocked `socket`/`execve` syscalls.
- **Trade-off:** Cross-process communication requires explicit AIDL contracts (`IIsolatedEngineService`) and `SharedMemory` (`ashmem`) pixel/mesh buffers rather than passing heap objects directly.
