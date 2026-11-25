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

package me.hlebkukleuski.fullfilestudio.ui.viewer

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.core.media.MediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager

internal val REFLOW_HTML_HEADING_REGEX = Regex("(?i)^<(h[1-6])>([\\s\\S]*?)</\\1>$")
internal val REFLOW_INLINE_TAG_REGEX = Regex("(?i)<(/?)(b|strong|i|em|u|s|del|strike|sub|sup)>")
internal val REFLOW_IMAGE_MARKER_REGEX = Regex("\\[IMAGE:(\\d+)]")
internal val PDF_NATIVE_BOUNDS_LINE_REGEX = Regex("^\\[PDF_BOX:([0-9.]+),([0-9.]+),([0-9.]+),([0-9.]+)](.*)$")
internal val SLIDE_META_TAG_REGEX = Regex("\\[SLIDE_META:transition=([a-z_]+);dur=(\\d+);steps=(\\d+)]")

private fun getFallbackGraphemeRelativeWeight(g: String): Float = PaddleOcrManager.getRelativeGraphemeWeight(g)

internal fun buildFallbackCharsFromPageText(
    rawPageText: String,
    bmpWidth: Int,
    bmpHeight: Int,
    isHexLayout: Boolean,
    docWidth: Float = 0f,
    docHeight: Float = 0f,
    zoomPermille: Int = 1000,
    tileX: Int = 0,
    tileY: Int = 0
): Pair<List<RecognizedLineUi>, List<RecognizedCharUi>> {
    if (rawPageText.isBlank() || bmpWidth < 32 || bmpHeight < 32) {
        return emptyList<RecognizedLineUi>() to emptyList()
    }

    val rawLines = rawPageText.lines()
    val hasExplicitPdfBoxes = rawLines.any { it.startsWith("[PDF_BOX:") }
    if (hasExplicitPdfBoxes && docWidth > 0f && docHeight > 0f) {
        val linesOut = ArrayList<RecognizedLineUi>(rawLines.size)
        val charsOut = ArrayList<RecognizedCharUi>(rawPageText.length)
        val zoom = (zoomPermille / 1000f).coerceAtLeast(0.5f)
        val baseScale = minOf(bmpWidth.toFloat() / docWidth, bmpHeight.toFloat() / docHeight)
        val totalScale = baseScale * zoom
        val scaledW = docWidth * totalScale
        val scaledH = docHeight * totalScale
        val dx = (bmpWidth - scaledW) / 2f - tileX.toFloat()
        val dy = (bmpHeight - scaledH) / 2f - tileY.toFloat()

        for (rawLine in rawLines) {
            val match = PDF_NATIVE_BOUNDS_LINE_REGEX.matchEntire(rawLine.trim()) ?: continue
            val normL = match.groupValues[1].toFloatOrNull() ?: continue
            val normT = match.groupValues[2].toFloatOrNull() ?: continue
            val normR = match.groupValues[3].toFloatOrNull() ?: continue
            val normB = match.groupValues[4].toFloatOrNull() ?: continue
            val text = match.groupValues[5].trim()
            if (text.isEmpty()) continue

            val leftPx = dx + normL * scaledW
            val topPx = dy + normT * scaledH
            val rightPx = (dx + normR * scaledW).coerceAtLeast(leftPx + 2f)
            val botPx = (dy + normB * scaledH).coerceAtLeast(topPx + 2f)
            if (rightPx <= 4f || botPx <= 4f || leftPx >= bmpWidth - 4f || topPx >= bmpHeight - 4f) continue

            val graphemes = PaddleOcrManager.splitIntoGraphemes(text)
            if (graphemes.isEmpty()) continue

            val lineIdx = linesOut.size
            val lineRectF = RectF(leftPx, topPx, rightPx, botPx)
            val pageBoundsF = RectF(dx, dy, dx + scaledW.coerceAtLeast(1f), dy + scaledH.coerceAtLeast(1f))
            linesOut.add(
                RecognizedLineUi(
                    text = text,
                    rect = Rect(
                        leftPx.roundToInt(),
                        topPx.roundToInt(),
                        rightPx.roundToInt(),
                        botPx.roundToInt()
                    ),
                    normRect = normalizeRectToPage(lineRectF, pageBoundsF)
                )
            )
            val boxW = (rightPx - leftPx).coerceAtLeast(2f)
            val weights = FloatArray(graphemes.size) { getFallbackGraphemeRelativeWeight(graphemes[it]) }
            val totalWeight = weights.sum().coerceAtLeast(0.001f)
            var curX = leftPx
            for (i in graphemes.indices) {
                val gW = boxW * (weights[i] / totalWeight)
                val cl = curX.coerceIn(leftPx, maxOf(leftPx, rightPx - 1f))
                val cr = if (i == graphemes.lastIndex) {
                    rightPx.coerceAtLeast(cl + 1f)
                } else {
                    (curX + gW).coerceIn(cl + 1f, maxOf(cl + 1f, rightPx))
                }
                val charRectF = RectF(cl, topPx, cr, botPx)
                charsOut.add(
                    RecognizedCharUi(
                        text = graphemes[i],
                        lineIndex = lineIdx,
                        rect = charRectF,
                        normRect = normalizeRectToPage(charRectF, pageBoundsF)
                    )
                )
                curX += gW
            }
        }
        if (charsOut.isNotEmpty()) {
            return linesOut to charsOut
        }
    }

    if (isHexLayout) {
        val hexDataLines = rawLines.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                    !trimmed.startsWith("HEX INSPECTOR", ignoreCase = true) &&
                    !trimmed.startsWith("OFFSET", ignoreCase = true) &&
                    !trimmed.startsWith("----")
        }
        if (hexDataLines.isEmpty()) return emptyList<RecognizedLineUi>() to emptyList()

        val linesOut = ArrayList<RecognizedLineUi>(hexDataLines.size)
        val charsOut = ArrayList<RecognizedCharUi>(rawPageText.length)

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 8.0f)
        val tableVirtualWidth = 80f * 14.2f + 56f
        val fitScale = (bmpWidth.toFloat() / tableVirtualWidth).coerceIn(0.55f, 2.2f)
        val effectiveScale = fitScale * zoom

        val lineHeight = 32f * effectiveScale
        val headerHeight = 76f * effectiveScale
        val padLeft = 24f * effectiveScale
        val charW = 13.8f * effectiveScale
        val tableContentWidth = 78f * charW + padLeft * 2f
        val totalTableW = max(bmpWidth.toFloat(), tableContentWidth)
        val totalTableH = headerHeight + hexDataLines.size * lineHeight + 16f * effectiveScale

        val maxEffX = (totalTableW - bmpWidth.toFloat()).coerceAtLeast(0f)
        val maxEffY = (totalTableH - bmpHeight.toFloat()).coerceAtLeast(0f)
        val effX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effY = tileY.toFloat().coerceIn(0f, maxEffY)

        val baseCenterX = if (tableContentWidth < bmpWidth) {
            (bmpWidth - tableContentWidth) / 2f
        } else {
            0f
        }
        val drawOriginX = baseCenterX - effX

        for ((rowIdx, lineStr) in hexDataLines.withIndex()) {
            val rawTopPx = headerHeight + rowIdx * lineHeight - effY
            val rawBotPx = rawTopPx + lineHeight
            if (rawBotPx <= headerHeight || rawTopPx >= bmpHeight.toFloat()) continue

            val topPx = rawTopPx.coerceIn(headerHeight, bmpHeight - 4f)
            val botPx = rawBotPx.coerceIn(topPx + 4f, bmpHeight.toFloat())
            val graphemes = PaddleOcrManager.splitIntoGraphemes(lineStr)
            if (graphemes.isEmpty()) continue

            val rawLeftPx = drawOriginX + padLeft
            val rawRightPx = rawLeftPx + graphemes.size * charW
            if (rawRightPx <= 0f || rawLeftPx >= bmpWidth.toFloat()) continue

            val leftPx = rawLeftPx.coerceIn(0f, bmpWidth - 4f)
            val rightPx = rawRightPx.coerceIn(leftPx + 4f, bmpWidth.toFloat())

            val hexPageBounds = RectF(
                drawOriginX,
                -effY,
                drawOriginX + totalTableW.coerceAtLeast(1f),
                -effY + totalTableH.coerceAtLeast(1f)
            )
            val lineIdx = linesOut.size
            val lineRectF = RectF(leftPx, topPx, rightPx, botPx)
            linesOut.add(
                RecognizedLineUi(
                    text = lineStr,
                    rect = Rect(
                        leftPx.roundToInt(),
                        topPx.roundToInt(),
                        rightPx.roundToInt(),
                        botPx.roundToInt()
                    ),
                    normRect = normalizeRectToPage(lineRectF, hexPageBounds)
                )
            )
            for (i in graphemes.indices) {
                val clRaw = rawLeftPx + i * charW
                val crRaw = clRaw + charW
                if (crRaw <= 0f || clRaw >= bmpWidth.toFloat()) continue
                val cl = clRaw.coerceIn(0f, bmpWidth - 2f)
                val cr = crRaw.coerceIn(cl + 1f, bmpWidth.toFloat())
                val charRectF = RectF(cl, topPx, cr, botPx)
                charsOut.add(
                    RecognizedCharUi(
                        text = graphemes[i],
                        lineIndex = lineIdx,
                        rect = charRectF,
                        normRect = normalizeRectToPage(charRectF, hexPageBounds)
                    )
                )
            }
        }
        return linesOut to charsOut
    }

    val linesClean = rawLines
        .map { line ->
            val m = PDF_NATIVE_BOUNDS_LINE_REGEX.matchEntire(line.trim())
            val extracted = m?.groupValues?.get(5) ?: line
            extracted.trim()
        }
        .filter { it.isNotEmpty() }
    if (linesClean.isEmpty()) return emptyList<RecognizedLineUi>() to emptyList()

    val linesOut = ArrayList<RecognizedLineUi>(linesClean.size)
    val charsOut = ArrayList<RecognizedCharUi>(rawPageText.length)

    val zoom = (zoomPermille / 1000f).coerceAtLeast(0.5f)
    val effDocW = if (docWidth > 0f) docWidth else bmpWidth.toFloat()
    val effDocH = if (docHeight > 0f) docHeight else bmpHeight.toFloat()
    val baseScale = minOf(bmpWidth.toFloat() / effDocW, bmpHeight.toFloat() / effDocH)
    val totalScale = baseScale * zoom
    val pageW = effDocW * totalScale
    val pageH = effDocH * totalScale
    val pageOriginX = (bmpWidth - pageW) / 2f - tileX.toFloat()
    val pageOriginY = (bmpHeight - pageH) / 2f - tileY.toFloat()

    val padX = (pageW * 0.07f).coerceAtLeast(20f)
    val padTop = (pageH * 0.07f).coerceAtLeast(24f)
    val availW = (pageW - padX * 2f).coerceAtLeast(64f)
    val availH = (pageH - padTop * 1.8f).coerceAtLeast(64f)
    val stepH = (availH / linesClean.size.coerceAtLeast(1)).coerceIn(14f * zoom, 68f * zoom)
    val boxH = (stepH * 0.82f).coerceAtLeast(12f)

    for ((rawIdx, lineStr) in linesClean.withIndex()) {
        val rawTopPx = pageOriginY + padTop + rawIdx * stepH
        val rawBotPx = rawTopPx + boxH
        if (rawBotPx < 0f || rawTopPx > bmpHeight.toFloat()) continue

        val topPx = rawTopPx.coerceIn(0f, bmpHeight - 6f)
        val botPx = rawBotPx.coerceIn(topPx + 4f, bmpHeight.toFloat())
        val graphemes = PaddleOcrManager.splitIntoGraphemes(lineStr)
        if (graphemes.isEmpty()) continue

        val estCharW = (availW / maxOf(graphemes.size, 24)).coerceIn(7f * zoom, 32f * zoom)
        val lineW = (graphemes.size * estCharW).coerceAtMost(availW)
        val rawLeftPx = pageOriginX + padX
        val rawRightPx = rawLeftPx + lineW
        if (rawRightPx < 0f || rawLeftPx > bmpWidth.toFloat()) continue

        val leftPx = rawLeftPx.coerceIn(0f, bmpWidth - 4f)
        val rightPx = rawRightPx.coerceIn(leftPx + 4f, bmpWidth.toFloat())

        val genericPageBounds = RectF(
            pageOriginX,
            pageOriginY,
            pageOriginX + pageW.coerceAtLeast(1f),
            pageOriginY + pageH.coerceAtLeast(1f)
        )
        val lineIdx = linesOut.size
        val lineRectF = RectF(leftPx, topPx, rightPx, botPx)
        linesOut.add(
            RecognizedLineUi(
                text = lineStr,
                rect = Rect(
                    leftPx.roundToInt(),
                    topPx.roundToInt(),
                    rightPx.roundToInt(),
                    botPx.roundToInt()
                ),
                normRect = normalizeRectToPage(lineRectF, genericPageBounds)
            )
        )

        val charStep = (rightPx - leftPx) / graphemes.size.coerceAtLeast(1)
        for (i in graphemes.indices) {
            val cl = leftPx + i * charStep
            val cr = (cl + charStep).coerceAtLeast(cl + 1f)
            val charRectF = RectF(cl, topPx, cr, botPx)
            charsOut.add(
                RecognizedCharUi(
                    text = graphemes[i],
                    lineIndex = lineIdx,
                    rect = charRectF,
                    normRect = normalizeRectToPage(charRectF, genericPageBounds)
                )
            )
        }
    }
    return linesOut to charsOut
}

