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

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Color
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class SheetDataBar(
    val start: Float,
    val end: Float,
    val color: Int,
    val gradient: Boolean,
    val border: Int?,
    val axis: Float = -1f,
    val axisColor: Int = Color.BLACK
)

class SheetIcon(val shape: Int, val color: Int, val level: Int = 0)

class SheetConditional {
    val styles = HashMap<Long, SheetCellStyle>()
    val bars = HashMap<Long, SheetDataBar>()
    val icons = HashMap<Long, SheetIcon>()
    val texts = HashMap<Long, String>()
    val hideText = HashSet<Long>()
    val isEmpty: Boolean get() = styles.isEmpty() && bars.isEmpty() && icons.isEmpty() && texts.isEmpty()
}

class CfStyle(
    val fill: SheetFill? = null,
    val color: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Int? = null,
    val strike: Boolean? = null,
    val left: SheetBorder? = null,
    val right: SheetBorder? = null,
    val top: SheetBorder? = null,
    val bottom: SheetBorder? = null,
    val numFmt: String? = null
) {
    fun merge(lower: CfStyle): CfStyle = CfStyle(
        fill ?: lower.fill, color ?: lower.color, bold ?: lower.bold, italic ?: lower.italic,
        underline ?: lower.underline, strike ?: lower.strike, left ?: lower.left, right ?: lower.right,
        top ?: lower.top, bottom ?: lower.bottom, numFmt ?: lower.numFmt
    )

    fun applyTo(base: SheetCellStyle): SheetCellStyle {
        var s = base
        if (fill != null) s = s.withFill(fill)
        return s.copy(
            textColor = color ?: s.textColor,
            bold = bold ?: s.bold,
            italic = italic ?: s.italic,
            underline = underline ?: s.underline,
            strike = strike ?: s.strike,
            left = left ?: s.left,
            right = right ?: s.right,
            top = top ?: s.top,
            bottom = bottom ?: s.bottom
        )
    }
}

class CfValueObject(val type: String, val value: String? = null, val gte: Boolean = true)

data class CfRule(
    val type: String,
    val priority: Int = Int.MAX_VALUE,
    val stopIfTrue: Boolean = false,
    val operator: String? = null,
    val formulas: List<String> = emptyList(),
    val text: String? = null,
    val style: CfStyle? = null,
    val cfvos: List<CfValueObject> = emptyList(),
    val colors: List<Int> = emptyList(),
    val iconSet: String = "3TrafficLights1",
    val reverse: Boolean = false,
    val showValue: Boolean = true,
    val rank: Int = 10,
    val percent: Boolean = false,
    val bottom: Boolean = false,
    val aboveAverage: Boolean = true,
    val equalAverage: Boolean = false,
    val stdDev: Int = 0,
    val timePeriod: String? = null,
    val barGradient: Boolean = true,
    val barBorder: Int? = null,
    val barNegative: Int? = null,
    val barAxisColor: Int? = null,
    val barMinLength: Float = 0.1f,
    val barMaxLength: Float = 0.9f,
    val barAxisNone: Boolean = false,
    val customIcons: List<Pair<String, Int>>? = null
)

class CfBlock(val ranges: List<IntArray>, val rules: List<CfRule>)

interface CfCells {
    fun value(row: Int, col: Int, sheet: String? = null): Any?
    fun name(name: String): String? = null
}

object SheetConditionalFormats {
    const val ICON_ARROW_UP = 1
    const val ICON_ARROW_UP_RIGHT = 2
    const val ICON_ARROW_RIGHT = 3
    const val ICON_ARROW_DOWN_RIGHT = 4
    const val ICON_ARROW_DOWN = 5
    const val ICON_CIRCLE = 6
    const val ICON_TRIANGLE_UP = 7
    const val ICON_DASH = 8
    const val ICON_TRIANGLE_DOWN = 9
    const val ICON_CHECK = 10
    const val ICON_EXCLAMATION = 11
    const val ICON_CROSS = 12
    const val ICON_FLAG = 13
    const val ICON_DIAMOND = 14
    const val ICON_STAR = 15
    const val ICON_BARS = 16
    const val ICON_QUARTERS = 17
    const val ICON_BOXES = 18

