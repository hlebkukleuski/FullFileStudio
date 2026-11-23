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
 * Portions ported and adapted from Blender (https://www.blender.org/),
 * Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class PathPoint(val vec: FloatArray, val tilt: Float, val radius: Float) {
    var dir = floatArrayOf(0f, 0f, 1f)
    var fixedDir: FloatArray? = null
    var quat = floatArrayOf(1f, 0f, 0f, 0f)
}

internal class PathSample(val vec: FloatArray, val dir: FloatArray, val quat: FloatArray, val radius: Float)

internal class BlendPath(val points: List<PathPoint>, val cyclic: Boolean) {
    val accum: FloatArray
    val length: Float
    val minBound: FloatArray
    val maxBound: FloatArray

    init {
        val n = points.size
        val segs = if (cyclic) n else n - 1
        accum = FloatArray(maxOf(segs, 1))
        var acc = 0f
        for (i in 0 until segs) {
            val a = points[i].vec
            val b = points[(i + 1) % n].vec
            acc += RigMath.len(RigMath.sub(b, a))
            accum[i] = acc
        }
        length = acc
        minBound = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        maxBound = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in points) for (k in 0 until 3) {
            minBound[k] = minOf(minBound[k], p.vec[k])
            maxBound[k] = maxOf(maxBound[k], p.vec[k])
        }
    }

    private fun pt(i: Int): PathPoint {
        val n = points.size
        return if (cyclic) points[((i % n) + n) % n] else points[i.coerceIn(0, n - 1)]
    }

    fun where(ctime: Float): PathSample? {
        val n = points.size
        if (n < 2 || accum.isEmpty()) return if (n == 1) PathSample(points[0].vec, points[0].dir, points[0].quat, points[0].radius) else null
        val segs = accum.size
        val goal = ctime * accum[segs - 1]
        val idx: Int
        val frac: Float
        if (ctime <= 0f || ctime >= 1f) {
            idx = (ctime.coerceIn(0f, 1f) * segs).toInt().coerceIn(0, segs - 1)
            frac = if (idx == 0) (if (accum[0] != 0f) goal / accum[0] else 0f) else {
                val d = accum[idx] - accum[idx - 1]
                if (d != 0f) (goal - accum[idx - 1]) / d else 0f
            }
        } else {
            var lo = 0
            var hi = segs - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (accum[mid] < goal) lo = mid + 1 else hi = mid
            }
            idx = lo
            val prev = if (idx > 0) accum[idx - 1] else 0f
            val d = accum[idx] - prev
            frac = if (d != 0f) (goal - prev) / d else 0f
        }
        val p0 = pt(idx - 1)
        val p1 = pt(idx)
        val p2 = pt(idx + 1)
        val p3 = pt(idx + 2)
        val t = frac
        val t2 = t * t
        val t3 = t2 * t
        val tw = floatArrayOf(-0.5f * t2 + t - 0.5f, 1.5f * t2 - 2f * t, -1.5f * t2 + t + 0.5f, 0.5f * t2)
        val dir = FloatArray(3) { k -> -(tw[0] * p0.dir[k] + tw[1] * p1.dir[k] + tw[2] * p2.dir[k] + tw[3] * p3.dir[k]) }
        val w = floatArrayOf(0f, 1f - t, t, 0f)
        val vec = FloatArray(3) { k -> w[1] * p1.vec[k] + w[2] * p2.vec[k] }
        val radius = w[1] * p1.radius + w[2] * p2.radius
        val tot1 = w[0] + w[3]
        val q1 = if (tot1 > 1.1920929E-7f) RigMath.slerp(p0.quat, p3.quat, w[3] / tot1) else p1.quat
        val tot2 = w[1] + w[2]
        val q2 = if (tot2 > 1.1920929E-7f) RigMath.slerp(p1.quat, p2.quat, w[2] / tot2) else p3.quat
        val tot = tot1 + tot2
        val q = if (tot > 1.1920929E-7f) RigMath.slerp(q1, q2, (w[1] + w[2]) / tot) else q2
        return PathSample(vec, dir, q, radius)
    }

    companion object {
        fun quatMul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        )

        fun axisAngleQuat(axis: FloatArray, angle: Float): FloatArray {
            val n = RigMath.norm(axis)
            val s = sin(angle / 2)
            return floatArrayOf(cos(angle / 2), n[0] * s, n[1] * s, n[2] * s)
        }

        fun rotate(q: FloatArray, v: FloatArray): FloatArray = RigMath.dir(RigMath.quatMat(q), v)

        fun normalizeQ(q: FloatArray): FloatArray {
            val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            return if (l > 0f) floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l) else floatArrayOf(1f, 0f, 0f, 0f)
        }

        fun vecToQuat(vec: FloatArray, axis0: Int, upflag: Int): FloatArray {
            var q = floatArrayOf(1f, 0f, 0f, 0f)
            val len = RigMath.len(vec)
            if (len == 0f) return q
            var axis = axis0
            val tvec = if (axis > 2) {
                axis -= 3
                vec.copyOf()
            } else RigMath.scale(vec, -1f)
            val nor = FloatArray(3)
            val co: Float
            val eps = 1e-4f
            when (axis) {
                0 -> {
                    nor[1] = -tvec[2]; nor[2] = tvec[1]
                    if (abs(tvec[1]) + abs(tvec[2]) < eps) nor[1] = 1f
                    co = tvec[0]
                }
                1 -> {
                    nor[0] = tvec[2]; nor[2] = -tvec[0]
                    if (abs(tvec[0]) + abs(tvec[2]) < eps) nor[2] = 1f
                    co = tvec[1]
                }
                else -> {
                    nor[0] = -tvec[1]; nor[1] = tvec[0]
                    if (abs(tvec[0]) + abs(tvec[1]) < eps) nor[0] = 1f
                    co = tvec[2]
                }
            }
            val c = (co / len).coerceIn(-1f, 1f)
            q = axisAngleQuat(RigMath.norm(nor), acos(c))
            if (axis != upflag) {
                val m = RigMath.quatMat(q)
                val fp = floatArrayOf(m[8], m[9], m[10])
                val angle = when (axis) {
                    0 -> if (upflag == 1) 0.5f * atan2(fp[2], fp[1]) else -0.5f * atan2(fp[1], fp[2])
                    1 -> if (upflag == 0) -0.5f * atan2(fp[2], fp[0]) else 0.5f * atan2(fp[0], fp[2])
                    else -> if (upflag == 0) 0.5f * atan2(-fp[1], -fp[0]) else -0.5f * atan2(-fp[0], -fp[1])
                }
                val si = sin(angle) / len
                val q2 = floatArrayOf(cos(angle), tvec[0] * si, tvec[1] * si, tvec[2] * si)
                q = quatMul(q2, q)
            }
            return q
        }

        private val h = sqrt(0.5).toFloat()
        private val track = arrayOf(
            floatArrayOf(h, 0f, -h, 0f), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), floatArrayOf(h, 0f, 0f, h),
            floatArrayOf(h, 0f, h, 0f), floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f), floatArrayOf(0f, h, h, 0f)
        )

        fun applyTrack(quat: FloatArray, axis0: Int, upflag: Int): FloatArray {
            val axisIdx = axis0.coerceIn(0, 5)
            var q = quatMul(quat, track[axisIdx])
            val axis = if (axisIdx > 2) axisIdx - 3 else axisIdx
            if (upflag != (2 - axis) shr 1) {
                val r = floatArrayOf(h, 0f, 0f, 0f)
                r[axis + 1] = if (axis == 1) h else -h
                q = quatMul(q, r)
            }
            return q
        }

        fun applyTrackVec(v: FloatArray, axis: Int): FloatArray {
            val t = v.copyOf()
            return when (axis) {
                0, 3 -> floatArrayOf(v[0], t[2], -t[1])
                4 -> floatArrayOf(-t[2], v[1], t[0])
                5 -> floatArrayOf(-t[0], -t[1], v[2])
                else -> v.copyOf()
            }
        }
    }
}

