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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette

@Composable
internal fun ReflowDocumentViewport(
    session: DocumentSession,
    structuredDoc: StructuredReflowDocument,
    currentRange: IntRange,
    pageText: String,
    previousReflowText: AnnotatedString?,
    pageTableRows: List<TableRowLayoutMeta>,
    reflowTextStyle: TextStyle,
    normalReflowTextColor: Color,
    currentPage: Int,
    totalPages: Int,
    pageTransitionProgress: Float,
    pageTransitionDirection: Int,
    activeBitmap: Bitmap?,
    renderVersion: Int,
    activeReflowIllustrationIndex: Int,
    activeReflowIllustrationIndices: List<Int>,
    selectedReflowIllustrationPos: Int,
    onSelectIllustrationPos: (Int) -> Unit,
    activeSearchHighlightPage: Int,
    activeSearchHighlightStart: Int,
    activeSearchHighlightEnd: Int,
    searchHighlightAlpha: Float,
    textLayoutResult: TextLayoutResult?,
    onTextLayout: (TextLayoutResult) -> Unit,
    textSelStart: Int,
    textSelEnd: Int,
    onUpdateTextSelection: (Int, Int) -> Unit,
    onUpdateDragPoint: (Offset?, Offset?) -> Unit,
    fontScaleMultiplier: Float,
    onUpdateFontScale: (Float) -> Unit,
    lastTapTimeMs: Long,
    lastTapPosition: Offset,
    onUpdateLastTap: (Long, Offset) -> Unit,
    isSearchDropdownExpanded: Boolean,
    onDismissSearchDropdown: () -> Unit,
    onCopySelection: (String) -> Unit,
    onNavigateToPage: (Int) -> Unit,
    paddingHorizPx: Float,
    paddingVertPx: Float,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val textToolbar = LocalTextToolbar.current

    val latestPageText by rememberUpdatedState(pageText)
    val latestPageTableRows by rememberUpdatedState(pageTableRows)
    val latestTotalPages by rememberUpdatedState(totalPages)
    val latestTextLayout by rememberUpdatedState(textLayoutResult)
    val latestTextSelStart by rememberUpdatedState(textSelStart)
    val latestTextSelEnd by rememberUpdatedState(textSelEnd)
    val latestFontScale by rememberUpdatedState(fontScaleMultiplier)
    val latestLastTapMs by rememberUpdatedState(lastTapTimeMs)
    val latestLastTapPos by rememberUpdatedState(lastTapPosition)
    val latestDropdownExpanded by rememberUpdatedState(isSearchDropdownExpanded)

    Box(modifier = modifier.fillMaxSize()) {
        if (previousReflowText != null && pageTransitionProgress < 0.999f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -pageTransitionDirection * size.height * pageTransitionProgress
                        alpha = (1f - pageTransitionProgress * 0.35f).coerceIn(0f, 1f)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = previousReflowText,
                    style = reflowTextStyle,
                    softWrap = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = if (pageTransitionProgress < 0.999f) {
                        pageTransitionDirection * size.height * (1f - pageTransitionProgress)
                    } else {
                        0f
                    }
                }
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            if (activeReflowIllustrationIndex >= 0 && activeBitmap != null && !activeBitmap.isRecycled) {
                renderVersion.hashCode()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(204.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceElevated)
                        .border(1.dp, palette.border, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = "Document Illustration",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )

                    if (activeReflowIllustrationIndices.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(palette.surface.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "◀",
                                color = palette.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    onSelectIllustrationPos(
                                        (selectedReflowIllustrationPos - 1 + activeReflowIllustrationIndices.size) %
                                            activeReflowIllustrationIndices.size
                                    )
                                }
                            )
                            Text(
                                text = "🖼 ${selectedReflowIllustrationPos + 1}/${activeReflowIllustrationIndices.size}",
                                color = palette.primaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "▶",
                                color = palette.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    onSelectIllustrationPos(
                                        (selectedReflowIllustrationPos + 1) % activeReflowIllustrationIndices.size
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .drawWithContent {
                        val layout = latestTextLayout
                        val curText = latestPageText
                        val tableRows = latestPageTableRows

                        if (layout != null && curText.isNotEmpty() && tableRows.isNotEmpty()) {
                            val tableHeaderBg = if (palette.isDark) Color(0xFF232638) else Color(0xFFE6EBF4)
                            val tableCellBg = if (palette.isDark) Color(0xFF1A1B26) else Color(0xFFFDFEFF)
                            val tableBorderCol = palette.border

                            for (rowMeta in tableRows) {
                                val sOff = rowMeta.charStart.coerceIn(0, curText.length - 1)
                                val eOff = (rowMeta.charEndExclusive - 1).coerceIn(sOff, curText.length - 1)
                                val startLine = layout.getLineForOffset(sOff)
                                val endLine = layout.getLineForOffset(eOff)
                                val rowTop = (layout.getLineTop(startLine) - 2f).coerceAtLeast(0f)
                                val rowBot = layout.getLineBottom(endLine) + 2f
                                val rowWidth = size.width

                                drawRect(
                                    color = if (rowMeta.isHeaderRow) tableHeaderBg else tableCellBg,
                                    topLeft = Offset(0f, rowTop),
                                    size = Size(rowWidth, (rowBot - rowTop).coerceAtLeast(4f))
                                )
                                drawRect(
                                    color = tableBorderCol,
                                    topLeft = Offset(0f, rowTop),
                                    size = Size(rowWidth, (rowBot - rowTop).coerceAtLeast(4f)),
                                    style = Stroke(width = 1.5f)
                                )

                                for (sepOff in rowMeta.separatorOffsets) {
                                    if (sepOff in 0 until curText.length) {
                                        val sepBox = layout.getBoundingBox(sepOff)
                                        val sepX = sepBox.center.x
                                        drawLine(
                                            color = tableBorderCol,
                                            start = Offset(sepX, rowTop),
                                            end = Offset(sepX, rowBot),
                                            strokeWidth = 1.5f
                                        )
                                    }
                                }
                            }
                        }

                        if (activeSearchHighlightPage == currentPage &&
                            searchHighlightAlpha > 0.005f &&
                            activeSearchHighlightStart >= 0 &&
                            activeSearchHighlightEnd > activeSearchHighlightStart &&
                            layout != null &&
                            curText.isNotEmpty()
                        ) {
                            val hStart = activeSearchHighlightStart.coerceIn(0, curText.length - 1)
                            val hEnd = activeSearchHighlightEnd.coerceIn(hStart + 1, curText.length)
                            val highlightPath = layout.getPathForRange(hStart, hEnd)
                            val bgTargetColor = lerp(palette.canvasBackground, Color(0xFFFFEB3B), searchHighlightAlpha)
                            drawPath(path = highlightPath, color = bgTargetColor)
                        }

                        drawContent()

                        if (textSelStart >= 0 && textSelEnd >= 0 && layout != null && curText.isNotEmpty()) {
                            val minO = minOf(textSelStart, textSelEnd).coerceIn(0, curText.length - 1)
                            val maxO = maxOf(textSelStart, textSelEnd).coerceIn(minO, curText.length - 1)

                            val selPath = layout.getPathForRange(minO, (maxO + 1).coerceAtMost(curText.length))
                            drawPath(selPath, Color(0x6600E676))

                            val handleRadius = 9.dp.toPx()
                            val handleColor = Color(0xFF00E676)

                            val startBox = layout.getBoundingBox(minO)
                            val endBox = layout.getBoundingBox(maxO)

                            val startX = startBox.left
                            val startY = startBox.bottom
                            drawCircle(color = handleColor, radius = handleRadius, center = Offset(startX - handleRadius, startY + handleRadius))
                            val leftPath = Path().apply {
                                moveTo(startX, startY)
                                lineTo(startX - handleRadius * 2f, startY + handleRadius)
                                lineTo(startX - handleRadius, startY + handleRadius * 2f)
                                close()
                            }
                            drawPath(path = leftPath, color = handleColor)

                            val endX = endBox.right
                            val endY = endBox.bottom
                            drawCircle(color = handleColor, radius = handleRadius, center = Offset(endX + handleRadius, endY + handleRadius))
                            val rightPath = Path().apply {
                                moveTo(endX, endY)
                                lineTo(endX + handleRadius * 2f, endY + handleRadius)
                                lineTo(endX + handleRadius, endY + handleRadius * 2f)
                                close()
                            }
                            drawPath(path = rightPath, color = handleColor)
                        }
                    }
                    .pointerInput(session) {
                        val handleHitRadiusPx = 38.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val layout = latestTextLayout
                            val curText = latestPageText
                            val hadPreviousSelection = latestTextSelStart >= 0 && latestTextSelEnd >= 0

                            var isDraggingStart = false
                            var isDraggingEnd = false
                            var isLongPress = false

                            if (layout != null && hadPreviousSelection && curText.isNotEmpty()) {
                                val minO = minOf(latestTextSelStart, latestTextSelEnd).coerceIn(0, curText.length - 1)
                                val maxO = maxOf(latestTextSelStart, latestTextSelEnd).coerceIn(minO, curText.length - 1)
                                val startBox = layout.getBoundingBox(minO)
                                val endBox = layout.getBoundingBox(maxO)

                                val leftHandlePos = Offset(startBox.left, startBox.bottom)
                                val rightHandlePos = Offset(endBox.right, endBox.bottom)

                                val distLeft = (downPos - leftHandlePos).getDistance()
                                val distRight = (downPos - rightHandlePos).getDistance()
                                if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                                    onUpdateTextSelection(minO, maxO)
                                    if (distLeft <= distRight) {
                                        isDraggingStart = true
                                    } else {
                                        isDraggingEnd = true
                                    }
                                    textToolbar.hide()
                                    val screenPoint = downPos + Offset(paddingHorizPx, paddingVertPx)
                                    onUpdateDragPoint(screenPoint, screenPoint)
                                }
                            }

                            var accPanX = 0f
                            var accPanY = 0f
                            var panStarted = false
                            var pinchStarted = false
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                            val timerJob = coroutineScope.launch {
                                delay(longPressTimeout)
                                val curLayout = latestTextLayout
                                val txtNow = latestPageText
                                if (!session.isCopyRestricted && !panStarted && !pinchStarted && !isDraggingStart && !isDraggingEnd &&
                                    curLayout != null && txtNow.isNotEmpty()
                                ) {
                                    val offsetIdx = curLayout.getOffsetForPosition(downPos).coerceIn(0, txtNow.length - 1)
                                    val wordBoundary = curLayout.getWordBoundary(offsetIdx)
                                    val wStart = wordBoundary.start.coerceIn(0, txtNow.length - 1)
                                    val wEnd = (wordBoundary.end - 1).coerceIn(wStart, txtNow.length - 1)
                                    textToolbar.hide()
                                    onUpdateTextSelection(wStart, wEnd)
                                    val screenPoint = downPos + Offset(paddingHorizPx, paddingVertPx)
                                    onUpdateDragPoint(screenPoint, screenPoint)
                                    isLongPress = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pointerCount = event.changes.count { it.pressed }
                                val curChange = event.changes.firstOrNull { it.pressed } ?: event.changes.first()
                                val curPos = curChange.position

                                if (pointerCount > 1) {
                                    timerJob.cancel()
                                    pinchStarted = true
                                    isLongPress = false
                                    isDraggingStart = false
                                    isDraggingEnd = false
                                    onUpdateDragPoint(null, null)
                                }

                                if (isDraggingStart || isDraggingEnd) {
                                    curChange.consume()
                                    val screenPoint = curPos + Offset(paddingHorizPx, paddingVertPx)
                                    onUpdateDragPoint(screenPoint, screenPoint)
                                    val curLayout = latestTextLayout
                                    val txtNow = latestPageText
                                    if (curLayout != null && txtNow.isNotEmpty()) {
                                        val newIdx = curLayout.getOffsetForPosition(curPos).coerceIn(0, txtNow.length - 1)
                                        val curS = latestTextSelStart
                                        val curE = latestTextSelEnd
                                        if (isDraggingStart) {
                                            if (newIdx > curE) {
                                                onUpdateTextSelection(curE, newIdx)
                                                isDraggingStart = false
                                                isDraggingEnd = true
                                            } else if (newIdx != curS) {
                                                onUpdateTextSelection(newIdx, curE)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        } else {
                                            if (newIdx < curS) {
                                                onUpdateTextSelection(newIdx, curS)
                                                isDraggingEnd = false
                                                isDraggingStart = true
                                            } else if (newIdx != curE) {
                                                onUpdateTextSelection(curS, newIdx)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                } else if (isLongPress) {
                                    curChange.consume()
                                    val screenPoint = curPos + Offset(paddingHorizPx, paddingVertPx)
                                    onUpdateDragPoint(screenPoint, screenPoint)
                                    if ((curPos - downPos).getDistance() > touchSlop) {
                                        val curLayout = latestTextLayout
                                        val txtNow = latestPageText
                                        if (curLayout != null && txtNow.isNotEmpty()) {
                                            val curIdx = curLayout.getOffsetForPosition(curPos).coerceIn(0, txtNow.length - 1)
                                            if (curIdx != latestTextSelEnd) {
                                                onUpdateTextSelection(latestTextSelStart, curIdx)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                } else {
                                    val panChange = event.calculatePan()
                                    val zoomChange = event.calculateZoom()
                                    accPanX += panChange.x
                                    accPanY += panChange.y
                                    val totalDist = Offset(accPanX, accPanY).getDistance()

                                    if (totalDist > touchSlop || zoomChange != 1f) {
                                        panStarted = true
                                        timerJob.cancel()
                                        if (latestTextSelStart >= 0 || latestTextSelEnd >= 0) {
                                            onUpdateTextSelection(-1, -1)
                                            textToolbar.hide()
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }

                                    if (zoomChange != 1f) {
                                        onUpdateFontScale((latestFontScale * zoomChange).coerceIn(0.5f, 3.5f))
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            timerJob.cancel()
                            onUpdateDragPoint(null, null)

                            val curLayout = latestTextLayout
                            val txtNow = latestPageText
                            if (!session.isCopyRestricted && (isDraggingStart || isDraggingEnd || isLongPress)) {
                                val sNow = latestTextSelStart
                                val eNow = latestTextSelEnd
                                if (sNow >= 0 && eNow >= 0 && curLayout != null && txtNow.isNotEmpty()) {
                                    val minO = minOf(sNow, eNow).coerceIn(0, txtNow.length - 1)
                                    val maxO = maxOf(sNow, eNow).coerceIn(minO, txtNow.length - 1)
                                    onUpdateTextSelection(minO, maxO)
                                    val startBox = curLayout.getBoundingBox(minO)
                                    val endBox = curLayout.getBoundingBox(maxO)

                                    val selRect = Rect(
                                        left = minOf(startBox.left, endBox.left) + paddingHorizPx,
                                        top = (minOf(startBox.top, endBox.top) + paddingVertPx - 20f).coerceAtLeast(10f),
                                        right = maxOf(startBox.right, endBox.right) + paddingHorizPx,
                                        bottom = maxOf(startBox.bottom, endBox.bottom) + paddingVertPx
                                    )

                                    textToolbar.showMenu(
                                        rect = selRect,
                                        onCopyRequested = { onCopySelection(latestPageText) },
                                        onSelectAllRequested = {
                                            val t = latestPageText
                                            if (t.isNotEmpty()) {
                                                onUpdateTextSelection(0, t.length - 1)
                                            }
                                        },
                                        onCutRequested = null,
                                        onPasteRequested = null
                                    )
                                }
                            } else if (!panStarted && !pinchStarted) {
                                focusManager.clearFocus()
                                val nowMs = System.currentTimeMillis()
                                if (!hadPreviousSelection && nowMs - latestLastTapMs in 40L..320L &&
                                    (downPos - latestLastTapPos).getDistance() < 48.dp.toPx()
                                ) {
                                    onUpdateFontScale(if (latestFontScale > 1.25f) 1.0f else 1.5f)
                                    onUpdateLastTap(0L, downPos)
                                } else {
                                    onUpdateLastTap(nowMs, downPos)
                                    if (hadPreviousSelection || latestTextSelStart >= 0 || latestTextSelEnd >= 0) {
                                        onUpdateTextSelection(-1, -1)
                                        textToolbar.hide()
                                    }
                                    if (latestDropdownExpanded) {
                                        onDismissSearchDropdown()
                                    }
                                }
                            } else if (panStarted && !pinchStarted) {
                                val swipeThreshold = 85f
                                if (abs(accPanY) >= abs(accPanX) && abs(accPanY) > swipeThreshold) {
                                    if (accPanY < -swipeThreshold && currentPage < latestTotalPages - 1) {
                                        onNavigateToPage(currentPage + 1)
                                    } else if (accPanY > swipeThreshold && currentPage > 0) {
                                        onNavigateToPage(currentPage - 1)
                                    }
                                } else if (abs(accPanX) > swipeThreshold * 1.15f) {
                                    if (accPanX < -swipeThreshold * 1.15f && currentPage < latestTotalPages - 1) {
                                        onNavigateToPage(currentPage + 1)
                                    } else if (accPanX > swipeThreshold * 1.15f && currentPage > 0) {
                                        onNavigateToPage(currentPage - 1)
                                    }
                                }
                            }
                        }
                    }
            ) {
                val annotatedPageText = remember(
                    structuredDoc.annotatedText,
                    currentRange,
                    currentPage,
                    activeSearchHighlightPage,
                    activeSearchHighlightStart,
                    activeSearchHighlightEnd,
                    searchHighlightAlpha,
                    normalReflowTextColor,
                    pageText
                ) {
                    val baseSub = if (!currentRange.isEmpty() &&
                        currentRange.first >= 0 &&
                        currentRange.last < structuredDoc.annotatedText.length
                    ) {
                        structuredDoc.annotatedText.subSequence(currentRange.first, currentRange.last + 1)
                    } else {
                        AnnotatedString(pageText)
                    }
                    if (activeSearchHighlightPage == currentPage &&
                        searchHighlightAlpha > 0.005f &&
                        activeSearchHighlightStart >= 0 &&
                        activeSearchHighlightEnd > activeSearchHighlightStart &&
                        baseSub.isNotEmpty()
                    ) {
                        val hStart = activeSearchHighlightStart.coerceIn(0, baseSub.length - 1)
                        val hEnd = activeSearchHighlightEnd.coerceIn(hStart + 1, baseSub.length)
                        val fgColor = lerp(normalReflowTextColor, Color(0xFF121212), searchHighlightAlpha)
                        buildAnnotatedString {
                            append(baseSub)
                            addStyle(
                                SpanStyle(color = fgColor),
                                start = hStart,
                                end = hEnd
                            )
                        }
                    } else {
                        baseSub
                    }
                }

                Text(
                    text = annotatedPageText,
                    style = reflowTextStyle,
                    softWrap = true,
                    onTextLayout = onTextLayout,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
