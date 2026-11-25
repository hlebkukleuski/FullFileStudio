/*
 * FullFileStudio
 * Copyright (C) 2026 Hleb Kukleuski (https://github.com/hlebkukleuski)
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 *
 * Implements the W3C WOFF File Format 2.0 Recommendation (https://www.w3.org/TR/WOFF2/)
 * with reference to the Google Inc. woff2 specification algorithms (MIT License,
 * Copyright 2014 Google Inc.) and org.brotli.dec (MIT License).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.brotli.dec.BrotliInputStream

object Woff2Decoder {

    private const val WOFF2_SIGNATURE = 0x774F4632
    private const val TTC_FLAVOR = 0x74746366
    private const val TAG_GLYF = 0x676C7966
    private const val TAG_LOCA = 0x6C6F6361
    private const val TAG_HMTX = 0x686D7478
    private const val TAG_HHEA = 0x68686561
    private const val TAG_HEAD = 0x68656164
    private const val TAG_MAXP = 0x6D617870

    private const val GLYF_ON_CURVE = 1 shl 0
    private const val GLYF_X_SHORT = 1 shl 1
    private const val GLYF_Y_SHORT = 1 shl 2
    private const val GLYF_REPEAT = 1 shl 3
    private const val GLYF_THIS_X_IS_SAME = 1 shl 4
    private const val GLYF_THIS_Y_IS_SAME = 1 shl 5
    private const val GLYF_OVERLAP_SIMPLE = 1 shl 6

    private const val COMP_ARG_1_AND_2_ARE_WORDS = 1 shl 0
    private const val COMP_WE_HAVE_A_SCALE = 1 shl 3
    private const val COMP_MORE_COMPONENTS = 1 shl 5
    private const val COMP_WE_HAVE_AN_X_AND_Y_SCALE = 1 shl 6
    private const val COMP_WE_HAVE_A_TWO_BY_TWO = 1 shl 7
    private const val COMP_WE_HAVE_INSTRUCTIONS = 1 shl 8

    private const val FLAG_OVERLAP_SIMPLE_BITMAP = 1 shl 0

    private const val MAX_WOFF2_INPUT_BYTES = 64 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_OUTPUT_SFNT_BYTES = 96 * 1024 * 1024
    private const val MAX_PLAUSIBLE_COMPRESSION_RATIO = 100L

    private val KNOWN_TAGS = intArrayOf(
        0x636D6170, 0x68656164, 0x68686561, 0x686D7478,
        0x6D617870, 0x6E616D65, 0x4F532F32, 0x706F7374,
        0x63767420, 0x6670676D, 0x676C7966, 0x6C6F6361,
        0x70726570, 0x43464620, 0x564F5247, 0x45424454,
        0x45424C43, 0x67617370, 0x68646D78, 0x6B65726E,
        0x4C545348, 0x50434C54, 0x56444D58, 0x76686561,
        0x766D7478, 0x42415345, 0x47444546, 0x47504F53,
        0x47535542, 0x45425343, 0x4A535446, 0x4D415448,
        0x43424454, 0x43424C43, 0x434F4C52, 0x4350414C,
        0x53564720, 0x73626978, 0x61636E74, 0x61766172,
        0x62646174, 0x626C6F63, 0x62736C6E, 0x63766172,
        0x66647363, 0x66656174, 0x666D7478, 0x66766172,
        0x67766172, 0x68737479, 0x6A757374, 0x6C636172,
        0x6D6F7274, 0x6D6F7278, 0x6F706264, 0x70726F70,
        0x7472616B, 0x5A617066, 0x53696C66, 0x476C6174,
        0x476C6F63, 0x46656174, 0x53696C6C
    )

    private class ByteSlice(
        val data: ByteArray,
        val baseOffset: Int,
        val length: Int
    ) {
        var pos: Int = 0

        fun remaining(): Int = length - pos

        fun skip(count: Int): Boolean {
            if (count < 0 || count > remaining()) return false
            pos += count
            return true
        }

        fun readU8(): Int {
            if (pos >= length) return -1
            val v = data[baseOffset + pos].toInt() and 0xFF
            pos++
            return v
        }

        fun readU16(): Int {
            if (pos + 2 > length) return -1
            val idx = baseOffset + pos
            val v = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun readS16(): Int? {
            val u = readU16()
            if (u < 0) return null
            return u.toShort().toInt()
        }

        fun readU32Long(): Long {
            if (pos + 4 > length) return -1L
            val idx = baseOffset + pos
            val v = ((data[idx].toLong() and 0xFFL) shl 24) or
                ((data[idx + 1].toLong() and 0xFFL) shl 16) or
                ((data[idx + 2].toLong() and 0xFFL) shl 8) or
                (data[idx + 3].toLong() and 0xFFL)
            pos += 4
            return v
        }

        fun readInt32(): Int? {
            val u = readU32Long()
            if (u < 0L) return null
            return u.toInt()
        }

        fun readBytes(dst: ByteArray, dstOffset: Int, count: Int): Boolean {
            if (count < 0 || count > remaining() || dstOffset < 0 || dstOffset + count > dst.size) {
                return false
            }
            System.arraycopy(data, baseOffset + pos, dst, dstOffset, count)
            pos += count
            return true
        }

        fun read255UShort(): Int {
            val code = readU8()
            if (code < 0) return -1
            return when (code) {
                253 -> readU16()
                255 -> {
                    val b = readU8()
                    if (b < 0) -1 else 253 + b
                }
                254 -> {
                    val b = readU8()
                    if (b < 0) -1 else 506 + b
                }
                else -> code
            }
        }

        fun readUIntBase128(): Long {
            var accum = 0L
            for (i in 0 until 5) {
                val b = readU8()
                if (b < 0) return -1L
                if (i == 0 && b == 0x80) return -1L
                if ((accum and 0xFE000000L) != 0L) return -1L
                accum = (accum shl 7) or (b.toLong() and 0x7FL)
                if ((b and 0x80) == 0) {
                    return accum and 0xFFFFFFFFL
                }
            }
            return -1L
        }
    }

    private class TableEntry(
        val index: Int,
        val tag: Int,
        val xformVersion: Int,
        val isTransformed: Boolean,
        val origLength: Int,
        val transformLength: Int,
        val srcOffset: Int
    ) {
        var reconstructedBytes: ByteArray? = null
        var dstOffset: Int = 0
        var dstLength: Int = 0
        var checksum: Int = 0
    }

    private class TtcFontSpec(
        val flavor: Int,
        val tableIndices: IntArray
    ) {
        var offsetTablePos: Int = 0
        val directoryEntryOffsets = HashMap<Int, Int>()
    }

    private class ReconstructedGlyfPair(
        val glyfBytes: ByteArray,
        val locaBytes: ByteArray,
        val numGlyphs: Int,
        val xMins: ShortArray
    )

    fun decodeToSfnt(woff2: ByteArray): ByteArray? {
        if (woff2.size < 48 || woff2.size > MAX_WOFF2_INPUT_BYTES) return null
        return try {
            decodeInternal(woff2)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeInternal(woff2: ByteArray): ByteArray? {
        val reader = ByteSlice(woff2, 0, woff2.size)
        val signature = reader.readInt32() ?: return null
        if (signature != WOFF2_SIGNATURE) return null

        val flavor = reader.readInt32() ?: return null
        val reportedLength = reader.readU32Long()
        if (reportedLength != woff2.size.toLong()) return null

        val numTables = reader.readU16()
        if (numTables !in 1..512) return null

        if (!reader.skip(6)) return null

        val compressedLengthLong = reader.readU32Long()
        if (compressedLengthLong <= 0L || compressedLengthLong > woff2.size.toLong()) return null
        val compressedLength = compressedLengthLong.toInt()

        if (!reader.skip(4)) return null

        val metaOffset = reader.readU32Long()
        val metaLength = reader.readU32Long()
        val metaOrigLength = reader.readU32Long()
        val privOffset = reader.readU32Long()
        val privLength = reader.readU32Long()
        if (metaOffset < 0L || metaLength < 0L || metaOrigLength < 0L || privOffset < 0L || privLength < 0L) {
            return null
        }
        if (metaOffset != 0L && (metaOffset >= woff2.size.toLong() || woff2.size.toLong() - metaOffset < metaLength)) {
            return null
        }
        if (privOffset != 0L && (privOffset >= woff2.size.toLong() || woff2.size.toLong() - privOffset < privLength)) {
            return null
        }

        val tables = ArrayList<TableEntry>(numTables)
        var uncompressedCursor = 0L
        for (i in 0 until numTables) {
            val flagByte = reader.readU8()
            if (flagByte < 0) return null
            val knownIdx = flagByte and 0x3F
            val tag = if (knownIdx == 63) {
                reader.readInt32() ?: return null
            } else {
                KNOWN_TAGS[knownIdx]
            }
            val xformVersion = (flagByte ushr 6) and 0x03
            val isTransformed = when (tag) {
                TAG_GLYF, TAG_LOCA -> when (xformVersion) {
                    0 -> true
                    3 -> false
                    else -> return null
                }
                TAG_HMTX -> when (xformVersion) {
                    0 -> false
                    1 -> true
                    else -> return null
                }
                else -> if (xformVersion == 0) false else return null
            }

            val origLenLong = reader.readUIntBase128()
            if (origLenLong < 0L || origLenLong > MAX_DECOMPRESSED_BYTES.toLong()) return null

            val xformLenLong = if (isTransformed) {
                val xl = reader.readUIntBase128()
                if (xl < 0L || xl > MAX_DECOMPRESSED_BYTES.toLong()) return null
                if (tag == TAG_LOCA && xl != 0L) return null
                xl
            } else {
                origLenLong
            }

            if (uncompressedCursor + xformLenLong > MAX_DECOMPRESSED_BYTES.toLong()) return null
            tables.add(
                TableEntry(
                    index = i,
                    tag = tag,
                    xformVersion = xformVersion,
                    isTransformed = isTransformed,
                    origLength = origLenLong.toInt(),
                    transformLength = xformLenLong.toInt(),
                    srcOffset = uncompressedCursor.toInt()
                )
            )
            uncompressedCursor += xformLenLong
        }

        var ttcVersion = 0
        val ttcFonts = ArrayList<TtcFontSpec>()
        if (flavor == TTC_FLAVOR) {
            ttcVersion = reader.readInt32() ?: return null
            if (ttcVersion != 0x00010000 && ttcVersion != 0x00020000) return null
            val numFonts = reader.read255UShort()
            if (numFonts !in 1..256) return null
            for (f in 0 until numFonts) {
                val fontNumTables = reader.read255UShort()
                if (fontNumTables !in 1..numTables) return null
                val fontFlavor = reader.readInt32() ?: return null
                val indices = IntArray(fontNumTables)
                var glyfIdx = -1
                var locaIdx = -1
                val seenTags = HashSet<Int>(fontNumTables * 2)
                for (j in 0 until fontNumTables) {
                    val tIdx = reader.read255UShort()
                    if (tIdx !in 0 until numTables) return null
                    val tbl = tables[tIdx]
                    if (!seenTags.add(tbl.tag)) return null
                    indices[j] = tIdx
                    if (tbl.tag == TAG_GLYF) glyfIdx = tIdx
                    if (tbl.tag == TAG_LOCA) locaIdx = tIdx
                }
                if ((glyfIdx >= 0) != (locaIdx >= 0)) return null
                if (glyfIdx >= 0 && locaIdx - glyfIdx != 1) return null
                sortIndicesByTag(indices, tables)
                ttcFonts.add(TtcFontSpec(fontFlavor, indices))
            }
        } else {
            val seenTags = HashSet<Int>(numTables * 2)
            var glyfOrder = -1
            var locaOrder = -1
            for (i in 0 until numTables) {
                val t = tables[i]
                if (!seenTags.add(t.tag)) return null
                if (t.tag == TAG_GLYF) glyfOrder = i
                if (t.tag == TAG_LOCA) locaOrder = i
            }
            if ((glyfOrder >= 0) != (locaOrder >= 0)) return null
            if (glyfOrder >= 0 && locaOrder < glyfOrder) return null
        }

        val compressedOffset = reader.pos
        if (compressedOffset.toLong() + compressedLength.toLong() > woff2.size.toLong()) return null

        var expectedAfterBlock = round4Long(compressedOffset.toLong() + compressedLength.toLong())
        if (metaOffset != 0L) {
            if (metaOffset != expectedAfterBlock) return null
            expectedAfterBlock = round4Long(metaOffset + metaLength)
        }
        if (privOffset != 0L) {
            if (privOffset != expectedAfterBlock) return null
            expectedAfterBlock = round4Long(privOffset + privLength)
        }
        if (expectedAfterBlock != round4Long(woff2.size.toLong())) return null

        val totalUncompressedSize = uncompressedCursor.toInt()
        if (totalUncompressedSize < 1) return null
        if (totalUncompressedSize.toLong() > woff2.size.toLong() * MAX_PLAUSIBLE_COMPRESSION_RATIO &&
            totalUncompressedSize > 65536
        ) {
            return null
        }

        val uncompressedData = decompressBrotliExact(
            woff2,
            compressedOffset,
            compressedLength,
            totalUncompressedSize
        ) ?: return null

        if (flavor == TTC_FLAVOR) {
            return assembleCollectionSfnt(ttcVersion, ttcFonts, tables, uncompressedData)
        }
        val allIndices = IntArray(numTables) { it }
        sortIndicesByTag(allIndices, tables)
        return assembleSingleSfnt(flavor, allIndices, tables, uncompressedData)
    }

    private fun sortIndicesByTag(indices: IntArray, tables: List<TableEntry>) {
        for (i in 1 until indices.size) {
            val cur = indices[i]
            val curTag = tables[cur].tag
            var j = i - 1
            while (j >= 0 && compareTagsUnsigned(tables[indices[j]].tag, curTag) > 0) {
                indices[j + 1] = indices[j]
                j--
            }
            indices[j + 1] = cur
        }
    }

    private fun assembleSingleSfnt(
        flavor: Int,
        sortedTableIndices: IntArray,
        tables: List<TableEntry>,
        uncompressedData: ByteArray
    ): ByteArray? {
        if (!reconstructFontTables(sortedTableIndices, tables, uncompressedData)) return null

        val numTables = sortedTableIndices.size
        val headerSize = 12 + numTables * 16
        var totalSizeLong = headerSize.toLong()
        for (idx in sortedTableIndices) {
            val bytes = tables[idx].reconstructedBytes ?: return null
            totalSizeLong += round4Long(bytes.size.toLong())
        }
        if (totalSizeLong <= 0L || totalSizeLong > MAX_OUTPUT_SFNT_BYTES.toLong()) return null

        val out = ByteArray(totalSizeLong.toInt())
        writeOffsetTable(out, 0, flavor, numTables)

        var dataOffset = headerSize
        var headTableEntry: TableEntry? = null
        var fontChecksum = 0

        for (i in sortedTableIndices.indices) {
            val tbl = tables[sortedTableIndices[i]]
            val payload = tbl.reconstructedBytes ?: return null
            if (tbl.tag == TAG_HEAD) {
                if (payload.size < 12) return null
                storeU32(payload, 8, 0)
                headTableEntry = tbl
            }
            val checksum = computeSfntChecksum(payload, 0, payload.size)
            tbl.dstOffset = dataOffset
            tbl.dstLength = payload.size
            tbl.checksum = checksum

            val entryPos = 12 + i * 16
            storeU32(out, entryPos, tbl.tag)
            storeU32(out, entryPos + 4, checksum)
            storeU32(out, entryPos + 8, dataOffset)
            storeU32(out, entryPos + 12, payload.size)

            System.arraycopy(payload, 0, out, dataOffset, payload.size)
            dataOffset = round4Int(dataOffset + payload.size)
            fontChecksum += checksum
        }

        fontChecksum += computeSfntChecksum(out, 0, headerSize)
        if (headTableEntry != null) {
            storeU32(out, headTableEntry.dstOffset + 8, 0xB1B0AFBA.toInt() - fontChecksum)
        }
        return out
    }

    private fun assembleCollectionSfnt(
        ttcVersion: Int,
        ttcFonts: List<TtcFontSpec>,
        tables: List<TableEntry>,
        uncompressedData: ByteArray
    ): ByteArray? {
        for (font in ttcFonts) {
            if (!reconstructFontTables(font.tableIndices, tables, uncompressedData)) return null
        }

        var headerCursor = 12 + ttcFonts.size * 4 + (if (ttcVersion == 0x00020000) 12 else 0)
        for (font in ttcFonts) {
            font.offsetTablePos = headerCursor
            headerCursor += 12 + font.tableIndices.size * 16
        }

        var totalSizeLong = headerCursor.toLong()
        for (tbl in tables) {
            val payload = tbl.reconstructedBytes ?: continue
            totalSizeLong += round4Long(payload.size.toLong())
        }
        if (totalSizeLong <= 0L || totalSizeLong > MAX_OUTPUT_SFNT_BYTES.toLong()) return null

        val out = ByteArray(totalSizeLong.toInt())
        storeU32(out, 0, TTC_FLAVOR)
        storeU32(out, 4, ttcVersion)
        storeU32(out, 8, ttcFonts.size)

        for (fIdx in ttcFonts.indices) {
            val font = ttcFonts[fIdx]
            storeU32(out, 12 + fIdx * 4, font.offsetTablePos)
            writeOffsetTable(out, font.offsetTablePos, font.flavor, font.tableIndices.size)
        }

        var dataOffset = headerCursor
        val writtenTables = BooleanArray(tables.size)
        for (font in ttcFonts) {
            for (i in font.tableIndices.indices) {
                val tIdx = font.tableIndices[i]
                val tbl = tables[tIdx]
                val payload = tbl.reconstructedBytes ?: return null
                if (!writtenTables[tIdx]) {
                    if (tbl.tag == TAG_HEAD) {
                        if (payload.size < 12) return null
                        storeU32(payload, 8, 0)
                    }
                    tbl.dstOffset = dataOffset
                    tbl.dstLength = payload.size
                    tbl.checksum = computeSfntChecksum(payload, 0, payload.size)
                    System.arraycopy(payload, 0, out, dataOffset, payload.size)
                    dataOffset = round4Int(dataOffset + payload.size)
                    writtenTables[tIdx] = true
                }
                val entryPos = font.offsetTablePos + 12 + i * 16
                storeU32(out, entryPos, tbl.tag)
                storeU32(out, entryPos + 4, tbl.checksum)
                storeU32(out, entryPos + 8, tbl.dstOffset)
                storeU32(out, entryPos + 12, tbl.dstLength)
            }
        }

        val updatedHeads = HashSet<Int>()
        for (font in ttcFonts) {
            val dirBytesLen = 12 + font.tableIndices.size * 16
            var fontChecksum = computeSfntChecksum(out, font.offsetTablePos, dirBytesLen)
            var headIdx = -1
            for (tIdx in font.tableIndices) {
                fontChecksum += tables[tIdx].checksum
                if (tables[tIdx].tag == TAG_HEAD) {
                    headIdx = tIdx
                }
            }
            if (headIdx >= 0 && updatedHeads.add(headIdx)) {
                storeU32(out, tables[headIdx].dstOffset + 8, 0xB1B0AFBA.toInt() - fontChecksum)
            }
        }

        return out
    }

    private fun reconstructFontTables(
        fontTableIndices: IntArray,
        tables: List<TableEntry>,
        uncompressedData: ByteArray
    ): Boolean {
        var glyfEntry: TableEntry? = null
        var locaEntry: TableEntry? = null
        var hmtxEntry: TableEntry? = null
        var hheaEntry: TableEntry? = null
        var maxpEntry: TableEntry? = null
        var headEntry: TableEntry? = null

        for (idx in fontTableIndices) {
            val tbl = tables[idx]
            when (tbl.tag) {
                TAG_GLYF -> glyfEntry = tbl
                TAG_LOCA -> locaEntry = tbl
                TAG_HMTX -> hmtxEntry = tbl
                TAG_HHEA -> hheaEntry = tbl
                TAG_MAXP -> maxpEntry = tbl
                TAG_HEAD -> headEntry = tbl
            }
            if (!tbl.isTransformed && tbl.reconstructedBytes == null) {
                if (tbl.srcOffset < 0 || tbl.srcOffset + tbl.transformLength > uncompressedData.size) {
                    return false
                }
                tbl.reconstructedBytes = uncompressedData.copyOfRange(
                    tbl.srcOffset,
                    tbl.srcOffset + tbl.transformLength
                )
            }
        }

        if ((glyfEntry != null) != (locaEntry != null)) return false
        if (glyfEntry != null && locaEntry != null && glyfEntry.isTransformed != locaEntry.isTransformed) {
            return false
        }

        var numGlyphs = -1
        var xMins: ShortArray? = null

        val maxpBytes = maxpEntry?.reconstructedBytes
        if (maxpBytes != null && maxpBytes.size >= 6) {
            numGlyphs = readU16BE(maxpBytes, 4)
        }

        if (glyfEntry != null && locaEntry != null) {
            if (glyfEntry.isTransformed) {
                if (glyfEntry.reconstructedBytes == null || locaEntry.reconstructedBytes == null) {
                    val pair = reconstructTransformedGlyfAndLoca(
                        uncompressedData,
                        glyfEntry,
                        locaEntry
                    ) ?: return false
                    glyfEntry.reconstructedBytes = pair.glyfBytes
                    locaEntry.reconstructedBytes = pair.locaBytes
                    numGlyphs = pair.numGlyphs
                    xMins = pair.xMins
                } else if (hmtxEntry != null && hmtxEntry.isTransformed) {
                    val headBytes = headEntry?.reconstructedBytes ?: return false
                    if (headBytes.size < 52) return false
                    val indexFormat = readU16BE(headBytes, 50)
                    val extracted = extractXMinsFromSfntGlyf(
                        glyfEntry.reconstructedBytes!!,
                        locaEntry.reconstructedBytes!!,
                        indexFormat,
                        numGlyphs
                    ) ?: return false
                    numGlyphs = extracted.size
                    xMins = extracted
                }
            } else if (hmtxEntry != null && hmtxEntry.isTransformed) {
                val headBytes = headEntry?.reconstructedBytes ?: return false
                if (headBytes.size < 52 || numGlyphs < 0) return false
                val indexFormat = readU16BE(headBytes, 50)
                xMins = extractXMinsFromSfntGlyf(
                    glyfEntry.reconstructedBytes ?: return false,
                    locaEntry.reconstructedBytes ?: return false,
                    indexFormat,
                    numGlyphs
                ) ?: return false
            }
        }

        if (hmtxEntry != null && hmtxEntry.isTransformed && hmtxEntry.reconstructedBytes == null) {
            val hheaBytes = hheaEntry?.reconstructedBytes ?: return false
            if (hheaBytes.size < 36) return false
            val numHMetrics = readU16BE(hheaBytes, 34)
            val resolvedXMins = xMins ?: return false
            if (numGlyphs <= 0 || resolvedXMins.size != numGlyphs) return false
            hmtxEntry.reconstructedBytes = reconstructTransformedHmtx(
                uncompressedData,
                hmtxEntry,
                numGlyphs,
                numHMetrics,
                resolvedXMins
            ) ?: return false
        }

        for (idx in fontTableIndices) {
            if (tables[idx].reconstructedBytes == null) return false
        }
        return true
    }

    private fun reconstructTransformedGlyfAndLoca(
        uncompressedData: ByteArray,
        glyfEntry: TableEntry,
        locaEntry: TableEntry
    ): ReconstructedGlyfPair? {
        if (glyfEntry.srcOffset < 0 || glyfEntry.srcOffset + glyfEntry.transformLength > uncompressedData.size) {
            return null
        }
        val header = ByteSlice(uncompressedData, glyfEntry.srcOffset, glyfEntry.transformLength)
        val version = header.readU16()
        val optionFlags = header.readU16()
        val numGlyphs = header.readU16()
        val indexFormat = header.readU16()
        if (version < 0 || optionFlags < 0 || numGlyphs < 0 || indexFormat < 0) return null

        val expectedLocaOrigLen = (if (indexFormat != 0) 4L else 2L) * (numGlyphs.toLong() + 1L)
        if (locaEntry.origLength.toLong() != expectedLocaOrigLen) return null

        val substreamSizes = IntArray(7)
        var streamOffset = 36
        for (i in 0 until 7) {
            val sz = header.readU32Long()
            if (sz < 0L || sz > (glyfEntry.transformLength - streamOffset).toLong()) return null
            substreamSizes[i] = sz.toInt()
            streamOffset += sz.toInt()
        }

        var curBase = glyfEntry.srcOffset + 36
        val nContourStream = ByteSlice(uncompressedData, curBase, substreamSizes[0])
        curBase += substreamSizes[0]
        val nPointsStream = ByteSlice(uncompressedData, curBase, substreamSizes[1])
        curBase += substreamSizes[1]
        val flagStream = ByteSlice(uncompressedData, curBase, substreamSizes[2])
        curBase += substreamSizes[2]
        val glyphStream = ByteSlice(uncompressedData, curBase, substreamSizes[3])
        curBase += substreamSizes[3]
        val compositeStream = ByteSlice(uncompressedData, curBase, substreamSizes[4])
        curBase += substreamSizes[4]
        val bboxStream = ByteSlice(uncompressedData, curBase, substreamSizes[5])
        curBase += substreamSizes[5]
        val instructionStream = ByteSlice(uncompressedData, curBase, substreamSizes[6])
        curBase += substreamSizes[6]

        val hasOverlapBitmap = (optionFlags and FLAG_OVERLAP_SIMPLE_BITMAP) != 0
        val overlapBitmapBase = curBase
        if (hasOverlapBitmap) {
            val overlapLen = (numGlyphs + 7) ushr 3
            if (overlapLen > glyfEntry.srcOffset + glyfEntry.transformLength - overlapBitmapBase) {
                return null
            }
        }

        val bboxBitmapLen = ((numGlyphs + 31) ushr 5) shl 2
        val bboxBitmapBase = bboxStream.baseOffset
        if (!bboxStream.skip(bboxBitmapLen)) return null

        val locaOffsets = IntArray(numGlyphs + 1)
        val xMins = ShortArray(numGlyphs)
        val glyfOut = ByteArrayOutputStream(max(256, min(glyfEntry.origLength, 16 * 1024 * 1024)))

        var glyphBuf = ByteArray(5120)
        var pointX = IntArray(256)
        var pointY = IntArray(256)
        var pointOnCurve = BooleanArray(256)
        var contourLengths = IntArray(64)
        val padZeroes = ByteArray(4)

        for (glyphId in 0 until numGlyphs) {
            locaOffsets[glyphId] = glyfOut.size()
            val haveBbox = (uncompressedData[bboxBitmapBase + (glyphId ushr 3)].toInt() and (0x80 ushr (glyphId and 7))) != 0
            val nContoursUnsigned = nContourStream.readU16()
            if (nContoursUnsigned < 0) return null

            var glyphSize = 0
            if (nContoursUnsigned == 0xFFFF) {
                if (!haveBbox) return null
                val compStart = compositeStream.pos
                var haveInstructions = false
                var flags = COMP_MORE_COMPONENTS
                while ((flags and COMP_MORE_COMPONENTS) != 0) {
                    flags = compositeStream.readU16()
                    if (flags < 0) return null
                    if ((flags and COMP_WE_HAVE_INSTRUCTIONS) != 0) {
                        haveInstructions = true
                    }
                    var argSize = 2 + if ((flags and COMP_ARG_1_AND_2_ARE_WORDS) != 0) 4 else 2
                    argSize += when {
                        (flags and COMP_WE_HAVE_A_SCALE) != 0 -> 2
                        (flags and COMP_WE_HAVE_AN_X_AND_Y_SCALE) != 0 -> 4
                        (flags and COMP_WE_HAVE_A_TWO_BY_TWO) != 0 -> 8
                        else -> 0
                    }
                    if (!compositeStream.skip(argSize)) return null
                }
                val compositeSize = compositeStream.pos - compStart
                compositeStream.pos = compStart

                var instructionSize = 0
                if (haveInstructions) {
                    instructionSize = glyphStream.read255UShort()
                    if (instructionSize < 0 || instructionSize > instructionStream.remaining()) return null
                }

                val needed = 12 + compositeSize + instructionSize
                if (needed > MAX_DECOMPRESSED_BYTES) return null
                if (glyphBuf.size < needed) {
                    glyphBuf = ByteArray(max(needed, glyphBuf.size * 2))
                }

                storeU16(glyphBuf, 0, 0xFFFF)
                if (!bboxStream.readBytes(glyphBuf, 2, 8)) return null
                if (!compositeStream.readBytes(glyphBuf, 10, compositeSize)) return null
                glyphSize = 10 + compositeSize
                if (haveInstructions) {
                    storeU16(glyphBuf, glyphSize, instructionSize)
                    glyphSize += 2
                    if (!instructionStream.readBytes(glyphBuf, glyphSize, instructionSize)) return null
                    glyphSize += instructionSize
                }
                xMins[glyphId] = readS16BE(glyphBuf, 2)
            } else if (nContoursUnsigned > 0) {
                if (contourLengths.size < nContoursUnsigned) {
                    contourLengths = IntArray(max(nContoursUnsigned, contourLengths.size * 2))
                }
                var totalPoints = 0
                for (c in 0 until nContoursUnsigned) {
                    val pts = nPointsStream.read255UShort()
                    if (pts < 0) return null
                    contourLengths[c] = pts
                    totalPoints += pts
                    if (totalPoints < 0 || totalPoints > 65535) return null
                }
                if (totalPoints > flagStream.remaining()) return null

                if (pointX.size < totalPoints) {
                    val cap = max(totalPoints, pointX.size * 2)
                    pointX = IntArray(cap)
                    pointY = IntArray(cap)
                    pointOnCurve = BooleanArray(cap)
                }

                if (!decodeTriplets(flagStream, glyphStream, totalPoints, pointX, pointY, pointOnCurve)) {
                    return null
                }

                val instructionSize = glyphStream.read255UShort()
                if (instructionSize < 0 || instructionSize > instructionStream.remaining()) return null

                val needed = 12 + 2 * nContoursUnsigned + 5 * totalPoints + instructionSize
                if (needed > MAX_DECOMPRESSED_BYTES) return null
                if (glyphBuf.size < needed) {
                    glyphBuf = ByteArray(max(needed, glyphBuf.size * 2))
                }

                storeU16(glyphBuf, 0, nContoursUnsigned)
                if (haveBbox) {
                    if (!bboxStream.readBytes(glyphBuf, 2, 8)) return null
                } else {
                    computeAndStoreBbox(totalPoints, pointX, pointY, glyphBuf)
                }

                var writePos = 10
                var endPoint = -1
                for (c in 0 until nContoursUnsigned) {
                    endPoint += contourLengths[c]
                    if (endPoint < 0 || endPoint >= 65536) return null
                    storeU16(glyphBuf, writePos, endPoint)
                    writePos += 2
                }

                storeU16(glyphBuf, writePos, instructionSize)
                writePos += 2
                if (!instructionStream.readBytes(glyphBuf, writePos, instructionSize)) return null
                writePos += instructionSize

                val hasOverlapBit = hasOverlapBitmap &&
                    (uncompressedData[overlapBitmapBase + (glyphId ushr 3)].toInt() and (0x80 ushr (glyphId and 7))) != 0

                glyphSize = storeSimpleGlyphPoints(
                    totalPoints,
                    pointX,
                    pointY,
                    pointOnCurve,
                    hasOverlapBit,
                    glyphBuf,
                    writePos
                )
                if (glyphSize < 0) return null
                xMins[glyphId] = readS16BE(glyphBuf, 2)
            } else {
                if (haveBbox) return null
            }

            if (glyphSize > 0) {
                if (glyfOut.size().toLong() + glyphSize.toLong() + 4L > MAX_OUTPUT_SFNT_BYTES.toLong()) {
                    return null
                }
                glyfOut.write(glyphBuf, 0, glyphSize)
                val pad = round4Int(glyfOut.size()) - glyfOut.size()
                if (pad > 0) {
                    glyfOut.write(padZeroes, 0, pad)
                }
            }
        }

        val glyfBytes = glyfOut.toByteArray()
        locaOffsets[numGlyphs] = glyfBytes.size

        val locaBytes = ByteArray(locaEntry.origLength)
        if (indexFormat != 0) {
            for (i in 0..numGlyphs) {
                storeU32(locaBytes, i * 4, locaOffsets[i])
            }
        } else {
            for (i in 0..numGlyphs) {
                val halfOffset = locaOffsets[i] ushr 1
                if ((locaOffsets[i] and 1) != 0 || halfOffset > 0xFFFF) return null
                storeU16(locaBytes, i * 2, halfOffset)
            }
        }

        return ReconstructedGlyfPair(
            glyfBytes = glyfBytes,
            locaBytes = locaBytes,
            numGlyphs = numGlyphs,
            xMins = xMins
        )
    }

    private fun decodeTriplets(
        flagStream: ByteSlice,
        glyphStream: ByteSlice,
        nPoints: Int,
        outX: IntArray,
        outY: IntArray,
        outOnCurve: BooleanArray
    ): Boolean {
        var x = 0
        var y = 0
        for (i in 0 until nPoints) {
            val rawFlag = flagStream.readU8()
            if (rawFlag < 0) return false
            outOnCurve[i] = (rawFlag and 0x80) == 0
            val flag = rawFlag and 0x7F

            val dx: Int
            val dy: Int
            when {
                flag < 10 -> {
                    val b0 = glyphStream.readU8()
                    if (b0 < 0) return false
                    dx = 0
                    dy = withSign(flag, ((flag and 14) shl 7) + b0)
                }
                flag < 20 -> {
                    val b0 = glyphStream.readU8()
                    if (b0 < 0) return false
                    dx = withSign(flag, (((flag - 10) and 14) shl 7) + b0)
                    dy = 0
                }
                flag < 84 -> {
                    val b1 = glyphStream.readU8()
                    if (b1 < 0) return false
                    val b0 = flag - 20
                    dx = withSign(flag, 1 + (b0 and 0x30) + (b1 ushr 4))
                    dy = withSign(flag ushr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F))
                }
                flag < 120 -> {
                    val c0 = glyphStream.readU8()
                    val c1 = glyphStream.readU8()
                    if (c0 < 0 || c1 < 0) return false
                    val b0 = flag - 84
                    dx = withSign(flag, 1 + ((b0 / 12) shl 8) + c0)
                    dy = withSign(flag ushr 1, 1 + (((b0 % 12) ushr 2) shl 8) + c1)
                }
                flag < 124 -> {
                    val c0 = glyphStream.readU8()
                    val c1 = glyphStream.readU8()
                    val c2 = glyphStream.readU8()
                    if (c0 < 0 || c1 < 0 || c2 < 0) return false
                    dx = withSign(flag, (c0 shl 4) + (c1 ushr 4))
                    dy = withSign(flag ushr 1, ((c1 and 0x0F) shl 8) + c2)
                }
                else -> {
                    val c0 = glyphStream.readU8()
                    val c1 = glyphStream.readU8()
                    val c2 = glyphStream.readU8()
                    val c3 = glyphStream.readU8()
                    if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return false
                    dx = withSign(flag, (c0 shl 8) + c1)
                    dy = withSign(flag ushr 1, (c2 shl 8) + c3)
                }
            }

            val nextX = x.toLong() + dx.toLong()
            val nextY = y.toLong() + dy.toLong()
            if (nextX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                nextY !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                return false
            }
            x = nextX.toInt()
            y = nextY.toInt()
            outX[i] = x
            outY[i] = y
        }
        return true
    }

    private fun storeSimpleGlyphPoints(
        nPoints: Int,
        pointX: IntArray,
        pointY: IntArray,
        pointOnCurve: BooleanArray,
        hasOverlapBit: Boolean,
        dst: ByteArray,
        startFlagOffset: Int
    ): Int {
        var flagOffset = startFlagOffset
        var lastFlag = -1
        var repeatCount = 0
        var lastX = 0
        var lastY = 0
        var xBytes = 0
        var yBytes = 0

        for (i in 0 until nPoints) {
            var flag = if (pointOnCurve[i]) GLYF_ON_CURVE else 0
            if (hasOverlapBit && i == 0) {
                flag = flag or GLYF_OVERLAP_SIMPLE
            }
            val dx = pointX[i] - lastX
            val dy = pointY[i] - lastY
            if (dx == 0) {
                flag = flag or GLYF_THIS_X_IS_SAME
            } else if (dx in -255..255) {
                flag = flag or GLYF_X_SHORT or (if (dx > 0) GLYF_THIS_X_IS_SAME else 0)
                xBytes += 1
            } else {
                xBytes += 2
            }

            if (dy == 0) {
                flag = flag or GLYF_THIS_Y_IS_SAME
            } else if (dy in -255..255) {
                flag = flag or GLYF_Y_SHORT or (if (dy > 0) GLYF_THIS_Y_IS_SAME else 0)
                yBytes += 1
            } else {
                yBytes += 2
            }

            if (flag == lastFlag && repeatCount != 255) {
                dst[flagOffset - 1] = (dst[flagOffset - 1].toInt() or GLYF_REPEAT).toByte()
                repeatCount++
            } else {
                if (repeatCount != 0) {
                    if (flagOffset >= dst.size) return -1
                    dst[flagOffset++] = repeatCount.toByte()
                }
                if (flagOffset >= dst.size) return -1
                dst[flagOffset++] = flag.toByte()
                repeatCount = 0
            }

            lastX = pointX[i]
            lastY = pointY[i]
            lastFlag = flag
        }

        if (repeatCount != 0) {
            if (flagOffset >= dst.size) return -1
            dst[flagOffset++] = repeatCount.toByte()
        }

        val totalSize = flagOffset + xBytes + yBytes
        if (totalSize < flagOffset || totalSize > dst.size) return -1

        var xOffset = flagOffset
        var yOffset = flagOffset + xBytes
        lastX = 0
        lastY = 0
        for (i in 0 until nPoints) {
            val dx = pointX[i] - lastX
            if (dx != 0) {
                if (dx in -255..255) {
                    dst[xOffset++] = abs(dx).toByte()
                } else {
                    storeU16(dst, xOffset, dx)
                    xOffset += 2
                }
            }
            lastX += dx

            val dy = pointY[i] - lastY
            if (dy != 0) {
                if (dy in -255..255) {
                    dst[yOffset++] = abs(dy).toByte()
                } else {
                    storeU16(dst, yOffset, dy)
                    yOffset += 2
                }
            }
            lastY += dy
        }

        return yOffset
    }

    private fun computeAndStoreBbox(
        nPoints: Int,
        pointX: IntArray,
        pointY: IntArray,
        dst: ByteArray
    ) {
        var xMin = 0
        var yMin = 0
        var xMax = 0
        var yMax = 0
        if (nPoints > 0) {
            xMin = pointX[0]
            xMax = pointX[0]
            yMin = pointY[0]
            yMax = pointY[0]
            for (i in 1 until nPoints) {
                val x = pointX[i]
                val y = pointY[i]
                if (x < xMin) xMin = x
                if (x > xMax) xMax = x
                if (y < yMin) yMin = y
                if (y > yMax) yMax = y
            }
        }
        storeU16(dst, 2, xMin)
        storeU16(dst, 4, yMin)
        storeU16(dst, 6, xMax)
        storeU16(dst, 8, yMax)
    }

    private fun reconstructTransformedHmtx(
        uncompressedData: ByteArray,
        hmtxEntry: TableEntry,
        numGlyphs: Int,
        numHMetrics: Int,
        xMins: ShortArray
    ): ByteArray? {
        if (numHMetrics < 1 || numHMetrics > numGlyphs) return null
        if (hmtxEntry.srcOffset < 0 || hmtxEntry.srcOffset + hmtxEntry.transformLength > uncompressedData.size) {
            return null
        }
        val reader = ByteSlice(uncompressedData, hmtxEntry.srcOffset, hmtxEntry.transformLength)
        val flags = reader.readU8()
        if (flags < 0) return null
        if ((flags and 0xFC) != 0) return null

        val hasProportionalLsbs = (flags and 0x01) == 0
        val hasMonospaceLsbs = (flags and 0x02) == 0
        if (hasProportionalLsbs && hasMonospaceLsbs) return null

        val advanceWidths = IntArray(numHMetrics)
        for (i in 0 until numHMetrics) {
            val aw = reader.readU16()
            if (aw < 0) return null
            advanceWidths[i] = aw
        }

        val lsbs = ShortArray(numGlyphs)
        for (i in 0 until numHMetrics) {
            lsbs[i] = if (hasProportionalLsbs) {
                val s = reader.readS16() ?: return null
                s.toShort()
            } else {
                xMins[i]
            }
        }
        for (i in numHMetrics until numGlyphs) {
            lsbs[i] = if (hasMonospaceLsbs) {
                val s = reader.readS16() ?: return null
                s.toShort()
            } else {
                xMins[i]
            }
        }

        val outSize = 2 * numGlyphs + 2 * numHMetrics
        val out = ByteArray(outSize)
        var pos = 0
        for (i in 0 until numGlyphs) {
            if (i < numHMetrics) {
                storeU16(out, pos, advanceWidths[i])
                pos += 2
            }
            storeU16(out, pos, lsbs[i].toInt())
            pos += 2
        }
        return out
    }

    private fun extractXMinsFromSfntGlyf(
        glyfBytes: ByteArray,
        locaBytes: ByteArray,
        indexFormat: Int,
        expectedNumGlyphs: Int
    ): ShortArray? {
        val locaCount = if (indexFormat == 0) locaBytes.size / 2 else locaBytes.size / 4
        if (locaCount < 2) return null
        val numGlyphs = if (expectedNumGlyphs > 0) expectedNumGlyphs else (locaCount - 1)
        if (locaCount < numGlyphs + 1) return null

        val bb = ByteBuffer.wrap(locaBytes).order(ByteOrder.BIG_ENDIAN)
        val xMins = ShortArray(numGlyphs)
        for (i in 0 until numGlyphs) {
            val start = if (indexFormat == 0) (bb.getShort(i * 2).toInt() and 0xFFFF) * 2 else bb.getInt(i * 4)
            val end = if (indexFormat == 0) (bb.getShort((i + 1) * 2).toInt() and 0xFFFF) * 2 else bb.getInt((i + 1) * 4)
            if (start < 0 || end < start || end > glyfBytes.size) return null
            if (end - start >= 10) {
                val nContours = readS16BE(glyfBytes, start).toInt()
                if (nContours != 0) {
                    xMins[i] = readS16BE(glyfBytes, start + 2)
                }
            }
        }
        return xMins
    }

    private fun decompressBrotliExact(
        src: ByteArray,
        offset: Int,
        length: Int,
        expectedSize: Int
    ): ByteArray? {
        if (expectedSize <= 0 || expectedSize > MAX_DECOMPRESSED_BYTES) return null
        val out = ByteArray(expectedSize)
        BrotliInputStream(ByteArrayInputStream(src, offset, length)).use { brotli ->
            var pos = 0
            while (pos < expectedSize) {
                val n = brotli.read(out, pos, expectedSize - pos)
                if (n <= 0) return null
                pos += n
            }
            if (brotli.read() != -1) return null
        }
        return out
    }

    private fun writeOffsetTable(dst: ByteArray, offset: Int, flavor: Int, numTables: Int) {
        var maxPow2 = 0
        while ((1 shl (maxPow2 + 1)) <= numTables) {
            maxPow2++
        }
        val searchRange = (1 shl maxPow2) shl 4
        val rangeShift = (numTables shl 4) - searchRange
        storeU32(dst, offset, flavor)
        storeU16(dst, offset + 4, numTables)
        storeU16(dst, offset + 6, searchRange)
        storeU16(dst, offset + 8, maxPow2)
        storeU16(dst, offset + 10, rangeShift)
    }

    private fun computeSfntChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        val alignedEnd = offset + (length and -4)
        var i = offset
        while (i < alignedEnd) {
            val word = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            sum += word
            i += 4
        }
        val tail = length and 3
        if (tail != 0) {
            var lastWord = 0
            for (t in 0 until tail) {
                lastWord = lastWord or ((data[alignedEnd + t].toInt() and 0xFF) shl (24 - t * 8))
            }
            sum += lastWord
        }
        return sum
    }

    private fun withSign(flag: Int, baseVal: Int): Int = if ((flag and 1) != 0) baseVal else -baseVal

    private fun compareTagsUnsigned(a: Int, b: Int): Int =
        (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)

    private fun round4Int(value: Int): Int = (value + 3) and -4

    private fun round4Long(value: Long): Long = (value + 3L) and -4L

    private fun readU16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readS16BE(buf: ByteArray, offset: Int): Short =
        (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)).toShort()

    private fun storeU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }

    private fun storeU32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }
}
