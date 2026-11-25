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
 * Copyright (C) 2001-2002 NaN Holding B.V., Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Includes Selectively Damped Least Squares (SDLS) inverse kinematics algorithm
 * based on Samuel R. Buss and Jin-Su Kim (intern/iksolver/intern/IK_QJacobian.cpp).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser

internal object RigMath {
    val I: FloatArray get() = GltfParser.IDENTITY.copyOf()

    fun mul(a: FloatArray, b: FloatArray): FloatArray = GltfParser.mul(a, b)

    fun mul(vararg m: FloatArray): FloatArray {
        var r = m[0]
        for (i in 1 until m.size) r = mul(r, m[i])
        return r
    }

    fun inv(m: FloatArray): FloatArray = BlendAnimator.invert(m)

    fun col(m: FloatArray, c: Int) = floatArrayOf(m[c * 4], m[c * 4 + 1], m[c * 4 + 2])

    fun setCol(m: FloatArray, c: Int, v: FloatArray) {
        m[c * 4] = v[0]; m[c * 4 + 1] = v[1]; m[c * 4 + 2] = v[2]
    }

    fun loc(m: FloatArray) = floatArrayOf(m[12], m[13], m[14])

    fun len(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)

    fun norm(a: FloatArray): FloatArray {
        val l = len(a)
        return if (l > 0f) scale(a, 1f / l) else a.copyOf()
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    fun point(m: FloatArray, p: FloatArray) = BlendAnimator.transformPoint(m, p[0], p[1], p[2])

    fun dir(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2]
    )

    fun size(m: FloatArray) = floatArrayOf(BlendAnimator.axisLength(m, 0), BlendAnimator.axisLength(m, 1), BlendAnimator.axisLength(m, 2))

    fun det3(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun rot3(m: FloatArray): FloatArray {
        val r = BlendAnimator.normalizedAxes(m)
        r[12] = 0f; r[13] = 0f; r[14] = 0f
        return r
    }

    fun withLoc(m: FloatArray, l: FloatArray): FloatArray = m.copyOf().also { it[12] = l[0]; it[13] = l[1]; it[14] = l[2] }

    fun locRotSize(m: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val s = size(m)
        val r = I
        for (c in 0 until 3) for (k in 0 until 3) r[c * 4 + k] = if (s[c] != 0f) m[c * 4 + k] / s[c] else if (c == k) 1f else 0f
        if (det3(r) < 0f) {
            for (i in 0 until 11) if (i % 4 != 3) r[i] = -r[i]
            s[0] = -s[0]; s[1] = -s[1]; s[2] = -s[2]
        }
        return Triple(loc(m), r, s)
    }

    fun compose(l: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val m = I
        for (c in 0 until 3) for (k in 0 until 3) m[c * 4 + k] = r[c * 4 + k] * s[c]
        m[12] = l[0]; m[13] = l[1]; m[14] = l[2]
        return m
    }

    fun eul(e: FloatArray, order: Int): FloatArray = BlendAnimator.eulerMatrix(e, order.coerceIn(1, 6))

    fun toEul(m: FloatArray, order: Int): FloatArray = BlendAnimator.matToEuler(BlendAnimator.normalizedAxes(m), order.coerceIn(1, 6))

    fun compatible(e: FloatArray, old: FloatArray) {
        val pi2 = (2 * PI).toFloat()
        for (i in 0 until 3) {
            val d = e[i] - old[i]
            if (d > PI.toFloat()) e[i] -= floor(d / pi2 + 0.5f) * pi2
            else if (d < -PI.toFloat()) e[i] += floor(-d / pi2 + 0.5f) * pi2
        }
    }

    fun toCompatibleEul(m: FloatArray, old: FloatArray, order: Int): FloatArray {
        val o = intArrayOf(0, 1, 2, 0, 0, 2, 1, 1, 1, 0, 2, 1, 1, 2, 0, 0, 2, 0, 1, 0, 2, 1, 0, 1)
        val oi = (order.coerceIn(1, 6) - 1) * 4
        val i = o[oi]
        val j = o[oi + 1]
        val k = o[oi + 2]
        val parity = o[oi + 3] == 1
        val n = BlendAnimator.normalizedAxes(m)
        fun a(c: Int, r: Int) = n[c * 4 + r]
        val e1 = FloatArray(3)
        val e2 = FloatArray(3)
        val cy = kotlin.math.hypot(a(i, i), a(i, j))
        if (cy > 16f * 1.1920929E-7f) {
            e1[i] = kotlin.math.atan2(a(j, k), a(k, k)); e1[j] = kotlin.math.atan2(-a(i, k), cy); e1[k] = kotlin.math.atan2(a(i, j), a(i, i))
            e2[i] = kotlin.math.atan2(-a(j, k), -a(k, k)); e2[j] = kotlin.math.atan2(-a(i, k), -cy); e2[k] = kotlin.math.atan2(-a(i, j), -a(i, i))
        } else {
            e1[i] = kotlin.math.atan2(-a(k, j), a(j, j)); e1[j] = kotlin.math.atan2(-a(i, k), cy); e1[k] = 0f
            e1.copyInto(e2)
        }
        if (parity) for (q in 0 until 3) { e1[q] = -e1[q]; e2[q] = -e2[q] }
        compatible(e1, old)
        compatible(e2, old)
        val d1 = abs(e1[0] - old[0]) + abs(e1[1] - old[1]) + abs(e1[2] - old[2])
        val d2 = abs(e2[0] - old[0]) + abs(e2[1] - old[1]) + abs(e2[2] - old[2])
        return if (d1 > d2) e2 else e1
    }

    fun quat(m: FloatArray): FloatArray {
        val q = BlendAnimator.matToQuat(BlendAnimator.normalizedAxes(m))
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (l > 0f) floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l) else floatArrayOf(1f, 0f, 0f, 0f)
    }

    fun quatMat(q: FloatArray): FloatArray = BlendAnimator.quatMatrix(q)

    fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
        var b = b0
        var c = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        if (c < 0f) {
            c = -c
            b = floatArrayOf(-b[0], -b[1], -b[2], -b[3])
        }
        val w0: Float
        val w1: Float
        if (1f - c > 0.0001f) {
            val om = acos(c.coerceAtMost(1f))
            val so = sin(om)
            w0 = sin((1f - t) * om) / so
            w1 = sin(t * om) / so
        } else {
            w0 = 1f - t
            w1 = t
        }
        return floatArrayOf(w0 * a[0] + w1 * b[0], w0 * a[1] + w1 * b[1], w0 * a[2] + w1 * b[2], w0 * a[3] + w1 * b[3])
    }

    fun axisAngle(axis: FloatArray, angle: Float): FloatArray = BlendAnimator.axisAngle(axis[0], axis[1], axis[2], angle)

    private fun sym3Eigen(a: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val m = a.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(50) {
            var p = 0
            var q = 1
            var best = abs(m[1])
            if (abs(m[2]) > best) { p = 0; q = 2; best = abs(m[2]) }
            if (abs(m[5]) > best) { p = 1; q = 2; best = abs(m[5]) }
            if (best < 1e-15) return@repeat
            val app = m[p * 3 + p]
            val aqq = m[q * 3 + q]
            val apq = m[p * 3 + q]
            val theta = (aqq - app) / (2 * apq)
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1)
            val s = t * c
            for (k in 0 until 3) {
                val mkp = m[k * 3 + p]
                val mkq = m[k * 3 + q]
                m[k * 3 + p] = c * mkp - s * mkq
                m[k * 3 + q] = s * mkp + c * mkq
            }
            for (k in 0 until 3) {
                val mpk = m[p * 3 + k]
                val mqk = m[q * 3 + k]
                m[p * 3 + k] = c * mpk - s * mqk
                m[q * 3 + k] = s * mpk + c * mqk
            }
            for (k in 0 until 3) {
                val vkp = v[k * 3 + p]
                val vkq = v[k * 3 + q]
                v[k * 3 + p] = c * vkp - s * vkq
                v[k * 3 + q] = s * vkp + c * vkq
            }
        }
        return doubleArrayOf(m[0], m[4], m[8]) to v
    }

    private fun polar(m: FloatArray): Pair<FloatArray, FloatArray> {
        val a = DoubleArray(9) { m[(it % 3) * 4 + it / 3].toDouble() }
        val ata = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[k * 3 + r] * a[k * 3 + c]
            ata[r * 3 + c] = s
        }
        val (ev, vecs) = sym3Eigen(ata)
        val p = DoubleArray(9)
        val pinv = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            var si = 0.0
            for (k in 0 until 3) {
                val sv = sqrt(max(0.0, ev[k]))
                s += vecs[r * 3 + k] * sv * vecs[c * 3 + k]
                si += if (sv > 1e-12) vecs[r * 3 + k] / sv * vecs[c * 3 + k] else 0.0
            }
            p[r * 3 + c] = s
            pinv[r * 3 + c] = si
        }
        val u = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[r * 3 + k] * pinv[k * 3 + c]
            u[r * 3 + c] = s
        }
        val um = I
        val pm = I
        for (r in 0 until 3) for (c in 0 until 3) {
            um[c * 4 + r] = u[r * 3 + c].toFloat()
            pm[c * 4 + r] = p[r * 3 + c].toFloat()
        }
        if (ev.any { it <= 1e-18 }) {
            val n = rot3(m)
            return n to mul(inv(n), withLoc(m, floatArrayOf(0f, 0f, 0f)))
        }
        return um to pm
    }

    fun interp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var (ua, pa) = polar(a)
        var (ub, pb) = polar(b)
        if (det3(ua) < 0f) {
            for (i in 0 until 11) if (i % 4 != 3) { ua[i] = -ua[i]; pa[i] = -pa[i] }
        }
        if (det3(ub) < 0f) {
            for (i in 0 until 11) if (i % 4 != 3) { ub[i] = -ub[i]; pb[i] = -pb[i] }
        }
        val u = quatMat(slerp(quat(ua), quat(ub), t))
        val p = I
        for (i in 0 until 11) if (i % 4 != 3) p[i] = pa[i] + (pb[i] - pa[i]) * t
        val r = mul(u, p)
        r[12] = a[12] + (b[12] - a[12]) * t
        r[13] = a[13] + (b[13] - a[13]) * t
        r[14] = a[14] + (b[14] - a[14]) * t
        return r
    }

    fun alignedScale(a: FloatArray, b: FloatArray): FloatArray {
        val (la, ra, sa) = locRotSize(a)
        val (lb, rb, sb) = locRotSize(b)
        return compose(point(a, lb), mul(ra, rb), floatArrayOf(sa[0] * sb[0], sa[1] * sb[1], sa[2] * sb[2]))
    }

    fun splitChannels(a: FloatArray, b: FloatArray): FloatArray {
        val (la, ra, sa) = locRotSize(a)
        val (lb, rb, sb) = locRotSize(b)
        return compose(add(la, lb), mul(ra, rb), floatArrayOf(sa[0] * sb[0], sa[1] * sb[1], sa[2] * sb[2]))
    }

    fun mulM3M4(r3: FloatArray, m: FloatArray): FloatArray {
        val r = mul(withLoc(r3, floatArrayOf(0f, 0f, 0f)), withLoc(m, floatArrayOf(0f, 0f, 0f)))
        r[12] = m[12]; r[13] = m[13]; r[14] = m[14]
        return r
    }

    fun fromCols(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m = I
        setCol(m, 0, x); setCol(m, 1, y); setCol(m, 2, z)
        return m
    }

    fun project(v: FloatArray, onto: FloatArray): FloatArray {
        val d = dot(onto, onto)
        return if (d == 0f) floatArrayOf(0f, 0f, 0f) else scale(onto, dot(v, onto) / d)
    }

    fun distFactor(p: FloatArray, b: BlendAnimator.BoneRest): Float {
        val h = b.head
        val t = b.tail
        var bx = t[0] - h[0]; var by = t[1] - h[1]; var bz = t[2] - h[2]
        val l = sqrt(bx * bx + by * by + bz * bz)
        if (l > 0f) { bx /= l; by /= l; bz /= l }
        val px = p[0] - h[0]; val py = p[1] - h[1]; val pz = p[2] - h[2]
        val a = bx * px + by * py + bz * pz
        val hsqr = px * px + py * py + pz * pz
        val distSq: Float
        val rad: Float
        if (a < 0f) {
            distSq = hsqr
            rad = b.radHead
        } else if (a > l) {
            val qx = p[0] - t[0]; val qy = p[1] - t[1]; val qz = p[2] - t[2]
            distSq = qx * qx + qy * qy + qz * qz
            rad = b.radTail
        } else {
            distSq = hsqr - a * a
            rad = if (l != 0f) (a / l) * b.radTail + (1f - a / l) * b.radHead else b.radHead
        }
        if (distSq < rad * rad) return 1f
        val lim = (rad + b.dist) * (rad + b.dist)
        if (b.dist == 0f || distSq >= lim) return 0f
        val d = sqrt(distSq) - rad
        return 1f - (d * d) / (b.dist * b.dist)
    }

    fun rotationBetween(a0: FloatArray, b0: FloatArray): FloatArray {
        val a = norm(a0)
        val b = norm(b0)
        val axis = cross(a, b)
        val s = len(axis)
        val c = dot(a, b)
        if (s < 1e-7f) {
            if (c > 0f) return I
            val perp = if (abs(a[0]) < 0.9f) norm(cross(a, floatArrayOf(1f, 0f, 0f))) else norm(cross(a, floatArrayOf(0f, 1f, 0f)))
            return axisAngle(perp, PI.toFloat())
        }
        return axisAngle(scale(axis, 1f / s), kotlin.math.atan2(s, c))
    }
}

internal class BlendConstraint(
    val type: Int,
    val flag: Int,
    val ownSpace: Int,
    val tarSpace: Int,
    val influence: Float,
    val headTail: Float,
    val data: BlendFile.View,
    val targets: List<Pair<Int?, String>>,
    val weights: List<Float>,
    val spaceObject: Int?,
    val spaceSubtarget: String
) {
    val active: Boolean get() = flag and ((1 shl 2) or (1 shl 9)) == 0 && influence > 0f
}

internal fun readConstraints(f: BlendFile, list: BlendFile.View?, items: (BlendFile.View?, String) -> List<BlendFile.View>): List<BlendConstraint> {
    val out = ArrayList<BlendConstraint>()
    for (c in items(list, "bConstraint")) {
        val type = c.int("type")
        val dataAt = f.offsetOf(c.ptr("data"))
        val structName = when (type) {
            1 -> "bChildOfConstraint"
            2 -> "bTrackToConstraint"
            3 -> "bKinematicConstraint"
            4 -> "bFollowPathConstraint"
            5 -> "bRotLimitConstraint"
            6 -> "bLocLimitConstraint"
            7 -> "bSizeLimitConstraint"
            8 -> "bRotateLikeConstraint"
            9 -> "bLocateLikeConstraint"
            10 -> "bSizeLikeConstraint"
            12 -> "bActionConstraint"
            13 -> "bLockTrackConstraint"
            14 -> "bDistLimitConstraint"
            15 -> "bStretchToConstraint"
            16 -> "bMinMaxConstraint"
            18 -> "bClampToConstraint"
            19 -> "bTransformConstraint"
            21 -> "bDampTrackConstraint"
            22 -> "bSplineIKConstraint"
            23 -> "bTransLikeConstraint"
            24 -> "bSameVolumeConstraint"
            25 -> "bPivotConstraint"
            30 -> "bArmatureConstraint"
            20 -> "bShrinkwrapConstraint"
            else -> null
        } ?: continue
        val data = dataAt?.let { f.view(structName, it) } ?: continue
        val targets = ArrayList<Pair<Int?, String>>()
        val weights = ArrayList<Float>()
        if (type == 30) {
            for (t in items(data.sub("targets"), "bConstraintTarget")) {
                targets.add(f.offsetOf(t.ptr("tar")) to t.str("subtarget"))
                weights.add(t.float("weight", 1f))
            }
        } else if (data.has("tar")) {
            targets.add(f.offsetOf(data.ptr("tar")) to data.str("subtarget"))
            weights.add(1f)
        } else if (type == 20) {
            targets.add(f.offsetOf(data.ptr("target")) to "")
            weights.add(1f)
        }
        out.add(
            BlendConstraint(
                type, c.int("flag"), c.int("ownspace"), c.int("tarspace"), c.float("enforce", 1f), c.float("headtail"),
                data, targets, weights, f.offsetOf(c.ptr("space_object")), c.str("space_subtarget")
            )
        )
    }
    return out
}

