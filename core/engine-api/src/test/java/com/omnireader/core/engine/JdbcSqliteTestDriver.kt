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

import com.omnireader.core.engine.impl.SqliteCursor
import com.omnireader.core.engine.impl.SqliteDbConnection
import com.omnireader.core.engine.impl.SqliteFieldType

class JdbcSqliteCursor(
    private val rs: java.sql.ResultSet,
    private val stmt: java.sql.Statement
) : SqliteCursor {
    private val meta = rs.metaData
    override val columnCount: Int get() = meta.columnCount
    override val columnNames: Array<String> get() = Array(columnCount) { meta.getColumnName(it + 1) }

    override fun moveToNext(): Boolean = rs.next()
    override fun moveToFirst(): Boolean = rs.next()
    override fun getColumnIndex(name: String): Int {
        for (i in 1..columnCount) {
            if (meta.getColumnName(i).equals(name, ignoreCase = true)) return i - 1
        }
        return -1
    }
    override fun getType(colIdx: Int): Int {
        val obj = rs.getObject(colIdx + 1) ?: return SqliteFieldType.NULL
        return when (obj) {
            is Number -> if (obj is Float || obj is Double || obj is java.math.BigDecimal) SqliteFieldType.FLOAT else SqliteFieldType.INTEGER
            is ByteArray -> SqliteFieldType.BLOB
            else -> SqliteFieldType.STRING
        }
    }
    override fun getString(colIdx: Int): String? = rs.getString(colIdx + 1)
    override fun getLong(colIdx: Int): Long = rs.getLong(colIdx + 1)
    override fun getDouble(colIdx: Int): Double = rs.getDouble(colIdx + 1)
    override fun getBlob(colIdx: Int): ByteArray? = rs.getBytes(colIdx + 1)
    override fun close() {
        try { rs.close() } catch (_: Exception) {}
        try { stmt.close() } catch (_: Exception) {}
    }
}

class JdbcSqliteConnection(private val conn: java.sql.Connection) : SqliteDbConnection {
    override fun execSQL(sql: String) {
        conn.createStatement().use { stmt -> stmt.execute(sql) }
    }
    override fun query(sql: String, args: Array<String>?): SqliteCursor {
        if (args != null && args.isNotEmpty()) {
            val stmt = conn.prepareStatement(sql)
            for (i in args.indices) {
                stmt.setString(i + 1, args[i])
            }
            val rs = stmt.executeQuery()
            return JdbcSqliteCursor(rs, stmt)
        } else {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery(sql)
            return JdbcSqliteCursor(rs, stmt)
        }
    }
    override fun close() { try { conn.close() } catch (_: Exception) {} }
}