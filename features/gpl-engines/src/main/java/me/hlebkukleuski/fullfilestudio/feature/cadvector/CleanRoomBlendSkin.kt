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
 * Portions ported and adapted from Blender (https://www.blender.org/, source/blender/modifiers/intern/MOD_skin.cc),
 * Copyright (C) 2011-2026 Nicholas Bishop, Blender Foundation, and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal object BlendSkinModifier {

    const val FLAG_ROOT = 1
    const val FLAG_LOOSE = 2
    const val MOD_FLAG_SMOOTH = 1
    const val SYM_X = 1
    const val SYM_Y = 2
    const val SYM_Z = 4

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun lenSq() = x * x + y * y + z * z
        fun len() = sqrt(lenSq())
        fun norm(): Vec3 {
            val l = len()
            return if (l > 1e-12) Vec3(x / l, y / l, z / l) else Vec3(0.0, 0.0, 1.0)
        }
        fun comp(axis: Int): Double = when (axis) { 0 -> x; 1 -> y; else -> z }
    }

    private class Frame(
        val center: Vec3,
        val dir: Vec3,
        val axisU: Vec3,
        val axisV: Vec3,
        val rx: Double,
        val ry: Double,
        val vertIds: IntArray
    )

    private class EdgeAdj(val edgeIdx: Int, val other: Int, val isFirst: Boolean)

    fun apply(
        d: BlendMeshData,
        branchSmoothing: Float,
        flag: Int,
        symmetryAxes: Int
    ): BlendMeshData {
        val nV = d.nV
        if (nV == 0) return d
        val smoothShade = (flag and MOD_FLAG_SMOOTH) != 0
        val smoothFac = branchSmoothing.coerceIn(0f, 1f).toDouble()

        val rxArr = DoubleArray(nV) { v ->
            val r = d.skinRadius?.getOrNull(v * 2)?.toDouble() ?: 0.25
            if (r.isFinite() && r > 1e-6) r else 1e-4
        }
        val ryArr = DoubleArray(nV) { v ->
            val r = d.skinRadius?.getOrNull(v * 2 + 1)?.toDouble() ?: 0.25
            if (r.isFinite() && r > 1e-6) r else 1e-4
        }
        val vFlag = IntArray(nV) { v -> d.skinFlag?.getOrNull(v) ?: 0 }
        val pts = Array(nV) { v -> Vec3(d.co[v * 3].toDouble(), d.co[v * 3 + 1].toDouble(), d.co[v * 3 + 2].toDouble()) }

        val rawEdges = collectAllEdges(d)
        val nE = rawEdges.size / 2
        val adj = Array(nV) { ArrayList<EdgeAdj>() }
        for (e in 0 until nE) {
            val a = rawEdges[e * 2]
            val b = rawEdges[e * 2 + 1]
            if (a == b || a !in 0 until nV || b !in 0 until nV) continue
            adj[a].add(EdgeAdj(e, b, true))
            adj[b].add(EdgeAdj(e, a, false))
        }

        val outCo = ArrayList<Double>()
        val outDverts = if (d.dverts != null) ArrayList<List<Pair<Int, Float>>>() else null
        val outFaces = ArrayList<IntArray>()
        val branchVertMask = ArrayList<Boolean>()

        fun addVert(p: Vec3, srcVert: Int, isBranch: Boolean): Int {
            val id = outCo.size / 3
            outCo.add(p.x); outCo.add(p.y); outCo.add(p.z)
            outDverts?.add(d.dverts?.getOrNull(srcVert) ?: emptyList())
            branchVertMask.add(isBranch)
            return id
        }

        val edgeAxisU = Array<Vec3?>(nE) { null }
        val edgeAxisV = Array<Vec3?>(nE) { null }
        val edgeDirFromA = Array(nE) { e ->
            val a = rawEdges[e * 2]; val b = rawEdges[e * 2 + 1]
            (pts[b] - pts[a]).norm()
        }

        val visitedV = BooleanArray(nV)
        val visitedE = BooleanArray(nE)
        val roots = ArrayList<Int>()
        for (v in 0 until nV) {
            if (adj[v].isEmpty()) {
                visitedV[v] = true
                emitIsolatedBox(pts[v], rxArr[v], ryArr[v], v, ::addVert, outFaces)
            } else if ((vFlag[v] and FLAG_ROOT) != 0) {
                roots.add(v)
            }
        }
        for (v in 0 until nV) {
            if (!visitedV[v] && adj[v].isNotEmpty() && v !in roots) {
                var compHasRoot = false
                val q = ArrayDeque<Int>()
                val comp = ArrayList<Int>()
                q.addLast(v)
                visitedV[v] = true
                while (q.isNotEmpty()) {
                    val u = q.removeFirst()
                    comp.add(u)
                    if ((vFlag[u] and FLAG_ROOT) != 0) compHasRoot = true
                    for (ea in adj[u]) {
                        if (!visitedV[ea.other]) {
                            visitedV[ea.other] = true
                            q.addLast(ea.other)
                        }
                    }
                }
                if (!compHasRoot) roots.add(v)
                for (u in comp) visitedV[u] = false
            }
        }

        val queue = ArrayDeque<Int>()
        val vertParentEdge = IntArray(nV) { -1 }
        for (r in roots) {
            if (visitedV[r] || adj[r].isEmpty()) continue
            visitedV[r] = true
            val firstAdj = adj[r][0]
            val d0 = if (firstAdj.isFirst) edgeDirFromA[firstAdj.edgeIdx] else edgeDirFromA[firstAdj.edgeIdx] * -1.0
            val (u0, v0) = canonicalBasis(d0, pts[r], symmetryAxes)
            for (ea in adj[r]) {
                val e = ea.edgeIdx
                if (visitedE[e]) continue
                visitedE[e] = true
                val de = if (ea.isFirst) edgeDirFromA[e] else edgeDirFromA[e] * -1.0
                val (ue, ve) = transportBasis(d0, u0, v0, de)
                val (sue, sve) = alignSymmetry(pts[r], de, ue, ve, symmetryAxes)
                if (ea.isFirst) {
                    edgeAxisU[e] = sue; edgeAxisV[e] = sve
                } else {
                    edgeAxisU[e] = sue * -1.0; edgeAxisV[e] = sve
                }
                if (!visitedV[ea.other]) {
                    visitedV[ea.other] = true
                    vertParentEdge[ea.other] = e
                    queue.addLast(ea.other)
                }
            }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                val pe = vertParentEdge[u]
                val pDir = if (rawEdges[pe * 2 + 1] == u) edgeDirFromA[pe] else edgeDirFromA[pe] * -1.0
                val pU = if (rawEdges[pe * 2 + 1] == u) edgeAxisU[pe]!! else edgeAxisU[pe]!! * -1.0
                val pV = edgeAxisV[pe]!!
                for (ea in adj[u]) {
                    val e = ea.edgeIdx
                    if (visitedE[e]) continue
                    visitedE[e] = true
                    val de = if (ea.isFirst) edgeDirFromA[e] else edgeDirFromA[e] * -1.0
                    val (ue, ve) = transportBasis(pDir, pU, pV, de)
                    val (sue, sve) = alignSymmetry(pts[u], de, ue, ve, symmetryAxes)
                    if (ea.isFirst) {
                        edgeAxisU[e] = sue; edgeAxisV[e] = sve
                    } else {
                        edgeAxisU[e] = sue * -1.0; edgeAxisV[e] = sve
                    }
                    if (!visitedV[ea.other]) {
                        visitedV[ea.other] = true
                        vertParentEdge[ea.other] = e
                        queue.addLast(ea.other)
                    }
                }
            }
        }

        val edgeSegCounts = IntArray(nE) { e ->
            val a = rawEdges[e * 2]; val b = rawEdges[e * 2 + 1]
            if (a !in 0 until nV || b !in 0 until nV || a == b) 1 else {
                val len = (pts[b] - pts[a]).len()
                val avgDiam = 0.5 * (rxArr[a] + ryArr[a]) + 0.5 * (rxArr[b] + ryArr[b])
                if (avgDiam > 1e-6) (floor(len / avgDiam).toInt() + 1).coerceIn(1, 64) else 1
            }
        }

        val frameFromA = arrayOfNulls<Frame>(nE)
        val frameFromB = arrayOfNulls<Frame>(nE)

        fun basisOutward(e: Int, atA: Boolean): Triple<Vec3, Vec3, Vec3> {
            val dA = edgeDirFromA[e]
            val uA = edgeAxisU[e] ?: canonicalBasis(dA, pts[rawEdges[e * 2]], symmetryAxes).first
            val vA = edgeAxisV[e] ?: dA.cross(uA).norm()
            return if (atA) Triple(dA, uA, vA) else Triple(dA * -1.0, uA * -1.0, vA)
        }

        fun makeFrame(center: Vec3, dir: Vec3, u: Vec3, v: Vec3, rx: Double, ry: Double, srcVert: Int, isBranch: Boolean): Frame {
            val c0 = center + u * rx - v * ry
            val c1 = center - u * rx - v * ry
            val c2 = center - u * rx + v * ry
            val c3 = center + u * rx + v * ry
            val ids = intArrayOf(
                addVert(c0, srcVert, isBranch),
                addVert(c1, srcVert, isBranch),
                addVert(c2, srcVert, isBranch),
                addVert(c3, srcVert, isBranch)
            )
            return Frame(center, dir, u, v, rx, ry, ids)
        }

        for (v in 0 until nV) {
            val inc = adj[v]
            if (inc.isEmpty()) continue
            val rx = rxArr[v]
            val ry = ryArr[v]
            val isLoose = (vFlag[v] and FLAG_LOOSE) != 0

            if (inc.size == 1) {
                val ea = inc[0]
                val (dir, u, w) = basisOutward(ea.edgeIdx, ea.isFirst)
                val fr = makeFrame(pts[v], dir, u, w, rx, ry, v, false)
                outFaces.add(intArrayOf(fr.vertIds[0], fr.vertIds[1], fr.vertIds[2], fr.vertIds[3]))
                if (ea.isFirst) frameFromA[ea.edgeIdx] = fr else frameFromB[ea.edgeIdx] = fr
            } else if (inc.size == 2 && !isLoose) {
                val ea0 = inc[0]; val ea1 = inc[1]
                val (d0, u0, w0) = basisOutward(ea0.edgeIdx, ea0.isFirst)
                val (d1, _, _) = basisOutward(ea1.edgeIdx, ea1.isFirst)
                val dot = d0.dot(d1)
                if (dot > 0.96) {
                    buildBranchNode(v, pts[v], rx, ry, inc, pts, rawEdges, edgeSegCounts, ::basisOutward, ::addVert, outCo, outFaces, frameFromA, frameFromB)
                } else {
                    val tangent = (d1 - d0).norm()
                    val (ut, wt) = transportBasis(d0, u0, w0, tangent)
                    val fr1 = makeFrame(pts[v], tangent, ut, wt, rx, ry, v, false)
                    val fr0 = Frame(
                        pts[v], tangent * -1.0, ut * -1.0, wt, rx, ry,
                        intArrayOf(fr1.vertIds[1], fr1.vertIds[0], fr1.vertIds[3], fr1.vertIds[2])
                    )
                    if (ea0.isFirst) frameFromA[ea0.edgeIdx] = fr0 else frameFromB[ea0.edgeIdx] = fr0
                    if (ea1.isFirst) frameFromA[ea1.edgeIdx] = fr1 else frameFromB[ea1.edgeIdx] = fr1
                }
            } else {
                buildBranchNode(v, pts[v], rx, ry, inc, pts, rawEdges, edgeSegCounts, ::basisOutward, ::addVert, outCo, outFaces, frameFromA, frameFromB)
            }
        }

        for (e in 0 until nE) {
            val fA = frameFromA[e] ?: continue
            val fB = frameFromB[e] ?: continue
            val a = rawEdges[e * 2]; val b = rawEdges[e * 2 + 1]
            val pA = fA.center; val pB = fB.center
            val spanLen = (pB - pA).len()
            val avgDiam = 0.5 * (rxArr[a] + ryArr[a]) + 0.5 * (rxArr[b] + ryArr[b])
            val segs = if (avgDiam > 1e-6) (floor(spanLen / avgDiam).toInt() + 1).coerceIn(1, 64) else 1

            val ringA = fA.vertIds
            val ringBRev = intArrayOf(fB.vertIds[1], fB.vertIds[0], fB.vertIds[3], fB.vertIds[2])
            val (_, uEdge, vEdge) = basisOutward(e, true)

            var prevRing = ringA
            for (s in 1 until segs) {
                val t = s.toDouble() / segs.toDouble()
                val cx = pA.x * (1.0 - t) + pB.x * t
                val cy = pA.y * (1.0 - t) + pB.y * t
                val cz = pA.z * (1.0 - t) + pB.z * t
                val rxt = fA.rx * (1.0 - t) + fB.rx * t
                val ryt = fA.ry * (1.0 - t) + fB.ry * t
                val srcV = if (t < 0.5) a else b
                val rawMid = intArrayOf(
                    addVert(Vec3(cx + uEdge.x * rxt - vEdge.x * ryt, cy + uEdge.y * rxt - vEdge.y * ryt, cz + uEdge.z * rxt - vEdge.z * ryt), srcV, false),
                    addVert(Vec3(cx - uEdge.x * rxt - vEdge.x * ryt, cy - uEdge.y * rxt - vEdge.y * ryt, cz - uEdge.z * rxt - vEdge.z * ryt), srcV, false),
                    addVert(Vec3(cx - uEdge.x * rxt + vEdge.x * ryt, cy - uEdge.y * rxt + vEdge.y * ryt, cz - uEdge.z * rxt + vEdge.z * ryt), srcV, false),
                    addVert(Vec3(cx + uEdge.x * rxt + vEdge.x * ryt, cy + uEdge.y * rxt + vEdge.y * ryt, cz + uEdge.z * rxt + vEdge.z * ryt), srcV, false)
                )
                val midRing = bestShiftAlignment(prevRing, rawMid, outCo)
                stitchRings(prevRing, midRing, outFaces)
                prevRing = midRing
            }
            val ringB = bestShiftAlignment(prevRing, ringBRev, outCo)
            stitchRings(prevRing, ringB, outFaces)
        }

        if (smoothFac > 1e-6) {
            applyBranchSmoothing(outCo, outFaces, branchVertMask, smoothFac)
        }

        ensureOutwardWinding(outCo, outFaces)

        val finalNV = outCo.size / 3
        val finalCo = FloatArray(outCo.size) { outCo[it].toFloat() }
        val fStart = IntArray(outFaces.size)
        val fSize = IntArray(outFaces.size)
        var totalCorners = 0
        for (i in outFaces.indices) {
            fStart[i] = totalCorners
            fSize[i] = outFaces[i].size
            totalCorners += outFaces[i].size
        }
        val corners = IntArray(totalCorners)
        var cp = 0
        for (face in outFaces) {
            for (vId in face) corners[cp++] = vId
        }
        val faceSmooth = BooleanArray(outFaces.size) { smoothShade }
        val faceMat = IntArray(outFaces.size) { 0 }
        val finalDverts = outDverts?.toTypedArray()

        return BlendMeshData(
            co = finalCo,
            fStart = fStart,
            fSize = fSize,
            corners = corners,
            faceMat = faceMat,
            faceSmooth = faceSmooth,
            uv = null,
            cornerColor = null,
            pointColor = null,
            edgeCrease = emptyMap(),
            vertCrease = null,
            looseEdges = IntArray(0),
            dverts = finalDverts,
            groups = d.groups
        ).carryNormals(d)
    }

    private fun collectAllEdges(d: BlendMeshData): IntArray {
        val seen = LinkedHashSet<Long>()
        val out = ArrayList<Int>()
        fun addEdge(a: Int, b: Int) {
            if (a == b || a !in 0 until d.nV || b !in 0 until d.nV) return
            if (seen.add(BlendMeshData.key(a, b))) {
                out.add(a); out.add(b)
            }
        }
        val fe = d.fileEdges
        if (fe != null) {
            for (i in 0 until fe.size / 2) addEdge(fe[i * 2], fe[i * 2 + 1])
        }
        for (i in 0 until d.looseEdges.size / 2) addEdge(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1])
        for (f in 0 until d.nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            for (k in 0 until m) addEdge(d.corners[s + k], d.corners[s + (k + 1) % m])
        }
        return out.toIntArray()
    }

    private fun canonicalBasis(dir: Vec3, origin: Vec3, symmetryAxes: Int): Pair<Vec3, Vec3> {
        val ax = abs(dir.x); val ay = abs(dir.y); val az = abs(dir.z)
        val u: Vec3
        val v: Vec3
        if (az >= ax && az >= ay) {
            u = Vec3(1.0, 0.0, 0.0)
            val vRaw = dir.cross(u).norm()
            val uOrth = vRaw.cross(dir).norm()
            return alignSymmetry(origin, dir, uOrth, vRaw, symmetryAxes)
        } else if (ax >= ay) {
            u = Vec3(0.0, 1.0, 0.0)
            val vRaw = dir.cross(u).norm()
            val uOrth = vRaw.cross(dir).norm()
            return alignSymmetry(origin, dir, uOrth, vRaw, symmetryAxes)
        } else {
            u = Vec3(1.0, 0.0, 0.0)
            val vRaw = dir.cross(u).norm()
            val uOrth = vRaw.cross(dir).norm()
            return alignSymmetry(origin, dir, uOrth, vRaw, symmetryAxes)
        }
    }

    private fun transportBasis(d0: Vec3, u0: Vec3, v0: Vec3, d1: Vec3): Pair<Vec3, Vec3> {
        val dot = d0.dot(d1).coerceIn(-1.0, 1.0)
        if (dot > 1.0 - 1e-10) return u0 to v0
        if (dot < -1.0 + 1e-10) {
            val u = u0 * -1.0
            val v = d1.cross(u).norm()
            return u to v
        }
        val axis = d0.cross(d1).norm()
        val angle = acos(dot)
        val u = rotateAroundAxis(u0, axis, angle)
        val uProj = (u - d1 * u.dot(d1)).norm()
        val vProj = d1.cross(uProj).norm()
        return uProj to vProj
    }

    private fun rotateAroundAxis(p: Vec3, axis: Vec3, angle: Double): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return p * c + axis.cross(p) * s + axis * (axis.dot(p) * (1.0 - c))
    }

    private fun alignSymmetry(origin: Vec3, dir: Vec3, u: Vec3, v: Vec3, symmetryAxes: Int): Pair<Vec3, Vec3> {
        if (symmetryAxes == 0) return u to v
        for (axis in 0 until 3) {
            if ((symmetryAxes and (1 shl axis)) == 0) continue
            if (abs(origin.comp(axis)) > 1e-3) continue
            val n = Vec3(if (axis == 0) 1.0 else 0.0, if (axis == 1) 1.0 else 0.0, if (axis == 2) 1.0 else 0.0)
            if (abs(dir.dot(n)) > 1e-3) continue
            val du = abs(u.dot(n))
            val dv = abs(v.dot(n))
            return if (du >= dv) {
                val uSign = if (u.dot(n) >= 0.0) 1.0 else -1.0
                val newU = (n * uSign - dir * (n * uSign).dot(dir)).norm()
                val newV = dir.cross(newU).norm()
                newU to newV
            } else {
                val vSign = if (v.dot(n) >= 0.0) 1.0 else -1.0
                val newV = (n * vSign - dir * (n * vSign).dot(dir)).norm()
                val newU = newV.cross(dir).norm()
                newU to newV
            }
        }
        return u to v
    }

    private fun emitIsolatedBox(
        c: Vec3,
        rx: Double,
        ry: Double,
        srcVert: Int,
        addVert: (Vec3, Int, Boolean) -> Int,
        outFaces: ArrayList<IntArray>
    ) {
        val rz = (rx + ry) * 0.5
        val v0 = addVert(Vec3(c.x + rx, c.y - ry, c.z + rz), srcVert, false)
        val v1 = addVert(Vec3(c.x - rx, c.y - ry, c.z + rz), srcVert, false)
        val v2 = addVert(Vec3(c.x - rx, c.y + ry, c.z + rz), srcVert, false)
        val v3 = addVert(Vec3(c.x + rx, c.y + ry, c.z + rz), srcVert, false)
        val v4 = addVert(Vec3(c.x + rx, c.y - ry, c.z - rz), srcVert, false)
        val v5 = addVert(Vec3(c.x - rx, c.y - ry, c.z - rz), srcVert, false)
        val v6 = addVert(Vec3(c.x - rx, c.y + ry, c.z - rz), srcVert, false)
        val v7 = addVert(Vec3(c.x + rx, c.y + ry, c.z - rz), srcVert, false)
        outFaces.add(intArrayOf(v0, v1, v5, v4))
        outFaces.add(intArrayOf(v1, v2, v6, v5))
        outFaces.add(intArrayOf(v2, v3, v7, v6))
        outFaces.add(intArrayOf(v3, v0, v4, v7))
        outFaces.add(intArrayOf(v3, v2, v1, v0))
        outFaces.add(intArrayOf(v4, v5, v6, v7))
    }

    private fun buildBranchNode(
        v: Int,
        center: Vec3,
        rx: Double,
        ry: Double,
        inc: List<EdgeAdj>,
        pts: Array<Vec3>,
        rawEdges: IntArray,
        edgeSegCounts: IntArray,
        basisOutward: (Int, Boolean) -> Triple<Vec3, Vec3, Vec3>,
        addVert: (Vec3, Int, Boolean) -> Int,
        outCo: ArrayList<Double>,
        outFaces: ArrayList<IntArray>,
        frameFromA: Array<Frame?>,
        frameFromB: Array<Frame?>
    ) {
        val kCount = inc.size
        val frameDirs = ArrayList<Vec3>(kCount)
        val frameCenters = ArrayList<Vec3>(kCount)
        val frameUs = ArrayList<Vec3>(kCount)
        val frameVs = ArrayList<Vec3>(kCount)
        val hullPoints = ArrayList<Vec3>(kCount * 4)
        val hullOutVerts = ArrayList<Int>(kCount * 4)
        val frameOutVerts = Array(kCount) { IntArray(4) }

        val maxR = max(rx, ry)
        for (i in 0 until kCount) {
            val ea = inc[i]
            val (dir, u, w) = basisOutward(ea.edgeIdx, ea.isFirst)
            val edgeLen = (pts[ea.other] - center).len()
            val offset = min(edgeLen * 0.4, maxR)
            val fc = center + dir * offset
            frameDirs.add(dir)
            frameCenters.add(fc)
            frameUs.add(u)
            frameVs.add(w)
            val corners = arrayOf(
                fc + u * rx - w * ry,
                fc - u * rx - w * ry,
                fc - u * rx + w * ry,
                fc + u * rx + w * ry
            )
            for (c in 0 until 4) {
                val outId = addVert(corners[c], v, true)
                hullPoints.add(corners[c])
                hullOutVerts.add(outId)
                frameOutVerts[i][c] = outId
            }
        }

        for (i in 0 until kCount) {
            val ea = inc[i]
            val fr = Frame(frameCenters[i], frameDirs[i], frameUs[i], frameVs[i], rx, ry, frameOutVerts[i])
            if (ea.isFirst) frameFromA[ea.edgeIdx] = fr else frameFromB[ea.edgeIdx] = fr
        }

        val sepDirs = Array(kCount) { frameDirs[it] }
        for (i in 0 until kCount) {
            for (j in i + 1 until kCount) {
                if (sepDirs[i].dot(sepDirs[j]) > 1.0 - 1e-4) {
                    val nudge = (sepDirs[j] + frameUs[j] * 0.02 + frameVs[j] * 0.013).norm()
                    sepDirs[j] = nudge
                }
            }
        }

        var minAngle = PI
        for (i in 0 until kCount) {
            for (j in i + 1 until kCount) {
                val ang = acos(sepDirs[i].dot(sepDirs[j]).coerceIn(-1.0, 1.0))
                if (ang < minAngle) minAngle = ang
            }
        }
        val alpha = min(0.25, 0.3 * minAngle.coerceAtLeast(1e-2))
        val zCap = cos(alpha)
        val h = sin(alpha) / sqrt(2.0)
        val zOther = cos((minAngle - alpha).coerceIn(alpha + 1e-4, PI))
        val slope = min(0.25 * (zCap - zOther), 0.5).coerceAtLeast(1e-6)
        val apexH = slope * h

        val spherePts = ArrayList<Vec3>(kCount * 5)
        for (i in 0 until kCount) {
            val d = sepDirs[i]
            val u0 = frameUs[i]
            val u = (u0 - d * u0.dot(d)).let { if (it.lenSq() > 1e-12) it.norm() else canonicalBasis(d, Vec3(0.0, 0.0, 0.0), 0).first }
            val w = d.cross(u).norm()
            spherePts.add(d * zCap + u * h - w * h)
            spherePts.add(d * zCap - u * h - w * h)
            spherePts.add(d * zCap - u * h + w * h)
            spherePts.add(d * zCap + u * h + w * h)
        }
        val apexStart = kCount * 4
        for (i in 0 until kCount) {
            spherePts.add(sepDirs[i] * (zCap + apexH))
        }

        val hullFaces = computeConvexHull3D(spherePts)
        val keptTris = ArrayList<IntArray>()
        for (tri in hullFaces) {
            if (tri[0] < apexStart && tri[1] < apexStart && tri[2] < apexStart) {
                keptTris.add(tri)
            }
        }

        val mergedFaces = mergeCoplanarTriangles(keptTris, hullPoints)
        for (face in mergedFaces) {
            val mapped = IntArray(face.size) { hullOutVerts[face[it]] }
            outFaces.add(mapped)
        }
    }

    private fun computeConvexHull3D(pts: List<Vec3>): List<IntArray> {
        val n = pts.size
        if (n < 4) return emptyList()

        var i0 = 0
        for (i in 1 until n) if (pts[i].x < pts[i0].x) i0 = i
        var i1 = -1; var maxD = -1.0
        for (i in 0 until n) {
            val d = (pts[i] - pts[i0]).lenSq()
            if (d > maxD) { maxD = d; i1 = i }
        }
        if (i1 < 0 || maxD < 1e-14) return emptyList()
        val e01 = pts[i1] - pts[i0]
        var i2 = -1; var maxArea = -1.0
        for (i in 0 until n) {
            if (i == i0 || i == i1) continue
            val a = e01.cross(pts[i] - pts[i0]).lenSq()
            if (a > maxArea) { maxArea = a; i2 = i }
        }
        if (i2 < 0 || maxArea < 1e-14) return emptyList()
        val n012 = e01.cross(pts[i2] - pts[i0])
        var i3 = -1; var maxVol = 0.0
        for (i in 0 until n) {
            if (i == i0 || i == i1 || i == i2) continue
            val vol = abs(n012.dot(pts[i] - pts[i0]))
            if (vol > maxVol) { maxVol = vol; i3 = i }
        }
        if (i3 < 0 || maxVol < 1e-12) return emptyList()

        val faces = ArrayList<IntArray>()
        val orient = n012.dot(pts[i3] - pts[i0])
        if (orient > 0.0) {
            faces.add(intArrayOf(i0, i2, i1))
            faces.add(intArrayOf(i0, i1, i3))
            faces.add(intArrayOf(i1, i2, i3))
            faces.add(intArrayOf(i2, i0, i3))
        } else {
            faces.add(intArrayOf(i0, i1, i2))
            faces.add(intArrayOf(i0, i3, i1))
            faces.add(intArrayOf(i1, i3, i2))
            faces.add(intArrayOf(i2, i3, i0))
        }

        val scale = sqrt(maxD).coerceAtLeast(1e-6)
        val distEps = scale * 1e-11

        for (p in 0 until n) {
            if (p == i0 || p == i1 || p == i2 || p == i3) continue
            val pt = pts[p]
            val visible = BooleanArray(faces.size)
            var anyVis = false
            for (fi in faces.indices) {
                val f = faces[fi]
                val a = pts[f[0]]; val b = pts[f[1]]; val c = pts[f[2]]
                val cr = (b - a).cross(c - a)
                val crLen = cr.len()
                if (crLen > 1e-15 && cr.dot(pt - a) > crLen * distEps) {
                    visible[fi] = true
                    anyVis = true
                }
            }
            if (!anyVis) continue

            val dirEdgeCount = HashMap<Long, Int>()
            val horizon = ArrayList<Pair<Int, Int>>()
            for (fi in faces.indices) {
                if (!visible[fi]) continue
                val f = faces[fi]
                for (k in 0 until 3) {
                    val u = f[k]; val w = f[(k + 1) % 3]
                    val revKey = (w.toLong() shl 32) or (u.toLong() and 0xFFFFFFFFL)
                    val fwdKey = (u.toLong() shl 32) or (w.toLong() and 0xFFFFFFFFL)
                    val c = dirEdgeCount[revKey]
                    if (c != null && c > 0) {
                        dirEdgeCount[revKey] = c - 1
                    } else {
                        dirEdgeCount[fwdKey] = (dirEdgeCount[fwdKey] ?: 0) + 1
                    }
                }
            }
            for ((key, cnt) in dirEdgeCount) {
                if (cnt > 0) {
                    val u = (key ushr 32).toInt()
                    val w = (key and 0xFFFFFFFFL).toInt()
                    horizon.add(u to w)
                }
            }
            val nextFaces = ArrayList<IntArray>(faces.size + horizon.size)
            for (fi in faces.indices) if (!visible[fi]) nextFaces.add(faces[fi])
            for ((u, w) in horizon) {
                nextFaces.add(intArrayOf(u, w, p))
            }
            faces.clear()
            faces.addAll(nextFaces)
        }
        return faces
    }

    private fun mergeCoplanarTriangles(tris: List<IntArray>, pts: List<Vec3>): List<IntArray> {
        if (tris.isEmpty()) return emptyList()
        val normals = Array(tris.size) { i ->
            val t = tris[i]
            (pts[t[1]] - pts[t[0]]).cross(pts[t[2]] - pts[t[0]]).norm()
        }
        val edgeToTris = HashMap<Long, ArrayList<Int>>()
        for (ti in tris.indices) {
            val t = tris[ti]
            for (k in 0 until 3) {
                val key = BlendMeshData.key(t[k], t[(k + 1) % 3])
                edgeToTris.getOrPut(key) { ArrayList(2) }.add(ti)
            }
        }
        val visited = BooleanArray(tris.size)
        val out = ArrayList<IntArray>()
        for (start in tris.indices) {
            if (visited[start]) continue
            val group = ArrayList<Int>()
            val q = ArrayDeque<Int>()
            q.addLast(start)
            visited[start] = true
            val n0 = normals[start]
            val p0 = pts[tris[start][0]]
            while (q.isNotEmpty()) {
                val cur = q.removeFirst()
                group.add(cur)
                val t = tris[cur]
                for (k in 0 until 3) {
                    val neighbors = edgeToTris[BlendMeshData.key(t[k], t[(k + 1) % 3])] ?: continue
                    for (nb in neighbors) {
                        if (visited[nb]) continue
                        if (normals[nb].dot(n0) > 1.0 - 1e-4) {
                            val tnb = tris[nb]
                            val coplanar = (0 until 3).all { abs((pts[tnb[it]] - p0).dot(n0)) < 1e-4 }
                            if (coplanar) {
                                visited[nb] = true
                                q.addLast(nb)
                            }
                        }
                    }
                }
            }
            if (group.size == 1) {
                out.add(tris[start])
                continue
            }
            val nextVert = HashMap<Int, Int>()
            val dirEdges = HashSet<Long>()
            for (ti in group) {
                val t = tris[ti]
                for (k in 0 until 3) {
                    val u = t[k]; val w = t[(k + 1) % 3]
                    val rev = (w.toLong() shl 32) or (u.toLong() and 0xFFFFFFFFL)
                    val fwd = (u.toLong() shl 32) or (w.toLong() and 0xFFFFFFFFL)
                    if (!dirEdges.remove(rev)) dirEdges.add(fwd)
                }
            }
            var validLoop = true
            for (de in dirEdges) {
                val u = (de ushr 32).toInt()
                val w = (de and 0xFFFFFFFFL).toInt()
                if (nextVert.put(u, w) != null) { validLoop = false; break }
            }
            if (!validLoop || nextVert.size < 3) {
                for (ti in group) out.add(tris[ti])
                continue
            }
            val startV = nextVert.keys.first()
            val poly = ArrayList<Int>(nextVert.size)
            var curV = startV
            var guard = 0
            while (guard++ <= nextVert.size) {
                poly.add(curV)
                curV = nextVert[curV] ?: break
                if (curV == startV) break
            }
            if (curV == startV && poly.size == nextVert.size) {
                out.add(poly.toIntArray())
            } else {
                for (ti in group) out.add(tris[ti])
            }
        }
        return out
    }

    private fun bestShiftAlignment(ringA: IntArray, ringB: IntArray, outCo: ArrayList<Double>): IntArray {
        var bestShift = 0
        var bestCost = Double.POSITIVE_INFINITY
        for (shift in 0 until 4) {
            var cost = 0.0
            for (k in 0 until 4) {
                val ia = ringA[k]
                val ib = ringB[(k + shift) and 3]
                val dx = outCo[ia * 3] - outCo[ib * 3]
                val dy = outCo[ia * 3 + 1] - outCo[ib * 3 + 1]
                val dz = outCo[ia * 3 + 2] - outCo[ib * 3 + 2]
                cost += dx * dx + dy * dy + dz * dz
            }
            if (cost < bestCost) {
                bestCost = cost
                bestShift = shift
            }
        }
        return IntArray(4) { k -> ringB[(k + bestShift) and 3] }
    }

    private fun stitchRings(ring0: IntArray, ring1: IntArray, outFaces: ArrayList<IntArray>) {
        for (k in 0 until 4) {
            val kNext = (k + 1) and 3
            val a0 = ring0[k]; val a1 = ring0[kNext]
            val b0 = ring1[k]; val b1 = ring1[kNext]
            if (a0 != b0 && a1 != b1 && a0 != a1 && b0 != b1) {
                outFaces.add(intArrayOf(b0, b1, a1, a0))
            }
        }
    }

    private fun applyBranchSmoothing(
        outCo: ArrayList<Double>,
        outFaces: ArrayList<IntArray>,
        branchMask: ArrayList<Boolean>,
        smoothFac: Double
    ) {
        val nV = outCo.size / 3
        val neighbors = Array(nV) { LinkedHashSet<Int>() }
        for (f in outFaces) {
            for (k in f.indices) {
                val u = f[k]; val w = f[(k + 1) % f.size]
                if (branchMask[u] && branchMask[w]) {
                    neighbors[u].add(w)
                    neighbors[w].add(u)
                }
            }
        }
        val orig = DoubleArray(outCo.size) { outCo[it] }
        val wBlend = 0.5 * smoothFac
        for (v in 0 until nV) {
            if (!branchMask[v]) continue
            val nb = neighbors[v]
            if (nb.isEmpty()) continue
            var sx = orig[v * 3]; var sy = orig[v * 3 + 1]; var sz = orig[v * 3 + 2]
            for (u in nb) {
                sx += orig[u * 3]; sy += orig[u * 3 + 1]; sz += orig[u * 3 + 2]
            }
            val denom = (nb.size + 1).toDouble()
            outCo[v * 3] = orig[v * 3] * (1.0 - wBlend) + (sx / denom) * wBlend
            outCo[v * 3 + 1] = orig[v * 3 + 1] * (1.0 - wBlend) + (sy / denom) * wBlend
            outCo[v * 3 + 2] = orig[v * 3 + 2] * (1.0 - wBlend) + (sz / denom) * wBlend
        }
    }

    private fun ensureOutwardWinding(outCo: ArrayList<Double>, outFaces: ArrayList<IntArray>) {
        if (outFaces.isEmpty()) return
        val edgeToFaces = HashMap<Long, ArrayList<Int>>()
        for (fi in outFaces.indices) {
            val f = outFaces[fi]
            for (k in f.indices) {
                val key = BlendMeshData.key(f[k], f[(k + 1) % f.size])
                edgeToFaces.getOrPut(key) { ArrayList(2) }.add(fi)
            }
        }
        val visited = BooleanArray(outFaces.size)
        for (start in outFaces.indices) {
            if (visited[start]) continue
            val comp = ArrayList<Int>()
            val q = ArrayDeque<Int>()
            q.addLast(start)
            visited[start] = true
            while (q.isNotEmpty()) {
                val fi = q.removeFirst()
                comp.add(fi)
                val f = outFaces[fi]
                for (k in f.indices) {
                    val u = f[k]; val w = f[(k + 1) % f.size]
                    val nbs = edgeToFaces[BlendMeshData.key(u, w)] ?: continue
                    for (nb in nbs) {
                        if (visited[nb]) continue
                        visited[nb] = true
                        val nf = outFaces[nb]
                        var sameDir = false
                        for (j in nf.indices) {
                            if (nf[j] == u && nf[(j + 1) % nf.size] == w) { sameDir = true; break }
                        }
                        if (sameDir) nf.reverse()
                        q.addLast(nb)
                    }
                }
            }
            var signedVol6 = 0.0
            for (fi in comp) {
                val f = outFaces[fi]
                if (f.size < 3) continue
                val i0 = f[0]
                val ax = outCo[i0 * 3]; val ay = outCo[i0 * 3 + 1]; val az = outCo[i0 * 3 + 2]
                for (k in 1 until f.size - 1) {
                    val i1 = f[k]; val i2 = f[k + 1]
                    val bx = outCo[i1 * 3]; val by = outCo[i1 * 3 + 1]; val bz = outCo[i1 * 3 + 2]
                    val cx = outCo[i2 * 3]; val cy = outCo[i2 * 3 + 1]; val cz = outCo[i2 * 3 + 2]
                    signedVol6 += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
                }
            }
            if (signedVol6 < 0.0) {
                for (fi in comp) outFaces[fi].reverse()
            }
        }
    }
}
