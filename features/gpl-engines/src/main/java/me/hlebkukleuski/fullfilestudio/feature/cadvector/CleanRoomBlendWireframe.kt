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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sqrt

internal object BlendWireframe {
    const val INVERT_VGROUP = 1
    const val REPLACE = 1 shl 1
    const val BOUNDARY = 1 shl 2
    const val OFS_EVEN = 1 shl 3
    const val OFS_RELATIVE = 1 shl 4
    const val CREASE = 1 shl 5

    class Params(
        val offset: Float, val offsetFac: Float, val offsetFacVg: Float, val creaseWeight: Float, val flag: Int,
        val matOfs: Int, val matMax: Int, val groupWeights: FloatArray?
    )

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray): FloatArray {
        val d = dot(v, v)
        if (d > 1.0e-35f) { val l = sqrt(d); v[0] /= l; v[1] /= l; v[2] /= l } else { v[0] = 0f; v[1] = 0f; v[2] = 0f }
        return v
    }

    private fun safeAsin(x: Float): Float = if (x <= -1f) (-Math.PI / 2).toFloat() else if (x >= 1f) (Math.PI / 2).toFloat() else asin(x)

    private fun angleNormalized(a: FloatArray, b: FloatArray): Float {
        if (dot(a, b) >= 0f) {
            val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
            return 2.0f * safeAsin(sqrt(x * x + y * y + z * z) / 2.0f)
        }
        val x = a[0] + b[0]; val y = a[1] + b[1]; val z = a[2] + b[2]
        return Math.PI.toFloat() - 2.0f * safeAsin(sqrt(x * x + y * y + z * z) / 2.0f)
    }

    private fun shellAngleToDist(angle: Float): Float = if (angle < 1.0e-8f) 1.0f else abs(1.0f / cos(angle))

    private fun angleOnAxis(v1: FloatArray, v2: FloatArray, v3: FloatArray, axis: FloatArray): Float {
        val a = sub(v1, v2)
        val b = sub(v3, v2)
        val da = dot(a, axis)
        val db = dot(b, axis)
        val pa = normalize(floatArrayOf(a[0] - axis[0] * da, a[1] - axis[1] * da, a[2] - axis[2] * da))
        val pb = normalize(floatArrayOf(b[0] - axis[0] * db, b[1] - axis[1] * db, b[2] - axis[2] * db))
        return angleNormalized(pa, pb)
    }

    fun apply(d: BlendMeshData, p: Params): BlendMeshData {
        val nV = d.nV
        val nF = d.nF
        val co = d.co
        fun pos(v: Int) = floatArrayOf(co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
        val fno = BMesh.faceNormals(co, d.corners, d.fStart, d.fSize)
        val vno = MeshTopology(d.corners, d.fStart, d.fSize, IntArray(0)).vertexNormals(co)
        fun fn(f: Int) = floatArrayOf(fno[f * 3], fno[f * 3 + 1], fno[f * 3 + 2])
        fun vn(v: Int) = floatArrayOf(vno[v * 3], vno[v * 3 + 1], vno[v * 3 + 2])
        val faceOfCorner = IntArray(d.corners.size)
        for (f in 0 until nF) for (k in 0 until d.fSize[f]) faceOfCorner[d.fStart[f] + k] = f
        fun nextC(c: Int): Int { val f = faceOfCorner[c]; val s = d.fStart[f]; return s + (c - s + 1) % d.fSize[f] }
        fun prevC(c: Int): Int { val f = faceOfCorner[c]; val s = d.fStart[f]; val m = d.fSize[f]; return s + (c - s + m - 1) % m }

        val edgeOrder = LinkedHashSet<Long>()
        val fe = d.fileEdges
        if (fe != null) for (e in 0 until fe.size / 2) edgeOrder.add(BlendMeshData.key(fe[e * 2], fe[e * 2 + 1]))
        for (f in 0 until nF) for (k in 0 until d.fSize[f]) { val c = d.fStart[f] + k; edgeOrder.add(BlendMeshData.key(d.corners[c], d.corners[nextC(c)])) }
        for (i in 0 until d.looseEdges.size / 2) edgeOrder.add(BlendMeshData.key(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))
        val edgeLoops = HashMap<Long, ArrayList<Int>>()
        for (c in d.corners.indices) edgeLoops.getOrPut(BlendMeshData.key(d.corners[c], d.corners[nextC(c)])) { ArrayList(2) }.add(c)
        val vertEdges = Array(nV) { ArrayList<Long>(4) }
        for (k in edgeOrder) { vertEdges[(k ushr 32).toInt()].add(k); vertEdges[(k and 0xFFFFFFFFL).toInt()].add(k) }
        fun otherVert(k: Long, v: Int): Int { val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt(); return if (a == v) b else a }
        fun isBoundary(k: Long) = edgeLoops[k]?.size == 1

        val offset = p.offset
        val ofsOrig = -(((-p.offsetFac + 1.0f) * 0.5f) * offset)
        val ofsNew = offset + ofsOrig
        val ofsMid = (ofsOrig + ofsNew) / 2.0f
        val inset = offset / 2.0f
        val useReplace = p.flag and REPLACE != 0
        val useBoundary = p.flag and BOUNDARY != 0
        val useEven = p.flag and OFS_EVEN != 0
        val useRelative = p.flag and OFS_RELATIVE != 0
        val useCrease = p.flag and CREASE != 0
        val gw = p.groupWeights
        val vgInv = 1.0f - p.offsetFacVg

        val tagged = BooleanArray(nV)
        for (c in d.corners) tagged[c] = true

        val newCo = ArrayList<Float>()
        val newSrc = ArrayList<Int>()
        fun create(src: Int, x: FloatArray): Int {
            newCo.add(x[0]); newCo.add(x[1]); newCo.add(x[2]); newSrc.add(src)
            return newSrc.size - 1
        }
        val relfac = if (useRelative || gw != null) FloatArray(nV) else null
        val vNeg = IntArray(nV) { -1 }
        val vPos = IntArray(nV) { -1 }
        for (i in 0 until nV) {
            if (!tagged[i]) continue
            var fac = 1.0f
            if (relfac != null) {
                if (useRelative) {
                    var length = 0f
                    val es = vertEdges[i]
                    for (k in es) {
                        val o = otherVert(k, i)
                        if (tagged[o]) { val dx = co[i * 3] - co[o * 3]; val dy = co[i * 3 + 1] - co[o * 3 + 1]; val dz = co[i * 3 + 2] - co[o * 3 + 2]; length += sqrt(dx * dx + dy * dy + dz * dz) }
                    }
                    relfac[i] = if (es.isNotEmpty()) length / es.size.toFloat() else 0.0f
                } else relfac[i] = 1.0f
                if (gw != null) {
                    var w = gw[i]
                    if (p.offsetFacVg > 0.0f) w = p.offsetFacVg + w * vgInv
                    relfac[i] *= w
                }
                fac *= relfac[i]
            }
            val c0 = pos(i)
            val no = vn(i)
            if (offset == 0.0f) {
                vNeg[i] = create(i, floatArrayOf(c0[0] + no[0] * (ofsOrig * fac), c0[1] + no[1] * (ofsOrig * fac), c0[2] + no[2] * (ofsOrig * fac)))
                vPos[i] = create(i, floatArrayOf(c0[0] + no[0] * (ofsNew * fac), c0[1] + no[1] * (ofsNew * fac), c0[2] + no[2] * (ofsNew * fac)))
            } else {
                val t = floatArrayOf(c0[0] + no[0] * (ofsMid * fac), c0[1] + no[1] * (ofsMid * fac), c0[2] + no[2] * (ofsMid * fac))
                vNeg[i] = create(i, floatArrayOf(t[0] + no[0] * ((ofsOrig - ofsMid) * fac), t[1] + no[1] * ((ofsOrig - ofsMid) * fac), t[2] + no[2] * ((ofsOrig - ofsMid) * fac)))
                vPos[i] = create(i, floatArrayOf(t[0] + no[0] * ((ofsNew - ofsMid) * fac), t[1] + no[1] * ((ofsNew - ofsMid) * fac), t[2] + no[2] * ((ofsNew - ofsMid) * fac)))
            }
        }

        val vLoop = IntArray(d.corners.size)
        val vBoundary = IntArray(nV) { -1 }
        val boundaryDone = BooleanArray(nV)
        for (f in 0 until nF) for (k in 0 until d.fSize[f]) {
            val c = d.fStart[f] + k
            val v = d.corners[c]
            val vp = pos(d.corners[prevC(c)]); val vc = pos(v); val vx = pos(d.corners[nextC(c)])
            val ePrev = normalize(sub(vp, vc))
            val eNext = normalize(sub(vc, vx))
            val dir = add(ePrev, eNext)
            val same = abs(ePrev[0] - eNext[0]) <= 1.1920929E-7f * 10.0f && abs(ePrev[1] - eNext[1]) <= 1.1920929E-7f * 10.0f && abs(ePrev[2] - eNext[2]) <= 1.1920929E-7f * 10.0f
            val tan = if (!same) {
                var nor = cross(ePrev, eNext)
                if (dot(nor, fn(f)) < 0.0f) nor = floatArrayOf(-nor[0], -nor[1], -nor[2])
                normalize(cross(dir, nor))
            } else normalize(cross(dir, fn(f)))
            var fac = 1.0f
            if (relfac != null) fac *= relfac[v]
            var facShell = fac
            if (useEven) {
                val a = normalize(sub(vc, vp)); val b = normalize(sub(vc, vx))
                facShell *= shellAngleToDist((Math.PI.toFloat() - angleNormalized(a, b)) * 0.5f)
            }
            val t = floatArrayOf(vc[0] + tan[0] * (inset * facShell), vc[1] + tan[1] * (inset * facShell), vc[2] + tan[2] * (inset * facShell))
            if (offset != 0.0f) { val no = vn(v); for (j in 0 until 3) t[j] += no[j] * (ofsMid * fac) }
            vLoop[c] = create(v, t)
            if (useBoundary) {
                val ek = BlendMeshData.key(v, d.corners[nextC(c)])
                if (isBoundary(ek)) {
                    for (vb in intArrayOf(v, d.corners[nextC(c)])) {
                        if (boundaryDone[vb]) continue
                        boundaryDone[vb] = true
                        var ea = -1L; var eb = -1L
                        for (k2 in vertEdges[vb]) if (isBoundary(k2)) { if (ea == -1L) ea = k2 else { eb = k2; break } }
                        val noFace: FloatArray
                        val noEdge: FloatArray
                        val tvecA: FloatArray
                        var vaOther = -1; var vbOther = -1
                        val la = edgeLoops[ea]!![0]
                        fun edgeTangent(l: Int): FloatArray = normalize(cross(sub(pos(d.corners[l]), pos(d.corners[nextC(l)])), fn(faceOfCorner[l])))
                        val bc = pos(vb)
                        if (ea != -1L && eb != -1L) {
                            val lb = edgeLoops[eb]!![0]
                            noFace = normalize(add(fn(faceOfCorner[la]), fn(faceOfCorner[lb])))
                            val va = otherVert(ea, vb); val vbb = otherVert(eb, vb)
                            val ta = normalize(sub(bc, pos(va)))
                            val tb = normalize(sub(pos(vbb), bc))
                            noEdge = add(ta, tb)
                            tvecA = add(edgeTangent(la), edgeTangent(lb))
                            vaOther = va; vbOther = vbb
                        } else {
                            noFace = fn(faceOfCorner[la])
                            noEdge = sub(bc, pos(otherVert(ea, vb)))
                            tvecA = edgeTangent(la)
                        }
                        var rNo = normalize(cross(noEdge, noFace))
                        if (dot(rNo, tvecA) > 0.0f) rNo = floatArrayOf(-rNo[0], -rNo[1], -rNo[2])
                        var bfac = 1.0f
                        if (relfac != null) bfac *= relfac[vb]
                        var bShell = bfac
                        if (useEven && vaOther >= 0) bShell *= shellAngleToDist((Math.PI.toFloat() - angleOnAxis(pos(vaOther), bc, pos(vbOther), noFace)) * 0.5f)
                        val bt = floatArrayOf(bc[0] + rNo[0] * (inset * bShell), bc[1] + rNo[1] * (inset * bShell), bc[2] + rNo[2] * (inset * bShell))
                        if (offset != 0.0f) { val no = vn(vb); for (j in 0 until 3) bt[j] += no[j] * (ofsMid * bfac) }
                        vBoundary[vb] = create(vb, bt)
                    }
                }
            }
        }

        val base = if (useReplace) 0 else nV
        val outFaces = ArrayList<IntArray>()
        val outSrc = ArrayList<IntArray>()
        val outFaceOf = ArrayList<Int>()
        val creased = HashSet<Long>()
        fun nv(i: Int) = base + i
        if (!useReplace) for (f in 0 until nF) {
            outFaces.add(IntArray(d.fSize[f]) { d.corners[d.fStart[f] + it] })
            outSrc.add(IntArray(d.fSize[f]) { d.fStart[f] + it })
            outFaceOf.add(-1 - f)
        }
        for (f in 0 until nF) for (k in 0 until d.fSize[f]) {
            val l = d.fStart[f] + k
            val ln = nextC(l)
            val i1 = d.corners[l]; val i2 = d.corners[ln]
            val vl1 = nv(vLoop[l]); val vl2 = nv(vLoop[ln])
            val neg1 = nv(vNeg[i1]); val neg2 = nv(vNeg[i2]); val pos1 = nv(vPos[i1]); val pos2 = nv(vPos[i2])
            outFaces.add(intArrayOf(vl1, vl2, neg2, neg1)); outSrc.add(intArrayOf(l, ln, ln, l)); outFaceOf.add(f)
            outFaces.add(intArrayOf(vl2, vl1, pos1, pos2)); outSrc.add(intArrayOf(ln, l, l, ln)); outFaceOf.add(f)
            if (useBoundary && isBoundary(BlendMeshData.key(i1, i2))) {
                val b1 = nv(vBoundary[i1]); val b2 = nv(vBoundary[i2])
                outFaces.add(intArrayOf(b2, b1, neg1, neg2)); outSrc.add(intArrayOf(ln, l, l, ln)); outFaceOf.add(f)
                outFaces.add(intArrayOf(b1, b2, pos2, pos1)); outSrc.add(intArrayOf(l, ln, ln, l)); outFaceOf.add(f)
                if (useCrease) for ((a, b) in listOf(pos1 to b1, pos2 to b2, neg1 to b1, neg2 to b2)) creased.add(BlendMeshData.key(a, b))
            }
            if (useCrease) for ((a, b) in listOf(pos1 to vl1, pos2 to vl2, neg1 to vl1, neg2 to vl2)) creased.add(BlendMeshData.key(a, b))
        }

        val totV = base + newSrc.size
        val outCo = FloatArray(totV * 3)
        if (!useReplace) co.copyInto(outCo, 0, 0, nV * 3)
        for (i in newSrc.indices) for (j in 0 until 3) outCo[(base + i) * 3 + j] = newCo[i * 3 + j]
        fun srcVert(v: Int) = if (v < base) v else newSrc[v - base]
        val nOut = outFaces.size
        val fStart = IntArray(nOut)
        val fSize = IntArray(nOut)
        var total = 0
        for (i in 0 until nOut) { fStart[i] = total; fSize[i] = outFaces[i].size; total += fSize[i] }
        val corners = IntArray(total)
        val cornerSrc = IntArray(total)
        for (i in 0 until nOut) { outFaces[i].copyInto(corners, fStart[i]); outSrc[i].copyInto(cornerSrc, fStart[i]) }
        fun faceSrc(i: Int) = outFaceOf[i].let { if (it < 0) -1 - it else it }
        val faceMat = IntArray(nOut) { i ->
            val m = d.faceMat?.getOrNull(faceSrc(i)) ?: 0
            if (outFaceOf[i] >= 0 && p.matOfs != 0) (m + p.matOfs).coerceIn(0, p.matMax) else m
        }
        val faceSmooth = d.faceSmooth?.let { s -> BooleanArray(nOut) { s.getOrElse(faceSrc(it)) { false } } }
        val uv = d.uv?.let { u -> FloatArray(total * 2) { u[cornerSrc[it / 2] * 2 + it % 2] } }
        val cc = d.cornerColor?.let { c -> IntArray(total) { c[cornerSrc[it]] } }
        val pc = d.pointColor?.let { c -> IntArray(totV) { c[srcVert(it)] } }
        val vc = d.vertCrease?.let { c -> FloatArray(totV) { c[srcVert(it)] } }
        val dv = d.dverts?.let { a -> Array(totV) { a[srcVert(it)] } }
        val crease = HashMap<Long, Float>()
        if (!useReplace) crease.putAll(d.edgeCrease)
        for (k in creased) crease[k] = p.creaseWeight
        val r = BlendMeshData(outCo, fStart, fSize, corners, faceMat, faceSmooth, uv, cc, pc, crease, vc, if (useReplace) IntArray(0) else d.looseEdges, dv, d.groups)
        return r.carryNormals(d, if (useReplace) emptySet() else d.sharpEdges)
    }
}
