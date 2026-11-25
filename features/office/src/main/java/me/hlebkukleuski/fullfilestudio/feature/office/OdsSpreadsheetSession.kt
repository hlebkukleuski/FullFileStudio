/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.Color
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.document.CfBlock
import me.hlebkukleuski.fullfilestudio.core.document.CfCells
import me.hlebkukleuski.fullfilestudio.core.document.CfRule
import me.hlebkukleuski.fullfilestudio.core.document.CfStyle
import me.hlebkukleuski.fullfilestudio.core.document.CfValueObject
import me.hlebkukleuski.fullfilestudio.core.document.DmlNode
import me.hlebkukleuski.fullfilestudio.core.document.EbookTextSession
import me.hlebkukleuski.fullfilestudio.core.document.ExcelFormula
import me.hlebkukleuski.fullfilestudio.core.document.ExcelNumberFormat
import me.hlebkukleuski.fullfilestudio.core.document.FormulaError
import me.hlebkukleuski.fullfilestudio.core.document.FormulaWorkbook
import me.hlebkukleuski.fullfilestudio.core.document.SheetBorder
import me.hlebkukleuski.fullfilestudio.core.document.SheetCellFlags
import me.hlebkukleuski.fullfilestudio.core.document.SheetCellStyle
import me.hlebkukleuski.fullfilestudio.core.document.SheetConditionalFormats
import me.hlebkukleuski.fullfilestudio.core.document.SheetFill
import me.hlebkukleuski.fullfilestudio.core.document.SpreadsheetGridSession
import org.xmlpull.v1.XmlPullParser

