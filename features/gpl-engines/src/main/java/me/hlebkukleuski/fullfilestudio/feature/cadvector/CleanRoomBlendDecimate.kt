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
 * Copyright (C) 2001-2002 NaN Holding BV.
 * Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.abs
import kotlin.math.sqrt

internal object BlendDecimate {
    private const val TOPOLOGY_FALLBACK_EPS = 1e-12f
    private const val BOUNDARY_PRESERVE_WEIGHT = 100.0f
    private const val OPTIMIZE_EPS = 1e-8
    private const val COST_INVALID = Float.MAX_VALUE
    private const val FLT_EPSILON = 1.1920929E-7f

    class Quadric {
        var a2 = 0.0; var ab = 0.0; var ac = 0.0; var ad = 0.0; var b2 = 0.0; var bc = 0.0; var bd = 0.0; var c2 = 0.0; var cd = 0.0; var d2 = 0.0

        fun fromPlane(v: DoubleArray) {
            a2 = v[0] * v[0]; b2 = v[1] * v[1]; c2 = v[2] * v[2]
            ab = v[0] * v[1]; ac = v[0] * v[2]; bc = v[1] * v[2]
            ad = v[0] * v[3]; bd = v[1] * v[3]; cd = v[2] * v[3]; d2 = v[3] * v[3]
        }

        fun add(o: Quadric) { a2 += o.a2; ab += o.ab; ac += o.ac; ad += o.ad; b2 += o.b2; bc += o.bc; bd += o.bd; c2 += o.c2; cd += o.cd; d2 += o.d2 }

        fun mul(s: Double) { a2 *= s; ab *= s; ac *= s; ad *= s; b2 *= s; bc *= s; bd *= s; c2 *= s; cd *= s; d2 *= s }

        fun evaluate(v: DoubleArray): Double {
            val v00 = v[0] * v[0]; val v01 = v[0] * v[1]; val v02 = v[0] * v[2]
            val v11 = v[1] * v[1]; val v12 = v[1] * v[2]
            val v22 = v[2] * v[2]
            return ((a2 * v00) + (ab * 2 * v01) + (ac * 2 * v02) + (ad * 2 * v[0]) +
                (b2 * v11) + (bc * 2 * v12) + (bd * 2 * v[1]) +
                (c2 * v22) + (cd * 2 * v[2]) +
                (d2))
        }

        fun optimize(out: DoubleArray, eps: Double): Boolean {
            val det = (a2 * (b2 * c2 - bc * bc) - ab * (ab * c2 - ac * bc) + ac * (ab * bc - ac * b2))
            if (abs(det) > eps) {
                val inv = 1.0 / det
                val m00 = (b2 * c2 - bc * bc) * inv
                val m10 = (bc * ac - ab * c2) * inv
                val m20 = (ab * bc - b2 * ac) * inv
                val m01 = (ac * bc - ab * c2) * inv
                val m11 = (a2 * c2 - ac * ac) * inv
                val m21 = (ab * ac - a2 * bc) * inv
                val m02 = (ab * bc - ac * b2) * inv
                val m12 = (ac * ab - a2 * bc) * inv
                val m22 = (a2 * b2 - ab * ab) * inv
                val t0 = ad; val t1 = bd; val t2 = cd
                out[0] = -(m00 * t0 + m10 * t1 + m20 * t2)
                out[1] = -(m01 * t0 + m11 * t1 + m21 * t2)
                out[2] = -(m02 * t0 + m12 * t1 + m22 * t2)
                return true
            }
            return false
        }

        fun copyFrom(o: Quadric) { a2 = o.a2; ab = o.ab; ac = o.ac; ad = o.ad; b2 = o.b2; bc = o.bc; bd = o.bd; c2 = o.c2; cd = o.cd; d2 = o.d2 }
    }

    private fun interpColor(a: Int, wa: Float, b: Int, wb: Float): Int {
        fun ch(x: Int, s: Int) = (x ushr s) and 0xFF
        fun q(v: Float): Int { val r = (v + 0.5f); return if (r <= 0f) 0 else if (r >= 255f) 255 else r.toInt() }
        var out = 0
        for (s in intArrayOf(24, 16, 8, 0)) out = out or (q(ch(a, s) * wa + ch(b, s) * wb) shl s)
        return out
    }

    private fun interpDvert(a: List<Pair<Int, Float>>, wa: Float, b: List<Pair<Int, Float>>, wb: Float): List<Pair<Int, Float>> {
        val nodes = ArrayList<FloatArray>()
        for ((src, iw) in listOf(a to wa, b to wb)) for ((g, x) in src) {
            val w = x * iw
            if (w == 0.0f) continue
            val n = nodes.firstOrNull { it[0].toInt() == g }
            if (n != null) n[1] += w else nodes.add(0, floatArrayOf(g.toFloat(), w))
        }
        return nodes.map { it[0].toInt() to minOf(it[1], 1.0f) }
    }

    class Context(val bm: BMesh) {
        lateinit var quadrics: Array<Quadric>
        lateinit var table: Array<BlendHeap.Node?>
        val heap = BlendHeap()
        lateinit var edgeById: HashMap<Int, BMesh.Edge>
        var vweights: FloatArray? = null
        var vweightFactor = 0f
        var symmetry: IntArray? = null
        var doVert = false
        var doEdge = false
        var doLoop = false
        lateinit var edgeByIndex: Array<BMesh.Edge?>
    }

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun lenSq(a: FloatArray) = dot(a, a)

