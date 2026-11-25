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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.document.BiffCf
import me.hlebkukleuski.fullfilestudio.core.document.CfBlock
import me.hlebkukleuski.fullfilestudio.core.document.CfCells
import me.hlebkukleuski.fullfilestudio.core.document.CfRule
import me.hlebkukleuski.fullfilestudio.core.document.CfStyle
import me.hlebkukleuski.fullfilestudio.core.document.CfValueObject
import me.hlebkukleuski.fullfilestudio.core.document.ChartCellResolver
import me.hlebkukleuski.fullfilestudio.core.document.DmlCellAnchor
import me.hlebkukleuski.fullfilestudio.core.document.DmlDrawings
import me.hlebkukleuski.fullfilestudio.core.document.DmlNode
import me.hlebkukleuski.fullfilestudio.core.document.DmlPackage
import me.hlebkukleuski.fullfilestudio.core.document.DmlPresentation
import me.hlebkukleuski.fullfilestudio.core.document.DmlSlideRenderer
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
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import org.xmlpull.v1.XmlPullParser

class OoxmlSpreadsheetSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : SpreadsheetGridSession() {
    companion object {
        private const val MAX_SHEET_ROWS = 10_000
        private const val MAX_SHEET_COLS = 256

        private val INDEXED_EXCEL_COLORS = mapOf(
            0 to Color.rgb(0, 0, 0),
            1 to Color.rgb(255, 255, 255),
            2 to Color.rgb(255, 0, 0),
            3 to Color.rgb(0, 255, 0),
            4 to Color.rgb(0, 0, 255),
            5 to Color.rgb(255, 255, 0),
            6 to Color.rgb(255, 0, 255),
            7 to Color.rgb(0, 255, 255),
            8 to Color.rgb(0, 0, 0),
            9 to Color.rgb(255, 255, 255),
            10 to Color.rgb(255, 0, 0),
            11 to Color.rgb(0, 255, 0),
            12 to Color.rgb(0, 0, 255),
            13 to Color.rgb(255, 255, 0),
            14 to Color.rgb(255, 0, 255),
            15 to Color.rgb(0, 255, 255),
            16 to Color.rgb(128, 0, 0),
            17 to Color.rgb(0, 128, 0),
            18 to Color.rgb(0, 0, 128),
            19 to Color.rgb(128, 128, 0),
            20 to Color.rgb(128, 0, 128),
            21 to Color.rgb(0, 128, 128),
            22 to Color.rgb(192, 192, 192),
            23 to Color.rgb(128, 128, 128),
            24 to Color.rgb(153, 153, 255),
            25 to Color.rgb(153, 51, 102),
            26 to Color.rgb(255, 255, 204),
            27 to Color.rgb(204, 255, 255),
            28 to Color.rgb(102, 0, 102),
            29 to Color.rgb(255, 128, 128),
            30 to Color.rgb(0, 102, 204),
            31 to Color.rgb(204, 204, 255),
            40 to Color.rgb(0, 204, 255),
            41 to Color.rgb(204, 255, 255),
            42 to Color.rgb(204, 255, 204),
            43 to Color.rgb(255, 255, 153),
            44 to Color.rgb(153, 204, 255),
            45 to Color.rgb(255, 153, 204),
            46 to Color.rgb(204, 153, 255),
            47 to Color.rgb(255, 204, 153),
            48 to Color.rgb(51, 102, 255),
            49 to Color.rgb(51, 204, 204),
            50 to Color.rgb(153, 204, 0),
            51 to Color.rgb(255, 204, 0),
            52 to Color.rgb(255, 153, 0),
            53 to Color.rgb(255, 102, 0),
            54 to Color.rgb(102, 102, 153),
            55 to Color.rgb(150, 150, 150),
            56 to Color.rgb(0, 51, 102),
            57 to Color.rgb(51, 153, 102),
            58 to Color.rgb(0, 51, 0),
            59 to Color.rgb(51, 51, 0),
            60 to Color.rgb(153, 51, 0),
            61 to Color.rgb(153, 51, 102),
            62 to Color.rgb(51, 51, 153),
            63 to Color.rgb(51, 51, 51)
        )
    }

    private data class XfStyleSpec(
        val numFmtId: Int,
        val formatCode: String?,
        val style: SheetCellStyle?
    )

    private val xfStyles = ArrayList<XfStyleSpec>()
    private val dxfStyles = ArrayList<CfStyle>()
    private var cfNames: Map<String, String> = emptyMap()
    private var defaultFontName: String? = null
    private var defaultFontSize = 11f
    private val indexedPalette = HashMap<Int, Int>(INDEXED_EXCEL_COLORS)
    private val themePalette = intArrayOf(
        Color.rgb(255, 255, 255),
        Color.rgb(0, 0, 0),
        Color.rgb(238, 236, 225),
        Color.rgb(31, 73, 125),
        Color.rgb(79, 129, 189),
        Color.rgb(192, 80, 77),
        Color.rgb(155, 187, 89),
        Color.rgb(128, 100, 162),
        Color.rgb(75, 172, 198),
        Color.rgb(247, 150, 70)
    )
    private var use1904DateSystem = false

    init {
        parseXlsx()
    }

