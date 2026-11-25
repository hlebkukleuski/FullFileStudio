/*
 * FullFileStudio
 * Copyright (C) 2026 Hleb Kukleuski (https://github.com/hlebkukleuski)
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Woff2DecoderTest {

    private fun loadRobotoWoff2Fixture(): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("woff2/Roboto-Subset.woff2")
        assertNotNull("Expected woff2/Roboto-Subset.woff2 test resource", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun computeWholeFontChecksum(sfnt: ByteArray): Int {
        var sum = 0
        val aligned = sfnt.size and -4
        var i = 0
        while (i < aligned) {
            val w = ((sfnt[i].toInt() and 0xFF) shl 24) or
                ((sfnt[i + 1].toInt() and 0xFF) shl 16) or
                ((sfnt[i + 2].toInt() and 0xFF) shl 8) or
                (sfnt[i + 3].toInt() and 0xFF)
            sum += w
            i += 4
        }
        if (aligned < sfnt.size) {
            var last = 0
            for (t in 0 until (sfnt.size - aligned)) {
                last = last or ((sfnt[aligned + t].toInt() and 0xFF) shl (24 - t * 8))
            }
            sum += last
        }
        return sum
    }

    private fun wrapBrotliUncompressed(payload: ByteArray): ByteArray {
        require(payload.size in 1..65536)
        val mlen = payload.size - 1
        var bits = 0L
        var nbits = 0
        fun putBits(value: Int, width: Int) {
            bits = bits or ((value.toLong() and ((1L shl width) - 1L)) shl nbits)
            nbits += width
        }
        putBits(0, 1)
        putBits(0, 1)
        putBits(0, 2)
        putBits(mlen, 16)
        putBits(1, 1)
        if (nbits % 8 != 0) {
            putBits(0, 8 - (nbits % 8))
        }
        val headerBytes = nbits / 8
        val out = ByteArray(headerBytes + payload.size + 1)
        for (i in 0 until headerBytes) {
            out[i] = ((bits ushr (i * 8)) and 0xFFL).toByte()
        }
        System.arraycopy(payload, 0, out, headerBytes, payload.size)
        out[out.size - 1] = 0x03
        return out
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Int) {
        require(value >= 0)
        if (value < 128) {
            out.write(value)
            return
        }
        val bytes = IntArray(5)
        var n = 0
        var v = value
        while (v > 0) {
            bytes[n++] = v and 0x7F
            v = v ushr 7
        }
        for (i in n - 1 downTo 0) {
            out.write(if (i > 0) (bytes[i] or 0x80) else bytes[i])
        }
    }

    @Test
    fun decodesRealGoogleFontsRobotoWoff2WithValidChecksumsAndCompositeGlyphs() {
        val woff2 = loadRobotoWoff2Fixture()
        val sfnt = Woff2Decoder.decodeToSfnt(woff2)
        assertNotNull("Real Roboto WOFF2 should decode cleanly to SFNT", sfnt)
        val bytes = sfnt!!
        assertTrue(bytes.size > 4800)

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00010000, bb.getInt(0))
        val numTables = bb.getShort(4).toInt() and 0xFFFF
        assertEquals(15, numTables)

        val tags = ArrayList<String>(numTables)
        val tableMap = HashMap<String, Pair<Int, Int>>()
        for (i in 0 until numTables) {
            val dir = 12 + i * 16
            val tag = String(bytes, dir, 4, StandardCharsets.US_ASCII)
            val offset = bb.getInt(dir + 8)
            val length = bb.getInt(dir + 12)
            assertTrue("Invalid bounds for table $tag", offset >= 12 + numTables * 16 && offset + length <= bytes.size)
            tags.add(tag)
            tableMap[tag] = offset to length
        }

        val sortedTags = tags.sorted()
        assertEquals("Table directory entries must be sorted in ascending tag order", sortedTags, tags)
        assertTrue(tableMap.containsKey("glyf"))
        assertTrue(tableMap.containsKey("loca"))
        assertTrue(tableMap.containsKey("hmtx"))
        assertTrue(tableMap.containsKey("STAT"))

        assertEquals(
            "Whole-font SFNT checksum must equal OpenType magic 0xB1B0AFBA",
            0xB1B0AFBA.toInt(),
            computeWholeFontChecksum(bytes)
        )

        val (maxpOff, _) = tableMap.getValue("maxp")
        val numGlyphs = bb.getShort(maxpOff + 4).toInt() and 0xFFFF
        assertEquals(72, numGlyphs)

        val (glyfOff, glyfLen) = tableMap.getValue("glyf")
        val (locaOff, _) = tableMap.getValue("loca")
        var simpleCount = 0
        var compositeCount = 0
        for (g in 0 until numGlyphs) {
            val start = (bb.getShort(locaOff + g * 2).toInt() and 0xFFFF) * 2
            val end = (bb.getShort(locaOff + (g + 1) * 2).toInt() and 0xFFFF) * 2
            assertTrue(end >= start && end <= glyfLen)
            if (end > start) {
                val nContours = bb.getShort(glyfOff + start).toInt()
                if (nContours > 0) simpleCount++
                if (nContours == -1) compositeCount++
            }
        }
        assertTrue("Expected reconstructed simple glyphs", simpleCount >= 20)
        assertTrue("Expected reconstructed composite glyphs (e.g. accented E)", compositeCount >= 1)
    }

    @Test
    fun rendersWoff2ThroughDocumentRendererEngineWithoutError() {
        val woff2 = loadRobotoWoff2Fixture()
        val tempFile = File.createTempFile("roboto_test_", ".woff2")
        try {
            tempFile.writeBytes(woff2)
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val engine = DocumentRendererEngineImpl()
                val session = engine.createSessionForFormat(pfd.fileDescriptor, DetectedFormat.FONT, "woff2")
                try {
                    assertTrue("Expected at least 1 font preview page", session.pageCount >= 1)
                    val summaryText = session.getPageText(0)
                    assertTrue("Expected WOFF 2.0 label in font preview text: $summaryText", summaryText.contains("WOFF 2.0"))
                    assertTrue("Expected decoded Roboto family in font preview text: $summaryText", summaryText.contains("Roboto"))
                    assertTrue("Must not report unsupported error: $summaryText", !summaryText.contains("not supported", ignoreCase = true))
                    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    session.renderTile(0, 0, 0, 1000, bmp)
                } finally {
                    session.close()
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun reconstructsTransformedHmtxOverlapBitmapAndTtcCollection() {
        val head = ByteArray(54)
        ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x00010000)
            putInt(12, 0x5F0F3CF5)
            putShort(16, 0x080B.toShort())
            putShort(18, 1000.toShort())
            putShort(50, 0.toShort())
        }

        val hhea = ByteArray(36)
        ByteBuffer.wrap(hhea).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x00010000)
            putShort(4, 800.toShort())
            putShort(6, (-200).toShort())
            putShort(34, 2.toShort())
        }

        val maxp = ByteArray(32)
        ByteBuffer.wrap(maxp).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x00010000)
            putShort(4, 3.toShort())
        }

        val glyfOut = ByteArrayOutputStream()
        val glyfHdr = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
        glyfHdr.putShort(0)
        glyfHdr.putShort(1)
        glyfHdr.putShort(3)
        glyfHdr.putShort(0)
        glyfHdr.putInt(6)
        glyfHdr.putInt(1)
        glyfHdr.putInt(3)
        glyfHdr.putInt(7)
        glyfHdr.putInt(6)
        glyfHdr.putInt(12)
        glyfHdr.putInt(0)
        glyfOut.write(glyfHdr.array())
        glyfOut.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xFF.toByte(), 0xFF.toByte()))
        glyfOut.write(byteArrayOf(0x03))
        glyfOut.write(byteArrayOf(23, 11, 0))
        glyfOut.write(byteArrayOf(0xFF.toByte(), 100, 100, 0x00, 0x00, 0x00, 0x00))
        glyfOut.write(byteArrayOf(0x00, 0x02, 0x00, 0x01, 0x0A, 0x14))
        val bbox = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        bbox.put(byteArrayOf(0x20, 0x00, 0x00, 0x00))
        bbox.putShort(26)
        bbox.putShort(36)
        bbox.putShort(150)
        bbox.putShort(200)
        glyfOut.write(bbox.array())
        glyfOut.write(byteArrayOf(0x40))
        val transformedGlyf = glyfOut.toByteArray()

        val transformedHmtx = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x03.toByte())
            putShort(500.toShort())
            putShort(620.toShort())
        }.array()

        val rawStream = ByteArrayOutputStream()
        rawStream.write(head)
        rawStream.write(hhea)
        rawStream.write(maxp)
        rawStream.write(transformedGlyf)
        rawStream.write(transformedHmtx)
        val compressed = wrapBrotliUncompressed(rawStream.toByteArray())

        val dir = ByteArrayOutputStream()
        dir.write(1)
        writeBase128(dir, head.size)
        dir.write(2)
        writeBase128(dir, hhea.size)
        dir.write(4)
        writeBase128(dir, maxp.size)
        dir.write(10)
        writeBase128(dir, 64)
        writeBase128(dir, transformedGlyf.size)
        dir.write(11)
        writeBase128(dir, 8)
        writeBase128(dir, 0)
        dir.write((1 shl 6) or 3)
        writeBase128(dir, 10)
        writeBase128(dir, transformedHmtx.size)

        val ttcDir = ByteArrayOutputStream()
        ttcDir.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00010000).array())
        ttcDir.write(1)
        ttcDir.write(6)
        ttcDir.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00010000).array())
        for (idx in 0..5) ttcDir.write(idx)

        val dirBytes = dir.toByteArray()
        val ttcBytes = ttcDir.toByteArray()
        val totalLen = ((48 + dirBytes.size + ttcBytes.size + compressed.size) + 3) and -4
        val woff2 = ByteArray(totalLen)
        ByteBuffer.wrap(woff2).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x774F4632)
            putInt(4, 0x74746366)
            putInt(8, totalLen)
            putShort(12, 6.toShort())
            putInt(16, 512)
            putInt(20, compressed.size)
        }
        System.arraycopy(dirBytes, 0, woff2, 48, dirBytes.size)
        System.arraycopy(ttcBytes, 0, woff2, 48 + dirBytes.size, ttcBytes.size)
        System.arraycopy(compressed, 0, woff2, 48 + dirBytes.size + ttcBytes.size, compressed.size)

        val ttcSfnt = Woff2Decoder.decodeToSfnt(woff2)
        assertNotNull("Expected WOFF2 TTC with transformed hmtx + overlap bitmap to decode", ttcSfnt)
        val outBb = ByteBuffer.wrap(ttcSfnt!!).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x74746366, outBb.getInt(0))
        assertEquals(1, outBb.getInt(8))
        val firstFontOffset = outBb.getInt(12)
        val numTables = outBb.getShort(firstFontOffset + 4).toInt() and 0xFFFF
        assertEquals(6, numTables)

        var hmtxOff = -1
        var hmtxLen = -1
        var glyfOff = -1
        var locaOff = -1
        for (i in 0 until numTables) {
            val e = firstFontOffset + 12 + i * 16
            val tag = String(ttcSfnt, e, 4, StandardCharsets.US_ASCII)
            val off = outBb.getInt(e + 8)
            val len = outBb.getInt(e + 12)
            when (tag) {
                "hmtx" -> {
                    hmtxOff = off
                    hmtxLen = len
                }
                "glyf" -> glyfOff = off
                "loca" -> locaOff = off
            }
        }
        assertEquals(10, hmtxLen)
        assertEquals(500, outBb.getShort(hmtxOff).toInt() and 0xFFFF)
        assertEquals(0, outBb.getShort(hmtxOff + 2).toInt())
        assertEquals(620, outBb.getShort(hmtxOff + 4).toInt() and 0xFFFF)
        assertEquals(16, outBb.getShort(hmtxOff + 6).toInt())
        assertEquals(26, outBb.getShort(hmtxOff + 8).toInt())

        val g1Start = (outBb.getShort(locaOff + 2).toInt() and 0xFFFF) * 2
        val g1FirstFlagOffset = glyfOff + g1Start + 10 + 2 + 2
        val g1FirstFlag = ttcSfnt[g1FirstFlagOffset].toInt() and 0xFF
        assertTrue("Expected OVERLAP_SIMPLE bit 6 on glyph 1 first flag", (g1FirstFlag and 0x40) != 0)
    }

    @Test
    fun rejectsMalformedBase128AndTruncatedInputs() {
        assertNull(Woff2Decoder.decodeToSfnt(ByteArray(16)))

        val badBase128 = ByteArray(64)
        ByteBuffer.wrap(badBase128).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x774F4632)
            putInt(4, 0x00010000)
            putInt(8, 64)
            putShort(12, 1.toShort())
            putInt(20, 4)
        }
        badBase128[48] = 1
        badBase128[49] = 0x80.toByte()
        badBase128[50] = 0x10
        assertNull("Leading 0x80 in UIntBase128 must be rejected per W3C WOFF2 §3.1", Woff2Decoder.decodeToSfnt(badBase128))
    }
}
