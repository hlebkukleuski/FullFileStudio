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

import com.omnireader.core.engine.MeshAnimation

internal class AnimSink(private val files: Map<String, ByteArray> = emptyMap()) : GltfOutput {
    val verts = ArrayList<Float>()
    val normals = ArrayList<Float>()
    val normalTex = ArrayList<Int>()
    val normalTextures = ArrayList<String>()
    val textures = ArrayList<String>()
    val triBone = ArrayList<Int>()
    private val skinOffset = ArrayList<Int>()
    private val skinJoints = ArrayList<IntArray>()
    private val skinWeights = ArrayList<FloatArray>()
    private val skinDq = ArrayList<Int>()
    private val skinBind = ArrayList<Float>()
    private val skinBindN = ArrayList<Float>()
    private var skinHasN = false
    private val morphPoly = ArrayList<Int>()
    private val morphTarget = ArrayList<Int>()
    private val morphDelta = ArrayList<Float>()
    private val morphNormal = ArrayList<Float>()
    private var morphHasNormals = false
    var clip: AnimClip? = null
    var anyNormals = false
    override val truncated = false

    val polys get() = verts.size / 9

    override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) =
        triangleFull(p, color, uv, texture, c0, c1, c2, null, -1, null)

    override fun triangleN(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int, normals: FloatArray?) =
        triangleFull(p, color, uv, texture, c0, c1, c2, normals, -1, null)

    override fun triangleFull(
        p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int,
        normals: FloatArray?, normalTexture: Int, anim: TriAnim?
    ) {
        val poly = polys
        for (k in 0 until 9) verts.add(p[k])
        for (k in 0 until 9) this.normals.add(normals?.get(k) ?: 0f)
        if (normals != null) anyNormals = true
        normalTex.add(normalTexture)
        val j = anim?.joints
        val w = anim?.weights
        val b = anim?.bindPos
        if (j != null && w != null && b != null) {
            triBone.add(-2)
            skinOffset.add(skinJoints.size)
            skinJoints.add(j.copyOf())
            skinWeights.add(w.copyOf(j.size))
            skinDq.add(anim.dqPre)
            for (k in 0 until 9) skinBind.add(b[k])
            val bn = anim.bindNrm
            for (k in 0 until 9) skinBindN.add(bn?.get(k) ?: 0f)
            if (bn != null) skinHasN = true
        } else {
            triBone.add(anim?.bone ?: -1)
            skinOffset.add(-1)
        }
        val mt = anim?.morphTargets
        val md = anim?.morphDeltas
        val mn = anim?.morphNormalDeltas
        if (mn != null) morphHasNormals = true
        if (mt != null && md != null) for (k in mt.indices) {
            morphPoly.add(poly)
            morphTarget.add(mt[k])
            for (q in 0 until 9) morphDelta.add(md[k * 9 + q])
            for (q in 0 until 9) morphNormal.add(mn?.getOrNull(k * 9 + q) ?: 0f)
        }
    }

    override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
    override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int {
        textures.add(key)
        return textures.size - 1
    }
    override fun normalTexture(key: String, bytes: ByteArray?, flipGreen: Boolean, strength: Float): Int {
        normalTextures.add("$key|$flipGreen|$strength")
        return 1000 + normalTextures.size - 1
    }
    override fun animation(clip: AnimClip) {
        this.clip = clip
    }
    override fun resource(uri: String): ByteArray? = files[uri]

    override fun pixels(bytes: ByteArray): DecodedPixels? = CleanRoomTextureDecoders.decode(bytes) ?: try {
        javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))?.let { img ->
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            DecodedPixels(img.width, img.height, px)
        }
    } catch (_: Exception) {
        null
    }

    fun animation(): MeshAnimation? {
        val c = clip ?: return null
        val hasSkin = skinJoints.isNotEmpty()
        val hasMorph = morphPoly.isNotEmpty()
        val k = skinJoints.maxOfOrNull { it.size / 3 } ?: 4
        val packedJ = IntArray(skinJoints.size * 3 * k) { -1 }
        val packedW = FloatArray(packedJ.size)
        for ((e, j) in skinJoints.withIndex()) {
            val kk = j.size / 3
            for (c in 0 until 3) for (q in 0 until kk) {
                packedJ[e * 3 * k + c * k + q] = j[c * kk + q]
                packedW[e * 3 * k + c * k + q] = skinWeights[e][c * kk + q]
            }
        }
        return MeshAnimation(
            c.fps, c.frames, c.bones, c.matrices, triBone.toIntArray(), skinOffset.toIntArray(),
            if (hasSkin) packedJ else null,
            if (hasSkin) packedW else null,
            if (hasSkin) skinBind.toFloatArray() else null,
            if (hasSkin && skinHasN) skinBindN.toFloatArray() else null,
            if (hasMorph) c.morphCount else 0,
            if (hasMorph) morphPoly.toIntArray() else null,
            if (hasMorph) morphTarget.toIntArray() else null,
            if (hasMorph) morphDelta.toFloatArray() else null,
            c.morphBase, c.morphWeights,
            if (hasMorph && morphHasNormals) morphNormal.toFloatArray() else null,
            c.clipNames, c.clipStarts, c.clipFrames, k,
            if (hasSkin && skinDq.any { it >= 0 }) skinDq.toIntArray() else null
        )
    }

    fun pose(t: Float, clip: Int = 0): FloatArray {
        val a = animation() ?: return verts.toFloatArray()
        val base = verts.toFloatArray()
        return a.pose(t, base, base.size, if (anyNormals) normals.toFloatArray() else null, clip).vertices.copyOf(base.size)
    }

    fun poseNormals(t: Float, clip: Int = 0): FloatArray? {
        val a = animation() ?: return null
        val base = verts.toFloatArray()
        return a.pose(t, base, base.size, if (anyNormals) normals.toFloatArray() else null, clip).normals?.copyOf(base.size)
    }

    fun summary(t: Float): String {
        val p = pose(t)
        val mn = FloatArray(3) { Float.MAX_VALUE }
        val mx = FloatArray(3) { -Float.MAX_VALUE }
        for (i in p.indices) {
            mn[i % 3] = minOf(mn[i % 3], p[i])
            mx[i % 3] = maxOf(mx[i % 3], p[i])
        }
        return "t=$t bbox=" + mn.joinToString { "%.3f".format(it) } + " .. " + mx.joinToString { "%.3f".format(it) }
    }

    fun stats(): String = "polys=$polys skinned=${triBone.count { it == -2 }} rigid=${triBone.count { it >= 0 }} morphEntries=${morphPoly.size} " +
        "clip=${clip?.let { "fps=${it.fps} frames=${it.frames} bones=${it.bones} morphs=${it.morphCount}" }} normalTex=${normalTextures.size} normals=$anyNormals"
}
