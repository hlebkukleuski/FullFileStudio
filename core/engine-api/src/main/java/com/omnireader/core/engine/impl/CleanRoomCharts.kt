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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class ChartSeries(
    val name: String,
    val categories: List<String>,
    val values: List<Double?>,
    val xValues: List<Double?>? = null,
    val fillColor: Int? = null,
    val lineColor: Int? = null,
    val lineWidthPt: Float? = null,
    val noLine: Boolean = false,
    val noFill: Boolean = false,
    val marker: String? = null,
    val markerSizePt: Float? = null,
    val pointColors: Map<Int, Int> = emptyMap(),
    val explosion: Int = 0,
    val smooth: Boolean = false,
    val labels: ChartLabels? = null,
    val formatCode: String? = null
)

class ChartLabels(val showVal: Boolean, val showPercent: Boolean, val showCat: Boolean, val showSeries: Boolean, val position: String?)

class ChartGroup(
    val type: String,
    val grouping: String,
    val series: List<ChartSeries>,
    val gapWidth: Int = 150,
    val overlap: Int = 0,
    val varyColors: Boolean = false,
    val holeSize: Int = 0,
    val firstSliceAngle: Int = 0,
    val horizontal: Boolean = false,
    val secondary: Boolean = false,
    val showMarkers: Boolean = true,
    val labels: ChartLabels? = null,
    val radarStyle: String? = null,
    val threeD: Boolean = false,
    val rotX: Int = 30,
    val depthPercent: Int = 100
)

class ChartAxis(
    val deleted: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val majorUnit: Double? = null,
    val formatCode: String? = null,
    val title: String? = null,
    val majorGrid: Boolean = false,
    val reversed: Boolean = false,
    val labelColor: Int? = null,
    val labelSizePt: Float? = null,
    val lineColor: Int? = null,
    val noLine: Boolean = false,
    val gridColor: Int? = null,
    val tickLabels: Boolean = true,
    val logBase: Double? = null
)

class ChartModel(
    val title: String?,
    val titleSizePt: Float,
    val titleBold: Boolean,
    val groups: List<ChartGroup>,
    val catAxis: ChartAxis,
    val valAxis: ChartAxis,
    val secValAxis: ChartAxis? = null,
    val legendPos: String?,
    val legendOverlay: Boolean = false,
    val plotLayout: RectF? = null,
    val areaFill: Int? = null,
    val areaLine: Int? = null,
    val plotFill: Int? = null,
    val textColor: Int = Color.rgb(0x59, 0x59, 0x59),
    val textSizePt: Float = 10f,
    val palette: IntArray,
    val fontFamily: String? = null,
    val areaImage: android.graphics.Bitmap? = null,
    val areaImageTiled: Boolean = false,
    val plotImage: android.graphics.Bitmap? = null,
    val catBetween: Boolean = true,
    val roundedCorners: Boolean = false
)

object CleanRoomCharts {
    fun stylePalette(colors: DmlColorContext?, style: Int?, count: Int): IntArray {
        val kind = if (style != null && style in 1..48) (style - 1) % 8 else 1
        if (kind == 1) return defaultPalette(colors)
        val base = if (kind == 0) Color.rgb(0x80, 0x80, 0x80) else colors?.scheme("accent${kind - 1}") ?: defaultPalette(null)[kind - 2]
        val n = count.coerceIn(1, 64)
        if (n == 1) return intArrayOf(base)
        val hsl = DmlColors.rgbToHsl(base)
        return IntArray(n) { i ->
            val t = (i.toFloat() / (n - 1) - 0.5f) * 0.5f
            val l = if (t < 0f) hsl[2] * (1f + t) else hsl[2] + (1f - hsl[2]) * t
            DmlColors.hslToRgb(hsl[0], hsl[1], l.coerceIn(0f, 1f))
        }
    }

    fun defaultPalette(colors: DmlColorContext?): IntArray {
        val base = if (colors != null) IntArray(6) { colors.scheme("accent${it + 1}") }
        else intArrayOf(Color.rgb(0x44, 0x72, 0xC4), Color.rgb(0xED, 0x7D, 0x31), Color.rgb(0xA5, 0xA5, 0xA5), Color.rgb(0xFF, 0xC0, 0x00), Color.rgb(0x5B, 0x9B, 0xD5), Color.rgb(0x70, 0xAD, 0x47))
        val out = IntArray(36)
        for (i in 0 until 36) {
            val c = base[i % 6]
            val round = i / 6
            out[i] = when (round) {
                0 -> c
                1 -> DmlColors.applyTransforms(c, listOf(DmlNode("lumMod", mapOf("val" to "60000"))))
                2 -> DmlColors.applyTransforms(c, listOf(DmlNode("lumMod", mapOf("val" to "80000")), DmlNode("lumOff", mapOf("val" to "20000"))))
                3 -> DmlColors.applyTransforms(c, listOf(DmlNode("lumMod", mapOf("val" to "80000"))))
                4 -> DmlColors.applyTransforms(c, listOf(DmlNode("lumMod", mapOf("val" to "60000")), DmlNode("lumOff", mapOf("val" to "40000"))))
                else -> DmlColors.applyTransforms(c, listOf(DmlNode("lumMod", mapOf("val" to "50000"))))
            }
        }
        return out
    }

    fun drawDrawingMlChart(canvas: Canvas, chartSpace: DmlNode, rect: RectF, colors: DmlColorContext, emuScale: Float, images: ((String?) -> android.graphics.Bitmap?)? = null, spreadsheet: Boolean = false) {
        val model = parseDrawingMl(chartSpace, colors, images, spreadsheet) ?: return
        draw(canvas, model, rect, emuScale * 12700f)
    }

    private fun richText(n: DmlNode?): String? {
        n ?: return null
        val rich = n.path("tx", "rich")
        if (rich != null) return rich.all("p").joinToString("\n") { p -> p.children.filter { it.name == "r" || it.name == "fld" }.joinToString("") { it.child("t")?.text ?: "" } }.trim()
        n.path("tx", "strRef", "strCache")?.let { c -> return c.all("pt").joinToString(" ") { it.child("v")?.text ?: "" }.trim() }
        return null
    }

    private fun cacheStrings(n: DmlNode?): List<String> {
        val cache = n?.let { it.path("strRef", "strCache") ?: it.path("numRef", "numCache") ?: it.child("strLit") ?: it.child("numLit") ?: it.path("multiLvlStrRef", "multiLvlStrCache")?.all("lvl")?.firstOrNull() } ?: return emptyList()
        val count = cache.child("ptCount")?.int("val") ?: 0
        val pts = cache.all("pt")
        val size = max(count, (pts.maxOfOrNull { it.int("idx") ?: 0 } ?: -1) + 1).coerceAtMost(100_000)
        val arr = Array(size) { "" }
        val fmt = cache.child("formatCode")?.text
        for (p in pts) {
            val i = p.int("idx") ?: continue
            if (i in arr.indices) {
                val v = p.child("v")?.text ?: ""
                arr[i] = if (fmt != null && n.child("numRef") != null) v.toDoubleOrNull()?.let { formatNumber(it, fmt) } ?: v else v
            }
        }
        return arr.toList()
    }

    private fun cacheNumbers(n: DmlNode?): Pair<List<Double?>, String?> {
        val cache = n?.let { it.path("numRef", "numCache") ?: it.child("numLit") } ?: return emptyList<Double?>() to null
        val count = cache.child("ptCount")?.int("val") ?: 0
        val pts = cache.all("pt")
        val size = max(count, (pts.maxOfOrNull { it.int("idx") ?: 0 } ?: -1) + 1).coerceAtMost(100_000)
        val arr = arrayOfNulls<Double>(size)
        for (p in pts) {
            val i = p.int("idx") ?: continue
            if (i in arr.indices) arr[i] = p.child("v")?.text?.trim()?.toDoubleOrNull()
        }
        return arr.toList() to cache.child("formatCode")?.text
    }

