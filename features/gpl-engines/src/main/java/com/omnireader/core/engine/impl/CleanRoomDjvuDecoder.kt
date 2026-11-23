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
 * Portions ported and adapted from DjVuLibre (https://djvu.sourceforge.net/),
 * Copyright (c) 1999-2001 LizardTech, Inc. / AT&T Labs / Leon Bottou, Yann LeCun,
 * Patrick Haffner, Paul G. Howard, and Bill Riemers,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.impl

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class DjvuFormatException(message: String) : Exception(message)

class DjvuZpDecoder(private val data: ByteArray, start: Int, private val end: Int) {
    private var pos = start
    private var a = 0
    private var code = 0
    private var fence = 0
    private var buffer = 0
    private var scount = 0
    private var delay = 25

    init {
        var b = readByte()
        code = (if (b < 0) 0xFF else b) shl 8
        b = readByte()
        code = code or (if (b < 0) 0xFF else b)
        preload()
        fence = if (code >= 0x8000) 0x7FFF else code
    }

    private fun readByte(): Int = if (pos < end) data[pos++].toInt() and 0xFF else -1

    private fun preload() {
        while (scount <= 24) {
            var b = readByte()
            if (b < 0) {
                b = 0xFF
                if (--delay < 1) throw DjvuFormatException("ZP stream is truncated")
            }
            buffer = (buffer shl 8) or b
            scount += 8
        }
    }

    private fun ffz(x: Int): Int = if (x >= 0xFF00) FFZT[x and 0xFF] + 8 else FFZT[(x shr 8) and 0xFF]

    fun decode(ctx: ByteArray, index: Int): Int {
        val state = ctx[index].toInt() and 0xFF
        val z = a + P[state]
        if (z <= fence) {
            a = z
            return state and 1
        }
        var zz = z
        val d = 0x6000 + ((zz + a) shr 2)
        if (zz > d) zz = d
        val bit = state and 1
        if (zz > code) {
            zz = 0x10000 - zz
            a += zz
            code += zz
            ctx[index] = DN[state].toByte()
            renormalizeLps()
            return bit xor 1
        }
        if (a >= M[state]) ctx[index] = UP[state].toByte()
        renormalizeMps(zz)
        return bit
    }

    fun decodeRaw(): Int = decodeSimple(0x8000 + (a shr 1))

    fun decodeIw(): Int = decodeSimple(0x8000 + ((a + a + a) shr 3))

    private fun decodeSimple(z: Int): Int {
        if (z > code) {
            val zz = 0x10000 - z
            a += zz
            code += zz
            renormalizeLps()
            return 1
        }
        renormalizeMps(z)
        return 0
    }

    private fun renormalizeLps() {
        val shift = ffz(a)
        scount -= shift
        a = (a shl shift) and 0xFFFF
        code = ((code shl shift) and 0xFFFF) or ((buffer ushr scount) and ((1 shl shift) - 1))
        if (scount < 16) preload()
        fence = if (code >= 0x8000) 0x7FFF else code
    }

    private fun renormalizeMps(z: Int) {
        scount -= 1
        a = (z shl 1) and 0xFFFF
        code = ((code shl 1) and 0xFFFF) or ((buffer ushr scount) and 1)
        if (scount < 16) preload()
        fence = if (code >= 0x8000) 0x7FFF else code
    }

    companion object {
        private val FFZT = IntArray(256) { i ->
            var n = 0
            var j = i
            while (j and 0x80 != 0) {
                n++
                j = j shl 1
            }
            n
        }

        private val P_TABLE = intArrayOf(
            0x8000, 0x8000, 0x8000, 0x6bbd, 0x6bbd, 0x5d45, 0x5d45, 0x51b9, 0x51b9, 0x4813, 0x4813, 0x3fd5, 0x3fd5, 0x38b1, 0x38b1, 0x3275,
            0x3275, 0x2cfd, 0x2cfd, 0x2825, 0x2825, 0x23ab, 0x23ab, 0x1f87, 0x1f87, 0x1bbb, 0x1bbb, 0x1845, 0x1845, 0x1523, 0x1523, 0x1253,
            0x1253, 0x0fcf, 0x0fcf, 0x0d95, 0x0d95, 0x0b9d, 0x0b9d, 0x09e3, 0x09e3, 0x0861, 0x0861, 0x0711, 0x0711, 0x05f1, 0x05f1, 0x04f9,
            0x04f9, 0x0425, 0x0425, 0x0371, 0x0371, 0x02d9, 0x02d9, 0x0259, 0x0259, 0x01ed, 0x01ed, 0x0193, 0x0193, 0x0149, 0x0149, 0x010b,
            0x010b, 0x00d5, 0x00d5, 0x00a5, 0x00a5, 0x007b, 0x007b, 0x0057, 0x0057, 0x003b, 0x003b, 0x0023, 0x0023, 0x0013, 0x0013, 0x0007,
            0x0007, 0x0001, 0x0001, 0x5695, 0x24ee, 0x8000, 0x0d30, 0x481a, 0x0481, 0x3579, 0x017a, 0x24ef, 0x007b, 0x1978, 0x0028, 0x10ca,
            0x000d, 0x0b5d, 0x0034, 0x078a, 0x00a0, 0x050f, 0x0117, 0x0358, 0x01ea, 0x0234, 0x0144, 0x0173, 0x0234, 0x00f5, 0x0353, 0x00a1,
            0x05c5, 0x011a, 0x03cf, 0x01aa, 0x0285, 0x0286, 0x01ab, 0x03d3, 0x011a, 0x05c5, 0x00ba, 0x08ad, 0x007a, 0x0ccc, 0x01eb, 0x1302,
            0x02e6, 0x1b81, 0x045e, 0x24ef, 0x0690, 0x2865, 0x09de, 0x3987, 0x0dc8, 0x2c99, 0x10ca, 0x3b5f, 0x0b5d, 0x5695, 0x078a, 0x8000,
            0x050f, 0x24ee, 0x0358, 0x0d30, 0x0234, 0x0481, 0x0173, 0x017a, 0x00f5, 0x007b, 0x00a1, 0x0028, 0x011a, 0x000d, 0x01aa, 0x0034,
            0x0286, 0x00a0, 0x03d3, 0x0117, 0x05c5, 0x01ea, 0x08ad, 0x0144, 0x0ccc, 0x0234, 0x1302, 0x0353, 0x1b81, 0x05c5, 0x24ef, 0x03cf,
            0x2b74, 0x0285, 0x201d, 0x01ab, 0x1715, 0x011a, 0x0fb7, 0x00ba, 0x0a67, 0x01eb, 0x06e7, 0x02e6, 0x0496, 0x045e, 0x030d, 0x0690,
            0x0206, 0x09de, 0x0155, 0x0dc8, 0x00e1, 0x2b74, 0x0094, 0x201d, 0x0188, 0x1715, 0x0252, 0x0fb7, 0x0383, 0x0a67, 0x0547, 0x06e7,
            0x07e2, 0x0496, 0x0bc0, 0x030d, 0x1178, 0x0206, 0x19da, 0x0155, 0x24ef, 0x00e1, 0x320e, 0x0094, 0x432a, 0x0188, 0x447d, 0x0252,
            0x5ece, 0x0383, 0x8000, 0x0547, 0x481a, 0x07e2, 0x3579, 0x0bc0, 0x24ef, 0x1178, 0x1978, 0x19da, 0x2865, 0x24ef, 0x3987, 0x320e,
            0x2c99, 0x432a, 0x3b5f, 0x447d, 0x5695, 0x5ece, 0x8000, 0x8000, 0x5695, 0x481a, 0x481a
        )
        private val M_TABLE = intArrayOf(
            0x0000, 0x0000, 0x0000, 0x10a5, 0x10a5, 0x1f28, 0x1f28, 0x2bd3, 0x2bd3, 0x36e3, 0x36e3, 0x408c, 0x408c, 0x48fd, 0x48fd, 0x505d,
            0x505d, 0x56d0, 0x56d0, 0x5c71, 0x5c71, 0x615b, 0x615b, 0x65a5, 0x65a5, 0x6962, 0x6962, 0x6ca2, 0x6ca2, 0x6f74, 0x6f74, 0x71e6,
            0x71e6, 0x7404, 0x7404, 0x75d6, 0x75d6, 0x7768, 0x7768, 0x78c2, 0x78c2, 0x79ea, 0x79ea, 0x7ae7, 0x7ae7, 0x7bbe, 0x7bbe, 0x7c75,
            0x7c75, 0x7d0f, 0x7d0f, 0x7d91, 0x7d91, 0x7dfe, 0x7dfe, 0x7e5a, 0x7e5a, 0x7ea6, 0x7ea6, 0x7ee6, 0x7ee6, 0x7f1a, 0x7f1a, 0x7f45,
            0x7f45, 0x7f6b, 0x7f6b, 0x7f8d, 0x7f8d, 0x7faa, 0x7faa, 0x7fc3, 0x7fc3, 0x7fd7, 0x7fd7, 0x7fe7, 0x7fe7, 0x7ff2, 0x7ff2, 0x7ffa,
            0x7ffa, 0x7fff, 0x7fff, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000
        )
        private val UP_TABLE = intArrayOf(
            84, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
            34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
            50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65,
            66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81,
            82, 81, 82, 9, 86, 5, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97,
            82, 99, 76, 101, 70, 103, 66, 105, 106, 107, 66, 109, 60, 111, 56, 69,
            114, 65, 116, 61, 118, 57, 120, 53, 122, 49, 124, 43, 72, 39, 60, 33,
            56, 29, 52, 23, 48, 23, 42, 137, 38, 21, 140, 15, 142, 9, 144, 141,
            146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 70, 157, 66, 81, 62, 75,
            58, 69, 54, 65, 50, 167, 44, 65, 40, 59, 34, 55, 30, 175, 24, 177,
            178, 179, 180, 181, 182, 183, 184, 69, 186, 59, 188, 55, 190, 51, 192, 47,
            194, 41, 196, 37, 198, 199, 72, 201, 62, 203, 58, 205, 54, 207, 50, 209,
            46, 211, 40, 213, 36, 215, 30, 217, 26, 219, 20, 71, 14, 61, 14, 57,
            8, 53, 228, 49, 230, 45, 232, 39, 234, 35, 138, 29, 24, 25, 240, 19,
            22, 13, 16, 13, 10, 7, 244, 249, 10, 89, 230
        )
        private val DN_TABLE = intArrayOf(
            145, 4, 3, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
            14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61,
            62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77,
            78, 79, 80, 85, 226, 6, 176, 143, 138, 141, 112, 135, 104, 133, 100, 129,
            98, 127, 72, 125, 102, 123, 60, 121, 110, 119, 108, 117, 54, 115, 48, 113,
            134, 59, 132, 55, 130, 51, 128, 47, 126, 41, 62, 37, 66, 31, 54, 25,
            50, 131, 46, 17, 40, 15, 136, 7, 32, 139, 172, 9, 170, 85, 168, 248,
            166, 247, 164, 197, 162, 95, 160, 173, 158, 165, 156, 161, 60, 159, 56, 71,
            52, 163, 48, 59, 42, 171, 38, 169, 32, 53, 26, 47, 174, 193, 18, 191,
            222, 189, 218, 187, 216, 185, 214, 61, 212, 53, 210, 49, 208, 45, 206, 39,
            204, 195, 202, 31, 200, 243, 64, 239, 56, 237, 52, 235, 48, 233, 44, 231,
            38, 229, 34, 227, 28, 225, 22, 223, 16, 221, 220, 63, 8, 55, 224, 51,
            2, 47, 87, 43, 246, 37, 244, 33, 238, 27, 236, 21, 16, 15, 8, 241,
            242, 7, 10, 245, 2, 1, 83, 250, 2, 143, 246
        )
        private val P = P_TABLE.copyOf(256)
        private val M = M_TABLE.copyOf(256)
        private val UP = UP_TABLE.copyOf(256)
        private val DN = DN_TABLE.copyOf(256)
    }
}

object DjvuBzz {
    private const val MAX_BLOCK = 4096 * 1024

    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, maxOutput: Int = 64 shl 20): ByteArray {
        if (length <= 0) return ByteArray(0)
        val zp = DjvuZpDecoder(data, offset, offset + length)
        val ctx = ByteArray(300)
        val out = ByteArrayOutputStream()
        while (true) {
            val size = decodeRaw(zp, 24)
            if (size == 0) break
            if (size > MAX_BLOCK) throw DjvuFormatException("BZZ block is too large")
            val block = decodeBlock(zp, ctx, size)
            out.write(block, 0, size - 1)
            if (out.size() > maxOutput) throw DjvuFormatException("BZZ output exceeds limit")
        }
        return out.toByteArray()
    }

    private fun decodeRaw(zp: DjvuZpDecoder, bits: Int): Int {
        var n = 1
        val m = 1 shl bits
        while (n < m) n = (n shl 1) or zp.decodeRaw()
        return n - m
    }

    private fun decodeBinary(zp: DjvuZpDecoder, ctx: ByteArray, base: Int, bits: Int): Int {
        var n = 1
        val m = 1 shl bits
        while (n < m) n = (n shl 1) or zp.decode(ctx, base + n)
        return n - m
    }

    private fun decodeBlock(zp: DjvuZpDecoder, ctx: ByteArray, size: Int): ByteArray {
        val data = ByteArray(size)
        var fshift = 0
        if (zp.decodeRaw() != 0) {
            fshift++
            if (zp.decodeRaw() != 0) fshift++
        }
        val mtf = IntArray(256) { it }
        val freq = IntArray(4)
        var fadd = 4
        var mtfno = 3
        var markerpos = -1
        for (i in 0 until size) {
            val ctxid = minOf(2, mtfno)
            mtfno = when {
                zp.decode(ctx, ctxid) != 0 -> 0
                zp.decode(ctx, 3 + ctxid) != 0 -> 1
                zp.decode(ctx, 6) != 0 -> 2 + decodeBinary(zp, ctx, 6, 1)
                zp.decode(ctx, 8) != 0 -> 4 + decodeBinary(zp, ctx, 8, 2)
                zp.decode(ctx, 12) != 0 -> 8 + decodeBinary(zp, ctx, 12, 3)
                zp.decode(ctx, 20) != 0 -> 16 + decodeBinary(zp, ctx, 20, 4)
                zp.decode(ctx, 36) != 0 -> 32 + decodeBinary(zp, ctx, 36, 5)
                zp.decode(ctx, 68) != 0 -> 64 + decodeBinary(zp, ctx, 68, 6)
                zp.decode(ctx, 132) != 0 -> 128 + decodeBinary(zp, ctx, 132, 7)
                else -> 256
            }
            if (mtfno == 256) {
                data[i] = 0
                markerpos = i
                continue
            }
            val symbol = mtf[mtfno]
            data[i] = symbol.toByte()
            fadd += fadd shr fshift
            if (fadd > 0x10000000) {
                fadd = fadd shr 24
                for (k in 0 until 4) freq[k] = freq[k] shr 24
            }
            var fc = fadd
            if (mtfno < 4) fc += freq[mtfno]
            var k = mtfno
            while (k >= 4) {
                mtf[k] = mtf[k - 1]
                k--
            }
            while (k > 0 && fc >= freq[k - 1]) {
                mtf[k] = mtf[k - 1]
                freq[k] = freq[k - 1]
                k--
            }
            mtf[k] = symbol
            freq[k] = fc
        }
        if (markerpos < 1 || markerpos >= size) throw DjvuFormatException("BZZ block marker is invalid")
        val posn = IntArray(size)
        val count = IntArray(256)
        for (i in 0 until size) {
            if (i == markerpos) continue
            val c = data[i].toInt() and 0xFF
            posn[i] = (c shl 24) or (count[c] and 0xFFFFFF)
            count[c]++
        }
        var last = 1
        for (i in 0 until 256) {
            val tmp = count[i]
            count[i] = last
            last += tmp
        }
        var i = 0
        last = size - 1
        while (last > 0) {
            val n = posn[i]
            val c = n ushr 24
            data[--last] = c.toByte()
            i = count[c] + (n and 0xFFFFFF)
            if (i < 0 || i >= size) throw DjvuFormatException("BZZ block is corrupted")
        }
        if (i != markerpos) throw DjvuFormatException("BZZ block is corrupted")
        return data
    }
}

