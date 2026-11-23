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
 * Implements the SQLite 3 Database File Format Specification
 * (https://www.sqlite.org/fileformat2.html, dedicated to the Public Domain by D. Richard Hipp / Hwaci).
 */

package com.omnireader.core.engine.impl

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

fun interface SqlitePageSource {
    fun readAt(offset: Long, dst: ByteArray, length: Int): Int
}

class CleanRoomSqliteReader(private val source: SqlitePageSource, private val fileSize: Long) {

    class SchemaEntry(
        val type: String,
        val name: String,
        val tableName: String,
        val rootPage: Long,
        val sql: String
    )

    class ColumnDef(val name: String, val declaredType: String, val notNull: Boolean, val defaultValue: String?, val pkIndex: Int)

    class TableInfo(
        val entry: SchemaEntry,
        val columns: List<ColumnDef>,
        val rowidAliasIndex: Int,
        val withoutRowid: Boolean,
        val recordColumnOrder: IntArray
    )

    class Row(val rowid: Long, val values: Array<Any?>)

    val pageSize: Int
    private val usableSize: Int
    private val textCharset: Charset
    private val pageCount: Long
    val schema: List<SchemaEntry>
    private val tableInfoCache = HashMap<String, TableInfo>()
    private val pageCache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 256
    }

    init {
        require(fileSize >= 100) { "File is too small to be an SQLite database" }
        val header = readBytes(0, 100)
        val magic = String(header, 0, 16, StandardCharsets.US_ASCII)
        require(magic == "SQLite format 3\u0000") { "Missing SQLite header" }
        val rawPageSize = u16(header, 16)
        pageSize = if (rawPageSize == 1) 65536 else rawPageSize
        require(pageSize in 512..65536 && pageSize and (pageSize - 1) == 0) { "Invalid page size $pageSize" }
        val reserved = header[20].toInt() and 0xFF
        usableSize = pageSize - reserved
        require(usableSize >= 480) { "Invalid reserved space" }
        textCharset = when (u32(header, 56).toInt()) {
            2 -> StandardCharsets.UTF_16LE
            3 -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        val headerPages = u32(header, 28)
        val pagesFromSize = (fileSize + pageSize - 1) / pageSize
        pageCount = if (headerPages in 1..pagesFromSize) headerPages else pagesFromSize
        schema = readSchema()
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val chunk = ByteArray(length - done)
            val r = source.readAt(offset + done, chunk, length - done)
            if (r <= 0) break
            System.arraycopy(chunk, 0, out, done, r)
            done += r
        }
        require(done == length) { "Unexpected end of database file" }
        return out
    }

    private fun page(number: Long): ByteArray {
        require(number in 1..pageCount) { "Page $number is out of range" }
        synchronized(pageCache) {
            pageCache[number]?.let { return it }
        }
        val data = readBytes((number - 1) * pageSize, pageSize)
        synchronized(pageCache) { pageCache[number] = data }
        return data
    }

    private class Varint(val value: Long, val length: Int)

    private fun varint(b: ByteArray, off: Int): Varint {
        var result = 0L
        for (i in 0 until 8) {
            val byte = b[off + i].toInt() and 0xFF
            result = (result shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return Varint(result, i + 1)
        }
        result = (result shl 8) or (b[off + 8].toLong() and 0xFF)
        return Varint(result, 9)
    }

    private fun headerOffset(pageNumber: Long): Int = if (pageNumber == 1L) 100 else 0

    private fun localPayloadSize(payloadSize: Long, isTableLeaf: Boolean): Int {
        val u = usableSize
        val maxLocal = if (isTableLeaf) u - 35 else ((u - 12) * 64 / 255) - 23
        val minLocal = ((u - 12) * 32 / 255) - 23
        if (payloadSize <= maxLocal) return payloadSize.toInt()
        val k = minLocal + ((payloadSize - minLocal) % (u - 4)).toInt()
        return if (k <= maxLocal) k else minLocal
    }

    private fun readPayload(page: ByteArray, start: Int, payloadSize: Long, isTableLeaf: Boolean): ByteArray {
        require(payloadSize in 0..Int.MAX_VALUE.toLong() && payloadSize <= 1L shl 30) { "Record is too large" }
        val total = payloadSize.toInt()
        val local = localPayloadSize(payloadSize, isTableLeaf)
        val out = ByteArray(total)
        require(start + local <= page.size) { "Corrupted cell" }
        System.arraycopy(page, start, out, 0, local)
        var written = local
        var next = if (local < total) u32(page, start + local) else 0L
        var guard = 0
        while (written < total) {
            require(next in 2..pageCount && guard++ < pageCount) { "Corrupted overflow chain" }
            val overflow = page(next)
            val chunk = minOf(usableSize - 4, total - written)
            System.arraycopy(overflow, 4, out, written, chunk)
            written += chunk
            next = u32(overflow, 0)
        }
        return out
    }

    private fun decodeRecord(payload: ByteArray): Array<Any?> {
        val headerSize = varint(payload, 0)
        require(headerSize.value in 1..payload.size.toLong()) { "Corrupted record header" }
        val types = ArrayList<Long>()
        var p = headerSize.length
        while (p < headerSize.value) {
            val t = varint(payload, p)
            types.add(t.value)
            p += t.length
        }
        var body = headerSize.value.toInt()
        val values = arrayOfNulls<Any?>(types.size)
        for ((i, t) in types.withIndex()) {
            fun intOf(len: Int): Long {
                require(body + len <= payload.size) { "Corrupted record body" }
                var v = payload[body].toLong()
                for (k in 1 until len) v = (v shl 8) or (payload[body + k].toLong() and 0xFF)
                body += len
                return v
            }
            values[i] = when {
                t == 0L -> null
                t in 1L..4L -> intOf(t.toInt())
                t == 5L -> intOf(6)
                t == 6L -> intOf(8)
                t == 7L -> java.lang.Double.longBitsToDouble(intOf(8))
                t == 8L -> 0L
                t == 9L -> 1L
                t >= 12 && t % 2 == 0L -> {
                    val len = ((t - 12) / 2).toInt()
                    require(body + len <= payload.size) { "Corrupted blob" }
                    payload.copyOfRange(body, body + len).also { body += len }
                }
                t >= 13 -> {
                    val len = ((t - 13) / 2).toInt()
                    require(body + len <= payload.size) { "Corrupted text" }
                    String(payload, body, len, textCharset).also { body += len }
                }
                else -> throw IllegalArgumentException("Reserved serial type $t")
            }
        }
        return values
    }

    private fun forEachCell(rootPage: Long, visitor: (rowid: Long, values: Array<Any?>) -> Boolean) {
        walk(rootPage, 0, HashSet(), visitor)
    }

    private fun walk(
        pageNo: Long,
        depth: Int,
        visited: HashSet<Long>,
        visitor: (rowid: Long, values: Array<Any?>) -> Boolean
    ): Boolean {
        require(depth < 64 && visited.add(pageNo)) { "Corrupted b-tree structure" }
        val data = page(pageNo)
        val h = headerOffset(pageNo)
        val type = data[h].toInt() and 0xFF
        val cellCount = u16(data, h + 3)
        when (type) {
            0x0D, 0x0A -> {
                val isTable = type == 0x0D
                for (i in 0 until cellCount) {
                    var off = u16(data, h + 8 + i * 2)
                    val payloadSize = varint(data, off)
                    off += payloadSize.length
                    var rowid = 0L
                    if (isTable) {
                        val r = varint(data, off)
                        rowid = r.value
                        off += r.length
                    }
                    if (!visitor(rowid, decodeRecord(readPayload(data, off, payloadSize.value, isTable)))) return false
                }
            }
            0x05, 0x02 -> {
                val isIndex = type == 0x02
                for (i in 0 until cellCount) {
                    val off = u16(data, h + 12 + i * 2)
                    if (!walk(u32(data, off), depth + 1, visited, visitor)) return false
                    if (isIndex) {
                        val payloadSize = varint(data, off + 4)
                        val record = decodeRecord(readPayload(data, off + 4 + payloadSize.length, payloadSize.value, false))
                        if (!visitor(0L, record)) return false
                    }
                }
                if (!walk(u32(data, h + 8), depth + 1, visited, visitor)) return false
            }
            else -> throw IllegalArgumentException("Unexpected b-tree page type $type on page $pageNo")
        }
        return true
    }

    private fun readSchema(): List<SchemaEntry> {
        val out = ArrayList<SchemaEntry>()
        forEachCell(1L) { _, v ->
            if (v.size >= 5) {
                out.add(
                    SchemaEntry(
                        type = v[0] as? String ?: "",
                        name = v[1] as? String ?: "",
                        tableName = v[2] as? String ?: "",
                        rootPage = (v[3] as? Long) ?: 0L,
                        sql = v[4] as? String ?: ""
                    )
                )
            }
            true
        }
        return out
    }

    fun findEntry(name: String): SchemaEntry? =
        schema.firstOrNull { it.name == name && (it.type == "table" || it.type == "view") }
            ?: schema.firstOrNull { it.name.equals(name, ignoreCase = true) && (it.type == "table" || it.type == "view") }

    fun tableInfo(name: String): TableInfo {
        synchronized(tableInfoCache) { tableInfoCache[name]?.let { return it } }
        val entry = findEntry(name) ?: throw IllegalArgumentException("no such table: $name")
        val parsed = SqliteCreateTableParser.parse(entry.sql)
        val columns = parsed.columns
        val rowidAlias = if (parsed.withoutRowid) -1 else columns.indexOfFirst { col ->
            col.pkIndex == 1 && columns.count { it.pkIndex > 0 } == 1 &&
                    col.declaredType.trim().equals("INTEGER", ignoreCase = true) && !parsed.primaryKeyDescending
        }
        val order = if (parsed.withoutRowid) {
            val pkCols = columns.indices.filter { columns[it].pkIndex > 0 }.sortedBy { columns[it].pkIndex }
            (pkCols + columns.indices.filter { columns[it].pkIndex == 0 }).toIntArray()
        } else {
            IntArray(columns.size) { it }
        }
        val info = TableInfo(entry, columns, rowidAlias, parsed.withoutRowid, order)
        synchronized(tableInfoCache) { tableInfoCache[name] = info }
        return info
    }

    private fun materialize(info: TableInfo, rowid: Long, record: Array<Any?>): Row {
        val values = arrayOfNulls<Any?>(info.columns.size)
        for (recordIdx in info.recordColumnOrder.indices) {
            val colIdx = info.recordColumnOrder[recordIdx]
            values[colIdx] = if (recordIdx < record.size) record[recordIdx] else defaultValueOf(info.columns[colIdx])
        }
        if (info.rowidAliasIndex >= 0) values[info.rowidAliasIndex] = rowid
        for (colIdx in values.indices) {
            val v = values[colIdx]
            if (v is Long && colIdx != info.rowidAliasIndex && hasRealAffinity(info.columns[colIdx].declaredType)) {
                values[colIdx] = v.toDouble()
            }
        }
        return Row(rowid, values)
    }

    private fun hasRealAffinity(declaredType: String): Boolean {
        val t = declaredType.uppercase(Locale.ROOT)
        if (t.contains("INT")) return false
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return false
        if (t.contains("BLOB") || t.isBlank()) return false
        return t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")
    }

    private fun defaultValueOf(col: ColumnDef): Any? {
        val raw = col.defaultValue?.trim() ?: return null
        if (raw.equals("NULL", ignoreCase = true)) return null
        if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) return raw.substring(1, raw.length - 1).replace("''", "'")
        raw.toLongOrNull()?.let { return it }
        raw.toDoubleOrNull()?.let { return it }
        return raw
    }

    fun scanRows(name: String, offset: Long, limit: Int, minRowid: Long? = null): List<Row> {
        val info = tableInfo(name)
        if (info.entry.type != "table" || info.entry.rootPage <= 0) return emptyList()
        val out = ArrayList<Row>(minOf(limit, 4096).coerceAtLeast(0))
        if (limit <= 0) return out
        if (minRowid != null && !info.withoutRowid) {
            seekRowid(info.entry.rootPage, minRowid) { rowid, record ->
                out.add(materialize(info, rowid, record))
                out.size < limit
            }
            return out
        }
        var skipped = 0L
        forEachCell(info.entry.rootPage) { rowid, record ->
            if (skipped < offset) {
                skipped++
                true
            } else {
                out.add(materialize(info, rowid, record))
                out.size < limit
            }
        }
        return out
    }

    private fun seekRowid(rootPage: Long, minRowid: Long, visitor: (Long, Array<Any?>) -> Boolean) {
        val path = ArrayList<Pair<Long, Int>>()
        var current = rootPage
        var depth = 0
        while (true) {
            require(depth++ < 64) { "Corrupted b-tree depth" }
            val data = page(current)
            val h = headerOffset(current)
            val type = data[h].toInt() and 0xFF
            val cellCount = u16(data, h + 3)
            if (type == 0x0D) {
                var started = false
                for (i in 0 until cellCount) {
                    var off = u16(data, h + 8 + i * 2)
                    val payloadSize = varint(data, off)
                    off += payloadSize.length
                    val r = varint(data, off)
                    off += r.length
                    if (!started && r.value < minRowid) continue
                    started = true
                    if (!visitor(r.value, decodeRecord(readPayload(data, off, payloadSize.value, true)))) return
                }
                break
            }
            require(type == 0x05) { "Unexpected page type $type" }
            var childIdx = cellCount
            for (i in 0 until cellCount) {
                val off = u16(data, h + 12 + i * 2)
                val key = varint(data, off + 4).value
                if (minRowid <= key) {
                    childIdx = i
                    break
                }
            }
            path.add(current to childIdx)
            current = if (childIdx < cellCount) u32(data, u16(data, h + 12 + childIdx * 2)) else u32(data, h + 8)
        }
        while (path.isNotEmpty()) {
            val (pageNo, idx) = path.removeAt(path.lastIndex)
            val data = page(pageNo)
            val h = headerOffset(pageNo)
            val cellCount = u16(data, h + 3)
            for (next in idx + 1..cellCount) {
                val child = if (next < cellCount) u32(data, u16(data, h + 12 + next * 2)) else u32(data, h + 8)
                var stop = false
                forEachCell(child) { rowid, record ->
                    val go = visitor(rowid, record)
                    if (!go) stop = true
                    go
                }
                if (stop) return
            }
        }
    }

    fun countRows(name: String): Long {
        val info = tableInfo(name)
        if (info.entry.type != "table" || info.entry.rootPage <= 0) return 0L
        return countCells(info.entry.rootPage, 0)
    }

    private fun countCells(pageNo: Long, depth: Int): Long {
        require(depth < 64) { "Corrupted b-tree depth" }
        val data = page(pageNo)
        val h = headerOffset(pageNo)
        val type = data[h].toInt() and 0xFF
        val cellCount = u16(data, h + 3)
        return when (type) {
            0x0D, 0x0A -> cellCount.toLong()
            0x05, 0x02 -> {
                var total = if (type == 0x02) cellCount.toLong() else 0L
                for (i in 0 until cellCount) total += countCells(u32(data, u16(data, h + 12 + i * 2)), depth + 1)
                total + countCells(u32(data, h + 8), depth + 1)
            }
            else -> throw IllegalArgumentException("Unexpected b-tree page type $type")
        }
    }
}

