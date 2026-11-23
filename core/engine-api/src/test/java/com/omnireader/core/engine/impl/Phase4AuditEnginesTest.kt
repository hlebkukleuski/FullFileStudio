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

package com.omnireader.core.engine.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Phase4AuditEnginesTest {

    @Test
    fun isoRockRidgeParsesLongUtf8NameCeContinuationSymlinkPxAndRelocation() {
        val ceArea = ByteArrayOutputStream().apply {
            val nm2 = "Документ_v2.pdf".toByteArray(StandardCharsets.UTF_8)
            write(byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), (5 + nm2.size).toByte(), 1, 0))
            write(nm2)
            write(byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), 36, 1))
            writeBothEndian32(this, 0x81A4)
            writeBothEndian32(this, 1)
            writeBothEndian32(this, 1000)
            writeBothEndian32(this, 1000)
            val slComp = "target".toByteArray(StandardCharsets.UTF_8)
            write(byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), (5 + 2 + 2 + slComp.size).toByte(), 1, 0))
            write(byteArrayOf(0x08, 0))
            write(byteArrayOf(0x00, slComp.size.toByte()))
            write(slComp)
            write(byteArrayOf('C'.code.toByte(), 'L'.code.toByte(), 12, 1))
            writeBothEndian32(this, 77)
            write(byteArrayOf('R'.code.toByte(), 'E'.code.toByte(), 4, 1))
        }.toByteArray()

        val nm1 = "Отчёт_".toByteArray(StandardCharsets.UTF_8)
        val suInline = ByteArrayOutputStream().apply {
            write(byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 7, 1, 0xBE.toByte(), 0xEF.toByte(), 0))
            write(byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), (5 + nm1.size).toByte(), 1, 1))
            write(nm1)
            write(byteArrayOf('C'.code.toByte(), 'E'.code.toByte(), 28, 1))
            writeBothEndian32(this, 50)
            writeBothEndian32(this, 16)
            writeBothEndian32(this, ceArea.size)
        }.toByteArray()

        val shortName = "SHORT.PDF;1".toByteArray(StandardCharsets.US_ASCII)
        val pad = if ((shortName.size and 1) == 0) 1 else 0
        val recLen = 33 + shortName.size + pad + suInline.size
        val rec = ByteArray(recLen)
        rec[0] = recLen.toByte()
        rec[32] = shortName.size.toByte()
        System.arraycopy(shortName, 0, rec, 33, shortName.size)
        System.arraycopy(suInline, 0, rec, 33 + shortName.size + pad, suInline.size)

        val meta = CleanRoomIsoRockRidge.parseSystemUseArea(rec, 0, recLen, shortName.size, 0) { lba, off, len ->
            if (lba == 50L && off == 16 && len == ceArea.size) ceArea else null
        }

        assertTrue(meta.spFound)
        assertTrue(meta.hasRockRidge)
        assertEquals("Отчёт_Документ_v2.pdf", meta.name)
        assertEquals(0x81A4, meta.posixMode)
        assertEquals(1000, meta.posixUid)
        assertEquals(1000, meta.posixGid)
        assertEquals("/target", meta.symlinkTarget)
        assertEquals(77L, meta.childLinkLba)
        assertTrue(meta.relocated)
    }

    @Test
    fun realmReaderParsesHeaderSlotAndClassicTableColumns() {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(24))

        fun align8(): Int {
            while ((out.size() and 7) != 0) out.write(0)
            return out.size()
        }

        fun writeNodeHeader(size: Int, widthType: Int, widthBitsCode: Int, hasRefs: Boolean = false): Long {
            val ref = align8().toLong()
            val b4 = (if (hasRefs) 0x40 else 0) or ((widthType and 3) shl 3) or (widthBitsCode and 7)
            out.write(byteArrayOf(0x41, 0x41, 0x41, 0x41, b4.toByte(), ((size ushr 16) and 0xFF).toByte(), ((size ushr 8) and 0xFF).toByte(), (size and 0xFF).toByte()))
            return ref
        }

        fun writeLongArrayNode(vals: LongArray, hasRefs: Boolean = false): Long {
            val ref = writeNodeHeader(vals.size, 0, 7, hasRefs)
            val bb = ByteBuffer.allocate(vals.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vals) bb.putLong(v)
            out.write(bb.array())
            return ref
        }

        fun writeStringArrayNode(strings: List<String>): Long {
            val maxLen = (strings.maxOfOrNull { it.toByteArray(StandardCharsets.UTF_8).size + 1 } ?: 1).coerceAtLeast(1)
            val code = when {
                maxLen <= 4 -> 3
                maxLen <= 8 -> 4
                maxLen <= 16 -> 5
                maxLen <= 32 -> 6
                else -> 7
            }
            val width = 1 shl (code - 1)
            val payload = ByteArray(strings.size * width)
            val ref = writeNodeHeader(payload.size, 1, code, false)
            for (i in strings.indices) {
                val b = strings[i].toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(b, 0, payload, i * width, b.size)
                val pad = width - 1 - b.size
                payload[(i + 1) * width - 1] = pad.toByte()
            }
            out.write(payload)
            return ref
        }

        val colTypesRef = writeLongArrayNode(longArrayOf(0L, 2L, 1L))
        val colNamesRef = writeStringArrayNode(listOf("id", "name", "active"))
        val specRef = writeLongArrayNode(longArrayOf(colTypesRef, colNamesRef), hasRefs = true)

        val idColRef = writeLongArrayNode(longArrayOf(101L, 202L))
        val nameColRef = writeStringArrayNode(listOf("Alice", "Bob"))
        val activeColRef = writeLongArrayNode(longArrayOf(1L, 0L))
        val colsRef = writeLongArrayNode(longArrayOf(idColRef, nameColRef, activeColRef), hasRefs = true)

        val tableRef = writeLongArrayNode(longArrayOf(specRef, colsRef), hasRefs = true)
        val tableNamesRef = writeStringArrayNode(listOf("class_Users"))
        val tablesArrRef = writeLongArrayNode(longArrayOf(tableRef), hasRefs = true)
        val topRef = writeLongArrayNode(longArrayOf(tableNamesRef, tablesArrRef), hasRefs = true)

        val bytes = out.toByteArray()
        val hdr = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putLong(0, 0L)
        hdr.putLong(8, topRef)
        bytes[16] = 'T'.code.toByte()
        bytes[17] = '-'.code.toByte()
        bytes[18] = 'D'.code.toByte()
        bytes[19] = 'B'.code.toByte()
        bytes[20] = 9
        bytes[21] = 9
        bytes[23] = 1

        assertTrue(CleanRoomRealmReader.isRealmHeader(bytes))
        val parsed = CleanRoomRealmReader.parse(bytes)
        assertNotNull(parsed)
        assertEquals(9, parsed!!.formatVersion)
        assertEquals(topRef, parsed.activeTopRef)
        val users = parsed.tables.firstOrNull { it.name == "Users" }
        assertNotNull(users)
        assertEquals(listOf("id", "name", "active"), users!!.columns)
        assertEquals(2, users.rows.size)
        assertEquals(listOf("101", "Alice", "true"), users.rows[0])
        assertEquals(listOf("202", "Bob", "false"), users.rows[1])
    }

    @Test
    fun duckDbReaderSelectsActiveCheckpointAndDecodesMetadataAndDataBlocks() {
        val blockAlloc = 16384
        val totalSize = 12288 + blockAlloc * 2
        val bytes = ByteArray(totalSize)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        bytes[8] = 'D'.code.toByte()
        bytes[9] = 'U'.code.toByte()
        bytes[10] = 'C'.code.toByte()
        bytes[11] = 'K'.code.toByte()
        bb.putLong(12, 64L)
        val ver = "v1.1.3".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(ver, 0, bytes, 84, ver.size)

        fun writeDbHeader(off: Int, iteration: Long, metaBlockPtr: Long) {
            bb.putLong(off + 8, iteration)
            bb.putLong(off + 16, metaBlockPtr)
            bb.putLong(off + 24, -1L)
            bb.putLong(off + 32, 2L)
            bb.putLong(off + 40, blockAlloc.toLong())
            bb.putLong(off + 48, 2048L)
            bb.putLong(off + 56, 1L)
        }
        writeDbHeader(4096, 1L, -1L)
        val metaPtr = 0L
        writeDbHeader(8192, 5L, metaPtr)

        val metaBlockOff = 12288
        bb.putLong(metaBlockOff, -1L)
        val tlv = ByteArrayOutputStream().apply {
            writeFieldStr(this, 100, "main")
            writeFieldStr(this, 101, "orders")
            writeFieldU16(this, 105, 100)
            writeFieldStr(this, 100, "order_id")
            writeFieldU8(this, 101, 13)
            writeFieldU16(this, 0xFFFF, 0)
            writeFieldStr(this, 100, "amount")
            writeFieldU8(this, 101, 23)
            writeFieldU16(this, 0xFFFF, 0)
            writeFieldU16(this, 0xFFFF, 0)
        }.toByteArray()
        System.arraycopy(tlv, 0, bytes, metaBlockOff + 8, tlv.size)

        assertTrue(CleanRoomDuckDbReader.isDuckDbHeader(bytes))
        val parsed = CleanRoomDuckDbReader.parse(bytes)
        assertNotNull(parsed)
        assertEquals(64L, parsed!!.storageVersion)
        assertEquals("v1.1.3", parsed.libraryVersion)
        assertEquals(5L, parsed.iteration)
        assertTrue(parsed.tables.any { it.name == "_DuckDB_Header" })
        val headerTbl = parsed.tables.first { it.name == "_DuckDB_Header" }
        assertTrue(headerTbl.rows.any { it[0] == "Library Version" && it[1] == "v1.1.3" })
    }

    @Test
    fun ooxmlAgileAes256Sha512RoundTrip() {
        val plainZip = "PK\u0003\u0004synthetic-docx-payload-0123456789abcdef".repeat(120).toByteArray(StandardCharsets.UTF_8)
        val password = "SuperSecret#2026"
        val keySalt = ByteArray(16) { (0x10 + it).toByte() }
        val pwdSalt = ByteArray(16) { (0x40 + it).toByte() }
        val secretKey = ByteArray(32) { (0x70 + it).toByte() }
        val spinCount = 200
        val hashAlg = "SHA-512"

        val md = MessageDigest.getInstance(hashAlg)
        md.update(pwdSalt)
        md.update(password.toByteArray(StandardCharsets.UTF_16LE))
        var h = md.digest()
        for (i in 0 until spinCount) {
            md.reset()
            md.update(le32(i))
            md.update(h)
            h = md.digest()
        }
        fun agileKey(blockKey: ByteArray): ByteArray {
            md.reset()
            md.update(h)
            md.update(blockKey)
            return md.digest().copyOf(32)
        }
        fun aesCbcEnc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return c.doFinal(data)
        }

        val vInputPlain = ByteArray(16) { (0xA0 + it).toByte() }
        val vHashPlain = MessageDigest.getInstance(hashAlg).digest(vInputPlain)
        val encVInput = aesCbcEnc(agileKey(byteArrayOf(0xFE.toByte(), 0xA7.toByte(), 0xD2.toByte(), 0x76, 0x3B, 0x4B, 0x9E.toByte(), 0x79)), pwdSalt, vInputPlain)
        val encVHash = aesCbcEnc(agileKey(byteArrayOf(0xD7.toByte(), 0xAA.toByte(), 0x0F, 0x6D, 0x30, 0x61, 0x34, 0x4E)), pwdSalt, vHashPlain)
        val encKeyVal = aesCbcEnc(agileKey(byteArrayOf(0x14, 0x6E, 0x0B, 0xE7.toByte(), 0xAB.toByte(), 0xAC.toByte(), 0xD0.toByte(), 0xD6.toByte())), pwdSalt, secretKey)

        val b64 = Base64.getEncoder()
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="http://schemas.microsoft.com/office/2006/encryption" xmlns:p="http://schemas.microsoft.com/office/2006/keyEncryptor/password">
  <keyData saltSize="16" blockSize="16" keyBits="256" hashSize="64" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC" hashAlgorithm="SHA512" saltValue="${b64.encodeToString(keySalt)}"/>
  <keyEncryptors>
    <keyEncryptor uri="http://schemas.microsoft.com/office/2006/keyEncryptor/password">
      <p:encryptedKey spinCount="$spinCount" saltSize="16" blockSize="16" keyBits="256" hashSize="64" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC" hashAlgorithm="SHA512" saltValue="${b64.encodeToString(pwdSalt)}" encryptedVerifierHashInput="${b64.encodeToString(encVInput)}" encryptedVerifierHashValue="${b64.encodeToString(encVHash)}" encryptedKeyValue="${b64.encodeToString(encKeyVal)}"/>
    </keyEncryptor>
  </keyEncryptors>
