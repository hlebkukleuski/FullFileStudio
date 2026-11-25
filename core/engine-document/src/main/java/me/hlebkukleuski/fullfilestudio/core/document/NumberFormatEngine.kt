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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

object ExcelNumberFormat {

    class Result(
        val text: String,
        val color: Int? = null,
        val fillAt: Int = -1,
        val fillChar: Char = ' ',
        val general: Boolean = false
    )

    @Volatile
    var systemLocale: Locale = Locale.getDefault()
        set(value) {
            field = value
            separators = null
        }

    @Volatile
    private var separators: CharArray? = null

    private fun seps(): CharArray = separators ?: run {
        val sym = try { java.text.DecimalFormatSymbols(systemLocale) } catch (_: Throwable) { java.text.DecimalFormatSymbols(Locale.US) }
        val group = sym.groupingSeparator.let { if (it == '\u202F') '\u00A0' else it }
        charArrayOf(sym.decimalSeparator, group).also { separators = it }
    }

    val decimalSeparator: Char get() = seps()[0]
    val groupingSeparator: Char get() = seps()[1]

    private const val T_LIT = 0
    private const val T_DIGIT = 1
    private const val T_POINT = 2
    private const val T_COMMA = 3
    private const val T_PERCENT = 4
    private const val T_EXP = 5
    private const val T_SLASH = 6
    private const val T_AT = 7
    private const val T_FILL = 8
    private const val T_DATE = 9
    private const val T_GENERAL = 10

    private class Tok(val type: Int, val text: String)

    private class Section(
        val tokens: List<Tok>,
        val color: Int?,
        val condOp: String?,
        val condValue: Double,
        val locale: Locale?,
        val isDate: Boolean,
        val hasAt: Boolean,
        val isGeneral: Boolean,
        val systemDate: Int
    )

    private val cache = ConcurrentHashMap<String, List<Section>>()

