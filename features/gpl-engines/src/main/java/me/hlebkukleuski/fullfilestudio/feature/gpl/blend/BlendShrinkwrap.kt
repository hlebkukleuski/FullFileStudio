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
 * Includes watertight ray-triangle intersection (isect_ray_tri_watertight_v3)
 * based on Sven Woop, Carsten Benthin, and Ingo Wald (JCGT 2013) / Intel Corporation Embree (Apache-2.0).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import kotlin.math.abs
import kotlin.math.sqrt

internal class ShrinkTree(val co: FloatArray, val topo: MeshTopology, private val sharpFaces: BooleanArray?) {
    class Nearest {
        var index = -1
        var distSq = Float.MAX_VALUE
        val co = FloatArray(3)
        val no = FloatArray(3)
    }

    class RayHit {
        var index = -1
        var dist = Float.MAX_VALUE / 2f
        val co = FloatArray(3)
        val no = FloatArray(3)
    }

    val vertexNormals: FloatArray by lazy { topo.vertexNormals(co) }
    val triCorners: IntArray
    val triFace: IntArray
    private val triTree: Bvh
    private val vertTree: Bvh by lazy { Bvh(co.size / 3) { i, b -> for (k in 0 until 3) { b[k] = co[i * 3 + k]; b[k + 3] = co[i * 3 + k] } } }

    init {
        val tris = topo.cornerTris(co)
        triCorners = tris.first
        triFace = tris.second
        triTree = Bvh(triFace.size) { t, b ->
            b[0] = Float.MAX_VALUE; b[1] = Float.MAX_VALUE; b[2] = Float.MAX_VALUE
            b[3] = -Float.MAX_VALUE; b[4] = -Float.MAX_VALUE; b[5] = -Float.MAX_VALUE
            for (j in 0 until 3) {
                val v = topo.corners[triCorners[t * 3 + j]]
                for (k in 0 until 3) { b[k] = minOf(b[k], co[v * 3 + k]); b[k + 3] = maxOf(b[k + 3], co[v * 3 + k]) }
            }
        }
    }

    val hasFaces get() = triFace.isNotEmpty()

    fun triVert(t: Int, j: Int) = topo.corners[triCorners[t * 3 + j]]

