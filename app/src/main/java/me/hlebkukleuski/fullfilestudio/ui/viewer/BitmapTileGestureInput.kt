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
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager

internal class BitmapGestureCallbacks(
    val session: DocumentSession,
    val flags: ViewerSessionFlags,
    val coroutineScope: CoroutineScope,
    val ocrDispatcher: CoroutineDispatcher,
    val viewConfig: ViewConfiguration,
    val haptic: HapticFeedback,
    val textToolbar: TextToolbar,
    val focusManager: FocusManager,
    val flingAnimatable: Animatable<Offset, AnimationVector2D>,
    val activeLangCode: () -> String,
    val currentPage: () -> Int,
    val totalPages: () -> Int,
    val scale: () -> Float,
    val onScaleChange: (Float) -> Unit,
    val offset: () -> Offset,
    val onOffsetChange: (Offset) -> Unit,
    val yaw: () -> Float,
    val pitch: () -> Float,
    val onUpdate3dAngles: (Float, Float) -> Unit,
    val activeBitmap: () -> Bitmap?,
    val bufferWidth: () -> Int,
    val bufferHeight: () -> Int,
    val docWidth: () -> Float,
    val docHeight: () -> Float,
    val colWidthsPx: () -> FloatArray?,
    val rowHeightsPx: () -> FloatArray?,
    val renderedPage: () -> Int,
    val renderedZoomPermille: () -> Int,
    val renderedTileX: () -> Int,
    val renderedTileY: () -> Int,
    val rawNativePageText: () -> String,
    val ocrLines: () -> List<RecognizedLineUi>,
    val ocrChars: () -> List<RecognizedCharUi>,
    val onUpdateOcr: (List<RecognizedLineUi>, List<RecognizedCharUi>) -> Unit,
    val selStartChar: () -> Int,
    val selEndChar: () -> Int,
    val onUpdateCharSel: (Int, Int) -> Unit,
    val sheetSelStartRow: () -> Int,
    val sheetSelStartCol: () -> Int,
    val sheetSelEndRow: () -> Int,
    val sheetSelEndCol: () -> Int,
    val onUpdateSheetSel: (Int, Int, Int, Int) -> Unit,
    val onClearAllSelections: () -> Unit,
    val onCopySelection: () -> Unit,
    val onSetGestureActive: (Boolean) -> Unit,
    val onUpdateDragPoint: (Offset?, Offset?) -> Unit,
    val lastTapTimeMs: () -> Long,
    val lastTapPosition: () -> Offset,
    val onUpdateLastTap: (Long, Offset) -> Unit,
    val isSearchDropdownExpanded: () -> Boolean,
    val onDismissSearchDropdown: () -> Unit,
    val onNavigateToPage: (Int) -> Unit
)

