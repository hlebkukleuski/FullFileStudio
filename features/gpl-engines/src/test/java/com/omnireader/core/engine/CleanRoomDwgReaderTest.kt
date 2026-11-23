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

import com.omnireader.core.engine.impl.CleanRoomDwgContainer
import com.omnireader.core.engine.impl.CleanRoomDwgReader
import com.omnireader.core.engine.impl.DwgBitReader
import com.omnireader.core.engine.impl.DwgFormatException
import com.omnireader.core.engine.impl.DwgGeometrySink
import com.omnireader.core.engine.impl.DwgVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class CleanRoomDwgReaderTest {

    private class BitWriter {
        private val bytes = ByteArrayOutputStream()
        private var cur = 0
        var bitCount = 0L
            private set

        fun b(v: Int) {
            cur = (cur shl 1) or (v and 1)
            bitCount++
            if (bitCount % 8 == 0L) { bytes.write(cur); cur = 0 }
        }
        fun bits(v: Int, n: Int) { for (i in n - 1 downTo 0) b((v shr i) and 1) }
        fun rc(v: Int) = bits(v and 0xFF, 8)
        fun rs(v: Int) { rc(v); rc(v shr 8) }
        fun rl(v: Int) { rs(v); rs(v shr 16) }
        fun rd(v: Double) { val l = java.lang.Double.doubleToRawLongBits(v); for (i in 0 until 8) rc((l shr (8 * i)).toInt()) }
        fun bs(v: Int) = when {
            v == 0 -> bits(2, 2)
            v == 256 -> bits(3, 2)
            v in 1..255 -> { bits(1, 2); rc(v) }
            else -> { bits(0, 2); rs(v) }
        }
        fun bl(v: Int) = when {
            v == 0 -> bits(2, 2)
            v in 1..255 -> { bits(1, 2); rc(v) }
            else -> { bits(0, 2); rl(v) }
        }
        fun bd(v: Double) = when (v) {
            1.0 -> bits(1, 2)
            0.0 -> bits(2, 2)
            else -> { bits(0, 2); rd(v) }
        }
        fun ddFull(v: Double) { bits(3, 2); rd(v) }
        fun threeBd(x: Double, y: Double, z: Double) { bd(x); bd(y); bd(z) }
        fun h(code: Int, value: Long) {
            var n = 0
            var t = value
            while (t != 0L) { n++; t = t ushr 8 }
            bits(code, 4); bits(n, 4)
            for (i in n - 1 downTo 0) rc((value shr (8 * i)).toInt())
        }
        fun t(s: String) { val raw = s.toByteArray(Charsets.ISO_8859_1); bs(raw.size); raw.forEach { rc(it.toInt()) } }
        fun pad() { while (bitCount % 8 != 0L) b(0) }
        fun toBytes(): ByteArray { pad(); return bytes.toByteArray() }
    }

    private class Obj(val handle: Long, val bytes: ByteArray)

    private fun obj(handle: Long, type: Int, isEntity: Boolean, entmode: Int = 2, owner: Long = 0, layer: Long = 0x10, color: Int = 256,
                    body: (BitWriter) -> Unit, handles: (BitWriter) -> Unit = {}): Obj {
        fun build(bitSize: Int): Pair<ByteArray, Int> {
            val w = BitWriter()
            w.bs(type)
            w.rl(bitSize)
            w.h(0, handle)
            w.bs(0)
            if (isEntity) {
                w.b(0)
                w.bits(entmode, 2)
                w.bl(0)
                w.b(1)
                w.bs(color)
                w.bd(1.0)
                w.bits(0, 2)
                w.bits(0, 2)
                w.bs(0)
                w.rc(0)
            } else {
                w.bl(0)
            }
            body(w)
            val dataBits = w.bitCount.toInt()
            if (isEntity) {
                if (entmode == 0) w.h(4, owner)
                w.h(3, 0)
                w.h(5, layer)
            } else {
                w.h(4, owner)
                w.h(3, 0)
            }
            handles(w)
            return Pair(w.toBytes(), dataBits)
        }
        var (data, bits) = build(0)
        val second = build(bits)
        data = second.first
        val out = ByteArrayOutputStream()
        out.write(data.size and 0xFF)
        out.write((data.size shr 8) and 0x7F)
        if (data.size > 0x7FFF) throw IllegalStateException("object too large for test writer")
        out.write(data)
        out.write(0); out.write(0)
        return Obj(handle, out.toByteArray())
    }

    private fun mc(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v shr 7
            if (v == 0L && (b and 0x40) == 0) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private fun buildDwg(objects: List<Obj>, classes: List<String> = emptyList()): ByteArray {
        val header = ByteArray(0x19 + 3 * 9 + 2 + 16)
        "AC1015".toByteArray().copyInto(header)
        header[0x13] = 30
        header[0x15] = 3
        val body = ByteArrayOutputStream()
        val base = header.size
        val classesOffset = base
        body.write(byteArrayOf(0x8D.toByte(), 0xA1.toByte(), 0xC4.toByte(), 0xB8.toByte(), 0xC4.toByte(), 0xA9.toByte(), 0xF8.toByte(), 0xC5.toByte(), 0xC0.toByte(), 0xDC.toByte(), 0xF4.toByte(), 0x5F, 0xE7.toByte(), 0xCF.toByte(), 0xB6.toByte(), 0x8A.toByte()))
        val cw = BitWriter()
        for ((k, name) in classes.withIndex()) {
            cw.bs(500 + k); cw.bs(0); cw.t("ObjectDBX Classes"); cw.t("AcDb$name"); cw.t(name); cw.b(0); cw.bs(0x1F2)
        }
        val classBytes = if (classes.isEmpty()) ByteArray(0) else cw.toBytes() + ByteArray(4)
        val cs = classBytes.size
        body.write(byteArrayOf(cs.toByte(), (cs shr 8).toByte(), 0, 0))
        body.write(classBytes)
        body.write(byteArrayOf(0, 0))
        val locs = HashMap<Long, Int>()
        for (o in objects.sortedBy { it.handle }) {
            locs[o.handle] = base + body.size()
            body.write(o.bytes)
        }
        val mapOffset = base + body.size()
        val section = ByteArrayOutputStream()
        var lastH = 0L
        var lastL = 0L
        for (h in locs.keys.sorted()) {
            mc(section, h - lastH)
            mc(section, locs[h]!!.toLong() - lastL)
            lastH = h
            lastL = locs[h]!!.toLong()
        }
        val size = section.size() + 2
        body.write(size shr 8); body.write(size and 0xFF)
        body.write(section.toByteArray())
        body.write(0); body.write(0)
        body.write(0); body.write(2); body.write(0); body.write(0)
        fun rec(i: Int, num: Int, seeker: Int, len: Int) {
            val p = 0x19 + i * 9
            header[p] = num.toByte()
            for (k in 0 until 4) header[p + 1 + k] = (seeker shr (8 * k)).toByte()
            for (k in 0 until 4) header[p + 5 + k] = (len shr (8 * k)).toByte()
        }
        rec(0, 0, 0, 0)
        rec(1, 1, classesOffset, 22 + 0)
        rec(2, 2, mapOffset, size + 6)
        return header + body.toByteArray()
    }

    private class Recorder : DwgGeometrySink {
        val lines = ArrayList<DoubleArray>()
        val triangles = ArrayList<DoubleArray>()
        val texts = ArrayList<Pair<String, DoubleArray>>()
        val colors = ArrayList<Int>()
        override fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) { lines.add(doubleArrayOf(x1, y1, x2, y2)); colors.add(color) }
        override fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int) { triangles.add(doubleArrayOf(x1, y1, x2, y2, x3, y3, z1, z2, z3)) }
        override fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int) { texts.add(value to doubleArrayOf(x, y, height, rotationDeg, color.toDouble())) }
    }

    private fun sampleDrawing(): ByteArray {
        val model = 0x1FL
        val block = 0x30L
        val layer0 = 0x10L
        val layerRed = 0x11L
        val layerOff = 0x12L
        val objs = ArrayList<Obj>()
        objs.add(obj(model, 49, false, owner = 1, body = { w -> w.t("*Model_Space"); w.b(1); w.bs(0); w.b(0); repeat(5) { w.b(0) }; w.threeBd(0.0, 0.0, 0.0); w.t(""); w.rc(0); w.t(""); w.bl(0) }, handles = { w -> w.h(5, 0); w.h(3, 0); w.h(4, 0); w.h(4, 0); w.h(3, 0) }))
        objs.add(obj(block, 49, false, owner = 1, body = { w -> w.t("SQUARE"); w.b(1); w.bs(0); w.b(0); repeat(5) { w.b(0) }; w.threeBd(5.0, 5.0, 0.0); w.t(""); w.rc(0); w.t(""); w.bl(0) }, handles = { w -> w.h(5, 0); w.h(3, 0); w.h(4, 0); w.h(4, 0); w.h(3, 0) }))
        objs.add(obj(layer0, 51, false, owner = 2, body = { w -> w.t("0"); w.b(1); w.bs(0); w.b(0); w.bs(0); w.bs(7) }))
        objs.add(obj(layerRed, 51, false, owner = 2, body = { w -> w.t("RED"); w.b(1); w.bs(0); w.b(0); w.bs(0); w.bs(1) }))
        objs.add(obj(layerOff, 51, false, owner = 2, body = { w -> w.t("HIDDEN"); w.b(1); w.bs(0); w.b(0); w.bs(2); w.bs(3) }))
        objs.add(obj(0x40, 19, true, layer = layerRed, body = { w -> w.b(1); w.rd(1.0); w.ddFull(11.0); w.rd(2.0); w.ddFull(2.0); w.b(1); w.b(1) }))
        objs.add(obj(0x41, 18, true, color = 3, body = { w -> w.threeBd(20.0, 0.0, 0.0); w.bd(4.0); w.b(1); w.b(1) }))
        objs.add(obj(0x42, 17, true, body = { w -> w.threeBd(0.0, 30.0, 0.0); w.bd(2.0); w.b(1); w.b(1); w.bd(0.0); w.bd(Math.PI / 2) }))
        objs.add(obj(0x43, 1, true, color = 5, body = { w -> w.rc(0xF7); w.rd(3.0); w.rd(4.0); w.b(1); w.b(1); w.rd(Math.PI / 2); w.rd(2.5); w.t("Hello °") }, handles = { w -> w.h(5, 0) }))
        objs.add(obj(0x44, 77, true, body = { w -> w.bs(512 or 16); w.bl(3); w.bl(3); w.rd(0.0); w.rd(-10.0); w.ddFull(10.0); w.ddFull(-10.0); w.ddFull(10.0); w.ddFull(-5.0); w.bd(0.0); w.bd(1.0); w.bd(0.0) }))
        objs.add(obj(0x45, 19, true, layer = layerOff, body = { w -> w.b(1); w.rd(100.0); w.ddFull(200.0); w.rd(100.0); w.ddFull(200.0); w.b(1); w.b(1) }))
        objs.add(obj(0x46, 7, true, color = 2, body = { w -> w.threeBd(50.0, 50.0, 0.0); w.bits(0, 2); w.rd(2.0); w.ddFull(2.0); w.bits(0, 2); w.bd(Math.PI / 2); w.threeBd(0.0, 0.0, 1.0); w.b(0) }, handles = { w -> w.h(5, block) }))
        objs.add(obj(0x50, 19, true, entmode = 0, owner = block, color = 0, body = { w -> w.b(1); w.rd(5.0); w.ddFull(6.0); w.rd(5.0); w.ddFull(5.0); w.b(1); w.b(1) }))
        objs.add(obj(0x51, 19, true, entmode = 0, owner = block, body = { w -> w.b(1); w.rd(5.0); w.ddFull(5.0); w.rd(5.0); w.ddFull(6.0); w.b(1); w.b(1) }))
        return buildDwg(objs)
    }

    private fun near(a: Double, b: Double) = Math.abs(a - b) < 1e-6

    @Test
    fun readsSyntheticR2000DrawingWithLayersBlocksAndText() {
        val reader = CleanRoomDwgReader.open(sampleDrawing())
        assertEquals(DwgVersion.R2000, reader.version)
        assertEquals(0, reader.parseFailures)
        val rec = Recorder()
        val result = reader.render(rec)
        assertTrue(result.entityCount >= 9)
        val line = rec.lines.indices.first { near(rec.lines[it][0], 1.0) && near(rec.lines[it][2], 11.0) }
        assertEquals(1, rec.colors[line])
        assertTrue("entities on an off layer must be hidden", rec.lines.none { it.any { v -> v >= 100.0 } })
        val circle = rec.lines.filter { near(Math.hypot(it[0] - 20.0, it[1]), 4.0) }
        assertTrue(circle.size >= 8)
        val arcPts = rec.lines.filter { near(Math.hypot(it[0], it[1] - 30.0), 2.0) && it[0] >= -1e-9 && it[1] >= 30.0 - 1e-9 }
        assertTrue(arcPts.isNotEmpty())
        val (text, t) = rec.texts.single()
        assertEquals("Hello °", text)
        assertTrue(near(t[0], 3.0) && near(t[1], 4.0) && near(t[2], 2.5) && near(t[3], 90.0))
        val bulge = rec.lines.filter { it[0] > 9.99 && it[1] in -10.0..-5.0 && it[1] != -10.0 || it[2] > 10.0 }
        assertTrue("bulge segment must bow outward", rec.lines.any { it[0] > 10.0 || it[2] > 10.0 })
        assertTrue(bulge.isNotEmpty())
        val byBlock = rec.lines.indices.single { rec.colors[it] == 2 }
        assertTrue(near(rec.lines[byBlock][0], 50.0) && near(rec.lines[byBlock][1], 50.0) && near(rec.lines[byBlock][2], 50.0) && near(rec.lines[byBlock][3], 52.0))
        val byLayer = rec.lines.indices.single { near(rec.lines[it][0], 50.0) && near(rec.lines[it][1], 50.0) && it != byBlock }
        assertEquals(7, rec.colors[byLayer])
        assertTrue(near(rec.lines[byLayer][2], 48.0) && near(rec.lines[byLayer][3], 50.0))
    }

    private fun area(rec: Recorder): Double = rec.triangles.sumOf { t -> Math.abs((t[2] - t[0]) * (t[5] - t[1]) - (t[3] - t[1]) * (t[4] - t[0])) / 2 }

    private fun hatch(handle: Long, solid: Boolean): Obj = obj(handle, 78, true, body = { w ->
        w.bd(0.0); w.threeBd(0.0, 0.0, 1.0); w.t(if (solid) "SOLID" else "ANSI31"); w.b(if (solid) 1 else 0); w.b(0); w.bl(2)
        for (sq in listOf(doubleArrayOf(0.0, 10.0), doubleArrayOf(3.0, 7.0))) {
            w.bl(2); w.b(0); w.b(1); w.bl(4)
            w.rd(sq[0]); w.rd(sq[0]); w.rd(sq[1]); w.rd(sq[0]); w.rd(sq[1]); w.rd(sq[1]); w.rd(sq[0]); w.rd(sq[1])
            w.bl(0)
        }
        w.bs(0); w.bs(1)
        if (!solid) {
            w.bd(Math.PI / 4); w.bd(1.0); w.b(0); w.bs(1)
            w.bd(Math.PI / 4); w.bd(0.0); w.bd(0.0); w.bd(-0.5); w.bd(0.5); w.bs(0)
        }
        w.bl(0)
    })

    private fun minimalDrawing(extra: List<Obj>, classes: List<String> = emptyList()): ByteArray {
        val objs = ArrayList<Obj>()
        objs.add(obj(0x1F, 49, false, owner = 1, body = { w -> w.t("*Model_Space"); w.b(1); w.bs(0); w.b(0); repeat(5) { w.b(0) }; w.threeBd(0.0, 0.0, 0.0); w.t(""); w.rc(0); w.t(""); w.bl(0) }, handles = { w -> w.h(5, 0); w.h(3, 0); w.h(4, 0); w.h(4, 0); w.h(3, 0) }))
        objs.add(obj(0x10, 51, false, owner = 2, body = { w -> w.t("0"); w.b(1); w.bs(0); w.b(0); w.bs(0); w.bs(7) }))
        objs.addAll(extra)
        return buildDwg(objs, classes)
    }

    @Test
    fun solidHatchWithHoleFillsEvenOddArea() {
        val rec = Recorder()
        CleanRoomDwgReader.open(minimalDrawing(listOf(hatch(0x40, true)))).render(rec)
        assertEquals(84.0, area(rec), 1e-9)
        assertTrue(rec.triangles.none { t -> val cx = (t[0] + t[2] + t[4]) / 3; val cy = (t[1] + t[3] + t[5]) / 3; cx > 3 && cx < 7 && cy > 3 && cy < 7 })
        assertEquals(8, rec.lines.size)
    }

    @Test
    fun patternHatchLinesStayInsideBoundaryAndOutsideHoles() {
        val rec = Recorder()
        CleanRoomDwgReader.open(minimalDrawing(listOf(hatch(0x40, false)))).render(rec)
        val pattern = rec.lines.drop(8)
        assertTrue(pattern.size > 15)
        for (l in pattern) {
            val mx = (l[0] + l[2]) / 2; val my = (l[1] + l[3]) / 2
            assertTrue(mx in -1e-9..10.0 + 1e-9 && my in -1e-9..10.0 + 1e-9)
            assertTrue(!(mx > 3 + 1e-9 && mx < 7 - 1e-9 && my > 3 + 1e-9 && my < 7 - 1e-9))
            assertEquals(1.0, (l[3] - l[1]) / (l[2] - l[0]), 1e-9)
        }
    }

    @Test
    fun wipeoutMasksEarlierGeometryOnly() {
        fun line(h: Long, y: Double) = obj(h, 19, true, body = { w -> w.b(1); w.rd(-5.0); w.ddFull(15.0); w.rd(y); w.ddFull(y); w.b(1); w.b(1) })
        val wipe = obj(0x41, 500, true, body = { w ->
            w.bl(0); w.threeBd(0.0, 0.0, 0.0); w.threeBd(10.0, 0.0, 0.0); w.threeBd(0.0, 10.0, 0.0); w.rd(1.0); w.rd(1.0)
            w.bs(7); w.b(1); w.rc(50); w.rc(50); w.rc(0); w.bs(2); w.bl(4)
            w.rd(-0.5); w.rd(-0.5); w.rd(0.5); w.rd(-0.5); w.rd(0.5); w.rd(0.5); w.rd(-0.5); w.rd(0.5)
        }, handles = { w -> w.h(5, 0); w.h(3, 0) })
        val rec = Recorder()
        CleanRoomDwgReader.open(minimalDrawing(listOf(line(0x40, 5.0), wipe, line(0x42, 6.0)), listOf("WIPEOUT"))).render(rec)
        val masked = rec.lines.filter { Math.abs(it[1] - 5.0) < 1e-9 && Math.abs(it[3] - 5.0) < 1e-9 }
        assertEquals(2, masked.size)
        assertTrue(masked.all { maxOf(it[0], it[2]) <= 1e-9 || minOf(it[0], it[2]) >= 10 - 1e-9 })
        assertEquals(1, rec.lines.count { Math.abs(it[1] - 6.0) < 1e-9 && Math.abs(it[0] + 5.0) < 1e-9 && Math.abs(it[2] - 15.0) < 1e-9 })
        assertTrue("wipeout frame is drawn", rec.lines.any { Math.abs(it[1]) < 1e-9 && Math.abs(it[3]) < 1e-9 && Math.abs(Math.abs(it[2] - it[0]) - 10.0) < 1e-9 })
    }

    @Test
    fun solidWithEmbeddedSatIsDrawn() {
        val sat = """400 9 1 0
@7 unknown 24 Test
1 1e-06 1e-10
body $-1 $1 $-1 $-1 #
lump $-1 $-1 $2 $0 #
shell $-1 $-1 $-1 $3 $-1 $1 #
face $-1 $-1 $-1 $2 $-1 $4 forward single #
sphere-surface $-1 0 0 0 2 1 0 0 0 0 1 forward_v I I I I #
End-of-ACIS-data """
        val enc = sat.toByteArray().map { c -> val v = c.toInt() and 0xFF; if (v in 0x21..0x7E) 0x9F - v else v }
        val solid = obj(0x40, 38, true, color = 4, body = { w ->
            w.b(0); w.b(0); w.bs(1); w.bl(enc.size); enc.forEach { w.rc(it) }; w.bl(0); w.b(0)
        })
        val rec = Recorder()
        CleanRoomDwgReader.open(minimalDrawing(listOf(solid))).render(rec)
        assertTrue(rec.triangles.size > 500)
        assertTrue(rec.lines.isNotEmpty())
        assertTrue(rec.colors.all { it == 4 })
        for (t in rec.triangles) for (k in 0 until 3) assertEquals(2.0, Math.sqrt(t[k * 2] * t[k * 2] + t[k * 2 + 1] * t[k * 2 + 1] + t[6 + k] * t[6 + k]), 1e-9)
    }

    @Test
    fun corruptedSyntheticDrawingsNeverCrash() {
        val original = sampleDrawing()
        val rnd = Random(77)
        repeat(500) {
            val bytes = original.copyOf()
            repeat(1 + rnd.nextInt(8)) { bytes[0x30 + rnd.nextInt(bytes.size - 0x30)] = rnd.nextInt(256).toByte() }
            try {
                CleanRoomDwgReader.open(bytes).render(Recorder())
            } catch (_: DwgFormatException) {
            } catch (e: Throwable) {
                fail("unexpected ${e.javaClass.simpleName}: ${e.message} at " + e.stackTrace.take(4).joinToString(" | "))
            }
        }
    }

    @Test
    fun bitCodesFollowSpecification() {
        val w = BitWriter()
        w.bs(257); w.bs(0); w.bs(256); w.bs(15); w.bs(-1)
        w.bl(257); w.bl(0); w.bl(15)
        w.bd(1.0); w.bd(0.0); w.bd(3.25)
        w.bits(1, 2); w.rc(0x78); w.rc(0x56); w.rc(0x34); w.rc(0x12)
        w.bits(2, 2); w.rc(0xAB); w.rc(0xCD); w.rc(1); w.rc(2); w.rc(3); w.rc(4)
        w.h(5, 0x5E7)
        w.h(6, 0)
        w.h(0xC, 3)
        w.bits(2, 3); w.rc(0xD4); w.rc(0x07)
        val data = w.toBytes()
        val r = DwgBitReader(data, 0, data.size * 8L, DwgVersion.R2010, Charsets.ISO_8859_1)
        assertEquals(listOf(257, 0, 256, 15, -1), List(5) { r.bs() })
        assertEquals(listOf(257, 0, 15), List(3) { r.bl() })
        assertEquals(listOf(1.0, 0.0, 3.25), List(3) { r.bd() })
        val base = 0x1122334455667788L
        val d1 = java.lang.Double.doubleToRawLongBits(r.dd(java.lang.Double.longBitsToDouble(base)))
        assertEquals(0x1122334412345678L, d1)
        val d2 = java.lang.Double.doubleToRawLongBits(r.dd(java.lang.Double.longBitsToDouble(base)))
        assertEquals(0x1122CDAB04030201L, d2)
        assertEquals(0x5E7L, r.ref(0x100))
        assertEquals(0x101L, r.ref(0x100))
        assertEquals(0xFDL, r.ref(0x100))
        assertEquals(0x07D4L, r.bll())
        val mc = DwgBitReader.readMc(byteArrayOf(0x82.toByte(), 0x24), 0, false)
        assertEquals(4610L, mc[0])
        val neg = DwgBitReader.readMc(byteArrayOf(0x85.toByte(), 0x4B), 0, true)
        assertEquals(-1413L, neg[0])
        val ms = DwgBitReader.readMs(byteArrayOf(0x31, 0xF4.toByte(), 0x8D.toByte(), 0x00), 0)
        assertEquals(4650033, ms[0])
    }

    @Test
    fun r2004DecompressorHandlesLiteralsAndBackReferences() {
        val stream = byteArrayOf(0x01, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 0x5C, 0x00, 0x11)
        assertArrayEquals("abcdabcd".toByteArray(), CleanRoomDwgContainer.decompressR2004(stream, 0, stream.size, 8))
        val long = ByteArray(1 + 0x12 + 1)
        long[0] = 0x00
        long[1] = 0x00
        long[2] = 0x02
        try {
            CleanRoomDwgContainer.decompressR2004(long, 0, 3, 64)
            fail()
        } catch (_: DwgFormatException) {
        }
    }

    @Test
    fun r2007LiteralCopyUsesSpecifiedByteOrder() {
        val src = ByteArray(1 + 8) { it.toByte() }
        src[0] = 0x00
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), CleanRoomDwgContainer.decompressR21(src, 0, src.size, 8))
        val src32 = ByteArray(1 + 1 + 32)
        src32[0] = 0x0F
        src32[1] = (32 - 0x17).toByte()
        for (i in 0 until 32) src32[2 + i] = i.toByte()
        val out = CleanRoomDwgContainer.decompressR21(src32, 0, src32.size, 32)
        assertArrayEquals(ByteArray(32) { ((it + 16) % 32).toByte() }.let { a -> ByteArray(32) { i -> val half = if (i < 16) i + 16 else i - 16; val inner = half % 16; val blk = half - inner; (blk + (if (inner < 8) inner + 8 else inner - 8)).toByte() } }, out)
    }

    @Test
    fun bulgeArcsAndFitSplinesHaveCorrectGeometry() {
        val semi = CleanRoomDwgReader.bulgeArc(0.0, 0.0, 2.0, 0.0, 1.0)
        for (k in 0 until semi.size / 2) assertEquals(1.0, Math.hypot(semi[k * 2] - 1.0, semi[k * 2 + 1]), 1e-9)
        assertTrue(semi.indices.filter { it % 2 == 1 }.all { semi[it] <= 1e-9 })
        val fit = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 2.0, 0.0, 0.0, 3.0, 1.0, 0.0)
        val curve = CleanRoomDwgReader.interpolateFitPoints(fit, DoubleArray(6))
        for (k in 0 until 4) {
            val hit = (0 until curve.size / 3).any { Math.abs(curve[it * 3] - fit[k * 3]) < 1e-9 && Math.abs(curve[it * 3 + 1] - fit[k * 3 + 1]) < 1e-9 }
            assertTrue("curve passes through fit point $k", hit)
        }
        val ctrl = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 2.0, 0.0, 3.0, 2.0, 0.0, 4.0, 0.0, 0.0)
        val bez = CleanRoomDwgReader.evaluateSpline(3, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0), ctrl, DoubleArray(4) { 1.0 })
        val mid = bez.size / 3 / 2
        assertEquals(2.0, bez[mid * 3], 1e-9)
        assertEquals(1.5, bez[mid * 3 + 1], 1e-9)
    }

    @Test
    fun textControlCodesAreDecoded() {
        assertEquals("45° ±0.1 ⌀2", CleanRoomDwgReader.cleanText("45%%d %%p0.1 %%c2", false))
        assertEquals("Line one\nLine two 1/2", CleanRoomDwgReader.cleanText("{\\fArial|b1;Line one}\\P\\H2.5;Line two \\S1^2;", true))
        assertEquals("Ж°", CleanRoomDwgReader.cleanText("\\U+0416\\U+00B0", true))
    }

    @Test
    fun rejectsNonDwgData() {
        try {
            CleanRoomDwgReader.open("AC9999 not a drawing at all".toByteArray() + ByteArray(200))
            fail()
        } catch (_: DwgFormatException) {
        }
    }
}
