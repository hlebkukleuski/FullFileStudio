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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.core.graphics.PathParser
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.modules.ArchiveSession
import me.hlebkukleuski.fullfilestudio.feature.office.SpreadsheetCoordinateUtils
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
private const val LANG_ORDINAL_SHIFT = 24
private const val PAGE_INDEX_MASK = 0x000FFFFF
private val DARK_PRESENTATION_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
private val LIGHT_PRESENTATION_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)

private val DARK_SHEET_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
private val DARK_SHEET_CELL_BG_COLOR = Color.rgb(0x1E, 0x1E, 0x2E)
private val DARK_SHEET_HEADER_BG_COLOR = Color.rgb(0x18, 0x18, 0x25)
private val DARK_SHEET_GRID_COLOR = Color.rgb(0x31, 0x32, 0x44)
private val DARK_SHEET_TEXT_COLOR = Color.rgb(0xFF, 0xFF, 0xFF)
private val DARK_SHEET_HEADER_TEXT_COLOR = Color.rgb(0xBA, 0xC2, 0xDE)

private val LIGHT_SHEET_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)
private val LIGHT_SHEET_CELL_BG_COLOR = Color.rgb(0xFF, 0xFF, 0xFF)
private val LIGHT_SHEET_HEADER_BG_COLOR = Color.rgb(0xEE, 0xF1, 0xF6)
private val LIGHT_SHEET_GRID_COLOR = Color.rgb(0xD8, 0xDE, 0xE9)
private val LIGHT_SHEET_TEXT_COLOR = Color.rgb(0x1A, 0x1C, 0x24)
private val LIGHT_SHEET_HEADER_TEXT_COLOR = Color.rgb(0x5A, 0x62, 0x75)

internal object ExcelDateFormatter {
    private val BUILTIN_DATE_FORMAT_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 30, 36, 45, 46, 47, 50, 57
    )
    private val BUILTIN_TIME_ONLY_IDS = setOf(18, 19, 20, 21, 45, 46, 47)
    private val BUILTIN_PERCENT_IDS = setOf(9, 10)

    fun isDateFormatCode(formatIdx: Int, customFormatString: String?): Boolean {
        if (formatIdx in BUILTIN_DATE_FORMAT_IDS) return true
        if (customFormatString.isNullOrBlank()) return false
        val cleaned = customFormatString
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("\\\\."), "")
            .lowercase(Locale.ROOT)
        if (cleaned.any { it in setOf('y', 'd', 'h', 's') }) return true
        return cleaned.contains("mm")
    }

    fun isPercentFormatCode(formatIdx: Int, customFormatString: String?): Boolean {
        if (formatIdx in BUILTIN_PERCENT_IDS) return true
        if (customFormatString.isNullOrBlank()) return false
        return customFormatString.contains('%')
    }

    fun formatExcelSerialDate(
        serial: Double,
        use1904Windowing: Boolean,
        formatIdx: Int = 14,
        customFormatString: String? = null
    ): String {
        if (!serial.isFinite() || serial < 0.0 || serial > 2958465.0) {
            val asLong = serial.toLong()
            return if (serial == asLong.toDouble()) asLong.toString() else serial.toString()
        }
        var wholeDays = serial.toInt()
        val fractionOfDay = (serial - wholeDays).coerceIn(0.0, 0.9999999)
        val totalSeconds = (fractionOfDay * 86400.0).roundToLong().toInt().coerceIn(0, 86399)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val isTimeOnlyFormat = formatIdx in BUILTIN_TIME_ONLY_IDS ||
                (wholeDays == 0 && totalSeconds > 0) ||
                (customFormatString != null && !customFormatString.lowercase(Locale.ROOT).any { it == 'y' || it == 'd' } &&
                        customFormatString.lowercase(Locale.ROOT).any { it == 'h' || it == 's' })

        if (isTimeOnlyFormat && wholeDays == 0) {
            return if (seconds > 0 || formatIdx in setOf(19, 21, 46, 47)) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", hours, minutes)
            }
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        if (use1904Windowing) {
            cal.set(1904, Calendar.JANUARY, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, wholeDays)
        } else {
            if (wholeDays >= 60) wholeDays -= 1
            cal.set(1899, Calendar.DECEMBER, 31, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, wholeDays)
        }

        cal.add(Calendar.SECOND, totalSeconds)

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        return if (totalSeconds >= 60) {
            String.format(Locale.US, "%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute)
        } else {
            String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        }
    }
}

