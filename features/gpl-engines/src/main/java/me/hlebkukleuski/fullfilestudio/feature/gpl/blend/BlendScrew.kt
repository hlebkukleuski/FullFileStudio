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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal object BlendScrew {
    private const val UNUSED = -1
    private const val INVALID = -2

    class Params(
        val steps: Int, val iter: Int, val screwOfs: Float, val angle: Float, val mergeDist: Float, val flag: Int, val axis: Int,
        val axisMatrix: FloatArray?
    )

    fun apply(d: BlendMeshData, p: Params): BlendMeshData {
        val totvert = d.nV
        if (totvert == 0) return d
        val edgeList = ArrayList<IntArray>()
        val fe = d.fileEdges
        if (fe != null) for (e in 0 until fe.size / 2) edgeList.add(intArrayOf(fe[e * 2], fe[e * 2 + 1]))
        else {
            val seen = HashSet<Long>()
            for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
                val a = d.corners[d.fStart[f] + k]; val b = d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]
                if (seen.add(BlendMeshData.key(a, b))) edgeList.add(intArrayOf(minOf(a, b), maxOf(a, b)))
            }
            for (i in 0 until d.looseEdges.size / 2) if (seen.add(BlendMeshData.key(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))) edgeList.add(intArrayOf(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))
        }
        val totedge = edgeList.size
        val edgeIndex = HashMap<Long, Int>()
        for ((i, e) in edgeList.withIndex()) edgeIndex[BlendMeshData.key(e[0], e[1])] = i
        val doFlip = p.flag and 1 != 0
        val quadOrd = if (doFlip) intArrayOf(3, 2, 1, 0) else intArrayOf(0, 1, 2, 3)
        val flat = p.flag and (1 shl 5) == 0
        val other1 = if (p.axis == 0) 1 else 0
        val other2 = if (p.axis == 2) 1 else 2
        var axisVec = FloatArray(3).also { it[p.axis] = 1f }
        var angle = p.angle
        var screwOfs = p.screwOfs
        val mtx = p.axisMatrix
        if (mtx != null) {
            val x = mtx[0] * axisVec[0] + mtx[4] * axisVec[1] + mtx[8] * axisVec[2]
            val y = mtx[1] * axisVec[0] + mtx[5] * axisVec[1] + mtx[9] * axisVec[2]
            val z = mtx[2] * axisVec[0] + mtx[6] * axisVec[1] + mtx[10] * axisVec[2]
            val l = sqrt(x * x + y * y + z * z)
            axisVec = if (l > 1.0e-35f) floatArrayOf(x / l, y / l, z / l) else floatArrayOf(0f, 0f, 0f)
            if (p.flag and (1 shl 2) != 0) {
                val tx = mtx[12]; val ty = mtx[13]; val tz = mtx[14]
                screwOfs = if (sqrt(tx * tx + ty * ty + tz * tz) != 0f) {
                    val dd = axisVec[0] * axisVec[0] + axisVec[1] * axisVec[1] + axisVec[2] * axisVec[2]
                    if (dd == 0f) 0f else (axisVec[0] * tx + axisVec[1] * ty + axisVec[2] * tz) / dd
                } else 0f
            }
        }
        angle *= p.iter.toFloat()
        screwOfs *= p.iter.toFloat()
        var uvUScale = 1.0f / p.steps.toFloat()
        var stepTot = ((p.steps + 1) * p.iter) - (p.iter - 1)
        val close: Boolean
        if (abs(screwOfs) <= 1.1920929E-7f * 100.0f && abs(abs(angle) - Math.PI.toFloat() * 2.0f) <= 1.1920929E-7f * 100.0f && stepTot > 3) {
            close = true
            stepTot--
            screwOfs = 0f
        } else {
            close = false
            stepTot = maxOf(stepTot, 2)
        }
        if (p.flag and (1 shl 6) == 0) uvUScale = (uvUScale / p.iter.toFloat()) * (angle / (Math.PI.toFloat() * 2.0f))
        val doRemoveDoubles = p.flag and (1 shl 8) != 0 && screwOfs == 0.0f
        val edges = Array(totedge) { edgeList[it].copyOf() }
        var edgeFace: IntArray? = null
        var vertLoop: IntArray? = null
        if (d.nF > 0) {
            edgeFace = IntArray(totedge) { -1 }
            vertLoop = IntArray(totvert) { -1 }
            for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
                val c = d.fStart[f] + k
                val v = d.corners[c]
                val e = edgeIndex[BlendMeshData.key(v, d.corners[d.fStart[f] + (k + 1) % d.fSize[f]])] ?: continue
                edgeFace[e] = f
                vertLoop[v] = c
                if (edges[e][0] != v) { val t = edges[e][0]; edges[e][0] = edges[e][1]; edges[e][1] = t }
            }
        }
        val co = FloatArray(totvert * stepTot * 3)
        d.co.copyInto(co, 0, 0, totvert * 3)
        if (p.flag and 2 != 0 && totedge != 0) {
            val vco = FloatArray(totvert * 3)
            val dist = FloatArray(totvert)
            val vv = Array(totvert) { intArrayOf(UNUSED, UNUSED) }
            val ve = Array(totvert) { intArrayOf(-1, -1) }
            val flagArr = IntArray(totvert)
            for (i in 0 until totvert) {
                var x = d.co[i * 3]; var y = d.co[i * 3 + 1]; var z = d.co[i * 3 + 2]
                if (mtx != null) { val tp = BlendAnimator.transformPoint(mtx, x, y, z); x = tp[0]; y = tp[1]; z = tp[2] }
                vco[i * 3] = x; vco[i * 3 + 1] = y; vco[i * 3 + 2] = z
                dist[i] = vco[i * 3 + other1] * vco[i * 3 + other1] + vco[i * 3 + other2] * vco[i * 3 + other2]
            }
            for ((ei, e) in edges.withIndex()) {
                for (side in 0 until 2) {
                    val a = e[side]; val b = e[1 - side]
                    if (vv[a][0] == UNUSED) { vv[a][0] = b; ve[a][0] = ei }
                    else if (vv[a][1] == UNUSED) { vv[a][1] = b; ve[a][1] = ei }
                    else { vv[a][0] = INVALID; vv[a][1] = INVALID }
                }
            }
            fun valid(v: Int) = v >= 0
            class It(var v: Int, var vOther: Int, var e: Int)
            fun iterInit(vInit: Int, dir: Int): It? = if (valid(vInit)) It(vInit, vv[vInit][dir], ve[vInit][1 - dir]) else null
            fun iterStep(it: It): Boolean {
                val vp = vv[it.v]
                if (vp[0] == it.vOther) { it.vOther = it.v; it.v = vp[1] }
                else if (vp[1] == it.vOther) { it.vOther = it.v; it.v = vp[0] }
                if (valid(it.v)) {
                    val ep = ve[it.v]
                    it.e = ep[if (ep[0] == it.e) 1 else 0]
                    return true
                }
                it.e = -1
                return false
            }
            for (i in 0 until totvert) {
                var linked = 0
                if (flagArr[i] != 0) continue
                var vBest = UNUSED
                var loopClosed = 0
                var fl = -1.0f
                var loopFlip = false
                for (j in 0 until 2) {
                    var it = iterInit(i, j) ?: continue
                    var alive = true
                    if (j == 1) alive = iterStep(it)
                    while (alive) {
                        if (flagArr[it.v] != 0) { loopClosed = 1; break }
                        flagArr[it.v] = 1
                        linked++
                        if (fl <= dist[it.v]) { fl = dist[it.v]; vBest = it.v }
                        alive = iterStep(it)
                    }
                }
                if (linked > 1) {
                    val vb = vv[vBest]
                    val ax = p.axis
                    if (valid(vb[0]) && valid(vb[1])) {
                        val vf1 = vco[vb[0] * 3 + ax]; val vf2 = vco[vb[1] * 3 + ax]; val vfb = vco[vBest * 3 + ax]
                        loopFlip = if (vf1 < vfb && vfb < vf2) false else if (vf1 > vfb && vfb > vf2) true else {
                            fun nrm(o: Int): FloatArray { val x = vco[o * 3] - vco[vBest * 3]; val y = vco[o * 3 + 1] - vco[vBest * 3 + 1]; val z = vco[o * 3 + 2] - vco[vBest * 3 + 2]; val d2 = x * x + y * y + z * z; return if (d2 > 1.0e-35f) { val inv = 1f / sqrt(d2); floatArrayOf(x * inv, y * inv, z * inv) } else floatArrayOf(0f, 0f, 0f) }
                            nrm(vb[0])[ax] < nrm(vb[1])[ax]
                        }
                    } else if (valid(vb[0])) {
                        loopFlip = vco[vb[0] * 3 + ax] < vco[vBest * 3 + ax]
                    }
                    if (angle < 0.0f) loopFlip = !loopFlip
                    for (j in loopClosed until 2) {
                        val it = iterInit(vBest, j) ?: continue
                        flagArr[it.v] = 1
                        if (j == 1 && valid(vb[0]) && valid(vb[1])) loopFlip = !loopFlip
                        var alive = true
                        while (alive && flagArr[it.v] != 2) {
                            flagArr[it.v] = 2
                            if (it.e >= 0) {
                                val e = edges[it.e]
                                if (it.v == e[0]) { if (!loopFlip) { val t = e[0]; e[0] = e[1]; e[1] = t } }
                                else if (it.v == e[1]) { if (loopFlip) { val t = e[0]; e[0] = e[1]; e[1] = t } }
                            }
                            alive = iterStep(it)
                        }
                    }
                }
            }
        }
        for (step in 1 until stepTot) {
            val stepAngle = (angle / (stepTot - (if (close) 0 else 1)).toFloat()) * step.toFloat()
            val m = FloatArray(16)
            val c = cos(stepAngle); val s = sin(stepAngle)
            if (mtx != null) {
                val ico = 1.0f - c
                val n0 = axisVec[0] * s; val n1 = axisVec[1] * s; val n2 = axisVec[2] * s
                val a0 = axisVec[0]; val a1 = axisVec[1]; val a2 = axisVec[2]
                m[0] = (a0 * a0) * ico + c; m[1] = (a0 * a1) * ico + n2; m[2] = (a0 * a2) * ico - n1
                m[4] = (a0 * a1) * ico - n2; m[5] = (a1 * a1) * ico + c; m[6] = (a1 * a2) * ico + n0
                m[8] = (a0 * a2) * ico + n1; m[9] = (a1 * a2) * ico - n0; m[10] = (a2 * a2) * ico + c
            } else when (p.axis) {
                0 -> { m[0] = 1f; m[5] = c; m[6] = s; m[9] = -s; m[10] = c }
                1 -> { m[0] = c; m[2] = -s; m[5] = 1f; m[8] = s; m[10] = c }
                else -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c; m[10] = 1f }
            }
            m[15] = 1f
            if (screwOfs != 0f) for (k in 0 until 3) m[12 + k] += axisVec[k] * (screwOfs * (step.toFloat() / (stepTot - 1).toFloat()))
            for (j in 0 until totvert) {
                var x = co[j * 3]; var y = co[j * 3 + 1]; var z = co[j * 3 + 2]
                if (mtx != null) { x -= mtx[12]; y -= mtx[13]; z -= mtx[14] }
                val r = BlendAnimator.transformPoint(m, x, y, z)
                if (mtx != null) { r[0] += mtx[12]; r[1] += mtx[13]; r[2] += mtx[14] }
                r.copyInto(co, (totvert * step + j) * 3)
            }
        }
        val hasUv = d.uv != null
        val plane = floatArrayOf(axisVec[0], axisVec[1], axisVec[2], 0f)
        val uvMin = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE)
        var uvRangeInv = 0f
        if (hasUv) {
            if (p.flag and (1 shl 7) != 0) {
                for (i in 0 until totvert) {
                    val dd = plane[0] * d.co[i * 3] + plane[1] * d.co[i * 3 + 1] + plane[2] * d.co[i * 3 + 2]
                    val len = plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]
                    val v = (if (dd < 0) -1f else 1f) * (dd * dd) / len
                    uvMin[0] = minOf(v, uvMin[0]); uvMin[1] = maxOf(v, uvMin[1])
                }
                uvMin[0] = if (uvMin[0] < 0) -sqrt(-uvMin[0]) else sqrt(uvMin[0])
                uvMin[1] = if (uvMin[1] < 0) -sqrt(-uvMin[1]) else sqrt(uvMin[1])
            }
            val r = uvMin[1] - uvMin[0]
            uvRangeInv = if (r != 0f) 1.0f / r else 0.0f
        }
        val stepLast = stepTot - (if (close) 1 else 2)
        val nFaces = totedge * (stepLast + 1)
        val corners = IntArray(nFaces * 4)
        val uv = if (hasUv) FloatArray(nFaces * 8) else null
        val cc = d.cornerColor?.let { IntArray(nFaces * 4) }
        val faceMat = IntArray(nFaces)
        val faceSmooth = BooleanArray(nFaces)
        var fi = 0
        for (i in 0 until totedge) {
            val faceOrig = edgeFace?.get(i) ?: -1
            val e = edges[i]
            val l0 = vertLoop?.get(e[0]) ?: -1
            val l1 = vertLoop?.get(e[1]) ?: -1
            val hasLoop = l0 != -1
            var i1 = e[0]; var i2 = e[1]
            val first0 = e[0]; val first1 = e[1]
            var va = 0f; var vb = 0f
            if (!hasLoop && hasUv) {
                fun sd(v: Int): Float { val len = sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]); return (plane[0] * co[v * 3] + plane[1] * co[v * 3 + 1] + plane[2] * co[v * 3 + 2]) / len }
                va = sd(e[0]); vb = sd(e[1])
                if (p.flag and (1 shl 7) != 0) { va = (va - uvMin[0]) * uvRangeInv; vb = (vb - uvMin[0]) * uvRangeInv }
            }
            for (step in 0..stepLast) {
                if (faceOrig >= 0) {
                    faceMat[fi] = d.faceMat?.getOrNull(faceOrig) ?: 0
                    faceSmooth[fi] = d.faceSmooth?.getOrNull(faceOrig) ?: false
                } else {
                    faceMat[fi] = 0
                    faceSmooth[fi] = !flat
                }
                val base = fi * 4
                val ua = step.toFloat() * uvUScale
                val ub = (step + 1).toFloat() * uvUScale
                if (hasLoop) {
                    val src = intArrayOf(l0, l1, l1, l0)
                    for (k in 0 until 4) {
                        if (uv != null) { uv[(base + k) * 2] = d.uv!![src[k] * 2]; uv[(base + k) * 2 + 1] = d.uv!![src[k] * 2 + 1] }
                        if (cc != null) cc[base + k] = d.cornerColor!![src[k]]
                    }
                    if (uv != null) {
                        uv[(base + quadOrd[0]) * 2] += ua; uv[(base + quadOrd[1]) * 2] += ua
                        uv[(base + quadOrd[2]) * 2] += ub; uv[(base + quadOrd[3]) * 2] += ub
                    }
                } else if (uv != null) {
                    uv[(base + quadOrd[0]) * 2] = ua; uv[(base + quadOrd[0]) * 2 + 1] = va
                    uv[(base + quadOrd[1]) * 2] = ua; uv[(base + quadOrd[1]) * 2 + 1] = vb
                    uv[(base + quadOrd[2]) * 2] = ub; uv[(base + quadOrd[2]) * 2 + 1] = vb
                    uv[(base + quadOrd[3]) * 2] = ub; uv[(base + quadOrd[3]) * 2 + 1] = va
                }
                if (!(close && step == stepLast)) {
                    corners[base + quadOrd[0]] = i1; corners[base + quadOrd[1]] = i2
                    corners[base + quadOrd[2]] = i2 + totvert; corners[base + quadOrd[3]] = i1 + totvert
                    i1 += totvert; i2 += totvert
                } else {
                    corners[base + quadOrd[0]] = i1; corners[base + quadOrd[1]] = i2
                    corners[base + quadOrd[2]] = first1; corners[base + quadOrd[3]] = first0
                }
                fi++
            }
        }
        val nV = totvert * stepTot
        val r = BlendMeshData(
            co, IntArray(nFaces) { it * 4 }, IntArray(nFaces) { 4 }, corners, faceMat, faceSmooth, uv, cc,
            d.pointColor?.let { pc -> IntArray(nV) { pc[it % totvert] } }, emptyMap(), d.vertCrease?.let { vc -> FloatArray(nV) { vc[it % totvert] } },
            IntArray(0), d.dverts?.let { dv -> Array(nV) { dv[it % totvert] } }, d.groups
        ).carryNormals(d, emptySet())
        if (!doRemoveDoubles) return r
        val dest = IntArray(nV) { -1 }
        var any = false
        val tsq = p.mergeDist * p.mergeDist
        for (i in 0 until totvert) {
            var x = co[i * 3]; var y = co[i * 3 + 1]; var z = co[i * 3 + 2]
            if (mtx != null) { x -= mtx[12]; y -= mtx[13]; z -= mtx[14] }
            val dd = x * axisVec[0] + y * axisVec[1] + z * axisVec[2]
            var px = axisVec[0] * dd; var py = axisVec[1] * dd; var pz = axisVec[2] * dd
            if (mtx != null) { px += mtx[12]; py += mtx[13]; pz += mtx[14] }
            val dx = px - co[i * 3]; val dy = py - co[i * 3 + 1]; val dz = pz - co[i * 3 + 2]
            if (dx * dx + dy * dy + dz * dz <= tsq) {
                any = true
                co[i * 3] = px; co[i * 3 + 1] = py; co[i * 3 + 2] = pz
                for (step in 1 until stepTot) dest[totvert * step + i] = i
            }
        }
        return if (any) BlendMeshOps.mergeVerts(r, dest) else r
    }
}
