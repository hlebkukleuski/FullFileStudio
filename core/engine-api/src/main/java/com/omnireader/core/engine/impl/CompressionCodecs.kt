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
 * Implements Snappy block decompression (Google Snappy format specification,
 * Copyright 2011 Google Inc., BSD-3-Clause), LZ4 raw block decompression
 * (Yann Collet LZ4 Block Format Description, Copyright (c) 2011-2020 Yann Collet, BSD-2-Clause),
 * Zstandard frame/FSE/Huff0 decompression (IETF RFC 8878 / Meta Zstandard,
 * Copyright (c) Meta Platforms, Inc. and affiliates, BSD-3-Clause), and
 * ITU-T Rec. T.4 / T.6 CCITT Group 3 & Group 4 facsimile decoding.
 */

package com.omnireader.core.engine.impl

import java.io.ByteArrayOutputStream

class CorruptedDataException(message: String) : IllegalArgumentException(message)

object SnappyRawDecoder {

    fun decompress(src: ByteArray, offset: Int = 0, length: Int = src.size - offset, maxOutput: Int = Int.MAX_VALUE): ByteArray {
        var p = offset
        val end = offset + length
        var expected = 0L
        var shift = 0
        while (true) {
            if (p >= end) throw CorruptedDataException("Truncated snappy header")
            val b = src[p++].toInt() and 0xFF
            expected = expected or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw CorruptedDataException("Bad snappy length")
        }
        if (expected > maxOutput) throw CorruptedDataException("Snappy output too large")
        val out = ByteArray(expected.toInt())
        var o = 0
        while (p < end) {
            val tag = src[p++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    var len = (tag ushr 2) + 1
                    if (len > 60) {
                        val extra = len - 60
                        if (p + extra > end) throw CorruptedDataException("Truncated snappy literal")
                        var v = 0
                        for (i in 0 until extra) v = v or ((src[p + i].toInt() and 0xFF) shl (8 * i))
                        p += extra
                        len = v + 1
                    }
                    if (len < 0 || p + len > end || o + len > out.size) throw CorruptedDataException("Bad snappy literal")
                    System.arraycopy(src, p, out, o, len)
                    p += len
                    o += len
                }
                else -> {
                    val len: Int
                    val dist: Int
                    when (tag and 3) {
                        1 -> {
                            if (p >= end) throw CorruptedDataException("Truncated snappy copy")
                            len = ((tag ushr 2) and 7) + 4
                            dist = ((tag ushr 5) shl 8) or (src[p++].toInt() and 0xFF)
                        }
                        2 -> {
                            if (p + 2 > end) throw CorruptedDataException("Truncated snappy copy")
                            len = (tag ushr 2) + 1
                            dist = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                            p += 2
                        }
                        else -> {
                            if (p + 4 > end) throw CorruptedDataException("Truncated snappy copy")
                            len = (tag ushr 2) + 1
                            dist = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                                    ((src[p + 2].toInt() and 0xFF) shl 16) or ((src[p + 3].toInt() and 0xFF) shl 24)
                            p += 4
                        }
                    }
                    if (dist <= 0 || dist > o || o + len > out.size) throw CorruptedDataException("Bad snappy copy")
                    var from = o - dist
                    repeat(len) { out[o++] = out[from++] }
                }
            }
        }
        if (o != out.size) throw CorruptedDataException("Snappy size mismatch")
        return out
    }
}

object Lz4RawDecoder {

    fun decompress(src: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        var p = offset
        val end = offset + length
        var o = 0
        while (p < end) {
            val token = src[p++].toInt() and 0xFF
            var litLen = token ushr 4
            if (litLen == 15) {
                while (true) {
                    if (p >= end) throw CorruptedDataException("Truncated LZ4 literal length")
                    val b = src[p++].toInt() and 0xFF
                    litLen += b
                    if (b != 255) break
                }
            }
            if (p + litLen > end || o + litLen > out.size) throw CorruptedDataException("Bad LZ4 literal run")
            System.arraycopy(src, p, out, o, litLen)
            p += litLen
            o += litLen
            if (p >= end) break
            if (p + 2 > end) throw CorruptedDataException("Truncated LZ4 offset")
            val dist = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            var matchLen = (token and 15) + 4
            if ((token and 15) == 15) {
                while (true) {
                    if (p >= end) throw CorruptedDataException("Truncated LZ4 match length")
                    val b = src[p++].toInt() and 0xFF
                    matchLen += b
                    if (b != 255) break
                }
            }
            if (dist <= 0 || dist > o || o + matchLen > out.size) throw CorruptedDataException("Bad LZ4 match")
            var from = o - dist
            repeat(matchLen) { out[o++] = out[from++] }
        }
        if (o != out.size) throw CorruptedDataException("LZ4 size mismatch")
        return out
    }
}

object ZstdDecoder {

    private const val MAGIC = 0xFD2FB528.toInt()

