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
 * Portions ported and adapted from resvg, usvg, unicode-vo, fontdb, tiny-skia, and kurbo
 * (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors,
 * Copyright (c) 2011 Google Inc. (Skia / tiny-skia, BSD-3-Clause), and
 * Copyright (c) 2018 The Kurbo Authors / Raph Levien (Apache-2.0 OR MIT),
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT; Unicode UAX #50 under Unicode-3.0).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.os.Build
import kotlin.math.abs

internal class SvgText(private val db: SvgFontDb) : SvgTextConverter {
    override fun convert(node: SvgNode, state: SvgConverter.State, conv: SvgConverter, parent: RGroup) {
        val b = Builder(db, conv, state)
        val text = b.collect(node, parent) ?: return
        val r = b.layout(text) ?: return
        parent.children.add(r)
    }

    companion object {
        private const val SHAPE_SIZE = 256f
        private const val MAX_EXACT_RUN = 256
    }

    private data class FontSpec(val families: List<String>, val style: Int, val stretch: Int, val weight: Int, val variations: List<Pair<String, Float>>)

    private class Deco(val fill: RFill?, val stroke: RStroke?)

    private class Shift(val kind: Int, val value: Float)

    private class Span(
        var start: Int, var end: Int, val fill: RFill?, val stroke: RStroke?, val strokeFirst: Boolean, val font: FontSpec, val fontSize: Float,
        val smallCaps: Boolean, val kerning: Boolean, val opticalAuto: Boolean, val underline: Deco?, val overline: Deco?, val lineThrough: Deco?,
        val visible: Boolean, val dominant: String, val alignment: String, val shift: List<Shift>, val letterSpacing: Float, val wordSpacing: Float,
        val textLength: Float?, val spacingAndGlyphs: Boolean
    ) {
        fun copy(s: Int, e: Int) = Span(s, e, fill, stroke, strokeFirst, font, fontSize, smallCaps, kerning, opticalAuto, underline, overline, lineThrough, visible, dominant, alignment, shift, letterSpacing, wordSpacing, textLength, spacingAndGlyphs)
        fun contains(i: Int) = i >= start && i < end
    }

    private class Flow(val startOffset: Float, val path: SPath)

    private class Chunk(val x: Float?, val y: Float?, val anchor: Int, val spans: MutableList<Span>, val flow: Flow?, val text: StringBuilder)

    private class TextData(
        val id: String, val antiAlias: Boolean, val dx: FloatArray, val dy: FloatArray, val rotate: FloatArray, val vertical: Boolean,
        val chunks: List<Chunk>, val absTransform: Ts
    )

    private class Sh(val idx: Int, var len: Int, var unitAdv: Float, val face: SvgFontFace, val runStart: Int, val runEnd: Int, val rtl: Boolean, val paint: Paint) {
        var ids: IntArray? = null
        var pos: FloatArray? = null
        var faces: Array<SvgFontFace>? = null
        var fonts: Array<Font>? = null
    }

    private class Cluster(
        val idx: Int, val cp: Int, var width: Float, var advance: Float, var tls: Float, var ascent: Float, var descent: Float,
        var hasRelShift: Boolean, var ts: Ts, var pathTs: Ts, var visible: Boolean, var glyphs: List<Pair<Sh, Float>>
    ) {
        val height get() = ascent - descent
        fun transform(): Ts = ts.pre(pathTs)
    }

    private class Pos(var x: Float? = null, var y: Float? = null, var dx: Float? = null, var dy: Float? = null)

    private class Builder(val db: SvgFontDb, val conv: SvgConverter, val state: SvgConverter.State) {
        private var charsCount = 0
        private var splitChunk = false
        private var flow: Flow? = null
        private val chunks = ArrayList<Chunk>()
        private lateinit var pos: Array<Pos>
        private val resolved = HashMap<FontSpec, SvgFontFace?>()

        fun collect(node: SvgNode, parent: RGroup): TextData? {
            pos = positions(node)
            val rotate = rotateList(node)
            val vertical = node.ancestors().firstOrNull { it.hasAttr("writing-mode") }?.attr("writing-mode").let { it == "tb" || it == "tb-rl" || it == "vertical-rl" || it == "vertical-lr" }
            collectImpl(node)
            val tr = node.findAttr("text-rendering")
            val aa = tr != "optimizeSpeed"
            val id = if (!state.insideMarker) node.elementId else ""
            return TextData(id, aa, FloatArray(pos.size) { pos[it].dx ?: 0f }, FloatArray(pos.size) { pos[it].dy ?: 0f }, rotate, vertical, chunks, parent.absTransform)
        }

        private fun countChars(n: SvgNode): Int = n.descendants().filter { it.isText }.sumOf { it.textContent().let { t -> t.codePointCount(0, t.length) } }

        private fun positions(text: SvgNode): Array<Pos> {
            val list = Array(countChars(text)) { Pos() }
            var offset = 0
            for (child in text.descendants()) {
                if (child.isText) { val t = child.textContent(); offset += t.codePointCount(0, t.length); continue }
                if (child.tag != "text" && child.tag != "tspan") continue
                val cc = countChars(child)
                fun push(aid: String, set: (Pos, Float) -> Unit) {
                    val v = child.attr(aid) ?: return
                    val l = SvgSyntax.lengthListPrefix(v).map { conv.convertLength(it, child, aid, RPaint.UNITS_USER, state) }
                    for (i in 0 until minOf(l.size, cc)) set(list[offset + i], l[i])
                }
                push("x") { p, v -> p.x = v }
                push("y") { p, v -> p.y = v }
                push("dx") { p, v -> p.dx = v }
                push("dy") { p, v -> p.dy = v }
            }
            return list
        }

        private fun rotateList(text: SvgNode): FloatArray {
            val list = FloatArray(countChars(text))
            var last = 0f
            var offset = 0
            for (child in text.descendants()) {
                if (child.isText) { val t = child.textContent(); offset += t.codePointCount(0, t.length); continue }
                if (!child.isElement) continue
                val r = child.attr("rotate")?.let { SvgSyntax.numberList(it) } ?: continue
                for (i in 0 until countChars(child)) {
                    if (i < r.size) { list[offset + i] = r[i].toFloat(); last = r[i].toFloat() } else list[offset + i] = last
                }
            }
            return list
        }

        private fun collectImpl(parent: SvgNode) {
            for (child in parent.children) {
                if (child.isElement) {
                    if (child.tag == "textPath") {
                        if (parent.tag != "text") { charsCount += countChars(child); continue }
                        val f = resolveFlow(child)
                        if (f == null) { charsCount += countChars(child); continue }
                        flow = f
                        splitChunk = true
                    }
                    collectImpl(child)
                    flow = null
                    if (child.tag == "textPath") splitChunk = true
                    continue
                }
                if (!child.isText) continue
                val text = child.textContent()
                val n = text.codePointCount(0, text.length)
                if (!conv.isVisibleElement(parent)) { charsCount += n; continue }
                val anchor = when (parent.findAttr("text-anchor")) { "middle" -> 1; "end" -> 2; else -> 0 }
                val fontSize = conv.resolveFontSize(parent, state)
                if (!(fontSize > 0f) || !fontSize.isFinite()) { charsCount += n; continue }
                val span = makeSpan(parent, fontSize)
                var newSpan = true
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    val cl = Character.charCount(cp)
                    val p = pos[charsCount]
                    val newChunk = p.x != null || p.y != null || splitChunk || chunks.isEmpty()
                    splitChunk = false
                    if (newChunk) {
                        chunks.add(Chunk(p.x, p.y, anchor, mutableListOf(span.copy(0, cl)), flow, StringBuilder().appendCodePoint(cp)))
                    } else if (newSpan) {
                        val c = chunks.last()
                        val s = c.text.length
                        c.text.appendCodePoint(cp)
                        c.spans.add(span.copy(s, s + cl))
                    } else {
                        val c = chunks.last()
                        c.text.appendCodePoint(cp)
                        c.spans.last().end += cl
                    }
                    newSpan = false
                    charsCount++
                    i += cl
                }
            }
        }

        private fun makeSpan(parent: SvgNode, fontSize: Float): Span {
            val order = parent.findAttr("paint-order")?.let { SvgSyntax.paintOrder(it) } ?: intArrayOf(0, 1, 2)
            val strokeFirst = order[0] == 1 || (order[0] == 2 && order[1] == 1)
            var dominant = parent.findAttr("dominant-baseline") ?: "auto"
            if (dominant == "no-change") dominant = parent.parentElement()?.findAttr("dominant-baseline") ?: "auto"
            var kerning = true
            if (conv.resolveLength(parent, "kerning", state, -1f) == 0f) kerning = false
            else if (parent.findAttr("font-kerning") == "none") kerning = false
            val optical = parent.findAttr("font-optical-sizing") != "none"
            val tl = conv.tryLength(parent, "textLength", RPaint.UNITS_USER, state)?.takeIf { it >= 0f }
            val visible = (parent.findAttr("visibility") ?: "visible") == "visible"
            return Span(
                0, 0, conv.resolveFill(parent, true, state), conv.resolveStroke(parent, true, state), strokeFirst, font(parent), fontSize,
                parent.findAttr("font-variant") == "small-caps", kerning, optical,
                decoration(parent, "underline"), decoration(parent, "overline"), decoration(parent, "line-through"),
                visible, dominant, parent.findAttr("alignment-baseline") ?: "auto", baselineShift(parent),
                conv.resolveLength(parent, "letter-spacing", state, 0f), conv.resolveLength(parent, "word-spacing", state, 0f),
                tl, parent.findAttr("lengthAdjust") == "spacingAndGlyphs"
            )
        }

        private fun decoration(tspan: SvgNode, value: String): Deco? {
            fun has(n: SvgNode) = n.attr("text-decoration")?.split(' ')?.any { it == value } == true
            if (tspan.ancestors().none { has(it) }) return null
            var node: SvgNode? = null
            for (n in tspan.ancestors()) if (has(n) || n.tag == "text") { node = n; break }
            return Deco(node?.let { conv.resolveFill(it, true, state) }, node?.let { conv.resolveStroke(it, true, state) })
        }

        private fun baselineShift(node: SvgNode): List<Shift> {
            val out = ArrayList<Shift>()
            for (n in node.ancestors().takeWhile { it.tag != "text" }) {
                val raw = n.tryAttr("baseline-shift")
                val len = raw?.let { SvgSyntax.length(it) }
                if (len != null) {
                    if (len.unit == SvgLength.PERCENT) out.add(Shift(3, conv.resolveFontSize(n, state) * (len.number.toFloat() / 100f)))
                    else out.add(Shift(3, conv.convertLength(len, n, "baseline-shift", RPaint.UNITS_BBOX, state)))
                } else {
                    when (n.attr("baseline-shift")) {
                        null -> {}
                        "sub" -> out.add(Shift(1, 0f))
                        "super" -> out.add(Shift(2, 0f))
                        else -> out.add(Shift(0, 0f))
                    }
                }
            }
            if (out.all { it.kind == 0 }) out.clear()
            return out
        }

        private fun font(node: SvgNode): FontSpec {
            val style = when (node.findAttr("font-style")) { "italic" -> SvgFontDb.STYLE_ITALIC; "oblique" -> SvgFontDb.STYLE_OBLIQUE; else -> SvgFontDb.STYLE_NORMAL }
            val stretch = when (node.ancestors().firstOrNull { it.hasAttr("font-stretch") }?.attr("font-stretch") ?: "") {
                "narrower", "condensed" -> 3; "ultra-condensed" -> 1; "extra-condensed" -> 2; "semi-condensed" -> 4
                "semi-expanded" -> 6; "wider", "expanded" -> 7; "extra-expanded" -> 8; "ultra-expanded" -> 9; else -> 5
            }
            var weight = 400
            for (n in node.ancestors().toList().reversed().drop(1)) {
                weight = when (val v = n.attr("font-weight") ?: "") {
                    "normal" -> 400; "bold" -> 700
                    "100", "200", "300", "400", "500", "600", "700", "800", "900" -> v.toInt()
                    "bolder" -> (weight + if (weight == 400) 300 else 100).coerceIn(100, 900)
                    "lighter" -> (weight - if (weight == 400) 200 else 100).coerceIn(100, 900)
                    else -> weight
                }
            }
            val vars = variationSettings(node)
            if (vars.none { it.first == "wght" }) vars.add("wght" to weight.toFloat())
            if (vars.none { it.first == "wdth" }) {
                val w = when (stretch) { 1 -> 50f; 2 -> 62.5f; 3 -> 75f; 4 -> 87.5f; 6 -> 112.5f; 7 -> 125f; 8 -> 150f; 9 -> 200f; else -> 100f }
                if (w != 100f) vars.add("wdth" to w)
            }
            if (vars.none { it.first == "ital" } && style == SvgFontDb.STYLE_ITALIC) vars.add("ital" to 1f)
            if (vars.none { it.first == "slnt" } && style == SvgFontDb.STYLE_OBLIQUE) vars.add("slnt" to -12f)
            val ff = node.ancestors().firstOrNull { it.hasAttr("font-family") }?.attr("font-family") ?: ""
            val fams = (SvgSyntax.fontFamilies(ff) ?: emptyList()).toMutableList()
            if (fams.isEmpty()) fams.add(db.defaultFamily)
            return FontSpec(fams, style, stretch, weight, vars)
        }

        private fun variationSettings(node: SvgNode): MutableList<Pair<String, Float>> {
            val out = ArrayList<Pair<String, Float>>()
            val n = node.ancestors().firstOrNull { it.hasAttr("font-variation-settings") } ?: return out
            val value = n.attr("font-variation-settings") ?: ""
            if (value.equals("normal", true) || value.isEmpty()) return out
            for (part0 in value.split(',')) {
                val part = part0.trim()
                if (part.isEmpty()) continue
                val q = part[0]
                if (q != '"' && q != '\'') continue
                val close = part.indexOf(q, 1)
                val tag = if (close < 0) part.substring(1) else part.substring(1, close)
                if (tag.toByteArray(Charsets.UTF_8).size != 4) continue
                val rest = if (close < 0) "" else part.substring(close + 1).trim()
                val v = rest.toFloatOrNull() ?: continue
                out.add(tag to v)
            }
            return out
        }

        private fun resolveFlow(node: SvgNode): Flow? {
            var path: SPath? = null
            node.attr("path")?.let { d -> path = SPathBuilder.fromSegments(SvgSyntax.pathData(d)) }
            if (path == null) {
                val linked = node.nodeAttr("href") ?: return null
                var p = conv.convertShape(linked, state) ?: return null
                val ts = conv.resolveTransform(linked, "transform", state)
                if (!ts.isIdentity) p = p.transform(ts)
                if (linked.elementId.isEmpty()) return null
                path = p
            }
            val pp = path ?: return null
            val so = node.attr("startOffset")?.let { SvgSyntax.length(it) } ?: SvgLength.ZERO
            val start = if (so.unit == SvgLength.PERCENT) (SvgArc.pathLength(pp) * (so.number / 100.0)).toFloat() else conv.resolveLength(node, "startOffset", state, 0f)
            return Flow(start, pp)
        }

        private fun resolve(spec: FontSpec): SvgFontFace? = resolved.getOrPut(spec) { db.query(SvgFontDb.Query(spec.families, spec.weight, spec.stretch, spec.style)) }

        private fun isIgnorable(cp: Int): Boolean = Character.getType(cp) == Character.FORMAT.toInt() || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x200B || Character.getType(cp) == Character.CONTROL.toInt()

        private fun covered(cp: Int, chain: List<SvgFontFace>) = isIgnorable(cp) || chain.any { it.hasChar(cp) }

        private fun shape(text: String, span: Span, main: SvgFontFace): List<Sh> {
            var chain = mutableListOf(main)
            val used = mutableListOf(main)
            val cps = text.codePoints().toArray()
            while (true) {
                val missing = cps.firstOrNull { !covered(it, chain) } ?: break
                val fb = db.fallback(missing, used) ?: break
                if (cps.all { isIgnorable(it) || fb.hasChar(it) }) { chain = mutableListOf(fb); break }
                chain.add(fb)
                used.add(fb)
            }
            val vars = ArrayList<Pair<String, Float>>()
            if (span.opticalAuto) vars.add("opsz" to span.fontSize)
            vars.addAll(span.font.variations)
            val tf = db.typeface(chain, vars) ?: return emptyList()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
            paint.hinting = Paint.HINTING_OFF
            paint.typeface = tf
            paint.textSize = SHAPE_SIZE
            val feats = ArrayList<String>()
            if (span.smallCaps) feats.add("'smcp' 1")
            if (!span.kerning) feats.add("'kern' 0")
            if (feats.isNotEmpty()) paint.fontFeatureSettings = feats.joinToString(", ")
            val chars = text.toCharArray()
            val out = ArrayList<Sh>()
            val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_LEFT_TO_RIGHT)
            val n = bidi.runCount
            val levels = ByteArray(n) { bidi.getRunLevel(it).toByte() }
            val order = Array<Any>(n) { it }
            java.text.Bidi.reorderVisually(levels, 0, order, 0, n)
            val bi = android.icu.text.BreakIterator.getCharacterInstance()
            bi.setText(text)
            for (o in order) {
                val r = o as Int
                val rs = bidi.getRunStart(r); val re = bidi.getRunLimit(r)
                if (re <= rs) continue
                val rtl = bidi.getRunLevel(r) and 1 == 1
                val adv = FloatArray(re - rs)
                try { paint.getTextRunAdvances(chars, rs, re - rs, rs, re - rs, rtl, adv, 0) } catch (e: RuntimeException) { continue }
                val logical = ArrayList<Sh>()
                var s = rs
                while (s < re) {
                    var e = bi.following(s)
                    if (e == android.icu.text.BreakIterator.DONE || e > re) e = re
                    if (e <= s) e = s + Character.charCount(text.codePointAt(s))
                    var a = 0f
                    for (k in s until e) a += adv[k - rs]
                    val prev = logical.lastOrNull()
                    if (a == 0f && prev != null && !isIgnorable(text.codePointAt(s))) {
                        prev.len = e - prev.idx
                    } else {
                        val cp = text.codePointAt(s)
                        val face = chain.firstOrNull { it.hasChar(cp) } ?: chain[0]
                        logical.add(Sh(s, e - s, a / SHAPE_SIZE, face, rs, re, rtl, paint))
                    }
                    s = e
                }
                if (rtl) logical.reverse()
                if (Build.VERSION.SDK_INT >= 31) assignGlyphs(chars, rs, re, rtl, paint, logical, SHAPE_SIZE, chain, vars)
                out.addAll(logical)
            }
            return out
        }

        private fun assignGlyphs(chars: CharArray, rs: Int, re: Int, rtl: Boolean, paint: Paint, visual: List<Sh>, fontSize: Float, chain: List<SvgFontFace>, vars: List<Pair<String, Float>>) {
            val byFont = HashMap<String, SvgFontFace>()
            for (f in chain) f.font(vars)?.let { byFont[fontKey(it)] = f }
            val whole = try { TextRunShaper.shapeTextRun(chars, rs, re - rs, rs, re - rs, 0f, 0f, rtl, paint) } catch (e: RuntimeException) { return }
            val total = whole.glyphCount()
            val counts = IntArray(visual.size)
            val exact = re - rs <= MAX_EXACT_RUN
            if (exact) for ((i, sh) in visual.withIndex()) counts[i] = try { TextRunShaper.shapeTextRun(chars, sh.idx, sh.len, rs, re - rs, 0f, 0f, rtl, paint).glyphCount() } catch (e: RuntimeException) { return }
            val owner = IntArray(total)
            if (exact && counts.sum() == total) {
                var g = 0
                for (i in visual.indices) repeat(counts[i]) { owner[g++] = i }
            } else {
                val pens = FloatArray(visual.size + 1)
                for ((i, sh) in visual.withIndex()) pens[i + 1] = pens[i] + sh.unitAdv * fontSize
                var cur = 0
                for (g in 0 until total) {
                    val x = whole.getGlyphX(g) + 0.01f
                    while (cur + 1 < visual.size && x >= pens[cur + 1]) cur++
                    owner[g] = cur
                }
            }
            var pen = 0f
            var g0 = 0
            for ((i, sh) in visual.withIndex()) {
                var g1 = g0
                while (g1 < total && owner[g1] == i) g1++
                val k = g1 - g0
                val ids = IntArray(k); val pos = FloatArray(2 * k)
                val fonts = arrayOfNulls<Font>(k)
                val faces = arrayOfNulls<SvgFontFace>(k)
                for (j in 0 until k) {
                    ids[j] = whole.getGlyphId(g0 + j)
                    pos[2 * j] = whole.getGlyphX(g0 + j) - pen
                    pos[2 * j + 1] = whole.getGlyphY(g0 + j)
                    val f = whole.getFont(g0 + j)
                    fonts[j] = f
                    faces[j] = byFont[fontKey(f)] ?: sh.face
                }
                sh.ids = ids
                sh.pos = pos
                @Suppress("UNCHECKED_CAST")
                sh.fonts = fonts as Array<Font>
                @Suppress("UNCHECKED_CAST")
                sh.faces = faces as Array<SvgFontFace>
                val origAdv = sh.unitAdv * fontSize
                if (k > 1 && sh.len > 2) {
                    val sub = String(chars, sh.idx, sh.len)
                    if (sub.indexOf('\u200D') >= 0 && sub.indexOf('\uFE0F') >= 0) {
                        val stripped = sub.replace("\uFE0F", "")
                        val sc = stripped.toCharArray()
                        val alt = try { TextRunShaper.shapeTextRun(sc, 0, sc.size, 0, sc.size, 0f, 0f, rtl, paint) } catch (_: RuntimeException) { null }
                        if (alt != null && alt.glyphCount() in 1 until k) {
                            val ak = alt.glyphCount()
                            val aIds = IntArray(ak)
                            val aPos = FloatArray(2 * ak)
                            val aFonts = arrayOfNulls<Font>(ak)
                            val aFaces = arrayOfNulls<SvgFontFace>(ak)
                            for (j in 0 until ak) {
                                aIds[j] = alt.getGlyphId(j)
                                aPos[2 * j] = alt.getGlyphX(j)
                                aPos[2 * j + 1] = alt.getGlyphY(j)
                                val af = alt.getFont(j)
                                aFonts[j] = af
                                aFaces[j] = byFont[fontKey(af)] ?: sh.face
                            }
                            sh.ids = aIds
                            sh.pos = aPos
                            @Suppress("UNCHECKED_CAST")
                            sh.fonts = aFonts as Array<Font>
                            @Suppress("UNCHECKED_CAST")
                            sh.faces = aFaces as Array<SvgFontFace>
                            sh.unitAdv = alt.advance / fontSize
                        }
                    }
                }
                pen += origAdv
                g0 = g1
            }
        }

        private fun fontKey(f: Font) = (f.file?.path ?: f.buffer.capacity().toString()) + "#" + f.ttcIndex

        private fun processChunk(chunk: Chunk): MutableList<Cluster> {
            val text = chunk.text.toString()
            var glyphs: MutableList<Sh>? = null
            val positions = HashSet<Int>()
            for (span in chunk.spans) {
                val face = resolve(span.font) ?: continue
                val tmp = shape(text, span, face)
                if (glyphs == null || glyphs.isEmpty()) { glyphs = tmp.toMutableList(); continue }
                positions.clear()
                val it = tmp.iterator()
                while (it.hasNext()) {
                    val ng = it.next()
                    if (!span.contains(ng.idx)) continue
                    val idx = glyphs.indexOfFirst { g -> g.idx == ng.idx }
                    if (idx < 0 || idx in positions) continue
                    positions.add(idx)
                    val prevLen = glyphs[idx].len
                    if (prevLen < ng.len) {
                        for (k in 1 until ng.len) if (idx + 1 < glyphs.size) glyphs.removeAt(idx + 1)
                    } else if (prevLen > ng.len) {
                        for (j in 1 until prevLen) if (it.hasNext()) glyphs.add(minOf(idx + j, glyphs.size), it.next())
                    }
                    glyphs[idx] = ng
                }
            }
            val out = ArrayList<Cluster>()
            val g = glyphs ?: return out
            var i = 0
            while (i < g.size) {
                val start = i
                val idx = g[i].idx
                while (i < g.size && g[i].idx == idx) i++
                val span = chunk.spans.firstOrNull { it.contains(idx) } ?: continue
                val fs = span.fontSize
                var x = 0f; var width = 0f; var advance = 0f
                val list = ArrayList<Pair<Sh, Float>>()
                for (k in start until i) {
                    val gl = g[k]
                    list.add(gl to x)
                    val w = gl.unitAdv * fs
                    x += w
                    advance += w
                    if (w > width) width = w
                }
                val f = g[start].face
                out.add(Cluster(idx, text.codePointAt(idx), width, advance, 0f, f.ascent * f.scale(fs), f.descent * f.scale(fs), false, Ts.IDENTITY, Ts.IDENTITY, true, list))
            }
            return out
        }

        private fun spanAt(chunk: Chunk, i: Int) = chunk.spans.firstOrNull { it.contains(i) }

        fun layout(t: TextData): RTextNode? {
            for (c in t.chunks) for (s in c.spans) resolve(s.font)
            val outSpans = ArrayList<RNode>()
            var charOffset = 0
            var lastX = 0f; var lastY = 0f
            var bl = Float.MAX_VALUE; var bt = Float.MAX_VALUE; var br = -Float.MAX_VALUE; var bb = -Float.MAX_VALUE
            fun expand(r: SRect?) { if (r == null) return; bl = minOf(bl, r.x); bt = minOf(bt, r.y); br = maxOf(br, r.right); bb = maxOf(bb, r.bottom) }
            for (chunk in t.chunks) {
                val (x, y) = if (chunk.flow == null) (chunk.x ?: lastX) to (chunk.y ?: lastY) else 0f to 0f
                val clusters = processChunk(chunk)
                val text = chunk.text.toString()
                if (clusters.isEmpty()) { charOffset += text.codePointCount(0, text.length); continue }
                applyWritingMode(t.vertical, clusters)
                applyLetterSpacing(chunk, clusters)
                applyWordSpacing(chunk, clusters)
                applyLengthAdjust(chunk, clusters)
                val cur = if (chunk.flow == null) positionsHorizontal(t, chunk, charOffset, clusters) else positionsPath(t, chunk, charOffset, chunk.flow, clusters)
                var textTs = Ts.IDENTITY
                if (t.vertical && chunk.flow == null) textTs = rotateAt(textTs, 90f, x, y)
                for (span in chunk.spans) {
                    val face = resolve(span.font) ?: continue
                    val decoSpans = decorationSpans(span, clusters)
                    var spanTs = textTs.preTranslate(x, y)
                    if (chunk.flow == null) spanTs = spanTs.preTranslate(0f, resolveBaseline(span, face, t.vertical))
                    val fs = span.fontSize
                    val sc = face.scale(fs)
                    var underline: RPathNode? = null; var overline: RPathNode? = null; var lineThrough: RPathNode? = null
                    span.underline?.let { d ->
                        val off = if (!t.vertical) -face.underlinePosition * sc else (face.ascent - face.descent) * sc / 2f
                        underline = decorationPath(off, span, face, d, decoSpans, spanTs, t.antiAlias)?.also { expand(it.data.bounds()) }
                    }
                    span.overline?.let { d ->
                        val off = if (!t.vertical) -face.ascent * sc else -(face.ascent - face.descent) * sc / 2f
                        overline = decorationPath(off, span, face, d, decoSpans, spanTs, t.antiAlias)?.also { expand(it.data.bounds()) }
                    }
                    span.lineThrough?.let { d ->
                        val off = if (!t.vertical) -face.lineThroughPosition * sc else 0f
                        lineThrough = decorationPath(off, span, face, d, decoSpans, spanTs, t.antiAlias)?.also { expand(it.data.bounds()) }
                    }
                    val fill = span.fill?.let { RFill(it.paint, it.opacity, false, it.context) }
                    val conv = convertSpan(span, clusters, spanTs) ?: continue
                    expand(conv.second)
                    overline?.let { outSpans.add(it) }
                    underline?.let { outSpans.add(it) }
                    val glyphs = ArrayList<RGlyph>()
                    var il = Float.MAX_VALUE; var it0 = Float.MAX_VALUE; var ir = -Float.MAX_VALUE; var ib = -Float.MAX_VALUE
                    val tmpPath = Path()
                    val rf = RectF()
                    val chars = text.toCharArray()
                    val svgGlyphs = ArrayList<RNode>()
                    fun addInk(r: SRect?) { if (r != null) { il = minOf(il, r.x); it0 = minOf(it0, r.y); ir = maxOf(ir, r.right); ib = maxOf(ib, r.bottom) } }
                    for (c in conv.first) {
                        val cts = spanTs.pre(c.transform())
                        for ((g, ox) in c.glyphs) {
                            val k = span.fontSize / SHAPE_SIZE
                            val gts = cts.preTranslate(ox, 0f).preScale(k, k)
                            val ids = g.ids
                            if (ids != null) {
                                for (j in ids.indices) {
                                    val face = g.faces!![j]
                                    val font = g.fonts!![j]
                                    val x = g.pos!![2 * j]; val y = g.pos!![2 * j + 1]
                                    val svgNode = if (face.hasSvg) face.svgGlyph(ids[j]) else null
                                    if (svgNode != null) {
                                        val sg = RGroup()
                                        sg.transform = gts.preTranslate(x, y).preScale(g.paint.textSize / face.upem, g.paint.textSize / face.upem)
                                        sg.absTransform = t.absTransform.pre(sg.transform)
                                        sg.children.add(svgNode)
                                        svgGlyphs.add(sg)
                                        addInk(sg.boundingBox()?.transform(sg.transform))
                                        continue
                                    }
                                    font.getGlyphBounds(ids[j], g.paint, rf)
                                    if (rf.isEmpty) continue
                                    addInk(SRect.ltrb(rf.left + x, rf.top + y, rf.right + x, rf.bottom + y)?.transform(gts))
                                    glyphs.add(RGlyph(gts, chars, g.idx, g.idx + g.len, g.runStart, g.runEnd, g.rtl, g.paint, face.isColor, ids[j], x, y, font, k))
                                }
                                continue
                            }
                            g.paint.getTextPath(chars, g.idx, g.len, 0f, 0f, tmpPath)
                            tmpPath.computeBounds(rf, true)
                            val gr = if (rf.isEmpty) {
                                if (!g.face.isColor) continue
                                SRect.nz(0f, -c.ascent, maxOf(c.advance, 1f), c.height)?.transform(cts)
                            } else SRect.ltrb(rf.left, rf.top, rf.right, rf.bottom)?.transform(gts)
                            addInk(gr)
                            glyphs.add(RGlyph(gts, chars, g.idx, g.idx + g.len, g.runStart, g.runEnd, g.rtl, g.paint, g.face.isColor, -1, 0f, 0f, null, k))
                        }
                    }
                    val ink = SRect.ltrb(il, it0, ir, ib)
                    if (glyphs.isNotEmpty() && ink != null) {
                        val stroke = span.stroke?.copy()
                        outSpans.add(RGlyphRun(span.visible, fill, stroke, span.strokeFirst, t.antiAlias, glyphs, ink, t.absTransform))
                    }
                    outSpans.addAll(svgGlyphs)
                    lineThrough?.let { outSpans.add(it) }
                }
                charOffset += text.codePointCount(0, text.length)
                var cx = cur[0]; var cy = cur[1]
                if (t.vertical && chunk.flow == null) { val tmp = cx; cx = cy; cy = tmp }
                lastX = x + cx
                lastY = y + cy
            }
            val bbox = SRect.ltrb(bl, bt, br, bb)?.takeIf { it.nonZero } ?: return null
            val g = RGroup()
            g.id = t.id
            g.absTransform = t.absTransform
            g.children.addAll(outSpans)
            g.strokeBoundingBox()?.takeIf { it.nonZero } ?: return null
            return RTextNode(t.id, bbox, g, t.absTransform)
        }

        private fun convertSpan(span: Span, clusters: List<Cluster>, ts: Ts): Pair<List<Cluster>, SRect>? {
            val out = ArrayList<Cluster>()
            var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var any = false
            for (c in clusters) {
                if (!c.visible) continue
                if (span.contains(c.idx)) out.add(c)
                var adv = c.advance
                if (adv <= 0f) adv = 1f
                val rect = SRect.nz(0f, -c.ascent, adv, c.height) ?: continue
                val pts = rectPoints(rect, ts.pre(c.transform()))
                for (k in 0 until 4) { l = minOf(l, pts[2 * k]); r = maxOf(r, pts[2 * k]); tp = minOf(tp, pts[2 * k + 1]); b = maxOf(b, pts[2 * k + 1]) }
                any = true
            }
            if (!any) return null
            val bbox = SRect.ltrb(l, tp, r, b)?.takeIf { it.nonZero } ?: return null
            return out to bbox
        }

        private fun rectPoints(r: SRect, ts: Ts): FloatArray {
            val a = ts.map(r.x, r.y); val b = ts.map(r.right, r.y); val c = ts.map(r.right, r.bottom); val d = ts.map(r.x, r.bottom)
            return floatArrayOf(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
        }

        private class DecoSpan(val width: Float, val ts: Ts)

        private fun decorationSpans(span: Span, clusters: List<Cluster>): List<DecoSpan> {
            val out = ArrayList<DecoSpan>()
            var started = false
            var width = 0f
            var ts = Ts.IDENTITY
            for (c in clusters) {
                if (span.contains(c.idx)) {
                    if (started && c.hasRelShift) { started = false; out.add(DecoSpan(width, ts)) }
                    if (!started) { width = c.advance; started = true; ts = c.ts } else width += c.advance
                } else if (started) { out.add(DecoSpan(width, ts)); started = false }
            }
            if (started) out.add(DecoSpan(width, ts))
            return out
        }

        private fun decorationPath(dy: Float, span: Span, face: SvgFontFace, d: Deco, spans: List<DecoSpan>, ts: Ts, aa: Boolean): RPathNode? {
            val th = face.underlineThickness * face.scale(span.fontSize)
            val b = SPathBuilder()
            for (ds in spans) {
                val rect = SRect.nz(0f, -th / 2f, ds.width, th) ?: continue
                val p = rectPoints(rect, ts.pre(ds.ts.preTranslate(0f, dy)))
                b.moveTo(p[0], p[1]); b.lineTo(p[2], p[3]); b.lineTo(p[4], p[5]); b.lineTo(p[6], p[7]); b.close()
            }
            val path = b.finish() ?: return null
            return RPathNode("", span.visible, d.fill?.copy(), d.stroke?.copy(), false, aa, path, Ts.IDENTITY)
        }

        private fun relativeShifts(t: TextData, chunk: Chunk, offset: Int, clusters: List<Cluster>): Pair<Array<FloatArray>, List<Int>> {
            val text = chunk.text
            val logical = clusters.indices.sortedBy { clusters[it].idx }
            val shifts = Array(clusters.size) { FloatArray(2) }
            var sx = 0f; var sy = 0f
            for (i in logical) {
                val cp = offset + Character.codePointCount(text, 0, clusters[i].idx)
                sx += t.dx.getOrElse(cp) { 0f }
                sy += t.dy.getOrElse(cp) { 0f }
                shifts[i][0] = sx; shifts[i][1] = sy
            }
            return shifts to logical
        }

        private fun approxZero(v: Float) = abs(v) <= 4 * Math.ulp(0f) || v == 0f

        private fun positionsHorizontal(t: TextData, chunk: Chunk, offset: Int, clusters: MutableList<Cluster>): FloatArray {
            val (shifts, logical) = relativeShifts(t, chunk, offset, clusters)
            val first = logical.first(); val last = logical.last()
            if (t.vertical) for (s in shifts) { val a = s[0]; s[0] = s[1]; s[1] = -a }
            for (i in 1 until clusters.size) if (shifts[i][0] != shifts[i - 1][0] || shifts[i][1] != shifts[i - 1][1]) clusters[i].hasRelShift = true
            var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
            var advance = 0f
            for ((i, c) in clusters.withIndex()) {
                val start = advance + shifts[i][0]
                val end = start + c.advance - c.tls
                left = minOf(left, start, end); right = maxOf(right, start, end)
                advance += c.advance
            }
            var x = shifts[first][0] - left + anchor(chunk.anchor, right - left)
            for ((i, c) in clusters.withIndex()) {
                val cp = offset + Character.codePointCount(chunk.text, 0, c.idx)
                c.ts = c.ts.preTranslate(x + shifts[i][0], shifts[i][1])
                val angle = t.rotate.getOrNull(cp)
                if (angle != null && !approxZero(angle)) { c.ts = c.ts.pre(Ts.rotate(angle)); c.hasRelShift = true }
                x += c.advance
            }
            return floatArrayOf(x + shifts[last][0], shifts[last][1])
        }

        private fun anchor(a: Int, w: Float) = when (a) { 1 -> -w / 2f; 2 -> -w; else -> 0f }

        private fun resolveBaseline(span: Span, face: SvgFontFace, vertical: Boolean): Float {
            val fs = span.fontSize
            val sc = face.scale(fs)
            var shift = 0f
            for (s in span.shift.reversed()) when (s.kind) {
                1 -> shift -= face.subscriptOffset * sc
                2 -> shift += face.superscriptOffset * sc
                3 -> shift += s.value
            }
            var r = -shift
            if (!vertical) {
                r += if (span.alignment == "auto" || span.alignment == "baseline") {
                    alignmentShift(dominantToAlignment(span.dominant), face, fs)
                } else alignmentShift(span.alignment, face, fs)
            }
            return r
        }

        private fun dominantToAlignment(d: String) = when (d) {
            "ideographic", "alphabetic", "hanging", "mathematical", "central", "middle", "text-after-edge", "text-before-edge" -> d
            else -> "auto"
        }

        private fun alignmentShift(a: String, face: SvgFontFace, fs: Float): Float {
            val sc = face.scale(fs)
            val asc = face.ascent * sc; val desc = face.descent * sc
            return when (a) {
                "before-edge", "text-before-edge" -> asc
                "middle" -> face.xHeight * sc * 0.5f
                "central" -> asc - (asc - desc) * 0.5f
                "after-edge", "text-after-edge" -> desc
                "ideographic" -> desc
                "hanging" -> asc * 0.8f
                "mathematical" -> asc * 0.5f
                else -> 0f
            }
        }

        private fun positionsPath(t: TextData, chunk: Chunk, offset: Int, flow: Flow, clusters: MutableList<Cluster>): FloatArray {
            var lastX = 0f; var lastY = 0f
            val (shifts, _) = relativeShifts(t, chunk, offset, clusters)
            val chunkOffset = if (!t.vertical) chunk.x ?: 0f else chunk.y ?: 0f
            val startOffset = chunkOffset + flow.startOffset + anchor(chunk.anchor, clusters.sumOf { it.advance.toDouble() }.toFloat())
            val normals = SvgArc.normals(clusters.map { it.width }, clusters.map { it.advance }, shifts.map { it[0] }, flow.path, startOffset)
            for ((i, c) in clusters.withIndex()) {
                val nm = normals[i]
                if (nm == null) { c.visible = false; continue }
                c.hasRelShift = true
                val orig = c.ts
                val hw = c.width / 2f
                var ts = Ts.translate(nm[0] - hw, nm[1])
                ts = rotateAt(ts, nm[2], hw, 0f)
                val cp = offset + Character.codePointCount(chunk.text, 0, c.idx)
                val bs = spanAt(chunk, c.idx)?.let { s -> resolve(s.font)?.let { -resolveBaseline(s, it, t.vertical) } ?: 0f } ?: 0f
                val dy = shifts[i][1]
                if (!approxZero(dy) || !approxZero(bs)) ts = ts.preTranslate(0f, dy - bs)
                val angle = t.rotate.getOrNull(cp)
                if (angle != null && !approxZero(angle)) ts = ts.pre(Ts.rotate(angle))
                c.ts = ts.pre(orig)
                lastX = nm[0] + c.advance
                lastY = nm[1]
            }
            return floatArrayOf(lastX, lastY)
        }

        private fun applyLengthAdjust(chunk: Chunk, clusters: MutableList<Cluster>) {
            val horizontal = chunk.flow == null
            for (span in chunk.spans) {
                val target = span.textLength ?: continue
                val idxs = ArrayList<Int>()
                for (i in span.start until span.end) { val k = clusters.indexOfFirst { it.idx == i }; if (k >= 0) idxs.add(k) }
                val list = idxs.sorted().distinct()
                if (list.isEmpty()) continue
                var width = 0f
                for (i in list) width += clusters[i].width
                if (!span.spacingAndGlyphs) {
                    val factor = if (list.size > 1) (target - width) / (list.size - 1) else 0f
                    for (i in list) { clusters[i].advance = clusters[i].width + factor; clusters[i].tls = factor }
                } else {
                    val factor = target / width
                    if (factor < 0.001f) continue
                    for (i in list) {
                        clusters[i].ts = clusters[i].ts.preScale(factor, 1f)
                        if (!horizontal) { clusters[i].advance *= factor; clusters[i].width *= factor }
                    }
                }
            }
        }

        private fun applyWritingMode(vertical: Boolean, clusters: MutableList<Cluster>) {
            if (!vertical) return
            for (c in clusters) {
                if (SvgVerticalOrientation.isUpright(c.cp)) {
                    var ts = Ts.IDENTITY.preTranslate(0f, (c.ascent + c.descent) / 2f)
                    ts = rotateAt(ts, -90f, c.width / 2f, -(c.ascent + c.descent) / 2f)
                    c.pathTs = ts
                    c.ascent = c.width / 2f
                    c.descent = -c.width / 2f
                } else {
                    c.ts = c.ts.preTranslate(0f, (c.ascent + c.descent) / 2f)
                }
            }
        }

        private fun applyLetterSpacing(chunk: Chunk, clusters: MutableList<Cluster>) {
            if (chunk.spans.none { !approxZero(it.letterSpacing) }) return
            val n = clusters.size
            for ((i, c) in clusters.withIndex()) {
                if (!supportsLetterSpacing(c.cp)) continue
                val span = spanAt(chunk, c.idx) ?: continue
                if (i != n - 1) c.advance += span.letterSpacing
                if (!(c.advance > 0f && c.advance.isFinite())) { c.width = 0f; c.advance = 0f; c.glyphs = emptyList() }
            }
        }

        private fun applyWordSpacing(chunk: Chunk, clusters: MutableList<Cluster>) {
            if (chunk.spans.none { !approxZero(it.wordSpacing) }) return
            for (c in clusters) {
                if (c.cp == 0x20 || c.cp == 0xA0 || c.cp == 0x1361 || c.cp == 0x10100 || c.cp == 0x10101 || c.cp == 0x1039F || c.cp == 0x1091F) {
                    spanAt(chunk, c.idx)?.let { c.advance += it.wordSpacing }
                }
            }
        }

        private fun supportsLetterSpacing(cp: Int): Boolean {
            val s = try { Character.UnicodeScript.of(cp) } catch (e: IllegalArgumentException) { return true }
            return when (s) {
                Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO, Character.UnicodeScript.MANICHAEAN,
                Character.UnicodeScript.PSALTER_PAHLAVI, Character.UnicodeScript.MANDAIC, Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.PHAGS_PA,
                Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.MODI,
                Character.UnicodeScript.SHARADA, Character.UnicodeScript.SYLOTI_NAGRI, Character.UnicodeScript.TIRHUTA, Character.UnicodeScript.OGHAM -> false
                else -> true
            }
        }

        private fun rotateAt(ts: Ts, deg: Float, x: Float, y: Float): Ts = ts.preTranslate(x, y).pre(Ts.rotate(deg)).preTranslate(-x, -y)
    }
}