internal object ConstraintMath {
    private val trackDirs = arrayOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
        floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, -1f)
    )

    fun trackTo(own: FloatArray, tar: FloatArray, axis0: Int, up: Int, targetZ: Boolean): FloatArray {
        val s = RigMath.size(own)
        val m = own.copyOf()
        for (c in 0 until 3) for (k in 0 until 3) m[c * 4 + k] = if (c == k) s[c] else 0f
        var n = RigMath.sub(RigMath.loc(own), RigMath.loc(tar))
        val nl = RigMath.len(n)
        n = if (nl == 0f) floatArrayOf(0f, 0f, 1f) else RigMath.scale(n, 1f / nl)
        var axis = axis0
        if (axis > 2) axis -= 3 else n = RigMath.scale(n, -1f)
        val u = if (targetZ) RigMath.col(tar, 2) else floatArrayOf(0f, 0f, 1f)
        var proj = RigMath.sub(u, RigMath.project(u, n))
        val pl = RigMath.len(proj)
        proj = if (pl == 0f) floatArrayOf(0f, 1f, 0f) else RigMath.scale(proj, 1f / pl)
        val right = RigMath.norm(RigMath.cross(proj, n))
        val r = RigMath.I
        if (axis != up && up in 0..2) {
            val ri = 3 - axis - up
            val neg = if ((axis + 1) % 3 == up) -1f else 1f
            RigMath.setCol(r, ri, RigMath.scale(right, neg))
            RigMath.setCol(r, up, proj)
            RigMath.setCol(r, axis, n)
        }
        return RigMath.mulM3M4(r, m)
    }

    fun dampTrack(own: FloatArray, tar: FloatArray, axis: Int): FloatArray = dampTrackVec(own, RigMath.sub(RigMath.loc(tar), RigMath.loc(own)), axis)

    fun dampTrackVec(own: FloatArray, tv: FloatArray, axis: Int): FloatArray {
        val tl = RigMath.len(tv)
        if (tl == 0f) return own
        val tarvec = RigMath.scale(tv, 1f / tl)
        var obvec = RigMath.dir(own, trackDirs[axis.coerceIn(0, 5)])
        val ol = RigMath.len(obvec)
        obvec = if (ol == 0f) trackDirs[axis.coerceIn(0, 5)] else RigMath.scale(obvec, 1f / ol)
        var raxis = RigMath.cross(obvec, tarvec)
        var angle = acos(RigMath.dot(obvec, tarvec).coerceIn(-1f, 1f))
        val norm = RigMath.len(raxis)
        if (norm < 1.1920929E-7f) {
            if (abs(angle) < PI.toFloat() - 0.01f) return own
            angle = PI.toFloat()
            val tmp = RigMath.dir(own, trackDirs[(axis + 1) % 6])
            raxis = RigMath.cross(obvec, tmp)
            if (RigMath.len(raxis) == 0f) return own
            raxis = RigMath.norm(raxis)
        } else {
            raxis = RigMath.scale(raxis, 1f / norm)
            if (norm < 0.1f) angle = if (angle > PI.toFloat() / 2) PI.toFloat() - asin(norm) else asin(norm)
        }
        return RigMath.mulM3M4(RigMath.axisAngle(raxis, angle), own)
    }

    fun lockTrack(own: FloatArray, tar: FloatArray, track: Int, lock: Int): FloatArray {
        val vec = RigMath.sub(RigMath.loc(tar), RigMath.loc(own))
        val t = track % 3
        val neg = track > 2
        if (lock !in 0..2 || t == lock) return own
        val lockAxis = RigMath.col(own, lock)
        var proj = RigMath.norm(RigMath.sub(vec, RigMath.project(vec, lockAxis)))
        if (neg) proj = RigMath.scale(proj, -1f)
        val fixed = RigMath.norm(lockAxis)
        val third = 3 - t - lock
        val cyclic = (lock + 1) % 3 == t
        val r = RigMath.I
        RigMath.setCol(r, lock, fixed)
        RigMath.setCol(r, t, proj)
        RigMath.setCol(r, third, if (cyclic) RigMath.cross(fixed, proj) else RigMath.cross(proj, fixed))
        val tmp = RigMath.rot3(own)
        val res = RigMath.mul(r, RigMath.inv(tmp))
        if (abs(RigMath.det3(res)) < 1e-12f) return own
        return RigMath.mulM3M4(res, own)
    }

    fun stretchTo(own: FloatArray, tar: FloatArray, data: BlendFile.View): FloatArray {
        val size = RigMath.size(own)
        val n = BlendAnimator.normalizedAxes(own)
        val xx = RigMath.col(n, 0)
        val zz = RigMath.col(n, 2)
        var vec = RigMath.sub(RigMath.loc(tar), RigMath.loc(own))
        var dist = RigMath.len(vec)
        if (dist > 0f) vec = RigMath.scale(vec, 1f / dist)
        if (size[1] != 0f) dist /= size[1]
        var org = data.float("orglength")
        if (org == 0f) org = dist
        val sc = FloatArray(3)
        sc[1] = if (org != 0f) dist / org else 1f
        var bulge = if (dist != 0f) (org / dist).pow(data.float("bulge", 1f)) else 1f
        val flag = data.int("flag")
        val smooth = data.float("bulge_smooth")
        if (bulge > 1f && flag and 1 != 0) {
            val bmax = max(data.float("bulge_max", 1f), 1f)
            val hard = min(bulge, bmax)
            val range = bmax - 1f
            val f = if (range > 0f) 1f / range else 0f
            val soft = 1f + range * atan((bulge - 1f) * f) / (PI.toFloat() / 2)
            bulge = soft + (hard - soft) * smooth
        }
        if (bulge < 1f && flag and 2 != 0) {
            val bmin = data.float("bulge_min", 1f).coerceIn(0f, 1f)
            val hard = max(bulge, bmin)
            val range = 1f - bmin
            val f = if (range > 0f) 1f / range else 0f
            val soft = 1f - range * atan((1f - bulge) * f) / (PI.toFloat() / 2)
            bulge = soft + (hard - soft) * smooth
        }
        when (data.int("volmode")) {
            0 -> { sc[0] = sqrt(bulge); sc[2] = sc[0] }
            1 -> { sc[0] = bulge; sc[2] = 1f }
            2 -> { sc[0] = 1f; sc[2] = bulge }
            else -> { sc[0] = 1f; sc[2] = 1f }
        }
        val m = own.copyOf()
        for (c in 0 until 3) for (k in 0 until 3) m[c * 4 + k] = if (c == k) size[c] * sc[c] else 0f
        val r = when (data.int("plane")) {
            2 -> {
                val orth = RigMath.norm(RigMath.cross(vec, zz))
                RigMath.fromCols(orth, vec, RigMath.norm(RigMath.cross(orth, vec)))
            }
            3 -> RigMath.mul(RigMath.rotationBetween(RigMath.col(n, 1), vec), RigMath.rot3(own))
            else -> {
                val orth = RigMath.norm(RigMath.cross(xx, vec))
                RigMath.fromCols(RigMath.norm(RigMath.cross(vec, orth)), vec, orth)
            }
        }
        return RigMath.mulM3M4(r, m)
    }

    fun limitLoc(m: FloatArray, d: BlendFile.View): FloatArray {
        val r = m.copyOf()
        val flag = d.int("flag")
        val mins = floatArrayOf(d.float("xmin"), d.float("ymin"), d.float("zmin"))
        val maxs = floatArrayOf(d.float("xmax"), d.float("ymax"), d.float("zmax"))
        for (i in 0 until 3) {
            if (flag and (1 shl (i * 2)) != 0 && r[12 + i] < mins[i]) r[12 + i] = mins[i]
            if (flag and (1 shl (i * 2 + 1)) != 0 && r[12 + i] > maxs[i]) r[12 + i] = maxs[i]
        }
        return r
    }

    fun limitRot(m: FloatArray, d: BlendFile.View, ownOrder: Int): FloatArray {
        val flag = d.int("flag")
        val order = d.int("euler_order").takeIf { it in 1..6 } ?: ownOrder
        val (l, rot, s) = RigMath.locRotSize(m)
        val e = RigMath.toEul(rot, order)
        val mins = floatArrayOf(d.float("xmin"), d.float("ymin"), d.float("zmin"))
        val maxs = floatArrayOf(d.float("xmax"), d.float("ymax"), d.float("zmax"))
        for (i in 0 until 3) if (flag and (1 shl i) != 0) e[i] = e[i].coerceIn(mins[i], max(mins[i], maxs[i]))
        return RigMath.compose(l, RigMath.eul(e, order), s)
    }

    fun limitSize(m: FloatArray, d: BlendFile.View): FloatArray {
        val flag = d.int("flag")
        val ob = RigMath.size(m)
        val s = ob.copyOf()
        val mins = floatArrayOf(d.float("xmin"), d.float("ymin"), d.float("zmin"))
        val maxs = floatArrayOf(d.float("xmax"), d.float("ymax"), d.float("zmax"))
        for (i in 0 until 3) {
            if (flag and (1 shl (i * 2)) != 0 && s[i] < mins[i]) s[i] = mins[i]
            if (flag and (1 shl (i * 2 + 1)) != 0 && s[i] > maxs[i]) s[i] = maxs[i]
        }
        val r = m.copyOf()
        for (c in 0 until 3) if (ob[c] != 0f) for (k in 0 until 3) r[c * 4 + k] *= s[c] / ob[c]
        return r
    }

    fun copyLocation(own: FloatArray, tar: FloatArray, d: BlendFile.View): FloatArray {
        val flag = d.int("flag")
        val r = own.copyOf()
        val off = if (flag and 128 != 0) RigMath.loc(own) else floatArrayOf(0f, 0f, 0f)
        for (i in 0 until 3) if (flag and (1 shl i) != 0) {
            var v = tar[12 + i]
            if (flag and (16 shl i) != 0) v = -v
            r[12 + i] = v + off[i]
        }
        return r
    }

    fun copyRotation(own: FloatArray, tar: FloatArray, d: BlendFile.View, ownOrder: Int): FloatArray {
        val flag = d.int("flag")
        val order = d.int("euler_order").takeIf { it in 1..6 } ?: ownOrder
        var mix = if (d.has("mix_mode")) d.int("mix_mode") else 0
        if (mix == 0 && flag and 128 != 0) mix = 1
        val (l, oldRot, s) = RigMath.locRotSize(own)
        val obeul = RigMath.toEul(own, order)
        val tm = RigMath.rot3(tar)
        val e = RigMath.toCompatibleEul(tm, obeul, order)
        val legacy = mix == 1
        val def = if (mix == 0 || mix == 1) obeul else floatArrayOf(0f, 0f, 0f)
        for (i in 0 until 3) {
            if (flag and (1 shl i) == 0) e[i] = def[i]
            else {
                if (legacy) e[i] += obeul[i]
                if (flag and (16 shl i) != 0) e[i] = -e[i]
            }
        }
        if (mix == 2) for (i in 0 until 3) e[i] += obeul[i]
        RigMath.compatible(e, obeul)
        var newRot = RigMath.eul(e, order)
        when (mix) {
            3 -> newRot = RigMath.mul(newRot, oldRot)
            4 -> newRot = RigMath.mul(oldRot, newRot)
        }
        return RigMath.compose(l, newRot, s)
    }

    fun copyScale(own: FloatArray, tar: FloatArray, d: BlendFile.View): FloatArray {
        val flag = d.int("flag")
        val ob = RigMath.size(own)
        val power = if (d.has("power")) d.float("power", 1f) else 1f
        val size: FloatArray
        if (flag and 32 != 0) {
            var total = 1f
            if (flag and 7 == 7) total = abs(RigMath.det3(tar))
            else {
                val ts = RigMath.size(tar)
                for (i in 0 until 3) if (flag and (1 shl i) != 0) total *= ts[i]
            }
            val c = cbrt(total)
            size = floatArrayOf(c, c, c)
        } else size = RigMath.size(tar)
        for (i in 0 until 3) size[i] = size[i].pow(power)
        if (flag and 8 != 0) {
            for (i in 0 until 3) if (flag and (1 shl i) != 0) size[i] += ob[i] - 1f
        } else if (flag and 16 != 0) {
            for (i in 0 until 3) size[i] *= ob[i]
        }
        val r = own.copyOf()
        for (c in 0 until 3) if (flag and (1 shl c) != 0 && ob[c] != 0f) for (k in 0 until 3) r[c * 4 + k] *= size[c] / ob[c]
        return r
    }

    fun copyTransforms(own: FloatArray, tar: FloatArray, mix: Int): FloatArray = when (mix) {
        1 -> RigMath.alignedScale(tar, own)
        2 -> RigMath.alignedScale(own, tar)
        3 -> RigMath.mul(tar, own)
        4 -> RigMath.mul(own, tar)
        5 -> RigMath.splitChannels(tar, own)
        6 -> RigMath.splitChannels(own, tar)
        else -> tar.copyOf()
    }

    fun childOf(own: FloatArray, tar: FloatArray, d: BlendFile.View, ownOrder: Int, tarOrder: Int): FloatArray {
        val flag = d.int("flag")
        val inv = d.floats("invmat", 16) ?: RigMath.I
        val par: FloatArray
        val invm: FloatArray
        if (flag and 511 == 511) {
            par = tar
            invm = inv
        } else {
            val l = RigMath.loc(tar)
            val e = RigMath.toEul(tar, tarOrder)
            val s = RigMath.size(tar)
            val lo = RigMath.loc(inv)
            val eo = RigMath.toEul(inv, ownOrder)
            val so = RigMath.size(inv)
            for (i in 0 until 3) {
                if (flag and (1 shl i) == 0) { l[i] = 0f; lo[i] = 0f }
                if (flag and (8 shl i) == 0) { e[i] = 0f; eo[i] = 0f }
                if (flag and (64 shl i) == 0) { s[i] = 1f; so[i] = 1f }
            }
            par = RigMath.compose(l, RigMath.eul(e, tarOrder), s)
            invm = RigMath.compose(lo, RigMath.eul(eo, ownOrder), so)
        }
        return RigMath.mul(par, invm, own)
    }

    fun distLimit(own: FloatArray, tar: FloatArray, d: BlendFile.View): FloatArray {
        val o = RigMath.loc(own)
        val t = RigMath.loc(tar)
        val dist = RigMath.len(RigMath.sub(o, t))
        val limit = d.float("dist").takeIf { it != 0f } ?: return own
        val soft = d.float("soft")
        val useSoft = d.int("flag") and 1 != 0
        var sfac = 1f
        var clamp = false
        when (d.int("mode")) {
            1 -> if (dist <= limit) { clamp = true; if (dist != 0f) sfac = limit / dist }
            0 -> if (dist >= limit) { clamp = true; if (dist != 0f) sfac = limit / dist } else if (useSoft && dist >= limit - soft && soft != 0f) {
                sfac = soft * (1f - exp(-(dist - limit) / soft)) + limit
                if (dist != 0f) sfac /= dist
                clamp = true
            }
            else -> if (abs(dist - limit) > 1e-6f) { clamp = true; if (dist != 0f) sfac = limit / dist }
        }
        if (!clamp) return own
        return RigMath.withLoc(own, RigMath.lerp(t, o, sfac))
    }

    fun floor(own: FloatArray, tar0: FloatArray, d: BlendFile.View): FloatArray {
        val useRot = d.int("flag") and 4 != 0
        val offset = d.float("offset")
        var ob = own
        var tar = tar0
        if (useRot) {
            ob = RigMath.mul(RigMath.inv(tar0), own)
            tar = RigMath.I
        }
        val mm = d.int("minmaxflag")
        val index = mm % 3
        val (v1, v2) = if (mm < 3) tar[12 + index] to ob[12 + index] - offset else ob[12 + index] - offset to tar[12 + index]
        if (mm !in 0..5 || v1 <= v2) return own
        val r = ob.copyOf()
        r[12 + index] = tar[12 + index] + offset
        return if (useRot) RigMath.mul(tar0, r) else RigMath.withLoc(own, RigMath.loc(r))
    }

    fun sameVolume(own: FloatArray, d: BlendFile.View): FloatArray {
        val volume = d.float("volume", 1f)
        val axis = d.int("flag").coerceIn(0, 2)
        val s = RigMath.size(own)
        val total = when (d.int("mode")) {
            1 -> s[axis] * s[axis] * s[axis]
            2 -> s[axis]
            else -> s[0] * s[1] * s[2]
        }
        if (total == 0f) return own
        val fac = sqrt(volume / total)
        val r = own.copyOf()
        for (c in 0 until 3) if (c != axis) for (k in 0 until 3) r[c * 4 + k] *= fac
        return r
    }

    fun pivot(own: FloatArray, tar: FloatArray?, d: BlendFile.View): FloatArray {
        val axis = d.int("rotAxis")
        if (axis >= 0) {
            val rot = RigMath.toEul(own, 1)
            if (axis < 3) { if (rot[axis] > 0f) return own } else if (rot[axis - 3] < 0f) return own
        }
        val off = d.floats("offset", 3) ?: floatArrayOf(0f, 0f, 0f)
        val pivot = when {
            tar != null -> RigMath.add(RigMath.loc(tar), off)
            d.int("flag") and 1 == 0 -> RigMath.add(RigMath.loc(own), off)
            else -> off
        }
        val vec = RigMath.dir(RigMath.rot3(own), RigMath.sub(RigMath.loc(own), pivot))
        return RigMath.withLoc(own, RigMath.add(pivot, vec))
    }

    fun transform(own: FloatArray, tar: FloatArray, d: BlendFile.View, ownOrder: Int): FloatArray {
        val from = d.int("from")
        val to = d.int("to")
        val modern = d.has("from_min_rot")
        fun arr(n: String, legacy: String = n) = d.floats(if (modern) n else legacy, 3) ?: FloatArray(3)
        val dvec: FloatArray
        val fmin: FloatArray
        val fmax: FloatArray
        when (from) {
            2 -> {
                dvec = RigMath.size(tar)
                if (RigMath.det3(tar) < 0f) for (i in 0 until 3) dvec[i] = -dvec[i]
                fmin = arr("from_min_scale", "from_min"); fmax = arr("from_max_scale", "from_max")
            }
            1 -> {
                val mode = d.int("from_rotation_mode")
                dvec = RigMath.toEul(tar, if (mode in 1..6) mode else ownOrder)
                fmin = arr("from_min_rot", "from_min"); fmax = arr("from_max_rot", "from_max")
                if (!modern) for (i in 0 until 3) { fmin[i] = Math.toRadians(fmin[i].toDouble()).toFloat(); fmax[i] = Math.toRadians(fmax[i].toDouble()).toFloat() }
            }
            else -> {
                dvec = RigMath.loc(tar)
                fmin = arr("from_min"); fmax = arr("from_max")
            }
        }
        val expo = d.int("expo") != 0
        val sval = FloatArray(3)
        for (i in 0 until 3) {
            if (!expo) dvec[i] = dvec[i].coerceIn(min(fmin[i], fmax[i]), max(fmin[i], fmax[i]))
            sval[i] = if (fmax[i] - fmin[i] != 0f) (dvec[i] - fmin[i]) / (fmax[i] - fmin[i]) else 0f
        }
        val map = IntArray(3) { i -> d.field("map")?.let { fd -> f(d, fd.offset + i) } ?: i }
        val (l, rot, s) = RigMath.locRotSize(own)
        var loc = l
        var r = rot
        var size = s
        when (to) {
            2 -> {
                val tmin = arr("to_min_scale", "to_min")
                val tmax = arr("to_max_scale", "to_max")
                val ns = FloatArray(3) { i -> tmin[i] + sval[map[i].coerceIn(0, 2)] * (tmax[i] - tmin[i]) }
                size = if (d.int("mix_mode_scale") == 1) floatArrayOf(size[0] * ns[0], size[1] * ns[1], size[2] * ns[2]) else ns
            }
            1 -> {
                val tmin = arr("to_min_rot", "to_min")
                val tmax = arr("to_max_rot", "to_max")
                if (!modern) for (i in 0 until 3) { tmin[i] = Math.toRadians(tmin[i].toDouble()).toFloat(); tmax[i] = Math.toRadians(tmax[i].toDouble()).toFloat() }
                val order = d.int("to_euler_order").takeIf { it in 1..6 } ?: ownOrder
                val ne = FloatArray(3) { i -> tmin[i] + sval[map[i].coerceIn(0, 2)] * (tmax[i] - tmin[i]) }
                r = when (d.int("mix_mode_rot")) {
                    1 -> RigMath.eul(ne, order)
                    2 -> RigMath.mul(RigMath.eul(ne, order), r)
                    3 -> RigMath.mul(r, RigMath.eul(ne, order))
                    else -> {
                        val old = RigMath.toEul(r, order)
                        RigMath.eul(floatArrayOf(ne[0] + old[0], ne[1] + old[1], ne[2] + old[2]), order)
                    }
                }
            }
            else -> {
                val tmin = arr("to_min")
                val tmax = arr("to_max")
                val nl = FloatArray(3) { i -> tmin[i] + sval[map[i].coerceIn(0, 2)] * (tmax[i] - tmin[i]) }
                loc = if (d.int("mix_mode_loc") == 1) nl else RigMath.add(loc, nl)
            }
        }
        return RigMath.compose(loc, r, size)
    }

    private fun f(d: BlendFile.View, off: Int): Int = d.byte(off)
}