    private fun buildQuadrics(bm: BMesh, q: Array<Quadric>) {
        for (f in bm.faces()) {
            val c = FloatArray(3)
            val ls = BMesh.loopsOfFace(f)
            for (l in ls) { c[0] += l.v.co[0]; c[1] += l.v.co[1]; c[2] += l.v.co[2] }
            val inv = 1.0f / f.len.toFloat()
            c[0] *= inv; c[1] *= inv; c[2] *= inv
            val plane = doubleArrayOf(f.no[0].toDouble(), f.no[1].toDouble(), f.no[2].toDouble(), 0.0)
            plane[3] = -(plane[0] * c[0] + plane[1] * c[1] + plane[2] * c[2])
            val fq = Quadric().also { it.fromPlane(plane) }
            for (l in ls) q[l.v.index].add(fq)
        }
        for (e in bm.edges()) {
            if (BMesh.isBoundary(e)) {
                val ev = sub(e.v2.co, e.v1.co)
                val f = e.l!!.f
                val ep = cross(ev, f.no)
                val pd = doubleArrayOf(ep[0].toDouble(), ep[1].toDouble(), ep[2].toDouble(), 0.0)
                val dd = pd[0] * pd[0] + pd[1] * pd[1] + pd[2] * pd[2]
                var len = 0.0
                if (dd > 1.0e-35) { len = sqrt(dd); pd[0] *= 1.0 / len; pd[1] *= 1.0 / len; pd[2] *= 1.0 / len } else { pd[0] = 0.0; pd[1] = 0.0; pd[2] = 0.0 }
                if (len > FLT_EPSILON.toDouble()) {
                    val c = floatArrayOf((e.v1.co[0] + e.v2.co[0]) * 0.5f, (e.v1.co[1] + e.v2.co[1]) * 0.5f, (e.v1.co[2] + e.v2.co[2]) * 0.5f)
                    pd[3] = -(pd[0] * c[0] + pd[1] * c[1] + pd[2] * c[2])
                    val eq = Quadric().also { it.fromPlane(pd); it.mul(BOUNDARY_PRESERVE_WEIGHT.toDouble()) }
                    q[e.v1.index].add(eq)
                    q[e.v2.index].add(eq)
                }
            }
        }
    }

    private fun targetDb(e: BMesh.Edge, q: Array<Quadric>, out: DoubleArray) {
        val s = Quadric().also { it.copyFrom(q[e.v1.index]); it.add(q[e.v2.index]) }
        if (s.optimize(out, OPTIMIZE_EPS)) return
        out[0] = 0.5 * (e.v1.co[0].toDouble() + e.v2.co[0].toDouble())
        out[1] = 0.5 * (e.v1.co[1].toDouble() + e.v2.co[1].toDouble())
        out[2] = 0.5 * (e.v1.co[2].toDouble() + e.v2.co[2].toDouble())
    }

    private fun targetFl(e: BMesh.Edge, q: Array<Quadric>): FloatArray {
        val d = DoubleArray(3)
        targetDb(e, q, d)
        return floatArrayOf(d[0].toFloat(), d[1].toFloat(), d[2].toFloat())
    }

    private fun isDegenerateFlip(e: BMesh.Edge, opt: FloatArray): Boolean {
        for (v in arrayOf(e.v1, e.v2)) {
            for (l in BMesh.loopsOfVert(v)) {
                if (l.e !== e && l.prev!!.e !== e) {
                    val coPrev = l.prev!!.v.co
                    val coNext = l.next!!.v.co
                    val vecOther = sub(coPrev, coNext)
                    val vecExist = sub(coPrev, v.co)
                    val vecOptim = sub(coPrev, opt)
                    val ce = cross(vecOther, vecExist)
                    val co = cross(vecOther, vecOptim)
                    if (dot(ce, co) <= (lenSq(ce) + lenSq(co)) * 0.01f) return true
                }
            }
        }
        return false
    }

    private fun lenV3(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return sqrt(x * x + y * y + z * z) }
    private fun lenSqV3(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }

    private fun buildCostSingle(c: Context, e: BMesh.Edge) {
        val vw = c.vweights
        var clear = false
        if (vw != null && (vw[e.v1.index] == 0.0f || vw[e.v2.index] == 0.0f)) clear = true
        else if (BMesh.isBoundary(e)) { if (e.l!!.f.len != 3) clear = true }
        else if (BMesh.isManifold(e)) { if (!(e.l!!.f.len == 3 && e.l!!.rNext!!.f.len == 3)) clear = true }
        else clear = true
        if (clear) {
            val n = c.table[e.index]
            if (n != null) c.heap.remove(n)
            c.table[e.index] = null
            return
        }
        val opt = DoubleArray(3)
        targetDb(e, c.quadrics, opt)
        var cost = (c.quadrics[e.v1.index].evaluate(opt) + c.quadrics[e.v2.index].evaluate(opt)).toFloat()
        cost = abs(cost)
        if (cost < TOPOLOGY_FALLBACK_EPS) {
            if (vw == null) {
                cost = abs(dot(e.v1.no, e.v2.no)) / minOf(-lenSqV3(e.v1.co, e.v2.co), -FLT_EPSILON) - cost
            } else {
                val ew = vw[e.v1.index] + vw[e.v2.index]
                cost = abs(dot(e.v1.no, e.v2.no)) / minOf(-lenV3(e.v1.co, e.v2.co), -FLT_EPSILON) - cost
                if (ew != 0f) cost *= 1.0f + (ew * c.vweightFactor)
            }
        } else if (vw != null) {
            val ew = 2.0f - (vw[e.v1.index] + vw[e.v2.index])
            if (ew != 0f) cost += (lenV3(e.v1.co, e.v2.co) * (ew * c.vweightFactor))
        }
        val n = c.table[e.index]
        if (n == null) c.table[e.index] = c.heap.insert(cost, e.id) else c.heap.update(n, cost, e.id)
    }

    private fun invalidCost(c: Context, e: BMesh.Edge) {
        c.table[e.index] = c.heap.insert(COST_INVALID, e.id)
    }

    private fun triangulateBegin(bm: BMesh): Pair<Boolean, Int> {
        for (f in bm.faces()) for (l in BMesh.loopsOfFace(f)) l.index = -1
        var hasCut = false
        var edgesTri = 0
        val facesDouble = ArrayList<BMesh.Face>()
        var slot = 0
        while (slot < bm.faceSlotCount()) {
            val f = bm.faceAt(slot)
            slot++
            if (f != null && f.len > 3) {
                val fIndex = f.index
                val newFaces = ArrayList<BMesh.Face>()
                val newEdges = ArrayList<BMesh.Edge>()
                bm.faceTriangulateBeauty(f, newFaces, newEdges, facesDouble)
                for (e in newEdges) {
                    val first = e.l!!
                    var l = first
                    do { l.index = fIndex; hasCut = true; l = l.rNext!! } while (l !== first)
                }
                for (nf in newFaces) BMesh.faceCalcNormal(nf, nf.no)
                edgesTri += newEdges.size
            }
        }
        for (f in facesDouble) if (f.alive) bm.faceKill(f)
        if (hasCut) {
            for ((i, e) in bm.edges().withIndex()) e.index = i
            for ((i, f) in bm.faces().withIndex()) f.index = i
        }
        return hasCut to edgesTri
    }

