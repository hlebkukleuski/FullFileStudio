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

package com.omnireader.core.engine.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgEngineTest {
    private val root: File by lazy { File(javaClass.classLoader!!.getResource("svg-reference")!!.toURI()) }
    private val fonts: SvgFontDb by lazy {
        SvgFontDb.fromFiles(listOf(File(root, "fonts/NotoSans-Regular.ttf")), "Noto Serif", "Noto Sans", "Yellowtail", "Sedgwick Ave Display", "Noto Mono", "Times New Roman")
    }

    private fun premul(p: Int, shift: Int): Int {
        val v = (p ushr shift) and 0xFF
        return if (shift == 24) v else (v * ((p ushr 24) and 0xFF) + 127) / 255
    }

    private fun badPixels(got: Bitmap, ref: Bitmap): Int {
        val w = ref.width; val h = ref.height
        val a = IntArray(w * h); val b = IntArray(w * h)
        got.getPixels(a, 0, w, 0, 0, w, h)
        ref.getPixels(b, 0, w, 0, 0, w, h)
        val shifts = intArrayOf(24, 16, 8, 0)
        var bad = 0
        for (i in a.indices) {
            var md = 0
            for (s in shifts) md = maxOf(md, Math.abs(premul(a[i], s) - premul(b[i], s)))
            if (md <= 24) continue
            val x0 = i % w; val y0 = i / w
            var inRange = true
            for (s in shifts) {
                var lo = 255; var hi = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val x = x0 + dx; val y = y0 + dy
                    if (x < 0 || y < 0 || x >= w || y >= h) continue
                    val v = premul(b[y * w + x], s)
                    lo = minOf(lo, v); hi = maxOf(hi, v)
                }
                val v = premul(a[i], s)
                if (v < lo - 8 || v > hi + 8) inRange = false
            }
            if (!inRange) bad++
        }
        return bad
    }

    private fun render(svg: String, w: Int, h: Int, text: SvgTextConverter? = SvgText(fonts)): Bitmap {
        val tree = SvgConverter.convert(SvgDoc.parse(svg), SvgOptions(textRenderer = text))!!
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        SvgRender.render(tree, Ts.scale(w / tree.width, h / tree.height), bmp)
        return bmp
    }

    @Test
    fun rendersResvgReferenceSubset() {
        val base = File(root, "tests")
        val files = base.walkTopDown().filter { it.isFile && it.extension == "svg" }.sortedBy { it.path }.toList()
        assertTrue(files.size >= 400)
        val text = SvgText(fonts)
        val failures = ArrayList<String>()
        for (f in files) {
            val name = f.relativeTo(base).path
            val ref = BitmapFactory.decodeFile(File(f.parentFile, f.nameWithoutExtension + ".png").path)
            assertNotNull(name, ref)
            val got = try {
                val doc = SvgDoc.parse(SvgDocumentSession.decodeText(f.readBytes()))
                val tree = SvgConverter.convert(doc, SvgOptions(textRenderer = text))
                Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888).also { b ->
                    if (tree != null) SvgRender.render(tree, Ts.scale(ref.width / tree.width, ref.height / tree.height), b)
                }
            } catch (e: Exception) {
                failures.add("$name: ${e.javaClass.simpleName} ${e.message}")
                continue
            }
            val ratio = badPixels(got, ref).toDouble() / (ref.width * ref.height)
            if (ratio > 0.002) failures.add("$name: %.4f".format(ratio))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun decodesDeclaredEncodingsAndByteOrderMarks() {
        val cp1251 = "<?xml version=\"1.0\" encoding=\"Windows-1251\"?><svg xmlns=\"http://www.w3.org/2000/svg\"><text>Привет</text></svg>"
        assertTrue(SvgDocumentSession.decodeText(cp1251.toByteArray(charset("windows-1251"))).contains("Привет"))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>Ünï</text></svg>".toByteArray(Charsets.UTF_16LE)
        val decoded16 = SvgDocumentSession.decodeText(utf16)
        assertTrue(decoded16.startsWith("<svg"))
        assertTrue(decoded16.contains("Ünï"))
        val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<svg/>".toByteArray()
        assertEquals("<svg/>", SvgDocumentSession.decodeText(bom8))
    }

    @Test
    fun inflatesSvgzAndRejectsDecompressionBombs() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".toByteArray()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(svg) } }.toByteArray()
        assertTrue(SvgDocumentSession.gunzipIfNeeded(gz, 1 shl 20).contentEquals(svg))
        assertTrue(SvgDocumentSession.gunzipIfNeeded(svg, 1 shl 20).contentEquals(svg))
        val bomb = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(ByteArray(4 shl 20)) } }.toByteArray()
        var thrown = false
        try { SvgDocumentSession.gunzipIfNeeded(bomb, 1 shl 20) } catch (e: SecurityException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun detectsEmbeddedSvgPayloads() {
        assertTrue(SvgDocumentSession.looksLikeSvg("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertTrue(SvgDocumentSession.looksLikeSvg("﻿<?xml version=\"1.0\"?>\n<!-- c -->\n<!DOCTYPE svg>\n<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()) } }.toByteArray()
        assertTrue(SvgDocumentSession.looksLikeSvg(gz))
        assertFalse(SvgDocumentSession.looksLikeSvg("<?xml version=\"1.0\"?><root><svgish/></root>".toByteArray()))
        assertFalse(SvgDocumentSession.looksLikeSvg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    @Test
    fun rasterizesEmbeddedSvgWithAspectRatioAndTransparency() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\"><rect x=\"50\" y=\"25\" width=\"100\" height=\"50\" fill=\"#ff0000\"/></svg>"
        val bmp = SvgDocumentSession.rasterize(svg.toByteArray(), 400)!!
        assertEquals(400, bmp.width)
        assertEquals(200, bmp.height)
        assertEquals(Color.RED, bmp.getPixel(200, 100))
        assertEquals(0, Color.alpha(bmp.getPixel(10, 10)))
        assertNull(SvgDocumentSession.rasterize("<html/>".toByteArray(), 400))
    }

    @Test
    fun requestsAndAppliesExternalImageResources() {
        val png = ByteArrayOutputStream().also { o ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, o)
        }.toByteArray()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"20\" height=\"20\">" +
            "<image xlink:href=\"./img/pic%20one.png\" width=\"20\" height=\"20\"/>" +
            "<image href=\"https://example.com/remote.png\" width=\"20\" height=\"20\"/></svg>"
        val model = SvgDocumentModel(svg, null)
        model.build()
        assertEquals(listOf("img/pic one.png"), model.missingResources)
        assertFalse(model.provide("other.png", png))
        assertTrue(model.provide("pic one.png", png))
        model.build()
        assertTrue(model.missingResources.isEmpty())
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        SvgRender.render(model.tree!!, Ts.IDENTITY, bmp)
        assertEquals(Color.BLUE, bmp.getPixel(10, 10))
    }

    @Test
    fun extractsSearchableText() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\" y=\"10\">Hello <tspan>world</tspan></text><g><text>Second   line</text></g><title>skip</title></svg>"
        val model = SvgDocumentModel(svg, null)
        model.build()
        assertEquals("Hello world\nSecond line", model.plainText)
        assertNull(SvgDocumentModel("<svg", null).also { it.build() }.tree)
        assertNotNull(SvgDocumentModel("<svg", null).also { it.build() }.error)
    }

    @Test
    fun shapesContextualScriptsAndKeepsClusterPositions() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"60\" font-family=\"Noto Sans\" font-size=\"40\">" +
            "<text x=\"10 60 110\" y=\"45\" fill=\"#000\">AVA</text></svg>"
        val bmp = render(svg, 200, 60)
        fun columnInk(x0: Int, x1: Int): Int { var n = 0; for (x in x0 until x1) for (y in 0 until 60) if (Color.alpha(bmp.getPixel(x, y)) > 128) n++; return n }
        assertTrue(columnInk(10, 40) > 50)
        assertTrue(columnInk(60, 90) > 50)
        assertTrue(columnInk(110, 140) > 50)
        assertEquals(0, columnInk(150, 200))
    }

    @Test
    fun textBoundingBoxDrivesObjectBoundingBoxGradients() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"100\" font-family=\"Noto Sans\" font-size=\"80\">" +
            "<linearGradient id=\"g\"><stop offset=\"0.5\" stop-color=\"#f00\"/><stop offset=\"0.5\" stop-color=\"#00f\"/></linearGradient>" +
            "<text x=\"10\" y=\"80\" fill=\"url(#g)\">IIIIIIIII</text></svg>"
        val bmp = render(svg, 300, 100)
        var red = 0; var blue = 0
        for (x in 0 until 300) for (y in 0 until 100) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 250) continue
            if (Color.red(p) > 200 && Color.blue(p) < 50) { red++; assertTrue(x < 160) }
            if (Color.blue(p) > 200 && Color.red(p) < 50) { blue++; assertTrue(x > 60) }
        }
        assertTrue(red > 100)
        assertTrue(blue > 100)
    }
}
