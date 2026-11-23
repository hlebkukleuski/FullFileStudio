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
 * Evaluates ECMA-376 Part 1 Annex D DrawingML preset shape definitions
 * (assets/drawingml/presetShapeDefinitions.xml), Copyright Ecma International,
 * adapted and stripped from Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 * Modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.impl

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object DmlAssets {
    @Volatile
    var loader: ((String) -> ByteArray?)? = null

    fun bytes(name: String): ByteArray? = try { loader?.invoke(name) } catch (_: Throwable) { null }
}

class DmlSubPath(val path: Path, val fillMode: String, val stroke: Boolean)

class DmlGeometryResult(val paths: List<DmlSubPath>, val textRect: RectF, val isLineOnly: Boolean)

object DmlGeometry {
    private val presets: Map<String, DmlNode> by lazy {
        val root = DmlNode.parse(DmlAssets.bytes("drawingml/presetShapeDefinitions.xml"))
        root?.children?.associateBy { it.name } ?: emptyMap()
    }

    private val LINE_PRESETS = setOf("line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4", "bentConnector5",
        "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5", "arc", "leftBracket", "rightBracket",
        "leftBrace", "rightBrace", "bracketPair", "bracePair")

    fun hasPreset(name: String): Boolean = presets.containsKey(name)