    private fun parseExcelThemeXml(themeBytes: ByteArray) {
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(themeBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inClrScheme = false
            var slotIdx = -1
            val slotOrder = mapOf(
                "lt1" to 0, "dk1" to 1, "lt2" to 2, "dk2" to 3,
                "accent1" to 4, "accent2" to 5, "accent3" to 6,
                "accent4" to 7, "accent5" to 8, "accent6" to 9
            )
            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (local == "clrscheme") inClrScheme = true
                        else if (inClrScheme && local in slotOrder) slotIdx = slotOrder[local] ?: -1
                        else if (inClrScheme && slotIdx in themePalette.indices) {
                            val hex = if (local == "srgbclr") {
                                parser.getAttributeValue(null, "val")
                            } else if (local == "sysclr") {
                                parser.getAttributeValue(null, "lastClr") ?: parser.getAttributeValue(null, "val")
                            } else null
                            if (!hex.isNullOrBlank() && hex.length == 6) {
                                try { themePalette[slotIdx] = Color.parseColor("#$hex") } catch (_: Exception) {}
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (local == "clrscheme") inClrScheme = false
                        else if (local in slotOrder) slotIdx = -1
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
    }

    private fun nodeColor(n: DmlNode?): Int? {
        if (n == null || n.bool("auto") == true) return null
        var base: Int? = null
        val hex = n.attr("rgb")?.trim()
        if (hex != null && (hex.length == 8 || hex.length == 6)) base = try { Color.parseColor("#" + hex.takeLast(6)) } catch (_: Exception) { null }
        if (base == null) n.int("theme")?.let { if (it in themePalette.indices) base = themePalette[it] }
        if (base == null) n.int("indexed")?.let { base = indexedPalette[it] }
        val b = base ?: return null
        return DrawingMlColorUtils.applyExcelTint(b, n.attr("tint")?.toDoubleOrNull() ?: 0.0)
    }

    private class XlsxFont(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Int,
        val strike: Boolean,
        val color: Int?,
        val size: Float,
        val name: String?,
        val script: Int
    )

    private fun parseStylesXml(stylesBytes: ByteArray?) {
        val root = DmlNode.parse(stylesBytes) ?: return
        root.path("colors", "indexedColors")?.all("rgbColor")?.forEachIndexed { i, c ->
            val hex = c.attr("rgb")?.trim()
            if (hex != null && hex.length >= 6) try { indexedPalette[i] = Color.parseColor("#" + hex.takeLast(6)) } catch (_: Exception) {}
        }
        val customFormats = HashMap<Int, String>()
        root.child("numFmts")?.all("numFmt")?.forEach { n ->
            val id = n.int("numFmtId")
            val code = n.attr("formatCode")
            if (id != null && !code.isNullOrEmpty()) customFormats[id] = code
        }
        fun flag(n: DmlNode?): Boolean = n != null && (n.attr("val")?.let { it != "0" && !it.equals("false", true) } ?: true)
        val fonts = root.child("fonts")?.all("font").orEmpty().map { f ->
            val u = f.child("u")
            XlsxFont(
                bold = flag(f.child("b")),
                italic = flag(f.child("i")),
                underline = if (u == null) 0 else when (u.attr("val")?.lowercase(Locale.ROOT)) {
                    null, "single", "singleaccounting" -> 1
                    "double", "doubleaccounting" -> 2
                    else -> 0
                },
                strike = flag(f.child("strike")),
                color = nodeColor(f.child("color")),
                size = f.child("sz")?.attr("val")?.toFloatOrNull() ?: 0f,
                name = f.child("name")?.attr("val"),
                script = when (f.child("vertAlign")?.attr("val")) { "superscript" -> 1; "subscript" -> 2; else -> 0 }
            )
        }
        fonts.firstOrNull()?.let { f ->
            defaultFontName = f.name
            if (f.size > 0f) defaultFontSize = f.size
        }
        val fills = root.child("fills")?.all("fill").orEmpty().map { f ->
            val pf = f.child("patternFill")
            if (pf != null) {
                SheetCellStyle.patternFill(pf.attr("patternType") ?: "none", nodeColor(pf.child("fgColor")), nodeColor(pf.child("bgColor")))
            } else {
                val stops = f.child("gradientFill")?.all("stop").orEmpty().mapNotNull { nodeColor(it.child("color")) }
                if (stops.isEmpty()) null else SheetFill(stops.reduce { a, b -> SheetCellStyle.blend(a, b, 0.5f) })
            }
        }
        fun side(n: DmlNode?): SheetBorder? {
            if (n == null) return null
            val st = SheetBorder.ooxml(n.attr("style"))
            if (st == SheetBorder.NONE) return null
            return SheetBorder(st, nodeColor(n.child("color")))
        }
        val borders = root.child("borders")?.all("border").orEmpty().map { b ->
            val diag = side(b.child("diagonal"))
            arrayOf(
                side(b.child("left") ?: b.child("start")),
                side(b.child("right") ?: b.child("end")),
                side(b.child("top")),
                side(b.child("bottom")),
                if (b.bool("diagonalDown") == true) diag else null,
                if (b.bool("diagonalUp") == true) diag else null
            )
        }
        for (dxf in root.child("dxfs")?.all("dxf").orEmpty()) {
            val f = dxf.child("font")
            val pf = dxf.path("fill", "patternFill")
            val fill = if (pf != null) {
                val type = pf.attr("patternType")
                val fg = nodeColor(pf.child("fgColor"))
                val bg = nodeColor(pf.child("bgColor"))
                when {
                    type == null || type == "solid" -> (bg ?: fg)?.let { SheetFill(it) }
                    type == "none" -> null
                    else -> SheetCellStyle.patternFill(type, fg, bg)
                }
            } else null
            val u = f?.child("u")
            val bd = dxf.child("border")
            val code = dxf.child("numFmt")?.attr("formatCode")
            dxfStyles.add(
                CfStyle(
                    fill = fill,
                    color = nodeColor(f?.child("color")),
                    bold = f?.child("b")?.let { flag(it) },
                    italic = f?.child("i")?.let { flag(it) },
                    underline = u?.let { if (it.attr("val") == "none") 0 else if (it.attr("val")?.startsWith("double") == true) 2 else 1 },
                    strike = f?.child("strike")?.let { flag(it) },
                    left = side(bd?.child("left") ?: bd?.child("start")),
                    right = side(bd?.child("right") ?: bd?.child("end")),
                    top = side(bd?.child("top")),
                    bottom = side(bd?.child("bottom")),
                    numFmt = code ?: dxf.child("numFmt")?.int("numFmtId")?.let { ExcelNumberFormat.code(it, null) }
                )
            )
        }
        val baseFont = fonts.firstOrNull()
        for (xf in root.child("cellXfs")?.all("xf").orEmpty()) {
            if (xfStyles.size >= 65536) break
            val numFmtId = xf.int("numFmtId") ?: 0
            val font = fonts.getOrNull(xf.int("fontId") ?: 0)
            val fill = fills.getOrNull(xf.int("fillId") ?: 0)
            val bd = borders.getOrNull(xf.int("borderId") ?: 0)
            val al = xf.child("alignment")
            val st = SheetCellStyle(
                fill = fill?.bg,
                pattern = fill?.pattern ?: 0,
                patternColor = fill?.fg,
                textColor = font?.color,
                bold = font?.bold == true,
                italic = font?.italic == true,
                underline = font?.underline ?: 0,
                strike = font?.strike == true,
                fontSize = font?.size ?: 0f,
                fontName = font?.name,
                script = font?.script ?: 0,
                hAlign = SheetCellStyle.hAlign(al?.attr("horizontal")),
                vAlign = SheetCellStyle.vAlign(al?.attr("vertical")),
                wrap = al?.bool("wrapText") == true,
                indent = al?.int("indent")?.coerceIn(0, 250) ?: 0,
                rotation = al?.int("textRotation")?.coerceIn(0, 255) ?: 0,
                shrink = al?.bool("shrinkToFit") == true,
                left = bd?.get(0),
                right = bd?.get(1),
                top = bd?.get(2),
                bottom = bd?.get(3),
                diagonalDown = bd?.get(4),
                diagonalUp = bd?.get(5)
            )
            val plain = SheetCellStyle(textColor = baseFont?.color, fontSize = baseFont?.size ?: 0f, fontName = baseFont?.name)
            xfStyles.add(XfStyleSpec(numFmtId, customFormats[numFmtId], if (st == plain) null else st))
        }
    }

    private fun readBiff12RecordHeader(bytes: ByteArray, startPos: Int): Triple<Int, Int, Int>? {
        var p = startPos
        if (p >= bytes.size) return null
        var recId = 0
        var shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recId = recId or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        var recLen = 0
        shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recLen = recLen or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (recLen < 0 || p + recLen > bytes.size) return null
        return Triple(recId, recLen, p)
    }

    private fun biff12Color(b: ByteArray, at: Int): Int? {
        if (at + 8 > b.size) return null
        val type = (b[at].toInt() and 0xFF) ushr 1
        val index = b[at + 1].toInt() and 0xFF
        val tint = ByteBuffer.wrap(b, at + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short / 32767.0
        val base = when (type) {
            1 -> indexedPalette[index]
            2 -> Color.rgb(b[at + 4].toInt() and 0xFF, b[at + 5].toInt() and 0xFF, b[at + 6].toInt() and 0xFF)
            3 -> themePalette.getOrNull(index)
            else -> null
        } ?: return null
        return DrawingMlColorUtils.applyExcelTint(base, tint)
    }

    private fun parseBiff12Styles(stylesBin: ByteArray?) {
        if (stylesBin == null) return
        val customFormats = HashMap<Int, String>()
        val bb = ByteBuffer.wrap(stylesBin).order(ByteOrder.LITTLE_ENDIAN)
        val fonts = ArrayList<SheetCellStyle>()
        val fills = ArrayList<SheetFill?>()
        val borders = ArrayList<Array<SheetBorder?>>()
        var pos = 0
        var inCellXfs = false

        while (pos < stylesBin.size && xfStyles.size < 16384) {
            val hdr = readBiff12RecordHeader(stylesBin, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x002C -> {
                    if (recLen >= 6) {
                        val fmtId = bb.getShort(dataStart).toInt() and 0xFFFF
                        val cch = bb.getInt(dataStart + 2)
                        if (cch in 1..1024 && dataStart + 6 + cch * 2 <= dataStart + recLen) {
                            customFormats[fmtId] = String(stylesBin, dataStart + 6, cch * 2, StandardCharsets.UTF_16LE)
                        }
                    }
                }
                0x002B -> if (recLen >= 21 && fonts.size < 4096) {
                    val height = (bb.getShort(dataStart).toInt() and 0xFFFF) / 20f
                    val grbit = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                    val bls = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                    val sss = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                    val uls = stylesBin[dataStart + 8].toInt() and 0xFF
                    val color = biff12Color(stylesBin, dataStart + 12)
                    var fname: String? = null
                    if (recLen >= 25) {
                        val cch = bb.getInt(dataStart + 21)
                        if (cch in 1..256 && dataStart + 25 + cch * 2 <= dataStart + recLen) fname = String(stylesBin, dataStart + 25, cch * 2, StandardCharsets.UTF_16LE)
                    }
                    fonts.add(
                        SheetCellStyle(
                            textColor = color,
                            bold = bls >= 0x02BC,
                            italic = (grbit and 0x02) != 0,
                            strike = (grbit and 0x08) != 0,
                            underline = when (uls) { 0x01, 0x21 -> 1; 0x02, 0x22 -> 2; else -> 0 },
                            fontSize = height,
                            fontName = fname,
                            script = if (sss in 1..2) sss else 0
                        )
                    )
                    if (fonts.size == 1) {
                        defaultFontName = fname
                        if (height > 0f) defaultFontSize = height
                    }
                }
                0x002D -> if (recLen >= 20 && fills.size < 4096) {
                    val fls = bb.getInt(dataStart)
                    val fg = biff12Color(stylesBin, dataStart + 4)
                    val bg = biff12Color(stylesBin, dataStart + 12)
                    fills.add(SheetCellStyle.biffPattern(fls, fg, bg))
                }
                0x002E -> if (recLen >= 51 && borders.size < 4096) {
                    val flags = stylesBin[dataStart].toInt() and 0xFF
                    fun side(at: Int): SheetBorder? {
                        val dg = stylesBin[at].toInt() and 0xFF
                        if (dg == 0) return null
                        return SheetBorder(dg.coerceIn(1, 13), biff12Color(stylesBin, at + 2))
                    }
                    val diag = side(dataStart + 41)
                    borders.add(
                        arrayOf(
                            side(dataStart + 21), side(dataStart + 31), side(dataStart + 1), side(dataStart + 11),
                            if ((flags and 0x01) != 0) diag else null, if ((flags and 0x02) != 0) diag else null
                        )
                    )
                }
                0x01FB -> if (recLen >= 6) {
                    val n = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                    var q = dataStart + 6
                    var fill: SheetFill? = null
                    var fg: Int? = null
                    var bg: Int? = null
                    var pattern = -1
                    var color: Int? = null
                    var bold: Boolean? = null
                    var italic: Boolean? = null
                    var underline: Int? = null
                    var strike: Boolean? = null
                    var numFmt: String? = null
                    val sides = arrayOfNulls<SheetBorder>(4)
                    for (k in 0 until n.coerceAtMost(64)) {
                        if (q + 4 > dataStart + recLen) break
                        val type = bb.getShort(q).toInt() and 0xFFFF
                        val cb = bb.getShort(q + 2).toInt() and 0xFFFF
                        if (cb < 4 || q + cb > dataStart + recLen) break
                        val d = q + 4
                        when (type) {
                            0x00 -> pattern = stylesBin[d].toInt() and 0xFF
                            0x01 -> fg = biff12Color(stylesBin, d)
                            0x02 -> bg = biff12Color(stylesBin, d)
                            0x05 -> color = biff12Color(stylesBin, d)
                            in 0x06..0x09 -> {
                                val dg = bb.getShort(d + 8).toInt() and 0xFFFF
                                val side = if (dg == 0) null else SheetBorder(dg.coerceIn(1, 13), biff12Color(stylesBin, d))
                                sides[when (type) { 0x06 -> 2; 0x07 -> 3; 0x08 -> 0; else -> 1 }] = side
                            }
                            0x19 -> bold = (bb.getShort(d).toInt() and 0xFFFF) >= 0x2BC
                            0x1A -> underline = when (bb.getShort(d).toInt() and 0xFFFF) { 0 -> 0; 2, 0x22 -> 2; else -> 1 }
                            0x1C -> italic = stylesBin[d].toInt() != 0
                            0x1D -> strike = stylesBin[d].toInt() != 0
                            0x26 -> if (cb >= 10) {
                                val cch = bb.getInt(d + 2)
                                if (cch in 1..1024 && d + 6 + cch * 2 <= q + cb) numFmt = String(stylesBin, d + 6, cch * 2, StandardCharsets.UTF_16LE)
                                else numFmt = ExcelNumberFormat.code(bb.getShort(d).toInt() and 0xFFFF, null)
                            }
                        }
                        q += cb
                    }
                    fill = when {
                        pattern > 1 -> SheetCellStyle.biffPattern(pattern, fg, bg)
                        pattern == 0 -> null
                        else -> (bg ?: fg)?.let { SheetFill(it) }
                    }
                    dxfStyles.add(CfStyle(fill, color, bold, italic, underline, strike, sides[0], sides[1], sides[2], sides[3], numFmt))
                }
                0x0269 -> inCellXfs = true
                0x026A -> inCellXfs = false
                0x002F -> {
                    if (inCellXfs && recLen >= 14) {
                        val numFmtId = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val font = fonts.getOrNull(bb.getShort(dataStart + 4).toInt() and 0xFFFF)
                        val fill = fills.getOrNull(bb.getShort(dataStart + 6).toInt() and 0xFFFF)
                        val bd = borders.getOrNull(bb.getShort(dataStart + 8).toInt() and 0xFFFF)
                        val trot = stylesBin[dataStart + 10].toInt() and 0xFF
                        val indent = stylesBin[dataStart + 11].toInt() and 0xFF
                        val fl = bb.getShort(dataStart + 12).toInt() and 0xFFFF
                        val st = (font ?: SheetCellStyle()).withFill(fill).copy(
                            hAlign = when (fl and 0x7) {
                                1 -> SheetCellStyle.H_LEFT
                                2 -> SheetCellStyle.H_CENTER
                                3 -> SheetCellStyle.H_RIGHT
                                4 -> SheetCellStyle.H_FILL
                                5 -> SheetCellStyle.H_JUSTIFY
                                6 -> SheetCellStyle.H_CENTER_CONTINUOUS
                                7 -> SheetCellStyle.H_DISTRIBUTED
                                else -> SheetCellStyle.H_GENERAL
                            },
                            vAlign = when ((fl ushr 3) and 0x7) {
                                0 -> SheetCellStyle.V_TOP
                                1 -> SheetCellStyle.V_CENTER
                                3 -> SheetCellStyle.V_JUSTIFY
                                4 -> SheetCellStyle.V_DISTRIBUTED
                                else -> SheetCellStyle.V_BOTTOM
                            },
                            wrap = (fl and 0x40) != 0,
                            shrink = (fl and 0x100) != 0,
                            rotation = trot,
                            indent = indent.coerceAtMost(250),
                            left = bd?.get(0), right = bd?.get(1), top = bd?.get(2), bottom = bd?.get(3),
                            diagonalDown = bd?.get(4), diagonalUp = bd?.get(5)
                        )
                        val plain = fonts.firstOrNull() ?: SheetCellStyle()
                        xfStyles.add(XfStyleSpec(numFmtId, customFormats[numFmtId], if (st == plain) null else st))
                    }
                }
            }
            pos = dataStart + recLen
        }
    }

    private fun parseBiff12SharedStrings(sstBytes: ByteArray): List<String> {
        val list = ArrayList<String>()
        val bb = ByteBuffer.wrap(sstBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < sstBytes.size && list.size < 200_000) {
            val hdr = readBiff12RecordHeader(sstBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            if (recId == 0x0013 && recLen >= 5) {
                val charCount = bb.getInt(dataStart + 1)
                if (charCount in 0..32768 && dataStart + 5 + charCount * 2 <= dataStart + recLen) {
                    val s = String(sstBytes, dataStart + 5, charCount * 2, StandardCharsets.UTF_16LE)
                    list.add(s)
                }
            }
            pos = dataStart + recLen
        }
        return list
    }

    private fun parseBiff12WorkbookSheetNames(wbBytes: ByteArray?): List<Pair<String, String>> {
        if (wbBytes == null) return emptyList()
        val names = ArrayList<Pair<String, String>>()
        val bb = ByteBuffer.wrap(wbBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < wbBytes.size && names.size < 128) {
            val hdr = readBiff12RecordHeader(wbBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            if (recId == 0x0099 && recLen >= 1) {
                val flags = wbBytes[dataStart].toInt() and 0xFF
                if ((flags and 0x01) != 0) use1904DateSystem = true
            } else if (recId == 0x009C && recLen >= 12) {
                val rIdCch = bb.getInt(dataStart + 8)
                val nameLenPos = if (rIdCch in 0..256 && dataStart + 12 + rIdCch * 2 + 4 <= dataStart + recLen) {
                    dataStart + 12 + rIdCch * 2
                } else -1
                if (nameLenPos > 0) {
                    val nameCch = bb.getInt(nameLenPos)
                    val nameCharsStart = nameLenPos + 4
                    if (nameCch in 1..256 && nameCharsStart + nameCch * 2 <= dataStart + recLen) {
                        val rId = if (rIdCch in 1..256) String(wbBytes, dataStart + 12, rIdCch * 2, StandardCharsets.UTF_16LE) else ""
                        names.add(String(wbBytes, nameCharsStart, nameCch * 2, StandardCharsets.UTF_16LE) to rId)
                    }
                }
            }
            pos = dataStart + recLen
        }
        return names
    }

    private fun formatXlsbNumericValue(d: Double, styleRef: Int): ExcelNumberFormat.Result {
        val xf = xfStyles.getOrNull(styleRef)
        return ExcelNumberFormat.format(d, xf?.numFmtId ?: 0, xf?.formatCode, use1904DateSystem)
    }

    private fun parseBiff12Worksheet(
        sheetName: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): SheetModel {
        val rowsMap = TreeMap<Int, ArrayList<String>>()
        val colWidths = HashMap<Int, Float>()
        val colStyles = HashMap<Int, SheetCellStyle>()
        val rowHeights = HashMap<Int, Float>()
        val rowStyles = HashMap<Int, SheetCellStyle>()
        val styles = HashMap<Long, SheetCellStyle>()
        val flags = HashMap<Int, ByteArray>()
        val formatColors = HashMap<Long, Int>()
        val fills = HashMap<Long, Int>()
        val merges = ArrayList<MergedCellRegion>()
        val bb = ByteBuffer.wrap(sheetBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var currentRowIdx = -1
        var maxColSeen = -1
        var showGrid = true
        var defaultRow = Math.round(defaultFontSize * 1.364f * 4f) / 4f * PX_PER_PT
        val defaultCol = defaultExcelColumnWidthPx()

        fun putCellAt(r: Int, col: Int, value: String, styleRef: Int, kind: Int = 0, fmt: ExcelNumberFormat.Result? = null) {
            if (r !in 0 until MAX_SHEET_ROWS || col !in 0 until MAX_SHEET_COLS) return
            val rowList = rowsMap.getOrPut(r) { ArrayList() }
            while (rowList.size <= col) rowList.add("")
            rowList[col] = value
            if (col > maxColSeen) maxColSeen = col
            val key = packCoord(r, col)
            xfStyles.getOrNull(styleRef)?.style?.let { if (styles.size < 250_000) styles[key] = it }
            if (kind != 0 && value.isNotEmpty()) rowFlags(flags, r, col, kind)
            fmt?.color?.let { formatColors[key] = it }
            if (fmt != null && fmt.fillAt >= 0) fills[key] = (fmt.fillAt shl 16) or fmt.fillChar.code
        }

        fun putNumber(r: Int, col: Int, d: Double, styleRef: Int) {
            val res = formatXlsbNumericValue(d, styleRef)
            putCellAt(r, col, res.text, styleRef, SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0), res)
        }

        val cfBlocks = ArrayList<CfBlock>()
        var cfRanges: List<IntArray> = emptyList()
        var cfRules = ArrayList<CfRule>()
        var ruleHead: IntArray? = null
        var ruleFormulas: List<String?> = emptyList()
        var ruleText: String? = null
        val ruleCfvos = ArrayList<CfValueObject>()
        val ruleColors = ArrayList<Int>()
        var barMin = 0.1f
        var barMax = 0.9f
        var showValue = true
        var iconSetIdx = 0
        var iconFlags = 0
        val iconSets = listOf("3Arrows", "3ArrowsGray", "3Flags", "3TrafficLights1", "3TrafficLights2", "3Signs", "3Symbols", "3Symbols2", "4Arrows", "4ArrowsGray", "4RedToBlack", "4Rating", "4TrafficLights", "5Arrows", "5ArrowsGray", "5Rating", "5Quarters", "3Stars", "3Triangles", "5Boxes")

        while (pos < sheetBytes.size && rowsMap.size < MAX_SHEET_ROWS) {
            val hdr = readBiff12RecordHeader(sheetBytes, pos) ?: break
            val (recId, recLen, dataStart) = hdr
            when (recId) {
                0x01CD -> if (recLen >= 12) {
                    val n = bb.getInt(dataStart + 8).coerceIn(0, 512)
                    cfRanges = (0 until n).mapNotNull { k ->
                        val q = dataStart + 12 + k * 16
                        if (q + 16 > dataStart + recLen) null else intArrayOf(bb.getInt(q), bb.getInt(q + 4), bb.getInt(q + 8), bb.getInt(q + 12))
                    }
                    cfRules = ArrayList()
                }
                0x01CE -> if (cfRanges.isNotEmpty() && cfRules.isNotEmpty()) cfBlocks.add(CfBlock(cfRanges, cfRules))
                0x01CF -> if (recLen >= 46 && cfRanges.isNotEmpty()) {
                    val top = cfRanges.minOf { it[0] }
                    val left = cfRanges.minOf { it[2] }
                    ruleHead = intArrayOf(bb.getInt(dataStart), bb.getInt(dataStart + 4), bb.getInt(dataStart + 8), bb.getInt(dataStart + 12), bb.getInt(dataStart + 16), bb.getShort(dataStart + 28).toInt() and 0xFFFF)
                    val lens = intArrayOf(bb.getInt(dataStart + 30), bb.getInt(dataStart + 34), bb.getInt(dataStart + 38))
                    var q = dataStart + 42
                    val cch = bb.getInt(q)
                    ruleText = null
                    q += 4
                    if (cch in 0..32767 && q + cch * 2 <= dataStart + recLen) { ruleText = String(sheetBytes, q, cch * 2, StandardCharsets.UTF_16LE); q += cch * 2 }
                    val fs = ArrayList<String?>()
                    for (len in lens) {
                        if (len <= 0 || q + 4 > dataStart + recLen) { fs.add(null); continue }
                        val cce = bb.getInt(q)
                        val f = if (cce in 1..65535 && q + 4 + cce <= dataStart + recLen) BiffCf.formula12(sheetBytes, q + 4, q + 4 + cce, top, left) else null
                        q += 4 + cce.coerceAtLeast(0)
                        if (q + 4 <= dataStart + recLen) q += 4 + bb.getInt(q).coerceAtLeast(0)
                        fs.add(f)
                    }
                    ruleFormulas = fs
                    ruleCfvos.clear(); ruleColors.clear()
                    barMin = 0.1f; barMax = 0.9f; showValue = true; iconSetIdx = 0; iconFlags = 0
                }
                0x01D3 -> if (recLen >= 3) {
                    barMin = (sheetBytes[dataStart].toInt() and 0xFF) / 100f
                    barMax = (sheetBytes[dataStart + 1].toInt() and 0xFF) / 100f
                    showValue = sheetBytes[dataStart + 2].toInt() != 0
                }
                0x01D1 -> if (recLen >= 6) {
                    iconSetIdx = bb.getInt(dataStart)
                    iconFlags = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                }
                0x01D7 -> if (recLen >= 24) {
                    val t = bb.getInt(dataStart)
                    val num = bb.getDouble(dataStart + 4)
                    val gte = bb.getInt(dataStart + 16) != 0
                    val cbF = bb.getInt(dataStart + 20)
                    val f = if (cbF > 0 && dataStart + 28 + cbF <= dataStart + recLen) BiffCf.formula12(sheetBytes, dataStart + 28, dataStart + 28 + cbF, cfRanges.minOfOrNull { it[0] } ?: 0, cfRanges.minOfOrNull { it[2] } ?: 0) else null
                    ruleCfvos.add(CfValueObject(when (t) { 1 -> "num"; 2 -> "min"; 3 -> "max"; 4 -> "percent"; 5 -> "percentile"; else -> "formula" }, f ?: ExcelFormula.numberText(num), gte || t != 4 && t != 5 && t != 1))
                }
                0x0234 -> if (recLen >= 8 && ruleHead != null) biff12Color(sheetBytes, dataStart)?.let { ruleColors.add(it) }
                0x01D0 -> {
                    val h = ruleHead
                    if (h != null) {
                        biff12Rule(h, ruleFormulas, ruleText, ruleCfvos.toList(), ruleColors.toList(), barMin, barMax, showValue, iconSets.getOrElse(iconSetIdx) { "3TrafficLights1" }, iconFlags)?.let { cfRules.add(it) }
                    }
                    ruleHead = null
                }
                0x0089 -> if (recLen >= 2) {
                    showGrid = (bb.getShort(dataStart).toInt() and 0x0004) != 0
                }
                0x01E5 -> if (recLen >= 8) {
                    val miy = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                    if (miy > 0) defaultRow = miy / 20f * PX_PER_PT
                }
                0x003C -> {
                    if (recLen >= 18) {
                        val colFirst = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_COLS - 1)
                        val colLast = bb.getInt(dataStart + 4).coerceIn(colFirst, MAX_SHEET_COLS - 1)
                        val coldx = bb.getInt(dataStart + 8)
                        val ixfe = bb.getInt(dataStart + 12)
                        val hidden = (bb.getShort(dataStart + 16).toInt() and 0x0001) != 0
                        val px = if (hidden) 0f else SpreadsheetCoordinateUtils.excelCharWidthToPixels(coldx / 256f)
                        val cs = xfStyles.getOrNull(ixfe)?.style
                        for (c in colFirst..colLast) {
                            colWidths[c] = px
                            if (cs != null) colStyles[c] = cs
                        }
                    }
                }
                0x0000 -> {
                    if (recLen >= 4) {
                        currentRowIdx = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_ROWS - 1)
                        if (recLen >= 12) {
                            val ixfe = bb.getInt(dataStart + 4)
                            val miy = bb.getShort(dataStart + 8).toInt() and 0xFFFF
                            val fl = bb.getShort(dataStart + 10).toInt() and 0xFFFF
                            if ((fl and 0x1000) != 0) rowHeights[currentRowIdx] = 0f
                            else if (miy > 0) rowHeights[currentRowIdx] = miy / 20f * PX_PER_PT
                            if ((fl and 0x4000) != 0) xfStyles.getOrNull(ixfe)?.style?.let { rowStyles[currentRowIdx] = it }
                        }
                        rowsMap.getOrPut(currentRowIdx) { ArrayList() }
                    }
                }
                0x0001 -> if (recLen >= 8 && currentRowIdx >= 0) {
                    putCellAt(currentRowIdx, bb.getInt(dataStart), "", bb.getInt(dataStart + 4) and 0x00FFFFFF)
                }
                0x0002 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val rk = bb.getInt(dataStart + 8)
                        val isDiv100 = (rk and 1) != 0
                        val isInt = (rk and 2) != 0
                        val raw = if (isInt) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and -4L) shl 32)
                        val v = if (isDiv100) raw / 100.0 else raw
                        putNumber(currentRowIdx, col, v, styleRef)
                    }
                }
                0x0003, 0x000B -> if (recLen >= 9 && currentRowIdx >= 0) {
                    val col = bb.getInt(dataStart)
                    val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                    val code = sheetBytes[dataStart + 8].toInt() and 0xFF
                    val txt = when (code) {
                        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"
                        0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"; else -> "#ERR!"
                    }
                    putCellAt(currentRowIdx, col, txt, styleRef, SheetCellFlags.ERROR)
                }
                0x0004, 0x000A -> {
                    if (recLen >= 9 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val bVal = sheetBytes[dataStart + 8].toInt() != 0
                        putCellAt(currentRowIdx, col, if (bVal) "TRUE" else "FALSE", styleRef, SheetCellFlags.BOOL)
                    }
                }
                0x0005, 0x0009 -> {
                    if (recLen >= 16 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val d = bb.getDouble(dataStart + 8)
                        if (d.isFinite()) putNumber(currentRowIdx, col, d, styleRef)
                    }
                }
                0x0006, 0x0008 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val cch = bb.getInt(dataStart + 8)
                        if (cch in 0..32768 && dataStart + 12 + cch * 2 <= dataStart + recLen) {
                            putCellAt(currentRowIdx, col, String(sheetBytes, dataStart + 12, cch * 2, StandardCharsets.UTF_16LE), styleRef)
                        }
                    }
                }
                0x0007 -> {
                    if (recLen >= 12 && currentRowIdx >= 0) {
                        val col = bb.getInt(dataStart)
                        val styleRef = bb.getInt(dataStart + 4) and 0x00FFFFFF
                        val isst = bb.getInt(dataStart + 8)
                        putCellAt(currentRowIdx, col, sharedStrings.getOrNull(isst) ?: "", styleRef)
                    }
                }
                0x00B0 -> {
                    if (recLen >= 16 && merges.size < 4096) {
                        val r0 = bb.getInt(dataStart).coerceIn(0, MAX_SHEET_ROWS - 1)
                        val r1 = bb.getInt(dataStart + 4).coerceIn(r0, MAX_SHEET_ROWS - 1)
                        val c0 = bb.getInt(dataStart + 8).coerceIn(0, MAX_SHEET_COLS - 1)
                        val c1 = bb.getInt(dataStart + 12).coerceIn(c0, MAX_SHEET_COLS - 1)
                        if (r1 > r0 || c1 > c0) {
                            merges.add(MergedCellRegion(r0, r1, c0, c1))
                        }
                    }
                }
            }
            pos = dataStart + recLen
        }

        while (rowsMap.isNotEmpty()) {
            val last = rowsMap.lastKey()
            val row = rowsMap[last]!!
            if (row.isEmpty() && !rowHeights.containsKey(last) && !rowStyles.containsKey(last)) rowsMap.remove(last) else break
        }
        val maxRowKey = rowsMap.keys.maxOrNull() ?: -1
        val denseRows = ArrayList<List<String>>(maxRowKey + 1)
        for (r in 0..maxRowKey) denseRows.add(rowsMap[r] ?: emptyList())
        val colCount = maxColSeen + 1
        val model = SheetModel(
            name = sheetName,
            rows = denseRows,
            styles = styles,
            colWidthsPx = if (colCount > 0) FloatArray(colCount) { c -> colWidths[c] ?: defaultCol } else null,
            mergedRegions = merges,
            rowHeightsPx = if (denseRows.isNotEmpty()) FloatArray(denseRows.size) { r -> rowHeights[r] ?: defaultRow } else null,
            flags = flags,
            formatColors = formatColors,
            fills = fills,
            rowStyles = rowStyles,
            colStyles = colStyles,
            showGridLines = showGrid,
            defaultFontName = defaultFontName,
            defaultFontSize = defaultFontSize,
            defaultRowHeightPx = defaultRow,
            defaultColWidthPx = defaultCol
        )
        if (cfBlocks.isNotEmpty()) {
            val nums = biff12Numbers(sheetBytes)
            val cells = object : CfCells {
                override fun value(row: Int, col: Int, sheet: String?): Any? {
                    if (sheet != null && !sheet.equals(sheetName, true)) return null
                    nums[packCoord(row, col)]?.let { return it }
                    val t = model.text(row, col)
                    if (t.isEmpty()) return null
                    val fl = model.flags(row, col)
                    if ((fl and SheetCellFlags.BOOL) != 0) return t.equals("TRUE", true)
                    if ((fl and SheetCellFlags.ERROR) != 0) return FormulaError.parse(t) ?: t
                    return t
                }
            }
            try {
                SheetConditionalFormats.apply(cfBlocks, model.rowCount, model.colCount, cells, { r, c -> model.baseStyle(r, c) }, use1904DateSystem, model.conditional) { v, code, _, _ ->
                    ExcelNumberFormat.formatCode(v, code, use1904DateSystem).text
                }
            } catch (_: Exception) {}
        }
        return model
    }

    private fun biff12Numbers(bytes: ByteArray): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var row = -1
        while (pos < bytes.size && out.size < 2_000_000) {
            val (recId, recLen, d) = readBiff12RecordHeader(bytes, pos) ?: break
            when (recId) {
                0x0000 -> if (recLen >= 4) row = bb.getInt(d)
                0x0002 -> if (recLen >= 12 && row >= 0) {
                    val rk = bb.getInt(d + 8)
                    val raw = if ((rk and 2) != 0) (rk shr 2).toDouble() else Double.fromBits((rk.toLong() and -4L) shl 32)
                    out[packCoord(row, bb.getInt(d))] = if ((rk and 1) != 0) raw / 100.0 else raw
                }
                0x0005, 0x0009 -> if (recLen >= 16 && row >= 0) out[packCoord(row, bb.getInt(d))] = bb.getDouble(d + 8)
            }
            pos = d + recLen
        }
        return out
    }

    private fun biff12Rule(
        h: IntArray, formulas: List<String?>, text: String?, cfvos: List<CfValueObject>, colors: List<Int>,
        barMin: Float, barMax: Float, showValue: Boolean, iconSet: String, iconFlags: Int
    ): CfRule? {
        val type = h[0]
        val template = h[1]
        val style = dxfStyles.getOrNull(h[2])
        val priority = h[3]
        val param = h[4]
        val flags = h[5]
        val stop = (flags and 0x02) != 0
        val f = formulas.filterNotNull()
        return when (type) {
            1 -> if (f.isEmpty()) null else CfRule(
                "cellIs", priority, stopIfTrue = stop,
                operator = when (param) { 1 -> "between"; 2 -> "notBetween"; 3 -> "equal"; 4 -> "notEqual"; 5 -> "greaterThan"; 6 -> "lessThan"; 7 -> "greaterThanOrEqual"; else -> "lessThanOrEqual" },
                formulas = f, style = style
            )
            3 -> if (colors.size < 2 || cfvos.size != colors.size) null else CfRule("colorScale", priority, cfvos = cfvos, colors = colors)
            4 -> CfRule("dataBar", priority, cfvos = cfvos, colors = colors.take(1), showValue = showValue, barMinLength = barMin, barMaxLength = barMax.coerceAtLeast(barMin), barAxisNone = true)
            6 -> CfRule("iconSet", priority, cfvos = cfvos, iconSet = iconSet, reverse = (iconFlags and 0x02) != 0, showValue = (iconFlags and 0x01) == 0)
            5 -> CfRule("top10", priority, stopIfTrue = stop, rank = param, percent = (flags and 0x10) != 0, bottom = (flags and 0x08) != 0, style = style)
            else -> when (template) {
                0x07 -> CfRule("uniqueValues", priority, stopIfTrue = stop, style = style)
                0x1B -> CfRule("duplicateValues", priority, stopIfTrue = stop, style = style)
                0x19, 0x1A, 0x1D, 0x1E -> CfRule("aboveAverage", priority, stopIfTrue = stop, aboveAverage = template == 0x19 || template == 0x1D, equalAverage = template >= 0x1D, stdDev = param, style = style)
                0x08 -> CfRule(when (param) { 1 -> "notContainsText"; 2 -> "beginsWith"; 3 -> "endsWith"; else -> "containsText" }, priority, stopIfTrue = stop, text = text, style = style)
                0x09 -> CfRule("containsBlanks", priority, stopIfTrue = stop, style = style)
                0x0A -> CfRule("notContainsBlanks", priority, stopIfTrue = stop, style = style)
                0x0B -> CfRule("containsErrors", priority, stopIfTrue = stop, style = style)
                0x0C -> CfRule("notContainsErrors", priority, stopIfTrue = stop, style = style)
                in 0x0F..0x18 -> CfRule("timePeriod", priority, stopIfTrue = stop, timePeriod = when (template) {
                    0x0F -> "today"; 0x10 -> "tomorrow"; 0x11 -> "yesterday"; 0x12 -> "last7Days"; 0x13 -> "lastMonth"
                    0x14 -> "nextMonth"; 0x15 -> "thisWeek"; 0x16 -> "nextWeek"; 0x17 -> "lastWeek"; else -> "thisMonth"
                }, style = style)
                else -> if (f.isEmpty()) null else CfRule("expression", priority, stopIfTrue = stop, formulas = f.take(1), style = style)
            }
        }
    }

    private fun parseXlsx() {
        val filter: (String) -> Boolean = { normName ->
            normName.endsWith(".xml", ignoreCase = true) ||
                    normName.endsWith(".rels", ignoreCase = true) ||
                    normName.endsWith(".bin", ignoreCase = true) ||
                    normName.startsWith("xl/media/", ignoreCase = true)
        }
        val zipEntries: Map<String, ByteArray> = try {
            if (isEncryptedOle2Container(pfd.fileDescriptor)) {
                val raw = OfficeZipContainerLoader.readRawBytes(pfd, 128 * 1024 * 1024L)
                val decZip = me.hlebkukleuski.fullfilestudio.core.document.OfficeCrypto.decryptOoxmlBytes(
                    raw, password, tryDefaultExcelPassword = true
                )
                if (decZip == null) {
                    lastErrorMsg = if (password.isNullOrEmpty()) {
                        "PASSWORD_REQUIRED: Excel spreadsheet (.xlsx) is encrypted or password protected."
                    } else {
                        "PASSWORD_REQUIRED: Incorrect password for Excel spreadsheet (.xlsx)."
                    }
                    return
                }
                OfficeZipContainerLoader.readZipEntriesFromBytes(decZip, 128 * 1024 * 1024L, filter)
            } else {
                OfficeZipContainerLoader.readZipEntries(
                    pfd = pfd,
                    maxTotalBytes = 128 * 1024 * 1024L,
                    entryFilter = filter
                )
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
            return
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый контейнер XLSX: ${e.localizedMessage}"
            return
        }

        if (zipEntries.containsKey("xl/workbook.bin") ||
            zipEntries.keys.any { it.startsWith("xl/worksheets/", true) && it.endsWith(".bin", true) }
        ) {
            parseBiff12Styles(zipEntries["xl/styles.bin"])
            val sst = zipEntries["xl/sharedStrings.bin"]?.let { parseBiff12SharedStrings(it) } ?: emptyList()
            val wbNames = parseBiff12WorkbookSheetNames(zipEntries["xl/workbook.bin"])
            val binRels = DmlPackage(zipEntries).rels("xl/workbook.bin")
            val byRel = wbNames.mapNotNull { (n, rId) ->
                val target = binRels[rId]?.target ?: return@mapNotNull null
                if (zipEntries.containsKey(target) && target.contains("/worksheets/", true)) n to target else null
            }
            val sheetBinKeys = if (byRel.isNotEmpty()) byRel.map { it.second } else zipEntries.keys.filter {
                val l = it.lowercase(Locale.ROOT)
                l.startsWith("xl/worksheets/sheet") && l.endsWith(".bin")
            }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

            sheetBinKeys.forEachIndexed { idx, key ->
                val bytes = zipEntries[key] ?: return@forEachIndexed
                val sName = (if (byRel.isNotEmpty()) byRel[idx].first else wbNames.getOrNull(idx)?.first)?.takeIf { it.isNotBlank() } ?: "Sheet ${idx + 1}"
                val model = parseBiff12Worksheet(sName, bytes, sst)
                if (model.rows.isNotEmpty() || sheetBinKeys.size == 1) {
                    sheets.add(model)
                }
            }
            if (sheets.isEmpty()) {
                lastErrorMsg = "В бинарной книге XLSB не обнаружено листов с данными."
            }
            return
        }

        zipEntries.entries.firstOrNull {
            it.key.startsWith("xl/theme/theme", ignoreCase = true) && it.key.endsWith(".xml", ignoreCase = true)
        }?.value?.let { parseExcelThemeXml(it) }

        parseStylesXml(zipEntries["xl/styles.xml"])

        val sharedStrings = mutableListOf<String>()
        zipEntries["xl/sharedStrings.xml"]?.let { ssBytes ->
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(ssBytes), StandardCharsets.UTF_8))
                var event = parser.eventType
                var currentSi = StringBuilder()
                var inSi = false
                var inT = false
                var ignorePhoneticDepth = 0
                while (event != XmlPullParser.END_DOCUMENT && sharedStrings.size < 250_000) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (local) {
                                "si" -> { inSi = true; currentSi = StringBuilder() }
                                "rph", "phoneticpr" -> if (inSi) ignorePhoneticDepth++
                                "t" -> if (inSi && ignorePhoneticDepth == 0) inT = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inSi && inT && ignorePhoneticDepth == 0 && currentSi.length < 32768) {
                                currentSi.append(parser.text ?: "")
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (local) {
                                "rph", "phoneticpr" -> if (ignorePhoneticDepth > 0) ignorePhoneticDepth--
                                "t" -> inT = false
                                "si" -> if (inSi) {
                                    sharedStrings.add(currentSi.toString())
                                    inSi = false
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            } catch (_: Exception) {}
        }

        val wbRels = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        val relsBytes = zipEntries["xl/_rels/workbook.xml.rels"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("workbook.xml.rels", ignoreCase = true) }?.value
        if (relsBytes != null) {
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(relsBytes), StandardCharsets.UTF_8))
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (ev == XmlPullParser.START_TAG && local == "relationship") {
                        val id = parser.getAttributeValue(null, "Id") ?: parser.getAttributeValue(null, "id")
                        val target = parser.getAttributeValue(null, "Target") ?: parser.getAttributeValue(null, "target")
                        if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                            val clean = target.replace('\\', '/').trimStart('/')
                            val resolved = if (clean.startsWith("xl/", ignoreCase = true)) {
                                OfficeZipContainerLoader.normalizeZipPath(clean)
                            } else {
                                OfficeZipContainerLoader.normalizeZipPath("xl/$clean")
                            }
                            wbRels[id] = resolved
                        }
                    }
                    ev = parser.next()
                }
            } catch (_: Exception) {}
        }

        val orderedSheets = mutableListOf<Pair<String, String>>()
        val wbXml = zipEntries["xl/workbook.xml"]
            ?: zipEntries.entries.firstOrNull { it.key.endsWith("/workbook.xml", ignoreCase = true) }?.value
        if (wbXml != null) {
            try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(InputStreamReader(ByteArrayInputStream(wbXml), StandardCharsets.UTF_8))
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    if (ev == XmlPullParser.START_TAG) {
                        if (local == "workbookpr") {
                            val d1904 = parser.getAttributeValue(null, "date1904")
                            use1904DateSystem = (d1904 == "1" || d1904.equals("true", ignoreCase = true))
                        } else if (local == "sheet") {
                            val name = parser.getAttributeValue(null, "name") ?: "Sheet ${orderedSheets.size + 1}"
                            var rId: String? = null
                            for (i in 0 until parser.attributeCount) {
                                val aName = parser.getAttributeName(i)?.lowercase(Locale.ROOT) ?: continue
                                val aNs = parser.getAttributeNamespace(i).orEmpty()
                                if (aName == "r:id" || aName.endsWith(":id") || (aName == "id" && aNs.endsWith("/relationships"))) {
                                    rId = parser.getAttributeValue(i)
                                    break
                                }
                            }
                            val targetPath = if (rId != null) wbRels[rId] else null
                            if (targetPath != null && zipEntries.containsKey(targetPath)) {
                                orderedSheets.add(name to targetPath)
                            } else {
                                val fallbackKey = "xl/worksheets/sheet${orderedSheets.size + 1}.xml"
                                if (zipEntries.containsKey(fallbackKey)) {
                                    orderedSheets.add(name to fallbackKey)
                                }
                            }
                        }
                    }
                    ev = parser.next()
                }
            } catch (_: Exception) {}
        }

        if (orderedSheets.isEmpty()) {
            val discovered = zipEntries.keys
                .filter {
                    val norm = it.lowercase(Locale.ROOT)
                    norm.startsWith("xl/worksheets/sheet") && norm.endsWith(".xml")
                }
                .sortedWith(compareBy({ path ->
                    path.substringAfterLast('/').filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
                }, { it }))
            discovered.forEachIndexed { idx, path ->
                orderedSheets.add("Sheet ${idx + 1}" to path)
            }
        }

        val dmlPackage = DmlPackage(zipEntries)
        val dmlPres = try { DmlPresentation(dmlPackage).apply { spreadsheet = true } } catch (_: Exception) { null }
        drawingRenderer = dmlPres?.let { DmlSlideRenderer(it) }
        val sheetDrawings = HashMap<String, List<DmlCellAnchor>>()
        val pendingSheets = ArrayList<RawWorksheetParseResult>()
        for ((sheetName, sheetPath) in orderedSheets) {
            val drawings = if (dmlPres != null) try { DmlDrawings.forSheet(dmlPackage, sheetPath) } catch (_: Exception) { emptyList() } else emptyList()
            if (sheetPath.contains("/chartsheets/", ignoreCase = true)) {
                if (drawings.isNotEmpty()) {
                    sheetDrawings[sheetName.lowercase(Locale.ROOT)] = drawings
                    pendingSheets.add(RawWorksheetParseResult(sheetName, ArrayList(), HashMap(), emptyMap(), null, emptyList(), chartSheet = true))
                }
                continue
            }
            val sheetBytes = zipEntries[sheetPath] ?: continue
            val parsed = parseWorksheetRaw(sheetName, sheetBytes, sharedStrings)
            if (parsed.grid.isNotEmpty() || drawings.isNotEmpty() || orderedSheets.size == 1) {
                if (drawings.isNotEmpty()) sheetDrawings[sheetName.lowercase(Locale.ROOT)] = drawings
                pendingSheets.add(parsed)
            }
        }

        val sheetByName = HashMap<String, RawWorksheetParseResult>()
        for (ps in pendingSheets) {
            sheetByName[ps.name.lowercase(Locale.ROOT)] = ps
        }

        val sheetPathByName = HashMap<String, String>()
        for ((n, path) in orderedSheets) sheetPathByName[n.lowercase(Locale.ROOT)] = path
        val rawCache = HashMap<String, Map<Long, String>>()
        val definedNames = HashMap<String, String>()
        wbXml?.let { bytes ->
            for (dn in xmlSubtrees(bytes, setOf("definedName"))) {
                val n = dn.attr("name") ?: continue
                if (dn.text.isNotBlank()) definedNames.putIfAbsent(n.lowercase(Locale.ROOT), dn.text.trim())
            }
        }
        fun rawFor(sheetName: String): Map<Long, String> = rawCache.getOrPut(sheetName.lowercase(Locale.ROOT)) {
            sheetPathByName[sheetName.lowercase(Locale.ROOT)]?.let { zipEntries[it] }?.let { rawNumericValues(it) } ?: emptyMap()
        }
        cfNames = definedNames
        val workbook = FormulaWorkbook(use1904DateSystem, definedNames)
        if (pendingSheets.any { it.formulas.isNotEmpty() }) {
            for (ps in pendingSheets) {
                if (ps.chartSheet) continue
                workbook.add(FormulaWorkbook.Sheet(ps.name, ps.grid.size, maxOf(ps.grid.maxOfOrNull { it.size } ?: 0, 1), ps.formulas) { r, c ->
                    rawFor(ps.name)[packCoord(r, c)]?.toDoubleOrNull() ?: typedCell(ps.grid.getOrNull(r)?.getOrNull(c), ps.flags[r]?.getOrNull(c)?.toInt() ?: 0)
                })
            }
        }

        val formulaResults = HashMap<String, Map<Long, Any?>>()
        for (ps in pendingSheets) {
            if (ps.formulas.isNotEmpty()) {
                val computed = workbook.evaluateAll(ps.name)
                formulaResults[ps.name.lowercase(Locale.ROOT)] = computed
                for ((key, v) in computed) {
                    val r = (key ushr 32).toInt()
                    val c = (key and 0xFFFFFFFFL).toInt()
                    val row = ps.grid.getOrNull(r) ?: continue
                    while (row.size <= c) row.add("")
                    when (v) {
                        is Double -> {
                            val xf = xfStyles.getOrNull(ps.formulaXf[key] ?: -1)
                            val res = ExcelNumberFormat.format(v, xf?.numFmtId ?: 0, xf?.formatCode, use1904DateSystem)
                            row[c] = res.text
                            rowFlags(ps.flags, r, c, SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0))
                            res.color?.let { ps.formatColors[key] = it }
                            if (res.fillAt >= 0) ps.fills[key] = (res.fillAt shl 16) or res.fillChar.code
                        }
                        is Boolean -> { row[c] = if (v) "TRUE" else "FALSE"; rowFlags(ps.flags, r, c, SheetCellFlags.BOOL) }
                        is FormulaError -> { row[c] = v.code; rowFlags(ps.flags, r, c, SheetCellFlags.ERROR) }
                        null -> row[c] = ""
                        else -> row[c] = v.toString()
                    }
                }
            }
            val drawings = sheetDrawings[ps.name.lowercase(Locale.ROOT)].orEmpty()
            if (!ps.chartSheet && drawings.isNotEmpty()) padGridForDrawings(ps.grid, drawings, MAX_SHEET_ROWS, MAX_SHEET_COLS)
            val colCount = maxOf(ps.grid.maxOfOrNull { it.size } ?: 0, ps.colWidthsPx?.size ?: 0)
            val widths = if (ps.chartSheet) null else FloatArray(colCount) { c -> ps.colWidthsPx?.getOrNull(c) ?: ps.colWidthMap[c] ?: ps.defaultColWidthPx }
            val heights = if (ps.chartSheet || ps.grid.isEmpty()) null else FloatArray(ps.grid.size) { r -> ps.rowHeights[r] ?: ps.defaultRowHeightPx }
            val model =
                SheetModel(
                    name = ps.name,
                    rows = ps.grid,
                    styles = ps.styles,
                    colWidthsPx = widths,
                    mergedRegions = ps.mergedRegions,
                    drawings = drawings,
                    chartSheet = ps.chartSheet,
                    rowHeightsPx = heights,
                    flags = ps.flags,
                    formatColors = ps.formatColors,
                    fills = ps.fills,
                    rowStyles = ps.rowStyles,
                    colStyles = ps.colStyles,
                    showGridLines = ps.showGridLines,
                    defaultFontName = defaultFontName,
                    defaultFontSize = defaultFontSize,
                    defaultRowHeightPx = ps.defaultRowHeightPx,
                    defaultColWidthPx = ps.defaultColWidthPx
                )
            sheets.add(model)
            val sheetBytes = sheetPathByName[ps.name.lowercase(Locale.ROOT)]?.let { zipEntries[it] }
            if (!ps.chartSheet && sheetBytes != null) {
                try {
                    val blocks = conditionalBlocks(sheetBytes)
                    if (blocks.isNotEmpty()) applyConditional(model, blocks, ps.name, { n -> formulaResults[n.lowercase(Locale.ROOT)].orEmpty() }, ::rawFor) { n -> sheets.firstOrNull { it.name.equals(n, true) } }
                } catch (_: Exception) {}
            }
        }

        if (dmlPres != null && sheets.any { it.drawings.isNotEmpty() }) {
            val byName = HashMap<String, SheetModel>()
            for (sm in sheets) byName[sm.name.lowercase(Locale.ROOT)] = sm
            val first = sheets.firstOrNull { !it.chartSheet }
            dmlPres.chartCells = ChartCellResolver { sheetName, row, col ->
                val sm = if (sheetName == null) first else byName[sheetName.lowercase(Locale.ROOT)]
                val txt = sm?.rows?.getOrNull(row)?.getOrNull(col)?.trim()
                if (txt.isNullOrEmpty()) null else chartCellValue(txt)
            }
        }

        if (sheets.isEmpty()) {
            lastErrorMsg = "В книге XLSX не обнаружено листов с данными."
        }
    }

    private fun chartCellValue(txt: String): Any {
        val compact = txt.replace("\u00A0", "").replace(" ", "")
        compact.toDoubleOrNull()?.let { return it }
        if (compact.endsWith("%")) compact.dropLast(1).replace(',', '.').toDoubleOrNull()?.let { return it / 100.0 }
        val commas = compact.count { it == ',' }
        if (commas == 1 && '.' !in compact && compact.length - compact.indexOf(',') - 1 != 3) compact.replace(',', '.').toDoubleOrNull()?.let { return it }
        if (commas >= 1) compact.replace(",", "").toDoubleOrNull()?.let { return it }
        return txt
    }

    private class RawWorksheetParseResult(
        val name: String,
        val grid: ArrayList<ArrayList<String>>,
        val formulas: HashMap<Long, String>,
        val styles: Map<Long, SheetCellStyle>,
        val colWidthsPx: FloatArray?,
        val mergedRegions: List<MergedCellRegion>,
        val chartSheet: Boolean = false,
        val formulaXf: Map<Long, Int> = emptyMap(),
        val rowHeights: Map<Int, Float> = emptyMap(),
        val flags: HashMap<Int, ByteArray> = HashMap(),
        val formatColors: HashMap<Long, Int> = HashMap(),
        val fills: HashMap<Long, Int> = HashMap(),
        val rowStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colWidthMap: Map<Int, Float> = emptyMap(),
        val showGridLines: Boolean = true,
        val defaultRowHeightPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT,
        val defaultColWidthPx: Float = defaultExcelColumnWidthPx()
    )

    private fun rawNumericValues(sheetBytes: ByteArray): Map<Long, String> {
        val out = HashMap<Long, String>()
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(sheetBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var key = -1L
            var numeric = false
            var inV = false
            val sb = StringBuilder()
            var rowIdx = -1
            var colIdx = 0
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 2_000_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> when (local) {
                        "row" -> {
                            rowIdx = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (rowIdx + 1)
                            colIdx = 0
                        }
                        "c" -> {
                            val t = parser.getAttributeValue(null, "t")
                            numeric = t == null || t == "n"
                            val c = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parser.getAttributeValue(null, "r") ?: "")
                            if (c >= 0) colIdx = c
                            key = packCoord(rowIdx, colIdx)
                            sb.setLength(0)
                        }
                        "v" -> inV = true
                    }
                    XmlPullParser.TEXT -> if (inV) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> when (local) {
                        "v" -> inV = false
                        "c" -> {
                            if (numeric && sb.isNotEmpty()) out[key] = sb.toString().trim()
                            colIdx++
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun typedCell(text: String?, flags: Int): Any? {
        if (text.isNullOrEmpty()) return null
        if ((flags and SheetCellFlags.BOOL) != 0) return text.equals("TRUE", true)
        if ((flags and SheetCellFlags.ERROR) != 0) return FormulaError.parse(text) ?: text
        return text
    }

    private fun applyConditional(
        model: SheetModel,
        blocks: List<CfBlock>,
        sheetName: String,
        computed: (String) -> Map<Long, Any?>,
        raw: (String) -> Map<Long, String>,
        other: (String) -> SheetModel?
    ) {
        fun cellValue(m: SheetModel?, rawMap: Map<Long, String>, r: Int, c: Int): Any? {
            if (m == null) return null
            val key = packCoord(r, c)
            val results = computed(m.name)
            if (results.containsKey(key)) return results[key]
            rawMap[key]?.toDoubleOrNull()?.let { return it }
            val t = m.text(r, c)
            if (t.isEmpty()) return null
            val fl = m.flags(r, c)
            if ((fl and SheetCellFlags.BOOL) != 0) return t.equals("TRUE", true)
            if ((fl and SheetCellFlags.ERROR) != 0) return FormulaError.parse(t) ?: t
            return t
        }
        val ownRaw = raw(sheetName)
        val cells = object : CfCells {
            override fun value(row: Int, col: Int, sheet: String?): Any? =
                if (sheet == null || sheet.equals(sheetName, true)) cellValue(model, ownRaw, row, col) else cellValue(other(sheet), raw(sheet), row, col)

            override fun name(name: String): String? = cfNames[name.lowercase(Locale.ROOT)]
        }
        SheetConditionalFormats.apply(
            blocks, model.rowCount, model.colCount, cells, { r, c -> model.baseStyle(r, c) }, use1904DateSystem, model.conditional
        ) { v, code, _, _ -> ExcelNumberFormat.formatCode(v, code, use1904DateSystem).text }
    }

    private fun xmlSubtrees(bytes: ByteArray, names: Set<String>): List<DmlNode> {
        val out = ArrayList<DmlNode>()
        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
            val stack = ArrayList<DmlNode>()
            var ev = parser.eventType
            var count = 0
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name.substringAfterLast(':')
                        if (stack.isNotEmpty() || local in names) {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfterLast(':')] = parser.getAttributeValue(i)
                            val node = DmlNode(local, attrs)
                            if (stack.isEmpty()) out.add(node) else stack.last().children.add(node)
                            stack.add(node)
                            if (++count > 200_000) return out
                        }
                    }
                    XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack.last().text += parser.text
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                ev = parser.next()
            }
        } catch (_: Exception) {}
        return out
    }

    private fun cfvo(n: DmlNode): CfValueObject = CfValueObject(
        n.attr("type") ?: "min",
        n.attr("val") ?: n.child("f")?.text?.trim(),
        n.attr("gte") != "0"
    )

    private fun conditionalBlocks(sheetBytes: ByteArray): List<CfBlock> {
        if (!String(sheetBytes, StandardCharsets.ISO_8859_1).contains("conditionalFormatting")) return emptyList()
        val nodes = xmlSubtrees(sheetBytes, setOf("conditionalFormatting"))
        val x14Rules = HashMap<String, DmlNode>()
        val blocks = ArrayList<CfBlock>()
        for (cf in nodes) {
            val x14 = cf.child("sqref") != null
            if (!x14) continue
            for (rule in cf.all("cfRule")) rule.attr("id")?.let { x14Rules[it] = rule }
        }
        for (cf in nodes) {
            val x14Sqref = cf.child("sqref")?.text
            val ranges = SheetConditionalFormats.parseSqref(x14Sqref ?: cf.attr("sqref") ?: continue)
            if (ranges.isEmpty()) continue
            val rules = ArrayList<CfRule>()
            for (r in cf.all("cfRule")) {
                val type = r.attr("type") ?: continue
                val extId = r.descendants("id").firstOrNull()?.text?.trim()
                if (x14Sqref == null && extId != null && type != "dataBar") continue
                if (x14Sqref != null && (type == "dataBar" && x14Rules.containsKey(r.attr("id") ?: "") && nodes.any { o -> o.child("sqref") == null && o.descendants("id").any { it.text.trim() == r.attr("id") } })) continue
                val formulas = r.all("formula").map { it.text.trim() } + r.all("f").map { it.text.trim() }
                val style = r.int("dxfId")?.let { dxfStyles.getOrNull(it) }
                when (type) {
                    "colorScale" -> {
                        val cs = r.child("colorScale") ?: continue
                        rules.add(CfRule(type, r.int("priority") ?: Int.MAX_VALUE, cfvos = cs.all("cfvo").map { cfvo(it) }, colors = cs.all("color").mapNotNull { nodeColor(it) }))
                    }
                    "dataBar" -> {
                        val db = r.child("dataBar") ?: continue
                        val ext = extId?.let { x14Rules[it] }?.child("dataBar")
                        val src = ext ?: db
                        rules.add(
                            CfRule(
                                type, r.int("priority") ?: Int.MAX_VALUE,
                                cfvos = src.all("cfvo").map { cfvo(it) }.ifEmpty { db.all("cfvo").map { cfvo(it) } },
                                colors = listOfNotNull(nodeColor(db.child("color")) ?: nodeColor(src.child("fillColor"))),
                                showValue = db.attr("showValue") != "0",
                                barGradient = ext?.attr("gradient") != "0",
                                barBorder = if (ext?.attr("border") == "1") nodeColor(ext.child("borderColor")) else null,
                                barNegative = if (ext != null && ext.attr("negativeBarColorSameAsPositive") != "1") nodeColor(ext.child("negativeFillColor")) else nodeColor(db.child("color")),
                                barAxisColor = ext?.child("axisColor")?.let { nodeColor(it) },
                                barAxisNone = ext == null || ext.attr("axisPosition") == "none",
                                barMinLength = (ext?.attr("minLength")?.toFloatOrNull() ?: if (ext != null) 0f else db.attr("minLength")?.toFloatOrNull() ?: 10f) / 100f,
                                barMaxLength = (ext?.attr("maxLength")?.toFloatOrNull() ?: if (ext != null) 100f else db.attr("maxLength")?.toFloatOrNull() ?: 90f) / 100f
                            )
                        )
                    }
                    "iconSet" -> {
                        val ic = r.child("iconSet") ?: continue
                        val custom = if (ic.attr("custom") == "1") ic.all("cfIcon").map { (it.attr("iconSet") ?: "3TrafficLights1") to (it.int("iconId") ?: 0) } else null
                        rules.add(
                            CfRule(
                                type, r.int("priority") ?: Int.MAX_VALUE,
                                cfvos = ic.all("cfvo").map { cfvo(it) },
                                iconSet = ic.attr("iconSet") ?: "3TrafficLights1",
                                reverse = ic.attr("reverse") == "1",
                                showValue = ic.attr("showValue") != "0",
                                customIcons = custom
                            )
                        )
                    }
                    else -> rules.add(
                        CfRule(
                            type,
                            r.int("priority") ?: Int.MAX_VALUE,
                            stopIfTrue = r.attr("stopIfTrue") == "1",
                            operator = r.attr("operator"),
                            formulas = formulas,
                            text = r.attr("text"),
                            style = style,
                            rank = r.int("rank") ?: 10,
                            percent = r.attr("percent") == "1",
                            bottom = r.attr("bottom") == "1",
                            aboveAverage = r.attr("aboveAverage") != "0",
                            equalAverage = r.attr("equalAverage") == "1",
                            stdDev = r.int("stdDev") ?: 0,
                            timePeriod = r.attr("timePeriod")
                        )
                    )
                }
            }
            if (rules.isNotEmpty()) blocks.add(CfBlock(ranges, rules))
        }
        return blocks
    }

    private fun xmlBool(v: String?): Boolean = v == "1" || v.equals("true", ignoreCase = true)

    private fun parseWorksheetRaw(
        name: String,
        sheetBytes: ByteArray,
        sharedStrings: List<String>
    ): RawWorksheetParseResult {
        val grid = ArrayList<ArrayList<String>>()
        val stylesMap = HashMap<Long, SheetCellStyle>()
        val formulasMap = HashMap<Long, String>()
        val formulaXf = HashMap<Long, Int>()
        val colWidthsMap = HashMap<Int, Float>()
        val colStyles = HashMap<Int, SheetCellStyle>()
        val rowStyles = HashMap<Int, SheetCellStyle>()
        val rowHeights = HashMap<Int, Float>()
        val flags = HashMap<Int, ByteArray>()
        val formatColors = HashMap<Long, Int>()
        val fills = HashMap<Long, Int>()
        val mergedRegions = ArrayList<MergedCellRegion>()
        var showGridLines = true
        var defaultRowHeight = Math.round(defaultFontSize * 1.364f * 4f) / 4f * PX_PER_PT
        var defaultColWidth = defaultExcelColumnWidthPx()
        var maxColSeen = -1
        var maxWidthCol = -1

        try {
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(sheetBytes), StandardCharsets.UTF_8))
            var event = parser.eventType
            var currentRow: ArrayList<String>? = null
            var currentRowIdx = -1
            var currentCellType: String? = null
            var currentCellStyleIdx = -1
            var currentColIdx = 0
            var inV = false
            var inIs = false
            var inFormula = false
            var inRph = 0
            var formulaType: String? = null
            var formulaSi: String? = null
            val sharedMasters = HashMap<String, Triple<String, Int, Int>>()
            val cellTextBuilder = StringBuilder()
            val formulaTextBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT && grid.size <= MAX_SHEET_ROWS) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "sheetview" -> {
                                val g = parser.getAttributeValue(null, "showGridLines")
                                if (g == "0" || g.equals("false", true)) showGridLines = false
                            }
                            "sheetformatpr" -> {
                                parser.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { if (it > 0f) defaultRowHeight = it * PX_PER_PT }
                                val dcw = parser.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull()
                                val base = parser.getAttributeValue(null, "baseColWidth")?.toFloatOrNull()
                                if (dcw != null && dcw > 0f) defaultColWidth = SpreadsheetCoordinateUtils.excelCharWidthToPixels(dcw)
                                else if (base != null && base > 0f) defaultColWidth = defaultExcelColumnWidthPx(base)
                            }
                            "col" -> {
                                val minCol = (parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                                val maxCol = (parser.getAttributeValue(null, "max")?.toIntOrNull() ?: (minCol + 1)) - 1
                                val widthChars = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                val hidden = xmlBool(parser.getAttributeValue(null, "hidden"))
                                val colStyle = parser.getAttributeValue(null, "style")?.toIntOrNull()?.let { xfStyles.getOrNull(it)?.style }
                                val startC = minCol.coerceIn(0, MAX_SHEET_COLS - 1)
                                val endC = maxCol.coerceIn(startC, MAX_SHEET_COLS - 1)
                                val px = when {
                                    hidden -> 0f
                                    widthChars != null && widthChars >= 0f -> SpreadsheetCoordinateUtils.excelCharWidthToPixels(widthChars)
                                    else -> null
                                }
                                for (c in startC..endC) {
                                    if (px != null) colWidthsMap[c] = px
                                    if (colStyle != null) colStyles[c] = colStyle
                                }
                                if (px != null && (hidden || abs(px - defaultColWidth) > 0.5f)) maxWidthCol = maxOf(maxWidthCol, minOf(endC, startC + 64))
                            }
                            "row" -> {
                                val rAttr = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                val targetRowIdx = if (rAttr != null && rAttr > 0) (rAttr - 1) else (currentRowIdx + 1)
                                if (targetRowIdx < MAX_SHEET_ROWS) {
                                    while (grid.size < targetRowIdx && grid.size < MAX_SHEET_ROWS) {
                                        grid.add(ArrayList())
                                    }
                                    currentRow = ArrayList()
                                    currentRowIdx = targetRowIdx
                                    if (xmlBool(parser.getAttributeValue(null, "hidden"))) {
                                        rowHeights[targetRowIdx] = 0f
                                    } else {
                                        parser.getAttributeValue(null, "ht")?.toFloatOrNull()?.let { if (it >= 0f) rowHeights[targetRowIdx] = it * PX_PER_PT }
                                    }
                                    if (xmlBool(parser.getAttributeValue(null, "customFormat"))) {
                                        parser.getAttributeValue(null, "s")?.toIntOrNull()?.let { xfStyles.getOrNull(it)?.style }?.let { rowStyles[targetRowIdx] = it }
                                    }
                                } else {
                                    currentRow = null
                                }
                                currentColIdx = 0
                            }
                            "c" -> {
                                currentCellType = parser.getAttributeValue(null, "t")
                                currentCellStyleIdx = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                                val cellRef = parser.getAttributeValue(null, "r")
                                val parsedCol = SpreadsheetCoordinateUtils.columnIndexFromCellRef(cellRef ?: "")
                                if (parsedCol >= 0) {
                                    currentColIdx = parsedCol
                                }
                                cellTextBuilder.setLength(0)
                                formulaTextBuilder.setLength(0)
                                formulaType = null
                                formulaSi = null
                            }
                            "f" -> {
                                inFormula = true
                                formulaType = parser.getAttributeValue(null, "t")
                                formulaSi = parser.getAttributeValue(null, "si")
                            }
                            "v" -> inV = true
                            "is" -> inIs = true
                            "rph", "phoneticpr" -> if (inIs) inRph++
                            "t" -> if (inIs && inRph == 0) inV = true
                            "mergecell" -> {
                                val ref = parser.getAttributeValue(null, "ref") ?: ""
                                val parts = ref.split(':')
                                if (parts.size == 2 && mergedRegions.size < 4096) {
                                    val c0 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[0])
                                    val c1 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[1])
                                    val r0 = (parts[0].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    val r1 = (parts[1].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    if (c0 in 0..c1 && r0 in 0..r1 && c0 < MAX_SHEET_COLS && r0 < MAX_SHEET_ROWS) {
                                        mergedRegions.add(MergedCellRegion(r0, minOf(r1, MAX_SHEET_ROWS - 1), c0, minOf(c1, MAX_SHEET_COLS - 1)))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inFormula && formulaTextBuilder.length < 4096) {
                            formulaTextBuilder.append(parser.text ?: "")
                        } else if (inV && cellTextBuilder.length < 32768) {
                            cellTextBuilder.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "f" -> inFormula = false
                            "v" -> inV = false
                            "t" -> if (inIs) inV = false
                            "rph", "phoneticpr" -> if (inRph > 0) inRph--
                            "is" -> inIs = false
                            "c" -> {
                                val row = currentRow
                                if (row != null && currentColIdx in 0 until MAX_SHEET_COLS) {
                                    val rawText = cellTextBuilder.toString()
                                    val rawVal = rawText.trim()
                                    val xfSpec = xfStyles.getOrNull(currentCellStyleIdx)
                                    val packedCoord = packCoord(currentRowIdx, currentColIdx)
                                    var kind = 0
                                    var fmt: ExcelNumberFormat.Result? = null
                                    val resolved = when (currentCellType) {
                                        "s" -> {
                                            val idx = rawVal.toIntOrNull() ?: -1
                                            sharedStrings.getOrElse(idx) { rawVal }
                                        }
                                        "b" -> { kind = SheetCellFlags.BOOL; if (rawVal == "1" || rawVal.equals("true", ignoreCase = true)) "TRUE" else "FALSE" }
                                        "e" -> { kind = SheetCellFlags.ERROR; rawVal }
                                        "inlineStr" -> rawText
                                        "str", "d" -> rawText
                                        else -> {
                                            val numVal = rawVal.toDoubleOrNull()
                                            if (numVal != null) {
                                                val res = ExcelNumberFormat.format(numVal, xfSpec?.numFmtId ?: 0, xfSpec?.formatCode, use1904DateSystem)
                                                kind = SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0)
                                                fmt = res
                                                res.text
                                            } else {
                                                rawVal
                                            }
                                        }
                                    }.let { txt ->
                                        if (kind == 0 && txt.isNotEmpty() && xfSpec?.formatCode != null && (xfSpec.formatCode.contains('@') || xfSpec.formatCode.count { it == ';' } >= 3)) {
                                            val res = ExcelNumberFormat.formatText(txt, xfSpec.numFmtId, xfSpec.formatCode)
                                            fmt = res
                                            res.text
                                        } else txt
                                    }

                                    while (row.size < currentColIdx && row.size < MAX_SHEET_COLS) {
                                        row.add("")
                                    }
                                    if (row.size == currentColIdx) {
                                        row.add(resolved)
                                    } else if (currentColIdx < row.size) {
                                        row[currentColIdx] = resolved
                                    }
                                    if (currentColIdx > maxColSeen) maxColSeen = currentColIdx
                                    if (kind != 0 && resolved.isNotEmpty()) rowFlags(flags, currentRowIdx, currentColIdx, kind)
                                    fmt?.let { f ->
                                        f.color?.let { if (formatColors.size < 250_000) formatColors[packedCoord] = it }
                                        if (f.fillAt >= 0 && fills.size < 250_000) fills[packedCoord] = (f.fillAt shl 16) or f.fillChar.code
                                    }

                                    var fStr = formulaTextBuilder.toString().trim()
                                    val si = formulaSi
                                    if (formulaType == "shared" && si != null) {
                                        if (fStr.isNotEmpty()) sharedMasters[si] = Triple(fStr, currentRowIdx, currentColIdx)
                                        else sharedMasters[si]?.let { (m, mr, mc) -> fStr = SheetConditionalFormats.shiftFormula(m, currentRowIdx - mr, currentColIdx - mc) }
                                    }
                                    if (fStr.isNotEmpty() && resolved.isEmpty() && formulasMap.size < 100_000) {
                                        formulasMap[packedCoord] = fStr
                                        if (currentCellStyleIdx >= 0) formulaXf[packedCoord] = currentCellStyleIdx
                                    }

                                    val vs = xfSpec?.style
                                    if (vs != null && currentRowIdx >= 0 && stylesMap.size < 250_000) {
                                        stylesMap[packedCoord] = vs
                                    }
                                }
                                currentColIdx++
                            }
                            "row" -> {
                                currentRow?.let { grid.add(it) }
                                currentRow = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}

        while (grid.size > MAX_SHEET_ROWS) grid.removeAt(grid.lastIndex)
        val widthCols = maxOf(maxColSeen, minOf(maxWidthCol, maxColSeen + 1))
        val colWidthsArr = if (widthCols >= 0) {
            FloatArray(widthCols + 1) { c -> colWidthsMap[c] ?: defaultColWidth }
        } else null

        return RawWorksheetParseResult(
            name = name,
            grid = grid,
            formulas = formulasMap,
            styles = stylesMap,
            colWidthsPx = colWidthsArr,
            mergedRegions = mergedRegions,
            formulaXf = formulaXf,
            rowHeights = rowHeights,
            flags = flags,
            formatColors = formatColors,
            fills = fills,
            rowStyles = rowStyles,
            colStyles = colStyles,
            colWidthMap = colWidthsMap,
            showGridLines = showGridLines,
            defaultRowHeightPx = defaultRowHeight,
            defaultColWidthPx = defaultColWidth
        )
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}
