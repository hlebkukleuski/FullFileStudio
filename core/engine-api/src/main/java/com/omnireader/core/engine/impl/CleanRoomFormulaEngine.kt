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

package com.omnireader.core.engine.impl

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class FormulaError(val code: String) {
    override fun toString(): String = code
    override fun equals(other: Any?): Boolean = other is FormulaError && other.code == code
    override fun hashCode(): Int = code.hashCode()

    companion object {
        val NULL = FormulaError("#NULL!")
        val DIV0 = FormulaError("#DIV/0!")
        val VALUE = FormulaError("#VALUE!")
        val REF = FormulaError("#REF!")
        val NAME = FormulaError("#NAME?")
        val NUM = FormulaError("#NUM!")
        val NA = FormulaError("#N/A")
        val CALC = FormulaError("#CALC!")
        private val ALL = listOf(NULL, DIV0, VALUE, REF, NAME, NUM, NA, CALC, FormulaError("#SPILL!"), FormulaError("#GETTING_DATA"))
        fun parse(s: String): FormulaError? = ALL.firstOrNull { it.code.equals(s.trim(), true) }
    }
}

class FormulaRange(val sheet: String?, val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    val rows: Int get() = r1 - r0 + 1
    val cols: Int get() = c1 - c0 + 1
    val single: Boolean get() = r0 == r1 && c0 == c1
}

class FormulaArray(val rows: Int, val cols: Int, val data: Array<Any?>) {
    operator fun get(r: Int, c: Int): Any? = data[r * cols + c]
}

interface FormulaContext {
    fun cell(sheet: String?, row: Int, col: Int): Any?
    fun bounds(sheet: String?): IntArray? = null
    val date1904: Boolean get() = false
    fun name(name: String): String? = null
}

class ExcelFormula private constructor(private val root: Node) {

    private sealed class Node
    private class NumN(val v: Double) : Node()
    private class StrN(val v: String) : Node()
    private class BoolN(val v: Boolean) : Node()
    private class ErrN(val v: FormulaError) : Node()
    private class RefN(val sheet: String?, val r0: Int, val c0: Int, val r1: Int, val c1: Int, val rowAbs0: Boolean, val colAbs0: Boolean, val rowAbs1: Boolean, val colAbs1: Boolean, val wholeCol: Boolean, val wholeRow: Boolean) : Node()
    private class NameN(val name: String) : Node()
    private class FuncN(val name: String, val args: List<Node>) : Node()
    private class UnN(val op: Char, val x: Node) : Node()
    private class BinN(val op: String, val a: Node, val b: Node) : Node()
    private class PctN(val x: Node) : Node()
    private class ArrN(val rows: List<List<Node>>) : Node()
    private class MissN : Node()

    class Evaluation(val ctx: FormulaContext, val row: Int, val col: Int, val sheet: String?) {
        var depth = 0
    }

    fun evaluate(ctx: FormulaContext, row: Int = 0, col: Int = 0, sheet: String? = null): Any? {
        val ev = Evaluation(ctx, row, col, sheet)
        return try {
            scalarResult(eval(root, ev), ev)
        } catch (e: StackOverflowError) {
            FormulaError.CALC
        } catch (e: Exception) {
            FormulaError.VALUE
        }
    }

    fun evaluateRaw(ctx: FormulaContext, row: Int = 0, col: Int = 0, sheet: String? = null): Any? {
        val ev = Evaluation(ctx, row, col, sheet)
        return try { eval(root, ev) } catch (e: Exception) { FormulaError.VALUE }
    }

    private fun scalarResult(v: Any?, ev: Evaluation): Any? = when (v) {
        is FormulaRange -> {
            if (v.single) ev.ctx.cell(v.sheet, v.r0, v.c0)
            else if (v.c0 == v.c1 && ev.row in v.r0..v.r1) ev.ctx.cell(v.sheet, ev.row, v.c0)
            else if (v.r0 == v.r1 && ev.col in v.c0..v.c1) ev.ctx.cell(v.sheet, v.r0, ev.col)
            else ev.ctx.cell(v.sheet, v.r0, v.c0)
        }
        is FormulaArray -> if (v.data.isEmpty()) FormulaError.CALC else v[0, 0]
        else -> v
    }

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ExcelFormula>()
        private val FAILED = ExcelFormula(ErrN(FormulaError.NAME))

        fun compile(text: String): ExcelFormula? {
            cache[text]?.let { return if (it === FAILED) null else it }
            val f = try { ExcelFormula(Parser(text).parseAll()) } catch (e: Exception) { null }
            if (cache.size > 20000) cache.clear()
            cache[text] = f ?: FAILED
            return f
        }

        fun evaluate(text: String, ctx: FormulaContext, row: Int = 0, col: Int = 0, sheet: String? = null): Any? =
            compile(text)?.evaluate(ctx, row, col, sheet) ?: FormulaError.NAME

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
            while (n > 0) { sb.append('A' + (n - 1) % 26); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }

        fun toText(v: Any?): String = when (v) {
            null -> ""
            is Double -> numberText(v)
            is Boolean -> if (v) "TRUE" else "FALSE"
            is FormulaError -> v.code
            else -> v.toString()
        }

        fun numberText(d: Double): String {
            if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
            return ExcelNumberFormat.general(d).replace(ExcelNumberFormat.decimalSeparator, '.')
        }

        fun parseNumber(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            t.toDoubleOrNull()?.let { return it }
            var x = t.replace(" ", "").replace(" ", "")
            var scale = 1.0
            if (x.endsWith("%")) { x = x.dropLast(1); scale = 0.01 }
            x = x.removePrefix("$").removePrefix("€").removePrefix("£")
            if (x.startsWith("(") && x.endsWith(")")) x = "-" + x.substring(1, x.length - 1)
            x.replace(",", "").toDoubleOrNull()?.let { return it * scale }
            return null
        }

