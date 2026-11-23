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

package com.omnireader.core.engine.word

import android.graphics.Paint
import android.graphics.Typeface
import com.omnireader.core.engine.impl.OfficeFonts
import java.util.concurrent.ConcurrentHashMap

internal class RunStyle(
    val paint: Paint, val color: Int, val underline: Int, val underlineColor: Int, val strike: Boolean, val dstrike: Boolean,
    val highlight: Int, val shading: Int?, val baselineShift: Float, val sizePt: Float, val border: WBorder?, val vmetrics: FloatArray? = null
)

internal sealed class LItem {
    class Text(val x: Float, val y: Float, val text: String, val style: RunStyle, val width: Float, val rtl: Boolean, val wordSpacing: Float, val synthetic: Boolean = false) : LItem()
    class Image(val x: Float, val y: Float, val w: Float, val h: Float, val image: WImage, val clipW: Float = 0f) : LItem()
    class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int) : LItem()
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val border: WBorder) : LItem()
    class PageField(val x: Float, val y: Float, val kind: Int, val style: RunStyle, val format: Int, val width: Float) : LItem()
    class Comment(val x: Float, val y: Float, val w: Float, val h: Float, val anchorX: Float, val anchorY: Float, val lines: List<String>) : LItem()
}

internal class LPage(val width: Float, val height: Float, val pageNumber: Int, val sectionIndex: Int, val pageFormat: Int) {
    val items = ArrayList<LItem>()
    val back = ArrayList<LItem>()
    val text = StringBuilder()
    var sectionPageCount = 0
}

internal object WordFonts {
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun typeface(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val key = "${name ?: ""}|$bold|$italic"
        cache[key]?.let { return it }
        val tf = try { OfficeFonts.resolve(name, bold, italic) } catch (e: Throwable) { Typeface.DEFAULT }
        cache[key] = tf
        return tf
    }

    fun clear() = cache.clear()
}

internal class WordLayout(private val doc: WDocument) {

    private val styles = HashMap<String, RunStyle>()
    val pages = ArrayList<LPage>()

    private class Seg(
        val text: String, val style: RunStyle, val kind: Int, val image: WImage?, val field: Int, val fieldFormat: Int,
        val footnote: Int, val endnote: Boolean, val anchor: WFloating?, val comment: Int = -1, val lid: Int = 0
    ) {
        var widths: FloatArray = FloatArray(0)
        val width get() = widths.sum()
    }

    private class LineOut(
        val items: ArrayList<LItem>, var height: Float, var ascent: Float, var descent: Float,
        val footnotes: List<Int>, val anchors: List<WFloating>, val pageBreak: Boolean, val columnBreak: Boolean,
        val comments: List<Pair<Int, Float>> = emptyList()
    )

    private class ParaOut(val lines: List<LineOut>, val before: Float, val after: Float, val props: ParaProps, val keepNext: Boolean, val keep: Boolean, val pageBreakBefore: Boolean, val border: Array<WBorder?>, val shading: Int?, val left: Float, val right: Float, val topLine: WBorder?, val bottomLine: WBorder?) {
        val topExtra get() = topLine?.let { it.space + it.widthPt } ?: 0f
        val bottomExtra get() = bottomLine?.let { it.space + it.widthPt } ?: 0f
        val height get() = lines.sumOf { it.height.toDouble() }.toFloat() + topExtra + bottomExtra
    }

    private fun sameBorders(a: ParaProps, b: ParaProps): Boolean {
        if (a.indLeft != b.indLeft || a.indRight != b.indRight) return false
        var any = false
        for (k in 0..3) {
            val x = a.borders.getOrNull(k)?.takeIf { it.visible }
            val y = b.borders.getOrNull(k)?.takeIf { it.visible }
            if ((x == null) != (y == null)) return false
            if (x != null && y != null) {
                if (x.type != y.type || x.width8 != y.width8 || x.color != y.color || x.space != y.space) return false
                any = true
            }
        }
        return any
    }

    private fun paraBorderItems(po: ParaOut, l: Float, r: Float, t: Float, b: Float, first: Boolean, last: Boolean, out: MutableList<LItem>) {
        if (b <= t) return
        val br = po.border
        val lx = l - (br[1]?.takeIf { it.visible }?.space ?: 0)
        val rx = r + (br[3]?.takeIf { it.visible }?.space ?: 0)
        if (first) po.topLine?.let { out.add(LItem.Line(lx, t + it.widthPt / 2f, rx, t + it.widthPt / 2f, it)) }
        if (last) po.bottomLine?.let { out.add(LItem.Line(lx, b - it.widthPt / 2f, rx, b - it.widthPt / 2f, it)) }
        br[1]?.takeIf { it.visible }?.let { out.add(LItem.Line(lx, t, lx, b, it)) }
        br[3]?.takeIf { it.visible }?.let { out.add(LItem.Line(rx, t, rx, b, it)) }
    }

    private fun hlColor(i: Int): Int = when (i) {
        1 -> 0xFF000000.toInt(); 2 -> 0xFF0000FF.toInt(); 3 -> 0xFF00FFFF.toInt(); 4 -> 0xFF00FF00.toInt(); 5 -> 0xFFFF00FF.toInt(); 6 -> 0xFFFF0000.toInt()
        7 -> 0xFFFFFF00.toInt(); 8 -> 0xFFFFFFFF.toInt(); 9 -> 0xFF000080.toInt(); 10 -> 0xFF008080.toInt(); 11 -> 0xFF008000.toInt(); 12 -> 0xFF800080.toInt()
        13 -> 0xFF800000.toInt(); 14 -> 0xFF808000.toInt(); 15 -> 0xFF808080.toInt(); 16 -> 0xFFC0C0C0.toInt(); else -> 0
    }

