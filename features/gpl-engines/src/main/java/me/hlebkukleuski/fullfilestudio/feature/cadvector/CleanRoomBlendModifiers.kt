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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal class MeshTopology(val corners: IntArray, val faceStart: IntArray, val faceSize: IntArray, val edges: IntArray) {
    val faceCount: Int get() = faceStart.size
    val edgeCount: Int get() = edges.size / 2

    fun vertexNormals(co: FloatArray): FloatArray {
        val n = co.size / 3
        val acc = FloatArray(n * 3)
        val used = BooleanArray(n)
        val fn = FloatArray(3)
        for (f in 0 until faceCount) {
            val s = faceStart[f]
            val m = faceSize[f]
            if (m < 3 || s < 0 || s + m > corners.size) continue
            if ((0 until m).any { corners[s + it] !in 0 until n }) continue
            faceNormal(co, s, m, fn)
            for (k in 0 until m) {
                val prev = corners[s + (k + m - 1) % m]
                val cur = corners[s + k]
                val next = corners[s + (k + 1) % m]
                used[cur] = true
                val ax = co[prev * 3] - co[cur * 3]; val ay = co[prev * 3 + 1] - co[cur * 3 + 1]; val az = co[prev * 3 + 2] - co[cur * 3 + 2]
                val bx = co[next * 3] - co[cur * 3]; val by = co[next * 3 + 1] - co[cur * 3 + 1]; val bz = co[next * 3 + 2] - co[cur * 3 + 2]
                val da = divNormalize(ax, ay, az)
                val db = divNormalize(bx, by, bz)
                val ang = acosApprox(da[0] * db[0] + da[1] * db[1] + da[2] * db[2])
                acc[cur * 3] += fn[0] * ang; acc[cur * 3 + 1] += fn[1] * ang; acc[cur * 3 + 2] += fn[2] * ang
            }
        }
        for (i in 0 until n) {
            val r = if (used[i]) divNormalize(acc[i * 3], acc[i * 3 + 1], acc[i * 3 + 2]) else divNormalize(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            acc[i * 3] = r[0]; acc[i * 3 + 1] = r[1]; acc[i * 3 + 2] = r[2]
        }
        return acc
    }

    fun faceNormal(co: FloatArray, s: Int, m: Int, out: FloatArray) {
        fun c(k: Int, d: Int) = co[corners[s + k] * 3 + d]
        var x = 0f
        var y = 0f
        var z = 0f
        var p = m - 1
        for (k in 0 until m) {
            x += (c(p, 1) - c(k, 1)) * (c(p, 2) + c(k, 2))
            y += (c(p, 2) - c(k, 2)) * (c(p, 0) + c(k, 0))
            z += (c(p, 0) - c(k, 0)) * (c(p, 1) + c(k, 1))
            p = k
        }
        val d = x * x + y * y + z * z
        if (d > 1.0e-35f) { val inv = 1.0f / sqrt(d); x *= inv; y *= inv; z *= inv } else { x = 0f; y = 0f; z = 1f }
        out[0] = x; out[1] = y; out[2] = z
    }

    private fun divNormalize(x: Float, y: Float, z: Float): FloatArray {
        val d = x * x + y * y + z * z
        if (d > 1.0e-35f) { val l = sqrt(d); return floatArrayOf(x / l, y / l, z / l) }
        return floatArrayOf(0f, 0f, 0f)
    }

    private fun acosApprox(x: Float): Float {
        val f = kotlin.math.abs(x)
        val m = if (f < 1.0f) 1.0f - (1.0f - f) else 1.0f
        val a = sqrt(1.0f - m) * (1.5707963267f + m * (-0.213300989f + m * (0.077980478f + m * -0.02164095f)))
        return if (x < 0.0f) Math.PI.toFloat() - a else a
    }

    fun cornerTris(co: FloatArray): Pair<IntArray, IntArray> {
        val tc = ArrayList<Int>()
        val tf = ArrayList<Int>()
        for (f in 0 until faceCount) {
            val s = faceStart[f]
            val m = faceSize[f]
            if (m < 3 || s < 0 || s + m > corners.size) continue
            if (m == 4 && quadFlip(co, corners[s], corners[s + 1], corners[s + 2], corners[s + 3])) {
                tc.add(s); tc.add(s + 1); tc.add(s + 3); tf.add(f)
                tc.add(s + 1); tc.add(s + 2); tc.add(s + 3); tf.add(f)
            } else if (m > 4) {
                val vs = IntArray(m) { corners[s + it] }
                if (vs.any { it < 0 || it * 3 + 2 >= co.size }) {
                    for (k in 1 until m - 1) { tc.add(s); tc.add(s + k); tc.add(s + k + 1); tf.add(f) }
                } else {
                    val t = BlendPolyfill.triangulate(co, vs)
                    for (k in t.indices step 3) { tc.add(s + t[k]); tc.add(s + t[k + 1]); tc.add(s + t[k + 2]); tf.add(f) }
                }
            } else for (k in 1 until m - 1) { tc.add(s); tc.add(s + k); tc.add(s + k + 1); tf.add(f) }
        }
        return tc.toIntArray() to tf.toIntArray()
    }

    companion object {
        fun quadFlip(co: FloatArray, a: Int, b: Int, c: Int, d: Int): Boolean {
            val n = co.size / 3
            if (a !in 0 until n || b !in 0 until n || c !in 0 until n || d !in 0 until n) return false
            val d12 = FloatArray(3) { co[b * 3 + it] - co[a * 3 + it] }
            val d13 = FloatArray(3) { co[c * 3 + it] - co[a * 3 + it] }
            val d14 = FloatArray(3) { co[d * 3 + it] - co[a * 3 + it] }
            val ca = RigMath.cross(d12, d13)
            val cb = RigMath.cross(d14, d13)
            return RigMath.dot(ca, cb) > 0f
        }

        fun edgesFromFaces(corners: IntArray, faceStart: IntArray, faceSize: IntArray): IntArray {
            val seen = HashSet<Long>()
            val out = ArrayList<Int>()
            for (f in faceStart.indices) {
                val s = faceStart[f]
                val m = faceSize[f]
                for (k in 0 until m) {
                    if (s + k >= corners.size) break
                    val a = corners[s + k]
                    val b = corners[s + (k + 1) % m]
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                    if (seen.add(key)) { out.add(minOf(a, b)); out.add(maxOf(a, b)) }
                }
            }
            return out.toIntArray()
        }
    }
}

internal object BlendModifiers {

    fun cast(co: FloatArray, weights: FloatArray?, type: Int, fac0: Float, radius: Float, size: Float, flag0: Int, ctrl: Boolean, mat: FloatArray?, imat: FloatArray?, center: FloatArray) {
        var flag = flag0
        if (type == 1) flag = flag and (1 shl 3).inv()
        if (type == 2) {
            cuboid(co, weights, fac0, radius, size, flag, ctrl, mat, imat, center)
            return
        }
        val n = co.size / 3
        val useOb = ctrl && flag and (1 shl 4) != 0 && mat != null && imat != null
        val hasRadius = radius > 1.1920929E-7f
        var len = if (flag and (1 shl 5) != 0) radius else size
        if (len <= 0f) {
            len = 0f
            for (i in 0 until n) {
                val dx = center[0] - co[i * 3]; val dy = center[1] - co[i * 3 + 1]; val dz = center[2] - co[i * 3 + 2]
                len += sqrt(dx * dx + dy * dy + dz * dz)
            }
            len /= n
            if (len == 0f) len = 10f
        }
        for (i in 0 until n) {
            var t = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            if (ctrl) t = if (useOb) RigMath.point(mat!!, t) else RigMath.sub(t, center)
            val vec = t.copyOf()
            if (type == 1) vec[2] = 0f
            if (hasRadius && RigMath.len(vec) > radius) continue
            var fac = fac0
            if (weights != null) {
                val w = weights[i]
                if (w == 0f) continue
                fac = fac0 * w
            }
            val facm = 1f - fac
            val nv = RigMath.norm(vec)
            if (flag and (1 shl 1) != 0) t[0] = fac * nv[0] * len + facm * t[0]
            if (flag and (1 shl 2) != 0) t[1] = fac * nv[1] * len + facm * t[1]
            if (flag and (1 shl 3) != 0) t[2] = fac * nv[2] * len + facm * t[2]
            if (ctrl) t = if (useOb) RigMath.point(imat!!, t) else RigMath.add(t, center)
            co[i * 3] = t[0]; co[i * 3 + 1] = t[1]; co[i * 3 + 2] = t[2]
        }
    }

    private fun cuboid(co: FloatArray, weights: FloatArray?, fac0: Float, radius: Float, size: Float, flag: Int, ctrl: Boolean, mat: FloatArray?, imat: FloatArray?, center: FloatArray) {
        val n = co.size / 3
        val useOb = ctrl && flag and (1 shl 4) != 0 && mat != null && imat != null
        val hasRadius = radius > 1.1920929E-7f
        val mn = FloatArray(3)
        val mx = FloatArray(3)
        if (flag and (1 shl 5) != 0 && hasRadius) {
            for (k in 0 until 3) { mn[k] = -radius; mx[k] = radius }
        } else if (flag and (1 shl 5) == 0 && size > 0f) {
            for (k in 0 until 3) { mn[k] = -size; mx[k] = size }
        } else {
            mn.fill(Float.MAX_VALUE); mx.fill(-Float.MAX_VALUE)
            if (ctrl) for (k in 0 until 3) { mn[k] = minOf(mn[k], center[k]); mx[k] = maxOf(mx[k], center[k]) }
            for (i in 0 until n) for (k in 0 until 3) {
                val v = if (ctrl) co[i * 3 + k] - center[k] else co[i * 3 + k]
                mn[k] = minOf(mn[k], v); mx[k] = maxOf(mx[k], v)
            }
            for (k in 0 until 3) {
                if (abs(mn[k]) > abs(mx[k])) mx[k] = abs(mn[k])
                mn[k] = -mx[k]
            }
        }
        val bb = Array(8) { o -> floatArrayOf(if (o and 1 != 0) mx[0] else mn[0], if (o and 2 != 0) mx[1] else mn[1], if (o and 4 != 0) mx[2] else mn[2]) }
        for (i in 0 until n) {
            var t = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            if (ctrl) t = if (useOb) RigMath.point(mat!!, t) else RigMath.sub(t, center)
            if (hasRadius && (abs(t[0]) > radius || abs(t[1]) > radius || abs(t[2]) > radius)) continue
            var fac = fac0
            if (weights != null) {
                val w = weights[i]
                if (w == 0f) continue
                fac = fac0 * w
            }
            val facm = 1f - fac
            var octant = 0
            if (t[0] > 0f) octant += 1
            if (t[1] > 0f) octant += 2
            if (t[2] > 0f) octant += 4
            val apex = bb[octant]
            val d = floatArrayOf(t[0] / apex[0], t[1] / apex[1], t[2] / apex[2])
            var dmax = d[0]
            var coord = 0
            if (d[1] > dmax) { dmax = d[1]; coord = 1 }
            if (d[2] > dmax) coord = 2
            if (abs(t[coord]) < 1.1920929E-7f) continue
            val fbb = apex[coord] / t[coord]
            if (flag and (1 shl 1) != 0) t[0] = facm * t[0] + fac * t[0] * fbb
            if (flag and (1 shl 2) != 0) t[1] = facm * t[1] + fac * t[1] * fbb
            if (flag and (1 shl 3) != 0) t[2] = facm * t[2] + fac * t[2] * fbb
            if (ctrl) t = if (useOb) RigMath.point(imat!!, t) else RigMath.add(t, center)
            co[i * 3] = t[0]; co[i * 3 + 1] = t[1]; co[i * 3 + 2] = t[2]
        }
    }

    private val axisMap = arrayOf(intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(0, 1, 2))

    fun simpleDeform(co: FloatArray, weights: FloatArray?, mode: Int, factor: Float, limit0: Float, limit1: Float, lock0: Int, deformAxis: Int, toOrigin: FloatArray?, fromOrigin: FloatArray?) {
        val n = co.size / 3
        val axis = deformAxis.coerceIn(0, 2)
        val lock = if (mode == 2) 0 else lock0 and (1 shl axis).inv()
        val l0 = limit0.coerceIn(0f, 1f).let { minOf(it, limit1) }
        val l1 = limit1
        val limitAxis = if (mode == 2) (if (axis == 0 || axis == 1) 2 else 0) else axis
        var lower = Float.MAX_VALUE
        var upper = -Float.MAX_VALUE
        for (i in 0 until n) {
            val p = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            val q = if (toOrigin != null) RigMath.point(toOrigin, p) else p
            lower = minOf(lower, q[limitAxis])
            upper = maxOf(upper, q[limitAxis])
        }
        if (n == 0) return
        val lim1 = lower + (upper - lower) * l1
        val lim0 = lower + (upper - lower) * l0
        val f = factor / maxOf(1.1920929E-7f, lim1 - lim0)
        if (mode == 2 && abs(f) < 1e-6f) return
        val map = axisMap[if (mode != 2) axis else 2]
        for (i in 0 until n) {
            val w = weights?.get(i) ?: 1f
            if (w == 0f) continue
            var v = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            if (toOrigin != null) v = RigMath.point(toOrigin, v)
            val c = v.copyOf()
            val dcut = FloatArray(3)
            fun limit(axis: Int, a: Float, b: Float) {
                var x = c[axis]
                if (a > x) x = a
                if (b < x) x = b
                dcut[axis] = c[axis] - x
                c[axis] = x
            }
            for (k in 0 until 3) if (lock and (1 shl k) != 0) limit(k, 0f, 0f)
            limit(limitAxis, lim0, lim1)
            val r = floatArrayOf(c[map[0]], c[map[1]], c[map[2]])
            val dc = floatArrayOf(dcut[map[0]], dcut[map[1]], dcut[map[2]])
            val x = r[0]; val y = r[1]; val z = r[2]
            when (mode) {
                1 -> {
                    val th = z * f
                    val s = sin(th); val cs = cos(th)
                    r[0] = x * cs - y * s; r[1] = x * s + y * cs; r[2] = z
                    for (k in 0 until 3) r[k] += dc[k]
                }
                2 -> {
                    val th = if (axis == 0 || axis == 1) z * f else x * f
                    val s = sin(th); val cs = cos(th)
                    when (axis) {
                        0 -> {
                            r[0] = x; r[1] = y * cs + (1f - cs) / f; r[2] = -(y - 1f / f) * s
                            r[0] += dc[0]; r[1] += s * dc[2]; r[2] += cs * dc[2]
                        }
                        1 -> {
                            r[0] = x * cs + (1f - cs) / f; r[1] = y; r[2] = -(x - 1f / f) * s
                            r[0] += s * dc[2]; r[1] += dc[1]; r[2] += cs * dc[2]
                        }
                        else -> {
                            r[0] = -(y - 1f / f) * s; r[1] = y * cs + (1f - cs) / f; r[2] = z
                            r[0] += cs * dc[0]; r[1] += s * dc[0]; r[2] += dc[2]
                        }
                    }
                }
                3 -> {
                    val sc = z * f
                    r[0] = x + x * sc; r[1] = y + y * sc; r[2] = z
                    for (k in 0 until 3) r[k] += dc[k]
                }
                4 -> {
                    val sc = z * z * f - f + 1f
                    r[0] = x * sc; r[1] = y * sc; r[2] = z * (1f + f)
                    for (k in 0 until 3) r[k] += dc[k]
                }
                else -> return
            }
            val out = FloatArray(3)
            out[map[0]] = r[0]; out[map[1]] = r[1]; out[map[2]] = r[2]
            var res = floatArrayOf(v[0] + (out[0] - v[0]) * w, v[1] + (out[1] - v[1]) * w, v[2] + (out[2] - v[2]) * w)
            if (fromOrigin != null) res = RigMath.point(fromOrigin, res)
            co[i * 3] = res[0]; co[i * 3 + 1] = res[1]; co[i * 3 + 2] = res[2]
        }
    }

    fun smooth(co: FloatArray, topo: MeshTopology, weights: FloatArray?, fac: Float, repeat: Int, flag: Int) {
        val n = co.size / 3
        val acc = FloatArray(n * 3)
        val cnt = IntArray(n)
        for (it in 0 until repeat.coerceIn(0, 10_000)) {
            acc.fill(0f)
            cnt.fill(0)
            for (e in 0 until topo.edgeCount) {
                val a = topo.edges[e * 2]
                val b = topo.edges[e * 2 + 1]
                if (a !in 0 until n || b !in 0 until n) continue
                for (k in 0 until 3) {
                    val mid = (co[a * 3 + k] + co[b * 3 + k]) * 0.5f
                    acc[a * 3 + k] += mid
                    acc[b * 3 + k] += mid
                }
                cnt[a]++
                cnt[b]++
            }
            for (i in 0 until n) {
                if (cnt[i] > 0) {
                    val inv = 1f / cnt[i].toFloat()
                    for (k in 0 until 3) acc[i * 3 + k] *= inv
                }
                if (weights != null && weights[i] <= 0f) continue
                val fn = if (weights != null) weights[i] * fac else fac
                val fo = 1f - fn
                for (k in 0 until 3) if (flag and (1 shl (k + 1)) != 0) co[i * 3 + k] = fo * co[i * 3 + k] + fn * acc[i * 3 + k]
            }
        }
    }

    fun wave(co: FloatArray, normals: FloatArray?, weights: FloatArray?, ctime: Float, flag: Int, startX: Float, startY: Float, height: Float, width: Float, narrow: Float, speed: Float, damp0: Float, falloff: Float, timeOffset: Float, lifetime: Float, tex: Array<BlendTexture.Result>? = null) {
        val n = co.size / 3
        val minfac = (1.0 / exp((width * narrow * width * narrow).toDouble())).toFloat()
        val damp = if (damp0 == 0f) 10f else damp0
        var lifefac = height
        if (lifetime != 0f) {
            val x = ctime - timeOffset
            if (x > lifetime) {
                lifefac = x - lifetime
                lifefac = if (lifefac > damp) 0f else height * (1f - sqrt(lifefac / damp))
            }
        }
        if (lifefac == 0f) return
        val falloffInv = if (falloff != 0f) 1f / falloff else 1f
        val useNormals = flag and (1 shl 4) != 0 && normals != null
        for (i in 0 until n) {
            val x = co[i * 3] - startX
            val y = co[i * 3 + 1] - startY
            val w = weights?.get(i) ?: 1f
            if (weights != null && w == 0f) continue
            var amplit = when (flag and 6) {
                2 -> x
                4 -> y
                6 -> sqrt(x * x + y * y)
                else -> 0f
            }
            amplit -= (ctime - timeOffset) * speed
            if (flag and (1 shl 3) != 0) amplit = ((amplit - width) % (2f * width)) + width
            var falloffFac = 1f
            if (falloff != 0f) {
                val dist = when (flag and 6) {
                    2 -> abs(x)
                    4 -> abs(y)
                    6 -> sqrt(x * x + y * y)
                    else -> 0f
                }
                falloffFac = (1f - dist * falloffInv).coerceIn(0f, 1f)
            }
            if (falloffFac != 0f && amplit > -width && amplit < width) {
                amplit *= narrow
                amplit = (1f / exp(amplit * amplit) - minfac)
                if (tex != null) amplit *= tex[i].tin
                amplit *= w * falloffFac
                if (useNormals) {
                    if (flag and (1 shl 5) != 0) co[i * 3] += lifefac * amplit * normals!![i * 3]
                    if (flag and (1 shl 6) != 0) co[i * 3 + 1] += lifefac * amplit * normals!![i * 3 + 1]
                    if (flag and (1 shl 7) != 0) co[i * 3 + 2] += lifefac * amplit * normals!![i * 3 + 2]
                } else co[i * 3 + 2] += lifefac * amplit
            }
        }
    }

    fun displace(co: FloatArray, normals: FloatArray?, weights: FloatArray?, strength: Float, midlevel: Float, direction: Int, toWorld: FloatArray?, tex: Array<BlendTexture.Result>? = null) {
        if (strength == 0f) return
        if (tex == null && direction == 4) return
        val n = co.size / 3
        for (i in 0 until n) {
            var s = strength
            if (weights != null) {
                if (weights[i] == 0f) continue
                s *= weights[i]
            }
            var d = if (tex != null) tex[i].tin - midlevel else 1f - midlevel
            d *= s
            d = d.coerceIn(-10000f, 10000f)
            when (direction) {
                0, 1, 2 -> if (toWorld != null) for (k in 0 until 3) co[i * 3 + k] += d * toWorld[k * 4 + direction] else co[i * 3 + direction] += d
                3, 5 -> if (normals != null) for (k in 0 until 3) co[i * 3 + k] += normals[i * 3 + k] * d
                4 -> {
                    val c = tex!![i].rgba
                    var v = floatArrayOf(c[0] - midlevel, c[1] - midlevel, c[2] - midlevel)
                    if (toWorld != null) v = FloatArray(3) { r -> toWorld[r * 4] * v[0] + toWorld[r * 4 + 1] * v[1] + toWorld[r * 4 + 2] * v[2] }
                    for (k in 0 until 3) co[i * 3 + k] += v[k] * s
                }
            }
        }
    }

    private fun cotWeight(co: FloatArray, a: Int, b: Int, c: Int): Float {
        val ax = co[b * 3] - co[a * 3]; val ay = co[b * 3 + 1] - co[a * 3 + 1]; val az = co[b * 3 + 2] - co[a * 3 + 2]
        val bx = co[c * 3] - co[a * 3]; val by = co[c * 3 + 1] - co[a * 3 + 1]; val bz = co[c * 3 + 2] - co[a * 3 + 2]
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        val cl = sqrt(cx * cx + cy * cy + cz * cz)
        return if (cl > 1.1920929E-7f) (ax * bx + ay * by + az * bz) / cl else 0f
    }

    private fun triArea(co: FloatArray, a: Int, b: Int, c: Int): Float {
        val ax = co[a * 3] - co[b * 3]; val ay = co[a * 3 + 1] - co[b * 3 + 1]; val az = co[a * 3 + 2] - co[b * 3 + 2]
        val bx = co[b * 3] - co[c * 3]; val by = co[b * 3 + 1] - co[c * 3 + 1]; val bz = co[b * 3 + 2] - co[c * 3 + 2]
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        return sqrt(cx * cx + cy * cy + cz * cz) / 2f
    }

    private fun meshVolume(co: FloatArray, topo: MeshTopology, center: FloatArray): Float {
        var vol = 0f
        for (f in 0 until topo.faceCount) {
            val s = topo.faceStart[f]
            val m = topo.faceSize[f]
            for (k in 2 until m) {
                val p1 = topo.corners[s]; val p2 = topo.corners[s + k - 1]; val p3 = topo.corners[s + k]
                val m00 = center[0] - co[p1 * 3]; val m01 = center[1] - co[p1 * 3 + 1]; val m02 = center[2] - co[p1 * 3 + 2]
                val m10 = co[p1 * 3] - co[p2 * 3]; val m11 = co[p1 * 3 + 1] - co[p2 * 3 + 1]; val m12 = co[p1 * 3 + 2] - co[p2 * 3 + 2]
                val m20 = co[p2 * 3] - co[p3 * 3]; val m21 = co[p2 * 3 + 1] - co[p3 * 3 + 1]; val m22 = co[p2 * 3 + 2] - co[p3 * 3 + 2]
                vol += (m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20)) / 6f
            }
        }
        return abs(vol)
    }

    internal class SparseRows(val n: Int, private val colCount: Int = n) {
        private val rows = Array(n) { HashMap<Int, Double>() }
        fun add(r: Int, c: Int, v: Float) {
            if (r !in 0 until n || c !in 0 until colCount) return
            rows[r].merge(c, v.toDouble(), Double::plus)
        }
        fun compile(): Csr {
            val start = IntArray(n + 1)
            for (i in 0 until n) start[i + 1] = start[i] + rows[i].size
            val cols = IntArray(start[n])
            val vals = DoubleArray(start[n])
            for (i in 0 until n) {
                var k = start[i]
                for ((c, v) in rows[i].entries.sortedBy { it.key }) { cols[k] = c; vals[k] = v; k++ }
            }
            return Csr(n, start, cols, vals)
        }
    }

    internal class Csr(val n: Int, val start: IntArray, val cols: IntArray, val vals: DoubleArray) {
        fun mul(x: DoubleArray, out: DoubleArray) {
            for (i in 0 until n) {
                var s = 0.0
                for (k in start[i] until start[i + 1]) s += vals[k] * x[cols[k]]
                out[i] = s
            }
        }

        fun mulT(y: DoubleArray, out: DoubleArray) {
            out.fill(0.0)
            for (i in 0 until n) {
                val yi = y[i]
                if (yi == 0.0) continue
                for (k in start[i] until start[i + 1]) out[cols[k]] += vals[k] * yi
            }
        }

        fun leastSquares(rows: Int, b: DoubleArray, x: DoubleArray, cols0: Int): Boolean {
            val m = cols0
            val tmp = DoubleArray(rows)
            val diag = DoubleArray(m)
            for (i in 0 until rows) for (k in start[i] until start[i + 1]) diag[cols[k]] += vals[k] * vals[k]
            for (i in 0 until m) if (diag[i] == 0.0) diag[i] = 1.0
            fun op(v: DoubleArray, out: DoubleArray) { mulRows(rows, v, tmp); mulTRows(rows, tmp, out) }
            val rhs = DoubleArray(m)
            mulTRows(rows, b, rhs)
            val r = DoubleArray(m)
            op(x, r)
            for (i in 0 until m) r[i] = rhs[i] - r[i]
            val z = DoubleArray(m) { r[it] / diag[it] }
            val p = z.copyOf()
            val ap = DoubleArray(m)
            var rz = (0 until m).sumOf { r[it] * z[it] }
            val bn = sqrt(rhs.sumOf { it * it }).takeIf { it > 0.0 } ?: 1.0
            for (it in 0 until maxOf(500, m * 4)) {
                if (sqrt(r.sumOf { v -> v * v }) / bn < 1e-14) return true
                op(p, ap)
                val pap = (0 until m).sumOf { p[it] * ap[it] }
                if (pap <= 0.0) break
                val alpha = rz / pap
                for (i in 0 until m) { x[i] += alpha * p[i]; r[i] -= alpha * ap[i] }
                for (i in 0 until m) z[i] = r[i] / diag[i]
                val rzNew = (0 until m).sumOf { r[it] * z[it] }
                val beta = rzNew / rz
                rz = rzNew
                for (i in 0 until m) p[i] = z[i] + beta * p[i]
            }
            return sqrt(r.sumOf { v -> v * v }) / bn < 1e-8
        }

        private fun mulRows(rows: Int, x: DoubleArray, out: DoubleArray) {
            for (i in 0 until rows) {
                var s = 0.0
                for (k in start[i] until start[i + 1]) s += vals[k] * x[cols[k]]
                out[i] = s
            }
        }

        private fun mulTRows(rows: Int, y: DoubleArray, out: DoubleArray) {
            out.fill(0.0)
            for (i in 0 until rows) {
                val yi = y[i]
                if (yi == 0.0) continue
                for (k in start[i] until start[i + 1]) out[cols[k]] += vals[k] * yi
            }
        }

        fun solve(b: DoubleArray, x: DoubleArray): Boolean {
            val diag = DoubleArray(n) { i -> var d = 0.0; for (k in start[i] until start[i + 1]) if (cols[k] == i) d = vals[k]; if (d == 0.0) 1.0 else d }
            val r = DoubleArray(n)
            mul(x, r)
            for (i in 0 until n) r[i] = b[i] - r[i]
            val bn = sqrt(b.sumOf { it * it }).takeIf { it > 0.0 } ?: 1.0
            val r0 = r.copyOf()
            val p = DoubleArray(n)
            val v = DoubleArray(n)
            val y = DoubleArray(n)
            val z = DoubleArray(n)
            val sv = DoubleArray(n)
            val t = DoubleArray(n)
            var rho = 1.0
            var alpha = 1.0
            var omega = 1.0
            for (it in 0 until maxOf(200, n * 2)) {
                if (sqrt(r.sumOf { it * it }) / bn < 1e-13) return true
                val rhoNew = (0 until n).sumOf { r0[it] * r[it] }
                if (rhoNew == 0.0) return false
                val beta = (rhoNew / rho) * (alpha / omega)
                for (i in 0 until n) p[i] = r[i] + beta * (p[i] - omega * v[i])
                for (i in 0 until n) y[i] = p[i] / diag[i]
                mul(y, v)
                val r0v = (0 until n).sumOf { r0[it] * v[it] }
                if (r0v == 0.0) return false
                alpha = rhoNew / r0v
                for (i in 0 until n) sv[i] = r[i] - alpha * v[i]
                for (i in 0 until n) z[i] = sv[i] / diag[i]
                mul(z, t)
                val tt = t.sumOf { it * it }
                omega = if (tt == 0.0) 0.0 else (0 until n).sumOf { t[it] * sv[it] } / tt
                for (i in 0 until n) x[i] += alpha * y[i] + omega * z[i]
                for (i in 0 until n) r[i] = sv[i] - omega * t[i]
                rho = rhoNew
                if (omega == 0.0) return sqrt(r.sumOf { it * it }) / bn < 1e-9
            }
            return sqrt(r.sumOf { it * it }) / bn < 1e-9
        }
    }

    fun laplacianSmooth(co: FloatArray, topo: MeshTopology, weights: FloatArray?, lambda: Float, lambdaBorder: Float, repeat: Int, flag: Int) {
        val n = co.size / 3
        if (n == 0) return
        val minArea = 0.00001f
        val ne = topo.edgeCount
        val eweights = FloatArray(ne)
        val fweights = FloatArray(topo.corners.size * 3)
        val ringAreas = FloatArray(n)
        val vlengths = FloatArray(n)
        val vweights = FloatArray(n)
        val neEd = IntArray(n)
        val neFa = IntArray(n)
        val zerola = BooleanArray(n)
        for (i in 0 until ne) {
            val a = topo.edges[i * 2]; val b = topo.edges[i * 2 + 1]
            neEd[a]++; neEd[b]++
            val dx = co[a * 3] - co[b * 3]; val dy = co[a * 3 + 1] - co[b * 3 + 1]; val dz = co[a * 3 + 2] - co[b * 3 + 2]
            var w1 = sqrt(dx * dx + dy * dy + dz * dz)
            if (w1 < minArea) { zerola[a] = true; zerola[b] = true } else w1 = 1f / w1
            eweights[i] = w1
        }
        fun corners(f: Int, body: (Int, Int, Int) -> Unit) {
            val s = topo.faceStart[f]
            val term = s + topo.faceSize[f]
            var next = s
            var prev = term - 2
            var curr = term - 1
            while (next != term) {
                body(prev, curr, next)
                prev = curr; curr = next; next++
            }
        }
        val cv = topo.corners
        for (f in 0 until topo.faceCount) corners(f) { prev, curr, next ->
            val vp = cv[prev]; val vc = cv[curr]; val vn = cv[next]
            neFa[vc] += 1
            val areaf = triArea(co, vp, vc, vn)
            if (areaf < minArea) zerola[vc] = true
            ringAreas[vp] += areaf; ringAreas[vc] += areaf; ringAreas[vn] += areaf
            val w1 = cotWeight(co, vc, vn, vp) / 2f
            val w2 = cotWeight(co, vn, vp, vc) / 2f
            val w3 = cotWeight(co, vp, vc, vn) / 2f
            fweights[curr * 3] += w1; fweights[curr * 3 + 1] += w2; fweights[curr * 3 + 2] += w3
            vweights[vc] += w2 + w3
            vweights[vn] += w1 + w3
            vweights[vp] += w1 + w2
        }
        for (i in 0 until ne) {
            val a = topo.edges[i * 2]; val b = topo.edges[i * 2 + 1]
            if (neEd[a] != neFa[a] && neEd[b] != neFa[b]) { vlengths[a] += eweights[i]; vlengths[b] += eweights[i] }
        }
        val mat = SparseRows(n)
        val center = FloatArray(3)
        for (i in 0 until n) for (k in 0 until 3) center[k] += co[i * 3 + k]
        for (k in 0 until 3) center[k] *= 1f / n.toFloat()
        val lam = abs(lambda)
        val lamB = abs(lambdaBorder)
        for (i in 0 until n) {
            val wpaint = weights?.get(i) ?: 1f
            if (!zerola[i]) {
                if (flag and (1 shl 5) != 0) {
                    var w = vweights[i]
                    vweights[i] = if (w == 0f) 0f else -lam * wpaint / w
                    w = vlengths[i]
                    vlengths[i] = if (w == 0f) 0f else -lamB * wpaint * 2f / w
                    if (neEd[i] == neFa[i]) mat.add(i, i, 1f + lam * wpaint) else mat.add(i, i, 1f + lamB * wpaint * 2f)
                } else {
                    var w = vweights[i] * ringAreas[i]
                    vweights[i] = if (w == 0f) 0f else -lam * wpaint / (4f * w)
                    w = vlengths[i]
                    vlengths[i] = if (w == 0f) 0f else -lamB * wpaint * 2f / w
                    if (neEd[i] == neFa[i]) mat.add(i, i, 1f + lam * wpaint / (4f * ringAreas[i])) else mat.add(i, i, 1f + lamB * wpaint * 2f)
                }
            } else mat.add(i, i, 1f)
        }
        for (f in 0 until topo.faceCount) corners(f) { prev, curr, next ->
            val vp = cv[prev]; val vc = cv[curr]; val vn = cv[next]
            if (neEd[vc] == neFa[vc] && !zerola[vc]) {
                mat.add(vc, vn, fweights[curr * 3 + 2] * vweights[vc])
                mat.add(vc, vp, fweights[curr * 3 + 1] * vweights[vc])
            }
            if (neEd[vn] == neFa[vn] && !zerola[vn]) {
                mat.add(vn, vc, fweights[curr * 3 + 2] * vweights[vn])
                mat.add(vn, vp, fweights[curr * 3] * vweights[vn])
            }
            if (neEd[vp] == neFa[vp] && !zerola[vp]) {
                mat.add(vp, vc, fweights[curr * 3 + 1] * vweights[vp])
                mat.add(vp, vn, fweights[curr * 3] * vweights[vp])
            }
        }
        for (i in 0 until ne) {
            val a = topo.edges[i * 2]; val b = topo.edges[i * 2 + 1]
            if (neEd[a] != neFa[a] && neEd[b] != neFa[b] && !zerola[a] && !zerola[b]) {
                mat.add(a, b, eweights[i] * vlengths[a])
                mat.add(b, a, eweights[i] * vlengths[b])
            }
        }
        val csr = mat.compile()
        for (iter in 0 until repeat.coerceIn(0, 10_000)) {
            val sol = Array(3) { k -> DoubleArray(n) { co[it * 3 + k].toDouble() } }
            var ok = true
            for (k in 0 until 3) {
                val b = DoubleArray(n) { co[it * 3 + k].toDouble() }
                if (!csr.solve(b, sol[k])) ok = false
            }
            if (!ok) break
            val preserve = flag and (1 shl 4) != 0
            val vini = if (preserve) meshVolume(co, topo, center) else 0f
            for (i in 0 until n) {
                if (zerola[i]) continue
                val l = if (neEd[i] == neFa[i]) (if (lambda >= 0f) 1f else -1f) else (if (lambdaBorder >= 0f) 1f else -1f)
                for (k in 0 until 3) if (flag and (1 shl (k + 1)) != 0) co[i * 3 + k] += l * (sol[k][i].toFloat() - co[i * 3 + k])
            }
            if (preserve) {
                val vend = meshVolume(co, topo, center)
                if (vend != 0f) {
                    val beta = Math.pow((vini / vend).toDouble(), 1.0 / 3.0).toFloat()
                    for (i in 0 until n) for (k in 0 until 3) if (flag and (1 shl (k + 1)) != 0) co[i * 3 + k] = (co[i * 3 + k] - center[k]) * beta + center[k]
                }
            }
        }
    }


    private fun acosApprox(x: Float): Float {
        val f = abs(x)
        val m = if (f < 1f) 1f - (1f - f) else 1f
        val a = sqrt(1f - m) * (1.5707963267f + m * (-0.213300989f + m * (0.077980478f + m * -0.02164095f)))
        return if (x < 0f) PI.toFloat() - a else a
    }

    private fun normalize3(v: FloatArray, o: Int) {
        val l = sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2])
        if (l > 1.0e-35f) { v[o] /= l; v[o + 1] /= l; v[o + 2] /= l } else { v[o] = 0f; v[o + 1] = 0f; v[o + 2] = 0f }
    }

    private fun tangentSpaces(co: FloatArray, topo: MeshTopology, spaces: FloatArray, weights: FloatArray?, perVertex: FloatArray?) {
        perVertex?.fill(0f)
        val prev = FloatArray(3)
        val next = FloatArray(3)
        val cv = topo.corners
        for (f in 0 until topo.faceCount) {
            val s = topo.faceStart[f]
            val term = s + topo.faceSize[f]
            var nc = s
            var pc = term - 2
            var cc = term - 1
            for (k in 0 until 3) prev[k] = co[cv[pc] * 3 + k] - co[cv[cc] * 3 + k]
            normalize3(prev, 0)
            while (nc != term) {
                for (k in 0 until 3) next[k] = co[cv[cc] * 3 + k] - co[cv[nc] * 3 + k]
                normalize3(next, 0)
                val o = cc * 9
                val eps = 1.1920929E-7f * 10f
                if (abs(prev[0] - next[0]) <= eps && abs(prev[1] - next[1]) <= eps && abs(prev[2] - next[2]) <= eps) {
                    for (k in 0 until 9) spaces[o + k] = if (k % 4 == 0) 1f else 0f
                    if (weights != null) weights[cc] = 0f
                } else {
                    val nx = prev[1] * next[2] - prev[2] * next[1]
                    val ny = prev[2] * next[0] - prev[0] * next[2]
                    val nz = prev[0] * next[1] - prev[1] * next[0]
                    spaces[o + 6] = nx; spaces[o + 7] = ny; spaces[o + 8] = nz
                    normalize3(spaces, o + 6)
                    spaces[o] = prev[0] + next[0]; spaces[o + 1] = prev[1] + next[1]; spaces[o + 2] = prev[2] + next[2]
                    normalize3(spaces, o)
                    spaces[o + 3] = spaces[o + 7] * spaces[o + 2] - spaces[o + 8] * spaces[o + 1]
                    spaces[o + 4] = spaces[o + 8] * spaces[o] - spaces[o + 6] * spaces[o + 2]
                    spaces[o + 5] = spaces[o + 6] * spaces[o + 1] - spaces[o + 7] * spaces[o]
                    if (weights != null) {
                        val w = abs(acosApprox(next[0] * prev[0] + next[1] * prev[1] + next[2] * prev[2]))
                        weights[cc] = w
                        perVertex!![cv[cc]] += w
                    }
                }
                prev[0] = next[0]; prev[1] = next[1]; prev[2] = next[2]
                pc = cc; cc = nc; nc++
            }
        }
    }

    private fun correctiveIterate(co: FloatArray, topo: MeshTopology, w: FloatArray?, lambda0: Float, repeat: Int, type: Int) {
        val n = co.size / 3
        val ne = topo.edgeCount
        val e = topo.edges
        val count = FloatArray(n)
        for (i in 0 until ne) { count[e[i * 2]] += 1f; count[e[i * 2 + 1]] += 1f }
        val delta = FloatArray(n * 3)
        if (type == 1) {
            val lambda = lambda0 * 2f
            val eps = 1.1920929E-7f * 10f
            val lsum = FloatArray(n)
            repeat(repeat) {
                for (i in 0 until ne) {
                    val a = e[i * 2]; val b = e[i * 2 + 1]
                    var dx = co[b * 3] - co[a * 3]; var dy = co[b * 3 + 1] - co[a * 3 + 1]; var dz = co[b * 3 + 2] - co[a * 3 + 2]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    dx *= d; dy *= d; dz *= d
                    delta[a * 3] += dx; delta[a * 3 + 1] += dy; delta[a * 3 + 2] += dz
                    delta[b * 3] -= dx; delta[b * 3 + 1] -= dy; delta[b * 3 + 2] -= dz
                    lsum[a] += d; lsum[b] += d
                }
                for (i in 0 until n) {
                    val div = lsum[i] * count[i]
                    if (div > eps) {
                        val f = (if (w != null) lambda * w[i] else lambda) / div
                        for (k in 0 until 3) co[i * 3 + k] += delta[i * 3 + k] * f
                    }
                    delta[i * 3] = 0f; delta[i * 3 + 1] = 0f; delta[i * 3 + 2] = 0f
                    lsum[i] = 0f
                }
            }
        } else {
            val div = FloatArray(n) { i -> (if (w != null) w[i] * lambda0 else lambda0) * (if (count[i] != 0f) 1f / count[i] else 1f) }
            repeat(repeat) {
                for (i in 0 until ne) {
                    val a = e[i * 2]; val b = e[i * 2 + 1]
                    for (k in 0 until 3) {
                        val d = co[b * 3 + k] - co[a * 3 + k]
                        delta[a * 3 + k] += d
                        delta[b * 3 + k] -= d
                    }
                }
                for (i in 0 until n) for (k in 0 until 3) {
                    co[i * 3 + k] += delta[i * 3 + k] * div[i]
                    delta[i * 3 + k] = 0f
                }
            }
        }
    }

    private fun correctiveWeights(topo: MeshTopology, n: Int, mask: FloatArray?, pin: Boolean): FloatArray? {
        if (mask == null && !pin) return null
        val w = mask?.copyOf() ?: FloatArray(n) { 1f }
        if (pin) {
            val faces = HashMap<Long, Int>()
            for (f in 0 until topo.faceCount) {
                val s = topo.faceStart[f]
                val m = topo.faceSize[f]
                for (k in 0 until m) {
                    val a = topo.corners[s + k]; val b = topo.corners[s + (k + 1) % m]
                    val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                    faces[key] = (faces[key] ?: 0) + 1
                }
            }
            for (i in 0 until topo.edgeCount) {
                val a = topo.edges[i * 2]; val b = topo.edges[i * 2 + 1]
                if (faces[(minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()] == 1) { w[a] = 0f; w[b] = 0f }
            }
        }
        return w
    }

    fun correctiveSmooth(co: FloatArray, rest: FloatArray?, topo: MeshTopology, mask: FloatArray?, lambda: Float, scale: Float, repeat: Int, flag: Int, type: Int) {
        val n = co.size / 3
        val w = correctiveWeights(topo, n, mask, flag and 4 != 0)
        val reps = repeat.coerceIn(0, 100_000)
        if (flag and 2 != 0) {
            correctiveIterate(co, topo, w, lambda, reps, type)
            return
        }
        if (rest == null || rest.size != co.size) return
        val cv = topo.corners
        val smoothRest = rest.copyOf()
        correctiveIterate(smoothRest, topo, w, lambda, reps, type)
        val spaces = FloatArray(cv.size * 9)
        tangentSpaces(smoothRest, topo, spaces, null, null)
        val deltas = FloatArray(cv.size * 3)
        for (l in cv.indices) {
            val v = cv[l]
            val d0 = rest[v * 3] - smoothRest[v * 3]; val d1 = rest[v * 3 + 1] - smoothRest[v * 3 + 1]; val d2 = rest[v * 3 + 2] - smoothRest[v * 3 + 2]
            val o = l * 9
            val m = FloatArray(9) { spaces[o + it] }
            val inv = invert3(m) ?: floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
            deltas[l * 3] = inv[0] * d0 + inv[3] * d1 + inv[6] * d2
            deltas[l * 3 + 1] = inv[1] * d0 + inv[4] * d1 + inv[7] * d2
            deltas[l * 3 + 2] = inv[2] * d0 + inv[5] * d1 + inv[8] * d2
        }
        correctiveIterate(co, topo, w, lambda, reps, type)
        val tw = FloatArray(cv.size)
        val tv = FloatArray(n)
        tangentSpaces(co, topo, spaces, tw, tv)
        for (l in cv.indices) {
            val v = cv[l]
            val weight = tw[l] / tv[v]
            if (!(weight > 0f)) continue
            val o = l * 9
            val a = deltas[l * 3]; val b = deltas[l * 3 + 1]; val c = deltas[l * 3 + 2]
            for (k in 0 until 3) co[v * 3 + k] += (spaces[o + k] * a + spaces[o + 3 + k] * b + spaces[o + 6 + k] * c) * weight * scale
        }
    }

    private fun invert3(m: FloatArray): FloatArray? {
        val a = FloatArray(9)
        a[0] = m[4] * m[8] - m[7] * m[5]
        a[1] = -m[1] * m[8] + m[7] * m[2]
        a[2] = m[1] * m[5] - m[4] * m[2]
        a[3] = -m[3] * m[8] + m[6] * m[5]
        a[4] = m[0] * m[8] - m[6] * m[2]
        a[5] = -m[0] * m[5] + m[3] * m[2]
        a[6] = m[3] * m[7] - m[6] * m[4]
        a[7] = -m[0] * m[7] + m[6] * m[1]
        a[8] = m[0] * m[4] - m[3] * m[1]
        val det = m[0] * a[0] + m[1] * a[3] + m[2] * a[6]
        if (det == 0f) return null
        for (k in 0 until 9) a[k] /= det
        return a
    }


    fun warp(co: FloatArray, weights: FloatArray?, matFrom: FloatArray, matTo: FloatArray, strength0: Float, radius: Float, falloffType: Int, flag: Int, curve: BlendCurveMap?, tex: Array<BlendTexture.Result>? = null) {
        val n = co.size / 3
        val rsq = radius * radius
        var final = RigMath.mul(RigMath.inv(matFrom), matTo)
        val fromInv = RigMath.inv(matFrom)
        var strength = strength0
        if (strength < 0f) {
            strength = -strength
            val loc = floatArrayOf(final[12], final[13], final[14])
            final = RigMath.inv(final)
            final[12] = -loc[0]; final[13] = -loc[1]; final[14] = -loc[2]
        }
        val unitParts = RigMath.locRotSize(CleanRoomGltf.IDENTITY)
        val finalParts = RigMath.locRotSize(final)
        val uq = RigMath.quat(unitParts.second)
        val fq = RigMath.quat(finalParts.second)
        for (i in 0 until n) {
            var fac = 1f
            if (falloffType != 0) {
                val dx = co[i * 3] - matFrom[12]; val dy = co[i * 3 + 1] - matFrom[13]; val dz = co[i * 3 + 2] - matFrom[14]
                val d = dx * dx + dy * dy + dz * dz
                if (!(d < rsq)) continue
                fac = (radius - sqrt(d)) / radius
                if (fac == 0f) continue
            }
            var weight = strength
            if (weights != null) {
                weight = weights[i] * strength
                if (weight <= 0f) continue
            }
            fac = when (falloffType) {
                0, 6 -> 1f
                1 -> curve?.evaluate(fac) ?: fac
                2 -> fac * fac
                3 -> 3f * fac * fac - 2f * fac * fac * fac
                4 -> sqrt(fac)
                7 -> sqrt(2 * fac - fac * fac)
                8 -> fac * (2f - fac)
                else -> fac
            }
            fac *= weight
            if (tex != null) fac *= tex[i].tin
            if (fac == 0f) continue
            var p = RigMath.point(fromInv, floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2]))
            p = if (fac == 1f) RigMath.point(final, p)
            else if (flag and 1 != 0) {
                val q = RigMath.slerp(uq, fq, fac)
                val l = RigMath.lerp(unitParts.first, finalParts.first, fac)
                val sz = RigMath.lerp(unitParts.third, finalParts.third, fac)
                RigMath.point(RigMath.compose(l, RigMath.quatMat(q), sz), p)
            } else RigMath.lerp(p, RigMath.point(final, p), fac)
            p = RigMath.point(matFrom, p)
            co[i * 3] = p[0]; co[i * 3 + 1] = p[1]; co[i * 3 + 2] = p[2]
        }
    }


    fun surfaceDeform(co: FloatArray, weights: FloatArray?, b: SurfaceBind, target0: FloatArray, strength: Float) {
        if (strength == 0f) return
        val nt = target0.size / 3
        val tc = FloatArray(target0.size)
        for (i in 0 until nt) RigMath.point(b.mat, floatArrayOf(target0[i * 3], target0[i * 3 + 1], target0[i * 3 + 2])).copyInto(tc, i * 3)
        val n = co.size / 3
        for (i in b.vertexIdx.indices) {
            val vi = b.vertexIdx[i]
            if (vi !in 0 until n) continue
            val w = weights?.get(vi) ?: 1f
            if (w == 0f) continue
            val off = FloatArray(3)
            var ok = true
            for (j in b.bindStart[i] until b.bindStart[i + 1]) {
                val ind = b.inds[j]
                if (ind.any { it !in 0 until nt }) { ok = false; break }
                var nx = 0f; var ny = 0f; var nz = 0f
                var prev = ind[ind.size - 1]
                for (cur in ind) {
                    nx += (tc[prev * 3 + 1] - tc[cur * 3 + 1]) * (tc[prev * 3 + 2] + tc[cur * 3 + 2])
                    ny += (tc[prev * 3 + 2] - tc[cur * 3 + 2]) * (tc[prev * 3] + tc[cur * 3])
                    nz += (tc[prev * 3] - tc[cur * 3]) * (tc[prev * 3 + 1] + tc[cur * 3 + 1])
                    prev = cur
                }
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 1.0e-35f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 0f; nz = 0f }
                val t = FloatArray(3)
                val wt = b.weights[j]
                when (b.modes[j]) {
                    1 -> for (k in ind.indices) for (c in 0 until 3) t[c] += tc[ind[k] * 3 + c] * wt[k]
                    2 -> {
                        val cent = FloatArray(3)
                        for (k in ind) for (c in 0 until 3) cent[c] += tc[k * 3 + c]
                        for (c in 0 until 3) cent[c] *= 1f / ind.size
                        for (c in 0 until 3) t[c] = tc[ind[0] * 3 + c] * wt[0] + tc[ind[1] * 3 + c] * wt[1] + cent[c] * wt[2]
                    }
                    else -> for (c in 0 until 3) t[c] = tc[ind[0] * 3 + c] * wt[0] + tc[ind[1] * 3 + c] * wt[1] + tc[ind[2] * 3 + c] * wt[2]
                }
                val nd = b.normalDist[j]
                t[0] += nx * nd; t[1] += ny * nd; t[2] += nz * nd
                val inf = b.influence[j]
                for (c in 0 until 3) off[c] += t[c] * inf
            }
            if (!ok) continue
            val s = strength * w
            for (c in 0 until 3) co[vi * 3 + c] += (off[c] - co[vi * 3 + c]) * s
        }
    }


    fun laplacianDeform(co: FloatArray, bind: FloatArray, topo: MeshTopology, anchors: IntArray, repeat: Int): Boolean {
        val n = co.size / 3
        if (bind.size != co.size || anchors.isEmpty()) return false
        val (tc, _) = topo.cornerTris(bind)
        val nt = tc.size / 3
        val tris = IntArray(tc.size) { topo.corners[tc[it]] }
        val na = anchors.size
        val mat = SparseRows(n + na, n)
        val no = FloatArray(n * 3)
        val delta = FloatArray(n * 3)
        fun p(v: Int) = floatArrayOf(bind[v * 3], bind[v * 3 + 1], bind[v * 3 + 2])
        for (t in 0 until nt) {
            val v = intArrayOf(tris[t * 3], tris[t * 3 + 1], tris[t * 3 + 2])
            if (v.any { it !in 0 until n }) return false
            val c0 = p(v[0]); val c1 = p(v[1]); val c2 = p(v[2])
            val tn = ShrinkMath.normalized(RigMath.cross(RigMath.sub(c0, c1), RigMath.sub(c1, c2)))
            for (j in 0 until 3) for (k in 0 until 3) no[v[j] * 3 + k] += tn[k]
            for (j in 0 until 3) {
                val i0 = v[j]; val i1 = v[(j + 1) % 3]; val i2 = v[(j + 2) % 3]
                val w2 = cotWeight(bind, i2, i0, i1)
                val w3 = cotWeight(bind, i1, i2, i0)
                for (k in 0 until 3) {
                    delta[i0 * 3 + k] += bind[i0 * 3 + k] * (w2 + w3)
                    delta[i0 * 3 + k] -= bind[i1 * 3 + k] * w2
                    delta[i0 * 3 + k] -= bind[i2 * 3 + k] * w3
                }
                mat.add(i0, i1, -w2)
                mat.add(i0, i2, -w3)
                mat.add(i0, i0, w2 + w3)
            }
        }
        val ring = Array(n) { ArrayList<Int>() }
        for (e in 0 until topo.edgeCount) {
            val a = topo.edges[e * 2]; val b = topo.edges[e * 2 + 1]
            if (a !in 0 until n || b !in 0 until n) continue
            ring[a].add(b); ring[b].add(a)
        }
        val ringF = Array(n) { ArrayList<Int>() }
        for (t in 0 until nt) for (j in 0 until 3) ringF[tris[t * 3 + j]].add(t)
        val unit = IntArray(n)
        for (i in 0 until n) {
            ShrinkMath.normalized(floatArrayOf(no[i * 3], no[i * 3 + 1], no[i * 3 + 2])).copyInto(no, i * 3)
            var minj = 1000000f
            for (vid in ring[i]) {
                val vj = ShrinkMath.normalized(floatArrayOf(bind[vid * 3] - bind[i * 3], bind[vid * 3 + 1] - bind[i * 3 + 1], bind[vid * 3 + 2] - bind[i * 3 + 2]))
                val mjt = abs(vj[0] * no[i * 3] + vj[1] * no[i * 3 + 1] + vj[2] * no[i * 3 + 2])
                if (mjt < minj) { minj = mjt; unit[i] = vid }
            }
        }
        for (a in 0 until na) mat.add(n + a, anchors[a], 1f)
        val csr = mat.compile()
        val x = Array(3) { k -> DoubleArray(n) { bind[it * 3 + k].toDouble() } }
        for (a in 0 until na) for (k in 0 until 3) x[k][anchors[a]] = co[anchors[a] * 3 + k].toDouble()
        fun solveWith(rhs: Array<DoubleArray>): Boolean {
            for (k in 0 until 3) {
                for (a in 0 until na) rhs[k][n + a] = co[anchors[a] * 3 + k].toDouble()
                if (!csr.leastSquares(n + na, rhs[k], x[k], n)) return false
            }
            return true
        }
        if (!solveWith(Array(3) { k -> DoubleArray(n + na).also { b -> for (i in 0 until n) b[i] = delta[i * 3 + k].toDouble() } })) return false
        for (rep in 1..repeat) {
            val rhs = Array(3) { DoubleArray(n + na) }
            for (i in 0 until n) {
                val ni0 = floatArrayOf(no[i * 3], no[i * 3 + 1], no[i * 3 + 2])
                val pi0 = p(i)
                var uij = RigMath.sub(p(unit[i]), pi0)
                uij = ShrinkMath.normalized(RigMath.sub(uij, RigMath.scale(ni0, RigMath.dot(uij, ni0))))
                var e2 = RigMath.cross(ni0, uij)
                val di = floatArrayOf(delta[i * 3], delta[i * 3 + 1], delta[i * 3 + 2])
                val alpha = RigMath.dot(ni0, di); val beta = RigMath.dot(uij, di); val gamma = RigMath.dot(e2, di)
                val pi = FloatArray(3) { x[it][i].toFloat() }
                var ni = FloatArray(3)
                var pj = p(unit[i])
                for (t in ringF[i]) {
                    val vn = Array(3) { j -> FloatArray(3) { k -> x[k][tris[t * 3 + j]].toFloat() } }
                    for (j in 0 until 3) if (tris[t * 3 + j] == unit[i]) pj = vn[j]
                    val fn = ShrinkMath.normalized(RigMath.cross(RigMath.sub(vn[0], vn[1]), RigMath.sub(vn[1], vn[2])))
                    ni = RigMath.add(ni, fn)
                }
                ni = ShrinkMath.normalized(ni)
                uij = RigMath.sub(pj, pi)
                uij = ShrinkMath.normalized(RigMath.sub(uij, RigMath.scale(ni, RigMath.dot(uij, ni))))
                e2 = RigMath.cross(ni, uij)
                val fni = FloatArray(3) { alpha * ni[it] + beta * uij[it] + gamma * e2[it] }
                val src = if (fni[0] * fni[0] + fni[1] * fni[1] + fni[2] * fni[2] > 1.1920929E-7f) fni else di
                for (k in 0 until 3) rhs[k][i] = src[k].toDouble()
            }
            if (!solveWith(rhs)) return false
        }
        for (i in 0 until n) for (k in 0 until 3) co[i * 3 + k] = x[k][i].toFloat()
        return true
    }

}