internal object BlendCurves {

    private fun bezierPoints(f: BlendFile, nu: BlendFile.View, res: Int, cyclic: Boolean): List<PathPoint>? {
        val st = f.struct("BezTriple") ?: return null
        val at = f.offsetOf(nu.ptr("bezt")) ?: return null
        val n = nu.int("pntsu")
        if (n <= 0) return null
        val vec = st.fields["vec"]?.offset ?: 0
        val tiltF = st.fields["tilt"] ?: st.fields["alfa"]
        val radF = st.fields["radius"]
        fun p(i: Int, k: Int) = FloatArray(3) { c -> f.bb.getFloat(at + i * st.size + vec + k * 12 + c * 4) }
        fun tilt(i: Int) = tiltF?.let { f.bb.getFloat(at + i * st.size + it.offset) } ?: 0f
        fun rad(i: Int) = radF?.let { f.bb.getFloat(at + i * st.size + it.offset) } ?: 1f
        val ease = nu.int("tilt_interp") == 3
        val out = ArrayList<PathPoint>()
        val segs = if (cyclic) n else n - 1
        if (n == 1) return listOf(PathPoint(p(0, 1), tilt(0), rad(0)))
        for (s in 0 until segs) {
            val i0 = if (cyclic) (s + n - 1) % n else s
            val i1 = (i0 + 1) % n
            val a = p(i0, 1)
            val b = p(i0, 2)
            val c = p(i1, 0)
            val d = p(i1, 1)
            for (j in 0 until res) {
                val t = j.toFloat() / res
                val u = 1 - t
                val pos = FloatArray(3) { k -> u * u * u * a[k] + 3 * u * u * t * b[k] + 3 * u * t * t * c[k] + t * t * t * d[k] }
                val tf = if (ease) t * t * (3f - 2f * t) else t
                out.add(PathPoint(pos, tilt(i0) + (tilt(i1) - tilt(i0)) * tf, rad(i0) + (rad(i1) - rad(i0)) * tf))
            }
        }
        if (!cyclic) {
            out.add(PathPoint(p(n - 1, 1), tilt(n - 1), rad(n - 1)))
            val h0 = RigMath.sub(p(0, 2), p(0, 1))
            if (RigMath.len(h0) > 0f) out.first().fixedDir = RigMath.norm(h0)
            val h1 = RigMath.sub(p(n - 1, 1), p(n - 1, 0))
            if (RigMath.len(h1) > 0f) out.last().fixedDir = RigMath.norm(h1)
        }
        return out
    }

