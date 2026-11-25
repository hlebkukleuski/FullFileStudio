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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GltfParserTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("gltf/$name")!!.readBytes()

    private class Glb(val json: JSONObject, val bin: ByteArray)

    private fun glb(bytes: ByteArray): Glb {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(12)
        val json = JSONObject(String(bytes, 20, jl, Charsets.UTF_8))
        val bl = bb.getInt(20 + jl)
        return Glb(json, bytes.copyOfRange(28 + jl, 28 + jl + bl))
    }

    private class Collect(private val files: Map<String, ByteArray>) : GltfOutput {
        val tris = ArrayList<FloatArray>()
        val uvs = ArrayList<FloatArray?>()
        val colors = ArrayList<Int>()
        val textures = ArrayList<String>()
        val requested = ArrayList<String>()
        override val truncated = false
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            tris.add(p.copyOf())
            uvs.add(uv?.copyOf())
            colors.add(c0)
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int {
            if (bytes == null) return -1
            textures.add(key)
            return textures.size - 1
        }
        override fun resource(uri: String): ByteArray? {
            requested.add(uri)
            return files[uri]
        }
    }

    private fun run(json: JSONObject, bin: ByteArray?, files: Map<String, ByteArray> = emptyMap()): Collect {
        val c = Collect(files)
        GltfParser(json, bin?.let { b -> GltfBinSource { off, len -> b.copyOfRange(off.toInt(), off.toInt() + len) } }, bin?.size?.toLong() ?: 0L, c, 1_000_000).load()
        return c
    }

    @Test
    fun meshoptViewsMatchRawData() {
        val raw = glb(res("cube_emb.glb"))
        val packed = glb(res("cube_meshopt.glb"))
        val rawViews = raw.json.getJSONArray("bufferViews")
        val packedViews = packed.json.getJSONArray("bufferViews")
        for (v in 0..2) {
            val rv = rawViews.getJSONObject(v)
            val expected = raw.bin.copyOfRange(rv.getInt("byteOffset"), rv.getInt("byteOffset") + rv.getInt("byteLength"))
            val ext = packedViews.getJSONObject(v).getJSONObject("extensions").getJSONObject("EXT_meshopt_compression")
            val src = packed.bin.copyOfRange(ext.getInt("byteOffset"), ext.getInt("byteOffset") + ext.getInt("byteLength"))
            val decoded = MeshoptDecoder.decode(src, ext.getInt("count"), ext.getInt("byteStride"), ext.getString("mode"), ext.optString("filter", "NONE"))
            assertArrayEquals("view $v", expected, decoded)
        }
        val c = run(packed.json, packed.bin)
        assertEquals(12, c.tris.size)
        assertEquals(1, c.textures.size)
        assertTrue(c.uvs.all { it != null })
    }

    @Test
    fun externalBufferAndImage() {
        val json = JSONObject(String(res("cube_ext.gltf"), Charsets.UTF_8))
        val missing = run(json, null)
        assertEquals(0, missing.tris.size)
        assertTrue("cube.bin" in missing.requested)
        val c = run(JSONObject(String(res("cube_ext.gltf"), Charsets.UTF_8)), null, mapOf("cube.bin" to res("cube.bin"), "checker.png" to res("checker.png")))
        assertEquals(12, c.tris.size)
        assertEquals(1, c.textures.size)
        for (t in c.tris) for (k in 0 until 9 step 3) {
            val len = t[k] * t[k] + t[k + 1] * t[k + 1] + t[k + 2] * t[k + 2]
            assertEquals(3f, len, 1e-4f)
        }
    }

    @Test
    fun textureTransformAndVertexColors() {
        val g = glb(res("cube_emb.glb"))
        val c = run(g.json, g.bin)
        assertEquals(12, c.tris.size)
        val maxUv = c.uvs.filterNotNull().maxOf { it.max() }
        assertEquals(2f, maxUv, 1e-5f)
        assertTrue(c.colors.any { it != 0 && it != -1 })
    }
}
