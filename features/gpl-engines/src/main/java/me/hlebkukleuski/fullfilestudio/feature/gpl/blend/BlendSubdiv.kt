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
 * Portions ported and adapted from OpenSubdiv (https://graphics.pixar.com/opensubdiv/),
 * Copyright 2013-2026 Pixar / DreamWorks Animation LLC / Autodesk, Inc. / Google, Inc. / DigitalFish,
 * licensed under the Modified Apache 2.0 License (Tomorrow Open Source Technology License 1.0 / TOST-1.0).
 *
 * Multiresolution tangent displacement routines (tangentMatrix, displaceMultires, averageWithOther)
 * ported and adapted from Blender (https://www.blender.org/, source/blender/blenkernel/intern/subdiv_displacement_multires.cc),
 * Copyright (C) 2018-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

internal class SubdivInput(
    val co: FloatArray,
    val fStart: IntArray,
    val fSize: IntArray,
    val fVerts: IntArray,
    val edgeSharp: Map<Long, Float>,
    val vertSharp: FloatArray?,
    val infSharpVerts: BooleanArray?,
    val uvIndex: IntArray?,
    val uv: FloatArray?,
    val marked: Set<Long>? = null
)

internal class SubdivResult(
    val co: FloatArray,
    val fStart: IntArray,
    val fSize: IntArray,
    val fVerts: IntArray,
    val faceOrigin: IntArray,
    val uv: FloatArray?,
    val vertOriginFace: IntArray,
    val vertWeights: FloatArray,
    val cornerOriginCorner: IntArray,
    val cornerWeights: FloatArray,
    val marked: Set<Long> = emptySet()
)

internal class MultiresDisp(val grids: Array<FloatArray?>, val gridSize: Int)

internal object BlendSubdiv {
    const val BOUNDARY_EDGE_ONLY = 1
    const val BOUNDARY_EDGE_AND_CORNER = 2
    const val FVAR_NONE = 0
    const val FVAR_CORNERS_ONLY = 1
    const val FVAR_CORNERS_PLUS1 = 2
    const val FVAR_CORNERS_PLUS2 = 3
    const val FVAR_BOUNDARIES = 4
    const val FVAR_ALL = 5

    private const val SMOOTH = 1
    private const val DART = 2
    private const val CREASE = 4
    private const val CORNER = 8
    private const val INF = 10.0f

    private fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

    private fun dec(s: Float): Float = if (s <= 0f) 0f else if (s >= INF) INF else if (s > 1.0f) s - 1.0f else 0f

    private class Level(val nV: Int, val fStart: IntArray, val fSize: IntArray, val fVerts: IntArray) {
        val nF = fStart.size
        lateinit var eVerts: IntArray
        lateinit var fEdges: IntArray
        lateinit var eFaceStart: IntArray
        lateinit var eFaces: IntArray
        lateinit var vEdgeStart: IntArray
        lateinit var vEdges: IntArray
        lateinit var vFaceStart: IntArray
        lateinit var vFaces: IntArray
        lateinit var vFaceCorner: IntArray
        lateinit var eSharp: FloatArray
        lateinit var vSharp: FloatArray
        lateinit var vRule: IntArray
        lateinit var vBoundary: BooleanArray
        lateinit var vNonManifold: BooleanArray
        lateinit var eNonManifold: BooleanArray
        var eMark: BooleanArray? = null
        val nE get() = eVerts.size / 2

        fun build() {
            val map = HashMap<Long, Int>()
            val ev = ArrayList<Int>()
            fEdges = IntArray(fVerts.size)
            for (f in 0 until nF) {
                val s = fStart[f]
                val n = fSize[f]
                for (k in 0 until n) {
                    val a = fVerts[s + k]
                    val b = fVerts[s + (k + 1) % n]
                    val kk = key(a, b)
                    val e = map.getOrPut(kk) { ev.add(a); ev.add(b); ev.size / 2 - 1 }
                    fEdges[s + k] = e
                }
            }
            eVerts = ev.toIntArray()
            val ne = nE
            val ecount = IntArray(ne + 1)
            for (e in fEdges) ecount[e + 1]++
            for (i in 0 until ne) ecount[i + 1] += ecount[i]
            eFaceStart = ecount.copyOf()
            eFaces = IntArray(fEdges.size)
            val fill = ecount.copyOf()
            for (f in 0 until nF) for (k in 0 until fSize[f]) { val e = fEdges[fStart[f] + k]; eFaces[fill[e]++] = f }
            val vc = IntArray(nV + 1)
            for (e in 0 until ne) { vc[eVerts[e * 2] + 1]++; vc[eVerts[e * 2 + 1] + 1]++ }
            for (i in 0 until nV) vc[i + 1] += vc[i]
            vEdgeStart = vc.copyOf()
            vEdges = IntArray(ne * 2)
            val vf = vc.copyOf()
            for (e in 0 until ne) { vEdges[vf[eVerts[e * 2]]++] = e; vEdges[vf[eVerts[e * 2 + 1]]++] = e }
            val fc = IntArray(nV + 1)
            for (v in fVerts) fc[v + 1]++
            for (i in 0 until nV) fc[i + 1] += fc[i]
            vFaceStart = fc.copyOf()
            vFaces = IntArray(fVerts.size)
            vFaceCorner = IntArray(fVerts.size)
            val ff = fc.copyOf()
            for (f in 0 until nF) for (k in 0 until fSize[f]) { val v = fVerts[fStart[f] + k]; vFaces[ff[v]] = f; vFaceCorner[ff[v]] = fStart[f] + k; ff[v]++ }
            eNonManifold = BooleanArray(ne) { eFaceStart[it + 1] - eFaceStart[it] > 2 }
            vNonManifold = BooleanArray(nV)
            vBoundary = BooleanArray(nV)
            for (v in 0 until nV) {
                var bnd = 0
                var nm = false
                for (i in vEdgeStart[v] until vEdgeStart[v + 1]) {
                    val e = vEdges[i]
                    val nf = eFaceStart[e + 1] - eFaceStart[e]
                    if (nf < 2) bnd++
                    if (nf > 2) nm = true
                }
                vBoundary[v] = bnd > 0
                if (nm || bnd > 2 || (bnd == 1)) nm = true
                if (!nm) nm = !fanConnected(v)
                vNonManifold[v] = nm
            }
        }

        private fun fanConnected(v: Int): Boolean {
            val fs = vFaceStart[v]
            val fe = vFaceStart[v + 1]
            val n = fe - fs
            if (n <= 1) return true
            val seen = BooleanArray(n)
            val stack = ArrayList<Int>()
            stack.add(0); seen[0] = true
            var cnt = 1
            while (stack.isNotEmpty()) {
                val i = stack.removeAt(stack.size - 1)
                val f = vFaces[fs + i]
                val c = vFaceCorner[fs + i] - fStart[f]
                val sz = fSize[f]
                val e1 = fEdges[fStart[f] + c]
                val e0 = fEdges[fStart[f] + (c + sz - 1) % sz]
                for (j in 0 until n) if (!seen[j]) {
                    val g = vFaces[fs + j]
                    val cg = vFaceCorner[fs + j] - fStart[g]
                    val szg = fSize[g]
                    val g1 = fEdges[fStart[g] + cg]
                    val g0 = fEdges[fStart[g] + (cg + szg - 1) % szg]
                    if (g1 == e0 || g1 == e1 || g0 == e0 || g0 == e1) { seen[j] = true; cnt++; stack.add(j) }
                }
            }
            return cnt == n
        }

        fun other(e: Int, v: Int) = if (eVerts[e * 2] == v) eVerts[e * 2 + 1] else eVerts[e * 2]

        fun valenceE(v: Int) = vEdgeStart[v + 1] - vEdgeStart[v]
        fun valenceF(v: Int) = vFaceStart[v + 1] - vFaceStart[v]

        fun rule(vs: Float, edgesSharp: (Int) -> Float, v: Int): Int {
            if (vs > 0f) return CORNER
            var c = 0
            for (i in vEdgeStart[v] until vEdgeStart[v + 1]) if (edgesSharp(vEdges[i]) > 0f) c++
            return if (c > 2) CORNER else (1 shl c)
        }
    }

    private fun tag(l: Level, boundary: Int) {
        val ne = l.nE
        for (e in 0 until ne) {
            val nf = l.eFaceStart[e + 1] - l.eFaceStart[e]
            if (nf < 2 || l.eNonManifold[e]) l.eSharp[e] = INF
        }
        for (v in 0 until l.nV) {
            val topoCorner = l.valenceF(v) == 1 && l.valenceE(v) == 2
            if (topoCorner && boundary == BOUNDARY_EDGE_AND_CORNER) l.vSharp[v] = INF
            else if (l.vNonManifold[v] && l.vSharp[v] < INF) {
                var nm = 0
                var bnd = 0
                for (i in l.vEdgeStart[v] until l.vEdgeStart[v + 1]) {
                    val e = l.vEdges[i]
                    val nf = l.eFaceStart[e + 1] - l.eFaceStart[e]
                    if (nf > 2) nm++
                    if (nf < 2) bnd++
                }
                val nonManCrease = nm == 2 && bnd == 0 && l.valenceF(v) > l.valenceE(v)
                if (!nonManCrease) l.vSharp[v] = INF
            }
            l.vRule[v] = l.rule(l.vSharp[v], { l.eSharp[it] }, v)
        }
    }

    private class Refined(val child: Level, val faceChild: IntArray, val edgeChild: IntArray, val vertChild: IntArray, val childEdgeParentHalf: IntArray)

    private fun refineTopology(p: Level): Refined {
        val nF = p.nF
        val nE = p.nE
        val nV = p.nV
        val faceChild = IntArray(nF) { it }
        val edgeChild = IntArray(nE) { nF + it }
        val vertChild = IntArray(nV) { nF + nE + it }
        val cnV = nF + nE + nV
        val total = p.fVerts.size
        val cfStart = IntArray(total) { it * 4 }
        val cfSize = IntArray(total) { 4 }
        val cfVerts = IntArray(total * 4)
        var q = 0
        for (f in 0 until nF) {
            val s = p.fStart[f]
            val n = p.fSize[f]
            for (k in 0 until n) {
                val o = q * 4
                cfVerts[o] = vertChild[p.fVerts[s + k]]
                cfVerts[o + 1] = edgeChild[p.fEdges[s + k]]
                cfVerts[o + 2] = faceChild[f]
                cfVerts[o + 3] = edgeChild[p.fEdges[s + (k + n - 1) % n]]
                q++
            }
        }
        val c = Level(cnV, cfStart, cfSize, cfVerts)
        c.build()
        c.eSharp = FloatArray(c.nE)
        c.vSharp = FloatArray(cnV)
        c.vRule = IntArray(cnV)
        val half = IntArray(c.nE) { -1 }
        for (e in 0 until c.nE) {
            val a = c.eVerts[e * 2]
            val b = c.eVerts[e * 2 + 1]
            val (lo, hi) = if (a < b) a to b else b to a
            if (lo in nF until nF + nE && hi >= nF + nE) {
                val pe = lo - nF
                val pv = hi - nF - nE
                if (p.eVerts[pe * 2] == pv || p.eVerts[pe * 2 + 1] == pv) {
                    half[e] = pe
                    c.eSharp[e] = dec(p.eSharp[pe])
                }
            }
        }
        for (v in 0 until nV) c.vSharp[vertChild[v]] = dec(p.vSharp[v])
        val pm = p.eMark
        if (pm != null) c.eMark = BooleanArray(c.nE) { half[it] >= 0 && pm[half[it]] }
        for (v in 0 until cnV) c.vRule[v] = c.rule(c.vSharp[v], { c.eSharp[it] }, v)
        return Refined(c, faceChild, edgeChild, vertChild, half)
    }

    private fun smoothVertexMask(p: Level, v: Int, out: (Int, Float, Int) -> Unit) {
        val n = p.valenceF(v)
        val vw = (n - 2).toFloat() / n.toFloat()
        val fw = 1.0f / (n * n).toFloat()
        out(0, vw, v)
        for (i in p.vEdgeStart[v] until p.vEdgeStart[v + 1]) out(1, fw, p.other(p.vEdges[i], v))
        for (i in p.vFaceStart[v] until p.vFaceStart[v + 1]) out(2, fw, p.vFaces[i])
    }

    private fun creaseEnds(p: Level, v: Int, sharp: (Int) -> Float): IntArray {
        val r = ArrayList<Int>()
        for (i in p.vEdgeStart[v] until p.vEdgeStart[v + 1]) if (sharp(p.vEdges[i]) > 0f) r.add(p.vEdges[i])
        return intArrayOf(r.first(), r.last())
    }

    private class Mask {
        var v = 0f
        val e = HashMap<Int, Float>()
        val f = HashMap<Int, Float>()
        fun scaled(s: Float): Mask { val m = Mask(); m.v = v * s; e.forEach { (k, x) -> m.e[k] = x * s }; f.forEach { (k, x) -> m.f[k] = x * s }; return m }
        fun add(o: Mask) { v += o.v; o.e.forEach { (k, x) -> e[k] = (e[k] ?: 0f) + x }; o.f.forEach { (k, x) -> f[k] = (f[k] ?: 0f) + x } }
    }

    private fun ruleMask(p: Level, v: Int, rule: Int, sharp: (Int) -> Float): Mask {
        val m = Mask()
        when (rule) {
            SMOOTH, DART -> smoothVertexMask(p, v) { kind, w, idx -> when (kind) { 0 -> m.v = w; 1 -> m.e[idx] = (m.e[idx] ?: 0f) + w; else -> m.f[idx] = (m.f[idx] ?: 0f) + w } }
            CREASE -> {
                val ce = creaseEnds(p, v, sharp)
                m.v = 0.75f
                m.e[p.other(ce[0], v)] = (m.e[p.other(ce[0], v)] ?: 0f) + 0.125f
                m.e[p.other(ce[1], v)] = (m.e[p.other(ce[1], v)] ?: 0f) + 0.125f
            }
            else -> m.v = 1.0f
        }
        return m
    }

    private fun vertexVertexMask(p: Level, c: Level, v: Int, cv: Int): Mask {
        val pRule = p.vRule[v]
        if (pRule == SMOOTH || pRule == DART) return ruleMask(p, v, pRule) { p.eSharp[it] }
        val pm = ruleMask(p, v, pRule) { p.eSharp[it] }
        val cRule = c.vRule[cv]
        if (cRule == pRule) return pm
        val childSharp = { e: Int -> dec(p.eSharp[e]) }
        val cm = ruleMask(p, v, cRule, childSharp)
        var cnt = 0
        var sum = 0f
        val pvs = p.vSharp[v]
        if (pvs > 0f && dec(pvs) <= 0f) { cnt = 1; sum = pvs }
        for (i in p.vEdgeStart[v] until p.vEdgeStart[v + 1]) {
            val s = p.eSharp[p.vEdges[i]]
            if (s > 0f && s <= 1.0f) { sum += s; cnt++ }
        }
        val pw = if (cnt == 0) 0f else minOf(1f, sum / cnt)
        val cw = 1.0f - pw
        val r = pm.scaled(pw)
        r.add(cm.scaled(cw))
        return r
    }

    private class FVar(val values: FloatArray, val corner: IntArray)

    fun subdivide(input: SubdivInput, levels: Int, limitLevel: Int, boundary: Int, fvarMode: Int, useLimit: Boolean = true, patchLevel: Int = limitLevel, semiAsSmooth: Boolean = false, isolation: Int = -1, multires: MultiresDisp? = null): SubdivResult {
        var lvl = Level(input.co.size / 3, input.fStart, input.fSize, input.fVerts)
        lvl.build()
        lvl.eSharp = FloatArray(lvl.nE) { e -> input.edgeSharp[key(lvl.eVerts[e * 2], lvl.eVerts[e * 2 + 1])] ?: 0f }
        val mk = input.marked
        if (!mk.isNullOrEmpty()) lvl.eMark = BooleanArray(lvl.nE) { e -> key(lvl.eVerts[e * 2], lvl.eVerts[e * 2 + 1]) in mk }
        lvl.vSharp = FloatArray(lvl.nV) { v -> if (input.infSharpVerts?.getOrNull(v) == true) INF else input.vertSharp?.getOrNull(v) ?: 0f }
        for (v in 0 until lvl.nV) {
            if (lvl.valenceE(v) == 2 && input.infSharpVerts?.getOrNull(v) != true) {
                val a = lvl.eSharp[lvl.vEdges[lvl.vEdgeStart[v]]]
                val b = lvl.eSharp[lvl.vEdges[lvl.vEdgeStart[v] + 1]]
                lvl.vSharp[v] = minOf(lvl.vSharp[v] + minOf(a, b), 10.0f)
            }
        }
        lvl.vRule = IntArray(lvl.nV)
        tag(lvl, boundary)
        var co = input.co.copyOf()
        var fvar = if (input.uv != null && input.uvIndex != null) FVar(input.uv.copyOf(), input.uvIndex.copyOf()) else null
        val nF0 = lvl.nF
        var faceOrigin = IntArray(nF0) { it }
        var vertFace = IntArray(lvl.nV) { -1 }
        var vertW = FloatArray(0)
        val coarseCorner = HashMap<Int, Int>()
        for (f in 0 until nF0) for (k in 0 until lvl.fSize[f]) { val v = lvl.fVerts[lvl.fStart[f] + k]; if (vertFace[v] < 0) vertFace[v] = f }
        var vertCoarse = Array(lvl.nV) { v -> floatArrayOf() }
        var vWeights: Array<FloatArray>
        run {
            vWeights = Array(lvl.nV) { v ->
                val f = vertFace[v]
                if (f < 0) floatArrayOf() else FloatArray(lvl.fSize[f]) { k -> if (lvl.fVerts[lvl.fStart[f] + k] == v) 1f else 0f }
            }
        }
        var cornerOrigin = IntArray(lvl.fVerts.size) { it }
        var cornerW: Array<FloatArray> = Array(lvl.fVerts.size) { c -> val f = cornerFaceOf(lvl, c); FloatArray(lvl.fSize[f]) { k -> if (lvl.fStart[f] + k == c) 1f else 0f } }
        val target = maxOf(levels, limitLevel)
        var levelMap: IntArray? = null
        var greg: Array<DoubleArray?>? = null
        var fgreg: Array<DoubleArray?>? = null
        var origin = ByteArray(lvl.nV)
        var coarseIdx = IntArray(lvl.nV) { it }
        var pFace = IntArray(0)
        var pUv = FloatArray(0)
        val track = multires != null
        val ptexOff = IntArray(nF0)
        run { var o = 0; for (f in 0 until nF0) { ptexOff[f] = o; o += if (lvl.fSize[f] == 4) 1 else lvl.fSize[f] } }
        var fPtex = IntArray(if (track) nF0 else 0) { if (lvl.fSize[it] == 4) ptexOff[it] else -1 }
        var cUv = FloatArray(if (track) lvl.fVerts.size * 2 else 0) { i -> val c = i / 2; val f = cornerFaceOf(lvl, c); val k = c - lvl.fStart[f]; if (i % 2 == 0) (if (k == 1 || k == 2) 1f else 0f) else (if (k >= 2) 1f else 0f) }
        var eOrig = IntArray(if (track) lvl.nE else 0) { it }
        var vOrigE = IntArray(if (track) lvl.nV else 0) { -1 }
        val lvl0 = lvl
        var outPtex = fPtex; var outUv = cUv; var outVOrigE = vOrigE; var outCoarse = coarseIdx
        for (step in 0 until target) {
            if (useLimit && step == isolation && isolation in 1 until target) {
                greg = gregoryPatches(lvl, co)
                val fq = fvar
                if (fq != null && fvarMode != FVAR_ALL) fgreg = fvarPatches(lvl, fq, fvarMode, origin)
                flattenForPatches(lvl, boundary)
                pFace = IntArray(lvl.fVerts.size) { cornerFaceOf(lvl, it) }
                pUv = FloatArray(lvl.fVerts.size * 2) { i -> val k = (i / 2) - lvl.fStart[pFace[i / 2]]; if (i % 2 == 0) (if (k == 1 || k == 2) 1f else 0f) else (if (k >= 2) 1f else 0f) }
            }
            val r = refineTopology(lvl)
            val c = r.child
            if (step < levels) { val ci = coarseIdx; coarseIdx = IntArray(ci.size) { r.vertChild[ci[it]] } }
            val po = origin
            origin = ByteArray(c.nV) { if (it < lvl.nF) 2 else if (it < lvl.nF + lvl.nE) 1 else po[it - lvl.nF - lvl.nE] }
            val nco = FloatArray(c.nV * 3)
            for (f in 0 until lvl.nF) {
                val s = lvl.fStart[f]; val n = lvl.fSize[f]
                val cv = r.faceChild[f]
                for (k in 0 until n) { val v = lvl.fVerts[s + k]; for (d in 0 until 3) nco[cv * 3 + d] += co[v * 3 + d] }
                val inv = 1.0f / n
                for (d in 0 until 3) nco[cv * 3 + d] *= inv
            }
            for (e in 0 until lvl.nE) {
                val cv = r.edgeChild[e]
                val a = lvl.eVerts[e * 2]; val b = lvl.eVerts[e * 2 + 1]
                val sharp = lvl.eSharp[e]
                val cRule = c.vRule[cv]
                val nfc = lvl.eFaceStart[e + 1] - lvl.eFaceStart[e]
                var vw: Float
                var fwEach: Float
                if (sharp <= 0f) {
                    vw = 0.25f; fwEach = if (nfc == 2) 0.25f else 0.5f / nfc
                } else if (cRule == CREASE) {
                    vw = 0.5f; fwEach = 0f
                } else {
                    val pw = sharp
                    val cw = 1.0f - pw
                    val sv = 0.25f
                    vw = pw * 0.5f + cw * sv
                    fwEach = (if (nfc == 2) 0.25f else 0.5f / nfc) * cw
                }
                for (d in 0 until 3) nco[cv * 3 + d] = co[a * 3 + d] * vw + co[b * 3 + d] * vw
                if (fwEach != 0f) for (i in lvl.eFaceStart[e] until lvl.eFaceStart[e + 1]) {
                    val fcv = r.faceChild[lvl.eFaces[i]]
                    for (d in 0 until 3) nco[cv * 3 + d] += nco[fcv * 3 + d] * fwEach
                }
            }
            for (v in 0 until lvl.nV) {
                val cv = r.vertChild[v]
                val m = vertexVertexMask(lvl, c, v, cv)
                val acc = FloatArray(3)
                for ((f, w) in m.f) { val fcv = r.faceChild[f]; for (d in 0 until 3) acc[d] += nco[fcv * 3 + d] * w }
                for ((u, w) in m.e) for (d in 0 until 3) acc[d] += co[u * 3 + d] * w
                for (d in 0 until 3) acc[d] += co[v * 3 + d] * m.v
                acc.copyInto(nco, cv * 3)
            }
            val fv = fvar
            fvar = if (fv != null) refineFVar(lvl, c, r, fv, fvarMode, greg != null) else null
            val nvw = Array(c.nV) { floatArrayOf() }
            val nvf = IntArray(c.nV) { -1 }
            for (f in 0 until lvl.nF) {
                val s = lvl.fStart[f]; val n = lvl.fSize[f]
                val of = faceOrigin[f]
                val cvF = r.faceChild[f]
                if (nvf[cvF] < 0) {
                    nvf[cvF] = of
                    val acc = FloatArray(input.fSize[of])
                    for (k in 0 until n) { val w = cornerW[s + k]; for (j in acc.indices) acc[j] += w[j] / n }
                    nvw[cvF] = acc
                }
                for (k in 0 until n) {
                    val e = lvl.fEdges[s + k]
                    val ce = r.edgeChild[e]
                    if (nvf[ce] < 0) {
                        nvf[ce] = of
                        val w0 = cornerW[s + k]; val w1 = cornerW[s + (k + 1) % n]
                        nvw[ce] = FloatArray(w0.size) { (w0[it] + w1[it]) * 0.5f }
                    }
                    val vv = r.vertChild[lvl.fVerts[s + k]]
                    if (nvf[vv] < 0) { nvf[vv] = of; nvw[vv] = cornerW[s + k].copyOf() }
                }
            }
            val nco2 = c.fVerts.size
            val ncw = Array(nco2) { floatArrayOf() }
            val nco3 = IntArray(nco2)
            var q = 0
            for (f in 0 until lvl.nF) {
                val s = lvl.fStart[f]; val n = lvl.fSize[f]
                val cw0 = Array(n) { cornerW[s + it] }
                val center = FloatArray(cw0[0].size).also { a -> for (k in 0 until n) for (j in a.indices) a[j] += cw0[k][j] / n }
                for (k in 0 until n) {
                    val o = q * 4
                    val wv = cw0[k]
                    val we = FloatArray(wv.size) { (wv[it] + cw0[(k + 1) % n][it]) * 0.5f }
                    val wp = FloatArray(wv.size) { (wv[it] + cw0[(k + n - 1) % n][it]) * 0.5f }
                    ncw[o] = wv; ncw[o + 1] = we; ncw[o + 2] = center; ncw[o + 3] = wp
                    for (j in 0 until 4) nco3[o + j] = cornerOrigin[s]
                    q++
                }
            }
            if (greg != null) {
                val npf = IntArray(c.fVerts.size)
                val npu = FloatArray(c.fVerts.size * 2)
                q = 0
                for (f in 0 until lvl.nF) {
                    val s = lvl.fStart[f]; val n = lvl.fSize[f]
                    var cu = 0f; var cv = 0f
                    for (k in 0 until n) { cu += pUv[(s + k) * 2]; cv += pUv[(s + k) * 2 + 1] }
                    cu /= n; cv /= n
                    for (k in 0 until n) {
                        val o = q * 4
                        val a = s + k; val b = s + (k + 1) % n; val pr = s + (k + n - 1) % n
                        for (j in 0 until 4) npf[o + j] = pFace[a]
                        npu[o * 2] = pUv[a * 2]; npu[o * 2 + 1] = pUv[a * 2 + 1]
                        npu[o * 2 + 2] = (pUv[a * 2] + pUv[b * 2]) * 0.5f; npu[o * 2 + 3] = (pUv[a * 2 + 1] + pUv[b * 2 + 1]) * 0.5f
                        npu[o * 2 + 4] = cu; npu[o * 2 + 5] = cv
                        npu[o * 2 + 6] = (pUv[a * 2] + pUv[pr * 2]) * 0.5f; npu[o * 2 + 7] = (pUv[a * 2 + 1] + pUv[pr * 2 + 1]) * 0.5f
                        q++
                    }
                }
                pFace = npf
                pUv = npu
            }
            if (track) {
                val nfp = IntArray(c.nF)
                val ncu = FloatArray(c.fVerts.size * 2)
                q = 0
                for (f in 0 until lvl.nF) {
                    val s0 = lvl.fStart[f]; val n = lvl.fSize[f]
                    var cu = 0f; var cv = 0f
                    for (k in 0 until n) { cu += cUv[(s0 + k) * 2]; cv += cUv[(s0 + k) * 2 + 1] }
                    cu /= n; cv /= n
                    for (k in 0 until n) {
                        val o = q * 4
                        if (fPtex[f] < 0) {
                            nfp[q] = ptexOff[f] + k
                            ncu[o * 2] = 0f; ncu[o * 2 + 1] = 0f; ncu[o * 2 + 2] = 1f; ncu[o * 2 + 3] = 0f
                            ncu[o * 2 + 4] = 1f; ncu[o * 2 + 5] = 1f; ncu[o * 2 + 6] = 0f; ncu[o * 2 + 7] = 1f
                        } else {
                            nfp[q] = fPtex[f]
                            val a = s0 + k; val b = s0 + (k + 1) % n; val pr = s0 + (k + n - 1) % n
                            ncu[o * 2] = cUv[a * 2]; ncu[o * 2 + 1] = cUv[a * 2 + 1]
                            ncu[o * 2 + 2] = (cUv[a * 2] + cUv[b * 2]) * 0.5f; ncu[o * 2 + 3] = (cUv[a * 2 + 1] + cUv[b * 2 + 1]) * 0.5f
                            ncu[o * 2 + 4] = cu; ncu[o * 2 + 5] = cv
                            ncu[o * 2 + 6] = (cUv[a * 2] + cUv[pr * 2]) * 0.5f; ncu[o * 2 + 7] = (cUv[a * 2 + 1] + cUv[pr * 2 + 1]) * 0.5f
                        }
                        q++
                    }
                }
                val neo = IntArray(c.nE) { e -> val h = r.childEdgeParentHalf[e]; if (h >= 0) eOrig[h] else -1 }
                val nvo = IntArray(c.nV) { -1 }
                for (e in 0 until lvl.nE) nvo[r.edgeChild[e]] = eOrig[e]
                for (v in 0 until lvl.nV) nvo[r.vertChild[v]] = vOrigE[v]
                fPtex = nfp; cUv = ncu; eOrig = neo; vOrigE = nvo
                if (step < levels) { outPtex = fPtex; outUv = cUv; outVOrigE = vOrigE; outCoarse = coarseIdx }
            }
            val nfo = IntArray(c.nF)
            q = 0
            for (f in 0 until lvl.nF) for (k in 0 until lvl.fSize[f]) nfo[q++] = faceOrigin[f]
            val lm = levelMap
            levelMap = if (step < levels) null else (lm?.let { m -> IntArray(m.size) { r.vertChild[m[it]] } } ?: IntArray(lvl.nV) { r.vertChild[it] })
            if (step == levels - 1) levelMap = IntArray(c.nV) { it }
            co = nco
            lvl = c
            faceOrigin = nfo
            vWeights = nvw
            vertFace = nvf
            cornerW = ncw
            cornerOrigin = nco3
            if (step == levels - 1 && target > levels) {
                snapshot = Snapshot(lvl, co.copyOf(), fvar?.let { FVar(it.values.copyOf(), it.corner.copyOf()) }, faceOrigin.copyOf(), vWeights.copyOf(), vertFace.copyOf(), cornerW.copyOf(), cornerOrigin.copyOf())
            }
        }
        val snap = snapshot
        snapshot = null
        val outLevel = snap?.level ?: lvl
        var outCo = snap?.co ?: co
        var outFvar = snap?.fvar ?: fvar
        val map = levelMap
        if (useLimit) {
            val limitCo = limitPositions(lvl, co, semiAsSmooth)
            if (boundary == BOUNDARY_EDGE_ONLY) {
                for (v in 0 until lvl.nV) {
                    if (!(lvl.valenceF(v) == 1 && lvl.valenceE(v) == 2 && lvl.vSharp[v] < INF && lvl.vBoundary[v] && !lvl.vNonManifold[v])) continue
                    val ea = lvl.vEdges[lvl.vEdgeStart[v]]
                    val eb = lvl.vEdges[lvl.vEdgeStart[v] + 1]
                    if (lvl.eSharp[ea] < INF || lvl.eSharp[eb] < INF) continue
                    val a = lvl.other(ea, v)
                    val b = lvl.other(eb, v)
                    var pv = FloatArray(3) { co[v * 3 + it] }
                    var pa = FloatArray(3) { co[a * 3 + it] }
                    var pb = FloatArray(3) { co[b * 3 + it] }
                    for (k in target until patchLevel) {
                        val nv = FloatArray(3) { d -> pv[d] * 0.75f + pa[d] * 0.125f + pb[d] * 0.125f }
                        pa = FloatArray(3) { d -> pv[d] * 0.5f + pa[d] * 0.5f }
                        pb = FloatArray(3) { d -> pv[d] * 0.5f + pb[d] * 0.5f }
                        pv = nv
                    }
                    pv.copyInto(limitCo, v * 3)
                }
            }
            val g = greg
            if (g != null) {
                val pt = DoubleArray(3)
                for (c in lvl.fVerts.indices) {
                    val gp = g[pFace[c]] ?: continue
                    evalGregory(gp, 3, pUv[c * 2].toDouble(), pUv[c * 2 + 1].toDouble(), pt)
                    val v = lvl.fVerts[c]
                    for (d in 0 until 3) limitCo[v * 3 + d] = pt[d].toFloat()
                }
            }
            outCo = if (snap != null && map != null) FloatArray(outLevel.nV * 3) { i -> limitCo[map[i / 3] * 3 + i % 3] } else limitCo
            if (multires != null) {
                val ev = PtexEval(lvl, co, fPtex, cUv, target, greg, pFace, pUv)
                outCo = displaceMultires(multires, lvl0, ptexOff, outLevel, outCo, outPtex, outUv, outVOrigE, outCoarse, if (snap != null) snap.faceOrigin else faceOrigin, ev)
            }
            val fv = fvar
            if (fv != null) {
                val lf = limitFVar(lvl, fv, fvarMode, semiAsSmooth, patchLevel - target, greg != null)
                val fg = fgreg
                if (fg != null) {
                    val pt = DoubleArray(2)
                    for (c in lvl.fVerts.indices) {
                        val gp = fg[pFace[c]] ?: continue
                        evalGregory(gp, 2, pUv[c * 2].toDouble(), pUv[c * 2 + 1].toDouble(), pt)
                        val vi = lf.corner[c]
                        lf.values[vi * 2] = pt[0].toFloat(); lf.values[vi * 2 + 1] = pt[1].toFloat()
                    }
                }
                outFvar = if (snap != null && map != null) mapFVarToLevel(outLevel, snap.fvar!!, lf, target - levels) else lf
            }
        }
        val o = snap ?: Snapshot(lvl, co, fvar, faceOrigin, vWeights, vertFace, cornerW, cornerOrigin)
        val flatW = FloatArray(0)
        return SubdivResult(
            outCo, outLevel.fStart, outLevel.fSize, outLevel.fVerts, o.faceOrigin,
            outFvar?.let { fv -> FloatArray(outLevel.fVerts.size * 2) { i -> fv.values[fv.corner[i / 2] * 2 + i % 2] } },
            o.vertFace, packWeights(o.vWeights), o.cornerOrigin, packWeights(o.cornerW),
            outLevel.eMark?.let { em -> HashSet<Long>().also { set -> for (e in em.indices) if (em[e]) set.add(key(outLevel.eVerts[e * 2], outLevel.eVerts[e * 2 + 1])) } } ?: emptySet()
        ).also { lastCornerWeights = o.cornerW; lastVertWeights = o.vWeights; lastCoarseIndex = coarseIdx }
    }

    private class PtexEval(val l: Level, val co: FloatArray, val fPtex: IntArray, val cUv: FloatArray, val depth: Int, val greg: Array<DoubleArray?>?, val pFace: IntArray, val pUv: FloatArray) {
        val cell = HashMap<Long, Int>()
        val cache = HashMap<Int, DoubleArray?>()
        var topo: PatchTopo? = null
        init {
            for (f in 0 until l.nF) {
                val p = fPtex[f]
                if (p < 0) continue
                val s = l.fStart[f]
                var mu = Float.MAX_VALUE; var mv = Float.MAX_VALUE; var xu = -Float.MAX_VALUE
                for (k in 0 until 4) { mu = minOf(mu, cUv[(s + k) * 2]); mv = minOf(mv, cUv[(s + k) * 2 + 1]); xu = maxOf(xu, cUv[(s + k) * 2]) }
                val n = Math.round(1.0f / (xu - mu))
                cell[(p.toLong() shl 40) or (Math.round(mu * n).toLong() shl 20) or Math.round(mv * n).toLong()] = f
            }
        }
    }

    private fun ptexCells(ev: PtexEval, f: Int): Int {
        val s = ev.l.fStart[f]
        var mu = Float.MAX_VALUE; var xu = -Float.MAX_VALUE
        for (k in 0 until 4) { mu = minOf(mu, ev.cUv[(s + k) * 2]); xu = maxOf(xu, ev.cUv[(s + k) * 2]) }
        return Math.round(1.0f / (xu - mu))
    }

    private fun evalPtex(ev: PtexEval, ptex: Int, u: Float, v: Float, outP: DoubleArray, du: DoubleArray, dv: DoubleArray): Boolean {
        val l = ev.l
        var f = -1
        for (n in intArrayOf(1 shl ev.depth, 1 shl (ev.depth - 1))) {
            val i = minOf((u * n).toInt(), n - 1)
            val j = minOf((v * n).toInt(), n - 1)
            val cand = ev.cell[(ptex.toLong() shl 40) or (i.toLong() shl 20) or j.toLong()]
            if (cand != null && ptexCells(ev, cand) == n) { f = cand; break }
        }
        if (f < 0) return false
        val s = l.fStart[f]
        val u0 = ev.cUv[s * 2].toDouble(); val v0 = ev.cUv[s * 2 + 1].toDouble()
        val au = ev.cUv[(s + 1) * 2] - ev.cUv[s * 2].toDouble(); val av = ev.cUv[(s + 1) * 2 + 1] - ev.cUv[s * 2 + 1].toDouble()
        val bu = ev.cUv[(s + 3) * 2] - ev.cUv[s * 2].toDouble(); val bv = ev.cUv[(s + 3) * 2 + 1] - ev.cUv[s * 2 + 1].toDouble()
        val det = au * bv - av * bu
        val ru = u - u0; val rv = v - v0
        var ls = (ru * bv - rv * bu) / det
        var lt = (au * rv - av * ru) / det
        val dsdu = bv / det; val dsdv = -bu / det
        val dtdu = -av / det; val dtdv = au / det
        var patch: DoubleArray?
        var ps = ls; var pt = lt
        var pdsds = 1.0; var pdsdt = 0.0; var pdtds = 0.0; var pdtdt = 1.0
        val g = ev.greg
        val gp = if (g != null && ev.pFace.isNotEmpty()) g[ev.pFace[s]] else null
        if (gp != null) {
            patch = gp
            val p0s = ev.pUv[s * 2].toDouble(); val p0t = ev.pUv[s * 2 + 1].toDouble()
            val p1s = ev.pUv[(s + 1) * 2] - p0s; val p1t = ev.pUv[(s + 1) * 2 + 1] - p0t
            val p3s = ev.pUv[(s + 3) * 2] - p0s; val p3t = ev.pUv[(s + 3) * 2 + 1] - p0t
            ps = p0s + ls * p1s + lt * p3s
            pt = p0t + ls * p1t + lt * p3t
            pdsds = p1s; pdsdt = p3s; pdtds = p1t; pdtdt = p3t
        } else {
            patch = ev.cache.getOrPut(f) {
                val t = ev.topo ?: run {
                    val tags = Array(l.nV) { vertexTags(l, it) }
                    PatchTopo({ ff, k -> tags[l.fVerts[l.fStart[ff] + k]] }, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] != 2 || l.eSharp[e] >= INF }, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] < 2 }, { ff, k -> l.fVerts[l.fStart[ff] + k] }).also { ev.topo = it }
                }
                gregoryFace(l, f, ev.co, 3, t)
            }
        }
        if (patch == null) return false
        ps = ps.coerceIn(0.0, 1.0); pt = pt.coerceIn(0.0, 1.0)
        evalGregory(patch, 3, ps, pt, outP)
        val gs = DoubleArray(3); val gt = DoubleArray(3)
        gregoryDeriv(patch, ps, pt, gs, gt)
        for (d in 0 until 3) {
            val dls = gs[d] * pdsds + gt[d] * pdtds
            val dlt = gs[d] * pdsdt + gt[d] * pdtdt
            du[d] = dls * dsdu + dlt * dtdu
            dv[d] = dls * dsdv + dlt * dtdv
        }
        return true
    }

    private fun evalLimitDeriv(ev: PtexEval, ptex: Int, u: Float, v: Float, outP: DoubleArray, du: DoubleArray, dv: DoubleArray): Boolean {
        if (!evalPtex(ev, ptex, u, v, outP, du, dv)) return false
        if (degenerateDeriv(du, dv)) {
            return evalPtex(ev, ptex, u * 0.999f + 0.0005f, v * 0.999f + 0.0005f, outP, du, dv)
        }
        return true
    }

    private fun bezierD(t: Double, w: DoubleArray) {
        val tc = 1.0 - t
        w[0] = -3.0 * tc * tc
        w[1] = 3.0 * tc * tc - 6.0 * tc * t
        w[2] = 6.0 * t * tc - 3.0 * t * t
        w[3] = 3.0 * t * t
    }

    private fun gregoryDeriv(pts: DoubleArray, s: Double, t: Double, ds: DoubleArray, dt: DoubleArray) {
        val bs = DoubleArray(4); val bt = DoubleArray(4); val dbs = DoubleArray(4); val dbt = DoubleArray(4)
        bezier(s, bs); bezier(t, bt); bezierD(s, dbs); bezierD(t, dbt)
        val sc = 1.0 - s
        val tc = 1.0 - t
        fun inv(x: Double) = if (x <= 0.0) 1.0 else 1.0 / x
        val d0 = inv(s + t); val d1 = inv(sc + t); val d2 = inv(sc + tc); val d3 = inv(s + tc)
        val g = doubleArrayOf(s * d0, 1.0 - s * d0, t * d1, 1.0 - t * d1, sc * d2, 1.0 - sc * d2, tc * d3, 1.0 - tc * d3)
        val ws = DoubleArray(20); val wt = DoubleArray(20)
        for (i in 0 until 12) { ws[gBoundary[i]] = dbs[gBoundaryS[i]] * bt[gBoundaryT[i]]; wt[gBoundary[i]] = bs[gBoundaryS[i]] * dbt[gBoundaryT[i]] }
        for (i in 0 until 8) { ws[gInterior[i]] = dbs[gInteriorS[i]] * bt[gInteriorT[i]] * g[i]; wt[gInterior[i]] = bs[gInteriorS[i]] * dbt[gInteriorT[i]] * g[i] }
        ds.fill(0.0); dt.fill(0.0)
        for (i in 0 until 20) for (d in 0 until 3) { ds[d] += pts[i * 3 + d] * ws[i]; dt[d] += pts[i * 3 + d] * wt[i] }
    }

    private fun degenerateDeriv(du: DoubleArray, dv: DoubleArray): Boolean {
        val a = FloatArray(3) { du[it].toFloat() }
        val b = FloatArray(3) { dv[it].toFloat() }
        val za = a.all { it == 0f }
        val zb = b.all { it == 0f }
        return za || zb || (a[0] == b[0] && a[1] == b[1] && a[2] == b[2])
    }

    private fun tangentMatrix(du: DoubleArray, dv: DoubleArray, corner: Int): Array<FloatArray> {
        val fu = floatArrayOf(du[0].toFloat(), du[1].toFloat(), du[2].toFloat())
        val fv = floatArrayOf(dv[0].toFloat(), dv[1].toFloat(), dv[2].toFloat())
        val m0: FloatArray; val m1: FloatArray
        when (corner) {
            0 -> { m0 = floatArrayOf(-fv[0], -fv[1], -fv[2]); m1 = floatArrayOf(-fu[0], -fu[1], -fu[2]) }
            1 -> { m0 = fu.copyOf(); m1 = floatArrayOf(-fv[0], -fv[1], -fv[2]) }
            2 -> { m0 = fv.copyOf(); m1 = fu.copyOf() }
            else -> { m0 = floatArrayOf(-fu[0], -fu[1], -fu[2]); m1 = fv.copyOf() }
        }
        val m2 = floatArrayOf(fu[1] * fv[2] - fu[2] * fv[1], fu[2] * fv[0] - fu[0] * fv[2], fu[0] * fv[1] - fu[1] * fv[0])
        for (m in arrayOf(m0, m1, m2)) {
            val d = m[0] * m[0] + m[1] * m[1] + m[2] * m[2]
            if (d > 1.0e-35f) { val inv = 1.0f / kotlin.math.sqrt(d); m[0] *= inv; m[1] *= inv; m[2] *= inv } else { m[0] = 0f; m[1] = 0f; m[2] = 0f }
        }
        return arrayOf(m0, m1, m2)
    }

    private fun displaceMultires(md: MultiresDisp, l0: Level, ptexOff: IntArray, out: Level, outCo: FloatArray, oPtex: IntArray, oUv: FloatArray, oVOrigE: IntArray, oCoarse: IntArray, oFaceOrigin: IntArray, ev: PtexEval): FloatArray {
        val nF0 = l0.nF
        val ptexFace = IntArray(ptexOff.lastOrNull()?.let { it + (if (l0.fSize[nF0 - 1] == 4) 1 else l0.fSize[nF0 - 1]) } ?: 0)
        val ptexCorner = IntArray(ptexFace.size)
        for (f in 0 until nF0) { if (l0.fSize[f] == 4) { ptexFace[ptexOff[f]] = f; ptexCorner[ptexOff[f]] = 0 } else for (k in 0 until l0.fSize[f]) { ptexFace[ptexOff[f] + k] = f; ptexCorner[ptexOff[f] + k] = k } }
        val gs = md.gridSize
        fun readGrid(grid: FloatArray?, gu: Float, gv: Float, r: FloatArray): Int {
            if (grid == null) { r.fill(0f); return 0 }
            val x = Math.round(gu * (gs - 1)); val y = Math.round(gv * (gs - 1))
            val o = (y * gs + x) * 3
            r[0] = grid[o]; r[1] = grid[o + 1]; r[2] = grid[o + 2]
            return if (x == 0 && y == 0) 1 else if (x == 0) 2 else if (y == 0) 3 else 0
        }
        fun mulM(m: Array<FloatArray>, t: FloatArray) = floatArrayOf(m[0][0] * t[0] + m[1][0] * t[1] + m[2][0] * t[2], m[0][1] * t[0] + m[1][1] * t[1] + m[2][1] * t[2], m[0][2] * t[0] + m[1][2] * t[1] + m[2][2] * t[2])
        val pP = DoubleArray(3); val pU = DoubleArray(3); val pV = DoubleArray(3)
        fun readObject(grid: FloatArray?, gu: Float, gv: Float, ptex: Int, corner: Int): FloatArray {
            val f = ptexFace[ptex]
            val n = l0.fSize[f]
            val u: Float; val v: Float
            if (n == 4) {
                when (corner) {
                    0 -> { u = 0.5f - gv * 0.5f; v = 0.5f - gu * 0.5f }
                    1 -> { u = 0.5f + gu * 0.5f; v = 0.5f - gv * 0.5f }
                    2 -> { u = 0.5f + gv * 0.5f; v = 0.5f + gu * 0.5f }
                    else -> { u = 0.5f - gu * 0.5f; v = 0.5f + gv * 0.5f }
                }
            } else { u = 1.0f - gv; v = 1.0f - gu }
            if (!evalLimitDeriv(ev, ptex, u, v, pP, pU, pV)) { pU.fill(0.0); pV.fill(0.0) }
            val m = tangentMatrix(pU, pV, if (n == 4) corner else 0)
            val t = FloatArray(3)
            readGrid(grid, gu, gv, t)
            return mulM(m, t)
        }
        fun averageWithOther(ptex: Int, corner: Int, gu: Float, gv: Float, delta: Int, r: FloatArray) {
            val f = ptexFace[ptex]
            val n = l0.fSize[f]
            val eff = if (n == 4) corner else ptexCorner[ptex]
            val nextCorner = (eff + delta + n) % n
            val grid = md.grids.getOrNull(l0.fStart[f] + nextCorner)
            val oc = (corner + delta + n) % n
            val op = if (n == 4) ptexOff[f] else ptexOff[f] + oc
            val o = readObject(grid, gu, gv, op, oc)
            for (d in 0 until 3) r[d] = (r[d] + o[d]) * 0.5f
        }
        fun displacement(ptex: Int, u: Float, v: Float, du: DoubleArray, dv: DoubleArray): FloatArray {
            val f = ptexFace[ptex]
            val n = l0.fSize[f]
            var quadCorner = 0
            val gu: Float; val gv: Float
            val grid: FloatArray?
            if (n == 4) {
                val cu: Float; val cv: Float
                if (u <= 0.5f && v <= 0.5f) { quadCorner = 0; cu = 2.0f * u; cv = 2.0f * v }
                else if (u > 0.5f && v <= 0.5f) { quadCorner = 1; cu = 2.0f * v; cv = 2.0f * (1.0f - u) }
                else if (u > 0.5f && v > 0.5f) { quadCorner = 2; cu = 2.0f * (1.0f - u); cv = 2.0f * (1.0f - v) }
                else { quadCorner = 3; cu = 2.0f * (1.0f - v); cv = 2.0f * u }
                gu = 1.0f - cv; gv = 1.0f - cu
                grid = md.grids.getOrNull(l0.fStart[f] + quadCorner)
            } else {
                gu = 1.0f - v; gv = 1.0f - u
                grid = md.grids.getOrNull(l0.fStart[f] + ptexCorner[ptex])
            }
            val t = FloatArray(3)
            val avg = readGrid(grid, gu, gv, t)
            val r = mulM(tangentMatrix(du, dv, quadCorner), t)
            val corner = if (n == 4) quadCorner else ptexCorner[ptex]
            when (avg) {
                1 -> for (delta in 1 until n) averageWithOther(ptex, corner, 0f, 0f, delta, r)
                2 -> averageWithOther(ptex, corner, gv, 0f, -1, r)
                3 -> averageWithOther(ptex, corner, 0f, gu, 1, r)
            }
            return r
        }
        val vertCorners = Array(l0.nV) { ArrayList<Int>(4) }
        val edgeCorners = Array(l0.nE) { ArrayList<Int>(2) }
        for (c in l0.fVerts.indices) { vertCorners[l0.fVerts[c]].add(c); edgeCorners[l0.fEdges[c]].add(c) }
        val coarseOf = IntArray(out.nV) { -1 }
        for (v in oCoarse.indices) if (oCoarse[v] in 0 until out.nV) coarseOf[oCoarse[v]] = v
        val cand = Array(out.nV) { ArrayList<FloatArray>(2) }
        for (f in 0 until out.nF) for (k in 0 until out.fSize[f]) {
            val c = out.fStart[f] + k
            cand[out.fVerts[c]].add(floatArrayOf(oPtex[f].toFloat(), oUv[c * 2], oUv[c * 2 + 1], oFaceOrigin[f].toFloat()))
        }
        val res = outCo.copyOf()
        val P = DoubleArray(3); val du = DoubleArray(3); val dv = DoubleArray(3)
        val wts = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        fun accumulate(ptex: Int, u: Float, w: Float, acc: FloatArray) {
            if (!evalLimitDeriv(ev, ptex, u, w, P, du, dv)) { du.fill(0.0); dv.fill(0.0) }
            val dsp = displacement(ptex, u, w, du, dv)
            for (d in 0 until 3) acc[d] += dsp[d]
        }
        for (v in 0 until out.nV) {
            val cs = cand[v]
            if (cs.isEmpty()) continue
            val cv0 = coarseOf[v]
            val eo = oVOrigE[v]
            if (cv0 >= 0 || eo >= 0) {
                val acc = FloatArray(3)
                var count = 0
                if (cv0 >= 0) {
                    for (c in vertCorners[cv0]) {
                        val f = cornerFaceOf(l0, c)
                        val k = c - l0.fStart[f]
                        if (l0.fSize[f] == 4) accumulate(ptexOff[f], wts[k][0], wts[k][1], acc) else accumulate(ptexOff[f] + k, 0f, 0f, acc)
                        count++
                    }
                } else {
                    for (c in edgeCorners[eo]) {
                        val f = cornerFaceOf(l0, c)
                        val k = c - l0.fStart[f]
                        val n = l0.fSize[f]
                        val inF = cs.filter { it[3].toInt() == f }
                        val pick = if (n == 4) inF.firstOrNull() else (inF.firstOrNull { it[0].toInt() == ptexOff[f] + k && it[2] == 0f } ?: inF.firstOrNull { it[0].toInt() == ptexOff[f] + (k + 1) % n })
                        if (pick == null) continue
                        accumulate(pick[0].toInt(), pick[1], pick[2], acc)
                        count++
                    }
                }
                if (count > 0) { val inv = 1.0f / count; for (d in 0 until 3) res[v * 3 + d] += acc[d] * inv }
                continue
            }
            val f = cs[0][3].toInt()
            val n = l0.fSize[f]
            val pick = if (n == 4) cs[0] else (cs.firstOrNull { it[1] == 1f && it[2] == 1f }?.let { floatArrayOf(ptexOff[f].toFloat(), 1f, 1f) } ?: cs.firstOrNull { it[2] < 1f } ?: cs[0])
            val ptex = pick[0].toInt(); val u = pick[1]; val w = pick[2]
            if (!evalPtex(ev, ptex, u, w, P, du, dv)) continue
            if (degenerateDeriv(du, dv)) {
                if (evalPtex(ev, ptex, u * 0.999f + 0.0005f, w * 0.999f + 0.0005f, P, du, dv)) for (d in 0 until 3) res[v * 3 + d] = P[d].toFloat()
            }
            val dsp = displacement(ptex, u, w, du, dv)
            for (d in 0 until 3) res[v * 3 + d] += dsp[d]
        }
        return res
    }

    var lastCoarseIndex = IntArray(0)
    var lastCornerWeights: Array<FloatArray> = emptyArray()
    var lastVertWeights: Array<FloatArray> = emptyArray()

    private fun packWeights(w: Array<FloatArray>): FloatArray {
        var n = 0
        for (a in w) n += a.size
        val out = FloatArray(n)
        var o = 0
        for (a in w) { a.copyInto(out, o); o += a.size }
        return out
    }

    private class Snapshot(val level: Level, val co: FloatArray, val fvar: FVar?, val faceOrigin: IntArray, val vWeights: Array<FloatArray>, val vertFace: IntArray, val cornerW: Array<FloatArray>, val cornerOrigin: IntArray)

    private var snapshot: Snapshot? = null

    private fun cornerFaceOf(l: Level, c: Int): Int {
        var lo = 0
        var hi = l.nF - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (l.fStart[mid] <= c) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun limitPositions(l: Level, co: FloatArray, semiAsSmooth: Boolean = false, gregoryVal2: Boolean = true): FloatArray {
        val out = FloatArray(co.size)
        val infOnly = { e: Int -> if (l.eSharp[e] >= INF) INF else 0f }
        for (v in 0 until l.nV) {
            val acc = FloatArray(3)
            val rule = if (semiAsSmooth) l.rule(if (l.vSharp[v] >= INF) INF else 0f, infOnly, v) else l.vRule[v]
            val sharpOf: (Int) -> Float = if (semiAsSmooth) infOnly else { e -> l.eSharp[e] }
            when (rule) {
                SMOOTH, DART -> {
                    val n = l.valenceF(v)
                    val val2Interior = gregoryVal2 && n == 2 && l.valenceE(v) == 2 && !l.vBoundary[v] && !l.vNonManifold[v]
                    if (!val2Interior && (n == 2 || n == 0 || n != l.valenceE(v))) for (d in 0 until 3) acc[d] = co[v * 3 + d] else {
                        val fw: Float
                        val ew: Float
                        val vw: Float
                        if (val2Interior) {
                            val nn = 2.0
                            vw = (nn / (nn + 5.0)).toFloat(); ew = (4.0 / (nn * (nn + 5.0))).toFloat(); fw = (1.0 / (nn * (nn + 5.0))).toFloat()
                        } else if (n == 4) { fw = (1.0 / 36.0).toFloat(); ew = (1.0 / 9.0).toFloat(); vw = (4.0 / 9.0).toFloat() } else {
                            val nn = n.toFloat()
                            fw = 1.0f / (nn * (nn + 5.0f)); ew = 4.0f * fw; vw = 1.0f - nn * (ew + fw)
                        }
                        for (d in 0 until 3) acc[d] = co[v * 3 + d] * vw
                        for (i in l.vEdgeStart[v] until l.vEdgeStart[v + 1]) { val u = l.other(l.vEdges[i], v); for (d in 0 until 3) acc[d] += co[u * 3 + d] * ew }
                        for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) {
                            val f = l.vFaces[i]
                            val c = l.vFaceCorner[i] - l.fStart[f]
                            val opp = l.fVerts[l.fStart[f] + (c + 2) % l.fSize[f]]
                            for (d in 0 until 3) acc[d] += co[opp * 3 + d] * fw
                        }
                    }
                }
                CREASE -> {
                    val ce = creaseEnds(l, v, sharpOf)
                    val a = l.other(ce[0], v); val b = l.other(ce[1], v)
                    val vw = (2.0 / 3.0).toFloat(); val ew = (1.0 / 6.0).toFloat()
                    for (d in 0 until 3) acc[d] = co[v * 3 + d] * vw + co[a * 3 + d] * ew + co[b * 3 + d] * ew
                }
                else -> for (d in 0 until 3) acc[d] = co[v * 3 + d]
            }
            if (semiAsSmooth && rule != CORNER && !l.vNonManifold[v] && l.vRule[v] != DART) {
                val t = vertexTags(l, v)
                if (t.infSharpEdges && !t.infSharpCrease) for (d in 0 until 3) acc[d] = co[v * 3 + d]
            }
            acc.copyInto(out, v * 3)
        }
        return out
    }

    private fun flattenForPatches(l: Level, boundary: Int) {
        for (e in 0 until l.nE) if (l.eSharp[e] < INF) l.eSharp[e] = 0f
        for (v in 0 until l.nV) {
            if (l.vSharp[v] < INF) l.vSharp[v] = 0f
            if (boundary == BOUNDARY_EDGE_ONLY && l.valenceF(v) == 1 && l.valenceE(v) == 2 && l.vBoundary[v] && !l.vNonManifold[v]) l.vSharp[v] = INF
        }
        for (v in 0 until l.nV) l.vRule[v] = l.rule(l.vSharp[v], { l.eSharp[it] }, v)
    }

    private class VTags(val boundary: Boolean, val nonManifold: Boolean, val infSharp: Boolean, val infSharpEdges: Boolean, val corner: Boolean, val xordinary: Boolean, val infSharpCrease: Boolean, val infIrregular: Boolean, val dart: Boolean = false, val mismatch: Boolean = false)

    private class PatchTopo(val tag: (Int, Int) -> VTags, val singular: (Int) -> Boolean, val boundary: (Int) -> Boolean, val point: (Int, Int) -> Int)

    private fun edgeOfFace(l: Level, f: Int, k: Int) = l.fEdges[l.fStart[f] + ((k % l.fSize[f]) + l.fSize[f]) % l.fSize[f]]

    private fun localIndex(l: Level, f: Int, v: Int): Int {
        for (k in 0 until l.fSize[f]) if (l.fVerts[l.fStart[f] + k] == v) return k
        return -1
    }

    private fun acrossEdge(l: Level, e: Int, f: Int): Int {
        if (l.eFaceStart[e + 1] - l.eFaceStart[e] != 2) return -1
        val a = l.eFaces[l.eFaceStart[e]]
        val b = l.eFaces[l.eFaceStart[e] + 1]
        return if (a == f) b else a
    }

    private fun vertexTags(l: Level, v: Int): VTags {
        val nF = l.valenceF(v)
        val nE = l.valenceE(v)
        var infCount = 0
        for (i in l.vEdgeStart[v] until l.vEdgeStart[v + 1]) if (l.eSharp[l.vEdges[i]] >= INF) infCount++
        val infSharp = l.vSharp[v] >= INF
        val boundary = l.vBoundary[v]
        val nonManifold = l.vNonManifold[v]
        val corner = nF == 1 && nE == 2 && infSharp
        val xordinary = if (nonManifold || corner) false else if (boundary) nF != 2 else nF != 4
        val infSharpEdges = infCount > 0
        var infSharpCrease = false
        var infIrregular = infSharp || infSharpEdges
        if (infSharpEdges) {
            val infRule = if (infSharp || infCount > 2) CORNER else (1 shl infCount)
            if (infRule == CREASE) {
                infSharpCrease = true
                if (!xordinary && !nonManifold) {
                    if (boundary) infIrregular = false else {
                        val f0 = l.vFaces[l.vFaceStart[v]]
                        val k0 = l.vFaceCorner[l.vFaceStart[v]] - l.fStart[f0]
                        val e0 = edgeOfFace(l, f0, k0)
                        val f1 = acrossEdge(l, edgeOfFace(l, f0, k0 - 1), f0)
                        val e2 = if (f1 < 0) -1 else {
                            val k1 = localIndex(l, f1, v)
                            val f2 = acrossEdge(l, edgeOfFace(l, f1, k1 - 1), f1)
                            if (f2 < 0) -1 else edgeOfFace(l, f2, localIndex(l, f2, v))
                        }
                        infIrregular = e2 < 0 || (l.eSharp[e0] >= INF) != (l.eSharp[e2] >= INF)
                    }
                }
            } else if (infRule == CORNER) {
                if (infCount == nE && (nE > 2 || infSharp)) infIrregular = false
            }
        }
        return VTags(boundary, nonManifold, infSharp, infSharpEdges, corner, xordinary, infSharpCrease, infIrregular, l.vRule[v] == DART)
    }

    private fun singular(l: Level, e: Int) = l.eFaceStart[e + 1] - l.eFaceStart[e] != 2 || l.eSharp[e] >= INF

    private class Span(val startFace: Int, val numFaces: Int, val cornerInSpan: Int)

    private fun manifoldSpan(l: Level, f: Int, v: Int, singular: (Int) -> Boolean): Span? {
        var count = 1
        var before = 0
        var cur = f
        val limit = l.valenceF(v) + 1
        while (true) {
            val lead = edgeOfFace(l, cur, localIndex(l, cur, v))
            if (singular(lead)) break
            val prev = acrossEdge(l, lead, cur)
            if (prev < 0) return null
            if (prev == f) break
            cur = prev
            count++; before++
            if (count > limit) return null
        }
        val start = cur
        cur = f
        while (true) {
            val trail = edgeOfFace(l, cur, localIndex(l, cur, v) - 1)
            if (singular(trail)) break
            val next = acrossEdge(l, trail, cur)
            if (next < 0) return null
            if (next == start) break
            cur = next
            count++
            if (count > limit) return null
        }
        return Span(start, count, before)
    }

    private fun ringFrom(l: Level, v: Int, start: Int, numFaces: Int, periodic: Boolean, point: (Int, Int) -> Int): IntArray? {
        val ring = IntArray(numFaces * 2 + if (periodic) 0 else 1)
        var cur = start
        var o = 0
        for (i in 0 until numFaces) {
            if (l.fSize[cur] != 4) return null
            val k = localIndex(l, cur, v)
            ring[o++] = point(cur, (k + 1) % 4)
            ring[o++] = point(cur, (k + 2) % 4)
            if (i == numFaces - 1) {
                if (!periodic) ring[o++] = point(cur, (k + 3) % 4)
            } else {
                cur = acrossEdge(l, edgeOfFace(l, cur, k - 1), cur)
                if (cur < 0) return null
            }
        }
        return ring
    }

    private fun patchRegular(l: Level, f: Int, topo: PatchTopo): Boolean {
        val s = l.fStart[f]
        var anyInfSharp = false
        var anyInfEdges = false
        var anyX = false
        for (k in 0 until 4) {
            val t = topo.tag(f, k)
            anyInfSharp = anyInfSharp || t.infSharp
            anyInfEdges = anyInfEdges || t.infSharpEdges
            anyX = anyX || t.xordinary
        }
        if (!anyInfSharp && !anyInfEdges) return !anyX
        for (k in 0 until 4) {
            val v = l.fVerts[s + k]
            val t = topo.tag(f, k)
            if (!t.nonManifold && !t.xordinary && !t.infIrregular) continue
            if (t.nonManifold) return false
            if (t.xordinary) {
                if (!t.infSharpEdges) return false
                if (t.boundary && !t.infSharp) {
                    val e0 = l.fEdges[s + k]
                    val e1 = l.fEdges[s + (k + 3) % 4]
                    if (topo.boundary(e0) && topo.boundary(e1)) continue
                }
            }
            if (t.infIrregular) {
                if (!t.infSharpEdges) return false
                if (t.infSharpCrease && t.boundary) return false
                val n = manifoldSpan(l, f, v, topo.singular)?.numFaces ?: return false
                if (t.infSharpCrease) {
                    if (n != 2) return false
                } else if (n != 1) return false
            }
        }
        return true
    }

    private class GCorner(val v: Int, val numFaces: Int, val faceInRing: Int, val boundary: Boolean, val sharp: Boolean, val ring: IntArray) {
        val valence = numFaces + if (boundary) 1 else 0
        val regular = (numFaces shl (if (boundary) 1 else 0)) == 4 && !sharp
        val cosAngle = if (regular) 0.0 else Math.cos((if (boundary) Math.PI else 2.0 * Math.PI) / numFaces)
        var epOnBoundary = false
        var emOnBoundary = false
        var fpRegular = false
        var fmRegular = false
        var fpCopied = false
        var fmCopied = false
    }

    private fun gregoryPatches(l: Level, co: FloatArray): Array<DoubleArray?> {
        val tags = Array(l.nV) { vertexTags(l, it) }
        val topo = PatchTopo({ f, k -> tags[l.fVerts[l.fStart[f] + k]] }, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] != 2 || l.eSharp[e] >= INF }, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] < 2 }, { f, k -> l.fVerts[l.fStart[f] + k] })
        return patchesWith(l, topo, co, 3)
    }

    private fun patchesWith(l: Level, topo: PatchTopo, data: FloatArray, dim: Int): Array<DoubleArray?> {
        val res = arrayOfNulls<DoubleArray>(l.nF)
        for (f in 0 until l.nF) {
            if (l.fSize[f] != 4 || patchRegular(l, f, topo)) continue
            res[f] = gregoryFace(l, f, data, dim, topo)
        }
        return res
    }

    private fun gregoryFace(l: Level, f: Int, data: FloatArray, dim: Int, topo: PatchTopo): DoubleArray? {
        val c = arrayOfNulls<GCorner>(4)
        for (k in 0 until 4) {
            val v = l.fVerts[l.fStart[f] + k]
            val t = topo.tag(f, k)
            if (t.nonManifold) return null
            val testInfEdges = t.infSharpEdges && !t.dart
            val numFaces: Int
            val fir: Int
            val bnd: Boolean
            val ring: IntArray
            if (testInfEdges || t.mismatch) {
                val sp = manifoldSpan(l, f, v, topo.singular) ?: return null
                numFaces = sp.numFaces; fir = sp.cornerInSpan; bnd = true
                ring = ringFrom(l, v, sp.startFace, sp.numFaces, false, topo.point) ?: return null
            } else {
                if (t.boundary) return null
                numFaces = l.valenceF(v); fir = 0; bnd = false
                ring = ringFrom(l, v, f, numFaces, true, topo.point) ?: return null
            }
            var sharp = if (t.corner) (if (t.mismatch) numFaces == 1 else true) else if (testInfEdges) !t.infSharpCrease else t.infSharp
            if (!sharp && t.xordinary && t.boundary && !t.infSharp) sharp = numFaces == 1
            c[k] = GCorner(topo.point(f, k), numFaces, fir, bnd, sharp, ring)
        }
        val cs = Array(4) { c[it]!! }
        for (i in 0 until 4) {
            val cr = cs[i]
            cr.fpRegular = cr.regular && cs[(i + 1) and 3].regular
            cr.fmRegular = cr.regular && cs[(i + 3) and 3].regular
            if (cr.boundary) {
                cr.epOnBoundary = cr.faceInRing == 0
                cr.emOnBoundary = cr.faceInRing == cr.numFaces - 1
                if (cr.numFaces > 1) {
                    if (cr.epOnBoundary) { cr.fpRegular = cr.fmRegular; cr.fpCopied = !cr.fpRegular }
                    if (cr.emOnBoundary) { cr.fmRegular = cr.fpRegular; cr.fmCopied = !cr.fmRegular }
                } else {
                    cr.fpRegular = true; cr.fmRegular = true
                }
            }
        }
        val pts = DoubleArray(20 * dim)
        fun pos(v: Int, d: Int) = data[v * dim + d].toDouble()
        fun put(row: Int, idx: IntArray, w: DoubleArray) {
            for (d in 0 until dim) {
                var a = 0.0
                for (j in idx.indices) a += pos(idx[j], d) * w[j]
                pts[row * dim + d] = a
            }
        }
        val cv = IntArray(4) { cs[it].v }
        for (i in 0 until 4) {
            val cr = cs[i]
            val r = cr.ring
            val v = cr.v
            val nx = cv[(i + 1) and 3]
            val pv = cv[(i + 3) and 3]
            if (cr.regular) {
                if (!cr.boundary) {
                    put(5 * i, intArrayOf(v, r[0], r[2], r[4], r[6], r[1], r[3], r[5], r[7]), doubleArrayOf(4.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0))
                    val iEp = 2 * cr.faceInRing
                    val iEm = 2 * ((cr.faceInRing + 1) and 3)
                    val iOp = 2 * ((cr.faceInRing + 2) and 3)
                    val iOm = 2 * ((cr.faceInRing + 3) and 3)
                    put(5 * i + 1, intArrayOf(v, r[iEp], r[iEm], r[iOm], r[iEp + 1], r[iOm + 1]), doubleArrayOf(4.0 / 9.0, 2.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 18.0, 1.0 / 18.0))
                    put(5 * i + 2, intArrayOf(v, r[iEm], r[iEp], r[iOp], r[iEp + 1], r[iEm + 1]), doubleArrayOf(4.0 / 9.0, 2.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 18.0, 1.0 / 18.0))
                } else {
                    put(5 * i, intArrayOf(v, r[0], r[4]), doubleArrayOf(2.0 / 3.0, 1.0 / 6.0, 1.0 / 6.0))
                    val bRow = if (cr.epOnBoundary) 5 * i + 1 else 5 * i + 2
                    val iRow = if (cr.epOnBoundary) 5 * i + 2 else 5 * i + 1
                    put(bRow, intArrayOf(v, r[if (cr.epOnBoundary) 0 else 4]), doubleArrayOf(2.0 / 3.0, 1.0 / 3.0))
                    put(iRow, intArrayOf(v, r[2], r[0], r[4], r[1], r[3]), doubleArrayOf(4.0 / 9.0, 2.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 18.0, 1.0 / 18.0))
                }
            } else if (cr.sharp) {
                put(5 * i, intArrayOf(v), doubleArrayOf(1.0))
                put(5 * i + 1, intArrayOf(v, nx), doubleArrayOf(2.0 / 3.0, 1.0 / 3.0))
                put(5 * i + 2, intArrayOf(v, pv), doubleArrayOf(2.0 / 3.0, 1.0 / 3.0))
            } else if (!cr.boundary) {
                val w = 1 + 2 * cr.valence
                val pw = DoubleArray(w)
                val ew = DoubleArray(w)
                val mw = DoubleArray(w)
                interiorWeights(cr.valence, cr.faceInRing, pw, ew, mw)
                val idx = IntArray(w) { if (it == 0) v else r[it - 1] }
                put(5 * i, idx, pw)
                put(5 * i + 1, idx, ew)
                put(5 * i + 2, idx, mw)
            } else if (cr.numFaces > 1) {
                val w = 1 + cr.valence + cr.numFaces
                val pw = DoubleArray(w)
                val ew = DoubleArray(w)
                val mw = DoubleArray(w)
                boundaryWeights(cr.valence, cr.faceInRing, pw, ew, mw)
                val n = w - 1
                val pN = r[2 * (cr.valence - 1)]
                val idx = IntArray(w) { if (it == 0) v else r[it - 1] }
                put(5 * i, intArrayOf(v, r[0], pN), doubleArrayOf(pw[0], pw[1], pw[n]))
                if (cr.epOnBoundary) put(5 * i + 1, intArrayOf(v, r[0]), doubleArrayOf(ew[0], ew[1])) else put(5 * i + 1, idx, ew)
                if (cr.emOnBoundary) put(5 * i + 2, intArrayOf(v, pN), doubleArrayOf(mw[0], mw[n])) else put(5 * i + 2, idx, mw)
            } else {
                put(5 * i, intArrayOf(v, nx, pv), doubleArrayOf(4.0 / 6.0, 1.0 / 6.0, 1.0 / 6.0))
                put(5 * i + 1, intArrayOf(v, nx), doubleArrayOf(2.0 / 3.0, 1.0 / 3.0))
                put(5 * i + 2, intArrayOf(v, pv), doubleArrayOf(2.0 / 3.0, 1.0 / 3.0))
            }
        }
        fun facePoint(i: Int, edge: Int, far: Int, eNearRow: Int, eFarRow: Int, sign: Double, row: Int) {
            val cn = cs[i]
            val cf = cs[far]
            val pC = cf.cosAngle / 3.0
            val eN = (3.0 - 2.0 * cn.cosAngle - cf.cosAngle) / 3.0
            val eF = 2.0 * cn.cosAngle / 3.0
            val va = cn.valence
            val iPrev = (edge + va - 1) % va
            val iNext = (edge + 1) % va
            val r = cn.ring
            for (d in 0 until dim) {
                var a = pts[(5 * i) * dim + d] * pC + pts[eNearRow * dim + d] * eN + pts[eFarRow * dim + d] * eF
                a += -sign / 9.0 * pos(r[2 * iPrev], d) - sign / 18.0 * pos(r[2 * iPrev + 1], d) + sign / 18.0 * pos(r[2 * edge + 1], d) + sign / 9.0 * pos(r[2 * iNext], d)
                pts[row * dim + d] = a
            }
        }
        for (i in 0 until 4) {
            val cr = cs[i]
            val nx = (i + 1) and 3
            val op = (i + 2) and 3
            val pv = (i + 3) and 3
            val regIdx = intArrayOf(cv[i], cv[pv], cv[nx], cv[op])
            val regW = doubleArrayOf(4.0 / 9.0, 2.0 / 9.0, 2.0 / 9.0, 1.0 / 9.0)
            if (cr.fpRegular) put(5 * i + 3, regIdx, regW)
            if (cr.fmRegular) put(5 * i + 4, regIdx, regW)
            if (!cr.fpRegular && !cr.fpCopied) facePoint(i, cr.faceInRing, nx, 5 * i + 1, 5 * nx + 2, 1.0, 5 * i + 3)
            if (!cr.fmRegular && !cr.fmCopied) facePoint(i, (cr.faceInRing + 1) % cr.valence, pv, 5 * i + 2, 5 * pv + 1, -1.0, 5 * i + 4)
            if (cr.fpCopied) for (d in 0 until dim) pts[(5 * i + 3) * dim + d] = pts[(5 * i + 4) * dim + d]
            if (cr.fmCopied) for (d in 0 until dim) pts[(5 * i + 4) * dim + d] = pts[(5 * i + 3) * dim + d]
        }
        return pts
    }

    private val tangentScale = doubleArrayOf(
        0.0, 0.0, 0.0,
        8.1281572906372312e-01, 0.5, 3.6364406329142801e-01,
        2.8751379706077085e-01, 2.3868786685851678e-01, 2.0454364190756097e-01,
        1.7922903958061159e-01, 1.5965737079986253e-01, 1.4404233443011302e-01,
        1.3127568415883017e-01, 1.2063172212675841e-01, 1.1161437506676930e-01,
        1.0387245516114274e-01, 9.7150019090724835e-02, 9.1255917505950648e-02,
        8.6044378511602668e-02, 8.1402211336798411e-02, 7.7240129516184072e-02,
        7.3486719751997026e-02, 7.0084157479797987e-02, 6.6985104030725440e-02,
        6.4150420569810074e-02, 6.1547457638637268e-02, 5.9148757447233989e-02,
        5.6931056818776957e-02, 5.4874512279256417e-02, 5.2962091433796134e-02
    )

    private fun tangentCoefficient(valence: Int): Double {
        if (valence < 30) return tangentScale[valence]
        val inv = 1.0 / valence
        val cosT = Math.cos(2.0 * Math.PI * inv)
        return 16.0 * inv / ((cosT + 5.0) + Math.sqrt((cosT + 9.0) * (cosT + 1.0)))
    }

    private fun interiorWeights(valence: Int, faceInRing: Int, p: DoubleArray, ep: DoubleArray, em: DoubleArray) {
        val fv = valence.toDouble()
        val oneOverV5 = 1.0 / (fv + 5.0)
        val pCoeff = (1.0 / fv) * oneOverV5
        val tanCoeff = tangentCoefficient(valence) * 0.5 * oneOverV5
        val angle = 2.0 * Math.PI / fv
        val width = 1 + 2 * valence
        val tw = DoubleArray(width)
        p[0] = fv * oneOverV5
        for (i in 0 until valence) {
            p[1 + 2 * i] = pCoeff * 4.0
            p[2 + 2 * i] = pCoeff
            val iPrev = (i + valence - 1) % valence
            val iNext = (i + 1) % valence
            val c = tanCoeff * Math.cos(angle * i)
            tw[1 + 2 * iPrev] += c * 2.0
            tw[2 + 2 * iPrev] += c
            tw[1 + 2 * i] += c * 4.0
            tw[2 + 2 * i] += c
            tw[1 + 2 * iNext] += c * 2.0
        }
        val epOff = 2 * ((valence - faceInRing) % valence)
        val emOff = 2 * ((valence - faceInRing - 1 + valence) % valence)
        ep[0] = p[0]
        em[0] = p[0]
        for (i in 1 until width) {
            var ip = i + epOff
            if (ip >= width) ip -= width - 1
            var im = i + emOff
            if (im >= width) im -= width - 1
            ep[i] = p[i] + tw[ip]
            em[i] = p[i] + tw[im]
        }
    }

    private fun boundaryWeights(valence: Int, faceInRing: Int, p: DoubleArray, ep: DoubleArray, em: DoubleArray) {
        val numFaces = valence - 1
        val faceAngle = Math.PI / numFaces
        val width = 2 * valence
        val n = width - 1
        java.util.Arrays.fill(p, 0, width, 0.0)
        p[0] = 4.0 / 6.0
        p[1] = 1.0 / 6.0
        p[n] = 1.0 / 6.0
        val tw = DoubleArray(width)
        val k = numFaces.toDouble()
        val c = Math.cos(faceAngle)
        val sn = Math.sin(faceAngle)
        val div3 = 1.0 / 3.0
        val div3kc = 1.0 / (3.0 * k + c)
        val gamma = -4.0 * sn * div3kc
        val alpha0k = -((1.0 + 2.0 * c) * Math.sqrt(1.0 + c)) * div3kc / Math.sqrt(1.0 - c)
        val beta0 = sn * div3kc
        tw[0] = gamma * div3
        tw[1] = alpha0k * div3
        tw[2] = beta0 * div3
        tw[n] = alpha0k * div3
        for (i in 1 until valence - 1) {
            val si = Math.sin(faceAngle * i)
            val si1 = Math.sin(faceAngle * (i + 1))
            tw[1 + 2 * i] = 4.0 * si * div3kc * div3
            tw[2 + 2 * i] = (si + si1) * div3kc * div3
        }
        if (faceInRing == 0) {
            java.util.Arrays.fill(ep, 0, width, 0.0)
            ep[0] = 2.0 / 3.0
            ep[1] = 1.0 / 3.0
        } else {
            val a = faceAngle * faceInRing
            val ca = Math.cos(a)
            val sa = Math.sin(a)
            for (i in 0 until width) ep[i] = tw[i] * sa
            ep[0] += p[0]
            ep[1] += p[1] + ca / 6.0
            ep[n] += p[n] - ca / 6.0
        }
        if (faceInRing == numFaces - 1) {
            java.util.Arrays.fill(em, 0, width, 0.0)
            em[0] = 2.0 / 3.0
            em[n] = 1.0 / 3.0
        } else {
            val a = faceAngle * ((faceInRing + 1) % valence)
            val ca = Math.cos(a)
            val sa = Math.sin(a)
            for (i in 0 until width) em[i] = tw[i] * sa
            em[0] += p[0]
            em[1] += p[1] + ca / 6.0
            em[n] += p[n] - ca / 6.0
        }
    }

    private val gBoundary = intArrayOf(0, 1, 7, 5, 2, 6, 16, 12, 15, 17, 11, 10)
    private val gBoundaryS = intArrayOf(0, 1, 2, 3, 0, 3, 0, 3, 0, 1, 2, 3)
    private val gBoundaryT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 3)
    private val gInterior = intArrayOf(3, 4, 8, 9, 13, 14, 18, 19)
    private val gInteriorS = intArrayOf(1, 1, 2, 2, 2, 2, 1, 1)
    private val gInteriorT = intArrayOf(1, 1, 1, 1, 2, 2, 2, 2)

    private fun bezier(t: Double, w: DoubleArray) {
        val tc = 1.0 - t
        w[0] = tc * tc * tc
        w[1] = tc * tc * t * 3.0
        w[2] = t * t * tc * 3.0
        w[3] = t * t * t
    }

    private fun evalGregory(pts: DoubleArray, dim: Int, s: Double, t: Double, out: DoubleArray) {
        val bs = DoubleArray(4)
        val bt = DoubleArray(4)
        bezier(s, bs)
        bezier(t, bt)
        val sc = 1.0 - s
        val tc = 1.0 - t
        fun inv(x: Double) = if (x <= 0.0) 1.0 else 1.0 / x
        val d0 = inv(s + t)
        val d1 = inv(sc + t)
        val d2 = inv(sc + tc)
        val d3 = inv(s + tc)
        val g = doubleArrayOf(s * d0, 1.0 - s * d0, t * d1, 1.0 - t * d1, sc * d2, 1.0 - sc * d2, tc * d3, 1.0 - tc * d3)
        val w = DoubleArray(20)
        for (i in 0 until 12) w[gBoundary[i]] = bs[gBoundaryS[i]] * bt[gBoundaryT[i]]
        for (i in 0 until 8) w[gInterior[i]] = bs[gInteriorS[i]] * bt[gInteriorT[i]] * g[i]
        out.fill(0.0)
        for (i in 0 until 20) for (d in 0 until dim) out[d] += pts[i * dim + d] * w[i]
    }

    private fun fvarPatches(l: Level, fv: FVar, mode: Int, origin: ByteArray): Array<DoubleArray?> {
        val vt = Array(l.nV) { vertexTags(l, it) }
        val ft = fvarTags(l, fv, mode)
        val combined = HashMap<Long, VTags>()
        fun tagOf(f: Int, k: Int): VTags {
            val v = l.fVerts[l.fStart[f] + k]
            if (!ft.mismatch[v]) return vt[v]
            val value = fv.corner[l.fStart[f] + k]
            return combined.getOrPut((v.toLong() shl 32) or value.toLong()) {
                if (mode == FVAR_BOUNDARIES && origin[v].toInt() == 1) return@getOrPut VTags(true, vt[v].nonManifold, true, true, true, false, false, false, false, true)
                val si = ft.siblings[v].indexOf(value)
                val size = ft.spanFaces[v][si]
                val infCount = ft.infCount[v][si]
                val infSharpV = ft.corner[v][si]
                val xord = if (size == 1) !infSharpV else size != 2
                val infIrr = if (infCount > 0) (size - infCount) > 1 else if (infSharpV) size > 1 else xord
                val crease = !infSharpV
                val base = vt[v]
                VTags(true, base.nonManifold || ft.disjoint[v][si], !crease, true, if (crease) false else !infIrr && infCount == 0, xord, crease, infIrr, false, true)
            }
        }
        val topo = PatchTopo(::tagOf, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] != 2 || l.eSharp[e] >= INF || ft.edgeDisc[e] }, { e -> l.eFaceStart[e + 1] - l.eFaceStart[e] < 2 || ft.edgeDisc[e] }, { f, k -> fv.corner[l.fStart[f] + k] })
        return patchesWith(l, topo, fv.values, 2)
    }

    private class FTags(val siblings: Array<IntArray>, val mismatch: BooleanArray, val corner: Array<BooleanArray>, val creaseEnds: Array<Array<IntArray>?>, val spanFaces: Array<IntArray>, val edgeDisc: BooleanArray, val infCount: Array<IntArray>, val disjoint: Array<BooleanArray>)

    private fun fvarTags(l: Level, fv: FVar, mode: Int, legacy: Boolean = false): FTags {
        val linearBoundaries = mode == FVAR_ALL || mode == FVAR_BOUNDARIES
        val fvarCornersSharp = mode != FVAR_NONE
        val dependent = mode == FVAR_CORNERS_PLUS1 || mode == FVAR_CORNERS_PLUS2
        val sharpenDarts = mode == FVAR_CORNERS_PLUS2 || linearBoundaries
        val siblings = Array(l.nV) { v ->
            val vals = LinkedHashSet<Int>()
            for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) vals.add(fv.corner[l.vFaceCorner[i]])
            vals.toIntArray()
        }
        val mismatch = BooleanArray(l.nV)
        val edgeDisc = BooleanArray(l.nE)
        for (e in 0 until l.nE) {
            val fs = l.eFaceStart[e]; val fe = l.eFaceStart[e + 1]
            if (fe - fs < 2) continue
            val a = l.eVerts[e * 2]; val b = l.eVerts[e * 2 + 1]
            var va = -1; var vb = -1; var disc = false
            for (i in fs until fe) {
                val f = l.eFaces[i]
                val s = l.fStart[f]; val n = l.fSize[f]
                var ca = -1; var cb = -1
                for (k in 0 until n) { if (l.fVerts[s + k] == a) ca = fv.corner[s + k]; if (l.fVerts[s + k] == b) cb = fv.corner[s + k] }
                if (va < 0) { va = ca; vb = cb } else if (ca != va || cb != vb) disc = true
            }
            if (disc) { edgeDisc[e] = true; mismatch[a] = true; mismatch[b] = true }
        }
        for (v in 0 until l.nV) {
            if (siblings[v].size > 1) mismatch[v] = true
            if (l.vBoundary[v] && !mismatch[v]) {
                if (linearBoundaries && l.valenceF(v) > 0) mismatch[v] = true
                else if (l.valenceF(v) == 1 && fvarCornersSharp) mismatch[v] = true
            }
        }
        val corner = Array(l.nV) { v -> BooleanArray(siblings[v].size) }
        val spanFaces = Array(l.nV) { v -> IntArray(siblings[v].size) }
        val infCount = Array(l.nV) { v -> IntArray(siblings[v].size) }
        val disjoint = Array(l.nV) { v -> BooleanArray(siblings[v].size) }
        val ends = arrayOfNulls<Array<IntArray>>(l.nV)
        for (v in 0 until l.nV) {
            if (!mismatch[v]) continue
            val sib = siblings[v]
            var allSharp = linearBoundaries || l.vSharp[v] >= INF || l.vNonManifold[v] || (dependent && sib.size > 2) || (sharpenDarts && sib.size == 1 && !l.vBoundary[v])
            if (!allSharp && dependent && sib.size == 2) {
                val spanF = IntArray(2)
                val spanInf = IntArray(2)
                for ((si, value) in sib.withIndex()) for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) {
                    if (fv.corner[l.vFaceCorner[i]] != value) continue
                    spanF[si]++
                    val f = l.vFaces[i]
                    val c = l.vFaceCorner[i] - l.fStart[f]
                    val n = l.fSize[f]
                    for (eIdx in intArrayOf(l.fEdges[l.fStart[f] + c], l.fEdges[l.fStart[f] + (c + n - 1) % n])) {
                        val nf = l.eFaceStart[eIdx + 1] - l.eFaceStart[eIdx]
                        if (l.eSharp[eIdx] >= INF && nf >= 2 && !edgeDisc[eIdx]) spanInf[si]++
                    }
                }
                allSharp = spanInf[0] > 0 || spanInf[1] > 0
                if (mode == FVAR_CORNERS_PLUS2) allSharp = allSharp || spanF[0] == 1 || spanF[1] == 1
            }
            val e = Array(sib.size) { IntArray(2) { -1 } }
            for ((si, value) in sib.withIndex()) {
                var faces = 0
                val bEdges = ArrayList<Int>()
                var infEdges = 0
                val infSet = HashSet<Int>()
                for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) {
                    if (fv.corner[l.vFaceCorner[i]] != value) continue
                    faces++
                    val f = l.vFaces[i]
                    val c = l.vFaceCorner[i] - l.fStart[f]
                    val n = l.fSize[f]
                    for (eIdx in intArrayOf(l.fEdges[l.fStart[f] + c], l.fEdges[l.fStart[f] + (c + n - 1) % n])) {
                        val nf = l.eFaceStart[eIdx + 1] - l.eFaceStart[eIdx]
                        if (nf < 2 || edgeDisc[eIdx]) {
                            val u = l.other(eIdx, v)
                            val ov = (0 until n).firstOrNull { l.fVerts[l.fStart[f] + it] == u }?.let { fv.corner[l.fStart[f] + it] } ?: -1
                            bEdges.add(ov)
                        }
                        if (l.eSharp[eIdx] >= INF && nf >= 2) infEdges++
                        if (l.eSharp[eIdx] >= INF && nf >= 2 && !edgeDisc[eIdx]) infSet.add(eIdx)
                    }
                }
                infCount[v][si] = infSet.size
                disjoint[v][si] = bEdges.size != 2
                val isCorner = allSharp || infEdges > 0 || (faces == 1 && fvarCornersSharp) || bEdges.size != 2 || (legacy && faces == 1)
                corner[v][si] = isCorner
                spanFaces[v][si] = faces
                if (!isCorner) { e[si][0] = bEdges[0]; e[si][1] = bEdges[1] }
            }
            ends[v] = e
        }
        return FTags(siblings, mismatch, corner, ends, spanFaces, edgeDisc, infCount, disjoint)
    }

    private fun refineFVar(p: Level, c: Level, r: Refined, fv: FVar, mode: Int, legacy: Boolean = false): FVar {
        val tags = fvarTags(p, fv, mode, legacy)
        val linear = mode == FVAR_ALL
        val vals = ArrayList<Float>()
        val corner = IntArray(c.fVerts.size)
        fun addVal(x: Float, y: Float): Int { vals.add(x); vals.add(y); return vals.size / 2 - 1 }
        val faceVal = IntArray(p.nF)
        for (f in 0 until p.nF) {
            var x = 0f; var y = 0f
            val s = p.fStart[f]; val n = p.fSize[f]
            for (k in 0 until n) { val vi = fv.corner[s + k]; x += fv.values[vi * 2]; y += fv.values[vi * 2 + 1] }
            faceVal[f] = addVal(x / n, y / n)
        }
        val edgeVal = HashMap<Long, Int>()
        for (e in 0 until p.nE) {
            val a = p.eVerts[e * 2]; val b = p.eVerts[e * 2 + 1]
            val fs = p.eFaceStart[e]; val fe = p.eFaceStart[e + 1]
            val pairs = ArrayList<Triple<Int, Int, Int>>()
            for (i in fs until fe) {
                val f = p.eFaces[i]
                val s = p.fStart[f]; val n = p.fSize[f]
                var ca = -1; var cb = -1
                for (k in 0 until n) { if (p.fVerts[s + k] == a) ca = fv.corner[s + k]; if (p.fVerts[s + k] == b) cb = fv.corner[s + k] }
                pairs.add(Triple(f, ca, cb))
            }
            val disc = pairs.any { it.second != pairs[0].second || it.third != pairs[0].third }
            if (!disc && !linear) {
                val (_, ca, cb) = pairs[0]
                val sharp = p.eSharp[e]
                val cRule = c.vRule[r.edgeChild[e]]
                val nfc = fe - fs
                val vw: Float
                val fw: Float
                if (sharp <= 0f) { vw = 0.25f; fw = if (nfc == 2) 0.25f else 0.5f / nfc }
                else if (cRule == CREASE) { vw = 0.5f; fw = 0f }
                else { val pw = sharp; val cw = 1f - pw; vw = pw * 0.5f + cw * 0.25f; fw = (if (nfc == 2) 0.25f else 0.5f / nfc) * cw }
                var x = (fv.values[ca * 2] * vw) + (fv.values[cb * 2] * vw)
                var y = (fv.values[ca * 2 + 1] * vw) + (fv.values[cb * 2 + 1] * vw)
                if (fw != 0f) for (pr in pairs) { val fi = faceVal[pr.first]; x += vals[fi * 2] * fw; y += vals[fi * 2 + 1] * fw }
                val id = addVal(x, y)
                for (pr in pairs) edgeVal[(pr.first.toLong() shl 32) or e.toLong()] = id
            } else {
                for ((f, ca, cb) in pairs) {
                    val id = addVal((fv.values[ca * 2] * 0.5f) + (fv.values[cb * 2] * 0.5f), (fv.values[ca * 2 + 1] * 0.5f) + (fv.values[cb * 2 + 1] * 0.5f))
                    edgeVal[(f.toLong() shl 32) or e.toLong()] = id
                }
                if (!disc && pairs.size > 1 && linear) {
                    val first = edgeVal[(pairs[0].first.toLong() shl 32) or e.toLong()]!!
                    for (pr in pairs) edgeVal[(pr.first.toLong() shl 32) or e.toLong()] = first
                }
            }
        }
        val vertVal = HashMap<Long, Int>()
        for (v in 0 until p.nV) {
            val sib = tags.siblings[v]
            if (sib.isEmpty()) continue
            if (!tags.mismatch[v]) {
                val value = sib[0]
                if (linear) { vertVal[(v.toLong() shl 32) or value.toLong()] = addVal(fv.values[value * 2], fv.values[value * 2 + 1]); continue }
                val m = vertexVertexMask(p, c, v, r.vertChild[v])
                var x = 0f; var y = 0f
                for ((f, w) in m.f) { val fi = faceVal[f]; x += vals[fi * 2] * w; y += vals[fi * 2 + 1] * w }
                for ((u, w) in m.e) {
                    val uv = edgeOtherValue(p, fv, v, u)
                    x += fv.values[uv * 2] * w; y += fv.values[uv * 2 + 1] * w
                }
                x += fv.values[value * 2] * m.v; y += fv.values[value * 2 + 1] * m.v
                vertVal[(v.toLong() shl 32) or value.toLong()] = addVal(x, y)
            } else {
                for ((si, value) in sib.withIndex()) {
                    val id = if (linear || tags.corner[v][si]) addVal(fv.values[value * 2], fv.values[value * 2 + 1]) else {
                        val en = tags.creaseEnds[v]!![si]
                        addVal(
                            fv.values[en[0] * 2] * 0.125f + fv.values[en[1] * 2] * 0.125f + fv.values[value * 2] * 0.75f,
                            fv.values[en[0] * 2 + 1] * 0.125f + fv.values[en[1] * 2 + 1] * 0.125f + fv.values[value * 2 + 1] * 0.75f
                        )
                    }
                    vertVal[(v.toLong() shl 32) or value.toLong()] = id
                }
            }
        }
        var q = 0
        for (f in 0 until p.nF) {
            val s = p.fStart[f]; val n = p.fSize[f]
            for (k in 0 until n) {
                val o = q * 4
                val v = p.fVerts[s + k]
                corner[o] = vertVal[(v.toLong() shl 32) or fv.corner[s + k].toLong()]!!
                corner[o + 1] = edgeVal[(f.toLong() shl 32) or p.fEdges[s + k].toLong()]!!
                corner[o + 2] = faceVal[f]
                corner[o + 3] = edgeVal[(f.toLong() shl 32) or p.fEdges[s + (k + n - 1) % n].toLong()]!!
                q++
            }
        }
        return FVar(vals.toFloatArray(), corner)
    }

    private fun edgeOtherValue(l: Level, fv: FVar, v: Int, u: Int): Int {
        for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) {
            val f = l.vFaces[i]
            val s = l.fStart[f]
            for (k in 0 until l.fSize[f]) if (l.fVerts[s + k] == u) return fv.corner[s + k]
        }
        return fv.corner[l.vFaceCorner[l.vFaceStart[v]]]
    }

    private fun limitFVar(l: Level, fv: FVar, mode: Int, semiAsSmooth: Boolean, extraLevels: Int, legacy: Boolean = false): FVar {
        val tags = fvarTags(l, fv, mode, legacy)
        val out = fv.values.copyOf()
        if (mode == FVAR_ALL) return FVar(out, fv.corner)
        val infOnly = { e: Int -> if (l.eSharp[e] >= INF) INF else 0f }
        for (v in 0 until l.nV) {
            val sib = tags.siblings[v]
            if (sib.isEmpty()) continue
            if (!tags.mismatch[v]) {
                val value = sib[0]
                val rule = if (semiAsSmooth) l.rule(if (l.vSharp[v] >= INF) INF else 0f, infOnly, v) else l.vRule[v]
                var x: Float; var y: Float
                when (rule) {
                    SMOOTH, DART -> {
                        val n = l.valenceF(v)
                        if (n == 2 || n != l.valenceE(v)) { x = fv.values[value * 2]; y = fv.values[value * 2 + 1] } else {
                            val fw: Float; val ew: Float; val vw: Float
                            if (n == 4) { fw = (1.0 / 36.0).toFloat(); ew = (1.0 / 9.0).toFloat(); vw = (4.0 / 9.0).toFloat() } else {
                                val nn = n.toFloat(); fw = 1.0f / (nn * (nn + 5.0f)); ew = 4.0f * fw; vw = 1.0f - nn * (ew + fw)
                            }
                            x = fv.values[value * 2] * vw; y = fv.values[value * 2 + 1] * vw
                            for (i in l.vEdgeStart[v] until l.vEdgeStart[v + 1]) {
                                val uv = edgeOtherValue(l, fv, v, l.other(l.vEdges[i], v))
                                x += fv.values[uv * 2] * ew; y += fv.values[uv * 2 + 1] * ew
                            }
                            for (i in l.vFaceStart[v] until l.vFaceStart[v + 1]) {
                                val f = l.vFaces[i]
                                val c = l.vFaceCorner[i] - l.fStart[f]
                                val ov = fv.corner[l.fStart[f] + (c + 2) % l.fSize[f]]
                                x += fv.values[ov * 2] * fw; y += fv.values[ov * 2 + 1] * fw
                            }
                        }
                    }
                    CREASE -> {
                        val ce = creaseEnds(l, v, if (semiAsSmooth) infOnly else { e -> l.eSharp[e] })
                        val a = edgeOtherValue(l, fv, v, l.other(ce[0], v)); val b = edgeOtherValue(l, fv, v, l.other(ce[1], v))
                        val vw = (2.0 / 3.0).toFloat(); val ew = (1.0 / 6.0).toFloat()
                        x = fv.values[value * 2] * vw + fv.values[a * 2] * ew + fv.values[b * 2] * ew
                        y = fv.values[value * 2 + 1] * vw + fv.values[a * 2 + 1] * ew + fv.values[b * 2 + 1] * ew
                    }
                    else -> { x = fv.values[value * 2]; y = fv.values[value * 2 + 1] }
                }
                if (semiAsSmooth && rule != CORNER && !l.vNonManifold[v] && l.vRule[v] != DART) {
                    val t = vertexTags(l, v)
                    if (t.infSharpEdges && !t.infSharpCrease) { x = fv.values[value * 2]; y = fv.values[value * 2 + 1] }
                }
                out[value * 2] = x; out[value * 2 + 1] = y
            } else {
                for ((si, value) in sib.withIndex()) {
                    if (tags.corner[v][si]) continue
                    val en = tags.creaseEnds[v]!![si]
                    if (tags.spanFaces[v][si] == 1) {
                        var px = fv.values[value * 2]; var py = fv.values[value * 2 + 1]
                        var ax = fv.values[en[0] * 2]; var ay = fv.values[en[0] * 2 + 1]
                        var bx = fv.values[en[1] * 2]; var by = fv.values[en[1] * 2 + 1]
                        for (k in 0 until extraLevels) {
                            val nx = px * 0.75f + ax * 0.125f + bx * 0.125f
                            val ny = py * 0.75f + ay * 0.125f + by * 0.125f
                            ax = px * 0.5f + ax * 0.5f; ay = py * 0.5f + ay * 0.5f
                            bx = px * 0.5f + bx * 0.5f; by = py * 0.5f + by * 0.5f
                            px = nx; py = ny
                        }
                        out[value * 2] = px; out[value * 2 + 1] = py
                        continue
                    }
                    val vw = (2.0 / 3.0).toFloat(); val ew = (1.0 / 6.0).toFloat()
                    out[value * 2] = fv.values[value * 2] * vw + fv.values[en[0] * 2] * ew + fv.values[en[1] * 2] * ew
                    out[value * 2 + 1] = fv.values[value * 2 + 1] * vw + fv.values[en[0] * 2 + 1] * ew + fv.values[en[1] * 2 + 1] * ew
                }
            }
        }
        return FVar(out, fv.corner)
    }

    private fun mapFVarToLevel(outLevel: Level, outF: FVar, deepF: FVar, depth: Int): FVar {
        val vals = outF.values.copyOf()
        var mul = 1
        repeat(depth) { mul *= 4 }
        for (c in outF.corner.indices) {
            val dc = c * mul
            if (dc >= deepF.corner.size) continue
            val src = deepF.corner[dc]
            val dst = outF.corner[c]
            vals[dst * 2] = deepF.values[src * 2]; vals[dst * 2 + 1] = deepF.values[src * 2 + 1]
        }
        return FVar(vals, outF.corner)
    }
}