internal class SurfaceBind(
    val vertexIdx: IntArray, val bindStart: IntArray, val inds: List<IntArray>, val weights: List<FloatArray>,
    val modes: IntArray, val normalDist: FloatArray, val influence: FloatArray, val mat: FloatArray
)

internal class BlendCurveMap(xs: FloatArray, ys: FloatArray, pointFlags: IntArray, private val flag: Int, clip: FloatArray) {
    private val clipYMin = clip[2]
    private val clipYMax = clip[3]
    private val extIn = FloatArray(2)
    private val extOut = FloatArray(2)
    private val table = FloatArray((TABLE + 1) * 2)
    private var minTable = clip[0]
    private var range = 0f

    init {
        val wrap = flag and (1 shl 5) != 0
        val n = maxOf(xs.size, 2)
        val v = Array(n) { Array(3) { FloatArray(2) } }
        val h = IntArray(n)
        var maxTable = clip[1]
        if (xs.size >= 2) {
            for (a in 0 until n) {
                minTable = minOf(minTable, xs[a]); maxTable = maxOf(maxTable, xs[a])
                v[a][1][0] = xs[a]; v[a][1][1] = ys[a]
                h[a] = if (pointFlags[a] and 2 != 0) VECT else if (pointFlags[a] and 4 != 0) AUTO_ANIM else AUTO
            }
        } else {
            minTable = 0f; maxTable = 0f
            h[0] = AUTO; h[1] = AUTO
        }
        val tableRange = maxTable - minTable
        val pre = Array(3) { v[n - 1][it].copyOf() }
        val post = Array(3) { v[0][it].copyOf() }
        var preH = h[n - 1]
        var postH = h[0]
        if (wrap) {
            preH = h[n - 1]; pre[1][0] = v[n - 1][1][0] - tableRange; pre[1][1] = v[n - 1][1][1]
            postH = h[0]; post[1][0] = v[0][1][0] + tableRange; post[1][1] = v[0][1][1]
        }
        var prev: Array<FloatArray>? = if (wrap) pre else null
        for (a in 0 until n) {
            val next = if (a != n - 1) v[a + 1] else if (wrap) post else null
            handle(v[a], h[a], prev, next)
            prev = v[a]
        }
        pre[0][0] = v[n - 1][0][0] - tableRange; pre[0][1] = v[n - 1][0][1]
        pre[2][0] = v[n - 1][2][0] - tableRange; pre[2][1] = v[n - 1][2][1]
        post[0][0] = v[0][0][0] + tableRange; post[0][1] = v[0][0][1]
        post[2][0] = v[0][2][0] + tableRange; post[2][1] = v[0][2][1]
        if (n > 2 && !wrap) {
            if (h[0] == AUTO) {
                val hlen = len(v[0][1][0] - v[0][2][0], v[0][1][1] - v[0][2][1])
                val vx = maxOf(v[1][0][0], v[0][1][0]) - v[0][1][0]
                val vy = v[1][0][1] - v[0][1][1]
                val nlen = len(vx, vy)
                if (nlen > EPS) {
                    val fx = vx * (hlen / nlen); val fy = vy * (hlen / nlen)
                    v[0][2][0] = fx + v[0][1][0]; v[0][2][1] = fy + v[0][1][1]
                    v[0][0][0] = v[0][1][0] - fx; v[0][0][1] = v[0][1][1] - fy
                }
            }
            val a = n - 1
            if (h[a] == AUTO) {
                val hlen = len(v[a][1][0] - v[a][0][0], v[a][1][1] - v[a][0][1])
                val vx = minOf(v[a - 1][2][0], v[a][1][0]) - v[a][1][0]
                val vy = v[a - 1][2][1] - v[a][1][1]
                val nlen = len(vx, vy)
                if (nlen > EPS) {
                    val fx = vx * (hlen / nlen); val fy = vy * (hlen / nlen)
                    v[a][0][0] = fx + v[a][1][0]; v[a][0][1] = fy + v[a][1][1]
                    v[a][2][0] = v[a][1][0] - fx; v[a][2][1] = v[a][1][1] - fy
                }
            }
        }
        val total = if (wrap) (n + 1) * RESOL else (n - 1) * RESOL
        val pts = FloatArray(total * 2)
        var at = 0
        if (wrap) { segment(pre, v[0], pts, at); at += 2 * RESOL }
        for (a in 0 until n - 1) { segment(v[a], v[a + 1], pts, at); at += 2 * RESOL }
        if (wrap) segment(v[n - 1], post, pts, at)
        extIn[0] = v[0][0][0] - v[0][1][0]; extIn[1] = v[0][0][1] - v[0][1][1]
        var r = sqrt(extIn[0] * extIn[0] + extIn[1] * extIn[1])
        if (r == 0f) { extIn[0] = -1f; extIn[1] = 0f; r = 1f }
        extIn[0] /= r; extIn[1] /= r
        extOut[0] = v[n - 1][1][0] - v[n - 1][2][0]; extOut[1] = v[n - 1][1][1] - v[n - 1][2][1]
        r = sqrt(extOut[0] * extOut[0] + extOut[1] * extOut[1])
        if (r == 0f) { extOut[0] = -1f; extOut[1] = 0f; r = 1f }
        extOut[0] /= r; extOut[1] /= r
        val step = (1f / TABLE) * tableRange
        range = if (step != 0f) 1f / step else 0f
        val last = (total - 1) * 2
        var p = 0
        for (a in 0..TABLE) {
            val x = minTable + step * a.toFloat()
            table[a * 2] = x
            while (x >= pts[p] && p != last) p += 2
            table[a * 2 + 1] = if ((p == 0 || (p == last && x >= pts[p])) && !wrap) {
                if (abs(x - pts[p]) <= 1e-6f) pts[p + 1] else extend(x, pts[0], pts[1], pts[last], pts[last + 1])
            } else if (p == 0) {
                pts[1]
            } else {
                var f1 = pts[p] - pts[p - 2]
                val f2 = pts[p] - x
                f1 = if (f1 > EPS) f2 / f1 else 0f
                f1 * pts[p - 1] + (1f - f1) * pts[p + 1]
            }
        }
    }

