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

package com.omnireader.core.engine

import java.io.FileDescriptor

object SecurityArena {
    val isNativeLoaded: Boolean = try {
        System.loadLibrary("engine_api")
        true
    } catch (e: Throwable) {
        e.printStackTrace()
        false
    }

    external fun fadviseDontNeedNative(fd: FileDescriptor, offset: Long, len: Long)
    external fun setupSeccompBPF(): Boolean
    external fun findSequenceNeonNative(fd: FileDescriptor, startOffset: Long, size: Long, sequence: ByteArray): Long

    fun safeSetupSeccomp(): Boolean {
        if (!isNativeLoaded) return false
        return try { setupSeccompBPF() } catch (_: Throwable) { false }
    }

    fun safeFindSequence(fd: FileDescriptor, startOffset: Long, size: Long, sequence: ByteArray): Long {
        if (!isNativeLoaded) return -1L
        return try {
            findSequenceNeonNative(fd, startOffset, size, sequence)
        } catch (_: Throwable) {
            -1L
        }
    }
}