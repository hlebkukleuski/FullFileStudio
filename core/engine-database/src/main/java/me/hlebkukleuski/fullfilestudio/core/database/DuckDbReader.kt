/*
 * FullFileStudio
 * Copyright (C) 2026 Hleb Kukleuski (https://github.com/hlebkukleuski)
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 *
 * DuckDB single-file storage, checkpoint catalog, row-group, and column compression reader
 * informed by DuckDB (https://github.com/duckdb/duckdb, Copyright 2018-2026 Stichting DuckDB
 * Foundation, MIT License), FSST (Copyright 2018-2020 CWI & TU Munich, MIT License), and
 * FastPFor bitpacking layout (Apache-2.0 License).
 */

package me.hlebkukleuski.fullfilestudio.core.database

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

object DuckDbReader {

    data class ParsedTable(
        val name: String,
        val columns: List<String>,
        val rows: List<List<String>>
    )

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
    private const val BLOCK_HEADER_SIZE = 8
    private const val METADATA_BLOCK_COUNT = 64
    private const val TERM_FIELD = 0xFFFF
    private const val INVALID_PTR = -1L
    private const val FSST_VERSION = 20190218L
    private const val MAX_PREVIEW_ROWS_PER_TABLE = 10_000
    private const val MAX_METADATA_CHAIN_BYTES = 16 * 1024 * 1024

    private const val CAT_TABLE = 1
    private const val CAT_SCHEMA = 2
    private const val CAT_VIEW = 3
    private const val CAT_INDEX = 4
    private const val CAT_SEQUENCE = 6
    private const val CAT_TYPE = 8
    private const val CAT_MACRO = 30
    private const val CAT_TABLE_MACRO = 31

    private const val COMP_UNCOMPRESSED = 1
    private const val COMP_CONSTANT = 2
    private const val COMP_RLE = 3
    private const val COMP_DICTIONARY = 4
    private const val COMP_BITPACKING = 6
    private const val COMP_FSST = 7
    private const val COMP_EMPTY = 14

    private const val LTYPE_SQLNULL = 1
    private const val LTYPE_BOOLEAN = 10
    private const val LTYPE_TINYINT = 11
    private const val LTYPE_SMALLINT = 12
    private const val LTYPE_INTEGER = 13
    private const val LTYPE_BIGINT = 14
    private const val LTYPE_DATE = 15
    private const val LTYPE_TIME = 16
    private const val LTYPE_TIMESTAMP_SEC = 17
    private const val LTYPE_TIMESTAMP_MS = 18
    private const val LTYPE_TIMESTAMP = 19
    private const val LTYPE_TIMESTAMP_NS = 20
    private const val LTYPE_DECIMAL = 21
    private const val LTYPE_FLOAT = 22
    private const val LTYPE_DOUBLE = 23
    private const val LTYPE_CHAR = 24
    private const val LTYPE_VARCHAR = 25
    private const val LTYPE_BLOB = 26
    private const val LTYPE_INTERVAL = 27
    private const val LTYPE_UTINYINT = 28
    private const val LTYPE_USMALLINT = 29
    private const val LTYPE_UINTEGER = 30
    private const val LTYPE_UBIGINT = 31
    private const val LTYPE_TIMESTAMP_TZ = 32
    private const val LTYPE_TIME_TZ = 34
    private const val LTYPE_BIT = 36
    private const val LTYPE_VARINT = 39
    private const val LTYPE_UHUGEINT = 49
    private const val LTYPE_HUGEINT = 50
    private const val LTYPE_VALIDITY = 53
    private const val LTYPE_UUID = 54
    private const val LTYPE_STRUCT = 100
    private const val LTYPE_LIST = 101
    private const val LTYPE_MAP = 102
    private const val LTYPE_ENUM = 104
    private const val LTYPE_UNION = 107
    private const val LTYPE_ARRAY = 108

    private interface BinaryInput {
        val size: Long
        fun readFully(offset: Long, dst: ByteArray, dstOff: Int, length: Int): Boolean
    }

    private class ByteArrayBinaryInput(private val data: ByteArray) : BinaryInput {
        override val size: Long get() = data.size.toLong()
        override fun readFully(offset: Long, dst: ByteArray, dstOff: Int, length: Int): Boolean {
            if (offset < 0L || length < 0 || offset + length.toLong() > data.size.toLong()) return false
            System.arraycopy(data, offset.toInt(), dst, dstOff, length)
            return true
        }
    }

    private class SourceBinaryInput(private val source: ZeroCacheReadableSource) : BinaryInput {
        override val size: Long get() = source.size
        override fun readFully(offset: Long, dst: ByteArray, dstOff: Int, length: Int): Boolean {
            if (offset < 0L || length < 0 || offset + length.toLong() > source.size) return false
            if (length == 0) return true
            val direct = ByteBuffer.allocateDirect(length)
            var done = 0
            while (done < length) {
                direct.limit(length - done)
                direct.position(0)
                val got = source.readAt(offset + done.toLong(), direct)
                if (got <= 0) return false
                direct.flip()
                direct.get(dst, dstOff + done, got)
                done += got
            }
            return true
        }
    }

    private class BlockCache(
        private val input: BinaryInput,
        val blockAllocSize: Long
    ) {
        val blockSize: Int = (blockAllocSize - BLOCK_HEADER_SIZE).toInt()
        val metaBlockSize: Int = ((blockSize / METADATA_BLOCK_COUNT) and -8).coerceAtLeast(64)
        private val cache = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)

