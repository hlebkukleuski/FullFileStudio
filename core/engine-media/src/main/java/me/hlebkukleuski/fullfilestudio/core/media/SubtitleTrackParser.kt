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

import java.util.Locale

class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

class SubtitleTrack(val cues: List<SubtitleCue>) {
    private val starts = LongArray(cues.size) { cues[it].startMs }

    fun textAt(positionMs: Long): String? {
        var lo = 0
        var hi = starts.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        val active = ArrayList<String>()
        var k = idx
        while (k >= 0 && idx - k < 16) {
            val c = cues[k]
            if (c.startMs <= positionMs && positionMs < c.endMs) active.add(0, c.text)
            k--
        }
        return if (active.isEmpty()) null else active.joinToString("\n")
    }
}

class EmbeddedSubtitle(val label: String, val language: String?, val track: SubtitleTrack)

object SubtitleTrackParser {
    private const val MAX_CUES = 50_000

    fun fromSamples(mime: String, samples: List<Pair<Long, ByteArray>>, durationMs: Long = 0L): SubtitleTrack? =
        fromTimedSamples(mime, samples.map { Triple(it.first, -1L, it.second) }, durationMs)

    fun fromTimedSamples(mime: String, samples: List<Triple<Long, Long, ByteArray>>, durationMs: Long = 0L): SubtitleTrack? {
        val m = mime.lowercase(Locale.ROOT)
        val tx3g = m.contains("3gpp")
        val sorted = samples.sortedBy { it.first }
        val texts = sorted.map { (us, _, data) -> (us / 1000L) to sampleText(m, data) }
        val out = ArrayList<SubtitleCue>()
        for ((i, e) in texts.withIndex()) {
            val text = e.second ?: continue
            if (text.isEmpty()) continue
            val start = e.first
            val next = texts.getOrNull(i + 1)?.first
            val estimate = (1200L + 70L * text.length).coerceIn(2000L, 8000L)
            val known = sorted[i].second
            val end = when {
                known > 0L -> start + known / 1000L
                tx3g && next != null -> next
                tx3g -> if (durationMs > start) minOf(durationMs, start + 10_000L) else start + estimate
                next != null -> minOf(next, start + estimate)
                else -> start + estimate
            }
            if (end > start) out.add(SubtitleCue(start, end, text))
            if (out.size >= MAX_CUES) break
        }
        return if (out.isEmpty()) null else SubtitleTrack(out)
    }

    private fun sampleText(mime: String, data: ByteArray): String? {
        if (data.isEmpty()) return ""
        return when {
            mime.contains("3gpp") -> {
                if (data.size < 2) return ""
                val len = ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)).coerceAtMost(data.size - 2)
                if (len <= 0) return ""
                val utf16 = len >= 2 && (data[2].toInt() and 0xFF) == 0xFE && (data[3].toInt() and 0xFF) == 0xFF
                val raw = if (utf16) String(data, 4, len - 2, Charsets.UTF_16BE) else String(data, 2, len, Charsets.UTF_8)
                clean(raw)
            }
            mime.contains("vtt") && isVttBox(data) -> {
                val sb = StringBuilder()
                walkBoxes(data, 0, data.size) { type, off, size ->
                    if (type == "vttc") walkBoxes(data, off, off + size) { t2, o2, s2 ->
                        if (t2 == "payl") {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(String(data, o2, s2, Charsets.UTF_8))
                        }
                    }
                }
                clean(sb.toString())
            }
            mime.contains("ssa") || mime.contains("ass") -> {
                val raw = String(data, Charsets.UTF_8)
                val parts = raw.split(',', limit = 9)
                val body = if (parts.size == 9 && parts[0].trim().all { it.isDigit() }) parts[8] else raw
                clean(body.replace(Regex("""\{[^}]*\}"""), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
            }
            else -> clean(decode(data))
        }
    }

    private fun isVttBox(d: ByteArray): Boolean {
        if (d.size < 8) return false
        val t = String(d, 4, 4, Charsets.ISO_8859_1)
        return t == "vttc" || t == "vtte" || t == "vtta"
    }