class Jb2Shape(val width: Int, val height: Int, val bits: ByteArray) {
    var left = 0
    var right = -1
    var bottom = 0
    var top = -1

    fun get(row: Int, col: Int): Int =
        if (row < 0 || row >= height || col < 0 || col >= width) 0 else bits[row * width + col].toInt()

    fun computeBoundingBox() {
        right = width - 1
        while (right >= 0 && (0 until height).none { bits[it * width + right].toInt() != 0 }) right--
        top = height - 1
        while (top >= 0 && (0 until width).none { bits[top * width + it].toInt() != 0 }) top--
        left = 0
        while (left <= right && (0 until height).none { bits[it * width + left].toInt() != 0 }) left++
        bottom = 0
        while (bottom <= top && (0 until width).none { bits[bottom * width + it].toInt() != 0 }) bottom++
    }
}

class Jb2Image(val width: Int, val height: Int, val shapes: List<Jb2Shape>, val blitShapes: List<Jb2Shape>, val blitLeft: IntArray, val blitBottom: IntArray) {
    val blitCount: Int get() = blitShapes.size
}

class Jb2Decoder private constructor(data: ByteArray, offset: Int, length: Int, private val inherited: List<Jb2Shape>?, private val maxPixels: Long) {
    private val zp = DjvuZpDecoder(data, offset, offset + length)
    private var cellBits = ByteArray(4096)
    private var cellLeft = IntArray(4096)
    private var cellRight = IntArray(4096)
    private var cellCount = 1
    private val roots = IntArray(16)
    private val offsetTypeDist = ByteArray(1)
    private val refinementDist = ByteArray(1)
    private val directDist = ByteArray(1024)
    private val crossDist = ByteArray(2048)
    private val shapes = ArrayList<Jb2Shape>()
    private val library = ArrayList<Jb2Shape>()
    private val blitShapes = ArrayList<Jb2Shape>()
    private var blitLeft = IntArray(256)
    private var blitBottom = IntArray(256)
    private var imageWidth = 0
    private var imageHeight = 0
    private var gotStart = false
    private var inheritedCount = 0
    private var lastLeft = 0
    private var lastRight = 0
    private var lastBottom = 0
    private var lastRowLeft = 0
    private var lastRowBottom = 0
    private val shortList = IntArray(3)
    private var shortListPos = 0
    private var decodedPixels = 0L