internal object SvgArc {
    private class Cubic(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x3: Double, val y3: Double) {
        fun eval(t: Double): DoubleArray {
            val mt = 1 - t
            val a = mt * mt * mt; val b = 3 * mt * mt * t; val c = 3 * mt * t * t; val d = t * t * t
            return doubleArrayOf(a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3)
        }
        fun deriv(t: Double): DoubleArray {
            val mt = 1 - t
            val a = 3 * mt * mt; val b = 6 * mt * t; val c = 3 * t * t
            return doubleArrayOf(a * (x1 - x0) + b * (x2 - x1) + c * (x3 - x2), a * (y1 - y0) + b * (y2 - y1) + c * (y3 - y2))
        }
        fun speed(t: Double): Double { val d = deriv(t); return Math.hypot(d[0], d[1]) }

        fun arclen(t0: Double, t1: Double): Double = adaptive(t0, t1, gauss(t0, t1), 0)

        private fun gauss(a: Double, b: Double): Double {
            val h = (b - a) / 2; val m = (a + b) / 2
            var s = 0.0
            for (i in GX.indices) s += GW[i] * speed(m + h * GX[i])
            return s * h
        }

        private fun adaptive(a: Double, b: Double, whole: Double, depth: Int): Double {
            val m = (a + b) / 2
            val l = gauss(a, m); val r = gauss(m, b)
            if (depth >= 18 || abs(l + r - whole) <= 1e-9 * maxOf(1.0, abs(whole))) return l + r
            return adaptive(a, m, l, depth + 1) + adaptive(m, b, r, depth + 1)
        }