    private val LL_DEFAULT = shortArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1)
    private val ML_DEFAULT = shortArrayOf(
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1
    )
    private val OF_DEFAULT = shortArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1)

    private val LL_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512,
        1024, 2048, 4096, 8192, 16384, 32768, 65536
    )
    private val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val ML_BASE = IntArray(53) { if (it < 32) it + 3 else 0 }.also {
        val tail = intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
        for (i in tail.indices) it[32 + i] = tail[i]
    }
    private val ML_BITS = IntArray(53).also {
        val tail = intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        for (i in tail.indices) it[32 + i] = tail[i]
    }

    private class FseTable(val accuracyLog: Int, val symbols: IntArray, val nbBits: IntArray, val newStateBase: IntArray)

    private class ReverseBits(private val data: ByteArray, private val start: Int, length: Int) {
        var position: Long

        init {
            if (length <= 0) throw CorruptedDataException("Empty bitstream")
            val last = data[start + length - 1].toInt() and 0xFF
            if (last == 0) throw CorruptedDataException("Bad bitstream padding")
            val highBit = 31 - Integer.numberOfLeadingZeros(last)
            position = (length - 1).toLong() * 8 + highBit
        }

        fun read(n: Int): Int {
            if (n == 0) return 0
            position -= n
            return peekAt(position, n)
        }

        fun peek(n: Int): Int = if (n == 0) 0 else peekAt(position - n, n)

        fun skip(n: Int) {
            position -= n
        }

        private fun peekAt(pos: Long, n: Int): Int {
            var value = 0L
            var bitIndex = pos
            for (i in 0 until n) {
                if (bitIndex >= 0) {
                    val byte = data[start + (bitIndex ushr 3).toInt()].toInt()
                    if ((byte ushr (bitIndex and 7).toInt()) and 1 != 0) value = value or (1L shl i)
                }
                bitIndex++
            }
            return value.toInt()
        }

        val overflowed: Boolean get() = position < 0
        val finished: Boolean get() = position == 0L
    }

    private class ForwardBits(private val data: ByteArray, private val start: Int, private val end: Int) {
        var bitPos = 0L

        fun peek(n: Int): Int {
            var value = 0L
            for (i in 0 until n) {
                val idx = bitPos + i
                val bytePos = start + (idx ushr 3).toInt()
                if (bytePos < end && (data[bytePos].toInt() ushr (idx and 7).toInt()) and 1 != 0) value = value or (1L shl i)
            }
            return value.toInt()
        }

        fun skip(n: Int) {
            bitPos += n
        }

        val bytesConsumed: Int get() = ((bitPos + 7) ushr 3).toInt()
    }

    private fun highBit(v: Int): Int = 31 - Integer.numberOfLeadingZeros(v)

    private fun buildFse(normalized: ShortArray, maxSymbol: Int, accuracyLog: Int): FseTable {
        val tableSize = 1 shl accuracyLog
        val symbols = IntArray(tableSize)
        val nbBits = IntArray(tableSize)
        val base = IntArray(tableSize)
        val next = IntArray(maxSymbol + 1)
        var high = tableSize - 1
        for (s in 0..maxSymbol) {
            if (normalized[s].toInt() == -1) {
                symbols[high--] = s
                next[s] = 1
            } else {
                next[s] = normalized[s].toInt()
            }
        }
        val step = (tableSize ushr 1) + (tableSize ushr 3) + 3
        val mask = tableSize - 1
        var pos = 0
        for (s in 0..maxSymbol) {
            val n = normalized[s].toInt()
            if (n <= 0) continue
            for (i in 0 until n) {
                symbols[pos] = s
                do {
                    pos = (pos + step) and mask
                } while (pos > high)
            }
        }
        if (pos != 0) throw CorruptedDataException("Invalid FSE distribution")
        for (u in 0 until tableSize) {
            val s = symbols[u]
            val nextState = next[s]++
            val bits = accuracyLog - highBit(nextState)
            nbBits[u] = bits
            base[u] = (nextState shl bits) - tableSize
        }
        return FseTable(accuracyLog, symbols, nbBits, base)
    }

    private fun rleTable(symbol: Int): FseTable = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

    private class NCount(val normalized: ShortArray, val maxSymbol: Int, val accuracyLog: Int, val bytes: Int)

    private fun readNCount(data: ByteArray, start: Int, end: Int, maxSymbolAllowed: Int, maxLog: Int): NCount {
        val bits = ForwardBits(data, start, end)
        val accuracyLog = bits.peek(4) + 5
        bits.skip(4)
        if (accuracyLog > maxLog) throw CorruptedDataException("FSE accuracy too large")
        val normalized = ShortArray(maxSymbolAllowed + 1)
        var remaining = (1 shl accuracyLog) + 1
        var threshold = 1 shl accuracyLog
        var nbBits = accuracyLog + 1
        var symbol = 0
        var previous0 = false
        while (remaining > 1 && symbol <= maxSymbolAllowed) {
            if (previous0) {
                var n0 = symbol
                while (bits.peek(16) == 0xFFFF) {
                    n0 += 24
                    bits.skip(16)
                }
                while (bits.peek(2) == 3) {
                    n0 += 3
                    bits.skip(2)
                }
                n0 += bits.peek(2)
                bits.skip(2)
                if (n0 > maxSymbolAllowed) throw CorruptedDataException("FSE symbol overflow")
                while (symbol < n0) normalized[symbol++] = 0
                if (symbol > maxSymbolAllowed) break
            }
            val max = (2 * threshold - 1) - remaining
            var count: Int
            val raw = bits.peek(nbBits)
            if ((raw and (threshold - 1)) < max) {
                count = raw and (threshold - 1)
                bits.skip(nbBits - 1)
            } else {
                count = raw and (2 * threshold - 1)
                if (count >= threshold) count -= max
                bits.skip(nbBits)
            }
            count--
            remaining -= if (count < 0) -count else count
            normalized[symbol++] = count.toShort()
            previous0 = count == 0
            while (remaining < threshold) {
                nbBits--
                threshold = threshold ushr 1
            }
        }
        if (remaining != 1) throw CorruptedDataException("Invalid FSE table description")
        return NCount(normalized, symbol - 1, accuracyLog, bits.bytesConsumed)
    }

    private class HuffmanTable(val maxBits: Int, val symbols: IntArray, val lengths: IntArray)

    private class FrameState {
        var huffman: HuffmanTable? = null
        var llTable: FseTable? = null
        var ofTable: FseTable? = null
        var mlTable: FseTable? = null
        val reps = intArrayOf(1, 4, 8)
    }

    fun decompress(src: ByteArray, offset: Int = 0, length: Int = src.size - offset, maxOutput: Int = 512 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream(maxOf(64, minOf(length * 4, maxOutput)))
        var p = offset
        val end = offset + length
        while (p < end) {
            if (p + 4 > end) throw CorruptedDataException("Truncated zstd frame")
            val magic = le32(src, p)
            if ((magic and 0xFFFFFFF0.toInt()) == 0x184D2A50) {
                if (p + 8 > end) throw CorruptedDataException("Truncated skippable frame")
                val size = le32(src, p + 4).toLong() and 0xFFFFFFFFL
                p += 8 + size.toInt()
                continue
            }
            if (magic != MAGIC) throw CorruptedDataException("Not a zstd frame")
            p = decodeFrame(src, p + 4, end, out, maxOutput)
        }
        return out.toByteArray()
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private class Output(val sink: ByteArrayOutputStream, initial: Int, private val maxOutput: Int) {
        var buf = ByteArray(maxOf(initial, 1024))
        var size = 0

        fun ensure(extra: Int) {
            if (size.toLong() + extra > maxOutput) throw CorruptedDataException("zstd output too large")
            if (size + extra > buf.size) {
                var n = buf.size * 2
                while (n < size + extra) n *= 2
                buf = buf.copyOf(n)
            }
        }

        fun put(b: Byte) {
            ensure(1)
            buf[size++] = b
        }

        fun put(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, off, buf, size, len)
            size += len
        }

        fun copyMatch(distance: Int, length: Int) {
            if (distance <= 0 || distance > size) throw CorruptedDataException("zstd offset out of range")
            ensure(length)
            var from = size - distance
            for (i in 0 until length) buf[size++] = buf[from++]
        }
    }

    private fun decodeFrame(src: ByteArray, start: Int, end: Int, sink: ByteArrayOutputStream, maxOutput: Int): Int {
        var p = start
        val fhd = src[p++].toInt() and 0xFF
        val fcsFlag = fhd ushr 6
        val singleSegment = (fhd ushr 5) and 1 == 1
        val checksum = (fhd ushr 2) and 1 == 1
        val didFlag = fhd and 3
        if ((fhd ushr 3) and 1 == 1) throw CorruptedDataException("Reserved zstd bit set")
        if (!singleSegment) p++
        val didSize = intArrayOf(0, 1, 2, 4)[didFlag]
        var dictId = 0L
        for (i in 0 until didSize) dictId = dictId or ((src[p + i].toLong() and 0xFF) shl (8 * i))
        p += didSize
        if (dictId != 0L) throw CorruptedDataException("zstd dictionaries are not supported")
        val fcsSize = when (fcsFlag) {
            0 -> if (singleSegment) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        var contentSize = -1L
        if (fcsSize > 0) {
            var v = 0L
            for (i in 0 until fcsSize) v = v or ((src[p + i].toLong() and 0xFF) shl (8 * i))
            contentSize = if (fcsSize == 2) v + 256 else v
            p += fcsSize
        }
        if (contentSize > maxOutput) throw CorruptedDataException("zstd output too large")
        val output = Output(sink, if (contentSize in 0..(64L * 1024 * 1024)) contentSize.toInt() else 1 shl 16, maxOutput)
        val state = FrameState()
        while (true) {
            if (p + 3 > end) throw CorruptedDataException("Truncated zstd block header")
            val header = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or ((src[p + 2].toInt() and 0xFF) shl 16)
            p += 3
            val last = header and 1 == 1
            val type = (header ushr 1) and 3
            val blockSize = header ushr 3
            when (type) {
                0 -> {
                    if (p + blockSize > end) throw CorruptedDataException("Truncated raw block")
                    output.put(src, p, blockSize)
                    p += blockSize
                }
                1 -> {
                    if (p >= end) throw CorruptedDataException("Truncated RLE block")
                    output.ensure(blockSize)
                    val b = src[p++]
                    repeat(blockSize) { output.put(b) }
                }
                2 -> {
                    if (p + blockSize > end) throw CorruptedDataException("Truncated compressed block")
                    decodeCompressedBlock(src, p, p + blockSize, output, state)
                    p += blockSize
                }
                else -> throw CorruptedDataException("Reserved zstd block type")
            }
            if (last) break
        }
        if (checksum) p += 4
        if (contentSize >= 0 && output.size.toLong() != contentSize) throw CorruptedDataException("zstd content size mismatch")
        sink.write(output.buf, 0, output.size)
        return p
    }

    private fun decodeCompressedBlock(src: ByteArray, start: Int, end: Int, out: Output, state: FrameState) {
        var p = start
        val b0 = src[p].toInt() and 0xFF
        val litType = b0 and 3
        val sizeFormat = (b0 ushr 2) and 3
        val literals: ByteArray
        when (litType) {
            0, 1 -> {
                val regen: Int
                when (sizeFormat) {
                    0, 2 -> { regen = b0 ushr 3; p += 1 }
                    1 -> { regen = (b0 ushr 4) + ((src[p + 1].toInt() and 0xFF) shl 4); p += 2 }
                    else -> { regen = (b0 ushr 4) + ((src[p + 1].toInt() and 0xFF) shl 4) + ((src[p + 2].toInt() and 0xFF) shl 12); p += 3 }
                }
                if (litType == 0) {
                    if (p + regen > end) throw CorruptedDataException("Truncated raw literals")
                    literals = src.copyOfRange(p, p + regen)
                    p += regen
                } else {
                    if (p >= end) throw CorruptedDataException("Truncated RLE literals")
                    literals = ByteArray(regen) { src[p] }
                    p += 1
                }
            }
            else -> {
                val regen: Int
                val compressed: Int
                val streams: Int
                when (sizeFormat) {
                    0, 1 -> {
                        val v = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or ((src[p + 2].toInt() and 0xFF) shl 16)
                        regen = (v ushr 4) and 0x3FF
                        compressed = (v ushr 14) and 0x3FF
                        streams = if (sizeFormat == 0) 1 else 4
                        p += 3
                    }
                    2 -> {
                        val v = le32(src, p)
                        regen = (v ushr 4) and 0x3FFF
                        compressed = (v ushr 18) and 0x3FFF
                        streams = 4
                        p += 4
                    }
                    else -> {
                        val v = (le32(src, p).toLong() and 0xFFFFFFFFL) or ((src[p + 4].toLong() and 0xFF) shl 32)
                        regen = ((v ushr 4) and 0x3FFFF).toInt()
                        compressed = ((v ushr 22) and 0x3FFFF).toInt()
                        streams = 4
                        p += 5
                    }
                }
                if (p + compressed > end) throw CorruptedDataException("Truncated compressed literals")
                var q = p
                if (litType == 2) {
                    val (table, used) = readHuffmanTable(src, q, p + compressed)
                    state.huffman = table
                    q += used
                }
                val table = state.huffman ?: throw CorruptedDataException("Missing Huffman table")
                literals = decodeHuffmanStreams(src, q, p + compressed, regen, streams, table)
                p += compressed
            }
        }
        decodeSequences(src, p, end, literals, out, state)
    }

    private fun readHuffmanTable(src: ByteArray, start: Int, end: Int): Pair<HuffmanTable, Int> {
        val header = src[start].toInt() and 0xFF
        val weights = IntArray(256)
        var count: Int
        var used: Int
        if (header >= 128) {
            count = header - 127
            used = 1 + (count + 1) / 2
            if (start + used > end) throw CorruptedDataException("Truncated Huffman weights")
            for (i in 0 until count) {
                val byte = src[start + 1 + i / 2].toInt() and 0xFF
                weights[i] = if (i % 2 == 0) byte ushr 4 else byte and 15
            }
        } else {
            val size = header
            if (start + 1 + size > end) throw CorruptedDataException("Truncated Huffman FSE weights")
            val nc = readNCount(src, start + 1, start + 1 + size, 255, 6)
            val fse = buildFse(nc.normalized, nc.maxSymbol, nc.accuracyLog)
            val bits = ReverseBits(src, start + 1 + nc.bytes, size - nc.bytes)
            var s1 = bits.read(fse.accuracyLog)
            var s2 = bits.read(fse.accuracyLog)
            count = 0
            while (true) {
                if (count >= 255) throw CorruptedDataException("Too many Huffman weights")
                weights[count++] = fse.symbols[s1]
                s1 = fse.newStateBase[s1] + bits.read(fse.nbBits[s1])
                if (bits.overflowed) {
                    weights[count++] = fse.symbols[s2]
                    break
                }
                if (count >= 255) throw CorruptedDataException("Too many Huffman weights")
                weights[count++] = fse.symbols[s2]
                s2 = fse.newStateBase[s2] + bits.read(fse.nbBits[s2])
                if (bits.overflowed) {
                    weights[count++] = fse.symbols[s1]
                    break
                }
            }
            used = 1 + size
        }
        var sum = 0
        for (i in 0 until count) {
            if (weights[i] > 11) throw CorruptedDataException("Huffman weight too large")
            if (weights[i] > 0) sum += 1 shl (weights[i] - 1)
        }
        if (sum == 0) throw CorruptedDataException("Empty Huffman table")
        val maxBits = highBit(sum) + 1
        val leftover = (1 shl maxBits) - sum
        if (leftover and (leftover - 1) != 0) throw CorruptedDataException("Invalid Huffman weights")
        weights[count] = highBit(leftover) + 1
        val symbolCount = count + 1
        if (maxBits > 11) throw CorruptedDataException("Huffman table too deep")
        val rankCount = IntArray(maxBits + 2)
        for (i in 0 until symbolCount) rankCount[weights[i]]++
        val rankStart = IntArray(maxBits + 2)
        var current = 0
        for (w in 1..maxBits) {
            rankStart[w] = current
            current += rankCount[w] shl (w - 1)
        }
        val tableSize = 1 shl maxBits
        val symbols = IntArray(tableSize)
        val lengths = IntArray(tableSize)
        for (s in 0 until symbolCount) {
            val w = weights[s]
            if (w == 0) continue
            val len = 1 shl (w - 1)
            val bits = maxBits + 1 - w
            for (i in rankStart[w] until rankStart[w] + len) {
                symbols[i] = s
                lengths[i] = bits
            }
            rankStart[w] += len
        }
        return HuffmanTable(maxBits, symbols, lengths) to used
    }

    private fun decodeHuffmanStream(src: ByteArray, start: Int, length: Int, count: Int, table: HuffmanTable, out: ByteArray, outPos: Int) {
        if (count == 0) return
        val bits = ReverseBits(src, start, length)
        for (i in 0 until count) {
            val idx = bits.peek(table.maxBits)
            out[outPos + i] = table.symbols[idx].toByte()
            bits.skip(table.lengths[idx])
        }
        if (bits.position != 0L) throw CorruptedDataException("Huffman stream not fully consumed")
    }

    private fun decodeHuffmanStreams(src: ByteArray, start: Int, end: Int, regen: Int, streams: Int, table: HuffmanTable): ByteArray {
        val out = ByteArray(regen)
        if (streams == 1) {
            decodeHuffmanStream(src, start, end - start, regen, table, out, 0)
            return out
        }
        if (start + 6 > end) throw CorruptedDataException("Truncated jump table")
        val s1 = (src[start].toInt() and 0xFF) or ((src[start + 1].toInt() and 0xFF) shl 8)
        val s2 = (src[start + 2].toInt() and 0xFF) or ((src[start + 3].toInt() and 0xFF) shl 8)
        val s3 = (src[start + 4].toInt() and 0xFF) or ((src[start + 5].toInt() and 0xFF) shl 8)
        val s4 = end - start - 6 - s1 - s2 - s3
        if (s4 < 1) throw CorruptedDataException("Invalid jump table")
        val seg = (regen + 3) / 4
        var p = start + 6
        val sizes = intArrayOf(s1, s2, s3, s4)
        for (i in 0 until 4) {
            val count = if (i < 3) seg else regen - 3 * seg
            if (count < 0) throw CorruptedDataException("Invalid literal split")
            decodeHuffmanStream(src, p, sizes[i], count, table, out, i * seg)
            p += sizes[i]
        }
        return out
    }

    private fun readSequenceTable(
        src: ByteArray, p: Int, end: Int, mode: Int, default: ShortArray, defaultLog: Int, maxSymbol: Int, maxLog: Int, previous: FseTable?
    ): Pair<FseTable, Int> = when (mode) {
        0 -> buildFse(default, default.size - 1, defaultLog) to 0
        1 -> {
            if (p >= end) throw CorruptedDataException("Truncated RLE table")
            val s = src[p].toInt() and 0xFF
            if (s > maxSymbol) throw CorruptedDataException("RLE symbol out of range")
            rleTable(s) to 1
        }
        2 -> {
            val nc = readNCount(src, p, end, maxSymbol, maxLog)
            buildFse(nc.normalized, nc.maxSymbol, nc.accuracyLog) to nc.bytes
        }
        else -> (previous ?: throw CorruptedDataException("Missing repeat table")) to 0
    }

    private fun decodeSequences(src: ByteArray, start: Int, end: Int, literals: ByteArray, out: Output, state: FrameState) {
        var p = start
        if (p >= end) {
            out.put(literals, 0, literals.size)
            return
        }
        val b0 = src[p].toInt() and 0xFF
        val nbSeq: Int
        when {
            b0 == 0 -> {
                out.put(literals, 0, literals.size)
                return
            }
            b0 < 128 -> { nbSeq = b0; p += 1 }
            b0 < 255 -> { nbSeq = ((b0 - 128) shl 8) + (src[p + 1].toInt() and 0xFF); p += 2 }
            else -> { nbSeq = (src[p + 1].toInt() and 0xFF) + ((src[p + 2].toInt() and 0xFF) shl 8) + 0x7F00; p += 3 }
        }
        val modes = src[p++].toInt() and 0xFF
        if (modes and 3 != 0) throw CorruptedDataException("Reserved sequence mode bits")
        val (ll, llUsed) = readSequenceTable(src, p, end, modes ushr 6, LL_DEFAULT, 6, 35, 9, state.llTable)
        p += llUsed
        val (of, ofUsed) = readSequenceTable(src, p, end, (modes ushr 4) and 3, OF_DEFAULT, 5, 31, 8, state.ofTable)
        p += ofUsed
        val (ml, mlUsed) = readSequenceTable(src, p, end, (modes ushr 2) and 3, ML_DEFAULT, 6, 52, 9, state.mlTable)
        p += mlUsed
        state.llTable = ll
        state.ofTable = of
        state.mlTable = ml

        val bits = ReverseBits(src, p, end - p)
        var llState = bits.read(ll.accuracyLog)
        var ofState = bits.read(of.accuracyLog)
        var mlState = bits.read(ml.accuracyLog)
        var litPos = 0
        val reps = state.reps
        for (seq in 0 until nbSeq) {
            val ofCode = of.symbols[ofState]
            val mlCode = ml.symbols[mlState]
            val llCode = ll.symbols[llState]
            if (ofCode > 31 || mlCode > 52 || llCode > 35) throw CorruptedDataException("Sequence code out of range")
            val offsetValue = (1 shl ofCode) + bits.read(ofCode)
            val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode])
            val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode])
            val offset: Int
            if (offsetValue > 3) {
                offset = offsetValue - 3
                reps[2] = reps[1]
                reps[1] = reps[0]
                reps[0] = offset
            } else {
                var idx = offsetValue - 1
                if (literalLength == 0) idx++
                when (idx) {
                    0 -> offset = reps[0]
                    3 -> {
                        offset = reps[0] - 1
                        reps[2] = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }
                    else -> {
                        offset = reps[idx]
                        if (idx == 2) reps[2] = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }
                }
            }
            if (litPos + literalLength > literals.size) throw CorruptedDataException("Literal length out of range")
            out.put(literals, litPos, literalLength)
            litPos += literalLength
            out.copyMatch(offset, matchLength)
            if (seq != nbSeq - 1) {
                llState = ll.newStateBase[llState] + bits.read(ll.nbBits[llState])
                mlState = ml.newStateBase[mlState] + bits.read(ml.nbBits[mlState])
                ofState = of.newStateBase[ofState] + bits.read(of.nbBits[ofState])
            }
            if (bits.overflowed) throw CorruptedDataException("Sequence bitstream overflow")
        }
        if (litPos < literals.size) out.put(literals, litPos, literals.size - litPos)
    }
}