    private val GREEN = Color.rgb(0x00, 0xB0, 0x50)
    private val YELLOW = Color.rgb(0xFF, 0xC0, 0x00)
    private val RED = Color.rgb(0xC0, 0x00, 0x00)
    private val GRAY = Color.rgb(0x80, 0x80, 0x80)
    private val BLACK = Color.rgb(0x40, 0x40, 0x40)
    private val PINK = Color.rgb(0xFF, 0x99, 0x99)
    private val BLUE = Color.rgb(0x44, 0x72, 0xC4)

    fun iconSet(name: String): List<SheetIcon> = when (name) {
        "3Arrows" -> listOf(SheetIcon(ICON_ARROW_DOWN, RED), SheetIcon(ICON_ARROW_RIGHT, YELLOW), SheetIcon(ICON_ARROW_UP, GREEN))
        "3ArrowsGray" -> listOf(SheetIcon(ICON_ARROW_DOWN, GRAY), SheetIcon(ICON_ARROW_RIGHT, GRAY), SheetIcon(ICON_ARROW_UP, GRAY))
        "3Flags" -> listOf(SheetIcon(ICON_FLAG, RED), SheetIcon(ICON_FLAG, YELLOW), SheetIcon(ICON_FLAG, GREEN))
        "3Signs" -> listOf(SheetIcon(ICON_DIAMOND, RED), SheetIcon(ICON_TRIANGLE_UP, YELLOW), SheetIcon(ICON_CIRCLE, GREEN))
        "3Symbols", "3Symbols2" -> listOf(SheetIcon(ICON_CROSS, RED), SheetIcon(ICON_EXCLAMATION, YELLOW), SheetIcon(ICON_CHECK, GREEN))
        "3Stars" -> listOf(SheetIcon(ICON_STAR, YELLOW, 0), SheetIcon(ICON_STAR, YELLOW, 1), SheetIcon(ICON_STAR, YELLOW, 2))
        "3Triangles" -> listOf(SheetIcon(ICON_TRIANGLE_DOWN, RED), SheetIcon(ICON_DASH, YELLOW), SheetIcon(ICON_TRIANGLE_UP, GREEN))
        "4Arrows" -> listOf(SheetIcon(ICON_ARROW_DOWN, RED), SheetIcon(ICON_ARROW_DOWN_RIGHT, YELLOW), SheetIcon(ICON_ARROW_UP_RIGHT, YELLOW), SheetIcon(ICON_ARROW_UP, GREEN))
        "4ArrowsGray" -> listOf(SheetIcon(ICON_ARROW_DOWN, GRAY), SheetIcon(ICON_ARROW_DOWN_RIGHT, GRAY), SheetIcon(ICON_ARROW_UP_RIGHT, GRAY), SheetIcon(ICON_ARROW_UP, GRAY))
        "4RedToBlack" -> listOf(SheetIcon(ICON_CIRCLE, BLACK), SheetIcon(ICON_CIRCLE, GRAY), SheetIcon(ICON_CIRCLE, PINK), SheetIcon(ICON_CIRCLE, RED))
        "4Rating" -> List(4) { SheetIcon(ICON_BARS, BLUE, it + 1) }
        "4TrafficLights" -> listOf(SheetIcon(ICON_CIRCLE, BLACK), SheetIcon(ICON_CIRCLE, RED), SheetIcon(ICON_CIRCLE, YELLOW), SheetIcon(ICON_CIRCLE, GREEN))
        "5Arrows" -> listOf(SheetIcon(ICON_ARROW_DOWN, RED), SheetIcon(ICON_ARROW_DOWN_RIGHT, YELLOW), SheetIcon(ICON_ARROW_RIGHT, YELLOW), SheetIcon(ICON_ARROW_UP_RIGHT, YELLOW), SheetIcon(ICON_ARROW_UP, GREEN))
        "5ArrowsGray" -> listOf(SheetIcon(ICON_ARROW_DOWN, GRAY), SheetIcon(ICON_ARROW_DOWN_RIGHT, GRAY), SheetIcon(ICON_ARROW_RIGHT, GRAY), SheetIcon(ICON_ARROW_UP_RIGHT, GRAY), SheetIcon(ICON_ARROW_UP, GRAY))
        "5Rating" -> List(5) { SheetIcon(ICON_BARS, BLUE, it) }
        "5Quarters" -> List(5) { SheetIcon(ICON_QUARTERS, BLACK, it) }
        "5Boxes" -> List(5) { SheetIcon(ICON_BOXES, BLUE, it) }
        else -> listOf(SheetIcon(ICON_CIRCLE, RED), SheetIcon(ICON_CIRCLE, YELLOW), SheetIcon(ICON_CIRCLE, GREEN))
    }

