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
 *
 * Profile sorting routines (darwinQsort / heapsort) ported and adapted from
 * FreeBSD / UC Berkeley / Apple Darwin Libc stdlib/qsort.c and stdlib/heapsort.c,
 * Copyright (c) 1991, 1992, 1993 The Regents of the University of California. All rights reserved.
 * Derived from software contributed to Berkeley by Ronnie Kon at Mindcraft Inc.,
 * Kevin Lew and Elmer Yglesias; and Jon Bentley and M. Douglas McIlroy (BSD-3-Clause).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import me.hlebkukleuski.fullfilestudio.feature.cadvector.BevelMath.add
import me.hlebkukleuski.fullfilestudio.feature.cadvector.BevelMath.copy
import me.hlebkukleuski.fullfilestudio.feature.cadvector.BevelMath.dot
import me.hlebkukleuski.fullfilestudio.feature.cadvector.BevelMath.sub

internal class BevelNewVert {
    var v: BMesh.Vert? = null
    val co = FloatArray(3)
}

internal class BevelEdgeHalf {
    lateinit var next: BevelEdgeHalf
    lateinit var prev: BevelEdgeHalf
    lateinit var e: BMesh.Edge
    var fprev: BMesh.Face? = null
    var fnext: BMesh.Face? = null
    var leftv: BevelBoundVert? = null
    var rightv: BevelBoundVert? = null
    var profileIndex = 0
    var seg = 0
    var offsetL = 0f
    var offsetR = 0f
    var offsetLSpec = 0f
    var offsetRSpec = 0f
    var isBev = false
    var isRev = false
    var isSeam = false
    var visitedRpo = false
}

internal class BevelProfile {
    var superR = 0f
    var height = 0f
    val start = FloatArray(3)
    val middle = FloatArray(3)
    val end = FloatArray(3)
    val planeNo = FloatArray(3)
    val planeCo = FloatArray(3)
    val projDir = FloatArray(3)
    var profCo: FloatArray? = null
    var profCo2: FloatArray? = null
    var specialParams = false
}

internal class BevelProfileSpacing {
    var xvals: DoubleArray? = null
    var yvals: DoubleArray? = null
    var xvals2: DoubleArray? = null
    var yvals2: DoubleArray? = null
    var seg2 = 0
    var fullness = 0f
}

internal class BevelUVFace(val f: BMesh.Face, var attachedFrep: BMesh.Face?)

internal class BevelBoundVert {
    lateinit var next: BevelBoundVert
    lateinit var prev: BevelBoundVert
    val nv = BevelNewVert()
    var efirst: BevelEdgeHalf? = null
    var elast: BevelEdgeHalf? = null
    var eon: BevelEdgeHalf? = null
    var ebev: BevelEdgeHalf? = null
    var index = 0
    var sinratio = 1f
    var adjchain: BevelBoundVert? = null
    val profile = BevelProfile()
    var anySeam = false
    var visited = false
    var isArcStart = false
    var isPatchStart = false
    var isProfileStart = false
    var seamLen = 0
    var sharpLen = 0
}

internal class BevelVMesh {
    var mesh: Array<BevelNewVert> = emptyArray()
    var boundstart: BevelBoundVert? = null
    var count = 0
    var seg = 0
    var meshKind = BlendBevel.M_NONE
}

internal class BevelBevVert(val v: BMesh.Vert) {
    var edgecount = 0
    var selcount = 0
    var wirecount = 0
    var offset = 0f
    var anySeam = false
    var visited = false
    var edges: Array<BevelEdgeHalf> = emptyArray()
    var wireEdges: Array<BMesh.Edge> = emptyArray()
    lateinit var vmesh: BevelVMesh
}

internal class BevelParams(val bm: BMesh) {
    val vertHash = HashMap<BMesh.Vert, BevelBevVert>()
    val faceHash = HashMap<BMesh.Face, Int>()
    val uvFaceHash = HashMap<BMesh.Face, BevelUVFace>()
    val uvVertMaps = ArrayList<LinkedHashMap<BMesh.Vert, MutableList<LinkedHashSet<BMesh.Loop>>>>()
    val proSpacing = BevelProfileSpacing()
    val proSpacingMiter = BevelProfileSpacing()
    var faceComponent: IntArray? = null
    var hasMathLayers = false
    var offset = 0f
    var offsetType = 0
    var profileType = 0
    var affectType = 0
    var seg = 0
    var profile = 0f
    var proSuperR = 0f
    var useWeights = false
    var vertWeights: FloatArray? = null
    var edgeWeights: HashMap<BMesh.Edge, Float>? = null
    var loopSlide = false
    var limitOffset = false
    var offsetAdjust = false
    var markSeam = false
    var markSharp = false
    var hardenNormals = false
    var customProfile: BevelCurveProfile.Profile? = null
    var dvertWeights: FloatArray? = null
    var matNr = -1
    var faceStrengthMode = 0
    var miterOuter = 0
    var miterInner = 0
    var vmeshMethod = 0
    var spread = 0f
    val longTag = HashSet<BMesh.Loop>()
    var inputCustomNormals = false
    val harden = HashMap<BMesh.Loop, FloatArray>()
}

internal object BevelMath {
    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun copy(dst: FloatArray, src: FloatArray) { dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2] }
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun lenSq(a: FloatArray) = dot(a, a)
    fun len(a: FloatArray) = kotlin.math.sqrt(dot(a, a))
    fun lenV3V3(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return kotlin.math.sqrt(x * x + y * y + z * z) }
    fun lenSqV3V3(a: FloatArray, b: FloatArray): Float { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }
    fun normalize(v: FloatArray): Float = BMesh.normalizeV3(v)
    fun normalized(v: FloatArray): FloatArray { val r = v.copyOf(); BMesh.normalizeV3(r); return r }
    fun mulFl(v: FloatArray, f: Float) { v[0] *= f; v[1] *= f; v[2] *= f }
    fun scaled(v: FloatArray, f: Float) = floatArrayOf(v[0] * f, v[1] * f, v[2] * f)
    fun madd(a: FloatArray, b: FloatArray, f: Float) = floatArrayOf(a[0] + b[0] * f, a[1] + b[1] * f, a[2] + b[2] * f)
    fun negate(v: FloatArray) { v[0] = -v[0]; v[1] = -v[1]; v[2] = -v[2] }
    fun mid(a: FloatArray, b: FloatArray) = floatArrayOf((a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f)
    fun interp(a: FloatArray, b: FloatArray, t: Float): FloatArray { val s = 1.0f - t; return floatArrayOf(s * a[0] + t * b[0], s * a[1] + t * b[1], s * a[2] + t * b[2]) }
    fun isZero(v: FloatArray) = v[0] == 0f && v[1] == 0f && v[2] == 0f
    fun compareV3(a: FloatArray, b: FloatArray, limit: Float) = kotlin.math.abs(a[0] - b[0]) <= limit && kotlin.math.abs(a[1] - b[1]) <= limit && kotlin.math.abs(a[2] - b[2]) <= limit
    fun safeAsin(x: Float): Float = if (x <= -1f) (-Math.PI / 2).toFloat() else if (x >= 1f) (Math.PI / 2).toFloat() else kotlin.math.asin(x)
    fun saacos(x: Float): Float = if (x <= -1f) Math.PI.toFloat() else if (x >= 1f) 0f else kotlin.math.acos(x)
    fun angleNormalized(a: FloatArray, b: FloatArray): Float {
        if (dot(a, b) >= 0f) {
            val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
            return 2.0f * safeAsin(kotlin.math.sqrt(x * x + y * y + z * z) / 2.0f)
        }
        val x = a[0] + b[0]; val y = a[1] + b[1]; val z = a[2] + b[2]
        return Math.PI.toFloat() - 2.0f * safeAsin(kotlin.math.sqrt(x * x + y * y + z * z) / 2.0f)
    }
    fun angleV3V3(a: FloatArray, b: FloatArray): Float = angleNormalized(normalized(a), normalized(b))
    fun angleV3V3V3(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val v1 = sub(b, a); val v2 = sub(b, c)
        normalize(v1); normalize(v2)
        return angleNormalized(v1, v2)
    }
    fun closestToRay(p: FloatArray, orig: FloatArray, dir: FloatArray): Pair<FloatArray, Float> {
        if (isZero(dir)) return orig.copyOf() to 0.0f
        val h = sub(p, orig)
        val lambda = dot(dir, h) / dot(dir, dir)
        return madd(orig, dir, lambda) to lambda
    }

    fun closestToLine(p: FloatArray, l1: FloatArray, l2: FloatArray): Pair<FloatArray, Float> = closestToRay(p, l1, sub(l2, l1))

    fun closestToLineSegment(p: FloatArray, l1: FloatArray, l2: FloatArray): FloatArray {
        val (cp, lambda) = closestToLine(p, l1, l2)
        if (!(lambda > 0.0f)) return l1.copyOf()
        if (!(lambda < 1.0f)) return l2.copyOf()
        return cp
    }

    fun project(p: FloatArray, v: FloatArray): FloatArray {
        if (isZero(v)) return FloatArray(3)
        val mul = dot(p, v) / dot(v, v)
        return scaled(v, mul)
    }

    fun isectLineLine(v1: FloatArray, v2: FloatArray, v3: FloatArray, v4: FloatArray, r1: FloatArray, r2: FloatArray, epsilon: Float = 0.000001f): Int {
        var c = sub(v3, v1)
        var a = sub(v2, v1)
        var b = sub(v4, v3)
        var ab = cross(a, b)
        val d = dot(c, ab)
        val div = dot(ab, ab)
        if (div == 0.0f) return 0
        if (kotlin.math.abs(d) <= epsilon) {
            val cb = cross(c, b)
            mulFl(a, dot(cb, ab) / div)
            copy(r1, add(v1, a))
            copy(r2, r1)
            return 1
        }
        val n = cross(a, b)
        val t = project(sub(v1, v3), n)
        val v3t = add(v3, t)
        val v4t = add(v4, t)
        c = sub(v3t, v1)
        a = sub(v2, v1)
        b = sub(v4t, v3t)
        ab = cross(a, b)
        val cb = cross(c, b)
        mulFl(a, dot(cb, ab) / dot(ab, ab))
        copy(r1, add(v1, a))
        copy(r2, sub(r1, t))
        return 2
    }

    fun closestToPlaneNormalized(planeCo: FloatArray, planeNo: FloatArray, pt: FloatArray): FloatArray {
        val side = dot(planeNo, pt) + -dot(planeNo, planeCo)
        return madd(pt, planeNo, -side)
    }
}

internal object BlendBevel {
    const val M_NONE = 0
    const val M_POLY = 1
    const val M_ADJ = 2
    const val M_TRI_FAN = 3
    const val M_CUTOFF = 4

    const val F_NONE = 0
    const val F_ORIG = 1
    const val F_VERT = 2
    const val F_EDGE = 3
    const val F_RECON = 4

    const val ANGLE_SMALLER = -1
    const val ANGLE_STRAIGHT = 0
    const val ANGLE_LARGER = 1

    const val BEVEL_EPSILON_D = 1e-6
    const val BEVEL_EPSILON = 1e-6f
    const val BEVEL_EPSILON_SQ = 1e-12f
    const val BEVEL_EPSILON_BIG = 1e-4f
    const val BEVEL_EPSILON_BIG_SQ = 1e-8f
    val BEVEL_EPSILON_ANG = (2.0 * Math.PI / 180.0).toFloat()
    val BEVEL_SMALL_ANG = (10.0 * Math.PI / 180.0).toFloat()
    val BEVEL_SMALL_ANG_DOT = 1.0f - kotlin.math.cos(BEVEL_SMALL_ANG)
    val BEVEL_EPSILON_ANG_DOT = 1.0f - kotlin.math.cos(BEVEL_EPSILON_ANG)
    const val BEVEL_MAX_ADJUST_PCT = 10.0f
    const val BEVEL_MAX_AUTO_ADJUST_PCT = 300.0f
    const val BEVEL_MATCH_SPEC_WEIGHT = 0.2

    const val PRO_SQUARE_R = 1e4f
    const val PRO_CIRCLE_R = 2.0f
    const val PRO_LINE_R = 1.0f
    const val PRO_SQUARE_IN_R = 0.0f

    const val BEVEL_AMT_OFFSET = 0
    const val BEVEL_AMT_WIDTH = 1
    const val BEVEL_AMT_DEPTH = 2
    const val BEVEL_AMT_PERCENT = 3
    const val BEVEL_AMT_ABSOLUTE = 4

    const val BEVEL_PROFILE_SUPERELLIPSE = 0
    const val BEVEL_PROFILE_CUSTOM = 1

    const val BEVEL_AFFECT_VERTICES = 0
    const val BEVEL_AFFECT_EDGES = 1

    const val BEVEL_MITER_SHARP = 0
    const val BEVEL_MITER_PATCH = 1
    const val BEVEL_MITER_ARC = 2

    const val BEVEL_VMESH_ADJ = 0
    const val BEVEL_VMESH_CUTOFF = 1

    const val BEVEL_FACE_STRENGTH_NONE = 0
    const val FACE_STRENGTH_WEAK = -16384
    const val FACE_STRENGTH_MEDIUM = 0
    const val FACE_STRENGTH_STRONG = 16384
    const val BEVEL_FACE_STRENGTH_NEW = 1
    const val BEVEL_FACE_STRENGTH_AFFECTED = 2
    const val BEVEL_FACE_STRENGTH_ALL = 3

    const val STD_UV_CONNECT_LIMIT = 0.0001f

    fun recordFaceKind(bp: BevelParams, f: BMesh.Face, kind: Int) { bp.faceHash[f] = kind }

    fun getFaceKind(bp: BevelParams, f: BMesh.Face): Int = bp.faceHash[f] ?: F_ORIG

    fun nearlyParallel(d1: FloatArray, d2: FloatArray): Boolean {
        val ang = BevelMath.angleV3V3(d1, d2)
        return kotlin.math.abs(ang) < BEVEL_EPSILON_ANG || kotlin.math.abs(ang - Math.PI.toFloat()) < BEVEL_EPSILON_ANG
    }

    fun nearlyParallelNormalized(d1: FloatArray, d2: FloatArray): Boolean {
        val d = dot(d1, d2)
        return kotlin.math.abs(kotlin.math.abs(d) - 1.0f) <= BEVEL_EPSILON_ANG_DOT
    }

    fun addNewBoundVert(vm: BevelVMesh, co: FloatArray): BevelBoundVert {
        val ans = BevelBoundVert()
        copy(ans.nv.co, co)
        val start = vm.boundstart
        if (start == null) {
            ans.index = 0
            vm.boundstart = ans
            ans.next = ans
            ans.prev = ans
        } else {
            val tail = start.prev
            ans.index = tail.index + 1
            ans.prev = tail
            ans.next = start
            tail.next = ans
            start.prev = ans
        }
        ans.profile.superR = PRO_LINE_R
        ans.adjchain = null
        ans.sinratio = 1.0f
        ans.visited = false
        ans.anySeam = false
        ans.isArcStart = false
        ans.isPatchStart = false
        ans.isProfileStart = false
        vm.count++
        return ans
    }

    fun adjustBoundVert(bv: BevelBoundVert, co: FloatArray) = copy(bv.nv.co, co)

    fun meshVert(vm: BevelVMesh, i: Int, j: Int, k: Int): BevelNewVert {
        val nj = vm.seg / 2 + 1
        val nk = vm.seg + 1
        return vm.mesh[i * nk * nj + j * nk + k]
    }

    fun createMeshBmvert(bm: BMesh, vm: BevelVMesh, i: Int, j: Int, k: Int, eg: BMesh.Vert?) {
        val nv = meshVert(vm, i, j, k)
        val v = bm.vertCreate(nv.co, eg)
        v.tag = false
        nv.v = v
    }

    fun copyMeshVert(vm: BevelVMesh, ito: Int, jto: Int, kto: Int, ifrom: Int, jfrom: Int, kfrom: Int) {
        val to = meshVert(vm, ito, jto, kto)
        val from = meshVert(vm, ifrom, jfrom, kfrom)
        to.v = from.v
        copy(to.co, from.co)
    }

    fun findEdgeHalf(bv: BevelBevVert, bme: BMesh.Edge): BevelEdgeHalf? {
        for (i in 0 until bv.edgecount) if (bv.edges[i].e === bme) return bv.edges[i]
        return null
    }

    fun findBevvert(bp: BevelParams, v: BMesh.Vert): BevelBevVert? = bp.vertHash[v]

    fun findOtherEndEdgeHalf(bp: BevelParams, e: BevelEdgeHalf): Pair<BevelEdgeHalf, BevelBevVert>? {
        val bvo = findBevvert(bp, if (e.isRev) e.e.v1 else e.e.v2) ?: return null
        return findEdgeHalf(bvo, e.e)!! to bvo
    }

    fun nextBev(bv: BevelBevVert, fromIn: BevelEdgeHalf?): BevelEdgeHalf? {
        val from = fromIn ?: bv.edges[bv.edgecount - 1]
        var e = from
        do { if (e.isBev) return e; e = e.next } while (e !== from)
        return null
    }

    fun countCcwEdgesBetween(e1: BevelEdgeHalf, e2: BevelEdgeHalf): Int {
        var count = 0
        var e = e1
        do {
            if (e === e2) break
            e = e.next
            count++
        } while (e !== e1)
        return count
    }

    fun edgesFaceConnectedAtVert(a: BMesh.Edge, b: BMesh.Edge): Boolean {
        val h = a.l ?: return false
        var l: BMesh.Loop = h
        do { if (l.prev!!.e === b || l.next!!.e === b) return true; l = l.rNext!! } while (l !== h)
        return false
    }

    fun registerUvFace(bp: BevelParams, fnew: BMesh.Face?, frep: BMesh.Face?, frepArr: Array<BMesh.Face?>?): BevelUVFace? {
        if (fnew == null) return null
        val att = if (frepArr != null && frepArr.isNotEmpty() && frepArr[0] != null) frepArr[0] else frep
        val u = BevelUVFace(fnew, att)
        bp.uvFaceHash[fnew] = u
        return u
    }

    fun updateUvVertMap(bp: BevelParams, uvFace: BevelUVFace?, bv: BMesh.Vert?, nvBvMap: Map<BMesh.Vert, BMesh.Vert>?) {
        if (uvFace == null) return
        val frep = uvFace.attachedFrep ?: return
        for (map in bp.uvVertMaps) {
            for (l in BMesh.loopsOfFace(uvFace.f)) {
                val buckets = map[l.v]
                if (buckets == null) {
                    map[l.v] = mutableListOf(linkedSetOf(l))
                    continue
                }
                val origV = if (nvBvMap != null) nvBvMap[l.v] else bv
                val origL = bp.bm.faceVertShareLoop(frep, origV!!)
                var found = false
                for (l2 in BMesh.loopsOfVert(l.v)) {
                    if (l === l2) continue
                    val uf2 = bp.uvFaceHash[l2.f] ?: continue
                    val frep2 = uf2.attachedFrep ?: continue
                    val origL2 = bp.bm.faceVertShareLoop(frep2, origV)
                    var connected = false
                    val origBuckets = map[origV]
                    if (origBuckets != null) for (b in origBuckets) if (origL in b && origL2 in b) { connected = true; break }
                    if (frep === frep2 || connected) {
                        for (b in buckets) if (l2 in b) { b.add(l); found = true; break }
                    }
                    if (found) break
                }
                if (!found) buckets.add(linkedSetOf(l))
            }
        }
    }

    fun determineUvVertConnectivity(bp: BevelParams, v: BMesh.Vert) {
        if (!bp.bm.hasUv) return
        val map = bp.uvVertMaps[0]
        val buckets = ArrayList<LinkedHashSet<BMesh.Loop>>()
        for (l in BMesh.loopsOfVert(v)) {
            var found = false
            for (b in buckets) {
                for (l2 in b) {
                    if (kotlin.math.abs(l.u - l2.u) <= STD_UV_CONNECT_LIMIT && kotlin.math.abs(l.w - l2.w) <= STD_UV_CONNECT_LIMIT) {
                        b.add(l); found = true; break
                    }
                }
                if (found) break
            }
            if (!found) buckets.add(linkedSetOf(l))
        }
        map[v] = buckets
    }

    fun bevelMergeUvs(bp: BevelParams) {
        if (!bp.bm.hasUv) return
        for (buckets in bp.uvVertMaps[0].values) {
            for (b in buckets) {
                var wsum = 0f
                var u = 0f
                var w = 0f
                for (l in b) {
                    val area = BevelGeom.faceCalcArea(l.f)
                    u += l.u * area; w += l.w * area
                    wsum += area
                }
                if (b.size > 1 && wsum > 0f) {
                    val inv = 1.0f / wsum
                    u *= inv; w *= inv
                    for (l in b) { l.u = u; l.w = w }
                }
            }
        }
    }

    fun boundvertRepFace(v: BevelBoundVert): Pair<BMesh.Face?, BMesh.Face?> {
        var frep: BMesh.Face? = null
        var frep2: BMesh.Face? = null
        val ebev = v.ebev
        val efirst = v.efirst
        if (ebev != null) {
            frep = ebev.fprev
            if (efirst!!.fprev !== frep) frep2 = efirst.fprev
        } else if (efirst != null) {
            val elast = v.elast!!
            frep = efirst.fprev
            if (frep != null) {
                if (elast.fnext !== frep) frep2 = elast.fnext
                else if (efirst.fnext !== frep) frep2 = efirst.fnext
                else if (elast.fprev !== frep) frep2 = efirst.fprev
            } else if (efirst.fnext != null) {
                frep = efirst.fnext
                if (elast.fnext !== frep) frep2 = elast.fnext
            } else if (elast.fprev != null) {
                frep = elast.fprev
            }
        } else if (v.prev.elast != null) {
            frep = v.prev.elast!!.fnext
            if (v.next.efirst != null) {
                if (frep != null) frep2 = v.next.efirst!!.fprev else frep = v.next.efirst!!.fprev
            }
        }
        return frep to frep2
    }

    fun bevCreateNgon(
        bp: BevelParams, verts: Array<BMesh.Vert>, faceArr: Array<BMesh.Face?>?, facerep: BMesh.Face?, snapEdges: Array<BMesh.Edge?>?,
        bv: BMesh.Vert?, nvBvMap: Map<BMesh.Vert, BMesh.Vert>?, matNr: Int, doInterp: Boolean
    ): BMesh.Face? {
        val bm = bp.bm
        val f = bm.faceCreateVerts(verts, facerep)
        val rep = facerep ?: faceArr?.getOrNull(0)
        if (rep != null) {
            f.mat = rep.mat; f.smooth = rep.smooth; copy(f.no, rep.no)
            if (doInterp) {
                var i = 0
                for (l in BMesh.loopsOfFace(f)) {
                    val interpF = if (faceArr != null) faceArr[i] else facerep
                    if (interpF != null) {
                        val bme = snapEdges?.get(i)
                        var save: FloatArray? = null
                        if (bme != null) {
                            save = l.v.co.copyOf()
                            copy(l.v.co, BevelMath.closestToLineSegment(save, bme.v1.co, bme.v2.co))
                        }
                        BevelGeom.loopInterpFromFace(bm, l, interpF, true)
                        if (save != null) copy(l.v.co, save)
                    }
                    i++
                }
            }
        }
        f.tag = true
        if (matNr >= 0) f.mat = matNr
        val uf = registerUvFace(bp, f, facerep, faceArr)
        updateUvVertMap(bp, uf, bv, nvBvMap)
        return f
    }

    fun contigLdataAcrossEdge(bm: BMesh, e: BMesh.Edge, f1: BMesh.Face, f2: BMesh.Face): Boolean {
        if (!bm.hasUv && !bm.hasCornerColor) return true
        val pair = BMesh.edgeLoopPair(e) ?: return false
        var lef1 = pair.first
        var lef2 = pair.second
        if (lef1.f === f2) { val t = lef1; lef1 = lef2; lef2 = t }
        if (lef1.f !== f1 || lef2.f !== f2) return false
        if (lef1.v === lef2.v) return false
        val lv1f1 = lef1
        val lv2f1 = lef1.next!!
        val lv1f2 = lef2.next!!
        val lv2f2 = lef2
        fun eq(a: BMesh.Loop, b: BMesh.Loop): Boolean {
            if (bm.hasUv && (a.u - b.u) * (a.u - b.u) + (a.w - b.w) * (a.w - b.w) >= 0.00001f) return false
            if (bm.hasCornerColor) {
                var s = 0f
                for (sh in intArrayOf(24, 16, 8, 0)) { val d = (((a.color ushr sh) and 0xFF) - ((b.color ushr sh) and 0xFF)).toFloat(); s += d * d }
                if (s >= 0.001f) return false
            }
            return true
        }
        return eq(lv1f1, lv1f2) && eq(lv2f1, lv2f2)
    }

    fun swapFaceComponents(fc: IntArray, c1: Int, c2: Int) {
        if (c1 == c2) return
        for (f in fc.indices) { if (fc[f] == c1) fc[f] = c2 else if (fc[f] == c2) fc[f] = c1 }
    }

    fun uvVertMapInit(bp: BevelParams) {
        bp.uvVertMaps.clear()
        if (bp.bm.hasUv) bp.uvVertMaps.add(LinkedHashMap())
    }

    fun uvVertMapPop(bp: BevelParams, v: BMesh.Vert) { for (m in bp.uvVertMaps) m.remove(v) }

    fun facesOfEdge(e: BMesh.Edge): List<BMesh.Face> {
        val out = ArrayList<BMesh.Face>()
        val h = e.l ?: return out
        var l: BMesh.Loop = h
        do { out.add(l.f); l = l.rNext!! } while (l !== h)
        return out
    }

