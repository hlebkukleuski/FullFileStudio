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
import android.graphics.RectF
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager
import me.hlebkukleuski.fullfilestudio.ui.i18n.stringsForLanguage
import me.hlebkukleuski.fullfilestudio.ui.reader.ProxyDocumentSession
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings
import me.hlebkukleuski.fullfilestudio.ui.viewer.ANIM_STEP_SHIFT
import me.hlebkukleuski.fullfilestudio.ui.viewer.BASE_REFLOW_FONT_SIZE_SP
import me.hlebkukleuski.fullfilestudio.ui.viewer.BitmapGestureCallbacks
import me.hlebkukleuski.fullfilestudio.ui.viewer.BitmapTileCanvasViewport
import me.hlebkukleuski.fullfilestudio.ui.viewer.FloatingTextSelectionBar
import me.hlebkukleuski.fullfilestudio.ui.viewer.ILLUSTRATION_ONLY_PAGE_FLAG
import me.hlebkukleuski.fullfilestudio.ui.viewer.LANG_ORDINAL_SHIFT
import me.hlebkukleuski.fullfilestudio.ui.viewer.LIGHT_THEME_PAGE_FLAG
import me.hlebkukleuski.fullfilestudio.ui.viewer.MediaRenderScreen
import me.hlebkukleuski.fullfilestudio.ui.viewer.OfflineHtmlViewer
import me.hlebkukleuski.fullfilestudio.ui.viewer.PresentationFloatingControlRail
import me.hlebkukleuski.fullfilestudio.ui.viewer.PresentationFloatingSearchBar
import me.hlebkukleuski.fullfilestudio.ui.viewer.RecognizedCharUi
import me.hlebkukleuski.fullfilestudio.ui.viewer.RecognizedLineUi
import me.hlebkukleuski.fullfilestudio.ui.viewer.ReflowDocumentViewport
import me.hlebkukleuski.fullfilestudio.ui.viewer.SLIDE_META_TAG_REGEX
import me.hlebkukleuski.fullfilestudio.ui.viewer.SearchMatchItem
import me.hlebkukleuski.fullfilestudio.ui.viewer.SpeakerNotesDialog
import me.hlebkukleuski.fullfilestudio.ui.viewer.SpreadsheetDockedCopyBar
import me.hlebkukleuski.fullfilestudio.ui.viewer.SpreadsheetSheetTabsRow
import me.hlebkukleuski.fullfilestudio.ui.viewer.StructuredReflowDocument
import me.hlebkukleuski.fullfilestudio.ui.viewer.TableRowLayoutMeta
import me.hlebkukleuski.fullfilestudio.ui.viewer.TileRenderRequest
import me.hlebkukleuski.fullfilestudio.ui.viewer.ViewerBottomControlBar
import me.hlebkukleuski.fullfilestudio.ui.viewer.ViewerSearchResultsDropdown
import me.hlebkukleuski.fullfilestudio.ui.viewer.ViewerSessionErrorOverlay
import me.hlebkukleuski.fullfilestudio.ui.viewer.ViewerTopToolbar
import me.hlebkukleuski.fullfilestudio.ui.viewer.buildFallbackCharsFromPageText
import me.hlebkukleuski.fullfilestudio.ui.viewer.buildOcrCacheKey
import me.hlebkukleuski.fullfilestudio.ui.viewer.clampCanvasOffset
import me.hlebkukleuski.fullfilestudio.ui.viewer.collectDocumentSearchMatches
import me.hlebkukleuski.fullfilestudio.ui.viewer.computeDocumentPageBoundsOnBuffer
import me.hlebkukleuski.fullfilestudio.ui.viewer.computeViewportPageRanges
import me.hlebkukleuski.fullfilestudio.ui.viewer.copyViewerSelectionToClipboard
import me.hlebkukleuski.fullfilestudio.ui.viewer.extractSlideSpeakerNotes
import me.hlebkukleuski.fullfilestudio.ui.viewer.normalizeRectToPage
import me.hlebkukleuski.fullfilestudio.ui.viewer.parseStructuredReflowDocument
import me.hlebkukleuski.fullfilestudio.ui.viewer.rememberViewerSessionFlags
import me.hlebkukleuski.fullfilestudio.ui.viewer.resolveMediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.ui.viewer.stripBoundedNativeMarkers
import me.hlebkukleuski.fullfilestudio.ui.viewer.stripSlideNotesAndMeta

@OptIn(ExperimentalCoroutinesApi::class)
private val OCR_BACKGROUND_DISPATCHER = Dispatchers.Default.limitedParallelism(1)

