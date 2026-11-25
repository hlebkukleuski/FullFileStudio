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
 * Implements the Apache Parquet Format (https://github.com/apache/parquet-format,
 * Copyright 2014-2026 The Apache Software Foundation, Apache-2.0) and Apache Thrift Compact Protocol.
 */

package me.hlebkukleuski.fullfilestudio.core.database

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.Inflater
import me.hlebkukleuski.fullfilestudio.core.security.CorruptedDataException
import me.hlebkukleuski.fullfilestudio.core.security.Lz4RawDecoder
import me.hlebkukleuski.fullfilestudio.core.security.SnappyRawDecoder
import me.hlebkukleuski.fullfilestudio.core.security.ZstdDecoder

class ThriftCompactReader(private val data: ByteArray, var pos: Int = 0, private val end: Int = data.size) {

    class Struct(val fields: Map<Int, Any?>) {
        fun int(id: Int): Int? = (fields[id] as? Number)?.toInt()
        fun long(id: Int): Long? = (fields[id] as? Number)?.toLong()
        fun bool(id: Int): Boolean? = fields[id] as? Boolean
        fun string(id: Int): String? = (fields[id] as? ByteArray)?.toString(StandardCharsets.UTF_8)
        fun struct(id: Int): Struct? = fields[id] as? Struct
        @Suppress("UNCHECKED_CAST")
        fun list(id: Int): List<Any?> = fields[id] as? List<Any?> ?: emptyList()
        fun has(id: Int): Boolean = fields.containsKey(id)
    }

    private var depth = 0

