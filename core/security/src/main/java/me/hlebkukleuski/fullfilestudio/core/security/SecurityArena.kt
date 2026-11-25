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

import java.io.FileDescriptor

object SecurityArena {
    const val FIND_NOT_FOUND = -1L
    const val FIND_UNAVAILABLE = -2L

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("fullfilestudio_security")
        true
    } catch (e: Throwable) {
        try { android.util.Log.w("FullFileStudio", "Native load warning", e) } catch (_: Throwable) {}
        false
    }

    external fun fadviseDontNeedFdNative(fd: Int, offset: Long, len: Long)
    external fun fadviseDontNeedNative(fd: FileDescriptor, offset: Long, len: Long)
    external fun setupSeccompBPF(): Boolean
    external fun findSequenceNeonFdNative(fd: Int, startOffset: Long, size: Long, sequence: ByteArray): Long
    external fun findSequenceNeonNative(fd: FileDescriptor, startOffset: Long, size: Long, sequence: ByteArray): Long

    fun safeSetupSeccomp(): Boolean {
        if (!isNativeLoaded) return false
        return try { setupSeccompBPF() } catch (_: Throwable) { false }
    }

    fun safeFadviseDontNeed(fd: Int, offset: Long, len: Long) {
        if (!isNativeLoaded || fd < 0 || offset < 0L || len <= 0L) return
        try { fadviseDontNeedFdNative(fd, offset, len) } catch (_: Throwable) {}
    }

    fun safeFindSequenceFd(fd: Int, startOffset: Long, size: Long, sequence: ByteArray): Long {
        if (!isNativeLoaded || fd < 0) return FIND_UNAVAILABLE
        return try {
            findSequenceNeonFdNative(fd, startOffset, size, sequence)
        } catch (_: Throwable) {
            FIND_UNAVAILABLE
        }
    }

    fun safeFindSequence(fd: FileDescriptor, startOffset: Long, size: Long, sequence: ByteArray): Long {
        if (!isNativeLoaded) return FIND_UNAVAILABLE
        return try {
            findSequenceNeonNative(fd, startOffset, size, sequence)
        } catch (_: Throwable) {
            FIND_UNAVAILABLE
        }
    }
}