class OdsSpreadsheetSession private constructor(
    private val pfd: ParcelFileDescriptor?,
    preloadedEntries: Map<String, ByteArray>?,
    private val password: String? = null
) : SpreadsheetGridSession() {
    constructor(pfd: ParcelFileDescriptor, password: String? = null) : this(pfd, null, password)
    internal constructor(entries: Map<String, ByteArray>, password: String? = null) : this(null, entries, password)

    companion object {
        private const val MAX_ODS_ROWS = 50_000
        private const val MAX_ODS_COLS = 512
    }

    init {
        if (preloadedEntries != null) parseOdsEntries(preloadedEntries) else parseOds()
    }

    private fun findAttrBySuffix(parser: XmlPullParser, suffix: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i) ?: continue
            if (name.equals(suffix, ignoreCase = true) || name.endsWith(":$suffix", ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun odsLengthPt(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().lowercase(Locale.ROOT)
        val num = t.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
        return when {
            t.endsWith("pt") -> num
            t.endsWith("cm") -> num * 28.3465f
            t.endsWith("mm") -> num * 2.83465f
            t.endsWith("in") -> num * 72f
            t.endsWith("pc") -> num * 12f
            t.endsWith("px") -> num * 0.75f
            else -> null
        }
    }

    private class OdsStyleRaw(val family: String, val parent: String?, val dataStyleName: String? = null, val props: HashMap<String, String> = HashMap()) {
        val maps = ArrayList<Array<String?>>()
    }

    private fun odsColor(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val s = v.trim()
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        return when (hex.length) {
            6 -> hex.toIntOrNull(16)?.let { (0xFF shl 24) or it }
            8 -> hex.toLongOrNull(16)?.toInt()
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            else -> null
        }
    }

    private fun odsBorder(v: String?): SheetBorder? {
        if (v.isNullOrBlank()) return null
        val parts = v.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        if (parts.any { it == "none" || it == "hidden" }) return null
        var widthPt = 0.75f
        var color: Int? = null
        var kind = "solid"
        for (p in parts) {
            when {
                p.startsWith("#") -> color = odsColor(p)
                p in setOf("solid", "dotted", "dashed", "double", "dot-dash", "dot-dot-dash", "fine-dashed", "dash-dot", "dash-dot-dot", "groove", "ridge", "inset", "outset", "wave", "double-thin") -> kind = p
                p.firstOrNull()?.isDigit() == true || p.startsWith(".") -> odsLengthPt(p)?.let { widthPt = it }
                p == "thin" -> widthPt = 0.75f
                p == "medium" -> widthPt = 1.75f
                p == "thick" -> widthPt = 2.5f
            }
        }
        if (widthPt <= 0f) return null
        val style = when (kind) {
            "double", "double-thin" -> SheetBorder.DOUBLE
            "dotted" -> SheetBorder.DOTTED
            "dashed", "fine-dashed" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASHED else SheetBorder.DASHED
            "dot-dash", "dash-dot" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASH_DOT else SheetBorder.DASH_DOT
            "dot-dot-dash", "dash-dot-dot" -> if (widthPt >= 1.5f) SheetBorder.MEDIUM_DASH_DOT_DOT else SheetBorder.DASH_DOT_DOT
            else -> when {
                widthPt < 0.5f -> SheetBorder.HAIR
                widthPt < 1.4f -> SheetBorder.THIN
                widthPt < 2.4f -> SheetBorder.MEDIUM
                else -> SheetBorder.THICK
            }
        }
        return SheetBorder(style, color ?: Color.BLACK)
    }

    private fun odsDateSerial(v: String): Double? {
        val m = Regex("""^(-?\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?)?""").find(v.trim()) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        val days = cal.timeInMillis / 86_400_000L + 25569L
        val secs = (m.groupValues[4].toIntOrNull() ?: 0) * 3600 + (m.groupValues[5].toIntOrNull() ?: 0) * 60 + (m.groupValues[6].toIntOrNull() ?: 0)
        return days + secs / 86400.0
    }

    private fun odsDurationDays(v: String): Double? {
        val m = Regex("""^-?P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(v.trim()) ?: return null
        val d = (m.groupValues[1].toDoubleOrNull() ?: 0.0) + ((m.groupValues[2].toDoubleOrNull() ?: 0.0) * 3600 + (m.groupValues[3].toDoubleOrNull() ?: 0.0) * 60 + (m.groupValues[4].toDoubleOrNull() ?: 0.0)) / 86400.0
        return if (v.trim().startsWith("-")) -d else d
    }

    private fun odsConditionalNodes(content: ByteArray): Map<String, List<DmlNode>> {
        val out = HashMap<String, ArrayList<DmlNode>>()
        if (!String(content, StandardCharsets.ISO_8859_1).contains("conditional-format")) return out
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(content), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var table: String? = null
            var depth = 0
            val stack = ArrayList<DmlNode>()
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (local == "table" && stack.isEmpty()) { depth++; if (depth == 1) table = findAttrBySuffix(parser, "name") }
                        if (stack.isNotEmpty() || local == "conditional-format") {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfterLast(':')] = parser.getAttributeValue(i)
                            val node = DmlNode(local, attrs)
                            if (stack.isEmpty()) { table?.let { t -> out.getOrPut(t) { ArrayList() }.add(node) } } else stack.last().children.add(node)
                            stack.add(node)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        else if (local == "table") { depth--; if (depth == 0) table = null }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun odsFormula(expr: String): String {
        var e = expr.trim().removePrefix("of:").removePrefix("oooc:").removePrefix("msoxl:").removePrefix("=")
        e = Regex("""\[\$?(?:'((?:[^']|'')*)'|([^.\[\]':]*))\.(\$?[A-Za-z]{1,3}\$?\d+)(?::\$?(?:'(?:[^']|'')*'|[^.\[\]':]*)?\.(\$?[A-Za-z]{1,3}\$?\d+))?\]""").replace(e) { m ->
            val sheet = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val a = m.groupValues[3] + (if (m.groupValues[4].isNotEmpty()) ":" + m.groupValues[4] else "")
            if (sheet.isEmpty()) a else "'" + sheet.replace("'", "''") + "'!" + a
        }
        e = Regex("""\$?(?:'((?:[^']|'')*)'|([A-Za-z_][A-Za-z0-9_ ]*))\.(\$?[A-Za-z]{1,3}\$?\d+)""").replace(e) { m ->
            val sheet = m.groupValues[1].ifEmpty { m.groupValues[2] }
            "'" + sheet.replace("'", "''") + "'!" + m.groupValues[3]
        }
        e = e.replace(Regex("""\b(?:ORG\.OPENOFFICE|COM\.MICROSOFT)\.""", RegexOption.IGNORE_CASE), "")
        val sb = StringBuilder(e.length)
        var inStr = false
        var braceDepth = 0
        for (ch in e) {
            when {
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                inStr -> sb.append(ch)
                ch == '{' -> { braceDepth++; sb.append(ch) }
                ch == '}' -> { if (braceDepth > 0) braceDepth--; sb.append(ch) }
                ch == ';' && braceDepth == 0 -> sb.append(',')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun odsRanges(addr: String?): List<IntArray> {
        if (addr.isNullOrBlank()) return emptyList()
        val cleaned = addr.replace(Regex("""\$?(?:'(?:[^']|'')*'|[^\s.:']+)\."""), "").replace("$", "")
        return SheetConditionalFormats.parseSqref(cleaned)
    }

    private fun odsCfStyle(rawStyles: Map<String, OdsStyleRaw>, name: String): CfStyle? {
        val props = HashMap<String, String>()
        val chain = ArrayList<OdsStyleRaw>()
        var cur = rawStyles["table-cell/$name"]
        var guard = 0
        while (cur != null && guard++ < 32) {
            chain.add(cur)
            cur = cur.parent?.takeIf { it != "Default" }?.let { rawStyles["table-cell/$it"] }
        }
        if (chain.isEmpty()) return null
        for (st in chain.asReversed()) props.putAll(st.props)
        val all = odsBorder(props["border"])
        return CfStyle(
            fill = props["background-color"]?.let { odsColor(it) }?.let { SheetFill(it) },
            color = props["color"]?.let { odsColor(it) },
            bold = props["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 },
            italic = props["font-style"]?.let { it.equals("italic", true) || it.equals("oblique", true) },
            underline = props["text-underline-style"]?.let { if (it.equals("none", true)) 0 else 1 },
            strike = props["text-line-through-style"]?.let { !it.equals("none", true) },
            left = odsBorder(props["border-left"]) ?: all,
            right = odsBorder(props["border-right"]) ?: all,
            top = odsBorder(props["border-top"]) ?: all,
            bottom = odsBorder(props["border-bottom"]) ?: all
        )
    }

    private fun odsArgs(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                inStr -> sb.append(ch)
                ch == '(' -> { depth++; sb.append(ch) }
                ch == ')' -> { depth--; sb.append(ch) }
                depth == 0 && (ch == ',' || ch == ';') -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out
    }

    private fun odsConditionRule(cond: String, style: CfStyle?, priority: Int, ranges: List<IntArray>): CfRule? {
        val c = cond.trim().removePrefix("of:")
        fun inner(prefix: String): String = c.substring(prefix.length).trim().removePrefix("(").removeSuffix(")")
        return when {
            c.startsWith("cell-content-is-between") || c.startsWith("cell-content-is-not-between") -> {
                val args = odsArgs(inner(if (c.startsWith("cell-content-is-between")) "cell-content-is-between" else "cell-content-is-not-between")).map { odsFormula(it) }
                CfRule("cellIs", priority, operator = if (c.startsWith("cell-content-is-between")) "between" else "notBetween", formulas = args, style = style)
            }
            c.startsWith("cell-content()") -> {
                val rest = c.removePrefix("cell-content()").trim()
                val op = listOf("<=", ">=", "!=", "<>", "<", ">", "=").firstOrNull { rest.startsWith(it) } ?: return null
                val operator = when (op) { "<" -> "lessThan"; "<=" -> "lessThanOrEqual"; ">" -> "greaterThan"; ">=" -> "greaterThanOrEqual"; "=" -> "equal"; else -> "notEqual" }
                CfRule("cellIs", priority, operator = operator, formulas = listOf(odsFormula(rest.substring(op.length))), style = style)
            }
            c.startsWith("is-true-formula") || c.startsWith("formula-is") -> CfRule("expression", priority, formulas = listOf(odsFormula(inner(if (c.startsWith("formula-is")) "formula-is" else "is-true-formula"))), style = style)
            c.startsWith("begins-with") -> CfRule("beginsWith", priority, formulas = listOf(odsFormula(inner("begins-with"))), style = style)
            c.startsWith("ends-with") -> CfRule("endsWith", priority, formulas = listOf(odsFormula(inner("ends-with"))), style = style)
            c.startsWith("not-contains-text") -> CfRule("notContainsText", priority, formulas = listOf(odsFormula(inner("not-contains-text"))), style = style)
            c.startsWith("contains-text") -> CfRule("containsText", priority, formulas = listOf(odsFormula(inner("contains-text"))), style = style)
            c == "duplicate" -> CfRule("duplicateValues", priority, style = style)
            c == "unique" -> CfRule("uniqueValues", priority, style = style)
            c.startsWith("top-elements") -> CfRule("top10", priority, rank = inner("top-elements").toIntOrNull() ?: 10, style = style)
            c.startsWith("bottom-elements") -> CfRule("top10", priority, rank = inner("bottom-elements").toIntOrNull() ?: 10, bottom = true, style = style)
            c.startsWith("top-percent") -> CfRule("top10", priority, rank = inner("top-percent").toIntOrNull() ?: 10, percent = true, style = style)
            c.startsWith("bottom-percent") -> CfRule("top10", priority, rank = inner("bottom-percent").toIntOrNull() ?: 10, percent = true, bottom = true, style = style)
            c == "above-average" -> CfRule("aboveAverage", priority, style = style)
            c == "below-average" -> CfRule("aboveAverage", priority, aboveAverage = false, style = style)
            c == "above-equal-average" -> CfRule("aboveAverage", priority, equalAverage = true, style = style)
            c == "below-equal-average" -> CfRule("aboveAverage", priority, aboveAverage = false, equalAverage = true, style = style)
            c == "is-error" -> CfRule("containsErrors", priority, style = style)
            c == "is-no-error" -> CfRule("notContainsErrors", priority, style = style)
            else -> null
        }
    }

    private fun odsEntry(n: DmlNode): CfValueObject {
        val type = when (n.attr("type")) {
            "minimum" -> "min"; "maximum" -> "max"; "auto-minimum" -> "autoMin"; "auto-maximum" -> "autoMax"
            "percent" -> "percent"; "percentile" -> "percentile"; "formula" -> "formula"; else -> "num"
        }
        return CfValueObject(type, n.attr("value")?.let { odsFormula(it) })
    }

    private fun odsCalcextBlocks(nodes: List<DmlNode>, rawStyles: Map<String, OdsStyleRaw>, styleOf: (Map<String, OdsStyleRaw>, String) -> CfStyle?): List<CfBlock> {
        val out = ArrayList<CfBlock>()
        var priority = 0
        for (cf in nodes) {
            val ranges = odsRanges(cf.attr("target-range-address"))
            if (ranges.isEmpty()) continue
            val rules = ArrayList<CfRule>()
            for (ch in cf.children) {
                priority++
                when (ch.name) {
                    "condition" -> odsConditionRule(ch.attr("value") ?: continue, ch.attr("apply-style-name")?.let { styleOf(rawStyles, it) }, priority, ranges)?.let { rules.add(it) }
                    "date-is" -> rules.add(CfRule("timePeriod", priority, timePeriod = when (ch.attr("date")) {
                        "last-7-days" -> "last7Days"; "this-week" -> "thisWeek"; "last-week" -> "lastWeek"; "next-week" -> "nextWeek"
                        "this-month" -> "thisMonth"; "last-month" -> "lastMonth"; "next-month" -> "nextMonth"; else -> ch.attr("date")
                    }, style = ch.attr("style")?.let { styleOf(rawStyles, it) }))
                    "color-scale" -> {
                        val entries = ch.all("color-scale-entry")
                        rules.add(CfRule("colorScale", priority, cfvos = entries.map { odsEntry(it) }, colors = entries.mapNotNull { odsColor(it.attr("color")) }))
                    }
                    "data-bar" -> {
                        val entries = ch.all("formatting-entry")
                        rules.add(
                            CfRule(
                                "dataBar", priority,
                                cfvos = entries.map { odsEntry(it) },
                                colors = listOfNotNull(odsColor(ch.attr("positive-color"))),
                                barGradient = ch.attr("gradient") != "false",
                                barNegative = odsColor(ch.attr("negative-color")),
                                barAxisColor = odsColor(ch.attr("axis-color")),
                                barMinLength = (ch.attr("min-length")?.toFloatOrNull() ?: 0f) / 100f,
                                barMaxLength = (ch.attr("max-length")?.toFloatOrNull() ?: 100f) / 100f,
                                showValue = ch.attr("show-value") != "false"
                            )
                        )
                    }
                    "icon-set" -> {
                        rules.add(
                            CfRule(
                                "iconSet", priority,
                                cfvos = ch.all("formatting-entry").map { odsEntry(it) },
                                iconSet = ch.attr("icon-set-type") ?: "3TrafficLights1",
                                showValue = ch.attr("show-value") != "false"
                            )
                        )
                    }
                }
            }
            if (rules.isNotEmpty()) out.add(CfBlock(ranges, rules))
        }
        return out
    }

    private class OdsPending(
        val model: SheetModel,
        val grid: ArrayList<ArrayList<String>>,
        val formulas: Map<Long, String>,
        val raw: Map<Long, Any>,
        val flags: HashMap<Int, ByteArray>,
        val blocks: List<CfBlock>,
        val cellFormats: Map<Long, String>
    )

    private fun formatOdsRaw(v: Any, valueType: String?, numFmt: String?): String {
        return when (v) {
            is Boolean -> if (v) "TRUE" else "FALSE"
            is Double -> {
                if (!numFmt.isNullOrBlank()) {
                    val fmt = ExcelNumberFormat.format(v, 0, numFmt, false)
                    if (fmt.text.isNotEmpty()) return fmt.text
                }
                when (valueType) {
                    "percentage" -> ExcelNumberFormat.format(v, 0, "0.##%", false).text
                    "date" -> ExcelNumberFormat.format(v, 0, "yyyy-mm-dd", false).text
                    "time" -> ExcelNumberFormat.format(v, 0, "hh:mm:ss", false).text
                    "currency" -> ExcelNumberFormat.format(v, 0, "#,##0.00", false).text
                    else -> ExcelNumberFormat.general(v)
                }
            }
            else -> v.toString()
        }
    }

    private fun odsValue(t: OdsPending, row: Int, col: Int): Any? {
        t.raw[packCoord(row, col)]?.let { return it }
        val text = t.grid.getOrNull(row)?.getOrNull(col)
        if (text.isNullOrEmpty()) return null
        val fl = t.model.flags(row, col)
        if ((fl and SheetCellFlags.NUMBER) != 0) me.hlebkukleuski.fullfilestudio.core.document.ExcelFormula.parseNumber(text)?.let { return it }
        if ((fl and SheetCellFlags.BOOL) != 0) return text.equals("TRUE", true)
        return FormulaError.parse(text) ?: text
    }

    private fun finishOds(tables: List<OdsPending>) {
        val byName = tables.associateBy { it.model.name.lowercase(Locale.ROOT) }
        if (tables.any { it.formulas.isNotEmpty() }) {
            val wb = FormulaWorkbook(false)
            for (t in tables) wb.add(FormulaWorkbook.Sheet(t.model.name, t.grid.size, maxOf(1, t.model.colCount), t.formulas) { r, c -> odsValue(t, r, c) })
            for (t in tables) {
                for ((key, v) in wb.evaluateAll(t.model.name)) {
                    val r = (key ushr 32).toInt()
                    val c = (key and 0xFFFFFFFFL).toInt()
                    val row = t.grid.getOrNull(r) ?: continue
                    while (row.size <= c) row.add("")
                    when (v) {
                        is Double -> {
                            val nf = t.cellFormats[key]
                            row[c] = if (!nf.isNullOrBlank()) ExcelNumberFormat.format(v, 0, nf, false).text else ExcelNumberFormat.general(v)
                            rowFlags(t.flags, r, c, SheetCellFlags.NUMBER or SheetCellFlags.GENERAL)
                        }
                        is Boolean -> { row[c] = if (v) "TRUE" else "FALSE"; rowFlags(t.flags, r, c, SheetCellFlags.BOOL) }
                        is FormulaError -> { row[c] = v.code; rowFlags(t.flags, r, c, SheetCellFlags.ERROR) }
                        null -> row[c] = ""
                        else -> row[c] = v.toString()
                    }
                    if (v is Double && t.raw is HashMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val rawMap = t.raw as HashMap<Long, Any>
                        rawMap[key] = v
                    }
                }
            }
        }
        for (t in tables) {
            if (t.blocks.isEmpty()) continue
            try {
                val cells = object : CfCells {
                    override fun value(row: Int, col: Int, sheet: String?): Any? {
                        val target = if (sheet == null) t else byName[sheet.lowercase(Locale.ROOT)] ?: return null
                        return odsValue(target, row, col)
                    }
                }
                SheetConditionalFormats.apply(t.blocks, t.model.rowCount, t.model.colCount, cells, { r, c -> t.model.baseStyle(r, c) }, false, t.model.conditional)
            } catch (_: Exception) {}
        }
    }

    private fun parseOds() {
        val fd = pfd ?: return
        try {
            val entries = OfficeZipContainerLoader.readZipEntries(
                pfd = fd,
                maxTotalBytes = 96 * 1024 * 1024L
            ) { normName ->
                normName.equals("content.xml", ignoreCase = true) ||
                        normName.equals("styles.xml", ignoreCase = true) ||
                        normName.equals("settings.xml", ignoreCase = true) ||
                        normName.equals("META-INF/manifest.xml", ignoreCase = true)
            }
            parseOdsEntries(entries)
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер ODS: ${e.localizedMessage}"
        }
    }

    private fun parseOdsEntries(rawEntries: Map<String, ByteArray>) {
        try {
            val entries = if (OdpEngine.isEncryptedOdf(rawEntries)) {
                if (password.isNullOrEmpty()) {
                    lastErrorMsg = "PASSWORD_REQUIRED: ODS spreadsheet is encrypted or password protected."
                    return
                }
                me.hlebkukleuski.fullfilestudio.core.document.OfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                    lastErrorMsg = "PASSWORD_REQUIRED: Incorrect password for ODS spreadsheet."
                    return
                }
            } else {
                rawEntries
            }

            val contentBytes = entries["content.xml"] ?: entries.entries.firstOrNull { it.key.equals("content.xml", ignoreCase = true) }?.value
            if (contentBytes == null) {
                lastErrorMsg = "В контейнере ODS отсутствует обязательный файл content.xml."
                return
            }
            val stylesBytes = entries["styles.xml"] ?: entries.entries.firstOrNull { it.key.equals("styles.xml", ignoreCase = true) }?.value
            val settingsBytes = entries["settings.xml"] ?: entries.entries.firstOrNull { it.key.equals("settings.xml", ignoreCase = true) }?.value

            val rawStyles = HashMap<String, OdsStyleRaw>()
            val fontFaces = HashMap<String, String>()
            val dataFormats = HashMap<String, String>()
            for (xmlSource in listOfNotNull(stylesBytes, contentBytes)) {
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(xmlSource), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var active: OdsStyleRaw? = null
                    var activeDataStyleName: String? = null
                    var activeDataSb: StringBuilder? = null
                    var activeDataTextTag: String? = null
                    var depthInBody = false
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val local = sp.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                        when (ev) {
                            XmlPullParser.START_TAG -> {
                                when (local) {
                                    "body" -> depthInBody = true
                                    "font-face" -> {
                                        val n = findAttrBySuffix(sp, "name")
                                        val fam = findAttrBySuffix(sp, "font-family")?.trim()?.trim('\'', '"')
                                        if (n != null && fam != null) fontFaces[n] = fam
                                    }
                                    "number-style", "currency-style", "percentage-style", "date-style", "time-style" -> {
                                        activeDataStyleName = findAttrBySuffix(sp, "name")
                                        activeDataSb = StringBuilder()
                                    }
                                    "number" -> activeDataSb?.let { sb ->
                                        val grp = findAttrBySuffix(sp, "grouping").equals("true", true)
                                        val minInt = (findAttrBySuffix(sp, "min-integer-digits")?.toIntOrNull() ?: 1).coerceIn(0, 12)
                                        val dec = (findAttrBySuffix(sp, "decimal-places")?.toIntOrNull() ?: 0).coerceIn(0, 10)
                                        if (grp) sb.append("#,##").append("0".repeat(maxOf(1, minInt))) else sb.append("0".repeat(maxOf(1, minInt)))
                                        if (dec > 0) sb.append('.').append("0".repeat(dec))
                                    }
                                    "year" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "yyyy" else "yy")
                                    "month" -> activeDataSb?.append(if (findAttrBySuffix(sp, "textual") == "true") (if (findAttrBySuffix(sp, "style") == "long") "mmmm" else "mmm") else (if (findAttrBySuffix(sp, "style") == "long") "mm" else "m"))
                                    "day" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "dd" else "d")
                                    "hours" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "hh" else "h")
                                    "minutes" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "mm" else "m")
                                    "seconds" -> activeDataSb?.append(if (findAttrBySuffix(sp, "style") == "long") "ss" else "s")
                                    "am-pm" -> activeDataSb?.append("AM/PM")
                                    "currency-symbol", "text" -> if (activeDataSb != null) activeDataTextTag = local
                                    "style" -> {
                                        val n = findAttrBySuffix(sp, "name")
                                        val family = findAttrBySuffix(sp, "family") ?: ""
                                        active = if (n != null && rawStyles.size < 20000) OdsStyleRaw(family, findAttrBySuffix(sp, "parent-style-name"), findAttrBySuffix(sp, "data-style-name")).also { rawStyles["$family/$n"] = it } else null
                                    }
                                    "default-style" -> {
                                        val family = findAttrBySuffix(sp, "family") ?: ""
                                        active = OdsStyleRaw(family, null).also { rawStyles["$family/__default__"] = it }
                                    }
                                    "map" -> active?.let { st ->
                                        if (st.maps.size < 64) st.maps.add(arrayOf(findAttrBySuffix(sp, "condition"), findAttrBySuffix(sp, "apply-style-name"), findAttrBySuffix(sp, "base-cell-address")))
                                    }
                                    "table-cell-properties", "text-properties", "paragraph-properties", "table-column-properties", "table-row-properties", "table-properties" -> {
                                        val st = active
                                        if (st != null) {
                                            for (k in 0 until sp.attributeCount) {
                                                val an = sp.getAttributeName(k)?.substringAfterLast(':') ?: continue
                                                if (an.endsWith("-asian") || an.endsWith("-complex")) continue
                                                st.props[an] = sp.getAttributeValue(k)
                                            }
                                        }
                                    }
                                }
                                if (depthInBody && local == "table") break
                            }
                            XmlPullParser.TEXT -> {
                                val dt = activeDataTextTag
                                val sb = activeDataSb
                                if (dt != null && sb != null) {
                                    val t = sp.text ?: ""
                                    if (t == "%" || t == "/" || t == "-" || t == ":" || t == " " || t == "$" || t == "€" || t == "£") sb.append(t)
                                    else if (t.isNotEmpty()) sb.append('"').append(t.replace("\"", "")).append('"')
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (local == "style" || local == "default-style") active = null
                                else if (local == "currency-symbol" || local == "text") activeDataTextTag = null
                                else if (local in setOf("number-style", "currency-style", "percentage-style", "date-style", "time-style")) {
                                    val dn = activeDataStyleName
                                    val built = activeDataSb?.toString()
                                    if (dn != null && !built.isNullOrBlank()) dataFormats[dn] = built
                                    activeDataStyleName = null
                                    activeDataSb = null
                                    activeDataTextTag = null
                                }
                            }
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            fun resolveDataFormat(styleName: String?): String? {
                var cur = styleName?.let { rawStyles["table-cell/$it"] }
                var guard = 0
                while (cur != null && guard++ < 32) {
                    cur.dataStyleName?.let { dn -> dataFormats[dn]?.let { return it } }
                    cur = cur.parent?.let { rawStyles["table-cell/$it"] }
                }
                return null
            }

            fun mergedProps(family: String, name: String?): Map<String, String> {
                val chain = ArrayList<OdsStyleRaw>()
                var cur = name?.let { rawStyles["$family/$it"] }
                var guard = 0
                while (cur != null && guard++ < 32) {
                    chain.add(cur)
                    cur = cur.parent?.let { rawStyles["$family/$it"] }
                }
                val out = HashMap<String, String>()
                rawStyles["$family/__default__"]?.let { out.putAll(it.props) }
                for (st in chain.asReversed()) out.putAll(st.props)
                return out
            }

            val defaultCellProps = mergedProps("table-cell", "Default")
            val baseFontName = (defaultCellProps["font-name"]?.let { fontFaces[it] ?: it } ?: defaultCellProps["font-family"])?.trim('\'', '"') ?: "Liberation Sans"
            val baseFontSize = odsLengthPt(defaultCellProps["font-size"]) ?: 10f

            val cellStyleCache = HashMap<String, SheetCellStyle?>()
            fun cellStyle(name: String?): SheetCellStyle? {
                val key = name ?: "Default"
                return cellStyleCache.getOrPut(key) {
                    val pr = mergedProps("table-cell", key)
                    if (pr.isEmpty()) return@getOrPut null
                    val all = odsBorder(pr["border"])
                    val bold = pr["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 } == true
                    val rot = pr["rotation-angle"]?.removeSuffix("deg")?.trim()?.toFloatOrNull()?.toInt()?.let { ((it % 360) + 360) % 360 } ?: 0
                    val alignSourceFix = pr["text-align-source"]?.equals("fix", true) != false || pr.containsKey("text-align")
                    val st = SheetCellStyle(
                        fill = pr["background-color"]?.let { odsColor(it) },
                        textColor = pr["color"]?.let { odsColor(it) },
                        bold = bold,
                        italic = pr["font-style"]?.let { it.equals("italic", true) || it.equals("oblique", true) } == true,
                        underline = pr["text-underline-style"]?.let { if (it.equals("none", true)) 0 else if (pr["text-underline-type"].equals("double", true)) 2 else 1 } ?: 0,
                        strike = pr["text-line-through-style"]?.let { !it.equals("none", true) } == true,
                        fontSize = odsLengthPt(pr["font-size"]) ?: 0f,
                        fontName = (pr["font-name"]?.let { fontFaces[it] ?: it } ?: pr["font-family"])?.trim('\'', '"'),
                        script = pr["text-position"]?.let { if (it.startsWith("super")) 1 else if (it.startsWith("sub")) 2 else if (it.startsWith("-")) 2 else if (it.firstOrNull()?.isDigit() == true && !it.startsWith("0")) 1 else 0 } ?: 0,
                        hAlign = if (!alignSourceFix) SheetCellStyle.H_GENERAL else when (pr["text-align"]?.lowercase(Locale.ROOT)) {
                            "start", "left" -> SheetCellStyle.H_LEFT
                            "center" -> SheetCellStyle.H_CENTER
                            "end", "right" -> SheetCellStyle.H_RIGHT
                            "justify" -> SheetCellStyle.H_JUSTIFY
                            else -> SheetCellStyle.H_GENERAL
                        },
                        vAlign = when (pr["vertical-align"]?.lowercase(Locale.ROOT)) {
                            "top" -> SheetCellStyle.V_TOP
                            "middle" -> SheetCellStyle.V_CENTER
                            else -> SheetCellStyle.V_BOTTOM
                        },
                        wrap = pr["wrap-option"].equals("wrap", true),
                        rotation = when {
                            rot == 0 -> 0
                            rot <= 90 -> rot
                            rot >= 270 -> 90 + (360 - rot)
                            rot < 180 -> 90
                            else -> 180
                        },
                        shrink = pr["shrink-to-fit"].equals("true", true),
                        left = odsBorder(pr["border-left"]) ?: all,
                        right = odsBorder(pr["border-right"]) ?: all,
                        top = odsBorder(pr["border-top"]) ?: all,
                        bottom = odsBorder(pr["border-bottom"]) ?: all,
                        diagonalDown = odsBorder(pr["diagonal-tl-br"]),
                        diagonalUp = odsBorder(pr["diagonal-bl-tr"])
                    )
                    val plain = SheetCellStyle(fontSize = baseFontSize, fontName = baseFontName)
                    val normalized = st.copy(
                        fontSize = if (st.fontSize == 0f) baseFontSize else st.fontSize,
                        fontName = st.fontName ?: baseFontName
                    )
                    if (normalized == plain) null else normalized
                }
            }

            var showGrid = true
            settingsBytes?.let { bytes ->
                try {
                    val sp = EbookTextSession.createSafeXmlParser()
                    sp.setInput(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
                    var ev = sp.eventType
                    var inShowGrid = false
                    loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> inShowGrid = sp.name?.endsWith("config-item") == true && findAttrBySuffix(sp, "name") == "ShowGrid"
                            XmlPullParser.TEXT -> if (inShowGrid) { showGrid = !sp.text.trim().equals("false", true); break@loop }
                            XmlPullParser.END_TAG -> inShowGrid = false
                        }
                        ev = sp.next()
                    }
                } catch (_: Exception) {}
            }

            val calcext = odsConditionalNodes(contentBytes)
            val pendingTables = ArrayList<OdsPending>()
            val defaultRowPx = Math.round(baseFontSize * 1.28f * 4f) / 4f * PX_PER_PT
            val defaultColPx = 2.258f * 28.3465f * PX_PER_PT

            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType

            var currentTableName = "Sheet 1"
            var currentGrid = ArrayList<ArrayList<String>>()
            var currentStyles = HashMap<Long, SheetCellStyle>()
            var currentFormulas = HashMap<Long, String>()
            var currentCellFormats = HashMap<Long, String>()
            var currentColWidths = ArrayList<Float>()
            var currentColStyles = HashMap<Int, SheetCellStyle>()
            var currentRowHeights = HashMap<Int, Float>()
            var currentFlags = HashMap<Int, ByteArray>()
            var currentMerges = ArrayList<MergedCellRegion>()
            var currentRowStyleHeight: Float? = null
            var currentRow: ArrayList<String>? = null
            var currentRowFlags = HashMap<Int, Int>()
            var currentRaw = HashMap<Long, Any>()
            var currentRowRaw = HashMap<Int, Any>()
            var currentRowFormulas = HashMap<Int, String>()
            var currentRowFormats = HashMap<Int, String>()
            var currentMapCells = HashMap<String, ArrayList<IntArray>>()
            var currentRowMapCells = HashMap<Int, String>()
            var currentCellRaw: Any? = null
            var currentCellValueType: String? = null
            var currentCellStyleName: String? = null
            var currentCellExplicitStyle = false
            var currentRowCellStyles = HashMap<Int, SheetCellStyle>()
            var currentRowRepeat = 1
            var currentCellSb: StringBuilder? = null
            var currentCellFormula: String? = null
            var currentCellKind = 0
            var currentColRepeat = 1
            var currentColSpan = 1
            var currentRowSpan = 1
            var currentCellStyle: SheetCellStyle? = null
            var paragraphs = 0
            var annotationDepth = 0
            var inTable = false
            var inCell = false
            var tableDepth = 0

            while (ev != XmlPullParser.END_DOCUMENT && sheets.size < 64) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (annotationDepth > 0) {
                            annotationDepth++
                        } else when (local) {
                            "annotation" -> if (inCell) annotationDepth = 1
                            "table" -> {
                                tableDepth++
                                if (tableDepth == 1) {
                                    inTable = true
                                    currentTableName = findAttrBySuffix(parser, "name") ?: "Sheet ${sheets.size + 1}"
                                    currentGrid = ArrayList()
                                    currentStyles = HashMap()
                                    currentFormulas = HashMap()
                                    currentCellFormats = HashMap()
                                    currentColWidths = ArrayList()
                                    currentColStyles = HashMap()
                                    currentRowHeights = HashMap()
                                    currentFlags = HashMap()
                                    currentMerges = ArrayList()
                                    currentRaw = HashMap()
                                    currentMapCells = HashMap()
                                }
                            }
                            "table-column" -> if (inTable && tableDepth == 1) {
                                val colStyleName = findAttrBySuffix(parser, "style-name")
                                val repeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                val colProps = mergedProps("table-column", colStyleName)
                                val hidden = findAttrBySuffix(parser, "visibility")?.let { it == "collapse" || it == "filter" } == true
                                val wPx = if (hidden) 0f else odsLengthPt(colProps["column-width"])?.let { it * PX_PER_PT } ?: defaultColPx
                                val defCell = findAttrBySuffix(parser, "default-cell-style-name")?.let { cellStyle(it) }
                                repeat(repeat) {
                                    if (currentColWidths.size < MAX_ODS_COLS) {
                                        if (defCell != null && (defCell.fill != null || defCell.hasBorders)) currentColStyles[currentColWidths.size] = defCell
                                        currentColWidths.add(wPx)
                                    }
                                }
                            }
                            "table-row" -> if (inTable && tableDepth == 1 && currentGrid.size < MAX_ODS_ROWS) {
                                currentRow = ArrayList()
                                currentRowFlags = HashMap()
                                currentRowRaw = HashMap()
                                currentRowFormulas = HashMap()
                                currentRowFormats = HashMap()
                                currentRowMapCells = HashMap()
                                currentRowCellStyles = HashMap()
                                currentRowRepeat = (findAttrBySuffix(parser, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_ROWS)
                                val rowProps = mergedProps("table-row", findAttrBySuffix(parser, "style-name"))
                                val hidden = findAttrBySuffix(parser, "visibility")?.let { it == "collapse" || it == "filter" } == true
                                currentRowStyleHeight = if (hidden) 0f else odsLengthPt(rowProps["row-height"])?.let { it * PX_PER_PT }
                            }
                            "table-cell", "covered-table-cell" -> if (currentRow != null && tableDepth == 1) {
                                inCell = true
                                paragraphs = 0
                                currentCellSb = StringBuilder()
                                currentColRepeat = (findAttrBySuffix(parser, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                currentColSpan = (findAttrBySuffix(parser, "number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_COLS)
                                currentRowSpan = (findAttrBySuffix(parser, "number-rows-spanned")?.toIntOrNull() ?: 1).coerceIn(1, MAX_ODS_ROWS)
                                val colIdx = currentRow.size
                                val explicit = findAttrBySuffix(parser, "style-name")
                                currentCellExplicitStyle = explicit != null
                                currentCellStyle = if (explicit != null) cellStyle(explicit) else currentColStyles[colIdx]
                                currentCellStyleName = explicit
                                val vType = findAttrBySuffix(parser, "value-type")
                                currentCellValueType = vType
                                currentCellRaw = when (vType) {
                                    "float", "percentage", "currency" -> findAttrBySuffix(parser, "value")?.toDoubleOrNull()
                                    "date" -> findAttrBySuffix(parser, "date-value")?.let { odsDateSerial(it) }
                                    "time" -> findAttrBySuffix(parser, "time-value")?.let { odsDurationDays(it) }
                                    "boolean" -> findAttrBySuffix(parser, "boolean-value")?.let { it.equals("true", true) }
                                    else -> null
                                }
                                currentCellFormula = findAttrBySuffix(parser, "formula")
                                currentCellKind = when (vType) {
                                    "float", "percentage", "currency", "date", "time" -> SheetCellFlags.NUMBER
                                    "boolean" -> SheetCellFlags.BOOL
                                    else -> 0
                                }
                            }
                            "p", "h" -> if (inCell) {
                                val sb = currentCellSb
                                if (sb != null && paragraphs > 0) sb.append('\n')
                                paragraphs++
                            }
                            "s" -> if (inCell) {
                                val n = (findAttrBySuffix(parser, "c")?.toIntOrNull() ?: 1).coerceIn(1, 256)
                                currentCellSb?.let { sb -> if (sb.length < 32768) repeat(n) { sb.append(' ') } }
                            }
                            "tab" -> if (inCell) currentCellSb?.append('\t')
                            "line-break" -> if (inCell) currentCellSb?.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCell && annotationDepth == 0 && currentCellSb != null && currentCellSb.length < 32768) {
                            currentCellSb.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (annotationDepth > 0) {
                            annotationDepth--
                        } else when (local) {
                            "table-cell", "covered-table-cell" -> {
                                val row = currentRow
                                if (inCell && row != null) {
                                    val dataFmt = resolveDataFormat(currentCellStyleName)
                                    var txt = currentCellSb?.toString() ?: ""
                                    if (txt.isEmpty() && currentCellRaw != null && currentCellFormula.isNullOrBlank()) {
                                        txt = formatOdsRaw(currentCellRaw, currentCellValueType, dataFmt)
                                    }
                                    val styleObj = currentCellStyle
                                    val visual = currentCellExplicitStyle && styleObj != null && (styleObj.fill != null || styleObj.hasBorders)
                                    val targetRowIdx = currentGrid.size

                                    if ((currentColSpan > 1 || currentRowSpan > 1) && currentMerges.size < 4096) {
                                        val c0 = row.size
                                        val c1 = (c0 + currentColSpan - 1).coerceAtMost(MAX_ODS_COLS - 1)
                                        val r0 = targetRowIdx
                                        val r1 = (r0 + currentRowSpan - 1).coerceAtMost(MAX_ODS_ROWS - 1)
                                        if (c1 > c0 || r1 > r0) {
                                            currentMerges.add(MergedCellRegion(r0, r1, c0, c1))
                                        }
                                    }

                                    val effRepeat = if (txt.isEmpty() && currentCellFormula.isNullOrBlank() && !visual && currentColRepeat > 64) {
                                        minOf(currentColRepeat, MAX_ODS_COLS - row.size)
                                    } else {
                                        currentColRepeat
                                    }
                                    repeat(effRepeat) {
                                        if (row.size < MAX_ODS_COLS) {
                                            val colIdx = row.size
                                            row.add(txt)
                                            if (styleObj != null && (txt.isNotEmpty() || visual)) currentRowCellStyles[colIdx] = styleObj
                                            if (currentCellKind != 0 && txt.isNotEmpty()) currentRowFlags[colIdx] = currentCellKind
                                            currentCellRaw?.let { currentRowRaw[colIdx] = it }
                                            val sn = currentCellStyleName
                                            if (sn != null && rawStyles["table-cell/$sn"]?.maps?.isNotEmpty() == true) currentRowMapCells[colIdx] = sn
                                            if (dataFmt != null) currentRowFormats[colIdx] = dataFmt
                                            if (!currentCellFormula.isNullOrBlank() && (txt.isEmpty() || txt.startsWith("#") || txt.equals("Err:", true))) {
                                                currentRowFormulas[colIdx] = currentCellFormula
                                            }
                                        }
                                    }
                                }
                                inCell = false
                                currentCellSb = null
                                currentCellFormula = null
                            }
                            "table-row" -> {
                                val row = currentRow
                                if (row != null) {
                                    while (row.isNotEmpty() && row.last().isEmpty()) {
                                        val colIdx = row.lastIndex
                                        if (currentRowCellStyles.containsKey(colIdx) || currentRowFormulas.containsKey(colIdx)) break
                                        row.removeAt(row.lastIndex)
                                    }
                                    val rep = currentRowRepeat.coerceAtMost(MAX_ODS_ROWS - currentGrid.size)
                                    for (k in 0 until rep) {
                                        val rIdx = currentGrid.size
                                        currentGrid.add(if (k == 0) row else ArrayList(row))
                                        currentRowStyleHeight?.let { currentRowHeights[rIdx] = it }
                                        if (currentStyles.size < 250_000) for ((c, st) in currentRowCellStyles) currentStyles[packCoord(rIdx, c)] = st
                                        for ((c, f) in currentRowFlags) rowFlags(currentFlags, rIdx, c, f)
                                        if (currentRaw.size < 1_000_000) for ((c, v) in currentRowRaw) currentRaw[packCoord(rIdx, c)] = v
                                        if (currentFormulas.size < 100_000) for ((c, fm) in currentRowFormulas) currentFormulas[packCoord(rIdx, c)] = fm
                                        if (currentCellFormats.size < 250_000) for ((c, nf) in currentRowFormats) currentCellFormats[packCoord(rIdx, c)] = nf
                                        for ((c, sn) in currentRowMapCells) currentMapCells.getOrPut(sn) { ArrayList() }.let { if (it.size < 100_000) it.add(intArrayOf(rIdx, rIdx, c, c)) }
                                        if (row.isEmpty() && currentRowCellStyles.isEmpty() && currentRowFormulas.isEmpty() && k > 0 && rep > 64) {
                                            val remaining = rep - k - 1
                                            for (m in 0 until remaining) {
                                                val rr = currentGrid.size
                                                currentGrid.add(ArrayList())
                                                currentRowStyleHeight?.let { currentRowHeights[rr] = it }
                                            }
                                            break
                                        }
                                    }
                                }
                                currentRow = null
                            }
                            "table" -> {
                                tableDepth--
                                if (tableDepth == 0) {
                                    while (currentGrid.isNotEmpty() && currentGrid.last().isEmpty()) {
                                        currentGrid.removeAt(currentGrid.lastIndex)
                                    }
                                    if (currentGrid.isNotEmpty()) {
                                        val maxDataCols = currentGrid.maxOfOrNull { it.size } ?: 0
                                        val widthsArr = if (maxDataCols > 0) {
                                            FloatArray(maxDataCols) { c -> currentColWidths.getOrElse(c) { defaultColPx } }
                                        } else null
                                        val odsModel =
                                            SheetModel(
                                                name = currentTableName,
                                                rows = currentGrid,
                                                styles = currentStyles,
                                                colWidthsPx = widthsArr,
                                                mergedRegions = currentMerges,
                                                rowHeightsPx = FloatArray(currentGrid.size) { r -> currentRowHeights[r] ?: defaultRowPx },
                                                flags = currentFlags,
                                                colStyles = currentColStyles,
                                                showGridLines = showGrid,
                                                defaultFontName = baseFontName,
                                                defaultFontSize = baseFontSize,
                                                defaultRowHeightPx = defaultRowPx,
                                                defaultColWidthPx = defaultColPx
                                            )
                                        sheets.add(odsModel)
                                        val blocks = ArrayList<CfBlock>()
                                        try {
                                            calcext[currentTableName]?.let { blocks.addAll(odsCalcextBlocks(it, rawStyles, ::odsCfStyle)) }
                                            if (blocks.isEmpty()) {
                                                for ((sn, cellsList) in currentMapCells) {
                                                    val maps = rawStyles["table-cell/$sn"]?.maps ?: continue
                                                    val rules = maps.mapIndexedNotNull { i, m -> odsConditionRule(m[0] ?: return@mapIndexedNotNull null, m[1]?.let { odsCfStyle(rawStyles, it) }, i, emptyList()) }
                                                    if (rules.isNotEmpty()) blocks.add(CfBlock(cellsList, rules))
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        pendingTables.add(OdsPending(odsModel, currentGrid, currentFormulas.mapValues { odsFormula(it.value) }, currentRaw, currentFlags, blocks, currentCellFormats))
                                    }
                                    inTable = false
                                }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            finishOds(pendingTables)
            if (sheets.isEmpty()) {
                lastErrorMsg = "Таблица ODS пуста или не содержит читаемых ячеек."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер ODS: ${e.localizedMessage}"
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd?.close() } catch (_: Exception) {}
    }
}
