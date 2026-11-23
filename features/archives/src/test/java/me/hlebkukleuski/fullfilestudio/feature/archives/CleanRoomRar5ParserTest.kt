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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class CleanRoomRar5ParserTest {

    private val dir = File(javaClass.classLoader!!.getResource("rar5/test_read_format_rar5_stored.rar")!!.toURI()).parentFile!!

    private fun entriesOf(bytes: ByteArray) = CleanRoomRar5Parser.parseEntries(
        CleanRoomRar5Parser.PositionalReader { offset, buffer ->
            if (offset >= bytes.size) -1 else {
                val n = minOf(buffer.remaining().toLong(), bytes.size - offset).toInt()
                buffer.put(bytes, offset.toInt(), n)
                n
            }
        },
        bytes.size.toLong()
    )

    private fun unpack(bytes: ByteArray, all: List<CleanRoomRar5Parser.Rar5FileEntry>, e: CleanRoomRar5Parser.Rar5FileEntry): ByteArray =
        CleanRoomRar5Parser.unpackEntry(all, e, 256L * 1024 * 1024) {
            bytes.copyOfRange(it.dataOffset.toInt(), (it.dataOffset + it.packedSize).toInt())
        }

    @Test
    fun extractsEveryValidSampleExactlyLikeLibarchive() {
        val archives = dir.listFiles { f -> f.name.endsWith(".rar") }!!.sortedBy { it.name }
        assertTrue(archives.size >= 9)
        var compared = 0
        for (archive in archives) {
            val bytes = archive.readBytes()
            val result = entriesOf(bytes)
            val expectedDir = File(dir, archive.nameWithoutExtension + ".expected")
            val files = result.entries.filter { !it.isDirectory && !it.isSymlink }
            assertTrue(archive.name, files.isNotEmpty())
            for (e in files) {
                val expected = File(expectedDir, e.rawName)
                if (!expected.isFile) continue
                val actual = unpack(bytes, result.entries, e)
                assertArrayEquals("${archive.name}/${e.rawName}", expected.readBytes(), actual)
                compared++
            }
            for (e in files.asReversed()) {
                val expected = File(expectedDir, e.rawName)
                if (expected.isFile) assertArrayEquals("${archive.name}/${e.rawName} (reverse order)", expected.readBytes(), unpack(bytes, result.entries, e))
            }
        }
        assertTrue("compared $compared files", compared >= 29)
    }

    @Test
    fun malformedSamplesFailWithoutHangingOrCrashing() {
        val malformed = File(dir, "malformed").listFiles()!!.sortedBy { it.name }
        assertTrue(malformed.size >= 15)
        for (archive in malformed) {
            val bytes = archive.readBytes()
            val started = System.nanoTime()
            try {
                val result = entriesOf(bytes)
                for (e in result.entries.filter { !it.isDirectory && !it.isSymlink && it.dataOffset + it.packedSize <= bytes.size }) {
                    try {
                        unpack(bytes, result.entries, e)
                    } catch (_: IllegalStateException) {
                    } catch (_: IllegalArgumentException) {
                    } catch (_: IndexOutOfBoundsException) {
                    }
                }
            } catch (_: IllegalStateException) {
            }
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue("${archive.name} took $seconds s", seconds < 10)
        }
    }

    @Test
    fun randomCorruptionNeverEscapesAsUnexpectedError() {
        val random = java.util.Random(11)
        val source = File(dir, "test_read_format_rar5_solid.rar").readBytes()
        repeat(300) {
            val bytes = source.copyOf()
            repeat(1 + random.nextInt(6)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            try {
                val result = entriesOf(bytes)
                for (e in result.entries.filter { !it.isDirectory && it.dataOffset + it.packedSize <= bytes.size && it.packedSize >= 0 }) {
                    try {
                        unpack(bytes, result.entries, e)
                    } catch (_: IllegalStateException) {
                    } catch (_: IllegalArgumentException) {
                    } catch (_: IndexOutOfBoundsException) {
                    } catch (_: NegativeArraySizeException) {
                    }
                }
            } catch (_: IllegalStateException) {
            }
        }
    }
}
