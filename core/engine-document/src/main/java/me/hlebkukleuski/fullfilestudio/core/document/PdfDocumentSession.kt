/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class PdfDocumentSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    private val rendererLock = Any()
    private val textExtractLock = Any()
    private var pdfRenderer: PdfRenderer? = null
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var stateVer = 1L
    @Volatile private var activePageWidth = 1080f
    @Volatile private var activePageHeight = 1528f
    private val matrix = Matrix()
    private val pageTextCache = ConcurrentHashMap<Int, String>()

    private data class PdfStreamSlice(val offset: Long, val length: Int)

    private val readableSource = ZeroCacheReadableSource(pfd.fileDescriptor)
    override val isCopyRestricted: Boolean = PdfPermissionParser.isCopyRestricted(readableSource)
    private var indexedContentSlices: List<PdfStreamSlice>? = null
    private val cmapTable = HashMap<Int, String>()
    @Volatile private var isStreamsIndexed = false

    init {
        if (PdfPermissionParser.isDrmProtected(readableSource)) {
            pdfRenderer = null
            lastErrorMsg = PdfPermissionParser.PDF_DRM_ERROR
            stateVer++
        } else try {
            try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            val renderer = if (!password.isNullOrEmpty() && Build.VERSION.SDK_INT >= 35) {
                val params = android.graphics.pdf.LoadParams.Builder().setPassword(password).build()
                PdfRenderer(pfd, params)
            } else {
                PdfRenderer(pfd)
            }
            pdfRenderer = renderer
            if (renderer.pageCount > 0) {
                try {
                    renderer.openPage(0).use { firstPage ->
                        activePageWidth = firstPage.width.toFloat().coerceAtLeast(1f)
                        activePageHeight = firstPage.height.toFloat().coerceAtLeast(1f)
                    }
                } catch (_: Throwable) {}
            }
        } catch (secEx: SecurityException) {
            pdfRenderer = null
            lastErrorMsg = if (Build.VERSION.SDK_INT >= 35) {
                "PASSWORD_REQUIRED: PDF document is password protected."
            } else {
                "UNSUPPORTED_ENCRYPTION: Password-protected PDF decryption requires Android 15 (API 35) or newer."
            }
            stateVer++
        } catch (e: Exception) {
            pdfRenderer = null
            val isEncryptedHeader = checkPdfEncryptionMarker()
            lastErrorMsg = if (isEncryptedHeader) {
                if (Build.VERSION.SDK_INT >= 35) {
                    "PASSWORD_REQUIRED: PDF document is encrypted or password protected."
                } else {
                    "UNSUPPORTED_ENCRYPTION: Password-protected PDF decryption requires Android 15 (API 35) or newer."
                }
            } else {
                "CORRUPTED_PDF: PDF file is corrupted or has an unsupported structure (${e.localizedMessage ?: "I/O Error"})."
            }
            stateVer++
        }
    }

    private fun checkPdfEncryptionMarker(): Boolean {
        return try {
            val probeLen = minOf(readableSource.size, 64 * 1024L).toInt()
            if (probeLen <= 0) return false
            val buf = ByteBuffer.allocateDirect(probeLen)
            val r = readableSource.readAt(maxOf(0L, readableSource.size - probeLen), buf)
            if (r > 0) {
                val arr = ByteArray(r)
                buf.flip()
                buf.get(arr)
                String(arr, StandardCharsets.ISO_8859_1).contains("/Encrypt")
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    override fun getLastError(): String? = lastErrorMsg
    override fun getStateVersion(): Long = stateVer
    override fun getDocumentWidth(): Float = activePageWidth
    override fun getDocumentHeight(): Float = activePageHeight

    override val pageCount: Int
        get() = synchronized(rendererLock) {
            if (isClosed || pdfRenderer == null) 1 else (pdfRenderer?.pageCount ?: 1).coerceAtLeast(1)
        }

    /**
     * Groups character or word-run `RectF` bounds from Android 15+ `PdfPageTextContent.getBounds()`
     * into distinct visual line rectangles, keeping multi-column segments separate.
     */
    private fun clusterPdfContentBoundsIntoLineRects(rawBounds: List<RectF>): List<RectF> {
        val valid = rawBounds.filter {
            it.width() > 0.5f && it.height() > 0.5f &&
                    it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
        }
        if (valid.size <= 1) return valid

        val sorted = valid.sortedWith(compareBy({ (it.centerY() / 4f).roundToInt() }, { it.left }))
        val clustered = ArrayList<RectF>(sorted.size)
        for (b in sorted) {
            val prev = clustered.lastOrNull()
            if (prev != null) {
                val vOverlap = minOf(prev.bottom, b.bottom) - maxOf(prev.top, b.top)
                val minH = minOf(prev.height(), b.height()).coerceAtLeast(1f)
                val hGap = b.left - prev.right
                val maxMergeGap = maxOf(prev.height(), b.height()) * 1.85f
                if (vOverlap >= minH * 0.42f && hGap >= -minH && hGap <= maxMergeGap) {
                    clustered[clustered.lastIndex] = RectF(
                        minOf(prev.left, b.left),
                        minOf(prev.top, b.top),
                        maxOf(prev.right, b.right),
                        maxOf(prev.bottom, b.bottom)
                    )
                    continue
                }
            }
            clustered.add(RectF(b))
        }
        return clustered
    }

    /**
     * Splits a single paragraph string (when Android 15 PDFium returns `getText()` without `\n`
     * while `getBounds()` has one `RectF` per visual line) across [lineRects] proportionally to each line's width.
     */
    private fun distributeParagraphAcrossLineRects(paragraph: String, lineRects: List<RectF>): List<Pair<RectF, String>> {
        if (lineRects.isEmpty()) return emptyList()
        if (lineRects.size == 1) return listOf(lineRects[0] to paragraph)

        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        if (words.size < lineRects.size) {
            val totalChars = paragraph.length
            val out = ArrayList<Pair<RectF, String>>(lineRects.size)
            val totalW = lineRects.sumOf { it.width().toDouble() }.toFloat().coerceAtLeast(1f)
            var curPos = 0
            for (i in lineRects.indices) {
                if (curPos >= totalChars) break
                val endPos = if (i == lineRects.lastIndex) {
                    totalChars
                } else {
                    val share = ((lineRects[i].width() / totalW) * totalChars).roundToInt().coerceAtLeast(1)
                    (curPos + share).coerceIn(curPos + 1, totalChars)
                }
                val sub = paragraph.substring(curPos, endPos).trim()
                if (sub.isNotEmpty()) out.add(lineRects[i] to sub)
                curPos = endPos
            }
            return out
        }

        val totalWidth = lineRects.sumOf { it.width().toDouble() }.toFloat().coerceAtLeast(1f)
        val totalChars = words.sumOf { it.length + 1 }.coerceAtLeast(1)
        val out = ArrayList<Pair<RectF, String>>(lineRects.size)
        var wordIdx = 0

        for (rIdx in lineRects.indices) {
            if (wordIdx >= words.size) break
            val rect = lineRects[rIdx]
            if (rIdx == lineRects.lastIndex) {
                val rem = words.subList(wordIdx, words.size).joinToString(" ")
                if (rem.isNotEmpty()) out.add(rect to rem)
                break
            }
            val targetCharsForRect = ((rect.width() / totalWidth) * totalChars).roundToInt().coerceAtLeast(1)
            val sb = StringBuilder()
            val remainingRects = lineRects.size - rIdx - 1
            while (wordIdx < words.size - remainingRects) {
                val w = words[wordIdx]
                val nextLen = if (sb.isEmpty()) w.length else sb.length + 1 + w.length
                if (sb.isNotEmpty() && nextLen > targetCharsForRect && abs(nextLen - targetCharsForRect) > abs(sb.length - targetCharsForRect)) {
                    break
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(w)
                wordIdx++
            }
            if (sb.isEmpty() && wordIdx < words.size) {
                sb.append(words[wordIdx++])
            }
            val lineStr = sb.toString().trim()
            if (lineStr.isNotEmpty()) {
                out.add(rect to lineStr)
            }
        }
        return out
    }

    /**
     * Extracts text and normalized line-level bounding boxes directly from Android 15+ (API 35+)
     * `PdfRenderer.Page.getTextContents()` via the system PDFium engine.
     */
    private fun extractNativePdfPageTextAndroid15(cleanIdx: Int): String? {
        if (Build.VERSION.SDK_INT < 35) return null
        synchronized(rendererLock) {
            if (isClosed) return null
            val renderer = pdfRenderer ?: return null
            if (cleanIdx !in 0 until renderer.pageCount) return null
            return try {
                renderer.openPage(cleanIdx).use { page ->
                    val pageW = page.width.toFloat().coerceAtLeast(1f)
                    val pageH = page.height.toFloat().coerceAtLeast(1f)
                    activePageWidth = pageW
                    activePageHeight = pageH

                    val getTextContentsMethod = page.javaClass.getMethod("getTextContents")
                    val contentsList = getTextContentsMethod.invoke(page) as? List<*> ?: return@use null
                    if (contentsList.isEmpty()) return@use null

                    val sb = StringBuilder()
                    fun appendNormalizedBoxLine(rect: RectF, text: String) {
                        val cleanText = text.trim()
                        if (cleanText.isEmpty()) return
                        val lNorm = (rect.left / pageW).coerceIn(0f, 0.99f)
                        val tNorm = (rect.top / pageH).coerceIn(0f, 0.99f)
                        val rNorm = (rect.right / pageW).coerceIn(lNorm + 0.005f, 1f)
                        val bNorm = (rect.bottom / pageH).coerceIn(tNorm + 0.005f, 1f)
                        sb.append(
                            String.format(
                                Locale.US,
                                "[PDF_BOX:%.4f,%.4f,%.4f,%.4f]%s\n",
                                lNorm, tNorm, rNorm, bNorm, cleanText
                            )
                        )
                    }

                    for (item in contentsList) {
                        if (item == null) continue
                        val itemClass = item.javaClass
                        val rawText = (itemClass.getMethod("getText").invoke(item) as? String)?.trim() ?: continue
                        if (rawText.isEmpty()) continue
                        @Suppress("UNCHECKED_CAST")
                        val rawBoundsList = try {
                            itemClass.getMethod("getBounds").invoke(item) as? List<RectF>
                        } catch (_: Throwable) {
                            null
                        }

                        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val lineRects = if (!rawBoundsList.isNullOrEmpty()) {
                            clusterPdfContentBoundsIntoLineRects(rawBoundsList)
                        } else emptyList()

                        val mergedVisualRows = ArrayList<RectF>()
                        for (lr in lineRects) {
                            val prev = mergedVisualRows.lastOrNull()
                            if (prev != null) {
                                val vOverlap = minOf(prev.bottom, lr.bottom) - maxOf(prev.top, lr.top)
                                val minH = minOf(prev.height(), lr.height()).coerceAtLeast(1f)
                                if (vOverlap >= minH * 0.45f) {
                                    mergedVisualRows[mergedVisualRows.lastIndex] = RectF(
                                        minOf(prev.left, lr.left),
                                        minOf(prev.top, lr.top),
                                        maxOf(prev.right, lr.right),
                                        maxOf(prev.bottom, lr.bottom)
                                    )
                                    continue
                                }
                            }
                            mergedVisualRows.add(RectF(lr))
                        }

                        when {
                            !rawBoundsList.isNullOrEmpty() && rawBoundsList.size == lines.size -> {
                                for (i in lines.indices) {
                                    appendNormalizedBoxLine(rawBoundsList[i], lines[i])
                                }
                            }
                            lineRects.size == lines.size && lines.isNotEmpty() -> {
                                for (i in lines.indices) {
                                    appendNormalizedBoxLine(lineRects[i], lines[i])
                                }
                            }
                            mergedVisualRows.size == lines.size && lines.isNotEmpty() -> {
                                for (i in lines.indices) {
                                    appendNormalizedBoxLine(mergedVisualRows[i], lines[i])
                                }
                            }
                            lines.size == 1 && lineRects.size > 1 -> {
                                val pairs = distributeParagraphAcrossLineRects(lines[0], lineRects)
                                for ((r, t) in pairs) {
                                    appendNormalizedBoxLine(r, t)
                                }
                            }
                            lineRects.isNotEmpty() && lines.isNotEmpty() -> {
                                var minL = Float.MAX_VALUE
                                var minT = Float.MAX_VALUE
                                var maxR = -Float.MAX_VALUE
                                var maxB = -Float.MAX_VALUE
                                for (b in lineRects) {
                                    if (b.left < minL) minL = b.left
                                    if (b.top < minT) minT = b.top
                                    if (b.right > maxR) maxR = b.right
                                    if (b.bottom > maxB) maxB = b.bottom
                                }
                                if (maxR > minL && maxB > minT) {
                                    val maxCharsInBlock = lines.maxOf { it.length }.coerceAtLeast(1)
                                    val stepH = (maxB - minT) / lines.size.coerceAtLeast(1)
                                    for ((i, lineStr) in lines.withIndex()) {
                                        val lt = minT + i * stepH
                                        val lb = lt + stepH
                                        val widthFraction = (lineStr.length.toFloat() / maxCharsInBlock.toFloat()).coerceIn(0.18f, 1.0f)
                                        val lr = minL + (maxR - minL) * widthFraction
                                        appendNormalizedBoxLine(RectF(minL, lt, lr, lb), lineStr)
                                    }
                                } else {
                                    sb.append(rawText).append('\n')
                                }
                            }
                            else -> {
                                sb.append(rawText).append('\n')
                            }
                        }
                    }
                    sb.toString().trim().takeIf { it.isNotEmpty() }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun getPageText(pageIndex: Int): String {
        if (isClosed || pdfRenderer == null || lastErrorMsg != null || isCopyRestricted) return ""
        val total = pageCount
        if (pageIndex == -1) {
            val cap = minOf(total, 60)
            val sb = StringBuilder()
            for (i in 0 until cap) {
                if (isClosed) break
                val t = getPageText(i)
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                }
            }
            return sb.toString()
        }
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (total - 1).coerceAtLeast(0))
        pageTextCache[cleanIdx]?.let { return it }

        val nativeExtracted = extractNativePdfPageTextAndroid15(cleanIdx)
        if (!nativeExtracted.isNullOrBlank()) {
            if (pageTextCache.size > 256) pageTextCache.clear()
            pageTextCache[cleanIdx] = nativeExtracted
            return nativeExtracted
        }

        synchronized(textExtractLock) {
            if (isClosed) return ""
            pageTextCache[cleanIdx]?.let { return it }
            ensurePdfStreamsIndexedLocked()

            val slices = indexedContentSlices ?: return ""
            if (slices.isEmpty()) {
                pageTextCache[cleanIdx] = ""
                return ""
            }

            val targetSliceIdx = if (cleanIdx < slices.size) {
                cleanIdx
            } else {
                (cleanIdx.toLong() * slices.size / total.coerceAtLeast(1)).toInt().coerceIn(0, slices.lastIndex)
            }
            val slice = slices[targetSliceIdx]
            val safeLen = slice.length.coerceIn(1, 2 * 1024 * 1024)
            val rawBuf = ByteBuffer.allocateDirect(safeLen)
            val got = readableSource.readAt(slice.offset, rawBuf)
            if (got <= 0) return ""
            rawBuf.flip()
            val rawChunk = ByteArray(got)
            rawBuf.get(rawChunk)
            val decompressed = tryDecompressFlate(rawChunk) ?: rawChunk

            val sb = StringBuilder()
            extractTextFromPdfContentStream(decompressed, cmapTable, sb)
            val extracted = sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
            if (pageTextCache.size > 256) {
                pageTextCache.clear()
            }
            pageTextCache[cleanIdx] = extracted
            return extracted
        }
    }

    /**
     * Zero-spike sliding-window indexer (`256 KB` window) that locates `stream ... endstream`
     * byte offsets across the PDF file without allocating a 24 MB byte array in heap memory.
     */
    private fun ensurePdfStreamsIndexedLocked() {
        if (isStreamsIndexed) return
        isStreamsIndexed = true
        try {
            val totalFileSize = minOf(readableSource.size, 128L * 1024L * 1024L)
            if (totalFileSize <= 32L) return

            val windowSize = 256 * 1024
            val overlap = 1024
            val directBuf = ByteBuffer.allocateDirect(windowSize)
            val windowArr = ByteArray(windowSize)

            val streamMarker = "stream".toByteArray(StandardCharsets.ISO_8859_1)
            val endStreamMarker = "endstream".toByteArray(StandardCharsets.ISO_8859_1)
            val contentSlices = ArrayList<PdfStreamSlice>()
            var cmapsParsed = 0

            var fileOffset = 0L
            var pendingStreamStart = -1L
            var pendingIsImageOrFont = false
            var pendingIsLikelyCMap = false

            while (fileOffset < totalFileSize && !isClosed && contentSlices.size < 20_000) {
                val toRead = minOf(windowSize.toLong(), totalFileSize - fileOffset).toInt()
                if (toRead < 16) break
                directBuf.clear()
                directBuf.limit(toRead)
                val read = readableSource.readAt(fileOffset, directBuf)
                if (read < 16) break
                directBuf.flip()
                directBuf.get(windowArr, 0, read)

                var searchPos = 0
                while (searchPos < read - 10 && !isClosed) {
                    if (pendingStreamStart < 0L) {
                        val startIdx = indexOfBytes(windowArr, read, streamMarker, searchPos)
                        if (startIdx < 0) break

                        val dictStart = maxOf(0, startIdx - 320)
                        val dictHeader = String(windowArr, dictStart, startIdx - dictStart, StandardCharsets.ISO_8859_1)
                        pendingIsImageOrFont = dictHeader.contains("/Subtype/Image") ||
                                dictHeader.contains("/Subtype /Image") ||
                                dictHeader.contains("/FontFile") ||
                                dictHeader.contains("/Metadata") ||
                                dictHeader.contains("/XRef")
                        pendingIsLikelyCMap = cmapsParsed < 24 &&
                                (dictHeader.contains("/CMap") || dictHeader.contains("ToUnicode"))

                        var contentStartInWin = startIdx + streamMarker.size
                        if (contentStartInWin < read && windowArr[contentStartInWin] == '\r'.code.toByte()) contentStartInWin++
                        if (contentStartInWin < read && windowArr[contentStartInWin] == '\n'.code.toByte()) contentStartInWin++

                        pendingStreamStart = fileOffset + contentStartInWin
                        searchPos = contentStartInWin
                    } else {
                        val endIdx = indexOfBytes(windowArr, read, endStreamMarker, searchPos)
                        if (endIdx < 0) break

                        val streamEndAbs = fileOffset + endIdx
                        val streamLength = (streamEndAbs - pendingStreamStart).toInt()
                        if (!pendingIsImageOrFont && streamLength in 8..(1024 * 1024)) {
                            if (pendingIsLikelyCMap && streamLength < 65536) {
                                val cBuf = ByteBuffer.allocateDirect(streamLength)
                                val cGot = readableSource.readAt(pendingStreamStart, cBuf)
                                if (cGot > 0) {
                                    cBuf.flip()
                                    val rawChunk = ByteArray(cGot)
                                    cBuf.get(rawChunk)
                                    val decompressed = tryDecompressFlate(rawChunk) ?: rawChunk
                                    val latin = String(decompressed, StandardCharsets.ISO_8859_1)
                                    if (latin.contains("beginbfchar") || latin.contains("beginbfrange")) {
                                        parseToUnicodeCMapStream(latin, cmapTable)
                                        cmapsParsed++
                                    } else {
                                        contentSlices.add(PdfStreamSlice(pendingStreamStart, streamLength))
                                    }
                                }
                            } else {
                                contentSlices.add(PdfStreamSlice(pendingStreamStart, streamLength))
                            }
                        }
                        pendingStreamStart = -1L
                        searchPos = endIdx + endStreamMarker.size
                    }
                }

                if (fileOffset + read >= totalFileSize) break
                fileOffset += (read - overlap).coerceAtLeast(1)
            }

            indexedContentSlices = contentSlices
        } catch (_: Throwable) {
        }
    }

    private fun parseToUnicodeCMapStream(cmapText: String, outMap: MutableMap<Int, String>) {
        val bfCharBlocks = Regex("beginbfchar([\\s\\S]*?)endbfchar").findAll(cmapText)
        val pairRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
        for (block in bfCharBlocks) {
            for (m in pairRegex.findAll(block.groupValues[1])) {
                val srcCode = m.groupValues[1].toIntOrNull(16) ?: continue
                val dstStr = decodeUtf16Hex(m.groupValues[2])
                if (dstStr.isNotEmpty()) outMap[srcCode] = dstStr
            }
        }

        val bfRangeBlocks = Regex("beginbfrange([\\s\\S]*?)endbfrange").findAll(cmapText)
        val rangeRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
        for (block in bfRangeBlocks) {
            for (m in rangeRegex.findAll(block.groupValues[1])) {
                val startCode = m.groupValues[1].toIntOrNull(16) ?: continue
                val endCode = m.groupValues[2].toIntOrNull(16) ?: continue
                val targetStartStr = decodeUtf16Hex(m.groupValues[3])
                if (targetStartStr.isEmpty()) continue
                val targetStartCp = targetStartStr.codePointAt(0)
                val span = (endCode - startCode).coerceIn(0, 2048)
                for (offset in 0..span) {
                    val cp = targetStartCp + offset
                    if (Character.isValidCodePoint(cp)) {
                        outMap[startCode + offset] = String(Character.toChars(cp))
                    }
                }
            }
        }
    }

    private fun decodeUtf16Hex(hex: String): String {
        val clean = hex.trim()
        if (clean.length < 2) return ""
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return if (bytes.size >= 2 && bytes.size % 2 == 0) {
            String(bytes, StandardCharsets.UTF_16BE).replace("\u0000", "")
        } else {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    private fun extractTextFromPdfContentStream(
        streamBytes: ByteArray,
        cmapTable: Map<Int, String>,
        out: StringBuilder
    ) {
        val str = String(streamBytes, StandardCharsets.ISO_8859_1)
        val btBlocks = Regex("BT([\\s\\S]*?)ET").findAll(str)
        val tokenRegex = Regex(
            "(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+T[dD]|" +
                    "(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+Tm|" +
                    "\\((?:\\\\.|[^\\\\)])*\\)\\s*(?:Tj|'|\\\")|" +
                    "\\[(?:[^]]*)\\]\\s*TJ|" +
                    "<([0-9A-Fa-f]+)>\\s*Tj|" +
                    "T\\*"
        )

        for (bt in btBlocks) {
            val block = bt.groupValues[1]
            var lastTmY: Float? = null
            for (m in tokenRegex.findAll(block)) {
                val tok = m.value
                when {
                    tok == "T*" -> {
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                    }
                    tok.endsWith("Td") || tok.endsWith("TD") -> {
                        val dx = m.groupValues[1].toFloatOrNull() ?: 0f
                        val dy = m.groupValues[2].toFloatOrNull() ?: 0f
                        if (abs(dy) > 1.5f) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        } else if (dx > 1.5f && out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                            out.append(' ')
                        }
                    }
                    tok.endsWith("Tm") -> {
                        val newY = m.groupValues[8].toFloatOrNull()
                        if (newY != null) {
                            val prevY = lastTmY
                            if (prevY != null && abs(newY - prevY) > 2.5f) {
                                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            } else if (prevY != null && out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                                out.append(' ')
                            }
                            lastTmY = newY
                        }
                    }
                    tok.endsWith("'") || tok.endsWith("\"") -> {
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        val inner = tok.substringAfter("(").substringBeforeLast(")")
                        out.append(unescapePdfString(inner, cmapTable))
                    }
                    tok.endsWith("Tj") && tok.startsWith("(") -> {
                        val inner = tok.substringAfter("(").substringBeforeLast(")")
                        out.append(unescapePdfString(inner, cmapTable))
                    }
                    tok.endsWith("Tj") && tok.startsWith("<") -> {
                        val hex = m.groupValues[9]
                        out.append(decodePdfHexString(hex, cmapTable))
                    }
                    tok.endsWith("TJ") -> {
                        val inner = tok.substringAfter("[").substringBeforeLast("]")
                        val subMatcher = Regex("\\((?:\\\\.|[^\\\\)])*\\)|<([0-9A-Fa-f]+)>|(-?\\d+(?:\\.\\d+)?)").findAll(inner)
                        for (sub in subMatcher) {
                            val s = sub.value
                            when {
                                s.startsWith("(") -> out.append(unescapePdfString(s.substring(1, s.length - 1), cmapTable))
                                s.startsWith("<") -> out.append(decodePdfHexString(s.substring(1, s.length - 1), cmapTable))
                                else -> {
                                    val kern = s.toFloatOrNull() ?: 0f
                                    if (kern < -100f && out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                                        out.append(' ')
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        }
    }

    private fun unescapePdfString(raw: String, cmapTable: Map<Int, String>): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '(', ')', '\\' -> { sb.append(next); i += 2 }
                    '\r' -> {
                        i += if (i + 2 < raw.length && raw[i + 2] == '\n') 3 else 2
                    }
                    '\n' -> { i += 2 }
                    in '0'..'7' -> {
                        var oct = "" + next
                        var j = i + 2
                        while (j < raw.length && oct.length < 3 && raw[j] in '0'..'7') {
                            oct += raw[j]
                            j++
                        }
                        val code = oct.toInt(8)
                        sb.append(cmapTable[code] ?: code.toChar().toString())
                        i = j
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                val code = ch.code and 0xFF
                sb.append(cmapTable[code] ?: ch.toString())
                i++
            }
        }
        return sb.toString()
    }

    private fun decodePdfHexString(hex: String, cmapTable: Map<Int, String>): String {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.length < 2) return ""
        if (cmapTable.isNotEmpty()) {
            if (clean.length % 4 == 0) {
                val sb4 = StringBuilder()
                var matched4 = 0
                for (i in 0 until clean.length step 4) {
                    val code = clean.substring(i, i + 4).toIntOrNull(16) ?: continue
                    val mapped = cmapTable[code]
                    if (mapped != null) {
                        sb4.append(mapped)
                        matched4++
                    }
                }
                if (matched4 > 0 && matched4 >= (clean.length / 4) / 2) {
                    return sb4.toString()
                }
            }
            val sb2 = StringBuilder()
            var matched2 = false
            for (i in 0 until clean.length step 2) {
                if (i + 2 > clean.length) break
                val code = clean.substring(i, i + 2).toIntOrNull(16) ?: continue
                val mapped = cmapTable[code]
                if (mapped != null) {
                    sb2.append(mapped)
                    matched2 = true
                } else if (code in 32..126) {
                    sb2.append(code.toChar())
                }
            }
            if (matched2) return sb2.toString()
        }

        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0x20
        }
        return if (bytes.size >= 2 && bytes.size % 2 == 0 && (bytes[0].toInt() == 0 || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) {
            String(bytes, StandardCharsets.UTF_16BE).replace("\u0000", "")
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun tryDecompressFlate(data: ByteArray): ByteArray? {
        val outputStream = ByteArrayOutputStream(minOf(data.size * 3, 2 * 1024 * 1024))
        val buffer = ByteArray(8192)
        for (nowrap in arrayOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(data)
                var total = 0
                while (!inflater.finished() && !inflater.needsDictionary() && total < 4 * 1024 * 1024) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) break
                    outputStream.write(buffer, 0, count)
                    total += count
                }
                val result = outputStream.toByteArray()
                if (result.isNotEmpty()) return result
            } catch (_: Throwable) {
            } finally {
                inflater.end()
                outputStream.reset()
            }
        }
        return null
    }

    private fun indexOfBytes(data: ByteArray, dataLimit: Int, target: ByteArray, startFrom: Int): Int {
        if (target.isEmpty() || startFrom >= dataLimit) return -1
        val first = target[0]
        val limit = dataLimit - target.size
        var i = startFrom
        while (i <= limit) {
            if (data[i] == first) {
                var found = true
                for (j in 1 until target.size) {
                    if (data[i + j] != target[j]) {
                        found = false
                        break
                    }
                }
                if (found) return i
            }
            i++
        }
        return -1
    }

    override fun getPageAspectRatio(pageIndex: Int): Float {
        synchronized(rendererLock) {
            val renderer = pdfRenderer ?: return 0.707f
            if (isClosed || renderer.pageCount <= 0) return 0.707f
            val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
            return try {
                renderer.openPage(cleanIdx).use { page ->
                    page.width.toFloat() / page.height.toFloat().coerceAtLeast(1f)
                }
            } catch (_: Exception) {
                0.707f
            }
        }
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        bitmap.eraseColor(Color.TRANSPARENT)

        synchronized(rendererLock) {
            if (isClosed) return
            val renderer = pdfRenderer ?: return
            val count = renderer.pageCount
            if (count <= 0) return
            val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, count - 1)

            try {
                renderer.openPage(cleanIdx).use { page ->
                    activePageWidth = page.width.toFloat().coerceAtLeast(1f)
                    activePageHeight = page.height.toFloat().coerceAtLeast(1f)

                    matrix.reset()
                    val zoom = (zoomPermille / 1000f).coerceAtLeast(0.5f)
                    val baseScale = minOf(bitmap.width.toFloat() / page.width, bitmap.height.toFloat() / page.height)
                    val totalScale = baseScale * zoom

                    val scaledWidth = page.width * totalScale
                    val scaledHeight = page.height * totalScale

                    val dx = (bitmap.width - scaledWidth) / 2f - tileX.toFloat()
                    val dy = (bitmap.height - scaledHeight) / 2f - tileY.toFloat()

                    val visLeft = maxOf(0f, dx).toInt()
                    val visTop = maxOf(0f, dy).toInt()
                    val visRight = minOf(bitmap.width.toFloat(), dx + scaledWidth).roundToInt()
                    val visBottom = minOf(bitmap.height.toFloat(), dy + scaledHeight).roundToInt()

                    if (visRight > visLeft && visBottom > visTop) {
                        val canvas = Canvas(bitmap)
                        val pageCardPaint = Paint().apply {
                            color = Color.WHITE
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(
                            visLeft.toFloat(),
                            visTop.toFloat(),
                            visRight.toFloat(),
                            visBottom.toFloat(),
                            pageCardPaint
                        )

                        matrix.postScale(totalScale, totalScale)
                        matrix.postTranslate(dx, dy)
                        val clipRect = Rect(visLeft, visTop, visRight, visBottom)
                        page.render(bitmap, clipRect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) {
                lastErrorMsg = "PDF page render error ${cleanIdx + 1}: ${e.localizedMessage}"
                stateVer++
            }
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed || pdfRenderer == null) return -1L
        val total = pageCount
        val start = startPage.coerceIn(0, (total - 1).coerceAtLeast(0))
        for (i in start until total) {
            if (isClosed) return -1L
            if (getPageText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until start) {
            if (isClosed) return -1L
            if (getPageText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        synchronized(rendererLock) {
            try { pdfRenderer?.close() } catch (_: Exception) {}
            pdfRenderer = null
        }
        synchronized(textExtractLock) {
            indexedContentSlices = null
            pageTextCache.clear()
        }
        readableSource.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}