    fun mathLayerInfoInit(bp: BevelParams) {
        val bm = bp.bm
        bp.hasMathLayers = bm.hasUv
        bp.faceComponent = null
        if (!bp.hasMathLayers || bp.seg % 2 == 0) return
        val faces = bm.faces()
        for ((i, f) in faces.withIndex()) f.index = i
        val totface = faces.size
        val fc = IntArray(totface) { -1 }
        bp.faceComponent = fc
        val stack = arrayOfNulls<BMesh.Face>(totface)
        val inStack = BooleanArray(totface)
        var cur = -1
        for (f in 0 until totface) {
            if (fc[f] == -1 && !inStack[f]) {
                var top = 0
                cur++
                stack[top] = faces[f]
                inStack[f] = true
                while (top >= 0) {
                    val bmf = stack[top]!!
                    top--
                    val bi = bmf.index
                    inStack[bi] = false
                    if (fc[bi] != -1) continue
                    fc[bi] = cur
                    for (l in BMesh.loopsOfFace(bmf)) {
                        val bme = l.e!!
                        for (other in facesOfEdge(bme)) {
                            if (other !== bmf) {
                                val oi = other.index
                                if (fc[oi] != -1 || inStack[oi]) continue
                                if (contigLdataAcrossEdge(bm, bme, bmf, other)) {
                                    top++
                                    stack[top] = other
                                    inStack[oi] = true
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cur <= 0) return
        var topZ = -1e30f; var topC = -1
        var botZ = 1e30f; var botC = -1
        for (f in 0 until totface) {
            val fz = BevelGeom.faceCenterBounds(faces[f])[2]
            if (fz > topZ) { topZ = fz; topC = fc[f] }
            if (fz < botZ) { botZ = fz; botC = fc[f] }
        }
        swapFaceComponents(fc, fc[0], topC)
        if (botC != topC) {
            if (botC == 0) botC = topC
            swapFaceComponents(fc, fc[1], botC)
        }
    }

    fun chooseRepFace(bp: BevelParams, face: Array<BMesh.Face?>): BMesh.Face? {
        val n = face.size
        val vals = Array(n) { FloatArray(6) }
        val viable = BooleanArray(n)
        var numViable = 0
        for (f in 0 until n) {
            val bmf = face[f] ?: continue
            viable[f] = true
            numViable++
            val fc = bp.faceComponent
            vals[f][0] = if (fc != null) fc[bmf.index].toFloat() else 0.0f
            vals[f][1] = 1.0f
            vals[f][2] = if (bmf.mat >= 0) bmf.mat.toFloat() else 0.0f
            val cent = BevelGeom.faceCenterBounds(bmf)
            vals[f][3] = cent[2]; vals[f][4] = cent[0]; vals[f][5] = cent[1]
        }
        var best = -1
        var vi = 0
        while (numViable > 1 && vi < 6) {
            for (f in 0 until n) {
                if (!viable[f] || f == best) continue
                if (best == -1) { best = f; continue }
                if (vals[f][vi] < vals[best][vi]) {
                    best = f
                    for (i in f - 1 downTo 0) if (viable[i]) { viable[i] = false; numViable-- }
                } else if (vals[f][vi] > vals[best][vi]) {
                    viable[f] = false
                    numViable--
                }
            }
            vi++
        }
        if (best == -1) best = 0
        return face[best]
    }

    fun slideDist(e: BMesh.Edge, v: BMesh.Vert, dIn: Float): FloatArray {
        var d = dIn
        val dir = sub(v.co, BMesh.otherVert(e, v)!!.co)
        val len = BevelMath.normalize(dir)
        if (d > len) d = len - (50.0 * BEVEL_EPSILON_D).toFloat()
        return BevelMath.madd(v.co, dir, -d)
    }

    fun isOutsideEdge(e: BevelEdgeHalf, co: FloatArray): BMesh.Vert? {
        val l1 = e.e.v1.co
        val u = sub(e.e.v2.co, l1)
        val h = sub(co, l1)
        val lenu = BevelMath.normalize(u)
        val lambda = dot(u, h)
        if (lambda <= -BEVEL_EPSILON_BIG * lenu) return e.e.v1
        if (lambda >= (1.0f + BEVEL_EPSILON_BIG) * lenu) return e.e.v2
        return null
    }

    fun edgesAngleKind(e1: BevelEdgeHalf, e2: BevelEdgeHalf, v: BMesh.Vert): Int {
        val v1 = BMesh.otherVert(e1.e, v)!!
        val v2 = BMesh.otherVert(e2.e, v)!!
        val dir1 = sub(v.co, v1.co)
        val dir2 = sub(v.co, v2.co)
        BevelMath.normalize(dir1); BevelMath.normalize(dir2)
        if (nearlyParallelNormalized(dir1, dir2)) return ANGLE_STRAIGHT
        val cr = BevelMath.cross(dir1, dir2)
        BevelMath.normalize(cr)
        val no = e1.fnext?.no ?: e2.fprev?.no ?: v.no
        return if (dot(cr, no) < 0.0f) ANGLE_LARGER else ANGLE_SMALLER
    }

    fun pointBetweenEdges(co: FloatArray, v: BMesh.Vert, f: BMesh.Face, e1: BevelEdgeHalf, e2: BevelEdgeHalf): Boolean {
        val v1 = BMesh.otherVert(e1.e, v)!!
        val v2 = BMesh.otherVert(e2.e, v)!!
        val dir1 = sub(v.co, v1.co); val dir2 = sub(v.co, v2.co); val dirco = sub(v.co, co)
        BevelMath.normalize(dir1); BevelMath.normalize(dir2); BevelMath.normalize(dirco)
        var ang11 = BevelMath.angleNormalized(dir1, dir2)
        var ang1co = BevelMath.angleNormalized(dir1, dirco)
        if (dot(BevelMath.cross(dir1, dir2), f.no) < 0.0f) ang11 = (Math.PI * 2.0).toFloat() - ang11
        if (dot(BevelMath.cross(dir1, dirco), f.no) < 0.0f) ang1co = (Math.PI * 2.0).toFloat() - ang1co
        return ang11 - ang1co > -BEVEL_EPSILON_ANG
    }

    fun edgeEdgeAngleLessThan180(e1: BMesh.Edge, e2: BMesh.Edge, f: BMesh.Face): Boolean {
        val v: BMesh.Vert; val v1: BMesh.Vert; val v2: BMesh.Vert
        if (e1.v1 === e2.v1) { v = e1.v1; v1 = e1.v2; v2 = e2.v2 }
        else if (e1.v1 === e2.v2) { v = e1.v1; v1 = e1.v2; v2 = e2.v1 }
        else if (e1.v2 === e2.v1) { v = e1.v2; v1 = e1.v1; v2 = e2.v2 }
        else if (e1.v2 === e2.v2) { v = e1.v2; v1 = e1.v1; v2 = e2.v1 }
        else return false
        val cr = BevelMath.cross(sub(v1.co, v.co), sub(v2.co, v.co))
        return dot(cr, f.no) > 0.0f
    }

    fun edgeWeight(bp: BevelParams, e: BMesh.Edge): Float = bp.edgeWeights?.get(e) ?: 0.0f

    fun offsetMeetLinesPercentOrAbsolute(bp: BevelParams, e1: BevelEdgeHalf, e2: BevelEdgeHalf, v: BMesh.Vert): Array<FloatArray> {
        val v1 = BMesh.otherVert(e1.e, v)!!
        val v2 = BMesh.otherVert(e2.e, v)!!
        val f1 = e1.fnext
        val f2 = e2.fprev
        var noOffsets = f1 == null || f2 == null
        if (!noOffsets) {
            val e0 = bp.bm.faceVertShareLoop(f1!!, v1)!!.e!!
            val e3 = bp.bm.faceVertShareLoop(f2!!, v2)!!.prev!!.e!!
            val e4 = bp.bm.faceVertShareLoop(f1, v)!!.prev!!.e!!
            val e5 = bp.bm.faceVertShareLoop(f2, v)!!.e!!
            noOffsets = !edgeEdgeAngleLessThan180(e0, e1.e, f1) || !edgeEdgeAngleLessThan180(e1.e, e4, f1) ||
                !edgeEdgeAngleLessThan180(e2.e, e3, f2) || !edgeEdgeAngleLessThan180(e5, e2.e, f1)
            if (!noOffsets) {
                val d0: Float; val d3: Float; val d4: Float; val d5: Float
                if (bp.offsetType == BEVEL_AMT_ABSOLUTE) { d0 = bp.offset; d3 = bp.offset; d4 = bp.offset; d5 = bp.offset }
                else {
                    d0 = bp.offset * BevelMath.lenV3V3(e0.v1.co, e0.v2.co) / 100.0f
                    d3 = bp.offset * BevelMath.lenV3V3(e3.v1.co, e3.v2.co) / 100.0f
                    d4 = bp.offset * BevelMath.lenV3V3(e4.v1.co, e4.v2.co) / 100.0f
                    d5 = bp.offset * BevelMath.lenV3V3(e5.v1.co, e5.v2.co) / 100.0f
                }
                val w1 = if (bp.useWeights) edgeWeight(bp, e1.e) else 1.0f
                val w2 = if (bp.useWeights) edgeWeight(bp, e2.e) else 1.0f
                return arrayOf(slideDist(e4, v, d4 * w1), slideDist(e0, v1, d0 * w1), slideDist(e5, v, d5 * w2), slideDist(e3, v2, d3 * w2))
            }
        }
        return arrayOf(v.co.copyOf(), v1.co.copyOf(), v.co.copyOf(), v2.co.copyOf())
    }

    fun offsetMeet(bp: BevelParams, e1: BevelEdgeHalf, e2: BevelEdgeHalf, v: BMesh.Vert, fIn: BMesh.Face?, edgesBetween: Boolean, meetco: FloatArray, eInPlane: BevelEdgeHalf?) {
        var f = fIn
        var dir1 = sub(v.co, BMesh.otherVert(e1.e, v)!!.co)
        val dir2 = sub(BMesh.otherVert(e2.e, v)!!.co, v.co)
        val dir1n: FloatArray
        val dir2p: FloatArray
        if (edgesBetween) {
            dir1n = sub(BMesh.otherVert(e1.next.e, v)!!.co, v.co)
            dir2p = sub(v.co, BMesh.otherVert(e2.prev.e, v)!!.co)
        } else { dir1n = FloatArray(3); dir2p = FloatArray(3) }
        var ang = BevelMath.angleV3V3(dir1, dir2)
        if (ang < BEVEL_EPSILON_ANG) {
            val normV: FloatArray
            if (f != null) normV = f.no.copyOf() else {
                normV = FloatArray(3)
                var fcount = 0
                var eloop = e1
                while (eloop !== e2) {
                    val fn = eloop.fnext
                    if (fn != null) { normV[0] += fn.no[0]; normV[1] += fn.no[1]; normV[2] += fn.no[2]; fcount++ }
                    eloop = eloop.next
                }
                if (fcount == 0) copy(normV, v.no) else BevelMath.mulFl(normV, 1.0f / fcount)
            }
            dir1 = add(dir1, dir2)
            val perp = BevelMath.cross(dir1, normV)
            BevelMath.normalize(perp)
            var d = maxOf(e1.offsetR, e2.offsetL)
            d = d / kotlin.math.cos(ang / 2.0f)
            copy(meetco, BevelMath.madd(v.co, perp, d))
        } else if (kotlin.math.abs(ang - Math.PI.toFloat()) < BEVEL_EPSILON_ANG) {
            val d = maxOf(e1.offsetR, e2.offsetL)
            copy(meetco, slideDist(e2.e, v, d))
        } else {
            val n1: FloatArray
            val n2: FloatArray
            if (f != null && ang < BEVEL_SMALL_ANG) { n1 = f.no.copyOf(); n2 = f.no.copyOf() }
            else if (!edgesBetween) {
                n1 = BevelMath.cross(dir2, dir1)
                BevelMath.normalize(n1)
                if (dot(n1, f?.no ?: v.no) < 0.0f) BevelMath.negate(n1)
                n2 = n1.copyOf()
            } else {
                n1 = BevelMath.cross(dir1n, dir1)
                BevelMath.normalize(n1)
                f = e1.fnext
                if (dot(n1, f?.no ?: v.no) < 0.0f) BevelMath.negate(n1)
                n2 = BevelMath.cross(dir2, dir2p)
                BevelMath.normalize(n2)
                f = e2.fprev
                if (dot(n2, f?.no ?: v.no) < 0.0f) BevelMath.negate(n2)
            }
            val perp1 = BevelMath.cross(dir1, n1)
            val perp2 = BevelMath.cross(dir2, n2)
            BevelMath.normalize(perp1); BevelMath.normalize(perp2)
            val off1a: FloatArray; val off1b: FloatArray; val off2a: FloatArray; val off2b: FloatArray
            if (bp.offsetType == BEVEL_AMT_PERCENT || bp.offsetType == BEVEL_AMT_ABSOLUTE) {
                val r = offsetMeetLinesPercentOrAbsolute(bp, e1, e2, v)
                off1a = r[0]; off1b = r[1]; off2a = r[2]; off2b = r[3]
            } else {
                off1a = BevelMath.madd(v.co, perp1, e1.offsetR)
                off1b = add(off1a, dir1)
                off2a = BevelMath.madd(v.co, perp2, e2.offsetL)
                off2b = add(off2a, dir2)
            }
            val isect2 = FloatArray(3)
            val kind = BevelMath.isectLineLine(off1a, off1b, off2a, off2b, meetco, isect2)
            if (kind == 0) copy(meetco, off1a)
            else {
                if (e1.offsetR == 0.0f) isOutsideEdge(e1, meetco)?.let { copy(meetco, it.co) }
                if (e2.offsetL == 0.0f) isOutsideEdge(e2, meetco)?.let { copy(meetco, it.co) }
                if (edgesBetween && e1.offsetR > 0.0f && e2.offsetL > 0.0f) {
                    if (kind == 2) copy(meetco, BevelMath.mid(meetco, isect2))
                    var e = e1
                    while (e !== e2) {
                        val fnext = e.fnext
                        if (fnext != null) {
                            val dropco = BevelMath.closestToPlaneNormalized(v.co, fnext.no, meetco)
                            var skip = false
                            if (eInPlane != null) {
                                ang = BevelMath.angleV3V3(fnext.no, eInPlane.fnext!!.no)
                                if (kotlin.math.abs(ang) < BEVEL_SMALL_ANG || kotlin.math.abs(ang - Math.PI.toFloat()) < BEVEL_SMALL_ANG) skip = true
                            }
                            if (!skip && pointBetweenEdges(dropco, v, fnext, e, e.next)) { copy(meetco, dropco); break }
                        }
                        e = e.next
                    }
                }
            }
        }
    }

    private const val BEVEL_GOOD_ANGLE = 0.1f

    fun offsetMeetEdge(e1: BevelEdgeHalf, e2: BevelEdgeHalf, v: BMesh.Vert, meetco: FloatArray, rAngle: FloatArray?): Boolean {
        val dir1 = sub(BMesh.otherVert(e1.e, v)!!.co, v.co)
        val dir2 = sub(BMesh.otherVert(e2.e, v)!!.co, v.co)
        BevelMath.normalize(dir1); BevelMath.normalize(dir2)
        var ang = BevelMath.angleNormalized(dir1, dir2)
        if (kotlin.math.abs(ang) < BEVEL_GOOD_ANGLE) { rAngle?.set(0, 0.0f); return false }
        val fno = BevelMath.cross(dir1, dir2)
        if (dot(fno, v.no) < 0.0f) {
            ang = 2.0f * Math.PI.toFloat() - ang
            rAngle?.set(0, ang)
            return false
        }
        rAngle?.set(0, ang)
        if (kotlin.math.abs(ang - Math.PI.toFloat()) < BEVEL_GOOD_ANGLE) return false
        val sinang = kotlin.math.sin(ang)
        if (e1.offsetR == 0.0f) copy(meetco, BevelMath.madd(v.co, dir1, e2.offsetL / sinang))
        else copy(meetco, BevelMath.madd(v.co, dir2, e1.offsetR / sinang))
        return true
    }

    fun goodOffsetOnEdgeBetween(e1: BevelEdgeHalf, e2: BevelEdgeHalf, emid: BevelEdgeHalf, v: BMesh.Vert): Boolean {
        val m = FloatArray(3)
        val a = FloatArray(1)
        return offsetMeetEdge(e1, emid, v, m, a) && offsetMeetEdge(emid, e2, v, m, a)
    }

    fun offsetOnEdgeBetween(bp: BevelParams, e1: BevelEdgeHalf, e2: BevelEdgeHalf, emid: BevelEdgeHalf, v: BMesh.Vert, meetco: FloatArray, rSinratio: FloatArray?): Boolean {
        val a1 = FloatArray(1); val a2 = FloatArray(1)
        val meet1 = FloatArray(3); val meet2 = FloatArray(3)
        val ok1 = offsetMeetEdge(e1, emid, v, meet1, a1)
        val ok2 = offsetMeetEdge(emid, e2, v, meet2, a2)
        if (bp.offsetType == BEVEL_AMT_PERCENT || bp.offsetType == BEVEL_AMT_ABSOLUTE) {
            val v2 = BMesh.otherVert(emid.e, v)!!
            if (bp.offsetType == BEVEL_AMT_PERCENT) {
                var wt = 1.0f
                if (bp.useWeights) wt = if (bp.edgeWeights == null) 0.0f else 0.5f * (edgeWeight(bp, e1.e) + edgeWeight(bp, e2.e))
                copy(meetco, BevelMath.interp(v.co, v2.co, wt * bp.offset / 100.0f))
            } else {
                val dir = sub(v2.co, v.co)
                BevelMath.normalize(dir)
                copy(meetco, BevelMath.madd(v.co, dir, bp.offset))
            }
            rSinratio?.set(0, if (a1[0] == 0.0f) 1.0f else kotlin.math.sin(a2[0]) / kotlin.math.sin(a1[0]))
            return true
        }
        var ret = false
        if (ok1 && ok2) {
            copy(meetco, BevelMath.mid(meet1, meet2))
            rSinratio?.set(0, if (a1[0] == 0.0f) 1.0f else kotlin.math.sin(a2[0]) / kotlin.math.sin(a1[0]))
            ret = true
        } else if (ok1) copy(meetco, meet1)
        else if (ok2) copy(meetco, meet2)
        else copy(meetco, slideDist(emid.e, v, e1.offsetR))
        return ret
    }

    fun offsetInPlane(e: BevelEdgeHalf, planeNo: FloatArray?, left: Boolean): FloatArray {
        val v = if (e.isRev) e.e.v2 else e.e.v1
        val dir = sub(BMesh.otherVert(e.e, v)!!.co, v.co)
        BevelMath.normalize(dir)
        val no = FloatArray(3)
        if (planeNo != null) copy(no, planeNo) else if (kotlin.math.abs(dir[0]) < kotlin.math.abs(dir[1])) no[0] = 1.0f else no[1] = 1.0f
        val fdir = if (left) BevelMath.cross(dir, no) else BevelMath.cross(no, dir)
        BevelMath.normalize(fdir)
        return BevelMath.madd(v.co, fdir, if (left) e.offsetL else e.offsetR)
    }

    fun projectToEdge(e: BMesh.Edge, a: FloatArray, b: FloatArray, proj: FloatArray) {
        val other = FloatArray(3)
        if (BevelMath.isectLineLine(e.v1.co, e.v2.co, a, b, proj, other) == 0) copy(proj, e.v1.co)
    }

    fun setProfileParams(bp: BevelParams, bv: BevelBevVert, bndv: BevelBoundVert) {
        var linear = true
        val e = bndv.ebev
        val pro = bndv.profile
        val start = bndv.nv.co.copyOf()
        val end = bndv.next.nv.co.copyOf()
        if (e != null) {
            linear = false
            pro.superR = bp.proSuperR
            copy(pro.projDir, sub(e.e.v1.co, e.e.v2.co))
            if (e.isRev) BevelMath.negate(pro.projDir)
            BevelMath.normalize(pro.projDir)
            projectToEdge(e.e, start, end, pro.middle)
            copy(pro.start, start)
            copy(pro.end, end)
            var d1 = sub(pro.middle, start)
            var d2 = sub(pro.middle, end)
            BevelMath.normalize(d1); BevelMath.normalize(d2)
            copy(pro.planeNo, BevelMath.cross(d1, d2))
            BevelMath.normalize(pro.planeNo)
            if (nearlyParallel(d1, d2)) {
                copy(pro.middle, bv.v.co)
                if (e.prev.isBev && e.next.isBev && bv.selcount >= 3) {
                    val d3 = sub(e.prev.e.v1.co, e.prev.e.v2.co)
                    val d4 = sub(e.next.e.v1.co, e.next.e.v2.co)
                    BevelMath.normalize(d3); BevelMath.normalize(d4)
                    if (nearlyParallel(d3, d4)) {
                        copy(pro.middle, BevelMath.mid(start, end))
                        linear = true
                    } else {
                        val co3 = add(start, d3)
                        val co4 = add(end, d4)
                        val meet = FloatArray(3); val is2 = FloatArray(3)
                        if (BevelMath.isectLineLine(start, co3, end, co4, meet, is2) != 0) copy(pro.middle, meet)
                        else { copy(pro.middle, BevelMath.mid(start, end)); linear = true }
                    }
                }
                copy(pro.end, end)
                d1 = sub(pro.middle, start); BevelMath.normalize(d1)
                d2 = sub(pro.middle, end); BevelMath.normalize(d2)
                copy(pro.planeNo, BevelMath.cross(d1, d2))
                BevelMath.normalize(pro.planeNo)
                if (nearlyParallel(d1, d2)) linear = true
                else { copy(pro.planeCo, bv.v.co); copy(pro.projDir, pro.planeNo) }
            }
            copy(pro.planeCo, start)
        } else if (bndv.isArcStart) {
            copy(pro.start, start); copy(pro.end, end)
            pro.superR = PRO_CIRCLE_R
            pro.planeCo.fill(0f); pro.planeNo.fill(0f); pro.projDir.fill(0f)
            linear = false
        } else if (bp.affectType == BEVEL_AFFECT_VERTICES) {
            copy(pro.start, start); copy(pro.middle, bv.v.co); copy(pro.end, end)
            pro.superR = bp.proSuperR
            pro.planeCo.fill(0f); pro.planeNo.fill(0f); pro.projDir.fill(0f)
            linear = false
        }
        if (linear) {
            pro.superR = PRO_LINE_R
            copy(pro.start, start); copy(pro.end, end)
            copy(pro.middle, BevelMath.mid(start, end))
            pro.planeCo.fill(0f); pro.planeNo.fill(0f); pro.projDir.fill(0f)
        }
    }

    fun moveProfilePlane(bndv: BevelBoundVert, bmvert: BMesh.Vert) {
        val pro = bndv.profile
        if (BevelMath.isZero(pro.projDir)) return
        val d1 = sub(bmvert.co, pro.start); BevelMath.normalize(d1)
        val d2 = sub(bmvert.co, pro.end); BevelMath.normalize(d2)
        val no = BevelMath.cross(d1, d2)
        val no2 = BevelMath.cross(d1, pro.projDir)
        val no3 = BevelMath.cross(d2, pro.projDir)
        if (BevelMath.normalize(no) > BEVEL_EPSILON_BIG && BevelMath.normalize(no2) > BEVEL_EPSILON_BIG && BevelMath.normalize(no3) > BEVEL_EPSILON_BIG) {
            val dot2 = dot(no, no2)
            val dot3 = dot(no, no3)
            if (kotlin.math.abs(dot2) < 1 - BEVEL_EPSILON_BIG && kotlin.math.abs(dot3) < 1 - BEVEL_EPSILON_BIG) copy(bndv.profile.planeNo, no)
        }
        pro.specialParams = true
    }

    fun moveWeldProfilePlanes(bv: BevelBevVert, b1: BevelBoundVert, b2: BevelBoundVert) {
        if (BevelMath.isZero(b1.profile.projDir) || BevelMath.isZero(b2.profile.projDir)) return
        val d1 = sub(bv.v.co, b1.nv.co)
        val d2 = sub(bv.v.co, b2.nv.co)
        val no = BevelMath.cross(d1, d2)
        val l1 = BevelMath.normalize(no)
        val no2 = BevelMath.cross(d1, b1.profile.projDir)
        val l2 = BevelMath.normalize(no2)
        val no3 = BevelMath.cross(d2, b2.profile.projDir)
        val l3 = BevelMath.normalize(no3)
        if (l1 > BEVEL_EPSILON && (l2 > BEVEL_EPSILON || l3 > BEVEL_EPSILON)) {
            val dot1 = kotlin.math.abs(dot(no, no2))
            val dot2 = kotlin.math.abs(dot(no, no3))
            if (kotlin.math.abs(dot1 - 1.0f) > BEVEL_EPSILON) copy(b1.profile.planeNo, no)
            if (kotlin.math.abs(dot2 - 1.0f) > BEVEL_EPSILON) copy(b2.profile.planeNo, no)
        }
        b1.profile.specialParams = true
        b2.profile.specialParams = true
    }

    fun faceEdgeShareLoop(f: BMesh.Face, e: BMesh.Edge): BMesh.Loop? = BMesh.loopsOfFace(f).firstOrNull { it.e === e }

    fun bevCcwTest(a: BMesh.Edge, b: BMesh.Edge, f: BMesh.Face?): Int {
        if (f == null) return 0
        val la = faceEdgeShareLoop(f, a) ?: return 0
        val lb = faceEdgeShareLoop(f, b) ?: return 0
        return if (lb.next === la) 1 else -1
    }

    fun mulM4V3(m: Array<FloatArray>, v: FloatArray): FloatArray {
        val x = v[0]; val y = v[1]; val z = v[2]
        return floatArrayOf(
            x * m[0][0] + y * m[1][0] + z * m[2][0] + m[3][0],
            x * m[0][1] + y * m[1][1] + z * m[2][1] + m[3][1],
            x * m[0][2] + y * m[1][2] + z * m[2][2] + m[3][2]
        )
    }

    fun makeUnitSquareMap(va: FloatArray, vmid: FloatArray, vb: FloatArray, m: Array<FloatArray>): Boolean {
        val vaVmid = sub(vmid, va)
        val vbVmid = sub(vmid, vb)
        if (BevelMath.isZero(vaVmid) || BevelMath.isZero(vbVmid)) return false
        if (kotlin.math.abs(BevelMath.angleV3V3(vaVmid, vbVmid) - Math.PI.toFloat()) <= BEVEL_EPSILON_ANG) return false
        val vo = sub(va, vbVmid)
        val vddir = BevelMath.cross(vbVmid, vaVmid)
        BevelMath.normalize(vddir)
        val vd = add(vo, vddir)
        val c0 = sub(vmid, va); m[0][0] = c0[0]; m[0][1] = c0[1]; m[0][2] = c0[2]; m[0][3] = 0f
        val c1 = sub(vmid, vb); m[1][0] = c1[0]; m[1][1] = c1[1]; m[1][2] = c1[2]; m[1][3] = 0f
        val c2 = sub(sub(add(vmid, vd), va), vb); m[2][0] = c2[0]; m[2][1] = c2[1]; m[2][2] = c2[2]; m[2][3] = 0f
        val c3 = sub(add(va, vb), vmid); m[3][0] = c3[0]; m[3][1] = c3[1]; m[3][2] = c3[2]; m[3][3] = 1f
        return true
    }

    fun makeUnitCubeMap(va: FloatArray, vb: FloatArray, vc: FloatArray, vd: FloatArray, m: Array<FloatArray>) {
        fun set(i: Int, x: FloatArray, w: Float) { m[i][0] = x[0]; m[i][1] = x[1]; m[i][2] = x[2]; m[i][3] = w }
        set(0, BevelMath.scaled(add(sub(sub(va, vb), vc), vd), 0.5f), 0f)
        set(1, BevelMath.scaled(add(sub(sub(vb, va), vc), vd), 0.5f), 0f)
        set(2, BevelMath.scaled(add(sub(sub(vc, va), vb), vd), 0.5f), 0f)
        set(3, BevelMath.scaled(sub(add(add(va, vb), vc), vd), 0.5f), 1f)
    }

    fun superellipseCo(x: Double, r: Float, rbig: Boolean): Double {
        if (rbig) return Math.pow(1.0 - Math.pow(x, r.toDouble()), 1.0 / r)
        return 1.0 - Math.pow(1.0 - Math.pow(1.0 - x, r.toDouble()), 1.0 / r)
    }

    fun getProfilePoint(bp: BevelParams, pro: BevelProfile, i: Int, nseg: Int, out: FloatArray) {
        if (bp.seg == 1) { copy(out, if (i == 0) pro.start else pro.end); return }
        if (nseg == bp.seg) { val c = pro.profCo!!; out[0] = c[3 * i]; out[1] = c[3 * i + 1]; out[2] = c[3 * i + 2] }
        else {
            val ss = bp.proSpacing.seg2 / nseg
            val c = pro.profCo2!!
            out[0] = c[3 * i * ss]; out[1] = c[3 * i * ss + 1]; out[2] = c[3 * i * ss + 2]
        }
    }

    fun isectLinePlane(l1: FloatArray, l2: FloatArray, planeCo: FloatArray, planeNo: FloatArray): FloatArray? {
        val u = sub(l2, l1)
        val h = sub(l1, planeCo)
        val d = dot(planeNo, u)
        if (kotlin.math.abs(d) > 1.1920929E-7f) {
            val lambda = -dot(planeNo, h) / d
            return BevelMath.madd(l1, u, lambda)
        }
        return null
    }

    fun calculateProfileSegments(pro: BevelProfile, map: Array<FloatArray>, useMap: Boolean, reversed: Boolean, ns: Int, xvals: DoubleArray?, yvals: DoubleArray?, out: FloatArray) {
        for (k in 0..ns) {
            val co: FloatArray = if (k == 0) pro.start.copyOf() else if (k == ns) pro.end.copyOf() else if (useMap) {
                val p = floatArrayOf(if (reversed) yvals!![ns - k].toFloat() else xvals!![k].toFloat(), if (reversed) xvals!![ns - k].toFloat() else yvals!![k].toFloat(), 0f)
                mulM4V3(map, p)
            } else BevelMath.interp(pro.start, pro.end, k.toFloat() / ns.toFloat())
            var r = co
            if (!BevelMath.isZero(pro.projDir)) {
                val co2 = add(co, pro.projDir)
                r = isectLinePlane(co, co2, pro.planeCo, pro.planeNo) ?: co
            }
            out[3 * k] = r[0]; out[3 * k + 1] = r[1]; out[3 * k + 2] = r[2]
        }
    }

    fun calculateProfile(bp: BevelParams, bndv: BevelBoundVert, reversed: Boolean, miter: Boolean) {
        val pro = bndv.profile
        val sp = if (miter) bp.proSpacingMiter else bp.proSpacing
        if (bp.seg == 1) return
        val need2 = bp.seg != bp.proSpacing.seg2
        if (pro.profCo == null) {
            pro.profCo = FloatArray(3 * (bp.seg + 1))
            pro.profCo2 = if (need2) FloatArray(3 * (bp.proSpacing.seg2 + 1)) else pro.profCo
        }
        val map = Array(4) { FloatArray(4) }
        val useMap = if (bp.profileType == BEVEL_PROFILE_SUPERELLIPSE && pro.superR == PRO_LINE_R) false else makeUnitSquareMap(pro.start, pro.middle, pro.end, map)
        if (bp.vmeshMethod == BEVEL_VMESH_CUTOFF && useMap) {
            val bottom = mulM4V3(map, floatArrayOf(0f, 0f, 0f))
            val top = mulM4V3(map, floatArrayOf(1f, 1f, 0f))
            pro.height = BevelMath.lenV3V3(bottom, top)
        }
        calculateProfileSegments(pro, map, useMap, reversed, bp.seg, sp.xvals, sp.yvals, pro.profCo!!)
        if (need2) calculateProfileSegments(pro, map, useMap, reversed, bp.proSpacing.seg2, sp.xvals2, sp.yvals2, pro.profCo2!!)
    }

    fun snapToSuperellipsoid(co: FloatArray, superR: Float, midline: Boolean) {
        val r = superR
        if (r == PRO_CIRCLE_R) { BevelMath.normalize(co); return }
        val a = maxOf(0.0f, co[0]); val b = maxOf(0.0f, co[1]); val c = maxOf(0.0f, co[2])
        var x = a; var y = b; var z = c
        if (r == PRO_SQUARE_R || r == PRO_SQUARE_IN_R) {
            z = 0.0f
            x = minOf(1.0f, x); y = minOf(1.0f, y)
            if (r == PRO_SQUARE_R) {
                val dx = 1.0f - x; val dy = 1.0f - y
                if (dx < dy) { x = 1.0f; y = if (midline) 1.0f else y } else { y = 1.0f; x = if (midline) 1.0f else x }
            } else {
                if (x < y) { x = 0.0f; y = if (midline) 0.0f else y } else { y = 0.0f; x = if (midline) 0.0f else x }
            }
        } else {
            val rinv = 1.0f / r
            if (a == 0.0f) {
                if (b == 0.0f) { x = 0.0f; y = 0.0f; z = Math.pow(c.toDouble(), rinv.toDouble()).toFloat() }
                else {
                    x = 0.0f
                    y = Math.pow((1.0f / (1.0f + Math.pow((c / b).toDouble(), r.toDouble()).toFloat())).toDouble(), rinv.toDouble()).toFloat()
                    z = c * y / b
                }
            } else {
                x = Math.pow((1.0f / (1.0f + Math.pow((b / a).toDouble(), r.toDouble()).toFloat() + Math.pow((c / a).toDouble(), r.toDouble()).toFloat())).toDouble(), rinv.toDouble()).toFloat()
                y = b * x / a
                z = c * x / a
            }
        }
        co[0] = x; co[1] = y; co[2] = z
    }

    private const val FLAG_SEAM = 1
    private const val FLAG_SMOOTH = 2

    private fun hasNotSeamSharp(e: BevelEdgeHalf, flag: Int) = (flag == FLAG_SEAM && !e.e.seam) || (flag == FLAG_SMOOTH && e.e.smooth)

    fun checkEdgeDataSeamSharpEdges(bv: BevelBevVert, flag: Int) {
        var e = bv.edges[0]
        var efirst = bv.edges[0]
        while (hasNotSeamSharp(e, flag)) { e = e.next; if (e === efirst) break }
        if (hasNotSeamSharp(e, flag)) return
        efirst = e
        do {
            var count = 0
            var ne = e.next
            while (hasNotSeamSharp(ne, flag) && ne !== efirst) { if (ne.isBev) count++; ne = ne.next }
            if (ne === e || (ne === efirst && hasNotSeamSharp(efirst, flag))) break
            if (flag == FLAG_SEAM) e.rightv!!.seamLen = count else e.rightv!!.sharpLen = count
            e = ne
        } while (e !== efirst)
    }

    private fun findEdgeBetween(v1: BMesh.Vert, v2: BMesh.Vert): BMesh.Edge {
        var e = v1.e!!
        while (e.v1 !== v2 && e.v2 !== v2) e = BMesh.diskNext(e, v1)!!
        return e
    }

    fun bevelExtendEdgeDataEx(bv: BevelBevVert, flag: Int) {
        val vm = bv.vmesh
        var bcur = vm.boundstart!!
        var start = bcur
        do {
            val len = if (flag == FLAG_SEAM) bcur.seamLen else bcur.sharpLen
            if (len != 0) {
                if (vm.boundstart!!.seamLen == 0 && start === vm.boundstart) start = bcur
                val idxEnd = bcur.index + len
                for (i in bcur.index until idxEnd) {
                    var v1 = meshVert(vm, i % vm.count, 0, 0).v!!
                    for (k in 1 until vm.seg) {
                        val v2 = meshVert(vm, i % vm.count, 0, k).v!!
                        val e = findEdgeBetween(v1, v2)
                        if (flag == FLAG_SEAM) e.seam = true else e.smooth = false
                        v1 = v2
                    }
                    val v3 = meshVert(vm, (i + 1) % vm.count, 0, 0).v!!
                    val e = findEdgeBetween(v1, v3)
                    if (flag == FLAG_SEAM) e.seam = true else e.smooth = false
                    bcur = bcur.next
                }
            } else bcur = bcur.next
        } while (bcur !== start)
    }

    fun bevelExtendEdgeData(bv: BevelBevVert) {
        if (bv.vmesh.meshKind == M_TRI_FAN || bv.selcount < 2) return
        bevelExtendEdgeDataEx(bv, FLAG_SEAM)
        bevelExtendEdgeDataEx(bv, FLAG_SMOOTH)
    }

    fun bevelEdgesSharpBoundary(bp: BevelParams) {
        for (f in bp.bm.faces()) {
            if (!f.smooth) continue
            if (getFaceKind(bp, f) != F_RECON) continue
            for (l in BMesh.loopsOfFace(f)) {
                val lo = l.rNext!!
                if (lo !== l) {
                    val k = getFaceKind(bp, lo.f)
                    if (k == F_EDGE || k == F_VERT) l.e!!.smooth = false
                }
            }
        }
    }

    fun bmNormalsUpdate(bm: BMesh) {
        for (f in bm.faces()) BMesh.faceCalcNormal(f, f.no)
        for (v in bm.verts()) {
            val vn = v.no
            vn[0] = 0f; vn[1] = 0f; vn[2] = 0f
            val eFirst = v.e
            if (eFirst != null) {
                var e: BMesh.Edge = eFirst
                do {
                    val lFirst = e.l
                    if (lFirst != null) {
                        val e2 = BevelMath.sub(e.v1.co, e.v2.co)
                        BMesh.normalizeV3(e2)
                        var l: BMesh.Loop = lFirst
                        do {
                            if (l.v === v) {
                                val ePrev = l.prev!!.e!!
                                val e1 = BevelMath.sub(ePrev.v1.co, ePrev.v2.co)
                                BMesh.normalizeV3(e1)
                                var dp = BevelMath.dot(e1, e2)
                                if ((ePrev.v1 === l.prev!!.v) xor (l.e!!.v1 === l.v)) dp = -dp
                                val fac = BlendNormals.acosApprox(-dp)
                                vn[0] += l.f.no[0] * fac; vn[1] += l.f.no[1] * fac; vn[2] += l.f.no[2] * fac
                            }
                            l = l.rNext!!
                        } while (l !== lFirst)
                    }
                    e = BMesh.diskNext(e, v)!!
                } while (e !== eFirst)
                if (BMesh.normalizeV3(vn) != 0.0f) continue
            }
            BevelMath.copy(vn, v.co)
            BMesh.normalizeV3(vn)
        }
    }

    fun bmLnorSpaces(bm: BMesh): HashMap<BMesh.Loop, BlendNormals.Space> {
        val out = HashMap<BMesh.Loop, BlendNormals.Space>()
        val smoothE = HashSet<BMesh.Edge>()
        for (e in bm.edges()) {
            val la = e.l ?: continue
            val lb = la.rNext!!
            if (lb.rNext === la && la.v !== lb.v && e.smooth && la.f.smooth && lb.f.smooth) smoothE.add(e)
        }
        val tagL = HashSet<BMesh.Loop>()
        val step = arrayOfNulls<BMesh.Edge>(1)
        fun cyclic(lCurr: BMesh.Loop): Boolean {
            var p: BMesh.Loop = lCurr
            step[0] = lCurr.e
            tagL.add(p)
            while (true) {
                val nx = BMesh.vertStepFanLoop(p, step)
                if (nx == null || step[0] !in smoothE) return false
                if (nx in tagL) return nx === lCurr
                tagL.add(nx)
                p = nx
            }
        }
        fun dir(a: FloatArray, b: FloatArray): FloatArray { val r = BevelMath.sub(a, b); BMesh.normalizeV3(r); return r }
        for (f in bm.faces()) {
            for (lCurr in BMesh.loopsOfFace(f)) {
                if (lCurr.e!! in smoothE && (lCurr in tagL || !cyclic(lCurr))) continue
                if (lCurr.e!! !in smoothE && lCurr.prev!!.e!! !in smoothE) {
                    val vc = dir(lCurr.next!!.v.co, lCurr.v.co)
                    val vp = dir(lCurr.prev!!.v.co, lCurr.v.co)
                    out[lCurr] = BlendNormals.defineSpace(IntArray(0), lCurr.f.no.copyOf(), vc, vp, emptyList())
                    continue
                }
                val pivot = lCurr.v
                val eOrg = lCurr.e!!
                val lnor = FloatArray(3)
                val edgeVecs = ArrayList<FloatArray>()
                val fan = ArrayList<BMesh.Loop>()
                var lp: BMesh.Loop = lCurr
                step[0] = lp.e
                val vecOrg = dir(lp.next!!.v.co, pivot.co)
                var vecCurr = vecOrg
                edgeVecs.add(vecOrg)
                var vecNext: FloatArray
                while (true) {
                    val nx = BMesh.vertStepFanLoop(lp, step)
                    if (nx == null) step[0] = if (lp.e === step[0]) lp.prev!!.e else lp.e
                    val en = step[0]!!
                    vecNext = dir(if (en.v1 === pivot) en.v2.co else en.v1.co, pivot.co)
                    val fac = BlendNormals.acosApprox(BevelMath.dot(vecNext, vecCurr))
                    lnor[0] += lp.f.no[0] * fac; lnor[1] += lp.f.no[1] * fac; lnor[2] += lp.f.no[2] * fac
                    fan.add(lp)
                    if (en !== eOrg) edgeVecs.add(vecNext)
                    if (en !in smoothE || en === eOrg) break
                    vecCurr = vecNext
                    lp = nx!!
                }
                if (BMesh.normalizeV3(lnor) == 0.0f) BevelMath.copy(lnor, lp.f.no)
                val sp = BlendNormals.defineSpace(IntArray(0), lnor, vecOrg, vecNext, edgeVecs)
                for (l in fan) out[l] = sp
            }
        }
        return out
    }

    fun bevelHardenNormals(bp: BevelParams) {
        if (bp.offset == 0.0f || !bp.hardenNormals) return
        val bm = bp.bm
        bmNormalsUpdate(bm)
        if (!bp.inputCustomNormals) bevelEdgesSharpBoundary(bp)
        val step = arrayOfNulls<BMesh.Edge>(1)
        for (f in bm.faces()) {
            val fkind = getFaceKind(bp, f)
            if (fkind == F_ORIG || fkind == F_RECON) continue
            for (l in BMesh.loopsOfFace(f)) {
                step[0] = l.prev!!.e
                val lprev = BMesh.vertStepFanLoop(l, step)
                step[0] = l.e
                val lnext = BMesh.vertStepFanLoop(l, step)
                val fprevkind = if (lprev != null) getFaceKind(bp, lprev.f) else F_NONE
                val fnextkind = if (lnext != null) getFaceKind(bp, lnext.f) else F_NONE
                var pnorm: FloatArray? = null
                var sum = false
                if (fkind == F_EDGE) {
                    if (fprevkind == F_EDGE && l in bp.longTag) { pnorm = BevelMath.add(f.no, lprev!!.f.no); sum = true }
                    else if (fnextkind == F_EDGE && lnext!! in bp.longTag) { pnorm = BevelMath.add(f.no, lnext.f.no); sum = true }
                    else if (fprevkind == F_RECON && l in bp.longTag) pnorm = lprev!!.f.no
                    else if (fnextkind == F_RECON && l.prev!! in bp.longTag) pnorm = lnext!!.f.no
                } else if (fkind == F_VERT) {
                    if (fprevkind == F_VERT && fnextkind == F_VERT) pnorm = l.v.no
                    else if (fprevkind == F_RECON) pnorm = lprev!!.f.no
                    else if (fnextkind == F_RECON) pnorm = lnext!!.f.no
                    else {
                        val lprevprev = if (lprev != null) { step[0] = lprev.prev!!.e; BMesh.vertStepFanLoop(lprev, step) } else null
                        val lnextnext = if (lnext != null) { step[0] = lnext.e; BMesh.vertStepFanLoop(lnext, step) } else null
                        val fpp = if (lprevprev != null) getFaceKind(bp, lprevprev.f) else F_NONE
                        val fnn = if (lnextnext != null) getFaceKind(bp, lnextnext.f) else F_NONE
                        if (fprevkind == F_EDGE && fpp == F_RECON) pnorm = lprevprev!!.f.no
                        else if (fprevkind == F_EDGE && fnextkind == F_VERT && fpp == F_EDGE) { pnorm = BevelMath.add(lprev!!.f.no, lprevprev!!.f.no); sum = true }
                        else if (fnextkind == F_EDGE && fprevkind == F_VERT && fnn == F_EDGE) { pnorm = BevelMath.add(lnext!!.f.no, lnextnext!!.f.no); sum = true }
                    }
                }
                if (pnorm != null) {
                    if (sum) BMesh.normalizeV3(pnorm)
                    bp.harden[l] = pnorm.copyOf()
                }
            }
        }
    }

    fun bevelSetWeightedNormalFaceStrength(bp: BevelParams) {
        val mode = bp.faceStrengthMode
        bp.bm.hasStrength = true
        for (f in bp.bm.faces()) {
            val strength: Int
            val doSet: Boolean
            when (getFaceKind(bp, f)) {
                F_VERT -> { strength = FACE_STRENGTH_WEAK; doSet = mode >= BEVEL_FACE_STRENGTH_NEW }
                F_EDGE -> { strength = FACE_STRENGTH_MEDIUM; doSet = mode >= BEVEL_FACE_STRENGTH_NEW }
                F_RECON -> { strength = FACE_STRENGTH_STRONG; doSet = mode >= BEVEL_FACE_STRENGTH_AFFECTED }
                F_ORIG -> { strength = FACE_STRENGTH_STRONG; doSet = mode == BEVEL_FACE_STRENGTH_ALL }
                else -> { strength = 0; doSet = false }
            }
            if (doSet) f.strength = strength
        }
    }

    fun setBoundVertSeams(bv: BevelBevVert, markSeam: Boolean, markSharp: Boolean) {
        bv.anySeam = false
        var v = bv.vmesh.boundstart!!
        do {
            v.anySeam = false
            var e = v.efirst
            while (e != null) {
                v.anySeam = v.anySeam or e.isSeam
                if (e === v.elast) break
                e = e.next
            }
            bv.anySeam = bv.anySeam or v.anySeam
            v = v.next
        } while (v !== bv.vmesh.boundstart)
        if (markSeam) checkEdgeDataSeamSharpEdges(bv, FLAG_SEAM)
        if (markSharp) checkEdgeDataSeamSharpEdges(bv, FLAG_SMOOTH)
    }

    fun ehOnPlane(e: BevelEdgeHalf): Boolean {
        val fp = e.fprev; val fn = e.fnext
        if (fp != null && fn != null) {
            val d = dot(fp.no, fn.no)
            if (kotlin.math.abs(d + 1.0f) <= BEVEL_EPSILON_BIG || kotlin.math.abs(d - 1.0f) <= BEVEL_EPSILON_BIG) return true
        }
        return false
    }

    fun calculateVmProfiles(bp: BevelParams, bv: BevelBevVert, vm: BevelVMesh) {
        var bndv = vm.boundstart!!
        do {
            if (!bndv.profile.specialParams) setProfileParams(bp, bv, bndv)
            var miter = false
            var reverse = false
            if (bp.profileType == BEVEL_PROFILE_CUSTOM) {
                miter = bndv.isArcStart || bndv.isPatchStart
                reverse = !bndv.isProfileStart && !miter
            }
            calculateProfile(bp, bndv, reverse, miter)
            bndv = bndv.next
        } while (bndv !== vm.boundstart)
    }

    fun buildBoundaryVertexOnly(bp: BevelParams, bv: BevelBevVert, construct: Boolean) {
        val vm = bv.vmesh
        val efirst = bv.edges[0]
        var e = efirst
        do {
            val co = slideDist(e.e, bv.v, e.offsetL)
            if (construct) {
                val v = addNewBoundVert(vm, co)
                v.efirst = e; v.elast = e
                e.leftv = v; e.rightv = v
            } else adjustBoundVert(e.leftv!!, co)
            e = e.next
        } while (e !== efirst)
        if (construct) {
            setBoundVertSeams(bv, bp.markSeam, bp.markSharp)
            vm.meshKind = if (vm.count == 2) M_NONE else if (bp.seg == 1) M_POLY else M_ADJ
        }
    }

    fun buildBoundaryTerminalEdge(bp: BevelParams, bv: BevelBevVert, efirst: BevelEdgeHalf, construct: Boolean) {
        val vm = bv.vmesh
        var e = efirst
        var co: FloatArray
        if (bv.edgecount == 2) {
            var no = e.fprev?.no ?: e.fnext?.no
            co = offsetInPlane(e, no, true)
            if (construct) {
                val b = addNewBoundVert(vm, co)
                b.efirst = e; b.elast = e; b.ebev = e
                e.leftv = b
            } else adjustBoundVert(e.leftv!!, co)
            no = e.fnext?.no ?: e.fprev?.no
            co = offsetInPlane(e, no, false)
            if (construct) {
                val b = addNewBoundVert(vm, co)
                b.efirst = e; b.elast = e
                e.rightv = b
            } else adjustBoundVert(e.rightv!!, co)
            co = slideDist(e.next.e, bv.v, e.offsetL)
            if (construct) {
                val b = addNewBoundVert(vm, co)
                b.efirst = e.next; b.elast = e.next
                e.next.leftv = b; e.next.rightv = b
                setBoundVertSeams(bv, bp.markSeam, bp.markSharp)
            } else adjustBoundVert(e.next.leftv!!, co)
        } else {
            val legSlide = bp.offsetType == BEVEL_AMT_PERCENT || bp.offsetType == BEVEL_AMT_ABSOLUTE
            co = FloatArray(3)
            if (legSlide) co = slideDist(e.prev.e, bv.v, e.offsetL) else offsetMeet(bp, e.prev, e, bv.v, e.fprev, false, co, null)
            if (construct) {
                val b = addNewBoundVert(vm, co)
                b.efirst = e.prev; b.elast = e; b.ebev = e
                e.leftv = b
                e.prev.leftv = b; e.prev.rightv = b
            } else adjustBoundVert(e.leftv!!, co)
            e = e.next
            co = FloatArray(3)
            if (legSlide) co = slideDist(e.e, bv.v, e.prev.offsetR) else offsetMeet(bp, e.prev, e, bv.v, e.fprev, false, co, null)
            if (construct) {
                val b = addNewBoundVert(vm, co)
                b.efirst = e.prev; b.elast = e
                e.leftv = b; e.rightv = b
                e.prev.rightv = b
            } else adjustBoundVert(e.leftv!!, co)
            var d = efirst.offsetLSpec
            if (bp.profileType == BEVEL_PROFILE_CUSTOM || bp.profile < 0.25f) d *= kotlin.math.sqrt(2.0f)
            e = e.next
            while (e.next !== efirst) {
                co = slideDist(e.e, bv.v, d)
                if (construct) {
                    val b = addNewBoundVert(vm, co)
                    b.efirst = e; b.elast = e
                    e.leftv = b; e.rightv = b
                } else adjustBoundVert(e.leftv!!, co)
                e = e.next
            }
        }
        if (bv.edgecount >= 3) {
            val bndv = vm.boundstart!!
            setProfileParams(bp, bv, bndv)
            moveProfilePlane(bndv, bv.v)
        }
        if (construct) {
            setBoundVertSeams(bv, bp.markSeam, bp.markSharp)
            if (vm.count == 2 && bv.edgecount == 3) vm.meshKind = M_NONE
            else if (vm.count == 3) {
                var useTriFan = true
                if (bp.profileType == BEVEL_PROFILE_CUSTOM) {
                    val b1 = efirst.leftv!!
                    val b2 = efirst.rightv!!.next
                    val pn = b1.profile.planeNo
                    val side = dot(pn, b2.nv.co) + -dot(pn, b1.profile.planeCo)
                    val lenSq = dot(pn, pn)
                    val fac = side / lenSq
                    if (lenSq * (fac * fac) < BEVEL_EPSILON_BIG) useTriFan = false
                }
                vm.meshKind = if (useTriFan) M_TRI_FAN else M_POLY
            } else vm.meshKind = M_POLY
        }
    }

    fun adjustMiterCoords(bp: BevelParams, bv: BevelBevVert, emiter: BevelEdgeHalf) {
        val v1 = emiter.rightv!!
        val v3 = if (bp.miterOuter == BEVEL_MITER_PATCH) v1.next.next else v1.next
        val v1prev = v1.prev
        val v3next = v3.next
        val co2 = v1.nv.co.copyOf()
        if (v1.isArcStart) copy(v1.profile.middle, co2)
        var vother = BMesh.otherVert(emiter.e, bv.v)!!
        var dir = sub(bv.v.co, vother.co)
        BevelMath.normalize(dir)
        val d = bp.offset / (bp.seg / 2.0f)
        var lineP = BevelMath.madd(co2, dir, d)
        val co1 = isectLinePlane(co2, lineP, v1prev.nv.co, dir) ?: lineP
        adjustBoundVert(v1, co1)
        val eo = v3.elast!!
        vother = BMesh.otherVert(eo.e, bv.v)!!
        dir = sub(bv.v.co, vother.co)
        BevelMath.normalize(dir)
        lineP = BevelMath.madd(co2, dir, d)
        val co3 = isectLinePlane(co2, lineP, v3next.nv.co, dir) ?: lineP
        adjustBoundVert(v3, co3)
    }

    fun adjustMiterInnerCoords(bp: BevelParams, bv: BevelBevVert, emiter: BevelEdgeHalf?) {
        val vstart = bv.vmesh.boundstart!!
        var v = vstart
        do {
            if (v.isArcStart) {
                val v3 = v.next
                var e = v.efirst!!
                if (e !== emiter) {
                    val co = v.nv.co.copyOf()
                    var vother = BMesh.otherVert(e.e, bv.v)!!
                    var dir = sub(vother.co, bv.v.co)
                    BevelMath.normalize(dir)
                    copy(v.nv.co, BevelMath.madd(co, dir, bp.spread))
                    e = v3.elast!!
                    vother = BMesh.otherVert(e.e, bv.v)!!
                    dir = sub(vother.co, bv.v.co)
                    BevelMath.normalize(dir)
                    copy(v3.nv.co, BevelMath.madd(co, dir, bp.spread))
                }
                v = v3.next
            } else v = v.next
        } while (v !== vstart)
    }

    fun buildBoundary(bp: BevelParams, bv: BevelBevVert, construct: Boolean) {
        if (bv.edgecount <= 1) return
        if (bp.affectType == BEVEL_AFFECT_VERTICES) { buildBoundaryVertexOnly(bp, bv, construct); return }
        val vm = bv.vmesh
        val efirst = nextBev(bv, null)!!
        if (bv.selcount == 1) { buildBoundaryTerminalEdge(bp, bv, efirst, construct); return }
        val miterOuter = if (bv.selcount >= 3) bp.miterOuter else BEVEL_MITER_SHARP
        val miterInner = bp.miterInner
        var emiter: BevelEdgeHalf? = null
        var e = efirst
        do {
            var eon: BevelEdgeHalf? = null
            var inPlane = 0
            var notInPlane = 0
            var enip: BevelEdgeHalf? = null
            var eip: BevelEdgeHalf? = null
            var e2 = e.next
            while (!e2.isBev) {
                if (ehOnPlane(e2)) { inPlane++; eip = e2 } else { notInPlane++; enip = e2 }
                e2 = e2.next
            }
            val r = FloatArray(1)
            val co = FloatArray(3)
            if (inPlane == 0 && notInPlane == 0) offsetMeet(bp, e, e2, bv.v, e.fnext, false, co, null)
            else if (notInPlane > 0) {
                if (bp.loopSlide && notInPlane == 1 && goodOffsetOnEdgeBetween(e, e2, enip!!, bv.v)) {
                    if (offsetOnEdgeBetween(bp, e, e2, enip, bv.v, co, r)) eon = enip
                } else offsetMeet(bp, e, e2, bv.v, null, true, co, eip)
            } else {
                if (bp.loopSlide && inPlane == 1 && goodOffsetOnEdgeBetween(e, e2, eip!!, bv.v)) {
                    if (offsetOnEdgeBetween(bp, e, e2, eip, bv.v, co, r)) eon = eip
                } else offsetMeet(bp, e, e2, bv.v, e.fnext, false, co, null)
            }
            if (construct) {
                val v = addNewBoundVert(vm, co)
                v.efirst = e; v.elast = e2; v.ebev = e2; v.eon = eon
                if (eon != null) v.sinratio = r[0]
                e.rightv = v
                e2.leftv = v
                var e3 = e.next
                while (e3 !== e2) { e3.leftv = v; e3.rightv = v; e3 = e3.next }
                val angKind = edgesAngleKind(e, e2, bv.v)
                if ((miterOuter != BEVEL_MITER_SHARP && emiter == null && angKind == ANGLE_LARGER) || (miterInner != BEVEL_MITER_SHARP && angKind == ANGLE_SMALLER)) {
                    if (angKind == ANGLE_LARGER) emiter = e
                    val v1 = v
                    v1.ebev = null
                    val v2 = if (angKind == ANGLE_LARGER && miterOuter == BEVEL_MITER_PATCH) addNewBoundVert(vm, co) else null
                    val v3 = addNewBoundVert(vm, co)
                    v3.ebev = e2; v3.efirst = e2; v3.elast = e2; v3.eon = null
                    e2.leftv = v3
                    if (angKind == ANGLE_LARGER && miterOuter == BEVEL_MITER_PATCH) {
                        v1.isPatchStart = true
                        v2!!.eon = v1.eon
                        v2.sinratio = v1.sinratio
                        v2.ebev = null
                        v1.eon = null
                        v1.sinratio = 1.0f
                        v1.elast = e
                        if (e.next === e2) { v2.efirst = null; v2.elast = null }
                        else {
                            v2.efirst = e.next
                            var x = e.next
                            while (x !== e2) { x.leftv = v2; x.rightv = v2; v2.elast = x; x = x.next }
                        }
                    } else {
                        v1.isArcStart = true
                        copy(v1.profile.middle, co)
                        if (e.next === e2) v1.elast = v1.efirst
                        else {
                            val between = inPlane + notInPlane
                            val bet2 = between / 2
                            val betodd = between % 2 == 1
                            var i = 0
                            var x = e.next
                            while (x !== e2) {
                                v1.elast = x
                                x.profileIndex = if (i < bet2) 0 else if (betodd && i == bet2) bp.seg / 2 else bp.seg
                                i++
                                x = x.next
                            }
                        }
                    }
                }
            } else {
                val angKind = edgesAngleKind(e, e2, bv.v)
                if ((miterOuter != BEVEL_MITER_SHARP && emiter == null && angKind == ANGLE_LARGER) || (miterInner != BEVEL_MITER_SHARP && angKind == ANGLE_SMALLER)) {
                    if (angKind == ANGLE_LARGER) emiter = e
                    val v1 = e.rightv!!
                    val v2: BevelBoundVert?
                    val v3: BevelBoundVert
                    if (angKind == ANGLE_LARGER && miterOuter == BEVEL_MITER_PATCH) { v2 = v1.next; v3 = v2.next } else { v2 = null; v3 = v1.next }
                    adjustBoundVert(v1, co)
                    if (v2 != null) adjustBoundVert(v2, co)
                    adjustBoundVert(v3, co)
                } else adjustBoundVert(e.rightv!!, co)
            }
            e = e2
        } while (e !== efirst)
        if (miterInner != BEVEL_MITER_SHARP) adjustMiterInnerCoords(bp, bv, emiter)
        if (emiter != null) adjustMiterCoords(bp, bv, emiter!!)
        if (construct) {
            setBoundVertSeams(bv, bp.markSeam, bp.markSharp)
            vm.meshKind = if (vm.count == 2) M_NONE else if (efirst.seg == 1) M_POLY else if (bp.vmeshMethod == BEVEL_VMESH_CUTOFF) M_CUTOFF else M_ADJ
        }
    }

    fun nextEdgehalfBev(bp: BevelParams, start: BevelEdgeHalf, towardBv: Boolean, bvRef: Array<BevelBevVert?>): BevelEdgeHalf? {
        if (!towardBv) {
            val r = findOtherEndEdgeHalf(bp, start)
            bvRef[0] = r?.second
            return r?.first
        }
        val bv = bvRef[0]!!
        if (bv.selcount == 1) return null
        if (bv.selcount == 2) {
            var ne = start
            do { ne = ne.next } while (!ne.isBev)
            return ne
        }
        val dirStart = if (start.e.v1 === bv.v) sub(start.e.v1.co, start.e.v2.co) else sub(start.e.v2.co, start.e.v1.co)
        BevelMath.normalize(dirStart)
        var ne = start.next
        var second = 0.0f
        var best = 0.0f
        var next: BevelEdgeHalf? = null
        while (ne !== start) {
            if (!ne.isBev) { ne = ne.next; continue }
            val dn = if (ne.e.v2 === bv.v) sub(ne.e.v1.co, ne.e.v2.co) else sub(ne.e.v2.co, ne.e.v1.co)
            BevelMath.normalize(dn)
            val nd = dot(dn, dirStart)
            if (nd > best) { second = best; best = nd; next = ne } else if (nd > second) second = nd
            ne = ne.next
        }
        if (next != null && kotlin.math.abs(best - second) <= BEVEL_SMALL_ANG_DOT) return null
        return next
    }

    fun regularizeProfileOrientation(bp: BevelParams, bme: BMesh.Edge) {
        val startBv = findBevvert(bp, bme.v1)!!
        val start = findEdgeHalf(startBv, bme)!!
        if (!start.isBev || start.visitedRpo) return
        val rightHighest = start.leftv!!.nv.co[2] < start.rightv!!.nv.co[2]
        start.leftv!!.isProfileStart = rightHighest
        start.visitedRpo = true
        for (i in 0 until 2) {
            val bvRef = arrayOf<BevelBevVert?>(startBv)
            var toward = i == 0
            var eh = nextEdgehalfBev(bp, start, toward, bvRef)
            while (eh != null && !eh.visitedRpo) {
                eh.leftv!!.isProfileStart = if (i == 0) toward xor rightHighest else (!toward) xor rightHighest
                toward = !toward
                eh.visitedRpo = true
                eh = nextEdgehalfBev(bp, eh, toward, bvRef)
            }
        }
    }

    fun solveLeastSquares(nrows: Int, ncols: Int, entries: List<Triple<Int, Int, Double>>, rhs: DoubleArray): DoubleArray {
        val a = Array(nrows) { DoubleArray(ncols) }
        for ((r, c, v) in entries) a[r][c] += v
        val m = Array(ncols) { DoubleArray(ncols) }
        val b = DoubleArray(ncols)
        for (i in 0 until ncols) {
            for (j in 0 until ncols) { var x = 0.0; for (r in 0 until nrows) x += a[r][i] * a[r][j]; m[i][j] = x }
            var y = 0.0
            for (r in 0 until nrows) y += a[r][i] * rhs[r]
            b[i] = y
        }
        val n = ncols
        val perm = IntArray(n) { it }
        for (k in 0 until n) {
            var piv = k
            for (i in k + 1 until n) if (kotlin.math.abs(m[i][k]) > kotlin.math.abs(m[piv][k])) piv = i
            if (piv != k) { val t = m[k]; m[k] = m[piv]; m[piv] = t; val tb = b[k]; b[k] = b[piv]; b[piv] = tb; val tp = perm[k]; perm[k] = perm[piv]; perm[piv] = tp }
            val d = m[k][k]
            if (d == 0.0) continue
            for (i in k + 1 until n) {
                val f = m[i][k] / d
                if (f == 0.0) continue
                for (j in k until n) m[i][j] -= f * m[k][j]
                b[i] -= f * b[k]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = b[i]
            for (j in i + 1 until n) sum -= m[i][j] * x[j]
            x[i] = if (m[i][i] != 0.0) sum / m[i][i] else 0.0
        }
        return x
    }

    fun adjustTheCycleOrChain(vstart: BevelBoundVert, iscycle: Boolean) {
        var np = 0
        var v: BevelBoundVert? = vstart
        do { np++; v = v!!.adjchain } while (v != null && v !== vstart)
        val nrows = if (iscycle) 3 * np else 3 * np - 3
        val entries = ArrayList<Triple<Int, Int, Double>>()
        val rhs = DoubleArray(nrows)
        v = vstart
        var i = 0
        val weight = BEVEL_MATCH_SPEC_WEIGHT
        do {
            val cur = v!!
            if (iscycle || i < np - 1) {
                val eright = cur.efirst!!
                val enextleft = cur.adjchain!!.elast!!
                entries.add(Triple(i, i, 1.0))
                if (iscycle) entries.add(Triple(if (i > 0) i - 1 else np - 1, i, -cur.sinratio.toDouble()))
                else if (i > 0) entries.add(Triple(i - 1, i, -cur.sinratio.toDouble()))
                var row = if (iscycle) np + 2 * i else np - 1 + 2 * i
                entries.add(Triple(row, i, weight))
                rhs[row] += weight * eright.offsetR
                row += 1
                entries.add(Triple(row, if (i == np - 1) 0 else i + 1, weight * cur.adjchain!!.sinratio))
                rhs[row] += weight * enextleft.offsetL
            } else {
                entries.add(Triple(i - 1, i, -1.0))
            }
            i++
            v = cur.adjchain
        } while (v != null && v !== vstart)
        val sol = solveLeastSquares(nrows, np, entries, rhs)
        v = vstart
        i = 0
        do {
            val cur = v!!
            val value = sol[i]
            if (iscycle || i < np - 1) {
                cur.efirst!!.offsetR = value.toFloat()
                if (iscycle || cur !== vstart) cur.elast!!.offsetL = (cur.sinratio * value).toFloat()
            } else cur.elast!!.offsetL = value.toFloat()
            i++
            v = cur.adjchain
        } while (v != null && v !== vstart)
    }

    fun adjustOffsets(bp: BevelParams) {
        for (bmv in bp.bm.verts()) {
            if (!bmv.tag) continue
            val bv = findBevvert(bp, bmv) ?: continue
            var vanchor = bv.vmesh.boundstart!!
            do {
                if (!vanchor.visited && vanchor.eon != null) {
                    var v = vanchor
                    var vchainstart = vanchor
                    var vchainend = vanchor
                    var iscycle = false
                    var chainlen = 1
                    while (v.eon != null && !v.visited && !iscycle) {
                        v.visited = true
                        val ef = v.efirst ?: break
                        val en = findOtherEndEdgeHalf(bp, ef) ?: break
                        val vnext = en.first.leftv!!
                        v.adjchain = vnext
                        vchainend = vnext
                        chainlen++
                        if (vnext.visited) {
                            if (vnext !== vchainstart) break
                            adjustTheCycleOrChain(vchainstart, true)
                            iscycle = true
                        }
                        v = vnext
                    }
                    if (!iscycle) {
                        v.adjchain = null
                        v = vchainstart
                        do {
                            v.visited = true
                            val el = v.elast ?: break
                            val en = findOtherEndEdgeHalf(bp, el) ?: break
                            val vnext = en.first.rightv!!
                            vnext.adjchain = v
                            chainlen++
                            vchainstart = vnext
                            v = vnext
                        } while (!v.visited && v.eon != null)
                        if (chainlen >= 3 && vchainstart.eon == null && vchainend.eon == null) adjustTheCycleOrChain(vchainstart, false)
                    }
                }
                vanchor = vanchor.next
            } while (vanchor !== bv.vmesh.boundstart)
        }
        for (bmv in bp.bm.verts()) {
            if (bmv.tag) findBevvert(bp, bmv)?.let { buildBoundary(bp, it, false) }
        }
    }

    fun pipeTest(bv: BevelBevVert): BevelBoundVert? {
        val vm = bv.vmesh
        if (vm.count < 3 || vm.count > 4 || bv.selcount < 3 || bv.selcount > 4) return null
        var epipe: BevelEdgeHalf? = null
        var v1 = vm.boundstart!!
        var dir1 = FloatArray(3)
        do {
            val v2 = v1.next
            val v3 = v2.next
            if (v1.ebev != null && v2.ebev != null && v3.ebev != null) {
                dir1 = sub(bv.v.co, BMesh.otherVert(v1.ebev!!.e, bv.v)!!.co)
                val dir3 = sub(BMesh.otherVert(v3.ebev!!.e, bv.v)!!.co, bv.v.co)
                BevelMath.normalize(dir1); BevelMath.normalize(dir3)
                if (BevelMath.angleNormalized(dir1, dir3) < BEVEL_EPSILON_ANG) { epipe = v1.ebev; break }
            }
            v1 = v1.next
        } while (v1 !== vm.boundstart)
        if (epipe == null) return null
        for (e in bv.edges) {
            val fn = e.fnext
            if (fn != null && kotlin.math.abs(dot(dir1, fn.no)) > BEVEL_EPSILON_BIG) return null
        }
        return v1
    }

    fun newAdjVmesh(count: Int, seg: Int, bounds: BevelBoundVert?): BevelVMesh {
        val vm = BevelVMesh()
        vm.count = count
        vm.seg = seg
        vm.boundstart = bounds
        vm.mesh = Array(count * (1 + seg / 2) * (1 + seg)) { BevelNewVert() }
        vm.meshKind = M_ADJ
        return vm
    }

    fun meshVertCanon(vm: BevelVMesh, i: Int, j: Int, k: Int): BevelNewVert {
        val n = vm.count
        val ns = vm.seg
        val ns2 = ns / 2
        val odd = ns % 2
        if (odd == 0 && j == ns2 && k == ns2) return meshVert(vm, 0, j, k)
        if (j <= ns2 - 1 + odd && k <= ns2) return meshVert(vm, i, j, k)
        if (k <= ns2) return meshVert(vm, (i + n - 1) % n, k, ns - j)
        return meshVert(vm, (i + 1) % n, ns - k, j)
    }

    fun isCanon(vm: BevelVMesh, i: Int, j: Int, k: Int): Boolean {
        val ns2 = vm.seg / 2
        if (vm.seg % 2 == 1) return j <= ns2 && k <= ns2
        return (j < ns2 && k <= ns2) || (j == ns2 && k == ns2 && i == 0)
    }

    fun vmeshCopyEquivVerts(vm: BevelVMesh) {
        val n = vm.count
        val ns = vm.seg
        val ns2 = ns / 2
        for (i in 0 until n) for (j in 0..ns2) for (k in 0..ns) {
            if (isCanon(vm, i, j, k)) continue
            val v1 = meshVert(vm, i, j, k)
            val v0 = meshVertCanon(vm, i, j, k)
            copy(v1.co, v0.co)
            v1.v = v0.v
        }
    }

    fun vmeshCenter(vm: BevelVMesh): FloatArray {
        val n = vm.count
        val ns2 = vm.seg / 2
        if (vm.seg % 2 != 0) {
            val c = FloatArray(3)
            for (i in 0 until n) { val p = meshVert(vm, i, ns2, ns2).co; c[0] += p[0]; c[1] += p[1]; c[2] += p[2] }
            BevelMath.mulFl(c, 1.0f / n.toFloat())
            return c
        }
        return meshVert(vm, 0, ns2, ns2).co.copyOf()
    }

    fun avg4(v0: BevelNewVert, v1: BevelNewVert, v2: BevelNewVert, v3: BevelNewVert): FloatArray {
        val c = add(v0.co, v1.co)
        val c2 = add(c, v2.co)
        val c3 = add(c2, v3.co)
        BevelMath.mulFl(c3, 0.25f)
        return c3
    }

    fun sabinGamma(n: Int): Float {
        if (n < 3) return 0.0f
        if (n == 3) return 0.065247584f
        if (n == 4) return 0.25f
        if (n == 5) return 0.401983447f
        if (n == 6) return 0.523423277f
        val k = Math.cos(Math.PI / n.toDouble())
        val k2 = k * k
        val k4 = k2 * k2
        val k6 = k4 * k2
        val y = Math.pow(Math.sqrt(3.0) * Math.sqrt(64.0 * k6 - 144.0 * k4 + 135.0 * k2 - 27.0) + 9.0 * k, 1.0 / 3.0)
        val x = 0.480749856769136 * y - (0.231120424783545 * (12.0 * k2 - 9.0)) / y
        return ((k * x + 2.0 * k2 - 1.0) / (x * x * (k * x + 1.0))).toFloat()
    }

    fun fillVmeshFracs(vm: BevelVMesh, frac: FloatArray, i: Int) {
        var total = 0.0f
        val ns = vm.seg
        frac[0] = 0.0f
        for (k in 0 until ns) {
            total += BevelMath.lenV3V3(meshVert(vm, i, 0, k).co, meshVert(vm, i, 0, k + 1).co)
            frac[k + 1] = total
        }
        if (total > 0.0f) for (k in 1..ns) frac[k] /= total else frac[ns] = 1.0f
    }

    fun fillProfileFracs(bp: BevelParams, bndv: BevelBoundVert, frac: FloatArray, ns: Int) {
        var total = 0.0f
        frac[0] = 0.0f
        var co = bndv.nv.co.copyOf()
        val next = FloatArray(3)
        for (k in 0 until ns) {
            getProfilePoint(bp, bndv.profile, k + 1, ns, next)
            total += BevelMath.lenV3V3(co, next)
            frac[k + 1] = total
            co = next.copyOf()
        }
        if (total > 0.0f) for (k in 1..ns) frac[k] /= total else frac[ns] = 1.0f
    }

    fun interpRange(frac: FloatArray, n: Int, f: Float, rest: FloatArray): Int {
        for (i0 in 0 until n) {
            var i = i0
            if (f <= frac[i + 1]) {
                val r = f - frac[i]
                rest[0] = if (r == 0f) 0.0f else r / (frac[i + 1] - frac[i])
                if (i == n - 1 && rest[0] == 1.0f) { i = n; rest[0] = 0.0f }
                return i
            }
        }
        rest[0] = 0.0f
        return n
    }

    fun interpBilinearQuad(q: Array<FloatArray>, u: Float, v: Float): FloatArray {
        val res = BevelMath.scaled(q[0], (1 - u) * (1 - v))
        var r = add(res, BevelMath.scaled(q[1], u * (1 - v)))
        r = add(r, BevelMath.scaled(q[2], u * v))
        r = add(r, BevelMath.scaled(q[3], (1 - u) * v))
        return r
    }

    fun interpVmesh(bp: BevelParams, vmIn: BevelVMesh, nseg: Int): BevelVMesh {
        val nBndv = vmIn.count
        val nsIn = vmIn.seg
        val nseg2 = nseg / 2
        val odd = nseg % 2
        val vmOut = newAdjVmesh(nBndv, nseg, vmIn.boundstart)
        var prevFrac = FloatArray(nsIn + 1)
        var frac = FloatArray(nsIn + 1)
        var newFrac = FloatArray(nseg + 1)
        var prevNewFrac = FloatArray(nseg + 1)
        fillVmeshFracs(vmIn, prevFrac, nBndv - 1)
        var bndv = vmIn.boundstart!!
        fillProfileFracs(bp, bndv.prev, prevNewFrac, nseg)
        val rk = FloatArray(1); val rkp = FloatArray(1)
        for (i in 0 until nBndv) {
            frac = FloatArray(nsIn + 1)
            newFrac = FloatArray(nseg + 1)
            fillVmeshFracs(vmIn, frac, i)
            fillProfileFracs(bp, bndv, newFrac, nseg)
            for (j in 0..nseg2 - 1 + odd) for (k in 0..nseg2) {
                val kIn = interpRange(frac, nsIn, newFrac[k], rk)
                val restk = rk[0]
                val kInPrev = interpRange(prevFrac, nsIn, prevNewFrac[nseg - j], rkp)
                var jIn = nsIn - kInPrev
                var restj = -rkp[0]
                if (restj > -BEVEL_EPSILON) restj = 0.0f else { jIn -= 1; restj = 1.0f + restj }
                val co: FloatArray
                if (restj < BEVEL_EPSILON && restk < BEVEL_EPSILON) co = meshVertCanon(vmIn, i, jIn, kIn).co.copyOf()
                else {
                    val j0inc = if (restj < BEVEL_EPSILON || jIn == nsIn) 0 else 1
                    val k0inc = if (restk < BEVEL_EPSILON || kIn == nsIn) 0 else 1
                    val quad = arrayOf(
                        meshVertCanon(vmIn, i, jIn, kIn).co.copyOf(), meshVertCanon(vmIn, i, jIn, kIn + k0inc).co.copyOf(),
                        meshVertCanon(vmIn, i, jIn + j0inc, kIn + k0inc).co.copyOf(), meshVertCanon(vmIn, i, jIn + j0inc, kIn).co.copyOf()
                    )
                    co = interpBilinearQuad(quad, restk, restj)
                }
                copy(meshVert(vmOut, i, j, k).co, co)
            }
            bndv = bndv.next
            prevFrac = frac
            prevNewFrac = newFrac
        }
        if (odd == 0) copy(meshVert(vmOut, 0, nseg2, nseg2).co, vmeshCenter(vmIn))
        vmeshCopyEquivVerts(vmOut)
        return vmOut
    }

    fun cubicSubdiv(bp: BevelParams, vmIn: BevelVMesh): BevelVMesh {
        val nb = vmIn.count
        val nsIn = vmIn.seg
        val nsIn2 = nsIn / 2
        val nsOut = 2 * nsIn
        val vmOut = newAdjVmesh(nb, nsOut, vmIn.boundstart)
        fun smooth(co: FloatArray, co1: FloatArray, co2: FloatArray): FloatArray {
            var acc = add(co1, co2)
            acc = BevelMath.madd(acc, co, -2.0f)
            return BevelMath.madd(co, acc, -1.0f / 6.0f)
        }
        for (i in 0 until nb) {
            copy(meshVert(vmOut, i, 0, 0).co, meshVert(vmIn, i, 0, 0).co)
            for (k in 1 until nsIn) {
                var co = meshVert(vmIn, i, 0, k).co.copyOf()
                if (bp.profileType != BEVEL_PROFILE_CUSTOM) co = smooth(co, meshVert(vmIn, i, 0, k - 1).co, meshVert(vmIn, i, 0, k + 1).co)
                copy(meshVertCanon(vmOut, i, 0, 2 * k).co, co)
            }
        }
        var bndv = vmOut.boundstart!!
        val tmp = FloatArray(3)
        for (i in 0 until nb) {
            var k = 1
            while (k < nsOut) {
                getProfilePoint(bp, bndv.profile, k, nsOut, tmp)
                var co = tmp.copyOf()
                if (bp.profileType != BEVEL_PROFILE_CUSTOM) co = smooth(co, meshVertCanon(vmOut, i, 0, k - 1).co, meshVertCanon(vmOut, i, 0, k + 1).co)
                copy(meshVertCanon(vmOut, i, 0, k).co, co)
                k += 2
            }
            bndv = bndv.next
        }
        vmeshCopyEquivVerts(vmOut)
        for (i in 0 until nb) for (k in 0 until nsIn) copy(meshVert(vmIn, i, 0, k).co, meshVert(vmOut, i, 0, 2 * k).co)
        vmeshCopyEquivVerts(vmIn)
        for (i in 0 until nb) for (j in 0 until nsIn2) for (k in 0 until nsIn2) {
            copy(meshVert(vmOut, i, 2 * j + 1, 2 * k + 1).co, avg4(meshVert(vmIn, i, j, k), meshVert(vmIn, i, j, k + 1), meshVert(vmIn, i, j + 1, k), meshVert(vmIn, i, j + 1, k + 1)))
        }
        for (i in 0 until nb) for (j in 0 until nsIn2) for (k in 1..nsIn2) {
            copy(meshVert(vmOut, i, 2 * j + 1, 2 * k).co, avg4(meshVert(vmIn, i, j, k), meshVert(vmIn, i, j + 1, k), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k - 1), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k + 1)))
        }
        for (i in 0 until nb) for (j in 1 until nsIn2) for (k in 0 until nsIn2) {
            copy(meshVert(vmOut, i, 2 * j, 2 * k + 1).co, avg4(meshVert(vmIn, i, j, k), meshVert(vmIn, i, j, k + 1), meshVertCanon(vmOut, i, 2 * j - 1, 2 * k + 1), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k + 1)))
        }
        var gamma = 0.25f
        var beta = -gamma
        for (i in 0 until nb) for (j in 1 until nsIn2) for (k in 1..nsIn2) {
            val co1 = avg4(meshVertCanon(vmOut, i, 2 * j, 2 * k - 1), meshVertCanon(vmOut, i, 2 * j, 2 * k + 1), meshVertCanon(vmOut, i, 2 * j - 1, 2 * k), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k))
            val co2 = avg4(meshVertCanon(vmOut, i, 2 * j - 1, 2 * k - 1), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k - 1), meshVertCanon(vmOut, i, 2 * j - 1, 2 * k + 1), meshVertCanon(vmOut, i, 2 * j + 1, 2 * k + 1))
            var co = BevelMath.madd(co1, co2, beta)
            co = BevelMath.madd(co, meshVert(vmIn, i, j, k).co, gamma)
            copy(meshVert(vmOut, i, 2 * j, 2 * k).co, co)
        }
        vmeshCopyEquivVerts(vmOut)
        gamma = sabinGamma(nb)
        beta = -gamma
        val co1 = FloatArray(3)
        val co2 = FloatArray(3)
        for (i in 0 until nb) {
            val a = meshVert(vmOut, i, nsIn, nsIn - 1).co
            co1[0] += a[0]; co1[1] += a[1]; co1[2] += a[2]
            val b1 = meshVert(vmOut, i, nsIn - 1, nsIn - 1).co
            co2[0] += b1[0]; co2[1] += b1[1]; co2[2] += b1[2]
            val b2 = meshVert(vmOut, i, nsIn - 1, nsIn + 1).co
            co2[0] += b2[0]; co2[1] += b2[1]; co2[2] += b2[2]
        }
        var co = BevelMath.scaled(co1, 1.0f / nb.toFloat())
        co = BevelMath.madd(co, co2, beta / (2.0f * nb.toFloat()))
        co = BevelMath.madd(co, meshVert(vmIn, 0, nsIn2, nsIn2).co, gamma)
        for (i in 0 until nb) copy(meshVert(vmOut, i, nsIn, nsIn).co, co)
        bndv = vmOut.boundstart!!
        for (i in 0 until nb) {
            val inext = (i + 1) % nb
            for (k in 0..nsOut) {
                getProfilePoint(bp, bndv.profile, k, nsOut, tmp)
                copy(meshVert(vmOut, i, 0, k).co, tmp)
                if (k >= nsIn && k < nsOut) copy(meshVert(vmOut, inext, nsOut - k, 0).co, tmp)
            }
            bndv = bndv.next
        }
        return vmOut
    }

    fun makeCubeCornerSquare(nseg: Int): BevelVMesh {
        val ns2 = nseg / 2
        val vm = newAdjVmesh(3, nseg, null)
        vm.count = 0
        for (i in 0 until 3) { val co = FloatArray(3); co[i] = 1.0f; addNewBoundVert(vm, co) }
        for (i in 0 until 3) for (j in 0..ns2) for (k in 0..ns2) {
            if (!isCanon(vm, i, j, k)) continue
            val co = FloatArray(3)
            co[i] = 1.0f
            co[(i + 1) % 3] = k.toFloat() * 2.0f / nseg.toFloat()
            co[(i + 2) % 3] = j.toFloat() * 2.0f / nseg.toFloat()
            copy(meshVert(vm, i, j, k).co, co)
        }
        vmeshCopyEquivVerts(vm)
        return vm
    }

    fun makeCubeCornerSquareIn(nseg: Int): BevelVMesh {
        val ns2 = nseg / 2
        val odd = nseg % 2
        val vm = newAdjVmesh(3, nseg, null)
        vm.count = 0
        for (i in 0 until 3) { val co = FloatArray(3); co[i] = 1.0f; addNewBoundVert(vm, co) }
        val b = if (odd != 0) 2.0f / (2.0f * ns2.toFloat() + Math.sqrt(2.0).toFloat()) else 2.0f / nseg.toFloat()
        for (i in 0 until 3) for (k in 0..ns2) {
            val co = FloatArray(3)
            co[i] = 1.0f - k.toFloat() * b
            co[(i + 1) % 3] = 0.0f
            co[(i + 2) % 3] = 0.0f
            copy(meshVert(vm, i, 0, k).co, co)
            co[(i + 1) % 3] = 1.0f - k.toFloat() * b
            co[(i + 2) % 3] = 0.0f
            co[i] = 0.0f
            copy(meshVert(vm, i, 0, nseg - k).co, co)
        }
        return vm
    }

    fun makeCubeCornerAdjVmesh(bp: BevelParams): BevelVMesh {
        val nseg = bp.seg
        val r = bp.proSuperR
        if (bp.profileType != BEVEL_PROFILE_CUSTOM) {
            if (r == PRO_SQUARE_R) return makeCubeCornerSquare(nseg)
            if (r == PRO_SQUARE_IN_R) return makeCubeCornerSquareIn(nseg)
        }
        val vm0 = newAdjVmesh(3, 2, null)
        vm0.count = 0
        for (i in 0 until 3) { val co = FloatArray(3); co[i] = 1.0f; addNewBoundVert(vm0, co) }
        var bndv = vm0.boundstart!!
        for (i in 0 until 3) {
            val coc = FloatArray(3)
            coc[i] = 1.0f; coc[(i + 1) % 3] = 1.0f; coc[(i + 2) % 3] = 0.0f
            bndv.profile.superR = r
            copy(bndv.profile.start, bndv.nv.co)
            copy(bndv.profile.end, bndv.next.nv.co)
            copy(bndv.profile.middle, coc)
            copy(meshVert(vm0, i, 0, 0).co, bndv.profile.start)
            copy(bndv.profile.planeCo, bndv.profile.start)
            copy(bndv.profile.planeNo, BevelMath.cross(bndv.profile.start, bndv.profile.end))
            copy(bndv.profile.projDir, bndv.profile.planeNo)
            calculateProfile(bp, bndv, false, false)
            getProfilePoint(bp, bndv.profile, 1, 2, meshVert(vm0, i, 0, 1).co)
            bndv = bndv.next
        }
        val s3 = (1.0 / Math.sqrt(3.0)).toFloat()
        val co = floatArrayOf(s3, s3, s3)
        if (nseg > 2) { if (r > 1.5f) BevelMath.mulFl(co, 1.4f) else if (r < 0.75f) BevelMath.mulFl(co, 0.6f) }
        copy(meshVert(vm0, 0, 1, 1).co, co)
        vmeshCopyEquivVerts(vm0)
        var vm1 = vm0
        while (vm1.seg < nseg) vm1 = cubicSubdiv(bp, vm1)
        if (vm1.seg != nseg) vm1 = interpVmesh(bp, vm1, nseg)
        val ns2 = nseg / 2
        for (i in 0 until 3) for (j in 0..ns2) for (k in 0..nseg) snapToSuperellipsoid(meshVert(vm1, i, j, k).co, r, false)
        return vm1
    }

    fun edgeIsConvex(e: BMesh.Edge): Boolean {
        if (BMesh.isManifold(e)) {
            val l1 = e.l!!; val l2 = l1.rNext!!
            if (!(l1.f.no[0] == l2.f.no[0] && l1.f.no[1] == l2.f.no[1] && l1.f.no[2] == l2.f.no[2])) {
                val cr = BevelMath.cross(l1.f.no, l2.f.no)
                val dir = sub(l1.next!!.v.co, l1.v.co)
                return dot(dir, cr) > 0.0f
            }
        }
        return true
    }

    fun edgeFaceAngleSigned(e: BMesh.Edge, fallback: Float): Float {
        if (BMesh.isManifold(e)) {
            val l1 = e.l!!; val l2 = l1.rNext!!
            val a = BevelMath.angleNormalized(l1.f.no, l2.f.no)
            return if (edgeIsConvex(e)) a else -a
        }
        return fallback
    }

    fun triCornerTest(bp: BevelParams, bv: BevelBevVert): Int {
        var inPlane = 0
        if (bp.affectType == BEVEL_AFFECT_VERTICES || bp.profileType == BEVEL_PROFILE_CUSTOM) return -1
        if (bv.vmesh.count != 3) return 0
        val offset = bv.edges[0].offsetL
        var totang = 0.0f
        val qpi = (Math.PI / 4).toFloat()
        for (i in 0 until bv.edgecount) {
            val e = bv.edges[i]
            val ang = edgeFaceAngleSigned(e.e, 0.0f)
            val absang = kotlin.math.abs(ang)
            if (absang <= qpi) inPlane++ else if (absang >= 3.0f * qpi) return -1
            if (e.isBev && kotlin.math.abs(e.offsetL - offset) > BEVEL_EPSILON) return -1
            totang += ang
        }
        if (inPlane != bv.edgecount - 3) return -1
        val angdiff = kotlin.math.abs(kotlin.math.abs(totang) - 3.0f * (Math.PI / 2).toFloat())
        if ((bp.proSuperR == PRO_SQUARE_R && angdiff > Math.PI.toFloat() / 16.0f) || angdiff > qpi) return -1
        if (bv.edgecount != 3 || bv.selcount != 3) return 0
        return 1
    }

    fun triCornerAdjVmesh(bp: BevelParams, bv: BevelBevVert): BevelVMesh {
        var bndv = bv.vmesh.boundstart!!
        val co0 = bndv.nv.co.copyOf(); bndv = bndv.next
        val co1 = bndv.nv.co.copyOf(); bndv = bndv.next
        val co2 = bndv.nv.co.copyOf()
        val mat = Array(4) { FloatArray(4) }
        makeUnitCubeMap(co0, co1, co2, bv.v.co, mat)
        val ns = bp.seg
        val ns2 = ns / 2
        val vm = makeCubeCornerAdjVmesh(bp)
        for (i in 0 until 3) for (j in 0..ns2) for (k in 0..ns) {
            val c = meshVert(vm, i, j, k).co
            val x = c[0]; val y = c[1]; val z = c[2]
            for (r in 0 until 3) c[r] = mat[0][r] * x + mat[1][r] * y + mat[2][r] * z + mat[3][r] * 1.0f
        }
        return vm
    }

    fun adjVmesh(bp: BevelParams, bv: BevelBevVert): BevelVMesh {
        val nBndv = bv.vmesh.count
        if (nBndv == 3 && triCornerTest(bp, bv) != -1 && bp.proSuperR != PRO_SQUARE_IN_R) return triCornerAdjVmesh(bp, bv)
        val nseg = bv.vmesh.seg
        val vm0 = newAdjVmesh(nBndv, 2, bv.vmesh.boundstart)
        var bndv = vm0.boundstart!!
        val center = FloatArray(3)
        for (i in 0 until nBndv) {
            copy(meshVert(vm0, i, 0, 0).co, bndv.nv.co)
            getProfilePoint(bp, bndv.profile, 1, 2, meshVert(vm0, i, 0, 1).co)
            center[0] += bndv.nv.co[0]; center[1] += bndv.nv.co[1]; center[2] += bndv.nv.co[2]
            bndv = bndv.next
        }
        BevelMath.mulFl(center, 1.0f / nBndv.toFloat())
        val orig = bv.v.co.copyOf()
        val negFull = add(sub(center, orig), center)
        var fullness = bp.proSpacing.fullness
        val cdir = sub(orig, center)
        if (BevelMath.lenSq(cdir) > BEVEL_EPSILON_SQ) {
            if (bp.profileType == BEVEL_PROFILE_CUSTOM) {
                fullness *= 2.0f
                copy(meshVert(vm0, 0, 1, 1).co, BevelMath.madd(negFull, cdir, fullness))
            } else copy(meshVert(vm0, 0, 1, 1).co, BevelMath.madd(center, cdir, fullness))
        } else copy(meshVert(vm0, 0, 1, 1).co, center)
        vmeshCopyEquivVerts(vm0)
        var vm1 = vm0
        do { vm1 = cubicSubdiv(bp, vm1) } while (vm1.seg < nseg)
        if (vm1.seg != nseg) vm1 = interpVmesh(bp, vm1, nseg)
        return vm1
    }

    fun snapToPipeProfile(vpipe: BevelBoundVert, midline: Boolean, co: FloatArray) {
        val pro = vpipe.profile
        val e = vpipe.ebev!!
        if (BevelMath.compareV3(pro.start, pro.end, BEVEL_EPSILON_D.toFloat())) { copy(co, pro.start); return }
        val edir = sub(e.e.v1.co, e.e.v2.co)
        fun closestToPlane(pt: FloatArray): FloatArray {
            val lenSq = BevelMath.lenSq(edir)
            val side = dot(edir, pt) + -dot(edir, co)
            return BevelMath.madd(pt, edir, -side / lenSq)
        }
        val sp = closestToPlane(pro.start)
        val ep = closestToPlane(pro.end)
        val mp = closestToPlane(pro.middle)
        val m = Array(4) { FloatArray(4) }
        val minv = if (makeUnitSquareMap(sp, mp, ep, m)) BevelGeom.invertM4(m) else null
        if (minv != null) {
            val p = mulM4V3(minv, co)
            snapToSuperellipsoid(p, pro.superR, midline)
            copy(co, mulM4V3(m, p))
        } else copy(co, BevelMath.closestToLineSegment(co, sp, ep))
    }

    fun pipeAdjVmesh(bp: BevelParams, bv: BevelBevVert, vpipe: BevelBoundVert): BevelVMesh {
        val vm = adjVmesh(bp, bv)
        val nBndv = bv.vmesh.count
        val ns = bv.vmesh.seg
        val half = ns / 2
        val ip1 = vpipe.index
        val ip2 = vpipe.next.next.index
        for (i in 0 until nBndv) for (j in 1..half) for (k in 0..half) {
            if (!isCanon(vm, i, j, k)) continue
            if (bp.profileType == BEVEL_PROFILE_CUSTOM) {
                val p1: FloatArray; val p2: FloatArray; val f: Float
                if (i == ip1 || i == ip2) {
                    if (nBndv == 3 && i == ip1) {
                        val ring = maxOf(j, k)
                        p2 = meshVert(vm, i, 0, ring).co
                        p1 = meshVert(vm, i, ring, 0).co
                        f = (if (k < j) minOf(j, k).toFloat() else (2.0f * ring) - j) / (2.0f * ring)
                    } else {
                        p1 = meshVert(vm, i, 0, k).co
                        p2 = meshVert(vm, if (i == ip1) ip2 else ip1, 0, ns - k).co
                        f = j.toFloat() / ns.toFloat()
                    }
                } else {
                    p1 = meshVert(vm, i, j, 0).co
                    p2 = meshVert(vm, i, j, ns).co
                    f = k.toFloat() / ns.toFloat()
                }
                copy(meshVert(vm, i, j, k).co, BevelMath.interp(p1, p2, f))
            } else {
                val even = ns % 2 == 0
                val midline = even && k == half && ((i == 0 && j == half) || i == ip1 || i == ip2)
                snapToPipeProfile(vpipe, midline, meshVert(vm, i, j, k).co)
            }
        }
        return vm
    }

    fun getIncidentEdges(f: BMesh.Face?, v: BMesh.Vert): Pair<BMesh.Edge?, BMesh.Edge?> {
        if (f == null) return null to null
        var e1: BMesh.Edge? = null
        var e2: BMesh.Edge? = null
        for (l in BMesh.loopsOfFace(f)) {
            val e = l.e!!
            if (e.v1 === v || e.v2 === v) { if (e1 == null) e1 = e else if (e2 == null) e2 = e }
        }
        return e1 to e2
    }

    fun findCloserEdge(co: FloatArray, e1: BMesh.Edge, e2: BMesh.Edge): BMesh.Edge {
        val d1 = BevelMath.lenSqV3V3(BevelMath.closestToLineSegment(co, e1.v1.co, e1.v2.co), co)
        val d2 = BevelMath.lenSqV3V3(BevelMath.closestToLineSegment(co, e2.v1.co, e2.v2.co), co)
        return if (d1 < d2) e1 else e2
    }

    fun findFaceInternalBoundverts(bv: BevelBevVert, f: BMesh.Face?): Array<BevelBoundVert?> {
        val out = arrayOfNulls<BevelBoundVert>(3)
        if (f == null) return out
        var n = 0
        val vm = bv.vmesh
        var v = vm.boundstart!!
        do {
            if (BevelGeom.facePointInsideTest(f, v.nv.co)) { out[n++] = v; if (n == 3) break }
            v = v.next
        } while (v !== vm.boundstart)
        return out
    }

    fun projectedBoundaryArea(bv: BevelBevVert, f: BMesh.Face): Float {
        val vm = bv.vmesh
        val (b1, b2) = BevelGeom.axisBasis(f.no)
        val (e1, e2) = getIncidentEdges(f, bv.v)
        val unsnapped = findFaceInternalBoundverts(bv, f)
        val proj = Array(vm.count) { FloatArray(2) }
        var v = vm.boundstart!!
        var i = 0
        fun pr(c: FloatArray, o: FloatArray) { o[0] = dot(b1, c); o[1] = dot(b2, c) }
        do {
            val co = v.nv.v!!.co
            if (v === unsnapped[0] || v === unsnapped[1] || v === unsnapped[2]) pr(co, proj[i])
            else {
                val s1 = BevelMath.closestToLineSegment(co, e1!!.v1.co, e1.v2.co)
                val s2 = BevelMath.closestToLineSegment(co, e2!!.v1.co, e2.v2.co)
                if (BevelMath.lenSqV3V3(s1, co) <= BevelMath.lenSqV3V3(s2, co)) pr(s1, proj[i]) else pr(s2, proj[i])
            }
            i++
            v = v.next
        } while (v !== vm.boundstart)
        return BevelGeom.areaPoly2(proj)
    }

    fun isBadUvPoly(bv: BevelBevVert, frep: BMesh.Face) = projectedBoundaryArea(bv, frep) < BEVEL_EPSILON_BIG

    fun frepForCenterPoly(bp: BevelParams, bv: BevelBevVert): BMesh.Face? {
        var anyBmf: BMesh.Face? = null
        val considerAll = bv.selcount == 1
        val choices = ArrayList<BMesh.Face>()
        for (i in 0 until bv.edgecount) {
            if (!bv.edges[i].isBev && !considerAll) continue
            val bmf = chooseRepFace(bp, arrayOf(bv.edges[i].fprev, bv.edges[i].fnext)) ?: continue
            if (anyBmf == null) anyBmf = bmf
            if (choices.none { it === bmf }) {
                if (bp.hasMathLayers && isBadUvPoly(bv, bmf)) continue
                choices.add(bmf)
            }
        }
        if (choices.isEmpty()) return anyBmf
        return chooseRepFace(bp, choices.toTypedArray())
    }

    fun buildCenterNgon(bp: BevelParams, bv: BevelBevVert, matNr: Int) {
        val vm = bv.vmesh
        val vv = ArrayList<BMesh.Vert>(); val vf = ArrayList<BMesh.Face?>(); val ve = ArrayList<BMesh.Edge?>()
        val ns2 = vm.seg / 2
        val frep: BMesh.Face?
        var fe1: BMesh.Edge? = null; var fe2: BMesh.Edge? = null
        var uns = arrayOfNulls<BevelBoundVert>(3)
        if (bv.anySeam) {
            frep = frepForCenterPoly(bp, bv)
            val p = getIncidentEdges(frep, bv.v); fe1 = p.first; fe2 = p.second
            uns = findFaceInternalBoundverts(bv, frep)
        } else frep = null
        var v = vm.boundstart!!
        do {
            val i = v.index
            vv.add(meshVert(vm, i, ns2, ns2).v!!)
            if (frep != null) {
                vf.add(frep)
                if (v === uns[0] || v === uns[1] || v === uns[2]) ve.add(null) else ve.add(findCloserEdge(meshVert(vm, i, ns2, ns2).v!!.co, fe1!!, fe2!!))
            } else { vf.add(boundvertRepFace(v).first); ve.add(null) }
            v = v.next
        } while (v !== vm.boundstart)
        val f = bevCreateNgon(bp, vv.toTypedArray(), vf.toTypedArray(), frep, ve.toTypedArray(), bv.v, null, matNr, true)
        if (f != null) recordFaceKind(bp, f, F_VERT)
    }

    fun buildSquareInVmesh(bp: BevelParams, bv: BevelBevVert, vm1: BevelVMesh) {
        val vm = bv.vmesh
        val n = vm.count
        val ns = vm.seg
        val ns2 = ns / 2
        val odd = ns % 2
        for (i in 0 until n) for (k in 1 until ns) {
            copy(meshVert(vm, i, 0, k).co, meshVert(vm1, i, 0, k).co)
            if (i > 0 && k <= ns2) meshVert(vm, i, 0, k).v = meshVert(vm, i - 1, 0, ns - k).v
            else if (i == n - 1 && k > ns2) meshVert(vm, i, 0, k).v = meshVert(vm, 0, 0, ns - k).v
            else createMeshBmvert(bp.bm, vm, i, 0, k, bv.v)
        }
        if (odd != 0) {
            for (i in 0 until n) meshVert(vm, i, ns2, ns2).v = meshVert(vm, i, 0, ns2).v
            buildCenterNgon(bp, bv, bp.matNr)
        }
    }

    fun squareOutAdjVmesh(bp: BevelParams, bv: BevelBevVert): BevelVMesh {
        val nB = bv.vmesh.count
        val ns = bv.vmesh.seg
        val ns2 = ns / 2
        val odd = ns % 2
        var ns2inv = 1.0f / ns2.toFloat()
        val vm = newAdjVmesh(nB, ns, bv.vmesh.boundstart)
        val cl = Array(nB) { Array(ns2 + 1) { FloatArray(3) } }
        val cset = BooleanArray(nB)
        var bndv = vm.boundstart!!
        var i = 0
        while (i < nB) {
            val bndco = bndv.nv.co.copyOf()
            var e1 = bndv.efirst
            var e2 = bndv.elast
            var ang = ANGLE_STRAIGHT
            if (e1 != null && e2 != null) ang = edgesAngleKind(e1, e2, bv.v)
            if (bndv.isPatchStart) {
                copy(cl[i][0], BevelMath.mid(bndv.nv.co, bndv.next.nv.co))
                cset[i] = true
                bndv = bndv.next; i++
                copy(cl[i][0], BevelMath.mid(bndv.nv.co, bndv.next.nv.co))
                cset[i] = true
                bndv = bndv.next; i++
            } else if (bndv.isArcStart) {
                copy(cl[i][0], bndv.profile.middle)
                bndv = bndv.next
                cset[i] = true
                i++
            } else if (ang == ANGLE_SMALLER) {
                val dir1 = sub(e1!!.e.v1.co, e1.e.v2.co)
                val dir2 = sub(e2!!.e.v1.co, e2.e.v2.co)
                val co1 = add(bndco, dir1)
                val co2 = add(bndco, dir2)
                val m1 = FloatArray(3); val m2 = FloatArray(3)
                var v1co: FloatArray? = null
                if (BevelMath.isectLineLine(e1.e.v1.co, e1.e.v2.co, bndco, co2, m1, m2) != 0) v1co = m1.copyOf()
                var v2co: FloatArray? = null
                if (BevelMath.isectLineLine(e2.e.v1.co, e2.e.v2.co, bndco, co1, m1, m2) != 0) v2co = m1.copyOf()
                val cur = cl[i][0]
                val iprev = if (i == 0) nB - 1 else i - 1
                val prev = cl[iprev][0]
                if (v2co != null) {
                    if (cset[i]) copy(cur, if (BevelMath.lenSqV3V3(cur, bv.v.co) <= BevelMath.lenSqV3V3(v2co, bv.v.co)) cur.copyOf() else v2co)
                    else { copy(cur, v2co); cset[i] = true }
                }
                if (v1co != null) {
                    if (cset[iprev]) copy(prev, if (BevelMath.lenSqV3V3(prev, bv.v.co) <= BevelMath.lenSqV3V3(v1co, bv.v.co)) prev.copyOf() else v1co)
                    else { copy(prev, v1co); cset[iprev] = true }
                }
            }
            bndv = bndv.next
            i++
        }
        bndv = vm.boundstart!!
        for (ii in 0 until nB) {
            if (!cset[ii]) {
                val cur = cl[ii][0]
                val e1 = bndv.next.efirst
                val co1 = bndv.nv.co.copyOf()
                val co2 = bndv.next.nv.co.copyOf()
                if (e1 != null) {
                    if (bndv.prev.isArcStart && bndv.next.isArcStart) {
                        val m1 = FloatArray(3); val m2 = FloatArray(3)
                        if (BevelMath.isectLineLine(e1.e.v1.co, e1.e.v2.co, co1, co2, m1, m2) != 0) { copy(cur, m1); cset[ii] = true }
                    } else {
                        copy(cur, BevelMath.closestToLineSegment(if (bndv.prev.isArcStart) co1 else co2, e1.e.v1.co, e1.e.v2.co))
                        cset[ii] = true
                    }
                }
                if (!cset[ii]) { copy(cur, BevelMath.mid(co1, co2)); cset[ii] = true }
            }
            bndv = bndv.next
        }
        var co1 = FloatArray(3)
        var co2 = bv.v.co.copyOf()
        bndv = vm.boundstart!!
        for (ii in 0 until nB) {
            if (odd != 0) {
                val ang = 0.5f * BevelMath.angleV3V3V3(bndv.nv.co, co1, bndv.next.nv.co)
                val finalfrac = if (ang > BEVEL_SMALL_ANG) minOf(0.5f / kotlin.math.sin(ang), 0.8f) else 0.8f
                ns2inv = 1.0f / (ns2 + finalfrac)
            }
            co1 = cl[ii][0].copyOf()
            for (j in 1..ns2) copy(cl[ii][j], BevelMath.interp(co1, co2, j * ns2inv))
            bndv = bndv.next
        }
        bndv = vm.boundstart!!
        for (ii in 0 until nB) {
            co1 = bndv.nv.co.copyOf()
            co2 = cl[if (ii == 0) nB - 1 else ii - 1][0].copyOf()
            for (j in 0 until ns2 + odd) copy(meshVert(vm, ii, j, 0).co, BevelMath.interp(co1, co2, j * ns2inv))
            co2 = cl[ii][0].copyOf()
            for (k in 1..ns2) copy(meshVert(vm, ii, 0, k).co, BevelMath.interp(co1, co2, k * ns2inv))
            bndv = bndv.next
        }
        if (odd == 0) copy(meshVert(vm, 0, ns2, ns2).co, bv.v.co)
        vmeshCopyEquivVerts(vm)
        for (ii in 0 until nB) {
            val im1 = if (ii == 0) nB - 1 else ii - 1
            for (j in 1 until ns2 + odd) for (k in 1..ns2) {
                val m1 = FloatArray(3); val m2 = FloatArray(3)
                val kind = BevelMath.isectLineLine(meshVert(vm, ii, 0, k).co, cl[im1][k], meshVert(vm, ii, j, 0).co, cl[ii][j], m1, m2)
                val dst = meshVert(vm, ii, j, k).co
                when (kind) {
                    0 -> copy(dst, BevelMath.interp(meshVert(vm, ii, 0, k).co, cl[im1][k], j * ns2inv))
                    1 -> copy(dst, m1)
                    else -> copy(dst, BevelMath.mid(m1, m2))
                }
            }
        }
        vmeshCopyEquivVerts(vm)
        return vm
    }

    fun snapEdgeForCenterVmeshVert(i: Int, n: Int, eprev: BMesh.Edge?, enext: BMesh.Edge?, rep: Array<BMesh.Face?>, centerFrep: BMesh.Face?, beats: BooleanArray): BMesh.Edge? {
        val previ = (i + n - 1) % n
        val nexti = (i + 1) % n
        if (beats[previ] && rep[previ] === centerFrep) return eprev
        if (!beats[i] && rep[nexti] === centerFrep) return enext
        return null
    }

    fun snapEdgesForVmeshVert(i: Int, j: Int, k: Int, ns: Int, ns2: Int, n: Int, eprev: BMesh.Edge?, enext: BMesh.Edge?, enextnext: BMesh.Edge?, rep: Array<BMesh.Face?>, centerFrep: BMesh.Face?, beats: BooleanArray, out: Array<BMesh.Edge?>) {
        for (corner in 0 until 4) {
            out[corner] = null
            if (ns % 2 == 0) continue
            val previ = (i + n - 1) % n
            val jj = if (corner < 2) j else j + 1
            val kk = if (corner == 0 || corner == 3) k else k + 1
            if (jj < ns2 && kk < ns2) {
            } else if (jj < ns2 && kk == ns2) { if (!beats[i]) out[corner] = enext }
            else if (jj < ns2 && kk == ns2 + 1) { if (beats[i]) out[corner] = enext }
            else if (jj == ns2 && kk < ns2) { if (beats[previ]) out[corner] = eprev }
            else if (jj == ns2 && kk == ns2) out[corner] = snapEdgeForCenterVmeshVert(i, n, eprev, enext, rep, centerFrep, beats)
            else if (jj == ns2 && kk == ns2 + 1) out[corner] = snapEdgeForCenterVmeshVert((i + 1) % n, n, enext, enextnext, rep, centerFrep, beats)
        }
    }

    fun bevelBuildRings(bp: BevelParams, bv: BevelBevVert, vpipe: BevelBoundVert?) {
        val matNr = bp.matNr
        val nB = bv.vmesh.count
        val ns = bv.vmesh.seg
        val ns2 = ns / 2
        val odd = ns % 2
        val vm1: BevelVMesh
        if (bp.proSuperR == PRO_SQUARE_R && bv.selcount >= 3 && odd == 0 && bp.profileType != BEVEL_PROFILE_CUSTOM) vm1 = squareOutAdjVmesh(bp, bv)
        else if (vpipe != null) vm1 = pipeAdjVmesh(bp, bv, vpipe)
        else if (triCornerTest(bp, bv) == 1) {
            vm1 = triCornerAdjVmesh(bp, bv)
            if (bp.proSuperR == PRO_SQUARE_IN_R && bp.profileType != BEVEL_PROFILE_CUSTOM) { buildSquareInVmesh(bp, bv, vm1); return }
        } else vm1 = adjVmesh(bp, bv)
        val vm = bv.vmesh
        for (i in 0 until nB) for (j in 0..ns2) for (k in 0..ns) {
            if (j == 0 && (k == 0 || k == ns)) continue
            if (!isCanon(vm, i, j, k)) continue
            copy(meshVert(vm, i, j, k).co, meshVert(vm1, i, j, k).co)
            createMeshBmvert(bp.bm, vm, i, j, k, bv.v)
        }
        vmeshCopyEquivVerts(vm)
        val rep = arrayOfNulls<BMesh.Face>(nB)
        var bndv = vm.boundstart!!
        do { rep[bndv.index] = boundvertRepFace(bndv).first; bndv = bndv.next } while (bndv !== vm.boundstart)
        var centerVerts: Array<BMesh.Vert?>? = null
        var centerSnaps: Array<BMesh.Edge?>? = null
        var centerInterps: Array<BMesh.Face?>? = null
        var beats = BooleanArray(0)
        var centerFrep: BMesh.Face? = null
        if (odd != 0 && bp.affectType == BEVEL_AFFECT_EDGES) {
            centerVerts = arrayOfNulls(nB)
            centerSnaps = arrayOfNulls(nB)
            centerInterps = arrayOfNulls(nB)
            beats = BooleanArray(nB)
            centerFrep = frepForCenterPoly(bp, bv)
            for (i in 0 until nB) {
                val inext = (i + 1) % nB
                val w = chooseRepFace(bp, arrayOf(rep[i], rep[inext]))
                beats[i] = w === rep[i]
            }
        }
        bndv = vm.boundstart!!
        do {
            val i = bndv.index
            val inext = bndv.next.index
            val f = rep[i]
            val f2 = rep[inext]
            var fc: BMesh.Face? = null
            if (odd != 0 && bp.affectType == BEVEL_AFFECT_EDGES) fc = if (beats[i]) f else f2
            val e: BevelEdgeHalf?; val eprev: BevelEdgeHalf?; val enext: BevelEdgeHalf?
            if (bp.affectType == BEVEL_AFFECT_VERTICES) { e = bndv.efirst; eprev = bndv.prev.efirst; enext = bndv.next.efirst }
            else { e = bndv.ebev; eprev = bndv.prev.ebev; enext = bndv.next.ebev }
            val bme = e?.e
            val bmeprev = eprev?.e
            val bmenext = enext?.e
            for (j in 0 until ns2) for (k in 0 until ns2 + odd) {
                val bmvs = arrayOf(meshVert(vm, i, j, k).v!!, meshVert(vm, i, j, k + 1).v!!, meshVert(vm, i, j + 1, k + 1).v!!, meshVert(vm, i, j + 1, k).v!!)
                val fr = arrayOfNulls<BMesh.Face>(4)
                val se = arrayOfNulls<BMesh.Edge>(4)
                if (bp.affectType == BEVEL_AFFECT_VERTICES) {
                    fr.fill(f2)
                    if (j < k) {
                        if (k == ns2 && j == ns2 - 1) { se[2] = bndv.next.efirst!!.e; se[3] = bme }
                    } else if (j == k) {
                        se[0] = bme; se[2] = bme
                        if (!e!!.isSeam) fr[3] = f
                    }
                } else {
                    fr.fill(f)
                    if (odd != 0) {
                        val b1 = if (eprev != null && eprev.isSeam) bmeprev else null
                        val b2 = if (e != null && e.isSeam) bme else null
                        val b3 = if (enext != null && enext.isSeam) bmenext else null
                        snapEdgesForVmeshVert(i, j, k, ns, ns2, nB, b1, b2, b3, rep, centerFrep, beats, se)
                        if (k == ns2) {
                            if (e == null || e.isSeam) fr.fill(fc)
                            else { fr[0] = f; fr[3] = f; fr[1] = f2; fr[2] = f2 }
                            if (j == ns2 - 1) {
                                centerVerts!![i] = bmvs[3]
                                centerSnaps!![i] = se[3]
                                centerInterps!![i] = if (bv.anySeam) centerFrep else f
                            }
                        }
                    } else {
                        if (k == ns2 - 1) se[1] = bme
                        if (j == ns2 - 1 && bndv.prev.ebev != null) se[3] = bmeprev
                        se[2] = se[1] ?: se[3]
                    }
                }
                val rf = bevCreateNgon(bp, bmvs, fr, null, se, bv.v, null, matNr, true)
                if (rf != null) recordFaceKind(bp, rf, F_VERT)
            }
            bndv = bndv.next
        } while (bndv !== vm.boundstart)
        if (odd != 0) {
            if (bp.affectType == BEVEL_AFFECT_EDGES) {
                val frep = if (bv.anySeam) frepForCenterPoly(bp, bv) else null
                val cf = bevCreateNgon(bp, Array(nB) { centerVerts!![it]!! }, centerInterps, frep, centerSnaps, bv.v, null, matNr, true)
                if (cf != null) recordFaceKind(bp, cf, F_VERT)
            } else buildCenterNgon(bp, bv, matNr)
        }
    }

    fun bevelBuildCutoff(bp: BevelParams, bv: BevelBevVert) {
        val nB = bv.vmesh.count
        var bndv = bv.vmesh.boundstart!!
        do {
            val i = bndv.index
            val down = BevelMath.cross(bndv.profile.planeNo, bndv.prev.profile.planeNo)
            if (dot(down, bv.v.no) > 0.0f) BevelMath.negate(down)
            val length = (bndv.profile.height / kotlin.math.sqrt(2.0f) + bndv.prev.profile.height / kotlin.math.sqrt(2.0f)) / 2
            val nv = BevelMath.madd(bndv.nv.co, down, length)
            copy(meshVert(bv.vmesh, i, 1, 0).co, nv)
            copy(meshVert(bv.vmesh, bndv.prev.index, 1, 1).co, nv)
            bndv = bndv.next
        } while (bndv !== bv.vmesh.boundstart)
        var center = true
        if (nB == 3) {
            center = center && BevelMath.lenSqV3V3(meshVert(bv.vmesh, 0, 1, 0).co, meshVert(bv.vmesh, 1, 1, 0).co) > BEVEL_EPSILON
            center = center && BevelMath.lenSqV3V3(meshVert(bv.vmesh, 0, 1, 0).co, meshVert(bv.vmesh, 2, 1, 0).co) > BEVEL_EPSILON
            center = center && BevelMath.lenSqV3V3(meshVert(bv.vmesh, 1, 1, 0).co, meshVert(bv.vmesh, 2, 1, 0).co) > BEVEL_EPSILON
        }
        if (center) {
            do {
                val i = bndv.index
                createMeshBmvert(bp.bm, bv.vmesh, i, 1, 0, bv.v)
                meshVert(bv.vmesh, bndv.prev.index, 1, 1).v = meshVert(bv.vmesh, i, 1, 0).v
                bndv = bndv.next
            } while (bndv !== bv.vmesh.boundstart)
        } else {
            createMeshBmvert(bp.bm, bv.vmesh, 0, 1, 0, bv.v)
            for (i in 1 until nB) meshVert(bv.vmesh, i, 1, 0).v = meshVert(bv.vmesh, 0, 1, 0).v
        }
        bndv = bv.vmesh.boundstart!!
        do {
            val i = bndv.index
            val list = ArrayList<BMesh.Vert>()
            list.add(meshVert(bv.vmesh, i, 1, 0).v!!)
            for (k in 0 until bp.seg + 1) list.add(meshVert(bv.vmesh, i, 0, k).v!!)
            if (center) list.add(meshVert(bv.vmesh, i, 1, 1).v!!)
            bevCreateNgon(bp, list.toTypedArray(), null, null, null, bv.v, null, bp.matNr, true)
            bndv = bndv.next
        } while (bndv !== bv.vmesh.boundstart)
        if (center) bevCreateNgon(bp, Array(nB) { meshVert(bv.vmesh, it, 1, 0).v!! }, null, null, null, bv.v, null, bp.matNr, true)
    }

    fun bevelBuildPoly(bp: BevelParams, bv: BevelBevVert): BMesh.Face? {
        val vm = bv.vmesh
        val bmverts = ArrayList<BMesh.Vert>(); val bmedges = ArrayList<BMesh.Edge?>(); val bmfaces = ArrayList<BMesh.Face?>()
        val repface: BMesh.Face?
        var re1: BMesh.Edge? = null; var re2: BMesh.Edge? = null
        var uns = arrayOfNulls<BevelBoundVert>(3)
        if (bv.anySeam) {
            repface = frepForCenterPoly(bp, bv)
            val p = getIncidentEdges(repface, bv.v); re1 = p.first; re2 = p.second
            uns = findFaceInternalBoundverts(bv, repface)
        } else repface = null
        var bndv = vm.boundstart!!
        var n = 0
        do {
            bmverts.add(bndv.nv.v!!)
            if (repface != null) {
                bmfaces.add(repface)
                if (bndv === uns[0] || bndv === uns[1] || bndv === uns[2]) bmedges.add(null) else bmedges.add(findCloserEdge(bndv.nv.v!!.co, re1!!, re2!!))
            } else { bmfaces.add(boundvertRepFace(bndv).first); bmedges.add(null) }
            n++
            val eb = bndv.ebev
            if (eb != null && eb.seg > 1) {
                for (k in 1 until eb.seg) {
                    val mv = meshVert(vm, bndv.index, 0, k).v!!
                    bmverts.add(mv)
                    if (repface != null) {
                        bmfaces.add(repface)
                        val fe = findCloserEdge(mv.co, re1!!, re2!!)
                        bmedges.add(if (k < eb.seg / 2) null else fe)
                    } else { bmfaces.add(boundvertRepFace(bndv).first); bmedges.add(null) }
                    n++
                }
            }
            bndv = bndv.next
        } while (bndv !== vm.boundstart)
        if (n > 2) {
            val f = bevCreateNgon(bp, bmverts.toTypedArray(), bmfaces.toTypedArray(), repface, bmedges.toTypedArray(), bv.v, null, bp.matNr, true)
            if (f != null) recordFaceKind(bp, f, F_VERT)
            return f
        }
        return null
    }

    fun bevelBuildTrifan(bp: BevelParams, bv: BevelBevVert) {
        var f = bevelBuildPoly(bp, bv) ?: return
        var lFan = f.lFirst!!.prev!!
        val vFan = lFan.v
        while (f.len > 3) {
            val lNew = bp.bm.faceSplit(f, lFan, lFan.next!!.next!!, null, false)!!
            val fNew = bp.bm.lastSplitFace!!
            if (fNew.len > f.len) {
                f = fNew
                if (lNew.v === vFan) lFan = lNew
                else if (lNew.next!!.v === vFan) lFan = lNew.next!!
                else if (lNew.prev!!.v === vFan) lFan = lNew.prev!!
            } else {
                if (lFan.v === vFan) {
                } else if (lFan.next!!.v === vFan) lFan = lFan.next!!
                else if (lFan.prev!!.v === vFan) lFan = lFan.prev!!
            }
            recordFaceKind(bp, fNew, F_VERT)
        }
    }

    fun bevelVertTwoEdges(bp: BevelParams, bv: BevelBevVert) {
        val vm = bv.vmesh
        val v1 = meshVert(vm, 0, 0, 0).v!!
        val v2 = meshVert(vm, 1, 0, 0).v!!
        val ns = vm.seg
        if (ns > 1) {
            val pro = vm.boundstart!!.profile
            pro.superR = bp.proSuperR
            copy(pro.start, v1.co); copy(pro.end, v2.co); copy(pro.middle, bv.v.co)
            pro.planeCo.fill(0f); pro.planeNo.fill(0f); pro.projDir.fill(0f)
            val co = FloatArray(3)
            for (k in 1 until ns) {
                getProfilePoint(bp, pro, k, ns, co)
                copy(meshVert(vm, 0, 0, k).co, co)
                createMeshBmvert(bp.bm, vm, 0, 0, k, bv.v)
            }
            copy(meshVert(vm, 0, 0, ns).co, v2.co)
            for (k in 1 until ns) copyMeshVert(vm, 1, 0, ns - k, 0, 0, k)
        }
        if (!vertFaceCheck(bv.v)) {
            val eg = bv.edges[0].e
            for (k in 0 until ns) {
                val a = meshVert(vm, 0, 0, k).v!!
                val b = meshVert(vm, 0, 0, k + 1).v!!
                bp.bm.edgeCreate(a, b, eg, true)
            }
        }
    }

    fun vertFaceCheck(v: BMesh.Vert): Boolean {
        val first = v.e ?: return false
        var e = first
        do { if (e.l != null) return true; e = BMesh.diskNext(e, v)!! } while (e !== first)
        return false
    }

    fun edgeFaceCount(e: BMesh.Edge): Int {
        val h = e.l ?: return 0
        var c = 0
        var l: BMesh.Loop = h
        do { c++; l = l.rNext!! } while (l !== h)
        return c
    }

    fun buildVmesh(bp: BevelParams, bv: BevelBevVert) {
        val vm = bv.vmesh
        val n = vm.count
        val ns = vm.seg
        val ns2 = ns / 2
        vm.mesh = Array(n * (ns2 + 1) * (ns + 1)) { BevelNewVert() }
        val weld = bv.selcount == 2 && vm.count == 2
        var weld1: BevelBoundVert? = null
        var weld2: BevelBoundVert? = null
        var bndv = vm.boundstart!!
        do {
            val i = bndv.index
            copy(meshVert(vm, i, 0, 0).co, bndv.nv.co)
            createMeshBmvert(bp.bm, vm, i, 0, 0, bv.v)
            bndv.nv.v = meshVert(vm, i, 0, 0).v
            if (weld && bndv.ebev != null) {
                if (weld1 == null) weld1 = bndv
                else {
                    weld2 = bndv
                    setProfileParams(bp, bv, weld1)
                    setProfileParams(bp, bv, weld2)
                    moveWeldProfilePlanes(bv, weld1, weld2)
                }
            }
            bndv = bndv.next
        } while (bndv !== vm.boundstart)
        calculateVmProfiles(bp, bv, vm)
        val co = FloatArray(3)
        bndv = vm.boundstart!!
        do {
            val i = bndv.index
            copyMeshVert(vm, i, 0, ns, bndv.next.index, 0, 0)
            if (vm.meshKind != M_ADJ) {
                for (k in 1 until ns) {
                    if (bndv.ebev != null) {
                        getProfilePoint(bp, bndv.profile, k, ns, co)
                        copy(meshVert(vm, i, 0, k).co, co)
                        if (!weld) createMeshBmvert(bp.bm, vm, i, 0, k, bv.v)
                    } else if (n == 2) copyMeshVert(vm, i, 0, k, 1 - i, 0, ns - k)
                }
            }
            bndv = bndv.next
        } while (bndv !== vm.boundstart)
        if (weld) {
            val w1 = weld1!!; val w2 = weld2!!
            vm.meshKind = M_NONE
            for (k in 1 until ns) {
                val a = meshVert(vm, w1.index, 0, k).co
                val b = meshVert(vm, w2.index, 0, ns - k).co
                val c = if (bp.profileType == BEVEL_PROFILE_CUSTOM) BevelMath.mid(a, b)
                else if (w1.profile.superR == PRO_LINE_R && w2.profile.superR != PRO_LINE_R) b.copyOf()
                else if (w2.profile.superR == PRO_LINE_R && w1.profile.superR != PRO_LINE_R) a.copyOf()
                else BevelMath.mid(a, b)
                copy(meshVert(vm, w1.index, 0, k).co, c)
                createMeshBmvert(bp.bm, vm, w1.index, 0, k, bv.v)
            }
            for (k in 1 until ns) copyMeshVert(vm, w2.index, 0, ns - k, w1.index, 0, k)
        }
        var vpipe: BevelBoundVert? = null
        if ((vm.count == 3 || vm.count == 4) && bp.seg > 1) {
            vpipe = pipeTest(bv)
            if (vpipe != null) vm.meshKind = M_ADJ
        }
        when (vm.meshKind) {
            M_NONE -> if (n == 2 && bp.affectType == BEVEL_AFFECT_VERTICES) bevelVertTwoEdges(bp, bv)
            M_POLY -> bevelBuildPoly(bp, bv)
            M_ADJ -> bevelBuildRings(bp, bv, vpipe)
            M_TRI_FAN -> bevelBuildTrifan(bp, bv)
            M_CUTOFF -> bevelBuildCutoff(bp, bv)
        }
    }

    fun edgeFaceAngle(e: BevelEdgeHalf): Float {
        val fp = e.fprev; val fn = e.fnext
        if (fp != null && fn != null) return Math.PI.toFloat() - BevelMath.angleNormalized(fp.no, fn.no)
        return 0.0f
    }

    private const val EDGE_TAG = 1 shl 8

    private fun tagTest(e: BMesh.Edge) = e.api and EDGE_TAG != 0
    private fun tagOn(e: BMesh.Edge) { e.api = e.api or EDGE_TAG }
    private fun tagOff(e: BMesh.Edge) { e.api = e.api and EDGE_TAG.inv() }

    private fun loopsOfEdge(e: BMesh.Edge): List<BMesh.Loop> {
        val out = ArrayList<BMesh.Loop>()
        val h = e.l ?: return out
        var l: BMesh.Loop = h
        do { out.add(l); l = l.rNext!! } while (l !== h)
        return out
    }

    fun bevelEdgeOrderExtend(bv: BevelBevVert, slots: Array<BMesh.Edge?>, i: Int): Int {
        val sucs = ArrayList<BMesh.Edge>()
        val bme = slots[i]!!
        for (l in loopsOfEdge(bme)) {
            val bme2 = if (l.v === bv.v) l.prev!!.e!! else l.next!!.e!!
            if (!tagTest(bme2)) sucs.add(bme2)
        }
        var bestj = i
        val j = i
        val savePath = ArrayList<BMesh.Edge>()
        for (next in sucs) {
            slots[j + 1] = next
            tagOn(next)
            val tryj = bevelEdgeOrderExtend(bv, slots, j + 1)
            if (tryj > bestj || (tryj == bestj && edgesFaceConnectedAtVert(slots[tryj]!!, slots[0]!!))) {
                bestj = tryj
                savePath.clear()
                for (k in j + 1..bestj) savePath.add(slots[k]!!)
            }
            for (k in j + 1..tryj) { tagOff(slots[k]!!); slots[k] = null }
        }
        if (bestj > j) for (k in j + 1..bestj) { slots[k] = savePath[k - (j + 1)]; tagOn(slots[k]!!) }
        return bestj
    }

    fun fastBevelEdgeOrder(bv: BevelBevVert, slots: Array<BMesh.Edge?>): Boolean {
        val ntot = bv.edgecount
        var bme = slots[0]!!
        if (bme.l == null) return false
        for (i in 1 until ntot) {
            var shared = 0
            var firstSuc: BMesh.Edge? = null
            for (bme2 in BMesh.edgesOfVert(bv.v)) {
                if (tagTest(bme2)) continue
                for (f in facesOfEdge(bme2)) {
                    if (faceEdgeShareLoop(f, bme) != null) { shared++; if (firstSuc == null) firstSuc = bme2 }
                }
                if (shared >= 3) break
            }
            if (shared == 1 || (i == 1 && shared == 2)) {
                bme = firstSuc!!
                slots[i] = bme
                tagOn(bme)
            } else {
                for (k in 1 until i) { tagOff(slots[k]!!); slots[k] = null }
                return false
            }
        }
        return true
    }

    fun findBevelEdgeOrder(bv: BevelBevVert, firstIn: BMesh.Edge, slots: Array<BMesh.Edge?>, fprev: Array<BMesh.Face?>, fnext: Array<BMesh.Face?>) {
        val ntot = bv.edgecount
        var first: BMesh.Edge? = firstIn
        var i = 0
        while (true) {
            slots[i] = first
            tagOn(first!!)
            if (i == 0 && fastBevelEdgeOrder(bv, slots)) break
            i = bevelEdgeOrderExtend(bv, slots, i)
            i++
            if (i >= bv.edgecount) break
            first = null
            for (bme in BMesh.edgesOfVert(bv.v)) {
                if (tagTest(bme)) continue
                if (first == null) first = bme
                if (edgeFaceCount(bme) == 1) { first = bme; break }
            }
        }
        for (k in 0 until ntot) {
            val k2 = if (k == ntot - 1) 0 else k + 1
            val bme = slots[k]!!
            val bme2 = slots[k2]!!
            if (fnext[k] != null || fprev[k2] != null) continue
            var bestf: BMesh.Face? = null
            for (l in loopsOfEdge(bme)) {
                val f = l.f
                if (l.prev!!.e === bme2 || l.next!!.e === bme2) { if (bestf == null || l.v === bv.v) bestf = f }
                if (bestf != null) { fnext[k] = bestf; fprev[k2] = bestf }
            }
        }
    }

    fun bevelVertConstruct(bp: BevelParams, v: BMesh.Vert): BevelBevVert? {
        var nsel = 0; var totEdges = 0; var totWire = 0
        var first: BMesh.Edge? = null
        for (bme in BMesh.edgesOfVert(v)) {
            val fc = edgeFaceCount(bme)
            tagOff(bme)
            if (bme.tag && bp.affectType != BEVEL_AFFECT_VERTICES) { nsel++; if (first == null) first = bme }
            if (fc == 1) first = bme
            if (fc > 0 || bp.affectType == BEVEL_AFFECT_VERTICES) totEdges++
            if (BMesh.isWire(bme)) { totWire++; if (bp.affectType != BEVEL_AFFECT_VERTICES) tagOn(bme) }
        }
        if (first == null) first = v.e
        if ((nsel == 0 && bp.affectType != BEVEL_AFFECT_VERTICES) || (totEdges < 2 && bp.affectType == BEVEL_AFFECT_VERTICES)) {
            v.tag = false
            return null
        }
        val bv = BevelBevVert(v)
        bv.edgecount = totEdges
        bv.selcount = nsel
        bv.wirecount = totWire
        bv.offset = bp.offset
        bv.vmesh = BevelVMesh()
        bv.vmesh.seg = bp.seg
        bp.vertHash[v] = bv
        val slots = arrayOfNulls<BMesh.Edge>(totEdges)
        val fprev = arrayOfNulls<BMesh.Face>(totEdges)
        val fnext = arrayOfNulls<BMesh.Face>(totEdges)
        findBevelEdgeOrder(bv, first!!, slots, fprev, fnext)
        var edges = Array(totEdges) { idx ->
            BevelEdgeHalf().also { eh ->
                eh.e = slots[idx]!!
                eh.fprev = fprev[idx]; eh.fnext = fnext[idx]
                if (eh.e.tag && bp.affectType != BEVEL_AFFECT_VERTICES) { eh.isBev = true; eh.seg = bp.seg } else { eh.isBev = false; eh.seg = 0 }
                eh.isRev = eh.e.v2 === v
                eh.profileIndex = 0
            }
        }
        for (bme in BMesh.edgesOfVert(v)) tagOff(bme)
        if (totEdges > 1) {
            var sum = 0
            for (i in 0 until totEdges) sum += bevCcwTest(edges[i].e, edges[(i + 1) % totEdges].e, edges[i].fnext)
            if (sum < 0) {
                for (i in 0..totEdges / 2 - 1) {
                    val t = edges[i]; edges[i] = edges[totEdges - i - 1]; edges[totEdges - i - 1] = t
                    val a = edges[i]; val fp = a.fprev; a.fprev = a.fnext; a.fnext = fp
                    val b = edges[totEdges - i - 1]; val fp2 = b.fprev; b.fprev = b.fnext; b.fnext = fp2
                }
                if (totEdges % 2 == 1) { val m = edges[totEdges / 2]; val fp = m.fprev; m.fprev = m.fnext; m.fnext = fp }
            }
        }
        bv.edges = edges
        val vertAxis = FloatArray(3)
        if (bp.affectType == BEVEL_AFFECT_VERTICES) {
            val dw = bp.dvertWeights
            if (dw != null) bv.offset *= dw.getOrElse(v.index) { 0f }
            else if (bp.useWeights) bv.offset *= bp.vertWeights?.getOrElse(v.index) { 0f } ?: 0.0f
            if (bp.offsetType == BEVEL_AMT_WIDTH || bp.offsetType == BEVEL_AMT_DEPTH) {
                for (e in edges) {
                    val v2 = BMesh.otherVert(e.e, bv.v)!!
                    val d = sub(bv.v.co, v2.co)
                    BevelMath.normalize(d)
                    copy(vertAxis, add(vertAxis, d))
                }
            }
        }
        for (i in 0 until totEdges) {
            val e = edges[i]
            e.next = edges[(i + 1) % totEdges]
            e.prev = edges[(i + totEdges - 1) % totEdges]
        }
        for (e in edges) {
            if (e.isBev) {
                when (bp.offsetType) {
                    BEVEL_AMT_OFFSET -> e.offsetLSpec = bp.offset
                    BEVEL_AMT_WIDTH -> { val z = kotlin.math.abs(2.0f * kotlin.math.sin(edgeFaceAngle(e) / 2.0f)); e.offsetLSpec = if (z < BEVEL_EPSILON) 0.01f * bp.offset else bp.offset / z }
                    BEVEL_AMT_DEPTH -> { val z = kotlin.math.abs(kotlin.math.cos(edgeFaceAngle(e) / 2.0f)); e.offsetLSpec = if (z < BEVEL_EPSILON) 0.01f * bp.offset else bp.offset / z }
                    BEVEL_AMT_PERCENT -> {
                        e.offsetLSpec = BevelMath.lenV3V3(e.prev.e.v1.co, e.prev.e.v2.co) * bp.offset / 100.0f
                        e.offsetRSpec = BevelMath.lenV3V3(e.next.e.v1.co, e.next.e.v2.co) * bp.offset / 100.0f
                    }
                    BEVEL_AMT_ABSOLUTE -> { e.offsetLSpec = bp.offset; e.offsetRSpec = bp.offset }
                    else -> e.offsetLSpec = bp.offset
                }
                if (bp.offsetType != BEVEL_AMT_PERCENT && bp.offsetType != BEVEL_AMT_ABSOLUTE) e.offsetRSpec = e.offsetLSpec
                if (bp.useWeights) {
                    val w = edgeWeight(bp, e.e)
                    e.offsetLSpec *= w
                    e.offsetRSpec *= w
                }
            } else if (bp.affectType == BEVEL_AFFECT_VERTICES) {
                when (bp.offsetType) {
                    BEVEL_AMT_OFFSET -> e.offsetLSpec = bv.offset
                    BEVEL_AMT_WIDTH -> {
                        val d = sub(bv.v.co, BMesh.otherVert(e.e, bv.v)!!.co)
                        val z = kotlin.math.abs(2.0f * kotlin.math.sin(BevelMath.angleV3V3(vertAxis, d)))
                        e.offsetLSpec = if (z < BEVEL_EPSILON) 0.01f * bp.offset else bp.offset / z
                    }
                    BEVEL_AMT_DEPTH -> {
                        val d = sub(bv.v.co, BMesh.otherVert(e.e, bv.v)!!.co)
                        val z = kotlin.math.abs(kotlin.math.cos(BevelMath.angleV3V3(vertAxis, d)))
                        e.offsetLSpec = if (z < BEVEL_EPSILON) 0.01f * bp.offset else bp.offset / z
                    }
                    BEVEL_AMT_PERCENT -> e.offsetLSpec = BevelMath.lenV3V3(e.e.v1.co, e.e.v2.co) * bv.offset / 100.0f
                    BEVEL_AMT_ABSOLUTE -> e.offsetLSpec = bv.offset
                }
                e.offsetRSpec = e.offsetLSpec
            } else { e.offsetLSpec = 0.0f; e.offsetRSpec = 0.0f }
            e.offsetL = e.offsetLSpec
            e.offsetR = e.offsetRSpec
            val fp = e.fprev; val fn = e.fnext
            e.isSeam = if (fp != null && fn != null) !contigLdataAcrossEdge(bp.bm, e.e, fp, fn) else true
        }
        if (totWire != 0) bv.wireEdges = BMesh.edgesOfVert(v).filter { BMesh.isWire(it) }.toTypedArray()
        return bv
    }

    fun bevRebuildPolygon(bp: BevelParams, f: BMesh.Face): Boolean {
        var doRebuild = false
        val vv = ArrayList<BMesh.Vert>()
        val ee = ArrayList<BMesh.Edge>()
        val map = HashMap<BMesh.Vert, BMesh.Vert>()
        for (l in BMesh.loopsOfFace(f)) {
            if (l.v.tag) {
                val lprev = l.prev!!
                val bv = findBevvert(bp, l.v)!!
                val vm = bv.vmesh
                val e = findEdgeHalf(bv, l.e!!)!!
                val bme = e.e
                val eprev = findEdgeHalf(bv, lprev.e!!)!!
                val goCcw = if (e.prev === eprev) { if (eprev.prev === e) e.fnext !== f else true }
                else if (eprev.prev === e) false
                else countCcwEdgesBetween(eprev, e) < countCcwEdgesBetween(e, eprev)
                var onProfileStart = false
                var vstart: BevelBoundVert
                val vend: BevelBoundVert
                if (goCcw) {
                    vstart = eprev.rightv!!; vend = e.leftv!!
                    if (e.profileIndex > 0) { vstart = vstart.prev; onProfileStart = true }
                } else {
                    vstart = eprev.leftv!!; vend = e.rightv!!
                    if (eprev.profileIndex > 0) { vstart = vstart.next; onProfileStart = true }
                }
                var v = vstart
                if (!onProfileStart) { vv.add(v.nv.v!!); ee.add(bme); map.putIfAbsent(v.nv.v!!, l.v) }
                while (v !== vend) {
                    if (goCcw) {
                        val i = v.index
                        val kstart: Int
                        if (onProfileStart) { kstart = e.profileIndex; onProfileStart = false } else kstart = 1
                        val kend = if (eprev.rightv === v && eprev.profileIndex > 0) eprev.profileIndex else vm.seg
                        for (k in kstart..kend) {
                            val bmv = meshVert(vm, i, 0, k).v
                            if (bmv != null) { vv.add(bmv); ee.add(bme); map.putIfAbsent(bmv, l.v) }
                        }
                        v = v.next
                    } else {
                        val i = v.prev.index
                        val kstart: Int
                        if (onProfileStart) { kstart = eprev.profileIndex; onProfileStart = false } else kstart = vm.seg - 1
                        val kend = if (e.rightv === v.prev && e.profileIndex > 0) e.profileIndex else 0
                        var k = kstart
                        while (k >= kend) {
                            val bmv = meshVert(vm, i, 0, k).v
                            if (bmv != null) { vv.add(bmv); ee.add(bme); map.putIfAbsent(bmv, l.v) }
                            k--
                        }
                        v = v.prev
                    }
                }
                doRebuild = true
            } else {
                vv.add(l.v); ee.add(l.e!!); map.putIfAbsent(l.v, l.v)
            }
        }
        if (doRebuild) {
            val n = vv.size
            val fNew = bevCreateNgon(bp, vv.toTypedArray(), null, f, null, null, map, -1, true)
            var bmePrev = ee[n - 1]
            for (k in 0 until n) {
                val bmeNew = bp.bm.edgeExists(vv[k], vv[(k + 1) % n])!!
                if (ee[k] !== bmeNew) {
                    bmeNew.smooth = ee[k].smooth; bmeNew.seam = ee[k].seam; bmeNew.crease = ee[k].crease
                    if (k < n - 1 && ee[k] === ee[k + 1]) {
                        if (ee[k].seam && !bmePrev.seam) bmeNew.seam = false
                        if (!ee[k].smooth && bmePrev.smooth) bmeNew.smooth = true
                    } else bmePrev = ee[k]
                }
            }
            if (fNew != null) {
                recordFaceKind(bp, fNew, F_RECON)
                fNew.tag = false
            }
        }
        return doRebuild
    }

    fun bevelRebuildExistingPolygons(bp: BevelParams, v: BMesh.Vert, rebuilt: HashSet<BMesh.Face>) {
        for (f in bp.bm.facesOfVert(v)) {
            if (f !in rebuilt) { if (bevRebuildPolygon(bp, f)) rebuilt.add(f) }
        }
    }

    fun bevelReattachWires(bp: BevelParams, v: BMesh.Vert) {
        val bv = findBevvert(bp, v) ?: return
        if (bv.wirecount == 0) return
        for (i in 0 until bv.wirecount) {
            val e = bv.wireEdges[i]
            var vclosest: BMesh.Vert? = null
            var dclosest = Float.MAX_VALUE
            var votherclosest: BMesh.Vert? = null
            val vother = BMesh.otherVert(e, v)!!
            var bvother: BevelBevVert? = null
            if (vother.tag) {
                bvother = findBevvert(bp, vother) ?: return
            }
            var bndv = bv.vmesh.boundstart!!
            do {
                if (bvother != null) {
                    var bo = bvother.vmesh.boundstart!!
                    do {
                        val d = BevelMath.lenSqV3V3(bo.nv.co, bndv.nv.co)
                        if (d < dclosest) { vclosest = bndv.nv.v; votherclosest = bo.nv.v; dclosest = d }
                        bo = bo.next
                    } while (bo !== bvother.vmesh.boundstart)
                } else {
                    val d = BevelMath.lenSqV3V3(vother.co, bndv.nv.co)
                    if (d < dclosest) { vclosest = bndv.nv.v; votherclosest = vother; dclosest = d }
                }
                bndv = bndv.next
            } while (bndv !== bv.vmesh.boundstart)
            if (vclosest != null) bp.bm.edgeCreate(vclosest, votherclosest!!, e, true)
        }
    }

    fun bevvertIsWeldCross(bv: BevelBevVert) = bv.edgecount == 4 && bv.selcount == 2 && ((bv.edges[0].isBev && bv.edges[2].isBev) || (bv.edges[1].isBev && bv.edges[3].isBev))

    fun weldCrossAttrsCopy(bp: BevelParams, bv: BevelBevVert, vm: BevelVMesh, vmindex: Int, e: BevelEdgeHalf) {
        var prev: BMesh.Edge? = null
        var next: BMesh.Edge? = null
        for (i in 0 until 4) if (bv.edges[i] === e) { prev = bv.edges[(i + 3) % 4].e; next = bv.edges[(i + 1) % 4].e; break }
        val disableSeam = prev!!.seam != next!!.seam
        val enableSmooth = prev.smooth != next.smooth
        for (i in 0 until e.seg) {
            val bme = bp.bm.edgeExists(meshVert(vm, vmindex, 0, i).v!!, meshVert(vm, vmindex, 0, i + 1).v!!) ?: continue
            bme.smooth = prev.smooth; bme.seam = prev.seam; bme.crease = prev.crease
            if (disableSeam) bme.seam = false
            if (enableSmooth) bme.smooth = true
        }
    }

    fun bevelBuildEdgePolygons(bp: BevelParams, bme: BMesh.Edge) {
        val matNr = bp.matNr
        if (!BMesh.isManifold(bme)) return
        val bv1 = findBevvert(bp, bme.v1)!!
        val bv2 = findBevvert(bp, bme.v2)!!
        val e1 = findEdgeHalf(bv1, bme)!!
        val e2 = findEdgeHalf(bv2, bme)!!
        val nseg = e1.seg
        val bmv1 = e1.leftv!!.nv.v!!
        val bmv4 = e1.rightv!!.nv.v!!
        val bmv2 = e2.rightv!!.nv.v!!
        val bmv3 = e2.leftv!!.nv.v!!
        val f1 = e1.fprev
        val f2 = e1.fnext
        val faces = arrayOf(f1, f1, f2, f2)
        val i1 = e1.leftv!!.index
        val i2 = e2.leftv!!.index
        val vm1 = bv1.vmesh
        val vm2 = bv2.vmesh
        val verts = arrayOfNulls<BMesh.Vert>(4)
        verts[0] = bmv1
        verts[1] = bmv2
        val map = HashMap<BMesh.Vert, BMesh.Vert>()
        map.putIfAbsent(bmv1, bv1.v)
        map.putIfAbsent(bmv2, bv2.v)
        val odd = nseg % 2
        val mid = nseg / 2
        var fChoice: BMesh.Face? = null
        var centerAdjK = -1
        if (odd != 0 && e1.isSeam) {
            fChoice = chooseRepFace(bp, arrayOf(f1, f2))
            if (nseg > 1) centerAdjK = if (fChoice === f1) mid + 2 else mid
        }
        for (k in 1..nseg) {
            verts[3] = meshVert(vm1, i1, 0, k).v
            verts[2] = meshVert(vm2, i2, 0, nseg - k).v
            map.putIfAbsent(verts[3]!!, bv1.v)
            map.putIfAbsent(verts[2]!!, bv2.v)
            val vs = Array(4) { verts[it]!! }
            val rf: BMesh.Face?
            if (odd != 0 && k == mid + 1) {
                if (e1.isSeam) {
                    val edges: Array<BMesh.Edge?> = if (fChoice === f1) arrayOf(null, null, bme, bme) else arrayOf(bme, bme, null, null)
                    rf = bevCreateNgon(bp, vs, null, fChoice, edges, null, map, matNr, true)
                } else rf = bevCreateNgon(bp, vs, faces, fChoice, null, null, map, matNr, true)
            } else if (odd != 0 && k == centerAdjK && e1.isSeam) {
                val edges: Array<BMesh.Edge?>
                val fi: BMesh.Face?
                if (k == mid) { edges = arrayOf(null, null, bme, bme); fi = f1 } else { edges = arrayOf(bme, bme, null, null); fi = f2 }
                rf = bevCreateNgon(bp, vs, null, fi, edges, null, map, matNr, true)
            } else if (odd == 0 && k == mid) rf = bevCreateNgon(bp, vs, null, f1, arrayOf(null, null, bme, bme), null, map, matNr, true)
            else if (odd == 0 && k == mid + 1) rf = bevCreateNgon(bp, vs, null, f2, arrayOf(bme, bme, null, null), null, map, matNr, true)
            else rf = bevCreateNgon(bp, vs, null, if (k <= mid) f1 else f2, null, null, map, matNr, true)
            if (rf != null) {
                recordFaceKind(bp, rf, F_EDGE)
                for (l in BMesh.loopsOfFace(rf)) if (l.v === verts[0] || l.v === verts[2]) bp.longTag.add(l)
            }
            verts[0] = verts[3]
            verts[1] = verts[2]
        }
        val bme1 = bp.bm.edgeExists(bmv1, bmv2)
        val bme2 = bp.bm.edgeExists(bmv3, bmv4)
        for (x in arrayOf(bme1, bme2)) if (x != null) { x.smooth = bme.smooth; x.seam = bme.seam; x.crease = bme.crease }
        if (bevvertIsWeldCross(bv1)) weldCrossAttrsCopy(bp, bv1, vm1, i1, e1)
        if (bevvertIsWeldCross(bv2)) weldCrossAttrsCopy(bp, bv2, vm2, i2, e2)
    }

    fun findSuperellipseChordEndpoint(x0: Double, dtarget: Double, r: Float, rbig: Boolean): Double {
        val y0 = superellipseCo(x0, r, rbig)
        val tol = 1e-13
        var xmin = minOf(x0 + Math.sqrt(2.0) / 2.0 * dtarget, 1.0)
        var xmax = minOf(x0 + dtarget, 1.0)
        var ymin = superellipseCo(xmin, r, rbig)
        var ymax = superellipseCo(xmax, r, rbig)
        var dmaxerr = Math.sqrt(Math.pow(xmax - x0, 2.0) + Math.pow(ymax - y0, 2.0)) - dtarget
        var dminerr = Math.sqrt(Math.pow(xmin - x0, 2.0) + Math.pow(ymin - y0, 2.0)) - dtarget
        var xnew = xmax - dmaxerr * (xmax - xmin) / (dmaxerr - dminerr)
        var upper = true
        for (it in 0 until 10) {
            val ynew = superellipseCo(xnew, r, rbig)
            val dnewerr = Math.sqrt(Math.pow(xnew - x0, 2.0) + Math.pow(ynew - y0, 2.0)) - dtarget
            if (Math.abs(dnewerr) < tol) break
            if (dnewerr < 0) {
                xmin = xnew; ymin = ynew; dminerr = dnewerr
                xnew = if (!upper) (dmaxerr / 2 * xmin - dminerr * xmax) / (dmaxerr / 2 - dminerr) else xmax - dmaxerr * (xmax - xmin) / (dmaxerr - dminerr)
                upper = false
            } else {
                xmax = xnew; ymax = ynew; dmaxerr = dnewerr
                xnew = if (upper) (dmaxerr * xmin - dminerr / 2 * xmax) / (dmaxerr - dminerr / 2) else xmax - dmaxerr * (xmax - xmin) / (dmaxerr - dminerr)
                upper = true
            }
        }
        return xnew
    }

    fun findEvenSuperellipseChordsGeneral(seg: Int, r: Float, xvals: DoubleArray, yvals: DoubleArray) {
        val imax = (seg + 1) / 2 - 1
        val segOdd = seg % 2 == 1
        val rbig: Boolean
        val mx: Double
        if (r > 1.0f) { rbig = true; mx = Math.pow(0.5, 1.0 / r) } else { rbig = false; mx = 1 - Math.pow(0.5, 1.0 / r) }
        for (i in 0..imax) { xvals[i] = i * mx / seg * 2; yvals[i] = superellipseCo(xvals[i], r, rbig) }
        yvals[0] = 1.0
        for (iter in 0 until 10) {
            var sum = 0.0
            var dmin = 2.0
            var dmax = 0.0
            for (i in 0 until imax) {
                val d = Math.sqrt(Math.pow(xvals[i + 1] - xvals[i], 2.0) + Math.pow(yvals[i + 1] - yvals[i], 2.0))
                sum += d
                dmax = maxOf(d, dmax)
                dmin = minOf(d, dmin)
            }
            val davg: Double
            if (segOdd) { sum += Math.sqrt(2.0) / 2 * (yvals[imax] - xvals[imax]); davg = sum / (imax + 0.5) }
            else { sum += Math.sqrt(Math.pow(xvals[imax] - mx, 2.0) + Math.pow(yvals[imax] - mx, 2.0)); davg = sum / (imax + 1.0) }
            var reached = true
            if (dmax - davg > 1e-7) reached = false
            if (dmin - davg < 1e-7) reached = false
            if (reached) break
            for (i in 1..imax) { xvals[i] = findSuperellipseChordEndpoint(xvals[i - 1], davg, r, rbig); yvals[i] = superellipseCo(xvals[i], r, rbig) }
        }
        if (!segOdd) { xvals[imax + 1] = mx; yvals[imax + 1] = mx }
        for (i in imax + 1..seg) { yvals[i] = xvals[seg - i]; xvals[i] = yvals[seg - i] }
        if (!rbig) for (i in 0..seg) { val t = xvals[i]; xvals[i] = 1.0 - yvals[i]; yvals[i] = 1.0 - t }
    }

    fun findEvenSuperellipseChords(n: Int, r: Float, xvals: DoubleArray, yvals: DoubleArray) {
        val segOdd = n % 2 == 1
        val n2 = n / 2
        if (r == PRO_LINE_R) { for (i in 0..n) { xvals[i] = i.toDouble() / n; yvals[i] = 1.0 - i.toDouble() / n }; return }
        if (r == PRO_CIRCLE_R) { val t = (Math.PI / 2) / n; for (i in 0..n) { xvals[i] = Math.sin(i * t); yvals[i] = Math.cos(i * t) }; return }
        if (r == PRO_SQUARE_IN_R) {
            if (!segOdd) for (i in 0..n2) { xvals[i] = 0.0; yvals[i] = 1.0 - i.toDouble() / n2; xvals[n - i] = yvals[i]; yvals[n - i] = xvals[i] }
            else { val t = 1.0 / (n2 + Math.sqrt(2.0) / 2.0); for (i in 0..n2) { xvals[i] = 0.0; yvals[i] = 1.0 - i.toDouble() * t; xvals[n - i] = yvals[i]; yvals[n - i] = xvals[i] } }
            return
        }
        if (r == PRO_SQUARE_R) {
            if (!segOdd) for (i in 0..n2) { xvals[i] = i.toDouble() / n2; yvals[i] = 1.0; xvals[n - i] = yvals[i]; yvals[n - i] = xvals[i] }
            else { val t = 1.0 / (n2 + Math.sqrt(2.0) / 2); for (i in 0..n2) { xvals[i] = i.toDouble() * t; yvals[i] = 1.0; xvals[n - i] = yvals[i]; yvals[n - i] = xvals[i] } }
            return
        }
        findEvenSuperellipseChordsGeneral(n, r, xvals, yvals)
    }

    private val CIRCLE_FULLNESS = floatArrayOf(0.0f, 0.559f, 0.642f, 0.551f, 0.646f, 0.624f, 0.646f, 0.619f, 0.647f, 0.639f, 0.647f)

    fun findProfileFullness(bp: BevelParams): Float {
        val nseg = bp.seg
        if (bp.profileType == BEVEL_PROFILE_CUSTOM) {
            var f = 0.0f
            for (i in 0 until nseg) f += (bp.proSpacing.xvals!![i] + bp.proSpacing.yvals!![i]).toFloat() / (2.0f * nseg)
            return f
        }
        if (bp.proSuperR == PRO_LINE_R) return 0.0f
        if (bp.proSuperR == PRO_CIRCLE_R && nseg > 0 && nseg <= CIRCLE_FULLNESS.size) return CIRCLE_FULLNESS[nseg - 1]
        return if (nseg % 2 == 0) 2.4506f * bp.profile - 0.00000300f * nseg - 0.6266f else 2.3635f * bp.profile + 0.000152f * nseg - 0.6060f
    }

    fun setProfileSpacing(bp: BevelParams, sp: BevelProfileSpacing, custom: Boolean) {
        val seg = bp.seg
        if (seg <= 1) { sp.xvals = null; sp.yvals = null; sp.xvals2 = null; sp.yvals2 = null; sp.seg2 = 0; return }
        var p2 = 1
        while (p2 < seg) p2 = p2 shl 1
        val seg2 = maxOf(p2, 4)
        bp.proSpacing.seg2 = seg2
        val cp = bp.customProfile ?: BevelCurveProfile.line()
        fun fill(n: Int, xs: DoubleArray, ys: DoubleArray) {
            if (custom) {
                val pts = BevelCurveProfile.segments(cp, n)
                for (i in 0..n) { xs[i] = pts[i * 2 + 1].toDouble(); ys[i] = pts[i * 2].toDouble() }
            } else findEvenSuperellipseChords(n, bp.proSuperR, xs, ys)
        }
        val xv = DoubleArray(seg + 1); val yv = DoubleArray(seg + 1)
        if (seg2 == seg) { sp.xvals2 = xv; sp.yvals2 = yv }
        else { val x2 = DoubleArray(seg2 + 1); val y2 = DoubleArray(seg2 + 1); fill(seg2, x2, y2); sp.xvals2 = x2; sp.yvals2 = y2 }
        sp.xvals = xv; sp.yvals = yv
        fill(seg, xv, yv)
    }

    fun safeDivide(a: Float, b: Float) = if (b == 0f) 0f else a / b

    fun geometryCollideOffset(bp: BevelParams, eb: BevelEdgeHalf): Float {
        val noCollide = bp.offset + 1e6f
        var limit = noCollide
        if (bp.offset == 0.0f) return noCollide
        var kb = eb.offsetLSpec
        val ea = eb.next
        val ka = ea.offsetRSpec
        var vb: BMesh.Vert; var vc: BMesh.Vert
        if (eb.isRev) { vc = eb.e.v1; vb = eb.e.v2 } else { vb = eb.e.v1; vc = eb.e.v2 }
        val va = if (ea.isRev) ea.e.v1 else ea.e.v2
        val other = findOtherEndEdgeHalf(bp, eb)
        val ebother = other?.first
        val bvc = other?.second
        if (bp.offsetType == BEVEL_AMT_PERCENT || bp.offsetType == BEVEL_AMT_ABSOLUTE) {
            if (ea.isBev && ebother != null && ebother.prev.isBev) {
                if (bp.offsetType == BEVEL_AMT_PERCENT) return 50.0f
                val blen = BevelMath.lenV3V3(eb.e.v1.co, eb.e.v2.co)
                return if (bp.offset > blen / 2.0f) blen / 2.0f else blen
            }
            return noCollide
        }
        val ec: BevelEdgeHalf?
        val vd: BMesh.Vert
        val kc: Float
        if (ebother != null) {
            ec = ebother.prev
            vc = bvc!!.v
            kc = ec.offsetLSpec
            vd = if (ec.isRev) ec.e.v1 else ec.e.v2
        } else {
            kc = 0.0f
            ec = null
            val fn = eb.fnext ?: return noCollide
            val lb = faceEdgeShareLoop(fn, eb.e) ?: return noCollide
            vd = if (lb.next!!.v === vc) lb.next!!.next!!.v else if (lb.v === vc) lb.prev!!.v else return noCollide
        }
        if (ea.e === eb.e || (ec != null && ec.e === eb.e)) return noCollide
        val th1 = BevelMath.angleV3V3V3(va.co, vb.co, vc.co)
        val th2 = BevelMath.angleV3V3V3(vb.co, vc.co, vd.co)
        val sin1 = kotlin.math.sin(th1); val sin2 = kotlin.math.sin(th2)
        val cos1 = kotlin.math.cos(th1); val cos2 = kotlin.math.cos(th2)
        var proj = safeDivide(ka + cos1 * kb, sin1) + safeDivide(kc + cos2 * kb, sin2)
        if (proj > BEVEL_EPSILON) {
            proj = bp.offset * (BevelMath.lenV3V3(vb.co, vc.co) / proj)
            if (proj > BEVEL_EPSILON) limit = proj
        }
        if (kb > 1.1920929E-7f && (ka == 0.0f || kc == 0.0f)) {
            kb = bp.offset / kb
            if (ka == 0.0f) {
                var la = faceEdgeShareLoop(eb.fnext!!, ea.e)
                if (la != null) {
                    var slide = 0.0f
                    var ext = 0.0f
                    var first = true
                    while (ext < 0.0001f) {
                        if (first) { ext = Math.PI.toFloat() - th1; first = false }
                        else {
                            la = la!!.prev!!
                            ext += Math.PI.toFloat() - BevelMath.angleV3V3V3(la.v.co, la.next!!.v.co, la.next!!.next!!.v.co)
                        }
                        val le = la!!.e!!
                        slide += BevelMath.lenV3V3(le.v1.co, le.v2.co) * kotlin.math.sin(ext)
                    }
                    limit = minOf(slide * kb, limit)
                }
            }
            if (kc == 0.0f) {
                var lc = faceEdgeShareLoop(eb.fnext!!, eb.e)
                if (lc != null) {
                    lc = lc.next!!
                    var slide = 0.0f
                    var ext = 0.0f
                    var first = true
                    while (ext < 0.0001f) {
                        if (first) { ext = Math.PI.toFloat() - th2; first = false }
                        else {
                            lc = lc!!.next!!
                            ext += Math.PI.toFloat() - BevelMath.angleV3V3V3(lc.prev!!.v.co, lc.v.co, lc.next!!.v.co)
                        }
                        val le = lc!!.e!!
                        slide += BevelMath.lenV3V3(le.v1.co, le.v2.co) * kotlin.math.sin(ext)
                    }
                    limit = minOf(slide * kb, limit)
                }
            }
        }
        return limit
    }

    fun vertexCollideOffset(bp: BevelParams, ea: BevelEdgeHalf): Float {
        val noCollide = bp.offset + 1e6f
        if (bp.offset == 0.0f) return noCollide
        val ka = ea.offsetLSpec / bp.offset
        val eb = findOtherEndEdgeHalf(bp, ea)?.first
        val kb = if (eb != null) eb.offsetLSpec / bp.offset else 0.0f
        val kab = ka + kb
        val la = BevelMath.lenV3V3(ea.e.v1.co, ea.e.v2.co)
        if (kab <= 0.0f) return noCollide
        return la / kab
    }

    fun bevelLimitOffset(bp: BevelParams) {
        var limited = bp.offset
        for (bmv in bp.bm.verts()) {
            if (!bmv.tag) continue
            val bv = findBevvert(bp, bmv) ?: continue
            for (eh in bv.edges) limited = minOf(if (bp.affectType == BEVEL_AFFECT_VERTICES) vertexCollideOffset(bp, eh) else geometryCollideOffset(bp, eh), limited)
        }
        if (limited < bp.offset) {
            val factor = limited / bp.offset
            for (bmv in bp.bm.verts()) {
                if (!bmv.tag) continue
                val bv = findBevvert(bp, bmv) ?: continue
                for (eh in bv.edges) { eh.offsetLSpec *= factor; eh.offsetRSpec *= factor; eh.offsetL *= factor; eh.offsetR *= factor }
            }
            bp.offset = limited
        }
    }

    fun meshBevel(bp: BevelParams, profile: Float, limitOffset: Boolean) {
        val bm = bp.bm
        bp.seg = maxOf(bp.seg, 1)
        bp.profile = profile
        bp.proSuperR = -kotlin.math.ln(2.0f) / kotlin.math.ln(kotlin.math.sqrt(profile))
        bp.limitOffset = limitOffset
        bp.offsetAdjust = bp.affectType != BEVEL_AFFECT_VERTICES && bp.offsetType != BEVEL_AMT_PERCENT && bp.offsetType != BEVEL_AMT_ABSOLUTE
        if (bp.offset <= 0) return
        if (bp.vmeshMethod == BEVEL_VMESH_CUTOFF) { bp.miterOuter = BEVEL_MITER_SHARP; bp.miterInner = BEVEL_MITER_SHARP }
        if (profile >= 0.950f) bp.proSuperR = PRO_SQUARE_R
        else if (kotlin.math.abs(bp.proSuperR - PRO_CIRCLE_R) < 1e-4) bp.proSuperR = PRO_CIRCLE_R
        else if (kotlin.math.abs(bp.proSuperR - PRO_LINE_R) < 1e-4) bp.proSuperR = PRO_LINE_R
        else if (bp.proSuperR < 1e-4) bp.proSuperR = PRO_SQUARE_IN_R
        setProfileSpacing(bp, bp.proSpacing, bp.profileType == BEVEL_PROFILE_CUSTOM)
        if (bp.seg > 1) bp.proSpacing.fullness = findProfileFullness(bp)
        if (bp.profileType == BEVEL_PROFILE_CUSTOM && (bp.miterInner != BEVEL_MITER_SHARP || bp.miterOuter != BEVEL_MITER_SHARP)) setProfileSpacing(bp, bp.proSpacingMiter, false)
        mathLayerInfoInit(bp)
        uvVertMapInit(bp)
        for ((i, v) in bm.verts().withIndex()) v.index = i
        for (v in bm.verts()) {
            if (v.tag) {
                val bv = bevelVertConstruct(bp, v)
                if (!limitOffset && bv != null) buildBoundary(bp, bv, true)
                determineUvVertConnectivity(bp, v)
            }
        }
        if (limitOffset) {
            bevelLimitOffset(bp)
            for (v in bm.verts()) if (v.tag) findBevvert(bp, v)?.let { buildBoundary(bp, it, true) }
        }
        if (bp.offsetAdjust) adjustOffsets(bp)
        if (bp.profileType == BEVEL_PROFILE_CUSTOM) for (e in bm.edges()) if (e.tag) regularizeProfileOrientation(bp, e)
        for (v in bm.verts()) if (v.tag) findBevvert(bp, v)?.let { buildVmesh(bp, it) }
        if (bp.affectType != BEVEL_AFFECT_VERTICES) for (e in bm.edges()) if (e.tag) bevelBuildEdgePolygons(bp, e)
        for (v in bm.verts()) if (v.tag) findBevvert(bp, v)?.let { bevelExtendEdgeData(it) }
        val rebuilt = LinkedHashSet<BMesh.Face>()
        for (v in bm.verts()) {
            if (v.tag) {
                val set = HashSet<BMesh.Face>(rebuilt)
                bevelRebuildExistingPolygons(bp, v, set)
                for (f in set) rebuilt.add(f)
                bevelReattachWires(bp, v)
            }
        }
        for (f in rebuilt) if (f.alive) bm.faceKill(f)
        for (v in bm.verts()) {
            if (v.alive && v.tag) { uvVertMapPop(bp, v); bm.vertKill(v) }
        }
        bevelMergeUvs(bp)
        if (bp.hardenNormals) bevelHardenNormals(bp)
        if (bp.faceStrengthMode != BEVEL_FACE_STRENGTH_NONE) bevelSetWeightedNormalFaceStrength(bp)
    }
}

internal object BevelGeom {
    fun axisBasis(n: FloatArray): Pair<FloatArray, FloatArray> {
        val b1 = FloatArray(3); val b2 = FloatArray(3)
        val f = n[0] * n[0] + n[1] * n[1]
        if (f > 1.1920929E-7f) {
            val d = 1.0f / kotlin.math.sqrt(f)
            b1[0] = n[1] * d; b1[1] = -n[0] * d; b1[2] = 0.0f
            b2[0] = -n[2] * b1[1]; b2[1] = n[2] * b1[0]; b2[2] = n[0] * b1[1] - n[1] * b1[0]
        } else {
            b1[0] = if (n[2] < 0.0f) -1.0f else 1.0f
            b2[1] = 1.0f
        }
        return b1 to b2
    }

    fun faceCalcArea(f: BMesh.Face): Float {
        val n = FloatArray(3)
        for (l in BMesh.loopsOfFace(f)) {
            val a = l.v.co; val b = l.next!!.v.co
            n[0] += (a[1] - b[1]) * (a[2] + b[2])
            n[1] += (a[2] - b[2]) * (a[0] + b[0])
            n[2] += (a[0] - b[0]) * (a[1] + b[1])
        }
        return BevelMath.len(n) * 0.5f
    }

    fun faceCenterBounds(f: BMesh.Face): FloatArray {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (l in BMesh.loopsOfFace(f)) for (k in 0 until 3) { mn[k] = minOf(mn[k], l.v.co[k]); mx[k] = maxOf(mx[k], l.v.co[k]) }
        return BevelMath.mid(mn, mx)
    }

    fun areaPoly2(v: Array<FloatArray>): Float {
        var cross = 0.0f
        var prev = v[v.size - 1]
        for (cur in v) { cross += (cur[0] - prev[0]) * (cur[1] + prev[1]); prev = cur }
        return kotlin.math.abs(0.5f * cross)
    }

    fun facePointInsideTest(f: BMesh.Face, co: FloatArray): Boolean {
        val (b1, b2) = axisBasis(f.no)
        val px = BevelMath.dot(b1, co); val py = BevelMath.dot(b2, co)
        val ls = BMesh.loopsOfFace(f)
        val vx = FloatArray(ls.size) { BevelMath.dot(b1, ls[it].v.co) }
        val vy = FloatArray(ls.size) { BevelMath.dot(b2, ls[it].v.co) }
        var isect = false
        var j = ls.size - 1
        for (i in ls.indices) {
            if ((vy[i] > py) != (vy[j] > py) && px < (vx[j] - vx[i]) * (py - vy[i]) / (vy[j] - vy[i]) + vx[i]) isect = !isect
            j = i
        }
        return isect
    }

    fun invertM4(m: Array<FloatArray>): Array<FloatArray>? {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c][r].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r
            if (a[piv][col] == 0.0) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val d = a[col][col]
            for (c in 0 until 8) a[col][c] /= d
            for (r in 0 until 4) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in 0 until 8) a[r][c] -= f * a[col][c]
            }
        }
        return Array(4) { c -> FloatArray(4) { r -> a[r][c + 4].toFloat() } }
    }

    fun interpWeightsPoly2(v: Array<FloatArray>, co: FloatArray): FloatArray {
        val n = v.size
        val w = FloatArray(n)
        var maxValue = 0f
        for (p in v) { maxValue = maxOf(maxValue, kotlin.math.abs(p[0] - co[0])); maxValue = maxOf(maxValue, kotlin.math.abs(p[1] - co[1])) }
        val eps = 16.0f * 1.1920929E-7f * maxValue
        val epsSq = eps * eps
        fun dir(a: FloatArray): DoubleArray { val x = a[0].toDouble() - co[0].toDouble(); val y = a[1].toDouble() - co[1].toDouble(); return doubleArrayOf(x, y, Math.sqrt(x * x + y * y)) }
        fun ht(c: DoubleArray, nx: DoubleArray): Double {
            val area = c[0] * nx[1] - c[1] * nx[0]
            if (area != 0.0) {
                val d = c[0] * nx[0] + c[1] * nx[1]
                val r = (c[2] * nx[2] - d) / area
                if (!r.isNaN() && !r.isInfinite()) return r
            }
            return 0.0
        }
        var iCurr = n - 1
        var iNext = 0
        var vCurr = v[iCurr]
        var dCurr = dir(v[(n - 2 + n) % n])
        var dNext = dir(vCurr)
        var htPrev = ht(dCurr, dNext)
        var flag = 0
        var tot = 0.0f
        while (iNext < n) {
            val vNext = v[iNext]
            if (dNext[2] < eps) { flag = 1; break }
            val cl = closest2(co, vCurr, vNext)
            val dx = cl[0] - co[0]; val dy = cl[1] - co[1]
            if (dx * dx + dy * dy < epsSq) { flag = 2; break }
            dCurr = dNext
            dNext = dir(vNext)
            val h = ht(dCurr, dNext)
            w[iCurr] = if (dCurr[2] == 0.0) 0.0f else ((htPrev + h) / dCurr[2]).toFloat()
            tot += w[iCurr]
            iCurr = iNext++
            vCurr = vNext
            htPrev = h
        }
        if (flag != 0) {
            w.fill(0f)
            if (flag == 1) w[iCurr] = 1.0f
            else {
                val vNext = v[iNext % n]
                val ux = vNext[0] - vCurr[0]; val uy = vNext[1] - vCurr[1]
                val hx = co[0] - vCurr[0]; val hy = co[1] - vCurr[1]
                val d = ux * ux + uy * uy
                var fac = if (d > 0f) (ux * hx + uy * hy) / d else 0f
                fac = fac.coerceIn(0.0f, 1.0f)
                w[iCurr] = 1.0f - fac
                w[iNext % n] = fac
            }
        } else if (tot != 0.0f) for (i in 0 until n) w[i] /= tot
        return w
    }

    private fun closest2(p: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]
        val d = ux * ux + uy * uy
        if (d == 0f) return a.copyOf()
        val lambda = ((p[0] - a[0]) * ux + (p[1] - a[1]) * uy) / d
        if (!(lambda > 0.0f)) return a.copyOf()
        if (!(lambda < 1.0f)) return b.copyOf()
        return floatArrayOf(a[0] + ux * lambda, a[1] + uy * lambda)
    }

    fun loopInterpFromFace(bm: BMesh, lDst: BMesh.Loop, fSrc: BMesh.Face, doVertex: Boolean) {
        val (b1, b2) = axisBasis(fSrc.no)
        val ls = BMesh.loopsOfFace(fSrc)
        val cos = Array(ls.size) { floatArrayOf(BevelMath.dot(b1, ls[it].v.co), BevelMath.dot(b2, ls[it].v.co)) }
        val co = floatArrayOf(BevelMath.dot(b1, lDst.v.co), BevelMath.dot(b2, lDst.v.co))
        val w = interpWeightsPoly2(cos, co)
        if (bm.hasUv) {
            var u = 0f; var vv = 0f
            for (i in ls.indices) { u += ls[i].u * w[i]; vv += ls[i].w * w[i] }
            lDst.u = u; lDst.w = vv
        }
        if (bm.hasCornerColor) lDst.color = mixColors(IntArray(ls.size) { ls[it].color }, w)
        if (doVertex) {
            val v = lDst.v
            if (bm.hasPointColor) v.color = mixColors(IntArray(ls.size) { ls[it].v.color }, w)
            if (bm.hasVertCrease) { var c = 0f; for (i in ls.indices) c += ls[i].v.crease * w[i]; v.crease = c }
            if (bm.hasDvert) {
                val nodes = ArrayList<FloatArray>()
                for (i in ls.indices) for ((g, x) in ls[i].v.dvert) {
                    val ww = x * w[i]
                    if (ww == 0.0f) continue
                    val n = nodes.firstOrNull { it[0].toInt() == g }
                    if (n != null) n[1] += ww else nodes.add(0, floatArrayOf(g.toFloat(), ww))
                }
                v.dvert = nodes.map { it[0].toInt() to minOf(it[1], 1.0f) }
            }
        }
    }

    private fun mixColors(c: IntArray, w: FloatArray): Int {
        var out = 0
        for (sh in intArrayOf(24, 16, 8, 0)) {
            var a = 0f
            for (i in c.indices) a += ((c[i] ushr sh) and 0xFF) * w[i]
            val q = (a + 0.5f).let { if (it <= 0f) 0 else if (it >= 255f) 255 else it.toInt() }
            out = out or (q shl sh)
        }
        return out
    }
}