    private fun resetNumCoder() {
        cellCount = 1
        roots.fill(0)
    }

    private fun decodeNum(lowIn: Int, highIn: Int, root: Int): Int {
        var low = lowIn
        var high = highIn
        var negative = false
        var cutoff = 0
        var phase = 1
        var range = -1
        var slotKind = 0
        var slotRef = root
        while (range != 1) {
            var cell = when (slotKind) {
                0 -> roots[slotRef]
                1 -> cellLeft[slotRef]
                else -> cellRight[slotRef]
            }
            if (cell == 0) {
                if (cellCount >= MAX_CELLS) throw DjvuFormatException("JB2 number coder overflow")
                if (cellCount >= cellBits.size) {
                    val n = cellBits.size * 2
                    cellBits = cellBits.copyOf(n)
                    cellLeft = cellLeft.copyOf(n)
                    cellRight = cellRight.copyOf(n)
                }
                cell = cellCount++
                cellBits[cell] = 0
                cellLeft[cell] = 0
                cellRight[cell] = 0
                when (slotKind) {
                    0 -> roots[slotRef] = cell
                    1 -> cellLeft[slotRef] = cell
                    else -> cellRight[slotRef] = cell
                }
            }
            val decision = low >= cutoff || (high >= cutoff && zp.decode(cellBits, cell) != 0)
            slotKind = if (decision) 2 else 1
            slotRef = cell
            when (phase) {
                1 -> {
                    negative = !decision
                    if (negative) {
                        val temp = -low - 1
                        low = -high - 1
                        high = temp
                    }
                    phase = 2
                    cutoff = 1
                }
                2 -> {
                    if (!decision) {
                        phase = 3
                        range = (cutoff + 1) / 2
                        if (range == 1) cutoff = 0 else cutoff -= range / 2
                    } else {
                        cutoff += cutoff + 1
                    }
                }
                else -> {
                    range /= 2
                    if (range != 1) {
                        if (!decision) cutoff -= range / 2 else cutoff += range / 2
                    } else if (!decision) {
                        cutoff--
                    }
                }
            }
        }
        return if (negative) -cutoff - 1 else cutoff
    }

    private fun newShape(w: Int, h: Int): Jb2Shape {
        if (w < 0 || h < 0) throw DjvuFormatException("JB2 shape has negative size")
        decodedPixels += w.toLong() * h
        if (decodedPixels > maxPixels) throw DjvuFormatException("JB2 data exceeds limit")
        return Jb2Shape(w, h, ByteArray(w * h))
    }

    private fun decodeAbsoluteSize(): Jb2Shape {
        val w = decodeNum(0, BIG_POSITIVE, ABS_SIZE_X)
        val h = decodeNum(0, BIG_POSITIVE, ABS_SIZE_Y)
        return newShape(w, h)
    }

    private fun decodeRelativeSize(cw: Int, ch: Int): Jb2Shape {
        val dx = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_SIZE_X)
        val dy = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_SIZE_Y)
        return newShape(cw + dx, ch + dy)
    }

    private fun decodeDirect(bm: Jb2Shape) {
        val w = bm.width
        val bits = bm.bits
        for (dy in bm.height - 1 downTo 0) {
            var context = (bm.get(dy + 2, -1) shl 9) or (bm.get(dy + 2, 0) shl 8) or (bm.get(dy + 2, 1) shl 7) or
                (bm.get(dy + 1, -2) shl 6) or (bm.get(dy + 1, -1) shl 5) or (bm.get(dy + 1, 0) shl 4) or
                (bm.get(dy + 1, 1) shl 3) or (bm.get(dy + 1, 2) shl 2)
            val rowBase = dy * w
            var dx = 0
            while (dx < w) {
                val n = zp.decode(directDist, context)
                bits[rowBase + dx] = n.toByte()
                dx++
                context = ((context shl 1) and 0x37A) or (bm.get(dy + 1, dx + 2) shl 2) or (bm.get(dy + 2, dx + 1) shl 7) or n
            }
        }
    }

    private fun decodeCross(bm: Jb2Shape, cbm: Jb2Shape) {
        val dw = bm.width
        val dh = bm.height
        val xd2c = (dw / 2 - dw + 1) - ((cbm.right - cbm.left + 1) / 2 - cbm.right)
        val yd2c = (dh / 2 - dh + 1) - ((cbm.top - cbm.bottom + 1) / 2 - cbm.top)
        val bits = bm.bits
        for (dy in dh - 1 downTo 0) {
            val cy = dy + yd2c
            var context = (bm.get(dy + 1, -1) shl 10) or (bm.get(dy + 1, 0) shl 9) or (bm.get(dy + 1, 1) shl 8) or
                (bm.get(dy, -1) shl 7) or (cbm.get(cy + 1, xd2c) shl 6) or
                (cbm.get(cy, xd2c - 1) shl 5) or (cbm.get(cy, xd2c) shl 4) or (cbm.get(cy, xd2c + 1) shl 3) or
                (cbm.get(cy - 1, xd2c - 1) shl 2) or (cbm.get(cy - 1, xd2c) shl 1) or cbm.get(cy - 1, xd2c + 1)
            val rowBase = dy * dw
            var dx = 0
            while (dx < dw) {
                val n = zp.decode(crossDist, context)
                bits[rowBase + dx] = n.toByte()
                dx++
                val cx = dx + xd2c
                context = ((context shl 1) and 0x636) or (bm.get(dy + 1, dx + 1) shl 8) or (cbm.get(cy + 1, cx) shl 6) or
                    (cbm.get(cy, cx + 1) shl 3) or cbm.get(cy - 1, cx + 1) or (n shl 7)
            }
        }
    }

    private fun addLibrary(shape: Jb2Shape) {
        shape.computeBoundingBox()
        library.add(shape)
    }

    private fun fillShortList(v: Int) {
        shortList[0] = v
        shortList[1] = v
        shortList[2] = v
        shortListPos = 0
    }

    private fun updateShortList(v: Int): Int {
        if (++shortListPos == 3) shortListPos = 0
        shortList[shortListPos] = v
        val a = shortList[0]
        val b = shortList[1]
        val c = shortList[2]
        return if (a >= b) {
            if (b >= c) b else if (a >= c) c else a
        } else {
            if (a >= c) a else if (b >= c) c else b
        }
    }

    private fun addBlit(shape: Jb2Shape, left: Int, bottom: Int) {
        val n = blitShapes.size
        if (n >= blitLeft.size) {
            blitLeft = blitLeft.copyOf(n * 2)
            blitBottom = blitBottom.copyOf(n * 2)
        }
        blitShapes.add(shape)
        blitLeft[n] = left
        blitBottom[n] = bottom
    }

    private fun decodeRelativeLocation(rows: Int, columns: Int): IntArray {
        if (!gotStart) throw DjvuFormatException("JB2 location before start record")
        val left: Int
        val bottom: Int
        if (zp.decode(offsetTypeDist, 0) != 0) {
            val xDiff = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_LOC_X_LAST)
            val yDiff = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_LOC_Y_LAST)
            left = lastRowLeft + xDiff
            val top = lastRowBottom + yDiff
            val right = left + columns - 1
            bottom = top - rows + 1
            lastLeft = left
            lastRowLeft = left
            lastRight = right
            lastBottom = bottom
            lastRowBottom = bottom
            fillShortList(bottom)
        } else {
            val xDiff = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_LOC_X_CURRENT)
            val yDiff = decodeNum(BIG_NEGATIVE, BIG_POSITIVE, REL_LOC_Y_CURRENT)
            left = lastRight + xDiff
            bottom = lastBottom + yDiff
            val right = left + columns - 1
            lastLeft = left
            lastRight = right
            lastBottom = updateShortList(bottom)
        }
        return intArrayOf(left - 1, bottom - 1)
    }

    private fun matchLibrary(): Jb2Shape {
        if (library.isEmpty()) throw DjvuFormatException("JB2 match without library")
        val index = decodeNum(0, library.size - 1, MATCH_INDEX)
        return library.getOrNull(index) ?: throw DjvuFormatException("JB2 match index is invalid")
    }

    private fun startRecord(isDict: Boolean) {
        val w = decodeNum(0, BIG_POSITIVE, IMAGE_SIZE)
        val h = decodeNum(0, BIG_POSITIVE, IMAGE_SIZE)
        zp.decode(refinementDist, 0)
        if (isDict) {
            lastLeft = 1
            lastRowBottom = 0
        } else {
            if (w == 0 || h == 0) throw DjvuFormatException("JB2 image has zero size")
            imageWidth = w
            imageHeight = h
            lastLeft = 1 + w
            lastRowBottom = h
        }
        lastRowLeft = 0
        lastRight = 0
        fillShortList(lastRowBottom)
        gotStart = true
        val dict = inherited
        if (dict != null) {
            for (i in 0 until minOf(inheritedCount, dict.size)) {
                val s = dict[i]
                shapes.add(s)
                library.add(s)
            }
        }
    }

    private fun run(isDict: Boolean) {
        var records = 0
        while (true) {
            if (++records > MAX_RECORDS) throw DjvuFormatException("JB2 stream has too many records")
            val rectype = decodeNum(START_OF_DATA, END_OF_DATA, RECORD_TYPE)
            when (rectype) {
                START_OF_DATA -> {
                    if (gotStart) throw DjvuFormatException("JB2 duplicate start record")
                    startRecord(isDict)
                }
                NEW_MARK -> {
                    val bm = decodeAbsoluteSize()
                    decodeDirect(bm)
                    shapes.add(bm)
                    addLibrary(bm)
                    val loc = decodeRelativeLocation(bm.height, bm.width)
                    addBlit(bm, loc[0], loc[1])
                }
                NEW_MARK_LIBRARY_ONLY -> {
                    val bm = decodeAbsoluteSize()
                    decodeDirect(bm)
                    shapes.add(bm)
                    addLibrary(bm)
                }
                NEW_MARK_IMAGE_ONLY -> {
                    val bm = decodeAbsoluteSize()
                    decodeDirect(bm)
                    val loc = decodeRelativeLocation(bm.height, bm.width)
                    addBlit(bm, loc[0], loc[1])
                }
                MATCHED_REFINE, MATCHED_REFINE_LIBRARY_ONLY, MATCHED_REFINE_IMAGE_ONLY -> {
                    val cbm = matchLibrary()
                    val bm = decodeRelativeSize(cbm.right - cbm.left + 1, cbm.top - cbm.bottom + 1)
                    decodeCross(bm, cbm)
                    finishRefine(bm, rectype)
                }
                MATCHED_COPY -> {
                    val shape = matchLibrary()
                    val loc = decodeRelativeLocation(shape.top - shape.bottom + 1, shape.right - shape.left + 1)
                    addBlit(shape, loc[0] - shape.left, loc[1] - shape.bottom)
                }
                NON_MARK_DATA -> {
                    val bm = decodeAbsoluteSize()
                    decodeDirect(bm)
                    val left = decodeNum(1, imageWidth, ABS_LOC_X)
                    val top = decodeNum(1, imageHeight, ABS_LOC_Y)
                    addBlit(bm, left - 1, top - bm.height)
                }
                REQUIRED_DICT_OR_RESET -> {
                    if (!gotStart) {
                        inheritedCount = decodeNum(0, BIG_POSITIVE, INHERITED_COUNT)
                        val dict = inherited
                        if (inheritedCount > 0 && (dict == null || dict.size != inheritedCount)) {
                            throw DjvuFormatException("JB2 shared dictionary is missing")
                        }
                    } else {
                        resetNumCoder()
                    }
                }
                PRESERVED_COMMENT -> {
                    val size = decodeNum(0, BIG_POSITIVE, COMMENT_LENGTH)
                    repeat(size) { decodeNum(0, 255, COMMENT_BYTE) }
                }
                END_OF_DATA -> return
                else -> throw DjvuFormatException("JB2 record type is invalid")
            }
        }
    }

    private fun finishRefine(bm: Jb2Shape, rectype: Int) {
        when (rectype) {
            MATCHED_REFINE -> {
                shapes.add(bm)
                addLibrary(bm)
                val loc = decodeRelativeLocation(bm.height, bm.width)
                addBlit(bm, loc[0], loc[1])
            }
            MATCHED_REFINE_LIBRARY_ONLY -> {
                shapes.add(bm)
                addLibrary(bm)
            }
            else -> {
                val loc = decodeRelativeLocation(bm.height, bm.width)
                addBlit(bm, loc[0], loc[1])
            }
        }
    }

    companion object {
        private const val START_OF_DATA = 0
        private const val NEW_MARK = 1
        private const val NEW_MARK_LIBRARY_ONLY = 2
        private const val NEW_MARK_IMAGE_ONLY = 3
        private const val MATCHED_REFINE = 4
        private const val MATCHED_REFINE_LIBRARY_ONLY = 5
        private const val MATCHED_REFINE_IMAGE_ONLY = 6
        private const val MATCHED_COPY = 7
        private const val NON_MARK_DATA = 8
        private const val REQUIRED_DICT_OR_RESET = 9
        private const val PRESERVED_COMMENT = 10
        private const val END_OF_DATA = 11

        private const val COMMENT_BYTE = 0
        private const val COMMENT_LENGTH = 1
        private const val RECORD_TYPE = 2
        private const val MATCH_INDEX = 3
        private const val ABS_LOC_X = 4
        private const val ABS_LOC_Y = 5
        private const val ABS_SIZE_X = 6
        private const val ABS_SIZE_Y = 7
        private const val IMAGE_SIZE = 8
        private const val INHERITED_COUNT = 9
        private const val REL_LOC_X_CURRENT = 10
        private const val REL_LOC_X_LAST = 11
        private const val REL_LOC_Y_CURRENT = 12
        private const val REL_LOC_Y_LAST = 13
        private const val REL_SIZE_X = 14
        private const val REL_SIZE_Y = 15

        private const val BIG_POSITIVE = 262142
        private const val BIG_NEGATIVE = -262143
        private const val MAX_CELLS = 1 shl 22
        private const val MAX_RECORDS = 4_000_000

        fun decodeDictionary(data: ByteArray, offset: Int, length: Int, inherited: List<Jb2Shape>?, maxPixels: Long = 64_000_000L): List<Jb2Shape> {
            val d = Jb2Decoder(data, offset, length, inherited, maxPixels)
            d.run(true)
            return d.shapes
        }

        fun decodeImage(data: ByteArray, offset: Int, length: Int, inherited: List<Jb2Shape>?, maxPixels: Long = 64_000_000L): Jb2Image {
            val d = Jb2Decoder(data, offset, length, inherited, maxPixels)
            d.run(false)
            if (!d.gotStart) throw DjvuFormatException("JB2 image has no start record")
            return Jb2Image(d.imageWidth, d.imageHeight, d.shapes, d.blitShapes, d.blitLeft.copyOf(d.blitShapes.size), d.blitBottom.copyOf(d.blitShapes.size))
        }
    }
}