    private fun labelsOf(n: DmlNode?): ChartLabels? {
        val d = n?.child("dLbls") ?: return null
        if (d.child("delete")?.bool("val") == true) return null
        fun f(name: String) = d.child(name)?.bool("val") ?: false
        val l = ChartLabels(f("showVal"), f("showPercent"), f("showCatName"), f("showSerName"), d.child("dLblPos")?.attr("val"))
        return if (l.showVal || l.showPercent || l.showCat || l.showSeries) l else null
    }

    private fun axisOf(n: DmlNode?, colors: DmlColorContext): ChartAxis {
        if (n == null) return ChartAxis(deleted = true)
        val sc = n.child("scaling")
        val ln = n.path("spPr", "ln")
        val txColor = n.path("txPr", "p", "pPr", "defRPr", "solidFill")?.let { colors.colorIn(it) }
        val txSize = n.path("txPr", "p", "pPr", "defRPr")?.int("sz")?.let { it / 100f }
        val grid = n.child("majorGridlines")
        return ChartAxis(
            deleted = n.child("delete")?.bool("val") == true,
            min = sc?.child("min")?.attr("val")?.toDoubleOrNull(),
            max = sc?.child("max")?.attr("val")?.toDoubleOrNull(),
            majorUnit = n.child("majorUnit")?.attr("val")?.toDoubleOrNull(),
            formatCode = n.child("numFmt")?.takeIf { it.bool("sourceLinked") != true }?.attr("formatCode"),
            title = n.child("title")?.let { richText(it) ?: "" },
            majorGrid = grid != null,
            reversed = sc?.child("orientation")?.attr("val") == "maxMin",
            labelColor = txColor,
            labelSizePt = txSize,
            lineColor = ln?.child("solidFill")?.let { colors.colorIn(it) },
            noLine = ln?.child("noFill") != null,
            gridColor = grid?.path("spPr", "ln", "solidFill")?.let { colors.colorIn(it) },
            tickLabels = n.child("tickLblPos")?.attr("val") != "none",
            logBase = sc?.child("logBase")?.attr("val")?.toDoubleOrNull()
        )
    }

    fun parseDrawingMl(space: DmlNode, colors: DmlColorContext, images: ((String?) -> android.graphics.Bitmap?)? = null, spreadsheet: Boolean = false): ChartModel? {
        val chart = space.child("chart") ?: return null
        val plot = chart.child("plotArea") ?: return null
        val serCount = plot.descendants("ser").size
        val ptCount = plot.descendants("ser").maxOfOrNull { it.path("val", "numRef", "numCache", "ptCount")?.int("val") ?: it.path("val", "numLit", "ptCount")?.int("val") ?: 0 } ?: 0
        val varied = plot.children.any { it.child("varyColors")?.bool("val") == true }
        val palette = stylePalette(colors, space.child("style")?.int("val"), if (varied && serCount == 1) ptCount else serCount)
        val groups = ArrayList<ChartGroup>()
        var serIndex = 0
        val axIds = HashMap<String, DmlNode>()
        for (ax in plot.children) if (ax.name.endsWith("Ax")) ax.child("axId")?.attr("val")?.let { axIds[it] = ax }
        val primaryValId = plot.children.firstOrNull { it.name == "valAx" }?.child("axId")?.attr("val")
        for (g in plot.children) {
            val type = when (g.name) {
                "barChart", "bar3DChart" -> "bar"
                "lineChart", "line3DChart", "stockChart" -> "line"
                "areaChart", "area3DChart" -> "area"
                "pieChart", "pie3DChart", "ofPieChart" -> "pie"
                "doughnutChart" -> "doughnut"
                "scatterChart", "bubbleChart" -> "scatter"
                "radarChart" -> "radar"
                else -> null
            } ?: continue
            val series = ArrayList<ChartSeries>()
            val vary = g.child("varyColors")?.bool("val") ?: false
            for (ser in g.all("ser")) {
                val idx = ser.child("idx")?.int("val") ?: serIndex
                val sp = ser.child("spPr")
                val fill = sp?.child("solidFill")?.let { colors.colorIn(it) } ?: sp?.path("gradFill", "gsLst", "gs")?.let { colors.colorIn(it) }
                val ln = sp?.child("ln")
                val lineColor = ln?.child("solidFill")?.let { colors.colorIn(it) }
                val markerNode = ser.child("marker")
                val marker = markerNode?.child("symbol")?.attr("val")
                val pc = HashMap<Int, Int>()
                for (dp in ser.all("dPt")) {
                    val i = dp.child("idx")?.int("val") ?: continue
                    dp.path("spPr", "solidFill")?.let { colors.colorIn(it) }?.let { pc[i] = it }
                }
                val cats = cacheStrings(ser.child("cat") ?: ser.child("xVal"))
                val (vals, fmt) = cacheNumbers(ser.child("val") ?: ser.child("yVal"))
                val xs = if (type == "scatter") cacheNumbers(ser.child("xVal")).first.takeIf { it.isNotEmpty() } else null
                series.add(ChartSeries(
                    name = richText(ser) ?: "Series ${idx + 1}",
                    categories = cats,
                    values = vals,
                    xValues = xs,
                    fillColor = fill,
                    lineColor = lineColor,
                    lineWidthPt = ln?.long("w")?.let { it / 12700f },
                    noLine = ln?.child("noFill") != null,
                    noFill = sp?.child("noFill") != null,
                    marker = marker,
                    markerSizePt = markerNode?.child("size")?.int("val")?.toFloat(),
                    pointColors = pc,
                    explosion = ser.child("explosion")?.int("val") ?: 0,
                    smooth = ser.child("smooth")?.bool("val") ?: false,
                    labels = labelsOf(ser),
                    formatCode = fmt
                ))
                serIndex++
            }
            val axId = g.all("axId").map { it.attr("val") }
            val secondary = primaryValId != null && axId.none { it == primaryValId } && axId.isNotEmpty()
            groups.add(ChartGroup(
                type = type,
                grouping = g.child("grouping")?.attr("val") ?: if (type == "bar") "clustered" else "standard",
                series = series,
                gapWidth = g.child("gapWidth")?.int("val") ?: 150,
                overlap = g.child("overlap")?.int("val") ?: if ((g.child("grouping")?.attr("val") ?: "").contains("tacked")) 100 else 0,
                varyColors = vary,
                holeSize = g.child("holeSize")?.int("val") ?: if (type == "doughnut") 50 else 0,
                firstSliceAngle = g.child("firstSliceAng")?.int("val") ?: 0,
                horizontal = g.child("barDir")?.attr("val") == "bar",
                secondary = secondary,
                showMarkers = g.child("marker")?.bool("val") ?: true,
                labels = labelsOf(g),
                radarStyle = g.child("radarStyle")?.attr("val"),
                threeD = g.name.contains("3D"),
                rotX = chart.path("view3D", "rotX")?.int("val") ?: 30,
                depthPercent = chart.path("view3D", "depthPercent")?.int("val") ?: 100
            ))
        }
        if (groups.isEmpty()) return null
        val firstGroupAxes = plot.children.firstOrNull { it.name.endsWith("Chart") }?.all("axId")?.mapNotNull { it.attr("val") }.orEmpty()
        val catAxNode = firstGroupAxes.mapNotNull { axIds[it] }.firstOrNull { it.name == "catAx" || it.name == "dateAx" || (groups.first().type == "scatter" && it.name == "valAx" && it.child("axPos")?.attr("val").let { p -> p == "b" || p == "t" }) }
            ?: plot.child("catAx") ?: plot.child("dateAx")
        val valAxNode = firstGroupAxes.mapNotNull { axIds[it] }.firstOrNull { it.name == "valAx" && it !== catAxNode } ?: plot.all("valAx").firstOrNull { it !== catAxNode }
        val secValNode = plot.all("valAx").firstOrNull { it !== catAxNode && it !== valAxNode && groups.any { g -> g.secondary } }
        val titleNode = chart.child("title")
        val autoDeleted = chart.child("autoTitleDeleted")?.bool("val") == true
        val allSeries = groups.flatMap { it.series }
        val title = when {
            titleNode != null -> richText(titleNode) ?: if (allSeries.size == 1) allSeries[0].name else "Chart Title"
            !autoDeleted && allSeries.size == 1 && groups.first().type in setOf("pie", "doughnut") -> allSeries[0].name
            else -> null
        }
        val defRPr = space.path("txPr", "p", "pPr", "defRPr")
        val textSize = defRPr?.int("sz")?.let { it / 100f } ?: 10f
        val textColor = defRPr?.child("solidFill")?.let { colors.colorIn(it) } ?: Color.BLACK
        val titleRPr = titleNode?.path("tx", "rich", "p", "pPr", "defRPr") ?: titleNode?.path("txPr", "p", "pPr", "defRPr")
        val layout = plot.path("layout", "manualLayout")?.let { ml ->
            val x = ml.child("x")?.attr("val")?.toFloatOrNull()
            val y = ml.child("y")?.attr("val")?.toFloatOrNull()
            val w = ml.child("w")?.attr("val")?.toFloatOrNull()
            val h = ml.child("h")?.attr("val")?.toFloatOrNull()
            if (x != null && y != null && w != null && h != null && ml.child("xMode")?.attr("val") != "factor") RectF(x, y, x + w, y + h) else null
        }
        val legend = chart.child("legend")
        val areaSp = space.child("spPr")
        return ChartModel(
            title = title,
            titleSizePt = titleRPr?.int("sz")?.let { it / 100f } ?: textSize * 1.4f,
            titleBold = titleRPr?.attr("b")?.let { it == "1" || it == "true" } ?: true,
            catBetween = (valAxNode?.child("crossBetween")?.attr("val") ?: "between") != "midCat",
            groups = groups,
            catAxis = axisOf(catAxNode, colors),
            valAxis = axisOf(valAxNode, colors),
            secValAxis = secValNode?.let { axisOf(it, colors) },
            legendPos = legend?.let { it.child("legendPos")?.attr("val") ?: "r" },
            legendOverlay = legend?.child("overlay")?.bool("val") ?: false,
            plotLayout = layout,
            areaFill = areaSp?.child("solidFill")?.let { colors.colorIn(it) }
                ?: if (spreadsheet && areaSp?.children?.none { it.name.endsWith("Fill") } != false) Color.WHITE else null,
            areaLine = areaSp?.path("ln", "solidFill")?.let { colors.colorIn(it) }
                ?: if (spreadsheet && areaSp?.child("ln")?.children?.none { it.name.endsWith("Fill") } != false) Color.rgb(0x86, 0x86, 0x86) else null,
            plotFill = plot.path("spPr", "solidFill")?.let { colors.colorIn(it) },
            textColor = textColor,
            textSizePt = textSize,
            palette = palette,
            fontFamily = defRPr?.child("latin")?.attr("typeface")?.takeIf { !it.startsWith("+") } ?: colors.theme.minorLatin,
            areaImage = areaSp?.path("blipFill", "blip")?.attr("r:embed")?.let { images?.invoke(it) },
            areaImageTiled = areaSp?.path("blipFill", "tile") != null,
            plotImage = plot.path("spPr", "blipFill", "blip")?.attr("r:embed")?.let { images?.invoke(it) },
            roundedCorners = space.child("roundedCorners")?.attr("val")?.let { it == "1" || it.equals("true", true) } ?: true
        )
    }

