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

import com.healthmarketscience.jackcess.ColumnBuilder
import com.healthmarketscience.jackcess.DataType
import com.healthmarketscience.jackcess.Database
import com.healthmarketscience.jackcess.DatabaseBuilder
import com.healthmarketscience.jackcess.TableBuilder
import com.omnireader.core.engine.impl.AccessByteSource
import com.omnireader.core.engine.impl.CleanRoomAccessReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class CleanRoomAccessReaderTest {

    private fun open(file: File): CleanRoomAccessReader {
        val raf = RandomAccessFile(file, "r")
        return CleanRoomAccessReader(AccessByteSource { offset, length ->
            val b = ByteArray(length)
            raf.seek(offset)
            raf.readFully(b)
            b
        }, raf.length())
    }

    private fun build(format: Database.FileFormat): Pair<File, List<List<String>>> {
        val file = File.createTempFile("access_", format.fileExtension)
        file.delete()
        file.deleteOnExit()
        val db = DatabaseBuilder(file).setFileFormat(format).create()
        val table = TableBuilder("Товары и заметки")
            .addColumn(ColumnBuilder("ID", DataType.LONG).setAutoNumber(true))
            .addColumn(ColumnBuilder("Name", DataType.TEXT).setLength(200))
            .addColumn(ColumnBuilder("Price", DataType.MONEY))
            .addColumn(ColumnBuilder("Qty", DataType.INT))
            .addColumn(ColumnBuilder("Flag", DataType.BOOLEAN))
            .addColumn(ColumnBuilder("Ratio", DataType.DOUBLE))
            .addColumn(ColumnBuilder("Small", DataType.FLOAT))
            .addColumn(ColumnBuilder("Tiny", DataType.BYTE))
            .addColumn(ColumnBuilder("Created", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("Amount", DataType.NUMERIC).setPrecision(18).setScale(3))
            .addColumn(ColumnBuilder("Uid", DataType.GUID))
            .addColumn(ColumnBuilder("Notes", DataType.MEMO))
            .addColumn(ColumnBuilder("Data", DataType.OLE))
            .toTable(db)
        val expected = ArrayList<List<String>>()
        for (i in 1..900) {
            val notes = when (i % 4) {
                0 -> null
                1 -> "Короткая заметка $i"
                2 -> "Средняя заметка ".repeat(150) + i
                else -> "Очень длинная заметка с латиницей Lorem ipsum ".repeat(400) + i
            }
            val name = if (i % 9 == 0) null else if (i % 2 == 0) "Item $i plain ascii" else "Товар №$i — «кавычки»"
            val uid = UUID(0x1122334455667788L + i, -0x123456789ABCDEFL - i)
            val created = LocalDateTime.of(2020, 1, 1, 0, 0).plusDays(i.toLong()).plusMinutes((i * 7 % 1440).toLong())
            val ole = if (i % 5 == 0) ByteArray(i * 13) { (it * i).toByte() } else null
            table.addRow(
                Column.AUTO_NUMBER, name, BigDecimal("${i * 3}.4567"), (i * 11 % 30000).toShort(), i % 3 == 0,
                i * 0.5, (i % 7) * 0.25f, (i % 200).toByte(), created, BigDecimal(i * 1000 - 300000).movePointLeft(3), uid, notes, ole
            )
            val f = uid.toString().uppercase()
            val guid = "{" + f + "}"
            expected.add(
                listOf(
                    i.toString(), name ?: "", BigDecimal("${i * 3}.4567").toPlainString(), (i * 11 % 30000).toString(),
                    if (i % 3 == 0) "True" else "False", (i * 0.5).toString(), ((i % 7) * 0.25f).toString(), (i % 200).toString(),
                    "%04d-%02d-%02d %02d:%02d:%02d".format(created.year, created.monthValue, created.dayOfMonth, created.hour, created.minute, created.second)
                        .let { if (created.hour == 0 && created.minute == 0) it.substring(0, 10) else it },
                    BigDecimal(i * 1000 - 300000).movePointLeft(3).setScale(3).toPlainString(), guid, notes ?: "",
                    if (ole != null) "[OLE ${ole.size} B]" else ""
                )
            )
        }
        val wide = TableBuilder("Wide")
        for (c in 0 until 120) wide.addColumn(ColumnBuilder("Column number $c with a long name", DataType.TEXT).setLength(20))
        val wideTable = wide.toTable(db)
        wideTable.addRow(*Array<Any?>(120) { "v$it" })
        db.close()
        return file to expected
    }

    private object Column {
        val AUTO_NUMBER: Any = com.healthmarketscience.jackcess.Column.AUTO_NUMBER
    }

    private fun verify(format: Database.FileFormat) {
        val (file, expected) = build(format)
        val reader = open(file)
        assertEquals(listOf("Wide", "Товары и заметки"), reader.tables.map { it.name })
        val table = reader.tables.first { it.name == "Товары и заметки" }
        assertEquals(listOf("ID", "Name", "Price", "Qty", "Flag", "Ratio", "Small", "Tiny", "Created", "Amount", "Uid", "Notes", "Data"), table.columns.map { it.name })
        assertEquals(expected.size, reader.rowCount(table))
        for (i in expected.indices) assertEquals("$format row $i", expected[i], reader.row(table, i))
        val wide = reader.tables.first { it.name == "Wide" }
        assertEquals(120, wide.columns.size)
        assertEquals("Column number 119 with a long name", wide.columns.last().name)
        assertEquals(List(120) { "v$it" }, reader.row(wide, 0))
    }

    @Test fun jet4Access2000() = verify(Database.FileFormat.V2000)
    @Test fun jet4Access2003() = verify(Database.FileFormat.V2003)
    @Test fun aceAccess2007() = verify(Database.FileFormat.V2007)
    @Test fun aceAccess2010() = verify(Database.FileFormat.V2010)
    @Test fun aceAccess2016() = verify(Database.FileFormat.V2016)

    @Test
    fun corruptedDatabasesFailCleanly() {
        val (file, _) = build(Database.FileFormat.V2003)
        val original = file.readBytes()
        val random = java.util.Random(4)
        repeat(60) {
            val copy = original.copyOf()
            repeat(20) { copy[4096 + random.nextInt(copy.size - 4096)] = random.nextInt(256).toByte() }
            try {
                val reader = CleanRoomAccessReader(AccessByteSource { o, l -> copy.copyOfRange(o.toInt(), o.toInt() + l) }, copy.size.toLong())
                for (t in reader.tables) for (r in 0 until reader.rowCount(t)) reader.row(t, r)
            } catch (_: IllegalArgumentException) {
            } catch (_: IndexOutOfBoundsException) {
            } catch (_: IllegalStateException) {
            }
        }
        assertTrue(true)
    }
}