    private fun nurbsKnots(n: Int, order: Int, flag: Int, cyclic: Boolean): FloatArray {
        val total = n + order + if (cyclic) order - 1 else 0
        val k = FloatArray(total)
        if (cyclic) {
            for (i in 0 until total) k[i] = i.toFloat()
            return k
        }
        if (flag and 2 != 0) {
            var v = 0f
            for (i in 0 until total) {
                if (i >= order && i <= n) v += 1f
                k[i] = v
            }
        } else if (flag and 4 != 0) {
            var v = 0f
            for (i in 0 until total) {
                k[i] = v
                if (i >= order - 1 && (i - order + 1) % (order - 1) == 0 && i < n) v += 1f
            }
            for (i in n until total) k[i] = k[n - 1].coerceAtLeast(v)
        } else {
            for (i in 0 until total) k[i] = i.toFloat()
        }
        return k
    }

    private fun basis(knots: FloatArray, order: Int, count: Int, u: Float): FloatArray {
        val nKnots = knots.size
        val b = FloatArray(nKnots)
        var span = -1
        for (i in 0 until nKnots - 1) {
            if (u >= knots[i] && (u < knots[i + 1] || (u == knots[i + 1] && i + 1 == count && knots[i] != knots[i + 1]))) {
                b[i] = 1f
                span = i
                break
            }
        }
        if (span < 0) {
            for (i in nKnots - 2 downTo 0) if (knots[i] < knots[i + 1] && u >= knots[i]) { b[i] = 1f; break }
        }
        for (p in 2..order) {
            for (i in 0 until nKnots - p) {
                var v = 0f
                val d1 = knots[i + p - 1] - knots[i]
                if (d1 != 0f && b[i] != 0f) v += (u - knots[i]) / d1 * b[i]
                val d2 = knots[i + p] - knots[i + 1]
                if (d2 != 0f && b[i + 1] != 0f) v += (knots[i + p] - u) / d2 * b[i + 1]
                b[i] = v
            }
        }
        return b
    }

