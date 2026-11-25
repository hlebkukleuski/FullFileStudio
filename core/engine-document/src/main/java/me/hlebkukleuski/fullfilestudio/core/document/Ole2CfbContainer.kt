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
 *
 * Implements OLE2 Compound File Binary ([MS-CFB]), Excel BIFF8/BIFF12 ([MS-XLS] / [MS-XLSB]),
 * Word Binary ([MS-DOC]), PowerPoint Binary ([MS-PPT]), OneNote ([MS-ONE] / [MS-ONESTORE])
 * under the Microsoft Open Specifications Promise (OSP), ISO-9660 (ECMA-119), and dBase DBF,
 * with BIFF8/OLE2 structure definitions informed by Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

internal class Ole2CfbContainer(private val data: ByteArray) {
    data class DirEntry(
        val name: String,
        val objectType: Int,
        val startSector: Int,
        val size: Long,
        val depth: Int = 0
    )

    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniStreamCutoffSize: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = mutableListOf<DirEntry>()
    private var rootMiniStream: ByteArray = ByteArray(0)

    val isValid: Boolean

    companion object {
        private const val ENDOFCHAIN = -2
        private const val FREESECT = -1
        private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    }

    init {
        if (data.size < 512 ||
            bb.getInt(0) != -0x1FEE3030 ||
            bb.getInt(4) != -0x1EE54E5F
        ) {
            sectorSize = 512
            miniSectorSize = 64
            miniStreamCutoffSize = 4096
            fat = IntArray(0)
            miniFat = IntArray(0)
            isValid = false
        } else {
            val sectorShift = (bb.getShort(30).toInt() and 0xFFFF).coerceIn(9, 12)
            val miniSectorShift = (bb.getShort(32).toInt() and 0xFFFF).coerceIn(6, 8)
            sectorSize = 1 shl sectorShift
            miniSectorSize = 1 shl miniSectorShift
            miniStreamCutoffSize = bb.getInt(56).coerceAtLeast(4096)

            val maxFileSectors = (data.size / sectorSize) + 2
            val numFatSectors = bb.getInt(44).coerceIn(0, minOf(65536, maxFileSectors))
            val firstDirSector = bb.getInt(48)
            val firstMiniFatSector = bb.getInt(60)
            val numMiniFatSectors = bb.getInt(64).coerceIn(0, minOf(65536, maxFileSectors))
            val firstDifatSector = bb.getInt(68)
            val numDifatSectors = bb.getInt(72).coerceIn(0, minOf(1024, maxFileSectors))

            val difatSectors = ArrayList<Int>(numFatSectors)
            for (i in 0 until minOf(109, numFatSectors)) {
                val sec = bb.getInt(76 + i * 4)
                if (sec >= 0) difatSectors.add(sec)
            }

            var currDifat = firstDifatSector
            var difatVisited = 0
            val intsPerSector = sectorSize / 4
            while (currDifat >= 0 && difatVisited < numDifatSectors && difatSectors.size < numFatSectors) {
                val offset = (currDifat + 1L) * sectorSize
                if (offset + sectorSize > data.size) break
                for (i in 0 until intsPerSector - 1) {
                    val sec = bb.getInt(offset.toInt() + i * 4)
                    if (sec >= 0 && difatSectors.size < numFatSectors) difatSectors.add(sec)
                }
                currDifat = bb.getInt(offset.toInt() + (intsPerSector - 1) * 4)
                difatVisited++
            }

            val fatList = IntArray(difatSectors.size * intsPerSector)
            var fatIdx = 0
            for (sec in difatSectors) {
                val offset = (sec + 1L) * sectorSize
                if (offset + sectorSize <= data.size && offset >= 0) {
                    for (i in 0 until intsPerSector) {
                        fatList[fatIdx++] = bb.getInt(offset.toInt() + i * 4)
                    }
                }
            }
            fat = fatList

            val miniFatBytes = readChain(firstMiniFatSector, numMiniFatSectors.toLong() * sectorSize)
            val mfBb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
            miniFat = IntArray(miniFatBytes.size / 4) { mfBb.int }

            val dirBytes = readChain(firstDirSector, 4 * 1024 * 1024L)
            val entryCount = dirBytes.size / 128
            val dirBb = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
            val depthOf = IntArray(entryCount) { -1 }
            if (entryCount > 0) {
                depthOf[0] = 0
                val stack = ArrayDeque<Pair<Int, Int>>()
                val rootChild = dirBb.getInt(76)
                if (rootChild in 1 until entryCount) stack.addLast(rootChild to 1)
                var guard = 0
                while (stack.isNotEmpty() && guard++ < entryCount * 4) {
                    val (idx, d) = stack.removeLast()
                    if (idx !in 0 until entryCount || depthOf[idx] >= 0) continue
                    depthOf[idx] = d
                    val b = idx * 128
                    val left = dirBb.getInt(b + 68); val right = dirBb.getInt(b + 72); val child = dirBb.getInt(b + 76)
                    if (left in 0 until entryCount) stack.addLast(left to d)
                    if (right in 0 until entryCount) stack.addLast(right to d)
                    if (child in 0 until entryCount) stack.addLast(child to d + 1)
                }
            }

            for (i in 0 until entryCount) {
                val base = i * 128
                val nameLenBytes = (dirBb.getShort(base + 64).toInt() and 0xFFFF).coerceIn(0, 64)
                val objType = dirBytes[base + 66].toInt() and 0xFF
                if (objType == 0 || nameLenBytes <= 2) continue
                val name = String(dirBytes, base, nameLenBytes - 2, StandardCharsets.UTF_16LE)
                val startSec = dirBb.getInt(base + 116)
                val streamSize = if (sectorShift == 9) {
                    dirBb.getInt(base + 120).toLong() and 0xFFFFFFFFL
                } else {
                    dirBb.getLong(base + 120)
                }
                entries.add(DirEntry(name, objType, startSec, streamSize, if (objType == 5) 0 else depthOf[i].let { if (it < 0) Int.MAX_VALUE else it }))
            }

            val rootEntry = entries.firstOrNull { it.objectType == 5 }
            if (rootEntry != null && rootEntry.startSector >= 0 && rootEntry.size > 0L) {
                rootMiniStream = readChain(rootEntry.startSector, rootEntry.size.coerceAtMost(MAX_STREAM_BYTES.toLong()))
            }

            isValid = entries.isNotEmpty()
        }
    }