    fun build(prst: String?, custGeom: DmlNode?, avOverride: DmlNode?, w: Float, h: Float): DmlGeometryResult {
        val def = custGeom ?: prst?.let { presets[it] }
        if (def == null) {
            val p = Path().apply { addRect(0f, 0f, w, h, Path.Direction.CW) }
            return DmlGeometryResult(listOf(DmlSubPath(p, "norm", true)), RectF(0f, 0f, w, h), false)
        }
        val vars = HashMap<String, Double>()
        val wd = w.toDouble()
        val hd = h.toDouble()
        vars["w"] = wd; vars["h"] = hd; vars["l"] = 0.0; vars["t"] = 0.0; vars["r"] = wd; vars["b"] = hd
        vars["hc"] = wd / 2; vars["vc"] = hd / 2
        val ss = min(wd, hd)
        val ls = max(wd, hd)
        vars["ss"] = ss; vars["ls"] = ls
        for (d in intArrayOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 32)) {
            vars["wd$d"] = wd / d; vars["hd$d"] = hd / d; vars["ssd$d"] = ss / d
        }
        vars["cd2"] = 10800000.0; vars["cd4"] = 5400000.0; vars["cd8"] = 2700000.0
        vars["3cd4"] = 16200000.0; vars["3cd8"] = 8100000.0; vars["5cd8"] = 13500000.0; vars["7cd8"] = 18900000.0
        val overrides = HashMap<String, String>()
        avOverride?.all("gd")?.forEach { g -> g.attr("name")?.let { n -> g.attr("fmla")?.let { overrides[n] = it } } }
        def.child("avLst")?.all("gd")?.forEach { g ->
            val n = g.attr("name") ?: return@forEach
            vars[n] = eval(overrides[n] ?: g.attr("fmla") ?: "val 0", vars)
        }
        for ((n, f) in overrides) if (!vars.containsKey(n)) vars[n] = eval(f, vars)
        def.child("gdLst")?.all("gd")?.forEach { g ->
            val n = g.attr("name") ?: return@forEach
            vars[n] = eval(g.attr("fmla") ?: "val 0", vars)
        }
        fun v(s: String?): Double = if (s == null) 0.0 else s.toDoubleOrNull() ?: vars[s] ?: 0.0
        val rect = def.child("rect")?.let {
            RectF(v(it.attr("l")).toFloat(), v(it.attr("t")).toFloat(), v(it.attr("r")).toFloat(), v(it.attr("b")).toFloat())
        } ?: RectF(0f, 0f, w, h)
        val out = ArrayList<DmlSubPath>()
        var anyClosed = false
        for (p in def.child("pathLst")?.all("path").orEmpty()) {
            val pw = p.attr("w")?.toDoubleOrNull() ?: 0.0
            val ph = p.attr("h")?.toDoubleOrNull() ?: 0.0
            val sx = if (pw > 0) wd / pw else 1.0
            val sy = if (ph > 0) hd / ph else 1.0
            val path = Path()
            var cx = 0.0
            var cy = 0.0
            var closed = false
            for (c in p.children) {
                when (c.name) {
                    "moveTo" -> c.child("pt")?.let {
                        cx = v(it.attr("x")) * sx; cy = v(it.attr("y")) * sy
                        path.moveTo(cx.toFloat(), cy.toFloat())
                    }
                    "lnTo" -> c.child("pt")?.let {
                        cx = v(it.attr("x")) * sx; cy = v(it.attr("y")) * sy
                        path.lineTo(cx.toFloat(), cy.toFloat())
                    }
                    "cubicBezTo" -> {
                        val pts = c.all("pt")
                        if (pts.size >= 3) {
                            val x1 = v(pts[0].attr("x")) * sx; val y1 = v(pts[0].attr("y")) * sy
                            val x2 = v(pts[1].attr("x")) * sx; val y2 = v(pts[1].attr("y")) * sy
                            cx = v(pts[2].attr("x")) * sx; cy = v(pts[2].attr("y")) * sy
                            path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), cx.toFloat(), cy.toFloat())
                        }
                    }
                    "quadBezTo" -> {
                        val pts = c.all("pt")
                        if (pts.size >= 2) {
                            val x1 = v(pts[0].attr("x")) * sx; val y1 = v(pts[0].attr("y")) * sy
                            cx = v(pts[1].attr("x")) * sx; cy = v(pts[1].attr("y")) * sy
                            path.quadTo(x1.toFloat(), y1.toFloat(), cx.toFloat(), cy.toFloat())
                        }
                    }
                    "arcTo" -> {
                        val wR = v(c.attr("wR")) * sx
                        val hR = v(c.attr("hR")) * sy
                        val st = v(c.attr("stAng")) / 60000.0
                        val sw = v(c.attr("swAng")) / 60000.0
                        val (nx, ny) = arcTo(path, cx, cy, wR, hR, st, sw)
                        cx = nx; cy = ny
                    }
                    "close" -> { path.close(); closed = true }
                }
            }
            val fill = p.attr("fill") ?: "norm"
            val stroke = p.attr("stroke")?.let { it != "0" && it != "false" } ?: true
            if (closed && fill != "none") anyClosed = true
            out.add(DmlSubPath(path, if (fill == "none") "none" else fill, stroke))
        }
        val lineOnly = !anyClosed || (prst != null && prst in LINE_PRESETS)
        return DmlGeometryResult(out, rect, lineOnly)
    }

    private fun arcTo(path: Path, cx: Double, cy: Double, wR: Double, hR: Double, stDeg: Double, swDeg: Double): Pair<Double, Double> {
        if (wR <= 0 || hR <= 0) return cx to cy
        fun param(aDeg: Double): Double {
            val a = Math.toRadians(aDeg)
            return Math.toDegrees(atan2(wR * sin(a), hR * cos(a)))
        }
        val t0 = param(stDeg)
        var t1 = param(stDeg + swDeg)
        if (abs(swDeg) >= 360.0) {
            t1 = t0 + if (swDeg > 0) 360.0 else -360.0
        } else if (swDeg > 0) {
            while (t1 < t0) t1 += 360.0
            while (t1 - t0 > 360.0) t1 -= 360.0
        } else if (swDeg < 0) {
            while (t1 > t0) t1 -= 360.0
            while (t0 - t1 > 360.0) t1 += 360.0
        } else {
            t1 = t0
        }
        val r0 = Math.toRadians(t0)
        val centerX = cx - wR * cos(r0)
        val centerY = cy - hR * sin(r0)
        val oval = RectF((centerX - wR).toFloat(), (centerY - hR).toFloat(), (centerX + wR).toFloat(), (centerY + hR).toFloat())
        val sweep = t1 - t0
        if (abs(sweep) >= 359.999) {
            path.arcTo(oval, t0.toFloat(), (sweep / 2).toFloat(), false)
            path.arcTo(oval, (t0 + sweep / 2).toFloat(), (sweep / 2).toFloat(), false)
        } else {
            path.arcTo(oval, t0.toFloat(), sweep.toFloat(), false)
        }
        val r1 = Math.toRadians(t1)
        return (centerX + wR * cos(r1)) to (centerY + hR * sin(r1))
    }

    fun eval(fmla: String, vars: Map<String, Double>): Double {
        val parts = fmla.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return 0.0
        fun a(i: Int): Double {
            val s = parts.getOrNull(i) ?: return 0.0
            return s.toDoubleOrNull() ?: vars[s] ?: 0.0
        }
        fun deg(x: Double) = Math.toRadians(x / 60000.0)
        return when (parts[0]) {
            "val" -> a(1)
            "*/" -> if (a(3) == 0.0) 0.0 else a(1) * a(2) / a(3)
            "+-" -> a(1) + a(2) - a(3)
            "+/" -> if (a(3) == 0.0) 0.0 else (a(1) + a(2)) / a(3)
            "?:" -> if (a(1) > 0) a(2) else a(3)
            "abs" -> abs(a(1))
            "at2" -> Math.toDegrees(atan2(a(2), a(1))) * 60000.0
            "cat2" -> a(1) * cos(atan2(a(3), a(2)))
            "sat2" -> a(1) * sin(atan2(a(3), a(2)))
            "cos" -> a(1) * cos(deg(a(2)))
            "sin" -> a(1) * sin(deg(a(2)))
            "tan" -> a(1) * tan(deg(a(2)))
            "max" -> max(a(1), a(2))
            "min" -> min(a(1), a(2))
            "mod" -> sqrt(a(1) * a(1) + a(2) * a(2) + a(3) * a(3))
            "pin" -> when {
                a(2) < a(1) -> a(1)
                a(2) > a(3) -> a(3)
                else -> a(2)
            }
            "sqrt" -> sqrt(max(0.0, a(1)))
            else -> 0.0
        }
    }
}
