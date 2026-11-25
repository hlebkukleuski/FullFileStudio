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

package me.hlebkukleuski.fullfilestudio.feature.gpl.mobi

import java.io.File
import java.util.Random
import me.hlebkukleuski.fullfilestudio.core.security.CorruptedDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiParserTest {

    private val dir = File(javaClass.classLoader!!.getResource("mobi/kf6.expected.html")!!.toURI()).parentFile!!

    private fun bytes(name: String) = File(dir, name).readBytes()
    private fun expected(name: String) = File(dir, name).readText(Charsets.UTF_8)

    @Test
    fun palmDocCompressedBookWithTrailingEntries() {
        val book = MobiParser.parse(bytes("palmdoc.mobi"))
        assertEquals(expected("kf6.expected.html"), book.html)
        assertEquals("Test Book", book.title)
        assertFalse(book.isKf8)
        assertEquals(2, book.images.size)
        assertEquals(mapOf(1 to 0, 2 to 1), book.imageIndexByRecordOffset)
    }

    @Test
    fun huffCdicCompressedBook() {
        val book = MobiParser.parse(bytes("huffcdic.mobi"))
        assertEquals(expected("kf6.expected.html"), book.html)
        assertEquals(2, book.images.size)
    }

    @Test
    fun windows1252UncompressedBook() {
        val book = MobiParser.parse(bytes("uncompressed_cp1252.mobi"))
        assertEquals(expected("cp1252.expected.html"), book.html)
    }

    @Test
    fun kf8BookUsesOnlyTheTextFlow() {
        for (name in listOf("kf8.azw3", "kf8_huff.azw3")) {
            val book = MobiParser.parse(bytes(name))
            assertTrue(book.isKf8)
            assertEquals(name, expected("kf8.expected.html"), book.html)
            assertFalse(book.html.contains("css flow must not leak"))
            assertEquals(2, book.images.size)
            val withMarkers = MobiParser.replaceImageReferences(book.html, book)
            assertTrue(withMarkers.contains("[IMAGE:0]"))
            assertTrue(withMarkers.contains("[IMAGE:1]"))
            assertTrue(withMarkers.indexOf("[IMAGE:0]") < withMarkers.indexOf("Финал"))
        }
    }

    @Test
    fun jointFilePrefersKf8SectionAndFallsBackToKf6() {
        val data = bytes("joint.mobi")
        val kf8 = MobiParser.parse(data)
        assertTrue(kf8.isKf8)
        assertEquals(expected("kf8.expected.html"), kf8.html)
        assertEquals(2, kf8.images.size)
        val kf6 = MobiParser.parse(data, preferKf8 = false)
        assertFalse(kf6.isKf8)
        assertEquals(expected("kf6.expected.html"), kf6.html)
        val withMarkers = MobiParser.replaceImageReferences(kf6.html, kf6)
        assertTrue(withMarkers.indexOf("[IMAGE:0]") in 1 until withMarkers.indexOf("[IMAGE:1]"))
    }

    @Test
    fun encryptedBooksAreReported() {
        val data = bytes("palmdoc.mobi").copyOf()
        val rec0 = ((data[78].toInt() and 0xFF) shl 24) or ((data[79].toInt() and 0xFF) shl 16) or ((data[80].toInt() and 0xFF) shl 8) or (data[81].toInt() and 0xFF)
        data[rec0 + 13] = 2
        try {
            MobiParser.parse(data)
            throw AssertionError("expected DRM exception")
        } catch (e: MobiDrmException) {
            assertEquals(2, e.encryptionType)
        }
    }

    @Test
    fun corruptedBooksFailCleanly() {
        val random = Random(3)
        for (name in listOf("palmdoc.mobi", "huffcdic.mobi", "kf8_huff.azw3")) {
            val original = bytes(name)
            repeat(200) {
                val copy = original.copyOf()
                repeat(1 + random.nextInt(10)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
                try {
                    MobiParser.parse(copy)
                } catch (_: CorruptedDataException) {
                } catch (_: IllegalArgumentException) {
                } catch (_: IllegalStateException) {
                } catch (_: UnsupportedOperationException) {
                } catch (_: IndexOutOfBoundsException) {
                }
            }
        }
    }
}
