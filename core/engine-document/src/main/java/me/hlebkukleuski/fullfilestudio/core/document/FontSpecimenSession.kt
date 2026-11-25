/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class FontSpecimenSession(
    private val pfd: ParcelFileDescriptor,
    private val extension: String = "ttf"
) : DocumentSession {

    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    data class SfntTableEntry(
        val tag: String,
        val checkSum: Long,
        val offset: Int,
        val length: Int
    )

    private var customTypeface: Typeface? = null
    private var tempFontFile: File? = null

    private var fontFamily = ""
    private var fontSubfamily = "Regular"
    private var fullFontName = ""
    private var postScriptName = ""
    private var fontVersion = ""
    private var copyrightNotice = ""
    private var designerName = ""
    private var unitsPerEm = 1000
    private var numGlyphs = 0
    private var sfntTypeLabel = "OpenType / TrueType"
    private val tableEntries = mutableListOf<SfntTableEntry>()
    private val supportedCodepoints = ArrayList<Int>()
    private val showcaseSampleLines = ArrayList<Pair<String, Float>>()
    private val activePangramLines = ArrayList<String>()
    private val pageSummaries = ArrayList<String>()

    companion object {
        private const val GLYPHS_PER_PAGE = 64
        private const val VIRTUAL_DOC_WIDTH = 1280f
        private const val VIRTUAL_DOC_HEIGHT = 1810f
    }

    init {
        parseFontAndBuildTypeface()
    }

    private fun parseFontAndBuildTypeface() {
        try {
            val fileSize = minOf(source.size, 48L * 1024L * 1024L).toInt()
            if (fileSize < 12) {
                lastErrorMsg = "Font file is empty or truncated (< 12 bytes)."
                return
            }

            val buf = ByteBuffer.allocateDirect(fileSize)
            val got = source.readAt(0L, buf)
            if (got < 12) {
                lastErrorMsg = "Unable to read font header."
                return
            }
            buf.flip()
            var bytes = ByteArray(got)
            buf.get(bytes)

            val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            var sfntOffset = 0
            var unpackedFromContainer = false

            when (magic) {
                0x774F4646 -> {
                    sfntTypeLabel = "WOFF 1.0 Web Font"
                    val unpacked = unpackWoff1ToSfnt(bytes)
                    if (unpacked == null) {
                        lastErrorMsg = "Invalid or corrupted WOFF 1.0 font."
                        return
                    }
                    bytes = unpacked
                    unpackedFromContainer = true
                }
                0x774F4632 -> {
                    sfntTypeLabel = "WOFF 2.0 Web Font"
                    val unpacked = Woff2Decoder.decodeToSfnt(bytes)
                    if (unpacked == null) {
                        lastErrorMsg = "Invalid or corrupted WOFF 2.0 font."
                        return
                    }
                    bytes = unpacked
                    unpackedFromContainer = true
                }
                0x74746366 -> {
                    sfntTypeLabel = "TrueType Collection (TTC)"
                }
                0x4F54544F -> {
                    sfntTypeLabel = "OpenType CFF (OTF)"
                }
                0x00010000, 0x74727565 -> {
                    sfntTypeLabel = "TrueType (TTF)"
                }
            }

            if (bytes.size >= 16 && ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int == 0x74746366) {
                val firstOffset = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(12)
                if (firstOffset in 12 until bytes.size) {
                    sfntOffset = firstOffset
                }
            }

            if (!unpackedFromContainer) {
                customTypeface = try {
                    Typeface.Builder(pfd.fileDescriptor).build()
                } catch (_: Throwable) {
                    null
                }
            }

            if (customTypeface == null) {
                try {
                    val ext = if (sfntOffset > 0) ".ttc" else ".ttf"
                    val tmp = me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.createTempFile("omni_font_", ext)
                    try {
                        me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.openOutput(tmp).use { it.write(bytes) }
                        val memoryFd = me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.openReadOnlyDescriptor(tmp)
                        customTypeface = if (memoryFd != null) {
                            Typeface.Builder(memoryFd).build()
                        } else {
                            Typeface.createFromFile(tmp)
                        }
                    } finally {
                        me.hlebkukleuski.fullfilestudio.core.engine.ScratchFiles.delete(tmp)
                    }
                } catch (_: Throwable) {}
            }

            parseSfntTables(bytes, sfntOffset)

            if (supportedCodepoints.isEmpty()) {
                populateDefaultPreviewCodepoints()
            }

            buildAdaptiveShowcaseAndPangrams()
            buildSearchablePageSummaries()
        } catch (e: Exception) {
            lastErrorMsg = "Font parse error: ${e.localizedMessage}"
        }
    }

    private fun unpackWoff1ToSfnt(woff: ByteArray): ByteArray? {
        if (woff.size < 44) return null
        return try {
            val bb = ByteBuffer.wrap(woff).order(ByteOrder.BIG_ENDIAN)
            val flavor = bb.getInt(4)
            val numTables = bb.getShort(12).toInt() and 0xFFFF
            if (numTables !in 1..128 || 44 + numTables * 20 > woff.size) return null

            var searchRange = 16
            var entrySelector = 0
            while (searchRange * 2 <= numTables * 16) {
                searchRange *= 2
                entrySelector++
            }
            val rangeShift = numTables * 16 - searchRange

            val sfntHeaderSize = 12 + numTables * 16
            val unpackedTables = ArrayList<ByteArray>(numTables)
            val tags = IntArray(numTables)
            val origLengths = IntArray(numTables)
            val checksums = IntArray(numTables)

            var totalPayloadSize = 0
            for (i in 0 until numTables) {
                val base = 44 + i * 20
                tags[i] = bb.getInt(base)
                val offset = bb.getInt(base + 4)
                val compLen = bb.getInt(base + 8)
                val origLen = bb.getInt(base + 12)
                checksums[i] = bb.getInt(base + 16)

                if (offset < 44 || compLen < 0 || origLen < 0 || origLen > 32 * 1024 * 1024 ||
                    offset.toLong() + compLen.toLong() > woff.size.toLong()
                ) {
                    return null
                }
                val tableData: ByteArray = if (compLen == origLen) {
                    woff.copyOfRange(offset, offset + compLen)
                } else {
                    val inf = Inflater(false)
                    try {
                        inf.setInput(woff, offset, compLen)
                        val out = ByteArray(origLen)
                        var pos = 0
                        while (!inf.finished() && pos < origLen) {
                            val r = inf.inflate(out, pos, origLen - pos)
                            if (r <= 0) break
                            pos += r
                        }
                        out
                    } finally {
                        inf.end()
                    }
                }
                unpackedTables.add(tableData)
                origLengths[i] = tableData.size
                totalPayloadSize += (tableData.size + 3) and -4
            }

            val sfnt = ByteArray(sfntHeaderSize + totalPayloadSize)
            val outBb = ByteBuffer.wrap(sfnt).order(ByteOrder.BIG_ENDIAN)
            outBb.putInt(0, flavor)
            outBb.putShort(4, numTables.toShort())
            outBb.putShort(6, searchRange.toShort())
            outBb.putShort(8, entrySelector.toShort())
            outBb.putShort(10, rangeShift.toShort())

            var curDataOffset = sfntHeaderSize
            for (i in 0 until numTables) {
                val dirBase = 12 + i * 16
                outBb.putInt(dirBase, tags[i])
                outBb.putInt(dirBase + 4, checksums[i])
                outBb.putInt(dirBase + 8, curDataOffset)
                outBb.putInt(dirBase + 12, origLengths[i])

                val tBytes = unpackedTables[i]
                System.arraycopy(tBytes, 0, sfnt, curDataOffset, tBytes.size)
                curDataOffset += (tBytes.size + 3) and -4
            }
            sfnt
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseSfntTables(bytes: ByteArray, sfntOffset: Int) {
        if (sfntOffset + 12 > bytes.size) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numTables = (bb.getShort(sfntOffset + 4).toInt() and 0xFFFF).coerceIn(0, 128)
        val dirStart = sfntOffset + 12

        for (i in 0 until numTables) {
            val entryPos = dirStart + i * 16
            if (entryPos + 16 > bytes.size) break
            val tagStr = String(bytes, entryPos, 4, StandardCharsets.US_ASCII)
            val checkSum = bb.getInt(entryPos + 4).toLong() and 0xFFFFFFFFL
            val tOffset = bb.getInt(entryPos + 8)
            val tLen = bb.getInt(entryPos + 12)
            if (tOffset > 0 && tLen >= 0) {
                tableEntries.add(SfntTableEntry(tagStr, checkSum, tOffset, tLen))
            }
        }

        tableEntries.firstOrNull { it.tag == "head" }?.let { head ->
            if (head.offset + 20 <= bytes.size && head.length >= 20) {
                unitsPerEm = (bb.getShort(head.offset + 18).toInt() and 0xFFFF).coerceIn(16, 16384)
            }
        }

        tableEntries.firstOrNull { it.tag == "maxp" }?.let { maxp ->
            if (maxp.offset + 6 <= bytes.size && maxp.length >= 6) {
                numGlyphs = (bb.getShort(maxp.offset + 4).toInt() and 0xFFFF).coerceAtLeast(0)
            }
        }

        tableEntries.firstOrNull { it.tag == "name" }?.let { nameTbl ->
            parseNameTable(bytes, bb, nameTbl.offset, nameTbl.length)
        }

        tableEntries.firstOrNull { it.tag == "cmap" }?.let { cmapTbl ->
            parseCmapTable(bytes, bb, cmapTbl.offset, cmapTbl.length)
        }

        if (fullFontName.isBlank()) {
            fullFontName = if (fontFamily.isNotBlank()) "$fontFamily $fontSubfamily".trim() else "OpenType / TrueType Font"
        }
        if (fontFamily.isBlank()) {
            fontFamily = fullFontName
        }
    }

    private fun parseNameTable(bytes: ByteArray, bb: ByteBuffer, tableOffset: Int, tableLength: Int) {
        if (tableOffset + 6 > bytes.size || tableLength < 6) return
        val count = (bb.getShort(tableOffset + 2).toInt() and 0xFFFF).coerceIn(0, 512)
        val stringStorageOffset = tableOffset + (bb.getShort(tableOffset + 4).toInt() and 0xFFFF)
        val tableEnd = minOf(bytes.size, tableOffset + tableLength)

        val namesById = HashMap<Int, String>()

        for (i in 0 until count) {
            val recPos = tableOffset + 6 + i * 12
            if (recPos + 12 > tableEnd) break
            val platformId = bb.getShort(recPos).toInt() and 0xFFFF
            val encodingId = bb.getShort(recPos + 2).toInt() and 0xFFFF
            val languageId = bb.getShort(recPos + 4).toInt() and 0xFFFF
            val nameId = bb.getShort(recPos + 6).toInt() and 0xFFFF
            val length = bb.getShort(recPos + 8).toInt() and 0xFFFF
            val strOffset = stringStorageOffset + (bb.getShort(recPos + 10).toInt() and 0xFFFF)

            if (length <= 0 || strOffset < tableOffset || strOffset + length > tableEnd) continue

            val charset = when (platformId) {
                0, 3 -> StandardCharsets.UTF_16BE
                1 -> if (encodingId == 0) StandardCharsets.UTF_8 else PlainTextSession.CP1251
                else -> StandardCharsets.UTF_8
            }
            val decoded = try {
                String(bytes, strOffset, length, charset)
                    .replace('\u0000', ' ')
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } catch (_: Throwable) {
                ""
            }
            if (decoded.isNotEmpty()) {
                val isPreferred = (platformId == 3 && (languageId == 0x0409 || languageId == 0x0419)) || !namesById.containsKey(nameId)
                if (isPreferred) {
                    namesById[nameId] = decoded
                }
            }
        }

        copyrightNotice = namesById[0] ?: ""
        fontFamily = namesById[16] ?: namesById[1] ?: ""
        fontSubfamily = namesById[17] ?: namesById[2] ?: "Regular"
        fullFontName = namesById[4] ?: ""
        fontVersion = namesById[5] ?: ""
        postScriptName = namesById[6] ?: ""
        designerName = namesById[9] ?: namesById[8] ?: ""
    }

    private fun parseCmapTable(bytes: ByteArray, bb: ByteBuffer, tableOffset: Int, tableLength: Int) {
        if (tableOffset + 4 > bytes.size || tableLength < 4) return
        val numSubtables = (bb.getShort(tableOffset + 2).toInt() and 0xFFFF).coerceIn(0, 64)
        val tableEnd = minOf(bytes.size, tableOffset + tableLength)

        val format12Offsets = ArrayList<Int>()
        val format4Offsets = ArrayList<Int>()
        val format6Offsets = ArrayList<Int>()

        for (i in 0 until numSubtables) {
            val recPos = tableOffset + 4 + i * 8
            if (recPos + 8 > tableEnd) break
            val platformId = bb.getShort(recPos).toInt() and 0xFFFF
            val encodingId = bb.getShort(recPos + 2).toInt() and 0xFFFF
            val subOffset = tableOffset + bb.getInt(recPos + 4)
            if (subOffset < tableOffset || subOffset + 4 > tableEnd) continue
            val fmt = bb.getShort(subOffset).toInt() and 0xFFFF
            val isUnicodePlatform = platformId == 0 || (platformId == 3 && encodingId in setOf(0, 1, 10))
            when (fmt) {
                12 -> if (isUnicodePlatform || format12Offsets.isEmpty()) format12Offsets.add(subOffset)
                4 -> if (isUnicodePlatform || format4Offsets.isEmpty()) format4Offsets.add(subOffset)
                6 -> format6Offsets.add(subOffset)
            }
        }

        val codeSet = java.util.TreeSet<Int>()

        for (format12Offset in format12Offsets) {
            if (format12Offset + 16 <= tableEnd) {
                val numGroups = (bb.getInt(format12Offset + 12).toLong() and 0xFFFFFFFFL).coerceAtMost(65536L).toInt()
                var gPos = format12Offset + 16
                for (g in 0 until numGroups) {
                    if (gPos + 12 > tableEnd || codeSet.size >= 32768) break
                    val startChar = bb.getInt(gPos)
                    val endChar = bb.getInt(gPos + 4)
                    val startGlyphId = bb.getInt(gPos + 8)
                    if (startChar >= 0x20 && endChar >= startChar && endChar <= 0x10FFFF) {
                        for (cp in startChar..minOf(endChar, startChar + 16384)) {
                            val glyphId = startGlyphId + (cp - startChar)
                            if (glyphId > 0 && !Character.isISOControl(cp) && cp !in 0xD800..0xDFFF) {
                                codeSet.add(cp)
                                if (codeSet.size >= 32768) break
                            }
                        }
                    }
                    gPos += 12
                }
            }
        }

        for (format4Offset in format4Offsets) {
            if (format4Offset + 16 <= tableEnd) {
                val subLen = (bb.getShort(format4Offset + 2).toInt() and 0xFFFF)
                val subEnd = if (subLen > 16) minOf(tableEnd, format4Offset + subLen) else tableEnd
                val segCountX2 = (bb.getShort(format4Offset + 6).toInt() and 0xFFFF).coerceIn(2, 8192)
                val segCount = segCountX2 / 2
                val endCodesBase = format4Offset + 14
                val startCodesBase = endCodesBase + segCountX2 + 2
                val idDeltaBase = startCodesBase + segCountX2
                val idRangeOffsetBase = idDeltaBase + segCountX2

                if (idRangeOffsetBase + segCountX2 <= subEnd) {
                    for (s in 0 until segCount) {
                        if (codeSet.size >= 32768) break
                        val endCode = bb.getShort(endCodesBase + s * 2).toInt() and 0xFFFF
                        val startCode = bb.getShort(startCodesBase + s * 2).toInt() and 0xFFFF
                        if (startCode == 0xFFFF) break
                        val idDelta = bb.getShort(idDeltaBase + s * 2).toInt()
                        val idRangeOffsetPos = idRangeOffsetBase + s * 2
                        val idRangeOffset = bb.getShort(idRangeOffsetPos).toInt() and 0xFFFF

                        if (startCode >= 0x20 && endCode >= startCode) {
                            for (cp in startCode..endCode) {
                                if (Character.isISOControl(cp) || cp in 0xD800..0xDFFF) continue
                                val glyphId: Int = if (idRangeOffset == 0) {
                                    (cp + idDelta) and 0xFFFF
                                } else {
                                    val glyphIndexAddress = idRangeOffsetPos + idRangeOffset + 2 * (cp - startCode)
                                    if (glyphIndexAddress + 2 <= subEnd) {
                                        val rawGid = bb.getShort(glyphIndexAddress).toInt() and 0xFFFF
                                        if (rawGid != 0) (rawGid + idDelta) and 0xFFFF else 0
                                    } else {
                                        0
                                    }
                                }
                                if (glyphId != 0) {
                                    codeSet.add(cp)
                                    if (codeSet.size >= 32768) break
                                }
                            }
                        }
                    }
                }
            }
        }

        if (codeSet.isEmpty()) {
            for (format6Offset in format6Offsets) {
                if (format6Offset + 10 <= tableEnd) {
                    val firstCode = bb.getShort(format6Offset + 6).toInt() and 0xFFFF
                    val entryCount = (bb.getShort(format6Offset + 8).toInt() and 0xFFFF).coerceIn(0, 8192)
                    val arrayBase = format6Offset + 10
                    for (i in 0 until entryCount) {
                        val pos = arrayBase + i * 2
                        if (pos + 2 > tableEnd) break
                        val gid = bb.getShort(pos).toInt() and 0xFFFF
                        val cp = firstCode + i
                        if (gid != 0 && cp >= 0x20 && !Character.isISOControl(cp)) {
                            codeSet.add(cp)
                        }
                    }
                }
            }
        }

        supportedCodepoints.addAll(codeSet)
    }

    private fun populateDefaultPreviewCodepoints() {
        for (cp in 0x0021..0x007E) supportedCodepoints.add(cp)
        for (cp in 0x0401..0x0451) supportedCodepoints.add(cp)
    }

    private fun buildAdaptiveShowcaseAndPangrams() {
        showcaseSampleLines.clear()
        activePangramLines.clear()
        val cpSet = supportedCodepoints.toHashSet()

        fun filterSupported(candidate: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < candidate.length) {
                val cp = candidate.codePointAt(i)
                if (cp == ' '.code || cp in cpSet) {
                    sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        val upperLatin = filterSupported("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val lowerLatin = filterSupported("abcdefghijklmnopqrstuvwxyz")
        val upperCyr = filterSupported("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ")
        val lowerCyr = filterSupported("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
        val slavonicExt = filterSupported("ѢѣѦѧѨѩѪѫѬѭѮѯѰѱѲѳѴѵꙊꙋѺѻѾѿҀҁ")
        val digitsAndSymbols = filterSupported("0123456789  !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~  № € ₽ $ £ ¥")

        if (upperLatin.length >= 6) showcaseSampleLines.add(upperLatin to 38f)
        if (lowerLatin.length >= 6) showcaseSampleLines.add(lowerLatin to 38f)
        if (upperCyr.length >= 6) showcaseSampleLines.add(upperCyr to 36f)
        if (lowerCyr.length >= 6) showcaseSampleLines.add(lowerCyr to 36f)
        if (slavonicExt.length >= 4) showcaseSampleLines.add(slavonicExt to 36f)
        if (digitsAndSymbols.isNotEmpty()) showcaseSampleLines.add(digitsAndSymbols to 34f)

        if (showcaseSampleLines.isEmpty() && supportedCodepoints.isNotEmpty()) {
            val customLine = supportedCodepoints.take(32).joinToString(" ") { String(Character.toChars(it)) }
            showcaseSampleLines.add(customLine to 38f)
        }

        val hasCyrillic = upperCyr.length >= 10 || lowerCyr.length >= 10
        val hasLatin = upperLatin.length >= 10 || lowerLatin.length >= 10

        if (hasCyrillic) {
            val ruPangram = if (lowerCyr.length >= 10) {
                filterSupported("Съешь же ещё этих мягких французских булок, да выпей чаю 1234567890")
            } else {
                filterSupported("СЪЕШЬ ЖЕ ЕЩЁ ЭТИХ МЯГКИХ ФРАНЦУЗСКИХ БУЛОК, ДА ВЫПЕЙ ЧАЮ 1234567890")
            }
            if (ruPangram.isNotEmpty()) activePangramLines.add(ruPangram)
        }
        if (hasLatin) {
            val enPangram = if (lowerLatin.length >= 10) {
                filterSupported("The quick brown fox jumps over the lazy dog 1234567890")
            } else {
                filterSupported("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890")
            }
            if (enPangram.isNotEmpty()) activePangramLines.add(enPangram)
        }
        if (activePangramLines.isEmpty() && showcaseSampleLines.isNotEmpty()) {
            activePangramLines.add(showcaseSampleLines.first().first)
        }
    }

    private fun buildSearchablePageSummaries() {
        pageSummaries.clear()
        val p1 = StringBuilder()
        p1.append(fullFontName).append('\n')
        p1.append("Format: ").append(sfntTypeLabel)
            .append(" | Family: ").append(fontFamily)
            .append(" | Style: ").append(fontSubfamily)
            .append(" | Glyphs: ").append(numGlyphs)
            .append(" | UnitsPerEm: ").append(unitsPerEm)
            .append(" | Mapped Chars: ").append(supportedCodepoints.size).append('\n')
        val detailsLine = buildString {
            if (postScriptName.isNotEmpty()) append("PostScript: $postScriptName   ")
            if (fontVersion.isNotEmpty()) append("Version: $fontVersion   ")
            if (designerName.isNotEmpty()) append("Designer: $designerName")
        }.trim()
        if (detailsLine.isNotEmpty()) p1.append(detailsLine).append('\n')
        if (copyrightNotice.isNotEmpty()) p1.append("Copyright: ").append(copyrightNotice).append('\n')
        p1.append("SFNT Tables (").append(tableEntries.size).append("): ")
            .append(tableEntries.joinToString(", ") { "${it.tag} (${it.length} B)" }).append('\n')

        for ((lineStr, _) in showcaseSampleLines) {
            p1.append(lineStr).append('\n')
        }
        for (pangram in activePangramLines) {
            p1.append("PANGRAM: ").append(pangram).append('\n')
        }
        pageSummaries.add(p1.toString().trim())

        var idx = 0
        while (idx < supportedCodepoints.size) {
            val end = minOf(supportedCodepoints.size, idx + GLYPHS_PER_PAGE)
            val slice = supportedCodepoints.subList(idx, end)
            val sb = StringBuilder()
            for (cp in slice) {
                val hex = String.format(Locale.US, "U+%04X", cp)
                val chStr = String(Character.toChars(cp))
                sb.append('[').append(hex).append(" '").append(chStr).append("'] ")
            }
            pageSummaries.add(sb.toString().trim())
            idx = end
        }
    }

    override val pageCount: Int
        get() = pageSummaries.size.coerceAtLeast(1)

    override fun getLastError(): String? = lastErrorMsg
    override fun getDocumentWidth(): Float = VIRTUAL_DOC_WIDTH
    override fun getDocumentHeight(): Float = VIRTUAL_DOC_HEIGHT

    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return pageSummaries.joinToString("\n\n")
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageSummaries.size - 1).coerceAtLeast(0))
        return pageSummaries.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val cleanPage = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        val bgColor = if (isLight) Color.rgb(0xF8, 0xFA, 0xFC) else Color.rgb(0x12, 0x13, 0x1A)
        val cardBgColor = if (isLight) Color.WHITE else Color.rgb(0x1C, 0x1E, 0x2B)
        val primaryTextColor = if (isLight) Color.rgb(0x18, 0x1A, 0x24) else Color.WHITE
        val secondaryTextColor = if (isLight) Color.rgb(0x5A, 0x62, 0x75) else Color.rgb(0xA6, 0xAD, 0xC8)
        val accentColor = if (isLight) Color.rgb(0x00, 0x79, 0x6B) else Color.rgb(0x03, 0xDA, 0xC6)
        val borderColor = if (isLight) Color.rgb(0xD8, 0xDE, 0xE9) else Color.rgb(0x31, 0x32, 0x44)

        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 6.0f)
        val baseScale = minOf(
            bitmap.width.toFloat() / VIRTUAL_DOC_WIDTH,
            bitmap.height.toFloat() / VIRTUAL_DOC_HEIGHT
        ).coerceAtLeast(0.1f)
        val totalScale = baseScale * zoom

        val scaledW = VIRTUAL_DOC_WIDTH * totalScale
        val scaledH = VIRTUAL_DOC_HEIGHT * totalScale
        val dx = (bitmap.width - scaledW) / 2f - tileX.toFloat()
        val dy = (bitmap.height - scaledH) / 2f - tileY.toFloat()

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(totalScale, totalScale)

        val tf = customTypeface ?: Typeface.DEFAULT

        if (cleanPage == 0) {
            renderSpecimenOverviewPage(
                canvas = canvas,
                tf = tf,
                cardBgColor = cardBgColor,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                borderColor = borderColor
            )
        } else {
            renderGlyphGridPage(
                canvas = canvas,
                glyphPageIndex = cleanPage - 1,
                tf = tf,
                cardBgColor = cardBgColor,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor,
                borderColor = borderColor
            )
        }

        canvas.restore()
    }

    private fun renderSpecimenOverviewPage(
        canvas: Canvas,
        tf: Typeface,
        cardBgColor: Int,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        accentColor: Int,
        borderColor: Int
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val sysPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
        }
        val fontPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = primaryTextColor
        }

        val pageRect = RectF(32f, 32f, VIRTUAL_DOC_WIDTH - 32f, VIRTUAL_DOC_HEIGHT - 32f)
        canvas.drawRoundRect(pageRect, 20f, 20f, cardPaint)
        canvas.drawRoundRect(pageRect, 20f, 20f, borderPaint)

        var y = 96f
        val leftPad = 68f
        val maxContentW = VIRTUAL_DOC_WIDTH - leftPad * 2f

        fontPaint.textSize = 68f
        val canRenderTitleInFont = fullFontName.any { it != ' ' && fontPaint.hasGlyph(it.toString()) }
        val activeTitlePaint = if (canRenderTitleInFont) {
            fontPaint
        } else {
            TextPaint(sysPaint).apply {
                color = primaryTextColor
                textSize = 68f
                isFakeBoldText = true
            }
        }
        val heroTitle = TextUtils.ellipsize(fullFontName, activeTitlePaint, maxContentW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(heroTitle, leftPad, y, activeTitlePaint)
        y += 44f

        sysPaint.color = accentColor
        sysPaint.textSize = 24f
        sysPaint.isFakeBoldText = true
        val metaHeader = "Format: $sfntTypeLabel  •  Family: $fontFamily  •  Style: $fontSubfamily  •  Glyphs: $numGlyphs  •  UnitsPerEm: $unitsPerEm  •  Mapped Chars: ${supportedCodepoints.size}"
        canvas.drawText(
            TextUtils.ellipsize(metaHeader, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
            leftPad,
            y,
            sysPaint
        )
        y += 34f

        sysPaint.color = secondaryTextColor
        sysPaint.textSize = 21f
        sysPaint.isFakeBoldText = false
        val detailsLine = buildString {
            if (postScriptName.isNotEmpty()) append("PostScript: $postScriptName   ")
            if (fontVersion.isNotEmpty()) append("Version: $fontVersion   ")
            if (designerName.isNotEmpty()) append("Designer: $designerName")
        }.trim()
        if (detailsLine.isNotEmpty()) {
            canvas.drawText(
                TextUtils.ellipsize(detailsLine, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
                leftPad,
                y,
                sysPaint
            )
            y += 28f
        }
        if (copyrightNotice.isNotEmpty()) {
            canvas.drawText(
                TextUtils.ellipsize("Copyright: $copyrightNotice", sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
                leftPad,
                y,
                sysPaint
            )
            y += 30f
        }

        val tablesStr = "SFNT Tables (${tableEntries.size}): " +
                tableEntries.joinToString(", ") { "${it.tag} (${it.length} B)" }
        canvas.drawText(
            TextUtils.ellipsize(tablesStr, sysPaint, maxContentW, TextUtils.TruncateAt.END).toString(),
            leftPad,
            y,
            sysPaint
        )
        y += 26f

        canvas.drawLine(leftPad, y, VIRTUAL_DOC_WIDTH - leftPad, y, borderPaint)
        y += 46f

        for ((lineStr, sz) in showcaseSampleLines) {
            fontPaint.textSize = sz
            val fitted = TextUtils.ellipsize(lineStr, fontPaint, maxContentW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, leftPad, y, fontPaint)
            y += sz * 1.38f
        }

        y += 12f
        canvas.drawLine(leftPad, y, VIRTUAL_DOC_WIDTH - leftPad, y, borderPaint)
        y += 48f

        val waterfallSizes = intArrayOf(22, 28, 36, 48, 62)
        for (pt in waterfallSizes) {
            if (y + pt * 1.4f > VIRTUAL_DOC_HEIGHT - 54f) break
            for (pangram in activePangramLines) {
                if (y + pt * 1.3f > VIRTUAL_DOC_HEIGHT - 48f) break
                sysPaint.color = accentColor
                sysPaint.textSize = 19f
                sysPaint.isFakeBoldText = true
                canvas.drawText("${pt}px", leftPad, y, sysPaint)

                fontPaint.textSize = pt.toFloat()
                val clipped = TextUtils.ellipsize(pangram, fontPaint, maxContentW - 76f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(clipped, leftPad + 74f, y, fontPaint)
                y += pt * 1.32f
            }
            y += 6f
        }
    }

    private fun renderGlyphGridPage(
        canvas: Canvas,
        glyphPageIndex: Int,
        tf: Typeface,
        cardBgColor: Int,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        accentColor: Int,
        borderColor: Int
    ) {
        val startIdx = glyphPageIndex * GLYPHS_PER_PAGE
        if (startIdx >= supportedCodepoints.size) return
        val endIdx = minOf(supportedCodepoints.size, startIdx + GLYPHS_PER_PAGE)
        val slice = supportedCodepoints.subList(startIdx, endIdx)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 28f
            color = primaryTextColor
        }
        val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 18f
            color = secondaryTextColor
            textAlign = Paint.Align.CENTER
        }
        val glyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 64f
            color = primaryTextColor
            textAlign = Paint.Align.CENTER
        }

        val margin = 40f
        val headerTitle = "$fullFontName  •  Glyph Map (${startIdx + 1}–$endIdx of ${supportedCodepoints.size})"
        canvas.drawText(headerTitle, margin, 68f, headerPaint)

        val gridTop = 96f
        val gridCols = 8
        val gridRows = 8
        val cellW = (VIRTUAL_DOC_WIDTH - margin * 2f) / gridCols
        val cellH = (VIRTUAL_DOC_HEIGHT - gridTop - margin) / gridRows

        for ((i, cp) in slice.withIndex()) {
            val r = i / gridCols
            val c = i % gridCols
            val left = margin + c * cellW
            val top = gridTop + r * cellH
            val cellRect = RectF(left + 4f, top + 4f, left + cellW - 4f, top + cellH - 4f)

            canvas.drawRoundRect(cellRect, 10f, 10f, cardPaint)
            canvas.drawRoundRect(cellRect, 10f, 10f, borderPaint)

            val chStr = if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(cp) == Character.ENCLOSING_MARK.toInt()
            ) {
                "\u25CC" + String(Character.toChars(cp))
            } else {
                String(Character.toChars(cp))
            }
            canvas.drawText(chStr, cellRect.centerX(), cellRect.top + cellRect.height() * 0.60f, glyphPaint)

            codePaint.color = accentColor
            val hexCode = String.format(Locale.US, "U+%04X", cp)
            canvas.drawText(hexCode, cellRect.centerX(), cellRect.bottom - 14f, codePaint)
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pageSummaries.size - 1).coerceAtLeast(0))
        for (i in start until pageSummaries.size) {
            if (pageSummaries[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until start) {
            if (pageSummaries[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try { pfd.close() } catch (_: Exception) {}
        try { tempFontFile?.delete() } catch (_: Throwable) {}
        tempFontFile = null
    }
}
