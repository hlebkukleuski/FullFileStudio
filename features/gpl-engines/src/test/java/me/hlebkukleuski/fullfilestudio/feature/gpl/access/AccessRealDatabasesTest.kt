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

package me.hlebkukleuski.fullfilestudio.feature.gpl.access

import com.healthmarketscience.jackcess.DataType
import com.healthmarketscience.jackcess.DatabaseBuilder
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRealDatabasesTest {

    private val dir = File(javaClass.classLoader!!.getResource("access/testV1997.mdb")!!.toURI()).parentFile!!

    private fun expectedText(type: DataType, v: Any?): String = when (v) {
        null -> if (type == DataType.BOOLEAN) "False" else ""
        is Boolean -> if (v) "True" else "False"
        is BigDecimal -> v.toPlainString()
        is LocalDateTime -> {
            val base = "%04d-%02d-%02d".format(v.year, v.monthValue, v.dayOfMonth)
            if (v.hour == 0 && v.minute == 0 && v.second == 0) base else base + " %02d:%02d:%02d".format(v.hour, v.minute, v.second)
        }
        is ByteArray -> if (type == DataType.OLE) "[OLE ${v.size} B]" else "0x" + v.take(32).joinToString("") { "%02x".format(it) } + if (v.size > 32) "… (${v.size} B)" else ""
        else -> v.toString()
    }

    @Test
    fun matchesJackcessOnRealAccessFiles() {
        val files = dir.listFiles { f -> f.name.endsWith(".mdb") || f.name.endsWith(".accdb") }!!.sortedBy { it.name }
        assertTrue(files.size >= 8)
        var cells = 0
        for (file in files) {
            val bytes = file.readBytes()
            val reader = AccessDatabaseReader(AccessByteSource { o, l -> bytes.copyOfRange(o.toInt(), o.toInt() + l) }, bytes.size.toLong())
            DatabaseBuilder(file).setReadOnly(true).open().use { db ->
                val names = db.tableNames.sorted()
                assertEquals(file.name, names.map { it.lowercase() }.sorted(), reader.tables.map { it.name.lowercase() }.sorted())
                for (name in names) {
                    val jt = db.getTable(name)
                    val ours = reader.tables.first { it.name.equals(name, ignoreCase = true) }
                    val expectedRows = jt.toList()
                    assertEquals("${file.name}/$name rows", expectedRows.size, reader.rowCount(ours))
                    for ((r, row) in expectedRows.withIndex()) {
                        val actual = reader.row(ours, r)
                        for ((ci, col) in ours.columns.withIndex()) {
                            val jcol = jt.getColumn(col.name)
                            val v = row.get(col.name)
                            val exp = expectedText(jcol.type, v)
                            val act = actual[ci]
                            if ((jcol.type == DataType.FLOAT || jcol.type == DataType.DOUBLE) && v != null) {
                                assertEquals("${file.name}/$name[$r].${col.name}", (v as Number).toDouble(), act.toDouble(), 1e-6)
                            } else {
                                assertEquals("${file.name}/$name[$r].${col.name}", exp, act)
                            }
                            cells++
                        }
                    }
                }
            }
        }
        assertTrue("compared $cells cells", cells > 200)
    }
}