internal class IkSegment(
    val name: String,
    val parent: Int,
    val fkPose: FloatArray,
    val rootFrame: FloatArray?,
    val boneLength: Float,
    val dof: BooleanArray,
    val weights: FloatArray,
    val limits: FloatArray?,
    val restLocal: FloatArray,
    val stretchWeight: Float
) {
    var q: FloatArray = RigMath.I
    var stretch = 0f
    var local = RigMath.I
    var start = floatArrayOf(0f, 0f, 0f)
    var length = 0f
    var rot = RigMath.I
    var head = floatArrayOf(0f, 0f, 0f)
    var end = floatArrayOf(0f, 0f, 0f)
}

internal class IkTask(val segment: Int, val goal: FloatArray?, val goalRot: FloatArray?, val weight: Float, val rotWeight: Float)

internal class IkSolver(private val segs: List<IkSegment>, private val tasks: List<IkTask>) {

    init {
        for (s in segs) {
            val r = RigMath.rot3(s.fkPose)
            s.length = s.boneLength * RigMath.len(RigMath.col(s.fkPose, 1))
            val (pr, pe) = if (s.parent >= 0) {
                val p = segs[s.parent]
                RigMath.rot3(p.fkPose) to RigMath.add(RigMath.loc(p.fkPose), RigMath.scale(RigMath.col(p.fkPose, 1), p.boneLength))
            } else {
                val rf = s.rootFrame
                if (rf != null) RigMath.rot3(rf) to RigMath.loc(rf) else RigMath.I to floatArrayOf(0f, 0f, 0f)
            }
            val prInv = RigMath.inv(pr)
            s.local = RigMath.mul(prInv, r)
            s.start = RigMath.dir(prInv, RigMath.sub(RigMath.loc(s.fkPose), pe))
        }
    }

