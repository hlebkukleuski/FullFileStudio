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
 * Character recognition dictionaries and ONNX model integration adapted and modified from
 * PaddleOCR (https://github.com/PaddlePaddle/PaddleOCR, ppocr/utils/dict/),
 * Copyright (c) 2020-2026 PaddlePaddle Authors, licensed under the Apache License, Version 2.0.
 * Modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object OcrLexiconAndTypography {
    internal fun splitIntoGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        val bi = BreakIterator.getCharacterInstance(Locale.ROOT)
        bi.setText(text)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = bi.next()
        }
        return out
    }

    private val graphemeWeightCache = java.util.concurrent.ConcurrentHashMap<String, Float>()

    private val graphemeMeasurePaint: Paint by lazy {
        Paint().apply {
            textSize = 100f
            typeface = Typeface.SANS_SERIF
        }
    }

    internal fun getRelativeGraphemeWeight(g: String): Float {
        if (g.isEmpty()) return 1.0f
        graphemeWeightCache[g]?.let { return it }
        val measuredEm = try {
            synchronized(graphemeMeasurePaint) { graphemeMeasurePaint.measureText(g) } / 100f
        } catch (_: Throwable) {
            0f
        }
        val weight = if (measuredEm > 0.01f) {
            (measuredEm / 0.56f).coerceIn(0.2f, 3.0f)
        } else {
            getTabulatedGraphemeWeight(g)
        }
        if (graphemeWeightCache.size > 4096) graphemeWeightCache.clear()
        graphemeWeightCache[g] = weight
        return weight
    }

    private fun getTabulatedGraphemeWeight(g: String): Float {
        val cp = g.codePointAt(0)
        return when {
            cp in 0x1D504..0x1D59F || cp in 0x10330..0x1034A -> 1.15f
            g.length == 1 -> when (g[0]) {
                'W', 'M', 'w', 'm', 'Ш', 'Щ', 'Ж', 'Ю', 'Ы', 'Ф', 'ш', 'щ', 'ж', 'ю', 'ы', 'ф', 'Q', '@', '%', '#', 'Æ', 'Œ' -> 1.45f
                'I', 'l', 'i', 't', 'f', 'j', 'r', '1', '|', '!', ' ', '.', ',', ':', ';', '\'', '`', 'І', 'і', 'Ї', 'ї', 'ł' -> 0.50f
                'J', '(', ')', '[', ']', '{', '}', '/', '\\', '-', '–', '—', '«', '»' -> 0.68f
                else -> 1.0f
            }
            else -> 1.05f
        }
    }


    internal fun normalizeIntraWordHomoglyphs(word: String): String {
        if (word.length < 2) return word
        var cyrCount = 0
        var latCount = 0
        var hasUniqueCyrillic = false
        var hasUniqueLatin = false

        for (ch in word) {
            when {
                ch in '\u0400'..'\u052F' -> {
                    cyrCount++
                    if (ch !in "АВЕКМНОРСТУХІЇЈаеорсухіїј") hasUniqueCyrillic = true
                }
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                    latCount++
                    if (ch !in "ABEKMHOPCTYXIJaekmopcyxij") hasUniqueLatin = true
                }
            }
        }
        if (cyrCount == 0 || latCount == 0) return word
        if (hasUniqueCyrillic && hasUniqueLatin) return word

        val convertToCyrillic = when {
            hasUniqueCyrillic && !hasUniqueLatin -> true
            hasUniqueLatin && !hasUniqueCyrillic -> false
            else -> cyrCount >= latCount
        }

        val sb = StringBuilder(word.length)
        if (convertToCyrillic) {
            for (ch in word) {
                sb.append(
                    when (ch) {
                        'A' -> 'А'; 'a' -> 'а'
                        'B' -> 'В'; 'E' -> 'Е'; 'e' -> 'е'
                        'K' -> 'К'; 'k' -> 'к'
                        'M' -> 'М'; 'H' -> 'Н'
                        'O' -> 'О'; 'o' -> 'о'
                        'P' -> 'Р'; 'p' -> 'р'
                        'C' -> 'С'; 'c' -> 'с'
                        'T' -> 'Т'; 'Y' -> 'У'; 'y' -> 'у'
                        'X' -> 'Х'; 'x' -> 'х'
                        'I' -> 'І'; 'i' -> 'і'
                        else -> ch
                    }
                )
            }
        } else {
            for (ch in word) {
                sb.append(
                    when (ch) {
                        'А' -> 'A'; 'а' -> 'a'
                        'В' -> 'B'; 'Е' -> 'E'; 'е' -> 'e'
                        'К' -> 'K'; 'к' -> 'k'
                        'М' -> 'M'; 'Н' -> 'H'
                        'О' -> 'O'; 'о' -> 'o'
                        'Р' -> 'P'; 'р' -> 'p'
                        'С' -> 'C'; 'с' -> 'c'
                        'Т' -> 'T'; 'У' -> 'Y'; 'у' -> 'y'
                        'Х' -> 'X'; 'х' -> 'x'
                        'І' -> 'I'; 'і' -> 'i'
                        else -> ch
                    }
                )
            }
        }
        return sb.toString()
    }


    internal fun resolveShowcaseLineFontSize(line: String): Float {
        var hasLatin = false
        var hasCyrillicOrSlavonic = false
        var hasDigitOrPunct = false
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            when {
                cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code -> hasLatin = true
                cp in 0x0400..0x052F || cp in 0x2DE0..0x2DFF || cp in 0xA640..0xA69F -> hasCyrillicOrSlavonic = true
                cp != ' '.code -> hasDigitOrPunct = true
            }
            i += Character.charCount(cp)
        }
        return when {
            hasLatin -> 38f
            hasCyrillicOrSlavonic -> 36f
            hasDigitOrPunct -> 34f
            else -> 38f
        }
    }

}

