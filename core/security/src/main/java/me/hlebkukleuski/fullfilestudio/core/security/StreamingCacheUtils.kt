/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.core.security

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

object StreamingCacheUtils {

    private const val PROGRESS_THROTTLE_MS = 100L
    private const val STALE_CACHE_MAX_AGE_MS = 30L * 60L * 1000L

    fun isRandomAccessSupported(fd: FileDescriptor): Boolean {
        return try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                return false
            }
            Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            val probe = ByteBuffer.allocateDirect(1)
            val read = Os.pread(fd, probe, 0L)
            read > 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun queryContentSizeHint(
        contentResolver: ContentResolver,
        uri: Uri,
        originalPfd: ParcelFileDescriptor
    ): Long {
        try {
            val pfdSize = originalPfd.statSize
            if (pfdSize > 0L) return pfdSize
        } catch (_: Throwable) {}

        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val colSize = cursor.getLong(0)
                    if (colSize > 0L) return colSize
                }
            }
        } catch (_: Throwable) {}

        return -1L
    }

    private fun cleanupStaleStreamCacheFiles(cacheDir: File) {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.name.startsWith("stream_cache_") &&
                    now - file.lastModified() > STALE_CACHE_MAX_AGE_MS
                ) {
                    try { file.delete() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Buffers non-seekable streams (such as cloud pipes or zero-length FUSE descriptors) into a local
     * cache file while throttling UI progress updates to at most once every 100 ms.
     */
    fun ensureSeekableParcelFileDescriptor(
        contentResolver: ContentResolver,
        uri: Uri,
        originalPfd: ParcelFileDescriptor,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): ParcelFileDescriptor {
        val fd = originalPfd.fileDescriptor
        val stat = try { Os.fstat(fd) } catch (_: Exception) { null }
        val hasValidSize = (stat?.st_size ?: 0L) > 0L
        val isSeekableAndPreadable = isRandomAccessSupported(fd)

        if (isSeekableAndPreadable && hasValidSize) {
            try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            return originalPfd
        }

        var tempFile: File? = null
        try {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            cleanupStaleStreamCacheFiles(cacheDir)

            tempFile = File.createTempFile("stream_cache_", ".tmp", cacheDir)
            tempFile.deleteOnExit()

            val totalBytesHint = queryContentSizeHint(contentResolver, uri, originalPfd)

            try {
                Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            } catch (_: Throwable) {
            }

            ParcelFileDescriptor.AutoCloseInputStream(originalPfd).use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var totalWritten = 0L
                    var lastReportTime = 0L

                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        outputStream.write(buffer, 0, read)
                        totalWritten += read

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastReportTime >= PROGRESS_THROTTLE_MS ||
                            (totalBytesHint > 0L && totalWritten == totalBytesHint)
                        ) {
                            lastReportTime = currentTime
                            if (totalBytesHint > 0L) {
                                val progress = (totalWritten.toFloat() / totalBytesHint.toFloat()).coerceIn(0.01f, 0.99f)
                                onProgress(progress)
                            } else {
                                onProgress(0.5f)
                            }
                        }
                    }
                    outputStream.flush()
                }
            }

            onProgress(1.0f)
            val cachedPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            try { tempFile?.delete() } catch (_: Throwable) {}
            return cachedPfd
        } catch (e: Throwable) {
            try { tempFile?.delete() } catch (_: Throwable) {}
            try { originalPfd.close() } catch (_: Throwable) {}
            throw IllegalStateException("Failed to buffer streaming file: ${e.message}", e)
        }
    }
}
