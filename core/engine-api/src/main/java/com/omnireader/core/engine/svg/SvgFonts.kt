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
 * Portions ported and adapted from resvg, usvg, ttf-parser, fontdb, tiny-skia, and kurbo
 * (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors, and
 * Copyright (c) 2011 Google Inc. (Skia / tiny-skia, BSD-3-Clause),
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

internal class SvgFontAxis(val tag: String, val min: Float, val def: Float, val max: Float)

internal class SvgFontFace(
    val id: Int,
    val file: File?,
    val buffer: ByteBuffer,
    val index: Int,
    val families: List<String>,
    val style: Int,
    val weight: Int,
    val stretch: Int,
    val upem: Int,
    val ascent: Int,
    val descent: Int,
    val xHeight: Int,
    val underlinePosition: Int,
    val underlineThickness: Int,
    val lineThroughPosition: Int,
    val subscriptOffset: Int,
    val superscriptOffset: Int,
    val axes: List<SvgFontAxis>,
    val isColor: Boolean,
    private val cmap: SvgCmap?,
    private val svgTable: Int
) {
    val hasSvg get() = svgTable >= 0

    private val svgGlyphs = ConcurrentHashMap<Int, java.util.Optional<RNode>>()

    fun svgGlyph(id: Int): RNode? = svgGlyphs.getOrPut(id) { java.util.Optional.ofNullable(loadSvgGlyph(id)) }.orElse(null)

    private fun loadSvgGlyph(id: Int): RNode? = try {
        val b = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        val list = svgTable + b.getInt(svgTable + 2)
        val n = b.getShort(list).toInt() and 0xFFFF
        var found: RNode? = null
        for (i in 0 until n) {
            val r = list + 2 + i * 12
            val s = b.getShort(r).toInt() and 0xFFFF
            val e = b.getShort(r + 2).toInt() and 0xFFFF
            if (id < s || id > e) continue
            val off = list + b.getInt(r + 4)
            val len = b.getInt(r + 8)
            val bytes = ByteArray(len)
            b.position(off)
            b.get(bytes)
            val data = if (len > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
            val tree = SvgConverter.convert(SvgDoc.parse(String(data, Charsets.UTF_8)), SvgOptions()) ?: break
            found = if (s == e) tree.root else findNode(tree.root, "glyph$id")
            break
        }
        found
    } catch (e: Exception) { null }

    private fun findNode(g: RGroup, id: String): RNode? {
        for (c in g.children) {
            if (c.id == id) return c
            if (c is RGroup) findNode(c, id)?.let { return it }
        }
        return null
    }

    fun hasChar(cp: Int): Boolean = cmap?.glyph(cp)?.let { it != 0 } == true

    fun scale(size: Float) = size / upem

    private val fonts = ConcurrentHashMap<String, Font>()

    fun font(variations: List<Pair<String, Float>>): Font? {
        val vs = variations.filter { v -> axes.any { it.tag == v.first } }.associateBy { it.first }.values.toList()
        val key = vs.joinToString(",") { "'${it.first}' ${it.second}" }
        fonts[key]?.let { return it }
        val f = try {
            val b = if (file != null) Font.Builder(file) else Font.Builder(buffer.duplicate().also { it.position(0) })
            b.setTtcIndex(index)
            b.setWeight(weight.coerceIn(1, 1000))
            b.setSlant(if (style == SvgFontDb.STYLE_NORMAL) FontStyle.FONT_SLANT_UPRIGHT else FontStyle.FONT_SLANT_ITALIC)
            if (key.isNotEmpty()) b.setFontVariationSettings(key)
            b.build()
        } catch (e: Exception) { null } ?: return null
        fonts[key] = f
        return f
    }

    companion object {
        private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF
        private fun i16(b: ByteBuffer, o: Int) = b.getShort(o).toInt()
        private fun u32(b: ByteBuffer, o: Int) = b.getInt(o).toLong() and 0xFFFFFFFFL

        fun faceCount(b: ByteBuffer): Int {
            if (b.limit() < 12) return 0
            val tag = b.getInt(0)
            if (tag == 0x74746366) return u32(b, 8).toInt().coerceIn(0, 4096)
            return 1
        }

        fun parse(id: Int, file: File?, src: ByteBuffer, index: Int): SvgFontFace? = try {
            val b = src.duplicate().order(ByteOrder.BIG_ENDIAN)
            val base = if (b.getInt(0) == 0x74746366) u32(b, 12 + 4 * index).toInt() else 0
            val num = u16(b, base + 4)
            val tables = HashMap<String, Pair<Int, Int>>()
            for (i in 0 until num) {
                val r = base + 12 + i * 16
                val t = String(byteArrayOf(b.get(r), b.get(r + 1), b.get(r + 2), b.get(r + 3)), Charsets.ISO_8859_1)
                val off = u32(b, r + 8).toInt(); val len = u32(b, r + 12).toInt()
                if (off >= 0 && len >= 0 && off.toLong() + len <= b.limit()) tables[t] = off to len
            }
            build(id, file, src, index, b, tables)
        } catch (e: RuntimeException) { null }

        private fun build(id: Int, file: File?, src: ByteBuffer, index: Int, b: ByteBuffer, tables: Map<String, Pair<Int, Int>>): SvgFontFace? {
            val families = names(b, tables["name"]) ?: return null
            val head = tables["head"]
            val upem = if (head != null && head.second >= 20) u16(b, head.first + 18) else 0
            var style = SvgFontDb.STYLE_NORMAL; var weight = 400; var stretch = 5
            var typo: IntArray? = null; var useTypo = false; var win: IntArray? = null
            var xh: Int? = null; var strike: Int? = null; var sub: Int? = null; var sup: Int? = null
            tables["OS/2"]?.let { (o, len) ->
                if (len < 68) return@let
                val ver = u16(b, o)
                weight = u16(b, o + 4)
                stretch = u16(b, o + 6).let { if (it in 1..9) it else 5 }
                val fs = u16(b, o + 62)
                style = if (fs and 1 != 0) SvgFontDb.STYLE_ITALIC else if (ver >= 4 && fs and (1 shl 9) != 0) SvgFontDb.STYLE_OBLIQUE else SvgFontDb.STYLE_NORMAL
                sub = i16(b, o + 16); sup = i16(b, o + 24); strike = i16(b, o + 28)
                if (len >= 78) {
                    typo = intArrayOf(i16(b, o + 68), i16(b, o + 70))
                    win = intArrayOf(u16(b, o + 74), -u16(b, o + 76))
                    useTypo = fs and (1 shl 7) != 0
                }
                if (ver >= 2 && len >= 88) xh = i16(b, o + 86)
            }
            var italicPost = false
            var underline: IntArray? = null
            tables["post"]?.let { (o, len) ->
                if (len < 16) return@let
                italicPost = b.getInt(o + 4) != 0
                underline = intArrayOf(i16(b, o + 8), i16(b, o + 10))
            }
            if (style == SvgFontDb.STYLE_NORMAL && italicPost) style = SvgFontDb.STYLE_ITALIC
            val hhea = tables["hhea"]?.let { (o, len) -> if (len >= 10) intArrayOf(i16(b, o + 4), i16(b, o + 6)) else null }
            val line = when {
                typo != null && useTypo -> typo
                hhea != null && (hhea[0] != 0 || hhea[1] != 0) -> hhea
                typo != null && (typo!![0] != 0 || typo!![1] != 0) -> typo
                else -> win ?: hhea ?: intArrayOf(0, 0)
            }!!
            val ascent = line[0]; val descent = line[1]
            val xHeight = xh?.takeIf { it > 0 } ?: Math.round((ascent - descent) * 0.45f)
            val lineThrough = strike ?: (xHeight / 2)
            val ul = underline
            val ulPos = ul?.get(0) ?: (-upem / 9)
            val ulThick = ul?.get(1)?.takeIf { it > 0 } ?: (upem / 12)
            val subOff = sub ?: Math.round(upem / 0.2f)
            val supOff = sup ?: Math.round(upem / 0.4f)
            val axes = ArrayList<SvgFontAxis>()
            tables["fvar"]?.let { (o, len) ->
                if (len < 16) return@let
                val arr = o + u16(b, o + 4); val count = u16(b, o + 8); val size = u16(b, o + 10)
                for (i in 0 until count) {
                    val r = arr + i * size
                    if (r + 20 > o + len) break
                    val t = String(byteArrayOf(b.get(r), b.get(r + 1), b.get(r + 2), b.get(r + 3)), Charsets.ISO_8859_1)
                    axes.add(SvgFontAxis(t, b.getInt(r + 4) / 65536f, b.getInt(r + 8) / 65536f, b.getInt(r + 12) / 65536f))
                }
            }
            val isColor = tables.containsKey("COLR") || tables.containsKey("CBDT") || tables.containsKey("sbix") || tables.containsKey("SVG ")
            val cmap = tables["cmap"]?.let { SvgCmap.parse(b, it.first, it.second) }
            if (upem !in 16..16384) return null
            if (xHeight <= 0 || xHeight > 65535) return null
            return SvgFontFace(id, file, src, index, families, style, weight, stretch, upem, ascent, descent, xHeight, ulPos, ulThick, lineThrough, subOff, supOff, axes, isColor, cmap, tables["SVG "]?.first ?: -1)
        }

        private val MAC_ROMAN_HIGH = charArrayOf(
            'Ä', 'Å', 'Ç', 'É', 'Ñ', 'Ö', 'Ü', 'á', 'à', 'â', 'ä', 'ã', 'å', 'ç', 'é', 'è', 'ê', 'ë', 'í', 'ì', 'î', 'ï', 'ñ', 'ó', 'ò', 'ô', 'ö', 'õ', 'ú', 'ù', 'û', 'ü',
            '†', '°', '¢', '£', '§', '•', '¶', 'ß', '®', '©', '™', '´', '¨', '≠', 'Æ', 'Ø', '∞', '±', '≤', '≥', '¥', 'µ', '∂', '∑', '∏', 'π', '∫', 'ª', 'º', 'Ω', 'æ', 'ø',
            '¿', '¡', '¬', '√', 'ƒ', '≈', '∆', '«', '»', '…', ' ', 'À', 'Ã', 'Õ', 'Œ', 'œ', '–', '—', '“', '”', '‘', '’', '÷', '◊', 'ÿ', 'Ÿ', '⁄', '€', '‹', '›', 'ﬁ', 'ﬂ',
            '‡', '·', '‚', '„', '‰', 'Â', 'Ê', 'Á', 'Ë', 'È', 'Í', 'Î', 'Ï', 'Ì', 'Ó', 'Ô', '', 'Ò', 'Ú', 'Û', 'Ù', 'ı', 'ˆ', '˜', '¯', '˘', '˙', '˚', '¸', '˝', '˛', 'ˇ'
        )

        private class NameRec(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val off: Int, val len: Int)

        private fun isUnicode(p: Int, e: Int) = p == 0 || (p == 3 && (e == 0 || e == 1))
        private fun isMacRoman(p: Int, e: Int) = p == 1 && e == 0
        private fun isEnglishUs(r: NameRec) = (r.platform == 3 && r.language == 0x0409) || (r.platform == 1 && r.encoding == 0 && r.language == 0)

        private fun names(b: ByteBuffer, t: Pair<Int, Int>?): List<String>? {
            val (o, len) = t ?: return null
            if (len < 6) return null
            val count = u16(b, o + 2)
            val strOff = o + u16(b, o + 4)
            val recs = ArrayList<NameRec>()
            for (i in 0 until count) {
                val r = o + 6 + i * 12
                if (r + 12 > o + len) break
                recs.add(NameRec(u16(b, r), u16(b, r + 2), u16(b, r + 4), u16(b, r + 6), strOff + u16(b, r + 10), u16(b, r + 8)))
            }
            fun decode(r: NameRec): String? {
                if (r.off + r.len > b.limit()) return null
                return if (isUnicode(r.platform, r.encoding)) {
                    val sb = StringBuilder()
                    var i = 0
                    while (i + 1 < r.len) { sb.append(u16(b, r.off + i).toChar()); i += 2 }
                    sb.toString()
                } else if (isMacRoman(r.platform, r.encoding)) {
                    val sb = StringBuilder()
                    for (i in 0 until r.len) { val c = b.get(r.off + i).toInt() and 0xFF; sb.append(if (c < 128) c.toChar() else MAC_ROMAN_HIGH[c - 128]) }
                    sb.toString()
                } else null
            }
            fun collect(nameId: Int): MutableList<Pair<String, Boolean>> {
                val out = ArrayList<Pair<String, Boolean>>()
                for (r in recs) if (r.nameId == nameId && isUnicode(r.platform, r.encoding)) decode(r)?.let { out.add(it to isEnglishUs(r)) }
                if (out.none { it.second }) {
                    for (r in recs) if (r.nameId == nameId && isMacRoman(r.platform, r.encoding)) { decode(r)?.let { out.add(it to isEnglishUs(r)) }; break }
                }
                return out
            }
            var fams = collect(16)
            if (fams.isEmpty()) fams = collect(1)
            if (fams.size > 1) {
                val i = fams.indexOfFirst { it.second }
                if (i > 0) { val t0 = fams[0]; fams[0] = fams[i]; fams[i] = t0 }
            }
            if (fams.isEmpty()) return null
            if (recs.none { it.nameId == 6 && (isUnicode(it.platform, it.encoding) || isMacRoman(it.platform, it.encoding)) && decode(it) != null }) return null
            return fams.map { it.first }
        }
    }
}

internal class SvgCmap private constructor(private val b: ByteBuffer, private val off: Int, private val format: Int) {
    fun glyph(cp: Int): Int = try {
        when (format) {
            4 -> {
                if (cp > 0xFFFF) 0 else {
                    val segX2 = b.getShort(off + 6).toInt() and 0xFFFF
                    val ends = off + 14
                    val starts = ends + segX2 + 2
                    val deltas = starts + segX2
                    val ranges = deltas + segX2
                    var lo = 0; var hi = segX2 / 2 - 1; var res = 0
                    while (lo <= hi) {
                        val mid = (lo + hi) ushr 1
                        val end = b.getShort(ends + mid * 2).toInt() and 0xFFFF
                        if (end < cp) { lo = mid + 1; continue }
                        val start = b.getShort(starts + mid * 2).toInt() and 0xFFFF
                        if (start > cp) { hi = mid - 1; continue }
                        val delta = b.getShort(deltas + mid * 2).toInt()
                        val ro = b.getShort(ranges + mid * 2).toInt() and 0xFFFF
                        res = if (ro == 0) (cp + delta) and 0xFFFF else {
                            val g = b.getShort(ranges + mid * 2 + ro + (cp - start) * 2).toInt() and 0xFFFF
                            if (g == 0) 0 else (g + delta) and 0xFFFF
                        }
                        break
                    }
                    res
                }
            }
            12, 13 -> {
                val n = b.getInt(off + 12)
                var lo = 0; var hi = n - 1; var res = 0
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val r = off + 16 + mid * 12
                    val s = b.getInt(r); val e = b.getInt(r + 4)
                    if (e < cp) lo = mid + 1 else if (s > cp) hi = mid - 1 else { res = if (format == 12) b.getInt(r + 8) + (cp - s) else b.getInt(r + 8); break }
                }
                res
            }
            0 -> if (cp in 0..255) b.get(off + 6 + cp).toInt() and 0xFF else 0
            6 -> {
                val first = b.getShort(off + 6).toInt() and 0xFFFF
                val count = b.getShort(off + 8).toInt() and 0xFFFF
                if (cp >= first && cp < first + count) b.getShort(off + 10 + (cp - first) * 2).toInt() and 0xFFFF else 0
            }
            else -> 0
        }
    } catch (e: RuntimeException) { 0 }

    companion object {
        fun parse(b: ByteBuffer, o: Int, len: Int): SvgCmap? {
            if (len < 4) return null
            val n = b.getShort(o + 2).toInt() and 0xFFFF
            var best: SvgCmap? = null
            var bestRank = -1
            for (i in 0 until n) {
                val r = o + 4 + i * 8
                val p = b.getShort(r).toInt() and 0xFFFF
                val e = b.getShort(r + 2).toInt() and 0xFFFF
                val so = o + b.getInt(r + 4)
                val fmt = b.getShort(so).toInt() and 0xFFFF
                val rank = when {
                    (p == 3 && e == 10) || (p == 0 && (e == 4 || e == 6)) -> if (fmt == 12 || fmt == 13) 5 else 0
                    (p == 3 && e == 1) || (p == 0 && e <= 3) -> if (fmt == 4) 4 else if (fmt == 12) 5 else if (fmt == 6) 3 else 0
                    p == 3 && e == 0 -> if (fmt == 4) 2 else 0
                    p == 1 && e == 0 -> if (fmt == 0 || fmt == 6) 1 else 0
                    else -> 0
                }
                if (rank > bestRank && rank > 0) { bestRank = rank; best = SvgCmap(b, so, fmt) }
            }
            return best
        }
    }
}

internal class SvgFontDb(
    val faces: List<SvgFontFace>,
    val serif: String,
    val sansSerif: String,
    val cursive: String,
    val fantasy: String,
    val monospace: String,
    val defaultFamily: String,
    val aliases: Map<String, String> = emptyMap(),
    private val matchedFallback: Boolean = false
) {
    class Query(val families: List<String>, val weight: Int, val stretch: Int, val style: Int)

    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun familyName(f: String): String = when (f) {
        "serif" -> serif; "sans-serif" -> sansSerif; "cursive" -> cursive; "fantasy" -> fantasy; "monospace" -> monospace; else -> f
    }

    fun query(q: Query): SvgFontFace? {
        val list = ArrayList<String>()
        for (f in q.families) list.add(familyName(f))
        list.add(serif)
        for (name in list) {
            var candidates = faces.filter { f -> f.families.any { it == name } }
            if (candidates.isEmpty()) {
                val alias = aliases[name.lowercase()] ?: continue
                val an = familyName(alias)
                candidates = faces.filter { f -> f.families.any { it == an } }
                if (candidates.isEmpty()) continue
            }
            bestMatch(candidates, q)?.let { return it }
        }
        return null
    }

    private fun bestMatch(c: List<SvgFontFace>, q: Query): SvgFontFace? {
        var set = c
        val st = q.stretch
        val ms = if (set.any { it.stretch == st }) st else if (st <= 5) {
            set.filter { it.stretch < st }.minByOrNull { st - it.stretch }?.stretch ?: set.minByOrNull { it.stretch - st }!!.stretch
        } else {
            set.filter { it.stretch > st }.minByOrNull { it.stretch - st }?.stretch ?: set.minByOrNull { st - it.stretch }!!.stretch
        }
        set = set.filter { it.stretch == ms }
        val pref = when (q.style) { STYLE_ITALIC -> intArrayOf(STYLE_ITALIC, STYLE_OBLIQUE, STYLE_NORMAL); STYLE_OBLIQUE -> intArrayOf(STYLE_OBLIQUE, STYLE_ITALIC, STYLE_NORMAL); else -> intArrayOf(STYLE_NORMAL, STYLE_OBLIQUE, STYLE_ITALIC) }
        val mst = pref.firstOrNull { p -> set.any { it.style == p } } ?: return null
        set = set.filter { it.style == mst }
        val w = q.weight
        val mw = when {
            set.any { it.weight == w } -> w
            w in 400 until 450 && set.any { it.weight == 500 } -> 500
            w in 450..500 && set.any { it.weight == 400 } -> 400
            w <= 500 -> set.filter { it.weight <= w }.minByOrNull { w - it.weight }?.weight ?: set.minByOrNull { it.weight - w }!!.weight
            else -> set.filter { it.weight >= w }.minByOrNull { it.weight - w }?.weight ?: set.minByOrNull { w - it.weight }!!.weight
        }
        return set.firstOrNull { it.weight == mw }
    }

    fun fallback(cp: Int, exclude: List<SvgFontFace>): SvgFontFace? {
        val base = exclude.firstOrNull() ?: return null
        if (matchedFallback) {
            val cands = faces.filter { f -> exclude.none { it.id == f.id } && f.hasChar(cp) }
            val fam = cands.firstOrNull()?.families?.firstOrNull() ?: return null
            return bestMatch(cands.filter { it.families.firstOrNull() == fam }, Query(emptyList(), base.weight, base.stretch, base.style))
        }
        for (f in faces) {
            if (exclude.any { it.id == f.id }) continue
            if (base.style != f.style && base.weight != f.weight && base.stretch != f.stretch) continue
            if (!f.hasChar(cp)) continue
            return f
        }
        return null
    }

    fun typeface(chain: List<SvgFontFace>, variations: List<Pair<String, Float>>): Typeface? {
        val key = chain.joinToString(",") { it.id.toString() } + "|" + variations.joinToString(",") { "${it.first}=${it.second}" }
        typefaces[key]?.let { return it }
        val fams = chain.mapNotNull { f -> f.font(variations)?.let { FontFamily.Builder(it).build() } }
        if (fams.isEmpty()) return null
        val first = chain.first().font(variations) ?: return null
        val b = Typeface.CustomFallbackBuilder(fams[0])
        for (i in 1 until fams.size) b.addCustomFallback(fams[i])
        b.setStyle(first.style)
        val tf = b.build()
        typefaces[key] = tf
        return tf
    }

    companion object {
        const val STYLE_NORMAL = 0
        const val STYLE_ITALIC = 1
        const val STYLE_OBLIQUE = 2

        fun fromFiles(files: List<File>, serif: String, sansSerif: String, cursive: String, fantasy: String, monospace: String, defaultFamily: String): SvgFontDb {
            val faces = ArrayList<SvgFontFace>()
            for (f in files.sortedBy { it.path }) {
                val buf = try { java.io.RandomAccessFile(f, "r").use { r -> r.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, r.length()) } } catch (e: Exception) { continue }
                for (i in 0 until SvgFontFace.faceCount(buf)) SvgFontFace.parse(faces.size, f, buf, i)?.let { faces.add(it) }
            }
            return SvgFontDb(faces, serif, sansSerif, cursive, fantasy, monospace, defaultFamily)
        }

        private val SYSTEM_ALIASES = mapOf(
            "arial" to "sans-serif", "helvetica" to "sans-serif", "helvetica neue" to "sans-serif", "verdana" to "sans-serif", "tahoma" to "sans-serif",
            "segoe ui" to "sans-serif", "open sans" to "sans-serif", "dejavu sans" to "sans-serif", "liberation sans" to "sans-serif", "calibri" to "sans-serif",
            "trebuchet ms" to "sans-serif", "lucida grande" to "sans-serif", "system-ui" to "sans-serif", "ui-sans-serif" to "sans-serif",
            "times" to "serif", "times new roman" to "serif", "georgia" to "serif", "dejavu serif" to "serif", "liberation serif" to "serif", "cambria" to "serif",
            "garamond" to "serif", "palatino" to "serif", "book antiqua" to "serif", "ui-serif" to "serif",
            "courier" to "monospace", "courier new" to "monospace", "consolas" to "monospace", "menlo" to "monospace", "monaco" to "monospace",
            "dejavu sans mono" to "monospace", "liberation mono" to "monospace", "lucida console" to "monospace", "ui-monospace" to "monospace",
            "comic sans ms" to "cursive", "brush script mt" to "cursive", "impact" to "sans-serif"
        )

        val system: SvgFontDb by lazy {
            val faces = ArrayList<SvgFontFace>()
            val seen = HashSet<String>()
            val fonts = try { SystemFonts.getAvailableFonts().toList() } catch (e: Exception) { emptyList() }
            for (font in fonts.sortedBy { it.file?.path ?: "" }) {
                val key = (font.file?.path ?: font.buffer.hashCode().toString()) + "#" + font.ttcIndex
                if (!seen.add(key)) continue
                val buf = try { font.buffer } catch (e: Exception) { continue }
                SvgFontFace.parse(faces.size, font.file, buf, font.ttcIndex)?.let { faces.add(it) }
            }
            fun pick(vararg names: String): String = names.firstOrNull { n -> faces.any { f -> f.families.any { it == n } } } ?: names.first()
            val sans = pick("Roboto", "Google Sans", "Noto Sans", "Droid Sans")
            val serif = pick("Noto Serif", "Droid Serif", "Times New Roman")
            SvgFontDb(
                faces, serif, sans, pick("Dancing Script", "Yellowtail", sans), pick("Sedgwick Ave Display", "Coming Soon", sans),
                pick("Droid Sans Mono", "Noto Mono", "Roboto Mono", "Cutive Mono"), "Times New Roman", SYSTEM_ALIASES, true
            )
        }
    }
}