    fun formatNumber(v: Double, code: String?): String {
        val fmt = code?.split(';')?.firstOrNull()?.trim().orEmpty()
        if (fmt.isEmpty() || fmt.equals("General", true)) {
            if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
            val s = String.format(Locale.US, "%.10g", v).trimEnd('0').trimEnd('.')
            return if (s.contains('e') || s.contains('E')) String.format(Locale.US, "%.4g", v) else s
        }
        val clean = fmt.replace(Regex("\\[[^]]*]"), "").replace("\\", "").replace("_)", "").replace("_(", "")
        val percent = clean.contains('%')
        val value = if (percent) v * 100 else v
        val numPart = Regex("[#0,.]+").find(clean)?.value ?: return value.toString()
        val decimals = numPart.substringAfter('.', "").count { it == '0' || it == '#' }
        val grouping = numPart.contains(',')
        var body = String.format(Locale.US, if (grouping) "%,.${decimals}f" else "%.${decimals}f", abs(value))
        if (numPart.substringAfter('.', "").all { it == '#' } && decimals > 0) body = body.trimEnd('0').trimEnd('.')
        val prefix = clean.substringBefore(numPart).replace("\"", "")
        val suffix = clean.substringAfter(numPart).replace("\"", "")
        return (if (value < 0) "-" else "") + prefix + body + suffix
    }

    private fun niceUnit(raw: Double): Double {
        if (raw <= 0 || raw.isNaN()) return 1.0
        val exp = floor(log10(raw))
        val f = raw / 10.0.pow(exp)
        val n = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return n * 10.0.pow(exp)
    }

    private class Scale(val lo: Double, val hi: Double, val unit: Double)

    private fun intervals(lo: Double, hi: Double, unit: Double, axis: ChartAxis): Int {
        val outLo = axis.min ?: if (lo == 0.0) 0.0 else floor(lo / unit) * unit
        var outHi = axis.max ?: (ceil(hi / unit) * unit)
        if (axis.max == null && outHi - hi < unit * 0.05 && hi > 0) outHi += unit
        return Math.round((outHi - outLo) / unit).toInt()
    }

    private fun scaleFor(minV: Double, maxV: Double, axis: ChartAxis, percent: Boolean, maxIntervals: Int = 5): Scale {
        if (percent) return Scale(axis.min ?: 0.0, axis.max ?: 1.0, axis.majorUnit ?: 0.1)
        var lo = min(minV, maxV)
        var hi = max(minV, maxV)
        if (lo == hi) { if (hi == 0.0) hi = 1.0 else if (hi > 0) lo = 0.0 else hi = 0.0 }
        if (lo > 0 && (hi - lo) / hi > 1.0 / 6.0) lo = 0.0
        if (hi < 0 && (lo - hi) / lo > 1.0 / 6.0) hi = 0.0
        lo = axis.min ?: lo
        hi = axis.max ?: hi
        var unit = axis.majorUnit ?: niceUnit((hi - lo) / maxIntervals.coerceAtLeast(1))
        if (axis.majorUnit == null) {
            var up = 0
            while (up++ < 40 && intervals(lo, hi, unit, axis) > maxIntervals) {
                val exp = floor(log10(unit))
                val m = Math.round(unit / 10.0.pow(exp)).toInt()
                unit = when (m) { 1 -> 2.0; 2 -> 5.0; else -> 10.0 } * 10.0.pow(exp)
            }
            var guard = 0
            while (guard++ < 40) {
                val exp = floor(log10(unit))
                val m = Math.round(unit / 10.0.pow(exp)).toInt()
                val smaller = when (m) {
                    1 -> 0.5 * 10.0.pow(exp)
                    2 -> 1.0 * 10.0.pow(exp)
                    5 -> 2.0 * 10.0.pow(exp)
                    else -> 5.0 * 10.0.pow(exp)
                }
                if (intervals(lo, hi, smaller, axis) > maxIntervals) break
                unit = smaller
            }
        }
        val outLo = axis.min ?: if (lo == 0.0) 0.0 else floor(lo / unit) * unit
        var outHi = axis.max ?: (ceil(hi / unit) * unit)
        if (axis.max == null && outHi - hi < unit * 0.05 && hi > 0) outHi += unit
        if (outHi <= outLo) outHi = outLo + unit
        return Scale(outLo, outHi, unit)
    }