object SqliteCreateTableParser {

    class Parsed(val columns: List<CleanRoomSqliteReader.ColumnDef>, val withoutRowid: Boolean, val primaryKeyDescending: Boolean)

    private val constraintKeywords = setOf("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")

    fun parse(sql: String): Parsed {
        val open = sql.indexOf('(')
        if (open < 0) return Parsed(emptyList(), false, false)
        var depth = 0
        var close = -1
        var i = open
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) i++ else quote = null
                }
            } else when (c) {
                '\'', '"', '`' -> quote = c
                '[' -> quote = ']'
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
            i++
        }
        if (close < 0) return Parsed(emptyList(), false, false)
        val tail = sql.substring(close + 1).uppercase(Locale.ROOT)
        val withoutRowid = Regex("""WITHOUT\s+ROWID""").containsMatchIn(tail)
        val parts = splitTopLevel(sql.substring(open + 1, close))
        val columns = ArrayList<MutableColumn>()
        var tablePkNames: List<String>? = null
        var pkDesc = false
        for (part in parts) {
            val tokens = tokenize(part)
            if (tokens.isEmpty()) continue
            val first = tokens[0].uppercase(Locale.ROOT)
            if (first in constraintKeywords && !isQuotedToken(part)) {
                val upper = part.uppercase(Locale.ROOT)
                val pkIdx = upper.indexOf("PRIMARY")
                if (pkIdx >= 0 && Regex("""PRIMARY\s+KEY""").containsMatchIn(upper)) {
                    val inner = part.substring(part.indexOf('(', pkIdx) + 1, part.lastIndexOf(')'))
                    tablePkNames = splitTopLevel(inner).map { unquote(tokenize(it).firstOrNull() ?: "") }
                    if (Regex("""\bDESC\b""").containsMatchIn(inner.uppercase(Locale.ROOT))) pkDesc = true
                }
                continue
            }
            val name = unquote(tokens[0])
            val typeTokens = ArrayList<String>()
            var j = 1
            val stopWords = setOf("CONSTRAINT", "PRIMARY", "NOT", "NULL", "UNIQUE", "CHECK", "DEFAULT", "COLLATE", "REFERENCES", "GENERATED", "AS")
            while (j < tokens.size && tokens[j].uppercase(Locale.ROOT) !in stopWords) {
                typeTokens.add(tokens[j])
                j++
            }
            val upperRest = tokens.drop(j).joinToString(" ") { it.uppercase(Locale.ROOT) }
            val col = MutableColumn(name, typeTokens.joinToString(" "))
            col.notNull = upperRest.contains("NOT NULL")
            if (Regex("""PRIMARY KEY""").containsMatchIn(upperRest)) {
                col.pk = true
                if (Regex("""PRIMARY KEY DESC""").containsMatchIn(upperRest)) pkDesc = true
            }
            val defIdx = tokens.indexOfFirst { it.equals("DEFAULT", ignoreCase = true) }
            if (defIdx >= 0 && defIdx + 1 < tokens.size) col.defaultValue = tokens[defIdx + 1]
            columns.add(col)
        }
        if (tablePkNames != null) {
            for ((order, pkName) in tablePkNames.withIndex()) {
                columns.firstOrNull { it.name.equals(pkName, ignoreCase = true) }?.pkOrder = order + 1
            }
        } else {
            var order = 0
            for (c in columns) if (c.pk) c.pkOrder = ++order
        }
        return Parsed(
            columns.map { CleanRoomSqliteReader.ColumnDef(it.name, it.type, it.notNull, it.defaultValue, it.pkOrder) },
            withoutRowid,
            pkDesc
        )
    }

    private class MutableColumn(val name: String, val type: String) {
        var notNull = false
        var pk = false
        var pkOrder = 0
        var defaultValue: String? = null
    }

    private fun isQuotedToken(part: String): Boolean {
        val t = part.trimStart()
        return t.startsWith("\"") || t.startsWith("`") || t.startsWith("[") || t.startsWith("'")
    }

    fun unquote(token: String): String {
        if (token.length >= 2) {
            val f = token.first()
            val l = token.last()
            if ((f == '"' && l == '"') || (f == '`' && l == '`') || (f == '\'' && l == '\'')) {
                return token.substring(1, token.length - 1).replace("$f$f", "$f")
            }
            if (f == '[' && l == ']') return token.substring(1, token.length - 1)
        }
        return token
    }

    fun splitTopLevel(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (quote != null) {
                sb.append(c)
                if (c == quote) {
                    if (i + 1 < body.length && body[i + 1] == quote && quote != ']') {
                        sb.append(body[i + 1])
                        i++
                    } else quote = null
                }
            } else when (c) {
                '\'', '"', '`' -> { quote = c; sb.append(c) }
                '[' -> { quote = ']'; sb.append(c) }
                '(' -> { depth++; sb.append(c) }
                ')' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out
    }

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val endChar = if (c == '[') ']' else c
                    var j = i + 1
                    while (j < text.length) {
                        if (text[j] == endChar) {
                            if (endChar != ']' && j + 1 < text.length && text[j + 1] == endChar) {
                                j += 2
                                continue
                            }
                            break
                        }
                        j++
                    }
                    out.add(text.substring(i, minOf(j + 1, text.length)))
                    i = j + 1
                }
                c == '(' -> {
                    var depth = 0
                    var j = i
                    while (j < text.length) {
                        if (text[j] == '(') depth++
                        if (text[j] == ')') {
                            depth--
                            if (depth == 0) break
                        }
                        j++
                    }
                    out.add(text.substring(i, minOf(j + 1, text.length)))
                    i = j + 1
                }
                else -> {
                    var j = i
                    while (j < text.length && !text[j].isWhitespace() && text[j] != '(' && text[j] != ',') j++
                    if (j == i) j++
                    out.add(text.substring(i, j))
                    i = j
                }
            }
        }
        return out
    }
}