    private fun nurbsPoints(f: BlendFile, nu: BlendFile.View, res: Int, cyclic: Boolean): List<PathPoint>? {
        val st = f.struct("BPoint") ?: return null
        val at = f.offsetOf(nu.ptr("bp")) ?: return null
        val n = nu.int("pntsu")
        if (n <= 0) return null
        val vecOff = st.fields["vec"]?.offset ?: 0
        val tiltF = st.fields["tilt"] ?: st.fields["alfa"]
        val radF = st.fields["radius"]
        val pts = Array(n) { i -> FloatArray(4) { c -> f.bb.getFloat(at + i * st.size + vecOff + c * 4) } }
        val tilts = FloatArray(n) { i -> tiltF?.let { f.bb.getFloat(at + i * st.size + it.offset) } ?: 0f }
        val rads = FloatArray(n) { i -> radF?.let { f.bb.getFloat(at + i * st.size + it.offset) } ?: 1f }
        if (nu.int("type") == 0) return (0 until n).map { PathPoint(floatArrayOf(pts[it][0], pts[it][1], pts[it][2]), tilts[it], rads[it]) }
        val order = nu.int("orderu").coerceIn(2, n.coerceAtLeast(2))
        if (n < order) return (0 until n).map { PathPoint(floatArrayOf(pts[it][0], pts[it][1], pts[it][2]), tilts[it], rads[it]) }
        val count = if (cyclic) n + order - 1 else n
        val knots = nurbsKnots(n, order, nu.int("flagu"), cyclic)
        val uStart = knots[order - 1]
        val uEnd = if (cyclic) knots[n + order - 1] else knots[n]
        val segs = if (cyclic) n else n - 1
        val total = (res * segs).coerceAtLeast(2)
        val step = (uEnd - uStart) / (total - if (cyclic) 0 else 1)
        val out = ArrayList<PathPoint>()
        for (j in 0 until total) {
            val u = (uStart + j * step).coerceAtMost(uEnd)
            val b = basis(knots, order, count, u)
            val pos = FloatArray(3)
            var wsum = 0f
            var tilt = 0f
            var rad = 0f
            for (i in 0 until count) {
                val bi = b.getOrElse(i) { 0f }
                if (bi == 0f) continue
                val pi = i % n
                val w = bi * pts[pi][3]
                for (k in 0 until 3) pos[k] += w * pts[pi][k]
                tilt += w * tilts[pi]
                rad += w * rads[pi]
                wsum += w
            }
            if (wsum != 0f) {
                for (k in 0 until 3) pos[k] /= wsum
                tilt /= wsum
                rad /= wsum
            }
            out.add(PathPoint(pos, tilt, rad))
        }
        return out
    }

    fun path(f: BlendFile, curveData: BlendFile.View, items: (BlendFile.View?, String) -> List<BlendFile.View>): BlendPath? {
        val nu = items(curveData.sub("nurb"), "Nurb").firstOrNull() ?: return null
        val cyclic = nu.int("flagu") and 1 != 0
        val res = (nu.int("resolu").takeIf { it > 0 } ?: curveData.int("resolu", 12)).coerceIn(1, 1024)
        val pts = when (nu.int("type")) {
            1 -> bezierPoints(f, nu, res, cyclic)
            else -> nurbsPoints(f, nu, res, cyclic)
        } ?: return null
        if (pts.isEmpty()) return null
        val is3d = curveData.int("flag") and 1 != 0
        orient(pts, cyclic, if (is3d) curveData.int("twist_mode", 1) else 0, is3d)
        return BlendPath(pts, cyclic)
    }

    private fun orient(pts: List<PathPoint>, cyclic: Boolean, twist: Int, apply3d: Boolean) {
        val n = pts.size
        for (i in 0 until n) {
            val cur = pts[i].vec
            val prev = if (i > 0) pts[i - 1].vec else if (cyclic) pts[n - 1].vec else null
            val next = if (i < n - 1) pts[i + 1].vec else if (cyclic) pts[0].vec else null
            val d = when {
                prev != null && next != null -> {
                    val a = RigMath.norm(RigMath.sub(cur, prev))
                    val b = RigMath.norm(RigMath.sub(next, cur))
                    RigMath.add(a, b).let { if (RigMath.len(it) < 1e-8f) b else it }
                }
                next != null -> RigMath.sub(next, cur)
                prev != null -> RigMath.sub(cur, prev)
                else -> floatArrayOf(0f, 0f, 1f)
            }
            pts[i].dir = pts[i].fixedDir?.takeIf { !cyclic } ?: RigMath.norm(d)
        }
        if (!apply3d || twist != 1) {
            for (p in pts) p.quat = BlendPath.vecToQuat(p.dir, 5, 1)
        } else {
            for (i in 0 until n) {
                if (i == 0) {
                    pts[i].quat = BlendPath.vecToQuat(pts[i].dir, 5, 1)
                    continue
                }
                val prev = pts[i - 1]
                val cur = pts[i]
                val angle = acos(RigMath.dot(prev.dir, cur.dir).coerceIn(-1f, 1f))
                cur.quat = if (angle > 0f) {
                    val axis = RigMath.cross(prev.dir, cur.dir)
                    if (RigMath.len(axis) > 0f) BlendPath.quatMul(BlendPath.axisAngleQuat(axis, angle), prev.quat) else prev.quat.copyOf()
                } else prev.quat.copyOf()
            }
            if (cyclic && n > 2) {
                val first = pts[n - 1]
                val last = pts[n - 2]
                var v1 = RigMath.norm(BlendPath.rotate(first.quat, floatArrayOf(0f, 1f, 0f)))
                var v2 = RigMath.norm(BlendPath.rotate(last.quat, floatArrayOf(0f, 1f, 0f)))
                val cr = RigMath.cross(last.dir, first.dir)
                val a0 = acos(RigMath.dot(first.dir, last.dir).coerceIn(-1f, 1f))
                if (RigMath.len(cr) > 0f) v2 = BlendPath.rotate(BlendPath.axisAngleQuat(cr, a0), v2)
                var angle = acos(RigMath.dot(v1, v2).coerceIn(-1f, 1f))
                val c2 = RigMath.norm(RigMath.cross(v1, v2))
                if (acos(RigMath.dot(first.dir, c2).coerceIn(-1f, 1f)) < (PI / 2).toFloat()) angle = -angle
                for (i in 0 until n) {
                    val nr = n - 1 - i
                    val fac = angle * (1f - nr.toFloat() / n)
                    pts[i].quat = BlendPath.quatMul(BlendPath.axisAngleQuat(pts[i].dir, fac), pts[i].quat)
                }
            }
        }
        if (apply3d) for (p in pts) {
            if (p.tilt != 0f) p.quat = BlendPath.quatMul(BlendPath.axisAngleQuat(p.dir, p.tilt), p.quat)
            p.quat = BlendPath.normalizeQ(p.quat)
        }
    }
}