        fun invArclen(target: Double, total: Double): Double {
            if (target <= 0) return 0.0
            if (target >= total) return 1.0
            var lo = 0.0; var hi = 1.0
            var t = target / total
            for (iter in 0 until 60) {
                val f = arclen(0.0, t) - target
                if (abs(f) <= 1e-10 * maxOf(1.0, total)) break
                if (f > 0) hi = t else lo = t
                val sp = speed(t)
                var nt = if (sp > 1e-12) t - f / sp else (lo + hi) / 2
                if (nt <= lo || nt >= hi || nt.isNaN()) nt = (lo + hi) / 2
                t = nt
            }
            return t
        }
    }

    private val GX = doubleArrayOf(-0.9324695142031521, -0.6612093864662645, -0.2386191860831969, 0.2386191860831969, 0.6612093864662645, 0.9324695142031521)
    private val GW = doubleArrayOf(0.1713244923791704, 0.3607615730481386, 0.4679139345726910, 0.4679139345726910, 0.3607615730481386, 0.1713244923791704)

    private fun curves(path: SPath): List<Cubic> {
        val out = ArrayList<Cubic>()
        val p = path.pts
        if (p.size < 2) return out
        var mx = p[0].toDouble(); var my = p[1].toDouble()
        var px = mx; var py = my
        var i = 0
        fun line(x: Double, y: Double): Cubic = Cubic(px, py, px + (x - px) * 0.33, py + (y - py) * 0.33, px + (x - px) * 0.66, py + (y - py) * 0.66, x, y)
        for (v in path.verbs) {
            val c: Cubic? = when (v) {
                SPath.MOVE -> { mx = p[i].toDouble(); my = p[i + 1].toDouble(); px = mx; py = my; i += 2; null }
                SPath.LINE -> { val c = line(p[i].toDouble(), p[i + 1].toDouble()); i += 2; c }
                SPath.QUAD -> {
                    val qx = p[i].toDouble(); val qy = p[i + 1].toDouble(); val x = p[i + 2].toDouble(); val y = p[i + 3].toDouble()
                    i += 4
                    Cubic(px, py, px + 2.0 / 3.0 * (qx - px), py + 2.0 / 3.0 * (qy - py), x + 2.0 / 3.0 * (qx - x), y + 2.0 / 3.0 * (qy - y), x, y)
                }
                SPath.CUBIC -> { val c = Cubic(px, py, p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble(), p[i + 3].toDouble(), p[i + 4].toDouble(), p[i + 5].toDouble()); i += 6; c }
                else -> line(mx, my)
            }
            if (c != null) { out.add(c); px = c.x3; py = c.y3 }
        }
        return out
    }

