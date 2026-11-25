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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun ViewerTopToolbar(
    session: DocumentSession,
    is3D: Boolean,
    isSearchSupported: Boolean,
    isSearchVisible: Boolean,
    canShowQuickCopy: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    hasExecutedSearch: Boolean,
    searchResultCount: Int,
    isSearchDropdownExpanded: Boolean,
    onToggleSearchVisible: () -> Unit,
    onToggleSearchDropdown: () -> Unit,
    onTriggerSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    onQuickCopyOrSelectAll: () -> Unit,
    onReset3dCamera: () -> Unit,
    onOpenSettingsRequest: () -> Unit,
    onOpenFileRequest: () -> Unit,
    stateChangeTrigger: Int,
    onResourcesProvided: () -> Unit,
    animDuration: Float,
    animTime: Float,
    animPlaying: Boolean,
    animClips: List<String>,
    animClip: Int,
    onSelectAnimClip: (Int) -> Unit,
    onToggleAnimPlay: () -> Unit,
    onSeekAnim: (Float) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

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
                    Button(onClick = onReset3dCamera) {
                        Text(strings.reset3dButton)
                    }
                } else {
                    if (isSearchSupported) {
                        Button(onClick = onToggleSearchVisible) {
                            Text(if (isSearchVisible) strings.closeSearchButton else strings.searchButton)
                        }
                    }
                    if (canShowQuickCopy) {
                        OutlinedButton(
                            onClick = onQuickCopyOrSelectAll,
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

        ViewerExpandableSearchBar(
            visible = isSearchVisible && isSearchSupported,
            searchQuery = searchQuery,
            onQueryChange = onSearchQueryChange,
            hasExecutedSearch = hasExecutedSearch,
            resultCount = searchResultCount,
            isDropdownExpanded = isSearchDropdownExpanded,
            onToggleDropdown = onToggleSearchDropdown,
            onTriggerSearch = onTriggerSearch,
            focusRequester = searchFocusRequester
        )

        MissingResourcesBanner(session, stateChangeTrigger, onResourcesProvided)
        if (is3D && animDuration > 0f) {
            AnimationPlaybackBar(
                duration = animDuration,
                time = animTime,
                playing = animPlaying,
                clips = animClips,
                clip = animClip,
                onClip = onSelectAnimClip,
                onToggle = onToggleAnimPlay,
                onSeek = onSeekAnim
            )
        }
    }
    HorizontalDivider(color = palette.border)
}

@Composable
fun PresentationFloatingControlRail(
    currentPage: Int,
    totalPages: Int,
    scale: Float,
    isSearchVisible: Boolean,
    hasSpeakerNotes: Boolean,
    activeSlideTotalAnimSteps: Int,
    currentSlideAnimStep: Int,
    onOpenSettingsRequest: () -> Unit,
    onOpenFileRequest: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSpeakerNotes: () -> Unit,
    onAdvanceAnimStep: () -> Unit,
    onPrevSlideOrStep: () -> Unit,
    onNextSlideOrStep: () -> Unit,
    onZoomDelta: (Float) -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current

    Column(
        modifier = modifier
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
                onClick = onToggleSearch,
                shape = CircleShape,
                modifier = Modifier.requiredSize(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(if (isSearchVisible) "✕" else "🔍", fontSize = 12.sp)
            }

            if (hasSpeakerNotes) {
                OutlinedButton(
                    onClick = onOpenSpeakerNotes,
                    shape = CircleShape,
                    modifier = Modifier.requiredSize(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("📝", fontSize = 12.sp)
                }
            }

            if (activeSlideTotalAnimSteps > 0) {
                OutlinedButton(
                    onClick = onAdvanceAnimStep,
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
                onClick = onPrevSlideOrStep,
                shape = CircleShape,
                modifier = Modifier.requiredSize(30.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("◀", fontSize = 11.sp)
            }

            Button(
                enabled = currentPage < totalPages - 1 || (currentSlideAnimStep in 1 until activeSlideTotalAnimSteps),
                onClick = onNextSlideOrStep,
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
                onClick = { onZoomDelta(-0.25f) },
                shape = CircleShape,
                modifier = Modifier.requiredSize(30.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("-", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { onZoomDelta(0.25f) },
                shape = CircleShape,
                modifier = Modifier.requiredSize(30.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        TextButton(
            onClick = onResetZoom,
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

@Composable
fun ViewerBottomControlBar(
    sessionKey: Any,
    is3D: Boolean,
    isHtmlSession: Boolean,
    isHtmlBrowserMode: Boolean,
    currentPage: Int,
    totalPages: Int,
    isDraggingSlider: Boolean,
    zoomPercentDisplay: Int,
    onSliderPageJump: (Int, Boolean) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleHtmlBrowserMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    HorizontalDivider(color = palette.border)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!is3D && !isHtmlBrowserMode && totalPages > 1) {
            var sliderValue by remember(sessionKey) { mutableFloatStateOf(currentPage.toFloat()) }
            val maxPageIndexFloat = (totalPages - 1).coerceAtLeast(1).toFloat()

            LaunchedEffect(currentPage, totalPages, isDraggingSlider) {
                if (!isDraggingSlider) {
                    sliderValue = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)).toFloat()
                }
            }

            Slider(
                value = sliderValue.coerceIn(0f, maxPageIndexFloat),
                onValueChange = { newVal ->
                    sliderValue = newVal
                    val targetPage = newVal.roundToInt().coerceIn(0, totalPages - 1)
                    onSliderPageJump(targetPage, true)
                },
                onValueChangeFinished = {
                    val targetPage = sliderValue.roundToInt().coerceIn(0, totalPages - 1)
                    sliderValue = targetPage.toFloat()
                    onSliderPageJump(targetPage, false)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    enabled = currentPage > 0,
                    onClick = onPrevPage,
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
                    onClick = onNextPage,
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
                    onClick = onToggleHtmlBrowserMode,
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
                onClick = onZoomIn,
                shape = CircleShape,
                modifier = Modifier.requiredSize(36.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(2.dp))

            TextButton(
                onClick = onResetZoom,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "${zoomPercentDisplay}%",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Spacer(Modifier.width(2.dp))

            Button(
                onClick = onZoomOut,
                shape = CircleShape,
                modifier = Modifier.requiredSize(36.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