    private fun forward() {
        for (s in segs) {
            val (pr, pe) = if (s.parent >= 0) segs[s.parent].rot to segs[s.parent].end else {
                val rf = s.rootFrame
                if (rf != null) RigMath.rot3(rf) to RigMath.loc(rf) else RigMath.I to floatArrayOf(0f, 0f, 0f)
            }
            s.rot = RigMath.mul(pr, s.local, s.q)
            s.head = RigMath.add(pe, RigMath.dir(pr, s.start))
            s.end = RigMath.add(s.head, RigMath.scale(RigMath.col(s.rot, 1), s.length + s.stretch))
        }
    }

    private fun ancestors(i: Int): Set<Int> {
        val out = HashSet<Int>()
        var c = i
        while (c >= 0) {
            out.add(c)
            c = segs[c].parent
        }
        return out
    }

    fun poleAlign(root: Int, tip: Int, goal: FloatArray, pole: FloatArray, poleAngle: Float) {
        forward()
        val rs = segs[root]
        val rp = rs.head
        val ep = segs[tip].end
        val dir = RigMath.norm(RigMath.sub(ep, rp))
        val up = RigMath.add(RigMath.scale(RigMath.col(rs.rot, 0), cos(poleAngle)), RigMath.scale(RigMath.col(rs.rot, 2), sin(poleAngle)))
        val poleDir = RigMath.norm(RigMath.sub(goal, rp))
        val poleUp = RigMath.norm(RigMath.sub(pole, rp))
        fun frame(d: FloatArray, u: FloatArray): FloatArray {
            val r0 = RigMath.norm(RigMath.cross(d, u))
            val r1 = RigMath.cross(r0, d)
            val r2 = RigMath.scale(d, -1f)
            val m = RigMath.I
            for (c in 0 until 3) {
                m[c * 4] = r0[c]; m[c * 4 + 1] = r1[c]; m[c * 4 + 2] = r2[c]
            }
            return m
        }
        val rw = RigMath.mul(RigMath.inv(frame(poleDir, poleUp)), frame(dir, up))
        val pr = RigMath.mul(rs.rot, RigMath.inv(RigMath.mul(rs.local, rs.q)))
        rs.q = RigMath.mul(RigMath.inv(RigMath.mul(pr, rs.local)), rw, pr, rs.local, rs.q)
        rs.q[12] = 0f; rs.q[13] = 0f; rs.q[14] = 0f
    }

