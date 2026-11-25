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
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object EmbeddedImageScanner {
    fun scanBinaryForImages(data: ByteArray, maxImages: Int = 16): List<Bitmap> {
        val result = ArrayList<Bitmap>()
        var i = 0
        while (i + 16 < data.size && result.size < maxImages) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            val b3 = data[i + 3].toInt() and 0xFF

            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                var endPos = i + 8
                while (endPos + 8 <= data.size) {
                    if (data[endPos] == 'I'.code.toByte() &&
                        data[endPos + 1] == 'E'.code.toByte() &&
                        data[endPos + 2] == 'N'.code.toByte() &&
                        data[endPos + 3] == 'D'.code.toByte()
                    ) {
                        endPos += 8
                        break
                    }
                    endPos++
                }
                val len = (endPos - i).coerceAtMost(data.size - i)
                if (len in 64..(12 * 1024 * 1024)) {
                    decodeSampledBitmap(data, i, len)?.let { result.add(it) }
                    i = endPos
                    continue
                }
            }

            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                var endPos = i + 4
                while (endPos + 1 < data.size) {
                    if ((data[endPos].toInt() and 0xFF) == 0xFF &&
                        (data[endPos + 1].toInt() and 0xFF) == 0xD9
                    ) {
                        endPos += 2
                        break
                    }
                    endPos++
                }
                val len = (endPos - i).coerceAtMost(data.size - i)
                if (len in 128..(12 * 1024 * 1024)) {
                    decodeSampledBitmap(data, i, len)?.let { result.add(it) }
                    i = endPos
                    continue
                }
            }
            i++
        }
        return result
    }

    fun decodeSampledBitmap(data: ByteArray, offset: Int, len: Int): Bitmap? {
        if (offset < 0 || len <= 16 || offset + len > data.size) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, offset, len, bounds)
            if (bounds.outWidth <= 4 || bounds.outHeight <= 4) return null
            var sample = 1
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(data, offset, len, opts)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reassembles split EMFstreams embedded inside Windows Metafile `MFCOMMENT` (`WMFC` = `0x43464D57`)
     * escape records (used by Word and LibreOffice in `\wmetafile8` RTF pictures).
     */
    private fun reassembleWmfcChunksIfPresent(bytes: ByteArray): ByteArray? {
        if (bytes.size < 64) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var bos: ByteArrayOutputStream? = null
        val limit = bytes.size - 34

        while (pos <= limit) {
            if (bytes[pos] == 0x57.toByte() &&
                bytes[pos + 1] == 0x4D.toByte() &&
                bytes[pos + 2] == 0x46.toByte() &&
                bytes[pos + 3] == 0x43.toByte()
            ) {
                val flagsAndVer = bb.getInt(pos + 4)
                if (flagsAndVer == 0x00000001) {
                    val chunkDataSize = bb.getInt(pos + 20)
                    val payloadStart = pos + 34
                    if (chunkDataSize in 1..(bytes.size - payloadStart)) {
                        if (bos == null) {
                            bos = ByteArrayOutputStream(minOf(bytes.size, 4 * 1024 * 1024))
                        }
                        bos.write(bytes, payloadStart, chunkDataSize)
                        pos = payloadStart + chunkDataSize
                        continue
                    }
                }
            }
            pos++
        }
        return if (bos != null && bos.size() >= 44) bos.toByteArray() else null
    }

    /**
     * Clean-room decoder for Windows Metafiles (`WMF` / `\wmetafile8`), Enhanced Metafiles (`EMF` / `\emfblip`),
     * headless Device-Independent Bitmaps (`DIB` / `BITMAPINFOHEADER`), and embedded `<svg>` graphics.
     */
    fun decodeMetafileOrSvgBitmap(data: ByteArray, offset: Int = 0, len: Int = data.size - offset): Bitmap? {
        if (offset < 0 || len < 24 || offset + len > data.size) return null

        decodeSampledBitmap(data, offset, len)?.let { return it }

        val slice = if (offset == 0 && len == data.size) data else data.copyOfRange(offset, offset + len)

        if (MetafileRenderer.isWmf(slice) || MetafileRenderer.isEmf(slice)) {
            MetafileRenderer.decode(slice)?.rasterize(2048)?.let { return it }
        }

        val embeddedList = scanBinaryForImages(slice, 1)
        if (embeddedList.isNotEmpty()) return embeddedList[0]

        if (me.hlebkukleuski.fullfilestudio.core.document.svg.SvgDocumentSession.looksLikeSvg(slice)) {
            me.hlebkukleuski.fullfilestudio.core.document.svg.SvgDocumentSession.rasterize(slice, 1536)?.let { return it }
        }

        val reassembledEmf = reassembleWmfcChunksIfPresent(slice)
        if (reassembledEmf != null) {
            extractBestDibFromMetafile(reassembledEmf)?.let { return it }
        }

        return extractBestDibFromMetafile(slice)
    }

    private fun extractBestDibFromMetafile(bytes: ByteArray): Bitmap? {
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var bestBitmap: Bitmap? = null
        var bestBitDepth = 0

        var pos = 0
        val limit = bytes.size - 40
        while (pos <= limit) {
            val biSize = bb.getInt(pos)
            if (biSize == 40) {
                val biWidth = bb.getInt(pos + 4)
                val biHeightRaw = bb.getInt(pos + 8)
                val biPlanes = bb.getShort(pos + 12).toInt() and 0xFFFF
                val biBitCount = bb.getShort(pos + 14).toInt() and 0xFFFF
                val biCompression = bb.getInt(pos + 16)
                val biSizeImage = bb.getInt(pos + 20)
                val biClrUsed = bb.getInt(pos + 32)

                val absH = abs(biHeightRaw)
                val isValidHeader = biPlanes == 1 &&
                        biWidth in 4..2560 &&
                        absH in 4..2560 &&
                        (biWidth.toLong() * absH.toLong() <= 6_553_600L) &&
                        biBitCount in setOf(1, 4, 8, 16, 24, 32) &&
                        biCompression in setOf(0, 1, 2, 3)

                if (isValidHeader) {
                    val decoded = decodeDibAtOffset(
                        bytes = bytes,
                        dibStart = pos,
                        width = biWidth,
                        heightRaw = biHeightRaw,
                        bitCount = biBitCount,
                        compression = biCompression,
                        sizeImage = biSizeImage,
                        clrUsed = biClrUsed
                    )
                    if (decoded != null) {
                        if (biBitCount >= 24) {
                            bestBitmap?.recycle()
                            return decoded
                        }
                        if (bestBitmap == null || biBitCount > bestBitDepth) {
                            bestBitmap?.recycle()
                            bestBitmap = decoded
                            bestBitDepth = biBitCount
                        } else {
                            decoded.recycle()
                        }
                        pos += 40
                        continue
                    }
                }
            }
            pos += 2
        }
        return bestBitmap
    }

    private fun decodeDibAtOffset(
        bytes: ByteArray,
        dibStart: Int,
        width: Int,
        heightRaw: Int,
        bitCount: Int,
        compression: Int,
        sizeImage: Int,
        clrUsed: Int
    ): Bitmap? {
        return try {
            val height = abs(heightRaw)
            val isTopDown = heightRaw < 0
            val paletteEntries = when {
                clrUsed in 1..256 -> clrUsed
                bitCount <= 8 -> 1 shl bitCount
                else -> 0
            }
            val paletteStart = dibStart + 40
            val pixelsStart = paletteStart + paletteEntries * 4
            if (pixelsStart >= bytes.size) return null

            if (compression == 0) {
                val rowStride = ((width * bitCount + 31) / 32) * 4
                val neededPixelBytes = rowStride.toLong() * height.toLong()
                val availBytes = bytes.size - pixelsStart
                if (neededPixelBytes in 16L..availBytes.toLong()) {
                    val totalDibLen = 40 + paletteEntries * 4 + neededPixelBytes.toInt()
                    val bmpBytes = ByteArray(14 + totalDibLen)
                    val bmpBb = ByteBuffer.wrap(bmpBytes).order(ByteOrder.LITTLE_ENDIAN)
                    bmpBytes[0] = 'B'.code.toByte()
                    bmpBytes[1] = 'M'.code.toByte()
                    bmpBb.putInt(2, bmpBytes.size)
                    bmpBb.putInt(6, 0)
                    bmpBb.putInt(10, 14 + 40 + paletteEntries * 4)
                    System.arraycopy(bytes, dibStart, bmpBytes, 14, totalDibLen)
                    decodeSampledBitmap(bmpBytes, 0, bmpBytes.size)?.let { return it }
                }
            }

            val palette = IntArray(paletteEntries.coerceAtLeast(256))
            for (c in 0 until paletteEntries) {
                val pOff = paletteStart + c * 4
                if (pOff + 4 > bytes.size) break
                val b = bytes[pOff].toInt() and 0xFF
                val g = bytes[pOff + 1].toInt() and 0xFF
                val r = bytes[pOff + 2].toInt() and 0xFF
                palette[c] = Color.rgb(r, g, b)
            }

            val outPixels = IntArray(width * height) { Color.WHITE }

            if (compression == 1 && bitCount == 8) {
                var x = 0
                var y = 0
                var p = pixelsStart
                val maxEnd = if (sizeImage > 0) minOf(bytes.size, pixelsStart + sizeImage) else bytes.size
                while (p + 1 < maxEnd && y < height) {
                    val b0 = bytes[p++].toInt() and 0xFF
                    val b1 = bytes[p++].toInt() and 0xFF
                    if (b0 > 0) {
                        val color = palette[b1 and 0xFF]
                        val dstRow = if (isTopDown) y else (height - 1 - y)
                        if (dstRow in 0 until height) {
                            val rowBase = dstRow * width
                            for (k in 0 until b0) {
                                if (x < width) {
                                    outPixels[rowBase + x] = color
                                    x++
                                }
                            }
                        }
                    } else {
                        when (b1) {
                            0 -> { x = 0; y++ }
                            1 -> break
                            2 -> {
                                if (p + 1 >= maxEnd) break
                                x += bytes[p++].toInt() and 0xFF
                                y += bytes[p++].toInt() and 0xFF
                            }
                            else -> {
                                val dstRow = if (isTopDown) y else (height - 1 - y)
                                val rowBase = dstRow * width
                                for (k in 0 until b1) {
                                    if (p >= maxEnd) break
                                    val idx = bytes[p++].toInt() and 0xFF
                                    if (x < width && dstRow in 0 until height) {
                                        outPixels[rowBase + x] = palette[idx]
                                    }
                                    x++
                                }
                                if ((b1 and 1) == 1 && p < maxEnd) p++
                            }
                        }
                    }
                }
                return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
            }

            if (compression == 0 && bitCount in setOf(8, 24, 32)) {
                val rowStride = ((width * bitCount + 31) / 32) * 4
                val availRows = ((bytes.size - pixelsStart) / rowStride).coerceIn(0, height)
                if (availRows < height / 2) return null
                for (row in 0 until availRows) {
                    val srcRowOff = pixelsStart + row * rowStride
                    val dstY = if (isTopDown) row else (height - 1 - row)
                    val dstRowBase = dstY * width
                    when (bitCount) {
                        8 -> {
                            for (col in 0 until width) {
                                val idx = bytes[srcRowOff + col].toInt() and 0xFF
                                outPixels[dstRowBase + col] = palette[idx]
                            }
                        }
                        24 -> {
                            for (col in 0 until width) {
                                val p = srcRowOff + col * 3
                                val b = bytes[p].toInt() and 0xFF
                                val g = bytes[p + 1].toInt() and 0xFF
                                val r = bytes[p + 2].toInt() and 0xFF
                                outPixels[dstRowBase + col] = Color.rgb(r, g, b)
                            }
                        }
                        32 -> {
                            for (col in 0 until width) {
                                val p = srcRowOff + col * 4
                                val b = bytes[p].toInt() and 0xFF
                                val g = bytes[p + 1].toInt() and 0xFF
                                val r = bytes[p + 2].toInt() and 0xFF
                                val a = bytes[p + 3].toInt() and 0xFF
                                outPixels[dstRowBase + col] = Color.argb(if (a == 0) 255 else a, r, g, b)
                            }
                        }
                    }
                }
                return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
