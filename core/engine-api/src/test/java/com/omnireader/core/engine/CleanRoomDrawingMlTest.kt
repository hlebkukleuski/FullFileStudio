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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.omnireader.core.engine.impl.ChartAxis
import com.omnireader.core.engine.impl.ChartGroup
import com.omnireader.core.engine.impl.ChartModel
import com.omnireader.core.engine.impl.ChartSeries
import com.omnireader.core.engine.impl.CleanRoomCharts
import com.omnireader.core.engine.impl.DmlAssets
import com.omnireader.core.engine.impl.DmlColorContext
import com.omnireader.core.engine.impl.DmlGeometry
import com.omnireader.core.engine.impl.DmlNode
import com.omnireader.core.engine.impl.DmlPackage
import com.omnireader.core.engine.impl.DmlPresentation
import com.omnireader.core.engine.impl.DmlSlideRenderer
import com.omnireader.core.engine.impl.DmlTheme
import com.omnireader.core.engine.impl.OfficeFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CleanRoomDrawingMlTest {

    private val fixture = File(javaClass.classLoader!!.getResource("pptx/fixture.pptx")!!.toURI())

    @Before
    fun assets() {
        OfficeFonts.loader = { Typeface.createFromFile(File("src/main/assets/fonts/office/$it")) }
        DmlAssets.loader = { File("src/main/assets/$it").readBytes() }
    }

    private fun parts(bytes: ByteArray? = null): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val f = if (bytes == null) fixture else File.createTempFile("fx", ".pptx").apply { writeBytes(bytes); deleteOnExit() }
        ZipFile(f).use { z -> for (e in z.entries()) if (!e.isDirectory) out[e.name] = z.getInputStream(e).readBytes() }
        return out
    }

    private fun render(pres: DmlPresentation, index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        DmlSlideRenderer(pres).draw(Canvas(bmp), index, 0f, 0f, 960f / pres.slideWidth)
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
    fun parsesPresentationTextAndNotes() {
        val pres = DmlPresentation(DmlPackage(parts()))
        assertEquals(9144000L, pres.slideWidth)
        assertEquals(6858000L, pres.slideHeight)
        assertEquals(4, pres.slides.size)
        assertEquals("Fixture Title Slide\nSubtitle line", pres.slideText(0))
        assertEquals("Speaker notes for slide one", pres.notesText(pres.slides[0]))
        assertEquals("Bullet Levels\nLevel one alpha\nLevel two beta\nLevel three gamma\nLevel one delta", pres.slideText(1))
        assertEquals("Кириллица и Ελληνικά", pres.slideText(3))
    }

    @Test
    fun rendersShapesGroupsPicturesAndGradients() {
        val pres = DmlPresentation(DmlPackage(parts()))
        val shapes = render(pres, 2)
        assertColor(shapes, 250, 100, Color.RED)
        assertColor(shapes, 480, 144, Color.rgb(0, 128, 0))
        assertColor(shapes, 792, 216, Color.BLUE)
        assertColor(shapes, 192, 400, Color.rgb(0xFF, 0xC0, 0x00))
        assertColor(shapes, 100, 400, Color.WHITE)
        assertColor(shapes, 528, 432, Color.rgb(0x80, 0x00, 0x80))
        assertColor(shapes, 672, 528, Color.rgb(0x00, 0xC0, 0xC0))
        val pics = render(pres, 3)
        assertColor(pics, 192, 192, Color.YELLOW)
        assertColor(pics, 384, 192, Color.BLUE)
        assertTrue(Color.red(pics.getPixel(534, 192)) > 200 && Color.blue(pics.getPixel(534, 192)) < 60)
        assertTrue(Color.blue(pics.getPixel(906, 192)) > 200 && Color.red(pics.getPixel(906, 192)) < 60)
        val title = render(pres, 0)
        var dark = 0
        for (y in 200..300) for (x in 100..860) if (Color.red(title.getPixel(x, y)) < 80) dark++
        assertTrue("title text drawn", dark > 500)
    }

    @Test
    fun presetGeometryFormulasMatchSpec() {
        val tri = DmlGeometry.build("triangle", null, null, 200f, 100f)
        val b = RectF()
        tri.paths.first().path.computeBounds(b, true)
        assertEquals(RectF(0f, 0f, 200f, 100f), b)
        assertEquals(RectF(50f, 50f, 150f, 100f), tri.textRect)
        val rr = DmlGeometry.build("roundRect", null, DmlNode("avLst", emptyMap(), mutableListOf(DmlNode("gd", mapOf("name" to "adj", "fmla" to "val 50000")))), 200f, 100f)
        val p = Path(rr.paths.first().path)
        val probe = Path().apply { addRect(0f, 0f, 10f, 10f, Path.Direction.CW) }
        assertTrue(p.op(probe, Path.Op.INTERSECT))
        val r = RectF()
        p.computeBounds(r, true)
        assertTrue("corner is rounded away", r.isEmpty || r.width() < 10f)
        assertEquals(30.0, DmlGeometry.eval("*/ w 3 10", mapOf("w" to 100.0)), 1e-9)
        assertEquals(5400000.0, DmlGeometry.eval("at2 0 10", emptyMap()), 1e-3)
        assertEquals(7.0, DmlGeometry.eval("pin 0 7 10", emptyMap()), 1e-9)
        assertTrue(DmlGeometry.hasPreset("rightArrow") && DmlGeometry.hasPreset("wedgeRoundRectCallout"))
    }

    @Test
    fun colorTransformsFollowDrawingMl() {
        val theme = DmlTheme(null)
        val ctx = DmlColorContext(theme, mapOf("bg1" to "lt1", "tx1" to "dk1"))
        fun c(xml: String) = ctx.color(DmlNode.parse(xml.toByteArray())!!)!!
        assertEquals(Color.rgb(0x44, 0x72, 0xC4), c("<a:schemeClr xmlns:a='a' val='accent1'/>"))
        assertEquals(Color.rgb(0x80, 0x80, 0x80), c("<a:srgbClr xmlns:a='a' val='FFFFFF'><a:lumMod val='50196'/></a:srgbClr>"))
        assertEquals(0x80, Color.alpha(c("<a:srgbClr xmlns:a='a' val='FF0000'><a:alpha val='50000'/></a:srgbClr>")) + 0)
        assertEquals(Color.WHITE, c("<a:schemeClr xmlns:a='a' val='bg1'/>"))
        assertEquals(Color.rgb(0xFA, 0x80, 0x72), c("<a:prstClr xmlns:a='a' val='salmon'/>"))
    }

    @Test
    fun chartRendererDrawsBarsAndPie() {
        val bars = ChartModel(
            title = "T", titleSizePt = 14f, titleBold = false,
            groups = listOf(ChartGroup("bar", "clustered", listOf(ChartSeries("S", listOf("a", "b"), listOf(1.0, 2.0), fillColor = Color.RED)))),
            catAxis = ChartAxis(), valAxis = ChartAxis(majorGrid = true), legendPos = null,
            palette = CleanRoomCharts.defaultPalette(null)
        )
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        CleanRoomCharts.draw(Canvas(bmp), bars, RectF(0f, 0f, 400f, 300f), 1.33f)
        var red = 0
        for (y in 0 until 300) for (x in 0 until 400) if (bmp.getPixel(x, y) == Color.RED) red++
        assertTrue("bars drawn", red > 2000)
        val pie = ChartModel(null, 14f, false, listOf(ChartGroup("pie", "standard", listOf(ChartSeries("P", listOf("x", "y"), listOf(1.0, 1.0))), varyColors = true)),
            ChartAxis(), ChartAxis(), secValAxis = null, legendPos = null, palette = intArrayOf(Color.GREEN, Color.BLUE))
        bmp.eraseColor(Color.WHITE)
        CleanRoomCharts.draw(Canvas(bmp), pie, RectF(0f, 0f, 400f, 300f), 1.33f)
        assertColor(bmp, 240, 150, Color.GREEN, 10)
        assertColor(bmp, 160, 150, Color.BLUE, 10)
        assertEquals("1,234.50", CleanRoomCharts.formatNumber(1234.5, "#,##0.00"))
        assertEquals("25%", CleanRoomCharts.formatNumber(0.25, "0%"))
    }

    @Test
    fun corruptedPackagesNeverCrash() {
        val base = parts()
        val rnd = Random(77)
        val keys = base.keys.filter { it.endsWith(".xml") }.sorted()
        repeat(120) {
            val mutated = HashMap(base)
            repeat(1 + rnd.nextInt(4)) {
                val k = keys[rnd.nextInt(keys.size)]
                val b = mutated[k]?.copyOf() ?: return@repeat
                when (rnd.nextInt(3)) {
                    0 -> mutated[k] = b.copyOf(rnd.nextInt(b.size))
                    1 -> { repeat(8) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }; mutated[k] = b }
                    else -> mutated.remove(k)
                }
            }
            val pres = DmlPresentation(DmlPackage(mutated))
            val r = DmlSlideRenderer(pres)
            for (i in pres.slides.indices) {
                val bmp = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
                r.draw(Canvas(bmp), i, 0f, 0f, 96f / pres.slideWidth)
                pres.slideText(i)
                pres.notesText(pres.slides[i])
            }
        }
    }
}
