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

import kotlin.math.abs
import kotlin.math.sqrt

internal class BlendHeap {
    class Node(var value: Float, var ptr: Int) { var index = 0 }

    private val tree = ArrayList<Node>()

    val isEmpty get() = tree.isEmpty()

    fun topValue(): Float = tree[0].value

    fun topNode(): Node = tree[0]

    private fun less(a: Node, b: Node) = a.value < b.value

    private fun swap(i: Int, j: Int) {
        val pi = tree[i]; val pj = tree[j]
        pi.index = j; tree[j] = pi
        pj.index = i; tree[i] = pj
    }

    private fun down(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1
            val r = 2 * i + 2
            var smallest = i
            if (l < tree.size && less(tree[l], tree[smallest])) smallest = l
            if (r < tree.size && less(tree[r], tree[smallest])) smallest = r
            if (smallest == i) break
            swap(i, smallest)
            i = smallest
        }
    }

    private fun up(start: Int) {
        var i = start
        while (i > 0) {
            val p = (i - 1) / 2
            if (less(tree[p], tree[i])) break
            swap(p, i)
            i = p
        }
    }

    fun insert(value: Float, ptr: Int): Node {
        val n = Node(value, ptr)
        n.index = tree.size
        tree.add(n)
        up(n.index)
        return n
    }

    fun popMin(): Int {
        val ptr = tree[0].ptr
        val last = tree.size - 1
        if (last > 0) {
            swap(0, last)
            tree.removeAt(last)
            down(0)
        } else tree.removeAt(0)
        return ptr
    }

    fun remove(node: Node) {
        var i = node.index
        while (i > 0) { val p = (i - 1) / 2; swap(p, i); i = p }
        popMin()
    }

    fun update(node: Node, value: Float, ptr: Int) {
        node.ptr = ptr
        if (value < node.value) { node.value = value; up(node.index) }
        else if (value > node.value) { node.value = value; down(node.index) }
    }
}

internal object BlendTriangulate {
    private fun cross2(a: FloatArray, ai: Int, b: FloatArray, bi: Int, c: FloatArray, ci: Int): Float =
        (a[ai] - b[bi]) * (b[bi + 1] - c[ci + 1]) + (a[ai + 1] - b[bi + 1]) * (c[ci] - b[bi])

