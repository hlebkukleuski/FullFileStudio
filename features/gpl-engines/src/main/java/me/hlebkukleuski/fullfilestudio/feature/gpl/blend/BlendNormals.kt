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

internal class NormalSpec(val sharpEdges: Set<Long>, val customShort: ShortArray?, val customFloat: FloatArray?, val autoSmoothAngle: Float?)

internal object BlendNormals {
    private const val TRIGO = 1.0f - 1e-4f

    fun acosApprox(x: Float): Float {
        val f = abs(x)
        val m = if (f < 1.0f) 1.0f - (1.0f - f) else 1.0f
        val a = sqrt(1.0f - m) * (1.5707963267f + m * (-0.213300989f + m * (0.077980478f + m * -0.02164095f)))
        return if (x < 0.0f) Math.PI.toFloat() - a else a
    }

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val d = x * x + y * y + z * z
        if (d > 1.0e-35f) { val l = sqrt(d); return floatArrayOf(x / l, y / l, z / l) }
        return floatArrayOf(0f, 0f, 0f)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun faceNormals(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray): FloatArray {
        val topo = MeshTopology(corners, fStart, fSize, IntArray(0))
        val out = FloatArray(fStart.size * 3)
        val tmp = FloatArray(3)
        for (f in fStart.indices) {
            if (fSize[f] < 3) { out[f * 3 + 2] = 1f; continue }
            topo.faceNormal(co, fStart[f], fSize[f], tmp)
            tmp.copyInto(out, f * 3)
        }
        return out
    }

    fun sharpFromAngle(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray, sharpFaces: BooleanArray?, sharp: Set<Long>, angle: Float): Set<Long> {
        if (angle >= Math.PI.toFloat()) return sharp
        val fn = faceNormals(co, corners, fStart, fSize)
        val cosA = cos(angle)
        val firstFace = HashMap<Long, Int>()
        val firstVert = HashMap<Long, Int>()
        val state = HashMap<Long, Int>()
        val res = HashSet(sharp)
        for (f in fStart.indices) {
            val s = fStart[f]
            val n = fSize[f]
            val smooth = sharpFaces == null || !sharpFaces[f]
            for (k in 0 until n) {
                val v = corners[s + k]
                val key = BlendMeshData.key(v, corners[s + (k + 1) % n])
                when (state[key]) {
                    null -> { firstFace[key] = f; firstVert[key] = v; state[key] = if (smooth) 0 else 2 }
                    0 -> {
                        val g = firstFace[key]!!
                        val isAngleSharp = fn[g * 3] * fn[f * 3] + fn[g * 3 + 1] * fn[f * 3 + 1] + fn[g * 3 + 2] * fn[f * 3 + 2] < cosA
                        if (!smooth || key in res || v == firstVert[key] || isAngleSharp) {
                            state[key] = 2
                            if (isAngleSharp) res.add(key)
                        } else state[key] = 1
                    }
                    1 -> { state[key] = 2; res.remove(key) }
                }
            }
        }
        return res
    }

