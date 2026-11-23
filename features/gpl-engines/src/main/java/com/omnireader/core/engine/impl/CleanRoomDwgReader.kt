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
 *
 * DWG R13-R2018 object/entity bit-stream decoding informed by GNU LibreDWG
 * (https://www.gnu.org/software/libredwg/, Copyright (C) 2009-2026 Free Software Foundation, Inc.,
 * GPL-3.0-or-later) and ACadSharp (https://github.com/DomCR/ACadSharp, Copyright (c) 2020-2026
 * DomCR, MIT License).
 */

package com.omnireader.core.engine.impl

import java.nio.charset.Charset

class DwgBitReader(private val data: ByteArray, var pos: Long, private val end: Long, private val version: DwgVersion, private val charset: Charset) {
    var strings: DwgBitReader? = null

    fun b(): Int {
        if (pos >= end || (pos ushr 3) >= data.size) throw DwgFormatException("DWG bit stream overrun")
        val v = (data[(pos ushr 3).toInt()].toInt() shr (7 - (pos and 7).toInt())) and 1
        pos++
        return v
    }

    fun remaining(): Long = end - pos

    fun bits(n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or b()
        return v
    }

    fun rc(): Int = bits(8)
    fun rs(): Int = rc() or (rc() shl 8)
    fun rl(): Int = rs() or (rs() shl 16)
    fun rd(): Double {
        var v = 0L
        for (i in 0 until 8) v = v or (rc().toLong() shl (8 * i))
        return java.lang.Double.longBitsToDouble(v)
    }

    fun bb(): Int = bits(2)
    fun bs(): Int = when (bb()) {
        0 -> rs().toShort().toInt()
        1 -> rc()
        2 -> 0
        else -> 256
    }

    fun bl(): Int = when (bb()) {
        0 -> rl()
        1 -> rc()
        2 -> 0
        else -> throw DwgFormatException("Invalid DWG bitlong")
    }

    fun bll(): Long {
        val n = bits(3)
        var v = 0L
        for (i in 0 until n) v = v or (rc().toLong() shl (8 * i))
        return v
    }

    fun bd(): Double = when (bb()) {
        0 -> rd()
        1 -> 1.0
        2 -> 0.0
        else -> throw DwgFormatException("Invalid DWG bitdouble")
    }

    fun dd(default: Double): Double {
        return when (bb()) {
            0 -> default
            1 -> {
                var v = java.lang.Double.doubleToRawLongBits(default)
                v = v and 0xFFFFFFFFL.inv()
                for (i in 0 until 4) v = v or (rc().toLong() shl (8 * i))
                java.lang.Double.longBitsToDouble(v)
            }
            2 -> {
                var v = java.lang.Double.doubleToRawLongBits(default)
                val b4 = rc().toLong()
                val b5 = rc().toLong()
                v = v and (0xFFFFL shl 32).inv()
                v = v or (b4 shl 32) or (b5 shl 40)
                v = v and 0xFFFFFFFFL.inv()
                for (i in 0 until 4) v = v or (rc().toLong() shl (8 * i))
                java.lang.Double.longBitsToDouble(v)
            }
            else -> rd()
        }
    }

    fun bt(): Double = if (version.atLeast(DwgVersion.R2000)) { if (b() == 1) 0.0 else bd() } else bd()

    fun be(): DoubleArray = if (version.atLeast(DwgVersion.R2000) && b() == 1) doubleArrayOf(0.0, 0.0, 1.0) else threeBd()

    fun threeBd(): DoubleArray = doubleArrayOf(bd(), bd(), bd())
    fun twoRd(): DoubleArray = doubleArrayOf(rd(), rd())

    fun ot(): Int = if (version.atLeast(DwgVersion.R2010)) {
        when (bb()) {
            0 -> rc()
            1 -> rc() + 0x1F0
            else -> rs()
        }
    } else bs()

    fun handle(): LongArray {
        val code = bits(4)
        val counter = bits(4)
        if (counter > 8) throw DwgFormatException("Invalid DWG handle")
        var v = 0L
        for (i in 0 until counter) v = (v shl 8) or rc().toLong()
        return longArrayOf(code.toLong(), v)
    }

    fun ref(base: Long): Long {
        val h = handle()
        return when (h[0].toInt()) {
            6 -> base + 1
            8 -> base - 1
            0xA -> base + h[1]
            0xC -> base - h[1]
            else -> h[1]
        }
    }

    fun tv(): String {
        if (version.atLeast(DwgVersion.R2007)) {
            val s = strings ?: return ""
            return s.tu()
        }
        val len = bs()
        if (len < 0 || len > 65535) throw DwgFormatException("Invalid DWG string")
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = rc().toByte()
        var n = len
        while (n > 0 && bytes[n - 1].toInt() == 0) n--
        return String(bytes, 0, n, charset)
    }

    fun tu(): String {
        val len = bs()
        if (len < 0 || len > 65535) throw DwgFormatException("Invalid DWG unicode string")
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = rs()
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun color(): IntArray {
        if (version.below(DwgVersion.R2004)) return intArrayOf(bs(), -1, 0)
        val raw = bs()
        val index = raw and 0x1FF
        val flags = raw and 0xFE00
        var rgb = -1
        if ((flags and 0x8000) != 0) {
            val v = bl()
            rgb = if (((v ushr 24) and 0xFF) == 0xC2) v and 0xFFFFFF else -1
            if (rgb < 0 && ((v ushr 24) and 0xFF) == 0xC3) rgb = -2 - (v and 0xFF)
        }
        if ((flags and 0x2000) != 0) bl()
        return intArrayOf(index, rgb, flags)
    }

    fun tableColor(): IntArray {
        if (version.below(DwgVersion.R2004)) return intArrayOf(bs(), -1, 0)
        val index = bs()
        val v = bl()
        val flag = rc()
        if ((flag and 1) != 0) tv()
        if ((flag and 2) != 0) tv()
        val type = (v ushr 24) and 0xFF
        val rgb = if (type == 0xC2) v and 0xFFFFFF else -1
        val aci = if (type == 0xC3) v and 0xFF else index
        return intArrayOf(aci, rgb, 0)
    }

    companion object {
        fun readMs(data: ByteArray, at: Int): IntArray {
            var p = at
            var v = 0L
            var shift = 0
            while (true) {
                if (p + 2 > data.size) throw DwgFormatException("DWG object size is truncated")
                val w = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                v = v or ((w and 0x7FFF).toLong() shl shift)
                if ((w and 0x8000) == 0) break
                shift += 15
                if (shift > 30) throw DwgFormatException("DWG object size is invalid")
            }
            if (v > Int.MAX_VALUE) throw DwgFormatException("DWG object size is invalid")
            return intArrayOf(v.toInt(), p)
        }

        fun readMc(data: ByteArray, at: Int, signed: Boolean): LongArray {
            var p = at
            var v = 0L
            var shift = 0
            while (true) {
                if (p >= data.size) throw DwgFormatException("DWG modular char is truncated")
                val b = data[p++].toInt() and 0xFF
                if ((b and 0x80) != 0) {
                    v = v or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    if (shift > 63) throw DwgFormatException("DWG modular char is invalid")
                } else {
                    if (signed && (b and 0x40) != 0) {
                        v = v or ((b and 0x3F).toLong() shl shift)
                        v = -v
                    } else {
                        v = v or ((b and 0x7F).toLong() shl shift)
                    }
                    break
                }
            }
            return longArrayOf(v, p.toLong())
        }
    }
}

class DwgReadResult(val version: DwgVersion, val objectCount: Int, val entityCount: Int, val drawnEntities: Int, val unsupportedTypes: Map<String, Int>)

internal class MaskingSink(private val target: DwgGeometrySink) : DwgGeometrySink {
    private val ops = ArrayList<DoubleArray>()
    private val opColors = ArrayList<Int>()
    private val opTexts = ArrayList<String?>()
    private val masks = ArrayList<Pair<Int, DoubleArray>>()

    override val isFull: Boolean get() = ops.size > 3_000_000 || target.isFull

    override fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) {
        ops.add(doubleArrayOf(0.0, x1, y1, z1, x2, y2, z2)); opColors.add(color); opTexts.add(null)
    }

    override fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int) {
        ops.add(doubleArrayOf(1.0, x1, y1, z1, x2, y2, z2, x3, y3, z3)); opColors.add(color); opTexts.add(null)
    }

    override fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int) {
        ops.add(doubleArrayOf(2.0, x, y, z, height, rotationDeg)); opColors.add(color); opTexts.add(value)
    }

    override fun textWidth(value: String, height: Double): Double = target.textWidth(value, height)

    private val maskBoxes = ArrayList<DoubleArray>()

    fun addMask(polygon: DoubleArray) {
        if (polygon.size < 9) return
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (k in 0 until polygon.size / 3) {
            x0 = minOf(x0, polygon[k * 3]); x1 = maxOf(x1, polygon[k * 3])
            y0 = minOf(y0, polygon[k * 3 + 1]); y1 = maxOf(y1, polygon[k * 3 + 1])
        }
        masks.add(ops.size to polygon)
        maskBoxes.add(doubleArrayOf(x0, y0, x1, y1))
    }

    private fun inside(poly: DoubleArray, x: Double, y: Double): Boolean {
        val n = poly.size / 3
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 3]; val yi = poly[i * 3 + 1]; val xj = poly[j * 3]; val yj = poly[j * 3 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    private fun clipOutside(poly: DoubleArray, seg: DoubleArray): List<DoubleArray> {
        val ax = seg[0]; val ay = seg[1]; val az = seg[2]; val bx = seg[3]; val by = seg[4]; val bz = seg[5]
        val ts = ArrayList<Double>()
        ts.add(0.0); ts.add(1.0)
        val n = poly.size / 3
        val dx = bx - ax; val dy = by - ay
        for (i in 0 until n) {
            val j = (i + 1) % n
            val px = poly[i * 3]; val py = poly[i * 3 + 1]
            val qx = poly[j * 3] - px; val qy = poly[j * 3 + 1] - py
            val den = dx * qy - dy * qx
            if (Math.abs(den) < 1e-18) continue
            val t = ((px - ax) * qy - (py - ay) * qx) / den
            val u = ((px - ax) * dy - (py - ay) * dx) / den
            if (t > 0 && t < 1 && u >= 0 && u <= 1) ts.add(t)
        }
        ts.sort()
        val out = ArrayList<DoubleArray>()
        for (k in 0 until ts.size - 1) {
            val t0 = ts[k]; val t1 = ts[k + 1]
            if (t1 - t0 < 1e-12) continue
            val tm = (t0 + t1) / 2
            if (inside(poly, ax + dx * tm, ay + dy * tm)) continue
            out.add(doubleArrayOf(ax + dx * t0, ay + dy * t0, az + (bz - az) * t0, ax + dx * t1, ay + dy * t1, az + (bz - az) * t1))
        }
        return out
    }

    fun flush() {
        for (i in ops.indices) {
            if (target.isFull) return
            val op = ops[i]
            var lo = 0
            var hi = masks.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (masks[mid].first > i) hi = mid else lo = mid + 1 }
            val bx0: Double; val by0: Double; val bx1: Double; val by1: Double
            when (op[0].toInt()) {
                0 -> { bx0 = minOf(op[1], op[4]); bx1 = maxOf(op[1], op[4]); by0 = minOf(op[2], op[5]); by1 = maxOf(op[2], op[5]) }
                1 -> { bx0 = minOf(op[1], op[4], op[7]); bx1 = maxOf(op[1], op[4], op[7]); by0 = minOf(op[2], op[5], op[8]); by1 = maxOf(op[2], op[5], op[8]) }
                else -> { bx0 = op[1]; bx1 = op[1]; by0 = op[2]; by1 = op[2] }
            }
            val later = ArrayList<Pair<Int, DoubleArray>>()
            for (mi in lo until masks.size) {
                val b = maskBoxes[mi]
                if (bx1 < b[0] || bx0 > b[2] || by1 < b[1] || by0 > b[3]) continue
                later.add(masks[mi])
            }
            val color = opColors[i]
            when (op[0].toInt()) {
                0 -> {
                    var pieces = listOf(op.copyOfRange(1, 7))
                    for ((_, poly) in later) {
                        pieces = pieces.flatMap { clipOutside(poly, it) }
                        if (pieces.isEmpty()) break
                    }
                    for (pc in pieces) target.line(pc[0], pc[1], pc[2], pc[3], pc[4], pc[5], color)
                }
                1 -> {
                    val cx = (op[1] + op[4] + op[7]) / 3; val cy = (op[2] + op[5] + op[8]) / 3
                    if (later.none { inside(it.second, cx, cy) }) target.triangle(op[1], op[2], op[3], op[4], op[5], op[6], op[7], op[8], op[9], color)
                }
                else -> {
                    if (later.none { inside(it.second, op[1], op[2]) }) target.text(opTexts[i] ?: "", op[1], op[2], op[3], op[4], op[5], color)
                }
            }
        }
    }
}

class CleanRoomDwgReader private constructor(private val sections: DwgSections) {
    val version = sections.version
    private val charset: Charset = codepageCharset(sections.codepage)
    private val offsets = HashMap<Long, Int>()
    private val classNames = HashMap<Int, String>()

    private class Layer(val name: String, val color: Int, val visible: Boolean)

    private class BlockHeader(val handle: Long, val name: String, val base: DoubleArray, val owned: LongArray)

    private class Entity(val handle: Long, val kind: String) {
        var entmode = 0
        var owner = 0L
        var layer = 0L
        var color = 256
        var invisible = false
        var p = DoubleArray(0)
        var ext = doubleArrayOf(0.0, 0.0, 1.0)
        var d = DoubleArray(0)
        var i = IntArray(0)
        var text: String? = null
        var block = 0L
        var owned = LongArray(0)
        var firstLast = LongArray(0)
        var extra: Any? = null
        var dsData = false
    }