    private fun walkBoxes(d: ByteArray, from: Int, to: Int, visit: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            val size = ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
            if (size < 8 || p + size > to) return
            visit(String(d, p + 4, 4, Charsets.ISO_8859_1), p + 8, size - 8)
            p += size
        }
    }

    fun decode(bytes: ByteArray): String {
        val (cs, skip) = try {
            me.hlebkukleuski.fullfilestudio.core.security.TextEncodingDetector.detectCharsetAndValidateText(bytes, minOf(bytes.size, 65536))
        } catch (_: Throwable) {
            Charsets.UTF_8 to 0
        }
        return String(bytes, skip, (bytes.size - skip).coerceAtLeast(0), cs)
    }

    fun parse(bytes: ByteArray, nameHint: String? = null): SubtitleTrack? {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val ext = nameHint?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val head = text.trimStart('﻿', ' ', '\n').take(4096)
        val cues = when {
            head.startsWith("WEBVTT") || ext == "vtt" -> parseTimed(text)
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[Events]", ignoreCase = true) || ext == "ass" || ext == "ssa" -> parseAss(text)
            Regex("""(?m)^\{\d+\}\{\d*\}""").containsMatchIn(head) || ext == "sub" && head.startsWith("{") -> parseMicroDvd(text)
            Regex("""(?m)^\d+:\d{2}:\d{2}\.\d{3},\d+:\d{2}:\d{2}\.\d{3}\s*$""").containsMatchIn(head) || ext == "sbv" -> parseSbv(text)
            Regex("""(?m)^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""").containsMatchIn(head) || ext == "lrc" -> parseLrc(text)
            else -> parseTimed(text)
        }
        if (cues.isEmpty()) return null
        return SubtitleTrack(cues.sortedBy { it.startMs })
    }

    private fun timeMs(s: String): Long? {
        val t = s.trim().replace(',', '.')
        val parts = t.split(':')
        if (parts.size !in 2..3) return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val secParts = parts.last().split('.')
        val sec = secParts[0].toLongOrNull() ?: return null
        val frac = secParts.getOrNull(1)?.let { f -> (f + "000").take(3).toLongOrNull() } ?: 0L
        return ((h * 60 + m) * 60 + sec) * 1000 + frac
    }

    private fun clean(s: String): String = s
        .replace(Regex("<[^>]{0,200}>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ")
        .lines().joinToString("\n") { it.trim() }
        .trim()

    private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")

    private fun parseTimed(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size && out.size < MAX_CUES) {
            val m = ARROW.find(lines[i])
            if (m == null) { i++; continue }
            val start = timeMs(m.groupValues[1])
            val end = timeMs(m.groupValues[2])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (ARROW.containsMatchIn(lines[i])) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i])
                i++
            }
            if (start != null && end != null && end > start) {
                val t = clean(sb.toString())
                if (t.isNotEmpty()) out.add(SubtitleCue(start, end, t))
            }
        }
        return out
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                continue
            }
            if (!line.startsWith("Dialogue:", true)) continue
            val fields = line.substringAfter(':').split(',', limit = format.size)
            if (fields.size < format.size) continue
            val start = timeMs(fields[format.indexOf("start").coerceAtLeast(0)]) ?: continue
            val end = timeMs(fields[format.indexOf("end").coerceAtLeast(0)]) ?: continue
            val body = fields[format.indexOf("text").let { if (it < 0) fields.size - 1 else it }]
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            val t = clean(body)
            if (t.isNotEmpty() && end > start) out.add(SubtitleCue(start, end, t))
            if (out.size >= MAX_CUES) break
        }
        return out
    }

    private fun parseSbv(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        val lines = text.split('\n')
        val re = Regex("""^(\d+:\d{2}:\d{2}\.\d{3}),(\d+:\d{2}:\d{2}\.\d{3})\s*$""")
        var i = 0
        while (i < lines.size && out.size < MAX_CUES) {
            val m = re.find(lines[i].trim())
            if (m == null) { i++; continue }
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i]); i++
            }
            val s = timeMs(m.groupValues[1]); val e = timeMs(m.groupValues[2])
            if (s != null && e != null && e > s && sb.isNotBlank()) out.add(SubtitleCue(s, e, clean(sb.toString())))
        }
        return out
    }

    private fun parseMicroDvd(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        var fps = 23.976
        val re = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""")
        for ((n, raw) in text.split('\n').withIndex()) {
            val m = re.find(raw.trim()) ?: continue
            val a = m.groupValues[1].toLong()
            val b = m.groupValues[2].toLongOrNull()
            val body = m.groupValues[3]
            if (n < 3 && a <= 1 && (b ?: 0) <= 1) {
                val f = body.trim().replace(',', '.').toDoubleOrNull()
                if (f != null && f in 5.0..120.0) { fps = f; continue }
            }
            val start = (a * 1000 / fps).toLong()
            val end = ((b ?: (a + (fps * 3).toLong())) * 1000 / fps).toLong()
            val t = clean(body.replace(Regex("""\{[^}]*\}"""), "").replace('|', '\n'))
            if (t.isNotEmpty() && end > start) out.add(SubtitleCue(start, end, t))
            if (out.size >= MAX_CUES) break
        }
        return out
    }

    private fun parseLrc(text: String): List<SubtitleCue> {
        val stamps = ArrayList<Pair<Long, String>>()
        var offset = 0L
        val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        for (raw in text.split('\n')) {
            val line = raw.trim()
            Regex("""^\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(line)?.let { offset = it.groupValues[1].toLong() }
            val times = tag.findAll(line).toList()
            if (times.isEmpty()) continue
            val body = clean(line.substring(times.last().range.last + 1).replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), ""))
            for (m in times) {
                val frac = m.groupValues[3].let { if (it.isEmpty()) 0L else (it + "00").take(3).toLong() }
                val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + frac - offset
                stamps.add(ms.coerceAtLeast(0L) to body)
            }
            if (stamps.size >= MAX_CUES) break
        }
        stamps.sortBy { it.first }
        val out = ArrayList<SubtitleCue>()
        for ((i, s) in stamps.withIndex()) {
            val end = stamps.getOrNull(i + 1)?.first ?: (s.first + 10_000L)
            if (s.second.isNotEmpty() && end > s.first) out.add(SubtitleCue(s.first, end, s.second))
        }
        return out
    }
}
