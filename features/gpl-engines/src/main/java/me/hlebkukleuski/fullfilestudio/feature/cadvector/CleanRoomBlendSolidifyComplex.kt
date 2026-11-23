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

import kotlin.math.abs
import kotlin.math.sqrt

internal object BlendSolidifyComplex {
    private const val EMPTY = -1
    private const val FLT_EPSILON = 1.1920929E-7f
    private const val PI_F = Math.PI.toFloat()

    private class NewFaceRef(val start: Int, val size: Int, val index: Int, val reversed: Boolean) {
        val linkEdges = arrayOfNulls<NewEdgeRef>(size)
    }

    private class OldEdgeFaceRef(var faces: IntArray, var facesLen: Int, var facesReversed: BooleanArray, var used: Int)

    private class OldVertEdgeRef(val edges: IntArray, var edgesLen: Int)

    private class NewEdgeRef(val oldEdge: Int, val faces: IntArray, val angle: Float, var newEdge: Int) {
        val linkEdgeGroups = arrayOfNulls<EdgeGroup>(2)
    }

    private class EdgeGroup(
        var edges: Array<NewEdgeRef?>, var edgesLen: Int, var isOrigClosed: Boolean, var isEvenSplit: Boolean,
        var split: Int, var topoGroup: Int
    ) {
        var valid = true
        var openFaceEdge = EMPTY
        var isSingularity = false
        val co = FloatArray(3)
        val no = FloatArray(3)
        var newVert = EMPTY

        fun copy(): EdgeGroup = EdgeGroup(edges, edgesLen, isOrigClosed, isEvenSplit, split, topoGroup).also {
            it.valid = valid; it.openFaceEdge = openFaceEdge; it.isSingularity = isSingularity
            co.copyInto(it.co); no.copyInto(it.no); it.newVert = newVert
        }
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun lenSq(a: FloatArray) = dot(a, a)

    private fun normalize(v: FloatArray): Float {
        var d = dot(v, v)
        if (d > 1.0e-35f) {
            d = sqrt(d)
            val m = 1.0f / d
            v[0] *= m; v[1] *= m; v[2] *= m
        } else {
            v[0] = 0f; v[1] = 0f; v[2] = 0f
            d = 0.0f
        }
        return d
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun saasin(x: Float): Float = if (x <= -1.0f) -PI_F / 2 else if (x >= 1.0f) PI_F / 2 else kotlin.math.asin(x)

    private fun angleV3V3V3(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val v1 = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val v2 = floatArrayOf(b[0] - c[0], b[1] - c[1], b[2] - c[2])
        normalize(v1); normalize(v2)
        if (dot(v1, v2) >= 0.0f) {
            val x = v1[0] - v2[0]; val y = v1[1] - v2[1]; val z = v1[2] - v2[2]
            return 2.0f * saasin(sqrt(x * x + y * y + z * z) / 2.0f)
        }
        val x = v1[0] + v2[0]; val y = v1[1] + v2[1]; val z = v1[2] + v2[2]
        return PI_F - 2.0f * saasin(sqrt(x * x + y * y + z * z) / 2.0f)
    }

    private fun projectV3V3(r: FloatArray, a: FloatArray): Float {
        val d = dot(r, a)
        r[0] -= a[0] * d; r[1] -= a[1] * d; r[2] -= a[2] * d
        return d
    }

    private fun angleSignedOnAxis(n: FloatArray, refN: FloatArray, axis: FloatArray): Float {
        var d = dot(n, refN)
        d = d.coerceIn(-1f, 1f)
        var angle = kotlin.math.acos(d)
        if (dot(cross(n, refN), axis) >= 0) angle = (2 * Math.PI - angle).toFloat()
        return angle
    }

    private fun clampNonzero(value: Float, eps: Float): Float = if (value < 0.0f) minOf(value, -eps) else maxOf(value, eps)

    private fun invertM3(m: Array<FloatArray>) {
        val r = Array(3) { FloatArray(3) }
        r[0][0] = m[1][1] * m[2][2] - m[1][2] * m[2][1]
        r[0][1] = -m[0][1] * m[2][2] + m[0][2] * m[2][1]
        r[0][2] = m[0][1] * m[1][2] - m[0][2] * m[1][1]
        r[1][0] = -m[1][0] * m[2][2] + m[1][2] * m[2][0]
        r[1][1] = m[0][0] * m[2][2] - m[0][2] * m[2][0]
        r[1][2] = -m[0][0] * m[1][2] + m[0][2] * m[1][0]
        r[2][0] = m[1][0] * m[2][1] - m[1][1] * m[2][0]
        r[2][1] = -m[0][0] * m[2][1] + m[0][1] * m[2][0]
        r[2][2] = m[0][0] * m[1][1] - m[0][1] * m[1][0]
        var det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[1][0] * (m[0][1] * m[2][2] - m[0][2] * m[2][1]) + m[2][0] * (m[0][1] * m[1][2] - m[0][2] * m[1][1])
        if (det != 0.0f) {
            det = 1.0f / det
            for (a in 0 until 3) for (b in 0 until 3) r[a][b] *= det
        }
        for (a in 0 until 3) r[a].copyInto(m[a])
    }

    private fun edgeList(d: BlendMeshData): IntArray {
        val out = ArrayList<Int>()
        val seen = HashSet<Long>()
        d.fileEdges?.let { fe -> for (e in 0 until fe.size / 2) { out.add(fe[e * 2]); out.add(fe[e * 2 + 1]); seen.add(BlendMeshData.key(fe[e * 2], fe[e * 2 + 1])) } }
        for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
            val a = d.corners[d.fStart[f] + k]; val b = d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]
            if (seen.add(BlendMeshData.key(a, b))) { out.add(a); out.add(b) }
        }
        for (e in 0 until d.looseEdges.size / 2) {
            val a = d.looseEdges[e * 2]; val b = d.looseEdges[e * 2 + 1]
            if (seen.add(BlendMeshData.key(a, b))) { out.add(a); out.add(b) }
        }
        return out.toIntArray()
    }