internal object BevelCurveProfile {
    const val HD_FREE = 0
    const val HD_AUTO = 1
    const val HD_VECT = 2
    const val HD_ALIGN = 3
    const val PROF_SAMPLE_STRAIGHT_EDGES = 1 shl 2
    const val PROF_SAMPLE_EVEN_LENGTHS = 1 shl 3

    class Point(var x: Float, var y: Float, var h1: Int, var h2: Int) {
        val h1Loc = FloatArray(2)
        val h2Loc = FloatArray(2)
    }

    class Profile(val path: Array<Point>, val flag: Int)

    fun line() = Profile(arrayOf(Point(1f, 0f, HD_AUTO, HD_AUTO), Point(0f, 1f, HD_AUTO, HD_AUTO)), 1)

    private fun tableSize(p: Profile) = ((p.path.size - 1) * 16 + 1).coerceIn(1, 512)

    private fun lenV2(x: Float, y: Float) = kotlin.math.sqrt(x * x + y * y)

    private fun pointCalculateHandle(point: Point, prev: Point?, next: Point?) {
        if (point.h1 == HD_FREE && point.h2 == HD_FREE) return
        val prevX: Float; val prevY: Float; val nextX: Float; val nextY: Float
        if (prev == null) { prevX = 2.0f * point.x - next!!.x; prevY = 2.0f * point.y - next.y } else { prevX = prev.x; prevY = prev.y }
        if (next == null) { nextX = 2.0f * point.x - prev!!.x; nextY = 2.0f * point.y - prev.y } else { nextX = next.x; nextY = next.y }
        val ax = point.x - prevX; val ay = point.y - prevY
        val bx = nextX - point.x; val by = nextY - point.y
        var lenA = lenV2(ax, ay)
        var lenB = lenV2(bx, by)
        if (lenA == 0.0f) lenA = 1.0f
        if (lenB == 0.0f) lenB = 1.0f
        if (point.h1 == HD_AUTO || point.h2 == HD_AUTO) {
            val tx = bx / lenB + ax / lenA
            val ty = by / lenB + ay / lenA
            val len = lenV2(tx, ty) * 2.5614f
            if (len != 0.0f) {
                if (point.h1 == HD_AUTO) {
                    lenA /= len
                    point.h1Loc[0] = point.x + tx * -lenA; point.h1Loc[1] = point.y + ty * -lenA
                }
                if (point.h2 == HD_AUTO) {
                    lenB /= len
                    point.h2Loc[0] = point.x + tx * lenB; point.h2Loc[1] = point.y + ty * lenB
                }
            }
        }
        if (point.h1 == HD_VECT) { point.h1Loc[0] = point.x + ax * (-1.0f / 3.0f); point.h1Loc[1] = point.y + ay * (-1.0f / 3.0f) }
        if (point.h2 == HD_VECT) { point.h2Loc[0] = point.x + bx * (1.0f / 3.0f); point.h2Loc[1] = point.y + by * (1.0f / 3.0f) }
    }