        fun getBlockPayload(blockId: Long): ByteArray? {
            if (blockId < 0L) return null
            cache[blockId]?.let { return it }
            val diskOffset = ALL_HEADERS_SIZE.toLong() + blockId * blockAllocSize + BLOCK_HEADER_SIZE.toLong()
            if (diskOffset < ALL_HEADERS_SIZE.toLong() || diskOffset + blockSize.toLong() > input.size) {
                return null
            }
            val payload = ByteArray(blockSize)
            if (!input.readFully(diskOffset, payload, 0, blockSize)) return null
            if (cache.size >= 8) {
                val eldest = cache.keys.firstOrNull()
                if (eldest != null) cache.remove(eldest)
            }
            cache[blockId] = payload
            return payload
        }
    }

    private class DbHeader(
        val iteration: Long,
        val metaBlock: Long,
        val freeList: Long,
        val blockCount: Long,
        val blockAllocSize: Long,
        val vectorSize: Long,
        val serializationCompat: Long
    )

    private data class DuckType(
        val id: Int,
        val decimalWidth: Int = 0,
        val decimalScale: Int = 0,
        val childTypes: List<Pair<String, DuckType>> = emptyList(),
        val arraySize: Int = 0,
        val enumValues: List<String> = emptyList()
    )

    private data class DuckColumnDef(
        val name: String,
        val type: DuckType,
        val isGenerated: Boolean
    )

    private data class MetaPtr(val blockPointer: Long, val offset: Int) {
        val isValid: Boolean get() = blockPointer != INVALID_PTR
    }

    private data class TableCatalogSpec(
        val schema: String,
        val name: String,
        val columns: List<DuckColumnDef>,
        val tablePointer: MetaPtr,
        val totalRows: Long
    )

    private data class CatalogSummaryRow(
        val kind: String,
        val schema: String,
        val name: String,
        val details: String
    )

    private class SegmentStats(
        var hasNull: Boolean = false,
        var hasNoNull: Boolean = false,
        var hasMin: Boolean = false,
        var minLong: Long = 0L,
        var minUpper: Long = 0L,
        var minDouble: Double = 0.0,
        var minBytes: ByteArray? = null,
        var maxStringLen: Long = -1L
    )

    private class DataPtrSpec(
        val rowStart: Long,
        val tupleCount: Int,
        val blockId: Long,
        val blockOffset: Int,
        val compressionType: Int,
        val stats: SegmentStats,
        val overflowBlocks: LongArray
    )

    private class ColumnDataSpec(
        val dataPointers: List<DataPtrSpec>,
        val validityPointers: List<DataPtrSpec>
    )

    private class RowGroupSpec(
        val rowStart: Long,
        val tupleCount: Int,
        val columnPointers: List<MetaPtr>
    )

    private interface StreamSource {
        fun readByte(): Int
        fun readBytes(dst: ByteArray, dstOff: Int, len: Int): Boolean
    }

    private class MetaChainStream(
        private val blocks: BlockCache,
        startPtr: Long,
        initialOffset: Int
    ) : StreamSource {
        private var nextPtr: Long = startPtr
        private var hasNext: Boolean = startPtr != INVALID_PTR
        private var nextOffset: Int = initialOffset
        private var currentSubBlock: ByteArray? = null
        private var subBase: Int = 0
        private var pos: Int = 0
        private var cap: Int = 0
        private var blocksRead: Int = 0

        private fun advanceBlock(): Boolean {
            if (!hasNext || blocksRead >= 4096) return false
            val blockId = nextPtr and 0x00FFFFFFFFFFFFFFL
            val subIdx = ((nextPtr ushr 56) and 0xFFL).toInt()
            if (subIdx !in 0 until METADATA_BLOCK_COUNT) return false
            val blockPayload = blocks.getBlockPayload(blockId) ?: return false
            val base = subIdx * blocks.metaBlockSize
            if (base + blocks.metaBlockSize > blockPayload.size) return false

            val linkedNext = readI64LE(blockPayload, base)
            if (linkedNext == INVALID_PTR) {
                hasNext = false
            } else {
                nextPtr = linkedNext
            }
            val startInSub = max(8, nextOffset)
            if (startInSub > blocks.metaBlockSize) return false
            nextOffset = 8
            currentSubBlock = blockPayload
            subBase = base
            pos = startInSub
            cap = blocks.metaBlockSize
            blocksRead++
            return true
        }

        override fun readByte(): Int {
            while (pos >= cap) {
                if (!advanceBlock()) return -1
            }
            val arr = currentSubBlock ?: return -1
            val v = arr[subBase + pos].toInt() and 0xFF
            pos++
            return v
        }

        override fun readBytes(dst: ByteArray, dstOff: Int, len: Int): Boolean {
            var remaining = len
            var outPos = dstOff
            while (remaining > 0) {
                val avail = cap - pos
                if (avail <= 0) {
                    if (!advanceBlock()) return false
                    continue
                }
                val take = min(remaining, avail)
                val arr = currentSubBlock ?: return false
                System.arraycopy(arr, subBase + pos, dst, outPos, take)
                pos += take
                outPos += take
                remaining -= take
            }
            return true
        }
    }

    private class SliceStream(
        private val data: ByteArray,
        startOffset: Int
    ) : StreamSource {
        var pos: Int = startOffset

        override fun readByte(): Int {
            if (pos >= data.size) return -1
            val b = data[pos].toInt() and 0xFF
            pos++
            return b
        }

        override fun readBytes(dst: ByteArray, dstOff: Int, len: Int): Boolean {
            if (len < 0 || pos + len > data.size) return false
            System.arraycopy(data, pos, dst, dstOff, len)
            pos += len
            return true
        }
    }

    private class BinaryDeserializer(val src: StreamSource) {
        private var hasBufferedField = false
        private var bufferedField = -1

        fun peekField(): Int {
            if (!hasBufferedField) {
                bufferedField = readU16Raw()
                hasBufferedField = true
            }
            return bufferedField
        }

        fun nextField(): Int {
            if (hasBufferedField) {
                hasBufferedField = false
                return bufferedField
            }
            return readU16Raw()
        }

        fun readU16Raw(): Int {
            val b0 = src.readByte()
            val b1 = src.readByte()
            if (b0 < 0 || b1 < 0) throw IllegalStateException("EOF reading field id")
            return b0 or (b1 shl 8)
        }

        fun readI64Raw(): Long {
            val tmp = ByteArray(8)
            if (!src.readBytes(tmp, 0, 8)) throw IllegalStateException("EOF reading raw u64")
            return readI64LE(tmp, 0)
        }

        fun readBool(): Boolean {
            val b = src.readByte()
            if (b < 0) throw IllegalStateException("EOF reading bool")
            return b != 0
        }

        fun readF32(): Float {
            val tmp = ByteArray(4)
            if (!src.readBytes(tmp, 0, 4)) throw IllegalStateException("EOF reading f32")
            val bits = (tmp[0].toInt() and 0xFF) or
                ((tmp[1].toInt() and 0xFF) shl 8) or
                ((tmp[2].toInt() and 0xFF) shl 16) or
                ((tmp[3].toInt() and 0xFF) shl 24)
            return Float.fromBits(bits)
        }

        fun readF64(): Double = Double.fromBits(readI64Raw())

        fun readULEB(): Long {
            var result = 0L
            var shift = 0
            for (i in 0 until 10) {
                val b = src.readByte()
                if (b < 0) throw IllegalStateException("EOF reading ULEB128")
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalStateException("ULEB128 overflow")
        }

        fun readSLEB(bits: Int = 64): Long {
            var result = 0L
            var shift = 0
            var lastByte: Int
            var count = 0
            do {
                if (count++ >= 10) throw IllegalStateException("SLEB128 overflow")
                lastByte = src.readByte()
                if (lastByte < 0) throw IllegalStateException("EOF reading SLEB128")
                result = result or ((lastByte.toLong() and 0x7FL) shl shift)
                shift += 7
            } while ((lastByte and 0x80) != 0)

            if (shift < 64 && (lastByte and 0x40) != 0) {
                result = result or (-1L shl shift)
            }
            return when (bits) {
                8 -> result.toByte().toLong()
                16 -> result.toShort().toLong()
                32 -> result.toInt().toLong()
                else -> result
            }
        }

        fun readString(): String {
            val len = readULEB()
            if (len == 0L) return ""
            if (len < 0L || len > 4 * 1024 * 1024) throw IllegalStateException("Invalid string length $len")
            val buf = ByteArray(len.toInt())
            if (!src.readBytes(buf, 0, buf.size)) throw IllegalStateException("EOF reading string")
            return String(buf, StandardCharsets.UTF_8)
        }

        fun readBlob(): ByteArray {
            val len = readULEB()
            if (len == 0L) return ByteArray(0)
            if (len < 0L || len > 8 * 1024 * 1024) throw IllegalStateException("Invalid blob length $len")
            val buf = ByteArray(len.toInt())
            if (!src.readBytes(buf, 0, buf.size)) throw IllegalStateException("EOF reading blob")
            return buf
        }
    }

    fun isDuckDbHeader(bytes: ByteArray): Boolean =
        bytes.size >= 16 &&
            bytes[8] == 'D'.code.toByte() &&
            bytes[9] == 'U'.code.toByte() &&
            bytes[10] == 'C'.code.toByte() &&
            bytes[11] == 'K'.code.toByte()

    fun isEncrypted(bytes: ByteArray): Boolean =
        isDuckDbHeader(bytes) && bytes.size >= 28 && (bytes[20].toInt() and 1) != 0

    fun parse(bytes: ByteArray): ParsedDuckDb? = parseInput(ByteArrayBinaryInput(bytes))

    fun parse(source: ZeroCacheReadableSource): ParsedDuckDb? = parseInput(SourceBinaryInput(source))

    private fun parseInput(input: BinaryInput): ParsedDuckDb? {
        if (input.size < ALL_HEADERS_SIZE.toLong()) return null
        val headerBytes = ByteArray(ALL_HEADERS_SIZE)
        if (!input.readFully(0L, headerBytes, 0, ALL_HEADERS_SIZE)) return null
        if (!isDuckDbHeader(headerBytes)) return null

        val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val storageVersion = bb.getLong(12)
        val gitDesc = readCString(headerBytes, 52, 32)
        val libVer = readCString(headerBytes, 84, 32).ifEmpty { gitDesc }

        val h1 = readDbHeader(bb, FILE_HEADER_SIZE)
        val h2 = readDbHeader(bb, FILE_HEADER_SIZE * 2)
        val active = if (h2.iteration > h1.iteration) h2 else h1
        val blockAllocSize = if (active.blockAllocSize in 16384L..(64L * 1024L * 1024L)) {
            active.blockAllocSize
        } else {
            262144L
        }
        val vectorSize = if (active.vectorSize in 64L..65536L) active.vectorSize else 2048L

        val blocks = BlockCache(input, blockAllocSize)
        val metaStream = if (active.metaBlock != INVALID_PTR && active.metaBlock >= 0L) {
            readMetadataChain(blocks, active.metaBlock)
        } else {
            ByteArray(0)
        }

        val catalogSummaries = ArrayList<CatalogSummaryRow>()
        val tableSpecs = ArrayList<TableCatalogSpec>()
        if (metaStream.isNotEmpty()) {
            parseCatalogFromChain(metaStream, tableSpecs, catalogSummaries)
        }

        val decodedTables = ArrayList<ParsedTable>()
        for (spec in tableSpecs) {
            val decoded = decodeTableRows(blocks, vectorSize.toInt(), spec)
            val displayName = if (spec.schema.isEmpty() || spec.schema.equals("main", ignoreCase = true)) {
                spec.name
            } else {
                "${spec.schema}.${spec.name}"
            }
            val colHeaders = spec.columns.filter { !it.isGenerated }.map { it.name }.ifEmpty {
                spec.columns.map { it.name }
            }
            if (decoded != null) {
                decodedTables.add(ParsedTable(displayName, colHeaders, decoded))
            } else {
                decodedTables.add(ParsedTable(displayName, colHeaders, emptyList()))
            }
        }

        val resultTables = ArrayList<ParsedTable>(decodedTables.size + 2)
        resultTables.addAll(decodedTables)

        if (catalogSummaries.isNotEmpty()) {
            val catRows = catalogSummaries.map { listOf(it.kind, it.schema, it.name, it.details) }
            resultTables.add(
                ParsedTable(
                    "_DuckDB_Catalog",
                    listOf("Type", "Schema", "Name", "Details"),
                    catRows
                )
            )
        }

        val headerRows = listOf(
            listOf("Format", "DuckDB Columnar Storage Engine"),
            listOf("Storage Version", storageVersion.toString()),
            listOf("Library Version", libVer.ifEmpty { "v$storageVersion" }),
            listOf("Checkpoint Iteration", active.iteration.toString()),
            listOf("Active Meta Pointer", "0x${java.lang.Long.toUnsignedString(active.metaBlock, 16)}"),
            listOf("Free List Pointer", "0x${java.lang.Long.toUnsignedString(active.freeList, 16)}"),
            listOf("Block Count", active.blockCount.toString()),
            listOf("Block Alloc Size", "$blockAllocSize bytes"),
            listOf("Vector Size", vectorSize.toString()),
            listOf("Serialization Compat", active.serializationCompat.toString()),
            listOf("Metadata Stream Size", "${metaStream.size} bytes"),
            listOf("Decoded User Tables", tableSpecs.size.toString())
        )
        resultTables.add(ParsedTable("_DuckDB_Header", listOf("Property", "Value"), headerRows))

        return ParsedDuckDb(
            storageVersion = storageVersion,
            libraryVersion = libVer,
            iteration = active.iteration,
            blockCount = active.blockCount,
            blockAllocSize = blockAllocSize,
            vectorSize = vectorSize,
            tables = resultTables
        )
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

    private fun readMetadataChain(blocks: BlockCache, startPtr: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val visited = HashSet<Long>()
        var ptr = startPtr
        var guard = 0
        while (ptr != INVALID_PTR && ptr >= 0L && guard++ < 2048 && visited.add(ptr)) {
            val blockId = ptr and 0x00FFFFFFFFFFFFFFL
            val subIdx = ((ptr ushr 56) and 0xFFL).toInt()
            if (subIdx !in 0 until METADATA_BLOCK_COUNT) break
            val blockPayload = blocks.getBlockPayload(blockId) ?: break
            val off = subIdx * blocks.metaBlockSize
            if (off + blocks.metaBlockSize > blockPayload.size) break
            val nextPtr = readI64LE(blockPayload, off)
            out.write(blockPayload, off + 8, blocks.metaBlockSize - 8)
            if (out.size() > MAX_METADATA_CHAIN_BYTES) break
            ptr = nextPtr
        }
        return out.toByteArray()
    }

    private fun parseCatalogFromChain(
        chain: ByteArray,
        outTables: MutableList<TableCatalogSpec>,
        outSummaries: MutableList<CatalogSummaryRow>
    ) {
        val entryOffsets = findCatalogEntryOffsets(chain)
        if (entryOffsets.isEmpty()) return
        val seenTableKeys = HashSet<String>()

        for (entryIdx in entryOffsets.indices) {
            val startPos = entryOffsets[entryIdx]
            val slice = SliceStream(chain, startPos)
            val d = BinaryDeserializer(slice)
            try {
                if (d.nextField() != 99) continue
                val ctype = d.readULEB().toInt()
                when (ctype) {
                    CAT_SCHEMA -> {
                        if (d.nextField() != 100 || !d.readBool()) continue
                        var schemaName = ""
                        while (true) {
                            val fid = d.nextField()
                            if (fid == TERM_FIELD) break
                            when (fid) {
                                100, 105 -> d.readULEB()
                                101, 106 -> d.readString()
                                102 -> schemaName = d.readString()
                                103, 104 -> d.readBool()
                                107 -> skipValue(d, null)
                                108 -> skipStringMap(d)
                                109 -> skipDependencyList(d)
                                else -> throw IllegalStateException("Unexpected schema field $fid")
                            }
                        }
                        if (d.nextField() == TERM_FIELD && schemaName.isNotEmpty()) {
                            outSummaries.add(CatalogSummaryRow("SCHEMA", schemaName, schemaName, ""))
                        }
                    }
                    CAT_SEQUENCE -> {
                        if (d.nextField() != 100 || !d.readBool()) continue
                        var schemaName = "main"
                        var seqName = ""
                        var startVal = 1L
                        while (true) {
                            val fid = d.nextField()
                            if (fid == TERM_FIELD) break
                            when (fid) {
                                100, 105 -> d.readULEB()
                                101, 106 -> d.readString()
                                102 -> schemaName = d.readString()
                                103, 104 -> d.readBool()
                                107 -> skipValue(d, null)
                                108 -> skipStringMap(d)
                                109 -> skipDependencyList(d)
                                200 -> seqName = d.readString()
                                201 -> d.readULEB()
                                202, 203, 204 -> d.readSLEB(64)
                                205 -> startVal = d.readSLEB(64)
                                206 -> d.readBool()
                                else -> throw IllegalStateException("Unexpected sequence field $fid")
                            }
                        }
                        if (d.nextField() == TERM_FIELD && seqName.isNotEmpty()) {
                            outSummaries.add(CatalogSummaryRow("SEQUENCE", schemaName, seqName, "start=$startVal"))
                        }
                    }
                    CAT_TYPE -> {
                        if (d.nextField() != 100 || !d.readBool()) continue
                        var schemaName = "main"
                        var typeName = ""
                        var logicalType: DuckType? = null
                        while (true) {
                            val fid = d.nextField()
                            if (fid == TERM_FIELD) break
                            when (fid) {
                                100, 105 -> d.readULEB()
                                101, 106 -> d.readString()
                                102 -> schemaName = d.readString()
                                103, 104 -> d.readBool()
                                107 -> skipValue(d, null)
                                108 -> skipStringMap(d)
                                109 -> skipDependencyList(d)
                                200 -> typeName = d.readString()
                                201 -> logicalType = readLogicalType(d)
                                else -> throw IllegalStateException("Unexpected type field $fid")
                            }
                        }
                        if (d.nextField() == TERM_FIELD && typeName.isNotEmpty()) {
                            outSummaries.add(
                                CatalogSummaryRow("TYPE", schemaName, typeName, logicalType?.let { typeLabel(it) } ?: "")
                            )
                        }
                    }
                    CAT_TABLE -> {
                        val spec = parseTableCatalogEntry(d, chain, if (entryIdx + 1 < entryOffsets.size) entryOffsets[entryIdx + 1] else chain.size)
                        if (spec != null && seenTableKeys.add("${spec.schema}.${spec.name}")) {
                            outTables.add(spec)
                            outSummaries.add(
                                CatalogSummaryRow(
                                    "TABLE",
                                    spec.schema.ifEmpty { "main" },
                                    spec.name,
                                    "${spec.columns.size} cols, ${spec.totalRows} rows"
                                )
                            )
                        }
                    }
                    CAT_VIEW, CAT_MACRO, CAT_TABLE_MACRO, CAT_INDEX -> {
                        if (d.nextField() != 100 || !d.readBool()) continue
                        var schemaName = "main"
                        var sql = ""
                        var objName = ""
                        while (true) {
                            val fid = d.nextField()
                            if (fid == TERM_FIELD) break
                            when (fid) {
                                100, 105 -> d.readULEB()
                                101 -> d.readString()
                                102 -> schemaName = d.readString()
                                103, 104 -> d.readBool()
                                106 -> sql = d.readString()
                                107 -> skipValue(d, null)
                                108 -> skipStringMap(d)
                                109 -> skipDependencyList(d)
                                200 -> {
                                    objName = d.readString()
                                    break
                                }
                                else -> break
                            }
                        }
                        val kindLabel = when (ctype) {
                            CAT_VIEW -> "VIEW"
                            CAT_INDEX -> "INDEX"
                            CAT_TABLE_MACRO -> "TABLE_MACRO"
                            else -> "MACRO"
                        }
                        if (objName.isNotEmpty() || sql.isNotEmpty()) {
                            outSummaries.add(CatalogSummaryRow(kindLabel, schemaName, objName, sql.take(120)))
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun findCatalogEntryOffsets(chain: ByteArray): IntArray {
        val offsets = ArrayList<Int>()
        val slice = SliceStream(chain, 0)
        val d = BinaryDeserializer(slice)
        val listStartsAt3 = try {
            d.nextField() == 100 && d.readULEB() >= 0L
        } catch (_: Throwable) {
            false
        }
        val scanFrom = if (listStartsAt3) slice.pos else 0
        var i = scanFrom
        while (i <= chain.size - 9) {
            if (chain[i] == 0x63.toByte() &&
                chain[i + 1] == 0x00.toByte() &&
                chain[i + 3] == 0x64.toByte() &&
                chain[i + 4] == 0x00.toByte() &&
                chain[i + 5] == 0x01.toByte() &&
                chain[i + 6] == 0x64.toByte() &&
                chain[i + 7] == 0x00.toByte()
            ) {
                val ctype = chain[i + 2].toInt() and 0xFF
                val infoType = chain[i + 8].toInt() and 0xFF
                if (ctype == infoType && isKnownCatalogType(ctype)) {
                    offsets.add(i)
                    i += 9
                    continue
                }
            }
            i++
        }
        return offsets.toIntArray()
    }

    private fun isKnownCatalogType(ctype: Int): Boolean =
        ctype == CAT_TABLE ||
            ctype == CAT_SCHEMA ||
            ctype == CAT_VIEW ||
            ctype == CAT_INDEX ||
            ctype == CAT_SEQUENCE ||
            ctype == CAT_TYPE ||
            ctype == CAT_MACRO ||
            ctype == CAT_TABLE_MACRO

    private fun parseTableCatalogEntry(
        d: BinaryDeserializer,
        chain: ByteArray,
        entryBound: Int
    ): TableCatalogSpec? {
        if (d.nextField() != 100 || !d.readBool()) return null
        var schemaName = "main"
        var tableName = ""
        val columns = ArrayList<DuckColumnDef>()

        try {
            while (true) {
                val fid = d.nextField()
                if (fid == TERM_FIELD) break
                when (fid) {
                    100, 105 -> d.readULEB()
                    101, 106 -> d.readString()
                    102 -> schemaName = d.readString()
                    103, 104 -> d.readBool()
                    107 -> skipValue(d, null)
                    108 -> skipStringMap(d)
                    109 -> skipDependencyList(d)
                    200 -> tableName = d.readString()
                    201 -> {
                        while (true) {
                            val cf = d.nextField()
                            if (cf == TERM_FIELD) break
                            if (cf == 100) {
                                val colCount = d.readULEB().toInt()
                                if (colCount !in 0..4096) return null
                                for (c in 0 until colCount) {
                                    columns.add(readColumnDefinition(d))
                                }
                            } else {
                                throw IllegalStateException("Unexpected ColumnList field $cf")
                            }
                        }
                    }
                    202 -> {
                        val conCount = d.readULEB().toInt()
                        for (k in 0 until conCount) {
                            skipConstraint(d)
                        }
                    }
                    203 -> {
                        if (d.readBool()) {
                            throw IllegalStateException("Unexpected non-null query in CreateTableInfo")
                        }
                    }
                    else -> throw IllegalStateException("Unexpected CreateTableInfo field $fid")
                }
            }

            return parseTableStorageTail(d, schemaName, tableName, columns)
        } catch (_: Throwable) {
            if (tableName.isEmpty() || columns.isEmpty()) return null
            val currentPos = (d.src as? SliceStream)?.pos ?: return null
            return recoverTableStorageTail(chain, currentPos, entryBound, schemaName, tableName, columns)
        }
    }

    private fun parseTableStorageTail(
        d: BinaryDeserializer,
        schemaName: String,
        tableName: String,
        columns: List<DuckColumnDef>
    ): TableCatalogSpec? {
        var tablePtr: MetaPtr? = null
        var totalRows = 0L
        while (true) {
            val tf = d.nextField()
            if (tf == TERM_FIELD) break
            when (tf) {
                101 -> tablePtr = readMetaBlockPointer(d)
                102 -> totalRows = d.readULEB()
                103 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) readBlockPointer(d)
                }
                104 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) skipIndexStorageInfo(d)
                }
                else -> return null
            }
        }
        val resolvedPtr = tablePtr ?: return null
        if (tableName.isEmpty() || columns.isEmpty()) return null
        return TableCatalogSpec(schemaName, tableName, columns, resolvedPtr, totalRows)
    }

    private fun recoverTableStorageTail(
        chain: ByteArray,
        fromPos: Int,
        toPos: Int,
        schemaName: String,
        tableName: String,
        columns: List<DuckColumnDef>
    ): TableCatalogSpec? {
        val limit = min(toPos, chain.size) - 10
        for (pos in fromPos..limit) {
            if (chain[pos] == 0x65.toByte() &&
                chain[pos + 1] == 0x00.toByte() &&
                chain[pos + 2] == 0x64.toByte() &&
                chain[pos + 3] == 0x00.toByte()
            ) {
                try {
                    val sd = BinaryDeserializer(SliceStream(chain, pos))
                    val candidate = parseTableStorageTail(sd, schemaName, tableName, columns)
                    if (candidate != null) return candidate
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun readColumnDefinition(d: BinaryDeserializer): DuckColumnDef {
        var colName = ""
        var colType = DuckType(LTYPE_VARCHAR)
        var category = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> colName = d.readString()
                101 -> colType = readLogicalType(d)
                102 -> skipNullableParsedExpression(d)
                103 -> category = d.readULEB().toInt()
                104 -> d.readULEB()
                105 -> skipValue(d, null)
                106 -> skipStringMap(d)
                else -> throw IllegalStateException("Unexpected ColumnDefinition field $fid")
            }
        }
        return DuckColumnDef(colName, colType, isGenerated = category == 1)
    }

    private fun readLogicalType(d: BinaryDeserializer): DuckType {
        var id = LTYPE_VARCHAR
        var decWidth = 0
        var decScale = 0
        val children = ArrayList<Pair<String, DuckType>>()
        var arrSize = 0
        val enumVals = ArrayList<String>()

        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> id = d.readULEB().toInt()
                101 -> {
                    if (d.readBool()) {
                        var extraType = 0
                        while (true) {
                            val ef = d.nextField()
                            if (ef == TERM_FIELD) break
                            when (ef) {
                                100 -> extraType = d.readULEB().toInt()
                                101 -> d.readString()
                                103 -> {
                                    if (d.readBool()) skipExtensionTypeInfo(d)
                                }
                                200 -> when (extraType) {
                                    2 -> decWidth = d.readULEB().toInt()
                                    3 -> d.readString()
                                    4, 9 -> children.add("" to readLogicalType(d))
                                    5 -> {
                                        val cnt = d.readULEB().toInt()
                                        for (i in 0 until cnt) {
                                            var childName = ""
                                            var childType = DuckType(LTYPE_VARCHAR)
                                            while (true) {
                                                val pf = d.nextField()
                                                if (pf == TERM_FIELD) break
                                                when (pf) {
                                                    100 -> childName = d.readString()
                                                    101 -> childType = readLogicalType(d)
                                                }
                                            }
                                            children.add(childName to childType)
                                        }
                                    }
                                    6 -> {
                                        val enumSize = d.readULEB().toInt()
                                        for (i in 0 until min(enumSize, 65536)) {
                                            enumVals.add(d.readString())
                                        }
                                    }
                                    7, 8 -> d.readString()
                                    10 -> readLogicalType(d)
                                    11 -> skipValue(d, null)
                                    else -> throw IllegalStateException("Unsupported ExtraTypeInfo $extraType field 200")
                                }
                                201 -> when (extraType) {
                                    2 -> decScale = d.readULEB().toInt()
                                    7 -> d.readString()
                                    8 -> readLogicalType(d)
                                    9, 10 -> arrSize = d.readULEB().toInt()
                                    else -> throw IllegalStateException("Unsupported ExtraTypeInfo $extraType field 201")
                                }
                                202 -> when (extraType) {
                                    7 -> d.readString()
                                    8 -> {
                                        val n = d.readULEB().toInt()
                                        for (i in 0 until n) readLogicalType(d)
                                    }
                                }
                                203 -> {
                                    val n = d.readULEB().toInt()
                                    for (i in 0 until n) skipValue(d, null)
                                }
                                else -> throw IllegalStateException("Unexpected ExtraTypeInfo field $ef")
                            }
                        }
                    }
                }
                else -> throw IllegalStateException("Unexpected LogicalType field $fid")
            }
        }
        return DuckType(id, decWidth, decScale, children, arrSize, enumVals)
    }

    private fun skipExtensionTypeInfo(d: BinaryDeserializer) {
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) {
                        while (true) {
                            val mf = d.nextField()
                            if (mf == TERM_FIELD) break
                            when (mf) {
                                100 -> skipValue(d, null)
                                101 -> d.readString()
                            }
                        }
                    }
                }
                101 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) {
                        while (true) {
                            val pf = d.nextField()
                            if (pf == TERM_FIELD) break
                            when (pf) {
                                100 -> d.readString()
                                101 -> skipValue(d, null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun skipNullableParsedExpression(d: BinaryDeserializer) {
        if (!d.readBool()) return
        var exprClass = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> exprClass = d.readULEB().toInt()
                101 -> d.readULEB()
                102 -> d.readString()
                103 -> {
                    while (true) {
                        val qf = d.nextField()
                        if (qf == TERM_FIELD) break
                        if (qf == 100) d.readBool()
                        else if (qf == 101) d.readULEB()
                    }
                }
                200 -> when (exprClass) {
                    3, 16, 19 -> skipNullableParsedExpression(d)
                    4 -> {
                        val n = d.readULEB().toInt()
                        for (i in 0 until n) d.readString()
                    }
                    5 -> skipNullableParsedExpression(d)
                    6, 10 -> {
                        val n = d.readULEB().toInt()
                        for (i in 0 until n) skipNullableParsedExpression(d)
                    }
                    7 -> skipValue(d, null)
                    9 -> d.readString()
                    15, 18, 20 -> d.readULEB()
                    else -> throw IllegalStateException("Unsupported exprClass $exprClass field 200")
                }
                201 -> when (exprClass) {
                    3 -> readLogicalType(d)
                    5, 19 -> skipNullableParsedExpression(d)
                    9, 16, 20 -> d.readString()
                    else -> throw IllegalStateException("Unsupported exprClass $exprClass field 201")
                }
                202 -> when (exprClass) {
                    3 -> d.readBool()
                    9 -> {
                        val n = d.readULEB().toInt()
                        for (i in 0 until n) skipNullableParsedExpression(d)
                    }
                    19 -> skipNullableParsedExpression(d)
                    else -> throw IllegalStateException("Unsupported exprClass $exprClass field 202")
                }
                203 -> if (exprClass == 9) skipNullableParsedExpression(d) else throw IllegalStateException("Expr 203")
                204 -> if (exprClass == 9) {
                    if (d.readBool()) throw IllegalStateException("OrderModifier in expression")
                } else throw IllegalStateException("Expr 204")
                205, 206, 207 -> d.readBool()
                208 -> d.readString()
                else -> throw IllegalStateException("Unsupported ParsedExpression field $fid")
            }
        }
    }

    private fun skipConstraint(d: BinaryDeserializer) {
        if (!d.readBool()) return
        var ctype = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> ctype = d.readULEB().toInt()
                200 -> when (ctype) {
                    1 -> skipWrappedIndex(d)
                    2 -> skipNullableParsedExpression(d)
                    3 -> d.readBool()
                    4 -> skipStringList(d)
                    else -> throw IllegalStateException("Constraint $ctype")
                }
                201 -> when (ctype) {
                    3 -> skipWrappedIndex(d)
                    4 -> skipStringList(d)
                }
                202 -> when (ctype) {
                    3 -> skipStringList(d)
                    4 -> d.readULEB()
                }
                203, 204 -> d.readString()
                205, 206 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) skipWrappedIndex(d)
                }
            }
        }
    }

    private fun skipWrappedIndex(d: BinaryDeserializer) {
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            if (fid == 100) d.readULEB()
        }
    }

    private fun skipStringList(d: BinaryDeserializer) {
        val n = d.readULEB().toInt()
        for (i in 0 until n) d.readString()
    }

    private fun skipStringMap(d: BinaryDeserializer) {
        val n = d.readULEB().toInt()
        for (i in 0 until n) {
            while (true) {
                val fid = d.nextField()
                if (fid == TERM_FIELD) break
                if (fid == 100 || fid == 101) d.readString()
            }
        }
    }

    private fun skipDependencyList(d: BinaryDeserializer) {
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            if (fid == 100) {
                val n = d.readULEB().toInt()
                for (i in 0 until n) {
                    while (true) {
                        val df = d.nextField()
                        if (df == TERM_FIELD) break
                        when (df) {
                            100 -> {
                                while (true) {
                                    val cf = d.nextField()
                                    if (cf == TERM_FIELD) break
                                    when (cf) {
                                        100 -> d.readULEB()
                                        101, 102 -> d.readString()
                                    }
                                }
                            }
                            101 -> d.readString()
                        }
                    }
                }
            }
        }
    }

    private fun skipIndexStorageInfo(d: BinaryDeserializer) {
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> d.readString()
                101 -> d.readULEB()
                102 -> {
                    val n = d.readULEB().toInt()
                    for (i in 0 until n) {
                        while (true) {
                            val af = d.nextField()
                            if (af == TERM_FIELD) break
                            when (af) {
                                100 -> d.readULEB()
                                101, 103, 104, 105 -> {
                                    val m = d.readULEB().toInt()
                                    for (j in 0 until m) d.readULEB()
                                }
                                102 -> {
                                    val m = d.readULEB().toInt()
                                    for (j in 0 until m) readBlockPointer(d)
                                }
                            }
                        }
                    }
                }
                103 -> {
                    val n = d.readULEB().toInt()
                    for (i in 0 until n) {
                        while (true) {
                            val mf = d.nextField()
                            if (mf == TERM_FIELD) break
                            when (mf) {
                                100 -> d.readString()
                                101 -> skipValue(d, null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun skipValue(d: BinaryDeserializer, inheritedType: DuckType?) {
        var resolvedType = inheritedType
        var isNull = true
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> resolvedType = readLogicalType(d)
                101 -> isNull = d.readBool()
                102 -> {
                    if (!isNull) {
                        val t = resolvedType ?: DuckType(LTYPE_VARCHAR)
                        when (t.id) {
                            LTYPE_BOOLEAN -> d.readBool()
                            LTYPE_TINYINT, LTYPE_SMALLINT, LTYPE_INTEGER, LTYPE_BIGINT,
                            LTYPE_DATE, LTYPE_TIME, LTYPE_TIMESTAMP_SEC, LTYPE_TIMESTAMP_MS,
                            LTYPE_TIMESTAMP, LTYPE_TIMESTAMP_NS, LTYPE_TIMESTAMP_TZ, LTYPE_TIME_TZ -> d.readSLEB(64)
                            LTYPE_UTINYINT, LTYPE_USMALLINT, LTYPE_UINTEGER, LTYPE_UBIGINT -> d.readULEB()
                            LTYPE_DECIMAL -> if (t.decimalWidth <= 18) d.readSLEB(64) else {
                                d.readSLEB(64)
                                d.readULEB()
                            }
                            LTYPE_HUGEINT, LTYPE_UUID -> {
                                d.readSLEB(64)
                                d.readULEB()
                            }
                            LTYPE_UHUGEINT -> {
                                d.readULEB()
                                d.readULEB()
                            }
                            LTYPE_FLOAT -> d.readF32()
                            LTYPE_DOUBLE -> d.readF64()
                            LTYPE_INTERVAL -> {
                                while (true) {
                                    val ivf = d.nextField()
                                    if (ivf == TERM_FIELD) break
                                    d.readSLEB(64)
                                }
                            }
                            LTYPE_VARCHAR, LTYPE_CHAR -> d.readString()
                            LTYPE_BLOB, LTYPE_BIT, LTYPE_VARINT -> d.readBlob()
                            LTYPE_LIST, LTYPE_ARRAY -> {
                                val child = t.childTypes.firstOrNull()?.second
                                val n = d.readULEB().toInt()
                                for (i in 0 until n) skipValue(d, child)
                            }
                            LTYPE_STRUCT -> {
                                val n = d.readULEB().toInt()
                                for (i in 0 until n) {
                                    skipValue(d, t.childTypes.getOrNull(i)?.second)
                                }
                            }
                            else -> d.readString()
                        }
                    }
                }
            }
        }
    }

    private fun readMetaBlockPointer(d: BinaryDeserializer): MetaPtr {
        var ptr = INVALID_PTR
        var off = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> ptr = d.readULEB()
                101 -> off = d.readULEB().toInt()
            }
        }
        return MetaPtr(ptr, off)
    }

    private fun readBlockPointer(d: BinaryDeserializer): Pair<Long, Int> {
        var blockId = -1L
        var offset = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> blockId = d.readSLEB(64)
                101 -> offset = d.readULEB().toInt()
            }
        }
        return blockId to offset
    }

    private fun decodeTableRows(
        blocks: BlockCache,
        vectorSize: Int,
        spec: TableCatalogSpec
    ): List<List<String>>? {
        if (!spec.tablePointer.isValid) return emptyList()
        val physCols = spec.columns.filter { !it.isGenerated }
        if (physCols.isEmpty() || spec.totalRows <= 0L) return emptyList()

        return try {
            val metaStream = MetaChainStream(blocks, spec.tablePointer.blockPointer, spec.tablePointer.offset)
            val td = BinaryDeserializer(metaStream)
            while (true) {
                val sf = td.nextField()
                if (sf == TERM_FIELD) break
                when (sf) {
                    100 -> {
                        val statsCount = td.readULEB().toInt()
                        for (ci in 0 until statsCount) {
                            val colType = physCols.getOrNull(ci)?.type ?: DuckType(LTYPE_BIGINT)
                            skipColumnStatistics(td, colType)
                        }
                    }
                    101 -> skipNullableBlockingSample(td)
                    else -> return null
                }
            }

            val rowGroupCount = td.readI64Raw()
            if (rowGroupCount <= 0L || rowGroupCount > 1_000_000L) return emptyList()

            val rowsOut = ArrayList<List<String>>(min(spec.totalRows, MAX_PREVIEW_ROWS_PER_TABLE.toLong()).toInt())
            var remainingBudget = MAX_PREVIEW_ROWS_PER_TABLE

            for (rgi in 0 until rowGroupCount.toInt()) {
                if (remainingBudget <= 0) break
                val rg = readRowGroupSpec(td) ?: break
                if (rg.tupleCount <= 0 || rg.columnPointers.size < physCols.size) continue
                val takeRows = min(rg.tupleCount, remainingBudget)

                val decodedColumns = Array(physCols.size) { colIdx ->
                    val colPtr = rg.columnPointers[colIdx]
                    val colType = physCols[colIdx].type
                    decodeColumnValues(blocks, vectorSize, colPtr, colType, takeRows)
                }

                for (r in 0 until takeRows) {
                    val row = ArrayList<String>(physCols.size)
                    for (c in physCols.indices) {
                        row.add(decodedColumns[c].getOrElse(r) { "NULL" })
                    }
                    rowsOut.add(row)
                }
                remainingBudget -= takeRows
            }
            rowsOut
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRowGroupSpec(d: BinaryDeserializer): RowGroupSpec? {
        var rowStart = 0L
        var tupleCount = 0
        val colPtrs = ArrayList<MetaPtr>()
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> rowStart = d.readULEB()
                101 -> tupleCount = d.readULEB().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                102 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) colPtrs.add(readMetaBlockPointer(d))
                }
                103 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) readMetaBlockPointer(d)
                }
                else -> return null
            }
        }
        return RowGroupSpec(rowStart, tupleCount, colPtrs)
    }

    private fun skipColumnStatistics(d: BinaryDeserializer, type: DuckType) {
        if (!d.readBool()) return
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> readBaseStatistics(d, type)
                101 -> {
                    if (d.readBool()) {
                        while (true) {
                            val df = d.nextField()
                            if (df == TERM_FIELD) break
                            when (df) {
                                100, 101 -> d.readULEB()
                                102 -> {
                                    if (d.readBool()) {
                                        while (true) {
                                            val hf = d.nextField()
                                            if (hf == TERM_FIELD) break
                                            when (hf) {
                                                100 -> d.readULEB()
                                                101 -> d.readBlob()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun skipNullableBlockingSample(d: BinaryDeserializer) {
        if (!d.readBool()) return
        var sampleType = 0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> {
                    if (d.readBool()) {
                        while (true) {
                            val bf = d.nextField()
                            if (bf == TERM_FIELD) break
                            when (bf) {
                                100, 101, 102, 103, 104 -> d.readULEB()
                                105 -> {
                                    val wcnt = d.readULEB().toInt()
                                    for (i in 0 until wcnt) {
                                        while (true) {
                                            val pf = d.nextField()
                                            if (pf == TERM_FIELD) break
                                            when (pf) {
                                                100 -> d.readF64()
                                                101 -> d.readULEB()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                101 -> sampleType = d.readULEB().toInt()
                102 -> d.readBool()
                200 -> if (sampleType == 1) d.readF64() else d.readULEB()
                201 -> if (sampleType == 1) d.readULEB() else {
                    if (d.readBool()) {
                        throw IllegalStateException("Non-empty ReservoirChunk")
                    }
                }
            }
        }
    }

    private fun readBaseStatistics(d: BinaryDeserializer, type: DuckType): SegmentStats {
        val st = SegmentStats()
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> st.hasNull = d.readBool()
                101 -> st.hasNoNull = d.readBool()
                102 -> d.readULEB()
                103 -> {
                    while (true) {
                        val tf = d.nextField()
                        if (tf == TERM_FIELD) break
                        when {
                            type.id == LTYPE_VALIDITY || type.id == LTYPE_INTERVAL || type.id == LTYPE_SQLNULL -> {
                            }
                            isStringLike(type) -> when (tf) {
                                200 -> st.minBytes = d.readBlob()
                                201 -> d.readBlob()
                                202, 203 -> d.readBool()
                                204 -> st.maxStringLen = d.readULEB()
                            }
                            type.id == LTYPE_LIST || type.id == LTYPE_ARRAY -> {
                                if (tf == 200) {
                                    val child = type.childTypes.firstOrNull()?.second ?: DuckType(LTYPE_VARCHAR)
                                    readBaseStatistics(d, child)
                                }
                            }
                            type.id == LTYPE_STRUCT -> {
                                if (tf == 200) {
                                    val n = d.readULEB().toInt()
                                    for (i in 0 until n) {
                                        val child = type.childTypes.getOrNull(i)?.second ?: DuckType(LTYPE_VARCHAR)
                                        readBaseStatistics(d, child)
                                    }
                                }
                            }
                            else -> when (tf) {
                                200 -> readNumericStatsBound(d, type, st, isMinSlot = true)
                                201 -> readNumericStatsBound(d, type, st, isMinSlot = false)
                            }
                        }
                    }
                }
            }
        }
        return st
    }

    private fun readNumericStatsBound(
        d: BinaryDeserializer,
        type: DuckType,
        st: SegmentStats,
        isMinSlot: Boolean
    ) {
        var hasVal = false
        var vLong = 0L
        var vUpper = 0L
        var vDouble = 0.0
        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> hasVal = d.readBool()
                101 -> {
                    when (physicalCategory(type)) {
                        PhysCat.BOOL -> vLong = if (d.readBool()) 1L else 0L
                        PhysCat.I8 -> vLong = d.readSLEB(8)
                        PhysCat.I16 -> vLong = d.readSLEB(16)
                        PhysCat.I32 -> vLong = d.readSLEB(32)
                        PhysCat.I64 -> vLong = d.readSLEB(64)
                        PhysCat.U8, PhysCat.U16, PhysCat.U32, PhysCat.U64 -> vLong = d.readULEB()
                        PhysCat.I128 -> {
                            vUpper = d.readSLEB(64)
                            vLong = d.readULEB()
                        }
                        PhysCat.U128 -> {
                            vUpper = d.readULEB()
                            vLong = d.readULEB()
                        }
                        PhysCat.F32 -> vDouble = d.readF32().toDouble()
                        PhysCat.F64 -> vDouble = d.readF64()
                        else -> vLong = d.readSLEB(64)
                    }
                }
            }
        }
        if (isMinSlot && hasVal) {
            st.hasMin = true
            st.minLong = vLong
            st.minUpper = vUpper
            st.minDouble = vDouble
        }
    }

    private fun decodeColumnValues(
        blocks: BlockCache,
        vectorSize: Int,
        colPtr: MetaPtr,
        type: DuckType,
        maxRows: Int
    ): List<String> {
        if (!colPtr.isValid || maxRows <= 0) return emptyList()
        return try {
            val d = BinaryDeserializer(MetaChainStream(blocks, colPtr.blockPointer, colPtr.offset))
            val colData = readPersistentColumnData(d, type)
            val validMask = decodeValidityMask(blocks, colData.validityPointers, maxRows)

            val out = ArrayList<String>(maxRows)
            var rowCursor = 0
            for (dp in colData.dataPointers) {
                if (out.size >= maxRows) break
                val take = min(dp.tupleCount, maxRows - out.size)
                if (take <= 0) continue

                val segmentValues = decodeSegmentValues(blocks, vectorSize, dp, type, take)
                for (i in 0 until take) {
                    val globalIdx = rowCursor + i
                    val isValid = validMask?.getOrElse(globalIdx) { true } ?: true
                    if (!isValid || (dp.stats.hasNull && !dp.stats.hasNoNull)) {
                        out.add("NULL")
                    } else {
                        out.add(segmentValues.getOrElse(i) { "NULL" })
                    }
                }
                rowCursor += dp.tupleCount
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readPersistentColumnData(d: BinaryDeserializer, type: DuckType): ColumnDataSpec {
        val dataPtrs = ArrayList<DataPtrSpec>()
        var validityPtrs: List<DataPtrSpec> = emptyList()

        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> {
                    val cnt = d.readULEB().toInt()
                    for (i in 0 until cnt) {
                        dataPtrs.add(readDataPointer(d, type))
                    }
                }
                101 -> {
                    if (type.id != LTYPE_VALIDITY) {
                        validityPtrs = readPersistentColumnData(d, DuckType(LTYPE_VALIDITY)).dataPointers
                    }
                }
                102 -> {
                    when (type.id) {
                        LTYPE_LIST, LTYPE_ARRAY -> {
                            val child = type.childTypes.firstOrNull()?.second ?: DuckType(LTYPE_VARCHAR)
                            readPersistentColumnData(d, child)
                        }
                        LTYPE_STRUCT -> {
                            val n = d.readULEB().toInt()
                            for (i in 0 until n) {
                                val child = type.childTypes.getOrNull(i)?.second ?: DuckType(LTYPE_VARCHAR)
                                readPersistentColumnData(d, child)
                            }
                        }
                    }
                }
            }
        }
        return ColumnDataSpec(dataPtrs, validityPtrs)
    }

    private fun readDataPointer(d: BinaryDeserializer, type: DuckType): DataPtrSpec {
        var rowStart = 0L
        var tupleCount = 0
        var blockId = -1L
        var blockOffset = 0
        var compType = COMP_UNCOMPRESSED
        var stats = SegmentStats()
        var overflowBlocks = LongArray(0)

        while (true) {
            val fid = d.nextField()
            if (fid == TERM_FIELD) break
            when (fid) {
                100 -> rowStart = d.readULEB()
                101 -> tupleCount = d.readULEB().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                102 -> {
                    val bp = readBlockPointer(d)
                    blockId = bp.first
                    blockOffset = bp.second
                }
                103 -> compType = d.readULEB().toInt()
                104 -> stats = readBaseStatistics(d, type)
                105 -> {
                    if (d.readBool()) {
                        val blks = ArrayList<Long>()
                        while (true) {
                            val sf = d.nextField()
                            if (sf == TERM_FIELD) break
                            if (sf == 100) {
                                val nb = d.readULEB().toInt()
                                for (i in 0 until nb) blks.add(d.readSLEB(64))
                            }
                        }
                        overflowBlocks = blks.toLongArray()
                    }
                }
            }
        }
        return DataPtrSpec(rowStart, tupleCount, blockId, blockOffset, compType, stats, overflowBlocks)
    }

    private fun decodeValidityMask(
        blocks: BlockCache,
        ptrs: List<DataPtrSpec>,
        maxRows: Int
    ): BooleanArray? {
        if (ptrs.isEmpty()) return null
        val mask = BooleanArray(maxRows) { true }
        var cursor = 0
        for (dp in ptrs) {
            if (cursor >= maxRows) break
            val take = min(dp.tupleCount, maxRows - cursor)
            when (dp.compressionType) {
                COMP_EMPTY -> {
                    val valid = !(dp.stats.hasNull && !dp.stats.hasNoNull)
                    for (i in 0 until take) mask[cursor + i] = valid
                }
                COMP_CONSTANT -> {
                    val valid = !(dp.stats.hasNull && !dp.stats.hasNoNull)
                    for (i in 0 until take) mask[cursor + i] = valid
                }
                COMP_UNCOMPRESSED -> {
                    val payload = blocks.getBlockPayload(dp.blockId)
                    if (payload != null) {
                        for (i in 0 until take) {
                            val byteIdx = dp.blockOffset + (i ushr 3)
                            if (byteIdx in payload.indices) {
                                mask[cursor + i] = ((payload[byteIdx].toInt() ushr (i and 7)) and 1) != 0
                            }
                        }
                    }
                }
            }
            cursor += dp.tupleCount
        }
        return mask
    }

    private fun decodeSegmentValues(
        blocks: BlockCache,
        vectorSize: Int,
        dp: DataPtrSpec,
        type: DuckType,
        count: Int
    ): List<String> {
        if (count <= 0) return emptyList()
        if (dp.stats.hasNull && !dp.stats.hasNoNull) {
            return List(count) { "NULL" }
        }
        return when (dp.compressionType) {
            COMP_EMPTY -> List(count) { "NULL" }
            COMP_CONSTANT -> {
                val single = formatConstantValue(dp.stats, type)
                List(count) { single }
            }
            COMP_UNCOMPRESSED -> {
                val payload = blocks.getBlockPayload(dp.blockId) ?: return List(count) { "" }
                if (isStringLike(type)) {
                    decodeUncompressedStrings(blocks, payload, dp.blockOffset, count, type.id == LTYPE_BLOB)
                } else {
                    decodeUncompressedPrimitives(payload, dp.blockOffset, count, type)
                }
            }
            COMP_RLE -> {
                val payload = blocks.getBlockPayload(dp.blockId) ?: return List(count) { "" }
                decodeRleSegment(payload, dp.blockOffset, count, type)
            }
            COMP_BITPACKING -> {
                val payload = blocks.getBlockPayload(dp.blockId) ?: return List(count) { "" }
                decodeBitpackingSegment(payload, dp.blockOffset, count, vectorSize, type)
            }
            COMP_DICTIONARY -> {
                val payload = blocks.getBlockPayload(dp.blockId) ?: return List(count) { "" }
                decodeDictionaryStrings(payload, dp.blockOffset, count, type.id == LTYPE_BLOB)
            }
            COMP_FSST -> {
                val payload = blocks.getBlockPayload(dp.blockId) ?: return List(count) { "" }
                decodeFsstStrings(payload, dp.blockOffset, count, type.id == LTYPE_BLOB)
            }
            else -> List(count) { "" }
        }
    }

    private fun formatConstantValue(st: SegmentStats, type: DuckType): String {
        if (st.hasNull && !st.hasNoNull) return "NULL"
        if (isStringLike(type)) {
            if (st.maxStringLen == 0L) return ""
            val raw = st.minBytes ?: return ""
            val len = if (st.maxStringLen in 0..8) st.maxStringLen.toInt().coerceAtMost(raw.size) else {
                var n = raw.size
                while (n > 0 && raw[n - 1] == 0.toByte()) n--
                n
            }
            return if (type.id == LTYPE_BLOB) formatHexPreview(raw, 0, len)
            else String(raw, 0, len, StandardCharsets.UTF_8)
        }
        if (!st.hasMin) return ""
        return when (physicalCategory(type)) {
            PhysCat.BOOL -> if (st.minLong != 0L) "true" else "false"
            PhysCat.F32 -> st.minDouble.toFloat().toString()
            PhysCat.F64 -> st.minDouble.toString()
            PhysCat.I128 -> format128Value(st.minUpper, st.minLong, type)
            PhysCat.U128 -> formatUnsigned128(st.minUpper, st.minLong)
            else -> formatScalarInteger(st.minLong, type)
        }
    }

    private fun decodeUncompressedPrimitives(
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        type: DuckType
    ): List<String> {
        val out = ArrayList<String>(count)
        val cat = physicalCategory(type)
        val elemSize = cat.byteWidth
        for (i in 0 until count) {
            val pos = baseOffset + i * elemSize
            if (pos < 0 || pos + elemSize > seg.size) {
                out.add("")
                continue
            }
            out.add(readAndFormatPrimitiveAt(seg, pos, type, cat))
        }
        return out
    }

    private fun decodeUncompressedStrings(
        blocks: BlockCache,
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        asBlob: Boolean
    ): List<String> {
        if (baseOffset + 8 + count * 4 > seg.size) return List(count) { "" }
        val dictEnd = readI32LE(seg, baseOffset + 4)
        if (dictEnd < 0 || baseOffset + dictEnd > seg.size) return List(count) { "" }

        val out = ArrayList<String>(count)
        var prevAbs = 0
        for (i in 0 until count) {
            val curOffset = readI32LE(seg, baseOffset + 8 + i * 4)
            val absCur = abs(curOffset)
            val strLen = absCur - prevAbs
            prevAbs = absCur

            if (curOffset >= 0) {
                val strPos = baseOffset + dictEnd - curOffset
                if (strLen <= 0 || strPos < baseOffset || strPos + strLen > seg.size) {
                    out.add("")
                } else if (asBlob) {
                    out.add(formatHexPreview(seg, strPos, strLen))
                } else {
                    out.add(String(seg, strPos, strLen, StandardCharsets.UTF_8))
                }
            } else {
                val markerPos = baseOffset + dictEnd - absCur
                if (markerPos >= 0 && markerPos + 12 <= seg.size) {
                    val ovfBlockId = readI64LE(seg, markerPos)
                    val ovfOffset = readI32LE(seg, markerPos + 8)
                    val ovfPayload = blocks.getBlockPayload(ovfBlockId)
                    if (ovfPayload != null && ovfOffset >= 0 && ovfOffset + 4 <= ovfPayload.size) {
                        val ovfLen = readI32LE(ovfPayload, ovfOffset)
                        if (ovfLen >= 0 && ovfOffset + 4 + ovfLen <= ovfPayload.size) {
                            out.add(
                                if (asBlob) formatHexPreview(ovfPayload, ovfOffset + 4, ovfLen)
                                else String(ovfPayload, ovfOffset + 4, ovfLen, StandardCharsets.UTF_8)
                            )
                        } else {
                            out.add("")
                        }
                    } else {
                        out.add("")
                    }
                } else {
                    out.add("")
                }
            }
        }
        return out
    }

    private fun decodeRleSegment(
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        type: DuckType
    ): List<String> {
        if (baseOffset + 8 > seg.size) return List(count) { "" }
        val rleCountOffset = readI64LE(seg, baseOffset).toInt()
        if (rleCountOffset < 8 || baseOffset + rleCountOffset > seg.size) return List(count) { "" }

        val cat = physicalCategory(type)
        val elemSize = cat.byteWidth
        val out = ArrayList<String>(count)
        var entryIdx = 0
        while (out.size < count) {
            val valPos = baseOffset + 8 + entryIdx * elemSize
            val cntPos = baseOffset + rleCountOffset + entryIdx * 2
            if (valPos + elemSize > seg.size || cntPos + 2 > seg.size) break
            val runLen = readU16LE(seg, cntPos)
            if (runLen <= 0) break
            val formatted = readAndFormatPrimitiveAt(seg, valPos, type, cat)
            val take = min(runLen, count - out.size)
            for (k in 0 until take) out.add(formatted)
            entryIdx++
        }
        return out
    }

    private fun decodeBitpackingSegment(
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        vectorSize: Int,
        type: DuckType
    ): List<String> {
        if (baseOffset + 8 > seg.size) return List(count) { "" }
        val cat = physicalCategory(type)
        val elemBytes = cat.byteWidth
        if (elemBytes !in intArrayOf(1, 2, 4, 8)) return List(count) { "" }

        val groupSize = if (vectorSize > 512) vectorSize else 2048
        val metaOffset = readI64LE(seg, baseOffset).toInt()
        var metaPtr = baseOffset + metaOffset - 4
        if (metaPtr < baseOffset || metaPtr + 4 > seg.size) return List(count) { "" }

        val out = ArrayList<String>(count)
        var groupIdx = groupSize
        var mode = 0
        var frameOfRef = 0L
        var constantVal = 0L
        var deltaOffset = 0L
        var width = 0
        var groupDataPtr = 0
        val unpacked32 = LongArray(32)

        while (out.size < count) {
            if (groupIdx == groupSize) {
                if (metaPtr < baseOffset || metaPtr + 4 > seg.size) break
                groupIdx = 0
                val encoded = readI32LE(seg, metaPtr)
                metaPtr -= 4
                mode = (encoded ushr 24) and 0xFF
                val gOff = encoded and 0x00FFFFFF
                groupDataPtr = baseOffset + gOff
                if (groupDataPtr < baseOffset || groupDataPtr + elemBytes > seg.size) break

                when (mode) {
                    2 -> {
                        constantVal = readIntByWidth(seg, groupDataPtr, elemBytes, cat.isSigned)
                        groupDataPtr += elemBytes
                    }
                    3 -> {
                        frameOfRef = readIntByWidth(seg, groupDataPtr, elemBytes, cat.isSigned)
                        groupDataPtr += elemBytes
                        if (groupDataPtr + elemBytes > seg.size) break
                        constantVal = readIntByWidth(seg, groupDataPtr, elemBytes, cat.isSigned)
                        groupDataPtr += elemBytes
                    }
                    4, 5 -> {
                        frameOfRef = readIntByWidth(seg, groupDataPtr, elemBytes, cat.isSigned)
                        groupDataPtr += elemBytes
                        if (groupDataPtr + elemBytes > seg.size) break
                        width = (readIntByWidth(seg, groupDataPtr, elemBytes, false) and 0xFFL).toInt()
                        groupDataPtr += elemBytes
                        if (mode == 4) {
                            if (groupDataPtr + elemBytes > seg.size) break
                            deltaOffset = readIntByWidth(seg, groupDataPtr, elemBytes, cat.isSigned)
                            groupDataPtr += elemBytes
                        }
                    }
                    else -> break
                }
            }

            val rem = count - out.size
            when (mode) {
                2 -> {
                    val take = min(rem, groupSize - groupIdx)
                    val s = formatScalarInteger(constantVal, type)
                    for (i in 0 until take) out.add(s)
                    groupIdx += take
                }
                3 -> {
                    val take = min(rem, groupSize - groupIdx)
                    for (i in 0 until take) {
                        val raw = truncateToWidth(frameOfRef + constantVal * (groupIdx + i).toLong(), elemBytes, cat.isSigned)
                        out.add(formatScalarInteger(raw, type))
                    }
                    groupIdx += take
                }
                4, 5 -> {
                    val algOff = groupIdx and 31
                    val take = min(rem, 32 - algOff)
                    val algStart = groupDataPtr + ((groupIdx - algOff) * width) / 8
                    if (!unpack32Lsb(seg, algStart, width, unpacked32)) break
                    if (mode == 4) {
                        var acc = deltaOffset
                        for (i in 0 until take) {
                            acc = truncateToWidth(acc + unpacked32[algOff + i] + frameOfRef, elemBytes, cat.isSigned)
                            out.add(formatScalarInteger(acc, type))
                        }
                        deltaOffset = acc
                    } else {
                        for (i in 0 until take) {
                            val v = truncateToWidth(unpacked32[algOff + i] + frameOfRef, elemBytes, cat.isSigned)
                            out.add(formatScalarInteger(v, type))
                        }
                    }
                    groupIdx += take
                }
                else -> break
            }
        }
        return out
    }

    private fun decodeDictionaryStrings(
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        asBlob: Boolean
    ): List<String> {
        if (baseOffset + 20 > seg.size) return List(count) { "" }
        val dictEnd = readI32LE(seg, baseOffset + 4)
        val indexBufOffset = readI32LE(seg, baseOffset + 8)
        val indexBufCount = readI32LE(seg, baseOffset + 12)
        val bpWidth = readI32LE(seg, baseOffset + 16)
        if (dictEnd < 0 || indexBufOffset < 0 || indexBufCount <= 0 || bpWidth !in 0..32) return List(count) { "" }
        if (baseOffset + dictEnd > seg.size || baseOffset + indexBufOffset + indexBufCount * 4 > seg.size) {
            return List(count) { "" }
        }

        val rounded = (count + 31) and -32
        val selIndices = IntArray(rounded)
        val groupBuf = LongArray(32)
        val packedBase = baseOffset + 20
        for (g in 0 until rounded step 32) {
            val srcPos = packedBase + (g * bpWidth) / 8
            if (!unpack32Lsb(seg, srcPos, bpWidth, groupBuf)) break
            for (k in 0 until 32) selIndices[g + k] = groupBuf[k].toInt()
        }

        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val strNum = selIndices[i]
            if (strNum <= 0 || strNum >= indexBufCount) {
                out.add("")
                continue
            }
            val curDictOff = readI32LE(seg, baseOffset + indexBufOffset + strNum * 4)
            val prevDictOff = readI32LE(seg, baseOffset + indexBufOffset + (strNum - 1) * 4)
            val strLen = curDictOff - prevDictOff
            val strPos = baseOffset + dictEnd - curDictOff
            if (curDictOff == 0 || strLen <= 0 || strPos < baseOffset || strPos + strLen > seg.size) {
                out.add("")
            } else if (asBlob) {
                out.add(formatHexPreview(seg, strPos, strLen))
            } else {
                out.add(String(seg, strPos, strLen, StandardCharsets.UTF_8))
            }
        }
        return out
    }

    private fun decodeFsstStrings(
        seg: ByteArray,
        baseOffset: Int,
        count: Int,
        asBlob: Boolean
    ): List<String> {
        if (baseOffset + 16 > seg.size) return List(count) { "" }
        val dictEnd = readI32LE(seg, baseOffset + 4)
        val bpWidth = readI32LE(seg, baseOffset + 8)
        val symTableOffset = readI32LE(seg, baseOffset + 12)
        if (dictEnd < 0 || bpWidth !in 0..32 || symTableOffset < 16) return List(count) { "" }
        if (baseOffset + dictEnd > seg.size || baseOffset + symTableOffset + 17 > seg.size) return List(count) { "" }

        val symBase = baseOffset + symTableOffset
        val versionWord = readI64LE(seg, symBase)
        if ((versionWord ushr 32) != FSST_VERSION) {
            return List(count) { "" }
        }
        val zeroTerminated = (seg[symBase + 8].toInt() and 1)
        val lenHisto = IntArray(8) { seg[symBase + 9 + it].toInt() and 0xFF }
        if (zeroTerminated != 0 && lenHisto[0] > 0) lenHisto[0]--

        val symLen = IntArray(255)
        val symBytes = Array(255) { ByteArray(0) }
        if (zeroTerminated != 0) {
            symLen[0] = 1
            symBytes[0] = byteArrayOf(0)
        }
        var code = zeroTerminated
        var symPos = symBase + 17
        for (l in 1..8) {
            val bucket = l and 7
            val actualLen = bucket + 1
            val nSyms = lenHisto[bucket]
            for (s in 0 until nSyms) {
                if (code >= 255 || symPos + actualLen > seg.size) break
                symLen[code] = actualLen
                symBytes[code] = seg.copyOfRange(symPos, symPos + actualLen)
                symPos += actualLen
                code++
            }
        }

        val rounded = (count + 31) and -32
        val lengths = IntArray(rounded)
        val groupBuf = LongArray(32)
        val packedBase = baseOffset + 16
        for (g in 0 until rounded step 32) {
            val srcPos = packedBase + (g * bpWidth) / 8
            if (!unpack32Lsb(seg, srcPos, bpWidth, groupBuf)) break
            for (k in 0 until 32) lengths[g + k] = groupBuf[k].toInt()
        }

        val out = ArrayList<String>(count)
        val decBuf = ByteArrayOutputStream(128)
        var runningDictOffset = 0
        for (i in 0 until count) {
            val compLen = lengths[i]
            runningDictOffset += compLen
            if (compLen <= 0) {
                out.add("")
                continue
            }
            val compPos = baseOffset + dictEnd - runningDictOffset
            if (compPos < baseOffset || compPos + compLen > seg.size) {
                out.add("")
                continue
            }
            decBuf.reset()
            var p = 0
            while (p < compLen) {
                val c = seg[compPos + p].toInt() and 0xFF
                if (c == 0xFF) {
                    p++
                    if (p < compLen) decBuf.write(seg[compPos + p].toInt() and 0xFF)
                    p++
                } else {
                    val sb = symBytes[c]
                    if (sb.isNotEmpty()) decBuf.write(sb, 0, sb.size)
                    p++
                }
            }
            val decoded = decBuf.toByteArray()
            out.add(
                if (asBlob) formatHexPreview(decoded, 0, decoded.size)
                else String(decoded, StandardCharsets.UTF_8)
            )
        }
        return out
    }

    private fun unpack32Lsb(src: ByteArray, startPos: Int, width: Int, out32: LongArray): Boolean {
        if (width == 0) {
            out32.fill(0L)
            return true
        }
        val neededBytes = (32 * width + 7) ushr 3
        if (startPos < 0 || startPos + neededBytes > src.size) return false

        val mask = if (width == 64) -1L else ((1L shl width) - 1L)
        for (i in 0 until 32) {
            val bitIndex = i * width
            val byteIndex = startPos + (bitIndex ushr 3)
            val bitShift = bitIndex and 7
            var acc = 0L
            val maxBytes = min(9, src.size - byteIndex)
            val limit = min(maxBytes, ((bitShift + width + 7) ushr 3))
            if (limit <= 8) {
                for (b in 0 until limit) {
                    acc = acc or ((src[byteIndex + b].toLong() and 0xFFL) shl (b * 8))
                }
                out32[i] = (acc ushr bitShift) and mask
            } else {
                for (b in 0 until 8) {
                    acc = acc or ((src[byteIndex + b].toLong() and 0xFFL) shl (b * 8))
                }
                val ninth = src[byteIndex + 8].toLong() and 0xFFL
                val combined = (acc ushr bitShift) or (ninth shl (64 - bitShift))
                out32[i] = combined and mask
            }
        }
        return true
    }

    private enum class PhysCat(val byteWidth: Int, val isSigned: Boolean) {
        BOOL(1, false),
        I8(1, true),
        U8(1, false),
        I16(2, true),
        U16(2, false),
        I32(4, true),
        U32(4, false),
        I64(8, true),
        U64(8, false),
        I128(16, true),
        U128(16, false),
        F32(4, true),
        F64(8, true),
        INTERVAL(16, true)
    }

    private fun physicalCategory(type: DuckType): PhysCat = when (type.id) {
        LTYPE_BOOLEAN -> PhysCat.BOOL
        LTYPE_TINYINT -> PhysCat.I8
        LTYPE_UTINYINT -> PhysCat.U8
        LTYPE_SMALLINT -> PhysCat.I16
        LTYPE_USMALLINT -> PhysCat.U16
        LTYPE_INTEGER, LTYPE_DATE -> PhysCat.I32
        LTYPE_UINTEGER -> PhysCat.U32
        LTYPE_BIGINT, LTYPE_TIME, LTYPE_TIME_TZ,
        LTYPE_TIMESTAMP_SEC, LTYPE_TIMESTAMP_MS, LTYPE_TIMESTAMP,
        LTYPE_TIMESTAMP_NS, LTYPE_TIMESTAMP_TZ -> PhysCat.I64
        LTYPE_UBIGINT -> PhysCat.U64
        LTYPE_HUGEINT, LTYPE_UUID -> PhysCat.I128
        LTYPE_UHUGEINT -> PhysCat.U128
        LTYPE_FLOAT -> PhysCat.F32
        LTYPE_DOUBLE -> PhysCat.F64
        LTYPE_INTERVAL -> PhysCat.INTERVAL
        LTYPE_DECIMAL -> when {
            type.decimalWidth in 1..4 -> PhysCat.I16
            type.decimalWidth in 5..9 -> PhysCat.I32
            type.decimalWidth in 10..18 -> PhysCat.I64
            else -> PhysCat.I128
        }
        LTYPE_ENUM -> when {
            type.enumValues.size <= 255 -> PhysCat.U8
            type.enumValues.size <= 65535 -> PhysCat.U16
            else -> PhysCat.U32
        }
        else -> PhysCat.I64
    }

    private fun isStringLike(type: DuckType): Boolean =
        type.id == LTYPE_VARCHAR || type.id == LTYPE_CHAR || type.id == LTYPE_BLOB ||
            type.id == LTYPE_BIT || type.id == LTYPE_VARINT

    private fun readAndFormatPrimitiveAt(
        seg: ByteArray,
        pos: Int,
        type: DuckType,
        cat: PhysCat
    ): String {
        return when (cat) {
            PhysCat.BOOL -> if (seg[pos].toInt() != 0) "true" else "false"
            PhysCat.I8 -> formatScalarInteger(seg[pos].toLong(), type)
            PhysCat.U8 -> formatScalarInteger((seg[pos].toInt() and 0xFF).toLong(), type)
            PhysCat.I16 -> formatScalarInteger(readU16LE(seg, pos).toShort().toLong(), type)
            PhysCat.U16 -> formatScalarInteger(readU16LE(seg, pos).toLong(), type)
            PhysCat.I32 -> formatScalarInteger(readI32LE(seg, pos).toLong(), type)
            PhysCat.U32 -> formatScalarInteger(readI32LE(seg, pos).toLong() and 0xFFFFFFFFL, type)
            PhysCat.I64, PhysCat.U64 -> formatScalarInteger(readI64LE(seg, pos), type)
            PhysCat.F32 -> Float.fromBits(readI32LE(seg, pos)).toString()
            PhysCat.F64 -> Double.fromBits(readI64LE(seg, pos)).toString()
            PhysCat.I128 -> {
                val lower = readI64LE(seg, pos)
                val upper = readI64LE(seg, pos + 8)
                format128Value(upper, lower, type)
            }
            PhysCat.U128 -> {
                val lower = readI64LE(seg, pos)
                val upper = readI64LE(seg, pos + 8)
                formatUnsigned128(upper, lower)
            }
            PhysCat.INTERVAL -> {
                val months = readI32LE(seg, pos)
                val days = readI32LE(seg, pos + 4)
                val micros = readI64LE(seg, pos + 8)
                "${months}m ${days}d ${micros}us"
            }
        }
    }

    private fun formatScalarInteger(raw: Long, type: DuckType): String {
        return when (type.id) {
            LTYPE_BOOLEAN -> if (raw != 0L) "true" else "false"
            LTYPE_UTINYINT -> (raw and 0xFFL).toString()
            LTYPE_USMALLINT -> (raw and 0xFFFFL).toString()
            LTYPE_UINTEGER -> (raw and 0xFFFFFFFFL).toString()
            LTYPE_UBIGINT -> java.lang.Long.toUnsignedString(raw)
            LTYPE_DECIMAL -> BigDecimal.valueOf(raw, type.decimalScale).toPlainString()
            LTYPE_DATE -> try {
                LocalDate.ofEpochDay(raw).toString()
            } catch (_: Throwable) {
                raw.toString()
            }
            LTYPE_TIME, LTYPE_TIME_TZ -> try {
                val us = if (type.id == LTYPE_TIME_TZ) (raw ushr 24) else raw
                LocalTime.ofNanoOfDay(us.coerceIn(0L, 86_399_999_999L) * 1000L).toString()
            } catch (_: Throwable) {
                raw.toString()
            }
            LTYPE_TIMESTAMP_SEC -> Instant.ofEpochSecond(raw).toString()
            LTYPE_TIMESTAMP_MS -> Instant.ofEpochMilli(raw).toString()
            LTYPE_TIMESTAMP, LTYPE_TIMESTAMP_TZ -> {
                val secs = Math.floorDiv(raw, 1_000_000L)
                val nanos = Math.floorMod(raw, 1_000_000L) * 1000L
                Instant.ofEpochSecond(secs, nanos).atOffset(ZoneOffset.UTC).toString()
            }
            LTYPE_TIMESTAMP_NS -> {
                val secs = Math.floorDiv(raw, 1_000_000_000L)
                val nanos = Math.floorMod(raw, 1_000_000_000L)
                Instant.ofEpochSecond(secs, nanos).toString()
            }
            LTYPE_ENUM -> type.enumValues.getOrElse(raw.toInt()) { raw.toString() }
            else -> raw.toString()
        }
    }

    private fun format128Value(upper: Long, lower: Long, type: DuckType): String {
        if (type.id == LTYPE_UUID) {
            val msb = upper xor Long.MIN_VALUE
            return String.format(
                Locale.US,
                "%08x-%04x-%04x-%04x-%012x",
                (msb ushr 32) and 0xFFFFFFFFL,
                (msb ushr 16) and 0xFFFFL,
                msb and 0xFFFFL,
                (lower ushr 48) and 0xFFFFL,
                lower and 0xFFFFFFFFFFFFL
            )
        }
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(upper)
        buf.putLong(lower)
        val bi = BigInteger(buf.array())
        return if (type.id == LTYPE_DECIMAL) {
            BigDecimal(bi, type.decimalScale).toPlainString()
        } else {
            bi.toString()
        }
    }

    private fun formatUnsigned128(upper: Long, lower: Long): String {
        val buf = ByteBuffer.allocate(17)
        buf.put(0)
        buf.putLong(upper)
        buf.putLong(lower)
        return BigInteger(buf.array()).toString()
    }

    private fun readIntByWidth(seg: ByteArray, pos: Int, bytes: Int, signed: Boolean): Long = when (bytes) {
        1 -> if (signed) seg[pos].toLong() else (seg[pos].toInt() and 0xFF).toLong()
        2 -> if (signed) readU16LE(seg, pos).toShort().toLong() else readU16LE(seg, pos).toLong()
        4 -> if (signed) readI32LE(seg, pos).toLong() else (readI32LE(seg, pos).toLong() and 0xFFFFFFFFL)
        else -> readI64LE(seg, pos)
    }

    private fun truncateToWidth(value: Long, bytes: Int, signed: Boolean): Long = when (bytes) {
        1 -> if (signed) value.toByte().toLong() else (value and 0xFFL)
        2 -> if (signed) value.toShort().toLong() else (value and 0xFFFFL)
        4 -> if (signed) value.toInt().toLong() else (value and 0xFFFFFFFFL)
        else -> value
    }

    private fun typeLabel(t: DuckType): String = when (t.id) {
        LTYPE_BOOLEAN -> "BOOLEAN"
        LTYPE_TINYINT -> "TINYINT"
        LTYPE_SMALLINT -> "SMALLINT"
        LTYPE_INTEGER -> "INTEGER"
        LTYPE_BIGINT -> "BIGINT"
        LTYPE_UTINYINT -> "UTINYINT"
        LTYPE_USMALLINT -> "USMALLINT"
        LTYPE_UINTEGER -> "UINTEGER"
        LTYPE_UBIGINT -> "UBIGINT"
        LTYPE_HUGEINT -> "HUGEINT"
        LTYPE_UHUGEINT -> "UHUGEINT"
        LTYPE_FLOAT -> "FLOAT"
        LTYPE_DOUBLE -> "DOUBLE"
        LTYPE_DECIMAL -> "DECIMAL(${t.decimalWidth},${t.decimalScale})"
        LTYPE_DATE -> "DATE"
        LTYPE_TIME -> "TIME"
        LTYPE_TIMESTAMP -> "TIMESTAMP"
        LTYPE_TIMESTAMP_TZ -> "TIMESTAMPTZ"
        LTYPE_VARCHAR -> "VARCHAR"
        LTYPE_BLOB -> "BLOB"
        LTYPE_UUID -> "UUID"
        LTYPE_LIST -> "LIST"
        LTYPE_STRUCT -> "STRUCT"
        LTYPE_MAP -> "MAP"
        LTYPE_ENUM -> "ENUM"
        LTYPE_UNION -> "UNION"
        LTYPE_ARRAY -> "ARRAY"
        else -> "TYPE_${t.id}"
    }

    private fun formatHexPreview(data: ByteArray, offset: Int, length: Int): String {
        val cap = min(length, 64)
        val sb = StringBuilder(2 + cap * 2 + 4)
        sb.append("0x")
        for (i in 0 until cap) {
            val v = data[offset + i].toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        if (length > cap) sb.append("...")
        return sb.toString()
    }

    private fun readU16LE(buf: ByteArray, pos: Int): Int =
        (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)

    private fun readI32LE(buf: ByteArray, pos: Int): Int =
        (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)

    private fun readI64LE(buf: ByteArray, pos: Int): Long =
        (buf[pos].toLong() and 0xFFL) or
            ((buf[pos + 1].toLong() and 0xFFL) shl 8) or
            ((buf[pos + 2].toLong() and 0xFFL) shl 16) or
            ((buf[pos + 3].toLong() and 0xFFL) shl 24) or
            ((buf[pos + 4].toLong() and 0xFFL) shl 32) or
            ((buf[pos + 5].toLong() and 0xFFL) shl 40) or
            ((buf[pos + 6].toLong() and 0xFFL) shl 48) or
            ((buf[pos + 7].toLong() and 0xFFL) shl 56)

    private fun readCString(bytes: ByteArray, offset: Int, maxLen: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        val end = min(bytes.size, offset + maxLen)
        var i = offset
        while (i < end && bytes[i] != 0.toByte()) i++
        if (i == offset) return ""
        return String(bytes, offset, i - offset, StandardCharsets.UTF_8).trim()
    }
}