internal fun isRecognizedTextInconsistentWithLexicon(
    lines: List<OcrRecognizedLine>,
    pageLexicon: Set<String>
): Boolean {
    if (pageLexicon.size < 4) return false
    val lexiconLower = HashSet<String>(pageLexicon.size * 2)
    for (word in pageLexicon) lexiconLower.add(word.lowercase(Locale.ROOT))

    var comparable = 0
    var matched = 0
    for (line in lines) {
        for (token in line.text.split(LEXICON_TOKEN_SPLIT_REGEX)) {
            if (token.length < 3) continue
            comparable++
            if (token.lowercase(Locale.ROOT) in lexiconLower) matched++
        }
    }
    return comparable >= 4 && matched * 10 < comparable * 3
}

internal fun <T> sortIntoVisualRows(
    items: List<T>,
    rectOf: (T) -> Rect,
    isSameRow: (anchor: Rect, candidate: Rect) -> Boolean
): List<T> {
    if (items.size <= 1) return items
    val byTop = items.sortedWith(compareBy({ rectOf(it).top }, { rectOf(it).left }))
    val rows = ArrayList<ArrayList<T>>()
    for (item in byTop) {
        val rect = rectOf(item)
        val row = rows.lastOrNull { isSameRow(rectOf(it[0]), rect) }
        if (row != null) row.add(item) else rows.add(arrayListOf(item))
    }
    val out = ArrayList<T>(items.size)
    for (row in rows) {
        row.sortBy { rectOf(it).left }
        out.addAll(row)
    }
    return out
}

/**
 * Maps Android 15+ native `[PDF_BOX:normL,normT,normR,normB]text` lines onto the rendered [bitmap]
 * and refines each line's bounding box and word/character positions against actual foreground ink pixels.
 */

internal fun sampleDominantBackgroundColor(bitmap: Bitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    val sampleCoords = intArrayOf(
        2, 2,
        (w - 3).coerceAtLeast(0), 2,
        2, (h - 3).coerceAtLeast(0),
        (w - 3).coerceAtLeast(0), (h - 3).coerceAtLeast(0),
        w / 2, 2,
        w / 2, (h - 3).coerceAtLeast(0)
    )
    var rSum = 0; var gSum = 0; var bSum = 0; var cnt = 0
    var i = 0
    while (i + 1 < sampleCoords.size) {
        val px = bitmap.getPixel(sampleCoords[i].coerceIn(0, w - 1), sampleCoords[i + 1].coerceIn(0, h - 1))
        if (Color.alpha(px) > 200) {
            rSum += Color.red(px)
            gSum += Color.green(px)
            bSum += Color.blue(px)
            cnt++
        }
        i += 2
    }
    return if (cnt > 0) Color.rgb(rSum / cnt, gSum / cnt, bSum / cnt) else Color.WHITE
}