    private fun calculatePathHandles(path: Array<Point>) {
        val n = path.size
        pointCalculateHandle(path[0], null, path[1])
        for (i in 1 until n - 1) pointCalculateHandle(path[i], path[i - 1], path[i + 1])
        pointCalculateHandle(path[n - 1], path[n - 2], null)
    }

    private fun normalizeV2(v: FloatArray) {
        val d = v[0] * v[0] + v[1] * v[1]
        if (d > 1.0e-35f) { val inv = 1.0f / kotlin.math.sqrt(d); v[0] *= inv; v[1] *= inv } else { v[0] = 0f; v[1] = 0f }
    }

    private fun saasin(x: Float): Float = if (x <= -1.0f) (-Math.PI / 2).toFloat() else if (x >= 1.0f) (Math.PI / 2).toFloat() else kotlin.math.asin(x)

    private fun angleV2V2(a: FloatArray, b: FloatArray): Float {
        normalizeV2(a); normalizeV2(b)
        if (a[0] * b[0] + a[1] * b[1] >= 0.0f) return 2.0f * saasin(lenV2(a[0] - b[0], a[1] - b[1]) / 2.0f)
        return Math.PI.toFloat() - 2.0f * saasin(lenV2(a[0] + b[0], a[1] + b[1]) / 2.0f)
    }

