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
 * Portions ported and adapted from resvg, usvg, svgtypes, simplecss, roxmltree, and kurbo
 * (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors, and
 * Copyright (c) 2018 The Kurbo Authors / Raph Levien (Apache-2.0 OR MIT),
 * licensed under MPL-2.0 / Apache-2.0 / MIT.
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.hlebkukleuski.fullfilestudio.core.document.svg

import kotlin.math.roundToInt

internal class SvgSyntaxError : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

internal data class SvgLength(val number: Double, val unit: Int) {
    companion object {
        const val NONE = 0
        const val EM = 1
        const val EX = 2
        const val PX = 3
        const val IN = 4
        const val CM = 5
        const val MM = 6
        const val PT = 7
        const val PC = 8
        const val PERCENT = 9
        val ZERO = SvgLength(0.0, NONE)
        fun num(n: Double) = SvgLength(n, NONE)
        fun pct(n: Double) = SvgLength(n, PERCENT)
    }
}

internal data class SvgColor(val r: Int, val g: Int, val b: Int, val a: Int) {
    companion object {
        val BLACK = SvgColor(0, 0, 0, 255)
        val WHITE = SvgColor(255, 255, 255, 255)
    }
}

internal sealed class SvgPaintValue {
    object None : SvgPaintValue()
    object Inherit : SvgPaintValue()
    object CurrentColor : SvgPaintValue()
    object ContextFill : SvgPaintValue()
    object ContextStroke : SvgPaintValue()
    class Color(val color: SvgColor) : SvgPaintValue()
    class FuncIri(val id: String, val fallback: SvgPaintValue?) : SvgPaintValue()
}

internal sealed class SvgFilterValue {
    class Blur(val stdDev: SvgLength) : SvgFilterValue()
    class DropShadow(val color: SvgColor?, val dx: SvgLength, val dy: SvgLength, val stdDev: SvgLength) : SvgFilterValue()
    class Brightness(val v: Double) : SvgFilterValue()
    class Contrast(val v: Double) : SvgFilterValue()
    class Grayscale(val v: Double) : SvgFilterValue()
    class HueRotate(val degrees: Double) : SvgFilterValue()
    class Invert(val v: Double) : SvgFilterValue()
    class Opacity(val v: Double) : SvgFilterValue()
    class Sepia(val v: Double) : SvgFilterValue()
    class Saturate(val v: Double) : SvgFilterValue()
    class Url(val id: String) : SvgFilterValue()
}

internal sealed class SvgPathSeg {
    class MoveTo(val x: Double, val y: Double) : SvgPathSeg()
    class LineTo(val x: Double, val y: Double) : SvgPathSeg()
    class QuadTo(val x1: Double, val y1: Double, val x: Double, val y: Double) : SvgPathSeg()
    class CubicTo(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : SvgPathSeg()
    object Close : SvgPathSeg()
}

internal class SvgAspect(val align: Int, val slice: Boolean) {
    companion object {
        const val NONE = 0
        const val X_MIN_Y_MIN = 1
        const val X_MID_Y_MIN = 2
        const val X_MAX_Y_MIN = 3
        const val X_MIN_Y_MID = 4
        const val X_MID_Y_MID = 5
        const val X_MAX_Y_MID = 6
        const val X_MIN_Y_MAX = 7
        const val X_MID_Y_MAX = 8
        const val X_MAX_Y_MAX = 9
        val DEFAULT = SvgAspect(X_MID_Y_MID, false)
    }
}

internal class SvgFontShorthand(val style: String?, val variant: String?, val weight: String?, val stretch: String?, val size: String, val family: String)

internal class SvgStream(val text: String, var pos: Int = 0) {
    fun copy() = SvgStream(text, pos)
    fun atEnd() = pos >= text.length
    fun curr(): Char { if (atEnd()) throw SvgSyntaxError(); return text[pos] }
    fun currOrNull(): Char? = if (atEnd()) null else text[pos]
    fun isCurr(c: Char) = !atEnd() && text[pos] == c
    fun next(): Char { if (pos + 1 >= text.length) throw SvgSyntaxError(); return text[pos + 1] }
    fun advance(n: Int) { pos += n }
    fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    fun skipSpaces() { while (!atEnd() && isSpace(text[pos])) pos++ }
    fun startsWith(s: String) = text.startsWith(s, pos)
    fun consume(c: Char) { if (curr() != c) throw SvgSyntaxError(); pos++ }
    fun consumeString(s: String) { if (atEnd() || !startsWith(s)) throw SvgSyntaxError(); pos += s.length }
    fun sliceBack(start: Int) = text.substring(start, pos)
    fun tail() = text.substring(pos)
    inline fun consumeWhile(f: (Char) -> Boolean): String { val s = pos; while (!atEnd() && f(text[pos])) pos++; return text.substring(s, pos) }
    fun consumeAsciiIdent() = consumeWhile { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '-' || it == '_' }
    fun listSeparator() { if (isCurr(',')) pos++ }

    private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || isNonAscii(c)
    private fun isNameChar(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || isNonAscii(c)
    private fun isNonAscii(c: Char) = c == '·' || c in 'À'..'Ö' || c in 'Ø'..'ö' || c in 'ø'..'ͽ' ||
        c in 'Ϳ'..'῿' || c == '‌' || c == '‍' || c == '‿' || c == '⁀' || c in '⁰'..'↏' ||
        c in 'Ⰰ'..'⿯' || c in '、'..'퟿' || c in '豈'..'﷏' || c in 'ﷰ'..'�' || Character.isSurrogate(c)

    fun parseIdent(): String {
        val start = pos
        if (isCurr('-')) pos++
        if (!atEnd()) { if (isNameStart(text[pos])) pos++ else throw SvgSyntaxError() }
        while (!atEnd() && isNameChar(text[pos])) pos++
        if (start == pos) throw SvgSyntaxError()
        return text.substring(start, pos)
    }

    fun parseQuoted(): String {
        val q = curr()
        if (q != '\'' && q != '"') throw SvgSyntaxError()
        var prev = q
        pos++
        val start = pos
        while (!atEnd()) {
            val c = text[pos]
            if (c == q && prev != '\\') break
            prev = c
            pos++
        }
        val v = text.substring(start, pos)
        consume(q)
        return v
    }

    fun parseNumber(): Double {
        skipSpaces()
        if (atEnd()) throw SvgSyntaxError()
        val start = pos
        var c = curr()
        if (c == '+' || c == '-') { pos++; c = curr() }
        when (c) {
            in '0'..'9' -> while (!atEnd() && text[pos] in '0'..'9') pos++
            '.' -> {}
            else -> throw SvgSyntaxError()
        }
        if (isCurr('.')) { pos++; while (!atEnd() && text[pos] in '0'..'9') pos++ }
        if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
            val c2 = next()
            if (c2 != 'm' && c2 != 'x') {
                pos++
                when (curr()) {
                    '+', '-' -> { pos++; while (!atEnd() && text[pos] in '0'..'9') pos++ }
                    in '0'..'9' -> while (!atEnd() && text[pos] in '0'..'9') pos++
                    else -> throw SvgSyntaxError()
                }
            }
        }
        val s = text.substring(start, pos)
        val n = s.toDoubleOrNull() ?: (if (s.endsWith(".")) s.dropLast(1).toDoubleOrNull() else null) ?: (if (s.startsWith(".") || s.startsWith("-.") || s.startsWith("+.")) s.replace(".", "0.").toDoubleOrNull() else null) ?: throw SvgSyntaxError()
        if (!n.isFinite()) throw SvgSyntaxError()
        return n
    }

    fun parseListNumber(): Double {
        if (atEnd()) throw SvgSyntaxError()
        val n = parseNumber()
        skipSpaces()
        listSeparator()
        return n
    }

    fun parseNumberOrPercent(): Double {
        skipSpaces()
        val n = parseNumber()
        return if (startsWith("%")) { pos++; n / 100.0 } else n
    }

    fun parseListNumberOrPercent(): Double {
        if (atEnd()) throw SvgSyntaxError()
        val l = parseNumberOrPercent()
        skipSpaces()
        listSeparator()
        return l
    }

    fun parseLength(): SvgLength {
        skipSpaces()
        val n = parseNumber()
        if (atEnd()) return SvgLength(n, SvgLength.NONE)
        val u = when {
            startsWith("%") -> SvgLength.PERCENT
            startsWith("em") -> SvgLength.EM
            startsWith("ex") -> SvgLength.EX
            startsWith("px") -> SvgLength.PX
            startsWith("in") -> SvgLength.IN
            startsWith("cm") -> SvgLength.CM
            startsWith("mm") -> SvgLength.MM
            startsWith("pt") -> SvgLength.PT
            startsWith("pc") -> SvgLength.PC
            else -> SvgLength.NONE
        }
        when (u) {
            SvgLength.PERCENT -> pos += 1
            SvgLength.NONE -> {}
            else -> pos += 2
        }
        return SvgLength(n, u)
    }

    fun parseListLength(): SvgLength {
        if (atEnd()) throw SvgSyntaxError()
        val l = parseLength()
        skipSpaces()
        listSeparator()
        return l
    }

    fun parseAngle(): Double {
        skipSpaces()
        val n = parseNumber()
        if (atEnd()) return n
        return when {
            startsWith("deg") -> { pos += 3; n }
            startsWith("grad") -> { pos += 4; n * 180.0 / 200.0 }
            startsWith("rad") -> { pos += 3; Math.toDegrees(n) }
            startsWith("turn") -> { pos += 4; n * 360.0 }
            else -> n
        }
    }

    fun parseListAngle(): Double {
        if (atEnd()) throw SvgSyntaxError()
        val a = parseAngle()
        skipSpaces()
        listSeparator()
        return a
    }

    fun parseIri(): String {
        skipSpaces()
        consume('#')
        val link = consumeWhile { it != ' ' }
        if (link.isEmpty()) throw SvgSyntaxError()
        return link
    }

    fun parseFuncIri(): String {
        skipSpaces()
        consumeString("url(")
        skipSpaces()
        val quote = currOrNull()?.takeIf { it == '\'' || it == '"' }
        if (quote != null) { pos++; skipSpaces() }
        consume('#')
        val link = if (quote != null) consumeWhile { it != quote }.trimEnd() else consumeWhile { it != ' ' && it != ')' }
        if (link.isEmpty() || link.contains('\'') || link.contains('"')) throw SvgSyntaxError()
        skipSpaces()
        if (quote != null) { consume(quote); skipSpaces() }
        consume(')')
        return link
    }

    fun parseColor(): SvgColor {
        skipSpaces()
        var r = 0; var g = 0; var b = 0; var a = 255
        if (curr() == '#') {
            pos++
            val hex = consumeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            fun h(c: Char) = Character.digit(c, 16)
            when (hex.length) {
                6 -> { r = h(hex[0]) * 16 + h(hex[1]); g = h(hex[2]) * 16 + h(hex[3]); b = h(hex[4]) * 16 + h(hex[5]) }
                8 -> { r = h(hex[0]) * 16 + h(hex[1]); g = h(hex[2]) * 16 + h(hex[3]); b = h(hex[4]) * 16 + h(hex[5]); a = h(hex[6]) * 16 + h(hex[7]) }
                3 -> { r = h(hex[0]) * 17; g = h(hex[1]) * 17; b = h(hex[2]) * 17 }
                4 -> { r = h(hex[0]) * 17; g = h(hex[1]) * 17; b = h(hex[2]) * 17; a = h(hex[3]) * 17 }
                else -> throw SvgSyntaxError()
            }
            return SvgColor(r, g, b, a)
        }
        val name = consumeAsciiIdent().lowercase()
        fun u8(v: Double): Int = v.roundHalfAway().coerceIn(0.0, 255.0).toInt()
        when (name) {
            "rgb", "rgba" -> {
                consume('(')
                var isPercent = false
                val value = parseNumber()
                if (startsWith("%")) { pos++; isPercent = true }
                skipSpaces()
                listSeparator()
                if (isPercent) {
                    r = u8(value / 100.0 * 255.0)
                    g = u8(parseListNumberOrPercent() * 255.0)
                    b = u8(parseListNumberOrPercent() * 255.0)
                } else {
                    r = u8(value)
                    g = u8(parseListNumber())
                    b = u8(parseListNumber())
                }
                skipSpaces()
                if (!startsWith(")")) {
                    if (isCurr('/')) { pos++; skipSpaces() }
                    a = u8(parseListNumber() * 255.0)
                }
                skipSpaces()
                consume(')')
            }
            "hsl", "hsla" -> {
                consume('(')
                var hue = parseListAngle()
                hue = ((hue % 360.0) + 360.0) % 360.0
                val sat = parseListNumberOrPercent().coerceIn(0.0, 1.0)
                val light = parseListNumberOrPercent().coerceIn(0.0, 1.0)
                val rgb = hslToRgb((hue / 60.0).toFloat(), sat.toFloat(), light.toFloat())
                r = rgb[0]; g = rgb[1]; b = rgb[2]
                skipSpaces()
                if (!startsWith(")")) {
                    if (isCurr('/')) { pos++; skipSpaces() }
                    a = u8(parseListNumberOrPercent() * 255.0)
                }
                skipSpaces()
                consume(')')
            }
            "hwb" -> {
                consume('(')
                var hue = parseAngle()
                hue = ((hue % 360.0) + 360.0) % 360.0
                fun comp(): Double { val n = parseNumber(); if (startsWith("%")) pos++; return n / 100.0 }
                val white = comp().coerceIn(0.0, 1.0).toFloat()
                val black = comp().coerceIn(0.0, 1.0).toFloat()
                val rgb = if (white + black >= 1f) { val gr = white / (white + black); floatArrayOf(gr, gr, gr) } else {
                    val f = 1f - white - black
                    val c = hslToRgbF((hue / 60.0).toFloat(), 1f, 0.5f)
                    floatArrayOf(c[0] * f + white, c[1] * f + white, c[2] * f + white)
                }
                r = (rgb[0] * 255f).roundHalfAway(); g = (rgb[1] * 255f).roundHalfAway(); b = (rgb[2] * 255f).roundHalfAway()
                skipSpaces()
                if (isCurr('/')) { pos++; a = u8(parseNumberOrPercent() * 255.0) }
                skipSpaces()
                consume(')')
            }
            else -> {
                val c = SvgSyntax.NAMED_COLORS[name] ?: throw SvgSyntaxError()
                return SvgColor((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF, (c ushr 24) and 0xFF)
            }
        }
        return SvgColor(r, g, b, a)
    }

    fun tryParseColor(): SvgColor? {
        val save = pos
        return try { parseColor() } catch (e: SvgSyntaxError) { pos = save; null }
    }

    private fun hslToRgbF(hue: Float, s: Float, l: Float): FloatArray {
        val t2 = if (l <= 0.5f) l * (s + 1f) else l + s - l * s
        val t1 = l * 2f - t2
        fun h2r(hIn: Float): Float {
            var h = hIn
            if (h < 0f) h += 6f
            if (h >= 6f) h -= 6f
            return if (h < 1f) (t2 - t1) * h + t1 else if (h < 3f) t2 else if (h < 4f) (t2 - t1) * (4f - h) + t1 else t1
        }
        return floatArrayOf(h2r(hue + 2f), h2r(hue), h2r(hue - 2f))
    }

    private fun hslToRgb(hue: Float, s: Float, l: Float): IntArray {
        val c = hslToRgbF(hue, s, l)
        return intArrayOf((c[0] * 255f).roundHalfAway(), (c[1] * 255f).roundHalfAway(), (c[2] * 255f).roundHalfAway())
    }
}

internal fun Double.roundHalfAway(): Double = if (this < 0) -Math.floor(-this + 0.5) else Math.floor(this + 0.5)
internal fun Float.roundHalfAway(): Int = (if (this < 0) -Math.floor(-this + 0.5) else Math.floor(this + 0.5)).toInt().coerceIn(0, 255)

internal object SvgSyntax {
    val NAMED_COLORS: Map<String, Int> = hashMapOf(
        "lightgrey" to 0xFFD3D3D3.toInt(),
        "lavenderblush" to 0xFFFFF0F5.toInt(),
        "deeppink" to 0xFFFF1493.toInt(),
        "seashell" to 0xFFFFF5EE.toInt(),
        "lightsalmon" to 0xFFFFA07A.toInt(),
        "green" to 0xFF008000.toInt(),
        "lightgreen" to 0xFF90EE90.toInt(),
        "black" to 0xFF000000.toInt(),
        "deepskyblue" to 0xFF00BFFF.toInt(),
        "mistyrose" to 0xFFFFE4E1.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "dimgray" to 0xFF696969.toInt(),
        "navajowhite" to 0xFFFFDEAD.toInt(),
        "royalblue" to 0xFF4169E1.toInt(),
        "peru" to 0xFFCD853F.toInt(),
        "darkgrey" to 0xFFA9A9A9.toInt(),
        "steelblue" to 0xFF4682B4.toInt(),
        "teal" to 0xFF008080.toInt(),
        "orangered" to 0xFFFF4500.toInt(),
        "mediumslateblue" to 0xFF7B68EE.toInt(),
        "blueviolet" to 0xFF8A2BE2.toInt(),
        "cornflowerblue" to 0xFF6495ED.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "beige" to 0xFFF5F5DC.toInt(),
        "goldenrod" to 0xFFDAA520.toInt(),
        "rosybrown" to 0xFFBC8F8F.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "darkblue" to 0xFF00008B.toInt(),
        "aliceblue" to 0xFFF0F8FF.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "mediumblue" to 0xFF0000CD.toInt(),
        "dodgerblue" to 0xFF1E90FF.toInt(),
        "limegreen" to 0xFF32CD32.toInt(),
        "purple" to 0xFF800080.toInt(),
        "lightsteelblue" to 0xFFB0C4DE.toInt(),
        "lightslategray" to 0xFF778899.toInt(),
        "seagreen" to 0xFF2E8B57.toInt(),
        "mediumvioletred" to 0xFFC71585.toInt(),
        "slategrey" to 0xFF708090.toInt(),
        "darkslategrey" to 0xFF2F4F4F.toInt(),
        "turquoise" to 0xFF40E0D0.toInt(),
        "paleturquoise" to 0xFFAFEEEE.toInt(),
        "lightgoldenrodyellow" to 0xFFFAFAD2.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "darkseagreen" to 0xFF8FBC8F.toInt(),
        "lightcyan" to 0xFFE0FFFF.toInt(),
        "lightcoral" to 0xFFF08080.toInt(),
        "mediumseagreen" to 0xFF3CB371.toInt(),
        "palegoldenrod" to 0xFFEEE8AA.toInt(),
        "palegreen" to 0xFF98FB98.toInt(),
        "darkslateblue" to 0xFF483D8B.toInt(),
        "moccasin" to 0xFFFFE4B5.toInt(),
        "forestgreen" to 0xFF228B22.toInt(),
        "darkkhaki" to 0xFFBDB76B.toInt(),
        "chartreuse" to 0xFF7FFF00.toInt(),
        "floralwhite" to 0xFFFFFAF0.toInt(),
        "snow" to 0xFFFFFAFA.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(),
        "orchid" to 0xFFDA70D6.toInt(),
        "darkorchid" to 0xFF9932CC.toInt(),
        "darkred" to 0xFF8B0000.toInt(),
        "darksalmon" to 0xFFE9967A.toInt(),
        "crimson" to 0xFFDC143C.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "palevioletred" to 0xFFDB7093.toInt(),
        "lightseagreen" to 0xFF20B2AA.toInt(),
        "ivory" to 0xFFFFFFF0.toInt(),
        "powderblue" to 0xFFB0E0E6.toInt(),
        "aquamarine" to 0xFF7FFFD4.toInt(),
        "darkturquoise" to 0xFF00CED1.toInt(),
        "lavender" to 0xFFE6E6FA.toInt(),
        "azure" to 0xFFF0FFFF.toInt(),
        "mediumturquoise" to 0xFF48D1CC.toInt(),
        "lightgray" to 0xFFD3D3D3.toInt(),
        "transparent" to 0x00000000.toInt(),
        "gainsboro" to 0xFFDCDCDC.toInt(),
        "olivedrab" to 0xFF6B8E23.toInt(),
        "papayawhip" to 0xFFFFEFD5.toInt(),
        "tomato" to 0xFFFF6347.toInt(),
        "midnightblue" to 0xFF191970.toInt(),
        "pink" to 0xFFFFC0CB.toInt(),
        "yellowgreen" to 0xFF9ACD32.toInt(),
        "slategray" to 0xFF708090.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "indigo" to 0xFF4B0082.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "grey" to 0xFF808080.toInt(),
        "wheat" to 0xFFF5DEB3.toInt(),
        "darkgoldenrod" to 0xFFB8860B.toInt(),
        "lawngreen" to 0xFF7CFC00.toInt(),
        "lightslategrey" to 0xFF778899.toInt(),
        "burlywood" to 0xFFDEB887.toInt(),
        "aqua" to 0xFF00FFFF.toInt(),
        "saddlebrown" to 0xFF8B4513.toInt(),
        "oldlace" to 0xFFFDF5E6.toInt(),
        "lightskyblue" to 0xFF87CEFA.toInt(),
        "violet" to 0xFFEE82EE.toInt(),
        "dimgrey" to 0xFF696969.toInt(),
        "darkorange" to 0xFFFF8C00.toInt(),
        "lightblue" to 0xFFADD8E6.toInt(),
        "khaki" to 0xFFF0E68C.toInt(),
        "coral" to 0xFFFF7F50.toInt(),
        "brown" to 0xFFA52A2A.toInt(),
        "mediumpurple" to 0xFF9370DB.toInt(),
        "linen" to 0xFFFAF0E6.toInt(),
        "mediumorchid" to 0xFFBA55D3.toInt(),
        "indianred" to 0xFFCD5C5C.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "firebrick" to 0xFFB22222.toInt(),
        "skyblue" to 0xFF87CEEB.toInt(),
        "darkgray" to 0xFFA9A9A9.toInt(),
        "hotpink" to 0xFFFF69B4.toInt(),
        "olive" to 0xFF808000.toInt(),
        "sienna" to 0xFFA0522D.toInt(),
        "cadetblue" to 0xFF5F9EA0.toInt(),
        "darkslategray" to 0xFF2F4F4F.toInt(),
        "slateblue" to 0xFF6A5ACD.toInt(),
        "plum" to 0xFFDDA0DD.toInt(),
        "mediumspringgreen" to 0xFF00FA9A.toInt(),
        "thistle" to 0xFFD8BFD8.toInt(),
        "mintcream" to 0xFFF5FFFA.toInt(),
        "darkmagenta" to 0xFF8B008B.toInt(),
        "lemonchiffon" to 0xFFFFFACD.toInt(),
        "bisque" to 0xFFFFE4C4.toInt(),
        "antiquewhite" to 0xFFFAEBD7.toInt(),
        "darkgreen" to 0xFF006400.toInt(),
        "whitesmoke" to 0xFFF5F5F5.toInt(),
        "lightpink" to 0xFFFFB6C1.toInt(),
        "darkcyan" to 0xFF008B8B.toInt(),
        "tan" to 0xFFD2B48C.toInt(),
        "blanchedalmond" to 0xFFFFEBCD.toInt(),
        "honeydew" to 0xFFF0FFF0.toInt(),
        "salmon" to 0xFFFA8072.toInt(),
        "lightyellow" to 0xFFFFFFE0.toInt(),
        "springgreen" to 0xFF00FF7F.toInt(),
        "cornsilk" to 0xFFFFF8DC.toInt(),
        "sandybrown" to 0xFFF4A460.toInt(),
        "mediumaquamarine" to 0xFF66CDAA.toInt(),
        "darkviolet" to 0xFF9400D3.toInt(),
        "darkolivegreen" to 0xFF556B2F.toInt(),
        "gold" to 0xFFFFD700.toInt(),
        "peachpuff" to 0xFFFFDAB9.toInt(),
        "greenyellow" to 0xFFADFF2F.toInt(),
        "gray" to 0xFF808080.toInt(),
        "navy" to 0xFF000080.toInt(),
        "ghostwhite" to 0xFFF8F8FF.toInt(),
        "chocolate" to 0xFFD2691E.toInt()
    )

    private inline fun <T> full(text: String, trailingSpaces: Boolean = true, f: (SvgStream) -> T): T? = try {
        val s = SvgStream(text)
        val v = f(s)
        if (trailingSpaces) s.skipSpaces()
        if (!s.atEnd()) null else v
    } catch (e: SvgSyntaxError) { null }

    fun number(text: String): Double? = full(text) { it.parseNumber() }
    fun length(text: String): SvgLength? = full(text, false) { it.parseLength() }
    fun angle(text: String): Double? = full(text, false) { it.parseAngle() }
    fun color(text: String): SvgColor? = full(text) { it.parseColor() }
    fun iri(text: String): String? = full(text) { it.parseIri() }
    fun funcIri(text: String): String? = full(text) { it.parseFuncIri() }

    fun numberList(text: String): List<Double>? {
        val s = SvgStream(text)
        val out = ArrayList<Double>()
        while (!s.atEnd()) out.add(try { s.parseListNumber() } catch (e: SvgSyntaxError) { return null })
        return out
    }

    fun numberListPrefix(text: String): List<Double> {
        val s = SvgStream(text)
        val out = ArrayList<Double>()
        while (!s.atEnd()) out.add(try { s.parseListNumber() } catch (e: SvgSyntaxError) { return out })
        return out
    }

    fun lengthList(text: String): List<SvgLength>? {
        val s = SvgStream(text)
        val out = ArrayList<SvgLength>()
        while (!s.atEnd()) out.add(try { s.parseListLength() } catch (e: SvgSyntaxError) { return null })
        return out
    }

    fun lengthListPrefix(text: String): List<SvgLength> {
        val s = SvgStream(text)
        val out = ArrayList<SvgLength>()
        while (!s.atEnd()) out.add(try { s.parseListLength() } catch (e: SvgSyntaxError) { return out })
        return out
    }

    fun points(text: String): List<Double> {
        val s = SvgStream(text)
        val out = ArrayList<Double>()
        while (!s.atEnd()) {
            val x = try { s.parseListNumber() } catch (e: SvgSyntaxError) { break }
            val y = try { s.parseListNumber() } catch (e: SvgSyntaxError) { break }
            out.add(x); out.add(y)
        }
        return out
    }

    fun paint(textIn: String): SvgPaintValue? {
        val text = textIn.trim()
        return when (text) {
            "none" -> SvgPaintValue.None
            "inherit" -> SvgPaintValue.Inherit
            "currentColor" -> SvgPaintValue.CurrentColor
            "context-fill" -> SvgPaintValue.ContextFill
            "context-stroke" -> SvgPaintValue.ContextStroke
            else -> {
                val s = SvgStream(text)
                if (s.startsWith("url(")) {
                    val link = try { s.parseFuncIri() } catch (e: SvgSyntaxError) { return null }
                    s.skipSpaces()
                    if (!s.atEnd()) {
                        when (val fb = s.tail()) {
                            "none" -> SvgPaintValue.FuncIri(link, SvgPaintValue.None)
                            "currentColor" -> SvgPaintValue.FuncIri(link, SvgPaintValue.CurrentColor)
                            else -> SvgPaintValue.FuncIri(link, SvgPaintValue.Color(color(fb) ?: return null))
                        }
                    } else SvgPaintValue.FuncIri(link, null)
                } else color(text)?.let { SvgPaintValue.Color(it) }
            }
        }
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1], a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5]
    )

    fun transform(text: String): DoubleArray? {
        val s = SvgStream(text)
        var ts = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        try {
            while (true) {
                s.skipSpaces()
                if (s.atEnd()) break
                val name = s.consumeAsciiIdent()
                s.skipSpaces()
                s.consume('(')
                val m: List<DoubleArray> = when (name) {
                    "matrix" -> listOf(doubleArrayOf(s.parseListNumber(), s.parseListNumber(), s.parseListNumber(), s.parseListNumber(), s.parseListNumber(), s.parseListNumber()))
                    "translate" -> { val x = s.parseListNumber(); s.skipSpaces(); val y = if (s.isCurr(')')) 0.0 else s.parseListNumber(); listOf(doubleArrayOf(1.0, 0.0, 0.0, 1.0, x, y)) }
                    "scale" -> { val x = s.parseListNumber(); s.skipSpaces(); val y = if (s.isCurr(')')) x else s.parseListNumber(); listOf(doubleArrayOf(x, 0.0, 0.0, y, 0.0, 0.0)) }
                    "rotate" -> {
                        val a = s.parseListNumber()
                        s.skipSpaces()
                        val v = Math.toRadians(a)
                        val rot = doubleArrayOf(Math.cos(v), Math.sin(v), -Math.sin(v), Math.cos(v), 0.0, 0.0)
                        if (!s.isCurr(')')) {
                            val cx = s.parseListNumber(); val cy = s.parseListNumber()
                            listOf(doubleArrayOf(1.0, 0.0, 0.0, 1.0, cx, cy), rot, doubleArrayOf(1.0, 0.0, 0.0, 1.0, -cx, -cy))
                        } else listOf(rot)
                    }
                    "skewX" -> listOf(doubleArrayOf(1.0, 0.0, Math.tan(Math.toRadians(s.parseListNumber())), 1.0, 0.0, 0.0))
                    "skewY" -> listOf(doubleArrayOf(1.0, Math.tan(Math.toRadians(s.parseListNumber())), 0.0, 1.0, 0.0, 0.0))
                    else -> throw SvgSyntaxError()
                }
                s.skipSpaces()
                s.consume(')')
                s.skipSpaces()
                if (s.isCurr(',')) s.advance(1)
                for (x in m) ts = mul(ts, x)
            }
        } catch (e: SvgSyntaxError) { return null }
        return ts
    }

    fun viewBox(text: String): DoubleArray? = try {
        val s = SvgStream(text)
        val v = doubleArrayOf(s.parseListNumber(), s.parseListNumber(), s.parseListNumber(), s.parseListNumber())
        if (v[2] <= 0.0 || v[3] <= 0.0) null else v
    } catch (e: SvgSyntaxError) { null }

    fun aspect(text: String): SvgAspect? = try {
        val s = SvgStream(text)
        s.skipSpaces()
        if (s.startsWith("defer")) { s.advance(5); s.consume(' '); s.skipSpaces() }
        val align = when (s.consumeAsciiIdent()) {
            "none" -> SvgAspect.NONE
            "xMinYMin" -> SvgAspect.X_MIN_Y_MIN
            "xMidYMin" -> SvgAspect.X_MID_Y_MIN
            "xMaxYMin" -> SvgAspect.X_MAX_Y_MIN
            "xMinYMid" -> SvgAspect.X_MIN_Y_MID
            "xMidYMid" -> SvgAspect.X_MID_Y_MID
            "xMaxYMid" -> SvgAspect.X_MAX_Y_MID
            "xMinYMax" -> SvgAspect.X_MIN_Y_MAX
            "xMidYMax" -> SvgAspect.X_MID_Y_MAX
            "xMaxYMax" -> SvgAspect.X_MAX_Y_MAX
            else -> throw SvgSyntaxError()
        }
        s.skipSpaces()
        var slice = false
        if (!s.atEnd()) when (s.consumeAsciiIdent()) { "meet", "" -> {}; "slice" -> slice = true; else -> throw SvgSyntaxError() }
        SvgAspect(align, slice)
    } catch (e: SvgSyntaxError) { null }

    fun paintOrder(text: String): IntArray {
        val def = intArrayOf(0, 1, 2)
        val order = def.copyOf()
        val s = SvgStream(text)
        var count = 0
        while (!s.atEnd() && count < 3) {
            s.skipSpaces()
            val name = s.consumeAsciiIdent()
            s.skipSpaces()
            val k = when (name) { "normal" -> return def; "fill" -> 0; "stroke" -> 1; "markers" -> 2; else -> return def }
            var idx = -1
            for (i in count until 3) if (order[i] == k) { idx = i - count; break }
            if (idx < 0) return def
            val last = order[count + idx]
            for (i in count + idx downTo count + 1) order[i] = order[i - 1]
            order[count] = last
            count++
        }
        s.skipSpaces()
        return if (!s.atEnd()) def else order
    }

    fun transformOrigin(text: String): Array<SvgLength>? {
        val s = SvgStream(text)
        s.skipSpaces()
        if (s.atEnd()) return null
        class Pos(val len: SvgLength, val dir: Int)
        fun part(): Pos? {
            val save = s.pos
            s.skipSpaces()
            val d = when {
                s.startsWith("left") -> { s.advance(4); 1 }
                s.startsWith("right") -> { s.advance(5); 2 }
                s.startsWith("top") -> { s.advance(3); 3 }
                s.startsWith("bottom") -> { s.advance(6); 4 }
                s.startsWith("center") -> { s.advance(6); 5 }
                else -> 0
            }
            if (d != 0) return Pos(when (d) { 1, 3 -> SvgLength.pct(0.0); 2, 4 -> SvgLength.pct(100.0); else -> SvgLength.pct(50.0) }, d)
            s.pos = save
            return try { Pos(s.parseLength(), 0) } catch (e: SvgSyntaxError) { null }
        }
        fun horiz(p: Pos) = p.dir == 0 || p.dir == 5 || p.dir == 1 || p.dir == 2
        fun vert(p: Pos) = p.dir == 0 || p.dir == 5 || p.dir == 3 || p.dir == 4
        val p1 = part() ?: return null
        var p2: Pos? = null
        var z: SvgLength? = null
        if (!s.atEnd()) { s.skipSpaces(); s.listSeparator(); p2 = part() ?: return null }
        if (!s.atEnd()) { s.skipSpaces(); s.listSeparator(); z = try { s.parseLength() } catch (e: SvgSyntaxError) { return null } }
        s.skipSpaces()
        if (!s.atEnd()) return null
        val center = SvgLength.pct(50.0)
        if (p2 == null) return if (horiz(p1)) arrayOf(p1.len, center) else arrayOf(center, p1.len)
        if (z != null && z.unit == SvgLength.PERCENT) return null
        val onlyCenter = (p1.dir == 0 || p1.dir == 5) && (p2.dir == 0 || p2.dir == 5)
        return when {
            onlyCenter -> arrayOf(p1.len, p2.len)
            horiz(p1) && vert(p2) -> arrayOf(p1.len, p2.len)
            vert(p1) && horiz(p2) -> arrayOf(p2.len, p1.len)
            else -> null
        }
    }

    fun filterValues(text: String): List<SvgFilterValue>? {
        val s = SvgStream(text)
        val out = ArrayList<SvgFilterValue>()
        try {
            while (true) {
                s.skipSpaces()
                if (s.atEnd()) break
                if (s.startsWith("none")) {
                    s.advance(4)
                    s.skipSpaces()
                    if (s.atEnd()) break else return null
                }
                val name = s.consumeAsciiIdent()
                s.skipSpaces()
                s.consume('(')
                s.skipSpaces()
                fun positiveLength(): SvgLength { val v = s.parseLength(); if (v.number < 0 || (v.number == 0.0 && 1.0 / v.number < 0) || v.unit == SvgLength.PERCENT) throw SvgSyntaxError(); return v }
                fun filterLength(): SvgLength { val v = s.parseLength(); if (v.unit == SvgLength.PERCENT) throw SvgSyntaxError(); return v }
                fun generic(): Double {
                    if (s.isCurr(')')) return 1.0
                    val v = s.parseNumberOrPercent()
                    if (v < 0 || (v == 0.0 && 1.0 / v < 0)) throw SvgSyntaxError()
                    return v
                }
                val v: SvgFilterValue = when (name) {
                    "blur" -> SvgFilterValue.Blur(if (s.isCurr(')')) SvgLength.ZERO else positiveLength())
                    "drop-shadow" -> {
                        if (s.isCurr(')')) throw SvgSyntaxError()
                        var color: SvgColor? = s.tryParseColor()
                        var isCurrent = false
                        if (color != null) s.skipSpaces() else if (s.startsWith("currentColor")) { isCurrent = true; s.advance(12); s.skipSpaces() }
                        val dx = filterLength(); s.skipSpaces()
                        val dy = filterLength(); s.skipSpaces()
                        var sd = SvgLength.ZERO
                        val save = s.pos
                        try { sd = positiveLength(); s.skipSpaces() } catch (e: SvgSyntaxError) { s.pos = save }
                        if (color == null && !isCurrent) {
                            val c = s.tryParseColor()
                            if (c != null) { color = c; s.skipSpaces() } else if (s.startsWith("currentColor")) s.advance(12)
                        }
                        SvgFilterValue.DropShadow(color, dx, dy, sd)
                    }
                    "hue-rotate" -> {
                        if (s.isCurr(')')) SvgFilterValue.HueRotate(0.0) else {
                            s.skipSpaces()
                            val n = s.parseNumber()
                            val deg = when {
                                s.startsWith("deg") -> { s.advance(3); n }
                                s.startsWith("grad") -> { s.advance(4); n * 180.0 / 200.0 }
                                s.startsWith("rad") -> { s.advance(3); Math.toDegrees(n) }
                                s.startsWith("turn") -> { s.advance(4); n * 360.0 }
                                n == 0.0 -> n
                                else -> throw SvgSyntaxError()
                            }
                            SvgFilterValue.HueRotate(deg)
                        }
                    }
                    "brightness" -> SvgFilterValue.Brightness(generic())
                    "contrast" -> SvgFilterValue.Contrast(generic())
                    "grayscale" -> SvgFilterValue.Grayscale(generic())
                    "invert" -> SvgFilterValue.Invert(generic())
                    "opacity" -> SvgFilterValue.Opacity(generic())
                    "saturate" -> SvgFilterValue.Saturate(generic())
                    "sepia" -> SvgFilterValue.Sepia(generic())
                    "url" -> {
                        s.consume('#')
                        val link = s.consumeWhile { it != ' ' && it != ')' }
                        if (link.isEmpty()) throw SvgSyntaxError()
                        SvgFilterValue.Url(link)
                    }
                    else -> throw SvgSyntaxError()
                }
                s.skipSpaces()
                s.consume(')')
                s.skipSpaces()
                out.add(v)
            }
        } catch (e: SvgSyntaxError) { return null }
        return out
    }

    fun fontShorthand(text: String): SvgFontShorthand? {
        var s = SvgStream(text)
        try {
            s.skipSpaces()
            var prev = s.pos
            var style: String? = null; var variant: String? = null; var weight: String? = null; var stretch: String? = null
            for (i in 0 until 4) {
                val ident = s.consumeAsciiIdent()
                when (ident) {
                    "normal" -> {}
                    "small-caps" -> variant = ident
                    "italic", "oblique" -> style = ident
                    "bold", "bolder", "lighter", "100", "200", "300", "400", "500", "600", "700", "800", "900" -> weight = ident
                    "ultra-condensed", "extra-condensed", "condensed", "semi-condensed", "semi-expanded", "expanded", "extra-expanded", "ultra-expanded" -> stretch = ident
                    else -> { s = SvgStream(text, prev); break }
                }
                s.skipSpaces()
                prev = s.pos
            }
            prev = s.pos
            if (s.curr() in '0'..'9') s.parseLength() else {
                val size = s.consumeAsciiIdent()
                if (size !in setOf("xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large", "larger", "smaller")) return null
            }
            val fontSize = s.sliceBack(prev)
            s.skipSpaces()
            if (s.curr() == '/') { s.advance(1); s.skipSpaces(); s.parseLength(); s.skipSpaces() }
            if (s.atEnd()) return null
            return SvgFontShorthand(style, variant, weight, stretch, fontSize, s.tail())
        } catch (e: SvgSyntaxError) { return null }
    }

    fun fontFamilies(text: String): List<String>? {
        val s = SvgStream(text)
        val out = ArrayList<String>()
        try {
            while (!s.atEnd()) {
                s.skipSpaces()
                val ch = s.curr()
                val fam = if (ch == '\'' || ch == '"') s.parseQuoted() else {
                    val idents = ArrayList<String>()
                    while (!s.atEnd() && s.curr() != ',') { idents.add(s.parseIdent()); s.skipSpaces() }
                    idents.joinToString(" ")
                }
                out.add(fam)
                if (!s.atEnd()) { if (s.curr() == ',') s.advance(1) else break }
            }
            s.skipSpaces()
            if (!s.atEnd()) return null
        } catch (e: SvgSyntaxError) { return null }
        return out.filter { it.isNotEmpty() }
    }

    fun arcToCubics(px: Double, py: Double, rxIn: Double, ryIn: Double, xRotDeg: Double, large: Boolean, sweep: Boolean, x: Double, y: Double, out: MutableList<SvgPathSeg>): Boolean {
        if (Math.abs(rxIn) <= 1e-5 || Math.abs(ryIn) <= 1e-5 || (px == x && py == y)) return false
        var rx = Math.abs(rxIn)
        var ry = Math.abs(ryIn)
        val xr = Math.toRadians(xRotDeg) % (2.0 * Math.PI)
        val sinPhi = Math.sin(xr)
        val cosPhi = Math.cos(xr)
        val hdx = (px - x) * 0.5
        val hdy = (py - y) * 0.5
        val hsx = (px + x) * 0.5
        val hsy = (py + y) * 0.5
        val ppx = cosPhi * hdx + sinPhi * hdy
        val ppy = -sinPhi * hdx + cosPhi * hdy
        val rf = ppx * ppx / (rx * rx) + ppy * ppy / (ry * ry)
        if (rf > 1.0) { val sc = Math.sqrt(rf); rx *= sc; ry *= sc }
        val rxry = rx * ry
        val rxpy = rx * ppy
        val rypx = ry * ppx
        val sumSq = rxpy * rxpy + rypx * rypx
        val signCoe = if (large == sweep) -1.0 else 1.0
        val coe = signCoe * Math.sqrt(Math.abs((rxry * rxry - sumSq) / sumSq))
        val tcx = coe * rxpy / ry
        val tcy = -coe * rypx / rx
        val cx = cosPhi * tcx - sinPhi * tcy + hsx
        val cy = sinPhi * tcx + cosPhi * tcy + hsy
        val startAngle = Math.atan2((ppy - tcy) / ry, (ppx - tcx) / rx)
        var sweepAngle = (Math.atan2((-ppy - tcy) / ry, (-ppx - tcx) / rx) - startAngle) % (2.0 * Math.PI)
        if (sweep && sweepAngle < 0.0) sweepAngle += 2.0 * Math.PI else if (!sweep && sweepAngle > 0.0) sweepAngle -= 2.0 * Math.PI
        val sign = Math.signum(sweepAngle)
        val scaledErr = Math.max(rx, ry) / 0.1
        val nErr = Math.max(Math.pow(1.1163 * scaledErr, 1.0 / 6.0), 3.999999)
        val nf = Math.ceil(nErr * Math.abs(sweepAngle) * (1.0 / (2.0 * Math.PI)))
        val angleStep = sweepAngle / nf
        val n = nf.toInt()
        val armLen = (4.0 / 3.0) * Math.abs(Math.tan(0.25 * angleStep)) * sign
        fun sample(angle: Double): DoubleArray {
            val u = rx * Math.cos(angle)
            val v = ry * Math.sin(angle)
            val rs = Math.sin(xRotDeg.let { xr })
            val rc = Math.cos(xr)
            return doubleArrayOf(u * rc - v * rs, u * rs + v * rc)
        }
        var angle0 = startAngle
        var p0 = sample(angle0)
        for (i in 0 until n) {
            val angle1 = angle0 + angleStep
            val t0 = sample(angle0 + Math.PI / 2)
            val p1x = p0[0] + armLen * t0[0]; val p1y = p0[1] + armLen * t0[1]
            val p3 = sample(angle1)
            val t1 = sample(angle1 + Math.PI / 2)
            val p2x = p3[0] - armLen * t1[0]; val p2y = p3[1] - armLen * t1[1]
            out.add(SvgPathSeg.CubicTo(cx + p1x, cy + p1y, cx + p2x, cy + p2y, cx + p3[0], cy + p3[1]))
            angle0 = angle1
            p0 = p3
        }
        return true
    }

    fun pathData(text: String): List<SvgPathSeg> {
        val s = SvgStream(text)
        val out = ArrayList<SvgPathSeg>()
        var prevCmd: Char? = null
        var prevMx = 0.0; var prevMy = 0.0; var prevTx = 0.0; var prevTy = 0.0; var prevX = 0.0; var prevY = 0.0
        var prevSegKind = 'M'
        var prevSegC2x = 0.0; var prevSegC2y = 0.0; var prevSegQ1x = 0.0; var prevSegQ1y = 0.0
        var prevSimpleClose = false
        fun isCmd(c: Char) = c in "MmZzLlHhVvCcSsQqTtAa"
        try {
            while (true) {
                s.skipSpaces()
                if (s.atEnd()) break
                val first = s.curr()
                if (prevCmd == null && !isCmd(first)) break
                if (prevCmd == null && first != 'M' && first != 'm') break
                val implicitMove: Boolean
                val cmd: Char
                if (isCmd(first)) { implicitMove = false; cmd = first; s.advance(1) }
                else if ((first in '0'..'9' || first == '.' || first == '-' || first == '+') && prevCmd != null) {
                    val p = prevCmd
                    if (p == 'Z' || p == 'z') break
                    if (p == 'M' || p == 'm') { implicitMove = true; cmd = if (p == 'M') 'L' else 'l' } else { implicitMove = false; cmd = p }
                } else break
                val abs = cmd.isUpperCase()
                val buffer = ArrayList<SvgPathSeg>()
                val lc = cmd.lowercaseChar()
                if (prevSimpleClose && lc != 'm' && lc != 'z') {
                    val ns = SvgPathSeg.MoveTo(prevMx, prevMy)
                    buffer.add(ns)
                    prevSimpleClose = false
                }
                when (lc) {
                    'm' -> {
                        var x = s.parseListNumber(); var y = s.parseListNumber()
                        if (!abs) { if (prevSimpleClose) { x += prevMx; y += prevMy } else { x += prevX; y += prevY } }
                        buffer.add(SvgPathSeg.MoveTo(x, y)); prevSegKind = 'M'
                    }
                    'l' -> { var x = s.parseListNumber(); var y = s.parseListNumber(); if (!abs) { x += prevX; y += prevY }; buffer.add(SvgPathSeg.LineTo(x, y)); prevSegKind = 'L' }
                    'h' -> { var x = s.parseListNumber(); if (!abs) x += prevX; buffer.add(SvgPathSeg.LineTo(x, prevY)); prevSegKind = 'H' }
                    'v' -> { var y = s.parseListNumber(); if (!abs) y += prevY; buffer.add(SvgPathSeg.LineTo(prevX, y)); prevSegKind = 'V' }
                    'c' -> {
                        var x1 = s.parseListNumber(); var y1 = s.parseListNumber(); var x2 = s.parseListNumber(); var y2 = s.parseListNumber(); var x = s.parseListNumber(); var y = s.parseListNumber()
                        if (!abs) { x1 += prevX; y1 += prevY; x2 += prevX; y2 += prevY; x += prevX; y += prevY }
                        buffer.add(SvgPathSeg.CubicTo(x1, y1, x2, y2, x, y)); prevSegKind = 'C'; prevSegC2x = x2; prevSegC2y = y2
                    }
                    's' -> {
                        var x2 = s.parseListNumber(); var y2 = s.parseListNumber(); var x = s.parseListNumber(); var y = s.parseListNumber()
                        val x1: Double; val y1: Double
                        if (prevSegKind == 'C' || prevSegKind == 'S') { x1 = prevX * 2.0 - prevSegC2x; y1 = prevY * 2.0 - prevSegC2y } else { x1 = prevX; y1 = prevY }
                        if (!abs) { x2 += prevX; y2 += prevY; x += prevX; y += prevY }
                        buffer.add(SvgPathSeg.CubicTo(x1, y1, x2, y2, x, y)); prevSegKind = 'S'; prevSegC2x = x2; prevSegC2y = y2
                    }
                    'q' -> {
                        var x1 = s.parseListNumber(); var y1 = s.parseListNumber(); var x = s.parseListNumber(); var y = s.parseListNumber()
                        if (!abs) { x1 += prevX; y1 += prevY; x += prevX; y += prevY }
                        buffer.add(SvgPathSeg.QuadTo(x1, y1, x, y)); prevSegKind = 'Q'; prevSegQ1x = x1; prevSegQ1y = y1
                    }
                    't' -> {
                        var x = s.parseListNumber(); var y = s.parseListNumber()
                        val x1: Double; val y1: Double
                        when (prevSegKind) {
                            'Q' -> { x1 = prevX * 2.0 - prevSegQ1x; y1 = prevY * 2.0 - prevSegQ1y }
                            'T' -> { x1 = prevX * 2.0 - prevTx; y1 = prevY * 2.0 - prevTy }
                            else -> { x1 = prevX; y1 = prevY }
                        }
                        prevTx = x1; prevTy = y1
                        if (!abs) { x += prevX; y += prevY }
                        buffer.add(SvgPathSeg.QuadTo(x1, y1, x, y)); prevSegKind = 'T'
                    }
                    'a' -> {
                        val rx = s.parseListNumber(); val ry = s.parseListNumber(); val rot = s.parseListNumber()
                        fun flag(): Boolean {
                            s.skipSpaces()
                            val c = s.curr()
                            if (c != '0' && c != '1') throw SvgSyntaxError()
                            s.advance(1)
                            if (s.isCurr(',')) s.advance(1)
                            s.skipSpaces()
                            return c == '1'
                        }
                        val large = flag(); val sweep = flag()
                        var x = s.parseListNumber(); var y = s.parseListNumber()
                        if (!abs) { x += prevX; y += prevY }
                        if (!arcToCubics(prevX, prevY, rx, ry, rot, large, sweep, x, y, buffer)) buffer.add(SvgPathSeg.LineTo(x, y))
                        prevSegKind = 'A'
                    }
                    'z' -> {
                        if (!(out.lastOrNull() is SvgPathSeg.Close && buffer.isEmpty())) buffer.add(SvgPathSeg.Close)
                        prevSegKind = 'Z'
                    }
                }
                prevCmd = if (implicitMove) (if (abs) 'M' else 'm') else cmd
                out.addAll(buffer)
                when (val last = buffer.lastOrNull()) {
                    is SvgPathSeg.MoveTo -> { prevX = last.x; prevY = last.y; prevMx = prevX; prevMy = prevY; prevSimpleClose = false }
                    is SvgPathSeg.LineTo -> { prevX = last.x; prevY = last.y; prevSimpleClose = false }
                    is SvgPathSeg.CubicTo -> { prevX = last.x; prevY = last.y; prevSimpleClose = false }
                    is SvgPathSeg.QuadTo -> { prevX = last.x; prevY = last.y; prevSimpleClose = false }
                    is SvgPathSeg.Close -> { prevX = prevMx; prevY = prevMy; prevSimpleClose = true }
                    null -> {}
                }
            }
        } catch (e: SvgSyntaxError) {}
        return out
    }
}

internal class SvgCssDeclaration(val name: String, val value: String, val important: Boolean)

internal interface SvgCssElement {
    fun cssParent(): SvgCssElement?
    fun cssPrevSibling(): SvgCssElement?
    fun cssLocalName(): String
    fun cssAttribute(name: String): String?
}

internal class SvgCssSelector(private val components: List<Comp>) {
    class Sub(val attr: String?, val op: Int, val value: String, val pseudo: String?)
    class Comp(val combinator: Int, val type: String?, val subs: MutableList<Sub>)

    val specificity: Int = run {
        var a = 0; var b = 0; var c = 0
        for (comp in components) {
            if (comp.type != null) c++
            for (s in comp.subs) if (s.attr == "id") a++ else b++
        }
        minOf(a, 255) * 65536 + minOf(b, 255) * 256 + minOf(c, 255)
    }

    fun matches(e: SvgCssElement): Boolean = matchesAt(components.size - 1, e)

    private fun matchSimple(comp: Comp, e: SvgCssElement): Boolean {
        if (comp.type != null && e.cssLocalName() != comp.type) return false
        for (s in comp.subs) {
            if (s.pseudo != null) {
                if (s.pseudo == "first-child") { if (e.cssPrevSibling() != null) return false } else return false
                continue
            }
            val v = e.cssAttribute(s.attr!!) ?: return false
            val ok = when (s.op) {
                0 -> true
                1 -> v == s.value
                2 -> v.split(' ').any { it == s.value }
                else -> v == s.value || (v.startsWith(s.value) && v.length > s.value.length && v[s.value.length] == '-')
            }
            if (!ok) return false
        }
        return true
    }

    private fun matchesAt(idx: Int, e: SvgCssElement): Boolean {
        val comp = components[idx]
        if (!matchSimple(comp, e)) return false
        return when (comp.combinator) {
            1 -> { var p = e.cssParent(); while (p != null) { if (matchesAt(idx - 1, p)) return true; p = p.cssParent() }; false }
            2 -> e.cssParent()?.let { matchesAt(idx - 1, it) } ?: false
            3 -> e.cssPrevSibling()?.let { matchesAt(idx - 1, it) } ?: false
            else -> true
        }
    }
}

internal class SvgCssRule(val selector: SvgCssSelector, var declarations: List<SvgCssDeclaration>)

internal class SvgCssStream(text: String) {
    val t = text
    var pos = 0
    fun atEnd() = pos >= t.length
    fun curr(): Char { if (atEnd()) throw SvgSyntaxError(); return t[pos] }
    fun currOrNull(): Char? = if (atEnd()) null else t[pos]
    fun nextOrNull(): Char? = if (pos + 1 < t.length) t[pos + 1] else null
    fun skipSpaces() { while (!atEnd() && t[pos] in " \t\n\r\u000C") pos++ }
    fun skipComment() {
        if (currOrNull() != '/' || nextOrNull() != '*') throw SvgSyntaxError()
        pos += 2
        while (!atEnd()) { if (t[pos] == '*' && nextOrNull() == '/') break; pos++ }
        if (atEnd()) throw SvgSyntaxError()
        pos += 2
    }
    fun skipSpacesAndComments() { skipSpaces(); while (currOrNull() == '/' && nextOrNull() == '*') { skipComment(); skipSpaces() } }
    private fun nameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c.code > 0x7F
    private fun nameChar(c: Char) = nameStart(c) || c in '0'..'9' || c == '-'
    fun ident(): String {
        val start = pos
        if (currOrNull() == '-') pos++
        if (!atEnd()) { if (nameStart(t[pos])) pos++ else throw SvgSyntaxError() }
        while (!atEnd() && nameChar(t[pos])) pos++
        if (start == pos) throw SvgSyntaxError()
        return t.substring(start, pos)
    }
    fun string(): String {
        val q = curr()
        if (q == '\'' || q == '"') {
            var prev = q
            pos++
            val start = pos
            while (!atEnd()) { val c = t[pos]; if (c == q && prev != '\\') break; prev = c; pos++ }
            val v = t.substring(start, pos)
            if (currOrNull() != q) throw SvgSyntaxError()
            pos++
            return v
        }
        return ident()
    }
    fun consume(c: Char) { if (curr() != c) throw SvgSyntaxError(); pos++ }
}

internal object SvgCss {
    private fun parseSelector(text: String): Pair<SvgCssSelector?, Int> {
        val s = SvgCssStream(text)
        val comps = ArrayList<SvgCssSelector.Comp>()
        var combinator = 0
        var afterCombinator = true
        var finished = false
        fun addSub(sub: SvgCssSelector.Sub) {
            if (combinator == 0 && comps.isNotEmpty()) comps.last().subs.add(sub)
            else { comps.add(SvgCssSelector.Comp(combinator, null, mutableListOf(sub))); combinator = 0 }
        }
        try {
            while (true) {
                if (finished || s.atEnd()) {
                    if (afterCombinator) return null to s.pos
                    break
                }
                when (s.t[s.pos]) {
                    '*' -> {
                        if (!afterCombinator) return null to s.pos
                        afterCombinator = false; s.pos++
                        comps.add(SvgCssSelector.Comp(combinator, null, mutableListOf())); combinator = 0
                    }
                    '#' -> { afterCombinator = false; s.pos++; addSub(SvgCssSelector.Sub("id", 1, s.ident(), null)) }
                    '.' -> { afterCombinator = false; s.pos++; addSub(SvgCssSelector.Sub("class", 2, s.ident(), null)) }
                    '[' -> {
                        afterCombinator = false; s.pos++
                        val name = s.ident()
                        val sub = when (s.curr()) {
                            ']' -> SvgCssSelector.Sub(name, 0, "", null)
                            '=' -> { s.pos++; SvgCssSelector.Sub(name, 1, s.string(), null) }
                            '~' -> { s.pos++; s.consume('='); SvgCssSelector.Sub(name, 2, s.string(), null) }
                            '|' -> { s.pos++; s.consume('='); SvgCssSelector.Sub(name, 3, s.string(), null) }
                            else -> return null to s.pos
                        }
                        s.consume(']')
                        addSub(sub)
                    }
                    ':' -> {
                        afterCombinator = false; s.pos++
                        val ident = s.ident()
                        if (ident == "lang") {
                            s.consume('(')
                            val start = s.pos
                            while (!s.atEnd() && s.t[s.pos] != ')') s.pos++
                            val lang = s.t.substring(start, s.pos).trim()
                            s.consume(')')
                            if (lang.isEmpty()) return null to s.pos
                            addSub(SvgCssSelector.Sub(null, 0, "", "lang"))
                        } else {
                            if (ident !in setOf("first-child", "link", "visited", "hover", "active", "focus")) return null to s.pos
                            addSub(SvgCssSelector.Sub(null, 0, "", ident))
                        }
                    }
                    '>' -> { if (afterCombinator) return null to s.pos; s.pos++; afterCombinator = true; combinator = 2 }
                    '+' -> { if (afterCombinator) return null to s.pos; s.pos++; afterCombinator = true; combinator = 3 }
                    ' ', '\t', '\n', '\r', '\u000C' -> {
                        s.skipSpaces()
                        if (afterCombinator) continue
                        while (s.currOrNull() == '/') { s.skipComment(); s.skipSpaces() }
                        when (s.currOrNull()) {
                            '>', '+', ',', '{', null -> {}
                            else -> { afterCombinator = true; combinator = 1 }
                        }
                    }
                    '/' -> { if (s.nextOrNull() == '*') s.skipComment() else finished = true }
                    ',', '{' -> finished = true
                    else -> {
                        val ident = s.ident()
                        if (!afterCombinator) return null to s.pos
                        afterCombinator = false
                        comps.add(SvgCssSelector.Comp(combinator, ident, mutableListOf())); combinator = 0
                    }
                }
            }
        } catch (e: SvgSyntaxError) { return null to s.pos }
        if (comps.isEmpty() || comps[0].combinator != 0) return null to s.pos
        return SvgCssSelector(comps) to s.pos
    }

    private fun consumeTerm(s: SvgCssStream) {
        when (s.curr()) {
            '#' -> {
                s.pos++
                val save = s.pos
                try { s.ident() } catch (e: SvgSyntaxError) { s.pos = save; while (!s.atEnd() && (s.t[s.pos] in '0'..'9' || s.t[s.pos] in 'a'..'f' || s.t[s.pos] in 'A'..'F')) s.pos++ }
            }
            '+', '-', in '0'..'9', '.' -> {
                s.pos++
                while (!s.atEnd() && s.t[s.pos] in '0'..'9') s.pos++
                if (s.currOrNull() == '.') { s.pos++; while (!s.atEnd() && s.t[s.pos] in '0'..'9') s.pos++ }
                if (s.currOrNull() == '%') s.pos++ else { val save = s.pos; try { s.ident() } catch (e: SvgSyntaxError) { s.pos = save } }
            }
            '\'', '"' -> s.string()
            ',' -> s.pos++
            else -> {
                s.ident()
                if (s.currOrNull() == '(') { while (!s.atEnd() && s.t[s.pos] != ')') s.pos++; s.consume(')') }
            }
        }
    }

    private fun consumeDeclaration(s: SvgCssStream): SvgCssDeclaration {
        s.skipSpacesAndComments()
        if (s.currOrNull() == '*') s.pos++
        val name = s.ident()
        s.skipSpacesAndComments()
        s.consume(':')
        s.skipSpacesAndComments()
        val start = s.pos
        var end = s.pos
        while (true) {
            val save = s.pos
            try { consumeTerm(s) } catch (e: SvgSyntaxError) { s.pos = save; break }
            end = s.pos
            s.skipSpacesAndComments()
        }
        val value = s.t.substring(start, end).trim()
        s.skipSpacesAndComments()
        var important = false
        if (s.currOrNull() == '!') {
            s.pos++
            s.skipSpacesAndComments()
            if (s.t.startsWith("important", s.pos)) { s.pos += 9; important = true }
        }
        s.skipSpacesAndComments()
        while (s.currOrNull() == ';') { s.pos++; s.skipSpacesAndComments() }
        s.skipSpacesAndComments()
        if (value.isEmpty()) throw SvgSyntaxError()
        return SvgCssDeclaration(name, value, important)
    }

    fun declarations(text: String): List<SvgCssDeclaration> {
        val s = SvgCssStream(text)
        val out = ArrayList<SvgCssDeclaration>()
        while (true) {
            try { s.skipSpacesAndComments() } catch (e: SvgSyntaxError) {}
            if (s.atEnd()) break
            try { out.add(consumeDeclaration(s)) } catch (e: SvgSyntaxError) { break }
        }
        return out
    }

    private fun consumeUntilBlockEnd(s: SvgCssStream) {
        var braces = 0
        while (!s.atEnd()) {
            when (s.t[s.pos]) { '{' -> braces++; '}' -> if (braces == 0) break else braces-- }
            s.pos++
        }
        if (s.currOrNull() == '}') s.pos++
    }

    private fun consumeDeclarationsBlock(s: SvgCssStream): List<SvgCssDeclaration> {
        val out = ArrayList<SvgCssDeclaration>()
        while (!s.atEnd() && s.currOrNull() != '}') {
            val save = s.pos
            try { out.add(consumeDeclaration(s)) } catch (e: SvgSyntaxError) { s.pos = maxOf(s.pos, save); consumeUntilBlockEnd(s); break }
        }
        return out
    }

    fun parseInto(text: String, rules: MutableList<SvgCssRule>) {
        val s = SvgCssStream(text)
        try { s.skipSpacesAndComments() } catch (e: SvgSyntaxError) { return }
        while (!s.atEnd()) {
            try { s.skipSpacesAndComments() } catch (e: SvgSyntaxError) { break }
            if (s.atEnd()) break
            try {
                if (s.curr() == '@') {
                    s.pos++
                    s.ident()
                    while (!s.atEnd() && s.t[s.pos] != ';' && s.t[s.pos] != '{') s.pos++
                    when (s.curr()) { ';' -> s.pos++; '{' -> { s.pos++; consumeUntilBlockEnd(s) } }
                } else {
                    val start = rules.size
                    while (s.curr() == ',' || start == rules.size) {
                        if (s.curr() == ',') s.pos++
                        val (sel, off) = parseSelector(s.t.substring(s.pos))
                        s.pos += off
                        s.skipSpaces()
                        if (sel != null) rules.add(SvgCssRule(sel, emptyList()))
                        val c = s.curr()
                        if (c == '{') break
                        if (c != ',') { while (!s.atEnd() && s.t[s.pos] != '{') s.pos++; break }
                    }
                    if (s.currOrNull() == '{') s.pos++
                    val decls = consumeDeclarationsBlock(s)
                    for (i in start until rules.size) rules[i].declarations = decls
                    if (s.currOrNull() == '}') s.pos++
                }
            } catch (e: SvgSyntaxError) { break }
        }
        rules.removeAll { it.declarations.isEmpty() }
        val sorted = rules.sortedBy { it.selector.specificity }
        rules.clear()
        rules.addAll(sorted)
    }
}