    private fun len2(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Float { val x = a[ai] - b[bi]; val y = a[ai + 1] - b[bi + 1]; return sqrt(x * x + y * y) }

    fun quadRotateCalc(v: FloatArray, i1: Int, i2: Int, i3: Int, i4: Int, lockDegenerate: Boolean, area: FloatArray?): Float {
        val eps = 1e-12f
        val a234 = cross2(v, i2, v, i3, v, i4)
        val a241 = cross2(v, i2, v, i4, v, i1)
        val a123 = cross2(v, i1, v, i2, v, i3)
        val a134 = cross2(v, i1, v, i3, v, i4)
        if (area != null) area[0] = (abs(a234) + abs(a241) + abs(a123) + abs(a134)) / 8.0f
        if ((a123 >= 0f) != (a134 >= 0f)) return Float.MAX_VALUE
        if (abs(a123) <= eps || abs(a134) <= eps) return Float.MAX_VALUE
        if ((a234 >= 0f) != (a241 >= 0f)) return if (lockDegenerate) Float.MAX_VALUE else -Float.MAX_VALUE
        if (abs(a234) <= eps || abs(a241) <= eps) return -Float.MAX_VALUE
        val l12 = len2(v, i1, v, i2); val l23 = len2(v, i2, v, i3); val l34 = len2(v, i3, v, i4)
        val l41 = len2(v, i4, v, i1); val l13 = len2(v, i1, v, i3); val l24 = len2(v, i2, v, i4)
        val fac24 = abs(a234) / (l23 + l34 + l24) + abs(a241) / (l41 + l12 + l24)
        val fac13 = abs(a123) / (l12 + l23 + l13) + abs(a134) / (l34 + l41 + l13)
        return fac24 - fac13
    }

    private fun crossTri3(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val n1 = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        val n2 = floatArrayOf(b[0] - c[0], b[1] - c[1], b[2] - c[2])
        return floatArrayOf(n1[1] * n2[2] - n1[2] * n2[1], n1[2] * n2[0] - n1[0] * n2[2], n1[0] * n2[1] - n1[1] * n2[0])
    }

    private fun axisDominant(n: FloatArray): Array<FloatArray> {
        val b1 = FloatArray(3)
        val b2 = FloatArray(3)
        val f = n[0] * n[0] + n[1] * n[1]
        if (f > 1.1920929E-7f) {
            val d = 1f / sqrt(f)
            b1[0] = n[1] * d; b1[1] = -n[0] * d; b1[2] = 0f
            b2[0] = -n[2] * b1[1]; b2[1] = n[2] * b1[0]; b2[2] = n[0] * b1[1] - n[1] * b1[0]
        } else {
            b1[0] = if (n[2] < 0f) -1f else 1f
            b2[1] = 1f
        }
        return arrayOf(b1, b2)
    }

    private fun signumEx(a: Float, eps: Float) = if (a > eps) 1 else if (a < -eps) -1 else 0

    fun rotateBeautyArea(v1: FloatArray, v2: FloatArray, v3: FloatArray, v4: FloatArray, lockDegenerate: Boolean): Float {
        val noA = crossTri3(v2, v3, v4)
        val noB = crossTri3(v2, v4, v1)
        val no = floatArrayOf(noA[0] + noB[0], noA[1] + noB[1], noA[2] + noB[2])
        val d = no[0] * no[0] + no[1] * no[1] + no[2] * no[2]
        if (!(d > 1.0e-35f)) return Float.MAX_VALUE
        val scale = sqrt(d)
        val inv = 1.0f / scale
        no[0] *= inv; no[1] *= inv; no[2] *= inv
        val (b1, b2) = axisDominant(no)
        val p = FloatArray(8)
        for ((i, v) in listOf(v1, v2, v3, v4).withIndex()) {
            p[i * 2] = b1[0] * v[0] + b1[1] * v[1] + b1[2] * v[2]
            p[i * 2 + 1] = b2[0] * v[0] + b2[1] * v[1] + b2[2] * v[2]
        }
        if (signumEx(cross2(p, 2, p, 4, p, 6) / scale, 1e-5f) + signumEx(cross2(p, 2, p, 6, p, 0) / scale, 1e-5f) == 0) return Float.MAX_VALUE
        return quadRotateCalc(p, 0, 2, 4, 6, lockDegenerate, null)
    }

    private fun quadFlip(v1: FloatArray, v2: FloatArray, v3: FloatArray, v4: FloatArray): Int {
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun cr(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dt(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val d12 = sub(v1, v2); val d23 = sub(v2, v3); val d34 = sub(v3, v4); val d41 = sub(v4, v1)
        var r = 0
        if (dt(cr(d12, d23), cr(d34, d41)) < 0f) r = r or 1
        if (dt(cr(d23, d34), cr(d41, d12)) < 0f) r = r or 2
        return r
    }

    fun beautify(coords: FloatArray, n: Int, tris: IntArray) {
        val last = n - 1
        val trisLen = n - 2
        val halfLen = 3 * trisLen
        val hv = IntArray(halfLen); val hNext = IntArray(halfLen); val hRadial = IntArray(halfLen) { -1 }; val hBase = IntArray(halfLen) { -1 }
        val order = ArrayList<IntArray>()
        for (i in 0 until trisLen) {
            var jPrev = 2
            for (jCurr in 0 until 3) {
                val ep = i * 3 + jPrev
                hv[ep] = tris[i * 3 + jPrev]
                hNext[ep] = i * 3 + jCurr
                var a = tris[i * 3 + jPrev]; var b = tris[i * 3 + jCurr]
                if (a > b) { val t = a; a = b; b = t }
                if (!(a + 1 == b || (a == 0 && b == last))) order.add(intArrayOf(a, b, ep))
                jPrev = jCurr
            }
        }
        order.sortWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }))
        var base = 0
        var i = 0
        while (i + 1 < order.size) {
            val a = order[i++]; val b = order[i++]
            hRadial[a[2]] = b[2]; hRadial[b[2]] = a[2]
            hBase[a[2]] = base; hBase[b[2]] = base
            base++
        }
        val table = arrayOfNulls<BlendHeap.Node>(maxOf(base, 1))
        val heap = BlendHeap()
        fun calc(e: Int, area: FloatArray?): Float {
            val eb = hRadial[e]
            val v1 = hv[hNext[hNext[e]]]
            val v2 = hv[e]
            val v3 = hv[hNext[hNext[eb]]]
            val v4 = hv[eb]
            return quadRotateCalc(coords, v1 * 2, v2 * 2, v3 * 2, v4 * 2, false, area)
        }
        for (e in 0 until halfLen) {
            if (hRadial[e] in 0 until e) {
                val cost = calc(e, null)
                table[hBase[e]] = if (cost < 0f) heap.insert(cost, e) else null
            }
        }
        val area = FloatArray(1)
        fun updateSingle(e: Int) {
            val idx = hBase[e]
            val cost = calc(e, area)
            if (cost < -1e-6f * maxOf(area[0], 1.0f)) {
                val node = table[idx]
                if (node == null) table[idx] = heap.insert(cost, e) else heap.update(node, cost, e)
            } else {
                val node = table[idx]
                if (node != null) { heap.remove(node); table[idx] = null }
            }
        }
        while (!heap.isEmpty) {
            val e = heap.popMin()
            table[hBase[e]] = null
            val ed = IntArray(6)
            ed[0] = e; ed[1] = hNext[ed[0]]; ed[2] = hNext[ed[1]]
            ed[3] = hRadial[e]; ed[4] = hNext[ed[3]]; ed[5] = hNext[ed[4]]
            hNext[ed[0]] = ed[2]; hNext[ed[1]] = ed[3]; hNext[ed[2]] = ed[4]
            hNext[ed[3]] = ed[5]; hNext[ed[4]] = ed[0]; hNext[ed[5]] = ed[1]
            hv[ed[0]] = hv[ed[5]]
            hv[ed[3]] = hv[ed[2]]
            val arr = intArrayOf(hNext[e], hNext[hNext[e]], hNext[hRadial[e]], hNext[hNext[hRadial[e]]])
            for (x in arr) if (hBase[x] >= 0) updateSingle(x)
        }
        val used = BooleanArray(halfLen)
        var t = 0
        for (k in 0 until halfLen) {
            if (used[k]) continue
            var e = k
            tris[t * 3] = hv[e]; used[e] = true
            e = hNext[e]; tris[t * 3 + 1] = hv[e]; used[e] = true
            e = hNext[e]; tris[t * 3 + 2] = hv[e]; used[e] = true
            t++
        }
    }

