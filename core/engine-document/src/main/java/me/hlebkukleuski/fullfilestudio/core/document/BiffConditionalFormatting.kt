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
 * Implements OLE2 Compound File Binary ([MS-CFB]), Excel BIFF8/BIFF12 ([MS-XLS] / [MS-XLSB]),
 * Word Binary ([MS-DOC]), PowerPoint Binary ([MS-PPT]), OneNote ([MS-ONE] / [MS-ONESTORE])
 * under the Microsoft Open Specifications Promise (OSP), ISO-9660 (ECMA-119), and dBase DBF,
 * with BIFF8/OLE2 structure definitions informed by Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

object BiffCf {
    fun extProps(b: ByteArray, start: Int, end: Int): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        if (start + 8 > end) return out
        val n = u16(b, start + 6)
        var q = start + 8
        for (k in 0 until n.coerceAtMost(32)) {
            if (q + 4 > end) break
            val type = u16(b, q)
            val cb = u16(b, q + 2)
            if (cb < 4 || q + cb > end) break
            if (cb >= 12 && type in setOf(4, 5, 7, 8, 9, 10, 11, 13)) out[type] = intArrayOf(u16(b, q + 4), u16(b, q + 6).toShort().toInt(), u32(b, q + 8))
            q += cb
        }
        return out
    }

    fun withExt(st: CfStyle, ext: Map<Int, IntArray>, full: (IntArray) -> Int?): CfStyle {
        if (ext.isEmpty()) return st
        fun c(t: Int) = ext[t]?.let(full)
        val fill = st.fill?.let { f ->
            if (f.pattern > 1) SheetFill(c(5) ?: f.bg, f.pattern, c(4) ?: f.fg) else SheetFill(c(5) ?: c(4) ?: f.bg)
        } ?: (c(5) ?: c(4))?.let { SheetFill(it) }
        fun bd(b: SheetBorder?, t: Int) = b?.let { SheetBorder(it.style, c(t) ?: it.color) }
        return CfStyle(fill, c(13) ?: st.color, st.bold, st.italic, st.underline, st.strike, bd(st.left, 9), bd(st.right, 10), bd(st.top, 7), bd(st.bottom, 8), st.numFmt)
    }

    private fun xnum(b: ByteArray, p: Int): Double {
        var bits = 0L
        for (k in 7 downTo 0) bits = (bits shl 8) or (b[p + k].toLong() and 0xFF)
        return Double.fromBits(bits)
    }

    private fun cfColor(b: ByteArray, p: Int, icv: (Int) -> Int?, full: (IntArray) -> Int?): Int? {
        val type = u32(b, p)
        val v = u32(b, p + 4)
        val tint = xnum(b, p + 8)
        return when (type) {
            1 -> icv(v)
            2, 3 -> full(intArrayOf(type, (tint * 32767).toInt(), v))
            else -> null
        }
    }

    private class Cfvo(val obj: CfValueObject, val next: Int)

    private fun cfvo(b: ByteArray, p: Int, end: Int, top: Int, left: Int): Cfvo? {
        if (p + 3 > end) return null
        val type = b[p].toInt() and 0xFF
        val cce = u16(b, p + 1)
        var q = p + 3
        val fmla = if (cce > 0) formula(b, q, q + cce, top, left) else null
        q += cce
        var value: Double? = null
        if (type != 2 && type != 3 && type != 7 && cce == 0) {
            if (q + 8 > end) return null
            value = xnum(b, q)
            q += 8
        }
        val t = when (type) { 1 -> "num"; 2 -> "min"; 3 -> "max"; 4 -> "percent"; 5 -> "percentile"; else -> "formula" }
        val v = fmla ?: value?.let { ExcelNumberFormat.general(it).replace(ExcelNumberFormat.decimalSeparator, '.') }
        return Cfvo(CfValueObject(if (fmla != null && t != "formula") t else t, v), q)
    }

    private val ICON_SETS = listOf(
        "3Arrows", "3ArrowsGray", "3Flags", "3TrafficLights1", "3TrafficLights2", "3Signs", "3Symbols", "3Symbols2",
        "4Arrows", "4ArrowsGray", "4RedToBlack", "4Rating", "4TrafficLights", "5Arrows", "5ArrowsGray", "5Rating", "5Quarters"
    )

    fun cf12(b: ByteArray, start: Int, end: Int, top: Int, left: Int, icv: (Int) -> Int?, full: (IntArray) -> Int?, formats: Map<Int, String>, names: ((Int, Int) -> String?)? = null): CfRule? {
        if (start + 10 > end) return null
        val ct = b[start].toInt() and 0xFF
        val cp = b[start + 1].toInt() and 0xFF
        val cce1 = u16(b, start + 2)
        val cce2 = u16(b, start + 4)
        val cbDxf = u32(b, start + 6)
        var q = start + 10
        var style: CfStyle? = null
        if (cbDxf > 0) {
            val dxEnd = (q + cbDxf).coerceAtMost(end)
            dxfn(b, q, dxEnd, icv, formats)?.let { (st, after) -> style = withExt(st, extProps(b, after, dxEnd), full) }
            q += cbDxf
        }
        val f1 = if (cce1 > 0) formula(b, q, q + cce1, top, left, names) else null
        q += cce1
        val f2 = if (cce2 > 0) formula(b, q, q + cce2, top, left, names) else null
        q += cce2
        if (q + 2 > end) return null
        q += 2 + u16(b, q)
        if (q + 24 > end) return null
        val flags = b[q + 2].toInt() and 0xFF
        val priority = u16(b, q + 3)
        q += 24
        if (q + 6 > end) return null
        return when (ct) {
            1 -> if (f1 == null) null else CfRule(
                "cellIs", priority, stopIfTrue = (flags and 0x02) != 0,
                operator = when (cp) { 1 -> "between"; 2 -> "notBetween"; 3 -> "equal"; 4 -> "notEqual"; 5 -> "greaterThan"; 6 -> "lessThan"; 7 -> "greaterThanOrEqual"; else -> "lessThanOrEqual" },
                formulas = listOfNotNull(f1, f2), style = style
            )
            2 -> if (f1 == null) null else CfRule("expression", priority, stopIfTrue = (flags and 0x02) != 0, formulas = listOf(f1), style = style)
            3 -> {
                val nInterp = b[q + 4].toInt() and 0xFF
                val nCurve = b[q + 5].toInt() and 0xFF
                var p = q + 6
                val objs = ArrayList<CfValueObject>()
                for (k in 0 until nInterp) {
                    val c = cfvo(b, p, end, top, left) ?: return null
                    objs.add(c.obj)
                    p = c.next + 8
                }
                val colors = ArrayList<Int>()
                for (k in 0 until nCurve) {
                    if (p + 24 > end) return null
                    colors.add(cfColor(b, p + 8, icv, full) ?: return null)
                    p += 24
                }
                if (objs.size != colors.size) null else CfRule("colorScale", priority, cfvos = objs, colors = colors)
            }
            4 -> {
                val minLen = (b[q + 4].toInt() and 0xFF) / 100f
                val maxLen = (b[q + 5].toInt() and 0xFF) / 100f
                val color = cfColor(b, q + 6, icv, full) ?: return null
                val c1 = cfvo(b, q + 22, end, top, left) ?: return null
                val c2 = cfvo(b, c1.next, end, top, left) ?: return null
                CfRule("dataBar", priority, cfvos = listOf(c1.obj, c2.obj), colors = listOf(color), barMinLength = minLen, barMaxLength = maxLen.coerceAtLeast(minLen), barAxisNone = true)
            }
            6 -> {
                val n = b[q + 3].toInt() and 0xFF
                val set = ICON_SETS.getOrNull(b[q + 4].toInt() and 0xFF) ?: "3TrafficLights1"
                val fl = b[q + 5].toInt() and 0xFF
                var p = q + 6
                val objs = ArrayList<CfValueObject>()
                for (k in 0 until n.coerceAtMost(5)) {
                    val c = cfvo(b, p, end, top, left) ?: return null
                    if (c.next + 5 > end) return null
                    val eq = b[c.next].toInt() != 0
                    objs.add(CfValueObject(c.obj.type, c.obj.value, eq))
                    p = c.next + 5
                }
                CfRule("iconSet", priority, cfvos = objs, iconSet = set, reverse = (fl and 0x04) != 0, showValue = (fl and 0x01) == 0)
            }
            else -> null
        }
    }

    fun dxfn(b: ByteArray, start: Int, end: Int, color: (Int) -> Int?, formats: Map<Int, String>): Pair<CfStyle, Int>? {
        if (start + 6 > end) return null
        val flags = u32(b, start)
        val flags2 = u16(b, start + 4)
        var q = start + 6
        var numFmt: String? = null
        var fontColor: Int? = null
        var bold: Boolean? = null
        var italic: Boolean? = null
        var strike: Boolean? = null
        var underline: Int? = null
        var left: SheetBorder? = null
        var right: SheetBorder? = null
        var top: SheetBorder? = null
        var bottom: SheetBorder? = null
        var fill: SheetFill? = null
        if ((flags ushr 25) and 1 != 0) {
            if ((flags2 and 1) != 0) {
                if (q + 2 > end) return null
                val cb = u16(b, q)
                if (q + 5 <= end) {
                    val cch = u16(b, q + 2)
                    val hi = (b[q + 4].toInt() and 1) != 0
                    val s = q + 5
                    numFmt = if (hi && s + cch * 2 <= end) String(b, s, cch * 2, Charsets.UTF_16LE) else if (!hi && s + cch <= end) String(b, s, cch, Charsets.ISO_8859_1) else null
                }
                q += cb
            } else {
                if (q + 2 > end) return null
                val ifmt = b[q + 1].toInt() and 0xFF
                numFmt = formats[ifmt] ?: ExcelNumberFormat.code(ifmt, null)
                q += 2
            }
        }
        if ((flags ushr 26) and 1 != 0) {
            if (q + 118 > end) return null
            val ts = u32(b, q + 68)
            val bls = u16(b, q + 72)
            val uls = b[q + 76].toInt() and 0xFF
            val icv = u32(b, q + 80)
            val tsNinch = u32(b, q + 88)
            val ulsNinch = u32(b, q + 96)
            val blsNinch = u32(b, q + 100)
            if (blsNinch == 0 && bls != 0xFFFF && bls != 0) bold = bls >= 0x2BC
            if ((tsNinch and 0x02) == 0) italic = (ts and 0x02) != 0
            if ((tsNinch and 0x80) == 0) strike = (ts and 0x80) != 0
            if (ulsNinch == 0 && uls != 0xFF) underline = when (uls) { 1, 0x21 -> 1; 2, 0x22 -> 2; else -> 0 }
            if (icv != -1 && icv in 0..0x3F) fontColor = color(icv)
            q += 118
        }
        if ((flags ushr 27) and 1 != 0) q += 8
        if ((flags ushr 28) and 1 != 0) {
            if (q + 8 > end) return null
            val dg = u16(b, q)
            val icvLR = u16(b, q + 2)
            val tb = u32(b, q + 4)
            if ((flags ushr 10) and 1 == 0) left = side(dg and 0xF, color(icvLR and 0x7F))
            if ((flags ushr 11) and 1 == 0) right = side((dg ushr 4) and 0xF, color((icvLR ushr 7) and 0x7F))
            if ((flags ushr 12) and 1 == 0) top = side((dg ushr 8) and 0xF, color(tb and 0x7F))
            if ((flags ushr 13) and 1 == 0) bottom = side((dg ushr 12) and 0xF, color((tb ushr 7) and 0x7F))
            q += 8
        }
        if ((flags ushr 29) and 1 != 0) {
            if (q + 4 > end) return null
            val fls = (u16(b, q) ushr 10) and 0x3F
            val icv = u16(b, q + 2)
            val fg = if ((flags ushr 17) and 1 == 0) color(icv and 0x7F) else null
            val bg = if ((flags ushr 18) and 1 == 0) color((icv ushr 7) and 0x7F) else null
            val flsSet = (flags ushr 16) and 1 == 0
            fill = when {
                flsSet && fls > 1 -> SheetCellStyle.biffPattern(fls, fg, bg)
                flsSet && fls == 0 -> null
                else -> (bg ?: fg)?.let { SheetFill(it) }
            }
            q += 4
        }
        if ((flags ushr 30) and 1 != 0) q += 2
        if (q > end) return null
        return CfStyle(fill, fontColor, bold, italic, underline, strike, left, right, top, bottom, numFmt) to q
    }

    private fun side(style: Int, c: Int?): SheetBorder? = if (style == 0) null else SheetBorder(style.coerceIn(1, 13), c)

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, p: Int) = u16(b, p) or (u16(b, p + 2) shl 16)

    private val FTAB: Map<Int, Pair<String, Int>> = mapOf(
        0 to ("COUNT" to -1), 1 to ("IF" to -1), 2 to ("ISNA" to 1), 3 to ("ISERROR" to 1), 4 to ("SUM" to -1), 5 to ("AVERAGE" to -1), 6 to ("MIN" to -1),
        7 to ("MAX" to -1), 8 to ("ROW" to -1), 9 to ("COLUMN" to -1), 10 to ("NA" to 0), 11 to ("NPV" to -1), 12 to ("STDEV" to -1), 13 to ("DOLLAR" to -1),
        14 to ("FIXED" to -1), 15 to ("SIN" to 1), 16 to ("COS" to 1), 17 to ("TAN" to 1), 18 to ("ATAN" to 1), 19 to ("PI" to 0), 20 to ("SQRT" to 1),
        21 to ("EXP" to 1), 22 to ("LN" to 1), 23 to ("LOG10" to 1), 24 to ("ABS" to 1), 25 to ("INT" to 1), 26 to ("SIGN" to 1), 27 to ("ROUND" to 2),
        28 to ("LOOKUP" to -1), 29 to ("INDEX" to -1), 30 to ("REPT" to 2), 31 to ("MID" to 3), 32 to ("LEN" to 1), 33 to ("VALUE" to 1), 34 to ("TRUE" to 0),
        35 to ("FALSE" to 0), 36 to ("AND" to -1), 37 to ("OR" to -1), 38 to ("NOT" to 1), 39 to ("MOD" to 2), 40 to ("DCOUNT" to 3), 41 to ("DSUM" to 3),
        42 to ("DAVERAGE" to 3), 43 to ("DMIN" to 3), 44 to ("DMAX" to 3), 45 to ("DSTDEV" to 3), 46 to ("VAR" to -1), 47 to ("DVAR" to 3),
        48 to ("TEXT" to 2), 49 to ("LINEST" to -1), 50 to ("TREND" to -1), 51 to ("LOGEST" to -1), 52 to ("GROWTH" to -1), 56 to ("PV" to -1),
        57 to ("FV" to -1), 58 to ("NPER" to -1), 59 to ("PMT" to -1), 60 to ("RATE" to -1), 61 to ("MIRR" to 3), 62 to ("IRR" to -1), 63 to ("RAND" to 0),
        64 to ("MATCH" to -1), 65 to ("DATE" to 3), 66 to ("TIME" to 3), 67 to ("DAY" to 1), 68 to ("MONTH" to 1), 69 to ("YEAR" to 1), 70 to ("WEEKDAY" to 1),
        71 to ("HOUR" to 1), 72 to ("MINUTE" to 1), 73 to ("SECOND" to 1), 74 to ("NOW" to 0), 75 to ("AREAS" to 1), 76 to ("ROWS" to 1),
        77 to ("COLUMNS" to 1), 78 to ("OFFSET" to -1), 82 to ("SEARCH" to -1), 83 to ("TRANSPOSE" to 1), 86 to ("TYPE" to 1), 97 to ("ATAN2" to 2),
        98 to ("ASIN" to 1), 99 to ("ACOS" to 1), 100 to ("CHOOSE" to -1), 101 to ("HLOOKUP" to 3), 102 to ("VLOOKUP" to 3), 105 to ("ISREF" to 1),
        109 to ("LOG" to -1), 111 to ("CHAR" to 1), 112 to ("LOWER" to 1), 113 to ("UPPER" to 1), 114 to ("PROPER" to 1), 115 to ("LEFT" to -1),
        116 to ("RIGHT" to -1), 117 to ("EXACT" to 2), 118 to ("TRIM" to 1), 119 to ("REPLACE" to 4), 120 to ("SUBSTITUTE" to -1), 121 to ("CODE" to 1),
        124 to ("FIND" to -1), 125 to ("CELL" to -1), 126 to ("ISERR" to 1), 127 to ("ISTEXT" to 1), 128 to ("ISNUMBER" to 1), 129 to ("ISBLANK" to 1),
        130 to ("T" to 1), 131 to ("N" to 1), 140 to ("DATEVALUE" to 1), 141 to ("TIMEVALUE" to 1), 142 to ("SLN" to 3), 143 to ("SYD" to 4),
        144 to ("DDB" to -1), 148 to ("INDIRECT" to -1), 162 to ("CLEAN" to 1), 163 to ("MDETERM" to 1), 164 to ("MINVERSE" to 1), 165 to ("MMULT" to 2),
        167 to ("IPMT" to -1), 168 to ("PPMT" to -1), 169 to ("COUNTA" to -1), 183 to ("PRODUCT" to -1), 184 to ("FACT" to 1), 189 to ("DPRODUCT" to 3),
        190 to ("ISNONTEXT" to 1), 193 to ("STDEVP" to -1), 194 to ("VARP" to -1), 195 to ("DSTDEVP" to 3), 196 to ("DVARP" to 3), 197 to ("TRUNC" to 1),
        198 to ("ISLOGICAL" to 1), 199 to ("DCOUNTA" to 3), 204 to ("DOLLAR" to -1), 208 to ("LEFTB" to -1), 209 to ("RIGHTB" to -1), 210 to ("MIDB" to 3),
        211 to ("LENB" to 1), 212 to ("ROUNDUP" to 2), 213 to ("ROUNDDOWN" to 2), 214 to ("ASC" to 1), 215 to ("JIS" to 1), 216 to ("RANK" to -1),
        219 to ("ADDRESS" to -1), 220 to ("DAYS360" to 2), 221 to ("TODAY" to 0), 222 to ("VDB" to -1), 227 to ("MEDIAN" to -1), 228 to ("SUMPRODUCT" to -1),
        229 to ("SINH" to 1), 230 to ("COSH" to 1), 231 to ("TANH" to 1), 232 to ("ASINH" to 1), 233 to ("ACOSH" to 1), 234 to ("ATANH" to 1),
        235 to ("DGET" to 3), 244 to ("INFO" to 1), 247 to ("DB" to -1), 252 to ("FREQUENCY" to 2), 261 to ("ORG.OPENOFFICE.ERRORTYPE" to 1),
        269 to ("AVEDEV" to -1), 270 to ("BETADIST" to -1), 271 to ("GAMMALN" to 1), 272 to ("BETAINV" to -1), 273 to ("BINOMDIST" to 4),
        274 to ("LEGACY.CHIDIST" to 2), 275 to ("LEGACY.CHIINV" to 2), 276 to ("COMBIN" to 2), 277 to ("CONFIDENCE" to 3), 278 to ("CRITBINOM" to 3),
        279 to ("EVEN" to 1), 280 to ("EXPONDIST" to 3), 281 to ("LEGACY.FDIST" to 3), 282 to ("LEGACY.FINV" to 3), 283 to ("FISHER" to 1),
        284 to ("FISHERINV" to 1), 285 to ("FLOOR" to 2), 286 to ("GAMMADIST" to 4), 287 to ("GAMMAINV" to 3), 288 to ("CEILING" to 2),
        289 to ("HYPGEOMDIST" to 4), 290 to ("LOGNORMDIST" to 3), 291 to ("LOGINV" to 3), 292 to ("NEGBINOMDIST" to 3), 293 to ("NORMDIST" to 4),
        294 to ("LEGACY.NORMSDIST" to 1), 295 to ("NORMINV" to 3), 296 to ("LEGACY.NORMSINV" to 1), 297 to ("STANDARDIZE" to 3), 298 to ("ODD" to 1),
        299 to ("PERMUT" to 2), 300 to ("POISSON" to 3), 301 to ("TDIST" to 3), 302 to ("WEIBULL" to 4), 303 to ("SUMXMY2" to 2), 304 to ("SUMX2MY2" to 2),
        305 to ("SUMX2PY2" to 2), 306 to ("LEGACY.CHITEST" to 2), 307 to ("CORREL" to 2), 308 to ("COVAR" to 2), 309 to ("FORECAST" to 3),
        310 to ("FTEST" to 2), 311 to ("INTERCEPT" to 2), 312 to ("PEARSON" to 2), 313 to ("RSQ" to 2), 314 to ("STEYX" to 2), 315 to ("SLOPE" to 2),
        316 to ("TTEST" to 4), 317 to ("PROB" to -1), 318 to ("DEVSQ" to -1), 319 to ("GEOMEAN" to -1), 320 to ("HARMEAN" to -1), 321 to ("SUMSQ" to -1),
        322 to ("KURT" to -1), 323 to ("SKEW" to -1), 324 to ("ZTEST" to -1), 325 to ("LARGE" to 2), 326 to ("SMALL" to 2), 327 to ("QUARTILE" to 2),
        328 to ("PERCENTILE" to 2), 329 to ("PERCENTRANK" to -1), 330 to ("MODE" to -1), 331 to ("TRIMMEAN" to 2), 332 to ("TINV" to 2),
        336 to ("CONCATENATE" to -1), 337 to ("POWER" to 2), 342 to ("RADIANS" to 1), 343 to ("DEGREES" to 1), 344 to ("SUBTOTAL" to -1),
        345 to ("SUMIF" to -1), 346 to ("COUNTIF" to 2), 347 to ("COUNTBLANK" to 1), 350 to ("ISPMT" to 4), 354 to ("ROMAN" to -1),
        358 to ("GETPIVOTDATA" to -1), 359 to ("HYPERLINK" to -1), 361 to ("AVERAGEA" to -1), 362 to ("MAXA" to -1), 363 to ("MINA" to -1),
        364 to ("STDEVPA" to -1), 365 to ("VARPA" to -1), 366 to ("STDEVA" to -1), 367 to ("VARA" to -1), 368 to ("COM.MICROSOFT.BAHTTEXT" to 1)
    )

    private val FTAB12: Map<Int, Pair<String, Int>> = mapOf(
        0 to ("COUNT" to -1), 1 to ("IF" to -1), 2 to ("ISNA" to 1), 3 to ("ISERROR" to 1), 4 to ("SUM" to -1), 5 to ("AVERAGE" to -1), 6 to ("MIN" to -1),
        7 to ("MAX" to -1), 8 to ("ROW" to -1), 9 to ("COLUMN" to -1), 10 to ("NA" to 0), 11 to ("NPV" to -1), 12 to ("STDEV" to -1), 13 to ("DOLLAR" to -1),
        14 to ("FIXED" to -1), 15 to ("SIN" to 1), 16 to ("COS" to 1), 17 to ("TAN" to 1), 18 to ("ATAN" to 1), 19 to ("PI" to 0), 20 to ("SQRT" to 1),
        21 to ("EXP" to 1), 22 to ("LN" to 1), 23 to ("LOG10" to 1), 24 to ("ABS" to 1), 25 to ("INT" to 1), 26 to ("SIGN" to 1), 27 to ("ROUND" to 2),
        28 to ("LOOKUP" to -1), 29 to ("INDEX" to -1), 30 to ("REPT" to 2), 31 to ("MID" to 3), 32 to ("LEN" to 1), 33 to ("VALUE" to 1), 34 to ("TRUE" to 0),
        35 to ("FALSE" to 0), 36 to ("AND" to -1), 37 to ("OR" to -1), 38 to ("NOT" to 1), 39 to ("MOD" to 2), 40 to ("DCOUNT" to 3), 41 to ("DSUM" to 3),
        42 to ("DAVERAGE" to 3), 43 to ("DMIN" to 3), 44 to ("DMAX" to 3), 45 to ("DSTDEV" to 3), 46 to ("VAR" to -1), 47 to ("DVAR" to 3),
        48 to ("TEXT" to 2), 49 to ("LINEST" to -1), 50 to ("TREND" to -1), 51 to ("LOGEST" to -1), 52 to ("GROWTH" to -1), 56 to ("PV" to -1),
        57 to ("FV" to -1), 58 to ("NPER" to -1), 59 to ("PMT" to -1), 60 to ("RATE" to -1), 61 to ("MIRR" to 3), 62 to ("IRR" to -1), 63 to ("RAND" to 0),
        64 to ("MATCH" to -1), 65 to ("DATE" to 3), 66 to ("TIME" to 3), 67 to ("DAY" to 1), 68 to ("MONTH" to 1), 69 to ("YEAR" to 1), 70 to ("WEEKDAY" to 1),
        71 to ("HOUR" to 1), 72 to ("MINUTE" to 1), 73 to ("SECOND" to 1), 74 to ("NOW" to 0), 75 to ("AREAS" to 1), 76 to ("ROWS" to 1),
        77 to ("COLUMNS" to 1), 78 to ("OFFSET" to -1), 82 to ("SEARCH" to -1), 83 to ("TRANSPOSE" to 1), 86 to ("TYPE" to 1), 97 to ("ATAN2" to 2),
        98 to ("ASIN" to 1), 99 to ("ACOS" to 1), 100 to ("CHOOSE" to -1), 101 to ("HLOOKUP" to 3), 102 to ("VLOOKUP" to 3), 105 to ("ISREF" to 1),
        109 to ("LOG" to -1), 111 to ("CHAR" to 1), 112 to ("LOWER" to 1), 113 to ("UPPER" to 1), 114 to ("PROPER" to 1), 115 to ("LEFT" to -1),
        116 to ("RIGHT" to -1), 117 to ("EXACT" to 2), 118 to ("TRIM" to 1), 119 to ("REPLACE" to 4), 120 to ("SUBSTITUTE" to -1), 121 to ("CODE" to 1),
        124 to ("FIND" to -1), 125 to ("CELL" to -1), 126 to ("ISERR" to 1), 127 to ("ISTEXT" to 1), 128 to ("ISNUMBER" to 1), 129 to ("ISBLANK" to 1),
        130 to ("T" to 1), 131 to ("N" to 1), 140 to ("DATEVALUE" to 1), 141 to ("TIMEVALUE" to 1), 142 to ("SLN" to 3), 143 to ("SYD" to 4),
        144 to ("DDB" to -1), 148 to ("INDIRECT" to -1), 162 to ("CLEAN" to 1), 163 to ("MDETERM" to 1), 164 to ("MINVERSE" to 1), 165 to ("MMULT" to 2),
        167 to ("IPMT" to -1), 168 to ("PPMT" to -1), 169 to ("COUNTA" to -1), 183 to ("PRODUCT" to -1), 184 to ("FACT" to 1), 189 to ("DPRODUCT" to 3),
        190 to ("ISNONTEXT" to 1), 193 to ("STDEVP" to -1), 194 to ("VARP" to -1), 195 to ("DSTDEVP" to 3), 196 to ("DVARP" to 3), 197 to ("TRUNC" to 1),
        198 to ("ISLOGICAL" to 1), 199 to ("DCOUNTA" to 3), 204 to ("DOLLAR" to -1), 208 to ("LEFTB" to -1), 209 to ("RIGHTB" to -1), 210 to ("MIDB" to 3),
        211 to ("LENB" to 1), 212 to ("ROUNDUP" to 2), 213 to ("ROUNDDOWN" to 2), 214 to ("ASC" to 1), 215 to ("JIS" to 1), 216 to ("RANK" to -1),
        219 to ("ADDRESS" to -1), 220 to ("DAYS360" to 2), 221 to ("TODAY" to 0), 222 to ("VDB" to -1), 227 to ("MEDIAN" to -1), 228 to ("SUMPRODUCT" to -1),
        229 to ("SINH" to 1), 230 to ("COSH" to 1), 231 to ("TANH" to 1), 232 to ("ASINH" to 1), 233 to ("ACOSH" to 1), 234 to ("ATANH" to 1),
        235 to ("DGET" to 3), 244 to ("INFO" to 1), 247 to ("DB" to -1), 252 to ("FREQUENCY" to 2), 261 to ("ORG.OPENOFFICE.ERRORTYPE" to 1),
        269 to ("AVEDEV" to -1), 270 to ("BETADIST" to -1), 271 to ("GAMMALN" to 1), 272 to ("BETAINV" to -1), 273 to ("BINOMDIST" to 4),
        274 to ("LEGACY.CHIDIST" to 2), 275 to ("LEGACY.CHIINV" to 2), 276 to ("COMBIN" to 2), 277 to ("CONFIDENCE" to 3), 278 to ("CRITBINOM" to 3),
        279 to ("EVEN" to 1), 280 to ("EXPONDIST" to 3), 281 to ("LEGACY.FDIST" to 3), 282 to ("LEGACY.FINV" to 3), 283 to ("FISHER" to 1),
        284 to ("FISHERINV" to 1), 285 to ("FLOOR" to 2), 286 to ("GAMMADIST" to 4), 287 to ("GAMMAINV" to 3), 288 to ("CEILING" to 2),
        289 to ("HYPGEOMDIST" to 4), 290 to ("LOGNORMDIST" to 3), 291 to ("LOGINV" to 3), 292 to ("NEGBINOMDIST" to 3), 293 to ("NORMDIST" to 4),
        294 to ("LEGACY.NORMSDIST" to 1), 295 to ("NORMINV" to 3), 296 to ("LEGACY.NORMSINV" to 1), 297 to ("STANDARDIZE" to 3), 298 to ("ODD" to 1),
        299 to ("PERMUT" to 2), 300 to ("POISSON" to 3), 301 to ("TDIST" to 3), 302 to ("WEIBULL" to 4), 303 to ("SUMXMY2" to 2), 304 to ("SUMX2MY2" to 2),
        305 to ("SUMX2PY2" to 2), 306 to ("LEGACY.CHITEST" to 2), 307 to ("CORREL" to 2), 308 to ("COVAR" to 2), 309 to ("FORECAST" to 3),
        310 to ("FTEST" to 2), 311 to ("INTERCEPT" to 2), 312 to ("PEARSON" to 2), 313 to ("RSQ" to 2), 314 to ("STEYX" to 2), 315 to ("SLOPE" to 2),
        316 to ("TTEST" to 4), 317 to ("PROB" to -1), 318 to ("DEVSQ" to -1), 319 to ("GEOMEAN" to -1), 320 to ("HARMEAN" to -1), 321 to ("SUMSQ" to -1),
        322 to ("KURT" to -1), 323 to ("SKEW" to -1), 324 to ("ZTEST" to -1), 325 to ("LARGE" to 2), 326 to ("SMALL" to 2), 327 to ("QUARTILE" to 2),
        328 to ("PERCENTILE" to 2), 329 to ("PERCENTRANK" to -1), 330 to ("MODE" to -1), 331 to ("TRIMMEAN" to 2), 332 to ("TINV" to 2),
        336 to ("CONCATENATE" to -1), 337 to ("POWER" to 2), 342 to ("RADIANS" to 1), 343 to ("DEGREES" to 1), 344 to ("SUBTOTAL" to -1),
        345 to ("SUMIF" to -1), 346 to ("COUNTIF" to 2), 347 to ("COUNTBLANK" to 1), 350 to ("ISPMT" to 4), 354 to ("ROMAN" to -1),
        358 to ("GETPIVOTDATA" to -1), 359 to ("HYPERLINK" to -1), 361 to ("AVERAGEA" to -1), 362 to ("MAXA" to -1), 363 to ("MINA" to -1),
        364 to ("STDEVPA" to -1), 365 to ("VARPA" to -1), 366 to ("STDEVA" to -1), 367 to ("VARA" to -1), 368 to ("COM.MICROSOFT.BAHTTEXT" to 1),
        384 to ("HEX2BIN" to -1), 385 to ("HEX2DEC" to 1), 386 to ("HEX2OCT" to -1), 387 to ("DEC2BIN" to -1), 388 to ("DEC2HEX" to -1),
        389 to ("DEC2OCT" to -1), 390 to ("OCT2BIN" to -1), 391 to ("OCT2HEX" to -1), 392 to ("OCT2DEC" to 1), 393 to ("BIN2DEC" to 1),
        394 to ("BIN2OCT" to -1), 395 to ("BIN2HEX" to -1), 396 to ("IMSUB" to 2), 397 to ("IMDIV" to 2), 398 to ("IMPOWER" to 2), 399 to ("IMABS" to 1),
        400 to ("IMSQRT" to 1), 401 to ("IMLN" to 1), 402 to ("IMLOG2" to 1), 403 to ("IMLOG10" to 1), 404 to ("IMSIN" to 1), 405 to ("IMCOS" to 1),
        406 to ("IMEXP" to 1), 407 to ("IMARGUMENT" to 1), 408 to ("IMCONJUGATE" to 1), 409 to ("IMAGINARY" to 1), 410 to ("IMREAL" to 1),
        411 to ("COMPLEX" to -1), 412 to ("IMSUM" to -1), 413 to ("IMPRODUCT" to -1), 414 to ("SERIESSUM" to 4), 415 to ("FACTDOUBLE" to 1),
        416 to ("SQRTPI" to 1), 417 to ("QUOTIENT" to 2), 418 to ("DELTA" to -1), 419 to ("GESTEP" to -1), 420 to ("ISEVEN" to 1), 421 to ("ISODD" to 1),
        422 to ("MROUND" to 2), 423 to ("ERF" to -1), 424 to ("ERFC" to 1), 425 to ("BESSELJ" to 2), 426 to ("BESSELK" to 2), 427 to ("BESSELY" to 2),
        428 to ("BESSELI" to 2), 429 to ("XIRR" to -1), 430 to ("XNPV" to 3), 431 to ("PRICEMAT" to -1), 432 to ("YIELDMAT" to -1), 433 to ("INTRATE" to -1),
        434 to ("RECEIVED" to -1), 435 to ("DISC" to -1), 436 to ("PRICEDISC" to -1), 437 to ("YIELDDISC" to -1), 438 to ("TBILLEQ" to 3),
        439 to ("TBILLPRICE" to 3), 440 to ("TBILLYIELD" to 3), 441 to ("PRICE" to -1), 442 to ("YIELD" to -1), 443 to ("DOLLARDE" to 2),
        444 to ("DOLLARFR" to 2), 445 to ("NOMINAL" to 2), 446 to ("EFFECT" to 2), 447 to ("CUMPRINC" to 6), 448 to ("CUMIPMT" to 6), 449 to ("EDATE" to 2),
        450 to ("EOMONTH" to 2), 451 to ("YEARFRAC" to -1), 452 to ("COUPDAYBS" to -1), 453 to ("COUPDAYS" to -1), 454 to ("COUPDAYSNC" to -1),
        455 to ("COUPNCD" to -1), 456 to ("COUPNUM" to -1), 457 to ("COUPPCD" to -1), 458 to ("DURATION" to -1), 459 to ("MDURATION" to -1),
        460 to ("ODDLPRICE" to -1), 461 to ("ODDLYIELD" to -1), 462 to ("ODDFPRICE" to -1), 463 to ("ODDFYIELD" to -1), 464 to ("RANDBETWEEN" to 2),
        465 to ("WEEKNUM" to -1), 466 to ("AMORDEGRC" to -1), 467 to ("AMORLINC" to -1), 468 to ("CONVERT" to 3), 469 to ("ACCRINT" to -1),
        470 to ("ACCRINTM" to -1), 471 to ("WORKDAY" to -1), 472 to ("NETWORKDAYS" to -1), 473 to ("GCD" to -1), 474 to ("MULTINOMIAL" to -1),
        475 to ("LCM" to -1), 476 to ("FVSCHEDULE" to 2), 480 to ("IFERROR" to 2), 481 to ("COUNTIFS" to -1), 482 to ("SUMIFS" to -1),
        483 to ("AVERAGEIF" to -1), 484 to ("AVERAGEIFS" to -1)
    )

    private fun ref(row: Int, col: Int, rowRel: Boolean, colRel: Boolean): String =
        (if (colRel) "" else "$") + SheetConditionalFormats.colLetters(col.coerceIn(0, 16383)) + (if (rowRel) "" else "$") + (row.coerceAtLeast(0) + 1)

    private fun i32(b: ByteArray, p: Int) = u32(b, p)

    fun formula12(b: ByteArray, start: Int, end: Int, top: Int, left: Int): String? {
        if (end > b.size || start >= end) return null
        val st = ArrayList<String>()
        var p = start
        fun pop(): String = if (st.isEmpty()) throw IllegalStateException() else st.removeAt(st.size - 1)
        fun col12(v: Int, rel: Boolean, base: Int): Pair<Int, Boolean> {
            val colRel = (v and 0x4000) != 0
            val raw = v and 0x3FFF
            return (if (rel && colRel) base + (if (raw and 0x2000 != 0) raw - 0x4000 else raw) else raw) to colRel
        }
        try {
            while (p < end) {
                val id = b[p].toInt() and 0xFF
                p++
                when {
                    id in 0x03..0x0E -> {
                        val r = pop(); val l = pop()
                        st.add(l + arrayOf("+", "-", "*", "/", "^", "&", "<", "<=", "=", ">=", ">", "<>")[id - 3] + r)
                    }
                    id == 0x12 -> st.add("+" + pop())
                    id == 0x13 -> st.add("-" + pop())
                    id == 0x14 -> st.add(pop() + "%")
                    id == 0x15 -> st.add("(" + pop() + ")")
                    id == 0x16 -> st.add("")
                    id == 0x17 -> {
                        val cch = u16(b, p)
                        val txt = String(b, p + 2, cch * 2, Charsets.UTF_16LE)
                        st.add("\"" + txt.replace("\"", "\"\"") + "\"")
                        p += 2 + cch * 2
                    }
                    id == 0x19 -> {
                        val grbit = b[p].toInt() and 0xFF
                        if ((grbit and 0x04) != 0) return null
                        if ((grbit and 0x10) != 0) st.add("SUM(" + pop() + ")")
                        p += 3
                    }
                    id == 0x1C -> { st.add(when (b[p].toInt() and 0xFF) { 0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; else -> "#N/A" }); p++ }
                    id == 0x1D -> { st.add(if (b[p].toInt() != 0) "TRUE" else "FALSE"); p++ }
                    id == 0x1E -> { st.add(u16(b, p).toString()); p += 2 }
                    id == 0x1F -> {
                        var bits = 0L
                        for (k in 7 downTo 0) bits = (bits shl 8) or (b[p + k].toLong() and 0xFF)
                        st.add(ExcelFormula.numberText(Double.fromBits(bits)))
                        p += 8
                    }
                    id >= 0x20 -> {
                        val base = (id and 0x1F) or 0x20
                        when (base) {
                            0x21 -> {
                                val f = u16(b, p); p += 2
                                val (name, n) = FTAB12[f] ?: return null
                                if (n < 0) return null
                                val args = List(n) { pop() }.reversed()
                                st.add(name + "(" + args.joinToString(",") + ")")
                            }
                            0x22 -> {
                                val n = b[p].toInt() and 0x7F
                                val f = u16(b, p + 1) and 0x7FFF
                                p += 3
                                val args = List(n) { pop() }.reversed()
                                if (f == 255) return null
                                val name = FTAB12[f]?.first ?: return null
                                st.add(name + "(" + args.joinToString(",") + ")")
                            }
                            0x24, 0x2C -> {
                                val rw = i32(b, p)
                                val cf = u16(b, p + 4)
                                p += 6
                                val rel = base == 0x2C
                                val rowRel = (cf and 0x8000) != 0
                                val (col, colRel) = col12(cf, rel, left)
                                val row = if (rel && rowRel) top + rw else rw
                                st.add(ref(row, col, rowRel, colRel))
                            }
                            0x25, 0x2D -> {
                                val r0 = i32(b, p); val r1 = i32(b, p + 4); val c0 = u16(b, p + 8); val c1 = u16(b, p + 10)
                                p += 12
                                val rel = base == 0x2D
                                val (cc0, cr0) = col12(c0, rel, left)
                                val (cc1, cr1) = col12(c1, rel, left)
                                val rr0 = if (rel && (c0 and 0x8000) != 0) top + r0 else r0
                                val rr1 = if (rel && (c1 and 0x8000) != 0) top + r1 else r1
                                st.add(ref(rr0, cc0, (c0 and 0x8000) != 0, cr0) + ":" + ref(rr1, cc1, (c1 and 0x8000) != 0, cr1))
                            }
                            0x2A -> { st.add("#REF!"); p += 6 }
                            0x2B -> { st.add("#REF!"); p += 12 }
                            else -> return null
                        }
                    }
                    else -> return null
                }
            }
        } catch (_: Exception) {
            return null
        }
        return st.singleOrNull()
    }

    fun formula(b: ByteArray, start: Int, end: Int, top: Int, left: Int, names: ((Int, Int) -> String?)? = null): String? {
        if (end > b.size || start >= end) return null
        val st = ArrayList<String>()
        var p = start
        fun pop(): String = if (st.isEmpty()) throw IllegalStateException() else st.removeAt(st.size - 1)
        try {
            while (p < end) {
                val id = b[p].toInt() and 0xFF
                p++
                when {
                    id in 0x03..0x0E -> {
                        val r = pop(); val l = pop()
                        val op = arrayOf("+", "-", "*", "/", "^", "&", "<", "<=", "=", ">=", ">", "<>")[id - 3]
                        st.add(l + op + r)
                    }
                    id == 0x12 -> st.add("+" + pop())
                    id == 0x13 -> st.add("-" + pop())
                    id == 0x14 -> st.add(pop() + "%")
                    id == 0x15 -> st.add("(" + pop() + ")")
                    id == 0x16 -> st.add("")
                    id == 0x17 -> {
                        val cch = b[p].toInt() and 0xFF
                        val hi = (b[p + 1].toInt() and 1) != 0
                        val s = if (hi) String(b, p + 2, cch * 2, Charsets.UTF_16LE) else String(b, p + 2, cch, Charsets.ISO_8859_1)
                        st.add("\"" + s.replace("\"", "\"\"") + "\"")
                        p += 2 + if (hi) cch * 2 else cch
                    }
                    id == 0x19 -> {
                        val grbit = b[p].toInt() and 0xFF
                        if ((grbit and 0x04) != 0) return null
                        if ((grbit and 0x10) != 0) st.add("SUM(" + pop() + ")")
                        p += 3
                    }
                    id == 0x1C -> { st.add("#ERR!"); p++ }
                    id == 0x1D -> { st.add(if (b[p].toInt() != 0) "TRUE" else "FALSE"); p++ }
                    id == 0x1E -> { st.add(u16(b, p).toString()); p += 2 }
                    id == 0x1F -> {
                        var bits = 0L
                        for (k in 7 downTo 0) bits = (bits shl 8) or (b[p + k].toLong() and 0xFF)
                        st.add(ExcelNumberFormat.general(Double.fromBits(bits)).replace(ExcelNumberFormat.decimalSeparator, '.').replace("E+", "E"))
                        p += 8
                    }
                    id >= 0x20 -> {
                        when ((id and 0x1F) or 0x20) {
                            0x21 -> {
                                val f = u16(b, p); p += 2
                                val (name, n) = FTAB[f] ?: return null
                                if (n < 0) return null
                                val args = List(n) { pop() }.reversed()
                                st.add(name + "(" + args.joinToString(",") + ")")
                            }
                            0x22 -> {
                                val n = b[p].toInt() and 0x7F
                                val f = u16(b, p + 1) and 0x7FFF
                                p += 3
                                val args = List(n) { pop() }.reversed()
                                if (f == 255) {
                                    val fn = args.firstOrNull()?.takeIf { it.startsWith("@") }?.substring(1) ?: return null
                                    st.add(fn + "(" + args.drop(1).joinToString(",") + ")")
                                } else {
                                    val name = FTAB[f]?.first ?: return null
                                    st.add(name + "(" + args.joinToString(",") + ")")
                                }
                            }
                            0x39 -> {
                                val ixti = u16(b, p); val iname = u16(b, p + 2)
                                p += 6
                                val n = names?.invoke(ixti, iname) ?: return null
                                st.add("@" + n)
                            }
                            0x24, 0x2C -> {
                                val rw = u16(b, p)
                                val cf = u16(b, p + 2)
                                p += 4
                                val rowRel = (cf and 0x8000) != 0
                                val colRel = (cf and 0x4000) != 0
                                val rel = ((id and 0x1F) or 0x20) == 0x2C
                                val row = if (rel && rowRel) top + rw.toShort().toInt() else rw
                                val col = if (rel && colRel) left + (cf and 0xFF).toByte().toInt() else cf and 0x3FFF
                                st.add(ref(row, col, rowRel, colRel))
                            }
                            0x25, 0x2D -> {
                                val r0 = u16(b, p); val r1 = u16(b, p + 2); val c0 = u16(b, p + 4); val c1 = u16(b, p + 6)
                                p += 8
                                val rel = ((id and 0x1F) or 0x20) == 0x2D
                                fun rr(v: Int, flag: Int) = if (rel && (flag and 0x8000) != 0) top + v.toShort().toInt() else v
                                fun cc(v: Int) = if (rel && (v and 0x4000) != 0) left + (v and 0xFF).toByte().toInt() else v and 0x3FFF
                                st.add(ref(rr(r0, c0), cc(c0), (c0 and 0x8000) != 0, (c0 and 0x4000) != 0) + ":" + ref(rr(r1, c1), cc(c1), (c1 and 0x8000) != 0, (c1 and 0x4000) != 0))
                            }
                            0x2A -> { st.add("#REF!"); p += 4 }
                            else -> return null
                        }
                    }
                    else -> return null
                }
            }
        } catch (_: Exception) {
            return null
        }
        return st.singleOrNull()
    }
}