object EmbeddedImageScanner {
    fun scanBinaryForImages(data: ByteArray, maxImages: Int = 16): List<Bitmap> {
        val result = ArrayList<Bitmap>()
        var i = 0
        while (i + 16 < data.size && result.size < maxImages) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            val b3 = data[i + 3].toInt() and 0xFF

            // PNG signature: 89 50 4E 47 0D 0A 1A 0A
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

            // JPEG signature: FF D8 FF
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
            // Check for "WMFC" magic (0x57, 0x4D, 0x46, 0x43 = 0x43464D57 LE)
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

        // 1. Try direct Android BitmapFactory decode first
        decodeSampledBitmap(data, offset, len)?.let { return it }

        val slice = if (offset == 0 && len == data.size) data else data.copyOfRange(offset, offset + len)

        // 2. Check if the payload contains an embedded PNG or JPEG stream
        val embeddedList = scanBinaryForImages(slice, 1)
        if (embeddedList.isNotEmpty()) return embeddedList[0]

        // 3. Check if the payload is an SVG XML graphic
        val headStr = String(slice, 0, minOf(slice.size, 512), StandardCharsets.UTF_8).trimStart()
        if (headStr.startsWith("<svg", ignoreCase = true) || headStr.startsWith("<?xml", ignoreCase = true)) {
            decodeSimpleSvgBytes(slice)?.let { return it }
        }

        // 4. Reassemble split WMFC escape records (if present in \wmetafile8) before DIB extraction
        val reassembledEmf = reassembleWmfcChunksIfPresent(slice)
        if (reassembledEmf != null) {
            extractBestDibFromMetafile(reassembledEmf)?.let { return it }
        }

        // 5. Scan raw WMF / EMF / binary for embedded BITMAPINFOHEADER (DIB) records
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

    private fun decodeSimpleSvgBytes(svgBytes: ByteArray): Bitmap? {
        return try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(svgBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var vbW = 256f
            var vbH = 256f
            val pathsAndColors = ArrayList<Pair<Path, Int>>()

            while (ev != XmlPullParser.END_DOCUMENT && pathsAndColors.size < 2048) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (name == "svg") {
                        val vb = parser.getAttributeValue(null, "viewBox") ?: parser.getAttributeValue(null, "viewbox")
                        if (!vb.isNullOrBlank()) {
                            val nums = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                            if (nums.size >= 4 && nums[2] > 0f && nums[3] > 0f) {
                                vbW = nums[2]
                                vbH = nums[3]
                            }
                        }
                    } else if (name == "path") {
                        val d = parser.getAttributeValue(null, "d")
                        if (!d.isNullOrBlank() && d.length <= 65536) {
                            val p = try { PathParser.createPathFromPathData(d) } catch (_: Throwable) { null }
                            if (p != null) {
                                val fillStr = parser.getAttributeValue(null, "fill")
                                val col = if (!fillStr.isNullOrBlank() && fillStr.startsWith("#")) {
                                    try { Color.parseColor(fillStr) } catch (_: Throwable) { Color.DKGRAY }
                                } else Color.DKGRAY
                                pathsAndColors.add(p to col)
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            if (pathsAndColors.isEmpty()) return null
            val targetW = 512
            val targetH = ((512f * vbH / vbW.coerceAtLeast(1f)).roundToInt()).coerceIn(64, 1024)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(targetW / vbW.coerceAtLeast(1f), targetH / vbH.coerceAtLeast(1f))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for ((p, col) in pathsAndColors) {
                paint.color = col
                c.drawPath(p, paint)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

object CleanRoomLegacyAndIsoEngine {

    fun createSession(fd: FileDescriptor, format: DetectedFormat, extension: String): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val pfd = ParcelFileDescriptor.dup(fd)
        return when {
            format in setOf(DetectedFormat.SVG, DetectedFormat.SVGZ) || ext in setOf("svg", "svgz") ->
                CleanRoomSvgVectorSession(pfd)
            format in setOf(
                DetectedFormat.STL, DetectedFormat.OBJ, DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB, DetectedFormat.BLEND, DetectedFormat.USDZ
            ) || ext in setOf(
                "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
                "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            ) -> CleanRoomBuiltIn3dSession(pfd, ext)
            format in setOf(
                DetectedFormat.ISO, DetectedFormat.ZIP, DetectedFormat.SEVEN_ZIP,
                DetectedFormat.RAR_V4, DetectedFormat.RAR_V5, DetectedFormat.TAR,
                DetectedFormat.GZ, DetectedFormat.BZ2, DetectedFormat.XZ, DetectedFormat.APK
            ) || ext in setOf("iso", "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "apk", "jar") ->
                CleanRoomIso9660Session(pfd)
            format == DetectedFormat.ONE || ext == "one" -> CleanRoomOneNoteSession(pfd)
            format in setOf(DetectedFormat.MAX, DetectedFormat.RVT, DetectedFormat.INDD) ||
                    ext in setOf("max", "rvt", "indd") -> CleanRoomProprietaryPreviewSession(pfd, format, ext)
            format == DetectedFormat.RTF || ext == "rtf" -> CleanRoomRtfSession(pfd)
            format in setOf(DetectedFormat.XLS, DetectedFormat.XLSX, DetectedFormat.ODS) ||
                    ext in setOf("xls", "xlt", "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv") ->
                CleanRoomBiff8XlsSession(pfd)
            format in setOf(DetectedFormat.PPT, DetectedFormat.PPTX, DetectedFormat.ODP) ||
                    ext in setOf("ppt", "pps", "pot", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp") ->
                CleanRoomPptSession(pfd)
            else -> CleanRoomDocSession(pfd)
        }
    }
}

/**
 * Built-in Clean-Room SVG & SVGZ Vector Graphics Session for Standalone APK builds.
 */
class CleanRoomSvgVectorSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val pageCount: Int = 1

    private data class SvgDrawOp(
        val path: Path,
        val fillColor: Int?,
        val strokeColor: Int?,
        val strokeWidth: Float
    )

    private data class SvgTextOp(
        val text: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val color: Int
    )

    private val drawOps = ArrayList<SvgDrawOp>()
    private val textOps = ArrayList<SvgTextOp>()
    private var viewBoxMinX = 0f
    private var viewBoxMinY = 0f
    private var viewBoxWidth = 512f
    private var viewBoxHeight = 512f

    init {
        parseSvgOrSvgz()
    }

    private fun parseSvgOrSvgz() {
        try {
            val src = ZeroCacheReadableSource(pfd.fileDescriptor)
            val maxLen = minOf(src.size, 32L * 1024L * 1024L).toInt()
            if (maxLen <= 4) {
                lastErrorMsg = "SVG file is empty."
                return
            }
            val buf = ByteBuffer.allocateDirect(maxLen)
            val got = src.readAt(0L, buf)
            if (got <= 4) {
                lastErrorMsg = "Unable to read SVG stream."
                return
            }
            buf.flip()
            var bytes = ByteArray(got)
            buf.get(bytes)

            if ((bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
                val compressedSize = bytes.size.coerceAtLeast(1)
                bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val bos = ByteArrayOutputStream()
                    val chunk = ByteArray(16384)
                    var total = 0
                    while (total < 32 * 1024 * 1024) {
                        val r = gis.read(chunk)
                        if (r <= 0) break
                        total += r
                        if (total > 16 * 1024 * 1024 && (total / compressedSize) > 150) {
                            throw SecurityException("Превышен безопасный лимит распаковки SVGZ (защита от Zip-бомб).")
                        }
                        bos.write(chunk, 0, r)
                    }
                    bos.toByteArray()
                }
            }

            val xmlStr = EbookTextSession.decodeAndPreprocessXml(bytes)
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(StringReader(xmlStr))
            var ev = parser.eventType
            var inText = false
            var textX = 0f
            var textY = 0f
            var textFontSize = 18f
            var textColor = Color.BLACK
            val textSb = StringBuilder()

            fun parseSvgColor(raw: String?): Int? {
                if (raw.isNullOrBlank() || raw.equals("none", true) || raw.equals("transparent", true)) return null
                val clean = raw.trim()
                if (clean.startsWith("rgb(", true) && clean.endsWith(")")) {
                    val nums = clean.substringAfter('(').substringBefore(')').split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (nums.size >= 3) return Color.rgb(nums[0].coerceIn(0, 255), nums[1].coerceIn(0, 255), nums[2].coerceIn(0, 255))
                }
                return try { Color.parseColor(clean) } catch (_: Throwable) { Color.DKGRAY }
            }

            while (ev != XmlPullParser.END_DOCUMENT && drawOps.size < 10_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val styleAttr = parser.getAttributeValue(null, "style") ?: ""
                        fun resolveProp(name: String): String? {
                            parser.getAttributeValue(null, name)?.let { return it }
                            val m = Regex("""(?:^|;)\s*$name\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE).find(styleAttr)
                            return m?.groupValues?.get(1)?.trim()
                        }

                        val fillStr = resolveProp("fill")
                        val strokeStr = resolveProp("stroke")
                        val fillCol = if (fillStr == null) Color.rgb(45, 55, 72) else parseSvgColor(fillStr)
                        val strokeCol = parseSvgColor(strokeStr)
                        val strokeW = resolveProp("stroke-width")?.removeSuffix("px")?.toFloatOrNull() ?: 1.5f

                        when (local) {
                            "svg" -> {
                                val vb = parser.getAttributeValue(null, "viewBox") ?: parser.getAttributeValue(null, "viewbox")
                                if (!vb.isNullOrBlank()) {
                                    val nums = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                                    if (nums.size >= 4 && nums[2] > 0f && nums[3] > 0f) {
                                        viewBoxMinX = nums[0]
                                        viewBoxMinY = nums[1]
                                        viewBoxWidth = nums[2]
                                        viewBoxHeight = nums[3]
                                    }
                                } else {
                                    parser.getAttributeValue(null, "width")?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.let { if (it > 0f) viewBoxWidth = it }
                                    parser.getAttributeValue(null, "height")?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.let { if (it > 0f) viewBoxHeight = it }
                                }
                            }
                            "path" -> {
                                val d = parser.getAttributeValue(null, "d")
                                if (!d.isNullOrBlank() && d.length <= 65536) {
                                    try {
                                        PathParser.createPathFromPathData(d)?.let { p ->
                                            drawOps.add(SvgDrawOp(p, fillCol, strokeCol, strokeW))
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                            "rect" -> {
                                val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                                val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                                val w = parser.getAttributeValue(null, "width")?.toFloatOrNull() ?: 0f
                                val h = parser.getAttributeValue(null, "height")?.toFloatOrNull() ?: 0f
                                if (w > 0f && h > 0f) {
                                    val p = Path().apply { addRect(x, y, x + w, y + h, Path.Direction.CW) }
                                    drawOps.add(SvgDrawOp(p, fillCol, strokeCol, strokeW))
                                }
                            }
                            "circle" -> {
                                val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: 0f
                                val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: 0f
                                val r = parser.getAttributeValue(null, "r")?.toFloatOrNull() ?: 0f
                                if (r > 0f) {
                                    val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                                    drawOps.add(SvgDrawOp(p, fillCol, strokeCol, strokeW))
                                }
                            }
                            "ellipse" -> {
                                val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: 0f
                                val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: 0f
                                val rx = parser.getAttributeValue(null, "rx")?.toFloatOrNull() ?: 0f
                                val ry = parser.getAttributeValue(null, "ry")?.toFloatOrNull() ?: 0f
                                if (rx > 0f && ry > 0f) {
                                    val p = Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }
                                    drawOps.add(SvgDrawOp(p, fillCol, strokeCol, strokeW))
                                }
                            }
                            "line" -> {
                                val x1 = parser.getAttributeValue(null, "x1")?.toFloatOrNull() ?: 0f
                                val y1 = parser.getAttributeValue(null, "y1")?.toFloatOrNull() ?: 0f
                                val x2 = parser.getAttributeValue(null, "x2")?.toFloatOrNull() ?: 0f
                                val y2 = parser.getAttributeValue(null, "y2")?.toFloatOrNull() ?: 0f
                                val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                                drawOps.add(SvgDrawOp(p, null, strokeCol ?: fillCol ?: Color.BLACK, strokeW))
                            }
                            "polygon", "polyline" -> {
                                val ptsStr = parser.getAttributeValue(null, "points") ?: ""
                                if (ptsStr.length <= 65536) {
                                    val nums = ptsStr.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                                    if (nums.size >= 4) {
                                        val p = Path()
                                        p.moveTo(nums[0], nums[1])
                                        var i = 2
                                        while (i + 1 < nums.size) {
                                            p.lineTo(nums[i], nums[i + 1])
                                            i += 2
                                        }
                                        if (local == "polygon") p.close()
                                        drawOps.add(SvgDrawOp(p, if (local == "polygon") fillCol else null, strokeCol, strokeW))
                                    }
                                }
                            }
                            "text" -> {
                                inText = true
                                textSb.setLength(0)
                                textX = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                                textY = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                                textFontSize = resolveProp("font-size")?.filter { it.isDigit() || it == '.' }?.toFloatOrNull() ?: 18f
                                textColor = fillCol ?: Color.BLACK
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inText && textSb.length < 4096) textSb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> if (local == "text" && inText) {
                        val t = textSb.toString().trim()
                        if (t.isNotEmpty() && textOps.size < 2048) {
                            textOps.add(SvgTextOp(t, textX, textY, textFontSize, textColor))
                        }
                        inText = false
                    }
                }
                ev = parser.next()
            }
            if (drawOps.isEmpty() && textOps.isEmpty()) {
                lastErrorMsg = "SVG document contains no vector paths or text elements."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "SVG parse error: ${e.localizedMessage}"
        }
    }

    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = viewBoxWidth
    override fun getDocumentHeight(): Float = viewBoxHeight
    override fun getPageText(pageIndex: Int): String = textOps.joinToString("\n") { it.text }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) Color.WHITE else Color.rgb(0x18, 0x1A, 0x22))

        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10f)
        val baseScale = minOf(
            (bitmap.width - 48f) / viewBoxWidth.coerceAtLeast(1f),
            (bitmap.height - 48f) / viewBoxHeight.coerceAtLeast(1f)
        ).coerceAtLeast(0.05f)
        val totalScale = baseScale * zoom
        val dstW = viewBoxWidth * totalScale
        val dstH = viewBoxHeight * totalScale
        val dx = (bitmap.width - dstW) / 2f - tileX.toFloat()
        val dy = (bitmap.height - dstH) / 2f - tileY.toFloat()

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(dx, dy, dx + dstW, dy + dstH), 8f, 8f, cardPaint)

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(totalScale, totalScale)
        canvas.translate(-viewBoxMinX, -viewBoxMinY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (op in drawOps) {
            if (op.fillColor != null) {
                paint.style = Paint.Style.FILL
                paint.color = op.fillColor
                canvas.drawPath(op.path, paint)
            }
            if (op.strokeColor != null && op.strokeWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.strokeWidth
                paint.color = op.strokeColor
                canvas.drawPath(op.path, paint)
            }
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        for (tOp in textOps) {
            tp.color = tOp.color
            tp.textSize = tOp.fontSize
            canvas.drawText(tOp.text, tOp.x, tOp.y, tp)
        }
        canvas.restore()
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Built-in Clean-Room 3D/CAD Session in `:core:engine-api` supporting `glTF 2.0`, `GLB`, `Blender .blend`,
 * `USDZ / USDA / USDC`, `FBX` (Binary & ASCII), `STL` (Binary & ASCII), `OBJ`, `PLY` (Binary & ASCII),
 * `OFF`, `3DS`, `3MF`, `COLLADA .dae`, `STEP / STP`, `IGES / IGS`, `DXF` (Binary & ASCII), and `DWG`
 * with Zero-OOM `ZeroCacheReadableSource` streaming for Standalone APK builds.
 */
class CleanRoomBuiltIn3dSession(
    private val pfd: ParcelFileDescriptor,
    private val originalExtension: String
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var isGeometryTruncated = false

    override val is3dContent: Boolean = true
    override val pageCount: Int = 1

    private val readableSource = ZeroCacheReadableSource(pfd.fileDescriptor)

    private data class BlendBlock(
        val code: String,
        val dataOffset: Int,
        val len: Int,
        val sdnaIndex: Int,
        val count: Int
    )

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private var rawVertices = FloatArray(30_000)
    private var polyColors = IntArray(3_500)
    private var vertexCount = 0
    private var previewBitmap: Bitmap? = null

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private var modelType = "3D Model"

    private val maxPolygonsLimit: Int = computeDynamicPolygonLimit()
    private val maxVertexFloats: Int = maxPolygonsLimit * 9

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    companion object {
        private fun computeDynamicPolygonLimit(): Int {
            val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(128L * 1024L * 1024L)
            val budgetBytes = (maxHeapBytes * 35L) / 100L
            return (budgetBytes / 160L).coerceIn(750_000L, 4_000_000L).toInt()
        }

        private val DWG_IMAGE_SENTINEL = byteArrayOf(
            0x1F.toByte(), 0x25.toByte(), 0x6D.toByte(), 0x07.toByte(),
            0xD4.toByte(), 0x36.toByte(), 0x28.toByte(), 0x28.toByte(),
            0x9D.toByte(), 0x57.toByte(), 0xCA.toByte(), 0x3F.toByte(),
            0x9D.toByte(), 0x44.toByte(), 0x10.toByte(), 0x2B.toByte()
        )

        private fun aciToColor(aci: Int): Int {
            return when (abs(aci)) {
                1 -> Color.rgb(255, 59, 48)
                2 -> Color.rgb(255, 204, 0)
                3 -> Color.rgb(40, 205, 65)
                4 -> Color.rgb(0, 199, 190)
                5 -> Color.rgb(10, 132, 255)
                6 -> Color.rgb(191, 90, 242)
                7 -> 0
                8 -> Color.rgb(142, 142, 147)
                9 -> Color.rgb(199, 199, 204)
                in 10..249 -> Color.HSVToColor(floatArrayOf((((abs(aci) - 10) / 10) * 15f) % 360f, 0.80f, 0.92f))
                else -> 0
            }
        }
    }

    init {
        parseModel()
    }

    private fun readSliceAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0L) return ByteArray(0)
        val buf = ByteBuffer.allocateDirect(length)
        val got = readableSource.readAt(offset, buf)
        if (got <= 0) return ByteArray(0)
        buf.flip()
        val out = ByteArray(got)
        buf.get(out)
        return out
    }

    private fun readHeaderOrFullBytes(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val exactSize = readableSource.size
        val cap = minOf(if (exactSize > 0L) exactSize else maxBytes.toLong(), maxBytes.toLong()).toInt()
        return readSliceAt(0L, cap)
    }

    private inline fun openSafeStream(block: (java.io.InputStream) -> Unit) {
        val fileSize = readableSource.size.let { if (it > 0L) it else 500_000_000L }
        java.io.BufferedInputStream(PositionalPreadInputStream(pfd.fileDescriptor, fileSize), 64 * 1024).use(block)
    }

    private fun updateBounds(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    private fun addTriangle(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        customColor: Int = 0
    ) {
        if (!x1.isFinite() || !y1.isFinite() || !z1.isFinite() ||
            !x2.isFinite() || !y2.isFinite() || !z2.isFinite() ||
            !x3.isFinite() || !y3.isFinite() || !z3.isFinite()
        ) return
        if (vertexCount + 9 > maxVertexFloats) {
            isGeometryTruncated = true
            return
        }
        if (vertexCount + 9 > rawVertices.size) {
            rawVertices = rawVertices.copyOf(minOf(maxVertexFloats, maxOf(rawVertices.size * 2, vertexCount + 9)))
        }
        val polyIdx = vertexCount / 9
        if (polyIdx >= polyColors.size) {
            polyColors = polyColors.copyOf(minOf(maxPolygonsLimit, maxOf(polyColors.size * 2, polyIdx + 1024)))
        }
        polyColors[polyIdx] = customColor
        rawVertices[vertexCount++] = x1; rawVertices[vertexCount++] = y1; rawVertices[vertexCount++] = z1
        rawVertices[vertexCount++] = x2; rawVertices[vertexCount++] = y2; rawVertices[vertexCount++] = z2
        rawVertices[vertexCount++] = x3; rawVertices[vertexCount++] = y3; rawVertices[vertexCount++] = z3
        updateBounds(x1, y1, z1); updateBounds(x2, y2, z2); updateBounds(x3, y3, z3)
    }

    private fun addLineSegment(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        customColor: Int = 0
    ) {
        addTriangle(x1, y1, z1, x2, y2, z2, x1, y1, z1, customColor)
    }

    private fun parseModel() {
        try {
            val exactFileSize = readableSource.size
            val header = readSliceAt(0L, 1024)
            if (header.size < 4) {
                lastErrorMsg = "3D file is empty."
                return
            }

            val ext = originalExtension.lowercase(Locale.ROOT).trim()
            val headStr = String(header, 0, minOf(header.size, 960), StandardCharsets.UTF_8).trim().lowercase(Locale.ROOT)
            val isGlb = header.size >= 4 && header[0] == 'g'.code.toByte() && header[1] == 'l'.code.toByte() && header[2] == 'T'.code.toByte() && header[3] == 'F'.code.toByte()
            val isBlend = (header.size >= 7 && String(header, 0, 7, StandardCharsets.US_ASCII) == "BLENDER") || ext == "blend"
            val isUsdc = header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC"
            val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            val isDwg = (header.size >= 6 && header[0] == 'A'.code.toByte() && header[1] == 'C'.code.toByte() && header[2] == '1'.code.toByte() && header[3] == '0'.code.toByte()) || ext == "dwg"
            val isFbx = (header.size >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary") ||
                    headStr.startsWith("; fbx") || (headStr.contains("fbxheaderextension") && headStr.contains("objects:")) || ext == "fbx"
            val is3ds = header.size >= 6 && (header[0].toInt() and 0xFF) == 0x4D && (header[1].toInt() and 0xFF) == 0x4D &&
                    (ext == "3ds" || (exactFileSize >= 16L && (ByteBuffer.wrap(header, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) == exactFileSize))
            val isCollada = headStr.contains("<collada") || ext == "dae"
            val isStep = headStr.startsWith("iso-10303-21;") || (headStr.contains("iso-10303-21;") && headStr.contains("header;")) || ext in setOf("step", "stp")
            val isIges = ext in setOf("iges", "igs")

            val isVerifiedBinaryStlSize = if (header.size >= 84 && exactFileSize >= 134L) {
                val triCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val expected = 84L + triCount * 50L
                triCount in 1L..25_000_000L && (exactFileSize in expected..(expected + 64L))
            } else false

            val hasAsciiStlKeywords = headStr.startsWith("solid") && (headStr.contains("facet") || headStr.contains("vertex"))

            when {
                isBlend -> {
                    modelType = "Blender 3D"
                    parseBlendBytes(readHeaderOrFullBytes(64 * 1024 * 1024))
                }
                ext == "3mf" -> {
                    modelType = "3MF 3D Model"
                    parse3mfStream()
                }
                isUsdc || headStr.startsWith("#usda") || ext in setOf("usdz", "usd", "usda", "usdc") || (isZip && ext == "usdz") -> {
                    modelType = "USDZ / USD 3D"
                    parseUsdStream()
                }
                isZip -> {
                    parse3mfStream()
                    if (vertexCount == 0) parseUsdStream()
                }
                isGlb || (headStr.startsWith("{") && headStr.contains("\"asset\"")) || ext in setOf("glb", "gltf") -> {
                    modelType = if (isGlb || ext == "glb") "GLB (glTF 2.0)" else "glTF 2.0"
                    parseGltfOrGlbStreaming(null)
                }
                isFbx -> {
                    modelType = "FBX 3D"
                    parseFbxBytes(readHeaderOrFullBytes(64 * 1024 * 1024))
                }
                isCollada -> {
                    modelType = "COLLADA (.dae)"
                    parseColladaBytes(readHeaderOrFullBytes(48 * 1024 * 1024))
                }
                isStep -> {
                    modelType = "STEP (ISO 10303-21)"
                    openSafeStream { parseStepStream(it) }
                }
                isIges -> {
                    modelType = "IGES CAD"
                    openSafeStream { parseIgesStream(it) }
                }
                isDwg -> {
                    modelType = "DWG"
                    parseDwgBytes(readHeaderOrFullBytes(32 * 1024 * 1024))
                }
                is3ds -> {
                    modelType = "3DS"
                    parse3dsStreaming(exactFileSize)
                }
                headStr.startsWith("ply") || ext == "ply" -> {
                    modelType = "PLY"
                    parsePlyStreaming()
                }
                headStr.startsWith("off") || headStr.startsWith("coff") || headStr.startsWith("noff") || ext == "off" -> {
                    modelType = "OFF"
                    openSafeStream { parseOffStream(it) }
                }
                ext == "dxf" || (headStr.contains("section") && headStr.contains("entities")) ||
                        (header.size >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "AutoCAD Binary DXF") -> {
                    modelType = "DXF"
                    openSafeStream { parseDxfStream(it) }
                }
                ext == "obj" || headStr.startsWith("v ") || headStr.startsWith("mtllib") || headStr.contains("\nv ") -> {
                    modelType = "OBJ"
                    openSafeStream { parseObjStream(it) }
                }
                isVerifiedBinaryStlSize -> {
                    modelType = "Binary STL"
                    parseBinaryStlPread(exactFileSize)
                }
                hasAsciiStlKeywords -> {
                    modelType = "ASCII STL"
                    openSafeStream { parseAsciiStlStream(it) }
                    if (vertexCount == 0) {
                        modelType = "Binary STL"
                        parseBinaryStlPread(exactFileSize)
                    }
                }
                else -> {
                    modelType = "Binary STL"
                    parseBinaryStlPread(exactFileSize)
                    if (vertexCount == 0 && headStr.startsWith("solid")) {
                        modelType = "ASCII STL"
                        openSafeStream { parseAsciiStlStream(it) }
                    }
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка разбора 3D/CAD модели: ${e.localizedMessage}"
        }
        if (vertexCount == 0 && previewBitmap == null && lastErrorMsg == null) {
            lastErrorMsg = "Модель $modelType пуста, повреждена или не содержит поддерживаемой геометрии."
        }
    }

    private fun parseGltfOrGlbStreaming(customBytes: ByteArray?) {
        var jsonString: String? = null
        val inlineBuffersList = ArrayList<ByteArray>()
        val fileBufferSlices = ArrayList<Pair<Long, Int>>()

        if (customBytes != null) {
            if (customBytes.size < 12) return
            val bb = ByteBuffer.wrap(customBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) == 0x46546C67) {
                var pos = 12
                while (pos + 8 <= customBytes.size) {
                    val chunkLen = bb.getInt(pos)
                    val chunkType = bb.getInt(pos + 4)
                    val dataStart = pos + 8
                    if (chunkLen < 0 || dataStart > customBytes.size) break
                    val safeLen = minOf(chunkLen, customBytes.size - dataStart)
                    if (chunkType == 0x4E4F534A && jsonString == null) {
                        jsonString = String(customBytes, dataStart, safeLen, StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942) {
                        inlineBuffersList.add(customBytes.copyOfRange(dataStart, dataStart + safeLen))
                    }
                    pos = dataStart + safeLen
                }
            } else {
                jsonString = String(customBytes, StandardCharsets.UTF_8)
            }
        } else {
            val fileSize = readableSource.size.let { if (it > 0L) it else 512L * 1024L * 1024L }
            val hdr = readSliceAt(0L, 12)
            if (hdr.size < 12) return
            val hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            if (hbb.getInt(0) == 0x46546C67) {
                var pos = 12L
                while (pos + 8L <= fileSize) {
                    val ch = readSliceAt(pos, 8)
                    if (ch.size < 8) break
                    val cbb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                    val chunkLen = cbb.getInt(0).toLong() and 0xFFFFFFFFL
                    val chunkType = cbb.getInt(4)
                    val dataStart = pos + 8L
                    if (chunkLen <= 0L || dataStart >= fileSize) break
                    val availLen = minOf(chunkLen, fileSize - dataStart)
                    if (chunkType == 0x4E4F534A && jsonString == null && availLen <= 32L * 1024L * 1024L) {
                        jsonString = String(readSliceAt(dataStart, availLen.toInt()), StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942) {
                        fileBufferSlices.add(dataStart to availLen.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    pos = dataStart + availLen
                }
            } else {
                jsonString = String(readHeaderOrFullBytes(48 * 1024 * 1024), StandardCharsets.UTF_8)
            }
        }

        val root = try { JSONObject(jsonString?.trim() ?: return) } catch (_: Throwable) { return }
        val jsonBuffers = root.optJSONArray("buffers")
        if (jsonBuffers != null) {
            for (i in 0 until jsonBuffers.length()) {
                val uri = jsonBuffers.optJSONObject(i)?.optString("uri", "") ?: ""
                if (uri.startsWith("data:", true) && uri.contains(";base64,")) {
                    val b64 = uri.substringAfter(";base64,").trim()
                    if (b64.length <= 64 * 1024 * 1024) {
                        try {
                            val decoded = Base64.decode(b64, Base64.DEFAULT)
                            if (i < inlineBuffersList.size) inlineBuffersList[i] = decoded else inlineBuffersList.add(decoded)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        if (inlineBuffersList.isEmpty() && fileBufferSlices.isEmpty()) return
        val bufferViews = root.optJSONArray("bufferViews") ?: return
        val accessors = root.optJSONArray("accessors") ?: return
        val meshes = root.optJSONArray("meshes") ?: return

        fun readBufSlice(bufIdx: Int, startOffset: Int, byteLength: Int): ByteArray? {
            if (startOffset < 0 || byteLength <= 0 || byteLength > 48 * 1024 * 1024) return null
            val inlineBuf = inlineBuffersList.getOrNull(bufIdx)
            if (inlineBuf != null) {
                if (startOffset >= inlineBuf.size) return null
                return inlineBuf.copyOfRange(startOffset, minOf(inlineBuf.size, startOffset + byteLength))
            }
            val fileSlice = fileBufferSlices.getOrNull(bufIdx) ?: return null
            if (startOffset >= fileSlice.second) return null
            return readSliceAt(fileSlice.first + startOffset.toLong(), minOf(byteLength, fileSlice.second - startOffset))
        }

        fun readPos(accIdx: Int): FloatArray? {
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bv = bufferViews.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
            val bufIdx = bv.optInt("buffer", 0)
            val stride = bv.optInt("byteStride", 12).coerceAtLeast(12)
            val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declared = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 3)
            if (declared <= 0) return null
            val slice = readBufSlice(bufIdx, start, (declared - 1) * stride + 12) ?: return null
            if (slice.size < 12) return null
            val count = minOf(declared, 1 + (slice.size - 12) / stride)
            val bBuf = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(count * 3)
            for (i in 0 until count) {
                val p = i * stride
                if (p + 12 > slice.size) break
                out[i * 3] = bBuf.getFloat(p)
                out[i * 3 + 1] = bBuf.getFloat(p + 4)
                out[i * 3 + 2] = bBuf.getFloat(p + 8)
            }
            return out
        }

        fun readIdx(accIdx: Int): IntArray? {
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bv = bufferViews.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
            val bufIdx = bv.optInt("buffer", 0)
            val compType = acc.optInt("componentType", 5123)
            val bpc = when (compType) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val stride = bv.optInt("byteStride", bpc).coerceAtLeast(bpc)
            val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declared = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 6)
            if (declared <= 0) return null
            val slice = readBufSlice(bufIdx, start, (declared - 1) * stride + bpc) ?: return null
            if (slice.size < bpc) return null
            val count = minOf(declared, 1 + (slice.size - bpc) / stride)
            val bBuf = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { i ->
                val p = i * stride
                if (p + bpc > slice.size) 0 else when (compType) {
                    5120, 5121 -> slice[p].toInt() and 0xFF
                    5122, 5123 -> bBuf.getShort(p).toInt() and 0xFFFF
                    else -> bBuf.getInt(p)
                }
            }
        }

        val materials = root.optJSONArray("materials")
        val texturesJson = root.optJSONArray("textures")
        val imagesJson = root.optJSONArray("images")

        fun linearToSrgbChannel(linearVal: Double): Float {
            val c = linearVal.coerceIn(0.0, 1.0)
            val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
            return srgb.toFloat().coerceIn(0f, 1f)
        }

        val textureAvgCache = HashMap<Int, Int>()
        fun sampleTexColor(texIdx: Int): Int? {
            if (texIdx < 0 || texturesJson == null || imagesJson == null || texIdx >= texturesJson.length()) return null
            textureAvgCache[texIdx]?.let { return if (it == 0) null else it }
            val imgIdx = texturesJson.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
            if (imgIdx !in 0 until imagesJson.length()) {
                textureAvgCache[texIdx] = 0
                return null
            }
            val imgObj = imagesJson.optJSONObject(imgIdx) ?: return null
            val imgBytes: ByteArray? = when {
                imgObj.has("bufferView") -> {
                    val bvIdx = imgObj.optInt("bufferView", -1)
                    val bv = if (bvIdx in 0 until bufferViews.length()) bufferViews.optJSONObject(bvIdx) else null
                    if (bv != null) {
                        val bIdx = bv.optInt("buffer", 0)
                        val bOff = bv.optInt("byteOffset", 0)
                        val bLen = bv.optInt("byteLength", 0)
                        if (bLen in 16..(16 * 1024 * 1024)) readBufSlice(bIdx, bOff, bLen) else null
                    } else null
                }
                imgObj.optString("uri", "").startsWith("data:", true) -> {
                    val uri = imgObj.optString("uri", "")
                    if (uri.contains(";base64,")) {
                        val b64 = uri.substringAfter(";base64,").trim()
                        if (b64.length <= 16 * 1024 * 1024) {
                            try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Throwable) { null }
                        } else null
                    } else null
                }
                else -> null
            }
            if (imgBytes == null || imgBytes.size < 16) {
                textureAvgCache[texIdx] = 0
                return null
            }
            val sampled = try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, boundsOpts)
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sample = 1
                while (maxDim / sample > 32) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, opts)
                if (bmp != null) {
                    var sumR = 0L; var sumG = 0L; var sumB = 0L; var sumA = 0L; var count = 0L
                    for (py in 0 until bmp.height) {
                        for (px in 0 until bmp.width) {
                            val pxCol = bmp.getPixel(px, py)
                            val pa = Color.alpha(pxCol)
                            if (pa > 32) {
                                sumR += Color.red(pxCol)
                                sumG += Color.green(pxCol)
                                sumB += Color.blue(pxCol)
                                sumA += pa
                                count++
                            }
                        }
                    }
                    bmp.recycle()
                    if (count > 0L) {
                        Color.argb(
                            (sumA / count).toInt().coerceIn(32, 255),
                            (sumR / count).toInt().coerceIn(0, 255),
                            (sumG / count).toInt().coerceIn(0, 255),
                            (sumB / count).toInt().coerceIn(0, 255)
                        )
                    } else 0
                } else 0
            } catch (_: Throwable) {
                0
            }
            textureAvgCache[texIdx] = sampled
            return if (sampled == 0) null else sampled
        }

        val resolvedMatCache = HashMap<Int, Int>()
        fun resolveMatColor(matIdx: Int): Int {
            if (materials == null || matIdx !in 0 until materials.length()) return Color.rgb(185, 192, 204)
            resolvedMatCache[matIdx]?.let { return it }
            val mat = materials.optJSONObject(matIdx) ?: return Color.rgb(185, 192, 204)
            val matName = mat.optString("name", "").lowercase(Locale.ROOT)
            val alphaMode = mat.optString("alphaMode", "OPAQUE").uppercase(Locale.ROOT)
            val pbr = mat.optJSONObject("pbrMetallicRoughness")
            val factor = pbr?.optJSONArray("baseColorFactor")
                ?: mat.optJSONArray("emissiveFactor")
            var rSrgb = 0.78f; var gSrgb = 0.80f; var bSrgb = 0.84f; var alphaFactor = 1.0f
            if (factor != null && factor.length() >= 3) {
                rSrgb = linearToSrgbChannel(factor.optDouble(0, 0.8))
                gSrgb = linearToSrgbChannel(factor.optDouble(1, 0.8))
                bSrgb = linearToSrgbChannel(factor.optDouble(2, 0.8))
                if (factor.length() >= 4) alphaFactor = factor.optDouble(3, 1.0).toFloat().coerceIn(0f, 1f)
            }
            val baseTexIdx = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
            val texColor = sampleTexColor(baseTexIdx)
            if (texColor != null) {
                rSrgb = (rSrgb * (Color.red(texColor) / 255f)).coerceIn(0f, 1f)
                gSrgb = (gSrgb * (Color.green(texColor) / 255f)).coerceIn(0f, 1f)
                bSrgb = (bSrgb * (Color.blue(texColor) / 255f)).coerceIn(0f, 1f)
                if (alphaMode == "BLEND") alphaFactor = (alphaFactor * (Color.alpha(texColor) / 255f)).coerceIn(0f, 1f)
            }
            if (alphaMode == "BLEND" && (alphaFactor < 0.28f || matName.contains("coat") || matName.contains("refraction") || matName == "material_0")) {
                resolvedMatCache[matIdx] = Int.MIN_VALUE
                return Int.MIN_VALUE
            }
            val finalAlpha = when {
                alphaMode == "BLEND" || matName.contains("glass") || matName.contains("window") ->
                    (alphaFactor * 140f).toInt().coerceIn(48, 175)
                else -> 255
            }
            val r = maxOf((rSrgb * 255f).toInt().coerceIn(0, 255), 42)
            val g = maxOf((gSrgb * 255f).toInt().coerceIn(0, 255), 44)
            val b = maxOf((bSrgb * 255f).toInt().coerceIn(0, 255), 48)
            val packed = Color.argb(finalAlpha, r, g, b)
            resolvedMatCache[matIdx] = packed
            return packed
        }

        fun transformVerts(verts: FloatArray, m: FloatArray): FloatArray {
            val out = FloatArray(verts.size)
            for (i in 0 until verts.size step 3) {
                val x = verts[i]; val y = verts[i + 1]; val z = verts[i + 2]
                out[i] = m[0] * x + m[4] * y + m[8] * z + m[12]
                out[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                out[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            }
            return out
        }

        fun emitMeshAtMatrix(meshIdx: Int, worldMat: FloatArray) {
            if (meshIdx !in 0 until meshes.length() || isGeometryTruncated) return
            val prims = meshes.optJSONObject(meshIdx)?.optJSONArray("primitives") ?: return
            for (p in 0 until prims.length()) {
                if (isGeometryTruncated) break
                val prim = prims.optJSONObject(p) ?: continue
                val matColor = resolveMatColor(prim.optInt("material", -1))
                if (matColor == Int.MIN_VALUE) continue
                val rawPos = readPos(prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1) ?: continue
                val pos = transformVerts(rawPos, worldMat)
                val vCount = pos.size / 3
                val mode = prim.optInt("mode", 4)
                val idxArr = if (prim.has("indices")) readIdx(prim.optInt("indices", -1)) else null
                if (mode == 5) {
                    val count = idxArr?.size ?: vCount
                    for (i in 0 until count - 2) {
                        if (isGeometryTruncated) break
                        val i0 = if (i % 2 == 0) (idxArr?.get(i) ?: i) else (idxArr?.get(i + 1) ?: (i + 1))
                        val i1 = if (i % 2 == 0) (idxArr?.get(i + 1) ?: (i + 1)) else (idxArr?.get(i) ?: i)
                        val i2 = idxArr?.get(i + 2) ?: (i + 2)
                        if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                            addTriangle(
                                pos[i0 * 3], pos[i0 * 3 + 1], pos[i0 * 3 + 2],
                                pos[i1 * 3], pos[i1 * 3 + 1], pos[i1 * 3 + 2],
                                pos[i2 * 3], pos[i2 * 3 + 1], pos[i2 * 3 + 2],
                                matColor
                            )
                        }
                    }
                } else if (idxArr != null) {
                    var i = 0
                    while (i + 2 < idxArr.size && !isGeometryTruncated) {
                        val i0 = idxArr[i]; val i1 = idxArr[i + 1]; val i2 = idxArr[i + 2]
                        if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                            addTriangle(
                                pos[i0 * 3], pos[i0 * 3 + 1], pos[i0 * 3 + 2],
                                pos[i1 * 3], pos[i1 * 3 + 1], pos[i1 * 3 + 2],
                                pos[i2 * 3], pos[i2 * 3 + 1], pos[i2 * 3 + 2],
                                matColor
                            )
                        }
                        i += 3
                    }
                } else {
                    var i = 0
                    while (i + 2 < vCount && !isGeometryTruncated) {
                        addTriangle(
                            pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2],
                            pos[(i + 1) * 3], pos[(i + 1) * 3 + 1], pos[(i + 1) * 3 + 2],
                            pos[(i + 2) * 3], pos[(i + 2) * 3 + 1], pos[(i + 2) * 3 + 2],
                            matColor
                        )
                        i += 3
                    }
                }
            }
        }

        val identityMat = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray {
            val res = FloatArray(16)
            for (c in 0..3) {
                for (r in 0..3) {
                    res[c * 4 + r] =
                        a[0 * 4 + r] * b[c * 4 + 0] +
                                a[1 * 4 + r] * b[c * 4 + 1] +
                                a[2 * 4 + r] * b[c * 4 + 2] +
                                a[3 * 4 + r] * b[c * 4 + 3]
                }
            }
            return res
        }

        fun computeNodeLocalMatrix(node: JSONObject): FloatArray {
            val matArr = node.optJSONArray("matrix")
            if (matArr != null && matArr.length() == 16) {
                return FloatArray(16) { matArr.optDouble(it, if (it % 5 == 0) 1.0 else 0.0).toFloat() }
            }
            val t = node.optJSONArray("translation")
            val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
            val r = node.optJSONArray("rotation")
            val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
            val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
            val s = node.optJSONArray("scale")
            val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
            val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
            val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
            val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
            val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
            val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
            val wx = qw * x2; val wy = qw * y2; val wz = qw * z2
            return floatArrayOf(
                (1f - (yy + zz)) * sx, (xy + wz) * sx, (xz - wy) * sx, 0f,
                (xy - wz) * sy, (1f - (xx + zz)) * sy, (yz + wx) * sy, 0f,
                (xz + wy) * sz, (yz - wx) * sz, (1f - (xx + yy)) * sz, 0f,
                tx, ty, tz, 1f
            )
        }

        val nodes = root.optJSONArray("nodes")
        val visitedNodes = HashSet<Int>()
        if (nodes != null && nodes.length() > 0) {
            fun traverseNode(nodeIdx: Int, parentMat: FloatArray, depth: Int) {
                if (depth > 32 || isGeometryTruncated || nodeIdx !in 0 until nodes.length() || !visitedNodes.add(nodeIdx)) return
                val nodeObj = nodes.optJSONObject(nodeIdx) ?: return
                val worldMat = multiplyMat4(parentMat, computeNodeLocalMatrix(nodeObj))
                if (nodeObj.has("mesh")) {
                    emitMeshAtMatrix(nodeObj.optInt("mesh", -1), worldMat)
                }
                val children = nodeObj.optJSONArray("children")
                if (children != null) {
                    for (c in 0 until children.length()) {
                        traverseNode(children.optInt(c, -1), worldMat, depth + 1)
                    }
                }
            }
            for (n in 0 until nodes.length()) {
                if (n !in visitedNodes && !isGeometryTruncated) {
                    traverseNode(n, identityMat, 0)
                }
            }
        }

        if (vertexCount == 0) {
            for (m in 0 until meshes.length()) {
                if (isGeometryTruncated) break
                emitMeshAtMatrix(m, identityMat)
            }
        }
    }

    private fun parseBlendBytes(rawInput: ByteArray) {
        var bytes = rawInput
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            val compLen = bytes.size.coerceAtLeast(1)
            bytes = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val bos = ByteArrayOutputStream(minOf(compLen * 3, 64 * 1024 * 1024))
                    val tmp = ByteArray(16384)
                    var total = 0
                    while (total < 64 * 1024 * 1024) {
                        val r = gis.read(tmp)
                        if (r <= 0) break
                        total += r
                        if (total > 16 * 1024 * 1024 && (total / compLen) > 150) {
                            throw SecurityException("Превышен безопасный коэффициент сжатия GZIP.")
                        }
                        bos.write(tmp, 0, r)
                    }
                    bos.toByteArray()
                }
            } catch (_: Throwable) { return }
        }
        if (bytes.size < 16 || String(bytes, 0, 7, StandardCharsets.US_ASCII) != "BLENDER") return
        val is17 = bytes[7].toInt().toChar().isDigit() && bytes[8].toInt().toChar().isDigit()
        val ptrSize = if (is17) 8 else if (bytes[7].toInt().toChar() == '_') 4 else 8
        val order = if ((if (is17) bytes[12] else bytes[8]).toInt().toChar() == 'V') ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        var pos = if (is17) 17 else 12
        val bb = ByteBuffer.wrap(bytes).order(order)
        val blocks = ArrayList<BlendBlock>()

        while (pos + 16 <= bytes.size && blocks.size < 200_000) {
            val code = String(bytes, pos, 4, StandardCharsets.US_ASCII).trimEnd('\u0000', ' ')
            val hdrSize = if (is17) 32 else 16 + ptrSize
            if (pos + hdrSize > bytes.size) break
            val len = if (is17) bb.getLong(pos + 16).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else bb.getInt(pos + 4)
            val sdna = if (is17) bb.getInt(pos + 4) else bb.getInt(pos + 8 + ptrSize)
            val nr = if (is17) bb.getLong(pos + 24).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else bb.getInt(pos + 12 + ptrSize)
            val dataStart = pos + hdrSize
            if (len < 0 || dataStart + len > bytes.size) break
            blocks.add(BlendBlock(code, dataStart, len, sdna, nr))
            pos = dataStart + len
            if (code == "ENDB") break
        }

        var mvertStructIdx = -1
        var mpolyStructIdx = -1
        var mloopStructIdx = -1
        var mfaceStructIdx = -1
        val dnaBlock = blocks.firstOrNull { it.code == "DNA1" }
        if (dnaBlock != null && dnaBlock.len >= 16) {
            var dPos = dnaBlock.dataOffset
            val dEnd = dnaBlock.dataOffset + dnaBlock.len
            if (String(bytes, dPos, 8, StandardCharsets.US_ASCII) == "SDNANAME") {
                dPos += 8
                val nameCount = bb.getInt(dPos).coerceIn(0, 65536)
                dPos += 4
                for (i in 0 until nameCount) {
                    while (dPos < dEnd && bytes[dPos].toInt() != 0) dPos++
                    dPos++
                }
                dPos = (dPos + 3) and -4
                if (dPos + 8 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "TYPE") {
                    dPos += 4
                    val typeCount = bb.getInt(dPos).coerceIn(0, 65536)
                    dPos += 4
                    val typeNames = Array(typeCount) { "" }
                    for (i in 0 until typeCount) {
                        val s = dPos
                        while (dPos < dEnd && bytes[dPos].toInt() != 0) dPos++
                        if (dPos > s) typeNames[i] = String(bytes, s, dPos - s, StandardCharsets.US_ASCII)
                        dPos++
                    }
                    dPos = (dPos + 3) and -4
                    if (dPos + 4 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "TLEN") {
                        dPos += 4 + typeCount * 2
                        dPos = (dPos + 3) and -4
                    }
                    if (dPos + 8 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "STRC") {
                        dPos += 4
                        val structCount = bb.getInt(dPos).coerceIn(0, 65536)
                        dPos += 4
                        for (sIdx in 0 until structCount) {
                            if (dPos + 4 > dEnd) break
                            val typeIdx = bb.getShort(dPos).toInt() and 0xFFFF
                            val fieldCount = bb.getShort(dPos + 2).toInt() and 0xFFFF
                            dPos += 4 + fieldCount * 4
                            when (typeNames.getOrNull(typeIdx) ?: "") {
                                "MVert" -> mvertStructIdx = sIdx
                                "MPoly" -> mpolyStructIdx = sIdx
                                "MLoop" -> mloopStructIdx = sIdx
                                "MFace" -> mfaceStructIdx = sIdx
                            }
                        }
                    }
                }
            }
        }

        fun extractFromDataBlocks(dataBlocks: List<BlendBlock>) {
            var vertCoords: FloatArray? = null
            var vertCount = 0
            val loops = ArrayList<Int>()
            val polyLoops = ArrayList<Pair<Int, Int>>()
            val polyOffsets = ArrayList<Int>()

            for (blk in dataBlocks) {
                val blkOffset = blk.dataOffset
                val blkLen = blk.len
                if (blkLen < 12) continue

                if (blk.sdnaIndex == mvertStructIdx && mvertStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(12)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    val coords = FloatArray(safeCount * 3)
                    for (v in 0 until safeCount) {
                        val p = blkOffset + v * stride
                        coords[v * 3] = bb.getFloat(p); coords[v * 3 + 1] = bb.getFloat(p + 4); coords[v * 3 + 2] = bb.getFloat(p + 8)
                    }
                    vertCoords = coords; vertCount = safeCount
                    continue
                }
                if (blk.sdnaIndex == mloopStructIdx && mloopStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(4)
                    val safeCount = minOf(blk.count, blkLen / stride, 1_000_000)
                    for (k in 0 until safeCount) loops.add(bb.getInt(blkOffset + k * stride))
                    continue
                }
                if (blk.sdnaIndex == mpolyStructIdx && mpolyStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(8)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    for (k in 0 until safeCount) {
                        val p = blkOffset + k * stride
                        val ls = bb.getInt(p); val tl = bb.getInt(p + 4)
                        if (ls >= 0 && tl in 3..1024) polyLoops.add(ls to tl)
                    }
                    continue
                }
                if (blk.sdnaIndex == mfaceStructIdx && mfaceStructIdx >= 0 && blk.count > 0 && vertCoords != null) {
                    val coords = vertCoords
                    val stride = (blkLen / blk.count).coerceAtLeast(16)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    for (k in 0 until safeCount) {
                        if (isGeometryTruncated) break
                        val p = blkOffset + k * stride
                        val v1 = bb.getInt(p); val v2 = bb.getInt(p + 4); val v3 = bb.getInt(p + 8); val v4 = bb.getInt(p + 12)
                        if (v1 in 0 until vertCount && v2 in 0 until vertCount && v3 in 0 until vertCount) {
                            addTriangle(coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2], coords[v2 * 3], coords[v2 * 3 + 1], coords[v2 * 3 + 2], coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2])
                            if (v4 > 0 && v4 in 0 until vertCount) {
                                addTriangle(coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2], coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2], coords[v4 * 3], coords[v4 * 3 + 1], coords[v4 * 3 + 2])
                            }
                        }
                    }
                    continue
                }
                if (vertCoords == null && blkLen % 12 == 0 && (blkLen / 12) in 3..500_000) {
                    val cnt = blkLen / 12
                    val fx = bb.getFloat(blkOffset); val fy = bb.getFloat(blkOffset + 4); val fz = bb.getFloat(blkOffset + 8)
                    if (fx.isFinite() && fy.isFinite() && fz.isFinite() && abs(fx) < 1e4f && abs(fy) < 1e4f && abs(fz) < 1e4f) {
                        val coords = FloatArray(cnt * 3)
                        for (v in 0 until cnt) {
                            val p = blkOffset + v * 12
                            coords[v * 3] = bb.getFloat(p); coords[v * 3 + 1] = bb.getFloat(p + 4); coords[v * 3 + 2] = bb.getFloat(p + 8)
                        }
                        vertCoords = coords; vertCount = cnt
                        continue
                    }
                }
                if (vertCoords != null && vertCount > 0 && blkLen % 4 == 0) {
                    val intCnt = blkLen / 4
                    if (intCnt in 3..1_000_000) {
                        if (bb.getInt(blkOffset) == 0 && polyOffsets.isEmpty() && intCnt >= 2 && bb.getInt(blkOffset + 4) in 3..64) {
                            for (k in 0 until intCnt) polyOffsets.add(bb.getInt(blkOffset + k * 4))
                        } else if (loops.isEmpty() && bb.getInt(blkOffset) in 0 until vertCount) {
                            for (k in 0 until intCnt) loops.add(bb.getInt(blkOffset + k * 4))
                        }
                    }
                }
            }

            val coords = vertCoords ?: return
            if (polyLoops.isEmpty() && polyOffsets.size >= 2) {
                for (i in 0 until polyOffsets.size - 1) {
                    val s = polyOffsets[i]; val e = polyOffsets[i + 1]
                    if (e - s in 3..1024) polyLoops.add(s to (e - s))
                }
            }
            if (polyLoops.isNotEmpty() && loops.isNotEmpty()) {
                for ((ls, tl) in polyLoops) {
                    if (isGeometryTruncated) break
                    if (ls < 0 || tl < 3 || ls + tl > loops.size) continue
                    val i0 = loops[ls]
                    if (i0 !in 0 until vertCount) continue
                    val b0 = i0 * 3
                    for (k in 1 until tl - 1) {
                        val i1 = loops[ls + k]; val i2 = loops[ls + k + 1]
                        if (i1 in 0 until vertCount && i2 in 0 until vertCount) {
                            val b1 = i1 * 3; val b2 = i2 * 3
                            addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                        }
                    }
                }
            }
        }

        var idx = 0
        while (idx < blocks.size && !isGeometryTruncated) {
            if (blocks[idx].code == "ME") {
                val sub = ArrayList<BlendBlock>()
                var j = idx + 1
                while (j < blocks.size && blocks[j].code == "DATA") {
                    sub.add(blocks[j]); j++
                }
                extractFromDataBlocks(sub)
                idx = j
            } else idx++
        }
        if (vertexCount == 0) {
            extractFromDataBlocks(blocks.filter { it.code == "DATA" })
        }
    }

    private fun parseUsdStream() {
        val head = readSliceAt(0L, 16)
        if (head.size < 4) return
        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            val compContainerLen = readableSource.size.coerceAtLeast(1L)
            openSafeStream { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    val chunk = ByteArray(16384)
                    var totalUnpacked = 0L
                    while (!isGeometryTruncated) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory) {
                            val bos = ByteArrayOutputStream()
                            var entryRead = 0
                            while (entryRead < 48 * 1024 * 1024) {
                                val r = zis.read(chunk)
                                if (r <= 0) break
                                bos.write(chunk, 0, r)
                                entryRead += r
                                totalUnpacked += r
                                if (totalUnpacked > 48 * 1024 * 1024L && (totalUnpacked / compContainerLen) > 150L) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия USDZ.")
                                }
                            }
                            val entryBytes = bos.toByteArray()
                            when {
                                name.endsWith(".usda") || (name.endsWith(".usd") && entryBytes.size >= 5 && String(entryBytes, 0, 5, StandardCharsets.US_ASCII) == "#usda") ->
                                    parseUsdaText(String(entryBytes, StandardCharsets.UTF_8))
                                name.endsWith(".usdc") || (entryBytes.size >= 8 && String(entryBytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") ->
                                    parseUsdcBinary(entryBytes)
                                name.endsWith(".glb") || name.endsWith(".gltf") ->
                                    parseGltfOrGlbStreaming(entryBytes)
                                (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) && previewBitmap == null -> {
                                    if (entryBytes.size in 64..(12 * 1024 * 1024)) {
                                        previewBitmap = BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.size)
                                    }
                                }
                            }
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
            if (vertexCount > 0) {
                previewBitmap?.recycle()
                previewBitmap = null
            }
            return
        }
        val bytes = readHeaderOrFullBytes(64 * 1024 * 1024)
        if (bytes.size >= 8 && String(bytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") {
            parseUsdcBinary(bytes)
        } else {
            parseUsdaText(String(bytes, StandardCharsets.UTF_8))
        }
    }

    private fun parseUsdaText(txt: String) {
        val pointsRegex = Regex("""point3f\[\]\s+points\s*=\s*\[([\s\S]*?)\]""")
        val countsRegex = Regex("""int\[\]\s+faceVertexCounts\s*=\s*\[([\s\S]*?)\]""")
        val indicesRegex = Regex("""int\[\]\s+faceVertexIndices\s*=\s*\[([\s\S]*?)\]""")
        val tupleRegex = Regex("""\(\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*\)""")

        val ptsList = pointsRegex.findAll(txt).toList()
        val cntList = countsRegex.findAll(txt).toList()
        val idxList = indicesRegex.findAll(txt).toList()
        val meshCount = minOf(ptsList.size, idxList.size)

        for (m in 0 until meshCount) {
            if (isGeometryTruncated) break
            val verts = ArrayList<FloatArray>()
            for (tm in tupleRegex.findAll(ptsList[m].groupValues[1])) {
                if (verts.size >= 500_000) break
                verts.add(floatArrayOf(tm.groupValues[1].toFloatOrNull() ?: 0f, tm.groupValues[2].toFloatOrNull() ?: 0f, tm.groupValues[3].toFloatOrNull() ?: 0f))
            }
            if (verts.isEmpty()) continue
            val indices = idxList[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
            val counts = if (m < cntList.size) cntList[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() } else emptyList()
            if (counts.isNotEmpty()) {
                var cur = 0
                for (c in counts) {
                    if (isGeometryTruncated) break
                    if (c in 3..1024 && cur + c <= indices.size) {
                        val i0 = indices[cur]
                        if (i0 in verts.indices) {
                            val v0 = verts[i0]
                            for (k in 1 until c - 1) {
                                val i1 = indices[cur + k]; val i2 = indices[cur + k + 1]
                                if (i1 in verts.indices && i2 in verts.indices) {
                                    addTriangle(v0[0], v0[1], v0[2], verts[i1][0], verts[i1][1], verts[i1][2], verts[i2][0], verts[i2][1], verts[i2][2])
                                }
                            }
                        }
                    }
                    cur += c.coerceAtLeast(1)
                }
            } else {
                var k = 0
                while (k + 2 < indices.size && !isGeometryTruncated) {
                    val i0 = indices[k]; val i1 = indices[k + 1]; val i2 = indices[k + 2]
                    if (i0 in verts.indices && i1 in verts.indices && i2 in verts.indices) {
                        addTriangle(verts[i0][0], verts[i0][1], verts[i0][2], verts[i1][0], verts[i1][1], verts[i1][2], verts[i2][0], verts[i2][1], verts[i2][2])
                    }
                    k += 3
                }
            }
        }
    }

    private fun parseUsdcBinary(bytes: ByteArray) {
        if (bytes.size < 64) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 64
        val limit = minOf(bytes.size - 36, 32 * 1024 * 1024)
        fun okFloat(v: Float) = v.isFinite() && (v == 0f || abs(v) in 1e-4f..5000f)
        while (pos <= limit && !isGeometryTruncated) {
            val x1 = bb.getFloat(pos); val y1 = bb.getFloat(pos + 4); val z1 = bb.getFloat(pos + 8)
            val x2 = bb.getFloat(pos + 12); val y2 = bb.getFloat(pos + 16); val z2 = bb.getFloat(pos + 20)
            val x3 = bb.getFloat(pos + 24); val y3 = bb.getFloat(pos + 28); val z3 = bb.getFloat(pos + 32)
            if (okFloat(x1) && okFloat(y1) && okFloat(z1) && okFloat(x2) && okFloat(y2) && okFloat(z2) && okFloat(x3) && okFloat(y3) && okFloat(z3)) {
                val d12 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1)
                val d23 = (x3 - x2) * (x3 - x2) + (y3 - y2) * (y3 - y2) + (z3 - z2) * (z3 - z2)
                if (d12 in 1e-5f..1e6f && d23 in 1e-5f..1e6f) {
                    addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3)
                    pos += 36
                    continue
                }
            }
            pos += 4
        }
    }

    private fun parseFbxBytes(bytes: ByteArray) {
        if (bytes.size < 32) return
        val headAscii = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.US_ASCII)
        if (!headAscii.startsWith("Kaydara FBX Binary")) {
            val text = String(bytes, StandardCharsets.UTF_8)
            val vertsMatches = Regex("""Vertices:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val idxMatches = Regex("""PolygonVertexIndex:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val mCount = minOf(vertsMatches.size, idxMatches.size)
            for (m in 0 until mCount) {
                if (isGeometryTruncated) break
                val coords = vertsMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                val indices = idxMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }.toIntArray()
                emitFbxMesh(coords, indices)
            }
            return
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        val is64Bit = version >= 7500

        fun inflateArr(offset: Int, compLen: Int, expected: Int): ByteArray? {
            if (offset < 0 || compLen <= 0 || offset + compLen > bytes.size || expected <= 0 || expected > 48 * 1024 * 1024) return null
            for (nowrap in booleanArrayOf(false, true)) {
                val inf = Inflater(nowrap)
                try {
                    inf.setInput(bytes, offset, compLen)
                    val out = ByteArray(expected)
                    var p = 0
                    while (!inf.finished() && p < expected) {
                        val r = inf.inflate(out, p, expected - p)
                        if (r <= 0) break
                        p += r
                    }
                    if (p > 0) return out
                } catch (_: Throwable) {
                } finally {
                    try { inf.end() } catch (_: Throwable) {}
                }
            }
            return null
        }

        fun readFloatArr(propPos: Int, propEnd: Int): FloatArray? {
            if (propPos + 13 > propEnd) return null
            val tc = bytes[propPos].toInt().toChar()
            if (tc != 'd' && tc != 'f') return null
            val es = if (tc == 'd') 8 else 4
            val arrLen = bb.getInt(propPos + 1).coerceIn(0, 1_500_000)
            val enc = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dStart = propPos + 13
            if (arrLen < 3) return null
            val raw = if (enc == 0) {
                if (dStart + arrLen * es > bytes.size) return null
                bytes.copyOfRange(dStart, dStart + arrLen * es)
            } else inflateArr(dStart, compLen, arrLen * es) ?: return null
            val rbb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val cnt = minOf(arrLen, raw.size / es)
            return FloatArray(cnt) { i -> if (es == 8) rbb.getDouble(i * 8).toFloat() else rbb.getFloat(i * 4) }
        }

        fun readIntArr(propPos: Int, propEnd: Int): IntArray? {
            if (propPos + 13 > propEnd || bytes[propPos].toInt().toChar() != 'i') return null
            val arrLen = bb.getInt(propPos + 1).coerceIn(0, 3_000_000)
            val enc = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dStart = propPos + 13
            if (arrLen < 3) return null
            val raw = if (enc == 0) {
                if (dStart + arrLen * 4 > bytes.size) return null
                bytes.copyOfRange(dStart, dStart + arrLen * 4)
            } else inflateArr(dStart, compLen, arrLen * 4) ?: return null
            val rbb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val cnt = minOf(arrLen, raw.size / 4)
            return IntArray(cnt) { i -> rbb.getInt(i * 4) }
        }

        var activeV: FloatArray? = null
        var activeI: IntArray? = null
        fun flushGeom() {
            val v = activeV; val idx = activeI
            if (v != null && idx != null) emitFbxMesh(v, idx)
            activeV = null; activeI = null
        }

        fun walkNodes(startOffset: Int, endLimit: Int, depth: Int) {
            if (depth > 24 || isGeometryTruncated) return
            var pos = startOffset
            val minHdr = if (is64Bit) 25 else 13
            while (pos + minHdr <= endLimit && !isGeometryTruncated) {
                val endOff = if (is64Bit) bb.getLong(pos).toInt() else bb.getInt(pos)
                val numProps = if (is64Bit) bb.getLong(pos + 8).toInt() else bb.getInt(pos + 4)
                val propListLen = if (is64Bit) bb.getLong(pos + 16).toInt() else bb.getInt(pos + 8)
                val nameLen = bytes[pos + (if (is64Bit) 24 else 12)].toInt() and 0xFF
                val nameStart = pos + minHdr
                if (endOff == 0) break
                if (endOff <= pos || endOff > bytes.size || nameStart + nameLen > endOff) break
                val name = if (nameLen > 0) String(bytes, nameStart, nameLen, StandardCharsets.US_ASCII) else ""
                val propsStart = nameStart + nameLen
                val childrenStart = propsStart + propListLen
                when (name) {
                    "Geometry" -> {
                        flushGeom()
                        if (childrenStart < endOff) walkNodes(childrenStart, endOff, depth + 1)
                        flushGeom()
                    }
                    "Vertices" -> if (numProps >= 1 && propsStart < endOff) activeV = readFloatArr(propsStart, endOff)
                    "PolygonVertexIndex" -> if (numProps >= 1 && propsStart < endOff) activeI = readIntArr(propsStart, endOff)
                    else -> if (childrenStart in propsStart..(endOff - minHdr)) walkNodes(childrenStart, endOff, depth + 1)
                }
                pos = endOff
            }
        }
        walkNodes(27, bytes.size, 0)
        flushGeom()
    }

    private fun emitFbxMesh(coords: FloatArray, indices: IntArray) {
        val vCount = coords.size / 3
        if (vCount < 3 || indices.size < 3) return
        val poly = IntArray(64)
        var pLen = 0
        for (raw in indices) {
            if (isGeometryTruncated) break
            val isEnd = raw < 0
            val idx = if (isEnd) raw.inv() else raw
            if (pLen < poly.size) poly[pLen++] = idx
            if (isEnd) {
                if (pLen >= 3) {
                    val i0 = poly[0]
                    if (i0 in 0 until vCount) {
                        val b0 = i0 * 3
                        for (k in 1 until pLen - 1) {
                            val i1 = poly[k]; val i2 = poly[k + 1]
                            if (i1 in 0 until vCount && i2 in 0 until vCount) {
                                val b1 = i1 * 3; val b2 = i2 * 3
                                addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                            }
                        }
                    }
                }
                pLen = 0
            }
        }
    }

    private fun parse3mfStream() {
        val fileSize = readableSource.size.coerceAtLeast(1L)
        try {
            openSafeStream { raw ->
                ZipInputStream(raw).use { zis ->
                    var totalUnpacked = 0L
                    val buf = ByteArray(16384)
                    while (!isGeometryTruncated) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        val name = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                        if (!entry.isDirectory && (name.endsWith(".model") || (name.startsWith("3d/") && name.endsWith(".xml")))) {
                            modelType = "3MF 3D Model"
                            val bos = ByteArrayOutputStream()
                            var entryRead = 0
                            while (entryRead < 48 * 1024 * 1024) {
                                val r = zis.read(buf)
                                if (r <= 0) break
                                bos.write(buf, 0, r)
                                entryRead += r; totalUnpacked += r
                                if (totalUnpacked > 48 * 1024 * 1024L && totalUnpacked / fileSize > 150L) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия 3MF.")
                                }
                            }
                            val xml = EbookTextSession.decodeAndPreprocessXml(bos.toByteArray())
                            val parser = EbookTextSession.createSafeXmlParser()
                            parser.setInput(StringReader(xml))
                            var vertCoords = FloatArray(30_000)
                            var vertCount = 0
                            val colorGroups = HashMap<String, MutableList<Int>>()
                            var activeGroupId = ""
                            var defaultObjColor = 0
                            fun parseHexCol(hex: String?): Int {
                                if (hex.isNullOrBlank() || !hex.startsWith("#")) return 0
                                val rgb = hex.removePrefix("#").trim().take(6).toIntOrNull(16) ?: return 0
                                return (0xFF shl 24) or rgb
                            }
                            var ev = parser.eventType
                            while (ev != XmlPullParser.END_DOCUMENT && !isGeometryTruncated) {
                                if (ev == XmlPullParser.START_TAG) {
                                    when (parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: "") {
                                        "basematerials", "colorgroup" -> {
                                            activeGroupId = parser.getAttributeValue(null, "id") ?: ""
                                            if (activeGroupId.isNotEmpty()) colorGroups[activeGroupId] = ArrayList()
                                        }
                                        "base", "color" -> {
                                            val cStr = parser.getAttributeValue(null, "displaycolor") ?: parser.getAttributeValue(null, "color")
                                            val col = parseHexCol(cStr)
                                            if (activeGroupId.isNotEmpty() && col != 0) colorGroups[activeGroupId]?.add(col)
                                        }
                                        "object" -> {
                                            val pid = parser.getAttributeValue(null, "pid") ?: ""
                                            val pindex = parser.getAttributeValue(null, "pindex")?.toIntOrNull() ?: 0
                                            defaultObjColor = colorGroups[pid]?.getOrNull(pindex) ?: 0
                                        }
                                        "mesh" -> vertCount = 0
                                        "vertex" -> {
                                            val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                                            val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                                            val z = parser.getAttributeValue(null, "z")?.toFloatOrNull() ?: 0f
                                            if ((vertCount + 1) * 3 > vertCoords.size && vertCoords.size < 9_000_000) {
                                                vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                                            }
                                            if ((vertCount + 1) * 3 <= vertCoords.size) {
                                                val b = vertCount * 3
                                                vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                                                vertCount++
                                            }
                                        }
                                        "triangle" -> {
                                            val v1 = parser.getAttributeValue(null, "v1")?.toIntOrNull() ?: -1
                                            val v2 = parser.getAttributeValue(null, "v2")?.toIntOrNull() ?: -1
                                            val v3 = parser.getAttributeValue(null, "v3")?.toIntOrNull() ?: -1
                                            val pid = parser.getAttributeValue(null, "pid")
                                            val p1 = parser.getAttributeValue(null, "p1")?.toIntOrNull()
                                            val triCol = if (p1 != null) {
                                                val grp = if (pid != null) colorGroups[pid] else colorGroups.values.firstOrNull()
                                                grp?.getOrNull(p1) ?: defaultObjColor
                                            } else defaultObjColor
                                            if (v1 in 0 until vertCount && v2 in 0 until vertCount && v3 in 0 until vertCount) {
                                                val b1 = v1 * 3; val b2 = v2 * 3; val b3 = v3 * 3
                                                addTriangle(vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2], vertCoords[b3], vertCoords[b3 + 1], vertCoords[b3 + 2], triCol)
                                            }
                                        }
                                    }
                                }
                                ev = parser.next()
                            }
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseColladaBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val xml = EbookTextSession.decodeAndPreprocessXml(bytes)
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(StringReader(xml))
            val floatArraysById = HashMap<String, FloatArray>()
            val sourceToArrayId = HashMap<String, String>()
            val verticesToSourceId = HashMap<String, String>()
            var curSourceId = ""
            var curVerticesId = ""
            var inPrim = false
            var primType = ""
            var vOffset = 0
            var maxOffset = 0
            var curVSource = ""
            val vcountList = ArrayList<Int>()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT && !isGeometryTruncated) {
                val tag = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "source" -> curSourceId = parser.getAttributeValue(null, "id") ?: ""
                        "float_array" -> {
                            val fId = parser.getAttributeValue(null, "id") ?: ""
                            if (curSourceId.isNotEmpty() && fId.isNotEmpty()) sourceToArrayId[curSourceId] = fId
                            val floats = parser.nextText().trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                            if (fId.isNotEmpty()) floatArraysById[fId] = floats
                            if (curSourceId.isNotEmpty()) floatArraysById[curSourceId] = floats
                        }
                        "vertices" -> curVerticesId = parser.getAttributeValue(null, "id") ?: ""
                        "triangles", "polylist", "polygons" -> {
                            inPrim = true; primType = tag; vOffset = 0; maxOffset = 0; vcountList.clear()
                        }
                        "input" -> {
                            val sem = parser.getAttributeValue(null, "semantic")?.uppercase(Locale.ROOT) ?: ""
                            val src = (parser.getAttributeValue(null, "source") ?: "").removePrefix("#")
                            val off = parser.getAttributeValue(null, "offset")?.toIntOrNull() ?: 0
                            if (inPrim) {
                                if (off > maxOffset) maxOffset = off
                                if (sem == "VERTEX" || sem == "POSITION") {
                                    vOffset = off
                                    curVSource = verticesToSourceId[src] ?: src
                                }
                            } else if (curVerticesId.isNotEmpty() && sem == "POSITION") {
                                verticesToSourceId[curVerticesId] = src
                            }
                        }
                        "vcount" -> parser.nextText().trim().split(Regex("\\s+")).mapNotNullTo(vcountList) { it.toIntOrNull() }
                        "p" -> if (inPrim) {
                            val ints = parser.nextText().trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.toIntArray()
                            val coords = floatArraysById[curVSource] ?: floatArraysById[sourceToArrayId[curVSource]] ?: floatArraysById.values.firstOrNull()
                            if (coords != null && coords.size >= 9) {
                                val stride = (maxOffset + 1).coerceAtLeast(1)
                                val vCount = coords.size / 3
                                if (primType == "polylist" && vcountList.isNotEmpty()) {
                                    var cur = 0
                                    for (vc in vcountList) {
                                        if (isGeometryTruncated) break
                                        if (vc >= 3 && (cur + vc) * stride <= ints.size) {
                                            val i0 = ints[cur * stride + vOffset]
                                            if (i0 in 0 until vCount) {
                                                val b0 = i0 * 3
                                                for (k in 1 until vc - 1) {
                                                    val i1 = ints[(cur + k) * stride + vOffset]
                                                    val i2 = ints[(cur + k + 1) * stride + vOffset]
                                                    if (i1 in 0 until vCount && i2 in 0 until vCount) {
                                                        val b1 = i1 * 3; val b2 = i2 * 3
                                                        addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                                                    }
                                                }
                                            }
                                        }
                                        cur += vc.coerceAtLeast(1)
                                    }
                                } else {
                                    val totalRefs = ints.size / stride
                                    var t = 0
                                    while (t + 2 < totalRefs && !isGeometryTruncated) {
                                        val i0 = ints[t * stride + vOffset]
                                        val i1 = ints[(t + 1) * stride + vOffset]
                                        val i2 = ints[(t + 2) * stride + vOffset]
                                        if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                            val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                                            addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                                        }
                                        t += 3
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (tag in setOf("triangles", "polylist", "polygons")) inPrim = false
                }
                ev = parser.next()
            }
        } catch (_: Throwable) {}
    }

    private fun parseStepStream(stream: java.io.InputStream) {
        val cartesianPoints = HashMap<Int, FloatArray>(8192)
        val vertexPointToCartesian = HashMap<Int, Int>(4096)
        val edgePairs = ArrayList<Pair<Int, Int>>(4096)
        val polyLoops = ArrayList<IntArray>(1024)
        val entityRegex = Regex("""#(\d+)\s*=\s*([A-Z0-9_]+)\s*\(([\s\S]*)\)\s*;""")
        val coordTupleRegex = Regex("""\(\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)(?:\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?))?\s*\)""")
        val refRegex = Regex("""#(\d+)""")

        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val stmtSb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null && cartesianPoints.size < 600_000) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("/*")) continue
                stmtSb.append(trimmed)
                if (!trimmed.endsWith(";")) {
                    if (stmtSb.length > 65536) stmtSb.setLength(0)
                    continue
                }
                val stmt = stmtSb.toString()
                stmtSb.setLength(0)
                val m = entityRegex.find(stmt) ?: continue
                val id = m.groupValues[1].toIntOrNull() ?: continue
                val type = m.groupValues[2]
                val args = m.groupValues[3]
                when (type) {
                    "CARTESIAN_POINT" -> coordTupleRegex.find(args)?.let { cm ->
                        cartesianPoints[id] = floatArrayOf(cm.groupValues[1].toFloatOrNull() ?: 0f, cm.groupValues[2].toFloatOrNull() ?: 0f, cm.groupValues[3].toFloatOrNull() ?: 0f)
                    }
                    "VERTEX_POINT" -> refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()?.let { vertexPointToCartesian[id] = it }
                    "EDGE_CURVE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                        if (refs.size >= 2 && edgePairs.size < 200_000) edgePairs.add(refs[0] to refs[1])
                    }
                    "POLY_LOOP", "B_SPLINE_CURVE_WITH_KNOTS", "BEZIER_CURVE", "POLYLINE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList().toIntArray()
                        if (refs.size >= 2 && polyLoops.size < 100_000) polyLoops.add(refs)
                    }
                }
            }
        }
        fun resolvePt(id: Int): FloatArray? = cartesianPoints[id] ?: vertexPointToCartesian[id]?.let { cartesianPoints[it] }
        val cadColor = aciToColor(4)
        for (loop in polyLoops) {
            if (isGeometryTruncated) break
            val pts = loop.map { resolvePt(it) }.filterNotNull()
            if (pts.size >= 3) {
                val p0 = pts[0]
                for (k in 1 until pts.size - 1) {
                    val p1 = pts[k]; val p2 = pts[k + 1]
                    addTriangle(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
                }
            } else if (pts.size == 2) {
                addLineSegment(pts[0][0], pts[0][1], pts[0][2], pts[1][0], pts[1][1], pts[1][2], cadColor)
            }
        }
        for ((id1, id2) in edgePairs) {
            if (isGeometryTruncated) break
            val p1 = resolvePt(id1) ?: continue
            val p2 = resolvePt(id2) ?: continue
            addLineSegment(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
        }
        if (vertexCount == 0 && cartesianPoints.size >= 2) {
            val sortedPts = cartesianPoints.entries.sortedBy { it.key }.map { it.value }
            for (i in 0 until sortedPts.size - 1) {
                if (isGeometryTruncated) break
                val p1 = sortedPts[i]; val p2 = sortedPts[i + 1]
                addLineSegment(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
            }
        }
    }

    private fun parseIgesStream(stream: java.io.InputStream) {
        val paramStatements = ArrayList<String>()
        val currentSb = StringBuilder()
        BufferedReader(InputStreamReader(stream, StandardCharsets.US_ASCII)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null && paramStatements.size < 200_000) {
                val raw = line!!
                if (raw.length >= 73 && raw[72] == 'P') {
                    val dataSlice = raw.substring(0, 64).trim()
                    currentSb.append(dataSlice)
                    if (dataSlice.contains(';')) {
                        paramStatements.add(currentSb.toString().substringBefore(';'))
                        currentSb.setLength(0)
                    }
                }
            }
        }
        val cadColor = aciToColor(3)
        for (stmt in paramStatements) {
            if (isGeometryTruncated) break
            val nums = stmt.split(',').mapNotNull { it.trim().replace('D', 'E').replace('d', 'e').toFloatOrNull() }
            if (nums.isEmpty()) continue
            when (nums[0].toInt()) {
                110 -> if (nums.size >= 7) addLineSegment(nums[1], nums[2], nums[3], nums[4], nums[5], nums[6], cadColor)
                106 -> if (nums.size >= 7) {
                    val ip = nums[1].toInt()
                    val n = nums[2].toInt().coerceIn(2, 10_000)
                    if (ip == 1 && nums.size >= 4 + n * 2) {
                        val z = nums[3]
                        for (i in 0 until n - 1) {
                            val b = 4 + i * 2
                            addLineSegment(nums[b], nums[b + 1], z, nums[b + 2], nums[b + 3], z, cadColor)
                        }
                    } else if (ip == 2 && nums.size >= 3 + n * 3) {
                        for (i in 0 until n - 1) {
                            val b = 3 + i * 3
                            addLineSegment(nums[b], nums[b + 1], nums[b + 2], nums[b + 3], nums[b + 4], nums[b + 5], cadColor)
                        }
                    }
                }
            }
        }
    }

    private fun parseDwgBytes(bytes: ByteArray) {
        if (bytes.size < 32) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val previewAddr = bb.getInt(0x0D)
        var sentinelPos = -1
        if (previewAddr in 0x14..(bytes.size - 32) && (0 until 16).all { bytes[previewAddr + it] == DWG_IMAGE_SENTINEL[it] }) {
            sentinelPos = previewAddr
        }
        if (sentinelPos < 0) {
            for (i in 0x14..(bytes.size - 32)) {
                if (bytes[i] == DWG_IMAGE_SENTINEL[0] && (0 until 16).all { bytes[i + it] == DWG_IMAGE_SENTINEL[it] }) {
                    sentinelPos = i; break
                }
            }
        }
        if (sentinelPos >= 0 && sentinelPos + 21 <= bytes.size) {
            val dirPos = sentinelPos + 16
            val imgCount = (bytes[dirPos + 4].toInt() and 0xFF).coerceIn(0, 16)
            var recPos = dirPos + 5
            for (i in 0 until imgCount) {
                if (recPos + 9 > bytes.size) break
                val code = bytes[recPos].toInt() and 0xFF
                val off = bb.getInt(recPos + 1)
                val sz = bb.getInt(recPos + 5)
                recPos += 9
                if (off > 0 && sz > 16 && off.toLong() + sz.toLong() <= bytes.size.toLong()) {
                    if (code == 6 || code == 2) {
                        previewBitmap = EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes, off, sz)
                        if (previewBitmap != null) break
                    }
                }
            }
        }
        val scanEnd = minOf(bytes.size - 48, 1024 * 1024)
        var i = minOf(0x100, bytes.size)
        var segs = 0
        fun okDouble(v: Double) = v.isFinite() && (v == 0.0 || abs(v) in 0.01..50_000.0)
        while (i <= scanEnd && segs < 4000) {
            val x1 = bb.getDouble(i); val y1 = bb.getDouble(i + 8); val z1 = bb.getDouble(i + 16)
            val x2 = bb.getDouble(i + 24); val y2 = bb.getDouble(i + 32); val z2 = bb.getDouble(i + 40)
            if (okDouble(x1) && okDouble(y1) && okDouble(z1) && okDouble(x2) && okDouble(y2) && okDouble(z2)) {
                val dx = (x2 - x1).toFloat(); val dy = (y2 - y1).toFloat(); val dz = (z2 - z1).toFloat()
                if (dx * dx + dy * dy + dz * dz in 0.25f..1_000_000f) {
                    addLineSegment(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), aciToColor((segs % 5) + 1))
                    segs++; i += 48; continue
                }
            }
            i += 8
        }
        if (vertexCount == 0 && previewBitmap == null) {
            val w = 160f; val h = 110f
            addLineSegment(-w, -h, 0f, w, -h, 0f, aciToColor(4))
            addLineSegment(w, -h, 0f, w, h, 0f, aciToColor(4))
            addLineSegment(w, h, 0f, -w, h, 0f, aciToColor(4))
            addLineSegment(-w, h, 0f, -w, -h, 0f, aciToColor(4))
        }
    }

    private fun parse3dsStreaming(fileSize: Long) {
        val limit = if (fileSize > 0L) fileSize else 128L * 1024L * 1024L
        if (limit < 16L) return
        var currentVerts: FloatArray? = null
        var currentVertCount = 0

        fun walk(start: Long, endLimit: Long, depth: Int) {
            if (depth > 16) return
            var pos = start
            while (pos + 6L <= endLimit && !isGeometryTruncated) {
                val hdr = readSliceAt(pos, 6)
                if (hdr.size < 6) break
                val hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val chunkId = hbb.getShort(0).toInt() and 0xFFFF
                val chunkLen = hbb.getInt(2).toLong() and 0xFFFFFFFFL
                if (chunkLen < 6L) break
                val nextPos = minOf(endLimit, pos + chunkLen)
                val dataPos = pos + 6L
                when (chunkId) {
                    0x4D4D, 0x3D3D, 0x4100 -> walk(dataPos, nextPos, depth + 1)
                    0x4000 -> {
                        val probe = readSliceAt(dataPos, minOf(256L, nextPos - dataPos).toInt())
                        var sLen = 0
                        while (sLen < probe.size && probe[sLen].toInt() != 0) sLen++
                        if (dataPos + sLen + 1L < nextPos) walk(dataPos + sLen + 1L, nextPos, depth + 1)
                    }
                    0x4110 -> {
                        val pLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (pLen >= 2) {
                            val cb = readSliceAt(dataPos, pLen)
                            val bb = ByteBuffer.wrap(cb).order(ByteOrder.LITTLE_ENDIAN)
                            val vc = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (cb.size - 2) / 12)
                            val coords = FloatArray(vc * 3)
                            for (i in 0 until vc) {
                                val b = 2 + i * 12
                                coords[i * 3] = bb.getFloat(b); coords[i * 3 + 1] = bb.getFloat(b + 4); coords[i * 3 + 2] = bb.getFloat(b + 8)
                            }
                            currentVerts = coords; currentVertCount = vc
                        }
                    }
                    0x4120 -> {
                        val verts = currentVerts; val vc = currentVertCount
                        val pLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (verts != null && vc > 0 && pLen >= 2) {
                            val cb = readSliceAt(dataPos, pLen)
                            val bb = ByteBuffer.wrap(cb).order(ByteOrder.LITTLE_ENDIAN)
                            val fc = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (cb.size - 2) / 8)
                            for (i in 0 until fc) {
                                val b = 2 + i * 8
                                val i0 = bb.getShort(b).toInt() and 0xFFFF
                                val i1 = bb.getShort(b + 2).toInt() and 0xFFFF
                                val i2 = bb.getShort(b + 4).toInt() and 0xFFFF
                                if (i0 in 0 until vc && i1 in 0 until vc && i2 in 0 until vc) {
                                    val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(verts[b0], verts[b0 + 1], verts[b0 + 2], verts[b1], verts[b1 + 1], verts[b1 + 2], verts[b2], verts[b2 + 1], verts[b2 + 2])
                                }
                            }
                        }
                    }
                }
                if (nextPos <= pos) break
                pos = nextPos
            }
        }
        walk(0L, limit, 0)
    }

    private fun parsePlyStreaming() {
        val headBytes = readSliceAt(0L, 32768)
        if (headBytes.size < 16) return
        val headerAscii = String(headBytes, 0, headBytes.size, StandardCharsets.US_ASCII)
        val endHeaderMatch = Regex("end_header\\r?\\n").find(headerAscii)
        if (endHeaderMatch == null) {
            openSafeStream { parsePlyAscii(it) }
            return
        }
        val headerLines = headerAscii.substring(0, endHeaderMatch.range.first).lines()
        val bodyOffset = (endHeaderMatch.range.last + 1).toLong()
        var format = "ascii"
        var numVerts = 0
        var numFaces = 0
        var curElem = ""
        var vertexStride = 0
        var faceCountBytes = 1
        var faceIndexBytes = 4
        fun tSize(t: String): Int = when (t.lowercase(Locale.ROOT)) {
            "char", "int8", "uchar", "uint8" -> 1
            "short", "int16", "ushort", "uint16" -> 2
            "double", "float64" -> 8
            else -> 4
        }
        for (rawLine in headerLines) {
            val parts = rawLine.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            when (parts[0].lowercase(Locale.ROOT)) {
                "format" -> if (parts.size >= 2) format = parts[1].lowercase(Locale.ROOT)
                "element" -> if (parts.size >= 3) {
                    curElem = parts[1].lowercase(Locale.ROOT)
                    val cnt = parts[2].toIntOrNull()?.coerceIn(0, 5_000_000) ?: 0
                    if (curElem == "vertex") numVerts = cnt else if (curElem == "face") numFaces = cnt
                }
                "property" -> {
                    if (curElem == "vertex" && parts.size >= 3 && !parts[1].equals("list", true)) {
                        vertexStride += tSize(parts[1])
                    } else if (curElem == "face" && parts.size >= 5 && parts[1].equals("list", true)) {
                        faceCountBytes = tSize(parts[2]); faceIndexBytes = tSize(parts[3])
                    }
                }
            }
        }
        if (format == "ascii") {
            openSafeStream { parsePlyAscii(it) }
            return
        }
        val order = if (format.contains("big_endian")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val stride = vertexStride.coerceAtLeast(12)
        val avail = (readableSource.size - bodyOffset).coerceAtLeast(0L)
        val safeVerts = numVerts.coerceIn(0, (avail / stride).coerceAtMost(2_000_000L).toInt())
        if (safeVerts <= 0) return
        val vertBytes = readSliceAt(bodyOffset, safeVerts * stride)
        val vbb = ByteBuffer.wrap(vertBytes).order(order)
        val actualVerts = vertBytes.size / stride
        val vertCoords = FloatArray(actualVerts * 3)
        for (i in 0 until actualVerts) {
            val b = i * stride
            vertCoords[i * 3] = vbb.getFloat(b); vertCoords[i * 3 + 1] = vbb.getFloat(b + 4); vertCoords[i * 3 + 2] = vbb.getFloat(b + 8)
        }
        val facesStart = bodyOffset + safeVerts.toLong() * stride
        val faceBytes = readSliceAt(facesStart, minOf((readableSource.size - facesStart).coerceAtLeast(0L), 64L * 1024L * 1024L).toInt())
        val fbb = ByteBuffer.wrap(faceBytes).order(order)
        fun readIntAt(p: Int, w: Int): Int = when (w) {
            1 -> faceBytes[p].toInt() and 0xFF
            2 -> fbb.getShort(p).toInt() and 0xFFFF
            else -> fbb.getInt(p)
        }
        var pos = 0
        for (f in 0 until numFaces) {
            if (pos + faceCountBytes > faceBytes.size || isGeometryTruncated) break
            val cnt = readIntAt(pos, faceCountBytes)
            pos += faceCountBytes
            val listB = cnt * faceIndexBytes
            if (cnt < 3 || cnt > 256 || pos + listB > faceBytes.size) break
            val i0 = readIntAt(pos, faceIndexBytes)
            if (i0 in 0 until actualVerts) {
                val b0 = i0 * 3
                for (k in 1 until cnt - 1) {
                    val i1 = readIntAt(pos + k * faceIndexBytes, faceIndexBytes)
                    val i2 = readIntAt(pos + (k + 1) * faceIndexBytes, faceIndexBytes)
                    if (i1 in 0 until actualVerts && i2 in 0 until actualVerts) {
                        val b1 = i1 * 3; val b2 = i2 * 3
                        addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                    }
                }
            }
            pos += listB
        }
    }

    private fun parsePlyAscii(stream: java.io.InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertRead = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var numVerts = 0; var numFaces = 0; var inHeader = true
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val t = line!!.trim()
                if (t.isEmpty() || t.startsWith("comment", true)) continue
                if (inHeader) {
                    when {
                        t.startsWith("element vertex", true) -> numVerts = t.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        t.startsWith("element face", true) -> numFaces = t.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        t.equals("end_header", true) -> {
                            inHeader = false
                            if (numVerts in 1..3_000_000) vertCoords = FloatArray(numVerts * 3)
                        }
                    }
                } else if (vertRead < numVerts) {
                    val p = t.split(Regex("\\s+"))
                    if (p.size >= 3) {
                        val x = p[0].toFloatOrNull(); val y = p[1].toFloatOrNull(); val z = p[2].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            if ((vertRead + 1) * 3 > vertCoords.size) vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            val b = vertRead * 3
                            vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                            vertRead++
                        }
                    }
                } else if (numFaces > 0 && !isGeometryTruncated) {
                    val parts = t.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val cnt = parts[0].coerceAtMost(parts.size - 1)
                        val i0 = parts[1]
                        if (cnt >= 3 && i0 in 0 until vertRead) {
                            val b0 = i0 * 3
                            for (k in 2 until cnt) {
                                val i1 = parts[k]; val i2 = parts[k + 1]
                                if (i1 in 0 until vertRead && i2 in 0 until vertRead) {
                                    val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseOffStream(stream: java.io.InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertRead = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var countsRead = false; var numVerts = 0; var line: String?
            while (reader.readLine().also { line = it } != null && !isGeometryTruncated) {
                var clean = line!!.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                if (!countsRead && (clean.startsWith("OFF", true) || clean.startsWith("COFF", true) || clean.startsWith("NOFF", true))) {
                    clean = clean.replaceFirst(Regex("^(?i)(C?N?OFF)\\s*"), "").trim()
                    if (clean.isEmpty()) continue
                }
                if (!countsRead) {
                    val parts = clean.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 2) {
                        numVerts = parts[0].coerceIn(0, 2_000_000)
                        vertCoords = FloatArray(maxOf(30_000, numVerts * 3))
                        countsRead = true
                    }
                } else if (vertRead < numVerts) {
                    val p = clean.split(Regex("\\s+"))
                    if (p.size >= 3) {
                        val x = p[0].toFloatOrNull(); val y = p[1].toFloatOrNull(); val z = p[2].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            if ((vertRead + 1) * 3 > vertCoords.size) vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            val b = vertRead * 3
                            vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                            vertRead++
                        }
                    }
                } else {
                    val parts = clean.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val cnt = parts[0].coerceAtMost(parts.size - 1)
                        val i0 = parts[1]
                        if (cnt >= 3 && i0 in 0 until vertRead) {
                            val b0 = i0 * 3
                            for (k in 2 until cnt) {
                                val i1 = parts[k]; val i2 = parts[k + 1]
                                if (i1 in 0 until vertRead && i2 in 0 until vertRead) {
                                    val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseObjStream(stream: java.io.InputStream) {
        var vertCoords = FloatArray(60_000)
        var vertCount = 0
        val maxVerts = 3_000_000
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            var raw: String?
            val faceIdx = IntArray(64)
            while (reader.readLine().also { raw = it } != null && !isGeometryTruncated) {
                val line = raw!!.trim()
                if (line.isEmpty() || line[0] == '#') continue
                if (line.startsWith("v ")) {
                    if (vertCount < maxVerts) {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 4) {
                            val x = p[1].toFloatOrNull(); val y = p[2].toFloatOrNull(); val z = p[3].toFloatOrNull()
                            if (x != null && y != null && z != null) {
                                if ((vertCount + 1) * 3 > vertCoords.size) {
                                    vertCoords = vertCoords.copyOf(minOf(maxVerts * 3, vertCoords.size * 2))
                                }
                                val b = vertCount * 3
                                vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                                vertCount++
                            }
                        }
                    }
                } else if (line.startsWith("f ") && vertCount >= 3) {
                    val parts = line.split(Regex("\\s+"))
                    var fLen = 0
                    for (k in 1 until parts.size) {
                        if (fLen >= faceIdx.size) break
                        val rawIdx = parts[k].substringBefore('/').toIntOrNull() ?: continue
                        val r = if (rawIdx < 0) vertCount + rawIdx else rawIdx - 1
                        if (r in 0 until vertCount) faceIdx[fLen++] = r
                    }
                    if (fLen >= 3) {
                        val b0 = faceIdx[0] * 3
                        for (i in 1 until fLen - 1) {
                            val b1 = faceIdx[i] * 3; val b2 = faceIdx[i + 1] * 3
                            addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                        }
                    }
                }
            }
        }
    }

    private fun parseDxfStream(stream: java.io.InputStream) {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            val pairs = ArrayList<Pair<Int, String>>(8192)
            while (pairs.size < 1_500_000) {
                val cLine = reader.readLine() ?: break
                val vLine = reader.readLine() ?: break
                val c = cLine.trim().toIntOrNull() ?: continue
                pairs.add(c to vLine.trim())
            }
            var i = 0
            while (i < pairs.size && !isGeometryTruncated) {
                val (code, v) = pairs[i]
                if (code == 0) {
                    val ent = v.uppercase(Locale.ROOT)
                    i++
                    if (ent == "POLYLINE") {
                        var flags = 0; var aci = 7
                        while (i < pairs.size && pairs[i].first != 0) {
                            if (pairs[i].first == 62) aci = pairs[i].second.toIntOrNull() ?: 7
                            if (pairs[i].first == 70) flags = pairs[i].second.toIntOrNull() ?: 0
                            i++
                        }
                        val pts = ArrayList<Point3D>()
                        while (i < pairs.size) {
                            if (pairs[i].first == 0 && pairs[i].second.equals("VERTEX", true)) {
                                i++
                                var vx = 0f; var vy = 0f; var vz = 0f
                                while (i < pairs.size && pairs[i].first != 0) {
                                    when (pairs[i].first) {
                                        10 -> vx = pairs[i].second.toFloatOrNull() ?: 0f
                                        20 -> vy = pairs[i].second.toFloatOrNull() ?: 0f
                                        30 -> vz = pairs[i].second.toFloatOrNull() ?: 0f
                                    }
                                    i++
                                }
                                pts.add(Point3D(vx, vy, vz))
                            } else if (pairs[i].first == 0 && pairs[i].second.equals("SEQEND", true)) {
                                i++; while (i < pairs.size && pairs[i].first != 0) i++; break
                            } else break
                        }
                        val col = aciToColor(aci)
                        for (k in 0 until pts.size - 1) {
                            addLineSegment(pts[k].x, pts[k].y, pts[k].z, pts[k + 1].x, pts[k + 1].y, pts[k + 1].z, col)
                        }
                        if ((flags and 1) != 0 && pts.size >= 3) {
                            addLineSegment(pts.last().x, pts.last().y, pts.last().z, pts.first().x, pts.first().y, pts.first().z, col)
                        }
                        continue
                    }

                    var x1 = 0f; var y1 = 0f; var z1 = 0f
                    var x2 = 0f; var y2 = 0f; var z2 = 0f
                    var x3 = 0f; var y3 = 0f; var z3 = 0f
                    var x4 = 0f; var y4 = 0f; var z4 = 0f
                    var hasV4 = false
                    var radius = 0f; var startDeg = 0f; var endDeg = 360f; var flags = 0; var aci = 7
                    val lwX = ArrayList<Float>(); val lwY = ArrayList<Float>()

                    while (i < pairs.size && pairs[i].first != 0) {
                        val (c, s) = pairs[i]
                        val f = s.toFloatOrNull() ?: 0f
                        when (c) {
                            10 -> { x1 = f; if (ent == "LWPOLYLINE" || ent == "SPLINE") lwX.add(f) }
                            20 -> { y1 = f; if (ent == "LWPOLYLINE" || ent == "SPLINE") lwY.add(f) }
                            30 -> z1 = f
                            11 -> x2 = f; 21 -> y2 = f; 31 -> z2 = f
                            12 -> x3 = f; 22 -> y3 = f; 32 -> z3 = f
                            13 -> { x4 = f; hasV4 = true }; 23 -> { y4 = f; hasV4 = true }; 33 -> { z4 = f; hasV4 = true }
                            40 -> radius = f; 50 -> startDeg = f; 51 -> endDeg = f
                            62 -> aci = s.toIntOrNull() ?: 7
                            70 -> flags = s.toIntOrNull() ?: 0
                        }
                        i++
                    }
                    val col = aciToColor(aci)
                    when (ent) {
                        "LINE" -> addLineSegment(x1, y1, z1, x2, y2, z2, col)
                        "3DFACE", "SOLID", "TRACE" -> {
                            addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, col)
                            if (hasV4) addTriangle(x1, y1, z1, x3, y3, z3, x4, y4, z4, col)
                        }
                        "LWPOLYLINE", "SPLINE" -> {
                            val cnt = minOf(lwX.size, lwY.size)
                            for (k in 0 until cnt - 1) {
                                addLineSegment(lwX[k], lwY[k], z1, lwX[k + 1], lwY[k + 1], z1, col)
                            }
                            if ((flags and 1) != 0 && cnt >= 3) {
                                addLineSegment(lwX[cnt - 1], lwY[cnt - 1], z1, lwX[0], lwY[0], z1, col)
                            }
                        }
                        "CIRCLE", "ARC" -> if (radius > 0f) {
                            val isFull = ent == "CIRCLE"
                            var sweep = if (isFull) 360f else (endDeg - startDeg) % 360f
                            if (sweep <= 0f) sweep += 360f
                            val segs = 28
                            val step = sweep / segs
                            for (sIdx in 0 until segs) {
                                val a1 = Math.toRadians(((if (isFull) 0f else startDeg) + sIdx * step).toDouble()).toFloat()
                                val a2 = Math.toRadians(((if (isFull) 0f else startDeg) + (sIdx + 1) * step).toDouble()).toFloat()
                                addLineSegment(x1 + radius * cos(a1), y1 + radius * sin(a1), z1, x1 + radius * cos(a2), y1 + radius * sin(a2), z1, col)
                            }
                        }
                    }
                    continue
                }
                i++
            }
        }
    }

    private fun parseBinaryStlHeaderColor(hdr: ByteArray): Int {
        if (hdr.size < 80) return 0
        val ascii = String(hdr, 0, 80, StandardCharsets.ISO_8859_1)
        val idx = ascii.indexOf("COLOR=", ignoreCase = true)
        if (idx >= 0 && idx + 10 <= 80) {
            val r = hdr[idx + 6].toInt() and 0xFF
            val g = hdr[idx + 7].toInt() and 0xFF
            val b = hdr[idx + 8].toInt() and 0xFF
            val a = (hdr[idx + 9].toInt() and 0xFF).let { if (it == 0) 255 else it }
            if (r + g + b > 0) return Color.argb(a, r, g, b)
        }
        return 0
    }

    private fun parseBinaryStlPread(exactFileSize: Long) {
        val hdr = readSliceAt(0L, 84)
        if (hdr.size < 84) return
        val headerDefaultCol = parseBinaryStlHeaderColor(hdr)
        val headerCount = ByteBuffer.wrap(hdr, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val computedBySize = if (exactFileSize >= 134L) (exactFileSize - 84L) / 50L else 0L
        val totalTris = when {
            computedBySize > 0L && (headerCount <= 0L || headerCount > computedBySize + 1000L) -> computedBySize
            headerCount in 1L..50_000_000L -> if (computedBySize > 0L) minOf(headerCount, computedBySize) else headerCount
            else -> computedBySize
        }
        if (totalTris <= 0L) return

        val needsDecimation = totalTris > maxPolygonsLimit
        if (needsDecimation) isGeometryTruncated = true
        var decimationAcc = 0L

        val batchTris = 2048
        val directBuf = ByteBuffer.allocateDirect(batchTris * 50).order(ByteOrder.LITTLE_ENDIAN)
        var triIdx = 0L

        while (triIdx < totalTris && vertexCount + 9 <= maxVertexFloats) {
            val countToRead = minOf(batchTris.toLong(), totalTris - triIdx).toInt()
            directBuf.clear()
            directBuf.limit(countToRead * 50)
            val got = readableSource.readAt(84L + triIdx * 50L, directBuf)
            if (got < 50) break
            directBuf.flip()
            val actualTris = got / 50
            for (i in 0 until actualTris) {
                if (needsDecimation) {
                    decimationAcc += maxPolygonsLimit.toLong()
                    if (decimationAcc >= totalTris) {
                        decimationAcc -= totalTris
                    } else {
                        continue
                    }
                }
                if (vertexCount + 9 > maxVertexFloats) break
                val rowBase = i * 50
                val base = rowBase + 12
                val attr = directBuf.getShort(rowBase + 48).toInt() and 0xFFFF
                val col = if ((attr and 0x8000) != 0) {
                    val r = ((attr and 0x1F) * 255) / 31
                    val g = (((attr ushr 5) and 0x1F) * 255) / 31
                    val b = (((attr ushr 10) and 0x1F) * 255) / 31
                    Color.rgb(r, g, b)
                } else headerDefaultCol
                addTriangle(
                    directBuf.getFloat(base), directBuf.getFloat(base + 4), directBuf.getFloat(base + 8),
                    directBuf.getFloat(base + 12), directBuf.getFloat(base + 16), directBuf.getFloat(base + 20),
                    directBuf.getFloat(base + 24), directBuf.getFloat(base + 28), directBuf.getFloat(base + 32),
                    col
                )
            }
            triIdx += actualTris
        }
    }

    private fun parseAsciiStlStream(stream: java.io.InputStream) {
        val verts = FloatArray(9)
        var vIdx = 0
        var linesScanned = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var raw: String?
            while (reader.readLine().also { raw = it } != null && !isGeometryTruncated) {
                linesScanned++
                if (linesScanned > 500 && vertexCount == 0 && vIdx == 0) break
                val p = raw!!.trim()
                if (p.startsWith("vertex", true)) {
                    val parts = p.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        verts[vIdx * 3] = parts[1].toFloatOrNull() ?: 0f
                        verts[vIdx * 3 + 1] = parts[2].toFloatOrNull() ?: 0f
                        verts[vIdx * 3 + 2] = parts[3].toFloatOrNull() ?: 0f
                        vIdx++
                        if (vIdx == 3) {
                            addTriangle(verts[0], verts[1], verts[2], verts[3], verts[4], verts[5], verts[6], verts[7], verts[8])
                            vIdx = 0
                        }
                    }
                } else if (p.startsWith("endloop", true) || p.startsWith("endfacet", true)) {
                    vIdx = 0
                }
            }
        }
    }

    @Volatile private var glesContext: Gles30OffscreenContext? = null
    @Volatile private var glesRenderer: Gles30MeshRenderer? = null

    override fun getLastError(): String? = lastErrorMsg
    override fun getPageText(pageIndex: Int): String = modelType

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0 || (pageIndex and 0xFF) == 1
        val langOrdinal = (pageIndex ushr LANG_ORDINAL_SHIFT) and 0xF

        val extLower = originalExtension.lowercase(Locale.ROOT)
        val rangeY = (maxY - minY).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeZ = (maxZ - minZ).let { if (it.isFinite() && it > 0f) it else 1f }
        val isYUp = extLower in setOf("obj", "gltf", "glb", "usdz", "usd", "usda", "usdc", "fbx", "dae", "stl") ||
                (rangeY > rangeZ && extLower !in setOf("blend", "dxf", "dwg", "3mf"))

        if (vertexCount > 0) {
            try {
                var glesCtx = glesContext
                if (glesCtx != null && (glesCtx.width != bitmap.width || glesCtx.height != bitmap.height)) {
                    glesCtx.destroy()
                    glesContext = null
                    glesCtx = null
                }
                if (glesCtx == null && bitmap.width > 0 && bitmap.height > 0) {
                    glesCtx = Gles30OffscreenContext(bitmap.width, bitmap.height)
                    if (glesCtx.isInitialized) {
                        glesContext = glesCtx
                        glesCtx.makeCurrent()
                        glesRenderer = Gles30MeshRenderer()
                    } else {
                        glesCtx.destroy()
                        glesCtx = null
                    }
                }

                val renderer = glesRenderer
                if (glesCtx != null && glesCtx.isInitialized && renderer != null && renderer.isCompiled) {
                    glesCtx.makeCurrent()
                    val zoom = (zoomPermille / 1000f).coerceAtLeast(0.1f)
                    renderer.renderMesh(
                        vertices = rawVertices,
                        polyColors = polyColors,
                        vertexCount = vertexCount,
                        width = bitmap.width,
                        height = bitmap.height,
                        yawDeg = tileX.toFloat(),
                        pitchDeg = tileY.toFloat(),
                        scale = zoom,
                        isYUp = isYUp,
                        isLightTheme = isLight,
                        minX = minX, maxX = maxX,
                        minY = minY, maxY = maxY,
                        minZ = minZ, maxZ = maxZ
                    )
                    glesCtx.readPixelsToBitmap(bitmap)

                    val hudCanvas = Canvas(bitmap)
                    val polyCount = vertexCount / 9
                    val polygonsWord = when (langOrdinal) {
                        1 -> "Полигоны"
                        2 -> "Polygone"
                        3 -> "Polígonos"
                        4 -> "Polygones"
                        5 -> "Wielokąty"
                        6 -> "Полігони"
                        7 -> "多边形"
                        else -> "Polygons"
                    }
                    paint.color = if (isLight) Color.rgb(0x1A, 0x1C, 0x24) else Color.WHITE
                    paint.textSize = 26f
                    hudCanvas.drawText("3D/CAD: $modelType | $polygonsWord: $polyCount", 30f, bitmap.height - 32f, paint)
                    return
                }
            } catch (_: Throwable) {}
        }

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) Color.rgb(0xF5, 0xF7, 0xFA) else Color.rgb(0x12, 0x12, 0x12))

        val prev = previewBitmap
        if (prev != null && !prev.isRecycled && vertexCount == 0) {
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val zoom = (zoomPermille / 1000f).coerceAtLeast(0.1f)
            val aspect = prev.width.toFloat() / prev.height.toFloat().coerceAtLeast(1f)
            val availW = (width - 80f) * zoom
            val availH = (height - 120f) * zoom
            val dstW: Float; val dstH: Float
            if (availW / availH.coerceAtLeast(1f) > aspect) {
                dstH = availH; dstW = dstH * aspect
            } else {
                dstW = availW; dstH = dstW / aspect
            }
            val left = (width - dstW) / 2f - (tileX - 25) * 4f
            val top = (height - dstH) / 2f - (tileY - 20) * 4f
            canvas.drawBitmap(prev, null, RectF(left, top, left + dstW, top + dstH), bitmapPaint)
        }
        if (vertexCount > 0) {
            val polyCount = vertexCount / 9
            val polygonsWord = when (langOrdinal) {
                1 -> "Полигоны"
                2 -> "Polygone"
                3 -> "Polígonos"
                4 -> "Polygones"
                5 -> "Wielokąty"
                6 -> "Полігони"
                7 -> "多边形"
                else -> "Polygons"
            }
            paint.color = if (isLight) Color.rgb(0x1A, 0x1C, 0x24) else Color.WHITE
            paint.textSize = 26f
            canvas.drawText("3D/CAD: $modelType | $polygonsWord: $polyCount", 30f, bitmap.height - 32f, paint)
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        glesRenderer?.destroy()
        glesRenderer = null
        glesContext?.destroy()
        glesContext = null
        readableSource.close()
        previewBitmap?.recycle()
        previewBitmap = null
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Clean-Room parser for Microsoft OneNote (`.one`) revision store files based on the open
 * `[MS-ONE]` and `[MS-ONESTORE]` specifications published under the Microsoft Open Specifications Promise (OSP).
 */
class CleanRoomOneNoteSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 48L * 1024L * 1024L).toInt()
            if (size < 64) {
                lastErrorMsg = "OneNote (.one) file is empty or truncated."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(bytes, 16))
                fullText = extractOneNoteStructuredContent(bytes, embeddedImages.size)
            }
        } catch (e: Exception) {
            lastErrorMsg = "OneNote read error: ${e.localizedMessage}"
        }
        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "OneNote container contains no readable text or images."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun extractOneNoteStructuredContent(bytes: ByteArray, imageCount: Int): String {
        val blocks = LinkedHashSet<String>()

        var i = 0
        while (i + 8 < bytes.size && blocks.size < 4096) {
            val runSb = StringBuilder()
            var j = i
            while (j + 1 < bytes.size && runSb.length < 16384) {
                val lo = bytes[j].toInt() and 0xFF
                val hi = bytes[j + 1].toInt() and 0xFF
                val ch = ((hi shl 8) or lo).toChar()
                val isPrintableUnicode = (hi == 0x00 && (lo in 0x20..0x7E || lo == 0x0A || lo == 0x0D || lo == 0x09)) ||
                        (ch in '\u0400'..'\u04FF') ||
                        (ch in '\u00A0'..'\u024F') ||
                        (ch in '\u2000'..'\u206F')
                if (isPrintableUnicode) {
                    runSb.append(ch)
                    j += 2
                } else {
                    break
                }
            }
            if (runSb.length >= 4) {
                val cleaned = runSb.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
                if (cleaned.length >= 4 && cleaned.any { it.isLetter() } &&
                    !cleaned.equals("OneNote", ignoreCase = true) &&
                    !cleaned.startsWith("Calibri", ignoreCase = true)
                ) {
                    blocks.add(cleaned)
                }
                i = j
            } else {
                i += 2
            }
        }

        if (blocks.size < 3) {
            val asciiRun = StringBuilder()
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v == 10 || v == 13 || v in 32..126) {
                    if (asciiRun.length < 16384) asciiRun.append(v.toChar())
                } else {
                    if (asciiRun.length >= 8 && blocks.size < 4096) {
                        val s = asciiRun.toString().trim()
                        if (s.any { it.isLetter() }) blocks.add(s)
                    }
                    asciiRun.setLength(0)
                }
            }
        }

        val sb = StringBuilder()
        blocks.forEachIndexed { idx, block ->
            if (idx == 0 && block.length <= 80 && !block.contains('\n')) {
                sb.append("<h1>").append(block).append("</h1>\n")
            } else {
                sb.append(block).append("\n")
            }
        }
        for (imgIdx in 0 until imageCount) {
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

/**
 * Clean-Room preview & project metadata extractor for `MAX` (Autodesk 3ds Max), `RVT` (Autodesk Revit),
 * and `INDD` (Adobe InDesign).
 */
class CleanRoomProprietaryPreviewSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat,
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
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "Project container file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                parsePreviewAndMetadata(bytes)
            }
        } catch (e: Exception) {
            lastErrorMsg = "Container preview read error: ${e.localizedMessage}"
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun parsePreviewAndMetadata(bytes: ByteArray) {
        val sb = StringBuilder()
        val label = when {
            format == DetectedFormat.RVT || extension == "rvt" -> "Autodesk Revit BIM Project (.rvt)"
            format == DetectedFormat.MAX || extension == "max" -> "Autodesk 3ds Max Scene (.max)"
            format == DetectedFormat.INDD || extension == "indd" -> "Adobe InDesign Document (.indd)"
            else -> "Project Container (.${extension.uppercase(Locale.ROOT)})"
        }
        sb.append("<h1>").append(label).append("</h1>\n")

        val cfb = Ole2CfbContainer(bytes)
        if (cfb.isValid) {
            val streamNames = cfb.listStreams().map { "${it.name} (${it.size} B)" }
            if (streamNames.isNotEmpty()) {
                sb.append("<h2>OLE2 Container Streams</h2>\n")
                streamNames.take(25).forEach { sb.append("• ").append(it).append('\n') }
            }

            cfb.readStream("BasicFileInfo")?.let { infoBytes ->
                val infoText = String(infoBytes, StandardCharsets.UTF_16LE)
                    .replace('\u0000', ' ')
                    .lines()
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .joinToString("\n")
                if (infoText.isNotBlank()) {
                    sb.append("<h2>Project Metadata (BasicFileInfo)</h2>\n").append(infoText).append("\n")
                }
            }

            cfb.readStream("RevitPreview4.0")?.let { prevBytes ->
                embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(prevBytes, 4))
            }

            if (embeddedImages.isEmpty()) {
                for (entry in cfb.listStreams()) {
                    if (embeddedImages.size >= 6) break
                    val sBytes = cfb.readStream(entry.name) ?: continue
                    embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(sBytes, 4))
                }
            }
        }

        val latinStr = String(bytes, 0, minOf(bytes.size, 8 * 1024 * 1024), StandardCharsets.ISO_8859_1)
        val xmpImgMatches = Regex("<xmpGImg:image>([\\s\\S]*?)</xmpGImg:image>", RegexOption.IGNORE_CASE).findAll(latinStr)
        for (m in xmpImgMatches) {
            if (embeddedImages.size >= 8) break
            val b64 = m.groupValues[1].replace("&#xA;", "\n").replace("\\s+".toRegex(), "").trim()
            if (b64.length in 64..(16 * 1024 * 1024)) {
                try {
                    val imgBytes = Base64.decode(b64, Base64.DEFAULT)
                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let {
                        embeddedImages.add(it)
                    }
                } catch (_: Throwable) {}
            }
        }

        if (embeddedImages.isEmpty()) {
            embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(bytes, 6))
        }

        for (imgIdx in embeddedImages.indices) {
            sb.append("[IMAGE:$imgIdx]\n")
        }

        val xmpTagRegex = Regex("<(dc:title|dc:creator|dc:description|xmp:CreatorTool|xmp:CreateDate|xmp:ModifyDate)[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        val xmpFields = ArrayList<String>()
        for (m in xmpTagRegex.findAll(latinStr)) {
            val key = m.groupValues[1]
            val valClean = m.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (valClean.isNotEmpty()) {
                xmpFields.add("$key\t$valClean")
            }
        }
        if (xmpFields.isNotEmpty()) {
            sb.append("<h2>XMP Document Properties</h2>\n")
            sb.append("Property\tValue\n")
            xmpFields.forEach { sb.append(it).append('\n') }
        }

        val printableSummary = CleanRoomDocSession.extractPrintableRunsFromBinary(
            bytes.copyOf(minOf(bytes.size, 512 * 1024))
        )
        if (printableSummary.isNotBlank()) {
            sb.append("<h2>Extracted Container Strings</h2>\n")
            sb.append(printableSummary.lines().take(120).joinToString("\n"))
        }

        fullText = sb.toString().trim()
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

internal class Ole2CfbContainer(private val data: ByteArray) {
    data class DirEntry(
        val name: String,
        val objectType: Int,
        val startSector: Int,
        val size: Long
    )

    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniStreamCutoffSize: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = mutableListOf<DirEntry>()
    private var rootMiniStream: ByteArray = ByteArray(0)

    val isValid: Boolean

    companion object {
        private const val ENDOFCHAIN = -2
        private const val FREESECT = -1
        private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    }

    init {
        if (data.size < 512 ||
            bb.getInt(0) != -0x1FEE3030 ||
            bb.getInt(4) != -0x1EE54E5F
        ) {
            sectorSize = 512
            miniSectorSize = 64
            miniStreamCutoffSize = 4096
            fat = IntArray(0)
            miniFat = IntArray(0)
            isValid = false
        } else {
            val sectorShift = (bb.getShort(30).toInt() and 0xFFFF).coerceIn(9, 12)
            val miniSectorShift = (bb.getShort(32).toInt() and 0xFFFF).coerceIn(6, 8)
            sectorSize = 1 shl sectorShift
            miniSectorSize = 1 shl miniSectorShift
            miniStreamCutoffSize = bb.getInt(56).coerceAtLeast(4096)

            val maxFileSectors = (data.size / sectorSize) + 2
            val numFatSectors = bb.getInt(44).coerceIn(0, minOf(65536, maxFileSectors))
            val firstDirSector = bb.getInt(48)
            val firstMiniFatSector = bb.getInt(60)
            val numMiniFatSectors = bb.getInt(64).coerceIn(0, minOf(65536, maxFileSectors))
            val firstDifatSector = bb.getInt(68)
            val numDifatSectors = bb.getInt(72).coerceIn(0, minOf(1024, maxFileSectors))

            val difatSectors = ArrayList<Int>(numFatSectors)
            for (i in 0 until minOf(109, numFatSectors)) {
                val sec = bb.getInt(76 + i * 4)
                if (sec >= 0) difatSectors.add(sec)
            }

            var currDifat = firstDifatSector
            var difatVisited = 0
            val intsPerSector = sectorSize / 4
            while (currDifat >= 0 && difatVisited < numDifatSectors && difatSectors.size < numFatSectors) {
                val offset = (currDifat + 1L) * sectorSize
                if (offset + sectorSize > data.size) break
                for (i in 0 until intsPerSector - 1) {
                    val sec = bb.getInt(offset.toInt() + i * 4)
                    if (sec >= 0 && difatSectors.size < numFatSectors) difatSectors.add(sec)
                }
                currDifat = bb.getInt(offset.toInt() + (intsPerSector - 1) * 4)
                difatVisited++
            }

            val fatList = IntArray(difatSectors.size * intsPerSector)
            var fatIdx = 0
            for (sec in difatSectors) {
                val offset = (sec + 1L) * sectorSize
                if (offset + sectorSize <= data.size && offset >= 0) {
                    for (i in 0 until intsPerSector) {
                        fatList[fatIdx++] = bb.getInt(offset.toInt() + i * 4)
                    }
                }
            }
            fat = fatList

            val miniFatBytes = readChain(firstMiniFatSector, numMiniFatSectors.toLong() * sectorSize)
            val mfBb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
            miniFat = IntArray(miniFatBytes.size / 4) { mfBb.int }

            val dirBytes = readChain(firstDirSector, 4 * 1024 * 1024L)
            val entryCount = dirBytes.size / 128
            val dirBb = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until entryCount) {
                val base = i * 128
                val nameLenBytes = (dirBb.getShort(base + 64).toInt() and 0xFFFF).coerceIn(0, 64)
                val objType = dirBytes[base + 66].toInt() and 0xFF
                if (objType == 0 || nameLenBytes <= 2) continue
                val name = String(dirBytes, base, nameLenBytes - 2, StandardCharsets.UTF_16LE)
                val startSec = dirBb.getInt(base + 116)
                val streamSize = if (sectorShift == 9) {
                    dirBb.getInt(base + 120).toLong() and 0xFFFFFFFFL
                } else {
                    dirBb.getLong(base + 120)
                }
                entries.add(DirEntry(name, objType, startSec, streamSize))
            }

            val rootEntry = entries.firstOrNull { it.objectType == 5 }
            if (rootEntry != null && rootEntry.startSector >= 0 && rootEntry.size > 0L) {
                rootMiniStream = readChain(rootEntry.startSector, rootEntry.size.coerceAtMost(MAX_STREAM_BYTES.toLong()))
            }

            isValid = entries.isNotEmpty()
        }
    }

    fun listStreams(): List<DirEntry> = entries

    fun readStream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val safeLen = entry.size.coerceIn(0L, MAX_STREAM_BYTES.toLong())
        if (safeLen == 0L) return ByteArray(0)
        return if (entry.objectType != 5 && safeLen < miniStreamCutoffSize && rootMiniStream.isNotEmpty()) {
            readMiniChain(entry.startSector, safeLen)
        } else {
            readChain(entry.startSector, safeLen)
        }
    }

    private fun readChain(startSector: Int, expectedSize: Long): ByteArray {
        if (startSector < 0 || expectedSize <= 0L) return ByteArray(0)
        val maxBytes = minOf(expectedSize, MAX_STREAM_BYTES.toLong(), data.size.toLong()).toInt()
        val bos = ByteArrayOutputStream(minOf(maxBytes, 65536))
        var curr = startSector
        var visited = 0
        val maxSectors = minOf((maxBytes + sectorSize - 1) / sectorSize + 4, (data.size / sectorSize) + 2)
        while (curr >= 0 && curr != ENDOFCHAIN && curr != FREESECT && visited < maxSectors && bos.size() < maxBytes) {
            val offset = (curr + 1L) * sectorSize
            if (offset < 0 || offset + sectorSize > data.size) break
            val toCopy = minOf(sectorSize, maxBytes - bos.size())
            bos.write(data, offset.toInt(), toCopy)
            curr = if (curr < fat.size) fat[curr] else ENDOFCHAIN
            visited++
        }
        return bos.toByteArray()
    }

    private fun readMiniChain(startMiniSector: Int, expectedSize: Long): ByteArray {
        if (startMiniSector < 0 || expectedSize <= 0L || rootMiniStream.isEmpty()) return ByteArray(0)
        val maxBytes = minOf(expectedSize, MAX_STREAM_BYTES.toLong(), rootMiniStream.size.toLong()).toInt()
        val bos = ByteArrayOutputStream(minOf(maxBytes, 65536))
        var curr = startMiniSector
        var visited = 0
        val maxSectors = minOf((maxBytes + miniSectorSize - 1) / miniSectorSize + 4, (rootMiniStream.size / miniSectorSize) + 2)
        while (curr >= 0 && curr != ENDOFCHAIN && curr != FREESECT && visited < maxSectors && bos.size() < maxBytes) {
            val offset = curr.toLong() * miniSectorSize
            if (offset < 0 || offset + miniSectorSize > rootMiniStream.size) break
            val toCopy = minOf(miniSectorSize, maxBytes - bos.size())
            bos.write(rootMiniStream, offset.toInt(), toCopy)
            curr = if (curr < miniFat.size) miniFat[curr] else ENDOFCHAIN
            visited++
        }
        return bos.toByteArray()
    }
}

class CleanRoomDocSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 32 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "DOC file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }

                if (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII) == "{\\rtf") {
                    fullText = CleanRoomRtfSession.parseRtfBytes(bytes, embeddedImages)
                } else if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                    val ebookSession = EbookTextSession(dup, DetectedFormat.DOCX, "docx")
                    try {
                        fullText = ebookSession.getPageText(-1)
                        lastErrorMsg = ebookSession.getLastError()
                        for (i in 0 until ebookSession.getEmbeddedImagesCount()) {
                            ebookSession.getEmbeddedImage(i)?.let { bmp ->
                                bmp.config?.let { cfg -> embeddedImages.add(bmp.copy(cfg, false)) }
                            }
                        }
                    } finally {
                        ebookSession.close()
                    }
                } else {
                    val cfb = Ole2CfbContainer(bytes)
                    if (!cfb.isValid) {
                        val fallbackText = tryExtractNonOleDocText(bytes)
                        if (fallbackText.isNotBlank()) {
                            fullText = fallbackText
                        } else {
                            lastErrorMsg = "File is not a valid MS-DOC container (OLE2 CFB)."
                        }
                    } else {
                        if (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null) {
                            lastErrorMsg = "PASSWORD_REQUIRED: Document is encrypted or password protected."
                        } else {
                            cfb.readStream("Data")?.let { dataStream ->
                                embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(dataStream, 16))
                                if (embeddedImages.isEmpty()) {
                                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(dataStream)?.let { embeddedImages.add(it) }
                                }
                            }
                            val wordStream = cfb.readStream("WordDocument")
                            if (wordStream == null || wordStream.size < 68) {
                                lastErrorMsg = "Missing WordDocument stream in DOC container."
                            } else {
                                if (embeddedImages.isEmpty()) {
                                    embeddedImages.addAll(EmbeddedImageScanner.scanBinaryForImages(wordStream, 16))
                                }
                                var parsedBody = parseWordDocumentStream(cfb, wordStream)
                                if (embeddedImages.isNotEmpty() && !parsedBody.contains("[IMAGE:")) {
                                    val imgTags = (0 until embeddedImages.size).joinToString("\n") { "[IMAGE:$it]" }
                                    parsedBody = if (parsedBody.isNotEmpty()) "$parsedBody\n$imgTags" else imgTags
                                }
                                fullText = parsedBody
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "DOC read error: ${e.localizedMessage}"
        }

        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "DOC document contains no readable text or is encrypted."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    private fun tryExtractNonOleDocText(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            EbookTextSession.extractXmlBytesWithCharset(
                bytes = bytes,
                out = sb,
                onBinaryImageDecoded = { imgBytes ->
                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes)?.let { bmp ->
                        val idx = embeddedImages.size
                        embeddedImages.add(bmp)
                        sb.append("\n[IMAGE:$idx]\n")
                    }
                }
            )
            if (sb.isNotBlank()) return sb.toString().trim()
        } catch (_: Throwable) {}

        return try {
            val (cs, skip) = PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192))
            String(bytes, skip, (bytes.size - skip).coerceAtLeast(0), cs).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseWordDocumentStream(cfb: Ole2CfbContainer, wordDoc: ByteArray): String {
        val bb = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getShort(10).toInt() and 0xFFFF
        val isEncrypted = (flags and 0x0100) != 0
        if (isEncrypted) {
            lastErrorMsg = "PASSWORD_REQUIRED: MS Word document (.doc) is password protected."
            return ""
        }

        // Sum main text + footnotes + headers/footers + textboxes from FibRgLw97 (0x004C..0x006C)
        val ccpText = if (wordDoc.size >= 0x0050) bb.getInt(0x004C).coerceAtLeast(0) else 0
        val ccpFtn = if (wordDoc.size >= 0x0054) bb.getInt(0x0050).coerceAtLeast(0) else 0
        val ccpHdd = if (wordDoc.size >= 0x0058) bb.getInt(0x0054).coerceAtLeast(0) else 0
        val ccpAtn = if (wordDoc.size >= 0x0060) bb.getInt(0x005C).coerceAtLeast(0) else 0
        val ccpEdn = if (wordDoc.size >= 0x0064) bb.getInt(0x0060).coerceAtLeast(0) else 0
        val ccpTxbx = if (wordDoc.size >= 0x0068) bb.getInt(0x0064).coerceAtLeast(0) else 0
        val totalCcpLimit = (ccpText.toLong() + ccpFtn + ccpHdd + ccpAtn + ccpEdn + ccpTxbx)
            .coerceAtMost(8_000_000L).toInt().let { if (it > 0) it else -1 }

        val use1Table = (flags and 0x0200) != 0
        val tableStreamName = if (use1Table) "1Table" else "0Table"
        val tableStream = cfb.readStream(tableStreamName) ?: cfb.readStream(if (use1Table) "0Table" else "1Table")

        if (tableStream != null) {
            if (wordDoc.size >= 0x01AA) {
                val fcClx = bb.getInt(0x01A2)
                val lcbClx = bb.getInt(0x01A6)
                if (fcClx >= 0 && lcbClx > 0 && fcClx.toLong() + lcbClx.toLong() <= tableStream.size.toLong()) {
                    val clxText = parseClxPieceTable(wordDoc, tableStream, fcClx, lcbClx, totalCcpLimit)
                    if (clxText.isNotBlank()) return normalizeWordControlChars(clxText, embeddedImages.size)
                }
            }

            if (wordDoc.size >= 68) {
                val csw = bb.getShort(32).toInt() and 0xFFFF
                val fibRgLwStart = 34 + csw * 2
                if (fibRgLwStart + 2 < wordDoc.size) {
                    val cslw = bb.getShort(fibRgLwStart).toInt() and 0xFFFF
                    val fibRgFcLcbStart = fibRgLwStart + 2 + cslw * 4 + 2
                    val clxEntryOffset = fibRgFcLcbStart + 33 * 8
                    if (clxEntryOffset + 8 <= wordDoc.size) {
                        val dynFcClx = bb.getInt(clxEntryOffset)
                        val dynLcbClx = bb.getInt(clxEntryOffset + 4)
                        if (dynFcClx >= 0 && dynLcbClx > 0 && dynFcClx.toLong() + dynLcbClx.toLong() <= tableStream.size.toLong()) {
                            val clxText = parseClxPieceTable(wordDoc, tableStream, dynFcClx, dynLcbClx, totalCcpLimit)
                            if (clxText.isNotBlank()) return normalizeWordControlChars(clxText, embeddedImages.size)
                        }
                    }
                }
            }
        }

        val fcMin = bb.getInt(24).coerceIn(0, wordDoc.size)
        val fcMac = bb.getInt(28).coerceIn(fcMin, wordDoc.size)
        val isExtChar = (flags and 0x0004) != 0
        if (fcMac > fcMin) {
            val sliceLen = fcMac - fcMin
            val raw = if (isExtChar && sliceLen >= 2) {
                String(wordDoc, fcMin, sliceLen - (sliceLen % 2), StandardCharsets.UTF_16LE)
            } else {
                String(wordDoc, fcMin, sliceLen, PlainTextSession.CP1251)
            }
            val cleaned = normalizeWordControlChars(raw, embeddedImages.size)
            if (cleaned.isNotBlank()) return cleaned
        }

        return extractPrintableRunsFromBinary(wordDoc)
    }