    private fun len(x: Float, y: Float) = sqrt(x * x + y * y)

    private fun handle(b: Array<FloatArray>, h: Int, prev: Array<FloatArray>?, next: Array<FloatArray>?) {
        val p2 = b[1]
        var p1: FloatArray
        val p3: FloatArray
        if (prev == null) {
            val n3 = next!![1]
            p1 = floatArrayOf(2f * p2[0] - n3[0], 2f * p2[1] - n3[1])
        } else p1 = prev[1]
        if (next == null) {
            p1 = prev!![1]
            p3 = floatArrayOf(2f * p2[0] - p1[0], 2f * p2[1] - p1[1])
        } else p3 = next[1]
        val ax = p2[0] - p1[0]; val ay = p2[1] - p1[1]
        val bx = p3[0] - p2[0]; val by = p3[1] - p2[1]
        var la = len(ax, ay)
        var lb = len(bx, by)
        if (la == 0f) la = 1f
        if (lb == 0f) lb = 1f
        if (h == AUTO || h == AUTO_ANIM) {
            val tx = bx / lb + ax / la
            val ty = by / lb + ay / la
            val l = len(tx, ty) * 2.5614f
            if (l != 0f) {
                la /= l
                b[0][0] = p2[0] + tx * -la; b[0][1] = p2[1] + ty * -la
                if (h == AUTO_ANIM && next != null && prev != null) {
                    val d1 = prev[1][1] - p2[1]; val d2 = next[1][1] - p2[1]
                    if ((d1 <= 0f && d2 <= 0f) || (d1 >= 0f && d2 >= 0f)) b[0][1] = p2[1]
                    else if (d1 <= 0f) b[0][1] = maxOf(prev[1][1], b[0][1]) else b[0][1] = minOf(prev[1][1], b[0][1])
                }
                lb /= l
                b[2][0] = p2[0] + tx * lb; b[2][1] = p2[1] + ty * lb
                if (h == AUTO_ANIM && next != null && prev != null) {
                    val d1 = prev[1][1] - p2[1]; val d2 = next[1][1] - p2[1]
                    if ((d1 <= 0f && d2 <= 0f) || (d1 >= 0f && d2 >= 0f)) b[2][1] = p2[1]
                    else if (d1 <= 0f) b[2][1] = minOf(next[1][1], b[2][1]) else b[2][1] = maxOf(next[1][1], b[2][1])
                }
            }
        }
        if (h == VECT) {
            b[0][0] = p2[0] + ax * (-1f / 3f); b[0][1] = p2[1] + ay * (-1f / 3f)
            b[2][0] = p2[0] + bx * (1f / 3f); b[2][1] = p2[1] + by * (1f / 3f)
        }
    }

