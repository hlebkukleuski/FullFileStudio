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
 * ACIS SAT/SAB B-Rep record parsing informed by GNU LibreDWG
 * (https://www.gnu.org/software/libredwg/, Copyright (C) 2009-2026 Free Software Foundation, Inc.,
 * GPL-3.0-or-later) and FreeCAD InventorLoader (https://github.com/jmplonka/InventorLoader,
 * Copyright (c) 2018-2024 Jens M. Plonka, LGPL-2.1-or-later).
 */

package com.omnireader.core.engine.impl

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AcisToken(val kind: Int, val num: Double = 0.0, val text: String = "", val ptr: Int = -1, val vec: DoubleArray? = null) {
    companion object {
        const val PTR = 1
        const val NUM = 2
        const val WORD = 3
        const val VEC = 4
        const val SUB_START = 5
        const val SUB_END = 6
    }
}

class AcisRecord(val name: String, rawTokens: List<AcisToken>, hasHistoryPrefix: Boolean = false) {
    val tokens: List<AcisToken> = if (hasHistoryPrefix && rawTokens.size >= 2 && rawTokens[0].kind == AcisToken.PTR && rawTokens[1].kind == AcisToken.NUM) rawTokens.subList(2, rawTokens.size) else rawTokens

    val pointers: List<Int> get() = tokens.filter { it.kind == AcisToken.PTR }.map { it.ptr }

    fun numbers(): DoubleArray {
        val out = ArrayList<Double>()
        for (t in tokens) {
            when (t.kind) {
                AcisToken.NUM -> out.add(t.num)
                AcisToken.VEC -> t.vec!!.forEach { out.add(it) }
            }
        }
        return out.toDoubleArray()
    }

    fun firstWordAfterPointer(index: Int): String? {
        var seen = 0
        for (t in tokens) {
            if (t.kind == AcisToken.PTR) {
                seen++
                continue
            }
            if (seen > index && t.kind == AcisToken.WORD) return t.text
        }
        return null
    }
}

object CleanRoomAcis {

    fun parse(data: ByteArray): List<AcisRecord> {
        val head = String(data, 0, minOf(15, data.size), Charsets.ISO_8859_1)
        return if (head == "ACIS BinaryFile" || head.startsWith("ASM BinaryFile")) parseSab(data) else parseSat(String(data, Charsets.ISO_8859_1))
    }

    private fun isNumber(s: String): Boolean = s.toDoubleOrNull() != null

    fun parseSat(text: String): List<AcisRecord> {
        val lines = text.split('\n')
        if (lines.size < 2) throw DwgFormatException("ACIS text is truncated")
        val version = lines[0].trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: 0
        val bodyStart = run {
            var offset = 0
            var li = 0
            while (li < lines.size) {
                val l = lines[li].trim()
                val isHeader = li == 0 || (l.isNotEmpty() && (l[0].isDigit() || l[0] == '@')) || (version >= 700 && l.startsWith("T "))
                if (!isHeader || li > 4) break
                offset += lines[li].length + 1
                li++
            }
            offset
        }
        val records = ArrayList<AcisRecord>()
        var i = bodyStart
        val n = text.length
        val tokens = ArrayList<AcisToken>()
        var name: String? = null
        while (i < n) {
            val c = text[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '#') {
                if (name != null) records.add(AcisRecord(name, ArrayList(tokens), version >= 700))
                tokens.clear()
                name = null
                i++
                if (records.size > 5_000_000) throw DwgFormatException("ACIS data is too large")
                continue
            }
            if (c == '@' ) {
                var j = i + 1
                while (j < n && text[j].isDigit()) j++
                val len = text.substring(i + 1, j).toIntOrNull() ?: throw DwgFormatException("ACIS string is invalid")
                val start = j + 1
                val end = minOf(n, start + len)
                tokens.add(AcisToken(AcisToken.WORD, text = text.substring(minOf(start, n), end)))
                i = end
                continue
            }
            var j = i
            while (j < n && !text[j].isWhitespace() && text[j] != '#') j++
            val word = text.substring(i, j)
            i = j
            if (name == null) {
                if (word.startsWith("-") && isNumber(word)) continue
                name = word
                if (word == "End-of-ACIS-data" || word == "End-of-ASM-data") break
                continue
            }
            when {
                word.startsWith("$") -> tokens.add(AcisToken(AcisToken.PTR, ptr = word.substring(1).toIntOrNull() ?: -1))
                word == "{" -> tokens.add(AcisToken(AcisToken.SUB_START))
                word == "}" -> tokens.add(AcisToken(AcisToken.SUB_END))
                isNumber(word) -> tokens.add(AcisToken(AcisToken.NUM, num = word.toDouble()))
                else -> tokens.add(AcisToken(AcisToken.WORD, text = word))
            }
        }
        return records
    }