    private fun parseClxPieceTable(
        wordDoc: ByteArray,
        tableStream: ByteArray,
        fcClx: Int,
        lcbClx: Int,
        ccpTextLimit: Int
    ): String {
        val tb = ByteBuffer.wrap(tableStream).order(ByteOrder.LITTLE_ENDIAN)
        var pos = fcClx
        val end = (fcClx.toLong() + lcbClx.toLong()).coerceAtMost(tableStream.size.toLong()).toInt()
        while (pos < end) {
            val clxt = tableStream[pos].toInt() and 0xFF
            pos++
            if (clxt == 1) {
                if (pos + 2 > end) return ""
                val cbGrpprl = tb.getShort(pos).toInt() and 0xFFFF
                pos += 2 + cbGrpprl
            } else if (clxt == 2) {
                if (pos + 4 > end) return ""
                val lcbPlcfPcd = tb.getInt(pos)
                pos += 4
                if (lcbPlcfPcd <= 4 || pos.toLong() + lcbPlcfPcd.toLong() > end.toLong()) return ""
                val pieceCount = (lcbPlcfPcd - 4) / 12
                if (pieceCount <= 0 || pieceCount > 65536) return ""

                val cpBase = pos
                val pcdBase = pos + (pieceCount + 1) * 4
                val out = StringBuilder()

                for (i in 0 until pieceCount) {
                    if (out.length >= 8_000_000) break
                    val cpStart = tb.getInt(cpBase + i * 4)
                    val cpEnd = tb.getInt(cpBase + (i + 1) * 4)
                    if (ccpTextLimit > 0 && cpStart >= ccpTextLimit) break
                    val effectiveCpEnd = if (ccpTextLimit > 0) minOf(cpEnd, ccpTextLimit) else cpEnd
                    val charCount = effectiveCpEnd - cpStart
                    if (charCount <= 0 || charCount > 4_000_000) continue

                    val fcCompressed = tb.getInt(pcdBase + i * 8 + 2)
                    val isAnsiCompressed = (fcCompressed and 0x40000000) != 0
                    val fc = fcCompressed and 0x3FFFFFFF

                    if (isAnsiCompressed) {
                        val byteOffset = fc / 2
                        if (byteOffset in 0 until wordDoc.size) {
                            val safeByteLen = minOf(charCount, wordDoc.size - byteOffset)
                            if (safeByteLen > 0) {
                                out.append(String(wordDoc, byteOffset, safeByteLen, PlainTextSession.CP1251))
                            }
                        }
                    } else {
                        val byteOffset = fc
                        if (byteOffset in 0 until wordDoc.size) {
                            val safeByteLen = minOf(charCount * 2, wordDoc.size - byteOffset) and -2
                            if (safeByteLen > 0) {
                                out.append(String(wordDoc, byteOffset, safeByteLen, StandardCharsets.UTF_16LE))
                            }
                        }
                    }
                }
                return out.toString()
            } else {
                break
            }
        }
        return ""
    }

