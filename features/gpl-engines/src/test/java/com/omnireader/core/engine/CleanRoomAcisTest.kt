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

import com.omnireader.core.engine.impl.CleanRoomAcis
import com.omnireader.core.engine.impl.CleanRoomDwgContainer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CleanRoomAcisTest {

    private class Rec(val name: String, val fields: List<Any>)

    private fun cubeRecords(size: Double = 2.0, ox: Double = 0.0): List<Rec> {
        val v = (0 until 8).map { i -> doubleArrayOf(ox + (i and 1) * size, ((i shr 1) and 1) * size, ((i shr 2) and 1) * size) }
        val faces = listOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3))
        val edges = ArrayList<Pair<Int, Int>>()
        fun edgeIndex(a: Int, b: Int): Pair<Int, Boolean> {
            val i = edges.indexOfFirst { (it.first == a && it.second == b) || (it.first == b && it.second == a) }
            if (i >= 0) return i to (edges[i].first != a)
            edges.add(a to b)
            return edges.size - 1 to false
        }
        val faceEdges = faces.map { f -> (0 until 4).map { k -> edgeIndex(f[k], f[(k + 1) % 4]) } }
        val faceBase = 3
        val loopBase = faceBase + 6
        val surfBase = loopBase + 6
        val coBase = surfBase + 6
        val edgeBase = coBase + 24
        val curveBase = edgeBase + edges.size
        val vertBase = curveBase + edges.size
        val pointBase = vertBase + 8
        val recs = ArrayList<Rec>()
        recs.add(Rec("body", listOf("$-1", "$1", "$-1", "$-1")))
        recs.add(Rec("lump", listOf("$-1", "$-1", "$2", "$0")))
        recs.add(Rec("shell", listOf("$-1", "$-1", "$-1", "\$$faceBase", "$-1", "$1")))
        for (f in 0 until 6) recs.add(Rec("face", listOf("$-1", if (f < 5) "\$${faceBase + f + 1}" else "$-1", "\$${loopBase + f}", "$2", "$-1", "\$${surfBase + f}", "forward", "single")))
        for (f in 0 until 6) recs.add(Rec("loop", listOf("$-1", "$-1", "\$${coBase + f * 4}", "\$${faceBase + f}")))
        for (f in 0 until 6) {
            val a = v[faces[f][0]]; val b = v[faces[f][1]]; val c = v[faces[f][2]]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val wx = c[0] - a[0]; val wy = c[1] - a[1]; val wz = c[2] - a[2]
            recs.add(Rec("plane-surface", listOf("$-1", a[0], a[1], a[2], uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx, 1.0, 0.0, 0.0, "forward_v", "I", "I", "I", "I")))
        }
        for (f in 0 until 6) for (k in 0 until 4) {
            val (ei, rev) = faceEdges[f][k]
            recs.add(Rec("coedge", listOf("$-1", "\$${coBase + f * 4 + (k + 1) % 4}", "\$${coBase + f * 4 + (k + 3) % 4}", "$-1", "\$${edgeBase + ei}", if (rev) "reversed" else "forward", "\$${loopBase + f}", "$-1")))
        }
        for ((i, e) in edges.withIndex()) recs.add(Rec("edge", listOf("$-1", "\$${vertBase + e.first}", "\$${vertBase + e.second}", "$-1", "\$${curveBase + i}", "forward")))
        for (e in edges) {
            val a = v[e.first]; val b = v[e.second]
            recs.add(Rec("straight-curve", listOf("$-1", a[0], a[1], a[2], b[0] - a[0], b[1] - a[1], b[2] - a[2], "I", "I")))
        }
        for (i in 0 until 8) recs.add(Rec("vertex", listOf("$-1", "$-1", "\$${pointBase + i}")))
        for (i in 0 until 8) recs.add(Rec("point", listOf("$-1", v[i][0], v[i][1], v[i][2])))
        return recs
    }

    private fun sat(recs: List<Rec>, version: Int): ByteArray {
        val sb = StringBuilder()
        if (version < 200) sb.append("$version ${recs.size} 1 0\n")
        else sb.append("$version ${recs.size} 1 0\n@7 unknown 24 Test\n1 9.9999999999999995e-07 1e-10\n")
        for (r in recs) {
            sb.append(r.name)
            if (version >= 700) sb.append(" $-1 -1")
            for (f in r.fields) sb.append(' ').append(f.toString())
            sb.append(" #\n")
        }
        sb.append("End-of-ACIS-data ")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun sab(recs: List<Rec>): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun f64(v: Double) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()) }
        fun str(tag: Int, s: String) { out.write(tag); out.write(s.length); out.write(s.toByteArray()) }
        fun name(n: String) {
            val parts = n.split('-')
            for (pp in parts.dropLast(1)) str(0x0E, pp)
            str(0x0D, parts.last())
        }
        out.write("ACIS BinaryFile".toByteArray())
        i32(21800); i32(recs.size); i32(0); i32(0)
        str(0x07, "Test"); str(0x07, "ASM"); str(0x07, "today")
        out.write(0x06); f64(1.0); out.write(0x06); f64(1e-6); out.write(0x06); f64(1e-10)
        name("asmheader"); out.write(0x0C); i32(-1); out.write(0x04); i32(-1); str(0x07, "223"); out.write(0x11)
        for (r in recs) {
            name(r.name)
            out.write(0x0C); i32(-1); out.write(0x04); i32(-1)
            for (f in r.fields) {
                when {
                    f is String && f.startsWith("$") -> { out.write(0x0C); i32(f.substring(1).toInt().let { if (it >= 0) it + 1 else it }) }
                    f is Double -> { out.write(0x06); f64(f) }
                    f == "reversed" -> out.write(0x0A)
                    f == "forward" -> out.write(0x0B)
                    else -> str(0x07, f.toString())
                }
            }
            out.write(0x11)
        }
        name("End-of-ACIS-data")
        out.write(0x11)
        return out.toByteArray()
    }

    private fun checkCube(g: CleanRoomAcis.Geometry, size: Double = 2.0, ox: Double = 0.0) {
        assertEquals(12, g.lines.size)
        assertEquals(6, g.faces.size)
        for (face in g.faces) {
            val loop = face.single()
            val n = loop.size / 3
            for (k in 0 until n) {
                for (a in 0 until 3) {
                    val v = loop[k * 3 + a] - (if (a == 0) ox else 0.0)
                    assertTrue(Math.abs(v) < 1e-9 || Math.abs(v - size) < 1e-9)
                }
            }
            var steps = 0
            for (k in 0 until n - 1) {
                val d = Math.abs(loop[k * 3] - loop[k * 3 + 3]) + Math.abs(loop[k * 3 + 1] - loop[k * 3 + 4]) + Math.abs(loop[k * 3 + 2] - loop[k * 3 + 5])
                assertTrue("loop must walk along cube edges", Math.abs(d - size) < 1e-9 || d < 1e-9)
                steps++
            }
            assertTrue(steps >= 3)
        }
    }

    @Test
    fun parsesCubeInAllSatVersionsAndSab() {
        val recs = cubeRecords()
        for (ver in listOf(106, 400, 700)) {
            val g = CleanRoomAcis.build(CleanRoomAcis.parse(sat(recs, ver)))
            assertEquals("SAT $ver edges", 12, g.lines.size)
            checkCube(g)
        }
        val g = CleanRoomAcis.build(CleanRoomAcis.parse(sab(recs)))
        assertEquals("SAB edges", 12, g.lines.size)
        checkCube(g)
    }

    @Test
    fun appliesBodyTransform() {
        val recs = ArrayList(cubeRecords())
        val bodyFields = recs[0].fields.toMutableList()
        bodyFields[3] = "\$${recs.size}"
        recs[0] = Rec("body", bodyFields)
        recs.add(Rec("transform", listOf("$-1", 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 10.0, 0.0, 0.0, 1.0, "no_rotate", "no_reflect", "no_shear")))
        checkCube(CleanRoomAcis.build(CleanRoomAcis.parse(sat(recs, 400))), 2.0, 10.0)
    }

    @Test
    fun coneAndSphereSurfacesProduceCurvedGeometry() {
        val cone = """400 9 1 0
@7 unknown 24 Test
1 1e-06 1e-10
body $-1 $1 $-1 $-1 #
lump $-1 $-1 $2 $0 #
shell $-1 $-1 $-1 $3 $-1 $1 #
face $-1 $-1 $4 $2 $-1 $5 forward single #
loop $-1 $-1 $6 $3 #
cone-surface $-1 0 0 0 0 0 1 5 0 0 1 I I 0 1 5 forward I I I I #
coedge $-1 $6 $6 $-1 $7 forward $4 $-1 #
edge $-1 $8 $8 $6 $9 forward #
vertex $-1 $7 $10 #
ellipse-curve $-1 0 0 0 0 0 1 5 0 0 1 I I #
point $-1 5 0 0 #
coedge $-1 $11 $11 $-1 $12 forward $4 $-1 #
edge $-1 $13 $13 $11 $-1 forward #
vertex $-1 $12 $14 #
point $-1 0 0 8 #
End-of-ACIS-data """
        val text = cone.replace("loop $-1 $-1 $6 $3 #", "loop $-1 $15 $6 $3 #") + ""
        val withSecondLoop = text.replace("End-of-ACIS-data", "loop $-1 $-1 $11 $3 #\nEnd-of-ACIS-data")
        val g = CleanRoomAcis.build(CleanRoomAcis.parse(withSecondLoop.toByteArray()))
        val circle = g.lines.first { it.size > 30 }
        for (k in 0 until circle.size / 3) assertEquals(5.0, Math.hypot(circle[k * 3], circle[k * 3 + 1]), 1e-9)
        assertTrue("cone rulings reach the apex", g.lines.count { it.size == 6 && Math.abs(it[5] - 8.0) < 1e-9 } == 4)
        assertTrue(g.triangles.size >= 9 * 32)

        val sphere = """400 6 1 0
@7 unknown 24 Test
1 1e-06 1e-10
body $-1 $1 $-1 $-1 #
lump $-1 $-1 $2 $0 #
shell $-1 $-1 $-1 $3 $-1 $1 #
face $-1 $-1 $-1 $2 $-1 $4 forward single #
sphere-surface $-1 1 2 3 4 1 0 0 0 0 1 forward_v I I I I #
End-of-ACIS-data """
        val s = CleanRoomAcis.build(CleanRoomAcis.parse(sphere.toByteArray()))
        assertTrue(s.triangles.isNotEmpty())
        for (k in 0 until s.triangles.size / 3) {
            assertEquals(4.0, Math.sqrt(Math.pow(s.triangles[k * 3] - 1, 2.0) + Math.pow(s.triangles[k * 3 + 1] - 2, 2.0) + Math.pow(s.triangles[k * 3 + 2] - 3, 2.0)), 1e-9)
        }
        assertEquals(5, s.lines.size)
    }

    @Test
    fun readsSabFromR2013DataStore() {
        val payload = "ACIS BinaryFile-test".toByteArray()
        val ds = ByteArray(1024)
        val bb = ByteBuffer.wrap(ds).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 0x6472616a); bb.putInt(24, 128); bb.putInt(32, 3); bb.putInt(40, 1)
        bb.putLong(128 + 48, 0); bb.putInt(128 + 56, 0)
        bb.putLong(128 + 60, 256); bb.putInt(128 + 68, 128)
        bb.putLong(128 + 72, 384); bb.putInt(128 + 80, 512)
        bb.putInt(256 + 48, 1); bb.putInt(256 + 56, 2); bb.putInt(256 + 60, 0); bb.putInt(256 + 64, 1)
        bb.putInt(384 + 36, 8)
        bb.putInt(384 + 48, 20); bb.putInt(384 + 52, 1); bb.putLong(384 + 56, 0x2E1); bb.putInt(384 + 64, 0)
        bb.putInt(384 + 128, payload.size)
        payload.copyInto(ds, 384 + 132)
        val map = CleanRoomDwgContainer.readDataStore(ds)
        assertArrayEquals(payload, map[0x2E1L])
    }
}