    private val PALETTE = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )

    private val BUILTIN = mapOf(
        0 to "General",
        1 to "0",
        2 to "0.00",
        3 to "#,##0",
        4 to "#,##0.00",
        5 to "\$#,##0_);(\$#,##0)",
        6 to "\$#,##0_);[Red](\$#,##0)",
        7 to "\$#,##0.00_);(\$#,##0.00)",
        8 to "\$#,##0.00_);[Red](\$#,##0.00)",
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        12 to "# ?/?",
        13 to "# ??/??",
        15 to "d-mmm-yy",
        16 to "d-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        37 to "#,##0_);(#,##0)",
        38 to "#,##0_);[Red](#,##0)",
        39 to "#,##0.00_);(#,##0.00)",
        40 to "#,##0.00_);[Red](#,##0.00)",
        41 to "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)",
        42 to "_(\"\$\"* #,##0_);_(\"\$\"* \\(#,##0\\);_(\"\$\"* \"-\"_);_(@_)",
        43 to "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)",
        44 to "_(\"\$\"* #,##0.00_);_(\"\$\"* \\(#,##0.00\\);_(\"\$\"* \"-\"??_);_(@_)",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mm:ss.0",
        48 to "##0.0E+0",
        49 to "@"
    )

    fun shortDateCode(locale: Locale = systemLocale): String {
        val pattern = try {
            (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()
        } catch (_: Throwable) { null } ?: "M/d/yy"
        val sb = StringBuilder()
        var i = 0
        var inQuote = false
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\'') { inQuote = !inQuote; i++; continue }
            if (inQuote) { sb.append('\\').append(ch); i++; continue }
            when (ch) {
                'y', 'Y', 'u' -> {
                    while (i < pattern.length && pattern[i] == ch) i++
                    sb.append("yyyy")
                    continue
                }
                'M', 'L' -> {
                    var n = 0
                    while (i < pattern.length && pattern[i] == ch) { i++; n++ }
                    sb.append(if (n >= 2) "mm" else "m")
                    continue
                }
                'd' -> {
                    var n = 0
                    while (i < pattern.length && pattern[i] == ch) { i++; n++ }
                    sb.append(if (n >= 2) "dd" else "d")
                    continue
                }
                else -> if (ch.isLetter()) sb.append('\\').append(ch) else sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    fun code(numFmtId: Int, custom: String?): String {
        if (!custom.isNullOrBlank()) return custom
        return when (numFmtId) {
            14, in 27..36, in 50..58 -> shortDateCode()
            22 -> shortDateCode() + " h:mm"
            else -> BUILTIN[numFmtId] ?: "General"
        }
    }

    fun isDateTime(numFmtId: Int, custom: String?): Boolean {
        val sections = sections(code(numFmtId, custom))
        return sections.firstOrNull()?.isDate == true
    }

    fun isText(numFmtId: Int, custom: String?): Boolean {
        val c = code(numFmtId, custom)
        val s = sections(c)
        return s.size == 1 && s[0].hasAt && s[0].tokens.none { it.type == T_DIGIT || it.type == T_DATE }
    }

    fun format(value: Double, numFmtId: Int, custom: String?, date1904: Boolean = false): Result =
        formatCode(value, code(numFmtId, custom), date1904)

    fun formatCode(value: Double, formatCode: String, date1904: Boolean = false): Result {
        if (value.isNaN()) return Result("#NUM!")
        if (value.isInfinite()) return Result("#DIV/0!")
        val sections = sections(formatCode)
        if (sections.isEmpty()) return Result(general(value), general = true)
        val numeric = sections.take(if (sections.size >= 4) 3 else sections.size)
            .filter { !(it.hasAt && it.tokens.none { t -> t.type == T_DIGIT || t.type == T_DATE || t.type == T_GENERAL }) }
            .ifEmpty { sections }
        val pick: Section
        var v = value
        val hasCond = numeric.any { it.condOp != null }
        if (hasCond) {
            val matched = numeric.firstOrNull { it.condOp != null && test(it.condOp, value, it.condValue) }
            pick = matched ?: numeric.firstOrNull { it.condOp == null } ?: return Result(fill('#', 11))
        } else {
            when {
                numeric.size >= 3 && value == 0.0 -> pick = numeric[2]
                numeric.size >= 2 && value < 0 -> { pick = numeric[1]; v = abs(value) }
                else -> pick = numeric.getOrElse(0) { sections[0] }
            }
        }
        return render(pick, v, date1904, numeric.size < 2 || hasCond)
    }

    fun formatText(text: String, numFmtId: Int, custom: String?): Result {
        val c = code(numFmtId, custom)
        val sections = sections(c)
        val textSection = when {
            sections.size >= 4 -> sections[3]
            sections.size == 1 && sections[0].hasAt -> sections[0]
            else -> sections.firstOrNull { it.hasAt && it.tokens.none { t -> t.type == T_DIGIT || t.type == T_DATE } }
        } ?: return Result(text)
        val sb = StringBuilder()
        var fillAt = -1
        var fillChar = ' '
        for (t in textSection.tokens) {
            when (t.type) {
                T_AT -> sb.append(text)
                T_FILL -> { fillAt = sb.length; fillChar = t.text[0] }
                T_LIT -> sb.append(t.text)
                else -> sb.append(t.text)
            }
        }
        return Result(sb.toString(), textSection.color, fillAt, fillChar)
    }

    private fun test(op: String, v: Double, c: Double): Boolean = when (op) {
        "<" -> v < c
        "<=" -> v <= c
        ">" -> v > c
        ">=" -> v >= c
        "=" -> v == c
        "<>" -> v != c
        else -> false
    }

    private fun exact(v: Double): BigDecimal = BigDecimal(v).round(java.math.MathContext(15, RoundingMode.HALF_EVEN))

    private fun fill(ch: Char, n: Int) = String(CharArray(n) { ch })

    fun general(value: Double): String {
        if (value == 0.0) return "0"
        val a = abs(value)
        if (a >= 1e11 || a < 1e-4) return scientificGeneral(value)
        val intPart = floor(a).toLong()
        val intLen = intPart.toString().length + (if (value < 0) 1 else 0)
        val decimals = (10 - intLen).coerceIn(0, 10)
        val bd = exact(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString()
        return if (s == "-0") "0" else s.replace('.', decimalSeparator)
    }

    private fun scientificGeneral(value: Double): String {
        val a = abs(value)
        var e = floor(log10(a)).toInt()
        var m = a / 10.0.pow(e)
        var mb = exact(m).setScale(5, RoundingMode.HALF_UP)
        if (mb >= BigDecimal.TEN) { e += 1; m = a / 10.0.pow(e); mb = exact(m).setScale(5, RoundingMode.HALF_UP) }
        val ms = mb.stripTrailingZeros().toPlainString().replace('.', decimalSeparator)
        val es = (if (e < 0) "-" else "+") + abs(e).toString().padStart(2, '0')
        return (if (value < 0) "-" else "") + ms + "E" + es
    }

    private fun sections(code: String): List<Section> {
        cache[code]?.let { return it }
        val parsed = try { parse(code) } catch (_: Exception) { emptyList() }
        if (cache.size > 4096) cache.clear()
        cache[code] = parsed
        return parsed
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length - 1 else it }
                    sb.append(code, i, end + 1)
                    i = end + 1
                    continue
                }
                ch == '\\' && i + 1 < code.length -> { sb.append(ch).append(code[i + 1]); i += 2; continue }
                (ch == '_' || ch == '*') && i + 1 < code.length -> { sb.append(ch).append(code[i + 1]); i += 2; continue }
                ch == '[' -> {
                    val end = code.indexOf(']', i + 1).let { if (it < 0) code.length - 1 else it }
                    sb.append(code, i, end + 1)
                    i = end + 1
                    continue
                }
                ch == ';' -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out.take(4)
    }

    private val COLOR_NAMES = mapOf(
        "black" to 0xFF000000.toInt(), "blue" to 0xFF0000FF.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "green" to 0xFF00FF00.toInt(), "magenta" to 0xFFFF00FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "white" to 0xFFFFFFFF.toInt(), "yellow" to 0xFFFFFF00.toInt()
    )

    private fun lcidLocale(lcid: Int): Locale? = when (lcid and 0xFFFF) {
        0x0409 -> Locale.US
        0x0809 -> Locale.UK
        0x0419 -> Locale.forLanguageTag("ru-RU")
        0x0422 -> Locale.forLanguageTag("uk-UA")
        0x0423 -> Locale.forLanguageTag("be-BY")
        0x0407, 0x0C07, 0x0807 -> Locale.GERMANY
        0x040C, 0x080C, 0x0C0C, 0x100C -> Locale.FRANCE
        0x0410 -> Locale.ITALY
        0x040A, 0x0C0A, 0x080A -> Locale.forLanguageTag("es-ES")
        0x0416 -> Locale.forLanguageTag("pt-BR")
        0x0816 -> Locale.forLanguageTag("pt-PT")
        0x0415 -> Locale.forLanguageTag("pl-PL")
        0x0405 -> Locale.forLanguageTag("cs-CZ")
        0x0413 -> Locale.forLanguageTag("nl-NL")
        0x041F -> Locale.forLanguageTag("tr-TR")
        0x0411 -> Locale.JAPAN
        0x0412 -> Locale.KOREA
        0x0804 -> Locale.SIMPLIFIED_CHINESE
        0x0404 -> Locale.TRADITIONAL_CHINESE
        0x041D -> Locale.forLanguageTag("sv-SE")
        0x0406 -> Locale.forLanguageTag("da-DK")
        0x0414 -> Locale.forLanguageTag("nb-NO")
        0x040B -> Locale.forLanguageTag("fi-FI")
        0x040E -> Locale.forLanguageTag("hu-HU")
        0x0418 -> Locale.forLanguageTag("ro-RO")
        0x0402 -> Locale.forLanguageTag("bg-BG")
        0x0408 -> Locale.forLanguageTag("el-GR")
        0x043F -> Locale.forLanguageTag("kk-KZ")
        else -> null
    }

    private fun parse(code: String): List<Section> {
        val out = ArrayList<Section>()
        for (raw in splitSections(code)) {
            val toks = ArrayList<Tok>()
            var color: Int? = null
            var condOp: String? = null
            var condValue = 0.0
            var locale: Locale? = null
            var systemDate = 0
            var i = 0
            val s = raw
            fun lit(t: String) { toks.add(Tok(T_LIT, t)) }
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '"' -> {
                        val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                        lit(s.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                    ch == '\\' -> { if (i + 1 < s.length) lit(s[i + 1].toString()); i += 2; continue }
                    ch == '_' -> { if (i + 1 < s.length) lit(" "); i += 2; continue }
                    ch == '*' -> { if (i + 1 < s.length) toks.add(Tok(T_FILL, s[i + 1].toString())); i += 2; continue }
                    ch == '[' -> {
                        val end = s.indexOf(']', i + 1).let { if (it < 0) s.length else it }
                        val inner = s.substring(i + 1, end)
                        val low = inner.lowercase(Locale.ROOT)
                        when {
                            low in COLOR_NAMES -> color = COLOR_NAMES[low]
                            low.startsWith("color") -> low.removePrefix("color").trim().toIntOrNull()?.let { n ->
                                if (n in 1..56) color = PALETTE[n - 1] or 0xFF000000.toInt()
                            }
                            inner.startsWith("<=") || inner.startsWith(">=") || inner.startsWith("<>") -> {
                                condOp = inner.substring(0, 2); condValue = inner.substring(2).trim().toDoubleOrNull() ?: 0.0
                            }
                            inner.startsWith("<") || inner.startsWith(">") || inner.startsWith("=") -> {
                                condOp = inner.substring(0, 1); condValue = inner.substring(1).trim().toDoubleOrNull() ?: 0.0
                            }
                            inner.startsWith("$") -> {
                                val dash = inner.indexOf('-')
                                val sym = if (dash >= 0) inner.substring(1, dash) else inner.substring(1)
                                if (sym.isNotEmpty()) lit(sym)
                                if (dash >= 0) {
                                    val hex = inner.substring(dash + 1).trim()
                                    val lcid = hex.toLongOrNull(16)?.toInt() ?: 0
                                    when {
                                        hex.equals("F800", true) -> systemDate = 1
                                        hex.equals("F400", true) -> systemDate = 2
                                        else -> lcidLocale(lcid)?.let { locale = it }
                                    }
                                }
                            }
                            low.isNotEmpty() && low.all { it == 'h' } -> toks.add(Tok(T_DATE, "[h" + low.length))
                            low.isNotEmpty() && low.all { it == 'm' } -> toks.add(Tok(T_DATE, "[m" + low.length))
                            low.isNotEmpty() && low.all { it == 's' } -> toks.add(Tok(T_DATE, "[s" + low.length))
                        }
                        i = end + 1
                        continue
                    }
                    ch == '0' || ch == '#' || ch == '?' -> toks.add(Tok(T_DIGIT, ch.toString()))
                    ch == '.' -> toks.add(Tok(T_POINT, "."))
                    ch == ',' -> toks.add(Tok(T_COMMA, ","))
                    ch == '%' -> toks.add(Tok(T_PERCENT, "%"))
                    (ch == 'E' || ch == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') -> {
                        toks.add(Tok(T_EXP, s[i + 1].toString())); i += 2; continue
                    }
                    ch == '/' -> toks.add(Tok(T_SLASH, "/"))
                    ch == '@' -> toks.add(Tok(T_AT, "@"))
                    s.regionMatches(i, "General", 0, 7, ignoreCase = true) -> { toks.add(Tok(T_GENERAL, "")); i += 7; continue }
                    s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { toks.add(Tok(T_DATE, "ampm:" + s.substring(i, i + 5))); i += 5; continue }
                    s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { toks.add(Tok(T_DATE, "ap:" + s.substring(i, i + 3))); i += 3; continue }
                    ch.lowercaseChar() in "ymdhs" -> {
                        val lc = ch.lowercaseChar()
                        var j = i
                        while (j < s.length && s[j].lowercaseChar() == lc) j++
                        toks.add(Tok(T_DATE, lc.toString().repeat(j - i)))
                        i = j
                        continue
                    }
                    ch.lowercaseChar() == 'b' && i + 1 < s.length && (s[i + 1] == '1' || s[i + 1] == '2') -> { i += 2; continue }
                    else -> lit(ch.toString())
                }
                i++
            }
            val isDate = toks.any { it.type == T_DATE } || systemDate != 0
            out.add(
                Section(
                    toks, color, condOp, condValue, locale, isDate,
                    toks.any { it.type == T_AT },
                    toks.any { it.type == T_GENERAL },
                    systemDate
                )
            )
        }
        return out
    }

    private fun render(sec: Section, value: Double, date1904: Boolean, signed: Boolean): Result {
        if (sec.isDate) {
            val text = if (sec.systemDate == 1) systemLongDate(value, date1904, sec.locale) else renderDate(sec, value, date1904)
            return Result(text, sec.color)
        }
        if (sec.isGeneral && sec.tokens.none { it.type == T_DIGIT }) {
            val sb = StringBuilder()
            var fillAt = -1
            var fillChar = ' '
            val g = general(if (signed) value else abs(value))
            for (t in sec.tokens) when (t.type) {
                T_GENERAL -> sb.append(g)
                T_FILL -> { fillAt = sb.length; fillChar = t.text[0] }
                T_DIGIT, T_DATE, T_AT -> {}
                else -> sb.append(t.text)
            }
            if (sec.tokens.isEmpty()) sb.append(g)
            return Result(sb.toString(), sec.color, fillAt, fillChar, general = sec.tokens.size <= 1)
        }
        if (sec.tokens.none { it.type == T_DIGIT }) {
            val sb = StringBuilder()
            var fillAt = -1
            var fillChar = ' '
            if (signed && value < 0) sb.append('-')
            for (t in sec.tokens) when (t.type) {
                T_FILL -> { fillAt = sb.length; fillChar = t.text[0] }
                T_AT -> {}
                else -> sb.append(t.text)
            }
            return Result(sb.toString(), sec.color, fillAt, fillChar)
        }
        return if (sec.tokens.any { it.type == T_SLASH } && fractionLayout(sec.tokens) != null) {
            renderFraction(sec, value, signed)
        } else {
            renderNumber(sec, value, signed)
        }
    }

    private fun renderNumber(sec: Section, value: Double, signed: Boolean): Result {
        val toks = sec.tokens
        val pointIdx = toks.indexOfFirst { it.type == T_POINT }
        val expIdx = toks.indexOfFirst { it.type == T_EXP }
        val intEnd = when {
            pointIdx >= 0 -> pointIdx
            expIdx >= 0 -> expIdx
            else -> toks.size
        }
        val intSlots = ArrayList<Int>()
        for (k in 0 until intEnd) if (toks[k].type == T_DIGIT) intSlots.add(k)
        val fracSlots = ArrayList<Int>()
        if (pointIdx >= 0) for (k in pointIdx + 1 until (if (expIdx >= 0) expIdx else toks.size)) if (toks[k].type == T_DIGIT) fracSlots.add(k)
        val expSlots = ArrayList<Int>()
        if (expIdx >= 0) for (k in expIdx + 1 until toks.size) if (toks[k].type == T_DIGIT) expSlots.add(k)

        var grouping = false
        var scaleCommas = 0
        if (intSlots.isNotEmpty()) {
            val first = intSlots.first()
            val last = intSlots.last()
            for (k in first until last) if (toks[k].type == T_COMMA) grouping = true
        }
        val lastDigit = (fracSlots.lastOrNull() ?: intSlots.lastOrNull())
        if (lastDigit != null) {
            var k = lastDigit + 1
            if (fracSlots.isEmpty() && k < toks.size && toks[k].type == T_POINT) k++
            while (k < toks.size && toks[k].type == T_COMMA) { scaleCommas++; k++ }
        }
        val percents = toks.count { it.type == T_PERCENT }
        var v = value
        repeat(percents) { v *= 100.0 }
        repeat(scaleCommas) { v /= 1000.0 }

        var exponent = 0
        val fracCount = fracSlots.size
        if (expIdx >= 0 && v != 0.0) {
            val a = abs(v)
            val eng = intSlots.size > 1 && toks[intSlots.first()].text == "#"
            val step = if (eng) intSlots.size else 1
            val lead = if (eng) 1 else intSlots.count { toks[it].text == "0" }.coerceAtLeast(1)
            var e = floor(log10(a)).toInt()
            e = if (eng) Math.floorDiv(e, step) * step else e - (lead - 1)
            fun mant(ex: Int): BigDecimal = exact(a).movePointLeft(ex).setScale(fracCount, RoundingMode.HALF_UP)
            var m = mant(e)
            val limit = BigDecimal.TEN.pow(if (eng) step else lead)
            if (m >= limit) { e += step; m = mant(e) }
            exponent = e
            v = if (v < 0) -m.toDouble() else m.toDouble()
        }

        val rounded = exact(abs(v)).setScale(fracCount, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        val intDigitsRaw = plain.substringBefore('.')
        val fracDigits = if (fracCount > 0) plain.substringAfter('.', "").padEnd(fracCount, '0') else ""
        val intDigits = if (intDigitsRaw == "0") "" else intDigitsRaw
        val negative = signed && value < 0 && (rounded.signum() != 0 || fracSlots.isNotEmpty() || intSlots.isNotEmpty())

        val intText = buildIntText(toks, intSlots, intDigits, grouping)
        val sb = StringBuilder()
        var fillAt = -1
        var fillChar = ' '
        if (negative) sb.append('-')
        var intEmitted = false
        var expDone = false
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            when {
                k < intEnd && t.type == T_DIGIT -> {
                    if (!intEmitted) { sb.append(intText.combined); intEmitted = true }
                }
                k < intEnd && t.type == T_COMMA -> {}
                intSlots.isNotEmpty() && k > intSlots.first() && k < intSlots.last() -> {}
                t.type == T_POINT && k == pointIdx -> {
                    if (!intEmitted) { sb.append(intText.combined); intEmitted = true }
                    val trimmed = trimFraction(toks, fracSlots, fracDigits)
                    sb.append(decimalSeparator)
                    sb.append(trimmed)
                }
                t.type == T_DIGIT && pointIdx in 0 until k && (expIdx < 0 || k < expIdx) -> {}
                t.type == T_POINT -> {}
                t.type == T_EXP -> {
                    if (!intEmitted) { sb.append(intText.combined); intEmitted = true }
                    sb.append('E')
                    if (exponent < 0) sb.append('-') else if (t.text == "+") sb.append('+')
                    val minDigits = expSlots.count { toks[it].text == "0" }.coerceAtLeast(1)
                    sb.append(abs(exponent).toString().padStart(minDigits, '0'))
                    expDone = true
                }
                t.type == T_DIGIT && expDone -> {}
                t.type == T_COMMA -> {}
                t.type == T_FILL -> { fillAt = sb.length; fillChar = t.text[0] }
                t.type == T_GENERAL -> sb.append(general(abs(value)))
                t.type == T_AT -> {}
                else -> sb.append(t.text)
            }
            k++
        }
        if (!intEmitted && intText.combined.isNotEmpty()) sb.insert(if (negative) 1 else 0, intText.combined)
        return Result(sb.toString(), sec.color, fillAt, fillChar)
    }

    private class IntText(val combined: String)

    private fun buildIntText(toks: List<Tok>, slots: List<Int>, digits: String, grouping: Boolean): IntText {
        if (slots.isEmpty()) return IntText(digits)
        val n = slots.size
        val padded = StringBuilder()
        if (digits.length >= n) {
            padded.append(digits)
        } else {
            val missing = n - digits.length
            for (k in 0 until missing) {
                when (toks[slots[k]].text) {
                    "0" -> padded.append('0')
                    "?" -> padded.append(' ')
                    else -> {}
                }
            }
            padded.append(digits)
        }
        val raw = padded.toString()
        if (!grouping) {
            val hasInnerLiterals = (slots.first()..slots.last()).any { toks[it].type == T_LIT }
            if (!hasInnerLiterals) return IntText(raw)
            val sb = StringBuilder()
            var di = digits.length - 1
            val chars = ArrayList<String>()
            for (idx in slots.indices.reversed()) {
                val slot = slots[idx]
                val piece = if (idx == 0) {
                    if (di >= 0) digits.substring(0, di + 1).also { di = -1 } else when (toks[slot].text) { "0" -> "0"; "?" -> " "; else -> "" }
                } else if (di >= 0) {
                    digits[di].toString().also { di-- }
                } else when (toks[slot].text) { "0" -> "0"; "?" -> " "; else -> "" }
                chars.add(0, piece)
            }
            var si = 0
            for (k in slots.first()..slots.last()) {
                val t = toks[k]
                if (t.type == T_DIGIT) { sb.append(chars[si]); si++ } else if (t.type == T_LIT) sb.append(t.text)
            }
            return IntText(sb.toString())
        }
        val trimmedLead = raw.trimStart(' ')
        val lead = raw.length - trimmedLead.length
        val sb = StringBuilder()
        val len = trimmedLead.length
        for (k in 0 until len) {
            sb.append(trimmedLead[k])
            val remaining = len - k - 1
            if (remaining > 0 && remaining % 3 == 0) sb.append(groupingSeparator)
        }
        return IntText(" ".repeat(lead) + sb.toString())
    }

    private fun trimFraction(toks: List<Tok>, slots: List<Int>, digits: String): String {
        val chars = digits.toCharArray()
        val out = CharArray(chars.size) { chars[it] }
        var end = chars.size
        for (k in chars.indices.reversed()) {
            if (chars[k] != '0') break
            when (toks[slots[k]].text) {
                "#" -> end = k
                "?" -> out[k] = ' '
                else -> break
            }
        }
        return String(out, 0, end.coerceAtMost(out.size))
    }

    private class FracLayout(val intGroup: IntRange?, val numGroup: IntRange, val denGroup: IntRange?, val fixedDen: Int)

    private fun fractionLayout(toks: List<Tok>): FracLayout? {
        val slash = toks.indexOfFirst { it.type == T_SLASH }
        if (slash <= 0) return null
        var k = slash - 1
        while (k >= 0 && toks[k].type != T_DIGIT) k--
        if (k < 0) return null
        val numEnd = k
        while (k >= 0 && (toks[k].type == T_DIGIT || toks[k].type == T_COMMA)) k--
        val numGroup = (k + 1)..numEnd
        var intGroup: IntRange? = null
        var j = k
        while (j >= 0 && toks[j].type != T_DIGIT) j--
        if (j >= 0) {
            val intEnd = j
            while (j >= 0 && (toks[j].type == T_DIGIT || toks[j].type == T_COMMA)) j--
            intGroup = (j + 1)..intEnd
        }
        var d = slash + 1
        while (d < toks.size && toks[d].type == T_LIT && toks[d].text == " ") d++
        if (d < toks.size && toks[d].type == T_LIT && toks[d].text[0].isDigit()) {
            val sb = StringBuilder()
            var e = d
            while (e < toks.size && toks[e].type == T_LIT && toks[e].text.all { it.isDigit() }) { sb.append(toks[e].text); e++ }
            val fixed = sb.toString().toIntOrNull() ?: return null
            if (fixed <= 0) return null
            return FracLayout(intGroup, numGroup, d until e, fixed)
        }
        if (d >= toks.size || toks[d].type != T_DIGIT) return null
        var e = d
        while (e < toks.size && toks[e].type == T_DIGIT) e++
        return FracLayout(intGroup, numGroup, d until e, 0)
    }

    private fun renderFraction(sec: Section, value: Double, signed: Boolean): Result {
        val toks = sec.tokens
        val layout = fractionLayout(toks)!!
        val a = abs(value)
        val denSlots = if (layout.fixedDen > 0) 0 else layout.denGroup!!.count { toks[it].type == T_DIGIT }
        val whole: Long
        var num: Long
        var den: Long
        val target = if (layout.intGroup != null) a - floor(a) else a
        if (layout.fixedDen > 0) {
            den = layout.fixedDen.toLong()
            num = (target * den).roundToLong()
        } else {
            val maxDen = (10.0.pow(denSlots.coerceIn(1, 4)) - 1).toLong()
            var bestN = 0L
            var bestD = 1L
            var bestErr = Double.MAX_VALUE
            for (dd in 1..maxDen) {
                val nn = (target * dd).roundToLong()
                val err = abs(target - nn.toDouble() / dd)
                if (err < bestErr - 1e-12) { bestErr = err; bestN = nn; bestD = dd }
                if (err == 0.0) break
            }
            num = bestN
            den = bestD
        }
        var w = if (layout.intGroup != null) floor(a).toLong() else 0L
        if (layout.intGroup != null && num >= den && den > 0) { w += num / den; num %= den }
        whole = w
        val negative = signed && value < 0 && (whole != 0L || num != 0L)
        fun slotsText(range: IntRange, digits: String, leftPad: Boolean): String {
            val slotList = range.filter { toks[it].type == T_DIGIT }
            val n = slotList.size
            if (digits.length >= n) return digits
            val pad = StringBuilder()
            for (k in 0 until n - digits.length) {
                val slot = if (leftPad) slotList[k] else slotList[digits.length + k]
                when (toks[slot].text) { "0" -> pad.append('0'); "?" -> pad.append(' '); else -> {} }
            }
            return if (leftPad) pad.toString() + digits else digits + pad.toString()
        }
        val intStr = layout.intGroup?.let { slotsText(it, if (whole == 0L) "" else whole.toString(), true) } ?: ""
        val zeroFrac = num == 0L
        val numStr = slotsText(layout.numGroup, num.toString(), true)
        val denStr = if (layout.fixedDen > 0) den.toString() else slotsText(layout.denGroup!!, den.toString(), false)
        val sb = StringBuilder()
        if (negative) sb.append('-')
        var fillAt = -1
        var fillChar = ' '
        val slash = toks.indexOfFirst { it.type == T_SLASH }
        val hideFraction = zeroFrac && layout.intGroup != null
        val showZero = zeroFrac && whole == 0L
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            when {
                layout.intGroup != null && k in layout.intGroup -> {
                    if (k == layout.intGroup.first) sb.append(if (showZero && intStr.isBlank()) "0" else intStr)
                }
                k in layout.numGroup -> {
                    if (k == layout.numGroup.first) sb.append(if (hideFraction || showZero) " ".repeat(numStr.length) else numStr)
                }
                k == slash -> sb.append(if (hideFraction || showZero) " " else "/")
                layout.denGroup != null && k in layout.denGroup -> {
                    if (k == layout.denGroup.first) sb.append(if (hideFraction || showZero) " ".repeat(denStr.length) else denStr)
                }
                t.type == T_FILL -> { fillAt = sb.length; fillChar = t.text[0] }
                t.type == T_DIGIT || t.type == T_COMMA -> {}
                else -> sb.append(t.text)
            }
            k++
        }
        var text = sb.toString()
        if (showZero && layout.intGroup == null) text = text.replaceFirst(" ".repeat(numStr.length), "0".padStart(numStr.length))
        return Result(if (hideFraction || showZero) text.trimEnd().let { if (it.isEmpty() || it == "-") "0" else it } else text, sec.color, fillAt, fillChar)
    }

    private class DateParts(
        val year: Int, val month: Int, val day: Int, val weekday: Int,
        val hour: Int, val minute: Int, val second: Int, val millis: Int,
        val totalSeconds: Double
    )

    private fun dateParts(value: Double, date1904: Boolean, roundMillis: Int): DateParts? {
        if (value < 0 || value > 2958466.0) return null
        val unitMs = when (roundMillis) { 0 -> 1000L; 1 -> 100L; 2 -> 10L; else -> 1L }
        val totalMs = ((value * 86_400_000.0) / unitMs).roundToLong() * unitMs
        var days = Math.floorDiv(totalMs, 86_400_000L)
        val msOfDay = totalMs - days * 86_400_000L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        cal.clear()
        var y: Int
        var m: Int
        var d: Int
        var wd: Int
        if (!date1904 && days == 60L) {
            y = 1900; m = 2; d = 29; wd = 4
        } else if (!date1904 && days == 0L) {
            y = 1900; m = 1; d = 0; wd = 7
        } else {
            if (date1904) {
                cal.set(1904, Calendar.JANUARY, 1)
            } else {
                if (days > 60) days -= 1
                cal.set(1899, Calendar.DECEMBER, 31)
            }
            cal.add(Calendar.DAY_OF_YEAR, days.toInt())
            y = cal.get(Calendar.YEAR); m = cal.get(Calendar.MONTH) + 1; d = cal.get(Calendar.DAY_OF_MONTH)
            wd = cal.get(Calendar.DAY_OF_WEEK)
        }
        val secs = (msOfDay / 1000L).toInt()
        return DateParts(y, m, d, wd, secs / 3600, (secs % 3600) / 60, secs % 60, (msOfDay % 1000L).toInt(), value * 86400.0)
    }

    private fun monthName(locale: Locale, month: Int, style: Int, standalone: Boolean): String {
        val cal = Calendar.getInstance(locale)
        cal.clear()
        cal.set(2001, month - 1, 1)
        val s = if (standalone) {
            cal.getDisplayName(Calendar.MONTH, if (style == Calendar.LONG) Calendar.LONG_STANDALONE else Calendar.SHORT_STANDALONE, locale)
        } else {
            cal.getDisplayName(Calendar.MONTH, style, locale)
        }
        val fallback = if (style == Calendar.LONG) DateFormatSymbols(locale).months[month - 1] else DateFormatSymbols(locale).shortMonths[month - 1]
        val r = (s ?: fallback).trimEnd('.')
        return if (locale.language == "en" || standalone) r.replaceFirstChar { it.titlecase(locale) } else r
    }

    private fun dayName(locale: Locale, weekday: Int, long: Boolean): String {
        val sym = DateFormatSymbols(locale)
        val arr = if (long) sym.weekdays else sym.shortWeekdays
        return (arr.getOrNull(weekday) ?: "").trimEnd('.')
    }

    private fun renderDate(sec: Section, value: Double, date1904: Boolean): String {
        val toks = sec.tokens
        var subDigits = 0
        for (k in toks.indices) {
            if (toks[k].type == T_DATE && toks[k].text.startsWith("s") || toks[k].type == T_DATE && toks[k].text.startsWith("[s")) {
                if (k + 1 < toks.size && toks[k + 1].type == T_POINT) {
                    var j = k + 2
                    var n = 0
                    while (j < toks.size && toks[j].type == T_DIGIT && toks[j].text == "0") { n++; j++ }
                    subDigits = maxOf(subDigits, n.coerceAtMost(3))
                }
            }
        }
        val p = dateParts(value, date1904, subDigits) ?: return fill('#', 8)
        val locale = sec.locale ?: Locale.US
        val ampm = toks.any { it.type == T_DATE && (it.text.startsWith("ampm:") || it.text.startsWith("ap:")) }
        val hasDay = toks.any { it.type == T_DATE && it.text.startsWith("d") }
        val dateIdx = toks.indices.filter { toks[it].type == T_DATE }
        val sb = StringBuilder()
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            if (t.type != T_DATE) {
                when (t.type) {
                    T_POINT -> {
                        val prev = dateIdx.lastOrNull { it < k }
                        if (prev != null && (toks[prev].text.startsWith("s") || toks[prev].text.startsWith("[s")) && k + 1 < toks.size && toks[k + 1].type == T_DIGIT) {
                            var j = k + 1
                            var n = 0
                            while (j < toks.size && toks[j].type == T_DIGIT) { n++; j++ }
                            val digits = n.coerceAtMost(3)
                            sb.append(decimalSeparator)
                            sb.append(p.millis.toString().padStart(3, '0').substring(0, digits))
                            k = j
                            continue
                        }
                        sb.append('.')
                    }
                    T_FILL, T_AT -> {}
                    T_GENERAL -> sb.append(general(value))
                    else -> sb.append(t.text)
                }
                k++
                continue
            }
            val code = t.text
            when {
                code.startsWith("[h") -> sb.append(floor(p.totalSeconds / 3600.0 + 1e-9).toLong().toString().padStart(code.substring(2).toInt(), '0'))
                code.startsWith("[m") -> sb.append(floor(p.totalSeconds / 60.0 + 1e-9).toLong().toString().padStart(code.substring(2).toInt(), '0'))
                code.startsWith("[s") -> sb.append((p.totalSeconds).roundToLong().toString().padStart(code.substring(2).toInt(), '0'))
                code.startsWith("ampm:") -> {
                    val orig = code.substringAfter(':')
                    val pm = p.hour >= 12
                    val txt = if (pm) "PM" else "AM"
                    sb.append(if (orig[0].isLowerCase()) txt.lowercase(Locale.ROOT) else txt)
                }
                code.startsWith("ap:") -> {
                    val orig = code.substringAfter(':')
                    val pm = p.hour >= 12
                    sb.append(if (pm) orig[2] else orig[0])
                }
                code[0] == 'y' -> sb.append(if (code.length <= 2) (p.year % 100).toString().padStart(2, '0') else p.year.toString().padStart(4, '0'))
                code[0] == 'd' -> sb.append(
                    when (code.length) {
                        1 -> p.day.toString()
                        2 -> p.day.toString().padStart(2, '0')
                        3 -> dayName(locale, p.weekday, false)
                        else -> dayName(locale, p.weekday, true)
                    }
                )
                code[0] == 'h' -> {
                    val h = if (ampm) ((p.hour + 11) % 12 + 1) else p.hour
                    sb.append(if (code.length >= 2) h.toString().padStart(2, '0') else h.toString())
                }
                code[0] == 's' -> sb.append(if (code.length >= 2) p.second.toString().padStart(2, '0') else p.second.toString())
                code[0] == 'm' -> {
                    val pos = dateIdx.indexOf(k)
                    val prev = dateIdx.getOrNull(pos - 1)?.let { toks[it].text }
                    val next = dateIdx.getOrNull(pos + 1)?.let { toks[it].text }
                    val minutes = code.length <= 2 && ((prev != null && (prev[0] == 'h' || prev.startsWith("[h"))) || (next != null && (next[0] == 's' || next.startsWith("[s"))))
                    if (minutes) {
                        sb.append(if (code.length >= 2) p.minute.toString().padStart(2, '0') else p.minute.toString())
                    } else {
                        sb.append(
                            when (code.length) {
                                1 -> p.month.toString()
                                2 -> p.month.toString().padStart(2, '0')
                                3 -> monthName(locale, p.month, Calendar.SHORT, !hasDay)
                                5 -> monthName(locale, p.month, Calendar.LONG, true).take(1)
                                else -> monthName(locale, p.month, Calendar.LONG, !hasDay)
                            }
                        )
                    }
                }
            }
            k++
        }
        return sb.toString()
    }

    private fun systemLongDate(value: Double, date1904: Boolean, locale: Locale?): String {
        val p = dateParts(value, date1904, 0) ?: return fill('#', 8)
        val loc = locale ?: systemLocale
        return try {
            val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, loc)
            df.timeZone = TimeZone.getTimeZone("UTC")
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), loc)
            cal.clear()
            cal.set(p.year, p.month - 1, p.day.coerceAtLeast(1))
            df.format(cal.time)
        } catch (_: Exception) {
            "${p.year}-${p.month}-${p.day}"
        }
    }
}
