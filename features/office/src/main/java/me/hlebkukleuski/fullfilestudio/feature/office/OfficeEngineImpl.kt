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

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import androidx.core.graphics.PathParser
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileEngineProvider
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.impl.CleanRoomLegacyAndIsoEngine
import com.omnireader.core.engine.impl.DocumentRendererEngineImpl
import com.omnireader.core.engine.impl.EbookTextSession
import com.omnireader.core.engine.impl.EmbeddedImageScanner
import com.omnireader.core.engine.impl.OfficeFonts
import com.omnireader.core.engine.impl.ChartCellResolver
import com.omnireader.core.engine.impl.DmlCellAnchor
import com.omnireader.core.engine.impl.DmlDrawings
import com.omnireader.core.engine.impl.DmlNode
import com.omnireader.core.engine.impl.DmlPackage
import com.omnireader.core.engine.impl.DmlPresentation
import com.omnireader.core.engine.impl.DmlSlideRenderer
import com.omnireader.core.engine.impl.ExcelNumberFormat
import com.omnireader.core.engine.impl.SheetBorder
import com.omnireader.core.engine.impl.SheetCellFlags
import com.omnireader.core.engine.impl.SheetCellStyle
import com.omnireader.core.engine.impl.SheetFill
import com.omnireader.core.engine.impl.CfBlock
import com.omnireader.core.engine.impl.BiffCf
import com.omnireader.core.engine.impl.ExcelFormula
import com.omnireader.core.engine.impl.FormulaError
import com.omnireader.core.engine.impl.FormulaWorkbook
import com.omnireader.core.engine.impl.CfCells
import com.omnireader.core.engine.impl.CfRule
import com.omnireader.core.engine.impl.CfStyle
import com.omnireader.core.engine.impl.CfValueObject
import com.omnireader.core.engine.impl.SheetConditionalFormats
import com.omnireader.core.engine.impl.SpreadsheetGridSession
import com.omnireader.core.engine.impl.PlainTextSession
import com.omnireader.core.engine.impl.TiffCleanRoomDecoder
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
private const val ANIM_STEP_SHIFT = 20
private const val PAGE_INDEX_MASK = 0x000FFFFF
private val DARK_PRESENTATION_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
private val LIGHT_PRESENTATION_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)



