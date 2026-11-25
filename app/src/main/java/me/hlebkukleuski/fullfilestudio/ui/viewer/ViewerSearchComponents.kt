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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun ViewerExpandableSearchBar(
    visible: Boolean,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    hasExecutedSearch: Boolean,
    resultCount: Int,
    isDropdownExpanded: Boolean,
    onToggleDropdown: () -> Unit,
    onTriggerSearch: () -> Unit,
    focusRequester: FocusRequester
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    AnimatedVisibility(visible = visible) {
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
                        onValueChange = onQueryChange,
                        placeholder = { Text(strings.searchPlaceholder, color = palette.secondaryText) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onTriggerSearch() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.primaryText,
                            unfocusedTextColor = palette.primaryText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }

            if (hasExecutedSearch && resultCount > 0) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = onToggleDropdown,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    val arrow = if (isDropdownExpanded) "▲" else "▼"
                    Text(
                        text = "$arrow $resultCount",
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            Button(onClick = onTriggerSearch) {
                Text(strings.findButton)
            }
        }
    }
}

@Composable
fun PresentationFloatingSearchBar(
    visible: Boolean,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    hasExecutedSearch: Boolean,
    resultCount: Int,
    isDropdownExpanded: Boolean,
    onToggleDropdown: () -> Unit,
    onTriggerSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = visible,
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
                            onValueChange = onQueryChange,
                            placeholder = { Text(strings.searchPlaceholder, color = palette.secondaryText, fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = palette.primaryText),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onTriggerSearch() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = palette.primaryText,
                                unfocusedTextColor = palette.primaryText
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .focusRequester(focusRequester)
                        )
                    }
                }
                if (hasExecutedSearch && resultCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onToggleDropdown,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        val arrow = if (isDropdownExpanded) "▲" else "▼"
                        Text(
                            text = "$arrow $resultCount",
                            color = palette.accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = onTriggerSearch,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(strings.findButton, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun ViewerSearchResultsDropdown(
    isPresentation: Boolean,
    isSearchingInProgress: Boolean,
    searchResults: List<SearchMatchItem>,
    selectedMatchIndex: Int,
    listState: LazyListState,
    onSelectResult: (Int, SearchMatchItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val topPad = if (isPresentation) 64.dp else 6.dp
    val endPad = if (isPresentation) 96.dp else 16.dp

    Column(
        modifier = modifier
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
                state = listState,
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
                            .clickable { onSelectResult(idx, item) }
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
