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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.github.junrar.Archive
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.impl.CleanRoomIso9660Session
import com.omnireader.core.engine.modules.ArchiveNavigatorEngine
import com.omnireader.core.engine.modules.ArchiveSession
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.min

private fun resolveArchiveDescriptorSize(fd: FileDescriptor, fallbackSize: Long): Long {
    val statSize = try {
        Os.fstat(fd).st_size
    } catch (_: Throwable) {
        -1L
    }
    if (statSize > 0L) return statSize

    val lseekSize = try {
        val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
        val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
        Os.lseek(fd, cur, OsConstants.SEEK_SET)
        end
    } catch (_: Throwable) {
        -1L
    }
    if (lseekSize > 0L) return lseekSize
    return fallbackSize.coerceAtLeast(0L)
}

internal class PfdSeekableByteChannel(
    private val fd: FileDescriptor,
    private val totalSize: Long
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private val reusableDirect = ByteBuffer.allocateDirect(64 * 1024)

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    override fun position(): Long {
        if (!open) throw ClosedChannelException()
        return position
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        if (!open) throw ClosedChannelException()
        require(newPosition >= 0L) { "Negative position: $newPosition" }
        position = newPosition
        return this
    }

    override fun size(): Long {
        if (!open) throw ClosedChannelException()
        return totalSize
    }

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (!dst.hasRemaining()) return 0
        if (totalSize > 0L && position >= totalSize) return -1

        val maxToRead = if (totalSize > 0L) {
            min(dst.remaining().toLong(), totalSize - position).toInt()
        } else {
            dst.remaining()
        }
        if (maxToRead <= 0) return -1

        if (dst.isDirect) {
            val savedLimit = dst.limit()
            dst.limit(dst.position() + maxToRead)
            val r = try {
                Os.pread(fd, dst, position)
            } finally {
                dst.limit(savedLimit)
            }
            if (r <= 0) return -1
            position += r
            return r
        } else {
            val chunk = min(maxToRead, reusableDirect.capacity())
            reusableDirect.clear()
            reusableDirect.limit(chunk)
            val r = Os.pread(fd, reusableDirect, position)
            if (r <= 0) return -1
            reusableDirect.flip()
            dst.put(reusableDirect)
            position += r
            return r
        }
    }

    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("Read-only channel")
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("Read-only channel")
}

