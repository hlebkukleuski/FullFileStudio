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

internal class BlendMeshData(
    var co: FloatArray,
    var fStart: IntArray,
    var fSize: IntArray,
    var corners: IntArray,
    var faceMat: IntArray?,
    var faceSmooth: BooleanArray?,
    var uv: FloatArray?,
    var cornerColor: IntArray?,
    var pointColor: IntArray?,
    var edgeCrease: Map<Long, Float>,
    var vertCrease: FloatArray?,
    var looseEdges: IntArray,
    var dverts: Array<List<Pair<Int, Float>>>?,
    var groups: List<String>
) {
    var sharpEdges: Set<Long> = emptySet()
    var seamEdges: Set<Long> = emptySet()
    var edgeAttrs: Map<String, Map<Long, Float>> = emptyMap()
    var vertAttrs: Map<String, FloatArray> = emptyMap()
    var customShort: ShortArray? = null
    var customFloat: FloatArray? = null
    var faceStrength: IntArray? = null
    var autoSmooth: Float? = null
    var legacyNormals = false
    var fileEdges: IntArray? = null
    var skinRadius: FloatArray? = null
    var skinFlag: IntArray? = null

    fun normalSpec(): NormalSpec? = if (legacyNormals) null else NormalSpec(sharpEdges, customShort?.takeIf { it.size == corners.size * 2 }, customFloat?.takeIf { it.size == corners.size * 3 }, autoSmooth)

    fun carryNormals(from: BlendMeshData, sharp: Set<Long> = emptySet()): BlendMeshData {
        legacyNormals = from.legacyNormals
        autoSmooth = from.autoSmooth
        sharpEdges = sharp
        return this
    }

    val nV get() = co.size / 3
    val nF get() = fStart.size

    fun topology(): MeshTopology = MeshTopology(corners, fStart, fSize, MeshTopology.edgesFromFaces(corners, fStart, fSize))

    companion object {
        fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

        fun uvIndices(d: BlendMeshData, limit: Float = 0.0001f): Pair<IntArray, FloatArray>? {
            val uv = d.uv ?: return null
            val winding = BooleanArray(d.nF) { f ->
                var cross = 0f
                val s = d.fStart[f]; val n = d.fSize[f]
                var px = uv[(s + n - 1) * 2]; var py = uv[(s + n - 1) * 2 + 1]
                for (k in 0 until n) {
                    val x = uv[(s + k) * 2]; val y = uv[(s + k) * 2 + 1]
                    cross += (px - x) * (y + py)
                    px = x; py = y
                }
                cross < 0f
            }
            val byVert = Array(d.nV) { ArrayList<Int>() }
            val faceOf = IntArray(d.corners.size)
            for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) { val c = d.fStart[f] + k; faceOf[c] = f; byVert[d.corners[c]].add(c) }
            val idx = IntArray(d.corners.size) { -1 }
            val vals = ArrayList<Float>()
            for (v in 0 until d.nV) {
                val list = byVert[v]
                for (c in list) {
                    if (idx[c] >= 0) continue
                    val id = vals.size / 2
                    vals.add(uv[c * 2]); vals.add(uv[c * 2 + 1])
                    idx[c] = id
                    for (o in list) if (idx[o] < 0 && kotlin.math.abs(uv[o * 2] - uv[c * 2]) < limit && kotlin.math.abs(uv[o * 2 + 1] - uv[c * 2 + 1]) < limit && winding[faceOf[o]] == winding[faceOf[c]]) idx[o] = id
                }
            }
            return idx to vals.toFloatArray()
        }

        fun refinedCoarse(d: BlendMeshData, levels: Int): FloatArray {
            if (levels <= 0 || d.nF == 0) return d.co.copyOf()
            val loose = HashSet<Int>()
            for (i in d.looseEdges.indices) loose.add(d.looseEdges[i])
            val edgeSharp = HashMap<Long, Float>()
            for ((k, c) in d.edgeCrease) { val s = c * c * 10.0f; if (s >= 1e-6f) edgeSharp[k] = s }
            val vs = d.vertCrease?.let { vc -> FloatArray(vc.size) { vc[it] * vc[it] * 10.0f } }
            val r = BlendSubdiv.subdivide(
                SubdivInput(d.co, d.fStart, d.fSize, d.corners, edgeSharp, vs, BooleanArray(d.nV) { it in loose }, null, null),
                levels, levels, BlendSubdiv.BOUNDARY_EDGE_ONLY, BlendSubdiv.FVAR_CORNERS_PLUS1, false
            )
            val map = BlendSubdiv.lastCoarseIndex
            val used = BooleanArray(d.nV)
            for (c in d.corners) if (c in 0 until d.nV) used[c] = true
            val out = d.co.copyOf()
            for (v in 0 until d.nV) {
                if (!used[v]) continue
                val m = map[v]
                for (k in 0 until 3) out[v * 3 + k] = r.co[m * 3 + k]
            }
            return out
        }

        fun subsurf(d: BlendMeshData, levels: Int, quality: Int, adaptive: Boolean, simple: Boolean, useCrease: Boolean, boundary: Int, fvarMode: Int, multires: MultiresDisp? = null): BlendMeshData {
            val loose = HashSet<Int>()
            for (i in d.looseEdges.indices) loose.add(d.looseEdges[i])
            val inf = if (simple) BooleanArray(d.nV) { true } else BooleanArray(d.nV) { it in loose }
            val edgeSharp = HashMap<Long, Float>()
            if (simple) {
                for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
                    val a = d.corners[d.fStart[f] + k]; val b = d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]
                    edgeSharp[key(a, b)] = 10.0f
                }
            } else if (useCrease) for ((k, c) in d.edgeCrease) { val s = c * c * 10.0f; if (s >= 1e-6f) edgeSharp[k] = s }
            val vs = if (useCrease && !simple) d.vertCrease?.let { vc -> FloatArray(vc.size) { vc[it] * vc[it] * 10.0f } } else null
            val uvi = uvIndices(d)
            val target = levels
            val hasSemi = edgeSharp.values.any { it > 0f && it < 10f } || (vs?.any { it > 0f && it < 10f } == true)
            val faceCount = IntArray(d.nV)
            for (c in d.corners) if (c in 0 until d.nV) faceCount[c]++
            val edgeFaces = HashMap<Long, Int>()
            for (fi in 0 until d.nF) for (k in 0 until d.fSize[fi]) { val kk = key(d.corners[d.fStart[fi] + k], d.corners[d.fStart[fi] + (k + 1) % d.fSize[fi]]); edgeFaces[kk] = (edgeFaces[kk] ?: 0) + 1 }
            val vertEdges = IntArray(d.nV)
            val vertBoundary = BooleanArray(d.nV)
            for ((kk, n) in edgeFaces) { val a = (kk ushr 32).toInt(); val b = (kk and 0xFFFFFFFFL).toInt(); vertEdges[a]++; vertEdges[b]++; if (n < 2) { vertBoundary[a] = true; vertBoundary[b] = true } }
            val hasVal2 = (0 until d.nV).any { faceCount[it] == 2 && vertEdges[it] == 2 && !vertBoundary[it] }
            val input = SubdivInput(d.co, d.fStart, d.fSize, d.corners, edgeSharp, vs, inf, uvi?.first, uvi?.second, d.sharpEdges)
            val r = if (adaptive && !simple && quality < target) BlendSubdiv.subdivide(input, target, target, boundary, fvarMode, true, target, false, quality, multires)
            else {
                val hasInfInterior = !simple && edgeSharp.any { (k, v) -> v >= 10f && (edgeFaces[k] ?: 0) == 2 }
            val limitLevel = if (adaptive && (hasSemi || hasVal2 || hasInfInterior || (multires != null && d.fSize.sumOf { if (it == 4) 1L else it.toLong() } shl (2 * maxOf(quality - 1, 0)) <= 1_500_000L))) maxOf(target, quality) else target
                BlendSubdiv.subdivide(input, target, limitLevel, boundary, fvarMode, adaptive && !simple, if (adaptive) maxOf(quality, target) else target, adaptive && (hasSemi || hasInfInterior), -1, multires)
            }
            val nF = r.fStart.size
            val faceMat = d.faceMat?.let { m -> IntArray(nF) { m.getOrElse(r.faceOrigin[it]) { 0 } } }
            val faceSmooth = d.faceSmooth?.let { m -> BooleanArray(nF) { m.getOrElse(r.faceOrigin[it]) { false } } }
            val cw = BlendSubdiv.lastCornerWeights
            val vw = BlendSubdiv.lastVertWeights
            val cornerColor = d.cornerColor?.let { cc ->
                IntArray(r.fVerts.size) { c ->
                    val of = r.faceOrigin[c / 4]
                    val w = cw[c]
                    blendColors(IntArray(w.size) { k -> cc[d.fStart[of] + k] }, w)
                }
            }
            val pointColor = d.pointColor?.let { pc ->
                IntArray(r.co.size / 3) { v ->
                    val of = r.vertOriginFace[v]
                    if (of < 0) 0 else blendColors(IntArray(vw[v].size) { k -> pc[d.corners[d.fStart[of] + k]] }, vw[v])
                }
            }
            val dverts = d.dverts?.let { dv ->
                Array(r.co.size / 3) { v ->
                    val of = r.vertOriginFace[v]
                    if (of < 0) emptyList() else {
                        val acc = HashMap<Int, Float>()
                        val w = vw[v]
                        for (k in w.indices) if (w[k] != 0f) for ((g, x) in dv[d.corners[d.fStart[of] + k]]) acc[g] = (acc[g] ?: 0f) + x * w[k]
                        acc.entries.map { it.key to it.value }
                    }
                }
            }
            val coarseIdx = BlendSubdiv.lastCoarseIndex
            val faceUsed = BooleanArray(d.nV)
            for (c in d.corners) if (c in 0 until d.nV) faceUsed[c] = true
            val looseSegs = ArrayList<Int>()
            var co = r.co
            var pc = pointColor
            var dv = dverts
            val hasLoose = (0 until d.nV).any { !faceUsed[it] } || d.looseEdges.isNotEmpty()
            if (hasLoose && coarseIdx.size >= d.nV) {
                val coarseEdges = LinkedHashSet<Long>()
                for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) coarseEdges.add(key(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]))
                val looseList = ArrayList<Long>()
                val looseEnds = ArrayList<IntArray>()
                for (i in 0 until d.looseEdges.size / 2) { val kk = key(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]); if (coarseEdges.add(kk)) { looseList.add(kk); looseEnds.add(intArrayOf(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1])) } }
                val dpc = d.pointColor
                val ddv = d.dverts
                val vertEdges = Array(d.nV) { ArrayList<Long>(2) }
                for (kk in coarseEdges) { vertEdges[(kk ushr 32).toInt()].add(kk); vertEdges[(kk and 0xFFFFFFFFL).toInt()].add(kk) }
                val res = (1 shl levels) + 1
                val perEdge = res - 2
                val base = co.size / 3
                val out = co.copyOf((base + looseList.size * perEdge) * 3)
                val pcOut = pc?.copyOf(base + looseList.size * perEdge)
                val dvOut = dv?.let { a -> Array(base + looseList.size * perEdge) { if (it < base) a[it] else emptyList() } }
                for (v in 0 until d.nV) if (!faceUsed[v]) {
                    val m = coarseIdx[v]
                    for (k in 0 until 3) out[m * 3 + k] = d.co[v * 3 + k]
                    if (pcOut != null && dpc != null) pcOut[m] = dpc[v]
                    if (dvOut != null && ddv != null) dvOut[m] = ddv[v]
                }
                fun pos(v: Int) = floatArrayOf(d.co[v * 3], d.co[v * 3 + 1], d.co[v * 3 + 2])
                for ((li, kk) in looseList.withIndex()) {
                    val e0 = looseEnds[li][0]; val e1 = looseEnds[li][1]
                    fun neighbor(v: Int): Int {
                        val ne = vertEdges[v]
                        if (ne.size != 2) return -1
                        val o = if (ne[0] == kk) ne[1] else ne[0]
                        val x = (o ushr 32).toInt(); val y = (o and 0xFFFFFFFFL).toInt()
                        return if (x == v) y else x
                    }
                    val p1 = pos(e0); val p2 = pos(e1)
                    val n0 = neighbor(e0); val n1 = neighbor(e1)
                    val p0 = if (n0 >= 0) pos(n0) else FloatArray(3) { p1[it] * 2.0f - p2[it] }
                    val p3 = if (n1 >= 0) pos(n1) else FloatArray(3) { p2[it] * 2.0f - p1[it] }
                    val inv = 1.0f / (res - 1).toFloat()
                    var prev = coarseIdx[e0]
                    for (i in 0 until res) {
                        val u = i * inv
                        val idx = when (i) { 0 -> coarseIdx[e0]; res - 1 -> coarseIdx[e1]; else -> base + li * perEdge + (i - 1) }
                        if (simple) {
                            for (k in 0 until 3) out[idx * 3 + k] = p1[k] + (p2[k] - p1[k]) * u
                        } else {
                            val t2 = u * u; val t3 = t2 * u
                            val w0 = -0.16666666f * t3 + 0.5f * t2 - 0.5f * u + 0.16666666f
                            val w1 = 0.5f * t3 - t2 + 0.66666666f
                            val w2 = -0.5f * t3 + 0.5f * t2 + 0.5f * u + 0.16666666f
                            val w3 = 0.16666666f * t3
                            for (k in 0 until 3) out[idx * 3 + k] = w0 * p0[k] + w1 * p1[k] + w2 * p2[k] + w3 * p3[k]
                        }
                        if (i != 0 && i != res - 1) {
                            if (pcOut != null && dpc != null) pcOut[idx] = blendColors(intArrayOf(dpc[e0], dpc[e1]), floatArrayOf(1f - u, u))
                            if (dvOut != null && ddv != null) {
                                val acc = HashMap<Int, Float>()
                                for ((g, x) in ddv[e0]) acc[g] = (acc[g] ?: 0f) + x * (1f - u)
                                for ((g, x) in ddv[e1]) acc[g] = (acc[g] ?: 0f) + x * u
                                dvOut[idx] = acc.entries.map { it.key to it.value }
                            }
                        }
                        if (i > 0) { looseSegs.add(prev); looseSegs.add(idx) }
                        prev = idx
                    }
                }
                co = out; pc = pcOut; dv = dvOut
            }
            return BlendMeshData(
                co, r.fStart, r.fSize, r.fVerts, faceMat, faceSmooth, r.uv, cornerColor, pc,
                emptyMap(), null, looseSegs.toIntArray(), dv, d.groups
            ).carryNormals(d, r.marked)
        }

        private fun blendColors(c: IntArray, w: FloatArray): Int {
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            for (i in c.indices) {
                val x = c[i]
                a += ((x ushr 24) and 0xFF) * w[i]; r += ((x shr 16) and 0xFF) * w[i]; g += ((x shr 8) and 0xFF) * w[i]; b += (x and 0xFF) * w[i]
            }
            fun q(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
            return (q(a) shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
        }
    }
}