    fun pathLength(path: SPath): Double = curves(path).sumOf { it.arclen(0.0, 1.0) }

    fun normals(widths: List<Float>, advances: List<Float>, shiftsX: List<Float>, path: SPath, offset: Float): Array<FloatArray?> {
        val n = widths.size
        val offsets = DoubleArray(n)
        val normals = ArrayList<FloatArray?>()
        var advance = offset
        for (i in 0 until n) {
            val o = advance + shiftsX[i] + widths[i] / 2f
            if (o < 0f) normals.add(null)
            offsets[i] = o.toDouble()
            advance += advances[i]
        }
        var length = 0.0
        for (c in curves(path)) {
            val len = c.arclen(0.0, 1.0)
            var k = normals.size
            while (k < n) {
                val o = offsets[k]
                if (o >= length && o <= length + len) {
                    val t = c.invArclen(o - length, len).coerceIn(0.0, 1.0)
                    val p = c.eval(t)
                    val d = c.deriv(t)
                    val angle = Math.toDegrees(Math.atan2(d[0], -d[1])) - 90.0
                    normals.add(floatArrayOf(p[0].toFloat(), p[1].toFloat(), angle.toFloat()))
                    if (normals.size == n) break
                }
                k++
            }
            if (normals.size == n) break
            length += len
        }
        while (normals.size < n) normals.add(null)
        return normals.toTypedArray()
    }
}