    private fun edgeHandleAngle(path: Array<Point>, i: Int): Float {
        val s = floatArrayOf(path[i].h2Loc[0] - path[i].x, path[i].h2Loc[1] - path[i].y)
        val e = floatArrayOf(path[i + 1].x - path[i + 1].h1Loc[0], path[i + 1].y - path[i + 1].h1Loc[1])
        return angleV2V2(s, e)
    }

    private fun heapsort(arr: IntArray, a: Int, n0: Int, cmp: (Int, Int) -> Int) {
        var nmemb = n0
        if (nmemb <= 1) return
        fun b(i: Int) = a + i - 1
        fun create(l: Int, nm: Int) {
            var parI = l
            while (parI * 2 <= nm) {
                var childI = parI * 2
                if (childI < nm && cmp(arr[b(childI)], arr[b(childI + 1)]) < 0) childI++
                if (cmp(arr[b(childI)], arr[b(parI)]) <= 0) break
                val t = arr[b(parI)]; arr[b(parI)] = arr[b(childI)]; arr[b(childI)] = t
                parI = childI
            }
        }
        var l = nmemb / 2 + 1
        while (--l != 0) create(l, nmemb)
        while (nmemb > 1) {
            val k = arr[b(nmemb)]
            arr[b(nmemb)] = arr[b(1)]
            nmemb--
            var parI = 1
            while (parI * 2 <= nmemb) {
                var childI = parI * 2
                if (childI < nmemb && cmp(arr[b(childI)], arr[b(childI + 1)]) < 0) childI++
                arr[b(parI)] = arr[b(childI)]
                parI = childI
            }
            while (true) {
                val childI = parI
                parI = childI / 2
                if (childI == 1 || cmp(k, arr[b(parI)]) < 0) { arr[b(childI)] = k; break }
                arr[b(childI)] = arr[b(parI)]
            }
        }
    }

