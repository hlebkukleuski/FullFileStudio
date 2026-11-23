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

package me.hlebkukleuski.fullfilestudio.feature.office

import com.omnireader.core.engine.impl.PlainTextSession
import com.omnireader.core.engine.impl.SheetBorder
import com.omnireader.core.engine.impl.SheetCellFlags
import com.omnireader.core.engine.impl.SheetCellStyle
import com.omnireader.core.engine.impl.SpreadsheetGridSession
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object CleanRoomCsvParser {
    private const val MAX_ROWS = 100_000
    private const val MAX_COLS = 1024
    private const val MAX_CELL_CHARS = 16384

    data class Detection(val charset: Charset, val bomSkip: Int, val delimiter: Char, val skipFirstSepLine: Boolean)

    private val NUMERIC_RX = Regex("""^\(?[-+]?(?:[$€£¥₽]\s*)?(?:\d{1,3}(?:[ \u00A0,]\d{3})+|\d+)?(?:[.,]\d+)?(?:[eE][-+]?\d+)?(?:\s*[%$€£¥₽])?\)?$""")

    fun detectFromSample(sample: ByteArray, length: Int, ext: String): Detection {
        val len = minOf(sample.size, length).coerceAtLeast(0)
        if (len == 0) return Detection(StandardCharsets.UTF_8, 0, if (ext.equals("tsv", true)) '\t' else ',', false)
        val (charset, bomSkip) = try {
            PlainTextSession.detectCharsetAndValidateText(sample, len)
        } catch (_: Throwable) {
            StandardCharsets.UTF_8 to 0
        }
        val text = String(sample, bomSkip, (len - bomSkip).coerceAtLeast(0), charset)
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (firstLine.length == 5 && firstLine.startsWith("sep=", ignoreCase = true)) {
            val sep = firstLine[4]
            if (sep in charArrayOf(',', ';', '\t', '|')) {
                return Detection(charset, bomSkip, sep, true)
            }
        }
        if (ext.equals("tsv", true)) return Detection(charset, bomSkip, '\t', false)

        val candidates = charArrayOf(',', ';', '\t', '|')
        var bestDelim = ','
        var bestScore = -1.0
        for (delim in candidates) {
            val counts = countColumnsPerRow(text, delim, 25)
            if (counts.isEmpty()) continue
            val multi = counts.filter { it > 1 }
            if (multi.isEmpty()) continue
            val freq = HashMap<Int, Int>()
            for (c in multi) freq[c] = (freq[c] ?: 0) + 1
            val modeEntry = freq.maxByOrNull { it.value } ?: continue
            val consistency = modeEntry.value.toDouble() / counts.size.toDouble()
            val tieBonus = when (delim) { ',' -> 0.04; ';' -> 0.03; '\t' -> 0.02; else -> 0.01 }
            val score = consistency * 100.0 + minOf(modeEntry.key, 40) * 1.5 + tieBonus
            if (score > bestScore) {
                bestScore = score
                bestDelim = delim
            }
        }
        return Detection(charset, bomSkip, bestDelim, false)
    }

    private fun countColumnsPerRow(text: String, delim: Char, maxLines: Int): List<Int> {
        val out = ArrayList<Int>(maxLines)
        var cols = 1
        var inQuotes = false
        var fieldStart = true
        var nonEmptyLine = false
        var i = 0
        while (i < text.length && out.size < maxLines) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') i++ else inQuotes = false
                }
            } else {
                when (c) {
                    '"' -> if (fieldStart) { inQuotes = true; fieldStart = false; nonEmptyLine = true }
                    delim -> { cols++; fieldStart = true; nonEmptyLine = true }
                    '\r', '\n' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        if (nonEmptyLine) out.add(cols)
                        cols = 1; fieldStart = true; nonEmptyLine = false
                    }
                    else -> {
                        if (!c.isWhitespace()) { fieldStart = false; nonEmptyLine = true }
                    }
                }
            }
            i++
        }
        if (nonEmptyLine && out.size < maxLines) out.add(cols)
        return out
    }

    fun parseBytes(bytes: ByteArray, ext: String = "csv"): SpreadsheetGridSession.SheetModel {
        val sampleLen = minOf(bytes.size, 16384)
        val det = detectFromSample(bytes, sampleLen, ext)
        return parseStream(ByteArrayInputStream(bytes, det.bomSkip, (bytes.size - det.bomSkip).coerceAtLeast(0)), det, ext)
    }

    fun parseStream(stream: InputStream, det: Detection, ext: String): SpreadsheetGridSession.SheetModel {
        val rows = ArrayList<ArrayList<String>>()
        val reader = BufferedReader(InputStreamReader(stream, det.charset), 32 * 1024)
        if (det.skipFirstSepLine) reader.readLine()

        var currentRow = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var wasQuoted = false
        var atFieldStart = true
        var chInt = reader.read()

        fun finishCell() {
            if (currentRow.size < MAX_COLS) {
                val raw = cell.toString()
                currentRow.add(if (wasQuoted) raw else raw.trim())
            }
            cell.setLength(0)
            wasQuoted = false
            atFieldStart = true
        }

        fun finishRow() {
            finishCell()
            if (currentRow.size > 1 || (currentRow.size == 1 && currentRow[0].isNotEmpty())) {
                rows.add(currentRow)
            }
            currentRow = ArrayList()
        }

        while (chInt != -1 && rows.size < MAX_ROWS) {
            val c = chInt.toChar()
            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1)
                    val next = reader.read()
                    if (next == '"'.code) {
                        if (cell.length < MAX_CELL_CHARS) cell.append('"')
                    } else {
                        inQuotes = false
                        if (next != -1) reader.reset()
                    }
                } else {
                    if (cell.length < MAX_CELL_CHARS) cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        if (atFieldStart) {
                            cell.setLength(0)
                            inQuotes = true
                            wasQuoted = true
                            atFieldStart = false
                        } else if (cell.length < MAX_CELL_CHARS) {
                            cell.append(c)
                        }
                    }
                    det.delimiter -> finishCell()
                    '\r' -> {
                        reader.mark(1)
                        val next = reader.read()
                        if (next != '\n'.code && next != -1) reader.reset()
                        finishRow()
                    }
                    '\n' -> finishRow()
                    else -> {
                        if (!c.isWhitespace()) atFieldStart = false
                        if (cell.length < MAX_CELL_CHARS) cell.append(c)
                    }
                }
            }
            chInt = reader.read()
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty() || wasQuoted) {
            finishRow()
        }

        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        val flags = HashMap<Int, ByteArray>()
        var row0Numeric = 0
        var row1Numeric = 0
        val maxLens = IntArray(maxCols)
        var anyMultiline = false
        val rowLines = IntArray(rows.size) { 1 }
        val styles = HashMap<Long, SheetCellStyle>()
        val wrapStyle = SheetCellStyle(wrap = true)
        for ((r, row) in rows.withIndex()) {
            for ((c, v) in row.withIndex()) {
                val hasNl = v.indexOf('\n') >= 0
                if (hasNl) {
                    val parts = v.split('\n')
                    if (parts.size > rowLines[r]) rowLines[r] = parts.size.coerceAtMost(8)
                    anyMultiline = true
                    styles[SpreadsheetGridSession.packCoord(r, c)] = wrapStyle
                    if (r < 500 && c < maxCols) {
                        val lineLen = parts.maxOf { it.length }
                        if (lineLen > maxLens[c]) maxLens[c] = lineLen
                    }
                } else if (r < 500 && c < maxCols) {
                    if (v.length > maxLens[c]) maxLens[c] = v.length
                }
                if (v.isNotEmpty() && v.any { it.isDigit() } && NUMERIC_RX.matches(v)) {
                    SpreadsheetGridSession.rowFlags(flags, r, c, SheetCellFlags.NUMBER)
                    if (r == 0) row0Numeric++
                    else if (r == 1) row1Numeric++
                }
            }
        }

        val colWidths = if (maxCols > 0) {
            FloatArray(maxCols) { c ->
                val chars = maxLens[c].coerceIn(5, 36)
                (chars * 19.5f + 42f).coerceIn(124f, 680f)
            }
        } else null

        val rowHeights = if (anyMultiline) {
            FloatArray(rows.size) { r ->
                val lines = rowLines[r]
                if (lines <= 1) 48f else (lines * 36f + 16f)
            }
        } else null

        if (rows.size >= 2 && row0Numeric == 0 && (row1Numeric > 0 || rows[0].all { it.length <= 48 })) {
            val headerStyle = SheetCellStyle(
                fill = 0xFFECF0F8.toInt(),
                bold = true,
                bottom = SheetBorder(SheetBorder.THIN, 0xFFA0ACC2.toInt())
            )
            for (c in rows[0].indices) {
                styles[SpreadsheetGridSession.packCoord(0, c)] = headerStyle
            }
        }

        val title = if (ext.equals("tsv", true)) "TSV Data" else "CSV Data"
        return SpreadsheetGridSession.SheetModel(
            name = title,
            rows = rows,
            styles = styles,
            colWidthsPx = colWidths,
            rowHeightsPx = rowHeights,
            flags = flags
        )
    }
}
