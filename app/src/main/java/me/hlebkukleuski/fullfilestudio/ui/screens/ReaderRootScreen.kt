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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.hlebkukleuski.fullfilestudio.BuildConfig
import me.hlebkukleuski.fullfilestudio.StandaloneUpdateManager
import me.hlebkukleuski.fullfilestudio.core.media.AudioQualitySpec
import me.hlebkukleuski.fullfilestudio.data.settings.AppFrameRateMode
import me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController
import me.hlebkukleuski.fullfilestudio.ui.i18n.localizeEngineMessage
import me.hlebkukleuski.fullfilestudio.ui.reader.EnterpriseErrorDialog
import me.hlebkukleuski.fullfilestudio.ui.reader.PasswordUnlockDialog
import me.hlebkukleuski.fullfilestudio.ui.reader.ReaderUiAction
import me.hlebkukleuski.fullfilestudio.ui.reader.ReaderViewModel
import me.hlebkukleuski.fullfilestudio.ui.reader.StandaloneUpdateDialog
import me.hlebkukleuski.fullfilestudio.ui.reader.UiErrorCategory
import me.hlebkukleuski.fullfilestudio.ui.settings.AppSettingsDrawerSheet
import me.hlebkukleuski.fullfilestudio.ui.settings.rememberActiveAudioDeviceCapabilities
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
fun ReaderRootScreen(
    viewModel: ReaderViewModel,
    onSyncSystemBars: () -> Unit,
    onApplyFrameRateMode: (AppFrameRateMode) -> Unit
) {
    val bootstrapper = viewModel.bootstrapper
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val currentLang by bootstrapper.settingsManager.language.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val updateManager = remember(appContext) { StandaloneUpdateManager(appContext, drawerScope) }
    val updateState by updateManager.state.collectAsState()

    var isBootstrapComplete by rememberSaveable {
        mutableStateOf(
            bootstrapper.isFirstLaunchCompleted() &&
                bootstrapper.settingsManager.isInitialLanguageChosen.value
        )
    }
    val bootstrapState by bootstrapper.state.collectAsState()

    LaunchedEffect(uiState.currentSession, uiState.isArchiveSession) {
        val activity = context as? ComponentActivity
        if (uiState.currentSession != null &&
            !uiState.isArchiveSession &&
            uiState.currentSession?.isPresentationContent == true
        ) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onSyncSystemBars()
    }

    val frameRateMode by bootstrapper.settingsManager.frameRateMode.collectAsState()
    LaunchedEffect(frameRateMode) {
        onApplyFrameRateMode(frameRateMode)
    }

    val audioQualityMode by bootstrapper.settingsManager.audioQualityMode.collectAsState()
    val activeAudioDevice = rememberActiveAudioDeviceCapabilities()
    LaunchedEffect(audioQualityMode, activeAudioDevice.maxSampleRateHz) {
        MediaPlaybackController.setAudioQualitySpec(
            AudioQualitySpec(
                modeId = audioQualityMode.id,
                targetSampleRateHz = audioQualityMode.sampleRateHz,
                targetBitDepth = audioQualityMode.bitDepth,
                maxDeviceSampleRateHz = activeAudioDevice.maxSampleRateHz
            )
        )
    }

    LaunchedEffect(bootstrapState.isComplete) {
        if (bootstrapState.isComplete) isBootstrapComplete = true
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            viewModel.dispatch(ReaderUiAction.OpenUri(it))
        }
    }

    val isBackEnabled = drawerState.isOpen || uiState.currentSession != null || uiState.hasSessionHistory
    BackHandler(enabled = isBackEnabled) {
        if (drawerState.isOpen) {
            drawerScope.launch { drawerState.close() }
        } else {
            viewModel.dispatch(ReaderUiAction.NavigateBack)
            onSyncSystemBars()
        }
    }

    if (!isBootstrapComplete) {
        FirstLaunchBootstrapScreen(
            bootstrapper = bootstrapper,
            onBootstrapComplete = { isBootstrapComplete = true }
        )
        return
    }

    LaunchedEffect(Unit) {
        if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
            updateManager.checkForUpdates(manual = false)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppSettingsDrawerSheet(
                settingsManager = bootstrapper.settingsManager,
                onCloseDrawer = { drawerScope.launch { drawerState.close() } },
                onCheckForUpdates = if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
                    { updateManager.checkForUpdates(manual = true) }
                } else null,
                isCheckingUpdates = updateState.isChecking,
                isUpToDate = updateState.isUpToDate
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            if (uiState.currentSession != null) {
                if (uiState.isArchiveSession) {
                    ArchiveView(
                        entries = uiState.archiveEntries,
                        onEntryClick = { entryName ->
                            viewModel.dispatch(ReaderUiAction.OpenArchiveEntry(entryName))
                        },
                        onBackClick = {
                            viewModel.dispatch(ReaderUiAction.NavigateBack)
                            onSyncSystemBars()
                        },
                        onOpenSettingsClick = { drawerScope.launch { drawerState.open() } }
                    )
                } else {
                    TiledZoomableCanvas(
                        session = uiState.currentSession,
                        initialPage = uiState.savedPageIndex,
                        initialSheetIndex = uiState.savedSheetIndex,
                        onPageChanged = { page -> viewModel.dispatch(ReaderUiAction.PageChanged(page)) },
                        onSheetChanged = { sheet -> viewModel.dispatch(ReaderUiAction.SheetChanged(sheet)) },
                        onOpenFileRequest = { launcher.launch(arrayOf("*/*")) },
                        onOpenSettingsRequest = { drawerScope.launch { drawerState.open() } },
                        onOpenHexFallbackRequest = {
                            val target = uiState.pendingUri ?: uiState.lastOpenedUri
                            if (target != null) {
                                viewModel.dispatch(ReaderUiAction.OpenHexFallback(target))
                            }
                        },
                        onNavigateBack = {
                            viewModel.dispatch(ReaderUiAction.NavigateBack)
                            onSyncSystemBars()
                        },
                        isExternalDrawerOpen = drawerState.isOpen,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { drawerScope.launch { drawerState.open() } },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("⚙ ${strings.settingsMenuTitle}", color = palette.primaryText)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.headlineLarge,
                        color = palette.primaryText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = strings.universalReaderSubtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.secondaryText,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { launcher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text(
                            text = strings.openFileSaf,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (palette.isDark) Color.Black else Color.White
                        )
                    }

                    if (uiState.isBufferingFile) {
                        Spacer(modifier = Modifier.height(24.dp))
                        LinearProgressIndicator(
                            progress = { uiState.bufferingProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(0.75f),
                            color = palette.accent
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.bufferingCloudFile,
                            color = palette.accent,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
                updateState.availableRelease?.let { release ->
                    StandaloneUpdateDialog(
                        release = release,
                        updateState = updateState,
                        onConfirmDownload = { updateManager.downloadAndInstall(context) },
                        onDismiss = { updateManager.dismissDialog() }
                    )
                }
            }

            uiState.structuredError?.let { err ->
                val targetUri = uiState.pendingUri ?: uiState.lastOpenedUri
                val canOpenHex = err.allowHexFallback && (targetUri != null || uiState.hasVirtualHexBackup)
                val (badgeText, badgeColor, titleText) = when (err.category) {
                    UiErrorCategory.ACCESS_OR_SECURITY -> Triple(
                        strings.securityBadge,
                        palette.warningBadge,
                        strings.accessRestrictedTitle
                    )
                    UiErrorCategory.CLOSED_LICENSE -> Triple(
                        strings.closedLicenseBadge,
                        palette.warningBadge,
                        strings.closedLicenseTitle
                    )
                    UiErrorCategory.PASSWORD_REQUIRED -> Triple(
                        strings.passwordRequiredBadge,
                        palette.infoBadge,
                        strings.passwordRequiredTitle
                    )
                    UiErrorCategory.FORMAT_STRUCTURE -> Triple(
                        strings.formatErrorBadge,
                        palette.errorBadge,
                        strings.formatReadErrorTitle
                    )
                }

                val localizedMessage = localizeEngineMessage(err.message, currentLang)
                val fullDescription = if (canOpenHex) {
                    "$localizedMessage\n\n${strings.hexFallbackHint}"
                } else {
                    localizedMessage
                }

                if (err.category == UiErrorCategory.PASSWORD_REQUIRED && targetUri != null) {
                    PasswordUnlockDialog(
                        badgeText = badgeText,
                        badgeColor = badgeColor,
                        titleText = titleText,
                        descriptionText = localizedMessage,
                        placeholderText = strings.passwordPlaceholder,
                        unlockButtonText = strings.unlockBtn,
                        hexButtonText = if (canOpenHex) strings.openInHexBtn else null,
                        closeButtonText = strings.closeBtn,
                        onUnlock = { enteredPassword ->
                            viewModel.dispatch(ReaderUiAction.DismissError)
                            viewModel.dispatch(ReaderUiAction.OpenUri(targetUri, enteredPassword))
                        },
                        onHexClick = if (canOpenHex) {
                            {
                                viewModel.dispatch(ReaderUiAction.DismissError)
                                viewModel.dispatch(ReaderUiAction.OpenHexFallback(targetUri))
                            }
                        } else null,
                        onDismissRequest = { viewModel.dispatch(ReaderUiAction.DismissError) }
                    )
                } else {
                    EnterpriseErrorDialog(
                        badgeText = badgeText,
                        badgeColor = badgeColor,
                        titleText = titleText,
                        descriptionText = fullDescription,
                        primaryButtonText = if (canOpenHex) strings.openInHexBtn else strings.closeBtn,
                        onPrimaryClick = {
                            val uriToHex = targetUri ?: Uri.parse("virtual://hex_fallback")
                            viewModel.dispatch(ReaderUiAction.DismissError)
                            if (canOpenHex) {
                                viewModel.dispatch(ReaderUiAction.OpenHexFallback(uriToHex))
                            }
                        },
                        secondaryButtonText = if (canOpenHex) strings.closeBtn else null,
                        onSecondaryClick = { viewModel.dispatch(ReaderUiAction.DismissError) },
                        onDismissRequest = { viewModel.dispatch(ReaderUiAction.DismissError) }
                    )
                }
            }
        }
    }
}
