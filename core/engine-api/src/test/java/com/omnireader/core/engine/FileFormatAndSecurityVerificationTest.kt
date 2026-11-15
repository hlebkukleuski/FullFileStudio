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

import com.omnireader.core.engine.impl.CleanRoomRtfSession
import com.omnireader.core.engine.impl.EbookTextSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileFormatAndSecurityVerificationTest {

    private fun buildZipContainer(entries: List<Pair<String, ByteArray>>): ByteBuffer {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content)
                zos.closeEntry()
            }
        }
        return ByteBuffer.wrap(bos.toByteArray())
    }

    private fun buildPalmDocMobiHeader(encryptionType: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        val titleBytes = "CleanRoomKindleBook".toByteArray(StandardCharsets.US_ASCII)
        buf.position(0)
        buf.put(titleBytes)
        buf.position(60)
        buf.put("BOOKMOBI".toByteArray(StandardCharsets.US_ASCII))
        buf.putShort(76, 1) // 1 record in PDB header
        val rec0Offset = 96
        buf.putInt(78, rec0Offset)
        // PalmDOC header at rec0Offset:
        buf.putShort(rec0Offset, 1) // Compression == 1 (Uncompressed)
        buf.putInt(rec0Offset + 4, 128) // Text length
        buf.putShort(rec0Offset + 8, 1) // Text record count
        buf.putShort(rec0Offset + 10, 4096) // Record size
        buf.putShort(rec0Offset + 12, encryptionType) // Encryption Type (0 = none, 2 = Kindle DRM)
        buf.position(rec0Offset + 16)
        buf.put("MOBI".toByteArray(StandardCharsets.US_ASCII))
        buf.position(0)
        return buf
    }

    @Test
    fun testZipOoxmlOdfEpubAndAvifDetection() {
        val docxBuf = buildZipContainer(
            listOf(
                "[Content_Types].xml" to "<Types><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.DOCX, FileSignatureDetector.resolveFormat(docxBuf, "zip"))

        val docmBuf = buildZipContainer(
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.DOCX, FileSignatureDetector.resolveFormat(docmBuf, "docm"))

        val xlsxBuf = buildZipContainer(
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "xl/workbook.xml" to "<workbook/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.XLSX, FileSignatureDetector.resolveFormat(xlsxBuf, "xlsx"))

        val pptxBuf = buildZipContainer(
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "ppt/presentation.xml" to "<presentation/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.PPTX, FileSignatureDetector.resolveFormat(pptxBuf, "pptx"))

        val odtBuf = buildZipContainer(
            listOf(
                "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
                "content.xml" to "<office:document-content/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.ODT, FileSignatureDetector.resolveFormat(odtBuf, "odt"))

        val epubBuf = buildZipContainer(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to "<container/>".toByteArray()
            )
        )
        assertEquals(DetectedFormat.EPUB, FileSignatureDetector.resolveFormat(epubBuf, "epub"))

        // Truncated/corrupted ZIP header must be flagged as CORRUPTED_CONTAINER
        val corruptedZip = ByteBuffer.wrap(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(DetectedFormat.CORRUPTED_CONTAINER, FileSignatureDetector.resolveFormat(corruptedZip, "docx", 8L))

        // AVIF vs MP4
        val avifHeader = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(24)
            put("ftyp".toByteArray())
            put("avif".toByteArray())
            putInt(0)
            put("mif1".toByteArray())
            put("avif".toByteArray())
            flip()
        }
        assertEquals(DetectedFormat.AVIF, FileSignatureDetector.resolveFormat(avifHeader, "mp4"))

        // PalmDOC / MOBI unencrypted vs Kindle DRM encrypted
        val unencryptedMobi = buildPalmDocMobiHeader(0)
        assertEquals(DetectedFormat.MOBI, FileSignatureDetector.resolveFormat(unencryptedMobi, "mobi"))

        val drmEncryptedMobi = buildPalmDocMobiHeader(2)
        assertEquals(DetectedFormat.KINDLE_DRM, FileSignatureDetector.resolveFormat(drmEncryptedMobi, "azw3"))
    }

    @Test
    fun testFontAndNewDatabaseFormatsDetection() {
        // TrueType Font SFNT header (00 01 00 00 with valid table tag 'cmap')
        val ttfHeader = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00010000)
            putShort(1) // numTables = 1
            putShort(16)
            putShort(0)
            putShort(0)
            put("cmap".toByteArray()) // table tag
            putInt(0)
            putInt(32) // offset
            putInt(100) // length
            flip()
        }
        assertEquals(DetectedFormat.FONT, FileSignatureDetector.resolveFormat(ttfHeader, "ttf"))

        // OpenType CFF ("OTTO")
        val otfHeader = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put("OTTO".toByteArray())
            putShort(1)
            putShort(16)
            putShort(0)
            putShort(0)
            put("head".toByteArray())
            putInt(0)
            putInt(32)
            putInt(100)
            flip()
        }
        assertEquals(DetectedFormat.FONT, FileSignatureDetector.resolveFormat(otfHeader, "otf"))

        // Apache Parquet ("PAR1")
        val parquetHeader = ByteBuffer.wrap("PAR1abcdef".toByteArray(StandardCharsets.US_ASCII))
        assertEquals(DetectedFormat.PARQUET, FileSignatureDetector.resolveFormat(parquetHeader, "parquet"))

        // dBase DBF header simulation
        val dbfHeader = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x03.toByte()) // version 3
            put(25.toByte()) // year
            put(6.toByte())  // month
            put(15.toByte()) // day
            putInt(10)       // num records
            putShort(64)     // header len
            putShort(32)     // record len
            flip()
        }
        assertEquals(DetectedFormat.DBF, FileSignatureDetector.resolveFormat(dbfHeader, "dbf"))
    }

    @Test
    fun testXmlTextExtraction_PreservesWordBoundariesAndParagraphs() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:r><w:t>Hel</w:t></w:r>
                        <w:r><w:t>lo</w:t></w:r>
                        <w:r><w:t> </w:t></w:r>
                        <w:r><w:t>world!</w:t></w:r>
                    </w:p>
                    <w:p></w:p>
                    <w:p>
                        <w:r><w:t>Line1</w:t><w:br/><w:t>Line2 &amp; More</w:t></w:r>
                    </w:p>
                </w:body>
            </w:document>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val out = StringBuilder()
        EbookTextSession.extractXmlBytesWithCharset(xml, out)
        val result = out.toString().trim()

        assertEquals("Hello world!\n\nLine1\nLine2 & More", result)
    }

    @Test
    fun testRtfCleanRoomParserAndLegalSecurityGatekeeper() {
        val rtfSample = "{\\rtf1\\ansi\\ansicpg1251\\uc1 {\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Clean-Room \\u1058?\\u1077?\\u1089?\\u1090?\\par Line 2}".toByteArray(StandardCharsets.US_ASCII)
        val parsedRtf = CleanRoomRtfSession.parseRtfBytes(rtfSample)
        assertEquals("Clean-Room Тест\nLine 2", parsedRtf)

        // Clean-room read-only formats are legally permitted
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.RAR_V5, "rar").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.DJVU, "djvu").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.ONE, "one").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.DWG, "dwg").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.FONT, "ttf").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.PARQUET, "parquet").isLegallyPermitted)
        assertTrue(LegalLicensePolicy.evaluate(DetectedFormat.DBF, "dbf").isLegallyPermitted)

        // Cryptographic DRM containers must be blocked under DMCA § 1201
        assertFalse(LegalLicensePolicy.evaluate(DetectedFormat.KFX, "kfx").isLegallyPermitted)
        assertFalse(LegalLicensePolicy.evaluate(DetectedFormat.KINDLE_DRM, "azw3", isDrmEncrypted = true).isLegallyPermitted)

        // Verify XXE payload blocking in SVG/XML headers
        val xxeSvg = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg>&xxe;</svg>""".toByteArray()
        val report = MaliciousHeaderSanitizer.inspect(ByteBuffer.wrap(xxeSvg), DetectedFormat.SVG)
        assertFalse(report.isSafe)
    }
}