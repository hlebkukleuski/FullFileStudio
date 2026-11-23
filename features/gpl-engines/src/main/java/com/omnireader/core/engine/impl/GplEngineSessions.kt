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

package com.omnireader.core.engine.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.system.Os
import android.text.TextPaint
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private const val GPL_LIGHT_THEME_PAGE_FLAG = 1 shl 30
private const val GPL_ILLUSTRATION_ONLY_PAGE_FLAG = 1 shl 29
private const val GPL_PAGE_INDEX_MASK = 0x000FFFFF

class CleanRoomDjvuSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var activeRenderedPageIdx = 0
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)
    private val document: CleanRoomDjvuDocument?
    private val infoCache = HashMap<Int, DjvuPageInfo>()
    private val textCache = HashMap<Int, DjvuTextLayer?>()
    private val pageCache = LinkedHashMap<Int, DjvuPage>()
    private var composedKey: String? = null
    private var composedBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        document = try {
            CleanRoomDjvuDocument(object : DjvuByteSource {
                override val size: Long get() = source.size
                override fun read(offset: Long, length: Int): ByteArray {
                    val safeLen = minOf(length.toLong(), (source.size - offset).coerceAtLeast(0L)).toInt()
                    if (safeLen <= 0) return ByteArray(0)
                    val buf = ByteBuffer.allocateDirect(safeLen)
                    val read = source.readAt(offset, buf)
                    if (read <= 0) return ByteArray(0)
                    buf.flip()
                    val out = ByteArray(buf.remaining())
                    buf.get(out)
                    return out
                }
            }) { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) null else {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val raster = DjvuRaster(bmp.width, bmp.height, px)
                    bmp.recycle()
                    raster
                }
            }
        } catch (e: Throwable) {
            lastErrorMsg = "DjVu parse error: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    private fun cleanIndex(pageIndex: Int): Int =
        (pageIndex and GPL_PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    private fun info(index: Int): DjvuPageInfo? {
        val doc = document ?: return null
        infoCache[index]?.let { return it }
        return try {
            doc.pageInfo(index).also { infoCache[index] = it }
        } catch (e: Throwable) {
            lastErrorMsg = "DjVu page ${index + 1}: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    private fun displaySize(i: DjvuPageInfo): Pair<Int, Int> =
        if (i.rotation == 90 || i.rotation == 270) i.height to i.width else i.width to i.height

    private fun textLayer(index: Int): DjvuTextLayer? {
        val doc = document ?: return null
        if (textCache.containsKey(index)) return textCache[index]
        val layer = try { doc.pageText(index) } catch (_: Throwable) { null }
        textCache[index] = layer
        return layer
    }

    private fun page(index: Int): DjvuPage? {
        val doc = document ?: return null
        pageCache[index]?.let { return it }
        return try {
            val p = doc.decodePage(index)
            pageCache[index] = p
            while (pageCache.size > 2) pageCache.remove(pageCache.keys.first())
            p
        } catch (e: OutOfMemoryError) {
            pageCache.clear()
            lastErrorMsg = "DjVu page ${index + 1} is too large to decode."
            null
        } catch (e: Throwable) {
            lastErrorMsg = "DjVu page ${index + 1}: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    override val pageCount: Int get() = (document?.pageCount ?: 0).coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float {
        val i = info(activeRenderedPageIdx) ?: return 1240f
        return displaySize(i).first.toFloat()
    }

    override fun getDocumentHeight(): Float {
        val i = info(activeRenderedPageIdx) ?: return 1754f
        return displaySize(i).second.toFloat()
    }

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (isClosed || document == null) return ""
        if (pageIndex == -1) return (0 until pageCount).joinToString("\n\n") { textLayer(it)?.text ?: "" }.trim()
        val idx = cleanIndex(pageIndex)
        val layer = textLayer(idx) ?: return ""
        val i = info(idx)
        if (layer.lines.isEmpty() || i == null) return layer.text
        val (dw, dh) = displaySize(i)
        val sb = StringBuilder()
        for (line in layer.lines) {
            val r = rotateRect(line.left.toFloat(), line.top.toFloat(), line.right.toFloat(), line.bottom.toFloat(), i)
            val l = (r.left / dw).coerceIn(0f, 0.99f)
            val t = (r.top / dh).coerceIn(0f, 0.99f)
            val rr = (r.right / dw).coerceIn(l + 0.005f, 1f)
            val b = (r.bottom / dh).coerceIn(t + 0.005f, 1f)
            sb.append(String.format(Locale.US, "[PDF_BOX:%.4f,%.4f,%.4f,%.4f]%s\n", l, t, rr, b, line.text))
        }
        return sb.toString()
    }

    private fun pageToDisplay(i: DjvuPageInfo): Matrix {
        val m = Matrix()
        when (i.rotation) {
            90 -> { m.postRotate(90f); m.postTranslate(i.height.toFloat(), 0f) }
            180 -> { m.postRotate(180f); m.postTranslate(i.width.toFloat(), i.height.toFloat()) }
            270 -> { m.postRotate(270f); m.postTranslate(0f, i.width.toFloat()) }
        }
        return m
    }

    private fun rotateRect(l: Float, t: Float, r: Float, b: Float, i: DjvuPageInfo): RectF {
        val rect = RectF(l, t, r, b)
        pageToDisplay(i).mapRect(rect)
        return rect
    }

    override fun getPageAspectRatio(pageIndex: Int): Float {
        val i = info(cleanIndex(pageIndex)) ?: return 0.707f
        val (w, h) = displaySize(i)
        return w.toFloat() / h.toFloat().coerceAtLeast(1f)
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        bitmap.eraseColor(Color.TRANSPARENT)
        if (document == null) return
        val idx = cleanIndex(pageIndex)
        activeRenderedPageIdx = idx
        val i = info(idx) ?: return
        val (dw, dh) = displaySize(i)
        val zoom = (zoomPermille / 1000f).coerceAtLeast(0.5f)
        val total = minOf(bitmap.width.toFloat() / dw, bitmap.height.toFloat() / dh) * zoom
        val dx = (bitmap.width - dw * total) / 2f - tileX
        val dy = (bitmap.height - dh * total) / 2f - tileY
        val toBitmap = pageToDisplay(i).apply {
            postScale(total, total)
            postTranslate(dx, dy)
        }
        val inverse = Matrix()
        if (!toBitmap.invert(inverse)) return
        val region = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        inverse.mapRect(region)
        if (!region.intersect(0f, 0f, i.width.toFloat(), i.height.toFloat())) return
        var factor = maxOf(1, floor(1f / total).toInt())
        while (((region.width() / factor) * (region.height() / factor)) > 16_000_000f) factor++
        val x0 = (floor(region.left / factor).toInt() * factor).coerceIn(0, i.width)
        val y0 = (floor(region.top / factor).toInt() * factor).coerceIn(0, i.height)
        val x1 = minOf(i.width, ceil(region.right / factor).toInt() * factor)
        val y1 = minOf(i.height, ceil(region.bottom / factor).toInt() * factor)
        if (x1 <= x0 || y1 <= y0) return
        val key = "$idx:$x0:$y0:$x1:$y1:$factor"
        var composed = composedBitmap
        if (composedKey != key || composed == null || composed.isRecycled) {
            val p = page(idx) ?: return
            val raster = try {
                CleanRoomDjvuDocument.compose(p, x0, y0, x1, y1, factor)
            } catch (e: OutOfMemoryError) {
                lastErrorMsg = "DjVu page ${idx + 1} is too large to render."
                return
            }
            if (raster.width <= 0 || raster.height <= 0) return
            composedBitmap?.recycle()
            composed = Bitmap.createBitmap(raster.argb, raster.width, raster.height, Bitmap.Config.ARGB_8888)
            composedBitmap = composed
            composedKey = key
        }
        val draw = Matrix().apply {
            setScale(factor.toFloat(), factor.toFloat())
            postTranslate(x0.toFloat(), y0.toFloat())
            postConcat(toBitmap)
        }
        Canvas(bitmap).drawBitmap(composed!!, draw, bitmapPaint)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed || document == null) return -1L
        val count = document.pageCount
        if (count <= 0) return -1L
        val start = startPage.coerceIn(0, count - 1)
        for (k in 0 until count) {
            val p = (start + k) % count
            if (textLayer(p)?.text?.contains(query, ignoreCase = true) == true) return p.toLong()
        }
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        pageCache.clear()
        composedBitmap?.recycle()
        composedBitmap = null
        source.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}

class PalmDocMobiCleanRoomSession(
    private val pfd: ParcelFileDescriptor,
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
        typeface = Typeface.SERIF
        isAntiAlias = true
    }

    init {
        try {
            val fileSize = resolveStreamDescriptorSize(pfd.fileDescriptor)
            val maxRead = minOf(if (fileSize > 0L) fileSize else 32L * 1024L * 1024L, 32L * 1024L * 1024L).toInt()
            if (maxRead < 86) {
                lastErrorMsg = "Файл $extension пуст или повреждён (< 86 байт)."
            } else {
                val buf = ByteBuffer.allocateDirect(maxRead)
                var total = 0
                while (buf.hasRemaining()) {
                    val r = try { Os.pread(pfd.fileDescriptor, buf, total.toLong()) } catch (_: Throwable) { -1 }
                    if (r <= 0) break
                    total += r
                }
                buf.flip()
                val bytes = ByteArray(total)
                buf.get(bytes)
                parsePalmDatabase(bytes)
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения контейнера PalmDB/MOBI: ${e.localizedMessage}"
        }

        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "Контейнер электронной книги (.${extension.lowercase(Locale.ROOT)}) не содержит читаемого текста или повреждён."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun parsePalmDatabase(bytes: ByteArray) {
        val book = try {
            CleanRoomMobiParser.parse(bytes)
        } catch (drm: MobiDrmException) {
            lastErrorMsg = "DRM_PROTECTED: This Kindle e-book (MOBI/AZW3) is protected by cryptographic DRM (Encryption Type ${drm.encryptionType}). Circumventing DRM protection is prohibited under DMCA § 1201 and international copyright law."
            return
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения контейнера PalmDB/MOBI: ${e.localizedMessage}"
            return
        }
        val decodedIndex = HashMap<Int, Int>()
        for ((i, data) in book.images.withIndex()) {
            if (embeddedImages.size >= 64) break
            val bmp = try { EmbeddedImageScanner.decodeSampledBitmap(data, 0, data.size) } catch (_: Throwable) { null } ?: continue
            decodedIndex[i] = embeddedImages.size
            embeddedImages.add(bmp)
        }
        val remapped = MobiBook(
            book.title,
            book.html,
            book.isKf8,
            book.images,
            book.imageIndexByRecordOffset.mapNotNull { (rec, idx) -> decodedIndex[idx]?.let { rec to it } }.toMap()
        )
        val withMarkers = CleanRoomMobiParser.replaceImageReferences(book.html, remapped)
        val hasInlineImages = withMarkers.contains("[IMAGE:")
        fullText = stripMobiHtmlToRichText(withMarkers, if (hasInlineImages) 0 else embeddedImages.size)
        if (!book.title.isNullOrBlank() && !fullText.startsWith(book.title)) {
            fullText = "<h1>${book.title}</h1>\n" + fullText
        }
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and GPL_PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and GPL_LIGHT_THEME_PAGE_FLAG) != 0
        if (lastErrorMsg != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val isIllustrationOnly = (pageIndex and GPL_ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and GPL_PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and GPL_LIGHT_THEME_PAGE_FLAG.inv())
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

class CleanRoomAccessDatabaseSession(
    pfd: ParcelFileDescriptor,
    private val password: String? = null
) : BaseInMemoryTableDatabaseSession(pfd) {

    private class LazyAccessRows(
        private val reader: CleanRoomAccessReader,
        private val table: CleanRoomAccessReader.Table
    ) : AbstractList<List<String>>() {
        override val size: Int by lazy { reader.rowCount(table) }
        override fun get(index: Int): List<String> {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Row $index")
            return try {
                reader.row(table, index)
            } catch (e: Exception) {
                table.columns.map { "⚠ ${e.message ?: "decode error"}" }
            }
        }
    }

    init {
        try {
            val fileSize = source.size
            val reader = CleanRoomAccessReader(AccessByteSource { offset, length ->
                if (offset < 0 || length < 0 || offset + length > fileSize) throw CorruptedDataException("Read beyond end of database")
                val buf = ByteBuffer.allocateDirect(length)
                var done = 0
                while (done < length) {
                    val r = source.readAt(offset + done, buf)
                    if (r <= 0) break
                    done += r
                }
                if (done != length) throw CorruptedDataException("Unexpected end of database")
                buf.flip()
                ByteArray(length).also { buf.get(it) }
            }, fileSize, password)
            for (t in reader.tables) {
                tables.add(InMemoryTable(t.name, t.columns.map { it.name }, LazyAccessRows(reader, t)))
            }
            if (tables.isEmpty()) {
                lastErrorMsg = "Access database contains no user tables."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message ?: "PASSWORD_REQUIRED: Access database is password protected."
        } catch (e: Exception) {
            lastErrorMsg = if (e.message == "Not a table definition page") {
                "PASSWORD_REQUIRED: Access database is encrypted."
            } else {
                "Access database read error: ${e.localizedMessage}"
            }
        }
    }

    override fun getPageText(pageIndex: Int): String {
        val cleanIdx = pageIndex and GPL_LIGHT_THEME_PAGE_FLAG.inv()
        val targetIdx = if (cleanIdx in tables.indices) cleanIdx else getActiveSheetIndex()
        val tbl = tables.getOrNull(targetIdx) ?: return ""
        val sb = StringBuilder(tbl.columnNames.joinToString("\t"))
        for (i in 0 until minOf(tbl.rows.size, 2000)) sb.append('\n').append(tbl.rows[i].joinToString("\t"))
        return sb.toString()
    }
}
