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
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
internal const val ILLUSTRATION_ONLY_PAGE_FLAG = 1 shl 29
internal const val LANG_ORDINAL_SHIFT = 24
internal const val ANIM_STEP_SHIFT = 20
internal const val BASE_REFLOW_FONT_SIZE_SP = 17f
internal const val MAX_SEARCH_MATCHES = 200

/**
 * Intercepts the Android InputMethod `EditorInfo` and injects
 * `IME_FLAG_NO_EXTRACT_UI` + `IME_FLAG_NO_FULLSCREEN` so the soft keyboard
 * never expands into an opaque fullscreen white box when the device is in landscape mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DisableFullscreenImeWrapper(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val nonFullscreenRequest = EditorInfo().let {
                PlatformTextInputMethodRequest { outAttrs ->
                    val ic = request.createInputConnection(outAttrs)
                    outAttrs.imeOptions = outAttrs.imeOptions or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN
                    ic
                }
            }
            nextHandler.startInputMethod(nonFullscreenRequest)
        },
        content = content
    )
}

@Immutable
internal data class TileRenderRequest(
    val page: Int,
    val tileX: Int,
    val tileY: Int,
    val zoomPermille: Int,
    val is3D: Boolean,
    val stateTrigger: Int,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val animTimeMs: Int = -1,
    val animClip: Int = 0
)

@Immutable
internal data class SearchMatchItem(
    val pageIndex: Int,
    val occurrenceIndexOnPage: Int,
    val charStartInPage: Int,
    val charEndExclusiveInPage: Int,
    val contextPrefix: String,
    val matchedText: String,
    val contextSuffix: String,
    val sheetIndex: Int = -1,
    val cellRow: Int = -1,
    val cellCol: Int = -1,
    val customBadge: String? = null
)

@Immutable
internal data class TableRowLayoutMeta(
    val charStart: Int,
    val charEndExclusive: Int,
    val isHeaderRow: Boolean,
    val separatorOffsets: List<Int>
)

@Immutable
internal data class ImageAnchorMeta(
    val charOffsetInCleanText: Int,
    val imageIndex: Int
)

@Immutable
internal data class StructuredReflowDocument(
    val annotatedText: AnnotatedString,
    val cleanText: String,
    val tableRows: List<TableRowLayoutMeta>,
    val imageAnchors: List<ImageAnchorMeta>
)

@Immutable
internal data class RecognizedLineUi(
    val text: String,
    val rect: Rect,
    val normRect: RectF = RectF()
)

@Immutable
internal data class RecognizedCharUi(
    val text: String,
    val lineIndex: Int,
    val rect: RectF,
    val normRect: RectF = RectF()
) {
    val char: Char get() = text.firstOrNull() ?: ' '
}

internal fun computeDocumentPageBoundsOnBuffer(
    bufWidth: Float,
    bufHeight: Float,
    docWidth: Float,
    docHeight: Float,
    zoomPermille: Int,
    tileX: Int,
    tileY: Int,
    isHexLayout: Boolean,
    hexLineCount: Int = 1
): RectF {
    val safeBufW = bufWidth.coerceAtLeast(1f)
    val safeBufH = bufHeight.coerceAtLeast(1f)
    val zoom = (zoomPermille / 1000f).coerceIn(0.25f, 10.0f)

    if (isHexLayout) {
        val tableVirtualWidth = 80f * 14.2f + 56f
        val fitScale = (safeBufW / tableVirtualWidth).coerceIn(0.55f, 2.2f)
        val effectiveScale = fitScale * zoom
        val lineHeight = 32f * effectiveScale
        val headerHeight = 76f * effectiveScale
        val padLeft = 24f * effectiveScale
        val charW = 13.8f * effectiveScale
        val tableContentWidth = 78f * charW + padLeft * 2f
        val totalTableW = max(safeBufW, tableContentWidth)
        val safeRows = hexLineCount.coerceIn(1, 64)
        val totalTableH = headerHeight + safeRows * lineHeight + 16f * effectiveScale
        val maxEffX = (totalTableW - safeBufW).coerceAtLeast(0f)
        val maxEffY = (totalTableH - safeBufH).coerceAtLeast(0f)
        val effX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effY = tileY.toFloat().coerceIn(0f, maxEffY)
        val baseCenterX = if (tableContentWidth < safeBufW) (safeBufW - tableContentWidth) / 2f else 0f
        val drawOriginX = baseCenterX - effX
        return RectF(
            drawOriginX,
            -effY,
            drawOriginX + totalTableW.coerceAtLeast(1f),
            -effY + totalTableH.coerceAtLeast(1f)
        )
    }

    val effDocW = if (docWidth > 0f) docWidth else safeBufW
    val effDocH = if (docHeight > 0f) docHeight else safeBufH
    val baseScale = minOf(safeBufW / effDocW, safeBufH / effDocH).coerceAtLeast(0.01f)
    val totalScale = baseScale * zoom
    val pageW = (effDocW * totalScale).coerceAtLeast(1f)
    val pageH = (effDocH * totalScale).coerceAtLeast(1f)
    val pageOriginX = (safeBufW - pageW) / 2f - tileX.toFloat()
    val pageOriginY = (safeBufH - pageH) / 2f - tileY.toFloat()
    return RectF(pageOriginX, pageOriginY, pageOriginX + pageW, pageOriginY + pageH)
}

internal fun normalizeRectToPage(
    bmpRect: RectF,
    pageBounds: RectF
): RectF {
    val pw = pageBounds.width().coerceAtLeast(1f)
    val ph = pageBounds.height().coerceAtLeast(1f)
    return RectF(
        (bmpRect.left - pageBounds.left) / pw,
        (bmpRect.top - pageBounds.top) / ph,
        (bmpRect.right - pageBounds.left) / pw,
        (bmpRect.bottom - pageBounds.top) / ph
    )
}

internal fun projectNormRectToScreen(
    normRect: RectF,
    livePageBoundsBuf: RectF,
    screenScaleX: Float,
    screenScaleY: Float
): RectF {
    val pw = livePageBoundsBuf.width()
    val ph = livePageBoundsBuf.height()
    return RectF(
        (livePageBoundsBuf.left + normRect.left * pw) * screenScaleX,
        (livePageBoundsBuf.top + normRect.top * ph) * screenScaleY,
        (livePageBoundsBuf.left + normRect.right * pw) * screenScaleX,
        (livePageBoundsBuf.top + normRect.bottom * ph) * screenScaleY
    )
}

internal fun clampCanvasOffset(
    rawOffset: Offset,
    scale: Float,
    isSpreadsheet: Boolean,
    isHorizontalLayout: Boolean,
    docWidth: Float,
    docHeight: Float,
    viewportWidth: Float = if (isHorizontalLayout) 1920f else 1080f,
    viewportHeight: Float = if (isHorizontalLayout) 1080f else 1920f
): Offset {
    if (!isSpreadsheet && scale <= 1.02f) {
        return Offset.Zero
    }
    val curW = viewportWidth.coerceAtLeast(100f)
    val curH = viewportHeight.coerceAtLeast(100f)

    if (isSpreadsheet) {
        val effectiveScale = scale.coerceIn(0.5f, 8.0f)
        val baseW = if (docWidth > 0f) maxOf(docWidth, curW) else curW
        val baseH = if (docHeight > 0f) docHeight else curH

        val zoomedW = baseW * effectiveScale
        val zoomedH = baseH * effectiveScale

        val maxScrollX = (zoomedW - curW).coerceAtLeast(0f)
        val maxScrollY = (zoomedH - curH).coerceAtLeast(0f)

        return Offset(
            x = rawOffset.x.coerceIn(-maxScrollX, 0f),
            y = rawOffset.y.coerceIn(-maxScrollY, 0f)
        )
    }

    val (baseW, baseH) = if (docWidth > 0f && docHeight > 0f) {
        val aspect = (docWidth / docHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
        val targetAspect = curW / curH
        if (aspect > targetAspect) {
            curW to (curW / aspect)
        } else {
            (curH * aspect) to curH
        }
    } else {
        curW to curH
    }

    val zoomedW = baseW * scale
    val zoomedH = baseH * scale

    val edgeMarginX = if (zoomedW > curW) baseW * 0.06f else 0f
    val edgeMarginY = if (zoomedH > curH) baseH * 0.06f else 0f

    val maxScrollX = if (zoomedW > curW) (zoomedW - curW) + edgeMarginX else 0f
    val maxScrollY = if (zoomedH > curH) (zoomedH - curH) + edgeMarginY else 0f

    return Offset(
        x = rawOffset.x.coerceIn(-maxScrollX / 2f, maxScrollX / 2f),
        y = rawOffset.y.coerceIn(-maxScrollY / 2f, maxScrollY / 2f)
    )
}

internal fun calculateCentroidAnchoredOffset(
    currentOffset: Offset,
    panChange: Offset,
    oldScale: Float,
    newScale: Float,
    centroid: Offset,
    viewportCenter: Offset,
    isSpreadsheet: Boolean
): Offset {
    val panned = currentOffset + panChange
    if (newScale == oldScale || centroid == Offset.Unspecified) {
        return panned
    }
    val scaleRatio = newScale / oldScale.coerceAtLeast(0.001f)
    return if (isSpreadsheet) {
        val focusFromOrigin = centroid
        (panned - focusFromOrigin) * scaleRatio + focusFromOrigin
    } else {
        val focusDelta = centroid - viewportCenter
        (panned - focusDelta) * scaleRatio + focusDelta
    }
}

/**
 * Computes exact per-page character ranges (`IntRange`) for reflowable documents.
 * Dynamically expands the measurement slice (`windowCap`) whenever all lines fit inside
 * [viewportHeightPx], guaranteeing that every page fills 100% of the vertical screen height
 * even at small font sizes (`50%..100%`). Subtracts [illustrationBoxHeightPx] strictly on pages
 * whose character range `start until tentativeEndExclusive` actually contains an [ImageAnchorMeta].
 */