    fun darwinQsort(arr: IntArray, n: Int, cmp: (Int, Int) -> Int) {
        if (n > 0) darwinQsortRange(arr, 0, n, cmp, 2 * (32 - Integer.numberOfLeadingZeros(n) - 1))
    }

    private fun darwinQsortRange(arr: IntArray, lo: Int, n0: Int, cmp: (Int, Int) -> Int, depth0: Int) {
        var a = lo
        var n = n0
        var depth = depth0
        fun swap(i: Int, j: Int) { val t = arr[i]; arr[i] = arr[j]; arr[j] = t }
        fun c(i: Int, j: Int) = cmp(arr[i], arr[j])
        fun med3(x: Int, y: Int, z: Int): Int =
            if (c(x, y) < 0) (if (c(y, z) < 0) y else if (c(x, z) < 0) z else x)
            else (if (c(y, z) > 0) y else if (c(x, z) < 0) x else z)
        fun vecswap(i: Int, j: Int, cnt: Int) { for (k in 0 until cnt) swap(i + k, j + k) }
        while (true) {
            if (depth <= 0) { heapsort(arr, a, n, cmp); return }
            depth--
            var swapCnt = 0
            if (n < 8) {
                var pm = a + 1
                while (pm < a + n) {
                    var pl = pm
                    while (pl > a && c(pl - 1, pl) > 0) { swap(pl, pl - 1); pl-- }
                    pm++
                }
                return
            }
            var pm = a + n / 2
            var pl = a
            var pn = a + n - 1
            if (n > 40) {
                val d = n / 8
                pl = med3(pl, pl + d, pl + 2 * d)
                pm = med3(pm - d, pm, pm + d)
                pn = med3(pn - 2 * d, pn - d, pn)
            }
            pm = med3(pl, pm, pn)
            swap(a, pm)
            var pa = a + 1
            var pb = pa
            var pc = a + n - 1
            var pd = pc
            while (true) {
                while (pb <= pc) {
                    val r = c(pb, a)
                    if (r > 0) break
                    if (r == 0) { swapCnt = 1; swap(pa, pb); pa++ }
                    pb++
                }
                while (pb <= pc) {
                    val r = c(pc, a)
                    if (r < 0) break
                    if (r == 0) { swapCnt = 1; swap(pc, pd); pd-- }
                    pc--
                }
                if (pb > pc) break
                swap(pb, pc)
                swapCnt = 1
                pb++
                pc--
            }
            if (swapCnt == 0) {
                val limit = 1 + n / 4
                var bail = false
                var q = a + 1
                outer@ while (q < a + n) {
                    var r = q
                    while (r > a && c(r - 1, r) > 0) {
                        swap(r, r - 1)
                        if (++swapCnt > limit) { bail = true; break@outer }
                        r--
                    }
                    q++
                }
                if (!bail) return
            }
            val end = a + n
            var d1 = minOf(pa - a, pb - pa)
            vecswap(a, pb - d1, d1)
            d1 = minOf(pd - pc, end - pd - 1)
            vecswap(pb, end - d1, d1)
            d1 = pb - pa
            val d2 = pd - pc
            if (d1 <= d2) {
                if (d1 > 1) darwinQsortRange(arr, a, d1, cmp, depth)
                if (d2 > 1) { a = end - d2; n = d2; continue }
            } else {
                if (d2 > 1) darwinQsortRange(arr, end - d2, d2, cmp, depth)
                if (d1 > 1) { n = d1; continue }
            }
            return
        }
    }

