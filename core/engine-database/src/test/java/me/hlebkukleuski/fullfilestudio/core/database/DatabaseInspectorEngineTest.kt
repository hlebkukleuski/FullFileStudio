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
import java.io.FileOutputStream
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseInspectorEngineTest {

    @Test
    fun testDatabaseInspectorEngine_CanHandleExtensions() {
        val engine = DatabaseInspectorEngineImpl()
        assertTrue(engine.canHandle(ByteArray(0), "db"))
        assertTrue(engine.canHandle(ByteArray(0), "sqlite"))
        assertTrue(engine.canHandle(ByteArray(0), "sqlite3"))
        assertTrue(engine.canHandle(ByteArray(0), "DB"))
    }

    @Test
    fun testRealSqliteDatabase_FullLifecycleAndSchemaFiltering() {
        val dbFile = File.createTempFile("test_real_sqlite_lifecycle", ".db").apply { deleteOnExit() }
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE \"Users 'Table'\" (\"id\" INTEGER, \"Name Col\" TEXT, \"Score\" REAL, \"Avatar\" BLOB);")
                stmt.execute("CREATE VIEW \"Active Users View\" AS SELECT \"id\", \"Name Col\" FROM \"Users 'Table'\";")

                stmt.execute("INSERT INTO \"Users 'Table'\" VALUES (1, 'Alice', 99.5, X'01020304');")
                stmt.execute("INSERT INTO \"Users 'Table'\" VALUES (2, NULL, NULL, NULL);")
                stmt.execute("INSERT INTO \"Users 'Table'\" VALUES (3, '', 0.0, X'');")

                stmt.execute("CREATE TABLE \"Empty Table\" (\"col1\" TEXT, \"col2\" INTEGER);")
                stmt.execute("CREATE INDEX \"idx_users_score\" ON \"Users 'Table'\" (\"Score\");")
                stmt.execute("ANALYZE;")

                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1';")
                assertTrue("System table sqlite_stat1 should exist in sqlite_master", rs.next())
                rs.close()
            }
        }

        val rawConn = DriverManager.getConnection(jdbcUrl)
        val sqliteConn = JdbcSqliteConnection(rawConn)

        val session = SqliteDatabaseSession(null, dbFile.parentFile, sqliteConn)
        try {
            session.waitForMetaForTesting()
            val sheets = session.getSheetNames()
            assertEquals(3, sheets.size)
            assertTrue(sheets.contains("Users 'Table'"))
            assertTrue(sheets.contains("Active Users View"))
            assertTrue(sheets.contains("Empty Table"))
            assertFalse(sheets.contains("sqlite_stat1"))

            session.setActiveSheetIndex(sheets.indexOf("Users 'Table'"))
            session.waitForMetaForTesting()
            assertEquals("1", session.getCellText(0, 0))
            assertEquals("Alice", session.getCellText(0, 1))
            assertEquals("99.5", session.getCellText(0, 2))
            assertEquals("[BLOB]", session.getCellText(0, 3))

            assertEquals("2", session.getCellText(1, 0))
            assertEquals("NULL", session.getCellText(1, 1))
            assertEquals("NULL", session.getCellText(1, 2))
            assertEquals("NULL", session.getCellText(1, 3))

            assertEquals("3", session.getCellText(2, 0))
            assertEquals("", session.getCellText(2, 1))
            assertEquals("0.0", session.getCellText(2, 2))

            val copiedText = session.getSelectedRangeText(0, 0, 1, 3)
            val expectedText = "1\tAlice\t99.5\t[BLOB]\n2\tNULL\tNULL\tNULL"
            assertEquals(expectedText, copiedText)

            session.setActiveSheetIndex(sheets.indexOf("Active Users View"))
            session.waitForMetaForTesting()
            assertEquals("1", session.getCellText(0, 0))
            assertEquals("Alice", session.getCellText(0, 1))

            val rows = session.fetchVisibleRows(0, 2)
            assertEquals(2, rows.size)
            assertEquals("1", rows[0][0])
            assertEquals("Alice", rows[0][1])

            session.setActiveSheetIndex(sheets.indexOf("Empty Table"))
            session.waitForMetaForTesting()
            assertEquals("", session.getCellText(0, 0))
            val emptyRows = session.fetchVisibleRows(0, 10)
            assertEquals(0, emptyRows.size)
        } finally {
            session.close()
        }
    }

    @Test
    fun testAndroidTableIncludedAndAndroidMetadataFiltered() {
        val dbFile = File.createTempFile("test_android_metadata", ".db").apply { deleteOnExit() }
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE \"android_metadata\" (\"locale\" TEXT);")
                stmt.execute("CREATE TABLE \"AndroidTable\" (\"id\" INTEGER, \"val\" TEXT);")
            }
        }

        val rawConn = DriverManager.getConnection(jdbcUrl)
        val sqliteConn = JdbcSqliteConnection(rawConn)

        val session = SqliteDatabaseSession(null, dbFile.parentFile, sqliteConn)
        try {
            session.waitForMetaForTesting()
            val sheets = session.getSheetNames()
            assertEquals(1, sheets.size)
            assertEquals("AndroidTable", sheets[0])
        } finally {
            session.close()
        }
    }

    @Test
    fun testFetchVisibleRows_EdgeCasesAndLimits() {
        val dbFile = File.createTempFile("test_fetch_edge", ".db").apply { deleteOnExit() }
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE \"TestTable\" (\"id\" INTEGER, \"val\" TEXT);")
                stmt.execute("INSERT INTO \"TestTable\" VALUES (1, 'A'), (2, 'B'), (3, 'C');")
            }
        }

        val rawConn = DriverManager.getConnection(jdbcUrl)
        val sqliteConn = JdbcSqliteConnection(rawConn)

        val session = SqliteDatabaseSession(null, dbFile.parentFile, sqliteConn)
        try {
            session.waitForMetaForTesting()
            assertEquals(0, session.fetchVisibleRows(0, 0).size)
            assertEquals(0, session.fetchVisibleRows(0, -10).size)

            val negOffsetRows = session.fetchVisibleRows(-5, 2)
            assertEquals(2, negOffsetRows.size)
            assertEquals("1", negOffsetRows[0][0])
            assertEquals("2", negOffsetRows[1][0])
        } finally {
            session.close()
        }
    }

    @Test
    fun testCorruptedDatabaseFileViaConnection_ThrowsException() {
        val tmpFile = File.createTempFile("corrupt_jdbc_db", ".sqlite").apply { deleteOnExit() }
        FileOutputStream(tmpFile).use { fos ->
            fos.write("Corrupted garbage bytes that are not SQLite".toByteArray())
        }

        val jdbcUrl = "jdbc:sqlite:${tmpFile.absolutePath}"
        val rawConn = DriverManager.getConnection(jdbcUrl)
        val sqliteConn = JdbcSqliteConnection(rawConn)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteDatabaseSession(null, tmpFile.parentFile, sqliteConn)
        }
    }

    @Test
    fun testFailingConnectionDuringQuery_ThrowsExceptionAndSetsError() {
        class FailingOnRowReadConn : SqliteDbConnection {
            override fun execSQL(sql: String) {}
            override fun query(sql: String, args: Array<String>?): SqliteCursor {
                if (sql.contains("sqlite_master")) {
                    return singleRowCursor(arrayOf("name", "type"), arrayOf("ErrorTable", "table"))
                }
                if (sql.contains("PRAGMA table_info")) {
                    return singleRowCursor(arrayOf("cid", "name", "type"), arrayOf("0", "col_a", "TEXT"))
                }
                if (sql.contains("COUNT(*)")) {
                    return singleLongCursor(5L)
                }
                throw RuntimeException("Simulated disk I/O failure during row read")
            }
            override fun close() {}
        }

        val session = SqliteDatabaseSession(null, null, FailingOnRowReadConn())
        try {
            session.waitForMetaForTesting()
            assertNull("Metadata load must succeed without error", session.lastErrorMsg)

            assertThrows(IllegalArgumentException::class.java) {
                session.fetchVisibleRows(0, 10)
            }
            assertTrue(
                "Error message should record row read failure",
                session.lastErrorMsg?.contains("Simulated disk I/O failure during row read") == true
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun testFailureMidCursorIteration_ThrowsAndSetsErrorWithoutPartialData() {
        class MidCursorFailConn : SqliteDbConnection {
            override fun execSQL(sql: String) {}
            override fun query(sql: String, args: Array<String>?): SqliteCursor {
                if (sql.contains("sqlite_master")) {
                    return singleRowCursor(arrayOf("name", "type"), arrayOf("MidFailTable", "table"))
                }
                if (sql.contains("PRAGMA table_info")) {
                    return singleRowCursor(arrayOf("cid", "name", "type"), arrayOf("0", "col_a", "TEXT"))
                }
                if (sql.contains("COUNT(*)")) {
                    return singleLongCursor(3L)
                }
                return object : SqliteCursor {
                    private var rowIdx = -1
                    override fun moveToNext(): Boolean {
                        rowIdx++
                        if (rowIdx == 0) return true
                        throw RuntimeException("Corrupted B-Tree page at row 2")
                    }
                    override fun moveToFirst(): Boolean = moveToNext()
                    override val columnCount: Int get() = 1
                    override val columnNames: Array<String> get() = arrayOf("col_a")
                    override fun getColumnIndex(name: String): Int = 0
                    override fun getType(colIdx: Int): Int = SqliteFieldType.STRING
                    override fun getString(colIdx: Int): String = "ValidRow0"
                    override fun getLong(colIdx: Int): Long = 0L
                    override fun getDouble(colIdx: Int): Double = 0.0
                    override fun getBlob(colIdx: Int): ByteArray? = null
                    override fun close() {}
                }
            }
            override fun close() {}
        }

        val session = SqliteDatabaseSession(null, null, MidCursorFailConn())
        try {
            session.waitForMetaForTesting()
            assertNull(session.lastErrorMsg)
            assertThrows(IllegalArgumentException::class.java) {
                session.fetchVisibleRows(0, 3)
            }
            assertTrue(session.lastErrorMsg?.contains("Corrupted B-Tree page at row 2") == true)
        } finally {
            session.close()
        }
    }

    /**
     * Verifies that a late failure from a stale query on a previously active table does not
     * overwrite the state or error message of the newly selected table.
     */
    @Test
    fun testStaleReadRaceCondition_SwitchTableBeforeOldReadFails_DoesNotCorruptNewTable() {
        val table0QueryStarted = CountDownLatch(1)
        val allowTable0QueryToFail = CountDownLatch(1)

        class RaceSimulatingConn : SqliteDbConnection {
            override fun execSQL(sql: String) {}
            override fun query(sql: String, args: Array<String>?): SqliteCursor {
                if (sql.contains("sqlite_master") && args == null) {
                    return object : SqliteCursor {
                        private var idx = -1
                        private val names = arrayOf("SlowTable", "FastTable")
                        override fun moveToNext(): Boolean {
                            idx++
                            return idx < names.size
                        }
                        override fun moveToFirst(): Boolean {
                            idx = 0
                            return true
                        }
                        override val columnCount: Int get() = 1
                        override val columnNames: Array<String> get() = arrayOf("name")
                        override fun getColumnIndex(name: String): Int = 0
                        override fun getType(colIdx: Int): Int = SqliteFieldType.STRING
                        override fun getString(colIdx: Int): String = names[idx]
                        override fun getLong(colIdx: Int): Long = 0L
                        override fun getDouble(colIdx: Int): Double = 0.0
                        override fun getBlob(colIdx: Int): ByteArray? = null
                        override fun close() {}
                    }
                }
                if (sql.contains("sqlite_master") && args != null) {
                    return singleRowCursor(arrayOf("type"), arrayOf("table"))
                }
                if (sql.contains("PRAGMA table_info")) {
                    return singleRowCursor(arrayOf("cid", "name", "type"), arrayOf("0", "val", "TEXT"))
                }
                if (sql.contains("COUNT(*)")) {
                    return singleLongCursor(2L)
                }
                if (sql.contains("\"SlowTable\"")) {
                    table0QueryStarted.countDown()
                    allowTable0QueryToFail.await(3, TimeUnit.SECONDS)
                    throw RuntimeException("Stale SlowTable read failed")
                }
                return singleRowCursor(arrayOf("val"), arrayOf("CleanFastValue"))
            }
            override fun close() {}
        }

        val session = SqliteDatabaseSession(null, null, RaceSimulatingConn())
        try {
            session.waitForMetaForTesting()
            assertEquals(0, session.getActiveSheetIndex())

            val readerThread = Thread {
                try {
                    session.fetchVisibleRows(0, 2)
                } catch (_: Throwable) {}
            }
            readerThread.start()

            assertTrue(table0QueryStarted.await(3, TimeUnit.SECONDS))

            session.setActiveSheetIndex(1)
            session.waitForMetaForTesting()

            allowTable0QueryToFail.countDown()
            readerThread.join(3000)

            assertNull("Stale query failure must not set error on the newly selected table", session.lastErrorMsg)
            assertEquals("CleanFastValue", session.getCellText(0, 0))
        } finally {
            session.close()
        }
    }

    /**
     * Verifies that closing the session waits for any in-flight query to finish before closing the underlying connection.
     */
    @Test
    fun testCloseDuringBlockedRead_WaitsForInFlightOperationBeforeClosingConnection() {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val connectionClosed = AtomicBoolean(false)
        val connWasOpenDuringQuery = AtomicBoolean(false)

        class BlockingReadConn : SqliteDbConnection {
            override fun execSQL(sql: String) {}
            override fun query(sql: String, args: Array<String>?): SqliteCursor {
                if (sql.contains("sqlite_master")) {
                    return singleRowCursor(arrayOf("name", "type"), arrayOf("BlockTable", "table"))
                }
                if (sql.contains("PRAGMA table_info")) {
                    return singleRowCursor(arrayOf("cid", "name", "type"), arrayOf("0", "c1", "TEXT"))
                }
                if (sql.contains("COUNT(*)")) {
                    return singleLongCursor(1L)
                }
                readEntered.countDown()
                releaseRead.await(3, TimeUnit.SECONDS)
                connWasOpenDuringQuery.set(!connectionClosed.get())
                return singleRowCursor(arrayOf("c1"), arrayOf("RowVal"))
            }
            override fun close() {
                connectionClosed.set(true)
            }
        }

        val session = SqliteDatabaseSession(null, null, BlockingReadConn())
        session.waitForMetaForTesting()

        val queryThread = Thread {
            try {
                session.fetchVisibleRows(0, 1)
            } catch (_: Throwable) {}
        }
        queryThread.start()

        assertTrue(readEntered.await(3, TimeUnit.SECONDS))

        val closeThread = Thread {
            session.close()
        }
        closeThread.start()

        Thread.sleep(60)
        assertFalse("Connection must not be closed while query is still in flight", connectionClosed.get())

        releaseRead.countDown()
        queryThread.join(3000)
        closeThread.join(3000)

        assertTrue("Connection must have been open while query was executing", connWasOpenDuringQuery.get())
        assertTrue("Connection must be closed after in-flight query completes", connectionClosed.get())
    }

    private fun singleRowCursor(cols: Array<String>, vals: Array<String>): SqliteCursor {
        return object : SqliteCursor {
            private var pos = -1
            override fun moveToNext(): Boolean {
                pos++
                return pos == 0
            }
            override fun moveToFirst(): Boolean {
                pos = 0
                return true
            }
            override val columnCount: Int get() = cols.size
            override val columnNames: Array<String> get() = cols
            override fun getColumnIndex(name: String): Int = cols.indexOfFirst { it.equals(name, true) }
            override fun getType(colIdx: Int): Int = SqliteFieldType.STRING
            override fun getString(colIdx: Int): String = vals[colIdx]
            override fun getLong(colIdx: Int): Long = vals[colIdx].toLongOrNull() ?: 0L
            override fun getDouble(colIdx: Int): Double = vals[colIdx].toDoubleOrNull() ?: 0.0
            override fun getBlob(colIdx: Int): ByteArray? = null
            override fun close() {}
        }
    }

    private fun singleLongCursor(value: Long): SqliteCursor {
        return object : SqliteCursor {
            private var pos = -1
            override fun moveToNext(): Boolean {
                pos++
                return pos == 0
            }
            override fun moveToFirst(): Boolean {
                pos = 0
                return true
            }
            override val columnCount: Int get() = 1
            override val columnNames: Array<String> get() = arrayOf("count")
            override fun getColumnIndex(name: String): Int = 0
            override fun getType(colIdx: Int): Int = SqliteFieldType.INTEGER
            override fun getString(colIdx: Int): String = value.toString()
            override fun getLong(colIdx: Int): Long = value
            override fun getDouble(colIdx: Int): Double = value.toDouble()
            override fun getBlob(colIdx: Int): ByteArray? = null
            override fun close() {}
        }
    }
}
