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

package me.hlebkukleuski.fullfilestudio.core.media


object MatroskaSubtitleExtractor {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_BCP47 = 0x22B59DL
    private const val ID_NAME = 0x536EL
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_COMP_ALGO = 0x4254L
    private const val ID_COMP_SETTINGS = 0x4255L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    fun interface Reader {
        fun read(offset: Long, dst: ByteArray, dstOffset: Int, len: Int): Int
    }

    private class Track(val number: Long) {
        var codec = ""
        var language: String? = "eng"
        var name: String? = null
        var compAlgo = -1
        var compSettings: ByteArray? = null
        var isSubtitle = false
        val samples = ArrayList<Triple<Long, Long, ByteArray>>()
    }

    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3

    private class Cursor(private val reader: Reader, val size: Long) {
        private val buf = ByteArray(12)

        fun readId(pos: Long): Pair<Long, Int>? {
            if (pos >= size) return null
            if (reader.read(pos, buf, 0, 1) < 1) return null
            val first = buf[0].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..4) return null
            if (len > 1 && reader.read(pos, buf, 0, len) < len) return null
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (buf[i].toLong() and 0xFF)
            return v to len
        }

        fun readSize(pos: Long): Pair<Long, Int>? {
            if (reader.read(pos, buf, 0, 1) < 1) return null
            val first = buf[0].toInt() and 0xFF
            if (first == 0) return null
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..8) return null
            if (len > 1 && reader.read(pos, buf, 0, len) < len) return null
            var v = (first and (0xFF ushr len)).toLong()
            var allOnes = v == (0xFF ushr len).toLong()
            for (i in 1 until len) {
                val b = buf[i].toLong() and 0xFF
                if (b != 0xFFL) allOnes = false
                v = (v shl 8) or b
            }
            return (if (allOnes) -1L else v) to len
        }

        fun bytes(pos: Long, len: Int): ByteArray? {
            if (len < 0 || len > 64 * 1024 * 1024) return null
            val out = ByteArray(len)
            var done = 0
            while (done < len) {
                val r = reader.read(pos + done, out, done, len - done)
                if (r <= 0) return null
                done += r
            }
            return out
        }
    }

    private fun uint(b: ByteArray): Long {
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    fun read(reader: Reader, size: Long, maxBlocks: Int = 2_000_000): List<EmbeddedSubtitle> {
        val c = Cursor(reader, size)
        val head = c.bytes(0, minOf(4L, size).toInt()) ?: return emptyList()
        if (!isMatroska(head)) return emptyList()
        var timecodeScale = 1_000_000L
        val tracks = HashMap<Long, Track>()
        var clusterTc = 0L
        var pos = 0L
        var end = size
        var blocks = 0
        while (pos < end && blocks < maxBlocks) {
            val (id, idLen) = c.readId(pos) ?: break
            val (sz, szLen) = c.readSize(pos + idLen) ?: break
            val data = pos + idLen + szLen
            val elemEnd = if (sz < 0) end else minOf(end, data + sz)
            when (id) {
                ID_EBML -> pos = elemEnd
                ID_SEGMENT -> {
                    end = elemEnd
                    pos = data
                }
                ID_INFO -> {
                    var p = data
                    while (p < elemEnd) {
                        val (cid, cl) = c.readId(p) ?: break
                        val (cs, csl) = c.readSize(p + cl) ?: break
                        if (cs < 0) break
                        if (cid == ID_TIMECODE_SCALE) c.bytes(p + cl + csl, cs.toInt())?.let { timecodeScale = uint(it).takeIf { v -> v > 0 } ?: timecodeScale }
                        p += cl + csl + cs
                    }
                    pos = elemEnd
                }
                ID_TRACKS -> {
                    parseTracks(c, data, elemEnd, tracks)
                    if (tracks.values.none { it.isSubtitle }) return emptyList()
                    pos = elemEnd
                }
                ID_CLUSTER -> {
                    if (tracks.values.none { it.isSubtitle }) return emptyList()
                    pos = data
                }
                ID_CLUSTER_TIMECODE -> {
                    c.bytes(data, sz.toInt())?.let { clusterTc = uint(it) }
                    pos = elemEnd
                }
                ID_SIMPLE_BLOCK -> {
                    blocks++
                    handleBlock(c, data, sz, clusterTc, -1L, timecodeScale, tracks)
                    pos = elemEnd
                }
                ID_BLOCK_GROUP -> {
                    blocks++
                    var blockPos = -1L
                    var blockSize = 0L
                    var duration = -1L
                    var p = data
                    while (p < elemEnd) {
                        val (cid, cl) = c.readId(p) ?: break
                        val (cs, csl) = c.readSize(p + cl) ?: break
                        if (cs < 0) break
                        when (cid) {
                            ID_BLOCK -> {
                                blockPos = p + cl + csl
                                blockSize = cs
                            }
                            ID_BLOCK_DURATION -> c.bytes(p + cl + csl, cs.toInt())?.let { duration = uint(it) }
                        }
                        p += cl + csl + cs
                    }
                    if (blockPos >= 0) handleBlock(c, blockPos, blockSize, clusterTc, duration, timecodeScale, tracks)
                    pos = elemEnd
                }
                else -> {
                    if (sz < 0) break
                    pos = elemEnd
                }
            }
        }
        val out = ArrayList<EmbeddedSubtitle>()
        var n = 0
        for (t in tracks.values.sortedBy { it.number }) {
            if (!t.isSubtitle) continue
            n++
            val mime = when {
                t.codec.startsWith("S_TEXT/ASS") || t.codec.startsWith("S_TEXT/SSA") || t.codec == "S_ASS" || t.codec == "S_SSA" -> "text/x-ssa"
                t.codec.startsWith("S_TEXT/WEBVTT") -> "text/plain"
                else -> "application/x-subrip"
            }
            val track = SubtitleTrackParser.fromTimedSamples(mime, t.samples) ?: continue
            out.add(EmbeddedSubtitle(t.name?.takeIf { it.isNotBlank() } ?: "#$n", t.language, track))
        }
        return out
    }

    private fun parseTracks(c: Cursor, start: Long, end: Long, tracks: HashMap<Long, Track>) {
        var p = start
        while (p < end) {
            val (id, il) = c.readId(p) ?: return
            val (sz, sl) = c.readSize(p + il) ?: return
            if (sz < 0) return
            if (id == ID_TRACK_ENTRY) {
                val t = parseTrackEntry(c, p + il + sl, p + il + sl + sz)
                if (t != null) tracks[t.number] = t
            }
            p += il + sl + sz
        }
    }

    private fun parseTrackEntry(c: Cursor, start: Long, end: Long): Track? {
        var number = -1L
        var type = -1L
        var codec = ""
        var lang: String? = "eng"
        var bcp: String? = null
        var name: String? = null
        var algo = -1
        var settings: ByteArray? = null
        fun walk(s: Long, e: Long) {
            var p = s
            while (p < e) {
                val (id, il) = c.readId(p) ?: return
                val (sz, sl) = c.readSize(p + il) ?: return
                if (sz < 0) return
                val d = p + il + sl
                when (id) {
                    ID_TRACK_NUMBER -> c.bytes(d, sz.toInt())?.let { number = uint(it) }
                    ID_TRACK_TYPE -> c.bytes(d, sz.toInt())?.let { type = uint(it) }
                    ID_CODEC_ID -> c.bytes(d, sz.toInt())?.let { codec = String(it, Charsets.US_ASCII).trimEnd('\u0000') }
                    ID_LANGUAGE -> c.bytes(d, sz.toInt())?.let { lang = String(it, Charsets.US_ASCII).trimEnd('\u0000') }
                    ID_LANGUAGE_BCP47 -> c.bytes(d, sz.toInt())?.let { bcp = String(it, Charsets.US_ASCII).trimEnd('\u0000') }
                    ID_NAME -> c.bytes(d, sz.toInt())?.let { name = String(it, Charsets.UTF_8).trimEnd('\u0000') }
                    ID_CONTENT_ENCODINGS, ID_CONTENT_ENCODING, ID_CONTENT_COMPRESSION -> {
                        if (id == ID_CONTENT_COMPRESSION && algo < 0) algo = 0
                        walk(d, d + sz)
                    }
                    ID_COMP_ALGO -> c.bytes(d, sz.toInt())?.let { algo = uint(it).toInt() }
                    ID_COMP_SETTINGS -> settings = c.bytes(d, sz.toInt())
                }
                p = d + sz
            }
        }
        walk(start, end)
        if (number < 0) return null
        val t = Track(number)
        t.codec = codec
        t.isSubtitle = type == 0x11L && codec.startsWith("S_TEXT") || codec == "S_ASS" || codec == "S_SSA"
        t.language = (bcp ?: lang)?.takeIf { it.isNotBlank() && it != "und" }
        t.name = name
        t.compAlgo = algo
        t.compSettings = settings
        return t
    }

    private fun handleBlock(c: Cursor, data: Long, size: Long, clusterTc: Long, duration: Long, scale: Long, tracks: HashMap<Long, Track>) {
        if (size < 4) return
        val head = c.bytes(data, minOf(size, 12L).toInt()) ?: return
        val first = head[0].toInt() and 0xFF
        val vlen = Integer.numberOfLeadingZeros(first) - 23
        if (vlen !in 1..8 || vlen + 3 > head.size) return
        var num = (first and (0xFF ushr vlen)).toLong()
        for (i in 1 until vlen) num = (num shl 8) or (head[i].toLong() and 0xFF)
        val t = tracks[num] ?: return
        if (!t.isSubtitle) return
        val rel = ((head[vlen].toInt() shl 8) or (head[vlen + 1].toInt() and 0xFF)).toShort().toLong()
        val flags = head[vlen + 2].toInt() and 0xFF
        if (flags and 0x06 != 0) return
        val payloadOff = vlen + 3
        var payload = c.bytes(data + payloadOff, (size - payloadOff).toInt()) ?: return
        when (t.compAlgo) {
            0 -> payload = inflate(payload) ?: return
            3 -> t.compSettings?.let { payload = it + payload }
        }
        val startUs = (clusterTc + rel) * scale / 1000L
        val durUs = if (duration >= 0) duration * scale / 1000L else -1L
        t.samples.add(Triple(startUs, durUs, payload))
    }

    private fun inflate(b: ByteArray): ByteArray? = try {
        val inf = java.util.zip.Inflater()
        inf.setInput(b)
        val out = java.io.ByteArrayOutputStream(b.size * 3)
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}