internal fun stripBoundedNativeMarkers(rawPageText: String): String {
    if (!rawPageText.contains("[PDF_BOX:")) return rawPageText
    return rawPageText.lineSequence()
        .map { line ->
            val m = PDF_NATIVE_BOUNDS_LINE_REGEX.matchEntire(line.trim())
            m?.groupValues?.get(5) ?: line
        }
        .joinToString("\n")
}

internal fun parseStructuredReflowDocument(
    rawFullText: String,
    baseFontSizeSp: Float,
    normalTextColor: Color,
    accentHeadingColor: Color,
    isHtmlSource: Boolean = false
): StructuredReflowDocument {
    if (rawFullText.isEmpty()) {
        return StructuredReflowDocument(
            annotatedText = AnnotatedString(""),
            cleanText = "",
            tableRows = emptyList(),
            imageAnchors = emptyList()
        )
    }

    val isHtmlDetected = isHtmlSource || rawFullText.contains("<!DOCTYPE", ignoreCase = true) || rawFullText.contains("<html", ignoreCase = true)

    val rawLines = rawFullText.split('\n')
    val lines = ArrayList<String>(rawLines.size)
    var blankStreak = 0
    for (l in rawLines) {
        if (l.isBlank()) {
            blankStreak++
            if (blankStreak <= 1) {
                lines.add("")
            }
        } else {
            blankStreak = 0
            lines.add(l)
        }
    }

    val tableRowsMeta = ArrayList<TableRowLayoutMeta>()
    val imageAnchors = ArrayList<ImageAnchorMeta>()
    var consecutiveTableRowCount = 0
    val plainBuilder = StringBuilder(rawFullText.length)

    val annotated = buildAnnotatedString {
        var isFirstEmittedLine = true

        for (i in lines.indices) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()

            val imgMatch = REFLOW_IMAGE_MARKER_REGEX.find(trimmed)
            if (imgMatch != null) {
                val imgIdx = imgMatch.groupValues[1].toIntOrNull() ?: 0
                imageAnchors.add(ImageAnchorMeta(plainBuilder.length, imgIdx))
                continue
            }

            if (trimmed.isEmpty()) {
                val nextNonBlank = lines.getOrNull(i + 1)?.trim() ?: ""
                val isNextHeadingOrTable = nextNonBlank.startsWith("<h", ignoreCase = true) ||
                        nextNonBlank.startsWith("#") ||
                        (!isHtmlDetected && nextNonBlank.contains('\t'))
                if (!isNextHeadingOrTable) {
                    continue
                }
            }

            if (!isFirstEmittedLine) {
                append('\n')
                plainBuilder.append('\n')
            }
            isFirstEmittedLine = false

            var workLine = rawLine
            var headingLevel = 0

            if (!isHtmlDetected) {
                val htmlHeading = REFLOW_HTML_HEADING_REGEX.find(trimmed)
                if (htmlHeading != null) {
                    headingLevel = htmlHeading.groupValues[1].drop(1).toIntOrNull()?.coerceIn(1, 6) ?: 1
                    workLine = htmlHeading.groupValues[2]
                } else if (trimmed.startsWith("#")) {
                    val hashes = trimmed.takeWhile { it == '#' }.length
                    if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                        headingLevel = hashes
                        workLine = trimmed.substring(hashes + 1)
                    }
                }
            }

            val lineStartOffset = plainBuilder.length
            val isTableLine = !isHtmlDetected && workLine.contains('\t')

            if (isTableLine) {
                val cells = workLine.split('\t').map { it.trim() }.dropLastWhile { it.isEmpty() }
                val sepOffsets = ArrayList<Int>()
                val isHeader = (consecutiveTableRowCount == 0)
                consecutiveTableRowCount++

                for (cIdx in cells.indices) {
                    if (cIdx > 0) {
                        append("  ")
                        plainBuilder.append("  ")
                        val pipeOffset = plainBuilder.length
                        sepOffsets.add(pipeOffset)
                        withStyle(SpanStyle(color = Color.Transparent)) {
                            append("│")
                        }
                        plainBuilder.append("│")
                        append("  ")
                        plainBuilder.append("  ")
                    }
                    val cellStart = plainBuilder.length
                    appendFormattedInlineSegments(cells[cIdx], plainBuilder, baseFontSizeSp)
                    val cellEnd = plainBuilder.length
                    if (isHeader && cellEnd > cellStart) {
                        addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start = cellStart,
                            end = cellEnd
                        )
                    }
                }
                val lineEndOffset = plainBuilder.length
                if (lineEndOffset > lineStartOffset) {
                    tableRowsMeta.add(
                        TableRowLayoutMeta(
                            charStart = lineStartOffset,
                            charEndExclusive = lineEndOffset,
                            isHeaderRow = isHeader,
                            separatorOffsets = sepOffsets
                        )
                    )
                }
            } else {
                consecutiveTableRowCount = 0
                appendFormattedInlineSegments(workLine, plainBuilder, baseFontSizeSp)
                val lineEndOffset = plainBuilder.length

                if (lineEndOffset > lineStartOffset) {
                    if (headingLevel in 1..6) {
                        val scale = when (headingLevel) {
                            1 -> 1.30f
                            2 -> 1.20f
                            3 -> 1.12f
                            4 -> 1.06f
                            5 -> 1.03f
                            else -> 1.01f
                        }
                        addStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = (baseFontSizeSp * scale).sp,
                                color = if (headingLevel <= 2) accentHeadingColor else normalTextColor
                            ),
                            start = lineStartOffset,
                            end = lineEndOffset
                        )
                    } else if (trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("◦ ")) {
                        val bulletEnd = (lineStartOffset + 2).coerceAtMost(lineEndOffset)
                        addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold, color = accentHeadingColor),
                            start = lineStartOffset,
                            end = bulletEnd
                        )
                    }
                }
            }
        }
    }

    return StructuredReflowDocument(
        annotatedText = annotated,
        cleanText = plainBuilder.toString(),
        tableRows = tableRowsMeta,
        imageAnchors = imageAnchors
    )
}

