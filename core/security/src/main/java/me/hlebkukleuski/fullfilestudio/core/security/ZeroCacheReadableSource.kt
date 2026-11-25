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

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ZeroCacheReadableSource(
    private val fd: FileDescriptor,
    private val rawFd: Int = -1
) : AutoCloseable {

    constructor(pfd: ParcelFileDescriptor) : this(
        pfd.fileDescriptor,
        try { pfd.fd } catch (_: Throwable) { -1 }
    )

    private val fallbackSeekLock = Any()
    private val fallbackChannel: FileChannel? by lazy {
        try { FileInputStream(fd).channel } catch (_: Throwable) { null }
    }
    val size: Long

    init {
        size = resolveDescriptorSize(fd)
    }

    private fun resolveDescriptorSize(descriptor: FileDescriptor): Long {
        try {
            val stat = Os.fstat(descriptor)
            if (stat.st_size > 0L) {
                return stat.st_size
            }
        } catch (_: Throwable) {}

        try {
            val cur = Os.lseek(descriptor, 0L, OsConstants.SEEK_CUR)
            val end = Os.lseek(descriptor, 0L, OsConstants.SEEK_END)
            Os.lseek(descriptor, cur, OsConstants.SEEK_SET)
            if (end > 0L) {
                return end
            }
        } catch (_: Throwable) {}

        try {
            val channelSize = fallbackChannel?.size() ?: 0L
            if (channelSize > 0L) return channelSize
        } catch (_: Throwable) {}

        return 0L
    }

    fun readAt(offset: Long, buffer: ByteBuffer): Int {
        if (offset < 0L || !buffer.hasRemaining()) return -1
        val startPos = buffer.position()
        try {
            var totalRead = 0
            while (buffer.hasRemaining()) {
                val read = try {
                    Os.pread(fd, buffer, offset + totalRead)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (read <= 0) break
                totalRead += read
            }
            if (totalRead > 0) return totalRead
        } catch (_: Throwable) {
            buffer.position(startPos)
            val read = readViaSeekFallback(offset, buffer)
            if (read > 0) return read
            buffer.position(startPos)
            return readViaChannel(offset, buffer)
        }
        if (offset < size) {
            buffer.position(startPos)
            return readViaChannel(offset, buffer)
        }
        return -1
    }

    private fun readViaChannel(offset: Long, buffer: ByteBuffer): Int {
        val channel = fallbackChannel ?: return -1
        return try {
            var totalRead = 0
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, offset + totalRead)
                if (read <= 0) break
                totalRead += read
            }
            if (totalRead > 0) totalRead else -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readViaSeekFallback(offset: Long, buffer: ByteBuffer): Int {
        synchronized(fallbackSeekLock) {
            return try {
                val savedPos = try {
                    Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
                } catch (_: Throwable) {
                    -1L
                }
                Os.lseek(fd, offset, OsConstants.SEEK_SET)
                val read = Os.read(fd, buffer)
                if (savedPos >= 0L) {
                    try { Os.lseek(fd, savedPos, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                }
                if (read > 0) read else -1
            } catch (_: Throwable) {
                -1
            }
        }
    }

    fun readAndDropCache(offset: Long, buffer: ByteBuffer): Int {
        val read = readAt(offset, buffer)
        if (read > 0) {
            try {
                if (rawFd >= 0) {
                    SecurityArena.safeFadviseDontNeed(rawFd, offset, read.toLong())
                } else {
                    SecurityArena.fadviseDontNeedNative(fd, offset, read.toLong())
                }
            } catch (_: Throwable) {}
        }
        return read
    }

    override fun close() {}
}