class Iw44Plane(val width: Int, val height: Int) {
    private val bw = (width + 31) and 31.inv()
    private val bh = (height + 31) and 31.inv()
    private val blockCount = (bw / 32) * (bh / 32)
    private val buckets = arrayOfNulls<ShortArray>(blockCount * 64)
    private var curband = 0
    private var curbit = 1
    private val quantLo = IntArray(16)
    private val quantHi = IntArray(10)
    private val coeffState = ByteArray(256)
    private val bucketState = ByteArray(16)
    private val ctxStart = ByteArray(32)
    private val ctxBucket = ByteArray(80)
    private val ctxMant = ByteArray(1)
    private val ctxRoot = ByteArray(1)

    init {
        var i = 0
        var q = 0
        while (i < 4) quantLo[i++] = IW_QUANT[q++]
        repeat(4) { quantLo[i++] = IW_QUANT[q] }
        q++
        repeat(4) { quantLo[i++] = IW_QUANT[q] }
        q++
        repeat(4) { quantLo[i++] = IW_QUANT[q] }
        q++
        quantHi[0] = 0
        for (j in 1 until 10) quantHi[j] = IW_QUANT[q++]
    }

    fun codeSlice(zp: DjvuZpDecoder): Boolean {
        if (curbit < 0) return false
        if (!isNullSlice(curband)) {
            val fbucket = BAND_START[curband]
            val nbucket = BAND_SIZE[curband]
            for (block in 0 until blockCount) decodeBuckets(zp, curband, block * 64, fbucket, nbucket)
        }
        quantHi[curband] = quantHi[curband] shr 1
        if (curband == 0) for (i in 0 until 16) quantLo[i] = quantLo[i] shr 1
        if (++curband >= 10) {
            curband = 0
            curbit++
            if (quantHi[9] == 0) {
                curbit = -1
                return false
            }
        }
        return true
    }

    private fun isNullSlice(band: Int): Boolean {
        if (band == 0) {
            var isNull = true
            for (i in 0 until 16) {
                val threshold = quantLo[i]
                coeffState[i] = ZERO
                if (threshold > 0 && threshold < 0x8000) {
                    coeffState[i] = UNK
                    isNull = false
                }
            }
            return isNull
        }
        val threshold = quantHi[band]
        return !(threshold > 0 && threshold < 0x8000)
    }

    private fun prepare(blockBase: Int, fbucket: Int, nbucket: Int): Int {
        var bbstate = 0
        if (fbucket != 0) {
            for (buckno in 0 until nbucket) {
                var bstate = 0
                val coeffs = buckets[blockBase + fbucket + buckno]
                if (coeffs == null) {
                    bstate = UNK.toInt()
                } else {
                    for (i in 0 until 16) {
                        val cs = if (coeffs[i].toInt() != 0) ACTIVE else UNK
                        coeffState[buckno * 16 + i] = cs
                        bstate = bstate or cs.toInt()
                    }
                }
                bucketState[buckno] = bstate.toByte()
                bbstate = bbstate or bstate
            }
        } else {
            val coeffs = buckets[blockBase]
            if (coeffs == null) {
                bbstate = UNK.toInt()
            } else {
                for (i in 0 until 16) {
                    var cs = coeffState[i]
                    if (cs != ZERO) cs = if (coeffs[i].toInt() != 0) ACTIVE else UNK
                    coeffState[i] = cs
                    bbstate = bbstate or cs.toInt()
                }
            }
            bucketState[0] = bbstate.toByte()
        }
        return bbstate
    }