internal fun hasUnmaskedForegroundInk(bitmap: Bitmap, maskedBoxes: List<Rect>): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 16 || h < 16) return false
    val bg = sampleDominantBackgroundColor(bitmap)
    val bgR = Color.red(bg); val bgG = Color.green(bg); val bgB = Color.blue(bg)
    val step = 4
    var inkHits = 0
    var y = 6
    while (y < h - 6) {
        var x = 6
        while (x < w - 6) {
            var isMasked = false
            for (b in maskedBoxes) {
                if (x >= b.left - 3 && x <= b.right + 3 && y >= b.top - 3 && y <= b.bottom + 3) {
                    isMasked = true
                    break
                }
            }
            if (!isMasked) {
                val px = bitmap.getPixel(x, y)
                if (Color.alpha(px) > 128) {
                    val diff = abs(Color.red(px) - bgR) + abs(Color.green(px) - bgG) + abs(Color.blue(px) - bgB)
                    if (diff > 96) {
                        inkHits++
                        if (inkHits >= 55) return true
                    }
                }
            }
            x += step
        }
        y += step
    }
    return false
}

internal fun createMaskedBitmapCopy(bitmap: Bitmap, maskedBoxes: List<Rect>): Bitmap? {
    return try {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val bg = sampleDominantBackgroundColor(bitmap)
        val canvas = Canvas(copy)
        val erasePaint = Paint().apply {
            color = bg
            style = Paint.Style.FILL
        }
        for (b in maskedBoxes) {
            canvas.drawRect(
                (b.left - 2f).coerceAtLeast(0f),
                (b.top - 2f).coerceAtLeast(0f),
                (b.right + 2f).coerceAtMost(copy.width.toFloat()),
                (b.bottom + 2f).coerceAtMost(copy.height.toFloat()),
                erasePaint
            )
        }
        copy
    } catch (_: Throwable) {
        null
    }
}

internal fun stitchNativeAndRasterPayloadsSeamlessly(
    nativePayload: OcrResultPayload,
    rasterPayload: OcrResultPayload
): OcrResultPayload {
    data class LineWithChars(
        val line: OcrRecognizedLine,
        val chars: List<OcrRecognizedChar>
    )

    val nativeCharsByLine = nativePayload.chars.groupBy { it.lineIndex }
    val rasterCharsByLine = rasterPayload.chars.groupBy { it.lineIndex }

    val combined = ArrayList<LineWithChars>(nativePayload.lines.size + rasterPayload.lines.size)
    for (i in nativePayload.lines.indices) {
        val c = nativeCharsByLine[i].orEmpty()
        if (c.isNotEmpty()) combined.add(LineWithChars(nativePayload.lines[i], c))
    }
    for (i in rasterPayload.lines.indices) {
        val rLine = rasterPayload.lines[i]
        val c = rasterCharsByLine[i].orEmpty()
        if (c.isEmpty()) continue
        /* Deduplicate if a raster box overlaps > 55% with an existing native PDF line */
        val rArea = (rLine.rect.width().coerceAtLeast(1) * rLine.rect.height().coerceAtLeast(1)).toFloat()
        val overlapsNative = nativePayload.lines.any { nLine ->
            val ix = minOf(nLine.rect.right, rLine.rect.right) - maxOf(nLine.rect.left, rLine.rect.left)
            val iy = minOf(nLine.rect.bottom, rLine.rect.bottom) - maxOf(nLine.rect.top, rLine.rect.top)
            ix > 0 && iy > 0 && (ix * iy) / rArea > 0.55f
        }
        if (!overlapsNative) {
            combined.add(LineWithChars(rLine, c))
        }
    }

    /* Sort all lines in natural 2D reading order (same visual row left-to-right, then top-to-bottom) */
    val ordered = sortIntoVisualRows(combined, { it.line.rect }) { anchor, candidate ->
        val vOverlap = minOf(anchor.bottom, candidate.bottom) - maxOf(anchor.top, candidate.top)
        val minH = minOf(anchor.height(), candidate.height()).coerceAtLeast(1)
        vOverlap >= minH * 0.45f
    }

    val finalLines = ArrayList<OcrRecognizedLine>(ordered.size)
    val finalChars = ArrayList<OcrRecognizedChar>(nativePayload.chars.size + rasterPayload.chars.size)
    for ((newLineIdx, item) in ordered.withIndex()) {
        finalLines.add(item.line)
        for (ch in item.chars) {
            finalChars.add(ch.copy(lineIndex = newLineIdx))
        }
    }
    return OcrResultPayload(finalLines, finalChars)
}