    private fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> null
        else -> null
    }

    private fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> ExcelNumberFormat.general(v)
        is Boolean -> if (v) "TRUE" else "FALSE"
        is FormulaError -> v.code
        else -> v.toString()
    }

    private fun isError(v: Any?): Boolean = v is FormulaError || (v is String && v.startsWith("#") && (v.endsWith("!") || v.endsWith("?") || v == "#N/A"))

    private val REF = Regex("""('(?:[^']|'')+'!|[A-Za-z_][A-Za-z0-9_.]*!)?(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})(?![A-Za-z0-9_(])""")

    fun shiftFormula(expr: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return expr
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        var seg = StringBuilder()
        fun flush() {
            sb.append(REF.replace(seg) { m ->
                val start = m.range.first
                if (start > 0 && (seg[start - 1].isLetterOrDigit() || seg[start - 1] == '_')) return@replace m.value
                val colAbs = m.groupValues[2].isNotEmpty()
                val rowAbs = m.groupValues[4].isNotEmpty()
                var col = colIndex(m.groupValues[3])
                var row = m.groupValues[5].toInt()
                if (col < 0) return@replace m.value
                if (!colAbs) col += dCol
                if (!rowAbs) row += dRow
                if (col < 0 || row < 1) return@replace "#REF!"
                m.groupValues[1] + m.groupValues[2] + colLetters(col) + m.groupValues[4] + row
            })
            seg = StringBuilder()
        }
        while (i < expr.length) {
            val ch = expr[i]
            if (ch == '"') {
                if (!inStr) flush()
                inStr = !inStr
                sb.append(ch)
            } else if (inStr) sb.append(ch) else seg.append(ch)
            i++
        }
        flush()
        return sb.toString()
    }

    fun colIndex(letters: String): Int {
        var c = 0
        for (ch in letters.uppercase(Locale.ROOT)) {
            if (ch !in 'A'..'Z') return -1
            c = c * 26 + (ch - 'A' + 1)
        }
        return if (c in 1..16384) c - 1 else -1
    }

    fun colLetters(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            sb.append('A' + (n - 1) % 26)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun parseSqref(sqref: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (part in sqref.trim().split(Regex("[\\s,]+"))) {
            if (part.isEmpty()) continue
            val pieces = part.substringAfter('!').split(':')
            val a = cellRef(pieces[0]) ?: continue
            val b = if (pieces.size > 1) cellRef(pieces[1]) ?: continue else a
            out.add(intArrayOf(minOf(a[0], b[0]), maxOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[1], b[1])))
            if (out.size > 512) break
        }
        return out
    }

    private fun cellRef(ref: String): IntArray? {
        val m = Regex("""^\$?([A-Za-z]{1,3})\$?(\d{1,7})$""").find(ref.trim()) ?: return null
        val c = colIndex(m.groupValues[1])
        val r = m.groupValues[2].toInt() - 1
        if (c < 0 || r < 0) return null
        return intArrayOf(r, c)
    }

    private fun evalOperand(expr: String, row: Int, col: Int, ctx: FormulaContext): Any? {
        val t = expr.trim().removePrefix("=").trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it }
        return ExcelFormula.evaluate(t, ctx, row, col)
    }

    private fun formulaContext(cells: CfCells, rows: Int, cols: Int, date1904: Boolean): FormulaContext = object : FormulaContext {
        override fun cell(sheet: String?, row: Int, col: Int): Any? = when (val v = cells.value(row, col, sheet)) {
            is String -> if (v.startsWith("#")) FormulaError.parse(v) ?: v else v
            is Number -> v.toDouble()
            else -> v
        }
        override fun bounds(sheet: String?): IntArray = intArrayOf(rows, cols)
        override val date1904: Boolean get() = date1904
        override fun name(name: String): String? = cells.name(name)
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Double -> v != 0.0
        is Number -> v.toDouble() != 0.0
        is String -> v.equals("TRUE", true) || (v.toDoubleOrNull()?.let { it != 0.0 } ?: false)
        else -> false
    }

    private fun compare(a: Any?, b: Any?): Int? {
        val an = num(a)
        val bn = num(b)
        if (an != null && bn != null) return an.compareTo(bn)
        if (a == null && bn != null) return 0.0.compareTo(bn)
        if (an != null && b == null) return an.compareTo(0.0)
        if (an != null && b is String) return -1
        if (a is String && bn != null) return 1
        return str(a).lowercase(Locale.ROOT).compareTo(str(b).lowercase(Locale.ROOT))
    }

    private class Cell(val r: Int, val c: Int, val v: Any?)

    fun apply(
        blocks: List<CfBlock>,
        rowCount: Int,
        colCount: Int,
        cells: CfCells,
        baseStyle: (Int, Int) -> SheetCellStyle?,
        date1904: Boolean,
        out: SheetConditional,
        numberText: ((Double, String, Int, Int) -> String?)? = null
    ) {
        val styleHits = HashMap<Long, CfStyle>()
        val stopped = HashSet<Long>()
        val ordered = ArrayList<Pair<CfBlock, CfRule>>()
        for (b in blocks) for (r in b.rules) ordered.add(b to r)
        ordered.sortBy { it.second.priority }
        var budget = 3_000_000
        val fctx = formulaContext(cells, rowCount, colCount, date1904)
        for ((block, rule) in ordered) {
            val cellsIn = ArrayList<Cell>()
            for (rg in block.ranges) {
                val r1 = minOf(rg[1], rowCount - 1)
                val c1 = minOf(rg[3], colCount - 1)
                for (r in rg[0]..r1) for (c in rg[2]..c1) {
                    if (--budget < 0) break
                    cellsIn.add(Cell(r, c, cells.value(r, c)))
                }
            }
            if (cellsIn.isEmpty()) continue
            val top = block.ranges.minOf { it[0] }
            val left = block.ranges.minOf { it[2] }
            val numbers = cellsIn.mapNotNull { num(it.v) }
            when (rule.type) {
                "colorScale" -> {
                    if (numbers.isEmpty() || rule.colors.size < 2) continue
                    val thresholds = rule.cfvos.mapIndexed { i, o -> threshold(o, numbers, fctx, top, left, if (i == 0) "min" else "max") }
                    if (thresholds.any { it == null } || thresholds.size != rule.colors.size) continue
                    val th = thresholds.map { it!! }
                    for (cell in cellsIn) {
                        val key = SpreadsheetGridSession.packCoord(cell.r, cell.c)
                        if (key in stopped) continue
                        val v = num(cell.v) ?: continue
                        val color = scaleColor(v, th, rule.colors)
                        val prev = styleHits[key]
                        val st = CfStyle(fill = SheetFill(color))
                        styleHits[key] = if (prev == null) st else prev.merge(st)
                    }
                }
                "dataBar" -> {
                    if (numbers.isEmpty()) continue
                    val minT = threshold(rule.cfvos.getOrNull(0) ?: CfValueObject("min"), numbers, fctx, top, left, "min") ?: continue
                    val maxT = threshold(rule.cfvos.getOrNull(1) ?: CfValueObject("max"), numbers, fctx, top, left, "max") ?: continue
                    val color = rule.colors.firstOrNull() ?: Color.rgb(0x63, 0x8E, 0xC6)
                    val lo = minOf(minT, maxT)
                    val hi = maxOf(minT, maxT)
                    val hasNeg = lo < 0 && !rule.barAxisNone
                    for (cell in cellsIn) {
                        val key = SpreadsheetGridSession.packCoord(cell.r, cell.c)
                        if (key in stopped) continue
                        val v = num(cell.v) ?: continue
                        val bar = if (hasNeg && hi > 0) {
                            val axis = (-lo / (hi - lo)).toFloat()
                            val pos = ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()
                            SheetDataBar(minOf(axis, pos), maxOf(axis, pos), if (v < 0) rule.barNegative ?: Color.rgb(0xFF, 0x00, 0x00) else color, rule.barGradient, if (v < 0) rule.barNegative ?: rule.barBorder else rule.barBorder, axis, rule.barAxisColor ?: Color.BLACK)
                        } else if (hasNeg) {
                            val frac = if (lo == hi) 1f else ((hi - v.coerceIn(lo, hi)) / (hi - lo)).toFloat()
                            SheetDataBar(1f - (rule.barMinLength + frac * (rule.barMaxLength - rule.barMinLength)), 1f, rule.barNegative ?: Color.rgb(0xFF, 0x00, 0x00), rule.barGradient, rule.barNegative ?: rule.barBorder, 1f, rule.barAxisColor ?: Color.BLACK)
                        } else {
                            val frac = if (hi == lo) (if (v >= hi) 1f else 0f) else ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()
                            SheetDataBar(0f, rule.barMinLength + frac * (rule.barMaxLength - rule.barMinLength), color, rule.barGradient, rule.barBorder)
                        }
                        out.bars.putIfAbsent(key, bar)
                        if (!rule.showValue) out.hideText.add(key)
                    }
                }
                "iconSet" -> {
                    if (numbers.isEmpty()) continue
                    val icons = rule.customIcons?.mapNotNull { (set, idx) -> iconSet(set).getOrNull(idx) } ?: iconSet(rule.iconSet)
                    val n = rule.cfvos.size.coerceAtMost(icons.size)
                    if (n == 0) continue
                    val ths = rule.cfvos.take(n).map { threshold(it, numbers, fctx, top, left, "min") }
                    for (cell in cellsIn) {
                        val key = SpreadsheetGridSession.packCoord(cell.r, cell.c)
                        if (key in stopped) continue
                        val v = num(cell.v) ?: continue
                        var idx = 0
                        for (k in 0 until n) {
                            val t = ths[k] ?: continue
                            val ok = if (rule.cfvos[k].gte) v >= t else v > t
                            if (ok) idx = k
                        }
                        val icon = icons.getOrNull(if (rule.reverse) n - 1 - idx else idx) ?: continue
                        out.icons.putIfAbsent(key, icon)
                        if (!rule.showValue) out.hideText.add(key)
                    }
                }
                else -> {
                    val style = rule.style ?: CfStyle()
                    val matcher = matcher(rule, cellsIn, numbers, fctx, top, left, date1904) ?: continue
                    for (cell in cellsIn) {
                        val key = SpreadsheetGridSession.packCoord(cell.r, cell.c)
                        if (key in stopped) continue
                        if (!matcher(cell)) continue
                        val prev = styleHits[key]
                        styleHits[key] = if (prev == null) style else prev.merge(style)
                        if (rule.stopIfTrue) stopped.add(key)
                    }
                }
            }
        }
        for ((key, st) in styleHits) {
            val r = (key ushr 32).toInt()
            val c = (key and 0xFFFFFFFFL).toInt()
            out.styles[key] = st.applyTo(baseStyle(r, c) ?: SheetCellStyle.DEFAULT)
            val fmt = st.numFmt
            if (fmt != null && numberText != null) {
                num(cells.value(r, c))?.let { v -> numberText(v, fmt, r, c)?.let { out.texts[key] = it } }
            }
        }
    }

    private fun matcher(rule: CfRule, cellsIn: List<Cell>, numbers: List<Double>, cells: FormulaContext, top: Int, left: Int, date1904: Boolean): ((Cell) -> Boolean)? {
        fun operand(i: Int, cell: Cell): Any? {
            val f = rule.formulas.getOrNull(i) ?: return null
            return evalOperand(shiftFormula(f, cell.r - top, cell.c - left), cell.r, cell.c, cells)
        }
        return when (rule.type) {
            "cellIs" -> { cell ->
                val a = operand(0, cell)
                val v = cell.v ?: if (num(a) != null) 0.0 else ""
                when (rule.operator) {
                    "lessThan" -> (compare(v, a) ?: 0) < 0
                    "lessThanOrEqual" -> (compare(v, a) ?: 1) <= 0
                    "equal" -> compare(v, a) == 0
                    "notEqual" -> compare(v, a) != 0
                    "greaterThanOrEqual" -> (compare(v, a) ?: -1) >= 0
                    "greaterThan" -> (compare(v, a) ?: 0) > 0
                    "between", "notBetween" -> {
                        val b = operand(1, cell)
                        val lo = if ((compare(a, b) ?: 0) <= 0) a else b
                        val hi = if (lo === a) b else a
                        val inside = (compare(v, lo) ?: -1) >= 0 && (compare(v, hi) ?: 1) <= 0
                        if (rule.operator == "between") inside else !inside
                    }
                    "containsText" -> str(v).contains(str(a), true)
                    "notContains" -> !str(v).contains(str(a), true)
                    "beginsWith" -> str(v).startsWith(str(a), true)
                    "endsWith" -> str(v).endsWith(str(a), true)
                    else -> false
                }
            }
            "expression" -> { cell -> truthy(operand(0, cell)) }
            "containsText" -> { cell -> str(cell.v).contains(rule.text ?: str(operand(0, cell)), true) }
            "notContainsText" -> { cell -> !str(cell.v).contains(rule.text ?: str(operand(0, cell)), true) }
            "beginsWith" -> { cell -> str(cell.v).startsWith(rule.text ?: str(operand(0, cell)), true) }
            "endsWith" -> { cell -> str(cell.v).endsWith(rule.text ?: str(operand(0, cell)), true) }
            "containsBlanks" -> { cell -> str(cell.v).isBlank() }
            "notContainsBlanks" -> { cell -> str(cell.v).isNotBlank() }
            "containsErrors" -> { cell -> isError(cell.v) }
            "notContainsErrors" -> { cell -> !isError(cell.v) }
            "duplicateValues", "uniqueValues" -> {
                val counts = HashMap<String, Int>()
                for (c in cellsIn) if (c.v != null && str(c.v).isNotEmpty()) counts.merge(str(c.v).lowercase(Locale.ROOT), 1, Int::plus)
                val dup = rule.type == "duplicateValues";
                { cell -> cell.v != null && str(cell.v).isNotEmpty() && ((counts[str(cell.v).lowercase(Locale.ROOT)] ?: 0) > 1) == dup }
            }
            "top10" -> {
                if (numbers.isEmpty()) return null
                val sorted = numbers.sortedDescending()
                val n = if (rule.percent) maxOf(1, floor(numbers.size * rule.rank / 100.0).toInt()) else rule.rank.coerceAtLeast(1)
                if (rule.bottom) {
                    val limit = numbers.sorted()[minOf(n, numbers.size) - 1];
                    { cell -> num(cell.v)?.let { it <= limit } == true }
                } else {
                    val limit = sorted[minOf(n, sorted.size) - 1];
                    { cell -> num(cell.v)?.let { it >= limit } == true }
                }
            }
            "aboveAverage" -> {
                if (numbers.isEmpty()) return null
                val avg = numbers.average()
                val sd = if (rule.stdDev > 0) sqrt(numbers.sumOf { (it - avg) * (it - avg) } / numbers.size) * rule.stdDev else 0.0
                val limit = if (rule.aboveAverage) avg + sd else avg - sd;
                { cell ->
                    val v = num(cell.v)
                    v != null && if (rule.aboveAverage) (if (rule.equalAverage) v >= limit else v > limit) else (if (rule.equalAverage) v <= limit else v < limit)
                }
            }
            "timePeriod" -> {
                val today = todaySerial(date1904);
                { cell -> num(cell.v)?.let { inPeriod(floor(it).toLong(), today, rule.timePeriod, date1904) } == true }
            }
            else -> null
        }
    }

    private fun todaySerial(date1904: Boolean): Long {
        val cal = Calendar.getInstance()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        val days = utc.timeInMillis / 86_400_000L
        return days + (if (date1904) 24107L else 25569L)
    }

    private fun weekday(serial: Long, date1904: Boolean): Int {
        val unixDays = serial - (if (date1904) 24107L else 25569L)
        return (Math.floorMod(unixDays + 4, 7L)).toInt()
    }

    private fun monthIndex(serial: Long, date1904: Boolean): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = (serial - (if (date1904) 24107L else 25569L)) * 86_400_000L
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    private fun inPeriod(d: Long, today: Long, period: String?, date1904: Boolean): Boolean {
        val weekStart = today - weekday(today, date1904)
        return when (period) {
            "today" -> d == today
            "yesterday" -> d == today - 1
            "tomorrow" -> d == today + 1
            "last7Days" -> d in (today - 6)..today
            "thisWeek" -> d in weekStart..(weekStart + 6)
            "lastWeek" -> d in (weekStart - 7)..(weekStart - 1)
            "nextWeek" -> d in (weekStart + 7)..(weekStart + 13)
            "thisMonth" -> monthIndex(d, date1904) == monthIndex(today, date1904)
            "lastMonth" -> monthIndex(d, date1904) == monthIndex(today, date1904) - 1
            "nextMonth" -> monthIndex(d, date1904) == monthIndex(today, date1904) + 1
            else -> false
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (sorted.size - 1) * (p / 100.0).coerceIn(0.0, 1.0)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun threshold(o: CfValueObject, numbers: List<Double>, cells: FormulaContext, top: Int, left: Int, auto: String): Double? {
        val mn = numbers.minOrNull() ?: return null
        val mx = numbers.maxOrNull() ?: return null
        val v = o.value
        return when (o.type) {
            "min" -> mn
            "max" -> mx
            "autoMin" -> minOf(mn, 0.0)
            "autoMax" -> maxOf(mx, 0.0)
            "num" -> v?.toDoubleOrNull() ?: num(evalOperand(v ?: return null, top, left, cells))
            "percent" -> { val p = v?.toDoubleOrNull() ?: num(evalOperand(v ?: return null, top, left, cells)) ?: return null; mn + (mx - mn) * p / 100.0 }
            "percentile" -> { val p = v?.toDoubleOrNull() ?: return null; percentile(numbers.sorted(), p) }
            "formula" -> num(evalOperand(v ?: return null, top, left, cells))
            else -> if (auto == "min") mn else mx
        }
    }

    private fun scaleColor(v: Double, th: List<Double>, colors: List<Int>): Int {
        if (v <= th.first()) return colors.first()
        if (v >= th.last()) return colors.last()
        for (i in 0 until th.size - 1) {
            val a = th[i]
            val b = th[i + 1]
            if (v in a..b) {
                val t = if (b == a) 0f else ((v - a) / (b - a)).toFloat()
                return SheetCellStyle.blend(colors[i], colors[i + 1], t)
            }
        }
        return colors.last()
    }

    fun parseColorHex(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        return try { Color.parseColor("#" + h.takeLast(6)) } catch (_: Exception) { null }
    }
}
