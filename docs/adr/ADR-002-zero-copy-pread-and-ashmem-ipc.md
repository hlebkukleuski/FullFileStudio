# ADR-002: Zero-Copy Positional I/O (`Os.pread`) & 16 KB-Aligned `SharedMemory` Tile IPC

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

Two classic bottlenecks cause Android document and CAD viewers to stutter or crash on multi-hundred-megabyte files:

1. **Temp-File Copying & Stream Re-Reading:** Copying `content://` streams into `cacheDir` doubles storage I/O, triggers flash wear, fails inside `isolatedProcess="true"` (where `cacheDir` is inaccessible), and pollutes the Linux page cache.
2. **Binder Transaction Buffer Exhaustion (`TransactionTooLargeException`):** Android's per-process Binder mmap buffer is shared across all active transactions (~1 MB total). Returning `Bitmap` parcels or multi-megabyte 3D triangle arrays directly over AIDL fails or stalls under rapid pan/zoom tile requests.

## Decision

1. **Thread-Safe Positional Reads via `ZeroCacheReadableSource`:**
   All random-access engines read directly from the caller's `FileDescriptor` using stateless POSIX `Os.pread(fd, directByteBuffer, offset)` (with automatic `EINTR` retry and `FileChannel.read(buf, position)` fallback for synthetic descriptors). Because `pread` does not mutate the kernel file offset, concurrent tile renderers read different regions without seek locks. Sequential large-file scans invoke `posix_fadvise(..., POSIX_FADV_DONTNEED)` via `SecurityArena` to avoid evicting hot UI pages.
2. **16 KB-Aligned `SharedMemory` (`ashmem`) Tile & Mesh Transport:**
   - For 2D document/image/spreadsheet/CAD tiles, `ProxyDocumentSession` in `:app` allocates a `SharedMemory` region sized to `((width * height * 4) + 16383) and -16384` (16 KB page-aligned for Android 15+ 16 KB kernels), passes the `SharedMemory` handle over AIDL `renderTile(...)`, and copies the rendered `ARGB_8888` pixels into a pooled `Bitmap` without touching the 1 MB Binder buffer.
   - Inside `:render`, `IsolatedRenderService` reuses `ARGB_8888` bitmaps via `AdaptiveRingBitmapPool` (bounded to 24 MB, trimmed to zero on `closeSession` / `onTrimMemory`), eliminating per-tile bitmap GC churn.
   - For 3D scenes (`MeshSceneSource`), `exportMeshScene` serializes geometry into `SharedMemory`, seals it read-only with `setProtect(PROT_READ)`, and returns the descriptor to `:app`'s OpenGL ES 3.0 renderer (`Gles30MeshRenderer`).

## Consequences

- **Positive:** Multi-hundred-megabyte PDFs, archives, databases, and 3D scenes open instantaneously with O(1) startup memory and zero temp-file copies for seekable descriptors.
- **Positive:** Binder transactions carry only compact file descriptors and primitive coordinates (< 256 bytes per IPC call), completely eliminating `TransactionTooLargeException`.
- **Trade-off:** Callers must rigorously `SharedMemory.unmap(...)` and `close()` descriptors in `finally` blocks; this lifecycle is centralized in `ProxyDocumentSession` and `IsolatedRenderService` and verified under Robolectric IPC tests (`IsolatedRenderServiceIpcTest`).
