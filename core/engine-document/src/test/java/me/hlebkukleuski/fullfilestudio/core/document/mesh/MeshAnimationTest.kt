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

package me.hlebkukleuski.fullfilestudio.core.document.mesh

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshAnimationTest {

    private fun translation(x: Float, y: Float, z: Float) = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, x, y, z)

    private fun rotZ90() = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)

    private val identity = translation(0f, 0f, 0f)

    private fun animation(): MeshAnimation {
        val frames = 2
        val bones = 2
        val m = FloatArray(frames * bones * 12)
        System.arraycopy(identity, 0, m, 0, 12)
        System.arraycopy(identity, 0, m, 12, 12)
        System.arraycopy(translation(4f, 0f, 0f), 0, m, 24, 12)
        System.arraycopy(rotZ90(), 0, m, 36, 12)
        return MeshAnimation(
            fps = 1f, frameCount = frames, boneCount = bones, matrices = m,
            triBone = intArrayOf(0, -2, -1),
            skinOffset = intArrayOf(-1, 0, -1),
            skinJoints = intArrayOf(1, -1, -1, -1, 1, 0, -1, -1, 0, -1, -1, -1),
            skinWeights = floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f),
            skinBind = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            skinBindNormals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            morphCount = 1,
            morphPoly = intArrayOf(2),
            morphTarget = intArrayOf(0),
            morphDelta = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 0f, 0f, 2f),
            morphBase = floatArrayOf(0f),
            morphWeights = floatArrayOf(0f, 1f)
        )
    }

    private val base = floatArrayOf(
        0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f,
        1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
        5f, 5f, 5f, 6f, 5f, 5f, 5f, 6f, 5f
    )

    @Test
    fun posesRigidSkinnedAndMorphedTriangles() {
        val a = animation()
        assertEquals(1f, a.duration, 1e-6f)
        val start = a.pose(0f, base, base.size, null).vertices.copyOf(base.size)
        assertArrayEquals(base, start, 1e-6f)
        val end = a.pose(1f, base, base.size, null).vertices.copyOf(base.size)
        assertArrayEquals(floatArrayOf(4f, 0f, 0f, 5f, 0f, 0f, 4f, 1f, 0f), end.copyOfRange(0, 9), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 1.5f, 0.5f, 0f, 4f, 0f, 1f), end.copyOfRange(9, 18), 1e-5f)
        assertArrayEquals(floatArrayOf(5f, 5f, 7f, 6f, 5f, 7f, 5f, 6f, 7f), end.copyOfRange(18, 27), 1e-5f)
        val mid = a.pose(0.5f, base, base.size, null).vertices.copyOf(base.size)
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), mid.copyOfRange(0, 3), 1e-5f)
        assertEquals(6f, mid[20], 1e-5f)
    }

    @Test
    fun skinnedNormalsFollowJoints() {
        val a = animation()
        val normals = FloatArray(27) { if (it % 3 == 2) 1f else 0f }
        val pose = a.pose(1f, base, base.size, normals)
        val n = assertNotNull(pose.normals).let { pose.normals!! }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), n.copyOfRange(9, 12), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), n.copyOfRange(0, 3), 1e-5f)
    }

    @Test
    fun codecRoundTrip() {
        val scene = MeshScene(
            base, base.size, intArrayOf(0, 0, 0), 0f, 6f, 0f, 6f, 0f, 6f, true, "Test", false, 1000,
            emptyList(), null, animation = animation()
        )
        val buf = ByteBuffer.allocate(MeshSceneCodec.encodedSize(scene))
        MeshSceneCodec.encode(scene, buf)
        buf.flip()
        val decoded = MeshSceneCodec.decode(buf)
        val a = assertNotNull(decoded.animation).let { decoded.animation!! }
        assertEquals(2, a.frameCount)
        assertEquals(2, a.boneCount)
        assertEquals(1, a.morphCount)
        val posed = decoded.posed(1f)
        assertArrayEquals(floatArrayOf(5f, 5f, 7f), posed.vertices.copyOfRange(18, 21), 1e-5f)
        assertEquals(0, buf.remaining())
    }

    private fun clipped(): MeshAnimation {
        val m = FloatArray(3 * 12)
        System.arraycopy(identity, 0, m, 0, 12)
        System.arraycopy(identity, 0, m, 12, 12)
        System.arraycopy(translation(0f, 0f, 9f), 0, m, 24, 12)
        return MeshAnimation(
            fps = 1f, frameCount = 3, boneCount = 1, matrices = m,
            triBone = intArrayOf(0, -1, -1),
            skinOffset = null, skinJoints = null, skinWeights = null, skinBind = null, skinBindNormals = null,
            morphCount = 1,
            morphPoly = intArrayOf(2),
            morphTarget = intArrayOf(0),
            morphDelta = FloatArray(9),
            morphBase = floatArrayOf(0f),
            morphWeights = floatArrayOf(0f, 0f, 1f),
            morphNormalDelta = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f),
            clipNames = listOf("Idle", "Jump"),
            clipStarts = intArrayOf(0, 1),
            clipFrames = intArrayOf(1, 2)
        )
    }

    @Test
    fun clipsSelectTheirOwnFrameRanges() {
        val a = clipped()
        assertEquals(2, a.clipCount)
        assertEquals("Idle", a.clipName(0))
        assertEquals("Jump", a.clipName(1))
        assertEquals(0f, a.duration(0), 1e-6f)
        assertEquals(1f, a.duration(1), 1e-6f)
        assertArrayEquals(base.copyOfRange(0, 9), a.pose(5f, base, base.size, null, 0).vertices.copyOfRange(0, 9), 1e-6f)
        assertEquals(9f, a.pose(1f, base, base.size, null, 1).vertices[2], 1e-5f)
        assertEquals(4.5f, a.pose(0.5f, base, base.size, null, 1).vertices[2], 1e-5f)
    }

    @Test
    fun morphNormalDeltasBendShading() {
        val a = clipped()
        val normals = FloatArray(27) { if (it % 3 == 2) 1f else 0f }
        val still = a.pose(0f, base, base.size, normals, 1).normals!!
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), still.copyOfRange(18, 21), 1e-5f)
        val bent = a.pose(1f, base, base.size, normals, 1).normals!!
        val h = (1.0 / Math.sqrt(2.0)).toFloat()
        assertArrayEquals(floatArrayOf(h, 0f, h), bent.copyOfRange(18, 21), 1e-5f)
    }

    @Test
    fun framingCoversEveryClip() {
        val scene = MeshScene(
            base, base.size, intArrayOf(0, 0, 0), 0f, 6f, 0f, 6f, 0f, 6f, true, "Test", false, 1000,
            emptyList(), null, animation = clipped()
        )
        val framed = scene.framed()
        assertEquals(9f, framed.maxZ, 1e-4f)
        assertEquals(0f, framed.minZ, 1e-4f)
        assertEquals(6f, framed.maxX, 1e-4f)
    }

    @Test
    fun codecKeepsClipsAndNormalDeltas() {
        val scene = MeshScene(
            base, base.size, intArrayOf(0, 0, 0), 0f, 6f, 0f, 6f, 0f, 6f, true, "Test", false, 1000,
            emptyList(), null, vertexNormals = FloatArray(27) { if (it % 3 == 2) 1f else 0f }, animation = clipped()
        )
        val buf = ByteBuffer.allocate(MeshSceneCodec.encodedSize(scene))
        MeshSceneCodec.encode(scene, buf)
        buf.flip()
        val a = MeshSceneCodec.decode(buf).animation!!
        assertEquals(0, buf.remaining())
        assertEquals(listOf("Idle", "Jump"), (0 until a.clipCount).map { a.clipName(it) })
        assertEquals(1f, a.duration(1), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f), a.morphNormalDelta, 1e-6f)
    }

    private fun rotY(deg: Double): FloatArray {
        val c = Math.cos(Math.toRadians(deg)).toFloat()
        val s = Math.sin(Math.toRadians(deg)).toFloat()
        return floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c, 0f, 0f, 0f)
    }

    private fun twisted(dq: Boolean): MeshAnimation {
        val bones = 6
        val m = FloatArray(2 * bones * 12)
        for (frame in 0 until 2) {
            val mats = listOf(identity, identity, if (frame == 0) identity else rotY(120.0), identity, identity, identity)
            for ((b, mat) in mats.withIndex()) System.arraycopy(mat, 0, m, (frame * bones + b) * 12, 12)
        }
        return MeshAnimation(
            fps = 1f, frameCount = 2, boneCount = bones, matrices = m,
            triBone = intArrayOf(-2),
            skinOffset = intArrayOf(0),
            skinJoints = intArrayOf(0, 2, 0, 2, 0, 2),
            skinWeights = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            skinBind = floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 1f, 2f, 0f),
            skinBindNormals = null,
            morphCount = 0, morphPoly = null, morphTarget = null, morphDelta = null, morphBase = null, morphWeights = null,
            skinInfluences = 2,
            skinDq = if (dq) intArrayOf(4) else null
        )
    }

    @Test
    fun dualQuaternionSkinningPreservesVolume() {
        val tri = floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 1f, 2f, 0f)
        val linear = twisted(false).pose(1f, tri, 9, null).vertices
        val dq = twisted(true).pose(1f, tri, 9, null).vertices
        assertEquals(0.5f, kotlin.math.hypot(linear[0], linear[2]), 1e-4f)
        assertEquals(1f, kotlin.math.hypot(dq[0], dq[2]), 1e-4f)
        assertEquals(0.5f, dq[0], 1e-4f)
        assertEquals(-0.8660254f, dq[2], 1e-4f)
        assertEquals(2f, dq[7], 1e-5f)
    }

    @Test
    fun moreThanFourInfluencesAreBlended() {
        val bones = 6
        val m = FloatArray(2 * bones * 12)
        for (b in 0 until bones) {
            System.arraycopy(identity, 0, m, b * 12, 12)
            System.arraycopy(translation(b.toFloat(), 0f, 0f), 0, m, (bones + b) * 12, 12)
        }
        val joints = IntArray(18) { it % 6 }
        val weights = FloatArray(18) { 1f / 6f }
        val a = MeshAnimation(
            fps = 1f, frameCount = 2, boneCount = bones, matrices = m, triBone = intArrayOf(-2), skinOffset = intArrayOf(0),
            skinJoints = joints, skinWeights = weights, skinBind = FloatArray(9), skinBindNormals = null,
            morphCount = 0, morphPoly = null, morphTarget = null, morphDelta = null, morphBase = null, morphWeights = null,
            skinInfluences = 6
        )
        val p = a.pose(1f, FloatArray(9), 9, null).vertices
        assertEquals(2.5f, p[0], 1e-5f)
        val scene = MeshScene(FloatArray(9), 9, intArrayOf(0), 0f, 1f, 0f, 1f, 0f, 1f, true, "T", false, 10, emptyList(), null, animation = twisted(true))
        val buf = ByteBuffer.allocate(MeshSceneCodec.encodedSize(scene))
        MeshSceneCodec.encode(scene, buf)
        buf.flip()
        val d = MeshSceneCodec.decode(buf).animation!!
        assertEquals(2, d.skinInfluences)
        assertArrayEquals(intArrayOf(4), d.skinDq)
    }
}
