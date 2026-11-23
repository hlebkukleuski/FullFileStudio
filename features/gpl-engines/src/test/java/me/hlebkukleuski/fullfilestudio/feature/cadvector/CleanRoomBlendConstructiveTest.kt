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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanRoomBlendConstructiveTest {
    private fun cube(size: Float, ox: Float = 0f, oy: Float = 0f, oz: Float = 0f): BlendMeshData {
        val h = size / 2f
        val co = floatArrayOf(h, h, h, h, h, -h, h, -h, h, h, -h, -h, -h, h, h, -h, h, -h, -h, -h, h, -h, -h, -h)
        for (v in 0 until 8) { co[v * 3] += ox; co[v * 3 + 1] += oy; co[v * 3 + 2] += oz }
        val corners = intArrayOf(0, 4, 6, 2, 3, 2, 6, 7, 7, 6, 4, 5, 5, 1, 3, 7, 1, 0, 2, 3, 5, 4, 0, 1)
        return BlendMeshData(co, IntArray(6) { it * 4 }, IntArray(6) { 4 }, corners, null, null, null, null, null, emptyMap(), null, IntArray(0), null, emptyList())
    }

    private fun plane(size: Float): BlendMeshData {
        val h = size / 2f
        val co = floatArrayOf(-h, -h, 0f, h, -h, 0f, -h, h, 0f, h, h, 0f)
        return BlendMeshData(co, intArrayOf(0), intArrayOf(4), intArrayOf(0, 1, 3, 2), null, null, null, null, null, emptyMap(), null, IntArray(0), null, emptyList())
    }

    private fun assertContains(co: FloatArray, p: FloatArray, tol: Float = 2e-5f) {
        val hit = (0 until co.size / 3).any { v -> (0 until 3).all { abs(co[v * 3 + it] - p[it]) <= tol } }
        assertTrue("missing ${p.toList()}", hit)
    }

    @Test
    fun subsurfBelowQualityMatchesBlenderPatchEvaluation() {
        val q1 = BlendMeshData.subsurf(cube(2f), 3, 1, true, false, true, BlendSubdiv.BOUNDARY_EDGE_ONLY, BlendSubdiv.FVAR_BOUNDARIES)
        assertEquals(386, q1.nV)
        assertEquals(384, q1.nF)
        for (p in listOf(floatArrayOf(0.54391f, 0.54391f, 0.397376f), floatArrayOf(-0.729207f, -0.318375f, -0.318375f), floatArrayOf(0.781829f, 0.33314f, 0f), floatArrayOf(-0.431589f, -0.431589f, 0.613212f))) assertContains(q1.co, p)
        val q2 = BlendMeshData.subsurf(cube(2f), 3, 2, true, false, true, BlendSubdiv.BOUNDARY_EDGE_ONLY, BlendSubdiv.FVAR_BOUNDARIES)
        for (p in listOf(floatArrayOf(0.537904f, 0.537904f, 0.412377f), floatArrayOf(-0.72899f, -0.316157f, -0.316157f), floatArrayOf(-0.432461f, -0.432461f, 0.610082f))) assertContains(q2.co, p)
    }

    @Test
    fun mirrorMergesWithinToleranceAndDropsDuplicateFaces() {
        val mtx = CleanRoomGltf.IDENTITY.copyOf().also { it[5] = -1f }
        val r = BlendMeshOps.mirror(cube(1f, 0.8f, 0.3f, 0.1f), 1, mtx, BlendMeshOps.MIR_AXIS_X shl 1, 0.7f, 0.001f, FloatArray(2), FloatArray(2), true, null, null)
        assertEquals(12, r.nV)
        assertEquals(11, r.nF)
        val plain = BlendMeshOps.mirror(cube(1f, 0.8f, 0.3f, 0.1f), 1, mtx, (BlendMeshOps.MIR_AXIS_X shl 1) or BlendMeshOps.MIR_NO_MERGE, 0.7f, 0.001f, FloatArray(2), FloatArray(2), true, null, null)
        assertEquals(16, plain.nV)
        assertEquals(12, plain.nF)
    }

    @Test
    fun mirrorBisectKeepsPositiveHalfAndWelds() {
        val mtx = CleanRoomGltf.IDENTITY.copyOf().also { it[0] = -1f }
        val flag = BlendMeshOps.MIR_AXIS_X or BlendMeshOps.MIR_BISECT_AXIS_X
        val r = BlendMeshOps.mirror(cube(2f, 0.5f), 0, mtx, flag, 0.001f, 0.001f, FloatArray(2), FloatArray(2), true, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        assertEquals(12, r.nV)
        assertEquals(10, r.nF)
        for (v in 0 until r.nV) assertTrue(abs(abs(r.co[v * 3]) - 1.5f) < 1e-6f || abs(r.co[v * 3]) < 1e-6f)
    }

    @Test
    fun arrayMergesAdjacentAndFirstLastCopies() {
        val ring = BlendMeshOps.array(plane(1f), 4, 0, 0f, 2, floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f), null, 3, 0.01f, FloatArray(2), null, null, null)
        assertEquals(10, ring.nV)
        assertEquals(4, ring.nF)
        assertContains(ring.co, floatArrayOf(1.5f, -0.5f, 0f))
        assertContains(ring.co, floatArrayOf(3.5f, 0.5f, 0f))
        val cubes = BlendMeshOps.array(cube(1f), 5, 0, 0f, 2, floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f), null, 1, 0.01f, FloatArray(2), null, null, null)
        assertEquals(24, cubes.nV)
        assertEquals(26, cubes.nF)
        val fit = BlendMeshOps.array(cube(1f), 1, 1, 7.3f, 2, floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f), null, 0, 0.01f, FloatArray(2), null, null, null)
        assertEquals(64, fit.nV)
    }

    @Test
    fun vertexGroupSideNamesFlipLikeBlender() {
        assertEquals("arm.R", BlendMeshOps.flipSideName("arm.L"))
        assertEquals("hand.l.001", BlendMeshOps.flipSideName("hand.r.001"))
        assertEquals("Right_hand", BlendMeshOps.flipSideName("Left_hand"))
        assertEquals("RIGHTfoot", BlendMeshOps.flipSideName("LEFTfoot"))
        assertEquals("L_eye", BlendMeshOps.flipSideName("R_eye"))
        assertEquals("mid", BlendMeshOps.flipSideName("mid"))
        val map = BlendMeshOps.flipMap(listOf("arm.L", "arm.R", "mid"))
        assertEquals(listOf(1, 0, -1), map.toList())
    }

    @Test
    fun quadVertexNormalsUseNewellArea() {
        val co = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0.3f, 1f, 1f, 0f, 0f, 1f, 0f)
        val topo = MeshTopology(intArrayOf(0, 1, 2, 3), intArrayOf(0), intArrayOf(4), IntArray(0))
        val n = topo.vertexNormals(co)
        val d1 = floatArrayOf(-1f, -1f, 0f)
        val d2 = floatArrayOf(1f, -1f, 0.3f)
        val c = floatArrayOf(d1[1] * d2[2] - d1[2] * d2[1], d1[2] * d2[0] - d1[0] * d2[2], d1[0] * d2[1] - d1[1] * d2[0])
        val l = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
        for (v in 0 until 4) for (k in 0 until 3) assertEquals(c[k] / l, n[v * 3 + k], 1e-6f)
    }
}
