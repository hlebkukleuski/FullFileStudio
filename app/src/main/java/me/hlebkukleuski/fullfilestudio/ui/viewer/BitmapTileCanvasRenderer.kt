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

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette

@Composable
internal fun BitmapTileCanvasViewport(
    flags: ViewerSessionFlags,
    gestureCallbacks: BitmapGestureCallbacks,
    activeBitmap: Bitmap?,
    previousPageBitmap: Bitmap?,
    renderVersion: Int,
    currentPage: Int,
    renderedPage: Int,
    renderedZoomPermille: Int,
    renderedTileX: Int,
    renderedTileY: Int,
    scale: Float,
    offset: Offset,
    pageTransitionProgress: Float,
    pageTransitionDirection: Int,
    activeSlideTransitionType: String,
    cachedDocWidth: Float,
    cachedDocHeight: Float,
    cachedColWidthsPx: FloatArray?,
    cachedRowHeightsPx: FloatArray?,
    activeSheetIndex: Int,
    activeSearchHighlightMinRenderVersion: Int,
    activeSearchHighlightSheet: Int,
    activeSearchHighlightRow: Int,
    activeSearchHighlightCol: Int,
    activeSearchHighlightPage: Int,
    activeSearchHighlightText: String,
    activeSearchHighlightOccurrence: Int,
    searchHighlightAlpha: Float,
    ocrLines: List<RecognizedLineUi>,
    ocrChars: List<RecognizedCharUi>,
    selStartChar: Int,
    selEndChar: Int,
    sheetSelStartRow: Int,
    sheetSelStartCol: Int,
    sheetSelEndRow: Int,
    sheetSelEndCol: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .viewerBitmapGestureInput(gestureCallbacks)
    ) {
        activeBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                try {
                    renderVersion.hashCode()
                    val screenWInt = size.width.toInt()
                    val screenHInt = size.height.toInt()
                    val transKind = if (flags.isPresentation) activeSlideTransitionType else "vertical_slide"

                    if (previousPageBitmap != null && !previousPageBitmap.isRecycled &&
                        pageTransitionProgress < 0.999f && !flags.is3D && !flags.isTableBoundedLayout
                    ) {
                        val prevOffX = when {
                            !flags.isPresentation -> 0
                            transKind in setOf("fade", "dissolve", "wipe", "split", "zoom", "cover") -> 0
                            else -> (-pageTransitionDirection * size.width * pageTransitionProgress).roundToInt()
                        }
                        val prevOffY = if (!flags.isPresentation) (-pageTransitionDirection * size.height * pageTransitionProgress).roundToInt() else 0
                        val prevAlpha = when (transKind) {
                            "fade", "dissolve" -> (1f - pageTransitionProgress).coerceIn(0f, 1f)
                            else -> (1f - pageTransitionProgress * 0.25f).coerceIn(0f, 1f)
                        }
                        drawImage(
                            image = previousPageBitmap.asImageBitmap(),
                            dstOffset = IntOffset(prevOffX, prevOffY),
                            dstSize = IntSize(screenWInt, screenHInt),
                            alpha = prevAlpha
                        )
                    }

                    val isLivePageMatch = (renderedPage == currentPage && !flags.is3D && !flags.isTableBoundedLayout)
                    val minRenderedScale = if (flags.isGraphicSession) 0.25f else 0.5f
                    val renderedScale = (renderedZoomPermille / 1000f).coerceAtLeast(minRenderedScale)
                    val liveScaleRatio = if (isLivePageMatch && abs(scale - renderedScale) > 0.005f) {
                        (scale / renderedScale).coerceIn(0.125f, 8.0f)
                    } else {
                        1f
                    }
                    val scaleX = size.width / bmp.width.toFloat().coerceAtLeast(1f)
                    val scaleY = size.height / bmp.height.toFloat().coerceAtLeast(1f)
                    val livePanDeltaX = if (isLivePageMatch) (offset.x + renderedTileX * liveScaleRatio) * scaleX else 0f
                    val livePanDeltaY = if (isLivePageMatch) (offset.y + renderedTileY * liveScaleRatio) * scaleY else 0f

                    val enterScaleFactor = if (pageTransitionProgress < 0.999f && flags.isPresentation && transKind == "zoom") {
                        0.78f + 0.22f * pageTransitionProgress
                    } else {
                        1f
                    }
                    val enterTransX = if (pageTransitionProgress < 0.999f && flags.isPresentation && transKind in setOf("push", "cover", "pull")) {
                        pageTransitionDirection * size.width * (1f - pageTransitionProgress)
                    } else {
                        0f
                    }
                    val enterTransY = if (pageTransitionProgress < 0.999f && !flags.isPresentation && !flags.is3D && !flags.isTableBoundedLayout) {
                        pageTransitionDirection * size.height * (1f - pageTransitionProgress)
                    } else {
                        0f
                    }

                    val dstW = (size.width * liveScaleRatio * enterScaleFactor).roundToInt().coerceAtLeast(1)
                    val dstH = (size.height * liveScaleRatio * enterScaleFactor).roundToInt().coerceAtLeast(1)
                    val dstX = ((size.width - dstW) / 2f + livePanDeltaX + enterTransX).roundToInt()
                    val dstY = ((size.height - dstH) / 2f + livePanDeltaY + enterTransY).roundToInt()

                    val dstOffset = IntOffset(dstX, dstY)
                    val dstSize = IntSize(dstW, dstH)

                    val enterAlpha = if (pageTransitionProgress < 0.999f && flags.isPresentation) {
                        when (transKind) {
                            "fade", "dissolve", "zoom" -> pageTransitionProgress.coerceIn(0f, 1f)
                            else -> (0.45f + 0.55f * pageTransitionProgress).coerceIn(0f, 1f)
                        }
                    } else {
                        1f
                    }

                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        alpha = enterAlpha
                    )

                    val offsetX = dstOffset.x.toFloat()
                    val offsetY = dstOffset.y.toFloat()
                    val effScaleX = dstSize.width.toFloat() / bmp.width.toFloat().coerceAtLeast(1f)
                    val effScaleY = dstSize.height.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                    val screenScaleX = size.width / bmp.width.toFloat().coerceAtLeast(1f)
                    val screenScaleY = size.height / bmp.height.toFloat().coerceAtLeast(1f)

                    val livePageBoundsBuf = if (!flags.isSpreadsheet && !flags.is3D) {
                        computeDocumentPageBoundsOnBuffer(
                            bufWidth = bmp.width.toFloat(),
                            bufHeight = bmp.height.toFloat(),
                            docWidth = cachedDocWidth,
                            docHeight = cachedDocHeight,
                            zoomPermille = (scale * 1000f).roundToInt(),
                            tileX = (-offset.x).roundToInt(),
                            tileY = (-offset.y).roundToInt(),
                            isHexLayout = flags.isHexSession,
                            hexLineCount = ocrLines.size.coerceAtLeast(1)
                        )
                    } else {
                        null
                    }

                    if (flags.isSpreadsheet &&
                        renderVersion >= activeSearchHighlightMinRenderVersion &&
                        searchHighlightAlpha > 0.005f &&
                        activeSearchHighlightSheet == activeSheetIndex &&
                        activeSearchHighlightRow >= 0 &&
                        activeSearchHighlightCol >= 0
                    ) {
                        val zoom = scale.coerceIn(0.5f, 4.0f)
                        val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                            docWidth = cachedDocWidth,
                            customColWidthsPx = cachedColWidthsPx,
                            minRequiredCols = activeSearchHighlightCol + 1
                        )
                        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                            colCount = colCount,
                            customColWidthsPx = cachedColWidthsPx,
                            viewportWidth = bmp.width.toFloat(),
                            zoom = zoom
                        )
                        val rawCellRect = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                            startRow = activeSearchHighlightRow,
                            startCol = activeSearchHighlightCol,
                            colOffsets = colOffsets,
                            zoom = zoom,
                            effScrollX = (-offset.x).coerceAtLeast(0f),
                            effScrollY = (-offset.y).coerceAtLeast(0f),
                            customRowHeightsPx = cachedRowHeightsPx
                        )
                        val headerW = SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom
                        val headerH = SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
                        val cellLeftBmp = rawCellRect.left.coerceAtLeast(headerW)
                        val cellTopBmp = rawCellRect.top.coerceAtLeast(headerH)
                        val cellRightBmp = rawCellRect.right.coerceAtMost(bmp.width.toFloat())
                        val cellBotBmp = rawCellRect.bottom.coerceAtMost(bmp.height.toFloat())

                        if (cellRightBmp > cellLeftBmp && cellBotBmp > cellTopBmp) {
                            drawRect(
                                color = Color(0xFFFFEB3B).copy(alpha = 0.45f * searchHighlightAlpha),
                                topLeft = Offset(offsetX + cellLeftBmp * effScaleX, offsetY + cellTopBmp * effScaleY),
                                size = Size(
                                    (cellRightBmp - cellLeftBmp) * effScaleX,
                                    (cellBotBmp - cellTopBmp) * effScaleY
                                )
                            )
                        }
                    } else if (!flags.isSpreadsheet &&
                        renderVersion >= activeSearchHighlightMinRenderVersion &&
                        activeSearchHighlightPage == currentPage &&
                        searchHighlightAlpha > 0.005f &&
                        activeSearchHighlightText.isNotEmpty() &&
                        ocrChars.isNotEmpty()
                    ) {
                        val matchedCharRange = findOcrSearchHighlightRange(
                            ocrChars = ocrChars,
                            query = activeSearchHighlightText,
                            occurrenceIndex = activeSearchHighlightOccurrence
                        )
                        if (matchedCharRange != null && !matchedCharRange.isEmpty() && livePageBoundsBuf != null) {
                            val matchedChars = ocrChars.subList(
                                matchedCharRange.first.coerceIn(0, ocrChars.lastIndex),
                                (matchedCharRange.last + 1).coerceIn(1, ocrChars.size)
                            )
                            matchedChars.groupBy { it.lineIndex }.values.forEach { charsInLine ->
                                val nonBlankInLine = charsInLine.filter { it.text.isNotBlank() }.ifEmpty { charsInLine }
                                val lineScreenRects = nonBlankInLine.map {
                                    projectNormRectToScreen(it.normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                                }
                                val sLeft = lineScreenRects.minOf { it.left }
                                val sRight = lineScreenRects.maxOf { it.right }
                                val sTop = lineScreenRects.minOf { it.top }
                                val sBot = lineScreenRects.maxOf { it.bottom }
                                drawRoundRect(
                                    color = Color(0xFFFFEB3B).copy(alpha = 0.45f * searchHighlightAlpha),
                                    topLeft = Offset(sLeft, sTop),
                                    size = Size((sRight - sLeft).coerceAtLeast(4f), (sBot - sTop).coerceAtLeast(4f)),
                                    cornerRadius = CornerRadius(4.dp.toPx())
                                )
                            }
                        }
                    }

                    if (flags.isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0) {
                        val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
                        val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
                        val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
                        val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)

                        val zoom = scale.coerceIn(0.5f, 4.0f)
                        val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                            docWidth = cachedDocWidth,
                            customColWidthsPx = cachedColWidthsPx,
                            minRequiredCols = c1 + 1
                        )
                        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                            colCount = colCount,
                            customColWidthsPx = cachedColWidthsPx,
                            viewportWidth = bmp.width.toFloat(),
                            zoom = zoom
                        )
                        val rawCellRect = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                            startRow = r0, startCol = c0, endRow = r1, endCol = c1,
                            colOffsets = colOffsets, zoom = zoom,
                            effScrollX = (-offset.x).coerceAtLeast(0f),
                            effScrollY = (-offset.y).coerceAtLeast(0f),
                            customRowHeightsPx = cachedRowHeightsPx
                        )
                        val headerW = SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom
                        val headerH = SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
                        val cLeftBmp = rawCellRect.left.coerceAtLeast(headerW)
                        val cTopBmp = rawCellRect.top.coerceAtLeast(headerH)
                        val cRightBmp = rawCellRect.right.coerceAtMost(bmp.width.toFloat())
                        val cBotBmp = rawCellRect.bottom.coerceAtMost(bmp.height.toFloat())

                        if (cRightBmp > cLeftBmp && cBotBmp > cTopBmp) {
                            val sLeft = offsetX + cLeftBmp * effScaleX
                            val sTop = offsetY + cTopBmp * effScaleY
                            val sRight = offsetX + cRightBmp * effScaleX
                            val sBot = offsetY + cBotBmp * effScaleY
                            val handleColor = Color(0xFF00E676)
                            val handleRadius = 9.dp.toPx()

                            drawRect(
                                color = Color(0x5500E676),
                                topLeft = Offset(sLeft, sTop),
                                size = Size(sRight - sLeft, sBot - sTop)
                            )
                            drawRect(
                                color = handleColor,
                                topLeft = Offset(sLeft, sTop),
                                size = Size(sRight - sLeft, sBot - sTop),
                                style = Stroke(width = 2.5f.dp.toPx())
                            )
                            drawCircle(handleColor, handleRadius, Offset(sLeft, sBot))
                            drawCircle(handleColor, handleRadius, Offset(sRight, sBot))
                        }
                    }

                    if (flags.isGraphicSession && selStartChar < 0 && ocrLines.isNotEmpty() && livePageBoundsBuf != null) {
                        for (lineUi in ocrLines) {
                            val sr = projectNormRectToScreen(lineUi.normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                            drawRoundRect(
                                color = palette.accent.copy(alpha = 0.22f),
                                topLeft = Offset(sr.left, sr.top),
                                size = Size((sr.right - sr.left).coerceAtLeast(4f), (sr.bottom - sr.top).coerceAtLeast(4f)),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }

                    if (!flags.isSpreadsheet && selStartChar >= 0 && selEndChar >= 0 && ocrChars.isNotEmpty() && livePageBoundsBuf != null) {
                        val minC = minOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
                        val maxC = maxOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
                        val selectedChars = ocrChars.subList(minC, maxC + 1)

                        selectedChars.groupBy { it.lineIndex }.values.forEach { charsInLine ->
                            val lineScreenRects = charsInLine.map {
                                projectNormRectToScreen(it.normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                            }
                            val sLeft = lineScreenRects.minOf { it.left }
                            val sRight = lineScreenRects.maxOf { it.right }
                            val sTop = lineScreenRects.minOf { it.top }
                            val sBot = lineScreenRects.maxOf { it.bottom }
                            drawRoundRect(
                                color = Color(0x6600E676),
                                topLeft = Offset(sLeft, sTop),
                                size = Size((sRight - sLeft).coerceAtLeast(4f), (sBot - sTop).coerceAtLeast(4f)),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }

                        val startScreenRect = projectNormRectToScreen(ocrChars[minC].normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                        val endScreenRect = projectNormRectToScreen(ocrChars[maxC].normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                        val handleRadius = 9.dp.toPx()
                        val handleColor = Color(0xFF00E676)

                        val startX = startScreenRect.left
                        val startY = startScreenRect.bottom
                        drawCircle(color = handleColor, radius = handleRadius, center = Offset(startX - handleRadius, startY + handleRadius))
                        val leftPath = Path().apply {
                            moveTo(startX, startY)
                            lineTo(startX - handleRadius * 2f, startY + handleRadius)
                            lineTo(startX - handleRadius, startY + handleRadius * 2f)
                            close()
                        }
                        drawPath(path = leftPath, color = handleColor)

                        val endX = endScreenRect.right
                        val endY = endScreenRect.bottom
                        drawCircle(color = handleColor, radius = handleRadius, center = Offset(endX + handleRadius, endY + handleRadius))
                        val rightPath = Path().apply {
                            moveTo(endX, endY)
                            lineTo(endX + handleRadius * 2f, endY + handleRadius)
                            lineTo(endX + handleRadius, endY + handleRadius * 2f)
                            close()
                        }
                        drawPath(path = rightPath, color = handleColor)
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}

private fun findOcrSearchHighlightRange(
    ocrChars: List<RecognizedCharUi>,
    query: String,
    occurrenceIndex: Int
): IntRange? {
    val totalCodeUnits = ocrChars.sumOf { it.text.length.coerceAtLeast(1) } + ocrChars.size * 2 + 8
    val charIndexByCodeUnit = IntArray(totalCodeUnits)
    val fullOcrSb = StringBuilder(totalCodeUnits)
    val spacelessIndexByCodeUnit = IntArray(totalCodeUnits)
    val spacelessSb = StringBuilder(totalCodeUnits)

    var prevLineIdx = -1
    var prevRight = -1f
    var prevWasSpace = false

    for (cIdx in ocrChars.indices) {
        val chObj = ocrChars[cIdx]
        val isBlankToken = chObj.text.isBlank()
        if (prevLineIdx != -1 && chObj.lineIndex != prevLineIdx && fullOcrSb.isNotEmpty() && !prevWasSpace) {
            charIndexByCodeUnit[fullOcrSb.length] = cIdx
            fullOcrSb.append(' ')
            prevWasSpace = true
        } else if (prevLineIdx == chObj.lineIndex && prevRight >= 0f && !prevWasSpace && !isBlankToken) {
            val avgH = chObj.rect.height().coerceAtLeast(8f)
            if (chObj.rect.left - prevRight > avgH * 0.30f) {
                charIndexByCodeUnit[fullOcrSb.length] = cIdx
                fullOcrSb.append(' ')
                prevWasSpace = true
            }
        }

        if (isBlankToken) {
            if (!prevWasSpace && fullOcrSb.isNotEmpty()) {
                charIndexByCodeUnit[fullOcrSb.length] = cIdx
                fullOcrSb.append(' ')
                prevWasSpace = true
            }
        } else {
            val g = chObj.text
            for (k in g.indices) {
                charIndexByCodeUnit[fullOcrSb.length] = cIdx
                fullOcrSb.append(g[k])
                spacelessIndexByCodeUnit[spacelessSb.length] = cIdx
                spacelessSb.append(g[k])
            }
            prevWasSpace = false
        }
        prevLineIdx = chObj.lineIndex
        prevRight = chObj.rect.right
    }
    val fullOcrStr = fullOcrSb.toString()

    var searchPos = 0
    var curOcc = 0
    var matchedCharRange: IntRange? = null
    while (searchPos < fullOcrStr.length) {
        val found = fullOcrStr.indexOf(query, searchPos, ignoreCase = true)
        if (found < 0) break
        val endExc = (found + query.length).coerceAtMost(fullOcrStr.length)
        val startCharIdx = charIndexByCodeUnit[found]
        val endCharIdx = charIndexByCodeUnit[(endExc - 1).coerceAtLeast(found)]
        matchedCharRange = startCharIdx..endCharIdx
        if (curOcc == occurrenceIndex) break
        curOcc++
        searchPos = endExc
    }

    if (matchedCharRange == null) {
        val cleanQueryNoSpaces = query.filter { !it.isWhitespace() }
        val spacelessStr = spacelessSb.toString()
        if (cleanQueryNoSpaces.isNotEmpty() && spacelessStr.isNotEmpty()) {
            var spPos = 0
            var spOcc = 0
            while (spPos < spacelessStr.length) {
                val found = spacelessStr.indexOf(cleanQueryNoSpaces, spPos, ignoreCase = true)
                if (found < 0) break
                val endExc = (found + cleanQueryNoSpaces.length).coerceAtMost(spacelessStr.length)
                matchedCharRange = spacelessIndexByCodeUnit[found]..spacelessIndexByCodeUnit[(endExc - 1).coerceAtLeast(found)]
                if (spOcc == occurrenceIndex) break
                spOcc++
                spPos = endExc
            }
        }
    }
    return matchedCharRange
}