internal object SvgVerticalOrientation {
    private val UPRIGHT = intArrayOf(
        0xA7, 0xA7, 0xA9, 0xA9, 0xAE, 0xAE, 0xB1, 0xB1, 0xBC, 0xBE, 0xD7, 0xD7,
        0xF7, 0xF7, 0x2EA, 0x2EB, 0x1100, 0x11FF, 0x1401, 0x167F, 0x18B0, 0x18FF, 0x2016, 0x2016,
        0x2020, 0x2021, 0x2030, 0x2031, 0x203B, 0x203C, 0x2042, 0x2042, 0x2047, 0x2049, 0x2051, 0x2051,
        0x2065, 0x2065, 0x20DD, 0x20E0, 0x20E2, 0x20E4, 0x2100, 0x2101, 0x2103, 0x2109, 0x210F, 0x210F,
        0x2113, 0x2114, 0x2116, 0x2117, 0x211E, 0x2123, 0x2125, 0x2125, 0x2127, 0x2127, 0x2129, 0x2129,
        0x212E, 0x212E, 0x2135, 0x213F, 0x2145, 0x214A, 0x214C, 0x214D, 0x214F, 0x2189, 0x218C, 0x218F,
        0x221E, 0x221E, 0x2234, 0x2235, 0x2300, 0x2307, 0x230C, 0x231F, 0x2324, 0x2328, 0x232B, 0x232B,
        0x237D, 0x239A, 0x23BE, 0x23CD, 0x23CF, 0x23CF, 0x23D1, 0x23DB, 0x23E2, 0x2422, 0x2424, 0x24FF,
        0x25A0, 0x2619, 0x2620, 0x2767, 0x2776, 0x2793, 0x2B12, 0x2B2F, 0x2B50, 0x2B59, 0x2BB8, 0x2BEB,
        0x2BF0, 0x2BFF, 0x2E80, 0x3000, 0x3003, 0x3007, 0x3012, 0x3013, 0x3020, 0x302F, 0x3031, 0x3040,
        0x3042, 0x3042, 0x3044, 0x3044, 0x3046, 0x3046, 0x3048, 0x3048, 0x304A, 0x3062, 0x3064, 0x3082,
        0x3084, 0x3084, 0x3086, 0x3086, 0x3088, 0x308D, 0x308F, 0x3094, 0x3097, 0x309A, 0x309D, 0x309F,
        0x30A2, 0x30A2, 0x30A4, 0x30A4, 0x30A6, 0x30A6, 0x30A8, 0x30A8, 0x30AA, 0x30C2, 0x30C4, 0x30E2,
        0x30E4, 0x30E4, 0x30E6, 0x30E6, 0x30E8, 0x30ED, 0x30EF, 0x30F4, 0x30F7, 0x30FB, 0x30FD, 0x3126,
        0x3128, 0x31EF, 0x3200, 0x32FF, 0x3358, 0x337A, 0x3380, 0xA4CF, 0xA960, 0xA97F, 0xAC00, 0xD7FF,
        0xE000, 0xFAFF, 0xFE10, 0xFE1F, 0xFE30, 0xFE48, 0xFE53, 0xFE57, 0xFE5F, 0xFE62, 0xFE67, 0xFE6F,
        0xFF02, 0xFF07, 0xFF0A, 0xFF0B, 0xFF0F, 0xFF19, 0xFF20, 0xFF3A, 0xFF3C, 0xFF3C, 0xFF3E, 0xFF3E,
        0xFF40, 0xFF5A, 0xFFE0, 0xFFE2, 0xFFE4, 0xFFE7, 0xFFF0, 0xFFF8, 0xFFFC, 0xFFFD, 0x10980, 0x1099F,
        0x11580, 0x115FF, 0x13000, 0x1342F, 0x14400, 0x1467F, 0x16FE0, 0x18AFF, 0x1B000, 0x1B0FF, 0x1D000, 0x1D1FF,
        0x1D300, 0x1D37F, 0x1D800, 0x1DAAF, 0x1F000, 0x1F1FF, 0x1F202, 0x1F7FF, 0x1F900, 0x1F9FF, 0x20000, 0x2FFFD,
        0x30000, 0x3FFFD, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD
    )

    fun isUpright(cp: Int): Boolean {
        var lo = 0; var hi = UPRIGHT.size / 2 - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (cp < UPRIGHT[2 * m]) hi = m - 1 else if (cp > UPRIGHT[2 * m + 1]) lo = m + 1 else return true
        }
        return false
    }
}
