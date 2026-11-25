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
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class PlainTextSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)
    private val chunkSize = 4000L

    private var detectedCharset: Charset = StandardCharsets.UTF_8
    private var bomSkipBytes: Int = 0

    override val pageCount: Int

    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    init {
        val totalSize = source.size
        if (totalSize > 0L) {
            val sampleLen = minOf(totalSize, 8192L).toInt()
            val sampleBuf = ByteBuffer.allocateDirect(sampleLen)
            val r = source.readAt(0L, sampleBuf)
            if (r > 0) {
                val arr = ByteArray(r)
                sampleBuf.flip()
                sampleBuf.get(arr)
                try {
                    val result = detectCharsetAndValidateText(arr, r)
                    detectedCharset = result.first
                    bomSkipBytes = result.second
                } catch (e: IllegalArgumentException) {
                    lastErrorMsg = e.message
                }
            }
        }
        val effectiveSize = (totalSize - bomSkipBytes).coerceAtLeast(0L)
        pageCount = ((effectiveSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
    }

    override fun getLastError(): String? = lastErrorMsg

    companion object {
        val CP1251: Charset get() = me.hlebkukleuski.fullfilestudio.core.security.TextEncodingDetector.CP1251
        val KOI8_R: Charset get() = me.hlebkukleuski.fullfilestudio.core.security.TextEncodingDetector.KOI8_R
        val IBM866: Charset get() = me.hlebkukleuski.fullfilestudio.core.security.TextEncodingDetector.IBM866

        fun detectCharsetAndValidateText(sample: ByteArray, length: Int): Pair<Charset, Int> =
            me.hlebkukleuski.fullfilestudio.core.security.TextEncodingDetector.detectCharsetAndValidateText(sample, length)
    }

    override fun getPageText(pageIndex: Int): String {
        if (isClosed || lastErrorMsg != null) return ""

        if (pageIndex == -1) {
            val availBytes = (source.size - bomSkipBytes).coerceAtLeast(0L)
            if (availBytes <= 0L) return ""
            val maxReflowBytes = minOf(availBytes, 4L * 1024L * 1024L).toInt()
            val alignedLen = if (detectedCharset == StandardCharsets.UTF_16LE || detectedCharset == StandardCharsets.UTF_16BE) {
                maxReflowBytes and -2
            } else {
                maxReflowBytes
            }
            if (alignedLen <= 0) return ""
            val buf = ByteBuffer.allocateDirect(alignedLen)
            val got = source.readAt(bomSkipBytes.toLong(), buf)
            if (got <= 0) return ""
            val arr = ByteArray(got)
            buf.flip()
            buf.get(arr)
            return String(arr, detectedCharset)
        }

        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        val startOffset = bomSkipBytes + cleanIdx * chunkSize
        if (startOffset >= source.size) return ""
        val bytesToRead = minOf(chunkSize, source.size - startOffset).toInt()
        val alignedBytes = if (detectedCharset == StandardCharsets.UTF_16LE || detectedCharset == StandardCharsets.UTF_16BE) {
            bytesToRead and -2
        } else {
            bytesToRead
        }
        if (alignedBytes <= 0) return ""
        val buffer = ByteBuffer.allocateDirect(alignedBytes)
        val read = source.readAt(startOffset, buffer)
        if (read <= 0) return ""
        val byteArray = ByteArray(read)
        buffer.flip()
        buffer.get(byteArray)
        return String(byteArray, detectedCharset)
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        if (lastErrorMsg != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val cleanPageIndex = pageIndex and PAGE_INDEX_MASK
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
        textPaint.color = if (isLightTheme) Color.rgb(30, 32, 38) else Color.rgb(0xEC, 0xEF, 0xF4)

        val text = getPageText(cleanPageIndex)
        if (text.isNotEmpty()) {
            val contentWidth = (bitmap.width - 80).coerceAtLeast(200)
            val staticLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.14f)
                .setIncludePad(true)
                .build()

            canvas.save()
            canvas.translate(40f - tileX, 40f - tileY)
            canvas.scale(zoom, zoom)
            staticLayout.draw(canvas)
            canvas.restore()
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        val safeStart = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        for (i in safeStart until pageCount) {
            if (getPageText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until safeStart) {
            if (getPageText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}
