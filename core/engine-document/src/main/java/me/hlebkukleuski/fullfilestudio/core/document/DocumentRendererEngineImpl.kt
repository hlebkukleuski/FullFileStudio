/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileEngineProvider
import me.hlebkukleuski.fullfilestudio.core.engine.FileSignatureDetector
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridgeLoader

internal class UnavailableGplDocumentSession(
    private val pfd: ParcelFileDescriptor,
    private val formatName: String
) : DocumentSession {
    override val pageCount: Int = 1
    override fun getLastError(): String = "$formatName engine is not included in this build."
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        bitmap.eraseColor(Color.rgb(0x14, 0x15, 0x1C))
    }
    override fun close() {
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Clean-Room OpenType / TrueType / TTC / WOFF Font Specimen & Glyph Table Inspector (ISO/IEC 14496-22).
 */

class DocumentRendererEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return ext in setOf(
            "pdf", "djvu", "djv",
            "ttf", "otf", "ttc", "woff", "woff2", "dfont",
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur",
            "txt", "log", "json", "xml", "md", "ini", "cfg", "yaml", "yml",
            "fb2", "fbz", "epub", "docx", "docm", "dotx", "dotm", "odt", "ott",
            "azw3", "azw", "mobi", "prc", "kfx", "one", "max", "rvt", "indd",
            "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "iso",
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
            "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
            "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
            "3mf", "dae", "step", "stp", "iges", "igs"
        )
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        val header = ByteBuffer.allocateDirect(4096)
        val fileSize = resolveStreamDescriptorSize(fd)
        try {
            try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            Os.read(fd, header)
            header.flip()
        } catch (_: Exception) {
            header.limit(0)
        }
        val detected = FileSignatureDetector.resolveFormat(fd, header, "", fileSize)
        return createSessionForFormat(fd, detected, "")
    }

    fun createSessionForFormat(
        fd: FileDescriptor,
        format: DetectedFormat,
        extension: String = "",
        password: String? = null
    ): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val isGraphicExtension = ext in setOf(
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur"
        )

        if (format == DetectedFormat.FONT || ext in setOf("ttf", "otf", "ttc", "woff", "woff2", "dfont")) {
            return FontSpecimenSession(ParcelFileDescriptor.dup(fd), ext.ifEmpty { "ttf" })
        }

        if (format == DetectedFormat.DJVU || ext in setOf("djvu", "djv")) {
            val dup = ParcelFileDescriptor.dup(fd)
            return GplCoreEngineBridgeLoader.get()?.createDjvuSession(dup)
                ?: UnavailableGplDocumentSession(dup, "DjVu")
        }

        if (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX || ext == "kfx") {
            return KindleDrmNoticeSession(ParcelFileDescriptor.dup(fd), format)
        }

        if (format in setOf(DetectedFormat.AZW3, DetectedFormat.MOBI) || ext in setOf("azw3", "azw", "mobi", "prc")) {
            val dup = ParcelFileDescriptor.dup(fd)
            return GplCoreEngineBridgeLoader.get()?.createMobiSession(dup, ext.ifEmpty { "mobi" })
                ?: UnavailableGplDocumentSession(dup, "MOBI/AZW3")
        }

        if (format in setOf(
                DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT,
                DetectedFormat.RTF, DetectedFormat.ISO, DetectedFormat.ONE,
                DetectedFormat.MAX, DetectedFormat.RVT, DetectedFormat.INDD,
                DetectedFormat.XLSX, DetectedFormat.ODS,
                DetectedFormat.PPTX, DetectedFormat.ODP,
                DetectedFormat.SVG, DetectedFormat.SVGZ,
                DetectedFormat.STL, DetectedFormat.OBJ, DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB, DetectedFormat.BLEND, DetectedFormat.USDZ
            ) ||
            (ext in setOf(
                "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "iso", "one", "max", "rvt", "indd",
                "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv",
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
                "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
                "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "zae", "step", "stp", "iges", "igs"
            ) && format !in setOf(DetectedFormat.DOCX, DetectedFormat.ODT, DetectedFormat.EPUB, DetectedFormat.FB2))
        ) {
            return LegacyAndIsoEngine.createSession(fd, format, ext, password)
        }

        val dupFd = ParcelFileDescriptor.dup(fd)
        return when {
            format == DetectedFormat.PDF || ext == "pdf" -> PdfDocumentSession(dupFd, password)
            format in setOf(
                DetectedFormat.PNG, DetectedFormat.JPEG, DetectedFormat.GIF,
                DetectedFormat.TIFF, DetectedFormat.WEBP, DetectedFormat.BMP,
                DetectedFormat.HEIC, DetectedFormat.AVIF
            ) || isGraphicExtension -> ImageRegionSession(dupFd, format, ext)
            format == DetectedFormat.DOCX || ext in setOf("docx", "docm", "dotx", "dotm") ->
                LegacyDocSession(dupFd, password)
            format in setOf(
                DetectedFormat.FB2, DetectedFormat.EPUB, DetectedFormat.ODT, DetectedFormat.MHTML
            ) || ext in setOf("fb2", "fbz", "epub", "odt", "ott", "mhtml", "mht") ->
                EbookTextSession(dupFd, format, ext, password)
            else -> PlainTextSession(dupFd)
        }
    }
}

/**
 * Lock-separated, non-blocking PDF session.
 * Uses [rendererLock] strictly for fast `PdfRenderer` operations (`renderTile`, `getPageAspectRatio`)
 * and [textExtractLock] for lazy per-page text extraction so `getPageText` never stalls page turns.
 * On Android 15+ (API 35+, including HyperOS), extracts exact text and line-level bounding boxes
 * directly from the system `PdfRenderer.Page.getTextContents()` API.
 */
