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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class StepRef(val id: Int)
internal class StepEnum(val v: String)
internal class StepTyped(val type: String, val args: List<Any?>)

internal class StepEntity(val id: Int, val parts: Map<String, List<Any?>>) {
    val type: String = parts.keys.firstOrNull() ?: ""
    val args: List<Any?> get() = parts.values.firstOrNull() ?: emptyList()
    fun part(t: String): List<Any?>? = parts[t]
    fun isA(t: String) = parts.containsKey(t)
}

internal object StepIsoLexer {
    fun parse(text: String): Map<Int, StepEntity> {
        val map = HashMap<Int, StepEntity>()
        val data = text.indexOf("DATA;").let { if (it < 0) 0 else it + 5 }
        var i = data
        val n = text.length
        while (i < n) {
            val hash = text.indexOf('#', i)
            if (hash < 0) break
            val eq = text.indexOf('=', hash)
            if (eq < 0) break
            val id = text.substring(hash + 1, eq).trim().toIntOrNull()
            if (id == null) {
                i = hash + 1
                continue
            }
            val end = statementEnd(text, eq + 1)
            if (end < 0) break
            val body = text.substring(eq + 1, end).trim()
            try {
                val c = Cursor(body)
                c.ws()
                val parts = LinkedHashMap<String, List<Any?>>()
                if (c.peek() == '(') {
                    c.i++
                    while (true) {
                        c.ws()
                        if (c.peek() == ')' || c.done()) break
                        val name = c.ident()
                        c.ws()
                        parts[name] = c.list()
                    }
                } else {
                    val name = c.ident()
                    c.ws()
                    parts[name] = c.list()
                }
                map[id] = StepEntity(id, parts)
            } catch (_: Throwable) {}
            i = end + 1
        }
        return map
    }

    private fun statementEnd(t: String, from: Int): Int {
        var i = from
        var inStr = false
        while (i < t.length) {
            val ch = t[i]
            if (inStr) {
                if (ch == '\'') {
                    if (i + 1 < t.length && t[i + 1] == '\'') i++ else inStr = false
                }
            } else if (ch == '\'') {
                inStr = true
            } else if (ch == ';') {
                return i
            } else if (ch == '/' && i + 1 < t.length && t[i + 1] == '*') {
                val e = t.indexOf("*/", i + 2)
                if (e < 0) return -1
                i = e + 1
            }
            i++
        }
        return -1
    }

    private class Cursor(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        fun ident(): String {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
            return s.substring(st, i).uppercase()
        }
        fun list(): List<Any?> {
            ws()
            require(peek() == '(')
            i++
            val out = ArrayList<Any?>()
            while (true) {
                ws()
                if (peek() == ')') {
                    i++
                    break
                }
                if (done()) break
                out.add(value())
                ws()
                if (peek() == ',') i++
            }
            return out
        }
        fun value(): Any? {
            ws()
            val c = peek()
            return when {
                c == '(' -> list()
                c == '#' -> {
                    i++
                    val st = i
                    while (i < s.length && s[i].isDigit()) i++
                    StepRef(s.substring(st, i).toInt())
                }
                c == '\'' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length) {
                        if (s[i] == '\'') {
                            if (i + 1 < s.length && s[i + 1] == '\'') {
                                sb.append('\'')
                                i += 2
                                continue
                            }
                            i++
                            break
                        }
                        sb.append(s[i++])
                    }
                    sb.toString()
                }
                c == '.' -> {
                    i++
                    val st = i
                    while (i < s.length && s[i] != '.') i++
                    val v = s.substring(st, i)
                    i++
                    StepEnum(v.uppercase())
                }
                c == '$' || c == '*' -> {
                    i++
                    null
                }
                c == '-' || c == '+' || c.isDigit() -> {
                    val st = i
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'E' || s[i] == 'e' || ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'E' || s[i - 1] == 'e')))) i++
                    val t = s.substring(st, i)
                    if (t.contains('.') || t.contains('E') || t.contains('e')) t.toDoubleOrNull() ?: 0.0 else t.toLongOrNull() ?: 0L
                }
                c == '"' -> {
                    i++
                    val st = i
                    while (i < s.length && s[i] != '"') i++
                    i++
                    s.substring(st, i - 1)
                }
                c.isLetter() -> {
                    val name = ident()
                    ws()
                    if (peek() == '(') StepTyped(name, list()) else StepEnum(name)
                }
                else -> {
                    i++
                    null
                }
            }
        }
    }
}

internal class StepParser(private val e: Map<Int, StepEntity>, private val out: GltfOutput) {