internal fun extractPageLexicon(hint: String): Set<String> {
    if (hint.isBlank()) return emptySet()
    val out = LinkedHashSet<String>()
    for (rawLine in hint.lines()) {
        val m = PDF_NATIVE_BOX_LINE_REGEX.matchEntire(rawLine.trim())
        val lineText = m?.groupValues?.get(5) ?: rawLine
        for (token in lineText.split(LEXICON_TOKEN_SPLIT_REGEX)) {
            val trimmed = token.trim()
            if (trimmed.length in 2..42) {
                out.add(trimmed)
            }
        }
    }
    return out
}

/**
 * Normalizes accidental mixed-script homoglyphs inside a single word (e.g. Latin 'o', 'a', 'e', 'c'
 * inside a predominantly Cyrillic word, or vice versa).
 */

internal fun snapLineToPageLexicon(rawLine: String, pageLexicon: Set<String>): String {
    if (rawLine.isBlank()) return rawLine
    val words = rawLine.split(' ')
    val corrected = words.map { rawWord ->
        val w = OcrLexiconAndTypography.normalizeIntraWordHomoglyphs(rawWord)
        if (pageLexicon.isEmpty()) {
            w
        } else {
            val clean = w.trim(',', '.', ';', ':', '!', '?', '(', ')', '[', ']', '"', '«', '»')
            if (clean.length < 2 || clean in pageLexicon) {
                w
            } else {
                val normalizedHomoglyphs = normalizeHomoglyphsToTargetScript(clean, pageLexicon)
                if (normalizedHomoglyphs in pageLexicon) {
                    w.replaceFirst(clean, normalizedHomoglyphs)
                } else {
                    var bestMatch: String? = null
                    var bestDist = Int.MAX_VALUE
                    val maxAllowedDist = when {
                        clean.length >= 8 -> 2
                        clean.length >= 4 -> 1
                        else -> 0
                    }
                    if (maxAllowedDist > 0) {
                        for (candidate in pageLexicon) {
                            if (abs(candidate.length - normalizedHomoglyphs.length) > maxAllowedDist) continue
                            val dist = levenshteinDistanceIgnoreCase(normalizedHomoglyphs, candidate, maxAllowedDist)
                            if (dist <= maxAllowedDist && dist < bestDist) {
                                bestDist = dist
                                bestMatch = candidate
                            }
                        }
                    }
                    if (bestMatch != null) {
                        w.replaceFirst(clean, bestMatch)
                    } else {
                        w
                    }
                }
            }
        }
    }
    return corrected.joinToString(" ")
}

internal fun normalizeHomoglyphsToTargetScript(word: String, pageLexicon: Set<String>): String {
    if (pageLexicon.isEmpty()) return word
    val lexiconHasCyrillic = pageLexicon.any { tok -> tok.any { it in '\u0400'..'\u052F' } }
    val lexiconHasLatin = pageLexicon.any { tok -> tok.any { it in 'A'..'Z' || it in 'a'..'z' } }
    if (!lexiconHasCyrillic && !lexiconHasLatin) return word
    val sb = StringBuilder(word.length)
    if (lexiconHasCyrillic && !lexiconHasLatin) {
        for (ch in word) {
            val mapped = when (ch) {
                'A' -> 'А'; 'a' -> 'а'
                'B' -> 'В'; 'E' -> 'Е'; 'e' -> 'е'
                'K' -> 'К'; 'k' -> 'к'
                'M' -> 'М'; 'H' -> 'Н'
                'O', '0' -> 'О'; 'o' -> 'о'
                'P' -> 'Р'; 'p' -> 'р'
                'C' -> 'С'; 'c' -> 'с'
                'T' -> 'Т'; 'Y' -> 'У'; 'y' -> 'y'
                'X' -> 'Х'; 'x' -> 'х'
                else -> ch
            }
            sb.append(mapped)
        }
    } else if (lexiconHasLatin && !lexiconHasCyrillic) {
        for (ch in word) {
            val mapped = when (ch) {
                'А' -> 'A'; 'а' -> 'a'
                'В' -> 'B'; 'Е' -> 'E'; 'е' -> 'e'
                'К' -> 'K'; 'к' -> 'k'
                'М' -> 'M'; 'Н' -> 'H'
                'О' -> 'O'; 'о' -> 'o'
                'Р' -> 'P'; 'р' -> 'p'
                'С' -> 'C'; 'с' -> 'c'
                'Т' -> 'T'; 'У' -> 'Y'; 'у' -> 'y'
                'Х' -> 'X'; 'х' -> 'x'
                'І' -> 'I'; 'і' -> 'i'
                else -> ch
            }
            sb.append(mapped)
        }
    } else {
        return OcrLexiconAndTypography.normalizeIntraWordHomoglyphs(word)
    }
    return sb.toString()
}

