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
 * DuckDB block storage and catalog format structures informed by DuckDB
 * (https://github.com/duckdb/duckdb, Copyright 2018-2026 Stichting DuckDB Foundation, MIT License).
 */

package com.omnireader.core.engine.impl

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object CleanRoomDuckDbReader {
    data class ParsedTable(val name: String, val columns: List<String>, val rows: List<List<String>>)
    data class ParsedDuckDb(
        val storageVersion: Long,
        val libraryVersion: String,
        val iteration: Long,
        val blockCount: Long,
        val blockAllocSize: Long,
        val vectorSize: Long,
        val tables: List<ParsedTable>
    )

    private const val FILE_HEADER_SIZE = 4096
    private const val ALL_HEADERS_SIZE = 3 * FILE_HEADER_SIZE
    private const val META_BLOCK_SIZE = 4096

    fun isDuckDbHeader(bytes: ByteArray): Boolean =
        bytes.size >= 16 &&
            bytes[8] == 'D'.code.toByte() && bytes[9] == 'U'.code.toByte() &&
            bytes[10] == 'C'.code.toByte() && bytes[11] == 'K'.code.toByte()

    fun isEncrypted(bytes: ByteArray): Boolean =
        isDuckDbHeader(bytes) && bytes.size >= 28 && (bytes[20].toInt() and 1) != 0

    private class DbHeader(
        val iteration: Long,
        val metaBlock: Long,
        val freeList: Long,
        val blockCount: Long,
        val blockAllocSize: Long,
        val vectorSize: Long,
        val serializationCompat: Long
    )

    private class ColumnSpec(val name: String, val typeId: Int, val typeName: String)
    private class SegmentSpec(val blockId: Long, val offset: Long, val count: Long, val compression: Int)
    private class TableMeta(
        val schema: String,
        val name: String,
        val columns: ArrayList<ColumnSpec> = ArrayList(),
        val totalRows: Long = 0L,
        val colSegments: HashMap<Int, ArrayList<SegmentSpec>> = HashMap()
    )

    fun parse(bytes: ByteArray): ParsedDuckDb? {
        if (!isDuckDbHeader(bytes) || bytes.size < ALL_HEADERS_SIZE) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val storageVersion = bb.getLong(12)
        val gitDesc = readCString(bytes, 52, 32)
        val libVer = readCString(bytes, 84, 32).ifEmpty { gitDesc }

        val h1 = readDbHeader(bb, FILE_HEADER_SIZE)
        val h2 = readDbHeader(bb, FILE_HEADER_SIZE * 2)
        val active = if (h2.iteration > h1.iteration) h2 else h1
        val blockAllocSize = if (active.blockAllocSize in 4096L..(64L * 1024L * 1024L)) active.blockAllocSize else 262144L
        val vectorSize = if (active.vectorSize in 64L..65536L) active.vectorSize else 2048L

        val metaStream = if (active.metaBlock >= 0L) readMetadataChain(bytes, active.metaBlock, blockAllocSize) else ByteArray(0)
        val parsedTables = ArrayList<ParsedTable>()
        val catalogRows = ArrayList<List<String>>()
        val discoveredTables = ArrayList<TableMeta>()

        if (metaStream.isNotEmpty()) {
            parseCatalogStream(metaStream, discoveredTables, catalogRows)
            for (tm in discoveredTables) {
                if (tm.columns.isEmpty()) continue
                val rows = decodeTableRows(bytes, tm, blockAllocSize)
                parsedTables.add(ParsedTable("${tm.schema}.${tm.name}", tm.columns.map { it.name }, rows))
            }
        }

        if (catalogRows.isNotEmpty()) {
            parsedTables.add(
                ParsedTable(
                    name = "_DuckDB_Catalog (${catalogRows.size})",
                    columns = listOf("Schema", "Object", "Kind", "Columns / Details"),
                    rows = catalogRows
                )
            )
        }

        val headerRows = listOf(
            listOf("Format", "DuckDB Single-File Storage"),
            listOf("Storage Version", storageVersion.toString()),
            listOf("Library Version", libVer.ifEmpty { "v$storageVersion" }),
            listOf("Checkpoint Iteration", active.iteration.toString()),
            listOf("Active Meta Pointer", "0x${active.metaBlock.toString(16)}"),
            listOf("Block Count", active.blockCount.toString()),
            listOf("Block Alloc Size", "$blockAllocSize bytes"),
            listOf("Vector Size", vectorSize.toString()),
            listOf("Serialization Compat", active.serializationCompat.toString()),
            listOf("Metadata Stream Size", "${metaStream.size} bytes"),
            listOf("Catalog Objects", catalogRows.size.toString())
        )
        parsedTables.add(ParsedTable("_DuckDB_Header", listOf("Property", "Value"), headerRows))
        return ParsedDuckDb(storageVersion, libVer, active.iteration, active.blockCount, blockAllocSize, vectorSize, parsedTables)
    }

    private fun readDbHeader(bb: ByteBuffer, base: Int): DbHeader = DbHeader(
        iteration = bb.getLong(base + 8),
        metaBlock = bb.getLong(base + 16),
        freeList = bb.getLong(base + 24),
        blockCount = bb.getLong(base + 32),
        blockAllocSize = bb.getLong(base + 40),
        vectorSize = bb.getLong(base + 48),
        serializationCompat = bb.getLong(base + 56)
    )

    private fun readMetadataChain(bytes: ByteArray, startPtr: Long, blockAllocSize: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val visited = HashSet<Long>()
        var ptr = startPtr
        var guard = 0
        while (ptr >= 0L && guard++ < 512 && visited.add(ptr)) {
            val blockId = ptr and 0x00FFFFFFFFFFFFFFL
            val subIdx = ((ptr ushr 56) and 0xFFL).toInt()
            val offL = ALL_HEADERS_SIZE.toLong() + blockId * blockAllocSize + subIdx.toLong() * META_BLOCK_SIZE.toLong()
            if (offL < ALL_HEADERS_SIZE.toLong() || offL + META_BLOCK_SIZE > bytes.size.toLong()) break
            val off = offL.toInt()
            val nextPtr = ByteBuffer.wrap(bytes, off, 8).order(ByteOrder.LITTLE_ENDIAN).long
            out.write(bytes, off + 8, META_BLOCK_SIZE - 8)
            if (out.size() > 8 * 1024 * 1024) break
            ptr = nextPtr
        }
        return out.toByteArray()
    }

    private class BinReader(val data: ByteArray, var pos: Int = 0) {
        val remaining get() = data.size - pos
        fun u8(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else 0
        fun u16(): Int {
            if (pos + 2 > data.size) { pos = data.size; return 0xFFFF }
            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }
        fun varintU64(): Long {
            var res = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                res = res or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
            return res
        }
        fun readString(): String? {
            val len = varintU64()
            if (len < 0L || len > 65536L || pos + len.toInt() > data.size) return null
            val n = len.toInt()
            val s = String(data, pos, n, StandardCharsets.UTF_8)
            pos += n
            return s
        }
    }

    private fun parseCatalogStream(meta: ByteArray, tablesOut: ArrayList<TableMeta>, catalogRows: ArrayList<List<String>>) {
        val r = BinReader(meta, 0)
        val seenTables = HashSet<String>()
        while (r.remaining >= 6 && tablesOut.size < 256) {
            val saved = r.pos
            val f0 = r.u16()
            if (f0 == 100) {
                val typeTag = r.u8()
                if (typeTag in 1..12) {
                    val parsed = tryReadCatalogEntry(meta, saved)
                    if (parsed != null) {
                        val (tm, nextPos, kindName, detail) = parsed
                        if (tm != null && seenTables.add("${tm.schema}.${tm.name}")) {
                            tablesOut.add(tm)
                        }
                        if (catalogRows.size < 512) {
                            catalogRows.add(listOf(tm?.schema ?: "main", tm?.name ?: detail.take(40), kindName, detail))
                        }
                        if (nextPos > saved) {
                            r.pos = nextPos
                            continue
                        }
                    }
                }
            }
            r.pos = saved + 1
        }
    }

    private data class CatalogHit(val table: TableMeta?, val nextPos: Int, val kind: String, val detail: String)

    private fun tryReadCatalogEntry(meta: ByteArray, startPos: Int): CatalogHit? {
        val r = BinReader(meta, startPos)
        if (r.u16() != 100) return null
        val catalogType = r.u8()
        var schema = "main"
        var name = ""
        val cols = ArrayList<ColumnSpec>()
        var totalRows = 0L
        val segs = HashMap<Int, ArrayList<SegmentSpec>>()
        var fieldGuard = 0
        while (r.remaining >= 2 && fieldGuard++ < 128) {
            val fid = r.u16()
            if (fid == 0xFFFF) break
            when (fid) {
                101 -> {
                    val s = r.readString() ?: return null
                    if (s.isEmpty() || !isIdent(s)) return null
                    schema = s
                }
                102 -> {
                    val s = r.readString() ?: return null
                    if (s.isEmpty() || !isIdent(s)) return null
                    name = s
                }
                200 -> {
                    if (catalogType == 1) {
                        val colCnt = r.varintU64().toInt()
                        if (colCnt !in 1..512) return null
                        for (c in 0 until colCnt) {
                            val col = tryReadColumnDef(r) ?: return null
                            cols.add(col)
                        }
                    } else return null
                }
                201 -> {
                    totalRows = r.varintU64()
                }
                202 -> {
                    val segListCnt = r.varintU64().toInt()
                    if (segListCnt in 0..512) {
                        for (ci in 0 until segListCnt) {
                            val sc = r.varintU64().toInt()
                            if (sc !in 0..256) return null
                            val list = ArrayList<SegmentSpec>(sc)
                            for (si in 0 until sc) {
                                val blk = r.varintU64()
                                val off = r.varintU64()
                                val cnt = r.varintU64()
                                val comp = r.u8()
                                list.add(SegmentSpec(blk, off, cnt, comp))
                            }
                            segs[ci] = list
                        }
                    } else return null
                }
                else -> return null
            }
        }
        if (name.isEmpty()) return null
        val kind = when (catalogType) {
            1 -> "TABLE"
            2 -> "SCHEMA"
            3 -> "VIEW"
            4 -> "INDEX"
            5 -> "SEQUENCE"
            6 -> "MACRO"
            else -> "OBJECT($catalogType)"
        }
        val detail = if (cols.isNotEmpty()) cols.joinToString(", ") { "${it.name}: ${it.typeName}" } else kind
        val tm = if (catalogType == 1 && cols.isNotEmpty()) TableMeta(schema, name, cols, totalRows, segs) else null
        return CatalogHit(tm, r.pos, kind, detail)
    }

    private fun tryReadColumnDef(r: BinReader): ColumnSpec? {
        var colName = ""
        var typeId = 0
        var guard = 0
        while (r.remaining >= 2 && guard++ < 16) {
            val fid = r.u16()
            if (fid == 0xFFFF) break
            when (fid) {
                100 -> {
                    colName = r.readString() ?: return null
                    if (colName.isEmpty() || !isIdent(colName)) return null
                }
                101 -> typeId = r.u8()
                else -> return null
            }
        }
        if (colName.isEmpty()) return null
        return ColumnSpec(colName, typeId, logicalTypeName(typeId))
    }

    private fun decodeTableRows(bytes: ByteArray, tm: TableMeta, blockAllocSize: Long): List<List<String>> {
        if (tm.colSegments.isEmpty()) return emptyList()
        val colValues = Array(tm.columns.size) { ArrayList<String>() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        for ((ci, col) in tm.columns.withIndex()) {
            val segs = tm.colSegments[ci] ?: continue
            for (seg in segs) {
                if (colValues[ci].size >= 10_000) break
                val baseOff = ALL_HEADERS_SIZE.toLong() + seg.blockId * blockAllocSize + seg.offset
                if (baseOff < ALL_HEADERS_SIZE.toLong() || baseOff >= bytes.size.toLong()) continue
                val count = minOf(seg.count, (10_000 - colValues[ci].size).toLong()).toInt()
                if (count <= 0) continue
                var p = baseOff.toInt()
                if (seg.compression == 1) {
                    val v = readSingleValue(bytes, bb, p, col.typeId, dateFmt, tsFmt)?.first ?: ""
                    repeat(count) { colValues[ci].add(v) }
                } else {
                    for (k in 0 until count) {
                        val res = readSingleValue(bytes, bb, p, col.typeId, dateFmt, tsFmt) ?: break
                        colValues[ci].add(res.first)
                        p += res.second
                    }
                }
            }
        }
        val rowCount = colValues.maxOfOrNull { it.size } ?: 0
        val out = ArrayList<List<String>>(rowCount)
        for (r in 0 until rowCount) {
            out.add(List(tm.columns.size) { ci -> colValues[ci].getOrElse(r) { "" } })
        }
        return out
    }

    private fun readSingleValue(bytes: ByteArray, bb: ByteBuffer, p: Int, typeId: Int, dateFmt: SimpleDateFormat, tsFmt: SimpleDateFormat): Pair<String, Int>? {
        return when (typeId) {
            10 -> if (p + 1 <= bytes.size) (if (bytes[p].toInt() != 0) "true" else "false") to 1 else null
            11 -> if (p + 1 <= bytes.size) bytes[p].toString() to 1 else null
            12 -> if (p + 2 <= bytes.size) bb.getShort(p).toString() to 2 else null
            13 -> if (p + 4 <= bytes.size) bb.getInt(p).toString() to 4 else null
            14 -> if (p + 8 <= bytes.size) bb.getLong(p).toString() to 8 else null
            15 -> if (p + 4 <= bytes.size) dateFmt.format(Date(bb.getInt(p).toLong() * 86_400_000L)) to 4 else null
            17 -> if (p + 8 <= bytes.size) tsFmt.format(Date(bb.getLong(p) / 1000L)) to 8 else null
            22 -> if (p + 4 <= bytes.size) bb.getFloat(p).toString() to 4 else null
            23 -> if (p + 8 <= bytes.size) bb.getDouble(p).toString() to 8 else null
            25 -> {
                if (p + 4 > bytes.size) return null
                val len = bb.getInt(p)
                if (len < 0 || len > 65536 || p + 4 + len > bytes.size) return null
                String(bytes, p + 4, len, StandardCharsets.UTF_8) to (4 + len)
            }
            27 -> if (p + 1 <= bytes.size) (bytes[p].toInt() and 0xFF).toString() to 1 else null
            28 -> if (p + 2 <= bytes.size) (bb.getShort(p).toInt() and 0xFFFF).toString() to 2 else null
            29 -> if (p + 4 <= bytes.size) (bb.getInt(p).toLong() and 0xFFFFFFFFL).toString() to 4 else null
            30 -> if (p + 8 <= bytes.size) java.lang.Long.toUnsignedString(bb.getLong(p)) to 8 else null
            else -> null
        }
    }

    private fun logicalTypeName(id: Int): String = when (id) {
        10 -> "BOOLEAN"; 11 -> "TINYINT"; 12 -> "SMALLINT"; 13 -> "INTEGER"; 14 -> "BIGINT"
        15 -> "DATE"; 16 -> "TIME"; 17 -> "TIMESTAMP"; 18 -> "TIMESTAMP_S"; 19 -> "TIMESTAMP_MS"; 20 -> "TIMESTAMP_NS"
        21 -> "DECIMAL"; 22 -> "FLOAT"; 23 -> "DOUBLE"; 25 -> "VARCHAR"; 26 -> "BLOB"
        27 -> "UTINYINT"; 28 -> "USMALLINT"; 29 -> "UINTEGER"; 30 -> "UBIGINT"
        50 -> "HUGEINT"; 54 -> "UUID"
        else -> "TYPE($id)"
    }

    private fun isIdent(s: String): Boolean = s.length in 1..128 && s.all { it.code in 32..126 }

    private fun readCString(b: ByteArray, off: Int, maxLen: Int): String {
        if (off >= b.size) return ""
        val end = minOf(b.size, off + maxLen)
        var n = 0
        while (off + n < end && b[off + n].toInt() != 0) n++
        return String(b, off, n, StandardCharsets.US_ASCII).trim()
    }
}
