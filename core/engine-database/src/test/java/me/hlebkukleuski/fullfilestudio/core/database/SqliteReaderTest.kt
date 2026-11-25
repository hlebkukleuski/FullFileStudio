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

package me.hlebkukleuski.fullfilestudio.core.database

import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteReaderTest {

    private fun newDb(pageSize: Int = 4096, encoding: String = "UTF-8", setup: (Connection) -> Unit): File {
        val file = File.createTempFile("sqlite_reader_", ".db")
        file.delete()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA page_size = $pageSize")
                st.execute("PRAGMA encoding = '$encoding'")
                st.execute("PRAGMA journal_mode = DELETE")
            }
            c.autoCommit = false
            setup(c)
            c.commit()
        }
        file.deleteOnExit()
        return file
    }

    private fun open(file: File): SqliteReader {
        val raf = RandomAccessFile(file, "r")
        return SqliteReader(SqlitePageSource { offset, dst, length ->
            raf.seek(offset)
            raf.read(dst, 0, length)
        }, raf.length())
    }

    private fun jdbcRows(file: File, sql: String): List<List<Any?>> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    val out = ArrayList<List<Any?>>()
                    while (rs.next()) {
                        out.add((1..n).map { i ->
                            rs.getObject(i)
                            when (rs.metaData.getColumnType(i)) {
                                else -> when (val v = rs.getObject(i)) {
                                    is Int -> v.toLong()
                                    is Short -> v.toLong()
                                    is Float -> v.toDouble()
                                    else -> v
                                }
                            }
                        })
                    }
                    out
                }
            }
        }

    private fun normalize(v: Any?): Any? = when (v) {
        is Int -> v.toLong()
        is ByteArray -> v.toList()
        else -> v
    }

    private fun assertSameRows(expected: List<List<Any?>>, actual: List<Array<Any?>>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("row $i", expected[i].map { normalize(it) }, actual[i].map { normalize(it) })
        }
    }

    private fun exec(c: Connection, sql: String) = c.createStatement().use { it.execute(sql) }

    @Test
    fun readsMultiLevelTableWithRowidAliasAndAllValueTypes() {
        val db = newDb(pageSize = 512) { c ->
            exec(c, "CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL, price REAL, qty INT, data BLOB, note TEXT)")
            c.prepareStatement("INSERT INTO items(id, name, price, qty, data, note) VALUES(?,?,?,?,?,?)").use { ps ->
                for (i in 1..3000) {
                    ps.setLong(1, i * 3L)
                    ps.setString(2, "имя $i")
                    ps.setDouble(3, i * 1.25)
                    ps.setLong(4, if (i % 7 == 0) -i.toLong() * 100000000000L else i.toLong())
                    if (i % 5 == 0) ps.setNull(5, Types.BLOB) else ps.setBytes(5, ByteArray(i % 40) { (it * i).toByte() })
                    if (i % 11 == 0) ps.setNull(6, Types.VARCHAR) else ps.setString(6, "x".repeat(i % 900))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        val reader = open(db)
        assertEquals(3000L, reader.countRows("items"))
        val rows = reader.scanRows("items", 0, 10_000)
        assertSameRows(jdbcRows(db, "SELECT * FROM items ORDER BY rowid"), rows.map { it.values })
        val page = reader.scanRows("items", 1500, 25)
        assertSameRows(jdbcRows(db, "SELECT * FROM items ORDER BY rowid LIMIT 25 OFFSET 1500"), page.map { it.values })
        val seek = reader.scanRows("items", 0, 40, minRowid = 4000)
        assertSameRows(jdbcRows(db, "SELECT * FROM items WHERE rowid >= 4000 ORDER BY rowid LIMIT 40"), seek.map { it.values })
        assertEquals(listOf("id", "name", "price", "qty", "data", "note"), reader.tableInfo("items").columns.map { it.name })
    }

    @Test
    fun followsOverflowPagesForLargeTextAndBlobs() {
        val big = "Длинный текст ".repeat(20_000)
        val blob = ByteArray(300_000) { (it % 251).toByte() }
        val db = newDb { c ->
            exec(c, "CREATE TABLE big(t TEXT, b BLOB)")
            c.prepareStatement("INSERT INTO big VALUES(?, ?)").use { ps ->
                ps.setString(1, big); ps.setBytes(2, blob); ps.executeUpdate()
            }
        }
        val row = open(db).scanRows("big", 0, 1).single()
        assertEquals(big, row.values[0])
        assertArrayEquals(blob, row.values[1] as ByteArray)
    }

    @Test
    fun decodesUtf16DatabasesAndLargePages() {
        for (encoding in listOf("UTF-16le", "UTF-16be")) {
            val db = newDb(pageSize = 65536, encoding = encoding) { c ->
                exec(c, "CREATE TABLE \"weird \"\"name\"\" таблица\"([col one] TEXT, `col two` INTEGER)")
                c.prepareStatement("INSERT INTO \"weird \"\"name\"\" таблица\" VALUES(?, ?)").use { ps ->
                    for (i in 0 until 500) { ps.setString(1, "строка $i ✓"); ps.setLong(2, i.toLong()); ps.addBatch() }
                    ps.executeBatch()
                }
            }
            val reader = open(db)
            val table = "weird \"name\" таблица"
            assertEquals(listOf("col one", "col two"), reader.tableInfo(table).columns.map { it.name })
            assertSameRows(jdbcRows(db, "SELECT * FROM \"weird \"\"name\"\" таблица\""), reader.scanRows(table, 0, 1000).map { it.values })
        }
    }

    @Test
    fun readsWithoutRowidTablesAndAddedColumnsAndViews() {
        val db = newDb(pageSize = 1024) { c ->
            exec(c, "CREATE TABLE kv(region TEXT, code INTEGER, label TEXT, PRIMARY KEY(code, region)) WITHOUT ROWID")
            c.prepareStatement("INSERT INTO kv VALUES(?,?,?)").use { ps ->
                for (i in 0 until 2000) { ps.setString(1, "r${i % 13}"); ps.setLong(2, i.toLong() / 13); ps.setString(3, "label $i"); ps.addBatch() }
                ps.executeBatch()
            }
            exec(c, "CREATE TABLE people(name TEXT)")
            exec(c, "INSERT INTO people VALUES('Анна'), ('Bob')")
            exec(c, "ALTER TABLE people ADD COLUMN age INTEGER DEFAULT 42")
            exec(c, "ALTER TABLE people ADD COLUMN city TEXT DEFAULT 'Минск'")
            exec(c, "INSERT INTO people VALUES('Карл', 7, 'Wien')")
            exec(c, "CREATE VIEW adults AS SELECT name FROM people WHERE age > 18")
        }
        val reader = open(db)
        assertEquals(2000L, reader.countRows("kv"))
        assertSameRows(jdbcRows(db, "SELECT region, code, label FROM kv ORDER BY code, region"), reader.scanRows("kv", 0, 5000).map { it.values })
        assertSameRows(jdbcRows(db, "SELECT * FROM people ORDER BY rowid"), reader.scanRows("people", 0, 10).map { it.values })

        val conn = SqliteConnection(reader)
        val names = ArrayList<String>()
        conn.query("SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT GLOB 'sqlite_*' AND name != 'android_metadata' ORDER BY type ASC, name ASC;").use { cur ->
            while (cur.moveToNext()) names.add(cur.getString(0)!!)
        }
        assertEquals(listOf("kv", "people", "adults"), names)
        conn.query("SELECT type FROM sqlite_master WHERE name = ? AND type IN ('table', 'view');", arrayOf("adults")).use { cur ->
            assertTrue(cur.moveToFirst()); assertEquals("view", cur.getString(0))
        }
        val cols = ArrayList<String>()
        conn.query("PRAGMA table_info(\"people\");").use { cur ->
            val idx = cur.getColumnIndex("name")
            while (cur.moveToNext()) cols.add(cur.getString(idx)!!)
        }
        assertEquals(listOf("name", "age", "city"), cols)
        conn.query("SELECT COUNT(*) FROM \"kv\";").use { cur -> assertTrue(cur.moveToFirst()); assertEquals(2000L, cur.getLong(0)) }
        conn.query("SELECT * FROM \"people\" LIMIT 2 OFFSET 1;").use { cur ->
            assertTrue(cur.moveToNext()); assertEquals("Bob", cur.getString(0)); assertEquals(42L, cur.getLong(1)); assertEquals("Минск", cur.getString(2))
            assertTrue(cur.moveToNext()); assertEquals("Карл", cur.getString(0)); assertEquals("Wien", cur.getString(2))
        }
    }

    @Test
    fun rejectsCorruptedFilesWithoutHanging() {
        val db = newDb { c ->
            exec(c, "CREATE TABLE t(a TEXT)")
            exec(c, "INSERT INTO t VALUES('" + "z".repeat(10000) + "')")
        }
        val bytes = db.readBytes()
        val random = java.util.Random(7)
        for (attempt in 0 until 300) {
            val copy = bytes.copyOf()
            repeat(1 + random.nextInt(8)) { copy[100 + random.nextInt(copy.size - 100)] = random.nextInt(256).toByte() }
            try {
                val reader = SqliteReader(SqlitePageSource { offset, dst, length ->
                    if (offset >= copy.size) -1 else {
                        val n = minOf(length.toLong(), copy.size - offset).toInt()
                        System.arraycopy(copy, offset.toInt(), dst, 0, n); n
                    }
                }, copy.size.toLong())
                for (e in reader.schema) if (e.type == "table") { reader.countRows(e.name); reader.scanRows(e.name, 0, 100) }
            } catch (_: IllegalArgumentException) {
            } catch (_: UnsupportedOperationException) {
            } catch (_: IndexOutOfBoundsException) {
            }
        }
    }
}