@Composable
fun TiledZoomableCanvas(
    session: DocumentSession?,
    initialPage: Int = 0,
    initialSheetIndex: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    onSheetChanged: (Int) -> Unit = {},
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
        AppLanguage.entries.firstOrNull { strings == stringsForLanguage(it) } ?: AppLanguage.EN
    }
    val activeLang by rememberUpdatedState(currentLang)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val textToolbar = LocalTextToolbar.current
    val viewConfig = LocalViewConfiguration.current

    var isDetectedHexContent by remember(session) { mutableStateOf(false) }
    val flags = rememberViewerSessionFlags(session, isDetectedHexContent)

    val predictiveBackScale = remember { Animatable(1f) }
    val predictiveBackAlpha = remember { Animatable(1f) }
    val predictiveBackCornerRadius = remember { Animatable(0f) }

    var scale by remember(session) { mutableFloatStateOf(1f) }
    var offset by remember(session) { mutableStateOf(Offset.Zero) }
    var currentPage by remember(session) { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
    LaunchedEffect(currentPage) { onPageChanged(currentPage) }

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
    var activeSheetIndex by remember(session) { mutableIntStateOf(initialSheetIndex.coerceAtLeast(0)) }
    LaunchedEffect(activeSheetIndex) { onSheetChanged(activeSheetIndex) }

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
        coroutineScope.launch { searchHighlightAlpha.snapTo(0f) }
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
    var fontScaleMultiplier by remember(session) { mutableFloatStateOf(1f) }
    var anchorCharOffset by remember(session) { mutableIntStateOf(0) }

    var isHtmlBrowserMode by remember(session, flags.isHtmlSession) { mutableStateOf(flags.isHtmlSession) }
    var htmlBrowserZoomPercent by remember(session) { mutableIntStateOf(100) }
    var activeWebViewRef by remember(session) { mutableStateOf<WebView?>(null) }
    var blockedExternalUrlPrompt by remember(session) { mutableStateOf<String?>(null) }
    var stateChangeTrigger by remember(session) { mutableIntStateOf(0) }
    var activeBufferWidth by remember(session) { mutableIntStateOf(if (flags.isHorizontalLayout) 1920 else 1080) }
    var activeBufferHeight by remember(session) { mutableIntStateOf(if (flags.isHorizontalLayout) 1080 else 1920) }

    DisposableEffect(session) {
        if (session is ProxyDocumentSession) {
            session.setSessionStateListener { stateChangeTrigger++ }
        }
        onDispose {
            searchJob?.cancel()
            if (session is ProxyDocumentSession) session.setSessionStateListener(null)
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
        mutableStateOf(StructuredReflowDocument(AnnotatedString(""), "", emptyList(), emptyList()))
    }

    LaunchedEffect(rawFullReflowText, fontScaleMultiplier, normalReflowTextColor, palette.accent) {
        structuredDoc = if (rawFullReflowText.isEmpty()) {
            StructuredReflowDocument(AnnotatedString(""), "", emptyList(), emptyList())
        } else {
            withContext(Dispatchers.Default) {
                parseStructuredReflowDocument(
                    rawFullText = rawFullReflowText,
                    baseFontSizeSp = BASE_REFLOW_FONT_SIZE_SP * fontScaleMultiplier,
                    normalTextColor = normalReflowTextColor,
                    accentHeadingColor = palette.accent,
                    isHtmlSource = flags.isHtmlSession
                )
            }
        }
    }

    val fullReflowText = structuredDoc.cleanText
    val useReflowLayout = flags.isTextReflowCandidate && (fullReflowText.isNotEmpty() || rawFullReflowText.isNotEmpty()) && !flags.isHexSession

    LaunchedEffect(session, flags.isTextReflowCandidate, flags.isSpreadsheet, stateChangeTrigger) {
        withContext(Dispatchers.IO) {
            val err = session.getLastError()
            val w = try { session.getDocumentWidth() } catch (_: Exception) { 0f }
            val h = try { session.getDocumentHeight() } catch (_: Exception) { 0f }
            val pCount = try { session.pageCount.coerceAtLeast(1) } catch (_: Exception) { 1 }
            val loadedSheets = if (flags.isSpreadsheet) try { session.getSheetNames() } catch (_: Exception) { emptyList() } else emptyList()
            val loadedSheetIdx = if (flags.isSpreadsheet) try { session.getActiveSheetIndex() } catch (_: Exception) { 0 } else 0
            val loadedColWidths = if (flags.isSpreadsheet) try { session.getColumnWidths().takeIf { it.isNotEmpty() } } catch (_: Exception) { null } else null
            val loadedRowHeights = if (flags.isSpreadsheet) try { session.getRowHeights().takeIf { it.isNotEmpty() } } catch (_: Exception) { null } else null
            val loadedReflow = if (flags.isTextReflowCandidate && rawFullReflowText.isEmpty()) try { session.getPageText(-1) } catch (_: Throwable) { "" } else rawFullReflowText
            val isHexDetected = loadedReflow.startsWith("HEX INSPECTOR |")

            withContext(Dispatchers.Main) {
                sessionError = err
                cachedDocWidth = w
                cachedDocHeight = h
                if (isHexDetected) isDetectedHexContent = true
                if (flags.isTextReflowCandidate && rawFullReflowText.isEmpty() && !isHexDetected) rawFullReflowText = loadedReflow
                if (!flags.isTextReflowCandidate || loadedReflow.isEmpty() || isHexDetected) {
                    totalPages = pCount
                    if (currentPage >= pCount) currentPage = (pCount - 1).coerceAtLeast(0)
                }
                if (flags.isSpreadsheet) {
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
        if (!useReflowLayout && !flags.is3D && !flags.isSpreadsheet) {
            if (isDraggingSlider) delay(180L)
            val targetPage = currentPage
            val text = withContext(Dispatchers.IO) { try { session.getPageText(targetPage) } catch (_: Throwable) { "" } }
            if (currentPage == targetPage) {
                currentPageNativeText = text
                if (text.startsWith("HEX INSPECTOR |")) isDetectedHexContent = true
            }
        }
    }

    LaunchedEffect(flags.isPresentation, currentPageNativeText) {
        if (flags.isPresentation) {
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

    val currentSlideSpeakerNotes = remember(flags.isPresentation, currentPageNativeText) {
        extractSlideSpeakerNotes(currentPageNativeText, flags.isPresentation)
    }
    val rawCurrentPageNativeTextWithoutNotes = remember(currentPageNativeText) {
        stripSlideNotesAndMeta(currentPageNativeText)
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
                            android.graphics.Canvas(prevCopy).drawBitmap(bmp, 0f, 0f, null)
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
        coroutineScope.launch {
            if (!isDraggingSlider) {
                val durMs = if (flags.isPresentation) activeSlideTransitionDurationMs else 220
                pageTransitionProgress.snapTo(0f)
                pageTransitionProgress.animateTo(1f, tween(durationMillis = durMs, easing = FastOutSlowInEasing))
            } else {
                pageTransitionProgress.snapTo(1f)
            }
            previousReflowPageIdx = -1
        }
    }

    var viewportPageRanges by remember(session) { mutableStateOf<List<IntRange>>(emptyList()) }
    val activeReflowIllustrationIndices = remember(currentPage, viewportPageRanges, structuredDoc.imageAnchors, useReflowLayout, fullReflowText) {
        if (!useReflowLayout || structuredDoc.imageAnchors.isEmpty()) {
            emptyList()
        } else {
            val range = viewportPageRanges.getOrElse(currentPage.coerceIn(0, (viewportPageRanges.size - 1).coerceAtLeast(0))) {
                0 until minOf(3500, fullReflowText.length)
            }
            val isLastPage = viewportPageRanges.isEmpty() || currentPage >= viewportPageRanges.size - 1
            val upperBound = if (isLastPage) Int.MAX_VALUE else range.last
            val matched = structuredDoc.imageAnchors.filter { it.charOffsetInCleanText in range.first..upperBound }.map { it.imageIndex }.distinct()
            when {
                matched.isNotEmpty() -> matched
                currentPage == 0 && structuredDoc.imageAnchors.first().charOffsetInCleanText == 0 -> listOf(structuredDoc.imageAnchors.first().imageIndex)
                else -> emptyList()
            }
        }
    }
    var selectedReflowIllustrationPos by remember(currentPage, activeReflowIllustrationIndices) { mutableIntStateOf(0) }
    val activeReflowIllustrationIndex = activeReflowIllustrationIndices.getOrElse(
        selectedReflowIllustrationPos.coerceIn(0, (activeReflowIllustrationIndices.size - 1).coerceAtLeast(0))
    ) { -1 }
    val shouldRunTileRenderer = !useReflowLayout || activeReflowIllustrationIndex >= 0

    LaunchedEffect(session, isInitialMetaLoaded, shouldRunTileRenderer, activeBufferWidth, activeBufferHeight) {
        if (!isInitialMetaLoaded || !shouldRunTileRenderer) return@LaunchedEffect
        val bmpW = activeBufferWidth.coerceIn(256, 2560)
        val bmpH = activeBufferHeight.coerceIn(256, 2560)
        val doubleBuffers = try {
            arrayOf(Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888))
        } catch (_: Throwable) {
            return@LaunchedEffect
        }
        var bufferIndex = 0

        snapshotFlow {
            val themeFlag = if (isDarkTheme) 0 else LIGHT_THEME_PAGE_FLAG
            val langBits = (activeLang.ordinal and 0xF) shl LANG_ORDINAL_SHIFT
            val animStepBits = if (flags.isPresentation && activeSlideTotalAnimSteps > 0) (currentSlideAnimStep.coerceIn(0, 15) and 0xF) shl ANIM_STEP_SHIFT else 0
            when {
                flags.is3D -> TileRenderRequest(
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
                useReflowLayout && activeReflowIllustrationIndex >= 0 -> TileRenderRequest(
                    page = activeReflowIllustrationIndex or ILLUSTRATION_ONLY_PAGE_FLAG or themeFlag,
                    tileX = 0, tileY = 0, zoomPermille = 1000, is3D = false, stateTrigger = stateChangeTrigger, bufferWidth = bmpW, bufferHeight = bmpH
                )
                !flags.isTableBoundedLayout -> {
                    val shouldFetchHighResTile = !isGestureActive && scale > 1.15f
                    TileRenderRequest(
                        page = (currentPage and 0x000FFFFF) or themeFlag or animStepBits,
                        tileX = if (shouldFetchHighResTile) (-offset.x).toInt() else 0,
                        tileY = if (shouldFetchHighResTile) (-offset.y).toInt() else 0,
                        zoomPermille = if (shouldFetchHighResTile) (scale * 1000f).toInt().coerceIn(1150, 10_000) else 1000,
                        is3D = false, stateTrigger = stateChangeTrigger, bufferWidth = bmpW, bufferHeight = bmpH
                    )
                }
                else -> TileRenderRequest(
                    page = (currentPage and 0x000FFFFF) or themeFlag,
                    tileX = (-offset.x).toInt(),
                    tileY = (-offset.y).toInt(),
                    zoomPermille = (scale * 1000f).toInt().coerceIn(500, if (flags.isSpreadsheet) 4_000 else 8_000),
                    is3D = false, stateTrigger = stateChangeTrigger, bufferWidth = bmpW, bufferHeight = bmpH
                )
            }
        }.conflate().collect { req ->
            val reqCleanPage = req.page and 0x000FFFFF
            if (renderedPage == reqCleanPage && !req.is3D && !flags.isTableBoundedLayout &&
                (req.zoomPermille != renderedZoomPermille || req.tileX != renderedTileX || req.tileY != renderedTileY)
            ) {
                delay(80L)
            }
            val backBmp = doubleBuffers[bufferIndex]
            bufferIndex = 1 - bufferIndex

            withContext(Dispatchers.Default) {
                try {
                    if (!flags.is3D && !flags.isTableBoundedLayout) {
                        backBmp.eraseColor(android.graphics.Color.TRANSPARENT)
                    } else {
                        backBmp.eraseColor(if (isDarkTheme) android.graphics.Color.rgb(0x10, 0x10, 0x14) else android.graphics.Color.rgb(0xE9, 0xEC, 0xF2))
                    }
                    if (req.is3D) {
                        session.setAnimationClip(req.animClip)
                        session.setAnimationTime(if (req.animTimeMs < 0) -1f else req.animTimeMs / 1000f)
                    }
                    session.renderTile(req.page, req.tileX, req.tileY, req.zoomPermille, backBmp)
                    val latestErr = session.getLastError()
                    val animDur = if (req.is3D) session.getAnimationDuration() else 0f
                    val clipList = if (req.is3D) session.getAnimationClips() else emptyList()
                    val w = session.getDocumentWidth()
                    val h = session.getDocumentHeight()
                    val cw = if (flags.isSpreadsheet) try { session.getColumnWidths().takeIf { it.isNotEmpty() } } catch (_: Exception) { null } else null
                    val rh = if (flags.isSpreadsheet) try { session.getRowHeights() } catch (_: Exception) { null } else null
                    val pCount = session.pageCount.coerceAtLeast(1)

                    withContext(Dispatchers.Main) {
                        activeBitmap = backBmp
                        if (req.is3D && clipList != animClips) animClips = clipList
                        if (req.is3D && animDur != animDuration) {
                            animDuration = animDur
                            if (animDur <= 0f) { animPlaying = false; animTime = -1f }
                        }
                        renderedPage = reqCleanPage
                        renderedTileX = req.tileX
                        renderedTileY = req.tileY
                        renderedZoomPermille = req.zoomPermille
                        sessionError = latestErr
                        if (w > 0f) cachedDocWidth = w
                        if (h > 0f) cachedDocHeight = h
                        if (flags.isSpreadsheet && cw != null) cachedColWidthsPx = cw
                        if (flags.isSpreadsheet && rh != null) cachedRowHeightsPx = rh.takeIf { it.isNotEmpty() }
                        renderVersion++
                        if (!useReflowLayout) totalPages = pCount
                        if (flags.isTableBoundedLayout) {
                            offset = clampCanvasOffset(offset, scale, true, flags.isHorizontalLayout, w, h, bmpW.toFloat(), bmpH.toFloat())
                        }
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { sessionError = e.localizedMessage ?: strings.errorRenderFailed }
                }
            }
        }
    }

    var ocrLines by remember(session) { mutableStateOf<List<RecognizedLineUi>>(emptyList()) }
    var ocrChars by remember(session) { mutableStateOf<List<RecognizedCharUi>>(emptyList()) }
    val ocrCache = remember(session) { mutableMapOf<Long, Triple<List<RecognizedLineUi>, List<RecognizedCharUi>, String>>() }
    val appContext = LocalContext.current.applicationContext
    val clipManager = remember { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }
    LaunchedEffect(Unit) { PaddleOcrManager.initialize(appContext) }

    fun clearAllSelections() {
        selStartChar = -1; selEndChar = -1; textSelStart = -1; textSelEnd = -1
        sheetSelStartRow = -1; sheetSelStartCol = -1; sheetSelEndRow = -1; sheetSelEndCol = -1
        activeDragPoint = null; textToolbar.hide()
    }

    val hasActiveTextSelection = selStartChar >= 0 || sheetSelStartRow >= 0 || textSelStart >= 0
    val hasUiStateToDismiss = isHtmlBrowserMode || isSearchVisible || showSpeakerNotesDialog || hasActiveTextSelection || scale > 1.05f

    BackHandler(enabled = !isExternalDrawerOpen && hasUiStateToDismiss) {
        when {
            isHtmlBrowserMode -> isHtmlBrowserMode = false
            hasActiveTextSelection -> clearAllSelections()
            isSearchVisible -> { isSearchVisible = false; searchQuery = ""; isSearchDropdownExpanded = false; clearActiveSearchHighlight() }
            showSpeakerNotesDialog -> showSpeakerNotesDialog = false
            scale > 1.05f -> { scale = 1f; offset = Offset.Zero }
        }
    }

    PredictiveBackHandler(enabled = !isExternalDrawerOpen && onNavigateBack != null && !hasUiStateToDismiss) { progress ->
        try {
            progress.collect { backEvent ->
                val p = backEvent.progress
                predictiveBackScale.snapTo(1f - p * 0.14f)
                predictiveBackAlpha.snapTo(1f - p * 0.15f)
                predictiveBackCornerRadius.snapTo(p * 28f)
            }
            onNavigateBack?.invoke()
        } catch (_: CancellationException) {
            predictiveBackScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            predictiveBackAlpha.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            predictiveBackCornerRadius.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
        } finally {
            predictiveBackScale.snapTo(1f); predictiveBackAlpha.snapTo(1f); predictiveBackCornerRadius.snapTo(0f)
        }
    }

    LaunchedEffect(currentPage, activeSheetIndex, isDarkTheme, session) {
        clearAllSelections()
        val cacheKey = buildOcrCacheKey(currentPage, activeSheetIndex, isDarkTheme, (scale * 1000f).roundToInt().coerceIn(500, 10_000), (-offset.x).roundToInt(), (-offset.y).roundToInt())
        val cached = ocrCache[cacheKey]
        if (cached != null) { ocrLines = cached.first; ocrChars = cached.second } else { ocrLines = emptyList(); ocrChars = emptyList() }
    }

    LaunchedEffect(
        renderedPage, renderedZoomPermille, renderedTileX, renderedTileY, rawCurrentPageNativeTextWithoutNotes,
        activeSheetIndex, isDarkTheme, useReflowLayout, flags.is3D, flags.isHexSession, flags.isSpreadsheet,
        isDraggingSlider, cachedDocWidth, cachedDocHeight
    ) {
        if (isDraggingSlider || useReflowLayout || flags.is3D || flags.isSpreadsheet) return@LaunchedEffect
        val bmp = activeBitmap ?: return@LaunchedEffect
        if (bmp.isRecycled) return@LaunchedEffect

        val snapPage = renderedPage
        val snapZoomPermille = renderedZoomPermille
        val snapTileX = renderedTileX
        val snapTileY = renderedTileY
        val key = buildOcrCacheKey(snapPage, activeSheetIndex, isDarkTheme, snapZoomPermille, snapTileX, snapTileY)
        if (session.isCopyRestricted) { ocrLines = emptyList(); ocrChars = emptyList(); return@LaunchedEffect }
        ocrCache[key]?.let { ocrLines = it.first; ocrChars = it.second; return@LaunchedEffect }

        if (flags.isHexSession) {
            if (cleanCurrentPageNativeText.isNotEmpty()) {
                val (fbLines, fbChars) = buildFallbackCharsFromPageText(cleanCurrentPageNativeText, bmp.width, bmp.height, true, cachedDocWidth, cachedDocHeight, snapZoomPermille, snapTileX, snapTileY)
                ocrLines = fbLines; ocrChars = fbChars; ocrCache[key] = Triple(fbLines, fbChars, cleanCurrentPageNativeText)
            }
            return@LaunchedEffect
        }

        if (snapPage == currentPage && rawCurrentPageNativeTextWithoutNotes.contains("[PDF_BOX:")) {
            val (pdfLines, pdfChars) = buildFallbackCharsFromPageText(rawCurrentPageNativeTextWithoutNotes, bmp.width, bmp.height, false, cachedDocWidth, cachedDocHeight, snapZoomPermille, snapTileX, snapTileY)
            if (pdfChars.isNotEmpty() && ocrChars.isEmpty()) { ocrLines = pdfLines; ocrChars = pdfChars }
        }

        while (pageTransitionProgress.isRunning) delay(24L)
        delay(if (activeSearchHighlightPage == currentPage && activeSearchHighlightText.isNotEmpty()) 30L else 240L)

        val latestBmp = activeBitmap ?: return@LaunchedEffect
        if (latestBmp.isRecycled || renderedPage != snapPage) return@LaunchedEffect
        val snapHint = if (snapPage == currentPage) rawCurrentPageNativeTextWithoutNotes else ""
        val isolatedOcrBmp = try { latestBmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null } ?: return@LaunchedEffect

        withContext(OCR_BACKGROUND_DISPATCHER) {
            try {
                val ocrPayload = PaddleOcrManager.recognize(isolatedOcrBmp, snapHint, snapZoomPermille, snapTileX, snapTileY, cachedDocWidth, cachedDocHeight, activeLang.code)
                val snapPageBounds = computeDocumentPageBoundsOnBuffer(isolatedOcrBmp.width.toFloat(), isolatedOcrBmp.height.toFloat(), cachedDocWidth, cachedDocHeight, snapZoomPermille, snapTileX, snapTileY, false)
                var lineList = ocrPayload.lines.map { RecognizedLineUi(it.text, it.rect, normalizeRectToPage(RectF(it.rect), snapPageBounds)) }
                var charList = ocrPayload.chars.map { RecognizedCharUi(it.text, it.lineIndex, RectF(it.rect), normalizeRectToPage(RectF(it.rect), snapPageBounds)) }
                if (charList.isEmpty() && snapHint.isNotBlank()) {
                    val (fbLines, fbChars) = buildFallbackCharsFromPageText(rawCurrentPageNativeTextWithoutNotes, isolatedOcrBmp.width, isolatedOcrBmp.height, false, cachedDocWidth, cachedDocHeight, snapZoomPermille, snapTileX, snapTileY)
                    lineList = fbLines; charList = fbChars
                }
                val fullOcr = lineList.joinToString(" ") { it.text }
                withContext(Dispatchers.Main) {
                    if (renderedPage == snapPage) {
                        if (ocrCache.size > 24) ocrCache.clear()
                        ocrCache[key] = Triple(lineList, charList, fullOcr)
                        if (selStartChar < 0 || selEndChar < 0 || ocrChars.isEmpty()) { ocrLines = lineList; ocrChars = charList }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (!isolatedOcrBmp.isRecycled) isolatedOcrBmp.recycle()
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
                        .first { (ver, hasOcr) -> if (flags.isSpreadsheet) ver >= minVer else ver >= minVer && (hasOcr || flags.isHexSession) }
                }
            }
            delay(1000L)
            searchHighlightAlpha.animateTo(0f, tween(durationMillis = 2000, easing = LinearOutSlowInEasing))
            activeSearchHighlightPage = -1; activeSearchHighlightStart = -1; activeSearchHighlightEnd = -1
            activeSearchHighlightText = ""; activeSearchHighlightSheet = -1; activeSearchHighlightRow = -1; activeSearchHighlightCol = -1
        }
    }

    val isSearchSupported = (flags.isHtmlSession && isHtmlBrowserMode) ||
        (!flags.is3D && (useReflowLayout || flags.isTextReflowCandidate || flags.isPagedWordDocument || flags.isSpreadsheet || flags.isPresentation || flags.isPdfOrFontSession || totalPages > 1 || flags.isHexSession))

    fun triggerDocumentSearch() {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()
        if (flags.isHtmlSession && isHtmlBrowserMode) {
            lastExecutedQuery = trimmed; hasExecutedSearch = true
            activeWebViewRef?.findAllAsync(trimmed); activeWebViewRef?.findNext(true)
            return
        }
        if (trimmed == lastExecutedQuery && hasExecutedSearch && !isSearchingInProgress) { isSearchDropdownExpanded = true; return }
        searchJob?.cancel()
        isSearchingInProgress = true; isSearchDropdownExpanded = true
        searchJob = coroutineScope.launch(Dispatchers.IO) {
            val matches = collectDocumentSearchMatches(trimmed, useReflowLayout, fullReflowText, viewportPageRanges, totalPages, flags.isHexSession, flags.isSpreadsheet, sheetNames, session)
            withContext(Dispatchers.Main) {
                lastExecutedQuery = trimmed; searchResults = matches; hasExecutedSearch = true
                isSearchingInProgress = false; selectedMatchIndex = -1; isSearchDropdownExpanded = true
                searchDropdownListState.scrollToItem(0)
            }
        }
    }

    fun applyZoomStep(delta: Float) {
        val oldScale = scale
        val newScale = (oldScale + delta).coerceIn(if (flags.is3D) 0.2f else 1.0f, if (flags.isSpreadsheet) 4.0f else 8.0f)
        scale = newScale
        if (flags.is3D) return
        val scaledOffset = if (oldScale > 0.001f) offset * (newScale / oldScale) else offset
        offset = if (newScale <= 1.02f && !flags.isTableBoundedLayout) Offset.Zero else clampCanvasOffset(
            scaledOffset, newScale, flags.isTableBoundedLayout, flags.isHorizontalLayout, cachedDocWidth, cachedDocHeight, activeBufferWidth.toFloat(), activeBufferHeight.toFloat()
        )
    }

    fun copyActiveSelection(fullPageTextForReflow: String) {
        copyViewerSelectionToClipboard(
            session = session, clipManager = clipManager, coroutineScope = coroutineScope,
            useReflowLayout = useReflowLayout, isSpreadsheet = flags.isSpreadsheet,
            fullPageTextForReflow = fullPageTextForReflow, textSelStart = textSelStart, textSelEnd = textSelEnd,
            sheetSelStartRow = sheetSelStartRow, sheetSelStartCol = sheetSelStartCol, sheetSelEndRow = sheetSelEndRow, sheetSelEndCol = sheetSelEndCol,
            ocrChars = ocrChars, selStartChar = selStartChar, selEndChar = selEndChar,
            cleanCurrentPageNativeText = cleanCurrentPageNativeText, onClearSelections = ::clearAllSelections
        )
    }

    if (showSpeakerNotesDialog && currentSlideSpeakerNotes.isNotEmpty()) {
        SpeakerNotesDialog(currentPage, currentSlideSpeakerNotes, session.isCopyRestricted, clipManager) { showSpeakerNotesDialog = false }
    }

    val hasAnyActiveSelection = (useReflowLayout && textSelStart >= 0 && textSelEnd >= 0) ||
        (!useReflowLayout && flags.isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0 && sheetSelEndRow >= 0 && sheetSelEndCol >= 0) ||
        (!useReflowLayout && !flags.isSpreadsheet && selStartChar >= 0 && selEndChar >= 0)

    val rootInsetsModifier = if (flags.isPresentation) Modifier.displayCutoutPadding() else Modifier.statusBarsPadding().navigationBarsPadding().displayCutoutPadding()
    val magnifierModifier = if (activeDragPoint != null) {
        Modifier.magnifier(sourceCenter = { activeDragPoint ?: lastDragPoint }, magnifierCenter = { (activeDragPoint ?: lastDragPoint) - Offset(0f, 110f) }, zoom = 1.35f)
    } else Modifier

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = predictiveBackScale.value; scaleY = predictiveBackScale.value; alpha = predictiveBackAlpha.value }
            .clip(RoundedCornerShape(predictiveBackCornerRadius.value.dp))
            .background(if (flags.is3D || flags.isPresentation || flags.isTableBoundedLayout) palette.background else palette.canvasBackground)
            .then(rootInsetsModifier)
    ) {
        if (!flags.isPresentation) {
            ViewerTopToolbar(
                session = session, is3D = flags.is3D, isSearchSupported = isSearchSupported, isSearchVisible = isSearchVisible,
                canShowQuickCopy = !session.isCopyRestricted && !useReflowLayout && !flags.isSpreadsheet && !flags.isHtmlSession && (ocrChars.isNotEmpty() || cleanCurrentPageNativeText.isNotBlank()),
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it; if (it.trim() != lastExecutedQuery) hasExecutedSearch = false },
                hasExecutedSearch = hasExecutedSearch, searchResultCount = searchResults.size, isSearchDropdownExpanded = isSearchDropdownExpanded,
                onToggleSearchVisible = {
                    isSearchVisible = !isSearchVisible
                    if (!isSearchVisible) { isSearchDropdownExpanded = false; clearActiveSearchHighlight(); activeWebViewRef?.clearMatches() }
                    else if (!isHtmlBrowserMode && hasExecutedSearch && searchResults.isNotEmpty()) isSearchDropdownExpanded = true
                },
                onToggleSearchDropdown = { isSearchDropdownExpanded = !isSearchDropdownExpanded },
                onTriggerSearch = ::triggerDocumentSearch, searchFocusRequester = searchFocusRequester,
                onQuickCopyOrSelectAll = {
                    if (!session.isCopyRestricted) {
                        if (ocrChars.isNotEmpty()) { selStartChar = 0; selEndChar = ocrChars.lastIndex }
                        else if (cleanCurrentPageNativeText.isNotBlank()) clipManager?.setPrimaryClip(ClipData.newPlainText("OCR Text", cleanCurrentPageNativeText))
                    }
                },
                onReset3dCamera = { yaw = 25f; pitch = -20f; scale = 1f; fontScaleMultiplier = 1f },
                onOpenSettingsRequest = onOpenSettingsRequest, onOpenFileRequest = onOpenFileRequest,
                stateChangeTrigger = stateChangeTrigger, onResourcesProvided = { stateChangeTrigger++ },
                animDuration = animDuration, animTime = animTime, animPlaying = animPlaying, animClips = animClips, animClip = animClip,
                onSelectAnimClip = { animClip = it; animTime = if (animPlaying) 0f else -1f },
                onToggleAnimPlay = { animPlaying = !animPlaying },
                onSeekAnim = { animPlaying = false; animTime = it }
            )
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().systemGestureExclusion().then(magnifierModifier)
        ) {
            val density = LocalDensity.current
            val rawViewportWidthPx = constraints.maxWidth.coerceAtLeast(100)
            val rawViewportHeightPx = constraints.maxHeight.coerceAtLeast(100)

            LaunchedEffect(rawViewportWidthPx, rawViewportHeightPx) {
                val scaleFactor = 1920f / maxOf(rawViewportWidthPx.toFloat(), rawViewportHeightPx.toFloat()).coerceAtLeast(1f)
                val targetW = ((rawViewportWidthPx * scaleFactor).roundToInt() and -2).coerceIn(320, 2400)
                val targetH = ((rawViewportHeightPx * scaleFactor).roundToInt() and -2).coerceIn(320, 2400)
                if (abs(targetW - activeBufferWidth) > 8 || abs(targetH - activeBufferHeight) > 8) {
                    activeBufferWidth = targetW; activeBufferHeight = targetH
                }
            }

            val paddingHorizPx = with(density) { 20.dp.toPx() }
            val paddingVertPx = with(density) { 14.dp.toPx() }
            val availWidthPx = (rawViewportWidthPx - (paddingHorizPx * 2f).toInt()).coerceAtLeast(100)
            val availHeightPx = (rawViewportHeightPx - (paddingVertPx * 2f).toInt()).coerceAtLeast(100)
            val illustrationReservedHeightPx = with(density) { 216.dp.toPx() }.toInt()
            val textMeasurer = rememberTextMeasurer(cacheSize = 0)
            val reflowTextStyle = remember(fontScaleMultiplier, normalReflowTextColor) {
                TextStyle(color = normalReflowTextColor, fontSize = (BASE_REFLOW_FONT_SIZE_SP * fontScaleMultiplier).sp, lineHeight = (BASE_REFLOW_FONT_SIZE_SP * 1.4f * fontScaleMultiplier).sp)
            }

            LaunchedEffect(structuredDoc.annotatedText, structuredDoc.imageAnchors, availWidthPx, availHeightPx, illustrationReservedHeightPx, reflowTextStyle, useReflowLayout) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    val savedAnchorOffset = viewportPageRanges.getOrNull(currentPage.coerceIn(0, (viewportPageRanges.size - 1).coerceAtLeast(0)))?.first ?: anchorCharOffset
                    if (viewportPageRanges.isEmpty() && fullReflowText.length > 35_000) {
                        val quickInitial = withContext(Dispatchers.Default) {
                            computeViewportPageRanges(fullReflowText, { s, e -> textMeasurer.measure(text = structuredDoc.annotatedText.subSequence(s, e), style = reflowTextStyle, constraints = Constraints(maxWidth = availWidthPx)) }, availHeightPx, structuredDoc.imageAnchors, illustrationReservedHeightPx, 20)
                        }
                        if (quickInitial.isNotEmpty()) { viewportPageRanges = quickInitial; totalPages = maxOf(quickInitial.size, fullReflowText.length / 2600) }
                    } else if (viewportPageRanges.isNotEmpty()) delay(25)

                    val computed = withContext(Dispatchers.Default) {
                        computeViewportPageRanges(fullReflowText, { s, e -> textMeasurer.measure(text = structuredDoc.annotatedText.subSequence(s, e), style = reflowTextStyle, constraints = Constraints(maxWidth = availWidthPx)) }, availHeightPx, structuredDoc.imageAnchors, illustrationReservedHeightPx)
                    }
                    viewportPageRanges = computed; totalPages = computed.size.coerceAtLeast(1)
                    val restoredPage = computed.indexOfFirst { savedAnchorOffset in it }.takeIf { it >= 0 } ?: currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    currentPage = restoredPage; anchorCharOffset = computed.getOrNull(restoredPage)?.first ?: 0
                } else if (!useReflowLayout) viewportPageRanges = emptyList()
            }

            val currentRange = remember(useReflowLayout, fullReflowText, viewportPageRanges, currentPage) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    if (viewportPageRanges.isNotEmpty()) viewportPageRanges.getOrElse(currentPage.coerceIn(0, viewportPageRanges.lastIndex)) { 0 until 0 }
                    else 0 until min(fullReflowText.length, 4500)
                } else 0 until 0
            }
            LaunchedEffect(currentRange) { if (useReflowLayout && !currentRange.isEmpty()) anchorCharOffset = currentRange.first }

            val pageText = remember(useReflowLayout, fullReflowText, currentRange, cleanCurrentPageNativeText) {
                if (useReflowLayout && fullReflowText.isNotEmpty()) {
                    if (currentRange.first <= currentRange.last && currentRange.last < fullReflowText.length) fullReflowText.substring(currentRange.first, currentRange.last + 1) else ""
                } else cleanCurrentPageNativeText
            }
            val previousReflowText = remember(useReflowLayout, viewportPageRanges, previousReflowPageIdx, structuredDoc.annotatedText) {
                if (useReflowLayout && previousReflowPageIdx in viewportPageRanges.indices) {
                    val prevR = viewportPageRanges[previousReflowPageIdx]
                    if (!prevR.isEmpty() && prevR.last < structuredDoc.annotatedText.length) structuredDoc.annotatedText.subSequence(prevR.first, prevR.last + 1) else null
                } else null
            }
            val pageTableRows = remember(useReflowLayout, structuredDoc.tableRows, currentRange) {
                if (!useReflowLayout || structuredDoc.tableRows.isEmpty() || currentRange.isEmpty()) emptyList() else {
                    val pStart = currentRange.first; val pEnd = currentRange.last + 1
                    structuredDoc.tableRows.mapNotNull { row ->
                        val ovS = maxOf(row.charStart, pStart); val ovE = min(row.charEndExclusive, pEnd)
                        if (ovE > ovS) TableRowLayoutMeta(ovS - pStart, ovE - pStart, row.isHeaderRow, row.separatorOffsets.filter { it in ovS until ovE }.map { it - pStart }) else null
                    }
                }
            }

            if (flags.isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) {
                OfflineHtmlViewer(
                    htmlSource = rawFullReflowText.ifBlank { cleanCurrentPageNativeText },
                    htmlBrowserZoomPercent = htmlBrowserZoomPercent, searchQuery = searchQuery, isSearchVisible = isSearchVisible,
                    activeWebViewRef = activeWebViewRef, onActiveWebViewChanged = { activeWebViewRef = it },
                    blockedExternalUrlPrompt = blockedExternalUrlPrompt, onBlockedUrlChanged = { blockedExternalUrlPrompt = it },
                    clipManager = clipManager, modifier = Modifier.fillMaxSize()
                )
            } else if (useReflowLayout && pageText.isNotBlank()) {
                ReflowDocumentViewport(
                    session = session, structuredDoc = structuredDoc, currentRange = currentRange, pageText = pageText,
                    previousReflowText = previousReflowText, pageTableRows = pageTableRows, reflowTextStyle = reflowTextStyle,
                    normalReflowTextColor = normalReflowTextColor, currentPage = currentPage, totalPages = totalPages,
                    pageTransitionProgress = pageTransitionProgress.value, pageTransitionDirection = pageTransitionDirection,
                    activeBitmap = activeBitmap, renderVersion = renderVersion,
                    activeReflowIllustrationIndex = activeReflowIllustrationIndex, activeReflowIllustrationIndices = activeReflowIllustrationIndices,
                    selectedReflowIllustrationPos = selectedReflowIllustrationPos, onSelectIllustrationPos = { selectedReflowIllustrationPos = it },
                    activeSearchHighlightPage = activeSearchHighlightPage, activeSearchHighlightStart = activeSearchHighlightStart,
                    activeSearchHighlightEnd = activeSearchHighlightEnd, searchHighlightAlpha = searchHighlightAlpha.value,
                    textLayoutResult = textLayoutResult, onTextLayout = { textLayoutResult = it },
                    textSelStart = textSelStart, textSelEnd = textSelEnd, onUpdateTextSelection = { s, e -> textSelStart = s; textSelEnd = e },
                    onUpdateDragPoint = { active, last -> activeDragPoint = active; if (last != null) lastDragPoint = last },
                    fontScaleMultiplier = fontScaleMultiplier, onUpdateFontScale = { fontScaleMultiplier = it },
                    lastTapTimeMs = lastTapTimeMs, lastTapPosition = lastTapPosition, onUpdateLastTap = { ms, pos -> lastTapTimeMs = ms; lastTapPosition = pos },
                    isSearchDropdownExpanded = isSearchDropdownExpanded, onDismissSearchDropdown = { isSearchDropdownExpanded = false },
                    onCopySelection = ::copyActiveSelection, onNavigateToPage = ::navigateToPageSmoothly,
                    paddingHorizPx = paddingHorizPx, paddingVertPx = paddingVertPx
                )
            } else {
                val gestureCallbacks = BitmapGestureCallbacks(
                    session = session, flags = flags, coroutineScope = coroutineScope, ocrDispatcher = OCR_BACKGROUND_DISPATCHER,
                    viewConfig = viewConfig, haptic = haptic, textToolbar = textToolbar, focusManager = focusManager, flingAnimatable = flingAnimatable,
                    activeLangCode = { activeLang.code }, currentPage = { currentPage }, totalPages = { totalPages },
                    scale = { scale }, onScaleChange = { scale = it }, offset = { offset }, onOffsetChange = { offset = it },
                    yaw = { yaw }, pitch = { pitch }, onUpdate3dAngles = { y, p -> yaw = y; pitch = p },
                    activeBitmap = { activeBitmap }, bufferWidth = { activeBufferWidth }, bufferHeight = { activeBufferHeight },
                    docWidth = { cachedDocWidth }, docHeight = { cachedDocHeight }, colWidthsPx = { cachedColWidthsPx }, rowHeightsPx = { cachedRowHeightsPx },
                    renderedPage = { renderedPage }, renderedZoomPermille = { renderedZoomPermille }, renderedTileX = { renderedTileX }, renderedTileY = { renderedTileY },
                    rawNativePageText = { rawCurrentPageNativeTextWithoutNotes },
                    ocrLines = { ocrLines }, ocrChars = { ocrChars }, onUpdateOcr = { l, c -> ocrLines = l; ocrChars = c },
                    selStartChar = { selStartChar }, selEndChar = { selEndChar }, onUpdateCharSel = { s, e -> selStartChar = s; selEndChar = e },
                    sheetSelStartRow = { sheetSelStartRow }, sheetSelStartCol = { sheetSelStartCol }, sheetSelEndRow = { sheetSelEndRow }, sheetSelEndCol = { sheetSelEndCol },
                    onUpdateSheetSel = { r0, c0, r1, c1 -> sheetSelStartRow = r0; sheetSelStartCol = c0; sheetSelEndRow = r1; sheetSelEndCol = c1 },
                    onClearAllSelections = ::clearAllSelections, onCopySelection = { copyActiveSelection("") },
                    onSetGestureActive = { isGestureActive = it },
                    onUpdateDragPoint = { active, last -> activeDragPoint = active; if (last != null) lastDragPoint = last },
                    lastTapTimeMs = { lastTapTimeMs }, lastTapPosition = { lastTapPosition }, onUpdateLastTap = { ms, pos -> lastTapTimeMs = ms; lastTapPosition = pos },
                    isSearchDropdownExpanded = { isSearchDropdownExpanded }, onDismissSearchDropdown = { isSearchDropdownExpanded = false },
                    onNavigateToPage = ::navigateToPageSmoothly
                )
                BitmapTileCanvasViewport(
                    flags = flags, gestureCallbacks = gestureCallbacks, activeBitmap = activeBitmap, previousPageBitmap = previousPageBitmap,
                    renderVersion = renderVersion, currentPage = currentPage, renderedPage = renderedPage, renderedZoomPermille = renderedZoomPermille,
                    renderedTileX = renderedTileX, renderedTileY = renderedTileY, scale = scale, offset = offset,
                    pageTransitionProgress = pageTransitionProgress.value, pageTransitionDirection = pageTransitionDirection,
                    activeSlideTransitionType = activeSlideTransitionType, cachedDocWidth = cachedDocWidth, cachedDocHeight = cachedDocHeight,
                    cachedColWidthsPx = cachedColWidthsPx, cachedRowHeightsPx = cachedRowHeightsPx, activeSheetIndex = activeSheetIndex,
                    activeSearchHighlightMinRenderVersion = activeSearchHighlightMinRenderVersion,
                    activeSearchHighlightSheet = activeSearchHighlightSheet, activeSearchHighlightRow = activeSearchHighlightRow, activeSearchHighlightCol = activeSearchHighlightCol,
                    activeSearchHighlightPage = activeSearchHighlightPage, activeSearchHighlightText = activeSearchHighlightText,
                    activeSearchHighlightOccurrence = activeSearchHighlightOccurrence, searchHighlightAlpha = searchHighlightAlpha.value,
                    ocrLines = ocrLines, ocrChars = ocrChars, selStartChar = selStartChar, selEndChar = selEndChar,
                    sheetSelStartRow = sheetSelStartRow, sheetSelStartCol = sheetSelStartCol, sheetSelEndRow = sheetSelEndRow, sheetSelEndCol = sheetSelEndCol
                )
            }

            FloatingTextSelectionBar(
                visible = !session.isCopyRestricted && hasAnyActiveSelection && !flags.isSpreadsheet,
                onCopy = { copyActiveSelection(pageText) },
                onSelectAll = {
                    if (useReflowLayout && pageText.isNotEmpty()) { textSelStart = 0; textSelEnd = pageText.length - 1 }
                    else if (ocrChars.isNotEmpty()) { selStartChar = 0; selEndChar = ocrChars.lastIndex }
                },
                onClear = ::clearAllSelections,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            PresentationFloatingSearchBar(
                visible = flags.isPresentation && isSearchVisible,
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it; if (it.trim() != lastExecutedQuery) hasExecutedSearch = false },
                hasExecutedSearch = hasExecutedSearch, resultCount = searchResults.size, isDropdownExpanded = isSearchDropdownExpanded,
                onToggleDropdown = { isSearchDropdownExpanded = !isSearchDropdownExpanded },
                onTriggerSearch = ::triggerDocumentSearch, focusRequester = searchFocusRequester,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            if (isSearchVisible && isSearchDropdownExpanded && (isSearchingInProgress || hasExecutedSearch)) {
                ViewerSearchResultsDropdown(
                    isPresentation = flags.isPresentation, isSearchingInProgress = isSearchingInProgress,
                    searchResults = searchResults, selectedMatchIndex = selectedMatchIndex, listState = searchDropdownListState,
                    onSelectResult = { idx, item ->
                        focusManager.clearFocus(); selectedMatchIndex = idx; isSearchDropdownExpanded = false
                        if (flags.isSpreadsheet && item.cellRow >= 0 && item.cellCol >= 0) {
                            val targetSheet = item.sheetIndex.coerceAtLeast(0)
                            coroutineScope.launch(Dispatchers.IO) {
                                val sheetChanged = (targetSheet != activeSheetIndex)
                                if (sheetChanged) try { session.setActiveSheetIndex(targetSheet) } catch (_: Exception) {}
                                val newDocW = try { session.getDocumentWidth() } catch (_: Exception) { cachedDocWidth }
                                val newDocH = try { session.getDocumentHeight() } catch (_: Exception) { cachedDocHeight }
                                val newColWidths = try { session.getColumnWidths().takeIf { it.isNotEmpty() } } catch (_: Exception) { cachedColWidthsPx }
                                val newRowHeights = try { session.getRowHeights().takeIf { it.isNotEmpty() } } catch (_: Exception) { cachedRowHeightsPx }
                                withContext(Dispatchers.Main) {
                                    val zoom = scale.coerceIn(0.5f, 4.0f)
                                    val colCount = SpreadsheetCoordinateUtils.resolveColumnCount(newDocW, newColWidths, item.cellCol + 1)
                                    val bufW = activeBufferWidth.toFloat(); val bufH = activeBufferHeight.toFloat()
                                    val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(colCount, newColWidths, bufW, zoom)
                                    val safeCol = item.cellCol.coerceIn(0, colCount - 1)
                                    val colLeft = colOffsets[safeCol]
                                    val colW = (colOffsets[safeCol + 1] - colLeft).coerceAtLeast(40f)
                                    val targetRect = SpreadsheetCoordinateUtils.computeCellRectOnBitmap(item.cellRow, safeCol, item.cellRow, safeCol, colOffsets, zoom, 0f, 0f, newRowHeights)
                                    val targetTop = targetRect.top - SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
                                    val desiredEffX = (colLeft - (bufW - SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom - colW) / 2f).coerceAtLeast(0f)
                                    val desiredEffY = (targetTop - (bufH - SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom - targetRect.height().coerceAtLeast(1f)) / 2f).coerceAtLeast(0f)
                                    val newOffset = clampCanvasOffset(Offset(-desiredEffX, -desiredEffY), zoom, true, flags.isHorizontalLayout, newDocW, newDocH, bufW, bufH)
                                    val offsetChanged = (newOffset.x.toInt() != offset.x.toInt() || newOffset.y.toInt() != offset.y.toInt())
                                    cachedDocWidth = newDocW; cachedDocHeight = newDocH; cachedColWidthsPx = newColWidths; cachedRowHeightsPx = newRowHeights; offset = newOffset
                                    if (sheetChanged) { activeSheetIndex = targetSheet; stateChangeTrigger++ }
                                    activeSearchHighlightMinRenderVersion = if (sheetChanged || offsetChanged) renderVersion + 1 else renderVersion
                                    activeSearchHighlightSheet = targetSheet; activeSearchHighlightRow = item.cellRow; activeSearchHighlightCol = item.cellCol
                                    searchHighlightTrigger++
                                }
                            }
                        } else {
                            val targetPage = item.pageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                            val pageChanged = (targetPage != currentPage || offset != Offset.Zero)
                            activeSearchHighlightMinRenderVersion = if (!useReflowLayout && pageChanged) renderVersion + 1 else renderVersion
                            navigateToPageSmoothly(targetPage)
                            activeSearchHighlightPage = targetPage; activeSearchHighlightStart = item.charStartInPage
                            activeSearchHighlightEnd = item.charEndExclusiveInPage; activeSearchHighlightOccurrence = item.occurrenceIndexOnPage
                            activeSearchHighlightText = item.matchedText; searchHighlightTrigger++
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            sessionError?.let { errText ->
                ViewerSessionErrorOverlay(
                    errText = errText, currentLang = currentLang, isCopyRestricted = session.isCopyRestricted,
                    onOpenHexFallbackRequest = onOpenHexFallbackRequest, onDismiss = { sessionError = null },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (flags.isPresentation) {
                PresentationFloatingControlRail(
                    currentPage = currentPage, totalPages = totalPages, scale = scale, isSearchVisible = isSearchVisible,
                    hasSpeakerNotes = currentSlideSpeakerNotes.isNotEmpty(), activeSlideTotalAnimSteps = activeSlideTotalAnimSteps,
                    currentSlideAnimStep = currentSlideAnimStep, onOpenSettingsRequest = onOpenSettingsRequest, onOpenFileRequest = onOpenFileRequest,
                    onToggleSearch = {
                        isSearchVisible = !isSearchVisible
                        if (!isSearchVisible) { focusManager.clearFocus(); isSearchDropdownExpanded = false; clearActiveSearchHighlight() }
                        else if (hasExecutedSearch && searchResults.isNotEmpty()) isSearchDropdownExpanded = true
                    },
                    onOpenSpeakerNotes = { showSpeakerNotesDialog = true },
                    onAdvanceAnimStep = { currentSlideAnimStep = (currentSlideAnimStep + 1) % (activeSlideTotalAnimSteps + 1) },
                    onPrevSlideOrStep = { if (currentSlideAnimStep > 1) currentSlideAnimStep-- else if (currentPage > 0) navigateToPageSmoothly(currentPage - 1) },
                    onNextSlideOrStep = { if (currentSlideAnimStep in 1 until activeSlideTotalAnimSteps) currentSlideAnimStep++ else if (currentPage < totalPages - 1) navigateToPageSmoothly(currentPage + 1) },
                    onZoomDelta = ::applyZoomStep, onResetZoom = { scale = 1f; offset = Offset.Zero },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        SpreadsheetDockedCopyBar(
            visible = !session.isCopyRestricted && flags.isSpreadsheet && hasAnyActiveSelection,
            sheetSelStartRow = sheetSelStartRow, sheetSelStartCol = sheetSelStartCol, sheetSelEndRow = sheetSelEndRow, sheetSelEndCol = sheetSelEndCol,
            onCopy = { copyActiveSelection("") }, onClear = ::clearAllSelections
        )

        if (flags.isSpreadsheet && sheetNames.size > 1) {
            SpreadsheetSheetTabsRow(sheetNames, activeSheetIndex) { index ->
                clearActiveSearchHighlight(); clearAllSelections(); activeSheetIndex = index; offset = Offset.Zero; sessionError = null
                coroutineScope.launch(Dispatchers.IO) {
                    try { session.setActiveSheetIndex(index) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { stateChangeTrigger++ }
                }
            }
        }

        if (!flags.isPresentation) {
            ViewerBottomControlBar(
                sessionKey = session, is3D = flags.is3D, isHtmlSession = flags.isHtmlSession, isHtmlBrowserMode = isHtmlBrowserMode,
                currentPage = currentPage, totalPages = totalPages, isDraggingSlider = isDraggingSlider,
                zoomPercentDisplay = when {
                    flags.isHtmlSession && isHtmlBrowserMode -> htmlBrowserZoomPercent
                    useReflowLayout -> (fontScaleMultiplier * 100).roundToInt()
                    else -> (scale * 100).roundToInt()
                },
                onSliderPageJump = { targetPage, dragging ->
                    isDraggingSlider = dragging
                    if (targetPage != currentPage || !dragging) { currentPage = targetPage; offset = Offset.Zero }
                },
                onPrevPage = { if (currentPage > 0) navigateToPageSmoothly(currentPage - 1) },
                onNextPage = { if (currentPage < totalPages - 1) navigateToPageSmoothly(currentPage + 1) },
                onToggleHtmlBrowserMode = { clearAllSelections(); isSearchDropdownExpanded = false; isHtmlBrowserMode = !isHtmlBrowserMode },
                onZoomIn = {
                    if (flags.isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) htmlBrowserZoomPercent = (htmlBrowserZoomPercent + 15).coerceAtMost(300)
                    else if (useReflowLayout) fontScaleMultiplier = (fontScaleMultiplier + 0.15f).coerceAtMost(3.5f)
                    else applyZoomStep(0.25f)
                },
                onZoomOut = {
                    if (flags.isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) htmlBrowserZoomPercent = (htmlBrowserZoomPercent - 15).coerceAtLeast(50)
                    else if (useReflowLayout) fontScaleMultiplier = (fontScaleMultiplier - 0.15f).coerceAtLeast(0.5f)
                    else applyZoomStep(-0.25f)
                },
                onResetZoom = {
                    if (flags.isHtmlSession && isHtmlBrowserMode && !session.isCopyRestricted) htmlBrowserZoomPercent = 100
                    else { scale = 1f; fontScaleMultiplier = 1f; offset = Offset.Zero }
                }
            )
        }
    }
}
