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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileEngineProvider
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
internal const val ILLUSTRATION_ONLY_PAGE_FLAG = 1 shl 29
private const val PAGE_INDEX_MASK = 0x000FFFFF

internal fun resolveStreamDescriptorSize(fd: FileDescriptor): Long {
    val statSize = try {
        Os.fstat(fd).st_size
    } catch (_: Throwable) {
        -1L
    }
    if (statSize > 0L) return statSize

    return try {
        val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
        val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
        Os.lseek(fd, cur, OsConstants.SEEK_SET)
        if (end > 0L) end else -1L
    } catch (_: Throwable) {
        -1L
    }
}

internal class PositionalPreadInputStream(
    private val fd: FileDescriptor,
    private val totalSize: Long
) : InputStream() {
    private var position = 0L
    private val singleByte = ByteArray(1)
    private val directChunk = ByteBuffer.allocateDirect(32 * 1024)

    override fun read(): Int {
        val r = read(singleByte, 0, 1)
        return if (r == 1) (singleByte[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (totalSize > 0L && position >= totalSize) return -1
        val remaining = if (totalSize > 0L) (totalSize - position) else len.toLong()
        val toRead = minOf(len.toLong(), remaining, directChunk.capacity().toLong()).toInt()
        if (toRead <= 0) return -1

        directChunk.clear()
        directChunk.limit(toRead)
        val r = try {
            Os.pread(fd, directChunk, position)
        } catch (e: Exception) {
            throw IOException("pread failed at offset $position: ${e.message}", e)
        }
        if (r <= 0) return -1
        directChunk.flip()
        directChunk.get(b, off, r)
        position += r
        return r
    }
}

/**
 * 100% Clean-room TIFF 6.0 decoder (ISO 12639 / Adobe TIFF 6.0 & Tech Note #2 specification).
 * Supports Little-Endian & Big-Endian multi-page IFDs, Strip and Tile layouts (tags 322–325),
 * Uncompressed (1), LZW (5), Old & New JPEG-in-TIFF (6 / 7 + JPEGTables tag 347 & 513/514),
 * Deflate (8 / 32946), PackBits (32773), Horizontal Predictor (2), Bilevel, Grayscale, Palette, RGB, RGBA, YCbCr, CMYK.
 */
object TiffCleanRoomDecoder {

    private class IfdDirectory(
        val width: Int,
        val height: Int,
        val bitsPerSample: IntArray,
        val compression: Int,
        val photometric: Int,
        val stripOffsets: LongArray,
        val samplesPerPixel: Int,
        val rowsPerStrip: Int,
        val stripByteCounts: LongArray,
        val tileWidth: Int,
        val tileLength: Int,
        val tileOffsets: LongArray,
        val tileByteCounts: LongArray,
        val jpegTables: ByteArray?,
        val jpegInterchangeOffset: Long,
        val jpegInterchangeLength: Long,
        val predictor: Int,
        val colorMap: IntArray?,
        val orientation: Int
    ) {
        val isTiled: Boolean
            get() = tileWidth > 0 && tileLength > 0 && tileOffsets.isNotEmpty()
    }

    fun isTiffHeader(bytes: ByteArray, len: Int): Boolean {
        if (len < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return (b0 == 0x49 && b1 == 0x49 && b2 == 0x2A && b3 == 0x00) ||
                (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && b3 == 0x2A)
    }

    fun getPageCount(source: ZeroCacheReadableSource): Int {
        return try {
            val bytes = readTiffBytes(source) ?: return 1
            parseIfdDirectories(bytes).size.coerceAtLeast(1)
        } catch (_: Throwable) {
            1
        }
    }

    fun decodePage(source: ZeroCacheReadableSource, pageIndex: Int): Bitmap? {
        val bytes = readTiffBytes(source) ?: return null
        val dirs = parseIfdDirectories(bytes)
        if (dirs.isEmpty()) return null
        val ifd = dirs[pageIndex.coerceIn(0, dirs.lastIndex)]
        val order = if (bytes[0] == 0x49.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val rawBmp = renderIfdToBitmap(bytes, order, ifd) ?: return null
        return ImageRegionSession.applyExifOrientationOnce(rawBmp, ifd.orientation)
    }

    fun decodeFromBytes(bytes: ByteArray, pageIndex: Int = 0): Bitmap? {
        if (!isTiffHeader(bytes, bytes.size)) return null
        val dirs = parseIfdDirectories(bytes)
        if (dirs.isEmpty()) return null
        val ifd = dirs[pageIndex.coerceIn(0, dirs.lastIndex)]
        val order = if (bytes[0] == 0x49.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val rawBmp = renderIfdToBitmap(bytes, order, ifd) ?: return null
        return ImageRegionSession.applyExifOrientationOnce(rawBmp, ifd.orientation)
    }

    private fun readTiffBytes(source: ZeroCacheReadableSource): ByteArray? {
        val maxLen = minOf(source.size, 64L * 1024L * 1024L).toInt()
        if (maxLen < 8) return null
        val buf = ByteBuffer.allocateDirect(maxLen)
        val r = source.readAt(0L, buf)
        if (r < 8) return null
        val arr = ByteArray(r)
        buf.flip()
        buf.get(arr)
        return if (isTiffHeader(arr, r)) arr else null
    }

    private fun parseIfdDirectories(bytes: ByteArray): List<IfdDirectory> {
        val order = if (bytes[0] == 0x49.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(bytes).order(order)
        val result = mutableListOf<IfdDirectory>()
        var ifdOffset = bb.getInt(4).toLong() and 0xFFFFFFFFL
        val visited = HashSet<Long>()

        while (ifdOffset in 8L..(bytes.size - 2).toLong() && visited.add(ifdOffset) && result.size < 250) {
            val pos = ifdOffset.toInt()
            val entryCount = bb.getShort(pos).toInt() and 0xFFFF
            if (pos + 2 + entryCount * 12 > bytes.size) break

            var width = 0
            var height = 0
            var bitsPerSample = intArrayOf(8)
            var compression = 1
            var photometric = 2
            var stripOffsets = LongArray(0)
            var samplesPerPixel = 1
            var rowsPerStrip = Int.MAX_VALUE
            var stripByteCounts = LongArray(0)
            var tileWidth = 0
            var tileLength = 0
            var tileOffsets = LongArray(0)
            var tileByteCounts = LongArray(0)
            var jpegTables: ByteArray? = null
            var jpegIfOffset = 0L
            var jpegIfLength = 0L
            var predictor = 1
            var colorMap: IntArray? = null
            var orientation = ExifInterface.ORIENTATION_NORMAL

            for (i in 0 until entryCount) {
                val entryBase = pos + 2 + i * 12
                val tag = bb.getShort(entryBase).toInt() and 0xFFFF
                val type = bb.getShort(entryBase + 2).toInt() and 0xFFFF
                val count = (bb.getInt(entryBase + 4).toLong() and 0xFFFFFFFFL).toInt().coerceIn(0, 2_000_000)
                val valOffsetField = entryBase + 8

                when (tag) {
                    256 -> width = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0
                    257 -> height = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0
                    258 -> bitsPerSample = readTagInts(bb, bytes.size, type, count, valOffsetField).let { if (it.isEmpty()) intArrayOf(8) else it }
                    259 -> compression = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 1
                    262 -> photometric = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 2
                    273 -> stripOffsets = readTagLongs(bb, bytes.size, type, count, valOffsetField)
                    274 -> orientation = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 1
                    277 -> samplesPerPixel = (readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 1).coerceIn(1, 8)
                    278 -> rowsPerStrip = (readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: height).coerceAtLeast(1)
                    279 -> stripByteCounts = readTagLongs(bb, bytes.size, type, count, valOffsetField)
                    317 -> predictor = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 1
                    320 -> colorMap = readTagInts(bb, bytes.size, type, count, valOffsetField)
                    322 -> tileWidth = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0
                    323 -> tileLength = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0
                    324 -> tileOffsets = readTagLongs(bb, bytes.size, type, count, valOffsetField)
                    325 -> tileByteCounts = readTagLongs(bb, bytes.size, type, count, valOffsetField)
                    347 -> jpegTables = readTagRawBytes(bb, bytes, type, count, valOffsetField)
                    513 -> jpegIfOffset = readTagLongs(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0L
                    514 -> jpegIfLength = readTagLongs(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0L
                }
            }

            val hasValidData = (width > 0 && height > 0) &&
                    (stripOffsets.isNotEmpty() || tileOffsets.isNotEmpty() || jpegIfOffset > 0L)
            if (hasValidData) {
                if (samplesPerPixel == 1 && bitsPerSample.size > 1) {
                    samplesPerPixel = bitsPerSample.size
                }
                result.add(
                    IfdDirectory(
                        width = width,
                        height = height,
                        bitsPerSample = bitsPerSample,
                        compression = compression,
                        photometric = photometric,
                        stripOffsets = stripOffsets,
                        samplesPerPixel = samplesPerPixel,
                        rowsPerStrip = rowsPerStrip.coerceIn(1, height.coerceAtLeast(1)),
                        stripByteCounts = stripByteCounts,
                        tileWidth = tileWidth,
                        tileLength = tileLength,
                        tileOffsets = tileOffsets,
                        tileByteCounts = tileByteCounts,
                        jpegTables = jpegTables,
                        jpegInterchangeOffset = jpegIfOffset,
                        jpegInterchangeLength = jpegIfLength,
                        predictor = predictor,
                        colorMap = colorMap,
                        orientation = orientation
                    )
                )
            }

            val nextPos = pos + 2 + entryCount * 12
            if (nextPos + 4 > bytes.size) break
            ifdOffset = bb.getInt(nextPos).toLong() and 0xFFFFFFFFL
        }
        return result
    }

    private fun typeByteSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    private fun readTagRawBytes(
        bb: ByteBuffer,
        fileBytes: ByteArray,
        type: Int,
        count: Int,
        valFieldPos: Int
    ): ByteArray? {
        if (count <= 0) return null
        val unitSize = typeByteSize(type)
        val totalBytes = count.toLong() * unitSize
        if (totalBytes <= 0L || totalBytes > 16 * 1024 * 1024L) return null
        val dataPos = if (totalBytes <= 4L) {
            valFieldPos
        } else {
            (bb.getInt(valFieldPos).toLong() and 0xFFFFFFFFL).toInt()
        }
        if (dataPos < 0 || dataPos + totalBytes.toInt() > fileBytes.size) return null
        return fileBytes.copyOfRange(dataPos, dataPos + totalBytes.toInt())
    }

    private fun readTagInts(bb: ByteBuffer, limit: Int, type: Int, count: Int, valFieldPos: Int): IntArray {
        val longs = readTagLongs(bb, limit, type, count, valFieldPos)
        return IntArray(longs.size) { longs[it].toInt() }
    }

    private fun readTagLongs(bb: ByteBuffer, limit: Int, type: Int, count: Int, valFieldPos: Int): LongArray {
        if (count <= 0) return LongArray(0)
        val unitSize = typeByteSize(type)
        val totalBytes = count.toLong() * unitSize
        val dataPos = if (totalBytes <= 4L) {
            valFieldPos
        } else {
            (bb.getInt(valFieldPos).toLong() and 0xFFFFFFFFL).toInt()
        }
        if (dataPos < 0 || dataPos.toLong() + totalBytes > limit.toLong()) return LongArray(0)

        return LongArray(count) { i ->
            val p = dataPos + i * unitSize
            when (type) {
                1, 6, 7 -> bb.get(p).toLong() and 0xFFL
                3 -> bb.getShort(p).toLong() and 0xFFFFL
                8 -> bb.getShort(p).toLong()
                4 -> bb.getInt(p).toLong() and 0xFFFFFFFFL
                9 -> bb.getInt(p).toLong()
                else -> 0L
            }
        }
    }

    private fun decodeJpegInTiffChunk(
        fileBytes: ByteArray,
        offset: Int,
        length: Int,
        jpegTables: ByteArray?
    ): Bitmap? {
        if (offset < 0 || length <= 2 || offset + length > fileBytes.size) return null

        if (jpegTables == null || jpegTables.size < 4) {
            return BitmapFactory.decodeByteArray(fileBytes, offset, length)
        }

        var tablesEnd = jpegTables.size
        if (tablesEnd >= 2 &&
            (jpegTables[tablesEnd - 2].toInt() and 0xFF) == 0xFF &&
            (jpegTables[tablesEnd - 1].toInt() and 0xFF) == 0xD9
        ) {
            tablesEnd -= 2
        }

        var chunkStart = offset
        var chunkLen = length
        if (chunkLen >= 2 &&
            (fileBytes[chunkStart].toInt() and 0xFF) == 0xFF &&
            (fileBytes[chunkStart + 1].toInt() and 0xFF) == 0xD8
        ) {
            chunkStart += 2
            chunkLen -= 2
        }

        val combined = ByteArray(tablesEnd + chunkLen)
        System.arraycopy(jpegTables, 0, combined, 0, tablesEnd)
        System.arraycopy(fileBytes, chunkStart, combined, tablesEnd, chunkLen)

        return BitmapFactory.decodeByteArray(combined, 0, combined.size)
            ?: BitmapFactory.decodeByteArray(fileBytes, offset, length)
    }

    private fun renderIfdToBitmap(fileBytes: ByteArray, order: ByteOrder, ifd: IfdDirectory): Bitmap? {
        val srcW = ifd.width
        val srcH = ifd.height
        if (srcW <= 0 || srcH <= 0) return null

        if ((ifd.compression == 6 || ifd.compression == 7) && ifd.jpegInterchangeOffset > 0L) {
            val jOff = ifd.jpegInterchangeOffset.toInt()
            val jLen = if (ifd.jpegInterchangeLength > 0L) {
                minOf(ifd.jpegInterchangeLength.toInt(), fileBytes.size - jOff)
            } else {
                fileBytes.size - jOff
            }
            if (jOff in 0 until fileBytes.size && jLen > 16) {
                decodeJpegInTiffChunk(fileBytes, jOff, jLen, ifd.jpegTables)?.let { return it }
            }
        }

        var step = 1
        while (srcW / step > 3072 || srcH / step > 3072) {
            step *= 2
        }
        val dstW = (srcW + step - 1) / step
        val dstH = (srcH + step - 1) / step
        val pixels = IntArray(dstW * dstH)

        val bps = ifd.bitsPerSample.firstOrNull()?.coerceIn(1, 16) ?: 8
        val spp = ifd.samplesPerPixel.coerceIn(1, 8)

        if (ifd.isTiled) {
            val tw = ifd.tileWidth.coerceAtLeast(1)
            val tl = ifd.tileLength.coerceAtLeast(1)
            val tilesAcross = (srcW + tw - 1) / tw
            val tileBytesPerRow = ((tw.toLong() * spp * bps + 7L) / 8L).toInt().coerceAtLeast(1)
            val expectedTileBytes = tl * tileBytesPerRow

            for (tileIdx in ifd.tileOffsets.indices) {
                val offset = ifd.tileOffsets[tileIdx].toInt()
                if (offset <= 0 || offset >= fileBytes.size) continue
                val tileCol = tileIdx % tilesAcross
                val tileRow = tileIdx / tilesAcross
                val tileOriginX = tileCol * tw
                val tileOriginY = tileRow * tl
                if (tileOriginX >= srcW || tileOriginY >= srcH) continue

                val declaredCount = if (tileIdx < ifd.tileByteCounts.size && ifd.tileByteCounts[tileIdx] > 0L) {
                    ifd.tileByteCounts[tileIdx].toInt()
                } else {
                    minOf(expectedTileBytes, fileBytes.size - offset)
                }
                val safeCompLen = minOf(declaredCount, fileBytes.size - offset).coerceAtLeast(0)
                if (safeCompLen == 0) continue

                if (ifd.compression == 6 || ifd.compression == 7) {
                    val tileBmp = decodeJpegInTiffChunk(fileBytes, offset, safeCompLen, ifd.jpegTables) ?: continue
                    val bw = tileBmp.width
                    val bh = tileBmp.height
                    val tilePixels = IntArray(bw * bh)
                    tileBmp.getPixels(tilePixels, 0, bw, 0, 0, bw, bh)
                    tileBmp.recycle()

                    val validH = minOf(tl, bh, srcH - tileOriginY)
                    val validW = minOf(tw, bw, srcW - tileOriginX)
                    for (ty in 0 until validH) {
                        val globalY = tileOriginY + ty
                        if (globalY % step != 0) continue
                        val dstY = globalY / step
                        if (dstY >= dstH) break
                        for (tx in 0 until validW) {
                            val globalX = tileOriginX + tx
                            if (globalX % step != 0) continue
                            val dstX = globalX / step
                            if (dstX < dstW) {
                                pixels[dstY * dstW + dstX] = tilePixels[ty * bw + tx]
                            }
                        }
                    }
                    continue
                }

                val tileData = when (ifd.compression) {
                    1 -> fileBytes.copyOfRange(offset, offset + safeCompLen)
                    32773 -> decodePackBits(fileBytes, offset, safeCompLen, expectedTileBytes)
                    8, 32946 -> decodeDeflate(fileBytes, offset, safeCompLen, expectedTileBytes)
                    5 -> decodeTiffLzw(fileBytes, offset, safeCompLen, expectedTileBytes)
                    else -> null
                } ?: continue

                if (ifd.predictor == 2 && bps == 8) {
                    applyHorizontalPredictor8Bit(tileData, tw, tl, spp, tileBytesPerRow)
                }

                val validRows = minOf(tl, srcH - tileOriginY)
                val validCols = minOf(tw, srcW - tileOriginX)
                for (r in 0 until validRows) {
                    val globalY = tileOriginY + r
                    if (globalY % step != 0) continue
                    val dstY = globalY / step
                    if (dstY >= dstH) break
                    val rowStart = r * tileBytesPerRow
                    if (rowStart >= tileData.size) break

                    for (c in 0 until validCols) {
                        val globalX = tileOriginX + c
                        if (globalX % step != 0) continue
                        val dstX = globalX / step
                        if (dstX < dstW) {
                            pixels[dstY * dstW + dstX] = decodePixelAt(
                                tileData, rowStart, c, bps, spp, ifd.photometric, ifd.colorMap, order
                            )
                        }
                    }
                }
            }
            return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
        }

        val bytesPerRow = ((srcW.toLong() * spp * bps + 7L) / 8L).toInt().coerceAtLeast(1)
        val rps = ifd.rowsPerStrip.coerceIn(1, srcH)

        for (stripIdx in ifd.stripOffsets.indices) {
            val offset = ifd.stripOffsets[stripIdx].toInt()
            val firstRow = stripIdx * rps
            if (firstRow >= srcH || offset <= 0 || offset >= fileBytes.size) continue
            val rowsInThisStrip = minOf(rps, srcH - firstRow)
            val expectedUncompressed = rowsInThisStrip * bytesPerRow

            val declaredCount = if (stripIdx < ifd.stripByteCounts.size && ifd.stripByteCounts[stripIdx] > 0L) {
                ifd.stripByteCounts[stripIdx].toInt()
            } else {
                minOf(expectedUncompressed, fileBytes.size - offset)
            }
            val safeCompLen = minOf(declaredCount, fileBytes.size - offset).coerceAtLeast(0)
            if (safeCompLen == 0) continue

            if (ifd.compression == 6 || ifd.compression == 7) {
                val stripBmp = decodeJpegInTiffChunk(fileBytes, offset, safeCompLen, ifd.jpegTables) ?: continue
                val bw = stripBmp.width
                val bh = stripBmp.height
                val stripPixels = IntArray(bw * bh)
                stripBmp.getPixels(stripPixels, 0, bw, 0, 0, bw, bh)
                stripBmp.recycle()

                val validRows = minOf(rowsInThisStrip, bh, srcH - firstRow)
                val validCols = minOf(srcW, bw)
                for (r in 0 until validRows) {
                    val globalY = firstRow + r
                    if (globalY % step != 0) continue
                    val dstY = globalY / step
                    if (dstY >= dstH) break
                    for (dstX in 0 until dstW) {
                        val srcX = dstX * step
                        if (srcX < validCols) {
                            pixels[dstY * dstW + dstX] = stripPixels[r * bw + srcX]
                        }
                    }
                }
                continue
            }

            val stripData = when (ifd.compression) {
                1 -> fileBytes.copyOfRange(offset, offset + safeCompLen)
                32773 -> decodePackBits(fileBytes, offset, safeCompLen, expectedUncompressed)
                8, 32946 -> decodeDeflate(fileBytes, offset, safeCompLen, expectedUncompressed)
                5 -> decodeTiffLzw(fileBytes, offset, safeCompLen, expectedUncompressed)
                else -> null
            } ?: continue

            if (ifd.predictor == 2 && bps == 8) {
                applyHorizontalPredictor8Bit(stripData, srcW, rowsInThisStrip, spp, bytesPerRow)
            }

            for (r in 0 until rowsInThisStrip) {
                val globalY = firstRow + r
                if (globalY % step != 0) continue
                val dstY = globalY / step
                if (dstY >= dstH) break
                val rowStart = r * bytesPerRow
                if (rowStart >= stripData.size) break

                for (dstX in 0 until dstW) {
                    val srcX = dstX * step
                    val color = decodePixelAt(stripData, rowStart, srcX, bps, spp, ifd.photometric, ifd.colorMap, order)
                    pixels[dstY * dstW + dstX] = color
                }
            }
        }

        return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    private fun applyHorizontalPredictor8Bit(
        data: ByteArray,
        width: Int,
        rows: Int,
        spp: Int,
        stride: Int
    ) {
        for (r in 0 until rows) {
            val rowOffset = r * stride
            for (x in 1 until width) {
                val pxBase = rowOffset + x * spp
                val prevBase = pxBase - spp
                if (pxBase + spp > data.size) break
                for (s in 0 until spp) {
                    data[pxBase + s] = (data[pxBase + s].toInt() + data[prevBase + s].toInt()).toByte()
                }
            }
        }
    }

    private fun decodePixelAt(
        strip: ByteArray,
        rowStart: Int,
        x: Int,
        bps: Int,
        spp: Int,
        photometric: Int,
        colorMap: IntArray?,
        order: ByteOrder
    ): Int {
        if (bps == 1 && spp == 1) {
            val byteIdx = rowStart + (x shr 3)
            if (byteIdx >= strip.size) return Color.WHITE
            val bit = (strip[byteIdx].toInt() ushr (7 - (x and 7))) and 1
            val isWhite = if (photometric == 0) (bit == 0) else (bit == 1)
            return if (isWhite) Color.WHITE else Color.BLACK
        }

        if (bps == 4 && spp == 1) {
            val byteIdx = rowStart + (x shr 1)
            if (byteIdx >= strip.size) return Color.BLACK
            val nibble = if ((x and 1) == 0) {
                (strip[byteIdx].toInt() ushr 4) and 0x0F
            } else {
                strip[byteIdx].toInt() and 0x0F
            }
            if (photometric == 3 && colorMap != null && colorMap.size >= 48) {
                val r = (colorMap[nibble] ushr 8) and 0xFF
                val g = (colorMap[16 + nibble] ushr 8) and 0xFF
                val b = (colorMap[32 + nibble] ushr 8) and 0xFF
                return Color.rgb(r, g, b)
            }
            val gray = if (photometric == 0) (15 - nibble) * 17 else nibble * 17
            return Color.rgb(gray, gray, gray)
        }

        val bytesPerSample = (bps / 8).coerceAtLeast(1)
        val pxOffset = rowStart + x * spp * bytesPerSample
        if (pxOffset + spp * bytesPerSample > strip.size) return Color.TRANSPARENT

        fun sample8(channel: Int): Int {
            val pos = pxOffset + channel * bytesPerSample
            return if (bytesPerSample == 2) {
                val hi = if (order == ByteOrder.BIG_ENDIAN) strip[pos].toInt() else strip[pos + 1].toInt()
                hi and 0xFF
            } else {
                strip[pos].toInt() and 0xFF
            }
        }

        return when (photometric) {
            0 -> {
                val v = 255 - sample8(0)
                val a = if (spp >= 2) sample8(1) else 255
                Color.argb(a, v, v, v)
            }
            1 -> {
                val v = sample8(0)
                val a = if (spp >= 2) sample8(1) else 255
                Color.argb(a, v, v, v)
            }
            3 -> {
                val idx = sample8(0)
                if (colorMap != null && colorMap.size >= 768 && idx in 0..255) {
                    val r = (colorMap[idx] ushr 8) and 0xFF
                    val g = (colorMap[256 + idx] ushr 8) and 0xFF
                    val b = (colorMap[512 + idx] ushr 8) and 0xFF
                    Color.rgb(r, g, b)
                } else {
                    Color.rgb(idx, idx, idx)
                }
            }
            5 -> {
                val c = sample8(0)
                val m = if (spp > 1) sample8(1) else 0
                val y = if (spp > 2) sample8(2) else 0
                val k = if (spp > 3) sample8(3) else 0
                val r = ((255 - c) * (255 - k)) / 255
                val g = ((255 - m) * (255 - k)) / 255
                val b = ((255 - y) * (255 - k)) / 255
                Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
            6 -> {
                val yVal = sample8(0).toFloat()
                val cb = (if (spp > 1) sample8(1) else 128) - 128f
                val cr = (if (spp > 2) sample8(2) else 128) - 128f
                val r = (yVal + 1.402f * cr).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * cb).toInt().coerceIn(0, 255)
                Color.rgb(r, g, b)
            }
            else -> {
                val r = sample8(0)
                val g = if (spp > 1) sample8(1) else r
                val b = if (spp > 2) sample8(2) else r
                val a = if (spp > 3) sample8(3) else 255
                Color.argb(a, r, g, b)
            }
        }
    }

    private fun decodePackBits(src: ByteArray, offset: Int, len: Int, expectedOut: Int): ByteArray {
        val out = ByteArrayOutputStream(expectedOut.coerceAtLeast(1024))
        var i = offset
        val end = offset + len
        while (i < end && out.size() < expectedOut + 4096) {
            val n = src[i++].toInt()
            if (n in 0..127) {
                val count = minOf(n + 1, end - i)
                out.write(src, i, count)
                i += count
            } else if (n in -127..-1) {
                if (i >= end) break
                val b = src[i++].toInt()
                val count = -n + 1
                repeat(count) { out.write(b) }
            }
        }
        return out.toByteArray()
    }

    private fun decodeDeflate(src: ByteArray, offset: Int, len: Int, expectedOut: Int): ByteArray? {
        for (nowrap in booleanArrayOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(src, offset, len)
                val bos = ByteArrayOutputStream(expectedOut.coerceAtLeast(1024))
                val buf = ByteArray(8192)
                var total = 0
                val maxCap = maxOf(expectedOut * 2, 16 * 1024 * 1024)
                while (!inflater.finished() && !inflater.needsDictionary() && total < maxCap) {
                    val r = inflater.inflate(buf)
                    if (r <= 0) break
                    bos.write(buf, 0, r)
                    total += r
                }
                if (bos.size() > 0) return bos.toByteArray()
            } catch (_: Throwable) {
            } finally {
                try { inflater.end() } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun decodeTiffLzw(src: ByteArray, offset: Int, len: Int, expectedOut: Int): ByteArray? {
        if (len <= 0) return null
        val out = ByteArrayOutputStream(expectedOut.coerceAtLeast(1024))
        val maxLimit = maxOf(expectedOut * 2, 16 * 1024 * 1024)

        val table = arrayOfNulls<ByteArray>(4096)
        for (c in 0 until 256) {
            table[c] = byteArrayOf(c.toByte())
        }
        var nextCode = 258
        var codeSize = 9
        var bitPos = 0
        val totalBits = len * 8

        fun readNextCode(): Int {
            if (bitPos + codeSize > totalBits) return 257
            var code = 0
            for (b in 0 until codeSize) {
                val curBit = bitPos + b
                val byteVal = src[offset + (curBit shr 3)].toInt() and 0xFF
                val bitVal = (byteVal ushr (7 - (curBit and 7))) and 1
                code = (code shl 1) or bitVal
            }
            bitPos += codeSize
            return code
        }

        var oldCode = -1
        while (out.size() < maxLimit) {
            val code = readNextCode()
            if (code == 257) break
            if (code == 256) {
                for (i in 258 until 4096) table[i] = null
                nextCode = 258
                codeSize = 9
                oldCode = readNextCode()
                if (oldCode == 257 || oldCode !in 0..255) break
                out.write(table[oldCode]!!)
                continue
            }

            val entry: ByteArray = if (code < nextCode && table[code] != null) {
                table[code]!!
            } else if (code == nextCode && oldCode in 0 until nextCode && table[oldCode] != null) {
                val prev = table[oldCode]!!
                val combined = prev.copyOf(prev.size + 1)
                combined[prev.size] = prev[0]
                combined
            } else {
                break
            }

            out.write(entry)

            if (oldCode in 0 until nextCode && table[oldCode] != null && nextCode < 4096) {
                val prev = table[oldCode]!!
                val newEntry = prev.copyOf(prev.size + 1)
                newEntry[prev.size] = entry[0]
                table[nextCode++] = newEntry

                when (nextCode) {
                    511 -> codeSize = 10
                    1023 -> codeSize = 11
                    2047 -> codeSize = 12
                }
            }
            oldCode = code
        }

        return if (out.size() > 0) out.toByteArray() else null
    }
}

/**
 * Clean-Room parser and renderer for DjVu documents (`AT&TFORM:DJVU` single-page and `AT&TFORM:DJVM` multi-page).
 * All AT&T / LizardTech DjVu patents have expired; this implementation is built from scratch on the open
 * IFF85 / DjVu v3 specification without any GPLv2+ DjVuLibre code.
 */
class CleanRoomDjvuSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var activeRenderedPageIdx = 0
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    data class DjvuPageModel(
        val index: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val rotationDeg: Int,
        val textLayer: String,
        val bgJpegBytes: ByteArray?,
        val bg44Chunks: List<ByteArray>,
        val sjbzBytes: ByteArray?
    )

    private val pages = mutableListOf<DjvuPageModel>()

    init {
        parseDjvuContainer()
    }

    private fun parseDjvuContainer() {
        try {
            val fileSize = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (fileSize < 16) {
                lastErrorMsg = "DjVu file is empty or truncated."
                return
            }
            val buf = ByteBuffer.allocateDirect(fileSize)
            val read = source.readAt(0L, buf)
            if (read < 16) {
                lastErrorMsg = "Unable to read DjVu container header."
                return
            }
            val bytes = ByteArray(read)
            buf.flip()
            buf.get(bytes)

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var pos = 0
            if (read >= 12 &&
                bytes[0] == 'A'.code.toByte() &&
                bytes[1] == 'T'.code.toByte() &&
                bytes[2] == '&'.code.toByte() &&
                bytes[3] == 'T'.code.toByte()
            ) {
                pos = 4
            }
            if (pos + 12 > bytes.size) {
                lastErrorMsg = "Invalid DjVu IFF85 header."
                return
            }
            val formTag = String(bytes, pos, 4, StandardCharsets.US_ASCII)
            val formSize = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val formType = String(bytes, pos + 8, 4, StandardCharsets.US_ASCII)
            if (formTag != "FORM" || formType !in setOf("DJVU", "DJVM", "PM44", "BM44")) {
                lastErrorMsg = "Unsupported DjVu container form ($formTag:$formType)."
                return
            }

            val formEnd = minOf(bytes.size.toLong(), pos + 8L + formSize).toInt()
            if (formType == "DJVM") {
                var childPos = pos + 12
                var dirmOffsets = LongArray(0)
                while (childPos + 8 <= formEnd && pages.size < 2000) {
                    val chunkId = String(bytes, childPos, 4, StandardCharsets.US_ASCII)
                    val chunkLen = (bb.getInt(childPos + 4).toLong() and 0xFFFFFFFFL).toInt()
                    val dataStart = childPos + 8
                    val dataEnd = (dataStart.toLong() + chunkLen).coerceAtMost(formEnd.toLong()).toInt()
                    if (dataEnd < dataStart) break

                    if (chunkId == "DIRM" && dataEnd - dataStart >= 3) {
                        dirmOffsets = parseDirmChunkOffsets(bytes, dataStart, dataEnd)
                    } else if (chunkId == "FORM" && dataEnd - dataStart >= 4) {
                        val subType = String(bytes, dataStart, 4, StandardCharsets.US_ASCII)
                        if (subType in setOf("DJVU", "PM44", "BM44")) {
                            parseSingleDjvuForm(pages.size + 1, bytes, bb, dataStart + 4, dataEnd)?.let {
                                pages.add(it)
                            }
                        }
                    }
                    val paddedLen = (chunkLen + 1) and -2
                    val nextPos = dataStart + paddedLen
                    if (nextPos <= childPos) break
                    childPos = nextPos
                }

                if (pages.isEmpty() && dirmOffsets.isNotEmpty()) {
                    for (off in dirmOffsets) {
                        val pOff = off.toInt()
                        if (pOff in 12..(bytes.size - 12)) {
                            val cId = String(bytes, pOff, 4, StandardCharsets.US_ASCII)
                            val cLen = (bb.getInt(pOff + 4).toLong() and 0xFFFFFFFFL).toInt()
                            if (cId == "FORM" && pOff + 12 <= bytes.size) {
                                val subT = String(bytes, pOff + 8, 4, StandardCharsets.US_ASCII)
                                val subEnd = minOf(bytes.size, pOff + 8 + cLen)
                                if (subT in setOf("DJVU", "PM44", "BM44")) {
                                    parseSingleDjvuForm(pages.size + 1, bytes, bb, pOff + 12, subEnd)?.let {
                                        pages.add(it)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                parseSingleDjvuForm(1, bytes, bb, pos + 12, formEnd)?.let {
                    pages.add(it)
                }
            }

            if (pages.isEmpty()) {
                lastErrorMsg = "DjVu container contains no readable pages."
            }
        } catch (e: Exception) {
            lastErrorMsg = "DjVu parse error: ${e.localizedMessage}"
        }
    }

    private fun parseDirmChunkOffsets(bytes: ByteArray, start: Int, end: Int): LongArray {
        val flags = bytes[start].toInt() and 0xFF
        val isBundled = (flags and 0x80) != 0
        val numFiles = ((bytes[start + 1].toInt() and 0xFF) shl 8) or (bytes[start + 2].toInt() and 0xFF)
        if (!isBundled || numFiles <= 0 || numFiles > 5000) return LongArray(0)
        val offsetsStart = start + 3
        if (offsetsStart + numFiles * 4 > end) return LongArray(0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return LongArray(numFiles) { i ->
            bb.getInt(offsetsStart + i * 4).toLong() and 0xFFFFFFFFL
        }
    }

    private fun parseSingleDjvuForm(
        pageNumber: Int,
        bytes: ByteArray,
        bb: ByteBuffer,
        start: Int,
        end: Int
    ): DjvuPageModel? {
        var width = 2480
        var height = 3508
        var dpi = 300
        var rotationDeg = 0
        val textSb = StringBuilder()
        var bgJpeg: ByteArray? = null
        val bg44List = ArrayList<ByteArray>()
        var sjbzData: ByteArray? = null

        var pos = start
        while (pos + 8 <= end) {
            val chunkId = String(bytes, pos, 4, StandardCharsets.US_ASCII)
            val chunkLen = (bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL).toInt()
            val dataStart = pos + 8
            val dataEnd = (dataStart.toLong() + chunkLen).coerceAtMost(end.toLong()).toInt()
            if (dataEnd < dataStart) break

            when (chunkId) {
                "INFO" -> {
                    if (dataEnd - dataStart >= 5) {
                        width = ((bytes[dataStart].toInt() and 0xFF) shl 8) or (bytes[dataStart + 1].toInt() and 0xFF)
                        height = ((bytes[dataStart + 2].toInt() and 0xFF) shl 8) or (bytes[dataStart + 3].toInt() and 0xFF)
                        if (dataEnd - dataStart >= 8) {
                            dpi = ((bytes[dataStart + 6].toInt() and 0xFF)) or ((bytes[dataStart + 7].toInt() and 0xFF) shl 8)
                        }
                        if (dataEnd - dataStart >= 10) {
                            rotationDeg = when (bytes[dataStart + 9].toInt() and 0x07) {
                                6 -> 270
                                2 -> 180
                                5 -> 90
                                else -> 0
                            }
                        }
                    }
                }
                "TXTa" -> {
                    if (dataEnd - dataStart > 3) {
                        val txtLen = ((bytes[dataStart].toInt() and 0xFF) shl 16) or
                                ((bytes[dataStart + 1].toInt() and 0xFF) shl 8) or
                                (bytes[dataStart + 2].toInt() and 0xFF)
                        val safeTxtLen = minOf(txtLen, dataEnd - (dataStart + 3)).coerceAtLeast(0)
                        if (safeTxtLen > 0) {
                            val str = String(bytes, dataStart + 3, safeTxtLen, StandardCharsets.UTF_8).trim()
                            if (str.isNotEmpty()) {
                                if (textSb.isNotEmpty()) textSb.append('\n')
                                textSb.append(str)
                            }
                        }
                    }
                }
                "TXTz", "ANTz" -> {
                    if (dataEnd > dataStart) {
                        val compSlice = bytes.copyOfRange(dataStart, dataEnd)
                        val decodedBzz = decodeBzzOrExtractText(compSlice)
                        if (decodedBzz.isNotBlank()) {
                            if (textSb.isNotEmpty()) textSb.append('\n')
                            textSb.append(decodedBzz)
                        }
                    }
                }
                "ANTa" -> {
                    if (dataEnd > dataStart) {
                        val antStr = String(bytes, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8).trim()
                        if (antStr.isNotEmpty() && textSb.isEmpty()) {
                            textSb.append(antStr)
                        }
                    }
                }
                "BGjp", "FGjp" -> {
                    if (bgJpeg == null && dataEnd > dataStart) {
                        bgJpeg = bytes.copyOfRange(dataStart, dataEnd)
                    }
                }
                "BG44", "PM44", "BM44" -> {
                    if (dataEnd > dataStart && bg44List.size < 16) {
                        bg44List.add(bytes.copyOfRange(dataStart, dataEnd))
                    }
                }
                "Sjbz" -> {
                    if (sjbzData == null && dataEnd > dataStart) {
                        sjbzData = bytes.copyOfRange(dataStart, dataEnd)
                    }
                }
            }

            val paddedLen = (chunkLen + 1) and -2
            val nextPos = dataStart + paddedLen
            if (nextPos <= pos) break
            pos = nextPos
        }

        return DjvuPageModel(
            index = pageNumber,
            width = width.coerceIn(100, 12000),
            height = height.coerceIn(100, 16000),
            dpi = dpi.coerceIn(72, 1200),
            rotationDeg = rotationDeg,
            textLayer = textSb.toString().trim(),
            bgJpegBytes = bgJpeg,
            bg44Chunks = bg44List,
            sjbzBytes = sjbzData
        )
    }

    private fun decodeBzzOrExtractText(data: ByteArray): String {
        if (data.size > 3) {
            val maybeLen = ((data[0].toInt() and 0xFF) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)
            if (maybeLen in 1..(data.size - 3)) {
                val candidate = String(data, 3, maybeLen, StandardCharsets.UTF_8)
                if (candidate.count { it.isLetterOrDigit() || it.isWhitespace() } > candidate.length * 0.7) {
                    return candidate.trim()
                }
            }
        }
        val sb = StringBuilder()
        val utf8Run = ByteArrayOutputStream(256)
        fun flushRun() {
            if (utf8Run.size() >= 4) {
                val decoded = try {
                    String(utf8Run.toByteArray(), StandardCharsets.UTF_8).trim()
                } catch (_: Throwable) {
                    ""
                }
                if (decoded.count { it.isLetterOrDigit() } >= 2) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(decoded)
                }
            }
            utf8Run.reset()
        }
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == 10 || v == 13 || v in 32..126 || v >= 0x80) {
                utf8Run.write(v)
            } else {
                flushRun()
            }
        }
        flushRun()
        return sb.toString().trim()
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float {
        val p = pages.getOrNull(activeRenderedPageIdx) ?: pages.firstOrNull() ?: return 1240f
        return p.width.toFloat()
    }

    override fun getDocumentHeight(): Float {
        val p = pages.getOrNull(activeRenderedPageIdx) ?: pages.firstOrNull() ?: return 1754f
        return p.height.toFloat()
    }

    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return pages.joinToString("\n\n") { it.textLayer }
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrNull(cleanIdx)?.textLayer ?: ""
    }

    override fun getPageAspectRatio(pageIndex: Int): Float {
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val p = pages.getOrNull(cleanIdx) ?: return 0.707f
        return p.width.toFloat() / p.height.toFloat().coerceAtLeast(1f)
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        activeRenderedPageIdx = cleanIdx

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) Color.rgb(252, 250, 244) else Color.rgb(0x16, 0x17, 0x1E))

        val page = pages.getOrNull(cleanIdx) ?: return

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 6f)
        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())

        var drewBackgroundBitmap = false
        if (page.bgJpegBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(page.bgJpegBytes, 0, page.bgJpegBytes.size)
            if (bmp != null) {
                val dstW = bitmap.width * zoom
                val dstH = bitmap.height * zoom
                val left = (bitmap.width - dstW) / 2f
                val top = (bitmap.height - dstH) / 2f
                canvas.drawBitmap(bmp, null, RectF(left, top, left + dstW, top + dstH), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
                drewBackgroundBitmap = true
            }
        }

        if (!drewBackgroundBitmap && page.bg44Chunks.isNotEmpty()) {
            val firstChunk = page.bg44Chunks[0]
            if (firstChunk.size >= 9 && firstChunk[0].toInt() == 0) {
                val iwW = ((firstChunk[4].toInt() and 0xFF) shl 8) or (firstChunk[5].toInt() and 0xFF)
                val iwH = ((firstChunk[6].toInt() and 0xFF) shl 8) or (firstChunk[7].toInt() and 0xFF)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isLight) Color.rgb(248, 246, 238) else Color.rgb(0x1E, 0x20, 0x2B)
                    style = Paint.Style.FILL
                }
                val pageAspect = (if (iwW > 0 && iwH > 0) iwW.toFloat() / iwH else page.width.toFloat() / page.height).coerceIn(0.4f, 2.5f)
                val cardH = (bitmap.height - 40f) * zoom
                val cardW = cardH * pageAspect
                val left = (bitmap.width - cardW) / 2f
                val top = 20f
                canvas.drawRect(left, top, left + cardW, top + cardH, bgPaint)
            }
        }

        val pad = 44f
        val availW = ((bitmap.width - pad * 2f) * zoom).toInt().coerceAtLeast(120)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight || drewBackgroundBitmap) Color.rgb(28, 30, 36) else Color.rgb(232, 235, 244)
            textSize = 30f * zoom
            typeface = Typeface.SERIF
        }
        val contentText = page.textLayer.ifBlank {
            "DjVu Page ${page.index} (${page.width}×${page.height} px @ ${page.dpi} DPI)"
        }
        val rich = SpannableStringBuilder(contentText)
        val layout = StaticLayout.Builder.obtain(rich, 0, rich.length, textPaint, availW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.22f)
            .build()
        canvas.translate(pad, pad)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) {
            if (pages[i].textLayer.contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until start) {
            if (pages[i].textLayer.contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Clean-room decompiler for Android Binary XML (`AXML`, `0x00080003`) and `resources.arsc` (`0x000C0002`)
 * based on the open AOSP `ResourceTypes.h` specification.
 */
internal object AndroidBinaryXmlDecompiler {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200

    fun isBinaryXmlOrArsc(sample: ByteArray, len: Int): Boolean {
        if (len < 8) return false
        val bb = ByteBuffer.wrap(sample, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val type = bb.getShort(0).toInt() and 0xFFFF
        val headerSize = bb.getShort(2).toInt() and 0xFFFF
        val totalSize = bb.getInt(4)
        if (totalSize < 8) return false
        return (type == RES_XML_TYPE && headerSize == 8) || (type == RES_TABLE_TYPE && headerSize == 12)
    }

    fun decompile(bytes: ByteArray): String? {
        if (!isBinaryXmlOrArsc(bytes, bytes.size)) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rootType = bb.getShort(0).toInt() and 0xFFFF
        return if (rootType == RES_XML_TYPE) {
            decompileBinaryXml(bb, bytes)
        } else {
            decompileArscSummary(bb, bytes)
        }
    }

    private fun parseStringPool(bb: ByteBuffer, rawBytes: ByteArray, chunkPos: Int, chunkSize: Int): List<String> {
        if (chunkSize < 28 || chunkPos + chunkSize > rawBytes.size) return emptyList()
        val stringCount = bb.getInt(chunkPos + 8).coerceIn(0, 200_000)
        val flags = bb.getInt(chunkPos + 16)
        val stringsStart = chunkPos + bb.getInt(chunkPos + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0
        val offsetsBase = chunkPos + 28
        if (offsetsBase + stringCount * 4 > chunkPos + chunkSize) return emptyList()

        val list = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strOffset = stringsStart + bb.getInt(offsetsBase + i * 4)
            if (strOffset < stringsStart || strOffset >= chunkPos + chunkSize) {
                list.add("")
                continue
            }
            try {
                if (isUtf8) {
                    var p = strOffset
                    val u16Len = rawBytes[p++].toInt() and 0xFF
                    if ((u16Len and 0x80) != 0) p++
                    var u8Len = rawBytes[p++].toInt() and 0xFF
                    if ((u8Len and 0x80) != 0) {
                        u8Len = ((u8Len and 0x7F) shl 8) or (rawBytes[p++].toInt() and 0xFF)
                    }
                    val safeLen = minOf(u8Len, (chunkPos + chunkSize) - p).coerceAtLeast(0)
                    list.add(String(rawBytes, p, safeLen, StandardCharsets.UTF_8))
                } else {
                    var p = strOffset
                    var u16Len = bb.getShort(p).toInt() and 0xFFFF
                    p += 2
                    if ((u16Len and 0x8000) != 0) {
                        u16Len = ((u16Len and 0x7FFF) shl 16) or (bb.getShort(p).toInt() and 0xFFFF)
                        p += 2
                    }
                    val byteLen = minOf(u16Len * 2, (chunkPos + chunkSize) - p).coerceAtLeast(0)
                    list.add(String(rawBytes, p, byteLen, StandardCharsets.UTF_16LE))
                }
            } catch (_: Throwable) {
                list.add("")
            }
        }
        return list
    }

    private fun decompileBinaryXml(bb: ByteBuffer, bytes: ByteArray): String {
        val headerSize = (bb.getShort(2).toInt() and 0xFFFF).coerceAtLeast(8)
        var pos = headerSize
        var stringPool: List<String> = emptyList()
        val nsUriToPrefix = LinkedHashMap<String, String>()
        val pendingNsDeclarations = ArrayList<Pair<String, String>>()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var indent = 0

        fun getStr(idx: Int): String = if (idx in stringPool.indices) stringPool[idx] else ""

        while (pos + 8 <= bytes.size) {
            val chunkType = bb.getShort(pos).toInt() and 0xFFFF
            val chunkHeaderSize = bb.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = bb.getInt(pos + 4)
            if (chunkSize < 8 || pos + chunkSize > bytes.size) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    stringPool = parseStringPool(bb, bytes, pos, chunkSize)
                }
                RES_XML_START_NAMESPACE_TYPE -> {
                    if (chunkSize >= 24) {
                        val prefix = getStr(bb.getInt(pos + 16))
                        val uri = getStr(bb.getInt(pos + 20))
                        if (uri.isNotEmpty()) {
                            nsUriToPrefix[uri] = prefix
                            pendingNsDeclarations.add(prefix to uri)
                        }
                    }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    if (pos + chunkHeaderSize + 20 <= pos + chunkSize) {
                        val extBase = pos + chunkHeaderSize
                        val nsUri = getStr(bb.getInt(extBase))
                        val elemName = getStr(bb.getInt(extBase + 4))
                        val attrStart = bb.getShort(extBase + 8).toInt() and 0xFFFF
                        val attrSize = (bb.getShort(extBase + 10).toInt() and 0xFFFF).coerceAtLeast(20)
                        val attrCount = bb.getShort(extBase + 12).toInt() and 0xFFFF

                        val nsPrefix = nsUriToPrefix[nsUri]
                        val qName = if (!nsPrefix.isNullOrEmpty()) "$nsPrefix:$elemName" else elemName

                        repeat(indent) { sb.append("    ") }
                        sb.append('<').append(qName)

                        for ((pfx, uri) in pendingNsDeclarations) {
                            if (pfx.isEmpty()) {
                                sb.append(" xmlns=\"").append(uri).append('"')
                            } else {
                                sb.append(" xmlns:").append(pfx).append("=\"").append(uri).append('"')
                            }
                        }
                        pendingNsDeclarations.clear()

                        val attrListStart = extBase + attrStart
                        for (a in 0 until attrCount) {
                            val aPos = attrListStart + a * attrSize
                            if (aPos + 20 > pos + chunkSize) break
                            val aNs = getStr(bb.getInt(aPos))
                            val aName = getStr(bb.getInt(aPos + 4))
                            val aRawValIdx = bb.getInt(aPos + 8)
                            val aType = bb.get(aPos + 15).toInt() and 0xFF
                            val aData = bb.getInt(aPos + 16)

                            val aPrefix = nsUriToPrefix[aNs]
                            val aQName = if (!aPrefix.isNullOrEmpty()) "$aPrefix:$aName" else aName
                            val formattedVal = formatAttributeValue(aType, aData, aRawValIdx, ::getStr)
                            sb.append(' ').append(aQName).append("=\"").append(escapeXmlAttr(formattedVal)).append('"')
                        }
                        sb.append(">\n")
                        indent++
                    }
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    if (chunkSize >= 24) {
                        indent = (indent - 1).coerceAtLeast(0)
                        val extBase = pos + chunkHeaderSize
                        val nsUri = getStr(bb.getInt(extBase))
                        val elemName = getStr(bb.getInt(extBase + 4))
                        val nsPrefix = nsUriToPrefix[nsUri]
                        val qName = if (!nsPrefix.isNullOrEmpty()) "$nsPrefix:$elemName" else elemName
                        repeat(indent) { sb.append("    ") }
                        sb.append("</").append(qName).append(">\n")
                    }
                }
                RES_XML_CDATA_TYPE -> {
                    if (chunkSize >= 20) {
                        val txt = getStr(bb.getInt(pos + 16)).trim()
                        if (txt.isNotEmpty()) {
                            repeat(indent) { sb.append("    ") }
                            sb.append(txt).append('\n')
                        }
                    }
                }
            }
            pos += chunkSize
        }
        return sb.toString().trim()
    }

    private fun decompileArscSummary(bb: ByteBuffer, bytes: ByteArray): String {
        val sb = StringBuilder()
        sb.append("=== Android Resource Table (resources.arsc) ===\n\n")
        val headerSize = (bb.getShort(2).toInt() and 0xFFFF).coerceAtLeast(12)
        var pos = headerSize
        while (pos + 8 <= bytes.size) {
            val chunkType = bb.getShort(pos).toInt() and 0xFFFF
            val chunkSize = bb.getInt(pos + 4)
            if (chunkSize < 8 || pos + chunkSize > bytes.size) break
            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    val pool = parseStringPool(bb, bytes, pos, chunkSize)
                    sb.append("Global Strings (${pool.size}):\n")
                    pool.asSequence().filter { it.isNotBlank() }.take(1500).forEachIndexed { idx, s ->
                        sb.append("  [").append(idx).append("] ").append(s).append('\n')
                    }
                    sb.append('\n')
                }
                RES_TABLE_PACKAGE_TYPE -> {
                    if (pos + 268 <= bytes.size) {
                        val pkgId = bb.getInt(pos + 8)
                        val nameBytes = ByteArray(256)
                        System.arraycopy(bytes, pos + 12, nameBytes, 0, 256)
                        val pkgName = String(nameBytes, StandardCharsets.UTF_16LE).substringBefore('\u0000')
                        sb.append("Package ID: 0x").append(Integer.toHexString(pkgId))
                            .append(" | Name: ").append(pkgName).append('\n')
                    }
                }
            }
            pos += chunkSize
        }
        return sb.toString().trim()
    }

    private fun formatAttributeValue(type: Int, data: Int, rawValIdx: Int, getStr: (Int) -> String): String {
        if (rawValIdx >= 0) {
            val raw = getStr(rawValIdx)
            if (raw.isNotEmpty()) return raw
        }
        return when (type) {
            0x00 -> "null"
            0x01 -> "@0x${Integer.toHexString(data).uppercase(Locale.ROOT)}"
            0x02 -> "?0x${Integer.toHexString(data).uppercase(Locale.ROOT)}"
            0x03 -> getStr(data)
            0x04 -> Float.fromBits(data).toString()
            0x05 -> {
                val unit = when (data and 0x0F) {
                    0 -> "px"; 1 -> "dp"; 2 -> "sp"; 3 -> "pt"; 4 -> "in"; 5 -> "mm"; else -> "px"
                }
                val mantissa = (data shr 8).toFloat()
                "${mantissa.toInt()}$unit"
            }
            0x10 -> data.toString()
            0x11 -> "0x${Integer.toHexString(data)}"
            0x12 -> if (data != 0) "true" else "false"
            in 0x1C..0x1F -> String.format(Locale.US, "#%08X", data)
            else -> "0x${Integer.toHexString(data)}"
        }
    }

    private fun escapeXmlAttr(s: String): String {
        return s.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

/**
 * Clean-Room OpenType / TrueType / TTC / WOFF Font Specimen & Glyph Table Inspector (ISO/IEC 14496-22).
 */
class CleanRoomFontSession(
    private val pfd: ParcelFileDescriptor,
    private val extension: String = "ttf"
) : DocumentSession {

    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    data class SfntTableEntry(
        val tag: String,
        val checkSum: Long,
        val offset: Int,
        val length: Int
    )

    private var customTypeface: Typeface? = null
    private var tempFontFile: File? = null

    private var fontFamily = ""
    private var fontSubfamily = "Regular"
    private var fullFontName = ""
    private var postScriptName = ""
    private var fontVersion = ""
    private var copyrightNotice = ""
    private var designerName = ""
    private var unitsPerEm = 1000
    private var numGlyphs = 0
    private var sfntTypeLabel = "OpenType / TrueType"
    private val tableEntries = mutableListOf<SfntTableEntry>()
    private val supportedCodepoints = ArrayList<Int>()
    private val showcaseSampleLines = ArrayList<Pair<String, Float>>()
    private val activePangramLines = ArrayList<String>()
    private val pageSummaries = ArrayList<String>()

    companion object {
        private const val GLYPHS_PER_PAGE = 64 // 8x8 grid per glyph page
        private const val VIRTUAL_DOC_WIDTH = 1280f
        private const val VIRTUAL_DOC_HEIGHT = 1810f
    }

    init {
        parseFontAndBuildTypeface()
    }

    private fun parseFontAndBuildTypeface() {
        try {
            val fileSize = minOf(source.size, 48L * 1024L * 1024L).toInt()
            if (fileSize < 12) {
                lastErrorMsg = "Font file is empty or truncated (< 12 bytes)."
                return
            }

            val buf = ByteBuffer.allocateDirect(fileSize)
            val got = source.readAt(0L, buf)
            if (got < 12) {
                lastErrorMsg = "Unable to read font header."
                return
            }
            buf.flip()
            var bytes = ByteArray(got)
            buf.get(bytes)

            val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            var sfntOffset = 0

            when (magic) {
                0x774F4646 -> {
                    sfntTypeLabel = "WOFF 1.0 Web Font"
                    val unpacked = unpackWoff1ToSfnt(bytes)
                    if (unpacked != null) {
                        bytes = unpacked
                    }
                }
                0x774F4632 -> {
                    sfntTypeLabel = "WOFF 2.0 Web Font"
                }
                0x74746366 -> {
                    sfntTypeLabel = "TrueType Collection (TTC)"
                    if (bytes.size >= 16) {
                        val firstOffset = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(12)
                        if (firstOffset in 12 until bytes.size) {
                            sfntOffset = firstOffset
                        }
                    }
                }
                0x4F54544F -> {
                    sfntTypeLabel = "OpenType CFF (OTF)"
                }
                0x00010000, 0x74727565 -> {
                    sfntTypeLabel = "TrueType (TTF)"
                }
            }

            customTypeface = try {
                Typeface.Builder(pfd.fileDescriptor).build()
            } catch (_: Throwable) {
                null
            }

            if (customTypeface == null) {
                try {
                    val cacheDir = System.getProperty("omnireader.cache.dir")?.let { File(it) }
                        ?: System.getProperty("java.io.tmpdir")?.let { File(it) }
                    val tmp = File.createTempFile("omni_font_", ".ttf", cacheDir)
                    FileOutputStream(tmp).use { it.write(bytes) }
                    tempFontFile = tmp
                    customTypeface = Typeface.createFromFile(tmp)
                    try { tmp.delete() } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }

            parseSfntTables(bytes, sfntOffset)

            if (supportedCodepoints.isEmpty()) {
                populateDefaultPreviewCodepoints()
            }

            buildAdaptiveShowcaseAndPangrams()
            buildSearchablePageSummaries()
        } catch (e: Exception) {
            lastErrorMsg = "Font parse error: ${e.localizedMessage}"
        }
    }

    private fun unpackWoff1ToSfnt(woff: ByteArray): ByteArray? {
        if (woff.size < 44) return null
        return try {
            val bb = ByteBuffer.wrap(woff).order(ByteOrder.BIG_ENDIAN)
            val flavor = bb.getInt(4)
            val numTables = bb.getShort(12).toInt() and 0xFFFF
            if (numTables !in 1..128 || 44 + numTables * 20 > woff.size) return null

            var searchRange = 16
            var entrySelector = 0
            while (searchRange * 2 <= numTables * 16) {
                searchRange *= 2
                entrySelector++
            }
            val rangeShift = numTables * 16 - searchRange

            val sfntHeaderSize = 12 + numTables * 16
            val unpackedTables = ArrayList<ByteArray>(numTables)
            val tags = IntArray(numTables)
            val origLengths = IntArray(numTables)
            val checksums = IntArray(numTables)

            var totalPayloadSize = 0
            for (i in 0 until numTables) {
                val base = 44 + i * 20
                tags[i] = bb.getInt(base)
                val offset = bb.getInt(base + 4)
                val compLen = bb.getInt(base + 8)
                val origLen = bb.getInt(base + 12)
                checksums[i] = bb.getInt(base + 16)

                if (offset < 44 || compLen < 0 || origLen < 0 || origLen > 32 * 1024 * 1024 ||
                    offset.toLong() + compLen.toLong() > woff.size.toLong()
                ) {
                    return null
                }
                val tableData: ByteArray = if (compLen == origLen) {
                    woff.copyOfRange(offset, offset + compLen)
                } else {
                    val inf = Inflater(false)
                    try {
                        inf.setInput(woff, offset, compLen)
                        val out = ByteArray(origLen)
                        var pos = 0
                        while (!inf.finished() && pos < origLen) {
                            val r = inf.inflate(out, pos, origLen - pos)
                            if (r <= 0) break
                            pos += r
                        }
                        out
                    } finally {
                        inf.end()
                    }
                }
                unpackedTables.add(tableData)
                origLengths[i] = tableData.size
                totalPayloadSize += (tableData.size + 3) and -4
            }

            val sfnt = ByteArray(sfntHeaderSize + totalPayloadSize)
            val outBb = ByteBuffer.wrap(sfnt).order(ByteOrder.BIG_ENDIAN)
            outBb.putInt(0, flavor)
            outBb.putShort(4, numTables.toShort())
            outBb.putShort(6, searchRange.toShort())
            outBb.putShort(8, entrySelector.toShort())
            outBb.putShort(10, rangeShift.toShort())

            var curDataOffset = sfntHeaderSize
            for (i in 0 until numTables) {
                val dirBase = 12 + i * 16
                outBb.putInt(dirBase, tags[i])
                outBb.putInt(dirBase + 4, checksums[i])
                outBb.putInt(dirBase + 8, curDataOffset)
                outBb.putInt(dirBase + 12, origLengths[i])

                val tBytes = unpackedTables[i]
                System.arraycopy(tBytes, 0, sfnt, curDataOffset, tBytes.size)
                curDataOffset += (tBytes.size + 3) and -4
            }
            sfnt
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseSfntTables(bytes: ByteArray, sfntOffset: Int) {
        if (sfntOffset + 12 > bytes.size) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numTables = (bb.getShort(sfntOffset + 4).toInt() and 0xFFFF).coerceIn(0, 128)
        val dirStart = sfntOffset + 12

        for (i in 0 until numTables) {
            val entryPos = dirStart + i * 16
            if (entryPos + 16 > bytes.size) break
            val tagStr = String(bytes, entryPos, 4, StandardCharsets.US_ASCII)
            val checkSum = bb.getInt(entryPos + 4).toLong() and 0xFFFFFFFFL
            val tOffset = bb.getInt(entryPos + 8)
            val tLen = bb.getInt(entryPos + 12)
            if (tOffset > 0 && tLen >= 0) {
                tableEntries.add(SfntTableEntry(tagStr, checkSum, tOffset, tLen))
            }
        }

        tableEntries.firstOrNull { it.tag == "head" }?.let { head ->
            if (head.offset + 20 <= bytes.size && head.length >= 20) {
                unitsPerEm = (bb.getShort(head.offset + 18).toInt() and 0xFFFF).coerceIn(16, 16384)
            }
        }

        tableEntries.firstOrNull { it.tag == "maxp" }?.let { maxp ->
            if (maxp.offset + 6 <= bytes.size && maxp.length >= 6) {
                numGlyphs = (bb.getShort(maxp.offset + 4).toInt() and 0xFFFF).coerceAtLeast(0)
            }
        }

        tableEntries.firstOrNull { it.tag == "name" }?.let { nameTbl ->
            parseNameTable(bytes, bb, nameTbl.offset, nameTbl.length)
        }

        tableEntries.firstOrNull { it.tag == "cmap" }?.let { cmapTbl ->
            parseCmapTable(bytes, bb, cmapTbl.offset, cmapTbl.length)
        }

        if (fullFontName.isBlank()) {
            fullFontName = if (fontFamily.isNotBlank()) "$fontFamily $fontSubfamily".trim() else "OpenType / TrueType Font"
        }
        if (fontFamily.isBlank()) {
            fontFamily = fullFontName
        }
    }

    private fun parseNameTable(bytes: ByteArray, bb: ByteBuffer, tableOffset: Int, tableLength: Int) {
        if (tableOffset + 6 > bytes.size || tableLength < 6) return
        val count = (bb.getShort(tableOffset + 2).toInt() and 0xFFFF).coerceIn(0, 512)
        val stringStorageOffset = tableOffset + (bb.getShort(tableOffset + 4).toInt() and 0xFFFF)
        val tableEnd = minOf(bytes.size, tableOffset + tableLength)

        val namesById = HashMap<Int, String>()

        for (i in 0 until count) {
            val recPos = tableOffset + 6 + i * 12
            if (recPos + 12 > tableEnd) break
            val platformId = bb.getShort(recPos).toInt() and 0xFFFF
            val encodingId = bb.getShort(recPos + 2).toInt() and 0xFFFF
            val languageId = bb.getShort(recPos + 4).toInt() and 0xFFFF
            val nameId = bb.getShort(recPos + 6).toInt() and 0xFFFF
            val length = bb.getShort(recPos + 8).toInt() and 0xFFFF
            val strOffset = stringStorageOffset + (bb.getShort(recPos + 10).toInt() and 0xFFFF)

            if (length <= 0 || strOffset < tableOffset || strOffset + length > tableEnd) continue

            val charset = when (platformId) {
                0, 3 -> StandardCharsets.UTF_16BE
                1 -> if (encodingId == 0) StandardCharsets.UTF_8 else PlainTextSession.CP1251
                else -> StandardCharsets.UTF_8
            }
            val decoded = try {
                String(bytes, strOffset, length, charset)
                    .replace('\u0000', ' ')
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } catch (_: Throwable) {
                ""
            }
            if (decoded.isNotEmpty()) {
                val isPreferred = (platformId == 3 && (languageId == 0x0409 || languageId == 0x0419)) || !namesById.containsKey(nameId)
                if (isPreferred) {
                    namesById[nameId] = decoded
                }
            }
        }

        copyrightNotice = namesById[0] ?: ""
        fontFamily = namesById[16] ?: namesById[1] ?: ""
        fontSubfamily = namesById[17] ?: namesById[2] ?: "Regular"
        fullFontName = namesById[4] ?: ""
        fontVersion = namesById[5] ?: ""
        postScriptName = namesById[6] ?: ""
        designerName = namesById[9] ?: namesById[8] ?: ""
    }

    private fun parseCmapTable(bytes: ByteArray, bb: ByteBuffer, tableOffset: Int, tableLength: Int) {
        if (tableOffset + 4 > bytes.size || tableLength < 4) return
        val numSubtables = (bb.getShort(tableOffset + 2).toInt() and 0xFFFF).coerceIn(0, 64)
        val tableEnd = minOf(bytes.size, tableOffset + tableLength)

        val format12Offsets = ArrayList<Int>()
        val format4Offsets = ArrayList<Int>()
        val format6Offsets = ArrayList<Int>()

        for (i in 0 until numSubtables) {
            val recPos = tableOffset + 4 + i * 8
            if (recPos + 8 > tableEnd) break
            val platformId = bb.getShort(recPos).toInt() and 0xFFFF
            val encodingId = bb.getShort(recPos + 2).toInt() and 0xFFFF
            val subOffset = tableOffset + bb.getInt(recPos + 4)
            if (subOffset < tableOffset || subOffset + 4 > tableEnd) continue
            val fmt = bb.getShort(subOffset).toInt() and 0xFFFF
            val isUnicodePlatform = platformId == 0 || (platformId == 3 && encodingId in setOf(0, 1, 10))
            when (fmt) {
                12 -> if (isUnicodePlatform || format12Offsets.isEmpty()) format12Offsets.add(subOffset)
                4 -> if (isUnicodePlatform || format4Offsets.isEmpty()) format4Offsets.add(subOffset)
                6 -> format6Offsets.add(subOffset)
            }
        }

        val codeSet = java.util.TreeSet<Int>()

        for (format12Offset in format12Offsets) {
            if (format12Offset + 16 <= tableEnd) {
                val numGroups = (bb.getInt(format12Offset + 12).toLong() and 0xFFFFFFFFL).coerceAtMost(65536L).toInt()
                var gPos = format12Offset + 16
                for (g in 0 until numGroups) {
                    if (gPos + 12 > tableEnd || codeSet.size >= 32768) break
                    val startChar = bb.getInt(gPos)
                    val endChar = bb.getInt(gPos + 4)
                    val startGlyphId = bb.getInt(gPos + 8)
                    if (startChar >= 0x20 && endChar >= startChar && endChar <= 0x10FFFF) {
                        for (cp in startChar..minOf(endChar, startChar + 16384)) {
                            val glyphId = startGlyphId + (cp - startChar)
                            if (glyphId > 0 && !Character.isISOControl(cp) && cp !in 0xD800..0xDFFF) {
                                codeSet.add(cp)
                                if (codeSet.size >= 32768) break
                            }
                        }
                    }
                    gPos += 12
                }
            }
        }

        for (format4Offset in format4Offsets) {
            if (format4Offset + 16 <= tableEnd) {
                val subLen = (bb.getShort(format4Offset + 2).toInt() and 0xFFFF)
                val subEnd = if (subLen > 16) minOf(tableEnd, format4Offset + subLen) else tableEnd
                val segCountX2 = (bb.getShort(format4Offset + 6).toInt() and 0xFFFF).coerceIn(2, 8192)
                val segCount = segCountX2 / 2
                val endCodesBase = format4Offset + 14
                val startCodesBase = endCodesBase + segCountX2 + 2
                val idDeltaBase = startCodesBase + segCountX2
                val idRangeOffsetBase = idDeltaBase + segCountX2

                if (idRangeOffsetBase + segCountX2 <= subEnd) {
                    for (s in 0 until segCount) {
                        if (codeSet.size >= 32768) break
                        val endCode = bb.getShort(endCodesBase + s * 2).toInt() and 0xFFFF
                        val startCode = bb.getShort(startCodesBase + s * 2).toInt() and 0xFFFF
                        if (startCode == 0xFFFF) break
                        val idDelta = bb.getShort(idDeltaBase + s * 2).toInt()
                        val idRangeOffsetPos = idRangeOffsetBase + s * 2
                        val idRangeOffset = bb.getShort(idRangeOffsetPos).toInt() and 0xFFFF

                        if (startCode >= 0x20 && endCode >= startCode) {
                            for (cp in startCode..endCode) {
                                if (Character.isISOControl(cp) || cp in 0xD800..0xDFFF) continue
                                val glyphId: Int = if (idRangeOffset == 0) {
                                    (cp + idDelta) and 0xFFFF
                                } else {
                                    val glyphIndexAddress = idRangeOffsetPos + idRangeOffset + 2 * (cp - startCode)
                                    if (glyphIndexAddress + 2 <= subEnd) {
                                        val rawGid = bb.getShort(glyphIndexAddress).toInt() and 0xFFFF
                                        if (rawGid != 0) (rawGid + idDelta) and 0xFFFF else 0
                                    } else {
                                        0
                                    }
                                }
                                if (glyphId != 0) {
                                    codeSet.add(cp)
                                    if (codeSet.size >= 32768) break
                                }
                            }
                        }
                    }
                }
            }
        }

        if (codeSet.isEmpty()) {
            for (format6Offset in format6Offsets) {
                if (format6Offset + 10 <= tableEnd) {
                    val firstCode = bb.getShort(format6Offset + 6).toInt() and 0xFFFF
                    val entryCount = (bb.getShort(format6Offset + 8).toInt() and 0xFFFF).coerceIn(0, 8192)
                    val arrayBase = format6Offset + 10
                    for (i in 0 until entryCount) {
                        val pos = arrayBase + i * 2
                        if (pos + 2 > tableEnd) break
                        val gid = bb.getShort(pos).toInt() and 0xFFFF
                        val cp = firstCode + i
                        if (gid != 0 && cp >= 0x20 && !Character.isISOControl(cp)) {
                            codeSet.add(cp)
                        }
                    }
                }
            }
        }

        supportedCodepoints.addAll(codeSet)
    }

    private fun populateDefaultPreviewCodepoints() {
        for (cp in 0x0021..0x007E) supportedCodepoints.add(cp)
        for (cp in 0x0401..0x0451) supportedCodepoints.add(cp)
    }

    private fun buildAdaptiveShowcaseAndPangrams() {
        showcaseSampleLines.clear()
        activePangramLines.clear()
        val cpSet = supportedCodepoints.toHashSet()

        fun filterSupported(candidate: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < candidate.length) {
                val cp = candidate.codePointAt(i)
                if (cp == ' '.code || cp in cpSet) {
                    sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        val upperLatin = filterSupported("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val lowerLatin = filterSupported("abcdefghijklmnopqrstuvwxyz")
        val upperCyr = filterSupported("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ")
        val lowerCyr = filterSupported("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
        val slavonicExt = filterSupported("ѢѣѦѧѨѩѪѫѬѭѮѯѰѱѲѳѴѵꙊꙋѺѻѾѿҀҁ")
        val digitsAndSymbols = filterSupported("0123456789  !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~  № € ₽ $ £ ¥")

        if (upperLatin.length >= 6) showcaseSampleLines.add(upperLatin to 38f)
        if (lowerLatin.length >= 6) showcaseSampleLines.add(lowerLatin to 38f)
        if (upperCyr.length >= 6) showcaseSampleLines.add(upperCyr to 36f)
        if (lowerCyr.length >= 6) showcaseSampleLines.add(lowerCyr to 36f)
        if (slavonicExt.length >= 4) showcaseSampleLines.add(slavonicExt to 36f)
        if (digitsAndSymbols.isNotEmpty()) showcaseSampleLines.add(digitsAndSymbols to 34f)

        if (showcaseSampleLines.isEmpty() && supportedCodepoints.isNotEmpty()) {
            val customLine = supportedCodepoints.take(32).joinToString(" ") { String(Character.toChars(it)) }
            showcaseSampleLines.add(customLine to 38f)
        }

        val hasCyrillic = upperCyr.length >= 10 || lowerCyr.length >= 10
        val hasLatin = upperLatin.length >= 10 || lowerLatin.length >= 10

        if (hasCyrillic) {
            val ruPangram = if (lowerCyr.length >= 10) {
                filterSupported("Съешь же ещё этих мягких французских булок, да выпей чаю 1234567890")
            } else {
                filterSupported("СЪЕШЬ ЖЕ ЕЩЁ ЭТИХ МЯГКИХ ФРАНЦУЗСКИХ БУЛОК, ДА ВЫПЕЙ ЧАЮ 1234567890")
            }
            if (ruPangram.isNotEmpty()) activePangramLines.add(ruPangram)
        }
        if (hasLatin) {
            val enPangram = if (lowerLatin.length >= 10) {
                filterSupported("The quick brown fox jumps over the lazy dog 1234567890")
            } else {
                filterSupported("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890")
            }
            if (enPangram.isNotEmpty()) activePangramLines.add(enPangram)
        }
        if (activePangramLines.isEmpty() && showcaseSampleLines.isNotEmpty()) {
            activePangramLines.add(showcaseSampleLines.first().first)
        }
    }

    private fun buildSearchablePageSummaries() {
        pageSummaries.clear()
        val p1 = StringBuilder()
        p1.append(fullFontName).append('\n')
        p1.append("Format: ").append(sfntTypeLabel)
            .append(" | Family: ").append(fontFamily)
            .append(" | Style: ").append(fontSubfamily)
            .append(" | Glyphs: ").append(numGlyphs)
            .append(" | UnitsPerEm: ").append(unitsPerEm)
            .append(" | Mapped Chars: ").append(supportedCodepoints.size).append('\n')
        val detailsLine = buildString {
            if (postScriptName.isNotEmpty()) append("PostScript: $postScriptName   ")
            if (fontVersion.isNotEmpty()) append("Version: $fontVersion   ")
            if (designerName.isNotEmpty()) append("Designer: $designerName")
        }.trim()
        if (detailsLine.isNotEmpty()) p1.append(detailsLine).append('\n')
        if (copyrightNotice.isNotEmpty()) p1.append("Copyright: ").append(copyrightNotice).append('\n')
        p1.append("SFNT Tables (").append(tableEntries.size).append("): ")
            .append(tableEntries.joinToString(", ") { "${it.tag} (${it.length} B)" }).append('\n')

        for ((lineStr, _) in showcaseSampleLines) {
            p1.append(lineStr).append('\n')
        }
        for (pangram in activePangramLines) {
            p1.append("PANGRAM: ").append(pangram).append('\n')
        }
        pageSummaries.add(p1.toString().trim())

        var idx = 0
        while (idx < supportedCodepoints.size) {
            val end = minOf(supportedCodepoints.size, idx + GLYPHS_PER_PAGE)
            val slice = supportedCodepoints.subList(idx, end)
            val sb = StringBuilder()
            for (cp in slice) {
                val hex = String.format(Locale.US, "U+%04X", cp)
                val chStr = String(Character.toChars(cp))
                sb.append('[').append(hex).append(" '").append(chStr).append("'] ")
            }
            pageSummaries.add(sb.toString().trim())
            idx = end
        }
    }

    override val pageCount: Int
        get() = pageSummaries.size.coerceAtLeast(1)

    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = VIRTUAL_DOC_WIDTH
    override fun getDocumentHeight(): Float = VIRTUAL_DOC_HEIGHT

    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return pageSummaries.joinToString("\n\n")
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageSummaries.size - 1).coerceAtLeast(0))
        return pageSummaries.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPage = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        val bgColor = if (isLight) Color.rgb(0xF8, 0xFA, 0xFC) else Color.rgb(0x12, 0x13, 0x1A)
        val cardBgColor = if (isLight) Color.WHITE else Color.rgb(0x1C, 0x1E, 0x2B)
        val primaryTextColor = if (isLight) Color.rgb(0x18, 0x1A, 0x24) else Color.WHITE
        val secondaryTextColor = if (isLight) Color.rgb(0x5A, 0x62, 0x75) else Color.rgb(0xA6, 0xAD, 0xC8)
        val accentColor = if (isLight) Color.rgb(0x00, 0x79, 0x6B) else Color.rgb(0x03, 0xDA, 0xC6)
        val borderColor = if (isLight) Color.rgb(0xD8, 0xDE, 0xE9) else Color.rgb(0x31, 0x32, 0x44)

        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 6.0f)
        val baseScale = minOf(
            bitmap.width.toFloat() / VIRTUAL_DOC_WIDTH,
            bitmap.height.toFloat() / VIRTUAL_DOC_HEIGHT
        ).coerceAtLeast(0.1f)
        val totalScale = baseScale * zoom

        val scaledW = VIRTUAL_DOC_WIDTH * totalScale
        val scaledH = VIRTUAL_DOC_HEIGHT * totalScale
        val dx = (bitmap.width - scaledW) / 2f - tileX.toFloat()
        val dy = (bitmap.height - scaledH) / 2f - tileY.toFloat()

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(totalScale, totalScale)

        val tf = customTypeface ?: Typeface.DEFAULT

        if (cleanPage == 0) {
            renderSpecimenOverviewPage(
                canvas = canvas,
                tf = tf,
                cardBgColor = cardBgColor,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                borderColor = borderColor
            )
        } else {
            renderGlyphGridPage(
                canvas = canvas,
                glyphPageIndex = cleanPage - 1,
                tf = tf,
                cardBgColor = cardBgColor,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                borderColor = borderColor
            )
        }

        canvas.restore()
    }

    private fun renderSpecimenOverviewPage(
        canvas: Canvas,
        tf: Typeface,
        cardBgColor: Int,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        accentColor: Int,
        borderColor: Int
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val sysPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
        }
        val fontPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = primaryTextColor
        }

        val pageRect = RectF(32f, 32f, VIRTUAL_DOC_WIDTH - 32f, VIRTUAL_DOC_HEIGHT - 32f)
        canvas.drawRoundRect(pageRect, 20f, 20f, cardPaint)
        canvas.drawRoundRect(pageRect, 20f, 20f, borderPaint)

        var y = 96f
        val leftPad = 68f
        val maxContentW = VIRTUAL_DOC_WIDTH - leftPad * 2f

        fontPaint.textSize = 68f
        val canRenderTitleInFont = fullFontName.any { it != ' ' && fontPaint.hasGlyph(it.toString()) }
        val activeTitlePaint = if (canRenderTitleInFont) {
            fontPaint
        } else {
            TextPaint(sysPaint).apply {
                color = primaryTextColor
                textSize = 68f
                isFakeBoldText = true
            }
        }
        val heroTitle = TextUtils.ellipsize(fullFontName, activeTitlePaint, maxContentW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(heroTitle, leftPad, y, activeTitlePaint)
        y += 44f

        sysPaint.color = accentColor
        sysPaint.textSize = 24f
        sysPaint.isFakeBoldText = true
        val metaHeader = "Format: $sfntTypeLabel  •  Family: $fontFamily  •  Style: $fontSubfamily  •  Glyphs: $numGlyphs  •  UnitsPerEm: $unitsPerEm  •  Mapped Chars: ${supportedCodepoints.size}"
        canvas.drawText(
            TextUtils.ellipsize(metaHeader, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
            leftPad,
            y,
            sysPaint
        )
        y += 34f

        sysPaint.color = secondaryTextColor
        sysPaint.textSize = 21f
        sysPaint.isFakeBoldText = false
        val detailsLine = buildString {
            if (postScriptName.isNotEmpty()) append("PostScript: $postScriptName   ")
            if (fontVersion.isNotEmpty()) append("Version: $fontVersion   ")
            if (designerName.isNotEmpty()) append("Designer: $designerName")
        }.trim()
        if (detailsLine.isNotEmpty()) {
            canvas.drawText(
                TextUtils.ellipsize(detailsLine, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
                leftPad,
                y,
                sysPaint
            )
            y += 28f
        }
        if (copyrightNotice.isNotEmpty()) {
            canvas.drawText(
                TextUtils.ellipsize("Copyright: $copyrightNotice", sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
                leftPad,
                y,
                sysPaint
            )
            y += 30f
        }

        val tablesStr = "SFNT Tables (${tableEntries.size}): " +
                tableEntries.joinToString(", ") { "${it.tag} (${it.length} B)" }
        canvas.drawText(
            TextUtils.ellipsize(tablesStr, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
            leftPad,
            y,
            sysPaint
        )
        y += 26f

        canvas.drawLine(leftPad, y, VIRTUAL_DOC_WIDTH - leftPad, y, borderPaint)
        y += 46f

        for ((lineStr, sz) in showcaseSampleLines) {
            fontPaint.textSize = sz
            val fitted = TextUtils.ellipsize(lineStr, fontPaint, maxContentW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, leftPad, y, fontPaint)
            y += sz * 1.38f
        }

        y += 12f
        canvas.drawLine(leftPad, y, VIRTUAL_DOC_WIDTH - leftPad, y, borderPaint)
        y += 48f

        val waterfallSizes = intArrayOf(22, 28, 36, 48, 62)
        for (pt in waterfallSizes) {
            if (y + pt * 1.4f > VIRTUAL_DOC_HEIGHT - 54f) break
            for (pangram in activePangramLines) {
                if (y + pt * 1.3f > VIRTUAL_DOC_HEIGHT - 48f) break
                sysPaint.color = accentColor
                sysPaint.textSize = 19f
                sysPaint.isFakeBoldText = true
                canvas.drawText("${pt}px", leftPad, y, sysPaint)

                fontPaint.textSize = pt.toFloat()
                val clipped = TextUtils.ellipsize(pangram, fontPaint, maxContentW - 76f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(clipped, leftPad + 74f, y, fontPaint)
                y += pt * 1.32f
            }
            y += 6f
        }
    }

    private fun renderGlyphGridPage(
        canvas: Canvas,
        glyphPageIndex: Int,
        tf: Typeface,
        cardBgColor: Int,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        accentColor: Int,
        borderColor: Int
    ) {
        val startIdx = glyphPageIndex * GLYPHS_PER_PAGE
        if (startIdx >= supportedCodepoints.size) return
        val endIdx = minOf(supportedCodepoints.size, startIdx + GLYPHS_PER_PAGE)
        val slice = supportedCodepoints.subList(startIdx, endIdx)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 28f
            color = primaryTextColor
        }
        val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 18f
            color = secondaryTextColor
            textAlign = Paint.Align.CENTER
        }
        val glyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 64f
            color = primaryTextColor
            textAlign = Paint.Align.CENTER
        }

        val margin = 40f
        val headerTitle = "$fullFontName  •  Glyph Map (${startIdx + 1}–$endIdx of ${supportedCodepoints.size})"
        canvas.drawText(headerTitle, margin, 68f, headerPaint)

        val gridTop = 96f
        val gridCols = 8
        val gridRows = 8
        val cellW = (VIRTUAL_DOC_WIDTH - margin * 2f) / gridCols
        val cellH = (VIRTUAL_DOC_HEIGHT - gridTop - margin) / gridRows

        for ((i, cp) in slice.withIndex()) {
            val r = i / gridCols
            val c = i % gridCols
            val left = margin + c * cellW
            val top = gridTop + r * cellH
            val cellRect = RectF(left + 4f, top + 4f, left + cellW - 4f, top + cellH - 4f)

            canvas.drawRoundRect(cellRect, 10f, 10f, cardPaint)
            canvas.drawRoundRect(cellRect, 10f, 10f, borderPaint)

            val chStr = if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(cp) == Character.ENCLOSING_MARK.toInt()
            ) {
                "\u25CC" + String(Character.toChars(cp))
            } else {
                String(Character.toChars(cp))
            }
            canvas.drawText(chStr, cellRect.centerX(), cellRect.top + cellRect.height() * 0.60f, glyphPaint)

            codePaint.color = accentColor
            val hexCode = String.format(Locale.US, "U+%04X", cp)
            canvas.drawText(hexCode, cellRect.centerX(), cellRect.bottom - 14f, codePaint)
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pageSummaries.size - 1).coerceAtLeast(0))
        for (i in start until pageSummaries.size) {
            if (pageSummaries[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until start) {
            if (pageSummaries[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try { pfd.close() } catch (_: Exception) {}
        try { tempFontFile?.delete() } catch (_: Throwable) {}
        tempFontFile = null
    }
}


class DocumentRendererEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return ext in setOf(
            "pdf", "djvu", "djv",
            "ttf", "otf", "ttc", "woff", "woff2", "dfont",
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur",
            "txt", "log", "json", "xml", "md", "ini", "cfg", "yaml", "yml",
            "fb2", "fbz", "epub", "docx", "docm", "dotx", "dotm", "odt", "ott",
            "azw3", "azw", "mobi", "prc", "kfx", "one", "max", "rvt", "indd",
            "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "iso",
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
            "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
            "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
            "3mf", "dae", "step", "stp", "iges", "igs"
        )
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        val header = ByteBuffer.allocateDirect(4096)
        val fileSize = resolveStreamDescriptorSize(fd)
        try {
            try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            Os.read(fd, header)
            header.flip()
        } catch (_: Exception) {
            header.limit(0)
        }
        val detected = FileSignatureDetector.resolveFormat(fd, header, "", fileSize)
        return createSessionForFormat(fd, detected, "")
    }

    fun createSessionForFormat(fd: FileDescriptor, format: DetectedFormat, extension: String = ""): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val isGraphicExtension = ext in setOf(
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur"
        )

        if (format == DetectedFormat.FONT || ext in setOf("ttf", "otf", "ttc", "woff", "woff2", "dfont")) {
            return CleanRoomFontSession(ParcelFileDescriptor.dup(fd), ext.ifEmpty { "ttf" })
        }

        if (format == DetectedFormat.DJVU || ext in setOf("djvu", "djv")) {
            return CleanRoomDjvuSession(ParcelFileDescriptor.dup(fd))
        }

        if (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX || ext == "kfx") {
            return KindleDrmNoticeSession(ParcelFileDescriptor.dup(fd), format)
        }

        if (format in setOf(DetectedFormat.AZW3, DetectedFormat.MOBI) || ext in setOf("azw3", "azw", "mobi", "prc")) {
            return PalmDocMobiCleanRoomSession(ParcelFileDescriptor.dup(fd), ext.ifEmpty { "mobi" })
        }

        if (format in setOf(
                DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT,
                DetectedFormat.RTF, DetectedFormat.ISO, DetectedFormat.ONE,
                DetectedFormat.MAX, DetectedFormat.RVT, DetectedFormat.INDD,
                DetectedFormat.XLSX, DetectedFormat.ODS,
                DetectedFormat.PPTX, DetectedFormat.ODP,
                DetectedFormat.SVG, DetectedFormat.SVGZ,
                DetectedFormat.STL, DetectedFormat.OBJ, DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB, DetectedFormat.BLEND, DetectedFormat.USDZ
            ) ||
            (ext in setOf(
                "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "iso", "one", "max", "rvt", "indd",
                "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv",
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
                "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
                "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            ) && format !in setOf(DetectedFormat.DOCX, DetectedFormat.ODT, DetectedFormat.EPUB, DetectedFormat.FB2))
        ) {
            return CleanRoomLegacyAndIsoEngine.createSession(fd, format, ext)
        }

        val dupFd = ParcelFileDescriptor.dup(fd)
        return when {
            format == DetectedFormat.PDF || ext == "pdf" -> PdfDocumentSession(dupFd)
            format in setOf(
                DetectedFormat.PNG, DetectedFormat.JPEG, DetectedFormat.GIF,
                DetectedFormat.TIFF, DetectedFormat.WEBP, DetectedFormat.BMP,
                DetectedFormat.HEIC, DetectedFormat.AVIF
            ) || isGraphicExtension -> ImageRegionSession(dupFd, format, ext)
            format in setOf(
                DetectedFormat.FB2, DetectedFormat.EPUB, DetectedFormat.DOCX,
                DetectedFormat.ODT
            ) || ext in setOf("fb2", "fbz", "epub", "docx", "docm", "dotx", "dotm", "odt", "ott") ->
                EbookTextSession(dupFd, format, ext)
            else -> PlainTextSession(dupFd)
        }
    }
}

/**
 * Lock-separated, non-blocking PDF session.
 * Uses [rendererLock] strictly for fast `PdfRenderer` operations (`renderTile`, `getPageAspectRatio`)
 * and [textExtractLock] for lazy per-page text extraction so `getPageText` never stalls page turns.
 * On Android 15+ (API 35+, including HyperOS), extracts exact text and line-level bounding boxes
 * directly from the system `PdfRenderer.Page.getTextContents()` API.
 */
class PdfDocumentSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
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
    private var indexedContentSlices: List<PdfStreamSlice>? = null
    private val cmapTable = HashMap<Int, String>()
    @Volatile private var isStreamsIndexed = false

    init {
        try {
            try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            val renderer = PdfRenderer(pfd)
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
            lastErrorMsg = "PASSWORD_REQUIRED: PDF document is password protected."
            stateVer++
        } catch (e: Exception) {
            pdfRenderer = null
            val isEncryptedHeader = checkPdfEncryptionMarker()
            lastErrorMsg = if (isEncryptedHeader) {
                "PASSWORD_REQUIRED: PDF document is encrypted or password protected."
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

                        // Also group lineRects into full visual rows (merging multi-segment lines at the same Y)
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
        if (isClosed || pdfRenderer == null || lastErrorMsg != null) return ""
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

        // 1. Primary path on Android 15+ (HyperOS / API 35+): native PDFium text & bounds extraction
        val nativeExtracted = extractNativePdfPageTextAndroid15(cleanIdx)
        if (!nativeExtracted.isNullOrBlank()) {
            if (pageTextCache.size > 256) pageTextCache.clear()
            pageTextCache[cleanIdx] = nativeExtracted
            return nativeExtracted
        }

        // 2. Fallback path: low-memory streaming PDF content stream parser
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
        // Erase outer letterbox with TRANSPARENT so Compose theme background renders seamlessly
        // with zero black bars at any zoom or aspect ratio.
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
                val tiffPages = TiffCleanRoomDecoder.getPageCount(readableSource)
                val firstPageBmp = TiffCleanRoomDecoder.decodePage(readableSource, 0)
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
                    val tiffFallback = TiffCleanRoomDecoder.decodePage(readableSource, 0)
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

        // 1. Check if the icon payload is an embedded PNG image
        if ((bytes[bestOffset].toInt() and 0xFF) == 0x89 &&
            bytes[bestOffset + 1] == 0x50.toByte() &&
            bytes[bestOffset + 2] == 0x4E.toByte() &&
            bytes[bestOffset + 3] == 0x47.toByte()
        ) {
            BitmapFactory.decodeByteArray(bytes, bestOffset, bestSize)?.let { return it }
        }

        // 2. Otherwise it is an ICO headless DIB where biHeight == 2 * actualHeight (XOR + AND masks)
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

        // 1. Dedicated clean-room ICO / CUR decoder
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

        // 2. For HEIC / HEIF / AVIF on Android 11+ (API 30+), prefer ImageDecoder via AssetFileDescriptor first:
        //    ImageDecoder natively supports 10-bit/12-bit HEIC/AVIF, grid tiles, and container irot/imir rotation.
        if (isIsoBmff) {
            decodeViaZeroCopyImageDecoder()?.let { return it }
        }

        // 3. Fast native FileDescriptor path via BitmapFactory.decodeFileDescriptor
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

        // 4. Universal ImageDecoder fallback (handles animated WebP/GIF, unusual PNG/JPEG profiles, HEIC/AVIF)
        decodeViaZeroCopyImageDecoder()?.let { return it }

        // 5. Clean-room DIB / Metafile / SVG fallback
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
        // First try zero-copy AssetFileDescriptor (no heap/DirectByteBuffer size cap!)
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

        // Fallback to DirectByteBuffer for streams up to 48 MB
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
            TiffCleanRoomDecoder.decodePage(readableSource, targetPage)?.let { newBmp ->
                newBmp.setHasMipMap(true)
                orientedBitmap?.recycle()
                orientedBitmap = newBmp
                activeBitmapPageIndex = targetPage
                docW = newBmp.width.toFloat()
                docH = newBmp.height.toFloat()
            }
        }

        // Erase outer letterbox with TRANSPARENT so Compose canvasBackground renders seamlessly
        // with zero black/gray bars at any zoom-out or zoom-in ratio.
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

        // Always draw the full-frame orientedBitmap as a seamless base layer inside [visLeft..visRight, visTop..visBottom]
        // so that sub-block edges from BitmapRegionDecoder never expose uninitialized Color.BLACK pixels.
        drawMatrix.reset()
        drawMatrix.setScale(
            finalWidth / fb.width.toFloat().coerceAtLeast(1f),
            finalHeight / fb.height.toFloat().coerceAtLeast(1f)
        )
        drawMatrix.postTranslate(dx, dy)
        canvas.drawBitmap(fb, drawMatrix, bitmapDrawPaint)

        // High-resolution cached region overlay when zoomed in on downsampled large images
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

                // Strictly clamp rawRect inside [0, rawW] x [0, rawH] so native BitmapRegionDecoder
                // never pads out-of-bounds sensor areas with Color.BLACK (0xFF000000).
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

class EbookTextSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat,
    private val extension: String
) : DocumentSession {
    @Volatile private var isClosed = false
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private var parseError: String? = null

    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.SERIF
        isAntiAlias = true
    }

    init {
        try {
            val fileSize = resolveStreamDescriptorSize(pfd.fileDescriptor)
            val isZipContainer = format == DetectedFormat.EPUB ||
                    format == DetectedFormat.DOCX ||
                    format == DetectedFormat.ODT ||
                    extension == "fbz" ||
                    extension.endsWith(".zip")

            if (isZipContainer) {
                val hdr = ByteBuffer.allocateDirect(8)
                val r = try { Os.pread(pfd.fileDescriptor, hdr, 0L) } catch (_: Throwable) { 0 }
                if (r >= 4 &&
                    (hdr.get(0).toInt() and 0xFF) == 0xD0 &&
                    (hdr.get(1).toInt() and 0xFF) == 0xCF &&
                    (hdr.get(2).toInt() and 0xFF) == 0x11 &&
                    (hdr.get(3).toInt() and 0xFF) == 0xE0
                ) {
                    val legacyFormat = when (format) {
                        DetectedFormat.DOCX -> DetectedFormat.DOC
                        DetectedFormat.XLSX -> DetectedFormat.XLS
                        DetectedFormat.PPTX -> DetectedFormat.PPT
                        else -> DetectedFormat.DOC
                    }
                    val legacySession = CleanRoomLegacyAndIsoEngine.createSession(pfd.fileDescriptor, legacyFormat, extension)
                    try {
                        parseError = legacySession.getLastError()
                        fullText = legacySession.getPageText(-1)
                    } finally {
                        legacySession.close()
                    }
                } else {
                    val entries = extractSingleZipArchive(pfd.fileDescriptor, fileSize)
                    fullText = parseZipTextMap(entries)
                }
            } else {
                val maxRead = if (fileSize > 0L) minOf(fileSize, 32 * 1024 * 1024L).toInt() else 16 * 1024 * 1024
                val buf = ByteBuffer.allocateDirect(maxRead)
                var total = 0
                while (buf.hasRemaining()) {
                    val r = Os.pread(pfd.fileDescriptor, buf, total.toLong())
                    if (r <= 0) break
                    total += r
                }
                buf.flip()
                val bytes = ByteArray(total)
                buf.get(bytes)

                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    val entries = extractSingleZipArchive(pfd.fileDescriptor, fileSize)
                    fullText = parseZipTextMap(entries)
                } else {
                    val sb = StringBuilder()
                    extractXmlBytesWithCharset(
                        bytes = bytes,
                        out = sb,
                        onBinaryImageDecoded = { imgBytes ->
                            if (embeddedImages.size < 32) {
                                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let { bmp ->
                                    val idx = embeddedImages.size
                                    embeddedImages.add(bmp)
                                    sb.append("\n[IMAGE:$idx]\n")
                                }
                            }
                        }
                    )
                    fullText = sb.toString()
                }
            }
        } catch (sec: SecurityException) {
            parseError = sec.message ?: "Заблокирована потенциальная XXE/Zip-Bomb атака."
        } catch (e: Exception) {
            parseError = "Ошибка структуры контейнера (${format.name}): ${e.localizedMessage ?: "файл повреждён"}"
        }

        if (fullText.isBlank() && embeddedImages.isEmpty() && parseError == null) {
            parseError = "Не удалось извлечь текст из контейнера ${format.name}: файл пуст, зашифрован или повреждён."
        }

        paginateRichText(fullText, embeddedImages.size, pages)
    }

    fun getEmbeddedImagesCount(): Int = embeddedImages.size
    fun getEmbeddedImage(index: Int): Bitmap? = embeddedImages.getOrNull(index)

    private fun extractSingleZipArchive(fd: FileDescriptor, fileSize: Long): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val maxAllowedBytes = 96 * 1024 * 1024L

        if (fileSize >= 22L && tryReadZipCentralDirectory(fd, fileSize, maxAllowedBytes, result)) {
            if (result.isNotEmpty()) return result
        }

        extractZipStreamFallback(fd, fileSize, maxAllowedBytes, result)
        return result
    }

    private fun isTextOrXmlEntryName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".xml") ||
                lower.endsWith(".rels") ||
                lower.endsWith(".xhtml") ||
                lower.endsWith(".html") ||
                lower.endsWith(".htm") ||
                lower.endsWith(".opf") ||
                lower.endsWith(".ncx") ||
                lower.endsWith(".fb2") ||
                lower.endsWith(".txt")
    }

    private fun isSupportedImageEntry(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".bmp") ||
                lower.endsWith(".wmf") ||
                lower.endsWith(".emf") ||
                lower.endsWith(".svg") ||
                lower.endsWith(".tif") ||
                lower.endsWith(".tiff")
    }

    private fun tryReadZipCentralDirectory(
        fd: FileDescriptor,
        fileSize: Long,
        maxAllowedBytes: Long,
        outMap: MutableMap<String, ByteArray>
    ): Boolean {
        return try {
            val tailSize = minOf(fileSize, 65557L).toInt()
            val tailOffset = fileSize - tailSize
            val tailBuf = ByteBuffer.allocateDirect(tailSize).order(ByteOrder.LITTLE_ENDIAN)
            val readTail = preadFully(fd, tailBuf, tailOffset)
            if (readTail < 22) return false
            tailBuf.flip()

            var eocdPos = -1
            for (i in readTail - 22 downTo 0) {
                if (tailBuf.getInt(i) == 0x06054b50) {
                    eocdPos = i
                    break
                }
            }
            if (eocdPos < 0) return false

            val cdSize = tailBuf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset < 0L || cdSize <= 0L || cdOffset + cdSize > fileSize || cdSize > 32 * 1024 * 1024L) {
                return false
            }

            val cdBuf = ByteBuffer.allocateDirect(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val cdRead = preadFully(fd, cdBuf, cdOffset)
            if (cdRead < cdSize.toInt()) return false
            cdBuf.flip()

            data class CdEntryCandidate(
                val rawName: String,
                val method: Int,
                val compSize: Long,
                val uncompSize: Long,
                val localHeaderOffset: Long,
                val isTextOrXml: Boolean
            )

            val candidates = ArrayList<CdEntryCandidate>()
            var pos = 0

            while (pos + 46 <= cdRead) {
                val sig = cdBuf.getInt(pos)
                if (sig != 0x02014b50) break

                val gpFlags = cdBuf.getShort(pos + 8).toInt() and 0xFFFF
                val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
                val compSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                val uncompSize = cdBuf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
                val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
                val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
                val localHeaderOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

                val nextPos = pos + 46 + nameLen + extraLen + commentLen
                if (pos + 46 + nameLen > cdRead) break

                if ((gpFlags and 0x01) != 0) {
                    throw SecurityException("PASSWORD_REQUIRED: Архив или документ защищён паролем.")
                }

                val nameBytes = ByteArray(nameLen)
                cdBuf.position(pos + 46)
                cdBuf.get(nameBytes)
                val rawName = String(nameBytes, StandardCharsets.UTF_8)

                if (!rawName.endsWith("/")) {
                    if (uncompSize != 0xFFFFFFFFL && uncompSize > 16L * 1024L * 1024L &&
                        compSize > 0L && (uncompSize / compSize) > 150L
                    ) {
                        throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                    }
                    candidates.add(
                        CdEntryCandidate(
                            rawName = rawName,
                            method = method,
                            compSize = compSize,
                            uncompSize = uncompSize,
                            localHeaderOffset = localHeaderOffset,
                            isTextOrXml = isTextOrXmlEntryName(rawName)
                        )
                    )
                }
                pos = nextPos
            }

            val prioritized = candidates.sortedByDescending { it.isTextOrXml }
            var totalUnpacked = 0L
            val localHeaderBuf = ByteBuffer.allocateDirect(30).order(ByteOrder.LITTLE_ENDIAN)

            for (cand in prioritized) {
                val maxSingleEntry = if (cand.isTextOrXml) maxAllowedBytes else 32 * 1024 * 1024L
                if (cand.compSize !in 0L..maxSingleEntry || cand.localHeaderOffset + 30L > fileSize) continue
                if (!cand.isTextOrXml && totalUnpacked >= maxAllowedBytes) continue
                if (cand.uncompSize != 0xFFFFFFFFL && totalUnpacked + cand.uncompSize > maxAllowedBytes + 32 * 1024 * 1024L) {
                    if (!cand.isTextOrXml) continue
                }

                localHeaderBuf.clear()
                if (preadFully(fd, localHeaderBuf, cand.localHeaderOffset) == 30 &&
                    localHeaderBuf.getInt(0) == 0x04034b50
                ) {
                    val lNameLen = localHeaderBuf.getShort(26).toInt() and 0xFFFF
                    val lExtraLen = localHeaderBuf.getShort(28).toInt() and 0xFFFF
                    val dataOffset = cand.localHeaderOffset + 30L + lNameLen + lExtraLen
                    if (dataOffset + cand.compSize <= fileSize) {
                        val compBuf = ByteBuffer.allocateDirect(cand.compSize.toInt())
                        val got = preadFully(fd, compBuf, dataOffset)
                        if (got == cand.compSize.toInt()) {
                            compBuf.flip()
                            val compArray = ByteArray(got)
                            compBuf.get(compArray)
                            val entryBudget = (maxAllowedBytes + 32 * 1024 * 1024L - totalUnpacked).coerceAtLeast(8 * 1024 * 1024L)
                            val entryData = when (cand.method) {
                                0 -> compArray
                                8 -> inflateRawEntry(compArray, entryBudget)
                                else -> null
                            }
                            if (entryData != null) {
                                totalUnpacked += entryData.size
                                outMap[cand.rawName.replace('\\', '/').trimStart('/')] = entryData
                            }
                        }
                    }
                }
            }
            outMap.isNotEmpty()
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            false
        }
    }

    private fun preadFully(fd: FileDescriptor, buf: ByteBuffer, startOffset: Long): Int {
        var total = 0
        while (buf.hasRemaining()) {
            val r = try {
                Os.pread(fd, buf, startOffset + total)
            } catch (_: Throwable) {
                -1
            }
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun inflateRawEntry(compressed: ByteArray, maxAllowed: Long): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var total = 0L
            val compLen = compressed.size.coerceAtLeast(1).toLong()
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    total += count
                    if (total > 16L * 1024L * 1024L && (total / compLen) > 150L) {
                        throw SecurityException("Превышен безопасный лимит распаковки записи (защита от Zip-бомб).")
                    }
                    if (total > maxAllowed) {
                        return null
                    }
                    bos.write(buf, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            bos.toByteArray()
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            null
        } finally {
            try { inflater.end() } catch (_: Throwable) {}
        }
    }

    private fun extractZipStreamFallback(
        fd: FileDescriptor,
        fileSize: Long,
        maxAllowedBytes: Long,
        outMap: MutableMap<String, ByteArray>
    ) {
        val safeStream = BufferedInputStream(PositionalPreadInputStream(fd, fileSize), 32 * 1024)
        ZipInputStream(safeStream).use { zis ->
            var totalBytes = 0L
            val buffer = ByteArray(16384)
            while (true) {
                val entry = try {
                    zis.nextEntry
                } catch (_: Throwable) {
                    break
                } ?: break

                if (!entry.isDirectory) {
                    val normName = entry.name.replace('\\', '/').trimStart('/')
                    val isText = isTextOrXmlEntryName(normName)
                    if (!isText && totalBytes >= maxAllowedBytes) {
                        try { zis.closeEntry() } catch (_: Throwable) {}
                        continue
                    }
                    try {
                        val bos = ByteArrayOutputStream()
                        var entryUnpacked = 0L
                        val entryCompHint = entry.compressedSize.takeIf { it > 0L } ?: fileSize.coerceAtLeast(1L)
                        var read: Int
                        while (zis.read(buffer).also { read = it } > 0) {
                            entryUnpacked += read
                            totalBytes += read
                            if (entryUnpacked > 16L * 1024L * 1024L &&
                                (entryUnpacked / entryCompHint.coerceAtLeast(1L)) > 150L
                            ) {
                                throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                            }
                            if (totalBytes > maxAllowedBytes + 32 * 1024 * 1024L) {
                                if (fileSize in 1L..(totalBytes / 150L)) {
                                    throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                                }
                                break
                            }
                            bos.write(buffer, 0, read)
                        }
                        if (bos.size() > 0) {
                            outMap[normName] = bos.toByteArray()
                        }
                    } catch (sec: SecurityException) {
                        throw sec
                    } catch (_: Throwable) {
                    }
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
    }

    private fun extractDocxRelationships(relsBytes: ByteArray?, baseDir: String): Map<String, String> {
        if (relsBytes == null) return emptyMap()
        val map = HashMap<String, String>()
        try {
            val parser = createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(relsBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                if (ev == XmlPullParser.START_TAG && local == "relationship") {
                    val id = parser.getAttributeValue(null, "Id") ?: parser.getAttributeValue(null, "id")
                    val target = parser.getAttributeValue(null, "Target") ?: parser.getAttributeValue(null, "target")
                    if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                        val clean = target.replace('\\', '/').trim()
                        val resolved = when {
                            clean.startsWith("/") -> clean.trimStart('/')
                            clean.startsWith("$baseDir/", ignoreCase = true) -> clean
                            baseDir.isEmpty() -> clean
                            else -> "$baseDir/$clean"
                        }
                        val parts = resolved.split('/')
                        val normParts = ArrayList<String>(parts.size)
                        for (p in parts) {
                            if (p.isEmpty() || p == ".") continue
                            if (p == "..") {
                                if (normParts.isNotEmpty()) normParts.removeAt(normParts.lastIndex)
                            } else {
                                normParts.add(p)
                            }
                        }
                        map[id] = normParts.joinToString("/")
                    }
                }
                ev = parser.next()
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun parseZipTextMap(entries: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        if (entries.isEmpty()) return ""

        val imagePathToIndex = HashMap<String, Int>()
        for ((name, data) in entries) {
            if (embeddedImages.size >= 32) break
            if (isSupportedImageEntry(name) && data.size in 32..(12 * 1024 * 1024)) {
                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(data, 0, data.size)?.let { bmp ->
                    val idx = embeddedImages.size
                    embeddedImages.add(bmp)
                    val normKey = name.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
                    imagePathToIndex[normKey] = idx
                    imagePathToIndex[normKey.substringAfterLast('/')] = idx
                }
            }
        }

        val relIdToImageIndex = HashMap<String, Int>()
        val docxRelsBytes = entries["word/_rels/document.xml.rels"]
            ?: entries.entries.firstOrNull { it.key.endsWith("document.xml.rels", ignoreCase = true) }?.value
        if (docxRelsBytes != null) {
            val relsMap = extractDocxRelationships(docxRelsBytes, "word")
            for ((rId, targetPath) in relsMap) {
                val lowerTarget = targetPath.lowercase(Locale.ROOT)
                val imgIdx = imagePathToIndex[lowerTarget] ?: imagePathToIndex[lowerTarget.substringAfterLast('/')]
                if (imgIdx != null) {
                    relIdToImageIndex[rId] = imgIdx
                }
            }
        }

        val emittedImageIndices = HashSet<Int>()
        val onInlineImageRef: (String) -> Unit = { rawRef ->
            val cleanRef = rawRef.replace('\\', '/').trim()
            val imgIdx = relIdToImageIndex[cleanRef]
                ?: imagePathToIndex[cleanRef.lowercase(Locale.ROOT).trimStart('/')]
                ?: imagePathToIndex[cleanRef.substringAfterLast('/').lowercase(Locale.ROOT)]
            if (imgIdx != null && emittedImageIndices.add(imgIdx)) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append("[IMAGE:$imgIdx]\n")
            }
        }

        when {
            format == DetectedFormat.DOCX -> {
                val docKey = entries.keys.firstOrNull { it.equals("word/document.xml", ignoreCase = true) }
                    ?: entries.keys.firstOrNull { it.endsWith("/document.xml", ignoreCase = true) }
                if (docKey != null) {
                    extractXmlBytesWithCharset(entries[docKey]!!, sb, onInlineImageRef = onInlineImageRef)
                }
                entries.keys
                    .filter {
                        val l = it.lowercase(Locale.ROOT)
                        (l.startsWith("word/header") ||
                                l.startsWith("word/footer") ||
                                l == "word/footnotes.xml" ||
                                l == "word/endnotes.xml" ||
                                l == "word/comments.xml" ||
                                l.startsWith("word/diagrams/data")) &&
                                l.endsWith(".xml")
                    }
                    .sorted()
                    .forEach { k -> extractXmlBytesWithCharset(entries[k]!!, sb, onInlineImageRef = onInlineImageRef) }
            }
            format == DetectedFormat.ODT -> {
                val odtKey = entries.keys.firstOrNull { it.equals("content.xml", ignoreCase = true) }
                if (odtKey != null) {
                    extractXmlBytesWithCharset(entries[odtKey]!!, sb, onInlineImageRef = onInlineImageRef)
                }
            }
            format == DetectedFormat.EPUB -> {
                val orderedChapters = mutableListOf<ByteArray>()
                val opfEntry = entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }
                if (opfEntry != null) {
                    try {
                        val opfDir = opfEntry.key.substringBeforeLast('/', "")
                        val manifestHrefById = HashMap<String, String>()
                        val spineIdrefs = mutableListOf<String>()

                        val parser = createSafeXmlParser()
                        parser.setInput(InputStreamReader(ByteArrayInputStream(opfEntry.value), StandardCharsets.UTF_8))
                        var ev = parser.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                val tag = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                                if (tag == "item") {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                        val decodedHref = try {
                                            java.net.URLDecoder.decode(href, "UTF-8")
                                        } catch (_: Exception) {
                                            href
                                        }
                                        val fullPath = if (opfDir.isEmpty()) decodedHref else "$opfDir/$decodedHref"
                                        manifestHrefById[id] = fullPath.trimStart('/')
                                    }
                                } else if (tag == "itemref") {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    if (!idref.isNullOrBlank()) spineIdrefs.add(idref)
                                }
                            }
                            ev = parser.next()
                        }

                        for (idref in spineIdrefs) {
                            val targetPath = manifestHrefById[idref] ?: continue
                            val matchedEntry = entries.entries.firstOrNull {
                                it.key.equals(targetPath, ignoreCase = true) ||
                                        it.key.endsWith("/$targetPath", ignoreCase = true)
                            }
                            if (matchedEntry != null) {
                                orderedChapters.add(matchedEntry.value)
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (orderedChapters.isNotEmpty()) {
                    for (chapterBytes in orderedChapters) {
                        extractXmlBytesWithCharset(chapterBytes, sb, onInlineImageRef = onInlineImageRef)
                    }
                } else {
                    entries.entries
                        .filter {
                            val lower = it.key.lowercase(Locale.ROOT)
                            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                        }
                        .sortedBy { it.key.lowercase(Locale.ROOT) }
                        .forEach { (_, data) -> extractXmlBytesWithCharset(data, sb, onInlineImageRef = onInlineImageRef) }
                }
            }
            extension == "fbz" || entries.keys.any { it.endsWith(".fb2", ignoreCase = true) } -> {
                entries.entries
                    .filter { it.key.endsWith(".fb2", ignoreCase = true) || it.key.endsWith(".xml", ignoreCase = true) }
                    .forEach { (_, data) ->
                        extractXmlBytesWithCharset(
                            bytes = data,
                            out = sb,
                            onBinaryImageDecoded = { imgBytes ->
                                if (embeddedImages.size < 32) {
                                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let { bmp ->
                                        val idx = embeddedImages.size
                                        embeddedImages.add(bmp)
                                        sb.append("\n[IMAGE:$idx]\n")
                                    }
                                }
                            },
                            onInlineImageRef = onInlineImageRef
                        )
                    }
            }
        }

        if (sb.isBlank()) {
            for ((name, data) in entries) {
                val lower = name.lowercase(Locale.ROOT)
                val isMeta = lower == "mimetype" ||
                        lower.contains("meta-inf/") ||
                        lower.startsWith("_rels/") ||
                        lower.endsWith(".rels") ||
                        lower == "[content_types].xml" ||
                        lower.endsWith(".opf") ||
                        lower.endsWith(".ncx")
                if (!isMeta && (lower.endsWith(".xml") || lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".fb2") || lower.endsWith(".txt"))) {
                    extractXmlBytesWithCharset(data, sb, onInlineImageRef = onInlineImageRef)
                }
            }
        }

        for (imgIdx in 0 until embeddedImages.size) {
            if (imgIdx !in emittedImageIndices) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append("[IMAGE:$imgIdx]\n")
            }
        }

        return sb.toString()
    }

    companion object {
        private val XML_ENCODING_REGEX = Regex("""encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""", RegexOption.IGNORE_CASE)

        fun detectXmlCharset(bytes: ByteArray): Charset {
            if (bytes.isEmpty()) return StandardCharsets.UTF_8
            when {
                bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF ->
                    return StandardCharsets.UTF_8
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE ->
                    return StandardCharsets.UTF_16LE
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF ->
                    return StandardCharsets.UTF_16BE
            }
            val headLen = minOf(bytes.size, 512)
            val asciiHead = String(bytes, 0, headLen, StandardCharsets.ISO_8859_1)
            val match = XML_ENCODING_REGEX.find(asciiHead)
            val encName = match?.groupValues?.getOrNull(1)?.trim()
            if (!encName.isNullOrEmpty()) {
                return try {
                    Charset.forName(encName)
                } catch (_: Exception) {
                    StandardCharsets.UTF_8
                }
            }
            return try {
                PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192)).first
            } catch (_: Exception) {
                StandardCharsets.UTF_8
            }
        }

        fun decodeAndPreprocessXml(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val (bomCharset, bomSkip) = when {
                bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF ->
                    StandardCharsets.UTF_8 to 3
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE ->
                    StandardCharsets.UTF_16LE to 2
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF ->
                    StandardCharsets.UTF_16BE to 2
                else -> null to 0
            }

            val charset: Charset = bomCharset ?: detectXmlCharset(bytes)

            val rawXml = String(bytes, bomSkip, bytes.size - bomSkip, charset)
            val withoutDoctype = stripDoctypePreserveEntities(rawXml)
            return decodeCommonHtmlEntities(withoutDoctype)
        }

        private fun stripDoctypePreserveEntities(xml: String): String {
            val idx = xml.indexOf("<!DOCTYPE", ignoreCase = true)
            if (idx < 0) return xml
            val headSlice = xml.substring(idx, minOf(xml.length, idx + 8192))
            if (headSlice.contains("<!ENTITY", ignoreCase = true) ||
                headSlice.contains("SYSTEM", ignoreCase = true) ||
                headSlice.contains("PUBLIC", ignoreCase = true)
            ) {
                if (headSlice.contains("<!ENTITY", ignoreCase = true)) {
                    throw SecurityException("Обнаружена запрещённая внешняя сущность (XXE) в DOCTYPE.")
                }
            }
            val sb = StringBuilder(xml.length)
            sb.append(xml, 0, idx)
            var p = idx + 9
            var bracketDepth = 0
            while (p < xml.length) {
                val c = xml[p]
                if (c == '[') bracketDepth++
                else if (c == ']' && bracketDepth > 0) bracketDepth--
                else if (c == '>' && bracketDepth == 0) {
                    p++
                    break
                }
                p++
            }
            if (p < xml.length) sb.append(xml, p, xml.length)
            return sb.toString()
        }

        private fun decodeCommonHtmlEntities(xml: String): String {
            if (!xml.contains('&')) return xml
            return xml
                .replace("&nbsp;", "\u00A0")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&bdquo;", "„")
                .replace("&lsquo;", "‘")
                .replace("&rsquo;", "’")
                .replace("&hellip;", "…")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&trade;", "™")
                .replace("&sect;", "§")
                .replace("&bull;", "•")
                .replace("&deg;", "°")
                .replace("&plusmn;", "±")
                .replace("&times;", "×")
                .replace("&divide;", "÷")
                .replace("&euro;", "€")
        }

        fun createSafeXmlParser(): XmlPullParser {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            try { factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            return factory.newPullParser()
        }

        fun extractXmlBytesWithCharset(
            bytes: ByteArray,
            out: StringBuilder,
            onBinaryImageDecoded: ((ByteArray) -> Unit)? = null,
            onInlineImageRef: ((String) -> Unit)? = null
        ) {
            if (bytes.isEmpty()) return
            val xmlString = decodeAndPreprocessXml(bytes)
            val parser = createSafeXmlParser()
            parser.setInput(StringReader(xmlString))
            extractTextFromParser(parser, out, onBinaryImageDecoded, onInlineImageRef)
        }

        fun extractTextFromParser(
            parser: XmlPullParser,
            out: StringBuilder,
            onBinaryImageDecoded: ((ByteArray) -> Unit)? = null,
            onInlineImageRef: ((String) -> Unit)? = null
        ) {
            val ignoredSubtreeTags = setOf(
                "head", "style", "script",
                "office-automatic-styles", "automatic-styles",
                "office-styles", "styles", "master-styles",
                "font-face-decls", "file-info", "document-info", "publish-info",
                "custom-info", "stylesheet", "latentstyles",
                "fallback", "del", "deltext", "instrtext"
            )
            val blockBreakTags = setOf(
                "p", "h", "h1", "h2", "h3", "h4", "h5", "h6",
                "div", "li", "tr", "table-row", "row",
                "title", "subtitle", "epigraph", "annotation", "section", "v", "stanza",
                "br", "line-break"
            )

            var ignoreDepth = 0
            var inBinaryTag = false
            val binaryBase64Builder = StringBuilder()

            var inRun = false
            var inRPr = false
            var rPrBold = false
            var rPrItalic = false
            var rPrUnderline = false
            var rPrStrike = false
            var rPrVertAlign = ""
            var inRunText = false
            val runTextBuilder = StringBuilder()

            var activeHeadingLevel = 0
            var inListItem = false
            var listIndentLevel = 0
            var inTableCell = false
            var firstCellInRow = true

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && out.length < 8_000_000) {
                val rawName = parser.name ?: ""
                val localName = rawName.substringAfterLast(':').lowercase(Locale.ROOT)

                when (eventType) {
                    XmlPullParser.DOCDECL -> {
                        throw SecurityException("Обнаружено объявление DOCTYPE/DTD (защита от XXE-инъекций).")
                    }
                    XmlPullParser.ENTITY_REF -> {
                        val resolved = parser.text
                        if (resolved.isNullOrEmpty()) {
                            throw SecurityException("Попытка обращения к внешней сущности XML (XXE).")
                        }
                    }
                    XmlPullParser.START_TAG -> {
                        if (ignoreDepth > 0) {
                            ignoreDepth++
                        } else if (localName == "binary" || localName == "binarydata") {
                            inBinaryTag = true
                            binaryBase64Builder.setLength(0)
                        } else if (localName in ignoredSubtreeTags) {
                            ignoreDepth = 1
                        } else {
                            when (localName) {
                                "blip", "imagedata", "image", "img" -> {
                                    if (onInlineImageRef != null) {
                                        for (aIdx in 0 until parser.attributeCount) {
                                            val aName = parser.getAttributeName(aIdx)?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: continue
                                            if (aName in setOf("embed", "id", "href", "src")) {
                                                val aVal = parser.getAttributeValue(aIdx)?.trim()?.removePrefix("#")
                                                if (!aVal.isNullOrEmpty()) {
                                                    onInlineImageRef(aVal)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                "h1", "title" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("<h1>")
                                    activeHeadingLevel = 1
                                }
                                "h2", "h3", "subtitle" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("<h2>")
                                    activeHeadingLevel = 2
                                }
                                "h" -> {
                                    val lvl = parser.getAttributeValue(null, "outline-level")?.toIntOrNull() ?: 1
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    if (lvl <= 1) {
                                        out.append("<h1>")
                                        activeHeadingLevel = 1
                                    } else {
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                }
                                "li", "list-item" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("• ")
                                    inListItem = true
                                }
                                "p" -> {
                                    val pStyle = parser.getAttributeValue(null, "style-name")?.lowercase(Locale.ROOT) ?: ""
                                    if (pStyle.contains("heading") || pStyle.contains("title")) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                    listIndentLevel = 0
                                }
                                "pstyle" -> {
                                    val valAttr = parser.getAttributeValue(null, "val")?.lowercase(Locale.ROOT) ?: ""
                                    if ((valAttr.contains("heading1") || valAttr == "title") && activeHeadingLevel == 0) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h1>")
                                        activeHeadingLevel = 1
                                    } else if ((valAttr.contains("heading") || valAttr.contains("subtitle")) && activeHeadingLevel == 0) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                }
                                "ilvl" -> {
                                    listIndentLevel = (parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 0).coerceIn(0, 6)
                                }
                                "numpr" -> {
                                    if (!inListItem && !inTableCell) {
                                        if (out.isNotEmpty() && !out.endsWith("\n") && !out.endsWith("• ") && !out.endsWith("◦ ")) {
                                            out.append('\n')
                                        }
                                        if (!out.endsWith("• ") && !out.endsWith("◦ ")) {
                                            repeat(listIndentLevel) { out.append("  ") }
                                            out.append(if (listIndentLevel > 0) "◦ " else "• ")
                                        }
                                        inListItem = true
                                    }
                                }
                                "tr", "table-row", "row" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    firstCellInRow = true
                                }
                                "tc", "table-cell", "td", "th" -> {
                                    if (!firstCellInRow) {
                                        out.append('\t')
                                    }
                                    firstCellInRow = false
                                    inTableCell = true
                                }
                                "r" -> {
                                    inRun = true
                                    rPrBold = false
                                    rPrItalic = false
                                    rPrUnderline = false
                                    rPrStrike = false
                                    rPrVertAlign = ""
                                    runTextBuilder.setLength(0)
                                }
                                "rpr" -> {
                                    if (inRun) inRPr = true
                                }
                                "b", "strong" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrBold = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (!inRun) {
                                        out.append("<b>")
                                    }
                                }
                                "i", "emphasis", "em" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrItalic = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (!inRun) {
                                        out.append("<i>")
                                    }
                                }
                                "u" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrUnderline = (valAttr == null || (!valAttr.equals("none", true) && valAttr != "0"))
                                    } else if (!inRun) {
                                        out.append("<u>")
                                    }
                                }
                                "strike", "dstrike", "s" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrStrike = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (localName == "s" && !inRun) {
                                        val c = (parser.getAttributeValue(null, "c")?.toIntOrNull() ?: 0)
                                        if (c > 0) {
                                            repeat(c.coerceIn(1, 32)) { out.append(' ') }
                                        } else {
                                            out.append("<s>")
                                        }
                                    }
                                }
                                "vertalign" -> {
                                    if (inRPr) {
                                        val v = parser.getAttributeValue(null, "val")?.lowercase(Locale.ROOT) ?: ""
                                        rPrVertAlign = when (v) {
                                            "subscript" -> "sub"
                                            "superscript" -> "sup"
                                            else -> ""
                                        }
                                    }
                                }
                                "sym" -> {
                                    val charHex = parser.getAttributeValue(null, "char")
                                    if (!charHex.isNullOrBlank()) {
                                        val code = charHex.toIntOrNull(16)
                                        if (code != null) {
                                            val mapped = if (code in 0xF020..0xF0FF) (code - 0xF000).toChar() else code.toChar()
                                            if (inRun) runTextBuilder.append(mapped) else out.append(mapped)
                                        }
                                    }
                                }
                                "t" -> {
                                    if (inRun) inRunText = true
                                }
                                "tab" -> {
                                    if (inRun) runTextBuilder.append('\t') else out.append('\t')
                                }
                                "br", "line-break" -> {
                                    if (inRun) {
                                        runTextBuilder.append(if (inTableCell) ' ' else '\n')
                                    } else {
                                        out.append(if (inTableCell) ' ' else '\n')
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inBinaryTag) {
                            if (binaryBase64Builder.length < 24 * 1024 * 1024) {
                                binaryBase64Builder.append(parser.text ?: "")
                            }
                        } else if (ignoreDepth == 0) {
                            val rawText = parser.text ?: ""
                            if (inRun) {
                                if (inRunText && runTextBuilder.length < 65536) {
                                    runTextBuilder.append(rawText)
                                }
                            } else {
                                val normalized = rawText.replace(Regex("[\\r\\n\\t]+"), " ")
                                if (normalized.isNotBlank()) {
                                    val trimmed = normalized.trim()
                                    if (out.isNotEmpty()) {
                                        val lastCh = out.last()
                                        if (lastCh != '\n' && lastCh != '\t' && lastCh != ' ' && lastCh != '>' &&
                                            !trimmed.startsWith(",") && !trimmed.startsWith(".") &&
                                            !trimmed.startsWith(";") && !trimmed.startsWith(":") &&
                                            !trimmed.startsWith("!") && !trimmed.startsWith("?")
                                        ) {
                                            out.append(' ')
                                        }
                                    }
                                    out.append(trimmed)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (inBinaryTag && (localName == "binary" || localName == "binarydata")) {
                            inBinaryTag = false
                            if (onBinaryImageDecoded != null && binaryBase64Builder.length in 32..(24 * 1024 * 1024)) {
                                try {
                                    val decoded = Base64.decode(binaryBase64Builder.toString(), Base64.DEFAULT)
                                    if (decoded.isNotEmpty()) {
                                        onBinaryImageDecoded(decoded)
                                    }
                                } catch (_: Throwable) {}
                            }
                            binaryBase64Builder.setLength(0)
                        } else if (ignoreDepth > 0) {
                            ignoreDepth--
                        } else {
                            when (localName) {
                                "t" -> inRunText = false
                                "rpr" -> inRPr = false
                                "r" -> {
                                    if (inRun) {
                                        val runStr = runTextBuilder.toString()
                                        if (runStr.isNotEmpty()) {
                                            if (rPrBold) out.append("<b>")
                                            if (rPrItalic) out.append("<i>")
                                            if (rPrUnderline) out.append("<u>")
                                            if (rPrStrike) out.append("<s>")
                                            if (rPrVertAlign.isNotEmpty()) out.append("<$rPrVertAlign>")
                                            out.append(runStr)
                                            if (rPrVertAlign.isNotEmpty()) out.append("</$rPrVertAlign>")
                                            if (rPrStrike) out.append("</s>")
                                            if (rPrUnderline) out.append("</u>")
                                            if (rPrItalic) out.append("</i>")
                                            if (rPrBold) out.append("</b>")
                                        }
                                        inRun = false
                                        inRPr = false
                                        rPrBold = false
                                        rPrItalic = false
                                        rPrUnderline = false
                                        rPrStrike = false
                                        rPrVertAlign = ""
                                        runTextBuilder.setLength(0)
                                    }
                                }
                                "b", "strong" -> if (!inRPr && !inRun) out.append("</b>")
                                "i", "emphasis", "em" -> if (!inRPr && !inRun) out.append("</i>")
                                "u" -> if (!inRPr && !inRun) out.append("</u>")
                                "h1", "title", "h2", "h3", "subtitle", "h" -> {
                                    if (activeHeadingLevel == 1) out.append("</h1>")
                                    else if (activeHeadingLevel == 2) out.append("</h2>")
                                    activeHeadingLevel = 0
                                    if (!out.endsWith("\n")) out.append('\n')
                                }
                                "tc", "table-cell", "td", "th" -> {
                                    while (out.isNotEmpty() && out.last() == ' ') {
                                        out.deleteCharAt(out.length - 1)
                                    }
                                    inTableCell = false
                                }
                                "tr", "table-row", "row" -> {
                                    inTableCell = false
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                }
                                "li", "list-item" -> {
                                    inListItem = false
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                }
                                in blockBreakTags -> {
                                    if (localName == "p") {
                                        inRun = false
                                        inRPr = false
                                        rPrBold = false
                                        rPrItalic = false
                                        rPrUnderline = false
                                        rPrStrike = false
                                        rPrVertAlign = ""
                                        if (activeHeadingLevel == 1) {
                                            out.append("</h1>")
                                            activeHeadingLevel = 0
                                        } else if (activeHeadingLevel == 2) {
                                            out.append("</h2>")
                                            activeHeadingLevel = 0
                                        }
                                        inListItem = false
                                        listIndentLevel = 0
                                    }
                                    if (inTableCell) {
                                        if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\t') {
                                            out.append(' ')
                                        }
                                    } else {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) {
                                            out.append('\n')
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        private val INLINE_IMAGE_TAG_REGEX = Regex("""\[IMAGE:(\d+)]""")

        /**
         * Paginates formatted rich text into full-screen pages.
         * Uses calibrated document line density (`charsPerVisualLine = 68`, `maxVisualLinesPerPage = 46`)
         * so that even fallback raster pages fill 100% of the vertical screen height.
         */
        fun paginateRichText(full: String, imageCount: Int, targetPages: MutableList<String>) {
            targetPages.clear()
            val cleaned = full.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (cleaned.isEmpty()) {
                if (imageCount > 0) {
                    for (i in 0 until imageCount) {
                        targetPages.add("[IMAGE:$i]")
                    }
                } else {
                    targetPages.add("")
                }
                return
            }

            val rawLines = cleaned.split('\n')
            val charsPerVisualLine = 68
            val maxVisualLinesPerPage = 46
            val imageVisualLinesCost = 14

            val currentPageLines = ArrayList<String>()
            var currentVisualLines = 0
            var imagesOnCurrentPage = 0

            fun flushCurrentPage() {
                if (currentPageLines.isNotEmpty()) {
                    targetPages.add(currentPageLines.joinToString("\n").trim())
                    currentPageLines.clear()
                    currentVisualLines = 0
                    imagesOnCurrentPage = 0
                }
            }

            for (rawLine in rawLines) {
                val trimmed = rawLine.trimEnd()
                if (trimmed.isEmpty()) continue

                if (INLINE_IMAGE_TAG_REGEX.matches(trimmed.trim())) {
                    if (imagesOnCurrentPage >= 1 ||
                        (currentVisualLines > 0 && currentVisualLines + imageVisualLinesCost > maxVisualLinesPerPage)
                    ) {
                        flushCurrentPage()
                    }
                    currentPageLines.add(trimmed.trim())
                    currentVisualLines += imageVisualLinesCost
                    imagesOnCurrentPage++
                    continue
                }

                val isTableLine = trimmed.contains('\t')
                val isHeading1 = trimmed.contains("<h1>")
                val isHeading2 = trimmed.contains("<h2>")

                val plainVisible = trimmed
                    .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                    .replace(INLINE_IMAGE_TAG_REGEX, "")

                val lineVisualCost = when {
                    isTableLine -> 2
                    isHeading1 -> maxOf(2, (plainVisible.length + 39) / 40 + 1)
                    isHeading2 -> maxOf(2, (plainVisible.length + 49) / 50 + 1)
                    else -> maxOf(1, (plainVisible.length + charsPerVisualLine - 1) / charsPerVisualLine)
                }

                if (lineVisualCost > maxVisualLinesPerPage && !isTableLine) {
                    flushCurrentPage()
                    appendSplitOverlongBlock(trimmed, charsPerVisualLine * maxVisualLinesPerPage, targetPages)
                    continue
                }

                if (currentVisualLines + lineVisualCost > maxVisualLinesPerPage && currentPageLines.isNotEmpty()) {
                    flushCurrentPage()
                }

                currentPageLines.add(trimmed)
                currentVisualLines += lineVisualCost
            }

            flushCurrentPage()
            if (targetPages.isEmpty()) {
                targetPages.add("")
            }
        }

        private fun appendSplitOverlongBlock(
            block: String,
            maxCharsPerPage: Int,
            targetPages: MutableList<String>
        ) {
            val plain = block.replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
            var start = 0
            while (start < plain.length) {
                var end = minOf(start + maxCharsPerPage, plain.length)
                if (end < plain.length) {
                    val spaceIdx = plain.lastIndexOf(' ', end)
                    if (spaceIdx > start + maxCharsPerPage / 2) {
                        end = spaceIdx
                    }
                }
                val slice = plain.substring(start, end).trim()
                if (slice.isNotEmpty()) {
                    targetPages.add(slice)
                }
                start = if (end > start) end else start + maxCharsPerPage
                while (start < plain.length && plain[start] == ' ') start++
            }
        }

        /**
         * Renders a rich-formatted document page (`<h1>`, `<h2>`, `<b>`, `<i>`, `<u>`, `<s>`,
         * bullets `• `, tables with `\t`, and `[IMAGE:n]` illustrations) onto [canvas].
         */
        fun renderRichDocumentPageToCanvas(
            canvas: Canvas,
            bitmapWidth: Int,
            bitmapHeight: Int,
            pageIndex: Int,
            tileX: Int,
            tileY: Int,
            zoom: Float,
            isLightTheme: Boolean,
            pageText: String,
            embeddedImages: List<Bitmap>,
            baseTextPaint: TextPaint
        ) {
            val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
            val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)

            val pageBgColor = if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C)
            val bodyTextColor = if (isLightTheme) Color.rgb(30, 32, 38) else Color.rgb(0xEC, 0xEF, 0xF4)
            val headingTextColor = if (isLightTheme) Color.rgb(18, 24, 42) else Color.rgb(0x89, 0xB4, 0xFA)
            val tableHeaderBg = if (isLightTheme) Color.rgb(232, 236, 245) else Color.rgb(0x26, 0x29, 0x3A)
            val tableCellBg = if (isLightTheme) Color.WHITE else Color.rgb(0x1E, 0x1E, 0x2E)
            val tableBorderCol = if (isLightTheme) Color.rgb(180, 188, 204) else Color.rgb(0x3B, 0x3E, 0x52)

            canvas.drawColor(pageBgColor)

            val imgMatch = INLINE_IMAGE_TAG_REGEX.find(pageText)
            val matchedImgIdx = imgMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val pageImg: Bitmap? = when {
                matchedImgIdx != null -> embeddedImages.getOrNull(matchedImgIdx)
                isIllustrationOnly && cleanPageIndex < embeddedImages.size -> embeddedImages[cleanPageIndex]
                else -> null
            }

            if (isIllustrationOnly) {
                if (pageImg != null && !pageImg.isRecycled) {
                    val pad = 16f
                    val availW = (bitmapWidth - pad * 2f).coerceAtLeast(40f)
                    val availH = (bitmapHeight - pad * 2f).coerceAtLeast(40f)
                    val aspect = pageImg.width.toFloat() / pageImg.height.toFloat().coerceAtLeast(1f)
                    var drawW = availW
                    var drawH = drawW / aspect
                    if (drawH > availH) {
                        drawH = availH
                        drawW = drawH * aspect
                    }
                    val left = (bitmapWidth - drawW) / 2f
                    val top = (bitmapHeight - drawH) / 2f
                    val dst = RectF(left, top, left + drawW, top + drawH)
                    canvas.drawBitmap(pageImg, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
                return
            }

            val marginH = 40f
            val marginV = 36f
            val contentWidth = (bitmapWidth - (marginH * 2).toInt()).coerceAtLeast(200)

            canvas.save()
            canvas.translate(marginH - tileX, marginV - tileY)
            canvas.scale(zoom, zoom)

            var curY = 0f
            val baseFontSize = 23f
            val tableFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val tableGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = tableBorderCol
            }

            val rawLines = pageText.split('\n')
            var lineIdx = 0
            while (lineIdx < rawLines.size) {
                val line = rawLines[lineIdx].trimEnd()
                if (line.isBlank()) {
                    curY += 10f
                    lineIdx++
                    continue
                }

                val inlineImg = INLINE_IMAGE_TAG_REGEX.matchEntire(line.trim())
                if (inlineImg != null) {
                    val idx = inlineImg.groupValues[1].toIntOrNull() ?: -1
                    val bmp = embeddedImages.getOrNull(idx)
                    if (bmp != null && !bmp.isRecycled) {
                        val maxImgH = (bitmapHeight * 0.34f).coerceIn(160f, 400f)
                        val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        var drawW = contentWidth.toFloat() * 0.88f
                        var drawH = drawW / aspect
                        if (drawH > maxImgH) {
                            drawH = maxImgH
                            drawW = drawH * aspect
                        }
                        val left = (contentWidth - drawW) / 2f
                        val dst = RectF(left, curY + 8f, left + drawW, curY + 8f + drawH)
                        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                        curY += drawH + 18f
                    }
                    lineIdx++
                    continue
                }

                if (line.contains('\t')) {
                    val tableBlock = ArrayList<List<String>>()
                    while (lineIdx < rawLines.size && rawLines[lineIdx].contains('\t')) {
                        val cells = rawLines[lineIdx]
                            .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                            .split('\t')
                            .map { it.trim() }
                        if (cells.any { it.isNotEmpty() }) {
                            tableBlock.add(cells)
                        }
                        lineIdx++
                    }
                    if (tableBlock.isNotEmpty()) {
                        val colCount = tableBlock.maxOf { it.size }.coerceIn(1, 12)
                        val colW = contentWidth.toFloat() / colCount
                        val cellPaint = TextPaint(baseTextPaint).apply {
                            textSize = 20f
                            color = bodyTextColor
                        }
                        for ((rIdx, rowCells) in tableBlock.withIndex()) {
                            var rowMaxH = 36f
                            val cellLayouts = ArrayList<StaticLayout?>(colCount)
                            for (cIdx in 0 until colCount) {
                                val cellTxt = rowCells.getOrNull(cIdx) ?: ""
                                cellPaint.isFakeBoldText = (rIdx == 0)
                                if (cellTxt.isNotEmpty()) {
                                    val l = StaticLayout.Builder
                                        .obtain(cellTxt, 0, cellTxt.length, cellPaint, (colW - 14f).toInt().coerceAtLeast(24))
                                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                        .setIncludePad(false)
                                        .build()
                                    cellLayouts.add(l)
                                    rowMaxH = maxOf(rowMaxH, l.height + 14f)
                                } else {
                                    cellLayouts.add(null)
                                }
                            }
                            for (cIdx in 0 until colCount) {
                                val cellRect = RectF(cIdx * colW, curY, (cIdx + 1) * colW, curY + rowMaxH)
                                tableFillPaint.color = if (rIdx == 0) tableHeaderBg else tableCellBg
                                canvas.drawRect(cellRect, tableFillPaint)
                                canvas.drawRect(cellRect, tableGridPaint)
                                val layout = cellLayouts[cIdx]
                                if (layout != null) {
                                    canvas.save()
                                    canvas.clipRect(cellRect)
                                    canvas.translate(cellRect.left + 7f, cellRect.top + 7f)
                                    layout.draw(canvas)
                                    canvas.restore()
                                }
                            }
                            curY += rowMaxH
                        }
                        curY += 14f
                    }
                    continue
                }

                val isH1 = line.contains("<h1>")
                val isH2 = line.contains("<h2>")
                val hasBold = line.contains("<b>")
                val hasItalic = line.contains("<i>")
                val hasUnderline = line.contains("<u>")
                val hasStrike = line.contains("<s>")

                val cleanLine = line
                    .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                    .replace(INLINE_IMAGE_TAG_REGEX, "")
                    .trim()

                if (cleanLine.isNotEmpty()) {
                    val paraPaint = TextPaint(baseTextPaint).apply {
                        color = if (isH1 || isH2) headingTextColor else bodyTextColor
                        textSize = when {
                            isH1 -> baseFontSize * 1.32f
                            isH2 -> baseFontSize * 1.18f
                            else -> baseFontSize
                        }
                        typeface = when {
                            (isH1 || isH2 || hasBold) && hasItalic -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                            isH1 || isH2 || hasBold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                            hasItalic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                            else -> Typeface.SERIF
                        }
                        isUnderlineText = hasUnderline
                        isStrikeThruText = hasStrike
                    }

                    if (isH1 || isH2) {
                        curY += 6f
                    }

                    val layout = StaticLayout.Builder
                        .obtain(cleanLine, 0, cleanLine.length, paraPaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(3f, 1.12f)
                        .setIncludePad(false)
                        .build()

                    canvas.save()
                    canvas.translate(0f, curY)
                    layout.draw(canvas)
                    canvas.restore()

                    curY += layout.height + if (isH1 || isH2) 14f else 8f
                }

                lineIdx++
            }

            canvas.restore()
        }
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = parseError

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        if (parseError != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
        } else {
            cleanPageIndex
        }
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)

        renderRichDocumentPageToCanvas(
            canvas = canvas,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pageIndex = effectivePageIndex,
            tileX = tileX,
            tileY = tileY,
            zoom = zoom,
            isLightTheme = isLightTheme,
            pageText = getPageText(cleanPageIndex),
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
        val safeStart = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in safeStart until pages.size) {
            if (pages[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until safeStart) {
            if (pages[i].contains(query, ignoreCase = true)) return i.toLong()
        }
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
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val maxPossibleRecords = ((bytes.size - 78) / 8).coerceAtLeast(0)
        val numRecords = (bb.getShort(76).toInt() and 0xFFFF).coerceIn(0, minOf(16384, maxPossibleRecords))
        if (numRecords < 2 || 78 + numRecords * 8 > bytes.size) {
            lastErrorMsg = "Некорректная таблица записей PalmDB ($numRecords записей)."
            return
        }

        val recordOffsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            recordOffsets[i] = bb.getInt(78 + i * 8)
        }

        val rec0Start = recordOffsets[0]
        val rec0End = if (numRecords > 1) recordOffsets[1] else bytes.size
        if (rec0Start < 0 || rec0Start + 16 > bytes.size || rec0End <= rec0Start) {
            lastErrorMsg = "Повреждён нулевой заголовок PalmDOC."
            return
        }

        val compression = bb.getShort(rec0Start).toInt() and 0xFFFF
        val textRecCount = (bb.getShort(rec0Start + 8).toInt() and 0xFFFF).coerceIn(1, numRecords - 1)
        val encryptionType = bb.getShort(rec0Start + 12).toInt() and 0xFFFF

        if (encryptionType != 0) {
            lastErrorMsg = "Данная книга защищена криптографической DRM-защитой (Encryption Type $encryptionType). Обход технических средств защиты авторских прав (TPM/DRM) запрещён законом (DMCA § 1201)."
            return
        }

        var charset: Charset = StandardCharsets.UTF_8
        if (rec0Start + 28 <= rec0End &&
            bytes[rec0Start + 16] == 'M'.code.toByte() &&
            bytes[rec0Start + 17] == 'O'.code.toByte() &&
            bytes[rec0Start + 18] == 'B'.code.toByte() &&
            bytes[rec0Start + 19] == 'I'.code.toByte()
        ) {
            val codepage = bb.getInt(rec0Start + 28)
            if (codepage == 1251) charset = PlainTextSession.CP1251
            else if (codepage == 1252) charset = Charset.forName("windows-1252")
        }

        if (compression == 17480) {
            embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(bytes, 16))
            fullText = CleanRoomDocSession.extractPrintableRunsFromBinary(bytes)
            return
        }

        val rawTextOut = ByteArrayOutputStream(minOf(bytes.size * 2, 16 * 1024 * 1024))
        for (recIdx in 1..textRecCount) {
            if (recIdx >= numRecords || rawTextOut.size() >= 16 * 1024 * 1024) break
            val start = recordOffsets[recIdx]
            val end = if (recIdx + 1 < numRecords) recordOffsets[recIdx + 1] else bytes.size
            if (start < 0 || end <= start || end > bytes.size) continue

            val recLen = end - start
            when (compression) {
                1 -> rawTextOut.write(bytes, start, recLen)
                2 -> {
                    val decompressed = decompressPalmDocLz77(bytes, start, end)
                    rawTextOut.write(decompressed)
                }
                else -> rawTextOut.write(bytes, start, recLen)
            }
        }

        val afterTextOffset = if (textRecCount + 1 < numRecords) recordOffsets[textRecCount + 1].coerceIn(0, bytes.size) else bytes.size
        if (afterTextOffset < bytes.size) {
            val imgSlice = bytes.copyOfRange(afterTextOffset, bytes.size)
            embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(imgSlice, 16))
        }

        val rawHtmlOrMarkup = String(rawTextOut.toByteArray(), charset)
        fullText = stripMobiHtmlToRichText(rawHtmlOrMarkup, embeddedImages.size)
    }

    private fun decompressPalmDocLz77(data: ByteArray, start: Int, end: Int): ByteArray {
        val maxRecordOutputBytes = 65536
        val bos = ByteArrayOutputStream(minOf((end - start) * 2, 8192))
        var p = start
        while (p < end && bos.size() < maxRecordOutputBytes) {
            val b = data[p++].toInt() and 0xFF
            when {
                b == 0x00 -> bos.write(0)
                b in 0x01..0x08 -> {
                    val count = minOf(b, end - p, maxRecordOutputBytes - bos.size())
                    if (count > 0) {
                        bos.write(data, p, count)
                        p += count
                    }
                }
                b in 0x09..0x7F -> bos.write(b)
                b in 0x80..0xBF -> {
                    if (p >= end) break
                    val b2 = data[p++].toInt() and 0xFF
                    val pair = (b shl 8) or b2
                    val distance = (pair ushr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    if (distance > 0 && distance <= bos.size()) {
                        val currentBytes = bos.toByteArray()
                        var copyPos = currentBytes.size - distance
                        val safeLen = minOf(length, maxRecordOutputBytes - bos.size())
                        for (k in 0 until safeLen) {
                            val copiedByte = if (copyPos < currentBytes.size) {
                                currentBytes[copyPos].toInt()
                            } else {
                                bos.toByteArray()[copyPos].toInt()
                            }
                            bos.write(copiedByte)
                            copyPos++
                        }
                    }
                }
                b in 0xC0..0xFF -> {
                    bos.write(' '.code)
                    if (bos.size() < maxRecordOutputBytes) {
                        bos.write(b xor 0x80)
                    }
                }
            }
        }
        return bos.toByteArray()
    }

    private fun stripMobiHtmlToRichText(html: String, imageCount: Int): String {
        val withBreaks = html
            .replace(Regex("<head[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<(br|p|div|tr|li|blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<h1[^>]*>", RegexOption.IGNORE_CASE), "\n<h1>")
            .replace(Regex("</h1>", RegexOption.IGNORE_CASE), "</h1>\n")
            .replace(Regex("<h[2-4][^>]*>", RegexOption.IGNORE_CASE), "\n<h2>")
            .replace(Regex("</h[2-4]>", RegexOption.IGNORE_CASE), "</h2>\n")
            .replace(Regex("<(b|strong)[^>]*>", RegexOption.IGNORE_CASE), "<b>")
            .replace(Regex("</(b|strong)>", RegexOption.IGNORE_CASE), "</b>")
            .replace(Regex("<(i|em)[^>]*>", RegexOption.IGNORE_CASE), "<i>")
            .replace(Regex("</(i|em)>", RegexOption.IGNORE_CASE), "</i>")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")

        val sb = StringBuilder()
        if (imageCount > 0) {
            sb.append("[IMAGE:0]\n")
        }
        for (line in withBreaks.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                sb.append(trimmed).append('\n')
            }
        }
        for (imgIdx in 1 until imageCount) {
            sb.append("[IMAGE:$imgIdx]\n")
        }
        return sb.toString().trim()
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        if (lastErrorMsg != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
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

class KindleDrmNoticeSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat
) : DocumentSession {
    @Volatile private var isClosed = false
    override val pageCount: Int = 1

    override fun getLastError(): String =
        "Данная книга (${format.name}) защищена криптографической DRM-защитой Amazon. " +
                "Обход технических средств защиты авторских прав (TPM/DRM) запрещён законом (DMCA § 1201 / Директива ЕС 2001/29/EC)."

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        bitmap.eraseColor(if (isLight) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}

class PlainTextSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)
    private val chunkSize = 4000L

    private var detectedCharset: Charset = StandardCharsets.UTF_8
    private var bomSkipBytes: Int = 0
    private var decompiledBinaryXmlText: String? = null

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
                if (AndroidBinaryXmlDecompiler.isBinaryXmlOrArsc(arr, r)) {
                    val fullAxmLen = minOf(totalSize, 16L * 1024L * 1024L).toInt()
                    val fullAxmBuf = ByteBuffer.allocateDirect(fullAxmLen)
                    val gotAxm = source.readAt(0L, fullAxmBuf)
                    if (gotAxm > 0) {
                        fullAxmBuf.flip()
                        val axmBytes = ByteArray(gotAxm)
                        fullAxmBuf.get(axmBytes)
                        decompiledBinaryXmlText = AndroidBinaryXmlDecompiler.decompile(axmBytes)
                    }
                }
                if (decompiledBinaryXmlText == null) {
                    try {
                        val result = detectCharsetAndValidateText(arr, r)
                        detectedCharset = result.first
                        bomSkipBytes = result.second
                    } catch (e: IllegalArgumentException) {
                        lastErrorMsg = e.message
                    }
                }
            }
        }
        val effectiveSize = if (decompiledBinaryXmlText != null) {
            decompiledBinaryXmlText!!.length.toLong()
        } else {
            (totalSize - bomSkipBytes).coerceAtLeast(0L)
        }
        pageCount = ((effectiveSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
    }

    override fun getLastError(): String? = lastErrorMsg

    companion object {
        val CP1251: Charset = try { Charset.forName("windows-1251") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }
        val KOI8_R: Charset = try { Charset.forName("KOI8-R") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }
        val IBM866: Charset = try { Charset.forName("IBM866") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }

        fun detectCharsetAndValidateText(sample: ByteArray, length: Int): Pair<Charset, Int> {
            if (length >= 3 &&
                (sample[0].toInt() and 0xFF) == 0xEF &&
                (sample[1].toInt() and 0xFF) == 0xBB &&
                (sample[2].toInt() and 0xFF) == 0xBF
            ) {
                return StandardCharsets.UTF_8 to 3
            }
            if (length >= 2 &&
                (sample[0].toInt() and 0xFF) == 0xFF &&
                (sample[1].toInt() and 0xFF) == 0xFE
            ) {
                return StandardCharsets.UTF_16LE to 2
            }
            if (length >= 2 &&
                (sample[0].toInt() and 0xFF) == 0xFE &&
                (sample[1].toInt() and 0xFF) == 0xFF
            ) {
                return StandardCharsets.UTF_16BE to 2
            }

            if (length >= 16) {
                var evenNulls = 0
                var oddNulls = 0
                val pairs = length / 2
                for (i in 0 until pairs * 2 step 2) {
                    if (sample[i].toInt() == 0) evenNulls++
                    if (sample[i + 1].toInt() == 0) oddNulls++
                }
                if (oddNulls > pairs * 0.6 && evenNulls < pairs * 0.1) {
                    return StandardCharsets.UTF_16LE to 0
                }
                if (evenNulls > pairs * 0.6 && oddNulls < pairs * 0.1) {
                    return StandardCharsets.UTF_16BE to 0
                }
            }

            var suspiciousControlBytes = 0
            for (i in 0 until length) {
                val b = sample[i].toInt() and 0xFF
                if (b == 0x00 || (b in 1..8) || (b in 14..31 && b != 27)) {
                    suspiciousControlBytes++
                }
            }
            if (length > 0 && suspiciousControlBytes.toDouble() / length.toDouble() > 0.10) {
                throw IllegalArgumentException("Binary content is not a text document.")
            }

            if (isValidUtf8(sample, length)) {
                return StandardCharsets.UTF_8 to 0
            }

            return detectBest8BitCyrillicCharset(sample, length) to 0
        }

        private fun isValidUtf8(bytes: ByteArray, len: Int): Boolean {
            var i = 0
            while (i < len) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b <= 0x7F -> i++
                    b in 0xC2..0xDF -> {
                        if (i + 1 >= len) return true
                        if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                        i += 2
                    }
                    b in 0xE0..0xEF -> {
                        if (i + 2 >= len) return true
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80) return false
                        if (b == 0xE0 && b1 < 0xA0) return false
                        if (b == 0xED && b1 >= 0xA0) return false
                        i += 3
                    }
                    b in 0xF0..0xF4 -> {
                        if (i + 3 >= len) return true
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        val b3 = bytes[i + 3].toInt() and 0xFF
                        if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) return false
                        i += 4
                    }
                    else -> return false
                }
            }
            return true
        }

        private fun detectBest8BitCyrillicCharset(sample: ByteArray, length: Int): Charset {
            var highByteCount = 0
            var cp1251Lower = 0
            var cp1251Upper = 0
            var ibm866BoxOrControl = 0

            for (i in 0 until length) {
                val b = sample[i].toInt() and 0xFF
                if (b >= 0x80) {
                    highByteCount++
                    if (b in 0xE0..0xFF) cp1251Lower++
                    else if (b in 0xC0..0xDF) cp1251Upper++
                    if (b in 0xB0..0xDF) ibm866BoxOrControl++
                }
            }
            if (highByteCount == 0) return StandardCharsets.UTF_8

            if (cp1251Lower + cp1251Upper > highByteCount * 0.70 && cp1251Lower >= cp1251Upper) {
                return CP1251
            }

            val candidates = listOf(CP1251, KOI8_R, IBM866)
            var bestCharset = CP1251
            var bestScore = Int.MIN_VALUE

            for (cs in candidates) {
                val decoded = String(sample, 0, length, cs)
                var score = 0
                for (ch in decoded) {
                    when (ch) {
                        in 'а'..'я', 'ё' -> score += 3
                        in 'А'..'Я', 'Ё' -> score += 2
                        in '\u2500'..'\u257F' -> score -= 4
                    }
                }
                if (cs == IBM866 && ibm866BoxOrControl > highByteCount / 3) {
                    score -= ibm866BoxOrControl * 2
                }
                if (score > bestScore) {
                    bestScore = score
                    bestCharset = cs
                }
            }
            return bestCharset
        }
    }

    override fun getPageText(pageIndex: Int): String {
        if (isClosed || lastErrorMsg != null) return ""
        decompiledBinaryXmlText?.let { decompiled ->
            if (pageIndex == -1) return decompiled
            val cleanIdx = pageIndex and PAGE_INDEX_MASK
            val start = (cleanIdx * chunkSize).toInt().coerceAtMost(decompiled.length)
            val end = minOf(decompiled.length, start + chunkSize.toInt())
            return if (end > start) decompiled.substring(start, end) else ""
        }

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
