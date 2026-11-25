/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.feature.office

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.document.DocumentRendererEngineImpl
import me.hlebkukleuski.fullfilestudio.core.document.LegacyAndIsoEngine
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileEngineProvider
import me.hlebkukleuski.fullfilestudio.core.engine.FileSignatureDetector
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class OfficeEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return ext in setOf(
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp",
            "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf", "one",
            "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc"
        )
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        return createSessionForExtension(fd, "")
    }

    fun createSessionForExtension(
        fd: FileDescriptor,
        extension: String,
        password: String? = null
    ): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val headerSource = ZeroCacheReadableSource(fd)
        val header = ByteBuffer.allocateDirect(4096)
        val fileSize = headerSource.size.let { if (it > 0L) it else -1L }
        try {
            val r = headerSource.readAt(0L, header)
            if (r > 0) header.flip() else header.limit(0)
        } catch (_: Exception) {
            header.limit(0)
        }
        val detected = FileSignatureDetector.resolveFormat(fd, header, ext, fileSize)

        when (detected) {
            DetectedFormat.PPTX -> return PptxPresentationSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.ODP -> return OdpPresentationSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.XLSX -> return OoxmlSpreadsheetSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.ODS -> return OdsSpreadsheetSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.DOCX, DetectedFormat.ODT, DetectedFormat.EPUB, DetectedFormat.FB2,
            DetectedFormat.AZW3, DetectedFormat.MOBI, DetectedFormat.KINDLE_DRM, DetectedFormat.KFX -> {
                return DocumentRendererEngineImpl().createSessionForFormat(fd, detected, ext, password)
            }
            DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT, DetectedFormat.RTF, DetectedFormat.ONE -> {
                return LegacyAndIsoEngine.createSession(fd, detected, ext, password)
            }
            else -> {}
        }

        if (ext in setOf("csv", "tsv") || (ext in setOf("xls", "xlt") && detected == DetectedFormat.PLAINTEXT)) {
            return Rfc4180CsvTsvSession(ParcelFileDescriptor.dup(fd), if (ext == "tsv") "tsv" else "csv")
        }

        if (ext in setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "one")) {
            return LegacyAndIsoEngine.createSession(fd, detected, ext, password)
        }

        val pfd = ParcelFileDescriptor.dup(fd)
        return when (ext) {
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> PptxPresentationSession(pfd, password)
            "odp", "otp" -> OdpPresentationSession(pfd, password)
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> OoxmlSpreadsheetSession(pfd, password)
            "ods", "ots" -> OdsSpreadsheetSession(pfd, password)
            else -> DocumentRendererEngineImpl().createSessionForFormat(pfd.fileDescriptor, detected, ext, password).also {
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }
}
