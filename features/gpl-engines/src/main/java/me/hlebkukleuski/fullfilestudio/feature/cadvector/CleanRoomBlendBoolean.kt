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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object BlendBooleanModifier {

    const val OP_INTERSECT = 0
    const val OP_UNION = 1
    const val OP_DIFFERENCE = 2

    const val MAT_MODE_INDEX = 0
    const val MAT_MODE_TRANSFER = 1

    const val FLAG_SELF = 1
    const val FLAG_OBJECT = 2
    const val FLAG_COLLECTION = 4
    const val FLAG_HOLE_TOLERANT = 8

    private const val CLASS_OUTSIDE = 0
    private const val CLASS_INSIDE = 1
    private const val CLASS_COPLANAR_SAME = 2
    private const val CLASS_COPLANAR_OPP = 3

    class Operand(
        val mesh: BlendMeshData,
        val relMatrix: FloatArray,
        val matRemap: IntArray?
    )

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
            return if (l > 1e-15) Vec3(x / l, y / l, z / l) else Vec3(0.0, 0.0, 0.0)
        }
    }

    private data class Vec2(val x: Double, val y: Double) {
        operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
        operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
        operator fun times(s: Double) = Vec2(x * s, y * s)
        fun dot(o: Vec2) = x * o.x + y * o.y
        fun cross(o: Vec2) = x * o.y - y * o.x
        fun lenSq() = x * x + y * y
    }

    private class SpatialVertPool(private val eps: Double) {
        val verts = ArrayList<Vec3>()
        private val invCell = 1.0 / max(eps * 2.0, 1e-9)
        private val epsSq = eps * eps
        private val grid = HashMap<Long, ArrayList<Int>>()

        private fun hash(ix: Long, iy: Long, iz: Long): Long =
            (ix * 73856093L) xor (iy * 19349663L) xor (iz * 83492791L)

        fun intern(p: Vec3): Int {
            val cx = floor(p.x * invCell).toLong()
            val cy = floor(p.y * invCell).toLong()
            val cz = floor(p.z * invCell).toLong()
            var bestId = -1
            var bestDist = epsSq
            for (dx in -1L..1L) {
                for (dy in -1L..1L) {
                    for (dz in -1L..1L) {
                        val bucket = grid[hash(cx + dx, cy + dy, cz + dz)] ?: continue
                        for (id in bucket) {
                            val d = (verts[id] - p).lenSq()
                            if (d < bestDist) {
                                bestDist = d
                                bestId = id
                            }
                        }
                    }
                }
            }
            if (bestId >= 0) return bestId
            val id = verts.size
            verts.add(p)
            grid.getOrPut(hash(cx, cy, cz)) { ArrayList(4) }.add(id)
            return id
        }
    }

    private class Tri(
        val v0: Int,
        val v1: Int,
        val v2: Int,
        val origMesh: Int,
        val origFace: Int,
        val mat: Int,
        val smooth: Boolean,
        val uv0x: Float, val uv0y: Float,
        val uv1x: Float, val uv1y: Float,
        val uv2x: Float, val uv2y: Float,
        val cc0: Int, val cc1: Int, val cc2: Int,
        val dv0: List<Pair<Int, Float>>?,
        val dv1: List<Pair<Int, Float>>?,
        val dv2: List<Pair<Int, Float>>?,
        val normal: Vec3,
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double
    ) {
        fun vert(k: Int) = when (k) { 0 -> v0; 1 -> v1; else -> v2 }
    }

    private class BvhNode(
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
        val left: BvhNode?,
        val right: BvhNode?,
        val triIndices: IntArray?
    )

    private class TriBvh(private val tris: List<Tri>) {
        val root: BvhNode? = if (tris.isEmpty()) null else build(IntArray(tris.size) { it }, 0, tris.size)

        private fun build(ids: IntArray, start: Int, end: Int): BvhNode {
            var mnX = Double.POSITIVE_INFINITY; var mnY = Double.POSITIVE_INFINITY; var mnZ = Double.POSITIVE_INFINITY
            var mxX = Double.NEGATIVE_INFINITY; var mxY = Double.NEGATIVE_INFINITY; var mxZ = Double.NEGATIVE_INFINITY
            for (i in start until end) {
                val t = tris[ids[i]]
                if (t.minX < mnX) mnX = t.minX; if (t.minY < mnY) mnY = t.minY; if (t.minZ < mnZ) mnZ = t.minZ
                if (t.maxX > mxX) mxX = t.maxX; if (t.maxY > mxY) mxY = t.maxY; if (t.maxZ > mxZ) mxZ = t.maxZ
            }
            val count = end - start
            if (count <= 8) {
                return BvhNode(mnX, mnY, mnZ, mxX, mxY, mxZ, null, null, ids.copyOfRange(start, end))
            }
            val dx = mxX - mnX; val dy = mxY - mnY; val dz = mxZ - mnZ
            val axis = if (dx >= dy && dx >= dz) 0 else if (dy >= dz) 1 else 2
            val sub = Array(count) { ids[start + it] }
            sub.sortWith { a, b ->
                val ta = tris[a]; val tb = tris[b]
                val ca = when (axis) { 0 -> ta.minX + ta.maxX; 1 -> ta.minY + ta.maxY; else -> ta.minZ + ta.maxZ }
                val cb = when (axis) { 0 -> tb.minX + tb.maxX; 1 -> tb.minY + tb.maxY; else -> tb.minZ + tb.maxZ }
                ca.compareTo(cb)
            }
            for (k in 0 until count) ids[start + k] = sub[k]
            val mid = start + count / 2
            return BvhNode(mnX, mnY, mnZ, mxX, mxY, mxZ, build(ids, start, mid), build(ids, mid, end), null)
        }

        fun queryBox(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, out: ArrayList<Int>) {
            val r = root ?: return
            queryBoxRec(r, minX, minY, minZ, maxX, maxY, maxZ, out)
        }

        private fun queryBoxRec(n: BvhNode, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, out: ArrayList<Int>) {
            if (n.maxX < minX || n.minX > maxX || n.maxY < minY || n.minY > maxY || n.maxZ < minZ || n.minZ > maxZ) return
            val leaf = n.triIndices
            if (leaf != null) {
                for (id in leaf) {
                    val t = tris[id]
                    if (t.maxX >= minX && t.minX <= maxX && t.maxY >= minY && t.minY <= maxY && t.maxZ >= minZ && t.minZ <= maxZ) {
                        out.add(id)
                    }
                }
                return
            }
            n.left?.let { queryBoxRec(it, minX, minY, minZ, maxX, maxY, maxZ, out) }
            n.right?.let { queryBoxRec(it, minX, minY, minZ, maxX, maxY, maxZ, out) }
        }

        fun queryRay(ox: Double, oy: Double, oz: Double, invDx: Double, invDy: Double, invDz: Double, out: ArrayList<Int>) {
            val r = root ?: return
            queryRayRec(r, ox, oy, oz, invDx, invDy, invDz, out)
        }

        private fun queryRayRec(n: BvhNode, ox: Double, oy: Double, oz: Double, invDx: Double, invDy: Double, invDz: Double, out: ArrayList<Int>) {
            val t1 = (n.minX - ox) * invDx; val t2 = (n.maxX - ox) * invDx
            var tmin = min(t1, t2); var tmax = max(t1, t2)
            val t3 = (n.minY - oy) * invDy; val t4 = (n.maxY - oy) * invDy
            tmin = max(tmin, min(t3, t4)); tmax = min(tmax, max(t3, t4))
            val t5 = (n.minZ - oz) * invDz; val t6 = (n.maxZ - oz) * invDz
            tmin = max(tmin, min(t5, t6)); tmax = min(tmax, max(t5, t6))
            if (tmax < max(0.0, tmin)) return
            val leaf = n.triIndices
            if (leaf != null) {
                for (id in leaf) out.add(id)
                return
            }
            n.left?.let { queryRayRec(it, ox, oy, oz, invDx, invDy, invDz, out) }
            n.right?.let { queryRayRec(it, ox, oy, oz, invDx, invDy, invDz, out) }
        }
    }

    fun apply(
        target: BlendMeshData,
        operands: List<Operand>,
        operation: Int,
        doubleThreshold: Float,
        flag: Int,
        materialMode: Int,
        targetMatCount: Int
    ): BlendMeshData {
        if (operands.isEmpty()) return target
        val holeTolerant = (flag and FLAG_HOLE_TOLERANT) != 0
        val useSelf = (flag and FLAG_SELF) != 0
        var current = target
        for (op in operands) {
            if (op.mesh.nF == 0) {
                if (operation == OP_INTERSECT) return emptyMesh(target)
                continue
            }
            if (current.nF == 0) {
                if (operation == OP_UNION) {
                    current = transformOperandMesh(op.mesh, op.relMatrix, op.matRemap, materialMode, targetMatCount, target.groups)
                }
                continue
            }
            val bMesh = transformOperandMesh(op.mesh, op.relMatrix, op.matRemap, materialMode, targetMatCount, current.groups)
            current = booleanPair(current, bMesh, operation, doubleThreshold.toDouble(), holeTolerant, useSelf)
        }
        return current
    }

    private fun emptyMesh(src: BlendMeshData): BlendMeshData = BlendMeshData(
        co = FloatArray(0),
        fStart = IntArray(0),
        fSize = IntArray(0),
        corners = IntArray(0),
        faceMat = if (src.faceMat != null) IntArray(0) else null,
        faceSmooth = if (src.faceSmooth != null) BooleanArray(0) else null,
        uv = if (src.uv != null) FloatArray(0) else null,
        cornerColor = if (src.cornerColor != null) IntArray(0) else null,
        pointColor = if (src.pointColor != null) IntArray(0) else null,
        edgeCrease = emptyMap(),
        vertCrease = null,
        looseEdges = IntArray(0),
        dverts = if (src.dverts != null) emptyArray() else null,
        groups = src.groups
    ).carryNormals(src)

    private fun transformOperandMesh(
        m: BlendMeshData,
        mtx: FloatArray,
        matRemap: IntArray?,
        materialMode: Int,
        targetMatCount: Int,
        targetGroups: List<String>
    ): BlendMeshData {
        val nV = m.nV
        val co = FloatArray(nV * 3)
        for (i in 0 until nV) {
            val x = m.co[i * 3].toDouble()
            val y = m.co[i * 3 + 1].toDouble()
            val z = m.co[i * 3 + 2].toDouble()
            co[i * 3] = (mtx[0] * x + mtx[4] * y + mtx[8] * z + mtx[12]).toFloat()
            co[i * 3 + 1] = (mtx[1] * x + mtx[5] * y + mtx[9] * z + mtx[13]).toFloat()
            co[i * 3 + 2] = (mtx[2] * x + mtx[6] * y + mtx[10] * z + mtx[14]).toFloat()
        }
        val det = mtx[0].toDouble() * (mtx[5].toDouble() * mtx[10].toDouble() - mtx[6].toDouble() * mtx[9].toDouble()) -
            mtx[4].toDouble() * (mtx[1].toDouble() * mtx[10].toDouble() - mtx[2].toDouble() * mtx[9].toDouble()) +
            mtx[8].toDouble() * (mtx[1].toDouble() * mtx[6].toDouble() - mtx[2].toDouble() * mtx[5].toDouble())
        val flip = det < 0.0
        val corners = m.corners.copyOf()
        val uv = m.uv?.copyOf()
        val cc = m.cornerColor?.copyOf()
        if (flip) {
            for (f in 0 until m.nF) {
                val s = m.fStart[f]; val k = m.fSize[f]
                for (i in 0 until k / 2) {
                    val a = s + i; val b = s + k - 1 - i
                    val tv = corners[a]; corners[a] = corners[b]; corners[b] = tv
                    if (uv != null) {
                        val tu = uv[a * 2]; val tw = uv[a * 2 + 1]
                        uv[a * 2] = uv[b * 2]; uv[a * 2 + 1] = uv[b * 2 + 1]
                        uv[b * 2] = tu; uv[b * 2 + 1] = tw
                    }
                    if (cc != null) {
                        val tc = cc[a]; cc[a] = cc[b]; cc[b] = tc
                    }
                }
            }
        }
        val maxSlot = max(0, targetMatCount - 1)
        val faceMat = IntArray(m.nF) { f ->
            val srcSlot = m.faceMat?.getOrNull(f) ?: 0
            if (materialMode == MAT_MODE_TRANSFER && matRemap != null && srcSlot in matRemap.indices) {
                matRemap[srcSlot].coerceIn(0, maxSlot)
            } else {
                srcSlot.coerceIn(0, maxSlot)
            }
        }
        val groupMap = IntArray(m.groups.size) { targetGroups.indexOf(m.groups[it]) }
        val dverts = m.dverts?.let { dv ->
            Array(nV) { v ->
                dv[v].mapNotNull { (g, w) ->
                    val ng = groupMap.getOrElse(g) { -1 }
                    if (ng >= 0) ng to w else null
                }
            }
        }
        return BlendMeshData(
            co, m.fStart.copyOf(), m.fSize.copyOf(), corners, faceMat, m.faceSmooth?.copyOf(),
            uv, cc, m.pointColor?.copyOf(), emptyMap(), null, IntArray(0), dverts, targetGroups
        )
    }

    private fun booleanPair(
        a: BlendMeshData,
        b: BlendMeshData,
        operation: Int,
        doubleThreshold: Double,
        holeTolerant: Boolean,
        useSelf: Boolean
    ): BlendMeshData {
        var mnX = Double.POSITIVE_INFINITY; var mnY = Double.POSITIVE_INFINITY; var mnZ = Double.POSITIVE_INFINITY
        var mxX = Double.NEGATIVE_INFINITY; var mxY = Double.NEGATIVE_INFINITY; var mxZ = Double.NEGATIVE_INFINITY
        fun expand(co: FloatArray) {
            for (i in 0 until co.size / 3) {
                val x = co[i * 3].toDouble(); val y = co[i * 3 + 1].toDouble(); val z = co[i * 3 + 2].toDouble()
                if (x < mnX) mnX = x; if (y < mnY) mnY = y; if (z < mnZ) mnZ = z
                if (x > mxX) mxX = x; if (y > mxY) mxY = y; if (z > mxZ) mxZ = z
            }
        }
        expand(a.co); expand(b.co)
        val diag = sqrt((mxX - mnX) * (mxX - mnX) + (mxY - mnY) * (mxY - mnY) + (mxZ - mnZ) * (mxZ - mnZ)).coerceAtLeast(1e-4)
        val eps = max(doubleThreshold, diag * 1e-6).coerceIn(1e-7, diag * 1e-3)
        val planeEps = eps * 2.0

        val pool = SpatialVertPool(eps)
        val hasUv = a.uv != null || b.uv != null
        val hasCc = a.cornerColor != null || b.cornerColor != null
        val hasDv = a.dverts != null || b.dverts != null

        val trisA = extractTris(a, 0, pool)
        val trisB = extractTris(b, 1, pool)
        if (trisA.isEmpty() && trisB.isEmpty()) return emptyMesh(a)
        ensureClosedOutward(trisA, pool.verts)
        ensureClosedOutward(trisB, pool.verts)

        val bvhA = TriBvh(trisA)
        val bvhB = TriBvh(trisB)

        val segsA = Array(trisA.size) { ArrayList<Pair<Int, Int>>() }
        val segsB = Array(trisB.size) { ArrayList<Pair<Int, Int>>() }
        val ptsA = Array(trisA.size) { ArrayList<Int>() }
        val ptsB = Array(trisB.size) { ArrayList<Int>() }
        val edgeSplits = HashMap<Long, HashSet<Int>>()

        fun recordPointOnEdges(tri: Tri, vId: Int) {
            val p = pool.verts[vId]
            for (k in 0 until 3) {
                val u = tri.vert(k); val w = tri.vert((k + 1) % 3)
                if (vId == u || vId == w) continue
                val pu = pool.verts[u]; val pw = pool.verts[w]
                val ab = pw - pu
                val lenSq = ab.lenSq()
                if (lenSq < 1e-18) continue
                val t = (p - pu).dot(ab) / lenSq
                if (t > 1e-6 && t < 1.0 - 1e-6) {
                    val proj = pu + ab * t
                    if ((p - proj).lenSq() <= planeEps * planeEps * 4.0) {
                        edgeSplits.getOrPut(BlendMeshData.key(u, w)) { HashSet() }.add(vId)
                    }
                }
            }
        }

        val candidates = ArrayList<Int>(32)
        for (iA in trisA.indices) {
            val tA = trisA[iA]
            candidates.clear()
            bvhB.queryBox(tA.minX - planeEps, tA.minY - planeEps, tA.minZ - planeEps, tA.maxX + planeEps, tA.maxY + planeEps, tA.maxZ + planeEps, candidates)
            for (iB in candidates) {
                val tB = trisB[iB]
                intersectTrianglePair(tA, tB, pool, eps, planeEps, segsA[iA], segsB[iB], ptsA[iA], ptsB[iB], ::recordPointOnEdges)
            }
            if (useSelf) {
                candidates.clear()
                bvhA.queryBox(tA.minX - planeEps, tA.minY - planeEps, tA.minZ - planeEps, tA.maxX + planeEps, tA.maxY + planeEps, tA.maxZ + planeEps, candidates)
                for (jA in candidates) {
                    if (jA <= iA) continue
                    val t2 = trisA[jA]
                    if (sharesAnyVert(tA, t2)) continue
                    intersectTrianglePair(tA, t2, pool, eps, planeEps, segsA[iA], segsA[jA], ptsA[iA], ptsA[jA], ::recordPointOnEdges)
                }
            }
        }

        val splitA = ArrayList<Tri>(trisA.size * 2)
        for (iA in trisA.indices) {
            retriangulateIfNeeded(trisA[iA], segsA[iA], ptsA[iA], edgeSplits, pool, eps, splitA)
        }
        val splitB = ArrayList<Tri>(trisB.size * 2)
        for (iB in trisB.indices) {
            retriangulateIfNeeded(trisB[iB], segsB[iB], ptsB[iB], edgeSplits, pool, eps, splitB)
        }

        val kept = ArrayList<Tri>(splitA.size + splitB.size)

        for (t in splitA) {
            val cls = classifyTri(t, trisB, bvhB, pool.verts, planeEps, holeTolerant)
            val keep = when (operation) {
                OP_UNION -> cls == CLASS_OUTSIDE || cls == CLASS_COPLANAR_SAME
                OP_INTERSECT -> cls == CLASS_INSIDE || cls == CLASS_COPLANAR_SAME
                OP_DIFFERENCE -> cls == CLASS_OUTSIDE || cls == CLASS_COPLANAR_OPP
                else -> false
            }
            if (keep) kept.add(t)
        }

        for (t in splitB) {
            val cls = classifyTri(t, trisA, bvhA, pool.verts, planeEps, holeTolerant)
            val keep = when (operation) {
                OP_UNION -> cls == CLASS_OUTSIDE
                OP_INTERSECT -> cls == CLASS_INSIDE
                OP_DIFFERENCE -> cls == CLASS_INSIDE
                else -> false
            }
            if (keep) {
                if (operation == OP_DIFFERENCE) kept.add(flipTri(t)) else kept.add(t)
            }
        }

        return buildOutputMesh(kept, pool.verts, a, hasUv, hasCc, hasDv)
    }

    private fun sharesAnyVert(a: Tri, b: Tri): Boolean =
        a.v0 == b.v0 || a.v0 == b.v1 || a.v0 == b.v2 ||
            a.v1 == b.v0 || a.v1 == b.v1 || a.v1 == b.v2 ||
            a.v2 == b.v0 || a.v2 == b.v1 || a.v2 == b.v2

    private fun flipTri(t: Tri): Tri = Tri(
        t.v0, t.v2, t.v1,
        t.origMesh, t.origFace, t.mat, t.smooth,
        t.uv0x, t.uv0y, t.uv2x, t.uv2y, t.uv1x, t.uv1y,
        t.cc0, t.cc2, t.cc1,
        t.dv0, t.dv2, t.dv1,
        t.normal * -1.0,
        t.minX, t.minY, t.minZ, t.maxX, t.maxY, t.maxZ
    )

    private fun ensureClosedOutward(tris: ArrayList<Tri>, verts: List<Vec3>) {
        if (tris.size < 4) return
        val edgeToTris = HashMap<Long, ArrayList<Int>>(tris.size * 2)
        val dirEdges = HashSet<Long>(tris.size * 3)
        for (i in tris.indices) {
            val t = tris[i]
            val vs = intArrayOf(t.v0, t.v1, t.v2)
            for (k in 0 until 3) {
                val u = vs[k]; val w = vs[(k + 1) % 3]
                edgeToTris.getOrPut(BlendMeshData.key(u, w)) { ArrayList(2) }.add(i)
                dirEdges.add((u.toLong() shl 32) or (w.toLong() and 0xFFFFFFFFL))
            }
        }
        val visited = BooleanArray(tris.size)
        val compTris = ArrayList<ArrayList<Int>>()
        val compClosed = ArrayList<Boolean>()
        val compVol6 = ArrayList<Double>()
        val compMin = ArrayList<Vec3>()
        val compMax = ArrayList<Vec3>()
        val queue = ArrayDeque<Int>()
        for (i in tris.indices) {
            if (visited[i]) continue
            val comp = ArrayList<Int>()
            visited[i] = true
            queue.add(i)
            var closed = true
            var vol6 = 0.0
            var mnX = Double.POSITIVE_INFINITY; var mnY = Double.POSITIVE_INFINITY; var mnZ = Double.POSITIVE_INFINITY
            var mxX = Double.NEGATIVE_INFINITY; var mxY = Double.NEGATIVE_INFINITY; var mxZ = Double.NEGATIVE_INFINITY
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                comp.add(cur)
                val t = tris[cur]
                val p0 = verts[t.v0]; val p1 = verts[t.v1]; val p2 = verts[t.v2]
                vol6 += p0.dot(p1.cross(p2))
                if (t.minX < mnX) mnX = t.minX; if (t.minY < mnY) mnY = t.minY; if (t.minZ < mnZ) mnZ = t.minZ
                if (t.maxX > mxX) mxX = t.maxX; if (t.maxY > mxY) mxY = t.maxY; if (t.maxZ > mxZ) mxZ = t.maxZ
                val vs = intArrayOf(t.v0, t.v1, t.v2)
                for (k in 0 until 3) {
                    val u = vs[k]; val w = vs[(k + 1) % 3]
                    val adj = edgeToTris[BlendMeshData.key(u, w)]
                    if (adj == null || adj.size != 2 || !dirEdges.contains((w.toLong() shl 32) or (u.toLong() and 0xFFFFFFFFL))) {
                        closed = false
                    }
                    if (adj != null) {
                        for (nb in adj) {
                            if (!visited[nb]) {
                                visited[nb] = true
                                queue.add(nb)
                            }
                        }
                    }
                }
            }
            compTris.add(comp)
            compClosed.add(closed && comp.size >= 4)
            compVol6.add(vol6)
            compMin.add(Vec3(mnX, mnY, mnZ))
            compMax.add(Vec3(mxX, mxY, mxZ))
        }
        var allClosed = true
        var totalVol6 = 0.0
        for (c in compTris.indices) {
            if (!compClosed[c]) {
                allClosed = false
                break
            }
            totalVol6 += compVol6[c]
        }
        if (allClosed && totalVol6 < -1e-9) {
            var anyLargerPos = false
            for (c in compTris.indices) {
                if (compVol6[c] > -totalVol6) {
                    anyLargerPos = true
                    break
                }
            }
            if (!anyLargerPos) {
                for (i in tris.indices) tris[i] = flipTri(tris[i])
                return
            }
        }
        for (c in compTris.indices) {
            if (!compClosed[c] || compVol6[c] >= -1e-9) continue
            val mn = compMin[c]; val mx = compMax[c]
            val absVol = abs(compVol6[c])
            var insideLargerPos = false
            for (o in compTris.indices) {
                if (o == c || !compClosed[o] || compVol6[o] <= absVol) continue
                val omn = compMin[o]; val omx = compMax[o]
                if (mn.x >= omn.x - 1e-6 && mn.y >= omn.y - 1e-6 && mn.z >= omn.z - 1e-6 &&
                    mx.x <= omx.x + 1e-6 && mx.y <= omx.y + 1e-6 && mx.z <= omx.z + 1e-6
                ) {
                    insideLargerPos = true
                    break
                }
            }
            if (!insideLargerPos) {
                for (ti in compTris[c]) tris[ti] = flipTri(tris[ti])
            }
        }
    }

    private fun extractTris(m: BlendMeshData, meshId: Int, pool: SpatialVertPool): ArrayList<Tri> {
        val vMap = IntArray(m.nV) { v ->
            pool.intern(Vec3(m.co[v * 3].toDouble(), m.co[v * 3 + 1].toDouble(), m.co[v * 3 + 2].toDouble()))
        }
        val out = ArrayList<Tri>(m.nF * 2)
        for (f in 0 until m.nF) {
            val s = m.fStart[f]; val len = m.fSize[f]
            if (len < 3 || s < 0 || s + len > m.corners.size) continue
            val mat = m.faceMat?.getOrNull(f) ?: 0
            val smooth = m.faceSmooth?.getOrNull(f) ?: false
            val triLocal: IntArray = if (len == 3) {
                intArrayOf(0, 1, 2)
            } else if (len == 4) {
                if (MeshTopology.quadFlip(m.co, m.corners[s], m.corners[s + 1], m.corners[s + 2], m.corners[s + 3])) {
                    intArrayOf(0, 1, 3, 1, 2, 3)
                } else {
                    intArrayOf(0, 1, 2, 0, 2, 3)
                }
            } else {
                val vs = IntArray(len) { m.corners[s + it] }
                BlendPolyfill.triangulate(m.co, vs)
            }
            for (k in triLocal.indices step 3) {
                val c0 = s + triLocal[k]; val c1 = s + triLocal[k + 1]; val c2 = s + triLocal[k + 2]
                val ov0 = m.corners[c0]; val ov1 = m.corners[c1]; val ov2 = m.corners[c2]
                if (ov0 !in 0 until m.nV || ov1 !in 0 until m.nV || ov2 !in 0 until m.nV) continue
                val i0 = vMap[ov0]; val i1 = vMap[ov1]; val i2 = vMap[ov2]
                if (i0 == i1 || i1 == i2 || i2 == i0) continue
                val p0 = pool.verts[i0]; val p1 = pool.verts[i1]; val p2 = pool.verts[i2]
                val cross = (p1 - p0).cross(p2 - p0)
                if (cross.lenSq() < 1e-22) continue
                val n = cross.norm()
                val uv = m.uv
                val cc = m.cornerColor
                val dv = m.dverts
                out.add(
                    Tri(
                        i0, i1, i2, meshId, f, mat, smooth,
                        uv?.getOrNull(c0 * 2) ?: 0f, uv?.getOrNull(c0 * 2 + 1) ?: 0f,
                        uv?.getOrNull(c1 * 2) ?: 0f, uv?.getOrNull(c1 * 2 + 1) ?: 0f,
                        uv?.getOrNull(c2 * 2) ?: 0f, uv?.getOrNull(c2 * 2 + 1) ?: 0f,
                        cc?.getOrNull(c0) ?: 0, cc?.getOrNull(c1) ?: 0, cc?.getOrNull(c2) ?: 0,
                        dv?.getOrNull(ov0), dv?.getOrNull(ov1), dv?.getOrNull(ov2),
                        n,
                        min(p0.x, min(p1.x, p2.x)), min(p0.y, min(p1.y, p2.y)), min(p0.z, min(p1.z, p2.z)),
                        max(p0.x, max(p1.x, p2.x)), max(p0.y, max(p1.y, p2.y)), max(p0.z, max(p1.z, p2.z))
                    )
                )
            }
        }
        return out
    }

    private fun intersectTrianglePair(
        tA: Tri,
        tB: Tri,
        pool: SpatialVertPool,
        eps: Double,
        planeEps: Double,
        outSegA: ArrayList<Pair<Int, Int>>,
        outSegB: ArrayList<Pair<Int, Int>>,
        outPtA: ArrayList<Int>,
        outPtB: ArrayList<Int>,
        recordEdge: (Tri, Int) -> Unit
    ) {
        val a0 = pool.verts[tA.v0]; val a1 = pool.verts[tA.v1]; val a2 = pool.verts[tA.v2]
        val b0 = pool.verts[tB.v0]; val b1 = pool.verts[tB.v1]; val b2 = pool.verts[tB.v2]

        fun snap(d: Double) = if (abs(d) <= planeEps) 0.0 else d
        val da0 = snap((a0 - b0).dot(tB.normal))
        val da1 = snap((a1 - b0).dot(tB.normal))
        val da2 = snap((a2 - b0).dot(tB.normal))
        if ((da0 > 0.0 && da1 > 0.0 && da2 > 0.0) || (da0 < 0.0 && da1 < 0.0 && da2 < 0.0)) return

        val db0 = snap((b0 - a0).dot(tA.normal))
        val db1 = snap((b1 - a0).dot(tA.normal))
        val db2 = snap((b2 - a0).dot(tA.normal))
        if ((db0 > 0.0 && db1 > 0.0 && db2 > 0.0) || (db0 < 0.0 && db1 < 0.0 && db2 < 0.0)) return

        val lineDirRaw = tA.normal.cross(tB.normal)
        val lineDirLenSq = lineDirRaw.lenSq()

        if ((da0 == 0.0 && da1 == 0.0 && da2 == 0.0) || (db0 == 0.0 && db1 == 0.0 && db2 == 0.0) || lineDirLenSq < 1e-12) {
            intersectCoplanarPair(tA, tB, a0, a1, a2, b0, b1, b2, pool, eps, outSegA, outSegB, recordEdge)
            return
        }

        val lineDir = lineDirRaw * (1.0 / sqrt(lineDirLenSq))
        val origin = a0

        val ptsOnA = collectPlaneCrossings(a0, a1, a2, da0, da1, da2)
        val ptsOnB = collectPlaneCrossings(b0, b1, b2, db0, db1, db2)
        if (ptsOnA.isEmpty() || ptsOnB.isEmpty()) return

        var minTA = Double.POSITIVE_INFINITY; var maxTA = Double.NEGATIVE_INFINITY
        var pAMin = ptsOnA[0]; var pAMax = ptsOnA[0]
        for (p in ptsOnA) {
            val t = (p - origin).dot(lineDir)
            if (t < minTA) { minTA = t; pAMin = p }
            if (t > maxTA) { maxTA = t; pAMax = p }
        }
        var minTB = Double.POSITIVE_INFINITY; var maxTB = Double.NEGATIVE_INFINITY
        var pBMin = ptsOnB[0]; var pBMax = ptsOnB[0]
        for (p in ptsOnB) {
            val t = (p - origin).dot(lineDir)
            if (t < minTB) { minTB = t; pBMin = p }
            if (t > maxTB) { maxTB = t; pBMax = p }
        }

        if (maxTA < minTB - eps || maxTB < minTA - eps) return

        val pStart = if (minTA >= minTB) pAMin else pBMin
        val pEnd = if (maxTA <= maxTB) pAMax else pBMax
        val id0 = pool.intern(pStart)
        val id1 = pool.intern(pEnd)
        recordEdge(tA, id0); recordEdge(tB, id0)
        if (id0 == id1) {
            outPtA.add(id0); outPtB.add(id0)
        } else {
            recordEdge(tA, id1); recordEdge(tB, id1)
            outSegA.add(id0 to id1)
            outSegB.add(id0 to id1)
        }
    }

    private fun collectPlaneCrossings(p0: Vec3, p1: Vec3, p2: Vec3, d0: Double, d1: Double, d2: Double): ArrayList<Vec3> {
        val out = ArrayList<Vec3>(4)
        val ps = arrayOf(p0, p1, p2)
        val ds = doubleArrayOf(d0, d1, d2)
        for (k in 0 until 3) {
            val kn = (k + 1) % 3
            val dk = ds[k]; val dkn = ds[kn]
            if (dk == 0.0) {
                out.add(ps[k])
            } else if ((dk > 0.0 && dkn < 0.0) || (dk < 0.0 && dkn > 0.0)) {
                val t = dk / (dk - dkn)
                out.add(ps[k] + (ps[kn] - ps[k]) * t)
            }
        }
        return out
    }

    private fun intersectCoplanarPair(
        tA: Tri,
        tB: Tri,
        a0: Vec3, a1: Vec3, a2: Vec3,
        b0: Vec3, b1: Vec3, b2: Vec3,
        pool: SpatialVertPool,
        eps: Double,
        outSegA: ArrayList<Pair<Int, Int>>,
        outSegB: ArrayList<Pair<Int, Int>>,
        recordEdge: (Tri, Int) -> Unit
    ) {
        val n = tA.normal
        val dropAxis = dominantAxis(n)
        fun proj(p: Vec3) = project2D(p, dropAxis)
        var pa = arrayOf(proj(a0), proj(a1), proj(a2))
        var pb = arrayOf(proj(b0), proj(b1), proj(b2))
        val oriA = orient2d(pa[0], pa[1], pa[2])
        val oriB = orient2d(pb[0], pb[1], pb[2])
        if (abs(oriA) < 1e-18 || abs(oriB) < 1e-18) return
        val aVerts = arrayOf(a0, a1, a2)
        val bVerts = arrayOf(b0, b1, b2)

        fun clipEdges(src3: Array<Vec3>, src2: Array<Vec2>, clip2: Array<Vec2>, clipSign: Double, dstSeg: ArrayList<Pair<Int, Int>>, dstTri: Tri, otherTri: Tri) {
            for (e in 0 until 3) {
                val en = (e + 1) % 3
                val s0 = src2[e]; val s1 = src2[en]
                val ds = s1 - s0
                var tMin = 0.0; var tMax = 1.0
                var valid = true
                for (c in 0 until 3) {
                    val cn = (c + 1) % 3
                    val c0 = clip2[c]; val c1 = clip2[cn]
                    val edge = c1 - c0
                    val f0 = edge.cross(s0 - c0) * clipSign
                    val f1 = edge.cross(s1 - c0) * clipSign
                    if (f0 < -eps && f1 < -eps) { valid = false; break }
                    if (f0 < 0.0 && f1 > 0.0) {
                        val t = f0 / (f0 - f1)
                        if (t > tMin) tMin = t
                    } else if (f0 > 0.0 && f1 < 0.0) {
                        val t = f0 / (f0 - f1)
                        if (t < tMax) tMax = t
                    }
                    if (tMin > tMax + 1e-9) { valid = false; break }
                }
                if (!valid || tMax - tMin <= 1e-7) continue
                val pStart = src3[e] + (src3[en] - src3[e]) * tMin.coerceIn(0.0, 1.0)
                val pEnd = src3[e] + (src3[en] - src3[e]) * tMax.coerceIn(0.0, 1.0)
                val id0 = pool.intern(pStart)
                val id1 = pool.intern(pEnd)
                if (id0 != id1) {
                    recordEdge(dstTri, id0); recordEdge(dstTri, id1)
                    recordEdge(otherTri, id0); recordEdge(otherTri, id1)
                    dstSeg.add(id0 to id1)
                }
            }
        }

        val signA = if (oriA > 0.0) 1.0 else -1.0
        val signB = if (oriB > 0.0) 1.0 else -1.0
        clipEdges(bVerts, pb, pa, signA, outSegA, tA, tB)
        clipEdges(aVerts, pa, pb, signB, outSegB, tB, tA)
    }

    private fun dominantAxis(n: Vec3): Int {
        val ax = abs(n.x); val ay = abs(n.y); val az = abs(n.z)
        return if (ax >= ay && ax >= az) 0 else if (ay >= az) 1 else 2
    }

    private fun project2D(p: Vec3, dropAxis: Int): Vec2 = when (dropAxis) {
        0 -> Vec2(p.y, p.z)
        1 -> Vec2(p.z, p.x)
        else -> Vec2(p.x, p.y)
    }

    private fun orient2d(a: Vec2, b: Vec2, c: Vec2): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun retriangulateIfNeeded(
        tri: Tri,
        rawSegs: List<Pair<Int, Int>>,
        rawPts: List<Int>,
        edgeSplits: Map<Long, HashSet<Int>>,
        pool: SpatialVertPool,
        eps: Double,
        out: ArrayList<Tri>
    ) {
        val e01 = edgeSplits[BlendMeshData.key(tri.v0, tri.v1)]
        val e12 = edgeSplits[BlendMeshData.key(tri.v1, tri.v2)]
        val e20 = edgeSplits[BlendMeshData.key(tri.v2, tri.v0)]
        val hasSplits = (e01 != null && e01.isNotEmpty()) || (e12 != null && e12.isNotEmpty()) || (e20 != null && e20.isNotEmpty())
        if (rawSegs.isEmpty() && rawPts.isEmpty() && !hasSplits) {
            out.add(tri)
            return
        }

        val dropAxis = dominantAxis(tri.normal)
        val p0 = pool.verts[tri.v0]; val p1 = pool.verts[tri.v1]; val p2 = pool.verts[tri.v2]
        val q0 = project2D(p0, dropAxis); val q1 = project2D(p1, dropAxis); val q2 = project2D(p2, dropAxis)
        val triArea2 = orient2d(q0, q1, q2)
        if (abs(triArea2) < 1e-18) {
            out.add(tri)
            return
        }
        val flip2d = triArea2 < 0.0
        fun to2d(vId: Int): Vec2 {
            val v = project2D(pool.verts[vId], dropAxis)
            return if (flip2d) Vec2(v.x, -v.y) else v
        }

        val localVerts = ArrayList<Int>()
        val local2d = ArrayList<Vec2>()
        val globalToLocal = HashMap<Int, Int>()
        fun addLocal(vId: Int): Int {
            globalToLocal[vId]?.let { return it }
            val li = localVerts.size
            localVerts.add(vId)
            local2d.add(to2d(vId))
            globalToLocal[vId] = li
            return li
        }
        addLocal(tri.v0); addLocal(tri.v1); addLocal(tri.v2)

        val segList = ArrayList<Pair<Int, Int>>()
        segList.add(0 to 1); segList.add(1 to 2); segList.add(2 to 0)
        for ((u, v) in rawSegs) {
            if (u == v) continue
            val lu = addLocal(u); val lv = addLocal(v)
            if (lu != lv) segList.add(lu to lv)
        }
        for (vId in rawPts) addLocal(vId)
        e01?.forEach { addLocal(it) }
        e12?.forEach { addLocal(it) }
        e20?.forEach { addLocal(it) }

        val eps2d = eps * 2.0
        val eps2dSq = eps2d * eps2d

        val crossPoints = ArrayList<Vec3>()
        for (i in segList.indices) {
            val (a, b) = segList[i]
            val pa = local2d[a]; val pb = local2d[b]
            for (j in i + 1 until segList.size) {
                val (c, d) = segList[j]
                if (a == c || a == d || b == c || b == d) continue
                val pc = local2d[c]; val pd = local2d[d]
                val r = pb - pa; val s = pd - pc
                val denom = r.cross(s)
                if (abs(denom) < 1e-14) continue
                val t = (pc - pa).cross(s) / denom
                val u = (pc - pa).cross(r) / denom
                if (t > 1e-6 && t < 1.0 - 1e-6 && u > 1e-6 && u < 1.0 - 1e-6) {
                    val p3 = pool.verts[localVerts[a]] + (pool.verts[localVerts[b]] - pool.verts[localVerts[a]]) * t
                    crossPoints.add(p3)
                }
            }
        }
        for (cp in crossPoints) addLocal(pool.intern(cp))

        val elementarySegs = LinkedHashSet<Long>()
        for ((a, b) in segList) {
            if (a == b) continue
            val pa = local2d[a]; val pb = local2d[b]
            val ab = pb - pa
            val lenSq = ab.lenSq()
            if (lenSq < eps2dSq * 0.01) continue
            val onSeg = ArrayList<Pair<Double, Int>>()
            onSeg.add(0.0 to a)
            onSeg.add(1.0 to b)
            for (k in localVerts.indices) {
                if (k == a || k == b) continue
                val pk = local2d[k]
                val t = (pk - pa).dot(ab) / lenSq
                if (t > 1e-6 && t < 1.0 - 1e-6) {
                    val proj = pa + ab * t
                    if ((pk - proj).lenSq() <= eps2dSq * 4.0) {
                        onSeg.add(t to k)
                    }
                }
            }
            onSeg.sortBy { it.first }
            for (m in 0 until onSeg.size - 1) {
                val u = onSeg[m].second; val v = onSeg[m + 1].second
                if (u != v) elementarySegs.add(BlendMeshData.key(u, v))
            }
        }

        val subTris = ArrayList<IntArray>()
        subTris.add(intArrayOf(0, 1, 2))

        fun pointInOrOnTri(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Int {
            val o0 = orient2d(a, b, p)
            val o1 = orient2d(b, c, p)
            val o2 = orient2d(c, a, p)
            val area = orient2d(a, b, c)
            val tol = max(1e-12, abs(area) * 1e-6)
            if (o0 < -tol || o1 < -tol || o2 < -tol) return -1
            if (abs(o0) <= tol) return 0
            if (abs(o1) <= tol) return 1
            if (abs(o2) <= tol) return 2
            return 3
        }

        for (vIdx in 3 until localVerts.size) {
            val pv = local2d[vIdx]
            var foundIdx = -1
            var foundCode = -1
            for (ti in subTris.indices) {
                val st = subTris[ti]
                val code = pointInOrOnTri(pv, local2d[st[0]], local2d[st[1]], local2d[st[2]])
                if (code >= 0) {
                    foundIdx = ti
                    foundCode = code
                    break
                }
            }
            if (foundIdx < 0) continue
            val st = subTris[foundIdx]
            if (foundCode == 3) {
                subTris[foundIdx] = intArrayOf(st[0], st[1], vIdx)
                subTris.add(intArrayOf(st[1], st[2], vIdx))
                subTris.add(intArrayOf(st[2], st[0], vIdx))
            } else {
                val ea = st[foundCode]
                val eb = st[(foundCode + 1) % 3]
                val ec = st[(foundCode + 2) % 3]
                if ((pv - local2d[ea]).lenSq() <= eps2dSq || (pv - local2d[eb]).lenSq() <= eps2dSq) continue
                subTris[foundIdx] = intArrayOf(ea, vIdx, ec)
                subTris.add(intArrayOf(vIdx, eb, ec))
                for (oi in subTris.indices) {
                    if (oi == foundIdx) continue
                    val ot = subTris[oi]
                    for (ek in 0 until 3) {
                        if (ot[ek] == eb && ot[(ek + 1) % 3] == ea) {
                            val od = ot[(ek + 2) % 3]
                            subTris[oi] = intArrayOf(eb, vIdx, od)
                            subTris.add(intArrayOf(vIdx, ea, od))
                            break
                        }
                    }
                }
            }
        }

        for (segKey in elementarySegs) {
            val u = (segKey ushr 32).toInt()
            val v = (segKey and 0xFFFFFFFFL).toInt()
            if (hasEdge(subTris, u, v)) continue
            recoverConstraintEdge(subTris, local2d, u, v)
        }

        val a3 = pool.verts[tri.v0]; val b3 = pool.verts[tri.v1]; val c3 = pool.verts[tri.v2]
        val v01 = b3 - a3; val v02 = c3 - a3
        val d00 = v01.dot(v01); val d01 = v01.dot(v02); val d11 = v02.dot(v02)
        val invDen = (d00 * d11 - d01 * d01).let { if (abs(it) > 1e-24) 1.0 / it else 0.0 }

        fun bary(p: Vec3): DoubleArray {
            val vp = p - a3
            val d20 = vp.dot(v01); val d21 = vp.dot(v02)
            val w1 = (d11 * d20 - d01 * d21) * invDen
            val w2 = (d00 * d21 - d01 * d20) * invDen
            val w0 = 1.0 - w1 - w2
            return doubleArrayOf(w0, w1, w2)
        }

        fun interpColor(w: DoubleArray): Int {
            fun ch(shift: Int): Int {
                val v = ((tri.cc0 ushr shift) and 0xFF) * w[0] +
                    ((tri.cc1 ushr shift) and 0xFF) * w[1] +
                    ((tri.cc2 ushr shift) and 0xFF) * w[2]
                return (v + 0.5).toInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun interpDvert(w: DoubleArray): List<Pair<Int, Float>>? {
            if (tri.dv0 == null && tri.dv1 == null && tri.dv2 == null) return null
            val acc = HashMap<Int, Float>()
            tri.dv0?.forEach { (g, x) -> acc[g] = (acc[g] ?: 0f) + (x * w[0]).toFloat() }
            tri.dv1?.forEach { (g, x) -> acc[g] = (acc[g] ?: 0f) + (x * w[1]).toFloat() }
            tri.dv2?.forEach { (g, x) -> acc[g] = (acc[g] ?: 0f) + (x * w[2]).toFloat() }
            return acc.entries.filter { it.value > 1e-6f }.map { it.key to it.value }
        }

        for (st in subTris) {
            val g0 = localVerts[st[0]]; val g1 = localVerts[st[1]]; val g2 = localVerts[st[2]]
            if (g0 == g1 || g1 == g2 || g2 == g0) continue
            val sp0 = pool.verts[g0]; val sp1 = pool.verts[g1]; val sp2 = pool.verts[g2]
            val cross = (sp1 - sp0).cross(sp2 - sp0)
            if (cross.lenSq() < 1e-22) continue
            val w0 = bary(sp0); val w1 = bary(sp1); val w2 = bary(sp2)
            out.add(
                Tri(
                    g0, g1, g2,
                    tri.origMesh, tri.origFace, tri.mat, tri.smooth,
                    (tri.uv0x * w0[0] + tri.uv1x * w0[1] + tri.uv2x * w0[2]).toFloat(),
                    (tri.uv0y * w0[0] + tri.uv1y * w0[1] + tri.uv2y * w0[2]).toFloat(),
                    (tri.uv0x * w1[0] + tri.uv1x * w1[1] + tri.uv2x * w1[2]).toFloat(),
                    (tri.uv0y * w1[0] + tri.uv1y * w1[1] + tri.uv2y * w1[2]).toFloat(),
                    (tri.uv0x * w2[0] + tri.uv1x * w2[1] + tri.uv2x * w2[2]).toFloat(),
                    (tri.uv0y * w2[0] + tri.uv1y * w2[1] + tri.uv2y * w2[2]).toFloat(),
                    interpColor(w0), interpColor(w1), interpColor(w2),
                    interpDvert(w0), interpDvert(w1), interpDvert(w2),
                    tri.normal,
                    min(sp0.x, min(sp1.x, sp2.x)), min(sp0.y, min(sp1.y, sp2.y)), min(sp0.z, min(sp1.z, sp2.z)),
                    max(sp0.x, max(sp1.x, sp2.x)), max(sp0.y, max(sp1.y, sp2.y)), max(sp0.z, max(sp1.z, sp2.z))
                )
            )
        }
    }

    private fun hasEdge(tris: List<IntArray>, u: Int, v: Int): Boolean {
        for (t in tris) {
            for (k in 0 until 3) {
                val a = t[k]; val b = t[(k + 1) % 3]
                if ((a == u && b == v) || (a == v && b == u)) return true
            }
        }
        return false
    }

    private fun segmentsCrossStrictly(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Boolean {
        val o1 = orient2d(a, b, c)
        val o2 = orient2d(a, b, d)
        val o3 = orient2d(c, d, a)
        val o4 = orient2d(c, d, b)
        return ((o1 > 1e-12 && o2 < -1e-12) || (o1 < -1e-12 && o2 > 1e-12)) &&
            ((o3 > 1e-12 && o4 < -1e-12) || (o3 < -1e-12 && o4 > 1e-12))
    }

    private fun recoverConstraintEdge(tris: ArrayList<IntArray>, pts: List<Vec2>, u: Int, v: Int) {
        val pu = pts[u]; val pv = pts[v]
        val crossed = ArrayList<Int>()
        for (ti in tris.indices) {
            val t = tris[ti]
            var hits = false
            for (k in 0 until 3) {
                val a = t[k]; val b = t[(k + 1) % 3]
                if (a == u || a == v || b == u || b == v) continue
                if (segmentsCrossStrictly(pu, pv, pts[a], pts[b])) {
                    hits = true; break
                }
            }
            if (hits) crossed.add(ti)
        }
        if (crossed.isEmpty()) return

        val dirEdges = HashSet<Long>()
        for (ti in crossed) {
            val t = tris[ti]
            for (k in 0 until 3) {
                val a = t[k]; val b = t[(k + 1) % 3]
                val rev = (b.toLong() shl 32) or (a.toLong() and 0xFFFFFFFFL)
                val fwd = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
                if (!dirEdges.remove(rev)) dirEdges.add(fwd)
            }
        }
        val nextMap = HashMap<Int, Int>()
        for (de in dirEdges) {
            val a = (de ushr 32).toInt()
            val b = (de and 0xFFFFFFFFL).toInt()
            if (nextMap.put(a, b) != null) return
        }
        if (!nextMap.containsKey(u) || !nextMap.containsKey(v)) return

        val chainRight = ArrayList<Int>()
        var cur = u
        var guard = 0
        while (guard++ <= nextMap.size) {
            chainRight.add(cur)
            if (cur == v) break
            cur = nextMap[cur] ?: return
        }
        if (cur != v) return

        val chainLeft = ArrayList<Int>()
        cur = v
        guard = 0
        while (guard++ <= nextMap.size) {
            chainLeft.add(cur)
            if (cur == u) break
            cur = nextMap[cur] ?: return
        }
        if (cur != u || chainRight.size + chainLeft.size - 2 != nextMap.size) return

        val trisRight = earClip2D(chainRight, pts) ?: return
        val trisLeft = earClip2D(chainLeft, pts) ?: return

        val crossedSet = crossed.toHashSet()
        val kept = ArrayList<IntArray>(tris.size - crossed.size + trisRight.size + trisLeft.size)
        for (ti in tris.indices) if (ti !in crossedSet) kept.add(tris[ti])
        kept.addAll(trisRight)
        kept.addAll(trisLeft)
        tris.clear()
        tris.addAll(kept)
    }

    private fun earClip2D(poly: List<Int>, pts: List<Vec2>): List<IntArray>? {
        if (poly.size < 3) return emptyList()
        if (poly.size == 3) return listOf(intArrayOf(poly[0], poly[1], poly[2]))
        val rem = ArrayList<Int>(poly)
        val out = ArrayList<IntArray>(poly.size - 2)
        var guard = rem.size * rem.size + 8
        while (rem.size > 3 && guard-- > 0) {
            val n = rem.size
            var earFound = false
            for (i in 0 until n) {
                val prev = rem[(i + n - 1) % n]
                val curr = rem[i]
                val next = rem[(i + 1) % n]
                val a = pts[prev]; val b = pts[curr]; val c = pts[next]
                if (orient2d(a, b, c) <= 1e-14) continue
                var containsAny = false
                for (j in 0 until n) {
                    val v = rem[j]
                    if (v == prev || v == curr || v == next) continue
                    val p = pts[v]
                    if (orient2d(a, b, p) >= -1e-12 && orient2d(b, c, p) >= -1e-12 && orient2d(c, a, p) >= -1e-12) {
                        containsAny = true; break
                    }
                }
                if (!containsAny) {
                    out.add(intArrayOf(prev, curr, next))
                    rem.removeAt(i)
                    earFound = true
                    break
                }
            }
            if (!earFound) {
                var bestIdx = 0; var bestOri = Double.NEGATIVE_INFINITY
                for (i in 0 until n) {
                    val o = orient2d(pts[rem[(i + n - 1) % n]], pts[rem[i]], pts[rem[(i + 1) % n]])
                    if (o > bestOri) { bestOri = o; bestIdx = i }
                }
                val prev = rem[(bestIdx + n - 1) % n]
                val curr = rem[bestIdx]
                val next = rem[(bestIdx + 1) % n]
                out.add(intArrayOf(prev, curr, next))
                rem.removeAt(bestIdx)
            }
        }
        if (rem.size == 3) out.add(intArrayOf(rem[0], rem[1], rem[2]))
        return out
    }

    private val RAY_DIRS = arrayOf(
        Vec3(0.73124129, 0.51923117, 0.44236291).norm(),
        Vec3(-0.41289137, 0.81327419, 0.41010573).norm(),
        Vec3(0.29183411, -0.37124983, 0.88148247).norm()
    )

    private fun classifyTri(
        t: Tri,
        otherTris: List<Tri>,
        otherBvh: TriBvh,
        verts: List<Vec3>,
        planeEps: Double,
        holeTolerant: Boolean
    ): Int {
        val p0 = verts[t.v0]; val p1 = verts[t.v1]; val p2 = verts[t.v2]
        val c = Vec3((p0.x + p1.x + p2.x) / 3.0, (p0.y + p1.y + p2.y) / 3.0, (p0.z + p1.z + p2.z) / 3.0)

        val nearby = ArrayList<Int>(16)
        otherBvh.queryBox(c.x - planeEps * 2.0, c.y - planeEps * 2.0, c.z - planeEps * 2.0, c.x + planeEps * 2.0, c.y + planeEps * 2.0, c.z + planeEps * 2.0, nearby)
        for (idx in nearby) {
            val ot = otherTris[idx]
            val o0 = verts[ot.v0]; val o1 = verts[ot.v1]; val o2 = verts[ot.v2]
            val dist = abs((c - o0).dot(ot.normal))
            if (dist <= planeEps * 2.0 && pointInTriangle3D(c, o0, o1, o2, ot.normal, planeEps * 2.0)) {
                val ndot = t.normal.dot(ot.normal)
                if (abs(ndot) > 0.999) {
                    return if (ndot > 0.0) CLASS_COPLANAR_SAME else CLASS_COPLANAR_OPP
                }
            }
        }

        if (!holeTolerant) {
            var insideVotes = 0
            var validRays = 0
            val rayCands = ArrayList<Int>(32)
            for (dir in RAY_DIRS) {
                rayCands.clear()
                otherBvh.queryRay(c.x, c.y, c.z, 1.0 / dir.x, 1.0 / dir.y, 1.0 / dir.z, rayCands)
                var hits = 0
                var clean = true
                for (idx in rayCands) {
                    val ot = otherTris[idx]
                    val res = rayTriangleHit(c, dir, verts[ot.v0], verts[ot.v1], verts[ot.v2])
                    if (res == -1) { clean = false; break }
                    if (res == 1) hits++
                }
                if (clean) {
                    validRays++
                    if ((hits and 1) == 1) insideVotes++
                }
            }
            if (validRays >= 2) {
                return if (insideVotes * 2 > validRays) CLASS_INSIDE else CLASS_OUTSIDE
            }
        }

        val wn = windingNumber(c, otherTris, verts)
        return if (wn > 0.5) CLASS_INSIDE else CLASS_OUTSIDE
    }

    private fun pointInTriangle3D(p: Vec3, a: Vec3, b: Vec3, c: Vec3, normal: Vec3, tol: Double): Boolean {
        val e0 = b - a; val e1 = c - b; val e2 = a - c
        val c0 = e0.cross(p - a).dot(normal)
        val c1 = e1.cross(p - b).dot(normal)
        val c2 = e2.cross(p - c).dot(normal)
        val area2 = e0.cross(c - a).dot(normal)
        val t = max(tol * sqrt(abs(area2)), 1e-12)
        return if (area2 >= 0.0) (c0 >= -t && c1 >= -t && c2 >= -t) else (c0 <= t && c1 <= t && c2 <= t)
    }

    private fun rayTriangleHit(orig: Vec3, dir: Vec3, a: Vec3, b: Vec3, c: Vec3): Int {
        val e1 = b - a
        val e2 = c - a
        val h = dir.cross(e2)
        val det = e1.dot(h)
        if (abs(det) < 1e-13) return 0
        val invDet = 1.0 / det
        val s = orig - a
        val u = s.dot(h) * invDet
        if (u < -1e-7 || u > 1.0 + 1e-7) return 0
        val q = s.cross(e1)
        val v = dir.dot(q) * invDet
        if (v < -1e-7 || u + v > 1.0 + 1e-7) return 0
        val t = e2.dot(q) * invDet
        if (t <= 1e-9) return 0
        if (u < 1e-6 || v < 1e-6 || u + v > 1.0 - 1e-6) return -1
        return 1
    }

    private fun windingNumber(p: Vec3, tris: List<Tri>, verts: List<Vec3>): Double {
        var total = 0.0
        for (t in tris) {
            val a = verts[t.v0] - p
            val b = verts[t.v1] - p
            val c = verts[t.v2] - p
            val la = a.len(); val lb = b.len(); val lc = c.len()
            if (la < 1e-12 || lb < 1e-12 || lc < 1e-12) continue
            val num = a.dot(b.cross(c))
            val denom = la * lb * lc + a.dot(b) * lc + b.dot(c) * la + c.dot(a) * lb
            total += 2.0 * atan2(num, denom)
        }
        return total / (4.0 * PI)
    }

    private class OutPoly(
        val verts: IntArray,
        val mat: Int,
        val smooth: Boolean,
        val uvs: FloatArray?,
        val colors: IntArray?,
        val dverts: Array<List<Pair<Int, Float>>?>?
    )

    private fun buildOutputMesh(
        keptTris: List<Tri>,
        verts: List<Vec3>,
        ref: BlendMeshData,
        hasUv: Boolean,
        hasCc: Boolean,
        hasDv: Boolean
    ): BlendMeshData {
        if (keptTris.isEmpty()) return emptyMesh(ref)

        val polys = mergeSameOriginTris(keptTris, verts, hasUv, hasCc, hasDv)
        val cleanPolys = removeGlobalCollinearVerts(polys, verts)

        val oldToNew = HashMap<Int, Int>(verts.size)
        val newCo = ArrayList<Float>(verts.size * 3)
        val newDverts = if (hasDv) ArrayList<List<Pair<Int, Float>>>() else null

        val fStart = IntArray(cleanPolys.size)
        val fSize = IntArray(cleanPolys.size)
        val faceMat = IntArray(cleanPolys.size)
        val faceSmooth = BooleanArray(cleanPolys.size)
        val corners = ArrayList<Int>(cleanPolys.size * 4)
        val uvs = if (hasUv) ArrayList<Float>(cleanPolys.size * 8) else null
        val ccs = if (hasCc) ArrayList<Int>(cleanPolys.size * 4) else null

        for (pi in cleanPolys.indices) {
            val poly = cleanPolys[pi]
            fStart[pi] = corners.size
            fSize[pi] = poly.verts.size
            faceMat[pi] = poly.mat
            faceSmooth[pi] = poly.smooth
            for (k in poly.verts.indices) {
                val oldV = poly.verts[k]
                val nv = oldToNew.getOrPut(oldV) {
                    val id = newCo.size / 3
                    val p = verts[oldV]
                    newCo.add(p.x.toFloat()); newCo.add(p.y.toFloat()); newCo.add(p.z.toFloat())
                    newDverts?.add(poly.dverts?.getOrNull(k) ?: emptyList())
                    id
                }
                corners.add(nv)
                if (uvs != null) {
                    uvs.add(poly.uvs?.getOrNull(k * 2) ?: 0f)
                    uvs.add(poly.uvs?.getOrNull(k * 2 + 1) ?: 0f)
                }
                if (ccs != null) {
                    ccs.add(poly.colors?.getOrNull(k) ?: 0)
                }
            }
        }

        return BlendMeshData(
            co = newCo.toFloatArray(),
            fStart = fStart,
            fSize = fSize,
            corners = corners.toIntArray(),
            faceMat = faceMat,
            faceSmooth = faceSmooth,
            uv = uvs?.toFloatArray(),
            cornerColor = ccs?.toIntArray(),
            pointColor = null,
            edgeCrease = emptyMap(),
            vertCrease = null,
            looseEdges = IntArray(0),
            dverts = newDverts?.toTypedArray(),
            groups = ref.groups
        ).carryNormals(ref)
    }

    private fun mergeSameOriginTris(
        tris: List<Tri>,
        verts: List<Vec3>,
        hasUv: Boolean,
        hasCc: Boolean,
        hasDv: Boolean
    ): List<OutPoly> {
        val byOrigin = LinkedHashMap<Long, ArrayList<Int>>()
        for (ti in tris.indices) {
            val t = tris[ti]
            val key = (t.origMesh.toLong() shl 48) or ((t.mat.toLong() and 0xFFFFL) shl 32) or (t.origFace.toLong() and 0xFFFFFFFFL)
            byOrigin.getOrPut(key) { ArrayList(2) }.add(ti)
        }

        val out = ArrayList<OutPoly>(tris.size)
        for ((_, group) in byOrigin) {
            if (group.size == 1) {
                out.add(triToOutPoly(tris[group[0]], hasUv, hasCc, hasDv))
                continue
            }
            val t0 = tris[group[0]]
            val normal = t0.normal
            val allCoplanar = group.all { tris[it].normal.dot(normal) > 1.0 - 1e-4 }
            if (!allCoplanar) {
                for (ti in group) out.add(triToOutPoly(tris[ti], hasUv, hasCc, hasDv))
                continue
            }

            val edgeToTris = HashMap<Long, ArrayList<Int>>()
            for (ti in group) {
                val t = tris[ti]
                for (k in 0 until 3) {
                    edgeToTris.getOrPut(BlendMeshData.key(t.vert(k), t.vert((k + 1) % 3))) { ArrayList(2) }.add(ti)
                }
            }
            val visited = HashSet<Int>(group.size)
            for (start in group) {
                if (!visited.add(start)) continue
                val comp = ArrayList<Int>()
                val q = ArrayDeque<Int>()
                q.addLast(start)
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    comp.add(cur)
                    val t = tris[cur]
                    for (k in 0 until 3) {
                        val nbs = edgeToTris[BlendMeshData.key(t.vert(k), t.vert((k + 1) % 3))] ?: continue
                        for (nb in nbs) if (visited.add(nb)) q.addLast(nb)
                    }
                }
                if (comp.size == 1) {
                    out.add(triToOutPoly(tris[comp[0]], hasUv, hasCc, hasDv))
                    continue
                }
                val merged = tryMergeSimpleBoundary(comp, tris, verts, normal, hasUv, hasCc, hasDv)
                if (merged != null) {
                    out.add(merged)
                } else {
                    for (ti in comp) out.add(triToOutPoly(tris[ti], hasUv, hasCc, hasDv))
                }
            }
        }
        return out
    }

    private fun tryMergeSimpleBoundary(
        comp: List<Int>,
        tris: List<Tri>,
        verts: List<Vec3>,
        normal: Vec3,
        hasUv: Boolean,
        hasCc: Boolean,
        hasDv: Boolean
    ): OutPoly? {
        val dirEdges = HashSet<Long>()
        val cornerUv = if (hasUv) HashMap<Int, Pair<Float, Float>>() else null
        val cornerCc = if (hasCc) HashMap<Int, Int>() else null
        val cornerDv = if (hasDv) HashMap<Int, List<Pair<Int, Float>>?>() else null

        for (ti in comp) {
            val t = tris[ti]
            for (k in 0 until 3) {
                val u = t.vert(k); val w = t.vert((k + 1) % 3)
                val rev = (w.toLong() shl 32) or (u.toLong() and 0xFFFFFFFFL)
                val fwd = (u.toLong() shl 32) or (w.toLong() and 0xFFFFFFFFL)
                if (!dirEdges.remove(rev)) dirEdges.add(fwd)
                if (cornerUv != null && !cornerUv.containsKey(u)) {
                    cornerUv[u] = when (k) { 0 -> t.uv0x to t.uv0y; 1 -> t.uv1x to t.uv1y; else -> t.uv2x to t.uv2y }
                }
                if (cornerCc != null && !cornerCc.containsKey(u)) {
                    cornerCc[u] = when (k) { 0 -> t.cc0; 1 -> t.cc1; else -> t.cc2 }
                }
                if (cornerDv != null && !cornerDv.containsKey(u)) {
                    cornerDv[u] = when (k) { 0 -> t.dv0; 1 -> t.dv1; else -> t.dv2 }
                }
            }
        }
        if (dirEdges.size < 3) return null
        val nextMap = HashMap<Int, Int>(dirEdges.size)
        for (de in dirEdges) {
            val u = (de ushr 32).toInt()
            val w = (de and 0xFFFFFFFFL).toInt()
            if (nextMap.put(u, w) != null) return null
        }
        val startV = nextMap.keys.first()
        val rawLoop = ArrayList<Int>(nextMap.size)
        var cur = startV
        var guard = 0
        while (guard++ <= nextMap.size) {
            rawLoop.add(cur)
            cur = nextMap[cur] ?: return null
            if (cur == startV) break
        }
        if (cur != startV || rawLoop.size != nextMap.size) return null

        val finalVerts = rawLoop
        if (finalVerts.size < 3) return null

        val dropAxis = dominantAxis(normal)
        val p2d = Array(finalVerts.size) { project2D(verts[finalVerts[it]], dropAxis) }
        var signedArea = 0.0
        for (i in finalVerts.indices) {
            val a = p2d[i]; val b = p2d[(i + 1) % finalVerts.size]
            signedArea += a.x * b.y - b.x * a.y
        }
        if (abs(signedArea) < 1e-12) return null

        val t0 = tris[comp[0]]
        val uvArr = if (hasUv) FloatArray(finalVerts.size * 2) { idx ->
            val pair = cornerUv?.get(finalVerts[idx / 2])
            if ((idx and 1) == 0) pair?.first ?: 0f else pair?.second ?: 0f
        } else null
        val ccArr = if (hasCc) IntArray(finalVerts.size) { cornerCc?.get(finalVerts[it]) ?: 0 } else null
        val dvArr = if (hasDv) Array(finalVerts.size) { cornerDv?.get(finalVerts[it]) } else null

        return OutPoly(finalVerts.toIntArray(), t0.mat, t0.smooth, uvArr, ccArr, dvArr)
    }

    private fun removeGlobalCollinearVerts(polys: List<OutPoly>, verts: List<Vec3>): List<OutPoly> {
        val current = ArrayList<OutPoly>(polys)
        val vertPolys = HashMap<Int, ArrayList<Int>>()
        for (pi in current.indices) {
            for (v in current[pi].verts) vertPolys.getOrPut(v) { ArrayList(3) }.add(pi)
        }
        val toDrop = HashSet<Int>()
        for ((v, pList) in vertPolys) {
            if (pList.size != 2) continue
            val pA = current[pList[0]]; val pB = current[pList[1]]
            if (pA.verts.size < 4 || pB.verts.size < 4) continue
            val iA = pA.verts.indexOf(v); val iB = pB.verts.indexOf(v)
            if (iA < 0 || iB < 0) continue
            val prevA = pA.verts[(iA + pA.verts.size - 1) % pA.verts.size]
            val nextA = pA.verts[(iA + 1) % pA.verts.size]
            val prevB = pB.verts[(iB + pB.verts.size - 1) % pB.verts.size]
            val nextB = pB.verts[(iB + 1) % pB.verts.size]
            if (prevA != nextB || nextA != prevB) continue
            val e1 = (verts[v] - verts[prevA]).norm()
            val e2 = (verts[nextA] - verts[v]).norm()
            if (e1.dot(e2) > 1.0 - 1e-6 && e1.cross(e2).lenSq() < 1e-10) {
                toDrop.add(v)
            }
        }
        if (toDrop.isEmpty()) return current
        return current.map { p ->
            var keepCount = 0
            for (v in p.verts) if (v !in toDrop) keepCount++
            if (keepCount == p.verts.size || keepCount < 3) {
                p
            } else {
                val nv = IntArray(keepCount)
                val nuv = if (p.uvs != null) FloatArray(keepCount * 2) else null
                val ncc = if (p.colors != null) IntArray(keepCount) else null
                val ndv = if (p.dverts != null) arrayOfNulls<List<Pair<Int, Float>>>(keepCount) else null
                var o = 0
                for (k in p.verts.indices) {
                    if (p.verts[k] in toDrop) continue
                    nv[o] = p.verts[k]
                    if (nuv != null && p.uvs != null) { nuv[o * 2] = p.uvs[k * 2]; nuv[o * 2 + 1] = p.uvs[k * 2 + 1] }
                    if (ncc != null && p.colors != null) ncc[o] = p.colors[k]
                    if (ndv != null && p.dverts != null) ndv[o] = p.dverts[k]
                    o++
                }
                OutPoly(nv, p.mat, p.smooth, nuv, ncc, ndv)
            }
        }
    }

    private fun triToOutPoly(t: Tri, hasUv: Boolean, hasCc: Boolean, hasDv: Boolean): OutPoly = OutPoly(
        verts = intArrayOf(t.v0, t.v1, t.v2),
        mat = t.mat,
        smooth = t.smooth,
        uvs = if (hasUv) floatArrayOf(t.uv0x, t.uv0y, t.uv1x, t.uv1y, t.uv2x, t.uv2y) else null,
        colors = if (hasCc) intArrayOf(t.cc0, t.cc1, t.cc2) else null,
        dverts = if (hasDv) arrayOf(t.dv0, t.dv1, t.dv2) else null
    )
}