internal fun computeViewportPageRanges(
    fullText: String,
    measurePageSlice: (startIndex: Int, endCandidate: Int) -> TextLayoutResult,
    viewportHeightPx: Int,
    imageAnchors: List<ImageAnchorMeta> = emptyList(),
    illustrationBoxHeightPx: Int = 0,
    maxPagesToCompute: Int = Int.MAX_VALUE
): List<IntRange> {
    if (fullText.isEmpty()) return listOf(0..0)
    if (viewportHeightPx <= 0) return listOf(0 until fullText.length)

    val ranges = ArrayList<IntRange>()
    var start = 0
    val len = fullText.length
    val baseWindowCap = 8500
    val reducedViewportHeightPx = (viewportHeightPx - illustrationBoxHeightPx).coerceAtLeast(160)

    while (start < len && ranges.size < maxPagesToCompute) {
        var currentWindowCap = baseWindowCap
        var candidateEnd: Int
        var layout: TextLayoutResult? = null
        var lastFullFittingLine = -1

        while (true) {
            candidateEnd = minOf(len, start + currentWindowCap)
            layout = try {
                measurePageSlice(start, candidateEnd)
            } catch (_: Throwable) {
                null
            }
            if (layout == null) break

            val lineCount = layout.lineCount
            lastFullFittingLine = -1
            for (line in 0 until lineCount) {
                if (layout.getLineBottom(line) <= viewportHeightPx.toFloat()) {
                    lastFullFittingLine = line
                } else {
                    break
                }
            }

            if (lastFullFittingLine == lineCount - 1 && candidateEnd < len && currentWindowCap < 32_000) {
                currentWindowCap *= 2
            } else {
                break
            }
        }

        if (layout == null) {
            val fallbackEnd = minOf(len, start + 3500)
            ranges.add(start until fallbackEnd)
            start = fallbackEnd
            continue
        }

        val lineCount = layout.lineCount
        if (lineCount <= 0) {
            ranges.add(start until candidateEnd)
            break
        }

        val fullFitLine = lastFullFittingLine.coerceAtLeast(0)
        val tentativeRelEnd = layout.getLineEnd(fullFitLine, visibleEnd = false).coerceAtLeast(1)
        val tentativeEndExclusive = (start + tentativeRelEnd).coerceIn(start + 1, len)

        val pageHasIllustration = illustrationBoxHeightPx > 0 && imageAnchors.any { anchor ->
            anchor.charOffsetInCleanText in start until tentativeEndExclusive ||
                    (ranges.isEmpty() && anchor.charOffsetInCleanText == 0)
        }

        val actualEndExclusive = if (pageHasIllustration) {
            var lastReducedFittingLine = -1
            for (line in 0 until lineCount) {
                if (layout.getLineBottom(line) <= reducedViewportHeightPx.toFloat()) {
                    lastReducedFittingLine = line
                } else {
                    break
                }
            }
            val redLine = lastReducedFittingLine.coerceAtLeast(0)
            val redRelEnd = layout.getLineEnd(redLine, visibleEnd = false).coerceAtLeast(1)
            (start + redRelEnd).coerceIn(start + 1, len)
        } else {
            tentativeEndExclusive
        }

        ranges.add(start until actualEndExclusive)
        start = actualEndExclusive
    }

    return if (ranges.isEmpty()) listOf(0 until len) else ranges
}