    fun solve(iterations: Int) {
        val dofIndex = ArrayList<Pair<Int, Int>>()
        for ((i, s) in segs.withIndex()) {
            for (a in 0 until 3) if (s.dof[a]) dofIndex.add(i to a)
            if (s.stretchWeight > 0f) dofIndex.add(i to 3)
        }
        val n = dofIndex.size
        if (n == 0) return
        val rows = tasks.sumOf { (if (it.goal != null) 3 else 0) + (if (it.goalRot != null) 3 else 0) }
        if (rows == 0) return
        val anc = tasks.map { ancestors(it.segment) }
        val clamp = FloatArray(tasks.size) { t ->
            val a = anc[t]
            a.sumOf { (segs[it].length + if (segs[it].stretchWeight > 0f) segs[it].length else 0f).toDouble() }.toFloat() / (2f * a.size)
        }
        val weight = FloatArray(n) { k ->
            val (si, a) = dofIndex[k]
            if (a == 3) segs[si].stretchWeight else segs[si].weights[a]
        }
        val wSqrt = FloatArray(n) { sqrt(max(weight[it], 0f)) }
        val jac = Array(rows) { DoubleArray(n) }
        val beta = DoubleArray(rows)
        var it = 0
        while (it < iterations) {
            forward()
            for (r in jac) r.fill(0.0)
            var row = 0
            for ((ti, task) in tasks.withIndex()) {
                val eff = segs[task.segment]
                if (task.goal != null) {
                    val pos = eff.end
                    var d = RigMath.sub(task.goal, pos)
                    val l = RigMath.len(d)
                    if (l > clamp[ti] && clamp[ti] > 0f) d = RigMath.scale(d, clamp[ti] / l)
                    for (k in 0 until 3) beta[row + k] = (task.weight * d[k]).toDouble()
                    for (c in 0 until n) {
                        val (si, a) = dofIndex[c]
                        if (si !in anc[ti]) continue
                        val sg = segs[si]
                        val v = if (a == 3) RigMath.col(sg.rot, 1) else RigMath.cross(RigMath.col(sg.rot, a), RigMath.sub(pos, sg.head))
                        for (k in 0 until 3) jac[row + k][c] = (v[k] * task.weight * wSqrt[c]).toDouble()
                    }
                    row += 3
                }
                if (task.goalRot != null) {
                    val dm = RigMath.mul(RigMath.rot3(task.goalRot), RigMath.inv(eff.rot))
                    fun e(c: Int, rr: Int) = dm[c * 4 + rr]
                    val dr = floatArrayOf(0.5f * (e(1, 2) - e(2, 1)), 0.5f * (e(2, 0) - e(0, 2)), 0.5f * (e(0, 1) - e(1, 0)))
                    for (k in 0 until 3) beta[row + k] = (task.rotWeight * dr[k]).toDouble()
                    for (c in 0 until n) {
                        val (si, a) = dofIndex[c]
                        if (si !in anc[ti] || a == 3) continue
                        val axis = RigMath.col(segs[si].rot, a)
                        for (k in 0 until 3) jac[row + k][c] = (axis[k] * task.rotWeight * wSqrt[c]).toDouble()
                    }
                    row += 3
                }
            }
            val dTheta = sdls(jac, beta, n, wSqrt)
            var maxD = 0.0
            for (c in 0 until n) {
                dTheta[c] *= wSqrt[c].toDouble()
                maxD = max(maxD, abs(dTheta[c]))
            }
            val perSeg = HashMap<Int, FloatArray>()
            for (c in 0 until n) {
                val (si, a) = dofIndex[c]
                if (a == 3) segs[si].stretch = max(segs[si].stretch + dTheta[c].toFloat(), -segs[si].length * 0.999f)
                else perSeg.getOrPut(si) { FloatArray(3) }[a] = dTheta[c].toFloat()
            }
            for ((si, v) in perSeg) {
                val ang = RigMath.len(v)
                if (ang > 0f) {
                    val s = segs[si]
                    s.q = RigMath.mul(s.q, RigMath.axisAngle(RigMath.scale(v, 1f / ang), ang))
                    applyLimits(s)
                }
            }
            it++
            if (maxD < 1e-3 && it > 10) break
        }
        forward()
    }

    private fun applyLimits(s: IkSegment) {
        val lim = s.limits ?: return
        val r = RigMath.rot3(RigMath.mul(s.restLocal, s.q))
        val q = RigMath.quat(r)
        var tw = 2f * kotlin.math.atan2(q[2], q[0])
        val twistQ = floatArrayOf(cos(tw / 2), 0f, sin(tw / 2), 0f)
        val sw = quatMul(q, floatArrayOf(twistQ[0], -twistQ[1], -twistQ[2], -twistQ[3]))
        val sl = sqrt(sw[1] * sw[1] + sw[3] * sw[3])
        val sAng = 2f * kotlin.math.atan2(sl, sw[0])
        var ax = if (sl > 0f) sw[1] / sl * sAng else 0f
        var az = if (sl > 0f) sw[3] / sl * sAng else 0f
        var changed = false
        if (!lim[0].isNaN()) { val v = ax.coerceIn(lim[0], lim[1]); if (v != ax) { ax = v; changed = true } }
        if (!lim[2].isNaN()) { val v = tw.coerceIn(lim[2], lim[3]); if (v != tw) { tw = v; changed = true } }
        if (!lim[4].isNaN()) { val v = az.coerceIn(lim[4], lim[5]); if (v != az) { az = v; changed = true } }
        if (!changed) return
        val sa = sqrt(ax * ax + az * az)
        val swing = if (sa > 0f) RigMath.axisAngle(floatArrayOf(ax / sa, 0f, az / sa), sa) else RigMath.I
        val twist = RigMath.axisAngle(floatArrayOf(0f, 1f, 0f), tw)
        val q2 = RigMath.mul(RigMath.inv(RigMath.rot3(s.restLocal)), RigMath.mul(swing, twist))
        q2[12] = 0f; q2[13] = 0f; q2[14] = 0f
        s.q = q2
    }

