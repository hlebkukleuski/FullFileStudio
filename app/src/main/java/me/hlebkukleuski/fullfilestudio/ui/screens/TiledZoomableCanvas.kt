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

package me.hlebkukleuski.fullfilestudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.impl.MediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.ui.theme.rememberActiveAudioDeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.hlebkukleuski.fullfilestudio.MainActivity
import me.hlebkukleuski.fullfilestudio.feature.office.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager
import me.hlebkukleuski.fullfilestudio.ui.theme.AppAudioQualityMode
import me.hlebkukleuski.fullfilestudio.ui.theme.AppLanguage
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppSettingsManager
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings
import me.hlebkukleuski.fullfilestudio.ui.theme.localizeEngineMessage
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
private const val ILLUSTRATION_ONLY_PAGE_FLAG = 1 shl 29
private const val LANG_ORDINAL_SHIFT = 24
private const val ANIM_STEP_SHIFT = 20
private const val BASE_REFLOW_FONT_SIZE_SP = 17f
private const val MAX_SEARCH_MATCHES = 200

@OptIn(ExperimentalCoroutinesApi::class)
private val OCR_BACKGROUND_DISPATCHER = Dispatchers.Default.limitedParallelism(1)

private val REFLOW_HTML_HEADING_REGEX = Regex("(?i)^<(h[1-6])>([\\s\\S]*?)</\\1>$")
private val REFLOW_INLINE_TAG_REGEX = Regex("(?i)<(/?)(b|strong|i|em|u|s|del|strike|sub|sup)>")
private val REFLOW_IMAGE_MARKER_REGEX = Regex("\\[IMAGE:(\\d+)]")
private val PDF_NATIVE_BOUNDS_LINE_REGEX = Regex("^\\[PDF_BOX:([0-9.]+),([0-9.]+),([0-9.]+),([0-9.]+)](.*)$")
private val SLIDE_META_TAG_REGEX = Regex("\\[SLIDE_META:transition=([a-z_]+);dur=(\\d+);steps=(\\d+)]")