    fun cornerNormals(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray, sharpFaces: BooleanArray?, spec: NormalSpec?): FloatArray {
        val nV = co.size / 3
        val nC = corners.size
        val out = FloatArray(nC * 3)
        val fn = faceNormals(co, corners, fStart, fSize)
        var sharpEdges = spec?.sharpEdges ?: emptySet()
        val angle = spec?.autoSmoothAngle
        if (angle != null) sharpEdges = sharpFromAngle(co, corners, fStart, fSize, sharpFaces, sharpEdges, angle)
        val cf = spec?.customFloat
        val cs = spec?.customShort
        if (cf == null && cs == null) {
            val allSharp = fStart.isNotEmpty() && sharpFaces != null && sharpFaces.size >= fStart.size && fStart.indices.all { sharpFaces[it] }
            if (allSharp) {
                for (f in fStart.indices) for (k in 0 until fSize[f]) fn.copyInto(out, (fStart[f] + k) * 3, f * 3, f * 3 + 3)
                return out
            }
            if (sharpEdges.isEmpty() && (sharpFaces == null || fStart.indices.none { sharpFaces.getOrElse(it) { false } })) {
                val vn = MeshTopology(corners, fStart, fSize, IntArray(0)).vertexNormals(co)
                for (c in 0 until nC) { val v = corners[c]; if (v in 0 until nV) vn.copyInto(out, c * 3, v * 3, v * 3 + 3) }
                return out
            }
        }
        val vertFaces = Array(nV) { ArrayList<Int>(4) }
        for (f in fStart.indices) for (k in 0 until fSize[f]) { val v = corners[fStart[f] + k]; if (v in 0 until nV && (vertFaces[v].isEmpty() || vertFaces[v].last() != f)) vertFaces[v].add(f) }
        for (v in 0 until nV) {
            val vf = vertFaces[v]
            if (vf.isEmpty()) continue
            val m = vf.size
            val cCorner = IntArray(m); val cPrev = IntArray(m); val cNext = IntArray(m)
            val vPrev = IntArray(m); val vNext = IntArray(m)
            for (i in 0 until m) {
                val f = vf[i]; val s = fStart[f]; val n = fSize[f]
                var k = 0
                while (k < n && corners[s + k] != v) k++
                cCorner[i] = s + k
                cPrev[i] = s + (k + n - 1) % n
                cNext[i] = s + (k + 1) % n
                vPrev[i] = corners[cPrev[i]]; vNext[i] = corners[cNext[i]]
            }
            val localEdge = LinkedHashMap<Int, Int>()
            val lePrev = IntArray(m); val leNext = IntArray(m)
            for (i in 0 until m) {
                lePrev[i] = localEdge.getOrPut(vPrev[i]) { localEdge.size }
                leNext[i] = localEdge.getOrPut(vNext[i]) { localEdge.size }
            }
            val ne = localEdge.size
            val kind = IntArray(ne)
            val c1 = IntArray(ne); val c2 = IntArray(ne)
            val towards = BooleanArray(ne)
            fun addCorner(le: Int, local: Int, other: Int, toward: Boolean) {
                when (kind[le]) {
                    0 -> {
                        val ev = if (toward) BlendMeshData.key(corners[other], v) else BlendMeshData.key(v, corners[other])
                        if (ev in sharpEdges) { kind[le] = 3; return }
                        kind[le] = 1; c1[le] = local; towards[le] = toward
                    }
                    1 -> if (towards[le] == toward) kind[le] = 3 else { kind[le] = 2; c2[le] = local }
                    else -> kind[le] = 3
                }
            }
            for (i in 0 until m) {
                if (sharpFaces != null && sharpFaces[vf[i]]) { kind[lePrev[i]] = 3; kind[leNext[i]] = 3; continue }
                addCorner(lePrev[i], i, cPrev[i], true)
                addCorner(leNext[i], i, cNext[i], false)
            }
            val dirs = Array(ne) { FloatArray(3) }
            for ((ov, idx) in localEdge) dirs[idx] = normalize(co[ov * 3] - co[v * 3], co[ov * 3 + 1] - co[v * 3 + 1], co[ov * 3 + 2] - co[v * 3 + 2])
            val visited = BooleanArray(m)
            var visitedCount = 0
            var start = 0
            while (start != -1) {
                val fan = ArrayList<Int>()
                fan.add(start)
                var cur = start
                var le = leNext[cur]
                var cyclic = false
                while (kind[le] == 2) {
                    cur = if (cur == c1[le]) c2[le] else c1[le]
                    if (cur == start) { cyclic = true; break }
                    fan.add(cur)
                    le = leNext[cur]
                }
                fan.reverse()
                if (cyclic) {
                    var mi = 0
                    for (i in fan.indices) if (cCorner[fan[i]] < cCorner[fan[mi]]) mi = i
                    java.util.Collections.rotate(fan, -mi)
                } else {
                    cur = start
                    le = lePrev[cur]
                    while (kind[le] == 2) {
                        cur = if (cur == c1[le]) c2[le] else c1[le]
                        fan.add(cur)
                        le = lePrev[cur]
                    }
                }
                var normal: FloatArray
                if (fan.size == 1) {
                    val f = vf[fan[0]]
                    normal = floatArrayOf(fn[f * 3], fn[f * 3 + 1], fn[f * 3 + 2])
                } else {
                    var x = 0f; var y = 0f; var z = 0f
                    for (lc in fan) {
                        val fac = acosApprox(dot(dirs[lePrev[lc]], dirs[leNext[lc]]))
                        val f = vf[lc]
                        x += fn[f * 3] * fac; y += fn[f * 3 + 1] * fac; z += fn[f * 3 + 2] * fac
                    }
                    normal = normalize(x, y, z)
                }
                if (cs != null) {
                    val leFirst = leNext[fan.first()]
                    val leLast = lePrev[fan.last()]
                    val edgeVecs = ArrayList<FloatArray>()
                    if (fan.size > 1) {
                        for (lc in fan) edgeVecs.add(dirs[leNext[lc]])
                        if (leLast != leFirst) edgeVecs.add(dirs[leLast])
                    }
                    var a0 = 0; var a1 = 0
                    for (lc in fan) { a0 += cs[cCorner[lc] * 2]; a1 += cs[cCorner[lc] * 2 + 1] }
                    normal = customToNormal(normal, dirs[leFirst], dirs[leLast], edgeVecs, (a0 / fan.size).toShort(), (a1 / fan.size).toShort())
                }
                for (lc in fan) normal.copyInto(out, cCorner[lc] * 3)
                visitedCount += fan.size
                if (visitedCount >= m) break
                for (lc in fan) visited[lc] = true
                start = visited.indexOfFirst { !it }
            }
        }
        if (cf != null && cf.size >= nC * 3) {
            for (c in 0 until nC) {
                val r = normalize(cf[c * 3], cf[c * 3 + 1], cf[c * 3 + 2])
                if (r[0] != 0f || r[1] != 0f || r[2] != 0f) r.copyInto(out, c * 3)
            }
        }
        return out
    }

