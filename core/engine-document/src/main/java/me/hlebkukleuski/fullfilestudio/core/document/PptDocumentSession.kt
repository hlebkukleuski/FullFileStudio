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
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.TreeMap
import java.util.zip.ZipInputStream
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class PptDocumentSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    data class PptSlideData(
        val texts: List<String>,
        val fillColors: List<Int>,
        val pictureIndex: Int?
    )

    private val slides = mutableListOf<PptSlideData>()
    private val pictures = mutableListOf<Bitmap>()
    private var presentation: PptPresentation? = null
    private var renderer: PptSlideRenderer? = null
    private var picturesStream: ByteArray? = null
    private val blipCache = object : android.util.LruCache<Int, PptImage>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: PptImage): Int = value.byteSize.coerceAtLeast(1)
    }
    private val failedBlips = HashSet<Int>()
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 128 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "PPT file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    tryParseZipPresentationFallback(bytes)
                } else {
                    val cfb = Ole2CfbContainer(bytes)
                    if (cfb.isValid && OfficeCrypto.isOfficeIrmProtected(cfb)) {
                        lastErrorMsg = OfficeCrypto.OFFICE_IRM_DRM_ERROR
                    } else if (cfb.isValid && (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null)) {
                        val decZip = OfficeCrypto.decryptOoxmlBytes(bytes, password)
                        if (decZip != null) {
                            tryParseZipPresentationFallback(decZip)
                        } else {
                            lastErrorMsg = if (password.isNullOrEmpty()) {
                                "PASSWORD_REQUIRED: Microsoft PowerPoint presentation is encrypted."
                            } else {
                                "PASSWORD_REQUIRED: Incorrect password for Microsoft PowerPoint presentation."
                            }
                        }
                    } else {
                        val rawPptStream = if (cfb.isValid) cfb.readStream("PowerPoint Document") else null
                        if (rawPptStream == null || rawPptStream.size < 16) {
                            lastErrorMsg = if (cfb.isValid && cfb.listStreams().any { it.name.startsWith("PP40") || it.name == "PP97_DUALSTORAGE" }) {
                                "PowerPoint 95 and earlier presentations are not supported."
                            } else {
                                "Missing PowerPoint Document stream in PPT container."
                            }
                        } else {
                            var pptStream = rawPptStream
                            var currentUser = cfb.readStream("Current User")
                            picturesStream = cfb.readStream("Pictures")
                            if (OfficeCrypto.isEncryptedPpt(pptStream, currentUser)) {
                                if (password.isNullOrEmpty()) {
                                    throw PptEncryptedException()
                                }
                                val dec = OfficeCrypto.decryptPpt(pptStream, currentUser, picturesStream, password)
                                    ?: throw SecurityException("PASSWORD_REQUIRED: Incorrect password for Microsoft PowerPoint presentation (.ppt).")
                                pptStream = dec.powerPointDocument
                                currentUser = dec.currentUser
                                picturesStream = dec.picturesStream
                            }
                            val legacyText = cfb.readStream("Text_Content")
                            if (legacyText != null && (currentUser == null || currentUser.size < 20)) {
                                val lines = String(legacyText, MetafileRenderer.charsetFor(0))
                                    .split('\r').map { it.replace('\u000B', ' ').trim() }
                                var block = ArrayList<String>()
                                for (line in lines) {
                                    if (line.isEmpty() && block.isNotEmpty() && block.size >= 10) {
                                        slides.add(PptSlideData(block, emptyList(), null))
                                        block = ArrayList()
                                    } else if (line.isNotEmpty()) {
                                        block.add(line)
                                    }
                                }
                                if (block.isNotEmpty()) slides.add(PptSlideData(block, emptyList(), null))
                                throw PptFormatException("legacy")
                            }
                            val pres = PptPresentation.parse(pptStream, currentUser)
                            presentation = pres
                            renderer = PptSlideRenderer(pres) { pib -> loadBlip(pib) }
                            if (pres.slides.isEmpty()) lastErrorMsg = "PPT presentation contains no slides."
                        }
                    }
                }
            }
        } catch (_: PptEncryptedException) {
            lastErrorMsg = "PASSWORD_REQUIRED: Microsoft PowerPoint presentation (.ppt) is password protected."
        } catch (e: PptFormatException) {
            if (slides.isEmpty()) lastErrorMsg = "PPT presentation read error: ${e.localizedMessage}"
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "PPT presentation read error: ${e.localizedMessage}"
        }
        if (presentation == null && slides.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "PPT presentation contains no slides or is encrypted."
        }
    }

    private fun loadBlip(pib: Int): PptImage? {
        synchronized(blipCache) {
            blipCache.get(pib)?.let { return it }
            if (pib in failedBlips) return null
        }
        val ref = presentation?.blips?.getOrNull(pib - 1)
        val bmp = ref?.let {
            val data = it.embedded ?: picturesStream
            if (data == null) null else PptBlipDecoder.decodeImage(data, if (it.embedded != null) 0 else it.offset, 2048)
        }
        synchronized(blipCache) {
            if (bmp != null) blipCache.put(pib, bmp) else failedBlips.add(pib)
        }
        return bmp
    }

    private fun tryParseZipPresentationFallback(zipBytes: ByteArray) {
        val rawSlideEntries = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val compContainerLen = zipBytes.size.coerceAtLeast(1)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val entry = try {
                    zis.nextEntry
                } catch (ze: java.util.zip.ZipException) {
                    if (ze.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                    }
                    null
                } catch (_: Throwable) { null } ?: break
                val norm = entry.name.replace('\\', '/').trimStart('/')
                val lower = norm.lowercase(Locale.ROOT)
                if (!entry.isDirectory && (
                            (lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml")) ||
                                    lower == "content.xml" ||
                                    lower == "meta-inf/manifest.xml" ||
                                    lower.startsWith("ppt/media/") ||
                                    lower.startsWith("pictures/")
                            )
                ) {
                    val bos = ByteArrayOutputStream()
                    var r: Int
                    while (zis.read(buf).also { r = it } > 0) {
                        total += r
                        if (total > 16L * 1024L * 1024L && (total / compContainerLen) > 150L) {
                            throw SecurityException("Превышен безопасный лимит распаковки презентации (защита от Zip-бомб).")
                        }
                        if (total > 64L * 1024L * 1024L) break
                        bos.write(buf, 0, r)
                    }
                    val data = bos.toByteArray()
                    if ((lower.startsWith("ppt/media/") || lower.startsWith("pictures/")) && pictures.size < 24) {
                        EmbeddedImageScanner.decodeMetafileOrSvgBitmap(data)?.let { pictures.add(it) }
                    } else {
                        rawSlideEntries[norm] = data
                    }
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
        val slideEntries: Map<String, ByteArray> = if (OfficeCrypto.isEncryptedOdfManifest(rawSlideEntries["META-INF/manifest.xml"])) {
            if (password.isNullOrEmpty()) {
                throw SecurityException("PASSWORD_REQUIRED: ODF presentation (.odp) is encrypted or password protected.")
            }
            OfficeCrypto.decryptOdfEntries(rawSlideEntries, password)
                ?: throw SecurityException("PASSWORD_REQUIRED: Incorrect password for ODF presentation (.odp).")
        } else {
            rawSlideEntries
        }

        val orderedSlides = slideEntries.keys
            .filter { it.lowercase(Locale.ROOT).startsWith("ppt/slides/slide") }
            .sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

        if (orderedSlides.isNotEmpty()) {
            orderedSlides.forEachIndexed { idx, key ->
                val xml = slideEntries[key] ?: return@forEachIndexed
                val sb = StringBuilder()
                EbookTextSession.extractXmlBytesWithCharset(xml, sb)
                val paras = sb.toString()
                    .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                slides.add(PptSlideData(paras, emptyList(), if (idx < pictures.size) idx else null))
            }
        } else if (slideEntries.containsKey("content.xml")) {
            val sb = StringBuilder()
            EbookTextSession.extractXmlBytesWithCharset(slideEntries["content.xml"]!!, sb)
            val paras = sb.toString()
                .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (paras.isNotEmpty()) {
                slides.add(PptSlideData(paras, emptyList(), if (pictures.isNotEmpty()) 0 else null))
            }
        }
    }

    private fun computeSlideCardSize(
        canvasWidth: Float = lastCanvasWidth,
        canvasHeight: Float = lastCanvasHeight
    ): Pair<Float, Float> {
        val margin = 2f
        val availW = (canvasWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (canvasHeight - margin * 2f).coerceAtLeast(100f)
        val pres = presentation
        val aspect = if (pres != null) {
            (pres.slideWidth.toFloat() / pres.slideHeight.coerceAtLeast(1)).coerceIn(0.2f, 5f)
        } else {
            16f / 9f
        }
        var cardH = availH
        var cardW = cardH * aspect
        if (cardW > availW) {
            cardW = availW
            cardH = cardW / aspect
        }
        return cardW to cardH
    }

    override val pageCount: Int get() = (presentation?.slides?.size ?: slides.size).coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = computeSlideCardSize().first
    override fun getDocumentHeight(): Float = computeSlideCardSize().second

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        val pres = presentation
        if (pres != null) {
            if (pageIndex == -1) return pres.slides.indices.joinToString("\n\n") { pres.slideText(it) }
            return pres.slideText(pageIndex and PAGE_INDEX_MASK)
        }
        if (pageIndex == -1) return slides.joinToString("\n\n") { it.texts.joinToString("\n") }
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        return slides.getOrNull(cleanIdx)?.texts?.joinToString("\n") ?: ""
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        for (i in startPage.coerceAtLeast(0) until pageCount) {
            if (getPageText(i).contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
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
        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)
        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f

        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)

        val pres = presentation
        val r = renderer
        if (pres != null && r != null) {
            val index = cleanPageIndex.coerceIn(0, (pres.slides.size - 1).coerceAtLeast(0))
            if (pres.slides.isNotEmpty()) {
                try {
                    r.draw(canvas, index, left, top, cardW / pres.slideWidth.coerceAtLeast(1))
                } catch (e: Exception) {
                    lastErrorMsg = "PPT slide render error: ${e.localizedMessage}"
                }
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawRect(RectF(left, top, left + cardW, top + cardH), borderPaint)
            canvas.restore()
            return
        }

        val slideData = slides.getOrNull(cleanPageIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0)))
        if (slideData == null) {
            canvas.restore()
            return
        }
        val slideRect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(slideRect, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val baseScaleFactor = (cardH / 680f).coerceIn(0.75f, 2.0f)
        val picBmp = slideData.pictureIndex?.let { pictures.getOrNull(it) }
        val textRightMargin = if (picBmp != null && !picBmp.isRecycled) cardW * 0.42f else 80f
        val availWidth = (cardW - textRightMargin).toInt().coerceAtLeast(100)
        var curY = top + 32f
        canvas.save()
        canvas.clipRect(left + 16f, top + 16f, left + cardW - 16f, top + cardH - 16f)
        for ((idx, block) in slideData.texts.withIndex()) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (idx == 0) Color.rgb(25, 35, 60) else Color.DKGRAY
                textSize = (if (idx == 0) 38f else 28f) * baseScaleFactor
                isFakeBoldText = idx == 0
            }
            val l = StaticLayout.Builder.obtain(block, 0, block.length, p, availWidth).setIncludePad(false).build()
            canvas.save()
            canvas.translate(left + 40f, curY)
            l.draw(canvas)
            canvas.restore()
            curY += l.height + 18f
        }
        if (picBmp != null && !picBmp.isRecycled) {
            val boxLeft = left + cardW * 0.60f
            val boxTop = top + cardH * 0.26f
            canvas.drawBitmap(picBmp, null, RectF(boxLeft, boxTop, boxLeft + cardW * 0.36f, boxTop + cardH * 0.62f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.restore()
        canvas.restore()
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        pictures.forEach { if (!it.isRecycled) it.recycle() }
        pictures.clear()
        synchronized(blipCache) { blipCache.evictAll() }
        picturesStream = null
        try { pfd.close() } catch (_: Exception) {}
    }
}
