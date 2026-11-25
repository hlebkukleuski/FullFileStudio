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
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendSkinAndBooleanTest {

    private fun wireMesh(
        verts: FloatArray,
        edges: IntArray,
        radii: FloatArray,
        flags: IntArray
    ): BlendMeshData = BlendMeshData(
        co = verts,
        fStart = IntArray(0),
        fSize = IntArray(0),
        corners = IntArray(0),
        faceMat = null,
        faceSmooth = null,
        uv = null,
        cornerColor = null,
        pointColor = null,
        edgeCrease = emptyMap(),
        vertCrease = null,
        looseEdges = edges,
        dverts = null,
        groups = emptyList()
    ).also {
        it.fileEdges = edges
        it.skinRadius = radii
        it.skinFlag = flags
    }

    private fun boxMesh(
        cx: Float = 0f, cy: Float = 0f, cz: Float = 0f,
        hx: Float = 1f, hy: Float = 1f, hz: Float = 1f,
        mat: Int = 0
    ): BlendMeshData {
        val co = floatArrayOf(
            cx - hx, cy - hy, cz - hz,
            cx + hx, cy - hy, cz - hz,
            cx + hx, cy + hy, cz - hz,
            cx - hx, cy + hy, cz - hz,
            cx - hx, cy - hy, cz + hz,
            cx + hx, cy - hy, cz + hz,
            cx + hx, cy + hy, cz + hz,
            cx - hx, cy + hy, cz + hz
        )
        val corners = intArrayOf(
            0, 3, 2, 1,
            4, 5, 6, 7,
            0, 1, 5, 4,
            1, 2, 6, 5,
            2, 3, 7, 6,
            3, 0, 4, 7
        )
        val fStart = intArrayOf(0, 4, 8, 12, 16, 20)
        val fSize = intArrayOf(4, 4, 4, 4, 4, 4)
        val uv = FloatArray(corners.size * 2) { i ->
            when ((i / 2) % 4) {
                0 -> 0f
                1 -> if ((i and 1) == 0) 1f else 0f
                2 -> 1f
                else -> if ((i and 1) == 0) 0f else 1f
            }
        }
        return BlendMeshData(
            co = co,
            fStart = fStart,
            fSize = fSize,
            corners = corners,
            faceMat = IntArray(6) { mat },
            faceSmooth = BooleanArray(6) { false },
            uv = uv,
            cornerColor = null,
            pointColor = null,
            edgeCrease = emptyMap(),
            vertCrease = null,
            looseEdges = IntArray(0),
            dverts = null,
            groups = emptyList()
        )
    }

    private fun meshVolume(d: BlendMeshData): Double {
        var vol6 = 0.0
        for (f in 0 until d.nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            if (m < 3) continue
            val i0 = d.corners[s]
            val ax = d.co[i0 * 3].toDouble(); val ay = d.co[i0 * 3 + 1].toDouble(); val az = d.co[i0 * 3 + 2].toDouble()
            for (k in 1 until m - 1) {
                val i1 = d.corners[s + k]; val i2 = d.corners[s + k + 1]
                val bx = d.co[i1 * 3].toDouble(); val by = d.co[i1 * 3 + 1].toDouble(); val bz = d.co[i1 * 3 + 2].toDouble()
                val cx = d.co[i2 * 3].toDouble(); val cy = d.co[i2 * 3 + 1].toDouble(); val cz = d.co[i2 * 3 + 2].toDouble()
                vol6 += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
            }
        }
        return vol6 / 6.0
    }

    private fun meshArea(d: BlendMeshData): Double {
        var area = 0.0
        for (f in 0 until d.nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            if (m < 3) continue
            val i0 = d.corners[s]
            val ax = d.co[i0 * 3].toDouble(); val ay = d.co[i0 * 3 + 1].toDouble(); val az = d.co[i0 * 3 + 2].toDouble()
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (k in 1 until m - 1) {
                val i1 = d.corners[s + k]; val i2 = d.corners[s + k + 1]
                val bx = d.co[i1 * 3].toDouble() - ax; val by = d.co[i1 * 3 + 1].toDouble() - ay; val bz = d.co[i1 * 3 + 2].toDouble() - az
                val cx = d.co[i2 * 3].toDouble() - ax; val cy = d.co[i2 * 3 + 1].toDouble() - ay; val cz = d.co[i2 * 3 + 2].toDouble() - az
                sx += by * cz - bz * cy; sy += bz * cx - bx * cz; sz += bx * cy - by * cx
            }
            area += 0.5 * sqrt(sx * sx + sy * sy + sz * sz)
        }
        return area
    }

    private fun assertWatertightClosedManifold(d: BlendMeshData) {
        assertTrue("Expected non-empty mesh", d.nF >= 4 && d.nV >= 4)
        val dirEdges = HashMap<Long, Int>()
        for (f in 0 until d.nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            for (k in 0 until m) {
                val u = d.corners[s + k]; val v = d.corners[s + (k + 1) % m]
                val key = (u.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)
                dirEdges[key] = (dirEdges[key] ?: 0) + 1
            }
        }
        for ((key, cnt) in dirEdges) {
            assertEquals("Duplicate directed half-edge", 1, cnt)
            val u = (key ushr 32).toInt()
            val v = (key and 0xFFFFFFFFL).toInt()
            val rev = (v.toLong() shl 32) or (u.toLong() and 0xFFFFFFFFL)
            assertEquals("Unmatched boundary edge ($u -> $v)", 1, dirEdges[rev] ?: 0)
        }
    }

    @Test
    fun skinSingleVertAndSingleEdgeMatchBlenderGeometry() {
        val singleVert = wireMesh(
            floatArrayOf(0f, 0f, 0f),
            IntArray(0),
            floatArrayOf(0.25f, 0.5f),
            intArrayOf(BlendSkinModifier.FLAG_ROOT)
        )
        val box = BlendSkinModifier.apply(singleVert, 0f, 0, BlendSkinModifier.SYM_X)
        assertEquals(8, box.nV)
        assertEquals(6, box.nF)
        assertWatertightClosedManifold(box)
        assertEquals(2.0 * 0.25 * 2.0 * 0.5 * 2.0 * 0.375, meshVolume(box), 1e-4)

        val singleEdge = wireMesh(
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 2f),
            intArrayOf(0, 1),
            floatArrayOf(0.25f, 0.5f, 0.25f, 0.5f),
            intArrayOf(BlendSkinModifier.FLAG_ROOT, 0)
        )
        val tube = BlendSkinModifier.apply(singleEdge, 0f, BlendSkinModifier.MOD_FLAG_SMOOTH, BlendSkinModifier.SYM_X)
        assertEquals(16, tube.nV)
        assertEquals(14, tube.nF)
        assertTrue(tube.faceSmooth!!.all { it })
        assertWatertightClosedManifold(tube)
        assertEquals(2.0 * 0.25 * 2.0 * 0.5 * 2.0, meshVolume(tube), 1e-4)
    }

    @Test
    fun skinElbowTBranchSmoothingAndClosedLoopAreWatertight() {
        val lBend = wireMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f),
            intArrayOf(0, 1, 1, 2),
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
            intArrayOf(BlendSkinModifier.FLAG_ROOT, 0, 0)
        )
        val lRes = BlendSkinModifier.apply(lBend, 0f, 0, BlendSkinModifier.SYM_X)
        assertEquals(28, lRes.nV)
        assertEquals(26, lRes.nF)
        assertWatertightClosedManifold(lRes)
        assertTrue(meshVolume(lRes) > 0.2)

        val tBranch = wireMesh(
            floatArrayOf(0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 1, 0, 2, 0, 3),
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
            intArrayOf(BlendSkinModifier.FLAG_ROOT, 0, 0, 0)
        )
        val t0 = BlendSkinModifier.apply(tBranch, 0f, 0, BlendSkinModifier.SYM_X)
        val t5 = BlendSkinModifier.apply(tBranch, 0.5f, 0, BlendSkinModifier.SYM_X)
        assertWatertightClosedManifold(t0)
        assertWatertightClosedManifold(t5)
        assertEquals(t0.nV, t5.nV)
        assertEquals(t0.nF, t5.nF)
        assertTrue(meshVolume(t5) < meshVolume(t0))

        val loop = wireMesh(
            floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 0),
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
            intArrayOf(BlendSkinModifier.FLAG_ROOT, 0, 0, 0)
        )
        val loopRes = BlendSkinModifier.apply(loop, 0f, 0, BlendSkinModifier.SYM_X)
        assertWatertightClosedManifold(loopRes)
        assertTrue(meshVolume(loopRes) > 0.8)
    }

    @Test
    fun booleanOffsetDifferenceUnionAndIntersectMatchExactVolumesAndAreas() {
        val a = boxMesh(0f, 0f, 0f, 1f, 1f, 1f, mat = 0)
        val b = boxMesh(0f, 0f, 0f, 1f, 1f, 1f, mat = 0)
        val mtx = GltfParser.IDENTITY.copyOf().also { it[12] = 1f; it[13] = 0.5f; it[14] = 0.5f }
        val op = listOf(BlendBooleanModifier.Operand(b, mtx, intArrayOf(1)))

        val diff = BlendBooleanModifier.apply(a, op, BlendBooleanModifier.OP_DIFFERENCE, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, BlendBooleanModifier.MAT_MODE_TRANSFER, 2)
        assertWatertightClosedManifold(diff)
        assertEquals(5.75, meshVolume(diff), 1e-3)
        assertEquals(24.0, meshArea(diff), 1e-3)
        assertTrue(diff.faceMat!!.any { it == 0 } && diff.faceMat!!.any { it == 1 })

        val union = BlendBooleanModifier.apply(a, op, BlendBooleanModifier.OP_UNION, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, BlendBooleanModifier.MAT_MODE_INDEX, 1)
        assertWatertightClosedManifold(union)
        assertEquals(13.75, meshVolume(union), 1e-3)
        assertEquals(37.5, meshArea(union), 1e-3)

        val inter = BlendBooleanModifier.apply(a, op, BlendBooleanModifier.OP_INTERSECT, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, BlendBooleanModifier.MAT_MODE_INDEX, 1)
        assertWatertightClosedManifold(inter)
        assertEquals(2.25, meshVolume(inter), 1e-3)
        assertEquals(10.5, meshArea(inter), 1e-3)
    }

    @Test
    fun booleanCoplanarThroughHoleRotatedAndCollectionMatchGroundTruth() {
        val a = boxMesh(0f, 0f, 0f, 1f, 1f, 1f)
        val b = boxMesh(0f, 0f, 0f, 1f, 1f, 1f)
        val mCop = GltfParser.IDENTITY.copyOf().also { it[12] = 1f }
        val opCop = listOf(BlendBooleanModifier.Operand(b, mCop, null))

        val diffCop = BlendBooleanModifier.apply(a, opCop, BlendBooleanModifier.OP_DIFFERENCE, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, 0, 1)
        assertWatertightClosedManifold(diffCop)
        assertEquals(4.0, meshVolume(diffCop), 1e-3)
        assertEquals(16.0, meshArea(diffCop), 1e-3)

        val unionCop = BlendBooleanModifier.apply(a, opCop, BlendBooleanModifier.OP_UNION, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, 0, 1)
        assertWatertightClosedManifold(unionCop)
        assertEquals(12.0, meshVolume(unionCop), 1e-3)
        assertEquals(32.0, meshArea(unionCop), 1e-3)

        val interCop = BlendBooleanModifier.apply(a, opCop, BlendBooleanModifier.OP_INTERSECT, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, 0, 1)
        assertWatertightClosedManifold(interCop)
        assertEquals(4.0, meshVolume(interCop), 1e-3)
        assertEquals(16.0, meshArea(interCop), 1e-3)

        val mHole = GltfParser.IDENTITY.copyOf().also { it[0] = 0.5f; it[5] = 0.5f; it[10] = 2f }
        val hole = BlendBooleanModifier.apply(a, listOf(BlendBooleanModifier.Operand(b, mHole, null)), BlendBooleanModifier.OP_DIFFERENCE, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, 0, 1)
        assertWatertightClosedManifold(hole)
        assertEquals(6.0, meshVolume(hole), 1e-3)
        assertEquals(30.0, meshArea(hole), 1e-3)

        val rx = 0.4; val ry = 0.7; val rz = 0.3
        val cx = cos(rx); val sx = sin(rx); val cy = cos(ry); val sy = sin(ry); val cz = cos(rz); val sz = sin(rz)
        val sc = 0.8
        val mRot = floatArrayOf(
            (cy * cz * sc).toFloat(), (cy * sz * sc).toFloat(), (-sy * sc).toFloat(), 0f,
            ((sx * sy * cz - cx * sz) * sc).toFloat(), ((sx * sy * sz + cx * cz) * sc).toFloat(), (sx * cy * sc).toFloat(), 0f,
            ((cx * sy * cz + sx * sz) * sc).toFloat(), ((cx * sy * sz - sx * cz) * sc).toFloat(), (cx * cy * sc).toFloat(), 0f,
            0.5f, 0.5f, 0.5f, 1f
        )
        val rotDiff = BlendBooleanModifier.apply(a, listOf(BlendBooleanModifier.Operand(b, mRot, null)), BlendBooleanModifier.OP_DIFFERENCE, 1e-6f, BlendBooleanModifier.FLAG_OBJECT, 0, 1)
        assertWatertightClosedManifold(rotDiff)
        assertEquals(5.680974, meshVolume(rotDiff), 1e-3)
        assertEquals(24.983349, meshArea(rotDiff), 1e-3)

        val m1 = GltfParser.IDENTITY.copyOf().also { it[0] = 0.4f; it[5] = 0.4f; it[10] = 2f; it[12] = -0.4f }
        val m2 = GltfParser.IDENTITY.copyOf().also { it[0] = 0.4f; it[5] = 0.4f; it[10] = 2f; it[12] = 0.4f }
        val collDiff = BlendBooleanModifier.apply(
            a,
            listOf(BlendBooleanModifier.Operand(b, m1, null), BlendBooleanModifier.Operand(b, m2, null)),
            BlendBooleanModifier.OP_DIFFERENCE,
            1e-6f,
            BlendBooleanModifier.FLAG_COLLECTION,
            0,
            1
        )
        assertWatertightClosedManifold(collDiff)
        assertEquals(8.0 - 2.0 * (0.8 * 0.8 * 2.0), meshVolume(collDiff), 1e-3)
    }

    @Test
    fun booleanNegativeScaleInvertedOperandHoleTolerantAndSelfWork() {
        val a = boxMesh(0f, 0f, 0f, 1f, 1f, 1f)
        val b = boxMesh(0f, 0f, 0f, 1f, 1f, 1f)

        val mNeg = GltfParser.IDENTITY.copyOf().also {
            it[0] = -1f
            it[12] = 0.5f; it[13] = 0.5f; it[14] = 0.5f
        }
        val interNegMtx = BlendBooleanModifier.apply(
            a,
            listOf(BlendBooleanModifier.Operand(b, mNeg, null)),
            BlendBooleanModifier.OP_INTERSECT,
            1e-6f,
            BlendBooleanModifier.FLAG_OBJECT,
            0,
            1
        )
        assertWatertightClosedManifold(interNegMtx)
        assertEquals(3.375, meshVolume(interNegMtx), 1e-3)
        assertEquals(13.5, meshArea(interNegMtx), 1e-3)

        val flippedCorners = b.corners.copyOf()
        for (f in 0 until b.nF) {
            val s = b.fStart[f]; val k = b.fSize[f]
            for (i in 0 until k / 2) {
                val t = flippedCorners[s + i]
                flippedCorners[s + i] = flippedCorners[s + k - 1 - i]
                flippedCorners[s + k - 1 - i] = t
            }
        }
        val invertedB = BlendMeshData(
            b.co.copyOf(), b.fStart.copyOf(), b.fSize.copyOf(), flippedCorners,
            b.faceMat?.copyOf(), b.faceSmooth?.copyOf(), null, null, null,
            emptyMap(), null, IntArray(0), null, emptyList()
        )
        val mShift = GltfParser.IDENTITY.copyOf().also {
            it[12] = 0.5f; it[13] = 0.5f; it[14] = 0.5f
        }
        val interInv = BlendBooleanModifier.apply(
            a,
            listOf(BlendBooleanModifier.Operand(invertedB, mShift, null)),
            BlendBooleanModifier.OP_INTERSECT,
            1e-6f,
            BlendBooleanModifier.FLAG_OBJECT,
            0,
            1
        )
        assertWatertightClosedManifold(interInv)
        assertEquals(3.375, meshVolume(interInv), 1e-3)
        assertEquals(13.5, meshArea(interInv), 1e-3)

        val diffHoleTolSelf = BlendBooleanModifier.apply(
            a,
            listOf(BlendBooleanModifier.Operand(b, mShift, null)),
            BlendBooleanModifier.OP_DIFFERENCE,
            1e-6f,
            BlendBooleanModifier.FLAG_OBJECT or BlendBooleanModifier.FLAG_HOLE_TOLERANT or BlendBooleanModifier.FLAG_SELF,
            0,
            1
        )
        assertWatertightClosedManifold(diffHoleTolSelf)
        assertEquals(4.625, meshVolume(diffHoleTolSelf), 1e-3)
        assertEquals(24.0, meshArea(diffHoleTolSelf), 1e-3)
    }
}
