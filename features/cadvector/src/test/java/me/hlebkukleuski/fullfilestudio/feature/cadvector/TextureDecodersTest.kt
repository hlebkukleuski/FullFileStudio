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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TextureDecodersTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("tex/$name")!!.readBytes()

    @Test
    fun tga() {
        for (name in listOf("rle.tga", "raw.tga")) {
            val d = TextureDecoders.decode(res(name))
            assertNotNull(name, d)
            assertEquals(8, d!!.width)
            assertEquals(4, d.height)
            assertEquals(name, 0xFFFF0000.toInt(), d.argb[0])
            assertEquals(name, 0xFF0000FF.toInt(), d.argb[1 * 8 + 3])
        }
        assertEquals(0x8000FF00.toInt(), TextureDecoders.decode(res("rle.tga"))!!.argb[3 * 8 + 7])
    }

    @Test
    fun dds() {
        for (name in listOf("dxt1.dds", "dxt5.dds")) {
            val d = TextureDecoders.decode(res(name))
            assertNotNull(name, d)
            assertEquals(8, d!!.width)
            for (px in d.argb) assertEquals(name, 0xFFFF0000.toInt(), px)
        }
    }
}