    private fun quatMul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    )

    private fun factor(i: Int): Float {
        val s = segs[i]
        return if (s.length > 0f) (s.length + s.stretch) / s.length else 1f
    }

    fun basisChange(i: Int): FloatArray {
        val s = segs[i]
        val q = s.q.copyOf()
        val f = factor(i) / (if (s.parent >= 0) factor(s.parent) else 1f)
        if (f != 1f) for (k in 0 until 11) if (k % 4 != 3) q[k] *= f
        return q
    }
    private fun sdls(j: Array<DoubleArray>, beta: DoubleArray, n: Int, wSqrt: FloatArray): DoubleArray {
        val r = j.size
        val u = Array(r) { j[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n - 1) for (q in p + 1 until n) {
                var alpha = 0.0
                var bt = 0.0
                var gamma = 0.0
                for (i in 0 until r) {
                    alpha += u[i][p] * u[i][p]
                    bt += u[i][q] * u[i][q]
                    gamma += u[i][p] * u[i][q]
                }
                if (abs(gamma) <= 1e-15 * sqrt(alpha * bt) || gamma == 0.0) continue
                off = max(off, abs(gamma) / sqrt(alpha * bt))
                val zeta = (bt - alpha) / (2 * gamma)
                val t = (if (zeta >= 0) 1.0 else -1.0) / (abs(zeta) + sqrt(1 + zeta * zeta))
                val c = 1 / sqrt(1 + t * t)
                val s = c * t
                for (i in 0 until r) {
                    val up = u[i][p]
                    val uq = u[i][q]
                    u[i][p] = c * up - s * uq
                    u[i][q] = s * up + c * uq
                }
                for (i in 0 until n) {
                    val vp = v[i][p]
                    val vq = v[i][q]
                    v[i][p] = c * vp - s * vq
                    v[i][q] = s * vp + c * vq
                }
            }
            if (off < 1e-12) break
        }
        val w = DoubleArray(n)
        for (k in 0 until n) {
            var s = 0.0
            for (i in 0 until r) s += u[i][k] * u[i][k]
            w[k] = sqrt(s)
            if (w[k] > 0) for (i in 0 until r) u[i][k] /= w[k]
        }
        val norm = DoubleArray(n)
        for (c in 0 until n) {
            var row = 0
            while (row + 2 < r) {
                norm[c] += sqrt(j[row][c] * j[row][c] + j[row + 1][c] * j[row + 1][c] + j[row + 2][c] * j[row + 2][c])
                row += 3
            }
        }
        val maxAngle = PI / 4
        val dTheta = DoubleArray(n)
        val tmp = DoubleArray(n)
        for (i in 0 until n) {
            if (w[i] <= 1e-10) continue
            val wInv = 1.0 / w[i]
            var alpha = 0.0
            var nn = 0.0
            var row = 0
            while (row + 2 < r) {
                alpha += u[row][i] * beta[row] + u[row + 1][i] * beta[row + 1] + u[row + 2][i] * beta[row + 2]
                nn += sqrt(u[row][i] * u[row][i] + u[row + 1][i] * u[row + 1][i] + u[row + 2][i] * u[row + 2][i])
                row += 3
            }
            alpha *= wInv
            var m = 0.0
            var maxDt = 0.0
            for (k in 0 until n) {
                val vv = v[k][i]
                m += abs(vv) * norm[k]
                tmp[k] = vv * alpha
                maxDt = max(maxDt, abs(tmp[k]) * wSqrt[k])
            }
            m *= wInv
            var gamma = maxAngle
            if (nn < m) gamma *= nn / m
            val damp = if (gamma < maxDt) gamma / maxDt else 1.0
            for (k in 0 until n) dTheta[k] += 0.8 * damp * tmp[k]
        }
        var maxA = 0.0
        for (k in 0 until n) maxA = max(maxA, abs(dTheta[k]))
        if (maxA > maxAngle) {
            val damp = maxAngle / (maxAngle + maxA)
            for (k in 0 until n) dTheta[k] *= damp
        }
        return dTheta
    }



}

internal class BBoneParams {
    var segments = 1
    var length = 0f
    var doScale = false
    var scale = floatArrayOf(1f, 1f, 1f)
    var usePrev = false
    var useNext = false
    var prevBBone = false
    var nextBBone = false
    var prevH = floatArrayOf(0f, 0f, 0f)
    var nextH = floatArrayOf(0f, 0f, 0f)
    var prevMat = RigMath.I
    var nextMat = RigMath.I
    var roll1 = 0f
    var roll2 = 0f
    var ease1 = 0f
    var ease2 = 0f
    var curveInX = 0f
    var curveInZ = 0f
    var curveOutX = 0f
    var curveOutZ = 0f
    var scaleIn = floatArrayOf(1f, 1f, 1f)
    var scaleOut = floatArrayOf(1f, 1f, 1f)
}

internal object BBoneMath {
    fun vecRollToMat3(v: FloatArray, roll: Float): FloatArray {
        val nor = RigMath.norm(v)
        val x = nor[0]
        val y = nor[1]
        val z = nor[2]
        var theta = 1f + y
        val thetaAlt = x * x + z * z
        val b = RigMath.I
        if (theta > 6.1e-3f || thetaAlt > 2.5e-4f * 2.5e-4f) {
            if (theta <= 6.1e-3f) theta = thetaAlt * 0.5f + thetaAlt * thetaAlt * 0.125f
            b[1] = -x; b[4] = x; b[5] = y; b[6] = z; b[9] = -z
            b[0] = 1 - x * x / theta
            b[10] = 1 - z * z / theta
            b[2] = -x * z / theta
            b[8] = -x * z / theta
        } else {
            b[0] = -1f
            b[5] = -1f
        }
        return RigMath.mul(RigMath.axisAngle(nor, roll), b)
    }

    private fun twist(q: FloatArray): Float {
        val t = kotlin.math.atan2(q[2], q[0]) * 2f
        return if (t > PI.toFloat()) t - 2 * PI.toFloat() else if (t < -PI.toFloat()) t + 2 * PI.toFloat() else t
    }

    fun mat3VecToRoll(m: FloatArray, v: FloatArray): Float {
        val vm = vecRollToMat3(v, 0f)
        val rm = RigMath.mul(RigMath.inv(vm), RigMath.rot3(m))
        return twist(RigMath.quat(rm))
    }

    private fun circleFactor(a: FloatArray, b: FloatArray): Float {
        val d = RigMath.dot(a, b)
        if (d > 1f - 1e-7f) return (1f / 3f) * 0.75f
        if (d < -1f + 1e-7f) return 0.5f
        val angle = acos(d.coerceIn(-1f, 1f)) / 2f
        val s = sin(angle)
        val c = cos(angle)
        return ((1f - c) / (s * 2f)) / s
    }