    private fun forwardDiff(q0In: Float, q1In: Float, q2In: Float, q3In: Float, out: FloatArray, start: Int, it: Int) {
        var f = it.toFloat()
        val rt0 = q0In
        val rt1 = 3.0f * (q1In - q0In) / f
        f *= f
        val rt2 = 3.0f * (q0In - 2.0f * q1In + q2In) / f
        f *= it.toFloat()
        val rt3 = (q3In - q0In + 3.0f * (q1In - q2In)) / f
        var q0 = rt0
        var q1 = rt1 + rt2 + rt3
        var q2 = 2 * rt2 + 6 * rt3
        val q3 = 6 * rt3
        for (k in 0..it) {
            out[(start + k) * 2] = q0
            q0 += q1
            q1 += q2
            q2 += q3
        }
    }

    private fun createSamples(p: Profile, nSegments: Int, sampleStraight: Boolean, xs: FloatArray, ys: FloatArray) {
        val path = p.path
        val totedges = path.size - 1
        calculatePathHandles(path)
        val curv = FloatArray(totedges) { edgeHandleAngle(path, it) }
        val sorted = IntArray(totedges) { it }
        darwinQsort(sorted, totedges) { x, y -> if (curv[x] > curv[y]) 0 else 1 }
        val nSamples = IntArray(totedges)
        fun curved(i: Int) = path[i].h2 != HD_VECT || path[i + 1].h1 != HD_VECT
        var nLeft: Int
        if (nSegments >= totedges) {
            if (sampleStraight) {
                val nCommon = nSegments / totedges
                nLeft = nSegments % totedges
                if (nCommon > 0) for (i in 0 until totedges) nSamples[i] = nCommon
            } else {
                var nCurved = 0
                for (i in 0 until totedges) if (curved(i)) nCurved++
                nCurved = if (nCurved == 0) totedges else nCurved
                nLeft = nSegments - (totedges - nCurved)
                val nCommon = nLeft / nCurved
                if (nCommon > 0) {
                    for (i in 0 until totedges) {
                        if (curved(i) || nCurved == totedges) nSamples[i] += nCommon else nSamples[i] = 1
                    }
                }
                nLeft -= nCommon * nCurved
            }
        } else nLeft = nSegments
        for (i in 0 until nLeft) nSamples[sorted[i]]++
        val bx = FloatArray((nSegments + 1) * 2)
        val by = FloatArray((nSegments + 1) * 2)
        for (k in 0..nSegments) { bx[k * 2] = xs[k]; by[k * 2] = ys[k] }
        var iSample = 0
        for (i in 0 until totedges) {
            if (nSamples[i] > 0) {
                forwardDiff(path[i].x, path[i].h2Loc[0], path[i + 1].h1Loc[0], path[i + 1].x, bx, iSample, nSamples[i])
                forwardDiff(path[i].y, path[i].h2Loc[1], path[i + 1].h1Loc[1], path[i + 1].y, by, iSample, nSamples[i])
            }
            iSample += nSamples[i]
        }
        for (k in 0..nSegments) { xs[k] = bx[k * 2]; ys[k] = by[k * 2] }
    }