    private fun isQuadConvex(v1: FloatArray, v2: FloatArray, v3: FloatArray, v4: FloatArray): Boolean {
        val plane = cross(sub(v1, v3), sub(v2, v4))
        val eps = 1e-8f * 1e-8f
        if (lenSq(plane) < eps) return false
        fun proj(v: FloatArray): FloatArray {
            val m = dot(v, plane) / dot(plane, plane)
            return floatArrayOf(v[0] + plane[0] * -m, v[1] + plane[1] * -m, v[2] + plane[2] * -m)
        }
        val p = arrayOf(proj(v1), proj(v2), proj(v3), proj(v4))
        val dir = Array(4) { sub(p[it], p[(it + 3) % 4]) }
        fun cs(a: FloatArray, b: FloatArray) = dot(plane, cross(a, b)) > 0.0f
        return cs(dir[0], dir[1]) && cs(dir[1], dir[2]) && cs(dir[2], dir[3]) && cs(dir[3], dir[0])
    }

    private fun triangulateEnd(bm: BMesh) {
        val list = ArrayList<BMesh.Edge>()
        for (e in bm.edges()) {
            val pair = BMesh.edgeLoopPair(e) ?: continue
            val la = pair.first; val lb = pair.second
            val ia = la.index
            if (ia == -1) continue
            if (ia != lb.index) continue
            if (la.v === lb.v) continue
            fun canMerge(l: BMesh.Loop) = BMesh.loopIsManifold(l) && l.v !== l.rNext!!.v && ia == l.index && ia == l.rNext!!.index
            if (la.f.len == 3 && lb.f.len == 3 && !canMerge(la.next!!) && !canMerge(la.prev!!) && !canMerge(lb.next!!) && !canMerge(lb.prev!!)) {
                val q1 = if (BMesh.vertInEdge(e, la.next!!.v)) la.prev!!.v else la.next!!.v
                val q3 = if (BMesh.vertInEdge(e, lb.next!!.v)) lb.prev!!.v else lb.next!!.v
                if (!isQuadConvex(e.v1.co, q1.co, e.v2.co, q3.co)) continue
            }
            list.add(e)
        }
        for (e in list) {
            if (!e.alive) continue
            val pair = BMesh.edgeLoopPair(e) ?: continue
            bm.facesJoin(listOf(pair.first.f, pair.second.f), false, true)
            if (e.l == null) bm.edgeKill(e)
        }
    }

    private fun collapseLoopCustomdata(c: Context, l: BMesh.Loop, vClear: BMesh.Vert, fac: Float) {
        val isManifold = BMesh.isManifold(l.e!!)
        val lClear: BMesh.Loop
        val lOther: BMesh.Loop
        if (l.v === vClear) { lClear = l; lOther = l.next!! } else { lClear = l.next!!; lOther = l }
        for (side in 0 until 2) {
            val fExit = if (isManifold) l.rNext!!.f else null
            val eStep = arrayOf<BMesh.Edge?>(l.e)
            val lFirst: BMesh.Loop
            val s0u: Float; val s0w: Float; val s0c: Int
            val s1u: Float; val s1w: Float; val s1c: Int
            val w0: Float; val w1: Float
            if (side == 0) {
                lFirst = lClear
                s0u = lClear.u; s0w = lClear.w; s0c = lClear.color
                s1u = lOther.u; s1w = lOther.w; s1c = lOther.color
                w0 = fac; w1 = 1.0f - fac
            } else {
                lFirst = lOther
                s0u = lOther.u; s0w = lOther.w; s0c = lOther.color
                s1u = lClear.u; s1w = lClear.w; s1c = lClear.color
                w0 = 1.0f - fac; w1 = fac
            }
            var lIter: BMesh.Loop = lFirst
            while (true) {
                val nx = BMesh.vertStepFanLoop(lIter, eStep) ?: break
                if (nx === lFirst) break
                lIter = nx
                if (fExit != null && fExit === lIter.f) break
                if (c.bm.hasUv && (s0u - lIter.u) * (s0u - lIter.u) + (s0w - lIter.w) * (s0w - lIter.w) < 0.00001f) {
                    lIter.u = s0u * w0 + s1u * w1
                    lIter.w = s0w * w0 + s1w * w1
                }
                if (c.bm.hasCornerColor && lIter.color == s0c) lIter.color = interpColor(s0c, w0, s1c, w1)
            }
        }
    }

    private fun tagEnable(e: BMesh.Edge, on: Boolean) {
        e.v1.tag = on; e.v2.tag = on
        val l = e.l
        if (l != null) { l.f.tag = on; if (l !== l.rNext) l.rNext!!.f.tag = on }
    }

    private fun tagTest(e: BMesh.Edge): Boolean {
        if (e.v1.tag || e.v2.tag) return true
        val l = e.l ?: return false
        return l.f.tag || (l !== l.rNext && l.rNext!!.f.tag)
    }

    private fun isDegenerateTopology(eFirst: BMesh.Edge): Boolean {
        fun manOrBound(l: BMesh.Loop?) = l != null && l.rNext!!.rNext === l
        for (v in arrayOf(eFirst.v1, eFirst.v2)) {
            var e = eFirst
            do {
                if (!manOrBound(e.l)) return true
                tagEnable(e, false)
                e = BMesh.diskNext(e, v)!!
            } while (e !== eFirst)
        }
        var e = eFirst
        do { tagEnable(e, true); e = BMesh.diskNext(e, eFirst.v1)!! } while (e !== eFirst)
        val lr = eFirst.l!!
        var lf = lr
        lf.f.tag = false
        lf.v.tag = false
        lf = lf.next!!; lf.v.tag = false
        lf.next!!.v.tag = false
        val lo = lr.rNext!!
        if (lr !== lo) {
            lo.f.tag = false
            var lf2 = lo
            lf2.v.tag = false
            lf2 = lf2.next!!; lf2.v.tag = false
            lf2.next!!.v.tag = false
        }
        e = eFirst
        do { if (tagTest(e)) return true; e = BMesh.diskNext(e, eFirst.v2)!! } while (e !== eFirst)
        return false
    }

