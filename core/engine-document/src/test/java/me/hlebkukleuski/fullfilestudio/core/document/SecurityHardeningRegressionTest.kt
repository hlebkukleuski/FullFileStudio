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

package me.hlebkukleuski.fullfilestudio.core.document

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.database.SqlitePageSource
import me.hlebkukleuski.fullfilestudio.core.database.SqliteReader
import me.hlebkukleuski.fullfilestudio.core.document.svg.SvgXml
import me.hlebkukleuski.fullfilestudio.core.document.svg.SvgXmlException
import me.hlebkukleuski.fullfilestudio.core.security.CorruptedDataException
import me.hlebkukleuski.fullfilestudio.core.security.ZstdDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecurityHardeningRegressionTest {

    @Test(timeout = 2000L)
    fun zstdSkippableFrameOverflowThrowsInsteadOfLooping() {
        val frame = ByteArray(12)
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 0x184D2A50)
        bb.putInt(4, 0xFFFFFFF8.toInt())
        bb.putInt(8, 0)
        try {
            ZstdDecoder.decompress(frame, 0, frame.size, 1024 * 1024)
            fail("Expected CorruptedDataException for overflowing skippable frame size")
        } catch (_: CorruptedDataException) {
        }
    }

    @Test(timeout = 3000L)
    fun svgBillionLaughsIsRejectedByExpansionLimit() {
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE svg [
              <!ENTITY l0 "<g/>">
              <!ENTITY l1 "&l0;&l0;&l0;&l0;&l0;&l0;&l0;&l0;&l0;&l0;">
              <!ENTITY l2 "&l1;&l1;&l1;&l1;&l1;&l1;&l1;&l1;&l1;&l1;">
              <!ENTITY l3 "&l2;&l2;&l2;&l2;&l2;&l2;&l2;&l2;&l2;&l2;">
              <!ENTITY l4 "&l3;&l3;&l3;&l3;&l3;&l3;&l3;&l3;&l3;&l3;">
              <!ENTITY l5 "&l4;&l4;&l4;&l4;&l4;&l4;&l4;&l4;&l4;&l4;">
              <!ENTITY l6 "&l5;&l5;&l5;&l5;&l5;&l5;&l5;&l5;&l5;&l5;">
            ]>
            <svg xmlns="http://www.w3.org/2000/svg">&l6;</svg>
        """.trimIndent()
        try {
            SvgXml.parse(bomb)
            fail("Expected SvgXmlException on Billion Laughs entity expansion")
        } catch (_: SvgXmlException) {
        }
    }

    @Test(timeout = 2000L)
    fun formulaFactLargeInputReturnsNumErrorImmediately() {
        val ctx = object : FormulaContext {
            override fun cell(sheet: String?, row: Int, col: Int): Any? = null
        }
        val result = ExcelFormula.evaluate("=FACT(2000000000)", ctx, 0, 0, "Sheet1")
        assertEquals(FormulaError.NUM, result)
    }

    @Test(timeout = 2000L)
    fun formulaWildcardResistsReDoSBacktracking() {
        val evilText = "a".repeat(80)
        val ctx = object : FormulaContext {
            override fun cell(sheet: String?, row: Int, col: Int): Any? =
                if (row == 0 && col == 0) evilText else null
        }
        val t0 = System.nanoTime()
        val result = ExcelFormula.evaluate(
            """=COUNTIF(A1:A1,"*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b")""",
            ctx,
            0,
            1,
            "Sheet1"
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        assertEquals(0.0, (result as Number).toDouble(), 1e-6)
        assertTrue("Wildcard evaluation took too long: ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test(timeout = 2000L)
    fun sqliteCyclicInteriorPageCountRowsThrowsInsteadOfLooping() {
        val pageSize = 512
        val db = ByteArray(pageSize * 2)
        val magic = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, db, 0, magic.size)
        db[16] = (pageSize ushr 8).toByte()
        db[17] = (pageSize and 0xFF).toByte()
        db[18] = 1; db[19] = 1; db[21] = 64; db[22] = 32; db[23] = 32
        db[28] = 0; db[29] = 0; db[30] = 0; db[31] = 2
        db[59] = 1

        val sql = "CREATE TABLE t (id INT)".toByteArray(StandardCharsets.UTF_8)
        val typeB = "table".toByteArray(StandardCharsets.UTF_8)
        val nameB = "t".toByteArray(StandardCharsets.UTF_8)
        val rec = java.io.ByteArrayOutputStream().apply {
            write(6)
            write(13 + typeB.size * 2)
            write(13 + nameB.size * 2)
            write(13 + nameB.size * 2)
            write(1)
            write(13 + sql.size * 2)
            write(typeB); write(nameB); write(nameB); write(2); write(sql)
        }.toByteArray()
        val cellOff = pageSize - rec.size - 2
        db[100] = 0x0D
        db[103] = 0; db[104] = 1
        db[105] = (cellOff ushr 8).toByte(); db[106] = (cellOff and 0xFF).toByte()
        db[108] = (cellOff ushr 8).toByte(); db[109] = (cellOff and 0xFF).toByte()
        db[cellOff] = rec.size.toByte()
        db[cellOff + 1] = 1
        System.arraycopy(rec, 0, db, cellOff + 2, rec.size)

        val p2 = pageSize
        db[p2] = 0x05
        db[p2 + 3] = 0; db[p2 + 4] = 0
        db[p2 + 8] = 0; db[p2 + 9] = 0; db[p2 + 10] = 0; db[p2 + 11] = 2

        val reader = SqliteReader(
            SqlitePageSource { off, dst, len ->
                val avail = minOf(len, (db.size - off).toInt())
                if (avail <= 0) return@SqlitePageSource 0
                System.arraycopy(db, off.toInt(), dst, 0, avail)
                avail
            },
            db.size.toLong()
        )
        try {
            reader.countRows("t")
            fail("Expected IllegalArgumentException on cyclic SQLite interior page")
        } catch (_: IllegalArgumentException) {
        }
    }
}
