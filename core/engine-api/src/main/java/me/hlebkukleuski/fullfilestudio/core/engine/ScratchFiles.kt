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

package me.hlebkukleuski.fullfilestudio.core.engine

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ScratchFiles {

    private val memoryFiles = ConcurrentHashMap<String, ParcelFileDescriptor>()

    private fun defaultDirectories(): List<File> = listOfNotNull(
        System.getProperty("fullfilestudio.cache.dir")?.let { File(it) },
        System.getProperty("java.io.tmpdir")?.let { File(it) }
    )

    fun createTempFile(prefix: String, suffix: String, preferredDir: File? = null): File {
        if (!Process.isIsolated()) {
            for (dir in listOfNotNull(preferredDir) + defaultDirectories()) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (dir.isDirectory && dir.canWrite()) {
                        return File.createTempFile(prefix, suffix, dir).apply { deleteOnExit() }
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return createMemoryFile(prefix + suffix)
    }

    private fun createMemoryFile(name: String): File {
        val raw = Os.memfd_create(name, OsConstants.MFD_CLOEXEC)
        val pfd = try {
            ParcelFileDescriptor.dup(raw)
        } finally {
            Os.close(raw)
        }
        val file = File("/proc/self/fd/${pfd.fd}")
        memoryFiles[file.path] = pfd
        return file
    }

    fun isMemoryFile(file: File): Boolean = memoryFiles.containsKey(file.path)

    fun exists(file: File): Boolean = memoryFiles.containsKey(file.path) || file.exists()

    fun openOutput(file: File): java.io.OutputStream {
        val pfd = memoryFiles[file.path] ?: return java.io.FileOutputStream(file, false)
        Os.ftruncate(pfd.fileDescriptor, 0L)
        Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET)
        return ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(pfd.fileDescriptor))
    }

    fun openReadOnlyDescriptor(file: File): java.io.FileDescriptor? = memoryFiles[file.path]?.fileDescriptor

    fun delete(file: File?) {
        if (file == null) return
        val pfd = memoryFiles.remove(file.path)
        if (pfd != null) {
            try { pfd.close() } catch (_: Throwable) { }
        } else {
            try { file.delete() } catch (_: Throwable) { }
        }
    }

    fun openReadOnly(file: File): ParcelFileDescriptor {
        val pfd = memoryFiles[file.path]
        if (pfd != null) {
            val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            Os.lseek(dup.fileDescriptor, 0L, OsConstants.SEEK_SET)
            return dup
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
