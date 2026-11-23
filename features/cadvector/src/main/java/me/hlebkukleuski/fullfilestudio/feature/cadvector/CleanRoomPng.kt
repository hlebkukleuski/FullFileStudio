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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class PngImage(val width: Int, val height: Int, val bitDepth: Int, val channels: Int, val samples: IntArray, val hasAlpha: Boolean) {
    val maxValue: Int get() = if (bitDepth == 16) 65535 else 255
}

object CleanRoomPng {
    private fun u32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun isPng(b: ByteArray) = b.size > 33 && (b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    fun decode(b: ByteArray): PngImage? {
        if (!isPng(b)) return null
        var p = 8
        var w = 0
        var h = 0
        var depth = 0
        var ctype = 0
        var interlace = 0
        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (p + 8 <= b.size) {
            val len = u32(b, p)
            if (len < 0 || p + 12 + len > b.size) return null
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            val d = p + 8
            when (type) {
                "IHDR" -> { w = u32(b, d); h = u32(b, d + 4); depth = b[d + 8].toInt() and 0xFF; ctype = b[d + 9].toInt() and 0xFF; interlace = b[d + 12].toInt() and 0xFF }
                "PLTE" -> palette = b.copyOfRange(d, d + len)
                "tRNS" -> trns = b.copyOfRange(d, d + len)
                "IDAT" -> idat.write(b, d, len)
                "IEND" -> break
            }
            p += 12 + len
        }
        if (w <= 0 || h <= 0 || w.toLong() * h > 64L * 1024 * 1024) return null
        val comps = when (ctype) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        val raw = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            raw.write(buf, 0, n)
        }
        inf.end()
        val data = raw.toByteArray()
        val bpp = maxOf(1, comps * depth / 8)
        val outCh = when (ctype) { 0 -> if (trns != null) 2 else 1; 2 -> if (trns != null) 4 else 3; 3 -> if (trns != null) 4 else 3; 4 -> 2; else -> 4 }
        val samples = IntArray(w * h * outCh)
        var pos = 0
        fun pass(x0: Int, y0: Int, dx: Int, dy: Int) {
            val pw = (w - x0 + dx - 1) / dx
            val ph = (h - y0 + dy - 1) / dy
            if (pw <= 0 || ph <= 0) return
            val stride = (pw * comps * depth + 7) / 8
            var prev = ByteArray(stride)
            for (r in 0 until ph) {
                if (pos + 1 + stride > data.size) return
                val ft = data[pos].toInt() and 0xFF
                val line = data.copyOfRange(pos + 1, pos + 1 + stride)
                pos += 1 + stride
                for (i in 0 until stride) {
                    val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
                    val up = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val x = line[i].toInt() and 0xFF
                    line[i] = when (ft) {
                        1 -> x + a
                        2 -> x + up
                        3 -> x + ((a + up) ushr 1)
                        4 -> {
                            val pp = a + up - c
                            val pa = kotlin.math.abs(pp - a); val pb = kotlin.math.abs(pp - up); val pc = kotlin.math.abs(pp - c)
                            x + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c
                        }
                        else -> x
                    }.toByte()
                }
                prev = line
                val y = y0 + r * dy
                for (cx in 0 until pw) {
                    val x = x0 + cx * dx
                    val vals = IntArray(comps)
                    for (k in 0 until comps) {
                        vals[k] = when (depth) {
                            16 -> { val o = (cx * comps + k) * 2; ((line[o].toInt() and 0xFF) shl 8) or (line[o + 1].toInt() and 0xFF) }
                            8 -> line[cx * comps + k].toInt() and 0xFF
                            else -> {
                                val bit = (cx * comps + k) * depth
                                ((line[bit / 8].toInt() and 0xFF) shr (8 - depth - bit % 8)) and ((1 shl depth) - 1)
                            }
                        }
                    }
                    val o = (y * w + x) * outCh
                    when (ctype) {
                        3 -> {
                            val pi = vals[0]
                            val pl = palette ?: return
                            samples[o] = pl.getOrElse(pi * 3) { 0 }.toInt() and 0xFF
                            samples[o + 1] = pl.getOrElse(pi * 3 + 1) { 0 }.toInt() and 0xFF
                            samples[o + 2] = pl.getOrElse(pi * 3 + 2) { 0 }.toInt() and 0xFF
                            if (outCh == 4) samples[o + 3] = trns?.getOrNull(pi)?.toInt()?.and(0xFF) ?: 255
                        }
                        0, 2 -> {
                            val scale = if (depth < 8) 255 / ((1 shl depth) - 1) else 1
                            for (k in 0 until comps) samples[o + k] = vals[k] * scale
                            val t = trns
                            if (t != null) {
                                val key = IntArray(comps) { k -> ((t[k * 2].toInt() and 0xFF) shl 8) or (t[k * 2 + 1].toInt() and 0xFF) }
                                val match = (0 until comps).all { vals[it] == key[it] }
                                samples[o + comps] = if (match) 0 else (if (depth == 16) 65535 else 255)
                            }
                        }
                        else -> for (k in 0 until comps) samples[o + k] = vals[k]
                    }
                }
            }
        }
        if (interlace == 1) {
            pass(0, 0, 8, 8); pass(4, 0, 8, 8); pass(0, 4, 4, 8); pass(2, 0, 4, 4); pass(0, 2, 2, 4); pass(1, 0, 2, 2); pass(0, 1, 1, 2)
        } else pass(0, 0, 1, 1)
        val d16 = depth == 16 && ctype != 3
        return PngImage(w, h, if (d16) 16 else 8, outCh, samples, outCh == 2 || outCh == 4)
    }
}