    fun draw(canvas: Canvas, model: ChartModel, rect: RectF, pxPerPt: Float) {
        canvas.save()
        val radius = if (model.roundedCorners) min(10f * pxPerPt, min(rect.width(), rect.height()) * 0.08f) else 0f
        val areaPath = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
        if (radius > 0f) canvas.clipPath(areaPath) else canvas.clipRect(rect)
        model.areaFill?.let { canvas.drawPath(areaPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it }) }
        model.areaImage?.let { img ->
            if (model.areaImageTiled) {
                val sh = android.graphics.BitmapShader(img, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(pxPerPt * 0.75f, pxPerPt * 0.75f); postTranslate(rect.left, rect.top) })
                canvas.drawRect(rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = sh })
            } else {
                canvas.drawBitmap(img, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        model.areaLine?.let {
            val sw = max(1f, 0.75f * pxPerPt)
            val inset = RectF(rect.left + sw / 2f, rect.top + sw / 2f, rect.right - sw / 2f, rect.bottom - sw / 2f)
            canvas.drawRoundRect(inset, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it; style = Paint.Style.STROKE; strokeWidth = sw })
        }
        val tf = OfficeFonts.resolve(model.fontFamily, false, false)
        val tfBold = OfficeFonts.resolve(model.fontFamily, true, false)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = model.textSizePt * pxPerPt; color = model.textColor; typeface = tf }
        val pad = 7f * pxPerPt
        var inner = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        if (model.title != null && model.title.isNotEmpty()) {
            val tp = TextPaint(text).apply { textSize = model.titleSizePt * pxPerPt; typeface = if (model.titleBold) tfBold else tf; color = model.textColor }
            val lines = model.title.split('\n')
            var y = inner.top - tp.ascent()
            for (l in lines) {
                canvas.drawText(l, rect.centerX() - tp.measureText(l) / 2f, y, tp)
                y += tp.fontSpacing
            }
            inner = RectF(inner.left, inner.top + tp.fontSpacing * lines.size + pad * 0.5f, inner.right, inner.bottom)
        }
        val pieLike = model.groups.first().type in setOf("pie", "doughnut")
        val lineKinds = HashSet<String>()
        run {
            for (g in model.groups) if (g.type == "line" || g.type == "scatter" || g.type == "radar") for (s in g.series) lineKinds.add(s.name)
        }
        legendLineNames = lineKinds
        val legendEntries: List<Pair<String, Int>> = if (pieLike || (model.groups.size == 1 && model.groups[0].varyColors && model.groups[0].series.size == 1)) {
            val s = model.groups.first().series.firstOrNull()
            s?.categories?.indices?.map { i -> (s.categories.getOrElse(i) { "${i + 1}" }) to (s.pointColors[i] ?: model.palette[i % model.palette.size]) } ?: emptyList()
        } else {
            var k = 0
            model.groups.flatMap { g -> g.series.map { s -> val c = seriesColor(s, k++, model, g); s.name to c } }
        }
        val firstGroup = model.groups.first()
        val ordered = if (!pieLike && firstGroup.type == "bar" && firstGroup.horizontal) legendEntries.reversed() else legendEntries
        if (model.legendPos != null && ordered.isNotEmpty()) {
            inner = drawLegend(canvas, ordered, model.legendPos, inner, text, pxPerPt, model.legendOverlay)
        }
        val plot = model.plotLayout?.let { l ->
            RectF(rect.left + rect.width() * l.left, rect.top + rect.height() * l.top, rect.left + rect.width() * l.right, rect.top + rect.height() * l.bottom)
        } ?: inner
        if (pieLike || model.groups.first().type == "radar") {
            model.plotFill?.let { canvas.drawRect(plot, Paint().apply { color = it }) }
            model.plotImage?.let { canvas.drawBitmap(it, null, plot, Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        if (pieLike) {
            drawPie(canvas, model, model.groups.first(), plot, text, pxPerPt)
        } else if (model.groups.first().type == "radar") {
            drawRadar(canvas, model, model.groups.first(), plot, text, pxPerPt)
        } else {
            drawAxesChart(canvas, model, plot, text, pxPerPt, model.plotLayout != null)
        }
        canvas.restore()
    }

    private fun seriesColor(s: ChartSeries, index: Int, model: ChartModel, g: ChartGroup): Int =
        if (g.type == "line" || g.type == "scatter" || g.type == "radar") s.lineColor ?: s.fillColor ?: model.palette[index % model.palette.size]
        else s.fillColor ?: model.palette[index % model.palette.size]

    private fun drawLegend(canvas: Canvas, entries: List<Pair<String, Int>>, pos: String, area: RectF, text: TextPaint, pxPerPt: Float, overlay: Boolean): RectF {
        val sw = text.textSize * 0.7f
        val gap = text.textSize * 0.5f
        val rowH = text.fontSpacing
        val widths = entries.map { sw + gap * 0.6f + text.measureText(it.first) }
        if (pos == "b" || pos == "t") {
            val rows = ArrayList<MutableList<Int>>()
            var cur = ArrayList<Int>()
            var w = 0f
            for (i in entries.indices) {
                if (cur.isNotEmpty() && w + widths[i] + gap > area.width()) { rows.add(cur); cur = ArrayList(); w = 0f }
                cur.add(i); w += widths[i] + gap
            }
            if (cur.isNotEmpty()) rows.add(cur)
            val totalH = rows.size * rowH
            var y = if (pos == "b") area.bottom - totalH else area.top
            for (row in rows) {
                val rw = row.sumOf { (widths[it] + gap).toDouble() }.toFloat() - gap
                var x = area.centerX() - rw / 2f
                for (i in row) {
                    drawLegendItem(canvas, entries[i], x, y, sw, gap, text)
                    x += widths[i] + gap
                }
                y += rowH
            }
            if (overlay) return area
            return if (pos == "b") RectF(area.left, area.top, area.right, area.bottom - totalH - gap) else RectF(area.left, area.top + totalH + gap, area.right, area.bottom)
        }
        val maxW = (widths.maxOrNull() ?: 0f).coerceAtMost(area.width() * 0.45f)
        val totalH = entries.size * rowH
        val x = if (pos == "l") area.left else area.right - maxW
        var y = if (pos == "tr") area.top else area.centerY() - totalH / 2f
        for (e in entries) {
            drawLegendItem(canvas, e, x, y, sw, gap, text)
            y += rowH
        }
        if (overlay) return area
        return if (pos == "l") RectF(area.left + maxW + gap, area.top, area.right, area.bottom) else RectF(area.left, area.top, area.right - maxW - gap, area.bottom)
    }

    private var legendLineNames: Set<String> = emptySet()

    private fun drawLegendItem(canvas: Canvas, e: Pair<String, Int>, x: Float, y: Float, sw: Float, gap: Float, text: TextPaint) {
        val cy = y + text.fontSpacing / 2f
        if (e.first in legendLineNames) {
            canvas.drawLine(x - sw * 0.3f, cy, x + sw * 1.3f, cy, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.second; strokeWidth = max(1f, sw / 5f) })
        } else {
            canvas.drawRect(x, cy - sw / 2f, x + sw, cy + sw / 2f, Paint().apply { color = e.second })
        }
        canvas.drawText(e.first, x + sw + gap * 0.6f, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun drawAxesChart(canvas: Canvas, model: ChartModel, area: RectF, text: TextPaint, pxPerPt: Float, manual: Boolean) {
        val groups = model.groups
        val primary = groups.filter { !it.secondary }
        val horizontal = primary.firstOrNull()?.type == "bar" && primary.first().horizontal
        val scatter = primary.firstOrNull()?.type == "scatter"
        val nCat = groups.flatMap { it.series }.maxOfOrNull { max(it.values.size, it.categories.size) } ?: 0
        val categories = groups.flatMap { it.series }.firstOrNull { it.categories.isNotEmpty() }?.categories ?: (1..nCat).map { it.toString() }
        fun range(gs: List<ChartGroup>): Triple<Double, Double, Boolean> {
            var mn = Double.MAX_VALUE
            var mx = -Double.MAX_VALUE
            var pct = false
            for (g in gs) {
                if (g.grouping == "percentStacked") { pct = true; continue }
                if (g.grouping == "stacked") {
                    for (i in 0 until nCat) {
                        var pos = 0.0
                        var neg = 0.0
                        for (s in g.series) { val v = s.values.getOrNull(i) ?: continue; if (v >= 0) pos += v else neg += v }
                        mn = min(mn, neg); mx = max(mx, pos)
                    }
                } else {
                    for (s in g.series) for (v in s.values) if (v != null) { mn = min(mn, v); mx = max(mx, v) }
                }
            }
            if (mn == Double.MAX_VALUE) { mn = 0.0; mx = 1.0 }
            return Triple(mn, mx, pct)
        }
        val (mn, mx, pct) = range(primary)
        val probe = TextPaint(text).apply { model.valAxis.labelSizePt?.let { textSize = it * pxPerPt } }
        val plotLenGuess = if (horizontal) area.width() * 0.8f else area.height() * 0.85f
        fun sampleLabel(lo: Double, hi: Double, fmt: String?): String {
            val big = max(abs(lo), abs(hi))
            val t = formatNumber(big, fmt)
            return if (fmt == null && hi - lo < 5 && !t.contains('.')) "$t.0" else t
        }
        val maxIntervals = if (horizontal) (plotLenGuess / (probe.measureText(sampleLabel(mn, mx, model.valAxis.formatCode)) * 2f)).toInt().coerceAtMost(9) else (plotLenGuess / (probe.fontSpacing * 1.2f)).toInt()
        val vs = scaleFor(mn, mx, model.valAxis, pct, maxIntervals.coerceIn(2, 10))
        val sec = groups.filter { it.secondary }
        val vs2 = if (sec.isNotEmpty()) range(sec).let { scaleFor(it.first, it.second, model.secValAxis ?: ChartAxis(), it.third) } else null
        val xScale = if (scatter) {
            val xsAll = primary.flatMap { g -> g.series.flatMap { s -> s.xValues ?: s.values.indices.map { (it + 1).toDouble() } } }.filterNotNull()
            val xlo = xsAll.minOrNull() ?: 0.0
            val xhi = xsAll.maxOrNull() ?: 1.0
            val xLen = area.width() * 0.8f
            scaleFor(xlo, xhi, model.catAxis, false, (xLen / (probe.measureText(sampleLabel(xlo, xhi, model.catAxis.formatCode)) * 2f)).toInt().coerceIn(2, 9))
        } else null
        val lblPaint = TextPaint(text).apply {
            model.valAxis.labelColor?.let { color = it }
            model.valAxis.labelSizePt?.let { textSize = it * pxPerPt }
        }
        val catPaint = TextPaint(text).apply {
            model.catAxis.labelColor?.let { color = it }
            model.catAxis.labelSizePt?.let { textSize = it * pxPerPt }
        }
        val valFmt = model.valAxis.formatCode ?: if (pct) "0%" else groups.flatMap { it.series }.firstOrNull()?.formatCode
        val valLabels = ArrayList<Pair<Double, String>>()
        run {
            var v = vs.lo
            var k = 0
            while (v <= vs.hi + vs.unit * 1e-6 && k++ < 200) { valLabels.add(v to formatNumber(cleanZero(v, vs.unit), valFmt)); v += vs.unit }
        }
        var plot = RectF(area)
        if (!manual) {
            val valW = if (model.valAxis.deleted || !model.valAxis.tickLabels) 0f else (valLabels.maxOfOrNull { lblPaint.measureText(it.second) } ?: 0f) + 4f * pxPerPt
            val catH = if (model.catAxis.deleted || !model.catAxis.tickLabels) 0f else catPaint.fontSpacing + 3f * pxPerPt
            val sec2W = if (vs2 != null && model.secValAxis?.deleted != true) lblPaint.measureText(formatNumber(vs2.hi, model.secValAxis?.formatCode)) + 6f * pxPerPt else 0f
            plot = if (horizontal) {
                val catW = if (model.catAxis.deleted) 0f else (categories.maxOfOrNull { catPaint.measureText(it) } ?: 0f).coerceAtMost(area.width() * 0.4f) + 4f * pxPerPt
                RectF(area.left + catW, area.top, area.right, area.bottom - lblPaint.fontSpacing - 3f * pxPerPt)
            } else {
                RectF(area.left + valW, area.top + lblPaint.fontSpacing / 2f, area.right - sec2W, area.bottom - catH)
            }
        }
        if (plot.width() <= 2f || plot.height() <= 2f) return
        model.plotFill?.let { canvas.drawRect(plot, Paint().apply { color = it }) }
        model.plotImage?.let { canvas.drawBitmap(it, null, plot, Paint(Paint.FILTER_BITMAP_FLAG)) }
        fun valPos(v: Double, sc: Scale): Float {
            val t = ((v - sc.lo) / (sc.hi - sc.lo)).toFloat()
            val tt = if (model.valAxis.reversed) 1f - t else t
            return if (horizontal) plot.left + plot.width() * tt else plot.bottom - plot.height() * tt
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = model.valAxis.gridColor ?: Color.rgb(0xD9, 0xD9, 0xD9); strokeWidth = max(1f, 0.75f * pxPerPt) }
        for ((v, lab) in valLabels) {
            val p = valPos(v, vs)
            if (model.valAxis.majorGrid) {
                if (horizontal) canvas.drawLine(p, plot.top, p, plot.bottom, gridPaint) else canvas.drawLine(plot.left, p, plot.right, p, gridPaint)
            }
            if (!model.valAxis.deleted && model.valAxis.tickLabels) {
                if (horizontal) canvas.drawText(lab, p - lblPaint.measureText(lab) / 2f, plot.bottom + lblPaint.fontSpacing, lblPaint)
                else canvas.drawText(lab, plot.left - 4f * pxPerPt - lblPaint.measureText(lab), p - (lblPaint.ascent() + lblPaint.descent()) / 2f, lblPaint)
            }
        }
        if (vs2 != null && model.secValAxis?.deleted != true) {
            var v = vs2.lo
            var k = 0
            while (v <= vs2.hi + vs2.unit * 1e-6 && k++ < 200) {
                val lab = formatNumber(cleanZero(v, vs2.unit), model.secValAxis?.formatCode)
                val p = valPos(v, vs2)
                canvas.drawText(lab, plot.right + 4f * pxPerPt, p - (lblPaint.ascent() + lblPaint.descent()) / 2f, lblPaint)
                v += vs2.unit
            }
        }
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = model.catAxis.lineColor ?: Color.rgb(0xBF, 0xBF, 0xBF); strokeWidth = max(1f, 0.75f * pxPerPt) }
        val zero = valPos(0.0.coerceIn(vs.lo, vs.hi), vs)
        if (!model.catAxis.deleted && !model.catAxis.noLine) {
            if (horizontal) canvas.drawLine(zero, plot.top, zero, plot.bottom, axisPaint) else canvas.drawLine(plot.left, zero, plot.right, zero, axisPaint)
        }
        val n = max(1, nCat)
        val betweenTicks = groups.any { it.type == "bar" } || model.catBetween
        fun catCenter(i: Int): Float {
            val idx = if (model.catAxis.reversed != horizontal) n - 1 - i else i
            return if (horizontal) {
                plot.top + plot.height() * (idx + 0.5f) / n
            } else if (betweenTicks || primary.first().type == "bar") {
                plot.left + plot.width() * (idx + 0.5f) / n
            } else {
                if (n == 1) plot.centerX() else plot.left + plot.width() * idx / (n - 1)
            }
        }
        if (!model.catAxis.deleted && model.catAxis.tickLabels && !scatter) {
            val step = if (horizontal) 1 else max(1, ceil((categories.sumOf { catPaint.measureText(it).toDouble() }.toFloat() + n * 4f * pxPerPt) / plot.width()).toInt())
            for (i in 0 until n step step) {
                val lab = categories.getOrElse(i) { "${i + 1}" }
                val c = catCenter(i)
                if (horizontal) canvas.drawText(lab, plot.left - 4f * pxPerPt - catPaint.measureText(lab), c - (catPaint.ascent() + catPaint.descent()) / 2f, catPaint)
                else canvas.drawText(lab, c - catPaint.measureText(lab) / 2f, plot.bottom + catPaint.fontSpacing, catPaint)
            }
        }
        if (scatter && xScale != null && !model.catAxis.deleted) {
            var v = xScale.lo
            var k = 0
            while (v <= xScale.hi + xScale.unit * 1e-6 && k++ < 200) {
                val x = plot.left + plot.width() * ((v - xScale.lo) / (xScale.hi - xScale.lo)).toFloat()
                val lab = formatNumber(cleanZero(v, xScale.unit), model.catAxis.formatCode)
                if (model.catAxis.majorGrid) canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
                canvas.drawText(lab, x - catPaint.measureText(lab) / 2f, plot.bottom + catPaint.fontSpacing, catPaint)
                v += xScale.unit
            }
        }
        var seriesCounter = 0
        for (g in groups) {
            val sc = if (g.secondary && vs2 != null) vs2 else vs
            when (g.type) {
                "bar" -> {
                    val count = g.series.size
                    val stacked = g.grouping == "stacked" || g.grouping == "percentStacked"
                    val layered = g.threeD && g.grouping == "standard" && count > 1
                    val slot = (if (horizontal) plot.height() else plot.width()) / n
                    val gap = g.gapWidth / 100f
                    val ov = -g.overlap / 100f
                    val bars = if (stacked || layered) 1 else count
                    val barW = slot / (bars + (bars - 1) * ov + gap)
                    val depthStep = if (layered) min(barW * 0.9f * g.depthPercent / 100f, min(plot.width(), plot.height()) * 0.5f / count) else 0f
                    val order = if (layered) g.series.indices.reversed() else g.series.indices
                    for (si in order) {
                        val s = g.series[si]
                        val color = seriesColor(s, seriesCounter + si, model, g)
                        canvas.save()
                        if (layered) canvas.translate(depthStep * si, -depthStep * si)
                        for (i in 0 until n) {
                            val v = s.values.getOrNull(i) ?: continue
                            var base = 0.0
                            var top = v
                            if (stacked) {
                                var pos = 0.0
                                var neg = 0.0
                                var total = 0.0
                                for (t in g.series) total += abs(t.values.getOrNull(i) ?: 0.0)
                                for (k in 0 until si) { val pv = g.series[k].values.getOrNull(i) ?: 0.0; if (pv >= 0) pos += pv else neg += pv }
                                base = if (v >= 0) pos else neg
                                top = base + v
                                if (g.grouping == "percentStacked" && total > 0) { base /= total; top /= total }
                            }
                            val c = catCenter(i)
                            val slotIndex = if (horizontal) bars - 1 - si else si
                            val offset = if (stacked || layered) 0f else (slotIndex * barW * (1 + ov)) - (bars * barW + (bars - 1) * ov * barW) / 2f
                            val b0 = valPos(base.coerceIn(sc.lo, sc.hi), sc)
                            val b1 = valPos(top.coerceIn(sc.lo, sc.hi), sc)
                            val half = if (stacked || layered) barW / 2 else 0f
                            val r = if (horizontal) RectF(min(b0, b1), c + offset - half, max(b0, b1), c + offset + barW - half)
                            else RectF(c + offset - half, min(b0, b1), c + offset + barW - half, max(b0, b1))
                            val pc = s.pointColors[i] ?: if (g.varyColors && g.series.size == 1) model.palette[i % model.palette.size] else color
                            if (g.threeD && !s.noFill) {
                                val d = if (layered) depthStep * 0.8f else (if (horizontal) r.height() else r.width()) * 0.25f * g.depthPercent / 100f
                                val top = Path().apply { moveTo(r.left, r.top); lineTo(r.left + d, r.top - d); lineTo(r.right + d, r.top - d); lineTo(r.right, r.top); close() }
                                val side = Path().apply { moveTo(r.right, r.top); lineTo(r.right + d, r.top - d); lineTo(r.right + d, r.bottom - d); lineTo(r.right, r.bottom); close() }
                                canvas.drawPath(top, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = shadeColor(pc, 0.85f) })
                                canvas.drawPath(side, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = shadeColor(pc, 0.7f) })
                            }
                            if (!s.noFill) canvas.drawRect(r, Paint().apply { this.color = pc })
                            s.lineColor?.let { lc -> if (!s.noLine) canvas.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = lc; style = Paint.Style.STROKE; strokeWidth = max(1f, (s.lineWidthPt ?: 0.75f) * pxPerPt) }) }
                            val lbl = s.labels ?: g.labels
                            if (lbl?.showVal == true) {
                                val t = formatNumber(v, s.formatCode)
                                if (horizontal) canvas.drawText(t, r.right + 2f * pxPerPt, r.centerY() - (text.ascent() + text.descent()) / 2f, text)
                                else canvas.drawText(t, r.centerX() - text.measureText(t) / 2f, (if (stacked) r.centerY() - (text.ascent() + text.descent()) / 2f else r.top - 2f * pxPerPt), text)
                            }
                        }
                        canvas.restore()
                    }
                }
                "line", "area", "scatter" -> {
                    val stackedArea = g.type == "area" && (g.grouping == "stacked" || g.grouping == "percentStacked")
                    var prevPts: List<Pair<Float, Float>>? = null
                    for ((si, s) in g.series.withIndex()) {
                        val color = seriesColor(s, seriesCounter + si, model, g)
                        val pts = ArrayList<Pair<Float, Float>>()
                        for (i in s.values.indices) {
                            var v = s.values[i] ?: continue
                            if (g.grouping == "stacked" || g.grouping == "percentStacked") {
                                var acc = 0.0
                                var total = 0.0
                                for (k in g.series.indices) { val pv = g.series[k].values.getOrNull(i) ?: 0.0; total += abs(pv); if (k < si) acc += pv }
                                v += acc
                                if (g.grouping == "percentStacked" && total > 0) v /= total
                            }
                            val x = if (g.type == "scatter" && xScale != null) {
                                val xv = s.xValues?.getOrNull(i) ?: (i + 1).toDouble()
                                plot.left + plot.width() * ((xv - xScale.lo) / (xScale.hi - xScale.lo)).toFloat()
                            } else catCenter(i)
                            pts.add(x to valPos(v.coerceIn(sc.lo, sc.hi), sc))
                        }
                        if (pts.isEmpty()) continue
                        val path = Path()
                        if (s.smooth && pts.size > 2) {
                            path.moveTo(pts[0].first, pts[0].second)
                            for (k in 0 until pts.size - 1) {
                                val p0 = pts[maxOf(0, k - 1)]; val p1 = pts[k]; val p2 = pts[k + 1]; val p3 = pts[minOf(pts.size - 1, k + 2)]
                                path.cubicTo(
                                    p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                                    p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                                    p2.first, p2.second
                                )
                            }
                        } else {
                            pts.forEachIndexed { k, p -> if (k == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second) }
                        }
                        if (g.type == "area") {
                            val area = Path(path)
                            val base = prevPts
                            if (stackedArea && base != null && base.size == pts.size) {
                                for (k in base.indices.reversed()) area.lineTo(base[k].first, base[k].second)
                            } else {
                                area.lineTo(pts.last().first, zero)
                                area.lineTo(pts.first().first, zero)
                            }
                            area.close()
                            if (stackedArea) prevPts = pts
                            canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = s.fillColor ?: color })
                        } else {
                            val drawLine = !s.noLine && !(g.type == "scatter" && s.lineColor == null && s.lineWidthPt == null && s.marker != null && s.marker != "none")
                            if (drawLine) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = color; style = Paint.Style.STROKE; strokeWidth = max(1f, (s.lineWidthPt ?: 2.25f) * pxPerPt)
                                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                            })
                            val sym = s.marker ?: if (g.showMarkers && g.type != "line") "circle" else null
                            if (sym != null && sym != "none") {
                                val ms = (s.markerSizePt ?: 5f) * pxPerPt
                                val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = s.fillColor ?: color }
                                for (p in pts) drawMarker(canvas, sym, p.first, p.second, ms, mp)
                            }
                        }
                        val lbl = s.labels ?: g.labels
                        if (lbl?.showVal == true) for ((k, p) in pts.withIndex()) {
                            val t = formatNumber(s.values.filterNotNull().getOrElse(k) { 0.0 }, s.formatCode)
                            canvas.drawText(t, p.first + 3f * pxPerPt, p.second - 3f * pxPerPt, text)
                        }
                    }
                }
            }
            seriesCounter += g.series.size
        }
    }

    private fun shadeColor(c: Int, k: Float): Int = Color.argb(Color.alpha(c), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())

    private fun cleanZero(v: Double, unit: Double): Double = if (abs(v) < unit * 1e-9) 0.0 else v

    private fun drawMarker(canvas: Canvas, sym: String, x: Float, y: Float, size: Float, p: Paint) {
        val h = size / 2f
        when (sym) {
            "square" -> canvas.drawRect(x - h, y - h, x + h, y + h, p)
            "diamond" -> canvas.drawPath(Path().apply { moveTo(x, y - h); lineTo(x + h, y); lineTo(x, y + h); lineTo(x - h, y); close() }, p)
            "triangle" -> canvas.drawPath(Path().apply { moveTo(x, y - h); lineTo(x + h, y + h); lineTo(x - h, y + h); close() }, p)
            "x", "star", "plus", "dash", "dot" -> {
                val sp = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, size / 5f) }
                when (sym) {
                    "x" -> { canvas.drawLine(x - h, y - h, x + h, y + h, sp); canvas.drawLine(x - h, y + h, x + h, y - h, sp) }
                    "plus" -> { canvas.drawLine(x - h, y, x + h, y, sp); canvas.drawLine(x, y - h, x, y + h, sp) }
                    "dash" -> canvas.drawLine(x - h, y, x + h, y, sp)
                    "dot" -> canvas.drawCircle(x, y, h / 2f, p)
                    else -> { canvas.drawLine(x - h, y - h, x + h, y + h, sp); canvas.drawLine(x - h, y + h, x + h, y - h, sp); canvas.drawLine(x, y - h, x, y + h, sp) }
                }
            }
            else -> canvas.drawCircle(x, y, h, p)
        }
    }

    private fun drawPie(canvas: Canvas, model: ChartModel, g: ChartGroup, area: RectF, text: TextPaint, pxPerPt: Float) {
        val s = g.series.firstOrNull() ?: return
        val vals = s.values.map { max(0.0, it ?: 0.0) }
        val total = vals.sum()
        if (total <= 0) return
        if (g.threeD) {
            drawPie3D(canvas, model, g, s, vals, total, area, text, pxPerPt)
            return
        }
        val r = min(area.width(), area.height()) / 2f * 0.95f
        val cx = area.centerX()
        val cy = area.centerY()
        var start = -90f + g.firstSliceAngle
        val hole = g.holeSize / 100f
        for ((i, v) in vals.withIndex()) {
            val sweep = (v / total * 360.0).toFloat()
            val color = s.pointColors[i] ?: if (g.varyColors || true) model.palette[i % model.palette.size] else (s.fillColor ?: model.palette[0])
            val mid = Math.toRadians((start + sweep / 2f).toDouble())
            val ex = r * s.explosion / 100f
            val ox = (cos(mid) * ex).toFloat()
            val oy = (sin(mid) * ex).toFloat()
            val oval = RectF(cx - r + ox, cy - r + oy, cx + r + ox, cy + r + oy)
            val path = Path()
            if (hole > 0f) {
                val ir = r * hole
                path.arcTo(oval, start, sweep)
                path.arcTo(RectF(cx - ir + ox, cy - ir + oy, cx + ir + ox, cy + ir + oy), start + sweep, -sweep)
                path.close()
            } else {
                path.moveTo(cx + ox, cy + oy)
                path.arcTo(oval, start, sweep)
                path.close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = s.lineColor ?: Color.WHITE; style = Paint.Style.STROKE; strokeWidth = max(1f, (s.lineWidthPt ?: 0.75f) * pxPerPt) })
            val lbl = s.labels ?: g.labels
            if (lbl != null) {
                val parts = ArrayList<String>()
                if (lbl.showCat) parts.add(s.categories.getOrElse(i) { "" })
                if (lbl.showVal) parts.add(formatNumber(v, s.formatCode))
                if (lbl.showPercent) parts.add("${Math.round(v / total * 100)}%")
                val t = parts.joinToString("; ")
                val lr = if (lbl.position == "outEnd") r * 1.12f else if (hole > 0f) r * (1 + hole) / 2f else r * 0.65f
                val tx = cx + ox + (cos(mid) * lr).toFloat()
                val ty = cy + oy + (sin(mid) * lr).toFloat()
                canvas.drawText(t, tx - text.measureText(t) / 2f, ty - (text.ascent() + text.descent()) / 2f, text)
            }
            start += sweep
        }
    }

    private fun drawPie3D(canvas: Canvas, model: ChartModel, g: ChartGroup, s: ChartSeries, vals: List<Double>, total: Double, area: RectF, text: TextPaint, pxPerPt: Float) {
        val tilt = sin(Math.toRadians(g.rotX.coerceIn(10, 80).toDouble())).toFloat()
        val rx = min(area.width() / 2f, area.height() / (2f * tilt + 0.2f)) * 0.95f
        val ry = rx * tilt
        val depth = rx * 0.2f
        val cx = area.centerX()
        val cy = area.centerY() - depth / 2f
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        val starts = ArrayList<Float>()
        val sweeps = ArrayList<Float>()
        var a = -90f + g.firstSliceAngle
        for (v in vals) {
            val sw = (v / total * 360.0).toFloat()
            starts.add(a); sweeps.add(sw); a += sw
        }
        for (i in vals.indices) {
            val color = s.pointColors[i] ?: model.palette[i % model.palette.size]
            val st = starts[i]
            val en = st + sweeps[i]
            var x0 = st
            while (x0 < en) {
                val norm = ((x0 % 360f) + 360f) % 360f
                val segEnd = min(en, x0 + (if (norm < 180f) 180f - norm else 360f - norm))
                if (norm < 180f) {
                    val side = Path()
                    side.arcTo(oval, x0, segEnd - x0)
                    side.arcTo(RectF(oval.left, oval.top + depth, oval.right, oval.bottom + depth), segEnd, -(segEnd - x0))
                    side.close()
                    canvas.drawPath(side, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = shadeColor(color, 0.7f) })
                }
                x0 = segEnd
            }
        }
        for (i in vals.indices) {
            val color = s.pointColors[i] ?: model.palette[i % model.palette.size]
            val p = Path().apply { moveTo(cx, cy); arcTo(oval, starts[i], sweeps[i]); close() }
            canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = max(1f, 0.75f * pxPerPt) })
            val lbl = s.labels ?: g.labels
            if (lbl != null) {
                val v = vals[i]
                val parts = ArrayList<String>()
                if (lbl.showCat) parts.add(s.categories.getOrElse(i) { "" })
                if (lbl.showVal) parts.add(formatNumber(v, s.formatCode))
                if (lbl.showPercent) parts.add("${Math.round(v / total * 100)}%")
                val t = parts.joinToString("; ")
                val mid = Math.toRadians((starts[i] + sweeps[i] / 2f).toDouble())
                val tx = cx + (cos(mid) * rx * 0.65f).toFloat()
                val ty = cy + (sin(mid) * ry * 0.65f).toFloat()
                canvas.drawText(t, tx - text.measureText(t) / 2f, ty - (text.ascent() + text.descent()) / 2f, text)
            }
        }
    }

    private fun drawRadar(canvas: Canvas, model: ChartModel, g: ChartGroup, area: RectF, text: TextPaint, pxPerPt: Float) {
        val n = g.series.maxOfOrNull { it.values.size } ?: return
        if (n < 3) return
        val all = g.series.flatMap { it.values }.filterNotNull()
        val sc = scaleFor(0.0, all.maxOrNull() ?: 1.0, model.valAxis, false)
        val labH = text.fontSpacing
        val r = min(area.width(), area.height()) / 2f - labH * 1.5f
        if (r <= 0f) return
        val cx = area.centerX()
        val cy = area.centerY()
        fun pt(i: Int, v: Double): Pair<Float, Float> {
            val a = Math.toRadians(-90.0 + 360.0 * i / n)
            val rr = r * ((v - sc.lo) / (sc.hi - sc.lo)).toFloat()
            return (cx + cos(a) * rr).toFloat() to (cy + sin(a) * rr).toFloat()
        }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD9, 0xD9, 0xD9); style = Paint.Style.STROKE; strokeWidth = max(1f, 0.75f * pxPerPt) }
        var v = sc.lo + sc.unit
        var guard = 0
        while (v <= sc.hi + 1e-9 && guard++ < 50) {
            val p = Path()
            for (i in 0..n) { val q = pt(i % n, v); if (i == 0) p.moveTo(q.first, q.second) else p.lineTo(q.first, q.second) }
            canvas.drawPath(p, grid)
            v += sc.unit
        }
        val cats = g.series.first().categories
        for (i in 0 until n) {
            val q = pt(i, sc.hi)
            canvas.drawLine(cx, cy, q.first, q.second, grid)
            val lab = cats.getOrElse(i) { "${i + 1}" }
            val a = Math.toRadians(-90.0 + 360.0 * i / n)
            val lx = (cx + cos(a) * (r + labH * 0.8f)).toFloat()
            val ly = (cy + sin(a) * (r + labH * 0.8f)).toFloat()
            canvas.drawText(lab, lx - text.measureText(lab) / 2f, ly - (text.ascent() + text.descent()) / 2f, text)
        }
        for ((si, s) in g.series.withIndex()) {
            val color = seriesColor(s, si, model, g)
            val p = Path()
            var first = true
            for (i in 0 until n) {
                val q = pt(i, s.values.getOrNull(i) ?: 0.0)
                if (first) { p.moveTo(q.first, q.second); first = false } else p.lineTo(q.first, q.second)
            }
            p.close()
            if (g.radarStyle == "filled") canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = s.fillColor ?: color })
            canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = max(1f, (s.lineWidthPt ?: 2.25f) * pxPerPt) })
        }
    }

    fun typefaceFor(model: ChartModel): Typeface = OfficeFonts.resolve(model.fontFamily, false, false)
}