    private fun edgeCollapse(c: Context, eClear: BMesh.Edge, vClear: BMesh.Vert, out: IntArray, fac: Float): Boolean {
        val bm = c.bm
        val vOther = BMesh.otherVert(eClear, vClear)!!
        val sym = c.symmetry
        if (BMesh.isManifold(eClear)) {
            val (la, lb) = BMesh.edgeLoopPair(eClear)!!
            val ea = arrayOfNulls<BMesh.Edge>(2)
            val eb = arrayOfNulls<BMesh.Edge>(2)
            if (BMesh.vertInEdge(la.prev!!.e!!, vClear)) { ea[0] = la.prev!!.e; ea[1] = la.next!!.e } else { ea[1] = la.prev!!.e; ea[0] = la.next!!.e }
            if (BMesh.vertInEdge(lb.prev!!.e!!, vClear)) { eb[0] = lb.prev!!.e; eb[1] = lb.next!!.e } else { eb[1] = lb.prev!!.e; eb[0] = lb.next!!.e }
            if (ea[0] === eb[0] || ea[0] === eb[1] || ea[1] === eb[0] || ea[1] === eb[1]) return false
            out[0] = ea[0]!!.index
            out[1] = eb[0]!!.index
            if (c.doVert) interpVerts(bm, vOther, vClear, fac)
            if (c.doEdge) { interpEdges(ea[1]!!, ea[0]!!, fac); interpEdges(eb[1]!!, eb[0]!!, fac) }
            if (c.doLoop) {
                collapseLoopCustomdata(c, eClear.l!!, vClear, fac)
                collapseLoopCustomdata(c, eClear.l!!.rNext!!, vClear, fac)
            }
            bm.edgeKill(eClear)
            vOther.tag = vOther.tag || vClear.tag
            bm.vertSplice(vOther, vClear)
            mergeEdgeFlags(ea[1]!!, ea[0]!!)
            mergeEdgeFlags(eb[1]!!, eb[0]!!)
            bm.edgeSplice(ea[1]!!, ea[0]!!)
            bm.edgeSplice(eb[1]!!, eb[0]!!)
            if (sym != null) {
                if (sym[out[0]] != -1) sym[sym[out[0]]] = ea[1]!!.index
                if (sym[out[1]] != -1) sym[sym[out[1]]] = eb[1]!!.index
            }
            return true
        }
        if (BMesh.isBoundary(eClear)) {
            val la = eClear.l!!
            val ea = arrayOfNulls<BMesh.Edge>(2)
            if (BMesh.vertInEdge(la.prev!!.e!!, vClear)) { ea[0] = la.prev!!.e; ea[1] = la.next!!.e } else { ea[1] = la.prev!!.e; ea[0] = la.next!!.e }
            out[0] = ea[0]!!.index
            out[1] = -1
            if (c.doVert) interpVerts(bm, vOther, vClear, fac)
            if (c.doEdge) interpEdges(ea[1]!!, ea[0]!!, fac)
            if (c.doLoop) collapseLoopCustomdata(c, eClear.l!!, vClear, fac)
            bm.edgeKill(eClear)
            vOther.tag = vOther.tag || vClear.tag
            bm.vertSplice(vOther, vClear)
            mergeEdgeFlags(ea[1]!!, ea[0]!!)
            bm.edgeSplice(ea[1]!!, ea[0]!!)
            if (sym != null && sym[out[0]] != -1) sym[sym[out[0]]] = ea[1]!!.index
            return true
        }
        return false
    }

    private fun mergeEdgeFlags(dst: BMesh.Edge, src: BMesh.Edge) {
        dst.smooth = dst.smooth || src.smooth
        dst.seam = dst.seam || src.seam
        dst.tag = dst.tag || src.tag
    }

    private fun interpVerts(bm: BMesh, dst: BMesh.Vert, src: BMesh.Vert, fac: Float) {
        val w0 = 1.0f - fac
        if (bm.hasPointColor) dst.color = interpColor(dst.color, w0, src.color, fac)
        if (bm.hasVertCrease) dst.crease = dst.crease * w0 + src.crease * fac
        if (bm.hasDvert) dst.dvert = interpDvert(dst.dvert, w0, src.dvert, fac)
    }

    private fun interpEdges(dst: BMesh.Edge, src: BMesh.Edge, fac: Float) {
        dst.crease = dst.crease * (1.0f - fac) + src.crease * fac
    }

    private fun edgeCollapseDecim(c: Context, e: BMesh.Edge, opt: FloatArray, optCalc: Boolean): Boolean {
        val vOther = e.v1
        val vOtherIndex = e.v1.index
        val vClearIndex = e.v2.index
        val vClearNo = e.v2.no.copyOf()
        if (optCalc) {
            if (isDegenerateTopology(e)) { invalidCost(c, e); return false }
            targetFl(e, c.quadrics).copyInto(opt)
            if (isDegenerateFlip(e, opt)) { invalidCost(c, e); return false }
        }
        val same = abs(e.v1.co[0] - e.v2.co[0]) <= FLT_EPSILON && abs(e.v1.co[1] - e.v2.co[1]) <= FLT_EPSILON && abs(e.v1.co[2] - e.v2.co[2]) <= FLT_EPSILON
        val fac = if (!same) {
            val u = sub(e.v2.co, e.v1.co)
            val h = sub(opt, e.v1.co)
            val d = lenSq(u)
            if (d > 0.0f) dot(u, h) / d else 0.0f
        } else 0.5f
        val clearOther = IntArray(2)
        if (edgeCollapse(c, e, e.v2, clearOther, fac)) {
            val vw = c.vweights
            if (vw != null) vw[vOtherIndex] = (fac * vw[vOtherIndex] + (1.0f - fac) * vw[vClearIndex]).coerceIn(0.0f, 1.0f)
            opt.copyInto(vOther.co)
            for (i in 0 until 2) {
                val idx = clearOther[i]
                if (idx != -1) { val n = c.table[idx]; if (n != null) { c.heap.remove(n); c.table[idx] = null } }
            }
            c.quadrics[vOtherIndex].add(c.quadrics[vClearIndex])
            val s = 1.0f - fac
            for (k in 0 until 3) vOther.no[k] = s * vOther.no[k] + fac * vClearNo[k]
            BMesh.normalizeV3(vOther.no)
            val first = vOther.e
            if (first != null) {
                var it: BMesh.Edge = first
                do { buildCostSingle(c, it); it = BMesh.diskNext(it, vOther)!! } while (it !== first)
            }
            for (l in BMesh.loopsOfVert(vOther)) {
                if (l.f.len == 3) {
                    val outer = if (BMesh.vertInEdge(l.prev!!.e!!, l.v)) l.next!!.e!! else l.prev!!.e!!
                    buildCostSingle(c, outer)
                }
            }
            return true
        }
        invalidCost(c, e)
        return false
    }

