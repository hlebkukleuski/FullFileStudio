/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class ImageRegionSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat = DetectedFormat.UNKNOWN,
    private val extension: String = ""
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private var exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    private var orientedBitmap: Bitmap? = null
    private var baseSampleSize: Int = 1
    private var regionDecoder: BitmapRegionDecoder? = null
    private var cachedRegionBitmap: Bitmap? = null
    private var cachedRegionKey: Long = Long.MIN_VALUE
    private var activeBitmapPageIndex = 0
    private var totalPagesCount = 1
    private var isMultiPageTiff = false
    private val readableSource = ZeroCacheReadableSource(pfd.fileDescriptor)
    private var docW: Float = 0f
    private var docH: Float = 0f
    private val drawMatrix = Matrix()
    private val bitmapDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xE2, 0xE7, 0xF0)
        style = Paint.Style.FILL
    }

    private data class DecodedImageCandidate(
        val bitmap: Bitmap,
        val alreadyOriented: Boolean,
        val sampleSize: Int
    )

    init {
        try {
            val fileSize = readableSource.size
            val cleanExt = extension.lowercase(Locale.ROOT).trim()
            val isTiff = format == DetectedFormat.TIFF || cleanExt in setOf("tif", "tiff", "dng", "cr2", "nef", "arw")
            if (isTiff) {
                val tiffPages = TiffDecoder.getPageCount(readableSource)
                val firstPageBmp = TiffDecoder.decodePage(readableSource, 0)
                if (firstPageBmp != null) {
                    isMultiPageTiff = tiffPages > 1
                    totalPagesCount = tiffPages.coerceAtLeast(1)
                    orientedBitmap = firstPageBmp
                    activeBitmapPageIndex = 0
                    docW = firstPageBmp.width.toFloat()
                    docH = firstPageBmp.height.toFloat()
                }
            }

            if (orientedBitmap == null) {
                exifOrientation = try {
                    val exif = ExifInterface(PositionalPreadInputStream(pfd.fileDescriptor, fileSize))
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: Throwable) {
                    ExifInterface.ORIENTATION_NORMAL
                }

                val candidate = decodeBestBitmap(fileSize, cleanExt)
                if (candidate != null) {
                    baseSampleSize = candidate.sampleSize.coerceAtLeast(1)
                    orientedBitmap = if (candidate.alreadyOriented) {
                        candidate.bitmap
                    } else {
                        applyExifOrientationOnce(candidate.bitmap, exifOrientation)
                    }
                    orientedBitmap?.setHasMipMap(true)
                    docW = orientedBitmap?.width?.toFloat()?.times(baseSampleSize) ?: 0f
                    docH = orientedBitmap?.height?.toFloat()?.times(baseSampleSize) ?: 0f

                    if (baseSampleSize > 1) {
                        regionDecoder = try {
                            try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                BitmapRegionDecoder.newInstance(pfd)
                            } else {
                                @Suppress("DEPRECATION")
                                BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else {
                    val tiffFallback = TiffDecoder.decodePage(readableSource, 0)
                    if (tiffFallback != null) {
                        orientedBitmap = tiffFallback
                        docW = tiffFallback.width.toFloat()
                        docH = tiffFallback.height.toFloat()
                    } else {
                        lastErrorMsg = "Image format (${cleanExt.uppercase(Locale.ROOT).ifEmpty { format.name }}) is not supported by device decoder or file is corrupted."
                    }
                }
            }
        } catch (e: Throwable) {
            lastErrorMsg = "Image decode error: ${e.localizedMessage ?: "Corrupted image data"}"
        }
    }

    private fun isIsoBmffContainer(): Boolean {
        if (format == DetectedFormat.HEIC || format == DetectedFormat.AVIF) return true
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf("heic", "heif", "hif", "avif", "avifs")) return true
        val probe = ByteBuffer.allocateDirect(12)
        val r = readableSource.readAt(0L, probe)
        return r >= 8 &&
                probe.get(4) == 'f'.code.toByte() &&
                probe.get(5) == 't'.code.toByte() &&
                probe.get(6) == 'y'.code.toByte() &&
                probe.get(7) == 'p'.code.toByte()
    }

    /**
     * Clean-room Windows `.ico` / `.cur` directory parser (`00 00 01 00` / `00 00 02 00`).
     * Selects the highest-resolution icon entry and decodes either its embedded PNG stream
     * or its headless `BITMAPINFOHEADER` DIB (with halved `biHeight` and 1-bit AND transparency mask).
     */
    private fun decodeIcoOrCurFromSource(): Bitmap? {
        val maxLen = minOf(readableSource.size, 16L * 1024L * 1024L).toInt()
        if (maxLen < 22) return null
        val headBuf = ByteBuffer.allocateDirect(6).order(ByteOrder.LITTLE_ENDIAN)
        if (readableSource.readAt(0L, headBuf) < 6) return null
        headBuf.flip()
        val reserved = headBuf.short.toInt() and 0xFFFF
        val type = headBuf.short.toInt() and 0xFFFF
        val count = headBuf.short.toInt() and 0xFFFF
        if (reserved != 0 || (type != 1 && type != 2) || count !in 1..64) return null

        val fullBuf = ByteBuffer.allocateDirect(maxLen).order(ByteOrder.LITTLE_ENDIAN)
        val read = readableSource.readAt(0L, fullBuf)
        if (read < 6 + count * 16) return null
        fullBuf.flip()
        val bytes = ByteArray(read)
        fullBuf.get(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var bestOffset = -1
        var bestSize = 0
        var bestScore = -1L

        for (i in 0 until count) {
            val base = 6 + i * 16
            if (base + 16 > bytes.size) break
            val wRaw = bytes[base].toInt() and 0xFF
            val hRaw = bytes[base + 1].toInt() and 0xFF
            val w = if (wRaw == 0) 256 else wRaw
            val h = if (hRaw == 0) 256 else hRaw
            val bpp = (bb.getShort(base + 6).toInt() and 0xFFFF).coerceAtLeast(8)
            val bytesInRes = bb.getInt(base + 8)
            val imgOffset = bb.getInt(base + 12)

            if (imgOffset in (6 + count * 16) until bytes.size &&
                bytesInRes > 16 &&
                imgOffset.toLong() + bytesInRes.toLong() <= bytes.size.toLong()
            ) {
                val score = w.toLong() * h.toLong() * bpp.toLong()
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = imgOffset
                    bestSize = bytesInRes
                }
            }
        }

        if (bestOffset < 0 || bestSize <= 16) return null

        if ((bytes[bestOffset].toInt() and 0xFF) == 0x89 &&
            bytes[bestOffset + 1] == 0x50.toByte() &&
            bytes[bestOffset + 2] == 0x4E.toByte() &&
            bytes[bestOffset + 3] == 0x47.toByte()
        ) {
            BitmapFactory.decodeByteArray(bytes, bestOffset, bestSize)?.let { return it }
        }

        if (bestSize >= 40) {
            val biSize = bb.getInt(bestOffset)
            val biWidth = bb.getInt(bestOffset + 4)
            val biHeightDoubled = bb.getInt(bestOffset + 8)
            val biBitCount = bb.getShort(bestOffset + 14).toInt() and 0xFFFF
            if (biSize == 40 && biWidth in 1..1024 && abs(biHeightDoubled) in 2..2048) {
                val realHeight = biHeightDoubled / 2
                val dibCopy = bytes.copyOfRange(bestOffset, bestOffset + bestSize)
                ByteBuffer.wrap(dibCopy).order(ByteOrder.LITTLE_ENDIAN).putInt(8, realHeight)
                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(dibCopy, 0, dibCopy.size)?.let { return it }
            }
        }

        return EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes, bestOffset, bestSize)
    }

    private fun decodeBestBitmap(fileSize: Long, cleanExt: String): DecodedImageCandidate? {
        val isIsoBmff = isIsoBmffContainer()

        if (cleanExt in setOf("ico", "cur")) {
            decodeIcoOrCurFromSource()?.let {
                return DecodedImageCandidate(it, alreadyOriented = true, sampleSize = 1)
            }
        } else {
            val probe = ByteBuffer.allocateDirect(6)
            if (readableSource.readAt(0L, probe) >= 6 &&
                probe.get(0).toInt() == 0 && probe.get(1).toInt() == 0 &&
                (probe.get(2).toInt() == 1 || probe.get(2).toInt() == 2) &&
                probe.get(3).toInt() == 0
            ) {
                decodeIcoOrCurFromSource()?.let {
                    return DecodedImageCandidate(it, alreadyOriented = true, sampleSize = 1)
                }
            }
        }

        if (isIsoBmff) {
            decodeViaZeroCopyImageDecoder()?.let { return it }
        }

        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                BufferedInputStream(PositionalPreadInputStream(pfd.fileDescriptor, fileSize), 32 * 1024).use { s ->
                    BitmapFactory.decodeStream(s, null, boundsOpts)
                }
            }
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                var sample = 1
                while (boundsOpts.outWidth / sample > 2560 || boundsOpts.outHeight / sample > 2560) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bmp: Bitmap? = try {
                    try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOpts)
                } catch (_: Throwable) {
                    null
                }
                if (bmp == null) {
                    bmp = BufferedInputStream(PositionalPreadInputStream(pfd.fileDescriptor, fileSize), 32 * 1024).use { s ->
                        BitmapFactory.decodeStream(s, null, decodeOpts)
                    }
                }
                if (bmp != null) {
                    val dimensionsSwappedByCodec = (boundsOpts.outWidth > boundsOpts.outHeight && bmp.width < bmp.height) ||
                            (boundsOpts.outHeight > boundsOpts.outWidth && bmp.height < bmp.width)
                    val alreadyRotated = isIsoBmff || dimensionsSwappedByCodec
                    return DecodedImageCandidate(bmp, alreadyOriented = alreadyRotated, sampleSize = sample)
                }
            }
        } catch (_: Throwable) {}

        decodeViaZeroCopyImageDecoder()?.let { return it }

        try {
            val maxBytes = minOf(readableSource.size, 24L * 1024L * 1024L).toInt()
            if (maxBytes > 24) {
                val buf = ByteBuffer.allocateDirect(maxBytes)
                val read = readableSource.readAt(0L, buf)
                if (read > 24) {
                    buf.flip()
                    val arr = ByteArray(read)
                    buf.get(arr)
                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(arr, 0, read)?.let {
                        return DecodedImageCandidate(it, alreadyOriented = true, sampleSize = 1)
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun decodeViaZeroCopyImageDecoder(): DecodedImageCandidate? {
        try {
            var sampleComputed = 1
            val dupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            val imgSource = ImageDecoder.createSource {
                AssetFileDescriptor(dupPfd, 0L, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            val bmp = ImageDecoder.decodeBitmap(imgSource) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > 2560 || h > 2560) {
                    val ratio = minOf(2560f / w, 2560f / h)
                    sampleComputed = maxOf(1, (1f / ratio).roundToInt())
                    decoder.setTargetSize(
                        (w * ratio).roundToInt().coerceAtLeast(1),
                        (h * ratio).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            return DecodedImageCandidate(bmp, alreadyOriented = true, sampleSize = sampleComputed)
        } catch (_: Throwable) {}

        try {
            val maxBytes = minOf(readableSource.size, 48L * 1024L * 1024L).toInt()
            if (maxBytes > 0) {
                var sampleComputed = 1
                val buf = ByteBuffer.allocateDirect(maxBytes)
                val read = readableSource.readAt(0L, buf)
                if (read > 0) {
                    buf.flip()
                    val imgSource = ImageDecoder.createSource(buf)
                    val bmp = ImageDecoder.decodeBitmap(imgSource) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = info.size.width
                        val h = info.size.height
                        if (w > 2560 || h > 2560) {
                            val ratio = minOf(2560f / w, 2560f / h)
                            sampleComputed = maxOf(1, (1f / ratio).roundToInt())
                            decoder.setTargetSize(
                                (w * ratio).roundToInt().coerceAtLeast(1),
                                (h * ratio).roundToInt().coerceAtLeast(1)
                            )
                        }
                    }
                    return DecodedImageCandidate(bmp, alreadyOriented = true, sampleSize = sampleComputed)
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    companion object {
        fun applyExifOrientationOnce(source: Bitmap, orientation: Int): Bitmap {
            if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
                return source
            }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, -1f)
                }
                else -> return source
            }
            return try {
                val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                if (transformed != source) {
                    source.recycle()
                }
                transformed
            } catch (_: Throwable) {
                source
            }
        }
    }

    override val pageCount: Int get() = totalPagesCount
    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = docW
    override fun getDocumentHeight(): Float = docH

    /**
     * Sub-pixel matrix viewport renderer for raster images:
     * Uses exact floating-point `Matrix` scaling & translation inside a precise `canvas.clipRect(visLeft, visTop, visRight, visBottom)`
     * instead of integer-rounded `srcRect` / `visDestRect` rects.
     * Eliminates sub-pixel swimming during 120 FPS pan/zoom and removes white edge halos around opaque photos.
     * When zoomed in on ultra-high-resolution images (`baseSampleSize > 1`), decodes high-resolution regions via `BitmapRegionDecoder`.
     */
    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        val targetPage = cleanIdx.coerceIn(0, (totalPagesCount - 1).coerceAtLeast(0))

        if (isMultiPageTiff && targetPage != activeBitmapPageIndex) {
            TiffDecoder.decodePage(readableSource, targetPage)?.let { newBmp ->
                newBmp.setHasMipMap(true)
                orientedBitmap?.recycle()
                orientedBitmap = newBmp
                activeBitmapPageIndex = targetPage
                docW = newBmp.width.toFloat()
                docH = newBmp.height.toFloat()
            }
        }

        bitmap.eraseColor(Color.TRANSPARENT)

        val fb = orientedBitmap ?: return
        if (fb.isRecycled) return

        val zoom = (zoomPermille / 1000f).coerceIn(0.25f, 10.0f)
        val canvas = Canvas(bitmap)
        val aspect = fb.width.toFloat() / fb.height.toFloat().coerceAtLeast(1f)
        val targetAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)

        val baseW: Float
        val baseH: Float
        if (aspect > targetAspect) {
            baseW = bitmap.width.toFloat()
            baseH = bitmap.width / aspect
        } else {
            baseH = bitmap.height.toFloat()
            baseW = bitmap.height * aspect
        }

        val finalWidth = (baseW * zoom).coerceAtLeast(1f)
        val finalHeight = (baseH * zoom).coerceAtLeast(1f)
        val dx = (bitmap.width - finalWidth) / 2f - tileX.toFloat()
        val dy = (bitmap.height - finalHeight) / 2f - tileY.toFloat()

        val visLeft = maxOf(0f, dx)
        val visTop = maxOf(0f, dy)
        val visRight = minOf(bitmap.width.toFloat(), dx + finalWidth)
        val visBottom = minOf(bitmap.height.toFloat(), dy + finalHeight)
        if (visRight <= visLeft || visBottom <= visTop) return

        canvas.save()
        canvas.clipRect(visLeft, visTop, visRight, visBottom)

        if (fb.hasAlpha()) {
            cardBgPaint.color = Color.rgb(0xF8, 0xFA, 0xFC)
            canvas.drawRect(visLeft, visTop, visRight, visBottom, cardBgPaint)
            val cell = 24f
            var y = visTop
            var rowIdx = 0
            while (y < visBottom) {
                var x = visLeft + if (rowIdx % 2 == 1) cell else 0f
                val nextY = minOf(y + cell, visBottom)
                while (x < visRight) {
                    canvas.drawRect(x, y, minOf(x + cell, visRight), nextY, checkerPaint)
                    x += cell * 2f
                }
                y = nextY
                rowIdx++
            }
        }

        drawMatrix.reset()
        drawMatrix.setScale(
            finalWidth / fb.width.toFloat().coerceAtLeast(1f),
            finalHeight / fb.height.toFloat().coerceAtLeast(1f)
        )
        drawMatrix.postTranslate(dx, dy)
        canvas.drawBitmap(fb, drawMatrix, bitmapDrawPaint)

        val regDec = regionDecoder
        if (regDec != null && !regDec.isRecycled && baseSampleSize > 1 && zoom > 1.35f) {
            try {
                val rawW = regDec.width
                val rawH = regDec.height
                val u0 = ((visLeft - dx) / finalWidth).coerceIn(0f, 1f)
                val v0 = ((visTop - dy) / finalHeight).coerceIn(0f, 1f)
                val u1 = ((visRight - dx) / finalWidth).coerceIn(u0 + 0.001f, 1f)
                val v1 = ((visBottom - dy) / finalHeight).coerceIn(v0 + 0.001f, 1f)

                val unalignedRect = when (exifOrientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> Rect(
                        (v0 * rawW).toInt(),
                        ((1f - u1) * rawH).toInt(),
                        ceil(v1 * rawW).toInt(),
                        ceil((1f - u0) * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_ROTATE_180 -> Rect(
                        ((1f - u1) * rawW).toInt(),
                        ((1f - v1) * rawH).toInt(),
                        ceil((1f - u0) * rawW).toInt(),
                        ceil((1f - v0) * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_ROTATE_270 -> Rect(
                        ((1f - v1) * rawW).toInt(),
                        (u0 * rawH).toInt(),
                        ceil((1f - v0) * rawW).toInt(),
                        ceil(u1 * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Rect(
                        ((1f - u1) * rawW).toInt(),
                        (v0 * rawH).toInt(),
                        ceil((1f - u0) * rawW).toInt(),
                        ceil(v1 * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Rect(
                        (u0 * rawW).toInt(),
                        ((1f - v1) * rawH).toInt(),
                        ceil(u1 * rawW).toInt(),
                        ceil((1f - v0) * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_TRANSPOSE -> Rect(
                        (v0 * rawW).toInt(),
                        (u0 * rawH).toInt(),
                        ceil(v1 * rawW).toInt(),
                        ceil(u1 * rawH).toInt()
                    )
                    ExifInterface.ORIENTATION_TRANSVERSE -> Rect(
                        ((1f - v1) * rawW).toInt(),
                        ((1f - u1) * rawH).toInt(),
                        ceil((1f - v0) * rawW).toInt(),
                        ceil((1f - u0) * rawH).toInt()
                    )
                    else -> Rect(
                        (u0 * rawW).toInt(),
                        (v0 * rawH).toInt(),
                        ceil(u1 * rawW).toInt(),
                        ceil(v1 * rawH).toInt()
                    )
                }

                val rawRect = Rect(
                    unalignedRect.left.coerceIn(0, rawW - 1),
                    unalignedRect.top.coerceIn(0, rawH - 1),
                    unalignedRect.right.coerceIn(1, rawW),
                    unalignedRect.bottom.coerceIn(1, rawH)
                )

                val regionW = rawRect.width()
                val regionH = rawRect.height()
                if (regionW > 4 && regionH > 4) {
                    var regSample = 1
                    while (regionW / regSample > 2048 || regionH / regSample > 2048) {
                        regSample *= 2
                    }
                    if (regSample < baseSampleSize) {
                        val reqKey = ((rawRect.left / 4).toLong() shl 48) or
                                (((rawRect.top / 4).toLong() and 0xFFFFL) shl 32) or
                                (((rawRect.right / 4).toLong() and 0xFFFFL) shl 16) or
                                ((rawRect.bottom / 4).toLong() and 0xFFF0L) or
                                (regSample.toLong() and 0xFL)

                        var cropBmp = cachedRegionBitmap
                        if (cropBmp == null || cropBmp.isRecycled || cachedRegionKey != reqKey) {
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = regSample
                                inPreferredConfig = if (fb.hasAlpha()) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                            }
                            val decoded = regDec.decodeRegion(rawRect, opts)
                            if (decoded != null) {
                                val orientedCrop = applyExifOrientationOnce(decoded, exifOrientation)
                                cachedRegionBitmap?.recycle()
                                cachedRegionBitmap = orientedCrop
                                cachedRegionKey = reqKey
                                cropBmp = orientedCrop
                            }
                        }

                        if (cropBmp != null && !cropBmp.isRecycled) {
                            val dstCropLeft = dx + u0 * finalWidth
                            val dstCropTop = dy + v0 * finalHeight
                            val dstCropW = (u1 - u0) * finalWidth
                            val dstCropH = (v1 - v0) * finalHeight

                            drawMatrix.reset()
                            drawMatrix.setScale(
                                dstCropW / cropBmp.width.toFloat().coerceAtLeast(1f),
                                dstCropH / cropBmp.height.toFloat().coerceAtLeast(1f)
                            )
                            drawMatrix.postTranslate(dstCropLeft, dstCropTop)
                            canvas.drawBitmap(cropBmp, drawMatrix, bitmapDrawPaint)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        try { regionDecoder?.recycle() } catch (_: Throwable) {}
        regionDecoder = null
        cachedRegionBitmap?.recycle()
        cachedRegionBitmap = null
        orientedBitmap?.recycle()
        orientedBitmap = null
        readableSource.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}
