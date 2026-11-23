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
 * glTF EXT_meshopt_compression decoder adapted from meshoptimizer
 * (https://github.com/zeux/meshoptimizer),
 * Copyright (c) 2016-2026 Arseny Kapoulkine, MIT License.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

internal object CleanRoomMeshopt {

    fun decode(src: ByteArray, count: Int, stride: Int, mode: String, filter: String): ByteArray {
        val out = ByteArray(count * stride)
        when (mode.uppercase()) {
            "ATTRIBUTES" -> {
                decodeVertexBuffer(out, count, stride, src)
                when (filter.uppercase()) {
                    "OCTAHEDRAL" -> filterOct(out, count, stride)
                    "QUATERNION" -> filterQuat(out, count, stride)
                    "EXPONENTIAL" -> filterExp(out, count, stride)
                }
            }
            "TRIANGLES" -> decodeIndexBuffer(out, count, stride, src)
            "INDICES" -> decodeIndexSequence(out, count, stride, src)
            else -> throw IllegalArgumentException("mode")
        }
        return out
    }

    private class Reader(val data: ByteArray, var pos: Int) {
        fun u8(): Int = data[pos++].toInt() and 0xFF
    }

    fun decodeVertexBuffer(dst: ByteArray, count: Int, stride: Int, src: ByteArray) {
        require(stride in 1..256 && stride % 4 == 0)
        require(src.size >= 1 + stride)
        val header = src[0].toInt() and 0xFF
        require(header and 0xF0 == 0xA0 && header and 0x0F == 0)
        val last = ByteArray(stride)
        System.arraycopy(src, src.size - stride, last, 0, stride)
        var blockSize = (8192 / stride) and 15.inv()
        if (blockSize > 256) blockSize = 256
        val r = Reader(src, 1)
        val buffer = ByteArray(256)
        var offset = 0
        val tail = maxOf(32, stride)
        while (offset < count) {
            val n = minOf(blockSize, count - offset)
            val aligned = (n + 15) and 15.inv()
            for (k in 0 until stride) {
                decodeBytes(r, buffer, aligned, src.size - tail)
                var p = last[k].toInt()
                for (i in 0 until n) {
                    val v = buffer[i].toInt() and 0xFF
                    p += (v ushr 1) xor -(v and 1)
                    dst[(offset + i) * stride + k] = p.toByte()
                }
                last[k] = p.toByte()
            }
            offset += n
        }
        require(src.size - r.pos == tail)
    }

    private fun decodeBytes(r: Reader, buffer: ByteArray, size: Int, limit: Int) {
        val headerSize = ((size / 16) + 3) / 4
        val headerPos = r.pos
        require(headerPos + headerSize <= limit)
        r.pos += headerSize
        var i = 0
        while (i < size) {
            val g = i / 16
            val bits = ((r.data[headerPos + g / 4].toInt() and 0xFF) ushr ((g % 4) * 2)) and 3
            decodeGroup(r, buffer, i, bits, limit)
            i += 16
        }
    }

    private fun decodeGroup(r: Reader, buffer: ByteArray, at: Int, bitsLog2: Int, limit: Int) {
        when (bitsLog2) {
            0 -> java.util.Arrays.fill(buffer, at, at + 16, 0)
            3 -> {
                require(r.pos + 16 <= limit)
                System.arraycopy(r.data, r.pos, buffer, at, 16)
                r.pos += 16
            }
            else -> {
                val bits = if (bitsLog2 == 1) 2 else 4
                val packed = bits * 2
                require(r.pos + packed <= limit)
                var ex = r.pos + packed
                val full = (1 shl bits) - 1
                var o = at
                for (b in 0 until packed) {
                    var byte = r.data[r.pos + b].toInt() and 0xFF
                    for (k in 0 until 8 / bits) {
                        val enc = byte ushr (8 - bits)
                        byte = (byte shl bits) and 0xFF
                        if (enc == full) {
                            require(ex < limit)
                            buffer[o++] = r.data[ex++]
                        } else {
                            buffer[o++] = enc.toByte()
                        }
                    }
                }
                r.pos = ex
            }
        }
    }

    private fun vbyte(r: Reader): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = r.u8()
            result = result or ((b and 0x7F) shl shift)
            if (b < 0x80) break
            shift += 7
        }
        return result
    }

    private fun writeIndex(dst: ByteArray, i: Int, v: Int, stride: Int) {
        if (stride == 2) {
            dst[i * 2] = v.toByte()
            dst[i * 2 + 1] = (v ushr 8).toByte()
        } else {
            dst[i * 4] = v.toByte()
            dst[i * 4 + 1] = (v ushr 8).toByte()
            dst[i * 4 + 2] = (v ushr 16).toByte()
            dst[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    fun decodeIndexBuffer(dst: ByteArray, count: Int, stride: Int, src: ByteArray) {
        require(count % 3 == 0 && (stride == 2 || stride == 4))
        require(src.size >= 1 + count / 3 + 16)
        val header = src[0].toInt() and 0xFF
        require(header and 0xF0 == 0xE0)
        val version = header and 0x0F
        require(version <= 1)
        val edgeA = IntArray(16) { -1 }
        val edgeB = IntArray(16) { -1 }
        val vfifo = IntArray(16) { -1 }
        var eo = 0
        var vo = 0
        var next = 0
        var last = 0
        val fecmax = if (version >= 1) 13 else 15
        var code = 1
        val r = Reader(src, 1 + count / 3)
        val aux = src.size - 16
        fun pushEdge(a: Int, b: Int) {
            edgeA[eo] = a
            edgeB[eo] = b
            eo = (eo + 1) and 15
        }
        fun pushVertex(v: Int, cond: Boolean = true) {
            vfifo[vo] = v
            if (cond) vo = (vo + 1) and 15
        }
        fun index(): Int {
            val v = vbyte(r)
            last += (v ushr 1) xor -(v and 1)
            return last
        }
        var i = 0
        while (i < count) {
            val codetri = src[code++].toInt() and 0xFF
            if (codetri < 0xF0) {
                val fe = codetri ushr 4
                val slot = (eo - 1 - fe) and 15
                val a = edgeA[slot]
                val b = edgeB[slot]
                val fec = codetri and 15
                if (fec < fecmax) {
                    val c = if (fec == 0) next else vfifo[(vo - 1 - fec) and 15]
                    if (fec == 0) next++
                    writeIndex(dst, i, a, stride); writeIndex(dst, i + 1, b, stride); writeIndex(dst, i + 2, c, stride)
                    if (fec == 0) pushVertex(c)
                    pushEdge(c, b)
                    pushEdge(a, c)
                } else {
                    val c = if (fec != 15) {
                        last += fec - (fec xor 3)
                        last
                    } else index()
                    writeIndex(dst, i, a, stride); writeIndex(dst, i + 1, b, stride); writeIndex(dst, i + 2, c, stride)
                    pushVertex(c)
                    pushEdge(c, b)
                    pushEdge(a, c)
                }
            } else if (codetri < 0xFE) {
                val codeaux = src[aux + (codetri and 15)].toInt() and 0xFF
                val feb = codeaux ushr 4
                val fec = codeaux and 15
                val a = next++
                val b = if (feb == 0) next else vfifo[(vo - feb) and 15]
                if (feb == 0) next++
                val c = if (fec == 0) next else vfifo[(vo - fec) and 15]
                if (fec == 0) next++
                writeIndex(dst, i, a, stride); writeIndex(dst, i + 1, b, stride); writeIndex(dst, i + 2, c, stride)
                pushVertex(a)
                pushVertex(b, feb == 0)
                pushVertex(c, fec == 0)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
            } else {
                val codeaux = r.u8()
                val fea = if (codetri == 0xFE) 0 else 15
                val feb = codeaux ushr 4
                val fec = codeaux and 15
                var a = if (fea == 0) next++ else 0
                var b = if (feb == 0) next++ else vfifo[(vo - feb) and 15]
                var c = if (fec == 0) next++ else vfifo[(vo - fec) and 15]
                if (fea == 15) a = index()
                if (feb == 15) b = index()
                if (fec == 15) c = index()
                writeIndex(dst, i, a, stride); writeIndex(dst, i + 1, b, stride); writeIndex(dst, i + 2, c, stride)
                pushVertex(a)
                pushVertex(b, feb == 0 || feb == 15)
                pushVertex(c, fec == 0 || fec == 15)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
            }
            i += 3
        }
        require(r.pos <= aux)
    }

    fun decodeIndexSequence(dst: ByteArray, count: Int, stride: Int, src: ByteArray) {
        require(stride == 2 || stride == 4)
        require(src.size >= 1 + count + 4)
        require((src[0].toInt() and 0xF0) == 0xD0 && (src[0].toInt() and 0x0F) == 0)
        val r = Reader(src, 1)
        val last = IntArray(2)
        for (i in 0 until count) {
            var v = vbyte(r)
            val current = v and 1
            v = v ushr 1
            val d = (v ushr 1) xor -(v and 1)
            val idx = last[current] + d
            last[current] = idx
            writeIndex(dst, i, idx, stride)
        }
        require(r.pos <= src.size - 4)
    }

    private fun filterOct(data: ByteArray, count: Int, stride: Int) {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val wide = stride == 8
        require(stride == 4 || wide)
        val max = if (wide) 32767f else 127f
        for (i in 0 until count) {
            val b = i * stride
            fun get(k: Int): Float = if (wide) bb.getShort(b + k * 2).toFloat() else data[b + k].toFloat()
            fun put(k: Int, v: Int) = if (wide) { bb.putShort(b + k * 2, v.toShort()); Unit } else data[b + k] = v.toByte()
            var x = get(0)
            var y = get(1)
            val z = get(2) - abs(x) - abs(y)
            val t = if (z >= 0f) 0f else z
            x += if (x >= 0f) t else -t
            y += if (y >= 0f) t else -t
            val l = sqrt(x * x + y * y + z * z)
            val s = if (l > 0f) max / l else 0f
            put(0, (x * s + if (x >= 0f) 0.5f else -0.5f).toInt())
            put(1, (y * s + if (y >= 0f) 0.5f else -0.5f).toInt())
            put(2, (z * s + if (z >= 0f) 0.5f else -0.5f).toInt())
        }
    }

    private fun filterQuat(data: ByteArray, count: Int, stride: Int) {
        require(stride == 8)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val scale = 1f / sqrt(2f)
        for (i in 0 until count) {
            val b = i * 8
            val c3 = bb.getShort(b + 6).toInt()
            val sf = c3 or 3
            val ss = scale / sf.toFloat()
            val x = bb.getShort(b).toFloat() * ss
            val y = bb.getShort(b + 2).toFloat() * ss
            val z = bb.getShort(b + 4).toFloat() * ss
            val ww = 1f - x * x - y * y - z * z
            val w = sqrt(if (ww >= 0f) ww else 0f)
            fun q(v: Float) = (v * 32767f + if (v >= 0f) 0.5f else -0.5f).toInt().toShort()
            val qc = c3 and 3
            bb.putShort(b + ((qc + 1) and 3) * 2, q(x))
            bb.putShort(b + ((qc + 2) and 3) * 2, q(y))
            bb.putShort(b + ((qc + 3) and 3) * 2, q(z))
            bb.putShort(b + (qc and 3) * 2, q(w))
        }
    }

    private fun filterExp(data: ByteArray, count: Int, stride: Int) {
        require(stride % 4 == 0)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count * stride / 4) {
            val v = bb.getInt(i * 4)
            val e = v shr 24
            val m = (v shl 8) shr 8
            bb.putFloat(i * 4, Math.scalb(m.toFloat(), e))
        }
    }
}