    fun listStreams(): List<DirEntry> = entries

    fun readStream(name: String): ByteArray? {
        val entry = entries.filter { it.name.equals(name, ignoreCase = true) && it.objectType != 1 }.minByOrNull { it.depth } ?: return null
        val safeLen = entry.size.coerceIn(0L, MAX_STREAM_BYTES.toLong())
        if (safeLen == 0L) return ByteArray(0)
        return if (entry.objectType != 5 && safeLen < miniStreamCutoffSize && rootMiniStream.isNotEmpty()) {
            readMiniChain(entry.startSector, safeLen)
        } else {
            readChain(entry.startSector, safeLen)
        }
    }

    private fun readChain(startSector: Int, expectedSize: Long): ByteArray {
        if (startSector < 0 || expectedSize <= 0L) return ByteArray(0)
        val maxBytes = minOf(expectedSize, MAX_STREAM_BYTES.toLong(), data.size.toLong()).toInt()
        val bos = ByteArrayOutputStream(minOf(maxBytes, 65536))
        var curr = startSector
        var visited = 0
        val maxSectors = minOf((maxBytes + sectorSize - 1) / sectorSize + 4, (data.size / sectorSize) + 2)
        while (curr >= 0 && curr != ENDOFCHAIN && curr != FREESECT && visited < maxSectors && bos.size() < maxBytes) {
            val offset = (curr + 1L) * sectorSize
            if (offset < 0 || offset + sectorSize > data.size) break
            val toCopy = minOf(sectorSize, maxBytes - bos.size())
            bos.write(data, offset.toInt(), toCopy)
            curr = if (curr < fat.size) fat[curr] else ENDOFCHAIN
            visited++
        }
        return bos.toByteArray()
    }

    private fun readMiniChain(startMiniSector: Int, expectedSize: Long): ByteArray {
        if (startMiniSector < 0 || expectedSize <= 0L || rootMiniStream.isEmpty()) return ByteArray(0)
        val maxBytes = minOf(expectedSize, MAX_STREAM_BYTES.toLong(), rootMiniStream.size.toLong()).toInt()
        val bos = ByteArrayOutputStream(minOf(maxBytes, 65536))
        var curr = startMiniSector
        var visited = 0
        val maxSectors = minOf((maxBytes + miniSectorSize - 1) / miniSectorSize + 4, (rootMiniStream.size / miniSectorSize) + 2)
        while (curr >= 0 && curr != ENDOFCHAIN && curr != FREESECT && visited < maxSectors && bos.size() < maxBytes) {
            val offset = curr.toLong() * miniSectorSize
            if (offset < 0 || offset + miniSectorSize > rootMiniStream.size) break
            val toCopy = minOf(miniSectorSize, maxBytes - bos.size())
            bos.write(rootMiniStream, offset.toInt(), toCopy)
            curr = if (curr < miniFat.size) miniFat[curr] else ENDOFCHAIN
            visited++
        }
        return bos.toByteArray()
    }
}