        fun truthy(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Double -> v != 0.0
            is String -> v.equals("TRUE", true)
            else -> false
        }
    }

    private class Parser(src: String) {
        private val s = src.trim().removePrefix("=").removePrefix("of:").removePrefix("=").trim()
        private var p = 0

        fun parseAll(): Node {
            val n = comparison()
            ws()
            if (p != s.length) throw IllegalArgumentException("trailing")
            return n
        }

        private fun ws() { while (p < s.length && (s[p] == ' ' || s[p] == '\n' || s[p] == '\r' || s[p] == '\t')) p++ }
        private fun peek(t: String): Boolean { ws(); return s.startsWith(t, p) }

        private fun comparison(): Node {
            var a = concat()
            while (true) {
                ws()
                val op = listOf("<=", ">=", "<>", "<", ">", "=").firstOrNull { s.startsWith(it, p) } ?: return a
                p += op.length
                a = BinN(op, a, concat())
            }
        }

        private fun concat(): Node {
            var a = additive()
            while (peek("&")) { p++; a = BinN("&", a, additive()) }
            return a
        }

        private fun additive(): Node {
            var a = multiplicative()
            while (true) {
                ws()
                if (p < s.length && (s[p] == '+' || s[p] == '-')) { val op = s[p].toString(); p++; a = BinN(op, a, multiplicative()) } else return a
            }
        }

        private fun multiplicative(): Node {
            var a = power()
            while (true) {
                ws()
                if (p < s.length && (s[p] == '*' || s[p] == '/')) { val op = s[p].toString(); p++; a = BinN(op, a, power()) } else return a
            }
        }

        private fun power(): Node {
            var a = unary()
            while (peek("^")) { p++; a = BinN("^", a, unary()) }
            return a
        }

        private fun unary(): Node {
            ws()
            if (p < s.length && s[p] == '-') { p++; return UnN('-', unary()) }
            if (p < s.length && s[p] == '+') { p++; return unary() }
            var x = postfix()
            while (peek("%")) { p++; x = PctN(x) }
            return x
        }

        private fun postfix(): Node {
            var a = primary()
            while (true) {
                ws()
                if (p < s.length && s[p] == ':' && a is RefN) {
                    p++
                    val b = primary()
                    a = if (b is RefN) RefN(a.sheet ?: b.sheet, minOf(a.r0, b.r0), minOf(a.c0, b.c0), maxOf(a.r1, b.r1), maxOf(a.c1, b.c1), a.rowAbs0, a.colAbs0, b.rowAbs1, b.colAbs1, a.wholeCol && b.wholeCol, a.wholeRow && b.wholeRow)
                    else FuncN("_RANGE", listOf(a, b))
                } else if (p < s.length && s[p] == ':' && a is FuncN) {
                    p++
                    a = FuncN("_RANGE", listOf(a, primary()))
                } else return a
            }
        }

        private fun readSheetPrefix(): String? {
            val save = p
            if (p < s.length && s[p] == '\'') {
                val sb = StringBuilder()
                p++
                while (p < s.length) {
                    if (s[p] == '\'') {
                        if (p + 1 < s.length && s[p + 1] == '\'') { sb.append('\''); p += 2; continue }
                        p++
                        break
                    }
                    sb.append(s[p]); p++
                }
                if (p < s.length && s[p] == '!') { p++; return sb.toString() }
                p = save
                return null
            }
            var q = p
            while (q < s.length && (s[q].isLetterOrDigit() || s[q] == '_' || s[q] == '.')) q++
            if (q > p && q < s.length && s[q] == '!') {
                val name = s.substring(p, q)
                p = q + 1
                return name
            }
            return null
        }

        private val cellRe = Regex("""^(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})(?![A-Za-z0-9_(])""")
        private val colRe = Regex("""^(\$?)([A-Za-z]{1,3}):(\$?)([A-Za-z]{1,3})(?![A-Za-z0-9_(])""")
        private val rowRe = Regex("""^(\$?)(\d{1,7}):(\$?)(\d{1,7})(?![A-Za-z0-9_(])""")

        private fun tryRef(sheet: String?): Node? {
            val rest = s.substring(p)
            colRe.find(rest)?.let { m ->
                val c0 = colIndex(m.groupValues[2])
                val c1 = colIndex(m.groupValues[4])
                if (c0 >= 0 && c1 >= 0) {
                    p += m.value.length
                    return RefN(sheet, 0, minOf(c0, c1), 1_048_575, maxOf(c0, c1), true, m.groupValues[1].isNotEmpty(), true, m.groupValues[3].isNotEmpty(), true, false)
                }
            }
            rowRe.find(rest)?.let { m ->
                val r0 = m.groupValues[2].toInt() - 1
                val r1 = m.groupValues[4].toInt() - 1
                p += m.value.length
                return RefN(sheet, minOf(r0, r1), 0, maxOf(r0, r1), 16383, m.groupValues[1].isNotEmpty(), true, m.groupValues[3].isNotEmpty(), true, false, true)
            }
            cellRe.find(rest)?.let { m ->
                val c = colIndex(m.groupValues[2])
                val r = m.groupValues[4].toInt() - 1
                if (c >= 0 && r >= 0) {
                    p += m.value.length
                    return RefN(sheet, r, c, r, c, m.groupValues[3].isNotEmpty(), m.groupValues[1].isNotEmpty(), m.groupValues[3].isNotEmpty(), m.groupValues[1].isNotEmpty(), false, false)
                }
            }
            return null
        }

        private fun primary(): Node {
            ws()
            if (p >= s.length) throw IllegalArgumentException("eof")
            val ch = s[p]
            when {
                ch == '(' -> {
                    p++
                    val n = comparison()
                    if (!peek(")")) throw IllegalArgumentException(")")
                    p++
                    return n
                }
                ch == '"' -> {
                    val sb = StringBuilder()
                    p++
                    while (p < s.length) {
                        if (s[p] == '"') {
                            if (p + 1 < s.length && s[p + 1] == '"') { sb.append('"'); p += 2; continue }
                            p++
                            return StrN(sb.toString())
                        }
                        sb.append(s[p]); p++
                    }
                    throw IllegalArgumentException("string")
                }
                ch == '{' -> {
                    p++
                    val rows = ArrayList<List<Node>>()
                    var cur = ArrayList<Node>()
                    while (true) {
                        cur.add(unary())
                        ws()
                        if (p >= s.length) throw IllegalArgumentException("array")
                        when (s[p]) {
                            ',' -> p++
                            ';' -> { p++; rows.add(cur); cur = ArrayList() }
                            '}' -> { p++; rows.add(cur); return ArrN(rows) }
                            else -> throw IllegalArgumentException("array")
                        }
                    }
                }
                ch == '#' -> {
                    val m = Regex("""^#(NULL!|DIV/0!|VALUE!|REF!|NAME\?|NUM!|N/A|CALC!|SPILL!|GETTING_DATA)""", RegexOption.IGNORE_CASE).find(s.substring(p))
                        ?: throw IllegalArgumentException("err")
                    p += m.value.length
                    return ErrN(FormulaError.parse(m.value) ?: FormulaError.VALUE)
                }
                ch.isDigit() || (ch == '.' && p + 1 < s.length && s[p + 1].isDigit()) -> {
                    rowRe.find(s.substring(p))?.let { return tryRef(null)!! }
                    val st = p
                    while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
                    if (p < s.length && (s[p] == 'E' || s[p] == 'e') && p + 1 < s.length && (s[p + 1].isDigit() || s[p + 1] == '+' || s[p + 1] == '-')) {
                        p++
                        if (s[p] == '+' || s[p] == '-') p++
                        while (p < s.length && s[p].isDigit()) p++
                    }
                    return NumN(s.substring(st, p).toDouble())
                }
            }
            val save = p
            val sheet = readSheetPrefix()
            tryRef(sheet)?.let { return it }
            if (sheet != null) {
                val m = Regex("""^[A-Za-z_\\][A-Za-z0-9_.]*""").find(s.substring(p)) ?: throw IllegalArgumentException("ref")
                p += m.value.length
                return NameN(sheet + "!" + m.value)
            }
            p = save
            val m = Regex("""^[A-Za-z_\\][A-Za-z0-9_.]*""").find(s.substring(p)) ?: throw IllegalArgumentException("tok")
            p += m.value.length
            var name = m.value
            ws()
            if (p < s.length && s[p] == '(') {
                p++
                val args = ArrayList<Node>()
                ws()
                if (p < s.length && s[p] == ')') { p++; return FuncN(normalize(name), args) }
                while (true) {
                    ws()
                    if (p < s.length && (s[p] == ',' || s[p] == ';')) { args.add(MissN()); p++; continue }
                    if (p < s.length && s[p] == ')') { args.add(MissN()); p++; break }
                    args.add(comparison())
                    ws()
                    if (p >= s.length) throw IllegalArgumentException("args")
                    if (s[p] == ',' || s[p] == ';') { p++; ws(); if (p < s.length && s[p] == ')') { args.add(MissN()); p++; break }; continue }
                    if (s[p] == ')') { p++; break }
                    throw IllegalArgumentException("args")
                }
                return FuncN(normalize(name), args)
            }
            if (name.equals("TRUE", true)) return BoolN(true)
            if (name.equals("FALSE", true)) return BoolN(false)
            return NameN(name)
        }

        private fun normalize(n: String): String {
            var x = n.uppercase(Locale.ROOT)
            for (pre in listOf("_XLFN._XLWS.", "_XLFN.", "_XLWS.", "COM.MICROSOFT.", "ORG.OPENOFFICE.", "OF:")) if (x.startsWith(pre)) x = x.removePrefix(pre)
            return x
        }
    }

    private fun eval(n: Node, ev: Evaluation): Any? {
        if (++ev.depth > 4000) throw StackOverflowError()
        try {
            return when (n) {
                is NumN -> n.v
                is StrN -> n.v
                is BoolN -> n.v
                is ErrN -> n.v
                is MissN -> null
                is RefN -> {
                    val sh = n.sheet ?: ev.sheet
                    if (n.wholeCol || n.wholeRow) {
                        val b = ev.ctx.bounds(sh)
                        val r1 = if (n.wholeCol && b != null) minOf(n.r1, b[0] - 1).coerceAtLeast(n.r0) else n.r1
                        val c1 = if (n.wholeRow && b != null) minOf(n.c1, b[1] - 1).coerceAtLeast(n.c0) else n.c1
                        FormulaRange(sh, n.r0, n.c0, r1, c1)
                    } else FormulaRange(sh, n.r0, n.c0, n.r1, n.c1)
                }
                is NameN -> {
                    val text = ev.ctx.name(n.name) ?: return FormulaError.NAME
                    val f = compile(text) ?: return FormulaError.NAME
                    f.eval(f.root, ev)
                }
                is ArrN -> {
                    val cols = n.rows.maxOf { it.size }
                    val data = arrayOfNulls<Any?>(n.rows.size * cols)
                    for ((r, row) in n.rows.withIndex()) for ((c, x) in row.withIndex()) data[r * cols + c] = scalar(eval(x, ev), ev)
                    FormulaArray(n.rows.size, cols, data)
                }
                is UnN -> lift1(eval(n.x, ev), ev) { v -> num(v).let { if (it is Double) -it else it } }
                is PctN -> lift1(eval(n.x, ev), ev) { v -> num(v).let { if (it is Double) it / 100.0 else it } }
                is BinN -> binary(n.op, eval(n.a, ev), eval(n.b, ev), ev)
                is FuncN -> call(n, ev)
            }
        } finally {
            ev.depth--
        }
    }

    private fun scalar(v: Any?, ev: Evaluation): Any? = when (v) {
        is FormulaRange -> scalarResult(v, ev)
        is FormulaArray -> if (v.data.isEmpty()) null else v[0, 0]
        else -> v
    }

    private fun toArray(v: Any?, ev: Evaluation): FormulaArray? = when (v) {
        is FormulaArray -> v
        is FormulaRange -> {
            val n = v.rows.toLong() * v.cols
            if (n > 2_000_000) throw IllegalStateException()
            val data = arrayOfNulls<Any?>(n.toInt())
            var i = 0
            for (r in v.r0..v.r1) for (c in v.c0..v.c1) data[i++] = ev.ctx.cell(v.sheet, r, c)
            FormulaArray(v.rows, v.cols, data)
        }
        else -> null
    }

    private fun isMulti(v: Any?): Boolean = (v is FormulaRange && !v.single) || (v is FormulaArray && v.data.size > 1)

    private inline fun lift1(v: Any?, ev: Evaluation, f: (Any?) -> Any?): Any? {
        if (isMulti(v)) {
            val a = toArray(v, ev)!!
            return FormulaArray(a.rows, a.cols, Array(a.data.size) { f(a.data[it]) })
        }
        return f(scalar(v, ev))
    }

    private fun at(arr: FormulaArray?, s: Any?, r: Int, c: Int): Any? {
        if (arr == null) return s
        val rr = if (arr.rows == 1) 0 else r
        val cc = if (arr.cols == 1) 0 else c
        return if (rr < arr.rows && cc < arr.cols) arr[rr, cc] else FormulaError.NA
    }

    private inline fun lift2(x: Any?, y: Any?, ev: Evaluation, f: (Any?, Any?) -> Any?): Any? {
        if (isMulti(x) || isMulti(y)) {
            val a = toArray(x, ev)
            val b = toArray(y, ev)
            val rows = maxOf(a?.rows ?: 1, b?.rows ?: 1)
            val cols = maxOf(a?.cols ?: 1, b?.cols ?: 1)
            val sx = if (a == null) scalar(x, ev) else null
            val sy = if (b == null) scalar(y, ev) else null
            return FormulaArray(rows, cols, Array(rows * cols) { i -> f(at(a, sx, i / cols, i % cols), at(b, sy, i / cols, i % cols)) })
        }
        return f(scalar(x, ev), scalar(y, ev))
    }

    private fun num(v: Any?): Any = when (v) {
        null -> 0.0
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is FormulaError -> v
        is String -> parseNumber(v) ?: dateText(v) ?: FormulaError.VALUE
        else -> FormulaError.VALUE
    }

    private fun dateText(s: String): Double? {
        val t = s.trim()
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(t)?.let { return serial(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})$""").find(t)?.let {
            val a = it.groupValues[1].toInt()
            val b = it.groupValues[2].toInt()
            val usFirst = ExcelNumberFormat.shortDateCode().lowercase(Locale.ROOT).startsWith("m")
            return if (usFirst) serial(it.groupValues[3].toInt(), a, b) else serial(it.groupValues[3].toInt(), b, a)
        }
        Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""").find(t)?.let {
            return (it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + (it.groupValues[3].toIntOrNull() ?: 0)) / 86400.0
        }
        return null
    }

    private fun text(v: Any?): Any = if (v is FormulaError) v else toText(v)

    private fun bool(v: Any?): Any = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is FormulaError -> v
        is String -> if (v.equals("TRUE", true)) true else if (v.equals("FALSE", true)) false else FormulaError.VALUE
        else -> FormulaError.VALUE
    }

    private fun compare(a: Any?, b: Any?): Int {
        fun rank(v: Any?): Int = when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; else -> 0 }
        val x = a ?: when (b) { is String -> ""; is Boolean -> false; else -> 0.0 }
        val y = b ?: when (a) { is String -> ""; is Boolean -> false; else -> 0.0 }
        val rx = rank(x)
        val ry = rank(y)
        if (rx != ry) return rx.compareTo(ry)
        return when (x) {
            is Double -> x.compareTo(y as Double)
            is String -> x.lowercase(Locale.ROOT).compareTo((y as String).lowercase(Locale.ROOT))
            is Boolean -> x.compareTo(y as Boolean)
            else -> 0
        }
    }

    private fun binary(op: String, a: Any?, b: Any?, ev: Evaluation): Any? = lift2(a, b, ev) { x, y ->
        if (x is FormulaError) return@lift2 x
        if (y is FormulaError) return@lift2 y
        when (op) {
            "&" -> toText(x) + toText(y)
            "=", "<>", "<", ">", "<=", ">=" -> {
                val c = compare(x, y)
                when (op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
            }
            else -> {
                val nx = num(x)
                val ny = num(y)
                if (nx is FormulaError) return@lift2 nx
                if (ny is FormulaError) return@lift2 ny
                val dx = nx as Double
                val dy = ny as Double
                when (op) {
                    "+" -> dx + dy
                    "-" -> dx - dy
                    "*" -> dx * dy
                    "/" -> if (dy == 0.0) FormulaError.DIV0 else dx / dy
                    else -> {
                        if (dx == 0.0 && dy == 0.0) FormulaError.NUM
                        else { val r = dx.pow(dy); if (r.isNaN() || r.isInfinite()) FormulaError.NUM else r }
                    }
                }
            }
        }
    }

    private fun flatten(args: List<Any?>, ev: Evaluation, out: ArrayList<Any?>, directMarker: ArrayList<Boolean>? = null) {
        for (a in args) {
            when (a) {
                is FormulaRange, is FormulaArray -> {
                    val arr = toArray(a, ev)!!
                    for (v in arr.data) { out.add(v); directMarker?.add(false) }
                }
                else -> { out.add(a); directMarker?.add(true) }
            }
        }
    }

    private fun numbers(args: List<Any?>, ev: Evaluation, countText: Boolean = false): Any {
        val vals = ArrayList<Any?>()
        val direct = ArrayList<Boolean>()
        flatten(args, ev, vals, direct)
        val out = ArrayList<Double>(vals.size)
        for (i in vals.indices) {
            val v = vals[i]
            when (v) {
                is FormulaError -> return v
                is Double -> out.add(v)
                is Boolean -> if (direct[i] || countText) out.add(if (v) 1.0 else 0.0)
                is String -> if (direct[i]) { val n = num(v); if (n is FormulaError) return n; out.add(n as Double) } else if (countText) out.add(0.0)
                null -> {}
            }
        }
        return out
    }

    private fun criteria(crit: Any?): (Any?) -> Boolean {
        if (crit is Double) return { v -> v is Double && v == crit || (v is String && parseNumber(v) == crit) }
        if (crit is Boolean) return { v -> v == crit }
        val c = toText(crit)
        val op = listOf("<=", ">=", "<>", "=", "<", ">").firstOrNull { c.startsWith(it) } ?: ""
        val rhs = c.substring(op.length)
        val rn = parseNumber(rhs)
        val rb = if (rhs.equals("TRUE", true)) true else if (rhs.equals("FALSE", true)) false else null
        if (op == "" || op == "=" || op == "<>") {
            val positive: (Any?) -> Boolean = when {
                rhs.isEmpty() -> if (op == "") { v -> v == null || v == "" } else { v -> v == null || v == "" }
                rn != null -> { v -> (v is Double && v == rn) || (v is String && parseNumber(v) == rn) }
                rb != null -> { v -> v == rb }
                rhs.startsWith("#") && FormulaError.parse(rhs) != null -> { v -> v == FormulaError.parse(rhs) }
                rhs.contains('*') || rhs.contains('?') -> {
                    val re = wildcard(rhs)
                    ({ v -> v is String && re.matches(v) })
                }
                else -> { v -> v is String && v.equals(rhs, true) }
            }
            return if (op == "<>") ({ v -> !positive(v) }) else positive
        }
        if (rn != null) {
            return { v ->
                val d = when (v) { is Double -> v; else -> null }
                d != null && when (op) { "<" -> d < rn; ">" -> d > rn; "<=" -> d <= rn; else -> d >= rn }
            }
        }
        return { v ->
            v is String && run {
                val cmp = v.lowercase(Locale.ROOT).compareTo(rhs.lowercase(Locale.ROOT))
                when (op) { "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; else -> cmp >= 0 }
            }
        }
    }

    private fun wildcard(p: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '~' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i += 2; continue }
                ch == '*' -> sb.append(".*")
                ch == '?' -> sb.append(".")
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    private fun serial(y: Int, m: Int, d: Int): Double {
        var yy = y
        if (yy in 0..1899) yy += 1900
        val base = LocalDate.of(yy, 1, 1).plusMonths((m - 1).toLong())
        val fake = if (!base.isBefore(LocalDate.of(1900, 3, 1))) 1 else 0
        return (ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 31), base) + (d - 1) + fake).toDouble()
    }

    private fun dateOf(serialValue: Double, ev: Evaluation): LocalDate {
        val s = floor(serialValue).toLong()
        if (ev.ctx.date1904) return LocalDate.of(1904, 1, 1).plusDays(s)
        return if (s < 61) LocalDate.of(1899, 12, 31).plusDays(s) else LocalDate.of(1899, 12, 30).plusDays(s)
    }

    private fun toSerial(d: LocalDate, ev: Evaluation): Double {
        if (ev.ctx.date1904) return ChronoUnit.DAYS.between(LocalDate.of(1904, 1, 1), d).toDouble()
        val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d).toDouble()
        return if (days < 61) days - 1 else days
    }

    private fun refOf(v: Any?): FormulaRange? = v as? FormulaRange

    private fun round(x: Double, digits: Int, mode: RoundingMode): Double =
        BigDecimal(x).round(java.math.MathContext(15, RoundingMode.HALF_EVEN)).setScale(digits, mode).toDouble()

    private fun stat(args: List<Any?>, ev: Evaluation, f: (List<Double>) -> Any): Any {
        val n = numbers(args, ev)
        if (n is FormulaError) return n
        @Suppress("UNCHECKED_CAST")
        return f(n as List<Double>)
    }

    private fun percentile(xs: List<Double>, k: Double, exclusive: Boolean): Any {
        if (xs.isEmpty()) return FormulaError.NUM
        val s = xs.sorted()
        val n = s.size
        val pos = if (exclusive) {
            if (k <= 0 || k >= 1) return FormulaError.NUM
            k * (n + 1) - 1
        } else {
            if (k < 0 || k > 1) return FormulaError.NUM
            k * (n - 1)
        }
        if (pos < 0 || pos > n - 1) return FormulaError.NUM
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    private fun variance(xs: List<Double>, sample: Boolean): Any {
        val n = xs.size
        if (n < (if (sample) 2 else 1)) return FormulaError.DIV0
        val m = xs.average()
        return xs.sumOf { (it - m) * (it - m) } / (if (sample) n - 1 else n)
    }

    private fun lookupIndex(needle: Any?, list: List<Any?>, mode: Int, exactWildcard: Boolean = true): Int {
        if (mode == 0) {
            val wc = needle is String && exactWildcard && (needle.contains('*') || needle.contains('?'))
            val re = if (wc) wildcard(needle as String) else null
            for ((i, v) in list.withIndex()) {
                if (re != null) { if (v is String && re.matches(v)) return i }
                else if (v != null && sameType(v, needle) && compare(v, needle) == 0) return i
            }
            return -1
        }
        var best = -1
        if (mode == 1) {
            var lo = 0
            var hi = list.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val v = list[mid]
                if (v == null || !sameType(v, needle)) {
                    var k = mid - 1
                    while (k >= lo && (list[k] == null || !sameType(list[k], needle))) k--
                    if (k < lo) { lo = mid + 1; continue }
                    if (compare(list[k], needle) <= 0) { best = k; lo = mid + 1 } else hi = k - 1
                    continue
                }
                if (compare(v, needle) <= 0) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            return best
        }
        for ((i, v) in list.withIndex()) {
            if (v == null || !sameType(v, needle)) continue
            if (compare(v, needle) >= 0) best = i else break
        }
        return best
    }

    private fun sameType(a: Any?, b: Any?): Boolean = (a is Double && b is Double) || (a is String && b is String) || (a is Boolean && b is Boolean)

    private fun vector(v: Any?, ev: Evaluation): List<Any?> = toArray(v, ev)?.data?.toList() ?: listOf(scalar(v, ev))

    private fun call(n: FuncN, ev: Evaluation): Any? {
        val name = n.name
        when (name) {
            "IF" -> {
                val c = eval(n.args.getOrNull(0) ?: return FormulaError.VALUE, ev)
                if (isMulti(c)) {
                    val t = n.args.getOrNull(1)?.let { eval(it, ev) } ?: true
                    val f = if (n.args.size > 2) eval(n.args[2], ev) else false
                    val cond = toArray(c, ev)!!
                    val ta = toArray(t, ev)
                    val fa = toArray(f, ev)
                    return FormulaArray(cond.rows, cond.cols, Array(cond.data.size) { i ->
                        val b = bool(cond.data[i])
                        if (b is FormulaError) b else if (b == true) (ta?.data?.getOrNull(i) ?: scalar(t, ev)) else (fa?.data?.getOrNull(i) ?: scalar(f, ev))
                    })
                }
                val b = bool(scalar(c, ev))
                if (b is FormulaError) return b
                return if (b == true) (if (n.args.size > 1) eval(n.args[1], ev).let { if (n.args[1] is MissN) 0.0 else it } else true)
                else (if (n.args.size > 2) eval(n.args[2], ev).let { if (n.args[2] is MissN) 0.0 else it } else false)
            }
            "IFERROR", "IFNA" -> {
                val v = scalar(eval(n.args.getOrNull(0) ?: return FormulaError.VALUE, ev), ev)
                val bad = if (name == "IFERROR") v is FormulaError else v == FormulaError.NA
                return if (bad) eval(n.args.getOrNull(1) ?: return 0.0, ev) else v
            }
            "IFS" -> {
                var i = 0
                while (i + 1 < n.args.size) {
                    val b = bool(scalar(eval(n.args[i], ev), ev))
                    if (b is FormulaError) return b
                    if (b == true) return eval(n.args[i + 1], ev)
                    i += 2
                }
                return FormulaError.NA
            }
            "SWITCH" -> {
                val v = scalar(eval(n.args.getOrNull(0) ?: return FormulaError.VALUE, ev), ev)
                var i = 1
                while (i + 1 < n.args.size) {
                    if (compare(v, scalar(eval(n.args[i], ev), ev)) == 0) return eval(n.args[i + 1], ev)
                    i += 2
                }
                return if (i < n.args.size) eval(n.args[i], ev) else FormulaError.NA
            }
            "CHOOSE" -> {
                val idx = num(scalar(eval(n.args.getOrNull(0) ?: return FormulaError.VALUE, ev), ev))
                if (idx is FormulaError) return idx
                val k = floor(idx as Double).toInt()
                if (k < 1 || k >= n.args.size) return FormulaError.VALUE
                return eval(n.args[k], ev)
            }
            "ROW", "COLUMN" -> {
                if (n.args.isEmpty() || n.args[0] is MissN) return (if (name == "ROW") ev.row else ev.col) + 1.0
                val r = refOf(eval(n.args[0], ev)) ?: return FormulaError.VALUE
                return (if (name == "ROW") r.r0 else r.c0) + 1.0
            }
            "ISREF" -> return n.args.getOrNull(0)?.let { eval(it, ev) is FormulaRange } ?: false
        }
        val args = n.args.map { eval(it, ev) }
        return dispatch(name, args, ev) ?: FormulaError.NAME
    }

    private fun arg(a: List<Any?>, i: Int, ev: Evaluation): Any? = scalar(a.getOrNull(i), ev)

    private fun numArg(a: List<Any?>, i: Int, ev: Evaluation, def: Double? = null): Any {
        if (i >= a.size || a[i] == null && def != null) return def ?: FormulaError.VALUE
        return num(arg(a, i, ev))
    }

    private inline fun math1(a: List<Any?>, ev: Evaluation, f: (Double) -> Any): Any? = lift1(a.getOrNull(0), ev) { v ->
        val x = num(v)
        if (x is FormulaError) x else f(x as Double).let { if (it is Double && (it.isNaN() || it.isInfinite())) FormulaError.NUM else it }
    }

    private inline fun math2(a: List<Any?>, ev: Evaluation, def: Double?, f: (Double, Double) -> Any): Any? {
        val second = if (a.size > 1) a[1] else def
        return lift2(a.getOrNull(0), second, ev) { x, y ->
            val nx = num(x)
            val ny = if (y == null && def != null) def else num(y)
            if (nx is FormulaError) nx else if (ny is FormulaError) ny else f(nx as Double, ny as Double).let { if (it is Double && (it.isNaN() || it.isInfinite())) FormulaError.NUM else it }
        }
    }

    private inline fun text1(a: List<Any?>, ev: Evaluation, f: (String) -> Any): Any? = lift1(a.getOrNull(0), ev) { v -> val t = text(v); if (t is FormulaError) t else f(t as String) }

    private fun ifsAggregate(a: List<Any?>, ev: Evaluation, valueIndex: Int?): Pair<List<Any?>, List<Boolean>>? {
        val start = if (valueIndex == null) 0 else 1
        val target = valueIndex?.let { toArray(a[it], ev) }
        val pairs = ArrayList<Pair<FormulaArray, (Any?) -> Boolean>>()
        var i = start
        while (i + 1 < a.size) {
            val rng = toArray(a[i], ev) ?: return null
            pairs.add(rng to criteria(scalar(a[i + 1], ev)))
            i += 2
        }
        if (pairs.isEmpty()) return null
        val size = target?.data?.size ?: pairs[0].first.data.size
        if (pairs.any { it.first.data.size != size }) return null
        val values = target?.data?.toList() ?: List(size) { null }
        val mask = List(size) { idx -> pairs.all { (r, c) -> c(r.data[idx]) } }
        return values to mask
    }

    private fun dispatch(name: String, a: List<Any?>, ev: Evaluation): Any? = when (name) {
        "SUM" -> stat(a, ev) { it.sum() }
        "AVERAGE" -> stat(a, ev) { if (it.isEmpty()) FormulaError.DIV0 else it.average() }
        "AVERAGEA" -> { val n = numbers(a, ev, true); if (n is FormulaError) n else (n as List<*>).let { l -> if (l.isEmpty()) FormulaError.DIV0 else l.sumOf { it as Double } / l.size } }
        "MIN" -> stat(a, ev) { it.minOrNull() ?: 0.0 }
        "MAX" -> stat(a, ev) { it.maxOrNull() ?: 0.0 }
        "MINA" -> { val n = numbers(a, ev, true); if (n is FormulaError) n else ((n as List<*>).minOfOrNull { it as Double } ?: 0.0) }
        "MAXA" -> { val n = numbers(a, ev, true); if (n is FormulaError) n else ((n as List<*>).maxOfOrNull { it as Double } ?: 0.0) }
        "PRODUCT" -> stat(a, ev) { if (it.isEmpty()) 0.0 else it.fold(1.0) { x, y -> x * y } }
        "SUMSQ" -> stat(a, ev) { it.sumOf { x -> x * x } }
        "COUNT" -> { val vals = ArrayList<Any?>(); val d = ArrayList<Boolean>(); flatten(a, ev, vals, d); vals.indices.count { i -> vals[i] is Double || (d[i] && (vals[i] is Boolean || (vals[i] is String && parseNumber(vals[i] as String) != null))) }.toDouble() }
        "COUNTA" -> { val vals = ArrayList<Any?>(); flatten(a, ev, vals); vals.count { it != null }.toDouble() }
        "COUNTBLANK" -> { val vals = ArrayList<Any?>(); flatten(a, ev, vals); vals.count { it == null || it == "" }.toDouble() }
        "MEDIAN" -> stat(a, ev) { xs -> if (xs.isEmpty()) FormulaError.NUM else xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 } }
        "MODE", "MODE.SNGL" -> stat(a, ev) { xs ->
            val counts = LinkedHashMap<Double, Int>()
            for (x in xs) counts[x] = (counts[x] ?: 0) + 1
            val best = counts.maxByOrNull { it.value }
            if (best == null || best.value < 2) FormulaError.NA else best.key
        }
        "LARGE", "SMALL" -> {
            val xs = numbers(listOf(a.getOrNull(0)), ev)
            val k = numArg(a, 1, ev)
            if (xs is FormulaError) xs else if (k is FormulaError) k else {
                val s = (xs as List<*>).map { it as Double }.let { if (name == "LARGE") it.sortedDescending() else it.sorted() }
                val i = ceil(k as Double).toInt()
                if (i < 1 || i > s.size) FormulaError.NUM else s[i - 1]
            }
        }
        "RANK", "RANK.EQ", "RANK.AVG" -> {
            val x = numArg(a, 0, ev)
            val xs = numbers(listOf(a.getOrNull(1)), ev)
            val asc = a.size > 2 && truthy(num(arg(a, 2, ev)).let { if (it is Double) it != 0.0 else false })
            if (x is FormulaError) x else if (xs is FormulaError) xs else {
                val l = (xs as List<*>).map { it as Double }
                val v = x as Double
                if (v !in l) FormulaError.NA else {
                    val better = l.count { if (asc) it < v else it > v }
                    val ties = l.count { it == v }
                    if (name == "RANK.AVG") better + (ties + 1) / 2.0 else better + 1.0
                }
            }
        }
        "PERCENTILE", "PERCENTILE.INC", "PERCENTILE.EXC" -> {
            val xs = numbers(listOf(a.getOrNull(0)), ev); val k = numArg(a, 1, ev)
            if (xs is FormulaError) xs else if (k is FormulaError) k else percentile((xs as List<*>).map { it as Double }, k as Double, name.endsWith("EXC"))
        }
        "QUARTILE", "QUARTILE.INC", "QUARTILE.EXC" -> {
            val xs = numbers(listOf(a.getOrNull(0)), ev); val k = numArg(a, 1, ev)
            if (xs is FormulaError) xs else if (k is FormulaError) k else percentile((xs as List<*>).map { it as Double }, floor(k as Double) / 4.0, name.endsWith("EXC"))
        }
        "PERCENTRANK", "PERCENTRANK.INC" -> {
            val xs = numbers(listOf(a.getOrNull(0)), ev); val x = numArg(a, 1, ev)
            if (xs is FormulaError) xs else if (x is FormulaError) x else {
                val s = (xs as List<*>).map { it as Double }.sorted(); val v = x as Double
                if (s.isEmpty() || v < s.first() || v > s.last()) FormulaError.NA else {
                    val i = s.indexOfLast { it <= v }
                    val frac = if (i < s.size - 1 && s[i] != v) (v - s[i]) / (s[i + 1] - s[i]) else 0.0
                    val r = (i + frac) / (s.size - 1).coerceAtLeast(1)
                    floor(r * 1000) / 1000
                }
            }
        }
        "STDEV", "STDEV.S", "STDEVA" -> stat(a, ev) { variance(it, true).let { v -> if (v is Double) sqrt(v) else v } }
        "STDEVP", "STDEV.P", "STDEVPA" -> stat(a, ev) { variance(it, false).let { v -> if (v is Double) sqrt(v) else v } }
        "VAR", "VAR.S", "VARA" -> stat(a, ev) { variance(it, true) }
        "VARP", "VAR.P", "VARPA" -> stat(a, ev) { variance(it, false) }
        "AVEDEV" -> stat(a, ev) { xs -> if (xs.isEmpty()) FormulaError.NUM else xs.average().let { m -> xs.sumOf { abs(it - m) } / xs.size } }
        "DEVSQ" -> stat(a, ev) { xs -> xs.average().let { m -> if (xs.isEmpty()) 0.0 else xs.sumOf { (it - m) * (it - m) } } }
        "GEOMEAN" -> stat(a, ev) { xs -> if (xs.isEmpty() || xs.any { it <= 0 }) FormulaError.NUM else exp(xs.sumOf { ln(it) } / xs.size) }
        "HARMEAN" -> stat(a, ev) { xs -> if (xs.isEmpty() || xs.any { it <= 0 }) FormulaError.NUM else xs.size / xs.sumOf { 1 / it } }
        "CORREL", "PEARSON", "SLOPE", "INTERCEPT", "RSQ", "COVAR", "COVARIANCE.P", "COVARIANCE.S", "FORECAST", "FORECAST.LINEAR" -> {
            val off = if (name.startsWith("FORECAST")) 1 else 0
            val ya = toArray(a.getOrNull(if (name == "CORREL" || name == "PEARSON" || name.startsWith("COV")) 0 else off), ev)
            val xa = toArray(a.getOrNull(if (name == "CORREL" || name == "PEARSON" || name.startsWith("COV")) 1 else off + 1), ev)
            if (ya == null || xa == null || ya.data.size != xa.data.size) FormulaError.NA else {
                val ys = ArrayList<Double>(); val xs = ArrayList<Double>()
                for (i in ya.data.indices) { val y = ya.data[i]; val x = xa.data[i]; if (y is Double && x is Double) { ys.add(y); xs.add(x) } }
                if (xs.size < 2) FormulaError.DIV0 else {
                    val mx = xs.average(); val my = ys.average()
                    val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
                    val sxx = xs.sumOf { (it - mx) * (it - mx) }
                    val syy = ys.sumOf { (it - my) * (it - my) }
                    when (name) {
                        "COVAR", "COVARIANCE.P" -> sxy / xs.size
                        "COVARIANCE.S" -> sxy / (xs.size - 1)
                        "SLOPE" -> if (sxx == 0.0) FormulaError.DIV0 else sxy / sxx
                        "INTERCEPT" -> if (sxx == 0.0) FormulaError.DIV0 else my - sxy / sxx * mx
                        "RSQ" -> if (sxx == 0.0 || syy == 0.0) FormulaError.DIV0 else (sxy * sxy) / (sxx * syy)
                        "FORECAST", "FORECAST.LINEAR" -> { val x = numArg(a, 0, ev); if (x is FormulaError) x else if (sxx == 0.0) FormulaError.DIV0 else my + sxy / sxx * ((x as Double) - mx) }
                        else -> if (sxx == 0.0 || syy == 0.0) FormulaError.DIV0 else sxy / sqrt(sxx * syy)
                    }
                }
            }
        }
        "SUMPRODUCT" -> {
            val arrs = a.map { toArray(it, ev) ?: FormulaArray(1, 1, arrayOf(scalar(it, ev))) }
            if (arrs.isEmpty() || arrs.any { it.data.size != arrs[0].data.size }) FormulaError.VALUE else {
                var sum = 0.0
                var err: Any? = null
                for (i in arrs[0].data.indices) {
                    var prod = 1.0
                    for (arr in arrs) {
                        val v = arr.data[i]
                        if (v is FormulaError) { err = v; break }
                        prod *= when (v) { is Double -> v; is Boolean -> if (arrs.size == 1) (if (v) 1.0 else 0.0) else 0.0; else -> 0.0 }
                    }
                    if (err != null) break
                    sum += prod
                }
                err ?: sum
            }
        }
        "SUMIF", "AVERAGEIF", "COUNTIF" -> {
            val rng = toArray(a.getOrNull(0), ev)
            if (rng == null) FormulaError.VALUE else {
                val c = criteria(scalar(a.getOrNull(1), ev))
                val vals = if (name != "COUNTIF" && a.size > 2) toArray(a[2], ev)?.let { t -> List(rng.data.size) { i -> t.data.getOrNull(i) } } ?: rng.data.toList() else rng.data.toList()
                val hits = rng.data.indices.filter { c(rng.data[it]) }
                when (name) {
                    "COUNTIF" -> hits.size.toDouble()
                    "SUMIF" -> hits.sumOf { (vals[it] as? Double) ?: 0.0 }
                    else -> hits.mapNotNull { vals[it] as? Double }.let { if (it.isEmpty()) FormulaError.DIV0 else it.average() }
                }
            }
        }
        "SUMIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
            val r = ifsAggregate(a, ev, 0)
            if (r == null) FormulaError.VALUE else {
                val xs = r.first.indices.filter { r.second[it] }.mapNotNull { r.first[it] as? Double }
                when (name) {
                    "SUMIFS" -> xs.sum()
                    "AVERAGEIFS" -> if (xs.isEmpty()) FormulaError.DIV0 else xs.average()
                    "MAXIFS" -> xs.maxOrNull() ?: 0.0
                    else -> xs.minOrNull() ?: 0.0
                }
            }
        }
        "COUNTIFS" -> ifsAggregate(a, ev, null)?.second?.count { it }?.toDouble() ?: FormulaError.VALUE
        "SUBTOTAL", "AGGREGATE" -> {
            val code = (num(arg(a, 0, ev)) as? Double)?.toInt() ?: 0
            val fn = when (code % 100) { 1 -> "AVERAGE"; 2 -> "COUNT"; 3 -> "COUNTA"; 4 -> "MAX"; 5 -> "MIN"; 6 -> "PRODUCT"; 7 -> "STDEV"; 8 -> "STDEVP"; 9 -> "SUM"; 10 -> "VAR"; 11 -> "VARP"; 12 -> "MEDIAN"; 13 -> "MODE"; 14 -> "LARGE"; 15 -> "SMALL"; else -> null }
            if (fn == null) FormulaError.VALUE else dispatch(fn, a.drop(if (name == "AGGREGATE") 2 else 1), ev)
        }
        "ABS" -> math1(a, ev) { abs(it) }
        "SIGN" -> math1(a, ev) { Math.signum(it) }
        "SQRT" -> math1(a, ev) { if (it < 0) FormulaError.NUM else sqrt(it) }
        "SQRTPI" -> math1(a, ev) { if (it < 0) FormulaError.NUM else sqrt(it * Math.PI) }
        "EXP" -> math1(a, ev) { exp(it) }
        "LN" -> math1(a, ev) { if (it <= 0) FormulaError.NUM else ln(it) }
        "LOG10" -> math1(a, ev) { if (it <= 0) FormulaError.NUM else log10(it) }
        "LOG" -> math2(a, ev, 10.0) { x, b -> if (x <= 0 || b <= 0 || b == 1.0) FormulaError.NUM else ln(x) / ln(b) }
        "SIN" -> math1(a, ev) { Math.sin(it) }
        "COS" -> math1(a, ev) { Math.cos(it) }
        "TAN" -> math1(a, ev) { Math.tan(it) }
        "ASIN" -> math1(a, ev) { if (abs(it) > 1) FormulaError.NUM else Math.asin(it) }
        "ACOS" -> math1(a, ev) { if (abs(it) > 1) FormulaError.NUM else Math.acos(it) }
        "ATAN" -> math1(a, ev) { Math.atan(it) }
        "ATAN2" -> math2(a, ev, null) { x, y -> if (x == 0.0 && y == 0.0) FormulaError.DIV0 else Math.atan2(y, x) }
        "SINH" -> math1(a, ev) { Math.sinh(it) }
        "COSH" -> math1(a, ev) { Math.cosh(it) }
        "TANH" -> math1(a, ev) { Math.tanh(it) }
        "ASINH" -> math1(a, ev) { ln(it + sqrt(it * it + 1)) }
        "ACOSH" -> math1(a, ev) { if (it < 1) FormulaError.NUM else ln(it + sqrt(it * it - 1)) }
        "ATANH" -> math1(a, ev) { if (abs(it) >= 1) FormulaError.NUM else 0.5 * ln((1 + it) / (1 - it)) }
        "DEGREES" -> math1(a, ev) { Math.toDegrees(it) }
        "RADIANS" -> math1(a, ev) { Math.toRadians(it) }
        "PI" -> Math.PI
        "INT" -> math1(a, ev) { floor(it) }
        "TRUNC" -> math2(a, ev, 0.0) { x, d -> round(x, d.toInt(), RoundingMode.DOWN) }
        "ROUND" -> math2(a, ev, 0.0) { x, d -> round(x, d.toInt(), RoundingMode.HALF_UP) }
        "ROUNDUP" -> math2(a, ev, 0.0) { x, d -> round(x, d.toInt(), RoundingMode.UP) }
        "ROUNDDOWN" -> math2(a, ev, 0.0) { x, d -> round(x, d.toInt(), RoundingMode.DOWN) }
        "MROUND" -> math2(a, ev, null) { x, m -> if (m == 0.0) 0.0 else if (x * m < 0) FormulaError.NUM else round(x / m, 0, RoundingMode.HALF_UP) * m }
        "CEILING", "CEILING.PRECISE", "ISO.CEILING", "CEILING.MATH" -> math2(a, ev, 1.0) { x, s -> if (s == 0.0) 0.0 else if (name == "CEILING" && x > 0 && s < 0) FormulaError.NUM else ceil(x / abs(s).let { if (name == "CEILING") s else it }) * abs(s).let { if (name == "CEILING") s else it } }
        "FLOOR", "FLOOR.PRECISE", "FLOOR.MATH" -> math2(a, ev, 1.0) { x, s -> if (s == 0.0) (if (name == "FLOOR") FormulaError.DIV0 else 0.0) else if (name == "FLOOR" && x > 0 && s < 0) FormulaError.NUM else floor(x / abs(s).let { if (name == "FLOOR") s else it }) * abs(s).let { if (name == "FLOOR") s else it } }
        "EVEN" -> math1(a, ev) { val c = ceil(abs(it) / 2) * 2; if (it < 0) -c else c }
        "ODD" -> math1(a, ev) { var c = ceil(abs(it)); if (c % 2 == 0.0) c += 1; if (it < 0) -c else c }
        "MOD" -> math2(a, ev, null) { x, d -> if (d == 0.0) FormulaError.DIV0 else x - d * floor(x / d) }
        "QUOTIENT" -> math2(a, ev, null) { x, d -> if (d == 0.0) FormulaError.DIV0 else (x / d).let { if (it < 0) ceil(it) else floor(it) } }
        "POWER" -> math2(a, ev, null) { x, y -> if (x == 0.0 && y <= 0) FormulaError.DIV0 else x.pow(y) }
        "FACT" -> math1(a, ev) { if (it < 0) FormulaError.NUM else { var r = 1.0; for (i in 2..floor(it).toInt()) r *= i; r } }
        "FACTDOUBLE" -> math1(a, ev) { if (it < -1) FormulaError.NUM else { var r = 1.0; var i = floor(it).toInt(); while (i > 1) { r *= i; i -= 2 }; r } }
        "COMBIN" -> math2(a, ev, null) { nn, k -> val n0 = floor(nn); val k0 = floor(k); if (n0 < 0 || k0 < 0 || k0 > n0) FormulaError.NUM else { var r = 1.0; for (i in 1..k0.toInt()) r = r * (n0 - k0 + i) / i; Math.round(r).toDouble() } }
        "PERMUT" -> math2(a, ev, null) { nn, k -> val n0 = floor(nn); val k0 = floor(k); if (n0 < 0 || k0 < 0 || k0 > n0) FormulaError.NUM else { var r = 1.0; for (i in 0 until k0.toInt()) r *= (n0 - i); r } }
        "GCD", "LCM" -> stat(a, ev) { xs ->
            if (xs.any { it < 0 }) FormulaError.NUM else {
                fun g(x: Long, y: Long): Long = if (y == 0L) x else g(y, x % y)
                val ls = xs.map { floor(it).toLong() }
                if (name == "GCD") ls.fold(0L) { acc, v -> g(acc, v) }.toDouble()
                else ls.fold(1L) { acc, v -> if (v == 0L || acc == 0L) 0L else acc / g(acc, v) * v }.toDouble()
            }
        }
        "RAND" -> Math.random()
        "RANDBETWEEN" -> math2(a, ev, null) { lo, hi -> floor(Math.random() * (floor(hi) - ceil(lo) + 1)) + ceil(lo) }
        "AND", "OR", "XOR" -> {
            val vals = ArrayList<Any?>(); val d = ArrayList<Boolean>(); flatten(a, ev, vals, d)
            val bs = ArrayList<Boolean>()
            var err: FormulaError? = null
            for (i in vals.indices) {
                when (val v = vals[i]) {
                    is FormulaError -> { err = v; break }
                    is Boolean -> bs.add(v)
                    is Double -> bs.add(v != 0.0)
                    is String -> if (d[i]) { val b = bool(v); if (b is FormulaError) { err = b; break } else bs.add(b as Boolean) }
                    else -> {}
                }
            }
            err ?: if (bs.isEmpty()) FormulaError.VALUE else when (name) { "AND" -> bs.all { it }; "OR" -> bs.any { it }; else -> bs.count { it } % 2 == 1 }
        }
        "NOT" -> lift1(a.getOrNull(0), ev) { v -> val b = bool(v); if (b is FormulaError) b else !(b as Boolean) }
        "TRUE" -> true
        "FALSE" -> false
        "NA" -> FormulaError.NA
        "ISBLANK" -> lift1(a.getOrNull(0), ev) { it == null }
        "ISERROR" -> lift1(a.getOrNull(0), ev) { it is FormulaError }
        "ISERR" -> lift1(a.getOrNull(0), ev) { it is FormulaError && it != FormulaError.NA }
        "ISNA" -> lift1(a.getOrNull(0), ev) { it == FormulaError.NA }
        "ISNUMBER" -> lift1(a.getOrNull(0), ev) { it is Double }
        "ISTEXT" -> lift1(a.getOrNull(0), ev) { it is String }
        "ISNONTEXT" -> lift1(a.getOrNull(0), ev) { it !is String }
        "ISLOGICAL" -> lift1(a.getOrNull(0), ev) { it is Boolean }
        "ISEVEN", "ISODD" -> math1(a, ev) { (floor(abs(it)) % 2 == 0.0) == (name == "ISEVEN") }
        "ISFORMULA" -> false
        "N" -> when (val v = arg(a, 0, ev)) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is FormulaError -> v; else -> 0.0 }
        "T" -> when (val v = arg(a, 0, ev)) { is String -> v; is FormulaError -> v; else -> "" }
        "TYPE" -> when (val v = a.getOrNull(0)) { is FormulaArray -> 64.0; is FormulaRange -> if (v.single) when (scalar(v, ev)) { is String -> 2.0; is Boolean -> 4.0; is FormulaError -> 16.0; else -> 1.0 } else 64.0; is String -> 2.0; is Boolean -> 4.0; is FormulaError -> 16.0; else -> 1.0 }
        "ERROR.TYPE" -> when (arg(a, 0, ev)) { FormulaError.NULL -> 1.0; FormulaError.DIV0 -> 2.0; FormulaError.VALUE -> 3.0; FormulaError.REF -> 4.0; FormulaError.NAME -> 5.0; FormulaError.NUM -> 6.0; FormulaError.NA -> 7.0; else -> FormulaError.NA }
        "LEN" -> text1(a, ev) { it.length.toDouble() }
        "LOWER" -> text1(a, ev) { it.lowercase() }
        "UPPER" -> text1(a, ev) { it.uppercase() }
        "PROPER" -> text1(a, ev) { s -> val sb = StringBuilder(); var prev = false; for (ch in s) { sb.append(if (prev) ch.lowercaseChar() else ch.uppercaseChar()); prev = ch.isLetter() }; sb.toString() }
        "TRIM" -> text1(a, ev) { it.trim(' ').replace(Regex(" {2,}"), " ") }
        "CLEAN" -> text1(a, ev) { s -> s.filter { it.code >= 32 } }
        "CODE", "UNICODE" -> text1(a, ev) { if (it.isEmpty()) FormulaError.VALUE else it.codePointAt(0).toDouble() }
        "CHAR", "UNICHAR" -> math1(a, ev) { val c = it.toInt(); if (c < 1 || (name == "CHAR" && c > 255)) FormulaError.VALUE else String(Character.toChars(c)) }
        "LEFT", "RIGHT" -> lift2(a.getOrNull(0), if (a.size > 1) a[1] else 1.0, ev) { s, k ->
            val t = text(s); val nk = num(k ?: 1.0)
            if (t is FormulaError) t else if (nk is FormulaError) nk else { val c = (nk as Double).toInt(); if (c < 0) FormulaError.VALUE else (t as String).let { if (name == "LEFT") it.take(c) else it.takeLast(c) } }
        }
        "MID" -> {
            val t = text(arg(a, 0, ev)); val st = numArg(a, 1, ev); val len = numArg(a, 2, ev)
            if (t is FormulaError) t else if (st is FormulaError) st else if (len is FormulaError) len else {
                val s0 = (st as Double).toInt(); val l = (len as Double).toInt()
                if (s0 < 1 || l < 0) FormulaError.VALUE else (t as String).let { if (s0 > it.length) "" else it.substring(s0 - 1, minOf(it.length, s0 - 1 + l)) }
            }
        }
        "FIND", "SEARCH" -> {
            val needle = text(arg(a, 0, ev)); val hay = text(arg(a, 1, ev)); val st = numArg(a, 2, ev, 1.0)
            if (needle is FormulaError) needle else if (hay is FormulaError) hay else if (st is FormulaError) st else {
                val h = hay as String; val nd = needle as String; val s0 = (st as Double).toInt() - 1
                if (s0 < 0 || s0 > h.length) FormulaError.VALUE else {
                    val idx = if (name == "FIND") h.indexOf(nd, s0) else if (nd.contains('*') || nd.contains('?') || nd.contains('~')) {
                        wildcard(nd).find(h.substring(s0))?.range?.first?.let { it + s0 } ?: -1
                    } else h.indexOf(nd, s0, ignoreCase = true)
                    if (idx < 0) FormulaError.VALUE else (idx + 1).toDouble()
                }
            }
        }
        "SUBSTITUTE" -> {
            val t = text(arg(a, 0, ev)); val old = text(arg(a, 1, ev)); val new = text(arg(a, 2, ev))
            if (t is FormulaError) t else if (old is FormulaError) old else if (new is FormulaError) new else {
                val s = t as String; val o = old as String; val nw = new as String
                if (o.isEmpty()) s else if (a.size > 3) {
                    val k = (num(arg(a, 3, ev)) as? Double)?.toInt() ?: 1
                    var idx = -1; var c = 0
                    while (true) { idx = s.indexOf(o, idx + 1); if (idx < 0) break; c++; if (c == k) break }
                    if (idx < 0) s else s.substring(0, idx) + nw + s.substring(idx + o.length)
                } else s.replace(o, nw)
            }
        }
        "REPLACE" -> {
            val t = text(arg(a, 0, ev)); val st = numArg(a, 1, ev); val len = numArg(a, 2, ev); val nw = text(arg(a, 3, ev))
            if (t is FormulaError) t else if (st is FormulaError) st else if (len is FormulaError) len else if (nw is FormulaError) nw else {
                val s = t as String; val s0 = ((st as Double).toInt() - 1).coerceIn(0, s.length); val e = (s0 + (len as Double).toInt()).coerceIn(s0, s.length)
                s.substring(0, s0) + nw + s.substring(e)
            }
        }
        "REPT" -> { val t = text(arg(a, 0, ev)); val k = numArg(a, 1, ev); if (t is FormulaError) t else if (k is FormulaError) k else (t as String).repeat((k as Double).toInt().coerceIn(0, 32767 / maxOf(1, t.length))) }
        "EXACT" -> { val x = text(arg(a, 0, ev)); val y = text(arg(a, 1, ev)); if (x is FormulaError) x else if (y is FormulaError) y else x == y }
        "CONCATENATE", "CONCAT" -> {
            val vals = ArrayList<Any?>(); flatten(a, ev, vals)
            vals.firstOrNull { it is FormulaError } ?: vals.joinToString("") { toText(it) }
        }
        "TEXTJOIN" -> {
            val delim = toText(arg(a, 0, ev)); val skip = truthy(bool(arg(a, 1, ev)))
            val vals = ArrayList<Any?>(); flatten(a.drop(2), ev, vals)
            vals.firstOrNull { it is FormulaError } ?: vals.filter { !(skip && (it == null || it == "")) }.joinToString(delim) { toText(it) }
        }
        "VALUE", "NUMBERVALUE" -> lift1(a.getOrNull(0), ev) { v -> if (v is Double) v else if (v is FormulaError) v else (parseNumber(toText(v)) ?: dateText(toText(v)) ?: FormulaError.VALUE) }
        "TEXT" -> {
            val v = arg(a, 0, ev); val fmt = text(arg(a, 1, ev))
            if (v is FormulaError) v else if (fmt is FormulaError) fmt else {
                val d = if (v is Double) v else if (v is Boolean) null else parseNumber(toText(v)) ?: dateText(toText(v))
                if (d == null) toText(v) else ExcelNumberFormat.formatCode(d, fmt as String, ev.ctx.date1904).text
            }
        }
        "FIXED", "DOLLAR" -> {
            val v = numArg(a, 0, ev); val d = numArg(a, 1, ev, 2.0)
            if (v is FormulaError) v else if (d is FormulaError) d else {
                val digits = (d as Double).toInt().coerceIn(-15, 30)
                val noComma = name == "FIXED" && a.size > 2 && truthy(bool(arg(a, 2, ev)))
                val code = (if (noComma) "0" else "#,##0") + (if (digits > 0) "." + "0".repeat(digits) else "")
                val r = round(v as Double, digits, RoundingMode.HALF_UP)
                (if (name == "DOLLAR") "$" else "") + ExcelNumberFormat.formatCode(abs(r), code).text.let { if (r < 0) "-$it" else it }
            }
        }
        "DATE" -> {
            val y = numArg(a, 0, ev); val m = numArg(a, 1, ev); val d = numArg(a, 2, ev)
            if (y is FormulaError) y else if (m is FormulaError) m else if (d is FormulaError) d else try { serial((y as Double).toInt(), (m as Double).toInt(), (d as Double).toInt()) } catch (_: Exception) { FormulaError.NUM }
        }
        "TIME" -> {
            val h = numArg(a, 0, ev); val m = numArg(a, 1, ev); val s = numArg(a, 2, ev)
            if (h is FormulaError) h else if (m is FormulaError) m else if (s is FormulaError) s else ((h as Double) * 3600 + (m as Double) * 60 + (s as Double)).let { (it / 86400.0).let { x -> x - floor(x) } }
        }
        "DATEVALUE", "TIMEVALUE" -> text1(a, ev) { t -> dateText(t)?.let { if (name == "TIMEVALUE") it - floor(it) else floor(it) } ?: FormulaError.VALUE }
        "YEAR" -> math1(a, ev) { dateOf(it, ev).year.toDouble() }
        "MONTH" -> math1(a, ev) { if (!ev.ctx.date1904 && floor(it) == 60.0) 2.0 else dateOf(it, ev).monthValue.toDouble() }
        "DAY" -> math1(a, ev) { if (!ev.ctx.date1904 && floor(it) == 60.0) 29.0 else if (!ev.ctx.date1904 && floor(it) == 0.0) 0.0 else dateOf(it, ev).dayOfMonth.toDouble() }
        "HOUR" -> math1(a, ev) { floor((it - floor(it)) * 24 + 1e-9) }
        "MINUTE" -> math1(a, ev) { floor(((it - floor(it)) * 1440 + 1e-9) % 60) }
        "SECOND" -> math1(a, ev) { Math.round((it - floor(it)) * 86400) % 60.0 }
        "WEEKDAY" -> math2(a, ev, 1.0) { d, t ->
            val dow = dateOf(d, ev).dayOfWeek.value
            when (t.toInt()) { 1, 17 -> (dow % 7) + 1.0; 2, 11 -> dow.toDouble(); 3 -> (dow - 1).toDouble(); in 12..16 -> ((dow - (t.toInt() - 10) + 7) % 7) + 1.0; else -> FormulaError.NUM }
        }
        "WEEKNUM" -> math2(a, ev, 1.0) { d, t ->
            val date = dateOf(d, ev)
            if (t.toInt() == 21) date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble() else {
                val start = if (t.toInt() == 2 || t.toInt() == 11) 1 else 7
                val jan1 = date.withDayOfYear(1)
                val offset = (jan1.dayOfWeek.value - start + 7) % 7
                ((date.dayOfYear + offset - 1) / 7 + 1).toDouble()
            }
        }
        "ISOWEEKNUM" -> math1(a, ev) { dateOf(it, ev).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble() }
        "TODAY" -> toSerial(LocalDate.now(), ev)
        "NOW" -> { val now = java.time.LocalDateTime.now(); toSerial(now.toLocalDate(), ev) + now.toLocalTime().toSecondOfDay() / 86400.0 }
        "EDATE", "EOMONTH" -> math2(a, ev, null) { d, m ->
            val date = dateOf(d, ev).plusMonths(m.toLong())
            toSerial(if (name == "EOMONTH") date.withDayOfMonth(date.lengthOfMonth()) else date, ev)
        }
        "DAYS" -> math2(a, ev, null) { e, s -> floor(e) - floor(s) }
        "DAYS360" -> math2(a, ev, null) { s, e ->
            val d1 = dateOf(s, ev); val d2 = dateOf(e, ev)
            var a1 = d1.dayOfMonth; var a2 = d2.dayOfMonth
            if (a1 == 31) a1 = 30
            if (a2 == 31 && a1 == 30) a2 = 30
            ((d2.year - d1.year) * 360 + (d2.monthValue - d1.monthValue) * 30 + (a2 - a1)).toDouble()
        }
        "DATEDIF" -> {
            val s = numArg(a, 0, ev); val e = numArg(a, 1, ev); val unit = toText(arg(a, 2, ev)).uppercase(Locale.ROOT)
            if (s is FormulaError) s else if (e is FormulaError) e else if ((s as Double) > (e as Double)) FormulaError.NUM else {
                val d1 = dateOf(s, ev); val d2 = dateOf(e, ev)
                when (unit) {
                    "Y" -> ChronoUnit.YEARS.between(d1, d2).toDouble()
                    "M" -> ChronoUnit.MONTHS.between(d1, d2).toDouble()
                    "D" -> ChronoUnit.DAYS.between(d1, d2).toDouble()
                    "YM" -> (ChronoUnit.MONTHS.between(d1, d2) % 12).toDouble()
                    "MD" -> { var x = d2.dayOfMonth - d1.dayOfMonth; if (x < 0) x += d2.minusMonths(1).lengthOfMonth(); x.toDouble() }
                    "YD" -> { var x = d1.withYear(d2.year); if (x.isAfter(d2)) x = x.minusYears(1); ChronoUnit.DAYS.between(x, d2).toDouble() }
                    else -> FormulaError.NUM
                }
            }
        }
        "YEARFRAC" -> {
            val s = numArg(a, 0, ev); val e = numArg(a, 1, ev); val basis = (num(arg(a, 2, ev)) as? Double)?.toInt() ?: 0
            if (s is FormulaError) s else if (e is FormulaError) e else {
                val lo = minOf(s as Double, e as Double); val hi = maxOf(s, e)
                when (basis) {
                    1 -> (hi - lo) / (if (dateOf(lo, ev).isLeapYear || dateOf(hi, ev).isLeapYear) 366.0 else 365.0)
                    2 -> (floor(hi) - floor(lo)) / 360.0
                    3 -> (floor(hi) - floor(lo)) / 365.0
                    else -> {
                        val d1 = dateOf(lo, ev); val d2 = dateOf(hi, ev)
                        var a1 = d1.dayOfMonth; var a2 = d2.dayOfMonth
                        if (a1 == 31) a1 = 30
                        if (a2 == 31 && a1 >= 30) a2 = 30
                        ((d2.year - d1.year) * 360 + (d2.monthValue - d1.monthValue) * 30 + (a2 - a1)) / 360.0
                    }
                }
            }
        }
        "NETWORKDAYS", "NETWORKDAYS.INTL", "WORKDAY", "WORKDAY.INTL" -> {
            val s = numArg(a, 0, ev); val e = numArg(a, 1, ev)
            val intl = name.endsWith(".INTL")
            val holIdx = if (intl) 3 else 2
            val hol = HashSet<Long>()
            a.getOrNull(holIdx)?.let { h -> val xs = numbers(listOf(h), ev); if (xs is List<*>) xs.forEach { hol.add(floor(it as Double).toLong()) } }
            val weekend = BooleanArray(8)
            weekend[6] = true; weekend[7] = true
            if (intl && a.size > 2 && a[2] != null) {
                when (val w = arg(a, 2, ev)) {
                    is Double -> { weekend.fill(false); when (w.toInt()) { 1 -> { weekend[6] = true; weekend[7] = true }; 2 -> { weekend[7] = true; weekend[1] = true }; in 3..7 -> { val k = w.toInt(); weekend[k - 2] = true; weekend[k - 1] = true }; in 11..17 -> weekend[(w.toInt() - 10).let { if (it == 1) 7 else it - 1 }] = true; else -> { weekend[6] = true; weekend[7] = true } } }
                    is String -> if (w.length == 7) { weekend.fill(false); for (k in 0 until 7) weekend[k + 1] = w[k] == '1' }
                    else -> {}
                }
            }
            fun work(serialDay: Long): Boolean = !weekend[dateOf(serialDay.toDouble(), ev).dayOfWeek.value] && serialDay !in hol
            if (s is FormulaError) s else if (e is FormulaError) e else if (name.startsWith("NETWORKDAYS")) {
                val lo = floor(minOf(s as Double, e as Double)).toLong(); val hi = floor(maxOf(s, e)).toLong()
                if (hi - lo > 200_000) FormulaError.NUM else ((lo..hi).count { work(it) } * (if (s > e) -1 else 1)).toDouble()
            } else {
                var d = floor(s as Double).toLong(); var left = (e as Double).toLong(); val step = if (left < 0) -1 else 1
                var guard = 0
                while (left != 0L && guard++ < 1_000_000) { d += step; if (work(d)) left -= step }
                d.toDouble()
            }
        }
        "VLOOKUP", "HLOOKUP" -> {
            val needle = arg(a, 0, ev); val table = toArray(a.getOrNull(1), ev); val idx = numArg(a, 2, ev)
            val approx = a.size < 4 || a[3] == null || truthy(bool(arg(a, 3, ev)))
            if (needle is FormulaError) needle else if (table == null) FormulaError.REF else if (idx is FormulaError) idx else {
                val k = (idx as Double).toInt()
                val vertical = name == "VLOOKUP"
                if (k < 1 || k > (if (vertical) table.cols else table.rows)) FormulaError.REF else {
                    val keys = if (vertical) List(table.rows) { table[it, 0] } else List(table.cols) { table[0, it] }
                    val i = lookupIndex(needle, keys, if (approx) 1 else 0)
                    if (i < 0) FormulaError.NA else if (vertical) table[i, k - 1] else table[k - 1, i]
                }
            }
        }
        "LOOKUP" -> {
            val needle = arg(a, 0, ev); val look = toArray(a.getOrNull(1), ev)
            if (needle is FormulaError) needle else if (look == null) FormulaError.NA else {
                val vertical = look.rows >= look.cols
                val keys = if (a.size > 2) look.data.toList() else if (vertical) List(look.rows) { look[it, 0] } else List(look.cols) { look[0, it] }
                val i = lookupIndex(needle, keys, 1)
                if (i < 0) FormulaError.NA else if (a.size > 2) toArray(a[2], ev)?.data?.getOrNull(i) ?: FormulaError.NA
                else if (vertical) look[i, look.cols - 1] else look[look.rows - 1, i]
            }
        }
        "MATCH", "XMATCH" -> {
            val needle = arg(a, 0, ev); val look = toArray(a.getOrNull(1), ev)
            val mode = if (name == "MATCH") ((num(arg(a, 2, ev).let { if (a.size < 3) 1.0 else it }) as? Double) ?: 1.0).toInt() else ((num(arg(a, 2, ev) ?: 0.0) as? Double) ?: 0.0).toInt()
            if (needle is FormulaError) needle else if (look == null) FormulaError.NA else {
                val keys = look.data.toList()
                val i = if (name == "MATCH") lookupIndex(needle, keys, mode.coerceIn(-1, 1)) else when (mode) {
                    0, 2 -> lookupIndex(needle, keys, 0)
                    -1 -> keys.indices.firstOrNull { k -> keys[k] != null && sameType(keys[k], needle) && compare(keys[k], needle) == 0 }
                        ?: keys.indices.filter { keys[it] != null && sameType(keys[it], needle) && compare(keys[it], needle) < 0 }.maxWithOrNull { x, y -> compare(keys[x], keys[y]) } ?: -1
                    else -> keys.indices.firstOrNull { k -> keys[k] != null && sameType(keys[k], needle) && compare(keys[k], needle) == 0 }
                        ?: keys.indices.filter { keys[it] != null && sameType(keys[it], needle) && compare(keys[it], needle) > 0 }.minWithOrNull { x, y -> compare(keys[x], keys[y]) } ?: -1
                }
                if (i < 0) FormulaError.NA else (i + 1).toDouble()
            }
        }
        "XLOOKUP" -> {
            val needle = arg(a, 0, ev); val look = toArray(a.getOrNull(1), ev); val ret = toArray(a.getOrNull(2), ev)
            if (needle is FormulaError) needle else if (look == null || ret == null) FormulaError.VALUE else {
                val mode = (num(arg(a, 4, ev) ?: 0.0) as? Double)?.toInt() ?: 0
                val search = (num(arg(a, 5, ev) ?: 1.0) as? Double)?.toInt() ?: 1
                val keys = look.data.toList()
                val order = if (search < 0) keys.indices.reversed() else keys.indices
                var hit = -1
                if (mode == 0 || mode == 2) {
                    val re = if (mode == 2 && needle is String) wildcard(needle) else null
                    hit = order.firstOrNull { k -> val v = keys[k]; if (re != null) v is String && re.matches(v) else v != null && sameType(v, needle) && compare(v, needle) == 0 } ?: -1
                } else {
                    val exact = order.firstOrNull { k -> keys[k] != null && sameType(keys[k], needle) && compare(keys[k], needle) == 0 }
                    hit = exact ?: (if (mode == -1) keys.indices.filter { keys[it] != null && sameType(keys[it], needle) && compare(keys[it], needle) < 0 }.maxWithOrNull { x, y -> compare(keys[x], keys[y]) }
                    else keys.indices.filter { keys[it] != null && sameType(keys[it], needle) && compare(keys[it], needle) > 0 }.minWithOrNull { x, y -> compare(keys[x], keys[y]) }) ?: -1
                }
                if (hit < 0) (if (a.size > 3 && a[3] != null) scalar(a[3], ev) else FormulaError.NA)
                else if (look.cols == 1 && ret.rows == look.rows) {
                    if (ret.cols == 1) ret[hit, 0] else FormulaArray(1, ret.cols, Array(ret.cols) { ret[hit, it] })
                } else if (look.rows == 1 && ret.cols == look.cols) {
                    if (ret.rows == 1) ret[0, hit] else FormulaArray(ret.rows, 1, Array(ret.rows) { ret[it, hit] })
                } else ret.data.getOrNull(hit) ?: FormulaError.NA
            }
        }
        "INDEX" -> {
            val src = a.getOrNull(0)
            val r = (num(arg(a, 1, ev) ?: 0.0) as? Double)?.toInt() ?: return FormulaError.VALUE
            val c = if (a.size > 2) (num(arg(a, 2, ev) ?: 0.0) as? Double)?.toInt() ?: return FormulaError.VALUE else 0
            when (src) {
                is FormulaRange -> {
                    val rr = if (src.rows == 1 && a.size < 3) 1 else r
                    val cc = if (src.rows == 1 && a.size < 3) r else c
                    if (rr > src.rows || cc > src.cols || rr < 0 || cc < 0) FormulaError.REF
                    else if (rr == 0 && cc == 0) src
                    else if (rr == 0) FormulaRange(src.sheet, src.r0, src.c0 + cc - 1, src.r1, src.c0 + cc - 1)
                    else if (cc == 0) (if (src.cols == 1) FormulaRange(src.sheet, src.r0 + rr - 1, src.c0, src.r0 + rr - 1, src.c0) else FormulaRange(src.sheet, src.r0 + rr - 1, src.c0, src.r0 + rr - 1, src.c1))
                    else FormulaRange(src.sheet, src.r0 + rr - 1, src.c0 + cc - 1, src.r0 + rr - 1, src.c0 + cc - 1)
                }
                is FormulaArray -> {
                    val rr = if (src.rows == 1 && a.size < 3) 1 else r
                    val cc = if (src.rows == 1 && a.size < 3) r else c.coerceAtLeast(if (src.cols == 1) 1 else 0)
                    if (rr < 1 || cc < 1 || rr > src.rows || cc > src.cols) FormulaError.REF else src[rr - 1, cc - 1]
                }
                else -> if (r <= 1 && c <= 1) scalar(src, ev) else FormulaError.REF
            }
        }
        "OFFSET" -> {
            val base = refOf(a.getOrNull(0))
            val dr = (num(arg(a, 1, ev)) as? Double)?.toInt(); val dc = (num(arg(a, 2, ev)) as? Double)?.toInt()
            if (base == null || dr == null || dc == null) FormulaError.VALUE else {
                val h = if (a.size > 3 && a[3] != null) (num(arg(a, 3, ev)) as? Double)?.toInt() ?: base.rows else base.rows
                val w = if (a.size > 4 && a[4] != null) (num(arg(a, 4, ev)) as? Double)?.toInt() ?: base.cols else base.cols
                val r0 = base.r0 + dr; val c0 = base.c0 + dc
                if (r0 < 0 || c0 < 0 || h < 1 || w < 1) FormulaError.REF else FormulaRange(base.sheet, r0, c0, r0 + h - 1, c0 + w - 1)
            }
        }
        "INDIRECT" -> {
            val t = toText(arg(a, 0, ev))
            val f = compile(t)
            val r = f?.evaluateRaw(ev.ctx, ev.row, ev.col, ev.sheet)
            if (r is FormulaRange) r else FormulaError.REF
        }
        "ADDRESS" -> {
            val r = numArg(a, 0, ev); val c = numArg(a, 1, ev); val absNum = (num(arg(a, 2, ev) ?: 1.0) as? Double)?.toInt() ?: 1
            val sheet = if (a.size > 4) toText(arg(a, 4, ev)) else ""
            if (r is FormulaError) r else if (c is FormulaError) c else {
                val ra = absNum == 1 || absNum == 2; val ca = absNum == 1 || absNum == 3
                (if (sheet.isNotEmpty()) (if (sheet.any { !it.isLetterOrDigit() }) "'$sheet'!" else "$sheet!") else "") +
                    (if (ca) "$" else "") + colLetters((c as Double).toInt() - 1) + (if (ra) "$" else "") + (r as Double).toInt()
            }
        }
        "ROWS", "COLUMNS" -> when (val v = a.getOrNull(0)) {
            is FormulaRange -> (if (name == "ROWS") v.rows else v.cols).toDouble()
            is FormulaArray -> (if (name == "ROWS") v.rows else v.cols).toDouble()
            else -> 1.0
        }
        "TRANSPOSE" -> toArray(a.getOrNull(0), ev)?.let { m -> FormulaArray(m.cols, m.rows, Array(m.data.size) { i -> m[i % m.rows, i / m.rows] }) } ?: arg(a, 0, ev)
        "UNIQUE" -> toArray(a.getOrNull(0), ev)?.let { m -> val seen = LinkedHashMap<String, Any?>(); for (v in m.data) seen.putIfAbsent(toText(v).lowercase(Locale.ROOT), v); FormulaArray(seen.size, 1, seen.values.toTypedArray()) } ?: arg(a, 0, ev)
        "SORT" -> toArray(a.getOrNull(0), ev)?.let { m -> val desc = (num(arg(a, 2, ev) ?: 1.0) as? Double) == -1.0; val s = m.data.sortedWith { x, y -> compare(x, y) }.let { if (desc) it.reversed() else it }; FormulaArray(s.size, 1, s.toTypedArray()) } ?: arg(a, 0, ev)
        "FILTER" -> {
            val m = toArray(a.getOrNull(0), ev); val inc = toArray(a.getOrNull(1), ev)
            if (m == null || inc == null) FormulaError.VALUE else {
                val rowsKept = (0 until m.rows).filter { r -> truthy(bool(inc.data.getOrNull(if (inc.cols == 1) r else r * inc.cols))) }
                if (rowsKept.isEmpty()) (if (a.size > 2) arg(a, 2, ev) else FormulaError.CALC) else FormulaArray(rowsKept.size, m.cols, Array(rowsKept.size * m.cols) { i -> m[rowsKept[i / m.cols], i % m.cols] })
            }
        }
        "SEQUENCE" -> {
            val r = (num(arg(a, 0, ev)) as? Double)?.toInt() ?: 1; val c = (num(arg(a, 1, ev) ?: 1.0) as? Double)?.toInt() ?: 1
            val st = (num(arg(a, 2, ev) ?: 1.0) as? Double) ?: 1.0; val step = (num(arg(a, 3, ev) ?: 1.0) as? Double) ?: 1.0
            if (r < 1 || c < 1 || r.toLong() * c > 1_000_000) FormulaError.VALUE else FormulaArray(r, c, Array(r * c) { st + it * step })
        }
        "PMT", "PV", "FV", "NPER", "IPMT", "PPMT" -> finance(name, a, ev)
        "NPV" -> {
            val rate = numArg(a, 0, ev)
            val cf = numbers(a.drop(1), ev)
            if (rate is FormulaError) rate else if (cf is FormulaError) cf else (cf as List<*>).withIndex().sumOf { (i, v) -> (v as Double) / (1 + rate as Double).pow(i + 1) }
        }
        "IRR" -> {
            val cf = numbers(listOf(a.getOrNull(0)), ev)
            if (cf is FormulaError) cf else {
                val xs = (cf as List<*>).map { it as Double }
                var r = (num(arg(a, 1, ev) ?: 0.1) as? Double) ?: 0.1
                var ok = false
                for (it in 0 until 100) {
                    var f = 0.0; var df = 0.0
                    for ((i, v) in xs.withIndex()) { f += v / (1 + r).pow(i); df -= i * v / (1 + r).pow(i + 1) }
                    if (df == 0.0) break
                    val nr = r - f / df
                    if (abs(nr - r) < 1e-10) { r = nr; ok = true; break }
                    r = nr
                }
                if (ok) r else FormulaError.NUM
            }
        }
        "RATE" -> {
            val nper = numArg(a, 0, ev); val pmt = numArg(a, 1, ev); val pv = numArg(a, 2, ev)
            val fv = (num(arg(a, 3, ev) ?: 0.0) as? Double) ?: 0.0; val type = (num(arg(a, 4, ev) ?: 0.0) as? Double) ?: 0.0
            if (nper is FormulaError) nper else if (pmt is FormulaError) pmt else if (pv is FormulaError) pv else {
                var r = (num(arg(a, 5, ev) ?: 0.1) as? Double) ?: 0.1
                val n = nper as Double; val p = pmt as Double; val v = pv as Double
                fun f(x: Double): Double = if (abs(x) < 1e-12) v + p * n + fv else v * (1 + x).pow(n) + p * (1 + x * type) * ((1 + x).pow(n) - 1) / x + fv
                var ok = false
                for (it in 0 until 200) {
                    val y = f(r); val dy = (f(r + 1e-7) - y) / 1e-7
                    if (dy == 0.0) break
                    val nr = r - y / dy
                    if (abs(nr - r) < 1e-10) { r = nr; ok = true; break }
                    r = nr
                }
                if (ok) r else FormulaError.NUM
            }
        }
        else -> null
    }

    private fun finance(name: String, a: List<Any?>, ev: Evaluation): Any? {
        val v = a.indices.map { i -> if (a[i] == null) 0.0 else num(scalar(a[i], ev)) }
        v.firstOrNull { it is FormulaError }?.let { return it }
        fun d(i: Int, def: Double = 0.0) = (v.getOrNull(i) as? Double) ?: def
        val rate = d(0)
        return when (name) {
            "PMT" -> { val n = d(1); val pv = d(2); val fv = d(3); val t = d(4); if (rate == 0.0) -(pv + fv) / n else -(pv * (1 + rate).pow(n) + fv) * rate / ((1 + rate * t) * ((1 + rate).pow(n) - 1)) }
            "PV" -> { val n = d(1); val pmt = d(2); val fv = d(3); val t = d(4); if (rate == 0.0) -(fv + pmt * n) else -(fv + pmt * (1 + rate * t) * ((1 + rate).pow(n) - 1) / rate) / (1 + rate).pow(n) }
            "FV" -> { val n = d(1); val pmt = d(2); val pv = d(3); val t = d(4); if (rate == 0.0) -(pv + pmt * n) else -(pv * (1 + rate).pow(n) + pmt * (1 + rate * t) * ((1 + rate).pow(n) - 1) / rate) }
            "NPER" -> { val pmt = d(1); val pv = d(2); val fv = d(3); val t = d(4); if (rate == 0.0) -(pv + fv) / pmt else { val x = pmt * (1 + rate * t); ln((x - fv * rate) / (x + pv * rate)) / ln(1 + rate) } }
            else -> {
                val per = d(1); val n = d(2); val pv = d(3); val fv = d(4); val t = d(5)
                val pmt = if (rate == 0.0) -(pv + fv) / n else -(pv * (1 + rate).pow(n) + fv) * rate / ((1 + rate * t) * ((1 + rate).pow(n) - 1))
                val fvBefore = if (rate == 0.0) -(pv + pmt * (per - 1)) else -(pv * (1 + rate).pow(per - 1) + pmt * (1 + rate * t) * ((1 + rate).pow(per - 1) - 1) / rate)
                val ipmt = if (t == 1.0 && per == 1.0) 0.0 else fvBefore * rate * (if (t == 1.0) 1 / (1 + rate) else 1.0)
                if (name == "IPMT") ipmt else pmt - ipmt
            }
        }
    }
}

