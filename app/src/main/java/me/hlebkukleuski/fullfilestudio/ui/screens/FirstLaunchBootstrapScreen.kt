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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hlebkukleuski.fullfilestudio.BuildConfig
import me.hlebkukleuski.fullfilestudio.bootstrap.FirstLaunchEngineBootstrapper
import me.hlebkukleuski.fullfilestudio.ui.theme.AppLanguage
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings
import me.hlebkukleuski.fullfilestudio.ui.theme.ThemeDropdownSelector
import me.hlebkukleuski.fullfilestudio.ui.theme.localizeEngineMessage
import java.util.Locale

@Composable
fun FirstLaunchBootstrapScreen(
    bootstrapper: FirstLaunchEngineBootstrapper,
    onBootstrapComplete: () -> Unit
) {
    val state by bootstrapper.state.collectAsState()
    val playPluginManager = bootstrapper.playPluginManager
    val settingsManager = bootstrapper.settingsManager

    val isLanguageChosen by settingsManager.isInitialLanguageChosen.collectAsState()
    val currentLanguage by settingsManager.language.collectAsState()
    val currentThemeMode by settingsManager.themeMode.collectAsState()

    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    val selectedMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            playPluginManager.availableBundles.forEach { bundle ->
                put(bundle.id, false)
            }
        }
    }

    LaunchedEffect(state.isDownloading, state.errorMessage, state.isComplete) {
        playPluginManager.availableBundles.forEach { bundle ->
            if (playPluginManager.isModuleDownloaded(bundle.id)) {
                selectedMap[bundle.id] = false
            }
        }
        if (state.isComplete) {
            onBootstrapComplete()
        }
    }

    // Step 1: Initial language and theme selection on first launch
    if (!isLanguageChosen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 28.dp, horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = palette.primaryText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.selectLanguageTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.selectLanguageSubtitle,
                    fontSize = 13.sp,
                    color = palette.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = strings.themeLabel,
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    ThemeDropdownSelector(
                        currentMode = currentThemeMode,
                        onModeSelected = { settingsManager.setThemeMode(it) }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                AppLanguage.entries.forEach { lang ->
                    val isSelected = lang == currentLanguage
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) palette.accent else palette.border,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { settingsManager.setLanguage(lang) },
                        colors = CardDefaults.cardColors(containerColor = palette.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = lang.flagEmoji, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = lang.nativeName,
                                    color = palette.primaryText,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    color = palette.accent,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        settingsManager.confirmInitialLanguageSelection()
                        if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
                            bootstrapper.skipFirstLaunch()
                            onBootstrapComplete()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(
                        text = strings.continueButton,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) Color.Black else Color.White
                    )
                }
            }
        }
        return
    }

    // Standalone builds bundle all modules out of the box — never display the module installation screen
    if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
        LaunchedEffect(Unit) {
            bootstrapper.skipFirstLaunch()
            onBootstrapComplete()
        }
        return
    }

    // Step 2: Optional on-demand engine module selection (Google Play distribution only)
    val selectedIds = playPluginManager.availableBundles
        .filter { !playPluginManager.isModuleDownloaded(it.id) && (selectedMap[it.id] == true) }
        .map { it.id }

    val selectedSizeMb = playPluginManager.availableBundles
        .filter { it.id in selectedIds }
        .sumOf { it.sizeMb.toDouble() }
        .toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.appName,
                style = MaterialTheme.typography.headlineLarge,
                color = palette.primaryText,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = strings.engineCenterSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.accent,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.builtinFormatsDescription,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Strictly separated new line centered in the middle of the screen
            Text(
                text = strings.selectOptionalModulesPrompt,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.primaryText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            AnimatedVisibility(visible = state.isDownloading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progressPercent / 100f,
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                        label = "ProgressAnimation"
                    )

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = palette.accent,
                        trackColor = palette.border
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.currentModule,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (state.errorMessage != null) {
                val localizedError = localizeEngineMessage(state.errorMessage ?: "", currentLanguage)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(palette.errorBadge.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, palette.errorBadge, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = localizedError,
                            color = palette.errorBadge,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    bootstrapper.downloadSelectedBundles(
                                        bundleIds = selectedIds,
                                        completeBootstrapOnSuccess = true
                                    )
                                } else {
                                    bootstrapper.downloadAllBundles()
                                }
                            },
                            enabled = !state.isDownloading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = strings.retryDownloadButton,
                                color = palette.errorBadge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            playPluginManager.availableBundles.forEach { bundle ->
                val isDownloaded = playPluginManager.isModuleDownloaded(bundle.id)
                val isChecked = isDownloaded || (selectedMap[bundle.id] == true)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .border(
                            1.dp,
                            when {
                                isDownloaded -> palette.accent
                                isChecked -> palette.accentSecondary
                                else -> palette.border
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = !isDownloaded && !state.isDownloading) {
                            selectedMap[bundle.id] = !(selectedMap[bundle.id] ?: false)
                        },
                    colors = CardDefaults.cardColors(containerColor = palette.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (!isDownloaded && !state.isDownloading) {
                                    selectedMap[bundle.id] = checked
                                }
                            },
                            enabled = !isDownloaded && !state.isDownloading,
                            colors = CheckboxDefaults.colors(
                                checkedColor = palette.accent,
                                uncheckedColor = palette.secondaryText
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.localizedBundleName(bundle.id, bundle.name),
                                color = palette.primaryText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${strings.playModuleOpensFormatsPrefix} ${strings.localizedBundleDesc(bundle.id, bundle.description)}",
                                color = palette.secondaryText,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isDownloaded) {
                            Text(
                                text = strings.moduleReady,
                                color = palette.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                text = "${bundle.sizeMb} ${strings.mbSuffix}",
                                color = palette.secondaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { bootstrapper.downloadAllBundles() },
                enabled = !state.isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.accentSecondary)
            ) {
                Text(
                    text = strings.downloadAllOneClick,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (selectedIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        bootstrapper.downloadSelectedBundles(
                            bundleIds = selectedIds,
                            completeBootstrapOnSuccess = true
                        )
                    },
                    enabled = !state.isDownloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    val formattedSize = String.format(Locale.US, "%.1f", selectedSizeMb)
                    Text(
                        text = "${strings.installSelectedPrefix} (${selectedIds.size} • $formattedSize ${strings.mbSuffix})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) Color.Black else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { bootstrapper.skipFirstLaunch() },
                enabled = !state.isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(strings.workLightweightCoreOnly, color = palette.primaryText)
            }
        }
    }
}