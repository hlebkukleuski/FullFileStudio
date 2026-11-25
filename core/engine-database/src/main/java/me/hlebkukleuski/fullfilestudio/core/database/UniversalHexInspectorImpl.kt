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

package me.hlebkukleuski.fullfilestudio.core.database

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.engine.modules.HexSession
import me.hlebkukleuski.fullfilestudio.core.engine.modules.UniversalHexInspector
import me.hlebkukleuski.fullfilestudio.core.security.SecurityArena
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30

/**
 * Engineering-grade Zero-GC HEX & Binary Inspector.
 * Duplicates the incoming [FileDescriptor] via [ParcelFileDescriptor.dup] so that IPC binder
 * cleanup in `IsolatedRenderService.openSession` or `MainActivity` never invalidates the underlying
 * Linux file descriptor while the session is active.
 */
class UniversalHexInspectorImpl : UniversalHexInspector {

    override fun canHandle(signature: ByteArray, extension: String): Boolean = true

    override fun createSession(fd: FileDescriptor): DocumentSession = inspect(fd)

    override fun inspect(fd: FileDescriptor): HexSession {
        val dupPfd = try {
            ParcelFileDescriptor.dup(fd)
        } catch (_: Throwable) {
            null
        }
        return UniversalHexSessionImpl(dupPfd, fd)
    }
}