internal fun resolveDescriptorSize(fd: FileDescriptor): Long {
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

internal fun isEncryptedOle2Container(fd: FileDescriptor): Boolean {
    return try {
        val header = ByteBuffer.allocateDirect(8)
        val r = Os.pread(fd, header, 0L)
        if (r >= 4) {
            val b0 = header.get(0).toInt() and 0xFF
            val b1 = header.get(1).toInt() and 0xFF
            val b2 = header.get(2).toInt() and 0xFF
            val b3 = header.get(3).toInt() and 0xFF
            b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0
        } else false
    } catch (_: Throwable) {
        false
    }
}

internal object DrawingMlColorUtils {

    fun applyTransforms(
        baseColor: Int,
        lumMod: Int = 100_000,
        lumOff: Int = 0,
        tint: Int = 0,
        shade: Int = 0,
        alpha: Int = 100_000
    ): Int {
        var r = Color.red(baseColor) / 255f
        var g = Color.green(baseColor) / 255f
        var b = Color.blue(baseColor) / 255f

        if (lumMod != 100_000 || lumOff != 0) {
            val hsl = FloatArray(3)
            rgbToHsl(r, g, b, hsl)
            val lMod = (lumMod / 100_000f).coerceIn(0f, 2f)
            val lOff = (lumOff / 100_000f).coerceIn(-1f, 1f)
            hsl[2] = (hsl[2] * lMod + lOff).coerceIn(0f, 1f)
            val rgb = hslToRgb(hsl[0], hsl[1], hsl[2])
            r = rgb[0]
            g = rgb[1]
            b = rgb[2]
        }

        if (tint in 1..100_000) {
            val t = (tint / 100_000f).coerceIn(0f, 1f)
            r = 1f - (1f - r) * t
            g = 1f - (1f - g) * t
            b = 1f - (1f - b) * t
        }

        if (shade in 1..100_000) {
            val s = (shade / 100_000f).coerceIn(0f, 1f)
            r *= s
            g *= s
            b *= s
        }

        val outA = ((Color.alpha(baseColor) / 255f) * (alpha / 100_000f).coerceIn(0f, 1f) * 255f)
            .roundToInt().coerceIn(0, 255)
        val outR = (r * 255f).roundToInt().coerceIn(0, 255)
        val outG = (g * 255f).roundToInt().coerceIn(0, 255)
        val outB = (b * 255f).roundToInt().coerceIn(0, 255)
        return Color.argb(outA, outR, outG, outB)
    }

    fun applyExcelTint(baseColor: Int, tintDouble: Double): Int {
        if (!tintDouble.isFinite() || abs(tintDouble) < 1e-4) return baseColor
        val r = Color.red(baseColor) / 255f
        val g = Color.green(baseColor) / 255f
        val b = Color.blue(baseColor) / 255f
        val hsl = FloatArray(3)
        rgbToHsl(r, g, b, hsl)
        val lum = hsl[2]
        hsl[2] = if (tintDouble < 0.0) {
            (lum * (1.0 + tintDouble)).toFloat().coerceIn(0f, 1f)
        } else {
            (lum * (1.0 - tintDouble) + tintDouble).toFloat().coerceIn(0f, 1f)
        }
        val rgb = hslToRgb(hsl[0], hsl[1], hsl[2])
        return Color.rgb(
            (rgb[0] * 255f).roundToInt().coerceIn(0, 255),
            (rgb[1] * 255f).roundToInt().coerceIn(0, 255),
            (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
        )
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float, outHsl: FloatArray) {
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val d = maxC - minC
        val l = (maxC + minC) / 2f
        if (d < 1e-5f) {
            outHsl[0] = 0f
            outHsl[1] = 0f
            outHsl[2] = l
            return
        }
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        outHsl[0] = h
        outHsl[1] = s
        outHsl[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s < 1e-5f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue2rgb(tRaw: Float): Float {
            var t = tRaw
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            if (t < 1f / 6f) return p + (q - p) * 6f * t
            if (t < 1f / 2f) return q
            if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f
            return p
        }
        return floatArrayOf(
            hue2rgb(h + 1f / 3f),
            hue2rgb(h),
            hue2rgb(h - 1f / 3f)
        )
    }
}

internal class OfficePreadInputStream(
    private val fd: FileDescriptor,
    private val totalSize: Long
) : InputStream() {
    private var pos = 0L
    private val singleByte = ByteArray(1)
    private val reusableDirect = ByteBuffer.allocateDirect(32 * 1024)

    override fun read(): Int {
        val r = read(singleByte, 0, 1)
        return if (r == 1) (singleByte[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (totalSize > 0L && pos >= totalSize) return -1
        val maxRemaining = if (totalSize > 0L) (totalSize - pos) else len.toLong()
        val toRead = minOf(len.toLong(), maxRemaining, reusableDirect.capacity().toLong()).toInt()
        if (toRead <= 0) return -1

        reusableDirect.clear()
        reusableDirect.limit(toRead)
        val r = try {
            Os.pread(fd, reusableDirect, pos)
        } catch (_: Throwable) {
            -1
        }
        if (r <= 0) return -1
        reusableDirect.flip()
        reusableDirect.get(b, off, r)
        pos += r
        return r
    }
}

internal fun openZipFromPfdViaPread(pfd: ParcelFileDescriptor): ZipInputStream {
    val fileSize = resolveDescriptorSize(pfd.fileDescriptor)
    return ZipInputStream(BufferedInputStream(OfficePreadInputStream(pfd.fileDescriptor, fileSize), 32 * 1024))
}

internal object OfficeZipContainerLoader {

    private const val MAX_SUSPICIOUS_COMPRESSION_RATIO = 150L
    private const val RATIO_CHECK_MIN_UNPACKED_BYTES = 16L * 1024L * 1024L

    fun normalizeZipPath(rawPath: String): String {
        val slashNormalized = rawPath.replace('\\', '/').trim()
        val parts = slashNormalized.split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            } else {
                out.add(part)
            }
        }
        return out.joinToString("/")
    }

    private fun isStructuralXmlOrRels(normName: String): Boolean {
        val lower = normName.lowercase(Locale.ROOT)
        return lower.endsWith(".xml") || lower.endsWith(".rels") || lower.endsWith(".bin")
    }

    fun readRawBytes(pfd: ParcelFileDescriptor, maxBytes: Long = 128L * 1024L * 1024L): ByteArray {
        val fd = pfd.fileDescriptor
        val size = resolveDescriptorSize(fd)
        val cap = if (size > 0L) minOf(size, maxBytes).toInt() else minOf(32L * 1024L * 1024L, maxBytes).toInt()
        val buf = ByteBuffer.allocateDirect(cap)
        var pos = 0L
        while (buf.hasRemaining()) {
            val r = try { Os.pread(fd, buf, pos) } catch (_: Throwable) { -1 }
            if (r <= 0) break
            pos += r
        }
        buf.flip()
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    fun readZipEntriesFromBytes(
        zipBytes: ByteArray,
        maxTotalBytes: Long,
        entryFilter: (String) -> Boolean
    ): Map<String, ByteArray> {
        val result = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val compSize = zipBytes.size.toLong().coerceAtLeast(1L)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var totalBytes = 0L
            val buf = ByteArray(16 * 1024)
            while (true) {
                val entry = try {
                    zis.nextEntry
                } catch (ze: java.util.zip.ZipException) {
                    if (ze.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                    }
                    null
                } catch (_: Throwable) { null } ?: break
                val norm = normalizeZipPath(entry.name)
                if (!entry.isDirectory && norm.isNotEmpty() && entryFilter(norm)) {
                    val bos = ByteArrayOutputStream()
                    var r: Int
                    while (zis.read(buf).also { r = it } > 0) {
                        totalBytes += r
                        if (totalBytes > RATIO_CHECK_MIN_UNPACKED_BYTES && (totalBytes / compSize) > MAX_SUSPICIOUS_COMPRESSION_RATIO) {
                            throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                        }
                        if (totalBytes > maxTotalBytes) break
                        bos.write(buf, 0, r)
                    }
                    result[norm] = bos.toByteArray()
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
                if (totalBytes > maxTotalBytes) break
            }
        }
        return result
    }

    fun readZipEntries(
        pfd: ParcelFileDescriptor,
        maxTotalBytes: Long,
        entryFilter: (String) -> Boolean
    ): Map<String, ByteArray> {
        val result = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val fd = pfd.fileDescriptor
        val fileSize = resolveDescriptorSize(fd)

        if (fileSize >= 22L && tryReadViaCentralDirectory(fd, fileSize, maxTotalBytes, entryFilter, result)) {
            if (result.isNotEmpty()) return result
        }

        try {
            openZipFromPfdViaPread(pfd).use { zis ->
                var totalBytes = 0L
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = try {
                        zis.nextEntry
                    } catch (ze: java.util.zip.ZipException) {
                        if (ze.message?.contains("encrypted", ignoreCase = true) == true) {
                            throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                        }
                        break
                    } catch (_: Throwable) {
                        break
                    } ?: break

                    val normName = normalizeZipPath(entry.name)
                    if (!entry.isDirectory && normName.isNotEmpty() && entryFilter(normName)) {
                        val isStructural = isStructuralXmlOrRels(normName)
                        if (!isStructural && totalBytes >= maxTotalBytes) {
                            try { zis.closeEntry() } catch (_: Throwable) {}
                            continue
                        }
                        try {
                            val bos = ByteArrayOutputStream()
                            var entryUnpacked = 0L
                            val entryCompHint = entry.compressedSize.takeIf { it > 0L } ?: fileSize.coerceAtLeast(1L)
                            var r: Int
                            while (zis.read(buf).also { r = it } > 0) {
                                entryUnpacked += r
                                totalBytes += r
                                if (entryUnpacked > RATIO_CHECK_MIN_UNPACKED_BYTES &&
                                    entryUnpacked / entryCompHint.coerceAtLeast(1L) > MAX_SUSPICIOUS_COMPRESSION_RATIO
                                ) {
                                    throw SecurityException("Превышен лимит распаковки контейнера OpenXML (защита от Zip-бомб).")
                                }
                                if (totalBytes > maxTotalBytes) {
                                    if (fileSize in 1L..(totalBytes / MAX_SUSPICIOUS_COMPRESSION_RATIO)) {
                                        throw SecurityException("Превышен лимит распаковки контейнера OpenXML (защита от Zip-бомб).")
                                    }
                                    break
                                }
                                bos.write(buf, 0, r)
                            }
                            if (bos.size() > 0) {
                                result[normName] = bos.toByteArray()
                            }
                        } catch (sec: SecurityException) {
                            throw sec
                        } catch (_: Throwable) {
                        }
                    }
                    try { zis.closeEntry() } catch (_: Throwable) {}
                }
            }
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {}

        return result
    }

    private fun tryReadViaCentralDirectory(
        fd: FileDescriptor,
        fileSize: Long,
        maxTotalBytes: Long,
        entryFilter: (String) -> Boolean,
        outMap: MutableMap<String, ByteArray>
    ): Boolean {
        return try {
            val tailSize = min(fileSize, 65557L).toInt()
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

            data class CdCandidate(
                val normName: String,
                val method: Int,
                val compSize: Long,
                val uncompSize: Long,
                val localHeaderOffset: Long,
                val isStructural: Boolean
            )

            val candidates = ArrayList<CdCandidate>()
            var pos = 0

            while (pos + 46 <= cdRead) {
                val sig = cdBuf.getInt(pos)
                if (sig != 0x02014b50) break

                val flags = cdBuf.getShort(pos + 8).toInt() and 0xFFFF
                val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
                val compSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                val uncompSize = cdBuf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
                val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
                val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
                val localHeaderOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

                val nextPos = pos + 46 + nameLen + extraLen + commentLen
                if (pos + 46 + nameLen > cdRead) break

                if ((flags and 0x01) != 0) {
                    throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                }

                val nameBytes = ByteArray(nameLen)
                cdBuf.position(pos + 46)
                cdBuf.get(nameBytes)
                val rawName = String(nameBytes, StandardCharsets.UTF_8)
                val normName = normalizeZipPath(rawName)

                if (!rawName.endsWith("/") && normName.isNotEmpty() && entryFilter(normName)) {
                    if (uncompSize != 0xFFFFFFFFL && uncompSize > RATIO_CHECK_MIN_UNPACKED_BYTES &&
                        compSize > 0L && (uncompSize / compSize) > MAX_SUSPICIOUS_COMPRESSION_RATIO
                    ) {
                        throw SecurityException("Превышен лимит распаковки контейнера OpenXML (защита от Zip-бомб).")
                    }
                    candidates.add(
                        CdCandidate(
                            normName = normName,
                            method = method,
                            compSize = compSize,
                            uncompSize = uncompSize,
                            localHeaderOffset = localHeaderOffset,
                            isStructural = isStructuralXmlOrRels(normName)
                        )
                    )
                }
                pos = nextPos
            }

            val orderedCandidates = candidates.sortedByDescending { it.isStructural }
            var totalUnpacked = 0L
            val localHeaderBuf = ByteBuffer.allocateDirect(30).order(ByteOrder.LITTLE_ENDIAN)

            for (cand in orderedCandidates) {
                val maxSingleEntryBytes = if (cand.isStructural) maxTotalBytes else minOf(maxTotalBytes / 2, 32 * 1024 * 1024L)
                if (cand.compSize !in 0L..maxSingleEntryBytes || cand.localHeaderOffset + 30L > fileSize) continue
                if (!cand.isStructural && totalUnpacked >= maxTotalBytes) continue
                if (cand.uncompSize != 0xFFFFFFFFL && totalUnpacked + cand.uncompSize > maxTotalBytes + 32 * 1024 * 1024L) {
                    if (!cand.isStructural) continue
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
                            val entryBudget = (maxTotalBytes + 32 * 1024 * 1024L - totalUnpacked).coerceAtLeast(8 * 1024 * 1024L)
                            val entryData = when (cand.method) {
                                0 -> compArray
                                8 -> inflateRawBytes(compArray, entryBudget)
                                else -> null
                            }
                            if (entryData != null) {
                                totalUnpacked += entryData.size
                                outMap[cand.normName] = entryData
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

    private fun inflateRawBytes(compressed: ByteArray, maxAllowed: Long): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            val compLen = compressed.size.coerceAtLeast(1).toLong()
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    total += count
                    if (total > RATIO_CHECK_MIN_UNPACKED_BYTES && (total / compLen) > MAX_SUSPICIOUS_COMPRESSION_RATIO) {
                        throw SecurityException("Превышен лимит распаковки ZIP-записи (защита от Zip-бомб).")
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
}

class OfficeEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return ext in setOf(
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp",
            "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf", "one",
            "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc"
        )
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        return createSessionForExtension(fd, "")
    }

    fun createSessionForExtension(
        fd: FileDescriptor,
        extension: String,
        password: String? = null
    ): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val header = ByteBuffer.allocateDirect(4096)
        val fileSize = resolveDescriptorSize(fd)
        try {
            Os.pread(fd, header, 0L)
            header.flip()
        } catch (_: Exception) {
            header.limit(0)
        }
        val detected = FileSignatureDetector.resolveFormat(fd, header, ext, fileSize)

        when (detected) {
            DetectedFormat.PPTX -> return PptxPresentationSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.ODP -> return OdpPresentationSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.XLSX -> return OoxmlSpreadsheetSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.ODS -> return OdsSpreadsheetSession(ParcelFileDescriptor.dup(fd), password)
            DetectedFormat.DOCX, DetectedFormat.ODT, DetectedFormat.EPUB, DetectedFormat.FB2,
            DetectedFormat.AZW3, DetectedFormat.MOBI, DetectedFormat.KINDLE_DRM, DetectedFormat.KFX -> {
                return DocumentRendererEngineImpl().createSessionForFormat(fd, detected, ext, password)
            }
            DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT, DetectedFormat.RTF, DetectedFormat.ONE -> {
                return CleanRoomLegacyAndIsoEngine.createSession(fd, detected, ext, password)
            }
            else -> {}
        }

        if (ext in setOf("csv", "tsv") || (ext in setOf("xls", "xlt") && detected == DetectedFormat.PLAINTEXT)) {
            return Rfc4180CsvTsvSession(ParcelFileDescriptor.dup(fd), if (ext == "tsv") "tsv" else "csv")
        }

        if (ext in setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "one")) {
            return CleanRoomLegacyAndIsoEngine.createSession(fd, detected, ext, password)
        }

        val pfd = ParcelFileDescriptor.dup(fd)
        return when (ext) {
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> PptxPresentationSession(pfd, password)
            "odp", "otp" -> OdpPresentationSession(pfd, password)
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> OoxmlSpreadsheetSession(pfd, password)
            "ods", "ots" -> OdsSpreadsheetSession(pfd, password)
            else -> DocumentRendererEngineImpl().createSessionForFormat(pfd.fileDescriptor, detected, ext, password).also {
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }
}

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
                val decZip = com.omnireader.core.engine.impl.CleanRoomOfficeCrypto.decryptOoxmlBytes(raw, password)
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

class OdpPresentationSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    private var document: CleanRoomOdpEngine.OdpDocument? = null
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
            val entries = if (CleanRoomOdpEngine.isEncryptedOdf(rawEntries)) {
                if (password.isNullOrEmpty()) {
                    lastErrorMsg = "PASSWORD_REQUIRED: ODP presentation is encrypted or password protected."
                    return
                }
                com.omnireader.core.engine.impl.CleanRoomOfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                    lastErrorMsg = "PASSWORD_REQUIRED: Incorrect password for ODP presentation."
                    return
                }
            } else {
                rawEntries
            }
            val parsed = CleanRoomOdpEngine.parse(entries)
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
        CleanRoomOdpEngine.drawSlide(canvas, doc, slide, slideRect, bitmapCache)

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

class OoxmlSpreadsheetSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : SpreadsheetGridSession() {
    companion object {
        private const val MAX_SHEET_ROWS = 10_000
        private const val MAX_SHEET_COLS = 256

        private val INDEXED_EXCEL_COLORS = mapOf(
            0 to Color.rgb(0, 0, 0),
            1 to Color.rgb(255, 255, 255),
            2 to Color.rgb(255, 0, 0),
            3 to Color.rgb(0, 255, 0),
            4 to Color.rgb(0, 0, 255),
            5 to Color.rgb(255, 255, 0),
            6 to Color.rgb(255, 0, 255),
            7 to Color.rgb(0, 255, 255),
            8 to Color.rgb(0, 0, 0),
            9 to Color.rgb(255, 255, 255),
            10 to Color.rgb(255, 0, 0),
            11 to Color.rgb(0, 255, 0),
            12 to Color.rgb(0, 0, 255),
            13 to Color.rgb(255, 255, 0),
            14 to Color.rgb(255, 0, 255),
            15 to Color.rgb(0, 255, 255),
            16 to Color.rgb(128, 0, 0),
            17 to Color.rgb(0, 128, 0),
            18 to Color.rgb(0, 0, 128),
            19 to Color.rgb(128, 128, 0),
            20 to Color.rgb(128, 0, 128),
            21 to Color.rgb(0, 128, 128),
            22 to Color.rgb(192, 192, 192),
            23 to Color.rgb(128, 128, 128),
            24 to Color.rgb(153, 153, 255),
            25 to Color.rgb(153, 51, 102),
            26 to Color.rgb(255, 255, 204),
            27 to Color.rgb(204, 255, 255),
            28 to Color.rgb(102, 0, 102),
            29 to Color.rgb(255, 128, 128),
            30 to Color.rgb(0, 102, 204),
            31 to Color.rgb(204, 204, 255),
            40 to Color.rgb(0, 204, 255),
            41 to Color.rgb(204, 255, 255),
            42 to Color.rgb(204, 255, 204),
            43 to Color.rgb(255, 255, 153),
            44 to Color.rgb(153, 204, 255),
            45 to Color.rgb(255, 153, 204),
            46 to Color.rgb(204, 153, 255),
            47 to Color.rgb(255, 204, 153),
            48 to Color.rgb(51, 102, 255),
            49 to Color.rgb(51, 204, 204),
            50 to Color.rgb(153, 204, 0),
            51 to Color.rgb(255, 204, 0),
            52 to Color.rgb(255, 153, 0),
            53 to Color.rgb(255, 102, 0),
            54 to Color.rgb(102, 102, 153),
            55 to Color.rgb(150, 150, 150),
            56 to Color.rgb(0, 51, 102),
            57 to Color.rgb(51, 153, 102),
            58 to Color.rgb(0, 51, 0),
            59 to Color.rgb(51, 51, 0),
            60 to Color.rgb(153, 51, 0),
            61 to Color.rgb(153, 51, 102),
            62 to Color.rgb(51, 51, 153),
            63 to Color.rgb(51, 51, 51)
        )
    }

    private data class XfStyleSpec(
        val numFmtId: Int,
        val formatCode: String?,
        val style: SheetCellStyle?
    )

    private val xfStyles = ArrayList<XfStyleSpec>()
    private val dxfStyles = ArrayList<CfStyle>()
    private var cfNames: Map<String, String> = emptyMap()
    private var defaultFontName: String? = null
    private var defaultFontSize = 11f
    private val indexedPalette = HashMap<Int, Int>(INDEXED_EXCEL_COLORS)
    private val themePalette = intArrayOf(
        Color.rgb(255, 255, 255), // 0: lt1
        Color.rgb(0, 0, 0),       // 1: dk1
        Color.rgb(238, 236, 225), // 2: lt2
        Color.rgb(31, 73, 125),   // 3: dk2
        Color.rgb(79, 129, 189),  // 4: accent1
        Color.rgb(192, 80, 77),   // 5: accent2
        Color.rgb(155, 187, 89),  // 6: accent3
        Color.rgb(128, 100, 162), // 7: accent4
        Color.rgb(75, 172, 198),  // 8: accent5
        Color.rgb(247, 150, 70)   // 9: accent6
    )
    private var use1904DateSystem = false

    init {
        parseXlsx()
    }

    private fun parseExcelThemeXml(themeBytes: ByteArray) {
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(themeBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inClrScheme = false
            var slotIdx = -1
            val slotOrder = mapOf(
                "lt1" to 0, "dk1" to 1, "lt2" to 2, "dk2" to 3,
                "accent1" to 4, "accent2" to 5, "accent3" to 6,
                "accent4" to 7, "accent5" to 8, "accent6" to 9
            )
            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (local == "clrscheme") inClrScheme = true
                        else if (inClrScheme && local in slotOrder) slotIdx = slotOrder[local] ?: -1
                        else if (inClrScheme && slotIdx in themePalette.indices) {
                            val hex = if (local == "srgbclr") {
                                parser.getAttributeValue(null, "val")
                            } else if (local == "sysclr") {
                                parser.getAttributeValue(null, "lastClr") ?: parser.getAttributeValue(null, "val")
                            } else null
                            if (!hex.isNullOrBlank() && hex.length == 6) {
                                try { themePalette[slotIdx] = Color.parseColor("#$hex") } catch (_: Exception) {}
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (local == "clrscheme") inClrScheme = false
                        else if (local in slotOrder) slotIdx = -1
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
    }

    private fun nodeColor(n: DmlNode?): Int? {
        if (n == null || n.bool("auto") == true) return null
        var base: Int? = null
        val hex = n.attr("rgb")?.trim()
        if (hex != null && (hex.length == 8 || hex.length == 6)) base = try { Color.parseColor("#" + hex.takeLast(6)) } catch (_: Exception) { null }
        if (base == null) n.int("theme")?.let { if (it in themePalette.indices) base = themePalette[it] }
        if (base == null) n.int("indexed")?.let { base = indexedPalette[it] }
        val b = base ?: return null
        return DrawingMlColorUtils.applyExcelTint(b, n.attr("tint")?.toDoubleOrNull() ?: 0.0)
    }

    private class XlsxFont(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Int,
        val strike: Boolean,
        val color: Int?,
        val size: Float,
        val name: String?,
        val script: Int
    )

    private fun parseStylesXml(stylesBytes: ByteArray?) {
        val root = DmlNode.parse(stylesBytes) ?: return
        root.path("colors", "indexedColors")?.all("rgbColor")?.forEachIndexed { i, c ->
            val hex = c.attr("rgb")?.trim()
            if (hex != null && hex.length >= 6) try { indexedPalette[i] = Color.parseColor("#" + hex.takeLast(6)) } catch (_: Exception) {}
        }
        val customFormats = HashMap<Int, String>()
        root.child("numFmts")?.all("numFmt")?.forEach { n ->
            val id = n.int("numFmtId")
            val code = n.attr("formatCode")
            if (id != null && !code.isNullOrEmpty()) customFormats[id] = code
        }
        fun flag(n: DmlNode?): Boolean = n != null && (n.attr("val")?.let { it != "0" && !it.equals("false", true) } ?: true)
        val fonts = root.child("fonts")?.all("font").orEmpty().map { f ->
            val u = f.child("u")
            XlsxFont(
                bold = flag(f.child("b")),
                italic = flag(f.child("i")),
                underline = if (u == null) 0 else when (u.attr("val")?.lowercase(Locale.ROOT)) {
                    null, "single", "singleaccounting" -> 1
                    "double", "doubleaccounting" -> 2
                    else -> 0
                },
                strike = flag(f.child("strike")),
                color = nodeColor(f.child("color")),
                size = f.child("sz")?.attr("val")?.toFloatOrNull() ?: 0f,
                name = f.child("name")?.attr("val"),
                script = when (f.child("vertAlign")?.attr("val")) { "superscript" -> 1; "subscript" -> 2; else -> 0 }
            )
        }
        fonts.firstOrNull()?.let { f ->
            defaultFontName = f.name
            if (f.size > 0f) defaultFontSize = f.size
        }
        val fills = root.child("fills")?.all("fill").orEmpty().map { f ->
            val pf = f.child("patternFill")
            if (pf != null) {
                SheetCellStyle.patternFill(pf.attr("patternType") ?: "none", nodeColor(pf.child("fgColor")), nodeColor(pf.child("bgColor")))
            } else {
                val stops = f.child("gradientFill")?.all("stop").orEmpty().mapNotNull { nodeColor(it.child("color")) }
                if (stops.isEmpty()) null else SheetFill(stops.reduce { a, b -> SheetCellStyle.blend(a, b, 0.5f) })
            }
        }
        fun side(n: DmlNode?): SheetBorder? {
            if (n == null) return null
            val st = SheetBorder.ooxml(n.attr("style"))
            if (st == SheetBorder.NONE) return null
            return SheetBorder(st, nodeColor(n.child("color")))
        }
        val borders = root.child("borders")?.all("border").orEmpty().map { b ->
            val diag = side(b.child("diagonal"))
            arrayOf(
                side(b.child("left") ?: b.child("start")),
                side(b.child("right") ?: b.child("end")),
                side(b.child("top")),
                side(b.child("bottom")),
                if (b.bool("diagonalDown") == true) diag else null,
                if (b.bool("diagonalUp") == true) diag else null
            )
        }
        for (dxf in root.child("dxfs")?.all("dxf").orEmpty()) {
            val f = dxf.child("font")
            val pf = dxf.path("fill", "patternFill")
            val fill = if (pf != null) {
                val type = pf.attr("patternType")
                val fg = nodeColor(pf.child("fgColor"))
                val bg = nodeColor(pf.child("bgColor"))
                when {
                    type == null || type == "solid" -> (bg ?: fg)?.let { SheetFill(it) }
                    type == "none" -> null
                    else -> SheetCellStyle.patternFill(type, fg, bg)
                }
            } else null
            val u = f?.child("u")
            val bd = dxf.child("border")
            val code = dxf.child("numFmt")?.attr("formatCode")
            dxfStyles.add(
                CfStyle(
                    fill = fill,
                    color = nodeColor(f?.child("color")),
                    bold = f?.child("b")?.let { flag(it) },
                    italic = f?.child("i")?.let { flag(it) },
                    underline = u?.let { if (it.attr("val") == "none") 0 else if (it.attr("val")?.startsWith("double") == true) 2 else 1 },
                    strike = f?.child("strike")?.let { flag(it) },
                    left = side(bd?.child("left") ?: bd?.child("start")),
                    right = side(bd?.child("right") ?: bd?.child("end")),
                    top = side(bd?.child("top")),
                    bottom = side(bd?.child("bottom")),
                    numFmt = code ?: dxf.child("numFmt")?.int("numFmtId")?.let { ExcelNumberFormat.code(it, null) }
                )
            )
        }
        val baseFont = fonts.firstOrNull()
        for (xf in root.child("cellXfs")?.all("xf").orEmpty()) {
            if (xfStyles.size >= 65536) break
            val numFmtId = xf.int("numFmtId") ?: 0
            val font = fonts.getOrNull(xf.int("fontId") ?: 0)
            val fill = fills.getOrNull(xf.int("fillId") ?: 0)
            val bd = borders.getOrNull(xf.int("borderId") ?: 0)
            val al = xf.child("alignment")
            val st = SheetCellStyle(
                fill = fill?.bg,
                pattern = fill?.pattern ?: 0,
                patternColor = fill?.fg,
                textColor = font?.color,
                bold = font?.bold == true,
                italic = font?.italic == true,
                underline = font?.underline ?: 0,
                strike = font?.strike == true,
                fontSize = font?.size ?: 0f,
                fontName = font?.name,
                script = font?.script ?: 0,
                hAlign = SheetCellStyle.hAlign(al?.attr("horizontal")),
                vAlign = SheetCellStyle.vAlign(al?.attr("vertical")),
                wrap = al?.bool("wrapText") == true,
                indent = al?.int("indent")?.coerceIn(0, 250) ?: 0,
                rotation = al?.int("textRotation")?.coerceIn(0, 255) ?: 0,
                shrink = al?.bool("shrinkToFit") == true,
                left = bd?.get(0),
                right = bd?.get(1),
                top = bd?.get(2),
                bottom = bd?.get(3),
                diagonalDown = bd?.get(4),
                diagonalUp = bd?.get(5)
            )
            val plain = SheetCellStyle(textColor = baseFont?.color, fontSize = baseFont?.size ?: 0f, fontName = baseFont?.name)
            xfStyles.add(XfStyleSpec(numFmtId, customFormats[numFmtId], if (st == plain) null else st))
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

    private fun biff12Color(b: ByteArray, at: Int): Int? {
        if (at + 8 > b.size) return null
        val type = (b[at].toInt() and 0xFF) ushr 1
        val index = b[at + 1].toInt() and 0xFF
        val tint = ByteBuffer.wrap(b, at + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short / 32767.0
        val base = when (type) {
            1 -> indexedPalette[index]
            2 -> Color.rgb(b[at + 4].toInt() and 0xFF, b[at + 5].toInt() and 0xFF, b[at + 6].toInt() and 0xFF)
            3 -> themePalette.getOrNull(index)
            else -> null
        } ?: return null
        return DrawingMlColorUtils.applyExcelTint(base, tint)
    }

    private fun parseBiff12Styles(stylesBin: ByteArray?) {
        if (stylesBin == null) return
        val customFormats = HashMap<Int, String>()
        val bb = ByteBuffer.wrap(stylesBin).order(ByteOrder.LITTLE_ENDIAN)
        val fonts = ArrayList<SheetCellStyle>()
        val fills = ArrayList<SheetFill?>()
        val borders = ArrayList<Array<SheetBorder?>>()
        var pos = 0
        var inCellXfs = false

        while (pos < stylesBin.size && xfStyles.size < 16384) {
            val hdr = readBiff12RecordHeader(stylesBin, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x002C -> {
                    if (recLen >= 6) {
                        val fmtId = bb.getShort(dataStart).toInt() and 0xFFFF
                        val cch = bb.getInt(dataStart + 2)
                        if (cch in 1..1024 && dataStart + 6 + cch * 2 <= dataStart + recLen) {
                            customFormats[fmtId] = String(stylesBin, dataStart + 6, cch * 2, StandardCharsets.UTF_16LE)
                        }
                    }
                }
                0x002B -> if (recLen >= 21 && fonts.size < 4096) {
                    val height = (bb.getShort(dataStart).toInt() and 0xFFFF) / 20f
                    val grbit = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                    val bls = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                    val sss = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                    val uls = stylesBin[dataStart + 8].toInt() and 0xFF
                    val color = biff12Color(stylesBin, dataStart + 12)
                    var fname: String? = null
                    if (recLen >= 25) {
                        val cch = bb.getInt(dataStart + 21)
                        if (cch in 1..256 && dataStart + 25 + cch * 2 <= dataStart + recLen) fname = String(stylesBin, dataStart + 25, cch * 2, StandardCharsets.UTF_16LE)
                    }
                    fonts.add(
                        SheetCellStyle(
                            textColor = color,
                            bold = bls >= 0x02BC,
                            italic = (grbit and 0x02) != 0,
                            strike = (grbit and 0x08) != 0,
                            underline = when (uls) { 0x01, 0x21 -> 1; 0x02, 0x22 -> 2; else -> 0 },
                            fontSize = height,
                            fontName = fname,
                            script = if (sss in 1..2) sss else 0
                        )
                    )
                    if (fonts.size == 1) {
                        defaultFontName = fname
                        if (height > 0f) defaultFontSize = height
                    }
                }
                0x002D -> if (recLen >= 20 && fills.size < 4096) {
                    val fls = bb.getInt(dataStart)
                    val fg = biff12Color(stylesBin, dataStart + 4)
                    val bg = biff12Color(stylesBin, dataStart + 12)
                    fills.add(SheetCellStyle.biffPattern(fls, fg, bg))
                }
                0x002E -> if (recLen >= 51 && borders.size < 4096) {
                    val flags = stylesBin[dataStart].toInt() and 0xFF
                    fun side(at: Int): SheetBorder? {
                        val dg = stylesBin[at].toInt() and 0xFF
                        if (dg == 0) return null
                        return SheetBorder(dg.coerceIn(1, 13), biff12Color(stylesBin, at + 2))
                    }
                    val diag = side(dataStart + 41)
                    borders.add(
                        arrayOf(
                            side(dataStart + 21), side(dataStart + 31), side(dataStart + 1), side(dataStart + 11),
                            if ((flags and 0x01) != 0) diag else null, if ((flags and 0x02) != 0) diag else null
                        )
                    )
                }
                0x01FB -> if (recLen >= 6) {
                    val n = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                    var q = dataStart + 6
                    var fill: SheetFill? = null
                    var fg: Int? = null
                    var bg: Int? = null
                    var pattern = -1
                    var color: Int? = null
                    var bold: Boolean? = null
                    var italic: Boolean? = null
                    var underline: Int? = null
                    var strike: Boolean? = null
                    var numFmt: String? = null
                    val sides = arrayOfNulls<SheetBorder>(4)
                    for (k in 0 until n.coerceAtMost(64)) {
                        if (q + 4 > dataStart + recLen) break
                        val type = bb.getShort(q).toInt() and 0xFFFF
                        val cb = bb.getShort(q + 2).toInt() and 0xFFFF
                        if (cb < 4 || q + cb > dataStart + recLen) break
                        val d = q + 4
                        when (type) {
                            0x00 -> pattern = stylesBin[d].toInt() and 0xFF
                            0x01 -> fg = biff12Color(stylesBin, d)
                            0x02 -> bg = biff12Color(stylesBin, d)
                            0x05 -> color = biff12Color(stylesBin, d)
                            in 0x06..0x09 -> {
                                val dg = bb.getShort(d + 8).toInt() and 0xFFFF
                                val side = if (dg == 0) null else SheetBorder(dg.coerceIn(1, 13), biff12Color(stylesBin, d))
                                sides[when (type) { 0x06 -> 2; 0x07 -> 3; 0x08 -> 0; else -> 1 }] = side
                            }
                            0x19 -> bold = (bb.getShort(d).toInt() and 0xFFFF) >= 0x2BC
                            0x1A -> underline = when (bb.getShort(d).toInt() and 0xFFFF) { 0 -> 0; 2, 0x22 -> 2; else -> 1 }
                            0x1C -> italic = stylesBin[d].toInt() != 0
                            0x1D -> strike = stylesBin[d].toInt() != 0
                            0x26 -> if (cb >= 10) {
                                val cch = bb.getInt(d + 2)
                                if (cch in 1..1024 && d + 6 + cch * 2 <= q + cb) numFmt = String(stylesBin, d + 6, cch * 2, StandardCharsets.UTF_16LE)
                                else numFmt = ExcelNumberFormat.code(bb.getShort(d).toInt() and 0xFFFF, null)
                            }
                        }
                        q += cb
                    }
                    fill = when {
                        pattern > 1 -> SheetCellStyle.biffPattern(pattern, fg, bg)
                        pattern == 0 -> null
                        else -> (bg ?: fg)?.let { SheetFill(it) }
                    }
                    dxfStyles.add(CfStyle(fill, color, bold, italic, underline, strike, sides[0], sides[1], sides[2], sides[3], numFmt))
                }
                0x0269 -> inCellXfs = true
                0x026A -> inCellXfs = false
                0x002F -> {
                    if (inCellXfs && recLen >= 14) {
                        val numFmtId = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val font = fonts.getOrNull(bb.getShort(dataStart + 4).toInt() and 0xFFFF)
                        val fill = fills.getOrNull(bb.getShort(dataStart + 6).toInt() and 0xFFFF)
                        val bd = borders.getOrNull(bb.getShort(dataStart + 8).toInt() and 0xFFFF)
                        val trot = stylesBin[dataStart + 10].toInt() and 0xFF
                        val indent = stylesBin[dataStart + 11].toInt() and 0xFF
                        val fl = bb.getShort(dataStart + 12).toInt() and 0xFFFF
                        val st = (font ?: SheetCellStyle()).withFill(fill).copy(
                            hAlign = when (fl and 0x7) {
                                1 -> SheetCellStyle.H_LEFT
                                2 -> SheetCellStyle.H_CENTER
                                3 -> SheetCellStyle.H_RIGHT
                                4 -> SheetCellStyle.H_FILL
                                5 -> SheetCellStyle.H_JUSTIFY
                                6 -> SheetCellStyle.H_CENTER_CONTINUOUS
                                7 -> SheetCellStyle.H_DISTRIBUTED
                                else -> SheetCellStyle.H_GENERAL
                            },
                            vAlign = when ((fl ushr 3) and 0x7) {
                                0 -> SheetCellStyle.V_TOP
                                1 -> SheetCellStyle.V_CENTER
                                3 -> SheetCellStyle.V_JUSTIFY
                                4 -> SheetCellStyle.V_DISTRIBUTED
                                else -> SheetCellStyle.V_BOTTOM
                            },
                            wrap = (fl and 0x40) != 0,
                            shrink = (fl and 0x100) != 0,
                            rotation = trot,
                            indent = indent.coerceAtMost(250),
                            left = bd?.get(0), right = bd?.get(1), top = bd?.get(2), bottom = bd?.get(3),
                            diagonalDown = bd?.get(4), diagonalUp = bd?.get(5)
                        )
                        val plain = fonts.firstOrNull() ?: SheetCellStyle()
                        xfStyles.add(XfStyleSpec(numFmtId, customFormats[numFmtId], if (st == plain) null else st))
                    }
                }
            }
            pos = dataStart + recLen
        }
    }

    private fun parseBiff12SharedStrings(sstBytes: ByteArray): List<String> {
        val list = ArrayList<String>()
        val bb = ByteBuffer.wrap(sstBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < sstBytes.size && list.size < 200_000) {
            val hdr = readBiff12RecordHeader(sstBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            if (recId == 0x0013 && recLen >= 5) {
                val charCount = bb.getInt(dataStart + 1)
                if (charCount in 0..32768 && dataStart + 5 + charCount * 2 <= dataStart + recLen) {
                    val s = String(sstBytes, dataStart + 5, charCount * 2, StandardCharsets.UTF_16LE)
                    list.add(s)
                }
            }
            pos = dataStart + recLen
        }
        return list
    }

    private fun parseBiff12WorkbookSheetNames(wbBytes: ByteArray?): List<Pair<String, String>> {
        if (wbBytes == null) return emptyList()
        val names = ArrayList<Pair<String, String>>()
        val bb = ByteBuffer.wrap(wbBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < wbBytes.size && names.size < 128) {
            val hdr = readBiff12RecordHeader(wbBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            if (recId == 0x0099 && recLen >= 1) { // BrtWbProp
                val flags = wbBytes[dataStart].toInt() and 0xFF
                if ((flags and 0x01) != 0) use1904DateSystem = true
            } else if (recId == 0x009C && recLen >= 12) { // BrtBundleSh
                val rIdCch = bb.getInt(dataStart + 8)
                val nameLenPos = if (rIdCch in 0..256 && dataStart + 12 + rIdCch * 2 + 4 <= dataStart + recLen) {
                    dataStart + 12 + rIdCch * 2
                } else -1
                if (nameLenPos > 0) {
                    val nameCch = bb.getInt(nameLenPos)
                    val nameCharsStart = nameLenPos + 4
                    if (nameCch in 1..256 && nameCharsStart + nameCch * 2 <= dataStart + recLen) {
                        val rId = if (rIdCch in 1..256) String(wbBytes, dataStart + 12, rIdCch * 2, StandardCharsets.UTF_16LE) else ""
                        names.add(String(wbBytes, nameCharsStart, nameCch * 2, StandardCharsets.UTF_16LE) to rId)
                    }
                }
            }
            pos = dataStart + recLen
        }
        return names
    }

    private fun formatXlsbNumericValue(d: Double, styleRef: Int): ExcelNumberFormat.Result {
        val xf = xfStyles.getOrNull(styleRef)
        return ExcelNumberFormat.format(d, xf?.numFmtId ?: 0, xf?.formatCode, use1904DateSystem)
    }

    private fun parseBiff12Worksheet(
        sheetName: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): SheetModel {
        val rowsMap = TreeMap<Int, ArrayList<String>>()
        val colWidths = HashMap<Int, Float>()
        val colStyles = HashMap<Int, SheetCellStyle>()
        val rowHeights = HashMap<Int, Float>()
        val rowStyles = HashMap<Int, SheetCellStyle>()
        val styles = HashMap<Long, SheetCellStyle>()
        val flags = HashMap<Int, ByteArray>()
        val formatColors = HashMap<Long, Int>()
        val fills = HashMap<Long, Int>()
        val merges = ArrayList<MergedCellRegion>()
        val bb = ByteBuffer.wrap(sheetBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var currentRowIdx = -1
        var maxColSeen = -1
        var showGrid = true
        var defaultRow = Math.round(defaultFontSize * 1.364f * 4f) / 4f * PX_PER_PT
        val defaultCol = defaultExcelColumnWidthPx()

        fun putCellAt(r: Int, col: Int, value: String, styleRef: Int, kind: Int = 0, fmt: ExcelNumberFormat.Result? = null) {
            if (r !in 0 until MAX_SHEET_ROWS || col !in 0 until MAX_SHEET_COLS) return
            val rowList = rowsMap.getOrPut(r) { ArrayList() }
            while (rowList.size <= col) rowList.add("")
            rowList[col] = value
            if (col > maxColSeen) maxColSeen = col
            val key = packCoord(r, col)
            xfStyles.getOrNull(styleRef)?.style?.let { if (styles.size < 250_000) styles[key] = it }
            if (kind != 0 && value.isNotEmpty()) rowFlags(flags, r, col, kind)
            fmt?.color?.let { formatColors[key] = it }
            if (fmt != null && fmt.fillAt >= 0) fills[key] = (fmt.fillAt shl 16) or fmt.fillChar.code
        }

        fun putNumber(r: Int, col: Int, d: Double, styleRef: Int) {
            val res = formatXlsbNumericValue(d, styleRef)
            putCellAt(r, col, res.text, styleRef, SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0), res)
        }

        val cfBlocks = ArrayList<CfBlock>()
        var cfRanges: List<IntArray> = emptyList()
        var cfRules = ArrayList<CfRule>()
        var ruleHead: IntArray? = null
        var ruleFormulas: List<String?> = emptyList()
        var ruleText: String? = null
        val ruleCfvos = ArrayList<CfValueObject>()
        val ruleColors = ArrayList<Int>()
        var barMin = 0.1f
        var barMax = 0.9f
        var showValue = true
        var iconSetIdx = 0
        var iconFlags = 0
        val iconSets = listOf("3Arrows", "3ArrowsGray", "3Flags", "3TrafficLights1", "3TrafficLights2", "3Signs", "3Symbols", "3Symbols2", "4Arrows", "4ArrowsGray", "4RedToBlack", "4Rating", "4TrafficLights", "5Arrows", "5ArrowsGray", "5Rating", "5Quarters", "3Stars", "3Triangles", "5Boxes")

        while (pos < sheetBytes.size && rowsMap.size < MAX_SHEET_ROWS) {
            val hdr = readBiff12RecordHeader(sheetBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x01CD -> if (recLen >= 12) {
                    val n = bb.getInt(dataStart + 8).coerceIn(0, 512)
                    cfRanges = (0 until n).mapNotNull { k ->
                        val q = dataStart + 12 + k * 16
                        if (q + 16 > dataStart + recLen) null else intArrayOf(bb.getInt(q), bb.getInt(q + 4), bb.getInt(q + 8), bb.getInt(q + 12))
                    }
                    cfRules = ArrayList()
                }
                0x01CE -> if (cfRanges.isNotEmpty() && cfRules.isNotEmpty()) cfBlocks.add(CfBlock(cfRanges, cfRules))
                0x01CF -> if (recLen >= 46 && cfRanges.isNotEmpty()) {
                    val top = cfRanges.minOf { it[0] }
                    val left = cfRanges.minOf { it[2] }
                    ruleHead = intArrayOf(bb.getInt(dataStart), bb.getInt(dataStart + 4), bb.getInt(dataStart + 8), bb.getInt(dataStart + 12), bb.getInt(dataStart + 16), bb.getShort(dataStart + 28).toInt() and 0xFFFF)
                    val lens = intArrayOf(bb.getInt(dataStart + 30), bb.getInt(dataStart + 34), bb.getInt(dataStart + 38))
                    var q = dataStart + 42
                    val cch = bb.getInt(q)
                    ruleText = null
                    q += 4
                    if (cch in 0..32767 && q + cch * 2 <= dataStart + recLen) { ruleText = String(sheetBytes, q, cch * 2, StandardCharsets.UTF_16LE); q += cch * 2 }
                    val fs = ArrayList<String?>()
                    for (len in lens) {
                        if (len <= 0 || q + 4 > dataStart + recLen) { fs.add(null); continue }
                        val cce = bb.getInt(q)
                        val f = if (cce in 1..65535 && q + 4 + cce <= dataStart + recLen) BiffCf.formula12(sheetBytes, q + 4, q + 4 + cce, top, left) else null
                        q += 4 + cce.coerceAtLeast(0)
                        if (q + 4 <= dataStart + recLen) q += 4 + bb.getInt(q).coerceAtLeast(0)
                        fs.add(f)
                    }
                    ruleFormulas = fs
                    ruleCfvos.clear(); ruleColors.clear()
                    barMin = 0.1f; barMax = 0.9f; showValue = true; iconSetIdx = 0; iconFlags = 0
                }
                0x01D3 -> if (recLen >= 3) {
                    barMin = (sheetBytes[dataStart].toInt() and 0xFF) / 100f
                    barMax = (sheetBytes[dataStart + 1].toInt() and 0xFF) / 100f
                    showValue = sheetBytes[dataStart + 2].toInt() != 0
                }
                0x01D1 -> if (recLen >= 6) {
                    iconSetIdx = bb.getInt(dataStart)
                    iconFlags = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                }
                0x01D7 -> if (recLen >= 24) {
                    val t = bb.getInt(dataStart)
                    val num = bb.getDouble(dataStart + 4)
                    val gte = bb.getInt(dataStart + 16) != 0
                    val cbF = bb.getInt(dataStart + 20)
                    val f = if (cbF > 0 && dataStart + 28 + cbF <= dataStart + recLen) BiffCf.formula12(sheetBytes, dataStart + 28, dataStart + 28 + cbF, cfRanges.minOfOrNull { it[0] } ?: 0, cfRanges.minOfOrNull { it[2] } ?: 0) else null
                    ruleCfvos.add(CfValueObject(when (t) { 1 -> "num"; 2 -> "min"; 3 -> "max"; 4 -> "percent"; 5 -> "percentile"; else -> "formula" }, f ?: ExcelFormula.numberText(num), gte || t != 4 && t != 5 && t != 1))
                }
                0x0234 -> if (recLen >= 8 && ruleHead != null) biff12Color(sheetBytes, dataStart)?.let { ruleColors.add(it) }
                0x01D0 -> {
                    val h = ruleHead
                    if (h != null) {
                        biff12Rule(h, ruleFormulas, ruleText, ruleCfvos.toList(), ruleColors.toList(), barMin, barMax, showValue, iconSets.getOrElse(iconSetIdx) { "3TrafficLights1" }, iconFlags)?.let { cfRules.add(it) }
                    }
                    ruleHead = null
                }
                0x0089 -> if (recLen >= 2) {
                    showGrid = (bb.getShort(dataStart).toInt() and 0x0004) != 0
                }
                0x01E5 -> if (recLen >= 8) {
                    val miy = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                    if (miy > 0) defaultRow = miy / 20f * PX_PER_PT
                }
                0x003C -> {
                    if (recLen >= 18) {
                        val colFirst = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_COLS - 1)
                        val colLast = bb.getInt(dataStart + 4).coerceIn(colFirst, MAX_SHEET_COLS - 1)
                        val coldx = bb.getInt(dataStart + 8)
                        val ixfe = bb.getInt(dataStart + 12)
                        val hidden = (bb.getShort(dataStart + 16).toInt() and 0x0001) != 0
                        val px = if (hidden) 0f else SpreadsheetCoordinateUtils.excelCharWidthToPixels(coldx / 256f)
                        val cs = xfStyles.getOrNull(ixfe)?.style
                        for (c in colFirst..colLast) {
                            colWidths[c] = px
                            if (cs != null) colStyles[c] = cs
                        }
                    }
                }
                0x0000 -> {
                    if (recLen >= 4) {
                        currentRowIdx = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_ROWS - 1)
                        if (recLen >= 12) {
                            val ixfe = bb.getInt(dataStart + 4)
                            val miy = bb.getShort(dataStart + 8).toInt() and 0xFFFF
                            val fl = bb.getShort(dataStart + 10).toInt() and 0xFFFF
                            if ((fl and 0x1000) != 0) rowHeights[currentRowIdx] = 0f
                            else if (miy > 0) rowHeights[currentRowIdx] = miy / 20f * PX_PER_PT
                            if ((fl and 0x4000) != 0) xfStyles.getOrNull(ixfe)?.style?.let { rowStyles[currentRowIdx] = it }
                        }
                        rowsMap.getOrPut(currentRowIdx) { ArrayList() }
                    }
                }
                0x0001 -> if (recLen >= 8 && currentRowIdx >= 0) {
                    putCellAt(currentRowIdx, bb.getInt(dataStart), "", bb.getInt(dataStart + 4) and 0x00FFFFFF)
                }
                0x0002 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val rk = bb.getInt(dataStart + 8)
                        val isDiv100 = (rk and 1) != 0
                        val isInt = (rk and 2) != 0
                        val raw = if (isInt) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and -4L) shl 32)
                        val v = if (isDiv100) raw / 100.0 else raw
                        putNumber(currentRowIdx, col, v, styleRef)
                    }
                }
                0x0003, 0x000B -> if (recLen >= 9 && currentRowIdx >= 0) {
                    val col = bb.getInt(dataStart)
                    val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                    val code = sheetBytes[dataStart + 8].toInt() and 0xFF
                    val txt = when (code) {
                        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"
                        0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"; else -> "#ERR!"
                    }
                    putCellAt(currentRowIdx, col, txt, styleRef, SheetCellFlags.ERROR)
                }
                0x0004, 0x000A -> {
                    if (recLen >= 9 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val bVal = sheetBytes[dataStart + 8].toInt() != 0
                        putCellAt(currentRowIdx, col, if (bVal) "TRUE" else "FALSE", styleRef, SheetCellFlags.BOOL)
                    }
                }
                0x0005, 0x0009 -> {
                    if (recLen >= 16 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val d = bb.getDouble(dataStart + 8)
                        if (d.isFinite()) putNumber(currentRowIdx, col, d, styleRef)
                    }
                }
                0x0006, 0x0008 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val cch = bb.getInt(dataStart + 8)
                        if (cch in 0..32768 && dataStart + 12 + cch * 2 <= dataStart + recLen) {
                            putCellAt(currentRowIdx, col, String(sheetBytes, dataStart + 12, cch * 2, StandardCharsets.UTF_16LE), styleRef)
                        }
                    }
                }
                0x0007 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val isst = bb.getInt(dataStart + 8)
                        putCellAt(currentRowIdx, col, sharedStrings.getOrNull(isst) ?: "", styleRef)
                    }
                }
                0x00B0 -> {
                    if (recLen >= 16 && merges.size < 4096) {
                        val r0 = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_ROWS - 1)
                        val r1 = bb.getInt(dataStart + 4).coerceIn(r0, MAX_SHEET_ROWS - 1)
                        val c0 = bb.getInt(dataStart + 8).coerceIn(0, MAX_SHEET_COLS - 1)
                        val c1 = bb.getInt(dataStart + 12).coerceIn(c0, MAX_SHEET_COLS - 1)
                        if (r1 > r0 || c1 > c0) {
                            merges.add(MergedCellRegion(r0, r1, c0, c1))
                        }
                    }
                }
            }
            pos = dataStart + recLen
        }

        while (rowsMap.isNotEmpty()) {
            val last = rowsMap.lastKey()
            val row = rowsMap[last]!!
            if (row.isEmpty() && !rowHeights.containsKey(last) && !rowStyles.containsKey(last)) rowsMap.remove(last) else break
        }
        val maxRowKey = rowsMap.keys.maxOrNull() ?: -1
        val denseRows = ArrayList<List<String>>(maxRowKey + 1)
        for (r in 0..maxRowKey) denseRows.add(rowsMap[r] ?: emptyList())
        val colCount = maxColSeen + 1
        val model = SheetModel(
            name = sheetName,
            rows = denseRows,
            styles = styles,
            colWidthsPx = if (colCount > 0) FloatArray(colCount) { c -> colWidths[c] ?: defaultCol } else null,
            mergedRegions = merges,
            rowHeightsPx = if (denseRows.isNotEmpty()) FloatArray(denseRows.size) { r -> rowHeights[r] ?: defaultRow } else null,
            flags = flags,
            formatColors = formatColors,
            fills = fills,
            rowStyles = rowStyles,
            colStyles = colStyles,
            showGridLines = showGrid,
            defaultFontName = defaultFontName,
            defaultFontSize = defaultFontSize,
            defaultRowHeightPx = defaultRow,
            defaultColWidthPx = defaultCol
        )
        if (cfBlocks.isNotEmpty()) {
            val nums = biff12Numbers(sheetBytes)
            val cells = object : CfCells {
                override fun value(row: Int, col: Int, sheet: String?): Any? {
                    if (sheet != null && !sheet.equals(sheetName, true)) return null
                    nums[packCoord(row, col)]?.let { return it }
                    val t = model.text(row, col)
                    if (t.isEmpty()) return null
                    val fl = model.flags(row, col)
                    if ((fl and SheetCellFlags.BOOL) != 0) return t.equals("TRUE", true)
                    if ((fl and SheetCellFlags.ERROR) != 0) return FormulaError.parse(t) ?: t
                    return t
                }
            }
            try {
                SheetConditionalFormats.apply(cfBlocks, model.rowCount, model.colCount, cells, { r, c -> model.baseStyle(r, c) }, use1904DateSystem, model.conditional) { v, code, _, _ ->
                    ExcelNumberFormat.formatCode(v, code, use1904DateSystem).text
                }
            } catch (_: Exception) {}
        }
        return model
    }

    private fun biff12Numbers(bytes: ByteArray): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var row = -1
        while (pos < bytes.size && out.size < 2_000_000) {
            val (recId, recLen, d) = readBiff12RecordHeader(bytes, pos) ?: break
            when (recId) {
                0x0000 -> if (recLen >= 4) row = bb.getInt(d)
                0x0002 -> if (recLen >= 12 && row >= 0) {
                    val rk = bb.getInt(d + 8)
                    val raw = if ((rk and 2) != 0) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and -4L) shl 32)
                    out[packCoord(row, bb.getInt(d))] = if ((rk and 1) != 0) raw / 100.0 else raw
                }
                0x0005, 0x0009 -> if (recLen >= 16 && row >= 0) out[packCoord(row, bb.getInt(d))] = bb.getDouble(d + 8)
            }
            pos = d + recLen
        }
        return out
    }

    private fun biff12Rule(
        h: IntArray, formulas: List<String?>, text: String?, cfvos: List<CfValueObject>, colors: List<Int>,
        barMin: Float, barMax: Float, showValue: Boolean, iconSet: String, iconFlags: Int
    ): CfRule? {
        val type = h[0]
        val template = h[1]
        val style = dxfStyles.getOrNull(h[2])
        val priority = h[3]
        val param = h[4]
        val flags = h[5]
        val stop = (flags and 0x02) != 0
        val f = formulas.filterNotNull()
        return when (type) {
            1 -> if (f.isEmpty()) null else CfRule(
                "cellIs", priority, stopIfTrue = stop,
                operator = when (param) { 1 -> "between"; 2 -> "notBetween"; 3 -> "equal"; 4 -> "notEqual"; 5 -> "greaterThan"; 6 -> "lessThan"; 7 -> "greaterThanOrEqual"; else -> "lessThanOrEqual" },
                formulas = f, style = style
            )
            3 -> if (colors.size < 2 || cfvos.size != colors.size) null else CfRule("colorScale", priority, cfvos = cfvos, colors = colors)
            4 -> CfRule("dataBar", priority, cfvos = cfvos, colors = colors.take(1), showValue = showValue, barMinLength = barMin, barMaxLength = barMax.coerceAtLeast(barMin), barAxisNone = true)
            6 -> CfRule("iconSet", priority, cfvos = cfvos, iconSet = iconSet, reverse = (iconFlags and 0x02) != 0, showValue = (iconFlags and 0x01) == 0)
            5 -> CfRule("top10", priority, stopIfTrue = stop, rank = param, percent = (flags and 0x10) != 0, bottom = (flags and 0x08) != 0, style = style)
            else -> when (template) {
                0x07 -> CfRule("uniqueValues", priority, stopIfTrue = stop, style = style)
                0x1B -> CfRule("duplicateValues", priority, stopIfTrue = stop, style = style)
                0x19, 0x1A, 0x1D, 0x1E -> CfRule("aboveAverage", priority, stopIfTrue = stop, aboveAverage = template == 0x19 || template == 0x1D, equalAverage = template >= 0x1D, stdDev = param, style = style)
                0x08 -> CfRule(when (param) { 1 -> "notContainsText"; 2 -> "beginsWith"; 3 -> "endsWith"; else -> "containsText" }, priority, stopIfTrue = stop, text = text, style = style)
                0x09 -> CfRule("containsBlanks", priority, stopIfTrue = stop, style = style)
                0x0A -> CfRule("notContainsBlanks", priority, stopIfTrue = stop, style = style)
                0x0B -> CfRule("containsErrors", priority, stopIfTrue = stop, style = style)
                0x0C -> CfRule("notContainsErrors", priority, stopIfTrue = stop, style = style)
                in 0x0F..0x18 -> CfRule("timePeriod", priority, stopIfTrue = stop, timePeriod = when (template) {
                    0x0F -> "today"; 0x10 -> "tomorrow"; 0x11 -> "yesterday"; 0x12 -> "last7Days"; 0x13 -> "lastMonth"
                    0x14 -> "nextMonth"; 0x15 -> "thisWeek"; 0x16 -> "nextWeek"; 0x17 -> "lastWeek"; else -> "thisMonth"
                }, style = style)
                else -> if (f.isEmpty()) null else CfRule("expression", priority, stopIfTrue = stop, formulas = f.take(1), style = style)
            }
        }
    }

    private fun parseXlsx() {
        val filter: (String) -> Boolean = { normName ->
            normName.endsWith(".xml", ignoreCase = true) ||
                    normName.endsWith(".rels", ignoreCase = true) ||
                    normName.endsWith(".bin", ignoreCase = true) ||
                    normName.startsWith("xl/media/", ignoreCase = true)
        }
        val zipEntries: Map<String, ByteArray> = try {
            if (isEncryptedOle2Container(pfd.fileDescriptor)) {
                val raw = OfficeZipContainerLoader.readRawBytes(pfd, 128 * 1024 * 1024L)
                val decZip = com.omnireader.core.engine.impl.CleanRoomOfficeCrypto.decryptOoxmlBytes(
                    raw, password, tryDefaultExcelPassword = true
                )
                if (decZip == null) {
                    lastErrorMsg = if (password.isNullOrEmpty()) {
                        "PASSWORD_REQUIRED: Excel spreadsheet (.xlsx) is encrypted or password protected."
                    } else {
                        "PASSWORD_REQUIRED: Incorrect password for Excel spreadsheet (.xlsx)."
                    }
                    return
                }
                OfficeZipContainerLoader.readZipEntriesFromBytes(decZip, 128 * 1024 * 1024L, filter)
            } else {
                OfficeZipContainerLoader.readZipEntries(
                    pfd = pfd,
                    maxTotalBytes = 128 * 1024 * 1024L,
                    entryFilter = filter
                )
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
            return
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер XLSX: ${e.localizedMessage}"
            return
        }

        if (zipEntries.containsKey("xl/workbook.bin") ||
            zipEntries.keys.any { it.startsWith("xl/worksheets/", true) && it.endsWith(".bin", true) }
        ) {
            parseBiff12Styles(zipEntries["xl/styles.bin"])
            val sst = zipEntries["xl/sharedStrings.bin"]?.let { parseBiff12SharedStrings(it) } ?: emptyList()
            val wbNames = parseBiff12WorkbookSheetNames(zipEntries["xl/workbook.bin"])
            val binRels = DmlPackage(zipEntries).rels("xl/workbook.bin")
            val byRel = wbNames.mapNotNull { (n, rId) ->
                val target = binRels[rId]?.target ?: return@mapNotNull null
                if (zipEntries.containsKey(target) && target.contains("/worksheets/", true)) n to target else null
            }
            val sheetBinKeys = if (byRel.isNotEmpty()) byRel.map { it.second } else zipEntries.keys.filter {
                val l = it.lowercase(Locale.ROOT)
                l.startsWith("xl/worksheets/sheet") && l.endsWith(".bin")
            }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

            sheetBinKeys.forEachIndexed { idx, key ->
                val bytes = zipEntries[key] ?: return@forEachIndexed
                val sName = (if (byRel.isNotEmpty()) byRel[idx].first else wbNames.getOrNull(idx)?.first)?.takeIf { it.isNotBlank() } ?: "Sheet ${idx + 1}"
                val model = parseBiff12Worksheet(sName, bytes, sst)
                if (model.rows.isNotEmpty() || sheetBinKeys.size == 1) {
                    sheets.add(model)
                }
            }
            if (sheets.isEmpty()) {
                lastErrorMsg = "В бинарной книге XLSB не обнаружено листов с данными."
            }
            return
        }

        zipEntries.entries.firstOrNull {
            it.key.startsWith("xl/theme/theme", ignoreCase = true) && it.key.endsWith(".xml", ignoreCase = true)
        }?.value?.let { parseExcelThemeXml(it) }

        parseStylesXml(zipEntries["xl/styles.xml"])

        val sharedStrings = mutableListOf<String>()
        zipEntries["xl/sharedStrings.xml"]?.let { ssBytes ->
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(ssBytes), StandardCharsets.UTF_8))
                var event = parser.eventType
                var currentSi = StringBuilder()
                var inSi = false
                var inT = false
                var ignorePhoneticDepth = 0
                while (event != XmlPullParser.END_DOCUMENT && sharedStrings.size < 250_000) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (local) {
                                "si" -> { inSi = true; currentSi = StringBuilder() }
                                "rph", "phoneticpr" -> if (inSi) ignorePhoneticDepth++
                                "t" -> if (inSi && ignorePhoneticDepth == 0) inT = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inSi && inT && ignorePhoneticDepth == 0 && currentSi.length < 32768) {
                                currentSi.append(parser.text ?: "")
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (local) {
                                "rph", "phoneticpr" -> if (ignorePhoneticDepth > 0) ignorePhoneticDepth--
                                "t" -> inT = false
                                "si" -> if (inSi) {
                                    sharedStrings.add(currentSi.toString())
                                    inSi = false
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            } catch (_: Exception) {}
        }

        val wbRels = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        val relsBytes = zipEntries["xl/_rels/workbook.xml.rels"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("workbook.xml.rels", ignoreCase = true) }?.value
        if (relsBytes != null) {
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(relsBytes), StandardCharsets.UTF_8))
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (ev == XmlPullParser.START_TAG && local == "relationship") {
                        val id = parser.getAttributeValue(null, "Id") ?: parser.getAttributeValue(null, "id")
                        val target = parser.getAttributeValue(null, "Target") ?: parser.getAttributeValue(null, "target")
                        if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                            val clean = target.replace('\\', '/').trimStart('/')
                            val resolved = if (clean.startsWith("xl/", ignoreCase = true)) {
                                OfficeZipContainerLoader.normalizeZipPath(clean)
                            } else {
                                OfficeZipContainerLoader.normalizeZipPath("xl/$clean")
                            }
                            wbRels[id] = resolved
                        }
                    }
                    ev = parser.next()
                }
            } catch (_: Exception) {}
        }

        val orderedSheets = mutableListOf<Pair<String, String>>()
        val wbXml = zipEntries["xl/workbook.xml"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("/workbook.xml", ignoreCase = true) }?.value
        if (wbXml != null) {
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(wbXml), StandardCharsets.UTF_8))
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (ev == XmlPullParser.START_TAG) {
                        if (local == "workbookpr") {
                            val d1904 = parser.getAttributeValue(null, "date1904")
                            use1904DateSystem = (d1904 == "1" || d1904.equals("true", ignoreCase = true))
                        } else if (local == "sheet") {
                            val name = parser.getAttributeValue(null, "name") ?: "Sheet ${orderedSheets.size + 1}"
                            var rId: String? = null
                            for (i in 0 until parser.attributeCount) {
                                val aName = parser.getAttributeName(i)?.lowercase(Locale.ROOT) ?: continue
                                val aNs = parser.getAttributeNamespace(i).orEmpty()
                                if (aName == "r:id" || aName.endsWith(":id") || (aName == "id" && aNs.endsWith("/relationships"))) {
                                    rId = parser.getAttributeValue(i)
                                    break
                                }
                            }
                            val targetPath = if (rId != null) wbRels[rId] else null
                            if (targetPath != null && zipEntries.containsKey(targetPath)) {
                                orderedSheets.add(name to targetPath)
                            } else {
                                val fallbackKey = "xl/worksheets/sheet${orderedSheets.size + 1}.xml"
                                if (zipEntries.containsKey(fallbackKey)) {
                                    orderedSheets.add(name to fallbackKey)
                                }
                            }
                        }
                    }
                    ev = parser.next()
                }
            } catch (_: Exception) {}
        }

        if (orderedSheets.isEmpty()) {
            val discovered = zipEntries.keys
                .filter {
                    val norm = it.lowercase(Locale.ROOT)
                    norm.startsWith("xl/worksheets/sheet") && norm.endsWith(".xml")
                }
                .sortedWith(compareBy({ path ->
                    path.substringAfterLast('/').filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
                }, { it }))
            discovered.forEachIndexed { idx, path ->
                orderedSheets.add("Sheet ${idx + 1}" to path)
            }
        }

        val dmlPackage = DmlPackage(zipEntries)
        val dmlPres = try { DmlPresentation(dmlPackage).apply { spreadsheet = true } } catch (_: Exception) { null }
        drawingRenderer = dmlPres?.let { DmlSlideRenderer(it) }
        val sheetDrawings = HashMap<String, List<DmlCellAnchor>>()
        val pendingSheets = ArrayList<RawWorksheetParseResult>()
        for ((sheetName, sheetPath) in orderedSheets) {
            val drawings = if (dmlPres != null) try { DmlDrawings.forSheet(dmlPackage, sheetPath) } catch (_: Exception) { emptyList() } else emptyList()
            if (sheetPath.contains("/chartsheets/", ignoreCase = true)) {
                if (drawings.isNotEmpty()) {
                    sheetDrawings[sheetName.lowercase(Locale.ROOT)] = drawings
                    pendingSheets.add(RawWorksheetParseResult(sheetName, ArrayList(), HashMap(), emptyMap(), null, emptyList(), chartSheet = true))
                }
                continue
            }
            val sheetBytes = zipEntries[sheetPath] ?: continue
            val parsed = parseWorksheetRaw(sheetName, sheetBytes, sharedStrings)
            if (parsed.grid.isNotEmpty() || drawings.isNotEmpty() || orderedSheets.size == 1) {
                if (drawings.isNotEmpty()) sheetDrawings[sheetName.lowercase(Locale.ROOT)] = drawings
                pendingSheets.add(parsed)
            }
        }

        val sheetByName = HashMap<String, RawWorksheetParseResult>()
        for (ps in pendingSheets) {
            sheetByName[ps.name.lowercase(Locale.ROOT)] = ps
        }

        val sheetPathByName = HashMap<String, String>()
        for ((n, path) in orderedSheets) sheetPathByName[n.lowercase(Locale.ROOT)] = path
        val rawCache = HashMap<String, Map<Long, String>>()
        val definedNames = HashMap<String, String>()
        wbXml?.let { bytes ->
            for (dn in xmlSubtrees(bytes, setOf("definedName"))) {
                val n = dn.attr("name") ?: continue
                if (dn.text.isNotBlank()) definedNames.putIfAbsent(n.lowercase(Locale.ROOT), dn.text.trim())
            }
        }
        fun rawFor(sheetName: String): Map<Long, String> = rawCache.getOrPut(sheetName.lowercase(Locale.ROOT)) {
            sheetPathByName[sheetName.lowercase(Locale.ROOT)]?.let { zipEntries[it] }?.let { rawNumericValues(it) } ?: emptyMap()
        }
        cfNames = definedNames
        val workbook = FormulaWorkbook(use1904DateSystem, definedNames)
        if (pendingSheets.any { it.formulas.isNotEmpty() }) {
            for (ps in pendingSheets) {
                if (ps.chartSheet) continue
                workbook.add(FormulaWorkbook.Sheet(ps.name, ps.grid.size, maxOf(ps.grid.maxOfOrNull { it.size } ?: 0, 1), ps.formulas) { r, c ->
                    rawFor(ps.name)[packCoord(r, c)]?.toDoubleOrNull() ?: typedCell(ps.grid.getOrNull(r)?.getOrNull(c), ps.flags[r]?.getOrNull(c)?.toInt() ?: 0)
                })
            }
        }

        val formulaResults = HashMap<String, Map<Long, Any?>>()
        for (ps in pendingSheets) {
            if (ps.formulas.isNotEmpty()) {
                val computed = workbook.evaluateAll(ps.name)
                formulaResults[ps.name.lowercase(Locale.ROOT)] = computed
                for ((key, v) in computed) {
                    val r = (key ushr 32).toInt()
                    val c = (key and 0xFFFFFFFFL).toInt()
                    val row = ps.grid.getOrNull(r) ?: continue
                    while (row.size <= c) row.add("")
                    when (v) {
                        is Double -> {
                            val xf = xfStyles.getOrNull(ps.formulaXf[key] ?: -1)
                            val res = ExcelNumberFormat.format(v, xf?.numFmtId ?: 0, xf?.formatCode, use1904DateSystem)
                            row[c] = res.text
                            rowFlags(ps.flags, r, c, SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0))
                            res.color?.let { ps.formatColors[key] = it }
                            if (res.fillAt >= 0) ps.fills[key] = (res.fillAt shl 16) or res.fillChar.code
                        }
                        is Boolean -> { row[c] = if (v) "TRUE" else "FALSE"; rowFlags(ps.flags, r, c, SheetCellFlags.BOOL) }
                        is FormulaError -> { row[c] = v.code; rowFlags(ps.flags, r, c, SheetCellFlags.ERROR) }
                        null -> row[c] = ""
                        else -> row[c] = v.toString()
                    }
                }
            }
            val drawings = sheetDrawings[ps.name.lowercase(Locale.ROOT)].orEmpty()
            if (!ps.chartSheet && drawings.isNotEmpty()) padGridForDrawings(ps.grid, drawings, MAX_SHEET_ROWS, MAX_SHEET_COLS)
            val colCount = maxOf(ps.grid.maxOfOrNull { it.size } ?: 0, ps.colWidthsPx?.size ?: 0)
            val widths = if (ps.chartSheet) null else FloatArray(colCount) { c -> ps.colWidthsPx?.getOrNull(c) ?: ps.colWidthMap[c] ?: ps.defaultColWidthPx }
            val heights = if (ps.chartSheet || ps.grid.isEmpty()) null else FloatArray(ps.grid.size) { r -> ps.rowHeights[r] ?: ps.defaultRowHeightPx }
            val model =
                SheetModel(
                    name = ps.name,
                    rows = ps.grid,
                    styles = ps.styles,
                    colWidthsPx = widths,
                    mergedRegions = ps.mergedRegions,
                    drawings = drawings,
                    chartSheet = ps.chartSheet,
                    rowHeightsPx = heights,
                    flags = ps.flags,
                    formatColors = ps.formatColors,
                    fills = ps.fills,
                    rowStyles = ps.rowStyles,
                    colStyles = ps.colStyles,
                    showGridLines = ps.showGridLines,
                    defaultFontName = defaultFontName,
                    defaultFontSize = defaultFontSize,
                    defaultRowHeightPx = ps.defaultRowHeightPx,
                    defaultColWidthPx = ps.defaultColWidthPx
                )
            sheets.add(model)
            val sheetBytes = sheetPathByName[ps.name.lowercase(Locale.ROOT)]?.let { zipEntries[it] }
            if (!ps.chartSheet && sheetBytes != null) {
                try {
                    val blocks = conditionalBlocks(sheetBytes)
                    if (blocks.isNotEmpty()) applyConditional(model, blocks, ps.name, { n -> formulaResults[n.lowercase(Locale.ROOT)].orEmpty() }, ::rawFor) { n -> sheets.firstOrNull { it.name.equals(n, true) } }
                } catch (_: Exception) {}
            }
        }

        if (dmlPres != null && sheets.any { it.drawings.isNotEmpty() }) {
            val byName = HashMap<String, SheetModel>()
            for (sm in sheets) byName[sm.name.lowercase(Locale.ROOT)] = sm
            val first = sheets.firstOrNull { !it.chartSheet }
            dmlPres.chartCells = ChartCellResolver { sheetName, row, col ->
                val sm = if (sheetName == null) first else byName[sheetName.lowercase(Locale.ROOT)]
                val txt = sm?.rows?.getOrNull(row)?.getOrNull(col)?.trim()
                if (txt.isNullOrEmpty()) null else chartCellValue(txt)
            }
        }

        if (sheets.isEmpty()) {
            lastErrorMsg = "В книге XLSX не обнаружено листов с данными."
        }
    }

    private fun chartCellValue(txt: String): Any {
        val compact = txt.replace("\u00A0", "").replace(" ", "")
        compact.toDoubleOrNull()?.let { return it }
        if (compact.endsWith("%")) compact.dropLast(1).replace(',', '.').toDoubleOrNull()?.let { return it / 100.0 }
        val commas = compact.count { it == ',' }
        if (commas == 1 && '.' !in compact && compact.length - compact.indexOf(',') - 1 != 3) compact.replace(',', '.').toDoubleOrNull()?.let { return it }
        if (commas >= 1) compact.replace(",", "").toDoubleOrNull()?.let { return it }
        return txt
    }

    private class RawWorksheetParseResult(
        val name: String,
        val grid: ArrayList<ArrayList<String>>,
        val formulas: HashMap<Long, String>,
        val styles: Map<Long, SheetCellStyle>,
        val colWidthsPx: FloatArray?,
        val mergedRegions: List<MergedCellRegion>,
        val chartSheet: Boolean = false,
        val formulaXf: Map<Long, Int> = emptyMap(),
        val rowHeights: Map<Int, Float> = emptyMap(),
        val flags: HashMap<Int, ByteArray> = HashMap(),
        val formatColors: HashMap<Long, Int> = HashMap(),
        val fills: HashMap<Long, Int> = HashMap(),
        val rowStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colWidthMap: Map<Int, Float> = emptyMap(),
        val showGridLines: Boolean = true,
        val defaultRowHeightPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT,
        val defaultColWidthPx: Float = defaultExcelColumnWidthPx()
    )

    private fun rawNumericValues(sheetBytes: ByteArray): Map<Long, String> {
        val out = HashMap<Long, String>()
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(sheetBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var key = -1L
            var numeric = false
            var inV = false
            val sb = StringBuilder()
            var rowIdx = -1
            var colIdx = 0
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 2_000_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> when (local) {
                        "row" -> {
                            rowIdx = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (rowIdx + 1)
                            colIdx = 0
                        }
                        "c" -> {
                            val t = parser.getAttributeValue(null, "t")
                            numeric = t == null || t == "n"
                            val c = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parser.getAttributeValue(null, "r") ?: "")
                            if (c >= 0) colIdx = c
                            key = packCoord(rowIdx, colIdx)
                            sb.setLength(0)
                        }
                        "v" -> inV = true
                    }
                    XmlPullParser.TEXT -> if (inV) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> when (local) {
                        "v" -> inV = false
                        "c" -> {
                            if (numeric && sb.isNotEmpty()) out[key] = sb.toString().trim()
                            colIdx++
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun typedCell(text: String?, flags: Int): Any? {
        if (text.isNullOrEmpty()) return null
        if ((flags and SheetCellFlags.BOOL) != 0) return text.equals("TRUE", true)
        if ((flags and SheetCellFlags.ERROR) != 0) return FormulaError.parse(text) ?: text
        return text
    }

    private fun applyConditional(
        model: SheetModel,
        blocks: List<CfBlock>,
        sheetName: String,
        computed: (String) -> Map<Long, Any?>,
        raw: (String) -> Map<Long, String>,
        other: (String) -> SheetModel?
    ) {
        fun cellValue(m: SheetModel?, rawMap: Map<Long, String>, r: Int, c: Int): Any? {
            if (m == null) return null
            val key = packCoord(r, c)
            val results = computed(m.name)
            if (results.containsKey(key)) return results[key]
            rawMap[key]?.toDoubleOrNull()?.let { return it }
            val t = m.text(r, c)
            if (t.isEmpty()) return null
            val fl = m.flags(r, c)
            if ((fl and SheetCellFlags.BOOL) != 0) return t.equals("TRUE", true)
            if ((fl and SheetCellFlags.ERROR) != 0) return FormulaError.parse(t) ?: t
            return t
        }
        val ownRaw = raw(sheetName)
        val cells = object : CfCells {
            override fun value(row: Int, col: Int, sheet: String?): Any? =
                if (sheet == null || sheet.equals(sheetName, true)) cellValue(model, ownRaw, row, col) else cellValue(other(sheet), raw(sheet), row, col)

            override fun name(name: String): String? = cfNames[name.lowercase(Locale.ROOT)]
        }
        SheetConditionalFormats.apply(
            blocks, model.rowCount, model.colCount, cells, { r, c -> model.baseStyle(r, c) }, use1904DateSystem, model.conditional
        ) { v, code, _, _ -> ExcelNumberFormat.formatCode(v, code, use1904DateSystem).text }
    }

    private fun xmlSubtrees(bytes: ByteArray, names: Set<String>): List<DmlNode> {
        val out = ArrayList<DmlNode>()
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
            val stack = ArrayList<DmlNode>()
            var ev = parser.eventType
            var count = 0
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (stack.isNotEmpty() || local in names) {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfterLast(':')] = parser.getAttributeValue(i)
                            val node = DmlNode(local, attrs)
                            if (stack.isEmpty()) out.add(node) else stack.last().children.add(node)
                            stack.add(node)
                            if (++count > 200_000) return out
                        }
                    }
                    XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack.last().text += parser.text
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun cfvo(n: DmlNode): CfValueObject = CfValueObject(
        n.attr("type") ?: "min",
        n.attr("val") ?: n.child("f")?.text?.trim(),
        n.attr("gte") != "0"
    )

    private fun conditionalBlocks(sheetBytes: ByteArray): List<CfBlock> {
        if (!String(sheetBytes, StandardCharsets.ISO_8859_1).contains("conditionalFormatting")) return emptyList()
        val nodes = xmlSubtrees(sheetBytes, setOf("conditionalFormatting"))
        val x14Rules = HashMap<String, DmlNode>()
        val blocks = ArrayList<CfBlock>()
        for (cf in nodes) {
            val x14 = cf.child("sqref") != null
            if (!x14) continue
            for (rule in cf.all("cfRule")) rule.attr("id")?.let { x14Rules[it] = rule }
        }
        for (cf in nodes) {
            val x14Sqref = cf.child("sqref")?.text
            val ranges = SheetConditionalFormats.parseSqref(x14Sqref ?: cf.attr("sqref") ?: continue)
            if (ranges.isEmpty()) continue
            val rules = ArrayList<CfRule>()
            for (r in cf.all("cfRule")) {
                val type = r.attr("type") ?: continue
                val extId = r.descendants("id").firstOrNull()?.text?.trim()
                if (x14Sqref == null && extId != null && type != "dataBar") continue
                if (x14Sqref != null && (type == "dataBar" && x14Rules.containsKey(r.attr("id") ?: "") && nodes.any { o -> o.child("sqref") == null && o.descendants("id").any { it.text.trim() == r.attr("id") } })) continue
                val formulas = r.all("formula").map { it.text.trim() } + r.all("f").map { it.text.trim() }
                val style = r.int("dxfId")?.let { dxfStyles.getOrNull(it) }
                when (type) {
                    "colorScale" -> {
                        val cs = r.child("colorScale") ?: continue
                        rules.add(CfRule(type, r.int("priority") ?: Int.MAX_VALUE, cfvos = cs.all("cfvo").map { cfvo(it) }, colors = cs.all("color").mapNotNull { nodeColor(it) }))
                    }
                    "dataBar" -> {
                        val db = r.child("dataBar") ?: continue
                        val ext = extId?.let { x14Rules[it] }?.child("dataBar")
                        val src = ext ?: db
                        rules.add(
                            CfRule(
                                type, r.int("priority") ?: Int.MAX_VALUE,
                                cfvos = src.all("cfvo").map { cfvo(it) }.ifEmpty { db.all("cfvo").map { cfvo(it) } },
                                colors = listOfNotNull(nodeColor(db.child("color")) ?: nodeColor(src.child("fillColor"))),
                                showValue = db.attr("showValue") != "0",
                                barGradient = ext?.attr("gradient") != "0",
                                barBorder = if (ext?.attr("border") == "1") nodeColor(ext.child("borderColor")) else null,
                                barNegative = if (ext != null && ext.attr("negativeBarColorSameAsPositive") != "1") nodeColor(ext.child("negativeFillColor")) else nodeColor(db.child("color")),
                                barAxisColor = ext?.child("axisColor")?.let { nodeColor(it) },
                                barAxisNone = ext == null || ext.attr("axisPosition") == "none",
                                barMinLength = (ext?.attr("minLength")?.toFloatOrNull() ?: if (ext != null) 0f else db.attr("minLength")?.toFloatOrNull() ?: 10f) / 100f,
                                barMaxLength = (ext?.attr("maxLength")?.toFloatOrNull() ?: if (ext != null) 100f else db.attr("maxLength")?.toFloatOrNull() ?: 90f) / 100f
                            )
                        )
                    }
                    "iconSet" -> {
                        val ic = r.child("iconSet") ?: continue
                        val custom = if (ic.attr("custom") == "1") ic.all("cfIcon").map { (it.attr("iconSet") ?: "3TrafficLights1") to (it.int("iconId") ?: 0) } else null
                        rules.add(
                            CfRule(
                                type, r.int("priority") ?: Int.MAX_VALUE,
                                cfvos = ic.all("cfvo").map { cfvo(it) },
                                iconSet = ic.attr("iconSet") ?: "3TrafficLights1",
                                reverse = ic.attr("reverse") == "1",
                                showValue = ic.attr("showValue") != "0",
                                customIcons = custom
                            )
                        )
                    }
                    else -> rules.add(
                        CfRule(
                            type,
                            r.int("priority") ?: Int.MAX_VALUE,
                            stopIfTrue = r.attr("stopIfTrue") == "1",
                            operator = r.attr("operator"),
                            formulas = formulas,
                            text = r.attr("text"),
                            style = style,
                            rank = r.int("rank") ?: 10,
                            percent = r.attr("percent") == "1",
                            bottom = r.attr("bottom") == "1",
                            aboveAverage = r.attr("aboveAverage") != "0",
                            equalAverage = r.attr("equalAverage") == "1",
                            stdDev = r.int("stdDev") ?: 0,
                            timePeriod = r.attr("timePeriod")
                        )
                    )
                }
            }
            if (rules.isNotEmpty()) blocks.add(CfBlock(ranges, rules))
        }
        return blocks
    }

    private fun xmlBool(v: String?): Boolean = v == "1" || v.equals("true", ignoreCase = true)

    private fun parseWorksheetRaw(
        name: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): RawWorksheetParseResult {
        val grid = ArrayList<ArrayList<String>>()
        val stylesMap = HashMap<Long, SheetCellStyle>()
        val formulasMap = HashMap<Long, String>()
        val formulaXf = HashMap<Long, Int>()
        val colWidthsMap = HashMap<Int, Float>()
        val colStyles = HashMap<Int, SheetCellStyle>()
        val rowStyles = HashMap<Int, SheetCellStyle>()
        val rowHeights = HashMap<Int, Float>()
        val flags = HashMap<Int, ByteArray>()
        val formatColors = HashMap<Long, Int>()
        val fills = HashMap<Long, Int>()
        val mergedRegions = ArrayList<MergedCellRegion>()
        var showGridLines = true
        var defaultRowHeight = Math.round(defaultFontSize * 1.364f * 4f) / 4f * PX_PER_PT
        var defaultColWidth = defaultExcelColumnWidthPx()
        var maxColSeen = -1
        var maxWidthCol = -1

        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(sheetBytes), StandardCharsets.UTF_8))
            var event = parser.eventType
            var currentRow: ArrayList<String>? = null
            var currentRowIdx = -1
            var currentCellType: String? = null
            var currentCellStyleIdx = -1
            var currentColIdx = 0
            var inV = false
            var inIs = false
            var inFormula = false
            var inRph = 0
            var formulaType: String? = null
            var formulaSi: String? = null
            val sharedMasters = HashMap<String, Triple<String, Int, Int>>()
            val cellTextBuilder = StringBuilder()
            val formulaTextBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT && grid.size <= MAX_SHEET_ROWS) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "sheetview" -> {
                                val g = parser.getAttributeValue(null, "showGridLines")
                                if (g == "0" || g.equals("false", true)) showGridLines = false
                            }
                            "sheetformatpr" -> {
                                parser.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { if (it > 0f) defaultRowHeight = it * PX_PER_PT }
                                val dcw = parser.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull()
                                val base = parser.getAttributeValue(null, "baseColWidth")?.toFloatOrNull()
                                if (dcw != null && dcw > 0f) defaultColWidth = SpreadsheetCoordinateUtils.excelCharWidthToPixels(dcw)
                                else if (base != null && base > 0f) defaultColWidth = defaultExcelColumnWidthPx(base)
                            }
                            "col" -> {
                                val minCol = (parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                                val maxCol = (parser.getAttributeValue(null, "max")?.toIntOrNull() ?: (minCol + 1)) - 1
                                val widthChars = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                val hidden = xmlBool(parser.getAttributeValue(null, "hidden"))
                                val colStyle = parser.getAttributeValue(null, "style")?.toIntOrNull()?.let { xfStyles.getOrNull(it)?.style }
                                val startC = minCol.coerceIn(0, MAX_SHEET_COLS - 1)
                                val endC = maxCol.coerceIn(startC, MAX_SHEET_COLS - 1)
                                val px = when {
                                    hidden -> 0f
                                    widthChars != null && widthChars >= 0f -> SpreadsheetCoordinateUtils.excelCharWidthToPixels(widthChars)
                                    else -> null
                                }
                                for (c in startC..endC) {
                                    if (px != null) colWidthsMap[c] = px
                                    if (colStyle != null) colStyles[c] = colStyle
                                }
                                if (px != null && (hidden || abs(px - defaultColWidth) > 0.5f)) maxWidthCol = maxOf(maxWidthCol, minOf(endC, startC + 64))
                            }
                            "row" -> {
                                val rAttr = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                val targetRowIdx = if (rAttr != null && rAttr > 0) (rAttr - 1) else (currentRowIdx + 1)
                                if (targetRowIdx < MAX_SHEET_ROWS) {
                                    while (grid.size < targetRowIdx && grid.size < MAX_SHEET_ROWS) {
                                        grid.add(ArrayList())
                                    }
                                    currentRow = ArrayList()
                                    currentRowIdx = targetRowIdx
                                    if (xmlBool(parser.getAttributeValue(null, "hidden"))) {
                                        rowHeights[targetRowIdx] = 0f
                                    } else {
                                        parser.getAttributeValue(null, "ht")?.toFloatOrNull()?.let { if (it >= 0f) rowHeights[targetRowIdx] = it * PX_PER_PT }
                                    }
                                    if (xmlBool(parser.getAttributeValue(null, "customFormat"))) {
                                        parser.getAttributeValue(null, "s")?.toIntOrNull()?.let { xfStyles.getOrNull(it)?.style }?.let { rowStyles[targetRowIdx] = it }
                                    }
                                } else {
                                    currentRow = null
                                }
                                currentColIdx = 0
                            }
                            "c" -> {
                                currentCellType = parser.getAttributeValue(null, "t")
                                currentCellStyleIdx = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                                val cellRef = parser.getAttributeValue(null, "r")
                                val parsedCol = SpreadsheetCoordinateUtils.columnIndexFromCellRef(cellRef ?: "")
                                if (parsedCol >= 0) {
                                    currentColIdx = parsedCol
                                }
                                cellTextBuilder.setLength(0)
                                formulaTextBuilder.setLength(0)
                                formulaType = null
                                formulaSi = null
                            }
                            "f" -> {
                                inFormula = true
                                formulaType = parser.getAttributeValue(null, "t")
                                formulaSi = parser.getAttributeValue(null, "si")
                            }
                            "v" -> inV = true
                            "is" -> inIs = true
                            "rph", "phoneticpr" -> if (inIs) inRph++
                            "t" -> if (inIs && inRph == 0) inV = true
                            "mergecell" -> {
                                val ref = parser.getAttributeValue(null, "ref") ?: ""
                                val parts = ref.split(':')
                                if (parts.size == 2 && mergedRegions.size < 4096) {
                                    val c0 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[0])
                                    val c1 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[1])
                                    val r0 = (parts[0].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    val r1 = (parts[1].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    if (c0 in 0..c1 && r0 in 0..r1 && c0 < MAX_SHEET_COLS && r0 < MAX_SHEET_ROWS) {
                                        mergedRegions.add(MergedCellRegion(r0, minOf(r1, MAX_SHEET_ROWS - 1), c0, minOf(c1, MAX_SHEET_COLS - 1)))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inFormula && formulaTextBuilder.length < 4096) {
                            formulaTextBuilder.append(parser.text ?: "")
                        } else if (inV && cellTextBuilder.length < 32768) {
                            cellTextBuilder.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "f" -> inFormula = false
                            "v" -> inV = false
                            "t" -> if (inIs) inV = false
                            "rph", "phoneticpr" -> if (inRph > 0) inRph--
                            "is" -> inIs = false
                            "c" -> {
                                val row = currentRow
                                if (row != null && currentColIdx in 0 until MAX_SHEET_COLS) {
                                    val rawText = cellTextBuilder.toString()
                                    val rawVal = rawText.trim()
                                    val xfSpec = xfStyles.getOrNull(currentCellStyleIdx)
                                    val packedCoord = packCoord(currentRowIdx, currentColIdx)
                                    var kind = 0
                                    var fmt: ExcelNumberFormat.Result? = null
                                    val resolved = when (currentCellType) {
                                        "s" -> {
                                            val idx = rawVal.toIntOrNull() ?: -1
                                            sharedStrings.getOrElse(idx) { rawVal }
                                        }
                                        "b" -> { kind = SheetCellFlags.BOOL; if (rawVal == "1" || rawVal.equals("true", ignoreCase = true)) "TRUE" else "FALSE" }
                                        "e" -> { kind = SheetCellFlags.ERROR; rawVal }
                                        "inlineStr" -> rawText
                                        "str", "d" -> rawText
                                        else -> {
                                            val numVal = rawVal.toDoubleOrNull()
                                            if (numVal != null) {
                                                val res = ExcelNumberFormat.format(numVal, xfSpec?.numFmtId ?: 0, xfSpec?.formatCode, use1904DateSystem)
                                                kind = SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0)
                                                fmt = res
                                                res.text
                                            } else {
                                                rawVal
                                            }
                                        }
                                    }.let { txt ->
                                        if (kind == 0 && txt.isNotEmpty() && xfSpec?.formatCode != null && (xfSpec.formatCode.contains('@') || xfSpec.formatCode.count { it == ';' } >= 3)) {
                                            val res = ExcelNumberFormat.formatText(txt, xfSpec.numFmtId, xfSpec.formatCode)
                                            fmt = res
                                            res.text
                                        } else txt
                                    }

                                    while (row.size < currentColIdx && row.size < MAX_SHEET_COLS) {
                                        row.add("")
                                    }
                                    if (row.size == currentColIdx) {
                                        row.add(resolved)
                                    } else if (currentColIdx < row.size) {
                                        row[currentColIdx] = resolved
                                    }
                                    if (currentColIdx > maxColSeen) maxColSeen = currentColIdx
                                    if (kind != 0 && resolved.isNotEmpty()) rowFlags(flags, currentRowIdx, currentColIdx, kind)
                                    fmt?.let { f ->
                                        f.color?.let { if (formatColors.size < 250_000) formatColors[packedCoord] = it }
                                        if (f.fillAt >= 0 && fills.size < 250_000) fills[packedCoord] = (f.fillAt shl 16) or f.fillChar.code
                                    }

                                    var fStr = formulaTextBuilder.toString().trim()
                                    val si = formulaSi
                                    if (formulaType == "shared" && si != null) {
                                        if (fStr.isNotEmpty()) sharedMasters[si] = Triple(fStr, currentRowIdx, currentColIdx)
                                        else sharedMasters[si]?.let { (m, mr, mc) -> fStr = SheetConditionalFormats.shiftFormula(m, currentRowIdx - mr, currentColIdx - mc) }
                                    }
                                    if (fStr.isNotEmpty() && resolved.isEmpty() && formulasMap.size < 100_000) {
                                        formulasMap[packedCoord] = fStr
                                        if (currentCellStyleIdx >= 0) formulaXf[packedCoord] = currentCellStyleIdx
                                    }

                                    val vs = xfSpec?.style
                                    if (vs != null && currentRowIdx >= 0 && stylesMap.size < 250_000) {
                                        stylesMap[packedCoord] = vs
                                    }
                                }
                                currentColIdx++
                            }
                            "row" -> {
                                currentRow?.let { grid.add(it) }
                                currentRow = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}

        while (grid.size > MAX_SHEET_ROWS) grid.removeAt(grid.lastIndex)
        val widthCols = maxOf(maxColSeen, minOf(maxWidthCol, maxColSeen + 1))
        val colWidthsArr = if (widthCols >= 0) {
            FloatArray(widthCols + 1) { c -> colWidthsMap[c] ?: defaultColWidth }
        } else null

        return RawWorksheetParseResult(
            name = name,
            grid = grid,
            formulas = formulasMap,
            styles = stylesMap,
            colWidthsPx = colWidthsArr,
            mergedRegions = mergedRegions,
            formulaXf = formulaXf,
            rowHeights = rowHeights,
            flags = flags,
            formatColors = formatColors,
            fills = fills,
            rowStyles = rowStyles,
            colStyles = colStyles,
            colWidthMap = colWidthsMap,
            showGridLines = showGridLines,
            defaultRowHeightPx = defaultRowHeight,
            defaultColWidthPx = defaultColWidth
        )
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}

class OdsSpreadsheetSession private constructor(
    private val pfd: ParcelFileDescriptor?,
    preloadedEntries: Map<String, ByteArray>?,
    private val password: String? = null
) : SpreadsheetGridSession() {
    constructor(pfd: ParcelFileDescriptor, password: String? = null) : this(pfd, null, password)
    internal constructor(entries: Map<String, ByteArray>, password: String? = null) : this(null, entries, password)

    companion object {
        private const val MAX_ODS_ROWS = 50_000
        private const val MAX_ODS_COLS = 512
    }

    init {
        if (preloadedEntries != null) parseOdsEntries(preloadedEntries) else parseOds()
    }

    private fun findAttrBySuffix(parser: XmlPullParser, suffix: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i) ?: continue
            if (name.equals(suffix, ignoreCase = true) || name.endsWith(":$suffix", ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun odsLengthPt(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().lowercase(Locale.ROOT)
        val num = t.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
        return when {
            t.endsWith("pt") -> num
            t.endsWith("cm") -> num * 28.3465f
            t.endsWith("mm") -> num * 2.83465f
            t.endsWith("in") -> num * 72f
            t.endsWith("pc") -> num * 12f
            t.endsWith("px") -> num * 0.75f
            else -> null
        }
    }

    private class OdsStyleRaw(val family: String, val parent: String?, val dataStyleName: String? = null, val props: HashMap<String, String> = HashMap()) {
        val maps = ArrayList<Array<String?>>()
    }

    private fun odsColor(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val s = v.trim()
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        return when (hex.length) {
            6 -> hex.toIntOrNull(16)?.let { (0xFF shl 24) or it }
            8 -> hex.toLongOrNull(16)?.toInt()
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            else -> null
        }
    }

    private fun odsBorder(v: String?): SheetBorder? {
        if (v.isNullOrBlank()) return null
        val parts = v.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        if (parts.any { it == "none" || it == "hidden" }) return null
        var widthPt = 0.75f
        var color: Int? = null
        var kind = "solid"
        for (p in parts) {
            when {
                p.startsWith("#") -> color = odsColor(p)
                p in setOf("solid", "dotted", "dashed", "double", "dot-dash", "dot-dot-dash", "fine-dashed", "dash-dot", "dash-dot-dot", "groove", "ridge", "inset", "outset", "wave", "double-thin") -> kind = p
                p.firstOrNull()?.isDigit() == true || p.startsWith(".") -> odsLengthPt(p)?.let { widthPt = it }
                p == "thin" -> widthPt = 0.75f
                p == "medium" -> widthPt = 1.75f
                p == "thick" -> widthPt = 2.5f
            }
        }
        if (widthPt <= 0f) return null
        val style = when (kind) {
            "double", "double-thin" -> SheetBorder.DOUBLE
            "dotted" -> SheetBorder.DOTTED
            "dashed", "fine-dashed" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASHED else SheetBorder.DASHED
            "dot-dash", "dash-dot" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASH_DOT else SheetBorder.DASH_DOT
            "dot-dot-dash", "dash-dot-dot" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASH_DOT_DOT else SheetBorder.DASH_DOT_DOT
            else -> when {
                widthPt < 0.5f -> SheetBorder.HAIR
                widthPt < 1.4f -> SheetBorder.THIN
                widthPt < 2.4f -> SheetBorder.MEDIUM
                else -> SheetBorder.THICK
            }
        }
        return SheetBorder(style, color ?: Color.BLACK)
    }

    private fun odsDateSerial(v: String): Double? {
        val m = Regex("""^(-?\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?)?""").find(v.trim()) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        val days = cal.timeInMillis / 86_400_000L + 25569L
        val secs = (m.groupValues[4].toIntOrNull() ?: 0) * 3600 + (m.groupValues[5].toIntOrNull() ?: 0) * 60 + (m.groupValues[6].toIntOrNull() ?: 0)
        return days + secs / 86400.0
    }

    private fun odsDurationDays(v: String): Double? {
        val m = Regex("""^-?P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(v.trim()) ?: return null
        val d = (m.groupValues[1].toDoubleOrNull() ?: 0.0) + ((m.groupValues[2].toDoubleOrNull() ?: 0.0) * 3600 + (m.groupValues[3].toDoubleOrNull() ?: 0.0) * 60 + (m.groupValues[4].toDoubleOrNull() ?: 0.0)) / 86400.0
        return if (v.trim().startsWith("-")) -d else d
    }

    private fun odsConditionalNodes(content: ByteArray): Map<String, List<DmlNode>> {
        val out = HashMap<String, ArrayList<DmlNode>>()
        if (!String(content, StandardCharsets.ISO_8859_1).contains("conditional-format")) return out
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(content), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var table: String? = null
            var depth = 0
            val stack = ArrayList<DmlNode>()
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (local == "table" && stack.isEmpty()) { depth++; if (depth == 1) table = findAttrBySuffix(parser, "name") }
                        if (stack.isNotEmpty() || local == "conditional-format") {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfterLast(':')] = parser.getAttributeValue(i)
                            val node = DmlNode(local, attrs)
                            if (stack.isEmpty()) { table?.let { t -> out.getOrPut(t) { ArrayList() }.add(node) } } else stack.last().children.add(node)
                            stack.add(node)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        else if (local == "table") { depth--; if (depth == 0) table = null }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun odsFormula(expr: String): String {
        var e = expr.trim().removePrefix("of:").removePrefix("oooc:").removePrefix("msoxl:").removePrefix("=")
        e = Regex("""\[\$?(?:'((?:[^']|'')*)'|([^.\[\]':]*))\.(\$?[A-Za-z]{1,3}\$?\d+)(?::\$?(?:'(?:[^']|'')*'|[^.\[\]':]*)?\.(\$?[A-Za-z]{1,3}\$?\d+))?\]""").replace(e) { m ->
            val sheet = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val a = m.groupValues[3] + (if (m.groupValues[4].isNotEmpty()) ":" + m.groupValues[4] else "")
            if (sheet.isEmpty()) a else "'" + sheet.replace("'", "''") + "'!" + a
        }
        e = Regex("""\$?(?:'((?:[^']|'')*)'|([A-Za-z_][A-Za-z0-9_ ]*))\.(\$?[A-Za-z]{1,3}\$?\d+)""").replace(e) { m ->
            val sheet = m.groupValues[1].ifEmpty { m.groupValues[2] }
            "'" + sheet.replace("'", "''") + "'!" + m.groupValues[3]
        }
        e = e.replace(Regex("""\b(?:ORG\.OPENOFFICE|COM\.MICROSOFT)\.""", RegexOption.IGNORE_CASE), "")
        val sb = StringBuilder(e.length)
        var inStr = false
        var braceDepth = 0
        for (ch in e) {
            when {
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                inStr -> sb.append(ch)
                ch == '{' -> { braceDepth++; sb.append(ch) }
                ch == '}' -> { if (braceDepth > 0) braceDepth--; sb.append(ch) }
                ch == ';' && braceDepth == 0 -> sb.append(',')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun odsRanges(addr: String?): List<IntArray> {
        if (addr.isNullOrBlank()) return emptyList()
        val cleaned = addr.replace(Regex("""\$?(?:'(?:[^']|'')*'|[^\s.:']+)\."""), "").replace("$", "")
        return SheetConditionalFormats.parseSqref(cleaned)
    }

    private fun odsCfStyle(rawStyles: Map<String, OdsStyleRaw>, name: String): CfStyle? {
        val props = HashMap<String, String>()
        val chain = ArrayList<OdsStyleRaw>()
        var cur = rawStyles["table-cell/$name"]
        var guard = 0
        while (cur != null && guard++ < 32) {
            chain.add(cur)
            cur = cur.parent?.takeIf { it != "Default" }?.let { rawStyles["table-cell/$it"] }
        }
        if (chain.isEmpty()) return null
        for (st in chain.asReversed()) props.putAll(st.props)
        val all = odsBorder(props["border"])
        return CfStyle(
            fill = props["background-color"]?.let { odsColor(it) }?.let { SheetFill(it) },
            color = props["color"]?.let { odsColor(it) },
            bold = props["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 },
            italic = props["font-style"]?.let { it.equals("italic", true) || it.equals("oblique", true) },
            underline = props["text-underline-style"]?.let { if (it.equals("none", true)) 0 else 1 },
            strike = props["text-line-through-style"]?.let { !it.equals("none", true) },
            left = odsBorder(props["border-left"]) ?: all,
            right = odsBorder(props["border-right"]) ?: all,
            top = odsBorder(props["border-top"]) ?: all,
            bottom = odsBorder(props["border-bottom"]) ?: all
        )
    }

    private fun odsArgs(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                inStr -> sb.append(ch)
                ch == '(' -> { depth++; sb.append(ch) }
                ch == ')' -> { depth--; sb.append(ch) }
                depth == 0 && (ch == ',' || ch == ';') -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out
    }

    private fun odsConditionRule(cond: String, style: CfStyle?, priority: Int, ranges: List<IntArray>): CfRule? {
        val c = cond.trim().removePrefix("of:")
        fun inner(prefix: String): String = c.substring(prefix.length).trim().removePrefix("(").removeSuffix(")")
        return when {
            c.startsWith("cell-content-is-between") || c.startsWith("cell-content-is-not-between") -> {
                val args = odsArgs(inner(if (c.startsWith("cell-content-is-between")) "cell-content-is-between" else "cell-content-is-not-between")).map { odsFormula(it) }
                CfRule("cellIs", priority, operator = if (c.startsWith("cell-content-is-between")) "between" else "notBetween", formulas = args, style = style)
            }
            c.startsWith("cell-content()") -> {
                val rest = c.removePrefix("cell-content()").trim()
                val op = listOf("<=", ">=", "!=", "<>", "<", ">", "=").firstOrNull { rest.startsWith(it) } ?: return null
                val operator = when (op) { "<" -> "lessThan"; "<=" -> "lessThanOrEqual"; ">" -> "greaterThan"; ">=" -> "greaterThanOrEqual"; "=" -> "equal"; else -> "notEqual" }
                CfRule("cellIs", priority, operator = operator, formulas = listOf(odsFormula(rest.substring(op.length))), style = style)
            }
            c.startsWith("is-true-formula") || c.startsWith("formula-is") -> CfRule("expression", priority, formulas = listOf(odsFormula(inner(if (c.startsWith("formula-is")) "formula-is" else "is-true-formula"))), style = style)
            c.startsWith("begins-with") -> CfRule("beginsWith", priority, formulas = listOf(odsFormula(inner("begins-with"))), style = style)
            c.startsWith("ends-with") -> CfRule("endsWith", priority, formulas = listOf(odsFormula(inner("ends-with"))), style = style)
            c.startsWith("not-contains-text") -> CfRule("notContainsText", priority, formulas = listOf(odsFormula(inner("not-contains-text"))), style = style)
            c.startsWith("contains-text") -> CfRule("containsText", priority, formulas = listOf(odsFormula(inner("contains-text"))), style = style)
            c == "duplicate" -> CfRule("duplicateValues", priority, style = style)
            c == "unique" -> CfRule("uniqueValues", priority, style = style)
            c.startsWith("top-elements") -> CfRule("top10", priority, rank = inner("top-elements").toIntOrNull() ?: 10, style = style)
            c.startsWith("bottom-elements") -> CfRule("top10", priority, rank = inner("bottom-elements").toIntOrNull() ?: 10, bottom = true, style = style)
            c.startsWith("top-percent") -> CfRule("top10", priority, rank = inner("top-percent").toIntOrNull() ?: 10, percent = true, style = style)
            c.startsWith("bottom-percent") -> CfRule("top10", priority, rank = inner("bottom-percent").toIntOrNull() ?: 10, percent = true, bottom = true, style = style)
            c == "above-average" -> CfRule("aboveAverage", priority, style = style)
            c == "below-average" -> CfRule("aboveAverage", priority, aboveAverage = false, style = style)
            c == "above-equal-average" -> CfRule("aboveAverage", priority, equalAverage = true, style = style)
            c == "below-equal-average" -> CfRule("aboveAverage", priority, aboveAverage = false, equalAverage = true, style = style)
            c == "is-error" -> CfRule("containsErrors", priority, style = style)
            c == "is-no-error" -> CfRule("notContainsErrors", priority, style = style)
            else -> null
        }
    }

    private fun odsEntry(n: DmlNode): CfValueObject {
        val type = when (n.attr("type")) {
            "minimum" -> "min"; "maximum" -> "max"; "auto-minimum" -> "autoMin"; "auto-maximum" -> "autoMax"
            "percent" -> "percent"; "percentile" -> "percentile"; "formula" -> "formula"; else -> "num"
        }
        return CfValueObject(type, n.attr("value")?.let { odsFormula(it) })
    }

    private fun odsCalcextBlocks(nodes: List<DmlNode>, rawStyles: Map<String, OdsStyleRaw>, styleOf: (Map<String, OdsStyleRaw>, String) -> CfStyle?): List<CfBlock> {
        val out = ArrayList<CfBlock>()
        var priority = 0
        for (cf in nodes) {
            val ranges = odsRanges(cf.attr("target-range-address"))
            if (ranges.isEmpty()) continue
            val rules = ArrayList<CfRule>()
            for (ch in cf.children) {
                priority++
                when (ch.name) {
                    "condition" -> odsConditionRule(ch.attr("value") ?: continue, ch.attr("apply-style-name")?.let { styleOf(rawStyles, it) }, priority, ranges)?.let { rules.add(it) }
                    "date-is" -> rules.add(CfRule("timePeriod", priority, timePeriod = when (ch.attr("date")) {
                        "last-7-days" -> "last7Days"; "this-week" -> "thisWeek"; "last-week" -> "lastWeek"; "next-week" -> "nextWeek"
                        "this-month" -> "thisMonth"; "last-month" -> "lastMonth"; "next-month" -> "nextMonth"; else -> ch.attr("date")
                    }, style = ch.attr("style")?.let { styleOf(rawStyles, it) }))
                    "color-scale" -> {
                        val entries = ch.all("color-scale-entry")
                        rules.add(CfRule("colorScale", priority, cfvos = entries.map { odsEntry(it) }, colors = entries.mapNotNull { odsColor(it.attr("color")) }))
                    }
                    "data-bar" -> {
                        val entries = ch.all("formatting-entry")
                        rules.add(
                            CfRule(
                                "dataBar", priority,
                                cfvos = entries.map { odsEntry(it) },
                                colors = listOfNotNull(odsColor(ch.attr("positive-color"))),
                                barGradient = ch.attr("gradient") != "false",
                                barNegative = odsColor(ch.attr("negative-color")),
                                barAxisColor = odsColor(ch.attr("axis-color")),
                                barMinLength = (ch.attr("min-length")?.toFloatOrNull() ?: 0f) / 100f,
                                barMaxLength = (ch.attr("max-length")?.toFloatOrNull() ?: 100f) / 100f,
                                showValue = ch.attr("show-value") != "false"
                            )
                        )
                    }
                    "icon-set" -> {
                        rules.add(
                            CfRule(
                                "iconSet", priority,
                                cfvos = ch.all("formatting-entry").map { odsEntry(it) },
                                iconSet = ch.attr("icon-set-type") ?: "3TrafficLights1",
                                showValue = ch.attr("show-value") != "false"
                            )
                        )
                    }
                }
            }
            if (rules.isNotEmpty()) out.add(CfBlock(ranges, rules))
        }
        return out
    }

    private class OdsPending(
        val model: SheetModel,
        val grid: ArrayList<ArrayList<String>>,
        val formulas: Map<Long, String>,
        val raw: Map<Long, Any>,
        val flags: HashMap<Int, ByteArray>,
        val blocks: List<CfBlock>,
        val cellFormats: Map<Long, String>
    )

    private fun formatOdsRaw(v: Any, valueType: String?, numFmt: String?): String {
        return when (v) {
            is Boolean -> if (v) "TRUE" else "FALSE"
            is Double -> {
                if (!numFmt.isNullOrBlank()) {
                    val fmt = ExcelNumberFormat.format(v, 0, numFmt, false)
                    if (fmt.text.isNotEmpty()) return fmt.text
                }
                when (valueType) {
                    "percentage" -> ExcelNumberFormat.format(v, 0, "0.##%", false).text
                    "date" -> ExcelNumberFormat.format(v, 0, "yyyy-mm-dd", false).text
                    "time" -> ExcelNumberFormat.format(v, 0, "hh:mm:ss", false).text
                    "currency" -> ExcelNumberFormat.format(v, 0, "#,##0.00", false).text
                    else -> ExcelNumberFormat.general(v)
                }
            }
            else -> v.toString()
        }
    }

    private fun odsValue(t: OdsPending, row: Int, col: Int): Any? {
        t.raw[packCoord(row, col)]?.let { return it }
        val text = t.grid.getOrNull(row)?.getOrNull(col)
        if (text.isNullOrEmpty()) return null
        val fl = t.model.flags(row, col)
        if ((fl and SheetCellFlags.NUMBER) != 0) com.omnireader.core.engine.impl.ExcelFormula.parseNumber(text)?.let { return it }
        if ((fl and SheetCellFlags.BOOL) != 0) return text.equals("TRUE", true)
        return FormulaError.parse(text) ?: text
    }

    private fun finishOds(tables: List<OdsPending>) {
        val byName = tables.associateBy { it.model.name.lowercase(Locale.ROOT) }
        if (tables.any { it.formulas.isNotEmpty() }) {
            val wb = FormulaWorkbook(false)
            for (t in tables) wb.add(FormulaWorkbook.Sheet(t.model.name, t.grid.size, maxOf(1, t.model.colCount), t.formulas) { r, c -> odsValue(t, r, c) })
            for (t in tables) {
                for ((key, v) in wb.evaluateAll(t.model.name)) {
                    val r = (key ushr 32).toInt()
                    val c = (key and 0xFFFFFFFFL).toInt()
                    val row = t.grid.getOrNull(r) ?: continue
                    while (row.size <= c) row.add("")
                    when (v) {
                        is Double -> {
                            val nf = t.cellFormats[key]
                            row[c] = if (!nf.isNullOrBlank()) ExcelNumberFormat.format(v, 0, nf, false).text else ExcelNumberFormat.general(v)
                            rowFlags(t.flags, r, c, SheetCellFlags.NUMBER or SheetCellFlags.GENERAL)
                        }
                        is Boolean -> { row[c] = if (v) "TRUE" else "FALSE"; rowFlags(t.flags, r, c, SheetCellFlags.BOOL) }
                        is FormulaError -> { row[c] = v.code; rowFlags(t.flags, r, c, SheetCellFlags.ERROR) }
                        null -> row[c] = ""
                        else -> row[c] = v.toString()
                    }
                    if (v is Double && t.raw is HashMap<*, *>) @Suppress("UNCHECKED_CAST") (t.raw as HashMap<Long, Any>)[key] = v
                }
            }
        }
        for (t in tables) {
            if (t.blocks.isEmpty()) continue
            try {
                val cells = object : CfCells {
                    override fun value(row: Int, col: Int, sheet: String?): Any? {
                        val target = if (sheet == null) t else byName[sheet.lowercase(Locale.ROOT)] ?: return null
                        return odsValue(target, row, col)
                    }
                }
                SheetConditionalFormats.apply(t.blocks, t.model.rowCount, t.model.colCount, cells, { r, c -> t.model.baseStyle(r, c) }, false, t.model.conditional)
            } catch (_: Exception) {}
        }
    }

    private fun parseOds() {
        val fd = pfd ?: return
        try {
            val entries = OfficeZipContainerLoader.readZipEntries(
                pfd = fd,
                maxTotalBytes = 96 * 1024 * 1024L
            ) { normName ->
                normName.equals("content.xml", ignoreCase = true) ||
                        normName.equals("styles.xml", ignoreCase = true) ||
                        normName.equals("settings.xml", ignoreCase = true) ||
                        normName.equals("META-INF/manifest.xml", ignoreCase = true)
            }
            parseOdsEntries(entries)
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер ODS: ${e.localizedMessage}"
        }
    }

    private fun parseOdsEntries(rawEntries: Map<String, ByteArray>) {
        try {
            val entries = if (CleanRoomOdpEngine.isEncryptedOdf(rawEntries)) {
                if (password.isNullOrEmpty()) {
                    lastErrorMsg = "PASSWORD_REQUIRED: ODS spreadsheet is encrypted or password protected."
                    return
                }
                com.omnireader.core.engine.impl.CleanRoomOfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                    lastErrorMsg = "PASSWORD_REQUIRED: Incorrect password for ODS spreadsheet."
                    return
                }
            } else {
                rawEntries
            }

            val contentBytes = entries["content.xml"] ?: entries.entries.firstOrNull { it.key.equals("content.xml", ignoreCase = true) }?.value
            if (contentBytes == null) {
                lastErrorMsg = "В контейнере ODS отсутствует обязательный файл content.xml."
                return
            }
            val stylesBytes = entries["styles.xml"] ?: entries.entries.firstOrNull { it.key.equals("styles.xml", ignoreCase = true) }?.value
            val settingsBytes = entries["settings.xml"] ?: entries.entries.firstOrNull { it.key.equals("settings.xml", ignoreCase = true) }?.value

            val rawStyles = HashMap<String, OdsStyleRaw>()
            val fontFaces = HashMap<String, String>()
            val dataFormats = HashMap<String, String>()
            for (xmlSource in listOfNotNull(stylesBytes, contentBytes)) {
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(xmlSource), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var active: OdsStyleRaw? = null
                    var activeDataStyleName: String? = null
                    var activeDataSb: StringBuilder? = null
                    var activeDataTextTag: String? = null
                    var depthInBody = false
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val local = sp.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                        when (ev) {
                            XmlPullParser.START_TAG -> {
                                when (local) {
                                    "body" -> depthInBody = true
                                    "font-face" -> {
                                        val n = findAttrBySuffix(sp, "name")
                                        val fam = findAttrBySuffix(sp, "font-family")?.trim()?.trim('\'', '"')
                                        if (n != null && fam != null) fontFaces[n] = fam
                                    }
                                    "number-style", "currency-style", "percentage-style", "date-style", "time-style" -> {
                                        activeDataStyleName = findAttrBySuffix(sp, "name")
                                        activeDataSb = StringBuilder()
                                    }
                                    "number" -> activeDataSb?.let { sb ->
                                        val grp = findAttrBySuffix(sp, "grouping").equals("true", true)
                                        val minInt = (findAttrBySuffix(sp, "min-integer-digits")?.toIntOrNull() ?: 1).coerceIn(0, 12)
                                        val dec = (findAttrBySuffix(sp, "decimal-places")?.toIntOrNull() ?: 0).coerceIn(0, 10)
                                        if (grp) sb.append("#,##").append("0".repeat(maxOf(1, minInt))) else sb.append("0".repeat(maxOf(1, minInt)))
                                        if (dec > 0) sb.append('.').append("0".repeat(dec))
                                    }
                                    "year" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "yyyy" else "yy")
                                    "month" -> activeDataSb?.append(if (findAttrBySuffix(sp, "textual") == "true") (if (findAttrBySuffix(sp, "style") == "long") "mmmm" else "mmm") else (if (findAttrBySuffix(sp, "style") == "long") "mm" else "m"))
                                    "day" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "dd" else "d")
                                    "hours" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "hh" else "h")
                                    "minutes" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "mm" else "m")
                                    "seconds" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "ss" else "s")
                                    "am-pm" -> activeDataSb?.append("AM/PM")
                                    "currency-symbol", "text" -> if (activeDataSb != null) activeDataTextTag = local
                                    "style" -> {
                                        val n = findAttrBySuffix(sp, "name")
                                        val family = findAttrBySuffix(sp, "family") ?: ""
                                        active = if (n != null && rawStyles.size < 20000) OdsStyleRaw(family, findAttrBySuffix(sp, "parent-style-name"), findAttrBySuffix(sp, "data-style-name")).also { rawStyles["$family/$n"] = it } else null
                                    }
                                    "default-style" -> {
                                        val family = findAttrBySuffix(sp, "family") ?: ""
                                        active = OdsStyleRaw(family, null).also { rawStyles["$family/__default__"] = it }
                                    }
                                    "map" -> active?.let { st ->
                                        if (st.maps.size < 64) st.maps.add(arrayOf(findAttrBySuffix(sp, "condition"), findAttrBySuffix(sp, "apply-style-name"), findAttrBySuffix(sp, "base-cell-address")))
                                    }
                                    "table-cell-properties", "text-properties", "paragraph-properties", "table-column-properties", "table-row-properties", "table-properties" -> {
                                        val st = active
                                        if (st != null) {
                                            for (k in 0 until sp.attributeCount) {
                                                val an = sp.getAttributeName(k)?.substringAfterLast(':') ?: continue
                                                if (an.endsWith("-asian") || an.endsWith("-complex")) continue
                                                st.props[an] = sp.getAttributeValue(k)
                                            }
                                        }
                                    }
                                }
                                if (depthInBody && local == "table") break
                            }
                            XmlPullParser.TEXT -> {
                                val dt = activeDataTextTag
                                val sb = activeDataSb
                                if (dt != null && sb != null) {
                                    val t = sp.text ?: ""
                                    if (t == "%" || t == "/" || t == "-" || t == ":" || t == " " || t == "$" || t == "€" || t == "£") sb.append(t)
                                    else if (t.isNotEmpty()) sb.append('"').append(t.replace("\"", "")).append('"')
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (local == "style" || local == "default-style") active = null
                                else if (local == "currency-symbol" || local == "text") activeDataTextTag = null
                                else if (local in setOf("number-style", "currency-style", "percentage-style", "date-style", "time-style")) {
                                    val dn = activeDataStyleName
                                    val built = activeDataSb?.toString()
                                    if (dn != null && !built.isNullOrBlank()) dataFormats[dn] = built
                                    activeDataStyleName = null
                                    activeDataSb = null
                                    activeDataTextTag = null
                                }
                            }
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            fun resolveDataFormat(styleName: String?): String? {
                var cur = styleName?.let { rawStyles["table-cell/$it"] }
                var guard = 0
                while (cur != null && guard++ < 32) {
                    cur.dataStyleName?.let { dn -> dataFormats[dn]?.let { return it } }
                    cur = cur.parent?.let { rawStyles["table-cell/$it"] }
                }
                return null
            }

            fun mergedProps(family: String, name: String?): Map<String, String> {
                val chain = ArrayList<OdsStyleRaw>()
                var cur = name?.let { rawStyles["$family/$it"] }
                var guard = 0
                while (cur != null && guard++ < 32) {
                    chain.add(cur)
                    cur = cur.parent?.let { rawStyles["$family/$it"] }
                }
                val out = HashMap<String, String>()
                rawStyles["$family/__default__"]?.let { out.putAll(it.props) }
                for (st in chain.asReversed()) out.putAll(st.props)
                return out
            }

            val defaultCellProps = mergedProps("table-cell", "Default")
            val baseFontName = (defaultCellProps["font-name"]?.let { fontFaces[it] ?: it } ?: defaultCellProps["font-family"])?.trim('\'', '"') ?: "Liberation Sans"
            val baseFontSize = odsLengthPt(defaultCellProps["font-size"]) ?: 10f

            val cellStyleCache = HashMap<String, SheetCellStyle?>()
            fun cellStyle(name: String?): SheetCellStyle? {
                val key = name ?: "Default"
                return cellStyleCache.getOrPut(key) {
                    val pr = mergedProps("table-cell", key)
                    if (pr.isEmpty()) return@getOrPut null
                    val all = odsBorder(pr["border"])
                    val bold = pr["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 } == true
                    val rot = pr["rotation-angle"]?.removeSuffix("deg")?.trim()?.toFloatOrNull()?.toInt()?.let { ((it % 360) + 360) % 360 } ?: 0
                    val alignSourceFix = pr["text-align-source"]?.equals("fix", true) != false || pr.containsKey("text-align")
                    val st = SheetCellStyle(
                        fill = pr["background-color"]?.let { odsColor(it) },
                        textColor = pr["color"]?.let { odsColor(it) },
                        bold = bold,
                        italic = pr["font-style"]?.let { it.equals("italic", true) || it.equals("oblique", true) } == true,
                        underline = pr["text-underline-style"]?.let { if (it.equals("none", true)) 0 else if (pr["text-underline-type"].equals("double", true)) 2 else 1 } ?: 0,
                        strike = pr["text-line-through-style"]?.let { !it.equals("none", true) } == true,
                        fontSize = odsLengthPt(pr["font-size"]) ?: 0f,
                        fontName = (pr["font-name"]?.let { fontFaces[it] ?: it } ?: pr["font-family"])?.trim('\'', '"'),
                        script = pr["text-position"]?.let { if (it.startsWith("super")) 1 else if (it.startsWith("sub")) 2 else if (it.startsWith("-")) 2 else if (it.firstOrNull()?.isDigit() == true && !it.startsWith("0")) 1 else 0 } ?: 0,
                        hAlign = if (!alignSourceFix) SheetCellStyle.H_GENERAL else when (pr["text-align"]?.lowercase(Locale.ROOT)) {
                            "start", "left" -> SheetCellStyle.H_LEFT
                            "center" -> SheetCellStyle.H_CENTER
                            "end", "right" -> SheetCellStyle.H_RIGHT
                            "justify" -> SheetCellStyle.H_JUSTIFY
                            else -> SheetCellStyle.H_GENERAL
                        },
                        vAlign = when (pr["vertical-align"]?.lowercase(Locale.ROOT)) {
                            "top" -> SheetCellStyle.V_TOP
                            "middle" -> SheetCellStyle.V_CENTER
                            else -> SheetCellStyle.V_BOTTOM
                        },
                        wrap = pr["wrap-option"].equals("wrap", true),
                        rotation = when {
                            rot == 0 -> 0
                            rot <= 90 -> rot
                            rot >= 270 -> 90 + (360 - rot)
                            rot < 180 -> 90
                            else -> 180
                        },
                        shrink = pr["shrink-to-fit"].equals("true", true),
                        left = odsBorder(pr["border-left"]) ?: all,
                        right = odsBorder(pr["border-right"]) ?: all,
                        top = odsBorder(pr["border-top"]) ?: all,
                        bottom = odsBorder(pr["border-bottom"]) ?: all,
                        diagonalDown = odsBorder(pr["diagonal-tl-br"]),
                        diagonalUp = odsBorder(pr["diagonal-bl-tr"])
                    )
                    val plain = SheetCellStyle(fontSize = baseFontSize, fontName = baseFontName)
                    val normalized = st.copy(
                        fontSize = if (st.fontSize == 0f) baseFontSize else st.fontSize,
                        fontName = st.fontName ?: baseFontName
                    )
                    if (normalized == plain) null else normalized
                }
            }

            var showGrid = true
            settingsBytes?.let { bytes ->
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var inShowGrid = false
                    loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> inShowGrid = sp.name?.endsWith("config-item") == true && findAttrBySuffix(sp, "name") == "ShowGrid"
                            XmlPullParser.TEXT -> if (inShowGrid) { showGrid = !sp.text.trim().equals("false", true); break@loop }
                            XmlPullParser.END_TAG -> inShowGrid = false
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            val calcext = odsConditionalNodes(contentBytes)
            val pendingTables = ArrayList<OdsPending>()
            val defaultRowPx = Math.round(baseFontSize * 1.28f * 4f) / 4f * PX_PER_PT
            val defaultColPx = 2.258f * 28.3465f * PX_PER_PT

            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var currentTableName = "Sheet 1"
            var currentGrid = ArrayList<ArrayList<String>>()
            var currentStyles = HashMap<Long, SheetCellStyle>()
            var currentFormulas = HashMap<Long, String>()
            var currentCellFormats = HashMap<Long, String>()
            var currentColWidths = ArrayList<Float>()
            var currentColStyles = HashMap<Int, SheetCellStyle>()
            var currentRowHeights = HashMap<Int, Float>()
            var currentFlags = HashMap<Int, ByteArray>()
            var currentMerges = ArrayList<MergedCellRegion>()
            var currentRowStyleHeight: Float? = null
            var currentRow: ArrayList<String>? = null
            var currentRowFlags = HashMap<Int, Int>()
            var currentRaw = HashMap<Long, Any>()
            var currentRowRaw = HashMap<Int, Any>()
            var currentRowFormulas = HashMap<Int, String>()
            var currentRowFormats = HashMap<Int, String>()
            var currentMapCells = HashMap<String, ArrayList<IntArray>>()
            var currentRowMapCells = HashMap<Int, String>()
            var currentCellRaw: Any? = null
            var currentCellValueType: String? = null
            var currentCellStyleName: String? = null
            var currentCellExplicitStyle = false
            var currentRowCellStyles = HashMap<Int, SheetCellStyle>()
            var currentRowRepeat = 1
            var currentCellSb: StringBuilder? = null
            var currentCellFormula: String? = null
            var currentCellKind = 0
            var currentColRepeat = 1
            var currentColSpan = 1
            var currentRowSpan = 1
            var currentCellStyle: SheetCellStyle? = null
            var paragraphs = 0
            var annotationDepth = 0
            var inTable = false
            var inCell = false
            var tableDepth = 0

            while (ev != XmlPullParser.END_DOCUMENT && sheets.size < 64) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (annotationDepth > 0) {
                            annotationDepth++
                        } else when (local) {
                            "annotation" -> if (inCell) annotationDepth = 1
                            "table" -> {
                                tableDepth++
                                if (tableDepth == 1) {
                                    inTable = true
                                    currentTableName = findAttrBySuffix(parser, "name") ?: "Sheet ${sheets.size + 1}"
                                    currentGrid = ArrayList()
                                    currentStyles = HashMap()
                                    currentFormulas = HashMap()
                                    currentCellFormats = HashMap()
                                    currentColWidths = ArrayList()
                                    currentColStyles = HashMap()
                                    currentRowHeights = HashMap()
                                    currentFlags = HashMap()
                                    currentMerges = ArrayList()
                                    currentRaw = HashMap()
                                    currentMapCells = HashMap()
                                }
                            }
                            "table-column" -> if (inTable && tableDepth == 1) {
                                val colStyleName = findAttrBySuffix(parser, "style-name")
                                val repeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                val colProps = mergedProps("table-column", colStyleName)
                                val hidden = findAttrBySuffix(parser, "visibility")?.let { it == "collapse" || it == "filter" } == true
                                val wPx = if (hidden) 0f else odsLengthPt(colProps["column-width"])?.let { it * PX_PER_PT } ?: defaultColPx
                                val defCell = findAttrBySuffix(parser, "default-cell-style-name")?.let { cellStyle(it) }
                                repeat(repeat) {
                                    if (currentColWidths.size < MAX_ODS_COLS) {
                                        if (defCell != null && (defCell.fill != null || defCell.hasBorders)) currentColStyles[currentColWidths.size] = defCell
                                        currentColWidths.add(wPx)
                                    }
                                }
                            }
                            "table-row" -> if (inTable && tableDepth == 1 && currentGrid.size < MAX_ODS_ROWS) {
                                currentRow = ArrayList()
                                currentRowFlags = HashMap()
                                currentRowRaw = HashMap()
                                currentRowFormulas = HashMap()
                                currentRowFormats = HashMap()
                                currentRowMapCells = HashMap()
                                currentRowCellStyles = HashMap()
                                currentRowRepeat = (findAttrBySuffix(parser, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_ROWS)
                                val rowProps = mergedProps("table-row", findAttrBySuffix(parser, "style-name"))
                                val hidden = findAttrBySuffix(parser, "visibility")?.let { it == "collapse" || it == "filter" } == true
                                currentRowStyleHeight = if (hidden) 0f else odsLengthPt(rowProps["row-height"])?.let { it * PX_PER_PT }
                            }
                            "table-cell", "covered-table-cell" -> if (currentRow != null && tableDepth == 1) {
                                inCell = true
                                paragraphs = 0
                                currentCellSb = StringBuilder()
                                currentColRepeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                currentColSpan = (findAttrBySuffix(parser, "number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                currentRowSpan = (findAttrBySuffix(parser, "number-rows-spanned")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_ROWS)
                                val colIdx = currentRow!!.size
                                val explicit = findAttrBySuffix(parser, "style-name")
                                currentCellExplicitStyle = explicit != null
                                currentCellStyle = if (explicit != null) cellStyle(explicit) else currentColStyles[colIdx]
                                currentCellStyleName = explicit
                                val vType = findAttrBySuffix(parser, "value-type")
                                currentCellValueType = vType
                                currentCellRaw = when (vType) {
                                    "float", "percentage", "currency" -> findAttrBySuffix(parser, "value")?.toDoubleOrNull()
                                    "date" -> findAttrBySuffix(parser, "date-value")?.let { odsDateSerial(it) }
                                    "time" -> findAttrBySuffix(parser, "time-value")?.let { odsDurationDays(it) }
                                    "boolean" -> findAttrBySuffix(parser, "boolean-value")?.let { it.equals("true", true) }
                                    else -> null
                                }
                                currentCellFormula = findAttrBySuffix(parser, "formula")
                                currentCellKind = when (vType) {
                                    "float", "percentage", "currency", "date", "time" -> SheetCellFlags.NUMBER
                                    "boolean" -> SheetCellFlags.BOOL
                                    else -> 0
                                }
                            }
                            "p", "h" -> if (inCell) {
                                val sb = currentCellSb
                                if (sb != null && paragraphs > 0) sb.append('\n')
                                paragraphs++
                            }
                            "s" -> if (inCell) {
                                val n = (findAttrBySuffix(parser, "c")?.toIntOrNull() ?: 1).coerceIn(1, 256)
                                currentCellSb?.let { sb -> if (sb.length < 32768) repeat(n) { sb.append(' ') } }
                            }
                            "tab" -> if (inCell) currentCellSb?.append('\t')
                            "line-break" -> if (inCell) currentCellSb?.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCell && annotationDepth == 0 && currentCellSb != null && currentCellSb.length < 32768) {
                            currentCellSb.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (annotationDepth > 0) {
                            annotationDepth--
                        } else when (local) {
                            "table-cell", "covered-table-cell" -> {
                                val row = currentRow
                                if (inCell && row != null) {
                                    val dataFmt = resolveDataFormat(currentCellStyleName)
                                    var txt = currentCellSb?.toString() ?: ""
                                    if (txt.isEmpty() && currentCellRaw != null && currentCellFormula.isNullOrBlank()) {
                                        txt = formatOdsRaw(currentCellRaw!!, currentCellValueType, dataFmt)
                                    }
                                    val styleObj = currentCellStyle
                                    val visual = currentCellExplicitStyle && styleObj != null && (styleObj.fill != null || styleObj.hasBorders)
                                    val targetRowIdx = currentGrid.size

                                    if ((currentColSpan > 1 || currentRowSpan > 1) && currentMerges.size < 4096) {
                                        val c0 = row.size
                                        val c1 = (c0 + currentColSpan - 1).coerceAtMost(MAX_ODS_COLS - 1)
                                        val r0 = targetRowIdx
                                        val r1 = (r0 + currentRowSpan - 1).coerceAtMost(MAX_ODS_ROWS - 1)
                                        if (c1 > c0 || r1 > r0) {
                                            currentMerges.add(MergedCellRegion(r0, r1, c0, c1))
                                        }
                                    }

                                    val effRepeat = if (txt.isEmpty() && currentCellFormula.isNullOrBlank() && !visual && currentColRepeat > 64) {
                                        minOf(currentColRepeat, MAX_ODS_COLS - row.size)
                                    } else {
                                        currentColRepeat
                                    }
                                    repeat(effRepeat) {
                                        if (row.size < MAX_ODS_COLS) {
                                            val colIdx = row.size
                                            row.add(txt)
                                            if (styleObj != null && (txt.isNotEmpty() || visual)) currentRowCellStyles[colIdx] = styleObj
                                            if (currentCellKind != 0 && txt.isNotEmpty()) currentRowFlags[colIdx] = currentCellKind
                                            currentCellRaw?.let { currentRowRaw[colIdx] = it }
                                            val sn = currentCellStyleName
                                            if (sn != null && rawStyles["table-cell/$sn"]?.maps?.isNotEmpty() == true) currentRowMapCells[colIdx] = sn
                                            if (dataFmt != null) currentRowFormats[colIdx] = dataFmt
                                            if (!currentCellFormula.isNullOrBlank() && (txt.isEmpty() || txt.startsWith("#") || txt.equals("Err:", true))) {
                                                currentRowFormulas[colIdx] = currentCellFormula!!
                                            }
                                        }
                                    }
                                }
                                inCell = false
                                currentCellSb = null
                                currentCellFormula = null
                            }
                            "table-row" -> {
                                val row = currentRow
                                if (row != null) {
                                    while (row.isNotEmpty() && row.last().isEmpty()) {
                                        val colIdx = row.lastIndex
                                        if (currentRowCellStyles.containsKey(colIdx) || currentRowFormulas.containsKey(colIdx)) break
                                        row.removeAt(row.lastIndex)
                                    }
                                    val rep = currentRowRepeat.coerceAtMost(MAX_ODS_ROWS - currentGrid.size)
                                    for (k in 0 until rep) {
                                        val rIdx = currentGrid.size
                                        currentGrid.add(if (k == 0) row else ArrayList(row))
                                        currentRowStyleHeight?.let { currentRowHeights[rIdx] = it }
                                        if (currentStyles.size < 250_000) for ((c, st) in currentRowCellStyles) currentStyles[packCoord(rIdx, c)] = st
                                        for ((c, f) in currentRowFlags) rowFlags(currentFlags, rIdx, c, f)
                                        if (currentRaw.size < 1_000_000) for ((c, v) in currentRowRaw) currentRaw[packCoord(rIdx, c)] = v
                                        if (currentFormulas.size < 100_000) for ((c, fm) in currentRowFormulas) currentFormulas[packCoord(rIdx, c)] = fm
                                        if (currentCellFormats.size < 250_000) for ((c, nf) in currentRowFormats) currentCellFormats[packCoord(rIdx, c)] = nf
                                        for ((c, sn) in currentRowMapCells) currentMapCells.getOrPut(sn) { ArrayList() }.let { if (it.size < 100_000) it.add(intArrayOf(rIdx, rIdx, c, c)) }
                                        if (row.isEmpty() && currentRowCellStyles.isEmpty() && currentRowFormulas.isEmpty() && k > 0 && rep > 64) {
                                            val remaining = rep - k - 1
                                            for (m in 0 until remaining) {
                                                val rr = currentGrid.size
                                                currentGrid.add(ArrayList())
                                                currentRowStyleHeight?.let { currentRowHeights[rr] = it }
                                            }
                                            break
                                        }
                                    }
                                }
                                currentRow = null
                            }
                            "table" -> {
                                tableDepth--
                                if (tableDepth == 0) {
                                    while (currentGrid.isNotEmpty() && currentGrid.last().isEmpty()) {
                                        currentGrid.removeAt(currentGrid.lastIndex)
                                    }
                                    if (currentGrid.isNotEmpty()) {
                                        val maxDataCols = currentGrid.maxOfOrNull { it.size } ?: 0
                                        val widthsArr = if (maxDataCols > 0) {
                                            FloatArray(maxDataCols) { c -> currentColWidths.getOrElse(c) { defaultColPx } }
                                        } else null
                                        val odsModel =
                                            SheetModel(
                                                name = currentTableName,
                                                rows = currentGrid,
                                                styles = currentStyles,
                                                colWidthsPx = widthsArr,
                                                mergedRegions = currentMerges,
                                                rowHeightsPx = FloatArray(currentGrid.size) { r -> currentRowHeights[r] ?: defaultRowPx },
                                                flags = currentFlags,
                                                colStyles = currentColStyles,
                                                showGridLines = showGrid,
                                                defaultFontName = baseFontName,
                                                defaultFontSize = baseFontSize,
                                                defaultRowHeightPx = defaultRowPx,
                                                defaultColWidthPx = defaultColPx
                                            )
                                        sheets.add(odsModel)
                                        val blocks = ArrayList<CfBlock>()
                                        try {
                                            calcext[currentTableName]?.let { blocks.addAll(odsCalcextBlocks(it, rawStyles, ::odsCfStyle)) }
                                            if (blocks.isEmpty()) {
                                                for ((sn, cellsList) in currentMapCells) {
                                                    val maps = rawStyles["table-cell/$sn"]?.maps ?: continue
                                                    val rules = maps.mapIndexedNotNull { i, m -> odsConditionRule(m[0] ?: return@mapIndexedNotNull null, m[1]?.let { odsCfStyle(rawStyles, it) }, i, emptyList()) }
                                                    if (rules.isNotEmpty()) blocks.add(CfBlock(cellsList, rules))
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        pendingTables.add(OdsPending(odsModel, currentGrid, currentFormulas.mapValues { odsFormula(it.value) }, currentRaw, currentFlags, blocks, currentCellFormats))
                                    }
                                    inTable = false
                                }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            finishOds(pendingTables)
            if (sheets.isEmpty()) {
                lastErrorMsg = "Таблица ODS пуста или не содержит читаемых ячеек."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер ODS: ${e.localizedMessage}"
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd?.close() } catch (_: Exception) {}
    }
}

class Rfc4180CsvTsvSession(
    private val pfd: ParcelFileDescriptor,
    private val ext: String
) : SpreadsheetGridSession() {
    init {
        parseStreamingRfc4180()
    }

    private fun parseStreamingRfc4180() {
        try {
            val fileSize = resolveDescriptorSize(pfd.fileDescriptor)
            val sampleLen = minOf(if (fileSize > 0L) fileSize else 16384L, 16384L).toInt()
            val sampleBytes = if (sampleLen > 0) {
                val buf = ByteBuffer.allocateDirect(sampleLen)
                val r = try { Os.pread(pfd.fileDescriptor, buf, 0L) } catch (_: Throwable) { 0 }
                if (r > 0) {
                    val arr = ByteArray(r)
                    buf.flip()
                    buf.get(arr)
                    arr
                } else ByteArray(0)
            } else ByteArray(0)
            val det = CleanRoomCsvParser.detectFromSample(sampleBytes, sampleBytes.size, ext)
            val stream = OfficePreadInputStream(pfd.fileDescriptor, fileSize)
            repeat(det.bomSkip) { stream.read() }
            sheets.add(CleanRoomCsvParser.parseStream(stream, det, ext))
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения таблицы ${ext.uppercase(Locale.ROOT)}: ${e.localizedMessage}"
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}