internal fun buildOcrCacheKey(
    page: Int,
    sheetIdx: Int,
    darkTheme: Boolean,
    zoomPermilleVal: Int,
    tileXVal: Int,
    tileYVal: Int
): Long {
    val pagePart = (page.toLong() and 0xFFFFFL)
    val themeBit = if (darkTheme) 1L else 0L
    val sheetPart = (sheetIdx.toLong() and 0x7FL)
    val zoomBucket = ((zoomPermilleVal / 25).toLong() and 0x3FFL)
    val offXBucket = ((tileXVal / 12).toLong() and 0x1FFFL)
    val offYBucket = ((tileYVal / 12).toLong() and 0x1FFFL)
    return (pagePart shl 44) or
            (themeBit shl 43) or
            (sheetPart shl 36) or
            (zoomBucket shl 26) or
            (offXBucket shl 13) or
            offYBucket
}

internal fun findCharIndexAt(
    targetX: Float,
    targetY: Float,
    chars: List<RecognizedCharUi>,
    rectResolver: (RecognizedCharUi) -> RectF = { it.rect },
    maxDistance: Float = Float.MAX_VALUE,
    preferredLineIndex: Int = -1
): Int {
    if (chars.isEmpty()) return -1
    val resolvedRects = Array(chars.size) { idx -> rectResolver(chars[idx]) }
    val lineChars = chars.indices.filter { idx ->
        val r = resolvedRects[idx]
        val padY = (r.height() * 0.45f).coerceIn(8f, 28f)
        targetY >= r.top - padY && targetY <= r.bottom + padY
    }
    if (lineChars.isNotEmpty()) {
        val insideChar = lineChars.firstOrNull { idx ->
            val r = resolvedRects[idx]
            targetX >= r.left && targetX <= r.right
        }
        if (insideChar != null) return insideChar

        if (preferredLineIndex >= 0) {
            val sameLineClosest = lineChars
                .filter { idx -> chars[idx].lineIndex == preferredLineIndex }
                .minByOrNull { idx -> abs(resolvedRects[idx].centerX() - targetX) }
            if (sameLineClosest != null) {
                val dist = abs(resolvedRects[sameLineClosest].centerX() - targetX)
                if (dist <= maxDistance) return sameLineClosest
            }
        }

        val closestInLine = lineChars.minByOrNull { idx ->
            val r = resolvedRects[idx]
            val dx = when {
                targetX < r.left -> r.left - targetX
                targetX > r.right -> targetX - r.right
                else -> 0f
            }
            val dy = abs(r.centerY() - targetY) * 1.5f
            dx + dy
        }
        if (closestInLine != null) {
            val r = resolvedRects[closestInLine]
            val dx = maxOf(0f, maxOf(r.left - targetX, targetX - r.right))
            val dy = maxOf(0f, maxOf(r.top - targetY, targetY - r.bottom))
            if (hypot(dx, dy) <= maxDistance) {
                return closestInLine
            }
        }
    }
    val closestChar = chars.indices.minByOrNull { idx ->
        val r = resolvedRects[idx]
        val dy = if (targetY < r.top) r.top - targetY else if (targetY > r.bottom) targetY - r.bottom else 0f
        val dx = if (targetX < r.left) r.left - targetX else if (targetX > r.right) targetX - r.right else 0f
        dy * 3f + dx
    } ?: return -1

    val r = resolvedRects[closestChar]
    val dx = maxOf(0f, maxOf(r.left - targetX, targetX - r.right))
    val dy = maxOf(0f, maxOf(r.top - targetY, targetY - r.bottom))
    return if (hypot(dx, dy) <= maxDistance) closestChar else -1
}

internal fun findWordRangeAt(index: Int, chars: List<RecognizedCharUi>): IntRange {
    if (index !in chars.indices) return index..index
    val target = chars[index]
    if (target.text.isBlank()) return index..index
    val lineIdx = target.lineIndex
    var start = index
    while (start > 0 && chars[start - 1].lineIndex == lineIdx && chars[start - 1].text.isNotBlank()) {
        val gap = chars[start].normRect.left - chars[start - 1].normRect.right
        val maxAllowedGap = chars[start].normRect.height().coerceAtLeast(0.005f) * 0.30f
        if (gap > maxAllowedGap) break
        start--
    }
    var end = index
    while (end < chars.lastIndex && chars[end + 1].lineIndex == lineIdx && chars[end + 1].text.isNotBlank()) {
        val gap = chars[end + 1].normRect.left - chars[end].normRect.right
        val maxAllowedGap = chars[end].normRect.height().coerceAtLeast(0.005f) * 0.30f
        if (gap > maxAllowedGap) break
        end++
    }
    return start..end
}
