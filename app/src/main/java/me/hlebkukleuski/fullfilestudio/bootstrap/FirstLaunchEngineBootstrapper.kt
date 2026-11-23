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

package me.hlebkukleuski.fullfilestudio.bootstrap

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.hlebkukleuski.fullfilestudio.ui.theme.AppSettingsManager

data class BootstrapState(
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val isComplete: Boolean = false,
    val currentModule: String = "",
    val errorMessage: String? = null
)

class FirstLaunchEngineBootstrapper(context: Context) {

    private val _state = MutableStateFlow(BootstrapState())
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    val playPluginManager = PlayFeaturePluginManager(context)
    val settingsManager = AppSettingsManager(context)

    fun startMonitoring() {
        if (playPluginManager.isFirstLaunchCompleted()) {
            _state.value = _state.value.copy(isComplete = true, progressPercent = 100)
        }
    }

    fun stopMonitoring() {
    }

    fun downloadAllBundles() {
        val allModuleIds = playPluginManager.availableBundles.map { it.id }
        downloadSelectedBundles(allModuleIds, completeBootstrapOnSuccess = true)
    }

    fun downloadSelectedBundles(
        bundleIds: List<String>,
        completeBootstrapOnSuccess: Boolean = true,
        onDone: () -> Unit = {}
    ) {
        if (_state.value.isDownloading) return
        if (bundleIds.isEmpty()) {
            if (completeBootstrapOnSuccess) skipFirstLaunch()
            onDone()
            return
        }

        _state.value = _state.value.copy(
            isDownloading = true,
            progressPercent = 0,
            currentModule = bundleIds.joinToString(", "),
            errorMessage = null
        )

        playPluginManager.downloadModules(
            moduleNames = bundleIds,
            onProgress = { progress ->
                val percent = (progress * 100).toInt().coerceIn(0, 100)
                _state.value = _state.value.copy(
                    progressPercent = percent,
                    currentModule = "$percent%"
                )
            },
            onSuccess = {
                if (completeBootstrapOnSuccess) {
                    playPluginManager.setFirstLaunchCompleted()
                }
                _state.value = _state.value.copy(
                    isDownloading = false,
                    isComplete = if (completeBootstrapOnSuccess) true else _state.value.isComplete,
                    progressPercent = 100,
                    currentModule = ""
                )
                onDone()
            },
            onError = { errorCode ->
                _state.value = _state.value.copy(
                    isDownloading = false,
                    errorMessage = "Engine initialization error (code: $errorCode)"
                )
            }
        )
    }

    fun downloadSingleBundle(bundleId: String, onDone: () -> Unit = {}) {
        downloadSelectedBundles(
            bundleIds = listOf(bundleId),
            completeBootstrapOnSuccess = false,
            onDone = onDone
        )
    }

    fun skipFirstLaunch() {
        settingsManager.confirmInitialLanguageSelection()
        playPluginManager.setFirstLaunchCompleted()
        _state.value = _state.value.copy(
            isDownloading = false,
            isComplete = true,
            progressPercent = 100,
            errorMessage = null
        )
    }
}
