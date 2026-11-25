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
 *
 * Implements OLE2 Compound File Binary ([MS-CFB]), Excel BIFF8/BIFF12 ([MS-XLS] / [MS-XLSB]),
 * Word Binary ([MS-DOC]), PowerPoint Binary ([MS-PPT]), OneNote ([MS-ONE] / [MS-ONESTORE])
 * under the Microsoft Open Specifications Promise (OSP), ISO-9660 (ECMA-119), and dBase DBF,
 * with BIFF8/OLE2 structure definitions informed by Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

object OneNoteEncryptionDetector {
    private val ENCRYPTION_KEY_ROOT_GUID = byteArrayOf(
        0xF8.toByte(), 0x17, 0x37, 0x4A, 0x14, 0x1C, 0xE7.toByte(), 0x49,
        0x95.toByte(), 0x26, 0x81.toByte(), 0xD9.toByte(), 0x42, 0xDE.toByte(), 0x17, 0x41
    )

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < ENCRYPTION_KEY_ROOT_GUID.size) return false
        val limit = bytes.size - ENCRYPTION_KEY_ROOT_GUID.size
        val first = ENCRYPTION_KEY_ROOT_GUID[0]
        var i = 0
        while (i <= limit) {
            if (bytes[i] == first) {
                var ok = true
                for (k in 1 until ENCRYPTION_KEY_ROOT_GUID.size) {
                    if (bytes[i + k] != ENCRYPTION_KEY_ROOT_GUID[k]) {
                        ok = false
                        break
                    }
                }
                if (ok) return true
            }
            i++
        }
        return false
    }
}

class OneNoteDocumentSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 48L * 1024L * 1024L).toInt()
            if (size < 64) {
                lastErrorMsg = "OneNote (.one) file is empty or truncated."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (OneNoteEncryptionDetector.isEncrypted(bytes)) {
                    lastErrorMsg = "UNSUPPORTED_ENCRYPTION: Password decryption for encrypted OneNote (.one) sections is not supported in this version."
                } else {
                    embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(bytes, 16))
                    fullText = extractOneNoteStructuredContent(bytes, embeddedImages.size)
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "OneNote read error: ${e.localizedMessage}"
        }
        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "OneNote container contains no readable text or images."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun extractOneNoteStructuredContent(bytes: ByteArray, imageCount: Int): String {
        val blocks = LinkedHashSet<String>()

        var i = 0
        while (i + 8 < bytes.size && blocks.size < 4096) {
            val runSb = StringBuilder()
            var j = i
            while (j + 1 < bytes.size && runSb.length < 16384) {
                val lo = bytes[j].toInt() and 0xFF
                val hi = bytes[j + 1].toInt() and 0xFF
                val ch = ((hi shl 8) or lo).toChar()
                val isPrintableUnicode = (hi == 0x00 && (lo in 0x20..0x7E || lo == 0x0A || lo == 0x0D || lo == 0x09)) ||
                        (ch in '\u0400'..'\u04FF') ||
                        (ch in '\u00A0'..'\u024F') ||
                        (ch in '\u2000'..'\u206F')
                if (isPrintableUnicode) {
                    runSb.append(ch)
                    j += 2
                } else {
                    break
                }
            }
            if (runSb.length >= 4) {
                val cleaned = runSb.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
                if (cleaned.length >= 4 && cleaned.any { it.isLetter() } &&
                    !cleaned.equals("OneNote", ignoreCase = true) &&
                    !cleaned.startsWith("Calibri", ignoreCase = true)
                ) {
                    blocks.add(cleaned)
                }
                i = j
            } else {
                i += 2
            }
        }

        if (blocks.size < 3) {
            val asciiRun = StringBuilder()
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v == 10 || v == 13 || v in 32..126) {
                    if (asciiRun.length < 16384) asciiRun.append(v.toChar())
                } else {
                    if (asciiRun.length >= 8 && blocks.size < 4096) {
                        val s = asciiRun.toString().trim()
                        if (s.any { it.isLetter() }) blocks.add(s)
                    }
                    asciiRun.setLength(0)
                }
            }
        }

        val sb = StringBuilder()
        blocks.forEachIndexed { idx, block ->
            if (idx == 0 && block.length <= 80 && !block.contains('\n')) {
                sb.append("<h1>").append(block).append("</h1>\n")
            } else {
                sb.append(block).append("\n")
            }
        }
        for (imgIdx in 0 until imageCount) {
            sb.append("[IMAGE:$imgIdx]\n")
        }
        return sb.toString().trim()
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
        } else {
            cleanIdx
        }
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)
        EbookTextSession.renderRichDocumentPageToCanvas(
            canvas = canvas,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pageIndex = effectivePageIndex,
            tileX = tileX,
            tileY = tileY,
            zoom = zoom,
            isLightTheme = isLightTheme,
            pageText = getPageText(cleanIdx),
            embeddedImages = embeddedImages,
            baseTextPaint = textPaint
        )
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) if (pages[i].contains(query, true)) return i.toLong()
        for (i in 0 until start) if (pages[i].contains(query, true)) return i.toLong()
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        embeddedImages.forEach { if (!it.isRecycled) it.recycle() }
        embeddedImages.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Clean-Room preview & project metadata extractor for `MAX` (Autodesk 3ds Max), `RVT` (Autodesk Revit),
 * and `INDD` (Adobe InDesign).
 */
class ProprietaryContainerPreviewSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat,
    private val extension: String
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "Project container file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                parsePreviewAndMetadata(bytes)
            }
        } catch (e: Exception) {
            lastErrorMsg = "Container preview read error: ${e.localizedMessage}"
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun parsePreviewAndMetadata(bytes: ByteArray) {
        val sb = StringBuilder()
        val label = when {
            format == DetectedFormat.RVT || extension == "rvt" -> "Revit BIM Project (.rvt)"
            format == DetectedFormat.MAX || extension == "max" -> "3ds Max Scene (.max)"
            format == DetectedFormat.INDD || extension == "indd" -> "InDesign Document (.indd)"
            else -> "Project Container (.${extension.uppercase(Locale.ROOT)})"
        }
        sb.append("<h1>").append(label).append("</h1>\n")

        val cfb = Ole2CfbContainer(bytes)
        if (cfb.isValid) {
            val streamNames = cfb.listStreams().map { "${it.name} (${it.size} B)" }
            if (streamNames.isNotEmpty()) {
                sb.append("<h2>OLE2 Container Streams</h2>\n")
                streamNames.take(25).forEach { sb.append("• ").append(it).append('\n') }
            }

            cfb.readStream("BasicFileInfo")?.let { infoBytes ->
                val infoText = String(infoBytes, StandardCharsets.UTF_16LE)
                    .replace('\u0000', ' ')
                    .lines()
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .joinToString("\n")
                if (infoText.isNotBlank()) {
                    sb.append("<h2>Project Metadata (BasicFileInfo)</h2>\n").append(infoText).append("\n")
                }
            }

            cfb.readStream("RevitPreview4.0")?.let { prevBytes ->
                embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(prevBytes, 4))
            }

            if (embeddedImages.isEmpty()) {
                for (entry in cfb.listStreams()) {
                    if (embeddedImages.size >= 6) break
                    val sBytes = cfb.readStream(entry.name) ?: continue
                    embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(sBytes, 4))
                }
            }
        }

        val latinStr = String(bytes, 0, minOf(bytes.size, 8 * 1024 * 1024), StandardCharsets.ISO_8859_1)
        val xmpImgMatches = Regex("<xmpGImg:image>([\\s\\S]*?)</xmpGImg:image>", RegexOption.IGNORE_CASE).findAll(latinStr)
        for (m in xmpImgMatches) {
            if (embeddedImages.size >= 8) break
            val b64 = m.groupValues[1].replace("&#xA;", "\n").replace("\\s+".toRegex(), "").trim()
            if (b64.length in 64..(16 * 1024 * 1024)) {
                try {
                    val imgBytes = Base64.decode(b64, Base64.DEFAULT)
                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let {
                        embeddedImages.add(it)
                    }
                } catch (_: Throwable) {}
            }
        }

        if (embeddedImages.isEmpty()) {
            embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(bytes, 6))
        }

        for (imgIdx in embeddedImages.indices) {
            sb.append("[IMAGE:$imgIdx]\n")
        }

        val xmpTagRegex = Regex("<(dc:title|dc:creator|dc:description|xmp:CreatorTool|xmp:CreateDate|xmp:ModifyDate)[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        val xmpFields = ArrayList<String>()
        for (m in xmpTagRegex.findAll(latinStr)) {
            val key = m.groupValues[1]
            val valClean = m.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (valClean.isNotEmpty()) {
                xmpFields.add("$key\t$valClean")
            }
        }
        if (xmpFields.isNotEmpty()) {
            sb.append("<h2>XMP Document Properties</h2>\n")
            sb.append("Property\tValue\n")
            xmpFields.forEach { sb.append(it).append('\n') }
        }

        val printableSummary = LegacyDocSession.extractPrintableRunsFromBinary(
            bytes.copyOf(minOf(bytes.size, 512 * 1024))
        )
        if (printableSummary.isNotBlank()) {
            sb.append("<h2>Extracted Container Strings</h2>\n")
            sb.append(printableSummary.lines().take(120).joinToString("\n"))
        }

        fullText = sb.toString().trim()
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
        } else {
            cleanIdx
        }
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)
        EbookTextSession.renderRichDocumentPageToCanvas(
            canvas = canvas,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pageIndex = effectivePageIndex,
            tileX = tileX,
            tileY = tileY,
            zoom = zoom,
            isLightTheme = isLightTheme,
            pageText = getPageText(cleanIdx),
            embeddedImages = embeddedImages,
            baseTextPaint = textPaint
        )
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) if (pages[i].contains(query, true)) return i.toLong()
        for (i in 0 until start) if (pages[i].contains(query, true)) return i.toLong()
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        embeddedImages.forEach { if (!it.isRecycled) it.recycle() }
        embeddedImages.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}