/**
 * Intercepts the Android InputMethod `EditorInfo` and injects
 * `IME_FLAG_NO_EXTRACT_UI` + `IME_FLAG_NO_FULLSCREEN` so the soft keyboard
 * never expands into an opaque fullscreen white box when the device is in landscape mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DisableFullscreenImeWrapper(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val nonFullscreenRequest = android.view.inputmethod.EditorInfo().let {
                androidx.compose.ui.platform.PlatformTextInputMethodRequest { outAttrs ->
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

private data class TileRenderRequest(
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

internal data class TableRowLayoutMeta(
    val charStart: Int,
    val charEndExclusive: Int,
    val isHeaderRow: Boolean,
    val separatorOffsets: List<Int>
)

internal data class ImageAnchorMeta(
    val charOffsetInCleanText: Int,
    val imageIndex: Int
)

internal data class StructuredReflowDocument(
    val annotatedText: AnnotatedString,
    val cleanText: String,
    val tableRows: List<TableRowLayoutMeta>,
    val imageAnchors: List<ImageAnchorMeta>
)

internal data class RecognizedLineUi(
    val text: String,
    val rect: android.graphics.Rect,
    val normRect: android.graphics.RectF = android.graphics.RectF()
)

internal data class RecognizedCharUi(
    val text: String,
    val lineIndex: Int,
    val rect: android.graphics.RectF,
    val normRect: android.graphics.RectF = android.graphics.RectF()
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
): android.graphics.RectF {
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
        return android.graphics.RectF(
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
    return android.graphics.RectF(pageOriginX, pageOriginY, pageOriginX + pageW, pageOriginY + pageH)
}

internal fun normalizeRectToPage(
    bmpRect: android.graphics.RectF,
    pageBounds: android.graphics.RectF
): android.graphics.RectF {
    val pw = pageBounds.width().coerceAtLeast(1f)
    val ph = pageBounds.height().coerceAtLeast(1f)
    return android.graphics.RectF(
        (bmpRect.left - pageBounds.left) / pw,
        (bmpRect.top - pageBounds.top) / ph,
        (bmpRect.right - pageBounds.left) / pw,
        (bmpRect.bottom - pageBounds.top) / ph
    )
}

internal fun projectNormRectToScreen(
    normRect: android.graphics.RectF,
    livePageBoundsBuf: android.graphics.RectF,
    screenScaleX: Float,
    screenScaleY: Float
): android.graphics.RectF {
    val pw = livePageBoundsBuf.width()
    val ph = livePageBoundsBuf.height()
    return android.graphics.RectF(
        (livePageBoundsBuf.left + normRect.left * pw) * screenScaleX,
        (livePageBoundsBuf.top + normRect.top * ph) * screenScaleY,
        (livePageBoundsBuf.left + normRect.right * pw) * screenScaleX,
        (livePageBoundsBuf.top + normRect.bottom * ph) * screenScaleY
    )
}

private fun getFallbackGraphemeRelativeWeight(g: String): Float = PaddleOcrManager.getRelativeGraphemeWeight(g)

private fun buildFallbackCharsFromPageText(
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
            val lineRectF = android.graphics.RectF(leftPx, topPx, rightPx, botPx)
            val pageBoundsF = android.graphics.RectF(dx, dy, dx + scaledW.coerceAtLeast(1f), dy + scaledH.coerceAtLeast(1f))
            linesOut.add(
                RecognizedLineUi(
                    text = text,
                    rect = android.graphics.Rect(
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
                val charRectF = android.graphics.RectF(cl, topPx, cr, botPx)
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

            val hexPageBounds = android.graphics.RectF(
                drawOriginX,
                -effY,
                drawOriginX + totalTableW.coerceAtLeast(1f),
                -effY + totalTableH.coerceAtLeast(1f)
            )
            val lineIdx = linesOut.size
            val lineRectF = android.graphics.RectF(leftPx, topPx, rightPx, botPx)
            linesOut.add(
                RecognizedLineUi(
                    text = lineStr,
                    rect = android.graphics.Rect(
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
                val charRectF = android.graphics.RectF(cl, topPx, cr, botPx)
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

        val genericPageBounds = android.graphics.RectF(
            pageOriginX,
            pageOriginY,
            pageOriginX + pageW.coerceAtLeast(1f),
            pageOriginY + pageH.coerceAtLeast(1f)
        )
        val lineIdx = linesOut.size
        val lineRectF = android.graphics.RectF(leftPx, topPx, rightPx, botPx)
        linesOut.add(
            RecognizedLineUi(
                text = lineStr,
                rect = android.graphics.Rect(
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
            val charRectF = android.graphics.RectF(cl, topPx, cr, botPx)
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

private fun stripBoundedNativeMarkers(rawPageText: String): String {
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

private fun resolveMediaPlaybackSession(session: DocumentSession): MediaPlaybackSession? {
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

@Composable
fun TiledZoomableCanvas(
    session: DocumentSession?,
    onOpenFileRequest: () -> Unit = {},
    onOpenSettingsRequest: () -> Unit = {},
    onOpenHexFallbackRequest: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    isExternalDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (session == null) return

    val mediaPlaybackSession = remember(session) { resolveMediaPlaybackSession(session) }
    if (mediaPlaybackSession != null) {
        MediaRenderScreen(
            session = mediaPlaybackSession,
            onOpenFileRequest = onOpenFileRequest,
            onOpenSettingsRequest = onOpenSettingsRequest,
            modifier = modifier
        )
        return
    }

    val palette = LocalAppPalette.current
    val isDarkTheme by rememberUpdatedState(palette.isDark)
    val strings = LocalAppStrings.current
    val currentLang = remember(strings) {
        AppLanguage.entries.firstOrNull { strings == me.hlebkukleuski.fullfilestudio.ui.theme.stringsForLanguage(it) }
            ?: AppLanguage.EN
    }
    val activeLang by rememberUpdatedState(currentLang)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val is3D = remember(session) { session.is3dContent }

    val predictiveBackScale = remember { Animatable(1f) }
    val predictiveBackAlpha = remember { Animatable(1f) }
    val predictiveBackCornerRadius = remember { Animatable(0f) }

    var scale by remember(session) { mutableFloatStateOf(1f) }
    var offset by remember(session) { mutableStateOf(Offset.Zero) }
    var currentPage by remember(session) { mutableIntStateOf(0) }

    var isSearchVisible by remember(session) { mutableStateOf(false) }
    var searchQuery by remember(session) { mutableStateOf("") }
    var lastExecutedQuery by remember(session) { mutableStateOf("") }
    var searchResults by remember(session) { mutableStateOf<List<SearchMatchItem>>(emptyList()) }
    var isSearchDropdownExpanded by remember(session) { mutableStateOf(false) }
    var isSearchingInProgress by remember(session) { mutableStateOf(false) }
    var hasExecutedSearch by remember(session) { mutableStateOf(false) }
    var selectedMatchIndex by remember(session) { mutableIntStateOf(-1) }
    var searchJob by remember(session) { mutableStateOf<Job?>(null) }
    val searchDropdownListState = rememberLazyListState()

    var showSpeakerNotesDialog by remember(session) { mutableStateOf(false) }

    var yaw by remember(session) { mutableFloatStateOf(25f) }
    var pitch by remember(session) { mutableFloatStateOf(-20f) }
    var animDuration by remember(session) { mutableFloatStateOf(0f) }
    var animTime by remember(session) { mutableFloatStateOf(-1f) }
    var animPlaying by remember(session) { mutableStateOf(false) }
    var animClips by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    var animClip by remember(session) { mutableIntStateOf(0) }
    LaunchedEffect(session, animPlaying, animDuration) {
        if (!animPlaying || animDuration <= 0f) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val next = animTime.coerceAtLeast(0f) + (now - last) / 1_000_000_000f
                    animTime = if (next > animDuration) next % animDuration else next
                }
                last = now
            }
        }
    }

    var activeBitmap by remember(session) { mutableStateOf<Bitmap?>(null) }
    var previousPageBitmap by remember(session) { mutableStateOf<Bitmap?>(null) }
    var renderedPage by remember(session) { mutableIntStateOf(0) }
    var renderedTileX by remember(session) { mutableIntStateOf(0) }
    var renderedTileY by remember(session) { mutableIntStateOf(0) }
    var renderedZoomPermille by remember(session) { mutableIntStateOf(1000) }
    var renderVersion by remember(session) { mutableIntStateOf(0) }
    var totalPages by remember(session) { mutableIntStateOf(1) }
    var sessionError by remember(session) { mutableStateOf<String?>(null) }

    var cachedDocWidth by remember(session) { mutableFloatStateOf(0f) }
    var cachedDocHeight by remember(session) { mutableFloatStateOf(0f) }
    var cachedColWidthsPx by remember(session) { mutableStateOf<FloatArray?>(null) }
    var cachedRowHeightsPx by remember(session) { mutableStateOf<FloatArray?>(null) }

    var sheetNames by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    var activeSheetIndex by remember(session) { mutableIntStateOf(0) }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            delay(40)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    var activeSearchHighlightPage by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightStart by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightEnd by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightOccurrence by remember(session) { mutableIntStateOf(0) }
    var activeSearchHighlightText by remember(session) { mutableStateOf("") }
    var activeSearchHighlightSheet by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightRow by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightCol by remember(session) { mutableIntStateOf(-1) }
    var activeSearchHighlightMinRenderVersion by remember(session) { mutableIntStateOf(0) }
    var searchHighlightTrigger by remember(session) { mutableIntStateOf(0) }
    val searchHighlightAlpha = remember(session) { Animatable(0f) }

    fun clearActiveSearchHighlight() {
        activeSearchHighlightPage = -1
        activeSearchHighlightStart = -1
        activeSearchHighlightEnd = -1
        activeSearchHighlightText = ""
        activeSearchHighlightSheet = -1
        activeSearchHighlightRow = -1
        activeSearchHighlightCol = -1
        coroutineScope.launch {
            searchHighlightAlpha.snapTo(0f)
        }
    }

    var selStartChar by remember(session) { mutableIntStateOf(-1) }
    var selEndChar by remember(session) { mutableIntStateOf(-1) }
    var sheetSelStartRow by remember(session) { mutableIntStateOf(-1) }
    var sheetSelStartCol by remember(session) { mutableIntStateOf(-1) }
    var sheetSelEndRow by remember(session) { mutableIntStateOf(-1) }
    var sheetSelEndCol by remember(session) { mutableIntStateOf(-1) }

    var activeDragPoint by remember(session) { mutableStateOf<Offset?>(null) }
    var lastDragPoint by remember(session) { mutableStateOf(Offset.Zero) }

    var textLayoutResult by remember(session) { mutableStateOf<TextLayoutResult?>(null) }
    var textSelStart by remember(session) { mutableIntStateOf(-1) }
    var textSelEnd by remember(session) { mutableIntStateOf(-1) }

    var lastTapTimeMs by remember(session) { mutableLongStateOf(0L) }
    var lastTapPosition by remember(session) { mutableStateOf(Offset.Zero) }

    var activeSlideTransitionType by remember(session) { mutableStateOf("fade") }
    var activeSlideTransitionDurationMs by remember(session) { mutableIntStateOf(280) }
    var activeSlideTotalAnimSteps by remember(session) { mutableIntStateOf(0) }
    var currentSlideAnimStep by remember(session, currentPage) { mutableIntStateOf(0) }

    val haptic = LocalHapticFeedback.current
    val textToolbar = LocalTextToolbar.current
    val viewConfig = LocalViewConfiguration.current

    var fontScaleMultiplier by remember(session) { mutableFloatStateOf(1f) }
    var anchorCharOffset by remember(session) { mutableIntStateOf(0) }

    val proxyExt = remember(session) {
        (session as? MainActivity.ProxyDocumentSession)?.extension?.lowercase(Locale.ROOT)?.trim() ?: ""
    }
    val sessionName = remember(session) { session.javaClass.name }

    var isDetectedHexContent by remember(session) { mutableStateOf(false) }

    val isHexSession = remember(session, proxyExt, sessionName, isDetectedHexContent) {
        proxyExt in setOf("hex", "bin", "elf", "dex", "so") ||
                sessionName.contains("Hex", ignoreCase = true) ||
                isDetectedHexContent
    }

    val isPdfOrFontSession = remember(session, proxyExt, sessionName) {
        proxyExt in setOf("pdf", "djvu", "djv", "ttf", "otf", "ttc", "woff", "woff2", "dfont") ||
                sessionName.contains("Pdf", ignoreCase = true) ||
                sessionName.contains("Djvu", ignoreCase = true) ||
                sessionName.contains("Font", ignoreCase = true)
    }

    val isHtmlSession = remember(session, proxyExt, sessionName) {
        val firstPageHead = try { session.getPageText(0).take(500).lowercase(Locale.ROOT) } catch (_: Throwable) { "" }
        proxyExt in setOf("html", "htm", "xhtml", "mhtml", "mht") ||
                sessionName.contains("Mhtml", ignoreCase = true) ||
                sessionName.contains("Html", ignoreCase = true) ||
                firstPageHead.contains("<html") ||
                firstPageHead.contains("<!doctype html") ||
                firstPageHead.contains("content-type: multipart/related") ||
                firstPageHead.contains("content-type: text/html")
    }
    var isHtmlBrowserMode by remember(session, isHtmlSession) { mutableStateOf(isHtmlSession) }
    var htmlBrowserZoomPercent by remember(session) { mutableIntStateOf(100) }
    var activeWebViewRef by remember(session) { mutableStateOf<WebView?>(null) }
    var blockedExternalUrlPrompt by remember(session) { mutableStateOf<String?>(null) }
    var reflowIllustrationOcrText by remember(session) { mutableStateOf("") }
    var showIllustrationOcrDialog by remember(session) { mutableStateOf(false) }

    val isGraphicSession = remember(session, proxyExt, sessionName) {
        proxyExt in setOf(
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur"
        ) || sessionName.contains("ImageRegion", ignoreCase = true)
    }

    val isPresentation = remember(session, proxyExt) {
        session.isPresentationContent ||
                proxyExt in setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp") ||
                sessionName.contains("Ppt", ignoreCase = true) ||
                sessionName.contains("Slide", ignoreCase = true)
    }

    val isSpreadsheet = remember(session, proxyExt) {
        proxyExt in setOf(
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "csv", "tsv", "ods", "ots",
            "db", "db3", "s3db", "sl3", "sqlite", "sqlite3", "accdb", "mdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson"
        ) ||
                sessionName.contains("Sheet", ignoreCase = true) ||
                sessionName.contains("Xls", ignoreCase = true) ||
                sessionName.contains("Csv", ignoreCase = true) ||
                sessionName.contains("Database", ignoreCase = true) ||
                sessionName.contains("Sqlite", ignoreCase = true) ||
                sessionName.contains("Dbf", ignoreCase = true) ||
                sessionName.contains("Parquet", ignoreCase = true)
    }

    val isTableBoundedLayout = isSpreadsheet || isHexSession

    val isPagedWordDocument = remember(session, proxyExt) {
        (proxyExt in setOf("doc", "dot", "docx", "docm", "dotx", "dotm") || sessionName.contains("CleanRoomDocSession")) &&
                try { session.getDocumentWidth() > 0f && session.getDocumentHeight() > 0f } catch (_: Throwable) { false }
    }

    val isTextReflowCandidate = remember(session, proxyExt, isPresentation, isSpreadsheet, isHexSession, isPdfOrFontSession, isPagedWordDocument) {
        val isTextExt = proxyExt in setOf(
            "fb2", "fbz", "epub", "azw3", "azw", "mobi", "prc", "one",
            "txt", "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf",
            "json", "xml", "md", "ini", "cfg", "log", "html", "htm", "xhtml", "mhtml", "mht",
            "yaml", "yml", "py", "js", "ts", "kt", "java", "c", "cpp",
            "h", "hpp", "sh", "sql", "css", "properties", "toml", "gradle"
        )
        !isPagedWordDocument && (isTextExt ||
                sessionName.contains("Ebook", ignoreCase = true) ||
                sessionName.contains("PlainText", ignoreCase = true) ||
                sessionName.contains("DocSession", ignoreCase = true) ||
                sessionName.contains("RtfSession", ignoreCase = true) ||
                sessionName.contains("OneNote", ignoreCase = true)) &&
                !isPresentation && !isSpreadsheet && !session.is3dContent && !isHexSession && !isPdfOrFontSession
    }

    val isHorizontalLayout = remember(isPresentation) {
        isPresentation
    }

    var stateChangeTrigger by remember(session) { mutableIntStateOf(0) }

    var activeBufferWidth by remember(session) { mutableIntStateOf(if (isHorizontalLayout) 1920 else 1080) }
    var activeBufferHeight by remember(session) { mutableIntStateOf(if (isHorizontalLayout) 1080 else 1920) }

    DisposableEffect(session) {
        if (session is MainActivity.ProxyDocumentSession) {
            session.setSessionStateListener {
                stateChangeTrigger++
            }
        }
        onDispose {
            searchJob?.cancel()
            if (session is MainActivity.ProxyDocumentSession) {
                session.setSessionStateListener(null)
            }
            activeBitmap = null
            previousPageBitmap = null
        }
    }

    var rawFullReflowText by remember(session) { mutableStateOf("") }
    var isInitialMetaLoaded by remember(session) { mutableStateOf(false) }
    var currentPageNativeText by remember(session) { mutableStateOf("") }
    var isDraggingSlider by remember(session) { mutableStateOf(false) }
    var isGestureActive by remember(session) { mutableStateOf(false) }

    val normalReflowTextColor = if (palette.isDark) Color(0xFFE0E0E0) else Color(0xFF1A1C24)
    var structuredDoc by remember(session) {
        mutableStateOf(
            StructuredReflowDocument(
                annotatedText = AnnotatedString(""),
                cleanText = "",
                tableRows = emptyList(),
                imageAnchors = emptyList()
            )
        )
    }

    LaunchedEffect(rawFullReflowText, fontScaleMultiplier, normalReflowTextColor, palette.accent) {
        if (rawFullReflowText.isEmpty()) {
            structuredDoc = StructuredReflowDocument(
                annotatedText = AnnotatedString(""),
                cleanText = "",
                tableRows = emptyList(),
                imageAnchors = emptyList()
            )
        } else {
            val parsed = withContext(Dispatchers.Default) {
                parseStructuredReflowDocument(
                    rawFullText = rawFullReflowText,
                    baseFontSizeSp = BASE_REFLOW_FONT_SIZE_SP * fontScaleMultiplier,
                    normalTextColor = normalReflowTextColor,
                    accentHeadingColor = palette.accent,
                    isHtmlSource = isHtmlSession
                )
            }
            structuredDoc = parsed
        }
    }

    val fullReflowText = structuredDoc.cleanText
    val useReflowLayout = isTextReflowCandidate && (fullReflowText.isNotEmpty() || rawFullReflowText.isNotEmpty()) && !isHexSession

    LaunchedEffect(session, isTextReflowCandidate, isSpreadsheet, stateChangeTrigger) {
        withContext(Dispatchers.IO) {
            val err = session.getLastError()
            val w = try { session.getDocumentWidth() } catch (_: Exception) { 0f }
            val h = try { session.getDocumentHeight() } catch (_: Exception) { 0f }
            val pCount = try { session.pageCount.coerceAtLeast(1) } catch (_: Exception) { 1 }
            val loadedSheets = if (isSpreadsheet) {
                try { session.getSheetNames() } catch (_: Exception) { emptyList() }
            } else emptyList()
            val loadedSheetIdx = if (isSpreadsheet) {
                try { session.getActiveSheetIndex() } catch (_: Exception) { 0 }
            } else 0
            val loadedColWidths = if (isSpreadsheet) {
                try { session.getColumnWidths().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            } else null
            val loadedRowHeights = if (isSpreadsheet) {
                try { session.getRowHeights().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            } else null
            val loadedReflow = if (isTextReflowCandidate && rawFullReflowText.isEmpty()) {
                try { session.getPageText(-1) } catch (_: Throwable) { "" }
            } else rawFullReflowText
            val isHexDetected = loadedReflow.startsWith("HEX INSPECTOR |")

            withContext(Dispatchers.Main) {
                sessionError = err
                cachedDocWidth = w
                cachedDocHeight = h
                if (isHexDetected) {
                    isDetectedHexContent = true
                }
                if (isTextReflowCandidate && rawFullReflowText.isEmpty() && !isHexDetected) {
                    rawFullReflowText = loadedReflow
                }
                if (!isTextReflowCandidate || loadedReflow.isEmpty() || isHexDetected) {
                    totalPages = pCount
                }
                if (isSpreadsheet) {
                    sheetNames = loadedSheets
                    activeSheetIndex = loadedSheetIdx
                    cachedColWidthsPx = loadedColWidths
                    cachedRowHeightsPx = loadedRowHeights
                }
                isInitialMetaLoaded = true
            }
        }
    }

    LaunchedEffect(session, currentPage, useReflowLayout, isDraggingSlider) {
        if (!useReflowLayout && !is3D && !isSpreadsheet) {
            if (isDraggingSlider) {
                delay(180L)
            }
            val targetPage = currentPage
            val text = withContext(Dispatchers.IO) {
                try { session.getPageText(targetPage) } catch (_: Throwable) { "" }
            }
            if (currentPage == targetPage) {
                currentPageNativeText = text
                if (text.startsWith("HEX INSPECTOR |")) {
                    isDetectedHexContent = true
                }
            }
        }
    }

    LaunchedEffect(isPresentation, currentPageNativeText) {
        if (isPresentation) {
            val metaMatch = SLIDE_META_TAG_REGEX.find(currentPageNativeText)
            if (metaMatch != null) {
                activeSlideTransitionType = metaMatch.groupValues[1].lowercase(Locale.ROOT)
                activeSlideTransitionDurationMs = (metaMatch.groupValues[2].toIntOrNull() ?: 280).coerceIn(140, 900)
                activeSlideTotalAnimSteps = (metaMatch.groupValues[3].toIntOrNull() ?: 0).coerceIn(0, 15)
            } else {
                activeSlideTransitionType = "push"
                activeSlideTransitionDurationMs = 260
                activeSlideTotalAnimSteps = 0
            }
        }
    }

    val currentSlideSpeakerNotes = remember(isPresentation, currentPageNativeText) {
        if (isPresentation && currentPageNativeText.contains("\n\n[Notes]\n")) {
            currentPageNativeText.substringAfter("\n\n[Notes]\n")
                .replace(SLIDE_META_TAG_REGEX, "")
                .trim()
        } else ""
    }

    val rawCurrentPageNativeTextWithoutNotes = remember(currentPageNativeText) {
        val withoutNotes = if (currentPageNativeText.contains("\n\n[Notes]\n")) {
            currentPageNativeText.substringBefore("\n\n[Notes]\n")
        } else {
            currentPageNativeText
        }
        withoutNotes.replace(SLIDE_META_TAG_REGEX, "").trim()
    }

    val cleanCurrentPageNativeText = remember(rawCurrentPageNativeTextWithoutNotes) {
        stripBoundedNativeMarkers(rawCurrentPageNativeTextWithoutNotes).trim()
    }

    val flingAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    var pageTransitionDirection by remember(session) { mutableIntStateOf(1) }
    var previousReflowPageIdx by remember(session) { mutableIntStateOf(-1) }
    val pageTransitionProgress = remember(session) { Animatable(1f) }

    fun navigateToPageSmoothly(targetPage: Int) {
        val clampedTarget = targetPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        if (clampedTarget == currentPage) return
        pageTransitionDirection = if (clampedTarget > currentPage) 1 else -1
        if (useReflowLayout) {
            previousReflowPageIdx = currentPage
        } else {
            activeBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    try {
                        val prevCopy = previousPageBitmap
                        if (prevCopy != null && !prevCopy.isRecycled && prevCopy.width == bmp.width && prevCopy.height == bmp.height) {
                            val c = android.graphics.Canvas(prevCopy)
                            c.drawBitmap(bmp, 0f, 0f, null)
                        } else {
                            previousPageBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
        currentPage = clampedTarget
        currentSlideAnimStep = 0
        offset = Offset.Zero
        if (!isDraggingSlider) {
            val durMs = if (isPresentation) activeSlideTransitionDurationMs else 220
            coroutineScope.launch {
                pageTransitionProgress.snapTo(0f)
                pageTransitionProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = durMs, easing = FastOutSlowInEasing)
                )
                previousReflowPageIdx = -1
            }
        } else {
            coroutineScope.launch {
                pageTransitionProgress.snapTo(1f)
                previousReflowPageIdx = -1
            }
        }
    }

    var viewportPageRanges by remember(session) { mutableStateOf<List<IntRange>>(emptyList()) }

    val activeReflowIllustrationIndices = remember(currentPage, viewportPageRanges, structuredDoc.imageAnchors) {
        if (!useReflowLayout || structuredDoc.imageAnchors.isEmpty()) {
            emptyList()
        } else {
            val range = viewportPageRanges.getOrElse(currentPage.coerceIn(0, (viewportPageRanges.size - 1).coerceAtLeast(0))) {
                0 until minOf(3500, fullReflowText.length)
            }
            val isLastPage = viewportPageRanges.isEmpty() || currentPage >= viewportPageRanges.size - 1
            val upperBound = if (isLastPage) Int.MAX_VALUE else range.last
            val matched = structuredDoc.imageAnchors.filter { anchor ->
                anchor.charOffsetInCleanText in range.first..upperBound
            }.map { it.imageIndex }.distinct()
            if (matched.isNotEmpty()) {
                matched
            } else if (currentPage == 0 && structuredDoc.imageAnchors.first().charOffsetInCleanText == 0) {
                listOf(structuredDoc.imageAnchors.first().imageIndex)
            } else {
                emptyList()
            }
        }
    }

    var selectedReflowIllustrationPos by remember(currentPage, activeReflowIllustrationIndices) { mutableIntStateOf(0) }
    val activeReflowIllustrationIndex = activeReflowIllustrationIndices.getOrElse(
        selectedReflowIllustrationPos.coerceIn(0, (activeReflowIllustrationIndices.size - 1).coerceAtLeast(0))
    ) { -1 }

    val shouldRunTileRenderer = !useReflowLayout || activeReflowIllustrationIndex >= 0

    LaunchedEffect(
        session,
        isInitialMetaLoaded,
        shouldRunTileRenderer,
        activeBufferWidth,
        activeBufferHeight
    ) {
        if (!isInitialMetaLoaded || !shouldRunTileRenderer) return@LaunchedEffect

        val bmpW = activeBufferWidth.coerceIn(256, 2560)
        val bmpH = activeBufferHeight.coerceIn(256, 2560)
        val doubleBuffers = try {
            arrayOf(
                Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            )
        } catch (_: Throwable) {
            return@LaunchedEffect
        }
        var bufferIndex = 0

        snapshotFlow {
            val themeFlag = if (isDarkTheme) 0 else LIGHT_THEME_PAGE_FLAG
            val langBits = (activeLang.ordinal and 0xF) shl LANG_ORDINAL_SHIFT
            val animStepBits = if (isPresentation && activeSlideTotalAnimSteps > 0) {
                (currentSlideAnimStep.coerceIn(0, 15) and 0xF) shl ANIM_STEP_SHIFT
            } else 0

            if (is3D) {
                TileRenderRequest(
                    page = (if (isDarkTheme) 0 else (1 or LIGHT_THEME_PAGE_FLAG)) or langBits,
                    tileX = yaw.toInt(),
                    tileY = pitch.toInt(),
                    zoomPermille = (scale * 1000f).toInt().coerceIn(100, 10_000),
                    is3D = true,
                    stateTrigger = stateChangeTrigger,
                    bufferWidth = bmpW,
                    bufferHeight = bmpH,
                    animTimeMs = if (animTime < 0f) -1 else (animTime * 1000f).toInt(),
                    animClip = animClip
                )
            } else if (useReflowLayout && activeReflowIllustrationIndex >= 0) {
                TileRenderRequest(
                    page = activeReflowIllustrationIndex or ILLUSTRATION_ONLY_PAGE_FLAG or themeFlag,
                    tileX = 0,
                    tileY = 0,
                    zoomPermille = 1000,
                    is3D = false,
                    stateTrigger = stateChangeTrigger,
                    bufferWidth = bmpW,
                    bufferHeight = bmpH
                )
            } else if (!isTableBoundedLayout) {
                // GPU-first 120 FPS zoom & pan for Photos, PDF, DjVu, Fonts, and Presentations:
                // While fingers are moving (`isGestureActive`), keep the base 1.0x frame pinned (0 IPC lag).
                // Once fingers lift at zoom > 1.15x, render a single crisp high-DPI viewport tile.
                val encodedPage = (currentPage and 0x000FFFFF) or themeFlag or animStepBits
                val shouldFetchHighResTile = !isGestureActive && scale > 1.15f
                TileRenderRequest(
                    page = encodedPage,
                    tileX = if (shouldFetchHighResTile) (-offset.x).toInt() else 0,
                    tileY = if (shouldFetchHighResTile) (-offset.y).toInt() else 0,
                    zoomPermille = if (shouldFetchHighResTile) (scale * 1000f).toInt().coerceIn(1150, 10_000) else 1000,
                    is3D = false,
                    stateTrigger = stateChangeTrigger,
                    bufferWidth = bmpW,
                    bufferHeight = bmpH
                )
            } else {
                val maxPermille = if (isSpreadsheet) 4_000 else 8_000
                val encodedPage = (currentPage and 0x000FFFFF) or themeFlag
                TileRenderRequest(
                    page = encodedPage,
                    tileX = (-offset.x).toInt(),
                    tileY = (-offset.y).toInt(),
                    zoomPermille = (scale * 1000f).toInt().coerceIn(500, maxPermille),
                    is3D = false,
                    stateTrigger = stateChangeTrigger,
                    bufferWidth = bmpW,
                    bufferHeight = bmpH
                )
            }
        }
            .conflate()
            .collect { req ->
                val reqCleanPage = req.page and 0x000FFFFF
                if (renderedPage == reqCleanPage && !req.is3D && !isTableBoundedLayout &&
                    (req.zoomPermille != renderedZoomPermille || req.tileX != renderedTileX || req.tileY != renderedTileY)
                ) {
                    delay(80L)
                }
                val backBmp = doubleBuffers[bufferIndex]
                bufferIndex = 1 - bufferIndex

                withContext(Dispatchers.Default) {
                    try {
                        if (!is3D && !isTableBoundedLayout) {
                            backBmp.eraseColor(android.graphics.Color.TRANSPARENT)
                        } else {
                            backBmp.eraseColor(if (isDarkTheme) android.graphics.Color.rgb(0x10, 0x10, 0x14) else android.graphics.Color.rgb(0xE9, 0xEC, 0xF2))
                        }
                        if (req.is3D) {
                            session.setAnimationClip(req.animClip)
                            session.setAnimationTime(if (req.animTimeMs < 0) -1f else req.animTimeMs / 1000f)
                        }
                        session.renderTile(
                            pageIndex = req.page,
                            tileX = req.tileX,
                            tileY = req.tileY,
                            zoomPermille = req.zoomPermille,
                            bitmap = backBmp
                        )
                        val latestErr = session.getLastError()
                        val animDur = if (req.is3D) session.getAnimationDuration() else 0f
                        val clipList = if (req.is3D) session.getAnimationClips() else emptyList()
                        val w = session.getDocumentWidth()
                        val h = session.getDocumentHeight()
                        val cw = if (isSpreadsheet) {
                            try { session.getColumnWidths().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
                        } else null
                        val rh = if (isSpreadsheet) {
                            try { session.getRowHeights() } catch (_: Exception) { null }
                        } else null
                        val pCount = session.pageCount.coerceAtLeast(1)

                        withContext(Dispatchers.Main) {
                            activeBitmap = backBmp
                            if (req.is3D && clipList != animClips) animClips = clipList
                            if (req.is3D && animDur != animDuration) {
                                animDuration = animDur
                                if (animDur <= 0f) {
                                    animPlaying = false
                                    animTime = -1f
                                }
                            }
                            renderedPage = req.page and 0x000FFFFF
                            renderedTileX = req.tileX
                            renderedTileY = req.tileY
                            renderedZoomPermille = req.zoomPermille
                            sessionError = latestErr
                            if (w > 0f) cachedDocWidth = w
                            if (h > 0f) cachedDocHeight = h
                            if (isSpreadsheet && cw != null) cachedColWidthsPx = cw
                            if (isSpreadsheet && rh != null) cachedRowHeightsPx = rh.takeIf { it.isNotEmpty() }
                            renderVersion++
                            if (!useReflowLayout) {
                                totalPages = pCount
                            }
                            if (isTableBoundedLayout) {
                                offset = clampCanvasOffset(
                                    rawOffset = offset,
                                    scale = scale,
                                    isSpreadsheet = true,
                                    isHorizontalLayout = isHorizontalLayout,
                                    docWidth = w,
                                    docHeight = h,
                                    viewportWidth = bmpW.toFloat(),
                                    viewportHeight = bmpH.toFloat()
                                )
                            }
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            sessionError = e.localizedMessage ?: strings.errorRenderFailed
                        }
                    }
                }
            }
    }

    var ocrLines by remember(session) { mutableStateOf<List<RecognizedLineUi>>(emptyList()) }
    var ocrChars by remember(session) { mutableStateOf<List<RecognizedCharUi>>(emptyList()) }
    var ocrText by remember(session) { mutableStateOf("") }
    val ocrCache = remember(session) { mutableMapOf<Long, Triple<List<RecognizedLineUi>, List<RecognizedCharUi>, String>>() }

    val currentContext = LocalContext.current
    LaunchedEffect(Unit) {
        PaddleOcrManager.initialize(currentContext.applicationContext)
    }

    LaunchedEffect(useReflowLayout, activeReflowIllustrationIndex, renderVersion) {
        if (session.isCopyRestricted || !useReflowLayout || activeReflowIllustrationIndex < 0) {
            reflowIllustrationOcrText = ""
            return@LaunchedEffect
        }
        val bmp = activeBitmap ?: return@LaunchedEffect
        if (bmp.isRecycled) return@LaunchedEffect
        val copyBmp = try { bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null } ?: return@LaunchedEffect
        val recognized = withContext(OCR_BACKGROUND_DISPATCHER) {
            try {
                val payload = PaddleOcrManager.recognize(
                    bitmap = copyBmp,
                    pageTextHint = "",
                    preferredLanguageCode = activeLang.code
                )
                payload.lines.joinToString("\n") { it.text }.trim()
            } catch (_: Throwable) {
                ""
            } finally {
                if (!copyBmp.isRecycled) copyBmp.recycle()
            }
        }
        reflowIllustrationOcrText = recognized
    }

    fun buildOcrCacheKey(
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

    fun clearAllSelections() {
        selStartChar = -1
        selEndChar = -1
        textSelStart = -1
        textSelEnd = -1
        sheetSelStartRow = -1
        sheetSelStartCol = -1
        sheetSelEndRow = -1
        sheetSelEndCol = -1
        activeDragPoint = null
        textToolbar.hide()
    }

    val hasActiveTextSelection = selStartChar >= 0 || sheetSelStartRow >= 0 || textSelStart >= 0
    val hasUiStateToDismiss = isHtmlBrowserMode || isSearchVisible || showSpeakerNotesDialog || hasActiveTextSelection || scale > 1.05f

    // 1. Instant BackHandler for dismissing UI popups, HTML browser mode, search bar, text selections, and zoom scale
    BackHandler(enabled = !isExternalDrawerOpen && hasUiStateToDismiss) {
        when {
            isHtmlBrowserMode -> isHtmlBrowserMode = false
            hasActiveTextSelection -> clearAllSelections()
            isSearchVisible -> {
                isSearchVisible = false
                searchQuery = ""
                isSearchDropdownExpanded = false
                clearActiveSearchHighlight()
            }
            showSpeakerNotesDialog -> showSpeakerNotesDialog = false
            scale > 1.05f -> {
                scale = 1f
                offset = Offset.Zero
            }
        }
    }

    // 2. PredictiveBackHandler strictly for document exit shrink animation when UI is clean
    PredictiveBackHandler(enabled = !isExternalDrawerOpen && onNavigateBack != null && !hasUiStateToDismiss) { progress ->
        try {
            progress.collect { backEvent ->
                val p = backEvent.progress
                val targetScale = 1f - p * 0.14f
                val targetAlpha = 1f - p * 0.15f
                val targetCorner = p * 28f
                predictiveBackScale.snapTo(targetScale)
                predictiveBackAlpha.snapTo(targetAlpha)
                predictiveBackCornerRadius.snapTo(targetCorner)
            }
            onNavigateBack?.invoke()
        } catch (_: kotlinx.coroutines.CancellationException) {
            predictiveBackScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            predictiveBackAlpha.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            predictiveBackCornerRadius.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
        } finally {
            predictiveBackScale.snapTo(1f)
            predictiveBackAlpha.snapTo(1f)
            predictiveBackCornerRadius.snapTo(0f)
        }
    }

    LaunchedEffect(currentPage, activeSheetIndex, isDarkTheme, session) {
        clearAllSelections()

        val curZoomPermille = (scale * 1000f).roundToInt().coerceIn(500, 10_000)
        val curTileX = (-offset.x).roundToInt()
        val curTileY = (-offset.y).roundToInt()
        val cacheKey = buildOcrCacheKey(
            page = currentPage,
            sheetIdx = activeSheetIndex,
            darkTheme = isDarkTheme,
            zoomPermilleVal = curZoomPermille,
            tileXVal = curTileX,
            tileYVal = curTileY
        )
        val cached = ocrCache[cacheKey]
        if (cached != null) {
            ocrLines = cached.first
            ocrChars = cached.second
            ocrText = cached.third
        } else {
            ocrLines = emptyList()
            ocrChars = emptyList()
            ocrText = ""
        }
    }

    LaunchedEffect(
        renderedPage,
        renderedZoomPermille,
        renderedTileX,
        renderedTileY,
        rawCurrentPageNativeTextWithoutNotes,
        activeSheetIndex,
        isDarkTheme,
        useReflowLayout,
        is3D,
        isHexSession,
        isSpreadsheet,
        isDraggingSlider,
        cachedDocWidth,
        cachedDocHeight
    ) {
        if (isDraggingSlider || useReflowLayout || is3D || isSpreadsheet) return@LaunchedEffect
        val bmp = activeBitmap ?: return@LaunchedEffect
        if (bmp.isRecycled) return@LaunchedEffect

        val snapPage = renderedPage
        val snapSheet = activeSheetIndex
        val snapDark = isDarkTheme
        val snapZoomPermille = renderedZoomPermille
        val snapTileX = renderedTileX
        val snapTileY = renderedTileY

        val key = buildOcrCacheKey(snapPage, snapSheet, snapDark, snapZoomPermille, snapTileX, snapTileY)
        if (session.isCopyRestricted) {
            ocrLines = emptyList()
            ocrChars = emptyList()
            ocrText = ""
            return@LaunchedEffect
        }
        val cached = ocrCache[key]
        if (cached != null) {
            ocrLines = cached.first
            ocrChars = cached.second
            ocrText = cached.third
            return@LaunchedEffect
        }

        if (isHexSession) {
            if (cleanCurrentPageNativeText.isNotEmpty()) {
                val (fbLines, fbChars) = buildFallbackCharsFromPageText(
                    rawPageText = cleanCurrentPageNativeText,
                    bmpWidth = bmp.width,
                    bmpHeight = bmp.height,
                    isHexLayout = true,
                    docWidth = cachedDocWidth,
                    docHeight = cachedDocHeight,
                    zoomPermille = snapZoomPermille,
                    tileX = snapTileX,
                    tileY = snapTileY
                )
                ocrLines = fbLines
                ocrChars = fbChars
                ocrText = cleanCurrentPageNativeText
                ocrCache[key] = Triple(fbLines, fbChars, cleanCurrentPageNativeText)
            }
            return@LaunchedEffect
        }

        // Immediate frame-1 population for PDF pages with native Android 15+ [PDF_BOX:...] coordinates,
        // followed by background ink-tightened alignment in PaddleOcrManager.
        if (snapPage == currentPage && rawCurrentPageNativeTextWithoutNotes.contains("[PDF_BOX:")) {
            val (pdfLines, pdfChars) = buildFallbackCharsFromPageText(
                rawPageText = rawCurrentPageNativeTextWithoutNotes,
                bmpWidth = bmp.width,
                bmpHeight = bmp.height,
                isHexLayout = false,
                docWidth = cachedDocWidth,
                docHeight = cachedDocHeight,
                zoomPermille = snapZoomPermille,
                tileX = snapTileX,
                tileY = snapTileY
            )
            if (pdfChars.isNotEmpty() && ocrChars.isEmpty()) {
                ocrLines = pdfLines
                ocrChars = pdfChars
                ocrText = cleanCurrentPageNativeText
            }
        }

        while (pageTransitionProgress.isRunning) {
            delay(24L)
        }
        val isAwaitingSearchHighlight = (activeSearchHighlightPage == currentPage && activeSearchHighlightText.isNotEmpty())
        delay(if (isAwaitingSearchHighlight) 30L else 240L)

        val latestBmp = activeBitmap ?: return@LaunchedEffect
        if (latestBmp.isRecycled || renderedPage != snapPage) return@LaunchedEffect

        val snapHint = if (snapPage == currentPage) rawCurrentPageNativeTextWithoutNotes else ""
        val isolatedOcrBmp = try {
            latestBmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        } ?: return@LaunchedEffect

        withContext(OCR_BACKGROUND_DISPATCHER) {
            try {
                val ocrPayload = PaddleOcrManager.recognize(
                    bitmap = isolatedOcrBmp,
                    pageTextHint = snapHint,
                    zoomPermille = snapZoomPermille,
                    tileX = snapTileX,
                    tileY = snapTileY,
                    docWidth = cachedDocWidth,
                    docHeight = cachedDocHeight,
                    preferredLanguageCode = activeLang.code
                )
                val snapPageBounds = computeDocumentPageBoundsOnBuffer(
                    bufWidth = isolatedOcrBmp.width.toFloat(),
                    bufHeight = isolatedOcrBmp.height.toFloat(),
                    docWidth = cachedDocWidth,
                    docHeight = cachedDocHeight,
                    zoomPermille = snapZoomPermille,
                    tileX = snapTileX,
                    tileY = snapTileY,
                    isHexLayout = false
                )
                var lineList = ocrPayload.lines.map {
                    val rf = android.graphics.RectF(it.rect)
                    RecognizedLineUi(it.text, it.rect, normalizeRectToPage(rf, snapPageBounds))
                }
                var charList = ocrPayload.chars.map {
                    val rf = android.graphics.RectF(it.rect)
                    RecognizedCharUi(it.text, it.lineIndex, rf, normalizeRectToPage(rf, snapPageBounds))
                }
                if (charList.isEmpty() && snapHint.isNotBlank()) {
                    val (fbLines, fbChars) = buildFallbackCharsFromPageText(
                        rawPageText = rawCurrentPageNativeTextWithoutNotes,
                        bmpWidth = isolatedOcrBmp.width,
                        bmpHeight = isolatedOcrBmp.height,
                        isHexLayout = false,
                        docWidth = cachedDocWidth,
                        docHeight = cachedDocHeight,
                        zoomPermille = snapZoomPermille,
                        tileX = snapTileX,
                        tileY = snapTileY
                    )
                    lineList = fbLines
                    charList = fbChars
                }
                val fullOcr = lineList.joinToString(" ") { it.text }

                withContext(Dispatchers.Main) {
                    if (renderedPage == snapPage) {
                        if (ocrCache.size > 24) {
                            ocrCache.clear()
                        }
                        ocrCache[key] = Triple(lineList, charList, fullOcr)
                        // Preserve active character indices when the user has an active selection on the page;
                        // normRect already projects accurately at any zoom/pan without drift.
                        if (selStartChar < 0 || selEndChar < 0 || ocrChars.isEmpty()) {
                            ocrLines = lineList
                            ocrChars = charList
                            ocrText = fullOcr
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (!isolatedOcrBmp.isRecycled) {
                    isolatedOcrBmp.recycle()
                }
            }
        }
    }

    LaunchedEffect(searchHighlightTrigger) {
        if (searchHighlightTrigger > 0) {
            searchHighlightAlpha.snapTo(1f)
            if (!useReflowLayout) {
                val minVer = activeSearchHighlightMinRenderVersion
                withTimeoutOrNull(3.seconds) {
                    snapshotFlow { renderVersion to ocrChars.isNotEmpty() }
                        .first { (ver, hasOcr) ->
                            if (isSpreadsheet) {
                                ver >= minVer
                            } else {
                                ver >= minVer && (hasOcr || isHexSession)
                            }
                        }
                }
            }

            delay(1000L)

            searchHighlightAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 2000, easing = LinearOutSlowInEasing)
            )
            activeSearchHighlightPage = -1
            activeSearchHighlightStart = -1
            activeSearchHighlightEnd = -1
            activeSearchHighlightText = ""
            activeSearchHighlightSheet = -1
            activeSearchHighlightRow = -1
            activeSearchHighlightCol = -1
        }
    }

    fun findCharIndexAt(
        targetX: Float,
        targetY: Float,
        chars: List<RecognizedCharUi>,
        rectResolver: (RecognizedCharUi) -> android.graphics.RectF = { it.rect },
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

    fun findWordRangeAt(index: Int, chars: List<RecognizedCharUi>): IntRange {
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

    val appContext = LocalContext.current.applicationContext
    val clipManager = remember { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    val magnifierModifier = if (activeDragPoint != null) {
        Modifier.magnifier(
            sourceCenter = { activeDragPoint ?: lastDragPoint },
            magnifierCenter = { (activeDragPoint ?: lastDragPoint) - Offset(0f, 110f) },
            zoom = 1.35f
        )
    } else Modifier

    val isSearchSupported = (isHtmlSession && isHtmlBrowserMode) ||
            (!is3D && (useReflowLayout || isTextReflowCandidate || isPagedWordDocument || isSpreadsheet || isPresentation || isPdfOrFontSession || totalPages > 1 || isHexSession))

    fun triggerDocumentSearch() {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()

        if (isHtmlSession && isHtmlBrowserMode) {
            lastExecutedQuery = trimmed
            hasExecutedSearch = true
            activeWebViewRef?.findAllAsync(trimmed)
            activeWebViewRef?.findNext(true)
            return
        }

        if (trimmed.equals(lastExecutedQuery, ignoreCase = false) && hasExecutedSearch && !isSearchingInProgress) {
            isSearchDropdownExpanded = true
            return
        }

        searchJob?.cancel()
        isSearchingInProgress = true
        isSearchDropdownExpanded = true
        searchJob = coroutineScope.launch(Dispatchers.IO) {
            val matches = collectDocumentSearchMatches(
                query = trimmed,
                useReflowLayout = useReflowLayout,
                fullReflowText = fullReflowText,
                viewportPageRanges = viewportPageRanges,
                totalPages = totalPages,
                isHexSession = isHexSession,
                isSpreadsheet = isSpreadsheet,
                sheetNames = sheetNames,
                session = session
            )
            withContext(Dispatchers.Main) {
                lastExecutedQuery = trimmed
                searchResults = matches
                hasExecutedSearch = true
                isSearchingInProgress = false
                selectedMatchIndex = -1
                isSearchDropdownExpanded = true
                searchDropdownListState.scrollToItem(0)
            }
        }
    }

    fun applyZoomStep(delta: Float) {
        val oldScale = scale
        val minScale = when {
            is3D -> 0.2f
            else -> 1.0f
        }
        val maxScale = when {
            is3D -> 8.0f
            isSpreadsheet -> 4.0f
            else -> 8.0f
        }
        val newScale = (oldScale + delta).coerceIn(minScale, maxScale)
        scale = newScale
        if (is3D) return

        val scaledOffset = if (oldScale > 0.001f) offset * (newScale / oldScale) else offset
        offset = if (newScale <= 1.02f && !isTableBoundedLayout) {
            Offset.Zero
        } else {
            clampCanvasOffset(
                rawOffset = scaledOffset,
                scale = newScale,
                isSpreadsheet = isTableBoundedLayout,
                isHorizontalLayout = isHorizontalLayout,
                docWidth = cachedDocWidth,
                docHeight = cachedDocHeight,
                viewportWidth = activeBufferWidth.toFloat(),
                viewportHeight = activeBufferHeight.toFloat()
            )
        }
    }

    val rootInsetsModifier = if (isPresentation) {
        Modifier.displayCutoutPadding()
    } else {
        Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
    }

    val hasAnyActiveSelection = (useReflowLayout && textSelStart >= 0 && textSelEnd >= 0) ||
            (!useReflowLayout && isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0 && sheetSelEndRow >= 0 && sheetSelEndCol >= 0) ||
            (!useReflowLayout && !isSpreadsheet && selStartChar >= 0 && selEndChar >= 0)

    fun copyActiveSelectionToClipboard(fullPageTextForReflow: String) {
        if (session.isCopyRestricted) {
            clearAllSelections()
            return
        }
        if (useReflowLayout && fullPageTextForReflow.isNotEmpty() && textSelStart >= 0 && textSelEnd >= 0) {
            val minI = minOf(textSelStart, textSelEnd).coerceIn(0, fullPageTextForReflow.length - 1)
            val maxI = (maxOf(textSelStart, textSelEnd) + 1).coerceIn(minI, fullPageTextForReflow.length)
            val copied = fullPageTextForReflow.substring(minI, maxI)
            if (copied.isNotEmpty()) {
                clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Text", copied))
            }
        } else if (!useReflowLayout && isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0) {
            val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
            val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
            val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
            val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)
            coroutineScope.launch(Dispatchers.IO) {
                val copied = try {
                    session.getSelectedRangeText(r0, c0, r1, c1)
                } catch (_: Exception) { "" }
                if (copied.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Range", copied))
                    }
                }
            }
        } else if (!useReflowLayout && ocrChars.isNotEmpty() && selStartChar >= 0 && selEndChar >= 0) {
            val minI = minOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
            val maxI = maxOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
            val copied = buildString {
                var lastLine = -1
                var prevRight = -1f
                var prevWasSpace = false
                for (i in minI..maxI) {
                    val ch = ocrChars[i]
                    val isSpaceToken = ch.text.isBlank()
                    if (lastLine != -1 && ch.lineIndex != lastLine) {
                        append('\n')
                        prevRight = -1f
                        prevWasSpace = true
                    } else if (lastLine == ch.lineIndex &&
                        prevRight >= 0f &&
                        !prevWasSpace &&
                        !isSpaceToken
                    ) {
                        val avgH = ch.rect.height().coerceAtLeast(8f)
                        if (ch.rect.left - prevRight > avgH * 0.30f) {
                            append(' ')
                            prevWasSpace = true
                        }
                    }
                    if (isSpaceToken) {
                        if (!prevWasSpace && isNotEmpty()) {
                            append(' ')
                            prevWasSpace = true
                        }
                    } else {
                        append(ch.text)
                        prevWasSpace = false
                    }
                    lastLine = ch.lineIndex
                    prevRight = ch.rect.right
                }
            }.trim()
            if (copied.isNotEmpty()) {
                clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Text", copied))
            }
        } else if (!useReflowLayout && cleanCurrentPageNativeText.isNotBlank()) {
            clipManager?.setPrimaryClip(ClipData.newPlainText("Page Text", cleanCurrentPageNativeText))
        }
        clearAllSelections()
    }

    if (showSpeakerNotesDialog && currentSlideSpeakerNotes.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSpeakerNotesDialog = false },
            confirmButton = {
                Button(onClick = { showSpeakerNotesDialog = false }) {
                    Text(strings.closeBtn)
                }
            },
            dismissButton = if (!session.isCopyRestricted) {
                {
                    TextButton(
                        onClick = {
                            clipManager?.setPrimaryClip(ClipData.newPlainText("Slide Notes", currentSlideSpeakerNotes))
                            showSpeakerNotesDialog = false
                        }
                    ) {
                        Text(strings.copyActionButton, color = palette.accent)
                    }
                }
            } else null,
            title = {
                Text(
                    text = "${strings.slideNotesButton} (${strings.pageLabel} ${currentPage + 1})",
                    color = palette.primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = currentSlideSpeakerNotes,
                        color = palette.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    val pScale = predictiveBackScale.value
    val pAlpha = predictiveBackAlpha.value
    val pCorner = predictiveBackCornerRadius.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = pScale
                scaleY = pScale
                alpha = pAlpha
            }
            .clip(RoundedCornerShape(pCorner.dp))
            .background(if (is3D || isPresentation || isTableBoundedLayout) palette.background else palette.canvasBackground)
            .then(rootInsetsModifier)
    ) {
        if (!isPresentation) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(10f)
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onOpenSettingsRequest,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("⚙", color = palette.primaryText, fontSize = 16.sp)
                        }

                        if (is3D) {
                            Button(onClick = { yaw = 25f; pitch = -20f; scale = 1f; fontScaleMultiplier = 1f }) {
                                Text(strings.reset3dButton)
                            }
                        } else {
                            if (isSearchSupported) {
                                Button(
                                    onClick = {
                                        isSearchVisible = !isSearchVisible
                                        if (!isSearchVisible) {
                                            isSearchDropdownExpanded = false
                                            clearActiveSearchHighlight()
                                            activeWebViewRef?.clearMatches()
                                        } else if (!isHtmlBrowserMode && hasExecutedSearch && searchResults.isNotEmpty()) {
                                            isSearchDropdownExpanded = true
                                        }
                                    }
                                ) {
                                    Text(if (isSearchVisible) strings.closeSearchButton else strings.searchButton)
                                }
                            }
                            if (!session.isCopyRestricted && !useReflowLayout && !isSpreadsheet && !isHtmlSession && (ocrChars.isNotEmpty() || cleanCurrentPageNativeText.isNotBlank())) {
                                OutlinedButton(
                                    onClick = {
                                        if (session.isCopyRestricted) return@OutlinedButton
                                        if (ocrChars.isNotEmpty()) {
                                            selStartChar = 0
                                            selEndChar = ocrChars.lastIndex
                                        } else if (cleanCurrentPageNativeText.isNotBlank()) {
                                            clipManager?.setPrimaryClip(ClipData.newPlainText("OCR Text", cleanCurrentPageNativeText))
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = strings.copyActionButton,
                                        color = palette.accent,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    Button(onClick = onOpenFileRequest) {
                        Text(strings.changeFileButton)
                    }
                }

                AnimatedVisibility(visible = isSearchVisible && isSearchSupported) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            DisableFullscreenImeWrapper {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { newVal ->
                                        searchQuery = newVal
                                        if (newVal.trim() != lastExecutedQuery) {
                                            hasExecutedSearch = false
                                        }
                                    },
                                    placeholder = { Text(strings.searchPlaceholder, color = palette.secondaryText) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { triggerDocumentSearch() }),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = palette.primaryText,
                                        unfocusedTextColor = palette.primaryText
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                )
                            }
                        }

                        if (hasExecutedSearch && searchResults.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = { isSearchDropdownExpanded = !isSearchDropdownExpanded },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                val arrow = if (isSearchDropdownExpanded) "▲" else "▼"
                                Text(
                                    text = "$arrow ${searchResults.size}",
                                    color = palette.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = { triggerDocumentSearch() }
                        ) {
                            Text(strings.findButton)
                        }
                    }
                }
                MissingResourcesBanner(session, stateChangeTrigger) { stateChangeTrigger++ }
                if (is3D) {
                    if (animDuration > 0f) {
                        AnimationPlaybackBar(
                            duration = animDuration,
                            time = animTime,
                            playing = animPlaying,
                            clips = animClips,
                            clip = animClip,
                            onClip = { c ->
                                animClip = c
                                animTime = if (animPlaying) 0f else -1f
                            },
                            onToggle = { animPlaying = !animPlaying },
                            onSeek = { t ->
                                animPlaying = false
                                animTime = t
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = palette.border)
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .systemGestureExclusion()
                .then(magnifierModifier)
        ) {
            val density = LocalDensity.current
            val rawViewportWidthPx = constraints.maxWidth.coerceAtLeast(100)
            val rawViewportHeightPx = constraints.maxHeight.coerceAtLeast(100)

            LaunchedEffect(rawViewportWidthPx, rawViewportHeightPx) {
                val maxDim = 1920f
                val vW = rawViewportWidthPx.toFloat()
                val vH = rawViewportHeightPx.toFloat()
                val scaleFactor = maxDim / maxOf(vW, vH).coerceAtLeast(1f)
                val targetW = ((vW * scaleFactor).roundToInt() and -2).coerceIn(320, 2400)
                val targetH = ((vH * scaleFactor).roundToInt() and -2).coerceIn(320, 2400)
                if (abs(targetW - activeBufferWidth) > 8 || abs(targetH - activeBufferHeight) > 8) {
                    activeBufferWidth = targetW
                    activeBufferHeight = targetH
                }
            }

            val paddingHorizPx = with(density) { 20.dp.toPx() }
            val paddingVertPx = with(density) { 14.dp.toPx() }
            val availWidthPx = (rawViewportWidthPx - (paddingHorizPx * 2f).toInt()).coerceAtLeast(100)
            val availHeightPx = (rawViewportHeightPx - (paddingVertPx * 2f).toInt()).coerceAtLeast(100)
            val illustrationReservedHeightPx = with(density) { 216.dp.toPx() }.toInt()

            val textMeasurer = rememberTextMeasurer()
            val reflowTextStyle = remember(fontScaleMultiplier, normalReflowTextColor) {
                TextStyle(
                    color = normalReflowTextColor,
                    fontSize = (BASE_REFLOW_FONT_SIZE_SP * fontScaleMultiplier).sp,
                    lineHeight = (BASE_REFLOW_FONT_SIZE_SP * 1.4f * fontScaleMultiplier).sp
                )
            }

            LaunchedEffect(
                structuredDoc.annotatedText,
                structuredDoc.imageAnchors,
                availWidthPx,
                availHeightPx,
                illustrationReservedHeightPx,
                reflowTextStyle,
                useReflowLayout
            ) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    val savedAnchorOffset = viewportPageRanges
                        .getOrNull(currentPage.coerceIn(0, (viewportPageRanges.size - 1).coerceAtLeast(0)))
                        ?.first ?: anchorCharOffset

                    if (viewportPageRanges.isEmpty() && fullReflowText.length > 35_000) {
                        val quickInitial = withContext(Dispatchers.Default) {
                            computeViewportPageRanges(
                                fullText = fullReflowText,
                                measurePageSlice = { start, end ->
                                    textMeasurer.measure(
                                        text = structuredDoc.annotatedText.subSequence(start, end),
                                        style = reflowTextStyle,
                                        constraints = Constraints(maxWidth = availWidthPx)
                                    )
                                },
                                viewportHeightPx = availHeightPx,
                                imageAnchors = structuredDoc.imageAnchors,
                                illustrationBoxHeightPx = illustrationReservedHeightPx,
                                maxPagesToCompute = 20
                            )
                        }
                        if (quickInitial.isNotEmpty()) {
                            viewportPageRanges = quickInitial
                            totalPages = maxOf(quickInitial.size, fullReflowText.length / 2600)
                        }
                    } else if (viewportPageRanges.isNotEmpty()) {
                        delay(25)
                    }

                    val computed = withContext(Dispatchers.Default) {
                        computeViewportPageRanges(
                            fullText = fullReflowText,
                            measurePageSlice = { start, end ->
                                textMeasurer.measure(
                                    text = structuredDoc.annotatedText.subSequence(start, end),
                                    style = reflowTextStyle,
                                    constraints = Constraints(maxWidth = availWidthPx)
                                )
                            },
                            viewportHeightPx = availHeightPx,
                            imageAnchors = structuredDoc.imageAnchors,
                            illustrationBoxHeightPx = illustrationReservedHeightPx
                        )
                    }
                    viewportPageRanges = computed
                    totalPages = computed.size.coerceAtLeast(1)
                    val restoredPage = computed.indexOfFirst { savedAnchorOffset in it }
                        .takeIf { it >= 0 }
                        ?: currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    currentPage = restoredPage
                    anchorCharOffset = computed.getOrNull(restoredPage)?.first ?: 0
                } else if (!useReflowLayout) {
                    viewportPageRanges = emptyList()
                }
            }

            val currentRange = remember(useReflowLayout, fullReflowText, viewportPageRanges, currentPage) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    if (viewportPageRanges.isNotEmpty()) {
                        viewportPageRanges.getOrElse(currentPage.coerceIn(0, viewportPageRanges.lastIndex)) { 0 until 0 }
                    } else {
                        0 until minOf(fullReflowText.length, 4500)
                    }
                } else {
                    0 until 0
                }
            }

            LaunchedEffect(currentRange) {
                if (useReflowLayout && !currentRange.isEmpty()) {
                    anchorCharOffset = currentRange.first
                }
            }

            val pageText = remember(useReflowLayout, fullReflowText, currentRange, cleanCurrentPageNativeText) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    if (currentRange.first <= currentRange.last && currentRange.last < fullReflowText.length) {
                        fullReflowText.substring(currentRange.first, currentRange.last + 1)
                    } else ""
                } else {
                    cleanCurrentPageNativeText
                }
            }

            val previousReflowText = remember(useReflowLayout, fullReflowText, viewportPageRanges, previousReflowPageIdx) {
                if (useReflowLayout && previousReflowPageIdx in viewportPageRanges.indices) {
                    val prevR = viewportPageRanges[previousReflowPageIdx]
                    if (!prevR.isEmpty() && prevR.last < structuredDoc.annotatedText.length) {
                        structuredDoc.annotatedText.subSequence(prevR.first, prevR.last + 1)
                    } else null
                } else null
            }

            val pageTableRows = remember(useReflowLayout, structuredDoc.tableRows, currentRange) {
                if (!useReflowLayout || structuredDoc.tableRows.isEmpty() || currentRange.isEmpty()) {
                    emptyList()
                } else {
                    val pageStart = currentRange.first
                    val pageEndExclusive = currentRange.last + 1
                    structuredDoc.tableRows.mapNotNull { row ->
                        val ovStart = maxOf(row.charStart, pageStart)
                        val ovEnd = minOf(row.charEndExclusive, pageEndExclusive)
                        if (ovEnd > ovStart) {
                            TableRowLayoutMeta(
                                charStart = ovStart - pageStart,
                                charEndExclusive = ovEnd - pageStart,
                                isHeaderRow = row.isHeaderRow,
                                separatorOffsets = row.separatorOffsets
                                    .filter { it in ovStart until ovEnd }
                                    .map { it - pageStart }
                            )
                        } else null
                    }
                }
            }

            if (isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) {
                val htmlSource = rawFullReflowText.ifBlank { cleanCurrentPageNativeText }
                val bgHex = remember(palette.canvasBackground) {
                    String.format(
                        Locale.US,
                        "#%02X%02X%02X",
                        (palette.canvasBackground.red * 255).roundToInt().coerceIn(0, 255),
                        (palette.canvasBackground.green * 255).roundToInt().coerceIn(0, 255),
                        (palette.canvasBackground.blue * 255).roundToInt().coerceIn(0, 255)
                    )
                }
                val fgHex = remember(palette.primaryText) {
                    String.format(
                        Locale.US,
                        "#%02X%02X%02X",
                        (palette.primaryText.red * 255).roundToInt().coerceIn(0, 255),
                        (palette.primaryText.green * 255).roundToInt().coerceIn(0, 255),
                        (palette.primaryText.blue * 255).roundToInt().coerceIn(0, 255)
                    )
                }
                val accentHex = remember(palette.accent) {
                    String.format(
                        Locale.US,
                        "#%02X%02X%02X",
                        (palette.accent.red * 255).roundToInt().coerceIn(0, 255),
                        (palette.accent.green * 255).roundToInt().coerceIn(0, 255),
                        (palette.accent.blue * 255).roundToInt().coerceIn(0, 255)
                    )
                }

                val sandboxedHtml = remember(htmlSource, bgHex, fgHex, accentHex) {
                    val cspAndThemeHeader = """
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline' data:; img-src data: blob: file: about:; font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none';">
                        <style id="ffs-theme-defaults">
                            :root { color-scheme: ${if (palette.isDark) "dark" else "light"}; }
                            html, body {
                                background-color: $bgHex !important;
                                color: $fgHex !important;
                                font-family: sans-serif;
                                line-height: 1.5;
                                margin: 12px;
                                word-wrap: break-word;
                            }
                            a { color: $accentHex !important; }
                            img, svg, video, canvas, table { max-width: 100%; height: auto; }
                            pre, code { overflow-x: auto; max-width: 100%; }
                        </style>
                    """.trimIndent()
                    if (htmlSource.contains("<head", ignoreCase = true)) {
                        htmlSource.replaceFirst(Regex("(?i)<head[^>]*>"), "$0\n$cspAndThemeHeader")
                    } else {
                        "<!DOCTYPE html><html><head>$cspAndThemeHeader</head><body>$htmlSource</body></html>"
                    }
                }

                LaunchedEffect(searchQuery, isSearchVisible) {
                    val wv = activeWebViewRef ?: return@LaunchedEffect
                    if (isSearchVisible && searchQuery.trim().isNotEmpty()) {
                        wv.findAllAsync(searchQuery.trim())
                    } else {
                        wv.clearMatches()
                    }
                }

                if (blockedExternalUrlPrompt != null) {
                    val blockedUrl = blockedExternalUrlPrompt!!
                    AlertDialog(
                        onDismissRequest = { blockedExternalUrlPrompt = null },
                        confirmButton = {
                            Button(
                                onClick = {
                                    clipManager?.setPrimaryClip(ClipData.newPlainText("URL", blockedUrl))
                                    blockedExternalUrlPrompt = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                            ) {
                                Text(
                                    text = strings.copyActionButton,
                                    color = if (palette.isDark) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { blockedExternalUrlPrompt = null }) {
                                Text(strings.closeBtn, color = palette.secondaryText)
                            }
                        },
                        title = {
                            Text(
                                text = strings.securityBadge,
                                color = palette.warningBadge,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        },
                        text = {
                            Text(
                                text = blockedUrl,
                                color = palette.primaryText,
                                fontSize = 14.sp
                            )
                        },
                        containerColor = palette.surface,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(
                                android.graphics.Color.rgb(
                                    (palette.canvasBackground.red * 255).roundToInt(),
                                    (palette.canvasBackground.green * 255).roundToInt(),
                                    (palette.canvasBackground.blue * 255).roundToInt()
                                )
                            )
                            settings.apply {
                                javaScriptEnabled = false
                                blockNetworkLoads = true
                                blockNetworkImage = true
                                allowFileAccess = false
                                allowContentAccess = false
                                @Suppress("DEPRECATION")
                                allowFileAccessFromFileURLs = false
                                @Suppress("DEPRECATION")
                                allowUniversalAccessFromFileURLs = false
                                domStorageEnabled = false
                                databaseEnabled = false
                                setGeolocationEnabled(false)
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                defaultTextEncodingName = "UTF-8"
                                textZoom = htmlBrowserZoomPercent
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    val scheme = request?.url?.scheme?.lowercase(Locale.ROOT) ?: ""
                                    if (scheme in setOf("about", "data")) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    return android.webkit.WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Blocked by FullFileStudio Offline Sandbox",
                                        emptyMap(),
                                        java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val targetUrl = request?.url?.toString() ?: return true
                                    if (targetUrl.startsWith("about:blank#")) {
                                        return false
                                    }
                                    blockedExternalUrlPrompt = targetUrl
                                    return true
                                }
                            }
                            activeWebViewRef = this
                        }
                    },
                    update = { webView ->
                        activeWebViewRef = webView
                        webView.setBackgroundColor(
                            android.graphics.Color.rgb(
                                (palette.canvasBackground.red * 255).roundToInt(),
                                (palette.canvasBackground.green * 255).roundToInt(),
                                (palette.canvasBackground.blue * 255).roundToInt()
                            )
                        )
                        if (webView.settings.textZoom != htmlBrowserZoomPercent) {
                            webView.settings.textZoom = htmlBrowserZoomPercent
                        }
                        val contentHash = sandboxedHtml.hashCode()
                        if (webView.tag != contentHash) {
                            webView.tag = contentHash
                            webView.loadDataWithBaseURL(
                                "about:blank",
                                sandboxedHtml,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (useReflowLayout && pageText.isNotBlank()) {
                val latestPageText by rememberUpdatedState(pageText)
                val latestPageTableRows by rememberUpdatedState(pageTableRows)
                val latestTotalPages by rememberUpdatedState(totalPages)
                val animProgress = pageTransitionProgress.value
                val dir = pageTransitionDirection

                Box(modifier = Modifier.fillMaxSize()) {
                    if (previousReflowText != null && animProgress < 0.999f) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = -dir * size.height * animProgress
                                    alpha = (1f - animProgress * 0.35f).coerceIn(0f, 1f)
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
                                translationY = if (animProgress < 0.999f) {
                                    dir * size.height * (1f - animProgress)
                                } else 0f
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        val bmp = activeBitmap
                        if (activeReflowIllustrationIndex >= 0 && bmp != null && !bmp.isRecycled) {
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
                                    bitmap = bmp.asImageBitmap(),
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
                                                selectedReflowIllustrationPos =
                                                    (selectedReflowIllustrationPos - 1 + activeReflowIllustrationIndices.size) % activeReflowIllustrationIndices.size
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
                                                selectedReflowIllustrationPos =
                                                    (selectedReflowIllustrationPos + 1) % activeReflowIllustrationIndices.size
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
                                    val layout = textLayoutResult
                                    val curText = latestPageText
                                    val tableRows = latestPageTableRows
                                    val highlightAlpha = searchHighlightAlpha.value

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
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
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
                                        highlightAlpha > 0.005f &&
                                        activeSearchHighlightStart >= 0 &&
                                        activeSearchHighlightEnd > activeSearchHighlightStart &&
                                        layout != null &&
                                        curText.isNotEmpty()
                                    ) {
                                        val hStart = activeSearchHighlightStart.coerceIn(0, curText.length - 1)
                                        val hEnd = activeSearchHighlightEnd.coerceIn(hStart + 1, curText.length)
                                        val highlightPath = layout.getPathForRange(hStart, hEnd)
                                        val bgTargetColor = androidx.compose.ui.graphics.lerp(palette.canvasBackground, Color(0xFFFFEB3B), highlightAlpha)
                                        drawPath(
                                            path = highlightPath,
                                            color = bgTargetColor
                                        )
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
                                        val layout = textLayoutResult
                                        val curText = latestPageText
                                        val hadPreviousSelection = textSelStart >= 0 && textSelEnd >= 0

                                        var isDraggingStart = false
                                        var isDraggingEnd = false
                                        var isLongPress = false

                                        if (layout != null && hadPreviousSelection && curText.isNotEmpty()) {
                                            val minO = minOf(textSelStart, textSelEnd).coerceIn(0, curText.length - 1)
                                            val maxO = maxOf(textSelStart, textSelEnd).coerceIn(minO, curText.length - 1)
                                            val startBox = layout.getBoundingBox(minO)
                                            val endBox = layout.getBoundingBox(maxO)

                                            val leftHandlePos = Offset(startBox.left, startBox.bottom)
                                            val rightHandlePos = Offset(endBox.right, endBox.bottom)

                                            val distLeft = (downPos - leftHandlePos).getDistance()
                                            val distRight = (downPos - rightHandlePos).getDistance()
                                            if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                                                textSelStart = minO
                                                textSelEnd = maxO
                                                if (distLeft <= distRight) {
                                                    isDraggingStart = true
                                                } else {
                                                    isDraggingEnd = true
                                                }
                                                textToolbar.hide()
                                                val screenPoint = downPos + Offset(paddingHorizPx, paddingVertPx)
                                                lastDragPoint = screenPoint
                                                activeDragPoint = screenPoint
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
                                            val curLayout = textLayoutResult
                                            val txtNow = latestPageText
                                            if (!session.isCopyRestricted && !panStarted && !pinchStarted && !isDraggingStart && !isDraggingEnd && curLayout != null && txtNow.isNotEmpty()) {
                                                val offsetIdx = curLayout.getOffsetForPosition(downPos).coerceIn(0, txtNow.length - 1)
                                                val wordBoundary = curLayout.getWordBoundary(offsetIdx)
                                                val wStart = wordBoundary.start.coerceIn(0, txtNow.length - 1)
                                                val wEnd = (wordBoundary.end - 1).coerceIn(wStart, txtNow.length - 1)
                                                textToolbar.hide()
                                                textSelStart = wStart
                                                textSelEnd = wEnd
                                                val screenPoint = downPos + Offset(paddingHorizPx, paddingVertPx)
                                                lastDragPoint = screenPoint
                                                activeDragPoint = screenPoint
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
                                                activeDragPoint = null
                                            }

                                            if (isDraggingStart || isDraggingEnd) {
                                                curChange.consume()
                                                val screenPoint = curPos + Offset(paddingHorizPx, paddingVertPx)
                                                lastDragPoint = screenPoint
                                                activeDragPoint = screenPoint
                                                val curLayout = textLayoutResult
                                                val txtNow = latestPageText
                                                if (curLayout != null && txtNow.isNotEmpty()) {
                                                    val newIdx = curLayout.getOffsetForPosition(curPos).coerceIn(0, txtNow.length - 1)
                                                    if (isDraggingStart) {
                                                        if (newIdx > textSelEnd) {
                                                            textSelStart = textSelEnd
                                                            textSelEnd = newIdx
                                                            isDraggingStart = false
                                                            isDraggingEnd = true
                                                        } else if (newIdx != textSelStart) {
                                                            textSelStart = newIdx
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                    } else if (isDraggingEnd) {
                                                        if (newIdx < textSelStart) {
                                                            textSelEnd = textSelStart
                                                            textSelStart = newIdx
                                                            isDraggingEnd = false
                                                            isDraggingStart = true
                                                        } else if (newIdx != textSelEnd) {
                                                            textSelEnd = newIdx
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                    }
                                                }
                                            } else if (isLongPress) {
                                                curChange.consume()
                                                val screenPoint = curPos + Offset(paddingHorizPx, paddingVertPx)
                                                lastDragPoint = screenPoint
                                                activeDragPoint = screenPoint
                                                if ((curPos - downPos).getDistance() > touchSlop) {
                                                    val curLayout = textLayoutResult
                                                    val txtNow = latestPageText
                                                    if (curLayout != null && txtNow.isNotEmpty()) {
                                                        val curIdx = curLayout.getOffsetForPosition(curPos).coerceIn(0, txtNow.length - 1)
                                                        if (curIdx != textSelEnd) {
                                                            textSelEnd = curIdx
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
                                                    if (textSelStart >= 0 || textSelEnd >= 0) {
                                                        textSelStart = -1
                                                        textSelEnd = -1
                                                        textToolbar.hide()
                                                    }
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }

                                                if (zoomChange != 1f) {
                                                    fontScaleMultiplier = (fontScaleMultiplier * zoomChange).coerceIn(0.5f, 3.5f)
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })

                                        timerJob.cancel()
                                        activeDragPoint = null

                                        val curLayout = textLayoutResult
                                        val txtNow = latestPageText
                                        if (!session.isCopyRestricted && (isDraggingStart || isDraggingEnd || isLongPress)) {
                                            if (textSelStart >= 0 && textSelEnd >= 0 && curLayout != null && txtNow.isNotEmpty()) {
                                                val minO = minOf(textSelStart, textSelEnd).coerceIn(0, txtNow.length - 1)
                                                val maxO = maxOf(textSelStart, textSelEnd).coerceIn(minO, curText.length - 1)
                                                textSelStart = minO
                                                textSelEnd = maxO
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
                                                    onCopyRequested = {
                                                        copyActiveSelectionToClipboard(latestPageText)
                                                    },
                                                    onSelectAllRequested = {
                                                        val t = latestPageText
                                                        if (t.isNotEmpty()) {
                                                            textSelStart = 0
                                                            textSelEnd = t.length - 1
                                                        }
                                                    },
                                                    onCutRequested = null,
                                                    onPasteRequested = null
                                                )
                                            }
                                        } else if (!panStarted && !pinchStarted) {
                                            focusManager.clearFocus()
                                            val nowMs = System.currentTimeMillis()
                                            if (!hadPreviousSelection && nowMs - lastTapTimeMs in 40L..320L &&
                                                (downPos - lastTapPosition).getDistance() < 48.dp.toPx()
                                            ) {
                                                fontScaleMultiplier = if (fontScaleMultiplier > 1.25f) 1.0f else 1.5f
                                                lastTapTimeMs = 0L
                                            } else {
                                                lastTapTimeMs = nowMs
                                                lastTapPosition = downPos
                                                if (hadPreviousSelection || textSelStart >= 0 || textSelEnd >= 0) {
                                                    textSelStart = -1
                                                    textSelEnd = -1
                                                    textToolbar.hide()
                                                }
                                                if (isSearchDropdownExpanded) {
                                                    isSearchDropdownExpanded = false
                                                }
                                            }
                                        } else if (panStarted && !pinchStarted) {
                                            val swipeThreshold = 85f
                                            if (abs(accPanY) >= abs(accPanX) && abs(accPanY) > swipeThreshold) {
                                                if (accPanY < -swipeThreshold && currentPage < latestTotalPages - 1) {
                                                    navigateToPageSmoothly(currentPage + 1)
                                                } else if (accPanY > swipeThreshold && currentPage > 0) {
                                                    navigateToPageSmoothly(currentPage - 1)
                                                }
                                            } else if (abs(accPanX) > swipeThreshold * 1.15f) {
                                                if (accPanX < -swipeThreshold * 1.15f && currentPage < latestTotalPages - 1) {
                                                    navigateToPageSmoothly(currentPage + 1)
                                                } else if (accPanX > swipeThreshold * 1.15f && currentPage > 0) {
                                                    navigateToPageSmoothly(currentPage - 1)
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
                                searchHighlightAlpha.value,
                                normalReflowTextColor
                            ) {
                                val baseSub = if (!currentRange.isEmpty() &&
                                    currentRange.first >= 0 &&
                                    currentRange.last < structuredDoc.annotatedText.length
                                ) {
                                    structuredDoc.annotatedText.subSequence(currentRange.first, currentRange.last + 1)
                                } else {
                                    AnnotatedString(pageText)
                                }
                                val alpha = searchHighlightAlpha.value
                                if (activeSearchHighlightPage == currentPage &&
                                    alpha > 0.005f &&
                                    activeSearchHighlightStart >= 0 &&
                                    activeSearchHighlightEnd > activeSearchHighlightStart &&
                                    baseSub.isNotEmpty()
                                ) {
                                    val hStart = activeSearchHighlightStart.coerceIn(0, baseSub.length - 1)
                                    val hEnd = activeSearchHighlightEnd.coerceIn(hStart + 1, baseSub.length)
                                    val fgColor = androidx.compose.ui.graphics.lerp(normalReflowTextColor, Color(0xFF121212), alpha)
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
                                onTextLayout = { textLayoutResult = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                val latestTotalPages by rememberUpdatedState(totalPages)
                val latestOcrChars by rememberUpdatedState(ocrChars)
                val latestActiveBitmap by rememberUpdatedState(activeBitmap)
                val latestDocWidth by rememberUpdatedState(cachedDocWidth)
                val latestDocHeight by rememberUpdatedState(cachedDocHeight)
                val latestColWidthsPx by rememberUpdatedState(cachedColWidthsPx)
                val latestRowHeightsPx by rememberUpdatedState(cachedRowHeightsPx)
                val latestBufferWidth by rememberUpdatedState(activeBufferWidth)
                val latestBufferHeight by rememberUpdatedState(activeBufferHeight)
                val latestRawNativePageText by rememberUpdatedState(rawCurrentPageNativeTextWithoutNotes)
                val latestRenderedPage by rememberUpdatedState(renderedPage)
                val latestRenderedZoomPermille by rememberUpdatedState(renderedZoomPermille)
                val latestRenderedTileX by rememberUpdatedState(renderedTileX)
                val latestRenderedTileY by rememberUpdatedState(renderedTileY)

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(session, is3D, isHorizontalLayout, isSpreadsheet, isHexSession, isPresentation) {
                            val decay = exponentialDecay<Offset>(frictionMultiplier = 1.8f)
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                isGestureActive = true
                                coroutineScope.launch { flingAnimatable.stop() }
                                val velocityTracker = VelocityTracker()
                                var accPanX = 0f
                                var accPanY = 0f
                                var panStarted = false
                                var pinchStarted = false
                                val touchSlop = viewConfig.touchSlop
                                val longPressTimeout = viewConfig.longPressTimeoutMillis

                                val downScreenPos = down.position
                                val handleHitRadiusPx = 38.dp.toPx()

                                val bmp = latestActiveBitmap
                                var charsNow = latestOcrChars
                                val screenW = size.width.toFloat().coerceAtLeast(1f)
                                val screenH = size.height.toFloat().coerceAtLeast(1f)
                                val bufW = (bmp?.width ?: latestBufferWidth).toFloat().coerceAtLeast(1f)
                                val bufH = (bmp?.height ?: latestBufferHeight).toFloat().coerceAtLeast(1f)
                                val screenToBufX = bufW / screenW
                                val screenToBufY = bufH / screenH
                                val viewportCenterBuf = Offset(bufW / 2f, bufH / 2f)

                                val hadPreviousCharSelection = !isSpreadsheet && selStartChar >= 0 && selEndChar >= 0
                                val hadPreviousSheetSelection = isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0
                                var isDraggingLeftHandle = false
                                var isDraggingRightHandle = false
                                var isLongPressActive = false

                                val gestureDocW = latestDocWidth
                                val gestureDocH = latestDocHeight
                                val gestureColWidths = latestColWidthsPx
                                val gestureRowHeights = latestRowHeightsPx

                                fun computeBitmapPlacement(curBmp: Bitmap): FloatArray {
                                    val isLiveMatch = (latestRenderedPage == currentPage && !is3D && !isTableBoundedLayout)
                                    val rScale = (latestRenderedZoomPermille / 1000f).coerceAtLeast(0.25f)
                                    val liveRatio = if (isLiveMatch && abs(scale - rScale) > 0.005f) {
                                        (scale / rScale).coerceIn(0.125f, 8.0f)
                                    } else 1f
                                    val baseScaleX = screenW / curBmp.width.toFloat().coerceAtLeast(1f)
                                    val baseScaleY = screenH / curBmp.height.toFloat().coerceAtLeast(1f)
                                    val panDeltaX = if (isLiveMatch) (offset.x + latestRenderedTileX * liveRatio) * baseScaleX else 0f
                                    val panDeltaY = if (isLiveMatch) (offset.y + latestRenderedTileY * liveRatio) * baseScaleY else 0f
                                    val dstW = (screenW * liveRatio).coerceAtLeast(1f)
                                    val dstH = (screenH * liveRatio).coerceAtLeast(1f)
                                    val dstX = (screenW - dstW) / 2f + panDeltaX
                                    val dstY = (screenH - dstH) / 2f + panDeltaY
                                    return floatArrayOf(dstX, dstY, dstW, dstH, baseScaleX * liveRatio, baseScaleY * liveRatio)
                                }

                                fun resolveCharScreenRect(ch: RecognizedCharUi, curBufW: Float, curBufH: Float): android.graphics.RectF {
                                    if (isGraphicSession) {
                                        val curBmp = latestActiveBitmap
                                        if (curBmp != null && !curBmp.isRecycled) {
                                            val placement = computeBitmapPlacement(curBmp)
                                            val left = placement[0] + ch.rect.left * placement[4]
                                            val top = placement[1] + ch.rect.top * placement[5]
                                            val right = placement[0] + ch.rect.right * placement[4]
                                            val bottom = placement[1] + ch.rect.bottom * placement[5]
                                            return android.graphics.RectF(left, top, right, bottom)
                                        }
                                    }
                                    val livePageBoundsBuf = computeDocumentPageBoundsOnBuffer(
                                        bufWidth = curBufW,
                                        bufHeight = curBufH,
                                        docWidth = latestDocWidth,
                                        docHeight = latestDocHeight,
                                        zoomPermille = (scale * 1000f).roundToInt(),
                                        tileX = (-offset.x).roundToInt(),
                                        tileY = (-offset.y).roundToInt(),
                                        isHexLayout = isHexSession,
                                        hexLineCount = ocrLines.size.coerceAtLeast(1)
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

                                    val zoom = scale.coerceIn(0.5f, 4.0f)
                                    val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                                        docWidth = gestureDocW,
                                        customColWidthsPx = gestureColWidths,
                                        minRequiredCols = maxOf(sheetSelStartCol, sheetSelEndCol) + 1
                                    )
                                    val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                                        colCount = colCount,
                                        customColWidthsPx = gestureColWidths,
                                        viewportWidth = bmp.width.toFloat(),
                                        zoom = zoom
                                    )
                                    val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
                                    val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
                                    val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
                                    val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)

                                    val rectBmp = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                                        startRow = r0, startCol = c0, endRow = r1, endCol = c1,
                                        colOffsets = colOffsets, zoom = zoom,
                                        effScrollX = (-offset.x).coerceAtLeast(0f),
                                        effScrollY = (-offset.y).coerceAtLeast(0f),
                                        customRowHeightsPx = gestureRowHeights
                                    )
                                    val leftHandlePos = Offset(originX + rectBmp.left * scaleX, originY + rectBmp.bottom * scaleY)
                                    val rightHandlePos = Offset(originX + rectBmp.right * scaleX, originY + rectBmp.bottom * scaleY)

                                    val distLeft = (downScreenPos - leftHandlePos).getDistance()
                                    val distRight = (downScreenPos - rightHandlePos).getDistance()
                                    if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                                        sheetSelStartRow = r0; sheetSelStartCol = c0
                                        sheetSelEndRow = r1; sheetSelEndCol = c1
                                        if (distLeft <= distRight) {
                                            isDraggingLeftHandle = true
                                        } else {
                                            isDraggingRightHandle = true
                                        }
                                        textToolbar.hide()
                                        lastDragPoint = downScreenPos
                                        activeDragPoint = downScreenPos
                                    }
                                } else if (bmp != null && !bmp.isRecycled && hadPreviousCharSelection && charsNow.isNotEmpty()) {
                                    val minC = minOf(selStartChar, selEndChar).coerceIn(0, charsNow.size - 1)
                                    val maxC = maxOf(selStartChar, selEndChar).coerceIn(0, charsNow.size - 1)

                                    val startScreenRect = resolveCharScreenRect(charsNow[minC], bmp.width.toFloat(), bmp.height.toFloat())
                                    val endScreenRect = resolveCharScreenRect(charsNow[maxC], bmp.width.toFloat(), bmp.height.toFloat())

                                    val leftHandlePos = Offset(startScreenRect.left, startScreenRect.bottom)
                                    val rightHandlePos = Offset(endScreenRect.right, endScreenRect.bottom)

                                    val distLeft = (downScreenPos - leftHandlePos).getDistance()
                                    val distRight = (downScreenPos - rightHandlePos).getDistance()
                                    if (distLeft <= handleHitRadiusPx || distRight <= handleHitRadiusPx) {
                                        selStartChar = minC
                                        selEndChar = maxC
                                        if (distLeft <= distRight) {
                                            isDraggingLeftHandle = true
                                        } else {
                                            isDraggingRightHandle = true
                                        }
                                        textToolbar.hide()
                                        lastDragPoint = downScreenPos
                                        activeDragPoint = downScreenPos
                                    }
                                }

                                val longPressJob = coroutineScope.launch {
                                    delay(longPressTimeout)
                                    val curBmp = latestActiveBitmap
                                    if (!panStarted && !pinchStarted && !isDraggingLeftHandle && !isDraggingRightHandle &&
                                        !is3D && curBmp != null && !curBmp.isRecycled
                                    ) {
                                        val placement = computeBitmapPlacement(curBmp)
                                        val bmpX = (downScreenPos.x - placement[0]) / placement[4]
                                        val bmpY = (downScreenPos.y - placement[1]) / placement[5]

                                        if (isSpreadsheet) {
                                            val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                                                minX = bmpX, maxX = bmpX,
                                                minY = bmpY, maxY = bmpY,
                                                scale = scale, fontScaleMultiplier = 1f,
                                                offsetX = offset.x, offsetY = offset.y,
                                                viewportWidth = bufW, docWidth = gestureDocW,
                                                viewportHeight = bufH, docHeight = gestureDocH,
                                                customColWidthsPx = gestureColWidths,
                                                customRowHeightsPx = gestureRowHeights
                                            )
                                            textToolbar.hide()
                                            sheetSelStartRow = cellRange.startRow
                                            sheetSelStartCol = cellRange.startCol
                                            sheetSelEndRow = cellRange.endRow
                                            sheetSelEndCol = cellRange.endCol
                                            lastDragPoint = downScreenPos
                                            activeDragPoint = downScreenPos
                                            isLongPressActive = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else if (!session.isCopyRestricted) {
                                            var curChars = latestOcrChars
                                            if (curChars.isEmpty() && latestRawNativePageText.isNotBlank()) {
                                                val (fbLines, fbChars) = buildFallbackCharsFromPageText(
                                                    rawPageText = latestRawNativePageText,
                                                    bmpWidth = curBmp.width,
                                                    bmpHeight = curBmp.height,
                                                    isHexLayout = isHexSession,
                                                    docWidth = gestureDocW,
                                                    docHeight = gestureDocH,
                                                    zoomPermille = latestRenderedZoomPermille,
                                                    tileX = latestRenderedTileX,
                                                    tileY = latestRenderedTileY
                                                )
                                                ocrLines = fbLines
                                                ocrChars = fbChars
                                                curChars = fbChars
                                            }
                                            if (curChars.isEmpty() && !curBmp.isRecycled) {
                                                val snapCopy = try { curBmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
                                                if (snapCopy != null) {
                                                    val snapZ = latestRenderedZoomPermille
                                                    val snapTX = latestRenderedTileX
                                                    val snapTY = latestRenderedTileY
                                                    val payload = withContext(OCR_BACKGROUND_DISPATCHER) {
                                                        try {
                                                            PaddleOcrManager.recognize(
                                                                bitmap = snapCopy,
                                                                pageTextHint = latestRawNativePageText,
                                                                zoomPermille = snapZ,
                                                                tileX = snapTX,
                                                                tileY = snapTY,
                                                                docWidth = gestureDocW,
                                                                docHeight = gestureDocH,
                                                                preferredLanguageCode = activeLang.code
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
                                                            val rf = android.graphics.RectF(it.rect)
                                                            RecognizedLineUi(it.text, it.rect, normalizeRectToPage(rf, snapPageBounds))
                                                        }
                                                        val cList = payload.chars.map {
                                                            val rf = android.graphics.RectF(it.rect)
                                                            RecognizedCharUi(it.text, it.lineIndex, rf, normalizeRectToPage(rf, snapPageBounds))
                                                        }
                                                        ocrLines = lList
                                                        ocrChars = cList
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
                                                    textToolbar.hide()
                                                    selStartChar = wordRange.first
                                                    selEndChar = wordRange.last
                                                    lastDragPoint = downScreenPos
                                                    activeDragPoint = downScreenPos
                                                    isLongPressActive = true
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                                    val selectedChars = curChars.subList(wordRange.first, wordRange.last + 1)
                                                    val screenRects = selectedChars.map {
                                                        resolveCharScreenRect(it, curBmp.width.toFloat(), curBmp.height.toFloat())
                                                    }
                                                    val leftOnScreen = screenRects.minOf { it.left }
                                                    val rightOnScreen = screenRects.maxOf { it.right }
                                                    val topOnScreen = screenRects.minOf { it.top }
                                                    val botOnScreen = screenRects.maxOf { it.bottom }

                                                    val selectionRectOnScreen = Rect(
                                                        leftOnScreen.coerceIn(0f, screenW),
                                                        (topOnScreen - 40f).coerceIn(10f, screenH),
                                                        rightOnScreen.coerceIn(0f, screenW),
                                                        botOnScreen.coerceIn(10f, screenH)
                                                    )

                                                    textToolbar.showMenu(
                                                        rect = selectionRectOnScreen,
                                                        onCopyRequested = { copyActiveSelectionToClipboard("") },
                                                        onSelectAllRequested = {
                                                            val cChars = latestOcrChars
                                                            if (cChars.isNotEmpty()) {
                                                                selStartChar = 0
                                                                selEndChar = cChars.lastIndex
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
                                        activeDragPoint = null
                                    }

                                    val curBmp = latestActiveBitmap
                                    charsNow = latestOcrChars

                                    if (isDraggingLeftHandle || isDraggingRightHandle) {
                                        curChange.consume()
                                        lastDragPoint = curScreenPos
                                        activeDragPoint = curScreenPos
                                        if (curBmp != null && !curBmp.isRecycled) {
                                            val placement = computeBitmapPlacement(curBmp)
                                            val bmpX = (curScreenPos.x - placement[0]) / placement[4]
                                            val bmpY = (curScreenPos.y - placement[1]) / placement[5]

                                            if (isSpreadsheet) {
                                                val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                                                    minX = bmpX, maxX = bmpX,
                                                    minY = bmpY, maxY = bmpY,
                                                    scale = scale, fontScaleMultiplier = 1f,
                                                    offsetX = offset.x, offsetY = offset.y,
                                                    viewportWidth = bufW, docWidth = gestureDocW,
                                                    viewportHeight = bufH, docHeight = gestureDocH,
                                                    customColWidthsPx = gestureColWidths,
                                                    customRowHeightsPx = gestureRowHeights
                                                )
                                                if (isDraggingLeftHandle) {
                                                    if (cellRange.startRow != sheetSelStartRow || cellRange.startCol != sheetSelStartCol) {
                                                        sheetSelStartRow = cellRange.startRow
                                                        sheetSelStartCol = cellRange.startCol
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                } else {
                                                    if (cellRange.endRow != sheetSelEndRow || cellRange.endCol != sheetSelEndCol) {
                                                        sheetSelEndRow = cellRange.endRow
                                                        sheetSelEndCol = cellRange.endCol
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            } else if (charsNow.isNotEmpty()) {
                                                val activeIdx = if (isDraggingLeftHandle) selStartChar else selEndChar
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
                                                        if (newCharIdx > selEndChar) {
                                                            selStartChar = selEndChar
                                                            selEndChar = newCharIdx
                                                            isDraggingLeftHandle = false
                                                            isDraggingRightHandle = true
                                                        } else if (newCharIdx != selStartChar) {
                                                            selStartChar = newCharIdx
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                    } else if (isDraggingRightHandle) {
                                                        if (newCharIdx < selStartChar) {
                                                            selEndChar = selStartChar
                                                            selStartChar = newCharIdx
                                                            isDraggingRightHandle = false
                                                            isDraggingLeftHandle = true
                                                        } else if (newCharIdx != selEndChar) {
                                                            selEndChar = newCharIdx
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (isLongPressActive) {
                                        curChange.consume()
                                        lastDragPoint = curScreenPos
                                        activeDragPoint = curScreenPos
                                        if ((curScreenPos - downScreenPos).getDistance() > touchSlop &&
                                            curBmp != null && !curBmp.isRecycled
                                        ) {
                                            val placement = computeBitmapPlacement(curBmp)
                                            val bmpX = (curScreenPos.x - placement[0]) / placement[4]
                                            val bmpY = (curScreenPos.y - placement[1]) / placement[5]

                                            if (isSpreadsheet) {
                                                val cellRange = SpreadsheetCoordinateUtils.calculateCellRange(
                                                    minX = bmpX, maxX = bmpX,
                                                    minY = bmpY, maxY = bmpY,
                                                    scale = scale, fontScaleMultiplier = 1f,
                                                    offsetX = offset.x, offsetY = offset.y,
                                                    viewportWidth = bufW, docWidth = gestureDocW,
                                                    viewportHeight = bufH, docHeight = gestureDocH,
                                                    customColWidthsPx = gestureColWidths,
                                                    customRowHeightsPx = gestureRowHeights
                                                )
                                                if (cellRange.endRow != sheetSelEndRow || cellRange.endCol != sheetSelEndCol) {
                                                    sheetSelEndRow = cellRange.endRow
                                                    sheetSelEndCol = cellRange.endCol
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            } else if (charsNow.isNotEmpty()) {
                                                val prefLine = charsNow.getOrNull(selEndChar)?.lineIndex ?: -1
                                                val curIdx = findCharIndexAt(
                                                    targetX = curScreenPos.x,
                                                    targetY = curScreenPos.y,
                                                    chars = charsNow,
                                                    rectResolver = { ch -> resolveCharScreenRect(ch, curBmp.width.toFloat(), curBmp.height.toFloat()) },
                                                    preferredLineIndex = prefLine
                                                )
                                                if (curIdx >= 0 && curIdx != selEndChar) {
                                                    selEndChar = curIdx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                            textToolbar.hide()
                                        }

                                        if (panStarted) {
                                            if (is3D) {
                                                yaw = (yaw + panChange.x * 0.45f) % 360f
                                                pitch = (pitch - panChange.y * 0.45f).coerceIn(-89f, 89f)
                                                scale = (scale * zoomChange).coerceIn(0.2f, 8f)
                                            } else {
                                                val oldScale = scale
                                                val minAllowedScale = if (isGraphicSession) 0.78f else 1f
                                                val maxAllowedScale = if (isSpreadsheet) 4f else 8f
                                                scale = (scale * zoomChange).coerceIn(minAllowedScale, maxAllowedScale)
                                                val centroidScreen = event.calculateCentroid(useCurrent = false)
                                                val centroidBuf = if (centroidScreen != Offset.Unspecified) {
                                                    Offset(centroidScreen.x * screenToBufX, centroidScreen.y * screenToBufY)
                                                } else {
                                                    Offset.Unspecified
                                                }
                                                val panChangeBuf = Offset(panChange.x * screenToBufX, panChange.y * screenToBufY)
                                                val anchoredRawOffset = calculateCentroidAnchoredOffset(
                                                    currentOffset = offset,
                                                    panChange = panChangeBuf,
                                                    oldScale = oldScale,
                                                    newScale = scale,
                                                    centroid = centroidBuf,
                                                    viewportCenter = viewportCenterBuf,
                                                    isSpreadsheet = isTableBoundedLayout
                                                )
                                                offset = clampCanvasOffset(
                                                    rawOffset = anchoredRawOffset,
                                                    scale = scale,
                                                    isSpreadsheet = isTableBoundedLayout,
                                                    isHorizontalLayout = isHorizontalLayout,
                                                    docWidth = gestureDocW,
                                                    docHeight = gestureDocH,
                                                    viewportWidth = bufW,
                                                    viewportHeight = bufH
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

                                isGestureActive = false
                                longPressJob.cancel()
                                activeDragPoint = null
                                if (!is3D && scale < 1.0f) {
                                    scale = 1.0f
                                    offset = Offset.Zero
                                }

                                val curBmp = latestActiveBitmap
                                val curChars = latestOcrChars

                                if (isDraggingLeftHandle || isDraggingRightHandle || isLongPressActive) {
                                    if (isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0 && curBmp != null && !curBmp.isRecycled) {
                                        val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
                                        val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
                                        val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
                                        val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)
                                        sheetSelStartRow = r0; sheetSelStartCol = c0
                                        sheetSelEndRow = r1; sheetSelEndCol = c1

                                        val placement = computeBitmapPlacement(curBmp)
                                        val zoom = scale.coerceIn(0.5f, 4.0f)
                                        val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                                            docWidth = gestureDocW,
                                            customColWidthsPx = gestureColWidths,
                                            minRequiredCols = c1 + 1
                                        )
                                        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                                            colCount = colCount,
                                            customColWidthsPx = gestureColWidths,
                                            viewportWidth = curBmp.width.toFloat(),
                                            zoom = zoom
                                        )
                                        val rectBmp = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                                            startRow = r0, startCol = c0, endRow = r1, endCol = c1,
                                            colOffsets = colOffsets, zoom = zoom,
                                            effScrollX = (-offset.x).coerceAtLeast(0f),
                                            effScrollY = (-offset.y).coerceAtLeast(0f),
                                            customRowHeightsPx = gestureRowHeights
                                        )
                                        // Suppress native dark Android ActionMode popup on spreadsheets;
                                        // our docked non-overlapping copy bar handles copying cleanly.
                                        textToolbar.hide()
                                    } else if (!session.isCopyRestricted && selStartChar >= 0 && selEndChar >= 0 && curChars.isNotEmpty() && curBmp != null && !curBmp.isRecycled) {
                                        val minC = minOf(selStartChar, selEndChar).coerceIn(0, curChars.size - 1)
                                        val maxC = maxOf(selStartChar, selEndChar).coerceIn(0, curChars.size - 1)
                                        selStartChar = minC
                                        selEndChar = maxC
                                        val selectedChars = curChars.subList(minC, maxC + 1)
                                        val screenRects = selectedChars.map {
                                            resolveCharScreenRect(it, curBmp.width.toFloat(), curBmp.height.toFloat())
                                        }

                                        val leftOnScreen = screenRects.minOf { it.left }
                                        val rightOnScreen = screenRects.maxOf { it.right }
                                        val topOnScreen = screenRects.minOf { it.top }
                                        val botOnScreen = screenRects.maxOf { it.bottom }

                                        val selectionRectOnScreen = Rect(
                                            leftOnScreen.coerceIn(0f, screenW),
                                            (topOnScreen - 40f).coerceIn(10f, screenH),
                                            rightOnScreen.coerceIn(0f, screenW),
                                            botOnScreen.coerceIn(10f, screenH)
                                        )

                                        textToolbar.showMenu(
                                            rect = selectionRectOnScreen,
                                            onCopyRequested = { copyActiveSelectionToClipboard("") },
                                            onSelectAllRequested = {
                                                val cChars = latestOcrChars
                                                if (cChars.isNotEmpty()) {
                                                    selStartChar = 0
                                                    selEndChar = cChars.lastIndex
                                                }
                                            },
                                            onCutRequested = null,
                                            onPasteRequested = null
                                        )
                                    }
                                } else if (!panStarted && !pinchStarted) {
                                    focusManager.clearFocus()
                                    val nowMs = System.currentTimeMillis()
                                    if (!hadPreviousCharSelection && !hadPreviousSheetSelection &&
                                        nowMs - lastTapTimeMs in 40L..320L &&
                                        (downScreenPos - lastTapPosition).getDistance() < 48.dp.toPx()
                                    ) {
                                        val oldScale = scale
                                        val targetScale = if (oldScale > 1.15f) 1.0f else if (isTableBoundedLayout) 2.0f else 2.5f
                                        scale = targetScale
                                        if (targetScale <= 1.02f && !isTableBoundedLayout) {
                                            offset = Offset.Zero
                                        } else {
                                            val tapBuf = Offset(downScreenPos.x * screenToBufX, downScreenPos.y * screenToBufY)
                                            val rawAnchored = calculateCentroidAnchoredOffset(
                                                currentOffset = offset,
                                                panChange = Offset.Zero,
                                                oldScale = oldScale,
                                                newScale = targetScale,
                                                centroid = tapBuf,
                                                viewportCenter = viewportCenterBuf,
                                                isSpreadsheet = isTableBoundedLayout
                                            )
                                            offset = clampCanvasOffset(
                                                rawOffset = rawAnchored,
                                                scale = targetScale,
                                                isSpreadsheet = isTableBoundedLayout,
                                                isHorizontalLayout = isHorizontalLayout,
                                                docWidth = gestureDocW,
                                                docHeight = gestureDocH,
                                                viewportWidth = bufW,
                                                viewportHeight = bufH
                                            )
                                        }
                                        lastTapTimeMs = 0L
                                    } else {
                                        lastTapTimeMs = nowMs
                                        lastTapPosition = downScreenPos
                                        if (hadPreviousCharSelection || hadPreviousSheetSelection || selStartChar >= 0 || sheetSelStartRow >= 0) {
                                            clearAllSelections()
                                        }
                                        if (isSearchDropdownExpanded) {
                                            isSearchDropdownExpanded = false
                                        }
                                    }
                                } else if (panStarted && !is3D) {
                                    val swipeThreshold = 85f
                                    if (!pinchStarted && latestTotalPages > 1 && scale <= 1.02f && !isTableBoundedLayout) {
                                        when {
                                            isPresentation -> {
                                                if (accPanX < -swipeThreshold && currentPage < latestTotalPages - 1) {
                                                    navigateToPageSmoothly(currentPage + 1)
                                                } else if (accPanX > swipeThreshold && currentPage > 0) {
                                                    navigateToPageSmoothly(currentPage - 1)
                                                }
                                            }
                                            else -> {
                                                if ((accPanY < -swipeThreshold || accPanX < -swipeThreshold * 1.15f) && currentPage < latestTotalPages - 1) {
                                                    navigateToPageSmoothly(currentPage + 1)
                                                } else if ((accPanY > swipeThreshold || accPanX > swipeThreshold * 1.15f) && currentPage > 0) {
                                                    navigateToPageSmoothly(currentPage - 1)
                                                }
                                            }
                                        }
                                    }

                                    if (scale > 1.02f || isTableBoundedLayout) {
                                        val v: Velocity = velocityTracker.calculateVelocity()
                                        val initialVel = Offset(v.x * screenToBufX, v.y * screenToBufY)
                                        if (initialVel.getDistance() > 250f) {
                                            coroutineScope.launch {
                                                flingAnimatable.snapTo(offset)
                                                flingAnimatable.animateDecay(initialVel, decay) {
                                                    offset = clampCanvasOffset(
                                                        rawOffset = value,
                                                        scale = scale,
                                                        isSpreadsheet = isTableBoundedLayout,
                                                        isHorizontalLayout = isHorizontalLayout,
                                                        docWidth = gestureDocW,
                                                        docHeight = gestureDocH,
                                                        viewportWidth = bufW,
                                                        viewportHeight = bufH
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                            }
                        }
                ) {
                    activeBitmap?.let { bmp ->
                        if (!bmp.isRecycled) {
                            try {
                                renderVersion.hashCode()
                                val animProgress = pageTransitionProgress.value
                                val dir = pageTransitionDirection
                                val prevBmp = previousPageBitmap

                                val screenWInt = size.width.toInt()
                                val screenHInt = size.height.toInt()

                                val transKind = if (isPresentation) activeSlideTransitionType else "vertical_slide"

                                // 1. Draw outgoing previous page/slide during transition
                                if (prevBmp != null && !prevBmp.isRecycled && animProgress < 0.999f && !is3D && !isTableBoundedLayout) {
                                    val prevOffX = when {
                                        !isPresentation -> 0
                                        transKind in setOf("fade", "dissolve", "wipe", "split", "zoom", "cover") -> 0
                                        else -> (-dir * size.width * animProgress).roundToInt()
                                    }
                                    val prevOffY = if (!isPresentation) (-dir * size.height * animProgress).roundToInt() else 0
                                    val prevAlpha = when (transKind) {
                                        "fade", "dissolve" -> (1f - animProgress).coerceIn(0f, 1f)
                                        else -> (1f - animProgress * 0.25f).coerceIn(0f, 1f)
                                    }
                                    drawImage(
                                        image = prevBmp.asImageBitmap(),
                                        dstOffset = IntOffset(prevOffX, prevOffY),
                                        dstSize = IntSize(screenWInt, screenHInt),
                                        alpha = prevAlpha
                                    )
                                }

                                // 2. Compute real-time 120 FPS delta compensation when pinch-zooming or panning between IPC renders
                                val isLivePageMatch = (renderedPage == currentPage && !is3D && !isTableBoundedLayout)
                                val minRenderedScale = if (isGraphicSession) 0.25f else 0.5f
                                val renderedScale = (renderedZoomPermille / 1000f).coerceAtLeast(minRenderedScale)
                                val liveScaleRatio = if (isLivePageMatch && abs(scale - renderedScale) > 0.005f) {
                                    (scale / renderedScale).coerceIn(0.125f, 8.0f)
                                } else 1f
                                val scaleX = size.width / bmp.width.toFloat().coerceAtLeast(1f)
                                val scaleY = size.height / bmp.height.toFloat().coerceAtLeast(1f)
                                val livePanDeltaX = if (isLivePageMatch) (offset.x + renderedTileX * liveScaleRatio) * scaleX else 0f
                                val livePanDeltaY = if (isLivePageMatch) (offset.y + renderedTileY * liveScaleRatio) * scaleY else 0f

                                val enterScaleFactor = if (animProgress < 0.999f && isPresentation && transKind == "zoom") {
                                    0.78f + 0.22f * animProgress
                                } else 1f

                                val enterTransX = if (animProgress < 0.999f && isPresentation && transKind in setOf("push", "cover", "pull")) {
                                    dir * size.width * (1f - animProgress)
                                } else 0f
                                val enterTransY = if (animProgress < 0.999f && !isPresentation && !is3D && !isTableBoundedLayout) {
                                    dir * size.height * (1f - animProgress)
                                } else 0f

                                val dstW = (size.width * liveScaleRatio * enterScaleFactor).roundToInt().coerceAtLeast(1)
                                val dstH = (size.height * liveScaleRatio * enterScaleFactor).roundToInt().coerceAtLeast(1)
                                val dstX = ((size.width - dstW) / 2f + livePanDeltaX + enterTransX).roundToInt()
                                val dstY = ((size.height - dstH) / 2f + livePanDeltaY + enterTransY).roundToInt()

                                val dstOffset = IntOffset(dstX, dstY)
                                val dstSize = IntSize(dstW, dstH)

                                val enterAlpha = if (animProgress < 0.999f && isPresentation) {
                                    when (transKind) {
                                        "fade", "dissolve", "zoom" -> animProgress.coerceIn(0f, 1f)
                                        else -> (0.45f + 0.55f * animProgress).coerceIn(0f, 1f)
                                    }
                                } else 1f

                                drawImage(
                                    image = bmp.asImageBitmap(),
                                    dstOffset = dstOffset,
                                    dstSize = dstSize,
                                    alpha = enterAlpha
                                )

                                val highlightAlpha = searchHighlightAlpha.value
                                val offsetX = dstOffset.x.toFloat()
                                val offsetY = dstOffset.y.toFloat()
                                val effScaleX = dstSize.width.toFloat() / bmp.width.toFloat().coerceAtLeast(1f)
                                val effScaleY = dstSize.height.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)

                                val screenScaleX = size.width / bmp.width.toFloat().coerceAtLeast(1f)
                                val screenScaleY = size.height / bmp.height.toFloat().coerceAtLeast(1f)
                                val livePageBoundsBuf = if (!isSpreadsheet && !is3D) {
                                    computeDocumentPageBoundsOnBuffer(
                                        bufWidth = bmp.width.toFloat(),
                                        bufHeight = bmp.height.toFloat(),
                                        docWidth = cachedDocWidth,
                                        docHeight = cachedDocHeight,
                                        zoomPermille = (scale * 1000f).roundToInt(),
                                        tileX = (-offset.x).roundToInt(),
                                        tileY = (-offset.y).roundToInt(),
                                        isHexLayout = isHexSession,
                                        hexLineCount = ocrLines.size.coerceAtLeast(1)
                                    )
                                } else null

                                if (isSpreadsheet &&
                                    renderVersion >= activeSearchHighlightMinRenderVersion &&
                                    highlightAlpha > 0.005f &&
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
                                    val effX = (-offset.x).coerceAtLeast(0f)
                                    val effY = (-offset.y).coerceAtLeast(0f)
                                    val rawCellRect = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                                        startRow = activeSearchHighlightRow,
                                        startCol = activeSearchHighlightCol,
                                        colOffsets = colOffsets,
                                        zoom = zoom,
                                        effScrollX = effX,
                                        effScrollY = effY,
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
                                            color = Color(0xFFFFEB3B).copy(alpha = 0.45f * highlightAlpha),
                                            topLeft = Offset(offsetX + cellLeftBmp * effScaleX, offsetY + cellTopBmp * effScaleY),
                                            size = Size(
                                                (cellRightBmp - cellLeftBmp) * effScaleX,
                                                (cellBotBmp - cellTopBmp) * effScaleY
                                            )
                                        )
                                    }
                                } else if (!isSpreadsheet &&
                                    renderVersion >= activeSearchHighlightMinRenderVersion &&
                                    activeSearchHighlightPage == currentPage &&
                                    highlightAlpha > 0.005f &&
                                    activeSearchHighlightText.isNotEmpty() &&
                                    ocrChars.isNotEmpty()
                                ) {
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
                                        val found = fullOcrStr.indexOf(activeSearchHighlightText, searchPos, ignoreCase = true)
                                        if (found < 0) break
                                        val endExc = (found + activeSearchHighlightText.length).coerceAtMost(fullOcrStr.length)
                                        val startCharIdx = charIndexByCodeUnit[found]
                                        val endCharIdx = charIndexByCodeUnit[(endExc - 1).coerceAtLeast(found)]
                                        matchedCharRange = startCharIdx..endCharIdx
                                        if (curOcc == activeSearchHighlightOccurrence) break
                                        curOcc++
                                        searchPos = endExc
                                    }

                                    if (matchedCharRange == null) {
                                        val cleanQueryNoSpaces = activeSearchHighlightText.filter { !it.isWhitespace() }
                                        val spacelessStr = spacelessSb.toString()
                                        if (cleanQueryNoSpaces.isNotEmpty() && spacelessStr.isNotEmpty()) {
                                            var spPos = 0
                                            var spOcc = 0
                                            while (spPos < spacelessStr.length) {
                                                val found = spacelessStr.indexOf(cleanQueryNoSpaces, spPos, ignoreCase = true)
                                                if (found < 0) break
                                                val endExc = (found + cleanQueryNoSpaces.length).coerceAtMost(spacelessStr.length)
                                                matchedCharRange = spacelessIndexByCodeUnit[found]..spacelessIndexByCodeUnit[(endExc - 1).coerceAtLeast(found)]
                                                if (spOcc == activeSearchHighlightOccurrence) break
                                                spOcc++
                                                spPos = endExc
                                            }
                                        }
                                    }

                                    if (matchedCharRange != null && !matchedCharRange.isEmpty()) {
                                        val matchedChars = ocrChars.subList(
                                            matchedCharRange.first.coerceIn(0, ocrChars.lastIndex),
                                            (matchedCharRange.last + 1).coerceIn(1, ocrChars.size)
                                        )
                                        val pageBounds = livePageBoundsBuf
                                        if (pageBounds != null) {
                                            matchedChars.groupBy { it.lineIndex }.values.forEach { charsInLine ->
                                                val nonBlankInLine = charsInLine.filter { it.text.isNotBlank() }.ifEmpty { charsInLine }
                                                val lineScreenRects = nonBlankInLine.map {
                                                    projectNormRectToScreen(it.normRect, pageBounds, screenScaleX, screenScaleY)
                                                }
                                                val sLeft = lineScreenRects.minOf { it.left }
                                                val sRight = lineScreenRects.maxOf { it.right }
                                                val sTop = lineScreenRects.minOf { it.top }
                                                val sBot = lineScreenRects.maxOf { it.bottom }
                                                drawRoundRect(
                                                    color = Color(0xFFFFEB3B).copy(alpha = 0.45f * highlightAlpha),
                                                    topLeft = Offset(sLeft, sTop),
                                                    size = Size(
                                                        (sRight - sLeft).coerceAtLeast(4f),
                                                        (sBot - sTop).coerceAtLeast(4f)
                                                    ),
                                                    cornerRadius = CornerRadius(4.dp.toPx())
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0) {
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
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f.dp.toPx())
                                        )
                                        drawCircle(handleColor, handleRadius, Offset(sLeft, sBot))
                                        drawCircle(handleColor, handleRadius, Offset(sRight, sBot))
                                    }
                                }

                                // Inside Canvas draw block (before active selection highlight):
                                if (isGraphicSession && selStartChar < 0 && ocrLines.isNotEmpty() && livePageBoundsBuf != null) {
                                    for (lineUi in ocrLines) {
                                        val sr = projectNormRectToScreen(lineUi.normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                                        drawRoundRect(
                                            color = palette.accent.copy(alpha = 0.22f),
                                            topLeft = Offset(sr.left, sr.top),
                                            size = Size((sr.right - sr.left).coerceAtLeast(4f), (sr.bottom - sr.top).coerceAtLeast(4f)),
                                            cornerRadius = CornerRadius(4.dp.toPx()),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                                        )
                                    }
                                }

                                if (!isSpreadsheet && selStartChar >= 0 && selEndChar >= 0 && ocrChars.isNotEmpty() && livePageBoundsBuf != null) {
                                    val minC = minOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
                                    val maxC = maxOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
                                    val selectedChars = ocrChars.subList(minC, maxC + 1)

                                    val lineGroups = selectedChars.groupBy { it.lineIndex }

                                    lineGroups.values.forEach { charsInLine ->
                                        val lineScreenRects = charsInLine.map {
                                            projectNormRectToScreen(it.normRect, livePageBoundsBuf, screenScaleX, screenScaleY)
                                        }
                                        val sLeft = lineScreenRects.minOf { it.left }
                                        val sRight = lineScreenRects.maxOf { it.right }
                                        val sTop = lineScreenRects.minOf { it.top }
                                        val sBot = lineScreenRects.maxOf { it.bottom }
                                        val sWidth = sRight - sLeft
                                        val sHeight = sBot - sTop

                                        drawRoundRect(
                                            color = Color(0x6600E676),
                                            topLeft = Offset(sLeft, sTop),
                                            size = Size(sWidth.coerceAtLeast(4f), sHeight.coerceAtLeast(4f)),
                                            cornerRadius = CornerRadius(4.dp.toPx())
                                        )
                                    }

                                    val startScreenRect = projectNormRectToScreen(
                                        ocrChars[minC].normRect,
                                        livePageBoundsBuf,
                                        screenScaleX,
                                        screenScaleY
                                    )
                                    val endScreenRect = projectNormRectToScreen(
                                        ocrChars[maxC].normRect,
                                        livePageBoundsBuf,
                                        screenScaleX,
                                        screenScaleY
                                    )
                                    val handleRadius = 9.dp.toPx()
                                    val handleColor = Color(0xFF00E676)

                                    val startX = startScreenRect.left
                                    val startY = startScreenRect.bottom

                                    drawCircle(
                                        color = handleColor,
                                        radius = handleRadius,
                                        center = Offset(startX - handleRadius, startY + handleRadius)
                                    )
                                    val leftPath = Path().apply {
                                        moveTo(startX, startY)
                                        lineTo(startX - handleRadius * 2f, startY + handleRadius)
                                        lineTo(startX - handleRadius, startY + handleRadius * 2f)
                                        close()
                                    }
                                    drawPath(path = leftPath, color = handleColor)

                                    val endX = endScreenRect.right
                                    val endY = endScreenRect.bottom

                                    drawCircle(
                                        color = handleColor,
                                        radius = handleRadius,
                                        center = Offset(endX + handleRadius, endY + handleRadius)
                                    )
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

            androidx.compose.animation.AnimatedVisibility(
                visible = !session.isCopyRestricted && hasAnyActiveSelection && !isSpreadsheet,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = palette.surface.copy(alpha = 0.97f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.accent),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { copyActiveSelectionToClipboard(pageText) },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = strings.copyActionButton,
                                color = if (palette.isDark) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        if (!isSpreadsheet) {
                            OutlinedButton(
                                onClick = {
                                    if (useReflowLayout && pageText.isNotEmpty()) {
                                        textSelStart = 0
                                        textSelEnd = pageText.length - 1
                                    } else if (ocrChars.isNotEmpty()) {
                                        selStartChar = 0
                                        selEndChar = ocrChars.lastIndex
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = strings.selectAllActionButton,
                                    color = palette.primaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        TextButton(
                            onClick = { clearAllSelections() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("✕", color = palette.secondaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isPresentation && isSearchVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically(
                        initialOffsetY = { -it },
                        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                    ) + fadeIn(),
                    exit = slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing)
                    ) + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp, start = 16.dp, end = 96.dp)
                            .fillMaxWidth()
                            .systemGestureExclusion()
                            .background(palette.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            DisableFullscreenImeWrapper {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { newVal ->
                                        searchQuery = newVal
                                        if (newVal.trim() != lastExecutedQuery) {
                                            hasExecutedSearch = false
                                        }
                                    },
                                    placeholder = { Text(strings.searchPlaceholder, color = palette.secondaryText, fontSize = 13.sp) },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp, color = palette.primaryText),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { triggerDocumentSearch() }),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = palette.primaryText,
                                        unfocusedTextColor = palette.primaryText
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .focusRequester(searchFocusRequester)
                                )
                            }
                        }
                        if (hasExecutedSearch && searchResults.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = { isSearchDropdownExpanded = !isSearchDropdownExpanded },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                val arrow = if (isSearchDropdownExpanded) "▲" else "▼"
                                Text(
                                    text = "$arrow ${searchResults.size}",
                                    color = palette.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = { triggerDocumentSearch() },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(strings.findButton, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (isSearchVisible && isSearchDropdownExpanded && (isSearchingInProgress || hasExecutedSearch)) {
                val topPad = if (isPresentation) 64.dp else 6.dp
                val endPad = if (isPresentation) 96.dp else 16.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPad, start = 16.dp, end = endPad)
                        .imePadding()
                        .fillMaxWidth()
                        .heightIn(max = if (isPresentation) 190.dp else 250.dp)
                        .systemGestureExclusion()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface.copy(alpha = 0.98f))
                        .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                ) {
                    if (isSearchingInProgress) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${strings.searchButton}...",
                                color = palette.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.notFoundMessage,
                                color = palette.secondaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        LazyColumn(
                            state = searchDropdownListState,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(
                                items = searchResults,
                                key = { idx, item -> "$idx:${item.sheetIndex}:${item.pageIndex}:${item.cellRow}:${item.cellCol}:${item.charStartInPage}" }
                            ) { idx, item ->
                                val isCurrentSelected = (idx == selectedMatchIndex)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isCurrentSelected) palette.accent.copy(alpha = 0.14f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            focusManager.clearFocus()
                                            selectedMatchIndex = idx
                                            isSearchDropdownExpanded = false

                                            if (isSpreadsheet && item.cellRow >= 0 && item.cellCol >= 0) {
                                                val targetSheet = item.sheetIndex.coerceAtLeast(0)
                                                val targetRow = item.cellRow
                                                val targetCol = item.cellCol

                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val sheetChanged = (targetSheet != activeSheetIndex)
                                                    if (sheetChanged) {
                                                        try { session.setActiveSheetIndex(targetSheet) } catch (_: Exception) {}
                                                    }
                                                    val newDocW = try { session.getDocumentWidth() } catch (_: Exception) { cachedDocWidth }
                                                    val newDocH = try { session.getDocumentHeight() } catch (_: Exception) { cachedDocHeight }
                                                    val newColWidths = try {
                                                        session.getColumnWidths().takeIf { it.isNotEmpty() }
                                                    } catch (_: Exception) {
                                                        cachedColWidthsPx
                                                    }
                                                    val newRowHeights = try {
                                                        session.getRowHeights().takeIf { it.isNotEmpty() }
                                                    } catch (_: Exception) {
                                                        cachedRowHeightsPx
                                                    }

                                                    withContext(Dispatchers.Main) {
                                                        val zoom = scale.coerceIn(0.5f, 4.0f)
                                                        val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(
                                                            docWidth = newDocW,
                                                            customColWidthsPx = newColWidths,
                                                            minRequiredCols = targetCol + 1
                                                        )
                                                        val bufW = activeBufferWidth.toFloat()
                                                        val bufH = activeBufferHeight.toFloat()
                                                        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(
                                                            colCount = colCount,
                                                            customColWidthsPx = newColWidths,
                                                            viewportWidth = bufW,
                                                            zoom = zoom
                                                        )
                                                        val safeTargetCol = targetCol.coerceIn(0, colCount - 1)
                                                        val targetColLeft = colOffsets[safeTargetCol]
                                                        val targetColW = (colOffsets[safeTargetCol + 1] - targetColLeft).coerceAtLeast(40f)
                                                        val targetRect = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(
                                                            startRow = targetRow, startCol = safeTargetCol,
                                                            colOffsets = colOffsets, zoom = zoom,
                                                            effScrollX = 0f, effScrollY = 0f,
                                                            customRowHeightsPx = newRowHeights
                                                        )
                                                        val cellH = targetRect.height().coerceAtLeast(1f)
                                                        val targetTop = targetRect.top - SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom

                                                        val desiredEffX = (targetColLeft - (bufW - SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom - targetColW) / 2f).coerceAtLeast(0f)
                                                        val desiredEffY = (targetTop - (bufH - SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom - cellH) / 2f).coerceAtLeast(0f)

                                                        val newOffset = clampCanvasOffset(
                                                            rawOffset = Offset(-desiredEffX, -desiredEffY),
                                                            scale = zoom,
                                                            isSpreadsheet = true,
                                                            isHorizontalLayout = isHorizontalLayout,
                                                            docWidth = newDocW,
                                                            docHeight = newDocH,
                                                            viewportWidth = bufW,
                                                            viewportHeight = bufH
                                                        )
                                                        val offsetChanged = (newOffset.x.toInt() != offset.x.toInt() || newOffset.y.toInt() != offset.y.toInt())

                                                        cachedDocWidth = newDocW
                                                        cachedDocHeight = newDocH
                                                        cachedColWidthsPx = newColWidths
                                                        cachedRowHeightsPx = newRowHeights
                                                        offset = newOffset
                                                        if (sheetChanged) {
                                                            activeSheetIndex = targetSheet
                                                            stateChangeTrigger++
                                                        }

                                                        activeSearchHighlightMinRenderVersion = if (sheetChanged || offsetChanged) {
                                                            renderVersion + 1
                                                        } else {
                                                            renderVersion
                                                        }
                                                        activeSearchHighlightSheet = targetSheet
                                                        activeSearchHighlightRow = targetRow
                                                        activeSearchHighlightCol = targetCol
                                                        searchHighlightTrigger++
                                                    }
                                                }
                                            } else {
                                                val targetPage = item.pageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                                val pageChanged = (targetPage != currentPage || offset != Offset.Zero)
                                                activeSearchHighlightMinRenderVersion = if (!useReflowLayout && pageChanged) {
                                                    renderVersion + 1
                                                } else {
                                                    renderVersion
                                                }
                                                navigateToPageSmoothly(targetPage)
                                                activeSearchHighlightPage = targetPage
                                                activeSearchHighlightStart = item.charStartInPage
                                                activeSearchHighlightEnd = item.charEndExclusiveInPage
                                                activeSearchHighlightOccurrence = item.occurrenceIndexOnPage
                                                activeSearchHighlightText = item.matchedText
                                                searchHighlightTrigger++
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(palette.surfaceElevated, RoundedCornerShape(6.dp))
                                            .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = item.customBadge ?: "${strings.pageLabel} ${item.pageIndex + 1}",
                                            color = palette.accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    val highlightedLine = remember(item, palette.primaryText) {
                                        buildAnnotatedString {
                                            if (item.contextPrefix.isNotEmpty()) {
                                                withStyle(SpanStyle(color = palette.primaryText)) {
                                                    append(item.contextPrefix)
                                                }
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    background = Color(0xFFFFEB3B),
                                                    color = Color(0xFF121212),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            ) {
                                                append(item.matchedText)
                                            }
                                            if (item.contextSuffix.isNotEmpty()) {
                                                withStyle(SpanStyle(color = palette.primaryText)) {
                                                    append(item.contextSuffix)
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = highlightedLine,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = palette.border)
                            }
                        }
                    }
                }
            }

            sessionError?.let { errText ->
                val localizedErr = localizeEngineMessage(errText, currentLang)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .background(palette.surface, RoundedCornerShape(20.dp))
                        .border(1.5.dp, palette.errorBadge, RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .background(palette.errorBadge.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = strings.formatErrorBadge,
                                color = palette.errorBadge,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = strings.formatReadErrorTitle,
                            color = palette.primaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = localizedErr,
                            color = palette.secondaryText,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        val allowInSessionHex = !session.isCopyRestricted &&
                                !errText.startsWith("DRM_PROTECTED") &&
                                !errText.startsWith("UNSUPPORTED_ENCRYPTION") &&
                                !errText.startsWith("PASSWORD_REQUIRED")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (allowInSessionHex) {
                                Button(
                                    onClick = onOpenHexFallbackRequest,
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.errorBadge)
                                ) {
                                    Text(strings.openInHexBtn, color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                            OutlinedButton(onClick = { sessionError = null }) {
                                Text(strings.closeBtn, color = palette.primaryText)
                            }
                        }
                    }
                }
            }

            if (isPresentation) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
                        .systemGestureExclusion()
                        .background(palette.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(
                            onClick = onOpenSettingsRequest,
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("⚙", color = palette.primaryText, fontSize = 13.sp)
                        }

                        Button(
                            onClick = onOpenFileRequest,
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📁", fontSize = 12.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Button(
                            onClick = {
                                isSearchVisible = !isSearchVisible
                                if (!isSearchVisible) {
                                    focusManager.clearFocus()
                                    isSearchDropdownExpanded = false
                                    clearActiveSearchHighlight()
                                } else if (hasExecutedSearch && searchResults.isNotEmpty()) {
                                    isSearchDropdownExpanded = true
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(if (isSearchVisible) "✕" else "🔍", fontSize = 12.sp)
                        }

                        if (currentSlideSpeakerNotes.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { showSpeakerNotesDialog = true },
                                shape = CircleShape,
                                modifier = Modifier.requiredSize(32.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("📝", fontSize = 12.sp)
                            }
                        }

                        if (activeSlideTotalAnimSteps > 0) {
                            OutlinedButton(
                                onClick = {
                                    currentSlideAnimStep = (currentSlideAnimStep + 1) % (activeSlideTotalAnimSteps + 1)
                                },
                                shape = CircleShape,
                                modifier = Modifier.requiredSize(32.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                val stepBadge = if (currentSlideAnimStep == 0) "✨" else "$currentSlideAnimStep"
                                Text(stepBadge, color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = currentPage > 0 || currentSlideAnimStep > 1,
                            onClick = {
                                if (currentSlideAnimStep > 1) {
                                    currentSlideAnimStep--
                                } else if (currentPage > 0) {
                                    navigateToPageSmoothly(currentPage - 1)
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("◀", fontSize = 11.sp)
                        }

                        Button(
                            enabled = currentPage < totalPages - 1 || (currentSlideAnimStep in 1 until activeSlideTotalAnimSteps),
                            onClick = {
                                if (currentSlideAnimStep in 1 until activeSlideTotalAnimSteps) {
                                    currentSlideAnimStep++
                                } else if (currentPage < totalPages - 1) {
                                    navigateToPageSmoothly(currentPage + 1)
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("▶", fontSize = 11.sp)
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${currentPage + 1}",
                            color = palette.primaryText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "/ $totalPages",
                            color = palette.secondaryText,
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.sp,
                            lineHeight = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { applyZoomStep(-0.25f) },
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("-", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { applyZoomStep(0.25f) },
                            shape = CircleShape,
                            modifier = Modifier.requiredSize(30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("+", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    TextButton(
                        onClick = { scale = 1f; offset = Offset.Zero },
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "${(scale * 100).toInt()}%",
                            color = palette.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Non-overlapping docked copy bar for spreadsheets (slides in cleanly without covering cells)
        androidx.compose.animation.AnimatedVisibility(
            visible = !session.isCopyRestricted && isSpreadsheet && hasAnyActiveSelection,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
            val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
            val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
            val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)
            val startRef = "${SpreadsheetCoordinateUtils.columnIndexToExcelLetters(c0)}${r0 + 1}"
            val endRef = "${SpreadsheetCoordinateUtils.columnIndexToExcelLetters(c1)}${r1 + 1}"
            val rangeBadge = if (startRef == endRef) startRef else "$startRef:$endRef"

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
            ) {
                HorizontalDivider(color = palette.border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = palette.surfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.accent),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(palette.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = rangeBadge,
                                    color = palette.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = { copyActiveSelectionToClipboard("") },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = strings.copyActionButton,
                                    color = if (palette.isDark) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            TextButton(
                                onClick = { clearAllSelections() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("✕", color = palette.secondaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        if (isSpreadsheet && sheetNames.size > 1) {
            HorizontalDivider(color = palette.border)
            ScrollableTabRow(
                selectedTabIndex = activeSheetIndex.coerceIn(0, (sheetNames.size - 1).coerceAtLeast(0)),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth(),
                containerColor = palette.surface,
                contentColor = palette.accent
            ) {
                sheetNames.forEachIndexed { index, name ->
                    Tab(
                        selected = activeSheetIndex == index,
                        onClick = {
                            if (activeSheetIndex != index) {
                                clearActiveSearchHighlight()
                                clearAllSelections()
                                activeSheetIndex = index
                                offset = Offset.Zero
                                sessionError = null
                                coroutineScope.launch(Dispatchers.IO) {
                                    try { session.setActiveSheetIndex(index) } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) {
                                        stateChangeTrigger++
                                    }
                                }
                            }
                        },
                        text = {
                            Text(
                                text = name,
                                fontSize = 13.sp,
                                fontWeight = if (activeSheetIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeSheetIndex == index) palette.accent else palette.secondaryText
                            )
                        }
                    )
                }
            }
        }

        if (!isPresentation) {
            HorizontalDivider(color = palette.border)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!is3D && !isHtmlBrowserMode && totalPages > 1) {
                    var sliderValue by remember(session) { mutableFloatStateOf(currentPage.toFloat()) }
                    val maxPageIndexFloat = (totalPages - 1).coerceAtLeast(1).toFloat()

                    LaunchedEffect(currentPage, totalPages, isDraggingSlider) {
                        if (!isDraggingSlider) {
                            sliderValue = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)).toFloat()
                        }
                    }

                    Slider(
                        value = sliderValue.coerceIn(0f, maxPageIndexFloat),
                        onValueChange = { newVal ->
                            isDraggingSlider = true
                            sliderValue = newVal
                            val targetPage = newVal.roundToInt().coerceIn(0, totalPages - 1)
                            if (targetPage != currentPage) {
                                currentPage = targetPage
                                offset = Offset.Zero
                            }
                        },
                        onValueChangeFinished = {
                            val targetPage = sliderValue.roundToInt().coerceIn(0, totalPages - 1)
                            isDraggingSlider = false
                            currentPage = targetPage
                            sliderValue = targetPage.toFloat()
                            offset = Offset.Zero
                        },
                        valueRange = 0f..maxPageIndexFloat,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.accent,
                            activeTrackColor = palette.accent,
                            inactiveTrackColor = palette.border
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .height(28.dp)
                    )
                }

                if (!is3D && !isHtmlBrowserMode && totalPages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            enabled = currentPage > 0,
                            onClick = {
                                if (currentPage > 0) {
                                    navigateToPageSmoothly(currentPage - 1)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("▲", fontSize = 13.sp)
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(
                            modifier = Modifier.widthIn(min = 64.dp, max = 148.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "${strings.pageLabel} ${currentPage + 1}",
                                color = palette.primaryText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "/ $totalPages",
                                color = palette.secondaryText,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Button(
                            enabled = currentPage < totalPages - 1,
                            onClick = {
                                if (currentPage < totalPages - 1) {
                                    navigateToPageSmoothly(currentPage + 1)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("▼", fontSize = 13.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isHtmlSession) {
                        OutlinedButton(
                            onClick = {
                                clearAllSelections()
                                isSearchDropdownExpanded = false
                                isHtmlBrowserMode = !isHtmlBrowserMode
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = if (isHtmlBrowserMode) strings.viewHtmlSourceButton else strings.openAsInBrowserButton,
                                color = palette.accent,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }

                    Button(
                        onClick = {
                            if (isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) {
                                htmlBrowserZoomPercent = (htmlBrowserZoomPercent + 15).coerceAtMost(300)
                            } else if (useReflowLayout) {
                                fontScaleMultiplier = (fontScaleMultiplier + 0.15f).coerceAtMost(3.5f)
                            } else {
                                applyZoomStep(0.25f)
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.requiredSize(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(2.dp))

                    TextButton(
                        onClick = {
                            if (isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) {
                                htmlBrowserZoomPercent = 100
                            } else {
                                scale = 1f; fontScaleMultiplier = 1f; offset = Offset.Zero
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = if (isHtmlSession && isHtmlBrowserMode) {
                                "${htmlBrowserZoomPercent}%"
                            } else if (useReflowLayout) {
                                "${(fontScaleMultiplier * 100).roundToInt()}%"
                            } else {
                                "${(scale * 100).roundToInt()}%"
                            },
                            color = palette.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Spacer(Modifier.width(2.dp))

                    Button(
                        onClick = {
                            if (isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) {
                                htmlBrowserZoomPercent = (htmlBrowserZoomPercent - 15).coerceAtLeast(50)
                            } else if (useReflowLayout) {
                                fontScaleMultiplier = (fontScaleMultiplier - 0.15f).coerceAtLeast(0.5f)
                            } else {
                                applyZoomStep(-0.25f)
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.requiredSize(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimationPlaybackBar(
    duration: Float,
    time: Float,
    playing: Boolean,
    clips: List<String>,
    clip: Int,
    onClip: (Int) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    fun clock(t: Float): String {
        val tenths = (t.coerceAtLeast(0f) * 10f).roundToInt()
        val minutes = tenths / 600
        val rest = tenths % 600
        return String.format(Locale.ROOT, "%d:%02d.%d", minutes, rest / 10, rest % 10)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onToggle) {
            Text(if (playing) strings.pauseMedia else strings.playMedia, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = time.coerceIn(0f, duration),
            onValueChange = onSeek,
            valueRange = 0f..duration,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text("${clock(time)} / ${clock(duration)}", color = palette.secondaryText, fontSize = 13.sp)
        if (clips.size > 1) {
            Spacer(Modifier.width(6.dp))
            PlayerMenuButton(clips.getOrNull(clip)?.ifBlank { "#${clip + 1}" } ?: "#${clip + 1}", palette) { close ->
                for ((i, name) in clips.withIndex()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text((if (i == clip) "✓ " else "") + name.ifBlank { "#${i + 1}" }) },
                        onClick = { onClip(i); close() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingResourcesBanner(session: DocumentSession, trigger: Int, onApplied: () -> Unit) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var missing by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, trigger) {
        missing = withContext(Dispatchers.IO) { try { session.getMissingResources() } catch (_: Throwable) { emptyList() } }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                var any = false
                for (u in uris) {
                    val name = try {
                        context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    } catch (_: Throwable) { null } ?: u.lastPathSegment?.substringAfterLast('/') ?: continue
                    try {
                        context.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                            if (session.provideResource(name, pfd.fileDescriptor)) any = true
                        }
                    } catch (_: Throwable) {}
                }
                if (any) try { session.applyProvidedResources() } catch (_: Throwable) {}
                missing = try { session.getMissingResources() } catch (_: Throwable) { emptyList() }
                any
            }.let { if (it) onApplied() }
            busy = false
        }
    }
    if (missing.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(palette.background, RoundedCornerShape(10.dp))
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(strings.missingResourcesTitle, color = palette.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                missing.take(6).joinToString(", ") { it.substringAfterLast('/') } + if (missing.size > 6) " …" else "",
                color = palette.secondaryText,
                fontSize = 12.sp,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(strings.attachResourcesButton, color = palette.primaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SubtitleOverlay(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 17.sp) {
    Text(
        text = text,
        color = Color.White,
        fontSize = fontSize,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun PlayerMenuButton(label: String, palette: me.hlebkukleuski.fullfilestudio.ui.theme.AppPalette, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, color = palette.primaryText, fontSize = 13.sp, maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@Composable
fun MediaRenderScreen(
    session: MediaPlaybackSession,
    onOpenFileRequest: () -> Unit,
    onOpenSettingsRequest: () -> Unit = {},
    modifier: Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val controller = me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController
    val currentLang = remember(strings) {
        AppLanguage.entries.firstOrNull { strings == me.hlebkukleuski.fullfilestudio.ui.theme.stringsForLanguage(it) }
            ?: AppLanguage.EN
    }
    fun titleOf(x: MediaPlaybackSession): String = if (x.trackTitle == "Аудио / Видео файл" || x.trackTitle == "Audio / Video Stream") {
        controller.queue.value.getOrNull(controller.index.value)?.title?.takeIf { x === controller.session.value } ?: strings.defaultMediaTitle
    } else x.trackTitle
    fun artistOf(x: MediaPlaybackSession): String = if (x.trackArtist == "Неизвестный исполнитель" || x.trackArtist == "Unknown Artist") strings.defaultMediaArtist else x.trackArtist

    LaunchedEffect(session) {
        controller.init(context)
        if (!controller.isKnown(session) && !session.isClosed) controller.attach(session, titleOf(session))
    }
    val active by controller.session.collectAsState()
    val s = active ?: session
    val isPlaying by controller.isPlaying.collectAsState()
    val speed by controller.speed.collectAsState()
    val queue by controller.queue.collectAsState()
    val queueIndex by controller.index.collectAsState()
    val subtitle by controller.subtitleText.collectAsState()
    val audioTracks by controller.audioTracks.collectAsState()
    val textTracks by controller.textTracks.collectAsState()
    val selectedAudio by controller.selectedAudio.collectAsState()
    val selectedText by controller.selectedText.collectAsState()

    val queuePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) controller.addToQueue(uris) }
    val subtitlePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !controller.loadSubtitles(uri)) {
            android.widget.Toast.makeText(context, strings.mediaSubtitlesFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var showQueue by remember { mutableStateOf(false) }

    val activeAudioDevice = rememberActiveAudioDeviceCapabilities()
    val settingsManager = LocalAppSettingsManager.current
    val currentAudioQualityModeState = settingsManager?.audioQualityMode?.collectAsState()
    val currentAudioQualityMode = currentAudioQualityModeState?.value ?: AppAudioQualityMode.AUTO
    val effectiveAudioQualityText = remember(currentAudioQualityMode, activeAudioDevice, strings) {
        when (currentAudioQualityMode) {
            AppAudioQualityMode.AUTO -> strings.localizedAudioQualityAuto(activeAudioDevice)
            AppAudioQualityMode.STUDIO_96 -> strings.audioQuality96
            AppAudioQualityMode.HIGH_48 -> strings.audioQuality48
            AppAudioQualityMode.STANDARD_44 -> strings.audioQuality44
        }
    }

    LaunchedEffect(strings, activeAudioDevice, effectiveAudioQualityText) {
        val devName = activeAudioDevice.getLocalizedDeviceName(strings)
        val info = "${activeAudioDevice.deviceTypeIcon} $devName • $effectiveAudioQualityText"
        controller.metadataProvider = { x -> Triple(titleOf(x), artistOf(x), info) }
        controller.notifyState()
    }

    androidx.compose.runtime.key(s) {
        var currentPosition by remember { mutableFloatStateOf(0f) }
        var duration by remember { mutableFloatStateOf(1f) }
        val isPrepared by s.isPrepared.collectAsState()
        val playbackError by s.playbackError.collectAsState()
        val resolvedTitle = titleOf(s)
        val resolvedArtist = artistOf(s)

        var isDragging by remember { mutableStateOf(false) }
        var sliderPosition by remember { mutableFloatStateOf(0f) }

        var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
        var videoScale by remember { mutableFloatStateOf(1f) }
        var videoOffset by remember { mutableStateOf(Offset.Zero) }
        var lastVideoTapMs by remember { mutableLongStateOf(0L) }
        var lastVideoTapPos by remember { mutableStateOf(Offset.Zero) }

        LaunchedEffect(isPrepared) {
            if (isPrepared) {
                try {
                    duration = s.mediaPlayer.duration.toFloat().coerceAtLeast(1f)
                    val vw = s.mediaPlayer.videoWidth
                    val vh = s.mediaPlayer.videoHeight
                    if (vw > 0 && vh > 0) videoAspectRatio = (vw.toFloat() / vh.toFloat()).coerceIn(0.2f, 5.0f)
                    s.mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) videoAspectRatio = (w.toFloat() / h.toFloat()).coerceIn(0.2f, 5.0f)
                    }
                } catch (_: Exception) {}
            }
        }

        LaunchedEffect(isPrepared, isPlaying, isDragging) {
            while (isPrepared && !isDragging) {
                try {
                    currentPosition = s.mediaPlayer.currentPosition.toFloat()
                    sliderPosition = currentPosition
                } catch (_: Exception) {}
                if (!isPlaying) break
                delay(250)
            }
        }


        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onOpenSettingsRequest) {
                    Text("⚙", color = palette.primaryText)
                }
                Button(onClick = onOpenFileRequest) {
                    Text(strings.changeFileButton)
                }
            }
            HorizontalDivider(color = palette.border)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (playbackError != null) {
                    val localizedErr = localizeEngineMessage(playbackError ?: "", currentLang)
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.9f)
                            .background(palette.surface, RoundedCornerShape(20.dp))
                            .border(1.5.dp, palette.errorBadge, RoundedCornerShape(20.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = strings.formatErrorBadge,
                                color = palette.errorBadge,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = localizedErr,
                                color = palette.primaryText,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenFileRequest) {
                                Text(strings.changeFileButton)
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (s.hasVideo) {
                            val density = LocalDensity.current
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(Color.Black)
                                    .pointerInput(s) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downPos = down.position
                                            var moved = false
                                            val slop = viewConfiguration.touchSlop
                                            var accPan = Offset.Zero
                                            do {
                                                val ev = awaitPointerEvent(PointerEventPass.Main)
                                                val zoomChange = ev.calculateZoom()
                                                val panChange = ev.calculatePan()
                                                accPan += panChange
                                                if (zoomChange != 1f || accPan.getDistance() > slop) {
                                                    moved = true
                                                    val oldScale = videoScale
                                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 5f)
                                                    videoScale = newScale
                                                    if (newScale <= 1.01f) {
                                                        videoOffset = Offset.Zero
                                                    } else {
                                                        val maxOffX = (size.width * (newScale - 1f)) / 2f
                                                        val maxOffY = (size.height * (newScale - 1f)) / 2f
                                                        val rawOff = videoOffset * (newScale / oldScale) + panChange
                                                        videoOffset = Offset(
                                                            rawOff.x.coerceIn(-maxOffX, maxOffX),
                                                            rawOff.y.coerceIn(-maxOffY, maxOffY)
                                                        )
                                                    }
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            } while (ev.changes.any { it.pressed })

                                            if (!moved) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastVideoTapMs in 40L..320L &&
                                                    (downPos - lastVideoTapPos).getDistance() < 48.dp.toPx()
                                                ) {
                                                    if (videoScale > 1.15f) {
                                                        videoScale = 1f
                                                        videoOffset = Offset.Zero
                                                    } else {
                                                        videoScale = 2.5f
                                                    }
                                                    lastVideoTapMs = 0L
                                                } else {
                                                    lastVideoTapMs = now
                                                    lastVideoTapPos = downPos
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                                val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                                val containerRatio = boxW / boxH
                                val (fittedW, fittedH) = if (videoAspectRatio >= containerRatio) {
                                    boxW to (boxW / videoAspectRatio).coerceAtMost(boxH)
                                } else {
                                    (boxH * videoAspectRatio).coerceAtMost(boxW) to boxH
                                }
                                val fittedWidthDp = with(density) { fittedW.toDp() }
                                val fittedHeightDp = with(density) { fittedH.toDp() }

                                AndroidView(
                                    factory = { ctx ->
                                        SurfaceView(ctx).apply {
                                            holder.addCallback(object : SurfaceHolder.Callback {
                                                override fun surfaceCreated(h: SurfaceHolder) {
                                                    try {
                                                        s.mediaPlayer.setSurface(h.surface)
                                                        if (isPrepared && !s.mediaPlayer.isPlaying) {
                                                            s.mediaPlayer.seekTo(s.mediaPlayer.currentPosition.coerceAtLeast(1))
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                                    try { s.detachSurface(h.surface) } catch (_: Exception) {}
                                                }
                                            })
                                        }
                                    },
                                    update = {},
                                    modifier = Modifier
                                        .size(fittedWidthDp, fittedHeightDp)
                                        .graphicsLayer {
                                            scaleX = videoScale
                                            scaleY = videoScale
                                            translationX = videoOffset.x
                                            translationY = videoOffset.y
                                        }
                                )
                                subtitle?.let { text ->
                                    val zw = fittedW * videoScale
                                    val zh = fittedH * videoScale
                                    val left = ((boxW - zw) / 2f + videoOffset.x).coerceIn(0f, boxW)
                                    val top = ((boxH - zh) / 2f + videoOffset.y).coerceIn(0f, boxH)
                                    val right = ((boxW + zw) / 2f + videoOffset.x).coerceIn(left, boxW)
                                    val bottom = ((boxH + zh) / 2f + videoOffset.y).coerceIn(top, boxH)
                                    val frameH = bottom - top
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                            .size(with(density) { (right - left).toDp() }, with(density) { frameH.toDp() }),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        SubtitleOverlay(
                                            text,
                                            Modifier.padding(
                                                bottom = with(density) { (frameH * 0.05f).toDp() }.coerceAtLeast(6.dp),
                                                start = 12.dp,
                                                end = 12.dp
                                            ),
                                            fontSize = with(density) { (frameH * 0.055f).toSp() }.value.coerceIn(12f, 22f).sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val art = s.albumArt
                                if (art != null && !art.isRecycled) {
                                    Image(
                                        bitmap = art.asImageBitmap(),
                                        contentDescription = "Album Art",
                                        modifier = Modifier.size(240.dp).padding(16.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(200.dp)
                                            .background(palette.surface, RoundedCornerShape(16.dp))
                                            .border(1.dp, palette.border, RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(strings.audioLabel, color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text(resolvedTitle, color = palette.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text(resolvedArtist, color = palette.secondaryText, fontSize = 16.sp)
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.surfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(activeAudioDevice.deviceTypeIcon, fontSize = 13.sp)
                                        Text(
                                            text = "${activeAudioDevice.getLocalizedDeviceName(strings)} • $effectiveAudioQualityText",
                                            color = palette.accent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                subtitle?.let {
                                    Spacer(Modifier.height(16.dp))
                                    Text(it, color = palette.primaryText, fontSize = 18.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }

                        if (isPrepared) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Slider(
                                    value = sliderPosition.coerceIn(0f, duration),
                                    onValueChange = { pos -> isDragging = true; sliderPosition = pos },
                                    onValueChangeFinished = {
                                        isDragging = false
                                        currentPosition = sliderPosition
                                        controller.seekTo(sliderPosition.toLong())
                                    },
                                    valueRange = 0f..duration,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(currentPosition.toInt()), color = palette.secondaryText, fontSize = 14.sp)
                                    Text(formatTime(duration.toInt()), color = palette.secondaryText, fontSize = 14.sp)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(enabled = isPrepared, onClick = { controller.previous() }) {
                                Text("⏮", color = palette.primaryText, fontSize = 18.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(enabled = isPrepared, onClick = { controller.toggle() }) {
                                Text(
                                    text = if (!isPrepared) strings.preparingMedia else if (isPlaying) strings.pauseMedia else strings.playMedia,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(enabled = isPrepared && queueIndex < queue.size - 1, onClick = { controller.next() }) {
                                Text("⏭", color = palette.primaryText, fontSize = 18.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerMenuButton("${strings.mediaSpeed} ${formatSpeed(speed)}", palette) { close ->
                                for (v in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text((if (v == speed) "✓ " else "") + formatSpeed(v)) },
                                        onClick = { controller.setSpeed(v); close() }
                                    )
                                }
                            }
                            if (audioTracks.size > 1) {
                                PlayerMenuButton(strings.mediaAudioTrack, palette) { close ->
                                    for (t in audioTracks) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text((if (t.id == selectedAudio) "✓ " else "") + t.label) },
                                            onClick = { controller.selectAudioTrack(t.id); close() }
                                        )
                                    }
                                }
                            }
                            PlayerMenuButton(strings.mediaSubtitles, palette) { close ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text((if (selectedText == -1) "✓ " else "") + strings.mediaSubtitlesOff) },
                                    onClick = { controller.selectTextTrack(-1); close() }
                                )
                                for (t in textTracks) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text((if (t.id == selectedText) "✓ " else "") + t.label) },
                                        onClick = { controller.selectTextTrack(t.id); close() }
                                    )
                                }
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(strings.mediaLoadSubtitles) },
                                    onClick = { close(); subtitlePicker.launch(arrayOf("*/*")) }
                                )
                            }
                            OutlinedButton(onClick = { showQueue = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("${strings.mediaQueue} ${queueIndex + 1}/${queue.size.coerceAtLeast(1)}", color = palette.primaryText, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = { Text(strings.mediaQueue) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        itemsIndexed(queue) { i, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = i != queueIndex && item.uri != null) { controller.playAt(i) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (if (i == queueIndex) "▶ " else "${i + 1}. ") + item.title,
                                    color = if (i == queueIndex) palette.accent else palette.primaryText,
                                    fontWeight = if (i == queueIndex) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f)
                                )
                                if (i > 0) TextButton(onClick = { controller.moveInQueue(i, i - 1) }) { Text("↑") }
                                if (i < queue.size - 1) TextButton(onClick = { controller.moveInQueue(i, i + 1) }) { Text("↓") }
                                if (i != queueIndex) TextButton(onClick = { controller.removeFromQueue(i) }) { Text("✕") }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { queuePicker.launch(arrayOf("audio/*", "video/*")) }) { Text(strings.mediaAddFiles) }
                },
                dismissButton = {
                    TextButton(onClick = { showQueue = false }) { Text(strings.mediaClose) }
                }
            )
        }
    }
}

private fun formatSpeed(v: Float): String =
    if (v == kotlin.math.floor(v)) "${v.toInt()}×" else "${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}×"

private fun formatTime(ms: Int): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
