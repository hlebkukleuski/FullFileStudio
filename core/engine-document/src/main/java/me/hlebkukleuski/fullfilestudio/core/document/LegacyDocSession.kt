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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class LegacyDocSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private var paged: me.hlebkukleuski.fullfilestudio.core.document.word.WordPagedDocument? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 32 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "DOC file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }

                if (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII) == "{\\rtf") {
                    fullText = RtfDocumentSession.parseRtfBytes(bytes, embeddedImages)
                } else if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    paged = try {
                        me.hlebkukleuski.fullfilestudio.core.document.word.WordPagedDocument.openDocx(bytes)
                    } catch (_: OutOfMemoryError) {
                        null
                    } catch (_: Throwable) {
                        null
                    }
                    if (paged != null) {
                        fullText = paged?.fullText() ?: ""
                    } else {
                        val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val ebookSession = EbookTextSession(dup, DetectedFormat.DOCX, "docx")
                        try {
                            fullText = ebookSession.getPageText(-1)
                            lastErrorMsg = ebookSession.getLastError()
                            for (i in 0 until ebookSession.getEmbeddedImagesCount()) {
                                ebookSession.getEmbeddedImage(i)?.let { bmp ->
                                    bmp.config?.let { cfg -> embeddedImages.add(bmp.copy(cfg, false)) }
                                }
                            }
                        } finally {
                            ebookSession.close()
                        }
                    }
                } else {
                    val cfb = Ole2CfbContainer(bytes)
                    if (!cfb.isValid) {
                        val fallbackText = tryExtractNonOleDocText(bytes)
                        if (fallbackText.isNotBlank()) {
                            fullText = fallbackText
                        } else {
                            lastErrorMsg = "File is not a valid MS-DOC container (OLE2 CFB)."
                        }
                    } else if (OfficeCrypto.isOfficeIrmProtected(cfb)) {
                        lastErrorMsg = OfficeCrypto.OFFICE_IRM_DRM_ERROR
                    } else {
                        if (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null) {
                            val decryptedZip = if (!password.isNullOrEmpty()) OfficeCrypto.decryptOoxml(cfb, password) else null
                            if (decryptedZip != null) {
                                paged = try {
                                    me.hlebkukleuski.fullfilestudio.core.document.word.WordPagedDocument.openDocx(decryptedZip)
                                } catch (_: OutOfMemoryError) {
                                    null
                                } catch (_: Throwable) {
                                    null
                                }
                                if (paged != null) {
                                    fullText = paged?.fullText() ?: ""
                                } else {
                                    fullText = extractZipDocxTextFallback(decryptedZip)
                                }
                            } else {
                                lastErrorMsg = if (password.isNullOrEmpty()) {
                                    "PASSWORD_REQUIRED: Document is encrypted or password protected."
                                } else {
                                    "PASSWORD_REQUIRED: Incorrect password for encrypted document."
                                }
                            }
                        } else if (openPaged(cfb)) {
                            fullText = paged?.fullText() ?: ""
                        } else if (lastErrorMsg == null) {
                            cfb.readStream("Data")?.let { dataStream ->
                                embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(dataStream, 16))
                                if (embeddedImages.isEmpty()) {
                                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(dataStream)?.let { embeddedImages.add(it) }
                                }
                            }
                            val wordStream = cfb.readStream("WordDocument")
                            if (wordStream == null || wordStream.size < 68) {
                                lastErrorMsg = "Missing WordDocument stream in DOC container."
                            } else {
                                if (embeddedImages.isEmpty()) {
                                    embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(wordStream, 16))
                                }
                                var parsedBody = parseWordDocumentStream(cfb, wordStream)
                                if (embeddedImages.isNotEmpty() && !parsedBody.contains("[IMAGE:")) {
                                    val imgTags = (0 until embeddedImages.size).joinToString("\n") { "[IMAGE:$it]" }
                                    parsedBody = if (parsedBody.isNotEmpty()) "$parsedBody\n$imgTags" else imgTags
                                }
                                fullText = parsedBody
                            }
                        }
                    }
                }
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "DOC read error: ${e.localizedMessage}"
        }

        if (paged == null) {
            if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
                lastErrorMsg = "DOC document contains no readable text or is encrypted."
            }
            EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
        }
    }

    private fun extractZipDocxTextFallback(zipBytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                val buf = ByteArray(16384)
                while (true) {
                    val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                    val norm = entry.name.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
                    if (!entry.isDirectory && norm == "word/document.xml") {
                        val bos = ByteArrayOutputStream()
                        var r: Int
                        while (zis.read(buf).also { r = it } > 0) {
                            if (bos.size() + r > 32 * 1024 * 1024) break
                            bos.write(buf, 0, r)
                        }
                        EbookTextSession.extractXmlBytesWithCharset(bos.toByteArray(), sb)
                    }
                    try { zis.closeEntry() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return sb.toString().trim()
    }

    private fun openPaged(cfb: Ole2CfbContainer): Boolean {
        val rawWord = cfb.readStream("WordDocument") ?: return false
        val tblName = me.hlebkukleuski.fullfilestudio.core.document.word.WordPagedDocument.tableStreamName(rawWord)
        val rawTbl = cfb.readStream(tblName)
        val rawData = cfb.readStream("Data")
        val word: ByteArray
        val tbl: ByteArray?
        val data: ByteArray?
        if (OfficeCrypto.isEncryptedDoc(rawWord)) {
            if (password.isNullOrEmpty()) {
                lastErrorMsg = "PASSWORD_REQUIRED: MS Word document (.doc) is password protected."
                return false
            }
            val dec = OfficeCrypto.decryptDoc(rawWord, rawTbl, rawData, password)
            if (dec == null) {
                lastErrorMsg = "PASSWORD_REQUIRED: Incorrect password for MS Word document (.doc)."
                return false
            }
            word = dec.wordDocument
            tbl = dec.tableStream
            data = dec.dataStream
        } else {
            word = rawWord
            tbl = rawTbl
            data = rawData
        }
        return try {
            paged = me.hlebkukleuski.fullfilestudio.core.document.word.WordPagedDocument.open(word, tbl, data)
            paged != null
        } catch (e: OutOfMemoryError) {
            paged = null
            false
        } catch (sec: SecurityException) {
            paged = null
            lastErrorMsg = sec.message
            false
        } catch (e: Exception) {
            paged = null
            if (e.message?.startsWith("PASSWORD_REQUIRED") == true) lastErrorMsg = e.message
            false
        }
    }

    private fun tryExtractNonOleDocText(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            EbookTextSession.extractXmlBytesWithCharset(
                bytes = bytes,
                out = sb,
                onBinaryImageDecoded = { imgBytes ->
                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes)?.let { bmp ->
                        val idx = embeddedImages.size
                        embeddedImages.add(bmp)
                        sb.append("\n[IMAGE:$idx]\n")
                    }
                }
            )
            if (sb.isNotBlank()) return sb.toString().trim()
        } catch (_: Throwable) {}

        return try {
            val (cs, skip) = PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192))
            String(bytes, skip, (bytes.size - skip).coerceAtLeast(0), cs).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseWordDocumentStream(cfb: Ole2CfbContainer, wordDoc: ByteArray): String {
        val bb = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getShort(10).toInt() and 0xFFFF
        val isEncrypted = (flags and 0x0100) != 0
        if (isEncrypted) {
            lastErrorMsg = "PASSWORD_REQUIRED: MS Word document (.doc) is password protected."
            return ""
        }

        val ccpText = if (wordDoc.size >= 0x0050) bb.getInt(0x004C).coerceAtLeast(0) else 0
        val ccpFtn = if (wordDoc.size >= 0x0054) bb.getInt(0x0050).coerceAtLeast(0) else 0
        val ccpHdd = if (wordDoc.size >= 0x0058) bb.getInt(0x0054).coerceAtLeast(0) else 0
        val ccpAtn = if (wordDoc.size >= 0x0060) bb.getInt(0x005C).coerceAtLeast(0) else 0
        val ccpEdn = if (wordDoc.size >= 0x0064) bb.getInt(0x0060).coerceAtLeast(0) else 0
        val ccpTxbx = if (wordDoc.size >= 0x0068) bb.getInt(0x0064).coerceAtLeast(0) else 0
        val totalCcpLimit = (ccpText.toLong() + ccpFtn + ccpHdd + ccpAtn + ccpEdn + ccpTxbx)
            .coerceAtMost(8_000_000L).toInt().let { if (it > 0) it else -1 }

        val use1Table = (flags and 0x0200) != 0
        val tableStreamName = if (use1Table) "1Table" else "0Table"
        val tableStream = cfb.readStream(tableStreamName) ?: cfb.readStream(if (use1Table) "0Table" else "1Table")

        if (tableStream != null) {
            if (wordDoc.size >= 0x01AA) {
                val fcClx = bb.getInt(0x01A2)
                val lcbClx = bb.getInt(0x01A6)
                if (fcClx >= 0 && lcbClx > 0 && fcClx.toLong() + lcbClx.toLong() <= tableStream.size.toLong()) {
                    val clxText = parseClxPieceTable(wordDoc, tableStream, fcClx, lcbClx, totalCcpLimit)
                    if (clxText.isNotBlank()) return normalizeWordControlChars(clxText, embeddedImages.size)
                }
            }

            if (wordDoc.size >= 68) {
                val csw = bb.getShort(32).toInt() and 0xFFFF
                val fibRgLwStart = 34 + csw * 2
                if (fibRgLwStart + 2 < wordDoc.size) {
                    val cslw = bb.getShort(fibRgLwStart).toInt() and 0xFFFF
                    val fibRgFcLcbStart = fibRgLwStart + 2 + cslw * 4 + 2
                    val clxEntryOffset = fibRgFcLcbStart + 33 * 8
                    if (clxEntryOffset + 8 <= wordDoc.size) {
                        val dynFcClx = bb.getInt(clxEntryOffset)
                        val dynLcbClx = bb.getInt(clxEntryOffset + 4)
                        if (dynFcClx >= 0 && dynLcbClx > 0 && dynFcClx.toLong() + dynLcbClx.toLong() <= tableStream.size.toLong()) {
                            val clxText = parseClxPieceTable(wordDoc, tableStream, dynFcClx, dynLcbClx, totalCcpLimit)
                            if (clxText.isNotBlank()) return normalizeWordControlChars(clxText, embeddedImages.size)
                        }
                    }
                }
            }
        }

        val fcMin = bb.getInt(24).coerceIn(0, wordDoc.size)
        val fcMac = bb.getInt(28).coerceIn(fcMin, wordDoc.size)
        val isExtChar = (flags and 0x0004) != 0
        if (fcMac > fcMin) {
            val sliceLen = fcMac - fcMin
            val raw = if (isExtChar && sliceLen >= 2) {
                String(wordDoc, fcMin, sliceLen - (sliceLen % 2), StandardCharsets.UTF_16LE)
            } else {
                String(wordDoc, fcMin, sliceLen, PlainTextSession.CP1251)
            }
            val cleaned = normalizeWordControlChars(raw, embeddedImages.size)
            if (cleaned.isNotBlank()) return cleaned
        }

        return extractPrintableRunsFromBinary(wordDoc)
    }

    private fun parseClxPieceTable(
        wordDoc: ByteArray,
        tableStream: ByteArray,
        fcClx: Int,
        lcbClx: Int,
        ccpTextLimit: Int
    ): String {
        val tb = ByteBuffer.wrap(tableStream).order(ByteOrder.LITTLE_ENDIAN)
        var pos = fcClx
        val end = (fcClx.toLong() + lcbClx.toLong()).coerceAtMost(tableStream.size.toLong()).toInt()
        while (pos < end) {
            val clxt = tableStream[pos].toInt() and 0xFF
            pos++
            if (clxt == 1) {
                if (pos + 2 > end) return ""
                val cbGrpprl = tb.getShort(pos).toInt() and 0xFFFF
                pos += 2 + cbGrpprl
            } else if (clxt == 2) {
                if (pos + 4 > end) return ""
                val lcbPlcfPcd = tb.getInt(pos)
                pos += 4
                if (lcbPlcfPcd <= 4 || pos.toLong() + lcbPlcfPcd.toLong() > end.toLong()) return ""
                val pieceCount = (lcbPlcfPcd - 4) / 12
                if (pieceCount <= 0 || pieceCount > 65536) return ""

                val cpBase = pos
                val pcdBase = pos + (pieceCount + 1) * 4
                val out = StringBuilder()

                for (i in 0 until pieceCount) {
                    if (out.length >= 8_000_000) break
                    val cpStart = tb.getInt(cpBase + i * 4)
                    val cpEnd = tb.getInt(cpBase + (i + 1) * 4)
                    if (ccpTextLimit > 0 && cpStart >= ccpTextLimit) break
                    val effectiveCpEnd = if (ccpTextLimit > 0) minOf(cpEnd, ccpTextLimit) else cpEnd
                    val charCount = effectiveCpEnd - cpStart
                    if (charCount <= 0 || charCount > 4_000_000) continue

                    val fcCompressed = tb.getInt(pcdBase + i * 8 + 2)
                    val isAnsiCompressed = (fcCompressed and 0x40000000) != 0
                    val fc = fcCompressed and 0x3FFFFFFF

                    if (isAnsiCompressed) {
                        val byteOffset = fc / 2
                        if (byteOffset in 0 until wordDoc.size) {
                            val safeByteLen = minOf(charCount, wordDoc.size - byteOffset)
                            if (safeByteLen > 0) {
                                out.append(String(wordDoc, byteOffset, safeByteLen, PlainTextSession.CP1251))
                            }
                        }
                    } else {
                        val byteOffset = fc
                        if (byteOffset in 0 until wordDoc.size) {
                            val safeByteLen = minOf(charCount * 2, wordDoc.size - byteOffset) and -2
                            if (safeByteLen > 0) {
                                out.append(String(wordDoc, byteOffset, safeByteLen, StandardCharsets.UTF_16LE))
                            }
                        }
                    }
                }
                return out.toString()
            } else {
                break
            }
        }
        return ""
    }

    companion object {
        fun normalizeWordControlChars(raw: String, availableImages: Int = 0): String {
            val sb = StringBuilder(raw.length)
            var fieldDepth = 0
            var fieldInstructionChars = 0
            var inFieldInstruction = false
            var nextInlineImgIdx = 0
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                when (ch.code) {
                    0x13 -> {
                        fieldDepth++
                        inFieldInstruction = true
                        fieldInstructionChars = 0
                    }
                    0x14 -> {
                        inFieldInstruction = false
                    }
                    0x15 -> {
                        fieldDepth = (fieldDepth - 1).coerceAtLeast(0)
                        inFieldInstruction = false
                    }
                    0x01, 0x08 -> {
                        if (!inFieldInstruction && nextInlineImgIdx < availableImages) {
                            sb.append("\n[IMAGE:${nextInlineImgIdx++}]\n")
                        }
                    }
                    0x07 -> {
                        if (!inFieldInstruction) {
                            if (i + 1 < raw.length && raw[i + 1].code == 0x07) {
                                sb.append('\n')
                                i++
                            } else if (i + 2 < raw.length && raw[i + 1].code in setOf(0x0D, 0x0A) && raw[i + 2].code == 0x07) {
                                sb.append('\n')
                                i += 2
                            } else {
                                sb.append('\t')
                            }
                        }
                    }
                    0x0B, 0x0C, 0x0D, 0x0A -> {
                        inFieldInstruction = false
                        sb.append('\n')
                    }
                    else -> {
                        if (inFieldInstruction) {
                            fieldInstructionChars++
                            if (fieldInstructionChars > 512) {
                                inFieldInstruction = false
                            }
                        } else if (ch == '\t' || ch.code >= 32) {
                            sb.append(ch)
                        }
                    }
                }
                i++
            }
            return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }

        fun extractPrintableRunsFromBinary(data: ByteArray): String {
            val sb = StringBuilder()
            val run = StringBuilder()
            for (b in data) {
                val v = b.toInt() and 0xFF
                if (v == 10 || v == 13 || v in 32..126) {
                    run.append(v.toChar())
                } else {
                    if (run.length >= 6) {
                        sb.append(run).append('\n')
                    }
                    run.clear()
                }
            }
            if (run.length >= 6) sb.append(run)
            return sb.toString().trim()
        }

        fun paginateText(full: String, targetPages: MutableList<String>) {
            EbookTextSession.paginateRichText(full, 0, targetPages)
        }
    }

    override val pageCount: Int get() = paged?.pageCount ?: pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float = paged?.pageWidth(0) ?: 0f
    override fun getDocumentHeight(): Float = paged?.pageHeight(0) ?: 0f

    override fun getPageAspectRatio(pageIndex: Int): Float {
        val p = paged ?: return super.getPageAspectRatio(pageIndex)
        return p.aspectRatio((pageIndex and PAGE_INDEX_MASK).coerceIn(0, (p.pageCount - 1).coerceAtLeast(0)))
    }

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        paged?.let { p -> return p.pageText((pageIndex and PAGE_INDEX_MASK).coerceIn(0, (p.pageCount - 1).coerceAtLeast(0))) }
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        paged?.let { p ->
            try {
                p.render((pageIndex and PAGE_INDEX_MASK).coerceIn(0, (p.pageCount - 1).coerceAtLeast(0)), tileX, tileY, zoomPermille, bitmap)
            } catch (e: OutOfMemoryError) {
                p.release()
            }
            return
        }
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        if (lastErrorMsg != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
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
        paged?.let { p -> return p.search(query, startPage).toLong() }
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) if (pages[i].contains(query, true)) return i.toLong()
        for (i in 0 until start) if (pages[i].contains(query, true)) return i.toLong()
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        paged?.release()
        paged = null
        embeddedImages.forEach { if (!it.isRecycled) it.recycle() }
        embeddedImages.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}
