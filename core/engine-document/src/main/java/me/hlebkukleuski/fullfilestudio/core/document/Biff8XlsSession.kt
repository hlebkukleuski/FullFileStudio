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
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource
import org.xmlpull.v1.XmlPullParser

class Biff8XlsSession(
    private val pfd: ParcelFileDescriptor,
    private val password: String? = null
) : SpreadsheetGridSession() {

    data class SheetGrid(
        val name: String,
        val cells: Map<Long, String>,
        val cellStyles: Map<Long, SheetCellStyle>,
        val colWidthsPx: FloatArray?,
        val maxRow: Int,
        val maxCol: Int,
        val mergedRegions: List<MergedCellRegion> = emptyList(),
        val charts: List<BiffChartRef> = emptyList(),
        val isChartSheet: Boolean = false,
        val sourceIndex: Int = -1,
        val rowHeightsPx: Map<Int, Float> = emptyMap(),
        val flags: Map<Long, Int> = emptyMap(),
        val formatColors: Map<Long, Int> = emptyMap(),
        val fills: Map<Long, Int> = emptyMap(),
        val rowStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val showGridLines: Boolean = true,
        val defaultRowHeightPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT,
        val defaultColWidthPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_WIDTH,
        val defaultFontName: String? = null,
        val defaultFontSize: Float = 11f,
        val conditional: List<CfBlock> = emptyList()
    )

    private class XlsSheetExtra(val charts: List<BiffChartRef>, val sourceIndex: Int)

    private val grids = mutableListOf<SheetGrid>()
    private val rawNumbers = ArrayList<Map<Long, Double>>()
    private val rawTexts = ArrayList<Map<Long, String>>()
    private var chartXti: List<IntArray> = emptyList()
    private var chartPalette: Map<Int, Int> = emptyMap()
    private var chartTheme: DmlTheme? = null
    private var chartFontSizes: List<Float> = emptyList()
    private val chartModels = HashMap<BiffChartRef, ChartModel?>()

    private val cellSource = object : BiffCellSource {
        override fun number(sheet: Int, row: Int, col: Int): Double? = rawNumbers.getOrNull(sheet)?.get(packCellCoord(row, col))
        override fun text(sheet: Int, row: Int, col: Int): String? = rawTexts.getOrNull(sheet)?.get(packCellCoord(row, col))
    }

    private fun chartModel(ref: BiffChartRef, sheetIndex: Int): ChartModel? = synchronized(chartModels) {
        chartModels.getOrPut(ref) {
            try {
                BiffChartParser.parse(ref, cellSource, chartXti, chartPalette, { idx ->
                    val k = if (idx >= 5) idx - 1 else idx
                    chartFontSizes.getOrNull(k)
                }, sheetIndex, chartTheme)
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val MAX_BIFF_ROWS = 20_000
        private const val MAX_BIFF_COLS = 512
        private fun packCellCoord(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)

        private val DEFAULT_BIFF8_PALETTE = mapOf(
            0x08 to Color.rgb(0, 0, 0),
            0x09 to Color.rgb(255, 255, 255),
            0x0A to Color.rgb(255, 0, 0),
            0x0B to Color.rgb(0, 255, 0),
            0x0C to Color.rgb(0, 0, 255),
            0x0D to Color.rgb(255, 255, 0),
            0x0E to Color.rgb(255, 0, 255),
            0x0F to Color.rgb(0, 255, 255),
            0x10 to Color.rgb(128, 0, 0),
            0x11 to Color.rgb(0, 128, 0),
            0x12 to Color.rgb(0, 0, 128),
            0x13 to Color.rgb(128, 128, 0),
            0x14 to Color.rgb(128, 0, 128),
            0x15 to Color.rgb(0, 128, 128),
            0x16 to Color.rgb(192, 192, 192),
            0x17 to Color.rgb(128, 128, 128),
            0x18 to Color.rgb(153, 153, 255),
            0x19 to Color.rgb(153, 51, 102),
            0x1A to Color.rgb(255, 255, 204),
            0x1B to Color.rgb(204, 255, 255),
            0x1C to Color.rgb(102, 0, 102),
            0x1D to Color.rgb(255, 128, 128),
            0x1E to Color.rgb(0, 102, 204),
            0x1F to Color.rgb(204, 204, 255),
            0x28 to Color.rgb(0, 204, 255),
            0x29 to Color.rgb(204, 255, 255),
            0x2A to Color.rgb(204, 255, 204),
            0x2B to Color.rgb(255, 255, 153),
            0x2C to Color.rgb(153, 204, 255),
            0x2D to Color.rgb(255, 153, 204),
            0x2E to Color.rgb(204, 153, 255),
            0x2F to Color.rgb(255, 204, 153),
            0x30 to Color.rgb(51, 102, 255),
            0x31 to Color.rgb(51, 204, 204),
            0x32 to Color.rgb(153, 204, 0),
            0x33 to Color.rgb(255, 204, 0),
            0x34 to Color.rgb(255, 153, 0),
            0x35 to Color.rgb(255, 102, 0),
            0x36 to Color.rgb(102, 102, 153),
            0x37 to Color.rgb(150, 150, 150)
        )
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 32 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "XLS file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    if (!tryParseZipSpreadsheetFallback(bytes)) {
                        lastErrorMsg = "No data sheets found in spreadsheet container."
                    }
                } else {
                    val cfb = Ole2CfbContainer(bytes)
                    if (!cfb.isValid) {
                        if (!tryParseTextOrXmlSpreadsheetFallback(bytes)) {
                            lastErrorMsg = "File is not a valid MS-XLS container (OLE2 CFB)."
                        }
                    } else if (OfficeCrypto.isOfficeIrmProtected(cfb)) {
                        lastErrorMsg = OfficeCrypto.OFFICE_IRM_DRM_ERROR
                    } else {
                        if (cfb.readStream("EncryptedPackage") != null || cfb.readStream("EncryptionInfo") != null) {
                            val decZip = OfficeCrypto.decryptOoxmlBytes(bytes, password, tryDefaultExcelPassword = true)
                            if (decZip != null) {
                                if (!tryParseZipSpreadsheetFallback(decZip)) {
                                    lastErrorMsg = "No data sheets found in spreadsheet container."
                                }
                            } else {
                                lastErrorMsg = if (password.isNullOrEmpty()) {
                                    "PASSWORD_REQUIRED: Microsoft Excel spreadsheet is encrypted."
                                } else {
                                    "PASSWORD_REQUIRED: Incorrect password for Microsoft Excel spreadsheet."
                                }
                            }
                        } else {
                            val wbStream = cfb.readStream("Workbook") ?: cfb.readStream("Book")
                            if (wbStream == null || wbStream.size < 16) {
                                lastErrorMsg = "Missing Workbook/Book stream in XLS container."
                            } else if (OfficeCrypto.isEncryptedXls(wbStream)) {
                                val decWb = OfficeCrypto.decryptXlsWithDefaultFallback(wbStream, password)
                                if (decWb != null) {
                                    parseBiff8Workbook(decWb)
                                } else {
                                    lastErrorMsg = if (password.isNullOrEmpty()) {
                                        "PASSWORD_REQUIRED: Microsoft Excel spreadsheet (.xls) is password protected."
                                    } else {
                                        "PASSWORD_REQUIRED: Incorrect password for Microsoft Excel spreadsheet (.xls)."
                                    }
                                }
                            } else {
                                parseBiff8Workbook(wbStream)
                            }
                        }
                    }
                }
            }
        } catch (sec: SecurityException) {
            lastErrorMsg = sec.message
        } catch (e: Exception) {
            lastErrorMsg = "XLS read error: ${e.localizedMessage}"
        }
        for (gr in grids) sheets.add(toModel(gr))
        grids.clear()
    }

    private fun toModel(gr: SheetGrid): SheetModel {
        val rowCount = if (gr.maxRow >= 0) gr.maxRow + 1 else 0
        val rowLists = ArrayList<ArrayList<String>>(rowCount)
        for (r in 0 until rowCount) rowLists.add(ArrayList())
        for ((key, v) in gr.cells) {
            val r = (key ushr 32).toInt()
            val c = (key and 0xFFFFFFFFL).toInt()
            if (r !in 0 until rowCount || c < 0) continue
            val row = rowLists[r]
            while (row.size <= c) row.add("")
            row[c] = v
        }
        if (rowCount > 0 && gr.maxCol >= 0) {
            val first = rowLists[0]
            while (first.size <= gr.maxCol) first.add("")
        }
        val flagRows = HashMap<Int, ByteArray>()
        for ((key, f) in gr.flags) rowFlags(flagRows, (key ushr 32).toInt(), (key and 0xFFFFFFFFL).toInt(), f)
        val colCount = gr.maxCol + 1
        val widths = if (colCount > 0) FloatArray(colCount) { c -> gr.colWidthsPx?.getOrNull(c) ?: gr.defaultColWidthPx } else null
        val heights = if (rowCount > 0) FloatArray(rowCount) { r -> gr.rowHeightsPx[r] ?: gr.defaultRowHeightPx } else null
        val model = SheetModel(
            name = gr.name,
            rows = rowLists,
            styles = gr.cellStyles,
            colWidthsPx = widths,
            mergedRegions = gr.mergedRegions,
            chartSheet = gr.isChartSheet && gr.charts.isNotEmpty(),
            rowHeightsPx = heights,
            flags = flagRows,
            formatColors = gr.formatColors,
            fills = gr.fills,
            rowStyles = gr.rowStyles,
            colStyles = gr.colStyles,
            showGridLines = gr.showGridLines,
            defaultFontName = gr.defaultFontName,
            defaultFontSize = gr.defaultFontSize,
            defaultRowHeightPx = gr.defaultRowHeightPx,
            defaultColWidthPx = gr.defaultColWidthPx,
            extra = XlsSheetExtra(gr.charts, gr.sourceIndex)
        )
        if (gr.conditional.isNotEmpty()) {
            val nums = rawNumbers.getOrNull(gr.sourceIndex).orEmpty()
            val cells = object : CfCells {
                override fun value(row: Int, col: Int, sheet: String?): Any? {
                    if (sheet != null && !sheet.equals(gr.name, true)) return null
                    nums[packCellCoord(row, col)]?.let { return it }
                    val t = model.text(row, col)
                    if (t.isEmpty()) return null
                    if ((model.flags(row, col) and SheetCellFlags.BOOL) != 0) return t.equals("TRUE", true)
                    return t
                }
            }
            try {
                SheetConditionalFormats.apply(gr.conditional, model.rowCount, model.colCount, cells, { r, c -> model.baseStyle(r, c) }, false, model.conditional) { v, code, _, _ ->
                    ExcelNumberFormat.formatCode(v, code).text
                }
            } catch (_: Exception) {}
        }
        return model
    }

    private fun readBiff12RecordHeader(bytes: ByteArray, startPos: Int): Triple<Int, Int, Int>? {
        var p = startPos
        if (p >= bytes.size) return null
        var recId = 0
        var shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recId = recId or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        var recLen = 0
        shift = 0
        for (i in 0 until 4) {
            if (p >= bytes.size) return null
            val b = bytes[p++].toInt() and 0xFF
            recLen = recLen or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (recLen < 0 || p + recLen > bytes.size) return null
        return Triple(recId, recLen, p)
    }

    private fun tryParseZipSpreadsheetFallback(zipBytes: ByteArray): Boolean {
        val rawEntries = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        val compContainerLen = zipBytes.size.coerceAtLeast(1)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16384)
            var total = 0L
            while (total < 64L * 1024L * 1024L) {
                val entry = try {
                    zis.nextEntry
                } catch (ze: java.util.zip.ZipException) {
                    if (ze.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                    }
                    null
                } catch (_: Throwable) { null } ?: break
                if (!entry.isDirectory && (
                            entry.name.endsWith(".xml", true) ||
                                    entry.name.endsWith(".rels", true) ||
                                    entry.name.endsWith(".bin", true)
                            )
                ) {
                    val bos = ByteArrayOutputStream()
                    var r: Int
                    while (zis.read(buf).also { r = it } > 0) {
                        total += r
                        if (total > 16L * 1024L * 1024L && (total / compContainerLen) > 150L) {
                            throw SecurityException("Превышен безопасный лимит распаковки (защита от Zip-бомб).")
                        }
                        if (total > 64L * 1024L * 1024L) break
                        bos.write(buf, 0, r)
                    }
                    rawEntries[entry.name.replace('\\', '/').trimStart('/')] = bos.toByteArray()
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
        val entries: Map<String, ByteArray> = if (OfficeCrypto.isEncryptedOdfManifest(rawEntries["META-INF/manifest.xml"])) {
            if (password.isNullOrEmpty()) {
                throw SecurityException("PASSWORD_REQUIRED: ODF spreadsheet (.ods) is encrypted or password protected.")
            }
            OfficeCrypto.decryptOdfEntries(rawEntries, password)
                ?: throw SecurityException("PASSWORD_REQUIRED: Incorrect password for ODF spreadsheet (.ods).")
        } else {
            rawEntries
        }

        if (entries.containsKey("xl/workbook.bin") ||
            entries.keys.any { it.lowercase(Locale.ROOT).startsWith("xl/worksheets/") && it.lowercase(Locale.ROOT).endsWith(".bin") }
        ) {
            val sharedStrings = ArrayList<String>()
            entries["xl/sharedStrings.bin"]?.let { sstBytes ->
                val bb = ByteBuffer.wrap(sstBytes).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos < sstBytes.size && sharedStrings.size < 200_000) {
                    val hdr = readBiff12RecordHeader(sstBytes, pos) ?: break
                    val (recId, recLen, dataStart) = hdr
                    if (recId == 0x0013 && recLen >= 5) {
                        val charCount = bb.getInt(dataStart + 1)
                        if (charCount in 0..32768 && dataStart + 5 + charCount * 2 <= dataStart + recLen) {
                            sharedStrings.add(String(sstBytes, dataStart + 5, charCount * 2, StandardCharsets.UTF_16LE))
                        }
                    }
                    pos = dataStart + recLen
                }
            }
            val sheetBinKeys = entries.keys.filter {
                val l = it.lowercase(Locale.ROOT)
                l.startsWith("xl/worksheets/sheet") && l.endsWith(".bin")
            }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

            for ((idx, key) in sheetBinKeys.withIndex()) {
                val bin = entries[key] ?: continue
                val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
                val map = HashMap<Long, String>()
                val merges = ArrayList<MergedCellRegion>()
                var maxR = -1
                var maxC = -1
                var curR = -1
                var pos = 0
                while (pos < bin.size && map.size < 500_000) {
                    val hdr = readBiff12RecordHeader(bin, pos) ?: break
                    val (recId, recLen, dataStart) = hdr
                    when (recId) {
                        0x0000 -> if (recLen >= 4) curR = bb.getInt(dataStart).coerceIn(0, MAX_BIFF_ROWS - 1)
                        0x0002 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val rk = bb.getInt(dataStart + 8)
                            val v = decodeRkValue(rk)
                            if (col in 0 until MAX_BIFF_COLS) {
                                map[packCellCoord(curR, col)] = formatNumber(v)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0005, 0x0008 -> if (recLen >= 16 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val d = bb.getDouble(dataStart + 8)
                            if (col in 0 until MAX_BIFF_COLS && d.isFinite()) {
                                map[packCellCoord(curR, col)] = formatNumber(d)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0006, 0x0009 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val cch = bb.getInt(dataStart + 8)
                            if (col in 0 until MAX_BIFF_COLS && cch in 0..32768 && dataStart + 12 + cch * 2 <= dataStart + recLen) {
                                map[packCellCoord(curR, col)] = String(bin, dataStart + 12, cch * 2, StandardCharsets.UTF_16LE)
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x0007 -> if (recLen >= 12 && curR >= 0) {
                            val col = bb.getInt(dataStart)
                            val isst = bb.getInt(dataStart + 8)
                            val str = sharedStrings.getOrNull(isst) ?: ""
                            if (col in 0 until MAX_BIFF_COLS && str.isNotEmpty()) {
                                map[packCellCoord(curR, col)] = str
                                if (curR > maxR) maxR = curR
                                if (col > maxC) maxC = col
                            }
                        }
                        0x00B0 -> if (recLen >= 16 && merges.size < 4096) {
                            val r0 = bb.getInt(dataStart).coerceIn(0, MAX_BIFF_ROWS - 1)
                            val r1 = bb.getInt(dataStart + 4).coerceIn(r0, MAX_BIFF_ROWS - 1)
                            val c0 = bb.getInt(dataStart + 8).coerceIn(0, MAX_BIFF_COLS - 1)
                            val c1 = bb.getInt(dataStart + 12).coerceIn(c0, MAX_BIFF_COLS - 1)
                            if (r1 > r0 || c1 > c0) merges.add(MergedCellRegion(r0, r1, c0, c1))
                        }
                    }
                    pos = dataStart + recLen
                }
                if (maxR >= 0 && maxC >= 0) {
                    grids.add(SheetGrid("Sheet ${idx + 1}", map, emptyMap(), null, maxR, maxC, merges))
                }
            }
            if (grids.isNotEmpty()) return true
        }

        val sharedStrings = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { ssBytes ->
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(ssBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var inSi = false
            var inT = false
            val sb = StringBuilder()
            while (ev != XmlPullParser.END_DOCUMENT && sharedStrings.size < 250_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (local == "si") { inSi = true; sb.setLength(0) }
                        else if (local == "t" && inSi) inT = true
                    }
                    XmlPullParser.TEXT -> if (inSi && inT && sb.length < 32768) sb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        if (local == "t") inT = false
                        else if (local == "si" && inSi) { sharedStrings.add(sb.toString()); inSi = false }
                    }
                }
                ev = parser.next()
            }
        }

        val worksheetKeys = entries.keys.filter {
            val l = it.lowercase(Locale.ROOT)
            (l.startsWith("xl/worksheets/sheet") && l.endsWith(".xml")) || l == "content.xml"
        }.sortedWith(compareBy({ it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

        for ((idx, key) in worksheetKeys.withIndex()) {
            val xml = entries[key] ?: continue
            val map = HashMap<Long, String>()
            val colWidths = HashMap<Int, Float>()
            val merges = ArrayList<MergedCellRegion>()
            var maxR = -1
            var maxC = -1
            val parser = EbookTextSession.createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(xml), StandardCharsets.UTF_8))
            var ev = parser.eventType
            var curR = -1
            var curC = -1
            var cellType = ""
            var inVal = false
            val valSb = StringBuilder()

            while (ev != XmlPullParser.END_DOCUMENT && map.size < 500_000) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (local) {
                            "col" -> {
                                val minCol = (parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                                val maxCol = (parser.getAttributeValue(null, "max")?.toIntOrNull() ?: (minCol + 1)) - 1
                                val wChars = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                if (wChars != null && wChars > 0f && minCol in 0 until MAX_BIFF_COLS) {
                                    val px = SpreadsheetCoordinateUtils.excelCharWidthToPixels(wChars)
                                    for (c in minCol..minOf(maxCol, minCol + 128, MAX_BIFF_COLS - 1)) {
                                        colWidths[c] = px
                                    }
                                }
                            }
                            "row", "table-row" -> {
                                val rAttr = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                curR = if (rAttr != null && rAttr > 0) rAttr - 1 else (curR + 1)
                                curC = -1
                            }
                            "c", "table-cell" -> {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                                val ref = parser.getAttributeValue(null, "r") ?: ""
                                val refCol = SpreadsheetCoordinateUtils.columnIndexFromCellRef(ref)
                                curC = if (refCol >= 0) refCol else (curC + 1)
                                valSb.setLength(0)
                            }
                            "v", "t", "p" -> inVal = true
                            "mergecell" -> {
                                val ref = parser.getAttributeValue(null, "ref") ?: ""
                                val parts = ref.split(':')
                                if (parts.size == 2 && merges.size < 4096) {
                                    val c0 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[0])
                                    val c1 = SpreadsheetCoordinateUtils.columnIndexFromCellRef(parts[1])
                                    val r0 = (parts[0].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    val r1 = (parts[1].filter { it.isDigit() }.toIntOrNull() ?: 0) - 1
                                    if (c0 in 0..c1 && r0 in 0..r1 && c1 < MAX_BIFF_COLS && r1 < MAX_BIFF_ROWS) {
                                        merges.add(MergedCellRegion(r0, r1, c0, c1))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inVal && valSb.length < 32768) valSb.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> {
                        when (local) {
                            "v", "t", "p" -> inVal = false
                            "c", "table-cell" -> {
                                val raw = valSb.toString().trim()
                                val resolved = if (cellType == "s") {
                                    sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                                } else raw
                                if (resolved.isNotEmpty() && curR in 0 until MAX_BIFF_ROWS && curC in 0 until MAX_BIFF_COLS) {
                                    map[packCellCoord(curR, curC)] = resolved
                                    if (curR > maxR) maxR = curR
                                    if (curC > maxC) maxC = curC
                                }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            if (maxR >= 0 && maxC >= 0) {
                val widthsArr = if (colWidths.isNotEmpty()) {
                    FloatArray(maxC + 1) { c -> colWidths[c] ?: SpreadsheetCoordinateUtils.BASE_CELL_WIDTH }
                } else null
                grids.add(SheetGrid("Sheet ${idx + 1}", map, emptyMap(), widthsArr, maxR, maxC, merges))
            }
        }
        return grids.isNotEmpty()
    }

    private fun tryParseTextOrXmlSpreadsheetFallback(bytes: ByteArray): Boolean {
        return try {
            val (cs, skip) = PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192))
            val text = String(bytes, skip, (bytes.size - skip).coerceAtLeast(0), cs).trim()
            if (text.isEmpty()) return false

            val map = HashMap<Long, String>()
            var maxR = -1
            var maxC = -1
            val lines = text.lines().filter { it.isNotBlank() }.take(10_000)
            val delimiter = when {
                lines.firstOrNull()?.contains('\t') == true -> '\t'
                lines.firstOrNull()?.contains(';') == true -> ';'
                else -> ','
            }
            for ((rIdx, line) in lines.withIndex()) {
                val parts = line.split(delimiter)
                for ((cIdx, rawCell) in parts.withIndex()) {
                    if (cIdx >= MAX_BIFF_COLS) break
                    val clean = rawCell.trim().removeSurrounding("\"")
                    if (clean.isNotEmpty()) {
                        map[packCellCoord(rIdx, cIdx)] = clean
                        if (rIdx > maxR) maxR = rIdx
                        if (cIdx > maxC) maxC = cIdx
                    }
                }
            }
            if (maxR >= 0 && maxC >= 0) {
                grids.add(SheetGrid("Sheet 1", map, emptyMap(), null, maxR, maxC))
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private data class BiffFontInfo(
        val colorIndex: Int,
        val isBold: Boolean,
        val isItalic: Boolean,
        val heightTwips: Int = 200,
        val name: String? = null,
        val underline: Int = 0,
        val strike: Boolean = false,
        val script: Int = 0
    )

    private data class BiffXfInfo(
        val fontIndex: Int,
        val numFmtIndex: Int,
        val fillPattern: Int,
        val fillFgColorIndex: Int,
        val fillBgColorIndex: Int = 0x41,
        val hAlign: Int = 0,
        val vAlign: Int = 2,
        val wrap: Boolean = false,
        val rotation: Int = 0,
        val indent: Int = 0,
        val shrink: Boolean = false,
        val borderStyles: Int = 0,
        val borderColorsLR: Int = 0,
        val borderColorsTB: Int = 0
    )

    private fun parseBiff8Workbook(wb: ByteArray) {
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val sheetNames = mutableListOf<String>()
        val sst = mutableListOf<String>()
        val formatMap = HashMap<Int, String>()
        val paletteMap = HashMap<Int, Int>(DEFAULT_BIFF8_PALETTE)
        paletteMap[0x00] = Color.BLACK
        paletteMap[0x01] = Color.WHITE
        paletteMap[0x02] = Color.rgb(255, 0, 0)
        paletteMap[0x03] = Color.rgb(0, 255, 0)
        paletteMap[0x04] = Color.rgb(0, 0, 255)
        paletteMap[0x05] = Color.rgb(255, 255, 0)
        paletteMap[0x06] = Color.rgb(255, 0, 255)
        paletteMap[0x07] = Color.rgb(0, 255, 255)
        val fonts = ArrayList<BiffFontInfo>()
        val xfRecords = ArrayList<BiffXfInfo>()
        val xfStyleCache = HashMap<Int, SheetCellStyle?>()
        val xfExt = HashMap<Int, HashMap<Int, IntArray>>()
        var themeColors: IntArray? = null
        var use1904DateSystem = false

        var pos = 0
        var bofDepth = 0
        var currentSheetIdx = -1
        var lastFormulaRow = -1
        var lastFormulaCol = -1
        var lastFormulaXf = -1
        val sheetMaps = mutableListOf<MutableMap<Long, String>>()
        val sheetStyleMaps = mutableListOf<MutableMap<Long, SheetCellStyle>>()
        val sheetColWidths = mutableListOf<MutableMap<Int, Float>>()
        val sheetMaxRows = mutableListOf<Int>()
        val sheetMaxCols = mutableListOf<Int>()
        val sheetMergedRegions = mutableListOf<MutableList<MergedCellRegion>>()
        val sheetNumbers = mutableListOf<MutableMap<Long, Double>>()
        val sheetCharts = mutableListOf<MutableList<BiffChartRef>>()
        val sheetRowHeights = mutableListOf<MutableMap<Int, Float>>()
        val sheetFlags = mutableListOf<MutableMap<Long, Int>>()
        val sheetFmtColors = mutableListOf<MutableMap<Long, Int>>()
        val sheetFills = mutableListOf<MutableMap<Long, Int>>()
        val sheetRowStyles = mutableListOf<MutableMap<Int, SheetCellStyle>>()
        val sheetColStyles = mutableListOf<MutableMap<Int, SheetCellStyle>>()
        val sheetGridLines = mutableListOf<Boolean>()
        val sheetDefaultRow = mutableListOf<Float>()
        val sheetDefaultCol = mutableListOf<Float>()
        val sheetCf = mutableListOf<MutableList<Pair<List<IntArray>, MutableList<CfRule>>>>()
        val sheetCfIds = mutableListOf<HashMap<Int, Int>>()
        var cfTop = 0
        var cfLeft = 0
        val chartSheetFlags = HashSet<Int>()
        val xtiList = ArrayList<IntArray>()
        var chartStart = -1
        var chartDepth = -1
        var chartOwner = -1
        var lastDrawStart = -1
        var lastDrawEnd = -1

        fun ensureSheetLists(targetIdx: Int) {
            if (targetIdx > 64) return
            while (sheetNumbers.size <= targetIdx) { sheetNumbers.add(HashMap()); sheetCharts.add(ArrayList()) }
            while (sheetMaps.size <= targetIdx) {
                sheetMaps.add(HashMap())
                sheetStyleMaps.add(HashMap())
                sheetColWidths.add(HashMap())
                sheetMaxRows.add(-1)
                sheetMaxCols.add(-1)
                sheetMergedRegions.add(ArrayList())
                sheetRowHeights.add(HashMap())
                sheetFlags.add(HashMap())
                sheetFmtColors.add(HashMap())
                sheetFills.add(HashMap())
                sheetRowStyles.add(HashMap())
                sheetColStyles.add(HashMap())
                sheetGridLines.add(true)
                sheetDefaultRow.add(-1f)
                sheetDefaultCol.add(-1f)
                sheetCf.add(ArrayList())
                sheetCfIds.add(HashMap())
            }
        }

        fun color(idx: Int): Int? = if (idx in 0..0x3F) paletteMap[idx] else null

        val supBookNames = ArrayList<ArrayList<String>>()

        fun externName(ixti: Int, iname: Int): String? {
            val sb = xtiList.getOrNull(ixti)?.get(0) ?: return null
            return supBookNames.getOrNull(sb)?.getOrNull(iname - 1)
        }

        val themeBufRef = java.io.ByteArrayOutputStream()
        var themeVersionRef = -1

        fun fullColor(e: IntArray): Int? {
            val base = when (e[0]) {
                1 -> color(e[2])
                2 -> Color.rgb(e[2] and 0xFF, (e[2] ushr 8) and 0xFF, (e[2] ushr 16) and 0xFF)
                3 -> {
                    val tc = themeColors ?: run {
                        val t = BiffChartParser.theme(themeVersionRef, themeBufRef.toByteArray()) ?: BiffChartParser.theme(124226, ByteArray(0))
                        val keys = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
                        IntArray(keys.size) { t?.colors?.get(keys[it]) ?: Color.BLACK }.also { themeColors = it }
                    }
                    tc.getOrNull(e[2])
                }
                else -> null
            } ?: return null
            val tint = e[1] / 32767.0
            if (abs(tint) < 1e-4) return base
            val hsl = DmlColors.rgbToHsl(base)
            val l = if (tint < 0) hsl[2] * (1.0 + tint) else hsl[2] * (1.0 - tint) + tint
            return DmlColors.hslToRgb(hsl[0], hsl[1], l.toFloat())
        }

        fun extColor(xf: Int, type: Int): Int? = xfExt[xf]?.get(type)?.let { fullColor(it) }

        fun border(styleCode: Int, colorIdx: Int, ext: Int? = null): SheetBorder? {
            if (styleCode == 0) return null
            return SheetBorder(styleCode.coerceIn(1, 13), ext ?: color(colorIdx))
        }

        fun fontFor(idx: Int): BiffFontInfo? = fonts.getOrNull(if (idx >= 4) idx - 1 else idx)

        fun resolveBiffStyle(xfIdx: Int): SheetCellStyle? = xfStyleCache.getOrPut(xfIdx) {
            val xf = xfRecords.getOrNull(xfIdx) ?: return@getOrPut null
            val font = fontFor(xf.fontIndex)
            val base = fonts.firstOrNull()
            val fill = SheetCellStyle.biffPattern(xf.fillPattern, extColor(xfIdx, 4) ?: color(xf.fillFgColorIndex), extColor(xfIdx, 5) ?: color(xf.fillBgColorIndex))
            val txtCol = extColor(xfIdx, 13) ?: if (font != null && font.colorIndex in 0..0x3F) color(font.colorIndex) else null
            val bs = xf.borderStyles
            val diagFlags = (xf.borderColorsLR ushr 14) and 0x3
            val diagStyle = (xf.borderColorsTB ushr 21) and 0xF
            val diagColor = (xf.borderColorsTB ushr 14) and 0x7F
            val st = SheetCellStyle(
                fill = fill?.bg,
                pattern = fill?.pattern ?: 0,
                patternColor = fill?.fg,
                textColor = txtCol,
                bold = font?.isBold == true,
                italic = font?.isItalic == true,
                underline = font?.underline ?: 0,
                strike = font?.strike == true,
                fontSize = font?.let { it.heightTwips / 20f } ?: 0f,
                fontName = font?.name ?: base?.name,
                script = font?.script ?: 0,
                hAlign = when (xf.hAlign) {
                    1 -> SheetCellStyle.H_LEFT
                    2 -> SheetCellStyle.H_CENTER
                    3 -> SheetCellStyle.H_RIGHT
                    4 -> SheetCellStyle.H_FILL
                    5 -> SheetCellStyle.H_JUSTIFY
                    6 -> SheetCellStyle.H_CENTER_CONTINUOUS
                    7 -> SheetCellStyle.H_DISTRIBUTED
                    else -> SheetCellStyle.H_GENERAL
                },
                vAlign = when (xf.vAlign) {
                    0 -> SheetCellStyle.V_TOP
                    1 -> SheetCellStyle.V_CENTER
                    3 -> SheetCellStyle.V_JUSTIFY
                    4 -> SheetCellStyle.V_DISTRIBUTED
                    else -> SheetCellStyle.V_BOTTOM
                },
                wrap = xf.wrap,
                indent = xf.indent,
                rotation = xf.rotation,
                shrink = xf.shrink,
                left = border(bs and 0xF, xf.borderColorsLR and 0x7F, extColor(xfIdx, 9)),
                right = border((bs ushr 4) and 0xF, (xf.borderColorsLR ushr 7) and 0x7F, extColor(xfIdx, 10)),
                top = border((bs ushr 8) and 0xF, xf.borderColorsTB and 0x7F, extColor(xfIdx, 7)),
                bottom = border((bs ushr 12) and 0xF, (xf.borderColorsTB ushr 7) and 0x7F, extColor(xfIdx, 8)),
                diagonalDown = if ((diagFlags and 1) != 0) border(diagStyle, diagColor, extColor(xfIdx, 11)) else null,
                diagonalUp = if ((diagFlags and 2) != 0) border(diagStyle, diagColor, extColor(xfIdx, 11)) else null
            )
            if (st == SheetCellStyle(fontSize = st.fontSize, fontName = st.fontName) && st.fontSize == (base?.heightTwips ?: 200) / 20f && st.fontName == base?.name) null else st
        }

        fun putCell(row: Int, col: Int, value: String, xfIdx: Int = -1, kind: Int = 0, fmtColor: Int? = null, fill: Int = -1) {
            if (bofDepth > 1) return
            if (row !in 0 until MAX_BIFF_ROWS || col !in 0 until MAX_BIFF_COLS) return
            val targetIdx = currentSheetIdx.coerceIn(0, 64)
            ensureSheetLists(targetIdx)
            val key = packCellCoord(row, col)
            if (value.isNotBlank() && sheetMaps[targetIdx].size < 500_000) {
                sheetMaps[targetIdx][key] = value.trimEnd('\r', '\n')
                if (row > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = row
                if (col > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = col
                if (kind != 0) sheetFlags[targetIdx][key] = kind
                if (fmtColor != null) sheetFmtColors[targetIdx][key] = fmtColor
                if (fill >= 0) sheetFills[targetIdx][key] = fill
            }
            if (xfIdx >= 0 && sheetStyleMaps[targetIdx].size < 250_000) {
                resolveBiffStyle(xfIdx)?.let { style ->
                    sheetStyleMaps[targetIdx][key] = style
                    if (style.fill != null || style.hasBorders || value.isNotBlank()) {
                        if (row > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = row
                        if (col > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = col
                    }
                }
            }
        }

        fun putNumber(row: Int, col: Int, d: Double) {
            if (bofDepth > 1 || !d.isFinite()) return
            if (row !in 0 until MAX_BIFF_ROWS || col !in 0 until MAX_BIFF_COLS) return
            val targetIdx = currentSheetIdx.coerceIn(0, 64)
            ensureSheetLists(targetIdx)
            if (sheetNumbers[targetIdx].size < 500_000) sheetNumbers[targetIdx][packCellCoord(row, col)] = d
        }

        fun putNumericCell(row: Int, col: Int, d: Double, xfIdx: Int) {
            putNumber(row, col, d)
            val fmtIdx = xfRecords.getOrNull(xfIdx)?.numFmtIndex ?: 0
            val res = ExcelNumberFormat.format(d, fmtIdx, formatMap[fmtIdx], use1904DateSystem)
            val kind = SheetCellFlags.NUMBER or (if (res.general) SheetCellFlags.GENERAL else 0)
            putCell(row, col, res.text, xfIdx, kind, res.color, if (res.fillAt >= 0) (res.fillAt shl 16) or res.fillChar.code else -1)
            if (res.text.isBlank()) {
                val targetIdx = currentSheetIdx.coerceIn(0, 64)
                if (bofDepth <= 1 && targetIdx < sheetMaxRows.size) {
                    if (row > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = row
                    if (col > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = col
                }
            }
        }

        fun putTextCell(row: Int, col: Int, text: String, xfIdx: Int) {
            val fmtIdx = xfRecords.getOrNull(xfIdx)?.numFmtIndex ?: 0
            val code = formatMap[fmtIdx]
            if (code != null && (code.contains('@') || code.count { it == ';' } >= 3)) {
                val res = ExcelNumberFormat.formatText(text, fmtIdx, code)
                putCell(row, col, res.text, xfIdx, 0, res.color, if (res.fillAt >= 0) (res.fillAt shl 16) or res.fillChar.code else -1)
            } else {
                putCell(row, col, text, xfIdx)
            }
        }

        fun errorText(code: Int): String = when (code) {
            0x00 -> "#NULL!"
            0x07 -> "#DIV/0!"
            0x0F -> "#VALUE!"
            0x17 -> "#REF!"
            0x1D -> "#NAME?"
            0x24 -> "#NUM!"
            0x2A -> "#N/A"
            0x2B -> "#GETTING_DATA"
            else -> "#ERR!"
        }

        val themeBuf = java.io.ByteArrayOutputStream()
        var themeVersion = -1
        var themeOpen = false
        while (pos + 4 <= wb.size) {
            val recId = bb.getShort(pos).toInt() and 0xFFFF
            if (recId != 0x087F && recId != 0x0896) themeOpen = false
            val recLen = bb.getShort(pos + 2).toInt() and 0xFFFF
            val dataStart = pos + 4
            if (dataStart + recLen > wb.size) break

            when (recId) {
                0x002F -> {
                    lastErrorMsg = "PASSWORD_REQUIRED: Microsoft Excel spreadsheet (.xls) is password protected."
                    return
                }
                0x0022 -> {
                    if (recLen >= 2) {
                        use1904DateSystem = (bb.getShort(dataStart).toInt() and 0xFFFF) == 1
                    }
                }
                0x0031 -> {
                    if (recLen >= 14 && fonts.size < 4096) {
                        val grbit = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val icv = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val bls = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                        val sss = bb.getShort(dataStart + 8).toInt() and 0xFFFF
                        val uls = wb[dataStart + 10].toInt() and 0xFF
                        var fontName: String? = null
                        if (recLen >= 16) {
                            val cch = wb[dataStart + 14].toInt() and 0xFF
                            val hi = (wb[dataStart + 15].toInt() and 0x01) != 0
                            val start = dataStart + 16
                            fontName = if (hi && start + cch * 2 <= dataStart + recLen) String(wb, start, cch * 2, StandardCharsets.UTF_16LE)
                            else if (!hi && start + cch <= dataStart + recLen) String(wb, start, cch, StandardCharsets.ISO_8859_1)
                            else null
                        }
                        fonts.add(
                            BiffFontInfo(
                                colorIndex = icv,
                                isBold = bls >= 0x02BC || (grbit and 0x01) != 0,
                                isItalic = (grbit and 0x02) != 0,
                                heightTwips = bb.getShort(dataStart).toInt() and 0xFFFF,
                                name = fontName?.takeIf { it.isNotBlank() },
                                underline = when (uls) { 0x01, 0x21 -> 1; 0x02, 0x22 -> 2; else -> 0 },
                                strike = (grbit and 0x08) != 0,
                                script = if (sss in 1..2) sss else 0
                            )
                        )
                    }
                }
                0x0092 -> {
                    if (recLen >= 2) {
                        val count = (bb.getShort(dataStart).toInt() and 0xFFFF).coerceIn(0, 56)
                        for (i in 0 until count) {
                            val cPos = dataStart + 2 + i * 4
                            if (cPos + 4 > dataStart + recLen) break
                            val r = wb[cPos].toInt() and 0xFF
                            val g = wb[cPos + 1].toInt() and 0xFF
                            val b = wb[cPos + 2].toInt() and 0xFF
                            paletteMap[0x08 + i] = Color.rgb(r, g, b)
                        }
                    }
                }
                0x041E -> {
                    if (recLen >= 5) {
                        val fmtIndex = bb.getShort(dataStart).toInt() and 0xFFFF
                        val fmtCode = readBiff8UnicodeString(wb, dataStart + 2, dataStart + recLen)
                        if (fmtCode.isNotEmpty()) {
                            formatMap[fmtIndex] = fmtCode
                        }
                    }
                }
                0x00E0 -> {
                    if (recLen >= 4 && xfRecords.size < 16384) {
                        val fontIdx = bb.getShort(dataStart).toInt() and 0xFFFF
                        val fmtIdx = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        var fillPat = 0
                        var fillFgIcv = 0x40
                        var fillBgIcv = 0x41
                        var align = 0
                        var align2 = 0
                        var borders = 0
                        var colorsLR = 0
                        var colorsTB = 0
                        if (recLen >= 20) {
                            align = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                            align2 = bb.getShort(dataStart + 8).toInt() and 0xFFFF
                            borders = bb.getShort(dataStart + 10).toInt() and 0xFFFF
                            colorsLR = bb.getShort(dataStart + 12).toInt() and 0xFFFF
                            val borderAndPattern = bb.getInt(dataStart + 14)
                            colorsTB = borderAndPattern
                            fillPat = (borderAndPattern ushr 26) and 0x3F
                            val icvCombined = bb.getShort(dataStart + 18).toInt() and 0xFFFF
                            fillFgIcv = icvCombined and 0x7F
                            fillBgIcv = (icvCombined ushr 7) and 0x7F
                        }
                        xfRecords.add(
                            BiffXfInfo(
                                fontIdx, fmtIdx, fillPat, fillFgIcv, fillBgIcv,
                                hAlign = align and 0x7,
                                vAlign = (align ushr 4) and 0x7,
                                wrap = (align and 0x8) != 0,
                                rotation = (align ushr 8) and 0xFF,
                                indent = align2 and 0xF,
                                shrink = (align2 and 0x10) != 0,
                                borderStyles = borders,
                                borderColorsLR = colorsLR,
                                borderColorsTB = colorsTB
                            )
                        )
                    }
                }
                0x007D -> {
                    if (recLen >= 6 && bofDepth == 1) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val colFirst = (bb.getShort(dataStart).toInt() and 0xFFFF).coerceIn(0, MAX_BIFF_COLS - 1)
                        val colLast = (bb.getShort(dataStart + 2).toInt() and 0xFFFF).coerceIn(colFirst, MAX_BIFF_COLS - 1)
                        val colUnits256 = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val colXf = if (recLen >= 8) bb.getShort(dataStart + 6).toInt() and 0xFFFF else -1
                        val hidden = recLen >= 10 && (bb.getShort(dataStart + 8).toInt() and 0x0001) != 0
                        val pxWidth = if (hidden) 0f else SpreadsheetCoordinateUtils.excelCharWidthToPixels(colUnits256 / 256f)
                        val colStyle = if (colXf > 15) resolveBiffStyle(colXf) else null
                        for (c in colFirst..minOf(colLast, colFirst + 255)) {
                            sheetColWidths[targetIdx][c] = pxWidth
                            if (colStyle != null) sheetColStyles[targetIdx][c] = colStyle
                        }
                    }
                }
                0x0809 -> {
                    if (recLen >= 4) {
                        val substreamType = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        if (substreamType != 0x0005) {
                            if (bofDepth == 0 && currentSheetIdx < 64) {
                                currentSheetIdx++
                                ensureSheetLists(currentSheetIdx)
                            }
                            if (substreamType == 0x0020 && chartStart < 0) {
                                chartStart = pos
                                chartDepth = bofDepth
                                chartOwner = currentSheetIdx.coerceIn(0, 64)
                                if (bofDepth == 0) chartSheetFlags.add(chartOwner)
                            }
                            if (bofDepth == 0) { lastDrawStart = -1; lastDrawEnd = -1 }
                            bofDepth++
                        }
                    }
                }
                0x000A -> {
                    if (bofDepth > 0) {
                        bofDepth--
                        if (chartStart >= 0 && bofDepth == chartDepth) {
                            ensureSheetLists(chartOwner)
                            val anchor = if (chartDepth == 1 && lastDrawStart >= 0) BiffChartParser.anchorFromDrawing(wb, lastDrawStart, lastDrawEnd) else null
                            if (sheetCharts[chartOwner].size < 256) sheetCharts[chartOwner].add(BiffChartRef(wb.copyOfRange(chartStart, dataStart + recLen), anchor, chartDepth == 0))
                            if (anchor != null) {
                                if (anchor.rwB > sheetMaxRows[chartOwner]) sheetMaxRows[chartOwner] = anchor.rwB.coerceAtMost(MAX_BIFF_ROWS - 1)
                                if (anchor.colR > sheetMaxCols[chartOwner]) sheetMaxCols[chartOwner] = anchor.colR.coerceAtMost(MAX_BIFF_COLS - 1)
                            }
                            chartStart = -1
                            chartDepth = -1
                        }
                    }
                }
                0x00EC -> if (bofDepth == 1) {
                    lastDrawStart = dataStart
                    lastDrawEnd = dataStart + recLen
                }
                0x0896 -> if (bofDepth == 0 && recLen >= 16) {
                    themeVersion = bb.getInt(dataStart + 12)
                    themeVersionRef = themeVersion
                    if (recLen > 16) { themeBuf.write(wb, dataStart + 16, recLen - 16); themeBufRef.write(wb, dataStart + 16, recLen - 16) }
                    themeOpen = true
                    themeColors = null
                }
                0x087F -> if (themeOpen && recLen > 12 && themeBuf.size() < 8 * 1024 * 1024) {
                    themeBuf.write(wb, dataStart + 12, recLen - 12)
                    themeBufRef.write(wb, dataStart + 12, recLen - 12)
                    themeColors = null
                }
                0x087D -> if (bofDepth == 0 && recLen >= 20) {
                    val ixfe = bb.getShort(dataStart + 14).toInt() and 0xFFFF
                    val cexts = bb.getShort(dataStart + 18).toInt() and 0xFFFF
                    var q = dataStart + 20
                    val map = xfExt.getOrPut(ixfe) { HashMap() }
                    for (k in 0 until cexts.coerceAtMost(32)) {
                        if (q + 4 > dataStart + recLen) break
                        val type = bb.getShort(q).toInt() and 0xFFFF
                        val cb = bb.getShort(q + 2).toInt() and 0xFFFF
                        if (cb < 4 || q + cb > dataStart + recLen) break
                        if (type in setOf(4, 5, 7, 8, 9, 10, 11, 13) && cb >= 12) {
                            map[type] = intArrayOf(bb.getShort(q + 4).toInt() and 0xFFFF, bb.getShort(q + 6).toInt(), bb.getInt(q + 8))
                        }
                        q += cb
                    }
                    xfStyleCache.remove(ixfe)
                }
                0x01AE -> if (bofDepth == 0) supBookNames.add(ArrayList())
                0x0023 -> if (bofDepth == 0 && recLen >= 8 && supBookNames.isNotEmpty()) {
                    val cch = wb[dataStart + 6].toInt() and 0xFF
                    val hi = (wb[dataStart + 7].toInt() and 1) != 0
                    val st = dataStart + 8
                    val nm = if (hi && st + cch * 2 <= dataStart + recLen) String(wb, st, cch * 2, StandardCharsets.UTF_16LE)
                    else if (!hi && st + cch <= dataStart + recLen) String(wb, st, cch, StandardCharsets.ISO_8859_1) else ""
                    supBookNames.last().add(nm)
                }
                0x0017 -> if (bofDepth == 0 && recLen >= 2) {
                    val n = bb.getShort(dataStart).toInt() and 0xFFFF
                    for (k in 0 until n) {
                        val q = dataStart + 2 + k * 6
                        if (q + 6 > dataStart + recLen || xtiList.size > 4096) break
                        xtiList.add(intArrayOf(bb.getShort(q).toInt() and 0xFFFF, bb.getShort(q + 2).toInt() and 0xFFFF, bb.getShort(q + 4).toInt() and 0xFFFF))
                    }
                }
                0x0085 -> {
                    if (recLen >= 8 && sheetNames.size < 64) {
                        val nameLen = wb[dataStart + 6].toInt() and 0xFF
                        val flags = wb[dataStart + 7].toInt() and 0xFF
                        val isUtf16 = (flags and 0x01) != 0
                        val strStart = dataStart + 8
                        val sName = if (isUtf16 && strStart + nameLen * 2 <= dataStart + recLen) {
                            String(wb, strStart, nameLen * 2, StandardCharsets.UTF_16LE)
                        } else if (!isUtf16 && strStart + nameLen <= dataStart + recLen) {
                            String(wb, strStart, nameLen, PlainTextSession.CP1251)
                        } else "Sheet${sheetNames.size + 1}"
                        sheetNames.add(sName)
                    }
                }
                0x00FC -> {
                    parseBiff8Sst(wb, pos, sst)
                }
                0x0201 -> {
                    if (recLen >= 6) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        putCell(row, col, "", xfIdx)
                    }
                }
                0x00BE -> {
                    if (recLen >= 6) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val firstCol = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val lastCol = bb.getShort(dataStart + recLen - 2).toInt() and 0xFFFF
                        var off = dataStart + 4
                        var c = firstCol
                        while (c <= lastCol && off + 2 <= dataStart + recLen - 2) {
                            putCell(row, c, "", bb.getShort(off).toInt() and 0xFFFF)
                            off += 2
                            c++
                        }
                    }
                }
                0x0208 -> {
                    if (recLen >= 16 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val miyRw = bb.getShort(dataStart + 6).toInt() and 0xFFFF
                        val grbit = bb.getShort(dataStart + 12).toInt() and 0xFFFF
                        val ixfe = bb.getShort(dataStart + 14).toInt() and 0x0FFF
                        if (row < MAX_BIFF_ROWS) {
                            if ((grbit and 0x20) != 0) sheetRowHeights[targetIdx][row] = 0f
                            else if ((miyRw and 0x8000) == 0 && (miyRw and 0x7FFF) > 0) sheetRowHeights[targetIdx][row] = (miyRw and 0x7FFF) / 20f * PX_PER_PT
                            if ((grbit and 0x80) != 0) resolveBiffStyle(ixfe)?.let { sheetRowStyles[targetIdx][row] = it }
                        }
                    }
                }
                0x0225 -> {
                    if (recLen >= 4 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val miyRw = bb.getShort(dataStart + 2).toInt() and 0x7FFF
                        if (miyRw > 0) sheetDefaultRow[targetIdx] = miyRw / 20f * PX_PER_PT
                    }
                }
                0x0055 -> {
                    if (recLen >= 2 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val chars = bb.getShort(dataStart).toInt() and 0xFFFF
                        if (chars in 1..255) sheetDefaultCol[targetIdx] = defaultExcelColumnWidthPx(chars.toFloat())
                    }
                }
                0x01B0 -> {
                    if (recLen >= 14 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val cref = bb.getShort(dataStart + 12).toInt() and 0xFFFF
                        val ranges = ArrayList<IntArray>()
                        var q = dataStart + 14
                        for (k in 0 until cref.coerceAtMost(512)) {
                            if (q + 8 > dataStart + recLen) break
                            ranges.add(intArrayOf(bb.getShort(q).toInt() and 0xFFFF, bb.getShort(q + 2).toInt() and 0xFFFF, bb.getShort(q + 4).toInt() and 0xFFFF, bb.getShort(q + 6).toInt() and 0xFFFF))
                            q += 8
                        }
                        cfTop = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        cfLeft = bb.getShort(dataStart + 8).toInt() and 0xFFFF
                        if (ranges.isNotEmpty() && sheetCf[targetIdx].size < 1024) {
                            sheetCfIds[targetIdx][(bb.getShort(dataStart + 2).toInt() and 0xFFFF) ushr 1] = sheetCf[targetIdx].size
                            sheetCf[targetIdx].add(ranges to ArrayList())
                        }
                    }
                }
                0x01B1 -> {
                    if (recLen >= 12 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val block = sheetCf[targetIdx].lastOrNull()
                        if (block != null && block.second.size < 64) {
                            val ct = wb[dataStart].toInt() and 0xFF
                            val cp = wb[dataStart + 1].toInt() and 0xFF
                            val cce1 = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                            val cce2 = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                            val dx = BiffCf.dxfn(wb, dataStart + 6, dataStart + recLen, ::color, formatMap)
                            if (dx != null) {
                                val f1Start = dx.second
                                val f1 = BiffCf.formula(wb, f1Start, f1Start + cce1, cfTop, cfLeft, ::externName)
                                val f2 = if (cce2 > 0) BiffCf.formula(wb, f1Start + cce1, f1Start + cce1 + cce2, cfTop, cfLeft, ::externName) else null
                                val prio = block.second.size + sheetCf[targetIdx].sumOf { it.second.size }
                                if (f1 != null && (cce2 == 0 || f2 != null)) {
                                    val rule = if (ct == 2) CfRule("expression", prio, formulas = listOf(f1), style = dx.first)
                                    else CfRule(
                                        "cellIs", prio,
                                        operator = when (cp) {
                                            1 -> "between"; 2 -> "notBetween"; 3 -> "equal"; 4 -> "notEqual"
                                            5 -> "greaterThan"; 6 -> "lessThan"; 7 -> "greaterThanOrEqual"; else -> "lessThanOrEqual"
                                        },
                                        formulas = listOfNotNull(f1, f2), style = dx.first
                                    )
                                    block.second.add(rule)
                                }
                            }
                        }
                    }
                }
                0x0879 -> {
                    if (recLen >= 26 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val base = dataStart + 12
                        val cref = bb.getShort(base + 12).toInt() and 0xFFFF
                        val ranges = ArrayList<IntArray>()
                        var q = base + 14
                        for (k in 0 until cref.coerceAtMost(512)) {
                            if (q + 8 > dataStart + recLen) break
                            ranges.add(intArrayOf(bb.getShort(q).toInt() and 0xFFFF, bb.getShort(q + 2).toInt() and 0xFFFF, bb.getShort(q + 4).toInt() and 0xFFFF, bb.getShort(q + 6).toInt() and 0xFFFF))
                            q += 8
                        }
                        cfTop = bb.getShort(base + 4).toInt() and 0xFFFF
                        cfLeft = bb.getShort(base + 8).toInt() and 0xFFFF
                        if (ranges.isNotEmpty() && sheetCf[targetIdx].size < 1024) {
                            sheetCfIds[targetIdx][(bb.getShort(base + 2).toInt() and 0xFFFF) ushr 1] = sheetCf[targetIdx].size
                            sheetCf[targetIdx].add(ranges to ArrayList())
                        }
                    }
                }
                0x087A -> {
                    if (recLen >= 24 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val block = sheetCf[targetIdx].lastOrNull()
                        if (block != null && block.second.size < 64) {
                            BiffCf.cf12(wb, dataStart + 12, dataStart + recLen, cfTop, cfLeft, ::color, ::fullColor, formatMap, ::externName)?.let { block.second.add(it) }
                        }
                    }
                }
                0x087B -> {
                    if (recLen >= 26 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val isCf12 = bb.getInt(dataStart + 12) != 0
                        val nId = bb.getShort(dataStart + 16).toInt() and 0xFFFF
                        val blockIdx = sheetCfIds[targetIdx][nId]
                        if (!isCf12 && blockIdx != null) {
                            val rules = sheetCf[targetIdx][blockIdx].second
                            val icf = bb.getShort(dataStart + 18).toInt() and 0xFFFF
                            val prio = bb.getShort(dataStart + 22).toInt() and 0xFFFF
                            val flags = wb[dataStart + 24].toInt() and 0xFF
                            val hasDxf = recLen >= 30 && wb[dataStart + 25].toInt() != 0
                            rules.getOrNull(icf)?.let { r ->
                                var style = r.style
                                if (hasDxf) {
                                    val cb = bb.getInt(dataStart + 26)
                                    val dxEnd = (dataStart + 30 + cb).coerceAtMost(dataStart + recLen)
                                    BiffCf.dxfn(wb, dataStart + 30, dxEnd, ::color, formatMap)?.let { (st, after) ->
                                        style = BiffCf.withExt(st, BiffCf.extProps(wb, after, dxEnd), ::fullColor)
                                    }
                                }
                                rules[icf] = r.copy(priority = prio, stopIfTrue = (flags and 0x0002) != 0, style = style)
                            }
                        }
                    }
                }
                0x023E -> {
                    if (recLen >= 2 && bofDepth == 1 && chartStart < 0) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val grbit = bb.getShort(dataStart).toInt() and 0xFFFF
                        sheetGridLines[targetIdx] = (grbit and 0x0002) != 0
                    }
                }
                0x00FD -> {
                    if (recLen >= 10) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val sstIdx = bb.getInt(dataStart + 6)
                        if (sstIdx in sst.indices) {
                            putTextCell(row, col, sst[sstIdx], xfIdx)
                        } else {
                            putCell(row, col, "", xfIdx)
                        }
                    }
                }
                0x0204, 0x00D6 -> {
                    if (recLen >= 8) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val str = readBiff8UnicodeString(wb, dataStart + 6, dataStart + recLen)
                        putTextCell(row, col, str, xfIdx)
                    }
                }
                0x0203 -> {
                    if (recLen >= 14) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val d = Double.fromBits(bb.getLong(dataStart + 6))
                        putNumericCell(row, col, d, xfIdx)
                    }
                }
                0x027E -> {
                    if (recLen >= 10) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val rkVal = decodeRkValue(bb.getInt(dataStart + 6))
                        putNumericCell(row, col, rkVal, xfIdx)
                    }
                }
                0x00BD -> {
                    if (recLen >= 6) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val firstCol = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val lastCol = bb.getShort(dataStart + recLen - 2).toInt() and 0xFFFF
                        var offset = dataStart + 4
                        var c = firstCol
                        while (c <= lastCol && offset + 6 <= dataStart + recLen - 2) {
                            val xfIdx = bb.getShort(offset).toInt() and 0xFFFF
                            val rkVal = decodeRkValue(bb.getInt(offset + 2))
                            putNumericCell(row, c, rkVal, xfIdx)
                            offset += 6
                            c++
                        }
                    }
                }
                0x0205 -> {
                    if (recLen >= 8) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        val valByte = wb[dataStart + 6].toInt() and 0xFF
                        val isError = (wb[dataStart + 7].toInt() and 0xFF) == 1
                        putCell(row, col, if (isError) errorText(valByte) else if (valByte != 0) "TRUE" else "FALSE", xfIdx, if (isError) SheetCellFlags.ERROR else SheetCellFlags.BOOL)
                    }
                }
                0x0006 -> {
                    if (recLen >= 14) {
                        val row = bb.getShort(dataStart).toInt() and 0xFFFF
                        val col = bb.getShort(dataStart + 2).toInt() and 0xFFFF
                        val xfIdx = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                        lastFormulaRow = row
                        lastFormulaCol = col
                        lastFormulaXf = xfIdx
                        val expr6 = wb[dataStart + 12].toInt() and 0xFF
                        val expr7 = wb[dataStart + 13].toInt() and 0xFF
                        if (expr6 == 0xFF && expr7 == 0xFF) {
                            val kind = wb[dataStart + 6].toInt() and 0xFF
                            if (kind == 1 && dataStart + 8 < wb.size) {
                                putCell(row, col, if (wb[dataStart + 8].toInt() != 0) "TRUE" else "FALSE", xfIdx, SheetCellFlags.BOOL)
                            } else if (kind == 2 && dataStart + 8 < wb.size) {
                                putCell(row, col, errorText(wb[dataStart + 8].toInt() and 0xFF), xfIdx, SheetCellFlags.ERROR)
                            } else if (kind == 3) {
                                putCell(row, col, "", xfIdx)
                            }
                        } else {
                            val d = Double.fromBits(bb.getLong(dataStart + 6))
                            if (d.isFinite()) {
                                putNumericCell(row, col, d, xfIdx)
                            }
                        }
                    }
                }
                0x0207 -> {
                    if (lastFormulaRow >= 0 && lastFormulaCol >= 0 && recLen >= 3) {
                        val formulaStr = readBiff8UnicodeString(wb, dataStart, dataStart + recLen)
                        putTextCell(lastFormulaRow, lastFormulaCol, formulaStr, lastFormulaXf)
                        lastFormulaRow = -1
                        lastFormulaCol = -1
                        lastFormulaXf = -1
                    }
                }
                0x00E5 -> {
                    if (recLen >= 2 && bofDepth == 1) {
                        val targetIdx = currentSheetIdx.coerceIn(0, 64)
                        ensureSheetLists(targetIdx)
                        val count = bb.getShort(dataStart).toInt() and 0xFFFF
                        var mPos = dataStart + 2
                        for (m in 0 until count) {
                            if (mPos + 8 > dataStart + recLen || sheetMergedRegions[targetIdx].size >= 4096) break
                            val rFirst = bb.getShort(mPos).toInt() and 0xFFFF
                            val rLast = (bb.getShort(mPos + 2).toInt() and 0xFFFF).coerceAtMost(MAX_BIFF_ROWS - 1)
                            val cFirst = bb.getShort(mPos + 4).toInt() and 0xFFFF
                            val cLast = (bb.getShort(mPos + 6).toInt() and 0xFFFF).coerceAtMost(MAX_BIFF_COLS - 1)
                            if (rFirst in 0..rLast && cFirst in 0..cLast && (rLast > rFirst || cLast > cFirst)) {
                                sheetMergedRegions[targetIdx].add(MergedCellRegion(rFirst, rLast, cFirst, cLast))
                                if (rLast > sheetMaxRows[targetIdx]) sheetMaxRows[targetIdx] = rLast
                                if (cLast > sheetMaxCols[targetIdx]) sheetMaxCols[targetIdx] = cLast
                            }
                            mPos += 8
                        }
                    }
                }
            }
            pos = dataStart + recLen
        }

        val count = maxOf(sheetNames.size, sheetMaps.size)
        for (i in 0 until count) {
            val name = sheetNames.getOrElse(i) { "Sheet${i + 1}" }
            val map = sheetMaps.getOrElse(i) { HashMap() }
            val styles = sheetStyleMaps.getOrElse(i) { HashMap() }
            val mr = sheetMaxRows.getOrElse(i) { -1 }
            val mc = sheetMaxCols.getOrElse(i) { -1 }
            val merges = sheetMergedRegions.getOrElse(i) { emptyList() }
            val baseFont = fonts.firstOrNull()
            val baseSizePt = (baseFont?.heightTwips ?: 200) / 20f
            val defRow = sheetDefaultRow.getOrNull(i)?.takeIf { it > 0f } ?: (Math.round(baseSizePt * 1.275f * 4f) / 4f * PX_PER_PT)
            val defCol = sheetDefaultCol.getOrNull(i)?.takeIf { it > 0f } ?: defaultExcelColumnWidthPx()
            val colWidthsMap = sheetColWidths.getOrNull(i).orEmpty()
            val colWidthsArr = if (mc >= 0) {
                FloatArray((mc + 1).coerceAtMost(MAX_BIFF_COLS)) { c -> colWidthsMap[c] ?: defCol }
            } else null
            val charts = sheetCharts.getOrNull(i).orEmpty()
            val isChartSheet = i in chartSheetFlags
            if (mr >= 0 || mc >= 0 || count == 1 || i < sheetNames.size || charts.isNotEmpty()) {
                grids.add(
                    SheetGrid(
                        name, map, styles, colWidthsArr, mr, mc, merges, charts, isChartSheet, i,
                        rowHeightsPx = sheetRowHeights.getOrNull(i).orEmpty(),
                        flags = sheetFlags.getOrNull(i).orEmpty(),
                        formatColors = sheetFmtColors.getOrNull(i).orEmpty(),
                        fills = sheetFills.getOrNull(i).orEmpty(),
                        rowStyles = sheetRowStyles.getOrNull(i).orEmpty(),
                        colStyles = sheetColStyles.getOrNull(i).orEmpty(),
                        showGridLines = sheetGridLines.getOrElse(i) { true },
                        defaultRowHeightPx = defRow,
                        defaultColWidthPx = defCol,
                        defaultFontName = baseFont?.name,
                        defaultFontSize = baseSizePt,
                        conditional = sheetCf.getOrNull(i).orEmpty().map { CfBlock(it.first, it.second) }.filter { it.rules.isNotEmpty() }
                    )
                )
            }
        }
        rawNumbers.clear()
        rawTexts.clear()
        for (i in 0 until count) {
            rawNumbers.add(sheetNumbers.getOrElse(i) { HashMap() })
            rawTexts.add(sheetMaps.getOrElse(i) { HashMap() })
        }
        chartXti = xtiList
        chartPalette = HashMap(paletteMap)
        chartTheme = BiffChartParser.theme(themeVersion, themeBuf.toByteArray())
        chartFontSizes = fonts.map { it.heightTwips / 20f }
        if (grids.isEmpty()) {
            lastErrorMsg = "No data sheets found in XLS file."
        }
    }

    private fun parseBiff8Sst(wb: ByteArray, sstRecordPos: Int, outSst: MutableList<String>) {
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val recLen = bb.getShort(sstRecordPos + 2).toInt() and 0xFFFF
        if (recLen < 8) return
        val uniqueStrings = bb.getInt(sstRecordPos + 8).coerceIn(0, minOf(250_000, wb.size / 2))

        var curPos = sstRecordPos + 12
        var curEnd = sstRecordPos + 4 + recLen

        fun advanceToContinue(): Boolean {
            if (curEnd + 4 <= wb.size) {
                val nextId = bb.getShort(curEnd).toInt() and 0xFFFF
                val nextLen = bb.getShort(curEnd + 2).toInt() and 0xFFFF
                if (nextId == 0x003C && curEnd + 4 + nextLen <= wb.size) {
                    curPos = curEnd + 4
                    curEnd = curPos + nextLen
                    return true
                }
            }
            return false
        }

        for (sIdx in 0 until uniqueStrings) {
            if (curPos + 3 > curEnd && !advanceToContinue()) break
            if (curPos + 3 > curEnd) break

            val charCount = bb.getShort(curPos).toInt() and 0xFFFF
            val flags = wb[curPos + 2].toInt() and 0xFF
            curPos += 3

            var isUtf16 = (flags and 0x01) != 0
            val hasExt = (flags and 0x04) != 0
            val hasRich = (flags and 0x08) != 0

            var richRuns = 0
            var extSize = 0
            if (hasRich && curPos + 2 <= curEnd) {
                richRuns = bb.getShort(curPos).toInt() and 0xFFFF
                curPos += 2
            }
            if (hasExt && curPos + 4 <= curEnd) {
                extSize = bb.getInt(curPos).coerceIn(0, wb.size)
                curPos += 4
            }

            val sb = StringBuilder(minOf(charCount, 4096))
            var charsRemaining = charCount
            while (charsRemaining > 0) {
                if (curPos >= curEnd) {
                    if (!advanceToContinue() || curPos >= curEnd) break
                    val contFlags = wb[curPos].toInt() and 0xFF
                    isUtf16 = (contFlags and 0x01) != 0
                    curPos++
                }
                val availBytes = curEnd - curPos
                if (isUtf16) {
                    val canReadChars = minOf(charsRemaining, availBytes / 2)
                    if (canReadChars <= 0) break
                    sb.append(String(wb, curPos, canReadChars * 2, StandardCharsets.UTF_16LE))
                    curPos += canReadChars * 2
                    charsRemaining -= canReadChars
                } else {
                    val canReadChars = minOf(charsRemaining, availBytes)
                    if (canReadChars <= 0) break
                    sb.append(String(wb, curPos, canReadChars, PlainTextSession.CP1251))
                    curPos += canReadChars
                    charsRemaining -= canReadChars
                }
            }
            outSst.add(sb.toString())

            var skipExtra = richRuns * 4 + extSize
            while (skipExtra > 0) {
                val avail = curEnd - curPos
                if (avail >= skipExtra) {
                    curPos += skipExtra
                    skipExtra = 0
                } else {
                    skipExtra -= avail.coerceAtLeast(0)
                    if (!advanceToContinue()) break
                }
            }
        }
    }

    private fun readBiff8UnicodeString(wb: ByteArray, offset: Int, limit: Int): String {
        if (offset + 2 > limit) return ""
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        val charCount = bb.getShort(offset).toInt() and 0xFFFF
        if (offset + 3 > limit) {
            val bytesLen = minOf(charCount, limit - (offset + 2)).coerceAtLeast(0)
            return String(wb, offset + 2, bytesLen, PlainTextSession.CP1251)
        }
        val flags = wb[offset + 2].toInt() and 0xFF
        val isUtf16 = (flags and 0x01) != 0
        val hasExt = (flags and 0x04) != 0
        val hasRich = (flags and 0x08) != 0
        var start = offset + 3
        if (hasRich && start + 2 <= limit) start += 2
        if (hasExt && start + 4 <= limit) start += 4

        return if (isUtf16) {
            val bytesLen = minOf(charCount * 2, limit - start).coerceAtLeast(0)
            String(wb, start, bytesLen and -2, StandardCharsets.UTF_16LE)
        } else {
            val bytesLen = minOf(charCount, limit - start).coerceAtLeast(0)
            String(wb, start, bytesLen, PlainTextSession.CP1251)
        }
    }

    private fun decodeRkValue(rk: Int): Double {
        val isDiv100 = (rk and 0x01) != 0
        val isInt = (rk and 0x02) != 0
        val raw = if (isInt) {
            (rk shr 2).toDouble()
        } else {
            Double.fromBits((rk.toLong() and -4L) shl 32)
        }
        return if (isDiv100) raw / 100.0 else raw
    }

    private fun formatNumber(d: Double): String {
        val asLong = d.toLong()
        if (d == asLong.toDouble()) return asLong.toString()
        val rounded = Math.round(d * 1e10) / 1e10
        val rLong = rounded.toLong()
        return if (rounded == rLong.toDouble()) rLong.toString() else rounded.toString()
    }

    override val pageCount: Int = 1
    override fun chartSheetSize(sheet: SheetModel): Pair<Float, Float> = 1600f to 1100f

    override fun renderChartSheet(sheet: SheetModel, canvas: Canvas, tileX: Int, tileY: Int, zoom: Float, bitmap: Bitmap) {
        val extra = sheet.extra as? XlsSheetExtra ?: return
        val ref = extra.charts.firstOrNull() ?: return
        val model = chartModel(ref, extra.sourceIndex) ?: return
        canvas.save()
        canvas.translate(-tileX.toFloat(), -tileY.toFloat())
        ChartsRenderer.draw(canvas, model, RectF(0f, 0f, 1600f * zoom, 1100f * zoom), zoom * 2.2f)
        canvas.restore()
    }

    override fun drawSheetOverlays(sheet: SheetModel, canvas: Canvas, g: SheetGridGeometry) {
        val extra = sheet.extra as? XlsSheetExtra ?: return
        if (extra.charts.isEmpty()) return
        fun xAt(col: Int, dx: Int): Float = g.screenX(g.colLeft(col) + g.colWidth(col) * (dx.coerceIn(0, 1024) / 1024f))
        fun yAt(row: Int, dy: Int): Float = g.screenY(g.rowTop(row) + g.rowHeight(row) * (dy.coerceIn(0, 256) / 256f))
        for (ref in extra.charts) {
            val a = ref.anchor ?: continue
            val rect = RectF(xAt(a.colL, a.dxL), yAt(a.rwT, a.dyT), xAt(a.colR, a.dxR), yAt(a.rwB, a.dyB))
            if (rect.right < g.headerWidth || rect.bottom < g.headerHeight || rect.left > g.viewWidth || rect.top > g.viewHeight) continue
            if (rect.width() < 4f || rect.height() < 4f) continue
            val model = chartModel(ref, extra.sourceIndex) ?: continue
            ChartsRenderer.draw(canvas, model, rect, SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT * g.zoom / 15f)
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}