    private fun handles(p: BBoneParams): Triple<FloatArray, FloatArray, FloatArray> {
        var length = p.length
        if (p.doScale) length *= p.scale[1]
        val eps = 1e-5f * length
        var roll1 = 0f
        var roll2 = 0f
        var h1: FloatArray
        var h2: FloatArray
        if (p.usePrev) {
            h1 = p.prevH.copyOf()
            if (p.prevBBone) h1[1] -= length
            h1 = if (RigMath.len(h1) < eps) floatArrayOf(0f, -1f, 0f) else RigMath.norm(h1)
            h1 = RigMath.scale(h1, -1f)
            if (!p.prevBBone) roll1 = mat3VecToRoll(p.prevMat, h1)
        } else h1 = floatArrayOf(0f, 1f, 0f)
        if (p.useNext) {
            h2 = p.nextH.copyOf()
            if (!p.nextBBone) h2[1] -= length
            h2 = if (RigMath.len(h2) < eps) floatArrayOf(0f, 1f, 0f) else RigMath.norm(h2)
            roll2 = mat3VecToRoll(p.nextMat, h2)
        } else h2 = floatArrayOf(0f, 1f, 0f)
        val cf = length * (circleFactor(h1, h2) / 0.75f)
        h1 = RigMath.scale(h1, p.ease1 * cf)
        h2 = RigMath.scale(h2, -p.ease2 * cf)
        roll1 += p.roll1
        roll2 += p.roll2
        val xs = if (p.doScale) p.scale[0] else 1f
        val zs = if (p.doScale) p.scale[2] else 1f
        h1[0] += p.curveInX * xs
        h1[2] += p.curveInZ * zs
        h2[0] += p.curveOutX * xs
        h2[2] += p.curveOutZ * zs
        return Triple(h1, h2, floatArrayOf(roll1, roll2))
    }

    private fun bezier(c: Array<FloatArray>, t: Float): Pair<FloatArray, FloatArray> {
        val l1 = Array(3) { RigMath.lerp(c[it], c[it + 1], t) }
        val l2a = RigMath.lerp(l1[0], l1[1], t)
        val l2b = RigMath.lerp(l1[1], l1[2], t)
        val tan = RigMath.sub(l2b, l2a)
        return RigMath.add(l2a, RigMath.scale(tan, t)) to tan
    }

    private fun easeAxis(d1: FloatArray, d2: FloatArray): FloatArray {
        val gap = 1.1920929E-7f
        val l1 = RigMath.dot(d1, d1)
        val l2 = RigMath.dot(d2, d2)
        val ratio = if (l2 > 0f) l1 / l2 else Float.MAX_VALUE
        return if (ratio < gap * gap) RigMath.add(d1, RigMath.scale(d2, gap - ratio)) else d1.copyOf()
    }

    private fun splineMatrix(p: BBoneParams, pos: FloatArray, axis: FloatArray, roll: Float, sx: Float, sz: Float): FloatArray {
        var m = vecRollToMat3(axis, roll)
        m[12] = pos[0]; m[13] = pos[1]; m[14] = pos[2]
        if (p.doScale) {
            val s1 = GltfParser.trs(null, null, p.scale)
            m = RigMath.mul(RigMath.inv(s1), m, s1)
        }
        for (k in 0 until 3) {
            m[k] *= sx
            m[8 + k] *= sz
        }
        return m
    }

    fun compute(p: BBoneParams): Array<FloatArray> {
        var length = p.length
        if (p.doScale) length *= p.scale[1]
        val (h1, h2, rolls) = handles(p)
        val segs = p.segments.coerceIn(1, 32)
        val c = arrayOf(floatArrayOf(0f, 0f, 0f), h1, RigMath.add(floatArrayOf(0f, length, 0f), h2), floatArrayOf(0f, length, 0f))
        val sIn = max(p.scaleIn[1], 0.0001f)
        val sOut = max(p.scaleOut[1], 0.0001f)
        val lin = kotlin.math.ln(sIn)
        val lout = kotlin.math.ln(sOut)
        val segScales = FloatArray(segs) { i ->
            val fac = if (segs > 1) i.toFloat() / (segs - 1) else 0f
            exp(lin + (lout - lin) * fac)
        }
        val temp = 32
        val coords = Array(temp + 1) { bezier(c, it.toFloat() / temp).first }
        val pdist = FloatArray(temp + 1)
        for (i in 0 until temp) pdist[i + 1] = pdist[i] + RigMath.len(RigMath.sub(coords[i + 1], coords[i]))
        val tPts = FloatArray(segs + 1)
        var dist = 0f
        val stepTotal = pdist[temp] / segScales.sum()
        var nr = 1
        for (i in 1..segs) {
            dist += segScales[i - 1] * stepTotal
            while (nr < temp && dist >= pdist[nr]) nr++
            val den = pdist[nr] - pdist[nr - 1]
            val fac = if (den != 0f) (pdist[nr] - dist) / den else 0f
            tPts[i] = (nr - fac) / temp
        }
        tPts[segs] = 1f
        val d1 = Array(3) { RigMath.sub(c[it + 1], c[it]) }
        val d2 = Array(2) { RigMath.sub(d1[it + 1], d1[it]) }
        val out = Array(segs + 1) { RigMath.I }
        out[0] = splineMatrix(p, c[0], easeAxis(d1[0], d2[0]), rolls[0], p.scaleIn[0], p.scaleIn[2])
        for (a in 1 until segs) {
            val (pos, tan) = bezier(c, tPts[a])
            val fac = a.toFloat() / segs
            val roll = rolls[0] + (rolls[1] - rolls[0]) * fac
            val sx = p.scaleIn[0] + (p.scaleOut[0] - p.scaleIn[0]) * fac
            val sz = p.scaleIn[2] + (p.scaleOut[2] - p.scaleIn[2]) * fac
            out[a] = splineMatrix(p, pos, tan, roll, sx, sz)
        }
        out[segs] = splineMatrix(p, c[3], easeAxis(d1[2], RigMath.scale(d2[1], -1f)), rolls[1], p.scaleOut[0], p.scaleOut[2])
        return out
    }
}

internal class BBoneCurvedMap(armMat: FloatArray, rest: List<FloatArray>) {
    private val segments = rest.size - 1
    private val point = Array(rest.size) { i -> RigMath.point(armMat, floatArrayOf(rest[i][12], rest[i][13], rest[i][14])) }
    private val normal = Array(rest.size) { i -> RigMath.norm(RigMath.dir(armMat, RigMath.col(rest[i], 1))) }
    private val offset = FloatArray(rest.size) { RigMath.dot(point[it], normal[it]) }
    private val arcReciprocal: Float
    private val depthScale = FloatArray(rest.size)

    init {
        var arc = 0f
        for (i in 0 until segments) arc += RigMath.len(RigMath.sub(point[i + 1], point[i]))
        arcReciprocal = 1f / arc
        val depth = kotlin.math.ceil(kotlin.math.log2((segments + 2).toFloat())).toInt()
        val straight = RigMath.len(RigMath.sub(point[0], point[segments]))
        val maxDepthScale = segments * (straight / arc) * 0.222f
        val scaleFactor = maxOf(maxDepthScale, 1f).pow(1f / (depth - 1))
        depthScale[0] = segments / maxDepthScale
        for (i in 1 until minOf(depth, depthScale.size)) depthScale[i] = depthScale[i - 1] * scaleFactor
    }

    fun headTail(co: FloatArray): Float {
        val dist = FloatArray(segments + 1)
        val stack = IntArray(segments + 2)
        var top = -1
        var start = -1
        var end = segments + 1
        var bias = 0
        while (end - start > 1) {
            val mid = (start + end + bias) / 2
            val d = RigMath.dot(co, normal[mid]) - offset[mid]
            stack[++top] = mid
            dist[mid] = d
            if (d < 0) { end = mid; bias = 1 } else { start = mid; bias = 0 }
        }
        val size = 1f / segments
        var ht = when {
            end <= 0 -> 0f
            start >= segments -> 1f
            else -> {
                val d1 = kotlin.math.abs(dist[start])
                val d2 = kotlin.math.abs(dist[end])
                size * (start + d1 / (d1 + d2))
            }
        }
        for (i in top downTo 0) {
            val bi = stack[i]
            val bpos = bi * size
            val pd = dist[bi] * arcReciprocal
            val pda = kotlin.math.abs(pd)
            val gap = kotlin.math.abs(ht - bpos) - pda
            if (gap <= 0) continue
            val dc = 1f - pda * depthScale.getOrElse(i) { 0f }
            if (dc <= 0) continue
            val sg = gap * 3f
            val slope = sg / (sg + pda)
            val dcs = dc * dc * 8f / (7f * dc + 1f)
            val target = bpos + pd
            val t = slope * dcs
            ht = ht + (target - ht) * t
        }
        return ht
    }
}