    private fun customToNormal(lnor: FloatArray, vecRef: FloatArray, vecOther: FloatArray, edgeVecs: List<FloatArray>, d0: Short, d1: Short): FloatArray {
        val pi2 = Math.PI.toFloat() * 2.0f
        val dtpRef = dot(vecRef, lnor)
        val dtpOther = dot(vecOther, lnor)
        if (abs(dtpRef) >= TRIGO || abs(dtpOther) >= TRIGO) return lnor
        val refAlpha = if (edgeVecs.isNotEmpty()) {
            var a = 0f
            for (v in edgeVecs) a += acosApprox(dot(v, lnor))
            a / edgeVecs.size.toFloat()
        } else (acosApprox(dot(vecRef, lnor)) + acosApprox(dot(vecOther, lnor))) / 2.0f
        val ref = normalize(vecRef[0] - lnor[0] * dtpRef, vecRef[1] - lnor[1] * dtpRef, vecRef[2] - lnor[2] * dtpRef)
        val ortho = normalize(lnor[1] * ref[2] - lnor[2] * ref[1], lnor[2] * ref[0] - lnor[0] * ref[2], lnor[0] * ref[1] - lnor[1] * ref[0])
        val otherProj = normalize(vecOther[0] - lnor[0] * dtpOther, vecOther[1] - lnor[1] * dtpOther, vecOther[2] - lnor[2] * dtpOther)
        val dtp = dot(ref, otherProj)
        val refBeta = if (dtp < TRIGO) { val b = acosApprox(dtp); if (dot(ortho, otherProj) < 0f) pi2 - b else b } else pi2
        if (d0.toInt() == 0 || refAlpha == 0f || refBeta == 0f) return lnor
        val alphaFac = d0.toFloat() / Short.MAX_VALUE.toFloat()
        val alpha = (if (alphaFac > 0f) refAlpha else pi2 - refAlpha) * alphaFac
        val betaFac = d1.toFloat() / Short.MAX_VALUE.toFloat()
        val r = FloatArray(3) { lnor[it] * cos(alpha) }
        if (betaFac == 0f) {
            val s = sin(alpha)
            for (k in 0 until 3) r[k] += ref[k] * s
        } else {
            val sa = sin(alpha)
            val beta = (if (betaFac > 0f) refBeta else pi2 - refBeta) * betaFac
            for (k in 0 until 3) r[k] += ref[k] * (sa * cos(beta))
            for (k in 0 until 3) r[k] += ortho[k] * (sa * sin(beta))
        }
        return r
    }

    class Space(val corners: IntArray, val lnor: FloatArray, val ref: FloatArray, val ortho: FloatArray, val refAlpha: Float, val refBeta: Float, val valid: Boolean)

    class Fans(val normals: FloatArray, val spaceOf: IntArray, val spaces: List<Space>)