    private fun decodeBuckets(zp: DjvuZpDecoder, band: Int, blockBase: Int, fbucket: Int, nbucket: Int) {
        var bbstate = prepare(blockBase, fbucket, nbucket)
        if (nbucket < 16 || (bbstate and ACTIVE.toInt()) != 0) {
            bbstate = bbstate or NEW.toInt()
        } else if ((bbstate and UNK.toInt()) != 0) {
            if (zp.decode(ctxRoot, 0) != 0) bbstate = bbstate or NEW.toInt()
        }
        if ((bbstate and NEW.toInt()) != 0) {
            for (buckno in 0 until nbucket) {
                if ((bucketState[buckno].toInt() and UNK.toInt()) == 0) continue
                var ctx = 0
                if (band > 0) {
                    val k = (fbucket + buckno) shl 2
                    val b = buckets[blockBase + (k shr 4)]
                    if (b != null) {
                        val kk = k and 0xF
                        if (b[kk].toInt() != 0) ctx++
                        if (b[kk + 1].toInt() != 0) ctx++
                        if (b[kk + 2].toInt() != 0) ctx++
                        if (ctx < 3 && b[kk + 3].toInt() != 0) ctx++
                    }
                }
                if ((bbstate and ACTIVE.toInt()) != 0) ctx = ctx or 4
                if (zp.decode(ctxBucket, band * 8 + ctx) != 0) {
                    bucketState[buckno] = (bucketState[buckno].toInt() or NEW.toInt()).toByte()
                }
            }
            var thres = quantHi[band]
            for (buckno in 0 until nbucket) {
                if ((bucketState[buckno].toInt() and NEW.toInt()) == 0) continue
                val cs = buckno * 16
                var coeffs = buckets[blockBase + fbucket + buckno]
                if (coeffs == null) {
                    coeffs = ShortArray(16)
                    buckets[blockBase + fbucket + buckno] = coeffs
                    if (fbucket == 0) {
                        for (i in 0 until 16) if (coeffState[cs + i] != ZERO) coeffState[cs + i] = UNK
                    } else {
                        for (i in 0 until 16) coeffState[cs + i] = UNK
                    }
                }
                var gotcha = 0
                for (i in 0 until 16) if ((coeffState[cs + i].toInt() and UNK.toInt()) != 0) gotcha++
                for (i in 0 until 16) {
                    if ((coeffState[cs + i].toInt() and UNK.toInt()) == 0) continue
                    if (band == 0) thres = quantLo[i]
                    var ctx = minOf(gotcha, 7)
                    if ((bucketState[buckno].toInt() and ACTIVE.toInt()) != 0) ctx = ctx or 8
                    if (zp.decode(ctxStart, ctx) != 0) {
                        coeffState[cs + i] = (coeffState[cs + i].toInt() or NEW.toInt()).toByte()
                        val half = thres shr 1
                        val coeff = thres + half - (half shr 2)
                        coeffs[i] = (if (zp.decodeIw() != 0) -coeff else coeff).toShort()
                    }
                    if ((coeffState[cs + i].toInt() and NEW.toInt()) != 0) gotcha = 0 else if (gotcha > 0) gotcha--
                }
            }
        }
        if ((bbstate and ACTIVE.toInt()) != 0) {
            var thres = quantHi[band]
            for (buckno in 0 until nbucket) {
                if ((bucketState[buckno].toInt() and ACTIVE.toInt()) == 0) continue
                val cs = buckno * 16
                val coeffs = buckets[blockBase + fbucket + buckno] ?: continue
                for (i in 0 until 16) {
                    if ((coeffState[cs + i].toInt() and ACTIVE.toInt()) == 0) continue
                    val original = coeffs[i].toInt()
                    var coeff = if (original < 0) -original else original
                    if (band == 0) thres = quantLo[i]
                    if (coeff <= 3 * thres) {
                        coeff += thres shr 2
                        coeff = if (zp.decode(ctxMant, 0) != 0) coeff + (thres shr 1) else coeff - thres + (thres shr 1)
                    } else {
                        coeff = if (zp.decodeIw() != 0) coeff + (thres shr 1) else coeff - thres + (thres shr 1)
                    }
                    coeffs[i] = (if (original > 0) coeff else -coeff).toShort()
                }
            }
        }
    }

    fun reconstruct(fast: Boolean): ByteArray {
        val data = ShortArray(bw * bh)
        var block = 0
        for (by in 0 until bh step 32) {
            for (bx in 0 until bw step 32) {
                val base = block * 64
                for (n1 in 0 until 64) {
                    val d = buckets[base + n1] ?: continue
                    for (n2 in 0 until 16) {
                        val loc = ZIGZAG[(n1 shl 4) + n2]
                        data[(by + (loc shr 5)) * bw + bx + (loc and 31)] = d[n2]
                    }
                }
                block++
            }
        }
        if (fast) {
            backward(data, width, height, bw, 32, 2)
            var p = 0
            for (i in 0 until bh step 2) {
                var jj = 0
                while (jj < bw) {
                    val v = data[p]
                    data[p + 1] = v
                    if (p + bw + 1 < data.size) {
                        data[p + bw] = v
                        data[p + bw + 1] = v
                    }
                    p += 2
                    jj += 2
                }
                p += bw
            }
        } else {
            backward(data, width, height, bw, 32, 1)
        }
        val out = ByteArray(width * height)
        for (i in 0 until height) {
            val src = i * bw
            val dst = (height - 1 - i) * width
            for (j in 0 until width) {
                val x = (data[src + j] + 32) shr 6
                out[dst + j] = x.coerceIn(-128, 127).toByte()
            }
        }
        return out
    }

    companion object {
        private const val ZERO: Byte = 1
        private const val ACTIVE: Byte = 2
        private const val NEW: Byte = 4
        private const val UNK: Byte = 8
        private val IW_QUANT = intArrayOf(
            0x004000, 0x008000, 0x008000, 0x010000, 0x010000, 0x010000, 0x020000, 0x020000,
            0x020000, 0x040000, 0x040000, 0x040000, 0x080000, 0x040000, 0x040000, 0x080000
        )
        private val BAND_START = intArrayOf(0, 1, 2, 3, 4, 8, 12, 16, 32, 48)
        private val BAND_SIZE = intArrayOf(1, 1, 1, 1, 4, 4, 4, 16, 16, 16)
        private val ZIGZAG = IntArray(1024) { i ->
            val x = ((i and 1) shl 4) or ((i and 4) shl 1) or ((i and 16) shr 2) or ((i and 64) shr 5) or ((i and 256) shr 8)
            val y = ((i and 2) shl 3) or ((i and 8)) or ((i and 32) shr 3) or ((i and 128) shr 6) or ((i and 512) shr 9)
            (y shl 5) or x
        }

        private fun backward(p: ShortArray, w: Int, h: Int, rowsize: Int, begin: Int, end: Int) {
            var scale = begin shr 1
            while (scale >= end) {
                filterBv(p, w, h, rowsize, scale)
                filterBh(p, w, h, rowsize, scale)
                scale = scale shr 1
            }
        }

        private fun filterBv(p: ShortArray, w: Int, hIn: Int, rowsize: Int, scale: Int) {
            val s = scale * rowsize
            val s3 = s + s + s
            val h = ((hIn - 1) / scale) + 1
            var y = 0
            var base = 0
            while (y - 3 < h) {
                run {
                    var q = base
                    val e = q + w
                    if (y >= 3 && y + 3 < h) {
                        while (q < e) {
                            val a = p[q - s] + p[q + s]
                            val b = p[q - s3] + p[q + s3]
                            p[q] = (p[q] - (((a shl 3) + a - b + 16) shr 5)).toShort()
                            q += scale
                        }
                    } else if (y < h) {
                        val hasQ1 = y + 1 < h
                        val hasQ3 = y + 3 < h
                        while (q < e) {
                            val q1 = if (hasQ1) p[q + s].toInt() else 0
                            val q3 = if (hasQ3) p[q + s3].toInt() else 0
                            val a: Int
                            val b: Int
                            if (y >= 3) {
                                a = p[q - s] + q1
                                b = p[q - s3] + q3
                            } else if (y >= 1) {
                                a = p[q - s] + q1
                                b = q3
                            } else {
                                a = q1
                                b = q3
                            }
                            p[q] = (p[q] - (((a shl 3) + a - b + 16) shr 5)).toShort()
                            q += scale
                        }
                    }
                }
                run {
                    var q = base - s3
                    val e = q + w
                    if (y >= 6 && y < h) {
                        while (q < e) {
                            val a = p[q - s] + p[q + s]
                            val b = p[q - s3] + p[q + s3]
                            p[q] = (p[q] + (((a shl 3) + a - b + 8) shr 4)).toShort()
                            q += scale
                        }
                    } else if (y >= 3) {
                        var q1 = if (y - 2 < h) q + s else q - s
                        while (q < e) {
                            val a = p[q - s] + p[q1]
                            p[q] = (p[q] + ((a + 1) shr 1)).toShort()
                            q += scale
                            q1 += scale
                        }
                    }
                }
                y += 2
                base += s + s
            }
        }

        private fun filterBh(p: ShortArray, w: Int, h: Int, rowsizeIn: Int, scale: Int) {
            val s = scale
            val s3 = s + s + s
            val rowsize = rowsizeIn * scale
            var y = 0
            var base = 0
            while (y < h) {
                var q = base
                val e = base + w
                var a0 = 0; var a1 = 0; var a2 = 0; var a3 = 0
                var b0: Int; var b1 = 0; var b2 = 0; var b3 = 0
                if (q < e) {
                    if (q + s < e) a2 = p[q + s].toInt()
                    if (q + s3 < e) a3 = p[q + s3].toInt()
                    b3 = p[q] - ((((a1 + a2) shl 3) + (a1 + a2) - a0 - a3 + 16) shr 5)
                    b2 = b3
                    p[q] = b3.toShort()
                    q += s + s
                }
                if (q < e) {
                    a0 = a1; a1 = a2; a2 = a3
                    if (q + s3 < e) a3 = p[q + s3].toInt()
                    b3 = p[q] - ((((a1 + a2) shl 3) + (a1 + a2) - a0 - a3 + 16) shr 5)
                    p[q] = b3.toShort()
                    q += s + s
                }
                if (q < e) {
                    b1 = b2; b2 = b3
                    a0 = a1; a1 = a2; a2 = a3
                    if (q + s3 < e) a3 = p[q + s3].toInt()
                    b3 = p[q] - ((((a1 + a2) shl 3) + (a1 + a2) - a0 - a3 + 16) shr 5)
                    p[q] = b3.toShort()
                    p[q - s3] = (p[q - s3] + ((b1 + b2 + 1) shr 1)).toShort()
                    q += s + s
                }
                while (q + s3 < e) {
                    a0 = a1; a1 = a2; a2 = a3; a3 = p[q + s3].toInt()
                    b0 = b1; b1 = b2; b2 = b3
                    b3 = p[q] - ((((a1 + a2) shl 3) + (a1 + a2) - a0 - a3 + 16) shr 5)
                    p[q] = b3.toShort()
                    p[q - s3] = (p[q - s3] + ((((b1 + b2) shl 3) + (b1 + b2) - b0 - b3 + 8) shr 4)).toShort()
                    q += s + s
                }
                while (q < e) {
                    a0 = a1; a1 = a2; a2 = a3; a3 = 0
                    b0 = b1; b1 = b2; b2 = b3
                    b3 = p[q] - ((((a1 + a2) shl 3) + (a1 + a2) - a0 - a3 + 16) shr 5)
                    p[q] = b3.toShort()
                    p[q - s3] = (p[q - s3] + ((((b1 + b2) shl 3) + (b1 + b2) - b0 - b3 + 8) shr 4)).toShort()
                    q += s + s
                }
                while (q - s3 < e) {
                    b1 = b2; b2 = b3
                    if (q - s3 >= base) p[q - s3] = (p[q - s3] + ((b1 + b2 + 1) shr 1)).toShort()
                    q += s + s
                }
                y += scale
                base += rowsize
            }
        }
    }
}

