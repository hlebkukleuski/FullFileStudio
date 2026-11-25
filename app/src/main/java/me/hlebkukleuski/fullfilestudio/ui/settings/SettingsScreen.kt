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

package me.hlebkukleuski.fullfilestudio.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.engine.ServerEndpoints
import me.hlebkukleuski.fullfilestudio.data.settings.AppAudioQualityMode
import me.hlebkukleuski.fullfilestudio.data.settings.AppFrameRateMode
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.data.settings.AppSettingsManager
import me.hlebkukleuski.fullfilestudio.data.settings.AppThemeMode
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun ThemeDropdownSelector(
    currentMode: AppThemeMode,
    onModeSelected: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    val modeLabel = { mode: AppThemeMode ->
        when (mode) {
            AppThemeMode.DARK -> strings.themeDark
            AppThemeMode.LIGHT -> strings.themeLight
            AppThemeMode.AS_IN_PHONE -> strings.themeAsInPhone
        }
    }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .clickable { expanded = true },
            color = palette.surfaceElevated
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modeLabel(currentMode),
                    color = palette.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "▼",
                    color = palette.accent,
                    fontSize = 12.sp
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
        ) {
            AppThemeMode.entries.forEach { mode ->
                val isSelected = (mode == currentMode)
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = modeLabel(mode),
                                color = if (isSelected) palette.accent else palette.primaryText,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.width(16.dp))
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(palette.accent.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        color = palette.accent,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ThirdPartyLicensesDialog(
    onDismiss: () -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    val tabs = remember {
        listOf(
            "Notices" to listOf(
                "licenses/THIRD_PARTY_NOTICES.txt",
                "licenses/NOTICE-PaddleOCR.txt"
            ),
            "GPL-3.0" to listOf("licenses/GPL-3.0.txt"),
            "Fonts" to listOf(
                "fonts/office/LICENSE-Liberation.txt",
                "fonts/office/LICENSE-Carlito.txt",
                "fonts/office/LICENSE-Caladea.txt",
                "fonts/office/OFL-Gelasio.txt",
                "fonts/office/OFL-EBGaramond.txt",
                "fonts/office/OFL-ComicNeue.txt",
                "fonts/office/OFL-Selawik.txt",
                "fonts/office/LICENSE-TeXGyre-GFL.txt",
                "licenses/LPPL-1.3c.txt",
                "fonts/office/LICENSE-DejaVu.txt"
            ),
            "ONNX / MPL" to listOf(
                "licenses/MPL-2.0.txt",
                "licenses/ONNXRuntime-ThirdPartyNotices.txt"
            ),
            "Other" to listOf(
                "licenses/Apache-2.0.txt",
                "licenses/MIT.txt",
                "licenses/BSD-3-Clause.txt",
                "licenses/BSD-2-Clause.txt",
                "licenses/0BSD.txt",
                "licenses/TOST-1.0.txt"
            )
        )
    }

    var selectedTab by remember { mutableStateOf(0) }
    var chunks by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedTab) {
        val tabAssets = tabs[selectedTab].second
        chunks = withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            for (assetPath in tabAssets) {
                val content = try {
                    context.assets.open(assetPath).bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    "Unable to load $assetPath"
                }
                val lines = content.lines()
                var i = 0
                while (i < lines.size) {
                    val end = minOf(i + 40, lines.size)
                    out.add(lines.subList(i, end).joinToString("\n"))
                    i = end
                }
                out.add("")
            }
            out
        }
        listState.scrollToItem(0)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = strings.thirdPartyLicensesDialogTitle,
                    color = palette.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEachIndexed { idx, (label, _) ->
                        val active = idx == selectedTab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) palette.accent else palette.surfaceElevated)
                                .border(1.dp, if (active) palette.accent else palette.border, RoundedCornerShape(8.dp))
                                .clickable { selectedTab = idx }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (active) (if (palette.isDark) Color.Black else Color.White) else palette.primaryText,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = palette.border)
                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surfaceElevated)
                        .padding(12.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(chunks) { block ->
                            Text(
                                text = block,
                                color = palette.primaryText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(strings.closeBtn)
                }
            }
        }
    }
}