class CleanRoomSqliteCursor(
    override val columnNames: Array<String>,
    private val rows: List<Array<Any?>>
) : SqliteCursor {
    private var position = -1

    override fun moveToNext(): Boolean {
        if (position + 1 >= rows.size) {
            position = rows.size
            return false
        }
        position++
        return true
    }

    override fun moveToFirst(): Boolean {
        position = 0
        return rows.isNotEmpty()
    }

    override val columnCount: Int get() = columnNames.size

    override fun getColumnIndex(name: String): Int = columnNames.indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun value(colIdx: Int): Any? = rows.getOrNull(position)?.getOrNull(colIdx)

    override fun getType(colIdx: Int): Int = when (value(colIdx)) {
        null -> SqliteFieldType.NULL
        is Long -> SqliteFieldType.INTEGER
        is Double -> SqliteFieldType.FLOAT
        is ByteArray -> SqliteFieldType.BLOB
        else -> SqliteFieldType.STRING
    }

    override fun getString(colIdx: Int): String? = when (val v = value(colIdx)) {
        null -> null
        is ByteArray -> String(v, Charsets.UTF_8)
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) "${v.toLong()}.0" else v.toString()
        else -> v.toString()
    }

    override fun getLong(colIdx: Int): Long = when (val v = value(colIdx)) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    override fun getDouble(colIdx: Int): Double = when (val v = value(colIdx)) {
        is Long -> v.toDouble()
        is Double -> v
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    override fun getBlob(colIdx: Int): ByteArray? = when (val v = value(colIdx)) {
        null -> null
        is ByteArray -> v
        else -> v.toString().toByteArray(Charsets.UTF_8)
    }

    override fun close() {}
}