    fun apply(d: BlendMeshData, p: BlendMeshOps.SolidifyParams, offsetMode: Int, boundaryMode: Int, mergeTolerance: Float, bevelConvex: Float): BlendMeshData {
        val vertsNum = d.nV
        val origEdges = edgeList(d)
        val edgesNum = origEdges.size / 2
        val facesNum = d.nF
        if (facesNum == 0 && vertsNum != 0) return d
        val edgeIndex = HashMap<Long, Int>()
        for (e in 0 until edgesNum) edgeIndex.putIfAbsent(BlendMeshData.key(origEdges[e * 2], origEdges[e * 2 + 1]), e)
        val origCornerVerts = d.corners
        val origCornerEdges = IntArray(origCornerVerts.size)
        for (f in 0 until facesNum) for (k in 0 until d.fSize[f]) {
            val c = d.fStart[f] + k
            origCornerEdges[c] = edgeIndex[BlendMeshData.key(origCornerVerts[c], origCornerVerts[d.fStart[f] + (k + 1) % d.fSize[f]])]!!
        }
        fun ev(e: Int, k: Int) = origEdges[e * 2 + k]

        val flag = p.flag
        val matNrs = if (p.totcol > 1) p.totcol else 1
        val matNrMax = matNrs - 1
        val matOfs = if (matNrs > 1) p.matOfs else 0
        val matOfsRim = if (matNrs > 1) p.matOfsRim else 0
        val ofsFront = (p.offsetFac + 1.0f) * 0.5f * p.offset
        val ofsBack = ofsFront - p.offset * p.offsetFac
        val ofsFrontClamped = clampNonzero(ofsFront, 1e-5f)
        val ofsBackClamped = clampNonzero(ofsBack, 1e-5f)
        val offsetFacVg = p.offsetFacVg
        val offsetFacVgInv = 1.0f - p.offsetFacVg
        val offset = abs(p.offset) * p.offsetClamp
        val doAngleClamp = flag and (1 shl 7) != 0
        val doFlip = ((flag and (1 shl 5)) != 0) == (p.offset > 0)
        val doRim = flag and 1 != 0
        val doShell = !((flag and 1 != 0) && (flag and (1 shl 6) != 0))
        val doClamp = p.offsetClamp != 0.0f
        val weights = p.groupWeights
        val doFlatFaces = weights != null && (flag and (1 shl 8) != 0)
        val origVertBweight = d.vertAttrs["bevel_weight_vert"]
        val origEdgeBweight = d.edgeAttrs["bevel_weight_edge"]?.let { m -> FloatArray(edgesNum) { e -> m[BlendMeshData.key(ev(e, 0), ev(e, 1))] ?: 0f } }
        val origEdgeCrease = if (d.edgeCrease.isNotEmpty() || d.edgeAttrs.containsKey("crease_edge")) FloatArray(edgesNum) { e -> d.edgeCrease[BlendMeshData.key(ev(e, 0), ev(e, 1))] ?: 0f } else null

        var newVertsNum = 0
        var newEdgesNum = 0
        var newLoopsNum = 0
        var newFacesNum = 0

        val faceNors = BlendNormals.faceNormals(d.co, d.corners, d.fStart, d.fSize)
        fun fno(f: Int) = floatArrayOf(faceNors[f * 3], faceNors[f * 3 + 1], faceNors[f * 3 + 2])
        val faceSides = arrayOfNulls<NewFaceRef>(facesNum * 2)
        val nullFaces = if (offsetMode == 2) BooleanArray(facesNum) else null
        var largestNgon = 3
        for (i in 0 until facesNum) {
            val fn = fno(i)
            if (lenSq(fn) < 0.5f) {
                val e = origCornerEdges[d.fStart[i]]
                val dir = FloatArray(3) { d.co[ev(e, 1) * 3 + it] - d.co[ev(e, 0) * 3 + it] }
                if (abs(dir[2]) < abs(dir[1])) faceNors[i * 3 + 2] = 1.0f else faceNors[i * 3 + 1] = 1.0f
                if (nullFaces != null) nullFaces[i] = true
            }
            faceSides[i * 2] = NewFaceRef(d.fStart[i], d.fSize[i], i, false)
            faceSides[i * 2 + 1] = NewFaceRef(d.fStart[i], d.fSize[i], i, true)
            if (d.fSize[i] > largestNgon) largestNgon = d.fSize[i]
            if (doShell) { newFacesNum += 2; newLoopsNum += d.fSize[i] * 2 }
        }
        fun side(i: Int) = faceSides[i]!!

        val edgeAdjFacesLen = IntArray(edgesNum)
        for (i in 0 until facesNum) for (k in 0 until d.fSize[i]) edgeAdjFacesLen[origCornerEdges[d.fStart[i] + k]]++
        val origEdgeDataArr = arrayOfNulls<Array<NewEdgeRef>>(edgesNum)
        val origEdgeLengths = FloatArray(edgesNum)
        val origVertGroupsArr = arrayOfNulls<Array<EdgeGroup?>>(vertsNum)
        val vm = IntArray(vertsNum) { it }
        var edgeIdx = 0
        var loopIndex = 0
        var faceIndex = 0
        var hasSingularities = false
        val vertAdjEdges = arrayOfNulls<OldVertEdgeRef>(vertsNum)
        val origMvertCo = Array(vertsNum) { v -> floatArrayOf(d.co[v * 3], d.co[v * 3 + 1], d.co[v * 3 + 2]) }

        run {
            val edgeAdjFaces = arrayOfNulls<OldEdgeFaceRef>(edgesNum)
            for (i in 0 until facesNum) for (k in 0 until d.fSize[i]) {
                val corner = d.fStart[i] + k
                val vert = origCornerVerts[corner]
                val edge = origCornerEdges[corner]
                val reversed = ev(edge, 1) != vert
                val ref = edgeAdjFaces[edge]
                if (ref == null) {
                    val len = edgeAdjFacesLen[edge]
                    val adj = IntArray(len) { EMPTY }
                    adj[0] = i
                    val rev = BooleanArray(len)
                    rev[0] = reversed
                    edgeAdjFaces[edge] = OldEdgeFaceRef(adj, len, rev, 1)
                } else {
                    for (q in 1 until ref.facesLen) if (ref.faces[q] == EMPTY) { ref.faces[q] = i; ref.facesReversed[q] = reversed; break }
                }
            }
            val edgedir = FloatArray(3)
            val vertAdjEdgesLen = IntArray(vertsNum)
            run {
                val faceSingularity = BooleanArray(facesNum)
                val mergeTolSqr = mergeTolerance * mergeTolerance
                val combinedVerts = IntArray(vertsNum)
                for (i in 0 until edgesNum) {
                    if (edgeAdjFacesLen[i] > 0) {
                        var v1 = vm[ev(i, 0)]
                        var v2 = vm[ev(i, 1)]
                        if (v1 == v2) continue
                        if (v2 < v1) { val t = v1; v1 = v2; v2 = t }
                        for (q in 0 until 3) edgedir[q] = origMvertCo[v2][q] - origMvertCo[v1][q]
                        origEdgeLengths[i] = lenSq(edgedir)
                        if (origEdgeLengths[i] <= mergeTolSqr) {
                            var canMerge = true
                            var k = 0
                            while (k < edgesNum && canMerge) {
                                val a0 = vm[ev(k, 0)].let { it == v1 || it == v2 }
                                val a1 = vm[ev(k, 1)].let { it == v1 || it == v2 }
                                if (k != i && edgeAdjFacesLen[k] > 0 && a0 != a1) {
                                    val ref = edgeAdjFaces[k]!!
                                    var j = 0
                                    while (j < ref.facesLen && canMerge) {
                                        val f = ref.faces[j]
                                        val st = d.fStart[f]; val sz = d.fSize[f]
                                        var changes = 0
                                        var cur = sz - 1
                                        var next = 0
                                        while (next < sz && changes <= 2) {
                                            val cv = vm[origCornerVerts[st + cur]]
                                            val nv = vm[origCornerVerts[st + next]]
                                            if ((cv == v1 || cv == v2) != (nv == v1 || nv == v2)) changes++
                                            cur = next
                                            next++
                                        }
                                        canMerge = canMerge && changes <= 2
                                        j++
                                    }
                                }
                                k++
                            }
                            if (!canMerge) {
                                origEdgeLengths[i] = 0.0f
                                vertAdjEdgesLen[v1]++
                                vertAdjEdgesLen[v2]++
                                continue
                            }
                            val fac = (combinedVerts[v2] + 1) / (combinedVerts[v1] + combinedVerts[v2] + 2).toFloat()
                            for (q in 0 until 3) { edgedir[q] *= fac; origMvertCo[v1][q] += edgedir[q] }
                            for (j in v2 until vertsNum) if (vm[j] == v2) vm[j] = v1
                            vertAdjEdgesLen[v1] += vertAdjEdgesLen[v2]
                            vertAdjEdgesLen[v2] = 0
                            combinedVerts[v1] += combinedVerts[v2] + 1
                            if (doShell) newLoopsNum -= edgeAdjFacesLen[i] * 2
                            edgeAdjFacesLen[i] = 0
                            edgeAdjFaces[i] = null
                        } else {
                            origEdgeLengths[i] = sqrt(origEdgeLengths[i])
                            vertAdjEdgesLen[v1]++
                            vertAdjEdgesLen[v2]++
                        }
                    }
                }
                for (i in 0 until edgesNum) {
                    val v1 = vm[ev(i, 0)]
                    val v2 = vm[ev(i, 1)]
                    val ref = edgeAdjFaces[i]
                    if (v1 == v2 && ref != null) {
                        for (j in 0 until ref.facesLen) {
                            val face = ref.faces[j]
                            if (!faceSingularity[face]) {
                                var isSing = true
                                for (k in 0 until d.fSize[face]) if (vm[origCornerVerts[d.fStart[face] + k]] != v1) { isSing = false; break }
                                if (isSing) { faceSingularity[face] = true; if (doShell) newFacesNum -= 2 }
                            }
                        }
                        if (doShell) newLoopsNum -= edgeAdjFacesLen[i] * 2
                        edgeAdjFacesLen[i] = 0
                        edgeAdjFaces[i] = null
                    }
                }
            }
            run {
                var i = 0
                while (i < edgesNum) {
                    if (edgeAdjFacesLen[i] > 0) {
                        val vs = intArrayOf(vm[ev(i, 0)], vm[ev(i, 1)])
                        var invalidEdgeIndex = 0
                        var invalidEdgeReversed = false
                        for (j in 0 until 2) {
                            val vert = vs[j]
                            val len = vertAdjEdgesLen[vert]
                            if (len > 0) {
                                val ref = vertAdjEdges[vert]
                                if (ref == null) {
                                    val adj = IntArray(len) { EMPTY }
                                    adj[0] = i
                                    vertAdjEdges[vert] = OldVertEdgeRef(adj, 1)
                                } else {
                                    var k = 0
                                    while (k < len && k <= ref.edgesLen) {
                                        val edge = ref.edges[k]
                                        if (edge == EMPTY || k == ref.edgesLen) {
                                            ref.edges[k] = i
                                            ref.edgesLen++
                                            break
                                        }
                                        if (vm[ev(edge, 0)] == vs[1 - j]) { invalidEdgeIndex = edge + 1; invalidEdgeReversed = j == 0; break }
                                        if (vm[ev(edge, 1)] == vs[1 - j]) { invalidEdgeIndex = edge + 1; invalidEdgeReversed = j == 1; break }
                                        k++
                                    }
                                    if (invalidEdgeIndex != 0) {
                                        if (j == 1) vertAdjEdges[vs[0]]!!.edgesLen--
                                        break
                                    }
                                }
                            }
                        }
                        if (invalidEdgeIndex != 0) {
                            val tmp = invalidEdgeIndex - 1
                            invalidEdgeIndex = i
                            i = tmp
                            val iAdj = edgeAdjFaces[i]!!
                            val invAdj = edgeAdjFaces[invalidEdgeIndex]!!
                            var j = 0
                            for (k in 0 until iAdj.facesLen) for (l in 0 until invAdj.facesLen) {
                                if (iAdj.faces[k] == invAdj.faces[l] && iAdj.faces[k] != EMPTY) {
                                    iAdj.faces[k] = EMPTY
                                    invAdj.faces[l] = EMPTY
                                    j++
                                }
                            }
                            if (doShell) { newFacesNum -= 2 * j; newLoopsNum -= 4 * j }
                            val len = iAdj.facesLen + invAdj.facesLen - 2 * j
                            val adj = IntArray(len)
                            val adjRev = BooleanArray(len)
                            j = 0
                            for (k in 0 until iAdj.facesLen) if (iAdj.faces[k] != EMPTY) { adj[j] = iAdj.faces[k]; adjRev[j++] = iAdj.facesReversed[k] }
                            for (k in 0 until invAdj.facesLen) if (invAdj.faces[k] != EMPTY) { adj[j] = invAdj.faces[k]; adjRev[j++] = invalidEdgeReversed != invAdj.facesReversed[k] }
                            edgeAdjFacesLen[invalidEdgeIndex] = 0
                            edgeAdjFacesLen[i] = len
                            iAdj.facesLen = len
                            iAdj.faces = adj
                            iAdj.facesReversed = adjRev
                            iAdj.used += invAdj.used
                            edgeAdjFaces[invalidEdgeIndex] = iAdj
                            i = invalidEdgeIndex
                        }
                    }
                    i++
                }
            }
            for (i in 0 until edgesNum) {
                if (edgeAdjFacesLen[i] > 0) {
                    val adjFaces = edgeAdjFaces[i]!!
                    var adjLen = adjFaces.facesLen
                    if (adjLen > 1) {
                        var j = 0
                        while (j < adjLen) {
                            val face = adjFaces.faces[j]
                            val jStart = d.fStart[face]
                            val totloop = d.fSize[face]
                            val jFirstV = vm[origCornerVerts[jStart]]
                            for (k in j + 1 until adjLen) {
                                if (d.fSize[adjFaces.faces[k]] != totloop) continue
                                val kStart = d.fStart[adjFaces.faces[k]]
                                var l = 0
                                while (l < totloop && vm[origCornerVerts[kStart + l]] != jFirstV) l++
                                if (l == totloop) continue
                                val reversed = adjFaces.facesReversed[j] != adjFaces.facesReversed[k]
                                val countDir = if (reversed) -1 else 1
                                var hasDiff = false
                                var m = 0
                                var n = l + totloop
                                while (m < totloop && !hasDiff) {
                                    hasDiff = vm[origCornerVerts[jStart + m]] != vm[origCornerVerts[kStart + n % totloop]]
                                    m++
                                    n += countDir
                                }
                                if (!hasDiff) {
                                    var delLoops = 0
                                    for (mm in 0 until totloop) {
                                        val e = origCornerEdges[jStart + mm]
                                        val eAdj = edgeAdjFaces[e] ?: continue
                                        var fi = j
                                        val fl = eAdj.facesLen
                                        if (eAdj !== adjFaces) {
                                            fi = 0
                                            while (fi < fl && eAdj.faces[fi] != face) fi++
                                            if (fi == fl) continue
                                        } else adjLen--
                                        System.arraycopy(eAdj.faces, fi + 1, eAdj.faces, fi, fl - fi - 1)
                                        System.arraycopy(eAdj.facesReversed, fi + 1, eAdj.facesReversed, fi, fl - fi - 1)
                                        eAdj.facesLen--
                                        if (edgeAdjFacesLen[e] > 0) {
                                            edgeAdjFacesLen[e]--
                                            if (edgeAdjFacesLen[e] == 0) { eAdj.used--; edgeAdjFaces[e] = null }
                                        } else if (eAdj.used > 1) {
                                            for (nn in 0 until edgesNum) {
                                                if (edgeAdjFaces[nn] === eAdj && edgeAdjFacesLen[nn] > 0) {
                                                    edgeAdjFacesLen[nn]--
                                                    if (edgeAdjFacesLen[nn] == 0) { edgeAdjFaces[nn]!!.used--; edgeAdjFaces[nn] = null }
                                                    break
                                                }
                                            }
                                        }
                                        delLoops++
                                    }
                                    if (doShell) { newFacesNum -= 2; newLoopsNum -= 2 * delLoops }
                                    break
                                }
                            }
                            j++
                        }
                    }
                }
            }
            for (i in 0 until edgesNum) {
                val v1 = vm[ev(i, 0)]
                val v2 = vm[ev(i, 1)]
                if (edgeAdjFacesLen[i] > 0) {
                    if (origEdgeLengths[i] > FLT_EPSILON) {
                        for (q in 0 until 3) edgedir[q] = origMvertCo[v2][q] - origMvertCo[v1][q]
                        val inv = 1.0f / origEdgeLengths[i]
                        for (q in 0 until 3) edgedir[q] *= inv
                    } else {
                        val pos = origMvertCo[v2].copyOf()
                        val v1Dir = FloatArray(3)
                        val link1 = vertAdjEdges[v1]!!
                        for (j in 0 until link1.edgesLen) {
                            val e = link1.edges[j]
                            if (edgeAdjFacesLen[e] > 0 && e != i) {
                                val other = vm[if (vm[ev(e, 0)] == v1) ev(e, 1) else ev(e, 0)]
                                for (q in 0 until 3) { edgedir[q] = origMvertCo[other][q] - pos[q]; v1Dir[q] += edgedir[q] }
                            }
                        }
                        val v2Dir = FloatArray(3)
                        val link2 = vertAdjEdges[v2]!!
                        for (j in 0 until link2.edgesLen) {
                            val e = link2.edges[j]
                            if (edgeAdjFacesLen[e] > 0 && e != i) {
                                val other = vm[if (vm[ev(e, 0)] == v2) ev(e, 1) else ev(e, 0)]
                                for (q in 0 until 3) { edgedir[q] = origMvertCo[other][q] - pos[q]; v2Dir[q] += edgedir[q] }
                            }
                        }
                        for (q in 0 until 3) edgedir[q] = v2Dir[q] - v1Dir[q]
                        if (normalize(edgedir) == 0.0f) { edgedir[0] = 0f; edgedir[1] = 0f; edgedir[2] = 1f }
                    }
                    val adjFaces = edgeAdjFaces[i]!!
                    val adjLen = adjFaces.facesLen
                    val newEdgesLen: Int
                    val sortedAngle = FloatArray(maxOf(adjLen, 1))
                    val sortedFace = IntArray(maxOf(adjLen, 1))
                    var order = IntArray(maxOf(adjLen, 1)) { it }
                    if (adjLen > 1) {
                        newEdgesLen = adjLen
                        val refNor = FloatArray(3)
                        for (j in 0 until adjLen) {
                            val fi = adjFaces.faces[j]
                            val nor = fno(fi)
                            if (adjFaces.facesReversed[j]) { nor[0] = -nor[0]; nor[1] = -nor[1]; nor[2] = -nor[2] }
                            var dd = 1f
                            if (d.fSize[fi] > 3) {
                                dd = projectV3V3(nor, edgedir)
                                dd = if (dd != 0f) normalize(nor) else 1f
                            }
                            if (dd == 0.0f) sortedAngle[j] = 0.0f
                            else if (j == 0) { nor.copyInto(refNor); sortedAngle[j] = 0.0f }
                            else sortedAngle[j] = -angleSignedOnAxis(nor, refNor, edgedir)
                            sortedFace[j] = fi * 2 + if (adjFaces.facesReversed[j]) 1 else 0
                        }
                        BevelCurveProfile.darwinQsort(order, adjLen) { x, y -> (if (sortedAngle[x] > sortedAngle[y]) 1 else 0) - (if (sortedAngle[x] < sortedAngle[y]) 1 else 0) }
                    } else {
                        newEdgesLen = 2
                        sortedFace[0] = adjFaces.faces[0] * 2 + if (adjFaces.facesReversed[0]) 1 else 0
                        order = intArrayOf(0)
                        if (doRim) { newLoopsNum += 2; newFacesNum++ }
                    }
                    val newEdges = arrayOfNulls<NewEdgeRef>(newEdgesLen)
                    val pending = ArrayList<Int>()
                    for (j in 0 until newEdgesLen) {
                        val f0: Int
                        val f1: Int
                        val angle: Float
                        if (adjLen > 1) {
                            val nextJ = if (j + 1 == adjLen) 0 else j + 1
                            f0 = sortedFace[order[j]]
                            f1 = sortedFace[order[nextJ]] xor 1
                            var a = sortedAngle[order[nextJ]] - sortedAngle[order[j]]
                            if (a < 0) a += (2 * Math.PI).toFloat()
                            angle = a
                        } else {
                            val s0 = sortedFace[0]
                            f0 = if (side(s0).reversed) s0 - j else s0 + j
                            f1 = EMPTY
                            angle = 0f
                        }
                        val idx = if (doShell || (adjLen == 1 && doRim)) 0 else EMPTY
                        val ed = NewEdgeRef(i, intArrayOf(f0, f1), angle, idx)
                        newEdges[j] = ed
                        for (k in 0 until 2) {
                            val fk = ed.faces[k]
                            if (fk != EMPTY) {
                                val fr = side(fk)
                                for (l in 0 until fr.size) {
                                    val edge = origCornerEdges[fr.start + l]
                                    if (edgeAdjFaces[edge] === edgeAdjFaces[i]) {
                                        if (edge != i && origEdgeDataArr[edge] == null && edge !in pending) pending.add(edge)
                                        fr.linkEdges[l] = ed
                                        break
                                    }
                                }
                            }
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val arr = newEdges as Array<NewEdgeRef>
                    for (e in pending) origEdgeDataArr[e] = arr
                    origEdgeDataArr[i] = arr
                    if (doShell || (adjLen == 1 && doRim)) newEdgesNum += newEdgesLen
                }
            }
            for (i in 0 until edgesNum) edgeAdjFaces[i]?.let { if (it.used > 1) it.used-- }
        }

        for (i in 0 until vertsNum) {
            val adjRef = vertAdjEdges[i]
            if (adjRef != null && adjRef.edgesLen >= 2) {
                var edgeGroups: Array<EdgeGroup?>
                var egIndex = -1
                var containsLongGroups = false
                var topoGroups = 0
                run {
                    val adjEdges = adjRef.edges
                    val totAdj = adjRef.edgesLen
                    val unassigned = ArrayList<NewEdgeRef?>()
                    for (j in 0 until totAdj) origEdgeDataArr[adjEdges[j]]?.let { for (e in it) unassigned.add(e) }
                    val unassignedLen = unassigned.size
                    edgeGroups = arrayOfNulls(unassignedLen + 2)
                    var assignedLen = 0
                    var foundEdge: NewEdgeRef?
                    var foundEdgeIndex: Int
                    var insertAtStart: Boolean
                    var egCapacity = 5
                    val trackFaces = intArrayOf(EMPTY, EMPTY)
                    var lastOpenEdgeTrack = EMPTY
                    while (assignedLen < unassignedLen) {
                        foundEdge = null
                        insertAtStart = false
                        foundEdgeIndex = 0
                        if (egIndex >= 0 && edgeGroups[egIndex]!!.edgesLen == 0) {
                            var j = 0
                            var edge: NewEdgeRef? = null
                            while (edge == null && j < unassignedLen) {
                                edge = unassigned[j++]
                                if (edge != null && lastOpenEdgeTrack != EMPTY && (edge.faces[0] != lastOpenEdgeTrack || edge.faces[1] != EMPTY)) edge = null
                            }
                            if (edge == null && lastOpenEdgeTrack != EMPTY) {
                                topoGroups++
                                lastOpenEdgeTrack = EMPTY
                                edgeGroups[egIndex]!!.topoGroup++
                                j = 0
                                while (edge == null && j < unassignedLen) edge = unassigned[j++]
                            } else if (lastOpenEdgeTrack == EMPTY && egIndex > 0) {
                                topoGroups++
                                edgeGroups[egIndex]!!.topoGroup++
                            }
                            foundEdgeIndex = j - 1
                            foundEdge = edge!!
                            if (lastOpenEdgeTrack == EMPTY && vm[ev(edge.oldEdge, 0)] == i) {
                                trackFaces[0] = edge.faces[0]
                                trackFaces[1] = edge.faces[1]
                                if (edge.faces[1] == EMPTY) lastOpenEdgeTrack = edge.faces[0] xor 1
                            } else {
                                trackFaces[0] = edge.faces[1]
                                trackFaces[1] = edge.faces[0]
                            }
                        } else if (egIndex >= 0) {
                            foundEdgeIndex = 0
                            while (foundEdgeIndex < unassignedLen) {
                                val edge = unassigned[foundEdgeIndex]
                                if (edge != null) {
                                    if (edge.faces[0] == trackFaces[1]) {
                                        insertAtStart = false
                                        trackFaces[1] = edge.faces[1]
                                        foundEdge = edge
                                        if (edge.faces[1] == EMPTY) {
                                            edgeGroups[egIndex]!!.isOrigClosed = false
                                            lastOpenEdgeTrack = edge.faces[0] xor 1
                                        }
                                        break
                                    }
                                    if (edge.faces[0] == trackFaces[0]) {
                                        insertAtStart = true
                                        trackFaces[0] = edge.faces[1]
                                        foundEdge = edge
                                        if (edge.faces[1] == EMPTY) edgeGroups[egIndex]!!.isOrigClosed = false
                                        break
                                    }
                                    if (edge.faces[1] != EMPTY) {
                                        if (edge.faces[1] == trackFaces[1]) {
                                            insertAtStart = false
                                            trackFaces[1] = edge.faces[0]
                                            foundEdge = edge
                                            break
                                        }
                                        if (edge.faces[1] == trackFaces[0]) {
                                            insertAtStart = true
                                            trackFaces[0] = edge.faces[0]
                                            foundEdge = edge
                                            break
                                        }
                                    }
                                }
                                foundEdgeIndex++
                            }
                        }
                        if (foundEdge != null) {
                            unassigned[foundEdgeIndex] = null
                            assignedLen++
                            val g = edgeGroups[egIndex]!!
                            val needed = g.edgesLen + 1
                            if (needed > egCapacity) {
                                egCapacity = needed + 1
                                val ne = arrayOfNulls<NewEdgeRef>(egCapacity)
                                System.arraycopy(g.edges, 0, ne, if (insertAtStart) 1 else 0, g.edgesLen)
                                g.edges = ne
                            } else if (insertAtStart) {
                                System.arraycopy(g.edges, 0, g.edges, 1, g.edgesLen)
                            }
                            g.edges[if (insertAtStart) 0 else g.edgesLen] = foundEdge
                            g.edgesLen++
                            if (g.edges[g.edgesLen - 1]!!.faces[1] != EMPTY) lastOpenEdgeTrack = EMPTY
                            if (g.edgesLen > 3) containsLongGroups = true
                        } else {
                            egIndex++
                            egCapacity = 5
                            edgeGroups[egIndex] = EdgeGroup(arrayOfNulls(egCapacity), 0, true, false, 0, topoGroups)
                            trackFaces[0] = EMPTY
                            trackFaces[1] = EMPTY
                        }
                    }
                    egIndex++
                    topoGroups++
                }
                fun move(dst: Int, src: Int, count: Int) {
                    if (count <= 0) return
                    val tmp = Array(count) { edgeGroups[src + it]?.copy() }
                    for (k in 0 until count) edgeGroups[dst + k] = tmp[k]
                }
                run {
                    var splits = 0
                    if (containsLongGroups) {
                        var addIndex = 0
                        for (j in 0 until egIndex) {
                            val edgesLen = edgeGroups[j + addIndex]!!.edgesLen
                            if (edgesLen > 3) {
                                var hasDoubles = false
                                val doubles = BooleanArray(edgesLen)
                                val g = edgeGroups[j + addIndex]!!.copy()
                                for (k in 0 until edgesLen) for (l in k + 1 until edgesLen) {
                                    if (g.edges[k]!!.oldEdge == g.edges[l]!!.oldEdge) { doubles[k] = true; doubles[l] = true; hasDoubles = true }
                                }
                                if (hasDoubles) {
                                    val priorSplits = splits
                                    val priorIndex = addIndex
                                    var uniqueStart = -1
                                    var firstUniqueEnd = -1
                                    var lastSplit = -1
                                    var firstSplit = -1
                                    var firstEvenSplit = false
                                    var realK = 0
                                    while (realK < edgesLen || (g.isOrigClosed && (realK <= (if (firstUniqueEnd == -1) 0 else firstUniqueEnd) + edgesLen || firstSplit != lastSplit))) {
                                        val k = realK % edgesLen
                                        if (!doubles[k]) {
                                            if (firstUniqueEnd != -1 && uniqueStart == -1) uniqueStart = realK
                                        } else if (firstUniqueEnd == -1) {
                                            firstUniqueEnd = k
                                        } else if (uniqueStart != -1) {
                                            val split = ((uniqueStart + realK + 1) / 2) % edgesLen
                                            val isEvenSplit = ((uniqueStart + realK) and 1) != 0
                                            if (lastSplit != -1) {
                                                if (priorSplits != splits) {
                                                    move(j + addIndex + 1, j + addIndex, egIndex - j)
                                                    addIndex++
                                                }
                                                val newGroup = if (lastSplit > split) {
                                                    val len = (split + edgesLen) - lastSplit
                                                    val es = arrayOfNulls<NewEdgeRef>(len)
                                                    System.arraycopy(g.edges, lastSplit, es, 0, edgesLen - lastSplit)
                                                    System.arraycopy(g.edges, 0, es, edgesLen - lastSplit, split)
                                                    EdgeGroup(es, len, g.isOrigClosed, isEvenSplit, addIndex - priorIndex + 1 + (if (!g.isOrigClosed) 1 else 0), g.topoGroup)
                                                } else {
                                                    val len = split - lastSplit
                                                    val es = arrayOfNulls<NewEdgeRef>(len)
                                                    System.arraycopy(g.edges, lastSplit, es, 0, len)
                                                    EdgeGroup(es, len, g.isOrigClosed, isEvenSplit, addIndex - priorIndex + 1 + (if (!g.isOrigClosed) 1 else 0), g.topoGroup)
                                                }
                                                edgeGroups[j + addIndex] = newGroup
                                                splits++
                                            }
                                            lastSplit = split
                                            if (firstSplit == -1) { firstSplit = split; firstEvenSplit = isEvenSplit }
                                            uniqueStart = -1
                                        }
                                        realK++
                                    }
                                    if (firstSplit != -1) {
                                        if (!g.isOrigClosed) {
                                            if (priorSplits != splits) {
                                                move(j + priorIndex + 1, j + priorIndex, egIndex + addIndex - (j + priorIndex))
                                                move(j + addIndex + 2, j + addIndex + 1, egIndex - j)
                                                addIndex++
                                            } else {
                                                move(j + addIndex + 2, j + addIndex + 1, egIndex - j - 1)
                                            }
                                            val esA = arrayOfNulls<NewEdgeRef>(firstSplit)
                                            System.arraycopy(g.edges, 0, esA, 0, firstSplit)
                                            edgeGroups[j + priorIndex] = EdgeGroup(esA, firstSplit, g.isOrigClosed, firstEvenSplit, 1, g.topoGroup)
                                            addIndex++
                                            splits++
                                            val lenB = edgesLen - lastSplit
                                            val esB = arrayOfNulls<NewEdgeRef>(lenB)
                                            System.arraycopy(g.edges, lastSplit, esB, 0, lenB)
                                            edgeGroups[j + addIndex] = EdgeGroup(esB, lenB, g.isOrigClosed, false, addIndex - priorIndex + 1, g.topoGroup)
                                        }
                                    }
                                    if (firstUniqueEnd != -1 && priorSplits == splits) {
                                        hasSingularities = true
                                        edgeGroups[j + addIndex]!!.isSingularity = true
                                    }
                                }
                            }
                        }
                    }
                }
                origVertGroupsArr[i] = edgeGroups
                fun valid(k: Int) = k < edgeGroups.size && edgeGroups[k]?.valid == true
                run {
                    var newVerts = 0
                    var containsOpenSplits = false
                    var openEdges = 0
                    var containsSplits = 0
                    var lastAdded = 0
                    var firstAdded = 0
                    var firstSet = false
                    var gi = 0
                    while (valid(gi)) {
                        val g = edgeGroups[gi]!!
                        for (j in 0 until g.edgesLen) {
                            val e = g.edges[j]!!
                            val flip = if (vm[ev(e.oldEdge, 1)] == i) 1 else 0
                            e.linkEdgeGroups[flip] = g
                        }
                        var added = 0
                        if (doShell || (doRim && !g.isOrigClosed)) {
                            g.newVert = newVertsNum++
                            if (doRim || (doShell && g.split != 0)) {
                                newVerts++
                                if (g.split != 0) containsSplits++
                                containsOpenSplits = containsOpenSplits or (g.split != 0 && !g.isOrigClosed)
                                added = g.split
                            }
                        }
                        if (added < lastAdded) openEdges++
                        if (!firstSet) { firstSet = true; firstAdded = added }
                        lastAdded = added
                        if (!valid(gi + 1) || g.topoGroup != edgeGroups[gi + 1]!!.topoGroup) {
                            if (newVerts > 2) {
                                newFacesNum++
                                newEdgesNum += newVerts
                                if (firstAdded < lastAdded) openEdges++
                                if (openEdges != 0 && !containsOpenSplits) openEdges--
                                newLoopsNum += if (doShell && doRim) newVerts * 2 else if (doShell) newVerts * 2 - openEdges else newVerts * 2 + openEdges - containsSplits
                            } else if (newVerts == 2) {
                                newEdgesNum++
                                newLoopsNum += 2 - (if (!(doRim && doShell) && containsOpenSplits) 1 else 0)
                            }
                            newVerts = 0
                            containsOpenSplits = false
                            containsSplits = 0
                            openEdges = 0
                            lastAdded = 0
                            firstAdded = 0
                            firstSet = false
                        }
                        gi++
                    }
                }
            }
        }

        run {
            val faceWeight = if (doFlatFaces) FloatArray(facesNum) { f ->
                var s = 1.0f
                for (k in 0 until d.fSize[f]) s = minOf(weights!![origCornerVerts[d.fStart[f] + k]], s)
                offsetFacVg + s * offsetFacVgInv
            } else null
            for (i in 0 until vertsNum) {
                val gs = origVertGroupsArr[i] ?: continue
                var gi = 0
                while (gi < gs.size && gs[gi]?.valid == true) {
                    val g = gs[gi]!!
                    gi++
                    if (g.isSingularity) { origMvertCo[i].copyInto(g.co); continue }
                    val nor = g.no
                    val moveNor = FloatArray(3)
                    var disableBoundaryFix = boundaryMode == 0 || g.isOrigClosed || g.split != 0
                    var approximateFreeDirection = false
                    if (offsetMode == 2) {
                        var firstEdge: NewEdgeRef? = null
                        val planes = Array(g.edgesLen + 1) { FloatArray(4) }
                        var queueIndex = 0
                        val fallbackNor = FloatArray(3)
                        var fallbackOfs = 0.0f
                        val cycle = (g.isOrigClosed && g.split == 0) || g.isEvenSplit
                        for (k in 0 until g.edgesLen) {
                            if ((k and 1) == 0 || (!cycle && k == g.edgesLen - 1)) {
                                val edge = g.edges[k]!!
                                for (l in 0 until 2) {
                                    val fs = edge.faces[l]
                                    if (fs != EMPTY && (firstEdge == null || (firstEdge.faces[0] != fs && firstEdge.faces[1] != fs))) {
                                        val face = side(fs)
                                        var ofs = if (face.reversed) ofsBackClamped else ofsFrontClamped
                                        if (faceWeight != null) ofs *= faceWeight[face.index]
                                        val sgn = if (face.reversed) -1f else 1f
                                        if (!nullFaces!![face.index]) {
                                            for (q in 0 until 3) planes[queueIndex][q] = faceNors[face.index * 3 + q] * sgn
                                            planes[queueIndex++][3] = ofs
                                        } else {
                                            for (q in 0 until 3) fallbackNor[q] = faceNors[face.index * 3 + q] * sgn
                                            fallbackOfs = ofs
                                        }
                                    }
                                }
                                if ((cycle && k == 0) || (!cycle && k + 3 >= g.edgesLen)) firstEdge = edge
                            }
                        }
                        fun swap4(a: Int, b: Int) { val t = planes[a]; planes[a] = planes[b]; planes[b] = t }
                        if (queueIndex > 2) {
                            var minP = 2.0f
                            var minN0 = 0
                            var minN1 = 0
                            for (k in 0 until queueIndex) for (m in k + 1 until queueIndex) {
                                val pp = dot(planes[k], planes[m])
                                if (pp < minP) { minP = pp; minN0 = k; minN1 = m }
                            }
                            if (minN1 != 0) { swap4(minN0, 0); swap4(minN1, 1) } else swap4(minN0, 1)
                            minP = 1.0f
                            minN1 = 2
                            for (k in 2 until queueIndex) {
                                val maxP = maxOf(dot(planes[0], planes[k]), dot(planes[1], planes[k]))
                                if (maxP <= minP) { minP = maxP; minN1 = k }
                            }
                            swap4(minN1, 2)
                        }
                        while (queueIndex > 2) {
                            var bestN0 = 0
                            var bestN1 = 0
                            var bestP = -1.0f
                            var bestOfsDiff = 0.0f
                            for (k in 0 until queueIndex) for (m in k + 1 until queueIndex) {
                                val pp = dot(planes[m], planes[k])
                                val ofsDiff = abs(planes[m][3] - planes[k][3])
                                if (pp > bestP + FLT_EPSILON || (pp >= bestP && ofsDiff < bestOfsDiff)) {
                                    bestP = pp; bestOfsDiff = ofsDiff; bestN0 = k; bestN1 = m
                                }
                            }
                            if (bestP < 0.98f) break
                            for (q in 0 until 3) planes[bestN0][q] += planes[bestN1][q]
                            normalize(planes[bestN0])
                            planes[bestN0][3] = (planes[bestN0][3] + planes[bestN1][3]) * 0.5f
                            queueIndex--
                            for (k in bestN1 until queueIndex) planes[k] = planes[k + 1].copyOf()
                        }
                        val size = queueIndex
                        val boundaryFixThreshold = 0.7f
                        if (size > 3) {
                            val mat = Array(3) { FloatArray(3) }
                            for (k in 0 until 3) {
                                for (m in 0 until size) for (q in 0 until 3) mat[k][q] += planes[m][q] * planes[m][k]
                                mat[k][k] += 5e-5f
                            }
                            invertM3(mat)
                            nor[0] = 0f; nor[1] = 0f; nor[2] = 0f
                            for (k in 0 until size) for (q in 0 until 3) nor[q] += planes[k][q] * planes[k][3]
                            val t = nor.copyOf()
                            nor[0] = mat[0][0] * t[0] + mat[1][0] * t[1] + mat[2][0] * t[2]
                            nor[1] = mat[0][1] * t[0] + mat[1][1] * t[1] + mat[2][1] * t[2]
                            nor[2] = mat[0][2] * t[0] + mat[1][2] * t[1] + mat[2][2] * t[2]
                            if (!disableBoundaryFix) {
                                var greatest = 1.0f
                                for (k in 0 until 2) for (m in 2 until size) greatest = minOf(dot(planes[m], planes[k]), greatest)
                                if (greatest > boundaryFixThreshold) approximateFreeDirection = true else disableBoundaryFix = true
                            }
                        } else if (size > 1) {
                            val stopExplosion = 0.999f - abs(p.offsetFac) * 0.05f
                            val q = dot(planes[0], planes[1])
                            var dd = 1.0f - q * q
                            cross(planes[0], planes[1]).copyInto(moveNor)
                            normalize(moveNor)
                            if (dd > FLT_EPSILON * 10 && q < stopExplosion) {
                                dd = 1.0f / dd
                                val s0 = (planes[0][3] - planes[1][3] * q) * dd
                                val s1 = (planes[1][3] - planes[0][3] * q) * dd
                                for (c in 0 until 3) { planes[0][c] *= s0; planes[1][c] *= s1 }
                            } else {
                                dd = 1.0f / (abs(q) + 1.0f)
                                val s0 = planes[0][3] * dd
                                val s1 = planes[1][3] * dd
                                for (c in 0 until 3) { planes[0][c] *= s0; planes[1][c] *= s1 }
                            }
                            for (c in 0 until 3) nor[c] = planes[0][c] + planes[1][c]
                            if (size == 3) {
                                dd = dot(planes[2], moveNor)
                                if (abs(dd) > 0.02f) {
                                    val tmp = FloatArray(3) { nor[it] + planes[2][it] * -planes[2][3] }
                                    val s = dot(planes[2], tmp) / dd
                                    for (c in 0 until 3) { tmp[c] = moveNor[c] * s; nor[c] -= tmp[c] }
                                    if (abs(dd) > 1.0f - boundaryFixThreshold) disableBoundaryFix = true
                                }
                            }
                            approximateFreeDirection = false
                        } else if (size == 1) {
                            for (c in 0 until 3) nor[c] = planes[0][c] * planes[0][3]
                            if (g.edgesLen > 2) { disableBoundaryFix = true; approximateFreeDirection = true }
                        } else {
                            for (c in 0 until 3) nor[c] = fallbackNor[c] * fallbackOfs
                            disableBoundaryFix = true
                        }
                    } else {
                        var totalAngle = 0f
                        var totalAngleBack = 0f
                        var firstEdge: NewEdgeRef? = null
                        val norBack = FloatArray(3)
                        var hasBack = false
                        var hasFront = false
                        val cycle = (g.isOrigClosed && g.split == 0) || g.isEvenSplit
                        for (k in 0 until g.edgesLen) {
                            if ((k and 1) == 0 || (!cycle && k == g.edgesLen - 1)) {
                                val edge = g.edges[k]!!
                                for (l in 0 until 2) {
                                    val fs = edge.faces[l]
                                    if (fs != EMPTY && (firstEdge == null || (firstEdge.faces[0] != fs && firstEdge.faces[1] != fs))) {
                                        val face = side(fs)
                                        var angle = 1.0f
                                        var ofs = if (face.reversed) -ofsBackClamped else ofsFrontClamped
                                        if (faceWeight != null) ofs *= faceWeight[face.index]
                                        if (offsetMode == 1) {
                                            var cornerNext = face.start
                                            var corner = cornerNext + (face.size - 1)
                                            var cornerPrev = corner - 1
                                            var m = 0
                                            while (m < face.size && vm[origCornerVerts[corner]] != i) {
                                                cornerPrev = corner
                                                corner = cornerNext
                                                m++
                                                cornerNext++
                                            }
                                            angle = angleV3V3V3(origMvertCo[vm[origCornerVerts[cornerPrev]]], origMvertCo[i], origMvertCo[vm[origCornerVerts[cornerNext]]])
                                            if (face.reversed) totalAngleBack += angle * ofs * ofs else totalAngle += angle * ofs * ofs
                                        } else {
                                            if (face.reversed) totalAngleBack++ else totalAngle++
                                        }
                                        val s = angle * ofs
                                        if (face.reversed) {
                                            for (c in 0 until 3) norBack[c] += faceNors[face.index * 3 + c] * s
                                            hasBack = true
                                        } else {
                                            for (c in 0 until 3) nor[c] += faceNors[face.index * 3 + c] * s
                                            hasFront = true
                                        }
                                    }
                                }
                                if ((cycle && k == 0) || (!cycle && k + 3 >= g.edgesLen)) firstEdge = edge
                            }
                        }
                        if (offsetMode == 1) {
                            if (hasFront) {
                                val l2 = lenSq(nor)
                                if (l2 > FLT_EPSILON) { val s = totalAngle / l2; for (c in 0 until 3) nor[c] *= s }
                            }
                            if (hasBack) {
                                val l2 = lenSq(norBack)
                                if (l2 > FLT_EPSILON) { val s = totalAngleBack / l2; for (c in 0 until 3) norBack[c] *= s }
                                if (!hasFront) norBack.copyInto(nor)
                            }
                            if (hasFront && hasBack) {
                                val norLength = sqrt(lenSq(nor))
                                val norBackLength = sqrt(lenSq(norBack))
                                var q = dot(nor, norBack)
                                if (abs(q) > FLT_EPSILON) q /= norLength * norBackLength
                                var dd = 1.0f - q * q
                                if (dd > FLT_EPSILON) {
                                    dd = 1.0f / dd
                                    if (norLength > FLT_EPSILON) { val s = (1 - norBackLength * q / norLength) * dd; for (c in 0 until 3) nor[c] *= s }
                                    if (norBackLength > FLT_EPSILON) { val s = (1 - norLength * q / norBackLength) * dd; for (c in 0 until 3) norBack[c] *= s }
                                    for (c in 0 until 3) nor[c] += norBack[c]
                                } else {
                                    for (c in 0 until 3) { nor[c] *= 0.5f; norBack[c] *= 0.5f; nor[c] += norBack[c] }
                                }
                            }
                        } else {
                            if (hasFront && totalAngle > FLT_EPSILON) { val s = 1.0f / totalAngle; for (c in 0 until 3) nor[c] *= s }
                            if (hasBack && totalAngleBack > FLT_EPSILON) {
                                val s = 1.0f / totalAngleBack
                                for (c in 0 until 3) { norBack[c] *= s; nor[c] += norBack[c] }
                                if (hasFront && totalAngle > FLT_EPSILON) for (c in 0 until 3) nor[c] *= 0.5f
                            }
                        }
                        if (!disableBoundaryFix && g.edgesLen > 2) approximateFreeDirection = true else disableBoundaryFix = true
                    }
                    if (approximateFreeDirection) {
                        var k = 1
                        while (k + 1 < g.edgesLen) {
                            val e = g.edges[k]!!.oldEdge
                            val o = if (vm[ev(e, 0)] == i) ev(e, 1) else ev(e, 0)
                            for (c in 0 until 3) moveNor[c] += origMvertCo[o][c] - origMvertCo[i][c]
                            k++
                        }
                        disableBoundaryFix = if (k == 1) true else normalize(moveNor) == 0.0f
                    }
                    if (!disableBoundaryFix) {
                        val e0e = g.edges[0]!!.oldEdge
                        val e1e = g.edges[g.edgesLen - 1]!!.oldEdge
                        val o0 = if (vm[ev(e0e, 0)] == i) ev(e0e, 1) else ev(e0e, 0)
                        val o1 = if (vm[ev(e1e, 0)] == i) ev(e1e, 1) else ev(e1e, 0)
                        val e0 = FloatArray(3) { origMvertCo[o0][it] - origMvertCo[i][it] }
                        val e1 = FloatArray(3) { origMvertCo[o1][it] - origMvertCo[i][it] }
                        val constrNor: FloatArray
                        if (boundaryMode == 2) {
                            constrNor = cross(e0, e1)
                            normalize(constrNor)
                        } else {
                            val fa = side(g.edges[0]!!.faces[0])
                            val fb = side(g.edges[g.edgesLen - 1]!!.faces[0])
                            val f0 = fno(fa.index).also { if (fa.reversed) for (c in 0 until 3) it[c] = -it[c] }
                            val f1 = fno(fb.index).also { if (fb.reversed) for (c in 0 until 3) it[c] = -it[c] }
                            val n0 = cross(e0, f0)
                            val n1 = cross(f1, e1)
                            normalize(n0); normalize(n1)
                            constrNor = FloatArray(3) { n0[it] + n1[it] }
                            normalize(constrNor)
                        }
                        val dd = dot(constrNor, moveNor)
                        if (abs(dd) > 0.1f) {
                            val s = dot(constrNor, nor) / dd
                            for (c in 0 until 3) { moveNor[c] *= s; nor[c] -= moveNor[c] }
                        }
                    }
                    var scalarVgroup = 1f
                    if (weights != null && !doFlatFaces) scalarVgroup = offsetFacVg + weights[i] * offsetFacVgInv
                    if (doClamp) {
                        if (doAngleClamp) {
                            if (g.edgesLen > 2) {
                                var minLength = 0f
                                var angle = 0.5f * PI_F
                                for (k in 0 until g.edgesLen) {
                                    val e = g.edges[k]!!
                                    val length = origEdgeLengths[e.oldEdge]
                                    angle = maxOf(e.angle, angle)
                                    if (length < minLength || k == 0) minLength = length
                                }
                                val cosAng = kotlin.math.cos(angle * 0.5f)
                                if (cosAng > 0) {
                                    val maxOff = minLength * 0.5f / cosAng
                                    if (maxOff < offset * 0.5f) scalarVgroup *= maxOff / offset * 2
                                }
                            }
                        } else {
                            var minLength = 0f
                            for (k in 0 until g.edgesLen) {
                                val length = origEdgeLengths[g.edges[k]!!.oldEdge]
                                if (length < minLength || k == 0) minLength = length
                            }
                            if (minLength < offset) scalarVgroup *= minLength / offset
                        }
                    }
                    for (c in 0 until 3) { nor[c] *= scalarVgroup; g.co[c] = nor[c] + origMvertCo[i][c] }
                }
            }
        }

        var singularityEdges = ArrayList<IntArray>()
        var totsingularity = 0
        if (hasSingularities) {
            hasSingularities = false
            for (i in 0 until edgesNum) {
                val ne = origEdgeDataArr[i]
                if (ne != null && (doShell || edgeAdjFacesLen[i] == 1) && ne[0].oldEdge == i) {
                    for (l in ne) {
                        if (l.linkEdgeGroups[0]!!.isSingularity && l.linkEdgeGroups[1]!!.isSingularity) {
                            val v1 = l.linkEdgeGroups[0]!!.newVert
                            val v2 = l.linkEdgeGroups[1]!!.newVert
                            var exists = false
                            for (j in 0 until totsingularity) {
                                val pe = singularityEdges[j]
                                if ((pe[0] == v1 && pe[1] == v2) || (pe[0] == v2 && pe[1] == v1)) { exists = true; break }
                            }
                            if (!exists) {
                                hasSingularities = true
                                singularityEdges.add(intArrayOf(v1, v2))
                                totsingularity++
                                if (edgeAdjFacesLen[i] == 1 && doRim) { newLoopsNum -= 2; newFacesNum-- }
                            } else newEdgesNum--
                        }
                    }
                }
            }
        }

        val outCo = FloatArray(newVertsNum * 3)
        val outVertSrc = IntArray(newVertsNum)
        val edges = IntArray(newEdgesNum * 2)
        val edgeSrc = IntArray(newEdgesNum) { -1 }
        val faceOffsets = IntArray(newFacesNum + 1)
        val faceSrc = IntArray(newFacesNum)
        val faceMat = IntArray(newFacesNum)
        val cornerVerts = IntArray(newLoopsNum)
        val cornerSrc = IntArray(newLoopsNum)
        val vertexCrease = d.vertCrease
        val hasCreaseLayer = vertexCrease != null || origEdgeCrease != null
        val resultEdgeCrease = if (hasCreaseLayer) FloatArray(newEdgesNum) else null
        val hasBweightLayer = origEdgeBweight != null || bevelConvex != 0.0f || origVertBweight != null
        val resultEdgeBweight = if (hasBweightLayer) FloatArray(newEdgesNum) else null
        val needDvert = p.shellGroup != -1 || p.rimGroup != -1
        val outDvert: Array<MutableList<Pair<Int, Float>>>? = if (d.dverts != null || needDvert) Array(newVertsNum) { mutableListOf() } else null
        fun ensureWeight(v: Int, group: Int) {
            val list = outDvert!![v]
            val k = list.indexOfFirst { it.first == group }
            if (k >= 0) list[k] = group to 1.0f else list.add(group to 1.0f)
        }

        for (i in 0 until vertsNum) {
            val gs = origVertGroupsArr[i] ?: continue
            var gi = 0
            while (gi < gs.size && gs[gi]?.valid == true) {
                val g = gs[gi]!!
                if (g.newVert != EMPTY) {
                    outVertSrc[g.newVert] = i
                    g.co.copyInto(outCo, g.newVert * 3)
                }
                gi++
            }
        }
        if (outDvert != null) for (v in 0 until newVertsNum) d.dverts?.getOrNull(outVertSrc[v])?.let { outDvert[v].addAll(it) }

        edgeIdx += totsingularity
        for (i in 0 until edgesNum) {
            val ne = origEdgeDataArr[i]
            if (ne != null && (doShell || edgeAdjFacesLen[i] == 1) && ne[0].oldEdge == i) {
                for (l in ne) {
                    if (l.newEdge != EMPTY) {
                        val v1 = l.linkEdgeGroups[0]!!.newVert
                        val v2 = l.linkEdgeGroups[1]!!.newVert
                        var insert = edgeIdx
                        if (hasSingularities && l.linkEdgeGroups[0]!!.isSingularity && l.linkEdgeGroups[1]!!.isSingularity) {
                            for (j in 0 until totsingularity) {
                                val pe = singularityEdges[j]
                                if ((pe[0] == v1 && pe[1] == v2) || (pe[0] == v2 && pe[1] == v1)) { insert = j; break }
                            }
                        } else edgeIdx++
                        edgeSrc[insert] = i
                        edges[insert * 2] = v1
                        edges[insert * 2 + 1] = v2
                        if (resultEdgeCrease != null) resultEdgeCrease[insert] = origEdgeCrease?.get(l.oldEdge) ?: 0.0f
                        if (resultEdgeBweight != null) resultEdgeBweight[insert] = origEdgeBweight?.get(l.oldEdge) ?: 0.0f
                        if (bevelConvex != 0.0f && l.faces[1] != EMPTY) {
                            val add = if (l.angle > PI_F + FLT_EPSILON) bevelConvex.coerceIn(0.0f, 1.0f) else if (l.angle < PI_F - FLT_EPSILON) bevelConvex.coerceIn(-1.0f, 0.0f) else 0f
                            resultEdgeBweight!![insert] = (resultEdgeBweight[insert] + add).coerceIn(0.0f, 1.0f)
                        }
                        l.newEdge = insert
                    }
                }
            }
        }

        val srcMat = d.faceMat
        fun srcMatOf(f: Int) = srcMat?.getOrNull(f) ?: 0
        for (i in 0 until vertsNum) {
            val gs = origVertGroupsArr[i] ?: continue
            fun valid(k: Int) = k < gs.size && gs[k]?.valid == true
            var g2 = 0
            var lastG: EdgeGroup? = null
            var firstG: EdgeGroup? = null
            val mvCrease = vertexCrease?.getOrNull(i) ?: 0.0f
            val mvBweight = origVertBweight?.getOrNull(i) ?: 0.0f
            var lastMaxCrease = 0.0f
            var firstMaxCrease = 0.0f
            var lastMaxBweight = 0.0f
            var firstMaxBweight = 0.0f
            var j = 0
            var gi = 0
            while (valid(gi)) {
                val g = gs[gi]!!
                if ((doRim && !g.isOrigClosed) || (doShell && g.split != 0)) {
                    var maxCrease = 0f
                    var maxBweight = 0f
                    if (g.edgesLen == 2) {
                        if (resultEdgeCrease != null) maxCrease = if (origEdgeCrease != null) minOf(origEdgeCrease[g.edges[0]!!.oldEdge], origEdgeCrease[g.edges[1]!!.oldEdge]) else 0.0f
                    } else {
                        for (k in 1 until g.edgesLen - 1) {
                            val oe = g.edges[k]!!.oldEdge
                            if (resultEdgeCrease != null && origEdgeCrease != null && origEdgeCrease[oe] > maxCrease) maxCrease = origEdgeCrease[oe]
                            if (g.edges[k]!!.newEdge != EMPTY && resultEdgeBweight != null) maxBweight = maxOf(resultEdgeBweight[g.edges[k]!!.newEdge], maxBweight)
                        }
                    }
                    val bweightOpenEdge = if (origEdgeBweight != null) minOf(origEdgeBweight[g.edges[0]!!.oldEdge], origEdgeBweight[g.edges[g.edgesLen - 1]!!.oldEdge]) else 0.0f
                    if (bweightOpenEdge > 0) maxBweight = minOf(bweightOpenEdge, maxBweight)
                    else if (bevelConvex < 0.0f) maxBweight = 0f
                    if (firstG == null) {
                        firstG = g
                        firstMaxCrease = maxCrease
                        firstMaxBweight = maxBweight
                    } else {
                        lastG!!.openFaceEdge = edgeIdx
                        edgeSrc[edgeIdx] = lastG.edges[0]!!.oldEdge
                        edges[edgeIdx * 2] = lastG.newVert
                        edges[edgeIdx * 2 + 1] = g.newVert
                        if (resultEdgeCrease != null) resultEdgeCrease[edgeIdx] = maxOf(mvCrease, minOf(lastMaxCrease, maxCrease))
                        if (resultEdgeBweight != null) resultEdgeBweight[edgeIdx] = maxOf(mvBweight, minOf(lastMaxBweight, maxBweight))
                        edgeIdx++
                    }
                    lastG = g
                    lastMaxCrease = maxCrease
                    lastMaxBweight = maxBweight
                    j++
                }
                if (!valid(gi + 1) || g.topoGroup != gs[gi + 1]!!.topoGroup) {
                    if (j == 2) lastG!!.openFaceEdge = edgeIdx - 1
                    if (j > 2) {
                        edgeSrc[edgeIdx] = lastG!!.edges[0]!!.oldEdge
                        lastG.openFaceEdge = edgeIdx
                        edges[edgeIdx * 2] = lastG.newVert
                        edges[edgeIdx * 2 + 1] = firstG!!.newVert
                        if (resultEdgeCrease != null) resultEdgeCrease[edgeIdx] = maxOf(mvCrease, minOf(lastMaxCrease, firstMaxCrease))
                        if (resultEdgeBweight != null) resultEdgeBweight[edgeIdx] = maxOf(mvBweight, minOf(lastMaxBweight, firstMaxBweight))
                        edgeIdx++
                        val loopsData = IntArray(j)
                        var mostMatNr = 0
                        var mostMatNrFace = 0
                        var mostMatNrCount = 0
                        for (l in 0 until matNrs) {
                            var count = 0
                            var face = 0
                            var k = 0
                            var g3 = g2
                            while (valid(g3) && k < j) {
                                val gg = gs[g3]!!
                                if ((doRim && !gg.isOrigClosed) || (doShell && gg.split != 0)) {
                                    val f0 = side(gg.edges[0]!!.faces[0]).index
                                    if (srcMatOf(f0) == l) { face = f0; count++ }
                                    val le = gg.edges[gg.edgesLen - 1]!!
                                    if (le.faces[1] != EMPTY && srcMatOf(side(le.faces[1]).index) == l) { face = side(le.faces[1]).index; count++ }
                                    else if (le.faces[1] == EMPTY && srcMatOf(side(le.faces[0]).index) == l) { face = side(le.faces[0]).index; count++ }
                                    k++
                                }
                                g3++
                            }
                            if (count > mostMatNrCount) { mostMatNr = l; mostMatNrFace = face; mostMatNrCount = count }
                        }
                        faceSrc[faceIndex] = mostMatNrFace
                        faceOffsets[faceIndex] = loopIndex
                        faceMat[faceIndex] = (mostMatNr + if (g.isOrigClosed || !doRim) 0 else matOfsRim).coerceIn(0, matNrMax)
                        faceIndex++
                        var k = 0
                        while (valid(g2) && k < j) {
                            val gg = gs[g2]!!
                            if ((doRim && !gg.isOrigClosed) || (doShell && gg.split != 0)) {
                                val fr = side(gg.edges[0]!!.faces[0])
                                for (l in 0 until fr.size) {
                                    if (vm[origCornerVerts[fr.start + l]] == i) { loopsData[k] = fr.start + l; break }
                                }
                                k++
                            }
                            g2++
                        }
                        if (!doFlip) {
                            for (kk in 0 until j) {
                                cornerSrc[loopIndex] = loopsData[kk]
                                cornerVerts[loopIndex++] = edges[(edgeIdx - j + kk) * 2]
                            }
                        } else {
                            for (kk in 1..j) {
                                cornerSrc[loopIndex] = loopsData[j - kk]
                                cornerVerts[loopIndex++] = edges[(edgeIdx - kk) * 2 + 1]
                            }
                        }
                    }
                    j = 0
                    lastG = null
                    firstG = null
                    lastMaxCrease = 0f
                    firstMaxCrease = 0f
                    lastMaxBweight = 0f
                    firstMaxBweight = 0f
                }
                gi++
            }
        }

        if (doRim) {
            for (i in 0 until edgesNum) {
                val ne = origEdgeDataArr[i]
                if (edgeAdjFacesLen[i] == 1 && ne != null && ne[0].oldEdge == i) {
                    val edge1 = ne[0]
                    val edge2 = ne[1]
                    val v1Sing = edge1.linkEdgeGroups[0]!!.isSingularity && edge2.linkEdgeGroups[0]!!.isSingularity
                    val v2Sing = edge1.linkEdgeGroups[1]!!.isSingularity && edge2.linkEdgeGroups[1]!!.isSingularity
                    if (v1Sing && v2Sing) continue
                    val fr = side(ne[0].faces[0])
                    faceSrc[faceIndex] = fr.index
                    faceOffsets[faceIndex] = loopIndex
                    faceMat[faceIndex] = (srcMatOf(fr.index) + matOfsRim).coerceIn(0, matNrMax)
                    faceIndex++
                    var loop1 = -1
                    var loop2 = -1
                    val oldV1 = vm[ev(edge1.oldEdge, 0)]
                    val oldV2 = vm[ev(edge1.oldEdge, 1)]
                    for (j in 0 until fr.size) {
                        val vert = origCornerVerts[fr.start + j]
                        if (vm[vert] == oldV1) loop1 = fr.start + j else if (vm[vert] == oldV2) loop2 = fr.start + j
                    }
                    fun ed(e: Int, k: Int) = edges[e * 2 + k]
                    fun corner(src: Int, v: Int) { cornerSrc[loopIndex] = src; cornerVerts[loopIndex++] = v }
                    if (!doFlip) {
                        if (p.rimGroup != -1) ensureWeight(ed(edge1.newEdge, 0), p.rimGroup)
                        corner(loop1, ed(edge1.newEdge, 0))
                        if (!v2Sing) {
                            if (p.rimGroup != -1) ensureWeight(ed(edge1.newEdge, 1), p.rimGroup)
                            corner(loop2, ed(edge1.newEdge, 1))
                        }
                        if (p.rimGroup != -1) ensureWeight(ed(edge2.newEdge, 1), p.rimGroup)
                        corner(loop2, ed(edge2.newEdge, 1))
                        if (!v1Sing) {
                            if (p.rimGroup != -1) ensureWeight(ed(edge2.newEdge, 0), p.rimGroup)
                            corner(loop1, ed(edge2.newEdge, 0))
                        }
                    } else {
                        if (!v1Sing) {
                            if (p.rimGroup != -1) ensureWeight(ed(edge1.newEdge, 0), p.rimGroup)
                            corner(loop1, ed(edge1.newEdge, 0))
                        }
                        if (p.rimGroup != -1) ensureWeight(ed(edge2.newEdge, 0), p.rimGroup)
                        corner(loop1, ed(edge2.newEdge, 0))
                        if (!v2Sing) {
                            if (p.rimGroup != -1) ensureWeight(ed(edge2.newEdge, 1), p.rimGroup)
                            corner(loop2, ed(edge2.newEdge, 1))
                        }
                        if (p.rimGroup != -1) ensureWeight(ed(edge1.newEdge, 1), p.rimGroup)
                        corner(loop2, ed(edge1.newEdge, 1))
                    }
                }
            }
        }

        if (doShell) {
            val faceLoops = IntArray(largestNgon * 2)
            val faceVerts = IntArray(largestNgon * 2)
            for (i in 0 until facesNum * 2) {
                val fr = side(i)
                val loopstart = fr.start
                var totloop = fr.size
                var validEdges = 0
                var k = 0
                while (totloop > 0 && (fr.linkEdges[totloop - 1] == null || fr.linkEdges[totloop - 1]!!.newEdge == EMPTY)) totloop--
                if (totloop > 0) {
                    for (j in 0 until totloop) {
                        val newEdge = fr.linkEdges[j]
                        if (newEdge != null && newEdge.newEdge != EMPTY) {
                            validEdges++
                            val flip = if (vm[ev(newEdge.oldEdge, 1)] == vm[origCornerVerts[loopstart + j]]) 1 else 0
                            val newV1 = newEdge.linkEdgeGroups[flip]!!.newVert
                            val newV2 = newEdge.linkEdgeGroups[1 - flip]!!.newVert
                            if (k == 0 || faceVerts[k - 1] != newV1) {
                                faceLoops[k] = loopstart + j
                                faceVerts[k++] = newV1
                            }
                            if (j < totloop - 1 || faceVerts[0] != newV2) {
                                faceLoops[k] = loopstart + (j + 1) % totloop
                                faceVerts[k++] = newV2
                            }
                        }
                    }
                    if (k > 2 && validEdges > 2) {
                        faceSrc[faceIndex] = i / 2
                        faceOffsets[faceIndex] = loopIndex
                        faceMat[faceIndex] = (srcMatOf(fr.index) + if (fr.reversed != doFlip) matOfs else 0).coerceIn(0, matNrMax)
                        if (fr.reversed != doFlip) {
                            for (l in k - 1 downTo 0) {
                                if (p.shellGroup != -1) ensureWeight(faceVerts[l], p.shellGroup)
                                cornerSrc[loopIndex] = faceLoops[l]
                                cornerVerts[loopIndex++] = faceVerts[l]
                            }
                        } else {
                            var l = k - 1
                            for (nextL in 0 until k) {
                                cornerSrc[loopIndex] = faceLoops[l]
                                cornerVerts[loopIndex++] = faceVerts[l]
                                l = nextL
                            }
                        }
                        faceIndex++
                    }
                }
            }
        }
        faceOffsets[newFacesNum] = loopIndex
        if (edgeIdx != newEdgesNum || faceIndex != newFacesNum || loopIndex != newLoopsNum) return d

        val nF = newFacesNum
        val fStart = IntArray(nF) { faceOffsets[it] }
        val fSize = IntArray(nF) { faceOffsets[it + 1] - faceOffsets[it] }
        val uv = d.uv?.let { src -> FloatArray(newLoopsNum * 2) { src[cornerSrc[it / 2] * 2 + it % 2] } }
        val cc = d.cornerColor?.let { src -> IntArray(newLoopsNum) { src[cornerSrc[it]] } }
        val pc = d.pointColor?.let { src -> IntArray(newVertsNum) { src[outVertSrc[it]] } }
        val vCrease = if (doRim && hasCreaseLayer) null else d.vertCrease?.let { src -> FloatArray(newVertsNum) { src[outVertSrc[it]] } }
        fun srcKey(e: Int): Long { val s = edgeSrc[e]; return BlendMeshData.key(ev(s, 0), ev(s, 1)) }
        val crease = HashMap<Long, Float>()
        for (e in 0 until newEdgesNum) {
            val v = if (resultEdgeCrease != null) resultEdgeCrease[e] else (d.edgeCrease[srcKey(e)] ?: 0f)
            if (v != 0f) crease[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = v
        }
        val faceEdgeKeys = HashSet<Long>()
        for (f in 0 until nF) for (k in 0 until fSize[f]) faceEdgeKeys.add(BlendMeshData.key(cornerVerts[fStart[f] + k], cornerVerts[fStart[f] + (k + 1) % fSize[f]]))
        val loose = ArrayList<Int>()
        for (e in 0 until newEdgesNum) if (BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]) !in faceEdgeKeys) { loose.add(edges[e * 2]); loose.add(edges[e * 2 + 1]) }
        val r = BlendMeshData(
            outCo, fStart, fSize, cornerVerts, faceMat, d.faceSmooth?.let { s -> BooleanArray(nF) { s.getOrElse(faceSrc[it]) { false } } },
            uv, cc, pc, crease, vCrease, loose.toIntArray(), outDvert?.let { o -> Array(newVertsNum) { o[it].toList() } }, d.groups
        )
        val sharp = HashSet<Long>()
        val seams = HashSet<Long>()
        for (e in 0 until newEdgesNum) {
            val k = BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])
            if (srcKey(e) in d.sharpEdges) sharp.add(k)
            if (srcKey(e) in d.seamEdges) seams.add(k)
        }
        r.carryNormals(d, sharp)
        r.seamEdges = seams
        r.fileEdges = edges
        val ea = HashMap<String, Map<Long, Float>>()
        for ((name, m) in d.edgeAttrs) {
            if (name == "bevel_weight_edge" || name == "crease_edge") continue
            val o = HashMap<Long, Float>()
            for (e in 0 until newEdgesNum) m[srcKey(e)]?.let { o[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = it }
            ea[name] = o
        }
        if (resultEdgeBweight != null) {
            val o = HashMap<Long, Float>()
            for (e in 0 until newEdgesNum) o[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = resultEdgeBweight[e]
            ea["bevel_weight_edge"] = o
        }
        r.edgeAttrs = ea
        r.vertAttrs = d.vertAttrs.mapValues { (_, a) -> FloatArray(newVertsNum) { a.getOrElse(outVertSrc[it]) { 0f } } }
        r.faceStrength = d.faceStrength?.let { s -> IntArray(nF) { s.getOrElse(faceSrc[it]) { 0 } } }
        return r
    }
}