private fun AnnotatedString.Builder.appendFormattedInlineSegments(
    rawSegment: String,
    plainOut: StringBuilder,
    baseFontSizeSp: Float = BASE_REFLOW_FONT_SIZE_SP
) {
    if (!rawSegment.contains('<')) {
        append(rawSegment)
        plainOut.append(rawSegment)
        return
    }
    var pos = 0
    var boldStart = -1
    var italicStart = -1
    var underlineStart = -1
    var strikeStart = -1
    var subSupStart = -1

    for (m in REFLOW_INLINE_TAG_REGEX.findAll(rawSegment)) {
        if (m.range.first > pos) {
            val sub = rawSegment.substring(pos, m.range.first)
            append(sub)
            plainOut.append(sub)
        }
        val isClosing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase(Locale.ROOT)
        when (tag) {
            "b", "strong" -> {
                if (!isClosing && boldStart < 0) {
                    boldStart = plainOut.length
                } else if (isClosing && boldStart in 0 until plainOut.length) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), boldStart, plainOut.length)
                    boldStart = -1
                }
            }
            "i", "em" -> {
                if (!isClosing && italicStart < 0) {
                    italicStart = plainOut.length
                } else if (isClosing && italicStart in 0 until plainOut.length) {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), italicStart, plainOut.length)
                    italicStart = -1
                }
            }
            "u" -> {
                if (!isClosing && underlineStart < 0) {
                    underlineStart = plainOut.length
                } else if (isClosing && underlineStart in 0 until plainOut.length) {
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), underlineStart, plainOut.length)
                    underlineStart = -1
                }
            }
            "s", "del", "strike" -> {
                if (!isClosing && strikeStart < 0) {
                    strikeStart = plainOut.length
                } else if (isClosing && strikeStart in 0 until plainOut.length) {
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), strikeStart, plainOut.length)
                    strikeStart = -1
                }
            }
            "sub", "sup" -> {
                if (!isClosing && subSupStart < 0) {
                    subSupStart = plainOut.length
                } else if (isClosing && subSupStart in 0 until plainOut.length) {
                    addStyle(SpanStyle(fontSize = (baseFontSizeSp * 0.82f).sp), subSupStart, plainOut.length)
                    subSupStart = -1
                }
            }
        }
        pos = m.range.last + 1
    }
    if (pos < rawSegment.length) {
        val tail = rawSegment.substring(pos)
        append(tail)
        plainOut.append(tail)
    }
}

