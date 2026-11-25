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

package me.hlebkukleuski.fullfilestudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.data.settings.AppSettingsManager
import me.hlebkukleuski.fullfilestudio.data.settings.AppThemeMode
import me.hlebkukleuski.fullfilestudio.ui.i18n.stringsForLanguage

@Immutable
data class AppPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentSecondary: Color,
    val border: Color,
    val errorBadge: Color,
    val warningBadge: Color,
    val infoBadge: Color,
    val canvasBackground: Color
)

private val DarkPalette = AppPalette(
    isDark = true,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E2E),
    surfaceElevated = Color(0xFF181825),
    primaryText = Color(0xFFFFFFFF),
    secondaryText = Color(0xFFBAC2DE),
    accent = Color(0xFF03DAC6),
    accentSecondary = Color(0xFF7C4DFF),
    border = Color(0xFF313244),
    errorBadge = Color(0xFFF38BA8),
    warningBadge = Color(0xFFFAB387),
    infoBadge = Color(0xFF89B4FA),
    canvasBackground = Color(0xFF101014)
)

private val LightPalette = AppPalette(
    isDark = false,
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEEF1F6),
    primaryText = Color(0xFF1A1C24),
    secondaryText = Color(0xFF5A6275),
    accent = Color(0xFF00796B),
    accentSecondary = Color(0xFF5E35B1),
    border = Color(0xFFD8DEE9),
    errorBadge = Color(0xFFD32F2F),
    warningBadge = Color(0xFFEF6C00),
    infoBadge = Color(0xFF1976D2),
    canvasBackground = Color(0xFFE9ECF2)
)

val LocalAppPalette = staticCompositionLocalOf { DarkPalette }
val LocalAppStrings = staticCompositionLocalOf { stringsForLanguage(AppLanguage.EN) }
val LocalAppSettingsManager = staticCompositionLocalOf<AppSettingsManager?> { null }

@Composable
fun FullFileStudioTheme(
    settingsManager: AppSettingsManager,
    content: @Composable () -> Unit
) {
    val lang by settingsManager.language.collectAsState()
    val themeMode by settingsManager.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()

    val effectiveDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.AS_IN_PHONE -> systemDark
    }

    val palette = if (effectiveDark) DarkPalette else LightPalette
    val strings = remember(lang) { stringsForLanguage(lang) }

    val colorScheme = if (effectiveDark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.background,
            surface = palette.surface,
            onPrimary = Color.Black,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.background,
            surface = palette.surface,
            onPrimary = Color.White,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText
        )
    }

    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalAppStrings provides strings,
        LocalAppSettingsManager provides settingsManager
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