    fun faceTris(co: FloatArray, verts: IntArray, quadMethod: Int, ngonMethod: Int): IntArray {
        val n = verts.size
        fun p(i: Int) = floatArrayOf(co[verts[i] * 3], co[verts[i] * 3 + 1], co[verts[i] * 3 + 2])
        if (n == 4) {
            var l1: Int
            var l2: Int
            when (quadMethod) {
                1 -> { l1 = 0; l2 = 2 }
                2 -> { l1 = 1; l2 = 3 }
                else -> {
                    l1 = 1; l2 = 2
                    val l3 = 3; val l4 = 0
                    val split24 = when (quadMethod) {
                        3 -> { val d1 = sq(p(l4), p(l2)); val d2 = sq(p(l1), p(l3)); (d2 - d1) > 0f }
                        4 -> { val d1 = sq(p(l4), p(l2)); val d2 = sq(p(l1), p(l3)); (d2 - d1) < 0f }
                        else -> {
                            val flip = quadFlip(p(l1), p(l2), p(l3), p(l4))
                            if (flip and 1 != 0) true else if (flip and 2 != 0) false
                            else rotateBeautyArea(p(l1), p(l2), p(l3), p(l4), false) > 0f
                        }
                    }
                    if (split24) l1 = l4 else l2 = l3
                }
            }
            return intArrayOf(l1, (l1 + 1) % 4, l2, l1, l2, (l2 + 1) % 4)
        }
        val tris = BlendPolyfill.triangulate(co, verts)
        if (ngonMethod == 0) beautify(BlendPolyfill.lastProjected, n, tris)
        return tris
    }

    private fun sq(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }

    fun apply(d: BlendMeshData, quadMethod: Int, ngonMethod: Int, minVerts: Int): BlendMeshData {
        val fStart = ArrayList<Int>(); val fSize = ArrayList<Int>(); val corners = ArrayList<Int>()
        val faceMat = d.faceMat?.let { ArrayList<Int>() }
        val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        val cs = d.customShort?.takeIf { it.size == d.corners.size * 2 }?.let { ArrayList<Short>() }
        fun corner(c: Int) {
            corners.add(d.corners[c])
            d.uv?.let { u -> uv!!.add(u[c * 2]); uv.add(u[c * 2 + 1]) }
            d.cornerColor?.let { cc!!.add(it[c]) }
            if (cs != null) { cs.add(d.customShort!![c * 2]); cs.add(d.customShort!![c * 2 + 1]) }
        }
        for (f in 0 until d.nF) {
            val s = d.fStart[f]
            val m = d.fSize[f]
            if (m <= 3 || m < minVerts) {
                fStart.add(corners.size); fSize.add(m)
                for (k in 0 until m) corner(s + k)
                faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
                continue
            }
            val tris = faceTris(d.co, IntArray(m) { d.corners[s + it] }, quadMethod, ngonMethod)
            for (t in 0 until tris.size / 3) {
                fStart.add(corners.size); fSize.add(3)
                for (k in 0 until 3) corner(s + tris[t * 3 + k])
                faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
            }
        }
        val r = BlendMeshData(d.co, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(), faceMat?.toIntArray(), faceSmooth?.toBooleanArray(), uv?.toFloatArray(), cc?.toIntArray(), d.pointColor, d.edgeCrease, d.vertCrease, d.looseEdges, d.dverts, d.groups)
        r.carryNormals(d, d.sharpEdges)
        if (cs != null) r.customShort = ShortArray(cs.size) { cs[it] }
        return r
    }
}
