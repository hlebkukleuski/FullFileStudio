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
 * Realm (T-DB) slab allocator and B+Tree array layout informed by Realm Core
 * (https://github.com/realm/realm-core, Copyright 2016-2026 Realm Inc. / MongoDB, Inc., Apache-2.0).
 */

package com.omnireader.core.engine.impl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object CleanRoomRealmReader {
    data class ParsedTable(val name: String, val columns: List<String>, val rows: List<List<String>>)
    data class ParsedRealm(val formatVersion: Int, val activeTopRef: Long, val tables: List<ParsedTable>)

    private class NodeHeader(
        val ref: Long,
        val isInnerBptree: Boolean,
        val hasRefs: Boolean,
        val contextFlag: Boolean,
        val widthType: Int,
        val widthBits: Int,
        val size: Int
    ) {
        val dataOffset: Int get() = (ref + 8L).toInt()
    }

    fun isRealmHeader(bytes: ByteArray): Boolean {
        if (bytes.size >= 24 &&
            bytes[16] == 'T'.code.toByte() && bytes[17] == '-'.code.toByte() &&
            bytes[18] == 'D'.code.toByte() && bytes[19] == 'B'.code.toByte()
        ) return true
        return bytes.size >= 4 &&
            bytes[0] == 'T'.code.toByte() && bytes[1] == '-'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'B'.code.toByte()
    }

    fun parse(bytes: ByteArray): ParsedRealm? {
        if (bytes.size < 24) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val hasStdMagic = bytes[16] == 'T'.code.toByte() && bytes[17] == '-'.code.toByte() &&
            bytes[18] == 'D'.code.toByte() && bytes[19] == 'B'.code.toByte()
        if (!hasStdMagic) return null
        val topRef0 = bb.getLong(0)
        val topRef1 = bb.getLong(8)
        val fmt0 = bytes[20].toInt() and 0xFF
        val fmt1 = bytes[21].toInt() and 0xFF
        val flags = bytes[23].toInt() and 0xFF
        val useSecond = (flags and 1) != 0
        val activeTopRef = if (useSecond) topRef1 else topRef0
        val formatVersion = if (useSecond) fmt1 else fmt0

        val outTables = ArrayList<ParsedTable>()
        val schemaSummary = ArrayList<List<String>>()

        val topNode = readHeader(bytes, activeTopRef)
        if (topNode != null && topNode.hasRefs && topNode.size >= 2) {
            val tableNamesRef = readRef(bytes, topNode, 0)
            val tablesRef = readRef(bytes, topNode, 1)
            val tableNames = readStringArray(bytes, tableNamesRef)
            val tablesArr = readHeader(bytes, tablesRef)
            val count = minOf(tableNames.size, tablesArr?.size ?: 0)
            for (i in 0 until count) {
                val rawName = tableNames[i]
                val tblRef = if (tablesArr != null) readRef(bytes, tablesArr, i) else 0L
                val parsed = parseTable(bytes, rawName, tblRef, formatVersion)
                if (parsed != null) {
                    outTables.add(parsed)
                    schemaSummary.add(listOf(rawName, parsed.columns.size.toString(), parsed.rows.size.toString(), parsed.columns.joinToString(", ")))
                }
            }
        }

        val metaRows = ArrayList<List<String>>()
        metaRows.add(listOf("Format Version", formatVersion.toString()))
        metaRows.add(listOf("Active Slot", if (useSecond) "1" else "0"))
        metaRows.add(listOf("Active Top Ref", "0x${activeTopRef.toString(16)}"))
        metaRows.add(listOf("File Size", "${bytes.size} bytes"))
        metaRows.add(listOf("Tables Found", outTables.size.toString()))
        for (sr in schemaSummary) {
            metaRows.add(listOf("Table: ${sr[0]}", "${sr[1]} cols, ${sr[2]} rows (${sr[3]})"))
        }
        outTables.add(ParsedTable("_Realm_Metadata", listOf("Property", "Value"), metaRows))
        return ParsedRealm(formatVersion, activeTopRef, outTables)
    }

    private fun parseTable(bytes: ByteArray, tableName: String, tblRef: Long, formatVersion: Int): ParsedTable? {
        val tblNode = readHeader(bytes, tblRef) ?: return null
        if (!tblNode.hasRefs || tblNode.size < 2) return null
        val specRef = readRef(bytes, tblNode, 0)
        val specNode = readHeader(bytes, specRef) ?: return null
        if (!specNode.hasRefs || specNode.size < 2) return null

        val typesRef = readRef(bytes, specNode, 0)
        val namesRef = readRef(bytes, specNode, 1)
        val attrsRef = if (specNode.size > 2) readRef(bytes, specNode, 2) else 0L
        val keysRef = if (specNode.size > 3) readRef(bytes, specNode, 3) else 0L

        val colNames = readStringArray(bytes, namesRef)
        val colTypes = readIntArray(bytes, typesRef)
        val colAttrs = if (attrsRef > 0L) readIntArray(bytes, attrsRef) else LongArray(colNames.size)
        val colKeys = if (keysRef > 0L) readIntArray(bytes, keysRef) else LongArray(0)
        if (colNames.isEmpty()) return null

        val colCount = colNames.size
        val colData = Array(colCount) { ArrayList<String>() }

        val colStoreRef = readRef(bytes, tblNode, 1)
        val clusterRef = if (tblNode.size >= 3) readRef(bytes, tblNode, 2) else 0L
        val isClusterTree = formatVersion >= 10 && clusterRef > 0L && readHeader(bytes, clusterRef) != null

        if (isClusterTree) {
            val leafIndices = IntArray(colCount) { ci ->
                val ck = colKeys.getOrElse(ci) { 0L }
                if (ck != 0L) ((ck ushr 30) and 0xFFFFL).toInt() else ci
            }
            val effTypes = IntArray(colCount) { ci ->
                val ck = colKeys.getOrElse(ci) { 0L }
                if (ck != 0L) ((ck ushr 16) and 0x3FL).toInt() else colTypes.getOrElse(ci) { 0 }.toInt()
            }
            val effNullable = BooleanArray(colCount) { ci ->
                val ck = colKeys.getOrElse(ci) { 0L }
                val attr = if (ck != 0L) ((ck ushr 22) and 0xFFL).toInt() else colAttrs.getOrElse(ci) { 0L }.toInt()
                (attr and 0x02) != 0
            }
            walkClusterTree(bytes, clusterRef, leafIndices, effTypes, effNullable, colData, 0)
        } else if (colStoreRef > 0L) {
            val colsNode = readHeader(bytes, colStoreRef)
            if (colsNode != null && colsNode.hasRefs) {
                for (ci in 0 until minOf(colCount, colsNode.size)) {
                    val cRef = readRef(bytes, colsNode, ci)
                    val cType = colTypes.getOrElse(ci) { 0 }.toInt()
                    val nullable = (colAttrs.getOrElse(ci) { 0L }.toInt() and 0x02) != 0
                    readColumnValues(bytes, cRef, cType, nullable, colData[ci], 0)
                }
            }
        }

        val rowCount = colData.maxOfOrNull { it.size } ?: 0
        val rows = ArrayList<List<String>>(rowCount)
        for (r in 0 until minOf(rowCount, 10_000)) {
            rows.add(List(colCount) { ci -> colData[ci].getOrElse(r) { "" } })
        }
        return ParsedTable(tableName.removePrefix("class_"), colNames, rows)
    }

    private fun walkClusterTree(
        bytes: ByteArray,
        nodeRef: Long,
        leafIndices: IntArray,
        types: IntArray,
        nullable: BooleanArray,
        colData: Array<ArrayList<String>>,
        depth: Int
    ) {
        if (depth > 16 || nodeRef <= 0L) return
        val hdr = readHeader(bytes, nodeRef) ?: return
        if (!hdr.hasRefs || hdr.size == 0) return
        if (hdr.isInnerBptree) {
            val childrenRef = readRef(bytes, hdr, 0)
            val childrenNode = readHeader(bytes, childrenRef) ?: return
            for (i in 0 until childrenNode.size) {
                if (colData[0].size >= 10_000) break
                val childRef = readRef(bytes, childrenNode, i)
                if (childRef > 0L) walkClusterTree(bytes, childRef, leafIndices, types, nullable, colData, depth + 1)
            }
            return
        }
        val keyRef = readRef(bytes, hdr, 0)
        val keyCount = if (keyRef > 0L) readHeader(bytes, keyRef)?.size ?: 0 else 0
        if (keyCount <= 0) return
        for (ci in leafIndices.indices) {
            val slot = leafIndices[ci] + 1
            val before = colData[ci].size
            if (slot in 1 until hdr.size) {
                val leafRef = readRef(bytes, hdr, slot)
                if (leafRef > 0L) readLeafColumn(bytes, leafRef, types[ci], nullable[ci], keyCount, colData[ci])
            }
            while (colData[ci].size < before + keyCount) colData[ci].add("")
        }
    }

    private fun readColumnValues(bytes: ByteArray, ref: Long, colType: Int, nullable: Boolean, out: ArrayList<String>, depth: Int) {
        if (depth > 16 || ref <= 0L || out.size >= 10_000) return
        val hdr = readHeader(bytes, ref) ?: return
        if (hdr.isInnerBptree && hdr.hasRefs) {
            for (i in 0 until hdr.size) {
                val childRef = readRef(bytes, hdr, i)
                if (childRef > 0L && i < hdr.size - 1) readColumnValues(bytes, childRef, colType, nullable, out, depth + 1)
            }
            return
        }
        readLeafColumn(bytes, ref, colType, nullable, hdr.size, out)
    }

    private fun readLeafColumn(bytes: ByteArray, ref: Long, colType: Int, nullable: Boolean, expectedCount: Int, out: ArrayList<String>) {
        val hdr = readHeader(bytes, ref) ?: return
        val remaining = (10_000 - out.size).coerceAtLeast(0)
        if (remaining == 0) return
        when (colType) {
            0 -> {
                val vals = readIntArray(bytes, ref)
                if (nullable && vals.isNotEmpty()) {
                    val nullVal = vals[0]
                    for (i in 1 until minOf(vals.size, 1 + remaining)) {
                        out.add(if (vals[i] == nullVal) "NULL" else vals[i].toString())
                    }
                } else {
                    for (i in 0 until minOf(vals.size, remaining)) out.add(vals[i].toString())
                }
            }
            1 -> {
                val vals = readIntArray(bytes, ref)
                if (nullable && vals.isNotEmpty()) {
                    val nullVal = vals[0]
                    for (i in 1 until minOf(vals.size, 1 + remaining)) {
                        out.add(if (vals[i] == nullVal) "NULL" else if (vals[i] != 0L) "true" else "false")
                    }
                } else {
                    for (i in 0 until minOf(vals.size, remaining)) out.add(if (vals[i] != 0L) "true" else "false")
                }
            }
            2 -> {
                val strs = readStringArray(bytes, ref)
                for (i in 0 until minOf(strs.size, remaining)) out.add(strs[i])
            }
            4 -> {
                val blobs = readBinarySizes(bytes, ref)
                for (i in 0 until minOf(blobs.size, remaining)) out.add(blobs[i])
            }
            8 -> {
                if (hdr.hasRefs && hdr.size >= 2) {
                    val secRef = readRef(bytes, hdr, 0)
                    val nsRef = readRef(bytes, hdr, 1)
                    val secs = readIntArray(bytes, secRef)
                    val nanos = readIntArray(bytes, nsRef)
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val startIdx = if (nullable && secs.isNotEmpty()) 1 else 0
                    val nullVal = if (nullable && secs.isNotEmpty()) secs[0] else Long.MIN_VALUE
                    for (i in startIdx until minOf(secs.size, startIdx + remaining)) {
                        if (nullable && secs[i] == nullVal) out.add("NULL")
                        else out.add(fmt.format(Date(secs[i] * 1000L + (nanos.getOrElse(i) { 0L } / 1_000_000L))))
                    }
                }
            }
            9 -> {
                val count = minOf(hdr.size / 4, remaining)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val pos = hdr.dataOffset + i * 4
                    if (pos + 4 <= bytes.size) {
                        val f = bb.getFloat(pos)
                        out.add(if (f.isNaN()) "NULL" else f.toString())
                    }
                }
            }
            10 -> {
                val count = minOf(hdr.size / 8, remaining)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val pos = hdr.dataOffset + i * 8
                    if (pos + 8 <= bytes.size) {
                        val d = bb.getDouble(pos)
                        out.add(if (d.isNaN()) "NULL" else d.toString())
                    }
                }
            }
            12 -> {
                val vals = readIntArray(bytes, ref)
                for (i in 0 until minOf(vals.size, remaining)) {
                    out.add(if (vals[i] == 0L) "NULL" else "Link#${vals[i]}")
                }
            }
            else -> {
                val count = minOf(expectedCount, remaining)
                repeat(count) { out.add("[type:$colType]") }
            }
        }
    }

    private fun readHeader(bytes: ByteArray, ref: Long): NodeHeader? {
        if (ref <= 0L || (ref and 7L) != 0L || ref + 8L > bytes.size) return null
        val p = ref.toInt()
        if (bytes[p] != 0x41.toByte() || bytes[p + 1] != 0x41.toByte() || bytes[p + 2] != 0x41.toByte() || bytes[p + 3] != 0x41.toByte()) return null
        val flags = bytes[p + 4].toInt() and 0xFF
        val isInner = (flags and 0x80) != 0
        val hasRefs = (flags and 0x40) != 0
        val ctx = (flags and 0x20) != 0
        val widthType = (flags ushr 3) and 0x03
        val widthScheme = flags and 0x07
        val widthBits = when (widthType) {
            0 -> when (widthScheme) { 0 -> 0; 1 -> 1; 2 -> 2; 3 -> 4; 4 -> 8; 5 -> 16; 6 -> 32; else -> 64 }
            1 -> 8
            else -> 0
        }
        val size = ((bytes[p + 5].toInt() and 0xFF) shl 16) or ((bytes[p + 6].toInt() and 0xFF) shl 8) or (bytes[p + 7].toInt() and 0xFF)
        return NodeHeader(ref, isInner, hasRefs, ctx, widthType, widthBits, size)
    }

    private fun readElementRaw(bytes: ByteArray, hdr: NodeHeader, idx: Int): Long {
        if (idx < 0 || idx >= hdr.size || hdr.widthBits == 0) return 0L
        val base = hdr.dataOffset
        return when (hdr.widthBits) {
            1 -> {
                val p = base + (idx ushr 3)
                if (p >= bytes.size) 0L else ((bytes[p].toInt() ushr (idx and 7)) and 1).toLong()
            }
            2 -> {
                val p = base + (idx ushr 2)
                if (p >= bytes.size) 0L else ((bytes[p].toInt() ushr ((idx and 3) * 2)) and 3).toLong()
            }
            4 -> {
                val p = base + (idx ushr 1)
                if (p >= bytes.size) 0L else ((bytes[p].toInt() ushr ((idx and 1) * 4)) and 0xF).toLong()
            }
            8 -> {
                val p = base + idx
                if (p >= bytes.size) 0L else bytes[p].toLong()
            }
            16 -> {
                val p = base + idx * 2
                if (p + 2 > bytes.size) 0L else ByteBuffer.wrap(bytes, p, 2).order(ByteOrder.LITTLE_ENDIAN).short.toLong()
            }
            32 -> {
                val p = base + idx * 4
                if (p + 4 > bytes.size) 0L else ByteBuffer.wrap(bytes, p, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            }
            64 -> {
                val p = base + idx * 8
                if (p + 8 > bytes.size) 0L else ByteBuffer.wrap(bytes, p, 8).order(ByteOrder.LITTLE_ENDIAN).long
            }
            else -> 0L
        }
    }

    private fun readRef(bytes: ByteArray, hdr: NodeHeader, idx: Int): Long {
        val v = readElementRaw(bytes, hdr, idx)
        return if (v > 0L && (v and 1L) == 0L) v else 0L
    }

    private fun readIntArray(bytes: ByteArray, ref: Long): LongArray {
        val hdr = readHeader(bytes, ref) ?: return LongArray(0)
        val n = minOf(hdr.size, 65536)
        return LongArray(n) { readElementRaw(bytes, hdr, it) }
    }

    private fun readStringArray(bytes: ByteArray, ref: Long): List<String> {
        val hdr = readHeader(bytes, ref) ?: return emptyList()
        val out = ArrayList<String>()
        if (hdr.hasRefs && hdr.size >= 2) {
            val offsetsRef = readRef(bytes, hdr, 0)
            val blobRef = readRef(bytes, hdr, 1)
            val offsets = readIntArray(bytes, offsetsRef)
            val blobHdr = readHeader(bytes, blobRef) ?: return emptyList()
            val blobBase = blobHdr.dataOffset
            var prev = 0
            for (endOffL in offsets) {
                val endOff = endOffL.toInt()
                if (endOff > prev && blobBase + endOff <= bytes.size) {
                    var sLen = endOff - prev
                    if (sLen > 0 && bytes[blobBase + prev + sLen - 1].toInt() == 0) sLen--
                    out.add(String(bytes, blobBase + prev, sLen.coerceAtLeast(0), StandardCharsets.UTF_8))
                } else {
                    out.add("")
                }
                prev = endOff
                if (out.size >= 10_000) break
            }
            return out
        }
        if (hdr.widthType == 1 && hdr.size > 0) {
            val base = hdr.dataOffset
            val width = 1 shl (bytes[(ref + 4).toInt()].toInt() and 7)
            val slotWidth = when (bytes[(ref + 4).toInt()].toInt() and 7) {
                0 -> 0; 1 -> 1; 2 -> 2; 3 -> 4; 4 -> 8; 5 -> 16; 6 -> 32; else -> 64
            }
            val count = if (slotWidth > 0) hdr.size / slotWidth else 0
            val stride = if (count > 0 && base + count * slotWidth <= bytes.size) slotWidth else width
            if (stride > 0 && base + hdr.size <= bytes.size) {
                var p = base
                val end = base + hdr.size
                while (p + stride <= end && out.size < 10_000) {
                    val lastByte = bytes[p + stride - 1].toInt() and 0xFF
                    if (lastByte == 0 && stride >= 1) {
                        var len = 0
                        while (len < stride && bytes[p + len].toInt() != 0) len++
                        out.add(String(bytes, p, len, StandardCharsets.UTF_8))
                    } else if (lastByte in 1..stride) {
                        val len = (stride - 1 - lastByte).coerceAtLeast(0)
                        out.add(String(bytes, p, len, StandardCharsets.UTF_8))
                    } else {
                        out.add("")
                    }
                    p += stride
                }
            }
        }
        return out
    }

    private fun readBinarySizes(bytes: ByteArray, ref: Long): List<String> {
        val hdr = readHeader(bytes, ref) ?: return emptyList()
        val out = ArrayList<String>()
        if (hdr.hasRefs && hdr.size >= 2) {
            val offsets = readIntArray(bytes, readRef(bytes, hdr, 0))
            var prev = 0L
            for (endOff in offsets) {
                val len = (endOff - prev).coerceAtLeast(0L)
                out.add("[BLOB $len B]")
                prev = endOff
                if (out.size >= 10_000) break
            }
        }
        return out
    }
}