class FormulaWorkbook(override val date1904: Boolean, private val names: Map<String, String> = emptyMap()) : FormulaContext {
    class Sheet(val name: String, val rows: Int, val cols: Int, val formulas: Map<Long, String>, val value: (Int, Int) -> Any?)

    private val sheets = LinkedHashMap<String, Sheet>()
    private val results = HashMap<String, HashMap<Long, Any?>>()
    private val visiting = HashSet<String>()

    fun add(sheet: Sheet) {
        sheets[sheet.name.lowercase(Locale.ROOT)] = sheet
    }

    private fun sheet(name: String?): Sheet? = if (name == null) sheets.values.firstOrNull() else sheets[name.lowercase(Locale.ROOT)]

    override fun cell(sheet: String?, row: Int, col: Int): Any? {
        val s = sheet(sheet) ?: return FormulaError.REF
        val key = SpreadsheetGridSession.packCoord(row, col)
        if (s.formulas.containsKey(key)) return result(s, key)
        return s.value(row, col)
    }

    override fun bounds(sheet: String?): IntArray? = sheet(sheet)?.let { intArrayOf(it.rows, it.cols) }

    override fun name(name: String): String? {
        val n = name.lowercase(Locale.ROOT)
        return names[n] ?: names[n.substringAfter('!')]
    }

    fun result(s: Sheet, key: Long): Any? {
        val cache = results.getOrPut(s.name.lowercase(Locale.ROOT)) { HashMap() }
        if (cache.containsKey(key)) return cache[key]
        val id = s.name.lowercase(Locale.ROOT) + ":" + key
        if (!visiting.add(id)) return 0.0
        val r = (key ushr 32).toInt()
        val c = (key and 0xFFFFFFFFL).toInt()
        val v = try {
            ExcelFormula.evaluate(s.formulas[key] ?: "", this, r, c, s.name)
        } catch (e: StackOverflowError) {
            FormulaError.CALC
        }
        visiting.remove(id)
        cache[key] = v
        return v
    }

    fun evaluateAll(sheetName: String): Map<Long, Any?> {
        val s = sheet(sheetName) ?: return emptyMap()
        val out = HashMap<Long, Any?>()
        for (key in s.formulas.keys.sorted()) out[key] = result(s, key)
        return out
    }
}