object CcittFaxDecoder {

    private class Code(val bits: Int, val length: Int, val run: Int)

    private val WHITE_TERM = arrayOf(
        "00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111", "10011", "10100", "00111", "01000",
        "001000", "000011", "110100", "110101", "101010", "101011", "0100111", "0001100", "0001000", "0010111",
        "0000011", "0000100", "0101000", "0101011", "0010011", "0100100", "0011000", "00000010", "00000011",
        "00011010", "00011011", "00010010", "00010011", "00010100", "00010101", "00010110", "00010111", "00101000",
        "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101", "00001010", "00001011",
        "01010010", "01010011", "01010100", "01010101", "00100100", "00100101", "01011000", "01011001", "01011010",
        "01011011", "01001010", "01001011", "00110010", "00110011", "00110100"
    )
    private val WHITE_MAKEUP = arrayOf(
        "11011", "10010", "010111", "0110111", "00110110", "00110111", "01100100", "01100101", "01101000", "01100111",
        "011001100", "011001101", "011010010", "011010011", "011010100", "011010101", "011010110", "011010111",
        "011011000", "011011001", "011011010", "011011011", "010011000", "010011001", "010011010", "011000", "010011011"
    )
    private val BLACK_TERM = arrayOf(
        "0000110111", "010", "11", "10", "011", "0011", "0010", "00011", "000101", "000100", "0000100", "0000101",
        "0000111", "00000100", "00000111", "000011000", "0000010111", "0000011000", "0000001000", "00001100111",
        "00001101000", "00001101100", "00000110111", "00000101000", "00000010111", "00000011000", "000011001010",
        "000011001011", "000011001100", "000011001101", "000001101000", "000001101001", "000001101010", "000001101011",
        "000011010010", "000011010011", "000011010100", "000011010101", "000011010110", "000011010111", "000001101100",
        "000001101101", "000011011010", "000011011011", "000001010100", "000001010101", "000001010110", "000001010111",
        "000001100100", "000001100101", "000001010010", "000001010011", "000000100100", "000000110111", "000000111000",
        "000000100111", "000000101000", "000001011000", "000001011001", "000000101011", "000000101100", "000001011010",
        "000001100110", "000001100111"
    )
    private val BLACK_MAKEUP = arrayOf(
        "0000001111", "000011001000", "000011001001", "000001011011", "000000110011", "000000110100", "000000110101",
        "0000001101100", "0000001101101", "0000001001010", "0000001001011", "0000001001100", "0000001001101",
        "0000001110010", "0000001110011", "0000001110100", "0000001110101", "0000001110110", "0000001110111",
        "0000001010010", "0000001010011", "0000001010100", "0000001010101", "0000001011010", "0000001011011",
        "0000001100100", "0000001100101"
    )
    private val EXTENDED_MAKEUP = arrayOf(
        "00000001000", "00000001100", "00000001101", "000000010010", "000000010011", "000000010100", "000000010101",
        "000000010110", "000000010111", "000000011100", "000000011101", "000000011110", "000000011111"
    )