internal fun Modifier.viewerBitmapGestureInput(
    cb: BitmapGestureCallbacks
): Modifier = pointerInput(
    cb.session,
    cb.flags.is3D,
    cb.flags.isHorizontalLayout,
    cb.flags.isSpreadsheet,
    cb.flags.isHexSession,
    cb.flags.isPresentation
) {
    val decay = exponentialDecay<Offset>(frictionMultiplier = 1.8f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        cb.onSetGestureActive(true)
        cb.coroutineScope.launch { cb.flingAnimatable.stop() }
        val velocityTracker = VelocityTracker()
        var accPanX = 0f
        var accPanY = 0f
        var panStarted = false
        var pinchStarted = false
        val touchSlop = cb.viewConfig.touchSlop
        val longPressTimeout = cb.viewConfig.longPressTimeoutMillis

        val downScreenPos = down.position
        val handleHitRadiusPx = 38.dp.toPx()

        val bmp = cb.activeBitmap()
        var charsNow = cb.ocrChars()
        val screenW = size.width.toFloat().coerceAtLeast(1f)
        val screenH = size.height.toFloat().coerceAtLeast(1f)
        val bufW = (bmp?.width ?: cb.bufferWidth()).toFloat().coerceAtLeast(1f)
        val bufH = (bmp?.height ?: cb.bufferHeight()).toFloat().coerceAtLeast(1f)
        val screenToBufX = bufW / screenW
        val screenToBufY = bufH / screenH
        val viewportCenterBuf = Offset(bufW / 2f, bufH / 2f)

        val hadPreviousCharSelection = !cb.flags.isSpreadsheet && cb.selStartChar() >= 0 && cb.selEndChar() >= 0
        val hadPreviousSheetSelection = cb.flags.isSpreadsheet && cb.sheetSelStartRow() >= 0 && cb.sheetSelStartCol() >= 0
        var isDraggingLeftHandle = false
        var isDraggingRightHandle = false
        var isLongPressActive = false

        val gestureDocW = cb.docWidth()
        val gestureDocH = cb.docHeight()
        val gestureColWidths = cb.colWidthsPx()
        val gestureRowHeights = cb.rowHeightsPx()

        fun computeBitmapPlacement(curBmp: Bitmap): FloatArray {
            val curScale = cb.scale()
            val curOff = cb.offset()
            val isLiveMatch = (cb.renderedPage() == cb.currentPage() && !cb.flags.is3D && !cb.flags.isTableBoundedLayout)
            val rScale = (cb.renderedZoomPermille() / 1000f).coerceAtLeast(0.25f)
            val liveRatio = if (isLiveMatch && abs(curScale - rScale) > 0.005f) {
                (curScale / rScale).coerceIn(0.125f, 8.0f)
            } else {
                1f
            }
            val baseScaleX = screenW / curBmp.width.toFloat().coerceAtLeast(1f)
            val baseScaleY = screenH / curBmp.height.toFloat().coerceAtLeast(1f)
            val panDeltaX = if (isLiveMatch) (curOff.x + cb.renderedTileX() * liveRatio) * baseScaleX else 0f
            val panDeltaY = if (isLiveMatch) (curOff.y + cb.renderedTileY() * liveRatio) * baseScaleY else 0f
            val dstW = (screenW * liveRatio).coerceAtLeast(1f)
            val dstH = (screenH * liveRatio).coerceAtLeast(1f)
            val dstX = (screenW - dstW) / 2f + panDeltaX
            val dstY = (screenH - dstH) / 2f + panDeltaY
            return floatArrayOf(dstX, dstY, dstW, dstH, baseScaleX * liveRatio, baseScaleY * liveRatio)
        }

        fun resolveCharScreenRect(ch: RecognizedCharUi, curBufW: Float, curBufH: Float): RectF {
            if (cb.flags.isGraphicSession) {
                val curBmp = cb.activeBitmap()
                if (curBmp != null && !curBmp.isRecycled) {
                    val placement = computeBitmapPlacement(curBmp)
                    val left = placement[0] + ch.rect.left * placement[4]
                    val top = placement[1] + ch.rect.top * placement[5]
                    val right = placement[0] + ch.rect.right * placement[4]
                    val bottom = placement[1] + ch.rect.bottom * placement[5]
                    return RectF(left, top, right, bottom)
                }
            }
            val curScale = cb.scale()
            val curOff = cb.offset()
            val livePageBoundsBuf = computeDocumentPageBoundsOnBuffer(
                bufWidth = curBufW,
                bufHeight = curBufH,
                docWidth = cb.docWidth(),
                docHeight = cb.docHeight(),
                zoomPermille = (curScale * 1000f).roundToInt(),
                tileX = (-curOff.x).roundToInt(),
                tileY = (-curOff.y).roundToInt(),
                isHexLayout = cb.flags.isHexSession,
                hexLineCount = cb.ocrLines().size.coerceAtLeast(1)
            )
            return projectNormRectToScreen(
                normRect = ch.normRect,
                livePageBoundsBuf = livePageBoundsBuf,
                screenScaleX = screenW / curBufW.coerceAtLeast(1f),
                screenScaleY = screenH / curBufH.coerceAtLeast(1f)
            )
        }

        if (bmp != null && !bmp.isRecycled && hadPreviousSheetSelection) {
            val placement = computeBitmapPlacement(bmp)
            val originX = placement[0]
            val originY = placement[1]
            val scaleX = placement[4]
            val scaleY = placement[5]

            val zoom = cb.scale().coerceIn(0.5f, 4.0f)
            val sr0 = cb.sheetSelStartRow()
            val sc0 = cb.sheetSelStartCol()
            val sr1 = cb.sheetSelEndRow()
            val sc1 = cb.sheetSelEndCol()
            val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                docWidth = gestureDocW,
                customColWidthsPx = gestureColWidths,
                minRequiredCols = maxOf(sc0, sc1) + 1
            )
            val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                colCount = colCount,
                customColWidthsPx = gestureColWidths,
                viewportWidth = bmp.width.toFloat(),
                zoom = zoom
            )
            val r0 = minOf(sr0, sr1).coerceAtLeast(0)
            val c0 = minOf(sc0, sc1).coerceAtLeast(0)
            val r1 = maxOf(sr0, sr1).coerceAtLeast(r0)
            val c1 = maxOf(sc0, sc1).coerceAtLeast(c0)

            val curOff = cb.offset()
            val rectBmp = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                startRow = r0, startCol = c0, endRow = r1, endCol = c1,
                colOffsets = colOffsets, zoom = zoom,
                effScrollX = (-curOff.x).coerceAtLeast(0f),
                effScrollY = (-curOff.y).coerceAtLeast(0f),
                customRowHeightsPx = gestureRowHeights
            )
            val leftHandlePos = Offset(originX + rectBmp.left * scaleX, originY + rectBmp.bottom * scaleY)
            val rightHandlePos = Offset(originX + rectBmp.right * scaleX, originY + rectBmp.bottom * scaleY)

            val distLeft = (downScreenPos - leftHandlePos).getDistance()
            val distRight = (downScreenPos - rightHandlePos).getDistance()
            if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                cb.onUpdateSheetSel(r0, c0, r1, c1)
                if (distLeft <= distRight) {
                    isDraggingLeftHandle = true
                } else {
                    isDraggingRightHandle = true
                }
                cb.textToolbar.hide()
                cb.onUpdateDragPoint(downScreenPos, downScreenPos)
            }
        } else if (bmp != null && !bmp.isRecycled && hadPreviousCharSelection && charsNow.isNotEmpty()) {
            val minC = minOf(cb.selStartChar(), cb.selEndChar()).coerceIn(0, charsNow.size - 1)
            val maxC = maxOf(cb.selStartChar(), cb.selEndChar()).coerceIn(0, charsNow.size - 1)

            val startScreenRect = resolveCharScreenRect(charsNow[minC], bmp.width.toFloat(), bmp.height.toFloat())
            val endScreenRect = resolveCharScreenRect(charsNow[maxC], bmp.width.toFloat(), bmp.height.toFloat())

            val leftHandlePos = Offset(startScreenRect.left, startScreenRect.bottom)
            val rightHandlePos = Offset(endScreenRect.right, endScreenRect.bottom)

            val distLeft = (downScreenPos - leftHandlePos).getDistance()
            val distRight = (downScreenPos - rightHandlePos).getDistance()
            if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                cb.onUpdateCharSel(minC, maxC)
                if (distLeft <= distRight) {
                    isDraggingLeftHandle = true
                } else {
                    isDraggingRightHandle = true
                }
                cb.textToolbar.hide()
                cb.onUpdateDragPoint(downScreenPos, downScreenPos)
            }
        }

        val longPressJob = cb.coroutineScope.launch {
            delay(longPressTimeout)
            val curBmp = cb.activeBitmap()
            if (!panStarted && !pinchStarted && !isDraggingLeftHandle && !isDraggingRightHandle &&
                !cb.flags.is3D && curBmp != null && !curBmp.isRecycled
            ) {
                val placement = computeBitmapPlacement(curBmp)
                val bmpX = (downScreenPos.x - placement[0]) / placement[4]
                val bmpY = (downScreenPos.y - placement[1]) / placement[5]

                if (cb.flags.isSpreadsheet) {
                    val curOff = cb.offset()
                    val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                        minX = bmpX, maxX = bmpX,
                        minY = bmpY, maxY = bmpY,
                        scale = cb.scale(), fontScaleMultiplier = 1f,
                        offsetX = curOff.x, offsetY = curOff.y,
                        viewportWidth = bufW, docWidth = gestureDocW,
                        viewportHeight = bufH, docHeight = gestureDocH,
                        customColWidthsPx = gestureColWidths,
                        customRowHeightsPx = gestureRowHeights
                    )
                    cb.textToolbar.hide()
                    cb.onUpdateSheetSel(cellRange.startRow, cellRange.startCol, cellRange.endRow, cellRange.endCol)
                    cb.onUpdateDragPoint(downScreenPos, downScreenPos)
                    isLongPressActive = true
                    cb.haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } else if (!cb.session.isCopyRestricted) {
                    var curChars = cb.ocrChars()
                    val rawNative = cb.rawNativePageText()
                    if (curChars.isEmpty() && rawNative.isNotBlank()) {
                        val (fbLines, fbChars) = buildFallbackCharsFromPageText(
                            rawPageText = rawNative,
                            bmpWidth = curBmp.width,
                            bmpHeight = curBmp.height,
                            isHexLayout = cb.flags.isHexSession,
                            docWidth = gestureDocW,
                            docHeight = gestureDocH,
                            zoomPermille = cb.renderedZoomPermille(),
                            tileX = cb.renderedTileX(),
                            tileY = cb.renderedTileY()
                        )
                        cb.onUpdateOcr(fbLines, fbChars)
                        curChars = fbChars
                    }
                    if (curChars.isEmpty() && !curBmp.isRecycled) {
                        val snapCopy = try { curBmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
                        if (snapCopy != null) {
                            val snapZ = cb.renderedZoomPermille()
                            val snapTX = cb.renderedTileX()
                            val snapTY = cb.renderedTileY()
                            val payload = withContext(cb.ocrDispatcher) {
                                try {
                                    PaddleOcrManager.recognize(
                                        bitmap = snapCopy,
                                        pageTextHint = rawNative,
                                        zoomPermille = snapZ,
                                        tileX = snapTX,
                                        tileY = snapTY,
                                        docWidth = gestureDocW,
                                        docHeight = gestureDocH,
                                        preferredLanguageCode = cb.activeLangCode()
                                    )
                                } finally {
                                    if (!snapCopy.isRecycled) snapCopy.recycle()
                                }
                            }
                            if (payload.chars.isNotEmpty()) {
                                val snapPageBounds = computeDocumentPageBoundsOnBuffer(
                                    bufWidth = curBmp.width.toFloat(),
                                    bufHeight = curBmp.height.toFloat(),
                                    docWidth = gestureDocW,
                                    docHeight = gestureDocH,
                                    zoomPermille = snapZ,
                                    tileX = snapTX,
                                    tileY = snapTY,
                                    isHexLayout = false
                                )
                                val lList = payload.lines.map {
                                    val rf = RectF(it.rect)
                                    RecognizedLineUi(it.text, it.rect, normalizeRectToPage(rf, snapPageBounds))
                                }
                                val cList = payload.chars.map {
                                    val rf = RectF(it.rect)
                                    RecognizedCharUi(it.text, it.lineIndex, rf, normalizeRectToPage(rf, snapPageBounds))
                                }
                                cb.onUpdateOcr(lList, cList)
                                curChars = cList
                            }
                        }
                    }
                    if (curChars.isNotEmpty()) {
                        val maxHitDist = maxOf(screenW, screenH) * 0.25f
                        val startIdx = findCharIndexAt(
                            targetX = downScreenPos.x,
                            targetY = downScreenPos.y,
                            chars = curChars,
                            rectResolver = { ch -> resolveCharScreenRect(ch, curBmp.width.toFloat(), curBmp.height.toFloat()) },
                            maxDistance = maxHitDist
                        )
                        if (startIdx >= 0) {
                            val wordRange = findWordRangeAt(startIdx, curChars)
                            cb.textToolbar.hide()
                            cb.onUpdateCharSel(wordRange.first, wordRange.last)
                            cb.onUpdateDragPoint(downScreenPos, downScreenPos)
                            isLongPressActive = true
                            cb.haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            val selectedChars = curChars.subList(wordRange.first, wordRange.last + 1)
                            val screenRects = selectedChars.map {
                                resolveCharScreenRect(it, curBmp.width.toFloat(), curBmp.height.toFloat())
                            }
                            val selectionRectOnScreen = Rect(
                                screenRects.minOf { it.left }.coerceIn(0f, screenW),
                                (screenRects.minOf { it.top } - 40f).coerceIn(10f, screenH),
                                screenRects.maxOf { it.right }.coerceIn(0f, screenW),
                                screenRects.maxOf { it.bottom }.coerceIn(10f, screenH)
                            )

                            cb.textToolbar.showMenu(
                                rect = selectionRectOnScreen,
                                onCopyRequested = cb.onCopySelection,
                                onSelectAllRequested = {
                                    val cChars = cb.ocrChars()
                                    if (cChars.isNotEmpty()) {
                                        cb.onUpdateCharSel(0, cChars.lastIndex)
                                    }
                                },
                                onCutRequested = null,
                                onPasteRequested = null
                            )
                        }
                    }
                }
            }
        }

        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pointerCount = event.changes.count { it.pressed }
            val curChange = event.changes.firstOrNull { it.pressed } ?: event.changes.first()
            val curScreenPos = curChange.position

            if (pointerCount > 1 || panStarted) {
                longPressJob.cancel()
            }

            if (pointerCount > 1) {
                pinchStarted = true
                isLongPressActive = false
                isDraggingLeftHandle = false
                isDraggingRightHandle = false
                cb.onUpdateDragPoint(null, null)
            }

            val curBmp = cb.activeBitmap()
            charsNow = cb.ocrChars()

            if (isDraggingLeftHandle || isDraggingRightHandle) {
                curChange.consume()
                cb.onUpdateDragPoint(curScreenPos, curScreenPos)
                if (curBmp != null && !curBmp.isRecycled) {
                    val placement = computeBitmapPlacement(curBmp)
                    val bmpX = (curScreenPos.x - placement[0]) / placement[4]
                    val bmpY = (curScreenPos.y - placement[1]) / placement[5]

                    if (cb.flags.isSpreadsheet) {
                        val curOff = cb.offset()
                        val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                            minX = bmpX, maxX = bmpX,
                            minY = bmpY, maxY = bmpY,
                            scale = cb.scale(), fontScaleMultiplier = 1f,
                            offsetX = curOff.x, offsetY = curOff.y,
                            viewportWidth = bufW, docWidth = gestureDocW,
                            viewportHeight = bufH, docHeight = gestureDocH,
                            customColWidthsPx = gestureColWidths,
                            customRowHeightsPx = gestureRowHeights
                        )
                        if (isDraggingLeftHandle) {
                            if (cellRange.startRow != cb.sheetSelStartRow() || cellRange.startCol != cb.sheetSelStartCol()) {
                                cb.onUpdateSheetSel(cellRange.startRow, cellRange.startCol, cb.sheetSelEndRow(), cb.sheetSelEndCol())
                                cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        } else {
                            if (cellRange.endRow != cb.sheetSelEndRow() || cellRange.endCol != cb.sheetSelEndCol()) {
                                cb.onUpdateSheetSel(cb.sheetSelStartRow(), cb.sheetSelStartCol(), cellRange.endRow, cellRange.endCol)
                                cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    } else if (charsNow.isNotEmpty()) {
                        val sNow = cb.selStartChar()
                        val eNow = cb.selEndChar()
                        val activeIdx = if (isDraggingLeftHandle) sNow else eNow
                        val prefLine = charsNow.getOrNull(activeIdx)?.lineIndex ?: -1
                        val newCharIdx = findCharIndexAt(
                            targetX = curScreenPos.x,
                            targetY = curScreenPos.y,
                            chars = charsNow,
                            rectResolver = { ch -> resolveCharScreenRect(ch, curBmp.width.toFloat(), curBmp.height.toFloat()) },
                            preferredLineIndex = prefLine
                        )

                        if (newCharIdx >= 0) {
                            if (isDraggingLeftHandle) {
                                if (newCharIdx > eNow) {
                                    cb.onUpdateCharSel(eNow, newCharIdx)
                                    isDraggingLeftHandle = false
                                    isDraggingRightHandle = true
                                } else if (newCharIdx != sNow) {
                                    cb.onUpdateCharSel(newCharIdx, eNow)
                                    cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            } else {
                                if (newCharIdx < sNow) {
                                    cb.onUpdateCharSel(newCharIdx, sNow)
                                    isDraggingRightHandle = false
                                    isDraggingLeftHandle = true
                                } else if (newCharIdx != eNow) {
                                    cb.onUpdateCharSel(sNow, newCharIdx)
                                    cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                }
            } else if (isLongPressActive) {
                curChange.consume()
                cb.onUpdateDragPoint(curScreenPos, curScreenPos)
                if ((curScreenPos - downScreenPos).getDistance() > touchSlop &&
                    curBmp != null && !curBmp.isRecycled
                ) {
                    val placement = computeBitmapPlacement(curBmp)
                    val bmpX = (curScreenPos.x - placement[0]) / placement[4]
                    val bmpY = (curScreenPos.y - placement[1]) / placement[5]

                    if (cb.flags.isSpreadsheet) {
                        val curOff = cb.offset()
                        val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                            minX = bmpX, maxX = bmpX,
                            minY = bmpY, maxY = bmpY,
                            scale = cb.scale(), fontScaleMultiplier = 1f,
                            offsetX = curOff.x, offsetY = curOff.y,
                            viewportWidth = bufW, docWidth = gestureDocW,
                            viewportHeight = bufH, docHeight = gestureDocH,
                            customColWidthsPx = gestureColWidths,
                            customRowHeightsPx = gestureRowHeights
                        )
                        if (cellRange.endRow != cb.sheetSelEndRow() || cellRange.endCol != cb.sheetSelEndCol()) {
                            cb.onUpdateSheetSel(cb.sheetSelStartRow(), cb.sheetSelStartCol(), cellRange.endRow, cellRange.endCol)
                            cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    } else if (charsNow.isNotEmpty()) {
                        val eNow = cb.selEndChar()
                        val prefLine = charsNow.getOrNull(eNow)?.lineIndex ?: -1
                        val curIdx = findCharIndexAt(
                            targetX = curScreenPos.x,
                            targetY = curScreenPos.y,
                            chars = charsNow,
                            rectResolver = { ch -> resolveCharScreenRect(ch, curBmp.width.toFloat(), curBmp.height.toFloat()) },
                            preferredLineIndex = prefLine
                        )
                        if (curIdx >= 0 && curIdx != eNow) {
                            cb.onUpdateCharSel(cb.selStartChar(), curIdx)
                            cb.haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            } else {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                accPanX += panChange.x
                accPanY += panChange.y

                val totalPanDist = Offset(accPanX, accPanY).getDistance()
                if (zoomChange != 1f || totalPanDist > touchSlop) {
                    panStarted = true
                    longPressJob.cancel()
                    cb.textToolbar.hide()
                }

                if (panStarted) {
                    if (cb.flags.is3D) {
                        val newYaw = (cb.yaw() + panChange.x * 0.45f) % 360f
                        val newPitch = (cb.pitch() - panChange.y * 0.45f).coerceIn(-89f, 89f)
                        cb.onUpdate3dAngles(newYaw, newPitch)
                        cb.onScaleChange((cb.scale() * zoomChange).coerceIn(0.2f, 8f))
                    } else {
                        val oldScale = cb.scale()
                        val minAllowedScale = if (cb.flags.isGraphicSession) 0.78f else 1f
                        val maxAllowedScale = if (cb.flags.isSpreadsheet) 4f else 8f
                        val nextScale = (oldScale * zoomChange).coerceIn(minAllowedScale, maxAllowedScale)
                        cb.onScaleChange(nextScale)
                        val centroidScreen = event.calculateCentroid(useCurrent = false)
                        val centroidBuf = if (centroidScreen != Offset.Unspecified) {
                            Offset(centroidScreen.x * screenToBufX, centroidScreen.y * screenToBufY)
                        } else {
                            Offset.Unspecified
                        }
                        val panChangeBuf = Offset(panChange.x * screenToBufX, panChange.y * screenToBufY)
                        val anchoredRawOffset = calculateCentroidAnchoredOffset(
                            currentOffset = cb.offset(),
                            panChange = panChangeBuf,
                            oldScale = oldScale,
                            newScale = nextScale,
                            centroid = centroidBuf,
                            viewportCenter = viewportCenterBuf,
                            isSpreadsheet = cb.flags.isTableBoundedLayout
                        )
                        cb.onOffsetChange(
                            clampCanvasOffset(
                                rawOffset = anchoredRawOffset,
                                scale = nextScale,
                                isSpreadsheet = cb.flags.isTableBoundedLayout,
                                isHorizontalLayout = cb.flags.isHorizontalLayout,
                                docWidth = gestureDocW,
                                docHeight = gestureDocH,
                                viewportWidth = bufW,
                                viewportHeight = bufH
                            )
                        )
                    }

                    event.changes.forEach { change ->
                        if (change.positionChanged()) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        cb.onSetGestureActive(false)
        longPressJob.cancel()
        cb.onUpdateDragPoint(null, null)
        if (!cb.flags.is3D && cb.scale() < 1.0f) {
            cb.onScaleChange(1.0f)
            cb.onOffsetChange(Offset.Zero)
        }

        val curBmp = cb.activeBitmap()
        val curChars = cb.ocrChars()

        if (isDraggingLeftHandle || isDraggingRightHandle || isLongPressActive) {
            if (cb.flags.isSpreadsheet && cb.sheetSelStartRow() >= 0 && cb.sheetSelStartCol() >= 0) {
                val r0 = minOf(cb.sheetSelStartRow(), cb.sheetSelEndRow()).coerceAtLeast(0)
                val c0 = minOf(cb.sheetSelStartCol(), cb.sheetSelEndCol()).coerceAtLeast(0)
                val r1 = maxOf(cb.sheetSelStartRow(), cb.sheetSelEndRow()).coerceAtLeast(r0)
                val c1 = maxOf(cb.sheetSelStartCol(), cb.sheetSelEndCol()).coerceAtLeast(c0)
                cb.onUpdateSheetSel(r0, c0, r1, c1)
                cb.textToolbar.hide()
            } else if (!cb.session.isCopyRestricted && cb.selStartChar() >= 0 && cb.selEndChar() >= 0 &&
                curChars.isNotEmpty() && curBmp != null && !curBmp.isRecycled
            ) {
                val minC = minOf(cb.selStartChar(), cb.selEndChar()).coerceIn(0, curChars.size - 1)
                val maxC = maxOf(cb.selStartChar(), cb.selEndChar()).coerceIn(0, curChars.size - 1)
                cb.onUpdateCharSel(minC, maxC)
                val selectedChars = curChars.subList(minC, maxC + 1)
                val screenRects = selectedChars.map {
                    resolveCharScreenRect(it, curBmp.width.toFloat(), curBmp.height.toFloat())
                }
                val selectionRectOnScreen = Rect(
                    screenRects.minOf { it.left }.coerceIn(0f, screenW),
                    (screenRects.minOf { it.top } - 40f).coerceIn(10f, screenH),
                    screenRects.maxOf { it.right }.coerceIn(0f, screenW),
                    screenRects.maxOf { it.bottom }.coerceIn(10f, screenH)
                )

                cb.textToolbar.showMenu(
                    rect = selectionRectOnScreen,
                    onCopyRequested = cb.onCopySelection,
                    onSelectAllRequested = {
                        val cChars = cb.ocrChars()
                        if (cChars.isNotEmpty()) {
                            cb.onUpdateCharSel(0, cChars.lastIndex)
                        }
                    },
                    onCutRequested = null,
                    onPasteRequested = null
                )
            }
        } else if (!panStarted && !pinchStarted) {
            cb.focusManager.clearFocus()
            val nowMs = System.currentTimeMillis()
            if (!hadPreviousCharSelection && !hadPreviousSheetSelection &&
                nowMs - cb.lastTapTimeMs() in 40L..320L &&
                (downScreenPos - cb.lastTapPosition()).getDistance() < 48.dp.toPx()
            ) {
                val oldScale = cb.scale()
                val targetScale = if (oldScale > 1.15f) 1.0f else if (cb.flags.isTableBoundedLayout) 2.0f else 2.5f
                cb.onScaleChange(targetScale)
                if (targetScale <= 1.02f && !cb.flags.isTableBoundedLayout) {
                    cb.onOffsetChange(Offset.Zero)
                } else {
                    val tapBuf = Offset(downScreenPos.x * screenToBufX, downScreenPos.y * screenToBufY)
                    val rawAnchored = calculateCentroidAnchoredOffset(
                        currentOffset = cb.offset(),
                        panChange = Offset.Zero,
                        oldScale = oldScale,
                        newScale = targetScale,
                        centroid = tapBuf,
                        viewportCenter = viewportCenterBuf,
                        isSpreadsheet = cb.flags.isTableBoundedLayout
                    )
                    cb.onOffsetChange(
                        clampCanvasOffset(
                            rawOffset = rawAnchored,
                            scale = targetScale,
                            isSpreadsheet = cb.flags.isTableBoundedLayout,
                            isHorizontalLayout = cb.flags.isHorizontalLayout,
                            docWidth = gestureDocW,
                            docHeight = gestureDocH,
                            viewportWidth = bufW,
                            viewportHeight = bufH
                        )
                    )
                }
                cb.onUpdateLastTap(0L, downScreenPos)
            } else {
                cb.onUpdateLastTap(nowMs, downScreenPos)
                if (hadPreviousCharSelection || hadPreviousSheetSelection || cb.selStartChar() >= 0 || cb.sheetSelStartRow() >= 0) {
                    cb.onClearAllSelections()
                }
                if (cb.isSearchDropdownExpanded()) {
                    cb.onDismissSearchDropdown()
                }
            }
        } else if (panStarted && !cb.flags.is3D) {
            val swipeThreshold = 85f
            val curPage = cb.currentPage()
            val totPages = cb.totalPages()
            if (!pinchStarted && totPages > 1 && cb.scale() <= 1.02f && !cb.flags.isTableBoundedLayout) {
                when {
                    cb.flags.isPresentation -> {
                        if (accPanX < -swipeThreshold && curPage < totPages - 1) {
                            cb.onNavigateToPage(curPage + 1)
                        } else if (accPanX > swipeThreshold && curPage > 0) {
                            cb.onNavigateToPage(curPage - 1)
                        }
                    }
                    else -> {
                        if ((accPanY < -swipeThreshold || accPanX < -swipeThreshold * 1.15f) && curPage < totPages - 1) {
                            cb.onNavigateToPage(curPage + 1)
                        } else if ((accPanY > swipeThreshold || accPanX > swipeThreshold * 1.15f) && curPage > 0) {
                            cb.onNavigateToPage(curPage - 1)
                        }
                    }
                }
            }

            if (cb.scale() > 1.02f || cb.flags.isTableBoundedLayout) {
                val v: Velocity = velocityTracker.calculateVelocity()
                val initialVel = Offset(v.x * screenToBufX, v.y * screenToBufY)
                if (initialVel.getDistance() > 250f) {
                    cb.coroutineScope.launch {
                        cb.flingAnimatable.snapTo(cb.offset())
                        cb.flingAnimatable.animateDecay(initialVel, decay) {
                            cb.onOffsetChange(
                                clampCanvasOffset(
                                    rawOffset = value,
                                    scale = cb.scale(),
                                    isSpreadsheet = cb.flags.isTableBoundedLayout,
                                    isHorizontalLayout = cb.flags.isHorizontalLayout,
                                    docWidth = gestureDocW,
                                    docHeight = gestureDocH,
                                    viewportWidth = bufW,
                                    viewportHeight = bufH
                                )
                            )
                        }
                    }
                }
            } else {
                cb.onOffsetChange(Offset.Zero)
            }
        }
    }
}