    private var chordTolerance = 0.0
    private var sceneScale = 0.0
    private val layers = HashMap<Long, Layer>()
    private val imageFiles = HashMap<Long, String>()
    private val sortKeys = HashMap<Long, Long>()
    private var wipeoutFrame = true
    private val blocks = HashMap<Long, BlockHeader>()
    private val entities = HashMap<Long, Entity>()
    private val byOwner = HashMap<Long, ArrayList<Long>>()
    private val unsupported = HashMap<String, Int>()
    private var objectCount = 0
    private var drawn = 0

    private fun parseObjectMap() {
        val b = sections.handles
        var p = sections.handlesOffset
        val end = minOf(sections.handlesEnd, b.size)
        while (p + 2 <= end) {
            val size = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            if (size <= 2) break
            val sectionEnd = p + size
            if (sectionEnd > end) throw DwgFormatException("DWG object map is truncated")
            var q = p + 2
            var lastHandle = 0L
            var lastLoc = 0L
            while (q < sectionEnd) {
                val h = DwgBitReader.readMc(b, q, false)
                q = h[1].toInt()
                val l = DwgBitReader.readMc(b, q, true)
                q = l[1].toInt()
                lastHandle += h[0]
                lastLoc += l[0]
                if (lastLoc in 0 until sections.objects.size) offsets[lastHandle] = lastLoc.toInt()
            }
            p = sectionEnd + 2
        }
        if (offsets.isEmpty()) throw DwgFormatException("DWG object map is empty")
    }

    private fun setupStrings(r: DwgBitReader, endBit: Long, startBit: Long) {
        if (version.below(DwgVersion.R2007)) return
        var pos = endBit - 1
        if (pos <= startBit) return
        val probe = DwgBitReader(sections.objects, pos, endBit, version, charset)
        if (probe.b() == 0) return
        pos -= 16
        val sizeReader = DwgBitReader(sections.objects, pos, endBit, version, charset)
        var size = sizeReader.rs().toLong()
        if ((size and 0x8000L) != 0L) {
            pos -= 16
            val hiReader = DwgBitReader(sections.objects, pos, endBit, version, charset)
            size = (size and 0x7FFFL) or (hiReader.rs().toLong() shl 15)
        }
        val start = pos - size
        if (start < startBit) return
        r.strings = DwgBitReader(sections.objects, start, pos, version, charset)
    }

    private fun parseClasses() {
        val c = sections.classes ?: return
        try {
            var p = sections.classesOffset
            if (!CleanRoomDwgContainer.classSentinelAt(c, p)) return
            p += 16
            val size = CleanRoomDwgContainer.i32(c, p)
            p += 4
            if (size <= 0 || p + size > c.size) return
            if (version.atLeast(DwgVersion.R2010)) p += 4
            var bitStart = p.toLong() * 8
            var endBit = bitStart + size.toLong() * 8
            if (version.atLeast(DwgVersion.R2007)) {
                val bitSize = CleanRoomDwgContainer.u32(c, p)
                endBit = p.toLong() * 8 + bitSize
                p += 4
                bitStart = p.toLong() * 8
            }
            val r = DwgBitReader(c, bitStart, minOf(endBit, c.size.toLong() * 8), version, charset)
            if (version.atLeast(DwgVersion.R2007)) {
                val probeEnd = endBit
                var pos = probeEnd - 1
                val probe = DwgBitReader(c, pos, probeEnd, version, charset)
                if (probe.b() == 1) {
                    pos -= 16
                    var sz = DwgBitReader(c, pos, probeEnd, version, charset).rs().toLong()
                    if ((sz and 0x8000L) != 0L) {
                        pos -= 16
                        sz = (sz and 0x7FFFL) or (DwgBitReader(c, pos, probeEnd, version, charset).rs().toLong() shl 15)
                    }
                    r.strings = DwgBitReader(c, pos - sz, pos, version, charset)
                }
            }
            val limit = if (r.strings != null) r.strings!!.pos else endBit
            if (version.atLeast(DwgVersion.R2004)) {
                r.bs()
                r.rc()
                r.rc()
                r.b()
            }
            while (r.pos + 16 < limit) {
                val num = r.bs()
                r.bs()
                r.tv()
                r.tv()
                val dxf = r.tv()
                r.b()
                r.bs()
                if (version.atLeast(DwgVersion.R2004)) {
                    r.bl()
                    r.bs()
                    r.bs()
                    r.bl()
                    r.bl()
                }
                if (num in 500..5000) classNames[num] = dxf.uppercase()
            }
        } catch (_: DwgFormatException) {
        }
    }

    private fun typeName(type: Int): String = when (type) {
        1 -> "TEXT"; 2 -> "ATTRIB"; 3 -> "ATTDEF"; 4 -> "BLOCK"; 5 -> "ENDBLK"; 6 -> "SEQEND"
        7 -> "INSERT"; 8 -> "MINSERT"; 10 -> "VERTEX_2D"; 11 -> "VERTEX_3D"; 12 -> "VERTEX_MESH"; 13 -> "VERTEX_PFACE"
        14 -> "VERTEX_PFACE_FACE"; 15 -> "POLYLINE_2D"; 16 -> "POLYLINE_3D"; 17 -> "ARC"; 18 -> "CIRCLE"; 19 -> "LINE"
        in 20..26 -> "DIMENSION"; 27 -> "POINT"; 28 -> "3DFACE"; 29 -> "POLYLINE_PFACE"; 30 -> "POLYLINE_MESH"
        31 -> "SOLID"; 32 -> "TRACE"; 33 -> "SHAPE"; 34 -> "VIEWPORT"; 35 -> "ELLIPSE"; 36 -> "SPLINE"
        37 -> "REGION"; 38 -> "3DSOLID"; 39 -> "BODY"; 40 -> "RAY"; 41 -> "XLINE"; 44 -> "MTEXT"; 45 -> "LEADER"
        46 -> "TOLERANCE"; 47 -> "MLINE"; 49 -> "BLOCK_HEADER"; 51 -> "LAYER"; 77 -> "LWPOLYLINE"; 78 -> "HATCH"
        else -> if (type >= 500) classNames[type] ?: "CLASS_$type" else "OBJECT_$type"
    }

    private fun isEntityType(name: String, type: Int): Boolean = when (name) {
        "BLOCK_HEADER", "LAYER" -> false
        "LWPOLYLINE", "HATCH", "ARC_DIMENSION", "LARGE_RADIAL_DIMENSION", "ACAD_TABLE", "WIPEOUT", "IMAGE", "MLEADER", "MULTILEADER", "MESH", "HELIX", "PDFUNDERLAY", "DWFUNDERLAY", "DGNUNDERLAY" -> true
        else -> type in 1..41 || type in 44..47
    }

    private fun parseObject(handleKey: Long, offset: Int) {
        val data = sections.objects
        val ms = DwgBitReader.readMs(data, offset)
        val size = ms[0]
        var start = ms[1]
        var handleStreamBits = 0L
        if (version.atLeast(DwgVersion.R2010)) {
            val mc = DwgBitReader.readMc(data, start, false)
            handleStreamBits = mc[0]
            start = mc[1].toInt()
        }
        if (size <= 0 || start.toLong() + size > data.size) throw DwgFormatException("DWG object is truncated")
        val startBit = start.toLong() * 8
        val endBitAll = startBit + size.toLong() * 8
        val r = DwgBitReader(data, startBit, endBitAll, version, charset)
        val type = r.ot()
        var dataEndBit = endBitAll
        if (version.atLeast(DwgVersion.R2000) && version.below(DwgVersion.R2010)) dataEndBit = startBit + (r.rl().toLong() and 0xFFFFFFFFL)
        if (version.atLeast(DwgVersion.R2010)) dataEndBit = endBitAll - handleStreamBits
        val name = typeName(type)
        val wanted = name == "BLOCK_HEADER" || name == "LAYER" || name == "IMAGEDEF" || name == "WIPEOUTVARIABLES" || name == "SORTENTSTABLE" || isEntityType(name, type)
        if (!wanted) return
        val handle = r.handle()[1]
        var eed = r.bs()
        var guard = 0
        while (eed != 0) {
            if (eed < 0 || ++guard > 4096) throw DwgFormatException("DWG extended data is invalid")
            r.handle()
            r.pos += eed.toLong() * 8
            eed = r.bs()
        }
        val isEntity = isEntityType(name, type)
        if (isEntity) {
            if (r.b() == 1) {
                val gsize = if (version.atLeast(DwgVersion.R2010)) r.bll() else (r.rl().toLong() and 0xFFFFFFFFL)
                if (gsize < 0 || gsize > size) throw DwgFormatException("DWG graphic data is invalid")
                r.pos += gsize * 8
            }
        }
        if (version.below(DwgVersion.R2000)) dataEndBit = startBit + (r.rl().toLong() and 0xFFFFFFFFL)
        if (dataEndBit <= startBit || dataEndBit > endBitAll) throw DwgFormatException("DWG object bit size is invalid")
        setupStrings(r, dataEndBit, startBit)
        val hr = DwgBitReader(data, dataEndBit, endBitAll, version, charset)
        if (isEntity) parseEntity(handle, type, name, r, hr) else parseNonEntity(handle, name, r, hr)
    }

    private fun parseNonEntity(handle: Long, name: String, r: DwgBitReader, hr: DwgBitReader) {
        val reactors = r.bl()
        var xdicMissing = false
        if (version.atLeast(DwgVersion.R2004)) xdicMissing = r.b() == 1
        if (version.atLeast(DwgVersion.R2013)) r.b()
        if (reactors < 0 || reactors > 100000) throw DwgFormatException("DWG reactor count is invalid")
        hr.ref(handle)
        repeat(reactors) { hr.ref(handle) }
        if (!xdicMissing) hr.ref(handle)
        when (name) {
            "IMAGEDEF" -> {
                r.bl()
                r.twoRd()
                imageFiles[handle] = r.tv()
                return
            }
            "WIPEOUTVARIABLES" -> {
                wipeoutFrame = r.bs() != 0
                return
            }
            "SORTENTSTABLE" -> {
                val n = r.bl()
                if (n < 0 || n > 10_000_000) throw DwgFormatException("DWG sort table is invalid")
                val keys = LongArray(n) { r.handle()[1] }
                hr.ref(handle)
                for (k in 0 until n) sortKeys[hr.ref(handle)] = keys[k]
                return
            }
        }
        val entryName = r.tv()
        r.b()
        if (version.below(DwgVersion.R2007)) r.bs()
        r.b()
        when (name) {
            "LAYER" -> {
                var visible = true
                if (version.below(DwgVersion.R2000)) {
                    val frozen = r.b()
                    r.b()
                    r.b()
                    r.b()
                    visible = frozen == 0
                } else {
                    val flags = r.bs()
                    visible = (flags and 1) == 0 && (flags and 2) == 0
                }
                val col = r.tableColor()
                if (col[0] < 0) visible = false
                val color = if (col[1] >= 0) (Int.MIN_VALUE or col[1]) else Math.abs(col[0]).let { if (it in 1..255) it else 7 }
                layers[handle] = Layer(entryName, color, visible)
            }
            "BLOCK_HEADER" -> {
                r.b()
                r.b()
                val isXref = r.b() == 1
                val overlaid = r.b() == 1
                if (version.atLeast(DwgVersion.R2000)) r.b()
                var ownedCount = 0
                if (version.atLeast(DwgVersion.R2004)) ownedCount = r.bl()
                val base = r.threeBd()
                if (ownedCount < 0 || ownedCount > 10_000_000) throw DwgFormatException("DWG block is invalid")
                hr.ref(handle)
                hr.ref(handle)
                val owned = if (version.atLeast(DwgVersion.R2004)) {
                    LongArray(ownedCount) { hr.ref(handle) }
                } else if (!isXref && !overlaid) {
                    longArrayOf(hr.ref(handle), hr.ref(handle))
                } else LongArray(0)
                blocks[handle] = BlockHeader(handle, entryName, base, if (version.atLeast(DwgVersion.R2004)) owned else LongArray(0))
            }
        }
    }

    private fun parseEntity(handle: Long, type: Int, name: String, r: DwgBitReader, hr: DwgBitReader) {
        val e = Entity(handle, name)
        e.entmode = r.bb()
        val reactors = r.bl()
        if (reactors < 0 || reactors > 100000) throw DwgFormatException("DWG reactor count is invalid")
        var xdicMissing = false
        if (version.atLeast(DwgVersion.R2004)) xdicMissing = r.b() == 1
        if (version.atLeast(DwgVersion.R2013)) e.dsData = r.b() == 1
        var isByLayerLt = true
        if (version.below(DwgVersion.R2000)) isByLayerLt = r.b() == 1
        var nolinks = true
        if (version.below(DwgVersion.R2004)) nolinks = r.b() == 1
        val col = r.color()
        e.color = if (col[1] >= 0) (Int.MIN_VALUE or col[1]) else if (col[1] <= -2) (-2 - col[1]).coerceIn(0, 256) else col[0]
        r.bd()
        var ltFlags = 0
        var psFlags = 0
        var matFlags = 0
        var fullVs = 0
        var faceVs = 0
        var edgeVs = 0
        if (version.atLeast(DwgVersion.R2000)) {
            ltFlags = r.bb()
            psFlags = r.bb()
        }
        if (version.atLeast(DwgVersion.R2007)) {
            matFlags = r.bb()
            r.rc()
        }
        if (version.atLeast(DwgVersion.R2010)) {
            fullVs = r.b()
            faceVs = r.b()
            edgeVs = r.b()
        }
        e.invisible = (r.bs() and 1) != 0
        if (version.atLeast(DwgVersion.R2000)) r.rc()
        if (e.entmode == 0) e.owner = hr.ref(handle)
        repeat(reactors) { hr.ref(handle) }
        if (version.below(DwgVersion.R2004) || !xdicMissing) hr.ref(handle)
        if (version.below(DwgVersion.R2000)) {
            e.layer = hr.ref(handle)
            if (!isByLayerLt) hr.ref(handle)
        }
        if (version.below(DwgVersion.R2004) && !nolinks) {
            hr.ref(handle)
            hr.ref(handle)
        }
        if (version.atLeast(DwgVersion.R2004) && (col[2] and 0x4000) != 0) hr.ref(handle)
        if (version.atLeast(DwgVersion.R2000)) {
            e.layer = hr.ref(handle)
            if (ltFlags == 3) hr.ref(handle)
        }
        if (version.atLeast(DwgVersion.R2007) && matFlags == 3) hr.ref(handle)
        if (version.atLeast(DwgVersion.R2000) && psFlags == 3) hr.ref(handle)
        if (version.atLeast(DwgVersion.R2010)) {
            if (fullVs == 1) hr.ref(handle)
            if (faceVs == 1) hr.ref(handle)
            if (edgeVs == 1) hr.ref(handle)
        }
        if (!parseEntityBody(e, type, name, r, hr)) {
            unsupported[name] = (unsupported[name] ?: 0) + 1
        }
        entities[handle] = e
        val ownerKey = when (e.entmode) {
            1 -> PAPER_KEY
            2 -> MODEL_KEY
            else -> e.owner
        }
        byOwner.getOrPut(ownerKey) { ArrayList() }.add(handle)
    }