    fun defineSpace(fanCorners: IntArray, lnor: FloatArray, vecRef: FloatArray, vecOther: FloatArray, edgeVecs: List<FloatArray>): Space {
        val pi2 = Math.PI.toFloat() * 2.0f
        val dtpRef = dot(vecRef, lnor)
        val dtpOther = dot(vecOther, lnor)
        if (abs(dtpRef) >= TRIGO || abs(dtpOther) >= TRIGO) return Space(fanCorners, lnor, FloatArray(3), FloatArray(3), 0f, 0f, false)
        val refAlpha = if (edgeVecs.isNotEmpty()) {
            var a = 0f
            for (v in edgeVecs) a += acosApprox(dot(v, lnor))
            a / edgeVecs.size.toFloat()
        } else (acosApprox(dot(vecRef, lnor)) + acosApprox(dot(vecOther, lnor))) / 2.0f
        val ref = normalize(vecRef[0] - lnor[0] * dtpRef, vecRef[1] - lnor[1] * dtpRef, vecRef[2] - lnor[2] * dtpRef)
        val ortho = normalize(lnor[1] * ref[2] - lnor[2] * ref[1], lnor[2] * ref[0] - lnor[0] * ref[2], lnor[0] * ref[1] - lnor[1] * ref[0])
        val otherProj = normalize(vecOther[0] - lnor[0] * dtpOther, vecOther[1] - lnor[1] * dtpOther, vecOther[2] - lnor[2] * dtpOther)
        val dtp = dot(ref, otherProj)
        val refBeta = if (dtp < TRIGO) { val b = acosApprox(dtp); if (dot(ortho, otherProj) < 0f) pi2 - b else b } else pi2
        return Space(fanCorners, lnor, ref, ortho, refAlpha, refBeta, true)
    }

    private fun unitToShort(v: Float): Short = kotlin.math.floor(v * Short.MAX_VALUE.toFloat() + 0.5f).toInt().toShort()

    fun encode(sp: Space, n: FloatArray): ShortArray {
        if (!sp.valid) return shortArrayOf(0, 0)
        if ((n[0] == 0f && n[1] == 0f && n[2] == 0f) || (abs(sp.lnor[0] - n[0]) <= 1e-4f && abs(sp.lnor[1] - n[1]) <= 1e-4f && abs(sp.lnor[2] - n[2]) <= 1e-4f)) return shortArrayOf(0, 0)
        val pi2 = (Math.PI * 2.0).toFloat()
        val cosA = dot(sp.lnor, n)
        val alpha = acosApprox(cosA)
        val r0 = if (alpha > sp.refAlpha) unitToShort(-(pi2 - alpha) / (pi2 - sp.refAlpha)) else unitToShort(alpha / sp.refAlpha)
        val vec = normalize(sp.lnor[0] * -cosA + n[0], sp.lnor[1] * -cosA + n[1], sp.lnor[2] * -cosA + n[2])
        val cosB = dot(sp.ref, vec)
        val r1: Short = if (cosB < TRIGO) {
            var beta = acosApprox(cosB)
            if (dot(sp.ortho, vec) < 0f) beta = pi2 - beta
            if (beta > sp.refBeta) unitToShort(-(pi2 - beta) / (pi2 - sp.refBeta)) else unitToShort(beta / sp.refBeta)
        } else 0
        return shortArrayOf(r0, r1)
    }

