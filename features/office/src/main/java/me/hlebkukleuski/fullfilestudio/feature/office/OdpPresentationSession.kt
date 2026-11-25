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
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey

class OdpPresentationSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    private var document: OdpEngine.OdpDocument? = null
    private val bitmapCache = HashMap<String, Bitmap?>()
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f

    init {
        parseOdp()
    }

    private fun parseOdp() {
        try {
            val rawEntries = OfficeZipContainerLoader.readZipEntries(
                pfd = pfd,
                maxTotalBytes = 96 * 1024 * 1024L
            ) { normName ->
                normName.equals("content.xml", ignoreCase = true) ||
                        normName.equals("styles.xml", ignoreCase = true) ||
                        normName.equals("META-INF/manifest.xml", ignoreCase = true) ||
                        normName.startsWith("Pictures/", ignoreCase = true) ||
                        normName.startsWith("media/", ignoreCase = true)
            }
            val entries = if (OdpEngine.isEncryptedOdf(rawEntries)) {
                if (password.isNullOrEmpty()) {
                    lastErrorMsg = "PASSWORD_REQUIRED: ODP presentation is encrypted or password protected."
                    return
                }
                me.hlebkukleuski.fullfilestudio.core.document.OfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                    lastErrorMsg = "PASSWORD_REQUIRED: Incorrect password for ODP presentation."
                    return
                }
            } else {
                rawEntries
            }
            val parsed = OdpEngine.parse(entries)
            if (parsed == null) {
                lastErrorMsg = "В контейнере ODP отсутствует обязательный файл content.xml."
            } else if (parsed.slides.isEmpty()) {
                lastErrorMsg = "В презентации ODP не найдено слайдов."
            } else {
                document = parsed
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер ODP: ${e.localizedMessage}"
        }
    }

    private fun computeSlideCardSize(
        canvasWidth: Float = lastCanvasWidth,
        canvasHeight: Float = lastCanvasHeight
    ): Pair<Float, Float> {
        val margin = 2f
        val availW = (canvasWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (canvasHeight - margin * 2f).coerceAtLeast(100f)
        val doc = document
        val pw = doc?.pageWidthCm ?: 28.0f
        val ph = doc?.pageHeightCm ?: 15.75f
        val aspect = (pw / ph.coerceAtLeast(1f)).coerceIn(1.0f, 2.5f)
        var cardH = availH
        var cardW = cardH * aspect
        if (cardW > availW) {
            cardW = availW
            cardH = cardW / aspect
        }
        return cardW to cardH
    }

    override val pageCount: Int get() = (document?.slides?.size ?: 0).coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = computeSlideCardSize().first
    override fun getDocumentHeight(): Float = computeSlideCardSize().second

    override fun getPageText(pageIndex: Int): String {
        val slides = document?.slides ?: return ""
        if (pageIndex == -1) return slides.joinToString("\n\n") { it.plainText }
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        return slides.getOrNull(cleanIdx)?.plainText ?: ""
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        lastCanvasWidth = bitmap.width.toFloat()
        lastCanvasHeight = bitmap.height.toFloat()

        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_PRESENTATION_BG_COLOR else DARK_PRESENTATION_BG_COLOR)
        val doc = document ?: return
        val slide = doc.slides.getOrNull(cleanPageIndex.coerceIn(0, (doc.slides.size - 1).coerceAtLeast(0))) ?: return
        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)

        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f

        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)

        val slideRect = RectF(left, top, left + cardW, top + cardH)
        OdpEngine.drawSlide(canvas, doc, slide, slideRect, bitmapCache)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(slideRect, borderPaint)
        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        val slides = document?.slides ?: return -1L
        if (query.isBlank()) return -1L
        for (i in startPage.coerceAtLeast(0) until slides.size) {
            if (slides[i].plainText.contains(query, ignoreCase = true) || slides[i].notesText.contains(query, ignoreCase = true)) {
                return i.toLong()
            }
        }
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        for (bmp in bitmapCache.values) {
            try { if (bmp != null && !bmp.isRecycled) bmp.recycle() } catch (_: Throwable) {}
        }
        bitmapCache.clear()
        document = null
        try { pfd.close() } catch (_: Exception) {}
    }
}
