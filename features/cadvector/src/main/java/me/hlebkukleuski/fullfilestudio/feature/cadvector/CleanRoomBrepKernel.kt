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
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): Vec3 {
        val l = len()
        return if (l > 1e-300) Vec3(x / l, y / l, z / l) else this
    }
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

internal class Frame(val origin: Vec3, axis: Vec3, ref: Vec3?) {
    val z: Vec3 = axis.norm()
    val x: Vec3
    val y: Vec3

    init {
        var r = ref ?: (if (abs(z.x) < 0.9) Vec3.X else Vec3.Y)
        r = (r - z * r.dot(z))
        if (r.len() < 1e-12) r = (if (abs(z.x) < 0.9) Vec3.X else Vec3.Y).let { it - z * it.dot(z) }
        x = r.norm()
        y = z.cross(x)
    }

    fun toWorld(a: Double, b: Double, c: Double) = origin + x * a + y * b + z * c
    fun local(p: Vec3): Vec3 {
        val d = p - origin
        return Vec3(d.dot(x), d.dot(y), d.dot(z))
    }

    fun matrix(): DoubleArray = doubleArrayOf(x.x, x.y, x.z, 0.0, y.x, y.y, y.z, 0.0, z.x, z.y, z.z, 0.0, origin.x, origin.y, origin.z, 1.0)
}

internal object Mat4d {
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (c in 0..3) for (row in 0..3) {
            r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3]
        }
        return r
    }

    fun invertRigid(m: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (i in 0..2) for (j in 0..2) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0..2) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1.0
        return r
    }

    fun apply(m: DoubleArray, p: Vec3) = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
    )
}

internal class Nurbs(val degree: Int, val knots: DoubleArray, val weights: DoubleArray?) {
    val n get() = knots.size - degree - 1
    val start get() = knots[degree]
    val end get() = knots[knots.size - degree - 1]

    fun span(t: Double): Int {
        val nn = n - 1
        if (t >= knots[nn + 1]) return nn
        if (t <= knots[degree]) return degree
        var lo = degree
        var hi = nn + 1
        var mid = (lo + hi) / 2
        var guard = 0
        while ((t < knots[mid] || t >= knots[mid + 1]) && guard++ < 64) {
            if (t < knots[mid]) hi = mid else lo = mid
            mid = (lo + hi) / 2
        }
        return mid
    }

    fun basis(s: Int, t: Double, out: DoubleArray) {
        out[0] = 1.0
        val left = DoubleArray(degree + 1)
        val right = DoubleArray(degree + 1)
        for (j in 1..degree) {
            left[j] = t - knots[s + 1 - j]
            right[j] = knots[s + j] - t
            var saved = 0.0
            for (r in 0 until j) {
                val den = right[r + 1] + left[j - r]
                val temp = if (den != 0.0) out[r] / den else 0.0
                out[r] = saved + right[r + 1] * temp
                saved = left[j - r] * temp
            }
            out[j] = saved
        }
    }
}

internal class NurbsCurve(val basis: Nurbs, val points: Array<Vec3>) {
    private val buf = DoubleArray(basis.degree + 1)
    fun eval(t: Double): Vec3 {
        val tt = t.coerceIn(basis.start, basis.end)
        val s = basis.span(tt)
        basis.basis(s, tt, buf)
        var x = 0.0; var y = 0.0; var z = 0.0; var w = 0.0
        for (i in 0..basis.degree) {
            val idx = s - basis.degree + i
            if (idx !in points.indices) continue
            val wi = (basis.weights?.getOrNull(idx) ?: 1.0) * buf[i]
            val p = points[idx]
            x += p.x * wi; y += p.y * wi; z += p.z * wi; w += wi
        }
        return if (w != 0.0) Vec3(x / w, y / w, z / w) else Vec3.ZERO
    }
}