class CleanRoomSqliteConnection(private val reader: CleanRoomSqliteReader) : SqliteDbConnection {

    private val identifier = """("(?:[^"]|"")*"|`[^`]*`|\[[^\]]*\]|[A-Za-z_][A-Za-z0-9_$]*)"""
    private val listObjects = Regex("""(?is)^\s*SELECT\s+name\s+FROM\s+sqlite_master\s+WHERE\s+type\s+IN\s*\('table',\s*'view'\).*$""")
    private val objectType = Regex("""(?is)^\s*SELECT\s+type\s+FROM\s+sqlite_master\s+WHERE\s+name\s*=\s*\?.*$""")
    private val tableInfo = Regex("""(?is)^\s*PRAGMA\s+table_info\s*\(\s*$identifier\s*\)\s*;?\s*$""")
    private val countAll = Regex("""(?is)^\s*SELECT\s+COUNT\(\*\)\s+FROM\s+$identifier\s*;?\s*$""")
    private val selectRowid = Regex("""(?is)^\s*SELECT\s+\*\s+FROM\s+$identifier\s+WHERE\s+_rowid_\s*>=\s*(-?\d+)\s+LIMIT\s+(\d+)\s*;?\s*$""")
    private val selectLimitOffset = Regex("""(?is)^\s*SELECT\s+\*\s+FROM\s+$identifier\s+LIMIT\s+(\d+)(?:\s+OFFSET\s+(\d+))?\s*;?\s*$""")