@Composable
fun FrameRateDropdownSelector(
    currentMode: AppFrameRateMode,
    onModeSelected: (AppFrameRateMode) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val maxHz = remember(context) { detectMaxDisplayRefreshRate(context) }
    var expanded by remember { mutableStateOf(false) }

    val availableModes = remember(maxHz) {
        AppFrameRateMode.entries.filter { mode ->
            mode == AppFrameRateMode.AUTO || (mode.fps > 0f && mode.fps < maxHz.toFloat() - 1f)
        }
    }

    fun localizedModeName(mode: AppFrameRateMode): String = when (mode) {
        AppFrameRateMode.AUTO -> strings.localizedFrameRateAuto(maxHz)
        AppFrameRateMode.FPS_90 -> strings.frameRate90
        AppFrameRateMode.FPS_60 -> strings.frameRate60
        AppFrameRateMode.FPS_25 -> strings.frameRate25
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = localizedModeName(currentMode),
                    color = palette.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(text = "▼", color = palette.secondaryText, fontSize = 11.sp)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(palette.surfaceElevated)
        ) {
            availableModes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = localizedModeName(mode),
                            color = if (mode == currentMode) palette.accent else palette.primaryText,
                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AudioQualityDropdownSelector(
    currentMode: AppAudioQualityMode,
    activeDevice: ActiveAudioDeviceCapabilities,
    onModeSelected: (AppAudioQualityMode) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    val availableModes = remember(activeDevice.maxSampleRateHz) {
        AppAudioQualityMode.entries.filter { mode ->
            mode == AppAudioQualityMode.AUTO || mode.sampleRateHz <= activeDevice.maxSampleRateHz || mode.sampleRateHz <= 48_000
        }
    }

    fun localizedAudioName(mode: AppAudioQualityMode): String = when (mode) {
        AppAudioQualityMode.AUTO -> strings.localizedAudioQualityAuto(activeDevice)
        AppAudioQualityMode.STUDIO_96 -> strings.audioQuality96
        AppAudioQualityMode.HIGH_48 -> strings.audioQuality48
        AppAudioQualityMode.STANDARD_44 -> strings.audioQuality44
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = localizedAudioName(currentMode),
                    color = palette.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "▼", color = palette.secondaryText, fontSize = 11.sp)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(palette.surfaceElevated)
        ) {
            availableModes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = localizedAudioName(mode),
                            color = if (mode == currentMode) palette.accent else palette.primaryText,
                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AppSettingsDrawerSheet(
    settingsManager: AppSettingsManager,
    onCloseDrawer: () -> Unit,
    onCheckForUpdates: (() -> Unit)? = null,
    isCheckingUpdates: Boolean = false,
    isUpToDate: Boolean = false
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val currentLanguage by settingsManager.language.collectAsState()
    val currentThemeMode by settingsManager.themeMode.collectAsState()
    val currentFrameRateMode by settingsManager.frameRateMode.collectAsState()
    val currentAudioQualityMode by settingsManager.audioQualityMode.collectAsState()
    val activeAudioDevice = rememberActiveAudioDeviceCapabilities()
    val uriHandler = LocalUriHandler.current
    var showLicensesDialog by remember { mutableStateOf(false) }

    if (showLicensesDialog) {
        ThirdPartyLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    ModalDrawerSheet(
        drawerContainerColor = palette.surface,
        drawerContentColor = palette.primaryText
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(310.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = strings.settingsMenuTitle,
                color = palette.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = palette.border)
            Spacer(Modifier.height(16.dp))

            Text(
                text = strings.themeLabel,
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            ThemeDropdownSelector(
                currentMode = currentThemeMode,
                onModeSelected = { settingsManager.setThemeMode(it) }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = strings.frameRateLabel,
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            FrameRateDropdownSelector(
                currentMode = currentFrameRateMode,
                onModeSelected = { settingsManager.setFrameRateMode(it) }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "${activeAudioDevice.deviceTypeIcon} ${strings.audioQualityLabel}",
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            AudioQualityDropdownSelector(
                currentMode = currentAudioQualityMode,
                activeDevice = activeAudioDevice,
                onModeSelected = { settingsManager.setAudioQualityMode(it) }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = strings.languageLabel,
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))

            AppLanguage.entries.forEach { lang ->
                val isSelected = lang == currentLanguage
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) palette.accent.copy(alpha = 0.14f) else palette.surfaceElevated)
                        .border(
                            1.dp,
                            if (isSelected) palette.accent else palette.border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { settingsManager.setLanguage(lang) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = lang.flagEmoji, fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = lang.nativeName,
                            color = palette.primaryText,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp
                        )
                    }
                    if (isSelected) {
                        Text(
                            text = "✓",
                            color = palette.accent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (onCheckForUpdates != null) {
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !isCheckingUpdates,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val label = when {
                        isCheckingUpdates -> strings.checkingUpdatesStatus
                        isUpToDate -> strings.appUpToDateStatus
                        else -> "⟳ ${strings.checkForUpdatesButton}"
                    }
                    Text(
                        text = label,
                        color = if (isUpToDate) palette.accent else palette.primaryText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onCloseDrawer,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(strings.closeDrawerButton, color = palette.primaryText)
            }

            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = palette.border)
            Spacer(Modifier.height(12.dp))

            Text(
                text = strings.aboutLegalSectionTitle,
                color = palette.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "FullFileStudio • Copyright (C) 2026 Hleb Kukleuski • GNU GPL v3.0+ • Provided WITHOUT ANY WARRANTY.",
                color = palette.secondaryText,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(6.dp))

            Text(
                text = strings.termsOfUseLink,
                color = palette.accent,
                fontSize = 11.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        try {
                            uriHandler.openUri(ServerEndpoints.termsOfServiceWebUrl)
                        } catch (_: Throwable) {}
                    }
                    .padding(vertical = 3.dp)
            )

            Text(
                text = strings.privacyPolicyLink,
                color = palette.accent,
                fontSize = 11.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        try {
                            uriHandler.openUri(ServerEndpoints.privacyPolicyWebUrl)
                        } catch (_: Throwable) {}
                    }
                    .padding(vertical = 3.dp)
            )

            Text(
                text = strings.thirdPartyLicensesLink,
                color = palette.accent,
                fontSize = 11.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showLicensesDialog = true }
                    .padding(vertical = 3.dp)
            )
        }
    }
}
