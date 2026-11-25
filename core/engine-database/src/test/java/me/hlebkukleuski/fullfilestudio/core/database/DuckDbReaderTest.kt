/*
 * FullFileStudio
 * Copyright (C) 2026 Hleb Kukleuski (https://github.com/hlebkukleuski)
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.database

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DuckDbReaderTest {

    private fun readResource(path: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
        assertNotNull("Expected test resource $path", stream)
        return stream!!.use { raw ->
            if (path.endsWith(".gz")) GZIPInputStream(raw).readBytes() else raw.readBytes()
        }
    }

    @Test
    fun decodesRealDuckDb16KbBlockSizeAndMultiSchemaTables() {
        val db16k = readResource("duckdb/block_size_16kb.db")
        val parsed16k = DuckDbReader.parse(db16k)
        assertNotNull(parsed16k)
        assertEquals(16384L, parsed16k!!.blockAllocSize)
        val tbl16k = parsed16k.tables.firstOrNull { it.name == "tbl" }
        assertNotNull("Expected user table 'tbl' in block_size_16kb.db", tbl16k)
        assertEquals(listOf("magic"), tbl16k!!.columns)
        assertEquals(listOf(listOf("42")), tbl16k.rows)

        val bcDb = readResource("duckdb/bc_0102.db.gz")
        val parsedBc = DuckDbReader.parse(bcDb)
        assertNotNull(parsedBc)
        val s1Tbl = parsedBc!!.tables.firstOrNull { it.name == "s1.tbl" }
        assertNotNull("Expected schema-qualified user table 's1.tbl'", s1Tbl)
        assertEquals(listOf("i"), s1Tbl!!.columns)
        assertEquals(listOf(listOf("42")), s1Tbl.rows)
    }

    @Test
    fun decodesRealDuckDbUtf8UncompressedStringColumn() {
        val germanDb = readResource("duckdb/german_collation.db.gz")
        val parsed = DuckDbReader.parse(germanDb)
        assertNotNull(parsed)
        val stringsTbl = parsed!!.tables.firstOrNull { it.name == "strings" }
        assertNotNull("Expected user table 'strings'", stringsTbl)
        assertEquals(listOf("s"), stringsTbl!!.columns)
        val values = stringsTbl.rows.map { it.single() }
        assertEquals(listOf("Gabel", "Goethe", "Götz", "Goldmann", "Göbel", "Göthe"), values)
    }

    @Test
    fun decodesRealMultiTableDuckDbWithBitpackingRleConstantsMacrosAndSequences() {
        val hfDb = readResource("duckdb/huggingface_index.db.gz")
        val parsed = DuckDbReader.parse(hfDb)
        assertNotNull(parsed)

        val byName = parsed!!.tables.associateBy { it.name }
        assertTrue("Expected main user table 'data', found: ${byName.keys}", byName.containsKey("data"))
        assertTrue(byName.containsKey("fts_main_data.dict"))
        assertTrue(byName.containsKey("fts_main_data.docs"))
        assertTrue(byName.containsKey("fts_main_data.fields"))
        assertTrue(byName.containsKey("fts_main_data.stats"))
        assertTrue(byName.containsKey("fts_main_data.stopwords"))
        assertTrue(byName.containsKey("fts_main_data.terms"))
        assertTrue(byName.containsKey("_DuckDB_Catalog"))

        val mainData = byName.getValue("data")
        assertEquals(listOf("act", "prompt", "__hf_index_id"), mainData.columns)
        assertEquals(153, mainData.rows.size)
        assertTrue(mainData.rows[0][0].isNotBlank())
        assertTrue(mainData.rows[0][1].length > 20)
        assertEquals("0", mainData.rows[0][2])
        assertEquals("152", mainData.rows[152][2])

        val dict = byName.getValue("fts_main_data.dict")
        assertEquals(1558, dict.rows.size)
        assertEquals(listOf("0", "bracket", "10"), dict.rows[0])
        assertEquals(listOf("1557", "sunnah", "1"), dict.rows[1557])

        val terms = byName.getValue("fts_main_data.terms")
        assertEquals(5468, terms.rows.size)
        assertEquals("0", terms.rows[0][0])
        assertEquals("152", terms.rows.last()[0])

        val stats = byName.getValue("fts_main_data.stats")
        assertEquals(1, stats.rows.size)
        assertEquals("153", stats.rows[0][0])
        assertTrue(stats.rows[0][1].startsWith("35.738"))
    }

    @Test
    fun opensRealDuckDbThroughDatabaseInspectorStreamingSession() {
        val hfDb = readResource("duckdb/huggingface_index.db.gz")
        val tmp = File.createTempFile("hf_duckdb_", ".duckdb")
        try {
            tmp.writeBytes(hfDb)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val engine = DatabaseInspectorEngineImpl()
                val session = engine.createSessionWithCacheDir(pfd.fileDescriptor, tmp.parentFile, "duckdb")
                try {
                    val sheetNames = session.getSheetNames()
                    val dataSheetIdx = sheetNames.indexOf("data")
                    assertTrue("Expected 'data' sheet in DatabaseInspectorEngineImpl: $sheetNames", dataSheetIdx >= 0)
                    session.setActiveSheetIndex(dataSheetIdx)
                    assertTrue(session.getCellText(0, 0).isNotBlank())
                    assertTrue(session.getCellText(0, 1).length > 20)
                    assertEquals("0", session.getCellText(0, 2))
                    assertEquals("152", session.getCellText(152, 2))
                } finally {
                    session.close()
                }
            }
        } finally {
            tmp.delete()
        }
    }
}
