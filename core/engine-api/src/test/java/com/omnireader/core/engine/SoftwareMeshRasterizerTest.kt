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

package com.omnireader.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.omnireader.core.engine.impl.SoftwareMeshRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoftwareMeshRasterizerTest {

    private fun quad(z: Float, color: Int): Pair<FloatArray, IntArray> {
        val v = floatArrayOf(-1f, -1f, z, 1f, -1f, z, 1f, 1f, z, -1f, -1f, z, 1f, 1f, z, -1f, 1f, z)
        return v to intArrayOf(color, color)
    }

    private fun scene(v: FloatArray, c: IntArray, uvs: FloatArray? = null, tex: IntArray? = null, textures: List<Bitmap> = emptyList(), ntex: IntArray? = null) =
        MeshScene(v, v.size, c, -1f, 1f, -1f, 1f, -1f, 1f, true, "t", false, 1000, emptyList(), null,
            uvs = uvs, triTextures = tex, textures = textures, triNormalTextures = ntex)

    @Test
    fun depthBufferKeepsNearestSurface() {
        val (back, bc) = quad(-0.5f, Color.RED)
        val (front, fc) = quad(0.5f, Color.BLUE)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val s = scene(back + front, bc + fc)
        SoftwareMeshRasterizer().render(s, bmp, 0f, 0f, 1f, false, 1000)
        val center = bmp.getPixel(32, 32)
        assertTrue(Color.blue(center) > 150 && Color.red(center) < 40)
        assertEquals(Color.rgb(0x12, 0x12, 0x12), bmp.getPixel(0, 0))
        val s2 = scene(front + back, fc + bc)
        val bmp2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        SoftwareMeshRasterizer().render(s2, bmp2, 0f, 0f, 1f, false, 1000)
        assertEquals(center, bmp2.getPixel(32, 32))
    }

    @Test
    fun normalMapChangesShading() {
        val (v, c) = quad(0f, Color.WHITE)
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
        val flat = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 255)) }
        val tilted = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(230, 128, 160)) }
        val opposite = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(25, 128, 160)) }
        fun render(n: Bitmap): Int {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            SoftwareMeshRasterizer().render(scene(v, c, uv, intArrayOf(-1, -1), listOf(n), intArrayOf(0, 0)), bmp, 0f, 0f, 1f, false, 1000)
            return bmp.getPixel(32, 32)
        }
        val plain = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        SoftwareMeshRasterizer().render(scene(v, c), plain, 0f, 0f, 1f, false, 1000)
        val a = render(flat)
        val b = render(tilted)
        val o = render(opposite)
        val p = plain.getPixel(32, 32)
        assertTrue("flat=${Integer.toHexString(a)} plain=${Integer.toHexString(p)}", kotlin.math.abs(Color.red(a) - Color.red(p)) <= 3)
        assertTrue("flat=${Integer.toHexString(a)} tilted=${Integer.toHexString(b)}", Color.red(a) != Color.red(b))
        assertTrue("tilted=${Integer.toHexString(b)} opposite=${Integer.toHexString(o)}", kotlin.math.abs(Color.red(b) - Color.red(o)) > 20)
    }
}