    private fun symmetryMap(bm: BMesh, axis: Int, limit: Float): IntArray {
        val es = bm.edges()
        val map = IntArray(es.size) { -1 }
        val limitSq = limit * limit
        val cell = HashMap<Long, ArrayList<Int>>()
        val size = maxOf(limit * 4f, 1e-6f)
        fun key(x: Float, y: Float, z: Float): Long {
            val a = Math.floor((x / size).toDouble()).toLong(); val b = Math.floor((y / size).toDouble()).toLong(); val c = Math.floor((z / size).toDouble()).toLong()
            return (a * 73856093L) xor (b * 19349663L) xor (c * 83492791L)
        }
        val mids = Array(es.size) { i -> val e = es[i]; floatArrayOf((e.v1.co[0] + e.v2.co[0]) * 0.5f, (e.v1.co[1] + e.v2.co[1]) * 0.5f, (e.v1.co[2] + e.v2.co[2]) * 0.5f) }
        for (i in es.indices) cell.getOrPut(key(mids[i][0], mids[i][1], mids[i][2])) { ArrayList() }.add(i)
        for (i in es.indices) {
            if (map[i] != -1) continue
            val e = es[i]
            val co = mids[i].copyOf(); co[axis] *= -1.0f
            val v1 = e.v1.co.copyOf(); v1[axis] *= -1.0f
            val v2 = e.v2.co.copyOf(); v2[axis] *= -1.0f
            val dir = sub(v2, v1)
            var found = -1
            val cands = ArrayList<Int>()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) cell[key(co[0] + dx * size, co[1] + dy * size, co[2] + dz * size)]?.let { cands.addAll(it) }
            for (j in cands.distinct().sorted()) {
                if (lenSqV3(mids[j], co) > limitSq) continue
                val o = es[j]
                val od = sub(o.v2.co, o.v1.co)
                val bad = if (dot(od, dir) > 0.0f) lenSqV3(v1, o.v1.co) > limitSq || lenSqV3(v2, o.v2.co) > limitSq
                else lenSqV3(v1, o.v2.co) > limitSq || lenSqV3(v2, o.v1.co) > limitSq
                if (!bad) { found = j; break }
            }
            if (found != -1) { map[i] = found; map[found] = i }
        }
        return map
    }

    private const val DELIM_NORMAL = 1
    private const val DELIM_MATERIAL = 2
    private const val DELIM_SEAM = 4
    private const val DELIM_SHARP = 8
    private const val DELIM_UV = 16

    private fun safeAcosAngle(a: FloatArray, b: FloatArray): Float {
        val x: Float; val y: Float; val z: Float
        if (dot(a, b) >= 0f) {
            x = a[0] - b[0]; y = a[1] - b[1]; z = a[2] - b[2]
            return 2.0f * asinSafe(sqrt(x * x + y * y + z * z) / 2.0f)
        }
        x = a[0] + b[0]; y = a[1] + b[1]; z = a[2] + b[2]
        return Math.PI.toFloat() - 2.0f * asinSafe(sqrt(x * x + y * y + z * z) / 2.0f)
    }

    private fun asinSafe(x: Float): Float = if (x <= -1f) (-Math.PI / 2).toFloat() else if (x >= 1f) (Math.PI / 2).toFloat() else kotlin.math.asin(x)

    private fun isContiguous(e: BMesh.Edge): Boolean {
        val l = e.l ?: return false
        val o = l.rNext!!
        return o !== l && o.rNext === l && o.v !== l.v
    }

    private fun uvContiguous(e: BMesh.Edge): Boolean {
        val h = e.l ?: return true
        if (h.rNext === h) return true
        val b1 = h; val b2 = h.next!!
        var it = h.rNext!!
        do {
            val i1: BMesh.Loop; val i2: BMesh.Loop
            if (it.v === b1.v) { i1 = it; i2 = it.next!! } else { i1 = it.next!!; i2 = it }
            fun eq(a: BMesh.Loop, b: BMesh.Loop) = (a.u - b.u) * (a.u - b.u) + (a.w - b.w) * (a.w - b.w) < 0.00001f
            if (!eq(b1, i1) || !eq(b2, i2)) return false
            it = it.rNext!!
        } while (it !== h)
        return true
    }

    private fun isDelimiter(bm: BMesh, e: BMesh.Edge, delimit: Int): Boolean {
        if (delimit == 0) return false
        if (delimit and DELIM_SEAM != 0 && e.seam) return true
        if (delimit and DELIM_SHARP != 0 && !e.smooth) return true
        if (delimit and DELIM_MATERIAL != 0 && e.l!!.f.mat != e.l!!.rNext!!.f.mat) return true
        if (delimit and DELIM_NORMAL != 0 && !isContiguous(e)) return true
        if (delimit and DELIM_UV != 0 && bm.hasUv && !uvContiguous(e)) return true
        return false
    }

    private fun vertIsDelimiter(bm: BMesh, v: BMesh.Vert, delimit: Int): Boolean {
        if (delimit == 0) return false
        val first = v.e!!
        var e = first
        do { if (BMesh.isManifold(e) && isDelimiter(bm, e, delimit)) return true; e = BMesh.diskNext(e, v)!! } while (e !== first)
        return false
    }

    private fun angle3(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val v1 = sub(b, a); val v2 = sub(b, c)
        BMesh.normalizeV3(v1); BMesh.normalizeV3(v2)
        return safeAcosAngle(v1, v2)
    }

    private fun vertEdgeAngle(v: BMesh.Vert): Float {
        val e1 = v.e ?: return (Math.PI / 2).toFloat()
        val e2 = BMesh.diskNext(e1, v)!!
        if (e1 !== e2 && e1 === BMesh.diskNext(e2, v)) {
            val v1 = BMesh.otherVert(e1, v)!!; val v2 = BMesh.otherVert(e2, v)!!
            return Math.PI.toFloat() - angle3(v1.co, v.co, v2.co)
        }
        return (Math.PI / 2).toFloat()
    }

    private fun vertEdgeFaceAngle(bm: BMesh, v: BMesh.Vert, delimit: Int): Float {
        val unit = (90.0 * Math.PI / 180.0).toFloat()
        val toUnit = 1.0f / unit
        val angle = vertEdgeAngle(v)
        val e = v.e
        if (e != null && BMesh.isManifold(e)) {
            if (!vertIsDelimiter(bm, v, delimit)) {
                val fa = safeAcosAngle(e.l!!.f.no, e.l!!.rNext!!.f.no)
                return ((angle * toUnit) * (fa * toUnit)) * unit
            }
        }
        return angle
    }

    private fun dissolveError(bm: BMesh, e: BMesh.Edge, delimit: Int): Float {
        if (BMesh.isManifold(e) && !isDelimiter(bm, e, delimit)) {
            var c = dot(e.l!!.f.no, e.l!!.rNext!!.f.no)
            if (isContiguous(e)) c *= -1f
            return c
        }
        return COST_INVALID
    }

    private fun cross2(a: FloatArray, b: FloatArray, c: FloatArray) = (a[0] - b[0]) * (b[1] - c[1]) + (a[1] - b[1]) * (c[0] - b[0])
    private fun side2(l1: FloatArray, l2: FloatArray, p: FloatArray) = ((l1[0] - p[0]) * (l2[1] - p[1])) - ((l2[0] - p[0]) * (l1[1] - p[1]))
    private fun signum(a: Float) = if (a == 0.0f) 0 else if (a > 0.0f) 1 else -1

    private fun axisBasis(n: FloatArray): Array<FloatArray> {
        val b1 = FloatArray(3); val b2 = FloatArray(3)
        val f = n[0] * n[0] + n[1] * n[1]
        if (f > FLT_EPSILON) {
            val d = 1.0f / sqrt(f)
            b1[0] = n[1] * d; b1[1] = -n[0] * d; b1[2] = 0.0f
            b2[0] = -n[2] * b1[1]; b2[1] = n[2] * b1[0]; b2[2] = n[0] * b1[1] - n[1] * b1[0]
        } else {
            b1[0] = if (n[2] < 0.0f) -1.0f else 1.0f
            b2[1] = 1.0f
        }
        return arrayOf(b1, b2)
    }

    private fun loopCollapseIsDegenerate(lEar: BMesh.Loop): Boolean {
        val center = lEar.v.co
        val (b1, b2) = axisBasis(lEar.f.no)
        fun proj(a: FloatArray): FloatArray { val c = sub(a, center); return floatArrayOf(b1[0] * c[0] + b1[1] * c[1] + b1[2] * c[2], b2[0] * c[0] + b2[1] * c[1] + b2[2] * c[2]) }
        val t0 = proj(lEar.prev!!.v.co)
        var t1 = floatArrayOf(0f, 0f)
        var t2 = proj(lEar.next!!.v.co)
        fun isEdgePair(v: BMesh.Vert): Boolean { val e = v.e ?: return false; val o = BMesh.diskNext(e, v)!!; return o !== e && BMesh.diskNext(o, v) === e }
        if (!isEdgePair(lEar.prev!!.v)) {
            val adj = proj(lEar.prev!!.prev!!.v.co)
            if (signum(cross2(adj, t0, t1)) != signum(cross2(adj, t0, t2))) return true
        }
        if (!isEdgePair(lEar.next!!.v)) {
            val adj = proj(lEar.next!!.next!!.v.co)
            if (signum(cross2(adj, t2, t1)) != signum(cross2(adj, t2, t0))) return true
        }
        if (cross2(t0, t1, t2) < 0.0f) { val t = t1; t1 = t2; t2 = t }
        var it = lEar.next!!.next!!
        val first = lEar.prev!!
        do {
            val c = proj(it.v.co)
            if (side2(t0, t1, c) >= 0.0f && side2(t1, t2, c) >= 0.0f && side2(t2, t0, c) >= 0.0f) return true
            it = it.next!!
        } while (it !== first)
        return false
    }

    private fun vertCollapseIsDegenerate(v: BMesh.Vert): Boolean {
        val ea = v.e ?: return true
        val eb = BMesh.diskNext(ea, v)!!
        if (!(eb !== ea && BMesh.diskNext(eb, v) === ea)) return true
        if (BMesh.isWire(ea) || BMesh.isWire(eb)) return false
        val p0 = BMesh.otherVert(ea, v)!!; val p1 = BMesh.otherVert(eb, v)!!
        val v1 = sub(v.co, p0.co); val v2 = sub(v.co, p1.co)
        BMesh.normalizeV3(v1); BMesh.normalizeV3(v2)
        if (abs(dot(v1, v2)) < (1.0f - FLT_EPSILON)) {
            val h = eb.l!!
            var l = h
            do {
                if (l.f.len > 3) {
                    val pivot = if (l.v === v) l else l.next!!
                    if (loopCollapseIsDegenerate(pivot)) return true
                }
                l = l.rNext!!
            } while (l !== h)
        }
        return false
    }

    private fun interpVertsFn(bm: BMesh): (BMesh.Vert, BMesh.Vert, Float) -> Unit = { dst, src, fac -> interpVerts(bm, dst, src, fac) }

    fun dissolve(d: BlendMeshData, angleLimit: Float, doBoundaries: Boolean, delimitIn: Int): BlendMeshData {
        val bm = BMesh.fromMesh(d, false, true)
        var delimit = delimitIn
        if (delimit and DELIM_UV != 0 && !bm.hasUv) delimit = delimit and DELIM_UV.inv()
        val limitCosNeg = -kotlin.math.cos(angleLimit)
        val vinput = bm.verts().toMutableList<BMesh.Vert?>()
        val einput = bm.edges()
        val heap = BlendHeap()
        val table = arrayOfNulls<BlendHeap.Node>(if (doBoundaries) einput.size else maxOf(einput.size, vinput.size))
        val wireTag = HashSet<BMesh.Edge>()
        val edgeById = HashMap<Int, BMesh.Edge>()
        for (e in bm.edges()) { if (BMesh.isWire(e)) wireTag.add(e); e.index = -1 }
        for ((i, e) in einput.withIndex()) { table[i] = heap.insert(dissolveError(bm, e, delimit), e.id); e.index = i; edgeById[e.id] = e }
        while (!heap.isEmpty && heap.topValue() < limitCosNeg) {
            val top = heap.topNode()
            val e = edgeById[top.ptr]!!
            val i = e.index
            var fNew: BMesh.Face? = null
            if (BMesh.isManifold(e)) {
                fNew = bm.facesJoinPair(e.l!!, e.l!!.rNext!!, false, true)
                if (fNew != null) {
                    heap.remove(top)
                    table[i] = null
                    BMesh.faceCalcNormal(fNew, fNew.no)
                    for (l in BMesh.loopsOfFace(fNew)) {
                        val j = l.e!!.index
                        val n = if (j != -1) table[j] else null
                        if (n != null) heap.update(n, dissolveError(bm, l.e!!, delimit), n.ptr)
                    }
                }
            }
            if (fNew == null) heap.update(top, COST_INVALID, top.ptr)
        }
        for ((i, v) in bm.verts().withIndex()) v.index = i
        val reverse = IntArray(bm.totvert) { -1 }
        for ((i, v) in vinput.withIndex()) if (v != null && v.alive) reverse[v.index] = i
        val earray = bm.edges()
        for (i in earray.indices.reversed()) {
            val e = earray[i]
            if (!e.alive) continue
            if (BMesh.isWire(e) && e !in wireTag) {
                val v1 = e.v1; val v2 = e.v2
                bm.edgeKill(e)
                if (v1.e == null) { val r = reverse[v1.index]; if (r != -1) vinput[r] = null; bm.vertKill(v1) }
                if (v2.e == null) { val r = reverse[v2.index]; if (r != -1) vinput[r] = null; bm.vertKill(v2) }
            }
        }
        if (doBoundaries) {
            for (v in vinput) {
                if (v == null || !v.alive) continue
                val e = v.e ?: continue
                val o = BMesh.diskNext(e, v)!!
                if (o !== e && BMesh.diskNext(o, v) === e) bm.vertCollapseFaces(e, v, 1.0f, true, false, true, true, interpVertsFn(bm))
            }
        } else {
            for (v in bm.verts()) v.index = -1
            val vheap = BlendHeap()
            val vtable = table
            for (k in vtable.indices) vtable[k] = null
            val vertById = HashMap<Int, BMesh.Vert>()
            for ((i, v) in vinput.withIndex()) {
                if (v != null) {
                    vtable[i] = vheap.insert(vertEdgeFaceAngle(bm, v, delimit), v.id)
                    v.index = i
                    vertById[v.id] = v
                }
            }
            while (!vheap.isEmpty && vheap.topValue() < angleLimit) {
                val top = vheap.topNode()
                val v = vertById[top.ptr]!!
                val i = v.index
                var eNew: BMesh.Edge? = null
                if (!vertCollapseIsDegenerate(v)) {
                    eNew = bm.vertCollapseFaces(v.e!!, v, 1.0f, true, false, true, true, interpVertsFn(bm))
                    if (eNew != null) {
                        vheap.remove(top)
                        vtable[i] = null
                        val h = eNew.l
                        if (h != null) { var l: BMesh.Loop = h; do { BMesh.faceCalcNormal(l.f, l.f.no); l = l.rNext!! } while (l !== h) }
                        for (vi in arrayOf(eNew.v1, eNew.v2)) {
                            val j = vi.index
                            val n = if (j != -1) vtable[j] else null
                            if (n != null) vheap.update(n, vertEdgeFaceAngle(bm, vi, delimit), n.ptr)
                        }
                        if (h != null) {
                            var l: BMesh.Loop = h
                            do {
                                var c = l.next!!.next!!
                                val cf = l.prev!!
                                do {
                                    val j = c.v.index
                                    val n = if (j != -1) vtable[j] else null
                                    if (n != null && n.value == COST_INVALID) vheap.update(n, vertEdgeFaceAngle(bm, c.v, delimit), n.ptr)
                                    c = c.next!!
                                } while (c !== cf)
                                l = l.rNext!!
                            } while (l !== h)
                        }
                    }
                }
                if (eNew == null) vheap.update(top, COST_INVALID, top.ptr)
            }
        }
        return bm.toMesh(d.groups, d)
    }

    private fun dissolveFanTest(bm: BMesh, v: BMesh.Vert): Boolean {
        var tot = 0; var bnd = 0; var man = 0; var wire = 0
        val varr = ArrayList<BMesh.Vert>(4)
        for (e in BMesh.edgesOfVert(v)) {
            if (BMesh.isBoundary(e)) bnd++ else if (BMesh.isManifold(e)) man++ else if (BMesh.isWire(e)) wire++
            if (tot == 4) return false
            varr.add(BMesh.otherVert(e, v)!!)
            tot++
        }
        if ((tot == 4 && bnd == 0 && man == 4) || (tot == 3 && bnd == 0 && man == 3) || (tot == 3 && bnd == 2 && man == 1)) {
            if (bm.faceExists(varr.toTypedArray()) == null) return true
        } else if (tot == 2 && wire == 2) return true
        return false
    }

    private fun dissolveFan(bm: BMesh, v: BMesh.Vert): Boolean {
        var tot = 0; var bnd = 0; var man = 0; var wire = 0
        for (e in BMesh.edgesOfVert(v)) {
            if (BMesh.isBoundary(e)) bnd++ else if (BMesh.isManifold(e)) man++ else if (BMesh.isWire(e)) wire++
            tot++
        }
        var totLoop = 0
        if (tot == 2) {
            if (wire == 2) return bm.vertCollapseFaces(v.e!!, v, 1.0f, true, false, true, true, interpVertsFn(bm)) != null
        } else if (tot == 4) {
            if (bnd == 0 && man == 4) totLoop = 4
        } else if (tot == 3) {
            if (bnd == 2 && man == 1) totLoop = 2 else if (bnd == 0 && man == 3) totLoop = 3
        }
        if (totLoop != 0) {
            val loops = BMesh.loopsOfVert(v).take(totLoop)
            for (l in loops) {
                if (l.f.len > 3) {
                    val lNew = bm.faceSplit(l.f, l.prev!!, l.next!!, null, true)
                    if (lNew != null) {
                        val ne = lNew.e!!
                        ne.smooth = l.e!!.smooth || l.prev!!.e!!.smooth
                        ne.seam = l.e!!.seam || l.prev!!.e!!.seam
                    }
                }
            }
            return bm.vertDissolve(v, interpVertsFn(bm))
        }
        return false
    }

    fun unsubdivide(d: BlendMeshData, iterations: Int): BlendMeshData {
        val bm = BMesh.fromMesh(d, false, false)
        for (v in bm.verts()) v.tag = true
        val collapse = -1; val init = 0; val ignore = 1
        for (step in 0 until iterations) {
            var marked = false
            for (v in bm.verts()) v.index = if (v.tag && dissolveFanTest(bm, v)) init else ignore
            for (v in bm.verts()) {
                if (v.index != init) continue
                v.index = ignore
                var ign = listOf(v)
                while (true) {
                    val col = ArrayList<BMesh.Vert>()
                    for (x in ign) for (e in BMesh.edgesOfVert(x)) { val o = BMesh.otherVert(e, x)!!; if (o.index == init) { o.index = collapse; col.add(o) } }
                    if (col.isEmpty()) break
                    marked = true
                    val ig = ArrayList<BMesh.Vert>()
                    for (x in col) for (e in BMesh.edgesOfVert(x)) { val o = BMesh.otherVert(e, x)!!; if (o.index == init) { o.index = ignore; ig.add(o) } }
                    if (ig.isEmpty()) break
                    ign = ig
                }
            }
            if (!marked) break
            for (v in bm.verts()) if (v.alive && v.index == collapse) dissolveFan(bm, v)
        }
        return bm.toMesh(d.groups, d)
    }

    fun collapse(d: BlendMeshData, factor: Float, vweights: FloatArray?, vweightFactor: Float, doTriangulate: Boolean, symmetryAxis: Int): BlendMeshData {
        val bm = BMesh.fromMesh(d, true, true)
        val (useTri, _) = triangulateBegin(bm)
        val c = Context(bm)
        c.vweights = vweights?.copyOf()
        c.vweightFactor = vweightFactor
        c.quadrics = Array(d.nV) { Quadric() }
        val es = bm.edges()
        c.table = arrayOfNulls(es.size)
        c.edgeById = HashMap()
        for (e in es) c.edgeById[e.id] = e
        buildQuadrics(bm, c.quadrics)
        for ((i, e) in es.withIndex()) { c.table[i] = null; buildCostSingle(c, e) }
        val target = (bm.totface * factor).toInt()
        c.symmetry = if (symmetryAxis != -1) symmetryMap(bm, symmetryAxis, 0.00002f) else null
        c.doVert = bm.hasPointColor || bm.hasVertCrease || bm.hasDvert
        c.doEdge = bm.hasEdgeCrease
        c.doLoop = bm.hasUv || bm.hasCornerColor
        fun topValue(): Float = c.heap.topValue()
        if (c.symmetry == null) {
            while (bm.totface > target && !c.heap.isEmpty && topValue() != COST_INVALID) {
                val e = c.edgeById[c.heap.popMin()]!!
                c.table[e.index] = null
                edgeCollapseDecim(c, e, FloatArray(3), true)
            }
        } else {
            val sym = c.symmetry!!
            while (bm.totface > target && !c.heap.isEmpty && topValue() != COST_INVALID) {
                val e = c.edgeById[c.heap.popMin()]!!
                val ei = e.index
                val em = sym[ei]
                var eMirr: BMesh.Edge? = null
                val opt = FloatArray(3)
                var inval = 0
                c.table[ei] = null
                run body@{
                    if (em != -1) {
                        if (em == ei) {
                        } else if (c.table[em] != null) {
                            eMirr = c.edgeById[c.table[em]!!.ptr]
                            val m = eMirr!!
                            if (e.v1 === m.v1 || e.v1 === m.v2 || e.v2 === m.v1 || e.v2 === m.v2) return@body
                        } else { inval = inval or 1; return@body }
                    }
                    val okA = !isDegenerateTopology(e)
                    val okB = eMirr?.let { !isDegenerateTopology(it) } ?: true
                    if (!okA || !okB) { inval = inval or (1 or (if (eMirr != null) 2 else 0)); return@body }
                    targetFl(e, c.quadrics).copyInto(opt)
                    if (em == ei) opt[symmetryAxis] = 0.0f
                    if (isDegenerateFlip(e, opt)) { inval = inval or (1 or (if (eMirr != null) 2 else 0)); return@body }
                    if (edgeCollapseDecim(c, e, opt, false)) {
                        val m = eMirr
                        if (m != null && c.table[em] != null) {
                            c.heap.remove(c.table[em]!!)
                            c.table[em] = null
                            opt[symmetryAxis] *= -1.0f
                            edgeCollapseDecim(c, m, opt, false)
                        }
                    } else {
                        if (eMirr != null && c.table[em] != null) { inval = inval or 2; return@body }
                    }
                }
                if (inval and 1 != 0) invalidCost(c, e)
                if (inval and 2 != 0) {
                    val n = c.table[em]
                    if (n != null) c.heap.remove(n)
                    c.table[em] = null
                    invalidCost(c, eMirr!!)
                }
            }
        }
        if (!doTriangulate && useTri) triangulateEnd(bm)
        return bm.toMesh(d.groups, d)
    }
}
