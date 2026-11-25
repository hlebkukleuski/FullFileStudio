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

class DecodedPixels(val width: Int, val height: Int, val argb: IntArray)

object TextureDecoders {

    fun decode(b: ByteArray): DecodedPixels? = try {
        when {
            b.size > 128 && b[0] == 'D'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == 'S'.code.toByte() && b[3] == ' '.code.toByte() -> dds(b)
            looksLikeTga(b) -> tga(b)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun looksLikeTga(b: ByteArray): Boolean {
        if (b.size < 18) return false
        val cmapType = b[1].toInt() and 0xFF
        val type = b[2].toInt() and 0xFF
        val w = u16(b, 12)
        val h = u16(b, 14)
        val bpp = b[16].toInt() and 0xFF
        return cmapType <= 1 && type in setOf(1, 2, 3, 9, 10, 11) && w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16L * 1024 * 1024 && bpp in setOf(8, 15, 16, 24, 32)
    }

    private fun tga(b: ByteArray): DecodedPixels? {
        val idLen = b[0].toInt() and 0xFF
        val cmapType = b[1].toInt() and 0xFF
        val type = b[2].toInt() and 0xFF
        val cmapStart = u16(b, 3)
        val cmapLen = u16(b, 5)
        val cmapBits = b[7].toInt() and 0xFF
        val w = u16(b, 12)
        val h = u16(b, 14)
        if (w <= 0 || h <= 0 || w.toLong() * h > 16L * 1024 * 1024) return null
        val bpp = b[16].toInt() and 0xFF
        val desc = b[17].toInt() and 0xFF
        var p = 18 + idLen
        if (p > b.size) return null
        val palette = IntArray(if (cmapType == 1) cmapStart + cmapLen else 0)
        if (cmapType == 1) {
            val eb = (cmapBits + 7) / 8
            if (eb <= 0 || p + cmapLen.toLong() * eb > b.size) return null
            for (i in 0 until cmapLen) {
                palette[cmapStart + i] = pixel(b, p, cmapBits)
                p += eb
            }
        }
        val pixelBytes = (bpp + 7) / 8
        val n = w * h
        val raw = IntArray(n)
        fun read(at: Int): Int = when (type and 7) {
            1 -> palette.getOrElse(if (pixelBytes == 1) b[at].toInt() and 0xFF else u16(b, at)) { 0 }
            3 -> (b[at].toInt() and 0xFF).let { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }
            else -> pixel(b, at, bpp)
        }
        if (type >= 9) {
            var i = 0
            while (i < n && p < b.size) {
                val hdr = b[p++].toInt() and 0xFF
                val count = (hdr and 0x7F) + 1
                if (hdr and 0x80 != 0) {
                    if (p + pixelBytes > b.size) break
                    val v = read(p)
                    p += pixelBytes
                    for (k in 0 until count) if (i < n) raw[i++] = v
                } else {
                    for (k in 0 until count) {
                        if (p + pixelBytes > b.size) break
                        if (i < n) raw[i++] = read(p)
                        p += pixelBytes
                    }
                }
            }
        } else {
            for (i in 0 until n) {
                if (p + pixelBytes > b.size) break
                raw[i] = read(p)
                p += pixelBytes
            }
        }
        val alphaBits = desc and 0x0F
        val topDown = desc and 0x20 != 0
        val rightToLeft = desc and 0x10 != 0
        val out = IntArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val sy = if (topDown) y else h - 1 - y
            val sx = if (rightToLeft) w - 1 - x else x
            var v = raw[sy * w + sx]
            if (bpp == 32 && alphaBits == 0) v = v or (0xFF shl 24)
            out[y * w + x] = v
        }
        return DecodedPixels(w, h, out)
    }

    private fun pixel(b: ByteArray, at: Int, bits: Int): Int = when (bits) {
        15, 16 -> {
            val v = u16(b, at)
            val r = ((v shr 10) and 31) * 255 / 31
            val g = ((v shr 5) and 31) * 255 / 31
            val bl = (v and 31) * 255 / 31
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        24 -> (0xFF shl 24) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)
        32 -> ((b[at + 3].toInt() and 0xFF) shl 24) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)
        8 -> (b[at].toInt() and 0xFF).let { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }
        else -> 0
    }