    fun computeFans(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray, sharpFaces: BooleanArray?, sharpEdges: Set<Long>, faceNo: FloatArray? = null): Fans {
        val nV = co.size / 3
        val nC = corners.size
        val out = FloatArray(nC * 3)
        val spaceOf = IntArray(nC) { -1 }
        val spaces = ArrayList<Space>()
        val fn = faceNo ?: faceNormals(co, corners, fStart, fSize)
        val vertFaces = Array(nV) { ArrayList<Int>(4) }
        for (f in fStart.indices) for (k in 0 until fSize[f]) { val v = corners[fStart[f] + k]; if (v in 0 until nV && (vertFaces[v].isEmpty() || vertFaces[v].last() != f)) vertFaces[v].add(f) }
        for (v in 0 until nV) {
            val vf = vertFaces[v]
            if (vf.isEmpty()) continue
            val m = vf.size
            val cCorner = IntArray(m); val cPrev = IntArray(m); val cNext = IntArray(m)
            val vPrev = IntArray(m); val vNext = IntArray(m)
            for (i in 0 until m) {
                val f = vf[i]; val st = fStart[f]; val n = fSize[f]
                var k = 0
                while (k < n && corners[st + k] != v) k++
                cCorner[i] = st + k; cPrev[i] = st + (k + n - 1) % n; cNext[i] = st + (k + 1) % n
                vPrev[i] = corners[cPrev[i]]; vNext[i] = corners[cNext[i]]
            }
            val localEdge = LinkedHashMap<Int, Int>()
            val lePrev = IntArray(m); val leNext = IntArray(m)
            for (i in 0 until m) { lePrev[i] = localEdge.getOrPut(vPrev[i]) { localEdge.size }; leNext[i] = localEdge.getOrPut(vNext[i]) { localEdge.size } }
            val ne = localEdge.size
            val kind = IntArray(ne); val c1 = IntArray(ne); val c2 = IntArray(ne); val towards = BooleanArray(ne)
            fun addCorner(le: Int, local: Int, other: Int, toward: Boolean) {
                when (kind[le]) {
                    0 -> {
                        val ev = BlendMeshData.key(corners[other], v)
                        if (ev in sharpEdges) { kind[le] = 3; return }
                        kind[le] = 1; c1[le] = local; towards[le] = toward
                    }
                    1 -> if (towards[le] == toward) kind[le] = 3 else { kind[le] = 2; c2[le] = local }
                    else -> kind[le] = 3
                }
            }
            for (i in 0 until m) {
                if (sharpFaces != null && sharpFaces.getOrElse(vf[i]) { false }) { kind[lePrev[i]] = 3; kind[leNext[i]] = 3; continue }
                addCorner(lePrev[i], i, cPrev[i], true)
                addCorner(leNext[i], i, cNext[i], false)
            }
            val dirs = Array(ne) { FloatArray(3) }
            for ((ov, idx) in localEdge) dirs[idx] = normalize(co[ov * 3] - co[v * 3], co[ov * 3 + 1] - co[v * 3 + 1], co[ov * 3 + 2] - co[v * 3 + 2])
            val visited = BooleanArray(m)
            var visitedCount = 0
            var start = 0
            while (start != -1) {
                val fan = ArrayList<Int>()
                fan.add(start)
                var cur = start
                var le = leNext[cur]
                var cyclic = false
                while (kind[le] == 2) {
                    cur = if (cur == c1[le]) c2[le] else c1[le]
                    if (cur == start) { cyclic = true; break }
                    fan.add(cur)
                    le = leNext[cur]
                }
                fan.reverse()
                if (cyclic) {
                    var mi = 0
                    for (i in fan.indices) if (cCorner[fan[i]] < cCorner[fan[mi]]) mi = i
                    java.util.Collections.rotate(fan, -mi)
                } else {
                    cur = start
                    le = lePrev[cur]
                    while (kind[le] == 2) { cur = if (cur == c1[le]) c2[le] else c1[le]; fan.add(cur); le = lePrev[cur] }
                }
                val normal: FloatArray = if (fan.size == 1) { val f = vf[fan[0]]; floatArrayOf(fn[f * 3], fn[f * 3 + 1], fn[f * 3 + 2]) } else {
                    var x = 0f; var y = 0f; var z = 0f
                    for (lc in fan) { val fac = acosApprox(dot(dirs[lePrev[lc]], dirs[leNext[lc]])); val f = vf[lc]; x += fn[f * 3] * fac; y += fn[f * 3 + 1] * fac; z += fn[f * 3 + 2] * fac }
                    normalize(x, y, z)
                }
                val leFirst = leNext[fan.first()]
                val leLast = lePrev[fan.last()]
                val edgeVecs = ArrayList<FloatArray>()
                if (fan.size > 1) { for (lc in fan) edgeVecs.add(dirs[leNext[lc]]); if (leLast != leFirst) edgeVecs.add(dirs[leLast]) }
                val sp = defineSpace(IntArray(fan.size) { cCorner[fan[it]] }, normal, dirs[leFirst], dirs[leLast], edgeVecs)
                spaces.add(sp)
                for (lc in fan) { normal.copyInto(out, cCorner[lc] * 3); spaceOf[cCorner[lc]] = spaces.size - 1 }
                visitedCount += fan.size
                if (visitedCount >= m) break
                for (lc in fan) visited[lc] = true
                start = visited.indexOfFirst { !it }
            }
        }
        return Fans(out, spaceOf, spaces)
    }