    private const val MAX_CODE_BITS = 13
    private val whiteTable = buildTable(true)
    private val blackTable = buildTable(false)

    private fun buildTable(white: Boolean): IntArray {
        val table = IntArray(1 shl MAX_CODE_BITS) { -1 }
        fun add(code: String, run: Int) {
            val len = code.length
            val value = code.toInt(2)
            val shift = MAX_CODE_BITS - len
            for (suffix in 0 until (1 shl shift)) {
                val idx = (value shl shift) or suffix
                if (table[idx] != -1) throw IllegalStateException("Overlapping CCITT code $code")
                table[idx] = (run shl 4) or len
            }
        }
        val term = if (white) WHITE_TERM else BLACK_TERM
        val makeup = if (white) WHITE_MAKEUP else BLACK_MAKEUP
        term.forEachIndexed { run, code -> add(code, run) }
        makeup.forEachIndexed { i, code -> add(code, (i + 1) * 64) }
        EXTENDED_MAKEUP.forEachIndexed { i, code -> add(code, 1792 + i * 64) }
        return table
    }

    private class BitReader(private val data: ByteArray, private val start: Int, private val end: Int, private val lsbFirst: Boolean) {
        var pos = 0L
        val totalBits = (end - start).toLong() * 8

        fun bit(at: Long): Int {
            if (at >= totalBits) return 0
            val b = data[start + (at ushr 3).toInt()].toInt()
            val shift = (at and 7).toInt()
            return if (lsbFirst) (b ushr shift) and 1 else (b ushr (7 - shift)) and 1
        }

