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

package me.hlebkukleuski.fullfilestudio.data.settings

import android.content.Context
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val code: String, val nativeName: String, val flagEmoji: String) {
    EN("en", "English", "🇬🇧"),
    RU("ru", "Русский", "🇷🇺"),
    DE("de", "Deutsch", "🇩🇪"),
    ES("es", "Español", "🇪🇸"),
    FR("fr", "Français", "🇫🇷"),
    PL("pl", "Polski", "🇵🇱"),
    UK("uk", "Українська", "🇺🇦"),
    ZH("zh", "中文", "🇨🇳");

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
        }
    }
}

enum class AppThemeMode(val id: String) {
    DARK("dark"),
    LIGHT("light"),
    AS_IN_PHONE("system");

    companion object {
        fun fromId(id: String?): AppThemeMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DARK
        }
    }
}

enum class AppFrameRateMode(val id: String, val fps: Float) {
    AUTO("auto", 0f),
    FPS_90("90", 90f),
    FPS_60("60", 60f),
    FPS_25("25", 25f);

    companion object {
        fun fromId(id: String?): AppFrameRateMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class AppAudioQualityMode(val id: String, val sampleRateHz: Int, val bitDepth: Int) {
    AUTO("auto", 0, 0),
    STUDIO_96("96_24", 96_000, 24),
    HIGH_48("48_16", 48_000, 16),
    STANDARD_44("44_16", 44_100, 16);

    companion object {
        fun fromId(id: String?): AppAudioQualityMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

@Stable
class AppSettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("fullfilestudio_ui_prefs", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(
        AppLanguage.fromCode(prefs.getString("app_language", AppLanguage.EN.code))
    )
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _themeMode = MutableStateFlow(
        AppThemeMode.fromId(prefs.getString("app_theme_mode", AppThemeMode.DARK.id))
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _frameRateMode = MutableStateFlow(
        AppFrameRateMode.fromId(prefs.getString("app_frame_rate_mode", AppFrameRateMode.AUTO.id))
    )
    val frameRateMode: StateFlow<AppFrameRateMode> = _frameRateMode.asStateFlow()

    private val _audioQualityMode = MutableStateFlow(
        AppAudioQualityMode.fromId(prefs.getString("app_audio_quality_mode", AppAudioQualityMode.AUTO.id))
    )
    val audioQualityMode: StateFlow<AppAudioQualityMode> = _audioQualityMode.asStateFlow()

    private val _isInitialLanguageChosen = MutableStateFlow(
        prefs.getBoolean("initial_language_chosen", false)
    )
    val isInitialLanguageChosen: StateFlow<Boolean> = _isInitialLanguageChosen.asStateFlow()

    fun setLanguage(lang: AppLanguage) {
        prefs.edit().putString("app_language", lang.code).apply()
        _language.value = lang
    }

    fun confirmInitialLanguageSelection() {
        prefs.edit().putBoolean("initial_language_chosen", true).apply()
        _isInitialLanguageChosen.value = true
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("app_theme_mode", mode.id).apply()
        _themeMode.value = mode
    }

    fun setFrameRateMode(mode: AppFrameRateMode) {
        prefs.edit().putString("app_frame_rate_mode", mode.id).apply()
        _frameRateMode.value = mode
    }

    fun setAudioQualityMode(mode: AppAudioQualityMode) {
        prefs.edit().putString("app_audio_quality_mode", mode.id).apply()
        _audioQualityMode.value = mode
    }
}
