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

import com.omnireader.core.engine.impl.CleanRoomDjvuDocument
import com.omnireader.core.engine.impl.DjvuByteArraySource
import com.omnireader.core.engine.impl.DjvuBzz
import com.omnireader.core.engine.impl.DjvuFormatException
import com.omnireader.core.engine.impl.DjvuRaster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.zip.CRC32

class CleanRoomDjvuDecoderTest {

    private fun fixture(name: String): ByteArray = File(javaClass.classLoader!!.getResource("djvu/$name")!!.toURI()).readBytes()

    private fun open(name: String) = CleanRoomDjvuDocument(DjvuByteArraySource(fixture(name)))

    private fun rgbCrc(r: DjvuRaster): String {
        val rgb = ByteArray(r.argb.size * 3)
        for (i in r.argb.indices) {
            val c = r.argb[i]
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
        }
        val crc = CRC32()
        crc.update(rgb)
        return "%08x".format(crc.value)
    }

    private fun renderFull(name: String, page: Int = 0): DjvuRaster {
        val p = open(name).decodePage(page)
        return CleanRoomDjvuDocument.compose(p, 0, 0, p.info.width, p.info.height, 1)
    }

    private fun assertMatchesReference(name: String, width: Int, height: Int, crc: String, page: Int = 0) {
        val r = renderFull(name, page)
        assertEquals("$name width", width, r.width)
        assertEquals("$name height", height, r.height)
        assertEquals("$name pixels", crc, rgbCrc(r))
    }

    @Test
    fun bzzMatchesReferenceEncoder() {
        assertArrayEquals(fixture("bzz_src.txt"), DjvuBzz.decode(fixture("bzz_src.bzz")))
    }

    @Test
    fun jb2LosslessAndLossyMatchReference() {
        assertMatchesReference("bilevel.djvu", 1240, 1754, "7cda95f3")
        assertMatchesReference("bilevel_lossy.djvu", 1240, 1754, "5f183139")
        assertEquals(1364, open("bilevel_lossy.djvu").decodePage(0).mask!!.blitCount)
    }

    @Test
    fun iw44ColourGreyPartialAndOddSizesMatchReference() {
        assertMatchesReference("photo_partial.djvu", 1240, 1754, "c71f1bbd")
        assertMatchesReference("gray.djvu", 1240, 1754, "0d0cba60")
        assertMatchesReference("odd.djvu", 333, 517, "83312f3b")
    }

    @Test
    fun compoundPagesWithPaletteForegroundAndSubsampledLayersMatchReference() {
        assertMatchesReference("palette.djvu", 1240, 1754, "5477628c")
        assertMatchesReference("compound.djvu", 1240, 1754, "a9f3958b")
        assertMatchesReference("zones.djvu", 1240, 1754, "7fed6d90")
    }

    @Test
    fun bundledDocumentUsesDirectoryForPagesAndText() {
        val doc = open("bundle.djvu")
        assertEquals(4, doc.pageCount)
        assertMatchesReference("bundle.djvu", 1240, 1754, "7cda95f3", 0)
        assertMatchesReference("bundle.djvu", 1240, 1754, "4f69f1a2", 1)
        assertMatchesReference("bundle.djvu", 1240, 1754, "5477628c", 2)
        assertMatchesReference("bundle.djvu", 1240, 1754, "a9f3958b", 3)
        assertNotNull(doc.pageText(0))
        assertNull(doc.pageText(1))
    }

    @Test
    fun hiddenTextProducesLineBoxesInTopDownPixels() {
        val text = open("withtext.djvu").pageText(0)!!
        assertEquals(30, text.lines.size)
        val first = text.lines[0]
        assertEquals("forty jumps wizards Москва quick brown near fox seven", first.text)
        assertEquals(90, first.left)
        assertEquals(87, first.top)
        assertEquals(866, first.right)
        assertEquals(118, first.bottom)
        assertTrue(text.lines.zipWithNext().all { (a, b) -> b.top > a.top })
        assertTrue(text.text.startsWith("forty jumps wizards Москва"))
    }

    @Test
    fun rotationFlagIsReportedAndMatchesReferenceOrientation() {
        val doc = open("rotated.djvu")
        val info = doc.pageInfo(0)
        assertEquals(270, info.rotation)
        val r = renderFull("rotated.djvu")
        val w = r.width
        val h = r.height
        val rotated = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) rotated[(w - 1 - x) * h + y] = r.argb[y * w + x]
        assertEquals("0e8db073", rgbCrc(DjvuRaster(h, w, rotated)))
    }

    @Test
    fun subsampledRegionCompositionCoversRequestedArea() {
        val p = open("bilevel.djvu").decodePage(0)
        val full = CleanRoomDjvuDocument.compose(p, 0, 0, 1240, 1754, 1)
        val sub = CleanRoomDjvuDocument.compose(p, 0, 0, 1240, 1754, 4)
        assertEquals(310, sub.width)
        assertEquals(439, sub.height)
        val region = CleanRoomDjvuDocument.compose(p, 88, 76, 300, 130, 1)
        for (y in 0 until region.height) for (x in 0 until region.width) {
            assertEquals(full.argb[(76 + y) * 1240 + 88 + x], region.argb[y * region.width + x])
        }
        var dark = 0
        for (c in sub.argb) if ((c and 0xFF) < 200) dark++
        assertTrue(dark > 1000)
    }

    @Test
    fun corruptedInputFailsWithFormatExceptionInsteadOfCrashing() {
        val names = listOf("bilevel_lossy.djvu", "photo_partial.djvu", "palette.djvu", "compound.djvu", "withtext.djvu", "bundle.djvu")
        val rnd = Random(1234)
        for (name in names) {
            val original = fixture(name)
            repeat(40) {
                val bytes = original.copyOf()
                val start = 40 + rnd.nextInt(bytes.size - 40)
                repeat(1 + rnd.nextInt(16)) { k ->
                    val i = (start + k * 7) % bytes.size
                    if (i >= 32) bytes[i] = rnd.nextInt(256).toByte()
                }
                try {
                    val doc = CleanRoomDjvuDocument(DjvuByteArraySource(bytes))
                    for (pg in 0 until doc.pageCount) {
                        val page = doc.decodePage(pg)
                        CleanRoomDjvuDocument.compose(page, 0, 0, page.info.width, page.info.height, 4)
                        doc.pageText(pg)
                    }
                } catch (_: DjvuFormatException) {
                } catch (e: Throwable) {
                    fail("$name: unexpected ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun rejectsNonDjvuData() {
        try {
            CleanRoomDjvuDocument(DjvuByteArraySource("not a djvu file at all".toByteArray()))
            fail()
        } catch (_: DjvuFormatException) {
        }
    }
}
