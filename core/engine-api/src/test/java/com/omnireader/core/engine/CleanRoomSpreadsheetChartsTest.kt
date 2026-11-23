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
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import com.omnireader.core.engine.impl.BiffChartParser
import com.omnireader.core.engine.impl.ChartCellResolver
import com.omnireader.core.engine.impl.ChartReferenceData
import com.omnireader.core.engine.impl.CleanRoomBiff8XlsSession
import com.omnireader.core.engine.impl.CleanRoomCharts
import com.omnireader.core.engine.impl.DmlAssets
import com.omnireader.core.engine.impl.DmlCellAnchor
import com.omnireader.core.engine.impl.DmlColorContext
import com.omnireader.core.engine.impl.DmlColors
import com.omnireader.core.engine.impl.DmlDrawings
import com.omnireader.core.engine.impl.DmlNode
import com.omnireader.core.engine.impl.DmlPackage
import com.omnireader.core.engine.impl.DmlPresentation
import com.omnireader.core.engine.impl.DmlSlideRenderer
import com.omnireader.core.engine.impl.OfficeFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CleanRoomSpreadsheetChartsTest {

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Before
    fun assets() {
        OfficeFonts.loader = { Typeface.createFromFile(File("src/main/assets/fonts/office/$it")) }
        DmlAssets.loader = { File("src/main/assets/$it").readBytes() }
    }

    private fun count(bmp: Bitmap, rgb: Int, tolerance: Int = 6): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var n = 0
        for (c in px) {
            if (Math.abs(Color.red(c) - Color.red(rgb)) <= tolerance &&
                Math.abs(Color.green(c) - Color.green(rgb)) <= tolerance &&
                Math.abs(Color.blue(c) - Color.blue(rgb)) <= tolerance
            ) n++
        }
        return n
    }

    private fun xlsxPackage(): DmlPackage {
        val parts = HashMap<String, ByteArray>()
        ZipFile(resource("xls/charts.xlsx")).use { z -> for (e in z.entries()) if (!e.isDirectory) parts[e.name] = z.getInputStream(e).readBytes() }
        return DmlPackage(parts)
    }

    private fun sheetResolver(pkg: DmlPackage, sheetPath: String): ChartCellResolver {
        val cells = HashMap<String, Any>()
        for (c in pkg.xml(sheetPath)!!.descendants("c")) {
            val ref = c.attr("r") ?: continue
            val v: Any? = when (c.attr("t")) {
                "inlineStr" -> c.child("is")?.allText()
                "str" -> c.child("v")?.text
                else -> c.child("v")?.text?.toDoubleOrNull()
            }
            if (v != null) cells[ref] = v
        }
        return ChartCellResolver { _, row, col ->
            var name = ""
            var n = col + 1
            while (n > 0) { name = ('A' + (n - 1) % 26) + name; n = (n - 1) / 26 }
            cells[name + (row + 1)]
        }
    }

    @Test
    fun biffChartsUseWorkbookThemeAndStyles() {
        val s = CleanRoomBiff8XlsSession(ParcelFileDescriptor.open(resource("xls/charts.xls"), ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertNull(s.getLastError())
            assertEquals(listOf("Data"), s.getSheetNames())
            assertTrue(s.getDocumentHeight() > 50 * 48f)
            val bmp = Bitmap.createBitmap(4800, 3000, Bitmap.Config.ARGB_8888)
            s.renderTile(1 shl 30, 0, 0, 700, bmp)
            val accent1 = count(bmp, Color.rgb(0x4F, 0x81, 0xBD))
            val accent2 = count(bmp, Color.rgb(0xC0, 0x50, 0x4D))
            val accent3 = count(bmp, Color.rgb(0x9B, 0xBB, 0x59))
            assertTrue("accent1=$accent1", accent1 > 20_000)
            assertTrue("accent2=$accent2", accent2 > 20_000)
            assertTrue("accent3=$accent3", accent3 > 20_000)
            assertEquals(0, count(bmp, Color.rgb(0x66, 0x66, 0x99), 2))
            assertEquals(0, count(bmp, Color.rgb(0x99, 0x33, 0x66), 2))
        } finally {
            s.close()
        }
    }

    @Test
    fun biffThemeRecord() {
        val t = BiffChartParser.theme(124226, ByteArray(0))
        assertNotNull(t)
        assertEquals(Color.rgb(0x4F, 0x81, 0xBD), t!!.colors["accent1"])
        assertNull(BiffChartParser.theme(0, ByteArray(32) { 7 }))
    }

    @Test
    fun chartStylePalettes() {
        val ctx = DmlColorContext(BiffChartParser.theme(124226, ByteArray(0))!!, emptyMap())
        val colorful = CleanRoomCharts.stylePalette(ctx, 2, 3)
        assertEquals(Color.rgb(0x4F, 0x81, 0xBD), colorful[0])
        assertEquals(Color.rgb(0xC0, 0x50, 0x4D), colorful[1])
        val mono = CleanRoomCharts.stylePalette(ctx, 13, 2)
        assertEquals(2, mono.size)
        val base = DmlColors.rgbToHsl(Color.rgb(0x9B, 0xBB, 0x59))
        val dark = DmlColors.rgbToHsl(mono[0])
        val light = DmlColors.rgbToHsl(mono[1])
        assertEquals(base[0], dark[0], 3f)
        assertEquals(base[0], light[0], 3f)
        assertTrue(dark[2] < base[2] && light[2] > base[2])
        val gray = CleanRoomCharts.stylePalette(ctx, 1, 3)
        assertTrue(gray.all { Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) })
    }

    @Test
    fun referenceDataFillsMissingCaches() {
        val xml = """<chartSpace><chart><plotArea><barChart><ser>
            <tx><strRef><f>'My Sheet'!${'$'}B${'$'}1</f></strRef></tx>
            <cat><numRef><f>('My Sheet'!${'$'}A${'$'}2,'My Sheet'!${'$'}A${'$'}4:${'$'}A${'$'}5)</f></numRef></cat>
            <val><numRef><f>'My Sheet'!${'$'}B${'$'}2:${'$'}B${'$'}5</f><numCache><ptCount val="0"/></numCache></numRef></val>
            </ser></barChart></plotArea></chart></chartSpace>"""
        val space = DmlNode.parse(xml.toByteArray())!!
        val seen = HashSet<String?>()
        ChartReferenceData.fill(space) { sheet, row, col ->
            seen.add(sheet)
            when (col) {
                0 -> listOf("h", "Jan", "Feb", "Mar", "Apr")[row]
                1 -> if (row == 0) "Name" else if (row == 2) "n/a" else row * 10.0
                else -> null
            }
        }
        assertEquals(setOf<String?>("My Sheet"), seen)
        val ser = space.descendants("ser").single()
        assertEquals(listOf("Name"), ser.path("tx", "strRef", "strCache")!!.all("pt").map { it.child("v")!!.text })
        assertEquals(listOf("Jan", "Mar", "Apr"), ser.path("cat", "numRef", "numCache")!!.all("pt").map { it.child("v")!!.text })
        val vals = ser.path("val", "numRef", "numCache")!!
        assertEquals("4", vals.child("ptCount")!!.attr("val"))
        assertEquals(listOf("0" to "10", "2" to "30", "3" to "40"), vals.all("pt").map { it.attr("idx")!! to it.child("v")!!.text })
        val model = CleanRoomCharts.parseDrawingMl(space, DmlColorContext(BiffChartParser.theme(124226, ByteArray(0))!!, emptyMap()))!!
        assertEquals(listOf(10.0, null, 30.0, 40.0), model.groups.single().series.single().values)
    }

    @Test
    fun xlsxDrawingAnchorsAndChartRendering() {
        val pkg = xlsxPackage()
        val anchors = DmlDrawings.forSheet(pkg, "xl/worksheets/sheet1.xml")
        assertEquals(8, anchors.size)
        assertTrue(anchors.all { it.kind == DmlCellAnchor.ONE_CELL && it.element.name == "graphicFrame" })
        assertEquals(listOf(5, 13), anchors.map { it.fromCol }.distinct().sorted())
        assertEquals(listOf(0, 15, 30, 45), anchors.map { it.fromRow }.distinct().sorted())
        assertTrue(anchors.all { it.cx > 0 && it.cy > 0 })

        val pres = DmlPresentation(pkg)
        pres.chartCells = sheetResolver(pkg, "xl/worksheets/sheet1.xml")
        pres.spreadsheet = true
        val renderer = DmlSlideRenderer(pres)
        val pxPerEmu = 2f / 12700f
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val first = anchors.first { it.fromRow == 0 && it.fromCol == 5 }
        renderer.drawAnchored(canvas, first.element, first.drawingPath, RectF(0f, 0f, first.cx * pxPerEmu, first.cy * pxPerEmu), pxPerEmu)
        assertTrue(count(bmp, Color.rgb(0x4F, 0x81, 0xBD)) > 3000)
        assertTrue(count(bmp, Color.rgb(0xC0, 0x50, 0x4D)) > 2000)
        assertTrue(count(bmp, Color.rgb(0x9B, 0xBB, 0x59)) > 1000)
        assertEquals(Color.WHITE, bmp.getPixel((first.cx * pxPerEmu / 2).toInt(), 6))
        assertEquals(Color.BLACK, bmp.getPixel((first.cx * pxPerEmu).toInt() + 20, 20))
        val chartPath = pkg.relTarget(first.drawingPath, first.element.path("graphic", "graphicData", "chart")!!.attr("r:id"))!!
        val cats = pkg.xml(chartPath)!!.descendants("cat").first().descendants("v").map { it.text }
        assertEquals(listOf("Jan", "Feb", "Mar", "Apr", "May"), cats)
    }
}