internal class PfdPositionalInputStream(
    private val fd: FileDescriptor,
    private val totalSize: Long,
    startOffset: Long = 0L,
    maxLength: Long = -1L
) : InputStream() {
    private var pos = startOffset
    private val limitOffset: Long = when {
        maxLength >= 0L && totalSize > 0L -> minOf(totalSize, startOffset + maxLength)
        maxLength >= 0L -> startOffset + maxLength
        else -> totalSize
    }
    private val oneByte = ByteArray(1)
    private val directBuf = ByteBuffer.allocateDirect(32 * 1024)

    override fun read(): Int {
        val r = read(oneByte, 0, 1)
        return if (r == 1) (oneByte[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (limitOffset > 0L && pos >= limitOffset) return -1
        val remaining = if (limitOffset > 0L) (limitOffset - pos) else len.toLong()
        val toRead = minOf(len.toLong(), remaining, directBuf.capacity().toLong()).toInt()
        if (toRead <= 0) return -1

        directBuf.clear()
        directBuf.limit(toRead)
        val r = try {
            Os.pread(fd, directBuf, pos)
        } catch (_: Throwable) {
            -1
        }
        if (r <= 0) return -1
        directBuf.flip()
        directBuf.get(b, off, r)
        pos += r
        return r
    }
}

/**
 * Clean-Room RAR 5.0 (`Rar!\x1A\x07\x01\x00`) read-only header parser and stream extractor.
 * The WinRAR/UnRAR license only restricts recreating the RAR compression algorithm; read-only
 * parsing and extraction of RAR 5.0 archives is 100% permitted in commercial applications.
 */
internal object CleanRoomRar5Parser {

    data class Rar5FileEntry(
        val rawName: String,
        val unpackedSize: Long,
        val packedSize: Long,
        val dataOffset: Long,
        val compressionMethod: Int,
        val dictSizeBytes: Int,
        val isSolid: Boolean,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val isEncrypted: Boolean,
        val dataCrc32: Int?
    )

    data class Rar5ParseResult(
        val entries: List<Rar5FileEntry>,
        val isHeaderEncrypted: Boolean
    )

    private class VintReader(val data: ByteArray, var pos: Int, val limit: Int) {
        fun hasRemaining(): Boolean = pos < limit

        fun readVint(): Long {
            var result = 0L
            var shift = 0
            var count = 0
            while (pos < limit && count < 10) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
                count++
            }
            return result
        }

        fun readInt32LE(): Int {
            if (pos + 4 > limit) {
                pos = limit
                return 0
            }
            val v = (data[pos].toInt() and 0xFF) or
                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                    ((data[pos + 2].toInt() and 0xFF) shl 16) or
                    ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
    }

    fun parseEntries(source: ZeroCacheReadableSource, fileSize: Long): Rar5ParseResult {
        val entries = ArrayList<Rar5FileEntry>()
        val sigBuf = ByteBuffer.allocateDirect(8)
        if (source.readAt(0L, sigBuf) < 8) {
            return Rar5ParseResult(emptyList(), false)
        }
        if (sigBuf.get(0) != 0x52.toByte() || sigBuf.get(1) != 0x61.toByte() ||
            sigBuf.get(2) != 0x72.toByte() || sigBuf.get(3) != 0x21.toByte() ||
            sigBuf.get(4) != 0x1A.toByte() || sigBuf.get(5) != 0x07.toByte() ||
            sigBuf.get(6) != 0x01.toByte() || sigBuf.get(7) != 0x00.toByte()
        ) {
            return Rar5ParseResult(emptyList(), false)
        }

        var offset = 8L
        val probeBuf = ByteBuffer.allocateDirect(16)
        val probeBytes = ByteArray(16)
        val maxEntries = 25_000

        while (offset + 7L <= fileSize && entries.size < maxEntries) {
            probeBuf.clear()
            val got = source.readAt(offset, probeBuf)
            if (got < 7) break
            probeBuf.flip()
            probeBuf.get(probeBytes, 0, got)

            val sizeReader = VintReader(probeBytes, 4, got)
            val headerSize = sizeReader.readVint()
            val headerContentOffset = offset + sizeReader.pos
            if (headerSize <= 0L || headerSize > 2 * 1024 * 1024L || headerContentOffset + headerSize > fileSize) {
                break
            }

            val hLen = headerSize.toInt()
            val hBuf = ByteBuffer.allocateDirect(hLen)
            if (source.readAt(headerContentOffset, hBuf) < hLen) break
            hBuf.flip()
            val hBytes = ByteArray(hLen)
            hBuf.get(hBytes)

            val reader = VintReader(hBytes, 0, hLen)
            val headerType = reader.readVint().toInt()
            val headerFlags = reader.readVint().toInt()

            val hasExtraArea = (headerFlags and 0x0001) != 0
            val hasDataArea = (headerFlags and 0x0002) != 0

            val extraAreaSize = if (hasExtraArea) reader.readVint() else 0L
            val dataAreaSize = if (hasDataArea) reader.readVint().coerceAtLeast(0L) else 0L

            val dataOffset = headerContentOffset + headerSize
            val nextBlockOffset = dataOffset + dataAreaSize

            when (headerType) {
                4 -> {
                    return Rar5ParseResult(entries, isHeaderEncrypted = true)
                }
                5 -> {
                    break
                }
                2 -> {
                    val fileFlags = reader.readVint().toInt()
                    val isDir = (fileFlags and 0x0001) != 0
                    val hasMtime = (fileFlags and 0x0002) != 0
                    val hasCrc32 = (fileFlags and 0x0004) != 0
                    val unknownUnpackedSize = (fileFlags and 0x0008) != 0

                    val unpackedSizeRaw = reader.readVint()
                    val unpackedSize = if (unknownUnpackedSize) -1L else unpackedSizeRaw
                    reader.readVint()

                    if (hasMtime) {
                        reader.readInt32LE()
                    }
                    val crc32 = if (hasCrc32) reader.readInt32LE() else null

                    val compInfo = reader.readVint().toInt()
                    val isSolid = (compInfo and 0x40) != 0
                    val compMethod = (compInfo ushr 7) and 0x07
                    val dictSizePower = (compInfo ushr 10) and 0x1F
                    val dictSizeBytes = (128 * 1024) shl dictSizePower.coerceIn(0, 15)

                    reader.readVint()
                    val nameLen = reader.readVint().toInt().coerceIn(0, hLen - reader.pos)
                    val rawName = if (nameLen > 0) {
                        String(hBytes, reader.pos, nameLen, StandardCharsets.UTF_8)
                    } else ""
                    reader.pos += nameLen

                    var isFileEncrypted = false
                    var isSymlink = false
                    if (hasExtraArea && extraAreaSize > 0L && reader.pos < hLen) {
                        val extraEnd = minOf(hLen, reader.pos + extraAreaSize.toInt())
                        while (reader.pos < extraEnd) {
                            val recSize = reader.readVint().toInt()
                            if (recSize <= 0 || reader.pos + recSize > extraEnd) break
                            val nextRecPos = reader.pos + recSize
                            val recType = reader.readVint().toInt()
                            if (recType == 0x01) {
                                isFileEncrypted = true
                            } else if (recType == 0x05) {
                                isSymlink = true
                            }
                            reader.pos = nextRecPos
                        }
                    }

                    if (rawName.isNotEmpty()) {
                        entries.add(
                            Rar5FileEntry(
                                rawName = rawName,
                                unpackedSize = unpackedSize,
                                packedSize = dataAreaSize,
                                dataOffset = dataOffset,
                                compressionMethod = compMethod,
                                dictSizeBytes = dictSizeBytes,
                                isSolid = isSolid,
                                isDirectory = isDir,
                                isSymlink = isSymlink,
                                isEncrypted = isFileEncrypted,
                                dataCrc32 = crc32
                            )
                        )
                    }
                }
            }

            if (nextBlockOffset <= offset) break
            offset = nextBlockOffset
        }

        return Rar5ParseResult(entries, isHeaderEncrypted = false)
    }

    fun decompressRar5Stream(
        compressed: ByteArray,
        expectedUnpackedSize: Long,
        dictSizeBytes: Int,
        output: OutputStream,
        maxAllowedBytes: Long
    ): Boolean {
        if (expectedUnpackedSize == 0L) return true
        if (compressed.isEmpty()) return expectedUnpackedSize <= 0L
        val targetSize = if (expectedUnpackedSize > 0L) {
            minOf(expectedUnpackedSize, maxAllowedBytes).toInt()
        } else {
            minOf(compressed.size.toLong() * 8L, maxAllowedBytes).toInt()
        }
        if (targetSize <= 0) return true

        return try {
            val bitReader = Rar5BitReader(compressed)
            val windowCap = minOf(maxOf(dictSizeBytes, 128 * 1024), 16 * 1024 * 1024)
            val window = ByteArray(windowCap)
            var winPos = 0
            var totalWritten = 0
            val outChunk = ByteArray(16 * 1024)
            var outChunkPos = 0

            fun emitByte(b: Byte) {
                window[winPos] = b
                winPos = (winPos + 1) % windowCap
                outChunk[outChunkPos++] = b
                totalWritten++
                if (outChunkPos == outChunk.size) {
                    output.write(outChunk, 0, outChunkPos)
                    outChunkPos = 0
                }
            }

            val prevDist = IntArray(4)
            var lastLength = 0

            val mainTable = Rar5HuffmanTable(306)
            val distTable = Rar5HuffmanTable(64)
            val lowDistTable = Rar5HuffmanTable(16)
            val lenTable = Rar5HuffmanTable(44)

            var isLastBlock = false
            while (!isLastBlock && totalWritten < targetSize && bitReader.hasBits(8)) {
                bitReader.alignToByte()
                if (!bitReader.hasBits(16)) break
                val blockFlags = bitReader.readBits(8)
                bitReader.readBits(8)

                val byteCount = ((blockFlags ushr 3) and 0x03) + 1
                if (byteCount > 3) break
                val bitSize = (blockFlags and 0x07) + 1
                isLastBlock = (blockFlags and 0x40) != 0
                val tablePresent = (blockFlags and 0x80) != 0

                var blockByteSize = 0
                for (i in 0 until byteCount) {
                    blockByteSize = blockByteSize or (bitReader.readBits(8) shl (i * 8))
                }
                val blockEndBitPos = bitReader.bitPosition + (blockByteSize - 1) * 8 + bitSize

                if (tablePresent) {
                    if (!readRar5HuffmanTables(bitReader, mainTable, distTable, lowDistTable, lenTable)) {
                        break
                    }
                }

                while (bitReader.bitPosition < blockEndBitPos && totalWritten < targetSize) {
                    val sym = mainTable.decodeSymbol(bitReader)
                    if (sym < 0) break
                    when {
                        sym < 256 -> {
                            emitByte(sym.toByte())
                        }
                        sym >= 262 -> {
                            var length = slotToLength(bitReader, sym - 262)
                            val distSlot = distTable.decodeSymbol(bitReader)
                            if (distSlot < 0) break
                            val distance: Int = if (distSlot < 4) {
                                distSlot + 1
                            } else {
                                val dBits = (distSlot / 2) - 1
                                val base = (2 or (distSlot and 1)) shl dBits
                                if (dBits >= 4) {
                                    val highPart = if (dBits > 4) bitReader.readBits(dBits - 4) shl 4 else 0
                                    val lowPart = lowDistTable.decodeSymbol(bitReader).coerceAtLeast(0)
                                    base + highPart + lowPart + 1
                                } else {
                                    base + bitReader.readBits(dBits) + 1
                                }
                            }
                            if (distance > 0x100) {
                                length++
                                if (distance > 0x2000) {
                                    length++
                                    if (distance > 0x40000) length++
                                }
                            }
                            prevDist[3] = prevDist[2]
                            prevDist[2] = prevDist[1]
                            prevDist[1] = prevDist[0]
                            prevDist[0] = distance
                            lastLength = length

                            for (k in 0 until length) {
                                if (totalWritten >= targetSize) break
                                val srcIdx = (winPos - distance + windowCap) % windowCap
                                emitByte(window[srcIdx])
                            }
                        }
                        sym == 256 -> {
                            break
                        }
                        sym == 257 -> {
                            if (lastLength > 0 && prevDist[0] > 0) {
                                val dist = prevDist[0]
                                for (k in 0 until lastLength) {
                                    if (totalWritten >= targetSize) break
                                    val srcIdx = (winPos - dist + windowCap) % windowCap
                                    emitByte(window[srcIdx])
                                }
                            }
                        }
                        sym in 258..261 -> {
                            val idx = sym - 258
                            val dist = prevDist[idx]
                            for (k in idx downTo 1) {
                                prevDist[k] = prevDist[k - 1]
                            }
                            prevDist[0] = dist
                            val lenSym = lenTable.decodeSymbol(bitReader)
                            if (lenSym < 0) break
                            val length = slotToLength(bitReader, lenSym)
                            lastLength = length
                            if (dist > 0) {
                                for (k in 0 until length) {
                                    if (totalWritten >= targetSize) break
                                    val srcIdx = (winPos - dist + windowCap) % windowCap
                                    emitByte(window[srcIdx])
                                }
                            }
                        }
                    }
                }
            }

            if (outChunkPos > 0) {
                output.write(outChunk, 0, outChunkPos)
            }
            totalWritten > 0 || expectedUnpackedSize == 0L
        } catch (_: Throwable) {
            false
        }
    }

    private fun slotToLength(br: Rar5BitReader, slot: Int): Int {
        return if (slot < 8) {
            2 + slot
        } else {
            val lBits = (slot / 4) - 1
            2 + ((4 or (slot and 3)) shl lBits) + br.readBits(lBits)
        }
    }

    private fun readRar5HuffmanTables(
        br: Rar5BitReader,
        mainTable: Rar5HuffmanTable,
        distTable: Rar5HuffmanTable,
        lowDistTable: Rar5HuffmanTable,
        lenTable: Rar5HuffmanTable
    ): Boolean {
        val bitLengths = IntArray(20)
        var i = 0
        while (i < 20) {
            val len = br.readBits(4)
            if (len == 15) {
                var zeroCount = br.readBits(4)
                if (zeroCount == 0) {
                    bitLengths[i++] = 15
                } else {
                    zeroCount += 2
                    while (zeroCount-- > 0 && i < 20) {
                        bitLengths[i++] = 0
                    }
                }
            } else {
                bitLengths[i++] = len
            }
        }
        val codeLenTable = Rar5HuffmanTable(20)
        if (!codeLenTable.build(bitLengths)) return false

        val totalSymbols = 306 + 64 + 16 + 44
        val allLengths = IntArray(totalSymbols)
        var idx = 0
        while (idx < totalSymbols) {
            val sym = codeLenTable.decodeSymbol(br)
            if (sym < 0) return false
            when {
                sym < 16 -> {
                    allLengths[idx++] = sym
                }
                sym == 16 || sym == 17 -> {
                    if (idx == 0) return false
                    val prev = allLengths[idx - 1]
                    val rep = if (sym == 16) br.readBits(3) + 3 else br.readBits(7) + 11
                    repeat(rep) {
                        if (idx < totalSymbols) allLengths[idx++] = prev
                    }
                }
                sym == 18 || sym == 19 -> {
                    val zeros = if (sym == 18) br.readBits(3) + 3 else br.readBits(7) + 11
                    repeat(zeros) {
                        if (idx < totalSymbols) allLengths[idx++] = 0
                    }
                }
            }
        }

        return mainTable.build(allLengths.copyOfRange(0, 306)) &&
                distTable.build(allLengths.copyOfRange(306, 370)) &&
                lowDistTable.build(allLengths.copyOfRange(370, 386)) &&
                lenTable.build(allLengths.copyOfRange(386, 430))
    }

    private class Rar5BitReader(private val data: ByteArray) {
        var bitPosition = 0
        private val maxBits = data.size * 8

        fun hasBits(count: Int): Boolean = bitPosition + count <= maxBits

        fun alignToByte() {
            val rem = bitPosition and 7
            if (rem != 0) bitPosition += (8 - rem)
        }

        fun peekBits(count: Int): Int {
            if (count <= 0) return 0
            var res = 0
            for (i in 0 until count) {
                val bPos = bitPosition + i
                if (bPos >= maxBits) {
                    res = res shl (count - i)
                    break
                }
                val byteVal = data[bPos ushr 3].toInt() and 0xFF
                val bit = (byteVal ushr (7 - (bPos and 7))) and 1
                res = (res shl 1) or bit
            }
            return res
        }

        fun skipBits(count: Int) {
            bitPosition = (bitPosition + count).coerceAtMost(maxBits)
        }

        fun readBits(count: Int): Int {
            val v = peekBits(count)
            skipBits(count)
            return v
        }
    }

    private class Rar5HuffmanTable(private val alphabetSize: Int) {
        private val maxCodeLen = 15
        private val decodeLen = IntArray(1 shl 15)
        private val decodeSym = IntArray(1 shl 15) { -1 }

        fun build(lengths: IntArray): Boolean {
            decodeLen.fill(0)
            decodeSym.fill(-1)
            val blCount = IntArray(maxCodeLen + 1)
            for (l in lengths) {
                if (l in 1..maxCodeLen) blCount[l]++
            }
            val nextCode = IntArray(maxCodeLen + 1)
            var code = 0
            for (bits in 1..maxCodeLen) {
                code = (code + blCount[bits - 1]) shl 1
                nextCode[bits] = code
            }
            for (sym in 0 until minOf(alphabetSize, lengths.size)) {
                val len = lengths[sym]
                if (len in 1..maxCodeLen) {
                    val c = nextCode[len]++
                    val shift = maxCodeLen - len
                    val start = (c shl shift) and 0x7FFF
                    val end = start + (1 shl shift)
                    if (end <= decodeSym.size) {
                        for (slot in start until end) {
                            decodeLen[slot] = len
                            decodeSym[slot] = sym
                        }
                    }
                }
            }
            return true
        }

        fun decodeSymbol(br: Rar5BitReader): Int {
            val peeked = br.peekBits(maxCodeLen) and 0x7FFF
            val sym = decodeSym[peeked]
            val len = decodeLen[peeked]
            if (sym < 0 || len <= 0) return -1
            br.skipBits(len)
            return sym
        }
    }
}

class ArchiveNavigatorEngineImpl : ArchiveNavigatorEngine {

    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "iso", "apk", "jar")) {
            return true
        }
        if (signature.size >= 4) {
            if (signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte()) return true
            if (signature[0] == 0x52.toByte() && signature[1] == 0x61.toByte() && signature[2] == 0x72.toByte() && signature[3] == 0x21.toByte()) return true
        }
        if (signature.size >= 6 &&
            signature[0] == 0x37.toByte() && signature[1] == 0x7A.toByte() &&
            signature[2] == 0xBC.toByte() && signature[3] == 0xAF.toByte()
        ) {
            return true
        }
        return false
    }

    override fun createSession(fd: FileDescriptor): DocumentSession = createArchiveSession(fd)

    override fun createArchiveSession(fd: FileDescriptor): ArchiveSession {
        val header = ByteBuffer.allocateDirect(8)
        val fileSize = resolveArchiveDescriptorSize(fd, -1L)
        if (fileSize >= 0x8006L) {
            val isoProbe = ByteBuffer.allocateDirect(5)
            val r = try { Os.pread(fd, isoProbe, 0x8001L) } catch (_: Exception) { 0 }
            if (r == 5) {
                val arr = ByteArray(5)
                isoProbe.flip()
                isoProbe.get(arr)
                val vdIdent = String(arr, StandardCharsets.US_ASCII)
                if (vdIdent in setOf("CD001", "BEA01", "NSR02", "NSR03")) {
                    return CleanRoomIso9660Session(ParcelFileDescriptor.dup(fd))
                }
            }
        }
        try { Os.pread(fd, header, 0L) } catch (_: Exception) {}
        val pfd = ParcelFileDescriptor.dup(fd)
        return ArchiveSessionImpl(pfd)
    }
}