    private fun textCommon(e: Entity, r: DwgBitReader) {
        if (version.below(DwgVersion.R2000)) {
            val elevation = r.bd()
            val ins = r.twoRd()
            val align = r.twoRd()
            e.ext = r.threeBd()
            r.bd()
            r.bd()
            val rotation = r.bd()
            val height = r.bd()
            val widthFactor = r.bd()
            e.text = r.tv()
            r.bs()
            val h = r.bs()
            val v = r.bs()
            e.p = doubleArrayOf(ins[0], ins[1], elevation)
            e.d = doubleArrayOf(height, rotation, align[0], align[1], widthFactor)
            e.i = intArrayOf(h, v)
        } else {
            val flags = r.rc()
            val elevation = if ((flags and 1) == 0) r.rd() else 0.0
            val ins = r.twoRd()
            var ax = ins[0]
            var ay = ins[1]
            if ((flags and 2) == 0) {
                ax = r.dd(ins[0])
                ay = r.dd(ins[1])
            }
            e.ext = r.be()
            r.bt()
            if ((flags and 4) == 0) r.rd()
            val rotation = if ((flags and 8) == 0) r.rd() else 0.0
            val height = r.rd()
            val widthFactor = if ((flags and 0x10) == 0) r.rd() else 1.0
            e.text = r.tv()
            if ((flags and 0x20) == 0) r.bs()
            val h = if ((flags and 0x40) == 0) r.bs() else 0
            val v = if ((flags and 0x80) == 0) r.bs() else 0
            e.p = doubleArrayOf(ins[0], ins[1], elevation)
            e.d = doubleArrayOf(height, rotation, ax, ay, widthFactor)
            e.i = intArrayOf(h, v)
        }
    }

