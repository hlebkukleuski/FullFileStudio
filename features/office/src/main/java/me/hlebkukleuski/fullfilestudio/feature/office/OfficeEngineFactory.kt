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

package me.hlebkukleuski.fullfilestudio.feature.office

import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.DynamicEngineFactory

class OfficeEngineFactory : DynamicEngineFactory {

    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in SUPPORTED_OFFICE_EXTENSIONS) {
            return true
        }
        // OLE2 Compound Document magic: D0 CF 11 E0
        if (signature.size >= 4 &&
            (signature[0].toInt() and 0xFF) == 0xD0 &&
            (signature[1].toInt() and 0xFF) == 0xCF &&
            (signature[2].toInt() and 0xFF) == 0x11 &&
            (signature[3].toInt() and 0xFF) == 0xE0
        ) {
            return true
        }
        // RTF magic: "{\rtf"
        if (signature.size >= 5 &&
            String(signature, 0, 5, StandardCharsets.US_ASCII) == "{\\rtf"
        ) {
            return true
        }
        return false
    }

    override fun createSession(fd: FileDescriptor, extension: String): DocumentSession {
        return OfficeEngineImpl().createSessionForExtension(fd, extension, null)
    }

    override fun createSessionWithPassword(fd: FileDescriptor, extension: String, password: String?): DocumentSession {
        return OfficeEngineImpl().createSessionForExtension(fd, extension, password)
    }

    companion object {
        private val SUPPORTED_OFFICE_EXTENSIONS = setOf(
            "docx", "docm", "dotx", "dotm", "doc", "dot",
            "odt", "ott", "rtf", "one",
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt",
            "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot",
            "odp", "otp",
            "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc"
        )
    }
}