class ArchiveSessionImpl(private val pfd: ParcelFileDescriptor) : ArchiveSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)
    private val actualFileSize: Long = resolveArchiveDescriptorSize(pfd.fileDescriptor, source.size)

    private data class ArchiveEntryMeta(
        val sanitizedPath: String,
        val rawNameInArchive: String,
        val uncompressedSize: Long,
        val isSingleCompressedStream: Boolean = false,
        val rar5Entry: CleanRoomRar5Parser.Rar5FileEntry? = null
    )

    private val entryMetaList = mutableListOf<ArchiveEntryMeta>()
    private val entriesList = mutableListOf<String>()
    private val tempExtractedFiles = mutableListOf<File>()

    enum class ArcType { ZIP, TAR, GZ, BZ2, XZ, SEVEN_ZIP, RAR4, RAR5, UNKNOWN }
    private var arcType = ArcType.UNKNOWN

    override var isZipBombDetected: Boolean = false
        private set

    override val sanitizedFileEntries: List<String> get() = entriesList
    override fun getLastError(): String? = lastErrorMsg

    companion object {
        private const val MAX_ARCHIVE_ENTRIES = 25_000
        private const val MAX_SINGLE_EXTRACTED_BYTES = 256L * 1024L * 1024L
        private const val MAX_COMPRESSION_RATIO = 250L
        private val DISPLAY_SIZE_SUFFIX_REGEX = Regex(""" \(\d+ B\)$| \(unknown size\)$""", RegexOption.IGNORE_CASE)

        fun sanitizeArchiveEntryPath(rawPath: String): String? {
            if (rawPath.isBlank()) return null
            val clean = rawPath.replace('\u0000', ' ').replace('\\', '/').trim()
            val segments = clean.split('/')
            val safeSegments = ArrayList<String>(segments.size)
            for (seg in segments) {
                val trimmed = seg.trim()
                if (trimmed.isEmpty() || trimmed == ".") continue
                if (trimmed == "..") return null
                safeSegments.add(trimmed)
            }
            if (safeSegments.isEmpty()) return null
            return safeSegments.joinToString("/")
        }

        fun stripDisplaySizeSuffix(displayEntry: String): String {
            return displayEntry.replace(DISPLAY_SIZE_SUFFIX_REGEX, "").trim()
        }

        private fun isLikelyTarHeader(header: ByteBuffer, read: Int): Boolean {
            if (read >= 262 &&
                header.get(257) == 'u'.code.toByte() &&
                header.get(258) == 's'.code.toByte() &&
                header.get(259) == 't'.code.toByte() &&
                header.get(260) == 'a'.code.toByte() &&
                header.get(261) == 'r'.code.toByte()
            ) {
                return true
            }
            if (read < 512) return false
            val firstByte = header.get(0).toInt() and 0xFF
            if (firstByte !in 32..126) return false
            for (i in 148 until 156) {
                val ch = (header.get(i).toInt() and 0xFF).toChar()
                if (ch !in '0'..'7' && ch != ' ' && ch != '\u0000') {
                    return false
                }
            }
            return true
        }
    }

    init {
        inspectArchiveIndex()
    }

    private fun openNewChannel(): SeekableByteChannel {
        return PfdSeekableByteChannel(pfd.fileDescriptor, actualFileSize)
    }

    private fun openPositionalStream(): InputStream {
        return BufferedInputStream(PfdPositionalInputStream(pfd.fileDescriptor, actualFileSize), 32 * 1024)
    }

    private fun inspectArchiveIndex() {
        val header = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        val read = try { source.readAt(0L, header) } catch (_: Exception) { 0 }
        if (read < 2) {
            lastErrorMsg = "Файл архива пуст или недоступен для чтения."
            return
        }

        val b0 = header.get(0).toInt() and 0xFF
        val b1 = header.get(1).toInt() and 0xFF
        val b2 = if (read > 2) header.get(2).toInt() and 0xFF else 0
        val b3 = if (read > 3) header.get(3).toInt() and 0xFF else 0
        val b4 = if (read > 4) header.get(4).toInt() and 0xFF else 0
        val b5 = if (read > 5) header.get(5).toInt() and 0xFF else 0
        val b6 = if (read > 6) header.get(6).toInt() and 0xFF else 0

        val isTar = isLikelyTarHeader(header, read)

        arcType = when {
            b0 == 0x50 && b1 == 0x4B -> ArcType.ZIP
            b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF && b4 == 0x27 && b5 == 0x1C -> ArcType.SEVEN_ZIP
            b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21 && b4 == 0x1A && b5 == 0x07 && b6 == 0x01 -> ArcType.RAR5
            b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21 && b4 == 0x1A && b5 == 0x07 && b6 == 0x00 -> ArcType.RAR4
            b0 == 0x1F && b1 == 0x8B -> ArcType.GZ
            b0 == 0x42 && b1 == 0x5A && b2 == 0x68 -> ArcType.BZ2
            b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58 && b4 == 0x5A && b5 == 0x00 -> ArcType.XZ
            isTar -> ArcType.TAR
            else -> ArcType.ZIP
        }

        when (arcType) {
            ArcType.ZIP -> {
                parseZipEntries()
                if (entryMetaList.isEmpty() && lastErrorMsg?.startsWith("PASSWORD_REQUIRED") != true) {
                    lastErrorMsg = null
                    parseTarEntries { openPositionalStream() }
                    if (entryMetaList.isNotEmpty()) {
                        arcType = ArcType.TAR
                        lastErrorMsg = null
                    } else if (lastErrorMsg == null) {
                        lastErrorMsg = "В архиве не найдено файлов или оглавление повреждено."
                    }
                }
            }
            ArcType.SEVEN_ZIP -> parseSevenZEntries()
            ArcType.TAR -> parseTarEntries { openPositionalStream() }
            ArcType.GZ -> {
                val gzName = try {
                    GzipCompressorInputStream(openPositionalStream(), true).use { gis ->
                        gis.metaData?.fileName?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Throwable) { null } ?: "compressed_stream"
                parseSingleOrTarStream(gzName) { GzipCompressorInputStream(openPositionalStream(), true) }
            }
            ArcType.BZ2 -> parseSingleOrTarStream("compressed_stream") { BZip2CompressorInputStream(openPositionalStream(), true) }
            ArcType.XZ -> parseSingleOrTarStream("compressed_stream") { XZCompressorInputStream(openPositionalStream(), true) }
            ArcType.RAR4 -> parseRar4Entries()
            ArcType.RAR5 -> parseRar5Entries()
            ArcType.UNKNOWN -> {
                lastErrorMsg = "Неизвестный или повреждённый формат архива."
            }
        }
    }

    private fun registerArchiveEntry(
        displayName: String,
        size: Long,
        rawArchiveKey: String = displayName,
        compressedSize: Long = -1L,
        isSingleStream: Boolean = false,
        rar5Entry: CleanRoomRar5Parser.Rar5FileEntry? = null
    ) {
        if (entryMetaList.size >= MAX_ARCHIVE_ENTRIES) return
        val clean = sanitizeArchiveEntryPath(displayName) ?: return
        if (entryMetaList.any { it.sanitizedPath.equals(clean, ignoreCase = true) }) return

        val normalizedSize = if (size >= 0L) size else -1L
        val highRatioBomb = compressedSize > 0L &&
                normalizedSize > 16L * 1024L * 1024L &&
                (normalizedSize / compressedSize) > MAX_COMPRESSION_RATIO

        if (normalizedSize > MAX_SINGLE_EXTRACTED_BYTES * 8L || highRatioBomb) {
            isZipBombDetected = true
            lastErrorMsg = "Обнаружена потенциальная ZIP-бомба (аномальный коэффициент сжатия или размер файла)."
            return
        }

        entryMetaList.add(
            ArchiveEntryMeta(
                sanitizedPath = clean,
                rawNameInArchive = rawArchiveKey,
                uncompressedSize = normalizedSize,
                isSingleCompressedStream = isSingleStream,
                rar5Entry = rar5Entry
            )
        )
        val sizeLabel = if (normalizedSize >= 0L) "$normalizedSize B" else "unknown size"
        entriesList.add("$clean ($sizeLabel)")
    }

    private fun decodeZipEntryName(entry: ZipArchiveEntry): String {
        val rawBytes = entry.rawName ?: return entry.name
        if (entry.generalPurposeBit.usesUTF8ForNames()) {
            return String(rawBytes, StandardCharsets.UTF_8)
        }
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8Decoder.decode(ByteBuffer.wrap(rawBytes)).toString()
        } catch (_: Exception) {
            try {
                String(rawBytes, Charset.forName("CP866"))
            } catch (_: Exception) {
                entry.name
            }
        }
    }

    private fun parseZipEntries() {
        var parsedAny = false
        var sawEncryptedEntry = false
        try {
            openNewChannel().use { channel ->
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setCharset(StandardCharsets.UTF_8)
                    .setUseUnicodeExtraFields(true)
                    .setIgnoreLocalFileHeader(true)
                    .get()
                    .use { zipFile ->
                        val entriesEnum = zipFile.entries
                        while (entriesEnum.hasMoreElements() && entryMetaList.size < MAX_ARCHIVE_ENTRIES) {
                            val entry = entriesEnum.nextElement()
                            if (entry.generalPurposeBit.usesEncryption()) {
                                sawEncryptedEntry = true
                            }
                            if (!entry.isDirectory && !entry.isUnixSymlink) {
                                val decodedName = decodeZipEntryName(entry)
                                registerArchiveEntry(
                                    displayName = decodedName,
                                    size = entry.size,
                                    rawArchiveKey = entry.name,
                                    compressedSize = entry.compressedSize
                                )
                                parsedAny = true
                            }
                        }
                    }
            }
        } catch (_: Throwable) {}

        if (!parsedAny) {
            try {
                ZipArchiveInputStream(openPositionalStream(), "UTF-8", true, true).use { zais ->
                    while (entryMetaList.size < MAX_ARCHIVE_ENTRIES) {
                        val entry = try { zais.nextZipEntry } catch (_: Throwable) { null } ?: break
                        if (entry.generalPurposeBit.usesEncryption()) {
                            sawEncryptedEntry = true
                        }
                        if (!entry.isDirectory && !entry.isUnixSymlink) {
                            val decodedName = decodeZipEntryName(entry)
                            registerArchiveEntry(
                                displayName = decodedName,
                                size = entry.size,
                                rawArchiveKey = entry.name,
                                compressedSize = entry.compressedSize
                            )
                            parsedAny = true
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (!parsedAny) {
            try {
                ZipInputStream(openPositionalStream()).use { zis ->
                    while (entryMetaList.size < MAX_ARCHIVE_ENTRIES) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        if (!entry.isDirectory) {
                            registerArchiveEntry(
                                displayName = entry.name,
                                size = entry.size,
                                rawArchiveKey = entry.name,
                                compressedSize = entry.compressedSize
                            )
                            parsedAny = true
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        if (sawEncryptedEntry && entryMetaList.isEmpty()) {
            lastErrorMsg = "PASSWORD_REQUIRED: Архив ZIP защищён паролем."
        } else if (entryMetaList.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "В архиве не найдено файлов или оглавление повреждено."
        }
    }

    private fun parseSevenZEntries() {
        try {
            openNewChannel().use { channel ->
                SevenZFile.builder()
                    .setSeekableByteChannel(channel)
                    .get()
                    .use { sevenZ ->
                        for (entry in sevenZ.entries) {
                            if (!entry.isDirectory) {
                                registerArchiveEntry(entry.name, entry.size)
                            }
                        }
                    }
            }
        } catch (_: PasswordRequiredException) {
            lastErrorMsg = "PASSWORD_REQUIRED: Архив 7z защищён паролем."
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                lastErrorMsg = "PASSWORD_REQUIRED: Архив 7z защищён паролем."
            } else {
                lastErrorMsg = "Ошибка чтения 7z-архива: ${e.localizedMessage}"
            }
        }
    }

    private fun parseTarEntries(streamSupplier: () -> InputStream) {
        try {
            TarArchiveInputStream(streamSupplier()).use { tis ->
                while (entryMetaList.size < MAX_ARCHIVE_ENTRIES) {
                    val entry = tis.nextEntry ?: break
                    if (!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
                        registerArchiveEntry(entry.name, entry.size)
                    }
                }
            }
        } catch (e: Exception) {
            if (entryMetaList.isEmpty()) {
                lastErrorMsg = "Ошибка чтения TAR-архива: ${e.localizedMessage}"
            }
        }
    }

    private fun inferSingleStreamEntryName(baseName: String, sampleHead: ByteArray, sampleLen: Int): String {
        if (baseName.contains('.') && !baseName.equals("compressed_stream", ignoreCase = true)) {
            return baseName
        }
        if (sampleLen <= 0) return baseName
        val detected = try {
            FileSignatureDetector.resolveFormat(ByteBuffer.wrap(sampleHead, 0, sampleLen))
        } catch (_: Throwable) {
            DetectedFormat.PLAINTEXT
        }
        val ext = when (detected) {
            DetectedFormat.PDF -> "pdf"
            DetectedFormat.SVG -> "svg"
            DetectedFormat.FB2 -> "fb2"
            DetectedFormat.EPUB -> "epub"
            DetectedFormat.DOCX -> "docx"
            DetectedFormat.XLSX -> "xlsx"
            DetectedFormat.PPTX -> "pptx"
            DetectedFormat.SQLITE -> "sqlite"
            DetectedFormat.PARQUET -> "parquet"
            DetectedFormat.DBF -> "dbf"
            DetectedFormat.PNG -> "png"
            DetectedFormat.JPEG -> "jpg"
            DetectedFormat.WEBP -> "webp"
            DetectedFormat.GIF -> "gif"
            DetectedFormat.BMP -> "bmp"
            DetectedFormat.TIFF -> "tiff"
            DetectedFormat.DJVU -> "djvu"
            DetectedFormat.FONT -> "ttf"
            DetectedFormat.RTF -> "rtf"
            else -> {
                val headStr = String(sampleHead, 0, minOf(sampleLen, 256), StandardCharsets.UTF_8).trimStart()
                when {
                    headStr.startsWith("{") || headStr.startsWith("[") -> "json"
                    headStr.startsWith("<?xml", ignoreCase = true) || headStr.startsWith("<") -> "xml"
                    else -> "txt"
                }
            }
        }
        return "${baseName.removeSuffix(".$ext")}.$ext"
    }

    private fun parseSingleOrTarStream(defaultName: String, decompressorSupplier: () -> InputStream) {
        val beforeCount = entryMetaList.size
        var sampleBytes = ByteArray(0)
        var sampleLen = 0
        val looksLikeNestedTar = try {
            decompressorSupplier().use { input ->
                val head = ByteArray(512)
                var total = 0
                while (total < 512) {
                    val r = input.read(head, total, 512 - total)
                    if (r <= 0) break
                    total += r
                }
                sampleBytes = head
                sampleLen = total
                isLikelyTarHeader(ByteBuffer.wrap(head), total)
            }
        } catch (_: Throwable) {
            false
        }

        if (looksLikeNestedTar) {
            parseTarEntries(decompressorSupplier)
            if (entryMetaList.size > beforeCount) {
                lastErrorMsg = null
                return
            }
        }

        lastErrorMsg = null
        val effectiveName = inferSingleStreamEntryName(defaultName, sampleBytes, sampleLen)
        registerArchiveEntry(effectiveName, -1L, rawArchiveKey = effectiveName, isSingleStream = true)
    }

    private fun parseRar4Entries() {
        var tmpRar: File? = null
        try {
            tmpRar = createSafeTempFile("omni_rar4_", ".rar")
            FileOutputStream(tmpRar).use { fos ->
                openPositionalStream().use { input ->
                    copyWithSlidingWindow(input, fos, MAX_SINGLE_EXTRACTED_BYTES)
                }
            }
            Archive(tmpRar).use { rar ->
                if (rar.isEncrypted || rar.isPasswordProtected) {
                    lastErrorMsg = "PASSWORD_REQUIRED: Архив RAR защищён паролем."
                    return
                }
                for (fh in rar.fileHeaders) {
                    if (fh.isEncrypted) {
                        lastErrorMsg = "PASSWORD_REQUIRED: Архив RAR защищён паролем."
                        return
                    }
                    if (!fh.isDirectory) {
                        val name = fh.fileName
                        registerArchiveEntry(
                            displayName = name,
                            size = fh.fullUnpackSize,
                            rawArchiveKey = name,
                            compressedSize = fh.fullPackSize
                        )
                    }
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения RAR-архива: ${e.localizedMessage}"
        } finally {
            try { tmpRar?.delete() } catch (_: Throwable) {}
        }
    }

    private fun parseRar5Entries() {
        try {
            val result = CleanRoomRar5Parser.parseEntries(source, actualFileSize)
            if (result.isHeaderEncrypted) {
                lastErrorMsg = "PASSWORD_REQUIRED: Архив RAR 5.0 защищён паролем (заголовки зашифрованы)."
                return
            }
            var sawEncryptedFile = false
            for (fh in result.entries) {
                if (fh.isEncrypted) {
                    sawEncryptedFile = true
                }
                if (!fh.isDirectory && !fh.isSymlink) {
                    registerArchiveEntry(
                        displayName = fh.rawName,
                        size = fh.unpackedSize,
                        rawArchiveKey = fh.rawName,
                        compressedSize = fh.packedSize,
                        rar5Entry = fh
                    )
                }
            }
            if (entryMetaList.isEmpty()) {
                lastErrorMsg = if (sawEncryptedFile) {
                    "PASSWORD_REQUIRED: Архив RAR 5.0 защищён паролем."
                } else {
                    "В архиве RAR 5.0 не найдено файлов или оглавление повреждено."
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения архива RAR 5.0: ${e.localizedMessage}"
        }
    }

    private fun createSafeTempFile(prefix: String, suffix: String): File {
        val candidateDirs = listOfNotNull(
            System.getProperty("omnireader.cache.dir")?.let { File(it) },
            System.getProperty("java.io.tmpdir")?.let { File(it) },
            File("/data/data/me.hlebkukleuski.fullfilestudio/cache"),
            File("/data/data/me.hlebkukleuski.fullfilestudio.sitedl/cache"),
            File("/data/user/0/me.hlebkukleuski.fullfilestudio/cache"),
            File("/data/user/0/me.hlebkukleuski.fullfilestudio.sitedl/cache")
        )
        for (dir in candidateDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                if (dir.exists() && dir.canWrite()) {
                    val f = File.createTempFile(prefix, suffix, dir)
                    f.deleteOnExit()
                    return f
                }
            } catch (_: Throwable) {}
        }
        val fallback = File.createTempFile(prefix, suffix)
        fallback.deleteOnExit()
        return fallback
    }

    override fun openVirtualFile(entryPath: String): ParcelFileDescriptor? {
        if (isClosed) return null
        val stripped = stripDisplaySizeSuffix(entryPath)
        val requestedClean = sanitizeArchiveEntryPath(stripped) ?: stripped.replace('\\', '/').trimStart('/')

        val targetMeta = entryMetaList.firstOrNull {
            it.sanitizedPath.equals(requestedClean, ignoreCase = true) ||
                    it.rawNameInArchive.replace('\\', '/').trimStart('/').equals(requestedClean, ignoreCase = true) ||
                    it.sanitizedPath.equals(entryPath.trim(), ignoreCase = true)
        } ?: return null

        val extSuffix = targetMeta.sanitizedPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val safeSuffix = if (extSuffix.isNotEmpty() && extSuffix.length <= 10 && extSuffix.all { it.isLetterOrDigit() }) {
            ".$extSuffix"
        } else {
            ".tmp"
        }

        var tempFile: File? = null
        return try {
            tempFile = createSafeTempFile("omni_vfile_", safeSuffix)
            val extractedSuccess = extractEntryToFile(targetMeta, tempFile)

            if (!extractedSuccess || !tempFile.exists()) {
                tempFile.delete()
                return null
            }

            synchronized(this) {
                if (isClosed) {
                    tempFile.delete()
                    return null
                }
                tempExtractedFiles.add(tempFile)
            }

            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
            try { tempFile?.delete() } catch (_: Throwable) {}
            null
        } catch (_: Throwable) {
            try { tempFile?.delete() } catch (_: Throwable) {}
            null
        }
    }

    private fun matchesTargetEntry(candidateRawName: String, target: ArchiveEntryMeta): Boolean {
        if (candidateRawName == target.rawNameInArchive || candidateRawName == target.sanitizedPath) return true
        val normCandidate = sanitizeArchiveEntryPath(candidateRawName)
            ?: candidateRawName.replace('\\', '/').trimStart('/')
        val normRawTarget = sanitizeArchiveEntryPath(target.rawNameInArchive)
            ?: target.rawNameInArchive.replace('\\', '/').trimStart('/')
        return normCandidate.equals(target.sanitizedPath, ignoreCase = true) ||
                normCandidate.equals(normRawTarget, ignoreCase = true)
    }

    private inline fun writeFreshFile(destFile: File, block: (OutputStream) -> Boolean): Boolean {
        FileOutputStream(destFile, false).use { fos ->
            val ok = block(fos)
            if (ok) fos.flush()
            return ok
        }
    }

    private fun extractEntryToFile(target: ArchiveEntryMeta, destFile: File): Boolean {
        return when (arcType) {
            ArcType.ZIP -> extractZipEntryToFile(target, destFile)
            ArcType.SEVEN_ZIP -> {
                var extracted = false
                try {
                    openNewChannel().use { channel ->
                        SevenZFile.builder()
                            .setSeekableByteChannel(channel)
                            .get()
                            .use { sevenZ ->
                                var entry = sevenZ.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory && matchesTargetEntry(entry.name, target)) {
                                        sevenZ.getInputStream(entry).use { sis ->
                                            extracted = writeFreshFile(destFile) { fos ->
                                                copyWithSlidingWindow(sis, fos, target.uncompressedSize)
                                                true
                                            }
                                        }
                                        break
                                    }
                                    entry = sevenZ.nextEntry
                                }
                            }
                    }
                } catch (_: PasswordRequiredException) {
                    throw SecurityException("PASSWORD_REQUIRED: Архив 7z защищён паролем.")
                } catch (sec: SecurityException) {
                    throw sec
                } catch (_: Throwable) {}
                extracted
            }
            ArcType.TAR -> writeFreshFile(destFile) { fos ->
                extractFromTarStream({ openPositionalStream() }, target, fos)
            }
            ArcType.GZ -> writeFreshFile(destFile) { fos ->
                extractFromCompressedStream({ GzipCompressorInputStream(openPositionalStream(), true) }, target, fos)
            }
            ArcType.BZ2 -> writeFreshFile(destFile) { fos ->
                extractFromCompressedStream({ BZip2CompressorInputStream(openPositionalStream(), true) }, target, fos)
            }
            ArcType.XZ -> writeFreshFile(destFile) { fos ->
                extractFromCompressedStream({ XZCompressorInputStream(openPositionalStream(), true) }, target, fos)
            }
            ArcType.RAR4 -> {
                var extracted = false
                var tmpRar: File? = null
                try {
                    tmpRar = createSafeTempFile("omni_rar4_ext_", ".rar")
                    FileOutputStream(tmpRar).use { rfos ->
                        openPositionalStream().use { input ->
                            copyWithSlidingWindow(input, rfos, MAX_SINGLE_EXTRACTED_BYTES)
                        }
                    }
                    Archive(tmpRar).use { rar ->
                        for (fh in rar.fileHeaders) {
                            val name = fh.fileName
                            if (!fh.isDirectory && matchesTargetEntry(name, target)) {
                                if (fh.isEncrypted) {
                                    throw SecurityException("PASSWORD_REQUIRED: Запись в архиве RAR защищена паролем.")
                                }
                                extracted = writeFreshFile(destFile) { fos ->
                                    rar.extractFile(fh, fos)
                                    true
                                }
                                break
                            }
                        }
                    }
                } catch (sec: SecurityException) {
                    throw sec
                } catch (_: Throwable) {
                } finally {
                    try { tmpRar?.delete() } catch (_: Throwable) {}
                }
                extracted
            }
            ArcType.RAR5 -> extractRar5EntryToFile(target, destFile)
            else -> false
        }
    }

    private fun extractRar5EntryToFile(target: ArchiveEntryMeta, destFile: File): Boolean {
        val r5 = target.rar5Entry ?: return false
        if (r5.isEncrypted) {
            throw SecurityException("PASSWORD_REQUIRED: Файл в архиве RAR 5.0 защищён паролем.")
        }
        if (r5.packedSize < 0L || r5.packedSize > MAX_SINGLE_EXTRACTED_BYTES || r5.dataOffset + r5.packedSize > actualFileSize) {
            return false
        }

        if (r5.compressionMethod == 0) {
            return writeFreshFile(destFile) { fos ->
                PfdPositionalInputStream(pfd.fileDescriptor, actualFileSize, r5.dataOffset, r5.packedSize).use { input ->
                    copyWithSlidingWindow(input, fos, r5.unpackedSize, r5.packedSize)
                }
                true
            }
        }

        val compSize = r5.packedSize.toInt()
        val compBuf = ByteBuffer.allocateDirect(compSize)
        val got = source.readAt(r5.dataOffset, compBuf)
        if (got < compSize) return false
        compBuf.flip()
        val compBytes = ByteArray(compSize)
        compBuf.get(compBytes)

        val bos = ByteArrayOutputStream(minOf(maxOf(r5.unpackedSize, 4096L), 64L * 1024L * 1024L).toInt())
        val ok = CleanRoomRar5Parser.decompressRar5Stream(
            compressed = compBytes,
            expectedUnpackedSize = r5.unpackedSize,
            dictSizeBytes = r5.dictSizeBytes,
            output = bos,
            maxAllowedBytes = MAX_SINGLE_EXTRACTED_BYTES
        )
        if (!ok) return false

        val unpackedBytes = bos.toByteArray()
        return writeFreshFile(destFile) { fos ->
            ByteArrayInputStream(unpackedBytes).use { bais ->
                copyWithSlidingWindow(bais, fos, r5.unpackedSize, r5.packedSize)
            }
            true
        }
    }

    private fun extractZipEntryToFile(target: ArchiveEntryMeta, destFile: File): Boolean {
        for (ignoreLocalHeader in booleanArrayOf(false, true)) {
            try {
                openNewChannel().use { channel ->
                    ZipFile.builder()
                        .setSeekableByteChannel(channel)
                        .setCharset(StandardCharsets.UTF_8)
                        .setUseUnicodeExtraFields(true)
                        .setIgnoreLocalFileHeader(ignoreLocalHeader)
                        .get()
                        .use { zipFile ->
                            var matchedEntry: ZipArchiveEntry? = zipFile.getEntry(target.rawNameInArchive)
                                ?: zipFile.getEntry(target.sanitizedPath)
                            if (matchedEntry == null) {
                                val entriesEnum = zipFile.entries
                                while (entriesEnum.hasMoreElements()) {
                                    val candidate = entriesEnum.nextElement()
                                    if (!candidate.isDirectory) {
                                        val decoded = decodeZipEntryName(candidate)
                                        if (matchesTargetEntry(decoded, target) || matchesTargetEntry(candidate.name, target)) {
                                            matchedEntry = candidate
                                            break
                                        }
                                    }
                                }
                            }
                            if (matchedEntry != null && matchedEntry.generalPurposeBit.usesEncryption()) {
                                throw SecurityException("PASSWORD_REQUIRED: Файл в архиве ZIP защищён паролем.")
                            }
                            if (matchedEntry != null && !matchedEntry.isUnixSymlink && zipFile.canReadEntryData(matchedEntry)) {
                                zipFile.getInputStream(matchedEntry).use { zis ->
                                    return writeFreshFile(destFile) { fos ->
                                        copyWithSlidingWindow(zis, fos, target.uncompressedSize, matchedEntry.compressedSize)
                                        true
                                    }
                                }
                            }
                        }
                }
            } catch (sec: SecurityException) {
                throw sec
            } catch (_: Throwable) {}
        }

        for (charsetName in arrayOf("UTF-8", "CP866")) {
            try {
                ZipArchiveInputStream(openPositionalStream(), charsetName, true, true).use { zais ->
                    while (true) {
                        val entry = try { zais.nextZipEntry } catch (_: Throwable) { null } ?: break
                        if (!entry.isDirectory && !entry.isUnixSymlink) {
                            val decoded = decodeZipEntryName(entry)
                            if (matchesTargetEntry(decoded, target) || matchesTargetEntry(entry.name, target)) {
                                if (entry.generalPurposeBit.usesEncryption()) {
                                    throw SecurityException("PASSWORD_REQUIRED: Файл в архиве ZIP защищён паролем.")
                                }
                                return writeFreshFile(destFile) { fos ->
                                    copyWithSlidingWindow(zais, fos, target.uncompressedSize, entry.compressedSize)
                                    true
                                }
                            }
                        }
                    }
                }
            } catch (sec: SecurityException) {
                throw sec
            } catch (_: Throwable) {}
        }

        return try {
            ZipInputStream(openPositionalStream()).use { zis ->
                while (true) {
                    val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                    if (!entry.isDirectory && matchesTargetEntry(entry.name, target)) {
                        return@use writeFreshFile(destFile) { fos ->
                            copyWithSlidingWindow(zis, fos, target.uncompressedSize, entry.compressedSize)
                            true
                        }
                    }
                    try { zis.closeEntry() } catch (_: Throwable) {}
                }
                false
            }
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractFromTarStream(
        streamSupplier: () -> InputStream,
        target: ArchiveEntryMeta,
        fos: OutputStream
    ): Boolean {
        return try {
            TarArchiveInputStream(streamSupplier()).use { tis ->
                while (true) {
                    val entry = tis.nextEntry ?: break
                    if (!entry.isDirectory && !entry.isSymbolicLink && matchesTargetEntry(entry.name, target)) {
                        copyWithSlidingWindow(tis, fos, target.uncompressedSize)
                        return@use true
                    }
                }
                false
            }
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractFromCompressedStream(
        decompressorSupplier: () -> InputStream,
        target: ArchiveEntryMeta,
        fos: OutputStream
    ): Boolean {
        if (!target.isSingleCompressedStream && !target.sanitizedPath.startsWith("compressed_stream")) {
            if (extractFromTarStream(decompressorSupplier, target, fos)) return true
        }
        return try {
            decompressorSupplier().use { input ->
                copyWithSlidingWindow(input, fos, target.uncompressedSize)
                true
            }
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyWithSlidingWindow(
        input: InputStream,
        output: OutputStream,
        declaredSize: Long,
        compressedSize: Long = -1L
    ) {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        val limit = if (declaredSize > 0L) min(declaredSize * 2L + 65536L, MAX_SINGLE_EXTRACTED_BYTES) else MAX_SINGLE_EXTRACTED_BYTES
        val ratioCap = if (compressedSize > 0L) {
            maxOf(compressedSize * MAX_COMPRESSION_RATIO, 16L * 1024L * 1024L)
        } else {
            MAX_SINGLE_EXTRACTED_BYTES
        }

        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (r == 0) continue
            total += r
            if (total > MAX_SINGLE_EXTRACTED_BYTES || total > limit || total > ratioCap) {
                throw SecurityException("Превышен безопасный предел извлечения файла (защита от Zip Bomb).")
            }
            output.write(buf, 0, r)
        }
    }

    override val pageCount: Int get() = 1

    override fun getPageText(pageIndex: Int): String {
        return entriesList.joinToString("\n")
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 22, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }
        canvas.drawText("Archive entries: ${entriesList.size}", 32f, 48f, paint)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        return if (entriesList.any { it.contains(query, ignoreCase = true) }) 0L else -1L
    }

    override fun close() {
        val filesToDelete: List<File>
        synchronized(this) {
            if (isClosed) return
            isClosed = true
            filesToDelete = tempExtractedFiles.toList()
            tempExtractedFiles.clear()
        }
        try { pfd.close() } catch (_: Throwable) {}
        filesToDelete.forEach { file ->
            try { file.delete() } catch (_: Throwable) {}
        }
    }
}