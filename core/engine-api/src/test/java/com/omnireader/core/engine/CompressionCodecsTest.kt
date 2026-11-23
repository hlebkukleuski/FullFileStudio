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

import com.github.luben.zstd.Zstd
import com.omnireader.core.engine.impl.CorruptedDataException
import com.omnireader.core.engine.impl.Lz4RawDecoder
import com.omnireader.core.engine.impl.SnappyRawDecoder
import com.omnireader.core.engine.impl.ZstdDecoder
import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.xerial.snappy.Snappy
import java.util.Random

class CompressionCodecsTest {

    private fun samples(): List<ByteArray> {
        val random = Random(42)
        val out = ArrayList<ByteArray>()
        out.add(ByteArray(0))
        out.add(byteArrayOf(7))
        out.add(ByteArray(100_000) { (random.nextInt(256)).toByte() })
        out.add(ByteArray(300_000) { (it % 7).toByte() })
        val text = StringBuilder()
        val words = listOf("parquet", "колонка", "значение", "row", "group", "zstd", "сжатие", "ёжик", "\n", "1234567890")
        while (text.length < 600_000) text.append(words[random.nextInt(words.size)]).append(' ')
        out.add(text.toString().toByteArray(Charsets.UTF_8))
        val mixed = ByteArray(2_000_000)
        for (i in mixed.indices) mixed[i] = if ((i / 4096) % 3 == 0) random.nextInt(256).toByte() else ((i * 31) % 97).toByte()
        out.add(mixed)
        val ints = ByteArray(400_000)
        var v = 0
        for (i in 0 until ints.size / 4) {
            v += random.nextInt(5)
            ints[i * 4] = v.toByte(); ints[i * 4 + 1] = (v ushr 8).toByte(); ints[i * 4 + 2] = (v ushr 16).toByte(); ints[i * 4 + 3] = 0
        }
        out.add(ints)
        return out
    }

    @Test
    fun zstdMatchesReferenceAtAllLevels() {
        for (data in samples()) {
            for (level in listOf(-5, 1, 3, 9, 15, 19, 22)) {
                val compressed = Zstd.compress(data, level)
                assertArrayEquals("level $level size ${data.size}", data, ZstdDecoder.decompress(compressed))
            }
        }
    }

    @Test
    fun zstdHandlesConcatenatedFramesAndRejectsGarbage() {
        val a = "first frame ".repeat(1000).toByteArray()
        val b = "second frame ".repeat(1000).toByteArray()
        val joined = Zstd.compress(a, 5) + Zstd.compress(b, 12)
        assertArrayEquals(a + b, ZstdDecoder.decompress(joined))
        val random = Random(1)
        val valid = Zstd.compress(samples()[4], 9)
        repeat(400) {
            val bad = valid.copyOf()
            repeat(1 + random.nextInt(6)) { bad[4 + random.nextInt(bad.size - 4)] = random.nextInt(256).toByte() }
            try {
                ZstdDecoder.decompress(bad, maxOutput = 8 * 1024 * 1024)
            } catch (_: CorruptedDataException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: IndexOutOfBoundsException) {
            }
        }
    }

    @Test
    fun snappyMatchesReference() {
        for (data in samples()) {
            val compressed = Snappy.compress(data)
            assertArrayEquals(data, SnappyRawDecoder.decompress(compressed))
        }
    }

    @Test
    fun lz4BlockMatchesReference() {
        val factory = LZ4Factory.safeInstance()
        for (data in samples()) {
            for (compressor in listOf(factory.fastCompressor(), factory.highCompressor(9))) {
                val compressed = compressor.compress(data)
                assertArrayEquals(data, Lz4RawDecoder.decompress(compressed, 0, compressed.size, data.size))
            }
        }
    }
}
