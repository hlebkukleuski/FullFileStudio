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

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import kotlin.math.sqrt

internal object BlendPolyfill {
    private const val CONCAVE = -1
    private const val TANGENTIAL = 0
    private const val CONVEX = 1

    fun triangulate(co: FloatArray, verts: IntArray): IntArray {
        val n = verts.size
        if (n < 3) return IntArray(0)
        if (n == 3) return intArrayOf(0, 1, 2)
        var nx = 0f
        var ny = 0f
        var nz = 0f
        var prev = verts[n - 1]
        for (j in 0 until n) {
            val cur = verts[j]
            nx += (co[prev * 3 + 1] - co[cur * 3 + 1]) * (co[prev * 3 + 2] + co[cur * 3 + 2])
            ny += (co[prev * 3 + 2] - co[cur * 3 + 2]) * (co[prev * 3] + co[cur * 3])
            nz += (co[prev * 3] - co[cur * 3]) * (co[prev * 3 + 1] + co[cur * 3 + 1])
            prev = cur
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1.0e-35f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 0f; nz = 1f }
        return triangulateWithNormal(co, verts, nx, ny, nz)
    }

    fun triangulateWithNormal(co: FloatArray, verts: IntArray, nx: Float, ny: Float, nz: Float): IntArray {
        val n = verts.size
        val ax = -nx; val ay = -ny; val az = -nz
        val b1 = FloatArray(3)
        val b2 = FloatArray(3)
        val f = ax * ax + ay * ay
        if (f > 1.1920929E-7f) {
            val d = 1f / sqrt(f)
            b1[0] = ay * d; b1[1] = -ax * d; b1[2] = 0f
            b2[0] = -az * b1[1]; b2[1] = az * b1[0]; b2[2] = ax * b1[1] - ay * b1[0]
        } else {
            b1[0] = if (az < 0f) -1f else 1f
            b2[1] = 1f
        }
        val p = FloatArray(n * 2)
        for (j in 0 until n) {
            val v = verts[j]
            p[j * 2] = b1[0] * co[v * 3] + b1[1] * co[v * 3 + 1] + b1[2] * co[v * 3 + 2]
            p[j * 2 + 1] = b2[0] * co[v * 3] + b2[1] * co[v * 3 + 1] + b2[2] * co[v * 3 + 2]
        }
        lastProjected = p
        return fill(p, n)
    }

    var lastProjected = FloatArray(0)

    private fun sign(a: Float) = if (a > 0f) CONVEX else if (a == 0f) TANGENTIAL else CONCAVE

    private fun spanSign(p: FloatArray, i1: Int, i2: Int, i3: Int): Int {
        val d2x = p[i2 * 2] - p[i3 * 2]; val d2y = p[i2 * 2 + 1] - p[i3 * 2 + 1]
        val d3x = p[i1 * 2] - p[i3 * 2]; val d3y = p[i1 * 2 + 1] - p[i3 * 2 + 1]
        return sign(d2x * d3y - d3x * d2y)
    }

    fun fill(p: FloatArray, n: Int): IntArray {
        val next = IntArray(n) { (it + 1) % n }
        val prev = IntArray(n) { (it + n - 1) % n }
        val index = IntArray(n) { it }
        val sgn = IntArray(n)
        val inTree = BooleanArray(n)
        var count = n
        var concave = 0
        for (i in 0 until n) {
            sgn[i] = spanSign(p, index[prev[i]], index[i], index[next[i]])
            if (sgn[i] != CONVEX) { concave++; inTree[index[i]] = true }
        }
        var head = 0
        val tris = IntArray((n - 2) * 3)
        var tc = 0

        fun calcSign(i: Int) { sgn[i] = spanSign(p, index[prev[i]], index[i], index[next[i]]) }

        fun check(ear: Int, accept: Int): Boolean {
            if (concave == 0) return true
            if (sgn[ear] != accept) return false
            val i0 = index[ear]; val i1 = index[next[ear]]; val i2 = index[prev[ear]]
            val e0x = p[i1 * 2] - p[i0 * 2]; val e0y = p[i1 * 2 + 1] - p[i0 * 2 + 1]
            val e1x = p[i2 * 2] - p[i1 * 2]; val e1y = p[i2 * 2 + 1] - p[i1 * 2 + 1]
            val e2x = p[i0 * 2] - p[i2 * 2]; val e2y = p[i0 * 2 + 1] - p[i2 * 2 + 1]
            val c0 = e0x * p[i0 * 2 + 1] - p[i0 * 2] * e0y
            val c1 = e1x * p[i1 * 2 + 1] - p[i1 * 2] * e1y
            val c2 = e2x * p[i2 * 2 + 1] - p[i2 * 2] * e2y
            for (k in 0 until n) {
                if (!inTree[k] || k == i0 || k == i1 || k == i2) continue
                val x = p[k * 2]; val y = p[k * 2 + 1]
                if ((e1y * x) - (e1x * y) + c1 >= 0f && (e2y * x) - (e2x * y) + c2 >= 0f && (e0y * x) - (e0x * y) + c0 >= 0f) return false
            }
            return true
        }

        fun remove(i: Int) {
            inTree[index[i]] = false
            next[prev[i]] = next[i]
            prev[next[i]] = prev[i]
            if (head == i) head = next[i]
            count--
        }

        fun find(init: Int, reverse: Boolean): Int {
            for (accept in intArrayOf(CONVEX, TANGENTIAL)) {
                var e = init
                for (k in 0 until count) {
                    if (check(e, accept)) return e
                    e = if (reverse) prev[e] else next[e]
                }
            }
            var e = init
            for (k in 0 until count) {
                if (sgn[e] != CONCAVE) return e
                e = next[e]
            }
            return e
        }

        var earInit = head
        var reverse = false
        while (count > 3) {
            val ear = find(earInit, reverse)
            if (sgn[ear] != CONVEX) concave--
            val pp = prev[ear]
            val nn = next[ear]
            tris[tc++] = index[pp]; tris[tc++] = index[ear]; tris[tc++] = index[nn]
            remove(ear)
            if (sgn[pp] != CONVEX) {
                calcSign(pp)
                if (sgn[pp] == CONVEX) { concave--; inTree[index[pp]] = false }
            }
            if (sgn[nn] != CONVEX) {
                calcSign(nn)
                if (sgn[nn] == CONVEX) { concave--; inTree[index[nn]] = false }
            }
            earInit = if (reverse) prev[pp] else next[nn]
            if (sgn[earInit] != CONVEX) {
                earInit = if (reverse) prev[earInit] else next[earInit]
                reverse = !reverse
            }
        }
        if (count == 3) {
            var e = head
            tris[tc++] = index[e]; e = next[e]
            tris[tc++] = index[e]; e = next[e]
            tris[tc++] = index[e]
        }
        return tris.copyOf(tc)
    }
}
