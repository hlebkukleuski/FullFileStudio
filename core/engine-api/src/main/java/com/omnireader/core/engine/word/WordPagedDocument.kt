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

package com.omnireader.core.engine.word

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

internal class WordPagedDocument private constructor(private val pages: List<LPage>) {
    private val pagePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val pageImages: List<Set<WImage>> = pages.map { p ->
        val set = HashSet<WImage>()
        for (it in p.back) if (it is LItem.Image) set.add(it.image)
        for (it in p.items) if (it is LItem.Image) set.add(it.image)
        set
    }

    val pageCount: Int get() = pages.size

    fun aspectRatio(index: Int): Float {
        val p = pages.getOrNull(index) ?: return 0.707f
        return p.width / p.height.coerceAtLeast(1f)
    }

    fun pageWidth(index: Int): Float = pages.getOrNull(index)?.width ?: 0f

    fun pageHeight(index: Int): Float = pages.getOrNull(index)?.height ?: 0f

    fun pageText(index: Int): String = pages.getOrNull(index)?.text?.toString() ?: ""

    fun fullText(): String {
        val sb = StringBuilder()
        for (p in pages) {
            if (sb.length > 4_000_000) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(p.text)
        }
        return sb.toString()
    }

    fun search(query: String, startPage: Int): Int {
        if (query.isBlank() || pages.isEmpty()) return -1
        val start = startPage.coerceIn(0, pages.size - 1)
        for (k in pages.indices) {
            val i = (start + k) % pages.size
            if (pages[i].text.contains(query, ignoreCase = true)) return i
        }
        return -1
    }

    fun render(index: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val page = pages.getOrNull(index) ?: return
        val zoom = (zoomPermille / 1000f).coerceAtLeast(0.5f)
        val total = minOf(bitmap.width / page.width, bitmap.height / page.height) * zoom
        if (total <= 0f || total.isNaN()) return
        val dx = (bitmap.width - page.width * total) / 2f - tileX
        val dy = (bitmap.height - page.height * total) / 2f - tileY
        val canvas = Canvas(bitmap)
        canvas.translate(dx, dy)
        canvas.scale(total, total)
        canvas.clipRect(0f, 0f, page.width, page.height)
        canvas.drawRect(0f, 0f, page.width, page.height, pagePaint)
        WordRender.drawPage(canvas, page, pages.size)
        releaseFarImages(index)
    }

    private fun releaseFarImages(index: Int) {
        val keep = HashSet<WImage>()
        for (k in (index - 1)..(index + 1)) pageImages.getOrNull(k)?.let { keep.addAll(it) }
        for ((k, set) in pageImages.withIndex()) {
            if (k in (index - 1)..(index + 1)) continue
            for (img in set) if (img !in keep) img.release()
        }
    }

    fun release() {
        for (set in pageImages) for (img in set) img.release()
    }

    companion object {
        fun open(wordStream: ByteArray, tableStream: ByteArray?, dataStream: ByteArray?, maxImageDim: Int = 1600): WordPagedDocument? {
            val doc = DocReader(wordStream, tableStream, dataStream, maxImageDim).read()
            val pages = WordLayout(doc).layout()
            if (pages.isEmpty()) return null
            return WordPagedDocument(pages)
        }

        fun openDocx(zipBytes: ByteArray, maxImageDim: Int = 1600): WordPagedDocument? {
            val entries = DocxReader.unzipEntries(zipBytes)
            return openDocxEntries(entries, maxImageDim)
        }

        fun openDocxEntries(entries: Map<String, ByteArray>, maxImageDim: Int = 1600): WordPagedDocument? {
            val doc = DocxReader(entries, maxImageDim).read() ?: return null
            val pages = WordLayout(doc).layout()
            if (pages.isEmpty()) return null
            return WordPagedDocument(pages)
        }

        fun tableStreamName(wordStream: ByteArray): String {
            val flags = if (wordStream.size >= 12) (wordStream[10].toInt() and 0xFF) or ((wordStream[11].toInt() and 0xFF) shl 8) else 0
            return if (flags and 0x0200 != 0) "1Table" else "0Table"
        }
    }
}
