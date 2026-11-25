/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import me.hlebkukleuski.fullfilestudio.core.security.CcittFaxDecoder
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

/**
 * 100% Clean-room TIFF 6.0 decoder (ISO 12639 / Adobe TIFF 6.0 & Tech Note #2 specification).
 * Supports Little-Endian & Big-Endian multi-page IFDs, Strip and Tile layouts (tags 322–325),
 * Uncompressed (1), LZW (5), Old & New JPEG-in-TIFF (6 / 7 + JPEGTables tag 347 & 513/514),
 * Deflate (8 / 32946), PackBits (32773), Horizontal Predictor (2), Bilevel, Grayscale, Palette, RGB, RGBA, YCbCr, CMYK.
 */
object TiffDecoder {

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
        val orientation: Int,
        val fillOrder: Int = 1,
        val t4Options: Int = 0
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
            var fillOrder = 1
            var t4Options = 0

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
                    266 -> fillOrder = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 1
                    292 -> t4Options = readTagInts(bb, bytes.size, type, count, valOffsetField).firstOrNull() ?: 0
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
                        orientation = orientation,
                        fillOrder = fillOrder,
                        t4Options = t4Options
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
                    2, 3, 4 -> decodeCcitt(fileBytes, offset, safeCompLen, tw, tl, ifd)
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
                2, 3, 4 -> decodeCcitt(fileBytes, offset, safeCompLen, srcW, rowsInThisStrip, ifd)
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

    private fun decodeCcitt(fileBytes: ByteArray, offset: Int, length: Int, width: Int, rows: Int, ifd: IfdDirectory): ByteArray? {
        return try {
            CcittFaxDecoder.decode(fileBytes, offset, length, width, rows, ifd.compression, ifd.t4Options, ifd.fillOrder == 2)
        } catch (_: RuntimeException) {
            null
        }
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