    override fun execSQL(sql: String) {
        if (!sql.trim().startsWith("PRAGMA", ignoreCase = true)) {
            throw UnsupportedOperationException("Read-only clean-room SQLite reader")
        }
    }

    private fun objectName(token: String): String = SqliteCreateTableParser.unquote(token)

    private fun columnsOf(name: String): Array<String> {
        val info = reader.tableInfo(name)
        if (info.columns.isNotEmpty()) return info.columns.map { it.name }.toTypedArray()
        return arrayOf("sql")
    }

    override fun query(sql: String, args: Array<String>?): SqliteCursor {
        listObjects.matchEntire(sql)?.let {
            val names = reader.schema
                .filter { (it.type == "table" || it.type == "view") && !it.name.startsWith("sqlite_") && it.name != "android_metadata" }
                .sortedWith(compareBy({ it.type }, { it.name }))
                .map { arrayOf<Any?>(it.name) }
            return CleanRoomSqliteCursor(arrayOf("name"), names)
        }
        objectType.matchEntire(sql)?.let {
            val target = args?.firstOrNull() ?: ""
            val entry = reader.findEntry(target)
            return CleanRoomSqliteCursor(arrayOf("type"), if (entry != null) listOf(arrayOf<Any?>(entry.type)) else emptyList())
        }
        tableInfo.matchEntire(sql)?.let { m ->
            val info = reader.tableInfo(objectName(m.groupValues[1]))
            val rows = info.columns.mapIndexed { idx, c ->
                arrayOf<Any?>(idx.toLong(), c.name, c.declaredType, if (c.notNull) 1L else 0L, c.defaultValue, c.pkIndex.toLong())
            }
            return CleanRoomSqliteCursor(arrayOf("cid", "name", "type", "notnull", "dflt_value", "pk"), rows)
        }
        countAll.matchEntire(sql)?.let { m ->
            return CleanRoomSqliteCursor(arrayOf("COUNT(*)"), listOf(arrayOf<Any?>(reader.countRows(objectName(m.groupValues[1])))))
        }
        selectRowid.matchEntire(sql)?.let { m ->
            val name = objectName(m.groupValues[1])
            val info = reader.tableInfo(name)
            if (info.withoutRowid) throw UnsupportedOperationException("no such column: _rowid_")
            val rows = reader.scanRows(name, 0, m.groupValues[3].toInt(), minRowid = m.groupValues[2].toLong())
            return CleanRoomSqliteCursor(columnsOf(name), rows.map { it.values })
        }
        selectLimitOffset.matchEntire(sql)?.let { m ->
            val name = objectName(m.groupValues[1])
            val limit = m.groupValues[2].toInt()
            val offset = m.groupValues[3].toLongOrNull() ?: 0L
            val rows = reader.scanRows(name, offset, limit)
            return CleanRoomSqliteCursor(columnsOf(name), rows.map { it.values })
        }
        throw UnsupportedOperationException("Unsupported statement for clean-room SQLite reader: $sql")
    }

    override fun close() {}
}