    fun parseSab(data: ByteArray): List<AcisRecord> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var p = 15 + 16
        val sabVersion = if (data.size >= 19) bb.getInt(15) else 0
        fun need(n: Int) { if (p + n > data.size) throw DwgFormatException("ACIS binary data is truncated") }
        fun u8(): Int { need(1); return data[p++].toInt() and 0xFF }
        fun i32(): Int { need(4); val v = bb.getInt(p); p += 4; return v }
        fun f64(): Double { need(8); val v = bb.getDouble(p); p += 8; return v }
        fun str(len: Int): String { need(len); val s = String(data, p, len, Charsets.ISO_8859_1); p += len; return s }
        repeat(3) { if (u8() != 0x07) throw DwgFormatException("ACIS binary header is invalid"); str(u8()) }
        repeat(3) { if (u8() != 0x06) throw DwgFormatException("ACIS binary header is invalid"); f64() }
        val records = ArrayList<AcisRecord>()
        val tokens = ArrayList<AcisToken>()
        var name: String? = null
        val namePart = StringBuilder()
        while (p < data.size) {
            when (val tag = u8()) {
                0x0E -> namePart.append(str(u8())).append('-')
                0x0D -> {
                    val part = str(u8())
                    if (name == null) {
                        name = namePart.toString() + part
                        namePart.setLength(0)
                        if (name == "End-of-ACIS-data" || name == "End-of-ASM-data") break
                    } else {
                        tokens.add(AcisToken(AcisToken.WORD, text = namePart.toString() + part))
                        namePart.setLength(0)
                    }
                }
                0x04 -> tokens.add(AcisToken(AcisToken.NUM, num = i32().toDouble()))
                0x15 -> tokens.add(AcisToken(AcisToken.NUM, num = i32().toDouble()))
                0x06 -> tokens.add(AcisToken(AcisToken.NUM, num = f64()))
                0x07 -> tokens.add(AcisToken(AcisToken.WORD, text = str(u8())))
                0x08 -> { need(2); val len = bb.getShort(p).toInt() and 0xFFFF; p += 2; tokens.add(AcisToken(AcisToken.WORD, text = str(len))) }
                0x12 -> { val len = i32(); if (len < 0) throw DwgFormatException("ACIS string is invalid"); tokens.add(AcisToken(AcisToken.WORD, text = str(len))) }
                0x0A -> tokens.add(AcisToken(AcisToken.WORD, text = "TRUE"))
                0x0B -> tokens.add(AcisToken(AcisToken.WORD, text = "FALSE"))
                0x0C -> tokens.add(AcisToken(AcisToken.PTR, ptr = i32()))
                0x0F -> tokens.add(AcisToken(AcisToken.SUB_START))
                0x10 -> tokens.add(AcisToken(AcisToken.SUB_END))
                0x13, 0x14 -> tokens.add(AcisToken(AcisToken.VEC, vec = doubleArrayOf(f64(), f64(), f64())))
                0x11 -> {
                    records.add(AcisRecord(name ?: "", ArrayList(tokens), sabVersion >= 700))
                    tokens.clear()
                    name = null
                    if (records.size > 5_000_000) throw DwgFormatException("ACIS data is too large")
                }
                else -> throw DwgFormatException("ACIS binary tag $tag is unsupported")
            }
        }
        return records
    }

    private fun sense(word: String?): Boolean = word == "reversed" || word == "TRUE"

    class Geometry(val lines: List<DoubleArray>, val faces: List<List<DoubleArray>>, val triangles: DoubleArray = DoubleArray(0))

    fun build(records: List<AcisRecord>, chordSegments: Int = 64): Geometry {
        fun rec(i: Int): AcisRecord? = if (i < 0) null else records.getOrNull(i)
        var transform: DoubleArray? = null
        val body = records.firstOrNull { it.name == "body" }
        if (body != null) {
            val tp = body.pointers.getOrNull(3) ?: -1
            val tr = rec(tp)
            if (tr != null && tr.name == "transform") {
                val n = tr.numbers()
                if (n.size >= 13) transform = n.copyOf(13)
            }
        }
        fun xf(p: DoubleArray): DoubleArray {
            val t = transform ?: return p
            val s = t[12]
            return doubleArrayOf(
                (p[0] * t[0] + p[1] * t[3] + p[2] * t[6]) * s + t[9],
                (p[0] * t[1] + p[1] * t[4] + p[2] * t[7]) * s + t[10],
                (p[0] * t[2] + p[1] * t[5] + p[2] * t[8]) * s + t[11]
            )
        }
        fun vertexPoint(vi: Int): DoubleArray? {
            val v = rec(vi) ?: return null
            if (v.name != "vertex") return null
            val pt = rec(v.pointers.getOrNull(2) ?: -1) ?: return null
            val n = pt.numbers()
            return if (n.size >= 3) doubleArrayOf(n[0], n[1], n[2]) else null
        }
        val edgeCache = HashMap<Int, DoubleArray>()
        fun edgePolyline(ei: Int): DoubleArray? {
            edgeCache[ei]?.let { return it }
            val e = rec(ei) ?: return null
            if (e.name != "edge") return null
            val ptrs = e.pointers
            val p1 = vertexPoint(ptrs.getOrNull(1) ?: -1) ?: return null
            val p2 = vertexPoint(ptrs.getOrNull(2) ?: -1) ?: return null
            val curve = rec(ptrs.getOrNull(4) ?: -1)
            val reversed = sense(e.firstWordAfterPointer(4))
            val pts: DoubleArray = when (curve?.name) {
                "ellipse-curve" -> ellipseArc(curve, p1, p2, reversed, chordSegments) ?: (p1 + p2)
                "intcurve-curve" -> intcurveSamples(curve, p1, p2) ?: (p1 + p2)
                else -> p1 + p2
            }
            edgeCache[ei] = pts
            return pts
        }
        val lines = ArrayList<DoubleArray>()
        for ((idx, r) in records.withIndex()) {
            if (r.name != "edge") continue
            val pl = edgePolyline(idx) ?: continue
            var degenerate = true
            for (k in 3 until pl.size) if (Math.abs(pl[k] - pl[k % 3]) > 1e-12) { degenerate = false; break }
            if (degenerate) continue
            val out = DoubleArray(pl.size)
            for (k in 0 until pl.size / 3) {
                val q = xf(doubleArrayOf(pl[k * 3], pl[k * 3 + 1], pl[k * 3 + 2]))
                System.arraycopy(q, 0, out, k * 3, 3)
            }
            lines.add(out)
        }
        val faces = ArrayList<List<DoubleArray>>()
        val tris = ArrayList<Double>()
        for (r in records) {
            if (r.name != "face") continue
            val surf = rec(r.pointers.getOrNull(5) ?: -1) ?: continue
            if (surf.name == "cone-surface") {
                ruledFace(r, ::rec, ::edgePolyline, ::xf, lines, tris)
                continue
            }
            if ((surf.name == "sphere-surface" || surf.name == "torus-surface") && (r.pointers.getOrNull(2) ?: -1) < 0) {
                closedSurface(surf, ::xf, lines, tris)
                continue
            }
            if (surf.name != "plane-surface") continue
            val loops = ArrayList<DoubleArray>()
            var li = r.pointers.getOrNull(2) ?: -1
            var guard = 0
            while (li >= 0 && guard++ < 100_000) {
                val loop = rec(li) ?: break
                if (loop.name != "loop") break
                val parts = ArrayList<DoubleArray>()
                val first = loop.pointers.getOrNull(2) ?: -1
                var ci = first
                var g2 = 0
                while (ci >= 0 && g2++ < 1_000_000) {
                    val co = rec(ci) ?: break
                    if (co.name != "coedge") break
                    val cp = co.pointers
                    val pl = edgePolyline(cp.getOrNull(4) ?: -1)
                    if (pl != null && pl.size >= 6) parts.add(if (sense(co.firstWordAfterPointer(4))) reversedPolyline(pl) else pl)
                    ci = cp.getOrNull(1) ?: -1
                    if (ci == first) break
                }
                if (parts.size >= 2) {
                    val a = parts[0]; val b = parts[1]
                    val endGap = minOf(dist(a, a.size / 3 - 1, b, 0), dist(a, a.size / 3 - 1, b, b.size / 3 - 1))
                    val startGap = minOf(dist(a, 0, b, 0), dist(a, 0, b, b.size / 3 - 1))
                    if (startGap < endGap) parts[0] = reversedPolyline(a)
                    for (k in 1 until parts.size) {
                        val prev = parts[k - 1]
                        val cur = parts[k]
                        val last = prev.size / 3 - 1
                        if (dist(prev, last, cur, cur.size / 3 - 1) < dist(prev, last, cur, 0)) parts[k] = reversedPolyline(cur)
                    }
                }
                val pts = ArrayList<Double>()
                for ((k, part) in parts.withIndex()) {
                    val from = if (k == 0) 0 else 1
                    for (j in from until part.size / 3) {
                        val q = xf(doubleArrayOf(part[j * 3], part[j * 3 + 1], part[j * 3 + 2]))
                        pts.add(q[0]); pts.add(q[1]); pts.add(q[2])
                    }
                }
                if (pts.size >= 9) loops.add(pts.toDoubleArray())
                li = loop.pointers.getOrNull(1) ?: -1
            }
            if (loops.isNotEmpty()) faces.add(loops)
        }
        return Geometry(lines, faces, tris.toDoubleArray())
    }

    private fun ruledFace(face: AcisRecord, rec: (Int) -> AcisRecord?, edgePolyline: (Int) -> DoubleArray?, xf: (DoubleArray) -> DoubleArray, lines: MutableList<DoubleArray>, tris: MutableList<Double>) {
        val curves = ArrayList<DoubleArray>()
        val points = ArrayList<DoubleArray>()
        var li = face.pointers.getOrNull(2) ?: -1
        var guard = 0
        while (li >= 0 && guard++ < 1000) {
            val loop = rec(li) ?: break
            if (loop.name != "loop") break
            val first = loop.pointers.getOrNull(2) ?: -1
            var ci = first
            var g2 = 0
            while (ci >= 0 && g2++ < 10000) {
                val co = rec(ci) ?: break
                if (co.name != "coedge") break
                val ei = co.pointers.getOrNull(4) ?: -1
                val edge = rec(ei)
                val pl = edgePolyline(ei)
                if (edge != null && pl != null) {
                    val curve = rec(edge.pointers.getOrNull(4) ?: -1)
                    if (curve?.name == "ellipse-curve" && pl.size >= 12) curves.add(pl) else if (pl.size >= 3) points.add(pl.copyOfRange(0, 3))
                }
                ci = co.pointers.getOrNull(1) ?: -1
                if (ci == first) break
            }
            li = loop.pointers.getOrNull(1) ?: -1
        }
        fun resample(pl: DoubleArray, n: Int): DoubleArray {
            val m = pl.size / 3
            val out = DoubleArray(n * 3)
            for (k in 0 until n) {
                val f = k.toDouble() * (m - 1) / (n - 1)
                val i0 = minOf(m - 2, f.toInt())
                val t = f - i0
                for (a in 0 until 3) out[k * 3 + a] = pl[i0 * 3 + a] + (pl[(i0 + 1) * 3 + a] - pl[i0 * 3 + a]) * t
            }
            return out
        }
        fun addTri(a: DoubleArray, ai: Int, b: DoubleArray, bi: Int, c: DoubleArray, ci: Int) {
            val pa = xf(doubleArrayOf(a[ai * 3], a[ai * 3 + 1], a[ai * 3 + 2]))
            val pb = xf(doubleArrayOf(b[bi * 3], b[bi * 3 + 1], b[bi * 3 + 2]))
            val pc = xf(doubleArrayOf(c[ci * 3], c[ci * 3 + 1], c[ci * 3 + 2]))
            tris.addAll(pa.asList()); tris.addAll(pb.asList()); tris.addAll(pc.asList())
        }
        val n = 65
        if (curves.size >= 2) {
            val a = resample(curves[0], n)
            val b0 = resample(curves[1], n)
            val sameDir = dist(a, 0, b0, 0) + dist(a, n / 4, b0, n / 4) <= dist(a, 0, b0, n - 1) + dist(a, n / 4, b0, n - 1 - n / 4)
            val b = if (sameDir) b0 else DoubleArray(b0.size).also { r -> for (k in 0 until n) System.arraycopy(b0, (n - 1 - k) * 3, r, k * 3, 3) }
            var shift = 0
            var best = Double.MAX_VALUE
            for (s in 0 until n - 1) {
                val d = dist(a, 0, b, s)
                if (d < best) { best = d; shift = s }
            }
            fun bi(k: Int) = (k + shift) % (n - 1)
            for (k in 0 until n - 1) {
                addTri(a, k, a, k + 1, b, bi(k + 1))
                addTri(a, k, b, bi(k + 1), b, bi(k))
            }
            for (q in 0 until 4) {
                val k = q * (n - 1) / 4
                lines.add(xf(doubleArrayOf(a[k * 3], a[k * 3 + 1], a[k * 3 + 2])) + xf(doubleArrayOf(b[bi(k) * 3], b[bi(k) * 3 + 1], b[bi(k) * 3 + 2])))
            }
        } else if (curves.size == 1 && points.isNotEmpty()) {
            val a = resample(curves[0], n)
            val apex = points[0]
            for (k in 0 until n - 1) addTri(a, k, a, k + 1, apex, 0)
            for (q in 0 until 4) {
                val k = q * (n - 1) / 4
                lines.add(xf(doubleArrayOf(a[k * 3], a[k * 3 + 1], a[k * 3 + 2])) + xf(apex))
            }
        }
    }

    private fun closedSurface(surf: AcisRecord, xf: (DoubleArray) -> DoubleArray, lines: MutableList<DoubleArray>, tris: MutableList<Double>) {
        val n = surf.numbers()
        val sphere = surf.name == "sphere-surface"
        if ((sphere && n.size < 10) || (!sphere && n.size < 11)) return
        val cx = n[0]; val cy = n[1]; val cz = n[2]
        fun norm(x: Double, y: Double, z: Double): DoubleArray? {
            val l = Math.sqrt(x * x + y * y + z * z)
            return if (l < 1e-15) null else doubleArrayOf(x / l, y / l, z / l)
        }
        val axis: DoubleArray
        val ref: DoubleArray
        val big: Double
        val small: Double
        if (sphere) {
            big = n[3]
            small = 0.0
            ref = norm(n[4], n[5], n[6]) ?: doubleArrayOf(1.0, 0.0, 0.0)
            axis = norm(n[7], n[8], n[9]) ?: doubleArrayOf(0.0, 0.0, 1.0)
        } else {
            axis = norm(n[3], n[4], n[5]) ?: doubleArrayOf(0.0, 0.0, 1.0)
            big = n[6]
            small = n[7]
            ref = norm(n[8], n[9], n[10]) ?: doubleArrayOf(1.0, 0.0, 0.0)
        }
        if (!big.isFinite() || Math.abs(big) < 1e-12) return
        val wx = axis[1] * ref[2] - axis[2] * ref[1]
        val wy = axis[2] * ref[0] - axis[0] * ref[2]
        val wz = axis[0] * ref[1] - axis[1] * ref[0]
        val nu = 32
        val nv = 16
        fun point(u: Double, v: Double): DoubleArray {
            val ring: Double
            val h: Double
            if (sphere) {
                val lat = -Math.PI / 2 + Math.PI * v
                ring = Math.abs(big) * Math.cos(lat)
                h = Math.abs(big) * Math.sin(lat)
            } else {
                val a = Math.PI * 2 * v
                ring = big + small * Math.cos(a)
                h = small * Math.sin(a)
            }
            val ang = Math.PI * 2 * u
            val c = Math.cos(ang) * ring
            val sn = Math.sin(ang) * ring
            return xf(doubleArrayOf(cx + ref[0] * c + wx * sn + axis[0] * h, cy + ref[1] * c + wy * sn + axis[1] * h, cz + ref[2] * c + wz * sn + axis[2] * h))
        }
        for (i in 0 until nu) for (j in 0 until nv) {
            val p00 = point(i.toDouble() / nu, j.toDouble() / nv)
            val p10 = point((i + 1).toDouble() / nu, j.toDouble() / nv)
            val p01 = point(i.toDouble() / nu, (j + 1).toDouble() / nv)
            val p11 = point((i + 1).toDouble() / nu, (j + 1).toDouble() / nv)
            tris.addAll(p00.asList()); tris.addAll(p10.asList()); tris.addAll(p11.asList())
            tris.addAll(p00.asList()); tris.addAll(p11.asList()); tris.addAll(p01.asList())
        }
        val steps = 64
        val vLines = if (sphere) listOf(0.5) else listOf(0.0, 0.25, 0.5, 0.75)
        for (v in vLines) {
            val out = DoubleArray((steps + 1) * 3)
            for (k in 0..steps) System.arraycopy(point(k.toDouble() / steps, v), 0, out, k * 3, 3)
            lines.add(out)
        }
        for (q in 0 until 4) {
            val out = DoubleArray((steps + 1) * 3)
            for (k in 0..steps) System.arraycopy(point(q / 4.0, k.toDouble() / steps * (if (sphere) 1.0 else 1.0)), 0, out, k * 3, 3)
            lines.add(out)
        }
    }

    private fun reversedPolyline(pl: DoubleArray): DoubleArray {
        val n = pl.size / 3
        val out = DoubleArray(pl.size)
        for (k in 0 until n) System.arraycopy(pl, (n - 1 - k) * 3, out, k * 3, 3)
        return out
    }

    private fun dist(a: DoubleArray, i: Int, b: DoubleArray, j: Int): Double {
        val dx = a[i * 3] - b[j * 3]; val dy = a[i * 3 + 1] - b[j * 3 + 1]; val dz = a[i * 3 + 2] - b[j * 3 + 2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun ellipseArc(c: AcisRecord, p1: DoubleArray, p2: DoubleArray, reversed: Boolean, segments: Int): DoubleArray? {
        val n = c.numbers()
        if (n.size < 10) return null
        val cx = n[0]; val cy = n[1]; val cz = n[2]
        var nx = n[3]; var ny = n[4]; var nz = n[5]
        val mx = n[6]; val my = n[7]; val mz = n[8]
        val ratio = n[9]
        val major = Math.sqrt(mx * mx + my * my + mz * mz)
        val nl = Math.sqrt(nx * nx + ny * ny + nz * nz)
        if (major < 1e-12 || nl < 1e-12) return null
        nx /= nl; ny /= nl; nz /= nl
        val ux = mx / major; val uy = my / major; val uz = mz / major
        val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
        val minor = major * ratio
        fun angle(p: DoubleArray): Double {
            val dx = p[0] - cx; val dy = p[1] - cy; val dz = p[2] - cz
            return Math.atan2((dx * vx + dy * vy + dz * vz) / minor, (dx * ux + dy * uy + dz * uz) / major)
        }
        val a1 = angle(p1)
        val a2 = angle(p2)
        val same = Math.abs(p1[0] - p2[0]) + Math.abs(p1[1] - p2[1]) + Math.abs(p1[2] - p2[2]) < 1e-9 * maxOf(1.0, major)
        var sweep = if (same) Math.PI * 2 else if (!reversed) a2 - a1 else a1 - a2
        if (!same) {
            sweep = Math.IEEEremainder(sweep, Math.PI * 2)
            if (sweep <= 1e-12) sweep += Math.PI * 2
        }
        val steps = maxOf(4, Math.ceil(sweep / (Math.PI * 2) * segments).toInt())
        val out = DoubleArray((steps + 1) * 3)
        for (k in 0..steps) {
            val t = if (!reversed) a1 + sweep * k / steps else a1 - sweep * k / steps
            val ct = Math.cos(t) * major; val st = Math.sin(t) * minor
            out[k * 3] = cx + ux * ct + vx * st
            out[k * 3 + 1] = cy + uy * ct + vy * st
            out[k * 3 + 2] = cz + uz * ct + vz * st
        }
        System.arraycopy(p1, 0, out, 0, 3)
        System.arraycopy(p2, 0, out, steps * 3, 3)
        return out
    }

    private fun intcurveSamples(c: AcisRecord, p1: DoubleArray, p2: DoubleArray): DoubleArray? {
        val t = c.tokens
        var i = t.indexOfFirst { it.kind == AcisToken.WORD && (it.text == "nubs" || it.text == "nurbs") }
        if (i < 0) return null
        val rational = t[i].text == "nurbs"
        i++
        fun nextNum(): Double? {
            while (i < t.size) {
                val tk = t[i++]
                if (tk.kind == AcisToken.NUM) return tk.num
                if (tk.kind == AcisToken.SUB_END) return null
            }
            return null
        }
        val degree = nextNum()?.toInt() ?: return null
        while (i < t.size && t[i].kind == AcisToken.WORD) i++
        val nk = nextNum()?.toInt() ?: return null
        if (degree < 1 || degree > 25 || nk < 2 || nk > 100_000) return null
        val knots = ArrayList<Double>()
        for (k in 0 until nk) {
            val v = nextNum() ?: return null
            val m = nextNum()?.toInt() ?: return null
            if (m < 1 || m > degree + 1) return null
            repeat(m) { knots.add(v) }
        }
        val full = ArrayList<Double>()
        full.add(knots.first()); full.addAll(knots); full.add(knots.last())
        val nCtrl = full.size - degree - 1
        if (nCtrl < 2) return null
        val ctrl = DoubleArray(nCtrl * 3)
        val w = DoubleArray(nCtrl) { 1.0 }
        for (k in 0 until nCtrl) {
            for (a in 0 until 3) ctrl[k * 3 + a] = nextNum() ?: return null
            if (rational) w[k] = nextNum() ?: return null
        }
        val pts = CleanRoomDwgReader.evaluateSpline(degree, full.toDoubleArray(), ctrl, w)
        if (pts.size < 6) return null
        System.arraycopy(p1, 0, pts, 0, 3)
        System.arraycopy(p2, 0, pts, pts.size - 3, 3)
        return pts
    }
}