fun interface ChartCellResolver {
    fun cell(sheet: String?, row: Int, col: Int): Any?
}

object ChartReferenceData {
    private val CELL = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})$")

    private val done = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DmlNode, Boolean>())

    fun fill(space: DmlNode, resolver: ChartCellResolver) {
        synchronized(done) {
            if (!done.add(space)) return
            walk(space, resolver, 0)
        }
    }

    private fun walk(n: DmlNode, resolver: ChartCellResolver, depth: Int) {
        if (depth > 40) return
        for (c in n.children.toList()) {
            when (c.name) {
                "numRef" -> if (c.child("numCache")?.all("pt").isNullOrEmpty()) build(c, resolver, n.name == "val" || n.name == "yVal" || n.name == "bubbleSize")
                "strRef" -> if (c.child("strCache")?.all("pt").isNullOrEmpty()) build(c, resolver, false)
                else -> walk(c, resolver, depth + 1)
            }
        }
    }

    private fun build(ref: DmlNode, resolver: ChartCellResolver, numeric: Boolean) {
        val f = ref.child("f")?.text?.trim().orEmpty()
        if (f.isEmpty()) return
        val values = ArrayList<Any?>()
        for (part in splitUnion(f)) {
            if (values.size >= 100_000) break
            values.addAll(range(part, resolver))
        }
        if (values.isEmpty()) return
        val cacheName = if (ref.name == "numRef") "numCache" else "strCache"
        val kids = ArrayList<DmlNode>()
        kids.add(DmlNode("ptCount", mapOf("val" to values.size.toString())))
        for ((i, v) in values.withIndex()) {
            val s = when (v) {
                null -> continue
                is Double -> if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
                else -> v.toString().takeIf { it.isNotEmpty() } ?: continue
            }
            if (numeric && s.toDoubleOrNull() == null) continue
            val vn = DmlNode("v", emptyMap())
            vn.text = s
            kids.add(DmlNode("pt", mapOf("idx" to i.toString()), mutableListOf(vn)))
        }
        ref.children.removeAll { it.name == cacheName }
        ref.children.add(DmlNode(cacheName, emptyMap(), kids))
    }

    private fun splitUnion(f: String): List<String> {
        val body = f.removePrefix("(").removeSuffix(")")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in body) {
            when {
                ch == '\'' -> { quoted = !quoted; sb.append(ch) }
                ch == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun colIndex(s: String): Int {
        var v = 0
        for (ch in s.uppercase()) v = v * 26 + (ch - 'A' + 1)
        return v - 1
    }

    private fun range(part: String, resolver: ChartCellResolver): List<Any?> {
        val bang = part.lastIndexOf('!')
        val sheet = if (bang > 0) part.substring(0, bang).trim().removeSurrounding("'").replace("''", "'") else null
        val area = if (bang >= 0) part.substring(bang + 1) else part
        val ends = area.split(':')
        val a = CELL.matchEntire(ends[0].trim()) ?: return emptyList()
        val b = if (ends.size > 1) CELL.matchEntire(ends[1].trim()) ?: return emptyList() else a
        val c0 = colIndex(a.groupValues[1]); val r0 = a.groupValues[2].toInt() - 1
        val c1 = colIndex(b.groupValues[1]); val r1 = b.groupValues[2].toInt() - 1
        val rr = minOf(r0, r1)..maxOf(r0, r1)
        val cc = minOf(c0, c1)..maxOf(c0, c1)
        if ((rr.last - rr.first + 1).toLong() * (cc.last - cc.first + 1) > 100_000L) return emptyList()
        val out = ArrayList<Any?>()
        for (r in rr) for (c in cc) out.add(resolver.cell(sheet, r, c))
        return out
    }
}
