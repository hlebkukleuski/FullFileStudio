/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.Color
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

internal const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
internal const val ANIM_STEP_SHIFT = 20
internal const val PAGE_INDEX_MASK = 0x000FFFFF
internal val DARK_PRESENTATION_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
internal val LIGHT_PRESENTATION_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)

internal fun resolveDescriptorSize(fd: FileDescriptor): Long {
    val size = ZeroCacheReadableSource(fd).size
    return if (size > 0L) size else -1L
}

internal fun isEncryptedOle2Container(fd: FileDescriptor): Boolean {
    return try {
        val header = ByteBuffer.allocateDirect(8)
        val r = ZeroCacheReadableSource(fd).readAt(0L, header)
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
    private val source: ZeroCacheReadableSource,
    private val totalSize: Long
) : InputStream() {
    constructor(fd: FileDescriptor, totalSize: Long) : this(ZeroCacheReadableSource(fd), totalSize)
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
        val r = source.readAt(pos, reusableDirect)
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
        val source = ZeroCacheReadableSource(pfd)
        val size = source.size
        val cap = if (size > 0L) minOf(size, maxBytes).toInt() else minOf(32L * 1024L * 1024L, maxBytes).toInt()
        val buf = ByteBuffer.allocateDirect(cap)
        val got = source.readAt(0L, buf)
        if (got <= 0) return ByteArray(0)
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
                            if (totalUnpacked >= maxTotalBytes) {
                                if (fileSize in 1L..(totalUnpacked / MAX_SUSPICIOUS_COMPRESSION_RATIO)) {
                                    throw SecurityException("Превышен лимит распаковки контейнера OpenXML (защита от Zip-бомб).")
                                }
                                break
                            }
                            val entryBudget = (maxTotalBytes - totalUnpacked).coerceAtLeast(0L)
                            val entryData = when (cand.method) {
                                0 -> if (compArray.size.toLong() <= entryBudget) compArray else null
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
        val r = ZeroCacheReadableSource(fd).readAt(startOffset, buf)
        return if (r > 0) r else 0
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