    private fun byte(): Int {
        if (pos >= end) throw CorruptedDataException("Truncated Thrift data")
        return data[pos++].toInt() and 0xFF
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw CorruptedDataException("Bad Thrift varint")
        }
    }

    private fun zigzag(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    private fun binary(): ByteArray {
        val len = varint()
        if (len < 0 || pos + len > end) throw CorruptedDataException("Bad Thrift binary length")
        val out = data.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    private fun value(type: Int, inContainer: Boolean = false): Any? = when (type) {
        1 -> if (inContainer) byte() == 1 else true
        2 -> if (inContainer) byte() == 1 else false
        3 -> byte().toByte().toLong()
        4, 5, 6 -> zigzag()
        7 -> {
            if (pos + 8 > end) throw CorruptedDataException("Truncated Thrift double")
            val v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double
            pos += 8
            v
        }
        8 -> binary()
        9, 10 -> {
            val header = byte()
            var size = header ushr 4
            val elemType = header and 15
            if (size == 15) size = varint().toInt()
            if (size < 0 || size > end - pos + 1) throw CorruptedDataException("Bad Thrift list size")
            val list = ArrayList<Any?>(minOf(size, 4096))
            repeat(size) { list.add(value(elemType, true)) }
            list
        }
        11 -> {
            val size = varint().toInt()
            if (size < 0 || size > end - pos) throw CorruptedDataException("Bad Thrift map size")
            val map = LinkedHashMap<Any?, Any?>()
            if (size > 0) {
                val types = byte()
                repeat(size) { map[value(types ushr 4, true)] = value(types and 15, true) }
            }
            map
        }
        12 -> readStruct()
        else -> throw CorruptedDataException("Unknown Thrift type $type")
    }

    fun readStruct(): Struct {
        if (++depth > 64) throw CorruptedDataException("Thrift nesting too deep")
        val fields = HashMap<Int, Any?>()
        var lastId = 0
        while (true) {
            val header = byte()
            if (header == 0) break
            val type = header and 15
            val delta = header ushr 4
            val id = if (delta != 0) lastId + delta else zigzag().toInt()
            fields[id] = value(type)
            lastId = id
        }
        depth--
        return Struct(fields)
    }
}

fun interface ParquetByteSource {
    fun read(offset: Long, length: Int): ByteArray
}

class ParquetReader(private val source: ParquetByteSource, private val fileSize: Long) {

    enum class PhysicalType { BOOLEAN, INT32, INT64, INT96, FLOAT, DOUBLE, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY }

    class SchemaNode(
        val name: String,
        val physicalType: PhysicalType?,
        val typeLength: Int,
        val repetition: Int,
        val numChildren: Int,
        val convertedType: Int?,
        val scale: Int,
        val precision: Int,
        val logicalType: ThriftCompactReader.Struct?
    ) {
        val children = ArrayList<SchemaNode>()
    }

    class LeafColumn(
        val index: Int,
        val path: List<String>,
        val node: SchemaNode,
        val maxDefinition: Int,
        val maxRepetition: Int,
        val firstRepeatedDefinition: Int
    ) {
        val displayName: String get() = path.filter { it != "list" && it != "element" && it != "key_value" && it != "array" && it != "bag" }
            .joinToString(".").ifEmpty { path.joinToString(".") }
    }

    class ColumnChunkMeta(
        val codec: Int,
        val numValues: Long,
        val dataPageOffset: Long,
        val dictionaryPageOffset: Long?,
        val totalCompressedSize: Long
    )

    class RowGroupMeta(val numRows: Long, val firstRow: Long, val columns: List<ColumnChunkMeta>)

    private class PageRef(val offset: Long, val header: ThriftCompactReader.Struct, val headerSize: Int, val firstRow: Long, val rowCount: Long)

    private class ChunkIndex(val dictionary: List<Any?>?, val pages: List<PageRef>)

    val schemaRoot: SchemaNode
    val leaves: List<LeafColumn>
    val numRows: Long
    val rowGroups: List<RowGroupMeta>
    val createdBy: String?
    private val chunkIndexes = HashMap<Long, ChunkIndex>()
    private val pageCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 96
    }

    init {
        require(fileSize >= 12) { "File is too small to be Parquet" }
        val head = source.read(0, 4)
        val tail = source.read(fileSize - 8, 8)
        val headMagic = String(head, StandardCharsets.US_ASCII)
        val tailMagic = String(tail, 4, 4, StandardCharsets.US_ASCII)
        if (headMagic == "PARE" || tailMagic == "PARE") {
            throw UnsupportedOperationException("UNSUPPORTED_ENCRYPTION: Encrypted Parquet files are not supported.")
        }
        require(headMagic == "PAR1") { "Missing PAR1 header" }
        require(tailMagic == "PAR1") { "Missing PAR1 footer" }
        val footerLen = ByteBuffer.wrap(tail, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(footerLen > 0 && footerLen <= fileSize - 12) { "Invalid footer length" }
        val footer = source.read(fileSize - 8 - footerLen, footerLen)
        val meta = ThriftCompactReader(footer).readStruct()
        if (meta.has(8)) {
            throw UnsupportedOperationException("UNSUPPORTED_ENCRYPTION: Encrypted Parquet files are not supported.")
        }
        val schemaElements = meta.list(2).map { it as ThriftCompactReader.Struct }
        require(schemaElements.isNotEmpty()) { "Empty Parquet schema" }
        var cursor = 0
        fun buildNode(): SchemaNode {
            require(cursor < schemaElements.size) { "Corrupted Parquet schema tree" }
            val e = schemaElements[cursor++]
            val typeOrd = e.int(1)
            val node = SchemaNode(
                name = e.string(4) ?: "",
                physicalType = typeOrd?.let { PhysicalType.entries.getOrNull(it) ?: throw IllegalArgumentException("Unknown physical type $it") },
                typeLength = e.int(2) ?: 0,
                repetition = e.int(3) ?: 0,
                numChildren = e.int(5) ?: 0,
                convertedType = e.int(6),
                scale = e.int(7) ?: 0,
                precision = e.int(8) ?: 0,
                logicalType = e.struct(10)
            )
            require(node.numChildren in 0..100_000) { "Corrupted Parquet schema" }
            repeat(node.numChildren) { node.children.add(buildNode()) }
            return node
        }
        schemaRoot = buildNode()
        val collected = ArrayList<LeafColumn>()
        fun collect(node: SchemaNode, path: List<String>, def: Int, rep: Int, firstRepeatedDef: Int) {
            for (child in node.children) {
                val d = def + if (child.repetition != 0) 1 else 0
                val r = rep + if (child.repetition == 2) 1 else 0
                val frd = if (firstRepeatedDef < 0 && child.repetition == 2) d else firstRepeatedDef
                val p = path + child.name
                if (child.children.isEmpty()) collected.add(LeafColumn(collected.size, p, child, d, r, frd)) else collect(child, p, d, r, frd)
            }
        }
        collect(schemaRoot, emptyList(), 0, 0, -1)
        leaves = collected
        numRows = meta.long(3) ?: 0L
        createdBy = meta.string(6)
        var firstRow = 0L
        rowGroups = meta.list(4).map { rgAny ->
            val rg = rgAny as ThriftCompactReader.Struct
            val rows = rg.long(3) ?: 0L
            val columns = rg.list(1).map { ccAny ->
                val cc = ccAny as ThriftCompactReader.Struct
                if (cc.has(8) || cc.has(9)) {
                    throw UnsupportedOperationException("UNSUPPORTED_ENCRYPTION: Encrypted Parquet files are not supported.")
                }
                if (cc.string(1) != null) throw UnsupportedOperationException("Parquet files with external column chunks are not supported")
                val md = cc.struct(3) ?: throw IllegalArgumentException("Missing column metadata")
                ColumnChunkMeta(
                    codec = md.int(4) ?: 0,
                    numValues = md.long(5) ?: 0L,
                    dataPageOffset = md.long(9) ?: 0L,
                    dictionaryPageOffset = md.long(11),
                    totalCompressedSize = md.long(7) ?: 0L
                )
            }
            require(columns.size == leaves.size) { "Row group column count mismatch" }
            RowGroupMeta(rows, firstRow, columns).also { firstRow += rows }
        }
    }

    fun physicalTypeName(leaf: LeafColumn): String {
        val n = leaf.node
        val logical = logicalTypeName(n)
        val base = n.physicalType?.name ?: "GROUP"
        val rep = when (n.repetition) { 0 -> "REQUIRED"; 1 -> "OPTIONAL"; else -> "REPEATED" }
        return listOfNotNull(base + if (n.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY) "(${n.typeLength})" else "", logical, rep).joinToString(" ")
    }

    private fun logicalTypeName(n: SchemaNode): String? {
        val lt = n.logicalType
        if (lt != null) {
            val id = lt.fields.keys.firstOrNull() ?: return null
            return when (id) {
                1 -> "STRING"; 2 -> "MAP"; 3 -> "LIST"; 4 -> "ENUM"
                5 -> "DECIMAL(${lt.struct(5)?.int(2) ?: n.precision},${lt.struct(5)?.int(1) ?: n.scale})"
                6 -> "DATE"; 7 -> "TIME"; 8 -> "TIMESTAMP"; 10 -> "INTEGER"; 11 -> "NULL"; 12 -> "JSON"; 13 -> "BSON"
                14 -> "UUID"; 15 -> "FLOAT16"; else -> null
            }
        }
        return when (n.convertedType) {
            0 -> "UTF8"; 1 -> "MAP"; 2 -> "MAP_KEY_VALUE"; 3 -> "LIST"; 4 -> "ENUM"; 5 -> "DECIMAL(${n.precision},${n.scale})"
            6 -> "DATE"; 7 -> "TIME_MILLIS"; 8 -> "TIME_MICROS"; 9 -> "TIMESTAMP_MILLIS"; 10 -> "TIMESTAMP_MICROS"
            11 -> "UINT_8"; 12 -> "UINT_16"; 13 -> "UINT_32"; 14 -> "UINT_64"; 15 -> "INT_8"; 16 -> "INT_16"; 17 -> "INT_32"
            18 -> "INT_64"; 19 -> "JSON"; 20 -> "BSON"; 21 -> "INTERVAL"; else -> null
        }
    }

    private fun decompress(codec: Int, src: ByteArray, uncompressedSize: Int): ByteArray {
        if (uncompressedSize < 0 || uncompressedSize > 512 * 1024 * 1024) throw CorruptedDataException("Page too large")
        return when (codec) {
            0 -> src
            1 -> SnappyRawDecoder.decompress(src, maxOutput = uncompressedSize)
            2 -> {
                val stream = java.util.zip.GZIPInputStream(src.inputStream())
                val out = ByteArray(uncompressedSize)
                var n = 0
                while (n < out.size) {
                    val r = stream.read(out, n, out.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n != out.size) throw CorruptedDataException("GZIP page size mismatch")
                out
            }
            4 -> {
                val stream = org.brotli.dec.BrotliInputStream(src.inputStream())
                val out = ByteArray(uncompressedSize)
                var n = 0
                while (n < out.size) {
                    val r = stream.read(out, n, out.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n != out.size) throw CorruptedDataException("Brotli page size mismatch")
                out
            }
            5 -> decompressHadoopLz4(src, uncompressedSize)
            6 -> ZstdDecoder.decompress(src, maxOutput = uncompressedSize).also {
                if (it.size != uncompressedSize) throw CorruptedDataException("ZSTD page size mismatch")
            }
            7 -> Lz4RawDecoder.decompress(src, 0, src.size, uncompressedSize)
            3 -> throw UnsupportedOperationException("LZO-compressed Parquet pages are not supported")
            else -> throw UnsupportedOperationException("Unknown Parquet codec $codec")
        }
    }

    private fun decompressHadoopLz4(src: ByteArray, uncompressedSize: Int): ByteArray {
        try {
            val out = ByteArray(uncompressedSize)
            var p = 0
            var o = 0
            while (p + 8 <= src.size && o < uncompressedSize) {
                val blockRaw = ByteBuffer.wrap(src, p, 4).order(ByteOrder.BIG_ENDIAN).int
                val blockComp = ByteBuffer.wrap(src, p + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                p += 8
                val part = Lz4RawDecoder.decompress(src, p, blockComp, blockRaw)
                System.arraycopy(part, 0, out, o, part.size)
                o += part.size
                p += blockComp
            }
            if (o == uncompressedSize && p == src.size) return out
        } catch (_: RuntimeException) {
        }
        return Lz4RawDecoder.decompress(src, 0, src.size, uncompressedSize)
    }

    private class Bytes(val data: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int {
            if (pos >= end) throw CorruptedDataException("Truncated page data")
            return data[pos++].toInt() and 0xFF
        }

        fun le32(): Int {
            if (pos + 4 > end) throw CorruptedDataException("Truncated page data")
            val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
            pos += 4
            return v
        }

        fun uvarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw CorruptedDataException("Bad varint")
            }
        }

        fun zigzag(): Long {
            val v = uvarint()
            return (v ushr 1) xor -(v and 1)
        }
    }

    private fun readRleHybrid(b: Bytes, bitWidth: Int, count: Int, end: Int): IntArray {
        val out = IntArray(count)
        if (bitWidth == 0) return out
        require(bitWidth in 1..32) { "Invalid bit width $bitWidth" }
        var n = 0
        val byteWidth = (bitWidth + 7) / 8
        while (n < count) {
            if (b.pos >= end) throw CorruptedDataException("Truncated RLE data")
            val header = b.uvarint()
            if (header and 1L == 0L) {
                val run = (header ushr 1).toInt()
                if (run < 0) throw CorruptedDataException("Bad RLE run")
                var v = 0
                for (i in 0 until byteWidth) v = v or (b.u8() shl (8 * i))
                val take = minOf(run, count - n)
                java.util.Arrays.fill(out, n, n + take, v)
                n += take
            } else {
                val groups = (header ushr 1).toInt()
                val totalValues = groups * 8
                val totalBytes = groups * bitWidth
                if (b.pos + totalBytes > b.end) throw CorruptedDataException("Truncated bit-packed run")
                var bitPos = b.pos.toLong() * 8
                for (i in 0 until totalValues) {
                    var v = 0
                    for (k in 0 until bitWidth) {
                        val bit = bitPos + k
                        if ((b.data[(bit ushr 3).toInt()].toInt() ushr (bit and 7).toInt()) and 1 != 0) v = v or (1 shl k)
                    }
                    bitPos += bitWidth
                    if (n < count) out[n++] = v
                }
                b.pos += totalBytes
            }
        }
        return out
    }

    private fun readLegacyBitPacked(b: Bytes, bitWidth: Int, count: Int): IntArray {
        val out = IntArray(count)
        val totalBytes = ((count.toLong() * bitWidth + 7) / 8).toInt()
        if (b.pos + totalBytes > b.end) throw CorruptedDataException("Truncated bit-packed levels")
        var bitPos = 0L
        for (i in 0 until count) {
            var v = 0
            for (k in 0 until bitWidth) {
                val bit = bitPos + k
                val byte = b.data[b.pos + (bit ushr 3).toInt()].toInt()
                if ((byte ushr (7 - (bit and 7).toInt())) and 1 != 0) v = v or (1 shl (bitWidth - 1 - k))
            }
            bitPos += bitWidth
            out[i] = v
        }
        b.pos += totalBytes
        return out
    }

    private fun bitWidthOf(maxLevel: Int): Int = if (maxLevel == 0) 0 else 32 - Integer.numberOfLeadingZeros(maxLevel)

    private fun readDeltaBinaryPacked(b: Bytes, count: Int): LongArray {
        val blockSize = b.uvarint().toInt()
        val miniBlocks = b.uvarint().toInt()
        val total = b.uvarint().toInt()
        var last = b.zigzag()
        if (blockSize <= 0 || miniBlocks <= 0 || blockSize % miniBlocks != 0 || total < 0) throw CorruptedDataException("Bad delta header")
        val perMini = blockSize / miniBlocks
        val out = LongArray(total)
        if (total == 0) return out
        out[0] = last
        var n = 1
        while (n < total) {
            val minDelta = b.zigzag()
            val widths = IntArray(miniBlocks) { b.u8() }
            for (m in 0 until miniBlocks) {
                if (n >= total) break
                val w = widths[m]
                if (w > 64) throw CorruptedDataException("Bad delta bit width")
                val bytes = perMini * w / 8
                if (b.pos + bytes > b.end) throw CorruptedDataException("Truncated delta block")
                var bitPos = b.pos.toLong() * 8
                for (i in 0 until perMini) {
                    var v = 0L
                    for (k in 0 until w) {
                        val bit = bitPos + k
                        if ((b.data[(bit ushr 3).toInt()].toInt() ushr (bit and 7).toInt()) and 1 != 0) v = v or (1L shl k)
                    }
                    bitPos += w
                    if (n < total) {
                        last += minDelta + v
                        out[n++] = last
                    }
                }
                b.pos += bytes
            }
        }
        return out.copyOf(maxOf(count, 0).coerceAtMost(total)).let { if (it.size < count) out else it }
    }

    private fun decodePlain(type: PhysicalType, typeLength: Int, b: Bytes, count: Int): List<Any?> {
        val out = ArrayList<Any?>(count)
        when (type) {
            PhysicalType.BOOLEAN -> {
                var bit = 0
                for (i in 0 until count) {
                    val byte = b.data[b.pos + bit / 8].toInt()
                    out.add((byte ushr (bit % 8)) and 1 == 1)
                    bit++
                }
                b.pos += (count + 7) / 8
            }
            PhysicalType.INT32 -> repeat(count) { out.add(b.le32()) }
            PhysicalType.INT64 -> repeat(count) {
                if (b.pos + 8 > b.end) throw CorruptedDataException("Truncated INT64")
                out.add(ByteBuffer.wrap(b.data, b.pos, 8).order(ByteOrder.LITTLE_ENDIAN).long)
                b.pos += 8
            }
            PhysicalType.INT96 -> repeat(count) {
                if (b.pos + 12 > b.end) throw CorruptedDataException("Truncated INT96")
                out.add(b.data.copyOfRange(b.pos, b.pos + 12).also { b.pos += 12 }.let { Int96(it) })
            }
            PhysicalType.FLOAT -> repeat(count) { out.add(java.lang.Float.intBitsToFloat(b.le32())) }
            PhysicalType.DOUBLE -> repeat(count) {
                if (b.pos + 8 > b.end) throw CorruptedDataException("Truncated DOUBLE")
                out.add(ByteBuffer.wrap(b.data, b.pos, 8).order(ByteOrder.LITTLE_ENDIAN).double)
                b.pos += 8
            }
            PhysicalType.BYTE_ARRAY -> repeat(count) {
                val len = b.le32()
                if (len < 0 || b.pos + len > b.end) throw CorruptedDataException("Bad BYTE_ARRAY length")
                out.add(b.data.copyOfRange(b.pos, b.pos + len))
                b.pos += len
            }
            PhysicalType.FIXED_LEN_BYTE_ARRAY -> repeat(count) {
                if (typeLength < 0 || b.pos + typeLength > b.end) throw CorruptedDataException("Bad FIXED_LEN_BYTE_ARRAY")
                out.add(b.data.copyOfRange(b.pos, b.pos + typeLength))
                b.pos += typeLength
            }
        }
        return out
    }

    private class Int96(val bytes: ByteArray)

    private fun decodeValues(leaf: LeafColumn, encoding: Int, b: Bytes, count: Int, dictionary: List<Any?>?): List<Any?> {
        val type = leaf.node.physicalType ?: throw CorruptedDataException("Group column has data")
        return when (encoding) {
            0 -> decodePlain(type, leaf.node.typeLength, b, count)
            2, 8 -> {
                val dict = dictionary ?: throw CorruptedDataException("Dictionary page missing")
                val width = b.u8()
                val idx = readRleHybrid(b, width, count, b.end)
                idx.map { i -> if (i < 0 || i >= dict.size) throw CorruptedDataException("Dictionary index out of range") else dict[i] }
            }
            3 -> {
                require(type == PhysicalType.BOOLEAN) { "RLE values are only supported for BOOLEAN" }
                b.le32()
                readRleHybrid(b, 1, count, b.end).map { it == 1 }
            }
            5 -> readDeltaBinaryPacked(b, count).take(count).map { if (type == PhysicalType.INT32) it.toInt() else it }
            6 -> {
                val lengths = readDeltaBinaryPacked(b, count)
                lengths.take(count).map { len ->
                    if (len < 0 || b.pos + len > b.end) throw CorruptedDataException("Bad DELTA_LENGTH_BYTE_ARRAY")
                    b.data.copyOfRange(b.pos, b.pos + len.toInt()).also { b.pos += len.toInt() }
                }
            }
            7 -> {
                val prefixes = readDeltaBinaryPacked(b, count)
                val suffixLengths = readDeltaBinaryPacked(b, count)
                var previous = ByteArray(0)
                (0 until minOf(count, prefixes.size, suffixLengths.size)).map { i ->
                    val pre = prefixes[i].toInt()
                    val suf = suffixLengths[i].toInt()
                    if (pre < 0 || pre > previous.size || suf < 0 || b.pos + suf > b.end) throw CorruptedDataException("Bad DELTA_BYTE_ARRAY")
                    val v = ByteArray(pre + suf)
                    System.arraycopy(previous, 0, v, 0, pre)
                    System.arraycopy(b.data, b.pos, v, pre, suf)
                    b.pos += suf
                    previous = v
                    v
                }
            }
            9 -> {
                val width = when (type) {
                    PhysicalType.FLOAT, PhysicalType.INT32 -> 4
                    PhysicalType.DOUBLE, PhysicalType.INT64 -> 8
                    PhysicalType.FIXED_LEN_BYTE_ARRAY -> leaf.node.typeLength
                    else -> throw CorruptedDataException("BYTE_STREAM_SPLIT on unsupported type")
                }
                if (b.pos + width * count > b.end) throw CorruptedDataException("Truncated BYTE_STREAM_SPLIT")
                val joined = ByteArray(width * count)
                for (i in 0 until count) for (k in 0 until width) joined[i * width + k] = b.data[b.pos + k * count + i]
                b.pos += width * count
                decodePlain(type, leaf.node.typeLength, Bytes(joined, 0, joined.size), count)
            }
            else -> throw UnsupportedOperationException("Unsupported Parquet encoding $encoding")
        }
    }

    private fun readPageHeader(offset: Long): Pair<ThriftCompactReader.Struct, Int> {
        var window = 4096
        while (true) {
            val len = minOf(window.toLong(), fileSize - offset).toInt()
            if (len <= 0) throw CorruptedDataException("Page header beyond end of file")
            val raw = source.read(offset, len)
            try {
                val reader = ThriftCompactReader(raw)
                val header = reader.readStruct()
                return header to reader.pos
            } catch (e: CorruptedDataException) {
                if (len < window || window >= 1 shl 24) throw e
                window *= 4
            }
        }
    }

    private class Levels(val definition: IntArray, val repetition: IntArray, val valuesStart: Int, val data: ByteArray)

    private fun readPagePayload(chunk: ColumnChunkMeta, leaf: LeafColumn, page: PageRef): Levels {
        val h = page.header
        val compressedSize = h.int(3) ?: throw CorruptedDataException("Missing compressed page size")
        val uncompressedSize = h.int(2) ?: throw CorruptedDataException("Missing page size")
        if (compressedSize < 0) throw CorruptedDataException("Bad page size")
        val raw = source.read(page.offset + page.headerSize, compressedSize)
        val type = h.int(1)
        if (type == 3) {
            val v2 = h.struct(8) ?: throw CorruptedDataException("Missing v2 header")
            val numValues = v2.int(1) ?: 0
            val defLen = v2.int(5) ?: 0
            val repLen = v2.int(6) ?: 0
            if (defLen < 0 || repLen < 0 || defLen + repLen > raw.size) throw CorruptedDataException("Bad v2 level lengths")
            val rep = if (leaf.maxRepetition > 0) readRleHybrid(Bytes(raw, 0, repLen), bitWidthOf(leaf.maxRepetition), numValues, repLen) else IntArray(numValues)
            val def = if (leaf.maxDefinition > 0) readRleHybrid(Bytes(raw, repLen, repLen + defLen), bitWidthOf(leaf.maxDefinition), numValues, repLen + defLen) else IntArray(numValues) { leaf.maxDefinition }
            val compressedValues = v2.bool(7) ?: true
            val valueBytes = raw.copyOfRange(repLen + defLen, raw.size)
            val values = if (compressedValues && chunk.codec != 0) decompress(chunk.codec, valueBytes, uncompressedSize - repLen - defLen) else valueBytes
            return Levels(def, rep, 0, values)
        }
        val v1 = h.struct(5) ?: throw CorruptedDataException("Missing data page header")
        val numValues = v1.int(1) ?: 0
        val data = decompress(chunk.codec, raw, uncompressedSize)
        val b = Bytes(data, 0, data.size)
        fun levels(maxLevel: Int, encoding: Int?): IntArray {
            if (maxLevel == 0) return IntArray(numValues) { 0 }
            val width = bitWidthOf(maxLevel)
            return if (encoding == 4) readLegacyBitPacked(b, width, numValues) else {
                val len = b.le32()
                if (len < 0 || b.pos + len > b.end) throw CorruptedDataException("Bad level length")
                val endPos = b.pos + len
                val r = readRleHybrid(Bytes(b.data, b.pos, endPos), width, numValues, endPos)
                b.pos = endPos
                r
            }
        }
        val rep = levels(leaf.maxRepetition, v1.int(4))
        val def = if (leaf.maxDefinition == 0) IntArray(numValues) { 0 } else levels(leaf.maxDefinition, v1.int(3))
        return Levels(def, rep, b.pos, data)
    }

    private fun chunkIndex(rgIndex: Int, leaf: LeafColumn): ChunkIndex {
        val key = (rgIndex.toLong() shl 32) or leaf.index.toLong()
        synchronized(chunkIndexes) { chunkIndexes[key]?.let { return it } }
        val rg = rowGroups[rgIndex]
        val chunk = rg.columns[leaf.index]
        var offset = chunk.dictionaryPageOffset?.takeIf { it > 0 && it < chunk.dataPageOffset } ?: chunk.dataPageOffset
        val chunkEnd = offset + chunk.totalCompressedSize
        var dictionary: List<Any?>? = null
        val pages = ArrayList<PageRef>()
        var valuesSeen = 0L
        var rowCursor = 0L
        var guard = 0
        while (valuesSeen < chunk.numValues && offset < chunkEnd && guard++ < 10_000_000) {
            val (header, headerSize) = readPageHeader(offset)
            val compressed = header.int(3) ?: throw CorruptedDataException("Missing page size")
            when (header.int(1)) {
                2 -> {
                    val dh = header.struct(7) ?: throw CorruptedDataException("Missing dictionary header")
                    val n = dh.int(1) ?: 0
                    val raw = source.read(offset + headerSize, compressed)
                    val data = decompress(chunk.codec, raw, header.int(2) ?: 0)
                    dictionary = decodePlain(leaf.node.physicalType ?: throw CorruptedDataException("Dictionary on group"), leaf.node.typeLength, Bytes(data, 0, data.size), n)
                }
                0, 3 -> {
                    val values: Int
                    val rows: Long
                    if (header.int(1) == 3) {
                        val v2 = header.struct(8)!!
                        values = v2.int(1) ?: 0
                        rows = (v2.int(3) ?: values).toLong()
                    } else {
                        values = header.struct(5)?.int(1) ?: 0
                        rows = if (leaf.maxRepetition == 0) values.toLong() else {
                            val probe = PageRef(offset, header, headerSize, rowCursor, 0)
                            readPagePayload(chunk, leaf, probe).repetition.count { it == 0 }.toLong()
                        }
                    }
                    pages.add(PageRef(offset, header, headerSize, rowCursor, rows))
                    rowCursor += rows
                    valuesSeen += values
                }
            }
            offset += headerSize + compressed
        }
        val index = ChunkIndex(dictionary, pages)
        synchronized(chunkIndexes) { chunkIndexes[key] = index }
        return index
    }

    private fun decodePage(rgIndex: Int, leaf: LeafColumn, pageIdx: Int): List<String> {
        val cacheKey = "$rgIndex:${leaf.index}:$pageIdx"
        synchronized(pageCache) { pageCache[cacheKey]?.let { return it } }
        val index = chunkIndex(rgIndex, leaf)
        val page = index.pages[pageIdx]
        val chunk = rowGroups[rgIndex].columns[leaf.index]
        val levels = readPagePayload(chunk, leaf, page)
        val header = page.header
        val encoding = if (header.int(1) == 3) header.struct(8)?.int(4) ?: 0 else header.struct(5)?.int(2) ?: 0
        val nonNull = levels.definition.count { it == leaf.maxDefinition }
        val b = Bytes(levels.data, levels.valuesStart, levels.data.size)
        val values = decodeValues(leaf, encoding, b, nonNull, index.dictionary)
        val rows = ArrayList<String>(page.rowCount.toInt().coerceAtLeast(0))
        var v = 0
        if (leaf.maxRepetition == 0) {
            for (d in levels.definition) rows.add(if (d == leaf.maxDefinition) format(leaf, values[v++]) else "")
        } else {
            var current: StringBuilder? = null
            var items = 0
            var rowIsNull = false
            fun flush() {
                val c = current ?: return
                rows.add(if (rowIsNull) "" else c.append(']').toString())
            }
            for (i in levels.definition.indices) {
                val d = levels.definition[i]
                if (levels.repetition[i] == 0) {
                    flush()
                    current = StringBuilder("[")
                    items = 0
                    rowIsNull = d < leaf.firstRepeatedDefinition - 1
                }
                if (d == leaf.maxDefinition) {
                    if (items++ > 0) current!!.append(", ")
                    current!!.append(format(leaf, values[v++]))
                } else if (d >= leaf.firstRepeatedDefinition) {
                    if (items++ > 0) current!!.append(", ")
                    current!!.append("null")
                }
            }
            flush()
        }
        synchronized(pageCache) { pageCache[cacheKey] = rows }
        return rows
    }

    fun cell(row: Long, leaf: LeafColumn): String {
        val rgIndex = rowGroupFor(row)
        val rg = rowGroups[rgIndex]
        val local = row - rg.firstRow
        val pages = chunkIndex(rgIndex, leaf).pages
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].firstRow <= local) lo = mid else hi = mid - 1
        }
        if (pages.isEmpty()) return ""
        val decoded = decodePage(rgIndex, leaf, lo)
        return decoded.getOrElse((local - pages[lo].firstRow).toInt()) { "" }
    }

    private fun rowGroupFor(row: Long): Int {
        var lo = 0
        var hi = rowGroups.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowGroups[mid].firstRow <= row) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun row(row: Long): List<String> = leaves.map { cell(row, it) }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun format(leaf: LeafColumn, value: Any?): String {
        if (value == null) return ""
        val n = leaf.node
        val lt = n.logicalType
        val logicalId = lt?.fields?.keys?.firstOrNull()
        val converted = n.convertedType
        return when (value) {
            is Boolean -> if (value) "true" else "false"
            is Int96 -> formatInt96(value.bytes)
            is ByteArray -> when {
                logicalId == 5 || converted == 5 -> decimal(BigInteger(if (value.isEmpty()) byteArrayOf(0) else value), lt?.struct(5)?.int(1) ?: n.scale)
                logicalId == 14 -> uuid(value)
                logicalId == 15 && value.size == 2 -> float16(value).toString()
                n.physicalType == PhysicalType.BYTE_ARRAY && (logicalId in setOf(1, 4, 12) || converted in setOf(0, 4, 19) || isMostlyText(value)) ->
                    String(value, StandardCharsets.UTF_8)
                logicalId == 13 || converted == 20 -> "[BSON ${value.size} B]"
                else -> hex(value)
            }
            is Int -> formatInteger(n, lt, logicalId, converted, value.toLong(), true)
            is Long -> formatInteger(n, lt, logicalId, converted, value, false)
            is Float -> value.toString()
            is Double -> value.toString()
            else -> value.toString()
        }
    }

    private fun formatInteger(
        n: SchemaNode, lt: ThriftCompactReader.Struct?, logicalId: Int?, converted: Int?, v: Long, is32: Boolean
    ): String {
        return when {
            logicalId == 5 || converted == 5 -> decimal(BigInteger.valueOf(v), lt?.struct(5)?.int(1) ?: n.scale)
            logicalId == 6 || converted == 6 -> LocalDate.ofEpochDay(v).format(dateFormatter)
            logicalId == 8 || converted == 9 || converted == 10 -> {
                val unit = lt?.struct(8)?.struct(2)?.fields?.keys?.firstOrNull() ?: if (converted == 9) 1 else 2
                val instant = when (unit) {
                    1 -> Instant.ofEpochMilli(v)
                    2 -> Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L) * 1000L)
                    else -> Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000_000L), Math.floorMod(v, 1_000_000_000L))
                }
                val adjusted = lt?.struct(8)?.bool(1) ?: true
                val text = formatDateTime(instant, unit)
                if (adjusted) "$text Z" else text
            }
            logicalId == 7 || converted == 7 || converted == 8 -> {
                val unit = lt?.struct(7)?.struct(2)?.fields?.keys?.firstOrNull() ?: if (converted == 7) 1 else 2
                val nanos = when (unit) { 1 -> v * 1_000_000L; 2 -> v * 1000L; else -> v }
                val t = LocalTime.ofNanoOfDay(Math.floorMod(nanos, 86_400_000_000_000L))
                "%02d:%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute, t.second) + fraction(t.nano, unit)
            }
            logicalId == 10 && lt?.struct(10)?.bool(2) == false || converted in setOf(11, 12, 13, 14) ->
                if (is32) (v and 0xFFFFFFFFL).toString() else java.lang.Long.toUnsignedString(v)
            else -> v.toString()
        }
    }

    private fun decimal(unscaled: BigInteger, scale: Int): String = BigDecimal(unscaled, scale).toPlainString()

    private fun formatInt96(b: ByteArray): String {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val nanosOfDay = bb.long
        val julianDay = bb.int.toLong()
        val epochDay = julianDay - 2440588L
        val instant = Instant.ofEpochSecond(epochDay * 86400L + Math.floorDiv(nanosOfDay, 1_000_000_000L), Math.floorMod(nanosOfDay, 1_000_000_000L))
        return formatDateTime(instant, 3)
    }

    private fun fraction(nanos: Int, unit: Int): String {
        if (nanos == 0) return ""
        return when (unit) {
            1 -> ".%03d".format(java.util.Locale.ROOT, nanos / 1_000_000)
            2 -> ".%06d".format(java.util.Locale.ROOT, nanos / 1_000)
            else -> ".%09d".format(java.util.Locale.ROOT, nanos)
        }
    }

    private fun formatDateTime(instant: Instant, unit: Int): String {
        val t = instant.atOffset(ZoneOffset.UTC).toLocalDateTime()
        return "%04d-%02d-%02d %02d:%02d:%02d".format(java.util.Locale.ROOT, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second) + fraction(t.nano, unit)
    }

    private fun uuid(b: ByteArray): String {
        if (b.size != 16) return hex(b)
        val h = b.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private fun float16(b: ByteArray): Float {
        val bits = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val sign = if (bits and 0x8000 != 0) -1f else 1f
        val exp = (bits ushr 10) and 0x1F
        val frac = bits and 0x3FF
        return sign * when (exp) {
            0 -> (frac / 1024f) * Math.pow(2.0, -14.0).toFloat()
            31 -> if (frac == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + frac / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
    }

    private fun isMostlyText(b: ByteArray): Boolean {
        if (b.isEmpty()) return true
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
            val s = decoder.decode(ByteBuffer.wrap(b)).toString()
            s.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun hex(b: ByteArray): String {
        val shown = b.take(32).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return if (b.size > 32) "0x$shown… (${b.size} B)" else "0x$shown"
    }
}