    private fun parseEntityBody(e: Entity, type: Int, name: String, r: DwgBitReader, hr: DwgBitReader): Boolean {
        when (name) {
            "LINE" -> {
                if (version.below(DwgVersion.R2000)) {
                    e.p = r.threeBd() + r.threeBd()
                } else {
                    val zZero = r.b() == 1
                    val x1 = r.rd()
                    val x2 = r.dd(x1)
                    val y1 = r.rd()
                    val y2 = r.dd(y1)
                    var z1 = 0.0
                    var z2 = 0.0
                    if (!zZero) {
                        z1 = r.rd()
                        z2 = r.dd(z1)
                    }
                    e.p = doubleArrayOf(x1, y1, z1, x2, y2, z2)
                }
                r.bt()
                e.ext = r.be()
            }
            "CIRCLE", "ARC" -> {
                e.p = r.threeBd()
                val radius = r.bd()
                r.bt()
                e.ext = r.be()
                e.d = if (name == "ARC") doubleArrayOf(radius, r.bd(), r.bd()) else doubleArrayOf(radius)
            }
            "ELLIPSE" -> {
                e.p = r.threeBd() + r.threeBd()
                e.ext = r.threeBd()
                e.d = doubleArrayOf(r.bd(), r.bd(), r.bd())
            }
            "TEXT" -> {
                textCommon(e, r)
            }
            "ATTRIB" -> {
                textCommon(e, r)
                if (version.atLeast(DwgVersion.R2010)) r.rc()
                var hidden = false
                if (version.atLeast(DwgVersion.R2018)) {
                    val attType = r.rc()
                    if (attType == 2 || attType == 4) return false
                }
                r.tv()
                r.bs()
                hidden = (r.rc() and 1) != 0
                if (hidden) e.invisible = true
            }
            "ATTDEF" -> {
                textCommon(e, r)
                if (version.atLeast(DwgVersion.R2010)) r.rc()
                if (version.atLeast(DwgVersion.R2018)) {
                    val attType = r.rc()
                    if (attType == 2 || attType == 4) return false
                }
                e.text = r.tv()
                r.bs()
                if ((r.rc() and 1) != 0) e.invisible = true
            }
            "INSERT", "MINSERT" -> {
                val ins = r.threeBd()
                val scale: DoubleArray
                if (version.below(DwgVersion.R2000)) {
                    scale = r.threeBd()
                } else {
                    scale = when (r.bb()) {
                        3 -> doubleArrayOf(1.0, 1.0, 1.0)
                        1 -> doubleArrayOf(1.0, r.dd(1.0), r.dd(1.0))
                        2 -> r.rd().let { doubleArrayOf(it, it, it) }
                        else -> {
                            val x = r.rd()
                            doubleArrayOf(x, r.dd(x), r.dd(x))
                        }
                    }
                }
                val rotation = r.bd()
                e.ext = r.threeBd()
                val hasAttribs = r.b() == 1
                var ownedCount = 0
                if (version.atLeast(DwgVersion.R2004) && hasAttribs) ownedCount = r.bl()
                var grid = intArrayOf(1, 1)
                var spacing = doubleArrayOf(0.0, 0.0)
                if (name == "MINSERT") {
                    grid = intArrayOf(r.bs(), r.bs())
                    spacing = doubleArrayOf(r.bd(), r.bd())
                }
                e.p = ins
                e.d = doubleArrayOf(scale[0], scale[1], scale[2], rotation, spacing[0], spacing[1])
                e.i = grid
                e.block = hr.ref(e.handle)
                if (hasAttribs) {
                    if (ownedCount < 0 || ownedCount > 1_000_000) throw DwgFormatException("DWG insert is invalid")
                    e.owned = if (version.atLeast(DwgVersion.R2004)) LongArray(ownedCount) { hr.ref(e.handle) } else longArrayOf(hr.ref(e.handle), hr.ref(e.handle))
                }
            }
            "VERTEX_2D" -> {
                val flags = r.rc()
                val pt = r.threeBd()
                val sw = r.bd()
                if (sw >= 0) r.bd()
                val bulge = r.bd()
                e.p = pt
                e.d = doubleArrayOf(bulge)
                e.i = intArrayOf(flags)
            }
            "VERTEX_3D", "VERTEX_MESH", "VERTEX_PFACE" -> {
                e.i = intArrayOf(r.rc())
                e.p = r.threeBd()
            }
            "VERTEX_PFACE_FACE" -> {
                e.i = intArrayOf(r.bs(), r.bs(), r.bs(), r.bs())
            }
            "POLYLINE_2D", "POLYLINE_3D", "POLYLINE_PFACE", "POLYLINE_MESH" -> {
                var flags = 0
                var elevation = 0.0
                var counts = IntArray(0)
                when (name) {
                    "POLYLINE_2D" -> {
                        flags = r.bs()
                        r.bs()
                        r.bd(); r.bd()
                        r.bt()
                        elevation = r.bd()
                        e.ext = r.be()
                    }
                    "POLYLINE_3D" -> {
                        r.rc()
                        flags = r.rc()
                    }
                    "POLYLINE_PFACE" -> counts = intArrayOf(r.bs(), r.bs())
                    else -> {
                        flags = r.bs()
                        r.bs()
                        counts = intArrayOf(r.bs(), r.bs())
                        r.bs(); r.bs()
                    }
                }
                var ownedCount = 0
                if (version.atLeast(DwgVersion.R2004)) ownedCount = r.bl()
                if (ownedCount < 0 || ownedCount > 10_000_000) throw DwgFormatException("DWG polyline is invalid")
                e.i = intArrayOf(flags) + counts
                e.d = doubleArrayOf(elevation)
                if (version.atLeast(DwgVersion.R2004)) e.owned = LongArray(ownedCount) { hr.ref(e.handle) }
                else e.firstLast = longArrayOf(hr.ref(e.handle), hr.ref(e.handle))
            }
            "DIMENSION", "ARC_DIMENSION", "LARGE_RADIAL_DIMENSION" -> {
                hr.ref(e.handle)
                e.block = hr.ref(e.handle)
            }
            "POINT" -> {
                e.p = r.threeBd()
            }
            "3DFACE" -> {
                if (version.below(DwgVersion.R2000)) {
                    e.p = r.threeBd() + r.threeBd() + r.threeBd() + r.threeBd()
                    e.i = intArrayOf(r.bs())
                } else {
                    val noFlags = r.b() == 1
                    val zZero = r.b() == 1
                    val x1 = r.rd()
                    val y1 = r.rd()
                    val z1 = if (zZero) 0.0 else r.rd()
                    val x2 = r.dd(x1); val y2 = r.dd(y1); val z2 = r.dd(z1)
                    val x3 = r.dd(x2); val y3 = r.dd(y2); val z3 = r.dd(z2)
                    val x4 = r.dd(x3); val y4 = r.dd(y3); val z4 = r.dd(z3)
                    e.p = doubleArrayOf(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4)
                    e.i = intArrayOf(if (noFlags) 0 else r.bs())
                }
            }
            "SOLID", "TRACE" -> {
                r.bt()
                val z = r.bd()
                val c = r.twoRd() + r.twoRd() + r.twoRd() + r.twoRd()
                e.ext = r.be()
                e.p = doubleArrayOf(c[0], c[1], z, c[2], c[3], z, c[4], c[5], z, c[6], c[7], z)
            }
            "SPLINE", "HELIX" -> {
                var scenario = r.bl()
                if (version.atLeast(DwgVersion.R2013)) {
                    val sf = r.bl()
                    val knotParam = r.bl()
                    if ((sf and 1) != 0) scenario = 2
                    if (knotParam == 15) scenario = 1
                }
                val degree = r.bl()
                var knots = DoubleArray(0)
                var ctrl = DoubleArray(0)
                var weights = DoubleArray(0)
                var fit = DoubleArray(0)
                var numFit = 0
                var closed = false
                var tangents = DoubleArray(6)
                if (scenario == 2) {
                    r.bd()
                    tangents = r.threeBd() + r.threeBd()
                    numFit = r.bl()
                } else if (scenario == 1) {
                    r.b()
                    closed = r.b() == 1
                    r.b()
                    r.bd(); r.bd()
                    val nk = r.bl()
                    val nc = r.bl()
                    val weighted = r.b() == 1
                    if (nk < 0 || nk > 1_000_000 || nc < 0 || nc > 1_000_000) throw DwgFormatException("DWG spline is invalid")
                    knots = DoubleArray(nk) { r.bd() }
                    ctrl = DoubleArray(nc * 3)
                    weights = DoubleArray(nc) { 1.0 }
                    for (k in 0 until nc) {
                        val pt = r.threeBd()
                        ctrl[k * 3] = pt[0]; ctrl[k * 3 + 1] = pt[1]; ctrl[k * 3 + 2] = pt[2]
                        if (weighted) weights[k] = r.bd()
                    }
                } else throw DwgFormatException("DWG spline scenario is invalid")
                if (numFit < 0 || numFit > 1_000_000) throw DwgFormatException("DWG spline is invalid")
                fit = DoubleArray(numFit * 3)
                for (k in 0 until numFit) {
                    val pt = r.threeBd()
                    fit[k * 3] = pt[0]; fit[k * 3 + 1] = pt[1]; fit[k * 3 + 2] = pt[2]
                }
                e.i = intArrayOf(degree, if (closed) 1 else 0)
                e.extra = arrayOf(knots, ctrl, weights, fit, tangents)
            }
            "MTEXT" -> {
                val ins = r.threeBd()
                e.ext = r.threeBd()
                val xdir = r.threeBd()
                val width = r.bd()
                if (version.atLeast(DwgVersion.R2007)) r.bd()
                val height = r.bd()
                val attach = r.bs()
                r.bs()
                r.bd(); r.bd()
                e.text = r.tv()
                e.p = ins
                e.d = doubleArrayOf(height, width, xdir[0], xdir[1], xdir[2])
                e.i = intArrayOf(attach)
            }
            "LWPOLYLINE" -> {
                val flags = r.bs()
                if ((flags and 4) != 0) r.bd()
                val elevation = if ((flags and 8) != 0) r.bd() else 0.0
                if ((flags and 2) != 0) r.bd()
                if ((flags and 1) != 0) e.ext = r.threeBd()
                val n = r.bl()
                val nb = if ((flags and 16) != 0) r.bl() else 0
                val nid = if (version.atLeast(DwgVersion.R2010) && (flags and 1024) != 0) r.bl() else 0
                val nw = if ((flags and 32) != 0) r.bl() else 0
                if (n < 0 || n > 5_000_000 || nb < 0 || nb > 5_000_000 || nid < 0 || nid > 5_000_000 || nw < 0 || nw > 5_000_000) throw DwgFormatException("DWG lwpolyline is invalid")
                val pts = DoubleArray(n * 2)
                for (k in 0 until n) {
                    if (version.below(DwgVersion.R2000) || k == 0) {
                        pts[k * 2] = r.rd(); pts[k * 2 + 1] = r.rd()
                    } else {
                        pts[k * 2] = r.dd(pts[k * 2 - 2]); pts[k * 2 + 1] = r.dd(pts[k * 2 - 1])
                    }
                }
                val bulges = DoubleArray(n)
                for (k in 0 until nb) {
                    val v = r.bd()
                    if (k < n) bulges[k] = v
                }
                e.p = pts
                e.d = doubleArrayOf(elevation) + bulges
                e.i = intArrayOf(flags)
            }
            "LEADER" -> {
                r.b()
                r.bs()
                val pathType = r.bs()
                e.i = intArrayOf(pathType)
                val n = r.bl()
                if (n < 0 || n > 100000) throw DwgFormatException("DWG leader is invalid")
                val pts = DoubleArray(n * 3)
                for (k in 0 until n) {
                    val pt = r.threeBd()
                    pts[k * 3] = pt[0]; pts[k * 3 + 1] = pt[1]; pts[k * 3 + 2] = pt[2]
                }
                e.p = pts
            }
            "MLINE" -> {
                r.bd()
                r.rc()
                r.threeBd()
                e.ext = r.threeBd()
                val openClosed = r.bs()
                val lines = r.rc()
                val nv = r.bs()
                if (nv < 0 || nv > 100000 || lines > 64) throw DwgFormatException("DWG mline is invalid")
                val paths = Array(lines) { DoubleArray(nv * 3) }
                for (v in 0 until nv) {
                    val vert = r.threeBd()
                    r.threeBd()
                    val miter = r.threeBd()
                    for (l in 0 until lines) {
                        val ns = r.bs()
                        if (ns < 0 || ns > 10000) throw DwgFormatException("DWG mline is invalid")
                        val parms = DoubleArray(ns) { r.bd() }
                        val nf = r.bs()
                        if (nf < 0 || nf > 10000) throw DwgFormatException("DWG mline is invalid")
                        repeat(nf) { r.bd() }
                        val off = if (ns > 0) parms[0] else 0.0
                        paths[l][v * 3] = vert[0] + miter[0] * off
                        paths[l][v * 3 + 1] = vert[1] + miter[1] * off
                        paths[l][v * 3 + 2] = vert[2] + miter[2] * off
                    }
                }
                e.i = intArrayOf(if (openClosed == 3) 1 else 0)
                e.extra = paths
            }
            "HATCH" -> {
                var gradient: DoubleArray? = null
                if (version.atLeast(DwgVersion.R2004)) {
                    val isGradient = r.bl() != 0
                    r.bl()
                    val gAngle = r.bd()
                    r.bd()
                    val singleColor = r.bl() != 0
                    val tint = r.bd()
                    val nc = r.bl()
                    if (nc < 0 || nc > 1000) throw DwgFormatException("DWG hatch is invalid")
                    val rgbs = IntArray(nc)
                    for (k in 0 until nc) { r.bd(); r.bs(); rgbs[k] = r.bl() and 0xFFFFFF; r.rc() }
                    val gName = r.tv().uppercase()
                    if (isGradient && nc >= 1) {
                        val c1 = rgbs[0]
                        val c2 = if (nc >= 2 && !singleColor) rgbs[1] else {
                            val target = if (tint >= 0.5) 255 else 0
                            val f = Math.abs(tint - 0.5) * 2
                            fun mix(v: Int) = (v + (target - v) * f).toInt().coerceIn(0, 255)
                            (mix((c1 shr 16) and 255) shl 16) or (mix((c1 shr 8) and 255) shl 8) or mix(c1 and 255)
                        }
                        gradient = doubleArrayOf(gAngle, c1.toDouble(), c2.toDouble(), if (gName.startsWith("INV")) 1.0 else 0.0)
                    }
                }
                val z = r.bd()
                e.ext = r.threeBd()
                r.tv()
                val solid = r.b() == 1
                r.b()
                val np = r.bl()
                if (np < 0 || np > 100000) throw DwgFormatException("DWG hatch is invalid")
                val loops = ArrayList<DoubleArray>()
                for (pi in 0 until np) {
                    val pathFlag = r.bl()
                    val pts = ArrayList<Double>()
                    if ((pathFlag and 2) == 0) {
                        val ns = r.bl()
                        if (ns < 0 || ns > 1_000_000) throw DwgFormatException("DWG hatch is invalid")
                        repeat(ns) {
                            when (r.rc()) {
                                1 -> {
                                    val a = r.twoRd(); val b = r.twoRd()
                                    pts.add(a[0]); pts.add(a[1]); pts.add(b[0]); pts.add(b[1])
                                }
                                2 -> {
                                    val c = r.twoRd(); val rad = r.bd(); val sa = r.bd(); val ea = r.bd(); val ccw = r.b() == 1
                                    arcPoints(c[0], c[1], rad, rad, 0.0, sa, ea, ccw, pts)
                                }
                                3 -> {
                                    val c = r.twoRd(); val ax = r.twoRd(); val ratio = r.bd(); val sa = r.bd(); val ea = r.bd(); val ccw = r.b() == 1
                                    val major = Math.hypot(ax[0], ax[1])
                                    arcPoints(c[0], c[1], major, major * ratio, Math.atan2(ax[1], ax[0]), sa, ea, ccw, pts)
                                }
                                4 -> {
                                    val deg = r.bl()
                                    val rational = r.b() == 1
                                    r.b()
                                    val nk = r.bl()
                                    val nc2 = r.bl()
                                    if (nk < 0 || nk > 100000 || nc2 < 0 || nc2 > 100000) throw DwgFormatException("DWG hatch spline is invalid")
                                    val knots = DoubleArray(nk) { r.bd() }
                                    val ctrl = DoubleArray(nc2 * 3)
                                    val w = DoubleArray(nc2) { 1.0 }
                                    for (k in 0 until nc2) {
                                        val pt = r.twoRd()
                                        ctrl[k * 3] = pt[0]; ctrl[k * 3 + 1] = pt[1]
                                        if (rational) w[k] = r.bd()
                                    }
                                    if (version.atLeast(DwgVersion.R2010)) {
                                        val nf = r.bl()
                                        if (nf < 0 || nf > 100000) throw DwgFormatException("DWG hatch spline is invalid")
                                        if (nf > 0) {
                                            repeat(nf) { r.twoRd() }
                                            r.twoRd(); r.twoRd()
                                        }
                                    }
                                    val sp = evaluateSpline(deg, knots, ctrl, w)
                                    var k = 0
                                    while (k + 2 < sp.size) { pts.add(sp[k]); pts.add(sp[k + 1]); k += 3 }
                                }
                                else -> throw DwgFormatException("DWG hatch edge is invalid")
                            }
                        }
                    } else {
                        val hasBulges = r.b() == 1
                        val closed = r.b() == 1
                        val nv = r.bl()
                        if (nv < 0 || nv > 1_000_000) throw DwgFormatException("DWG hatch is invalid")
                        val vx = DoubleArray(nv); val vy = DoubleArray(nv); val vb = DoubleArray(nv)
                        for (k in 0 until nv) {
                            val pt = r.twoRd()
                            vx[k] = pt[0]; vy[k] = pt[1]
                            if (hasBulges) vb[k] = r.bd()
                        }
                        val count = if (closed) nv else nv - 1
                        for (k in 0 until maxOf(count, 0)) {
                            val n2 = (k + 1) % nv
                            bulgePoints(vx[k], vy[k], vx[n2], vy[n2], vb[k], pts, k == 0)
                        }
                    }
                    val nb = r.bl()
                    if (nb < 0 || nb > 1_000_000) throw DwgFormatException("DWG hatch is invalid")
                    loops.add(pts.toDoubleArray())
                }
                val patterns = ArrayList<DoubleArray>()
                r.bs()
                r.bs()
                if (!solid) {
                    r.bd()
                    r.bd()
                    r.b()
                    val nl = r.bs()
                    if (nl < 0 || nl > 1000) throw DwgFormatException("DWG hatch pattern is invalid")
                    repeat(nl) {
                        val angle = r.bd()
                        val bx = r.bd(); val by = r.bd()
                        val ox = r.bd(); val oy = r.bd()
                        val nd = r.bs()
                        if (nd < 0 || nd > 1000) throw DwgFormatException("DWG hatch pattern is invalid")
                        patterns.add(doubleArrayOf(angle, bx, by, ox, oy) + DoubleArray(nd) { r.bd() })
                    }
                }
                e.d = doubleArrayOf(z) + (gradient ?: DoubleArray(0))
                e.i = intArrayOf(if (solid || gradient != null) 1 else 0)
                e.extra = Pair(loops, patterns)
            }
            "ACAD_TABLE" -> {
                e.p = r.threeBd()
                val scale = if (version.below(DwgVersion.R2000)) r.threeBd() else when (r.bb()) {
                    3 -> doubleArrayOf(1.0, 1.0, 1.0)
                    1 -> doubleArrayOf(1.0, r.dd(1.0), r.dd(1.0))
                    2 -> r.rd().let { doubleArrayOf(it, it, it) }
                    else -> { val x = r.rd(); doubleArrayOf(x, r.dd(x), r.dd(x)) }
                }
                val rotation = r.bd()
                e.ext = r.threeBd()
                e.d = doubleArrayOf(scale[0], scale[1], scale[2], rotation, 0.0, 0.0)
                e.i = intArrayOf(1, 1)
                e.block = hr.ref(e.handle)
            }
            "MULTILEADER", "MLEADER" -> {
                if (version.atLeast(DwgVersion.R2010)) r.bs()
                val roots = r.bl()
                if (roots < 0 || roots > 64) throw DwgFormatException("DWG multileader is invalid")
                val lines = ArrayList<DoubleArray>()
                for (ri in 0 until roots) {
                    r.b(); r.b()
                    val conn = r.threeBd()
                    val dir = r.threeBd()
                    val nb = r.bl()
                    if (nb < 0 || nb > 10000) throw DwgFormatException("DWG multileader is invalid")
                    repeat(nb) { r.threeBd(); r.threeBd() }
                    r.bl()
                    val landing = r.bd()
                    if (landing > 0 && landing.isFinite()) {
                        lines.add(doubleArrayOf(conn[0], conn[1], conn[2], conn[0] + dir[0] * landing, conn[1] + dir[1] * landing, conn[2] + dir[2] * landing))
                    }
                    val nl = r.bl()
                    if (nl < 0 || nl > 10000) throw DwgFormatException("DWG multileader is invalid")
                    for (li in 0 until nl) {
                        val np = r.bl()
                        if (np < 0 || np > 100000) throw DwgFormatException("DWG multileader is invalid")
                        val pts = DoubleArray((np + 1) * 3)
                        for (k in 0 until np) {
                            val pt = r.threeBd()
                            System.arraycopy(pt, 0, pts, k * 3, 3)
                        }
                        System.arraycopy(conn, 0, pts, np * 3, 3)
                        val nbi = r.bl()
                        if (nbi < 0 || nbi > 10000) throw DwgFormatException("DWG multileader is invalid")
                        repeat(nbi) {
                            r.bl()
                            val pairs = r.bl()
                            if (pairs < 0 || pairs > 10000) throw DwgFormatException("DWG multileader is invalid")
                            repeat(pairs) { r.threeBd(); r.threeBd() }
                        }
                        r.bl()
                        if (version.atLeast(DwgVersion.R2010)) {
                            r.bs(); r.tableColor(); r.bl(); r.bd(); r.bl()
                        }
                        lines.add(pts)
                    }
                    if (version.atLeast(DwgVersion.R2010)) r.bs()
                }
                r.bd()
                r.threeBd()
                val textHeight = r.bd()
                r.bd(); r.bd(); r.bs(); r.bs(); r.bs(); r.bs()
                if (r.b() == 1) {
                    e.text = r.tv()
                    e.ext = r.threeBd()
                    e.p = r.threeBd()
                    val dir = r.threeBd()
                    r.bd()
                    val boxWidth = r.bd()
                    r.bd(); r.bd(); r.bs(); r.tableColor()
                    val align = r.bs().coerceIn(1, 3)
                    e.d = doubleArrayOf(textHeight, boxWidth, dir[0], dir[1], dir[2])
                    e.i = intArrayOf(align)
                } else if (r.b() == 1) {
                    r.threeBd(); r.threeBd(); r.threeBd(); r.bd(); r.tableColor()
                    val mtx = DoubleArray(16) { r.bd() }
                    e.extra = Pair(lines, mtx)
                    e.block = -1
                }
                if (e.extra == null) e.extra = Pair(lines, null)
            }
            "RAY", "XLINE" -> {
                e.p = r.threeBd() + r.threeBd()
            }
            "3DSOLID", "REGION", "BODY" -> {
                parseAcisEntity(e, r)
            }
            "PDFUNDERLAY", "DWFUNDERLAY", "DGNUNDERLAY" -> {
                e.ext = r.threeBd()
                val ins = r.threeBd()
                val rotation = r.bd()
                val scale = r.threeBd()
                r.rc(); r.rc(); r.rc()
                val nv = r.bl()
                if (nv < 0 || nv > 100_000) throw DwgFormatException("DWG underlay is invalid")
                val verts = DoubleArray(nv * 2)
                for (k in 0 until nv) { val pt = r.twoRd(); verts[k * 2] = pt[0]; verts[k * 2 + 1] = pt[1] }
                e.p = ins
                e.d = doubleArrayOf(scale[0], scale[1], scale[2], rotation)
                e.extra = verts
            }
            "WIPEOUT", "IMAGE" -> {
                r.bl()
                e.p = r.threeBd() + r.threeBd() + r.threeBd()
                val size = r.twoRd()
                val display = r.bs()
                val clipping = r.b() == 1
                r.rc(); r.rc(); r.rc()
                if (version.atLeast(DwgVersion.R2010)) r.b()
                val clipType = r.bs()
                var verts: DoubleArray
                if (clipType == 1) {
                    val a = r.twoRd(); val b = r.twoRd()
                    verts = doubleArrayOf(a[0], a[1], b[0], a[1], b[0], b[1], a[0], b[1])
                } else {
                    val nv = r.bl()
                    if (nv < 0 || nv > 1_000_000) throw DwgFormatException("DWG image clip is invalid")
                    verts = DoubleArray(nv * 2)
                    for (k in 0 until nv) { val pt = r.twoRd(); verts[k * 2] = pt[0]; verts[k * 2 + 1] = pt[1] }
                }
                if (!clipping || (display and 4) == 0 || verts.size < 6) {
                    verts = doubleArrayOf(-0.5, -0.5, size[0] - 0.5, -0.5, size[0] - 0.5, size[1] - 0.5, -0.5, size[1] - 0.5)
                }
                e.d = doubleArrayOf(size[0], size[1])
                e.i = intArrayOf(display)
                e.extra = verts
                e.block = hr.ref(e.handle)
            }
            "BLOCK", "ENDBLK", "SEQEND", "VIEWPORT" -> return true
            else -> return false
        }
        return true
    }