    private fun dds(b: ByteArray): DecodedPixels? {
        val h = u32(b, 12)
        val w = u32(b, 16)
        if (w !in 1..8192 || h !in 1..8192 || w.toLong() * h > 16L * 1024 * 1024) return null
        val pfFlags = u32(b, 80)
        val fourCc = String(b, 84, 4, Charsets.US_ASCII)
        var p = 128
        if (fourCc == "DX10") p += 20
        val out = IntArray(w * h)
        if (pfFlags and 0x4 != 0 && fourCc in setOf("DXT1", "DXT2", "DXT3", "DXT4", "DXT5")) {
            val blockSize = if (fourCc == "DXT1") 8 else 16
            val bw = (w + 3) / 4
            val bh = (h + 3) / 4
            val px = IntArray(16)
            for (by in 0 until bh) for (bx in 0 until bw) {
                val at = p + (by * bw + bx) * blockSize
                if (at + blockSize > b.size) return DecodedPixels(w, h, out)
                val colorAt = if (blockSize == 16) at + 8 else at
                colorBlock(b, colorAt, px, fourCc == "DXT1")
                when (fourCc) {
                    "DXT2", "DXT3" -> for (i in 0 until 16) {
                        val a = ((b[at + i / 2].toInt() and 0xFF) shr ((i % 2) * 4)) and 0xF
                        px[i] = (px[i] and 0xFFFFFF) or ((a * 17) shl 24)
                    }
                    "DXT4", "DXT5" -> {
                        val a0 = b[at].toInt() and 0xFF
                        val a1 = b[at + 1].toInt() and 0xFF
                        var bits = 0L
                        for (k in 0 until 6) bits = bits or ((b[at + 2 + k].toLong() and 0xFF) shl (8 * k))
                        for (i in 0 until 16) {
                            val code = ((bits shr (3 * i)) and 7).toInt()
                            val a = when {
                                code == 0 -> a0
                                code == 1 -> a1
                                a0 > a1 -> ((8 - code) * a0 + (code - 1) * a1) / 7
                                code == 6 -> 0
                                code == 7 -> 255
                                else -> ((6 - code) * a0 + (code - 1) * a1) / 5
                            }
                            px[i] = (px[i] and 0xFFFFFF) or (a shl 24)
                        }
                    }
                }
                for (i in 0 until 16) {
                    val x = bx * 4 + i % 4
                    val y = by * 4 + i / 4
                    if (x < w && y < h) out[y * w + x] = px[i]
                }
            }
            return DecodedPixels(w, h, out)
        }
        if (pfFlags and 0x40 != 0) {
            val bits = u32(b, 88)
            val rm = u32(b, 92)
            val gm = u32(b, 96)
            val bm = u32(b, 100)
            val am = if (pfFlags and 0x1 != 0) u32(b, 104) else 0
            val bytes = bits / 8
            fun ch(v: Int, m: Int): Int {
                if (m == 0) return 0
                val shift = Integer.numberOfTrailingZeros(m)
                val max = m ushr shift
                return ((v and m) ushr shift) * 255 / max
            }
            for (i in 0 until w * h) {
                val at = p + i * bytes
                if (at + bytes > b.size) break
                var v = 0
                for (k in 0 until bytes) v = v or ((b[at + k].toInt() and 0xFF) shl (8 * k))
                val a = if (am != 0) ch(v, am) else 255
                out[i] = (a shl 24) or (ch(v, rm) shl 16) or (ch(v, gm) shl 8) or ch(v, bm)
            }
            return DecodedPixels(w, h, out)
        }
        return null
    }

    private fun colorBlock(b: ByteArray, at: Int, px: IntArray, dxt1: Boolean) {
        val c0 = u16(b, at)
        val c1 = u16(b, at + 2)
        fun rgb(c: Int) = intArrayOf(((c shr 11) and 31) * 255 / 31, ((c shr 5) and 63) * 255 / 63, (c and 31) * 255 / 31)
        val a = rgb(c0)
        val bb = rgb(c1)
        val palette = IntArray(4)
        fun pack(r: Int, g: Int, bl: Int, al: Int = 255) = (al shl 24) or (r shl 16) or (g shl 8) or bl
        palette[0] = pack(a[0], a[1], a[2])
        palette[1] = pack(bb[0], bb[1], bb[2])
        if (c0 > c1 || !dxt1) {
            palette[2] = pack((2 * a[0] + bb[0]) / 3, (2 * a[1] + bb[1]) / 3, (2 * a[2] + bb[2]) / 3)
            palette[3] = pack((a[0] + 2 * bb[0]) / 3, (a[1] + 2 * bb[1]) / 3, (a[2] + 2 * bb[2]) / 3)
        } else {
            palette[2] = pack((a[0] + bb[0]) / 2, (a[1] + bb[1]) / 2, (a[2] + bb[2]) / 2)
            palette[3] = 0
        }
        val idx = u32(b, at + 4)
        for (i in 0 until 16) px[i] = palette[(idx ushr (2 * i)) and 3]
    }
}

object NormalMapHeuristic {
    fun looksLikeNormalMap(bytes: ByteArray): Boolean {
        val px: IntArray
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return false
                px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                bmp.recycle()
            } else {
                px = TextureDecoders.decode(bytes)?.argb ?: return false
            }
        } catch (_: Throwable) {
            return false
        }
        if (px.isEmpty()) return false
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var unitLike = 0
        for (c in px) {
            val x = ((c shr 16) and 0xFF) / 127.5 - 1.0
            val y = ((c shr 8) and 0xFF) / 127.5 - 1.0
            val z = (c and 0xFF) / 127.5 - 1.0
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            val len = x * x + y * y + z * z
            if (z > 0.0 && len in 0.6..1.4) unitLike++
        }
        val n = px.size.toDouble()
        r /= n * 255.0
        g /= n * 255.0
        b /= n * 255.0
        return b > 0.7 && kotlin.math.abs(r - 0.5) < 0.15 && kotlin.math.abs(g - 0.5) < 0.15 && unitLike > px.size * 0.8
    }
}
