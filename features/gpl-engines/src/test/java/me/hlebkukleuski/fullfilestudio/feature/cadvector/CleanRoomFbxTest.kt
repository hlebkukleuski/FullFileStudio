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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanRoomFbxTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("fbx/$name")!!.readBytes()

    private class Collect : GltfOutput {
        val tris = ArrayList<FloatArray>()
        val colors = ArrayList<Int>()
        val textured = ArrayList<Boolean>()
        val textureSizes = ArrayList<Int>()
        override val truncated = false
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            tris.add(p.copyOf())
            colors.add(color)
            textured.add(uv != null && texture >= 0)
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int {
            if (bytes == null) return -1
            textureSizes.add(bytes.size)
            return textureSizes.size - 1
        }
        override fun resource(uri: String): ByteArray? = null
    }

    private fun check(file: String) {
        val root = FbxReader.parse(res(file))
        assertNotNull(file, root)
        val c = Collect()
        CleanRoomFbx(root!!, c).load()
        assertEquals(file, 12, c.tris.size)
        val expected = JSONObject(String(res("scene_expected.json"))).getJSONArray("expected")
        val exp = (0 until expected.length()).map { i -> expected.getJSONArray(i).let { a -> FloatArray(3) { a.getDouble(it).toFloat() } } }
        for (t in c.tris) for (k in 0 until 3) {
            val p = floatArrayOf(t[k * 3], t[k * 3 + 1], t[k * 3 + 2])
            assertTrue("$file vertex ${p.toList()} not expected", exp.any { e -> (0 until 3).all { kotlin.math.abs(e[it] - p[it]) < 1e-4f } })
        }
        assertEquals(file, 6, c.textured.count { it })
        assertEquals(file, 1, c.textureSizes.size)
        val red = c.colors.filterIndexed { i, _ -> !c.textured[i] }
        assertEquals(file, 6, red.size)
        assertTrue(file, red.all { (it ushr 24) == 128 && ((it shr 16) and 0xFF) == 230 })
    }

    @Test fun binary() = check("scene_bin.fbx")
    @Test fun ascii() = check("scene_ascii.fbx")
}
