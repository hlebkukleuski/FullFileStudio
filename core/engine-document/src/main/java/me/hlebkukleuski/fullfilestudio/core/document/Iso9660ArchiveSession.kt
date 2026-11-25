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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import me.hlebkukleuski.fullfilestudio.core.engine.modules.ArchiveSession
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class Iso9660ArchiveSession(private val pfd: ParcelFileDescriptor) : ArchiveSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    data class UdfExtent(
        val byteOffset: Long,
        val byteLength: Long
    )

    data class IsoFileEntry(
        val path: String,
        val size: Long,
        val extents: List<UdfExtent>,
        val inlinePayload: ByteArray? = null
    )

    private val fileMap = LinkedHashMap<String, IsoFileEntry>()
    private val displayEntries = mutableListOf<String>()
    private val tempExtractedFiles = mutableListOf<File>()

    override val isZipBombDetected: Boolean = false
    override val sanitizedFileEntries: List<String> get() = displayEntries
    override fun getLastError(): String? = lastErrorMsg

    companion object {
        private const val SECTOR_SIZE = 2048L
        private const val MAX_EXTRACT_BYTES = 256 * 1024 * 1024L
        private val ISO_SIZE_SUFFIX_REGEX = Regex(""" \(\d+ B\)$| \(unknown size\)$""", RegexOption.IGNORE_CASE)

        private fun stripDisplaySizeSuffix(entryPath: String): String {
            return entryPath.replace(ISO_SIZE_SUFFIX_REGEX, "").trim()
        }
    }

    init {
        parseIsoAndUdfVolumeDescriptors()
    }

    private fun parseIsoAndUdfVolumeDescriptors() {
        try {
            val headProbe = ByteBuffer.allocateDirect(512)
            val headRead = source.readAt(0L, headProbe)
            if (headRead >= 4) {
                val b0 = headProbe.get(0).toInt() and 0xFF
                val b1 = headProbe.get(1).toInt() and 0xFF
                val b2 = headProbe.get(2).toInt() and 0xFF
                val b3 = headProbe.get(3).toInt() and 0xFF
                if (b0 == 0x50 && b1 == 0x4B) {
                    if (parseZipArchiveFallback()) return
                }
                if (b0 == 0x1F && b1 == 0x8B) {
                    if (parseGzipArchiveFallback()) return
                }
                if (headRead >= 262 &&
                    headProbe.get(257) == 'u'.code.toByte() &&
                    headProbe.get(258) == 's'.code.toByte() &&
                    headProbe.get(259) == 't'.code.toByte() &&
                    headProbe.get(260) == 'a'.code.toByte() &&
                    headProbe.get(261) == 'r'.code.toByte()
                ) {
                    if (parseTarArchiveFallback(null)) return
                }
            }

            val secBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            var pvdRootRecord: ByteArray? = null
            var jolietRootRecord: ByteArray? = null
            var sawUdfNSR = false

            for (secIdx in 16..64) {
                secBuf.clear()
                val read = source.readAt(secIdx * SECTOR_SIZE, secBuf)
                if (read < SECTOR_SIZE.toInt()) break
                val ident = ByteArray(5)
                secBuf.position(1)
                secBuf.get(ident)
                val identStr = String(ident, StandardCharsets.US_ASCII)

                if (identStr == "NSR02" || identStr == "NSR03" || identStr == "BEA01") {
                    sawUdfNSR = true
                    continue
                }
                if (identStr != "CD001") continue

                val vdType = secBuf.get(0).toInt() and 0xFF
                if (vdType == 1) {
                    val rootRec = ByteArray(34)
                    secBuf.position(156)
                    secBuf.get(rootRec)
                    pvdRootRecord = rootRec
                } else if (vdType == 2) {
                    val esc0 = secBuf.get(88).toInt() and 0xFF
                    val esc1 = secBuf.get(89).toInt() and 0xFF
                    val esc2 = secBuf.get(90).toInt() and 0xFF
                    if (esc0 == 0x25 && esc1 == 0x2F && (esc2 == 0x40 || esc2 == 0x43 || esc2 == 0x45)) {
                        val rootRec = ByteArray(34)
                        secBuf.position(156)
                        secBuf.get(rootRec)
                        jolietRootRecord = rootRec
                    }
                } else if (vdType == 255) {
                    break
                }
            }

            var useRockRidge = false
            var suspSkip = 0
            if (pvdRootRecord != null) {
                val (rrActive, skip) = IsoRockRidgeParser.probePvdRockRidge(pvdRootRecord) { off, len ->
                    readIsoRange(off, len)
                }
                if (rrActive) {
                    useRockRidge = true
                    suspSkip = skip
                }
            }
            val chosenRoot = if (useRockRidge) pvdRootRecord else (jolietRootRecord ?: pvdRootRecord)
            val isJoliet = !useRockRidge && jolietRootRecord != null

            if (chosenRoot != null) {
                val rootBb = ByteBuffer.wrap(chosenRoot).order(ByteOrder.LITTLE_ENDIAN)
                val rootLba = rootBb.getInt(2).toLong() and 0xFFFFFFFFL
                val rootLen = rootBb.getInt(10).toLong() and 0xFFFFFFFFL
                val visitedLbas = HashSet<Long>()
                traverseIsoDirectory(rootLba, rootLen, "", isJoliet, useRockRidge, suspSkip, visitedLbas, 0)
            }

            val isBridgeStubOnly = fileMap.size <= 1 && fileMap.keys.all { it.contains("readme", ignoreCase = true) }
            if (fileMap.isEmpty() || (sawUdfNSR && isBridgeStubOnly)) {
                parseUdfFileSystem()
            }

            if (fileMap.isEmpty() && chosenRoot == null && !sawUdfNSR) {
                lastErrorMsg = "ISO image lacks standard ISO-9660 or UDF volume descriptor (CD001 / NSR02 / NSR03)."
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            if (fileMap.isEmpty()) {
                lastErrorMsg = "ISO-9660/UDF file system read error: ${e.localizedMessage}"
            }
        }
    }

    private fun parseGzipArchiveFallback(): Boolean {
        val maxBytes = minOf(source.size, 32L * 1024L * 1024L).toInt()
        if (maxBytes <= 0) return false
        val buf = ByteBuffer.allocateDirect(maxBytes)
        val got = source.readAt(0L, buf)
        if (got <= 0) return false
        buf.flip()
        val compBytes = ByteArray(got)
        buf.get(compBytes)

        val decompressed = GZIPInputStream(ByteArrayInputStream(compBytes)).use { gis ->
            val bos = ByteArrayOutputStream()
            val chunk = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val r = gis.read(chunk)
                if (r <= 0) break
                total += r
                if (total > 16L * 1024L * 1024L && (total / got.coerceAtLeast(1)) > 150L) {
                    throw SecurityException("Превышен безопасный лимит распаковки GZIP (защита от Zip-бомб).")
                }
                bos.write(chunk, 0, r)
            }
            bos.toByteArray()
        }
        if (decompressed.isEmpty()) return false
        if (decompressed.size >= 512 && parseTarArchiveFallback(decompressed)) return true

        val name = "decompressed_stream.txt"
        fileMap[name] = IsoFileEntry(name, decompressed.size.toLong(), emptyList(), decompressed)
        displayEntries.add("$name (${decompressed.size} B)")
        return true
    }

    private fun parseTarArchiveFallback(customBytes: ByteArray?): Boolean {
        val tarBytes = customBytes ?: run {
            val maxBytes = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (maxBytes < 512) return false
            val buf = ByteBuffer.allocateDirect(maxBytes)
            val got = source.readAt(0L, buf)
            if (got < 512) return false
            buf.flip()
            val arr = ByteArray(got)
            buf.get(arr)
            arr
        }

        var pos = 0
        while (pos + 512 <= tarBytes.size && fileMap.size < 10_000) {
            if (tarBytes[pos].toInt() == 0) break
            var nameEnd = pos
            while (nameEnd < pos + 100 && tarBytes[nameEnd].toInt() != 0) nameEnd++
            val rawName = String(tarBytes, pos, nameEnd - pos, StandardCharsets.UTF_8)
                .replace('\\', '/')
                .trimStart('/')
            val sizeOctal = String(tarBytes, pos + 124, 12, StandardCharsets.US_ASCII)
                .trim { it <= ' ' || it == '\u0000' }
            val entrySize = sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = tarBytes[pos + 156].toInt().toChar()
            val dataStart = pos + 512
            val isDir = typeFlag == '5' || rawName.endsWith("/")

            if (!isDir && rawName.isNotEmpty() && !rawName.contains("..") &&
                entrySize in 0L..(tarBytes.size - dataStart).toLong()
            ) {
                val payload = tarBytes.copyOfRange(dataStart, dataStart + entrySize.toInt())
                fileMap[rawName] = IsoFileEntry(rawName, entrySize, emptyList(), payload)
                displayEntries.add("$rawName ($entrySize B)")
            }
            val paddedBlocks = ((entrySize + 511L) / 512L) * 512L
            val nextPos = dataStart + paddedBlocks.toInt()
            if (nextPos <= pos) break
            pos = nextPos
        }
        return fileMap.isNotEmpty()
    }

    private fun parseZipArchiveFallback(): Boolean {
        val maxZipBytes = minOf(source.size, 64L * 1024L * 1024L).toInt()
        if (maxZipBytes <= 0) return false
        val buf = ByteBuffer.allocateDirect(maxZipBytes)
        val got = source.readAt(0L, buf)
        if (got <= 0) return false
        buf.flip()
        val bytes = ByteArray(got)
        buf.get(bytes)

        if (bytes.size >= 8 && (bytes[6].toInt() and 0x01) != 0) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: Password decryption for encrypted ZIP archives is not supported in this version.")
        }
        val compContainerLen = got.coerceAtLeast(1).toLong()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            val chunk = ByteArray(16384)
            var totalUnpacked = 0L
            while (fileMap.size < 10_000) {
                val entry = try {
                    zis.nextEntry
                } catch (ze: java.util.zip.ZipException) {
                    if (ze.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw SecurityException("UNSUPPORTED_ENCRYPTION: Password decryption for encrypted ZIP archives is not supported in this version.")
                    }
                    null
                } catch (_: Throwable) { null } ?: break
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && cleanName.isNotEmpty() && !cleanName.contains("..")) {
                    val bos = ByteArrayOutputStream()
                    var entryUnpacked = 0L
                    val entryCompHint = entry.compressedSize.takeIf { it > 0L } ?: compContainerLen
                    var r: Int
                    while (zis.read(chunk).also { r = it } > 0) {
                        entryUnpacked += r
                        totalUnpacked += r
                        if (entryUnpacked > 16L * 1024L * 1024L && (entryUnpacked / entryCompHint.coerceAtLeast(1L)) > 150L) {
                            throw SecurityException("Превышен безопасный лимит распаковки архива (защита от Zip-бомб).")
                        }
                        if (totalUnpacked > 128L * 1024L * 1024L) break
                        bos.write(chunk, 0, r)
                    }
                    val payload = bos.toByteArray()
                    fileMap[cleanName] = IsoFileEntry(
                        path = cleanName,
                        size = payload.size.toLong(),
                        extents = emptyList(),
                        inlinePayload = payload
                    )
                    displayEntries.add("$cleanName (${payload.size} B)")
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
        return fileMap.isNotEmpty()
    }

    private fun parseUdfFileSystem() {
        val secBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        secBuf.clear()
        if (source.readAt(256L * SECTOR_SIZE, secBuf) < 512) return
        val avdpTag = secBuf.getShort(0).toInt() and 0xFFFF
        if (avdpTag != 2) return

        val vdsLen = secBuf.getInt(16).toLong() and 0xFFFFFFFFL
        val vdsLba = secBuf.getInt(20).toLong() and 0xFFFFFFFFL
        val vdsSectors = (vdsLen / SECTOR_SIZE).coerceIn(1L, 64L)

        var partitionStartSector = -1L
        var fsdPartitionRelativeBlock = 0L

        for (s in 0 until vdsSectors) {
            secBuf.clear()
            if (source.readAt((vdsLba + s) * SECTOR_SIZE, secBuf) < 512) break
            val tagId = secBuf.getShort(0).toInt() and 0xFFFF
            if (tagId == 8) break
            if (tagId == 5) {
                partitionStartSector = secBuf.getInt(188).toLong() and 0xFFFFFFFFL
            } else if (tagId == 6) {
                fsdPartitionRelativeBlock = secBuf.getInt(248 + 4).toLong() and 0xFFFFFFFFL
            }
        }

        if (partitionStartSector < 0L) return

        var rootIcbBlock = -1L
        for (probe in 0..32) {
            val sector = partitionStartSector + fsdPartitionRelativeBlock + probe
            secBuf.clear()
            if (source.readAt(sector * SECTOR_SIZE, secBuf) < 512) break
            val tagId = secBuf.getShort(0).toInt() and 0xFFFF
            if (tagId == 256) {
                rootIcbBlock = secBuf.getInt(404).toLong() and 0xFFFFFFFFL
                break
            }
        }

        if (rootIcbBlock >= 0L) {
            if (fileMap.size == 1 && fileMap.keys.first().contains("readme", ignoreCase = true)) {
                fileMap.clear()
                displayEntries.clear()
            }
            val visitedBlocks = HashSet<Long>()
            traverseUdfDirectory(partitionStartSector, rootIcbBlock, "", visitedBlocks, 0)
        }
    }

    private data class UdfIcbData(
        val fileType: Int,
        val infoLength: Long,
        val extents: List<UdfExtent>,
        val inlineBytes: ByteArray?
    )

    private fun readUdfIcb(partitionStartSector: Long, blockNum: Long): UdfIcbData? {
        val buf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val absOffset = (partitionStartSector + blockNum) * SECTOR_SIZE
        val r = source.readAt(absOffset, buf)
        if (r < 256) return null

        val tagId = buf.getShort(0).toInt() and 0xFFFF
        if (tagId != 261 && tagId != 266) return null

        val icbFlags = buf.getShort(34).toInt() and 0xFFFF
        val allocType = icbFlags and 0x07
        val fileType = buf.get(27).toInt() and 0xFF
        val infoLength = buf.getLong(56).coerceAtLeast(0L)

        val eaOffset = if (tagId == 261) 176 else 216
        val lEa = buf.getInt(eaOffset - 8).coerceIn(0, 1800)
        val lAd = buf.getInt(eaOffset - 4).coerceIn(0, 1800)
        val adStart = eaOffset + lEa
        if (adStart + lAd > r) return null

        if (allocType == 3) {
            val inline = ByteArray(lAd)
            buf.position(adStart)
            buf.get(inline)
            return UdfIcbData(
                fileType = fileType,
                infoLength = lAd.toLong(),
                extents = listOf(UdfExtent(absOffset + adStart, lAd.toLong())),
                inlineBytes = inline
            )
        }

        val extents = ArrayList<UdfExtent>()
        val visitedAedBlocks = HashSet<Long>()
        parseUdfAllocationDescriptors(
            buf = buf,
            adStart = adStart,
            lAd = lAd,
            allocType = allocType,
            partitionStartSector = partitionStartSector,
            outExtents = extents,
            visitedAedBlocks = visitedAedBlocks,
            depth = 0
        )

        if (extents.isEmpty()) return null
        return UdfIcbData(fileType, infoLength, extents, null)
    }

    private fun parseUdfAllocationDescriptors(
        buf: ByteBuffer,
        adStart: Int,
        lAd: Int,
        allocType: Int,
        partitionStartSector: Long,
        outExtents: MutableList<UdfExtent>,
        visitedAedBlocks: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 32 || outExtents.size >= 4096) return
        val descSize = when (allocType) {
            0 -> 8
            1 -> 16
            else -> return
        }
        var pos = adStart
        val end = adStart + lAd
        while (pos + descSize <= end && outExtents.size < 4096) {
            val lenAndType = buf.getInt(pos)
            val extLen = (lenAndType and 0x3FFFFFFF).toLong()
            val extType = (lenAndType ushr 30) and 0x03
            val extBlock = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL

            if (extLen == 0L) break

            when (extType) {
                0 -> {
                    outExtents.add(UdfExtent((partitionStartSector + extBlock) * SECTOR_SIZE, extLen))
                }
                3 -> {
                    if (visitedAedBlocks.add(extBlock)) {
                        val aedBuf = ByteBuffer.allocateDirect(SECTOR_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                        val aedRead = source.readAt((partitionStartSector + extBlock) * SECTOR_SIZE, aedBuf)
                        if (aedRead >= 24 && (aedBuf.getShort(0).toInt() and 0xFFFF) == 258) {
                            val nextLAd = aedBuf.getInt(20).coerceIn(0, aedRead - 24)
                            parseUdfAllocationDescriptors(
                                buf = aedBuf,
                                adStart = 24,
                                lAd = nextLAd,
                                allocType = allocType,
                                partitionStartSector = partitionStartSector,
                                outExtents = outExtents,
                                visitedAedBlocks = visitedAedBlocks,
                                depth = depth + 1
                            )
                        }
                    }
                    break
                }
            }
            pos += descSize
        }
    }

    private fun readUdfExtentsToByteArray(icb: UdfIcbData, maxBytes: Int): ByteArray? {
        if (icb.inlineBytes != null) return icb.inlineBytes
        val targetLen = minOf(icb.infoLength, maxBytes.toLong(), source.size.coerceAtLeast(0L)).toInt()
        if (targetLen <= 0) return null
        val out = ByteArrayOutputStream(minOf(targetLen, 65536))
        val tmpBuf = ByteBuffer.allocateDirect(minOf(targetLen, 64 * 1024))
        val tmpArr = ByteArray(tmpBuf.capacity())

        var remaining = targetLen
        for (ext in icb.extents) {
            if (remaining <= 0) break
            var extRem = minOf(ext.byteLength, remaining.toLong()).toInt()
            var curOff = ext.byteOffset
            while (extRem > 0 && remaining > 0) {
                val chunk = minOf(extRem, tmpBuf.capacity())
                tmpBuf.clear()
                tmpBuf.limit(chunk)
                val got = source.readAt(curOff, tmpBuf)
                if (got <= 0) break
                tmpBuf.flip()
                tmpBuf.get(tmpArr, 0, got)
                out.write(tmpArr, 0, got)
                curOff += got
                extRem -= got
                remaining -= got
            }
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun decodeUdfOstaCS0(raw: ByteArray, offset: Int, len: Int): String {
        if (len <= 1 || offset + len > raw.size) return ""
        val compId = raw[offset].toInt() and 0xFF
        return when (compId) {
            8 -> String(raw, offset + 1, len - 1, StandardCharsets.ISO_8859_1)
            16 -> String(raw, offset + 1, (len - 1) and -2, StandardCharsets.UTF_16BE)
            else -> String(raw, offset, len, StandardCharsets.UTF_8)
        }.replace('\u0000', ' ').trim()
    }

    private fun traverseUdfDirectory(
        partitionStart: Long,
        dirIcbBlock: Long,
        parentPath: String,
        visited: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 16 || !visited.add(dirIcbBlock) || fileMap.size >= 10_000) return
        val icb = readUdfIcb(partitionStart, dirIcbBlock) ?: return
        if (icb.fileType != 4 || icb.infoLength <= 0L || icb.infoLength > 4 * 1024 * 1024L) return

        val dirBytes = readUdfExtentsToByteArray(icb, 4 * 1024 * 1024) ?: return
        val bb = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 38 <= dirBytes.size && fileMap.size < 10_000) {
            val tagId = bb.getShort(pos).toInt() and 0xFFFF
            if (tagId != 257) break

            val fileCharacteristics = dirBytes[pos + 18].toInt() and 0xFF
            val lFi = dirBytes[pos + 19].toInt() and 0xFF
            val childIcbBlock = bb.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val lIu = bb.getShort(pos + 36).toInt() and 0xFFFF

            val nameStart = pos + 38 + lIu
            val unalignedEnd = nameStart + lFi
            val nextPos = (unalignedEnd + 3) and -4
            if (unalignedEnd > dirBytes.size || nextPos <= pos) break

            val isParent = (fileCharacteristics and 0x08) != 0
            val isDeleted = (fileCharacteristics and 0x04) != 0
            val isDir = (fileCharacteristics and 0x02) != 0

            if (!isParent && !isDeleted && lFi > 0) {
                val name = decodeUdfOstaCS0(dirBytes, nameStart, lFi).replace('\\', '/')
                if (name.isNotEmpty() && !name.contains("..") && !name.contains('/')) {
                    val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (isDir) {
                        traverseUdfDirectory(partitionStart, childIcbBlock, fullPath, visited, depth + 1)
                    } else {
                        val fileIcb = readUdfIcb(partitionStart, childIcbBlock)
                        if (fileIcb != null) {
                            fileMap[fullPath] = IsoFileEntry(
                                path = fullPath,
                                size = fileIcb.infoLength,
                                extents = fileIcb.extents,
                                inlinePayload = fileIcb.inlineBytes
                            )
                            displayEntries.add("$fullPath (${fileIcb.infoLength} B)")
                        }
                    }
                }
            }
            pos = nextPos
        }
    }

    private fun readIsoRange(byteOffset: Long, len: Int): ByteArray? {
        if (byteOffset < 0L || len <= 0 || len > 4 * 1024 * 1024) return null
        val buf = ByteBuffer.allocateDirect(len)
        val r = source.readAt(byteOffset, buf)
        if (r <= 0) return null
        buf.flip()
        val out = ByteArray(r)
        buf.get(out)
        return out
    }

    private fun readRelocatedDirSize(dirLba: Long): Long {
        val sec = readIsoRange(dirLba * SECTOR_SIZE, 256) ?: return SECTOR_SIZE
        if (sec.size < 34) return SECTOR_SIZE
        val recLen = sec[0].toInt() and 0xFF
        if (recLen < 34 || recLen > sec.size) return SECTOR_SIZE
        val len = ByteBuffer.wrap(sec).order(ByteOrder.LITTLE_ENDIAN).getInt(10).toLong() and 0xFFFFFFFFL
        return if (len > 0L) len else SECTOR_SIZE
    }

    private fun traverseIsoDirectory(
        dirLba: Long,
        dirSize: Long,
        parentPath: String,
        isJoliet: Boolean,
        useRockRidge: Boolean,
        suspSkip: Int,
        visitedLbas: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 32 || dirSize <= 0L || dirSize > 4 * 1024 * 1024L || !visitedLbas.add(dirLba) || fileMap.size >= 10_000) return
        val safeAlloc = minOf(dirSize, source.size.coerceAtLeast(2048L)).toInt()
        if (safeAlloc <= 0) return
        val buf = ByteBuffer.allocateDirect(safeAlloc).order(ByteOrder.LITTLE_ENDIAN)
        val r = source.readAt(dirLba * SECTOR_SIZE, buf)
        if (r <= 0) return
        val bytes = ByteArray(r)
        buf.flip()
        buf.get(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val multiExtentOpen = HashSet<String>()

        var pos = 0
        while (pos < bytes.size && fileMap.size < 10_000) {
            val recLen = bytes[pos].toInt() and 0xFF
            if (recLen == 0) {
                val nextSectorPos = ((pos / 2048) + 1) * 2048
                if (nextSectorPos <= pos) break
                pos = nextSectorPos
                continue
            }
            if (pos + recLen > bytes.size || recLen < 34) break

            val extLba = bb.getInt(pos + 2).toLong() and 0xFFFFFFFFL
            val dataLen = bb.getInt(pos + 10).toLong() and 0xFFFFFFFFL
            val flags = bytes[pos + 25].toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            val hasMoreExtents = (flags and 0x80) != 0
            val nameLen = bytes[pos + 32].toInt() and 0xFF

            if (nameLen > 0 && pos + 33 + nameLen <= pos + recLen) {
                val firstNameByte = bytes[pos + 33].toInt() and 0xFF
                if (!(nameLen == 1 && (firstNameByte == 0 || firstNameByte == 1))) {
                    val rr = if (useRockRidge) {
                        IsoRockRidgeParser.parseSystemUseArea(bytes, pos, recLen, nameLen, suspSkip) { lba, off, len ->
                            readIsoRange(lba * SECTOR_SIZE + off, len)
                        }
                    } else null
                    if (rr?.relocated == true) {
                        pos += recLen
                        continue
                    }
                    val rawName = rr?.name ?: if (isJoliet && nameLen >= 2) {
                        String(bytes, pos + 33, nameLen - (nameLen % 2), StandardCharsets.UTF_16BE)
                    } else {
                        String(bytes, pos + 33, nameLen, StandardCharsets.UTF_8)
                    }
                    val cleanName = (if (rr?.name != null) rawName else rawName.substringBefore(';').trimEnd('.'))
                        .replace('\u0000', ' ')
                        .trim()
                        .replace('\\', '/')
                    if (cleanName.isNotEmpty() && !cleanName.contains("..") && !cleanName.contains('/')) {
                        val fullPath = if (parentPath.isEmpty()) cleanName else "$parentPath/$cleanName"
                        val childLba = rr?.childLinkLba
                        if (childLba != null) {
                            val relocSize = readRelocatedDirSize(childLba)
                            traverseIsoDirectory(childLba, relocSize, fullPath, isJoliet, useRockRidge, suspSkip, visitedLbas, depth + 1)
                        } else if (isDir) {
                            traverseIsoDirectory(extLba, dataLen, fullPath, isJoliet, useRockRidge, suspSkip, visitedLbas, depth + 1)
                        } else if (rr?.symlinkTarget != null) {
                            val targetBytes = rr.symlinkTarget.toByteArray(StandardCharsets.UTF_8)
                            fileMap[fullPath] = IsoFileEntry(
                                path = fullPath,
                                size = targetBytes.size.toLong(),
                                extents = emptyList(),
                                inlinePayload = targetBytes
                            )
                            displayEntries.add("$fullPath (${targetBytes.size} B)")
                        } else {
                            val existing = fileMap[fullPath]
                            val newExtent = UdfExtent(extLba * SECTOR_SIZE, dataLen)
                            if (existing != null && fullPath in multiExtentOpen) {
                                val combinedExtents = existing.extents + newExtent
                                val combinedSize = existing.size + dataLen
                                fileMap[fullPath] = existing.copy(size = combinedSize, extents = combinedExtents)
                                if (!hasMoreExtents) {
                                    multiExtentOpen.remove(fullPath)
                                    val idx = displayEntries.indexOfLast { it.startsWith("$fullPath (") }
                                    if (idx >= 0) displayEntries[idx] = "$fullPath ($combinedSize B)"
                                }
                            } else {
                                fileMap[fullPath] = IsoFileEntry(
                                    path = fullPath,
                                    size = dataLen,
                                    extents = listOf(newExtent)
                                )
                                displayEntries.add("$fullPath ($dataLen B)")
                                if (hasMoreExtents) multiExtentOpen.add(fullPath)
                            }
                        }
                    }
                }
            }
            pos += recLen
        }
    }

    override val pageCount: Int get() = ((displayEntries.size + 24) / 25).coerceAtLeast(1)

    override fun openVirtualFile(entryPath: String): ParcelFileDescriptor? {
        if (isClosed) return null
        val cleanPath = stripDisplaySizeSuffix(entryPath).replace('\u0000', ' ').trim().trimStart('/')
        if (cleanPath.contains("..")) return null
        val entry = fileMap[cleanPath]
            ?: fileMap.entries.firstOrNull { it.key.equals(cleanPath, ignoreCase = true) }?.value
            ?: return null
        if (entry.size < 0L || entry.size > MAX_EXTRACT_BYTES) return null

        var tempFile: File? = null
        return try {
            tempFile = me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.createTempFile("iso_entry_", ".tmp")

            me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.openOutput(tempFile).use { fos ->
                if (entry.inlinePayload != null) {
                    fos.write(entry.inlinePayload)
                } else {
                    val chunkBuf = ByteBuffer.allocateDirect(64 * 1024)
                    val byteArr = ByteArray(64 * 1024)
                    var totalRemaining = entry.size

                    for (extent in entry.extents) {
                        if (totalRemaining <= 0L) break
                        var extRemaining = minOf(extent.byteLength, totalRemaining)
                        var currentOffset = extent.byteOffset

                        while (extRemaining > 0L) {
                            val toRead = minOf(extRemaining, 64 * 1024L).toInt()
                            chunkBuf.clear()
                            chunkBuf.limit(toRead)
                            val r = source.readAt(currentOffset, chunkBuf)
                            if (r <= 0) break
                            chunkBuf.flip()
                            chunkBuf.get(byteArr, 0, r)
                            fos.write(byteArr, 0, r)
                            currentOffset += r
                            extRemaining -= r
                            totalRemaining -= r
                        }
                    }
                }
                fos.flush()
            }
            synchronized(this) {
                if (isClosed) {
                    me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.delete(tempFile)
                    return null
                }
                tempExtractedFiles.add(tempFile)
            }
            me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.openReadOnly(tempFile)
        } catch (_: Exception) {
            me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.delete(tempFile)
            null
        }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPage = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) Color.WHITE else Color.rgb(0x12, 0x12, 0x12))
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val paint = Paint().apply {
            color = if (isLight) Color.BLACK else Color.WHITE
            textSize = 26f * zoom
            isAntiAlias = true
        }
        val start = (cleanPage * 25).coerceIn(0, displayEntries.size)
        val end = (start + 25).coerceAtMost(displayEntries.size)
        var y = 45f * zoom
        for (i in start until end) {
            canvas.drawText(displayEntries[i], 30f - tileX, y - tileY, paint)
            y += 38f * zoom
        }
    }

    @Synchronized
    override fun close() {
        val filesToDelete: List<File>
        synchronized(this) {
            if (isClosed) return
            isClosed = true
            filesToDelete = tempExtractedFiles.toList()
            tempExtractedFiles.clear()
        }
        source.close()
        try { pfd.close() } catch (_: Exception) {}
        filesToDelete.forEach { file ->
            me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.delete(file)
        }
    }
}
