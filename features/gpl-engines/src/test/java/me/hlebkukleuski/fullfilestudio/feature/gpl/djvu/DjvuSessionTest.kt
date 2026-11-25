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

package me.hlebkukleuski.fullfilestudio.feature.gpl.djvu

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import java.io.File
import me.hlebkukleuski.fullfilestudio.feature.gpl.DjvuDocumentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DjvuSessionTest {

    private fun session(name: String): DjvuDocumentSession {
        val file = File(javaClass.classLoader!!.getResource("djvu/$name")!!.toURI())
        return DjvuDocumentSession(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }

    private val boxRegex = Regex("""^\[PDF_BOX:([0-9.]+),([0-9.]+),([0-9.]+),([0-9.]+)](.*)$""")

    @Test
    fun bundleExposesPagesTextBoxesAndSearch() {
        session("bundle.djvu").use { s ->
            assertNull(s.getLastError())
            assertEquals(4, s.pageCount)
            val lines = s.getPageText(0).lines().filter { it.isNotBlank() }
            assertEquals(30, lines.size)
            val m = boxRegex.matchEntire(lines[0])!!
            assertEquals(90f / 1240f, m.groupValues[1].toFloat(), 0.001f)
            assertEquals(87f / 1754f, m.groupValues[2].toFloat(), 0.001f)
            assertEquals(866f / 1240f, m.groupValues[3].toFloat(), 0.001f)
            assertEquals(118f / 1754f, m.groupValues[4].toFloat(), 0.001f)
            assertEquals("forty jumps wizards Москва quick brown near fox seven", m.groupValues[5])
            assertEquals("", s.getPageText(1))
            assertTrue(s.getPageText(-1).contains("Москва"))
            assertEquals(0L, s.search("москва", 2))
            assertEquals(-1L, s.search("nonexistent phrase", 0))
            assertEquals(1240f / 1754f, s.getPageAspectRatio(1), 0.001f)
        }
    }

    @Test
    fun rendersPageLikePdfGeometry() {
        session("bilevel.djvu").use { s ->
            val bmp = Bitmap.createBitmap(620, 877, Bitmap.Config.ARGB_8888)
            s.renderTile(0, 0, 0, 1000, bmp)
            assertEquals(1240f, s.getDocumentWidth())
            assertEquals(1754f, s.getDocumentHeight())
            assertEquals(Color.WHITE, bmp.getPixel(20, 20))
            var dark = 0
            for (y in 40 until 60) for (x in 40 until 440) if (Color.red(bmp.getPixel(x, y)) < 128) dark++
            assertTrue("text ink expected in first line", dark > 200)
            var barDark = 0
            for (x in 60 until 560) if (Color.red(bmp.getPixel(x, 853)) < 128) barDark++
            assertTrue("rule at bottom expected", barDark > 450)
            val zoomed = Bitmap.createBitmap(620, 877, Bitmap.Config.ARGB_8888)
            s.renderTile(0, -255, -393, 3000, zoomed)
            var zoomDark = 0
            for (y in 0 until 877) for (x in 0 until 620) if (Color.red(zoomed.getPixel(x, y)) < 128) zoomDark++
            assertTrue(zoomDark > 1000)
        }
    }

    @Test
    fun rotatedPageSwapsDimensionsAndRotatesBoxes() {
        session("rotated.djvu").use { s ->
            assertEquals(1754f / 1240f, s.getPageAspectRatio(0), 0.001f)
            val bmp = Bitmap.createBitmap(877, 620, Bitmap.Config.ARGB_8888)
            s.renderTile(0, 0, 0, 1000, bmp)
            assertEquals(1754f, s.getDocumentWidth())
            assertEquals(1240f, s.getDocumentHeight())
            val m = boxRegex.matchEntire(s.getPageText(0).lines().first())!!
            val l = m.groupValues[1].toFloat()
            val t = m.groupValues[2].toFloat()
            val r = m.groupValues[3].toFloat()
            val b = m.groupValues[4].toFloat()
            assertEquals(87f / 1754f, l, 0.001f)
            assertEquals(1f - 866f / 1240f, t, 0.001f)
            assertEquals(118f / 1754f, r, 0.001f)
            assertEquals(1f - 90f / 1240f, b, 0.001f)
            var ink = 0
            val x0 = (l * 877).toInt()
            val x1 = (r * 877).toInt()
            for (x in x0..x1) for (y in (t * 620).toInt()..(b * 620).toInt()) if (Color.red(bmp.getPixel(x, y)) < 128) ink++
            assertTrue("rotated first line must be inside its box", ink > 300)
        }
    }

    @Test
    fun invalidFileReportsError() {
        val tmp = File.createTempFile("bad", ".djvu")
        tmp.writeBytes("AT&TFORM\u0000\u0000\u0000\u0004XXXX".toByteArray(Charsets.ISO_8859_1))
        DjvuDocumentSession(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)).use { s ->
            assertTrue(s.getLastError()!!.contains("DjVu"))
            val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            s.renderTile(0, 0, 0, 1000, bmp)
            assertEquals(1, s.pageCount)
        }
        tmp.delete()
    }
}
