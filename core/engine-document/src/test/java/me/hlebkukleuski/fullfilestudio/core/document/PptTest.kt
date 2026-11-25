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

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class PptTest {

    private val fixture: File = File(javaClass.classLoader!!.getResource("ppt/fixture.ppt")!!.toURI())

    private fun load(bytes: ByteArray = fixture.readBytes()): Pair<PptPresentation, PptSlideRenderer> {
        val cfb = Ole2CfbContainer(bytes)
        val pres = PptPresentation.parse(cfb.readStream("PowerPoint Document")!!, cfb.readStream("Current User"))
        val pictures = cfb.readStream("Pictures")
        val cache = HashMap<Int, PptImage?>()
        val renderer = PptSlideRenderer(pres) { pib ->
            cache.getOrPut(pib) {
                val ref = pres.blips.getOrNull(pib - 1)
                val data = ref?.embedded ?: pictures
                if (ref == null || data == null) null else PptBlipDecoder.decodeImage(data, if (ref.embedded != null) 0 else ref.offset, 2048)
            }
        }
        return pres to renderer
    }

    private fun render(pres: PptPresentation, renderer: PptSlideRenderer, index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bmp), index, 0f, 0f, 960f / pres.slideWidth)
        return bmp
    }

    private fun assertColor(bmp: Bitmap, x: Int, y: Int, expected: Int, tolerance: Int = 24) {
        val c = bmp.getPixel(x, y)
        val ok = Math.abs(Color.red(c) - Color.red(expected)) <= tolerance &&
            Math.abs(Color.green(c) - Color.green(expected)) <= tolerance &&
            Math.abs(Color.blue(c) - Color.blue(expected)) <= tolerance
        assertTrue("pixel ($x,$y) = ${Integer.toHexString(c)} expected ${Integer.toHexString(expected)}", ok)
    }

    @Test
    fun parsesStructureTextAndStyles() {
        val (pres, _) = load()
        assertEquals(5760, pres.slideWidth)
        assertEquals(4320, pres.slideHeight)
        assertEquals(4, pres.slides.size)
        assertEquals("Fixture Title Slide\nSubtitle line\n\n[Notes]\nSpeaker notes for slide one", pres.slideText(0))
        assertEquals("Bullet Levels\nLevel one alpha\nLevel two beta\nLevel three gamma\nLevel one delta", pres.slideText(1))
        assertEquals("Кириллица и Ελληνικά", pres.slideText(3))

        val body = pres.slides[1].shapes.first { it.placeholder == 19 }.text!!
        assertEquals(listOf(0, 1, 2, 0), body.paragraphs.map { it.level })
        assertTrue(body.paragraphs.all { it.hasBullet })
        assertEquals(listOf(216, 468, 720, 216), body.paragraphs.map { it.leftMargin })
        assertEquals(listOf(0, 288, 576, 0), body.paragraphs.map { it.indent })
        assertEquals(listOf(32f, 28f, 24f, 32f), body.paragraphs.map { it.runs.first().style.sizePt })

        val textBox = pres.slides[2].shapes.first { it.spt == 202 }.text!!
        val runs = textBox.paragraphs.single().runs
        assertEquals(listOf("Bold ", "Italic ", "Link"), runs.map { it.text })
        assertTrue(runs[0].style.bold)
        assertTrue(runs[1].style.italic)
        assertTrue(runs[2].style.underline)
        assertEquals(0x06 shl 24, runs[2].style.color)
        assertEquals(0xFE302010.toInt(), runs[0].style.color)
        assertEquals(1, textBox.paragraphs.single().align)
        assertEquals("Calibri", pres.fonts[runs[0].style.fontRef].name)

        val shapes = pres.slides[2].shapes
        assertEquals(listOf(1, 3, 5, 1, 0, 202), shapes.map { it.spt })
        assertTrue(shapes[4].isGroup)
        assertEquals(2, shapes[4].children.size)
        assertEquals(90f, shapes[3].props.int(0x0004, 0) / 65536f, 0.01f)
        assertEquals(listOf(288f, 288f, 2016f, 1440f), shapes[0].anchor!!.toList())
    }

    @Test
    fun rendersShapesPicturesAndGradients() {
        val (pres, renderer) = load()
        val shapes = render(pres, renderer, 2)
        assertColor(shapes, 250, 100, Color.RED)
        assertColor(shapes, 480, 144, Color.rgb(0, 128, 0))
        assertColor(shapes, 792, 216, Color.BLUE)
        assertColor(shapes, 192, 400, Color.rgb(0xFF, 0xC0, 0x00))
        assertColor(shapes, 100, 400, Color.WHITE)
        assertColor(shapes, 528, 432, Color.rgb(0x80, 0x00, 0x80))
        assertColor(shapes, 672, 528, Color.rgb(0x00, 0xC0, 0xC0))
        assertColor(shapes, 900, 650, Color.WHITE)

        val pictures = render(pres, renderer, 3)
        assertColor(pictures, 192, 192, Color.YELLOW)
        assertColor(pictures, 384, 192, Color.BLUE)
        val left = pictures.getPixel(534, 192)
        val right = pictures.getPixel(906, 192)
        assertTrue(Color.red(left) > 200 && Color.blue(left) < 60)
        assertTrue(Color.blue(right) > 200 && Color.red(right) < 60)
        val mid = pictures.getPixel(720, 192)
        assertTrue(Color.red(mid) > 60 && Color.blue(mid) > 60)
    }

    @Test
    fun sessionRendersAndSearches() {
        val session = PptDocumentSession(ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertNull(session.getLastError())
            assertEquals(4, session.pageCount)
            assertEquals(2L, session.search("Bold Italic", 0))
            assertEquals(3L, session.search("Ελληνικά", 0))
            assertEquals(-1L, session.search("absent words", 0))
            val bmp = Bitmap.createBitmap(964, 724, Bitmap.Config.ARGB_8888)
            session.renderTile(2 or (1 shl 30), 0, 0, 1000, bmp)
            assertEquals(4f / 3f, session.getDocumentWidth() / session.getDocumentHeight(), 0.01f)
            assertColor(bmp, 252, 102, Color.RED)
            assertNull(session.getLastError())
        } finally {
            session.close()
        }
    }

    @Test
    fun encryptedCurrentUserIsReportedAsPasswordRequired() {
        val bytes = fixture.readBytes()
        val token = byteArrayOf(0x5F, 0xC0.toByte(), 0x91.toByte(), 0xE3.toByte())
        var hits = 0
        var i = 0
        while (i + 4 <= bytes.size) {
            if (bytes[i] == token[0] && bytes[i + 1] == token[1] && bytes[i + 2] == token[2] && bytes[i + 3] == token[3]) {
                bytes[i] = 0xDF.toByte(); bytes[i + 1] = 0xC4.toByte(); bytes[i + 2] = 0xD1.toByte(); bytes[i + 3] = 0xF3.toByte()
                hits++
            }
            i++
        }
        assertEquals(1, hits)
        var thrown: Throwable? = null
        try {
            load(bytes)
        } catch (e: PptEncryptedException) {
            thrown = e
        }
        assertNotNull(thrown)
        val file = File.createTempFile("enc", ".ppt").apply { writeBytes(bytes); deleteOnExit() }
        val session = PptDocumentSession(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertTrue(session.getLastError().orEmpty().startsWith("PASSWORD_REQUIRED"))
        } finally {
            session.close()
        }
    }

    @Test
    fun corruptedInputNeverCrashes() {
        val original = fixture.readBytes()
        val cfb = Ole2CfbContainer(original)
        val doc = cfb.readStream("PowerPoint Document")!!
        val user = cfb.readStream("Current User")
        val rnd = Random(1234)
        repeat(300) { iteration ->
            val mutated = doc.copyOf()
            repeat(1 + rnd.nextInt(24)) {
                val p = rnd.nextInt(mutated.size)
                mutated[p] = when (rnd.nextInt(4)) {
                    0 -> 0
                    1 -> 0xFF.toByte()
                    else -> rnd.nextInt(256).toByte()
                }
            }
            val truncated = if (iteration % 7 == 0) mutated.copyOf(rnd.nextInt(mutated.size)) else mutated
            try {
                val pres = PptPresentation.parse(truncated, user)
                val renderer = PptSlideRenderer(pres) { null }
                for (s in pres.slides.indices) {
                    val bmp = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
                    renderer.draw(Canvas(bmp), s, 0f, 0f, 96f / pres.slideWidth)
                    pres.slideText(s)
                }
            } catch (_: PptFormatException) {
            } catch (_: PptEncryptedException) {
            }
        }
    }

    @Test
    fun bundledFontsAreMetricCompatibleWithOfficeFonts() {
        OfficeFonts.loader = { android.graphics.Typeface.createFromFile(File("src/main/assets/fonts/office/" + it)) }
        OfficeFonts.bytesLoader = { File("src/main/assets/fonts/office/" + it).takeIf { f -> f.isFile }?.readBytes() }
        OfficeFonts.clearCache()
        val paint = android.graphics.Paint().apply { textSize = 2048f }
        paint.typeface = OfficeFonts.typeface("Times New Roman", false, false)
        assertEquals(8643f, paint.measureText("abcdefghij"), 0.5f)
        paint.typeface = OfficeFonts.typeface("Arial", false, false)
        assertEquals(9337f, paint.measureText("abcdefghij"), 0.5f)
        assertNotNull(OfficeFonts.typeface("Calibri", true, true))
        assertNotNull(OfficeFonts.typeface("Cambria", false, true))
        assertNotNull(OfficeFonts.typeface("Courier New", true, false))
        assertNotNull(OfficeFonts.typeface("Book Antiqua", false, false))
        assertNotNull(OfficeFonts.typeface("Century Gothic", true, false))
        assertNotNull(OfficeFonts.typeface("Bookman Old Style", false, true))
        assertNotNull(OfficeFonts.typeface("Century Schoolbook", false, false))
        assertNotNull(OfficeFonts.typeface("Arial Narrow", false, false))
        assertNotNull(OfficeFonts.typeface("Georgia", false, false))
        assertNotNull(OfficeFonts.typeface("Garamond", false, false))
        assertNotNull(OfficeFonts.typeface("Comic Sans MS", false, false))
        assertNotNull(OfficeFonts.typeface("Segoe UI", false, false))
        assertNotNull(OfficeFonts.typeface("Verdana", false, false))
        assertNotNull(OfficeFonts.typeface("Tahoma", false, false))
        assertNotNull(OfficeFonts.typeface("Consolas", false, false))
        assertNull(OfficeFonts.typeface("NonExistentCustomFontXYZ", false, false))
        assertNotNull(OfficeFonts.resolve("Tahoma", true, false))
        val dir = File("src/main/assets/fonts/office")
        for (family in listOf("LiberationSans", "LiberationSerif", "LiberationMono", "Carlito", "Caladea", "Gelasio", "EBGaramond", "ComicNeue")) {
            for (style in listOf("Regular", "Bold", "Italic", "BoldItalic")) assertTrue("$family-$style.ttf", File(dir, "$family-$style.ttf").isFile)
        }
        for (prefix in listOf("texgyrepagella", "texgyreadventor", "texgyrebonum", "texgyreschola", "texgyreheroscn")) {
            for (style in listOf("regular", "bold", "italic", "bolditalic")) assertTrue("$prefix-$style.otf", File(dir, "$prefix-$style.otf").isFile)
        }
        assertTrue(File(dir, "LICENSE-Liberation.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "LICENSE-Carlito.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "LICENSE-Caladea.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "LICENSE-TeXGyre-GFL.txt").readText().contains("GUST Font License"))
        assertTrue(File(dir, "LICENSE-DejaVu.txt").readText().contains("Bitstream Vera"))
        assertTrue(File(dir, "OFL-Gelasio.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "OFL-EBGaramond.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "OFL-ComicNeue.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(dir, "OFL-Selawik.txt").readText().contains("SIL Open Font License"))
    }

    private class WmfWriter {
        private val body = ByteArrayOutputStream()
        private var maxRecord = 0

        fun record(fn: Int, vararg params: Int) {
            val size = 3 + params.size
            maxRecord = maxOf(maxRecord, size)
            val bb = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(size)
            bb.putShort(fn.toShort())
            params.forEach { bb.putShort(it.toShort()) }
            body.write(bb.array())
        }

        fun bytes(): ByteArray {
            record(0)
            val b = body.toByteArray()
            val hdr = ByteBuffer.allocate(22 + 18).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(0x9AC6CDD7.toInt()); hdr.putShort(0)
            hdr.putShort(0); hdr.putShort(0); hdr.putShort(100); hdr.putShort(100)
            hdr.putShort(1440); hdr.putInt(0); hdr.putShort(0)
            hdr.putShort(1); hdr.putShort(9); hdr.putShort(0x300)
            hdr.putInt((18 + b.size) / 2); hdr.putShort(4); hdr.putInt(maxRecord); hdr.putShort(0)
            return hdr.array() + b
        }
    }

    @Test
    fun wmfPlayerDrawsShapesWithMappedCoordinates() {
        val w = WmfWriter()
        w.record(0x020B, 0, 0)
        w.record(0x020C, 100, 100)
        w.record(0x02FC, 0, 0x00FF, 0x0000, 0)
        w.record(0x012D, 0)
        w.record(0x02FA, 5, 0, 0, 0, 0)
        w.record(0x012D, 1)
        w.record(0x041B, 50, 50, 10, 10)
        w.record(0x02FC, 0, 0x0000, 0x00FF, 0)
        w.record(0x012D, 2)
        w.record(0x0418, 90, 90, 60, 60)
        w.record(0x0324, 3, 60, 10, 90, 10, 75, 40)
        val img = MetafileRenderer.decode(w.bytes())
        assertNotNull(img)
        val bmp = img!!.rasterize(400)!!
        assertEquals(bmp.width, bmp.height)
        val s = bmp.width / 100f
        assertColor(bmp, (30 * s).toInt(), (30 * s).toInt(), Color.RED, 8)
        assertColor(bmp, (75 * s).toInt(), (75 * s).toInt(), Color.BLUE, 8)
        assertColor(bmp, (75 * s).toInt(), (18 * s).toInt(), Color.BLUE, 8)
        assertEquals(0, Color.alpha(bmp.getPixel((30 * s).toInt(), (75 * s).toInt())))
        assertEquals(0, Color.alpha(bmp.getPixel((62 * s).toInt(), (38 * s).toInt())))
    }

    @Test
    fun emfPlayerHonoursFrameAndStockObjects() {
        val records = ByteArrayOutputStream()
        fun rec(type: Int, vararg ints: Int) {
            val bb = ByteBuffer.allocate(8 + ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(type); bb.putInt(8 + ints.size * 4)
            ints.forEach { bb.putInt(it) }
            records.write(bb.array())
        }
        rec(39, 1, 0, 0x0000FF00.toInt() and 0xFFFFFF, 0)
        rec(37, 1)
        rec(37, 0x80000008.toInt())
        rec(43, 0, 0, 50, 100)
        rec(37, 0x80000004.toInt())
        rec(42, 60, 60, 90, 90)
        rec(14, 0, 0, 20)
        val body = records.toByteArray()
        val hdr = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(1); hdr.putInt(88)
        hdr.putInt(0); hdr.putInt(0); hdr.putInt(99); hdr.putInt(99)
        hdr.putInt(0); hdr.putInt(0); hdr.putInt(1000); hdr.putInt(1000)
        hdr.putInt(0x464D4520); hdr.putInt(0x10000); hdr.putInt(88 + body.size); hdr.putInt(8)
        hdr.putShort(2); hdr.putShort(0); hdr.putInt(0); hdr.putInt(0); hdr.putInt(0)
        hdr.putInt(1000); hdr.putInt(1000); hdr.putInt(100); hdr.putInt(100)
        val img = MetafileRenderer.decode(hdr.array() + body)
        assertNotNull(img)
        val bmp = img!!.rasterize(500)!!
        val s = bmp.width / 100f
        assertColor(bmp, (25 * s).toInt(), (50 * s).toInt(), Color.rgb(0, 255, 0), 8)
        assertColor(bmp, (75 * s).toInt(), (75 * s).toInt(), Color.BLACK, 8)
        assertEquals(0, Color.alpha(bmp.getPixel((75 * s).toInt(), (20 * s).toInt())))
    }
}