    private fun segment(start: Array<FloatArray>, end: Array<FloatArray>, out: FloatArray, at: Int) {
        val v1 = start[1]; val v2 = start[2].copyOf(); val v3 = end[0].copyOf(); val v4 = end[1]
        val h1x = v1[0] - v2[0]; val h1y = v1[1] - v2[1]
        val h2x = v4[0] - v3[0]; val h2y = v4[1] - v3[1]
        val l = v4[0] - v1[0]
        val l1 = abs(h1x); val l2 = abs(h2x)
        if (l1 + l2 != 0f && l1 + l2 > l) {
            val fac = l / (l1 + l2)
            v2[0] = v1[0] - fac * h1x; v2[1] = v1[1] - fac * h1y
            v3[0] = v4[0] - fac * h2x; v3[1] = v4[1] - fac * h2y
        }
        start[2][0] = v2[0]; start[2][1] = v2[1]; end[0][0] = v3[0]; end[0][1] = v3[1]
        forwardDiff(v1[0], v2[0], v3[0], v4[0], out, at)
        forwardDiff(v1[1], v2[1], v3[1], v4[1], out, at + 1)
    }

    private fun forwardDiff(q0i: Float, q1i: Float, q2i: Float, q3i: Float, out: FloatArray, at: Int) {
        val it = RESOL - 1
        var f = it.toFloat()
        val rt0 = q0i
        val rt1 = 3f * (q1i - q0i) / f
        f *= f
        val rt2 = 3f * (q0i - 2f * q1i + q2i) / f
        f *= it
        val rt3 = (q3i - q0i + 3f * (q1i - q2i)) / f
        var q0 = rt0
        var q1 = rt1 + rt2 + rt3
        var q2 = 2 * rt2 + 6 * rt3
        val q3 = 6 * rt3
        for (a in 0..it) {
            out[at + a * 2] = q0
            q0 += q1; q1 += q2; q2 += q3
        }
    }

