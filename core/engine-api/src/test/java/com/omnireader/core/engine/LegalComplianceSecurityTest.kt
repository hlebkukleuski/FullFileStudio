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

import com.omnireader.core.engine.impl.CleanRoomDuckDbReader
import com.omnireader.core.engine.impl.CleanRoomOfficeCrypto
import com.omnireader.core.engine.impl.EpubDrmDetector
import com.omnireader.core.engine.impl.MediaDrmDetector
import com.omnireader.core.engine.impl.OfficeFonts
import com.omnireader.core.engine.impl.OneNoteEncryptionDetector
import com.omnireader.core.engine.impl.PdfPermissionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class LegalComplianceSecurityTest {

    @Test
    fun officeIrmOle2ContainerIsRejectedAsDrm() {
        val cfb = buildMinimalOle2WithEntries(listOf("Root Entry", "\u0009DRMContent"))
        assertTrue(CleanRoomOfficeCrypto.isOfficeIrmBytes(cfb))
        try {
            CleanRoomOfficeCrypto.decryptOoxmlBytes(cfb, "anypass")
            fail("Expected DRM_PROTECTED SecurityException")
        } catch (ex: SecurityException) {
            assertTrue(ex.message?.startsWith("DRM_PROTECTED:") == true)
        }

        val irmDataSpace = buildMinimalOle2WithEntries(listOf("Root Entry", "DRMEncryptedDataSpace"))
        assertTrue(CleanRoomOfficeCrypto.isOfficeIrmBytes(irmDataSpace))
    }

    @Test
    fun epubDrmIsRejectedWhileStandardFontObfuscationIsAllowed() {
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/rights.xml" to "<rights/>".toByteArray())))
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/license.lcpl" to "{}".toByteArray())))
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/sinf.xml" to "<sinf/>".toByteArray())))

        val drmEncXml = """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
                <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent().toByteArray()
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/encryption.xml" to drmEncXml)))

        val idpfFontXml = """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/Body.ttf"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent().toByteArray()
        assertFalse(EpubDrmDetector.isDrmProtected(mapOf("META-INF/encryption.xml" to idpfFontXml)))

        val utf16DrmEncXml = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            String(drmEncXml, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_16LE)
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/encryption.xml" to utf16DrmEncXml)))

        val spoofedFontAlgorithmOnXhtml = """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent().toByteArray()
        assertTrue(EpubDrmDetector.isDrmProtected(mapOf("META-INF/encryption.xml" to spoofedFontAlgorithmOnXhtml)))
    }

    @Test
    fun pdfCopyRestrictionBit5IsEnforced() {
        val plainPdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF".toByteArray()
        assertFalse(PdfPermissionParser.isCopyRestricted(plainPdf))

        val copyAllowedPdf = """
            %PDF-1.7
            5 0 obj << /Filter /Standard /V 2 /R 3 /P -4 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertFalse(PdfPermissionParser.isCopyRestricted(copyAllowedPdf))

        val copyRestrictedPdf = """
            %PDF-1.7
            5 0 obj << /Filter /Standard /V 4 /R 4 /P -3904 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertTrue(PdfPermissionParser.isCopyRestricted(copyRestrictedPdf))

        val inlineRestrictedPdf = "%PDF-1.7\ntrailer << /Encrypt << /P -20 >> >>\n%%EOF".toByteArray()
        assertTrue(PdfPermissionParser.isCopyRestricted(inlineRestrictedPdf))

        val incrementalRestrictPdf = """
            %PDF-1.7
            5 0 obj << /Filter /Standard /V 2 /R 3 /P -4 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
            5 0 obj << /Filter /Standard /V 4 /R 4 /P -3904 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertTrue(PdfPermissionParser.isCopyRestricted(incrementalRestrictPdf))

        val incrementalAllowPdf = """
            %PDF-1.7
            5 0 obj << /Filter /Standard /V 4 /R 4 /P -3904 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
            5 0 obj << /Filter /Standard /V 2 /R 3 /P -4 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertFalse(PdfPermissionParser.isCopyRestricted(incrementalAllowPdf))
    }

    @Test
    fun pdfCopyRestrictionStreamingDetectsEncryptObjectBeyond16Mb() {
        val totalSize = 20L * 1024L * 1024L
        val midOffset = 10L * 1024L * 1024L + 123L
        val headBytes = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n".toByteArray(StandardCharsets.ISO_8859_1)
        val tailBytes = "\ntrailer << /Root 1 0 R /Encrypt 9 0 R >>\n%%EOF".toByteArray(StandardCharsets.ISO_8859_1)
        val tailOffset = totalSize - tailBytes.size

        fun runVirtualPdf(pValue: Int): Boolean {
            val midBytes = "\n9 0 obj << /Filter /Standard /V 4 /R 4 /P $pValue >> endobj\n".toByteArray(StandardCharsets.ISO_8859_1)
            return PdfPermissionParser.isCopyRestricted(totalSize) { offset, buf ->
                val len = minOf(buf.remaining().toLong(), totalSize - offset).toInt()
                if (len <= 0) return@isCopyRestricted 0
                val chunk = ByteArray(len) { ' '.code.toByte() }
                fun overlay(src: ByteArray, srcStart: Long) {
                    val oStart = maxOf(offset, srcStart)
                    val oEnd = minOf(offset + len, srcStart + src.size)
                    if (oStart < oEnd) {
                        System.arraycopy(src, (oStart - srcStart).toInt(), chunk, (oStart - offset).toInt(), (oEnd - oStart).toInt())
                    }
                }
                overlay(headBytes, 0L)
                overlay(midBytes, midOffset)
                overlay(tailBytes, tailOffset)
                buf.put(chunk)
                len
            }
        }

        assertTrue(runVirtualPdf(-3904))
        assertFalse(runVirtualPdf(-4))
    }

    @Test
    fun officeFontsCyrillicFallbackMatchesSerifAndSans() {
        assertEquals("LiberationSerif-Regular.ttf", OfficeFonts.fallbackAssetFileName("texgyrepagella-regular.otf"))
        assertEquals("LiberationSerif-BoldItalic.ttf", OfficeFonts.fallbackAssetFileName("Gelasio-BoldItalic.ttf"))
        assertEquals("LiberationSerif-Italic.ttf", OfficeFonts.fallbackAssetFileName("Caladea-Italic.ttf"))
        assertEquals("LiberationSerif-Bold.ttf", OfficeFonts.fallbackAssetFileName("texgyrebonum-bold.otf"))
        assertEquals("LiberationSerif-Regular.ttf", OfficeFonts.fallbackAssetFileName("texgyreschola-regular.otf"))
        assertEquals("LiberationSans-Regular.ttf", OfficeFonts.fallbackAssetFileName("selawk.ttf"))
        assertEquals("LiberationSans-Bold.ttf", OfficeFonts.fallbackAssetFileName("selawkb.ttf"))
        assertEquals("LiberationSans-BoldItalic.ttf", OfficeFonts.fallbackAssetFileName("ComicNeue-BoldItalic.ttf"))
        assertEquals("LiberationSans-Italic.ttf", OfficeFonts.fallbackAssetFileName("texgyreadventor-italic.otf"))
        assertEquals("LiberationSans-Bold.ttf", OfficeFonts.fallbackAssetFileName("texgyreheroscn-bold.otf"))
        assertNull(OfficeFonts.fallbackAssetFileName("LiberationSerif-Regular.ttf"))
        assertNull(OfficeFonts.fallbackAssetFileName("Carlito-Regular.ttf"))
        assertNull(OfficeFonts.fallbackAssetFileName("EBGaramond-Regular.ttf"))
        assertNull(OfficeFonts.fallbackAssetFileName("DejaVuSans.ttf"))
    }

    @Test
    fun pdfDrmSecurityHandlersAreRejectedAsDrmWhileStandardFilterIsNot() {
        val standardPdf = """
            %PDF-1.7
            5 0 obj << /Filter /Standard /V 4 /R 4 /P -4 >> endobj
            trailer << /Root 1 0 R /Encrypt 5 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertFalse(PdfPermissionParser.isDrmProtected(standardPdf))

        for (handler in listOf("EBX_HANDLER", "EBX#5FHANDLER", "Adobe.APS", "FOPN_foweb", "Microsoft.RMS", "Vitrium", "CustomProprietaryFilter")) {
            val drmPdf = """
                %PDF-1.7
                5 0 obj << /Filter /$handler /V 4 >> endobj
                trailer << /Root 1 0 R /Encrypt 5 0 R >>
                %%EOF
            """.trimIndent().toByteArray()
            assertTrue("Expected DRM detection for $handler", PdfPermissionParser.isDrmProtected(drmPdf))
        }
    }

    @Test
    fun mediaDrmDetectorIdentifiesIsoBmffAndMatroskaEncryptionWithoutFalsePositives() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(0, out.size)
            System.arraycopy(type.toByteArray(StandardCharsets.ISO_8859_1), 0, out, 4, 4)
            System.arraycopy(payload, 0, out, 8, payload.size)
            return out
        }

        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.ISO_8859_1))
        val plainMp4 = ftyp + box("moov", box("mvhd", ByteArray(16)))
        assertFalse(MediaDrmDetector.isDrmProtected(plainMp4))

        val psshMp4 = ftyp + box("moov", box("pssh", ByteArray(24)))
        assertTrue(MediaDrmDetector.isDrmProtected(psshMp4))

        val stsdHeader = ByteArray(8)
        val encvMp4 = ftyp + box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stsd", stsdHeader + box("encv", ByteArray(8))))))))
        assertTrue(MediaDrmDetector.isDrmProtected(encvMp4))

        val ebmlHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x84.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
        val plainMkv = ebmlHeader + byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x84.toByte(), 0x16, 0x54, 0xAE.toByte(), 0x6B)
        assertFalse(MediaDrmDetector.isDrmProtected(plainMkv))

        val contentEnc = byteArrayOf(0x50, 0x35, 0x81.toByte(), 0x05)
        val encoding = byteArrayOf(0x62, 0x40, (0x80 or contentEnc.size).toByte()) + contentEnc
        val encodings = byteArrayOf(0x6D, 0x80.toByte(), (0x80 or encoding.size).toByte()) + encoding
        val trackEntry = byteArrayOf(0xAE.toByte(), (0x80 or encodings.size).toByte()) + encodings
        val tracks = byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B, (0x80 or trackEntry.size).toByte()) + trackEntry
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, (0x80 or tracks.size).toByte()) + tracks
        assertTrue(MediaDrmDetector.isDrmProtected(ebmlHeader + segment))

        val movWithoutFtyp = box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stsd", stsdHeader + box("drms", ByteArray(8))))))))
        assertTrue(MediaDrmDetector.isDrmProtected(movWithoutFtyp))

        val asfHeaderGuid = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C
        )
        val asfDrmGuid = byteArrayOf(
            0xFB.toByte(), 0xB3.toByte(), 0x11, 0x22, 0x23, 0xBD.toByte(), 0xD2.toByte(), 0x11,
            0xB4.toByte(), 0xB7.toByte(), 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x55, 0xFC.toByte(), 0x6E
        )
        val plainAsf = asfHeaderGuid + ByteArray(48)
        assertFalse(MediaDrmDetector.isDrmProtected(plainAsf))
        val drmAsf = asfHeaderGuid + ByteArray(14) + asfDrmGuid + ByteArray(16)
        assertTrue(MediaDrmDetector.isDrmProtected(drmAsf))
    }

    @Test
    fun odfModernAndOpenPgpEncryptionSchemesReportUnsupportedEncryption() {
        val argonManifest = """
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
              <manifest:file-entry manifest:full-path="content.xml" manifest:size="128">
                <manifest:encryption-data manifest:checksum-type="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k" manifest:checksum="AAAA">
                  <manifest:algorithm manifest:algorithm-name="http://www.w3.org/2009/xmlenc11#aes256-gcm" manifest:initialisation-vector="AAAAAAAAAAAAAAAA"/>
                  <manifest:key-derivation manifest:key-derivation-name="urn:org:documentfoundation:names:experimental:office:manifest:argon2id" manifest:salt="AAAAAAAAAAAAAAAA"/>
                </manifest:encryption-data>
              </manifest:file-entry>
            </manifest:manifest>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val entries = mapOf(
            "META-INF/manifest.xml" to argonManifest,
            "content.xml" to ByteArray(64)
        )
        assertTrue(CleanRoomOfficeCrypto.isEncryptedOdfManifest(argonManifest))
        try {
            CleanRoomOfficeCrypto.decryptOdfEntries(entries, "secret")
            fail("Expected UNSUPPORTED_ENCRYPTION for Argon2id/AES-GCM ODF")
        } catch (ex: SecurityException) {
            assertTrue(ex.message?.startsWith("UNSUPPORTED_ENCRYPTION:") == true)
        }

        val pgpManifest = """
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
              <manifest:encrypted-key>
                <manifest:encryption-method manifest:PGPAlgorithm="http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p"/>
              </manifest:encrypted-key>
              <manifest:file-entry manifest:full-path="content.xml">
                <manifest:encryption-data>
                  <manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="AAAAAAAAAAAAAAAAAAAAAA=="/>
                </manifest:encryption-data>
              </manifest:file-entry>
            </manifest:manifest>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val pgpEntries = mapOf(
            "META-INF/manifest.xml" to pgpManifest,
            "content.xml" to ByteArray(64)
        )
        assertTrue(CleanRoomOfficeCrypto.isEncryptedOdfManifest(pgpManifest))
        try {
            CleanRoomOfficeCrypto.decryptOdfEntries(pgpEntries, "secret")
            fail("Expected UNSUPPORTED_ENCRYPTION for OpenPGP ODF")
        } catch (ex: SecurityException) {
            assertTrue(ex.message?.startsWith("UNSUPPORTED_ENCRYPTION:") == true)
        }
    }

    @Test
    fun duckDbAndOneNoteEncryptionDetectorsFlagEncryptedHeaders() {
        val duckPlain = ByteArray(64)
        "DUCK".toByteArray(StandardCharsets.US_ASCII).copyInto(duckPlain, 8)
        assertFalse(CleanRoomDuckDbReader.isEncrypted(duckPlain))
        val duckEnc = duckPlain.copyOf()
        duckEnc[20] = 0x01
        assertTrue(CleanRoomDuckDbReader.isEncrypted(duckEnc))

        val onePlain = ByteArray(64)
        assertFalse(OneNoteEncryptionDetector.isEncrypted(onePlain))
        val oneEnc = ByteArray(64)
        val guid = byteArrayOf(
            0xF8.toByte(), 0x17, 0x37, 0x4A, 0x14, 0x1C, 0xE7.toByte(), 0x49,
            0x95.toByte(), 0x26, 0x81.toByte(), 0xD9.toByte(), 0x42, 0xDE.toByte(), 0x17, 0x41
        )
        guid.copyInto(oneEnc, 16)
        assertTrue(OneNoteEncryptionDetector.isEncrypted(oneEnc))
    }

    private fun buildMinimalOle2WithEntries(names: List<String>): ByteArray {
        val buf = ByteArray(1536)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, -0x1FEE3030)
        bb.putInt(4, -0x1EE54E5F)
        bb.putShort(24, 0x003E)
        bb.putShort(26, 0x0003)
        bb.putShort(28, 0xFFFE.toShort())
        bb.putShort(30, 9)
        bb.putShort(32, 6)
        bb.putInt(44, 1)
        bb.putInt(48, 1)
        bb.putInt(56, 4096)
        bb.putInt(60, -2)
        bb.putInt(68, -2)
        bb.putInt(76, 0)
        for (i in 1 until 109) bb.putInt(76 + i * 4, -1)

        bb.putInt(512 + 0, -3)
        bb.putInt(512 + 4, -2)
        for (i in 2 until 128) bb.putInt(512 + i * 4, -1)

        for ((idx, name) in names.withIndex()) {
            val base = 1024 + idx * 128
            val utf16 = name.toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(utf16, 0, buf, base, minOf(utf16.size, 62))
            bb.putShort(base + 64, (utf16.size + 2).toShort())
            buf[base + 66] = if (idx == 0) 5 else 2
            buf[base + 67] = 1
            bb.putInt(base + 68, -1)
            bb.putInt(base + 72, if (idx + 1 < names.size && idx > 0) idx + 1 else -1)
            bb.putInt(base + 76, if (idx == 0 && names.size > 1) 1 else -1)
            bb.putInt(base + 116, -2)
            bb.putInt(base + 120, 0)
        }
        return buf
    }
}
