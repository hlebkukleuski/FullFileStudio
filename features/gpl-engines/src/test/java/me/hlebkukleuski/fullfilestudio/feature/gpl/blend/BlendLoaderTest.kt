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

import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlendLoaderTest {

    private fun check(name: String, expectedVersion: Int) {
        val raw = javaClass.classLoader!!.getResourceAsStream("blend/$name")!!.readBytes()
        val file = BlendFile(BlendFile.decompress(raw))
        assertEquals(name, expectedVersion, file.version)
        val tris = ArrayList<FloatArray>()
        val colors = HashSet<Int>()
        val out = object : GltfOutput {
            override val truncated = false
            override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
                tris.add(p.copyOf())
                colors.add(color)
            }
            override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
            override fun texture(key: String, bytes: ByteArray?, opaque: Boolean) = -1
            override fun resource(uri: String): ByteArray? = null
        }
        assertEquals(name, 1, BlendLoader(file, out).load())
        assertEquals(name, 12, tris.size)
        for (t in tris) for (v in t) assertEquals(name, 1f, kotlin.math.abs(v), 1e-4f)
        assertTrue(name, colors.single().let { (it and 0xFFFFFF) == 0xE7E7E7 })
    }

    @Test fun blender279Zstd() = check("startup_279.blend", 272)
    @Test fun blender293Gzip() = check("startup_293.blend", 293)
    @Test fun blender36Zstd() = check("startup_36.blend", 306)
}