class DjvuRaster(val width: Int, val height: Int, val argb: IntArray)

class Iw44Image {
    var width = 0
        private set
    var height = 0
        private set
    var isColor = false
        private set
    private var ymap: Iw44Plane? = null
    private var cbmap: Iw44Plane? = null
    private var crmap: Iw44Plane? = null
    private var crcbDelay = 0
    private var crcbHalf = false
    private var cslice = 0
    private var cserial = 0

    val isEmpty: Boolean get() = ymap == null

    fun decodeChunk(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length < 2) throw DjvuFormatException("IW44 chunk is truncated")
        val serial = data[offset].toInt() and 0xFF
        val slices = data[offset + 1].toInt() and 0xFF
        var p = offset + 2
        if (serial != cserial) throw DjvuFormatException("IW44 chunk is out of order")
        if (serial == 0) {
            if (length < 8) throw DjvuFormatException("IW44 header is truncated")
            val major = data[p].toInt() and 0xFF
            val minor = data[p + 1].toInt() and 0xFF
            p += 2
            if ((major and 0x7F) != 1) throw DjvuFormatException("IW44 version is unsupported")
            val w = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            val h = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            p += 4
            var crcb = 0
            if (minor >= 2) {
                if (p >= offset + length) throw DjvuFormatException("IW44 header is truncated")
                crcb = data[p].toInt() and 0xFF
                p++
            }
            if (w <= 0 || h <= 0) throw DjvuFormatException("IW44 image has zero size")
            if (w.toLong() * h > 50_000_000L) throw DjvuFormatException("IW44 image is too large")
            width = w
            height = h
            ymap = Iw44Plane(w, h)
            isColor = (major and 0x80) == 0
            if (isColor) {
                crcbDelay = if (minor >= 2) crcb and 0x7F else 0
                crcbHalf = minor >= 2 && (crcb and 0x80) == 0
                cbmap = Iw44Plane(w, h)
                crmap = Iw44Plane(w, h)
            } else {
                crcbDelay = -1
            }
        }
        val y = ymap ?: throw DjvuFormatException("IW44 data without header")
        val zp = DjvuZpDecoder(data, p, offset + length)
        val nslices = cslice + slices
        var flag = true
        while (flag && cslice < nslices) {
            flag = y.codeSlice(zp)
            val cb = cbmap
            val cr = crmap
            if (cb != null && cr != null && crcbDelay >= 0 && crcbDelay <= cslice) {
                val f1 = cb.codeSlice(zp)
                val f2 = cr.codeSlice(zp)
                flag = flag || f1 || f2
            }
            cslice++
        }
        cserial++
    }

    fun toRaster(): DjvuRaster {
        val y = ymap ?: throw DjvuFormatException("IW44 image is empty")
        val yy = y.reconstruct(false)
        val out = IntArray(width * height)
        val cb = cbmap
        val cr = crmap
        if (isColor && cb != null && cr != null && crcbDelay >= 0) {
            val bb = cb.reconstruct(crcbHalf)
            val rr = cr.reconstruct(crcbHalf)
            for (i in out.indices) {
                val yv = yy[i].toInt()
                val b = bb[i].toInt()
                val r = rr[i].toInt()
                val t1 = b shr 2
                val t2 = r + (r shr 1)
                val t3 = yv + 128 - t1
                val tr = (yv + 128 + t2).coerceIn(0, 255)
                val tg = (t3 - (t2 shr 1)).coerceIn(0, 255)
                val tb = (t3 + (b shl 1)).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (tr shl 16) or (tg shl 8) or tb
            }
        } else {
            for (i in out.indices) {
                val v = (127 - yy[i].toInt()).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return DjvuRaster(width, height, out)
    }
}

class DjvuTextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

class DjvuTextLayer(val text: String, val lines: List<DjvuTextLine>)

object DjvuTextParser {
    private const val PAGE = 1
    private const val PARAGRAPH = 4
    private const val LINE = 5
    private const val WORD = 6

    private class Zone(val type: Int, val xmin: Int, val ymin: Int, val xmax: Int, val ymax: Int, val textStart: Int, val textLength: Int) {
        val children = ArrayList<Zone>()
    }

    private class Reader(val b: ByteArray) {
        var p = 0
        fun u8(): Int {
            if (p >= b.size) throw DjvuFormatException("Text zone data is truncated")
            return b[p++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
    }

    fun parse(raw: ByteArray, pageHeight: Int): DjvuTextLayer {
        val r = Reader(raw)
        val len = r.u24()
        if (len > raw.size - 3) throw DjvuFormatException("Text layer is truncated")
        val textBytes = raw.copyOfRange(3, 3 + len)
        val text = String(textBytes, StandardCharsets.UTF_8)
        r.p = 3 + len
        val lines = ArrayList<DjvuTextLine>()
        if (r.p < raw.size) {
            r.u8()
            val root = try { decodeZone(r, null, null, 0) } catch (_: DjvuFormatException) { null }
            if (root != null) {
                val lineZones = ArrayList<Zone>()
                collect(root, LINE, lineZones)
                if (lineZones.isEmpty()) collect(root, WORD, lineZones)
                for (z in lineZones) {
                    val s = z.textStart.coerceIn(0, textBytes.size)
                    val e = (z.textStart + z.textLength).coerceIn(s, textBytes.size)
                    val t = String(textBytes, s, e - s, StandardCharsets.UTF_8).replace(Regex("[\\u0000-\\u001F]+"), " ").trim()
                    if (t.isEmpty() || z.xmax <= z.xmin || z.ymax <= z.ymin) continue
                    lines.add(DjvuTextLine(t, z.xmin, pageHeight - z.ymax, z.xmax, pageHeight - z.ymin))
                }
            }
        }
        return DjvuTextLayer(text.replace(Regex("[\\u0000-\\u0008\\u000E-\\u001F]"), "\n").replace('\u000B', '\n').replace('\u001D', '\n').trim(), lines)
    }

    private fun collect(z: Zone, type: Int, out: MutableList<Zone>) {
        if (z.type == type) {
            out.add(z)
            return
        }
        for (c in z.children) collect(c, type, out)
    }

    private fun decodeZone(r: Reader, parent: Zone?, prev: Zone?, depth: Int): Zone {
        if (depth > 32) throw DjvuFormatException("Text zones are nested too deeply")
        val type = r.u8()
        var x = r.u16() - 0x8000
        var y = r.u16() - 0x8000
        val width = r.u16() - 0x8000
        val height = r.u16() - 0x8000
        var textStart = r.u16() - 0x8000
        val textLength = r.u24()
        if (prev != null) {
            if (type == PAGE || type == PARAGRAPH || type == LINE) {
                x += prev.xmin
                y = prev.ymin - (y + height)
            } else {
                x += prev.xmax
                y += prev.ymin
            }
            textStart += prev.textStart + prev.textLength
        } else if (parent != null) {
            x += parent.xmin
            y = parent.ymax - (y + height)
            textStart += parent.textStart
        }
        val zone = Zone(type, x, y, x + width, y + height, textStart, textLength)
        val count = r.u24()
        var prevChild: Zone? = null
        for (i in 0 until count) {
            val child = decodeZone(r, zone, prevChild, depth + 1)
            zone.children.add(child)
            prevChild = child
        }
        return zone
    }
}

interface DjvuByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class DjvuByteArraySource(private val bytes: ByteArray) : DjvuByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset > bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

class DjvuChunk(val id: String, val data: ByteArray, val offset: Int, val length: Int)

class DjvuPageInfo(val width: Int, val height: Int, val dpi: Int, val rotation: Int)

class DjvuPage(
    val info: DjvuPageInfo,
    val background: DjvuRaster?,
    val foreground: DjvuRaster?,
    val mask: Jb2Image?,
    val blitColors: IntArray?,
    val text: DjvuTextLayer?
)

class CleanRoomDjvuDocument(private val source: DjvuByteSource, private val jpegDecoder: ((ByteArray) -> DjvuRaster?)? = null) {
    private class FormRef(val offset: Long, val length: Int, val type: String)

    private val pageForms = ArrayList<FormRef>()
    private val filesById = HashMap<String, FormRef>()
    private val dictCache = HashMap<String, List<Jb2Shape>>()
    var isIndirect = false
        private set

    val pageCount: Int get() = pageForms.size

    init {
        val head = source.read(0, 16)
        var p = 0
        if (head.size >= 4 && String(head, 0, 4, StandardCharsets.US_ASCII) == "AT&T") p = 4
        if (head.size < p + 12 || String(head, p, 4, StandardCharsets.US_ASCII) != "FORM") throw DjvuFormatException("Not a DjVu file")
        val formLen = be32(head, p + 4)
        val type = String(head, p + 8, 4, StandardCharsets.US_ASCII)
        val formEnd = minOf(source.size, p + 8L + formLen)
        when (type) {
            "DJVU", "PM44", "BM44" -> pageForms.add(FormRef(p.toLong(), (formEnd - p).toInt(), type))
            "DJVM" -> parseBundle(p + 12L, formEnd)
            else -> throw DjvuFormatException("Unsupported DjVu form $type")
        }
        if (pageForms.isEmpty()) {
            throw DjvuFormatException(if (isIndirect) "Indirect multi-file DjVu documents need their page files" else "DjVu document has no pages")
        }
    }

    private fun be32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun readFormRef(offset: Long, limit: Long): FormRef? {
        val h = source.read(offset, 12)
        if (h.size < 12 || String(h, 0, 4, StandardCharsets.US_ASCII) != "FORM") return null
        val len = be32(h, 4)
        if (len < 4 || offset + 8 + len > limit) return null
        return FormRef(offset, (8 + len).toInt(), String(h, 8, 4, StandardCharsets.US_ASCII))
    }

    private fun parseBundle(start: Long, end: Long) {
        val children = ArrayList<FormRef>()
        var dirm: ByteArray? = null
        var pos = start
        while (pos + 8 <= end) {
            val h = source.read(pos, 12)
            if (h.size < 8) break
            val id = String(h, 0, 4, StandardCharsets.US_ASCII)
            val len = be32(h, 4)
            if (pos + 8 + len > end) break
            if (id == "DIRM") {
                dirm = source.read(pos + 8, len.toInt())
            } else if (id == "FORM" && h.size >= 12) {
                children.add(FormRef(pos, (8 + len).toInt(), String(h, 8, 4, StandardCharsets.US_ASCII)))
            }
            pos += 8 + ((len + 1) and -2L)
        }
        val dir = dirm
        if (dir != null && dir.size >= 3) {
            val flags = dir[0].toInt() and 0xFF
            val count = ((dir[1].toInt() and 0xFF) shl 8) or (dir[2].toInt() and 0xFF)
            val bundled = (flags and 0x80) != 0
            if (!bundled) {
                isIndirect = true
                return
            }
            if (dir.size >= 3 + count * 4) {
                val offsets = LongArray(count) { be32(dir, 3 + it * 4) }
                val meta = try { DjvuBzz.decode(dir, 3 + count * 4, dir.size - 3 - count * 4) } catch (_: DjvuFormatException) { null }
                if (meta != null && meta.size >= count * 4) {
                    var mp = count * 3
                    val fileFlags = IntArray(count) { meta[mp + it].toInt() and 0xFF }
                    mp += count
                    val ids = ArrayList<String>(count)
                    for (i in 0 until count) {
                        val s = mp
                        while (mp < meta.size && meta[mp].toInt() != 0) mp++
                        ids.add(String(meta, s, mp - s, StandardCharsets.UTF_8))
                        mp++
                    }
                    for (i in 0 until count) {
                        val ref = readFormRef(offsets[i], end) ?: continue
                        filesById[ids[i]] = ref
                        if ((fileFlags[i] and 0x3F) == 1) pageForms.add(ref)
                    }
                    if (pageForms.isNotEmpty()) return
                }
            }
        }
        for (c in children) if (c.type == "DJVU" || c.type == "PM44" || c.type == "BM44") pageForms.add(c)
    }

    private fun readChunks(form: FormRef): List<DjvuChunk> {
        if (form.length > MAX_FORM_BYTES) throw DjvuFormatException("DjVu page is too large")
        val data = source.read(form.offset, form.length)
        val out = ArrayList<DjvuChunk>()
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, StandardCharsets.US_ASCII)
            val len = be32(data, pos + 4)
            val start = pos + 8
            if (len > data.size - start) break
            out.add(DjvuChunk(id, data, start, len.toInt()))
            pos = start + ((len.toInt() + 1) and -2)
        }
        return out
    }

    fun pageInfo(index: Int): DjvuPageInfo {
        val form = pageForms[index]
        val chunks = readChunks(form)
        return parseInfo(chunks, form.type)
    }

    private fun parseInfo(chunks: List<DjvuChunk>, type: String): DjvuPageInfo {
        val info = chunks.firstOrNull { it.id == "INFO" }
        if (info != null && info.length >= 4) {
            val d = info.data
            val o = info.offset
            val w = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
            val h = ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
            var dpi = 300
            if (info.length >= 8) dpi = (d[o + 6].toInt() and 0xFF) or ((d[o + 7].toInt() and 0xFF) shl 8)
            if (dpi !in 25..6000) dpi = 300
            var rotation = 0
            if (info.length >= 10) {
                rotation = when (d[o + 9].toInt() and 0x07) {
                    6 -> 270
                    2 -> 180
                    5 -> 90
                    else -> 0
                }
            }
            if (w > 0 && h > 0) return DjvuPageInfo(w, h, dpi, rotation)
        }
        val iw = chunks.firstOrNull { it.id == "BG44" || it.id == "PM44" || it.id == "BM44" }
        if (iw != null && iw.length >= 8 && iw.data[iw.offset].toInt() == 0) {
            val d = iw.data
            val o = iw.offset
            val w = ((d[o + 4].toInt() and 0xFF) shl 8) or (d[o + 5].toInt() and 0xFF)
            val h = ((d[o + 6].toInt() and 0xFF) shl 8) or (d[o + 7].toInt() and 0xFF)
            if (w > 0 && h > 0) return DjvuPageInfo(w, h, 300, 0)
        }
        throw DjvuFormatException("DjVu page ${type} has no INFO chunk")
    }

    fun pageText(index: Int): DjvuTextLayer? {
        val form = pageForms[index]
        val chunks = readChunks(form)
        val info = parseInfo(chunks, form.type)
        return parseText(chunks, info)
    }

    private fun parseText(chunks: List<DjvuChunk>, info: DjvuPageInfo): DjvuTextLayer? {
        for (c in chunks) {
            try {
                when (c.id) {
                    "TXTz" -> return DjvuTextParser.parse(DjvuBzz.decode(c.data, c.offset, c.length, 16 shl 20), info.height)
                    "TXTa" -> return DjvuTextParser.parse(c.data.copyOfRange(c.offset, c.offset + c.length), info.height)
                }
            } catch (_: DjvuFormatException) {
            }
        }
        return null
    }

    private fun dictionaryFor(id: String, depth: Int): List<Jb2Shape>? {
        dictCache[id]?.let { return it }
        if (depth > 8) return null
        val ref = filesById[id] ?: return null
        val chunks = readChunks(ref)
        var inherited: List<Jb2Shape>? = null
        for (c in chunks) {
            if (c.id == "INCL") {
                val includeId = String(c.data, c.offset, c.length, StandardCharsets.UTF_8).trim { it <= ' ' }
                dictionaryFor(includeId, depth + 1)?.let { inherited = it }
            } else if (c.id == "Djbz") {
                val shapes = Jb2Decoder.decodeDictionary(c.data, c.offset, c.length, inherited)
                dictCache[id] = shapes
                return shapes
            }
        }
        return null
    }

    fun decodePage(index: Int): DjvuPage {
        val form = pageForms[index]
        val chunks = readChunks(form)
        val info = parseInfo(chunks, form.type)
        var dict: List<Jb2Shape>? = null
        var bg: Iw44Image? = null
        var fg: Iw44Image? = null
        var bgJpeg: DjvuRaster? = null
        var fgJpeg: DjvuRaster? = null
        var mask: Jb2Image? = null
        var palette: IntArray? = null
        var paletteIndices: IntArray? = null
        for (c in chunks) {
            when (c.id) {
                "INCL" -> {
                    val id = String(c.data, c.offset, c.length, StandardCharsets.UTF_8).trim { it <= ' ' }
                    dictionaryFor(id, 0)?.let { dict = it }
                }
                "Djbz" -> dict = Jb2Decoder.decodeDictionary(c.data, c.offset, c.length, dict)
                "Sjbz" -> mask = Jb2Decoder.decodeImage(c.data, c.offset, c.length, dict)
                "BG44", "PM44", "BM44" -> {
                    val img = bg ?: Iw44Image().also { bg = it }
                    try { img.decodeChunk(c.data, c.offset, c.length) } catch (e: DjvuFormatException) { if (img.isEmpty) throw e }
                }
                "FG44" -> {
                    val img = fg ?: Iw44Image().also { fg = it }
                    try { img.decodeChunk(c.data, c.offset, c.length) } catch (e: DjvuFormatException) { if (img.isEmpty) throw e }
                }
                "BGjp" -> if (bgJpeg == null) bgJpeg = jpegDecoder?.invoke(c.data.copyOfRange(c.offset, c.offset + c.length))
                "FGjp" -> if (fgJpeg == null) fgJpeg = jpegDecoder?.invoke(c.data.copyOfRange(c.offset, c.offset + c.length))
                "FGbz" -> {
                    val d = c.data
                    val o = c.offset
                    if (c.length >= 3) {
                        val version = d[o].toInt() and 0xFF
                        val n = ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
                        if (3 + n * 3 <= c.length) {
                            palette = IntArray(n) {
                                val b = d[o + 3 + it * 3].toInt() and 0xFF
                                val g = d[o + 4 + it * 3].toInt() and 0xFF
                                val r = d[o + 5 + it * 3].toInt() and 0xFF
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            val q = o + 3 + n * 3
                            if ((version and 0x80) != 0 && q + 3 < o + c.length) {
                                val count = ((d[q].toInt() and 0xFF) shl 16) or ((d[q + 1].toInt() and 0xFF) shl 8) or (d[q + 2].toInt() and 0xFF)
                                val z = DjvuBzz.decode(d, q + 3, o + c.length - q - 3, 64 shl 20)
                                val avail = minOf(count, z.size / 2)
                                paletteIndices = IntArray(avail) { ((z[it * 2].toInt() and 0xFF) shl 8) or (z[it * 2 + 1].toInt() and 0xFF) }
                            }
                        }
                    }
                }
            }
        }
        val background = bgJpeg ?: bg?.takeIf { !it.isEmpty }?.toRaster()
        val foreground = fgJpeg ?: fg?.takeIf { !it.isEmpty }?.toRaster()
        val m = mask
        var blitColors: IntArray? = null
        val pal = palette
        if (m != null && pal != null && pal.isNotEmpty() && foreground == null) {
            val idx = paletteIndices
            blitColors = IntArray(m.blitCount) { i ->
                val k = if (idx != null && i < idx.size) idx[i] else 0
                pal.getOrElse(k) { pal[0] }
            }
        }
        return DjvuPage(info, background, foreground, m, blitColors, parseText(chunks, info))
    }

    companion object {
        private const val MAX_FORM_BYTES = 256 shl 20

        private fun reduction(full: Int, fullH: Int, w: Int, h: Int): Int {
            for (red in 1..12) {
                if ((full + red - 1) / red == w && (fullH + red - 1) / red == h) return red
            }
            return maxOf(1, Math.round(full.toFloat() / w.coerceAtLeast(1)))
        }

        private fun scaledCoord(x: Int, red: Int, inMax: Int): Int {
            val beg = (16 + red) / (2 * red) - 8
            val y = beg + (red / 2 + 16 * x) / red
            return minOf(y, (inMax - 1) * 16)
        }

        private fun lerp(lo: Int, hi: Int, frac: Int): Int = lo + (((hi - lo) * frac + 8) shr 4)

        private fun sampleBackground(r: DjvuRaster, red: Int, pageHeight: Int, x: Int, yTopDown: Int): Int {
            if (red == 1) {
                val cx = x.coerceIn(0, r.width - 1)
                val cy = yTopDown.coerceIn(0, r.height - 1)
                return r.argb[cy * r.width + cx]
            }
            val yb = pageHeight - 1 - yTopDown
            val fy = scaledCoord(yb, red, r.height)
            val fx = scaledCoord(x, red, r.width)
            val rowLo = (fy shr 4).coerceIn(0, r.height - 1)
            val rowHi = ((fy shr 4) + 1).coerceIn(0, r.height - 1)
            val colLo = (fx shr 4).coerceIn(0, r.width - 1)
            val colHi = ((fx shr 4) + 1).coerceIn(0, r.width - 1)
            val tLo = (r.height - 1 - rowLo) * r.width
            val tHi = (r.height - 1 - rowHi) * r.width
            val vf = fy and 15
            val hf = fx and 15
            var out = 0xFF shl 24
            for (shift in SHIFTS) {
                val left = lerp((r.argb[tLo + colLo] shr shift) and 0xFF, (r.argb[tHi + colLo] shr shift) and 0xFF, vf)
                val right = lerp((r.argb[tLo + colHi] shr shift) and 0xFF, (r.argb[tHi + colHi] shr shift) and 0xFF, vf)
                out = out or (lerp(left, right, hf).coerceIn(0, 255) shl shift)
            }
            return out
        }

        private fun sampleForeground(r: DjvuRaster, red: Int, pageHeight: Int, x: Int, yTopDown: Int): Int {
            val yb = pageHeight - 1 - yTopDown
            val fx = (x / red).coerceIn(0, r.width - 1)
            val fyb = (yb / red).coerceIn(0, r.height - 1)
            return r.argb[(r.height - 1 - fyb) * r.width + fx]
        }

        private val SHIFTS = intArrayOf(16, 8, 0)

        fun compose(page: DjvuPage, x0: Int, y0: Int, x1: Int, y1: Int, factor: Int): DjvuRaster {
            val w = page.info.width
            val h = page.info.height
            val r = factor.coerceAtLeast(1)
            val outW = ((x1 - x0) + r - 1) / r
            val outH = ((y1 - y0) + r - 1) / r
            if (outW <= 0 || outH <= 0) return DjvuRaster(0, 0, IntArray(0))
            val out = IntArray(outW * outH)
            val bg = page.background
            val bgRed = if (bg != null) reduction(w, h, bg.width, bg.height) else 1
            for (oy in 0 until outH) {
                val py = minOf(y0 + oy * r + (r - 1) / 2, h - 1)
                for (ox in 0 until outW) {
                    val px = minOf(x0 + ox * r + (r - 1) / 2, w - 1)
                    out[oy * outW + ox] = if (bg != null) sampleBackground(bg, bgRed, h, px, py) else -1
                }
            }
            val mask = page.mask ?: return DjvuRaster(outW, outH, out)
            val coverage = IntArray(outW * outH)
            val colors = page.blitColors
            val fgIndex = if (colors != null) IntArray(outW * outH) else null
            for (b in 0 until mask.blitCount) {
                val shape = mask.blitShapes[b]
                val left = mask.blitLeft[b]
                val bottom = mask.blitBottom[b]
                val topRow = h - bottom - shape.height
                if (left >= x1 || left + shape.width <= x0 || topRow >= y1 || h - bottom <= y0) continue
                val sw = shape.width
                val bits = shape.bits
                for (row in 0 until shape.height) {
                    val ty = h - 1 - (bottom + row)
                    if (ty < y0 || ty >= y1) continue
                    val oyBase = ((ty - y0) / r) * outW
                    val rowBase = row * sw
                    val cs = maxOf(0, x0 - left)
                    val ce = minOf(sw, x1 - left)
                    for (col in cs until ce) {
                        if (bits[rowBase + col].toInt() == 0) continue
                        val idx = oyBase + (left + col - x0) / r
                        coverage[idx]++
                        if (fgIndex != null) fgIndex[idx] = b
                    }
                }
            }
            val full = r * r
            val fg = page.foreground
            val fgRed = if (fg != null) reduction(w, h, fg.width, fg.height) else 1
            for (oy in 0 until outH) {
                for (ox in 0 until outW) {
                    val i = oy * outW + ox
                    val cov = coverage[i]
                    if (cov == 0) continue
                    val fgColor = when {
                        colors != null && fgIndex != null -> colors[fgIndex[i]]
                        fg != null -> sampleForeground(fg, fgRed, h, minOf(x0 + ox * r + (r - 1) / 2, w - 1), minOf(y0 + oy * r + (r - 1) / 2, h - 1))
                        else -> 0xFF000000.toInt()
                    }
                    if (cov >= full) {
                        out[i] = fgColor
                    } else {
                        val a = cov * 256 / full
                        val bgc = out[i]
                        val rr = (((fgColor shr 16) and 0xFF) * a + ((bgc shr 16) and 0xFF) * (256 - a)) shr 8
                        val gg = (((fgColor shr 8) and 0xFF) * a + ((bgc shr 8) and 0xFF) * (256 - a)) shr 8
                        val bb = ((fgColor and 0xFF) * a + (bgc and 0xFF) * (256 - a)) shr 8
                        out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
            return DjvuRaster(outW, outH, out)
        }
    }
}