    companion object {
        fun normalizeWordControlChars(raw: String, availableImages: Int = 0): String {
            val sb = StringBuilder(raw.length)
            var fieldDepth = 0
            var fieldInstructionChars = 0
            var inFieldInstruction = false
            var nextInlineImgIdx = 0
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                when (ch.code) {
                    0x13 -> {
                        fieldDepth++
                        inFieldInstruction = true
                        fieldInstructionChars = 0
                    }
                    0x14 -> {
                        inFieldInstruction = false
                    }
                    0x15 -> {
                        fieldDepth = (fieldDepth - 1).coerceAtLeast(0)
                        inFieldInstruction = false
                    }
                    0x01, 0x08 -> {
                        if (!inFieldInstruction && nextInlineImgIdx < availableImages) {
                            sb.append("\n[IMAGE:${nextInlineImgIdx++}]\n")
                        }
                    }
                    0x07 -> {
                        if (!inFieldInstruction) {
                            if (i + 1 < raw.length && raw[i + 1].code == 0x07) {
                                sb.append('\n')
                                i++
                            } else if (i + 2 < raw.length && raw[i + 1].code in setOf(0x0D, 0x0A) && raw[i + 2].code == 0x07) {
                                sb.append('\n')
                                i += 2
                            } else {
                                sb.append('\t')
                            }
                        }
                    }
                    0x0B, 0x0C, 0x0D, 0x0A -> {
                        inFieldInstruction = false
                        sb.append('\n')
                    }
                    else -> {
                        if (inFieldInstruction) {
                            fieldInstructionChars++
                            if (fieldInstructionChars > 512) {
                                inFieldInstruction = false
                            }
                        } else if (ch == '\t' || ch.code >= 32) {
                            sb.append(ch)
                        }
                    }
                }
                i++
            }
            return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }

        fun extractPrintableRunsFromBinary(data: ByteArray): String {
            val sb = StringBuilder()
            val run = StringBuilder()
            for (b in data) {
                val v = b.toInt() and 0xFF
                if (v == 10 || v == 13 || v in 32..126) {
                    run.append(v.toChar())
                } else {
                    if (run.length >= 6) {
                        sb.append(run).append('\n')
                    }
                    run.clear()
                }
            }
            if (run.length >= 6) sb.append(run)
            return sb.toString().trim()
        }

        fun paginateText(full: String, targetPages: MutableList<String>) {
            EbookTextSession.paginateRichText(full, 0, targetPages)
        }
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

class CleanRoomBiff8XlsSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var activeSheetIdx = 0

    data class MergedCellRegion(
        val firstRow: Int,
        val lastRow: Int,
        val firstCol: Int,
        val lastCol: Int
    )

    data class BiffCellStyle(
        val fillColor: Int? = null,
        val textColor: Int? = null,
        val isBold: Boolean = false,
        val isItalic: Boolean = false
    )

    data class SheetGrid(
        val name: String,
        val cells: Map<Long, String>,
        val cellStyles: Map<Long, BiffCellStyle>,
        val colWidthsPx: FloatArray?,
        val maxRow: Int,
        val maxCol: Int,
        val mergedRegions: List<MergedCellRegion> = emptyList()
    )

    private val sheets = mutableListOf<SheetGrid>()