    private val faceNormals: FloatArray by lazy {
        val out = FloatArray(topo.faceCount * 3)
        for (f in 0 until topo.faceCount) {
            val s = topo.faceStart[f]
            val m = topo.faceSize[f]
            var nx = 0f; var ny = 0f; var nz = 0f
            if (m == 3) {
                val n = triNormal(topo.corners[s], topo.corners[s + 1], topo.corners[s + 2])
                nx = n[0]; ny = n[1]; nz = n[2]
            } else if (m == 4) {
                val a = topo.corners[s]; val b = topo.corners[s + 1]; val c = topo.corners[s + 2]; val d = topo.corners[s + 3]
                val d1 = FloatArray(3) { co[a * 3 + it] - co[c * 3 + it] }
                val d2 = FloatArray(3) { co[b * 3 + it] - co[d * 3 + it] }
                nx = d1[1] * d2[2] - d1[2] * d2[1]; ny = d1[2] * d2[0] - d1[0] * d2[2]; nz = d1[0] * d2[1] - d1[1] * d2[0]
            } else {
                for (k in 0 until m) {
                    val a = topo.corners[s + k]; val b = topo.corners[s + (k + 1) % m]
                    nx += (co[a * 3 + 1] - co[b * 3 + 1]) * (co[a * 3 + 2] + co[b * 3 + 2])
                    ny += (co[a * 3 + 2] - co[b * 3 + 2]) * (co[a * 3] + co[b * 3])
                    nz += (co[a * 3] - co[b * 3]) * (co[a * 3 + 1] + co[b * 3 + 1])
                }
            }
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1.0e-35f) { out[f * 3] = nx / l; out[f * 3 + 1] = ny / l; out[f * 3 + 2] = nz / l }
        }
        out
    }

    fun triNormal(a: Int, b: Int, c: Int): FloatArray {
        val n1 = FloatArray(3) { co[a * 3 + it] - co[b * 3 + it] }
        val n2 = FloatArray(3) { co[b * 3 + it] - co[c * 3 + it] }
        val n = floatArrayOf(n1[1] * n2[2] - n1[2] * n2[1], n1[2] * n2[0] - n1[0] * n2[2], n1[0] * n2[1] - n1[1] * n2[0])
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        if (l > 1.0e-35f) { n[0] /= l; n[1] /= l; n[2] /= l } else { n[0] = 0f; n[1] = 0f; n[2] = 0f }
        return n
    }

    private fun p(v: Int) = floatArrayOf(co[v * 3], co[v * 3 + 1], co[v * 3 + 2])

    fun nearestSurface(q: FloatArray, n: Nearest) {
        triTree.nearest(q, n) { t ->
            val a = triVert(t, 0); val b = triVert(t, 1); val c = triVert(t, 2)
            val r = ShrinkMath.closestOnTri(q, p(a), p(b), p(c))
            val d = ShrinkMath.distSq(q, r)
            if (d < n.distSq) {
                n.index = t; n.distSq = d; r.copyInto(n.co); triNormal(a, b, c).copyInto(n.no)
            }
        }
    }

    fun nearestVertex(q: FloatArray, n: Nearest) {
        vertTree.nearest(q, n) { v ->
            val d = ShrinkMath.distSq(q, p(v))
            if (d < n.distSq) { n.index = v; n.distSq = d; for (k in 0 until 3) n.co[k] = co[v * 3 + k] }
        }
    }

    fun raycast(origin: FloatArray, dir: FloatArray, hit: RayHit) {
        val pre = ShrinkMath.WatertightRay(dir)
        triTree.ray(origin, dir, hit) { t ->
            val a = triVert(t, 0); val b = triVert(t, 1); val c = triVert(t, 2)
            val d = ShrinkMath.rayTriWatertight(origin, pre, p(a), p(b), p(c))
            if (d >= 0f && d < hit.dist) {
                hit.index = t; hit.dist = d
                for (k in 0 until 3) hit.co[k] = origin[k] + dir[k] * d
                triNormal(a, b, c).copyInto(hit.no)
            }
        }
    }

    fun smoothNormal(t: Int, hitCo: FloatArray, hitNo: FloatArray): FloatArray {
        val f = triFace[t]
        if (sharpFaces == null || sharpFaces.getOrElse(f) { false }) {
            val a = triVert(t, 0); val b = triVert(t, 1); val c = triVert(t, 2)
            val w = ShrinkMath.triWeights(p(a), p(b), p(c), hitCo)
            val vn = vertexNormals
            return FloatArray(3) { k -> vn[a * 3 + k] * w[0] + vn[b * 3 + k] * w[1] + vn[c * 3 + k] * w[2] }
        }
        return floatArrayOf(faceNormals[f * 3], faceNormals[f * 3 + 1], faceNormals[f * 3 + 2])
    }

    private val edgeIndex: HashMap<Long, Int> by lazy {
        val m = HashMap<Long, Int>()
        for (e in 0 until topo.edgeCount) {
            val a = topo.edges[e * 2]; val b = topo.edges[e * 2 + 1]
            m.putIfAbsent((minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong(), e)
        }
        m
    }

    private fun cornerEdge(c: Int): Int {
        val f = cornerFace[c]
        val s = topo.faceStart[f]
        val nx = s + (c - s + 1) % topo.faceSize[f]
        val a = topo.corners[c]; val b = topo.corners[nx]
        return edgeIndex[(minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()] ?: -1
    }

    private val cornerFace: IntArray by lazy {
        val r = IntArray(topo.corners.size)
        for (f in 0 until topo.faceCount) for (k in 0 until topo.faceSize[f]) r[topo.faceStart[f] + k] = f
        r
    }

    fun triRealEdges(t: Int): IntArray = IntArray(3) { j ->
        val c1 = triCorners[t * 3 + j]
        val c2 = triCorners[t * 3 + (j + 1) % 3]
        val f = cornerFace[c1]
        val s = topo.faceStart[f]
        val m = topo.faceSize[f]
        when {
            s + (c1 - s + 1) % m == c2 -> cornerEdge(c1)
            s + (c2 - s + 1) % m == c1 -> cornerEdge(c2)
            else -> -1
        }
    }

    class Boundary(val edgeIsBoundary: BooleanArray, val triHasBoundary: BooleanArray, val vertId: IntArray, val direction: FloatArray, val normalPlane: FloatArray)

    val boundary: Boundary? by lazy {
        val ne = topo.edgeCount
        val mode = IntArray(ne)
        for (c in topo.corners.indices) { val e = cornerEdge(c); if (e >= 0 && mode[e] < 2) mode[e]++ }
        val isB = BooleanArray(ne) { mode[it] == 1 }
        if (isB.none { it }) return@lazy null
        val triHas = BooleanArray(triFace.size) { t -> triRealEdges(t).any { it >= 0 && isB[it] } }
        val nv = co.size / 3
        val vid = IntArray(nv)
        for (e in 0 until ne) if (isB[e]) { vid[topo.edges[e * 2]] = 1; vid[topo.edges[e * 2 + 1]] = 1 }
        var cnt = 0
        for (i in 0 until nv) vid[i] = if (vid[i] != 0) cnt++ else -1
        val dir = FloatArray(cnt * 3)
        val status = IntArray(cnt)
        fun merge(idx: Int, ed: FloatArray, side: Int) {
            val o = idx * 3
            val same = if (status[idx] >= 0) status[idx] == side else dir[o] * ed[0] + dir[o + 1] * ed[1] + dir[o + 2] * ed[2] < 0f
            for (k in 0 until 3) dir[o + k] += if (same) -ed[k] else ed[k]
            status[idx] = if (status[idx] == 0) side else -1
        }
        for (e in 0 until ne) if (isB[e]) {
            val a = topo.edges[e * 2]; val b = topo.edges[e * 2 + 1]
            val d = ShrinkMath.normalized(FloatArray(3) { co[b * 3 + it] - co[a * 3 + it] })
            merge(vid[a], d, 1)
            merge(vid[b], d, 2)
        }
        val np = FloatArray(cnt * 3)
        val vn = vertexNormals
        for (i in 0 until nv) {
            val b = vid[i]
            if (b < 0) continue
            val d = ShrinkMath.normalized(floatArrayOf(dir[b * 3], dir[b * 3 + 1], dir[b * 3 + 2]))
            d.copyInto(dir, b * 3)
            val n = floatArrayOf(vn[i * 3], vn[i * 3 + 1], vn[i * 3 + 2])
            val tmp = RigMath.cross(n, d)
            ShrinkMath.normalized(RigMath.cross(tmp, n)).copyInto(np, b * 3)
        }
        Boundary(isB, triHas, vid, dir, np)
    }

    fun targetProject(q: FloatArray, n: Nearest) {
        triTree.nearest(q, n) { t -> targetProjectTri(t, q, n) }
        if (n.index < 0) nearestSurface(q, n)
    }

    private fun update(n: Nearest, idx: Int, q: FloatArray, hitCo: FloatArray, hitNo: FloatArray) {
        val d = ShrinkMath.distSq(hitCo, q)
        if (d < n.distSq) {
            n.index = idx; n.distSq = d; hitCo.copyInto(n.co); ShrinkMath.normalized(hitNo).copyInto(n.no)
        }
    }

    private fun targetProjectTri(t: Int, q: FloatArray, n: Nearest) {
        val vi = IntArray(3) { triVert(t, it) }
        val vc = Array(3) { p(vi[it]) }
        val raw = ShrinkMath.closestOnTri(q, vc[0], vc[1], vc[2])
        val dsq = ShrinkMath.distSq(q, raw)
        if (dsq >= n.distSq) return
        val vn = vertexNormals
        val no = Array(3) { j -> floatArrayOf(vn[vi[j] * 3], vn[vi[j] * 3 + 1], vn[vi[j] * 3 + 2]) }
        val r = ShrinkMath.solvePointTri(vc, no, q, raw, dsq)
        if (r != null) {
            update(n, t, q, r.first, r.second)
            return
        }
        val b = boundary ?: return
        if (!b.triHasBoundary[t]) return
        for (e in triRealEdges(t)) if (e >= 0 && b.edgeIsBoundary[e]) projectEdge(b, t, q, n, e)
    }

    private fun projectEdge(b: Boundary, idx: Int, q: FloatArray, n: Nearest, e: Int) {
        val v0 = topo.edges[e * 2]; val v1 = topo.edges[e * 2 + 1]
        val c0 = p(v0); val c1 = p(v1)
        val b1 = b.vertId[v0]; val b2 = b.vertId[v1]
        if (b1 < 0 || b2 < 0) return
        val d0 = floatArrayOf(b.normalPlane[b1 * 3], b.normalPlane[b1 * 3 + 1], b.normalPlane[b1 * 3 + 2])
        val d1 = floatArrayOf(b.normalPlane[b2 * 3], b.normalPlane[b2 * 3 + 1], b.normalPlane[b2 * 3 + 2])
        val dir = RigMath.sub(c1, c0)
        if (b.direction[b1 * 3] * dir[0] + b.direction[b1 * 3 + 1] * dir[1] + b.direction[b1 * 3 + 2] * dir[2] < 0f) for (k in 0 until 3) d0[k] = -d0[k]
        if (b.direction[b2 * 3] * dir[0] + b.direction[b2 * 3 + 1] * dir[1] + b.direction[b2 * 3 + 2] * dir[2] < 0f) for (k in 0 until 3) d1[k] = -d1[k]
        val d0v0 = RigMath.dot(d0, c0); val d0v1 = RigMath.dot(d0, c1)
        val d1v0 = RigMath.dot(d1, c0); val d1v1 = RigMath.dot(d1, c1)
        val d0co = RigMath.dot(d0, q)
        val a = d0v1 - d0v0 + d1v0 - d1v1
        if (a == 0f) return
        val bb = 2 * d0v0 - d0v1 - d0co - d1v0 + RigMath.dot(d1, q)
        val c = d0co - d0v0
        val det = bb * bb - 4 * a * c
        if (det < 0) return
        val sdet = sqrt(det)
        val vn = vertexNormals
        var i = if (det > 0) 2 else 0
        while (i >= 0) {
            var x = (-bb + (i - 1).toFloat() * sdet) / (2f * a)
            if (x >= -1e-6f && x <= 1f + 1e-6f) {
                x = x.coerceIn(0f, 1f)
                val hc = RigMath.lerp(c0, c1, x)
                val hn = FloatArray(3) { vn[v0 * 3 + it] + (vn[v1 * 3 + it] - vn[v0 * 3 + it]) * x }
                update(n, idx, q, hc, hn)
            }
            i -= 2
        }
    }
}

internal class Bvh(count: Int, bounds: (Int, FloatArray) -> Unit) {
    private val box = FloatArray(count * 6)
    private val items = IntArray(count) { it }
    private val nodeBox = ArrayList<FloatArray>()
    private val nodeLeft = ArrayList<Int>()
    private val nodeRight = ArrayList<Int>()
    private val nodeStart = ArrayList<Int>()
    private val nodeCount = ArrayList<Int>()

    init {
        val b = FloatArray(6)
        for (i in 0 until count) { bounds(i, b); b.copyInto(box, i * 6) }
        if (count > 0) build(0, count)
    }

    private fun build(s: Int, e: Int): Int {
        val bb = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in s until e) {
            val o = items[i] * 6
            for (k in 0 until 3) { bb[k] = minOf(bb[k], box[o + k]); bb[k + 3] = maxOf(bb[k + 3], box[o + k + 3]) }
        }
        val id = nodeBox.size
        nodeBox.add(bb); nodeLeft.add(-1); nodeRight.add(-1); nodeStart.add(s); nodeCount.add(e - s)
        if (e - s <= 4) return id
        var axis = 0
        for (k in 1 until 3) if (bb[k + 3] - bb[k] > bb[axis + 3] - bb[axis]) axis = k
        val sub = items.copyOfRange(s, e).sortedBy { box[it * 6 + axis] + box[it * 6 + axis + 3] }
        for (i in sub.indices) items[s + i] = sub[i]
        val m = (s + e) / 2
        val l = build(s, m)
        val r = build(m, e)
        nodeLeft[id] = l; nodeRight[id] = r
        return id
    }

    private fun boxDistSq(n: Int, q: FloatArray): Float {
        val b = nodeBox[n]
        var d = 0f
        for (k in 0 until 3) {
            val v = if (q[k] < b[k]) b[k] - q[k] else if (q[k] > b[k + 3]) q[k] - b[k + 3] else 0f
            d += v * v
        }
        return d
    }

    fun nearest(q: FloatArray, n: ShrinkTree.Nearest, visit: (Int) -> Unit) {
        if (nodeBox.isEmpty()) return
        fun rec(node: Int) {
            if (boxDistSq(node, q) >= n.distSq) return
            val l = nodeLeft[node]
            if (l < 0) {
                for (i in nodeStart[node] until nodeStart[node] + nodeCount[node]) visit(items[i])
                return
            }
            val r = nodeRight[node]
            if (boxDistSq(l, q) <= boxDistSq(r, q)) { rec(l); rec(r) } else { rec(r); rec(l) }
        }
        rec(0)
    }

    private fun rayBox(node: Int, o: FloatArray, d: FloatArray, maxT: Float): Float {
        val b = nodeBox[node]
        var t0 = 0f
        var t1 = maxT
        for (k in 0 until 3) {
            if (d[k] == 0f) {
                if (o[k] < b[k] || o[k] > b[k + 3]) return -1f
            } else {
                var a = (b[k] - o[k]) / d[k]
                var c = (b[k + 3] - o[k]) / d[k]
                if (a > c) { val t = a; a = c; c = t }
                t0 = maxOf(t0, a); t1 = minOf(t1, c)
                if (t0 > t1) return -1f
            }
        }
        return t0
    }

    fun ray(o: FloatArray, d: FloatArray, hit: ShrinkTree.RayHit, visit: (Int) -> Unit) {
        if (nodeBox.isEmpty()) return
        fun rec(node: Int) {
            if (rayBox(node, o, d, hit.dist * 1.0001f + 1e-6f) < 0f) return
            val l = nodeLeft[node]
            if (l < 0) {
                for (i in nodeStart[node] until nodeStart[node] + nodeCount[node]) visit(items[i])
                return
            }
            rec(l); rec(nodeRight[node])
        }
        rec(0)
    }
}