    private fun interpf(a: Float, b: Float, t: Float) = t * a + (1.0f - t) * b

    private fun evenSpacing(tx: FloatArray, ty: FloatArray, tableSize: Int, nSegments: Int, xs: FloatArray, ys: FloatArray) {
        fun dist(i: Int) = lenV2(tx[i] - tx[i + 1], ty[i] - ty[i + 1])
        var total = 0f
        for (i in 0 until tableSize - 1) total += dist(i)
        val segLen = total / nSegments
        var toNext = dist(0)
        var toPrev = 0.0f
        var iTable = 0
        xs[0] = tx[0]; ys[0] = ty[0]
        var left = segLen
        for (i in 1 until nSegments) {
            while (toNext < left) {
                left -= toNext
                iTable++
                toNext = dist(iTable)
                toPrev = 0.0f
            }
            val factor = (toPrev + left) / (toPrev + toNext)
            xs[i] = interpf(tx[iTable + 1], tx[iTable], factor)
            ys[i] = interpf(ty[iTable + 1], ty[iTable], factor)
            toNext -= left
            toPrev += left
            left = segLen
        }
    }

    fun segments(p: Profile, n: Int): FloatArray {
        val ts = tableSize(p)
        val tx = FloatArray(ts + 1)
        val ty = FloatArray(ts + 1)
        if (ts > 1) createSamples(p, ts - 1, false, tx, ty)
        tx[ts - 1] = 0.0f; ty[ts - 1] = 1.0f
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        if (p.flag and PROF_SAMPLE_EVEN_LENGTHS != 0) evenSpacing(tx, ty, ts, n, xs, ys)
        else createSamples(p, n, p.flag and PROF_SAMPLE_STRAIGHT_EDGES != 0, xs, ys)
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) { out[i * 2] = xs[i]; out[i * 2 + 1] = ys[i] }
        return out
    }
}

internal object BlendBevelModifier {
    fun apply(
        d: BlendMeshData, value: Float, res: Int, flags: Int, valFlags: Int, profileType: Int, limFlags: Int, edgeFlags: Int,
        matIn: Int, faceStrMode: Int, miterInner: Int, miterOuter: Int, vmeshMethod: Int, affectType: Int,
        profile: Float, bevelAngle: Float, spread: Float, defgrpName: String, edgeWeightName: String, vertWeightName: String, totcol: Int,
        customProfile: BevelCurveProfile.Profile?
    ): BlendMeshData {
        val bm = BMesh.fromMesh(d, true, true)
        val threshold = kotlin.math.cos(bevelAngle + 0.000000175f)
        val vgroup = if (limFlags and (1 shl 5) != 0 && defgrpName.isNotEmpty()) d.groups.indexOf(defgrpName) else -1
        val invert = flags and (1 shl 2) != 0
        val dv = d.dverts
        fun vgWeight(v: Int): Float {
            if (vgroup == -1) return 1.0f
            if (dv == null) return if (invert) 1.0f else 0.0f
            var w = dv.getOrNull(v)?.firstOrNull { it.first == vgroup }?.second ?: 0f
            if (invert) w = 1.0f - w
            return w
        }
        val vw = d.vertAttrs[vertWeightName]
        val ewMap = d.edgeAttrs[edgeWeightName]
        val verts = bm.verts()
        for ((i, v) in verts.withIndex()) v.index = i
        if (affectType == BlendBevel.BEVEL_AFFECT_VERTICES) {
            for (v in verts) {
                if (limFlags and (1 shl 4) != 0) {
                    val w = vw?.getOrNull(v.index) ?: 0f
                    if (w == 0.0f) continue
                } else if (vgWeight(v.index) < 0.5f) continue
                v.tag = true
            }
        } else if (limFlags and (1 shl 3) != 0) {
            for (e in bm.edges()) {
                val pair = BMesh.edgeLoopPair(e) ?: continue
                if (BevelMath.dot(pair.first.f.no, pair.second.f.no) < threshold) { e.tag = true; e.v1.tag = true; e.v2.tag = true }
            }
        } else {
            for (e in bm.edges()) {
                if (!BMesh.isManifold(e)) continue
                if (limFlags and (1 shl 4) != 0) {
                    val w = ewMap?.get(BlendMeshData.key(e.v1.index, e.v2.index)) ?: 0f
                    if (w == 0.0f) continue
                } else if (vgWeight(e.v1.index) < 0.5f || vgWeight(e.v2.index) < 0.5f) continue
                e.tag = true; e.v1.tag = true; e.v2.tag = true
            }
        }
        val bp = BevelParams(bm)
        bp.offset = value
        bp.offsetType = valFlags
        bp.profileType = profileType
        bp.seg = res
        bp.affectType = affectType
        bp.useWeights = limFlags and (1 shl 4) != 0
        if (vw != null) bp.vertWeights = vw
        if (ewMap != null) {
            val m = HashMap<BMesh.Edge, Float>()
            for (e in bm.edges()) m[e] = ewMap[BlendMeshData.key(e.v1.index, e.v2.index)] ?: 0f
            bp.edgeWeights = m
        }
        if (vgroup != -1 && dv != null) bp.dvertWeights = FloatArray(d.nV) { v -> dv.getOrNull(v)?.firstOrNull { it.first == vgroup }?.second ?: 0f }
        bp.matNr = matIn.coerceIn(-1, totcol - 1)
        bp.loopSlide = flags and (1 shl 14) == 0
        bp.markSeam = edgeFlags and 1 != 0
        bp.markSharp = edgeFlags and 2 != 0
        bp.hardenNormals = flags and (1 shl 15) != 0
        bp.faceStrengthMode = faceStrMode
        bp.miterOuter = miterOuter
        bp.miterInner = miterInner
        bp.spread = spread
        bp.customProfile = customProfile
        bp.vmeshMethod = vmeshMethod
        bp.inputCustomNormals = d.customShort != null || d.customFloat != null
        BlendBevel.meshBevel(bp, profile, flags and (1 shl 13) == 0)
        val r = bm.toMesh(d.groups, d)
        if (bp.harden.isNotEmpty()) {
            val spaces = BlendBevel.bmLnorSpaces(bm)
            val data = ShortArray(r.corners.size * 2)
            var c = 0
            for (f in bm.faces()) for (l in BMesh.loopsOfFace(f)) {
                val t = bp.harden[l]
                val sp = spaces[l]
                if (t != null && sp != null) { val e = BlendNormals.encode(sp, t); data[c * 2] = e[0]; data[c * 2 + 1] = e[1] }
                c++
            }
            r.customShort = data
            r.customFloat = null
        }
        return r
    }
}
