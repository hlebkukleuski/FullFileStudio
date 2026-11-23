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
 * Portions ported and adapted from resvg, usvg, svgtypes, simplecss, unicode-vo,
 * fontdb, tiny-skia, and kurbo (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, and The Linebender Authors,
 * licensed under Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.system.Os
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.impl.EbookTextSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

internal class SvgDocumentModel(private val source: String, private val fonts: SvgFontDb?) {
    var tree: SvgTree? = null
        private set
    var plainText: String = ""
        private set
    var error: String? = null
        private set
    private val external = HashMap<String, ByteArray>()
    private val missing = LinkedHashSet<String>()

    val missingResources: List<String> get() = missing.toList()

    fun build() {
        missing.clear()
        try {
            val doc = SvgDoc.parse(source)
            plainText = SvgDocumentSession.extractText(doc)
            val opt = SvgOptions(resolveExternal = { resolveExternal(it) }, textRenderer = fonts?.let { SvgText(it) })
            val t = SvgConverter.convert(doc, opt)
            tree = t
            error = if (t == null) "Документ SVG не содержит корректного корневого элемента <svg> или имеет нулевой размер." else null
        } catch (e: SvgXmlException) {
            error = "Некорректная разметка SVG: ${e.message}"
            tree = null
        } catch (e: StackOverflowError) {
            error = "Слишком глубокая вложенность элементов SVG."
            tree = null
        } catch (e: OutOfMemoryError) {
            error = "Недостаточно памяти для обработки SVG."
            tree = null
        } catch (e: RuntimeException) {
            error = "Ошибка обработки SVG: ${e.localizedMessage}"
            tree = null
        }
    }

    fun provide(name: String, bytes: ByteArray): Boolean {
        val n = normalizeRef(name)
        val base = n.substringAfterLast('/')
        val wanted = missing.any { normalizeRef(it).let { m -> m == n || m.substringAfterLast('/') == base } }
        if (!wanted) return false
        external[n] = bytes
        external[base] = bytes
        return true
    }

    fun clear() {
        tree = null
        external.clear()
    }

    private fun resolveExternal(href: String): ByteArray? {
        val h = href.trim()
        val lower = h.lowercase(Locale.ROOT)
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("ftp:") || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("//")) return null
        if (h.isEmpty() || h.startsWith("#")) return null
        val n = normalizeRef(h)
        if (n.isEmpty()) return null
        external[n]?.let { return it }
        external[n.substringAfterLast('/')]?.let { return it }
        val original = try { java.net.URLDecoder.decode(h.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { h }
        missing.add(original.replace('\\', '/').removePrefix("file://").removePrefix("file:").removePrefix("./").substringBefore('#').substringBefore('?').trim())
        return null
    }

    private fun normalizeRef(ref: String): String {
        val decoded = try { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { ref }
        var n = decoded.replace('\\', '/').trim().trim('"')
        n = n.removePrefix("file://").removePrefix("file:")
        while (n.startsWith("./")) n = n.substring(2)
        return n.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
    }
}

class SvgDocumentSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private var model: SvgDocumentModel? = null
    private var scratch: Bitmap? = null

    companion object {
        private const val MAX_SVG_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024

        internal fun decodeText(bytes: ByteArray): String {
            val charset = try { EbookTextSession.detectXmlCharset(bytes) } catch (_: Throwable) { StandardCharsets.UTF_8 }
            val bom = charset == StandardCharsets.UTF_8 && bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val text = String(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0, charset)
            return if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
        }

        internal fun gunzipIfNeeded(raw: ByteArray, limit: Int): ByteArray {
            val isGzip = raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B
            if (!isGzip) return raw
            GZIPInputStream(ByteArrayInputStream(raw)).use { gis ->
                val bos = ByteArrayOutputStream(minOf(raw.size * 3, limit))
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val r = gis.read(buf)
                    if (r == -1) break
                    total += r
                    if (total > limit) throw SecurityException("Превышен безопасный лимит декомпрессии SVGZ (32 МБ).")
                    bos.write(buf, 0, r)
                }
                return bos.toByteArray()
            }
        }

        internal fun extractText(doc: SvgDoc): String {
            val lines = ArrayList<String>()
            fun walk(n: SvgNode) {
                if (n.tag == "text") {
                    val t = n.descendants().filter { it.isText }.joinToString("") { it.textContent() }.trim()
                    if (t.isNotEmpty()) lines.add(t)
                    return
                }
                for (c in n.children) walk(c)
            }
            walk(doc.root)
            return lines.joinToString("\n")
        }

        fun looksLikeSvg(bytes: ByteArray): Boolean = try {
            val head = gunzipHead(bytes)
            val text = decodeText(head).trimStart()
            text.startsWith("<") && Regex("<(?:[A-Za-z_][\\w.-]*:)?svg[\\s>/]").containsMatchIn(text.take(8192))
        } catch (e: Exception) {
            false
        }

        private fun gunzipHead(bytes: ByteArray): ByteArray {
            val isGzip = bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B
            if (!isGzip) return if (bytes.size > 16384) bytes.copyOf(16384) else bytes
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                val buf = ByteArray(16384)
                var n = 0
                while (n < buf.size) { val r = gis.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                return buf.copyOf(n)
            }
        }

        fun rasterize(bytes: ByteArray, maxSide: Int): Bitmap? = try {
            val doc = SvgDoc.parse(decodeText(gunzipIfNeeded(bytes, MAX_SVG_UNCOMPRESSED_BYTES)))
            val t = SvgConverter.convert(doc, SvgOptions(textRenderer = SvgText(SvgFontDb.system)))
            if (t == null || !(t.width > 0f) || !(t.height > 0f)) null else {
                val s = maxSide / maxOf(t.width, t.height)
                val w = Math.round(t.width * s).coerceIn(1, maxSide)
                val h = Math.round(t.height * s).coerceIn(1, maxSide)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                SvgRender.render(t, Ts.scale(w / t.width, h / t.height), bmp)
                bmp
            }
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    init {
        load()
    }

    private fun load() {
        val bytes = readSvgBytes() ?: return
        val m = SvgDocumentModel(decodeText(bytes), SvgFontDb.system)
        m.build()
        model = m
        lastErrorMsg = m.error
    }

    private fun readSvgBytes(): ByteArray? {
        val src = ZeroCacheReadableSource(pfd.fileDescriptor)
        val rawSize = minOf(src.size, MAX_SVG_UNCOMPRESSED_BYTES.toLong()).toInt()
        if (rawSize <= 0) {
            lastErrorMsg = "Файл SVG/SVGZ пуст (0 байт)."
            return null
        }
        val direct = ByteBuffer.allocateDirect(rawSize)
        val read = src.readAt(0L, direct)
        if (read <= 0) {
            lastErrorMsg = "Не удалось прочитать данные SVG/SVGZ."
            return null
        }
        val raw = ByteArray(read)
        direct.flip()
        direct.get(raw)
        return try {
            gunzipIfNeeded(raw, MAX_SVG_UNCOMPRESSED_BYTES)
        } catch (e: SecurityException) {
            lastErrorMsg = e.message
            null
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый архив SVGZ (ошибка декомпрессии GZIP): ${e.localizedMessage}"
            null
        }
    }

    override val pageCount: Int = 1

    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float = model?.tree?.width?.takeIf { it > 0f } ?: 1000f

    override fun getDocumentHeight(): Float = model?.tree?.height?.takeIf { it > 0f } ?: 1000f

    override fun getPageText(pageIndex: Int): String = model?.plainText ?: ""

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        return if (model?.plainText?.contains(query, ignoreCase = true) == true) 0L else -1L
    }

    @Synchronized
    override fun getMissingResources(): List<String> = model?.missingResources ?: emptyList()

    @Synchronized
    override fun provideResource(name: String, fd: FileDescriptor): Boolean {
        if (isClosed) return false
        val bytes = try {
            val size = try { Os.fstat(fd).st_size } catch (_: Throwable) { -1L }
            if (size > MAX_RESOURCE_BYTES) return false
            java.io.FileInputStream(fd).use { it.readBytes() }
        } catch (_: Throwable) {
            return false
        }
        return model?.provide(name, bytes) == true
    }

    @Synchronized
    override fun applyProvidedResources(): Boolean {
        if (isClosed) return false
        val m = model ?: return false
        m.build()
        lastErrorMsg = m.error
        return m.tree != null
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val t = model?.tree ?: return
        val docW = t.width
        val docH = t.height
        if (!(docW > 0f) || !(docH > 0f)) return
        val fitScale = minOf((bitmap.width - 80f) / docW, (bitmap.height - 80f) / docH).coerceAtLeast(0.001f)
        val userZoom = (zoomPermille / 1000f).coerceIn(0.25f, 10f)
        val s = fitScale * userZoom
        val dx = (bitmap.width - docW * s) / 2f - tileX
        val dy = (bitmap.height - docH * s) / 2f - tileY
        var layer = scratch
        if (layer == null || layer.isRecycled || layer.width != bitmap.width || layer.height != bitmap.height) {
            layer?.recycle()
            layer = try { Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { null } ?: return
            scratch = layer
        }
        layer.eraseColor(Color.TRANSPARENT)
        try {
            SvgRender.render(t, Ts(s, 0f, 0f, s, dx, dy), layer)
        } catch (e: OutOfMemoryError) {
            lastErrorMsg = "Недостаточно памяти для отрисовки SVG."
        } catch (e: RuntimeException) {
            lastErrorMsg = "Ошибка отрисовки SVG: ${e.localizedMessage}"
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        model?.clear()
        model = null
        scratch?.recycle()
        scratch = null
        try { pfd.close() } catch (_: Exception) {}
    }
}