        fun peek(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or bit(pos + i)
            return v
        }

        fun skip(n: Int) {
            pos += n
        }

        fun read(n: Int): Int = peek(n).also { pos += n }

        fun alignToByte() {
            pos = (pos + 7) and 7L.inv()
        }

        val exhausted: Boolean get() = pos >= totalBits
    }

    private fun readRun(r: BitReader, white: Boolean): Int {
        val table = if (white) whiteTable else blackTable
        var total = 0
        while (true) {
            if (r.exhausted) throw CorruptedDataException("Truncated CCITT run")
            val entry = table[r.peek(MAX_CODE_BITS)]
            if (entry < 0) throw CorruptedDataException("Invalid CCITT ${if (white) "white" else "black"} code")
            r.skip(entry and 15)
            val run = entry ushr 4
            total += run
            if (run < 64) return total
        }
    }

    private fun skipEol(r: BitReader): Boolean {
        var p = r.pos
        while (p < r.totalBits && r.bit(p) == 0) p++
        if (p >= r.totalBits) return false
        val zeros = p - r.pos
        if (zeros >= 11) {
            r.pos = p + 1
            return true
        }
        return false
    }

    private fun setBits(row: ByteArray, rowOffset: Int, from: Int, to: Int) {
        for (x in from until to) {
            val idx = rowOffset + (x ushr 3)
            row[idx] = (row[idx].toInt() or (0x80 ushr (x and 7))).toByte()
        }
    }

