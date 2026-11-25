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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.hlebkukleuski.fullfilestudio.data.settings.AppSettingsManager

@Immutable
data class BootstrapState(
    val isComplete: Boolean = false
)

@Stable
class FirstLaunchEngineBootstrapper(
    context: Context,
    val settingsManager: AppSettingsManager = AppSettingsManager(context)
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BootstrapState(isComplete = isFirstLaunchCompleted()))
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    fun isFirstLaunchCompleted(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH_COMPLETE, false)

    fun startMonitoring() {
        if (isFirstLaunchCompleted()) {
            _state.value = _state.value.copy(isComplete = true)
        }
    }

    fun stopMonitoring() {
    }

    fun skipFirstLaunch() {
        settingsManager.confirmInitialLanguageSelection()
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_COMPLETE, true).apply()
        _state.value = _state.value.copy(isComplete = true)
    }

    companion object {
        private const val PREFS_NAME = "fullfilestudio_onboarding_prefs"
        private const val KEY_FIRST_LAUNCH_COMPLETE = "first_launch_complete"
    }
}
