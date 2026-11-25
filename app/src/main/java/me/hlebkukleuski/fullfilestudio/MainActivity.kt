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

package me.hlebkukleuski.fullfilestudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import me.hlebkukleuski.fullfilestudio.core.engine.ServerEndpoints
import me.hlebkukleuski.fullfilestudio.data.settings.AppFrameRateMode
import me.hlebkukleuski.fullfilestudio.di.appContainer
import me.hlebkukleuski.fullfilestudio.ui.reader.DocumentIntentResolver
import me.hlebkukleuski.fullfilestudio.ui.reader.ReaderUiAction
import me.hlebkukleuski.fullfilestudio.ui.reader.ReaderViewModel
import me.hlebkukleuski.fullfilestudio.ui.screens.ReaderRootScreen
import me.hlebkukleuski.fullfilestudio.ui.theme.FullFileStudioTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.factory(appContainer())
    }

    private var consumedIntentUri: String? = null

    private fun syncImmersiveSystemBars() {
        val windowVal = window ?: return
        val decorView = windowVal.decorView
        val controller = WindowCompat.getInsetsController(windowVal, decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val isImeVisible = ViewCompat.getRootWindowInsets(decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        val state = viewModel.uiState.value
        val session = state.currentSession
        if (session != null) {
            if (!state.isArchiveSession && session.isPresentationContent && !isImeVisible) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                if (!isImeVisible) {
                    controller.show(WindowInsetsCompat.Type.navigationBars())
                }
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncImmersiveSystemBars()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val snap = viewModel.uiState.value
        snap.pendingUri?.toString()?.let { outState.putString(KEY_PENDING_URI, it) }
        snap.lastOpenedUri?.toString()?.let { outState.putString(KEY_LAST_OPENED_URI, it) }
        consumedIntentUri?.let { outState.putString(KEY_CONSUMED_INTENT_URI, it) }
    }

    private fun applyFrameRateMode(mode: AppFrameRateMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = window.decorView.display ?: return
                val modes = display.supportedModes ?: return
                if (mode == AppFrameRateMode.AUTO) {
                    window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
                } else {
                    var bestMode = modes.firstOrNull()
                    var minDiff = Float.MAX_VALUE
                    for (m in modes) {
                        val diff = abs(m.refreshRate - mode.fps)
                        if (diff < minDiff) {
                            minDiff = diff
                            bestMode = m
                        }
                    }
                    if (bestMode != null) {
                        window.attributes = window.attributes.apply { preferredDisplayModeId = bestMode.modeId }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        ServerEndpoints.baseUrl = BuildConfig.SERVER_BASE_URL
        val bootstrapper = viewModel.bootstrapper

        val incomingUri = DocumentIntentResolver.extractIncomingIntentUri(intent)
        val previouslyConsumedUri = savedInstanceState?.getString(KEY_CONSUMED_INTENT_URI)
        if (incomingUri != null && (savedInstanceState == null || incomingUri.toString() != previouslyConsumedUri)) {
            consumedIntentUri = incomingUri.toString()
            bootstrapper.skipFirstLaunch()
            DocumentIntentResolver.clearConsumedIntentPayload(intent)
            viewModel.dispatch(ReaderUiAction.OpenUri(incomingUri))
        } else if (savedInstanceState != null) {
            consumedIntentUri = previouslyConsumedUri
            val savedLastUri = savedInstanceState.getString(KEY_LAST_OPENED_URI)?.let(Uri::parse)
            viewModel.restoreSavedPendingState(savedLastUri)
        }

        setContent {
            FullFileStudioTheme(settingsManager = bootstrapper.settingsManager) {
                ReaderRootScreen(
                    viewModel = viewModel,
                    onSyncSystemBars = ::syncImmersiveSystemBars,
                    onApplyFrameRateMode = ::applyFrameRateMode
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = DocumentIntentResolver.extractIncomingIntentUri(intent)
        DocumentIntentResolver.clearConsumedIntentPayload(intent)
        setIntent(intent)
        if (uri != null) {
            consumedIntentUri = uri.toString()
            viewModel.bootstrapper.skipFirstLaunch()
            viewModel.dispatch(ReaderUiAction.OpenUri(uri))
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bootstrapper.startMonitoring()
        viewModel.ensureServiceBound()
    }

    override fun onStop() {
        viewModel.bootstrapper.stopMonitoring()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.releaseAllSessionsAndUnbind()
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_LAST_OPENED_URI = "last_opened_uri"
        private const val KEY_CONSUMED_INTENT_URI = "consumed_intent_uri"
    }
}