    private fun decode1dRow(r: BitReader, width: Int, changes: IntArray): Int {
        var x = 0
        var white = true
        var n = 0
        while (x < width) {
            val run = readRun(r, white)
            x += run
            if (x > width) throw CorruptedDataException("CCITT run exceeds row width")
            changes[n++] = x
            white = !white
        }
        return n
    }

    private fun decode2dRow(r: BitReader, width: Int, ref: IntArray, refCount: Int, changes: IntArray): Int {
        var a0 = -1
        var white = true
        var n = 0
        var refIdx = 0
        fun findB1(): Int {
            while (refIdx > 0 && ref[refIdx - 1] > a0) refIdx--
            var i = refIdx
            while (i < refCount && (ref[i] <= a0 || (i % 2 == 0) != white)) i++
            refIdx = i
            return i
        }
        while (a0 < width) {
            val b1Idx = findB1()
            val b1 = if (b1Idx < refCount) ref[b1Idx] else width
            val b2 = if (b1Idx + 1 < refCount) ref[b1Idx + 1] else width
            when {
                r.peek(1) == 1 -> {
                    r.skip(1)
                    if (b1 < maxOf(a0, 0) && a0 >= 0) throw CorruptedDataException("Bad CCITT vertical code")
                    a0 = b1
                    changes[n++] = a0
                    white = !white
                }
                r.peek(3) == 0b011 || r.peek(3) == 0b010 -> {
                    val right = r.read(3) == 0b011
                    a0 = if (right) b1 + 1 else b1 - 1
                    if (a0 < 0 || a0 > width) throw CorruptedDataException("CCITT vertical change out of range")
                    changes[n++] = a0
                    white = !white
                }
                r.peek(3) == 0b001 -> {
                    r.skip(3)
                    val start = maxOf(a0, 0)
                    val run1 = readRun(r, white)
                    val run2 = readRun(r, !white)
                    val a1 = start + run1
                    val a2 = a1 + run2
                    if (a2 > width) throw CorruptedDataException("CCITT horizontal run exceeds width")
                    changes[n++] = a1
                    changes[n++] = a2
                    a0 = a2
                }
                r.peek(4) == 0b0001 -> {
                    r.skip(4)
                    a0 = b2
                    if (a0 > width) throw CorruptedDataException("CCITT pass beyond width")
                    if (n > 0 && changes[n - 1] == a0) {
                    } else {
                        changes[n++] = a0
                        changes[n++] = a0
                    }
                    if (a0 >= width) break
                }
                r.peek(6) == 0b000011 || r.peek(6) == 0b000010 -> {
                    val right = r.read(6) == 0b000011
                    a0 = if (right) b1 + 2 else b1 - 2
                    if (a0 < 0 || a0 > width) throw CorruptedDataException("CCITT vertical change out of range")
                    changes[n++] = a0
                    white = !white
                }
                r.peek(7) == 0b0000011 || r.peek(7) == 0b0000010 -> {
                    val right = r.read(7) == 0b0000011
                    a0 = if (right) b1 + 3 else b1 - 3
                    if (a0 < 0 || a0 > width) throw CorruptedDataException("CCITT vertical change out of range")
                    changes[n++] = a0
                    white = !white
                }
                else -> throw CorruptedDataException("Unsupported CCITT 2D code")
            }
            if (n > changes.size - 4) throw CorruptedDataException("Too many CCITT changes")
        }
        return normalizeChanges(changes, n)
    }