    companion object {
        private const val MAX_BIFF_ROWS = 20_000
        private const val MAX_BIFF_COLS = 512
        private fun packCellCoord(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)

        private val DEFAULT_BIFF8_PALETTE = mapOf(
            0x08 to Color.rgb(0, 0, 0),
            0x09 to Color.rgb(255, 255, 255),
            0x0A to Color.rgb(255, 0, 0),
            0x0B to Color.rgb(0, 255, 0),
            0x0C to Color.rgb(0, 0, 255),
            0x0D to Color.rgb(255, 255, 0),
            0x0E to Color.rgb(255, 0, 255),
            0x0F to Color.rgb(0, 255, 255),
            0x10 to Color.rgb(128, 0, 0),
            0x11 to Color.rgb(0, 128, 0),
            0x12 to Color.rgb(0, 0, 128),
            0x13 to Color.rgb(128, 128, 0),
            0x14 to Color.rgb(128, 0, 128),
            0x15 to Color.rgb(0, 128, 128),
            0x16 to Color.rgb(192, 192, 192),
            0x17 to Color.rgb(128, 128, 128),
            0x18 to Color.rgb(153, 153, 255),
            0x19 to Color.rgb(153, 51, 102),
            0x1A to Color.rgb(255, 255, 204),
            0x1B to Color.rgb(204, 255, 255),
            0x1C to Color.rgb(102, 0, 102),
            0x1D to Color.rgb(255, 128, 128),
            0x1E to Color.rgb(0, 102, 204),
            0x1F to Color.rgb(204, 204, 255),
            0x28 to Color.rgb(0, 204, 255),
            0x29 to Color.rgb(204, 255, 255),
            0x2A to Color.rgb(204, 255, 204),
            0x2B to Color.rgb(255, 255, 153),
            0x2C to Color.rgb(153, 204, 255),
            0x2D to Color.rgb(255, 153, 204),
            0x2E to Color.rgb(204, 153, 255),
            0x2F to Color.rgb(255, 204, 153),
            0x30 to Color.rgb(51, 102, 255),
            0x31 to Color.rgb(51, 204, 204),
            0x32 to Color.rgb(153, 204, 0),
            0x33 to Color.rgb(255, 204, 0),
            0x34 to Color.rgb(255, 153, 0),
            0x35 to Color.rgb(255, 102, 0),
            0x36 to Color.rgb(102, 102, 153),
            0x37 to Color.rgb(150, 150, 150)
        )
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 32 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "XLS file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    if (!tryParseZipSpreadsheetFallback(bytes)) {
                        lastErrorMsg = "No data sheets found in spreadsheet container."
                    }
                } else {
                    val cfb = Ole2CfbContainer(bytes)
                    if (!cfb.isValid) {
                        if (!tryParseTextOrXmlSpreadsheetFallback(bytes)) {
                            lastErrorMsg = "File is not a valid MS-XLS container (OLE2 CFB)."
                        }
                    } else {
                        if (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null) {
                            lastErrorMsg = "PASSWORD_REQUIRED: Microsoft Excel spreadsheet is encrypted."
                        } else {
                            val wbStream = cfb.readStream("Workbook") ?: cfb.readStream("Book")
                            if (wbStream == null || wbStream.size < 16) {
                                lastErrorMsg = "Missing Workbook/Book stream in XLS container."
                            } else {
                                parseBiff8Workbook(wbStream)
                            }
                        }
                    }
                }
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "XLS read error: ${e.localizedMessage}"
        }
    }

    private fun readBiff12RecordHeader(bytes: ByteArray, startPos: Int): Triple<Int, Int, Int>? {
        var p = startPos
        if (p >= bytes.size) return null
        var recId = 0
        var shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recId = recId or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        var recLen = 0
        shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recLen = recLen or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (recLen < 0 || p + recLen > bytes.size) return null
        return Triple(recId, recLen, p)
    }

    private fun tryParseZipSpreadsheetFallback(zipBytes: ByteArray): Boolean {
        val entries = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val compContainerLen = zipBytes.size.coerceAtLeast(1)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                if (!entry.isDirectory && (
                            entry.name.endsWith(".xml", true) ||
                                    entry.name.endsWith(".rels", true) ||
                                    entry.name.endsWith(".bin", true)
                            )
                ) {
                    val bos = ByteArrayOutputStream()
                    var r: Int
                    while (zis.read(buf).also { r = it } > 0) {
                        total += r
                        if (total > 16L * 1024L * 1024L && (total / compContainerLen) > 150L) {
                            throw SecurityException("Превышен безопасный лимит распаковки (защита от Zip-бомб).")
                        }
                        if (total > 64L * 1024L * 1024L) break
                        bos.write(buf, 0, r)
                    }
                    entries[entry.name.replace('\\', '/').trimStart('/')] = bos.toByteArray()
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }

        // Check if this is an XLSB binary workbook
        if (entries.containsKey("xl/workbook.bin") ||
            entries.keys.any { it.lowercase(Locale.ROOT).startsWith("xl/worksheets/") && it.lowercase(Locale.ROOT).endsWith(".bin") }
        ) {
            val sharedStrings = ArrayList<String>()
            entries["xl/sharedStrings.bin"]?.let { sstBytes ->
                val bb = ByteBuffer.wrap(sstBytes).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos < sstBytes.size && sharedStrings.size < 200_000) {
                    val hdr = readBiff12RecordHeader(sstBytes, pos) ?: break
                    val (recId, recLen, dataStart) = hdr
                    if (recId == 0x0013 && recLen >= 5) {
                        val charCount = bb.getInt(dataStart + 1)
                        if (charCount in 0..32768 && dataStart + 5 + charCount * 2 <= dataStart + recLen) {
                            sharedStrings.add(String(sstBytes, dataStart + 5, charCount * 2, StandardCharsets.UTF_16LE))
                        }
                    }
                    pos = dataStart + recLen
                }
            }
            val sheetBinKeys = entries.keys.filter {
                val l = it.lowercase(Locale.ROOT)
                l.startsWith("xl/worksheets/") && l.endsWith(".bin")
            }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

            for ((idx, key) in sheetBinKeys.withIndex()) {
                val bin = entries[key] ?: continue
                val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
                val map = HashMap<Long, String>()
                val merges = ArrayList<MergedCellRegion>()
                var maxR = -1
                var maxC = -1
                var curR = -1
                var pos = 0
                while (pos < bin.size && map.size < 500_000) {
                    val hdr = readBiff12RecordHeader(bin, pos) ?: break
                    val (recId, recLen, dataStart) = hdr
                    when (recId) {
                        0x0000 -> if (recLen >= 4) curR = bb.getInt(dataStart).coerceIn(0, MAX_BIFF_ROWS - 1)
                        0x0002 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val rk = bb.getInt(dataStart + 8)
                            val v = decodeRkValue(rk)
                            if (col in 0 until MAX_BIFF_COLS) {
                                map[packCellCoord(curR, col)] = formatNumber(v)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0005, 0x0008 -> if (recLen >= 16 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val d = bb.getDouble(dataStart + 8)
                            if (col in 0 until MAX_BIFF_COLS && d.isFinite()) {
                                map[packCellCoord(curR, col)] = formatNumber(d)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0006, 0x0009 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val cch = bb.getInt(dataStart + 8)
                            if (col in 0 until MAX_BIFF_COLS && cch in 0..32768 && dataStart + 12 + cch * 2 <= dataStart + recLen) {
                                map[packCellCoord(curR, col)] = String(bin, dataStart + 12, cch * 2, StandardCharsets.UTF_16LE)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0007 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val isst = bb.getInt(dataStart + 8)
                            val str = sharedStrings.getOrNull(isst) ?: ""
                            if (col in 0 until MAX_BIFF_COLS && str.isNotEmpty()) {
                                map[packCellCoord(curR, col)] = str
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x00B0 -> if (recLen >= 16 && merges.size < 4096) {
                            val r0 = bb.getInt(dataStart).coerceIn(0, MAX_BIFF_ROWS - 1)
                            val r1 = bb.getInt(dataStart + 4).coerceIn(r0, MAX_BIFF_ROWS - 1)
                            val c0 = bb.getInt(dataStart + 8).coerceIn(0, MAX_BIFF_COLS - 1)
                            val c1 = bb.getInt(dataStart + 12).coerceIn(c0, MAX_BIFF_COLS - 1)
                            if (r1 > r0 || c1 > c0) merges.add(MergedCellRegion(r0, r1, c0, c1))
                        }
                    }
                    pos = dataStart + recLen
                }
                if (maxR >= 0 && maxC >= 0) {
                    sheets.add(SheetGrid("Sheet ${idx + 1}", map, emptyMap(), null, maxR, maxC, merges))
                }
            }
            if (sheets.isNotEmpty()) return true
        }

        val sharedStrings = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { ssBytes ->
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(ssBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inSi = false
            var inT = false
            val sb = StringBuilder()
            while (ev != XmlPullParser.END_DOCUMENT && sharedStrings.size < 250_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (local == "si") { inSi = true; sb.setLength(0) }
                        else if (local == "t" && inSi) inT = true
                    }
                    XmlPullParser.TEXT -> if (inSi && inT && sb.length < 32768) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        if (local == "t") inT = false
                        else if (local == "si" && inSi) { sharedStrings.add(sb.toString()); inSi = false }
                    }
                }
                ev = parser.next()
            }
        }

        val worksheetKeys = entries.keys.filter {
            val l = it.lowercase(Locale.ROOT)
            (l.startsWith("xl/worksheets/sheet") && l.endsWith(".xml")) || l == "content.xml"
        }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

        for ((idx, key) in worksheetKeys.withIndex()) {
            val xml = entries[key] ?: continue
            val map = HashMap<Long, String>()
            val colWidths = HashMap<Int, Float>()
            val merges = ArrayList<MergedCellRegion>()
            var maxR = -1
            var maxC = -1
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(xml), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var curR = -1
            var curC = -1
            var cellType = ""
            var inVal = false
            val valSb = StringBuilder()

            while (ev != XmlPullParser.END_DOCUMENT && map.size < 500_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "col" -> {
                                val minCol = (parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                                val maxCol = (parser.getAttributeValue(null, "max")?.toIntOrNull() ?: (minCol + 1)) - 1
                                val wChars = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                if (wChars != null && wChars > 0f && minCol in 0 until MAX_BIFF_COLS) {
                                    val px = SpreadsheetCoordinateUtils.excelCharWidthToPixels(wChars)
                                    for (c in minCol..minOf(maxCol, minCol + 128, MAX_BIFF_COLS - 1)) {
                                        colWidths[c] = px
                                    }
                                }
                            }
                            "row", "table-row" -> {
                                val rAttr = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                curR = if (rAttr != null && rAttr > 0) rAttr - 1 else (curR + 1)
                                curC = -1
                            }
                            "c", "table-cell" -> {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                                val ref = parser.getAttributeValue(null, "r") ?: ""
                                val refCol = SpreadsheetCoordinateUtils.columnIndexFromCellRef(ref)
                                curC = if (refCol >= 0) refCol else (curC + 1)
                                valSb.setLength(0)
                            }
                            "v", "t", "p" -> inVal = true
                            "mergecell" -> {
                                val ref = parser.getAttributeValue(null, "ref") ?: ""
                                val parts = ref.split(':')
                                if (parts.size == 2 && merges.size < 4096) {
                                    val c0 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[0])
                                    val c1 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[1])
                                    val r0 = (parts[0].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    val r1 = (parts[1].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    if (c0 in 0..c1 && r0 in 0..r1 && c1 < MAX_BIFF_COLS && r1 < MAX_BIFF_ROWS) {
                                        merges.add(MergedCellRegion(r0, r1, c0, c1))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inVal && valSb.length < 32768) valSb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "v", "t", "p" -> inVal = false
                            "c", "table-cell" -> {
                                val raw = valSb.toString().trim()
                                val resolved = if (cellType == "s") {
                                    sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                                } else raw
                                if (resolved.isNotEmpty() && curR in 0 until MAX_BIFF_ROWS && curC in 0 until MAX_BIFF_COLS) {
                                    map[packCellCoord(curR, curC)] = resolved
                                    if (curR > maxR) maxR = curR
                                    if (curC > maxC) maxC = curC
                                }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            if (maxR >= 0 && maxC >= 0) {
                val widthsArr = if (colWidths.isNotEmpty()) {
                    FloatArray(maxC + 1) { c -> colWidths[c] ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH }
                } else null
                sheets.add(SheetGrid("Sheet ${idx + 1}", map, emptyMap(), widthsArr, maxR, maxC, merges))
            }
        }
        return sheets.isNotEmpty()
    }

    private fun tryParseTextOrXmlSpreadsheetFallback(bytes: ByteArray): Boolean {
        return try {
            val (cs, skip) = PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192))
            val text = String(bytes, skip, (bytes.size - skip).coerceAtLeast(0), cs).trim()
            if (text.isEmpty()) return false

            val map = HashMap<Long, String>()
            var maxR = -1
            var maxC = -1
            val lines = text.lines().filter { it.isNotBlank() }.take(10_000)
            val delimiter = when {
                lines.firstOrNull()?.contains('\t') == true -> '\t'
                lines.firstOrNull()?.contains(';') == true -> ';'
                else -> ','
            }
            for ((rIdx, line) in lines.withIndex()) {
                val parts = line.split(delimiter)
                for ((cIdx, rawCell) in parts.withIndex()) {
                    if (cIdx >= MAX_BIFF_COLS) break
                    val clean = rawCell.trim().removeSurrounding("\"")
                    if (clean.isNotEmpty()) {
                        map[packCellCoord(rIdx, cIdx)] = clean
                        if (rIdx > maxR) maxR = rIdx
                        if (cIdx > maxC) maxC = cIdx
                    }
                }
            }
            if (maxR >= 0 && maxC >= 0) {
                sheets.add(SheetGrid("Sheet 1", map, emptyMap(), null, maxR, maxC))
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private data class BiffFontInfo(
        val colorIndex: Int,
        val isBold: Boolean,
        val isItalic: Boolean
    )

    private data class BiffXfInfo(
        val fontIndex: Int,
        val numFmtIndex: Int,
        val fillPattern: Int,
        val fillFgColorIndex: Int
    )

    private fun parseBiff8Workbook(wb: ByteArray) {
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val sheetNames = mutableListOf<String>()
        val sst = mutableListOf<String>()
        val formatMap = HashMap<Int, String>()
        val paletteMap = HashMap<Int, Int>(DEFAULT_BIFF8_PALETTE)
        val fonts = ArrayList<BiffFontInfo>()
        val xfRecords = ArrayList<BiffXfInfo>()
        var use1904DateSystem = false

        var pos = 0
        var bofDepth = 0
        var currentSheetIdx = -1
        var lastFormulaRow = -1
        var lastFormulaCol = -1
        var lastFormulaXf = -1
        val sheetMaps = mutableListOf<MutableMap<Long, String>>()
        val sheetStyleMaps = mutableListOf<MutableMap<Long, BiffCellStyle>>()
        val sheetColWidths = mutableListOf<MutableMap<Int, Float>>()
        val sheetMaxRows = mutableListOf<Int>()
        val sheetMaxCols = mutableListOf<Int>()
        val sheetMergedRegions = mutableListOf<MutableList<MergedCellRegion>>()

        fun ensureSheetLists(targetIdx: Int) {
            if (targetIdx > 64) return
            while (sheetMaps.size <= targetIdx) {
                sheetMaps.add(HashMap())
                sheetStyleMaps.add(HashMap())
                sheetColWidths.add(HashMap())
                sheetMaxRows.add(-1)
                sheetMaxCols.add(-1)
                sheetMergedRegions.add(ArrayList())
            }
        }

        fun resolveBiffStyle(xfIdx: Int): BiffCellStyle? {
            val xf = xfRecords.getOrNull(xfIdx) ?: return null
            val adjFontIdx = if (xf.fontIndex >= 4) xf.fontIndex - 1 else xf.fontIndex
            val font = fonts.getOrNull(adjFontIdx)
            val fillCol = if (xf.fillPattern > 0 && xf.fillFgColorIndex in 0x08..0x3F && xf.fillFgColorIndex != 0x09) {
                paletteMap[xf.fillFgColorIndex]
            } else null
            val txtCol = if (font != null && font.colorIndex in 0x08..0x3F && font.colorIndex != 0x08) {
                paletteMap[font.colorIndex]
            } else null
            val bold = font?.isBold == true
            val italic = font?.isItalic == true
            return if (fillCol != null || txtCol != null || bold || italic) {
                BiffCellStyle(fillCol, txtCol, bold, italic)
            } else null
        }

        fun putCell(row: Int, col: Int, value: String, xfIdx: Int = -1) {
            if (bofDepth > 1) return
            val trimmed = value.trim()
            if (row !in 0 until MAX_BIFF_ROWS || col !in 0 until MAX_BIFF_COLS) return
            val targetIdx = currentSheetIdx.coerceIn(0, 64)
            ensureSheetLists(targetIdx)
            val key = packCellCoord(row, col)
            if (trimmed.isNotEmpty() && sheetMaps[targetIdx].size < 500_000) {
                sheetMaps[targetIdx][key] = trimmed
                if (row > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = row
                if (col > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = col
            }
            if (xfIdx >= 0 && sheetStyleMaps[targetIdx].size < 250_000) {
                resolveBiffStyle(xfIdx)?.let { style ->
                    sheetStyleMaps[targetIdx][key] = style
                    if (row > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = row
                    if (col > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = col
                }
            }
        }

        fun formatNumericCell(d: Double, xfIdx: Int): String {
            if (!d.isFinite()) return ""
            val fmtIdx = xfRecords.getOrNull(xfIdx)?.numFmtIndex ?: 0
            val fmtStr = formatMap[fmtIdx]
            if (ExcelDateFormatter.isDateFormatCode(fmtIdx, fmtStr)) {
                return ExcelDateFormatter.formatExcelSerialDate(d, use1904DateSystem, fmtIdx, fmtStr)
            }
            if (ExcelDateFormatter.isPercentFormatCode(fmtIdx, fmtStr)) {
                val pct = d * 100.0
                val rounded = pct.roundToLong()
                return if (abs(pct - rounded) < 1e-4) "${rounded}%" else String.format(Locale.US, "%.2f%%", pct)
            }
            return formatNumber(d)
        }

        while (pos + 4 <= wb.size) {
            val recId = bb.getShort(pos).toInt() and 0xFFFF
            val recLen = bb.getShort(pos + 2).toInt() and 0xFFFF
            val dataStart = pos + 4
            if (dataStart + recLen > wb.size) break

            when (recId) {
                0x002F -> {
                    lastErrorMsg = "PASSWORD_REQUIRED: Microsoft Excel spreadsheet (.xls) is password protected."
                    return
                }
                0x0022 -> {
                    if (recLen >= 2) {
                        use1904DateSystem = (bb.getShort(dataStart).toInt() and 0xFFFF) == 1
                    }
                }
                0x0031 -> {
                    if (recLen >= 14 && fonts.size < 4096) {
                        val grbit = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val icv = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val bls = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                        fonts.add(
                            BiffFontInfo(
                                colorIndex = icv,
                                isBold = bls >= 0x02BC || (grbit and 0x01) != 0,
                                isItalic = (grbit and 0x02) != 0
                            )
                        )
                    }
                }
                0x0092 -> {
                    if (recLen >= 2) {
                        val count = (bb.getShort(dataStart).toInt() and 0xFFFF).coerceIn(0, 56)
                        for (i in 0 until count) {
                            val cPos = dataStart + 2 + i * 4
                            if (cPos + 4 > dataStart + recLen) break
                            val r = wb[cPos].toInt() and 0xFF
                            val g = wb[cPos + 1].toInt() and 0xFF
                            val b = wb[cPos + 2].toInt() and 0xFF
                            paletteMap[0x08 + i] = Color.rgb(r, g, b)
                        }
                    }
                }
                0x041E -> {
                    if (recLen >= 5) {
                        val fmtIndex = bb.getShort(dataStart).toInt() and 0xFFFF
                        val fmtCode = readBiff8UnicodeString(wb, dataStart + 2, dataStart + recLen)
                        if (fmtCode.isNotEmpty()) {
                            formatMap[fmtIndex] = fmtCode
                        }
                    }
                }
                0x00E0 -> {
                    if (recLen >= 4 && xfRecords.size < 16384) {
                        val fontIdx = bb.getShort(dataStart).toInt() and 0xFFFF
                        val fmtIdx = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        var fillPat = 0
                        var fillFgIcv = 0x40
                        if (recLen >= 20) {
                            val borderAndPattern = bb.getInt(dataStart + 14)
                            fillPat = (borderAndPattern ushr 26) and 0x3F
                            val icvCombined = bb.getShort(dataStart + 18).toInt() and 0xFFFF
                            fillFgIcv = icvCombined and 0x7F
                        }
                        xfRecords.add(BiffXfInfo(fontIdx, fmtIdx, fillPat, fillFgIcv))
                    }
                }
                0x007D -> {
                    if (recLen >= 6 && bofDepth == 1) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val colFirst = (bb.getShort(dataStart).toInt() and 0xFFFF).coerceIn(0, MAX_BIFF_COLS - 1)
                        val colLast = (bb.getShort(dataStart + 2).toInt() and 0xFFFF).coerceIn(colFirst, MAX_BIFF_COLS - 1)
                        val colUnits256 = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        if (colUnits256 > 0) {
                            val pxWidth = SpreadsheetCoordinateUtils.excelCharWidthToPixels(colUnits256 / 256f)
                            for (c in colFirst..minOf(colLast, colFirst + 128)) {
                                sheetColWidths[targetIdx][c] = pxWidth
                            }
                        }
                    }
                }
                0x0809 -> {
                    if (recLen >= 4) {
                        val substreamType = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        if (substreamType != 0x0005) {
                            if (bofDepth == 0 && currentSheetIdx < 64) {
                                currentSheetIdx++
                                ensureSheetLists(currentSheetIdx)
                            }
                            bofDepth++
                        }
                    }
                }
                0x000A -> {
                    if (bofDepth > 0) {
                        bofDepth--
                    }
                }
                0x0085 -> {
                    if (recLen >= 8 && sheetNames.size < 64) {
                        val nameLen = wb[dataStart + 6].toInt() and 0xFF
                        val flags = wb[dataStart + 7].toInt() and 0xFF
                        val isUtf16 = (flags and 0x01) != 0
                        val strStart = dataStart + 8
                        val sName = if (isUtf16 && strStart + nameLen * 2 <= dataStart + recLen) {
                            String(wb, strStart, nameLen * 2, StandardCharsets.UTF_16LE)
                        } else if (!isUtf16 && strStart + nameLen <= dataStart + recLen) {
                            String(wb, strStart, nameLen, PlainTextSession.CP1251)
                        } else "Sheet${sheetNames.size + 1}"
                        sheetNames.add(sName)
                    }
                }
                0x00FC -> {
                    parseBiff8Sst(wb, pos, sst)
                }
                0x0201 -> {
                    if (recLen >= 6) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        putCell(row, col, "", xfIdx)
                    }
                }
                0x00FD -> {
                    if (recLen >= 10) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val sstIdx = bb.getInt(dataStart + 6)
                        if (sstIdx in sst.indices) {
                            putCell(row, col, sst[sstIdx], xfIdx)
                        }
                    }
                }
                0x0204, 0x00D6 -> {
                    if (recLen >= 8) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val str = readBiff8UnicodeString(wb, dataStart + 6, dataStart + recLen)
                        putCell(row, col, str, xfIdx)
                    }
                }
                0x0203 -> {
                    if (recLen >= 14) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val d = Double.fromBits(bb.getLong(dataStart + 6))
                        putCell(row, col, formatNumericCell(d, xfIdx), xfIdx)
                    }
                }
                0x027E -> {
                    if (recLen >= 10) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val rkVal = decodeRkValue(bb.getInt(dataStart + 6))
                        putCell(row, col, formatNumericCell(rkVal, xfIdx), xfIdx)
                    }
                }
                0x00BD -> {
                    if (recLen >= 6) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val firstCol = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val lastCol = bb.getShort(dataStart + recLen - 2).toInt() and 0xFFFF
                        var offset = dataStart + 4
                        var c = firstCol
                        while (c <= lastCol && offset + 6 <= dataStart + recLen - 2) {
                            val xfIdx = bb.getShort(offset).toInt() and 0xFFFF
                            val rkVal = decodeRkValue(bb.getInt(offset + 2))
                            putCell(row, c, formatNumericCell(rkVal, xfIdx), xfIdx)
                            offset += 6
                            c++
                        }
                    }
                }
                0x0205 -> {
                    if (recLen >= 8) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val valByte = wb[dataStart + 6].toInt() and 0xFF
                        val isError = (wb[dataStart + 7].toInt() and 0xFF) == 1
                        putCell(row, col, if (isError) "#ERR($valByte)" else if (valByte != 0) "TRUE" else "FALSE", xfIdx)
                    }
                }
                0x0006 -> {
                    if (recLen >= 14) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        lastFormulaRow = row
                        lastFormulaCol = col
                        lastFormulaXf = xfIdx
                        val expr6 = wb[dataStart + 12].toInt() and 0xFF
                        val expr7 = wb[dataStart + 13].toInt() and 0xFF
                        if (expr6 == 0xFF && expr7 == 0xFF) {
                            val kind = wb[dataStart + 6].toInt() and 0xFF
                            if (kind == 1 && dataStart + 8 < wb.size) {
                                putCell(row, col, if (wb[dataStart + 8].toInt() != 0) "TRUE" else "FALSE", xfIdx)
                            }
                        } else {
                            val d = Double.fromBits(bb.getLong(dataStart + 6))
                            if (d.isFinite()) putCell(row, col, formatNumericCell(d, xfIdx), xfIdx)
                        }
                    }
                }
                0x0207 -> {
                    if (lastFormulaRow >= 0 && lastFormulaCol >= 0 && recLen >= 3) {
                        val formulaStr = readBiff8UnicodeString(wb, dataStart, dataStart + recLen)
                        putCell(lastFormulaRow, lastFormulaCol, formulaStr, lastFormulaXf)
                        lastFormulaRow = -1
                        lastFormulaCol = -1
                        lastFormulaXf = -1
                    }
                }
                0x00E5 -> {
                    if (recLen >= 2 && bofDepth == 1) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val count = bb.getShort(dataStart).toInt() and 0xFFFF
                        var mPos = dataStart + 2
                        for (m in 0 until count) {
                            if (mPos + 8 > dataStart + recLen || sheetMergedRegions[targetIdx].size >= 4096) break
                            val rFirst = bb.getShort(mPos).toInt() and 0xFFFF
                            val rLast = (bb.getShort(mPos + 2).toInt() and 0xFFFF).coerceAtMost(MAX_BIFF_ROWS - 1)
                            val cFirst = bb.getShort(mPos + 4).toInt() and 0xFFFF
                            val cLast = (bb.getShort(mPos + 6).toInt() and 0xFFFF).coerceAtMost(MAX_BIFF_COLS - 1)
                            if (rFirst in 0..rLast && cFirst in 0..cLast && (rLast > rFirst || cLast > cFirst)) {
                                sheetMergedRegions[targetIdx].add(MergedCellRegion(rFirst, rLast, cFirst, cLast))
                                if (rLast > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = rLast
                                if (cLast > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = cLast
                            }
                            mPos += 8
                        }
                    }
                }
            }
            pos = dataStart + recLen
        }

        val count = maxOf(sheetNames.size, sheetMaps.size)
        for (i in 0 until count) {
            val name = sheetNames.getOrElse(i) { "Sheet${i + 1}" }
            val map = sheetMaps.getOrElse(i) { HashMap() }
            val styles = sheetStyleMaps.getOrElse(i) { HashMap() }
            val mr = sheetMaxRows.getOrElse(i) { -1 }
            val mc = sheetMaxCols.getOrElse(i) { -1 }
            val merges = sheetMergedRegions.getOrElse(i) { emptyList() }
            val colWidthsMap = sheetColWidths.getOrNull(i)
            val colWidthsArr = if (mc >= 0 && !colWidthsMap.isNullOrEmpty()) {
                FloatArray((mc + 1).coerceAtMost(MAX_BIFF_COLS)) { c ->
                    colWidthsMap[c] ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
                }
            } else null
            if (mr >= 0 || mc >= 0 || count == 1 || i < sheetNames.size) {
                sheets.add(SheetGrid(name, map, styles, colWidthsArr, mr, mc, merges))
            }
        }
        if (sheets.isEmpty()) {
            lastErrorMsg = "No data sheets found in XLS file."
        }
    }

    private fun parseBiff8Sst(wb: ByteArray, sstRecordPos: Int, outSst: MutableList<String>) {
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val recLen = bb.getShort(sstRecordPos + 2).toInt() and 0xFFFF
        if (recLen < 8) return
        val uniqueStrings = bb.getInt(sstRecordPos + 8).coerceIn(0, minOf(250_000, wb.size / 2))

        var curPos = sstRecordPos + 12
        var curEnd = sstRecordPos + 4 + recLen

        fun advanceToContinue(): Boolean {
            if (curEnd + 4 <= wb.size) {
                val nextId = bb.getShort(curEnd).toInt() and 0xFFFF
                val nextLen = bb.getShort(curEnd + 2).toInt() and 0xFFFF
                if (nextId == 0x003C && curEnd + 4 + nextLen <= wb.size) {
                    curPos = curEnd + 4
                    curEnd = curPos + nextLen
                    return true
                }
            }
            return false
        }

        for (sIdx in 0 until uniqueStrings) {
            if (curPos + 3 > curEnd && !advanceToContinue()) break
            if (curPos + 3 > curEnd) break

            val charCount = bb.getShort(curPos).toInt() and 0xFFFF
            val flags = wb[curPos + 2].toInt() and 0xFF
            curPos += 3

            var isUtf16 = (flags and 0x01) != 0
            val hasExt = (flags and 0x04) != 0
            val hasRich = (flags and 0x08) != 0

            var richRuns = 0
            var extSize = 0
            if (hasRich && curPos + 2 <= curEnd) {
                richRuns = bb.getShort(curPos).toInt() and 0xFFFF
                curPos += 2
            }
            if (hasExt && curPos + 4 <= curEnd) {
                extSize = bb.getInt(curPos).coerceIn(0, wb.size)
                curPos += 4
            }

            val sb = StringBuilder(minOf(charCount, 4096))
            var charsRemaining = charCount
            while (charsRemaining > 0) {
                if (curPos >= curEnd) {
                    if (!advanceToContinue() || curPos >= curEnd) break
                    val contFlags = wb[curPos].toInt() and 0xFF
                    isUtf16 = (contFlags and 0x01) != 0
                    curPos++
                }
                val availBytes = curEnd - curPos
                if (isUtf16) {
                    val canReadChars = minOf(charsRemaining, availBytes / 2)
                    if (canReadChars <= 0) break
                    sb.append(String(wb, curPos, canReadChars * 2, StandardCharsets.UTF_16LE))
                    curPos += canReadChars * 2
                    charsRemaining -= canReadChars
                } else {
                    val canReadChars = minOf(charsRemaining, availBytes)
                    if (canReadChars <= 0) break
                    sb.append(String(wb, curPos, canReadChars, PlainTextSession.CP1251))
                    curPos += canReadChars
                    charsRemaining -= canReadChars
                }
            }
            outSst.add(sb.toString())

            var skipExtra = richRuns * 4 + extSize
            while (skipExtra > 0) {
                val avail = curEnd - curPos
                if (avail >= skipExtra) {
                    curPos += skipExtra
                    skipExtra = 0
                } else {
                    skipExtra -= avail.coerceAtLeast(0)
                    if (!advanceToContinue()) break
                }
            }
        }
    }

    private fun readBiff8UnicodeString(wb: ByteArray, offset: Int, limit: Int): String {
        if (offset + 2 > limit) return ""
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val charCount = bb.getShort(offset).toInt() and 0xFFFF
        if (offset + 3 > limit) {
            val bytesLen = minOf(charCount, limit - (offset + 2)).coerceAtLeast(0)
            return String(wb, offset + 2, bytesLen, PlainTextSession.CP1251)
        }
        val flags = wb[offset + 2].toInt() and 0xFF
        val isUtf16 = (flags and 0x01) != 0
        val hasExt = (flags and 0x04) != 0
        val hasRich = (flags and 0x08) != 0
        var start = offset + 3
        if (hasRich && start + 2 <= limit) start += 2
        if (hasExt && start + 4 <= limit) start += 4

        return if (isUtf16) {
            val bytesLen = minOf(charCount * 2, limit - start).coerceAtLeast(0)
            String(wb, start, bytesLen and -2, StandardCharsets.UTF_16LE)
        } else {
            val bytesLen = minOf(charCount, limit - start).coerceAtLeast(0)
            String(wb, start, bytesLen, PlainTextSession.CP1251)
        }
    }

    private fun decodeRkValue(rk: Int): Double {
        val isDiv100 = (rk and 0x01) != 0
        val isInt = (rk and 0x02) != 0
        val raw = if (isInt) {
            (rk shr 2).toDouble()
        } else {
            Double.fromBits((rk.toLong() and -4L) shl 32)
        }
        return if (isDiv100) raw / 100.0 else raw
    }

    private fun formatNumber(d: Double): String {
        val asLong = d.toLong()
        if (d == asLong.toDouble()) return asLong.toString()
        val rounded = Math.round(d * 1e10) / 1e10
        val rLong = rounded.toLong()
        return if (rounded == rLong.toDouble()) rLong.toString() else rounded.toString()
    }

    override val pageCount: Int = 1
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getSheetNames(): List<String> = sheets.map { it.name }

    @Synchronized
    override fun getActiveSheetIndex(): Int = activeSheetIdx

    @Synchronized
    override fun setActiveSheetIndex(index: Int) {
        if (index in sheets.indices) activeSheetIdx = index
    }

    @Synchronized
    override fun getColumnWidths(): FloatArray {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return FloatArray(0)
        val widths = sheet.colWidthsPx ?: return FloatArray(0)
        val colCount = (sheet.maxCol + 1).coerceAtLeast(widths.size)
        return if (widths.size == colCount) {
            widths.clone()
        } else {
            FloatArray(colCount) { c ->
                if (c < widths.size && widths[c] > 0f) widths[c] else SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
            }
        }
    }

    @Synchronized
    override fun getCellText(row: Int, col: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        return sheet.cells[packCellCoord(row, col)] ?: ""
    }

    @Synchronized
    override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        if (sheet.maxRow < 0 || sheet.maxCol < 0) return ""
        val rMin = minOf(startRow, endRow).coerceAtLeast(0)
        val rMax = minOf(maxOf(startRow, endRow), sheet.maxRow)
        val cMin = minOf(startCol, endCol).coerceAtLeast(0)
        val cMax = minOf(maxOf(startCol, endCol), sheet.maxCol)
        if (rMin > rMax || cMin > cMax) return ""

        val sb = StringBuilder()
        for (r in rMin..rMax) {
            for (c in cMin..cMax) {
                sb.append(sheet.cells[packCellCoord(r, c)] ?: "")
                if (c < cMax) sb.append('\t')
            }
            if (r < rMax) sb.append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        val targetIdx = if (cleanIdx in sheets.indices) cleanIdx else activeSheetIdx
        val sheet = sheets.getOrNull(targetIdx) ?: return ""
        if (sheet.maxRow < 0 || sheet.maxCol < 0) return ""
        val maxScanRow = minOf(sheet.maxRow, 10_000)
        val maxScanCol = minOf(sheet.maxCol, 256)
        val sb = StringBuilder()
        for (r in 0..maxScanRow) {
            for (c in 0..maxScanCol) {
                if (c > 0) sb.append('\t')
                sb.append(sheet.cells[packCellCoord(r, c)] ?: "")
            }
            if (r < maxScanRow) sb.append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        for (i in startPage.coerceAtLeast(0) until sheets.size) {
            if (sheets[i].cells.values.any { it.contains(query, ignoreCase = true) }) {
                return i.toLong()
            }
        }
        return -1L
    }

    @Synchronized
    override fun getDocumentWidth(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.maxCol < 0) return 0f
        val colCount = sheet.maxCol + 1
        val widths = sheet.colWidthsPx
        val contentW = if (widths != null && widths.isNotEmpty()) {
            var sum = 0f
            for (c in 0 until colCount) {
                val w = if (c < widths.size && widths[c] > 0f) widths[c] else SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
                sum += w.coerceIn(60f, 680f)
            }
            sum
        } else {
            colCount * SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
        }
        return SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH + contentW
    }

    @Synchronized
    override fun getDocumentHeight(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.maxRow < 0) return 0f
        return SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT + (sheet.maxRow + 1) * SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return
        if (sheet.maxRow < 0 || sheet.maxCol < 0) return

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val headerHeight = SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
        val headerWidth = SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom
        val dataColCount = sheet.maxCol + 1
        val dataRowCount = sheet.maxRow + 1
        val cellHeight = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT * zoom

        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
            colCount = dataColCount,
            customColWidthsPx = sheet.colWidthsPx,
            viewportWidth = bitmap.width.toFloat(),
            zoom = zoom
        )

        val totalTableW = headerWidth + colOffsets[dataColCount]
        val totalTableH = headerHeight + dataRowCount * cellHeight
        val maxEffX = (totalTableW - bitmap.width.toFloat()).coerceAtLeast(0f)
        val maxEffY = (totalTableH - bitmap.height.toFloat()).coerceAtLeast(0f)
        val effX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effY = tileY.toFloat().coerceIn(0f, maxEffY)

        val defaultCellBgColor = if (isLightTheme) LIGHT_SHEET_CELL_BG_COLOR else DARK_SHEET_CELL_BG_COLOR
        val defaultTextColor = if (isLightTheme) LIGHT_SHEET_TEXT_COLOR else DARK_SHEET_TEXT_COLOR

        val paintCellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = defaultCellBgColor
            style = Paint.Style.FILL
        }
        val paintCustomFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val paintText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = defaultTextColor
            textSize = 26f * zoom
        }
        val paintHeader = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_TEXT_COLOR else DARK_SHEET_HEADER_TEXT_COLOR
            textSize = 26f * zoom
            isFakeBoldText = true
        }
        val paintHeaderBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_BG_COLOR else DARK_SHEET_HEADER_BG_COLOR
            style = Paint.Style.FILL
        }
        val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_GRID_COLOR else DARK_SHEET_GRID_COLOR
            strokeWidth = 2f
        }

        val maxColIdx = sheet.maxCol
        val maxRowIdx = sheet.maxRow
        val startCol = SpreadsheetCoordinateUtils.findColumnAtContentX(colOffsets, effX)
        val endCol = SpreadsheetCoordinateUtils.findColumnAtContentX(colOffsets, effX + bitmap.width).coerceIn(startCol, maxColIdx)
        val startRow = (effY / cellHeight).toInt().coerceAtLeast(0)
        val endRow = (startRow + (bitmap.height / cellHeight).toInt() + 2).coerceAtMost(maxRowIdx)
        val subY = effY % cellHeight

        if (startRow > endRow || startCol > endCol) return

        val tableRightX = (headerWidth + colOffsets[endCol + 1] - effX).coerceIn(headerWidth, bitmap.width.toFloat())
        val tableBottomY = (headerHeight + (endRow - startRow + 1) * cellHeight - subY).coerceIn(headerHeight, bitmap.height.toFloat())

        canvas.drawRect(headerWidth, headerHeight, tableRightX, tableBottomY, paintCellBg)

        fun findMergeForCell(r: Int, c: Int): MergedCellRegion? {
            return sheet.mergedRegions.firstOrNull { r in it.firstRow..it.lastRow && c in it.firstCol..it.lastCol }
        }

        for (r in startRow..endRow) {
            val rowY = headerHeight + (r - startRow) * cellHeight - subY
            if (rowY + cellHeight >= headerHeight && rowY <= bitmap.height) {
                for (c in startCol..endCol) {
                    val colX = headerWidth + colOffsets[c] - effX
                    val colW = colOffsets[c + 1] - colOffsets[c]
                    if (colX + colW >= headerWidth && colX <= bitmap.width) {
                        val activeMerge = findMergeForCell(r, c)
                        if (activeMerge == null) {
                            val key = packCellCoord(r, c)
                            val cellStyle = sheet.cellStyles[key]
                            if (cellStyle?.fillColor != null) {
                                paintCustomFill.color = cellStyle.fillColor
                                canvas.drawRect(
                                    maxOf(headerWidth, colX),
                                    maxOf(headerHeight, rowY),
                                    colX + colW,
                                    rowY + cellHeight,
                                    paintCustomFill
                                )
                            }
                            val txt = sheet.cells[key] ?: ""
                            if (txt.isNotEmpty()) {
                                paintText.color = cellStyle?.textColor ?: defaultTextColor
                                paintText.isFakeBoldText = cellStyle?.isBold == true
                                paintText.textSkewX = if (cellStyle?.isItalic == true) -0.2f else 0f
                                val clipped = TextUtils.ellipsize(
                                    txt,
                                    paintText,
                                    (colW - 16f * zoom).coerceAtLeast(10f),
                                    TextUtils.TruncateAt.END
                                ).toString()
                                canvas.save()
                                canvas.clipRect(
                                    maxOf(headerWidth, colX + 2f),
                                    maxOf(headerHeight, rowY + 2f),
                                    colX + colW - 2f,
                                    rowY + cellHeight - 2f
                                )
                                canvas.drawText(clipped, colX + 10f * zoom, rowY + 33f * zoom, paintText)
                                canvas.restore()
                            }
                        }

                        val suppressRightLine = activeMerge != null && c < activeMerge.lastCol
                        if (!suppressRightLine) {
                            canvas.drawLine(colX + colW, maxOf(headerHeight, rowY), colX + colW, rowY + cellHeight, paintGrid)
                        }
                        val suppressBottomLine = activeMerge != null && r < activeMerge.lastRow
                        if (!suppressBottomLine) {
                            canvas.drawLine(maxOf(headerWidth, colX), rowY + cellHeight, colX + colW, rowY + cellHeight, paintGrid)
                        }
                    }
                }
            }
        }

        for (merge in sheet.mergedRegions) {
            if (merge.lastRow < startRow || merge.firstRow > endRow ||
                merge.lastCol < startCol || merge.firstCol > endCol
            ) continue
            val c0 = merge.firstCol.coerceIn(0, maxColIdx)
            val c1 = merge.lastCol.coerceIn(c0, maxColIdx)
            val mLeft = headerWidth + colOffsets[c0] - effX
            val mRight = headerWidth + colOffsets[c1 + 1] - effX
            val mTop = headerHeight + (merge.firstRow - startRow) * cellHeight - subY
            val mBot = headerHeight + (merge.lastRow - startRow + 1) * cellHeight - subY

            val key = packCellCoord(merge.firstRow, merge.firstCol)
            val style = sheet.cellStyles[key]
            paintCustomFill.color = style?.fillColor ?: defaultCellBgColor
            canvas.save()
            canvas.clipRect(headerWidth, headerHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRect(mLeft + 1f, mTop + 1f, mRight - 1f, mBot - 1f, paintCustomFill)
            canvas.drawRect(mLeft, mTop, mRight, mBot, paintGrid)

            val txt = sheet.cells[key] ?: ""
            if (txt.isNotEmpty()) {
                paintText.color = style?.textColor ?: defaultTextColor
                paintText.isFakeBoldText = style?.isBold == true
                paintText.textSkewX = if (style?.isItalic == true) -0.2f else 0f
                val availW = (mRight - mLeft - 16f * zoom).toInt().coerceAtLeast(20)
                val layout = StaticLayout.Builder.obtain(txt, 0, txt.length, paintText, availW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
                canvas.clipRect(mLeft + 2f, mTop + 2f, mRight - 2f, mBot - 2f)
                val textY = mTop + ((mBot - mTop - layout.height) / 2f).coerceAtLeast(6f * zoom)
                canvas.translate(mLeft + 8f * zoom, textY)
                layout.draw(canvas)
            }
            canvas.restore()
        }

        paintText.isFakeBoldText = false
        paintText.textSkewX = 0f

        canvas.drawRect(0f, 0f, tableRightX, headerHeight, paintHeaderBg)
        for (c in startCol..endCol) {
            val colX = headerWidth + colOffsets[c] - effX
            val colW = colOffsets[c + 1] - colOffsets[c]
            if (colX + colW >= headerWidth && colX <= bitmap.width) {
                val colLabel = buildColumnLabel(c)
                canvas.drawText(colLabel, colX + 14f * zoom, 36f * zoom, paintHeader)
                canvas.drawLine(colX + colW, 0f, colX + colW, headerHeight, paintGrid)
            }
        }
        canvas.drawLine(0f, headerHeight, tableRightX, headerHeight, paintGrid)

        for (r in startRow..endRow) {
            val rowY = headerHeight + (r - startRow) * cellHeight - subY
            if (rowY + cellHeight >= headerHeight && rowY <= bitmap.height) {
                val clampedTop = maxOf(headerHeight, rowY)
                canvas.drawRect(0f, clampedTop, headerWidth, rowY + cellHeight, paintHeaderBg)
                canvas.drawText("${r + 1}", 14f * zoom, rowY + 33f * zoom, paintHeader)
                canvas.drawLine(0f, rowY + cellHeight, headerWidth, rowY + cellHeight, paintGrid)
                canvas.drawLine(headerWidth, clampedTop, headerWidth, rowY + cellHeight, paintGrid)
            }
        }
        canvas.drawRect(0f, 0f, headerWidth, headerHeight, paintHeaderBg)
        canvas.drawLine(headerWidth, 0f, headerWidth, tableBottomY, paintGrid)
        canvas.drawLine(0f, headerHeight, headerWidth, headerHeight, paintGrid)
    }

    private fun buildColumnLabel(colIndex: Int): String {
        var n = colIndex
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A'.code + (n % 26)).toChar())
            n = n / 26 - 1
        }
        return sb.toString()
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}

class CleanRoomPptSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
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
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f

    companion object {
        private const val MAX_PPT_RECORD_DEPTH = 32
        private val MASTER_BOILERPLATE_PHRASES = listOf(
            "click to edit master",
            "образец заголовка",
            "образец текста",
            "четвертый уровень",
            "пятый уровень"
        )

        private fun isBoilerplateMasterText(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT).trim()
            if (lower == "*") return true
            return MASTER_BOILERPLATE_PHRASES.any { lower.contains(it) }
        }
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 32 * 1024 * 1024L).toInt()
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
                    if (cfb.isValid && (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null)) {
                        lastErrorMsg = "PASSWORD_REQUIRED: Microsoft PowerPoint presentation is encrypted."
                    } else {
                        cfb.readStream("Pictures")?.let { picStream ->
                            parseOfficeArtPicturesStream(picStream)
                        }
                        val pptStream = if (cfb.isValid) cfb.readStream("PowerPoint Document") else null
                        if (pptStream == null || pptStream.size < 16) {
                            lastErrorMsg = "Missing PowerPoint Document stream in PPT container."
                        } else {
                            if (pictures.isEmpty()) {
                                pictures.addAll(EmbeddedImageScanner.scanBinaryForImages(pptStream, 16))
                            }
                            parsePptRecordTree(pptStream)
                        }
                    }
                }
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "PPT presentation read error: ${e.localizedMessage}"
        }
        if (slides.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "PPT presentation contains no slides or is encrypted."
        }
    }

    /**
     * Parses MS-ODRAW OfficeArt BLIP records (`0xF01A` EMF, `0xF01B` WMF, `0xF01C` PICT,
     * `0xF01D` JPEG, `0xF01E` PNG, `0xF01F` DIB, `0xF029` TIFF) from the PPT `Pictures` stream.
     */
    private fun parseOfficeArtPicturesStream(picStream: ByteArray) {
        val bb = ByteBuffer.wrap(picStream).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= picStream.size && pictures.size < 24) {
            val verAndInst = bb.getShort(pos).toInt() and 0xFFFF
            val recInstance = verAndInst ushr 4
            val recType = bb.getShort(pos + 2).toInt() and 0xFFFF
            val recLen = bb.getInt(pos + 4)
            if (recLen <= 0 || pos + 8L + recLen > picStream.size) break
            val dataStart = pos + 8
            val dataEnd = dataStart + recLen

            when (recType) {
                0xF01D, 0xF01E, 0xF01F, 0xF029 -> {
                    val hasTwoUid = (recInstance xor when (recType) {
                        0xF01D -> 0x46A
                        0xF01E -> 0x6E0
                        0xF01F -> 0x7A8
                        else -> 0x6E4
                    }) == 1
                    val headerSkip = if (hasTwoUid) 33 else 17
                    if (recLen > headerSkip) {
                        EmbeddedImageScanner.decodeMetafileOrSvgBitmap(
                            picStream,
                            dataStart + headerSkip,
                            recLen - headerSkip
                        )?.let { pictures.add(it) }
                    }
                }
                0xF01A, 0xF01B, 0xF01C -> {
                    val hasTwoUid = (recInstance and 1) == 1
                    val headerSkip = (if (hasTwoUid) 32 else 16) + 34
                    if (recLen > headerSkip) {
                        val compFlag = picStream[dataStart + headerSkip - 2].toInt() and 0xFF
                        val payloadStart = dataStart + headerSkip
                        val payloadLen = recLen - headerSkip
                        val rawMetaBytes = if (compFlag == 0) {
                            val inf = Inflater(false)
                            try {
                                inf.setInput(picStream, payloadStart, payloadLen)
                                val bos = ByteArrayOutputStream(minOf(payloadLen * 4, 8 * 1024 * 1024))
                                val tmp = ByteArray(16384)
                                var total = 0
                                while (!inf.finished() && total < 16 * 1024 * 1024) {
                                    val r = inf.inflate(tmp)
                                    if (r <= 0) break
                                    bos.write(tmp, 0, r)
                                    total += r
                                }
                                bos.toByteArray()
                            } catch (_: Throwable) {
                                null
                            } finally {
                                inf.end()
                            }
                        } else {
                            picStream.copyOfRange(payloadStart, payloadStart + payloadLen)
                        }
                        if (rawMetaBytes != null && rawMetaBytes.isNotEmpty()) {
                            EmbeddedImageScanner.decodeMetafileOrSvgBitmap(rawMetaBytes)?.let { pictures.add(it) }
                        }
                    }
                }
            }
            pos = dataEnd
        }

        if (pictures.isEmpty()) {
            pictures.addAll(EmbeddedImageScanner.scanBinaryForImages(picStream, 16))
            if (pictures.isEmpty()) {
                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(picStream)?.let { pictures.add(it) }
            }
        }
    }

    private fun tryParseZipPresentationFallback(zipBytes: ByteArray) {
        val slideEntries = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val compContainerLen = zipBytes.size.coerceAtLeast(1)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                val norm = entry.name.replace('\\', '/').trimStart('/')
                val lower = norm.lowercase(Locale.ROOT)
                if (!entry.isDirectory && (
                            (lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml")) ||
                                    lower == "content.xml" ||
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
                        slideEntries[norm] = data
                    }
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
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

    private fun parsePptRecordTree(stream: ByteArray) {
        val bb = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
        var currentSlideTexts = mutableListOf<String>()
        var currentSlideColors = mutableListOf<Int>()
        var currentPicIdx: Int? = null
        var nextAutoPicIdx = 0

        fun flushCurrentSlide() {
            val filteredTexts = currentSlideTexts
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isBoilerplateMasterText(it) }
                .distinct()
            if (filteredTexts.isNotEmpty() || currentPicIdx != null) {
                val assignedPic = currentPicIdx ?: if (nextAutoPicIdx < pictures.size) nextAutoPicIdx++ else null
                slides.add(PptSlideData(filteredTexts, currentSlideColors.toList(), assignedPic))
            }
            currentSlideTexts = mutableListOf()
            currentSlideColors = mutableListOf()
            currentPicIdx = null
        }

        fun walkRecords(start: Int, limit: Int, insideSlideContainer: Boolean, depth: Int) {
            if (depth > MAX_PPT_RECORD_DEPTH || slides.size >= 2000) return
            var pos = start
            while (pos + 8 <= limit) {
                val verAndInstance = bb.getShort(pos).toInt() and 0xFFFF
                val recVer = verAndInstance and 0x000F
                val recInstance = verAndInstance ushr 4
                val recType = bb.getShort(pos + 2).toInt() and 0xFFFF
                val recLen = bb.getInt(pos + 4)
                if (recLen < 0) break
                val dataStart = pos + 8
                val dataEnd = (dataStart.toLong() + recLen.toLong()).coerceAtMost(limit.toLong()).toInt()
                if (dataStart > limit || dataEnd < dataStart) break

                when (recType) {
                    0x0FF6 -> {
                        lastErrorMsg = "PASSWORD_REQUIRED: Microsoft PowerPoint presentation (.ppt) is password protected."
                        return
                    }
                    0x03F8, 0x03F0 -> {
                        // Skip MainMaster (0x03F8) and Notes (0x03F0) containers
                    }
                    0x03EE -> {
                        flushCurrentSlide()
                        walkRecords(dataStart, dataEnd, true, depth + 1)
                        flushCurrentSlide()
                    }
                    0x0FF0 -> {
                        if (recInstance == 0) {
                            flushCurrentSlide()
                            walkRecords(dataStart, dataEnd, true, depth + 1)
                            flushCurrentSlide()
                        }
                    }
                    0x03F3 -> {
                        if (insideSlideContainer) {
                            flushCurrentSlide()
                        }
                    }
                    0xF00B -> {
                        val propCount = recInstance.coerceIn(0, 1024)
                        var pPos = dataStart
                        for (p in 0 until propCount) {
                            if (pPos + 6 > dataEnd) break
                            val propIdRaw = bb.getShort(pPos).toInt() and 0xFFFF
                            val propId = propIdRaw and 0x3FFF
                            val propVal = bb.getInt(pPos + 2)
                            if (propId == 0x0181) {
                                val r = propVal and 0xFF
                                val g = (propVal ushr 8) and 0xFF
                                val b = (propVal ushr 16) and 0xFF
                                if (r + g + b in 15..730 && currentSlideColors.size < 64) {
                                    currentSlideColors.add(Color.rgb(r, g, b))
                                }
                            } else if (propId == 0x0104 && propVal >= 1 && (propVal - 1) in pictures.indices) {
                                currentPicIdx = propVal - 1
                            }
                            pPos += 6
                        }
                    }
                    0x0FA0 -> {
                        if (insideSlideContainer && dataEnd > dataStart && currentSlideTexts.size < 512) {
                            val txt = String(stream, dataStart, (dataEnd - dataStart) and -2, StandardCharsets.UTF_16LE)
                                .replace('\r', '\n')
                                .replace('\u000B', '\n')
                                .trim()
                            if (txt.isNotEmpty() && !isBoilerplateMasterText(txt)) {
                                currentSlideTexts.add(txt)
                            }
                        }
                    }
                    0x0FA8 -> {
                        if (insideSlideContainer && dataEnd > dataStart && currentSlideTexts.size < 512) {
                            val txt = String(stream, dataStart, dataEnd - dataStart, PlainTextSession.CP1251)
                                .replace('\r', '\n')
                                .replace('\u000B', '\n')
                                .trim()
                            if (txt.isNotEmpty() && !isBoilerplateMasterText(txt)) {
                                currentSlideTexts.add(txt)
                            }
                        }
                    }
                    else -> {
                        if (recVer == 0x0F && dataEnd > dataStart) {
                            walkRecords(dataStart, dataEnd, insideSlideContainer, depth + 1)
                        }
                    }
                }
                pos = dataEnd
            }
        }

        walkRecords(0, stream.size, false, 0)
        flushCurrentSlide()
    }

    private fun computeSlideCardSize(
        canvasWidth: Float = lastCanvasWidth,
        canvasHeight: Float = lastCanvasHeight
    ): Pair<Float, Float> {
        val margin = 2f
        val availW = (canvasWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (canvasHeight - margin * 2f).coerceAtLeast(100f)
        val aspect = 16f / 9f
        var cardH = availH
        var cardW = cardH * aspect
        if (cardW > availW) {
            cardW = availW
            cardH = cardW / aspect
        }
        return cardW to cardH
    }

    override val pageCount: Int get() = slides.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = computeSlideCardSize().first
    override fun getDocumentHeight(): Float = computeSlideCardSize().second

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return slides.joinToString("\n\n") { it.texts.joinToString("\n") }
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        return slides.getOrNull(cleanIdx)?.texts?.joinToString("\n") ?: ""
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
        val slideData = slides.getOrNull(cleanPageIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))) ?: return

        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)
        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f

        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)

        val slideRect = RectF(left, top, left + cardW, top + cardH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(slideRect, 8f, 8f, bgPaint)

        val accentColor = slideData.fillColors.firstOrNull() ?: Color.rgb(35, 65, 130)
        val headerBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 32
        }
        canvas.drawRoundRect(RectF(left, top, left + cardW, top + cardH * 0.22f), 8f, 8f, headerBandPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(slideRect, 8f, 8f, borderPaint)

        val baseScaleFactor = (cardH / 680f).coerceIn(0.75f, 2.0f)
        val picBmp = slideData.pictureIndex?.let { pictures.getOrNull(it) }
        val textRightMargin = if (picBmp != null && !picBmp.isRecycled) cardW * 0.42f else 80f
        val availWidth = (cardW - textRightMargin).toInt().coerceAtLeast(100)
        val maxAvailTextH = (cardH - 64f).coerceAtLeast(80f)

        var autofit = 1.0f
        var layouts: List<StaticLayout> = emptyList()
        for (attempt in 0..4) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25, 35, 60)
                textSize = 38f * baseScaleFactor * autofit
                isFakeBoldText = true
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 28f * baseScaleFactor * autofit
            }
            val candidate = ArrayList<StaticLayout>(slideData.texts.size)
            var totalH = 0f
            for ((idx, block) in slideData.texts.withIndex()) {
                val p = if (idx == 0) titlePaint else bodyPaint
                val l = StaticLayout.Builder.obtain(block, 0, block.length, p, availWidth)
                    .setIncludePad(false)
                    .build()
                candidate.add(l)
                totalH += l.height + 18f * autofit
            }
            layouts = candidate
            if (totalH <= maxAvailTextH || autofit <= 0.55f) break
            autofit *= (maxAvailTextH / totalH).coerceIn(0.68f, 0.88f)
        }

        var curY = top + 32f
        canvas.save()
        canvas.clipRect(left + 16f, top + 16f, left + cardW - 16f, top + cardH - 16f)
        for (layout in layouts) {
            canvas.save()
            canvas.translate(left + 40f, curY)
            layout.draw(canvas)
            canvas.restore()
            curY += layout.height + 18f * autofit
        }

        if (picBmp != null && !picBmp.isRecycled) {
            val boxW = cardW * 0.36f
            val boxH = cardH * 0.62f
            val boxLeft = left + cardW * 0.60f
            val boxTop = top + cardH * 0.26f
            canvas.drawBitmap(picBmp, null, RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH), Paint(Paint.FILTER_BITMAP_FLAG))
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
        try { pfd.close() } catch (_: Exception) {}
    }
}

class CleanRoomRtfSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 24 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "RTF file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (bytes.size < 5 || !String(bytes, 0, 5, StandardCharsets.US_ASCII).startsWith("{\\rtf")) {
                    lastErrorMsg = "File lacks RTF header ({\\rtf)."
                } else {
                    fullText = parseRtfBytes(bytes, embeddedImages)
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "RTF read error: ${e.localizedMessage}"
        }
        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "RTF document contains no visible text."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    companion object {
        private const val MAX_RTF_GROUP_DEPTH = 512
        private const val MAX_PICT_HEX_CHARS = 16 * 1024 * 1024
        private const val MAX_RTF_TEXT_CHARS = 8_000_000

        private fun charsetForRtfFCharset(fcharsetCode: Int): Charset? {
            val name = when (fcharsetCode) {
                0 -> "windows-1252"
                128 -> "Shift_JIS"
                129 -> "EUC-KR"
                134 -> "GB2312"
                136 -> "Big5"
                161 -> "windows-1253"
                162 -> "windows-1254"
                163 -> "windows-1258"
                177 -> "windows-1255"
                178 -> "windows-1256"
                186 -> "windows-1257"
                204 -> "windows-1251"
                222 -> "windows-874"
                238 -> "windows-1250"
                255 -> "CP850"
                else -> null
            } ?: return null
            return try {
                Charset.forName(name)
            } catch (_: Throwable) {
                null
            }
        }

        fun parseRtfBytes(bytes: ByteArray, outImages: MutableList<Bitmap>? = null): String {
            val out = StringBuilder()
            var defaultCharset: Charset = PlainTextSession.CP1251
            val fontCharsets = HashMap<Int, Charset>()
            val hexBuf = ByteArrayOutputStream()
            val pictHexBuilder = StringBuilder()

            data class RtfGroupState(
                var ucSkip: Int = 1,
                var ignoreContent: Boolean = false,
                var inFontTbl: Boolean = false,
                var currentFontId: Int = -1,
                var inPict: Boolean = false,
                var inNonShpPict: Boolean = false,
                var inBlipUid: Boolean = false,
                var bold: Boolean = false,
                var italic: Boolean = false,
                var underline: Boolean = false,
                var strike: Boolean = false
            )
            val stack = ArrayDeque<RtfGroupState>()
            var overflowGroupDepth = 0
            var state = RtfGroupState()

            var emittedBold = false
            var emittedItalic = false
            var emittedUnderline = false
            var emittedStrike = false

            fun activeCharset(): Charset {
                return fontCharsets[state.currentFontId] ?: defaultCharset
            }

            fun syncInlineTagsBeforeText() {
                if (!emittedBold && state.bold) {
                    out.append("<b>")
                    emittedBold = true
                } else if (emittedBold && !state.bold) {
                    out.append("</b>")
                    emittedBold = false
                }
                if (!emittedItalic && state.italic) {
                    out.append("<i>")
                    emittedItalic = true
                } else if (emittedItalic && !state.italic) {
                    out.append("</i>")
                    emittedItalic = false
                }
                if (!emittedUnderline && state.underline) {
                    out.append("<u>")
                    emittedUnderline = true
                } else if (emittedUnderline && !state.underline) {
                    out.append("</u>")
                    emittedUnderline = false
                }
                if (!emittedStrike && state.strike) {
                    out.append("<s>")
                    emittedStrike = true
                } else if (emittedStrike && !state.strike) {
                    out.append("</s>")
                    emittedStrike = false
                }
            }

            fun closeAllInlineTagsBeforeBreak() {
                if (emittedStrike) {
                    out.append("</s>")
                    emittedStrike = false
                }
                if (emittedUnderline) {
                    out.append("</u>")
                    emittedUnderline = false
                }
                if (emittedItalic) {
                    out.append("</i>")
                    emittedItalic = false
                }
                if (emittedBold) {
                    out.append("</b>")
                    emittedBold = false
                }
            }

            fun flushHexBuffer() {
                if (hexBuf.size() > 0) {
                    if (out.length < MAX_RTF_TEXT_CHARS) {
                        syncInlineTagsBeforeText()
                        out.append(String(hexBuf.toByteArray(), activeCharset()))
                    }
                    hexBuf.reset()
                }
            }

            fun decodePictHexIfPresent() {
                if (outImages != null && outImages.size < 32 && pictHexBuilder.length in 64..MAX_PICT_HEX_CHARS) {
                    try {
                        val cleanHex = pictHexBuilder.toString()
                        val rawBytes = ByteArray(cleanHex.length / 2)
                        for (k in rawBytes.indices) {
                            val hi = Character.digit(cleanHex[k * 2], 16)
                            val lo = Character.digit(cleanHex[k * 2 + 1], 16)
                            if (hi >= 0 && lo >= 0) {
                                rawBytes[k] = ((hi shl 4) or lo).toByte()
                            }
                        }
                        EmbeddedImageScanner.decodeMetafileOrSvgBitmap(rawBytes, 0, rawBytes.size)?.let {
                            closeAllInlineTagsBeforeBreak()
                            val idx = outImages.size
                            outImages.add(it)
                            if (out.isNotEmpty() && !out.endsWith('\n')) {
                                out.append('\n')
                            }
                            out.append("[IMAGE:$idx]\n")
                        }
                    } catch (_: Throwable) {}
                }
                pictHexBuilder.setLength(0)
            }

            val ignoredDestinations = setOf(
                "fonttbl", "colortbl", "stylesheet", "info", "object",
                "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
                "fldinst", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl",
                "generator", "company", "operator", "themedata", "colorschememapping",
                "latentstyles", "datastore", "pgp", "pgptbl", "nonshppict", "sn", "sv",
                "pgdsctbl", "userprops", "ftnsep", "ftnsepc", "aftnsep"
            )

            var i = 0
            var skipAfterUnicode = 0

            while (i < bytes.size && out.length < MAX_RTF_TEXT_CHARS) {
                val b = bytes[i].toInt() and 0xFF
                val c = b.toChar()

                when (c) {
                    '{' -> {
                        flushHexBuffer()
                        if (stack.size < MAX_RTF_GROUP_DEPTH) {
                            stack.addLast(state.copy())
                        } else {
                            overflowGroupDepth++
                        }
                        i++
                    }
                    '}' -> {
                        flushHexBuffer()
                        if (overflowGroupDepth > 0) {
                            overflowGroupDepth--
                        } else {
                            val wasPict = state.inPict && !state.inNonShpPict
                            if (stack.isNotEmpty()) state = stack.removeLast()
                            if (wasPict && !state.inPict) {
                                decodePictHexIfPresent()
                            }
                        }
                        i++
                    }
                    '\\' -> {
                        i++
                        if (i >= bytes.size) break
                        val next = (bytes[i].toInt() and 0xFF).toChar()
                        when {
                            next == '\'' -> {
                                if (i + 2 < bytes.size) {
                                    val hexStr = String(bytes, i + 1, 2, StandardCharsets.US_ASCII)
                                    val byteVal = hexStr.toIntOrNull(16)
                                    if (byteVal != null) {
                                        if (skipAfterUnicode > 0) {
                                            skipAfterUnicode--
                                        } else if (!state.ignoreContent && hexBuf.size() < 262_144) {
                                            hexBuf.write(byteVal)
                                        }
                                    }
                                    i += 3
                                } else i++
                            }
                            next == '*' -> {
                                flushHexBuffer()
                                var peek = i + 1
                                while (peek < bytes.size && (bytes[peek].toInt() and 0xFF).toChar() == '\\') peek++
                                val destStart = peek
                                while (peek < bytes.size && (bytes[peek].toInt() and 0xFF).toChar().isLetter()) peek++
                                val destWord = if (peek > destStart) {
                                    String(bytes, destStart, peek - destStart, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
                                } else ""
                                if (destWord !in setOf("shppict", "shpinst", "fldrslt", "shptxt")) {
                                    state.ignoreContent = true
                                }
                                i++
                            }
                            next == '\\' || next == '{' || next == '}' -> {
                                flushHexBuffer()
                                if (skipAfterUnicode > 0) skipAfterUnicode--
                                else if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append(next)
                                }
                                i++
                            }
                            next == '~' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append('\u00A0')
                                }
                                i++
                            }
                            next == '-' -> {
                                flushHexBuffer()
                                i++
                            }
                            next == '_' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append('-')
                                }
                                i++
                            }
                            next == '\n' || next == '\r' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    closeAllInlineTagsBeforeBreak()
                                    out.append('\n')
                                }
                                i++
                            }
                            next.isLetter() -> {
                                val wordStart = i
                                while (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar().isLetter()) i++
                                val word = String(bytes, wordStart, i - wordStart, StandardCharsets.US_ASCII)

                                val numStart = i
                                if (i < bytes.size && ((bytes[i].toInt() and 0xFF).toChar() == '-' || (bytes[i].toInt() and 0xFF).toChar().isDigit())) {
                                    i++
                                    while (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar().isDigit()) i++
                                }
                                val paramStr = if (i > numStart) String(bytes, numStart, i - numStart, StandardCharsets.US_ASCII) else null
                                if (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar() == ' ') i++

                                if (word != "u") flushHexBuffer()

                                when (word) {
                                    "fonttbl" -> {
                                        state.ignoreContent = true
                                        state.inFontTbl = true
                                    }
                                    "f" -> {
                                        val fId = paramStr?.toIntOrNull()
                                        if (fId != null) {
                                            state.currentFontId = fId
                                        }
                                    }
                                    "fcharset" -> {
                                        val fcs = paramStr?.toIntOrNull()
                                        if (fcs != null) {
                                            val resolvedCs = charsetForRtfFCharset(fcs)
                                            if (resolvedCs != null) {
                                                if (state.currentFontId >= 0) {
                                                    fontCharsets[state.currentFontId] = resolvedCs
                                                }
                                                if (fcs == 204) {
                                                    defaultCharset = resolvedCs
                                                }
                                            }
                                        }
                                    }
                                    "nonshppict" -> {
                                        state.ignoreContent = true
                                        state.inNonShpPict = true
                                    }
                                    "shppict", "shp", "shpinst", "shptxt" -> {
                                        if (!state.inNonShpPict) {
                                            state.ignoreContent = false
                                        }
                                    }
                                    "blipuid" -> {
                                        state.ignoreContent = true
                                        state.inBlipUid = true
                                    }
                                    "pict" -> {
                                        state.ignoreContent = true
                                        if (!state.inNonShpPict) {
                                            state.inPict = true
                                            state.inBlipUid = false
                                            pictHexBuilder.setLength(0)
                                        }
                                    }
                                    in ignoredDestinations -> state.ignoreContent = true
                                    "ansicpg" -> {
                                        val cp = paramStr?.toIntOrNull() ?: 1251
                                        if (cp != 1252 || fontCharsets.values.none { it == PlainTextSession.CP1251 }) {
                                            defaultCharset = try {
                                                Charset.forName("windows-$cp")
                                            } catch (_: Exception) {
                                                PlainTextSession.CP1251
                                            }
                                        }
                                    }
                                    "uc" -> state.ucSkip = (paramStr?.toIntOrNull() ?: 1).coerceIn(0, 16)
                                    "b" -> state.bold = (paramStr != "0")
                                    "i" -> state.italic = (paramStr != "0")
                                    "ul" -> state.underline = (paramStr != "0")
                                    "ulnone" -> state.underline = false
                                    "strike", "striked" -> state.strike = (paramStr != "0")
                                    "plain" -> {
                                        state.bold = false
                                        state.italic = false
                                        state.underline = false
                                        state.strike = false
                                    }
                                    "emdash" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('—') }
                                    "endash" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('–') }
                                    "bullet" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append("• ") }
                                    "ldblquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('“') }
                                    "rdblquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('”') }
                                    "lquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('‘') }
                                    "rquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('’') }
                                    "u" -> {
                                        flushHexBuffer()
                                        if (!state.ignoreContent && paramStr != null) {
                                            var codePoint = paramStr.toIntOrNull() ?: 63
                                            if (codePoint < 0) codePoint += 65536
                                            syncInlineTagsBeforeText()
                                            out.append(codePoint.toChar())
                                            skipAfterUnicode = state.ucSkip
                                        }
                                    }
                                    "par", "line", "sect", "page", "row", "nestrow" -> {
                                        if (!state.ignoreContent) {
                                            closeAllInlineTagsBeforeBreak()
                                            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                                                out.deleteCharAt(out.length - 1)
                                            }
                                            out.append('\n')
                                        }
                                    }
                                    "tab", "cell", "nestcell" -> {
                                        if (!state.ignoreContent) {
                                            closeAllInlineTagsBeforeBreak()
                                            out.append('\t')
                                        }
                                    }
                                }
                            }
                            else -> i++
                        }
                    }
                    '\r', '\n' -> i++
                    else -> {
                        flushHexBuffer()
                        if (state.inPict && !state.inNonShpPict && !state.inBlipUid && outImages != null && outImages.size < 32) {
                            if ((c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') && pictHexBuilder.length < MAX_PICT_HEX_CHARS) {
                                pictHexBuilder.append(c)
                            }
                        } else if (skipAfterUnicode > 0) {
                            skipAfterUnicode--
                        } else if (!state.ignoreContent && b >= 32) {
                            syncInlineTagsBeforeText()
                            out.append(c)
                        }
                        i++
                    }
                }
            }
            flushHexBuffer()
            closeAllInlineTagsBeforeBreak()
            return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }
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