internal fun levenshteinDistanceIgnoreCase(a: String, b: String, maxThreshold: Int): Int {
    val lenA = a.length
    val lenB = b.length
    if (abs(lenA - lenB) > maxThreshold) return maxThreshold + 1
    var prev = IntArray(lenB + 1) { it }
    var curr = IntArray(lenB + 1)
    for (i in 1..lenA) {
        curr[0] = i
        var minInRow = curr[0]
        val ca = a[i - 1].lowercaseChar()
        for (j in 1..lenB) {
            val cb = b[j - 1].lowercaseChar()
            val cost = if (ca == cb) 0 else 1
            curr[j] = minOf(
                prev[j] + 1,
                curr[j - 1] + 1,
                prev[j - 1] + cost
            )
            if (curr[j] < minInRow) minInRow = curr[j]
        }
        if (minInRow > maxThreshold) return maxThreshold + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[lenB]
}

internal fun isFontGlyphGridPageHint(hint: String): Boolean {
    return hint.startsWith("[U+", ignoreCase = true) && GLYPH_CELL_ENTRY_REGEX.containsMatchIn(hint)
}

internal fun isFontSpecimenOverviewPageHint(hint: String): Boolean {
    return hint.contains("UnitsPerEm:") && hint.contains("SFNT Tables")
}

internal fun buildFontGlyphGridPayload(
    bitmap: Bitmap,
    hint: String,
    zoom: Float,
    tileX: Int,
    tileY: Int
): OcrResultPayload {
    val matches = GLYPH_CELL_ENTRY_REGEX.findAll(hint).toList()
    if (matches.isEmpty()) return OcrResultPayload(emptyList(), emptyList())

    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val baseScale = minOf(bmpW / FONT_SPECIMEN_DOC_WIDTH, bmpH / FONT_SPECIMEN_DOC_HEIGHT).coerceAtLeast(0.1f)
    val totalScale = baseScale * zoom
    val dx = (bmpW - FONT_SPECIMEN_DOC_WIDTH * totalScale) / 2f - tileX.toFloat()
    val dy = (bmpH - FONT_SPECIMEN_DOC_HEIGHT * totalScale) / 2f - tileY.toFloat()

    val margin = 40f
    val gridTop = 96f
    val gridCols = 8
    val gridRows = 8
    val cellW = (FONT_SPECIMEN_DOC_WIDTH - margin * 2f) / gridCols
    val cellH = (FONT_SPECIMEN_DOC_HEIGHT - gridTop - margin) / gridRows

    val lines = ArrayList<OcrRecognizedLine>(gridRows * 2)
    val chars = ArrayList<OcrRecognizedChar>(matches.size * 8)

    val rowsGrouped = matches.withIndex().groupBy { it.index / gridCols }
    for ((rowIndex, rowEntries) in rowsGrouped) {
        if (rowIndex >= gridRows) break
        val rawGlyphTop = (dy + (gridTop + (rowIndex + 0.10f) * cellH) * totalScale).roundToInt()
        val rawGlyphBot = (dy + (gridTop + (rowIndex + 0.64f) * cellH) * totalScale).roundToInt()
        val rawCodeTop = (dy + (gridTop + (rowIndex + 0.76f) * cellH) * totalScale).roundToInt()
        val rawCodeBot = (dy + (gridTop + (rowIndex + 0.96f) * cellH) * totalScale).roundToInt()
        if (rawCodeBot <= 0 || rawGlyphTop >= bitmap.height) continue

        val glyphTopPx = rawGlyphTop.coerceIn(0, bitmap.height - 4)
        val glyphBotPx = rawGlyphBot.clampTo(glyphTopPx + 2, bitmap.height - 2)
        val codeTopPx = rawCodeTop.clampTo(glyphBotPx + 1, bitmap.height - 2)
        val codeBotPx = rawCodeBot.clampTo(codeTopPx + 1, bitmap.height)

        var rowLeftPx = bitmap.width
        var rowRightPx = 0
        val glyphLineBuilder = StringBuilder()
        val codeLineBuilder = StringBuilder()
        val glyphLineIdx = lines.size
        val codeLineIdx = lines.size + 1
        val pendingCodeChars = ArrayList<OcrRecognizedChar>()

        for ((itemIndex, matchResult) in rowEntries) {
            val colIndex = itemIndex % gridCols
            val hexPart = matchResult.groupValues[1].uppercase(Locale.ROOT)
            val codeHexStr = "U+$hexPart"
            val codePoint = hexPart.toIntOrNull(16)
            val glyphStr = if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                matchResult.groupValues[2]
            }

            val rawLeft = (dx + (margin + colIndex * cellW + 8f) * totalScale).roundToInt()
            val rawRight = (dx + (margin + (colIndex + 1) * cellW - 8f) * totalScale).roundToInt()
            if (rawRight <= 0 || rawLeft >= bitmap.width) continue

            val cLeft = rawLeft.coerceIn(0, bitmap.width - 2)
            val cRight = rawRight.clampTo(cLeft + 1, bitmap.width)

            if (cLeft < rowLeftPx) rowLeftPx = cLeft
            if (cRight > rowRightPx) rowRightPx = cRight

            if (glyphLineBuilder.isNotEmpty()) {
                val prevRight = chars.last().rect.right
                val spRect = Rect(prevRight, glyphTopPx, maxOf(prevRight + 1, cLeft), glyphBotPx)
                chars.add(OcrRecognizedChar(" ", spRect, glyphLineIdx))
                glyphLineBuilder.append(' ')
            }
            val glyphRect = Rect(cLeft, glyphTopPx, cRight, glyphBotPx)
            chars.add(OcrRecognizedChar(glyphStr, glyphRect, glyphLineIdx))
            glyphLineBuilder.append(glyphStr)

            if (codeLineBuilder.isNotEmpty() && pendingCodeChars.isNotEmpty()) {
                val prevCodeRight = pendingCodeChars.last().rect.right
                val spCodeRect = Rect(prevCodeRight, codeTopPx, maxOf(prevCodeRight + 1, cLeft), codeBotPx)
                pendingCodeChars.add(OcrRecognizedChar(" ", spCodeRect, codeLineIdx))
                codeLineBuilder.append(' ')
            }
            val codeRect = Rect(cLeft, codeTopPx, cRight, codeBotPx)
            emitGraphemesIntoBox(codeHexStr, codeRect, codeLineIdx, pendingCodeChars)
            codeLineBuilder.append(codeHexStr)
        }

        if (glyphLineBuilder.isNotEmpty() && rowRightPx > rowLeftPx) {
            lines.add(OcrRecognizedLine(glyphLineBuilder.toString(), Rect(rowLeftPx, glyphTopPx, rowRightPx, glyphBotPx)))
            lines.add(OcrRecognizedLine(codeLineBuilder.toString(), Rect(rowLeftPx, codeTopPx, rowRightPx, codeBotPx)))
            chars.addAll(pendingCodeChars)
        }
    }

    return OcrResultPayload(lines, chars)
}


