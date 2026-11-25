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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal class IgesEntity(val de: Int, val type: Int, val form: Int, val status: String, val color: Int, val transform: Int, val params: List<Any?>) {
    fun num(i: Int): Double = when (val v = params.getOrNull(i)) {
        is Number -> v.toDouble()
        else -> 0.0
    }
    fun int(i: Int): Int = num(i).toInt()
    val blanked get() = status.length >= 2 && status.substring(0, 2) == "01"
    val subordinate get() = status.length >= 4 && status.substring(2, 4).let { it == "01" || it == "03" }
}

internal object IgesSectionLexer {
    fun parse(text: String): Map<Int, IgesEntity> {
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.length >= 73 }
        val g = StringBuilder()
        val d = ArrayList<String>()
        val pData = HashMap<Int, StringBuilder>()
        for (l in lines) {
            when (l[72]) {
                'G' -> g.append(l.substring(0, 72))
                'D' -> d.add(l.padEnd(80))
                'P' -> {
                    val de = l.substring(64, 72).trim().toIntOrNull() ?: continue
                    pData.getOrPut(de) { StringBuilder() }.append(l.substring(0, 64))
                }
            }
        }
        var pd = ','
        var rd = ';'
        val gs = g.toString()
        if (gs.startsWith("1H")) {
            pd = gs[2]
            val r = gs.indexOf("1H", 3)
            if (r in 3..6) rd = gs[r + 2]
        } else if (gs.startsWith(",,")) {
            pd = ','
        }
        val map = HashMap<Int, IgesEntity>()
        var i = 0
        while (i + 1 < d.size) {
            val a = d[i]
            val b = d[i + 1]
            fun f(line: String, k: Int) = line.substring(k * 8, k * 8 + 8).trim()
            val type = f(a, 0).toIntOrNull()
            val seq = a.substring(73, 80).trim().toIntOrNull() ?: (i + 1)
            if (type != null) {
                val params = pData[seq]?.let { parseParams(it.toString(), pd, rd) } ?: emptyList()
                map[seq] = IgesEntity(
                    seq, type, f(b, 4).toIntOrNull() ?: 0, f(a, 8).padStart(8, '0'),
                    f(b, 2).toIntOrNull() ?: 0, f(a, 6).toIntOrNull() ?: 0,
                    if (params.isNotEmpty()) params.drop(1) else params
                )
            }
            i += 2
        }
        return map
    }

    private fun parseParams(s: String, pd: Char, rd: Char): List<Any?> {
        val out = ArrayList<Any?>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i] == ' ') i++
            if (i >= n) break
            val start = i
            var j = i
            while (j < n && s[j].isDigit()) j++
            if (j < n && j > i && s[j] == 'H') {
                val len = s.substring(i, j).toInt()
                val str = s.substring(j + 1, minOf(n, j + 1 + len))
                out.add(str)
                i = j + 1 + len
                while (i < n && s[i] != pd && s[i] != rd) i++
            } else {
                while (i < n && s[i] != pd && s[i] != rd) i++
                val tok = s.substring(start, i).trim().replace('D', 'E').replace('d', 'e')
                out.add(if (tok.isEmpty()) null else tok.toLongOrNull() ?: tok.toDoubleOrNull())
            }
            if (i < n && s[i] == rd) break
            i++
        }
        return out
    }
}

internal class IgesParser(private val e: Map<Int, IgesEntity>, private val out: GltfOutput) {

    companion object {
        fun parse(text: String): Map<Int, IgesEntity> = IgesSectionLexer.parse(text)
    }

    private var tolerance = 0.01
    private val surfaceCache = HashMap<Int, BrepSurface?>()