internal abstract class BrepSurface {
    abstract fun eval(u: Double, v: Double): Vec3
    abstract fun project(p: Vec3, hint: DoubleArray?): DoubleArray
    open val uPeriod: Double = 0.0
    open val vPeriod: Double = 0.0
    open val curved: Boolean = true
}

internal class PlaneSurface(private val f: Frame) : BrepSurface() {
    override val curved = false
    override fun eval(u: Double, v: Double) = f.toWorld(u, v, 0.0)
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray = f.local(p).let { doubleArrayOf(it.x, it.y) }
}

internal class CylinderSurface(private val f: Frame, private val r: Double) : BrepSurface() {
    override val uPeriod = 2 * PI
    override fun eval(u: Double, v: Double) = f.toWorld(r * cos(u), r * sin(u), v)
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray = f.local(p).let { doubleArrayOf(atan2(it.y, it.x), it.z) }
}

internal class ConeSurface(private val f: Frame, private val r: Double, private val semiAngle: Double) : BrepSurface() {
    override val uPeriod = 2 * PI
    override fun eval(u: Double, v: Double): Vec3 {
        val rr = r + v * kotlin.math.tan(semiAngle)
        return f.toWorld(rr * cos(u), rr * sin(u), v)
    }
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray = f.local(p).let { doubleArrayOf(atan2(it.y, it.x), it.z) }
}

internal class SphereSurface(private val f: Frame, private val r: Double) : BrepSurface() {
    override val uPeriod = 2 * PI
    override fun eval(u: Double, v: Double) = f.toWorld(r * cos(v) * cos(u), r * cos(v) * sin(u), r * sin(v))
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
        val l = f.local(p)
        val d = l.len().coerceAtLeast(1e-300)
        return doubleArrayOf(atan2(l.y, l.x), kotlin.math.asin((l.z / d).coerceIn(-1.0, 1.0)))
    }
}

internal class TorusSurface(private val f: Frame, private val major: Double, private val minor: Double) : BrepSurface() {
    override val uPeriod = 2 * PI
    override val vPeriod = 2 * PI
    override fun eval(u: Double, v: Double): Vec3 {
        val rr = major + minor * cos(v)
        return f.toWorld(rr * cos(u), rr * sin(u), minor * sin(v))
    }
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
        val l = f.local(p)
        val u = atan2(l.y, l.x)
        val radial = sqrt(l.x * l.x + l.y * l.y) - major
        return doubleArrayOf(u, atan2(l.z, radial))
    }
}

internal class RevolutionSurface(private val profile: (Double) -> Vec3, private val t0: Double, private val t1: Double, private val axisOrigin: Vec3, axisDir: Vec3) : BrepSurface() {
    private val f = Frame(axisOrigin, axisDir, null)
    override val uPeriod = 2 * PI
    override fun eval(u: Double, v: Double): Vec3 {
        val p = f.local(profile(v))
        val c = cos(u)
        val s = sin(u)
        return f.toWorld(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
    }
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
        val l = f.local(p)
        val r = sqrt(l.x * l.x + l.y * l.y)
        var bestT = t0
        var best = Double.MAX_VALUE
        val steps = 64
        for (i in 0..steps) {
            val t = t0 + (t1 - t0) * i / steps
            val q = f.local(profile(t))
            val qr = sqrt(q.x * q.x + q.y * q.y)
            val d = (qr - r) * (qr - r) + (q.z - l.z) * (q.z - l.z)
            if (d < best) { best = d; bestT = t }
        }
        var lo = (bestT - (t1 - t0) / steps).coerceAtLeast(min(t0, t1))
        var hi = (bestT + (t1 - t0) / steps).coerceAtMost(max(t0, t1))
        repeat(40) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            fun dist(t: Double): Double {
                val q = f.local(profile(t))
                val qr = sqrt(q.x * q.x + q.y * q.y)
                return (qr - r) * (qr - r) + (q.z - l.z) * (q.z - l.z)
            }
            if (dist(m1) < dist(m2)) hi = m2 else lo = m1
        }
        val t = (lo + hi) / 2
        val q = f.local(profile(t))
        return doubleArrayOf(atan2(l.y, l.x) - atan2(q.y, q.x), t)
    }
}

