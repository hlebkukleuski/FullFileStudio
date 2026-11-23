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

import android.graphics.BitmapFactory
import android.graphics.Color
import com.omnireader.core.engine.impl.TiffCleanRoomDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CcittTiffDecoderTest {

    private val dir = File(javaClass.classLoader!!.getResource("ccitt/text.png")!!.toURI()).parentFile!!
    private val images = listOf("text", "noise", "wide", "black", "white", "checker")
    private val variants = listOf("g4", "g3_1d", "g3_2d", "mh_rle", "g4_lsb", "g3_2d_strips")

    private fun expectedBlack(name: String): Array<BooleanArray> {
        val png = BitmapFactory.decodeFile(File(dir, "$name.png").path)
        return Array(png.height) { y -> BooleanArray(png.width) { x -> Color.red(png.getPixel(x, y)) < 128 } }
    }

    private fun setPhotometricWhiteIsZero(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val order = if (copy[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(copy).order(order)
        val ifd = bb.getInt(4)
        val count = bb.getShort(ifd).toInt() and 0xFFFF
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            if ((bb.getShort(e).toInt() and 0xFFFF) == 262) {
                bb.putShort(e + 8, 0)
                return copy
            }
        }
        throw AssertionError("no photometric tag")
    }

    private fun check(file: String, expected: Array<BooleanArray>, invert: Boolean, bytes: ByteArray) {
        val bmp = TiffCleanRoomDecoder.decodeFromBytes(bytes)
        assertNotNull(file, bmp)
        bmp!!
        val step = (expected[0].size + bmp.width - 1) / bmp.width
        assertEquals(file, (expected[0].size + step - 1) / step, bmp.width)
        assertEquals(file, (expected.size + step - 1) / step, bmp.height)
        var mismatches = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val black = Color.red(bmp.getPixel(x, y)) < 128
            if (black != (expected[y * step][x * step] != invert)) mismatches++
        }
        assertEquals("$file mismatching pixels", 0, mismatches)
    }

    @Test
    fun decodesAllCcittVariants() {
        for (name in images) {
            val expected = expectedBlack(name)
            for (variant in variants) {
                val file = "${name}_$variant.tif"
                check(file, expected, false, File(dir, file).readBytes())
            }
        }
    }

    @Test
    fun honoursWhiteIsZeroPhotometric() {
        for (name in images) {
            val expected = expectedBlack(name)
            for (variant in listOf("g4", "g3_2d", "mh_rle")) {
                val file = "${name}_$variant.tif"
                check("$file (WhiteIsZero)", expected, true, setPhotometricWhiteIsZero(File(dir, file).readBytes()))
            }
        }
    }
}
