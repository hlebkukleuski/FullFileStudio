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

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.ui.i18n.localizeEngineMessage
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun SpeakerNotesDialog(
    currentPage: Int,
    speakerNotes: String,
    isCopyRestricted: Boolean,
    clipManager: ClipboardManager?,
    onDismiss: () -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(strings.closeBtn)
            }
        },
        dismissButton = if (!isCopyRestricted) {
            {
                TextButton(
                    onClick = {
                        clipManager?.setPrimaryClip(ClipData.newPlainText("Slide Notes", speakerNotes))
                        onDismiss()
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
                    text = speakerNotes,
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

@Composable
fun FloatingTextSelectionBar(
    visible: Boolean,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = 12.dp),
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.surface.copy(alpha = 0.97f),
            border = BorderStroke(1.5.dp, palette.accent),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopy,
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

                OutlinedButton(
                    onClick = onSelectAll,
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

                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("✕", color = palette.secondaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ViewerSessionErrorOverlay(
    errText: String,
    currentLang: AppLanguage,
    isCopyRestricted: Boolean,
    onOpenHexFallbackRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val localizedErr = localizeEngineMessage(errText, currentLang)

    Box(
        modifier = modifier
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
            val allowInSessionHex = !isCopyRestricted &&
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
                OutlinedButton(onClick = onDismiss) {
                    Text(strings.closeBtn, color = palette.primaryText)
                }
            }
        }
    }
}

@Composable
fun SpreadsheetDockedCopyBar(
    visible: Boolean,
    sheetSelStartRow: Int,
    sheetSelStartCol: Int,
    sheetSelEndRow: Int,
    sheetSelEndCol: Int,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    AnimatedVisibility(
        visible = visible,
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
                    border = BorderStroke(1.5.dp, palette.accent),
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
                            onClick = onCopy,
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
                            onClick = onClear,
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
}

@Composable
fun SpreadsheetSheetTabsRow(
    sheetNames: List<String>,
    activeSheetIndex: Int,
    onSelectSheet: (Int) -> Unit
) {
    val palette = LocalAppPalette.current

    HorizontalDivider(color = palette.border)
    SecondaryScrollableTabRow(
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
                        onSelectSheet(index)
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