internal object ShrinkMath {
    fun distSq(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }

    fun normalized(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l > 1.0e-35f) floatArrayOf(v[0] / l, v[1] / l, v[2] / l) else floatArrayOf(0f, 0f, 0f)
    }

    fun closestOnTri(p: FloatArray, v1: FloatArray, v2: FloatArray, v3: FloatArray): FloatArray {
        val ab = RigMath.sub(v2, v1); val ac = RigMath.sub(v3, v1); val ap = RigMath.sub(p, v1)
        val d1 = RigMath.dot(ab, ap); val d2 = RigMath.dot(ac, ap)
        if (d1 <= 0f && d2 <= 0f) return v1.copyOf()
        val bp = RigMath.sub(p, v2)
        val d3 = RigMath.dot(ab, bp); val d4 = RigMath.dot(ac, bp)
        if (d3 >= 0f && d4 <= d3) return v2.copyOf()
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val s = d1 - d3
            if (s == 0f) return v1.copyOf()
            val v = d1 / s
            return floatArrayOf(v1[0] + ab[0] * v, v1[1] + ab[1] * v, v1[2] + ab[2] * v)
        }
        val cp = RigMath.sub(p, v3)
        val d5 = RigMath.dot(ab, cp); val d6 = RigMath.dot(ac, cp)
        if (d6 >= 0f && d5 <= d6) return v3.copyOf()
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val s = d2 - d6
            if (s == 0f) return v1.copyOf()
            val w = d2 / s
            return floatArrayOf(v1[0] + ac[0] * w, v1[1] + ac[1] * w, v1[2] + ac[2] * w)
        }
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val s = (d4 - d3) + (d5 - d6)
            if (s == 0f) return v2.copyOf()
            val w = (d4 - d3) / s
            return floatArrayOf((v3[0] - v2[0]) * w + v2[0], (v3[1] - v2[1]) * w + v2[1], (v3[2] - v2[2]) * w + v2[2])
        }
        val denom = 1f / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        return floatArrayOf(v1[0] + ab[0] * v + ac[0] * w, v1[1] + ab[1] * v + ac[1] * w, v1[2] + ab[2] * v + ac[2] * w)
    }

    class WatertightRay(d: FloatArray) {
        val kx: Int
        val ky: Int
        val kz: Int
        val sx: Float
        val sy: Float
        val sz: Float

        init {
            val x = abs(d[0]); val y = abs(d[1]); val z = abs(d[2])
            val dz = if (x > y) (if (x > z) 0 else 2) else (if (y > z) 1 else 2)
            var dx = if (dz != 2) dz + 1 else 0
            var dy = if (dx != 2) dx + 1 else 0
            if (d[dz] < 0f) { val t = dx; dx = dy; dy = t }
            val inv = 1.0f / d[dz]
            kx = dx; ky = dy; kz = dz
            sx = d[dx] * inv; sy = d[dy] * inv; sz = inv
        }
    }

    fun rayTriWatertight(o: FloatArray, r: WatertightRay, v0: FloatArray, v1: FloatArray, v2: FloatArray): Float {
        val akx = v0[r.kx] - o[r.kx]; val aky = v0[r.ky] - o[r.ky]; val akz = v0[r.kz] - o[r.kz]
        val bkx = v1[r.kx] - o[r.kx]; val bky = v1[r.ky] - o[r.ky]; val bkz = v1[r.kz] - o[r.kz]
        val ckx = v2[r.kx] - o[r.kx]; val cky = v2[r.ky] - o[r.ky]; val ckz = v2[r.kz] - o[r.kz]
        val ax = akx - r.sx * akz; val ay = aky - r.sy * akz
        val bx = bkx - r.sx * bkz; val by = bky - r.sy * bkz
        val cx = ckx - r.sx * ckz; val cy = cky - r.sy * ckz
        val u = cx * by - cy * bx
        val v = ax * cy - ay * cx
        val w = bx * ay - by * ax
        if ((u < 0f || v < 0f || w < 0f) && (u > 0f || v > 0f || w > 0f)) return Float.MAX_VALUE
        val det = u + v + w
        if (det == 0f || !det.isFinite()) return Float.MAX_VALUE
        val t = (u * akz + v * bkz + w * ckz) * r.sz
        val signT = Float.fromBits(t.toRawBits() xor (det.toRawBits() and Int.MIN_VALUE))
        if (signT < 0f) return Float.MAX_VALUE
        return t * (1.0f / det)
    }

    fun rayTri(o: FloatArray, d: FloatArray, v0: FloatArray, v1: FloatArray, v2: FloatArray): Float {
        val eps = 1.1920929E-7f
        val e1 = RigMath.sub(v1, v0); val e2 = RigMath.sub(v2, v0)
        val p = RigMath.cross(d, e2)
        val a = RigMath.dot(e1, p)
        if (a == 0f) return Float.MAX_VALUE
        val f = 1f / a
        val s = RigMath.sub(o, v0)
        val u = f * RigMath.dot(s, p)
        if (u < -eps || u > 1f + eps) return Float.MAX_VALUE
        val q = RigMath.cross(s, e1)
        val v = f * RigMath.dot(d, q)
        if (v < -eps || u + v > 1f + eps) return Float.MAX_VALUE
        val l = f * RigMath.dot(e2, q)
        return if (l < 0f) Float.MAX_VALUE else l
    }

    fun triWeights(v1: FloatArray, v2: FloatArray, v3: FloatArray, co: FloatArray): FloatArray {
        val n1 = RigMath.sub(v1, v2); val n2 = RigMath.sub(v2, v3)
        val n = normalized(RigMath.cross(n1, n2))
        val xn = abs(n[0]); val yn = abs(n[1]); val zn = abs(n[2])
        val (i, j) = if (zn >= xn && zn >= yn) 0 to 1 else if (yn >= xn && yn >= zn) 0 to 2 else 1 to 2
        fun area(a: FloatArray, b: FloatArray, c: FloatArray) = 0.5f * ((a[i] - b[i]) * (b[j] - c[j]) + (a[j] - b[j]) * (c[i] - b[i]))
        val w = floatArrayOf(area(v2, v3, co), area(v3, v1, co), area(v1, v2, co))
        val tot = w[0] + w[1] + w[2]
        if (tot != 0f) {
            val inv = 1f / tot
            val r = floatArrayOf(w[0] * inv, w[1] * inv, w[2] * inv)
            if (r.all { it.isFinite() }) return r
        }
        return floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)
    }

    private fun manhattan(v: FloatArray) = abs(v[0]) + abs(v[1]) + abs(v[2])

    private fun interp3(a: FloatArray, b: FloatArray, c: FloatArray, w: FloatArray) = FloatArray(3) { a[it] * w[0] + b[it] * w[1] + c[it] * w[2] }

    private fun invert3(m: FloatArray): FloatArray? {
        val a = FloatArray(9)
        a[0] = m[4] * m[8] - m[7] * m[5]; a[1] = -m[1] * m[8] + m[7] * m[2]; a[2] = m[1] * m[5] - m[4] * m[2]
        a[3] = -m[3] * m[8] + m[6] * m[5]; a[4] = m[0] * m[8] - m[6] * m[2]; a[5] = -m[0] * m[5] + m[3] * m[2]
        a[6] = m[3] * m[7] - m[6] * m[4]; a[7] = -m[0] * m[7] + m[6] * m[1]; a[8] = m[0] * m[4] - m[3] * m[1]
        val det = m[0] * a[0] + m[1] * a[3] + m[2] * a[6]
        if (det == 0f) return null
        val inv = 1f / det
        for (k in 0 until 9) a[k] *= inv
        return a
    }

    fun solvePointTri(vc: Array<FloatArray>, vn: Array<FloatArray>, q: FloatArray, hitCo: FloatArray, hitDistSq: Float): Pair<FloatArray, FloatArray>? {
        val dist = sqrt(hitDistSq)
        val mag = dist + manhattan(vc[0]) + manhattan(vc[1]) + manhattan(vc[2])
        val eps = mag * 1.0e-6f
        val x = FloatArray(3)
        triWeights(vc[0], vc[1], vc[2], hitCo).copyInto(x)
        val hn = interp3(vn[0], vn[1], vn[2], x)
        x[2] = if (RigMath.dot(RigMath.sub(q, hitCo), hn) < 0f) -dist else dist
        val n0n2 = RigMath.sub(vn[0], vn[2]); val n1n2 = RigMath.sub(vn[1], vn[2])
        val c0c2 = RigMath.sub(vc[0], vc[2]); val c1c2 = RigMath.sub(vc[1], vc[2])
        clamp(x)
        var coInterp = FloatArray(3)
        var noInterp = FloatArray(3)
        fun delta(xx: FloatArray): FloatArray {
            val w = floatArrayOf(xx[0], xx[1], 1f - xx[0] - xx[1])
            coInterp = interp3(vc[0], vc[1], vc[2], w)
            noInterp = interp3(vn[0], vn[1], vn[2], w)
            return FloatArray(3) { coInterp[it] + noInterp[it] * xx[2] - q[it] }
        }
        fun jacobian(xx: FloatArray): FloatArray {
            val j = FloatArray(9)
            for (k in 0 until 3) {
                j[k] = c0c2[k] + n0n2[k] * xx[2]
                j[3 + k] = c1c2[k] + n1n2[k] * xx[2]
                j[6 + k] = vn[2][k] + n0n2[k] * xx[0] + n1n2[k] * xx[1]
            }
            return j
        }
        val epsSq = eps * eps
        var fd = delta(x)
        var fdv = fd[0] * fd[0] + fd[1] * fd[1] + fd[2] * fd[2]
        var i = 0
        while (i == 0 || (i < 20 && fdv > epsSq)) {
            val jac = invert3(jacobian(x)) ?: return null
            val step = FloatArray(3) { r -> jac[r] * fd[0] + jac[3 + r] * fd[1] + jac[6 + r] * fd[2] }
            val next = FloatArray(3) { x[it] - step[it] }
            if (!correct(x, step, next)) return null
            fd = delta(next)
            var nfdv = fd[0] * fd[0] + fd[1] * fd[1] + fd[2] * fd[2]
            while (nfdv > fdv && nfdv > epsSq) {
                val g0 = sqrt(fdv); val g1 = sqrt(nfdv)
                val g01 = -g0 / sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2])
                val det = 2f * (g1 - g0 - g01)
                var l = if (det == 0f) 0.1f else -g01 / det
                if (l < 0.1f) l = 0.1f
                for (k in 0 until 3) { step[k] *= l; next[k] = x[k] - step[k] }
                fd = delta(next)
                nfdv = fd[0] * fd[0] + fd[1] * fd[1] + fd[2] * fd[2]
            }
            next.copyInto(x)
            fdv = nfdv
            i++
        }
        if (fdv > epsSq) return null
        return coInterp to noInterp
    }

    private fun clamp(x: FloatArray) {
        x[0] = maxOf(x[0], 0f)
        x[1] = maxOf(x[1], 0f)
        if (x[0] + x[1] > 1f) {
            x[0] = x[0] / (x[0] + x[1])
            x[1] = 1f - x[0]
        }
    }

    private fun correct(x: FloatArray, step: FloatArray, next: FloatArray): Boolean {
        val epsilon = 1e-5f
        val dirEps = 0.5f
        val sqrt2 = 1.4142135f
        var fixed = false
        var locked = false
        val sum = x[0] + x[1]
        val sstep = -(step[0] + step[1])
        if (sum + sstep > 1f) {
            val ldist = 1f - sum
            if (ldist < epsilon * sqrt2) {
                val sl = sqrt(step[0] * step[0] + step[1] * step[1])
                if (sl > epsilon && sstep > sl * dirEps * sqrt2) return false
                val add = (sum + sstep - 1f) * 0.5f
                step[0] += add; step[1] += add
                fixed = true; locked = true
            } else {
                val f = ldist / sstep
                for (k in 0 until 3) step[k] *= f
                fixed = true
            }
        }
        for (i in 0 until 2) {
            if (step[i] > x[i]) {
                if (x[i] < epsilon) {
                    val sl = sqrt(step[0] * step[0] + step[1] * step[1])
                    if (sl > epsilon && (locked || step[i] > sl * dirEps)) return false
                    step[i] = x[i]
                    fixed = true
                } else {
                    val f = x[i] / step[i]
                    for (k in 0 until 3) step[k] *= f
                    fixed = true
                }
            }
        }
        if (fixed) {
            for (k in 0 until 3) next[k] = x[k] - step[k]
            clamp(next)
        }
        return true
    }

    private fun snapWithSide(point: FloatArray, hitCo: FloatArray, hitNo: FloatArray, goal: Float, forcesign0: Float, forcesnap: Boolean): FloatArray {
        val delta = RigMath.sub(point, hitCo)
        val dist = RigMath.len(delta)
        var forcesign = forcesign0
        if (dist < 1.1920929E-7f) {
            return if (forcesnap || goal > 0) FloatArray(3) { hitCo[it] + hitNo[it] * goal * forcesign } else hitCo.copyOf()
        }
        val dsign = Math.signum(RigMath.dot(delta, hitNo))
        if (forcesign == 0f) forcesign = dsign
        if (forcesnap || dsign * dist * forcesign < goal) {
            var dd = FloatArray(3) { delta[it] * (dsign / dist) }
            val de = (abs(goal) + manhattan(hitCo)) * 1e-4f
            if (dist < de) {
                val t = dist / de
                dd = FloatArray(3) { hitNo[it] + (dd[it] - hitNo[it]) * t }
            }
            return FloatArray(3) { hitCo[it] + dd[it] * goal * forcesign }
        }
        return point.copyOf()
    }

    fun snap(tree: ShrinkTree, toTarget: FloatArray?, fromTarget: FloatArray?, mode: Int, hitIdx: Int, hitCo: FloatArray, hitNo: FloatArray, goal: Float, point: FloatArray): FloatArray = when (mode) {
        0 -> if (goal != 0f) snapWithSide(point, hitCo, hitNo, goal, 0f, true) else hitCo.copyOf()
        1 -> snapWithSide(point, hitCo, hitNo, goal, -1f, false)
        2 -> snapWithSide(point, hitCo, hitNo, goal, 1f, false)
        3 -> if (goal != 0f) snapWithSide(point, hitCo, hitNo, goal, 1f, true) else hitCo.copyOf()
        4 -> if (goal != 0f) {
            val tc = if (toTarget != null) RigMath.point(toTarget, hitCo) else hitCo
            var n = tree.smoothNormal(hitIdx, tc, hitNo)
            n = if (fromTarget != null) normalized(RigMath.dir(fromTarget, n)) else normalized(n)
            FloatArray(3) { hitCo[it] + n[it] * goal }
        } else hitCo.copyOf()
        else -> hitCo.copyOf()
    }
}