    private fun readAcisData(r: DwgBitReader): ByteArray? {
        if (r.b() == 1) return null
        r.b()
        val ver = r.bs()
        val out = java.io.ByteArrayOutputStream()
        if (ver == 1) {
            var guard = 0
            while (true) {
                val size = r.bl()
                if (size == 0) break
                if (size < 0 || size > 64 shl 20 || ++guard > 100000) throw DwgFormatException("DWG ACIS block is invalid")
                for (k in 0 until size) {
                    val c = r.rc()
                    out.write(if (c in 0x21..0x7E) 0x9F - c else if (c == 9) 32 else c)
                }
            }
        } else if (ver == 2) {
            val endMarker = byteArrayOf(0x45, 0x6E, 0x64, 0x0E, 0x02, 0x6F, 0x66, 0x0E, 0x04, 0x41, 0x43, 0x49, 0x53, 0x0D, 0x04, 0x64, 0x61, 0x74, 0x61)
            val asmEndMarker = byteArrayOf(0x45, 0x6E, 0x64, 0x0E, 0x02, 0x6F, 0x66, 0x0E, 0x03, 0x41, 0x53, 0x4D, 0x0D, 0x04, 0x64, 0x61, 0x74, 0x61)
            val satEnd = "End-of-ACIS-data".toByteArray()
            val asmSatEnd = "End-of-ASM-data".toByteArray()
            var n = 0
            val tail = ByteArray(32)
            var filled = 0
            while (n < (64 shl 20)) {
                val c = r.rc()
                out.write(c)
                n++
                System.arraycopy(tail, 1, tail, 0, tail.size - 1)
                tail[tail.size - 1] = c.toByte()
                if (filled < tail.size) filled++
                if (endsWith(tail, endMarker) || endsWith(tail, asmEndMarker) || endsWith(tail, satEnd) || endsWith(tail, asmSatEnd)) break
            }
        } else throw DwgFormatException("DWG ACIS version is unsupported")
        return out.toByteArray()
    }

    private fun endsWith(b: ByteArray, m: ByteArray): Boolean {
        if (b.size < m.size) return false
        for (i in m.indices) if (b[b.size - m.size + i] != m[i]) return false
        return true
    }

    private fun parseAcisEntity(e: Entity, r: DwgBitReader) {
        if (e.dsData) {
            e.extra = Pair<ByteArray?, List<DoubleArray>>(null, emptyList())
            return
        }
        val acis = readAcisData(r)
        val wires = ArrayList<DoubleArray>()
        if (r.remaining() > 0 && r.b() == 1) {
            if (r.b() == 1) r.threeBd()
            r.bl()
            r.b()
            val nw = r.bl()
            if (nw < 0 || nw > 1_000_000) throw DwgFormatException("DWG ACIS wires are invalid")
            repeat(nw) { wires.add(readWire(r)) }
            val ns = r.bl()
            if (ns < 0 || ns > 10000) throw DwgFormatException("DWG ACIS silhouettes are invalid")
            repeat(ns) {
                r.bl(); r.threeBd(); r.threeBd(); r.threeBd(); r.b()
                val n2 = r.bl()
                if (n2 < 0 || n2 > 1_000_000) throw DwgFormatException("DWG ACIS silhouettes are invalid")
                repeat(n2) { readWire(r) }
            }
        }
        e.extra = Pair(acis, wires)
    }

    private fun readWire(r: DwgBitReader): DoubleArray {
        r.rc(); r.bl(); r.bs(); r.bl()
        val np = r.bl()
        if (np < 0 || np > 1_000_000) throw DwgFormatException("DWG ACIS wire is invalid")
        val pts = DoubleArray(np * 3)
        for (k in 0 until np) { val pt = r.threeBd(); System.arraycopy(pt, 0, pts, k * 3, 3) }
        if (r.b() == 1) {
            val x = r.threeBd(); val y = r.threeBd(); val z = r.threeBd(); val t = r.threeBd(); val sc = r.bd()
            r.b(); r.b(); r.b()
            val out = DoubleArray(pts.size)
            for (k in 0 until np) {
                val px = pts[k * 3]; val py = pts[k * 3 + 1]; val pz = pts[k * 3 + 2]
                out[k * 3] = (x[0] * px + y[0] * py + z[0] * pz) * sc + t[0]
                out[k * 3 + 1] = (x[1] * px + y[1] * py + z[1] * pz) * sc + t[1]
                out[k * 3 + 2] = (x[2] * px + y[2] * py + z[2] * pz) * sc + t[2]
            }
            return out
        }
        return pts
    }

    private fun arcPoints(cx: Double, cy: Double, rx: Double, ry: Double, rot: Double, sa: Double, ea: Double, ccw: Boolean, out: MutableList<Double>) {
        val start = if (ccw) sa else -sa
        val end = if (ccw) ea else -ea
        val sweep = if (ccw) normalizedSweep(start, end) else normalizedSweep(end, start)
        if (!sweep.isFinite() || !start.isFinite()) return
        val n = maxOf(2, segmentsFor(maxOf(rx, ry), sweep, chordTolerance))
        val c = Math.cos(rot)
        val s = Math.sin(rot)
        for (k in 0..n) {
            val t = if (ccw) start + sweep * k / n else start - sweep * k / n
            val lx = rx * Math.cos(t)
            val ly = ry * Math.sin(t)
            out.add(cx + lx * c - ly * s)
            out.add(cy + lx * s + ly * c)
        }
    }

    private fun bulgePoints(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double, out: MutableList<Double>, first: Boolean) {
        val pts = if (Math.abs(bulge) < 1e-9) doubleArrayOf(x1, y1, x2, y2) else bulgeArc(x1, y1, x2, y2, bulge, chordTolerance)
        val from = if (first) 0 else 1
        for (k in from until pts.size / 2) {
            out.add(pts[k * 2])
            out.add(pts[k * 2 + 1])
        }
    }

    private class Ctx(val insertLayer: Long, val blockColor: Int, val depth: Int)

    private fun load(): CleanRoomDwgReader {
        parseObjectMap()
        parseClasses()
        var failures = 0
        val sorted = offsets.entries.sortedBy { it.key }
        for ((h, off) in sorted) {
            objectCount++
            try {
                parseObject(h, off)
            } catch (_: DwgFormatException) {
                failures++
            } catch (_: IndexOutOfBoundsException) {
                failures++
            }
        }
        parseFailures = failures
        return this
    }

    var parseFailures = 0
        private set



    private fun ocs(ext: DoubleArray): CleanRoomSceneFormats.Matrix4 {
        val len = Math.sqrt(ext[0] * ext[0] + ext[1] * ext[1] + ext[2] * ext[2])
        if (len < 1e-12) return CleanRoomSceneFormats.Matrix4.IDENTITY
        val nx = ext[0] / len; val ny = ext[1] / len; val nz = ext[2] / len
        if (Math.abs(nx) < 1e-12 && Math.abs(ny) < 1e-12 && nz > 0) return CleanRoomSceneFormats.Matrix4.IDENTITY
        var ax: Double; var ay: Double; var az: Double
        if (Math.abs(nx) < 1.0 / 64 && Math.abs(ny) < 1.0 / 64) {
            ax = nz; ay = 0.0; az = -nx
        } else {
            ax = -ny; ay = nx; az = 0.0
        }
        val al = Math.sqrt(ax * ax + ay * ay + az * az)
        ax /= al; ay /= al; az /= al
        val bx = ny * az - nz * ay
        val by = nz * ax - nx * az
        val bz = nx * ay - ny * ax
        return CleanRoomSceneFormats.Matrix4(doubleArrayOf(ax, bx, nx, 0.0, ay, by, ny, 0.0, az, bz, nz, 0.0, 0.0, 0.0, 0.0, 1.0))
    }

    private fun apply(m: CleanRoomSceneFormats.Matrix4, x: Double, y: Double, z: Double): DoubleArray {
        val a = m.m
        return doubleArrayOf(a[0] * x + a[1] * y + a[2] * z + a[3], a[4] * x + a[5] * y + a[6] * z + a[7], a[8] * x + a[9] * y + a[10] * z + a[11])
    }

    private fun layerOf(e: Entity, ctx: Ctx): Long {
        val l = layers[e.layer]
        if (ctx.insertLayer != 0L && (l == null || l.name == "0")) return ctx.insertLayer
        return e.layer
    }

    private fun colorOf(e: Entity, ctx: Ctx): Int {
        val c = e.color
        return when {
            c < 0 -> c
            c == 256 -> layers[layerOf(e, ctx)]?.color ?: 7
            c == 0 -> ctx.blockColor
            c in 1..255 -> c
            else -> 7
        }
    }

    private fun visible(e: Entity, ctx: Ctx): Boolean {
        if (e.invisible) return false
        val l = layers[layerOf(e, ctx)] ?: return true
        return l.visible
    }

