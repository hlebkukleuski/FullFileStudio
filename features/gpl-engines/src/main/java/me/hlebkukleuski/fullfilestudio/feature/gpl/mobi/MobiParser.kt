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
 * HUFF/CDIC decompression and trailing-entry parsing informed by Calibre
 * (https://calibre-ebook.com/, huffcdic.py), Copyright (C) 2008-2026 Kovid Goyal
 * and Calibre Contributors, licensed under the GNU General Public License v3 (GPL-3.0-or-later).
 * Adapted and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.mobi

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.security.CorruptedDataException

class MobiBook(
    val title: String?,
    val html: String,
    val isKf8: Boolean,
    val images: List<ByteArray>,
    val imageIndexByRecordOffset: Map<Int, Int>
)

class MobiDrmException(val encryptionType: Int) : IllegalStateException("Encrypted MOBI (type $encryptionType)")

class HuffCdicDecoder(huffRecord: ByteArray, cdicRecords: List<ByteArray>) {

    private val dict1Len = IntArray(256)
    private val dict1Term = BooleanArray(256)
    private val dict1Max = LongArray(256)
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val phrases = ArrayList<ByteArray?>()
    private val phraseDone = ArrayList<Boolean>()
    private var depth = 0

    init {
        if (huffRecord.size < 24 || String(huffRecord, 0, 4, StandardCharsets.US_ASCII) != "HUFF" || be32(huffRecord, 4) != 0x18L) {
            throw CorruptedDataException("Bad HUFF record")
        }
        val off1 = be32(huffRecord, 8).toInt()
        val off2 = be32(huffRecord, 12).toInt()
        if (off1 < 0 || off1 + 1024 > huffRecord.size || off2 < 0 || off2 + 256 > huffRecord.size) throw CorruptedDataException("Bad HUFF tables")
        for (i in 0 until 256) {
            val v = be32(huffRecord, off1 + i * 4)
            val len = (v and 0x1F).toInt()
            if (len == 0) throw CorruptedDataException("Zero HUFF code length")
            dict1Len[i] = len
            dict1Term[i] = v and 0x80 != 0L
            dict1Max[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
        }
        for (len in 1..32) {
            val mn = be32(huffRecord, off2 + (len - 1) * 8)
            val mx = be32(huffRecord, off2 + (len - 1) * 8 + 4)
            minCode[len] = mn shl (32 - len)
            maxCode[len] = ((mx + 1) shl (32 - len)) - 1
        }
        for (cdic in cdicRecords) {
            if (cdic.size < 16 || String(cdic, 0, 4, StandardCharsets.US_ASCII) != "CDIC" || be32(cdic, 4) != 0x10L) {
                throw CorruptedDataException("Bad CDIC record")
            }
            val total = be32(cdic, 8)
            val bits = be32(cdic, 12).toInt()
            if (bits !in 0..20) throw CorruptedDataException("Bad CDIC width")
            val n = minOf(1L shl bits, total - phrases.size).toInt()
            if (n < 0 || 16 + n * 2 > cdic.size) throw CorruptedDataException("Bad CDIC count")
            for (i in 0 until n) {
                val off = ((cdic[16 + i * 2].toInt() and 0xFF) shl 8) or (cdic[17 + i * 2].toInt() and 0xFF)
                if (16 + off + 2 > cdic.size) throw CorruptedDataException("Bad CDIC offset")
                val blen = ((cdic[16 + off].toInt() and 0xFF) shl 8) or (cdic[17 + off].toInt() and 0xFF)
                val len = blen and 0x7FFF
                if (18 + off + len > cdic.size) throw CorruptedDataException("Bad CDIC entry")
                phrases.add(cdic.copyOfRange(18 + off, 18 + off + len))
                phraseDone.add(blen and 0x8000 != 0)
            }
        }
    }

    private fun be32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    fun decode(data: ByteArray, start: Int, end: Int, out: ByteArrayOutputStream) {
        if (++depth > 32) throw CorruptedDataException("HUFF recursion too deep")
        try {
            var bitsLeft = (end - start).toLong() * 8
            var pos = start
            fun word(p: Int): Long {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (if (p + i < end) data[p + i].toLong() and 0xFF else 0L)
                return v
            }
            var x = word(pos)
            var n = 32
            while (true) {
                if (n <= 0) {
                    pos += 4
                    x = word(pos)
                    n += 32
                }
                val code = (x ushr n) and 0xFFFFFFFFL
                val top = (code ushr 24).toInt()
                var len = dict1Len[top]
                var max = dict1Max[top]
                if (!dict1Term[top]) {
                    while (len < 32 && code < minCode[len]) len++
                    max = maxCode[len]
                }
                n -= len
                bitsLeft -= len
                if (bitsLeft < 0) break
                val r = ((max - code) ushr (32 - len)).toInt()
                if (r < 0 || r >= phrases.size) throw CorruptedDataException("HUFF phrase index out of range")
                var phrase = phrases[r] ?: throw CorruptedDataException("Recursive HUFF phrase")
                if (!phraseDone[r]) {
                    phrases[r] = null
                    val expanded = ByteArrayOutputStream(phrase.size * 2)
                    decode(phrase, 0, phrase.size, expanded)
                    phrase = expanded.toByteArray()
                    phrases[r] = phrase
                    phraseDone[r] = true
                }
                out.write(phrase)
                if (out.size() > 64 * 1024 * 1024) throw CorruptedDataException("HUFF output too large")
            }
        } finally {
            depth--
        }
    }
}

object MobiParser {

    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    private fun be16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private class Records(val data: ByteArray, val offsets: IntArray) {
        val count: Int get() = offsets.size
        fun start(i: Int): Int = offsets[i]
        fun end(i: Int): Int = if (i + 1 < offsets.size) offsets[i + 1] else data.size
        fun bytes(i: Int): ByteArray = data.copyOfRange(start(i), end(i))
    }

    fun trailingSize(data: ByteArray, start: Int, end: Int, flags: Int): Int {
        if (flags == 0 || end <= start) return 0
        var cut = 0
        var f = flags ushr 1
        while (f != 0) {
            if (f and 1 != 0) {
                var num = 0
                val tail = end - cut
                for (p in maxOf(start, tail - 4) until tail) {
                    val v = data[p].toInt() and 0xFF
                    if (v and 0x80 != 0) num = 0
                    num = (num shl 7) or (v and 0x7F)
                }
                if (num <= 0 || num > tail - start) return end - start
                cut += num
            }
            f = f ushr 1
        }
        if (flags and 1 != 0 && end - cut - 1 >= start) {
            cut += (data[end - cut - 1].toInt() and 3) + 1
        }
        return cut.coerceAtMost(end - start)
    }

    fun decompressPalmDoc(data: ByteArray, start: Int, end: Int, out: ByteArrayOutputStream) {
        val buf = ByteArray(maxOf(4096, (end - start) * 8))
        var o = 0
        fun ensure(n: Int): ByteArray = if (o + n <= buf.size) buf else throw CorruptedDataException("PalmDoc record expands too much")
        var p = start
        while (p < end) {
            val b = data[p++].toInt() and 0xFF
            when {
                b == 0 || b in 0x09..0x7F -> { ensure(1); buf[o++] = b.toByte() }
                b in 0x01..0x08 -> {
                    val count = minOf(b, end - p)
                    ensure(count)
                    System.arraycopy(data, p, buf, o, count)
                    p += count
                    o += count
                }
                b in 0x80..0xBF -> {
                    if (p >= end) break
                    val pair = (b shl 8) or (data[p++].toInt() and 0xFF)
                    val distance = (pair ushr 3) and 0x07FF
                    val length = (pair and 7) + 3
                    if (distance == 0 || distance > o) throw CorruptedDataException("Bad PalmDoc back-reference")
                    ensure(length)
                    var from = o - distance
                    repeat(length) { buf[o++] = buf[from++] }
                }
                else -> {
                    ensure(2)
                    buf[o++] = ' '.code.toByte()
                    buf[o++] = (b xor 0x80).toByte()
                }
            }
        }
        out.write(buf, 0, o)
    }

    private fun charsetFor(codepage: Long): Charset = when (codepage) {
        65001L -> StandardCharsets.UTF_8
        1252L -> Charset.forName("windows-1252")
        1251L -> Charset.forName("windows-1251")
        1250L -> Charset.forName("windows-1250")
        else -> try { Charset.forName("windows-$codepage") } catch (_: Exception) { StandardCharsets.UTF_8 }
    }

    private class Header(val recordIndex: Int, val rec0: ByteArray) {
        init {
            if (rec0.size < 16) throw CorruptedDataException("Truncated PalmDOC record 0 header")
        }
        val compression = ((rec0[0].toInt() and 0xFF) shl 8) or (rec0[1].toInt() and 0xFF)
        val textLength = if (rec0.size >= 8) ((rec0[4].toLong() and 0xFF) shl 24) or ((rec0[5].toLong() and 0xFF) shl 16) or ((rec0[6].toLong() and 0xFF) shl 8) or (rec0[7].toLong() and 0xFF) else 0L
        val textRecordCount = ((rec0[8].toInt() and 0xFF) shl 8) or (rec0[9].toInt() and 0xFF)
        val encryption = ((rec0[12].toInt() and 0xFF) shl 8) or (rec0[13].toInt() and 0xFF)
        val hasMobi = rec0.size >= 24 && String(rec0, 16, 4, StandardCharsets.US_ASCII) == "MOBI"
        private fun f32(off: Int): Long = if (hasMobi && off + 4 <= rec0.size) {
            ((rec0[off].toLong() and 0xFF) shl 24) or ((rec0[off + 1].toLong() and 0xFF) shl 16) or ((rec0[off + 2].toLong() and 0xFF) shl 8) or (rec0[off + 3].toLong() and 0xFF)
        } else -1L
        val headerLength = f32(20)
        val codepage = f32(28)
        val version = f32(36)
        val fullNameOffset = f32(84)
        val fullNameLength = f32(88)
        val firstImage = f32(108)
        val huffRecord = f32(112)
        val huffCount = f32(116)
        val exthFlags = f32(128)
        val fdstIndex = if (headerLength >= 0xC4 - 16 + 4) f32(0xC0) else -1L
        val extraFlags = if (hasMobi && headerLength >= 0xE4) ((rec0[0xF2].toInt() and 0xFF) shl 8) or (rec0[0xF3].toInt() and 0xFF) else 0
        val exth: Map<Int, ByteArray> = run {
            val map = HashMap<Int, ByteArray>()
            if (hasMobi && exthFlags and 0x40 != 0L) {
                val p = (16 + headerLength).toInt()
                if (p + 12 <= rec0.size && String(rec0, p, 4, StandardCharsets.US_ASCII) == "EXTH") {
                    val count = f32(p + 8).toInt()
                    var q = p + 12
                    for (i in 0 until count.coerceIn(0, 10_000)) {
                        if (q + 8 > rec0.size) break
                        val type = f32(q).toInt()
                        val len = f32(q + 4).toInt()
                        if (len < 8 || q + len > rec0.size) break
                        map[type] = rec0.copyOfRange(q + 8, q + len)
                        q += len
                    }
                }
            }
            map
        }
    }

    fun parse(bytes: ByteArray, preferKf8: Boolean = true): MobiBook {
        if (bytes.size < 86) throw CorruptedDataException("File too small for a PalmDB container")
        val count = be16(bytes, 76)
        if (count < 2 || 78 + count * 8 > bytes.size) throw CorruptedDataException("Invalid PalmDB record table")
        val offsets = IntArray(count) { be32(bytes, 78 + it * 8).toInt() }
        for (i in offsets.indices) {
            if (offsets[i] < 0 || offsets[i] > bytes.size || (i > 0 && offsets[i] < offsets[i - 1])) throw CorruptedDataException("Invalid record offset")
        }
        val records = Records(bytes, offsets)
        var header = Header(0, records.bytes(0))
        if (header.encryption != 0) throw MobiDrmException(header.encryption)
        if (preferKf8 && header.version != 8L) {
            val boundary = header.exth[121]?.let { if (it.size == 4) be32(it, 0).toInt() else -1 } ?: -1
            for (candIdx in intArrayOf(boundary, boundary + 1)) {
                if (candIdx in 1 until count) {
                    val recBytes = records.bytes(candIdx)
                    if (recBytes.size >= 24 && String(recBytes, 16, 4, StandardCharsets.US_ASCII) == "MOBI") {
                        val kf8 = Header(candIdx, recBytes)
                        if (kf8.encryption != 0) throw MobiDrmException(kf8.encryption)
                        if (kf8.version == 8L) {
                            header = kf8
                            break
                        }
                    }
                }
            }
        }
        val base = header.recordIndex
        val charset = if (header.hasMobi) charsetFor(header.codepage) else Charset.forName("windows-1252")
        val huff = if (header.compression == 17480) {
            val first = (base + header.huffRecord).toInt()
            val n = header.huffCount.toInt()
            if (header.huffRecord <= 0 || n < 2 || first + n > count) throw CorruptedDataException("Missing HUFF/CDIC records")
            HuffCdicDecoder(records.bytes(first), (first + 1 until first + n).map { records.bytes(it) })
        } else null

        val raw = ByteArrayOutputStream(minOf(header.textLength.coerceIn(0, MAX_TEXT_BYTES.toLong()).toInt() + 16, MAX_TEXT_BYTES))
        for (i in 1..header.textRecordCount) {
            val idx = base + i
            if (idx >= count) break
            val s = records.start(idx)
            val e = records.end(idx)
            val end = e - trailingSize(bytes, s, e, header.extraFlags)
            when (header.compression) {
                1 -> raw.write(bytes, s, end - s)
                2 -> decompressPalmDoc(bytes, s, end, raw)
                17480 -> huff!!.decode(bytes, s, end, raw)
                else -> throw UnsupportedOperationException("Unknown MOBI compression ${header.compression}")
            }
            if (raw.size() > MAX_TEXT_BYTES) throw CorruptedDataException("Book text too large")
        }
        var text = raw.toByteArray()
        if (header.textLength in 1..text.size.toLong()) text = text.copyOf(header.textLength.toInt())

        val isKf8 = header.version == 8L
        if (isKf8 && header.fdstIndex > 0 && base + header.fdstIndex < count) {
            val fdst = records.bytes((base + header.fdstIndex).toInt())
            if (fdst.size >= 12 && String(fdst, 0, 4, StandardCharsets.US_ASCII) == "FDST") {
                val sections = be32(fdst, 8).toInt()
                if (sections >= 1 && fdst.size >= 12 + sections * 8) {
                    val s = be32(fdst, 12).toInt()
                    val e = be32(fdst, 16).toInt()
                    if (s in 0..e && e <= text.size) text = text.copyOfRange(s, e)
                }
            }
        }

        val images = ArrayList<ByteArray>()
        val imageIndex = HashMap<Int, Int>()
        val firstImage = header.firstImage
        if (firstImage in 1 until Int.MAX_VALUE.toLong()) {
            val start = (base + firstImage).toInt().coerceAtMost(count)
            for (idx in start until count) {
                val s = records.start(idx)
                val e = records.end(idx)
                if (e - s < 4) continue
                val magic = String(bytes, s, 4, StandardCharsets.ISO_8859_1)
                if (magic in setOf("FLIS", "FCIS", "SRCS", "DATP", "RESC", "FDST", "INDX", "HUFF", "CDIC", "BOUN", "CRES", "CONT", "kind")) {
                    if (magic == "BOUN" || magic == "CONT") break
                    continue
                }
                if (e - s == 4 && bytes[s] == 0xE9.toByte()) break
                if (isImage(bytes, s, e)) {
                    imageIndex[idx - start + 1] = images.size
                    images.add(bytes.copyOfRange(s, e))
                }
            }
        }
        val title = header.exth[503]?.toString(charset)
            ?: if (header.fullNameOffset > 0 && header.fullNameLength > 0 && header.fullNameOffset + header.fullNameLength <= header.rec0.size) {
                String(header.rec0, header.fullNameOffset.toInt(), header.fullNameLength.toInt(), charset)
            } else null
        return MobiBook(title, String(text, charset), isKf8, images, imageIndex)
    }

    private fun isImage(b: ByteArray, s: Int, e: Int): Boolean {
        if (e - s < 8) return false
        val b0 = b[s].toInt() and 0xFF
        val b1 = b[s + 1].toInt() and 0xFF
        return (b0 == 0xFF && b1 == 0xD8) ||
                (b0 == 0x89 && b1 == 0x50 && b[s + 2] == 'N'.code.toByte()) ||
                (b0 == 'G'.code && b1 == 'I'.code && b[s + 2] == 'F'.code.toByte()) ||
                (b0 == 'B'.code && b1 == 'M'.code) ||
                (b0 == 'R'.code && b1 == 'I'.code && b[s + 8] == 'W'.code.toByte())
    }

    private val recIndexRegex = Regex("""<img\b[^>]*?\brecindex\s*=\s*["']?0*(\d+)["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val kindleEmbedRegex = Regex("""<img\b[^>]*?kindle:embed:([0-9A-Va-v]{1,8})[^>]*>""", RegexOption.IGNORE_CASE)
    private val svgImageRegex = Regex("""<image\b[^>]*?kindle:embed:([0-9A-Va-v]{1,8})[^>]*>""", RegexOption.IGNORE_CASE)

    fun replaceImageReferences(html: String, book: MobiBook): String {
        fun marker(recordOffset: Int?): String {
            val idx = recordOffset?.let { book.imageIndexByRecordOffset[it] } ?: return ""
            return "\n[IMAGE:$idx]\n"
        }
        var out = recIndexRegex.replace(html) { marker(it.groupValues[1].toIntOrNull()) }
        out = kindleEmbedRegex.replace(out) { marker(it.groupValues[1].toIntOrNull(32)) }
        out = svgImageRegex.replace(out) { marker(it.groupValues[1].toIntOrNull(32)) }
        return out
    }
}
