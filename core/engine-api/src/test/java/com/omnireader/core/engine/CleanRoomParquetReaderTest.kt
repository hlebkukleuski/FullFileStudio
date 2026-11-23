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

import com.omnireader.core.engine.impl.CleanRoomParquetReader
import com.omnireader.core.engine.impl.ParquetByteSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanRoomParquetReaderTest {

    private val dir = File(javaClass.classLoader!!.getResource("parquet/expected.json")!!.toURI()).parentFile!!

    private fun open(name: String): CleanRoomParquetReader {
        val bytes = File(dir, "$name.parquet").readBytes()
        return CleanRoomParquetReader(ParquetByteSource { offset, length ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }, bytes.size.toLong())
    }

    private fun expected(file: String): Pair<List<String>, List<List<String>>> {
        val json = JSONObject(File(dir, file).readText())
        val cols = json.getJSONArray("columns").let { a -> List(a.length()) { a.getString(it) } }
        val rows = json.getJSONArray("rows").let { a ->
            List(a.length()) { r -> a.getJSONArray(r).let { row -> List(row.length()) { row.getString(it) } } }
        }
        return cols to rows
    }

    private fun check(name: String, expectedFile: String = "expected.json") {
        val (cols, rows) = expected(expectedFile)
        val reader = open(name)
        assertEquals(name, cols, reader.leaves.map { it.displayName })
        assertEquals(name, rows.size.toLong(), reader.numRows)
        for (i in rows.indices.reversed()) {
            assertEquals("$name row $i", rows[i], reader.row(i.toLong()))
        }
    }

    @Test fun uncompressed() = check("codec_none")
    @Test fun snappy() = check("codec_snappy")
    @Test fun gzip() = check("codec_gzip")
    @Test fun zstd() = check("codec_zstd")
    @Test fun brotli() = check("codec_brotli")
    @Test fun lz4() = check("codec_lz4")
    @Test fun dataPageV2() = check("v2_pages")
    @Test fun dataPageV2Uncompressed() = check("v2_uncompressed")
    @Test fun plainWithoutDictionary() = check("no_dictionary")
    @Test fun manySmallPagesAndRowGroups() = check("small_pages")
    @Test fun byteStreamSplit() = check("byte_stream_split")
    @Test fun deltaEncodings() = check("delta_encodings")
    @Test fun legacyInt96Timestamps() = check("int96", "expected_int96.json")
}