    private fun polyline(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, pts: DoubleArray, closed: Boolean, color: Int) {
        val n = pts.size / 3
        if (n < 2) return
        var prev = apply(m, pts[0], pts[1], pts[2])
        val first = prev
        for (k in 1 until n) {
            val cur = apply(m, pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2])
            sink.line(prev[0], prev[1], prev[2], cur[0], cur[1], cur[2], color)
            prev = cur
        }
        if (closed && n > 2 && (prev[0] != first[0] || prev[1] != first[1] || prev[2] != first[2])) sink.line(prev[0], prev[1], prev[2], first[0], first[1], first[2], color)
    }

    private fun triangle(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, p: DoubleArray, a: Int, b: Int, c: Int, color: Int) {
        val pa = apply(m, p[a * 3], p[a * 3 + 1], p[a * 3 + 2])
        val pb = apply(m, p[b * 3], p[b * 3 + 1], p[b * 3 + 2])
        val pc = apply(m, p[c * 3], p[c * 3 + 1], p[c * 3 + 2])
        sink.triangle(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pc[0], pc[1], pc[2], color)
    }

    private fun bulgePolyline(xy: DoubleArray, bulges: DoubleArray, z: Double, closed: Boolean): DoubleArray {
        val n = xy.size / 2
        val out = ArrayList<Double>()
        val segs = if (closed) n else n - 1
        if (n == 1) return doubleArrayOf(xy[0], xy[1], z)
        for (k in 0 until segs) {
            val k2 = (k + 1) % n
            val pts = if (Math.abs(bulges.getOrElse(k) { 0.0 }) < 1e-9) doubleArrayOf(xy[k * 2], xy[k * 2 + 1], xy[k2 * 2], xy[k2 * 2 + 1])
            else bulgeArc(xy[k * 2], xy[k * 2 + 1], xy[k2 * 2], xy[k2 * 2 + 1], bulges[k], chordTolerance)
            val from = if (k == 0) 0 else 1
            for (j in from until pts.size / 2) {
                out.add(pts[j * 2]); out.add(pts[j * 2 + 1]); out.add(z)
            }
        }
        return out.toDoubleArray()
    }

    private fun children(owner: Long): List<Entity> = byOwner[owner].orEmpty().sortedWith(compareBy({ sortKeys[it] ?: it }, { it })).mapNotNull { entities[it] }

    private fun ownedOrChildren(owned: LongArray, owner: Long): List<Entity> {
        if (owned.isNotEmpty()) {
            val list = owned.asList().mapNotNull { entities[it] }
            if (list.isNotEmpty()) return list
        }
        return children(owner)
    }

    private fun blockEntities(b: BlockHeader): List<Entity> = ownedOrChildren(b.owned, b.handle)

    private fun drawBlock(sink: DwgGeometrySink, blockHandle: Long, m: CleanRoomSceneFormats.Matrix4, ctx: Ctx) {
        val b = blocks[blockHandle] ?: return
        for (child in blockEntities(b)) {
            if (sink.isFull) return
            drawEntity(sink, child, m, ctx)
        }
    }

    private fun insertMatrix(e: Entity, m: CleanRoomSceneFormats.Matrix4, base: DoubleArray, col: Int, row: Int): CleanRoomSceneFormats.Matrix4 {
        val d = e.d
        return m * ocs(e.ext) * CleanRoomSceneFormats.Matrix4.translate(e.p[0], e.p[1], e.p[2]) *
            CleanRoomSceneFormats.Matrix4.rotate(0.0, 0.0, 1.0, Math.toDegrees(d[3])) *
            CleanRoomSceneFormats.Matrix4.translate(col * d[4], row * d[5], 0.0) *
            CleanRoomSceneFormats.Matrix4.scale(d[0], d[1], d[2]) *
            CleanRoomSceneFormats.Matrix4.translate(-base[0], -base[1], -base[2])
    }

    private fun emitText(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, value: String, x: Double, y: Double, z: Double, height: Double, rotation: Double, color: Int) {
        if (value.isBlank()) return
        val p0 = apply(m, x, y, z)
        val dir = apply(m, x + Math.cos(rotation), y + Math.sin(rotation), z)
        val up = apply(m, x - Math.sin(rotation), y + Math.cos(rotation), z)
        val angle = Math.toDegrees(Math.atan2(dir[1] - p0[1], dir[0] - p0[0]))
        val scale = Math.sqrt((up[0] - p0[0]).let { it * it } + (up[1] - p0[1]).let { it * it } + (up[2] - p0[2]).let { it * it })
        sink.text(value, p0[0], p0[1], p0[2], height * scale, angle, color)
    }

    private fun drawEntity(sink: DwgGeometrySink, e: Entity, m: CleanRoomSceneFormats.Matrix4, ctx: Ctx) {
        if (!visible(e, ctx) || ctx.depth > 16) return
        val color = colorOf(e, ctx)
        when (e.kind) {
            "LINE" -> if (e.p.size == 6) {
                val a = apply(m, e.p[0], e.p[1], e.p[2])
                val b = apply(m, e.p[3], e.p[4], e.p[5])
                sink.line(a[0], a[1], a[2], b[0], b[1], b[2], color)
                drawn++
            }
            "CIRCLE", "ARC" -> {
                val r = e.d[0]
                if (r > 0 && r.isFinite()) {
                    val start = if (e.kind == "ARC") e.d[1] else 0.0
                    val sweep = if (e.kind == "ARC") normalizedSweep(e.d[1], e.d[2]) else Math.PI * 2
                    if (sweep.isFinite() && start.isFinite()) {
                    val n = maxOf(if (e.kind == "CIRCLE") 8 else 2, segmentsFor(r, sweep, chordTolerance))
                    val pts = DoubleArray((n + 1) * 3)
                    for (k in 0..n) {
                        val a = start + sweep * k / n
                        pts[k * 3] = e.p[0] + r * Math.cos(a); pts[k * 3 + 1] = e.p[1] + r * Math.sin(a); pts[k * 3 + 2] = e.p[2]
                    }
                    polyline(sink, m * ocs(e.ext), pts, false, color)
                    drawn++
                    }
                }
            }
            "ELLIPSE" -> {
                val c = e.p
                val mx = c[3]; val my = c[4]; val mz = c[5]
                val ext = e.ext
                val el = Math.sqrt(ext[0] * ext[0] + ext[1] * ext[1] + ext[2] * ext[2]).let { if (it < 1e-12) 1.0 else it }
                val ratio = e.d[0]
                var nx = (ext[1] * mz - ext[2] * my) / el
                var ny = (ext[2] * mx - ext[0] * mz) / el
                var nz = (ext[0] * my - ext[1] * mx) / el
                nx *= ratio; ny *= ratio; nz *= ratio
                val s0 = e.d[1]
                val sweep = normalizedSweep(e.d[1], e.d[2])
                if (!sweep.isFinite() || !s0.isFinite()) return
                val n = maxOf(8, segmentsFor(Math.sqrt(mx * mx + my * my + mz * mz), sweep, chordTolerance))
                val pts = DoubleArray((n + 1) * 3)
                for (k in 0..n) {
                    val t = s0 + sweep * k / n
                    val ct = Math.cos(t); val st = Math.sin(t)
                    pts[k * 3] = c[0] + mx * ct + nx * st; pts[k * 3 + 1] = c[1] + my * ct + ny * st; pts[k * 3 + 2] = c[2] + mz * ct + nz * st
                }
                polyline(sink, m, pts, false, color)
                drawn++
            }
            "LWPOLYLINE" -> {
                val closed = (e.i[0] and 512) != 0
                val pts = bulgePolyline(e.p, e.d.copyOfRange(1, e.d.size), e.d[0], closed)
                polyline(sink, m * ocs(e.ext), pts, false, color)
                drawn++
            }
            "POLYLINE_2D", "POLYLINE_3D" -> {
                val verts = ownedOrChildren(e.owned, e.handle).filter { it.kind.startsWith("VERTEX") && (it.i.getOrElse(0) { 0 } and 16) == 0 }
                if (verts.size >= 2) {
                    val closed = (e.i[0] and 1) != 0
                    if (e.kind == "POLYLINE_2D") {
                        val xy = DoubleArray(verts.size * 2)
                        val bulges = DoubleArray(verts.size)
                        verts.forEachIndexed { k, v -> xy[k * 2] = v.p[0]; xy[k * 2 + 1] = v.p[1]; bulges[k] = v.d.getOrElse(0) { 0.0 } }
                        polyline(sink, m * ocs(e.ext), bulgePolyline(xy, bulges, e.d[0], closed), false, color)
                    } else {
                        val pts = DoubleArray(verts.size * 3)
                        verts.forEachIndexed { k, v -> System.arraycopy(v.p, 0, pts, k * 3, 3) }
                        polyline(sink, m, pts, closed, color)
                    }
                    drawn++
                }
            }
            "POLYLINE_PFACE" -> {
                val items = ownedOrChildren(e.owned, e.handle)
                val verts = items.filter { it.kind == "VERTEX_PFACE" }
                val pts = DoubleArray(verts.size * 3)
                verts.forEachIndexed { k, v -> System.arraycopy(v.p, 0, pts, k * 3, 3) }
                for (f in items.filter { it.kind == "VERTEX_PFACE_FACE" }) {
                    val idx = f.i.map { Math.abs(it) - 1 }.filter { it in verts.indices }
                    if (idx.size >= 3) {
                        val fc = if (f.color == 256 || f.color == 0) color else colorOf(f, ctx)
                        triangle(sink, m, pts, idx[0], idx[1], idx[2], fc)
                        if (idx.size == 4 && idx[3] != idx[2]) triangle(sink, m, pts, idx[0], idx[2], idx[3], fc)
                    }
                }
                drawn++
            }
            "POLYLINE_MESH" -> {
                val verts = ownedOrChildren(e.owned, e.handle).filter { it.kind == "VERTEX_MESH" }
                val mc = e.i.getOrElse(1) { 0 }
                val nc = e.i.getOrElse(2) { 0 }
                if (mc > 1 && nc > 1 && verts.size >= mc * nc) {
                    val pts = DoubleArray(verts.size * 3)
                    verts.forEachIndexed { k, v -> System.arraycopy(v.p, 0, pts, k * 3, 3) }
                    val closedM = (e.i[0] and 1) != 0
                    val closedN = (e.i[0] and 32) != 0
                    val mi = if (closedM) mc else mc - 1
                    val ni = if (closedN) nc else nc - 1
                    for (a in 0 until mi) for (b in 0 until ni) {
                        val a2 = (a + 1) % mc
                        val b2 = (b + 1) % nc
                        triangle(sink, m, pts, a * nc + b, a2 * nc + b, a2 * nc + b2, color)
                        triangle(sink, m, pts, a * nc + b, a2 * nc + b2, a * nc + b2, color)
                    }
                    drawn++
                }
            }
            "SPLINE", "HELIX" -> {
                @Suppress("UNCHECKED_CAST")
                val parts = e.extra as Array<DoubleArray>
                val pts = if (parts[1].isNotEmpty()) evaluateSpline(e.i[0], parts[0], parts[1], parts[2]) else interpolateFitPoints(parts[3], parts[4])
                polyline(sink, m, pts, false, color)
                drawn++
            }
            "SOLID", "TRACE" -> {
                val om = m * ocs(e.ext)
                triangle(sink, om, e.p, 0, 1, 2, color)
                triangle(sink, om, e.p, 1, 3, 2, color)
                drawn++
            }
            "3DFACE" -> {
                triangle(sink, m, e.p, 0, 1, 2, color)
                val same = e.p[6] == e.p[9] && e.p[7] == e.p[10] && e.p[8] == e.p[11]
                if (!same) triangle(sink, m, e.p, 0, 2, 3, color)
                drawn++
            }
            "TEXT", "ATTRIB", "ATTDEF" -> if (e.kind != "ATTDEF" || ctx.depth == 0) {
                val text = cleanText(e.text ?: "", false)
                val hAlign = e.i.getOrElse(0) { 0 }
                val vAlign = e.i.getOrElse(1) { 0 }
                val height = e.d[0]
                val rot = e.d[1]
                var x = e.p[0]
                var y = e.p[1]
                if ((hAlign != 0 || vAlign != 0) && hAlign != 3 && hAlign != 5 && e.d.size >= 5) {
                    val width = sink.textWidth(text, height) * (e.d[4].takeIf { it > 0 } ?: 1.0)
                    val dx = when (hAlign) { 1, 4 -> -width / 2; 2 -> -width; else -> 0.0 }
                    val dy = when {
                        hAlign == 4 -> -height / 2
                        vAlign == 1 -> height * 0.2
                        vAlign == 2 -> -height / 2
                        vAlign == 3 -> -height
                        else -> 0.0
                    }
                    x = e.d[2] + dx * Math.cos(rot) - dy * Math.sin(rot)
                    y = e.d[3] + dx * Math.sin(rot) + dy * Math.cos(rot)
                }
                emitText(sink, m * ocs(e.ext), text, x, y, e.p[2], height, rot, color)
                drawn++
            }
            "MTEXT" -> {
                drawMText(sink, e, m, color)
                drawn++
            }
            "INSERT", "MINSERT", "ACAD_TABLE" -> {
                val b = blocks[e.block]
                if (b != null) {
                    val cols = e.i.getOrElse(0) { 1 }.coerceIn(1, 1000)
                    val rows = e.i.getOrElse(1) { 1 }.coerceIn(1, 1000)
                    val child = Ctx(layerOf(e, ctx), color, ctx.depth + 1)
                    var count = 0
                    loop@ for (r in 0 until rows) for (c in 0 until cols) {
                        if (++count > 10000 || sink.isFull) break@loop
                        drawBlock(sink, b.handle, insertMatrix(e, m, b.base, c, r), child)
                    }
                    drawn++
                }
                for (a in ownedOrChildren(if (e.owned.size > 2 || version.atLeast(DwgVersion.R2004)) e.owned else LongArray(0), e.handle)) {
                    if (a.kind == "ATTRIB") drawEntity(sink, a, m, ctx)
                }
            }
            "DIMENSION", "ARC_DIMENSION", "LARGE_RADIAL_DIMENSION" -> {
                if (blocks.containsKey(e.block)) {
                    drawBlock(sink, e.block, m, Ctx(layerOf(e, ctx), color, ctx.depth + 1))
                    drawn++
                }
            }
            "3DSOLID", "REGION", "BODY" -> {
                @Suppress("UNCHECKED_CAST")
                val pr = e.extra as? Pair<ByteArray?, List<DoubleArray>>
                pr?.second?.forEach { polyline(sink, m, it, false, color) }
                val geo = acisGeometry(e.handle, pr?.first ?: if (e.dsData) dataStore()[e.handle] else null)
                if (geo != null) {
                    for (l in geo.lines) polyline(sink, m, l, false, color)
                    for (face in geo.faces) fillPlanarFace(sink, m, face, color)
                    val t = geo.triangles
                    for (k in 0 until t.size / 9) triangle(sink, m, t, k * 3, k * 3 + 1, k * 3 + 2, color)
                }
                if (geo != null || !pr?.second.isNullOrEmpty()) drawn++
            }
            "PDFUNDERLAY", "DWFUNDERLAY", "DGNUNDERLAY" -> {
                val verts = e.extra as? DoubleArray
                if (verts != null && verts.size >= 6 && e.p.size == 3 && e.d.size == 4) {
                    val c = Math.cos(e.d[3]); val sn = Math.sin(e.d[3])
                    val n = verts.size / 2
                    val pts = DoubleArray(n * 3)
                    for (k in 0 until n) {
                        val x = verts[k * 2] * e.d[0]; val y = verts[k * 2 + 1] * e.d[1]
                        pts[k * 3] = e.p[0] + x * c - y * sn
                        pts[k * 3 + 1] = e.p[1] + x * sn + y * c
                        pts[k * 3 + 2] = e.p[2]
                    }
                    polyline(sink, m * ocs(e.ext), pts, true, color)
                    drawn++
                }
            }
            "WIPEOUT", "IMAGE" -> {
                @Suppress("UNCHECKED_CAST")
                val verts = e.extra as? DoubleArray
                if (verts != null && verts.size >= 6 && e.p.size == 9) {
                    val sizeY = e.d.getOrElse(1) { 1.0 }
                    val n = verts.size / 2
                    val world = DoubleArray(n * 3)
                    for (k in 0 until n) {
                        val fu = verts[k * 2] + 0.5
                        val fv = sizeY - 0.5 - verts[k * 2 + 1]
                        val w = apply(m, e.p[0] + fu * e.p[3] + fv * e.p[6], e.p[1] + fu * e.p[4] + fv * e.p[7], e.p[2] + fu * e.p[5] + fv * e.p[8])
                        world[k * 3] = w[0]; world[k * 3 + 1] = w[1]; world[k * 3 + 2] = w[2]
                    }
                    if (e.kind == "WIPEOUT") (sink as? MaskingSink)?.addMask(world)
                    if (e.kind == "IMAGE" || wipeoutFrame) polyline(sink, CleanRoomSceneFormats.Matrix4.IDENTITY, world, true, color)
                    if (e.kind == "IMAGE") {
                        val name = imageFiles[e.block]?.substringAfterLast('/')?.substringAfterLast('\\')
                        if (!name.isNullOrBlank()) {
                            val h = Math.sqrt(e.p[6] * e.p[6] + e.p[7] * e.p[7] + e.p[8] * e.p[8]) * sizeY * 0.04
                            val o = apply(m, e.p[0], e.p[1], e.p[2])
                            sink.text(name, o[0] + h * 0.5, o[1] + h * 0.5, o[2], h, 0.0, color)
                        }
                    }
                    drawn++
                }
            }
            "LEADER" -> {
                val pts = if (e.i.getOrElse(0) { 0 } == 1 && e.p.size >= 9) interpolateFitPoints(e.p, DoubleArray(6)) else e.p
                polyline(sink, m, pts, false, color)
                drawn++
            }
            "RAY", "XLINE" -> if (e.p.size == 6) {
                val len = if (sceneScale > 0) sceneScale * 2 else 1000.0
                val dl = Math.sqrt(e.p[3] * e.p[3] + e.p[4] * e.p[4] + e.p[5] * e.p[5]).takeIf { it > 1e-12 } ?: 1.0
                val ux = e.p[3] / dl * len; val uy = e.p[4] / dl * len; val uz = e.p[5] / dl * len
                val back = if (e.kind == "XLINE") 1.0 else 0.0
                val a = apply(m, e.p[0] - ux * back, e.p[1] - uy * back, e.p[2] - uz * back)
                val b = apply(m, e.p[0] + ux, e.p[1] + uy, e.p[2] + uz)
                sink.line(a[0], a[1], a[2], b[0], b[1], b[2], color)
                drawn++
            }
            "POINT" -> if (e.p.size >= 3) {
                val s = if (sceneScale > 0) sceneScale / 400 else 0.5
                val a = apply(m, e.p[0] - s, e.p[1], e.p[2]); val b = apply(m, e.p[0] + s, e.p[1], e.p[2])
                val c = apply(m, e.p[0], e.p[1] - s, e.p[2]); val d = apply(m, e.p[0], e.p[1] + s, e.p[2])
                sink.line(a[0], a[1], a[2], b[0], b[1], b[2], color)
                sink.line(c[0], c[1], c[2], d[0], d[1], d[2], color)
                drawn++
            }
            "MULTILEADER", "MLEADER" -> {
                @Suppress("UNCHECKED_CAST")
                val data = e.extra as? Pair<List<DoubleArray>, DoubleArray?>
                data?.first?.forEach { polyline(sink, m, it, false, color) }
                if (e.text != null && e.p.size >= 3) drawMText(sink, e, m, color)
                drawn++
            }
            "MLINE" -> {
                @Suppress("UNCHECKED_CAST")
                val paths = e.extra as Array<DoubleArray>
                for (path in paths) polyline(sink, m, path, e.i[0] == 1, color)
                drawn++
            }
            "HATCH" -> {
                @Suppress("UNCHECKED_CAST")
                val data = e.extra as Pair<List<DoubleArray>, List<DoubleArray>>
                val loops = data.first
                val om = m * ocs(e.ext)
                val z = e.d[0]
                for (loop in loops) {
                    val n = loop.size / 2
                    if (n < 2) continue
                    val pts = DoubleArray(n * 3)
                    for (k in 0 until n) { pts[k * 3] = loop[k * 2]; pts[k * 3 + 1] = loop[k * 2 + 1]; pts[k * 3 + 2] = z }
                    polyline(sink, om, pts, true, color)
                }
                if (e.i[0] == 1) fillEvenOdd(sink, om, loops, z, color, if (e.d.size >= 5) e.d.copyOfRange(1, 5) else null)
                else hatchPattern(sink, om, loops, data.second, z, color)
                drawn++
            }
        }
    }

    private fun drawMText(sink: DwgGeometrySink, e: Entity, m: CleanRoomSceneFormats.Matrix4, color: Int) {
        val lines = cleanText(e.text ?: "", true).split('\n').map { it.trimEnd() }
        val h = e.d[0]
        var xdx = e.d[2]; var xdy = e.d[3]; var xdz = e.d[4]
        if (Math.abs(xdx) + Math.abs(xdy) + Math.abs(xdz) < 1e-12) { xdx = 1.0; xdy = 0.0; xdz = 0.0 }
        val o = ocs(e.ext)
        val inv = o.m
        val lx = inv[0] * xdx + inv[4] * xdy + inv[8] * xdz
        val ly = inv[1] * xdx + inv[5] * xdy + inv[9] * xdz
        val rot = Math.atan2(ly, lx)
        val ins = e.p
        val local = doubleArrayOf(inv[0] * ins[0] + inv[4] * ins[1] + inv[8] * ins[2], inv[1] * ins[0] + inv[5] * ins[1] + inv[9] * ins[2], inv[2] * ins[0] + inv[6] * ins[1] + inv[10] * ins[2])
        val attach = e.i.getOrElse(0) { 1 }.coerceIn(1, 9)
        val lineStep = h * 1.6667
        val total = lineStep * (lines.size - 1) + h
        val topOffset = when ((attach - 1) / 3) {
            0 -> -h
            1 -> total / 2 - h
            else -> total - h
        }
        val cosR = Math.cos(rot); val sinR = Math.sin(rot)
        for ((k, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val width = sink.textWidth(line, h)
            val dxl = when ((attach - 1) % 3) {
                0 -> 0.0
                1 -> -width / 2
                else -> -width
            }
            val dyl = topOffset - k * lineStep
            val px = local[0] + dxl * cosR - dyl * sinR
            val py = local[1] + dxl * sinR + dyl * cosR
            emitText(sink, m * o, line, px, py, local[2], h, rot, color)
        }
    }

    private val acisCache = HashMap<Long, CleanRoomAcis.Geometry?>()
    private var dataStoreMap: Map<Long, ByteArray>? = null

    private fun dataStore(): Map<Long, ByteArray> {
        dataStoreMap?.let { return it }
        val map = try {
            sections.dataStore?.let { CleanRoomDwgContainer.readDataStore(it) } ?: emptyMap()
        } catch (_: DwgFormatException) {
            emptyMap()
        } catch (_: IndexOutOfBoundsException) {
            emptyMap()
        }
        dataStoreMap = map
        return map
    }

    private fun acisGeometry(handle: Long, data: ByteArray?): CleanRoomAcis.Geometry? {
        if (data == null || data.isEmpty()) return null
        if (acisCache.containsKey(handle)) return acisCache[handle]
        val g = try {
            CleanRoomAcis.build(CleanRoomAcis.parse(data))
        } catch (_: DwgFormatException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
        acisCache[handle] = g
        return g
    }

    private fun fillPlanarFace(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, loops: List<DoubleArray>, color: Int) {
        val l0 = loops.firstOrNull() ?: return
        val n0 = l0.size / 3
        if (n0 < 3) return
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (k in 0 until n0) {
            val j = (k + 1) % n0
            nx += (l0[k * 3 + 1] - l0[j * 3 + 1]) * (l0[k * 3 + 2] + l0[j * 3 + 2])
            ny += (l0[k * 3 + 2] - l0[j * 3 + 2]) * (l0[k * 3] + l0[j * 3])
            nz += (l0[k * 3] - l0[j * 3]) * (l0[k * 3 + 1] + l0[j * 3 + 1])
        }
        val nl = Math.sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 1e-18) return
        nx /= nl; ny /= nl; nz /= nl
        var ax: Double; var ay: Double; var az: Double
        if (Math.abs(nx) < 0.9) { ax = 0.0; ay = -nz; az = ny } else { ax = nz; ay = 0.0; az = -nx }
        val al = Math.sqrt(ax * ax + ay * ay + az * az)
        ax /= al; ay /= al; az /= al
        val bx = ny * az - nz * ay; val by = nz * ax - nx * az; val bz = nx * ay - ny * ax
        val ox = l0[0]; val oy = l0[1]; val oz = l0[2]
        val flat = loops.map { l ->
            val n = l.size / 3
            DoubleArray(n * 2).also { out ->
                for (k in 0 until n) {
                    val dx = l[k * 3] - ox; val dy = l[k * 3 + 1] - oy; val dz = l[k * 3 + 2] - oz
                    out[k * 2] = dx * ax + dy * ay + dz * az
                    out[k * 2 + 1] = dx * bx + dy * by + dz * bz
                }
            }
        }
        val plane = CleanRoomSceneFormats.Matrix4(doubleArrayOf(ax, bx, nx, ox, ay, by, ny, oy, az, bz, nz, oz, 0.0, 0.0, 0.0, 1.0))
        fillEvenOdd(sink, m * plane, flat, 0.0, color, null)
    }

    private fun boundaryEdges(loops: List<DoubleArray>): DoubleArray {
        var count = 0
        for (l in loops) if (l.size >= 6) count += l.size / 2
        val out = DoubleArray(count * 4)
        var o = 0
        for (l in loops) {
            val n = l.size / 2
            if (n < 3) continue
            for (k in 0 until n) {
                val k2 = (k + 1) % n
                out[o++] = l[k * 2]; out[o++] = l[k * 2 + 1]; out[o++] = l[k2 * 2]; out[o++] = l[k2 * 2 + 1]
            }
        }
        return out.copyOf(o)
    }

    private fun fillEvenOdd(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, loops: List<DoubleArray>, z: Double, color: Int, gradient: DoubleArray?) {
        val raw = boundaryEdges(loops)
        val ne = raw.size / 4
        if (ne < 3 || ne > 50_000) return
        val gx = if (gradient != null) Math.cos(gradient[0]) else 0.0
        val gy = if (gradient != null) Math.sin(gradient[0]) else 1.0
        val edges = DoubleArray(raw.size)
        for (k in 0 until ne * 2) {
            val x = raw[k * 2]; val y = raw[k * 2 + 1]
            edges[k * 2] = -x * gy + y * gx
            edges[k * 2 + 1] = x * gx + y * gy
        }
        val levels = ArrayList<Double>(ne * 2 + 40)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in 0 until ne * 2) {
            val v = edges[k * 2 + 1]
            if (!v.isFinite()) return
            levels.add(v)
            lo = minOf(lo, v); hi = maxOf(hi, v)
        }
        val span = (hi - lo).takeIf { it > 1e-12 } ?: 1.0
        if (gradient != null) for (j in 1 until 32) levels.add(lo + span * j / 32)
        levels.sort()
        val uniq = ArrayList<Double>()
        for (v in levels) if (uniq.isEmpty() || v - uniq.last() > 1e-12) uniq.add(v)
        if (uniq.size.toLong() * ne > 60_000_000L) return
        fun colorAt(level: Double): Int {
            if (gradient == null) return color
            var f = ((level - lo) / span).coerceIn(0.0, 1.0)
            if (gradient[3] == 1.0) f = 1 - f
            val c1 = gradient[1].toInt(); val c2 = gradient[2].toInt()
            fun ch(sh: Int) = (((c1 shr sh) and 255) + ((((c2 shr sh) and 255) - ((c1 shr sh) and 255)) * f)).toInt().coerceIn(0, 255)
            return Int.MIN_VALUE or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val xs0 = DoubleArray(ne)
        val xs1 = DoubleArray(ne)
        val mids = DoubleArray(ne)
        val tri = DoubleArray(12)
        fun put(i: Int, u: Double, v: Double) {
            tri[i * 3] = u * -gy + v * gx
            tri[i * 3 + 1] = u * gx + v * gy
            tri[i * 3 + 2] = z
        }
        for (si in 0 until uniq.size - 1) {
            if (sink.isFull) return
            val y0 = uniq[si]
            val y1 = uniq[si + 1]
            var c = 0
            for (k in 0 until ne) {
                val ay = edges[k * 4 + 1]; val by = edges[k * 4 + 3]
                val lo2 = minOf(ay, by); val hi2 = maxOf(ay, by)
                if (lo2 > y0 + 1e-12 || hi2 < y1 - 1e-12 || hi2 - lo2 < 1e-12) continue
                val ax = edges[k * 4]; val bx = edges[k * 4 + 2]
                xs0[c] = ax + (bx - ax) * (y0 - ay) / (by - ay)
                xs1[c] = ax + (bx - ax) * (y1 - ay) / (by - ay)
                mids[c] = (xs0[c] + xs1[c]) / 2
                c++
            }
            if (c < 2) continue
            val sorted = (0 until c).sortedBy { mids[it] }
            val cc = colorAt((y0 + y1) / 2)
            var k = 0
            while (k + 1 < c) {
                val l = sorted[k]; val r = sorted[k + 1]
                put(0, xs0[l], y0); put(1, xs0[r], y0); put(2, xs1[r], y1); put(3, xs1[l], y1)
                triangle(sink, m, tri, 0, 1, 2, cc)
                triangle(sink, m, tri, 0, 2, 3, cc)
                k += 2
            }
        }
    }

    private fun hatchPattern(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, loops: List<DoubleArray>, patterns: List<DoubleArray>, z: Double, color: Int) {
        val edges = boundaryEdges(loops)
        val ne = edges.size / 4
        if (ne < 3 || patterns.isEmpty()) return
        var budget = 400_000
        val hits = DoubleArray(ne)
        for (pat in patterns) {
            val angle = pat[0]
            if (!angle.isFinite()) continue
            val dx = Math.cos(angle); val dy = Math.sin(angle)
            val nx = -dy; val ny = dx
            val bx = pat[1]; val by = pat[2]
            val spacing = pat[3] * nx + pat[4] * ny
            if (!spacing.isFinite() || Math.abs(spacing) < 1e-12) continue
            var dmin = Double.MAX_VALUE; var dmax = -Double.MAX_VALUE
            for (k in 0 until ne) {
                val d = edges[k * 4] * nx + edges[k * 4 + 1] * ny
                if (d < dmin) dmin = d
                if (d > dmax) dmax = d
            }
            val base = bx * nx + by * ny
            val kA = Math.ceil(minOf((dmin - base) / spacing, (dmax - base) / spacing)).toLong()
            val kB = Math.floor(maxOf((dmin - base) / spacing, (dmax - base) / spacing)).toLong()
            if (kB - kA > 20_000) continue
            val dashes = pat.copyOfRange(5, pat.size)
            val period = dashes.sumOf { Math.abs(it) }
            for (kk in kA..kB) {
                if (budget <= 0 || sink.isFull) return
                val ox = bx + kk * pat[3]
                val oy = by + kk * pat[4]
                var c = 0
                for (k in 0 until ne) {
                    val px = edges[k * 4]; val py = edges[k * 4 + 1]
                    val qx = edges[k * 4 + 2] - px; val qy = edges[k * 4 + 3] - py
                    val den = dx * qy - dy * qx
                    if (Math.abs(den) < 1e-15) continue
                    val wx = px - ox; val wy = py - oy
                    val u = (wx * dy - wy * dx) / den
                    if (u < 0 || u >= 1) continue
                    hits[c++] = (wx * qy - wy * qx) / den
                }
                if (c < 2) continue
                java.util.Arrays.sort(hits, 0, c)
                var h = 0
                while (h + 1 < c) {
                    val ta = hits[h]; val tb = hits[h + 1]
                    h += 2
                    if (tb - ta < 1e-12) continue
                    if (dashes.isEmpty() || period < 1e-12) {
                        emitHatchSegment(sink, m, ox, oy, dx, dy, ta, tb, z, color)
                        budget--
                        continue
                    }
                    var t = Math.floor(ta / period) * period
                    var guard = 0
                    while (t < tb && guard++ < 100_000) {
                        for (dash in dashes) {
                            val len = Math.abs(dash)
                            if (dash > 0) {
                                val s0 = maxOf(t, ta); val s1 = minOf(t + len, tb)
                                if (s1 > s0) { emitHatchSegment(sink, m, ox, oy, dx, dy, s0, s1, z, color); budget-- }
                            } else if (dash == 0.0 && t >= ta && t <= tb) {
                                val dot = period * 0.02
                                emitHatchSegment(sink, m, ox, oy, dx, dy, t, minOf(t + dot, tb), z, color)
                                budget--
                            }
                            t += len
                            if (t >= tb) break
                        }
                        if (budget <= 0) return
                    }
                }
            }
        }
    }

    private fun emitHatchSegment(sink: DwgGeometrySink, m: CleanRoomSceneFormats.Matrix4, ox: Double, oy: Double, dx: Double, dy: Double, t0: Double, t1: Double, z: Double, color: Int) {
        val a = apply(m, ox + dx * t0, oy + dy * t0, z)
        val b = apply(m, ox + dx * t1, oy + dy * t1, z)
        sink.line(a[0], a[1], a[2], b[0], b[1], b[2], color)
    }

    private fun estimateScale(list: List<Entity>): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (e in list) {
            if (e.kind in setOf("XLINE", "RAY", "VIEWPORT", "POINT") || e.p.size < 2) continue
            var k = 0
            val stride = if (e.kind == "LWPOLYLINE") 2 else 3
            while (k + 1 < e.p.size && xs.size < 200_000) {
                val x = e.p[k]; val y = e.p[k + 1]
                if (x.isFinite() && y.isFinite()) { xs.add(x); ys.add(y) }
                k += stride * maxOf(1, e.p.size / (stride * 64))
            }
        }
        if (xs.size < 2) return 0.0
        xs.sort(); ys.sort()
        fun pct(v: List<Double>, q: Double) = v[((v.size - 1) * q).toInt()]
        val w = pct(xs, 0.98) - pct(xs, 0.02)
        val h = pct(ys, 0.98) - pct(ys, 0.02)
        return maxOf(w, h)
    }

    fun render(sink: DwgGeometrySink): DwgReadResult {
        val model = blocks.values.firstOrNull { it.name.equals("*Model_Space", ignoreCase = true) }
        val paper = blocks.values.firstOrNull { it.name.equals("*Paper_Space", ignoreCase = true) }
        var list = byOwner[MODEL_KEY].orEmpty().mapNotNull { entities[it] } + (model?.let { blockEntities(it) } ?: emptyList())
        if (list.none { it.kind !in setOf("VIEWPORT", "POINT") }) {
            list = byOwner[PAPER_KEY].orEmpty().mapNotNull { entities[it] } + (paper?.let { blockEntities(it) } ?: emptyList())
        }
        val scale = estimateScale(list)
        sceneScale = if (scale > 0 && scale.isFinite()) scale else 0.0
        chordTolerance = sceneScale / 20000.0
        val seen = HashSet<Long>()
        val ctx = Ctx(0L, 7, 0)
        val masking = if (entities.values.any { it.kind == "WIPEOUT" }) MaskingSink(sink) else null
        val target: DwgGeometrySink = masking ?: sink
        for (e in list.sortedWith(compareBy({ sortKeys[it.handle] ?: it.handle }, { it.handle }))) {
            if (!seen.add(e.handle)) continue
            if (target.isFull) break
            drawEntity(target, e, CleanRoomSceneFormats.Matrix4.IDENTITY, ctx)
        }
        masking?.flush()
        return DwgReadResult(version, objectCount, entities.size, drawn, unsupported.toMap())
    }






    companion object {
        private const val MODEL_KEY = -1L
        private const val PAPER_KEY = -2L

        fun open(bytes: ByteArray, password: String? = null): CleanRoomDwgReader =
            CleanRoomDwgReader(CleanRoomDwgContainer.open(bytes, password)).load()

        fun cleanText(raw: String, mtext: Boolean): String {
            var s = raw
            s = Regex("\\\\U\\+([0-9A-Fa-f]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            s = Regex("%%(\\d{3})").replace(s) { it.groupValues[1].toInt().toChar().toString() }
            s = s.replace(Regex("%%[dD]"), "\u00B0").replace(Regex("%%[pP]"), "\u00B1").replace(Regex("%%[cC]"), "\u2300")
                .replace(Regex("%%[uUoOkK]"), "").replace("%%%", "%")
            if (!mtext) return s
            s = s.replace("\\\\", "\u0000")
            s = s.replace(Regex("\\\\[Pp]"), "\n").replace("\\~", " ")
            s = Regex("\\\\S([^;]*?)[\\^/#]([^;]*?);").replace(s) { it.groupValues[1] + "/" + it.groupValues[2] }
            s = s.replace(Regex("\\\\[ACcFfHhQqTtWwp][^;]*;"), "")
            s = s.replace(Regex("\\\\[LlOoKkNX]"), "")
            s = s.replace("{", "").replace("}", "")
            return s.replace("\u0000", "\\")
        }

        fun codepageCharset(cp: Int): Charset {
            val name = when (cp) {
                2 -> "ISO-8859-1"; 3 -> "ISO-8859-2"; 4 -> "ISO-8859-3"; 5 -> "ISO-8859-4"; 6 -> "ISO-8859-5"
                7 -> "ISO-8859-6"; 8 -> "ISO-8859-7"; 9 -> "ISO-8859-8"; 10 -> "ISO-8859-9"; 11 -> "IBM437"
                12 -> "IBM850"; 13 -> "IBM852"; 14 -> "IBM855"; 15 -> "IBM857"; 16 -> "IBM860"; 17 -> "IBM861"
                18 -> "IBM863"; 19 -> "IBM864"; 20 -> "IBM865"; 21 -> "IBM869"; 22 -> "Shift_JIS"; 23 -> "x-MacRoman"
                24 -> "Big5"; 25 -> "EUC-KR"; 26 -> "x-Johab"; 27 -> "IBM866"; 28 -> "windows-1250"; 29 -> "windows-1251"
                31 -> "GB2312"; 32 -> "windows-1253"; 33 -> "windows-1254"; 34 -> "windows-1255"; 35 -> "windows-1256"
                36 -> "windows-1257"; 37 -> "x-windows-874"; 38 -> "windows-31j"; 39 -> "GBK"; 40 -> "x-windows-949"
                41 -> "x-windows-950"; 42 -> "x-Johab"; 44 -> "windows-1258"
                else -> "windows-1252"
            }
            return try { Charset.forName(name) } catch (_: Exception) { Charsets.ISO_8859_1 }
        }

        fun normalizedSweep(start: Double, end: Double): Double {
            if (!start.isFinite() || !end.isFinite()) return Double.NaN
            var s = Math.IEEEremainder(end - start, Math.PI * 2)
            if (s <= 1e-12) s += Math.PI * 2
            return s
        }

        fun segmentsFor(radius: Double, sweepIn: Double, tol: Double): Int {
            if (!sweepIn.isFinite()) return 1
            val sweep = sweepIn.coerceIn(-Math.PI * 4, Math.PI * 4)
            val full = Math.abs(sweep) / (Math.PI * 2)
            val byAngle = maxOf(1, Math.ceil(full * 72).toInt())
            if (tol <= 0 || radius <= 0 || !radius.isFinite()) return maxOf(2, byAngle)
            val ratio = 1 - tol / radius
            if (ratio <= -1) return 1
            val step = 2 * Math.acos(ratio.coerceIn(-1.0, 1.0))
            val byTol = if (step <= 0) byAngle else Math.ceil(Math.abs(sweep) / step).toInt()
            return byTol.coerceIn(1, maxOf(1, byAngle))
        }

        fun bulgeArc(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double, tol: Double = 0.0): DoubleArray {
            val dx = x2 - x1
            val dy = y2 - y1
            val chord = Math.hypot(dx, dy)
            if (chord < 1e-12 || !chord.isFinite() || Math.abs(bulge) < 1e-12 || !bulge.isFinite()) return doubleArrayOf(x1, y1, x2, y2)
            val theta = 4 * Math.atan(bulge)
            val h = (chord / 2) * (1 - bulge * bulge) / (2 * bulge)
            val cx = (x1 + x2) / 2 - dy / chord * h
            val cy = (y1 + y2) / 2 + dx / chord * h
            val radius = Math.hypot(x1 - cx, y1 - cy)
            val a1 = Math.atan2(y1 - cy, x1 - cx)
            val n = segmentsFor(radius, theta, tol)
            val out = DoubleArray((n + 1) * 2)
            for (k in 0..n) {
                val a = a1 + theta * k / n
                out[k * 2] = cx + radius * Math.cos(a)
                out[k * 2 + 1] = cy + radius * Math.sin(a)
            }
            out[0] = x1; out[1] = y1; out[n * 2] = x2; out[n * 2 + 1] = y2
            return out
        }

        fun interpolateFitPoints(fit: DoubleArray, tangents: DoubleArray): DoubleArray {
            val n = fit.size / 3
            if (n < 3) return fit.copyOf()
            val t = DoubleArray(n)
            for (k in 1 until n) {
                val dx = fit[k * 3] - fit[k * 3 - 3]; val dy = fit[k * 3 + 1] - fit[k * 3 - 2]; val dz = fit[k * 3 + 2] - fit[k * 3 - 1]
                t[k] = t[k - 1] + maxOf(1e-12, Math.sqrt(dx * dx + dy * dy + dz * dz))
            }
            val m = DoubleArray(n * 3)
            for (axis in 0 until 3) {
                val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n); val d = DoubleArray(n)
                fun p(k: Int) = fit[k * 3 + axis]
                val beg = tangents.getOrElse(axis) { 0.0 }
                val end = tangents.getOrElse(3 + axis) { 0.0 }
                val begLen = Math.sqrt(tangents[0] * tangents[0] + tangents[1] * tangents[1] + tangents[2] * tangents[2])
                val endLen = Math.sqrt(tangents[3] * tangents[3] + tangents[4] * tangents[4] + tangents[5] * tangents[5])
                val h0 = t[1] - t[0]
                if (begLen > 1e-12) { b[0] = 1.0; d[0] = beg / begLen } else { b[0] = 2.0; c[0] = 1.0; d[0] = 3 * (p(1) - p(0)) / h0 }
                for (k in 1 until n - 1) {
                    val hp = t[k] - t[k - 1]; val hn = t[k + 1] - t[k]
                    a[k] = hn; b[k] = 2 * (hp + hn); c[k] = hp
                    d[k] = 3 * (hn * (p(k) - p(k - 1)) / hp + hp * (p(k + 1) - p(k)) / hn)
                }
                val hl = t[n - 1] - t[n - 2]
                if (endLen > 1e-12) { b[n - 1] = 1.0; d[n - 1] = end / endLen } else { a[n - 1] = 1.0; b[n - 1] = 2.0; d[n - 1] = 3 * (p(n - 1) - p(n - 2)) / hl }
                for (k in 1 until n) {
                    val w = a[k] / b[k - 1]
                    b[k] -= w * c[k - 1]
                    d[k] -= w * d[k - 1]
                }
                m[(n - 1) * 3 + axis] = d[n - 1] / b[n - 1]
                for (k in n - 2 downTo 0) m[k * 3 + axis] = (d[k] - c[k] * m[(k + 1) * 3 + axis]) / b[k]
            }
            val per = 16
            val out = DoubleArray(((n - 1) * per + 1) * 3)
            var o = 0
            for (k in 0 until n - 1) {
                val h = t[k + 1] - t[k]
                for (j in 0 until per) {
                    val s = j.toDouble() / per
                    val h00 = 2 * s * s * s - 3 * s * s + 1; val h10 = s * s * s - 2 * s * s + s
                    val h01 = -2 * s * s * s + 3 * s * s; val h11 = s * s * s - s * s
                    for (axis in 0 until 3) {
                        out[o++] = h00 * fit[k * 3 + axis] + h10 * h * m[k * 3 + axis] + h01 * fit[(k + 1) * 3 + axis] + h11 * h * m[(k + 1) * 3 + axis]
                    }
                }
            }
            for (axis in 0 until 3) out[o++] = fit[(n - 1) * 3 + axis]
            return out
        }

        fun evaluateSpline(degree: Int, knots: DoubleArray, ctrl: DoubleArray, weights: DoubleArray): DoubleArray {
            val n = ctrl.size / 3
            if (n == 0) return DoubleArray(0)
            if (degree < 1 || knots.size != n + degree + 1) return ctrl.copyOf()
            val t0 = knots[degree]
            val t1 = knots[n]
            if (!(t1 > t0)) return ctrl.copyOf()
            val samples = minOf(4000, maxOf(16, n * 12))
            val out = DoubleArray((samples + 1) * 3)
            val dx = DoubleArray(degree + 1)
            val dy = DoubleArray(degree + 1)
            val dz = DoubleArray(degree + 1)
            val dw = DoubleArray(degree + 1)
            for (s in 0..samples) {
                val t = if (s == samples) t1 else t0 + (t1 - t0) * s / samples
                var k = degree
                while (k < n - 1 && t >= knots[k + 1]) k++
                for (j in 0..degree) {
                    val idx = k - degree + j
                    val w = weights.getOrElse(idx) { 1.0 }
                    dx[j] = ctrl[idx * 3] * w; dy[j] = ctrl[idx * 3 + 1] * w; dz[j] = ctrl[idx * 3 + 2] * w; dw[j] = w
                }
                for (r in 1..degree) {
                    for (j in degree downTo r) {
                        val i = k - degree + j
                        val denom = knots[i + degree - r + 1] - knots[i]
                        val alpha = if (denom == 0.0) 0.0 else (t - knots[i]) / denom
                        dx[j] = (1 - alpha) * dx[j - 1] + alpha * dx[j]
                        dy[j] = (1 - alpha) * dy[j - 1] + alpha * dy[j]
                        dz[j] = (1 - alpha) * dz[j - 1] + alpha * dz[j]
                        dw[j] = (1 - alpha) * dw[j - 1] + alpha * dw[j]
                    }
                }
                val w = if (dw[degree] == 0.0) 1.0 else dw[degree]
                out[s * 3] = dx[degree] / w; out[s * 3 + 1] = dy[degree] / w; out[s * 3 + 2] = dz[degree] / w
            }
            return out
        }
    }
}
