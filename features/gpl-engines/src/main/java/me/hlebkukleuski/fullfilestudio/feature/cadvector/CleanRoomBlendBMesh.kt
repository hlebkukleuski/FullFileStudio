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

import kotlin.math.sqrt

internal class BMesh(
    val hasUv: Boolean, val hasCornerColor: Boolean, val hasPointColor: Boolean, val hasDvert: Boolean,
    val hasVertCrease: Boolean, val hasEdgeCrease: Boolean
) {
    class Vert(val id: Int) {
        val co = FloatArray(3)
        val no = FloatArray(3)
        var e: Edge? = null
        var index = -1
        var tag = false
        var api = 0
        var color = 0
        var crease = 0f
        var dvert: List<Pair<Int, Float>> = emptyList()
        var alive = true
    }

    class Edge(val id: Int, var v1: Vert, var v2: Vert) {
        var d1Next: Edge? = null
        var d1Prev: Edge? = null
        var d2Next: Edge? = null
        var d2Prev: Edge? = null
        var l: Loop? = null
        var index = -1
        var tag = false
        var api = 0
        var smooth = true
        var seam = false
        var crease = 0f
        var alive = true
    }

    class Loop(var v: Vert, var e: Edge?, var f: Face) {
        var next: Loop? = null
        var prev: Loop? = null
        var rNext: Loop? = null
        var rPrev: Loop? = null
        var index = -1
        var u = 0f
        var w = 0f
        var color = 0
    }

    class Face(val id: Int) {
        var lFirst: Loop? = null
        var len = 0
        var mat = 0
        var strength = 0
        var smooth = false
        val no = FloatArray(3)
        var index = -1
        var tag = false
        var api = 0
        var alive = true
    }

    private class Pool<T : Any> {
        val slots = ArrayList<T?>()
        private val free = ArrayList<Int>()
        val slotOf = HashMap<T, Int>()
        var count = 0

        fun add(x: T) {
            val s = if (free.isEmpty()) { slots.add(x); slots.size - 1 } else { val s = free.removeAt(free.size - 1); slots[s] = x; s }
            slotOf[x] = s
            count++
        }

        fun remove(x: T) {
            val s = slotOf.remove(x) ?: return
            slots[s] = null
            free.add(s)
            count--
        }

        fun list(): List<T> = slots.filterNotNull()
    }

    private val vpool = Pool<Vert>()
    private val epool = Pool<Edge>()
    private val fpool = Pool<Face>()
    private var nextId = 0

    val totvert get() = vpool.count
    val totedge get() = epool.count
    val totface get() = fpool.count

    fun verts(): List<Vert> = vpool.list()
    fun edges(): List<Edge> = epool.list()
    fun faces(): List<Face> = fpool.list()
    fun faceSlotCount() = fpool.slots.size
    fun faceAt(i: Int): Face? = fpool.slots[i]

    companion object {
        const val FLAG_JF = 1
        const val FLAG_MF = 2
        const val FLAG_MV = 4

        fun diskNext(e: Edge, v: Vert): Edge? = if (e.v1 === v) e.d1Next else e.d2Next
        fun diskPrev(e: Edge, v: Vert): Edge? = if (e.v1 === v) e.d1Prev else e.d2Prev
        private fun setDiskNext(e: Edge, v: Vert, x: Edge?) { if (e.v1 === v) e.d1Next = x else e.d2Next = x }
        private fun setDiskPrev(e: Edge, v: Vert, x: Edge?) { if (e.v1 === v) e.d1Prev = x else e.d2Prev = x }

        fun otherVert(e: Edge, v: Vert): Vert? = if (e.v1 === v) e.v2 else if (e.v2 === v) e.v1 else null
        fun vertInEdge(e: Edge, v: Vert) = e.v1 === v || e.v2 === v
        fun isBoundary(e: Edge): Boolean { val l = e.l; return l != null && l.rNext === l }
        fun isManifold(e: Edge): Boolean { val l = e.l; return l != null && l.rNext !== l && l.rNext!!.rNext === l }
        fun isWire(e: Edge) = e.l == null
        fun loopIsManifold(l: Loop) = l !== l.rNext && l === l.rNext!!.rNext

        fun loopsOfFace(f: Face): List<Loop> {
            val out = ArrayList<Loop>(f.len)
            val first = f.lFirst ?: return out
            var l: Loop = first
            do { out.add(l); l = l.next!! } while (l !== first)
            return out
        }

        fun edgesOfVert(v: Vert): List<Edge> {
            val out = ArrayList<Edge>()
            val first = v.e ?: return out
            var e = first
            do { out.add(e); e = diskNext(e, v)!! } while (e !== first)
            return out
        }

        fun edgeOtherLoop(e: Edge, l: Loop): Loop {
            var o = if (l.e === e) l else l.prev!!
            o = o.rNext!!
            if (o.v === l.v) return o
            if (o.next!!.v === l.v) return o.next!!
            return o
        }

        fun vertStepFanLoop(l: Loop, eStep: Array<Edge?>): Loop? {
            val ePrev = eStep[0]
            val eNext = if (l.e === ePrev) l.prev!!.e!! else if (l.prev!!.e === ePrev) l.e!! else return null
            if (isManifold(eNext)) { eStep[0] = eNext; return edgeOtherLoop(eNext, l) }
            return null
        }

        fun edgeLoopPair(e: Edge): Pair<Loop, Loop>? {
            val la = e.l ?: return null
            val lb = la.rNext ?: return null
            if (la !== lb && lb.rNext === la) return la to lb
            return null
        }

        private fun radialFacevertCount(l: Loop, v: Vert): Int { var c = 0; var it = l; do { if (it.v === v) c++; it = it.rNext!! } while (it !== l); return c }
        private fun radialFacevertCheck(l: Loop, v: Vert): Boolean { var it = l; do { if (it.v === v) return true; it = it.rNext!! } while (it !== l); return false }
        private fun radialFaceloopFindFirst(l: Loop, v: Vert): Loop? { var it = l; do { if (it.v === v) return it; it = it.rNext!! } while (it !== l); return null }
        private fun radialFaceloopFindNext(l: Loop, v: Vert): Loop { var it = l.rNext!!; do { if (it.v === v) return it; it = it.rNext!! } while (it !== l); return l }

        private fun diskFacevertCount(v: Vert): Int {
            val first = v.e ?: return 0
            var c = 0
            var e = first
            do { val l = e.l; if (l != null) c += radialFacevertCount(l, v); e = diskNext(e, v)!! } while (e !== first)
            return c
        }

        private fun diskFaceloopFindFirst(e0: Edge, v: Vert): Loop? {
            var e = e0
            do { val l = e.l; if (l != null) return if (l.v === v) l else l.next; e = diskNext(e, v)!! } while (e !== e0)
            return null
        }

        private fun diskFaceedgeFindNext(e0: Edge, v: Vert): Edge {
            var e = diskNext(e0, v)!!
            do { val l = e.l; if (l != null && radialFacevertCheck(l, v)) return e; e = diskNext(e, v)!! } while (e !== e0)
            return e0
        }

        fun loopsOfVert(v: Vert): List<Loop> {
            val out = ArrayList<Loop>()
            var count = diskFacevertCount(v)
            if (count == 0) return out
            var lFirst = diskFaceloopFindFirst(v.e!!, v)!!
            var eNext = lFirst.e!!
            var lNext: Loop? = lFirst
            while (lNext != null) {
                val cur = lNext
                out.add(cur)
                if (count > 0) {
                    count--
                    lNext = radialFaceloopFindNext(lNext, v)
                    if (lNext === lFirst) {
                        eNext = diskFaceedgeFindNext(eNext, v)
                        lFirst = radialFaceloopFindFirst(eNext.l!!, v)!!
                        lNext = lFirst
                    }
                }
                if (count == 0) lNext = null
            }
            return out
        }

        fun faceCalcNormal(f: Face, out: FloatArray) {
            val ls = loopsOfFace(f)
            when (f.len) {
                4 -> {
                    val a = ls[0].v.co; val b = ls[1].v.co; val c = ls[2].v.co; val d = ls[3].v.co
                    val n1 = floatArrayOf(a[0] - c[0], a[1] - c[1], a[2] - c[2])
                    val n2 = floatArrayOf(b[0] - d[0], b[1] - d[1], b[2] - d[2])
                    out[0] = n1[1] * n2[2] - n1[2] * n2[1]; out[1] = n1[2] * n2[0] - n1[0] * n2[2]; out[2] = n1[0] * n2[1] - n1[1] * n2[0]
                    normalizeV3(out)
                }
                3 -> {
                    val a = ls[0].v.co; val b = ls[1].v.co; val c = ls[2].v.co
                    val n1 = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
                    val n2 = floatArrayOf(b[0] - c[0], b[1] - c[1], b[2] - c[2])
                    out[0] = n1[1] * n2[2] - n1[2] * n2[1]; out[1] = n1[2] * n2[0] - n1[0] * n2[2]; out[2] = n1[0] * n2[1] - n1[1] * n2[0]
                    normalizeV3(out)
                }
                else -> {
                    out[0] = 0f; out[1] = 0f; out[2] = 0f
                    var prev = ls[ls.size - 1].v.co
                    for (l in ls) {
                        val cur = l.v.co
                        out[0] += (prev[1] - cur[1]) * (prev[2] + cur[2])
                        out[1] += (prev[2] - cur[2]) * (prev[0] + cur[0])
                        out[2] += (prev[0] - cur[0]) * (prev[1] + cur[1])
                        prev = cur
                    }
                    normalizeV3(out)
                }
            }
        }

        fun faceNormals(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray): FloatArray {
            val out = FloatArray(fStart.size * 3)
            val n = FloatArray(3)
            for (f in fStart.indices) {
                val s = fStart[f]
                val m = fSize[f]
                fun p(k: Int, d: Int) = co[corners[s + k] * 3 + d]
                when (m) {
                    4 -> {
                        val a0 = p(0, 0) - p(2, 0); val a1 = p(0, 1) - p(2, 1); val a2 = p(0, 2) - p(2, 2)
                        val b0 = p(1, 0) - p(3, 0); val b1 = p(1, 1) - p(3, 1); val b2 = p(1, 2) - p(3, 2)
                        n[0] = a1 * b2 - a2 * b1; n[1] = a2 * b0 - a0 * b2; n[2] = a0 * b1 - a1 * b0
                    }
                    3 -> {
                        val a0 = p(0, 0) - p(1, 0); val a1 = p(0, 1) - p(1, 1); val a2 = p(0, 2) - p(1, 2)
                        val b0 = p(1, 0) - p(2, 0); val b1 = p(1, 1) - p(2, 1); val b2 = p(1, 2) - p(2, 2)
                        n[0] = a1 * b2 - a2 * b1; n[1] = a2 * b0 - a0 * b2; n[2] = a0 * b1 - a1 * b0
                    }
                    else -> {
                        n[0] = 0f; n[1] = 0f; n[2] = 0f
                        var pr = m - 1
                        for (k in 0 until m) {
                            n[0] += (p(pr, 1) - p(k, 1)) * (p(pr, 2) + p(k, 2))
                            n[1] += (p(pr, 2) - p(k, 2)) * (p(pr, 0) + p(k, 0))
                            n[2] += (p(pr, 0) - p(k, 0)) * (p(pr, 1) + p(k, 1))
                            pr = k
                        }
                    }
                }
                normalizeV3(n)
                n.copyInto(out, f * 3)
            }
            return out
        }

        fun normalizeV3(v: FloatArray): Float {
            val d = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
            if (d > 1.0e-35f) {
                val l = sqrt(d)
                val inv = 1.0f / l
                v[0] *= inv; v[1] *= inv; v[2] *= inv
                return l
            }
            v[0] = 0f; v[1] = 0f; v[2] = 0f
            return 0f
        }

        fun fromMesh(d: BlendMeshData, calcVertNormals: Boolean, calcFaceNormals: Boolean): BMesh {
            val bm = BMesh(d.uv != null, d.cornerColor != null, d.pointColor != null, d.dverts != null, d.vertCrease != null, d.edgeCrease.isNotEmpty())
            bm.hasStrength = d.faceStrength != null
            val vn = if (calcVertNormals) MeshTopology(d.corners, d.fStart, d.fSize, IntArray(0)).vertexNormals(d.co) else null
            val fn = if (calcFaceNormals) faceNormals(d.co, d.corners, d.fStart, d.fSize) else null
            val vt = Array(d.nV) { i ->
                val v = bm.vertCreate(floatArrayOf(d.co[i * 3], d.co[i * 3 + 1], d.co[i * 3 + 2]), null)
                v.index = i
                if (vn != null) { v.no[0] = vn[i * 3]; v.no[1] = vn[i * 3 + 1]; v.no[2] = vn[i * 3 + 2] }
                d.pointColor?.let { v.color = it[i] }
                d.vertCrease?.let { v.crease = it[i] }
                d.dverts?.let { v.dvert = it[i] }
                v
            }
            val faceEdges = LinkedHashSet<Long>()
            for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) faceEdges.add(BlendMeshData.key(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]))
            val order = ArrayList<IntArray>()
            val fe = d.fileEdges
            val seen = HashSet<Long>()
            if (fe != null) {
                for (e in 0 until fe.size / 2) { val a = fe[e * 2]; val b = fe[e * 2 + 1]; if (a != b && a in 0 until d.nV && b in 0 until d.nV && seen.add(BlendMeshData.key(a, b))) order.add(intArrayOf(a, b)) }
            }
            if (fe == null || !faceEdges.all { it in seen }) {
                order.clear(); seen.clear()
                for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
                    val a = d.corners[d.fStart[f] + k]; val b = d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]
                    if (a != b && seen.add(BlendMeshData.key(a, b))) order.add(intArrayOf(minOf(a, b), maxOf(a, b)))
                }
                for (i in 0 until d.looseEdges.size / 2) {
                    val a = d.looseEdges[i * 2]; val b = d.looseEdges[i * 2 + 1]
                    if (a != b && seen.add(BlendMeshData.key(a, b))) order.add(intArrayOf(a, b))
                }
            }
            val emap = HashMap<Long, Edge>()
            for ((i, p) in order.withIndex()) {
                val e = bm.edgeCreate(vt[p[0]], vt[p[1]], null, false)
                e.index = i
                val k = BlendMeshData.key(p[0], p[1])
                e.smooth = k !in d.sharpEdges
                e.seam = k in d.seamEdges
                e.crease = d.edgeCrease[k] ?: 0f
                emap[k] = e
            }
            for (f in 0 until d.nF) {
                val n = d.fSize[f]
                val s = d.fStart[f]
                val verts = Array(n) { vt[d.corners[s + it]] }
                val edges = Array(n) { emap[BlendMeshData.key(d.corners[s + it], d.corners[s + (it + 1) % n])]!! }
                val face = bm.faceCreate(verts, edges, null)
                face.index = f
                face.mat = d.faceMat?.getOrNull(f) ?: 0
                face.strength = d.faceStrength?.getOrNull(f) ?: 0
                face.smooth = d.faceSmooth?.getOrNull(f) ?: false
                if (fn != null) { face.no[0] = fn[f * 3]; face.no[1] = fn[f * 3 + 1]; face.no[2] = fn[f * 3 + 2] }
                var l = face.lFirst!!
                for (k in 0 until n) {
                    val c = s + k
                    d.uv?.let { l.u = it[c * 2]; l.w = it[c * 2 + 1] }
                    d.cornerColor?.let { l.color = it[c] }
                    l = l.next!!
                }
            }
            return bm
        }
    }

    fun vertCreate(co: FloatArray?, example: Vert?): Vert {
        val v = Vert(nextId++)
        if (co != null) co.copyInto(v.co)
        vpool.add(v)
        if (example != null) {
            v.color = example.color; v.crease = example.crease; v.dvert = example.dvert
            example.no.copyInto(v.no)
        }
        return v
    }

    private fun diskAppend(e: Edge, v: Vert) {
        val first = v.e
        if (first == null) {
            v.e = e
            setDiskNext(e, v, e); setDiskPrev(e, v, e)
        } else {
            val prev = diskPrev(first, v)
            setDiskNext(e, v, first)
            setDiskPrev(e, v, prev)
            setDiskPrev(first, v, e)
            if (prev != null) setDiskNext(prev, v, e)
        }
    }

    private fun diskRemove(e: Edge, v: Vert) {
        val next = diskNext(e, v)
        val prev = diskPrev(e, v)
        if (prev != null) setDiskNext(prev, v, next)
        if (next != null) setDiskPrev(next, v, prev)
        if (v.e === e) v.e = if (e !== next) next else null
        setDiskNext(e, v, null); setDiskPrev(e, v, null)
    }

    private fun radialAppend(e: Edge, l: Loop) {
        val head = e.l
        if (head == null) {
            e.l = l
            l.rNext = l; l.rPrev = l
        } else {
            l.rPrev = head
            l.rNext = head.rNext
            head.rNext!!.rPrev = l
            head.rNext = l
            e.l = l
        }
        l.e = e
    }

    private fun radialRemove(e: Edge, l: Loop) {
        if (l.rNext !== l) {
            if (l === e.l) e.l = l.rNext
            l.rNext!!.rPrev = l.rPrev
            l.rPrev!!.rNext = l.rNext
        } else if (l === e.l) e.l = null
        l.rNext = null; l.rPrev = null; l.e = null
    }

    fun edgeExists(a: Vert, b: Vert): Edge? {
        val first = a.e ?: return null
        var e = first
        do { if (vertInEdge(e, b)) return e; e = diskNext(e, a)!! } while (e !== first)
        return null
    }

    fun edgeCreate(v1: Vert, v2: Vert, example: Edge?, noDouble: Boolean): Edge {
        if (noDouble) edgeExists(v1, v2)?.let { return it }
        val e = Edge(nextId++, v1, v2)
        epool.add(e)
        diskAppend(e, v1)
        diskAppend(e, v2)
        if (example != null) { e.smooth = example.smooth; e.seam = example.seam; e.crease = example.crease }
        return e
    }

    fun faceCreate(verts: Array<Vert>, edges: Array<Edge>, example: Face?): Face {
        val f = Face(nextId++)
        fpool.add(f)
        val start = Loop(verts[0], null, f)
        radialAppend(edges[0], start)
        f.lFirst = start
        var last = start
        for (i in 1 until verts.size) {
            val l = Loop(verts[i], null, f)
            radialAppend(edges[i], l)
            l.prev = last
            last.next = l
            last = l
        }
        start.prev = last
        last.next = start
        f.len = verts.size
        if (example != null) { f.mat = example.mat; f.strength = example.strength; f.smooth = example.smooth; example.no.copyInto(f.no) }
        return f
    }

    fun faceCreateVerts(verts: Array<Vert>, example: Face?): Face {
        val n = verts.size
        val edges = arrayOfNulls<Edge>(n)
        var iPrev = n - 1
        for (i in 0 until n) { edges[iPrev] = edgeCreate(verts[iPrev], verts[i], null, true); iPrev = i }
        return faceCreate(verts, Array(n) { edges[it]!! }, example)
    }

    fun faceKill(f: Face) {
        val first = f.lFirst
        if (first != null) {
            var l: Loop = first
            do { val nx = l.next!!; radialRemove(l.e!!, l); l = nx } while (l !== first)
        }
        f.alive = false
        fpool.remove(f)
    }

    fun edgeKill(e: Edge) {
        while (true) { val l = e.l ?: break; faceKill(l.f) }
        diskRemove(e, e.v1)
        diskRemove(e, e.v2)
        e.alive = false
        epool.remove(e)
    }

    fun vertKill(v: Vert) {
        while (true) { val e = v.e ?: break; edgeKill(e) }
        v.alive = false
        vpool.remove(v)
    }

    private fun diskVertReplace(e: Edge, dst: Vert, src: Vert) {
        diskRemove(e, src)
        if (e.v1 === src) { e.v1 = dst; e.d1Next = null; e.d1Prev = null } else if (e.v2 === src) { e.v2 = dst; e.d2Next = null; e.d2Prev = null }
        diskAppend(e, dst)
    }

    private fun edgeVertSwap(e: Edge, dst: Vert, src: Vert) {
        val first = e.l
        if (first != null) {
            var l: Loop = first
            do {
                if (l.v === src) l.v = dst else if (l.next!!.v === src) l.next!!.v = dst
                l = l.rNext!!
            } while (l !== first)
        }
        diskVertReplace(e, dst, src)
    }

    fun vertSplice(dst: Vert, src: Vert): Boolean {
        if (src === dst) return false
        while (true) { val e = src.e ?: break; edgeVertSwap(e, dst, src) }
        vertKill(src)
        return true
    }

    fun edgeSplice(dst: Edge, src: Edge): Boolean {
        if (!vertInEdge(src, dst.v1) || !vertInEdge(src, dst.v2)) return false
        while (true) { val l = src.l ?: break; radialRemove(src, l); radialAppend(dst, l) }
        edgeKill(src)
        return true
    }

    private fun faceFindDouble(f: Face): Face? {
        val first = f.lFirst!!
        var it = first.rNext!!
        while (it !== first) {
            if (it.f.len == first.f.len) {
                var la = first; var lb = it
                val init = it
                if (it.v === first.v) {
                    do { if (la.e !== lb.e) break; la = la.next!!; lb = lb.next!! } while (lb !== init)
                } else {
                    do { if (la.e !== lb.e) break; la = la.prev!!; lb = lb.next!! } while (lb !== init)
                }
                if (lb === init) return it.f
            }
            it = it.rNext!!
        }
        return null
    }

    private fun sortWinding(v1: Vert, v2: Vert, edges: List<Edge>): Pair<Array<Vert>, Array<Edge>>? {
        for (e in edges) { e.api = e.api or FLAG_MF; e.v1.api = e.v1.api or FLAG_MV; e.v2.api = e.v2.api or FLAG_MV }
        fun cleanup() { for (e in edges) { e.api = e.api and FLAG_MF.inv(); e.v1.api = e.v1.api and FLAG_MV.inv(); e.v2.api = e.v2.api and FLAG_MV.inv() } }
        val len = edges.size
        var vIter = v1
        var eFirst = v1.e!!
        var eIter = eFirst
        var found = false
        do {
            if (eIter.api and FLAG_MF != 0 && otherVert(eIter, vIter) === v2) { found = true; break }
            eIter = diskNext(eIter, vIter)!!
        } while (eIter !== eFirst)
        if (!found) { cleanup(); return null }
        val es = ArrayList<Edge>(len); val vs = ArrayList<Vert>(len)
        var i = 0
        do {
            if (eIter.api and FLAG_MF != 0) {
                if (vIter.api and FLAG_MV == 0) { cleanup(); return null }
                eIter.api = eIter.api and FLAG_MF.inv()
                es.add(eIter)
                vIter.api = vIter.api and FLAG_MV.inv()
                vs.add(vIter)
                i++
                vIter = otherVert(eIter, vIter)!!
                if (i == len) {
                    if (vIter !== vs[0]) { cleanup(); return null }
                    break
                }
                eFirst = eIter
            }
            eIter = diskNext(eIter, vIter)!!
        } while (eIter !== eFirst)
        if (i == len) return vs.toTypedArray() to es.toTypedArray()
        cleanup()
        return null
    }

    private fun systagCountRadial(l: Loop): Int { var c = 0; var it = l; do { if (it.f.api and FLAG_JF != 0) c++; it = it.rNext!! } while (it !== l); return c }

    private fun vertIsManifoldFlagged(v: Vert): Boolean {
        val first = v.e ?: return false
        var e = first
        do {
            val l0 = e.l ?: return false
            if (isBoundary(e)) return false
            var l = l0
            do { if (l.f.api and FLAG_JF == 0) return false; l = l.rNext!! } while (l !== l0)
            e = diskNext(e, v)!!
        } while (e !== first)
        return true
    }

    private fun edgeFaceCountIsOver(e: Edge, n: Int): Boolean {
        val first = e.l ?: return false
        var c = 0
        var l = first
        do { c++; if (c > n) return true; l = l.rNext!! } while (l !== first)
        return false
    }

    fun facesJoin(faces: List<Face>, doDel: Boolean, keepDouble: Boolean = false): Face? {
        if (faces.isEmpty()) return null
        if (faces.size == 1) return faces[0]
        for (f in faces) f.api = f.api or FLAG_JF
        fun clear() { for (f in faces) f.api = f.api and FLAG_JF.inv() }
        val edges = ArrayList<Edge>()
        val delEdges = ArrayList<Edge>()
        val delVerts = ArrayList<Vert>()
        var v1: Vert? = null
        var v2: Vert? = null
        val touched = ArrayList<Any>()
        for (f in faces) {
            for (l in loopsOfFace(f)) {
                val rlen = systagCountRadial(l)
                if (rlen > 2) { clear(); for (t in touched) when (t) { is Edge -> t.api = t.api and FLAG_JF.inv(); is Vert -> t.api = t.api and FLAG_JF.inv() }; return null }
                val e = l.e!!
                if (rlen == 1) {
                    edges.add(e)
                    if (v1 == null) { v1 = l.v; v2 = otherVert(e, l.v) }
                } else if (rlen == 2) {
                    val d1 = vertIsManifoldFlagged(e.v1)
                    val d2 = vertIsManifoldFlagged(e.v2)
                    if (!d1 && !d2 && e.api and FLAG_JF == 0) {
                        if (!edgeFaceCountIsOver(e, 2)) {
                            if (doDel) delEdges.add(e)
                            e.api = e.api or FLAG_JF; touched.add(e)
                        }
                    } else {
                        if (d1 && e.v1.api and FLAG_JF == 0) { if (doDel) delVerts.add(e.v1); e.v1.api = e.v1.api or FLAG_JF; touched.add(e.v1) }
                        if (d2 && e.v2.api and FLAG_JF == 0) { if (doDel) delVerts.add(e.v2); e.v2.api = e.v2.api or FLAG_JF; touched.add(e.v2) }
                    }
                }
            }
        }
        var fNew: Face? = null
        if (edges.isNotEmpty()) {
            val sorted = sortWinding(v1!!, v2!!, edges)
            if (sorted != null) fNew = faceCreate(sorted.first, sorted.second, faces[0])
        }
        if (fNew == null) { clear(); for (t in touched) when (t) { is Edge -> t.api = t.api and FLAG_JF.inv(); is Vert -> t.api = t.api and FLAG_JF.inv() }; return null }
        val existing = faceFindDouble(fNew)
        if (existing != null && !keepDouble) { faceKill(fNew); fNew = existing }
        if (existing == null || keepDouble) {
            for (l in loopsOfFace(fNew)) {
                var l2 = l.rNext!!
                do { if (l2.f.api and FLAG_JF != 0) break; l2 = l2.rNext!! } while (l2 !== l)
                if (l2 !== l) {
                    if (l2.v !== l.v) l2 = l2.next!!
                    l.u = l2.u; l.w = l2.w; l.color = l2.color
                }
            }
        }
        clear()
        fNew.api = fNew.api and FLAG_JF.inv()
        for (t in touched) when (t) { is Edge -> t.api = t.api and FLAG_JF.inv(); is Vert -> t.api = t.api and FLAG_JF.inv() }
        if (doDel) {
            for (e in delEdges) if (e.alive) edgeKill(e)
            for (v in delVerts) if (v.alive) vertKill(v)
        } else for (f in faces) if (f.alive) faceKill(f)
        return fNew
    }

    fun loopReverse(f: Face) {
        val lFirst = f.lFirst!!
        var ePrev = lFirst.prev!!.e!!
        var lPrevRNext = lFirst.prev!!.rNext!!
        var lPrevRPrev = lFirst.prev!!.rPrev!!
        var isPrevBoundary = lPrevRNext === lPrevRNext.rNext
        var l = lFirst
        do {
            val eIter = l.e!!
            val lRNext = l.rNext!!
            val lRPrev = l.rPrev!!
            val isIterBoundary = lRNext === lRNext.rNext
            if (isPrevBoundary) { l.rNext = l; l.rPrev = l } else {
                l.rNext = lPrevRNext; l.rPrev = lPrevRPrev
                lPrevRNext.rPrev = l; lPrevRPrev.rNext = l
            }
            if (eIter.l === l) eIter.l = l.next
            l.e = ePrev
            val t = l.next; l.next = l.prev; l.prev = t
            ePrev = eIter
            lPrevRNext = lRNext
            lPrevRPrev = lRPrev
            isPrevBoundary = isIterBoundary
            l = l.prev!!
        } while (l !== lFirst)
    }

    fun facesJoinPair(la: Loop, lb: Loop, doDel: Boolean, keepDouble: Boolean): Face? {
        if (la.v === lb.v) loopReverse(lb.f)
        return facesJoin(listOf(la.f, lb.f), doDel, keepDouble)
    }

    private fun diskCountAtMost(v: Vert, max: Int): Int {
        val first = v.e ?: return 0
        var c = 0
        var e = first
        do { c++; if (c == max) break; e = diskNext(e, v)!! } while (e !== first)
        return c
    }

    fun joinEdgeKillVert(eKill: Edge, vKill: Vert, doDel: Boolean, checkEdgeExists: Boolean, killDegenerate: Boolean, killDuplicate: Boolean): Edge? {
        if (!vertInEdge(eKill, vKill)) return null
        if (diskCountAtMost(vKill, 3) != 2) return null
        val eOld = diskNext(eKill, vKill)!!
        val vTarget = otherVert(eKill, vKill)!!
        val vOld = otherVert(eOld, vKill)!!
        if (vertInEdge(eOld, vKill) && vertInEdge(eOld, vTarget)) return null
        val eSplice = if (checkEdgeExists) edgeExists(vTarget, vOld) else null
        diskVertReplace(eOld, vTarget, vKill)
        diskRemove(eKill, vTarget)
        val degenerate = ArrayList<Face>()
        val duplicate = ArrayList<Face>()
        val head = eKill.l
        if (head != null) {
            var lk: Loop = head
            do {
                if (lk.next!!.v === vKill) lk.next!!.v = vTarget
                lk.next!!.prev = lk.prev
                lk.prev!!.next = lk.next
                if (lk.f.lFirst === lk) lk.f.lFirst = lk.next
                lk.f.len--
                if (killDegenerate && lk.f.len < 3) degenerate.add(lk.f) else if (killDuplicate) duplicate.add(lk.f)
                lk = lk.rNext!!
            } while (lk !== head)
        }
        eKill.alive = false
        epool.remove(eKill)
        if (doDel) { vKill.alive = false; vpool.remove(vKill) } else vKill.e = null
        if (eSplice != null) edgeSplice(eOld, eSplice)
        for (i in degenerate.indices.reversed()) if (degenerate[i].alive) faceKill(degenerate[i])
        for (i in duplicate.indices.reversed()) { val f = duplicate[i]; if (f.alive && faceFindDouble(f) != null) faceKill(f) }
        return eOld
    }

    fun facesOfVert(v: Vert): List<Face> = loopsOfVert(v).map { it.f }

    fun faceExists(varr: Array<Vert>): Face? {
        val len = varr.size
        val first = varr[0].e ?: return null
        var e = first
        do {
            val h = e.l
            if (h != null) {
                var lr: Loop = h
                do {
                    if (lr.v === varr[0] && lr.f.len == len) {
                        var iw = 2
                        if (lr.next!!.v === varr[1]) {
                            var lw = lr.next!!.next!!
                            do { if (lw.v !== varr[iw]) break; lw = lw.next!!; iw++ } while (iw != len)
                        } else if (lr.prev!!.v === varr[1]) {
                            var lw = lr.prev!!.prev!!
                            do { if (lw.v !== varr[iw]) break; lw = lw.prev!!; iw++ } while (iw != len)
                        }
                        if (iw == len) return lr.f
                    }
                    lr = lr.rNext!!
                } while (lr !== h)
            }
            e = diskNext(e, varr[0])!!
        } while (e !== first)
        return null
    }

    fun faceVertShareLoop(f: Face, v: Vert): Loop? = loopsOfFace(f).firstOrNull { it.v === v }

    fun vertCollapseFaces(eKill: Edge, vKill: Vert, fac: Float, doDel: Boolean, joinFaces: Boolean, killDegenerate: Boolean, killDuplicate: Boolean, interpVert: (Vert, Vert, Float) -> Unit): Edge? {
        val tv = otherVert(eKill, vKill)!!
        val head = eKill.l
        if (head != null) {
            var l: Loop = head
            do {
                if (l.v === tv && l.next!!.v === vKill) {
                    val kv = l.next!!
                    val w0 = 1.0f - fac
                    kv.u = 0f + kv.u * w0 + l.u * fac
                    kv.w = 0f + kv.w * w0 + l.w * fac
                    kv.color = mixColor(kv.color, w0, l.color, fac)
                }
                l = l.rNext!!
            } while (l !== head)
        }
        interpVert(vKill, tv, fac)
        val e2 = diskNext(eKill, vKill)!!
        val tv2 = otherVert(e2, vKill)!!
        if (joinFaces) {
            val faces = facesOfVert(vKill)
            if (faces.size >= 2) {
                val f2 = facesJoin(faces, true, true)
                if (f2 != null) {
                    val la = faceVertShareLoop(f2, tv)
                    val lb = faceVertShareLoop(f2, tv2)
                    if (la != null && lb != null) return faceSplit(f2, la, lb, null, false)?.e
                }
            }
            return null
        }
        return joinEdgeKillVert(eKill, vKill, doDel, true, killDegenerate, killDuplicate)
    }

    var lastSplitFace: Face? = null

    fun faceSplit(f: Face, la: Loop, lb: Loop, example: Edge?, noDouble: Boolean): Loop? {
        if (la.next === lb || la.prev === lb || f !== la.f || f !== lb.f) return null
        val v1 = la.v; val v2 = lb.v
        val e = edgeCreate(v1, v2, example, noDouble)
        val f2 = Face(nextId++)
        fpool.add(f2)
        lastSplitFace = f2
        f2.mat = f.mat; f2.strength = f.strength; f2.smooth = f.smooth; f.no.copyInto(f2.no)
        val lf1 = Loop(v2, e, f).also { it.u = lb.u; it.w = lb.w; it.color = lb.color }
        val lf2 = Loop(v1, e, f2).also { it.u = la.u; it.w = la.w; it.color = la.color }
        lf1.prev = lb.prev
        lf2.prev = la.prev
        lb.prev!!.next = lf1
        la.prev!!.next = lf2
        lf1.next = la
        lf2.next = lb
        la.prev = lf1
        lb.prev = lf2
        var first1 = false
        var it = lf1
        do { if (it === f.lFirst) first1 = true; it = it.next!! } while (it !== lf1)
        val fl = f.lFirst!!
        if (first1) {
            f2.lFirst = if (fl.prev === lf1) lf2.prev else if (fl.next === lf1) lf2.next else lf2
        } else {
            f2.lFirst = fl
            f.lFirst = if (fl.prev === lf2) lf1.prev else if (fl.next === lf2) lf1.next else lf1
        }
        var n2 = 0
        val s2 = f2.lFirst!!
        it = s2
        do { it.f = f2; n2++; it = it.next!! } while (it !== s2)
        lf1.e = null; lf2.e = null
        radialAppend(e, lf1)
        radialAppend(e, lf2)
        f2.len = n2
        var n1 = 0
        val s1 = f.lFirst!!
        it = s1
        do { n1++; it = it.next!! } while (it !== s1)
        f.len = n1
        return lf2
    }

    fun vertIsManifold(v: Vert): Boolean {
        val eFirst0 = v.e ?: return false
        var e = eFirst0
        var lFirst = e.l
        var loopNum = 0
        var boundary = 0
        do {
            val l = e.l
            if (l == null || l !== l.rNext!!.rNext) return false
            if (l.v === v) loopNum++
            if (!isBoundary(e)) { if (l.rNext!!.v === v) loopNum++ } else {
                lFirst = l
                boundary++
                if (boundary == 3) return false
            }
            e = diskNext(e, v)!!
        } while (e !== eFirst0)
        val lf0 = lFirst!!
        val eFirst = lf0.e
        val start = if (lf0.v === v) lf0 else lf0.next!!
        var it: Loop? = start
        val step = arrayOf<Edge?>(eFirst)
        var region = 0
        do {
            region++
            it = vertStepFanLoop(it!!, step)
        } while (it !== start && it != null)
        return loopNum == region
    }

    private fun edgeInFace(e: Edge, f: Face): Boolean = loopsOfFace(f).any { it.e === e }

    private fun edgeShareFaceCheck(a: Edge, b: Edge): Boolean {
        val h = a.l ?: return false
        if (b.l == null) return false
        var l = h
        do { if (edgeInFace(b, l.f)) return true; l = l.rNext!! } while (l !== h)
        return false
    }

    fun diskDissolve(v: Vert, interpVert: (Vert, Vert, Float) -> Unit): Boolean {
        if (!vertIsManifold(v)) return false
        var keep: Edge? = null
        var base: Edge? = null
        var len = 0
        var e: Edge = v.e!!
        do {
            e = diskNext(e, v)!!
            if (!edgeShareFaceCheck(e, v.e!!)) { keep = e; base = v.e; break }
            len++
        } while (e !== v.e)
        if (keep == null && len == 3) {
            if (facesJoinPair(e.l!!, e.l!!.rNext!!, true, true) == null) return false
            if (vertCollapseFaces(v.e!!, v, 1.0f, true, false, true, true, interpVert) == null) return false
            return true
        }
        if (keep == null && len == 2) {
            val en = vertCollapseFaces(v.e!!, v, 1.0f, true, true, true, true, interpVert) ?: return false
            if (en.l !== en.l!!.rNext) { if (facesJoinPair(en.l!!, en.l!!.rNext!!, true, true) == null) return false }
            return true
        }
        if (keep != null) {
            var done = false
            while (!done) {
                done = true
                var it: Edge = v.e!!
                do {
                    var f: Face? = null
                    if (isManifold(it) && it !== base && it !== keep) {
                        f = facesJoinPair(it.l!!, it.l!!.rNext!!, true, true) ?: return false
                    }
                    if (f != null) { done = false; break }
                    it = diskNext(it, v)!!
                } while (it !== v.e)
            }
            val b = base!!
            val en = vertCollapseFaces(b, v, 1.0f, true, !isBoundary(b), true, true, interpVert) ?: return false
            val l = en.l
            if (l != null && l !== l.rNext) { if (facesJoinPair(l, l.rNext!!, true, true) == null) return false }
        }
        return true
    }

    fun vertDissolve(v: Vert, interpVert: (Vert, Vert, Float) -> Unit): Boolean {
        val len = diskCountAtMost(v, 3)
        if (len == 1) { vertKill(v); return true }
        if (!vertIsManifold(v)) {
            if (v.e == null) { vertKill(v); return true }
            if (v.e!!.l == null) {
                if (len == 2) return vertCollapseFaces(v.e!!, v, 1.0f, true, false, true, true, interpVert) != null
                return false
            }
            return false
        }
        if (len == 2 && faceCountAtMost(v, 2) == 1) return vertCollapseFaces(v.e!!, v, 1.0f, true, false, true, true, interpVert) != null
        return diskDissolve(v, interpVert)
    }

    private fun faceCountAtMost(v: Vert, max: Int): Int {
        val first = v.e ?: return 0
        var c = 0
        var e = first
        do {
            val h = e.l
            if (h != null) {
                var l: Loop = h
                do { if (l.v === v) { c++; if (c == max) return c }; l = l.rNext!! } while (l !== h)
            }
            e = diskNext(e, v)!!
        } while (e !== first)
        return c
    }

    private fun mixColor(a: Int, wa: Float, b: Int, wb: Float): Int {
        fun ch(x: Int, s: Int) = (x ushr s) and 0xFF
        fun q(v: Float): Int { val r = v + 0.5f; return if (r <= 0f) 0 else if (r >= 255f) 255 else r.toInt() }
        var out = 0
        for (s in intArrayOf(24, 16, 8, 0)) out = out or (q(ch(a, s) * wa + ch(b, s) * wb) shl s)
        return out
    }

    private fun faceSwapData(a: Face, b: Face) {
        for (l in loopsOfFace(a)) l.f = b
        for (l in loopsOfFace(b)) l.f = a
        val lf = a.lFirst; a.lFirst = b.lFirst; b.lFirst = lf
        val ln = a.len; a.len = b.len; b.len = ln
        val m = a.mat; a.mat = b.mat; b.mat = m
        val st = a.strength; a.strength = b.strength; b.strength = st
        val s = a.smooth; a.smooth = b.smooth; b.smooth = s
        val t = a.tag; a.tag = b.tag; b.tag = t
        val ap = a.api; a.api = b.api; b.api = ap
        val no = a.no.copyOf(); b.no.copyInto(a.no); no.copyInto(b.no)
    }

    fun faceTriangulateBeauty(f: Face, newFaces: MutableList<Face>, newEdges: MutableList<Edge>, facesDouble: MutableList<Face>) {
        val loops = loopsOfFace(f)
        val n = f.len
        val totTri = n - 2
        val lastTri = n - 3
        val tris: IntArray
        val order: List<Loop>
        if (n == 4) {
            val co = FloatArray(12)
            for (i in 0 until 4) loops[i].v.co.copyInto(co, i * 3)
            tris = BlendTriangulate.faceTris(co, intArrayOf(0, 1, 2, 3), 0, 0)
            order = loops
        } else {
            val co = FloatArray(n * 3)
            for (i in 0 until n) loops[i].v.co.copyInto(co, i * 3)
            val t = BlendPolyfill.triangulateWithNormal(co, IntArray(n) { it }, f.no[0], f.no[1], f.no[2])
            BlendTriangulate.beautify(BlendPolyfill.lastProjected, n, t)
            tris = t
            order = loops
        }
        var fNew: Face? = null
        for (i in 0 until totTri) {
            val lt = arrayOf(order[tris[i * 3]], order[tris[i * 3 + 1]], order[tris[i * 3 + 2]])
            val nf = faceCreateVerts(arrayOf(lt[0].v, lt[1].v, lt[2].v), f)
            fNew = nf
            val lNew = nf.lFirst!!
            if (lNew.rNext !== lNew) {
                var it = lNew.rNext!!
                do {
                    if (it.f.len == 3 && lNew.prev!!.v === it.prev!!.v) { facesDouble.add(0, if (i != lastTri) nf else f); break }
                    it = it.rNext!!
                } while (it !== lNew)
            }
            fun copy(src: Loop, dst: Loop) { dst.u = src.u; dst.w = src.w; dst.color = src.color }
            copy(lt[0], lNew); copy(lt[1], lNew.next!!); copy(lt[2], lNew.prev!!)
            if (i != lastTri) newFaces.add(nf)
            var l = lNew
            do { if (l === l.rNext) newEdges.add(l.e!!); l = l.next!! } while (l !== lNew)
        }
        val last = fNew!!
        faceSwapData(f, last)
        faceKill(last)
    }

    var hasStrength = false

    fun toMesh(groups: List<String>, from: BlendMeshData): BlendMeshData {
        val vs = verts()
        for ((i, v) in vs.withIndex()) v.index = i
        val es = edges()
        val fs = faces()
        val co = FloatArray(vs.size * 3)
        for ((i, v) in vs.withIndex()) v.co.copyInto(co, i * 3)
        val fStart = IntArray(fs.size)
        val fSize = IntArray(fs.size)
        var total = 0
        for ((i, f) in fs.withIndex()) { fStart[i] = total; fSize[i] = f.len; total += f.len }
        val corners = IntArray(total)
        val uv = if (hasUv) FloatArray(total * 2) else null
        val cc = if (hasCornerColor) IntArray(total) else null
        var c = 0
        for (f in fs) for (l in loopsOfFace(f)) {
            corners[c] = l.v.index
            if (uv != null) { uv[c * 2] = l.u; uv[c * 2 + 1] = l.w }
            if (cc != null) cc[c] = l.color
            c++
        }
        val loose = ArrayList<Int>()
        val fileEdges = IntArray(es.size * 2)
        val sharp = HashSet<Long>()
        val crease = HashMap<Long, Float>()
        for ((i, e) in es.withIndex()) {
            fileEdges[i * 2] = e.v1.index; fileEdges[i * 2 + 1] = e.v2.index
            val k = BlendMeshData.key(e.v1.index, e.v2.index)
            if (e.l == null) { loose.add(e.v1.index); loose.add(e.v2.index) }
            if (!e.smooth) sharp.add(k)
            if (hasEdgeCrease && e.crease != 0f) crease[k] = e.crease
        }
        val r = BlendMeshData(
            co, fStart, fSize, corners, IntArray(fs.size) { fs[it].mat }, BooleanArray(fs.size) { fs[it].smooth }, uv, cc,
            if (hasPointColor) IntArray(vs.size) { vs[it].color } else null, crease,
            if (hasVertCrease) FloatArray(vs.size) { vs[it].crease } else null, loose.toIntArray(),
            if (hasDvert) Array(vs.size) { vs[it].dvert } else null, groups
        )
        r.fileEdges = fileEdges
        if (hasStrength) r.faceStrength = IntArray(fs.size) { fs[it].strength }
        r.seamEdges = es.filter { it.seam }.map { BlendMeshData.key(it.v1.index, it.v2.index) }.toSet()
        return r.carryNormals(from, sharp)
    }
}