    companion object {
        fun parse(text: String): Map<Int, StepEntity> = StepIsoLexer.parse(text)
    }

    private var degrees = false
    private var tolerance = 0.01
    private val colorOf = HashMap<Int, Int>()

    private fun ent(v: Any?): StepEntity? = (v as? StepRef)?.let { e[it.id] }
    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is StepTyped -> num(v.args.firstOrNull())
        else -> 0.0
    }
    private fun list(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()
    private fun bool(v: Any?): Boolean = (v as? StepEnum)?.v == "T"

    private fun point(v: Any?): Vec3? {
        val en = ent(v) ?: return null
        val c = list(en.args.getOrNull(1))
        return Vec3(num(c.getOrNull(0)), num(c.getOrNull(1)), num(c.getOrNull(2)))
    }

    private fun dir(v: Any?): Vec3? {
        val en = ent(v) ?: return null
        val c = list(en.args.getOrNull(1))
        return Vec3(num(c.getOrNull(0)), num(c.getOrNull(1)), num(c.getOrNull(2)))
    }

    private fun frame(v: Any?): Frame {
        val en = ent(v) ?: return Frame(Vec3.ZERO, Vec3.Z, Vec3.X)
        val o = point(en.args.getOrNull(1)) ?: Vec3.ZERO
        val z = dir(en.args.getOrNull(2)) ?: Vec3.Z
        val x = dir(en.args.getOrNull(3))
        return Frame(o, z, x)
    }

    private fun angle(v: Double) = if (degrees) v * PI / 180.0 else v

    private fun nurbsCurve(en: StepEntity): NurbsCurve? {
        val bc = en.part("B_SPLINE_CURVE") ?: if (en.type == "B_SPLINE_CURVE_WITH_KNOTS") en.args.drop(1) else return null
        val kn = en.part("B_SPLINE_CURVE_WITH_KNOTS")?.let { if (en.type == "B_SPLINE_CURVE_WITH_KNOTS" && en.parts.size == 1) it.drop(6) else it } ?: return null
        val degree = num(bc.getOrNull(0)).toInt()
        val pts = list(bc.getOrNull(1)).mapNotNull { point(it) }.toTypedArray()
        val mults = list(kn.getOrNull(0)).map { num(it).toInt() }
        val ks = list(kn.getOrNull(1)).map { num(it) }
        val knots = ArrayList<Double>()
        for ((k, m) in ks.zip(mults)) repeat(m) { knots.add(k) }
        val w = en.part("RATIONAL_B_SPLINE_CURVE")?.let { list(it.getOrNull(0)).map { x -> num(x) }.toDoubleArray() }
        if (pts.size < 2 || knots.size != pts.size + degree + 1) return null
        return NurbsCurve(Nurbs(degree, knots.toDoubleArray(), w), pts)
    }

    private class CurveFn(val f: (Double) -> Vec3, val t0: Double, val t1: Double, val periodic: Boolean, val segments: Int)

    private fun curveFn(v: Any?, depth: Int = 0): CurveFn? {
        val en = ent(v) ?: return null
        if (depth > 8) return null
        return when {
            en.type == "LINE" -> {
                val p = point(en.args.getOrNull(1)) ?: return null
                val vec = ent(en.args.getOrNull(2))
                val d = (dir(vec?.args?.getOrNull(1)) ?: Vec3.X).norm() * num(vec?.args?.getOrNull(2)).let { if (it == 0.0) 1.0 else it }
                CurveFn({ t -> p + d * t }, 0.0, 1.0, false, 1)
            }
            en.type == "CIRCLE" -> {
                val f = frame(en.args.getOrNull(1))
                val r = num(en.args.getOrNull(2))
                CurveFn({ t -> f.toWorld(r * cos(t), r * sin(t), 0.0) }, 0.0, 2 * PI, true, 64)
            }
            en.type == "ELLIPSE" -> {
                val f = frame(en.args.getOrNull(1))
                val a = num(en.args.getOrNull(2))
                val b = num(en.args.getOrNull(3))
                CurveFn({ t -> f.toWorld(a * cos(t), b * sin(t), 0.0) }, 0.0, 2 * PI, true, 64)
            }
            en.isA("B_SPLINE_CURVE_WITH_KNOTS") || en.isA("B_SPLINE_CURVE") -> {
                val c = nurbsCurve(en) ?: return null
                CurveFn({ t -> c.eval(t) }, c.basis.start, c.basis.end, false, (c.points.size * 8).coerceIn(8, 512))
            }
            en.type == "SURFACE_CURVE" || en.type == "SEAM_CURVE" || en.type == "INTERSECTION_CURVE" -> curveFn(en.args.getOrNull(1), depth + 1)
            en.type == "TRIMMED_CURVE" -> curveFn(en.args.getOrNull(1), depth + 1)
            en.type == "POLYLINE" -> {
                val pts = list(en.args.getOrNull(1)).mapNotNull { point(it) }
                if (pts.size < 2) return null
                CurveFn({ t ->
                    val k = t.coerceIn(0.0, (pts.size - 1).toDouble())
                    val i = k.toInt().coerceAtMost(pts.size - 2)
                    val f = k - i
                    pts[i] * (1 - f) + pts[i + 1] * f
                }, 0.0, (pts.size - 1).toDouble(), false, pts.size - 1)
            }
            else -> null
        }
    }

    private fun nearestParam(c: CurveFn, p: Vec3): Double {
        val n = (c.segments * 4).coerceIn(16, 2048)
        var best = c.t0
        var bd = Double.MAX_VALUE
        for (i in 0..n) {
            val t = c.t0 + (c.t1 - c.t0) * i / n
            val d = (c.f(t) - p).let { it.dot(it) }
            if (d < bd) { bd = d; best = t }
        }
        var lo = best - (c.t1 - c.t0) / n
        var hi = best + (c.t1 - c.t0) / n
        if (!c.periodic) {
            lo = lo.coerceAtLeast(c.t0)
            hi = hi.coerceAtMost(c.t1)
        }
        repeat(40) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            if ((c.f(m1) - p).len() < (c.f(m2) - p).len()) hi = m2 else lo = m1
        }
        return (lo + hi) / 2
    }

    private fun edgePoints(edge: StepEntity): List<Vec3> {
        val vs = point(ent(edge.args.getOrNull(1))?.args?.getOrNull(1))
        val ve = point(ent(edge.args.getOrNull(2))?.args?.getOrNull(1))
        val sameSense = (edge.args.getOrNull(4) as? StepEnum)?.v != "F"
        val c = curveFn(edge.args.getOrNull(3))
        if (c == null || vs == null || ve == null) return listOfNotNull(vs, ve)
        if (c.segments == 1) return listOf(vs, ve)
        var ts = nearestParam(c, vs)
        var te = nearestParam(c, ve)
        val closed = (vs - ve).len() < tolerance * 0.01
        if (sameSense) {
            if (c.periodic) {
                while (te <= ts + 1e-12) te += (c.t1 - c.t0)
                if (closed) te = ts + (c.t1 - c.t0)
            } else if (closed) {
                ts = c.t0
                te = c.t1
            }
        } else {
            if (c.periodic) {
                while (te >= ts - 1e-12) te -= (c.t1 - c.t0)
                if (closed) te = ts - (c.t1 - c.t0)
            } else if (closed) {
                ts = c.t1
                te = c.t0
            }
        }
        val span = abs(te - ts) / (c.t1 - c.t0).coerceAtLeast(1e-300)
        val n = ceil(c.segments * span.coerceAtMost(1.0)).toInt().coerceIn(1, 1024)
        val pts = ArrayList<Vec3>(n + 1)
        pts.add(vs)
        for (i in 1 until n) pts.add(c.f(ts + (te - ts) * i / n))
        pts.add(ve)
        return pts
    }

    private fun loopPoints(bound: StepEntity): List<Vec3>? {
        val loop = ent(bound.args.getOrNull(1)) ?: return null
        val orientation = (bound.args.getOrNull(2) as? StepEnum)?.v != "F"
        val pts = ArrayList<Vec3>()
        when (loop.type) {
            "EDGE_LOOP" -> for (oeRef in list(loop.args.getOrNull(1))) {
                val oe = ent(oeRef) ?: continue
                val edge = ent(oe.args.getOrNull(3)) ?: continue
                var ep = edgePoints(edge)
                if ((oe.args.getOrNull(4) as? StepEnum)?.v == "F") ep = ep.reversed()
                for ((k, p) in ep.withIndex()) {
                    if (k == 0 && pts.isNotEmpty() && (pts.last() - p).len() < tolerance * 1e-3) continue
                    pts.add(p)
                }
            }
            "POLY_LOOP" -> pts.addAll(list(loop.args.getOrNull(1)).mapNotNull { point(it) })
            else -> return null
        }
        if (pts.size > 1 && (pts.first() - pts.last()).len() < tolerance * 1e-3) pts.removeAt(pts.size - 1)
        return if (orientation) pts else pts.reversed()
    }

    private fun surface(v: Any?, depth: Int = 0): BrepSurface? {
        val en = ent(v) ?: return null
        if (depth > 6) return null
        return when {
            en.type == "PLANE" -> PlaneSurface(frame(en.args.getOrNull(1)))
            en.type == "CYLINDRICAL_SURFACE" -> CylinderSurface(frame(en.args.getOrNull(1)), num(en.args.getOrNull(2)))
            en.type == "CONICAL_SURFACE" -> ConeSurface(frame(en.args.getOrNull(1)), num(en.args.getOrNull(2)), angle(num(en.args.getOrNull(3))))
            en.type == "SPHERICAL_SURFACE" -> SphereSurface(frame(en.args.getOrNull(1)), num(en.args.getOrNull(2)))
            en.type == "TOROIDAL_SURFACE" || en.type == "DEGENERATE_TOROIDAL_SURFACE" -> TorusSurface(frame(en.args.getOrNull(1)), num(en.args.getOrNull(2)), num(en.args.getOrNull(3)))
            en.isA("B_SPLINE_SURFACE_WITH_KNOTS") || en.isA("B_SPLINE_SURFACE") -> nurbsSurface(en)
            en.type == "SURFACE_OF_REVOLUTION" -> {
                val c = curveFn(en.args.getOrNull(1)) ?: return null
                val ax = ent(en.args.getOrNull(2)) ?: return null
                RevolutionSurface(c.f, c.t0, c.t1, point(ax.args.getOrNull(1)) ?: Vec3.ZERO, dir(ax.args.getOrNull(2)) ?: Vec3.Z)
            }
            en.type == "SURFACE_OF_LINEAR_EXTRUSION" -> {
                val c = curveFn(en.args.getOrNull(1)) ?: return null
                val vec = ent(en.args.getOrNull(2))
                val d = (dir(vec?.args?.getOrNull(1)) ?: Vec3.Z).norm() * num(vec?.args?.getOrNull(2)).let { if (it == 0.0) 1.0 else it }
                ExtrusionSurface(c.f, c.t0, c.t1, d)
            }
            en.type == "RECTANGULAR_TRIMMED_SURFACE" || en.type == "CURVE_BOUNDED_SURFACE" -> surface(en.args.getOrNull(1), depth + 1)
            else -> null
        }
    }

    private fun nurbsSurface(en: StepEntity): NurbsSurface? {
        val bs = en.part("B_SPLINE_SURFACE") ?: if (en.type == "B_SPLINE_SURFACE_WITH_KNOTS") en.args.drop(1) else return null
        val kn = en.part("B_SPLINE_SURFACE_WITH_KNOTS")?.let { if (en.type == "B_SPLINE_SURFACE_WITH_KNOTS" && en.parts.size == 1) it.drop(8) else it } ?: return null
        val ud = num(bs.getOrNull(0)).toInt()
        val vd = num(bs.getOrNull(1)).toInt()
        val rows = list(bs.getOrNull(2)).map { r -> list(r).mapNotNull { point(it) } }
        val nu = rows.size
        val nv = rows.firstOrNull()?.size ?: 0
        if (nu < 2 || nv < 2 || rows.any { it.size != nv }) return null
        fun expand(m: Any?, k: Any?): DoubleArray {
            val out = ArrayList<Double>()
            for ((kk, mm) in list(k).map { num(it) }.zip(list(m).map { num(it).toInt() })) repeat(mm) { out.add(kk) }
            return out.toDoubleArray()
        }
        val uk = expand(kn.getOrNull(0), kn.getOrNull(2))
        val vk = expand(kn.getOrNull(1), kn.getOrNull(3))
        if (uk.size != nu + ud + 1 || vk.size != nv + vd + 1) return null
        val pts = Array(nu * nv) { rows[it % nu][it / nu] }
        val w = en.part("RATIONAL_B_SPLINE_SURFACE")?.let { r ->
            val wr = list(r.getOrNull(0)).map { row -> list(row).map { num(it) } }
            DoubleArray(nu * nv) { wr.getOrNull(it % nu)?.getOrNull(it / nu) ?: 1.0 }
        }
        return NurbsSurface(Nurbs(ud, uk, null), Nurbs(vd, vk, null), nu, nv, pts, w)
    }

    private fun projectLoop(s: BrepSurface, pts: List<Vec3>): List<DoubleArray> {
        var hint: DoubleArray? = null
        val raw = pts.map { p -> s.project(p, hint).also { hint = it } }
        return BrepTessellator.unwrap(raw, s.uPeriod, s.vPeriod)
    }

    fun tessellateFace(face: StepEntity, xf: DoubleArray, color: Int) {
        val s = surface(face.args.getOrNull(2)) ?: return planarFallback(face, xf, color)
        val bounds = list(face.args.getOrNull(1)).mapNotNull { ent(it) }
        val loops = ArrayList<Pair<List<DoubleArray>, Boolean>>()
        for (b in bounds) {
            val pts = loopPoints(b) ?: continue
            if (pts.size < 2) continue
            loops.add(projectLoop(s, pts) to (b.type == "FACE_OUTER_BOUND"))
        }
        val mesh = BrepTessellator.Mesh()
        val period = s.uPeriod
        if (loops.isEmpty()) {
            if (s is SphereSurface) {
                band(s, doubleArrayOf(0.0, -PI / 2), doubleArrayOf(2 * PI, PI / 2), mesh)
            } else if (s is TorusSurface) {
                band(s, doubleArrayOf(0.0, 0.0), doubleArrayOf(2 * PI, 2 * PI), mesh)
            } else return
        } else {
            val openBands = if (period > 0) loops.filter { (l, _) -> l.size >= 2 && abs(abs(l.last()[0] - l.first()[0]) - period) < 0.05 * period } else emptyList()
            if (openBands.size >= 2) {
                val a = openBands[0].first.let { if (it.last()[0] < it.first()[0]) it.reversed() else it }
                var b = openBands[1].first.let { if (it.last()[0] < it.first()[0]) it.reversed() else it }
                val shift = Math.round((a.first()[0] - b.first()[0]) / period) * period
                b = b.map { doubleArrayOf(it[0] + shift, it[1]) }
                val ring = ArrayList<DoubleArray>(a)
                ring.addAll(b.reversed())
                BrepTessellator.tessellate(s, ring, emptyList(), tolerance, mesh)
            } else if (openBands.size == 1 && s is SphereSurface) {
                val a = openBands[0].first.let { if (it.last()[0] < it.first()[0]) it.reversed() else it }
                val pole = if (a.map { it[1] }.average() > 0) -PI / 2 else PI / 2
                val ring = ArrayList<DoubleArray>(a)
                ring.add(doubleArrayOf(a.last()[0], pole))
                ring.add(doubleArrayOf(a.first()[0], pole))
                BrepTessellator.tessellate(s, ring, emptyList(), tolerance, mesh)
            } else {
                val closed = loops.filter { it.first.size >= 3 }
                if (closed.isEmpty()) return
                val outerIdx = closed.indexOfFirst { it.second }.takeIf { it >= 0 } ?: closed.indices.maxByOrNull { i -> abs(polyArea(closed[i].first)) }!!
                val outer = closed[outerIdx].first
                val holes = closed.filterIndexed { i, _ -> i != outerIdx }.map { (h, _) ->
                    if (period > 0) {
                        val shift = Math.round((outer.map { it[0] }.average() - h.map { it[0] }.average()) / period) * period
                        h.map { doubleArrayOf(it[0] + shift, it[1]) }
                    } else h
                }
                BrepTessellator.tessellate(s, outer, holes, tolerance, mesh)
            }
        }
        emit(mesh, xf, color)
    }

    private fun polyArea(p: List<DoubleArray>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i][0] * q[1] - q[0] * p[i][1]
        }
        return a / 2
    }

    private fun band(s: BrepSurface, lo: DoubleArray, hi: DoubleArray, mesh: BrepTessellator.Mesh) {
        val n = 32
        val ring = ArrayList<DoubleArray>()
        for (i in 0..n) ring.add(doubleArrayOf(lo[0] + (hi[0] - lo[0]) * i / n, lo[1]))
        for (i in 0..n) ring.add(doubleArrayOf(hi[0], lo[1] + (hi[1] - lo[1]) * i / n))
        for (i in n downTo 0) ring.add(doubleArrayOf(lo[0] + (hi[0] - lo[0]) * i / n, hi[1]))
        for (i in n downTo 0) ring.add(doubleArrayOf(lo[0], lo[1] + (hi[1] - lo[1]) * i / n))
        BrepTessellator.tessellate(s, ring, emptyList(), tolerance, mesh)
    }

    private fun planarFallback(face: StepEntity, xf: DoubleArray, color: Int) {
        val bounds = list(face.args.getOrNull(1)).mapNotNull { ent(it) }
        val loops = bounds.mapNotNull { b -> loopPoints(b)?.takeIf { it.size >= 3 }?.let { it to (b.type == "FACE_OUTER_BOUND") } }
        if (loops.isEmpty()) return
        val outer3 = loops.firstOrNull { it.second }?.first ?: loops.maxByOrNull { it.first.size }!!.first
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in outer3.indices) {
            val a = outer3[i]
            val b = outer3[(i + 1) % outer3.size]
            nx += (a.y - b.y) * (a.z + b.z); ny += (a.z - b.z) * (a.x + b.x); nz += (a.x - b.x) * (a.y + b.y)
        }
        val f = Frame(outer3[0], Vec3(nx, ny, nz).takeIf { it.len() > 1e-300 } ?: Vec3.Z, null)
        val plane = PlaneSurface(f)
        val outer = outer3.map { plane.project(it, null) }
        val holes = loops.filter { it.first !== outer3 }.map { (pts, _) -> pts.map { plane.project(it, null) } }
        val mesh = BrepTessellator.Mesh()
        BrepTessellator.tessellate(plane, outer, holes, tolerance, mesh)
        emit(mesh, xf, color)
    }

    private val tri = FloatArray(9)
    private val nrm = FloatArray(9)

    private fun emit(mesh: BrepTessellator.Mesh, xf: DoubleArray, color: Int) {
        for (t in mesh.triangles) {
            if (out.truncated) return
            var any = false
            for (k in 0 until 3) {
                val p = Mat4d.apply(xf, mesh.positions[t[k]])
                tri[k * 3] = p.x.toFloat(); tri[k * 3 + 1] = p.y.toFloat(); tri[k * 3 + 2] = p.z.toFloat()
                val n = mesh.normals.getOrNull(t[k]) ?: Vec3.ZERO
                val nx = xf[0] * n.x + xf[4] * n.y + xf[8] * n.z
                val ny = xf[1] * n.x + xf[5] * n.y + xf[9] * n.z
                val nz = xf[2] * n.x + xf[6] * n.y + xf[10] * n.z
                nrm[k * 3] = nx.toFloat(); nrm[k * 3 + 1] = ny.toFloat(); nrm[k * 3 + 2] = nz.toFloat()
                if (n !== Vec3.ZERO) any = true
            }
            out.triangleN(tri, color, null, -1, 0, 0, 0, if (any) nrm else null)
        }
    }

    private fun tessellatedItem(en: StepEntity, xf: DoubleArray, color: Int) {
        val coords = ent(en.args.getOrNull(1)) ?: return
        val pts = list(coords.args.getOrNull(2)).map { c -> list(c).let { Vec3(num(it.getOrNull(0)), num(it.getOrNull(1)), num(it.getOrNull(2))) } }
        val face = en.type.contains("FACE")
        val pnIndex = list(en.args.getOrNull(if (face) 5 else 4)).map { num(it).toInt() }
        fun p(i: Int): Vec3? {
            val idx = if (pnIndex.isNotEmpty()) pnIndex.getOrNull(i - 1) ?: return null else i
            return pts.getOrNull(idx - 1)
        }
        val tris = ArrayList<IntArray>()
        if (en.type.startsWith("COMPLEX_")) {
            val strips = list(en.args.getOrNull(if (face) 6 else 5))
            val fans = list(en.args.getOrNull(if (face) 7 else 6))
            for (s in strips) {
                val ix = list(s).map { num(it).toInt() }
                for (k in 0 until ix.size - 2) tris.add(if (k % 2 == 0) intArrayOf(ix[k], ix[k + 1], ix[k + 2]) else intArrayOf(ix[k + 1], ix[k], ix[k + 2]))
            }
            for (f in fans) {
                val ix = list(f).map { num(it).toInt() }
                for (k in 1 until ix.size - 1) tris.add(intArrayOf(ix[0], ix[k], ix[k + 1]))
            }
        } else {
            for (t in list(en.args.getOrNull(if (face) 6 else 5))) {
                val ix = list(t).map { num(it).toInt() }
                if (ix.size >= 3) tris.add(intArrayOf(ix[0], ix[1], ix[2]))
            }
        }
        for (t in tris) {
            if (out.truncated) return
            val a = p(t[0]) ?: continue
            val b = p(t[1]) ?: continue
            val c = p(t[2]) ?: continue
            for ((k, v) in listOf(a, b, c).withIndex()) {
                val w = Mat4d.apply(xf, v)
                tri[k * 3] = w.x.toFloat(); tri[k * 3 + 1] = w.y.toFloat(); tri[k * 3 + 2] = w.z.toFloat()
            }
            out.triangle(tri, color, null, -1, 0, 0, 0)
        }
    }

    private fun colorFromStyle(v: Any?, depth: Int = 0): Int? {
        if (depth > 12) return null
        when (v) {
            is List<*> -> { for (x in v) colorFromStyle(x, depth + 1)?.let { return it }; return null }
            is StepTyped -> return colorFromStyle(v.args, depth + 1)
        }
        val en = ent(v) ?: return null
        if (en.type == "COLOUR_RGB") {
            fun ch(x: Any?) = (num(x).coerceIn(0.0, 1.0) * 255).roundToInt()
            return (0xFF shl 24) or (ch(en.args.getOrNull(1)) shl 16) or (ch(en.args.getOrNull(2)) shl 8) or ch(en.args.getOrNull(3))
        }
        if (en.type == "DRAUGHTING_PRE_DEFINED_COLOUR") {
            return when ((en.args.getOrNull(0) as? String)?.lowercase()) {
                "red" -> 0xFFFF0000.toInt(); "green" -> 0xFF00FF00.toInt(); "blue" -> 0xFF0000FF.toInt()
                "yellow" -> 0xFFFFFF00.toInt(); "magenta" -> 0xFFFF00FF.toInt(); "cyan" -> 0xFF00FFFF.toInt()
                "black" -> 0xFF202020.toInt(); "white" -> 0xFFFFFFFF.toInt(); else -> null
            }
        }
        if (en.type == "SURFACE_STYLE_RENDERING" || en.type == "SURFACE_STYLE_RENDERING_WITH_PROPERTIES") return colorFromStyle(en.args.getOrNull(1), depth + 1)
        if (en.type == "CURVE_STYLE") return null
        for (a in en.args) colorFromStyle(a, depth + 1)?.let { return it }
        return null
    }

    private fun collectColors() {
        for (en in e.values) {
            if (en.type != "STYLED_ITEM" && en.type != "OVER_RIDING_STYLED_ITEM") continue
            val item = (en.args.getOrNull(2) as? StepRef)?.id ?: continue
            val c = colorFromStyle(en.args.getOrNull(1)) ?: continue
            if (en.type == "OVER_RIDING_STYLED_ITEM" || !colorOf.containsKey(item)) colorOf[item] = c
        }
    }

    private fun placementMatrix(v: Any?): DoubleArray = frame(v).matrix()

    private fun repTransforms(): Map<Int, List<DoubleArray>> {
        val children = HashMap<Int, MutableList<Pair<Int, DoubleArray>>>()
        val hasParent = HashSet<Int>()
        for (en in e.values) {
            val rr = en.part("REPRESENTATION_RELATIONSHIP") ?: (if (en.type == "SHAPE_REPRESENTATION_RELATIONSHIP") en.args else null) ?: continue
            val r1 = (rr.getOrNull(2) as? StepRef)?.id ?: continue
            val r2 = (rr.getOrNull(3) as? StepRef)?.id ?: continue
            val wt = en.part("REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION")
            var m = Mat4d.IDENTITY
            if (wt != null) {
                val idt = ent(wt.getOrNull(0))
                if (idt != null && idt.type == "ITEM_DEFINED_TRANSFORMATION") {
                    m = Mat4d.mul(placementMatrix(idt.args.getOrNull(3)), Mat4d.invertRigid(placementMatrix(idt.args.getOrNull(2))))
                }
            }
            children.getOrPut(r2) { ArrayList() }.add(r1 to m)
            hasParent.add(r1)
        }
        val reps = e.values.filter { it.type.endsWith("SHAPE_REPRESENTATION") || it.type == "SHAPE_REPRESENTATION" || it.isA("SHAPE_REPRESENTATION") }.map { it.id }
        val result = HashMap<Int, MutableList<DoubleArray>>()
        fun walk(rep: Int, m: DoubleArray, depth: Int) {
            if (depth > 32) return
            result.getOrPut(rep) { ArrayList() }.add(m)
            for ((c, cm) in children[rep] ?: emptyList()) walk(c, Mat4d.mul(m, cm), depth + 1)
        }
        val roots = (reps + children.keys).filter { it !in hasParent }.distinct()
        for (r in roots) walk(r, Mat4d.IDENTITY, 0)
        for (r in reps) if (r !in result) result[r] = mutableListOf(Mat4d.IDENTITY)
        return result
    }

    fun load(): Boolean {
        degrees = e.values.any { en -> en.isA("CONVERSION_BASED_UNIT") && en.isA("PLANE_ANGLE_UNIT") && (en.part("CONVERSION_BASED_UNIT")?.getOrNull(0) as? String)?.uppercase()?.contains("DEG") == true }
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (en in e.values) if (en.type == "CARTESIAN_POINT") {
            val c = list(en.args.getOrNull(1))
            if (c.size < 3) continue
            val x = num(c[0]); val y = num(c[1]); val z = num(c[2])
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y); minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        val diag = Vec3(maxX - minX, maxY - minY, maxZ - minZ).len().takeIf { it.isFinite() && it > 0 } ?: 1.0
        tolerance = diag * 0.0015
        collectColors()
        val transforms = repTransforms()
        var emitted = false
        val handled = HashSet<Int>()
        for (en in e.values) {
            if (out.truncated) break
            if (!(en.type.endsWith("SHAPE_REPRESENTATION") || en.isA("SHAPE_REPRESENTATION"))) continue
            val items = list((en.part("SHAPE_REPRESENTATION") ?: en.args).getOrNull(1))
            val xfs = transforms[en.id] ?: listOf(Mat4d.IDENTITY)
            for (it in items) {
                val item = ent(it) ?: continue
                for (xf in xfs) if (emitItem(item, xf, null, handled)) emitted = true
            }
        }
        if (!emitted) {
            for (en in e.values) {
                if (out.truncated) break
                if (en.type != "EDGE_CURVE") continue
                val pts = edgePoints(en)
                for (k in 0 until pts.size - 1) {
                    out.line(pts[k].x.toFloat(), pts[k].y.toFloat(), pts[k].z.toFloat(), pts[k + 1].x.toFloat(), pts[k + 1].y.toFloat(), pts[k + 1].z.toFloat(), colorOf[en.id] ?: 0)
                }
            }
            for (en in e.values) {
                if (out.truncated) break
                if (en.type in setOf("ADVANCED_FACE", "FACE_SURFACE")) {
                    tessellateFace(en, Mat4d.IDENTITY, colorOf[en.id] ?: 0)
                    emitted = true
                } else if (en.type.contains("TRIANGULATED_") && en.id !in handled) {
                    tessellatedItem(en, Mat4d.IDENTITY, colorOf[en.id] ?: 0)
                    emitted = true
                }
            }
        }
        return emitted
    }

    private fun emitItem(item: StepEntity, xf: DoubleArray, inherited: Int?, handled: HashSet<Int>, depth: Int = 0): Boolean {
        if (depth > 16 || out.truncated) return false
        val color = colorOf[item.id] ?: inherited ?: 0
        return when (item.type) {
            "MANIFOLD_SOLID_BREP", "FACETED_BREP", "BREP_WITH_VOIDS" -> {
                var any = false
                for (s in listOf(item.args.getOrNull(1)) + list(item.args.getOrNull(2))) {
                    val shell = ent(s) ?: continue
                    if (emitItem(shell, xf, color, handled, depth + 1)) any = true
                }
                any
            }
            "SHELL_BASED_SURFACE_MODEL" -> {
                var any = false
                for (s in list(item.args.getOrNull(1))) {
                    val shell = ent(s) ?: continue
                    if (emitItem(shell, xf, color, handled, depth + 1)) any = true
                }
                any
            }
            "CLOSED_SHELL", "OPEN_SHELL", "ORIENTED_CLOSED_SHELL" -> {
                val faces = if (item.type == "ORIENTED_CLOSED_SHELL") ent(item.args.getOrNull(2))?.let { list(it.args.getOrNull(1)) } ?: emptyList() else list(item.args.getOrNull(1))
                for (f in faces) {
                    val face = ent(f) ?: continue
                    val fc = colorOf[face.id] ?: color
                    when {
                        face.type == "ADVANCED_FACE" || face.type == "FACE_SURFACE" -> tessellateFace(face, xf, fc)
                        face.type == "FACE" || face.type == "ORIENTED_FACE" -> planarFallback(face, xf, fc)
                        face.type.contains("TRIANGULATED_FACE") -> { handled.add(face.id); tessellatedItem(face, xf, fc) }
                    }
                }
                faces.isNotEmpty()
            }
            "MAPPED_ITEM" -> {
                val map = ent(item.args.getOrNull(1)) ?: return false
                val origin = placementMatrix(map.args.getOrNull(0))
                val target = placementMatrix(item.args.getOrNull(2))
                val rep = ent(map.args.getOrNull(1)) ?: return false
                val m = Mat4d.mul(xf, Mat4d.mul(target, Mat4d.invertRigid(origin)))
                var any = false
                for (it in list(rep.args.getOrNull(1))) ent(it)?.let { if (emitItem(it, m, color, handled, depth + 1)) any = true }
                any
            }
            "TESSELLATED_SHELL", "TESSELLATED_SOLID" -> {
                var any = false
                for (it in list(item.args.getOrNull(1))) ent(it)?.let { if (emitItem(it, xf, color, handled, depth + 1)) any = true }
                any
            }
            else -> if (item.type.contains("TRIANGULATED_")) {
                handled.add(item.id)
                tessellatedItem(item, xf, color)
                true
            } else false
        }
    }
}