internal class ExtrusionSurface(private val profile: (Double) -> Vec3, private val t0: Double, private val t1: Double, private val dir: Vec3) : BrepSurface() {
    override fun eval(u: Double, v: Double) = profile(u) + dir * v
    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
        val d = dir.norm()
        val dl = dir.len().coerceAtLeast(1e-300)
        var bestT = t0
        var best = Double.MAX_VALUE
        for (i in 0..64) {
            val t = t0 + (t1 - t0) * i / 64
            val q = profile(t) - p
            val perp = q - d * q.dot(d)
            val dd = perp.dot(perp)
            if (dd < best) { best = dd; bestT = t }
        }
        val q = p - profile(bestT)
        return doubleArrayOf(bestT, q.dot(d) / dl)
    }
}

internal class NurbsSurface(val ub: Nurbs, val vb: Nurbs, val nu: Int, val nv: Int, val points: Array<Vec3>, val weights: DoubleArray?) : BrepSurface() {
    private val bu = DoubleArray(ub.degree + 1)
    private val bv = DoubleArray(vb.degree + 1)
    val u0 get() = ub.start
    val u1 get() = ub.end
    val v0 get() = vb.start
    val v1 get() = vb.end

    override fun eval(u: Double, v: Double): Vec3 {
        val uu = u.coerceIn(ub.start, ub.end)
        val vv = v.coerceIn(vb.start, vb.end)
        val su = ub.span(uu)
        val sv = vb.span(vv)
        ub.basis(su, uu, bu)
        vb.basis(sv, vv, bv)
        var x = 0.0; var y = 0.0; var z = 0.0; var w = 0.0
        for (i in 0..ub.degree) {
            val iu = su - ub.degree + i
            if (iu !in 0 until nu) continue
            for (j in 0..vb.degree) {
                val iv = sv - vb.degree + j
                if (iv !in 0 until nv) continue
                val idx = iu + iv * nu
                val wi = bu[i] * bv[j] * (weights?.getOrNull(idx) ?: 1.0)
                val p = points[idx]
                x += p.x * wi; y += p.y * wi; z += p.z * wi; w += wi
            }
        }
        return if (w != 0.0) Vec3(x / w, y / w, z / w) else Vec3.ZERO
    }

    private var grid: Array<Vec3>? = null
    private val gridN = 24

    override fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
        val g = grid ?: Array((gridN + 1) * (gridN + 1)) { k ->
            eval(u0 + (u1 - u0) * (k % (gridN + 1)) / gridN, v0 + (v1 - v0) * (k / (gridN + 1)) / gridN)
        }.also { grid = it }
        var u: Double
        var v: Double
        if (hint != null) {
            u = hint[0]; v = hint[1]
        } else {
            var best = 0
            var bd = Double.MAX_VALUE
            for (k in g.indices) {
                val d = (g[k] - p).let { it.dot(it) }
                if (d < bd) { bd = d; best = k }
            }
            u = u0 + (u1 - u0) * (best % (gridN + 1)) / gridN
            v = v0 + (v1 - v0) * (best / (gridN + 1)) / gridN
        }
        val hu = (u1 - u0) * 1e-6
        val hv = (v1 - v0) * 1e-6
        repeat(20) {
            val s = eval(u, v)
            val su = (eval(min(u + hu, u1), v) - eval(max(u - hu, u0), v)) * (1.0 / (min(u + hu, u1) - max(u - hu, u0)).coerceAtLeast(1e-300))
            val sv = (eval(u, min(v + hv, v1)) - eval(u, max(v - hv, v0))) * (1.0 / (min(v + hv, v1) - max(v - hv, v0)).coerceAtLeast(1e-300))
            val r = s - p
            val a = su.dot(su)
            val b = su.dot(sv)
            val c = sv.dot(sv)
            val d1 = -r.dot(su)
            val d2 = -r.dot(sv)
            val det = a * c - b * b
            if (abs(det) < 1e-30) return doubleArrayOf(u, v)
            val du = (d1 * c - d2 * b) / det
            val dv = (a * d2 - b * d1) / det
            u = (u + du).coerceIn(u0, u1)
            v = (v + dv).coerceIn(v0, v1)
            if (abs(du) < (u1 - u0) * 1e-9 && abs(dv) < (v1 - v0) * 1e-9) return doubleArrayOf(u, v)
        }
        return doubleArrayOf(u, v)
    }
}

