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

package me.hlebkukleuski.fullfilestudio.feature.archives

import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.DynamicEngineFactory
import java.io.FileDescriptor
import java.util.Locale

class ArchiveEngineFactory : DynamicEngineFactory {
    private val engine = ArchiveNavigatorEngineImpl()

    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return engine.canHandle(signature, ext)
    }

    override fun createSession(fd: FileDescriptor, extension: String): DocumentSession {
        return engine.createSession(fd)
    }

    override fun createSessionWithPassword(fd: FileDescriptor, extension: String, password: String?): DocumentSession {
        return engine.createArchiveSession(fd, password)
    }
}