internal fun isPangramCandidateLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.startsWith("PANGRAM:", ignoreCase = true)) return true
    if (trimmed.contains("Съешь", ignoreCase = true) ||
        trimmed.contains("мягких", ignoreCase = true) ||
        trimmed.contains("французских", ignoreCase = true) ||
        trimmed.contains("булок", ignoreCase = true) ||
        trimmed.contains("выпей", ignoreCase = true) ||
        trimmed.contains("чаю", ignoreCase = true) ||
        trimmed.contains("quick", ignoreCase = true) ||
        trimmed.contains("brown", ignoreCase = true) ||
        trimmed.contains("jumps", ignoreCase = true) ||
        trimmed.contains("lazy", ignoreCase = true)
    ) {
        return true
    }
    if (trimmed.endsWith("1234567890") && trimmed.count { it == ' ' } >= 3 && !trimmed.contains("!\"#")) {
        return true
    }
    return false
}

internal fun buildFontSpecimenOverviewPayload(
    bitmap: Bitmap,
    hint: String,
    zoom: Float,
    tileX: Int,
    tileY: Int
): OcrResultPayload {
    val rawLines = hint.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (rawLines.isEmpty()) return OcrResultPayload(emptyList(), emptyList())

    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val baseScale = minOf(bmpW / FONT_SPECIMEN_DOC_WIDTH, bmpH / FONT_SPECIMEN_DOC_HEIGHT).coerceAtLeast(0.1f)
    val totalScale = baseScale * zoom
    val dx = (bmpW - FONT_SPECIMEN_DOC_WIDTH * totalScale) / 2f - tileX.toFloat()
    val dy = (bmpH - FONT_SPECIMEN_DOC_HEIGHT * totalScale) / 2f - tileY.toFloat()

    val leftPadDoc = 68f
    val maxRightDoc = FONT_SPECIMEN_DOC_WIDTH - 68f
    val maxContentWidthDoc = maxRightDoc - leftPadDoc

    val linesOut = ArrayList<OcrRecognizedLine>()
    val charsOut = ArrayList<OcrRecognizedChar>()

    fun estimateLineRightDoc(text: String, leftDoc: Float, fontSizeDoc: Float): Float {
        val graphemes = OcrLexiconAndTypography.splitIntoGraphemes(text)
        var weightedCount = 0f
        for (g in graphemes) {
            weightedCount += OcrLexiconAndTypography.getRelativeGraphemeWeight(g)
        }
        val estWidth = (weightedCount * fontSizeDoc * 0.56f).coerceIn(fontSizeDoc * 1.2f, maxRightDoc - leftDoc)
        return (leftDoc + estWidth).coerceAtMost(maxRightDoc)
    }

    fun emitVirtualDocLine(
        text: String,
        leftDoc: Float,
        baselineYDoc: Float,
        fontSizeDoc: Float,
        useFullWidth: Boolean = false
    ) {
        val rightDoc = if (useFullWidth) maxRightDoc else estimateLineRightDoc(text, leftDoc, fontSizeDoc)
        val topDoc = baselineYDoc - fontSizeDoc * 0.88f
        val botDoc = baselineYDoc + fontSizeDoc * 0.28f
        val rawLeftPx = (dx + leftDoc * totalScale).roundToInt()
        val rawRightPx = (dx + rightDoc * totalScale).roundToInt()
        val rawTopPx = (dy + topDoc * totalScale).roundToInt()
        val rawBotPx = (dy + botDoc * totalScale).roundToInt()
        if (rawBotPx <= 0 || rawTopPx >= bitmap.height || rawRightPx <= 0 || rawLeftPx >= bitmap.width) return

        val lPx = rawLeftPx.coerceIn(0, bitmap.width - 2)
        val rPx = rawRightPx.clampTo(lPx + 1, bitmap.width)
        val tPx = rawTopPx.coerceIn(0, bitmap.height - 2)
        val bPx = rawBotPx.clampTo(tPx + 1, bitmap.height)
        val rect = Rect(lPx, tPx, rPx, bPx)
        val lineIdx = linesOut.size
        linesOut.add(OcrRecognizedLine(text, rect))
        emitGraphemesIntoBox(text, rect, lineIdx, charsOut)
    }

    val headerLines = ArrayList<String>()
    val showcaseLines = ArrayList<String>()
    val pangramTemplates = ArrayList<String>()

    var passedSfntTables = false
    for (l in rawLines) {
        when {
            !passedSfntTables -> {
                headerLines.add(l)
                if (l.startsWith("SFNT Tables")) passedSfntTables = true
            }
            isPangramCandidateLine(l) -> pangramTemplates.add(l.removePrefix("PANGRAM:").trim())
            else -> showcaseLines.add(l)
        }
    }

    if (pangramTemplates.isEmpty() && showcaseLines.size >= 2 && showcaseLines.last() == showcaseLines.first()) {
        pangramTemplates.add(showcaseLines.removeAt(showcaseLines.lastIndex))
    } else if (pangramTemplates.isEmpty() && showcaseLines.isNotEmpty()) {
        pangramTemplates.add(showcaseLines.first())
    }

    var yDoc = 96f
    if (headerLines.isNotEmpty()) {
        emitVirtualDocLine(headerLines[0], leftPadDoc, yDoc, 68f)
        yDoc += 44f
    }
    if (headerLines.size >= 2) {
        emitVirtualDocLine(headerLines[1], leftPadDoc, yDoc, 24f, useFullWidth = true)
        yDoc += 34f
    }
    for (idx in 2 until headerLines.size) {
        val hLine = headerLines[idx]
        emitVirtualDocLine(hLine, leftPadDoc, yDoc, 21f)
        yDoc += when {
            hLine.startsWith("SFNT Tables") -> 26f
            hLine.startsWith("PostScript:") || hLine.startsWith("Version:") || hLine.startsWith("Designer:") -> 28f
            else -> 30f
        }
    }

    yDoc += 46f

    for (sLine in showcaseLines) {
        val sz = OcrLexiconAndTypography.resolveShowcaseLineFontSize(sLine)
        emitVirtualDocLine(sLine, leftPadDoc, yDoc, sz)
        yDoc += sz * 1.38f
    }

    yDoc += 12f + 48f

    val waterfallSizes = intArrayOf(22, 28, 36, 48, 62)
    for (pt in waterfallSizes) {
        if (yDoc + pt * 1.4f > FONT_SPECIMEN_DOC_HEIGHT - 54f) break
        for (pangram in pangramTemplates) {
            if (yDoc + pt * 1.3f > FONT_SPECIMEN_DOC_HEIGHT - 48f) break
            val maxCharsAtPt = ((maxContentWidthDoc - 76f) / (pt * 0.54f)).toInt().coerceAtLeast(8)
            val visiblePangram = if (pangram.length > maxCharsAtPt) {
                pangram.take(maxCharsAtPt).trimEnd()
            } else {
                pangram
            }
            emitVirtualDocLine("${pt}px $visiblePangram", leftPadDoc, yDoc, pt.toFloat())
            yDoc += pt * 1.32f
        }
        yDoc += 6f
    }

    return OcrResultPayload(linesOut, charsOut)
}

