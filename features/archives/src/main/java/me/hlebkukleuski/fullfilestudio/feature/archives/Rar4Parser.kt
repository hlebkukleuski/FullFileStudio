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
 * RAR 2.x/3.x/4.x decompression tables and framing reference derived from
 * libarchive (archive_read_support_format_rar.c),
 * Copyright (c) 2003-2007 Tim Kientzle, Copyright (c) 2011 Andres Mejia,
 * and Copyright (c) 2011 Michihiro NAKAJIMA, BSD-2-Clause License.
 */

package me.hlebkukleuski.fullfilestudio.feature.archives

import com.omnireader.core.engine.ZeroCacheReadableSource
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal object Rar4Parser {

    data class Rar4FileEntry(
        val rawName: String,
        val unpackedSize: Long,
        val packedSize: Long,
        val dataOffset: Long,
        val method: Int,
        val unpVer: Int,
        val dictSizeBytes: Int,
        val isSolid: Boolean,
        val isDirectory: Boolean,
        val isEncrypted: Boolean,
        val dataCrc32: Int,
        val isSplit: Boolean = false
    )

    data class Rar4ParseResult(
        val entries: List<Rar4FileEntry>,
        val isHeaderEncrypted: Boolean
    )

    private const val MARKER_LEN = 7
    private const val HEAD_MAIN = 0x73
    private const val HEAD_FILE = 0x74
    private const val HEAD_END = 0x7B

    private const val MHD_PASSWORD = 0x0080
    private const val LHD_SPLIT_BEFORE = 0x0001
    private const val LHD_SPLIT_AFTER = 0x0002
    private const val LHD_PASSWORD = 0x0004
    private const val LHD_SOLID = 0x0010
    private const val LHD_WINDOWMASK = 0x00E0
    private const val LHD_DIRECTORY = 0x00E0
    private const val LHD_LARGE = 0x0100
    private const val LHD_UNICODE = 0x0200
    private const val LHD_LONG_BLOCK = 0x8000

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    fun parseEntries(source: ZeroCacheReadableSource, fileSize: Long): Rar4ParseResult =
        parseEntries(CleanRoomRar5Parser.PositionalReader { off, buf -> source.readAt(off, buf) }, fileSize)

    fun parseEntries(source: CleanRoomRar5Parser.PositionalReader, fileSize: Long): Rar4ParseResult {
        val sigBuf = ByteBuffer.allocateDirect(MARKER_LEN)
        if (source.readAt(0L, sigBuf) < MARKER_LEN) return Rar4ParseResult(emptyList(), false)
        if (sigBuf.get(0) != 0x52.toByte() || sigBuf.get(1) != 0x61.toByte() ||
            sigBuf.get(2) != 0x72.toByte() || sigBuf.get(3) != 0x21.toByte() ||
            sigBuf.get(4) != 0x1A.toByte() || sigBuf.get(5) != 0x07.toByte() ||
            sigBuf.get(6) != 0x00.toByte()
        ) {
            return Rar4ParseResult(emptyList(), false)
        }

        val entries = ArrayList<Rar4FileEntry>()
        var offset = MARKER_LEN.toLong()
        val headBuf = ByteBuffer.allocateDirect(7)
        val headBytes = ByteArray(7)
        val maxEntries = 25_000

        while (offset + 7L <= fileSize && entries.size < maxEntries) {
            headBuf.clear()
            if (source.readAt(offset, headBuf) < 7) break
            headBuf.flip()
            headBuf.get(headBytes, 0, 7)

            val headType = headBytes[2].toInt() and 0xFF
            val headFlags = u16(headBytes, 3)
            val headSize = u16(headBytes, 5)
            if (headSize < 7 || offset + headSize > fileSize) break

            if (headType == HEAD_END) break

            if (headType == HEAD_MAIN) {
                if ((headFlags and MHD_PASSWORD) != 0) {
                    return Rar4ParseResult(entries, isHeaderEncrypted = true)
                }
                offset += headSize
                continue
            }

            val fullBuf = ByteBuffer.allocateDirect(headSize)
            if (source.readAt(offset, fullBuf) < headSize) break
            fullBuf.flip()
            val h = ByteArray(headSize)
            fullBuf.get(h)

            var addSize = if ((headFlags and LHD_LONG_BLOCK) != 0 || headType == HEAD_FILE) {
                if (headSize >= 11) u32(h, 7) else 0L
            } else 0L

            if (headType == HEAD_FILE && headSize >= 32) {
                var packSize = u32(h, 7)
                var unpSize = u32(h, 11)
                val fileCrc = u32(h, 16).toInt()
                val unpVer = h[24].toInt() and 0xFF
                val method = h[25].toInt() and 0xFF
                val nameSize = u16(h, 26)
                var nameOff = 32
                if ((headFlags and LHD_LARGE) != 0) {
                    if (headSize < 40) break
                    packSize = packSize or (u32(h, 32) shl 32)
                    unpSize = unpSize or (u32(h, 36) shl 32)
                    nameOff = 40
                }
                addSize = packSize
                val actualNameLen = nameSize.coerceAtMost((headSize - nameOff).coerceAtLeast(0))
                val rawName = decodeRar4FileName(h, nameOff, actualNameLen, (headFlags and LHD_UNICODE) != 0)
                val winBits = (headFlags and LHD_WINDOWMASK) ushr 5
                val isDir = (headFlags and LHD_WINDOWMASK) == LHD_DIRECTORY
                val dictSize = if (isDir) 0 else (65536 shl winBits.coerceIn(0, 6))
                val dataOffset = offset + headSize

                if (rawName.isNotEmpty()) {
                    entries.add(
                        Rar4FileEntry(
                            rawName = rawName,
                            unpackedSize = unpSize,
                            packedSize = packSize,
                            dataOffset = dataOffset,
                            method = method,
                            unpVer = unpVer,
                            dictSizeBytes = dictSize,
                            isSolid = (headFlags and LHD_SOLID) != 0,
                            isDirectory = isDir,
                            isEncrypted = (headFlags and LHD_PASSWORD) != 0,
                            dataCrc32 = fileCrc,
                            isSplit = (headFlags and (LHD_SPLIT_BEFORE or LHD_SPLIT_AFTER)) != 0
                        )
                    )
                }
            }

            val next = offset + headSize + addSize
            if (next <= offset) break
            offset = next
        }

        return Rar4ParseResult(entries, isHeaderEncrypted = false)
    }

    internal fun decodeRar4FileName(buf: ByteArray, offset: Int, length: Int, isUnicode: Boolean): String {
        if (length <= 0 || offset + length > buf.size) return ""
        var zeroPos = -1
        for (i in 0 until length) {
            if (buf[offset + i].toInt() == 0) {
                zeroPos = i
                break
            }
        }
        val stdLen = if (zeroPos >= 0) zeroPos else length
        if (!isUnicode || zeroPos < 0 || zeroPos + 1 >= length) {
            return String(buf, offset, stdLen, StandardCharsets.UTF_8)
        }
        val encStart = offset + zeroPos + 1
        val encEnd = offset + length
        var encPos = encStart
        val highByte = (buf[encPos++].toInt() and 0xFF) shl 8
        var flagBits = 0
        var flags = 0
        var stdPos = 0
        val out = StringBuilder(stdLen)
        while (encPos < encEnd) {
            if (flagBits == 0) {
                flags = buf[encPos++].toInt() and 0xFF
                flagBits = 8
            }
            val mode = (flags ushr 6) and 3
            flags = (flags shl 2) and 0xFF
            flagBits -= 2
            when (mode) {
                0 -> {
                    if (encPos >= encEnd) break
                    out.append((buf[encPos++].toInt() and 0xFF).toChar())
                    stdPos++
                }
                1 -> {
                    if (encPos >= encEnd) break
                    out.append((highByte or (buf[encPos++].toInt() and 0xFF)).toChar())
                    stdPos++
                }
                2 -> {
                    if (encPos + 1 >= encEnd) break
                    val lo = buf[encPos++].toInt() and 0xFF
                    val hi = buf[encPos++].toInt() and 0xFF
                    out.append(((hi shl 8) or lo).toChar())
                    stdPos++
                }
                3 -> {
                    if (encPos >= encEnd) break
                    var count = buf[encPos++].toInt() and 0xFF
                    if ((count and 0x80) != 0) {
                        if (encPos >= encEnd) break
                        val corr = buf[encPos++].toInt() and 0xFF
                        count = (count and 0x7F) + 2
                        for (k in 0 until count) {
                            if (stdPos >= stdLen) break
                            val lo = ((buf[offset + stdPos].toInt() and 0xFF) + corr) and 0xFF
                            out.append((highByte or lo).toChar())
                            stdPos++
                        }
                    } else {
                        count += 2
                        for (k in 0 until count) {
                            if (stdPos >= stdLen) break
                            out.append((buf[offset + stdPos].toInt() and 0xFF).toChar())
                            stdPos++
                        }
                    }
                }
            }
        }
        return if (out.isNotEmpty()) out.toString() else String(buf, offset, stdLen, StandardCharsets.UTF_8)
    }

    fun unpackEntry(
        all: List<Rar4FileEntry>,
        target: Rar4FileEntry,
        maxFileBytes: Long,
        readPacked: (Rar4FileEntry) -> ByteArray
    ): ByteArray {
        if (target.isEncrypted) throw SecurityException("UNSUPPORTED_ENCRYPTION: Password decryption for encrypted RAR archives is not supported in this version.")
        if (target.isSplit) throw IllegalStateException("Multi-volume RAR4 archives are not supported")
        if (target.unpackedSize < 0L || target.unpackedSize > maxFileBytes) {
            throw IllegalStateException("RAR entry too large")
        }
        if (target.unpackedSize == 0L) {
            val empty = ByteArray(0)
            verifyCrc(target, empty)
            return empty
        }
        if (target.method == 0x30) {
            val raw = readPacked(target)
            if (raw.size.toLong() != target.unpackedSize) throw IllegalStateException("Stored RAR size mismatch")
            verifyCrc(target, raw)
            return raw
        }

        val chain = ArrayList<Rar4FileEntry>()
        if (target.isSolid) {
            val idx = all.indexOfFirst { it.dataOffset == target.dataOffset }
            if (idx < 0) throw IllegalStateException("Solid RAR entry not found")
            var start = idx
            while (start > 0 && all[start].isSolid) start--
            for (i in start..idx) {
                val e = all[i]
                if (!e.isDirectory && e.method != 0x30 && e.unpackedSize > 0) chain.add(e)
            }
        } else {
            chain.add(target)
        }
        if (chain.isEmpty()) {
            val empty = ByteArray(0)
            verifyCrc(target, empty)
            return empty
        }

        val winSize = maxOf(chain.maxOf { it.dictSizeBytes }, 1 shl 16).coerceAtMost(1 shl 22)
        val unpacker = Rar29Unpacker(winSize, maxFileBytes)
        var result: ByteArray? = null
        for (e in chain) {
            if (e.isEncrypted) throw SecurityException("UNSUPPORTED_ENCRYPTION: Password decryption for encrypted RAR archives is not supported in this version.")
            if (e.isSplit) throw IllegalStateException("Multi-volume RAR4 archives are not supported")
            val packed = readPacked(e)
            val data = when {
                e.unpVer >= 29 -> unpacker.unpack29(packed, e.unpackedSize, e.isSolid)
                e.unpVer in 20..28 -> unpacker.unpack20(packed, e.unpackedSize, e.isSolid)
                else -> throw IllegalStateException("Unsupported legacy RAR version ${e.unpVer}")
            }
            if (e.dataOffset == target.dataOffset) result = data
        }
        val out = result ?: ByteArray(0)
        verifyCrc(target, out)
        return out
    }

    private fun verifyCrc(entry: Rar4FileEntry, data: ByteArray) {
        val crc = CRC32()
        crc.update(data)
        if (crc.value.toInt() != entry.dataCrc32) {
            throw IllegalStateException("RAR CRC32 mismatch")
        }
    }

    private class BitReader(private val data: ByteArray) {
        var bitPos = 0
        val totalBits = data.size * 8

        fun peekBits(n: Int): Int {
            if (n <= 0) return 0
            val byteIdx = bitPos ushr 3
            val bitOff = bitPos and 7
            var acc = 0
            for (i in 0 until 4) {
                val b = if (byteIdx + i < data.size) (data[byteIdx + i].toInt() and 0xFF) else 0
                acc = (acc shl 8) or b
            }
            return (acc ushr (32 - bitOff - n)) and ((1 shl n) - 1)
        }

        fun readBits(n: Int): Int {
            val v = peekBits(n)
            bitPos += n
            return v
        }

        fun alignByte() {
            val rem = bitPos and 7
            if (rem != 0) bitPos += 8 - rem
        }

        fun hasBits(): Boolean = bitPos < totalBits
    }

    private class HuffmanDecoder(val maxSymbols: Int) {
        private val decodeLen = IntArray(16)
        private val decodePos = IntArray(16)
        private val decodeNum = IntArray(maxSymbols)
        private val quickLen = IntArray(1 shl QUICK_BITS)
        private val quickSym = IntArray(1 shl QUICK_BITS)
        var maxLen = 0
        private var totalSymbols = 0

        fun build(lengths: IntArray, count: Int) {
            val lenCount = IntArray(16)
            maxLen = 0
            for (i in 0 until count) {
                val l = lengths[i].coerceIn(0, 15)
                if (l > 0) {
                    lenCount[l]++
                    if (l > maxLen) maxLen = l
                }
            }
            var code = 0
            var pos = 0
            decodeLen[0] = 0
            decodePos[0] = 0
            for (bits in 1..15) {
                code = (code + lenCount[bits - 1]) shl 1
                decodeLen[bits] = (code + lenCount[bits]) shl (16 - bits)
                decodePos[bits] = pos
                pos += lenCount[bits]
            }
            totalSymbols = pos
            val tmpPos = decodePos.copyOf()
            decodeNum.fill(0)
            for (sym in 0 until count) {
                val l = lengths[sym].coerceIn(0, 15)
                if (l > 0) {
                    decodeNum[tmpPos[l]++] = sym
                }
            }
            quickLen.fill(0)
            quickSym.fill(0)
            for (bits in 1..QUICK_BITS) {
                val startPos = decodePos[bits]
                val nSyms = lenCount[bits]
                if (nSyms == 0) continue
                var baseCode = (decodeLen[bits] ushr (16 - bits)) - nSyms
                val shift = QUICK_BITS - bits
                for (k in 0 until nSyms) {
                    val sym = decodeNum[startPos + k]
                    val prefix = baseCode shl shift
                    val span = 1 shl shift
                    for (j in 0 until span) {
                        val idx = prefix + j
                        if (idx in quickLen.indices) {
                            quickLen[idx] = bits
                            quickSym[idx] = sym
                        }
                    }
                    baseCode++
                }
            }
        }

        fun decode(br: BitReader): Int {
            val bits16 = br.peekBits(16)
            val qIdx = bits16 ushr (16 - QUICK_BITS)
            val ql = quickLen[qIdx]
            if (ql > 0) {
                br.bitPos += ql
                return quickSym[qIdx]
            }
            var bits = QUICK_BITS + 1
            while (bits < 15 && bits16 >= decodeLen[bits]) bits++
            if (bits16 >= decodeLen[bits]) return -1
            br.bitPos += bits
            val dist = (bits16 - decodeLen[bits - 1]) ushr (16 - bits)
            val idx = decodePos[bits] + dist
            return if (idx in 0 until totalSymbols) decodeNum[idx] else -1
        }

        companion object {
            private const val QUICK_BITS = 10
        }
    }

    private class Rar29Unpacker(windowSize: Int, private val maxFileBytes: Long) {
        private val winSize: Int
        private val winMask: Int
        private val window: ByteArray
        private var writePos = 0
        private val oldDist = IntArray(4)
        private var oldDistPtr = 0
        private var lastDist = 0
        private var lastLength = 0
        private var lowDistRepCount = 0
        private var prevLowDist = 0
        private var tablesLoaded = false
        private var isPpmdBlock = false
        private var ppmdValid = false
        private var ppmdEscape = 2
        private val ppmd = Rar4Ppmd7Decoder()

        private val mainDec = HuffmanDecoder(NC)
        private val distDec = HuffmanDecoder(DC)
        private val lowDistDec = HuffmanDecoder(LDC)
        private val repLenDec = HuffmanDecoder(RC)
        private val bitLenDec = HuffmanDecoder(BC)
        private val oldTable = IntArray(HUFF_TABLE_SIZE)

        private val mainDec20 = HuffmanDecoder(NC20)
        private val distDec20 = HuffmanDecoder(DC20)
        private val repLenDec20 = HuffmanDecoder(RC20)
        private val oldTable20 = IntArray(NC20 + DC20 + RC20)

        init {
            var s = 1 shl 16
            while (s < windowSize && s < (1 shl 22)) s = s shl 1
            winSize = s
            winMask = s - 1
            window = ByteArray(s)
        }

        private fun resetState() {
            oldDist.fill(0)
            oldDistPtr = 0
            lastDist = 0
            lastLength = 0
            lowDistRepCount = 0
            prevLowDist = 0
            oldTable.fill(0)
            oldTable20.fill(0)
            tablesLoaded = false
            isPpmdBlock = false
            ppmdValid = false
            ppmdEscape = 2
            writePos = 0
        }

        fun unpack29(packed: ByteArray, unpackedSize: Long, solid: Boolean): ByteArray {
            if (unpackedSize < 0L || unpackedSize > maxFileBytes) throw IllegalStateException("RAR29 entry too large")
            if (!solid) resetState()
            val out = ByteArray(unpackedSize.toInt())
            var produced = 0
            val br = BitReader(packed)
            val readPpmdByte = {
                if (br.hasBits()) br.readBits(8) else 0
            }
            if (!solid || !tablesLoaded) {
                readTables29(br, readPpmdByte)
                tablesLoaded = true
            }

            fun emitByte(b: Byte) {
                window[writePos] = b
                writePos = (writePos + 1) and winMask
                if (produced < out.size) out[produced++] = b
            }

            fun copyMatch(length: Int, dist: Int) {
                var src = (writePos - dist) and winMask
                for (i in 0 until length) {
                    val b = window[src]
                    src = (src + 1) and winMask
                    emitByte(b)
                }
            }

            fun insertOldDist(dist: Int) {
                oldDist[3] = oldDist[2]
                oldDist[2] = oldDist[1]
                oldDist[1] = oldDist[0]
                oldDist[0] = dist
            }

            while (produced < out.size) {
                if (isPpmdBlock) {
                    val sym = ppmd.decodeSymbol(readPpmdByte)
                    if (sym < 0) throw IllegalStateException("Invalid RAR29 PPMd symbol")
                    if (sym != ppmdEscape) {
                        emitByte(sym.toByte())
                    } else {
                        val escCode = ppmd.decodeSymbol(readPpmdByte)
                        if (escCode < 0) throw IllegalStateException("Invalid RAR29 PPMd escape")
                        when (escCode) {
                            0 -> readTables29(br, readPpmdByte)
                            2 -> {
                                tablesLoaded = false
                                break
                            }
                            3 -> throw IllegalStateException("RAR29 PPMd VM filters are not supported")
                            4 -> {
                                var lzOff = 0
                                for (i in 2 downTo 0) {
                                    val b = ppmd.decodeSymbol(readPpmdByte)
                                    if (b < 0) throw IllegalStateException("Invalid RAR29 PPMd match offset")
                                    lzOff = lzOff or (b shl (i * 8))
                                }
                                val lzLen = ppmd.decodeSymbol(readPpmdByte)
                                if (lzLen < 0) throw IllegalStateException("Invalid RAR29 PPMd match length")
                                copyMatch(lzLen + 32, lzOff + 2)
                            }
                            5 -> {
                                val lzLen = ppmd.decodeSymbol(readPpmdByte)
                                if (lzLen < 0) throw IllegalStateException("Invalid RAR29 PPMd rle length")
                                copyMatch(lzLen + 4, 1)
                            }
                            else -> emitByte(sym.toByte())
                        }
                    }
                    continue
                }
                if (!br.hasBits()) break
                val sym = mainDec.decode(br)
                if (sym < 0) throw IllegalStateException("Invalid RAR29 Huffman code")
                when {
                    sym < 256 -> emitByte(sym.toByte())
                    sym >= 271 -> {
                        val lenIdx = sym - 271
                        var length = LDECODE[lenIdx] + 3
                        val lBits = LBITS[lenIdx]
                        if (lBits > 0) length += br.readBits(lBits)
                        val distSym = distDec.decode(br)
                        if (distSym < 0 || distSym >= DC) throw IllegalStateException("Invalid RAR29 distance symbol")
                        var distance = DDECODE[distSym] + 1
                        val dBits = DBITS[distSym]
                        if (dBits > 0) {
                            if (distSym > 9) {
                                if (dBits > 4) {
                                    distance += br.readBits(dBits - 4) shl 4
                                }
                                if (lowDistRepCount > 0) {
                                    lowDistRepCount--
                                    distance += prevLowDist
                                } else {
                                    val lowSym = lowDistDec.decode(br)
                                    if (lowSym == 16) {
                                        lowDistRepCount = LOW_DIST_REP_COUNT - 1
                                        distance += prevLowDist
                                    } else if (lowSym in 0..15) {
                                        distance += lowSym
                                        prevLowDist = lowSym
                                    } else {
                                        throw IllegalStateException("Invalid RAR29 low distance")
                                    }
                                }
                            } else {
                                distance += br.readBits(dBits)
                            }
                        }
                        if (distance >= 0x2000) {
                            length++
                            if (distance >= 0x40000) length++
                        }
                        insertOldDist(distance)
                        lastDist = distance
                        lastLength = length
                        copyMatch(length, distance)
                    }
                    sym == 256 -> {
                        val endOfFile = br.readBits(1) == 0
                        if (endOfFile) {
                            val newTable = br.readBits(1) != 0
                            tablesLoaded = !newTable
                            break
                        } else {
                            readTables29(br, readPpmdByte)
                        }
                    }
                    sym == 257 -> {
                        throw IllegalStateException("RAR29 VM filter blocks are not supported")
                    }
                    sym == 258 -> {
                        if (lastLength > 0) copyMatch(lastLength, lastDist)
                    }
                    sym in 259..262 -> {
                        val idx = sym - 259
                        val distance = oldDist[idx]
                        for (k in idx downTo 1) oldDist[k] = oldDist[k - 1]
                        oldDist[0] = distance
                        val lenSym = repLenDec.decode(br)
                        if (lenSym < 0 || lenSym >= RC) throw IllegalStateException("Invalid RAR29 rep length")
                        var length = LDECODE[lenSym] + 2
                        val lBits = LBITS[lenSym]
                        if (lBits > 0) length += br.readBits(lBits)
                        lastDist = distance
                        lastLength = length
                        copyMatch(length, distance)
                    }
                    sym in 263..270 -> {
                        val sdIdx = sym - 263
                        var distance = SDDECODE[sdIdx] + 1
                        val sdBits = SDBITS[sdIdx]
                        if (sdBits > 0) distance += br.readBits(sdBits)
                        insertOldDist(distance)
                        lastDist = distance
                        lastLength = 2
                        copyMatch(2, distance)
                    }
                }
            }
            if (produced < out.size) throw IllegalStateException("Truncated RAR29 stream")
            if (!isPpmdBlock && br.hasBits()) {
                val savedPos = br.bitPos
                val trail = mainDec.decode(br)
                if (trail == 256 && br.hasBits()) {
                    val endOfFile = br.readBits(1) == 0
                    if (endOfFile && br.hasBits()) {
                        val newTable = br.readBits(1) != 0
                        tablesLoaded = !newTable
                    }
                } else {
                    br.bitPos = savedPos
                }
            }
            return out
        }

        private fun readTables29(br: BitReader, readPpmdByte: () -> Int) {
            br.alignByte()
            val ppmdBit = br.readBits(1)
            if (ppmdBit != 0) {
                isPpmdBlock = true
                val ppmdFlags = br.readBits(7)
                var memBytes = 0
                if ((ppmdFlags and 0x20) != 0) {
                    memBytes = (br.readBits(8) + 1) shl 20
                }
                if ((ppmdFlags and 0x40) != 0) {
                    ppmdEscape = br.readBits(8)
                    ppmd.initEsc = ppmdEscape
                } else {
                    ppmdEscape = 2
                }
                if ((ppmdFlags and 0x20) != 0) {
                    var maxOrd = (ppmdFlags and 0x1F) + 1
                    if (maxOrd > 16) maxOrd = 16 + (maxOrd - 16) * 3
                    if (maxOrd == 1 || memBytes <= 0) {
                        throw IllegalStateException("Invalid RAR29 PPMd parameters")
                    }
                    if (!ppmd.alloc(memBytes)) {
                        throw IllegalStateException("Unable to allocate RAR29 PPMd heap")
                    }
                    if (!ppmd.initRangeDec(readPpmdByte)) {
                        throw IllegalStateException("Unable to initialize RAR29 PPMd range decoder")
                    }
                    ppmd.initModel(maxOrd)
                    ppmdValid = true
                } else {
                    if (!ppmdValid || !ppmd.isAllocated()) {
                        throw IllegalStateException("Invalid RAR29 PPMd continuation")
                    }
                    if (!ppmd.initRangeDec(readPpmdByte)) {
                        throw IllegalStateException("Unable to reinitialize RAR29 PPMd range decoder")
                    }
                }
                return
            }
            isPpmdBlock = false
            val keepOld = br.readBits(1) != 0
            if (!keepOld) oldTable.fill(0)
            lowDistRepCount = 0

            val bitLengths = IntArray(BC)
            var i = 0
            while (i < BC) {
                val len = br.readBits(4)
                if (len == 15) {
                    val zeroCount = br.readBits(4)
                    if (zeroCount == 0) {
                        bitLengths[i++] = 15
                    } else {
                        val n = (zeroCount + 2).coerceAtMost(BC - i)
                        for (k in 0 until n) bitLengths[i++] = 0
                    }
                } else {
                    bitLengths[i++] = len
                }
            }
            bitLenDec.build(bitLengths, BC)

            val table = IntArray(HUFF_TABLE_SIZE)
            i = 0
            while (i < HUFF_TABLE_SIZE) {
                val sym = bitLenDec.decode(br)
                if (sym < 0) throw IllegalStateException("Bad RAR29 code length")
                if (sym < 16) {
                    table[i] = (sym + oldTable[i]) and 15
                    i++
                } else if (sym < 18) {
                    val n = if (sym == 16) br.readBits(3) + 3 else br.readBits(7) + 11
                    if (i == 0) throw IllegalStateException("Invalid RAR29 repeat length at 0")
                    val prev = table[i - 1]
                    val lim = (i + n).coerceAtMost(HUFF_TABLE_SIZE)
                    while (i < lim) table[i++] = prev
                } else {
                    val n = if (sym == 18) br.readBits(3) + 3 else br.readBits(7) + 11
                    val lim = (i + n).coerceAtMost(HUFF_TABLE_SIZE)
                    while (i < lim) table[i++] = 0
                }
            }
            System.arraycopy(table, 0, oldTable, 0, HUFF_TABLE_SIZE)
            mainDec.build(table.copyOfRange(0, NC), NC)
            distDec.build(table.copyOfRange(NC, NC + DC), DC)
            lowDistDec.build(table.copyOfRange(NC + DC, NC + DC + LDC), LDC)
            repLenDec.build(table.copyOfRange(NC + DC + LDC, HUFF_TABLE_SIZE), RC)
        }

        fun unpack20(packed: ByteArray, unpackedSize: Long, solid: Boolean): ByteArray {
            if (unpackedSize < 0L || unpackedSize > maxFileBytes) throw IllegalStateException("RAR20 entry too large")
            if (!solid) resetState()
            val out = ByteArray(unpackedSize.toInt())
            var produced = 0
            val br = BitReader(packed)
            if (!solid || !tablesLoaded) {
                readTables20(br)
                tablesLoaded = true
            }

            fun emitByte(b: Byte) {
                window[writePos] = b
                writePos = (writePos + 1) and winMask
                if (produced < out.size) out[produced++] = b
            }

            fun copyMatch(length: Int, dist: Int) {
                var src = (writePos - dist) and winMask
                for (i in 0 until length) {
                    val b = window[src]
                    src = (src + 1) and winMask
                    emitByte(b)
                }
            }

            while (produced < out.size && br.hasBits()) {
                val sym = mainDec20.decode(br)
                if (sym < 0) throw IllegalStateException("Invalid RAR20 Huffman code")
                when {
                    sym < 256 -> emitByte(sym.toByte())
                    sym > 269 -> {
                        val lenIdx = sym - 270
                        var length = LDECODE[lenIdx] + 3
                        val lBits = LBITS[lenIdx]
                        if (lBits > 0) length += br.readBits(lBits)
                        val distSym = distDec20.decode(br)
                        if (distSym < 0 || distSym >= DC20) throw IllegalStateException("Invalid RAR20 distance")
                        var distance = DDECODE20[distSym] + 1
                        val dBits = DBITS20[distSym]
                        if (dBits > 0) distance += br.readBits(dBits)
                        if (distance >= 0x2000) {
                            length++
                            if (distance >= 0x40000) length++
                        }
                        oldDist[oldDistPtr] = distance
                        oldDistPtr = (oldDistPtr + 1) and 3
                        lastDist = distance
                        lastLength = length
                        copyMatch(length, distance)
                    }
                    sym == 269 -> readTables20(br)
                    sym == 256 -> if (lastLength > 0) copyMatch(lastLength, lastDist)
                    sym in 257..260 -> {
                        val distance = oldDist[(oldDistPtr - (sym - 256)) and 3]
                        val lenSym = repLenDec20.decode(br)
                        if (lenSym < 0 || lenSym >= RC20) throw IllegalStateException("Invalid RAR20 rep length")
                        var length = LDECODE[lenSym] + 2
                        val lBits = LBITS[lenSym]
                        if (lBits > 0) length += br.readBits(lBits)
                        if (distance >= 0x101) {
                            length++
                            if (distance >= 0x2000) {
                                length++
                                if (distance >= 0x40000) length++
                            }
                        }
                        oldDist[oldDistPtr] = distance
                        oldDistPtr = (oldDistPtr + 1) and 3
                        lastDist = distance
                        lastLength = length
                        copyMatch(length, distance)
                    }
                    sym in 261..268 -> {
                        val sdIdx = sym - 261
                        var distance = SDDECODE[sdIdx] + 1
                        val sdBits = SDBITS[sdIdx]
                        if (sdBits > 0) distance += br.readBits(sdBits)
                        oldDist[oldDistPtr] = distance
                        oldDistPtr = (oldDistPtr + 1) and 3
                        lastDist = distance
                        lastLength = 2
                        copyMatch(2, distance)
                    }
                }
            }
            if (produced < out.size) throw IllegalStateException("Truncated RAR20 stream")
            return out
        }

        private fun readTables20(br: BitReader) {
            val audioBlock = br.readBits(1) != 0
            if (audioBlock) throw IllegalStateException("RAR20 audio block is not supported")
            val keepOld = br.readBits(1) != 0
            if (!keepOld) oldTable20.fill(0)
            val bitLengths = IntArray(BC20) { br.readBits(4) }
            val bd20 = HuffmanDecoder(BC20)
            bd20.build(bitLengths, BC20)
            val total = NC20 + DC20 + RC20
            val table = IntArray(total)
            var i = 0
            while (i < total) {
                val sym = bd20.decode(br)
                if (sym < 0) throw IllegalStateException("Bad RAR20 code length")
                if (sym < 16) {
                    table[i] = (sym + oldTable20[i]) and 15
                    i++
                } else if (sym == 16) {
                    val n = br.readBits(2) + 3
                    if (i == 0) throw IllegalStateException("Invalid RAR20 repeat")
                    val prev = table[i - 1]
                    val lim = (i + n).coerceAtMost(total)
                    while (i < lim) table[i++] = prev
                } else {
                    val n = if (sym == 17) br.readBits(3) + 3 else br.readBits(7) + 11
                    val lim = (i + n).coerceAtMost(total)
                    while (i < lim) table[i++] = 0
                }
            }
            System.arraycopy(table, 0, oldTable20, 0, total)
            mainDec20.build(table.copyOfRange(0, NC20), NC20)
            distDec20.build(table.copyOfRange(NC20, NC20 + DC20), DC20)
            repLenDec20.build(table.copyOfRange(NC20 + DC20, total), RC20)
        }

        companion object {
            private const val NC = 299
            private const val DC = 60
            private const val LDC = 17
            private const val RC = 28
            private const val BC = 20
            private const val HUFF_TABLE_SIZE = NC + DC + LDC + RC
            private const val LOW_DIST_REP_COUNT = 16

            private const val NC20 = 298
            private const val DC20 = 48
            private const val RC20 = 28
            private const val BC20 = 19

            private val LDECODE = intArrayOf(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28,
                32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224
            )
            private val LBITS = intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
                3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5
            )
            private val SDDECODE = intArrayOf(0, 4, 8, 16, 32, 64, 128, 192)
            private val SDBITS = intArrayOf(2, 2, 3, 4, 5, 6, 6, 6)

            private val DDECODE = intArrayOf(
                0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48,
                64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072,
                4096, 6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608,
                262144, 327680, 393216, 458752, 524288, 589824, 655360, 720896, 786432, 851968, 917504, 983040,
                1048576, 1310720, 1572864, 1835008, 2097152, 2359296, 2621440, 2883584, 3145728, 3407872, 3670016, 3932160
            )
            private val DBITS = intArrayOf(
                0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4,
                5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
                11, 11, 12, 12, 13, 13, 14, 14, 15, 15, 16, 16,
                16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
                18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18
            )
            private val DDECODE20 = intArrayOf(
                0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192,
                256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288,
                16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608, 262144, 327680,
                393216, 458752, 524288, 589824, 655360, 720896, 786432, 851968, 917504, 983040
            )
            private val DBITS20 = intArrayOf(
                0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
                7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14,
                15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16
            )
        }
    }
}
