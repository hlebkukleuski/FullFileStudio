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
 * Jet3/Jet4/ACE (.mdb/.accdb) page and header structure definitions informed by
 * Jackcess (https://jackcess.sourceforge.io/, Copyright (c) 2008-2026 Health Market Science, Inc. /
 * James Ahlborn, Apache-2.0) and MDB Tools (https://github.com/mdbtools/mdbtools,
 * Copyright (c) 2000-2026 Brian Bruns and contributors, LGPL-2.0-or-later / GPL-2.0-or-later).
 * Adapted and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.access

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Locale

fun interface AccessByteSource {
    fun read(offset: Long, length: Int): ByteArray
}

class AccessDatabaseReader(
    private val source: AccessByteSource,
    private val fileSize: Long,
    password: String? = null
) {

    class Column(
        val name: String,
        val type: Int,
        val colNum: Int,
        val varIndex: Int,
        val fixedOffset: Int,
        val length: Int,
        val isFixed: Boolean,
        val precision: Int,
        val scale: Int
    )

    class Table(val name: String, val tdefPage: Int, val columns: List<Column>, val declaredRows: Long)

    private class RowRef(val page: Int, val row: Int)

    val isJet3: Boolean
    val pageSize: Int
    private val textCharset: Charset
    val tables: List<Table>
    private val dataPagesByOwner = HashMap<Int, MutableList<Int>>()
    private val rowIndexCache = HashMap<Int, List<RowRef>>()
    private val pageCache = object : LinkedHashMap<Int, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 128
    }

    init {
        require(fileSize >= 4096) { "Access database is too small" }
        val header = source.read(0, 0x100)
        val magic = String(header, 4, 15, StandardCharsets.US_ASCII)
        require(magic.startsWith("Standard Jet DB") || magic.startsWith("Standard ACE DB")) { "Not a Jet/ACE database" }
        isJet3 = header[0x14].toInt() == 0
        pageSize = if (isJet3) 2048 else 4096
        val decHeader = decryptHeaderRegion(header)
        textCharset = if (isJet3) charsetForCodePage(le16(decHeader, 0x3C - 0x18)) else StandardCharsets.UTF_16LE
        verifyAccessPassword(decHeader, password)
        val pageCount = (fileSize / pageSize).toInt()
        for (p in 1 until pageCount) {
            val head = source.read(p.toLong() * pageSize, 8)
            if (head[0].toInt() == 1 && head[1].toInt() == 1) {
                val owner = le32(head, 4)
                dataPagesByOwner.getOrPut(owner) { ArrayList() }.add(p)
            }
        }
        val catalogColumns = readTdef(2)
        val catalog = Table("MSysObjects", 2, catalogColumns, 0)
        val found = ArrayList<Table>()
        val idCol = catalogColumns.firstOrNull { it.name.equals("Id", true) }
        val nameCol = catalogColumns.firstOrNull { it.name.equals("Name", true) }
        val typeCol = catalogColumns.firstOrNull { it.name.equals("Type", true) }
        val flagsCol = catalogColumns.firstOrNull { it.name.equals("Flags", true) }
        if (idCol != null && nameCol != null && typeCol != null) {
            for (ref in rowRefs(catalog)) {
                val values = decodeRowRaw(catalog, ref) ?: continue
                val type = (values[typeCol] as? Number)?.toInt() ?: continue
                val flags = (values[flagsCol] as? Number)?.toLong() ?: 0L
                val name = values[nameCol] as? String ?: continue
                val id = (values[idCol] as? Number)?.toInt()?.and(0x00FFFFFF) ?: continue
                if (type != 1 || flags and 0x80000002L != 0L || name.startsWith("MSys", true)) continue
                if (id <= 0 || id >= pageCount) continue
                try {
                    val tdef = page(id)
                    if (tdef[0].toInt() != 2) continue
                    val rows = le32(tdef, if (isJet3) 12 else 16).toLong() and 0xFFFFFFFFL
                    found.add(Table(name, id, readTdef(id), rows))
                } catch (_: RuntimeException) {
                }
            }
        }
        tables = found.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, p: Int): Int = le16(b, p) or (le16(b, p + 2) shl 16)

    private fun decryptHeaderRegion(header: ByteArray): ByteArray {
        val key = byteArrayOf(0xC7.toByte(), 0xDA.toByte(), 0x39, 0x6B)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % 4].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val region = header.copyOfRange(0x18, 0x18 + 126)
        var a = 0
        var b = 0
        for (k in region.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            region[k] = (region[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return region
    }

    private fun verifyAccessPassword(decHeader: ByteArray, suppliedPassword: String?) {
        val encodingKey = le32(decHeader, 0x3E - 0x18)
        if (encodingKey != 0) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: Page-encrypted Microsoft Access database (.mdb/.accdb) is not supported.")
        }

        val storedPassword = if (isJet3) {
            val start = 0x42 - 0x18
            var end = start
            while (end < start + 20 && decHeader[end].toInt() != 0) end++
            if (end > start) String(decHeader, start, end - start, textCharset) else ""
        } else {
            val cDateDouble = ByteBuffer.wrap(decHeader, 0x72 - 0x18, 8).order(ByteOrder.LITTLE_ENDIAN).double
            val mask = if (cDateDouble.isFinite()) cDateDouble.toInt() and 0xFFFF else 0
            val raw = decHeader.copyOfRange(0x42 - 0x18, 0x6A - 0x18)
            var i = 0
            while (i < 40) {
                val w = ((raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)) xor mask
                raw[i] = (w and 0xFF).toByte()
                raw[i + 1] = ((w ushr 8) and 0xFF).toByte()
                i += 4
            }
            var charLen = 0
            while (charLen < 20) {
                val lo = raw[charLen * 2].toInt() and 0xFF
                val hi = raw[charLen * 2 + 1].toInt() and 0xFF
                if (lo == 0 && hi == 0) break
                charLen++
            }
            if (charLen > 0) String(raw, 0, charLen * 2, StandardCharsets.UTF_16LE) else ""
        }

        if (storedPassword.isNotEmpty()) {
            if (suppliedPassword.isNullOrEmpty()) {
                throw SecurityException("PASSWORD_REQUIRED: Access database is password protected.")
            }
            if (suppliedPassword != storedPassword) {
                throw SecurityException("PASSWORD_REQUIRED: Incorrect password for Access database.")
            }
        }
    }

    private fun charsetForCodePage(cp: Int): Charset = try {
        when (cp) {
            0, 1252 -> Charset.forName("windows-1252")
            else -> Charset.forName("windows-$cp")
        }
    } catch (_: Exception) {
        Charset.forName("windows-1252")
    }

    private fun page(n: Int): ByteArray {
        require(n > 0 && n.toLong() * pageSize < fileSize) { "Page $n out of range" }
        synchronized(pageCache) { pageCache[n]?.let { return it } }
        val data = source.read(n.toLong() * pageSize, pageSize)
        synchronized(pageCache) { pageCache[n] = data }
        return data
    }

    private fun readTdef(first: Int): List<Column> {
        val buf = java.io.ByteArrayOutputStream()
        var p = first
        var guard = 0
        while (p != 0 && guard++ < 1000) {
            val pg = page(p)
            require(pg[0].toInt() == 2) { "Not a table definition page" }
            if (buf.size() == 0) buf.write(pg, 0, pageSize) else buf.write(pg, 8, pageSize - 8)
            p = le32(pg, 4)
        }
        val t = buf.toByteArray()
        val numColsOff = if (isJet3) 25 else 45
        val numRealIdxOff = if (isJet3) 31 else 51
        val colsStart = if (isJet3) 43 else 63
        val ridxSize = if (isJet3) 8 else 12
        val entrySize = if (isJet3) 18 else 25
        val numCols = le16(t, numColsOff)
        val numRealIdx = le32(t, numRealIdxOff)
        require(numCols in 0..4096 && numRealIdx in 0..4096) { "Corrupted table definition" }
        var pos = colsStart + numRealIdx * ridxSize
        val raw = ArrayList<IntArray>()
        for (i in 0 until numCols) {
            require(pos + entrySize <= t.size) { "Truncated column list" }
            val type = t[pos].toInt() and 0xFF
            val colNum = le16(t, pos + if (isJet3) 1 else 5)
            val varIdx = le16(t, pos + if (isJet3) 3 else 7)
            val flags = t[pos + if (isJet3) 13 else 15].toInt() and 0xFF
            val fixedOff = le16(t, pos + if (isJet3) 14 else 21)
            val len = le16(t, pos + if (isJet3) 16 else 23)
            val precision = t[pos + if (isJet3) 11 else 11].toInt() and 0xFF
            val scale = t[pos + if (isJet3) 12 else 12].toInt() and 0xFF
            raw.add(intArrayOf(type, colNum, varIdx, flags, fixedOff, len, precision, scale))
            pos += entrySize
        }
        val cols = ArrayList<Column>()
        for (r in raw) {
            val name: String
            if (isJet3) {
                val n = t[pos].toInt() and 0xFF
                name = String(t, pos + 1, n, textCharset)
                pos += 1 + n
            } else {
                val n = le16(t, pos)
                name = String(t, pos + 2, n, StandardCharsets.UTF_16LE)
                pos += 2 + n
            }
            cols.add(Column(name, r[0], r[1], r[2], r[4], r[5], r[3] and 1 != 0, r[6], r[7]))
        }
        return cols.sortedBy { it.colNum }
    }

    private fun rowBounds(pg: ByteArray, row: Int): Triple<Int, Int, Int>? {
        val countOff = if (isJet3) 8 else 12
        val offsBase = if (isJet3) 10 else 14
        val count = le16(pg, countOff)
        if (row < 0 || row >= count || offsBase + count * 2 > pageSize) return null
        val raw = le16(pg, offsBase + row * 2)
        val start = raw and 0x1FFF
        val end = if (row == 0) pageSize else le16(pg, offsBase + (row - 1) * 2) and 0x1FFF
        if (start < offsBase || start >= end || end > pageSize) return null
        return Triple(start, end, raw and 0xE000)
    }

    private fun rowRefs(table: Table): List<RowRef> {
        synchronized(rowIndexCache) { rowIndexCache[table.tdefPage]?.let { return it } }
        val pages = dataPagesByOwner[table.tdefPage].orEmpty()
        val targets = HashSet<Long>()
        val pointers = ArrayList<RowRef>()
        for (p in pages) {
            val pg = page(p)
            val count = le16(pg, if (isJet3) 8 else 12)
            for (r in 0 until count) {
                val b = rowBounds(pg, r) ?: continue
                if (b.third and 0x8000 != 0) continue
                if (b.third and 0x4000 != 0 && b.second - b.first >= 4) {
                    val ptr = le32(pg, b.first)
                    targets.add(((ptr ushr 8).toLong() shl 8) or (ptr and 0xFF).toLong())
                }
            }
        }
        for (p in pages) {
            val pg = page(p)
            val count = le16(pg, if (isJet3) 8 else 12)
            for (r in 0 until count) {
                val b = rowBounds(pg, r) ?: continue
                if (b.third and 0x8000 != 0) continue
                if (b.third and 0x4000 != 0) {
                    if (b.second - b.first >= 4) {
                        val ptr = le32(pg, b.first)
                        pointers.add(RowRef(ptr ushr 8, ptr and 0xFF))
                    }
                    continue
                }
                if (((p.toLong() shl 8) or r.toLong()) in targets) continue
                pointers.add(RowRef(p, r))
            }
        }
        synchronized(rowIndexCache) { rowIndexCache[table.tdefPage] = pointers }
        return pointers
    }

    fun rowCount(table: Table): Int = rowRefs(table).size

    fun row(table: Table, index: Int): List<String> {
        val ref = rowRefs(table)[index]
        val values = decodeRowRaw(table, ref) ?: return table.columns.map { "" }
        return table.columns.map { formatValue(it, values[it]) }
    }

    private fun decodeRowRaw(table: Table, ref: RowRef): Map<Column, Any?>? {
        val pg = page(ref.page)
        val (start, endExclusive, _) = rowBounds(pg, ref.row) ?: return null
        val end = endExclusive - 1
        val rowCols = if (isJet3) pg[start].toInt() and 0xFF else le16(pg, start)
        val bitmapSize = (rowCols + 7) / 8
        val maskStart = end - bitmapSize + 1
        if (maskStart <= start) return null
        fun present(colNum: Int): Boolean {
            if (colNum >= rowCols) return false
            return (pg[maskStart + colNum / 8].toInt() ushr (colNum % 8)) and 1 != 0
        }
        val hasVar = table.columns.any { !it.isFixed }
        val varOffsets: IntArray
        if (!hasVar) {
            varOffsets = IntArray(0)
        } else if (!isJet3) {
            val varCols = le16(pg, end - bitmapSize - 1)
            varOffsets = IntArray(varCols + 1) { le16(pg, end - bitmapSize - 3 - it * 2) }
        } else {
            val varCols = pg[end - bitmapSize].toInt() and 0xFF
            val rowLen = end - start + 1
            var jumps = (rowLen - 1) / 256
            val colPtr = end - bitmapSize - jumps - 1
            if ((colPtr - start - varCols) / 256 < jumps) jumps--
            var used = 0
            varOffsets = IntArray(varCols + 1)
            for (i in 0..varCols) {
                while (used < jumps && i == (pg[end - bitmapSize - used - 1].toInt() and 0xFF)) used++
                varOffsets[i] = (pg[colPtr - i].toInt() and 0xFF) + used * 256
            }
        }
        val dataStart = start + if (isJet3) 1 else 2
        val out = HashMap<Column, Any?>()
        for (c in table.columns) {
            if (c.type == 0x01) {
                out[c] = present(c.colNum)
                continue
            }
            if (!present(c.colNum)) {
                out[c] = null
                continue
            }
            if (c.isFixed) {
                val p = dataStart + c.fixedOffset
                if (p + c.length > maskStart) {
                    out[c] = null
                    continue
                }
                out[c] = decodeFixed(c, pg, p)
            } else {
                if (c.varIndex + 1 >= varOffsets.size) {
                    out[c] = null
                    continue
                }
                val s = start + varOffsets[c.varIndex]
                val e = start + varOffsets[c.varIndex + 1]
                if (s < dataStart || e < s || e > maskStart) {
                    out[c] = null
                    continue
                }
                out[c] = decodeVariable(c, pg.copyOfRange(s, e))
            }
        }
        return out
    }

    private fun decodeFixed(c: Column, b: ByteArray, p: Int): Any? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return when (c.type) {
            0x02 -> (b[p].toInt() and 0xFF).toLong()
            0x03 -> bb.getShort(p).toLong()
            0x04 -> bb.getInt(p).toLong()
            0x05 -> java.math.BigDecimal.valueOf(bb.getLong(p), 4)
            0x06 -> bb.getFloat(p)
            0x07 -> bb.getDouble(p)
            0x08 -> OleDate(bb.getDouble(p))
            0x0F -> Guid(b.copyOfRange(p, p + 16))
            0x10 -> decodeNumeric(b, p, c.scale)
            0x11 -> ComplexRef(bb.getInt(p))
            0x12 -> bb.getLong(p)
            else -> b.copyOfRange(p, p + c.length.coerceAtMost(b.size - p))
        }
    }

    private class OleDate(val value: Double)
    private class Guid(val bytes: ByteArray)
    private class ComplexRef(val id: Int)
    private class Blob(val size: Int)

    private fun decodeNumeric(b: ByteArray, p: Int, scale: Int): java.math.BigDecimal {
        val mag = ByteArray(16)
        for (i in 0 until 16) mag[15 - i] = b[p + 13 - (i / 4) * 4 + (i % 4)]
        var v = BigInteger(1, mag)
        if (b[p].toInt() and 0x80 != 0) v = v.negate()
        return java.math.BigDecimal(v, scale)
    }

    private fun decodeVariable(c: Column, data: ByteArray): Any? = when (c.type) {
        0x0A -> decodeText(data)
        0x0C -> readLongValue(data)?.let { decodeText(it) }
        0x0B -> readLongValue(data)?.let { Blob(it.size) } ?: Blob(0)
        0x09 -> data
        0x10 -> decodeNumeric(data, 0, c.scale)
        else -> data
    }

    private fun decodeText(data: ByteArray): String {
        if (isJet3) return String(data, textCharset)
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xFE) {
            val sb = StringBuilder(data.size)
            var compressed = true
            var i = 2
            while (i < data.size) {
                if (data[i].toInt() == 0) {
                    compressed = !compressed
                    i++
                    continue
                }
                if (compressed) {
                    sb.append((data[i].toInt() and 0xFF).toChar())
                    i++
                } else {
                    if (i + 1 >= data.size) break
                    sb.append(((data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)).toChar())
                    i += 2
                }
            }
            return sb.toString()
        }
        return String(data, 0, data.size and -2, StandardCharsets.UTF_16LE)
    }

    private fun readLongValue(header: ByteArray): ByteArray? {
        if (header.size < 12) return null
        val lenFlags = le32(header, 0)
        val length = lenFlags and 0x3FFFFFFF
        if (length < 0 || length > 256 * 1024 * 1024) throw IllegalArgumentException("Long value too large")
        return when {
            lenFlags and 0x80000000.toInt() != 0 -> header.copyOfRange(12, minOf(header.size, 12 + length))
            lenFlags and 0x40000000 != 0 -> {
                val ptr = le32(header, 4)
                val pg = page(ptr ushr 8)
                val (s, e, _) = rowBounds(pg, ptr and 0xFF) ?: return null
                pg.copyOfRange(s, minOf(e, s + length))
            }
            else -> {
                val out = java.io.ByteArrayOutputStream(length)
                var ptr = le32(header, 4)
                var guard = 0
                while (ptr != 0 && out.size() < length && guard++ < 100_000) {
                    val pg = page(ptr ushr 8)
                    val (s, e, _) = rowBounds(pg, ptr and 0xFF) ?: break
                    if (e - s < 4) break
                    val next = le32(pg, s)
                    out.write(pg, s + 4, minOf(e - s - 4, length - out.size()))
                    ptr = next
                }
                out.toByteArray()
            }
        }
    }

    private fun formatValue(c: Column, v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "True" else "False"
        is String -> v
        is Long -> v.toString()
        is Float -> v.toString()
        is Double -> v.toString()
        is java.math.BigDecimal -> v.toPlainString()
        is OleDate -> formatOleDate(v.value)
        is Guid -> formatGuid(v.bytes)
        is ComplexRef -> "#${v.id}"
        is Blob -> "[OLE ${v.size} B]"
        is ByteArray -> if (v.isEmpty()) "" else "0x" + v.take(32).joinToString("") { "%02x".format(Locale.ROOT, it) } + if (v.size > 32) "… (${v.size} B)" else ""
        else -> v.toString()
    }

    private fun formatOleDate(d: Double): String {
        if (!d.isFinite()) return ""
        val days = kotlin.math.floor(d).toLong()
        val frac = kotlin.math.abs(d - days)
        val base = LocalDateTime.of(1899, 12, 30, 0, 0)
        val totalSeconds = Math.round(frac * 86400.0)
        val dt = base.plusDays(days).plusSeconds(totalSeconds)
        return if (totalSeconds == 0L) "%04d-%02d-%02d".format(Locale.ROOT, dt.year, dt.monthValue, dt.dayOfMonth)
        else "%04d-%02d-%02d %02d:%02d:%02d".format(Locale.ROOT, dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute, dt.second)
    }

    private fun formatGuid(b: ByteArray): String {
        fun h(i: Int) = "%02X".format(Locale.ROOT, b[i])
        return "{" + h(3) + h(2) + h(1) + h(0) + "-" + h(5) + h(4) + "-" + h(7) + h(6) + "-" + h(8) + h(9) + "-" +
                (10..15).joinToString("") { h(it) } + "}"
    }
}