internal fun emitGraphemesIntoBox(
    lineText: String,
    box: Rect,
    lineIndex: Int,
    outChars: MutableList<OcrRecognizedChar>
) {
    val graphemes = OcrLexiconAndTypography.splitIntoGraphemes(lineText)
    if (graphemes.isEmpty()) return
    val boxW = box.width().coerceAtLeast(1)
    val weights = FloatArray(graphemes.size) { OcrLexiconAndTypography.getRelativeGraphemeWeight(graphemes[it]) }
    val totalWeight = weights.sum().coerceAtLeast(0.001f)

    var curX = box.left.toFloat()
    for (i in graphemes.indices) {
        val gW = boxW * (weights[i] / totalWeight)
        val cLeft = curX.roundToInt().coerceIn(box.left, maxOf(box.left, box.right - 1))
        val cRight = if (i == graphemes.lastIndex) {
            box.right.coerceAtLeast(cLeft + 1)
        } else {
            (curX + gW).roundToInt().coerceIn(cLeft + 1, maxOf(cLeft + 1, box.right))
        }
        outChars.add(OcrRecognizedChar(graphemes[i], Rect(cLeft, box.top, cRight, box.bottom), lineIndex))
        curX += gW
    }
}

/**
 * Tightens a candidate line bounding box to the actual foreground ink pixels inside it,
 * eliminating excess horizontal/vertical whitespace margins from DBNet unclipping or contrast dilation.
 */
internal fun tightenBoxToForegroundInk(bitmap: Bitmap, candidate: Rect): Rect {
    val subGlyphs = segmentLineIntoGlyphRects(bitmap, candidate)
    if (subGlyphs.isEmpty()) return candidate
    val minL = subGlyphs.minOf { it.left }.coerceAtLeast(candidate.left)
    val minT = subGlyphs.minOf { it.top }.coerceAtLeast(candidate.top)
    val maxR = subGlyphs.maxOf { it.right }.clampTo(minL + 2, candidate.right)
    val maxB = subGlyphs.maxOf { it.bottom }.clampTo(minT + 2, candidate.bottom)
    return Rect(minL, minT, maxR, maxB)
}

internal fun isPlausibleTextContrastBox(bitmap: Bitmap, box: Rect): Boolean {
    val w = box.width()
    val h = box.height()
    if (w < 8 || h < 8) return false
    if (h > bitmap.height * 0.25f) return false
    /* Filter out thin vertical divider lines or huge non-text photo texture blocks */
    val aspect = w.toFloat() / h.toFloat()
    if (aspect < 0.45f && h > 28) return false
    val glyphs = segmentLineIntoGlyphRects(bitmap, box)
    return glyphs.isNotEmpty()
}