    private fun matrix(de: Int, depth: Int = 0): DoubleArray {
        if (de == 0 || depth > 16) return Mat4d.IDENTITY
        val t = e[de] ?: return Mat4d.IDENTITY
        if (t.type != 124) return Mat4d.IDENTITY
        val m = doubleArrayOf(
            t.num(0), t.num(4), t.num(8), 0.0,
            t.num(1), t.num(5), t.num(9), 0.0,
            t.num(2), t.num(6), t.num(10), 0.0,
            t.num(3), t.num(7), t.num(11), 1.0
        )
        return Mat4d.mul(matrix(t.transform, depth + 1), m)
    }

    private fun color(en: IgesEntity): Int {
        val c = en.color
        if (c < 0) {
            val cd = e[-c]
            if (cd != null && cd.type == 314) {
                fun ch(i: Int) = (cd.num(i) / 100.0 * 255).roundToInt().coerceIn(0, 255)
                return (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
            return 0
        }
        return when (c) {
            1 -> 0xFF303030.toInt(); 2 -> 0xFFE53935.toInt(); 3 -> 0xFF43A047.toInt(); 4 -> 0xFF1E88E5.toInt()
            5 -> 0xFFFDD835.toInt(); 6 -> 0xFFD81B60.toInt(); 7 -> 0xFF00ACC1.toInt(); 8 -> 0xFFF5F5F5.toInt()
            else -> 0
        }
    }

    private class Curve(val f: (Double) -> Vec3, val t0: Double, val t1: Double, val segments: Int)

    private fun nurbsCurve(en: IgesEntity): NurbsCurve? {
        val k = en.int(0)
        val m = en.int(1)
        val nKnots = k + m + 2
        var p = 6
        val knots = DoubleArray(nKnots) { en.num(p + it) }
        p += nKnots
        val w = DoubleArray(k + 1) { en.num(p + it) }
        p += k + 1
        val pts = Array(k + 1) { Vec3(en.num(p + it * 3), en.num(p + it * 3 + 1), en.num(p + it * 3 + 2)) }
        if (k < 1 || m < 1) return null
        return NurbsCurve(Nurbs(m, knots, w), pts)
    }

    private fun curve(de: Int, depth: Int = 0): Curve? {
        val en = e[de] ?: return null
        if (depth > 8) return null
        val m = matrix(en.transform)
        return when (en.type) {
            110 -> {
                val a = Vec3(en.num(0), en.num(1), en.num(2))
                val b = Vec3(en.num(3), en.num(4), en.num(5))
                Curve({ t -> Mat4d.apply(m, a + (b - a) * t) }, 0.0, 1.0, 1)
            }
            100 -> {
                val z = en.num(0)
                val cx = en.num(1); val cy = en.num(2)
                val sx = en.num(3); val sy = en.num(4)
                val ex = en.num(5); val ey = en.num(6)
                val r = sqrt((sx - cx) * (sx - cx) + (sy - cy) * (sy - cy))
                val a0 = atan2(sy - cy, sx - cx)
                var a1 = atan2(ey - cy, ex - cx)
                while (a1 <= a0 + 1e-12) a1 += 2 * PI
                Curve({ t -> Mat4d.apply(m, Vec3(cx + r * cos(t), cy + r * sin(t), z)) }, a0, a1, (64 * (a1 - a0) / (2 * PI)).toInt().coerceAtLeast(4))
            }
            126 -> {
                val c = nurbsCurve(en) ?: return null
                val v0 = en.num(6 + (en.int(0) + en.int(1) + 2) + (en.int(0) + 1) * 4)
                val v1 = en.num(7 + (en.int(0) + en.int(1) + 2) + (en.int(0) + 1) * 4)
                val t0 = if (v1 > v0) v0 else c.basis.start
                val t1 = if (v1 > v0) v1 else c.basis.end
                Curve({ t -> Mat4d.apply(m, c.eval(t)) }, t0, t1, (c.points.size * 8).coerceIn(8, 512))
            }
            106 -> {
                val ip = en.int(0)
                val n = en.int(1)
                val pts = ArrayList<Vec3>()
                for (i in 0 until n) {
                    pts.add(
                        when (ip) {
                            1 -> Vec3(en.num(3 + i * 2), en.num(4 + i * 2), en.num(2))
                            2 -> Vec3(en.num(2 + i * 3), en.num(3 + i * 3), en.num(4 + i * 3))
                            else -> Vec3(en.num(2 + i * 6), en.num(3 + i * 6), en.num(4 + i * 6))
                        }
                    )
                }
                if (pts.size < 2) return null
                Curve({ t ->
                    val kk = t.coerceIn(0.0, (pts.size - 1).toDouble())
                    val i = kk.toInt().coerceAtMost(pts.size - 2)
                    Mat4d.apply(m, pts[i] * (1 - (kk - i)) + pts[i + 1] * (kk - i))
                }, 0.0, (pts.size - 1).toDouble(), pts.size - 1)
            }
            102 -> {
                val parts = (0 until en.int(0)).mapNotNull { curve(en.int(1 + it), depth + 1) }
                if (parts.isEmpty()) return null
                Curve({ t ->
                    val i = t.toInt().coerceIn(0, parts.size - 1)
                    val c = parts[i]
                    Mat4d.apply(m, c.f(c.t0 + (c.t1 - c.t0) * (t - i).coerceIn(0.0, 1.0)))
                }, 0.0, parts.size.toDouble(), parts.sumOf { it.segments })
            }
            else -> null
        }
    }

    private fun sample(c: Curve): List<Vec3> {
        val n = c.segments.coerceIn(1, 4096)
        return (0..n).map { c.f(c.t0 + (c.t1 - c.t0) * it / n) }
    }

    private fun surface(de: Int): BrepSurface? {
        if (surfaceCache.containsKey(de)) return surfaceCache[de]
        val en = e[de]
        val s = if (en == null) null else when (en.type) {
            128 -> {
                val k1 = en.int(0); val k2 = en.int(1); val m1 = en.int(2); val m2 = en.int(3)
                var p = 9
                val uk = DoubleArray(k1 + m1 + 2) { en.num(p + it) }
                p += uk.size
                val vk = DoubleArray(k2 + m2 + 2) { en.num(p + it) }
                p += vk.size
                val n = (k1 + 1) * (k2 + 1)
                val w = DoubleArray(n) { en.num(p + it) }
                p += n
                val xf = matrix(en.transform)
                val pts = Array(n) { Mat4d.apply(xf, Vec3(en.num(p + it * 3), en.num(p + it * 3 + 1), en.num(p + it * 3 + 2))) }
                NurbsSurface(Nurbs(m1, uk, null), Nurbs(m2, vk, null), k1 + 1, k2 + 1, pts, w)
            }
            108 -> {
                val nrm = Vec3(en.num(0), en.num(1), en.num(2))
                val d = en.num(3)
                val l = nrm.len().coerceAtLeast(1e-300)
                val xf = matrix(en.transform)
                val o = Mat4d.apply(xf, nrm * (d / (l * l)))
                val nn = Vec3(xf[0] * nrm.x + xf[4] * nrm.y + xf[8] * nrm.z, xf[1] * nrm.x + xf[5] * nrm.y + xf[9] * nrm.z, xf[2] * nrm.x + xf[6] * nrm.y + xf[10] * nrm.z)
                PlaneSurface(Frame(o, nn, null))
            }
            118 -> {
                val c1 = curve(en.int(0))
                val c2 = curve(en.int(1))
                if (c1 == null || c2 == null) null else {
                    val rev = en.int(2) == 1
                    object : BrepSurface() {
                        override fun eval(u: Double, v: Double): Vec3 {
                            val a = c1.f(c1.t0 + (c1.t1 - c1.t0) * u)
                            val b = c2.f(if (rev) c2.t1 - (c2.t1 - c2.t0) * u else c2.t0 + (c2.t1 - c2.t0) * u)
                            return a * (1 - v) + b * v
                        }
                        private val inner = NurbsLike(this)
                        override fun project(p: Vec3, hint: DoubleArray?) = inner.project(p, hint)
                    }
                }
            }
            120 -> {
                val axis = e[en.int(0)]
                val gen = curve(en.int(1))
                if (axis == null || gen == null) null else {
                    val a = Vec3(axis.num(0), axis.num(1), axis.num(2))
                    val b = Vec3(axis.num(3), axis.num(4), axis.num(5))
                    RevolutionSurface(gen.f, gen.t0, gen.t1, a, b - a)
                }
            }
            122 -> {
                val dc = curve(en.int(0))
                if (dc == null) null else {
                    val start = dc.f(dc.t0)
                    ExtrusionSurface(dc.f, dc.t0, dc.t1, Vec3(en.num(1), en.num(2), en.num(3)) - start)
                }
            }
            else -> null
        }
        surfaceCache[de] = s
        return s
    }

    private class NurbsLike(private val s: BrepSurface) {
        fun project(p: Vec3, hint: DoubleArray?): DoubleArray {
            var bu = 0.5
            var bv = 0.5
            if (hint != null) {
                bu = hint[0]; bv = hint[1]
            } else {
                var bd = Double.MAX_VALUE
                for (i in 0..20) for (j in 0..20) {
                    val d = (s.eval(i / 20.0, j / 20.0) - p).len()
                    if (d < bd) { bd = d; bu = i / 20.0; bv = j / 20.0 }
                }
            }
            var u = bu
            var v = bv
            val h = 1e-5
            repeat(20) {
                val r = s.eval(u, v) - p
                val su = (s.eval((u + h).coerceAtMost(1.0), v) - s.eval((u - h).coerceAtLeast(0.0), v)) * (1.0 / (2 * h))
                val sv = (s.eval(u, (v + h).coerceAtMost(1.0)) - s.eval(u, (v - h).coerceAtLeast(0.0))) * (1.0 / (2 * h))
                val a = su.dot(su); val b = su.dot(sv); val c = sv.dot(sv)
                val det = a * c - b * b
                if (kotlin.math.abs(det) < 1e-30) return doubleArrayOf(u, v)
                val du = (-r.dot(su) * c + r.dot(sv) * b) / det
                val dv = (-a * r.dot(sv) + b * r.dot(su)) / det
                u = (u + du).coerceIn(0.0, 1.0)
                v = (v + dv).coerceIn(0.0, 1.0)
            }
            return doubleArrayOf(u, v)
        }
    }

    private fun paramLoop(curveOnSurface: IgesEntity, s: BrepSurface): List<DoubleArray>? {
        val bptr = curveOnSurface.int(2)
        val cptr = curveOnSurface.int(3)
        if (s is NurbsSurface && bptr != 0) {
            val c = curve(bptr) ?: return null
            return sample(c).map { doubleArrayOf(it.x, it.y) }
        }
        if (cptr != 0) {
            val c = curve(cptr) ?: return null
            var hint: DoubleArray? = null
            val raw = sample(c).map { p -> s.project(p, hint).also { hint = it } }
            return BrepTessellator.unwrap(raw, s.uPeriod, s.vPeriod)
        }
        if (bptr != 0) {
            val c = curve(bptr) ?: return null
            return sample(c).map { doubleArrayOf(it.x, it.y) }
        }
        return null
    }

    private val tri = FloatArray(9)
    private val nrm = FloatArray(9)

    private fun emit(mesh: BrepTessellator.Mesh, color: Int) {
        for (t in mesh.triangles) {
            if (out.truncated) return
            var any = false
            for (k in 0 until 3) {
                val p = mesh.positions[t[k]]
                tri[k * 3] = p.x.toFloat(); tri[k * 3 + 1] = p.y.toFloat(); tri[k * 3 + 2] = p.z.toFloat()
                val n = mesh.normals.getOrNull(t[k]) ?: Vec3.ZERO
                nrm[k * 3] = n.x.toFloat(); nrm[k * 3 + 1] = n.y.toFloat(); nrm[k * 3 + 2] = n.z.toFloat()
                if (n !== Vec3.ZERO) any = true
            }
            out.triangleN(tri, color, null, -1, 0, 0, 0, if (any) nrm else null)
        }
    }

    private fun trimmed(en: IgesEntity, color: Int) {
        val s = surface(en.int(0)) ?: return
        val n1 = en.int(1)
        val n2 = en.int(2)
        val outerDe = en.int(3)
        val outer: List<DoubleArray> = if (n1 == 0 || outerDe == 0) {
            if (s !is NurbsSurface) return
            listOf(doubleArrayOf(s.u0, s.v0), doubleArrayOf(s.u1, s.v0), doubleArrayOf(s.u1, s.v1), doubleArrayOf(s.u0, s.v1))
                .let { corners -> densify(corners, 24) }
        } else {
            val cos = e[outerDe] ?: return
            paramLoop(cos, s) ?: return
        }
        val holes = (0 until n2).mapNotNull { k -> e[en.int(4 + k)]?.let { paramLoop(it, s) } }
        val mesh = BrepTessellator.Mesh()
        BrepTessellator.tessellate(s, outer, holes, tolerance, mesh)
        emit(mesh, color)
    }

    private fun densify(poly: List<DoubleArray>, n: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            for (k in 0 until n) out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n))
        }
        return out
    }

    private fun bounded(en: IgesEntity, color: Int) {
        val s = surface(en.int(1)) ?: return
        val loops = (0 until en.int(2)).mapNotNull { k ->
            val b = e[en.int(3 + k)] ?: return@mapNotNull null
            val pts = ArrayList<Vec3>()
            val n = b.int(3)
            var p = 4
            for (i in 0 until n) {
                val c = curve(b.int(p)) ?: run { p += 3 + b.int(p + 2); null }
                if (c != null) {
                    var sp = sample(c)
                    if (b.int(p + 1) == 2) sp = sp.reversed()
                    pts.addAll(sp)
                    p += 3 + b.int(p + 2)
                }
            }
            var hint: DoubleArray? = null
            BrepTessellator.unwrap(pts.map { q -> s.project(q, hint).also { hint = it } }, s.uPeriod, s.vPeriod)
        }
        if (loops.isEmpty()) return
        val mesh = BrepTessellator.Mesh()
        BrepTessellator.tessellate(s, loops[0], loops.drop(1), tolerance, mesh)
        emit(mesh, color)
    }

    private fun brepFace(face: IgesEntity, color: Int) {
        val s = surface(face.int(0)) ?: return
        val n = face.int(1)
        val loops = ArrayList<List<DoubleArray>>()
        for (k in 0 until n) {
            val loop = e[face.int(3 + k)] ?: continue
            val pts = ArrayList<Vec3>()
            val ne = loop.int(0)
            var p = 1
            for (i in 0 until ne) {
                val type = loop.int(p)
                val listDe = loop.int(p + 1)
                val ndx = loop.int(p + 2)
                val orient = loop.int(p + 3)
                val kk = loop.int(p + 4)
                p += 5 + kk * 2
                if (type != 0) continue
                val el = e[listDe] ?: continue
                val base = 1 + (ndx - 1) * 5
                val c = curve(el.int(base)) ?: continue
                var sp = sample(c)
                if (orient == 0) sp = sp.reversed()
                pts.addAll(sp)
            }
            if (pts.size < 3) continue
            var hint: DoubleArray? = null
            loops.add(BrepTessellator.unwrap(pts.map { q -> s.project(q, hint).also { hint = it } }, s.uPeriod, s.vPeriod))
        }
        if (loops.isEmpty()) return
        val mesh = BrepTessellator.Mesh()
        BrepTessellator.tessellate(s, loops[0], loops.drop(1), tolerance, mesh)
        emit(mesh, color)
    }

    fun load(): Boolean {
        var minV = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var maxV = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (en in e.values) if (en.type == 128) {
            val k1 = en.int(0); val k2 = en.int(1); val m1 = en.int(2); val m2 = en.int(3)
            val p = 9 + (k1 + m1 + 2) + (k2 + m2 + 2) + (k1 + 1) * (k2 + 1)
            for (i in 0 until (k1 + 1) * (k2 + 1)) {
                val x = en.num(p + i * 3); val y = en.num(p + i * 3 + 1); val z = en.num(p + i * 3 + 2)
                minV = Vec3(minOf(minV.x, x), minOf(minV.y, y), minOf(minV.z, z))
                maxV = Vec3(maxOf(maxV.x, x), maxOf(maxV.y, y), maxOf(maxV.z, z))
            }
        } else if (en.type == 110) {
            for (o in listOf(0, 3)) {
                val x = en.num(o); val y = en.num(o + 1); val z = en.num(o + 2)
                minV = Vec3(minOf(minV.x, x), minOf(minV.y, y), minOf(minV.z, z))
                maxV = Vec3(maxOf(maxV.x, x), maxOf(maxV.y, y), maxOf(maxV.z, z))
            }
        }
        val diag = (maxV - minV).len().takeIf { it.isFinite() && it > 0 } ?: 1.0
        tolerance = diag * 0.0015
        var any = false
        val brepFaces = HashSet<Int>()
        for (en in e.values) {
            if (out.truncated) break
            if (en.blanked) continue
            when (en.type) {
                144 -> if (!en.subordinate) { trimmed(en, color(en)); any = true }
                143 -> if (!en.subordinate) { bounded(en, color(en)); any = true }
                186 -> {
                    val shell = e[en.int(0)] ?: continue
                    for (k in 0 until shell.int(0)) {
                        val f = e[shell.int(1 + k * 2)] ?: continue
                        brepFaces.add(f.de)
                        brepFace(f, color(f).takeIf { it != 0 } ?: color(en))
                    }
                    any = true
                }
            }
        }
        for (en in e.values) {
            if (out.truncated) break
            if (en.blanked || en.subordinate) continue
            when (en.type) {
                128, 118, 120, 122 -> {
                    val s = surface(en.de) ?: continue
                    val (u0, u1, v0, v1) = when (s) {
                        is NurbsSurface -> listOf(s.u0, s.u1, s.v0, s.v1)
                        is RevolutionSurface -> listOf(en.num(2), en.num(3), curve(en.int(1))?.t0 ?: 0.0, curve(en.int(1))?.t1 ?: 1.0)
                        is ExtrusionSurface -> listOf(curve(en.int(0))?.t0 ?: 0.0, curve(en.int(0))?.t1 ?: 1.0, 0.0, 1.0)
                        else -> listOf(0.0, 1.0, 0.0, 1.0)
                    }
                    val ring = densify(listOf(doubleArrayOf(u0, v0), doubleArrayOf(u1, v0), doubleArrayOf(u1, v1), doubleArrayOf(u0, v1)), 24)
                    val mesh = BrepTessellator.Mesh()
                    BrepTessellator.tessellate(s, ring, emptyList(), tolerance, mesh)
                    emit(mesh, color(en))
                    any = true
                }
                510 -> if (en.de !in brepFaces) { brepFace(en, color(en)); any = true }
                100, 102, 104, 106, 110, 126 -> {
                    val c = curve(en.de) ?: continue
                    val pts = sample(c)
                    val col = color(en)
                    for (k in 0 until pts.size - 1) out.line(pts[k].x.toFloat(), pts[k].y.toFloat(), pts[k].z.toFloat(), pts[k + 1].x.toFloat(), pts[k + 1].y.toFloat(), pts[k + 1].z.toFloat(), col)
                    any = true
                }
            }
        }
        return any
    }
}
