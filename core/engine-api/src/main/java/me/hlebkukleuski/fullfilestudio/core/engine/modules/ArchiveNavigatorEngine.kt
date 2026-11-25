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

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession

interface ArchiveNavigatorEngine {
    fun canHandle(signature: ByteArray, extension: String): Boolean
    fun createSession(fd: FileDescriptor): DocumentSession
    fun createArchiveSession(fd: FileDescriptor): ArchiveSession
}

interface ArchiveSession : DocumentSession {
    val isZipBombDetected: Boolean
    val sanitizedFileEntries: List<String>
    fun openVirtualFile(entryPath: String): ParcelFileDescriptor?
}