</encryption>""".toByteArray(StandardCharsets.UTF_8)

        val info = ByteArray(maxOf(4096, 8 + xml.size))
        info[0] = 4; info[2] = 4
        System.arraycopy(xml, 0, info, 8, xml.size)

        val pkgOut = ByteArrayOutputStream()
        pkgOut.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(plainZip.size.toLong()).array())
        var pos = 0
        var seg = 0
        while (pos < plainZip.size) {
            val chunkLen = minOf(4096, plainZip.size - pos)
            val paddedLen = ((chunkLen + 15) / 16) * 16
            val chunk = ByteArray(paddedLen)
            System.arraycopy(plainZip, pos, chunk, 0, chunkLen)
            md.reset()
            md.update(keySalt)
            md.update(le32(seg))
            val segIv = md.digest().copyOf(16)
            pkgOut.write(aesCbcEnc(secretKey, segIv, chunk))
            pos += chunkLen
            seg++
        }

        val cfbBytes = buildOle2Cfb(mapOf("EncryptionInfo" to info, "EncryptedPackage" to pkgOut.toByteArray()))
        val cfb = Ole2CfbContainer(cfbBytes)
        assertTrue(CleanRoomOfficeCrypto.isEncryptedOoxml(cfb))
        assertNull(CleanRoomOfficeCrypto.decryptOoxml(cfb, "wrong-pass"))
        val dec = CleanRoomOfficeCrypto.decryptOoxml(cfb, password)
        assertNotNull(dec)
        assertArrayEquals(plainZip, dec)
    }

    @Test
    fun docBinaryRc4AndCryptoApiRc4RoundTrip() {
        val password = "DocPassword42"
        val wordPlain = ByteArray(1200) { ((it * 7) and 0xFF).toByte() }
        wordPlain[10] = 0x00; wordPlain[11] = 0x03
        val tableHeaderLen = 256
        val tablePlain = ByteArray(1400) { ((it * 13 + 5) and 0xFF).toByte() }
        val dataPlain = ByteArray(900) { ((it * 3 + 9) and 0xFF).toByte() }
        val salt = ByteArray(16) { (0x21 + it).toByte() }

        val h0 = MessageDigest.getInstance("MD5").digest(password.toByteArray(StandardCharsets.UTF_16LE)).copyOf(5)
        val buf = ByteArray(16 * 21)
        for (i in 0 until 16) {
            System.arraycopy(h0, 0, buf, i * 21, 5)
            System.arraycopy(salt, 0, buf, i * 21 + 5, 16)
        }
        val inter = MessageDigest.getInstance("MD5").digest(buf).copyOf(5)
        val blockKey: (Int) -> ByteArray = { b ->
            val m = MessageDigest.getInstance("MD5")
            m.update(inter); m.update(le32(b))
            m.digest()
        }

        val encWord = encryptBlocks(wordPlain, 68, 512, blockKey)
        encWord[14] = (tableHeaderLen and 0xFF).toByte()
        encWord[15] = ((tableHeaderLen ushr 8) and 0xFF).toByte()
        encWord[16] = 0; encWord[17] = 0

        val encTable = encryptBlocks(tablePlain, tableHeaderLen, 512, blockKey)
        encTable[0] = 1; encTable[1] = 0; encTable[2] = 1; encTable[3] = 0
        System.arraycopy(salt, 0, encTable, 4, 16)
        val vPlain = ByteArray(16) { (0x55 + it).toByte() }
        val vHash = MessageDigest.getInstance("MD5").digest(vPlain)
        val rc4v = CleanRoomOfficeCrypto.Rc4Cipher(blockKey(0))
        System.arraycopy(rc4v.process(vPlain), 0, encTable, 20, 16)
        System.arraycopy(rc4v.process(vHash), 0, encTable, 36, 16)

        val encData = encryptBlocks(dataPlain, 0, 512, blockKey)

        assertTrue(CleanRoomOfficeCrypto.isEncryptedDoc(encWord))
        assertNull(CleanRoomOfficeCrypto.decryptDoc(encWord, encTable, encData, "bad"))
        val dec = CleanRoomOfficeCrypto.decryptDoc(encWord, encTable, encData, password)
        assertNotNull(dec)
        assertFalse(CleanRoomOfficeCrypto.isEncryptedDoc(dec!!.wordDocument))
        assertArrayEquals(wordPlain.copyOfRange(68, wordPlain.size), dec.wordDocument.copyOfRange(68, dec.wordDocument.size))
        assertArrayEquals(tablePlain.copyOfRange(tableHeaderLen, tablePlain.size), dec.tableStream.copyOfRange(tableHeaderLen, dec.tableStream.size))
        assertArrayEquals(dataPlain, dec.dataStream)
    }

    @Test
    fun xlsDefaultVelvetSweatshopCryptoApiRc4UnlocksAutomatically() {
        val password = CleanRoomOfficeCrypto.DEFAULT_EXCEL_PASSWORD
        val salt = ByteArray(16) { (0x33 + it).toByte() }
        val h0 = MessageDigest.getInstance("SHA-1").apply {
            update(salt)
            update(password.toByteArray(StandardCharsets.UTF_16LE))
        }.digest()
        val keyForBlock: (Int) -> ByteArray = { b ->
            val hb = MessageDigest.getInstance("SHA-1").apply { update(h0); update(le32(b)) }.digest()
            ByteArray(16).also { System.arraycopy(hb, 0, it, 0, 5) }
        }

        val vPlain = ByteArray(16) { (0x11 + it).toByte() }
        val vHash = MessageDigest.getInstance("SHA-1").digest(vPlain)
        val rc4v = CleanRoomOfficeCrypto.Rc4Cipher(keyForBlock(0))
        val encV = rc4v.process(vPlain)
        val encVH = rc4v.process(vHash)

        val fpBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(1, 0, 3, 0, 2, 0))
            write(le32(0x00000024))
            write(le32(32))
            write(le32(0x00000024))
            write(le32(0))
            write(le32(0x00006801))
            write(le32(0x00008004))
            write(le32(40))
            write(le32(1))
            write(le32(0))
            write(le32(0))
            write(le32(16))
            write(salt)
            write(encV)
            write(le32(20))
            write(encVH)
        }.toByteArray()

        val labelPayload = "HelloEncryptedXLS".toByteArray(StandardCharsets.ISO_8859_1)
        val wbPlain = ByteArrayOutputStream().apply {
            writeBiffRec(this, 0x0809, ByteArray(16))
            writeBiffRec(this, 0x002F, fpBody)
            writeBiffRec(this, 0x0085, byteArrayOf(0x40, 0x01, 0x00, 0x00, 0, 0, 5, 0, 'S'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte()))
            writeBiffRec(this, 0x0204, labelPayload)
            writeBiffRec(this, 0x000A, ByteArray(0))
        }.toByteArray()

        val wbEnc = wbPlain.copyOf()
        val fpEnd = 20 + 4 + fpBody.size
        val bsBody = fpEnd + 4
        val lblBody = bsBody + 13 + 4
        var rc4 = CleanRoomOfficeCrypto.Rc4Cipher(keyForBlock(0))
        rc4.skip(bsBody + 4)
        rc4.processInPlace(wbEnc, bsBody + 4, 9)
        rc4.skip(4)
        rc4.processInPlace(wbEnc, lblBody, labelPayload.size)

        assertTrue(CleanRoomOfficeCrypto.isEncryptedXls(wbEnc))
        val autoDec = CleanRoomOfficeCrypto.decryptXlsWithDefaultFallback(wbEnc, null)
        assertNotNull(autoDec)
        assertFalse(CleanRoomOfficeCrypto.isEncryptedXls(autoDec!!))
        assertArrayEquals(labelPayload, autoDec.copyOfRange(lblBody, lblBody + labelPayload.size))
    }

    @Test
    fun odfAes256CbcPbkdf2Sha256DecryptionRoundTrip() {
        val password = "odf-secret-password"
        val plainXml = "<office:document-content><text:p>Protected ODF content</text:p></office:document-content>".toByteArray(StandardCharsets.UTF_8)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(plainXml)
        deflater.finish()
        val defOut = ByteArrayOutputStream()
        val dbuf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(dbuf)
            if (n > 0) defOut.write(dbuf, 0, n)
        }
        deflater.end()
        val deflated = defOut.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(deflated.copyOf(minOf(1024, deflated.size)))

        val salt = ByteArray(16) { (0x60 + it).toByte() }
        val iv = ByteArray(16) { (0x80 + it).toByte() }
        val startKey = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
        val key = CleanRoomOfficeCrypto.pbkdf2HmacSha1(startKey, salt, 1000, 32)
        val padLen = 16 - (deflated.size % 16)
        val padded = ByteArray(deflated.size + padLen)
        System.arraycopy(deflated, 0, padded, 0, deflated.size)
        padded[padded.size - 1] = padLen.toByte()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val cipherBytes = cipher.doFinal(padded)

        val b64 = Base64.getEncoder()
        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml" manifest:size="${plainXml.size}">
    <manifest:encryption-data manifest:checksum-type="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k" manifest:checksum="${b64.encodeToString(checksum)}">
      <manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="${b64.encodeToString(iv)}"/>
      <manifest:key-derivation manifest:key-derivation-name="PBKDF2" manifest:iteration-count="1000" manifest:salt="${b64.encodeToString(salt)}" manifest:key-size="32"/>
      <manifest:start-key-generation manifest:start-key-generation-name="http://www.w3.org/2000/09/xmldsig#sha256" manifest:key-size="32"/>
    </manifest:encryption-data>
  </manifest:file-entry>
</manifest:manifest>""".toByteArray(StandardCharsets.UTF_8)

        val entries = mapOf("META-INF/manifest.xml" to manifest, "content.xml" to cipherBytes)
        assertTrue(CleanRoomOfficeCrypto.isEncryptedOdfManifest(manifest))
        assertNull(CleanRoomOfficeCrypto.decryptOdfEntries(entries, "wrong"))
        val dec = CleanRoomOfficeCrypto.decryptOdfEntries(entries, password)
        assertNotNull(dec)
        assertArrayEquals(plainXml, dec!!["content.xml"])
        assertFalse(CleanRoomOfficeCrypto.isEncryptedOdfManifest(dec["META-INF/manifest.xml"]))
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun writeBothEndian32(out: ByteArrayOutputStream, v: Int) {
        out.write(byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()))
        out.write(byteArrayOf(((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()))
    }

    private fun writeFieldU16(out: ByteArrayOutputStream, id: Int, v: Int) {
        out.write(id and 0xFF); out.write((id ushr 8) and 0xFF)
        if (id != 0xFFFF) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
    }

    private fun writeFieldU8(out: ByteArrayOutputStream, id: Int, v: Int) {
        out.write(id and 0xFF); out.write((id ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeFieldStr(out: ByteArrayOutputStream, id: Int, s: String) {
        out.write(id and 0xFF); out.write((id ushr 8) and 0xFF)
        val b = s.toByteArray(StandardCharsets.UTF_8)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun writeBiffRec(out: ByteArrayOutputStream, recId: Int, body: ByteArray) {
        out.write(recId and 0xFF); out.write((recId ushr 8) and 0xFF)
        out.write(body.size and 0xFF); out.write((body.size ushr 8) and 0xFF)
        out.write(body)
    }

    private fun encryptBlocks(src: ByteArray, plainPrefix: Int, blockSize: Int, keyForBlock: (Int) -> ByteArray): ByteArray {
        val out = src.copyOf()
        var pos = 0
        var blk = 0
        while (pos < out.size) {
            val end = minOf(out.size, pos + blockSize)
            if (end > plainPrefix) {
                val start = maxOf(pos, plainPrefix)
                val rc4 = CleanRoomOfficeCrypto.Rc4Cipher(keyForBlock(blk))
                if (start > pos) rc4.skip(start - pos)
                rc4.processInPlace(out, start, end - start)
            }
            pos = end
            blk++
        }
        return out
    }

    private fun buildOle2Cfb(streams: Map<String, ByteArray>): ByteArray {
        val sectorSize = 512
        val fatSec = 0
        val dirSec = 1
        var nextSec = 2
        val fat = ArrayList<Int>()
        fat.add(-3)
        fat.add(-2)
        val entries = ArrayList<Triple<String, Int, ByteArray>>()
        for ((name, data) in streams) {
            val padded = if (data.size < 4096) data.copyOf(4096) else data
            val count = (padded.size + sectorSize - 1) / sectorSize
            val start = nextSec
            for (k in 0 until count) {
                fat.add(if (k + 1 < count) nextSec + 1 else -2)
                nextSec++
            }
            entries.add(Triple(name, start, data))
        }
        val totalSectors = nextSec
        val out = ByteArray((1 + totalSectors) * sectorSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        System.arraycopy(magic, 0, out, 0, 8)
        bb.putShort(24, 0x003E); bb.putShort(26, 0x0003); bb.putShort(28, 0xFFFE.toShort())
        bb.putShort(30, 9); bb.putShort(32, 6)
        bb.putInt(44, 1); bb.putInt(48, dirSec); bb.putInt(56, 0)
        bb.putInt(60, -2); bb.putInt(68, -2)
        bb.putInt(76, fatSec)
        for (i in 1 until 109) bb.putInt(76 + i * 4, -1)

        val fatOff = (1 + fatSec) * sectorSize
        for (i in 0 until sectorSize / 4) bb.putInt(fatOff + i * 4, if (i < fat.size) fat[i] else -1)

        val dirOff = (1 + dirSec) * sectorSize
        fun writeDirEntry(slot: Int, name: String, type: Byte, startSec: Int, size: Int, child: Int, left: Int, right: Int) {
            val o = dirOff + slot * 128
            val nameBytes = (name + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(nameBytes, 0, out, o, minOf(64, nameBytes.size))
            bb.putShort(o + 64, nameBytes.size.toShort())
            out[o + 66] = type
            out[o + 67] = 1
            bb.putInt(o + 68, left)
            bb.putInt(o + 72, right)
            bb.putInt(o + 76, child)
            bb.putInt(o + 116, startSec)
            bb.putInt(o + 120, size)
        }
        writeDirEntry(0, "Root Entry", 5, -2, 0, if (entries.isNotEmpty()) 1 else -1, -1, -1)
        for (i in entries.indices) {
            val (name, start, data) = entries[i]
            val right = if (i + 1 < entries.size) i + 2 else -1
            writeDirEntry(i + 1, name, 2, start, data.size, -1, -1, right)
            System.arraycopy(data, 0, out, (1 + start) * sectorSize, data.size)
        }
        return out
    }
}