class CleanRoomIso9660Session(private val pfd: ParcelFileDescriptor) : ArchiveSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    data class UdfExtent(
        val byteOffset: Long,
        val byteLength: Long
    )

    data class IsoFileEntry(
        val path: String,
        val size: Long,
        val extents: List<UdfExtent>,
        val inlinePayload: ByteArray? = null
    )

    private val fileMap = LinkedHashMap<String, IsoFileEntry>()
    private val displayEntries = mutableListOf<String>()
    private val tempExtractedFiles = mutableListOf<File>()

    override val isZipBombDetected: Boolean = false
    override val sanitizedFileEntries: List<String> get() = displayEntries
    override fun getLastError(): String? = lastErrorMsg

    companion object {
        private const val SECTOR_SIZE = 2048L
        private const val MAX_EXTRACT_BYTES = 256 * 1024 * 1024L
        private val ISO_SIZE_SUFFIX_REGEX = Regex(""" \(\d+ B\)$| \(unknown size\)$""", RegexOption.IGNORE_CASE)

        private fun stripDisplaySizeSuffix(entryPath: String): String {
            return entryPath.replace(ISO_SIZE_SUFFIX_REGEX, "").trim()
        }
    }

    init {
        parseIsoAndUdfVolumeDescriptors()
    }

    private fun parseIsoAndUdfVolumeDescriptors() {
        try {
            val headProbe = ByteBuffer.allocateDirect(512)
            val headRead = source.readAt(0L, headProbe)
            if (headRead >= 4) {
                val b0 = headProbe.get(0).toInt() and 0xFF
                val b1 = headProbe.get(1).toInt() and 0xFF
                val b2 = headProbe.get(2).toInt() and 0xFF
                val b3 = headProbe.get(3).toInt() and 0xFF
                if (b0 == 0x50 && b1 == 0x4B) {
                    if (parseZipArchiveFallback()) return
                }
                if (b0 == 0x1F && b1 == 0x8B) {
                    if (parseGzipArchiveFallback()) return
                }
                if (headRead >= 262 &&
                    headProbe.get(257) == 'u'.code.toByte() &&
                    headProbe.get(258) == 's'.code.toByte() &&
                    headProbe.get(259) == 't'.code.toByte() &&
                    headProbe.get(260) == 'a'.code.toByte() &&
                    headProbe.get(261) == 'r'.code.toByte()
                ) {
                    if (parseTarArchiveFallback(null)) return
                }
            }

            val secBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            var pvdRootRecord: ByteArray? = null
            var jolietRootRecord: ByteArray? = null
            var sawUdfNSR = false

            for (secIdx in 16..64) {
                secBuf.clear()
                val read = source.readAt(secIdx * SECTOR_SIZE, secBuf)
                if (read < SECTOR_SIZE.toInt()) break
                val ident = ByteArray(5)
                secBuf.position(1)
                secBuf.get(ident)
                val identStr = String(ident, StandardCharsets.US_ASCII)

                if (identStr == "NSR02" || identStr == "NSR03" || identStr == "BEA01") {
                    sawUdfNSR = true
                    continue
                }
                if (identStr != "CD001") continue

                val vdType = secBuf.get(0).toInt() and 0xFF
                if (vdType == 1) {
                    val rootRec = ByteArray(34)
                    secBuf.position(156)
                    secBuf.get(rootRec)
                    pvdRootRecord = rootRec
                } else if (vdType == 2) {
                    val esc0 = secBuf.get(88).toInt() and 0xFF
                    val esc1 = secBuf.get(89).toInt() and 0xFF
                    val esc2 = secBuf.get(90).toInt() and 0xFF
                    if (esc0 == 0x25 && esc1 == 0x2F && (esc2 == 0x40 || esc2 == 0x43 || esc2 == 0x45)) {
                        val rootRec = ByteArray(34)
                        secBuf.position(156)
                        secBuf.get(rootRec)
                        jolietRootRecord = rootRec
                    }
                } else if (vdType == 255) {
                    break
                }
            }

            val chosenRoot = jolietRootRecord ?: pvdRootRecord
            val isJoliet = jolietRootRecord != null

            if (chosenRoot != null) {
                val rootBb = ByteBuffer.wrap(chosenRoot).order(ByteOrder.LITTLE_ENDIAN)
                val rootLba = rootBb.getInt(2).toLong() and 0xFFFFFFFFL
                val rootLen = rootBb.getInt(10).toLong() and 0xFFFFFFFFL
                val visitedLbas = HashSet<Long>()
                traverseIsoDirectory(rootLba, rootLen, "", isJoliet, visitedLbas, 0)
            }

            val isBridgeStubOnly = fileMap.size <= 1 && fileMap.keys.all { it.contains("readme", ignoreCase = true) }
            if (fileMap.isEmpty() || (sawUdfNSR && isBridgeStubOnly)) {
                parseUdfFileSystem()
            }

            if (fileMap.isEmpty() && chosenRoot == null && !sawUdfNSR) {
                lastErrorMsg = "ISO image lacks standard ISO-9660 or UDF volume descriptor (CD001 / NSR02 / NSR03)."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            if (fileMap.isEmpty()) {
                lastErrorMsg = "ISO-9660/UDF file system read error: ${e.localizedMessage}"
            }
        }
    }

    private fun parseGzipArchiveFallback(): Boolean {
        val maxBytes = minOf(source.size, 32L * 1024L * 1024L).toInt()
        if (maxBytes <= 0) return false
        val buf = ByteBuffer.allocateDirect(maxBytes)
        val got = source.readAt(0L, buf)
        if (got <= 0) return false
        buf.flip()
        val compBytes = ByteArray(got)
        buf.get(compBytes)

        val decompressed = GZIPInputStream(ByteArrayInputStream(compBytes)).use { gis ->
            val bos = ByteArrayOutputStream()
            val chunk = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val r = gis.read(chunk)
                if (r <= 0) break
                total += r
                if (total > 16L * 1024L * 1024L && (total / got.coerceAtLeast(1)) > 150L) {
                    throw SecurityException("Превышен безопасный лимит распаковки GZIP (защита от Zip-бомб).")
                }
                bos.write(chunk, 0, r)
            }
            bos.toByteArray()
        }
        if (decompressed.isEmpty()) return false
        if (decompressed.size >= 512 && parseTarArchiveFallback(decompressed)) return true

        val name = "decompressed_stream.txt"
        fileMap[name] = IsoFileEntry(name, decompressed.size.toLong(), emptyList(), decompressed)
        displayEntries.add("$name (${decompressed.size} B)")
        return true
    }

    private fun parseTarArchiveFallback(customBytes: ByteArray?): Boolean {
        val tarBytes = customBytes ?: run {
            val maxBytes = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (maxBytes < 512) return false
            val buf = ByteBuffer.allocateDirect(maxBytes)
            val got = source.readAt(0L, buf)
            if (got < 512) return false
            buf.flip()
            val arr = ByteArray(got)
            buf.get(arr)
            arr
        }

        var pos = 0
        while (pos + 512 <= tarBytes.size && fileMap.size < 10_000) {
            if (tarBytes[pos].toInt() == 0) break
            var nameEnd = pos
            while (nameEnd < pos + 100 && tarBytes[nameEnd].toInt() != 0) nameEnd++
            val rawName = String(tarBytes, pos, nameEnd - pos, StandardCharsets.UTF_8)
                .replace('\\', '/')
                .trimStart('/')
            val sizeOctal = String(tarBytes, pos + 124, 12, StandardCharsets.US_ASCII)
                .trim { it <= ' ' || it == '\u0000' }
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = tarBytes[pos + 156].toInt().toChar()
            val dataStart = pos + 512
            val isDir = typeFlag == '5' || rawName.endsWith("/")

            if (!isDir && rawName.isNotEmpty() && !rawName.contains("..") &&
                entrySize in 0L..(tarBytes.size - dataStart).toLong()
            ) {
                val payload = tarBytes.copyOfRange(dataStart, dataStart + entrySize.toInt())
                fileMap[rawName] = IsoFileEntry(rawName, entrySize, emptyList(), payload)
                displayEntries.add("$rawName ($entrySize B)")
            }
            val paddedBlocks = ((entrySize + 511L) / 512L) * 512L
            val nextPos = dataStart + paddedBlocks.toInt()
            if (nextPos <= pos) break
            pos = nextPos
        }
        return fileMap.isNotEmpty()
    }

    private fun parseZipArchiveFallback(): Boolean {
        val maxZipBytes = minOf(source.size, 64L * 1024L * 1024L).toInt()
        if (maxZipBytes <= 0) return false
        val buf = ByteBuffer.allocateDirect(maxZipBytes)
        val got = source.readAt(0L, buf)
        if (got <= 0) return false
        buf.flip()
        val bytes = ByteArray(got)
        buf.get(bytes)

        val compContainerLen = got.coerceAtLeast(1).toLong()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            val chunk = ByteArray(16384)
            var totalUnpacked = 0L
            while (fileMap.size < 10_000) {
                val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && cleanName.isNotEmpty() && !cleanName.contains("..")) {
                    val bos = ByteArrayOutputStream()
                    var entryUnpacked = 0L
                    val entryCompHint = entry.compressedSize.takeIf { it > 0L } ?: compContainerLen
                    var r: Int
                    while (zis.read(chunk).also { r = it } > 0) {
                        entryUnpacked += r
                        totalUnpacked += r
                        if (entryUnpacked > 16L * 1024L * 1024L && (entryUnpacked / entryCompHint.coerceAtLeast(1L)) > 150L) {
                            throw SecurityException("Превышен безопасный лимит распаковки архива (защита от Zip-бомб).")
                        }
                        if (totalUnpacked > 128L * 1024L * 1024L) break
                        bos.write(chunk, 0, r)
                    }
                    val payload = bos.toByteArray()
                    fileMap[cleanName] = IsoFileEntry(
                        path = cleanName,
                        size = payload.size.toLong(),
                        extents = emptyList(),
                        inlinePayload = payload
                    )
                    displayEntries.add("$cleanName (${payload.size} B)")
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
        return fileMap.isNotEmpty()
    }

    private fun parseUdfFileSystem() {
        val secBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        secBuf.clear()
        if (source.readAt(256L * SECTOR_SIZE, secBuf) < 512) return
        val avdpTag = secBuf.getShort(0).toInt() and 0xFFFF
        if (avdpTag != 2) return

        val vdsLen = secBuf.getInt(16).toLong() and 0xFFFFFFFFL
        val vdsLba = secBuf.getInt(20).toLong() and 0xFFFFFFFFL
        val vdsSectors = (vdsLen / SECTOR_SIZE).coerceIn(1L, 64L)

        var partitionStartSector = -1L
        var fsdPartitionRelativeBlock = 0L

        for (s in 0 until vdsSectors) {
            secBuf.clear()
            if (source.readAt((vdsLba + s) * SECTOR_SIZE, secBuf) < 512) break
            val tagId = secBuf.getShort(0).toInt() and 0xFFFF
            if (tagId == 8) break
            if (tagId == 5) {
                partitionStartSector = secBuf.getInt(188).toLong() and 0xFFFFFFFFL
            } else if (tagId == 6) {
                fsdPartitionRelativeBlock = secBuf.getInt(248 + 4).toLong() and 0xFFFFFFFFL
            }
        }

        if (partitionStartSector < 0L) return

        var rootIcbBlock = -1L
        for (probe in 0..32) {
            val sector = partitionStartSector + fsdPartitionRelativeBlock + probe
            secBuf.clear()
            if (source.readAt(sector * SECTOR_SIZE, secBuf) < 512) break
            val tagId = secBuf.getShort(0).toInt() and 0xFFFF
            if (tagId == 256) {
                rootIcbBlock = secBuf.getInt(404).toLong() and 0xFFFFFFFFL
                break
            }
        }

        if (rootIcbBlock >= 0L) {
            if (fileMap.size == 1 && fileMap.keys.first().contains("readme", ignoreCase = true)) {
                fileMap.clear()
                displayEntries.clear()
            }
            val visitedBlocks = HashSet<Long>()
            traverseUdfDirectory(partitionStartSector, rootIcbBlock, "", visitedBlocks, 0)
        }
    }

    private data class UdfIcbData(
        val fileType: Int,
        val infoLength: Long,
        val extents: List<UdfExtent>,
        val inlineBytes: ByteArray?
    )

    private fun readUdfIcb(partitionStartSector: Long, blockNum: Long): UdfIcbData? {
        val buf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val absOffset = (partitionStartSector + blockNum) * SECTOR_SIZE
        val r = source.readAt(absOffset, buf)
        if (r < 256) return null

        val tagId = buf.getShort(0).toInt() and 0xFFFF
        if (tagId != 261 && tagId != 266) return null

        val icbFlags = buf.getShort(34).toInt() and 0xFFFF
        val allocType = icbFlags and 0x07
        val fileType = buf.get(27).toInt() and 0xFF
        val infoLength = buf.getLong(56).coerceAtLeast(0L)

        val eaOffset = if (tagId == 261) 176 else 216
        val lEa = buf.getInt(eaOffset - 8).coerceIn(0, 1800)
        val lAd = buf.getInt(eaOffset - 4).coerceIn(0, 1800)
        val adStart = eaOffset + lEa
        if (adStart + lAd > r) return null

        if (allocType == 3) {
            val inline = ByteArray(lAd)
            buf.position(adStart)
            buf.get(inline)
            return UdfIcbData(
                fileType = fileType,
                infoLength = lAd.toLong(),
                extents = listOf(UdfExtent(absOffset + adStart, lAd.toLong())),
                inlineBytes = inline
            )
        }

        val extents = ArrayList<UdfExtent>()
        val visitedAedBlocks = HashSet<Long>()
        parseUdfAllocationDescriptors(
            buf = buf,
            adStart = adStart,
            lAd = lAd,
            allocType = allocType,
            partitionStartSector = partitionStartSector,
            outExtents = extents,
            visitedAedBlocks = visitedAedBlocks,
            depth = 0
        )

        if (extents.isEmpty()) return null
        return UdfIcbData(fileType, infoLength, extents, null)
    }

    private fun parseUdfAllocationDescriptors(
        buf: ByteBuffer,
        adStart: Int,
        lAd: Int,
        allocType: Int,
        partitionStartSector: Long,
        outExtents: MutableList<UdfExtent>,
        visitedAedBlocks: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 32 || outExtents.size >= 4096) return
        val descSize = when (allocType) {
            0 -> 8
            1 -> 16
            else -> return
        }
        var pos = adStart
        val end = adStart + lAd
        while (pos + descSize <= end && outExtents.size < 4096) {
            val lenAndType = buf.getInt(pos)
            val extLen = (lenAndType and 0x3FFFFFFF).toLong()
            val extType = (lenAndType ushr 30) and 0x03
            val extBlock = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL

            if (extLen == 0L) break

            when (extType) {
                0 -> {
                    outExtents.add(UdfExtent((partitionStartSector + extBlock) * SECTOR_SIZE, extLen))
                }
                3 -> {
                    if (visitedAedBlocks.add(extBlock)) {
                        val aedBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                        val aedRead = source.readAt((partitionStartSector + extBlock) * SECTOR_SIZE, aedBuf)
                        if (aedRead >= 24 && (aedBuf.getShort(0).toInt() and 0xFFFF) == 258) {
                            val nextLAd = aedBuf.getInt(20).coerceIn(0, aedRead - 24)
                            parseUdfAllocationDescriptors(
                                buf = aedBuf,
                                adStart = 24,
                                lAd = nextLAd,
                                allocType = allocType,
                                partitionStartSector = partitionStartSector,
                                outExtents = outExtents,
                                visitedAedBlocks = visitedAedBlocks,
                                depth = depth + 1
                            )
                        }
                    }
                    break
                }
            }
            pos += descSize
        }
    }

    private fun readUdfExtentsToByteArray(icb: UdfIcbData, maxBytes: Int): ByteArray? {
        if (icb.inlineBytes != null) return icb.inlineBytes
        val targetLen = minOf(icb.infoLength, maxBytes.toLong(), source.size.coerceAtLeast(0L)).toInt()
        if (targetLen <= 0) return null
        val out = ByteArrayOutputStream(minOf(targetLen, 65536))
        val tmpBuf = ByteBuffer.allocateDirect(minOf(targetLen, 64 * 1024))
        val tmpArr = ByteArray(tmpBuf.capacity())

        var remaining = targetLen
        for (ext in icb.extents) {
            if (remaining <= 0) break
            var extRem = minOf(ext.byteLength, remaining.toLong()).toInt()
            var curOff = ext.byteOffset
            while (extRem > 0 && remaining > 0) {
                val chunk = minOf(extRem, tmpBuf.capacity())
                tmpBuf.clear()
                tmpBuf.limit(chunk)
                val got = source.readAt(curOff, tmpBuf)
                if (got <= 0) break
                tmpBuf.flip()
                tmpBuf.get(tmpArr, 0, got)
                out.write(tmpArr, 0, got)
                curOff += got
                extRem -= got
                remaining -= got
            }
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun decodeUdfOstaCS0(raw: ByteArray, offset: Int, len: Int): String {
        if (len <= 1 || offset + len > raw.size) return ""
        val compId = raw[offset].toInt() and 0xFF
        return when (compId) {
            8 -> String(raw, offset + 1, len - 1, StandardCharsets.ISO_8859_1)
            16 -> String(raw, offset + 1, (len - 1) and -2, StandardCharsets.UTF_16BE)
            else -> String(raw, offset, len, StandardCharsets.UTF_8)
        }.replace('\u0000', ' ').trim()
    }

    private fun traverseUdfDirectory(
        partitionStart: Long,
        dirIcbBlock: Long,
        parentPath: String,
        visited: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 16 || !visited.add(dirIcbBlock) || fileMap.size >= 10_000) return
        val icb = readUdfIcb(partitionStart, dirIcbBlock) ?: return
        if (icb.fileType != 4 || icb.infoLength <= 0L || icb.infoLength > 4 * 1024 * 1024L) return

        val dirBytes = readUdfExtentsToByteArray(icb, 4 * 1024 * 1024) ?: return
        val bb = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 38 <= dirBytes.size && fileMap.size < 10_000) {
            val tagId = bb.getShort(pos).toInt() and 0xFFFF
            if (tagId != 257) break

            val fileCharacteristics = dirBytes[pos + 18].toInt() and 0xFF
            val lFi = dirBytes[pos + 19].toInt() and 0xFF
            val childIcbBlock = bb.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val lIu = bb.getShort(pos + 36).toInt() and 0xFFFF

            val nameStart = pos + 38 + lIu
            val unalignedEnd = nameStart + lFi
            val nextPos = (unalignedEnd + 3) and -4
            if (unalignedEnd > dirBytes.size || nextPos <= pos) break

            val isParent = (fileCharacteristics and 0x08) != 0
            val isDeleted = (fileCharacteristics and 0x04) != 0
            val isDir = (fileCharacteristics and 0x02) != 0

            if (!isParent && !isDeleted && lFi > 0) {
                val name = decodeUdfOstaCS0(dirBytes, nameStart, lFi).replace('\\', '/')
                if (name.isNotEmpty() && !name.contains("..") && !name.contains('/')) {
                    val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (isDir) {
                        traverseUdfDirectory(partitionStart, childIcbBlock, fullPath, visited, depth + 1)
                    } else {
                        val fileIcb = readUdfIcb(partitionStart, childIcbBlock)
                        if (fileIcb != null) {
                            fileMap[fullPath] = IsoFileEntry(
                                path = fullPath,
                                size = fileIcb.infoLength,
                                extents = fileIcb.extents,
                                inlinePayload = fileIcb.inlineBytes
                            )
                            displayEntries.add("$fullPath (${fileIcb.infoLength} B)")
                        }
                    }
                }
            }
            pos = nextPos
        }
    }

    private fun traverseIsoDirectory(
        dirLba: Long,
        dirSize: Long,
        parentPath: String,
        isJoliet: Boolean,
        visitedLbas: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 16 || dirSize <= 0L || dirSize > 4 * 1024 * 1024L || !visitedLbas.add(dirLba) || fileMap.size >= 10_000) return
        val safeAlloc = minOf(dirSize, source.size.coerceAtLeast(2048L)).toInt()
        if (safeAlloc <= 0) return
        val buf = ByteBuffer.allocateDirect(safeAlloc).order(ByteOrder.LITTLE_ENDIAN)
        val r = source.readAt(dirLba * SECTOR_SIZE, buf)
        if (r <= 0) return
        val bytes = ByteArray(r)
        buf.flip()
        buf.get(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var pos = 0
        while (pos < bytes.size && fileMap.size < 10_000) {
            val recLen = bytes[pos].toInt() and 0xFF
            if (recLen == 0) {
                val nextSectorPos = ((pos / 2048) + 1) * 2048
                if (nextSectorPos <= pos) break
                pos = nextSectorPos
                continue
            }
            if (pos + recLen > bytes.size || recLen < 34) break

            val extLba = bb.getInt(pos + 2).toLong() and 0xFFFFFFFFL
            val dataLen = bb.getInt(pos + 10).toLong() and 0xFFFFFFFFL
            val flags = bytes[pos + 25].toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            val nameLen = bytes[pos + 32].toInt() and 0xFF

            if (nameLen > 0 && pos + 33 + nameLen <= pos + recLen) {
                val firstNameByte = bytes[pos + 33].toInt() and 0xFF
                if (!(nameLen == 1 && (firstNameByte == 0 || firstNameByte == 1))) {
                    val rawName = if (isJoliet && nameLen >= 2) {
                        String(bytes, pos + 33, nameLen - (nameLen % 2), StandardCharsets.UTF_16BE)
                    } else {
                        String(bytes, pos + 33, nameLen, StandardCharsets.UTF_8)
                    }
                    val cleanName = rawName
                        .replace('\u0000', ' ')
                        .substringBefore(';')
                        .trim()
                        .replace('\\', '/')
                    if (cleanName.isNotEmpty() && !cleanName.contains("..") && !cleanName.contains('/')) {
                        val fullPath = if (parentPath.isEmpty()) cleanName else "$parentPath/$cleanName"
                        if (isDir) {
                            traverseIsoDirectory(extLba, dataLen, fullPath, isJoliet, visitedLbas, depth + 1)
                        } else {
                            val existing = fileMap[fullPath]
                            val newExtent = UdfExtent(extLba * SECTOR_SIZE, dataLen)
                            if (existing != null && (flags and 0x80) != 0) {
                                val combinedExtents = existing.extents + newExtent
                                val combinedSize = existing.size + dataLen
                                fileMap[fullPath] = existing.copy(size = combinedSize, extents = combinedExtents)
                            } else {
                                fileMap[fullPath] = IsoFileEntry(
                                    path = fullPath,
                                    size = dataLen,
                                    extents = listOf(newExtent)
                                )
                                displayEntries.add("$fullPath ($dataLen B)")
                            }
                        }
                    }
                }
            }
            pos += recLen
        }
    }

    override val pageCount: Int get() = ((displayEntries.size + 24) / 25).coerceAtLeast(1)

    override fun openVirtualFile(entryPath: String): ParcelFileDescriptor? {
        if (isClosed) return null
        val cleanPath = stripDisplaySizeSuffix(entryPath).replace('\u0000', ' ').trim().trimStart('/')
        if (cleanPath.contains("..")) return null
        val entry = fileMap[cleanPath]
            ?: fileMap.entries.firstOrNull { it.key.equals(cleanPath, ignoreCase = true) }?.value
            ?: return null
        if (entry.size < 0L || entry.size > MAX_EXTRACT_BYTES) return null

        var tempFile: File? = null
        return try {
            val candidateDirs = listOfNotNull(
                System.getProperty("omnireader.cache.dir")?.let { File(it) },
                System.getProperty("java.io.tmpdir")?.let { File(it) },
                File("/data/data/me.hlebkukleuski.fullfilestudio/cache"),
                File("/data/data/me.hlebkukleuski.fullfilestudio.sitedl/cache")
            )
            var targetDir: File? = null
            for (dir in candidateDirs) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (dir.exists() && dir.canWrite()) {
                        targetDir = dir
                        break
                    }
                } catch (_: Throwable) {}
            }
            tempFile = if (targetDir != null) File.createTempFile("iso_entry_", ".tmp", targetDir) else File.createTempFile("iso_entry_", ".tmp")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { fos ->
                if (entry.inlinePayload != null) {
                    fos.write(entry.inlinePayload)
                } else {
                    val chunkBuf = ByteBuffer.allocateDirect(64 * 1024)
                    val byteArr = ByteArray(64 * 1024)
                    var totalRemaining = entry.size

                    for (extent in entry.extents) {
                        if (totalRemaining <= 0L) break
                        var extRemaining = minOf(extent.byteLength, totalRemaining)
                        var currentOffset = extent.byteOffset

                        while (extRemaining > 0L) {
                            val toRead = minOf(extRemaining, 64 * 1024L).toInt()
                            chunkBuf.clear()
                            chunkBuf.limit(toRead)
                            val r = source.readAt(currentOffset, chunkBuf)
                            if (r <= 0) break
                            chunkBuf.flip()
                            chunkBuf.get(byteArr, 0, r)
                            fos.write(byteArr, 0, r)
                            currentOffset += r
                            extRemaining -= r
                            totalRemaining -= r
                        }
                    }
                }
                fos.flush()
            }
            synchronized(this) {
                if (isClosed) {
                    tempFile.delete()
                    return null
                }
                tempExtractedFiles.add(tempFile)
            }
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            try { tempFile?.delete() } catch (_: Throwable) {}
            null
        }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPage = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) Color.WHITE else Color.rgb(0x12, 0x12, 0x12))
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val paint = Paint().apply {
            color = if (isLight) Color.BLACK else Color.WHITE
            textSize = 26f * zoom
            isAntiAlias = true
        }
        val start = (cleanPage * 25).coerceIn(0, displayEntries.size)
        val end = (start + 25).coerceAtMost(displayEntries.size)
        var y = 45f * zoom
        for (i in start until end) {
            canvas.drawText(displayEntries[i], 30f - tileX, y - tileY, paint)
            y += 38f * zoom
        }
    }

    @Synchronized
    override fun close() {
        val filesToDelete: List<File>
        synchronized(this) {
            if (isClosed) return
            isClosed = true
            filesToDelete = tempExtractedFiles.toList()
            tempExtractedFiles.clear()
        }
        source.close()
        try { pfd.close() } catch (_: Exception) {}
        filesToDelete.forEach { file ->
            try { file.delete() } catch (_: Throwable) {}
        }
    }
}