private class UniversalHexSessionImpl(
    private val pfd: ParcelFileDescriptor?,
    fallbackFd: FileDescriptor
) : HexSession {

    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null

    private val actualFd = pfd?.fileDescriptor ?: fallbackFd
    private val rawFd: Int = try { pfd?.fd ?: -1 } catch (_: Throwable) { -1 }
    private val source = ZeroCacheReadableSource(actualFd, rawFd)
    private val fileSize: Long = source.size.coerceAtLeast(0L)

    @Volatile private var unzoomedDocWidth: Float = 1080f
    @Volatile private var unzoomedDocHeight: Float = 1132f

    private val pageBuffer = ByteBuffer.allocateDirect(BYTES_PER_PAGE)
    private val pageByteArray = ByteArray(BYTES_PER_PAGE)

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val rowStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 23f
    }
    private val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 23f
    }

    init {
        updateUnzoomedMetrics(1080, ROWS_PER_PAGE)
    }

    companion object {
        private const val BYTES_PER_ROW = 16
        private const val ROWS_PER_PAGE = 64
        private const val BYTES_PER_PAGE = BYTES_PER_ROW * ROWS_PER_PAGE // 1024 bytes per page (full screen height)
        private const val HEX_DIGITS = "0123456789ABCDEF"

        private val DARK_BG_COLOR = Color.rgb(0x10, 0x11, 0x16)
        private val DARK_CELL_BG = Color.rgb(0x13, 0x15, 0x1D)
        private val DARK_HEADER_BG = Color.rgb(0x18, 0x1A, 0x24)
        private val DARK_STRIPE_BG = Color.rgb(0x17, 0x19, 0x23)
        private val DARK_OFFSET_COLOR = Color.rgb(0x89, 0xB4, 0xFA)
        private val DARK_HEX_COLOR = Color.rgb(0xE2, 0xE8, 0xF0)
        private val DARK_ASCII_COLOR = Color.rgb(0x03, 0xDA, 0xC6)
        private val DARK_HEADER_TEXT = Color.rgb(0xFA, 0xB3, 0x87)
        private val DARK_BORDER_COLOR = Color.rgb(0x31, 0x32, 0x44)

        private val LIGHT_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)
        private val LIGHT_CELL_BG = Color.rgb(0xFF, 0xFF, 0xFF)
        private val LIGHT_HEADER_BG = Color.rgb(0xE8, 0xEE, 0xF5)
        private val LIGHT_STRIPE_BG = Color.rgb(0xF1, 0xF5, 0xF9)
        private val LIGHT_OFFSET_COLOR = Color.rgb(0x19, 0x76, 0xD2)
        private val LIGHT_HEX_COLOR = Color.rgb(0x1E, 0x29, 0x3B)
        private val LIGHT_ASCII_COLOR = Color.rgb(0x00, 0x79, 0x6B)
        private val LIGHT_HEADER_TEXT = Color.rgb(0xD8, 0x43, 0x15)
        private val LIGHT_BORDER_COLOR = Color.rgb(0xCB, 0xD5, 0xE1)
    }

    private fun computeFitScale(viewportWidth: Int): Float {
        val charSampleWidth = 14.2f
        val tableVirtualWidth = 80f * charSampleWidth + 56f
        return (viewportWidth.coerceAtLeast(320).toFloat() / tableVirtualWidth).coerceIn(0.55f, 2.2f)
    }

    private fun updateUnzoomedMetrics(viewportWidth: Int, rowCountOnPage: Int) {
        val fitScale = computeFitScale(viewportWidth)
        monoPaint.textSize = 23f * fitScale
        val unzoomedCharW = monoPaint.measureText("0")
        val unzoomedPadLeft = 24f * fitScale
        val unzoomedHeaderH = 76f * fitScale
        val unzoomedLineH = 32f * fitScale
        val safeRows = rowCountOnPage.coerceIn(1, ROWS_PER_PAGE)

        val contentW = (78f * unzoomedCharW) + unzoomedPadLeft * 2f
        val contentH = unzoomedHeaderH + safeRows * unzoomedLineH + 16f * fitScale

        unzoomedDocWidth = max(viewportWidth.toFloat(), contentW)
        unzoomedDocHeight = contentH
    }

    override val pageCount: Int
        get() = if (fileSize <= 0L) {
            1
        } else {
            ((fileSize + BYTES_PER_PAGE - 1L) / BYTES_PER_PAGE)
                .coerceIn(1L, 2_000_000L)
                .toInt()
        }

    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float = unzoomedDocWidth

    override fun getDocumentHeight(): Float = unzoomedDocHeight

    @Synchronized
    private fun readPageBytes(cleanPageIndex: Int): Int {
        if (isClosed || fileSize <= 0L) return 0
        val startOffset = cleanPageIndex.toLong() * BYTES_PER_PAGE
        if (startOffset < 0L || startOffset >= fileSize) return 0
        val maxRead = min(BYTES_PER_PAGE.toLong(), fileSize - startOffset).toInt()

        if (maxRead <= 0) return 0

        pageBuffer.clear()
        pageBuffer.limit(maxRead)
        val got = source.readAt(startOffset, pageBuffer)
        if (got <= 0) return 0
        pageBuffer.flip()
        pageBuffer.get(pageByteArray, 0, got)
        return got
    }

    private fun formatHexRow(
        rowFileOffset: Long,
        bytes: ByteArray,
        rowOffsetInBuf: Int,
        rowByteCount: Int,
        outOffsetSb: StringBuilder,
        outHexSb: StringBuilder,
        outAsciiSb: StringBuilder
    ) {
        outOffsetSb.setLength(0)
        outHexSb.setLength(0)
        outAsciiSb.setLength(0)

        val addrHex = java.lang.Long.toHexString(rowFileOffset).uppercase(Locale.ROOT)
        for (pad in addrHex.length until 8) {
            outOffsetSb.append('0')
        }
        outOffsetSb.append(addrHex)

        outAsciiSb.append('|')
        for (col in 0 until BYTES_PER_ROW) {
            if (col == 8) {
                outHexSb.append(' ')
            }
            if (col < rowByteCount) {
                val b = bytes[rowOffsetInBuf + col].toInt() and 0xFF
                outHexSb.append(HEX_DIGITS[b ushr 4])
                outHexSb.append(HEX_DIGITS[b and 0x0F])
                outHexSb.append(' ')
                val ch = if (b in 32..126) b.toChar() else '.'
                outAsciiSb.append(ch)
            } else {
                outHexSb.append("   ")
                outAsciiSb.append(' ')
            }
        }
        outAsciiSb.append('|')
    }

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (isClosed) return ""
        if (pageIndex == -1) {
            val maxPages = min(pageCount, 32)
            val sb = StringBuilder()
            for (p in 0 until maxPages) {
                if (p > 0) sb.append("\n\n")
                sb.append(getPageText(p))
            }
            return sb.toString()
        }

        val cleanPage = (pageIndex and (LIGHT_THEME_PAGE_FLAG or me.hlebkukleuski.fullfilestudio.core.engine.DocumentPageFlags.ILLUSTRATION_ONLY_PAGE_FLAG).inv())
            .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val readCount = readPageBytes(cleanPage)
        val pageStartOffset = cleanPage.toLong() * BYTES_PER_PAGE

        val sb = StringBuilder(2048)
        sb.append(String.format(Locale.US, "HEX INSPECTOR | Size: %d B | Page %d / %d\n", fileSize, cleanPage + 1, pageCount))
        sb.append("OFFSET    00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |ASCII           |\n")
        sb.append("------------------------------------------------------------------------------\n")

        if (readCount <= 0) {
            sb.append("00000000  [Empty file: 0 bytes]")
            return sb.toString()
        }

        val offSb = StringBuilder(12)
        val hexSb = StringBuilder(56)
        val ascSb = StringBuilder(20)

        var rowOffset = 0
        while (rowOffset < readCount) {
            val rowCount = min(BYTES_PER_ROW, readCount - rowOffset)
            formatHexRow(
                rowFileOffset = pageStartOffset + rowOffset,
                bytes = pageByteArray,
                rowOffsetInBuf = rowOffset,
                rowByteCount = rowCount,
                outOffsetSb = offSb,
                outHexSb = hexSb,
                outAsciiSb = ascSb
            )
            sb.append(offSb).append("  ").append(hexSb).append(' ').append(ascSb)
            rowOffset += BYTES_PER_ROW
            if (rowOffset < readCount) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPage = (pageIndex and (LIGHT_THEME_PAGE_FLAG or me.hlebkukleuski.fullfilestudio.core.engine.DocumentPageFlags.ILLUSTRATION_ONLY_PAGE_FLAG).inv())
            .coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) LIGHT_BG_COLOR else DARK_BG_COLOR)

        val readCount = readPageBytes(cleanPage)
        val actualRows = if (readCount <= 0) 1 else ((readCount + BYTES_PER_ROW - 1) / BYTES_PER_ROW).coerceIn(1, ROWS_PER_PAGE)
        updateUnzoomedMetrics(bitmap.width, actualRows)

        val pageStartOffset = cleanPage.toLong() * BYTES_PER_PAGE
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 8.0f)
        val fitScale = computeFitScale(bitmap.width)
        val effectiveScale = fitScale * zoom

        monoPaint.textSize = 23f * effectiveScale
        headerTextPaint.textSize = 23f * effectiveScale

        headerBgPaint.color = if (isLight) LIGHT_HEADER_BG else DARK_HEADER_BG
        cellBgPaint.color = if (isLight) LIGHT_CELL_BG else DARK_CELL_BG
        dividerPaint.color = if (isLight) LIGHT_BORDER_COLOR else DARK_BORDER_COLOR
        rowStripePaint.color = if (isLight) LIGHT_STRIPE_BG else DARK_STRIPE_BG

        val lineHeight = 32f * effectiveScale
        val headerHeight = 76f * effectiveScale
        val padLeft = 24f * effectiveScale
        val charW = monoPaint.measureText("0")

        val tableContentWidth = (78f * charW) + padLeft * 2f
        val totalTableW = max(bitmap.width.toFloat(), unzoomedDocWidth * zoom)
        val totalTableH = headerHeight + actualRows * lineHeight + 16f * effectiveScale

        val maxEffX = (totalTableW - bitmap.width.toFloat()).coerceAtLeast(0f)
        val maxEffY = (totalTableH - bitmap.height.toFloat()).coerceAtLeast(0f)
        val effX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effY = tileY.toFloat().coerceIn(0f, maxEffY)

        val baseCenterX = if (tableContentWidth < bitmap.width) {
            (bitmap.width - tableContentWidth) / 2f
        } else {
            0f
        }
        val drawOriginX = baseCenterX - effX
        val tableRightEdge = max(drawOriginX + tableContentWidth, bitmap.width.toFloat() - effX)
        val visibleTableRight = min(bitmap.width.toFloat(), tableRightEdge)
        val visibleTableBottom = min(bitmap.height.toFloat(), totalTableH - effY)

        if (visibleTableBottom > headerHeight && visibleTableRight > 0f) {
            canvas.drawRect(0f, headerHeight, visibleTableRight, visibleTableBottom, cellBgPaint)
        }

        val hexColStartX = padLeft + charW * 10f
        val asciiColStartX = padLeft + charW * 60f
        val hexDividerX = drawOriginX + padLeft + charW * 9.0f
        val asciiDividerX = drawOriginX + padLeft + charW * 59.0f

        // 1. Draw scrollable HEX + ASCII rows strictly clipped below the pinned header bar
        canvas.save()
        canvas.clipRect(0f, headerHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.translate(drawOriginX, -effY)

        if (readCount <= 0) {
            monoPaint.color = if (isLight) LIGHT_HEX_COLOR else DARK_HEX_COLOR
            canvas.drawText("00000000  [Empty file: 0 bytes]", padLeft, headerHeight + lineHeight * 0.78f, monoPaint)
        } else {
            val offSb = StringBuilder(12)
            val hexSb = StringBuilder(56)
            val ascSb = StringBuilder(20)

            var rowIdx = 0
            var rowOffset = 0
            while (rowOffset < readCount) {
                val rowTop = headerHeight + rowIdx * lineHeight
                val rowBottom = rowTop + lineHeight

                if (rowBottom >= effY + headerHeight && rowTop <= effY + bitmap.height) {
                    val baselineY = rowTop + lineHeight * 0.76f
                    if (rowIdx % 2 == 1) {
                        canvas.drawRect(0f, rowTop, max(tableContentWidth, bitmap.width.toFloat()), rowBottom, rowStripePaint)
                    }

                    val rowCount = min(BYTES_PER_ROW, readCount - rowOffset)
                    formatHexRow(
                        rowFileOffset = pageStartOffset + rowOffset,
                        bytes = pageByteArray,
                        rowOffsetInBuf = rowOffset,
                        rowByteCount = rowCount,
                        outOffsetSb = offSb,
                        outHexSb = hexSb,
                        outAsciiSb = ascSb
                    )

                    monoPaint.color = if (isLight) LIGHT_OFFSET_COLOR else DARK_OFFSET_COLOR
                    canvas.drawText(offSb, 0, offSb.length, padLeft, baselineY, monoPaint)

                    monoPaint.color = if (isLight) LIGHT_HEX_COLOR else DARK_HEX_COLOR
                    canvas.drawText(hexSb, 0, hexSb.length, hexColStartX, baselineY, monoPaint)

                    monoPaint.color = if (isLight) LIGHT_ASCII_COLOR else DARK_ASCII_COLOR
                    canvas.drawText(ascSb, 0, ascSb.length, asciiColStartX, baselineY, monoPaint)

                    canvas.drawLine(0f, rowBottom, max(tableContentWidth, bitmap.width.toFloat()), rowBottom, dividerPaint)
                }

                rowOffset += BYTES_PER_ROW
                rowIdx++
            }
        }
        canvas.restore()

        // Vertical column dividers
        if (visibleTableBottom > headerHeight) {
            if (hexDividerX in 0f..bitmap.width.toFloat()) {
                canvas.drawLine(hexDividerX, headerHeight, hexDividerX, visibleTableBottom, dividerPaint)
            }
            if (asciiDividerX in 0f..bitmap.width.toFloat()) {
                canvas.drawLine(asciiDividerX, headerHeight, asciiDividerX, visibleTableBottom, dividerPaint)
            }
        }

        // 2. Draw pinned top engineering status & column header bar
        canvas.save()
        canvas.clipRect(0f, 0f, bitmap.width.toFloat(), headerHeight)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), headerHeight, headerBgPaint)
        canvas.translate(drawOriginX, 0f)

        headerTextPaint.color = if (isLight) LIGHT_OFFSET_COLOR else DARK_OFFSET_COLOR
        val metaLine = String.format(
            Locale.US,
            "HEX INSPECTOR  •  %d B (0x%X)  •  Page %d / %d",
            fileSize,
            fileSize,
            cleanPage + 1,
            pageCount
        )
        canvas.drawText(metaLine, padLeft, 30f * effectiveScale, headerTextPaint)

        headerTextPaint.color = if (isLight) LIGHT_HEADER_TEXT else DARK_HEADER_TEXT
        val colHeader = "OFFSET    00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |ASCII           |"
        canvas.drawText(colHeader, padLeft, 64f * effectiveScale, headerTextPaint)
        canvas.restore()

        canvas.drawLine(0f, headerHeight, bitmap.width.toFloat(), headerHeight, dividerPaint)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun findSequenceWithNeonFast(sequence: ByteArray, startOffset: Long): Long {
        if (isClosed || sequence.isEmpty() || fileSize <= 0L) return -1L
        val safeStart = startOffset.coerceAtLeast(0L)
        if (safeStart >= fileSize) return -1L

        val neonResult = if (rawFd >= 0) {
            SecurityArena.safeFindSequenceFd(rawFd, safeStart, fileSize, sequence)
        } else {
            SecurityArena.safeFindSequence(actualFd, safeStart, fileSize, sequence)
        }
        if (neonResult >= SecurityArena.FIND_NOT_FOUND) return neonResult

        return findSequenceFallback(sequence, safeStart)
    }

    private fun findSequenceFallback(sequence: ByteArray, startOffset: Long): Long {
        if (sequence.isEmpty() || sequence.size > 4096 || startOffset >= fileSize) return -1L
        val chunkSize = 64 * 1024
        val buf = ByteBuffer.allocateDirect(chunkSize)
        val arr = ByteArray(chunkSize)
        val firstByte = sequence[0]
        val seqLen = sequence.size

        var curOffset = startOffset
        while (curOffset < fileSize && !isClosed) {
            val toRead = min(chunkSize.toLong(), fileSize - curOffset).toInt()
            if (toRead < seqLen) break
            buf.clear()
            buf.limit(toRead)
            val got = source.readAt(curOffset, buf)
            if (got < seqLen) break
            buf.flip()
            buf.get(arr, 0, got)

            val limit = got - seqLen
            for (i in 0..limit) {
                if (arr[i] == firstByte) {
                    var match = true
                    for (j in 1 until seqLen) {
                        if (arr[i + j] != sequence[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) return curOffset + i
                }
            }
            if (curOffset + got >= fileSize) break
            curOffset += (got - seqLen + 1)
        }
        return -1L
    }

    private fun parseHexQueryBytes(rawQuery: String): ByteArray? {
        val cleaned = rawQuery.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
        if (cleaned.length < 2 || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (isClosed || query.isBlank() || fileSize <= 0L) return -1L
        val startByteOffset = (startPage.coerceAtLeast(0).toLong() * BYTES_PER_PAGE).coerceAtMost(fileSize - 1L)

        parseHexQueryBytes(query)?.let { hexPattern ->
            val foundOffset = findSequenceWithNeonFast(hexPattern, startByteOffset)
            if (foundOffset >= 0L) {
                return foundOffset / BYTES_PER_PAGE
            }
        }

        val utf8Bytes = query.trim().toByteArray(StandardCharsets.UTF_8)
        if (utf8Bytes.isNotEmpty()) {
            val foundOffset = findSequenceWithNeonFast(utf8Bytes, startByteOffset)
            if (foundOffset >= 0L) {
                return foundOffset / BYTES_PER_PAGE
            }
        }

        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try {
            pfd?.close()
        } catch (_: Throwable) {}
    }
}