    private fun normalizeChanges(changes: IntArray, n: Int): Int {
        var out = 0
        var i = 0
        while (i < n) {
            if (i + 1 < n && changes[i] == changes[i + 1]) {
                i += 2
                continue
            }
            changes[out++] = changes[i]
            i++
        }
        return out
    }

    private fun emitRow(output: ByteArray, rowOffset: Int, width: Int, changes: IntArray, count: Int) {
        var x = 0
        var white = true
        for (i in 0 until count) {
            val next = minOf(changes[i], width)
            if (!white && next > x) setBits(output, rowOffset, x, next)
            x = next
            white = !white
        }
        if (!white && x < width) setBits(output, rowOffset, x, width)
    }

    fun decode(
        data: ByteArray, offset: Int, length: Int, width: Int, rows: Int,
        compression: Int, t4Options: Int, lsbFirst: Boolean
    ): ByteArray {
        if (width <= 0 || rows <= 0 || width > 1 shl 20) throw CorruptedDataException("Bad CCITT dimensions")
        val stride = (width + 7) / 8
        val output = ByteArray(stride * rows)
        val r = BitReader(data, offset, offset + length, lsbFirst)
        var ref = IntArray(width + 4)
        var refCount = 0
        var cur = IntArray(width + 4)
        val twoD = compression == 4 || (compression == 3 && t4Options and 1 != 0)
        for (y in 0 until rows) {
            var count: Int
            try {
                when (compression) {
                    2 -> {
                        count = decode1dRow(r, width, cur)
                        r.alignToByte()
                    }
                    3 -> {
                        if (t4Options and 4 != 0) {
                            val save = r.pos
                            var p = save
                            while (p < r.totalBits && r.bit(p) == 0) p++
                            if (p - save >= 11) {
                                val eolEnd = p + 1
                                r.pos = eolEnd
                            }
                        } else {
                            skipEol(r)
                        }
                        val use2d = twoD && r.read(1) == 0
                        count = if (use2d) decode2dRow(r, width, ref, refCount, cur) else decode1dRow(r, width, cur)
                    }
                    4 -> count = decode2dRow(r, width, ref, refCount, cur)
                    else -> throw UnsupportedOperationException("Unsupported CCITT compression $compression")
                }
            } catch (e: CorruptedDataException) {
                if (y == 0) throw e
                break
            }
            emitRow(output, y * stride, width, cur, count)
            val swap = ref
            ref = cur
            cur = swap
            refCount = count
            if (r.exhausted) break
        }
        return output
    }
}