    fun customSet(co: FloatArray, corners: IntArray, fStart: IntArray, fSize: IntArray, sharpFaces: BooleanArray?, sharpIn: Set<Long>, custom: FloatArray, useVertices: Boolean): Pair<ShortArray, Set<Long>> {
        val nC = corners.size
        val sharp = HashSet(sharpIn)
        var fans = computeFans(co, corners, fStart, fSize, sharpFaces, sharp)
        val cornerFace = IntArray(nC)
        for (f in fStart.indices) for (k in 0 until fSize[f]) cornerFace[fStart[f] + k] = f
        fun nidx(c: Int) = if (useVertices) corners[c] else c
        if (useVertices) {
            val vn = MeshTopology(corners, fStart, fSize, IntArray(0)).vertexNormals(co)
            for (v in 0 until co.size / 3) if (custom[v * 3] == 0f && custom[v * 3 + 1] == 0f && custom[v * 3 + 2] == 0f) for (k in 0 until 3) custom[v * 3 + k] = vn[v * 3 + k]
        } else {
            for (c in 0 until nC) if (custom[c * 3] == 0f && custom[c * 3 + 1] == 0f && custom[c * 3 + 2] == 0f) for (k in 0 until 3) custom[c * 3 + k] = fans.normals[c * 3 + k]
            fun edgeOf(c: Int): Long { val f = cornerFace[c]; val k = c - fStart[f]; return BlendMeshData.key(corners[c], corners[fStart[f] + (k + 1) % fSize[f]]) }
            fun prevCorner(c: Int): Int { val f = cornerFace[c]; val k = c - fStart[f]; return fStart[f] + (k + fSize[f] - 1) % fSize[f] }
            fun nor(c: Int) = floatArrayOf(custom[c * 3], custom[c * 3 + 1], custom[c * 3 + 2])
            val done = BooleanArray(nC)
            for (i in 0 until nC) {
                if (fans.spaceOf[i] < 0) { done[i] = true; continue }
                if (done[i]) continue
                val fc = fans.spaces[fans.spaceOf[i]].corners
                if (fc.isEmpty()) { done[i] = true; continue }
                var prev = -1
                var org: FloatArray? = null
                for (j in fc.indices.reversed()) {
                    val c = fc[j]
                    val nn = nor(c)
                    if (org == null) org = nn
                    else if (dot(org, nn) < TRIGO) {
                        val e = edgeOf(c); val ePrev = edgeOf(prevCorner(c)); val pe = edgeOf(prev)
                        sharp.add(if (pe == ePrev) pe else e)
                        org = nn
                    }
                    prev = c
                    done[c] = true
                }
                if (fc.size > 1 && org != null) {
                    val c = fc.last()
                    if (dot(org, nor(c)) < TRIGO) {
                        val e = edgeOf(c); val ePrev = edgeOf(prevCorner(c)); val pe = edgeOf(prev)
                        sharp.add(if (pe == ePrev) pe else e)
                    }
                }
            }
            fans = computeFans(co, corners, fStart, fSize, sharpFaces, sharp)
        }
        val data = ShortArray(nC * 2)
        val handled = BooleanArray(nC)
        for (i in 0 until nC) {
            val si = fans.spaceOf[i]
            if (si < 0 || handled[i]) continue
            val sp = fans.spaces[si]
            if (sp.corners.size < 2) {
                val n = nidx(i)
                val r = encode(sp, floatArrayOf(custom[n * 3], custom[n * 3 + 1], custom[n * 3 + 2]))
                data[i * 2] = r[0]; data[i * 2 + 1] = r[1]
                handled[i] = true
            } else {
                val avg = FloatArray(3)
                for (c in sp.corners) { val n = nidx(c); for (k in 0 until 3) avg[k] += custom[n * 3 + k] }
                val inv = 1.0f / sp.corners.size.toFloat()
                for (k in 0 until 3) avg[k] *= inv
                val r = encode(sp, avg)
                for (c in sp.corners) { data[c * 2] = r[0]; data[c * 2 + 1] = r[1]; handled[c] = true }
            }
        }
        return data to sharp
    }
}