internal object BrepTessellator {

    class Mesh(val positions: ArrayList<Vec3> = ArrayList(), val triangles: ArrayList<IntArray> = ArrayList(), val normals: ArrayList<Vec3> = ArrayList())

    fun surfaceNormal(s: BrepSurface, u: Double, v: Double, hu: Double, hv: Double): Vec3 {
        val du = s.eval(u + hu, v) - s.eval(u - hu, v)
        val dv = s.eval(u, v + hv) - s.eval(u, v - hv)
        val n = du.cross(dv)
        val l = n.len()
        return if (l > 1e-20) n * (1.0 / l) else Vec3.ZERO
    }

    private fun area(poly: List<DoubleArray>): Double {
        var a = 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p[0] * q[1] - q[0] * p[1]
        }
        return a / 2
    }

    fun unwrap(loop: List<DoubleArray>, uPeriod: Double, vPeriod: Double): List<DoubleArray> {
        if (loop.isEmpty()) return loop
        val out = ArrayList<DoubleArray>(loop.size)
        out.add(loop[0].copyOf())
        for (i in 1 until loop.size) {
            val prev = out.last()
            var u = loop[i][0]
            var v = loop[i][1]
            if (uPeriod > 0) {
                while (u - prev[0] > uPeriod / 2) u -= uPeriod
                while (prev[0] - u > uPeriod / 2) u += uPeriod
            }
            if (vPeriod > 0) {
                while (v - prev[1] > vPeriod / 2) v -= vPeriod
                while (prev[1] - v > vPeriod / 2) v += vPeriod
            }
            out.add(doubleArrayOf(u, v))
        }
        return out
    }

    private fun pointInPolygon(x: Double, y: Double, poly: List<DoubleArray>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
            j = i
        }
        return inside
    }

    fun triangulate(outerIn: List<DoubleArray>, holesIn: List<List<DoubleArray>>): Pair<List<DoubleArray>, List<IntArray>> {
        val outer = dedupe(outerIn).let { if (area(it) < 0) it.reversed() else it }
        if (outer.size < 3) return emptyList<DoubleArray>() to emptyList()
        var ring = ArrayList(outer)
        val holes = holesIn.map { dedupe(it) }.filter { it.size >= 3 }.map { if (area(it) > 0) it.reversed() else it }
            .filter { h -> pointInPolygon(h[0][0], h[0][1], outer) || true }
            .sortedByDescending { h -> h.maxOf { it[0] } }
        for (h in holes) {
            val hi = h.indices.maxByOrNull { h[it][0] } ?: continue
            val hp = h[hi]
            var bestIdx = -1
            var bestD = Double.MAX_VALUE
            for (i in ring.indices) {
                val q = ring[i]
                if (q[0] < hp[0] - 1e-12) continue
                val d = (q[0] - hp[0]) * (q[0] - hp[0]) + (q[1] - hp[1]) * (q[1] - hp[1])
                if (d < bestD && visible(ring, h, hp, q)) { bestD = d; bestIdx = i }
            }
            if (bestIdx < 0) {
                for (i in ring.indices) {
                    val q = ring[i]
                    val d = (q[0] - hp[0]) * (q[0] - hp[0]) + (q[1] - hp[1]) * (q[1] - hp[1])
                    if (d < bestD) { bestD = d; bestIdx = i }
                }
            }
            val merged = ArrayList<DoubleArray>(ring.size + h.size + 2)
            for (i in 0..bestIdx) merged.add(ring[i])
            for (k in 0..h.size) merged.add(h[(hi + k) % h.size])
            for (i in bestIdx until ring.size) merged.add(ring[i])
            ring = merged
        }
        return ring to earClip(ring)
    }

    private fun visible(ring: List<DoubleArray>, hole: List<DoubleArray>, a: DoubleArray, b: DoubleArray): Boolean {
        fun cross(p: DoubleArray, q: DoubleArray, r: DoubleArray) = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        fun intersects(p1: DoubleArray, p2: DoubleArray, p3: DoubleArray, p4: DoubleArray): Boolean {
            val d1 = cross(p3, p4, p1)
            val d2 = cross(p3, p4, p2)
            val d3 = cross(p1, p2, p3)
            val d4 = cross(p1, p2, p4)
            return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        }
        for (poly in listOf(ring, hole)) for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            if (p === a || q === a || p === b || q === b) continue
            if (intersects(a, b, p, q)) return false
        }
        return true
    }

    private fun dedupe(loop: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(loop.size)
        for (p in loop) {
            val last = out.lastOrNull()
            if (last != null && abs(last[0] - p[0]) < 1e-12 && abs(last[1] - p[1]) < 1e-12) continue
            out.add(p)
        }
        while (out.size > 1 && abs(out.first()[0] - out.last()[0]) < 1e-12 && abs(out.first()[1] - out.last()[1]) < 1e-12) out.removeAt(out.size - 1)
        return out
    }

    private fun earClip(poly: List<DoubleArray>): List<IntArray> {
        val n = poly.size
        val res = ArrayList<IntArray>()
        if (n < 3) return res
        val prev = IntArray(n) { (it + n - 1) % n }
        val next = IntArray(n) { (it + 1) % n }
        var remaining = n
        fun cross(a: Int, b: Int, c: Int): Double {
            val p = poly[a]; val q = poly[b]; val r = poly[c]
            return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        }
        fun inside(p: DoubleArray, a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
            val d1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
            val d2 = (c[0] - b[0]) * (p[1] - b[1]) - (c[1] - b[1]) * (p[0] - b[0])
            val d3 = (a[0] - c[0]) * (p[1] - c[1]) - (a[1] - c[1]) * (p[0] - c[0])
            return d1 >= 0 && d2 >= 0 && d3 >= 0
        }
        var i = 0
        var guard = 0
        while (remaining > 3 && guard < n * n + 100) {
            guard++
            val a = prev[i]
            val c = next[i]
            var ear = cross(a, i, c) > 0
            if (ear) {
                var k = next[c]
                while (k != a) {
                    val pk = poly[k]
                    if (pk !== poly[a] && pk !== poly[i] && pk !== poly[c] && !(pk[0] == poly[a][0] && pk[1] == poly[a][1]) && !(pk[0] == poly[c][0] && pk[1] == poly[c][1]) && !(pk[0] == poly[i][0] && pk[1] == poly[i][1]) &&
                        inside(pk, poly[a], poly[i], poly[c])) {
                        ear = false
                        break
                    }
                    k = next[k]
                }
            }
            if (ear) {
                res.add(intArrayOf(a, i, c))
                next[a] = c
                prev[c] = a
                remaining--
                i = c
                guard = 0
            } else {
                i = next[i]
                if (guard > remaining * 2) {
                    res.add(intArrayOf(a, i, c))
                    next[a] = c
                    prev[c] = a
                    remaining--
                    i = c
                    guard = 0
                }
            }
        }
        if (remaining == 3) res.add(intArrayOf(prev[i], i, next[i]))
        return res
    }

    private fun delaunayFlips(pts: List<DoubleArray>, tris: MutableList<IntArray>, su: Double, sv: Double) {
        fun x(i: Int) = pts[i][0] * su
        fun y(i: Int) = pts[i][1] * sv
        fun inCircle(a: Int, b: Int, c: Int, d: Int): Boolean {
            val ax = x(a) - x(d); val ay = y(a) - y(d)
            val bx = x(b) - x(d); val by = y(b) - y(d)
            val cx = x(c) - x(d); val cy = y(c) - y(d)
            val det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) + (cx * cx + cy * cy) * (ax * by - bx * ay)
            return det > 1e-12 * (1 + abs(det))
        }
        fun orient(a: Int, b: Int, c: Int) = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a))
        fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        repeat(12) {
            val edgeMap = HashMap<Long, MutableList<Int>>()
            for ((ti, t) in tris.withIndex()) for (k in 0 until 3) edgeMap.getOrPut(key(t[k], t[(k + 1) % 3])) { ArrayList(2) }.add(ti)
            var flips = 0
            val touched = BooleanArray(tris.size)
            for ((_, owners) in edgeMap) {
                if (owners.size != 2) continue
                val t1 = owners[0]
                val t2 = owners[1]
                if (touched[t1] || touched[t2]) continue
                val A = tris[t1]
                val B = tris[t2]
                var k1 = -1
                for (k in 0 until 3) if (A[k] !in B) k1 = k
                var k2 = -1
                for (k in 0 until 3) if (B[k] !in A) k2 = k
                if (k1 < 0 || k2 < 0) continue
                val p = A[k1]
                val q = A[(k1 + 1) % 3]
                val r = A[(k1 + 2) % 3]
                val o = B[k2]
                if (!inCircle(p, q, r, o) && orient(p, q, r) > 0) continue
                if (orient(p, q, o) <= 1e-15 || orient(p, o, r) <= 1e-15) continue
                tris[t1] = intArrayOf(p, q, o)
                tris[t2] = intArrayOf(p, o, r)
                touched[t1] = true
                touched[t2] = true
                flips++
            }
            if (flips == 0) return
        }
    }

    fun tessellate(surface: BrepSurface, outer: List<DoubleArray>, holes: List<List<DoubleArray>>, tolerance: Double, out: Mesh) {
        val (ring, tris0) = triangulate(outer, holes)
        if (tris0.isEmpty()) return
        val cu = ring.sumOf { it[0] } / ring.size
        val cv = ring.sumOf { it[1] } / ring.size
        val du = ((ring.maxOf { it[0] } - ring.minOf { it[0] }) * 1e-3).coerceAtLeast(1e-9)
        val dv = ((ring.maxOf { it[1] } - ring.minOf { it[1] }) * 1e-3).coerceAtLeast(1e-9)
        val p0 = surface.eval(cu, cv)
        val su = ((surface.eval(cu + du, cv) - p0).len() / du).takeIf { it.isFinite() && it > 1e-12 } ?: 1.0
        val sv = ((surface.eval(cu, cv + dv) - p0).len() / dv).takeIf { it.isFinite() && it > 1e-12 } ?: 1.0
        val tris = tris0.map { t ->
            val o = (ring[t[1]][0] - ring[t[0]][0]) * (ring[t[2]][1] - ring[t[0]][1]) - (ring[t[1]][1] - ring[t[0]][1]) * (ring[t[2]][0] - ring[t[0]][0])
            if (o < 0) intArrayOf(t[0], t[2], t[1]) else t
        }.toMutableList()
        delaunayFlips(ring, tris, su, sv)
        val uvs = ArrayList<DoubleArray>(ring)
        val pts = ArrayList<Vec3>(ring.size)
        for (p in ring) pts.add(surface.eval(p[0], p[1]))
        val mids = HashMap<Long, Int>()
        fun mid(a: Int, b: Int): Int {
            val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
            mids[key]?.let { return it }
            val m = doubleArrayOf((uvs[a][0] + uvs[b][0]) / 2, (uvs[a][1] + uvs[b][1]) / 2)
            uvs.add(m)
            pts.add(surface.eval(m[0], m[1]))
            val idx = uvs.size - 1
            mids[key] = idx
            return idx
        }
        val maxEdge = tolerance * 25
        fun needs(a: Int, b: Int): Boolean {
            if (!surface.curved) return false
            val c = (pts[a] + pts[b]) * 0.5
            if ((pts[a] - pts[b]).len() > maxEdge) return true
            val m = surface.eval((uvs[a][0] + uvs[b][0]) / 2, (uvs[a][1] + uvs[b][1]) / 2)
            return (m - c).len() > tolerance
        }
        val decisions = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        fun splitAt(a: Int, b: Int): Int {
            val k = key(a, b)
            decisions[k]?.let { return it }
            val r = if (needs(a, b)) mid(a, b) else -1
            decisions[k] = r
            return r
        }
        var current: List<IntArray> = tris
        var round = 0
        while (round < 8 && current.size < 150_000) {
            round++
            decisions.clear()
            var changed = false
            val next = ArrayList<IntArray>(current.size * 2)
            for (t in current) {
                val a = t[0]; val b = t[1]; val c = t[2]
                val m0 = splitAt(a, b)
                val m1 = splitAt(b, c)
                val m2 = splitAt(c, a)
                val count = (if (m0 >= 0) 1 else 0) + (if (m1 >= 0) 1 else 0) + (if (m2 >= 0) 1 else 0)
                if (count == 0) {
                    next.add(t)
                    continue
                }
                changed = true
                when {
                    count == 3 -> {
                        next.add(intArrayOf(a, m0, m2)); next.add(intArrayOf(m0, b, m1)); next.add(intArrayOf(m2, m1, c)); next.add(intArrayOf(m0, m1, m2))
                    }
                    count == 1 && m0 >= 0 -> { next.add(intArrayOf(a, m0, c)); next.add(intArrayOf(m0, b, c)) }
                    count == 1 && m1 >= 0 -> { next.add(intArrayOf(a, b, m1)); next.add(intArrayOf(a, m1, c)) }
                    count == 1 -> { next.add(intArrayOf(a, b, m2)); next.add(intArrayOf(m2, b, c)) }
                    m2 < 0 -> { next.add(intArrayOf(m0, b, m1)); next.add(intArrayOf(a, m0, m1)); next.add(intArrayOf(a, m1, c)) }
                    m0 < 0 -> { next.add(intArrayOf(m1, c, m2)); next.add(intArrayOf(b, m1, m2)); next.add(intArrayOf(b, m2, a)) }
                    else -> { next.add(intArrayOf(m2, a, m0)); next.add(intArrayOf(c, m2, m0)); next.add(intArrayOf(c, m0, b)) }
                }
            }
            current = next
            if (!changed) break
        }
        val base = out.positions.size
        val final = current
        val hu = ((uvs.maxOf { it[0] } - uvs.minOf { it[0] }) * 1e-4).coerceAtLeast(1e-9)
        val hv = ((uvs.maxOf { it[1] } - uvs.minOf { it[1] }) * 1e-4).coerceAtLeast(1e-9)
        for (uv in uvs) out.normals.add(if (surface.curved) surfaceNormal(surface, uv[0], uv[1], hu, hv) else Vec3.ZERO)
        out.positions.addAll(pts)
        for (t in final) out.triangles.add(intArrayOf(t[0] + base, t[1] + base, t[2] + base))
    }

    fun angleBetween(a: Vec3, b: Vec3): Double = acos((a.norm().dot(b.norm())).coerceIn(-1.0, 1.0))
}