internal fun resolveMediaPlaybackSession(session: DocumentSession): MediaPlaybackSession? {
    if (session is MediaPlaybackSession) return session
    return try {
        session.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            field.isAccessible = true
            field.get(session) as? MediaPlaybackSession
        }
    } catch (_: Throwable) {
        null
    }
}

private fun buildSnippetAroundMatch(
    source: String,
    matchStart: Int,
    matchEndExclusive: Int,
    radiusChars: Int = 28
): Triple<String, String, String> {
    val safeStart = matchStart.coerceIn(0, source.length)
    val safeEnd = matchEndExclusive.coerceIn(safeStart, source.length)
    val prefixStart = (safeStart - radiusChars).coerceAtLeast(0)
    val suffixEnd = (safeEnd + radiusChars).coerceAtMost(source.length)

    val rawPrefix = source.substring(prefixStart, safeStart)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
    val matched = source.substring(safeStart, safeEnd)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
    val rawSuffix = source.substring(safeEnd, suffixEnd)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')

    val prefix = (if (prefixStart > 0) "…" else "") + rawPrefix.trimStart()
    val suffix = rawSuffix.trimEnd() + (if (suffixEnd < source.length) "…" else "")
    return Triple(prefix, matched, suffix)
}

internal suspend fun collectDocumentSearchMatches(
    query: String,
    useReflowLayout: Boolean,
    fullReflowText: String,
    viewportPageRanges: List<IntRange>,
    totalPages: Int,
    isHexSession: Boolean,
    isSpreadsheet: Boolean = false,
    sheetNames: List<String> = emptyList(),
    session: DocumentSession
): List<SearchMatchItem> {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return emptyList()

    val results = ArrayList<SearchMatchItem>(32)

    if (useReflowLayout && fullReflowText.isNotEmpty()) {
        val ranges = viewportPageRanges.ifEmpty { listOf(0 until fullReflowText.length) }
        var searchPos = 0
        var lastPageIdx = -1
        var occurrenceOnCurrentPage = 0

        while (searchPos < fullReflowText.length && results.size < MAX_SEARCH_MATCHES) {
            coroutineContext.ensureActive()
            val foundIdx = fullReflowText.indexOf(cleanQuery, searchPos, ignoreCase = true)
            if (foundIdx < 0) break
            val matchEnd = (foundIdx + cleanQuery.length).coerceAtMost(fullReflowText.length)

            val pageIdx = ranges.indexOfFirst { foundIdx in it }.let { idx ->
                if (idx >= 0) idx else (ranges.size - 1).coerceAtLeast(0)
            }
            if (pageIdx != lastPageIdx) {
                lastPageIdx = pageIdx
                occurrenceOnCurrentPage = 0
            } else {
                occurrenceOnCurrentPage++
            }

            val pageRange = ranges.getOrElse(pageIdx) { 0 until fullReflowText.length }
            val relStartInPage = (foundIdx - pageRange.first).coerceAtLeast(0)
            val relEndInPage = (matchEnd - pageRange.first).coerceAtLeast(relStartInPage)
            val (prefix, matched, suffix) = buildSnippetAroundMatch(fullReflowText, foundIdx, matchEnd)

            results.add(
                SearchMatchItem(
                    pageIndex = pageIdx,
                    occurrenceIndexOnPage = occurrenceOnCurrentPage,
                    charStartInPage = relStartInPage,
                    charEndExclusiveInPage = relEndInPage,
                    contextPrefix = prefix,
                    matchedText = matched,
                    contextSuffix = suffix
                )
            )
            searchPos = matchEnd
        }
        return results
    }

    if (isSpreadsheet) {
        val originalSheet = try { session.getActiveSheetIndex() } catch (_: Throwable) { 0 }
        val sheetCount = sheetNames.size.coerceAtLeast(1)
        try {
            for (sIdx in 0 until sheetCount) {
                coroutineContext.ensureActive()
                if (results.size >= MAX_SEARCH_MATCHES) break
                if (sheetCount > 1) {
                    try { session.setActiveSheetIndex(sIdx) } catch (_: Throwable) {}
                }
                val sheetTitle = sheetNames.getOrNull(sIdx) ?: ""
                val sheetPages = try { session.pageCount.coerceAtLeast(1) } catch (_: Throwable) { 1 }
                for (p in 0 until sheetPages) {
                    coroutineContext.ensureActive()
                    if (results.size >= MAX_SEARCH_MATCHES) break
                    val pText = try { session.getPageText(p) } catch (_: Throwable) { "" }
                    if (pText.isEmpty()) continue
                    val lines = pText.lines()
                    var rowOnPage = 0
                    for (line in lines) {
                        if (line.startsWith("=== ") && line.endsWith(" ===")) continue
                        if (line.isEmpty() && rowOnPage > 0) continue
                        val cells = line.split('\t')
                        for (cIdx in cells.indices) {
                            val cellStr = cells[cIdx]
                            val matchPos = cellStr.indexOf(cleanQuery, 0, ignoreCase = true)
                            if (matchPos >= 0) {
                                val matchEnd = (matchPos + cleanQuery.length).coerceAtMost(cellStr.length)
                                val (prefix, matched, suffix) = buildSnippetAroundMatch(cellStr, matchPos, matchEnd, 20)
                                val colLetters = SpreadsheetCoordinateUtils.columnIndexToExcelLetters(cIdx)
                                val globalRowNum = p * 200 + rowOnPage + 1
                                val cellRef = "$colLetters$globalRowNum"
                                val badge = if (sheetCount > 1 && sheetTitle.isNotEmpty()) {
                                    "$sheetTitle • $cellRef"
                                } else {
                                    cellRef
                                }
                                results.add(
                                    SearchMatchItem(
                                        pageIndex = p,
                                        occurrenceIndexOnPage = 0,
                                        charStartInPage = matchPos,
                                        charEndExclusiveInPage = matchEnd,
                                        contextPrefix = prefix,
                                        matchedText = matched,
                                        contextSuffix = suffix,
                                        sheetIndex = sIdx,
                                        cellRow = rowOnPage,
                                        cellCol = cIdx,
                                        customBadge = badge
                                    )
                                )
                                if (results.size >= MAX_SEARCH_MATCHES) break
                            }
                        }
                        rowOnPage++
                        if (results.size >= MAX_SEARCH_MATCHES) break
                    }
                }
            }
        } finally {
            if (sheetCount > 1) {
                try { session.setActiveSheetIndex(originalSheet) } catch (_: Throwable) {}
            }
        }
        return results
    }

    val normalizedHexSpaced = if (isHexSession) {
        val stripped = cleanQuery
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
        if (stripped.length >= 2 && stripped.length % 2 == 0 &&
            stripped.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            stripped.uppercase(Locale.ROOT).chunked(2).joinToString(" ")
        } else null
    } else null

    fun collectMatchesFromPageText(pageIdx: Int, rawPageText: String) {
        val pageText = stripBoundedNativeMarkers(rawPageText)
        if (pageText.isEmpty()) return
        val directMatches = pageText.indexOf(cleanQuery, 0, ignoreCase = true) >= 0
        val effectiveNeedles = if (!directMatches && normalizedHexSpaced != null &&
            pageText.indexOf(normalizedHexSpaced, 0, ignoreCase = true) >= 0
        ) {
            listOf(normalizedHexSpaced)
        } else {
            listOf(cleanQuery)
        }

        for (needle in effectiveNeedles) {
            var pos = 0
            var occ = 0
            while (pos < pageText.length && results.size < MAX_SEARCH_MATCHES) {
                val idx = pageText.indexOf(needle, pos, ignoreCase = true)
                if (idx < 0) break
                val endIdx = (idx + needle.length).coerceAtMost(pageText.length)
                val (prefix, matched, suffix) = buildSnippetAroundMatch(pageText, idx, endIdx)
                results.add(
                    SearchMatchItem(
                        pageIndex = pageIdx,
                        occurrenceIndexOnPage = occ++,
                        charStartInPage = idx,
                        charEndExclusiveInPage = endIdx,
                        contextPrefix = prefix,
                        matchedText = matched,
                        contextSuffix = suffix
                    )
                )
                pos = endIdx
            }
            if (results.isNotEmpty()) break
        }
    }

    if (totalPages <= 300 && !isHexSession) {
        for (p in 0 until totalPages) {
            coroutineContext.ensureActive()
            if (results.size >= MAX_SEARCH_MATCHES) break
            val pText = try { session.getPageText(p) } catch (_: Throwable) { "" }
            collectMatchesFromPageText(p, pText)
        }
    } else {
        var curStartPage = 0
        val visitedPages = HashSet<Int>()
        while (curStartPage < totalPages && results.size < MAX_SEARCH_MATCHES) {
            coroutineContext.ensureActive()
            val foundPageLong = try { session.search(cleanQuery, curStartPage) } catch (_: Throwable) { -1L }
            val foundPage = foundPageLong.toInt()
            if (foundPage < curStartPage || foundPage >= totalPages || !visitedPages.add(foundPage)) {
                break
            }
            val pText = try { session.getPageText(foundPage) } catch (_: Throwable) { "" }
            val beforeCount = results.size
            collectMatchesFromPageText(foundPage, pText)
            if (results.size == beforeCount) {
                val preview = stripBoundedNativeMarkers(pText).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "Page ${foundPage + 1}"
                results.add(
                    SearchMatchItem(
                        pageIndex = foundPage,
                        occurrenceIndexOnPage = 0,
                        charStartInPage = 0,
                        charEndExclusiveInPage = 0,
                        contextPrefix = "",
                        matchedText = cleanQuery,
                        contextSuffix = " — $preview"
                    )
                )
            }
            curStartPage = foundPage + 1
        }
    }

    return results
}