internal object SplineMath {
    class Tail(val tail: FloatArray, val pos: Float, val radius: Float, val seg: Int)

    fun axisSort(v0: FloatArray): IntArray {
        val v = v0.copyOf()
        val o = intArrayOf(0, 1, 2)
        fun swap(a: Int, b: Int) {
            val t = v[a]; v[a] = v[b]; v[b] = t
            val u = o[a]; o[a] = o[b]; o[b] = u
        }
        if (v[0] < v[1]) {
            if (v[2] < v[0]) swap(0, 2)
        } else {
            if (v[1] < v[2]) swap(0, 1) else swap(0, 2)
        }
        if (v[2] < v[1]) swap(1, 2)
        return o
    }

    fun tailOnSpline(path: BlendPath, head: FloatArray, sphere: Float, prevSeg0: Int, curvePos: Float): Tail {
        val acc = path.accum
        val maxSeg = acc.size - 1
        val splineLen = acc[maxSeg]
        val guessed = curvePos * splineLen
        var cur = prevSeg0
        while (cur < maxSeg && guessed > acc[cur]) cur++
        var bpIdx = cur + 1
        val pts = path.points
        fun at(i: Int) = pts[i.coerceIn(0, pts.size - 1)]
        var bp = bpIdx
        var prev = bp - 1
        while (RigMath.len(RigMath.sub(head, at(bp).vec)) < sphere) {
            if (bpIdx > maxSeg) break
            prev = bp
            bp = if (path.cyclic && bpIdx == maxSeg) 0 else bp + 1
            bpIdx++
        }
        val bpv = at(bp).vec
        var x0 = 0f
        var x1 = 1f
        val eps = maxOf(1f, RigMath.len(head), RigMath.len(bpv)) * 1.1920929E-7f
        val start = if (prevSeg0 == bpIdx - 1) head else at(prev).vec
        var p0 = start
        var p1 = bpv
        for (i in 0 until 10) {
            p0 = RigMath.lerp(start, bpv, x0)
            p1 = RigMath.lerp(start, bpv, x1)
            val f0 = sphere - RigMath.len(RigMath.sub(head, p0))
            val f1 = sphere - RigMath.len(RigMath.sub(head, p1))
            if (abs(f1) <= eps || f0 == f1) break
            val x2 = x1 - f1 * (x1 - x0) / (f1 - f0)
            x0 = x1
            x1 = x2
        }
        val tail = p1
        var ps = bpIdx - 2
        val prevLen = if (ps < 0) { ps = 0; 0f } else acc[ps]
        val pv = at(prev).vec
        val isect = RigMath.len(RigMath.sub(tail, pv))
        val frac = isect / RigMath.len(RigMath.sub(bpv, pv))
        val pos = (prevLen + isect) / splineLen
        val radius = if (pos > 1f) at(bp).radius else (1f - frac) * at(prev).radius + frac * at(bp).radius
        return Tail(tail, pos, radius, bpIdx - 1)
    }
}
