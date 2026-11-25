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
 * Implements BIFF8 Chart sub-stream records defined by the Microsoft Excel (.xls) Binary File
 * Format ([MS-XLS] under the Microsoft Open Specifications Promise) and Apache POI HSSF
 * (https://poi.apache.org/, Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Color
import java.nio.charset.StandardCharsets

class BiffChartAnchor(val colL: Int, val dxL: Int, val rwT: Int, val dyT: Int, val colR: Int, val dxR: Int, val rwB: Int, val dyB: Int)

class BiffChartRef(val bytes: ByteArray, val anchor: BiffChartAnchor?, val chartSheet: Boolean)

interface BiffCellSource {
    fun number(sheet: Int, row: Int, col: Int): Double?
    fun text(sheet: Int, row: Int, col: Int): String?
}

object BiffChartParser {
    private const val BOF = 0x0809
    private const val EOF = 0x000A
    private const val CHART = 0x1002
    private const val BEGIN = 0x1033
    private const val END = 0x1034
    private const val SERIES = 0x1003
    private const val SERIES_TEXT = 0x100D
    private const val BRAI = 0x1051
    private const val DATA_FORMAT = 0x1006
    private const val LINE_FORMAT = 0x1007
    private const val AREA_FORMAT = 0x100A
    private const val PIE_FORMAT = 0x100B
    private const val MARKER_FORMAT = 0x1009
    private const val SER_TO_CRT = 0x1045
    private const val AXIS_PARENT = 0x1041
    private const val AXIS = 0x101D
    private const val CAT_SER_RANGE = 0x1020
    private const val VALUE_RANGE = 0x101F
    private const val TICK = 0x101E
    private const val AXIS_LINE = 0x1021
    private const val CHART_FORMAT = 0x1014
    private const val BAR = 0x1017
    private const val LINE = 0x1018
    private const val PIE = 0x1019
    private const val AREA = 0x101A
    private const val SCATTER = 0x101B
    private const val RADAR = 0x103E
    private const val RADAR_AREA = 0x1040
    private const val BOP_POP = 0x1061
    private const val CHART3D = 0x103A
    private const val LEGEND = 0x1015
    private const val TEXT = 0x1025
    private const val OBJECT_LINK = 0x1027
    private const val FONT_X = 0x1026
    private const val FRAME = 0x1032
    private const val PLOT_AREA = 0x1035
    private const val GEL_FRAME = 0x1066
    private const val CONTINUE = 0x003C
    private const val SIINDEX = 0x1065
    private const val NUMBER = 0x0203
    private const val LABEL = 0x0204
    private const val DEFAULT_TEXT = 0x1024
    private const val SERIES_LIST = 0x1016
    private const val SHAPE_PROPS = 0x08A4
    private const val CRT_ML_FRT = 0x089E
    private const val CONTINUE_FRT12 = 0x087F
    private const val THEME_OFFICE_2007 = 124226

    private val OFFICE_2007_THEME: DmlTheme by lazy {
        val accents = listOf("1F497D", "EEECE1", "4F81BD", "C0504D", "9BBB59", "8064A2", "4BACC6", "F79646", "0000FF", "800080")
        val names = listOf("dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        val xml = names.indices.joinToString("", "<theme><themeElements><clrScheme>", "</clrScheme></themeElements></theme>") { "<${names[it]}><srgbClr val=\"${accents[it]}\"/></${names[it]}>" }
        DmlTheme(DmlNode.parse(xml.toByteArray(StandardCharsets.UTF_8)))
    }

    fun theme(version: Int, data: ByteArray): DmlTheme? {
        if (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            try {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(data)).use { zin ->
                    var e = zin.nextEntry
                    var guard = 0
                    while (e != null && guard++ < 64) {
                        val n = e.name.lowercase(java.util.Locale.ROOT)
                        if (!e.isDirectory && Regex("theme/theme/theme\\d*\\.xml").matches(n)) {
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(16384)
                            while (out.size() < 4 * 1024 * 1024) {
                                val r = zin.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                            DmlNode.parse(out.toByteArray())?.let { return DmlTheme(it) }
                        }
                        e = zin.nextEntry
                    }
                }
            } catch (_: Exception) {
            }
        }
        return if (version == THEME_OFFICE_2007) OFFICE_2007_THEME else null
    }

    private fun accent(clr: DmlColorContext, index: Int, style: Int, count: Int): Int {
        val p = ChartsRenderer.stylePalette(clr, style, count)
        return p[index % p.size]
    }

    private fun applyShapeProps(sp: DmlNode?, f: Fmt, clr: DmlColorContext) {
        if (sp == null || sp.name != "spPr") return
        fun firstColor(fill: DmlNode): Int? = when (fill.name) {
            "solidFill" -> clr.colorIn(fill)
            "gradFill" -> fill.child("gsLst")?.all("gs")?.firstOrNull()?.let { clr.colorIn(it) }
            else -> null
        }
        val fill = sp.children.firstOrNull { it.name == "solidFill" || it.name == "gradFill" || it.name == "noFill" || it.name == "pattFill" || it.name == "blipFill" }
        when {
            fill == null -> f.styledFill = true
            fill.name == "noFill" -> f.noFill = true
            else -> firstColor(fill)?.let { f.gelFill = it; f.noFill = false }
        }
        val ln = sp.child("ln")
        ln?.long("w")?.takeIf { it > 0 }?.let { f.lineWidthPt = it / 12700f }
        val lf = ln?.children?.firstOrNull { it.name == "solidFill" || it.name == "gradFill" || it.name == "noFill" || it.name == "pattFill" }
        when {
            lf == null -> f.styledLine = true
            lf.name == "noFill" -> f.noLine = true
            else -> firstColor(lf)?.let { f.gelLine = it; f.noLine = false }
        }
    }

    private class Rec(val type: Int, val start: Int, val len: Int)

    private class Fmt {
        var fill: Int? = null
        var fillAuto = true
        var noFill = false
        var gelFill: Int? = null
        var gelLine: Int? = null
        var styledLine = false
        var styledFill = false
        var smooth = false
        var line: Int? = null
        var lineAuto = true
        var noLine = false
        var lineWidthPt: Float? = null
        var marker: String? = null
        var markerFill: Int? = null
        var explosion = 0
    }

    private class Ser {
        var name: String? = null
        var nameRef: IntArray? = null
        var valRef: IntArray? = null
        var catRef: IntArray? = null
        var cached = HashMap<Int, MutableMap<Int, Any>>()
        val fmt = Fmt()
        val points = HashMap<Int, Fmt>()
        var group = 0
        var count = 0
    }

    private class Grp {
        var type = "bar"
        var horizontal = false
        var stacked = false
        var percent = false
        var overlap = 0
        var gap = 150
        var varied = false
        var hole = 0
        var startAngle = 0
        var threeD = false
        var legend: String? = null
        var filledRadar = false
        var index = 0
    }

    fun parse(
        ref: BiffChartRef,
        cells: BiffCellSource,
        xti: List<IntArray>,
        palette: Map<Int, Int>,
        fontSizePt: (Int) -> Float?,
        defaultSheet: Int,
        theme: DmlTheme? = null
    ): ChartModel? {
        val clr = DmlColorContext(theme ?: OFFICE_2007_THEME, emptyMap())
        val b = ref.bytes
        val recs = ArrayList<Rec>()
        var p = 0
        while (p + 4 <= b.size && recs.size < 200_000) {
            val t = u16(b, p)
            val l = u16(b, p + 2)
            if (p + 4 + l > b.size) break
            recs.add(Rec(t, p + 4, l))
            p += 4 + l
        }
        val series = ArrayList<Ser>()
        val groups = ArrayList<Grp>()
        var curSer: Ser? = null
        var curFmt: Fmt? = null
        var depth = 0
        var serDepth = -1
        var pendingTextKind = 0
        var textDepth = -1
        var textString: String? = null
        var textFont: Int? = null
        var title: String? = null
        var titleFont: Int? = null
        var catAxis = ChartAxis()
        var valAxis = ChartAxis()
        var axisKind = -1
        var axisDepth = -1
        var axisInfo = HashMap<String, Any?>()
        var lastAxisLineId = -1
        var chartAreaFill: Int? = null
        var chartAreaLine: Int? = null
        var plotFill: Int? = null
        var frameKind = 0
        var chartStyle = 2
        var mlFrt = false
        var roundedExplicit: Boolean? = null
        var plotAreaPending = false
        var curGroup: Grp? = null
        var siIndex = 0
        var hasTitleText = false
        val axisAccum = ArrayList<Pair<Int, HashMap<String, Any?>>>()
        var i = 0
        while (i < recs.size) {
            val r = recs[i]
            val s = r.start
            when (r.type) {
                BEGIN -> depth++
                END -> {
                    depth--
                    if (curSer != null && depth == serDepth) { curSer = null; serDepth = -1 }
                    if (depth == textDepth) {
                        if (pendingTextKind == 1) { title = textString; titleFont = textFont; hasTitleText = true }
                        else if (pendingTextKind == 2 || pendingTextKind == 3) axisInfo["title$pendingTextKind"] = textString
                        pendingTextKind = 0; textDepth = -1; textString = null; textFont = null
                    }
                    if (depth == axisDepth) {
                        axisAccum.add(axisKind to axisInfo)
                        axisKind = -1; axisDepth = -1; axisInfo = HashMap()
                    }
                }
                SERIES -> {
                    val sr = Ser()
                    sr.count = u16(b, s + 6)
                    series.add(sr)
                    curSer = sr
                    serDepth = depth
                }
                BRAI -> {
                    val id = b[s].toInt() and 0xFF
                    val rt = b[s + 1].toInt() and 0xFF
                    val cce = u16(b, s + 6)
                    val refp = parseRef(b, s + 8, cce)
                    val sr = curSer
                    if (sr != null && textDepth < 0) {
                        when (id) {
                            0 -> if (rt == 2) sr.nameRef = refp
                            1 -> sr.valRef = refp
                            2 -> sr.catRef = refp
                        }
                    }
                }
                SERIES_TEXT -> {
                    val cch = b[s + 2].toInt() and 0xFF
                    val str = readXlString(b, s + 3, cch, s + r.len)
                    if (textDepth >= 0) textString = str else curSer?.let { if (it.name == null) it.name = str }
                }
                DATA_FORMAT -> {
                    val xi = u16(b, s)
                    val yi = u16(b, s + 2)
                    val target = series.getOrNull(yi)
                    curFmt = if (target == null) Fmt() else if (xi == 0xFFFF) target.fmt else target.points.getOrPut(xi) { Fmt() }
                }
                SER_TO_CRT -> curSer?.group = u16(b, s)
                LINE_FORMAT -> {
                    val f = curFmt
                    val rgb = Color.rgb(b[s].toInt() and 0xFF, b[s + 1].toInt() and 0xFF, b[s + 2].toInt() and 0xFF)
                    val lns = u16(b, s + 4)
                    val we = s16(b, s + 6)
                    val flags = u16(b, s + 8)
                    val auto = flags and 1 != 0
                    val width = when (we) { -1 -> 0.5f; 0 -> 1f; 1 -> 2f; 2 -> 3f; else -> 1f }
                    if (lastAxisLineId >= 0 && axisDepth >= 0) {
                        if (lastAxisLineId == 1) {
                            axisInfo["grid"] = lns != 5
                            if (!auto) axisInfo["gridColor"] = rgb
                        } else if (lastAxisLineId == 0) {
                            axisInfo["noLine"] = lns == 5 && flags and 4 == 0
                            if (!auto) axisInfo["lineColor"] = rgb
                        }
                        lastAxisLineId = -1
                    } else if (f != null && frameKind == 0) {
                        f.lineAuto = auto
                        f.noLine = !auto && lns == 5
                        if (!auto) f.line = rgb
                        f.lineWidthPt = width
                    } else if (frameKind == 1 && lns != 5) {
                        chartAreaLine = if (auto) Color.BLACK else rgb
                    }
                }
                AREA_FORMAT -> {
                    val f = curFmt
                    val fore = Color.rgb(b[s].toInt() and 0xFF, b[s + 1].toInt() and 0xFF, b[s + 2].toInt() and 0xFF)
                    val fls = u16(b, s + 8)
                    val flags = u16(b, s + 10)
                    val auto = flags and 1 != 0
                    when {
                        frameKind == 1 -> chartAreaFill = if (auto) Color.WHITE else if (fls == 0) null else fore
                        frameKind == 2 -> plotFill = if (auto) null else if (fls == 0) null else fore
                        f != null -> {
                            f.fillAuto = auto
                            f.noFill = !auto && fls == 0
                            if (!auto) f.fill = fore
                        }
                    }
                }
                GEL_FRAME -> {
                    val f = curFmt
                    if (f != null && frameKind == 0) {
                        val data = java.io.ByteArrayOutputStream()
                        data.write(b, s, r.len)
                        var k = i + 1
                        while (k < recs.size && recs[k].type == CONTINUE) { data.write(b, recs[k].start, recs[k].len); k++ }
                        val bytes = data.toByteArray()
                        val pr = PptBytes(bytes)
                        val props = PptProps()
                        var q = 0
                        while (q + 8 <= bytes.size) {
                            val rec = pr.record(q, bytes.size) ?: break
                            if (rec.type == 0xF00B || rec.type == 0xF122) props.mergeFrom(pr, rec)
                            q = rec.end
                        }
                        props.raw(0x0181)?.let { c -> if (c ushr 24 and 0x08 == 0) f.gelFill = Color.rgb(c and 0xFF, (c ushr 8) and 0xFF, (c ushr 16) and 0xFF) }
                        if (!props.bool(0x01BF, 0x10, true)) f.gelFill = null
                        props.raw(0x01C0)?.let { c -> if (c ushr 24 and 0x08 == 0) f.gelLine = Color.rgb(c and 0xFF, (c ushr 8) and 0xFF, (c ushr 16) and 0xFF) }
                    }
                }
                MARKER_FORMAT -> {
                    curFmt?.let { f ->
                        val type = u16(b, s + 8)
                        val flags = u16(b, s + 10)
                        val auto = flags and 1 != 0
                        f.marker = if (auto) null else when (type) {
                            0 -> "none"; 1 -> "square"; 2 -> "diamond"; 3 -> "triangle"; 4 -> "x"; 5 -> "star"
                            6 -> "dot"; 7 -> "dash"; 8 -> "circle"; 9 -> "plus"; else -> "square"
                        }
                        if (!auto) f.markerFill = Color.rgb(b[s + 4].toInt() and 0xFF, b[s + 5].toInt() and 0xFF, b[s + 6].toInt() and 0xFF)
                    }
                }
                SHAPE_PROPS -> {
                    val f = curFmt
                    if (f != null && frameKind == 0 && r.len > 24) {
                        val cb = u32(b, s + 20).coerceAtMost(r.len - 24)
                        val data = java.io.ByteArrayOutputStream()
                        data.write(b, s + 24, cb)
                        var k = i + 1
                        while (k < recs.size && recs[k].type == CONTINUE_FRT12 && data.size() < 1 shl 20) {
                            if (recs[k].len > 12) data.write(b, recs[k].start + 12, recs[k].len - 12)
                            k++
                        }
                        applyShapeProps(DmlNode.parse(data.toByteArray()), f, clr)
                    }
                }
                CRT_ML_FRT -> {
                    mlFrt = true
                    if (r.len >= 25 && u16(b, s + 18) == 2 && (b[s + 20].toInt() and 0xFF) == 2) roundedExplicit = b[s + 24].toInt() != 0
                    var q = s + 16
                    while (q + 8 <= s + r.len) {
                        if (b[q].toInt() == 4 && b[q + 1].toInt() == 0 && u16(b, q + 2) == 3) {
                            val v = u32(b, q + 4)
                            if (v in 1..48) chartStyle = v
                            break
                        }
                        q += 2
                    }
                }
                PIE_FORMAT -> curFmt?.explosion = u16(b, s)
                0x105D -> curFmt?.smooth = u16(b, s) and 1 != 0
                FRAME -> frameKind = if (plotAreaPending) 2 else if (groups.isEmpty() && axisAccum.isEmpty() && textDepth < 0 && depth == 1) 1 else 3
                PLOT_AREA -> plotAreaPending = true
                CHART_FORMAT -> {
                    curFmt = null
                    frameKind = 0
                    val g = Grp()
                    g.varied = u16(b, s + 16) and 1 != 0
                    g.index = u16(b, s + 18)
                    groups.add(g)
                    curGroup = g
                }
                BAR -> curGroup?.let { g ->
                    g.type = "bar"; g.overlap = -s16(b, s); g.gap = u16(b, s + 2)
                    val f = u16(b, s + 4); g.horizontal = f and 1 != 0; g.stacked = f and 2 != 0; g.percent = f and 4 != 0
                }
                LINE -> curGroup?.let { g -> g.type = "line"; val f = u16(b, s); g.stacked = f and 1 != 0; g.percent = f and 2 != 0 }
                AREA -> curGroup?.let { g -> g.type = "area"; val f = u16(b, s); g.stacked = f and 1 != 0; g.percent = f and 2 != 0 }
                PIE, BOP_POP -> curGroup?.let { g -> g.type = "pie"; g.startAngle = u16(b, s); g.hole = if (r.type == PIE) u16(b, s + 2) else 0; if (g.hole > 0) g.type = "doughnut" }
                SCATTER -> curGroup?.type = "scatter"
                RADAR -> curGroup?.type = "radar"
                RADAR_AREA -> curGroup?.let { it.type = "radar"; it.filledRadar = true }
                CHART3D -> curGroup?.threeD = true
                LEGEND -> curGroup?.legend = when (b[s + 16].toInt() and 0xFF) { 0 -> "b"; 1 -> "tr"; 2 -> "t"; 4 -> "l"; else -> "r" }
                AXIS -> {
                    curFmt = null
                    axisKind = u16(b, s)
                    axisDepth = depth
                    axisInfo = HashMap()
                    frameKind = 0
                }
                VALUE_RANGE -> {
                    val flags = u16(b, s + 40)
                    if (flags and 1 == 0) axisInfo["min"] = dbl(b, s)
                    if (flags and 2 == 0) axisInfo["max"] = dbl(b, s + 8)
                    if (flags and 4 == 0) axisInfo["major"] = dbl(b, s + 16)
                    axisInfo["log"] = flags and 0x20 != 0
                    axisInfo["reversed"] = flags and 0x40 != 0
                }
                CAT_SER_RANGE -> {
                    val flags = u16(b, s + 6)
                    axisInfo["between"] = flags and 1 != 0
                    axisInfo["reversed"] = flags and 4 != 0
                }
                TICK -> {
                    axisInfo["labels"] = (b[s + 2].toInt() and 0xFF) != 0
                    val tf = u16(b, s + 24)
                    if (tf and 1 == 0) axisInfo["labelColor"] = Color.rgb(b[s + 4].toInt() and 0xFF, b[s + 5].toInt() and 0xFF, b[s + 6].toInt() and 0xFF)
                }
                AXIS_LINE -> lastAxisLineId = u16(b, s)
                AXIS_PARENT -> curFmt = null
                TEXT -> {
                    curFmt = null
                    pendingTextKind = 0
                    textDepth = depth
                    textString = null
                    textFont = null
                }
                OBJECT_LINK -> if (textDepth >= 0) pendingTextKind = when (u16(b, s)) { 1 -> 1; 2 -> 2; 3 -> 3; else -> 0 }
                FONT_X -> if (textDepth >= 0) textFont = u16(b, s)
                SIINDEX -> siIndex = u16(b, s)
                NUMBER, LABEL -> {
                    val row = u16(b, s)
                    val col = u16(b, s + 2)
                    val v: Any? = if (r.type == NUMBER) dbl(b, s + 6) else readXlUnicode(b, s + 6, s + r.len)
                    val sr = series.getOrNull(col)
                    if (sr != null && v != null) sr.cached.getOrPut(siIndex) { HashMap() }[row] = v
                }
            }
            if (r.type != FRAME && r.type != BEGIN && r.type != LINE_FORMAT && r.type != AREA_FORMAT && r.type != GEL_FRAME && r.type != CONTINUE && r.type != 0x08A4 && r.type != 0x08A5) {
                if (r.type != PLOT_AREA) plotAreaPending = false
            }
            if (r.type == END && depth <= 1) frameKind = 0
            i++
        }
        if (groups.isEmpty() || series.isEmpty()) return null
        for ((kind, info) in axisAccum) {
            val ax = ChartAxis(
                min = info["min"] as Double?, max = info["max"] as Double?, majorUnit = info["major"] as Double?,
                title = info["title${if (kind == 0) 3 else 2}"] as String?,
                majorGrid = info["grid"] as Boolean? ?: false,
                reversed = info["reversed"] as Boolean? ?: false,
                labelColor = info["labelColor"] as Int?,
                lineColor = info["lineColor"] as Int?,
                noLine = info["noLine"] as Boolean? ?: false,
                gridColor = info["gridColor"] as Int?,
                tickLabels = info["labels"] as Boolean? ?: true,
                logBase = if (info["log"] == true) 10.0 else null
            )
            if (kind == 0 && catAxis.min == null) catAxis = ax else if (kind == 1) valAxis = ax
        }
        val scatter = groups.first().type == "scatter"
        if (scatter) {
            axisAccum.filter { it.first == 1 }.let { list ->
                if (list.size >= 2) {
                    val (_, xi) = list[0]
                    val (_, yi) = list[1]
                    catAxis = ChartAxis(min = xi["min"] as Double?, max = xi["max"] as Double?, majorUnit = xi["major"] as Double?, majorGrid = xi["grid"] as Boolean? ?: false)
                    valAxis = ChartAxis(min = yi["min"] as Double?, max = yi["max"] as Double?, majorUnit = yi["major"] as Double?, majorGrid = yi["grid"] as Boolean? ?: false, gridColor = yi["gridColor"] as Int?)
                }
            }
        }
        fun refValues(rf: IntArray?, display: Boolean = false): List<Any?> {
            if (rf == null) return emptyList()
            val sheet = if (rf[0] >= 0) xti.getOrNull(rf[0])?.getOrNull(1) ?: defaultSheet else defaultSheet
            val out = ArrayList<Any?>()
            val r0 = rf[1]; val r1 = rf[2]; val c0 = rf[3]; val c1 = rf[4]
            val n = ((r1 - r0 + 1).toLong() * (c1 - c0 + 1)).coerceAtMost(32000)
            var k = 0
            loop@ for (rr in r0..r1) for (cc in c0..c1) {
                if (k++ >= n) break@loop
                out.add(if (display) cells.text(sheet, rr, cc) ?: cells.number(sheet, rr, cc) else cells.number(sheet, rr, cc) ?: cells.text(sheet, rr, cc))
            }
            return out
        }
        val autoFill = intArrayOf(0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F)
        val autoLine = intArrayOf(0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
        val defaultsFill = intArrayOf(0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF)
        val defaultsLine = intArrayOf(0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF)
        fun pal(idx: Int, def: Int): Int = palette[idx] ?: (0xFF000000.toInt() or def)
        val chartGroups = groups.mapIndexed { gi, g ->
            val ss = series.withIndex().filter { (_, sr) -> sr.group == gi || (groups.size == 1) }
            ChartGroup(
                type = g.type,
                grouping = when { g.percent -> "percentStacked"; g.stacked -> "stacked"; g.type == "bar" -> "clustered"; else -> "standard" },
                series = ss.map { (si, sr) ->
                    val vals = sr.cached[1]?.let { m -> (0 until maxOf(sr.count, (m.keys.maxOrNull() ?: -1) + 1)).map { (m[it] as? Double) } }
                        ?: refValues(sr.valRef).map { it as? Double }
                    val cats = sr.cached[2]?.let { m -> (0 until (m.keys.maxOrNull() ?: -1) + 1).map { m[it]?.let { v -> if (v is Double) ChartsRenderer.formatNumber(v, null) else v.toString() } ?: "" } }
                        ?: refValues(sr.catRef, true).map { v -> when (v) { is Double -> ChartsRenderer.formatNumber(v, null); null -> ""; else -> v.toString() } }
                    val xs = if (g.type == "scatter") (sr.cached[2]?.let { m -> (0 until (m.keys.maxOrNull() ?: -1) + 1).map { m[it] as? Double } } ?: refValues(sr.catRef).map { it as? Double }).takeIf { l -> l.any { it != null } } else null
                    val name = sr.name ?: refValues(sr.nameRef).firstOrNull()?.toString() ?: "Series${si + 1}"
                    val f = sr.fmt
                    val lineType = g.type == "line" || g.type == "scatter" || g.type == "radar"
                    val fill = when {
                        f.noFill -> null
                        f.gelFill != null -> f.gelFill
                        f.styledFill -> accent(clr, si, chartStyle, series.size)
                        !f.fillAuto && f.fill != null -> f.fill
                        else -> pal(autoFill[si % 8], defaultsFill[si % 8])
                    }
                    val line = when {
                        f.noLine -> null
                        lineType && f.styledLine && f.gelLine != null -> f.gelLine
                        lineType && f.styledLine -> accent(clr, si, chartStyle, series.size)
                        !f.lineAuto && f.line != null -> f.line
                        lineType -> f.gelLine ?: f.gelFill ?: pal(autoLine[si % 8], defaultsLine[si % 8])
                        else -> null
                    }
                    ChartSeries(
                        name = name,
                        categories = cats,
                        values = vals,
                        xValues = xs,
                        fillColor = if (lineType) (f.markerFill ?: line) else fill,
                        lineColor = line,
                        lineWidthPt = if (lineType) (f.lineWidthPt?.let { it * 1.5f } ?: 1.5f) else f.lineWidthPt,
                        noLine = f.noLine,
                        noFill = f.noFill,
                        marker = f.marker ?: if (g.type == "scatter" || g.type == "line") listOf("diamond", "square", "triangle", "x", "star", "circle", "plus")[si % 7] else null,
                        pointColors = sr.points.mapNotNull { (k, pf) -> (pf.gelFill ?: if (!pf.fillAuto) pf.fill else null)?.let { k to it } }.toMap(),
                        explosion = f.explosion,
                        smooth = f.smooth
                    )
                },
                gapWidth = g.gap,
                overlap = g.overlap,
                varyColors = g.varied || g.type == "pie" || g.type == "doughnut",
                holeSize = g.hole,
                firstSliceAngle = g.startAngle,
                horizontal = g.horizontal,
                secondary = gi > 0 && g.index > 0,
                showMarkers = true,
                radarStyle = if (g.filledRadar) "filled" else null,
                threeD = g.threeD
            )
        }
        val allSeries = chartGroups.flatMap { it.series }
        val autoTitle = if (!hasTitleText && allSeries.size == 1 && chartGroups.first().type in setOf("pie", "doughnut")) allSeries[0].name else null
        val pie = chartGroups.first().type in setOf("pie", "doughnut")
        val paletteForVary = IntArray(36) { k -> if (pie) pal(autoFill[k % 8], defaultsFill[k % 8]) else pal(autoFill[k % 8], defaultsFill[k % 8]) }
        return ChartModel(
            title = title ?: autoTitle,
            titleSizePt = titleFont?.let { fontSizePt(it) } ?: 12f,
            titleBold = true,
            groups = chartGroups,
            catAxis = catAxis,
            valAxis = valAxis,
            legendPos = groups.firstNotNullOfOrNull { it.legend },
            areaFill = chartAreaFill ?: Color.WHITE,
            areaLine = chartAreaLine,
            plotFill = plotFill,
            textColor = Color.BLACK,
            textSizePt = 10f,
            palette = paletteForVary,
            fontFamily = "Arial",
            catBetween = true,
            roundedCorners = roundedExplicit ?: mlFrt
        )
    }

    private fun parseRef(b: ByteArray, start: Int, cce: Int): IntArray? {
        if (cce <= 0 || start + cce > b.size) return null
        val ptg = b[start].toInt() and 0xFF
        return run {
            when (ptg and 0x7F) {
                0x3A, 0x5A, 0x7A -> if (cce >= 7) {
                    val ixti = u16(b, start + 1); val row = u16(b, start + 3); val col = u16(b, start + 5) and 0x3FFF
                    intArrayOf(ixti, row, row, col, col)
                } else null
                0x3B, 0x5B, 0x7B -> if (cce >= 11) {
                    val ixti = u16(b, start + 1)
                    intArrayOf(ixti, u16(b, start + 3), u16(b, start + 5), u16(b, start + 7) and 0x3FFF, u16(b, start + 9) and 0x3FFF)
                } else null
                0x24, 0x44, 0x64 -> if (cce >= 5) {
                    val row = u16(b, start + 1); val col = u16(b, start + 3) and 0x3FFF
                    intArrayOf(-1, row, row, col, col)
                } else null
                0x25, 0x45, 0x65 -> if (cce >= 9) intArrayOf(-1, u16(b, start + 1), u16(b, start + 3), u16(b, start + 5) and 0x3FFF, u16(b, start + 7) and 0x3FFF) else null
                else -> null
            }
        }
    }

    private fun u16(b: ByteArray, p: Int): Int = if (p + 1 < b.size && p >= 0) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) else 0
    private fun s16(b: ByteArray, p: Int): Int = u16(b, p).toShort().toInt()
    private fun u32(b: ByteArray, p: Int): Int = u16(b, p) or (u16(b, p + 2) shl 16)
    private fun dbl(b: ByteArray, p: Int): Double {
        if (p + 8 > b.size) return 0.0
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
        return Double.fromBits(v)
    }

    private fun readXlString(b: ByteArray, p: Int, cch: Int, end: Int): String {
        if (p >= end) return ""
        val flags = b[p].toInt() and 0xFF
        val q = p + 1
        return if (flags and 1 != 0) String(b, q, minOf(cch * 2, end - q).coerceAtLeast(0) and -2, StandardCharsets.UTF_16LE)
        else String(b, q, minOf(cch, end - q).coerceAtLeast(0), StandardCharsets.ISO_8859_1)
    }

    private fun readXlUnicode(b: ByteArray, p: Int, end: Int): String {
        val cch = u16(b, p)
        return readXlString(b, p + 2, cch, end)
    }

    fun anchorFromDrawing(b: ByteArray, start: Int, end: Int): BiffChartAnchor? {
        var found: BiffChartAnchor? = null
        var q = start
        while (q + 26 <= end) {
            if (u16(b, q + 2) == 0xF010 && (b[q + 4].toInt() and 0xFF) == 18 && b[q + 5].toInt() == 0 && b[q + 6].toInt() == 0 && b[q + 7].toInt() == 0) {
                val a = q + 8
                found = BiffChartAnchor(u16(b, a + 2), u16(b, a + 4), u16(b, a + 6), u16(b, a + 8), u16(b, a + 10), u16(b, a + 12), u16(b, a + 14), u16(b, a + 16))
                q += 26
            } else {
                q++
            }
        }
        return found
    }
}