internal object Shrinkwrap {
    fun deform(
        co: FloatArray, weights: FloatArray?, srcNormals: FloatArray?, type: Int, opts: Int, mode: Int, keepDist: Float, projLimit: Float, projAxis: Int,
        target: ShrinkTree, local2target: FloatArray, aux: ShrinkTree?, local2aux: FloatArray?, srcPositions: FloatArray? = null
    ) {
        val target2local = RigMath.inv(local2target)
        val n = co.size / 3
        when (type) {
            2 -> for (i in 0 until n) {
                var w = weights?.get(i) ?: 1f
                if (w == 0f) continue
                val tmp = RigMath.point(local2target, floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2]))
                val nr = ShrinkTree.Nearest()
                target.nearestVertex(tmp, nr)
                if (nr.index < 0) continue
                val d = ShrinkMath.distSq(tmp, nr.co)
                if (d > 1.1920929E-7f) { val dist = sqrt(d); w *= (dist - keepDist) / dist }
                val r = RigMath.point(target2local, nr.co)
                for (k in 0 until 3) co[i * 3 + k] += (r[k] - co[i * 3 + k]) * w
            }
            0, 3 -> {
                if (!target.hasFaces) return
                val nr = ShrinkTree.Nearest()
                for (i in 0 until n) {
                    val w = weights?.get(i) ?: 1f
                    if (w == 0f) continue
                    val tmp = RigMath.point(local2target, floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2]))
                    if (nr.index != -1) {
                        if (type == 3) { nr.index = -1; nr.distSq = Float.MAX_VALUE } else nr.distSq = ShrinkMath.distSq(tmp, nr.co)
                    } else nr.distSq = Float.MAX_VALUE
                    if (type == 3) target.targetProject(tmp, nr) else target.nearestSurface(tmp, nr)
                    if (nr.index == -1) continue
                    val s = ShrinkMath.snap(target, null, null, mode, nr.index, nr.co, nr.no, keepDist, tmp)
                    val r = RigMath.point(target2local, s)
                    for (k in 0 until 3) co[i * 3 + k] += (r[k] - co[i * 3 + k]) * w
                }
            }
            1 -> project(co, weights, srcNormals, opts, mode, keepDist, projLimit, projAxis, target, local2target, target2local, aux, local2aux, srcPositions)
        }
    }

    fun projectNormal(options: Int, vert: FloatArray, dir: FloatArray, toT: FloatArray?, fromT: FloatArray?, tree: ShrinkTree, hit: ShrinkTree.RayHit): Boolean {
        val c = if (toT != null) RigMath.point(toT, vert) else vert
        val d = if (toT != null) ShrinkMath.normalized(RigMath.dir(toT, dir)) else dir
        val tmp = ShrinkTree.RayHit()
        tmp.dist = hit.dist
        tree.raycast(c, d, tmp)
        if (tmp.index == -1) return false
        var no = tmp.no
        if (fromT != null) no = ShrinkMath.normalized(RigMath.dir(fromT, no))
        if (options and 24 != 0) {
            val dot = RigMath.dot(dir, no)
            if ((options and 8 != 0 && dot <= 0f) || (options and 16 != 0 && dot >= 0f)) return false
        }
        val co = if (fromT != null) RigMath.point(fromT, tmp.co) else tmp.co
        hit.index = tmp.index; hit.dist = tmp.dist
        co.copyInto(hit.co); no.copyInto(hit.no)
        return true
    }

    private fun project(
        co: FloatArray, weights: FloatArray?, srcNormals: FloatArray?, opts: Int, mode: Int, keepDist: Float, projLimit: Float, projAxis: Int,
        target: ShrinkTree, l2t: FloatArray, t2l: FloatArray, aux: ShrinkTree?, l2a: FloatArray?, srcPositions: FloatArray?
    ) {
        if (opts and 3 == 0) return
        val axis = FloatArray(3)
        if (projAxis == 0) {
            if (srcNormals == null) return
        } else {
            if (projAxis and 1 != 0) axis[0] = 1f
            if (projAxis and 2 != 0) axis[1] = 1f
            if (projAxis and 4 != 0) axis[2] = 1f
            ShrinkMath.normalized(axis).copyInto(axis)
            if (axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2] < 1.1920929E-7f) return
        }
        if (!target.hasFaces) return
        val auxTree = aux?.takeIf { it.hasFaces && l2a != null }
        val a2l = l2a?.let { RigMath.inv(it) }
        val limSq = projLimit * projLimit
        val n = co.size / 3
        val hit = ShrinkTree.RayHit()
        for (i in 0 until n) {
            val w = weights?.get(i) ?: 1f
            if (w == 0f) continue
            val p = if (projAxis == 0 && srcPositions != null) floatArrayOf(srcPositions[i * 3], srcPositions[i * 3 + 1], srcPositions[i * 3 + 2]) else floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            val orig = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            val dir = if (projAxis == 0) floatArrayOf(srcNormals!![i * 3], srcNormals[i * 3 + 1], srcNormals[i * 3 + 2]) else axis
            hit.index = -1
            hit.dist = Float.MAX_VALUE / 2f
            var isAux = false
            if (opts and 1 != 0) {
                if (auxTree != null && projectNormal(0, p, dir, l2a, a2l, auxTree, hit)) isAux = true
                if (projectNormal(opts, p, dir, l2t, t2l, target, hit)) isAux = false
            }
            if (opts and 2 != 0) {
                val inv = floatArrayOf(-dir[0], -dir[1], -dir[2])
                var o = opts
                if (o and 128 != 0 && o and 24 != 0) o = o xor 24
                if (auxTree != null && projectNormal(0, p, inv, l2a, a2l, auxTree, hit)) isAux = true
                if (projectNormal(o, p, inv, l2t, t2l, target, hit)) isAux = false
            }
            if (limSq != 0f && hit.index != -1 && ShrinkMath.distSq(hit.co, orig) > limSq) hit.index = -1
            if (hit.index == -1) continue
            val r = if (isAux) ShrinkMath.snap(auxTree!!, l2a, a2l, mode, hit.index, hit.co, hit.no, keepDist, p)
            else ShrinkMath.snap(target, l2t, t2l, mode, hit.index, hit.co, hit.no, keepDist, p)
            for (k in 0 until 3) co[i * 3 + k] += (r[k] - co[i * 3 + k]) * w
        }
    }
}
