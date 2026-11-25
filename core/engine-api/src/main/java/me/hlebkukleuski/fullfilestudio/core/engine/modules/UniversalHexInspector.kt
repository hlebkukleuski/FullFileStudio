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

package me.hlebkukleuski.fullfilestudio.core.engine.modules

import java.io.FileDescriptor
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileEngineProvider

/**
 * Fallback hex inspector for arbitrary or corrupted binary files.
 */
interface UniversalHexInspector : FileEngineProvider {
    fun inspect(fd: FileDescriptor): HexSession
}

interface HexSession : DocumentSession {
    /**
     * Scans the file descriptor for a byte sequence starting at [startOffset] using native SIMD/NEON acceleration.
     */
    fun findSequenceWithNeonFast(sequence: ByteArray, startOffset: Long): Long
}
