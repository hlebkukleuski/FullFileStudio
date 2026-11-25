/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.document.DmlNode
import me.hlebkukleuski.fullfilestudio.core.document.DmlPackage
import me.hlebkukleuski.fullfilestudio.core.document.DmlPresentation
import me.hlebkukleuski.fullfilestudio.core.document.DmlSlideRenderer
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey

class PptxPresentationSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    private class SlideMeta(val transition: String, val durationMs: Int, val animatedIds: List<String>)

    private var presentation: DmlPresentation? = null
    private var renderer: DmlSlideRenderer? = null
    private val metas = ArrayList<SlideMeta>()
    private val textCache = HashMap<Int, String>()
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f

    init {
        load()
    }

    private fun load() {
        val filter: (String) -> Boolean = { name ->
            val lower = name.lowercase(Locale.ROOT)
            !lower.startsWith("ppt/embeddings/") && SKIPPED_MEDIA.none { lower.endsWith(it) }
        }
        val parts = try {
            if (isEncryptedOle2Container(pfd.fileDescriptor)) {
                val raw = OfficeZipContainerLoader.readRawBytes(pfd, 192 * 1024 * 1024L)
                val decZip = me.hlebkukleuski.fullfilestudio.core.document.OfficeCrypto.decryptOoxmlBytes(raw, password)
                if (decZip == null) {
                    lastErrorMsg = if (password.isNullOrEmpty()) {
                        "PASSWORD_REQUIRED: PowerPoint presentation (.pptx) is encrypted or password protected."
                    } else {
                        "PASSWORD_REQUIRED: Incorrect password for PowerPoint presentation (.pptx)."
                    }
                    return
                }
                OfficeZipContainerLoader.readZipEntriesFromBytes(decZip, 192 * 1024 * 1024L, filter)
            } else {
                OfficeZipContainerLoader.readZipEntries(pfd = pfd, maxTotalBytes = 192 * 1024 * 1024L, entryFilter = filter)
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
            return
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер PPTX: ${e.localizedMessage}"
            return
        }
        val pres = try {
            DmlPresentation(DmlPackage(parts))
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер PPTX: ${e.localizedMessage}"
            return
        }
        if (pres.slides.isEmpty()) {
            lastErrorMsg = "В контейнере PPTX отсутствуют слайды."
            return
        }
        presentation = pres
        renderer = DmlSlideRenderer(pres)
        for (slide in pres.slides) metas.add(metaFor(slide.root))
    }

    private fun metaFor(root: DmlNode): SlideMeta {
        val tr = root.child("transition")
        var type = "fade"
        var dur = 320
        if (tr != null) {
            val explicit = tr.attr("dur")?.toIntOrNull()
            dur = when {
                explicit != null && explicit in 100..2500 -> explicit
                tr.attr("spd") == "slow" -> 520
                tr.attr("spd") == "fast" -> 200
                else -> 320
            }
            tr.children.firstOrNull { it.name.lowercase(Locale.ROOT) in TRANSITIONS }?.let { type = it.name.lowercase(Locale.ROOT) }
        }
        val ids = LinkedHashSet<String>()
        root.child("timing")?.descendants("spTgt")?.forEach { t ->
            t.attr("spid")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (ids.size < 64) ids.add(it) }
        }
        return SlideMeta(type, dur, ids.toList())
    }

    private fun computeSlideCardSize(canvasWidth: Float = lastCanvasWidth, canvasHeight: Float = lastCanvasHeight): Pair<Float, Float> {
        val margin = 2f
        val availW = (canvasWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (canvasHeight - margin * 2f).coerceAtLeast(100f)
        val pres = presentation
        val aspect = if (pres != null) (pres.slideWidth.toFloat() / pres.slideHeight.coerceAtLeast(1L)).coerceIn(0.2f, 5f) else 16f / 9f
        var cardH = availH
        var cardW = cardH * aspect
        if (cardW > availW) {
            cardW = availW
            cardH = cardW / aspect
        }
        return cardW to cardH
    }

    override val pageCount: Int get() = (presentation?.slides?.size ?: 0).coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = computeSlideCardSize().first
    override fun getDocumentHeight(): Float = computeSlideCardSize().second

    private fun plainText(index: Int): String {
        val pres = presentation ?: return ""
        synchronized(textCache) {
            return textCache.getOrPut(index) {
                val body = pres.slideText(index)
                val notes = pres.slides.getOrNull(index)?.let { pres.notesText(it) }.orEmpty()
                if (notes.isNotBlank()) "$body\n\n[Notes]\n$notes".trim() else body
            }
        }
    }

    override fun getPageText(pageIndex: Int): String {
        val pres = presentation ?: return ""
        if (pageIndex == -1) return pres.slides.indices.joinToString("\n\n") { plainText(it) }
        val idx = pageIndex and PAGE_INDEX_MASK
        if (idx !in pres.slides.indices) return ""
        val meta = metas.getOrNull(idx) ?: SlideMeta("fade", 320, emptyList())
        val metaLine = "[SLIDE_META:transition=${meta.transition};dur=${meta.durationMs};steps=${meta.animatedIds.size.coerceAtMost(15)}]"
        val base = plainText(idx)
        return if (base.isEmpty()) metaLine else "$base\n$metaLine"
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        lastCanvasWidth = bitmap.width.toFloat()
        lastCanvasHeight = bitmap.height.toFloat()
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val animStep = (pageIndex ushr ANIM_STEP_SHIFT) and 0xF
        val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_PRESENTATION_BG_COLOR else DARK_PRESENTATION_BG_COLOR)
        val pres = presentation ?: return
        val r = renderer ?: return
        val index = cleanPageIndex.coerceIn(0, pres.slides.size - 1)
        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)
        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f
        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)
        val ids = metas.getOrNull(index)?.animatedIds.orEmpty()
        val hidden = if (animStep in 1..ids.size) ids.subList(animStep, ids.size).toSet() else emptySet()
        try {
            synchronized(this) { r.draw(canvas, index, left, top, cardW / pres.slideWidth, hidden) }
        } catch (e: Exception) {
            lastErrorMsg = "PPTX slide render error: ${e.localizedMessage}"
        }
        canvas.drawRect(RectF(left, top, left + cardW, top + cardH), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        })
        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        val pres = presentation ?: return -1L
        for (i in startPage.coerceAtLeast(0) until pres.slides.size) {
            if (plainText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        presentation?.clearCaches()
        try { pfd.close() } catch (_: Exception) {}
    }

    companion object {
        private val SKIPPED_MEDIA = listOf(".mp4", ".m4v", ".mov", ".wmv", ".avi", ".mpg", ".mpeg", ".mp3", ".wav", ".m4a", ".wma", ".aac", ".mkv", ".webm", ".swf")
        private val TRANSITIONS = setOf("fade", "push", "wipe", "split", "cover", "pull", "zoom", "wheel", "dissolve", "cut", "blinds", "checker", "circle", "comb", "diamond", "plus", "wedge")
    }
}