    private fun isEa(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c) ?: return false
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || b == Character.UnicodeBlock.HANGUL_JAMO ||
            b == Character.UnicodeBlock.BOPOMOFO || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
    }

    private fun isComplex(c: Char): Boolean {
        val d = Character.getDirectionality(c)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    private fun effectiveFont(name: String?, script: Int): String? {
        val n = name?.trim()
        if (n.isNullOrEmpty() || script == 1 || script == 2) return n
        val l = n.lowercase(java.util.Locale.ROOT)
        if (l == "symbol" || l.startsWith("wingdings") || l == "webdings" || l.contains("sorts") || l.contains("dingbat") || l.contains("symbol")) return n
        if (OfficeFonts.bundledFamily(n) != null || WordFontMetrics.of(n) != null) return n
        return "Calibri"
    }

    private fun style(c: CharProps, script: Int): RunStyle {
        val key = "${System.identityHashCode(c)}|$script"
        styles[key]?.let { return it }
        val fontIdx = when (script) { 1 -> c.fontEa; 2 -> c.fontBi; 3 -> c.fontOther; else -> c.fontAscii }
        val bold = if (script == 2) c.boldBi || c.bold else c.bold
        val italic = if (script == 2) c.italicBi || c.italic else c.italic
        val size = (if (script == 2) c.sizeBi else c.size) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        p.hinting = Paint.HINTING_OFF
        val fname = effectiveFont(doc.fontName(fontIdx), script)
        val tf = WordFonts.typeface(fname, bold, italic)
        p.typeface = tf
        if (bold && !tf.isBold) p.isFakeBoldText = true
        if (italic && !tf.isItalic) p.textSkewX = -0.2f
        var eff = size
        var shift = -c.position / 2f
        if (c.vertAlign == 1 || c.vertAlign == 2) {
            eff = size * 0.65f
            shift += if (c.vertAlign == 1) -size * 0.33f else size * 0.14f
        }
        p.textSize = maxOf(0.5f, eff)
        if (c.spacing != 0) p.letterSpacing = (c.spacing / 20f) / p.textSize
        if (c.kern == 0 || c.kern > (if (script == 2) c.sizeBi else c.size)) p.fontFeatureSettings = "'kern' 0"
        val known = WordFontMetrics.of(fname)
        val scaleX = c.scale / 100f
        if (scaleX != 1f) p.textScaleX = scaleX
        val color = if (c.color == WShading.AUTO) 0xFF000000.toInt() else c.color
        p.color = color
        val rs = RunStyle(p, color, if (c.underline != 0) c.underline else 0, c.underlineColor, c.strike, c.dstrike, hlColor(c.highlight), c.shading?.resolve(), shift, eff, c.border, known)
        styles[key] = rs
        return rs
    }

    private fun scriptOf(ch: Char, c: CharProps): Int = if (c.rtl || c.complex || isComplex(ch)) 2 else if (isEa(ch)) 1 else if (ch.code < 0x80 || ch == '\u00A0' || ch == '\u2011' || ch == '\u00AD') 0 else 3

    private fun applyCaps(s: String, c: CharProps): String = if (c.caps || c.smallCaps) s.uppercase() else s

    private fun segments(p: WParagraph): List<Seg> {
        val out = ArrayList<Seg>()
        val label = p.listLabel
        if (label != null && p.listLabelProps != null) {
            val lp = p.listLabelProps!!
            if (label.isNotEmpty()) out.add(Seg(label, style(lp, if (label.any { isComplex(it) }) 2 else 0), 0, null, 0, 0, -1, false, null))
            when (p.listFollow) { 0 -> out.add(Seg("\t", style(lp, 0), 3, null, 0, 0, -1, false, null)); 1 -> out.add(Seg(" ", style(lp, 0), 0, null, 0, 0, -1, false, null)) }
        }
        for (inl in p.inlines) {
            when (inl) {
                is WInline.Text -> {
                    val txt = inl.text
                    var i = 0
                    val sb = StringBuilder()
                    var curScript = -1
                    fun flush() {
                        if (sb.isNotEmpty()) {
                            val st = style(inl.props, maxOf(0, curScript))
                            if (inl.props.smallCaps && !inl.props.caps) {
                                var j = 0
                                val s = sb.toString()
                                while (j < s.length) {
                                    val lower = s[j].isLowerCase()
                                    var k = j
                                    while (k < s.length && s[k].isLowerCase() == lower) k++
                                    val part = s.substring(j, k)
                                    if (lower) {
                                        val sm = smallCapsStyle(st)
                                        out.add(Seg(part.uppercase(), sm, 0, null, 0, 0, -1, false, null, -1, inl.props.lid))
                                    } else out.add(Seg(part, st, 0, null, 0, 0, -1, false, null, -1, inl.props.lid))
                                    j = k
                                }
                            } else out.add(Seg(applyCaps(sb.toString(), inl.props), st, 0, null, 0, 0, -1, false, null, -1, inl.props.lid))
                            sb.setLength(0)
                        }
                    }
                    while (i < txt.length) {
                        val ch = txt[i]
                        when (ch) {
                            '\t' -> { flush(); out.add(Seg("\t", style(inl.props, 0), 3, null, 0, 0, -1, false, null)); i++; continue }
                            '\n' -> { flush(); out.add(Seg("\n", style(inl.props, 0), 4, null, 0, 0, -1, false, null)); i++; continue }
                            '\u000C' -> { flush(); out.add(Seg("", style(inl.props, 0), 5, null, 0, 0, -1, false, null)); i++; continue }
                            '\u000E' -> { flush(); out.add(Seg("", style(inl.props, 0), 6, null, 0, 0, -1, false, null)); i++; continue }
                        }
                        val keepWithPrev = curScript >= 0 && (
                            Character.isLowSurrogate(ch) || ch == '\u200D' || ch == '\uFE0F' || ch == '\uFE0E' ||
                                ch == '\u20E3' || Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ||
                                (sb.isNotEmpty() && sb[sb.length - 1] == '\u200D') ||
                                (Character.isHighSurrogate(ch) && i + 1 < txt.length && Character.toCodePoint(ch, txt[i + 1]) in 0x1F3FB..0x1F3FF)
                            )
                        val sc = if (keepWithPrev) curScript else scriptOf(ch, inl.props)
                        val effSc = if (curScript == 2 && (ch == ' ' || ch.isDigit())) 2 else sc
                        if (curScript >= 0 && effSc != curScript) flush()
                        curScript = effSc
                        sb.append(ch)
                        i++
                    }
                    flush()
                }
                is WInline.Picture -> out.add(Seg("￼", style(inl.props, 0), 1, inl.image, 0, 0, -1, false, null))
                is WInline.Field -> out.add(Seg("00", style(inl.props, 0), 2, null, inl.kind, inl.format, -1, false, null))
                is WInline.FootnoteRef -> out.add(Seg(inl.label, style(inl.props, 0), 7, null, 0, 0, inl.noteIndex, inl.endnote, null))
                is WInline.Anchor -> out.add(Seg("", style(inl.props, 0), 8, null, 0, 0, -1, false, inl.shape))
                is WInline.CommentRef -> out.add(Seg("", style(inl.props, 0), 9, null, 0, 0, -1, false, null, inl.commentIndex))
            }
        }
        for (s in out) measure(s)
        return out
    }

    private val smallCaps = HashMap<RunStyle, RunStyle>()

    private fun smallCapsStyle(st: RunStyle): RunStyle = smallCaps.getOrPut(st) {
        val p = Paint(st.paint)
        p.textSize = st.paint.textSize * 0.8f
        RunStyle(p, st.color, st.underline, st.underlineColor, st.strike, st.dstrike, st.highlight, st.shading, st.baselineShift, st.sizePt * 0.8f, st.border, st.vmetrics)
    }

    private fun measure(s: Seg) {
        when (s.kind) {
            0, 7 -> {
                val n = s.text.length
                val adv = FloatArray(n)
                if (n > 0) {
                    try { s.style.paint.getTextRunAdvances(s.text.toCharArray(), 0, n, 0, n, s.text.any { isComplex(it) }, adv, 0) } catch (e: Exception) { for (i in 0 until n) adv[i] = s.style.paint.measureText(s.text, i, i + 1) }
                    for (i in 0 until n) if (s.text[i] == '\u00AD') adv[i] = 0f
                }
                s.widths = adv
            }
            1 -> s.widths = floatArrayOf(s.image!!.widthTw / 20f)
            2 -> s.widths = floatArrayOf(s.style.paint.measureText(fieldSample(s)))
            else -> s.widths = FloatArray(s.text.length)
        }
    }

    private fun metrics(st: RunStyle): FloatArray {
        st.vmetrics?.let { v -> return floatArrayOf(v[0] * st.sizePt, v[1] * st.sizePt, v[2] * st.sizePt) }
        val fm = st.paint.fontMetrics
        val base = st.paint.textSize
        val scale = if (base > 0f) st.sizePt / base else 1f
        val asc = -fm.ascent * scale
        val desc = fm.descent * scale
        val lead = fm.leading * scale
        return floatArrayOf(asc, desc, lead)
    }

    private class Piece(val seg: Seg, val from: Int, val to: Int) {
        val width: Float get() {
            if (seg.kind != 0 && seg.kind != 7) return if (seg.kind == 1 || seg.kind == 2) seg.widths.getOrElse(0) { 0f } else 0f
            var w = 0f
            for (i in from until minOf(to, seg.widths.size)) w += seg.widths[i]
            return w
        }
    }

    private fun layoutParagraph(p: WParagraph, width: Float, defaultTab: Float, exclusions: (Float, Float) -> FloatArray? = { _, _ -> null }): List<LineOut> {
        val segs = segments(p)
        val pp = p.props
        val left = pp.indLeft / 20f
        val right = pp.indRight / 20f
        val first = pp.indFirst / 20f
        val lines = ArrayList<LineOut>()
        val flat = StringBuilder()
        val owners = ArrayList<Int>()
        for ((si, s) in segs.withIndex()) {
            val t = when (s.kind) { 0, 7 -> s.text; 3 -> "\t"; 4 -> "\n"; 1, 2, 8, 9 -> "￼"; else -> "￼" }
            flat.append(t)
            repeat(t.length) { owners.add(si) }
        }
        val text = flat.toString()
        val bi = android.icu.text.BreakIterator.getLineInstance()
        bi.setText(text)
        var charBi: android.icu.text.BreakIterator? = null
        val markStyle = style(p.markProps, 0)
        val mm = metrics(markStyle)
        val segStart = IntArray(segs.size + 1)
        run { var a = 0; for ((i, s) in segs.withIndex()) { segStart[i] = a; a += when (s.kind) { 0, 7 -> s.text.length; else -> 1 } }; segStart[segs.size] = a }
        fun w(i: Int): Float { val s = segs[owners[i]]; return if (s.kind == 0 || s.kind == 7) s.widths[i - segStart[owners[i]]] else if (s.kind == 1 || s.kind == 2) s.widths[0] else 0f }
        var pos = 0
        var lineIdx = 0
        var yAcc = 0f
        var consecHyph = 0
        val canHyphen = doc.autoHyphen && !pp.noAutoHyph
        val hotZone = doc.hotZoneTw / 20f
        if (text.isEmpty()) {
            var skips = 0
            while (skips++ < 20) {
                val ex = exclusions(yAcc, mm[0] + mm[1] + mm[2]) ?: break
                if (ex.size < 3 || ex[2] <= 0.01f) break
                lines.add(LineOut(ArrayList(), ex[2], 0f, ex[2], emptyList(), emptyList(), false, false))
                yAcc += ex[2]
            }
            lines.add(finishLine(ArrayList(), pp, mm, left + first, width - right, false, false, emptyList(), emptyList(), true, defaultTab, left))
            return lines
        }
        while (pos < text.length) {
            val lineLeft0 = left + (if (lineIdx == 0) first else 0f)
            var lineLeft = lineLeft0
            var lineRight = width - right
            var floatClipRight = false
            var skips = 0
            while (true) {
                val ex = exclusions(yAcc, mm[0] + mm[1] + mm[2]) ?: break
                if (ex.size >= 3 && ex[2] > 0.01f && skips++ < 20) {
                    lines.add(LineOut(ArrayList(), ex[2], 0f, ex[2], emptyList(), emptyList(), false, false))
                    yAcc += ex[2]
                    continue
                }
                if (ex[1] < lineRight - 0.5f) floatClipRight = true
                lineLeft = maxOf(lineLeft, ex[0]); lineRight = minOf(lineRight, ex[1])
                break
            }
            val avail = maxOf(1f, lineRight - lineLeft)
            var x = 0f
            var end = pos
            var lastBreak = -1
            var lastBreakX = 0f
            var hard = false
            var pageBreak = false
            var colBreak = false
            var hyphenated = false
            var i = pos
            var alignJc = 0
            var alignStop = 0f
            var alignedW = 0f
            var tabBreak = -1
            while (i < text.length) {
                val ch = text[i]
                val sg = segs[owners[i]]
                if (sg.kind == 4) { end = i + 1; hard = true; break }
                if (sg.kind == 5) { end = i + 1; pageBreak = true; break }
                if (sg.kind == 6) { end = i + 1; colBreak = true; break }
                if (sg.kind == 3) {
                    val cur = alignedEnd(alignJc, x, alignStop, alignedW)
                    val stop = nextTab(lineLeft + cur, pp, defaultTab, left, lineIdx == 0 && first < 0)
                    val jcT = stop.second?.jc ?: 0
                    alignStop = stop.first - lineLeft
                    alignJc = if (jcT == 1 || jcT == 2 || jcT == 3) jcT else 0
                    x = if (alignJc == 0) maxOf(cur, alignStop) else cur
                    alignedW = 0f
                    tabBreak = if (alignJc != 0 && i > pos) i else -1
                    i++
                    if (bi.isBoundary(i)) { lastBreak = i; lastBreakX = x }
                    continue
                }
                val cw = w(i)
                val endIfAdded = if (alignJc == 0) x + cw else alignedEnd(alignJc, x, alignStop, alignedW + cw)
                if (endIfAdded > avail + 0.01f && ch != ' ' && i > pos) {
                    if (alignJc != 0 && tabBreak > pos && (lastBreak <= tabBreak + 1)) {
                        end = tabBreak
                    } else {
                        var didHyph = false
                        if (canHyphen && alignJc == 0 && (doc.consecHyphLim == 0 || consecHyph < doc.consecHyphLim)) {
                            val hadBreak = lastBreak > pos
                            var trimEnd = if (hadBreak) lastBreak else pos
                            var trimX = if (hadBreak) lastBreakX else 0f
                            while (trimEnd > pos && text[trimEnd - 1] == ' ') { trimEnd--; trimX -= w(trimEnd) }
                            val needHyph = !hadBreak || (avail - trimX > hotZone + 0.01f)
                            if (needHyph) {
                                var ws = if (hadBreak) lastBreak else pos
                                var baseX = if (hadBreak) lastBreakX else 0f
                                while (ws < text.length && text[ws] == ' ') { baseX += w(ws); ws++ }
                                if (ws < text.length && segs[owners[ws]].kind == 0 && text[ws].isLetter()) {
                                    var we = ws
                                    while (we < text.length && segs[owners[we]].kind == 0 && text[we].isLetter()) we++
                                    if (we - ws >= 4 && i in ws..we) {
                                        val word = text.substring(ws, we)
                                        val wseg = segs[owners[ws]]
                                        val lid = if (wseg.lid != 0) wseg.lid else doc.lid
                                        val pts = WordHyphenator.hyphenPoints(word, lid, doc.autoHyphenCaps)
                                        if (pts.isNotEmpty()) {
                                            val hyphW = wseg.style.paint.measureText("-")
                                            var best = -1
                                            var acc = baseX
                                            var pIdx = 0
                                            var cursor = ws
                                            while (pIdx < pts.size) {
                                                val target = ws + pts[pIdx]
                                                while (cursor < target) { acc += w(cursor); cursor++ }
                                                if (acc + hyphW <= avail + 0.01f) best = target else break
                                                pIdx++
                                            }
                                            if (best > ws) {
                                                end = best
                                                hyphenated = true
                                                didHyph = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!didHyph) {
                            if (lastBreak > pos) {
                                end = lastBreak
                            } else {
                                val cb = charBi ?: android.icu.text.BreakIterator.getCharacterInstance().also { it.setText(text); charBi = it }
                                var e = i
                                if (e < text.length && !cb.isBoundary(e)) {
                                    val prev = cb.preceding(e)
                                    e = if (prev > pos) prev else cb.following(pos).let { if (it == android.icu.text.BreakIterator.DONE) text.length else it }
                                }
                                end = e
                            }
                        }
                    }
                    break
                }
                if (alignJc == 0) x += cw else alignedW += cw
                i++
                if (i < text.length && bi.isBoundary(i)) { lastBreak = i; lastBreakX = x }
                end = i
            }
            if (i >= text.length) end = text.length
            if (end <= pos) {
                val cb = charBi ?: android.icu.text.BreakIterator.getCharacterInstance().also { it.setText(text); charBi = it }
                val nxt = cb.following(pos)
                end = if (nxt != android.icu.text.BreakIterator.DONE && nxt > pos) nxt else pos + 1
            }
            if (!hyphenated && end > pos && end < text.length && text[end - 1] == '\u00AD') hyphenated = true
            consecHyph = if (hyphenated) consecHyph + 1 else 0
            val pieces = ArrayList<Piece>()
            var k = pos
            while (k < end) {
                val si = owners[k]
                val s = segs[si]
                val segEndFlat = segStart[si + 1]
                val stop = minOf(end, segEndFlat)
                if (s.kind == 0 || s.kind == 7) pieces.add(Piece(s, k - segStart[si], stop - segStart[si])) else pieces.add(Piece(s, 0, 1))
                k = stop
            }
            val last = end >= text.length
            val foot = pieces.filter { it.seg.kind == 7 && it.seg.footnote >= 0 }.map { if (it.seg.endnote) -1 - it.seg.footnote else it.seg.footnote }
            val anchors = pieces.mapNotNull { it.seg.anchor }
            val line = finishLine(pieces, pp, mm, lineLeft, lineRight, hard, last, foot, anchors, lineIdx == 0, defaultTab, left, floatClipRight, hyphenated)
            lines.add(LineOut(line.items, line.height, line.ascent, line.descent, line.footnotes, line.anchors, pageBreak, colBreak, line.comments))
            yAcc += line.height
            pos = end
            lineIdx++
            if (lines.size > 100000) break
        }
        return lines
    }

    private fun alignedEnd(jc: Int, before: Float, stop: Float, w: Float): Float = when (jc) {
        1 -> maxOf(before, stop - w / 2f) + w
        2, 3 -> maxOf(before + w, stop)
        else -> before + w
    }

    private fun nextTab(absX: Float, pp: ParaProps, defaultTab: Float, left: Float, hanging: Boolean): Pair<Float, WTab?> {
        var best: WTab? = null
        for (t in pp.tabs) { val tx = t.pos / 20f; if (tx > absX + 0.01f && t.jc != 4) { best = t; break } }
        if (hanging && left > absX + 0.01f && (best == null || best.pos / 20f > left)) return left to null
        if (best != null) return best.pos / 20f to best
        val step = if (defaultTab > 0.5f) defaultTab else 36f
        val n = Math.floor(((absX + 0.01f) / step).toDouble()).toInt() + 1
        return n * step to null
    }

    private fun finishLine(
        pieces: List<Piece>, pp: ParaProps, mm: FloatArray, lineLeft: Float, lineRight: Float, hard: Boolean, last: Boolean,
        foot: List<Int>, anchors: List<WFloating>, firstLine: Boolean, defaultTab: Float, paraLeft: Float,
        floatClipRight: Boolean = false, hyphenated: Boolean = false
    ): LineOut {
        var asc = 0f; var desc = 0f
        var any = false
        for (pc in pieces) {
            val s = pc.seg
            if (s.kind == 1) { asc = maxOf(asc, s.image!!.heightTw / 20f); any = true; continue }
            if (s.kind == 8 || s.kind == 9 || s.kind == 4 || s.kind == 5 || s.kind == 6) continue
            val m = metrics(s.style)
            asc = maxOf(asc, m[0] + m[2] - minOf(0f, s.style.baselineShift)); desc = maxOf(desc, m[1] + maxOf(0f, s.style.baselineShift)); any = true
        }
        if (!any) { asc = mm[0] + mm[2]; desc = mm[1] }
        val natural = asc + desc
        var height = natural
        var baseline = asc
        if (pp.lineMult) {
            if (pp.lineValue > 0 && pp.lineValue != 240) height = natural * pp.lineValue / 240f
        } else {
            val v = Math.abs(pp.lineValue) / 20f
            if (pp.lineValue < 0) { height = v; baseline = v * 0.8f } else if (v > natural) { baseline += v - natural; height = v }
        }
        var trailing = pieces.size
        while (trailing > 0) {
            val pc = pieces[trailing - 1]
            if ((pc.seg.kind == 0) && pc.seg.text.substring(pc.from, pc.to).isBlank()) trailing-- else break
        }
        val positions = ArrayList<Pair<Piece, Float>>()
        var x = lineLeft
        val tabsAt = ArrayList<Triple<Int, Float, WTab?>>()
        val tabFrom = FloatArray(pieces.size)
        for ((idx, pc) in pieces.withIndex()) {
            if (pc.seg.kind == 3) {
                val (stop, tab) = nextTab(x, pp, defaultTab, paraLeft, firstLine && pp.indFirst < 0)
                tabsAt.add(Triple(idx, stop, tab))
                tabFrom[idx] = x
                x = stop
                positions.add(pc to x)
                continue
            }
            positions.add(pc to x)
            x += pc.width
        }
        val xs = FloatArray(pieces.size) { positions[it].second }
        for ((ti, entry) in tabsAt.withIndex()) {
            val (idx, stop, tab) = entry
            if (tab == null || tab.jc == 0) continue
            val nextTabIdx = tabsAt.getOrNull(ti + 1)?.first ?: pieces.size
            var groupW = 0f
            var decimalW = -1f
            for (k in idx + 1 until nextTabIdx) {
                val pc = pieces[k]
                if (tab.jc == 3 && decimalW < 0f && pc.seg.kind == 0) {
                    val s = pc.seg.text.substring(pc.from, pc.to)
                    val dot = s.indexOfFirst { it == '.' || it == ',' }
                    if (dot >= 0) { var w = 0f; for (q in pc.from until pc.from + dot) w += pc.seg.widths[q]; decimalW = groupW + w }
                }
                groupW += pc.width
            }
            val startX = when (tab.jc) { 1 -> stop - groupW / 2f; 2 -> stop - groupW; 3 -> stop - (if (decimalW >= 0f) decimalW else groupW); else -> stop }
            val prevEnd = if (idx > 0) xs[idx - 1] + pieces[idx - 1].width else lineLeft
            val newStart = maxOf(prevEnd, startX)
            val delta = newStart - xs.getOrElse(idx + 1) { stop }
            for (k in idx + 1 until pieces.size) xs[k] += delta
            xs[idx] = prevEnd
            tabFrom[idx] = prevEnd
        }
        val hyphStyle = if (hyphenated) pieces.lastOrNull { it.seg.kind == 0 }?.seg?.style else null
        val hyphW = hyphStyle?.paint?.measureText("-") ?: 0f
        val endX = (if (trailing > 0) xs[trailing - 1] + pieces[trailing - 1].width else lineLeft) + hyphW
        val slack = lineRight - endX
        var shift = 0f
        var wordSpacing = 0f
        val jc = if (pp.bidi) when (pp.jc) { 0 -> 2; 2 -> 0; else -> pp.jc } else pp.jc
        val lastTab = tabsAt.lastOrNull()?.first ?: -1
        when (jc) {
            1 -> shift = slack / 2f
            2 -> shift = slack
            3, 4, 5 -> if (!last || jc == 4) {
                var spaces = 0
                for (k in maxOf(0, lastTab + 1) until trailing) { val pc = pieces[k]; if (pc.seg.kind == 0) for (q in pc.from until pc.to) if (pc.seg.text[q] == ' ') spaces++ }
                if (spaces > 0 && slack > 0f) wordSpacing = slack / spaces
            }
        }
        val items = ArrayList<LItem>()
        var extra = 0f
        val leader = ArrayList<LItem>()
        val lineComments = ArrayList<Pair<Int, Float>>()
        val availW = maxOf(1f, lineRight - lineLeft)
        val soleHr = pieces.size == 1 && pieces[0].seg.kind == 1 && pieces[0].seg.image?.kind == 2
        for ((k, pc) in pieces.withIndex()) {
            val s = pc.seg
            val px = xs[k] + shift + extra
            when (s.kind) {
                0, 7 -> {
                    val raw = s.text.substring(pc.from, pc.to)
                    val str = if (raw.indexOf('\u00AD') >= 0) raw.replace("\u00AD", "") else raw
                    var spaces = 0
                    if (wordSpacing > 0f && k > lastTab && k < trailing) spaces = str.count { it == ' ' }
                    val wpc = pc.width + spaces * wordSpacing
                    val rtl = str.any { isComplex(it) }
                    items.add(LItem.Text(px, baseline + s.style.baselineShift, str, s.style, wpc, rtl, if (spaces > 0) wordSpacing else 0f))
                    extra += spaces * wordSpacing
                }
                1 -> {
                    val img = s.image!!
                    val ih = img.heightTw / 20f
                    if (img.kind == 2) {
                        val rw = if (img.hrPct in 1..1000) availW * (img.hrPct / 1000f) else minOf(img.widthTw / 20f, availW)
                        val hx = if (soleHr) when (img.hrAlign) { 0 -> lineLeft; 2 -> lineRight - rw; else -> lineLeft + (availW - rw) / 2f } else px
                        items.add(LItem.Image(hx, baseline - ih, rw, ih, img))
                    } else {
                        val iw = img.widthTw / 20f
                        val cw = if (floatClipRight && px + iw > lineRight + 0.5f) maxOf(1f, lineRight - px) else 0f
                        items.add(LItem.Image(px, baseline - ih, iw, ih, img, cw))
                    }
                }
                2 -> items.add(LItem.PageField(px, baseline + s.style.baselineShift, s.field, s.style, s.fieldFormat, s.widths[0]))
                3 -> {
                    val tabEntry = tabsAt.firstOrNull { it.first == k }
                    val tab = tabEntry?.third
                    val from = tabFrom[k] + shift + extra
                    val nextX = if (k + 1 < pieces.size) xs[k + 1] + shift + extra else (tabEntry?.second ?: px) + shift + extra
                    if (tab != null && tab.leader in 1..5 && nextX - from > 2f) {
                        val ch = when (tab.leader) { 1 -> "."; 2 -> "-"; 3, 4 -> "_"; else -> "·" }
                        val p = s.style.paint
                        val cw = (p.measureText(ch.repeat(64)) / 64f).takeIf { it > 0.1f } ?: 2f
                        val k0 = Math.ceil((from / cw).toDouble()).toInt()
                        val k1 = Math.floor((nextX / cw).toDouble()).toInt()
                        val n = k1 - k0
                        if (n > 0) leader.add(LItem.Text(k0 * cw, baseline, ch.repeat(n), s.style, n * cw, false, 0f))
                    }
                }
                9 -> if (s.comment >= 0) lineComments.add(s.comment to px)
            }
        }
        if (hyphenated && hyphStyle != null) {
            val hx = (if (trailing > 0) xs[trailing - 1] + pieces[trailing - 1].width else lineLeft) + shift + extra
            items.add(LItem.Text(hx, baseline + hyphStyle.baselineShift, "-", hyphStyle, hyphW, false, 0f, true))
        }
        items.addAll(leader)
        return LineOut(items, height, baseline, height - baseline, foot, anchors, false, false, lineComments)
    }

    private fun paraSpacing(p: WParagraph, prev: WParagraph?, next: WParagraph?): Pair<Float, Float> {
        val pp = p.props
        val autoSp = if (doc.collapseSpacing) 14f else 5f
        var before = if (pp.beforeAuto) autoSp else pp.spBefore / 20f
        var after = if (pp.afterAuto) autoSp else pp.spAfter / 20f
        if (pp.contextualSpacing && prev != null && prev.props.istd == pp.istd) before = 0f
        if (pp.contextualSpacing && next != null && next.props.istd == pp.istd) after = 0f
        return before to after
    }

    private inner class Flow {
        lateinit var section: WSection
        var sp = SectionProps()
        var sectionIndex = 0
        var pw = 612f
        var ph = 792f
        var page: LPage? = null
        var pageNumber = 0
        var col = 0
        var y = 0f
        var top = 0f
        var colTop = 0f
        var bottom = 0f
        var colX = 0f
        var colW = 0f
        var cols = 1
        var footReserve = 0f
        val pageFootnotes = ArrayList<Int>()
        var firstOnPage = true
        var lastAfter = 0f
        val floats = ArrayList<FloatArray>()
        var pendingTitlePage = false
        var commentBottomY = 0f

        fun columnGeometry(c: Int): Pair<Float, Float> {
            val left = (sp.marginLeft + sp.gutter) / 20f
            val total = pw - left - sp.marginRight / 20f
            if (cols == 1) return left to total
            if (!sp.evenlySpaced && sp.columnWidths.size >= cols) {
                var x = left
                for (k in 0 until c) x += sp.columnWidths[k] / 20f + (sp.columnSpacings.getOrElse(k) { sp.columnSpacing }) / 20f
                return x to sp.columnWidths[c] / 20f
            }
            val gap = sp.columnSpacing / 20f
            val w = (total - gap * (cols - 1)) / cols
            return left + c * (w + gap) to w
        }

        fun startSection(s: WSection, si: Int) {
            val first = page == null
            val bk = s.props.breakCode
            val samePage = !first && (bk == 0 || bk == 1)
            if (!first && !samePage) finishPage()
            section = s
            sp = s.props
            sectionIndex = si
            cols = maxOf(1, sp.columns)
            if (si == 0 || sp.restartPageNumbers) pageNumber = sp.pageNumberStart - 1
            pendingTitlePage = sp.titlePage
            if (samePage) {
                if (bk == 1 && col + 1 < cols) { nextColumn(); return }
                colTop = y
                col = 0
                val (cx, cw) = columnGeometry(0)
                colX = cx; colW = cw
                return
            }
            newPage()
            if ((bk == 3 && pageNumber % 2 != 0) || (bk == 4 && pageNumber % 2 == 0)) { finishPage(); newPage() }
        }

        fun newPage() {
            pageNumber++
            pw = sp.pageWidth / 20f
            ph = sp.pageHeight / 20f
            val pg = LPage(pw, ph, pageNumber, sectionIndex, sp.pageNumberFormat)
            pages.add(pg)
            page = pg
            val headers = section.headers
            val firstPage = pendingTitlePage
            pendingTitlePage = false
            val even = pageNumber % 2 == 0
            val header = when { firstPage -> headers.firstHeader; doc.facingPages && even -> headers.evenHeader; else -> headers.oddHeader }
            val footer = when { firstPage -> headers.firstFooter; doc.facingPages && even -> headers.evenFooter; else -> headers.oddFooter }
            var bodyTop = Math.abs(sp.marginTop) / 20f
            var bodyBottom = ph - Math.abs(sp.marginBottom) / 20f
            fieldPage = pageNumber
            fieldPageFormat = sp.pageNumberFormat
            fieldSection = sectionIndex
            val hx = sp.marginLeft / 20f
            val hw = pw - sp.marginLeft / 20f - sp.marginRight / 20f
            if (header != null) {
                val h = placeBlocks(header, hx, hw, sp.headerTop / 20f, pg.back)
                if (sp.marginTop >= 0) bodyTop = maxOf(bodyTop, sp.headerTop / 20f + h)
            }
            if (footer != null) {
                val tmp = ArrayList<LItem>()
                val h = placeBlocks(footer, hx, hw, 0f, tmp)
                val fy = ph - sp.footerBottom / 20f - h
                for (it in tmp) pg.back.add(offset(it, 0f, fy))
                if (sp.marginBottom >= 0) bodyBottom = minOf(bodyBottom, fy)
            }
            fieldPage = 0
            top = bodyTop
            colTop = bodyTop
            bottom = bodyBottom
            col = 0
            val (cx, cw) = columnGeometry(0)
            colX = cx; colW = cw
            y = top
            footReserve = 0f
            pageFootnotes.clear()
            floats.clear()
            firstOnPage = true
            lastAfter = 0f
            commentBottomY = top
        }

        fun nextColumn() {
            if (col + 1 < cols) {
                col++
                val (cx, cw) = columnGeometry(col)
                colX = cx; colW = cw
                y = colTop
                firstOnPage = true
                lastAfter = 0f
            } else {
                finishPage()
                newPage()
            }
        }

        fun finishPage() {
            val pg = page ?: return
            if (pageFootnotes.isNotEmpty()) {
                var fy = bottom - footReserve
                val (fx, fw) = (sp.marginLeft / 20f) to (pw - sp.marginLeft / 20f - sp.marginRight / 20f)
                val sep = sepHeight
                pg.items.add(LItem.Line(fx, fy + sep / 2f, fx + 144f, fy + sep / 2f, WBorder(1, 4, 0xFF000000.toInt(), 0)))
                fy += sep
                for (fn in pageFootnotes) {
                    val note = doc.footnotes.getOrNull(fn) ?: continue
                    val from = pg.items.size
                    fy += placeBlocks(note.blocks, fx, fw, fy, pg.items)
                    if (pg.text.isNotEmpty() && pg.text.last() != '\n') pg.text.append('\n')
                    for (k in from until pg.items.size) (pg.items[k] as? LItem.Text)?.let { if (pg.text.length < 200000) pg.text.append(it.text) }
                }
                pageFootnotes.clear()
            }
            if (sp.pageBorders.any { it?.visible == true }) {
                val b = sp.pageBorders
                val l = sp.marginLeft / 20f - 24f; val t = Math.abs(sp.marginTop) / 20f - 24f
                val r = pw - sp.marginRight / 20f + 24f; val bt = ph - Math.abs(sp.marginBottom) / 20f + 24f
                b[0]?.takeIf { it.visible }?.let { pg.items.add(LItem.Line(l, t, r, t, it)) }
                b[1]?.takeIf { it.visible }?.let { pg.items.add(LItem.Line(l, t, l, bt, it)) }
                b[2]?.takeIf { it.visible }?.let { pg.items.add(LItem.Line(l, bt, r, bt, it)) }
                b[3]?.takeIf { it.visible }?.let { pg.items.add(LItem.Line(r, t, r, bt, it)) }
            }
        }

        val sepHeight: Float by lazy { finishLine(ArrayList(), doc.normalPara, metrics(style(doc.normalChar, 0)), 0f, 500f, false, true, emptyList(), emptyList(), true, 36f, 0f).height }

        fun placeEndnotes() {
            if (doc.endnotes.isEmpty()) return
            val sep = sepHeight
            if (y + sep > bottom - footReserve + 0.01f && !firstOnPage) nextColumn()
            page?.items?.add(LItem.Line(colX, y + sep / 2f, colX + 144f, y + sep / 2f, WBorder(1, 4, 0xFF000000.toInt(), 0)))
            y += sep
            lastAfter = 0f
            for (note in doc.endnotes) {
                val blocks = note.blocks
                for ((i, b) in blocks.withIndex()) when (b) {
                    is WParagraph -> placeParagraph(paraOut(b, colW, blocks.getOrNull(i - 1) as? WParagraph, blocks.getOrNull(i + 1) as? WParagraph), b)
                    is WTable -> placeTable(b)
                }
            }
        }

        fun footHeight(fn: Int): Float {
            if (fn < 0) return 0f
            val note = doc.footnotes.getOrNull(fn) ?: return 0f
            return measureBlocks(note.blocks, pw - sp.marginLeft / 20f - sp.marginRight / 20f)
        }

        fun exclusionFor(lineTop: Float, h: Float): FloatArray? {
            var l = 0f; var r = colW
            var hit = false
            var lowest = lineTop
            for (f in floats) {
                if (lineTop + h <= f[1] || lineTop >= f[3]) continue
                val fl = f[0] - colX; val fr = f[2] - colX
                if (fr <= 0f || fl >= colW) continue
                lowest = maxOf(lowest, f[3])
                if (f[4] == 1f) return floatArrayOf(0f, colW, f[3] - lineTop)
                hit = true
                if (fl > colW - fr) r = minOf(r, fl) else l = maxOf(l, fr)
            }
            if (hit && r - l < 18f) return floatArrayOf(0f, colW, lowest - lineTop)
            return if (hit) floatArrayOf(l, r) else null
        }

        fun placeParagraph(po: ParaOut, p: WParagraph, hang: Boolean = false) {
            if (po.pageBreakBefore && !firstOnPage) { finishPage(); newPage() }
            var i = 0
            var before = po.before
            if (doc.collapseSpacing && p.props.beforeAuto && firstOnPage) before = 0f
            if (doc.collapseSpacing) before -= minOf(before, lastAfter)
            y += before
            val lines = po.lines
            val widow = p.props.widowControl
            var paraTop = y
            var firstPart = true
            y += po.topExtra
            while (i < lines.size) {
                val ln = lines[i]
                val fh = ln.footnotes.sumOf { footHeight(it).toDouble() }.toFloat()
                val extraSep = if (ln.footnotes.any { it >= 0 } && pageFootnotes.isEmpty()) sepHeight else 0f
                val limit = bottom - footReserve - fh - extraSep
                var need = ln.height
                if (widow && i == 0 && lines.size >= 2 && !po.keep) need = ln.height + lines[1].height
                if (po.keep && i == 0) need = lines.sumOf { it.height.toDouble() }.toFloat()
                if (widow && lines.size >= 3 && i == lines.size - 2 && i > 0) need = ln.height + lines[i + 1].height
                if (hang) need = 0f
                if (y + need > limit + 0.01f && !firstOnPage) {
                    if (i == 0) y -= po.topExtra
                    nextColumn()
                    paraTop = y
                    if (i == 0) y += po.topExtra
                    continue
                }
                var lineTop = y
                if (ln.anchors.isNotEmpty()) {
                    val first = floats.size
                    for (a in ln.anchors) placeFloating(a, lineTop)
                    var push = lineTop
                    for (k in first until floats.size) {
                        val f = floats[k]
                        if (f[4] == 1f && f[1] < lineTop + ln.height && f[3] > lineTop && f[2] - colX > 0f && f[0] - colX < colW) push = maxOf(push, f[3])
                    }
                    if (push > lineTop) { lineTop = push; y = push }
                }
                for (it in ln.items) page!!.items.add(offset(it, colX, lineTop))
                if (po.shading != null) page!!.back.add(LItem.Rect(colX + po.left, lineTop, colW - po.left - po.right, ln.height, po.shading))
                appendText(ln)
                for ((ci, ax) in ln.comments) placeComment(ci, colX + ax, lineTop + ln.ascent * 0.5f)
                for (fn in ln.footnotes) if (fn >= 0) { footReserve += footHeight(fn) + (if (pageFootnotes.isEmpty()) sepHeight else 0f); pageFootnotes.add(fn) }
                y += ln.height
                firstOnPage = false
                if (ln.pageBreak) { drawParaBorders(po, paraTop, y, firstPart, false); firstPart = false; finishPage(); newPage(); paraTop = y }
                else if (ln.columnBreak) { drawParaBorders(po, paraTop, y, firstPart, false); firstPart = false; nextColumn(); paraTop = y }
                i++
            }
            y += po.bottomExtra
            drawParaBorders(po, paraTop, y, firstPart, true)
            y += po.after
            lastAfter = po.after
            firstOnPage = false
        }

        fun drawParaBorders(po: ParaOut, t: Float, b: Float, first: Boolean, last: Boolean) {
            val pg = page ?: return
            paraBorderItems(po, colX + po.left, colX + colW - po.right, t, b, first, last, pg.items)
        }

        fun appendText(ln: LineOut) {
            val pg = page ?: return
            for (it in ln.items) if (it is LItem.Text && !it.synthetic && pg.text.length < 200000) pg.text.append(it.text)
            if (pg.text.isNotEmpty() && pg.text.last() != '\n') pg.text.append('\n')
        }

        fun placeComment(idx: Int, anchorX: Float, anchorY: Float) {
            val pg = page ?: return
            val cm = doc.comments.getOrNull(idx) ?: return
            val full = if (cm.author.isNotEmpty() && cm.text.isNotEmpty()) "${cm.author}: ${cm.text}" else cm.text.ifEmpty { cm.author }
            if (full.isEmpty()) return
            if (pg.text.length < 200000) {
                if (pg.text.isNotEmpty() && pg.text.last() != '\n') pg.text.append('\n')
                pg.text.append(full).append('\n')
            }
            val rightEdge = pw - 4f
            val boxX = minOf(colX + colW + 6f, rightEdge - 28f)
            val boxW = rightEdge - boxX
            if (boxW < 22f) return
            val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply { textSize = 6.5f }
            val maxTextW = boxW - 6f
            val wrapped = ArrayList<String>()
            for (rawLine in full.split('\n')) {
                var rem = rawLine.trim()
                if (rem.isEmpty()) continue
                while (rem.isNotEmpty() && wrapped.size < 8) {
                    val count = tp.breakText(rem, true, maxTextW, null).coerceAtLeast(1)
                    var cut = count
                    if (cut < rem.length) {
                        val sp = rem.lastIndexOf(' ', cut)
                        if (sp > 0) cut = sp
                    }
                    wrapped.add(rem.substring(0, cut).trim())
                    rem = rem.substring(cut).trimStart()
                }
            }
            if (wrapped.isEmpty()) return
            val boxH = wrapped.size * 7.5f + 4f
            val boxY = maxOf(anchorY - 4f, commentBottomY).coerceAtMost(maxOf(top, ph - 12f - boxH))
            pg.items.add(LItem.Comment(boxX, boxY, boxW, boxH, anchorX, anchorY, wrapped))
            commentBottomY = boxY + boxH + 3f
        }

        fun placeFloating(f: WFloating, lineTop: Float) {
            val pg = page ?: return
            val x0 = when (f.relX) { 1 -> 0f; 0 -> sp.marginLeft / 20f; else -> colX } + f.left / 20f
            val y0 = when (f.relY) { 1 -> 0f; 0 -> top; else -> lineTop } + f.top / 20f
            val w = (f.right - f.left) / 20f; val h = (f.bottom - f.top) / 20f
            drawFloating(f, x0, y0, w, h, if (f.behind) pg.back else pg.items)
            if (f.wrap == 1) floats.add(floatArrayOf(x0, y0, x0 + w, y0 + h, 1f))
            else if (f.wrap == 2 || f.wrap == 4 || f.wrap == 5) floats.add(floatArrayOf(x0 - 9f, y0, x0 + w + 9f, y0 + h, 0f))
        }

        fun placeFrame(fb: FrameBox, next: WParagraph?) {
            val pg = page ?: return
            val pp = fb.props
            val pcVert = (pp.framePc ushr 4) and 3
            val pcHorz = (pp.framePc ushr 6) and 3
            val (refX, refW) = when (pcHorz) { 1 -> (sp.marginLeft / 20f) to (pw - sp.marginLeft / 20f - sp.marginRight / 20f); 2 -> 0f to pw; else -> colX to colW }
            val (refTop, refH) = when (pcVert) { 1 -> 0f to ph; else -> top to (bottom - top) }
            val nextBefore = next?.let { paraSpacing(it, null, null).first } ?: 0f
            var fx: Float
            var fy: Float
            when (pp.dropCap) {
                1 -> { fx = colX + (next?.props?.indLeft ?: 0) / 20f; fy = y + nextBefore }
                2 -> { fx = colX - fb.w - pp.frameFromX / 20f; fy = y + nextBefore }
                else -> { fx = frameX(fb, refX, refW); fy = frameY(fb, y, refTop, refH) }
            }
            if (pp.dropCap == 0 && pcVert == 2 && fy + fb.h > bottom - footReserve && !firstOnPage) {
                nextColumn()
                fy = frameY(fb, y, refTop, refH)
            }
            for (it in fb.items) page!!.items.add(offset(it, fx, fy))
            for (it in fb.items) if (it is LItem.Text && pg.text.length < 200000) pg.text.append(it.text)
            if (pp.dropCap != 2) {
                val gx = pp.frameFromX / 20f; val gy = pp.frameFromY / 20f
                floats.add(floatArrayOf(fx - gx, fy - gy, fx + fb.w + gx, fy + fb.h + gy, if (pp.frameWrap == 1) 1f else 0f))
            }
            if (pp.dropCap == 0 && pp.frameY == 0 && pcVert == 2 && pp.frameWrap == 1) y = fy + fb.h
        }

        fun placeTable(t: WTable) {
            val rows = ArrayList(layoutTable(t, colW))
            val headerCount = t.rows.takeWhile { it.props.header }.size
            val keepRow = BooleanArray(rows.size) { ri -> t.rows.getOrNull(ri)?.cells?.any { c -> c.blocks.any { it is WParagraph && it.props.keepNext } } == true }
            var r = 0
            var guard = 0
            while (r < rows.size && guard++ < 100000) {
                val lr = rows[r]
                val limit = bottom - footReserve
                if (keepRow[r] && r >= headerCount && !firstOnPage) {
                    val nx = rows.getOrNull(r + 1)
                    val nextNeed = when {
                        nx == null -> 0f
                        keepRow[r + 1] || !nx.canSplit -> nx.height
                        else -> minOf(nx.height, nx.parts.mapNotNull { it.breaks.firstOrNull() }.maxOrNull() ?: nx.height)
                    }
                    val need = lr.height + nextNeed
                    if (y + need > limit + 0.01f && need <= limit - top + 0.01f) {
                        nextColumn()
                        if (headerCount in 1..r) for (hi in 0 until headerCount) { drawRow(rows[hi], colX, y); y += rows[hi].height }
                        continue
                    }
                }
                if (y + lr.height > limit + 0.01f) {
                    val split = if (r >= headerCount && !keepRow[r]) splitRow(lr, limit - y) else null
                    if (split != null) {
                        drawRow(split.first, colX, y)
                        rows[r] = split.second
                        nextColumn()
                        if (headerCount in 1..r) for (hi in 0 until headerCount) { drawRow(rows[hi], colX, y); y += rows[hi].height }
                        continue
                    }
                    if (!firstOnPage) {
                        nextColumn()
                        if (headerCount in 1..r) for (hi in 0 until headerCount) { drawRow(rows[hi], colX, y); y += rows[hi].height }
                        continue
                    }
                    if (lr.height > limit - top + 0.01f) {
                        val forced = splitRow(RowOut(lr.height, lr.items, lr.back, lr.parts, true), limit - y)
                        if (forced != null) {
                            drawRow(forced.first, colX, y)
                            rows[r] = forced.second
                            nextColumn()
                            continue
                        }
                    }
                }
                drawRow(lr, colX, y)
                y += lr.height
                firstOnPage = false
                lastAfter = 0f
                r++
            }
        }

        fun drawRow(lr: RowOut, x: Float, top: Float) {
            val pg = page ?: return
            for (it in lr.back) pg.back.add(offset(it, x, top))
            for (it in lr.items) pg.items.add(offset(it, x, top))
            if (lr.parts.isNotEmpty()) {
                var anyCell = false
                for (c in lr.parts) {
                    var prevY = Float.NaN
                    var wrote = false
                    for (it in c.content) {
                        if (it !is LItem.Text || it.synthetic) continue
                        if (pg.text.length >= 200000) break
                        if (!wrote) {
                            if (anyCell && pg.text.isNotEmpty() && pg.text.last() != '\n' && pg.text.last() != '\t') pg.text.append('\t')
                            wrote = true
                            anyCell = true
                        } else if (!prevY.isNaN() && Math.abs(it.y - prevY) > 1.5f) {
                            if (pg.text.isNotEmpty() && pg.text.last() != '\n') pg.text.append('\n')
                        }
                        pg.text.append(it.text)
                        prevY = it.y
                    }
                }
            } else {
                for (it in lr.items) if (it is LItem.Text && !it.synthetic && pg.text.length < 200000) pg.text.append(it.text)
            }
            if (pg.text.isNotEmpty() && pg.text.last() != '\n') pg.text.append('\n')
        }
    }

    private fun drawFloating(f: WFloating, x0: Float, y0: Float, w: Float, h: Float, target: ArrayList<LItem>) {
        if (f.children.isNotEmpty()) {
            val sx = if (f.right != f.left) w / ((f.right - f.left) / 20f) else 1f
            val sy = if (f.bottom != f.top) h / ((f.bottom - f.top) / 20f) else 1f
            for (c in f.children) drawFloating(c, x0 + (c.left - f.left) / 20f * sx, y0 + (c.top - f.top) / 20f * sy, (c.right - c.left) / 20f * sx, (c.bottom - c.top) / 20f * sy, target)
            return
        }
        f.fill?.let { target.add(LItem.Rect(x0, y0, w, h, it)) }
        f.image?.let { target.add(LItem.Image(x0, y0, w, h, it)) }
        if (f.blocks.isNotEmpty()) {
            val ins = f.insets
            placeBlocks(f.blocks, x0 + ins[0] / 20f, maxOf(1f, w - (ins[0] + ins[2]) / 20f), y0 + ins[1] / 20f, target)
        }
        f.line?.let { b ->
            target.add(LItem.Line(x0, y0, x0 + w, y0, b)); target.add(LItem.Line(x0, y0 + h, x0 + w, y0 + h, b))
            target.add(LItem.Line(x0, y0, x0, y0 + h, b)); target.add(LItem.Line(x0 + w, y0, x0 + w, y0 + h, b))
        }
    }

    private fun offset(it: LItem, dx: Float, dy: Float): LItem = when (it) {
        is LItem.Text -> LItem.Text(it.x + dx, it.y + dy, it.text, it.style, it.width, it.rtl, it.wordSpacing, it.synthetic)
        is LItem.Image -> LItem.Image(it.x + dx, it.y + dy, it.w, it.h, it.image, it.clipW)
        is LItem.Rect -> LItem.Rect(it.x + dx, it.y + dy, it.w, it.h, it.color)
        is LItem.Line -> LItem.Line(it.x1 + dx, it.y1 + dy, it.x2 + dx, it.y2 + dy, it.border)
        is LItem.PageField -> LItem.PageField(it.x + dx, it.y + dy, it.kind, it.style, it.format, it.width)
        is LItem.Comment -> LItem.Comment(it.x + dx, it.y + dy, it.w, it.h, it.anchorX + dx, it.anchorY + dy, it.lines)
    }

    private fun paraOut(p: WParagraph, width: Float, prev: WParagraph?, next: WParagraph?, excl: (Float, Float) -> FloatArray? = { _, _ -> null }): ParaOut {
        if (p.inlines.isEmpty() && p.markProps.hidden && p.listLabel == null && p.props.borders.all { it?.visible != true } && p.props.shading == null) {
            return ParaOut(emptyList(), 0f, 0f, p.props, false, false, p.props.pageBreakBefore, p.props.borders, null, 0f, 0f, null, null)
        }
        val (b, a) = paraSpacing(p, prev, next)
        val lines = layoutParagraph(p, width, doc.defaultTab / 20f, excl)
        val pp = p.props
        val gPrev = prev != null && sameBorders(prev.props, pp)
        val gNext = next != null && sameBorders(next.props, pp)
        val topLine = if (gPrev) pp.borders.getOrNull(4)?.takeIf { it.visible } else pp.borders.getOrNull(0)?.takeIf { it.visible }
        val bottomLine = if (gNext) null else pp.borders.getOrNull(2)?.takeIf { it.visible }
        return ParaOut(lines, b, a, pp, pp.keepNext, pp.keep, pp.pageBreakBefore, pp.borders, pp.shading?.resolve(), pp.indLeft / 20f, pp.indRight / 20f, topLine, bottomLine)
    }

    private fun measureBlocks(blocks: List<WBlock>, width: Float): Float = placeBlocks(blocks, 0f, width, 0f, ArrayList())

    private fun isFramed(b: WBlock?): Boolean = b is WParagraph && b.props.frame && !b.props.inTable && b.props.itap == 0

    private class FrameBox(val items: ArrayList<LItem>, val w: Float, val h: Float, val props: ParaProps)

    private fun frameGroup(blocks: List<WBlock>, start: Int): List<WParagraph> {
        val first = blocks[start] as WParagraph
        val key = first.props.frameKey()
        val out = ArrayList<WParagraph>()
        var i = start
        while (i < blocks.size && isFramed(blocks[i]) && (blocks[i] as WParagraph).props.frameKey() == key) { out.add(blocks[i] as WParagraph); i++ }
        return out
    }

    private fun buildFrame(group: List<WParagraph>, availW: Float): FrameBox {
        val pp = group[0].props
        val fixed = pp.frameW > 0
        var w = if (fixed) pp.frameW / 20f else availW
        var items = ArrayList<LItem>()
        var h = 0f
        if (!fixed) {
            val probe = ArrayList<LItem>()
            val left = group.map { g -> WParagraph(g.props.copy().also { it.jc = 0 }, g.markProps, g.inlines).also { c -> c.listLabel = g.listLabel; c.listLabelProps = g.listLabelProps; c.listFollow = g.listFollow } }
            placeBlocks(left, 0f, w, 0f, probe, false)
            var maxR = 0f
            for (it in probe) when (it) {
                is LItem.Text -> if (it.text.isNotBlank()) maxR = maxOf(maxR, it.x + it.width)
                is LItem.Image -> maxR = maxOf(maxR, it.x + it.w)
                is LItem.PageField -> maxR = maxOf(maxR, it.x + it.width)
                else -> {}
            }
            w = minOf(availW, maxR + group.maxOf { it.props.indRight / 20f }).coerceAtLeast(1f)
        }
        h = placeBlocks(group, 0f, w, 0f, items, false)
        if (pp.frameH > 0) h = if (pp.frameMinH) maxOf(h, pp.frameH / 20f) else pp.frameH / 20f
        return FrameBox(items, w, h, pp)
    }

    private fun frameX(fb: FrameBox, refX: Float, refW: Float): Float = when (fb.props.frameX) {
        0 -> refX
        -4 -> refX + (refW - fb.w) / 2f
        -8, -16 -> refX + refW - fb.w
        -12 -> refX
        else -> refX + (fb.props.frameX - 1) / 20f
    }

    private fun frameY(fb: FrameBox, anchor: Float, refTop: Float, refH: Float): Float {
        val pcVert = (fb.props.framePc ushr 4) and 3
        val base = if (pcVert == 2) anchor else refTop
        return when (fb.props.frameY) {
            0 -> anchor
            -4 -> if (pcVert == 2) anchor else refTop
            -8 -> if (pcVert == 2) anchor else refTop + (refH - fb.h) / 2f
            -12 -> if (pcVert == 2) anchor else refTop + refH - fb.h
            -16, -20 -> base
            else -> base + (fb.props.frameY - 1) / 20f
        }
    }

    private fun placeBlocks(blocks: List<WBlock>, x: Float, width: Float, y0: Float, out: ArrayList<LItem>, frames: Boolean = true, breaks: MutableList<Float>? = null): Float {
        var y = y0
        var lastAfter = 0f
        var skipTo = -1
        for ((i, b) in blocks.withIndex()) {
            if (i < skipTo) continue
            if (frames && isFramed(b)) {
                val group = frameGroup(blocks, i)
                skipTo = i + group.size
                val fb = buildFrame(group, width)
                val fx = if (fb.props.dropCap == 2) x - fb.w - fb.props.frameFromX / 20f else frameX(fb, x, width)
                val fy = frameY(fb, y, y0, 0f)
                for (it in fb.items) out.add(offset(it, fx, fy))
                continue
            }
            when (b) {
                is WParagraph -> {
                    val prev = blocks.getOrNull(i - 1) as? WParagraph
                    val next = blocks.getOrNull(i + 1) as? WParagraph
                    val po = paraOut(b, width, prev, next)
                    var before = po.before
                    if (doc.collapseSpacing && b.props.beforeAuto && i == 0) before = 0f
                    if (doc.collapseSpacing) before -= minOf(before, lastAfter)
                    y += before
                    val top = y
                    y += po.topExtra
                    for (ln in po.lines) {
                        po.shading?.let { out.add(LItem.Rect(x + po.left, y, width - po.left - po.right, ln.height, it)) }
                        for (it in ln.items) out.add(offset(it, x, y))
                        for (a in ln.anchors) {
                            val ax = (if (a.relX == 1) 0f else x) + a.left / 20f
                            val ay = (if (a.relY == 1) 0f else y) + a.top / 20f
                            drawFloating(a, ax, ay, (a.right - a.left) / 20f, (a.bottom - a.top) / 20f, out)
                        }
                        y += ln.height
                        breaks?.add(y)
                    }
                    y += po.bottomExtra
                    paraBorderItems(po, x + po.left, x + width - po.right, top, y, true, true, out)
                    val after = if (doc.collapseSpacing && b.props.afterAuto && i == blocks.lastIndex) 0f else po.after
                    y += after
                    lastAfter = after
                }
                is WTable -> {
                    for (lr in layoutTable(b, width)) {
                        for (it in lr.back) out.add(offset(it, x, y))
                        for (it in lr.items) out.add(offset(it, x, y))
                        y += lr.height
                        breaks?.add(y)
                    }
                    lastAfter = 0f
                }
            }
        }
        return y - y0
    }

    internal class CellPart(
        val x0: Float, val x1: Float, val content: List<LItem>, val breaks: FloatArray, val padTop: Float, val padBottom: Float,
        val top: WBorder?, val left: WBorder?, val bottom: WBorder?, val right: WBorder?, val drawTop: Boolean, val drawBottom: Boolean, val shade: Int?, val placeholder: Boolean
    )

    internal class RowOut(val height: Float, val items: ArrayList<LItem>, val back: ArrayList<LItem>, val parts: List<CellPart> = emptyList(), val canSplit: Boolean = false)

    private fun itemBottom(it: LItem): Float = when (it) {
        is LItem.Text -> it.y
        is LItem.PageField -> it.y
        is LItem.Image -> it.y + it.h
        is LItem.Rect -> it.y + it.h
        is LItem.Line -> maxOf(it.y1, it.y2)
        is LItem.Comment -> it.y + it.h
    }

    private fun assembleRow(parts: List<CellPart>, height: Float, canSplit: Boolean): RowOut {
        val items = ArrayList<LItem>()
        val back = ArrayList<LItem>()
        for (c in parts) {
            c.shade?.let { back.add(LItem.Rect(c.x0, 0f, c.x1 - c.x0, height, it)) }
            items.addAll(c.content)
        }
        for (c in parts) {
            if (c.drawTop) c.top?.takeIf { it.visible }?.let { items.add(LItem.Line(c.x0, 0f, c.x1, 0f, it)) }
            if (c.drawBottom) c.bottom?.takeIf { it.visible }?.let { items.add(LItem.Line(c.x0, height, c.x1, height, it)) }
            c.left?.takeIf { it.visible }?.let { items.add(LItem.Line(c.x0, 0f, c.x0, height, it)) }
            c.right?.takeIf { it.visible }?.let { items.add(LItem.Line(c.x1, 0f, c.x1, height, it)) }
        }
        return RowOut(height, items, back, parts, canSplit)
    }

    private fun splitRow(r: RowOut, avail: Float): Pair<RowOut, RowOut>? {
        if (!r.canSplit || r.parts.isEmpty()) return null
        val cuts = FloatArray(r.parts.size)
        var any = false
        for ((i, c) in r.parts.withIndex()) {
            var cut = 0f
            for (b in c.breaks) if (b + c.padBottom <= avail + 0.01f && b > cut) cut = b
            cuts[i] = cut
            if (cut > 0f && c.content.any { itemBottom(it) <= cut + 0.01f }) any = true
        }
        if (!any) return null
        var h1 = 0f
        for ((i, c) in r.parts.withIndex()) h1 = maxOf(h1, if (cuts[i] > 0f) cuts[i] + c.padBottom else 0f)
        h1 = minOf(maxOf(h1, 1f), avail)
        val first = ArrayList<CellPart>()
        val second = ArrayList<CellPart>()
        var h2 = 0f
        for ((i, c) in r.parts.withIndex()) {
            val cut = cuts[i]
            val a = c.content.filter { itemBottom(it) <= cut + 0.01f }
            val shift = c.padTop - cut
            val b = c.content.filter { itemBottom(it) > cut + 0.01f }.map { offset(it, 0f, shift) }
            val nb = c.breaks.filter { it > cut + 0.01f }.map { it + shift }.toFloatArray()
            first.add(CellPart(c.x0, c.x1, a, c.breaks.filter { it <= cut + 0.01f }.toFloatArray(), c.padTop, c.padBottom, c.top, c.left, c.bottom, c.right, c.drawTop, true, c.shade, c.placeholder))
            second.add(CellPart(c.x0, c.x1, b, nb, c.padTop, c.padBottom, c.top, c.left, c.bottom, c.right, true, c.drawBottom, c.shade, c.placeholder))
            val rest = (b.maxOfOrNull { itemBottom(it) } ?: 0f)
            val lastBreak = nb.maxOrNull() ?: 0f
            h2 = maxOf(h2, maxOf(rest, lastBreak) + c.padBottom)
        }
        h2 = maxOf(h2, r.height - h1).coerceAtLeast(1f)
        return assembleRow(first, h1, false) to assembleRow(second, h2, true)
    }

    private fun cellPad(tp: TableProps, ci: Int, side: Int): Float {
        val v = tp.cellPadding.getOrNull(ci)?.getOrNull(side)?.takeIf { it >= 0 } ?: tp.defaultPadding[side].takeIf { it >= 0 }
        if (v != null) return v / 20f
        return if (side == 1 || side == 3) tp.gapHalf / 20f else 0f
    }

    private fun rowShift(tp: TableProps, width: Float): Float {
        if (tp.centers.isEmpty()) return 0f
        val total = (tp.centers.last() - tp.centers.first()) / 20f
        return when (tp.jc) { 1 -> (width - total) / 2f - tp.centers.first() / 20f; 2 -> width - total - tp.centers.first() / 20f; else -> 0f }
    }

    private class CellBox(val row: Int, val ci: Int, val span: Int, val x0: Float, val x1: Float, val flags: Int, val content: ArrayList<LItem>, val contentH: Float, val breaks: FloatArray, val padTop: Float, val padBottom: Float) {
        val vCont get() = flags and 0x20 != 0 && flags and 0x40 == 0
        val vStart get() = flags and 0x40 != 0
        val vAlign get() = (flags ushr 7) and 3
    }

    private fun layoutTable(t: WTable, width: Float): List<RowOut> {
        val n = t.rows.size
        val boxes = ArrayList<List<CellBox>>(n)
        val heights = FloatArray(n)
        for ((ri, row) in t.rows.withIndex()) {
            val tp = row.props
            val hasCenters = tp.centers.size >= 2
            val count = if (hasCenters) minOf(row.cells.size, tp.cellCount) else row.cells.size
            val centers = if (hasCenters) tp.centers else IntArray(row.cells.size + 1) { (it * (width * 20f / maxOf(1, row.cells.size))).toInt() }
            val shift = rowShift(tp, width)
            val list = ArrayList<CellBox>()
            var ci = 0
            var maxH = 0f
            var bt = 0f
            var bb = 0f
            val prevTp = t.rows.getOrNull(ri - 1)?.props
            for (k in 0 until count) {
                val cb = tp.cellBorders.getOrNull(k)
                (cb?.getOrNull(0) ?: if (ri == 0) tp.tableBorders[0] else tp.tableBorders[4])?.takeIf { it.visible }?.let { bt = maxOf(bt, it.widthPt) }
                if (ri > 0) prevTp?.cellBorders?.getOrNull(k)?.getOrNull(2)?.takeIf { it.visible }?.let { bt = maxOf(bt, it.widthPt) }
                if (ri == n - 1) (cb?.getOrNull(2) ?: tp.tableBorders[2])?.takeIf { it.visible }?.let { bb = maxOf(bb, it.widthPt) }
            }
            while (ci < count) {
                val flags = tp.cellFlags.getOrElse(ci) { 0 }
                var span = 1
                if (flags and 1 != 0) while (ci + span < count && tp.cellFlags.getOrElse(ci + span) { 0 } and 2 != 0) span++
                val x0 = centers[ci] / 20f + shift
                val x1 = centers[minOf(ci + span, centers.size - 1)] / 20f + shift
                val pl = cellPad(tp, ci, 1); val pr = cellPad(tp, ci, 3); val pt = cellPad(tp, ci, 0); val pb = cellPad(tp, ci, 2)
                val out = ArrayList<LItem>()
                val brk = ArrayList<Float>()
                val cont = flags and 0x20 != 0 && flags and 0x40 == 0
                val cell = row.cells.getOrNull(ci)
                val h = if (cell != null && !cont) placeBlocks(cell.blocks, x0 + pl, maxOf(1f, x1 - x0 - pl - pr), pt + bt, out, true, brk) + pt + pb + bt + bb else 0f
                val box = CellBox(ri, ci, span, x0, x1, flags, out, h, brk.toFloatArray(), pt + bt, pb + bb)
                list.add(box)
                if (!box.vStart && !cont) maxH = maxOf(maxH, h)
                ci += span
            }
            var height = maxH
            if (tp.rowHeight > 0) height = maxOf(height, tp.rowHeight / 20f + bt + bb)
            if (tp.rowHeight < 0) height = -tp.rowHeight / 20f + bt + bb
            if (height <= 0f) height = maxOf(1f, list.filter { it.vStart }.maxOfOrNull { minOf(it.contentH, 14f) } ?: 1f)
            heights[ri] = height
            boxes.add(list)
        }
        val spans = HashMap<CellBox, Int>()
        for (ri in 0 until n) for (b in boxes[ri]) {
            if (!b.vStart) continue
            var last = ri
            while (last + 1 < n && boxes[last + 1].any { it.vCont && Math.abs(it.x0 - b.x0) < 1f }) last++
            spans[b] = last
            var total = 0f
            for (k in ri..last) total += heights[k]
            if (b.contentH > total && t.rows[last].props.rowHeight >= 0) heights[last] += b.contentH - total
        }
        val out = ArrayList<RowOut>(n)
        for (ri in 0 until n) {
            val tp = t.rows[ri].props
            val height = heights[ri]
            val tb = tp.tableBorders
            val list = boxes[ri]
            val count = list.lastOrNull()?.let { it.ci + it.span } ?: 0
            val parts = ArrayList<CellPart>()
            for (b in list) {
                val ci = b.ci
                var content: List<LItem> = emptyList()
                var breaks = FloatArray(0)
                if (!b.vCont) {
                    val last = spans[b] ?: ri
                    var total = 0f
                    for (k in ri..last) total += heights[k]
                    val dy = maxOf(0f, when (b.vAlign) { 1 -> (total - b.contentH) / 2f; 2 -> total - b.contentH; else -> 0f })
                    content = if (dy == 0f) b.content else b.content.map { offset(it, 0f, dy) }
                    breaks = if (dy == 0f) b.breaks else FloatArray(b.breaks.size) { b.breaks[it] + dy }
                }
                val cb = tp.cellBorders.getOrNull(ci)
                val nextCont = boxes.getOrNull(ri + 1)?.any { it.vCont && Math.abs(it.x0 - b.x0) < 1f } == true
                fun pick(k: Int, fallback: WBorder?): WBorder? = cb?.getOrNull(k) ?: fallback
                parts.add(CellPart(
                    b.x0, b.x1, content, breaks, b.padTop, b.padBottom,
                    pick(0, if (ri == 0) tb[0] else tb[4]), pick(1, if (ci == 0) tb[1] else tb[5]),
                    pick(2, if (ri == n - 1) tb[2] else tb[4]), pick(3, if (ci + b.span >= count) tb[3] else tb[5]),
                    !b.vCont, !nextCont, tp.cellShading.getOrNull(ci)?.resolve() ?: tp.tableShading?.resolve(), b.vCont
                ))
            }
            val vmergeOpen = list.any { it.vStart && (spans[it] ?: ri) > ri }
            out.add(assembleRow(parts, height, !tp.cantSplit && !vmergeOpen))
        }
        return out
    }

    private var fieldPage = 0
    private var fieldPageFormat = 0
    private var fieldSection = -1
    private var knownTotal = 0
    private var knownSectionPages = IntArray(0)

    private fun fieldSample(s: Seg): String = when (s.field) {
        WInline.FIELD_PAGE -> if (fieldPage > 0) WordRender.formatPage(fieldPage, if (s.fieldFormat == 0) (if (fieldPageFormat in 1..4) fieldPageFormat else 0) else s.fieldFormat) else "0"
        WInline.FIELD_SECTIONPAGES -> knownSectionPages.getOrNull(fieldSection)?.takeIf { it > 0 }?.let { WordRender.formatPage(it, s.fieldFormat) } ?: "00"
        else -> if (knownTotal > 0) WordRender.formatPage(knownTotal, s.fieldFormat) else "00"
    }

    fun layout(): List<LPage> {
        knownTotal = 0
        knownSectionPages = IntArray(0)
        runLayout()
        if (pages.any { p -> p.items.any { it is LItem.PageField && it.kind != WInline.FIELD_PAGE } || p.back.any { it is LItem.PageField && it.kind != WInline.FIELD_PAGE } }) {
            knownTotal = pages.size
            knownSectionPages = IntArray(doc.sections.size) { si -> pages.count { it.sectionIndex == si } }
            runLayout()
        }
        return pages
    }

    private fun runLayout() {
        pages.clear()
        val flow = Flow()
        for ((si, s) in doc.sections.withIndex()) {
            flow.startSection(s, si)
            val blocks = s.blocks
            var skipTo = -1
            for ((i, b) in blocks.withIndex()) {
                if (i < skipTo) continue
                if (isFramed(b)) {
                    val group = frameGroup(blocks, i)
                    skipTo = i + group.size
                    flow.placeFrame(buildFrame(group, flow.colW), blocks.getOrNull(skipTo) as? WParagraph)
                    continue
                }
                when (b) {
                    is WParagraph -> {
                        val prev = blocks.getOrNull(i - 1) as? WParagraph
                        val next = blocks.getOrNull(i + 1) as? WParagraph
                        val bf = paraSpacing(b, prev, next).first
                        val po = paraOut(b, flow.colW, prev, next) { y, h -> flow.exclusionFor(flow.y + bf + y, h) }
                        if (po.keepNext && next != null && !flow.firstOnPage) {
                            var need = po.before + po.height + po.after
                            var j = i + 1
                            var prevP: WParagraph = b
                            while (true) {
                                val cur = blocks.getOrNull(j) as? WParagraph ?: break
                                if (isFramed(cur)) break
                                val np = paraOut(cur, flow.colW, prevP, blocks.getOrNull(j + 1) as? WParagraph)
                                if (cur.props.keepNext && blocks.getOrNull(j + 1) is WParagraph && j - i < 32) {
                                    need += np.before + np.height + np.after
                                    prevP = cur
                                    j++
                                    continue
                                }
                                need += np.before + np.topExtra + (np.lines.firstOrNull()?.height ?: 0f) + (if (np.lines.size >= 2 && cur.props.widowControl) np.lines[1].height else 0f)
                                break
                            }
                            if (flow.y + need > flow.bottom - flow.footReserve && need <= flow.bottom - flow.top) flow.nextColumn()
                        }
                        val hang = i == blocks.lastIndex && (doc.sections.getOrNull(si + 1)?.props?.breakCode ?: 0) != 0 && b.inlines.all { it is WInline.Text && it.text.isBlank() }
                        flow.placeParagraph(po, b, hang)
                    }
                    is WTable -> flow.placeTable(b)
                }
            }
        }
        if (pages.isNotEmpty()) flow.placeEndnotes()
        flow.finishPage()
        if (pages.isEmpty()) pages.add(LPage(612f, 792f, 1, 0, 0))
        for (si in doc.sections.indices) {
            val count = pages.count { it.sectionIndex == si }
            for (p in pages) if (p.sectionIndex == si) p.sectionPageCount = count
        }
    }
}
