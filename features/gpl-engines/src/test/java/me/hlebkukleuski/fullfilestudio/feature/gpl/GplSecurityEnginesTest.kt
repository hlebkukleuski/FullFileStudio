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

package me.hlebkukleuski.fullfilestudio.feature.gpl

import me.hlebkukleuski.fullfilestudio.feature.gpl.access.AccessByteSource
import me.hlebkukleuski.fullfilestudio.feature.gpl.dwg.DwgReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgFormatException
import me.hlebkukleuski.fullfilestudio.feature.gpl.access.AccessDatabaseReader
import me.hlebkukleuski.fullfilestudio.feature.gpl.dwg.DwgContainer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GplSecurityEnginesTest {

    private fun rc4JetHeader(region: ByteArray): ByteArray {
        val key = byteArrayOf(0xC7.toByte(), 0xDA.toByte(), 0x39, 0x6B)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % 4].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = region.copyOf()
        var a = 0
        var b = 0
        for (k in out.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            out[k] = (out[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    private fun withJetPassword(mdbBytes: ByteArray, password: String): ByteArray {
        val copy = mdbBytes.copyOf()
        val dec = rc4JetHeader(copy.copyOfRange(0x18, 0x18 + 126))
        val isJet3 = copy[0x14].toInt() == 0
        if (isJet3) {
            val pwBytes = password.toByteArray(StandardCharsets.ISO_8859_1)
            for (i in 0 until 20) dec[0x42 - 0x18 + i] = if (i < pwBytes.size) pwBytes[i] else 0
        } else {
            val cDateDouble = ByteBuffer.wrap(dec, 0x72 - 0x18, 8).order(ByteOrder.LITTLE_ENDIAN).double
            val mask = cDateDouble.toInt() and 0xFFFF
            val pwUtf16 = ByteArray(40)
            val src = password.toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(src, 0, pwUtf16, 0, minOf(src.size, 40))
            var i = 0
            while (i < 40) {
                val w = ((pwUtf16[i].toInt() and 0xFF) or ((pwUtf16[i + 1].toInt() and 0xFF) shl 8)) xor mask
                pwUtf16[i] = (w and 0xFF).toByte()
                pwUtf16[i + 1] = ((w ushr 8) and 0xFF).toByte()
                i += 4
            }
            System.arraycopy(pwUtf16, 0, dec, 0x42 - 0x18, 40)
        }
        val enc = rc4JetHeader(dec)
        System.arraycopy(enc, 0, copy, 0x18, 126)
        return copy
    }

    private fun openAccess(bytes: ByteArray, password: String? = null) = AccessDatabaseReader(
        AccessByteSource { off, len -> bytes.copyOfRange(off.toInt(), off.toInt() + len) },
        bytes.size.toLong(),
        password
    )

    @Test
    fun accessJet3AndJet4RequireMatchingPasswordWhenSet() {
        val jet3File = File(javaClass.classLoader!!.getResource("access/delColTestV1997.mdb")!!.toURI())
        val jet4File = File(javaClass.classLoader!!.getResource("access/delColTestV2003.mdb")!!.toURI())

        for (file in listOf(jet3File, jet4File)) {
            val raw = file.readBytes()
            val unprotected = openAccess(raw)
            assertTrue(file.name, unprotected.tables.isNotEmpty())

            val locked = withJetPassword(raw, "SecRet#42")
            try {
                openAccess(locked, null)
                fail("${file.name} should refuse without password")
            } catch (ex: SecurityException) {
                assertTrue(ex.message?.startsWith("PASSWORD_REQUIRED:") == true)
            }
            try {
                openAccess(locked, "wrong")
                fail("${file.name} should refuse wrong password")
            } catch (ex: SecurityException) {
                assertTrue(ex.message?.startsWith("PASSWORD_REQUIRED:") == true)
            }
            val unlocked = openAccess(locked, "SecRet#42")
            assertTrue(file.name, unlocked.tables.size == unprotected.tables.size)

            val encPages = raw.copyOf()
            val encDec = rc4JetHeader(encPages.copyOfRange(0x18, 0x18 + 126))
            ByteBuffer.wrap(encDec).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3E - 0x18, 0x12345678)
            System.arraycopy(rc4JetHeader(encDec), 0, encPages, 0x18, 126)
            try {
                openAccess(encPages, null)
                fail("${file.name} should refuse non-zero encodingKey")
            } catch (ex: SecurityException) {
                assertTrue(ex.message?.startsWith("UNSUPPORTED_ENCRYPTION:") == true)
            }
            try {
                openAccess(encPages, "SecRet#42")
                fail("${file.name} should refuse non-zero encodingKey even with password")
            } catch (ex: SecurityException) {
                assertTrue(ex.message?.startsWith("UNSUPPORTED_ENCRYPTION:") == true)
            }
        }
    }

    @Test
    fun encryptedDwgReportsPasswordRequiredPrefix() {
        for (ver in listOf("AC1018", "AC1021", "AC1032")) {
            val bytes = ByteArray(0x200)
            System.arraycopy(ver.toByteArray(StandardCharsets.US_ASCII), 0, bytes, 0, 6)
            bytes[0x18] = 1
            try {
                DwgContainer.open(bytes)
                fail("Expected DwgFormatException for encrypted $ver")
            } catch (e: DwgFormatException) {
                assertTrue("Missing PASSWORD_REQUIRED prefix: ${e.message}", e.message?.startsWith("PASSWORD_REQUIRED:") == true)
            }
        }
    }
}
