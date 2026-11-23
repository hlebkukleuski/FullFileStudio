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

package me.hlebkukleuski.fullfilestudio.feature.archives

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class Rar4ParserTest {

    private val dir = File(javaClass.classLoader!!.getResource("rar4/test_read_format_rar_windows.rar")!!.toURI()).parentFile!!

    private fun parse(bytes: ByteArray) = Rar4Parser.parseEntries(
        CleanRoomRar5Parser.PositionalReader { offset, buffer ->
            if (offset >= bytes.size) -1 else {
                val n = minOf(buffer.remaining().toLong(), bytes.size - offset).toInt()
                buffer.put(bytes, offset.toInt(), n)
                n
            }
        },
        bytes.size.toLong()
    )

    private fun unpack(bytes: ByteArray, all: List<Rar4Parser.Rar4FileEntry>, e: Rar4Parser.Rar4FileEntry): ByteArray =
        Rar4Parser.unpackEntry(all, e, 64L * 1024 * 1024) {
            bytes.copyOfRange(it.dataOffset.toInt(), (it.dataOffset + it.packedSize).toInt())
        }

    @Test
    fun unpacksNormalBestMultiBlockWindowsAndSubblockRar4WithExactCrc() {
        val sampleNames = listOf(
            "test_read_format_rar_compress_normal.rar",
            "test_read_format_rar_compress_best.rar",
            "test_read_format_rar_multi_lzss_blocks.rar",
            "test_read_format_rar_windows.rar",
            "test_read_format_rar_subblock.rar"
        )
        var verified = 0
        for (name in sampleNames) {
            val bytes = File(dir, name).readBytes()
            val parsed = parse(bytes)
            assertFalse(name, parsed.isHeaderEncrypted)
            val files = parsed.entries.filter { !it.isDirectory }
            assertTrue(name, files.isNotEmpty())
            for (e in files) {
                val out = unpack(bytes, parsed.entries, e)
                assertEquals("$name/${e.rawName}", e.unpackedSize, out.size.toLong())
                val crc = CRC32().apply { update(out) }.value.toInt()
                assertEquals("$name/${e.rawName} CRC", e.dataCrc32, crc)
                verified++
            }
        }
        assertTrue("verified $verified entries", verified >= 5)
    }

    @Test
    fun decodesUnicodeFilenamesFromLibarchiveFixture() {
        val bytes = File(dir, "test_read_format_rar_unicode.rar").readBytes()
        val parsed = parse(bytes)
        assertFalse(parsed.isHeaderEncrypted)
        assertTrue(parsed.entries.isNotEmpty())
        assertTrue(parsed.entries.any { e -> e.rawName.any { it.code > 127 } })
        for (e in parsed.entries.filter { !it.isDirectory }) {
            val out = unpack(bytes, parsed.entries, e)
            assertEquals(e.unpackedSize, out.size.toLong())
        }
    }

    @Test
    fun detectsHeaderAndFileEncryptionWithoutBypass() {
        val hdrBytes = File(dir, "test_read_format_rar_encryption_header.rar").readBytes()
        val hdrParsed = parse(hdrBytes)
        assertTrue(hdrParsed.isHeaderEncrypted)

        val dataBytes = File(dir, "test_read_format_rar_encryption_data.rar").readBytes()
        val dataParsed = parse(dataBytes)
        assertFalse(dataParsed.isHeaderEncrypted)
        val encrypted = dataParsed.entries.firstOrNull { it.isEncrypted }
        assertTrue(encrypted != null)
        try {
            unpack(dataBytes, dataParsed.entries, encrypted!!)
            fail("Expected UNSUPPORTED_ENCRYPTION SecurityException")
        } catch (ex: SecurityException) {
            assertTrue(ex.message?.startsWith("UNSUPPORTED_ENCRYPTION:") == true)
        }
    }

    @Test
    fun handlesMalformedRar4Safely() {
        val malformedNames = listOf(
            "test_read_format_rar_invalid1.rar",
            "test_read_format_rar_encryption_partially.rar",
            "test_read_format_rar_endarc_huge.rar",
            "test_read_format_rar_noeof.rar",
            "test_read_format_rar_overflow.rar",
            "test_read_format_rar_ppmd_use_after_free.rar",
            "test_read_format_rar_ppmd_use_after_free2.rar",
            "test_read_format_rar_symlink_huge.rar"
        )
        for (name in malformedNames) {
            val bytes = File(dir, name).readBytes()
            val parsed = parse(bytes)
            for (e in parsed.entries.filter { !it.isDirectory && it.dataOffset >= 0 && it.packedSize >= 0 && it.dataOffset + it.packedSize <= bytes.size }) {
                try {
                    unpack(bytes, parsed.entries, e)
                } catch (_: IllegalStateException) {
                } catch (_: SecurityException) {
                }
            }
        }
    }

    @Test
    fun parsesAndUnpacksSyntheticStoredRar4WithCrcVerification() {
        val payload = "FullFileStudio GPL-3.0 RAR4 stored payload!".toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val nameBytes = "hello.txt".toByteArray(StandardCharsets.UTF_8)
        val headSize = 32 + nameBytes.size

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x73, 0x00, 0x00, 0x0D, 0x00, 0, 0, 0, 0, 0, 0))
        val fh = ByteArray(headSize)
        fh[2] = 0x74
        fh[3] = 0x00
        fh[4] = 0x80.toByte()
        fh[5] = (headSize and 0xFF).toByte()
        fh[6] = ((headSize ushr 8) and 0xFF).toByte()
        writeLe32(fh, 7, payload.size)
        writeLe32(fh, 11, payload.size)
        writeLe32(fh, 16, crc)
        fh[24] = 29
        fh[25] = 0x30
        fh[26] = (nameBytes.size and 0xFF).toByte()
        fh[27] = ((nameBytes.size ushr 8) and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, fh, 32, nameBytes.size)
        out.write(fh)
        out.write(payload)
        out.write(byteArrayOf(0x00, 0x00, 0x7B, 0x00, 0x40, 0x07, 0x00))

        val bytes = out.toByteArray()
        val parsed = parse(bytes)
        assertFalse(parsed.isHeaderEncrypted)
        assertEquals(1, parsed.entries.size)
        assertEquals("hello.txt", parsed.entries[0].rawName)
        assertArrayEquals(payload, unpack(bytes, parsed.entries, parsed.entries[0]))
    }

    @Test
    fun handlesZeroByteSolidAndRejectsMultiVolumeSplitEntries() {
        val zeroEntry = Rar4Parser.Rar4FileEntry(
            rawName = "empty.txt",
            unpackedSize = 0L,
            packedSize = 0L,
            dataOffset = 64L,
            method = 0x33,
            unpVer = 29,
            dictSizeBytes = 65536,
            isSolid = true,
            isDirectory = false,
            isEncrypted = false,
            dataCrc32 = 0,
            isSplit = false
        )
        val unpacked = Rar4Parser.unpackEntry(listOf(zeroEntry), zeroEntry, 1024L) { ByteArray(0) }
        assertEquals(0, unpacked.size)

        val splitEntry = zeroEntry.copy(rawName = "part.bin", unpackedSize = 16L, isSplit = true)
        try {
            Rar4Parser.unpackEntry(listOf(splitEntry), splitEntry, 1024L) { ByteArray(16) }
            fail("Expected multi-volume IllegalStateException")
        } catch (ex: IllegalStateException) {
            assertTrue(ex.message?.contains("Multi-volume", ignoreCase = true) == true)
        }
    }

    private fun writeLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
