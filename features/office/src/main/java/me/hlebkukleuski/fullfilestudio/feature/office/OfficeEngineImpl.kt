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
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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

internal object OoxmlDateStyleHelper {
    private val BUILTIN_DATE_FMT_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 30, 36, 45, 46, 47, 50, 57
    )
    private val BUILTIN_TIME_ONLY_IDS = setOf(18, 19, 20, 21, 45, 46, 47)
    private val BUILTIN_PERCENT_FMT_IDS = setOf(9, 10)
    private val US_SYMBOLS = DecimalFormatSymbols(Locale.US)

    fun isDateFormat(numFmtId: Int, formatCode: String?): Boolean {
        if (numFmtId in BUILTIN_DATE_FMT_IDS) return true
        if (formatCode.isNullOrBlank()) return false
        val cleaned = formatCode
            .substringBefore(';')
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("\\\\."), "")
            .lowercase(Locale.ROOT)
        if (cleaned.any { it in setOf('y', 'd', 'h', 's') }) return true
        return cleaned.contains("mm")
    }

    fun isPercentFormat(numFmtId: Int, formatCode: String?): Boolean {
        if (numFmtId in BUILTIN_PERCENT_FMT_IDS) return true
        if (formatCode.isNullOrBlank()) return false
        return formatCode.substringBefore(';').contains('%')
    }

    fun formatPercent(value: Double, numFmtId: Int, formatCode: String?): String {
        if (!value.isFinite()) return ""
        val pct = value * 100.0
        val hasDecimals = numFmtId == 10 || (formatCode != null && formatCode.contains(".0"))
        return if (hasDecimals) {
            String.format(Locale.US, "%.2f%%", pct)
        } else {
            val rounded = pct.roundToLong()
            if (abs(pct - rounded) < 1e-4) "${rounded}%" else String.format(Locale.US, "%.2f%%", pct)
        }
    }

    fun formatNumericWithStyle(value: Double, numFmtId: Int, formatCode: String?): String {
        if (!value.isFinite()) return ""
        return when (numFmtId) {
            1 -> DecimalFormat("0", US_SYMBOLS).format(value)
            2 -> DecimalFormat("0.00", US_SYMBOLS).format(value)
            3, 37, 38 -> DecimalFormat("#,##0", US_SYMBOLS).format(value)
            4, 39, 40 -> DecimalFormat("#,##0.00", US_SYMBOLS).format(value)
            5, 6 -> "$" + DecimalFormat("#,##0", US_SYMBOLS).format(value)
            7, 8, 44 -> "$" + DecimalFormat("#,##0.00", US_SYMBOLS).format(value)
            11, 48 -> String.format(Locale.US, "%.2E", value)
            else -> {
                if (!formatCode.isNullOrBlank()) {
                    val primary = formatCode.substringBefore(';')
                    val currencyPrefix = when {
                        primary.contains("₽") || primary.contains("[$₽") -> "₽"
                        primary.contains("€") || primary.contains("[$€") -> "€"
                        primary.contains("£") || primary.contains("[$£") -> "£"
                        primary.contains("¥") || primary.contains("[$¥") -> "¥"
                        primary.contains("$") -> "$"
                        else -> ""
                    }
                    val hasThousands = primary.contains("#,##0")
                    val decMatch = Regex("\\.(0+)").find(primary)
                    val decPlaces = decMatch?.groupValues?.get(1)?.length ?: 0
                    if (hasThousands || decPlaces > 0 || currencyPrefix.isNotEmpty()) {
                        val pattern = buildString {
                            if (hasThousands) append("#,##0") else append("0")
                            if (decPlaces > 0) {
                                append('.')
                                repeat(decPlaces.coerceAtMost(6)) { append('0') }
                            }
                        }
                        return try {
                            val formatted = DecimalFormat(pattern, US_SYMBOLS).format(value)
                            if (currencyPrefix.isNotEmpty()) "$currencyPrefix$formatted" else formatted
                        } catch (_: Throwable) {
                            formatCleanNumber(value)
                        }
                    }
                }
                formatCleanNumber(value)
            }
        }
    }

    fun formatCleanNumber(value: Double): String {
        if (!value.isFinite()) return ""
        val asLong = value.toLong()
        if (value == asLong.toDouble()) return asLong.toString()
        val rounded = Math.round(value * 1e10) / 1e10
        val rLong = rounded.toLong()
        return if (rounded == rLong.toDouble()) rLong.toString() else rounded.toString()
    }

    fun formatSerialDate(
        serial: Double,
        date1904: Boolean,
        numFmtId: Int = 14,
        formatCode: String? = null
    ): String {
        if (!serial.isFinite() || serial < 0.0 || serial > 2958465.0) {
            return formatCleanNumber(serial)
        }
        var days = serial.toInt()
        val frac = (serial - days).coerceIn(0.0, 0.9999999)
        val totalSecs = (frac * 86400.0).roundToLong().toInt().coerceIn(0, 86399)
        val hh = totalSecs / 3600
        val mm = (totalSecs % 3600) / 60
        val ss = totalSecs % 60

        val isTimeOnly = numFmtId in BUILTIN_TIME_ONLY_IDS ||
                (days == 0 && totalSecs > 0) ||
                (formatCode != null && !formatCode.lowercase(Locale.ROOT).any { it == 'y' || it == 'd' } &&
                        formatCode.lowercase(Locale.ROOT).any { it == 'h' || it == 's' })

        if (isTimeOnly && days == 0) {
            return if (ss > 0 || numFmtId in setOf(19, 21, 46, 47)) {
                String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
            } else {
                String.format(Locale.US, "%02d:%02d", hh, mm)
            }
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        if (date1904) {
            cal.set(1904, Calendar.JANUARY, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, days)
        } else {
            if (days >= 60) days -= 1
            cal.set(1899, Calendar.DECEMBER, 31, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, days)
        }
        cal.add(Calendar.SECOND, totalSecs)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        return if (totalSecs >= 60) {
            String.format(Locale.US, "%04d-%02d-%02d %02d:%02d", y, m, d, hour, min)
        } else {
            String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
        }
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
                    throw SecurityException("PASSWORD_REQUIRED: Документ защищён паролем.")
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

    fun createSessionForExtension(fd: FileDescriptor, extension: String): DocumentSession {
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
            DetectedFormat.PPTX -> return PptxPresentationSession(ParcelFileDescriptor.dup(fd))
            DetectedFormat.ODP -> return OdpPresentationSession(ParcelFileDescriptor.dup(fd))
            DetectedFormat.XLSX -> return OoxmlSpreadsheetSession(ParcelFileDescriptor.dup(fd))
            DetectedFormat.ODS -> return OdsSpreadsheetSession(ParcelFileDescriptor.dup(fd))
            DetectedFormat.DOCX, DetectedFormat.ODT, DetectedFormat.EPUB, DetectedFormat.FB2,
            DetectedFormat.AZW3, DetectedFormat.MOBI, DetectedFormat.KINDLE_DRM, DetectedFormat.KFX -> {
                return DocumentRendererEngineImpl().createSessionForFormat(fd, detected, ext)
            }
            DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT, DetectedFormat.RTF, DetectedFormat.ONE -> {
                return CleanRoomLegacyAndIsoEngine.createSession(fd, detected, ext)
            }
            else -> {}
        }

        if (ext in setOf("csv", "tsv") || (ext in setOf("xls", "xlt") && detected == DetectedFormat.PLAINTEXT)) {
            return Rfc4180CsvTsvSession(ParcelFileDescriptor.dup(fd), if (ext == "tsv") "tsv" else "csv")
        }

        if (ext in setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "one")) {
            return CleanRoomLegacyAndIsoEngine.createSession(fd, detected, ext)
        }

        val pfd = ParcelFileDescriptor.dup(fd)
        return when (ext) {
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> PptxPresentationSession(pfd)
            "odp", "otp" -> OdpPresentationSession(pfd)
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> OoxmlSpreadsheetSession(pfd)
            "ods", "ots" -> OdsSpreadsheetSession(pfd)
            else -> DocumentRendererEngineImpl().createSessionForFormat(pfd.fileDescriptor, detected, ext).also {
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }
}

class PptxPresentationSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    data class ChartSeries(
        val name: String,
        val categories: List<String>,
        val values: List<Float>
    )

    sealed class CustomPathCmd {
        data class MoveTo(val x: Float, val y: Float) : CustomPathCmd()
        data class LineTo(val x: Float, val y: Float) : CustomPathCmd()
        data class CubicBezTo(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val x3: Float, val y3: Float
        ) : CustomPathCmd()
        data class QuadBezTo(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float
        ) : CustomPathCmd()
        data object Close : CustomPathCmd()
    }

    data class CustomVectorPath(
        val pathWidth: Float,
        val pathHeight: Float,
        val commands: List<CustomPathCmd>
    )

    data class SlideTextRun(
        val text: String,
        val fontSizePt: Float? = null,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val textColor: Int? = null
    )

    data class SlideParagraph(
        val text: String,
        val fontSizePt: Float? = null,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val textColor: Int? = null,
        val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        val isBullet: Boolean = false,
        val runs: List<SlideTextRun> = emptyList()
    )

    data class SlideTableCell(
        val text: String,
        val fillColor: Int? = null,
        val textColor: Int? = null,
        val isBold: Boolean = false,
        val gridSpan: Int = 1,
        val rowSpan: Int = 1,
        val isMergedContinuation: Boolean = false
    )

    data class PlaceholderStyleSpec(
        val rect: RectF?,
        val fillColor: Int? = null,
        val strokeColor: Int? = null,
        val textColor: Int? = null,
        val fontSizePt: Float? = null,
        val alignment: Layout.Alignment? = null
    )

    sealed class SlideVisualElement {
        data class ShapeBox(
            val shapeId: String = "",
            val rect: RectF,
            val presetGeom: String,
            val customPaths: List<CustomVectorPath> = emptyList(),
            val fillColor: Int?,
            val fillEndColor: Int? = null,
            val fillMediaPath: String? = null,
            val gradAngleDeg: Float = 90f,
            val strokeColor: Int?,
            val strokeWidthPt: Float = 1.5f,
            val isDashedStroke: Boolean = false,
            val headArrowType: String = "none",
            val tailArrowType: String = "none",
            val rotationDeg: Float = 0f,
            val flipH: Boolean = false,
            val flipV: Boolean = false,
            val hasDropShadow: Boolean = false,
            val paragraphs: List<String>,
            val richParagraphs: List<SlideParagraph> = emptyList(),
            val isTitle: Boolean,
            val fontSizePt: Float? = null,
            val textColor: Int? = null
        ) : SlideVisualElement()

        data class TableBox(
            val rect: RectF,
            val rows: List<List<String>>,
            val richRows: List<List<SlideTableCell>> = emptyList()
        ) : SlideVisualElement()

        data class PictureBox(
            val shapeId: String = "",
            val rect: RectF,
            val mediaPath: String,
            val cropLeft: Float = 0f,
            val cropTop: Float = 0f,
            val cropRight: Float = 0f,
            val cropBottom: Float = 0f,
            val strokeColor: Int? = null,
            val strokeWidthPt: Float = 0f,
            val presetGeom: String = "rect",
            val customPaths: List<CustomVectorPath> = emptyList(),
            val rotationDeg: Float = 0f,
            val flipH: Boolean = false,
            val flipV: Boolean = false,
            val hasDropShadow: Boolean = false
        ) : SlideVisualElement()

        data class ChartBox(
            val rect: RectF,
            val title: String,
            val chartKind: String,
            val seriesList: List<ChartSeries>
        ) : SlideVisualElement()
    }

    data class ParsedSlide(
        val index: Int,
        val backgroundColor: Int,
        val backgroundEndColor: Int? = null,
        val backgroundMediaPath: String?,
        val transitionType: String = "fade",
        val transitionDurationMs: Int = 320,
        val animatedTargetIds: List<String> = emptyList(),
        val speakerNotes: String = "",
        val elements: List<SlideVisualElement>,
        val plainTextSummary: String
    )

    private data class GroupTransformFrame(
        var offX: Float = 0f,
        var offY: Float = 0f,
        var extW: Float = 0f,
        var extH: Float = 0f,
        var chOffX: Float = 0f,
        var chOffY: Float = 0f,
        var chExtW: Float = 0f,
        var chExtH: Float = 0f
    ) {
        fun mapChildRect(localRect: RectF): RectF {
            if (extW <= 0f || extH <= 0f || chExtW <= 0f || chExtH <= 0f) return localRect
            val sx = extW / chExtW
            val sy = extH / chExtH
            val l = offX + (localRect.left - chOffX) * sx
            val t = offY + (localRect.top - chOffY) * sy
            val r = offX + (localRect.right - chOffX) * sx
            val b = offY + (localRect.bottom - chOffX) * sy
            return RectF(l, t, r, b)
        }
    }

    private data class SlideMasterOrLayoutContext(
        val backgroundColor: Int?,
        val backgroundEndColor: Int? = null,
        val backgroundMediaPath: String?,
        val decorativeElements: List<SlideVisualElement>,
        val placeholderStyles: Map<String, PlaceholderStyleSpec>,
        val colorMapOverrides: Map<String, String> = emptyMap(),
        val masterTitleTextColor: Int? = null,
        val masterBodyTextColor: Int? = null,
        val masterTitleFontSizePt: Float? = null,
        val masterBodyFontSizePt: Float? = null
    )

    private var slideWidthEmu = 12_192_000f
    private var slideHeightEmu = 6_858_000f
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f
    private val slides = mutableListOf<ParsedSlide>()
    private val mediaBytesMap = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
    private val themeColors = HashMap<String, Int>()
    private val parsedLayoutCache = HashMap<String, SlideMasterOrLayoutContext>()
    private val parsedMasterCache = HashMap<String, SlideMasterOrLayoutContext>()

    private val decodedBitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    init {
        initDefaultThemeColors()
        parsePptxContainer()
    }

    private fun initDefaultThemeColors() {
        themeColors["dk1"] = Color.rgb(0, 0, 0)
        themeColors["lt1"] = Color.rgb(255, 255, 255)
        themeColors["dk2"] = Color.rgb(31, 73, 125)
        themeColors["lt2"] = Color.rgb(238, 236, 225)
        themeColors["accent1"] = Color.rgb(79, 129, 189)
        themeColors["accent2"] = Color.rgb(192, 80, 77)
        themeColors["accent3"] = Color.rgb(155, 187, 89)
        themeColors["accent4"] = Color.rgb(128, 100, 162)
        themeColors["accent5"] = Color.rgb(75, 172, 198)
        themeColors["accent6"] = Color.rgb(247, 150, 70)
        themeColors["tx1"] = themeColors["dk1"]!!
        themeColors["tx2"] = themeColors["dk2"]!!
        themeColors["bg1"] = themeColors["lt1"]!!
        themeColors["bg2"] = themeColors["lt2"]!!
    }

    private fun parseThemeXml(themeBytes: ByteArray) {
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(themeBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inClrScheme = false
            var currentSlot: String? = null
            val slotNames = setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folhlink")

            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (local == "clrscheme") {
                            inClrScheme = true
                        } else if (inClrScheme && local in slotNames) {
                            currentSlot = local
                        } else if (inClrScheme && currentSlot != null) {
                            if (local == "srgbclr") {
                                val hex = getAttrIgnoreCase(parser, "val")
                                if (!hex.isNullOrBlank() && hex.length == 6) {
                                    try { themeColors[currentSlot] = Color.parseColor("#$hex") } catch (_: Exception) {}
                                }
                            } else if (local == "sysclr") {
                                val hex = getAttrIgnoreCase(parser, "lastClr") ?: getAttrIgnoreCase(parser, "val")
                                if (!hex.isNullOrBlank() && hex.length == 6) {
                                    try { themeColors[currentSlot] = Color.parseColor("#$hex") } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (local == "clrscheme") inClrScheme = false
                        else if (local == currentSlot) currentSlot = null
                    }
                }
                ev = parser.next()
            }
            themeColors["tx1"] = themeColors["dk1"] ?: Color.BLACK
            themeColors["tx2"] = themeColors["dk2"] ?: Color.DKGRAY
            themeColors["bg1"] = themeColors["lt1"] ?: Color.WHITE
            themeColors["bg2"] = themeColors["lt2"] ?: Color.LTGRAY
        } catch (_: Exception) {}
    }

    private fun resolveSchemeColor(schemeVal: String, colorMap: Map<String, String>? = null): Int? {
        val rawKey = schemeVal.lowercase(Locale.ROOT).trim()
        val mappedKey = colorMap?.get(rawKey)?.lowercase(Locale.ROOT) ?: rawKey
        return themeColors[mappedKey] ?: themeColors[rawKey] ?: when (rawKey) {
            "phclr" -> themeColors["accent1"]
            "tx1" -> themeColors["dk1"]
            "tx2" -> themeColors["dk2"]
            "bg1" -> themeColors["lt1"]
            "bg2" -> themeColors["lt2"]
            else -> null
        }
    }

    private fun parsePresetNamedColor(prstVal: String?): Int? {
        if (prstVal.isNullOrBlank()) return null
        return when (prstVal.lowercase(Locale.ROOT).trim()) {
            "black" -> Color.BLACK
            "white" -> Color.WHITE
            "red", "darkred" -> Color.rgb(192, 57, 43)
            "blue", "darkblue", "navy" -> Color.rgb(41, 128, 185)
            "green", "darkgreen" -> Color.rgb(39, 174, 96)
            "yellow", "gold", "orange" -> Color.rgb(243, 156, 18)
            "gray", "grey", "darkgray", "darkgrey" -> Color.DKGRAY
            "lightgray", "lightgrey", "silver" -> Color.LTGRAY
            "brown", "saddlebrown", "sienna" -> Color.rgb(101, 67, 33)
            "tan", "wheat", "beige", "antiquewhite" -> Color.rgb(235, 221, 195)
            else -> try { Color.parseColor(prstVal.trim()) } catch (_: Exception) { null }
        }
    }

    private fun parseChartXml(chartBytes: ByteArray): SlideVisualElement.ChartBox? {
        return try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(chartBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var chartKind = "bar"
            var chartTitle = ""
            var inTitle = false
            var inSer = false
            var inCat = false
            var inVal = false
            var inV = false
            var inTx = false

            val seriesList = mutableListOf<ChartSeries>()
            var curSerName = ""
            val curCats = mutableListOf<String>()
            val curVals = mutableListOf<Float>()
            val vBuilder = StringBuilder()

            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "doughnutchart" -> chartKind = "doughnut"
                            "piechart", "pie3dchart", "ofpiechart" -> chartKind = "pie"
                            "linechart", "line3dchart", "stockchart" -> chartKind = "line"
                            "areachart", "area3dchart" -> chartKind = "area"
                            "scatterchart", "bubblechart" -> chartKind = "scatter"
                            "radarchart" -> chartKind = "radar"
                            "barchart", "bar3dchart" -> chartKind = "bar"
                            "title" -> inTitle = true
                            "ser" -> {
                                inSer = true
                                curSerName = ""
                                curCats.clear()
                                curVals.clear()
                            }
                            "tx" -> if (inSer) inTx = true
                            "cat", "xval" -> if (inSer) inCat = true
                            "val", "yval" -> if (inSer) inVal = true
                            "v", "t" -> {
                                inV = true
                                vBuilder.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inV && vBuilder.length < 4096) vBuilder.append(parser.text ?: "")
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "v", "t" -> {
                                val text = vBuilder.toString().trim()
                                if (text.isNotEmpty()) {
                                    when {
                                        inTitle && !inSer -> if (chartTitle.isEmpty()) chartTitle = text else if (chartTitle.length < 512) chartTitle += " $text"
                                        inSer && inTx && !inCat && !inVal -> curSerName = text
                                        inSer && inCat && curCats.size < 4096 -> curCats.add(text)
                                        inSer && inVal && curVals.size < 4096 -> text.toFloatOrNull()?.let { if (it.isFinite()) curVals.add(it) }
                                    }
                                }
                                inV = false
                            }
                            "tx" -> inTx = false
                            "cat", "xval" -> inCat = false
                            "val", "yval" -> inVal = false
                            "title" -> inTitle = false
                            "ser" -> {
                                if (curVals.isNotEmpty() && seriesList.size < 64) {
                                    val name = curSerName.ifEmpty { "Series ${seriesList.size + 1}" }
                                    seriesList.add(ChartSeries(name, curCats.toList(), curVals.toList()))
                                }
                                inSer = false
                            }
                        }
                    }
                }
                ev = parser.next()
            }

            if (seriesList.isEmpty()) null else SlideVisualElement.ChartBox(
                rect = RectF(0f, 0f, slideWidthEmu, slideHeightEmu),
                title = chartTitle,
                chartKind = chartKind,
                seriesList = seriesList
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSpeakerNotesText(notesXml: ByteArray): String {
        return try {
            val sb = StringBuilder()
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(notesXml), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inT = false
            while (ev != XmlPullParser.END_DOCUMENT && sb.length < 65_536) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> if (local == "t") inT = true
                    XmlPullParser.TEXT -> if (inT) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        if (local == "t") inT = false
                        else if (local == "p" && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    }
                }
                ev = parser.next()
            }
            sb.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() } }
                .joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parsePptxContainer() {
        if (isEncryptedOle2Container(pfd.fileDescriptor)) {
            lastErrorMsg = "PASSWORD_REQUIRED: Презентация PowerPoint (.pptx) защищена паролем."
            return
        }

        val zipEntries = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        try {
            val allEntries = OfficeZipContainerLoader.readZipEntries(
                pfd = pfd,
                maxTotalBytes = 128 * 1024 * 1024L
            ) { normName ->
                normName.startsWith("ppt/media/", ignoreCase = true) ||
                        normName.endsWith(".xml", ignoreCase = true) ||
                        normName.endsWith(".rels", ignoreCase = true)
            }
            for ((name, bytes) in allEntries) {
                if (name.startsWith("ppt/media/", ignoreCase = true)) {
                    mediaBytesMap[name] = bytes
                } else {
                    zipEntries[name] = bytes
                }
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
            return
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер PPTX: ${e.localizedMessage}"
            return
        }

        zipEntries.entries.firstOrNull { it.key.startsWith("ppt/theme/theme", ignoreCase = true) && it.key.endsWith(".xml", ignoreCase = true) }
            ?.value?.let { parseThemeXml(it) }

        val presXml = zipEntries["ppt/presentation.xml"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("/presentation.xml", ignoreCase = true) }?.value

        if (presXml == null && zipEntries.none { it.key.startsWith("ppt/slides/slide", ignoreCase = true) }) {
            lastErrorMsg = "В контейнере PPTX отсутствует обязательный файл ppt/presentation.xml."
            return
        }

        val presRels = parseRelationshipsXml(
            zipEntries["ppt/_rels/presentation.xml.rels"]
                ?: zipEntries.entries.firstOrNull { it.key.endsWith("presentation.xml.rels", ignoreCase = true) }?.value,
            "ppt"
        )

        val orderedRelIds = mutableListOf<String>()
        if (presXml != null) {
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(presXml), StandardCharsets.UTF_8))
                var ev = parser.eventType
                var inSldIdLst = false
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (ev == XmlPullParser.START_TAG) {
                        if (local == "sldidlst") {
                            inSldIdLst = true
                        } else if (local == "sldid" && inSldIdLst) {
                            val rId = findRelationshipAttr(parser)
                            if (!rId.isNullOrBlank()) orderedRelIds.add(rId)
                        } else if (local == "sldsz") {
                            val cx = getAttrIgnoreCase(parser, "cx")?.toFloatOrNull()
                            val cy = getAttrIgnoreCase(parser, "cy")?.toFloatOrNull()
                            if (cx != null && cx > 100_000f) slideWidthEmu = cx
                            if (cy != null && cy > 100_000f) slideHeightEmu = cy
                        }
                    } else if (ev == XmlPullParser.END_TAG && local == "sldidlst") {
                        inSldIdLst = false
                    }
                    ev = parser.next()
                }
            } catch (_: Exception) {}
        }

        val slideTargets = mutableListOf<String>()
        for (rId in orderedRelIds) {
            val target = presRels[rId]
            if (target != null && zipEntries.containsKey(target)) {
                slideTargets.add(target)
            }
        }

        if (slideTargets.isEmpty()) {
            val discoveredSlides = zipEntries.keys
                .filter {
                    val norm = it.lowercase(Locale.ROOT)
                    norm.startsWith("ppt/slides/slide") && norm.endsWith(".xml")
                }
                .sortedWith(compareBy({ extractSlideNumber(it) }, { it.lowercase(Locale.ROOT) }))
            slideTargets.addAll(discoveredSlides)
        }

        slideTargets.forEachIndexed { idx, slidePath ->
            val slideBytes = zipEntries[slidePath] ?: return@forEachIndexed
            val slideFileName = slidePath.substringAfterLast('/')
            val slideDir = slidePath.substringBeforeLast('/', "ppt/slides")
            val relsPath = "$slideDir/_rels/$slideFileName.rels"
            val slideRels = parseRelationshipsXml(zipEntries[relsPath], slideDir)

            val inheritedCtx = resolveLayoutAndMasterContext(slideRels, zipEntries)
            slides.add(parseSingleSlide(idx + 1, slideBytes, slideRels, zipEntries, inheritedCtx))
        }

        if (slides.isEmpty()) {
            lastErrorMsg = "В презентации PPTX не обнаружено читаемых слайдов."
        }
    }

    private fun resolveLayoutAndMasterContext(
        slideRels: Map<String, String>,
        allZipEntries: Map<String, ByteArray>
    ): SlideMasterOrLayoutContext {
        val layoutPath = slideRels.values.firstOrNull {
            it.contains("slideLayouts/", ignoreCase = true) && it.endsWith(".xml", ignoreCase = true)
        } ?: return SlideMasterOrLayoutContext(null, null, null, emptyList(), emptyMap())

        parsedLayoutCache[layoutPath]?.let { return it }

        val layoutBytes = allZipEntries[layoutPath]
            ?: return SlideMasterOrLayoutContext(null, null, null, emptyList(), emptyMap())
        val layoutFileName = layoutPath.substringAfterLast('/')
        val layoutDir = layoutPath.substringBeforeLast('/', "ppt/slideLayouts")
        val layoutRelsPath = "$layoutDir/_rels/$layoutFileName.rels"
        val layoutRels = parseRelationshipsXml(allZipEntries[layoutRelsPath], layoutDir)

        val masterPath = layoutRels.values.firstOrNull {
            it.contains("slideMasters/", ignoreCase = true) && it.endsWith(".xml", ignoreCase = true)
        }
        val masterCtx = if (masterPath != null) {
            parsedMasterCache.getOrPut(masterPath) {
                val masterBytes = allZipEntries[masterPath]
                if (masterBytes != null) {
                    val masterFile = masterPath.substringAfterLast('/')
                    val masterDir = masterPath.substringBeforeLast('/', "ppt/slideMasters")
                    val masterRels = parseRelationshipsXml(allZipEntries["$masterDir/_rels/$masterFile.rels"], masterDir)
                    masterRels.values.firstOrNull {
                        it.contains("theme/", ignoreCase = true) && it.endsWith(".xml", ignoreCase = true)
                    }?.let { themePath ->
                        allZipEntries[themePath]?.let { parseThemeXml(it) }
                    }
                    parseMasterOrLayoutXml(masterBytes, masterRels, allZipEntries, null)
                } else {
                    SlideMasterOrLayoutContext(null, null, null, emptyList(), emptyMap())
                }
            }
        } else {
            SlideMasterOrLayoutContext(null, null, null, emptyList(), emptyMap())
        }

        val combined = parseMasterOrLayoutXml(layoutBytes, layoutRels, allZipEntries, masterCtx)
        parsedLayoutCache[layoutPath] = combined
        return combined
    }

    private fun extractMasterTxStyles(
        xmlBytes: ByteArray,
        colorMap: Map<String, String>
    ): Quadruple<Int?, Int?, Float?, Float?> {
        var titleColor: Int? = null
        var bodyColor: Int? = null
        var titleSzPt: Float? = null
        var bodySzPt: Float? = null
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(xmlBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inTxStyles = false
            var activeStyleSection = ""
            var inLvl1pPr = false

            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "txstyles" -> inTxStyles = true
                            "titlestyle" -> if (inTxStyles) activeStyleSection = "title"
                            "bodystyle" -> if (inTxStyles) activeStyleSection = "body"
                            "lvl1ppr" -> if (activeStyleSection.isNotEmpty()) inLvl1pPr = true
                            "defrpr" -> if (inLvl1pPr) {
                                val sz = getAttrIgnoreCase(parser, "sz")?.toFloatOrNull()
                                if (sz != null && sz in 600f..9600f) {
                                    if (activeStyleSection == "title" && titleSzPt == null) titleSzPt = sz / 100f
                                    else if (activeStyleSection == "body" && bodySzPt == null) bodySzPt = sz / 100f
                                }
                            }
                            "srgbclr" -> if (inLvl1pPr) {
                                val hex = getAttrIgnoreCase(parser, "val")
                                if (!hex.isNullOrBlank() && hex.length == 6) {
                                    val c = try { Color.parseColor("#$hex") } catch (_: Throwable) { null }
                                    if (c != null) {
                                        if (activeStyleSection == "title" && titleColor == null) titleColor = c
                                        else if (activeStyleSection == "body" && bodyColor == null) bodyColor = c
                                    }
                                }
                            }
                            "schemeclr" -> if (inLvl1pPr) {
                                val sVal = getAttrIgnoreCase(parser, "val")
                                if (!sVal.isNullOrBlank()) {
                                    val c = resolveSchemeColor(sVal, colorMap)
                                    if (c != null) {
                                        if (activeStyleSection == "title" && titleColor == null) titleColor = c
                                        else if (activeStyleSection == "body" && bodyColor == null) bodyColor = c
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "lvl1ppr" -> inLvl1pPr = false
                            "titlestyle", "bodystyle" -> activeStyleSection = ""
                            "txstyles" -> inTxStyles = false
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Throwable) {}
        return Quadruple(titleColor, bodyColor, titleSzPt, bodySzPt)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun parseMasterOrLayoutXml(
        xmlBytes: ByteArray,
        rels: Map<String, String>,
        allZipEntries: Map<String, ByteArray>,
        parentMaster: SlideMasterOrLayoutContext?
    ): SlideMasterOrLayoutContext {
        val parsed = parseSlideTreeInternal(
            slideNumber = 0,
            slideXml = xmlBytes,
            slideRels = rels,
            allZipEntries = allZipEntries,
            inheritedContext = parentMaster,
            isMasterOrLayout = true
        )
        val txStyles = extractMasterTxStyles(xmlBytes, parentMaster?.colorMapOverrides ?: emptyMap())
        val effectiveTitleCol = txStyles.first ?: parentMaster?.masterTitleTextColor
        val effectiveBodyCol = txStyles.second ?: parentMaster?.masterBodyTextColor
        val effectiveTitleSz = txStyles.third ?: parentMaster?.masterTitleFontSizePt
        val effectiveBodySz = txStyles.fourth ?: parentMaster?.masterBodyFontSizePt

        val mergedPlaceholders = HashMap<String, PlaceholderStyleSpec>()
        parentMaster?.placeholderStyles?.let { mergedPlaceholders.putAll(it) }
        for ((k, childSpec) in parsed.second) {
            val prev = mergedPlaceholders[k]
            mergedPlaceholders[k] = if (prev != null) {
                PlaceholderStyleSpec(
                    rect = childSpec.rect ?: prev.rect,
                    fillColor = childSpec.fillColor ?: prev.fillColor,
                    strokeColor = childSpec.strokeColor ?: prev.strokeColor,
                    textColor = childSpec.textColor ?: prev.textColor,
                    fontSizePt = childSpec.fontSizePt ?: prev.fontSizePt,
                    alignment = childSpec.alignment ?: prev.alignment
                )
            } else {
                childSpec
            }
        }

        val mergedDecorations = ArrayList<SlideVisualElement>()
        parentMaster?.decorativeElements?.let { mergedDecorations.addAll(it) }
        mergedDecorations.addAll(parsed.first.elements)

        val bgCol = if (parsed.third) parsed.first.backgroundColor else parentMaster?.backgroundColor
        val bgEndCol = if (parsed.third) parsed.first.backgroundEndColor else parentMaster?.backgroundEndColor
        val bgMedia = parsed.first.backgroundMediaPath ?: parentMaster?.backgroundMediaPath

        return SlideMasterOrLayoutContext(
            backgroundColor = bgCol,
            backgroundEndColor = bgEndCol,
            backgroundMediaPath = bgMedia,
            decorativeElements = mergedDecorations,
            placeholderStyles = mergedPlaceholders,
            colorMapOverrides = parentMaster?.colorMapOverrides ?: emptyMap(),
            masterTitleTextColor = effectiveTitleCol,
            masterBodyTextColor = effectiveBodyCol,
            masterTitleFontSizePt = effectiveTitleSz,
            masterBodyFontSizePt = effectiveBodySz
        )
    }

    private fun extractSlideNumber(path: String): Int {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val digits = name.filter { it.isDigit() }
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun findRelationshipAttr(parser: XmlPullParser): String? {
        parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")?.let {
            if (it.isNotBlank()) return it
        }
        parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")?.let {
            if (it.isNotBlank()) return it
        }
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i) ?: continue
            val attrVal = parser.getAttributeValue(i) ?: continue
            val lowerName = attrName.lowercase(Locale.ROOT)
            if (lowerName == "r:id" || lowerName == "r:embed" || lowerName.endsWith(":id") || lowerName.endsWith(":embed") || lowerName == "r:dm") {
                if (attrVal.isNotBlank()) return attrVal
            }
        }
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)?.lowercase(Locale.ROOT) ?: continue
            val attrVal = parser.getAttributeValue(i) ?: continue
            if (attrName == "id" && (attrVal.startsWith("rId", ignoreCase = true) || attrVal.startsWith("rel", ignoreCase = true))) {
                return attrVal
            }
        }
        return null
    }

    private fun getAttrIgnoreCase(parser: XmlPullParser, targetLocalName: String): String? {
        parser.getAttributeValue(null, targetLocalName)?.let { return it }
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)?.substringAfterLast(':') ?: continue
            if (name.equals(targetLocalName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseRelationshipsXml(relsBytes: ByteArray?, baseDir: String): Map<String, String> {
        if (relsBytes == null) return emptyMap()
        val map = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(relsBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                if (ev == XmlPullParser.START_TAG && local == "relationship") {
                    val id = getAttrIgnoreCase(parser, "Id")
                    val target = getAttrIgnoreCase(parser, "Target")
                    if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                        map[id] = resolveTargetPath(baseDir, target)
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return map
    }

    private fun resolveTargetPath(baseDir: String, rawTarget: String): String {
        val cleanTarget = rawTarget.replace('\\', '/').trim()
        if (cleanTarget.startsWith("/")) {
            return OfficeZipContainerLoader.normalizeZipPath(cleanTarget.trimStart('/'))
        }
        val normBase = OfficeZipContainerLoader.normalizeZipPath(baseDir)
        val rootPrefix = normBase.substringBefore('/')
        if (rootPrefix.isNotEmpty() &&
            (cleanTarget.startsWith("$rootPrefix/", ignoreCase = true) || cleanTarget.equals(rootPrefix, ignoreCase = true))
        ) {
            return OfficeZipContainerLoader.normalizeZipPath(cleanTarget)
        }
        return OfficeZipContainerLoader.normalizeZipPath("$normBase/$cleanTarget")
    }

    private fun defaultPlaceholderRect(phType: String, bodyIndex: Int): RectF {
        return when {
            phType == "title" || phType == "ctrtitle" -> RectF(
                slideWidthEmu * 0.05f,
                slideHeightEmu * 0.05f,
                slideWidthEmu * 0.95f,
                slideHeightEmu * 0.24f
            )
            phType == "subtitle" || phType == "subTitle".lowercase(Locale.ROOT) -> RectF(
                slideWidthEmu * 0.08f,
                slideHeightEmu * 0.26f,
                slideWidthEmu * 0.92f,
                slideHeightEmu * 0.44f
            )
            else -> {
                val topFrac = if (bodyIndex == 0) 0.25f else (0.25f + bodyIndex * 0.22f).coerceAtMost(0.72f)
                RectF(
                    slideWidthEmu * 0.05f,
                    slideHeightEmu * topFrac,
                    slideWidthEmu * 0.95f,
                    slideHeightEmu * 0.94f
                )
            }
        }
    }

    private fun mapRectThroughGroupStack(rawRect: RectF, groupStack: ArrayDeque<GroupTransformFrame>): RectF {
        var r = rawRect
        val frames = groupStack.toList()
        for (i in frames.indices.reversed()) {
            r = frames[i].mapChildRect(r)
        }
        return r
    }

    private fun parseSingleSlide(
        slideNumber: Int,
        slideXml: ByteArray,
        slideRels: Map<String, String>,
        allZipEntries: Map<String, ByteArray>,
        inheritedContext: SlideMasterOrLayoutContext? = null
    ): ParsedSlide {
        val baseTriple = parseSlideTreeInternal(
            slideNumber = slideNumber,
            slideXml = slideXml,
            slideRels = slideRels,
            allZipEntries = allZipEntries,
            inheritedContext = inheritedContext,
            isMasterOrLayout = false
        )
        var slide = baseTriple.first

        val diagramPaths = slideRels.values.filter {
            it.contains("diagrams/drawing", ignoreCase = true) && it.endsWith(".xml", ignoreCase = true)
        }
        if (diagramPaths.isNotEmpty()) {
            val extraElements = ArrayList<SlideVisualElement>(slide.elements)
            val extraSummary = StringBuilder(slide.plainTextSummary)
            for (dPath in diagramPaths) {
                val dBytes = allZipEntries[dPath] ?: continue
                val dFile = dPath.substringAfterLast('/')
                val dDir = dPath.substringBeforeLast('/', "ppt/diagrams")
                val dRels = parseRelationshipsXml(allZipEntries["$dDir/_rels/$dFile.rels"], dDir)
                val parsedDiag = parseSlideTreeInternal(0, dBytes, dRels, allZipEntries, inheritedContext, false).first
                extraElements.addAll(parsedDiag.elements)
                if (parsedDiag.plainTextSummary.isNotBlank()) {
                    if (extraSummary.isNotEmpty()) extraSummary.append('\n')
                    extraSummary.append(parsedDiag.plainTextSummary)
                }
            }
            slide = slide.copy(elements = extraElements, plainTextSummary = extraSummary.toString().trim())
        }

        val notesPath = slideRels.values.firstOrNull {
            it.contains("notesSlides/", ignoreCase = true) && it.endsWith(".xml", ignoreCase = true)
        }
        if (notesPath != null) {
            val notesBytes = allZipEntries[notesPath]
            if (notesBytes != null) {
                val notesTxt = extractSpeakerNotesText(notesBytes)
                if (notesTxt.isNotBlank()) {
                    slide = slide.copy(speakerNotes = notesTxt)
                }
            }
        }

        return slide
    }

    private fun parseSlideTreeInternal(
        slideNumber: Int,
        slideXml: ByteArray,
        slideRels: Map<String, String>,
        allZipEntries: Map<String, ByteArray>,
        inheritedContext: SlideMasterOrLayoutContext?,
        isMasterOrLayout: Boolean
    ): Triple<ParsedSlide, Map<String, PlaceholderStyleSpec>, Boolean> {
        val elements = mutableListOf<SlideVisualElement>()
        if (!isMasterOrLayout && inheritedContext != null) {
            elements.addAll(inheritedContext.decorativeElements)
        }
        val discoveredPlaceholders = HashMap<String, PlaceholderStyleSpec>()
        val colorMap = HashMap<String, String>()
        inheritedContext?.colorMapOverrides?.let { colorMap.putAll(it) }

        val summarySb = StringBuilder()
        var bgColor = inheritedContext?.backgroundColor ?: Color.WHITE
        var bgEndColor: Int? = inheritedContext?.backgroundEndColor
        var bgMediaPath: String? = inheritedContext?.backgroundMediaPath
        var hasExplicitBg = false

        var slideTransitionType = "fade"
        var slideTransitionDurationMs = 320
        val animatedTargetIds = LinkedHashSet<String>()

        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(slideXml), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var inMcFallbackDepth = 0
            val groupStack = ArrayDeque<GroupTransformFrame>()
            var inGrpSpPr = false

            var inSp = false
            var isConnectorShape = false
            var inPic = false
            var inGraphicFrame = false
            var inBg = false
            var inTransition = false
            var inTiming = false
            var inTextTag = false
            var inTxBody = false
            var inSpStyle = false
            var activeStyleRefTag = ""
            var styleFillIdx = 0

            var currentShapeId = ""
            var curX = 0f
            var curY = 0f
            var curW = 0f
            var curH = 0f
            var hasBounds = false
            var curRotDeg = 0f
            var curFlipH = false
            var curFlipV = false
            var curHasShadow = false
            var presetGeom = "rect"
            var fillColor: Int? = null
            var fillEndColor: Int? = null
            var shapeBlipMediaPath: String? = null
            var gradAngleDeg = 90f
            var styleRefFillColor: Int? = null
            var explicitNoFill = false
            var strokeColor: Int? = null
            var strokeWidthPt = 1.5f
            var isDashedStroke = false
            var headArrowType = "none"
            var tailArrowType = "none"
            var textRunColor: Int? = null
            var inLn = false
            var inGradFill = false
            var isTitleShape = false
            var hasPlaceholderTag = false
            var currentPhType = ""
            var currentPhIdx = ""
            var currentFontSizePt: Float? = null
            var currentShapeAlignment: Layout.Alignment? = null
            var bodyPlaceholderCount = 0
            var picRelId: String? = null
            var chartRelId: String? = null
            var cropL = 0f
            var cropT = 0f
            var cropR = 0f
            var cropB = 0f

            var pendingBaseColor: Int? = null
            var pendingColorTarget = ""
            var curLumMod = 100_000
            var curLumOff = 0
            var curTint = 0
            var curShade = 0
            var curAlpha = 100_000

            var inCustGeom = false
            var inCustPath = false
            var custPathW = 0f
            var custPathH = 0f
            var activeCustCmdTag = ""
            val custCmdPoints = ArrayList<Pair<Float, Float>>()
            val currentCustCmds = ArrayList<CustomPathCmd>()
            val currentShapeCustomPaths = ArrayList<CustomVectorPath>()

            val currentParagraphs = mutableListOf<String>()
            val currentRichParagraphs = mutableListOf<SlideParagraph>()
            var currentParaSb: StringBuilder? = null
            var curParaAlign = Layout.Alignment.ALIGN_NORMAL
            var curParaHasExplicitAlign = false
            var curParaFontSizePt: Float? = null
            var curParaBold = false
            var curParaItalic = false
            var curParaUnderline = false
            var curParaStrike = false
            var curParaColor: Int? = null
            var curParaIsBullet = false

            var inRun = false
            var curRunSb = StringBuilder()
            var curRunFontSizePt: Float? = null
            var curRunBold = false
            var curRunItalic = false
            var curRunUnderline = false
            var curRunStrike = false
            var curRunColor: Int? = null
            val currentParaRuns = ArrayList<SlideTextRun>()

            val tableRows = mutableListOf<List<String>>()
            val richTableRows = mutableListOf<List<SlideTableCell>>()
            var currentRowCells: MutableList<String>? = null
            var currentRichRowCells: MutableList<SlideTableCell>? = null
            var currentCellSb: StringBuilder? = null
            var inTcPr = false
            var curTcFillColor: Int? = null
            var curTcTextColor: Int? = null
            var curTcBold = false
            var curTcGridSpan = 1
            var curTcRowSpan = 1
            var curTcMerged = false

            fun determineColorTarget(): String {
                return when {
                    inBg -> "bg"
                    inTcPr -> "tcfill"
                    inLn -> "ln"
                    inSpStyle -> activeStyleRefTag
                    inTxBody -> "text"
                    inSp || inPic -> "fill"
                    else -> ""
                }
            }

            fun commitPendingColor() {
                val base = pendingBaseColor ?: return
                val transformed = DrawingMlColorUtils.applyTransforms(
                    baseColor = base,
                    lumMod = curLumMod,
                    lumOff = curLumOff,
                    tint = curTint,
                    shade = curShade,
                    alpha = curAlpha
                )
                when (pendingColorTarget) {
                    "bg" -> {
                        if (inGradFill && hasExplicitBg) {
                            bgEndColor = transformed
                        } else {
                            bgColor = transformed
                            hasExplicitBg = true
                        }
                    }
                    "tcfill" -> curTcFillColor = transformed
                    "ln" -> strokeColor = transformed
                    "fillref" -> if (styleFillIdx > 0) styleRefFillColor = transformed
                    "lnref" -> if (strokeColor == null) strokeColor = transformed
                    "fontref" -> if (textRunColor == null) textRunColor = transformed
                    "text" -> {
                        if (inGraphicFrame && currentCellSb != null) {
                            curTcTextColor = transformed
                        } else {
                            if (inRun) curRunColor = transformed
                            if (curParaColor == null) curParaColor = transformed
                            if (textRunColor == null) textRunColor = transformed
                        }
                    }
                    "fill" -> {
                        if (!explicitNoFill) {
                            if (inGradFill && fillColor != null) {
                                fillEndColor = transformed
                            } else if (fillColor == null) {
                                fillColor = transformed
                            }
                        }
                    }
                }
                pendingBaseColor = null
                pendingColorTarget = ""
            }

            fun startPendingColor(base: Int) {
                pendingBaseColor = base
                pendingColorTarget = determineColorTarget()
                curLumMod = 100_000
                curLumOff = 0
                curTint = 0
                curShade = 0
                curAlpha = 100_000
            }

            while (ev != XmlPullParser.END_DOCUMENT && elements.size < 2048) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""

                if (ev == XmlPullParser.START_TAG && local == "fallback") {
                    inMcFallbackDepth++
                    ev = parser.next()
                    continue
                } else if (ev == XmlPullParser.END_TAG && local == "fallback") {
                    inMcFallbackDepth = (inMcFallbackDepth - 1).coerceAtLeast(0)
                    ev = parser.next()
                    continue
                }
                if (inMcFallbackDepth > 0) {
                    ev = parser.next()
                    continue
                }

                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "transition" -> {
                                inTransition = true
                                val spd = getAttrIgnoreCase(parser, "spd")?.lowercase(Locale.ROOT)
                                val advTm = getAttrIgnoreCase(parser, "dur")?.toIntOrNull()
                                slideTransitionDurationMs = when {
                                    advTm != null && advTm in 100..2500 -> advTm
                                    spd == "slow" -> 520
                                    spd == "fast" -> 200
                                    else -> 320
                                }
                            }
                            "fade", "push", "wipe", "split", "cover", "pull", "zoom", "wheel", "dissolve", "cut", "blinds", "checker", "circle", "comb", "diamond", "plus", "wedge" -> {
                                if (inTransition) {
                                    slideTransitionType = local
                                }
                            }
                            "timing" -> inTiming = true
                            "sptgt" -> {
                                if (inTiming && animatedTargetIds.size < 64) {
                                    getAttrIgnoreCase(parser, "spid")?.trim()?.let {
                                        if (it.isNotEmpty()) animatedTargetIds.add(it)
                                    }
                                }
                            }
                            "clrmap", "clrmapovr" -> {
                                for (aIdx in 0 until parser.attributeCount) {
                                    val aName = parser.getAttributeName(aIdx)?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: continue
                                    val aVal = parser.getAttributeValue(aIdx)?.lowercase(Locale.ROOT) ?: continue
                                    if (aName.isNotEmpty() && aVal.isNotEmpty()) {
                                        colorMap[aName] = aVal
                                    }
                                }
                            }
                            "bg" -> inBg = true
                            "grpsp" -> {
                                if (groupStack.size < 16) {
                                    groupStack.addLast(GroupTransformFrame())
                                }
                            }
                            "grpsppr" -> {
                                inGrpSpPr = true
                            }
                            "choff" -> {
                                if (inGrpSpPr && groupStack.isNotEmpty()) {
                                    val gx = getAttrIgnoreCase(parser, "x")?.toFloatOrNull() ?: 0f
                                    val gy = getAttrIgnoreCase(parser, "y")?.toFloatOrNull() ?: 0f
                                    groupStack.last().chOffX = gx
                                    groupStack.last().chOffY = gy
                                }
                            }
                            "chext" -> {
                                if (inGrpSpPr && groupStack.isNotEmpty()) {
                                    val gcx = getAttrIgnoreCase(parser, "cx")?.toFloatOrNull() ?: 0f
                                    val gcy = getAttrIgnoreCase(parser, "cy")?.toFloatOrNull() ?: 0f
                                    groupStack.last().chExtW = gcx
                                    groupStack.last().chExtH = gcy
                                }
                            }
                            "sp", "cxnsp" -> {
                                inSp = true
                                isConnectorShape = (local == "cxnsp")
                                currentShapeId = ""
                                hasBounds = false
                                curX = 0f; curY = 0f; curW = 0f; curH = 0f
                                curRotDeg = 0f; curFlipH = false; curFlipV = false
                                curHasShadow = false
                                presetGeom = if (isConnectorShape) "line" else "rect"
                                fillColor = null
                                fillEndColor = null
                                shapeBlipMediaPath = null
                                gradAngleDeg = 90f
                                styleRefFillColor = null
                                explicitNoFill = isConnectorShape
                                strokeColor = if (isConnectorShape) themeColors["tx1"] else null
                                strokeWidthPt = 1.5f
                                isDashedStroke = false
                                headArrowType = "none"
                                tailArrowType = "none"
                                textRunColor = null
                                isTitleShape = false
                                hasPlaceholderTag = false
                                currentPhType = ""
                                currentPhIdx = ""
                                currentFontSizePt = null
                                currentShapeAlignment = null
                                currentParagraphs.clear()
                                currentRichParagraphs.clear()
                                currentShapeCustomPaths.clear()
                            }
                            "cnvpr" -> {
                                if (inSp || inPic) {
                                    val idVal = getAttrIgnoreCase(parser, "id")?.trim()
                                    if (!idVal.isNullOrEmpty() && currentShapeId.isEmpty()) {
                                        currentShapeId = idVal
                                    }
                                }
                            }
                            "xfrm" -> {
                                if (inSp || inPic) {
                                    val rotRaw = getAttrIgnoreCase(parser, "rot")?.toFloatOrNull()
                                    if (rotRaw != null) {
                                        curRotDeg = (rotRaw / 60_000f) % 360f
                                    }
                                    val fh = getAttrIgnoreCase(parser, "flipH")
                                    curFlipH = (fh == "1" || fh.equals("true", ignoreCase = true))
                                    val fv = getAttrIgnoreCase(parser, "flipV")
                                    curFlipV = (fv == "1" || fv.equals("true", ignoreCase = true))
                                }
                            }
                            "outershdw", "innershdw" -> {
                                if (inSp || inPic) curHasShadow = true
                            }
                            "style" -> {
                                if (inSp || inPic) inSpStyle = true
                            }
                            "fillref", "lnref", "fontref", "effectref" -> {
                                if (inSpStyle) {
                                    activeStyleRefTag = local
                                    if (local == "fillref") {
                                        styleFillIdx = getAttrIgnoreCase(parser, "idx")?.toIntOrNull() ?: 0
                                    }
                                }
                            }
                            "nofill" -> {
                                if (inSp && !inLn && !inTxBody && !inBg) {
                                    explicitNoFill = true
                                    fillColor = null
                                    fillEndColor = null
                                } else if (inLn) {
                                    strokeColor = null
                                }
                            }
                            "gradfill" -> {
                                inGradFill = true
                            }
                            "lin" -> {
                                if (inGradFill) {
                                    val ang = getAttrIgnoreCase(parser, "ang")?.toFloatOrNull()
                                    if (ang != null) gradAngleDeg = (ang / 60_000f) % 360f
                                }
                            }
                            "txbody" -> if (inSp) inTxBody = true
                            "pic" -> {
                                inPic = true
                                currentShapeId = ""
                                hasBounds = false
                                curX = 0f; curY = 0f; curW = 0f; curH = 0f
                                curRotDeg = 0f; curFlipH = false; curFlipV = false
                                curHasShadow = false
                                presetGeom = "rect"
                                strokeColor = null
                                strokeWidthPt = 0f
                                picRelId = null
                                hasPlaceholderTag = false
                                currentPhType = ""
                                currentPhIdx = ""
                                cropL = 0f; cropT = 0f; cropR = 0f; cropB = 0f
                                currentShapeCustomPaths.clear()
                            }
                            "srcrect" -> {
                                if (inPic) {
                                    cropL = (getAttrIgnoreCase(parser, "l")?.toFloatOrNull() ?: 0f) / 100_000f
                                    cropT = (getAttrIgnoreCase(parser, "t")?.toFloatOrNull() ?: 0f) / 100_000f
                                    cropR = (getAttrIgnoreCase(parser, "r")?.toFloatOrNull() ?: 0f) / 100_000f
                                    cropB = (getAttrIgnoreCase(parser, "b")?.toFloatOrNull() ?: 0f) / 100_000f
                                }
                            }
                            "graphicframe" -> {
                                inGraphicFrame = true
                                hasBounds = false
                                curX = 0f; curY = 0f; curW = 0f; curH = 0f
                                hasPlaceholderTag = false
                                currentPhType = ""
                                currentPhIdx = ""
                                chartRelId = null
                                tableRows.clear()
                                richTableRows.clear()
                            }
                            "chart" -> {
                                if (inGraphicFrame) {
                                    chartRelId = findRelationshipAttr(parser)
                                }
                            }
                            "ph" -> {
                                hasPlaceholderTag = true
                                val phType = getAttrIgnoreCase(parser, "type")?.lowercase(Locale.ROOT) ?: "body"
                                val phIdx = getAttrIgnoreCase(parser, "idx")?.trim() ?: ""
                                currentPhType = phType
                                currentPhIdx = phIdx
                                if (phType.contains("title") || phType == "ctrtitle") {
                                    isTitleShape = true
                                }
                            }
                            "ppr", "lvl1ppr" -> {
                                if (inSp) {
                                    val algn = getAttrIgnoreCase(parser, "algn")?.lowercase(Locale.ROOT)
                                    if (algn != null) {
                                        val mappedAlign = when (algn) {
                                            "ctr" -> Layout.Alignment.ALIGN_CENTER
                                            "r" -> Layout.Alignment.ALIGN_OPPOSITE
                                            else -> Layout.Alignment.ALIGN_NORMAL
                                        }
                                        curParaAlign = mappedAlign
                                        curParaHasExplicitAlign = true
                                        if (currentShapeAlignment == null) currentShapeAlignment = mappedAlign
                                    }
                                }
                            }
                            "buchar", "buautonum" -> {
                                if (inSp) curParaIsBullet = true
                            }
                            "bunone" -> {
                                if (inSp) curParaIsBullet = false
                            }
                            "r" -> {
                                if (inSp && currentParaSb != null) {
                                    inRun = true
                                    curRunSb.setLength(0)
                                    curRunFontSizePt = null
                                    curRunBold = false
                                    curRunItalic = false
                                    curRunUnderline = false
                                    curRunStrike = false
                                    curRunColor = null
                                }
                            }
                            "rpr", "defppr", "endpararpr" -> {
                                val bAttr = getAttrIgnoreCase(parser, "b")
                                val isBoldNow = (bAttr == "1" || bAttr.equals("true", ignoreCase = true))
                                if (inGraphicFrame && currentCellSb != null && isBoldNow) {
                                    curTcBold = true
                                }
                                if (inSp) {
                                    val szHundredths = getAttrIgnoreCase(parser, "sz")?.toFloatOrNull()
                                    if (szHundredths != null && szHundredths in 500f..9600f) {
                                        val pt = szHundredths / 100f
                                        if (inRun) curRunFontSizePt = pt
                                        if (curParaFontSizePt == null || pt > curParaFontSizePt!!) {
                                            curParaFontSizePt = pt
                                        }
                                        if (currentFontSizePt == null) {
                                            currentFontSizePt = pt
                                        }
                                    }
                                    if (isBoldNow) {
                                        if (inRun) curRunBold = true
                                        curParaBold = true
                                    }
                                    val iAttr = getAttrIgnoreCase(parser, "i")
                                    if (iAttr == "1" || iAttr.equals("true", ignoreCase = true)) {
                                        if (inRun) curRunItalic = true
                                        curParaItalic = true
                                    }
                                    val uAttr = getAttrIgnoreCase(parser, "u")
                                    if (!uAttr.isNullOrBlank() && !uAttr.equals("none", ignoreCase = true) && uAttr != "0") {
                                        if (inRun) curRunUnderline = true
                                        curParaUnderline = true
                                    }
                                    val strikeAttr = getAttrIgnoreCase(parser, "strike")
                                    if (!strikeAttr.isNullOrBlank() && !strikeAttr.equals("noStrike", ignoreCase = true) && strikeAttr != "0") {
                                        if (inRun) curRunStrike = true
                                        curParaStrike = true
                                    }
                                }
                            }
                            "off" -> {
                                val x = getAttrIgnoreCase(parser, "x")?.toFloatOrNull()
                                val y = getAttrIgnoreCase(parser, "y")?.toFloatOrNull()
                                if (x != null && y != null) {
                                    if (inGrpSpPr && !inSp && !inPic && !inGraphicFrame && groupStack.isNotEmpty()) {
                                        groupStack.last().offX = x
                                        groupStack.last().offY = y
                                    } else if (inSp || inPic || inGraphicFrame) {
                                        curX = x
                                        curY = y
                                    }
                                }
                            }
                            "ext" -> {
                                val cx = getAttrIgnoreCase(parser, "cx")?.toFloatOrNull()
                                val cy = getAttrIgnoreCase(parser, "cy")?.toFloatOrNull()
                                if (cx != null && cy != null && (cx >= 0f && cy >= 0f) && (cx > 0f || cy > 0f)) {
                                    if (inGrpSpPr && !inSp && !inPic && !inGraphicFrame && groupStack.isNotEmpty()) {
                                        groupStack.last().extW = cx
                                        groupStack.last().extH = cy
                                    } else if (inSp || inPic || inGraphicFrame) {
                                        curW = cx
                                        curH = cy
                                        hasBounds = true
                                    }
                                }
                            }
                            "prstgeom" -> {
                                val prst = getAttrIgnoreCase(parser, "prst")
                                if (!prst.isNullOrBlank()) presetGeom = prst
                            }
                            "custgeom" -> {
                                if (inSp || inPic) {
                                    inCustGeom = true
                                    presetGeom = "custGeom"
                                }
                            }
                            "path" -> {
                                if (inCustGeom) {
                                    inCustPath = true
                                    custPathW = getAttrIgnoreCase(parser, "w")?.toFloatOrNull() ?: 0f
                                    custPathH = getAttrIgnoreCase(parser, "h")?.toFloatOrNull() ?: 0f
                                    currentCustCmds.clear()
                                }
                            }
                            "moveto", "lnto", "cubicbezto", "quadbezto" -> {
                                if (inCustPath) {
                                    activeCustCmdTag = local
                                    custCmdPoints.clear()
                                }
                            }
                            "pt" -> {
                                if (inCustPath && activeCustCmdTag.isNotEmpty() && custCmdPoints.size < 8) {
                                    val px = getAttrIgnoreCase(parser, "x")?.toFloatOrNull() ?: 0f
                                    val py = getAttrIgnoreCase(parser, "y")?.toFloatOrNull() ?: 0f
                                    custCmdPoints.add(px to py)
                                }
                            }
                            "close" -> {
                                if (inCustPath && currentCustCmds.size < 10_000) {
                                    currentCustCmds.add(CustomPathCmd.Close)
                                }
                            }
                            "ln" -> {
                                inLn = true
                                val wEmu = getAttrIgnoreCase(parser, "w")?.toFloatOrNull()
                                if (wEmu != null && wEmu > 0f) {
                                    strokeWidthPt = (wEmu / 12700f).coerceIn(0.75f, 14f)
                                } else if (inPic && strokeWidthPt <= 0f) {
                                    strokeWidthPt = 1.5f
                                }
                            }
                            "prstdash" -> {
                                if (inLn) {
                                    val dVal = getAttrIgnoreCase(parser, "val")?.lowercase(Locale.ROOT) ?: "solid"
                                    isDashedStroke = dVal != "solid"
                                }
                            }
                            "headend" -> {
                                if (inLn) {
                                    headArrowType = getAttrIgnoreCase(parser, "type")?.lowercase(Locale.ROOT) ?: "none"
                                }
                            }
                            "tailend" -> {
                                if (inLn) {
                                    tailArrowType = getAttrIgnoreCase(parser, "type")?.lowercase(Locale.ROOT) ?: "none"
                                }
                            }
                            "srgbclr" -> {
                                val hex = getAttrIgnoreCase(parser, "val")
                                if (!hex.isNullOrBlank() && hex.length == 6) {
                                    try { startPendingColor(Color.parseColor("#$hex")) } catch (_: Exception) {}
                                }
                            }
                            "schemeclr" -> {
                                val schemeVal = getAttrIgnoreCase(parser, "val")
                                if (!schemeVal.isNullOrBlank()) {
                                    resolveSchemeColor(schemeVal, colorMap)?.let { startPendingColor(it) }
                                }
                            }
                            "sysclr" -> {
                                val hex = getAttrIgnoreCase(parser, "lastClr") ?: getAttrIgnoreCase(parser, "val")
                                if (!hex.isNullOrBlank() && hex.length == 6) {
                                    try { startPendingColor(Color.parseColor("#$hex")) } catch (_: Exception) {}
                                }
                            }
                            "prstclr" -> {
                                val prstVal = getAttrIgnoreCase(parser, "val")
                                parsePresetNamedColor(prstVal)?.let { startPendingColor(it) }
                            }
                            "lummod" -> {
                                getAttrIgnoreCase(parser, "val")?.toIntOrNull()?.let { curLumMod = it }
                            }
                            "lumoff" -> {
                                getAttrIgnoreCase(parser, "val")?.toIntOrNull()?.let { curLumOff = it }
                            }
                            "tint" -> {
                                getAttrIgnoreCase(parser, "val")?.toIntOrNull()?.let { curTint = it }
                            }
                            "shade" -> {
                                getAttrIgnoreCase(parser, "val")?.toIntOrNull()?.let { curShade = it }
                            }
                            "alpha" -> {
                                getAttrIgnoreCase(parser, "val")?.toIntOrNull()?.let { curAlpha = it }
                            }
                            "blip" -> {
                                val rId = findRelationshipAttr(parser) ?: getAttrIgnoreCase(parser, "embed")
                                if (inPic) {
                                    picRelId = rId
                                } else if (rId != null) {
                                    val target = slideRels[rId]
                                    if (target != null && mediaBytesMap.containsKey(target)) {
                                        if (inBg) {
                                            bgMediaPath = target
                                            hasExplicitBg = true
                                        } else if (inSp) {
                                            if (!hasPlaceholderTag && curW >= slideWidthEmu * 0.85f && curH >= slideHeightEmu * 0.85f && bgMediaPath == null) {
                                                bgMediaPath = target
                                                hasExplicitBg = true
                                            } else {
                                                shapeBlipMediaPath = target
                                            }
                                        }
                                    }
                                }
                            }
                            "p" -> {
                                if (inSp) {
                                    currentParaSb = StringBuilder()
                                    currentParaRuns.clear()
                                    curParaAlign = Layout.Alignment.ALIGN_NORMAL
                                    curParaHasExplicitAlign = false
                                    curParaFontSizePt = null
                                    curParaBold = false
                                    curParaItalic = false
                                    curParaUnderline = false
                                    curParaStrike = false
                                    curParaColor = null
                                    curParaIsBullet = false
                                }
                            }
                            "br" -> {
                                currentParaSb?.append('\n')
                                if (inRun) curRunSb.append('\n')
                            }
                            "tr" -> {
                                if (inGraphicFrame && tableRows.size < 512) {
                                    currentRowCells = mutableListOf()
                                    currentRichRowCells = mutableListOf()
                                }
                            }
                            "tc" -> {
                                if (inGraphicFrame && currentRowCells != null && (currentRowCells?.size ?: 0) < 128) {
                                    currentCellSb = StringBuilder()
                                    curTcFillColor = null
                                    curTcTextColor = null
                                    curTcBold = false
                                    curTcGridSpan = (getAttrIgnoreCase(parser, "gridSpan")?.toIntOrNull() ?: 1).coerceIn(1, 32)
                                    curTcRowSpan = (getAttrIgnoreCase(parser, "rowSpan")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                    val hMerge = getAttrIgnoreCase(parser, "hMerge")
                                    val vMerge = getAttrIgnoreCase(parser, "vMerge")
                                    curTcMerged = (hMerge == "1" || hMerge.equals("true", true) || vMerge == "1" || vMerge.equals("true", true))
                                }
                            }
                            "tcpr" -> if (inGraphicFrame) inTcPr = true
                            "t" -> {
                                inTextTag = true
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTextTag) {
                            val txt = parser.text ?: ""
                            if (txt.isNotEmpty()) {
                                when {
                                    currentCellSb != null -> {
                                        if (currentCellSb.length < 16384) {
                                            if (currentCellSb.isNotEmpty() && !currentCellSb.endsWith(" ")) {
                                                currentCellSb.append(" ")
                                            }
                                            currentCellSb.append(txt)
                                        }
                                    }
                                    currentParaSb != null -> {
                                        if (currentParaSb.length < 32768) currentParaSb.append(txt)
                                        if (inRun && curRunSb.length < 32768) curRunSb.append(txt)
                                    }
                                    inSp -> if (currentParagraphs.size < 1024) currentParagraphs.add(txt)
                                    else -> if (!isMasterOrLayout && summarySb.length < 65536) summarySb.append(txt).append(" ")
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "transition" -> inTransition = false
                            "timing" -> inTiming = false
                            "srgbclr", "schemeclr", "sysclr", "prstclr" -> commitPendingColor()
                            "style" -> inSpStyle = false
                            "fillref", "lnref", "fontref", "effectref" -> activeStyleRefTag = ""
                            "gradfill" -> inGradFill = false
                            "t" -> inTextTag = false
                            "r" -> {
                                if (inRun) {
                                    val rText = curRunSb.toString()
                                    if (rText.isNotEmpty() && currentParaRuns.size < 256) {
                                        currentParaRuns.add(
                                            SlideTextRun(
                                                text = rText,
                                                fontSizePt = curRunFontSizePt,
                                                isBold = curRunBold,
                                                isItalic = curRunItalic,
                                                isUnderline = curRunUnderline,
                                                isStrike = curRunStrike,
                                                textColor = curRunColor
                                            )
                                        )
                                    }
                                    inRun = false
                                    curRunSb.setLength(0)
                                }
                            }
                            "txbody" -> inTxBody = false
                            "bg" -> inBg = false
                            "ln" -> inLn = false
                            "tcpr" -> inTcPr = false
                            "grpsppr" -> inGrpSpPr = false
                            "grpsp" -> {
                                if (groupStack.isNotEmpty()) groupStack.removeLast()
                            }
                            "moveto" -> {
                                if (inCustPath && custCmdPoints.isNotEmpty() && currentCustCmds.size < 10_000) {
                                    val (x, y) = custCmdPoints[0]
                                    currentCustCmds.add(CustomPathCmd.MoveTo(x, y))
                                }
                                activeCustCmdTag = ""
                            }
                            "lnto" -> {
                                if (inCustPath && custCmdPoints.isNotEmpty() && currentCustCmds.size < 10_000) {
                                    val (x, y) = custCmdPoints[0]
                                    currentCustCmds.add(CustomPathCmd.LineTo(x, y))
                                }
                                activeCustCmdTag = ""
                            }
                            "cubicbezto" -> {
                                if (inCustPath && custCmdPoints.size >= 3 && currentCustCmds.size < 10_000) {
                                    val (x1, y1) = custCmdPoints[0]
                                    val (x2, y2) = custCmdPoints[1]
                                    val (x3, y3) = custCmdPoints[2]
                                    currentCustCmds.add(CustomPathCmd.CubicBezTo(x1, y1, x2, y2, x3, y3))
                                }
                                activeCustCmdTag = ""
                            }
                            "quadbezto" -> {
                                if (inCustPath && custCmdPoints.size >= 2 && currentCustCmds.size < 10_000) {
                                    val (x1, y1) = custCmdPoints[0]
                                    val (x2, y2) = custCmdPoints[1]
                                    currentCustCmds.add(CustomPathCmd.QuadBezTo(x1, y1, x2, y2))
                                }
                                activeCustCmdTag = ""
                            }
                            "path" -> {
                                if (inCustPath && currentCustCmds.isNotEmpty() && currentShapeCustomPaths.size < 256) {
                                    currentShapeCustomPaths.add(
                                        CustomVectorPath(
                                            pathWidth = custPathW,
                                            pathHeight = custPathH,
                                            commands = currentCustCmds.toList()
                                        )
                                    )
                                }
                                inCustPath = false
                            }
                            "custgeom" -> inCustGeom = false
                            "p" -> {
                                val rawParaText = currentParaSb?.toString()?.trim() ?: ""
                                if (rawParaText.isNotEmpty() && currentParagraphs.size < 1024) {
                                    val displayParaText = if (curParaIsBullet && !rawParaText.startsWith("•")) {
                                        "• $rawParaText"
                                    } else {
                                        rawParaText
                                    }
                                    currentParagraphs.add(displayParaText)
                                    currentRichParagraphs.add(
                                        SlideParagraph(
                                            text = displayParaText,
                                            fontSizePt = curParaFontSizePt,
                                            isBold = curParaBold,
                                            isItalic = curParaItalic,
                                            isUnderline = curParaUnderline,
                                            isStrike = curParaStrike,
                                            textColor = curParaColor,
                                            alignment = if (curParaHasExplicitAlign) curParaAlign else (currentShapeAlignment ?: Layout.Alignment.ALIGN_NORMAL),
                                            isBullet = curParaIsBullet,
                                            runs = currentParaRuns.toList()
                                        )
                                    )
                                    if (!isMasterOrLayout && summarySb.length < 65536) {
                                        summarySb.append(displayParaText).append("\n")
                                    }
                                }
                                currentParaSb = null
                                currentParaRuns.clear()
                            }
                            "tc" -> {
                                val cellTxt = currentCellSb?.toString()?.trim() ?: ""
                                currentRowCells?.add(cellTxt)
                                currentRichRowCells?.add(
                                    SlideTableCell(
                                        text = cellTxt,
                                        fillColor = curTcFillColor,
                                        textColor = curTcTextColor,
                                        isBold = curTcBold,
                                        gridSpan = curTcGridSpan,
                                        rowSpan = curTcRowSpan,
                                        isMergedContinuation = curTcMerged
                                    )
                                )
                                currentCellSb = null
                            }
                            "tr" -> {
                                val row = currentRowCells
                                val richRow = currentRichRowCells
                                if (!row.isNullOrEmpty()) {
                                    tableRows.add(row.toList())
                                    if (richRow != null) richTableRows.add(richRow.toList())
                                    if (!isMasterOrLayout && summarySb.length < 65536) {
                                        summarySb.append(row.joinToString(" | ")).append("\n")
                                    }
                                }
                                currentRowCells = null
                                currentRichRowCells = null
                            }
                            "sp", "cxnsp" -> {
                                val mappedBounds = if (hasBounds) {
                                    mapRectThroughGroupStack(RectF(curX, curY, curX + curW, curY + curH), groupStack)
                                } else null

                                val localEffectiveFill = if (explicitNoFill) null else (fillColor ?: styleRefFillColor)

                                if (hasPlaceholderTag) {
                                    val spec = PlaceholderStyleSpec(
                                        rect = mappedBounds,
                                        fillColor = localEffectiveFill,
                                        strokeColor = strokeColor,
                                        textColor = textRunColor,
                                        fontSizePt = currentFontSizePt,
                                        alignment = currentShapeAlignment
                                    )
                                    if (currentPhIdx.isNotEmpty()) {
                                        discoveredPlaceholders["idx:$currentPhIdx"] = spec
                                    }
                                    if (currentPhType.isNotEmpty()) {
                                        discoveredPlaceholders["type:$currentPhType"] = spec
                                    }
                                }

                                val inheritedPhSpec: PlaceholderStyleSpec? = if (hasPlaceholderTag && inheritedContext != null) {
                                    (if (currentPhIdx.isNotEmpty()) inheritedContext.placeholderStyles["idx:$currentPhIdx"] else null)
                                        ?: (if (currentPhType.isNotEmpty()) inheritedContext.placeholderStyles["type:$currentPhType"] else null)
                                } else null

                                val masterFallbackTextCol = if (isTitleShape) {
                                    inheritedContext?.masterTitleTextColor
                                } else if (hasPlaceholderTag) {
                                    inheritedContext?.masterBodyTextColor
                                } else null

                                val masterFallbackFontPt = if (isTitleShape) {
                                    inheritedContext?.masterTitleFontSizePt
                                } else if (hasPlaceholderTag) {
                                    inheritedContext?.masterBodyFontSizePt
                                } else null

                                val effectiveFill = if (explicitNoFill) null else (localEffectiveFill ?: inheritedPhSpec?.fillColor)
                                val effectiveStroke = strokeColor ?: inheritedPhSpec?.strokeColor
                                val effectiveTextCol = textRunColor ?: inheritedPhSpec?.textColor ?: masterFallbackTextCol
                                val effectiveFontPt = currentFontSizePt ?: inheritedPhSpec?.fontSizePt ?: masterFallbackFontPt
                                val inheritedAlign = inheritedPhSpec?.alignment

                                val finalRichParas = if (inheritedAlign != null) {
                                    currentRichParagraphs.map { p ->
                                        if (p.alignment == Layout.Alignment.ALIGN_NORMAL && currentShapeAlignment == null) {
                                            p.copy(alignment = inheritedAlign)
                                        } else p
                                    }
                                } else {
                                    currentRichParagraphs.toList()
                                }

                                val shouldEmitShape = if (isMasterOrLayout) {
                                    !hasPlaceholderTag && currentParagraphs.isEmpty() &&
                                            (effectiveFill != null || effectiveStroke != null || shapeBlipMediaPath != null || currentShapeCustomPaths.isNotEmpty())
                                } else {
                                    currentParagraphs.isNotEmpty() || effectiveFill != null || effectiveStroke != null || shapeBlipMediaPath != null || currentShapeCustomPaths.isNotEmpty()
                                }

                                if (shouldEmitShape) {
                                    if (!hasBounds && currentPhType.isEmpty() && currentPhIdx.isEmpty() &&
                                        elements.none { it is SlideVisualElement.ShapeBox && it.paragraphs.isNotEmpty() } &&
                                        currentParagraphs.size >= 2
                                    ) {
                                        elements.add(
                                            SlideVisualElement.ShapeBox(
                                                shapeId = currentShapeId,
                                                rect = defaultPlaceholderRect("title", 0),
                                                presetGeom = presetGeom,
                                                customPaths = currentShapeCustomPaths.toList(),
                                                fillColor = effectiveFill,
                                                fillEndColor = fillEndColor,
                                                fillMediaPath = shapeBlipMediaPath,
                                                gradAngleDeg = gradAngleDeg,
                                                strokeColor = effectiveStroke,
                                                strokeWidthPt = strokeWidthPt,
                                                isDashedStroke = isDashedStroke,
                                                headArrowType = headArrowType,
                                                tailArrowType = tailArrowType,
                                                rotationDeg = curRotDeg,
                                                flipH = curFlipH,
                                                flipV = curFlipV,
                                                hasDropShadow = curHasShadow,
                                                paragraphs = listOf(currentParagraphs.first()),
                                                richParagraphs = finalRichParas.take(1),
                                                isTitle = true,
                                                fontSizePt = effectiveFontPt ?: 30f,
                                                textColor = effectiveTextCol
                                            )
                                        )
                                        elements.add(
                                            SlideVisualElement.ShapeBox(
                                                shapeId = currentShapeId,
                                                rect = defaultPlaceholderRect("body", 0),
                                                presetGeom = "rect",
                                                fillColor = null,
                                                strokeColor = null,
                                                paragraphs = currentParagraphs.drop(1),
                                                richParagraphs = finalRichParas.drop(1),
                                                isTitle = false,
                                                fontSizePt = effectiveFontPt ?: 20f,
                                                textColor = effectiveTextCol
                                            )
                                        )
                                        bodyPlaceholderCount++
                                    } else {
                                        val rect = mappedBounds ?: inheritedPhSpec?.rect ?: run {
                                            val phRect = defaultPlaceholderRect(currentPhType, bodyPlaceholderCount)
                                            if (!isTitleShape && currentPhType != "subtitle") {
                                                bodyPlaceholderCount++
                                            }
                                            phRect
                                        }
                                        elements.add(
                                            SlideVisualElement.ShapeBox(
                                                shapeId = currentShapeId,
                                                rect = rect,
                                                presetGeom = presetGeom,
                                                customPaths = currentShapeCustomPaths.toList(),
                                                fillColor = effectiveFill,
                                                fillEndColor = fillEndColor,
                                                fillMediaPath = shapeBlipMediaPath,
                                                gradAngleDeg = gradAngleDeg,
                                                strokeColor = effectiveStroke,
                                                strokeWidthPt = strokeWidthPt,
                                                isDashedStroke = isDashedStroke,
                                                headArrowType = headArrowType,
                                                tailArrowType = tailArrowType,
                                                rotationDeg = curRotDeg,
                                                flipH = curFlipH,
                                                flipV = curFlipV,
                                                hasDropShadow = curHasShadow,
                                                paragraphs = if (isMasterOrLayout) emptyList() else currentParagraphs.toList(),
                                                richParagraphs = if (isMasterOrLayout) emptyList() else finalRichParas,
                                                isTitle = isTitleShape,
                                                fontSizePt = effectiveFontPt,
                                                textColor = effectiveTextCol
                                            )
                                        )
                                    }
                                }
                                inSp = false
                                isConnectorShape = false
                            }
                            "pic" -> {
                                val targetPath = picRelId?.let { slideRels[it] }
                                if (targetPath != null && mediaBytesMap.containsKey(targetPath)) {
                                    val inheritedPicRect = if (!hasBounds && hasPlaceholderTag && inheritedContext != null) {
                                        (if (currentPhIdx.isNotEmpty()) inheritedContext.placeholderStyles["idx:$currentPhIdx"]?.rect else null)
                                            ?: (if (currentPhType.isNotEmpty()) inheritedContext.placeholderStyles["type:$currentPhType"]?.rect else null)
                                    } else null
                                    val rawRect = when {
                                        hasBounds -> RectF(curX, curY, curX + curW, curY + curH)
                                        inheritedPicRect != null -> inheritedPicRect
                                        else -> RectF(
                                            slideWidthEmu * 0.10f,
                                            slideHeightEmu * 0.12f,
                                            slideWidthEmu * 0.90f,
                                            slideHeightEmu * 0.88f
                                        )
                                    }
                                    val rect = mapRectThroughGroupStack(rawRect, groupStack)
                                    val isFullSlideMasterBg = isMasterOrLayout &&
                                            rect.width() >= slideWidthEmu * 0.85f &&
                                            rect.height() >= slideHeightEmu * 0.85f
                                    if (!isMasterOrLayout || isFullSlideMasterBg) {
                                        elements.add(
                                            SlideVisualElement.PictureBox(
                                                shapeId = currentShapeId,
                                                rect = rect,
                                                mediaPath = targetPath,
                                                cropLeft = cropL.coerceIn(0f, 0.9f),
                                                cropTop = cropT.coerceIn(0f, 0.9f),
                                                cropRight = cropR.coerceIn(0f, 0.9f),
                                                cropBottom = cropB.coerceIn(0f, 0.9f),
                                                strokeColor = strokeColor,
                                                strokeWidthPt = strokeWidthPt,
                                                presetGeom = presetGeom,
                                                customPaths = currentShapeCustomPaths.toList(),
                                                rotationDeg = curRotDeg,
                                                flipH = curFlipH,
                                                flipV = curFlipV,
                                                hasDropShadow = curHasShadow
                                            )
                                        )
                                    }
                                }
                                inPic = false
                            }
                            "graphicframe" -> {
                                val inheritedFrameRect = if (!hasBounds && hasPlaceholderTag && inheritedContext != null) {
                                    (if (currentPhIdx.isNotEmpty()) inheritedContext.placeholderStyles["idx:$currentPhIdx"]?.rect else null)
                                        ?: (if (currentPhType.isNotEmpty()) inheritedContext.placeholderStyles["type:$currentPhType"]?.rect else null)
                                } else null
                                val rawRect = when {
                                    hasBounds -> RectF(curX, curY, curX + curW, curY + curH)
                                    inheritedFrameRect != null -> inheritedFrameRect
                                    else -> RectF(
                                        slideWidthEmu * 0.08f,
                                        slideHeightEmu * 0.20f,
                                        slideWidthEmu * 0.92f,
                                        slideHeightEmu * 0.88f
                                    )
                                }
                                val frameRect = mapRectThroughGroupStack(rawRect, groupStack)
                                if (tableRows.isNotEmpty()) {
                                    elements.add(SlideVisualElement.TableBox(frameRect, tableRows.toList(), richTableRows.toList()))
                                } else if (chartRelId != null) {
                                    val chartPath = slideRels[chartRelId!!]
                                    val chartBytes = if (chartPath != null) allZipEntries[chartPath] else null
                                    if (chartBytes != null) {
                                        parseChartXml(chartBytes)?.let { parsedChart ->
                                            elements.add(parsedChart.copy(rect = frameRect))
                                            if (!isMasterOrLayout && summarySb.length < 65536) {
                                                if (parsedChart.title.isNotEmpty()) {
                                                    summarySb.append(parsedChart.title).append("\n")
                                                }
                                                parsedChart.seriesList.forEach { ser ->
                                                    summarySb.append(ser.name).append(": ")
                                                        .append(ser.values.joinToString(", ")).append("\n")
                                                }
                                            }
                                        }
                                    }
                                }
                                inGraphicFrame = false
                            }
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}

        val fallbackText = summarySb.toString().trim()
        if (!isMasterOrLayout && elements.isEmpty() && fallbackText.isNotEmpty()) {
            elements.add(
                SlideVisualElement.ShapeBox(
                    rect = RectF(
                        slideWidthEmu * 0.05f,
                        slideHeightEmu * 0.06f,
                        slideWidthEmu * 0.95f,
                        slideHeightEmu * 0.94f
                    ),
                    presetGeom = "rect",
                    fillColor = null,
                    strokeColor = null,
                    paragraphs = fallbackText.lines().filter { it.isNotBlank() },
                    isTitle = false
                )
            )
        }

        val slideObj = ParsedSlide(
            index = slideNumber,
            backgroundColor = bgColor,
            backgroundEndColor = bgEndColor,
            backgroundMediaPath = bgMediaPath,
            transitionType = slideTransitionType,
            transitionDurationMs = slideTransitionDurationMs,
            animatedTargetIds = animatedTargetIds.toList(),
            elements = elements,
            plainTextSummary = fallbackText
        )
        return Triple(slideObj, discoveredPlaceholders, hasExplicitBg)
    }

    private fun computeSlideCardSize(
        canvasWidth: Float = lastCanvasWidth,
        canvasHeight: Float = lastCanvasHeight
    ): Pair<Float, Float> {
        val margin = 2f
        val availW = (canvasWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (canvasHeight - margin * 2f).coerceAtLeast(100f)
        val aspect = (slideWidthEmu / slideHeightEmu.coerceAtLeast(1f)).coerceIn(1.2f, 2.4f)
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

    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return slides.joinToString("\n\n") { it.plainTextSummary }
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        val slide = slides.getOrNull(cleanIdx) ?: return ""
        val metaLine = "[SLIDE_META:transition=${slide.transitionType};dur=${slide.transitionDurationMs};steps=${slide.animatedTargetIds.size.coerceAtMost(15)}]"
        val base = if (slide.speakerNotes.isNotBlank()) {
            "${slide.plainTextSummary}\n\n[Notes]\n${slide.speakerNotes}".trim()
        } else {
            slide.plainTextSummary
        }
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

        val slide = slides.getOrNull(cleanPageIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))) ?: return
        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)

        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f

        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)

        val slideRect = RectF(left, top, left + cardW, top + cardH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (slide.backgroundEndColor != null && slide.backgroundEndColor != slide.backgroundColor) {
                shader = LinearGradient(
                    slideRect.left, slideRect.top,
                    slideRect.left, slideRect.bottom,
                    slide.backgroundColor, slide.backgroundEndColor,
                    Shader.TileMode.CLAMP
                )
            } else {
                color = slide.backgroundColor
            }
        }
        canvas.drawRoundRect(slideRect, 8f, 8f, bgPaint)

        if (slide.backgroundMediaPath != null) {
            val bgBmp = getOrDecodeMediaBitmap(slide.backgroundMediaPath)
            if (bgBmp != null && !bgBmp.isRecycled) {
                canvas.save()
                canvas.clipRect(slideRect)
                canvas.drawBitmap(bgBmp, null, slideRect, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
            }
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(slideRect, 8f, 8f, borderPaint)

        val scaleX = cardW / slideWidthEmu.coerceAtLeast(1f)
        val scaleY = cardH / slideHeightEmu.coerceAtLeast(1f)
        val slideHeightPoints = (slideHeightEmu / 12700f).coerceAtLeast(300f)
        val pxPerPoint = cardH / slideHeightPoints

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 30, 38)
        }
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val hiddenShapeIds: Set<String> = if (animStep in 1..slide.animatedTargetIds.size) {
            slide.animatedTargetIds.subList(animStep, slide.animatedTargetIds.size).toSet()
        } else {
            emptySet()
        }

        canvas.save()
        canvas.clipRect(slideRect)

        for (el in slide.elements) {
            when (el) {
                is SlideVisualElement.PictureBox -> {
                    if (el.shapeId.isNotEmpty() && el.shapeId in hiddenShapeIds) continue
                    val dst = RectF(
                        left + el.rect.left * scaleX,
                        top + el.rect.top * scaleY,
                        left + el.rect.right * scaleX,
                        top + el.rect.bottom * scaleY
                    )
                    val bmp = getOrDecodeMediaBitmap(el.mediaPath)
                    if (bmp != null && !bmp.isRecycled) {
                        val bw = bmp.width
                        val bh = bmp.height
                        val srcLeft = (bw * el.cropLeft).roundToInt().coerceIn(0, bw - 1)
                        val srcTop = (bh * el.cropTop).roundToInt().coerceIn(0, bh - 1)
                        val srcRight = (bw * (1f - el.cropRight)).roundToInt().coerceIn(srcLeft + 1, bw)
                        val srcBottom = (bh * (1f - el.cropBottom)).roundToInt().coerceIn(srcTop + 1, bh)
                        val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

                        canvas.save()
                        if (el.rotationDeg != 0f) {
                            canvas.rotate(el.rotationDeg, dst.centerX(), dst.centerY())
                        }
                        if (el.flipH || el.flipV) {
                            canvas.scale(if (el.flipH) -1f else 1f, if (el.flipV) -1f else 1f, dst.centerX(), dst.centerY())
                        }
                        if (el.hasDropShadow) {
                            val shOff = 4f * pxPerPoint
                            val shRect = RectF(dst.left + shOff, dst.top + shOff, dst.right + shOff, dst.bottom + shOff)
                            drawShapeGeometry(canvas, shRect, el.presetGeom, el.customPaths, shadowPaint)
                        }
                        canvas.save()
                        if (el.customPaths.isNotEmpty() || el.presetGeom.lowercase(Locale.ROOT) !in setOf("rect", "")) {
                            val clipPath = buildShapePath(dst, el.presetGeom, el.customPaths)
                            canvas.clipPath(clipPath)
                        } else {
                            canvas.clipRect(dst)
                        }
                        canvas.drawBitmap(bmp, srcRect, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                        canvas.restore()

                        if (el.strokeColor != null && el.strokeWidthPt > 0f) {
                            shapePaint.shader = null
                            shapePaint.pathEffect = null
                            shapePaint.style = Paint.Style.STROKE
                            shapePaint.strokeWidth = (el.strokeWidthPt * pxPerPoint).coerceAtLeast(1.5f)
                            shapePaint.color = el.strokeColor
                            drawShapeGeometry(canvas, dst, el.presetGeom, el.customPaths, shapePaint)
                        }
                        canvas.restore()
                    }
                }
                is SlideVisualElement.ShapeBox -> {
                    if (el.shapeId.isNotEmpty() && el.shapeId in hiddenShapeIds) continue
                    val dst = RectF(
                        left + el.rect.left * scaleX,
                        top + el.rect.top * scaleY,
                        left + el.rect.right * scaleX,
                        top + el.rect.bottom * scaleY
                    )
                    canvas.save()
                    if (el.rotationDeg != 0f) {
                        canvas.rotate(el.rotationDeg, dst.centerX(), dst.centerY())
                    }
                    if (el.flipH || el.flipV) {
                        canvas.scale(if (el.flipH) -1f else 1f, if (el.flipV) -1f else 1f, dst.centerX(), dst.centerY())
                    }

                    if (el.hasDropShadow && (el.fillColor != null || el.fillMediaPath != null)) {
                        val shOff = 4f * pxPerPoint
                        val shRect = RectF(dst.left + shOff, dst.top + shOff, dst.right + shOff, dst.bottom + shOff)
                        drawShapeGeometry(canvas, shRect, el.presetGeom, el.customPaths, shadowPaint)
                    }

                    if (el.fillMediaPath != null) {
                        val fillBmp = getOrDecodeMediaBitmap(el.fillMediaPath)
                        if (fillBmp != null && !fillBmp.isRecycled) {
                            canvas.save()
                            val clipPath = buildShapePath(dst, el.presetGeom, el.customPaths)
                            canvas.clipPath(clipPath)
                            canvas.drawBitmap(fillBmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                            canvas.restore()
                        }
                    } else if (el.fillColor != null) {
                        shapePaint.style = Paint.Style.FILL
                        shapePaint.pathEffect = null
                        if (el.fillEndColor != null && el.fillEndColor != el.fillColor) {
                            val rad = Math.toRadians(el.gradAngleDeg.toDouble())
                            val cosA = cos(rad).toFloat()
                            val sinA = sin(rad).toFloat()
                            val cx = dst.centerX()
                            val cy = dst.centerY()
                            val r = max(dst.width(), dst.height()) * 0.5f
                            shapePaint.shader = LinearGradient(
                                cx - cosA * r, cy - sinA * r,
                                cx + cosA * r, cy + sinA * r,
                                el.fillColor, el.fillEndColor,
                                Shader.TileMode.CLAMP
                            )
                        } else {
                            shapePaint.shader = null
                            shapePaint.color = el.fillColor
                        }
                        drawShapeGeometry(canvas, dst, el.presetGeom, el.customPaths, shapePaint)
                        shapePaint.shader = null
                    }
                    if (el.strokeColor != null) {
                        shapePaint.shader = null
                        shapePaint.style = Paint.Style.STROKE
                        val sw = (el.strokeWidthPt * pxPerPoint).coerceAtLeast(1.5f)
                        shapePaint.strokeWidth = sw
                        shapePaint.color = el.strokeColor
                        shapePaint.pathEffect = if (el.isDashedStroke) {
                            DashPathEffect(floatArrayOf(sw * 3f, sw * 2f), 0f)
                        } else null
                        drawShapeGeometry(canvas, dst, el.presetGeom, el.customPaths, shapePaint)
                        shapePaint.pathEffect = null

                        if (el.headArrowType != "none" || el.tailArrowType != "none") {
                            drawConnectorArrows(canvas, dst, el, sw, shapePaint)
                        }
                    }
                    canvas.restore()

                    if (el.paragraphs.isNotEmpty()) {
                        canvas.save()
                        if (el.rotationDeg != 0f) {
                            canvas.rotate(el.rotationDeg, dst.centerX(), dst.centerY())
                        }
                        drawShapeRichTextWithAutofit(
                            canvas = canvas,
                            dst = dst,
                            shape = el,
                            slideBgColor = slide.backgroundColor,
                            pxPerPoint = pxPerPoint,
                            cardH = cardH,
                            textPaint = textPaint
                        )
                        canvas.restore()
                    }
                }
                is SlideVisualElement.TableBox -> {
                    val dst = RectF(
                        left + el.rect.left * scaleX,
                        top + el.rect.top * scaleY,
                        left + el.rect.right * scaleX,
                        top + el.rect.bottom * scaleY
                    )
                    drawSlideTable(canvas, dst, el.rows, el.richRows, textPaint, pxPerPoint)
                }
                is SlideVisualElement.ChartBox -> {
                    val dst = RectF(
                        left + el.rect.left * scaleX,
                        top + el.rect.top * scaleY,
                        left + el.rect.right * scaleX,
                        top + el.rect.bottom * scaleY
                    )
                    drawSlideChart(canvas, dst, el, textPaint, shapePaint, pxPerPoint)
                }
            }
        }

        canvas.restore()
        canvas.restore()
    }

    private fun drawConnectorArrows(
        canvas: Canvas,
        dst: RectF,
        el: SlideVisualElement.ShapeBox,
        strokeWidthPx: Float,
        paint: Paint
    ) {
        val x0 = dst.left
        val y0 = dst.top
        val x1 = dst.right
        val y1 = dst.bottom
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 4f) return

        val arrowSize = (strokeWidthPx * 3.8f).coerceIn(8f, 26f)
        val prevStyle = paint.style
        paint.style = Paint.Style.FILL

        fun drawArrowHead(tipX: Float, tipY: Float, fromX: Float, fromY: Float, type: String) {
            if (type == "none") return
            if (type == "oval" || type == "diamond") {
                canvas.drawCircle(tipX, tipY, arrowSize * 0.45f, paint)
                return
            }
            val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
            val spread = PI / 6.0
            val p = Path().apply {
                moveTo(tipX, tipY)
                lineTo(
                    (tipX - arrowSize * cos(angle - spread)).toFloat(),
                    (tipY - arrowSize * sin(angle - spread)).toFloat()
                )
                lineTo(
                    (tipX - arrowSize * cos(angle + spread)).toFloat(),
                    (tipY - arrowSize * sin(angle + spread)).toFloat()
                )
                close()
            }
            canvas.drawPath(p, paint)
        }

        if (el.headArrowType != "none") {
            drawArrowHead(x0, y0, x1, y1, el.headArrowType)
        }
        if (el.tailArrowType != "none") {
            drawArrowHead(x1, y1, x0, y0, el.tailArrowType)
        }
        paint.style = prevStyle
    }

    private fun buildRichParagraphCharSequence(
        p: SlideParagraph,
        defaultTextColor: Int
    ): CharSequence {
        if (p.runs.size <= 1) return p.text
        val ssb = SpannableStringBuilder()
        if (p.isBullet && !p.runs.first().text.startsWith("•")) {
            ssb.append("• ")
        }
        for (run in p.runs) {
            val start = ssb.length
            ssb.append(run.text)
            val end = ssb.length
            if (end > start) {
                val col = run.textColor ?: p.textColor ?: defaultTextColor
                ssb.setSpan(ForegroundColorSpan(col), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (run.isBold && run.isItalic) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (run.isBold) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (run.isItalic) {
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (run.isUnderline) {
                    ssb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (run.isStrike) {
                    ssb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return ssb
    }

    private fun drawShapeRichTextWithAutofit(
        canvas: Canvas,
        dst: RectF,
        shape: SlideVisualElement.ShapeBox,
        slideBgColor: Int,
        pxPerPoint: Float,
        cardH: Float,
        textPaint: TextPaint
    ) {
        val padH = (dst.width() * 0.04f).coerceIn(6f, 22f)
        val padV = (dst.height() * 0.04f).coerceIn(4f, 18f)
        val innerW = (dst.width() - padH * 2f).toInt().coerceAtLeast(36)
        val availH = (dst.height() - padV * 2f).coerceAtLeast(24f)

        val defaultTextColor = shape.textColor ?: if (shape.fillColor != null && isDarkColor(shape.fillColor)) {
            Color.rgb(246, 242, 234)
        } else if (shape.fillColor == null && isDarkColor(slideBgColor)) {
            Color.WHITE
        } else {
            Color.rgb(34, 28, 22)
        }

        val paras: List<SlideParagraph> = if (shape.richParagraphs.isNotEmpty()) {
            shape.richParagraphs
        } else {
            shape.paragraphs.mapIndexed { idx, txt ->
                SlideParagraph(
                    text = txt,
                    fontSizePt = shape.fontSizePt ?: if (shape.isTitle) 32f else if (idx == 0 && shape.paragraphs.size > 1 && txt.length <= 36) 22f else 16f,
                    isBold = shape.isTitle || (idx == 0 && shape.paragraphs.size > 1 && txt.length <= 42),
                    textColor = shape.textColor
                )
            }
        }

        var autofitFactor = 1.0f
        var builtLayouts: List<Pair<StaticLayout, Int>> = emptyList()
        var totalTextH = 0f

        for (attempt in 0..5) {
            val candidateLayouts = ArrayList<Pair<StaticLayout, Int>>(paras.size)
            var sumH = 0f
            for ((idx, p) in paras.withIndex()) {
                val basePt = p.fontSizePt ?: shape.fontSizePt ?: when {
                    shape.isTitle -> 32f
                    idx == 0 && paras.size > 1 && p.text.length <= 42 -> 20f
                    else -> 15.5f
                }
                val pxSize = (basePt * pxPerPoint * autofitFactor).coerceIn(10f, cardH * 0.14f)
                val paraPaint = TextPaint(textPaint).apply {
                    textSize = pxSize
                    typeface = when {
                        p.isBold && p.isItalic -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                        p.isBold || shape.isTitle -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                        p.isItalic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                        else -> Typeface.SERIF
                    }
                    isUnderlineText = p.isUnderline
                    isStrikeThruText = p.isStrike
                }
                val pColor = p.textColor ?: defaultTextColor
                val richSeq = buildRichParagraphCharSequence(p, pColor)
                val layout = StaticLayout.Builder
                    .obtain(richSeq, 0, richSeq.length, paraPaint, innerW)
                    .setAlignment(p.alignment)
                    .setLineSpacing(0f, 1.08f)
                    .setIncludePad(false)
                    .build()
                candidateLayouts.add(layout to pColor)
                sumH += layout.height
                if (idx < paras.lastIndex) {
                    sumH += (pxSize * 0.32f).coerceAtLeast(4f)
                }
            }
            builtLayouts = candidateLayouts
            totalTextH = sumH
            if (totalTextH <= availH || autofitFactor <= 0.58f) {
                break
            }
            val ratio = (availH / totalTextH).coerceIn(0.65f, 0.88f)
            autofitFactor *= ratio
        }

        val hasCardBackground = shape.fillColor != null || shape.strokeColor != null || shape.fillMediaPath != null
        val startY = if (hasCardBackground && totalTextH < availH) {
            dst.top + padV + (availH - totalTextH) * 0.38f
        } else {
            dst.top + padV
        }

        canvas.save()
        canvas.clipRect(dst)
        var curY = startY
        for ((idx, pair) in builtLayouts.withIndex()) {
            val (layout, col) = pair
            layout.paint.color = col
            canvas.save()
            canvas.translate(dst.left + padH, curY)
            layout.draw(canvas)
            canvas.restore()
            curY += layout.height
            if (idx < builtLayouts.lastIndex) {
                curY += (layout.paint.textSize * 0.32f).coerceAtLeast(4f)
            }
        }
        canvas.restore()
    }

    private fun drawSlideChart(
        canvas: Canvas,
        rect: RectF,
        chart: SlideVisualElement.ChartBox,
        textPaint: TextPaint,
        shapePaint: Paint,
        pxPerPoint: Float
    ) {
        val palette = intArrayOf(
            themeColors["accent1"] ?: Color.rgb(79, 129, 189),
            themeColors["accent2"] ?: Color.rgb(192, 80, 77),
            themeColors["accent3"] ?: Color.rgb(155, 187, 89),
            themeColors["accent4"] ?: Color.rgb(128, 100, 162),
            themeColors["accent5"] ?: Color.rgb(75, 172, 198),
            themeColors["accent6"] ?: Color.rgb(247, 150, 70)
        )

        shapePaint.shader = null
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = Color.rgb(248, 250, 253)
        canvas.drawRoundRect(rect, 8f, 8f, shapePaint)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = 1.5f
        shapePaint.color = Color.rgb(210, 216, 226)
        canvas.drawRoundRect(rect, 8f, 8f, shapePaint)

        var plotTop = rect.top + 16f
        if (chart.title.isNotEmpty()) {
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = (15f * pxPerPoint).coerceIn(12f, 24f)
            textPaint.color = Color.rgb(30, 35, 45)
            canvas.drawText(chart.title, rect.left + 16f, plotTop + 14f, textPaint)
            plotTop += 28f
        }

        val plotRect = RectF(rect.left + 36f, plotTop + 8f, rect.right - 20f, rect.bottom - 28f)
        if (plotRect.width() <= 20f || plotRect.height() <= 20f || chart.seriesList.isEmpty()) return

        if (chart.chartKind == "pie" || chart.chartKind == "doughnut") {
            val firstSeries = chart.seriesList.first()
            val total = firstSeries.values.map { it.coerceAtLeast(0f) }.sum().coerceAtLeast(0.001f)
            val radius = minOf(plotRect.width(), plotRect.height()) * 0.42f
            val cx = plotRect.centerX()
            val cy = plotRect.centerY()
            val pieRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            var startAngle = -90f
            firstSeries.values.forEachIndexed { idx, v ->
                val sweep = (v.coerceAtLeast(0f) / total) * 360f
                shapePaint.style = Paint.Style.FILL
                shapePaint.color = palette[idx % palette.size]
                canvas.drawArc(pieRect, startAngle, sweep, true, shapePaint)
                startAngle += sweep
            }
            if (chart.chartKind == "doughnut") {
                shapePaint.style = Paint.Style.FILL
                shapePaint.color = Color.rgb(248, 250, 253)
                canvas.drawCircle(cx, cy, radius * 0.52f, shapePaint)
            }
            return
        }

        val allVals = chart.seriesList.flatMap { it.values }
        val maxVal = (allVals.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val catCount = chart.seriesList.maxOf { it.values.size }.coerceAtLeast(1)
        val groupW = plotRect.width() / catCount
        val seriesCount = chart.seriesList.size.coerceAtLeast(1)
        val barW = (groupW * 0.72f) / seriesCount

        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = 1.5f
        shapePaint.color = Color.rgb(160, 170, 185)
        canvas.drawLine(plotRect.left, plotRect.bottom, plotRect.right, plotRect.bottom, shapePaint)
        canvas.drawLine(plotRect.left, plotRect.top, plotRect.left, plotRect.bottom, shapePaint)

        for ((sIdx, series) in chart.seriesList.withIndex()) {
            val color = palette[sIdx % palette.size]
            if (chart.chartKind in setOf("line", "area", "scatter", "radar")) {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = 3f
                shapePaint.color = color
                var prevX = 0f
                var prevY = 0f
                for ((i, v) in series.values.withIndex()) {
                    val x = plotRect.left + (i + 0.5f) * groupW
                    val y = plotRect.bottom - (v.coerceAtLeast(0f) / maxVal) * plotRect.height()
                    if (i > 0 && chart.chartKind != "scatter") {
                        canvas.drawLine(prevX, prevY, x, y, shapePaint)
                    }
                    shapePaint.style = Paint.Style.FILL
                    canvas.drawCircle(x, y, 4.5f, shapePaint)
                    shapePaint.style = Paint.Style.STROKE
                    prevX = x
                    prevY = y
                }
            } else {
                shapePaint.style = Paint.Style.FILL
                shapePaint.color = color
                for ((i, v) in series.values.withIndex()) {
                    val barH = (v.coerceAtLeast(0f) / maxVal) * plotRect.height()
                    val bLeft = plotRect.left + i * groupW + groupW * 0.14f + sIdx * barW
                    val bTop = plotRect.bottom - barH
                    canvas.drawRect(bLeft, bTop, bLeft + barW * 0.9f, plotRect.bottom, shapePaint)
                }
            }
        }
    }

    private fun isDarkColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luma = 0.299 * r + 0.587 * g + 0.114 * b
        return luma < 135.0
    }

    private fun buildShapePath(
        rect: RectF,
        geom: String,
        customPaths: List<CustomVectorPath>
    ): Path {
        val path = Path()
        if (customPaths.isNotEmpty()) {
            for (cp in customPaths) {
                val pw = if (cp.pathWidth > 0f) cp.pathWidth else rect.width().coerceAtLeast(1f)
                val ph = if (cp.pathHeight > 0f) cp.pathHeight else rect.height().coerceAtLeast(1f)
                val sx = rect.width() / pw
                val sy = rect.height() / ph
                for (cmd in cp.commands) {
                    when (cmd) {
                        is CustomPathCmd.MoveTo -> path.moveTo(rect.left + cmd.x * sx, rect.top + cmd.y * sy)
                        is CustomPathCmd.LineTo -> path.lineTo(rect.left + cmd.x * sx, rect.top + cmd.y * sy)
                        is CustomPathCmd.CubicBezTo -> path.cubicTo(
                            rect.left + cmd.x1 * sx, rect.top + cmd.y1 * sy,
                            rect.left + cmd.x2 * sx, rect.top + cmd.y2 * sy,
                            rect.left + cmd.x3 * sx, rect.top + cmd.y3 * sy
                        )
                        is CustomPathCmd.QuadBezTo -> path.quadTo(
                            rect.left + cmd.x1 * sx, rect.top + cmd.y1 * sy,
                            rect.left + cmd.x2 * sx, rect.top + cmd.y2 * sy
                        )
                        is CustomPathCmd.Close -> path.close()
                    }
                }
            }
            return path
        }

        val w = rect.width()
        val h = rect.height()
        when (geom.lowercase(Locale.ROOT)) {
            "line", "straightconnector1" -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right, rect.bottom)
            }
            "bentconnector2", "bentconnector3", "bentconnector4" -> {
                val midX = rect.centerX()
                path.moveTo(rect.left, rect.top)
                path.lineTo(midX, rect.top)
                path.lineTo(midX, rect.bottom)
                path.lineTo(rect.right, rect.bottom)
            }
            "curvedconnector2", "curvedconnector3", "curvedconnector4" -> {
                val midX = rect.centerX()
                path.moveTo(rect.left, rect.top)
                path.cubicTo(midX, rect.top, midX, rect.bottom, rect.right, rect.bottom)
            }
            "ellipse", "oval", "donut", "pie", "chord" -> path.addOval(rect, Path.Direction.CW)
            "roundrect", "sniproundrect", "round1rect", "round2samerect", "round2diagrect" -> {
                val r = minOf(w, h) * 0.16f
                path.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            "plaque" -> {
                val c = minOf(w, h) * 0.11f
                path.moveTo(rect.left + c, rect.top)
                path.lineTo(rect.right - c, rect.top)
                path.quadTo(rect.right, rect.top, rect.right, rect.top + c)
                path.lineTo(rect.right, rect.bottom - c)
                path.quadTo(rect.right, rect.bottom, rect.right - c, rect.bottom)
                path.lineTo(rect.left + c, rect.bottom)
                path.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - c)
                path.lineTo(rect.left, rect.top + c)
                path.quadTo(rect.left, rect.top, rect.left + c, rect.top)
                path.close()
            }
            "triangle", "isoscelestriangle" -> {
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "rtriangle" -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "diamond" -> {
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.centerX(), rect.bottom)
                path.lineTo(rect.left, rect.centerY())
                path.close()
            }
            "parallelogram" -> {
                val dx = w * 0.2f
                path.moveTo(rect.left + dx, rect.top)
                path.lineTo(rect.right, rect.top)
                path.lineTo(rect.right - dx, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "trapezoid" -> {
                val dx = w * 0.18f
                path.moveTo(rect.left + dx, rect.top)
                path.lineTo(rect.right - dx, rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "pentagon", "homeplate" -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right - w * 0.25f, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - w * 0.25f, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "chevron" -> {
                val d = w * 0.22f
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right - d, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - d, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.lineTo(rect.left + d, rect.centerY())
                path.close()
            }
            "hexagon" -> {
                val dx = w * 0.22f
                path.moveTo(rect.left + dx, rect.top)
                path.lineTo(rect.right - dx, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - dx, rect.bottom)
                path.lineTo(rect.left + dx, rect.bottom)
                path.lineTo(rect.left, rect.centerY())
                path.close()
            }
            "octagon" -> {
                val dx = w * 0.28f
                val dy = h * 0.28f
                path.moveTo(rect.left + dx, rect.top)
                path.lineTo(rect.right - dx, rect.top)
                path.lineTo(rect.right, rect.top + dy)
                path.lineTo(rect.right, rect.bottom - dy)
                path.lineTo(rect.right - dx, rect.bottom)
                path.lineTo(rect.left + dx, rect.bottom)
                path.lineTo(rect.left, rect.bottom - dy)
                path.lineTo(rect.left, rect.top + dy)
                path.close()
            }
            "rightarrow", "notchedrightarrow", "stripedrightarrow" -> {
                val headX = rect.right - w * 0.32f
                val shaftTop = rect.top + h * 0.25f
                val shaftBot = rect.bottom - h * 0.25f
                path.moveTo(rect.left, shaftTop)
                path.lineTo(headX, shaftTop)
                path.lineTo(headX, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(headX, rect.bottom)
                path.lineTo(headX, shaftBot)
                path.lineTo(rect.left, shaftBot)
                path.close()
            }
            "leftarrow" -> {
                val headX = rect.left + w * 0.32f
                val shaftTop = rect.top + h * 0.25f
                val shaftBot = rect.bottom - h * 0.25f
                path.moveTo(rect.right, shaftTop)
                path.lineTo(headX, shaftTop)
                path.lineTo(headX, rect.top)
                path.lineTo(rect.left, rect.centerY())
                path.lineTo(headX, rect.bottom)
                path.lineTo(headX, shaftBot)
                path.lineTo(rect.right, shaftBot)
                path.close()
            }
            "uparrow" -> {
                val headY = rect.top + h * 0.32f
                val shaftLeft = rect.left + w * 0.25f
                val shaftRight = rect.right - w * 0.25f
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, headY)
                path.lineTo(shaftRight, headY)
                path.lineTo(shaftRight, rect.bottom)
                path.lineTo(shaftLeft, rect.bottom)
                path.lineTo(shaftLeft, headY)
                path.lineTo(rect.left, headY)
                path.close()
            }
            "downarrow" -> {
                val headY = rect.bottom - h * 0.32f
                val shaftLeft = rect.left + w * 0.25f
                val shaftRight = rect.right - w * 0.25f
                path.moveTo(shaftLeft, rect.top)
                path.lineTo(shaftRight, rect.top)
                path.lineTo(shaftRight, headY)
                path.lineTo(rect.right, headY)
                path.lineTo(rect.centerX(), rect.bottom)
                path.lineTo(rect.left, headY)
                path.lineTo(shaftLeft, headY)
                path.close()
            }
            "star5", "star4", "star6", "star8" -> {
                val points = geom.filter { it.isDigit() }.toIntOrNull() ?: 5
                val cx = rect.centerX()
                val cy = rect.centerY()
                val outerR = minOf(w, h) * 0.5f
                val innerR = outerR * 0.42f
                for (i in 0 until points * 2) {
                    val r = if (i % 2 == 0) outerR else innerR
                    val a = -PI / 2.0 + i * PI / points
                    val px = cx + (r * cos(a)).toFloat()
                    val py = cy + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            else -> path.addRect(rect, Path.Direction.CW)
        }
        return path
    }

    private fun drawShapeGeometry(
        canvas: Canvas,
        rect: RectF,
        geom: String,
        customPaths: List<CustomVectorPath>,
        paint: Paint
    ) {
        val path = buildShapePath(rect, geom, customPaths)
        canvas.drawPath(path, paint)
    }

    private fun drawSlideTable(
        canvas: Canvas,
        rect: RectF,
        rows: List<List<String>>,
        richRows: List<List<SlideTableCell>>,
        textPaint: TextPaint,
        pxPerPoint: Float
    ) {
        if (rows.isEmpty()) return
        val rowCount = rows.size
        val colCount = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val cellW = rect.width() / colCount
        val cellH = (rect.height() / rowCount).coerceAtLeast(20f)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(160, 168, 184)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (r in 0 until rowCount) {
            val row = rows[r]
            val richRow = richRows.getOrNull(r)
            for (c in 0 until colCount) {
                val richCell = richRow?.getOrNull(c)
                if (richCell?.isMergedContinuation == true) continue

                val spanC = (richCell?.gridSpan ?: 1).coerceIn(1, colCount - c)
                val spanR = (richCell?.rowSpan ?: 1).coerceIn(1, rowCount - r)
                val cellRect = RectF(
                    rect.left + c * cellW,
                    rect.top + r * cellH,
                    rect.left + (c + spanC) * cellW,
                    rect.top + (r + spanR) * cellH
                )

                val bgCol = richCell?.fillColor ?: if (r == 0) Color.rgb(232, 236, 245) else Color.WHITE
                fillPaint.color = bgCol
                canvas.drawRect(cellRect, fillPaint)
                canvas.drawRect(cellRect, gridPaint)

                val txt = richCell?.text ?: row.getOrNull(c) ?: ""
                if (txt.isNotEmpty()) {
                    textPaint.textSize = (13.5f * pxPerPoint).coerceIn(11f, 26f)
                    textPaint.color = richCell?.textColor ?: if (isDarkColor(bgCol)) Color.WHITE else Color.rgb(30, 32, 40)
                    textPaint.isFakeBoldText = richCell?.isBold == true || r == 0
                    val availW = (cellRect.width() - 10f).toInt().coerceAtLeast(16)
                    val layout = StaticLayout.Builder
                        .obtain(txt, 0, txt.length, textPaint, availW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build()
                    canvas.save()
                    canvas.clipRect(cellRect)
                    val ty = cellRect.top + ((cellRect.height() - layout.height) / 2f).coerceAtLeast(4f)
                    canvas.translate(cellRect.left + 5f, ty)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
        }
        textPaint.isFakeBoldText = false
    }

    private fun decodeSvgMediaBytesToBitmap(svgBytes: ByteArray): Bitmap? {
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
                        val vb = getAttrIgnoreCase(parser, "viewBox")
                        if (!vb.isNullOrBlank()) {
                            val nums = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                            if (nums.size >= 4 && nums[2] > 0f && nums[3] > 0f) {
                                vbW = nums[2]
                                vbH = nums[3]
                            }
                        } else {
                            getAttrIgnoreCase(parser, "width")?.removeSuffix("px")?.toFloatOrNull()?.let { if (it > 0f) vbW = it }
                            getAttrIgnoreCase(parser, "height")?.removeSuffix("px")?.toFloatOrNull()?.let { if (it > 0f) vbH = it }
                        }
                    } else if (name == "path") {
                        val d = getAttrIgnoreCase(parser, "d")
                        if (!d.isNullOrBlank() && d.length <= 65536) {
                            val p = try { PathParser.createPathFromPathData(d) } catch (_: Throwable) { null }
                            if (p != null) {
                                val fillStr = getAttrIgnoreCase(parser, "fill")
                                val col = if (fillStr != null && fillStr.startsWith("#")) {
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

    private fun getOrDecodeMediaBitmap(path: String): Bitmap? {
        synchronized(decodedBitmapCache) {
            decodedBitmapCache.get(path)?.let { if (!it.isRecycled) return it }
        }
        val bytes = mediaBytesMap[path] ?: return null
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            var decoded: Bitmap? = null
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                var sample = 1
                while (boundsOpts.outWidth / sample > 1600 || boundsOpts.outHeight / sample > 1600) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            }
            if (decoded == null && TiffCleanRoomDecoder.isTiffHeader(bytes, bytes.size)) {
                decoded = TiffCleanRoomDecoder.decodeFromBytes(bytes, 0)
            }
            if (decoded == null && path.endsWith(".svg", ignoreCase = true)) {
                decoded = decodeSvgMediaBytesToBitmap(bytes)
            }
            if (decoded == null) {
                decoded = EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes)
            }
            if (decoded != null) {
                synchronized(decodedBitmapCache) {
                    decodedBitmapCache.put(path, decoded)
                }
            }
            decoded
        } catch (_: Throwable) {
            null
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        for (i in startPage.coerceAtLeast(0) until slides.size) {
            if (slides[i].plainTextSummary.contains(query, ignoreCase = true) ||
                slides[i].speakerNotes.contains(query, ignoreCase = true)
            ) {
                return i.toLong()
            }
        }
        return -1L
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        synchronized(decodedBitmapCache) {
            decodedBitmapCache.evictAll()
        }
        mediaBytesMap.clear()
        parsedLayoutCache.clear()
        parsedMasterCache.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}

class OdpPresentationSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    override val isPresentationContent: Boolean = true

    data class OdpStyleSpec(
        val fillColor: Int? = null,
        val strokeColor: Int? = null,
        val textColor: Int? = null,
        val fontSizePt: Float? = null,
        val isBold: Boolean = false
    )

    data class OdpFrameElement(
        val rectCm: RectF?,
        val paragraphs: List<String>,
        val tableRows: List<List<String>> = emptyList(),
        val imagePath: String?,
        val fillColor: Int?,
        val strokeColor: Int? = null,
        val textColor: Int? = null,
        val fontSizePt: Float? = null,
        val isLineConnector: Boolean = false
    )

    data class OdpSlide(
        val backgroundColor: Int = Color.WHITE,
        val frames: List<OdpFrameElement>,
        val plainText: String
    )

    private val slides = mutableListOf<OdpSlide>()
    private val mediaMap = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
    private var pageWidthCm = 28.0f
    private var pageHeightCm = 15.75f
    @Volatile private var lastCanvasWidth = 1920f
    @Volatile private var lastCanvasHeight = 1080f

    init {
        parseOdp()
    }

    private fun parseLengthToCm(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        return when {
            s.endsWith("cm") -> s.removeSuffix("cm").trim().toFloatOrNull()
            s.endsWith("mm") -> s.removeSuffix("mm").trim().toFloatOrNull()?.div(10f)
            s.endsWith("in") -> s.removeSuffix("in").trim().toFloatOrNull()?.times(2.54f)
            s.endsWith("pt") -> s.removeSuffix("pt").trim().toFloatOrNull()?.times(0.0352778f)
            s.endsWith("px") -> s.removeSuffix("px").trim().toFloatOrNull()?.times(0.026458f)
            else -> s.toFloatOrNull()
        }
    }

    private fun parseFontSizePt(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        return when {
            s.endsWith("pt") -> s.removeSuffix("pt").trim().toFloatOrNull()
            s.endsWith("px") -> s.removeSuffix("px").trim().toFloatOrNull()?.times(0.75f)
            s.endsWith("cm") -> s.removeSuffix("cm").trim().toFloatOrNull()?.times(28.346f)
            else -> null
        }
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

    private fun parseOdp() {
        try {
            val entries = OfficeZipContainerLoader.readZipEntries(
                pfd = pfd,
                maxTotalBytes = 96 * 1024 * 1024L
            ) { normName ->
                normName.equals("content.xml", ignoreCase = true) ||
                        normName.equals("styles.xml", ignoreCase = true) ||
                        normName.startsWith("Pictures/", ignoreCase = true)
            }

            for ((k, v) in entries) {
                if (k.startsWith("Pictures/", ignoreCase = true)) {
                    mediaMap[k] = v
                }
            }

            val contentBytes = entries["content.xml"]
            if (contentBytes == null) {
                lastErrorMsg = "В контейнере ODP отсутствует обязательный файл content.xml."
                return
            }

            val styleMap = HashMap<String, OdpStyleSpec>()
            for (xmlSource in listOfNotNull(entries["styles.xml"], contentBytes)) {
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(xmlSource), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var activeStyleName: String? = null
                    var curFill: Int? = null
                    var curStroke: Int? = null
                    var curTextCol: Int? = null
                    var curFontPt: Float? = null
                    var curBold = false

                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val local = sp.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                        if (ev == XmlPullParser.START_TAG) {
                            when (local) {
                                "style" -> {
                                    activeStyleName = findAttrBySuffix(sp, "name")
                                    curFill = null
                                    curStroke = null
                                    curTextCol = null
                                    curFontPt = null
                                    curBold = false
                                }
                                "page-layout-properties" -> {
                                    parseLengthToCm(findAttrBySuffix(sp, "page-width"))?.let { if (it > 5f) pageWidthCm = it }
                                    parseLengthToCm(findAttrBySuffix(sp, "page-height"))?.let { if (it > 5f) pageHeightCm = it }
                                }
                                "graphic-properties", "drawing-page-properties" -> if (activeStyleName != null) {
                                    val drawFill = findAttrBySuffix(sp, "fill")
                                    if (!drawFill.equals("none", ignoreCase = true)) {
                                        val hex = findAttrBySuffix(sp, "fill-color") ?: findAttrBySuffix(sp, "background-color")
                                        if (!hex.isNullOrBlank() && hex.startsWith("#")) {
                                            try { curFill = Color.parseColor(hex) } catch (_: Exception) {}
                                        }
                                    }
                                    val strokeMode = findAttrBySuffix(sp, "stroke")
                                    if (!strokeMode.equals("none", ignoreCase = true)) {
                                        val sHex = findAttrBySuffix(sp, "stroke-color")
                                        if (!sHex.isNullOrBlank() && sHex.startsWith("#")) {
                                            try { curStroke = Color.parseColor(sHex) } catch (_: Exception) {}
                                        }
                                    }
                                }
                                "text-properties" -> if (activeStyleName != null) {
                                    val fgHex = findAttrBySuffix(sp, "color")
                                    if (!fgHex.isNullOrBlank() && fgHex.startsWith("#")) {
                                        try { curTextCol = Color.parseColor(fgHex) } catch (_: Exception) {}
                                    }
                                    parseFontSizePt(findAttrBySuffix(sp, "font-size"))?.let { curFontPt = it }
                                    if (findAttrBySuffix(sp, "font-weight").equals("bold", ignoreCase = true)) {
                                        curBold = true
                                    }
                                }
                            }
                        } else if (ev == XmlPullParser.END_TAG && local == "style") {
                            val sName = activeStyleName
                            if (sName != null) {
                                styleMap[sName] = OdpStyleSpec(curFill, curStroke, curTextCol, curFontPt, curBold)
                            }
                            activeStyleName = null
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var inPage = false
            var currentPageBgColor = Color.WHITE
            val currentFrames = mutableListOf<OdpFrameElement>()
            val currentPageSummary = StringBuilder()

            var inFrame = false
            var isLineShape = false
            var frameRect: RectF? = null
            var frameStyle: OdpStyleSpec? = null
            var frameImage: String? = null
            val frameParas = mutableListOf<String>()
            val frameTableRows = mutableListOf<List<String>>()
            var currentTableRow: MutableList<String>? = null
            var currentTableCellSb: StringBuilder? = null
            var currentPara: StringBuilder? = null

            while (ev != XmlPullParser.END_DOCUMENT && slides.size < 2000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "page" -> {
                                inPage = true
                                val pageStyleName = findAttrBySuffix(parser, "style-name")
                                currentPageBgColor = pageStyleName?.let { styleMap[it]?.fillColor } ?: Color.WHITE
                                currentFrames.clear()
                                currentPageSummary.setLength(0)
                            }
                            "frame", "custom-shape", "rect", "ellipse", "line", "connector" -> {
                                if (inPage && !inFrame && currentFrames.size < 1024) {
                                    inFrame = true
                                    isLineShape = (local == "line" || local == "connector")
                                    if (isLineShape) {
                                        val x1 = parseLengthToCm(findAttrBySuffix(parser, "x1")) ?: 0f
                                        val y1 = parseLengthToCm(findAttrBySuffix(parser, "y1")) ?: 0f
                                        val x2 = parseLengthToCm(findAttrBySuffix(parser, "x2")) ?: 0f
                                        val y2 = parseLengthToCm(findAttrBySuffix(parser, "y2")) ?: 0f
                                        frameRect = RectF(x1, y1, x2, y2)
                                    } else {
                                        val x = parseLengthToCm(findAttrBySuffix(parser, "x"))
                                        val y = parseLengthToCm(findAttrBySuffix(parser, "y"))
                                        val w = parseLengthToCm(findAttrBySuffix(parser, "width"))
                                        val h = parseLengthToCm(findAttrBySuffix(parser, "height"))
                                        frameRect = if (x != null && y != null && w != null && h != null && w > 0f && h > 0f) {
                                            RectF(x, y, x + w, y + h)
                                        } else null
                                    }
                                    val sName = findAttrBySuffix(parser, "style-name") ?: findAttrBySuffix(parser, "text-style-name")
                                    frameStyle = if (sName != null) styleMap[sName] else null
                                    frameImage = null
                                    frameParas.clear()
                                    frameTableRows.clear()
                                }
                            }
                            "image" -> {
                                if (inFrame) {
                                    val href = findAttrBySuffix(parser, "href")?.replace('\\', '/')?.trimStart('/')
                                    if (!href.isNullOrBlank() && mediaMap.containsKey(href)) {
                                        frameImage = href
                                    }
                                }
                            }
                            "table-row" -> {
                                if (inFrame && frameTableRows.size < 512) currentTableRow = mutableListOf()
                            }
                            "table-cell" -> {
                                if (inFrame && currentTableRow != null && (currentTableRow?.size ?: 0) < 128) {
                                    currentTableCellSb = StringBuilder()
                                }
                            }
                            "p" -> {
                                if (inPage && currentTableCellSb == null) {
                                    currentPara = StringBuilder()
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text ?: ""
                        when {
                            currentTableCellSb != null -> if (currentTableCellSb.length < 16384) currentTableCellSb.append(txt)
                            currentPara != null -> if (currentPara.length < 32768) currentPara.append(txt)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "p" -> {
                                if (currentTableCellSb != null) {
                                    if (currentTableCellSb.isNotEmpty() && !currentTableCellSb.endsWith(" ")) {
                                        currentTableCellSb.append(' ')
                                    }
                                } else {
                                    val line = currentPara?.toString()?.trim() ?: ""
                                    if (line.isNotEmpty() && frameParas.size < 1024) {
                                        frameParas.add(line)
                                        if (currentPageSummary.length < 65536) {
                                            currentPageSummary.append(line).append('\n')
                                        }
                                    }
                                    currentPara = null
                                }
                            }
                            "table-cell" -> {
                                val cellTxt = currentTableCellSb?.toString()?.trim() ?: ""
                                currentTableRow?.add(cellTxt)
                                currentTableCellSb = null
                            }
                            "table-row" -> {
                                val r = currentTableRow
                                if (!r.isNullOrEmpty()) {
                                    frameTableRows.add(r.toList())
                                    if (currentPageSummary.length < 65536) {
                                        currentPageSummary.append(r.joinToString(" | ")).append('\n')
                                    }
                                }
                                currentTableRow = null
                            }
                            "frame", "custom-shape", "rect", "ellipse", "line", "connector" -> {
                                if (inFrame) {
                                    if (frameParas.isNotEmpty() || frameTableRows.isNotEmpty() || frameImage != null ||
                                        frameStyle?.fillColor != null || frameStyle?.strokeColor != null || isLineShape
                                    ) {
                                        currentFrames.add(
                                            OdpFrameElement(
                                                rectCm = frameRect,
                                                paragraphs = frameParas.toList(),
                                                tableRows = frameTableRows.toList(),
                                                imagePath = frameImage,
                                                fillColor = frameStyle?.fillColor,
                                                strokeColor = frameStyle?.strokeColor ?: if (isLineShape) Color.DKGRAY else null,
                                                textColor = frameStyle?.textColor,
                                                fontSizePt = frameStyle?.fontSizePt,
                                                isLineConnector = isLineShape
                                            )
                                        )
                                    }
                                    inFrame = false
                                    isLineShape = false
                                    frameRect = null
                                    frameStyle = null
                                    frameImage = null
                                    frameParas.clear()
                                    frameTableRows.clear()
                                }
                            }
                            "page" -> {
                                if (inPage) {
                                    if (currentFrames.isEmpty() && currentPageSummary.isNotBlank()) {
                                        currentFrames.add(
                                            OdpFrameElement(
                                                rectCm = null,
                                                paragraphs = currentPageSummary.toString().lines().filter { it.isNotBlank() },
                                                imagePath = null,
                                                fillColor = null
                                            )
                                        )
                                    }
                                    slides.add(
                                        OdpSlide(
                                            backgroundColor = currentPageBgColor,
                                            frames = currentFrames.toList(),
                                            plainText = currentPageSummary.toString().trim()
                                        )
                                    )
                                    inPage = false
                                }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            if (slides.isEmpty()) {
                lastErrorMsg = "В презентации ODP не найдено слайдов."
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
        val aspect = (pageWidthCm / pageHeightCm.coerceAtLeast(1f)).coerceIn(1.2f, 2.4f)
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

    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return slides.joinToString("\n\n") { it.plainText }
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        return slides.getOrNull(cleanIdx)?.plainText ?: ""
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        lastCanvasWidth = bitmap.width.toFloat()
        lastCanvasHeight = bitmap.height.toFloat()

        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_PRESENTATION_BG_COLOR else DARK_PRESENTATION_BG_COLOR)
        val slide = slides.getOrNull(cleanPageIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))) ?: return
        val zoom = (zoomPermille / 1000f).coerceIn(0.2f, 10.0f)

        val (cardW, cardH) = computeSlideCardSize(bitmap.width.toFloat(), bitmap.height.toFloat())
        val left = (bitmap.width - cardW) / 2f
        val top = (bitmap.height - cardH) / 2f

        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        canvas.scale(zoom, zoom, bitmap.width / 2f, bitmap.height / 2f)

        val slideRect = RectF(left, top, left + cardW, top + cardH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slide.backgroundColor }
        canvas.drawRoundRect(slideRect, 8f, 8f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(200, 206, 220) else Color.rgb(70, 75, 95)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(slideRect, 8f, 8f, borderPaint)

        val scaleFactor = (cardH / 680f).coerceIn(0.75f, 2.0f)
        val sx = cardW / pageWidthCm.coerceAtLeast(1f)
        val sy = cardH / pageHeightCm.coerceAtLeast(1f)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 28, 48)
            textSize = 36f * scaleFactor
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 38, 46)
            textSize = 26f * scaleFactor
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * scaleFactor
        }

        canvas.save()
        canvas.clipRect(left + 8f, top + 8f, left + cardW - 8f, top + cardH - 8f)
        var fallbackY = top + 36f

        slide.frames.forEachIndexed { frameIdx, frame ->
            val dstRect = if (frame.rectCm != null) {
                RectF(
                    left + frame.rectCm.left * sx,
                    top + frame.rectCm.top * sy,
                    left + frame.rectCm.right * sx,
                    top + frame.rectCm.bottom * sy
                )
            } else {
                RectF(left + 40f, fallbackY, left + cardW - 40f, left + cardH - 36f)
            }

            if (frame.isLineConnector) {
                strokePaint.color = frame.strokeColor ?: Color.DKGRAY
                canvas.drawLine(dstRect.left, dstRect.top, dstRect.right, dstRect.bottom, strokePaint)
                return@forEachIndexed
            }

            if (frame.fillColor != null) {
                fillPaint.color = frame.fillColor
                canvas.drawRoundRect(dstRect, 6f, 6f, fillPaint)
            }
            if (frame.strokeColor != null) {
                strokePaint.color = frame.strokeColor
                canvas.drawRoundRect(dstRect, 6f, 6f, strokePaint)
            }

            if (frame.imagePath != null) {
                val imgBytes = mediaMap[frame.imagePath]
                if (imgBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                        ?: EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                    }
                }
            }

            if (frame.tableRows.isNotEmpty()) {
                val rCount = frame.tableRows.size
                val cCount = frame.tableRows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                val cw = dstRect.width() / cCount
                val ch = (dstRect.height() / rCount).coerceAtLeast(20f)
                bodyPaint.textSize = 20f * scaleFactor
                for (r in 0 until rCount) {
                    for (c in 0 until cCount) {
                        val cr = RectF(dstRect.left + c * cw, dstRect.top + r * ch, dstRect.left + (c + 1) * cw, dstRect.top + (r + 1) * ch)
                        if (r == 0) {
                            fillPaint.color = Color.rgb(232, 236, 245)
                            canvas.drawRect(cr, fillPaint)
                        }
                        strokePaint.color = Color.rgb(160, 168, 184)
                        canvas.drawRect(cr, strokePaint)
                        val cellTxt = frame.tableRows[r].getOrNull(c) ?: ""
                        if (cellTxt.isNotEmpty()) {
                            val clipped = TextUtils.ellipsize(cellTxt, bodyPaint, (cw - 8f).coerceAtLeast(10f), TextUtils.TruncateAt.END).toString()
                            canvas.drawText(clipped, cr.left + 4f, cr.top + ch * 0.65f, bodyPaint)
                        }
                    }
                }
            }

            if (frame.paragraphs.isNotEmpty()) {
                val p = TextPaint(if (frameIdx == 0) titlePaint else bodyPaint)
                frame.textColor?.let { p.color = it }
                frame.fontSizePt?.let { p.textSize = (it * 1.35f * scaleFactor).coerceIn(14f, 64f) }
                val combined = frame.paragraphs.joinToString("\n")
                val innerW = (dstRect.width() - 12f).toInt().coerceAtLeast(80)
                val layout = StaticLayout.Builder
                    .obtain(combined, 0, combined.length, p, innerW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
                canvas.save()
                canvas.clipRect(dstRect)
                canvas.translate(dstRect.left + 6f, dstRect.top + 4f)
                layout.draw(canvas)
                canvas.restore()
                if (frame.rectCm == null) {
                    fallbackY += layout.height + 24f
                }
            }
        }

        canvas.restore()
        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        for (i in startPage.coerceAtLeast(0) until slides.size) {
            if (slides[i].plainText.contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        mediaMap.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}

abstract class BaseGridSpreadsheetSession : DocumentSession {
    @Volatile protected var isClosed = false
    @Volatile protected var lastErrorMsg: String? = null
    @Volatile protected var activeSheetIdx = 0

    data class CellVisualStyle(
        val fillColor: Int? = null,
        val textColor: Int? = null,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val horizontalAlign: Paint.Align = Paint.Align.LEFT
    )

    data class MergedCellRegion(
        val firstRow: Int,
        val lastRow: Int,
        val firstCol: Int,
        val lastCol: Int
    )

    data class SheetModel(
        val name: String,
        val rows: List<List<String>>,
        val styles: Map<Long, CellVisualStyle> = emptyMap(),
        val colWidthsPx: FloatArray? = null,
        val mergedRegions: List<MergedCellRegion> = emptyList()
    )

    protected val sheets = mutableListOf<SheetModel>()

    companion object {
        fun packCoord(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)
    }

    override val pageCount: Int get() = 1
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
        val dataColCount = (sheet.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(widths.size)
        if (dataColCount <= 0) return FloatArray(0)
        return if (widths.size == dataColCount) {
            widths.clone()
        } else {
            FloatArray(dataColCount) { c ->
                if (c < widths.size && widths[c] > 0f) widths[c] else SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
            }
        }
    }

    @Synchronized
    override fun getCellText(row: Int, col: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        return sheet.rows.getOrNull(row)?.getOrNull(col) ?: ""
    }

    @Synchronized
    override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        if (sheet.rows.isEmpty()) return ""
        val rMin = minOf(startRow, endRow).coerceAtLeast(0)
        val rMax = minOf(maxOf(startRow, endRow), sheet.rows.size - 1)
        if (rMin > rMax) return ""

        val maxCols = sheet.rows.maxOfOrNull { it.size } ?: 0
        if (maxCols == 0) return ""
        val cMin = minOf(startCol, endCol).coerceAtLeast(0)
        val cMax = minOf(maxOf(startCol, endCol), maxCols - 1)
        if (cMin > cMax) return ""

        val sb = StringBuilder()
        for (r in rMin..rMax) {
            val rowList = sheet.rows[r]
            for (c in cMin..cMax) {
                sb.append(rowList.getOrNull(c) ?: "")
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
        return sheet.rows.joinToString("\n") { it.joinToString("\t") }
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        for (sIdx in startPage.coerceAtLeast(0) until sheets.size) {
            val found = sheets[sIdx].rows.any { row -> row.any { it.contains(query, ignoreCase = true) } }
            if (found) return sIdx.toLong()
        }
        return -1L
    }

    @Synchronized
    override fun getDocumentWidth(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.rows.isEmpty()) return 0f
        val dataColCount = sheet.rows.maxOfOrNull { it.size } ?: 0
        if (dataColCount == 0) return 0f
        val widths = sheet.colWidthsPx
        val baseWidth = if (widths != null && widths.isNotEmpty()) {
            var sum = 0f
            for (c in 0 until dataColCount) {
                val w = if (c < widths.size && widths[c] > 0f) widths[c] else SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
                sum += w.coerceIn(60f, 680f)
            }
            sum
        } else {
            dataColCount * SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
        }
        return SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH + baseWidth
    }

    @Synchronized
    override fun getDocumentHeight(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.rows.isEmpty()) return 0f
        return SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT + sheet.rows.size * SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)

        val sheet = sheets.getOrNull(activeSheetIdx) ?: return
        val grid = sheet.rows
        if (grid.isEmpty()) return

        val dataRowCount = grid.size
        val dataColCount = grid.maxOfOrNull { it.size } ?: 0
        if (dataColCount <= 0) return

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val headerHeight = SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
        val headerWidth = SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom
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

        val defaultCellBg = if (isLightTheme) LIGHT_SHEET_CELL_BG_COLOR else DARK_SHEET_CELL_BG_COLOR
        val defaultTextColor = if (isLightTheme) LIGHT_SHEET_TEXT_COLOR else DARK_SHEET_TEXT_COLOR

        val paintCellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = defaultCellBg
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

        val maxColIdx = dataColCount - 1
        val maxRowIdx = dataRowCount - 1
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
                val rowData = grid.getOrNull(r)
                for (c in startCol..endCol) {
                    val colX = headerWidth + colOffsets[c] - effX
                    val colW = colOffsets[c + 1] - colOffsets[c]
                    if (colX + colW >= headerWidth && colX <= bitmap.width) {
                        val activeMerge = findMergeForCell(r, c)
                        if (activeMerge == null) {
                            val cellStyle = sheet.styles[packCoord(r, c)]
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

                            val txt = rowData?.getOrNull(c) ?: ""
                            if (txt.isNotEmpty()) {
                                paintText.color = cellStyle?.textColor ?: defaultTextColor
                                paintText.isFakeBoldText = cellStyle?.isBold == true
                                paintText.textSkewX = if (cellStyle?.isItalic == true) -0.2f else 0f
                                paintText.isUnderlineText = cellStyle?.isUnderline == true
                                paintText.isStrikeThruText = cellStyle?.isStrike == true
                                val clipped = TextUtils.ellipsize(
                                    txt,
                                    paintText,
                                    (colW - 16f * zoom).coerceAtLeast(10f),
                                    TextUtils.TruncateAt.END
                                ).toString()
                                val textWidth = paintText.measureText(clipped)
                                val drawX = when (cellStyle?.horizontalAlign) {
                                    Paint.Align.CENTER -> colX + ((colW - textWidth) / 2f).coerceAtLeast(6f * zoom)
                                    Paint.Align.RIGHT -> (colX + colW - textWidth - 10f * zoom).coerceAtLeast(colX + 6f * zoom)
                                    else -> colX + 10f * zoom
                                }
                                canvas.save()
                                canvas.clipRect(
                                    maxOf(headerWidth, colX + 2f),
                                    maxOf(headerHeight, rowY + 2f),
                                    colX + colW - 2f,
                                    rowY + cellHeight - 2f
                                )
                                canvas.drawText(clipped, drawX, rowY + 33f * zoom, paintText)
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

            val style = sheet.styles[packCoord(merge.firstRow, merge.firstCol)]
            paintCustomFill.color = style?.fillColor ?: defaultCellBg
            canvas.save()
            canvas.clipRect(headerWidth, headerHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRect(mLeft + 1f, mTop + 1f, mRight - 1f, mBot - 1f, paintCustomFill)
            canvas.drawRect(mLeft, mTop, mRight, mBot, paintGrid)

            val txt = grid.getOrNull(merge.firstRow)?.getOrNull(merge.firstCol) ?: ""
            if (txt.isNotEmpty()) {
                paintText.color = style?.textColor ?: defaultTextColor
                paintText.isFakeBoldText = style?.isBold == true
                paintText.textSkewX = if (style?.isItalic == true) -0.2f else 0f
                paintText.isUnderlineText = style?.isUnderline == true
                paintText.isStrikeThruText = style?.isStrike == true
                val availW = (mRight - mLeft - 16f * zoom).toInt().coerceAtLeast(20)
                val align = when (style?.horizontalAlign) {
                    Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                val layout = StaticLayout.Builder.obtain(txt, 0, txt.length, paintText, availW)
                    .setAlignment(align)
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
        paintText.isUnderlineText = false
        paintText.isStrikeThruText = false

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

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
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
}

/**
 * Clean-room Spreadsheet Formula Evaluator for XLSX / XLSB / ODS cells lacking cached `<v>` values.
 * Supports arithmetic (`+`, `-`, `*`, `/`, `^`), comparisons (`=`, `<>`, `<`, `<=`, `>`, `>=`),
 * string concatenation (`&`), cross-sheet cell & range references (`Sheet1!A1:B10`), and Excel functions:
 * `SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`, `COUNTA`, `IF`, `IFERROR`, `IFNA`, `AND`, `OR`, `NOT`,
 * `ABS`, `ROUND`, `ROUNDUP`, `ROUNDDOWN`, `INT`, `FLOOR`, `CEILING`, `SQRT`, `POW`, `MOD`,
 * `LEN`, `LOWER`, `UPPER`, `TRIM`, `LEFT`, `RIGHT`, `MID`, `CONCAT`, `CONCATENATE`,
 * `VLOOKUP`, `HLOOKUP`, `INDEX`, `MATCH`, `SUMIF`, `COUNTIF`.
 */
internal object CleanRoomFormulaEvaluator {

    private val CELL_REF_REGEX = Regex("""^(?:(?:'([^']+)'|([A-Za-z0-9_.\u0400-\u04FF]+))!)?\$?([A-Za-z]{1,3})\$?(\d+)$""")

    fun evaluateSheetFormulas(
        grid: ArrayList<ArrayList<String>>,
        formulas: Map<Long, String>,
        crossSheetResolver: ((sheetName: String, row: Int, col: Int) -> String?)? = null
    ) {
        if (formulas.isEmpty()) return
        val evaluated = HashSet<Long>()
        val visiting = HashSet<Long>()

        fun getCellValue(r: Int, c: Int, sheetName: String? = null): String {
            if (!sheetName.isNullOrBlank() && crossSheetResolver != null) {
                return crossSheetResolver(sheetName, r, c) ?: ""
            }
            if (r !in grid.indices) return ""
            val row = grid[r]
            if (c !in row.indices) return ""
            val key = BaseGridSpreadsheetSession.packCoord(r, c)
            val existing = row[c]
            if (existing.isNotEmpty() || key !in formulas || key in evaluated) {
                return existing
            }
            if (!visiting.add(key)) return ""
            val expr = formulas[key] ?: ""
            val computed = evaluateExpression(expr, ::getCellValue)
            visiting.remove(key)
            evaluated.add(key)
            if (computed.isNotEmpty()) {
                row[c] = computed
            }
            return row[c]
        }

        for ((packed, _) in formulas) {
            val r = (packed shr 32).toInt()
            val c = packed.toInt()
            if (r in grid.indices && c in grid[r].indices && grid[r][c].isEmpty()) {
                getCellValue(r, c, null)
            }
        }
    }

    private fun parseCellRef(ref: String): Triple<String?, Int, Int>? {
        val clean = ref.trim().removeSurrounding("[", "]").removePrefix(".")
        val m = CELL_REF_REGEX.matchEntire(clean) ?: return null
        val sheetName = m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotEmpty() }
        val col = SpreadsheetCoordinateUtils.columnIndexFromCellRef(m.groupValues[3])
        val row = (m.groupValues[4].toIntOrNull() ?: 0) - 1
        return if (col >= 0 && row >= 0) Triple(sheetName, row, col) else null
    }

    private fun expandRangeCells(
        rangeStr: String,
        cellLookup: (Int, Int, String?) -> String
    ): List<String> {
        val clean = rangeStr.trim().removeSurrounding("[", "]")
        val sheetPrefix = if (clean.contains('!')) clean.substringBeforeLast('!') + "!" else ""
        val rangePart = if (clean.contains('!')) clean.substringAfterLast('!') else clean
        val parts = rangePart.split(':')
        if (parts.size != 2) return emptyList()

        val start = parseCellRef(sheetPrefix + parts[0]) ?: return emptyList()
        val end = parseCellRef(sheetPrefix + parts[1]) ?: return emptyList()
        val sheet = start.first ?: end.first
        val rMin = minOf(start.second, end.second)
        val rMax = minOf(maxOf(start.second, end.second), rMin + 2000)
        val cMin = minOf(start.third, end.third)
        val cMax = minOf(maxOf(start.third, end.third), cMin + 128)

        val out = ArrayList<String>((rMax - rMin + 1) * (cMax - cMin + 1))
        for (r in rMin..rMax) {
            for (c in cMin..cMax) {
                out.add(cellLookup(r, c, sheet))
            }
        }
        return out
    }

    private fun expandRange2D(
        rangeStr: String,
        cellLookup: (Int, Int, String?) -> String
    ): List<List<String>> {
        val clean = rangeStr.trim().removeSurrounding("[", "]")
        val sheetPrefix = if (clean.contains('!')) clean.substringBeforeLast('!') + "!" else ""
        val rangePart = if (clean.contains('!')) clean.substringAfterLast('!') else clean
        val parts = rangePart.split(':')
        if (parts.size != 2) return emptyList()

        val start = parseCellRef(sheetPrefix + parts[0]) ?: return emptyList()
        val end = parseCellRef(sheetPrefix + parts[1]) ?: return emptyList()
        val sheet = start.first ?: end.first
        val rMin = minOf(start.second, end.second)
        val rMax = minOf(maxOf(start.second, end.second), rMin + 2000)
        val cMin = minOf(start.third, end.third)
        val cMax = minOf(maxOf(start.third, end.third), cMin + 128)

        val rowsOut = ArrayList<List<String>>(rMax - rMin + 1)
        for (r in rMin..rMax) {
            val rowList = ArrayList<String>(cMax - cMin + 1)
            for (c in cMin..cMax) {
                rowList.add(cellLookup(r, c, sheet))
            }
            rowsOut.add(rowList)
        }
        return rowsOut
    }

    private fun splitTopLevelArgs(argsStr: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var inQuotes = false
        val sb = StringBuilder()
        for (ch in argsStr) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                !inQuotes && ch == '(' -> {
                    depth++
                    sb.append(ch)
                }
                !inQuotes && ch == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append(ch)
                }
                !inQuotes && depth == 0 && (ch == ',' || ch == ';') -> {
                    result.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString().trim())
        return result
    }

    private fun evaluateCondition(condStr: String, cellLookup: (Int, Int, String?) -> String): Boolean {
        val ops = listOf("<>", "<=", ">=", "=", "<", ">")
        for (op in ops) {
            val idx = findTopLevelOperator(condStr, op)
            if (idx > 0) {
                val leftStr = evaluateExpression(condStr.substring(0, idx), cellLookup)
                val rightStr = evaluateExpression(condStr.substring(idx + op.length), cellLookup)
                val leftNum = leftStr.toDoubleOrNull()
                val rightNum = rightStr.toDoubleOrNull()
                return if (leftNum != null && rightNum != null) {
                    when (op) {
                        "=" -> abs(leftNum - rightNum) < 1e-9
                        "<>" -> abs(leftNum - rightNum) >= 1e-9
                        "<" -> leftNum < rightNum
                        "<=" -> leftNum <= rightNum
                        ">" -> leftNum > rightNum
                        ">=" -> leftNum >= rightNum
                        else -> false
                    }
                } else {
                    val cmp = leftStr.compareTo(rightStr, ignoreCase = true)
                    when (op) {
                        "=" -> cmp == 0
                        "<>" -> cmp != 0
                        "<" -> cmp < 0
                        "<=" -> cmp <= 0
                        ">" -> cmp > 0
                        ">=" -> cmp >= 0
                        else -> false
                    }
                }
            }
        }
        val singleVal = evaluateExpression(condStr, cellLookup)
        if (singleVal.equals("TRUE", ignoreCase = true)) return true
        if (singleVal.equals("FALSE", ignoreCase = true)) return false
        return (singleVal.toDoubleOrNull() ?: 0.0) != 0.0
    }

    private fun matchesCriteria(cellVal: String, rawCriteria: String): Boolean {
        val crit = rawCriteria.trim().removeSurrounding("\"")
        for (op in listOf("<>", "<=", ">=", "=", "<", ">")) {
            if (crit.startsWith(op)) {
                val targetStr = crit.substring(op.length).trim()
                val cNum = cellVal.toDoubleOrNull()
                val tNum = targetStr.toDoubleOrNull()
                return if (cNum != null && tNum != null) {
                    when (op) {
                        "=" -> abs(cNum - tNum) < 1e-9
                        "<>" -> abs(cNum - tNum) >= 1e-9
                        "<" -> cNum < tNum
                        "<=" -> cNum <= tNum
                        ">" -> cNum > tNum
                        ">=" -> cNum >= tNum
                        else -> false
                    }
                } else {
                    val cmp = cellVal.compareTo(targetStr, ignoreCase = true)
                    when (op) {
                        "=" -> cmp == 0
                        "<>" -> cmp != 0
                        "<" -> cmp < 0
                        "<=" -> cmp <= 0
                        ">" -> cmp > 0
                        ">=" -> cmp >= 0
                        else -> false
                    }
                }
            }
        }
        val cNum = cellVal.toDoubleOrNull()
        val tNum = crit.toDoubleOrNull()
        if (cNum != null && tNum != null) return abs(cNum - tNum) < 1e-9
        return cellVal.equals(crit, ignoreCase = true)
    }

    private fun findTopLevelOperator(s: String, op: String): Int {
        var depth = 0
        var inQuotes = false
        var i = 0
        while (i <= s.length - op.length) {
            val ch = s[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (!inQuotes && ch == '(') depth++
            else if (!inQuotes && ch == ')') depth = (depth - 1).coerceAtLeast(0)
            else if (!inQuotes && depth == 0 && s.startsWith(op, i)) {
                if (op == "=" && i > 0 && (s[i - 1] == '<' || s[i - 1] == '>')) {
                    i++
                    continue
                }
                if ((op == "<" || op == ">") && i + 1 < s.length && (s[i + 1] == '=' || s[i + 1] == '>')) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    fun evaluateExpression(
        rawFormula: String,
        cellLookup: (Int, Int, String?) -> String
    ): String {
        val expr = rawFormula.trim().removePrefix("of:").removePrefix("=").trim()
        if (expr.isEmpty()) return ""
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length >= 2 && expr.count { it == '"' } == 2) {
            return expr.substring(1, expr.length - 1)
        }

        // Top-level string concatenation operator '&'
        val ampIdx = findTopLevelOperator(expr, "&")
        if (ampIdx > 0) {
            val leftPart = evaluateExpression(expr.substring(0, ampIdx), cellLookup)
            val rightPart = evaluateExpression(expr.substring(ampIdx + 1), cellLookup)
            return leftPart + rightPart
        }

        return try {
            val parser = RecursiveFormulaParser(expr, cellLookup)
            val value = parser.parseExpression()
            value
        } catch (_: Throwable) {
            ""
        }
    }

    private class RecursiveFormulaParser(
        private val input: String,
        private val cellLookup: (Int, Int, String?) -> String
    ) {
        private var pos = 0

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun parseExpression(): String {
            val num = parseAdditive()
            return if (num.isNaN()) "" else OoxmlDateStyleHelper.formatCleanNumber(num)
        }

        private fun parseAdditive(): Double {
            var left = parseMultiplicative()
            while (true) {
                skipWhitespace()
                if (pos >= input.length) break
                val op = input[pos]
                if (op == '+' || op == '-') {
                    pos++
                    val right = parseMultiplicative()
                    left = if (op == '+') left + right else left - right
                } else {
                    break
                }
            }
            return left
        }

        private fun parseMultiplicative(): Double {
            var left = parsePower()
            while (true) {
                skipWhitespace()
                if (pos >= input.length) break
                val op = input[pos]
                if (op == '*' || op == '/') {
                    pos++
                    val right = parsePower()
                    left = if (op == '*') {
                        left * right
                    } else {
                        if (abs(right) < 1e-12) 0.0 else left / right
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parsePower(): Double {
            var base = parseUnary()
            skipWhitespace()
            if (pos < input.length && input[pos] == '^') {
                pos++
                val exp = parseUnary()
                base = base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            if (pos < input.length) {
                if (input[pos] == '+') {
                    pos++
                    return parseUnary()
                }
                if (input[pos] == '-') {
                    pos++
                    return -parseUnary()
                }
            }
            val atom = parsePrimary()
            skipWhitespace()
            if (pos < input.length && input[pos] == '%') {
                pos++
                return atom / 100.0
            }
            return atom
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (pos >= input.length) return 0.0

            if (input[pos] == '(') {
                pos++
                val inner = parseAdditive()
                skipWhitespace()
                if (pos < input.length && input[pos] == ')') pos++
                return inner
            }

            if (input[pos] == '"') {
                val start = ++pos
                while (pos < input.length && input[pos] != '"') pos++
                val str = input.substring(start, pos)
                if (pos < input.length && input[pos] == '"') pos++
                return str.toDoubleOrNull() ?: 0.0
            }

            val tokenStart = pos
            if (input[pos] == '\'') {
                pos++
                while (pos < input.length && input[pos] != '\'') pos++
                if (pos < input.length) pos++
            }
            while (pos < input.length && (
                        input[pos].isLetterOrDigit() ||
                                input[pos] in setOf('$', '.', '_', ':', '[', ']', '!')
                        )
            ) {
                pos++
            }
            val token = input.substring(tokenStart, pos).trim()
            if (token.isEmpty()) return 0.0

            skipWhitespace()
            if (pos < input.length && input[pos] == '(') {
                pos++
                val argsStart = pos
                var depth = 1
                var inQuotes = false
                while (pos < input.length && depth > 0) {
                    val c = input[pos]
                    if (c == '"') inQuotes = !inQuotes
                    else if (!inQuotes && c == '(') depth++
                    else if (!inQuotes && c == ')') depth--
                    pos++
                }
                val argsContent = input.substring(argsStart, (pos - 1).coerceAtLeast(argsStart))
                val fnUpper = token.uppercase(Locale.ROOT).substringAfterLast('.')
                val strResult = evaluateFunctionToString(fnUpper, argsContent)
                if (strResult != null) {
                    if (pos >= input.length) {
                        throw ShortCircuitStringResult(strResult)
                    }
                    return strResult.toDoubleOrNull() ?: 0.0
                }
                return evaluateNumericFunction(fnUpper, argsContent)
            }

            token.toDoubleOrNull()?.let { return it }

            val cellCoord = parseCellRef(token)
            if (cellCoord != null) {
                val cellVal = cellLookup(cellCoord.second, cellCoord.third, cellCoord.first)
                if (pos >= input.length && cellVal.toDoubleOrNull() == null && tokenStart == 0) {
                    throw ShortCircuitStringResult(cellVal)
                }
                return cellVal.replace(',', '.').toDoubleOrNull() ?: 0.0
            }

            return 0.0
        }

        private fun collectValuesFromArgs(argsContent: String): List<String> {
            val rawArgs = splitTopLevelArgs(argsContent)
            val values = ArrayList<String>()
            for (arg in rawArgs) {
                if (arg.contains(':')) {
                    values.addAll(expandRangeCells(arg, cellLookup))
                } else {
                    val coord = parseCellRef(arg)
                    if (coord != null) {
                        values.add(cellLookup(coord.second, coord.third, coord.first))
                    } else {
                        values.add(evaluateExpression(arg, cellLookup))
                    }
                }
            }
            return values
        }

        private fun evaluateFunctionToString(fnName: String, argsContent: String): String? {
            val rawArgs = splitTopLevelArgs(argsContent)
            return when (fnName) {
                "IF" -> {
                    if (rawArgs.isEmpty()) return ""
                    val cond = evaluateCondition(rawArgs[0], cellLookup)
                    val branch = if (cond) rawArgs.getOrNull(1) ?: "" else rawArgs.getOrNull(2) ?: ""
                    evaluateExpression(branch, cellLookup)
                }
                "IFERROR", "IFNA" -> {
                    if (rawArgs.isEmpty()) return ""
                    val primary = evaluateExpression(rawArgs[0], cellLookup)
                    if (primary.isEmpty() || primary.startsWith("#")) {
                        evaluateExpression(rawArgs.getOrNull(1) ?: "", cellLookup)
                    } else {
                        primary
                    }
                }
                "AND" -> {
                    val res = rawArgs.isNotEmpty() && rawArgs.all { evaluateCondition(it, cellLookup) }
                    if (res) "TRUE" else "FALSE"
                }
                "OR" -> {
                    val res = rawArgs.any { evaluateCondition(it, cellLookup) }
                    if (res) "TRUE" else "FALSE"
                }
                "NOT" -> {
                    val res = if (rawArgs.isNotEmpty()) !evaluateCondition(rawArgs[0], cellLookup) else true
                    if (res) "TRUE" else "FALSE"
                }
                "CONCAT", "CONCATENATE" -> {
                    collectValuesFromArgs(argsContent).joinToString("")
                }
                "LOWER" -> {
                    rawArgs.firstOrNull()?.let { evaluateExpression(it, cellLookup).lowercase(Locale.ROOT) } ?: ""
                }
                "UPPER" -> {
                    rawArgs.firstOrNull()?.let { evaluateExpression(it, cellLookup).uppercase(Locale.ROOT) } ?: ""
                }
                "TRIM" -> {
                    rawArgs.firstOrNull()?.let { evaluateExpression(it, cellLookup).trim() } ?: ""
                }
                "LEFT" -> {
                    val str = rawArgs.getOrNull(0)?.let { evaluateExpression(it, cellLookup) } ?: ""
                    val n = rawArgs.getOrNull(1)?.let { evaluateExpression(it, cellLookup).toDoubleOrNull()?.toInt() } ?: 1
                    str.take(n.coerceAtLeast(0))
                }
                "RIGHT" -> {
                    val str = rawArgs.getOrNull(0)?.let { evaluateExpression(it, cellLookup) } ?: ""
                    val n = rawArgs.getOrNull(1)?.let { evaluateExpression(it, cellLookup).toDoubleOrNull()?.toInt() } ?: 1
                    str.takeLast(n.coerceAtLeast(0))
                }
                "MID" -> {
                    val str = rawArgs.getOrNull(0)?.let { evaluateExpression(it, cellLookup) } ?: ""
                    val start1 = (rawArgs.getOrNull(1)?.let { evaluateExpression(it, cellLookup).toDoubleOrNull()?.toInt() } ?: 1) - 1
                    val len = rawArgs.getOrNull(2)?.let { evaluateExpression(it, cellLookup).toDoubleOrNull()?.toInt() } ?: 0
                    val s = start1.coerceIn(0, str.length)
                    val e = (s + len.coerceAtLeast(0)).coerceIn(s, str.length)
                    str.substring(s, e)
                }
                "VLOOKUP" -> {
                    if (rawArgs.size < 3) return ""
                    val needle = evaluateExpression(rawArgs[0], cellLookup)
                    val table2D = expandRange2D(rawArgs[1], cellLookup)
                    val colIdx = (evaluateExpression(rawArgs[2], cellLookup).toDoubleOrNull()?.toInt() ?: 1) - 1
                    if (colIdx < 0 || table2D.isEmpty()) return ""
                    val matchedRow = table2D.firstOrNull { row ->
                        val firstCell = row.firstOrNull() ?: ""
                        firstCell.equals(needle, ignoreCase = true) ||
                                (firstCell.toDoubleOrNull() != null && firstCell.toDoubleOrNull() == needle.toDoubleOrNull())
                    }
                    matchedRow?.getOrNull(colIdx) ?: ""
                }
                "HLOOKUP" -> {
                    if (rawArgs.size < 3) return ""
                    val needle = evaluateExpression(rawArgs[0], cellLookup)
                    val table2D = expandRange2D(rawArgs[1], cellLookup)
                    val rowIdx = (evaluateExpression(rawArgs[2], cellLookup).toDoubleOrNull()?.toInt() ?: 1) - 1
                    if (rowIdx !in table2D.indices || table2D.first().isEmpty()) return ""
                    val firstRow = table2D.first()
                    val colIdx = firstRow.indexOfFirst { cell ->
                        cell.equals(needle, ignoreCase = true) ||
                                (cell.toDoubleOrNull() != null && cell.toDoubleOrNull() == needle.toDoubleOrNull())
                    }
                    if (colIdx >= 0) table2D[rowIdx].getOrNull(colIdx) ?: "" else ""
                }
                "INDEX" -> {
                    if (rawArgs.size < 2) return ""
                    val table2D = expandRange2D(rawArgs[0], cellLookup)
                    if (table2D.isEmpty()) return ""
                    val rIdx = (evaluateExpression(rawArgs[1], cellLookup).toDoubleOrNull()?.toInt() ?: 1) - 1
                    val cIdx = (rawArgs.getOrNull(2)?.let { evaluateExpression(it, cellLookup).toDoubleOrNull()?.toInt() } ?: 1) - 1
                    if (table2D.size == 1 && rIdx >= 0 && rawArgs.size == 2) {
                        table2D[0].getOrNull(rIdx) ?: ""
                    } else {
                        table2D.getOrNull(rIdx)?.getOrNull(cIdx.coerceAtLeast(0)) ?: ""
                    }
                }
                else -> null
            }
        }

        private fun evaluateNumericFunction(fnName: String, argsContent: String): Double {
            val rawArgs = splitTopLevelArgs(argsContent)
            when (fnName) {
                "MATCH" -> {
                    if (rawArgs.size < 2) return 0.0
                    val needle = evaluateExpression(rawArgs[0], cellLookup)
                    val list = if (rawArgs[1].contains(':')) expandRangeCells(rawArgs[1], cellLookup) else collectValuesFromArgs(rawArgs[1])
                    val idx = list.indexOfFirst { cell ->
                        cell.equals(needle, ignoreCase = true) ||
                                (cell.toDoubleOrNull() != null && cell.toDoubleOrNull() == needle.toDoubleOrNull())
                    }
                    return if (idx >= 0) (idx + 1).toDouble() else 0.0
                }
                "COUNTIF" -> {
                    if (rawArgs.size < 2) return 0.0
                    val rangeVals = if (rawArgs[0].contains(':')) expandRangeCells(rawArgs[0], cellLookup) else collectValuesFromArgs(rawArgs[0])
                    val criteria = evaluateExpression(rawArgs[1], cellLookup).ifEmpty { rawArgs[1] }
                    return rangeVals.count { matchesCriteria(it, criteria) }.toDouble()
                }
                "SUMIF" -> {
                    if (rawArgs.size < 2) return 0.0
                    val rangeVals = if (rawArgs[0].contains(':')) expandRangeCells(rawArgs[0], cellLookup) else collectValuesFromArgs(rawArgs[0])
                    val criteria = evaluateExpression(rawArgs[1], cellLookup).ifEmpty { rawArgs[1] }
                    val sumVals = if (rawArgs.size >= 3 && rawArgs[2].contains(':')) {
                        expandRangeCells(rawArgs[2], cellLookup)
                    } else {
                        rangeVals
                    }
                    var sum = 0.0
                    for (i in rangeVals.indices) {
                        if (matchesCriteria(rangeVals[i], criteria)) {
                            sum += sumVals.getOrNull(i)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                        }
                    }
                    return sum
                }
            }

            val allVals = collectValuesFromArgs(argsContent)
            val nums = allVals.mapNotNull { it.replace(',', '.').toDoubleOrNull() }
            return when (fnName) {
                "SUM" -> nums.sum()
                "AVERAGE", "AVG" -> if (nums.isNotEmpty()) nums.average() else 0.0
                "MIN" -> nums.minOrNull() ?: 0.0
                "MAX" -> nums.maxOrNull() ?: 0.0
                "COUNT" -> nums.size.toDouble()
                "COUNTA" -> allVals.count { it.isNotBlank() }.toDouble()
                "ABS" -> abs(nums.firstOrNull() ?: 0.0)
                "SQRT" -> sqrt((nums.firstOrNull() ?: 0.0).coerceAtLeast(0.0))
                "INT", "FLOOR" -> floor(nums.firstOrNull() ?: 0.0)
                "CEILING" -> ceil(nums.firstOrNull() ?: 0.0)
                "ROUND" -> {
                    val v = nums.getOrNull(0) ?: 0.0
                    val digits = (nums.getOrNull(1) ?: 0.0).toInt().coerceIn(-6, 10)
                    val factor = 10.0.pow(digits)
                    (v * factor).roundToLong() / factor
                }
                "ROUNDUP" -> {
                    val v = nums.getOrNull(0) ?: 0.0
                    val digits = (nums.getOrNull(1) ?: 0.0).toInt().coerceIn(-6, 10)
                    val factor = 10.0.pow(digits)
                    (if (v >= 0.0) ceil(v * factor) else floor(v * factor)) / factor
                }
                "ROUNDDOWN" -> {
                    val v = nums.getOrNull(0) ?: 0.0
                    val digits = (nums.getOrNull(1) ?: 0.0).toInt().coerceIn(-6, 10)
                    val factor = 10.0.pow(digits)
                    (if (v >= 0.0) floor(v * factor) else ceil(v * factor)) / factor
                }
                "POW", "POWER" -> {
                    val b = nums.getOrNull(0) ?: 0.0
                    val e = nums.getOrNull(1) ?: 1.0
                    b.pow(e)
                }
                "MOD" -> {
                    val a = nums.getOrNull(0) ?: 0.0
                    val b = nums.getOrNull(1) ?: 1.0
                    if (abs(b) < 1e-12) 0.0 else a % b
                }
                "LEN" -> (allVals.firstOrNull()?.length ?: 0).toDouble()
                else -> nums.firstOrNull() ?: 0.0
            }
        }
    }

    private class ShortCircuitStringResult(val text: String) : RuntimeException(null, null, false, false) {
        override fun fillInStackTrace(): Throwable = this
    }
}

class OoxmlSpreadsheetSession(private val pfd: ParcelFileDescriptor) : BaseGridSpreadsheetSession() {
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

    private data class FontSpec(
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrike: Boolean = false,
        val textColor: Int? = null
    )

    private data class XfStyleSpec(
        val numFmtId: Int,
        val formatCode: String?,
        val visualStyle: CellVisualStyle
    )

    private val xfStyles = ArrayList<XfStyleSpec>()
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

    private fun resolveOoxmlColorAttr(parser: XmlPullParser): Int? {
        val rgbAttr = parser.getAttributeValue(null, "rgb")?.trim()
        var baseColor: Int? = null
        if (!rgbAttr.isNullOrBlank() && (rgbAttr.length == 6 || rgbAttr.length == 8)) {
            val hex6 = if (rgbAttr.length == 8) rgbAttr.substring(2) else rgbAttr
            baseColor = try { Color.parseColor("#$hex6") } catch (_: Exception) { null }
        }
        if (baseColor == null) {
            val themeIdx = parser.getAttributeValue(null, "theme")?.toIntOrNull()
            if (themeIdx != null && themeIdx in themePalette.indices) {
                baseColor = themePalette[themeIdx]
            }
        }
        if (baseColor == null) {
            val indexed = parser.getAttributeValue(null, "indexed")?.toIntOrNull()
            if (indexed != null) {
                baseColor = INDEXED_EXCEL_COLORS[indexed]
            }
        }
        if (baseColor == null) return null

        val tintVal = parser.getAttributeValue(null, "tint")?.toDoubleOrNull() ?: 0.0
        return DrawingMlColorUtils.applyExcelTint(baseColor, tintVal)
    }

    private fun parseStylesXml(stylesBytes: ByteArray?) {
        if (stylesBytes == null) return
        val customFormats = HashMap<Int, String>()
        val fonts = ArrayList<FontSpec>()
        val fills = ArrayList<Int?>()

        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(stylesBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var inFonts = false
            var inFont = false
            var curBold = false
            var curItalic = false
            var curUnderline = false
            var curStrike = false
            var curFontColor: Int? = null

            var inFills = false
            var inPatternFill = false
            var curPatternType = ""
            var curFillColor: Int? = null

            var inCellXfs = false
            var inXf = false
            var curXfNumFmtId = 0
            var curXfFontId = 0
            var curXfFillId = 0
            var curXfAlign = Paint.Align.LEFT

            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "numfmt" -> {
                                val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                                val code = parser.getAttributeValue(null, "formatCode")
                                if (id != null && !code.isNullOrBlank()) {
                                    customFormats[id] = code
                                }
                            }
                            "fonts" -> inFonts = true
                            "font" -> if (inFonts) {
                                inFont = true
                                curBold = false
                                curItalic = false
                                curUnderline = false
                                curStrike = false
                                curFontColor = null
                            }
                            "b" -> if (inFont) {
                                val v = parser.getAttributeValue(null, "val")
                                curBold = (v == null || (v != "0" && !v.equals("false", true)))
                            }
                            "i" -> if (inFont) {
                                val v = parser.getAttributeValue(null, "val")
                                curItalic = (v == null || (v != "0" && !v.equals("false", true)))
                            }
                            "u" -> if (inFont) {
                                val v = parser.getAttributeValue(null, "val")
                                curUnderline = (v == null || (!v.equals("none", true) && v != "0"))
                            }
                            "strike" -> if (inFont) {
                                val v = parser.getAttributeValue(null, "val")
                                curStrike = (v == null || (v != "0" && !v.equals("false", true)))
                            }
                            "color" -> if (inFont && curFontColor == null) {
                                curFontColor = resolveOoxmlColorAttr(parser)
                            }
                            "fills" -> inFills = true
                            "patternfill" -> if (inFills) {
                                inPatternFill = true
                                curPatternType = parser.getAttributeValue(null, "patternType")?.lowercase(Locale.ROOT) ?: "none"
                                curFillColor = null
                            }
                            "fgcolor" -> if (inPatternFill && curPatternType != "none" && curPatternType != "gray125") {
                                curFillColor = resolveOoxmlColorAttr(parser)
                            }
                            "cellxfs" -> inCellXfs = true
                            "xf" -> if (inCellXfs) {
                                inXf = true
                                curXfNumFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                                curXfFontId = parser.getAttributeValue(null, "fontId")?.toIntOrNull() ?: 0
                                curXfFillId = parser.getAttributeValue(null, "fillId")?.toIntOrNull() ?: 0
                                curXfAlign = Paint.Align.LEFT
                            }
                            "alignment" -> if (inXf) {
                                val h = parser.getAttributeValue(null, "horizontal")?.lowercase(Locale.ROOT)
                                curXfAlign = when (h) {
                                    "center", "centercontinuous" -> Paint.Align.CENTER
                                    "right" -> Paint.Align.RIGHT
                                    else -> Paint.Align.LEFT
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "font" -> if (inFont) {
                                fonts.add(FontSpec(curBold, curItalic, curUnderline, curStrike, curFontColor))
                                inFont = false
                            }
                            "fonts" -> inFonts = false
                            "patternfill" -> if (inPatternFill) {
                                fills.add(curFillColor)
                                inPatternFill = false
                            }
                            "fills" -> inFills = false
                            "xf" -> if (inXf) {
                                val fSpec = fonts.getOrNull(curXfFontId) ?: FontSpec()
                                val fillCol = fills.getOrNull(curXfFillId)
                                val vStyle = CellVisualStyle(
                                    fillColor = fillCol,
                                    textColor = fSpec.textColor,
                                    isBold = fSpec.isBold,
                                    isItalic = fSpec.isItalic,
                                    isUnderline = fSpec.isUnderline,
                                    isStrike = fSpec.isStrike,
                                    horizontalAlign = curXfAlign
                                )
                                xfStyles.add(XfStyleSpec(curXfNumFmtId, customFormats[curXfNumFmtId], vStyle))
                                inXf = false
                            }
                            "cellxfs" -> inCellXfs = false
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
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

    private fun parseBiff12Styles(stylesBin: ByteArray?) {
        if (stylesBin == null) return
        val customFormats = HashMap<Int, String>()
        val bb = ByteBuffer.wrap(stylesBin).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var inCellXfs = false

        while (pos < stylesBin.size && xfStyles.size < 16384) {
            val hdr = readBiff12RecordHeader(stylesBin, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x002C -> { // BrtFmt
                    if (recLen >= 6) {
                        val fmtId = bb.getShort(dataStart).toInt() and 0xFFFF
                        val cch = bb.getInt(dataStart + 2)
                        if (cch in 1..1024 && dataStart + 6 + cch * 2 <= dataStart + recLen) {
                            customFormats[fmtId] = String(stylesBin, dataStart + 6, cch * 2, StandardCharsets.UTF_16LE)
                        }
                    }
                }
                0x0269 -> inCellXfs = true  // BrtBeginCellXFs
                0x026A -> inCellXfs = false // BrtEndCellXFs
                0x002F -> { // BrtXF
                    if (inCellXfs && recLen >= 4) {
                        val numFmtId = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        xfStyles.add(
                            XfStyleSpec(
                                numFmtId = numFmtId,
                                formatCode = customFormats[numFmtId],
                                visualStyle = CellVisualStyle()
                            )
                        )
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

    private fun parseBiff12WorkbookSheetNames(wbBytes: ByteArray?): List<String> {
        if (wbBytes == null) return emptyList()
        val names = ArrayList<String>()
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
                        names.add(String(wbBytes, nameCharsStart, nameCch * 2, StandardCharsets.UTF_16LE))
                    }
                }
            }
            pos = dataStart + recLen
        }
        return names
    }

    private fun formatXlsbNumericValue(d: Double, styleRef: Int): String {
        if (!d.isFinite()) return ""
        val xf = xfStyles.getOrNull(styleRef)
        if (xf != null) {
            if (OoxmlDateStyleHelper.isDateFormat(xf.numFmtId, xf.formatCode)) {
                return OoxmlDateStyleHelper.formatSerialDate(d, use1904DateSystem, xf.numFmtId, xf.formatCode)
            }
            if (OoxmlDateStyleHelper.isPercentFormat(xf.numFmtId, xf.formatCode)) {
                return OoxmlDateStyleHelper.formatPercent(d, xf.numFmtId, xf.formatCode)
            }
            return OoxmlDateStyleHelper.formatNumericWithStyle(d, xf.numFmtId, xf.formatCode)
        }
        return OoxmlDateStyleHelper.formatCleanNumber(d)
    }

    private fun parseBiff12Worksheet(
        sheetName: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): SheetModel {
        val rowsMap = TreeMap<Int, ArrayList<String>>()
        val colWidths = HashMap<Int, Float>()
        val merges = ArrayList<MergedCellRegion>()
        val bb = ByteBuffer.wrap(sheetBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var currentRowIdx = -1
        var maxColSeen = -1

        fun putCellAt(r: Int, col: Int, value: String) {
            if (r !in 0 until MAX_SHEET_ROWS || col !in 0 until MAX_SHEET_COLS || value.isEmpty()) return
            val rowList = rowsMap.getOrPut(r) { ArrayList() }
            while (rowList.size <= col) rowList.add("")
            rowList[col] = value
            if (col > maxColSeen) maxColSeen = col
        }

        while (pos < sheetBytes.size && rowsMap.size < MAX_SHEET_ROWS) {
            val hdr = readBiff12RecordHeader(sheetBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x003C -> { // BrtColInfo
                    if (recLen >= 12) {
                        val colFirst = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_COLS - 1)
                        val colLast = bb.getInt(dataStart + 4).coerceIn(colFirst, MAX_SHEET_COLS - 1)
                        val coldx = bb.getInt(dataStart + 8)
                        if (coldx > 0) {
                            val px = SpreadsheetCoordinateUtils.excelCharWidthToPixels(coldx / 256f)
                            for (c in colFirst..minOf(colLast, colFirst + 128)) {
                                colWidths[c] = px
                            }
                        }
                    }
                }
                0x0000 -> { // BrtRowHdr
                    if (recLen >= 4) {
                        currentRowIdx = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_ROWS - 1)
                    }
                }
                0x0002 -> { // BrtCellRk
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val rk = bb.getInt(dataStart + 8)
                        val isDiv100 = (rk and 1) != 0
                        val isInt = (rk and 2) != 0
                        val raw = if (isInt) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and -4L) shl 32)
                        val v = if (isDiv100) raw / 100.0 else raw
                        putCellAt(currentRowIdx, col, formatXlsbNumericValue(v, styleRef))
                    }
                }
                0x0004, 0x000A -> { // BrtCellBool / BrtFmlaBool
                    if (recLen >= 9 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val bVal = sheetBytes[dataStart + 8].toInt() != 0
                        putCellAt(currentRowIdx, col, if (bVal) "TRUE" else "FALSE")
                    }
                }
                0x0005, 0x0009 -> { // BrtCellReal / BrtFmlaNum
                    if (recLen >= 16 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val d = bb.getDouble(dataStart + 8)
                        if (d.isFinite()) {
                            putCellAt(currentRowIdx, col, formatXlsbNumericValue(d, styleRef))
                        }
                    }
                }
                0x0006, 0x0008 -> { // BrtCellSt / BrtFmlaString
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val cch = bb.getInt(dataStart + 8)
                        if (cch in 0..32768 && dataStart + 12 + cch * 2 <= dataStart + recLen) {
                            putCellAt(currentRowIdx, col, String(sheetBytes, dataStart + 12, cch * 2, StandardCharsets.UTF_16LE))
                        }
                    }
                }
                0x0007 -> { // BrtCellIsst
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val isst = bb.getInt(dataStart + 8)
                        if (isst in sharedStrings.indices) {
                            putCellAt(currentRowIdx, col, sharedStrings[isst])
                        }
                    }
                }
                0x00B0 -> { // BrtMergeCell
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

        val maxRowKey = rowsMap.keys.maxOrNull() ?: -1
        val denseRows = if (maxRowKey >= 0) {
            ArrayList<List<String>>(maxRowKey + 1).apply {
                for (r in 0..maxRowKey) {
                    add(rowsMap[r] ?: emptyList())
                }
            }
        } else {
            emptyList()
        }
        val colWidthsArr = if (maxColSeen >= 0 && colWidths.isNotEmpty()) {
            FloatArray(maxColSeen + 1) { c -> colWidths[c] ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH }
        } else null

        return SheetModel(
            name = sheetName,
            rows = denseRows,
            colWidthsPx = colWidthsArr,
            mergedRegions = merges
        )
    }

    private fun parseXlsx() {
        if (isEncryptedOle2Container(pfd.fileDescriptor)) {
            lastErrorMsg = "PASSWORD_REQUIRED: Таблица Excel (.xlsx) защищена паролем."
            return
        }

        val zipEntries: Map<String, ByteArray> = try {
            OfficeZipContainerLoader.readZipEntries(
                pfd = pfd,
                maxTotalBytes = 128 * 1024 * 1024L
            ) { normName ->
                normName.endsWith(".xml", ignoreCase = true) ||
                        normName.endsWith(".rels", ignoreCase = true) ||
                        normName.endsWith(".bin", ignoreCase = true)
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
            val sheetBinKeys = zipEntries.keys.filter {
                val l = it.lowercase(Locale.ROOT)
                l.startsWith("xl/worksheets/") && l.endsWith(".bin")
            }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

            sheetBinKeys.forEachIndexed { idx, key ->
                val bytes = zipEntries[key] ?: return@forEachIndexed
                val sName = wbNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "Sheet ${idx + 1}"
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
                                if (aName == "r:id" || aName.endsWith(":id")) {
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

        data class PendingSheetData(
            val name: String,
            val grid: ArrayList<ArrayList<String>>,
            val formulas: Map<Long, String>,
            val styles: Map<Long, CellVisualStyle>,
            val colWidthsPx: FloatArray?,
            val mergedRegions: List<MergedCellRegion>
        )

        val pendingSheets = ArrayList<RawWorksheetParseResult>()
        for ((sheetName, sheetPath) in orderedSheets) {
            val sheetBytes = zipEntries[sheetPath] ?: continue
            val parsed = parseWorksheetRaw(sheetName, sheetBytes, sharedStrings)
            if (parsed.grid.isNotEmpty() || orderedSheets.size == 1) {
                pendingSheets.add(parsed)
            }
        }

        val sheetByName = HashMap<String, RawWorksheetParseResult>()
        for (ps in pendingSheets) {
            sheetByName[ps.name.lowercase(Locale.ROOT)] = ps
        }

        val crossSheetLookup: (String, Int, Int) -> String? = { targetSheetName, r, c ->
            val target = sheetByName[targetSheetName.lowercase(Locale.ROOT)]
            target?.grid?.getOrNull(r)?.getOrNull(c)
        }

        for (ps in pendingSheets) {
            if (ps.formulas.isNotEmpty()) {
                CleanRoomFormulaEvaluator.evaluateSheetFormulas(ps.grid, ps.formulas, crossSheetLookup)
            }
            sheets.add(
                SheetModel(
                    name = ps.name,
                    rows = ps.grid,
                    styles = ps.styles,
                    colWidthsPx = ps.colWidthsPx,
                    mergedRegions = ps.mergedRegions
                )
            )
        }

        if (sheets.isEmpty()) {
            lastErrorMsg = "В книге XLSX не обнаружено листов с данными."
        }
    }

    private data class RawWorksheetParseResult(
        val name: String,
        val grid: ArrayList<ArrayList<String>>,
        val formulas: Map<Long, String>,
        val styles: Map<Long, CellVisualStyle>,
        val colWidthsPx: FloatArray?,
        val mergedRegions: List<MergedCellRegion>
    )

    private fun parseWorksheetRaw(
        name: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): RawWorksheetParseResult {
        val grid = ArrayList<ArrayList<String>>()
        val stylesMap = HashMap<Long, CellVisualStyle>()
        val formulasMap = HashMap<Long, String>()
        val colWidthsMap = HashMap<Int, Float>()
        val mergedRegions = ArrayList<MergedCellRegion>()
        var maxColSeen = -1

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
            val cellTextBuilder = StringBuilder()
            val formulaTextBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT && grid.size < MAX_SHEET_ROWS) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "col" -> {
                                val minCol = (parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                                val maxCol = (parser.getAttributeValue(null, "max")?.toIntOrNull() ?: (minCol + 1)) - 1
                                val widthChars = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                val hidden = parser.getAttributeValue(null, "hidden")
                                if (widthChars != null && widthChars > 0f && hidden != "1" && !hidden.equals("true", true)) {
                                    val px = SpreadsheetCoordinateUtils.excelCharWidthToPixels(widthChars)
                                    val startC = minCol.coerceIn(0, MAX_SHEET_COLS - 1)
                                    val endC = maxCol.coerceIn(startC, minOf(startC + 128, MAX_SHEET_COLS - 1))
                                    for (c in startC..endC) {
                                        colWidthsMap[c] = px
                                    }
                                }
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
                                } else {
                                    currentRow = null
                                }
                                currentColIdx = 0
                            }
                            "c" -> {
                                currentCellType = parser.getAttributeValue(null, "t")
                                currentCellStyleIdx = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                                val cellRef = parser.getAttributeValue(null, "r")
                                val parsedCol = SpreadsheetCoordinateUtils.columnIndexFromCellRef(cellRef)
                                if (parsedCol >= 0) {
                                    currentColIdx = parsedCol
                                }
                                cellTextBuilder.setLength(0)
                                formulaTextBuilder.setLength(0)
                            }
                            "f" -> inFormula = true
                            "v" -> inV = true
                            "is" -> inIs = true
                            "t" -> if (inIs) inV = true
                            "mergecell" -> {
                                val ref = parser.getAttributeValue(null, "ref") ?: ""
                                val parts = ref.split(':')
                                if (parts.size == 2 && mergedRegions.size < 4096) {
                                    val c0 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[0])
                                    val c1 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[1])
                                    val r0 = (parts[0].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    val r1 = (parts[1].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    if (c0 in 0..c1 && r0 in 0..r1 && c1 < MAX_SHEET_COLS && r1 < MAX_SHEET_ROWS) {
                                        mergedRegions.add(MergedCellRegion(r0, r1, c0, c1))
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
                            "is" -> inIs = false
                            "c" -> {
                                val row = currentRow
                                if (row != null && currentColIdx in 0 until MAX_SHEET_COLS) {
                                    val rawVal = cellTextBuilder.toString().trim()
                                    val xfSpec = xfStyles.getOrNull(currentCellStyleIdx)

                                    val resolved = when (currentCellType) {
                                        "s" -> {
                                            val idx = rawVal.toIntOrNull() ?: -1
                                            sharedStrings.getOrElse(idx) { rawVal }
                                        }
                                        "b" -> if (rawVal == "1" || rawVal.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
                                        "str", "inlineStr", "e" -> rawVal
                                        else -> {
                                            val numVal = rawVal.toDoubleOrNull()
                                            if (numVal != null && xfSpec != null) {
                                                when {
                                                    OoxmlDateStyleHelper.isDateFormat(xfSpec.numFmtId, xfSpec.formatCode) ->
                                                        OoxmlDateStyleHelper.formatSerialDate(numVal, use1904DateSystem, xfSpec.numFmtId, xfSpec.formatCode)
                                                    OoxmlDateStyleHelper.isPercentFormat(xfSpec.numFmtId, xfSpec.formatCode) ->
                                                        OoxmlDateStyleHelper.formatPercent(numVal, xfSpec.numFmtId, xfSpec.formatCode)
                                                    else ->
                                                        OoxmlDateStyleHelper.formatNumericWithStyle(numVal, xfSpec.numFmtId, xfSpec.formatCode)
                                                }
                                            } else if (numVal != null) {
                                                OoxmlDateStyleHelper.formatCleanNumber(numVal)
                                            } else {
                                                rawVal
                                            }
                                        }
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

                                    val packedCoord = packCoord(currentRowIdx, currentColIdx)
                                    val fStr = formulaTextBuilder.toString().trim()
                                    if (fStr.isNotEmpty() && resolved.isEmpty() && formulasMap.size < 100_000) {
                                        formulasMap[packedCoord] = fStr
                                    }

                                    if (xfSpec != null && currentRowIdx >= 0 && stylesMap.size < 250_000) {
                                        val vs = xfSpec.visualStyle
                                        if (vs.fillColor != null || vs.textColor != null || vs.isBold || vs.isItalic ||
                                            vs.isUnderline || vs.isStrike || vs.horizontalAlign != Paint.Align.LEFT
                                        ) {
                                            stylesMap[packedCoord] = vs
                                        }
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

        val colWidthsArr = if (maxColSeen >= 0 && colWidthsMap.isNotEmpty()) {
            FloatArray(maxColSeen + 1) { c -> colWidthsMap[c] ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH }
        } else null

        return RawWorksheetParseResult(
            name = name,
            grid = grid,
            formulas = formulasMap,
            styles = stylesMap,
            colWidthsPx = colWidthsArr,
            mergedRegions = mergedRegions
        )
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}

class OdsSpreadsheetSession(private val pfd: ParcelFileDescriptor) : BaseGridSpreadsheetSession() {
    companion object {
        private const val MAX_ODS_ROWS = 10_000
        private const val MAX_ODS_COLS = 256
    }

    init {
        parseOds()
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

    private fun parseOdsWidthToPx(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        val cm = when {
            s.endsWith("cm") -> s.removeSuffix("cm").trim().toFloatOrNull()
            s.endsWith("mm") -> s.removeSuffix("mm").trim().toFloatOrNull()?.div(10f)
            s.endsWith("in") -> s.removeSuffix("in").trim().toFloatOrNull()?.times(2.54f)
            s.endsWith("pt") -> s.removeSuffix("pt").trim().toFloatOrNull()?.times(0.0352778f)
            else -> null
        } ?: return null
        return (cm * 56f).coerceIn(54f, 680f)
    }

    private fun parseOds() {
        try {
            val entries = OfficeZipContainerLoader.readZipEntries(
                pfd = pfd,
                maxTotalBytes = 96 * 1024 * 1024L
            ) { normName ->
                normName.equals("content.xml", ignoreCase = true) ||
                        normName.equals("styles.xml", ignoreCase = true)
            }

            val contentBytes = entries["content.xml"]
            if (contentBytes == null) {
                lastErrorMsg = "В контейнере ODS отсутствует обязательный файл content.xml."
                return
            }

            val styleMap = HashMap<String, CellVisualStyle>()
            val colWidthStyleMap = HashMap<String, Float>()
            for (xmlSource in listOfNotNull(entries["styles.xml"], contentBytes)) {
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(xmlSource), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var activeStyleName: String? = null
                    var curFill: Int? = null
                    var curTextCol: Int? = null
                    var curBold = false
                    var curItalic = false
                    var curAlign = Paint.Align.LEFT

                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val local = sp.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                        when (ev) {
                            XmlPullParser.START_TAG -> {
                                when (local) {
                                    "style" -> {
                                        activeStyleName = findAttrBySuffix(sp, "name")
                                        curFill = null
                                        curTextCol = null
                                        curBold = false
                                        curItalic = false
                                        curAlign = Paint.Align.LEFT
                                    }
                                    "table-column-properties" -> if (activeStyleName != null) {
                                        parseOdsWidthToPx(findAttrBySuffix(sp, "column-width"))?.let {
                                            colWidthStyleMap[activeStyleName!!] = it
                                        }
                                    }
                                    "table-cell-properties" -> if (activeStyleName != null) {
                                        val bgHex = findAttrBySuffix(sp, "background-color")
                                        if (!bgHex.isNullOrBlank() && bgHex.startsWith("#")) {
                                            try { curFill = Color.parseColor(bgHex) } catch (_: Exception) {}
                                        }
                                    }
                                    "text-properties" -> if (activeStyleName != null) {
                                        val fgHex = findAttrBySuffix(sp, "color")
                                        if (!fgHex.isNullOrBlank() && fgHex.startsWith("#")) {
                                            try { curTextCol = Color.parseColor(fgHex) } catch (_: Exception) {}
                                        }
                                        val fw = findAttrBySuffix(sp, "font-weight")
                                        if (fw.equals("bold", ignoreCase = true)) curBold = true
                                        val fs = findAttrBySuffix(sp, "font-style")
                                        if (fs.equals("italic", ignoreCase = true)) curItalic = true
                                    }
                                    "paragraph-properties" -> if (activeStyleName != null) {
                                        val ta = findAttrBySuffix(sp, "text-align")?.lowercase(Locale.ROOT)
                                        curAlign = when (ta) {
                                            "center" -> Paint.Align.CENTER
                                            "end", "right" -> Paint.Align.RIGHT
                                            else -> Paint.Align.LEFT
                                        }
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (local == "style") {
                                    val sName = activeStyleName
                                    if (sName != null && (curFill != null || curTextCol != null || curBold || curItalic || curAlign != Paint.Align.LEFT)) {
                                        styleMap[sName] = CellVisualStyle(
                                            fillColor = curFill,
                                            textColor = curTextCol,
                                            isBold = curBold,
                                            isItalic = curItalic,
                                            horizontalAlign = curAlign
                                        )
                                    }
                                    activeStyleName = null
                                }
                            }
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var currentTableName = "Sheet 1"
            var currentGrid = ArrayList<ArrayList<String>>()
            var currentStyles = HashMap<Long, CellVisualStyle>()
            var currentFormulas = HashMap<Long, String>()
            var currentColWidths = ArrayList<Float>()
            var currentMerges = ArrayList<MergedCellRegion>()
            var currentRow: ArrayList<String>? = null
            var currentRowRepeat = 1
            var currentRowStyleName: String? = null
            var currentCellSb: StringBuilder? = null
            var currentCellFormula: String? = null
            var currentColRepeat = 1
            var currentColSpan = 1
            var currentRowSpan = 1
            var currentCellStyleName: String? = null
            var inTable = false
            var inCell = false

            while (ev != XmlPullParser.END_DOCUMENT && sheets.size < 64) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "table" -> {
                                inTable = true
                                currentTableName = findAttrBySuffix(parser, "name") ?: "Sheet ${sheets.size + 1}"
                                currentGrid = ArrayList()
                                currentStyles = HashMap()
                                currentFormulas = HashMap()
                                currentColWidths = ArrayList()
                                currentMerges = ArrayList()
                            }
                            "table-column" -> if (inTable) {
                                val colStyleName = findAttrBySuffix(parser, "style-name")
                                val repeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                val wPx = colStyleName?.let { colWidthStyleMap[it] } ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
                                repeat(repeat) {
                                    if (currentColWidths.size < MAX_ODS_COLS) currentColWidths.add(wPx)
                                }
                            }
                            "table-row" -> if (inTable && currentGrid.size < MAX_ODS_ROWS) {
                                currentRow = ArrayList()
                                currentRowRepeat = (findAttrBySuffix(parser, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 32)
                                currentRowStyleName = findAttrBySuffix(parser, "style-name")
                            }
                            "table-cell", "covered-table-cell" -> if (currentRow != null) {
                                inCell = true
                                currentCellSb = StringBuilder()
                                currentColRepeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                currentColSpan = (findAttrBySuffix(parser, "number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                currentRowSpan = (findAttrBySuffix(parser, "number-rows-spanned")?.toIntOrNull() ?: 1).coerceIn(1, 128)
                                currentCellStyleName = findAttrBySuffix(parser, "style-name") ?: currentRowStyleName
                                currentCellFormula = findAttrBySuffix(parser, "formula")
                            }
                            "p" -> {
                                if (inCell && currentCellSb != null && currentCellSb.isNotEmpty()) {
                                    currentCellSb.append(' ')
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCell && currentCellSb != null && currentCellSb.length < 32768) {
                            currentCellSb.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "table-cell", "covered-table-cell" -> {
                                val row = currentRow
                                if (inCell && row != null) {
                                    val txt = currentCellSb?.toString()?.trim() ?: ""
                                    val styleObj = currentCellStyleName?.let { styleMap[it] }
                                    val limit = if (txt.isEmpty() && styleObj == null && currentCellFormula == null && currentColRepeat > 8) 1 else currentColRepeat
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

                                    repeat(limit) {
                                        if (row.size < MAX_ODS_COLS) {
                                            val colIdx = row.size
                                            row.add(txt)
                                            val packed = packCoord(targetRowIdx, colIdx)
                                            if (styleObj != null && currentStyles.size < 250_000) {
                                                currentStyles[packed] = styleObj
                                            }
                                            if (!currentCellFormula.isNullOrBlank() && txt.isEmpty() && currentFormulas.size < 100_000) {
                                                currentFormulas[packed] = currentCellFormula!!
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
                                        if (currentStyles.containsKey(packCoord(currentGrid.size, colIdx)) ||
                                            currentFormulas.containsKey(packCoord(currentGrid.size, colIdx))
                                        ) break
                                        row.removeAt(row.lastIndex)
                                    }
                                    if (row.isNotEmpty() || currentGrid.size < 50) {
                                        val rep = if (row.isEmpty()) 1 else currentRowRepeat
                                        repeat(rep) {
                                            if (currentGrid.size < MAX_ODS_ROWS) {
                                                currentGrid.add(ArrayList(row))
                                            }
                                        }
                                    }
                                }
                                currentRow = null
                            }
                            "table" -> {
                                while (currentGrid.isNotEmpty() && currentGrid.last().isEmpty()) {
                                    currentGrid.removeAt(currentGrid.lastIndex)
                                }
                                if (currentFormulas.isNotEmpty()) {
                                    CleanRoomFormulaEvaluator.evaluateSheetFormulas(currentGrid, currentFormulas)
                                }
                                if (currentGrid.isNotEmpty()) {
                                    val maxDataCols = currentGrid.maxOfOrNull { it.size } ?: 0
                                    val widthsArr = if (currentColWidths.isNotEmpty() && maxDataCols > 0) {
                                        FloatArray(maxDataCols) { c ->
                                            currentColWidths.getOrElse(c) { SpreadsheetCoordinateUtils.BASE_CELL_WIDTH }
                                        }
                                    } else null
                                    sheets.add(
                                        SheetModel(
                                            name = currentTableName,
                                            rows = currentGrid,
                                            styles = currentStyles,
                                            colWidthsPx = widthsArr,
                                            mergedRegions = currentMerges
                                        )
                                    )
                                }
                                inTable = false
                            }
                        }
                    }
                }
                ev = parser.next()
            }
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
        try { pfd.close() } catch (_: Exception) {}
    }
}

class Rfc4180CsvTsvSession(
    private val pfd: ParcelFileDescriptor,
    private val ext: String
) : BaseGridSpreadsheetSession() {
    init {
        parseStreamingRfc4180()
    }

    private fun detectCharsetAndDelimiter(fd: FileDescriptor, fileSize: Long): Triple<Charset, Int, Char> {
        val sampleLen = minOf(if (fileSize > 0L) fileSize else 8192L, 8192L).toInt()
        if (sampleLen <= 0) return Triple(StandardCharsets.UTF_8, 0, if (ext == "tsv") '\t' else ',')
        val buf = ByteBuffer.allocateDirect(sampleLen)
        val r = try { Os.pread(fd, buf, 0L) } catch (_: Throwable) { 0 }
        if (r <= 0) return Triple(StandardCharsets.UTF_8, 0, if (ext == "tsv") '\t' else ',')
        val arr = ByteArray(r)
        buf.flip()
        buf.get(arr)

        val (charset, bomSkip) = try {
            PlainTextSession.detectCharsetAndValidateText(arr, r)
        } catch (_: Exception) {
            StandardCharsets.UTF_8 to 0
        }

        if (ext == "tsv") return Triple(charset, bomSkip, '\t')

        val sampleText = String(arr, bomSkip, (r - bomSkip).coerceAtLeast(0), charset)
        val firstNonBlankLine = sampleText.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        var commas = 0
        var semicolons = 0
        var tabs = 0
        var pipes = 0
        var inQuotes = false
        for (ch in firstNonBlankLine) {
            if (ch == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                when (ch) {
                    ',' -> commas++
                    ';' -> semicolons++
                    '\t' -> tabs++
                    '|' -> pipes++
                }
            }
        }
        val delimiter = when {
            tabs > commas && tabs >= semicolons && tabs >= pipes -> '\t'
            semicolons > commas && semicolons >= pipes -> ';'
            pipes > commas && pipes > semicolons -> '|'
            else -> ','
        }
        return Triple(charset, bomSkip, delimiter)
    }

    private fun parseStreamingRfc4180() {
        val rows = mutableListOf<List<String>>()
        try {
            val fileSize = resolveDescriptorSize(pfd.fileDescriptor)
            val (charset, bomSkip, delimiter) = detectCharsetAndDelimiter(pfd.fileDescriptor, fileSize)
            val stream = OfficePreadInputStream(pfd.fileDescriptor, fileSize)
            repeat(bomSkip) { stream.read() }

            val reader = BufferedReader(InputStreamReader(stream, charset), 32 * 1024)
            var currentRow = mutableListOf<String>()
            val currentCell = StringBuilder()
            var inQuotes = false
            var chInt = reader.read()

            while (chInt != -1 && rows.size < 15_000) {
                val c = chInt.toChar()
                if (inQuotes) {
                    if (c == '"') {
                        reader.mark(1)
                        val next = reader.read()
                        if (next == '"'.code) {
                            if (currentCell.length < 16384) currentCell.append('"')
                        } else {
                            inQuotes = false
                            if (next != -1) reader.reset()
                        }
                    } else {
                        if (currentCell.length < 16384) currentCell.append(c)
                    }
                } else {
                    when (c) {
                        '"' -> inQuotes = true
                        delimiter -> {
                            if (currentRow.size < 512) {
                                currentRow.add(currentCell.toString().trim())
                            }
                            currentCell.setLength(0)
                        }
                        '\r' -> {}
                        '\n' -> {
                            if (currentRow.size < 512) {
                                currentRow.add(currentCell.toString().trim())
                            }
                            currentCell.setLength(0)
                            if (currentRow.any { it.isNotEmpty() }) {
                                rows.add(currentRow)
                            }
                            currentRow = mutableListOf()
                        }
                        else -> if (currentCell.length < 16384) currentCell.append(c)
                    }
                }
                chInt = reader.read()
            }
            if (currentCell.isNotEmpty() || currentRow.isNotEmpty()) {
                if (currentRow.size < 512) {
                    currentRow.add(currentCell.toString().trim())
                }
                if (currentRow.any { it.isNotEmpty() }) {
                    rows.add(currentRow)
                }
            }
            val title = if (ext == "tsv") "TSV Data" else "CSV Data"
            sheets.add(SheetModel(title, rows))
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