    private fun extend(x: Float, fx: Float, fy: Float, lx: Float, ly: Float): Float {
        if (x <= fx) {
            if (flag and (1 shl 4) == 0) return fy
            if (extIn[0] == 0f) return fy + extIn[1] * 10000f
            return fy + extIn[1] * (x - fx) / extIn[0]
        }
        if (x >= lx) {
            if (flag and (1 shl 4) == 0) return ly
            if (extOut[0] == 0f) return ly - extOut[1] * 10000f
            return ly + extOut[1] * (x - lx) / extOut[0]
        }
        return 0f
    }

    fun evaluate(value: Float): Float {
        var fi = (value - minTable) * range
        val i = fi.toInt()
        var v = if (fi < 0f || fi > TABLE) extend(value, table[0], table[1], table[TABLE * 2], table[TABLE * 2 + 1])
        else if (i < 0) table[1]
        else if (i >= TABLE) table[TABLE * 2 + 1]
        else {
            fi -= i.toFloat()
            (1f - fi) * table[i * 2 + 1] + fi * table[i * 2 + 3]
        }
        if (flag and 1 != 0) {
            if (v < clipYMin) v = clipYMin else if (v > clipYMax) v = clipYMax
        }
        return v
    }

    companion object {
        private const val TABLE = 256
        private const val RESOL = 32
        private const val EPS = 1.1920929E-7f
        private const val AUTO = 1
        private const val VECT = 2
        private const val AUTO_ANIM = 5
    }
}
