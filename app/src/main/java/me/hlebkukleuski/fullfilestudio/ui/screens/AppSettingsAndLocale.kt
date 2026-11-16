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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.omnireader.core.engine.OmniServerEndpoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val nativeName: String, val flagEmoji: String) {
    EN("en", "English", "🇬🇧"),
    RU("ru", "Русский", "🇷🇺"),
    DE("de", "Deutsch", "🇩🇪"),
    ES("es", "Español", "🇪🇸"),
    FR("fr", "Français", "🇫🇷"),
    PL("pl", "Polski", "🇵🇱"),
    UK("uk", "Суржик", "🇺🇦"),
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
    STUDIO_96("96k", 96000, 24),
    HIGH_48("48k", 48000, 16),
    STANDARD_44("44k", 44100, 16);

    companion object {
        fun fromId(id: String?): AppAudioQualityMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

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

data class AppStrings(
    val appName: String,
    val universalReaderSubtitle: String,
    val openFileSaf: String,
    val selectLanguageTitle: String,
    val selectLanguageSubtitle: String,
    val continueButton: String,
    val settingsMenuTitle: String,
    val languageLabel: String,
    val themeLabel: String,
    val themeDark: String,
    val themeLight: String,
    val themeAsInPhone: String,
    val engineCenterSubtitle: String,
    val builtinFormatsDescription: String,
    val selectOptionalModulesPrompt: String,
    val downloadAllOneClick: String,
    val installSelectedPrefix: String,
    val workLightweightCoreOnly: String,
    val moduleReady: String,
    val mbSuffix: String,
    val playModuleBadge: String,
    val playModuleRequiredTitle: String,
    val playModuleRequiredDesc: String,
    val playModuleOpensFormatsPrefix: String,
    val downloadModuleBtn: String,
    val cancelBtn: String,
    val closeBtn: String,
    val openInHexBtn: String,
    val securityBadge: String,
    val formatErrorBadge: String,
    val closedLicenseBadge: String,
    val passwordRequiredBadge: String,
    val accessRestrictedTitle: String,
    val formatReadErrorTitle: String,
    val closedLicenseTitle: String,
    val passwordRequiredTitle: String,
    val hexFallbackHint: String,
    val archiveContentsTitle: String,
    val backButton: String,
    val archiveEmptyMessage: String,
    val searchButton: String,
    val closeSearchButton: String,
    val searchPlaceholder: String,
    val findButton: String,
    val foundOnPagePrefix: String,
    val notFoundMessage: String,
    val reset3dButton: String,
    val changeFileButton: String,
    val pageLabel: String,
    val preparingMedia: String,
    val playMedia: String,
    val pauseMedia: String,
    val audioLabel: String,
    val legalComplianceTitle: String,
    val serverEndpointStatus: String,
    val closeDrawerButton: String,
    val aboutLegalSectionTitle: String,
    val termsOfUseLink: String,
    val privacyPolicyLink: String,
    val thirdPartyLicensesLink: String = "3. Third-Party Licenses",
    val thirdPartyLicensesDialogTitle: String = "Open Source Licenses & Notices",
    val copyActionButton: String = "📋 Copy",
    val selectAllActionButton: String = "Select All",
    val slideNotesButton: String = "📝 Notes",
    val bufferingCloudFile: String,
    val defaultMediaTitle: String,
    val defaultMediaArtist: String,
    val errorDescriptorAccess: String,
    val errorBufferingStreamPrefix: String,
    val errorSuspiciousHeader: String,
    val errorServiceTimeout: String,
    val errorServiceReconnecting: String,
    val errorCorruptedOrUnsupported: String,
    val errorFileReadPrefix: String,
    val errorArchiveExtractFailed: String,
    val errorNestedFileOpenFailed: String,
    val errorArchiveReadPrefix: String,
    val errorHexOpenFailed: String,
    val errorRenderFailed: String,
    val bundleOfficeName: String,
    val bundleOfficeDesc: String,
    val bundleCadName: String,
    val bundleCadDesc: String,
    val bundleArchivesName: String,
    val bundleArchivesDesc: String,
    val frameRateLabel: String = "Display Refresh Rate Limit",
    val frameRateAutoPattern: String = "The absolute max your phone can pull off (%d Hz)",
    val frameRate90: String = "90 Hz",
    val frameRate60: String = "60 Hz (Power Saver)",
    val frameRate25: String = "25 Hz (Ultra Battery Saver)",
    val audioQualityLabel: String = "Audio Playback Quality",
    val audioQualityAutoPattern: String = "Device Max (%s: %d kHz, %s)",
    val audioQuality96: String = "96 kHz / 24-bit (Studio Quality)",
    val audioQuality48: String = "48 kHz / 16-bit (High Quality)",
    val audioQuality44: String = "44.1 kHz / 16-bit (Standard)",
    val audioDeviceSpeaker: String = "Phone Speaker",
    val audioDeviceWired: String = "Wired Headphones / Aux",
    val audioDeviceBluetooth: String = "Bluetooth Headphones / Speaker",
    val audioDeviceUsbDac: String = "USB DAC / Sound Card",
    val audioDeviceGeneric: String = "Audio Device",
    val retryDownloadButton: String = "Retry Download",
    val openAsInBrowserButton: String = "🌐 Open as in Browser",
    val viewHtmlSourceButton: String = "📄 View Source",
    val checkForUpdatesButton: String = "Check for Updates",
    val checkingUpdatesStatus: String = "Checking for updates...",
    val appUpToDateStatus: String = "✓ Latest version installed",
    val updateAvailableTitle: String = "Update Available",
    val updateNowButton: String = "Update",
    val laterButton: String = "Later",
    val downloadingUpdateStatus: String = "Downloading update..."
) {
    fun localizedBundleName(bundleId: String, defaultName: String): String = when (bundleId) {
        "office" -> bundleOfficeName
        "cadvector" -> bundleCadName
        "archives" -> bundleArchivesName
        else -> defaultName
    }

    fun localizedBundleDesc(bundleId: String, defaultDesc: String): String = when (bundleId) {
        "office" -> bundleOfficeDesc
        "cadvector" -> bundleCadDesc
        "archives" -> bundleArchivesDesc
        else -> defaultDesc
    }

    fun formatPlayModulePrompt(bundleId: String, defaultDesc: String): String {
        val formatsLine = localizedBundleDesc(bundleId, defaultDesc)
        return "$playModuleRequiredDesc\n    $playModuleOpensFormatsPrefix $formatsLine"
    }

    fun localizedFrameRateAuto(maxHz: Int): String = when (frameRateAutoPattern) {
        "Максимум что может твой телефон (%d Гц)" -> "Максимум что может твой телефон ($maxHz Гц)"
        else -> String.format(Locale.US, frameRateAutoPattern, maxHz)
    }

    fun localizedAudioQualityAuto(activeDevice: ActiveAudioDeviceCapabilities): String {
        val devName = activeDevice.getLocalizedDeviceName(this)
        val fmtDesc = activeDevice.getLocalizedFormatDescription()
        val rateKhz = activeDevice.maxSampleRateHz / 1000
        return try {
            if (audioQualityAutoPattern.contains("%s") && audioQualityAutoPattern.contains("%d")) {
                String.format(Locale.US, audioQualityAutoPattern, devName, rateKhz, fmtDesc)
            } else if (audioQualityAutoPattern.contains("%s")) {
                String.format(Locale.US, audioQualityAutoPattern, devName)
            } else {
                "$audioQualityLabel ($devName: $rateKhz kHz)"
            }
        } catch (_: Exception) {
            "$devName ($rateKhz kHz, $fmtDesc)"
        }
    }
}

fun stringsForLanguage(lang: AppLanguage): AppStrings = when (lang) {
    AppLanguage.EN -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Universal Secure File Reader",
        openFileSaf = "Open File",
        selectLanguageTitle = "Select Application Language",
        selectLanguageSubtitle = "You can change the language and theme anytime in the side drawer.",
        continueButton = "Continue",
        settingsMenuTitle = "Settings & Appearance",
        languageLabel = "Interface Language",
        themeLabel = "Appearance Theme",
        themeDark = "Dark",
        themeLight = "Light",
        themeAsInPhone = "As in phone",
        engineCenterSubtitle = "Supported Formats & Optional Modules",
        builtinFormatsDescription = "Built-in formats: PDF, DjVu, Fonts (TTF, OTF, TTC, WOFF, WOFF2), Images (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), Text & Code (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), Databases (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), Previews (MAX, RVT, INDD), Audio & Video (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) & HEX.",
        selectOptionalModulesPrompt = "Select optional Google Play modules:",
        downloadAllOneClick = "⚡ Download everything in one click",
        installSelectedPrefix = "Install Selected",
        workLightweightCoreOnly = "Keep it light & fast (Core only)",
        moduleReady = "✓ Ready",
        mbSuffix = "MB",
        playModuleBadge = "📦 GOOGLE PLAY MODULE",
        playModuleRequiredTitle = "Module Required",
        playModuleRequiredDesc = "To read this file, we need to download a module for these file types from Google Play servers. Here is what it will give you:",
        playModuleOpensFormatsPrefix = "Opening these formats:",
        downloadModuleBtn = "Download Module",
        cancelBtn = "Cancel",
        closeBtn = "Close",
        openInHexBtn = "Open in HEX",
        securityBadge = "🔒 SECURITY & PERMISSIONS",
        formatErrorBadge = "🛠 FILE STRUCTURE DIAGNOSTICS",
        closedLicenseBadge = "⚖️ PROPRIETARY LICENSE RESTRICTION",
        passwordRequiredBadge = "🔑 PASSWORD REQUIRED",
        accessRestrictedTitle = "Access or Security Restriction",
        formatReadErrorTitle = "Unable to Parse File Structure",
        closedLicenseTitle = "Format Covered by Closed License",
        passwordRequiredTitle = "Document Is Encrypted",
        hexFallbackHint = "You can safely inspect the raw bytes of this file in the built-in engineering HEX inspector.",
        archiveContentsTitle = "Archive / Disk Image Contents",
        backButton = "← Back",
        archiveEmptyMessage = "Archive is empty or structure is corrupted",
        searchButton = "🔍 Search",
        closeSearchButton = "✕ Close",
        searchPlaceholder = "Text or HEX (50 4B)...",
        findButton = "Find",
        foundOnPagePrefix = "Found: page",
        notFoundMessage = "No matches found",
        reset3dButton = "Reset 3D",
        changeFileButton = "Change File",
        pageLabel = "Page",
        preparingMedia = "Preparing...",
        playMedia = "▶ Play",
        pauseMedia = "❚❚ Pause",
        audioLabel = "🎵 Audio Stream",
        legalComplianceTitle = "About / Legal Information",
        serverEndpointStatus = "Policy & Sync Server Endpoint Configured",
        closeDrawerButton = "Close Drawer",
        aboutLegalSectionTitle = "About / Legal Information",
        termsOfUseLink = "1. Terms of Use",
        privacyPolicyLink = "2. Privacy Policy",
        thirdPartyLicensesLink = "3. Third-Party Licenses",
        thirdPartyLicensesDialogTitle = "Open Source Licenses & Notices",
        copyActionButton = "📋 Copy",
        selectAllActionButton = "Select All",
        slideNotesButton = "📝 Notes",
        bufferingCloudFile = "Buffering file from cloud...",
        defaultMediaTitle = "Audio / Video Stream",
        defaultMediaArtist = "Unknown Artist",
        errorDescriptorAccess = "Unable to obtain file descriptor via system provider (missing access permissions).",
        errorBufferingStreamPrefix = "Stream buffering error",
        errorSuspiciousHeader = "Suspicious or malformed file header structure blocked.",
        errorServiceTimeout = "Timed out waiting for isolated rendering service connection.",
        errorServiceReconnecting = "Connection to isolated rendering service lost. Reconnecting...",
        errorCorruptedOrUnsupported = "File is corrupted, encrypted, or its format is unsupported.",
        errorFileReadPrefix = "File read error",
        errorArchiveExtractFailed = "Unable to safely extract entry from archive",
        errorNestedFileOpenFailed = "Unable to open nested archive file",
        errorArchiveReadPrefix = "Archive read error",
        errorHexOpenFailed = "Unable to open file in HEX inspector mode.",
        errorRenderFailed = "Document rendering failed.",
        bundleOfficeName = "Office Documents, Spreadsheets, Presentations & E-Books",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Vector Graphics & 3D CAD Models",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Archives, Packages & Disk Images",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Retry Download",
        openAsInBrowserButton = "🌐 Open as in Browser",
        viewHtmlSourceButton = "📄 View Source",
        checkForUpdatesButton = "Check for Updates",
        checkingUpdatesStatus = "Checking for updates...",
        appUpToDateStatus = "✓ Latest version installed",
        updateAvailableTitle = "Update Available",
        updateNowButton = "Update",
        laterButton = "Later",
        downloadingUpdateStatus = "Downloading update..."
    )
    AppLanguage.RU -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Универсальный безопасный ридер файлов",
        openFileSaf = "Открыть файл",
        selectLanguageTitle = "Выберите язык приложения",
        selectLanguageSubtitle = "Вы сможете изменить язык и тему в любой момент в боковом меню.",
        continueButton = "Продолжить",
        settingsMenuTitle = "Настройки и внешний вид",
        languageLabel = "Язык интерфейса",
        themeLabel = "Тема оформления",
        themeDark = "Тёмная",
        themeLight = "Светлая",
        themeAsInPhone = "Как в телефоне",
        engineCenterSubtitle = "Поддерживаемые форматы и дополнительные модули",
        builtinFormatsDescription = "Уже встроены: PDF, DjVu, шрифты (TTF, OTF, TTC, WOFF, WOFF2), изображения (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), текст и код (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), базы данных (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), превью (MAX, RVT, INDD), аудио и видео (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) и HEX.",
        selectOptionalModulesPrompt = "Выберите дополнительные модули Google Play:",
        downloadAllOneClick = "⚡ Скачать всё сразу одним кликом",
        installSelectedPrefix = "Установить выбранные",
        workLightweightCoreOnly = "Работать налегке (Только базовые)",
        moduleReady = "✓ Готов",
        mbSuffix = "МБ",
        playModuleBadge = "📦 МОДУЛЬ GOOGLE PLAY",
        playModuleRequiredTitle = "Требуется модуль",
        playModuleRequiredDesc = "Чтобы прочитать этот файл, нам нужно скачать модуль для таких типов файлов с серверов Google Play. Вот какие возможности это даст:",
        playModuleOpensFormatsPrefix = "Открытие этих форматов:",
        downloadModuleBtn = "Загрузить модуль",
        cancelBtn = "Отмена",
        closeBtn = "Закрыть",
        openInHexBtn = "Открыть в HEX",
        securityBadge = "🔒 ЗАЩИТА И ПРАВА ДОСТУПА",
        formatErrorBadge = "🛠 ДИАГНОСТИКА СТРУКТУРЫ ФАЙЛА",
        closedLicenseBadge = "⚖️ ОГРАНИЧЕНИЕ ЗАКРЫТОЙ ЛИЦЕНЗИИ",
        passwordRequiredBadge = "🔑 ТРЕБУЕТСЯ ПАРОЛЬ ДОСТУПА",
        accessRestrictedTitle = "Ограничение доступа или безопасности",
        formatReadErrorTitle = "Не удалось разобрать структуру файла",
        closedLicenseTitle = "Формат защищён закрытой лицензией",
        passwordRequiredTitle = "Документ защищён шифрованием",
        hexFallbackHint = "Вы можете безопасно исследовать сырые байты этого файла во встроенном инженерном HEX-инспекторе.",
        archiveContentsTitle = "Содержимое архива / образа",
        backButton = "← Назад",
        archiveEmptyMessage = "В архиве нет файлов или структура повреждена",
        searchButton = "🔍 Поиск",
        closeSearchButton = "✕ Закрыть",
        searchPlaceholder = "Текст или HEX (50 4B)...",
        findButton = "Найти",
        foundOnPagePrefix = "Найдено: стр.",
        notFoundMessage = "Совпадений не найдено",
        reset3dButton = "Сброс 3D",
        changeFileButton = "Сменить файл",
        pageLabel = "Стр.",
        preparingMedia = "Подготовка...",
        playMedia = "▶ Воспроизвести",
        pauseMedia = "❚❚ Пауза",
        audioLabel = "🎵 Аудиопоток",
        legalComplianceTitle = "О приложении / Правовая информация",
        serverEndpointStatus = "Сервер синхронизации и политик подключён",
        closeDrawerButton = "Закрыть панель",
        aboutLegalSectionTitle = "О приложении / Правовая информация",
        frameRateLabel = "Лимит частоты кадров экрана",
        frameRateAutoPattern = "Максимум что может твой телефон (%d Гц)",
        frameRate60 = "60 Гц (Экономия батареи)",
        frameRate25 = "25 Гц (Супер-экономия / Чтение)",
        audioQualityLabel = "Качество воспроизведения аудио",
        audioQualityAutoPattern = "Максимум устройства (%s: %d кГц, %s)",
        audioQuality96 = "96 кГц / 24 бита (Студийное качество)",
        audioQuality48 = "48 кГц / 16 бит (Высокое качество)",
        audioQuality44 = "44.1 кГц / 16 бит (Стандартное качество)",
        audioDeviceSpeaker = "Динамик телефона",
        audioDeviceWired = "Проводные наушники / Aux",
        audioDeviceBluetooth = "Bluetooth-наушники / Колонка",
        audioDeviceUsbDac = "USB ЦАП / Внешняя карта",
        audioDeviceGeneric = "Аудиоустройство",
        termsOfUseLink = "1. Условия использования (Terms of Use)",
        privacyPolicyLink = "2. Политика конфиденциальности (Privacy Policy)",
        thirdPartyLicensesLink = "3. Сторонние компоненты и лицензии",
        thirdPartyLicensesDialogTitle = "Сторонние компоненты и лицензии",
        copyActionButton = "📋 Копировать",
        selectAllActionButton = "Выделить всё",
        slideNotesButton = "📝 Заметки",
        bufferingCloudFile = "Буферизация файла из облака...",
        defaultMediaTitle = "Аудио / Видео файл",
        defaultMediaArtist = "Неизвестный исполнитель",
        errorDescriptorAccess = "Не удалось получить дескриптор файла через системный провайдер (отсутствуют права доступа).",
        errorBufferingStreamPrefix = "Ошибка буферизации потока",
        errorSuspiciousHeader = "Подозрительная структура заголовка файла заблокирована.",
        errorServiceTimeout = "Превышено время ожидания соединения с изолированным сервисом рендеринга.",
        errorServiceReconnecting = "Потеряно соединение с изолированным процессом рендеринга. Выполняется восстановление...",
        errorCorruptedOrUnsupported = "Файл повреждён, зашифрован или его формат не поддерживается.",
        errorFileReadPrefix = "Ошибка чтения файла",
        errorArchiveExtractFailed = "Не удалось безопасно извлечь запись из архива",
        errorNestedFileOpenFailed = "Не удалось открыть вложенный файл из архива",
        errorArchiveReadPrefix = "Ошибка чтения архива",
        errorHexOpenFailed = "Не удалось открыть файл в режиме HEX-инспектора.",
        errorRenderFailed = "Сбой рендеринга документа.",
        bundleOfficeName = "Офисные документы, таблицы, презентации и книги",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Векторная графика и 3D CAD-модели",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Архивы, пакеты и образы дисков",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Повторить загрузку",
        openAsInBrowserButton = "🌐 Открыть как в браузере",
        viewHtmlSourceButton = "📄 Исходный код",
        checkForUpdatesButton = "Проверить обновления",
        checkingUpdatesStatus = "Проверка обновлений...",
        appUpToDateStatus = "✓ Установлена последняя версия",
        updateAvailableTitle = "Доступно обновление",
        updateNowButton = "Обновить",
        laterButton = "Позже",
        downloadingUpdateStatus = "Загрузка обновления..."
    )
    AppLanguage.DE -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Universeller sicherer Dateileser",
        openFileSaf = "Datei öffnen",
        selectLanguageTitle = "App-Sprache auswählen",
        selectLanguageSubtitle = "Sie können Sprache und Design jederzeit im Seitenmenü ändern.",
        continueButton = "Weiter",
        settingsMenuTitle = "Einstellungen & Darstellung",
        languageLabel = "Oberflächensprache",
        themeLabel = "Farbdesign",
        themeDark = "Dunkel",
        themeLight = "Hell",
        themeAsInPhone = "Wie im Telefon",
        engineCenterSubtitle = "Unterstützte Formate & Zusatzmodule",
        builtinFormatsDescription = "Bereits integriert: PDF, DjVu, Schriften (TTF, OTF, TTC, WOFF, WOFF2), Bilder (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), Text & Code (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), Datenbanken (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), Vorschau (MAX, RVT, INDD), Audio & Video (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) & HEX.",
        selectOptionalModulesPrompt = "Optionale Google Play-Module auswählen:",
        downloadAllOneClick = "⚡ Alle mit einem Klick laden",
        installSelectedPrefix = "Ausgewählte installieren",
        workLightweightCoreOnly = "Leicht & schnell starten (Nur Basis)",
        moduleReady = "✓ Bereit",
        mbSuffix = "MB",
        playModuleBadge = "📦 GOOGLE PLAY-MODUL",
        playModuleRequiredTitle = "Modul erforderlich",
        playModuleRequiredDesc = "Um diese Datei zu lesen, müssen wir ein Modul für solche Dateitypen von den Google Play-Servern herunterladen. Das bietet Ihnen folgende Möglichkeiten:",
        playModuleOpensFormatsPrefix = "Öffnen dieser Formate:",
        downloadModuleBtn = "Modul laden",
        cancelBtn = "Abbrechen",
        closeBtn = "Schließen",
        openInHexBtn = "In HEX öffnen",
        securityBadge = "🔒 SICHERHEIT & ZUGRIFF",
        formatErrorBadge = "🛠 DATEISTRUKTUR-DIAGNOSE",
        closedLicenseBadge = "⚖️ LIZENZEINSCHRÄNKUNG",
        passwordRequiredBadge = "🔑 PASSWORT ERFORDERLICH",
        accessRestrictedTitle = "Zugriffs- oder Sicherheitseinschränkung",
        formatReadErrorTitle = "Dateistruktur konnte nicht gelesen werden",
        closedLicenseTitle = "Format durch geschlossene Lizenz geschützt",
        passwordRequiredTitle = "Dokument ist verschlüsselt",
        hexFallbackHint = "Sie können die Rohbytes dieser Datei sicher im HEX-Inspektor analysieren.",
        archiveContentsTitle = "Archiv- / Abbildinhalt",
        backButton = "← Zurück",
        archiveEmptyMessage = "Archiv ist leer oder beschädigt",
        searchButton = "🔍 Suche",
        closeSearchButton = "✕ Schließen",
        searchPlaceholder = "Text oder HEX (50 4B)...",
        findButton = "Suchen",
        foundOnPagePrefix = "Gefunden: Seite",
        notFoundMessage = "Keine Treffer",
        reset3dButton = "3D zurücksetzen",
        changeFileButton = "Datei wechseln",
        pageLabel = "S.",
        preparingMedia = "Vorbereitung...",
        playMedia = "▶ Abspielen",
        pauseMedia = "❚❚ Pause",
        audioLabel = "🎵 Audiostream",
        legalComplianceTitle = "Über die App / Rechtliche Hinweise",
        serverEndpointStatus = "Server-Endpunkte bereit",
        closeDrawerButton = "Menü schließen",
        aboutLegalSectionTitle = "Über die App / Rechtliche Hinweise",
        frameRateLabel = "Bildwiederholfrequenz-Limit",
        frameRateAutoPattern = "Das absolute Maximum, das dein Handy schafft (%d Hz)",
        frameRate60 = "60 Hz (Energiesparmodus)",
        frameRate25 = "25 Hz (Super-Energiesparmodus / Lesen)",
        audioQualityLabel = "Wiedergabequalität (Audio)",
        audioQualityAutoPattern = "Geräte-Maximum (%s: %d kHz, %s)",
        audioQuality96 = "96 kHz / 24-Bit (Studio-Qualität)",
        audioQuality48 = "48 kHz / 16-Bit (Hohe Qualität)",
        audioQuality44 = "44,1 kHz / 16-Bit (Standard-Qualität)",
        audioDeviceSpeaker = "Smartphone-Lautsprecher",
        audioDeviceWired = "Kabel-Kopfhörer / Aux",
        audioDeviceBluetooth = "Bluetooth-Kopfhörer / Lautsprecher",
        audioDeviceUsbDac = "USB-DAC / Externe Soundkarte",
        audioDeviceGeneric = "Audiogerät",
        termsOfUseLink = "1. Nutzungsbedingungen (Terms of Use)",
        privacyPolicyLink = "2. Datenschutzerklärung (Privacy Policy)",
        thirdPartyLicensesLink = "3. Drittanbieter-Lizenzen",
        thirdPartyLicensesDialogTitle = "Open-Source-Lizenzen & Hinweise",
        copyActionButton = "📋 Kopieren",
        selectAllActionButton = "Alles auswählen",
        slideNotesButton = "📝 Notizen",
        bufferingCloudFile = "Datei aus der Cloud wird gepuffert...",
        defaultMediaTitle = "Audio- / Videostream",
        defaultMediaArtist = "Unbekannter Künstler",
        errorDescriptorAccess = "Dateideskriptor konnte nicht über den Systemanbieter abgerufen werden (fehlende Zugriffsrechte).",
        errorBufferingStreamPrefix = "Fehler beim Puffern des Datenstroms",
        errorSuspiciousHeader = "Verdächtige oder beschädigte Dateikopfstruktur wurde blockiert.",
        errorServiceTimeout = "Zeitüberschreitung beim Verbinden mit dem isolierten Rendering-Dienst.",
        errorServiceReconnecting = "Verbindung zum isolierten Rendering-Dienst verloren. Wiederherstellung läuft...",
        errorCorruptedOrUnsupported = "Die Datei ist beschädigt, verschlüsselt oder das Format wird nicht unterstützt.",
        errorFileReadPrefix = "Dateilesefehler",
        errorArchiveExtractFailed = "Eintrag konnte nicht sicher aus dem Archiv extrahiert werden",
        errorNestedFileOpenFailed = "Verschachtelte Datei im Archiv konnte nicht geöffnet werden",
        errorArchiveReadPrefix = "Archivlesefehler",
        errorHexOpenFailed = "Datei konnte nicht im HEX-Inspektor geöffnet werden.",
        errorRenderFailed = "Fehler beim Rendern des Dokuments.",
        bundleOfficeName = "Office-Dokumente, Tabellen, Präsentationen & E-Books",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Vektorgrafik & 3D-CAD-Modelle",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Archive, Pakete & Datenträgerabbilder",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Erneut versuchen",
        openAsInBrowserButton = "🌐 Wie im Browser öffnen",
        viewHtmlSourceButton = "📄 Quellcode anzeigen",
        checkForUpdatesButton = "Nach Updates suchen",
        checkingUpdatesStatus = "Suche nach Updates...",
        appUpToDateStatus = "✓ Aktuelle Version installiert",
        updateAvailableTitle = "Update verfügbar",
        updateNowButton = "Aktualisieren",
        laterButton = "Später",
        downloadingUpdateStatus = "Update wird heruntergeladen..."
    )
    AppLanguage.ES -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Lector de archivos universal y seguro",
        openFileSaf = "Abrir archivo",
        selectLanguageTitle = "Seleccione el idioma",
        selectLanguageSubtitle = "Puede cambiar el idioma y el tema en cualquier momento en el panel lateral.",
        continueButton = "Continuar",
        settingsMenuTitle = "Ajustes y apariencia",
        languageLabel = "Idioma de la interfaz",
        themeLabel = "Tema visual",
        themeDark = "Oscuro",
        themeLight = "Claro",
        themeAsInPhone = "Como en el teléfono",
        engineCenterSubtitle = "Formatos compatibles y módulos adicionales",
        builtinFormatsDescription = "Ya incluidos: PDF, DjVu, fuentes (TTF, OTF, TTC, WOFF, WOFF2), imágenes (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), texto y código (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), bases de datos (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), vistas previas (MAX, RVT, INDD), audio y vídeo (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) y HEX.",
        selectOptionalModulesPrompt = "Seleccione módulos opcionales de Google Play:",
        downloadAllOneClick = "⚡ Descargar todo en un clic",
        installSelectedPrefix = "Instalar seleccionados",
        workLightweightCoreOnly = "Modo ligero y rápido (Solo básicos)",
        moduleReady = "✓ Listo",
        mbSuffix = "MB",
        playModuleBadge = "📦 MÓDULO DE GOOGLE PLAY",
        playModuleRequiredTitle = "Se requiere módulo",
        playModuleRequiredDesc = "Para leer este archivo, necesitamos descargar un módulo para este tipo de archivos desde los servidores de Google Play. Esto es lo que te permitirá hacer:",
        playModuleOpensFormatsPrefix = "Abrir estos formatos:",
        downloadModuleBtn = "Descargar módulo",
        cancelBtn = "Cancelar",
        closeBtn = "Cerrar",
        openInHexBtn = "Abrir en HEX",
        securityBadge = "🔒 SEGURIDAD Y PERMISOS",
        formatErrorBadge = "🛠 DIAGNÓSTICO DE ARCHIVO",
        closedLicenseBadge = "⚖️ LICENCIA PROPIETARIA CERRADA",
        passwordRequiredBadge = "🔑 CONTRASEÑA REQUERIDA",
        accessRestrictedTitle = "Restricción de acceso o seguridad",
        formatReadErrorTitle = "Error al leer la estructura del archivo",
        closedLicenseTitle = "Formato restringido por licencia cerrada",
        passwordRequiredTitle = "Documento cifrado con contraseña",
        hexFallbackHint = "Puede inspeccionar los bytes sin procesar de forma segura en el inspector HEX.",
        archiveContentsTitle = "Contenido del archivo / ISO",
        backButton = "← Atrás",
        archiveEmptyMessage = "El archivo está vacío o dañado",
        searchButton = "🔍 Buscar",
        closeSearchButton = "✕ Cerrar",
        searchPlaceholder = "Texto o HEX (50 4B)...",
        findButton = "Buscar",
        foundOnPagePrefix = "Encontrado: pág.",
        notFoundMessage = "Sin resultados",
        reset3dButton = "Reiniciar 3D",
        changeFileButton = "Cambiar archivo",
        pageLabel = "Pág.",
        preparingMedia = "Preparando...",
        playMedia = "▶ Reproducir",
        pauseMedia = "❚❚ Pausa",
        audioLabel = "🎵 Audio",
        legalComplianceTitle = "Acerca de la aplicación / Información legal",
        serverEndpointStatus = "Endpoints del servidor configurados",
        closeDrawerButton = "Cerrar panel",
        aboutLegalSectionTitle = "Acerca de la aplicación / Información legal",
        frameRateLabel = "Límite de frecuencia de actualización",
        frameRateAutoPattern = "Lo máximo que aguanta tu teléfono (%d Hz)",
        frameRate60 = "60 Hz (Ahorro de batería)",
        frameRate25 = "25 Hz (Súper ahorro / Lectura)",
        audioQualityLabel = "Calidad de reproducción de audio",
        audioQualityAutoPattern = "Máximo del dispositivo (%s: %d kHz, %s)",
        audioQuality96 = "96 kHz / 24 bits (Calidad de estudio)",
        audioQuality48 = "48 kHz / 16 bits (Alta calidad)",
        audioQuality44 = "44,1 kHz / 16 bits (Estándar)",
        audioDeviceSpeaker = "Altavoz del teléfono",
        audioDeviceWired = "Auriculares con cable / Aux",
        audioDeviceBluetooth = "Auriculares Bluetooth / Altavoz",
        audioDeviceUsbDac = "DAC USB / Tarjeta externa",
        audioDeviceGeneric = "Dispositivo de audio",
        termsOfUseLink = "1. Condiciones de uso (Terms of Use)",
        privacyPolicyLink = "2. Política de privacidad (Privacy Policy)",
        thirdPartyLicensesLink = "3. Licencias de terceros",
        thirdPartyLicensesDialogTitle = "Licencias y avisos de código abierto",
        copyActionButton = "📋 Copiar",
        selectAllActionButton = "Seleccionar todo",
        slideNotesButton = "📝 Notas",
        bufferingCloudFile = "Almacenando en búfer el archivo desde la nube...",
        defaultMediaTitle = "Archivo de audio / vídeo",
        defaultMediaArtist = "Artista desconocido",
        errorDescriptorAccess = "No se pudo obtener el descriptor del archivo del proveedor del sistema (faltan permisos de acceso).",
        errorBufferingStreamPrefix = "Error al almacenar el flujo en búfer",
        errorSuspiciousHeader = "Se bloqueó una estructura de encabezado de archivo sospechosa.",
        errorServiceTimeout = "Se agotó el tiempo de espera para conectar con el servicio de renderizado aislado.",
        errorServiceReconnecting = "Se perdió la conexión con el servicio de renderizado aislado. Reconectando...",
        errorCorruptedOrUnsupported = "El archivo está dañado, cifrado o su formato no es compatible.",
        errorFileReadPrefix = "Error de lectura del archivo",
        errorArchiveExtractFailed = "No se pudo extraer de forma segura el elemento del archivo comprimido",
        errorNestedFileOpenFailed = "No se pudo abrir el archivo anidado",
        errorArchiveReadPrefix = "Error al leer el archivo comprimido",
        errorHexOpenFailed = "No se pudo abrir el archivo en el modo de inspector HEX.",
        errorRenderFailed = "Error al renderizar el documento.",
        bundleOfficeName = "Documentos de oficina, hojas de cálculo, presentaciones y libros",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Gráficos vectoriales y modelos CAD 3D",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Archivos comprimidos, paquetes e imágenes de disco",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Reintentar descarga",
        openAsInBrowserButton = "🌐 Abrir como en navegador",
        viewHtmlSourceButton = "📄 Ver código fuente",
        checkForUpdatesButton = "Buscar actualizaciones",
        checkingUpdatesStatus = "Buscando actualizaciones...",
        appUpToDateStatus = "✓ Última versión instalada",
        updateAvailableTitle = "Actualización disponible",
        updateNowButton = "Actualizar",
        laterButton = "Más tarde",
        downloadingUpdateStatus = "Descargando actualización..."
    )
    AppLanguage.FR -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Lecteur de fichiers universel et sécurisé",
        openFileSaf = "Ouvrir un fichier",
        selectLanguageTitle = "Choisir la langue",
        selectLanguageSubtitle = "Vous pouvez modifier la langue et le thème à tout moment dans le menu latéral.",
        continueButton = "Continuer",
        settingsMenuTitle = "Paramètres et apparence",
        languageLabel = "Langue de l'interface",
        themeLabel = "Thème d'affichage",
        themeDark = "Sombre",
        themeLight = "Clair",
        themeAsInPhone = "Comme sur le téléphone",
        engineCenterSubtitle = "Formats pris en charge et modules supplémentaires",
        builtinFormatsDescription = "Formats intégrés : PDF, DjVu, polices (TTF, OTF, TTC, WOFF, WOFF2), images (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), texte et code (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), bases de données (SQLite : DB, SQLITE, SQLITE3 ; Access : ACCDB, MDB ; dBase : DBF ; Parquet, DuckDB, Realm, BSON, JSONL), aperçus (MAX, RVT, INDD), audio et vidéo (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) et HEX.",
        selectOptionalModulesPrompt = "Sélectionnez les modules Google Play optionnels :",
        downloadAllOneClick = "⚡ Tout télécharger en un clic",
        installSelectedPrefix = "Installer la sélection",
        workLightweightCoreOnly = "Rester léger & rapide (Base uniquement)",
        moduleReady = "✓ Prêt",
        mbSuffix = "Mo",
        playModuleBadge = "📦 MODULE GOOGLE PLAY",
        playModuleRequiredTitle = "Module requis",
        playModuleRequiredDesc = "Pour lire ce fichier, nous devons télécharger un module pour ce type de fichiers depuis les serveurs de Google Play. Voici ce que cela permettra :",
        playModuleOpensFormatsPrefix = "Ouverture de ces formats :",
        downloadModuleBtn = "Télécharger",
        cancelBtn = "Annuler",
        closeBtn = "Fermer",
        openInHexBtn = "Ouvrir en HEX",
        securityBadge = "🔒 SÉCURITÉ ET ACCÈS",
        formatErrorBadge = "🛠 DIAGNOSTIC DU FICHIER",
        closedLicenseBadge = "⚖️ LICENCE FERMÉE",
        passwordRequiredBadge = "🔑 MOT DE PASSE REQUIS",
        accessRestrictedTitle = "Restriction d'accès ou de sécurité",
        formatReadErrorTitle = "Impossible de lire la structure du fichier",
        closedLicenseTitle = "Format protégé par une licence fermée",
        passwordRequiredTitle = "Document chiffré",
        hexFallbackHint = "Vous pouvez inspecter les octets bruts en toute sécurité dans l'inspecteur HEX.",
        archiveContentsTitle = "Contenu de l'archive / ISO",
        backButton = "← Retour",
        archiveEmptyMessage = "Archive vide ou structure corrompue",
        searchButton = "🔍 Rechercher",
        closeSearchButton = "✕ Fermer",
        searchPlaceholder = "Texte ou HEX (50 4B)...",
        findButton = "Trouver",
        foundOnPagePrefix = "Trouvé : p.",
        notFoundMessage = "Aucun résultat",
        reset3dButton = "Réinit. 3D",
        changeFileButton = "Changer de fichier",
        pageLabel = "P.",
        preparingMedia = "Préparation...",
        playMedia = "▶ Lecture",
        pauseMedia = "❚❚ Pause",
        audioLabel = "🎵 Flux Audio",
        legalComplianceTitle = "À propos / Informations légales",
        serverEndpointStatus = "Serveur synchronisé",
        closeDrawerButton = "Fermer le panneau",
        aboutLegalSectionTitle = "À propos / Informations légales",
        frameRateLabel = "Limite de taux de rafraîchissement",
        frameRateAutoPattern = "Le maximum absolu que ton téléphone peut donner (%d Hz)",
        frameRate60 = "60 Hz (Économie d'énergie)",
        frameRate25 = "25 Hz (Super économie / Lecture)",
        audioQualityLabel = "Qualité de lecture audio",
        audioQualityAutoPattern = "Maximum de l'appareil (%s : %d kHz, %s)",
        audioQuality96 = "96 kHz / 24 bits (Qualité studio)",
        audioQuality48 = "48 kHz / 16 bits (Haute qualité)",
        audioQuality44 = "44,1 kHz / 16 bits (Qualité standard)",
        audioDeviceSpeaker = "Haut-parleur du téléphone",
        audioDeviceWired = "Casque filaire / Aux",
        audioDeviceBluetooth = "Casque Bluetooth / Enceinte",
        audioDeviceUsbDac = "DAC USB / Carte externe",
        audioDeviceGeneric = "Périphérique audio",
        termsOfUseLink = "1. Conditions d'utilisation (Terms of Use)",
        privacyPolicyLink = "2. Politique de confidentialité (Privacy Policy)",
        thirdPartyLicensesLink = "3. Licences tierces",
        thirdPartyLicensesDialogTitle = "Licences et avis Open Source",
        copyActionButton = "📋 Copier",
        selectAllActionButton = "Tout sélectionner",
        slideNotesButton = "📝 Notes",
        bufferingCloudFile = "Mise en mémoire tampon du fichier depuis le cloud...",
        defaultMediaTitle = "Fichier audio / vidéo",
        defaultMediaArtist = "Artiste inconnu",
        errorDescriptorAccess = "Impossible d'obtenir le descripteur de fichier via le fournisseur système (permissions manquantes).",
        errorBufferingStreamPrefix = "Erreur de mise en mémoire tampon du flux",
        errorSuspiciousHeader = "Structure d'en-tête de fichier suspecte bloquée.",
        errorServiceTimeout = "Délai d'attente dépassé lors de la connexion au service de rendu isolé.",
        errorServiceReconnecting = "Connexion au service de rendu isolé perdue. Reconnexion...",
        errorCorruptedOrUnsupported = "Le fichier est corrompu, chiffré ou son format n'est pas pris en charge.",
        errorFileReadPrefix = "Erreur de lecture du fichier",
        errorArchiveExtractFailed = "Impossible d'extraire l'élément de l'archive en toute sécurité",
        errorNestedFileOpenFailed = "Impossible d'ouvrir le fichier imbriqué",
        errorArchiveReadPrefix = "Erreur de lecture de l'archive",
        errorHexOpenFailed = "Impossible d'ouvrir le fichier dans l'inspecteur HEX.",
        errorRenderFailed = "Échec du rendu du document.",
        bundleOfficeName = "Documents bureautiques, tableurs, présentations et livres",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Graphiques vectoriels et modèles CAO 3D",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Archives, paquets et images disque",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Réessayer le téléchargement",
        openAsInBrowserButton = "🌐 Ouvrir comme dans un navigateur",
        viewHtmlSourceButton = "📄 Voir le code source",
        checkForUpdatesButton = "Vérifier les mises à jour",
        checkingUpdatesStatus = "Recherche de mises à jour...",
        appUpToDateStatus = "✓ Dernière version installée",
        updateAvailableTitle = "Mise à jour disponible",
        updateNowButton = "Mettre à jour",
        laterButton = "Plus tard",
        downloadingUpdateStatus = "Téléchargement de la mise à jour..."
    )
    AppLanguage.PL -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Uniwersalny bezpieczny czytnik plików",
        openFileSaf = "Otwórz plik",
        selectLanguageTitle = "Wybierz język aplikacji",
        selectLanguageSubtitle = "Język i motyw możesz zmienić w każdej chwili w wysuwanym panelu.",
        continueButton = "Kontynuuj",
        settingsMenuTitle = "Ustawienia i wygląd",
        languageLabel = "Język interfejsu",
        themeLabel = "Motyw aplikacji",
        themeDark = "Ciemny",
        themeLight = "Jasny",
        themeAsInPhone = "Jak w telefonie",
        engineCenterSubtitle = "Obsługiwane formaty i moduły dodatkowe",
        builtinFormatsDescription = "Wbudowane formaty: PDF, DjVu, czcionki (TTF, OTF, TTC, WOFF, WOFF2), obrazy (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), tekst i kod (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), bazy danych (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), podgląd (MAX, RVT, INDD), audio i wideo (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) oraz HEX.",
        selectOptionalModulesPrompt = "Wybierz dodatkowe moduły Google Play:",
        downloadAllOneClick = "⚡ Pobierz wszystko jednym kliknięciem",
        installSelectedPrefix = "Zainstaluj wybrane",
        workLightweightCoreOnly = "Pracuj lekko i szybko (Tylko podstawy)",
        moduleReady = "✓ Gotowy",
        mbSuffix = "MB",
        playModuleBadge = "📦 MODUŁ GOOGLE PLAY",
        playModuleRequiredTitle = "Wymagany moduł",
        playModuleRequiredDesc = "Aby odczytać ten plik, musimy pobrać moduł dla takich typów plików z serwerów Google Play. Oto co to umożliwi:",
        playModuleOpensFormatsPrefix = "Otwieranie tych formatów:",
        downloadModuleBtn = "Pobierz moduł",
        cancelBtn = "Anuluj",
        closeBtn = "Zamknij",
        openInHexBtn = "Otwórz w HEX",
        securityBadge = "🔒 BEZPIECZEŃSTWO I DOSTĘP",
        formatErrorBadge = "🛠 DIAGNOSTYKA STRUKTURY PLIKU",
        closedLicenseBadge = "⚖️ OGRANICZENIE ZAMKNIĘTEJ LICENCJI",
        passwordRequiredBadge = "🔑 WYMAGANE HASŁO",
        accessRestrictedTitle = "Ograniczenie dostępu lub bezpieczeństwa",
        formatReadErrorTitle = "Błąd odczytu struktury pliku",
        closedLicenseTitle = "Format objęty zamkniętą licencją",
        passwordRequiredTitle = "Dokument jest zaszyfrowany",
        hexFallbackHint = "Możesz bezpiecznie zbadać surowe bajty pliku we wbudowanym inspektorze HEX.",
        archiveContentsTitle = "Zawartość archiwum / obrazu ISO",
        backButton = "← Wstecz",
        archiveEmptyMessage = "Archiwum jest puste lub uszkodzone",
        searchButton = "🔍 Szukaj",
        closeSearchButton = "✕ Zamknij",
        searchPlaceholder = "Tekst lub HEX (50 4B)...",
        findButton = "Znajdź",
        foundOnPagePrefix = "Znaleziono: str.",
        notFoundMessage = "Brak wyników",
        reset3dButton = "Resetuj 3D",
        changeFileButton = "Zmień plik",
        pageLabel = "Str.",
        preparingMedia = "Przygotowanie...",
        playMedia = "▶ Odtwórz",
        pauseMedia = "❚❚ Pauza",
        audioLabel = "🎵 Audio",
        legalComplianceTitle = "O aplikacji / Informacje prawne",
        serverEndpointStatus = "Punkty końcowe serwera skonfigurowane",
        closeDrawerButton = "Zamknij panel",
        aboutLegalSectionTitle = "O aplikacji / Informacje prawne",
        frameRateLabel = "Limit odświeżania ekranu",
        frameRateAutoPattern = "Maksimum, ile wyciągnie Twój telefon (%d Hz)",
        frameRate60 = "60 Hz (Oszczędzanie baterii)",
        frameRate25 = "25 Hz (Super oszczędzanie / Czytanie)",
        audioQualityLabel = "Jakość odtwarzania dźwięku",
        audioQualityAutoPattern = "Maksimum urządzenia (%s: %d kHz, %s)",
        audioQuality96 = "96 kHz / 24-bit (Jakość studyjna)",
        audioQuality48 = "48 kHz / 16-bit (Wysoka jakość)",
        audioQuality44 = "44.1 kHz / 16-bit (Jakość standardowa)",
        audioDeviceSpeaker = "Głośnik telefonu",
        audioDeviceWired = "Słuchawki przewodowe / Aux",
        audioDeviceBluetooth = "Słuchawki Bluetooth / Głośnik",
        audioDeviceUsbDac = "USB DAC / Karta zewnętrzna",
        audioDeviceGeneric = "Urządzenie audio",
        termsOfUseLink = "1. Warunki korzystania (Terms of Use)",
        privacyPolicyLink = "2. Polityka prywatności (Privacy Policy)",
        thirdPartyLicensesLink = "3. Licencje komponentów zewnętrznych",
        thirdPartyLicensesDialogTitle = "Licencje i informacje Open Source",
        copyActionButton = "📋 Kopiuj",
        selectAllActionButton = "Zaznacz wszystko",
        slideNotesButton = "📝 Notatki",
        bufferingCloudFile = "Buforowanie pliku z chmury...",
        defaultMediaTitle = "Plik audio / wideo",
        defaultMediaArtist = "Nieznany wykonawca",
        errorDescriptorAccess = "Nie udało się uzyskać deskryptora pliku przez dostawcę systemowego (brak uprawnień dostępu).",
        errorBufferingStreamPrefix = "Błąd buforowania strumienia",
        errorSuspiciousHeader = "Zablokowano podejrzaną strukturę nagłówka pliku.",
        errorServiceTimeout = "Przekroczono limit czasu oczekiwania na połączenie z izolowaną usługą renderowania.",
        errorServiceReconnecting = "Utracono połączenie z izolowaną usługą renderowania. Ponowne łączenie...",
        errorCorruptedOrUnsupported = "Plik jest uszkodzony, zaszyfrowany lub jego format nie jest obsługiwany.",
        errorFileReadPrefix = "Błąd odczytu pliku",
        errorArchiveExtractFailed = "Nie udało się bezpiecznie wyodrębnić wpisu z archiwum",
        errorNestedFileOpenFailed = "Nie udało się otworzyć zagnieżdżonego pliku",
        errorArchiveReadPrefix = "Błąd odczytu archiwum",
        errorHexOpenFailed = "Nie udało się otworzyć pliku w trybie inspektora HEX.",
        errorRenderFailed = "Błąd renderowania dokumentu.",
        bundleOfficeName = "Dokumenty biurowe, arkusze, prezentacje i e-booki",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Grafika wektorowa i modele CAD 3D",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Archiwa, pakiety i obrazy dysków",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Ponów pobieranie",
        openAsInBrowserButton = "🌐 Otwórz jak w przeglądarce",
        viewHtmlSourceButton = "📄 Kod źródłowy",
        checkForUpdatesButton = "Sprawdź aktualizacje",
        checkingUpdatesStatus = "Sprawdzanie aktualizacji...",
        appUpToDateStatus = "✓ Zainstalowano najnowszą wersję",
        updateAvailableTitle = "Dostępna aktualizacja",
        updateNowButton = "Aktualizuj",
        laterButton = "Później",
        downloadingUpdateStatus = "Pobieranie aktualizacji..."
    )
    AppLanguage.UK -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "Універсальний і бєзопасний рідєр файлів",
        openFileSaf = "Одкрити файл",
        selectLanguageTitle = "Вибирай мову приложєнія",
        selectLanguageSubtitle = "Мову і тему мошно поміняти в любий момент у боковому меню.",
        continueButton = "Погнали дальше",
        settingsMenuTitle = "Настройки і зовнішній вид",
        languageLabel = "Мова інтерфейсу",
        themeLabel = "Тема оформлєнія",
        themeDark = "Тьомна",
        themeLight = "Свєтла",
        themeAsInPhone = "Як у тєлєфоні",
        engineCenterSubtitle = "Поддержувальні формати і доп-модулі",
        builtinFormatsDescription = "Уже встроєні: PDF, DjVu, шрифти (TTF, OTF, TTC, WOFF, WOFF2), картиночки (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO), текст і код (TXT, LOG, JSON, XML, MD, INI, CFG, YAML), бази данних (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL), прев'юшки (MAX, RVT, INDD), музочка і відоси (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) та HEX.",
        selectOptionalModulesPrompt = "Вибирай доп-модулі з Google Play:",
        downloadAllOneClick = "⚡ Скачати все в один клік",
        installSelectedPrefix = "Установити вибранне",
        workLightweightCoreOnly = "Робити налєгкє (Тільки базова основа)",
        moduleReady = "✓ Готово",
        mbSuffix = "МБ",
        playModuleBadge = "📦 МОДУЛЬ GOOGLE PLAY",
        playModuleRequiredTitle = "Надо модуль",
        playModuleRequiredDesc = "Шоб одкрити цей файл, надо скачати модуль для таких типів файлів з сервєрів Google Play. Ось шо воно дасть:",
        playModuleOpensFormatsPrefix = "Одкриває оци формати:",
        downloadModuleBtn = "Скачати модуль",
        cancelBtn = "Отмєна",
        closeBtn = "Закрити",
        openInHexBtn = "Одкрити в HEX",
        securityBadge = "🔒 БЄЗОПАСНОСТЬ І ДОСТУП",
        formatErrorBadge = "🛠 ДІАГНОСТИКА СТРУКТУРИ ФАЙЛА",
        closedLicenseBadge = "⚖️ ОБМЕЖЕННЯ ЗАКРИТОЇ ЛІЦЕНЗІЇ",
        passwordRequiredBadge = "🔑 НАДО ПАРОЛЬ",
        accessRestrictedTitle = "Ограничєніє доступу або бєзопасності",
        formatReadErrorTitle = "Ошибочка читання структури файла",
        closedLicenseTitle = "Формат защищон закритою ліцензією",
        passwordRequiredTitle = "Документ зашифрований",
        hexFallbackHint = "Мошно бєзопасно посмотрєти сирі байти цього файла в інженерному HEX-інспекторі.",
        archiveContentsTitle = "Содержиме архіва / образа",
        backButton = "← Назад",
        archiveEmptyMessage = "Архів пустий або структуру поламали",
        searchButton = "🔍 Поіск",
        closeSearchButton = "✕ Закрити",
        searchPlaceholder = "Текст або HEX (50 4B)...",
        findButton = "Найти",
        foundOnPagePrefix = "Знайшли на стор.",
        notFoundMessage = "Нічо не знайшли",
        reset3dButton = "Скинути 3D",
        changeFileButton = "Поміняти файл",
        pageLabel = "Стор.",
        preparingMedia = "Подготовка...",
        playMedia = "▶ Грати",
        pauseMedia = "❚❚ Пауза",
        audioLabel = "🎵 Аудіопотік",
        legalComplianceTitle = "Про приложєніє / Інформація",
        serverEndpointStatus = "Сервєр синхронізації подключон",
        closeDrawerButton = "Закрити шторку",
        aboutLegalSectionTitle = "Про приложєніє / Інформація",
        frameRateLabel = "Ліміт частоти кадрів екрана",
        frameRateAutoPattern = "Максимум шо може твій тєлєфон (%d Гц)",
        frameRate60 = "60 Гц (Економія батареї)",
        frameRate25 = "25 Гц (Супер-економія / Чітання)",
        audioQualityLabel = "Качество аудіопотока",
        audioQualityAutoPattern = "Максимум подключонного устройства (%s: %d кГц, %s)",
        audioQuality96 = "96 кГц / 24-bit (Студійне качество)",
        audioQuality48 = "48 кГц / 16-bit (Высоке качество)",
        audioQuality44 = "44.1 кГц / 16-bit (Стандартне качество)",
        audioDeviceSpeaker = "Динамік тєлєфона",
        audioDeviceWired = "Проводні наушники / Aux",
        audioDeviceBluetooth = "Bluetooth наушники / Колонка",
        audioDeviceUsbDac = "USB ЦАП / Внєшня карта",
        audioDeviceGeneric = "Аудіоустройство",
        termsOfUseLink = "1. Условія використання (Terms of Use)",
        privacyPolicyLink = "2. Політика конфідеціальності (Privacy Policy)",
        thirdPartyLicensesLink = "3. Сторонні компоненти і ліцензії",
        thirdPartyLicensesDialogTitle = "Сторонні компоненти і ліцензії",
        copyActionButton = "📋 Скопірувати",
        selectAllActionButton = "Виділити все",
        slideNotesButton = "📝 Замєтки",
        bufferingCloudFile = "Буферізація файла з облака...",
        defaultMediaTitle = "Аудіо / Відео файл",
        defaultMediaArtist = "Неізвєстний ісполнітєль",
        errorDescriptorAccess = "Не получилось получить дескриптор файла через системний провайдєр (нєту прав доступу).",
        errorBufferingStreamPrefix = "Ошибочка буферізації потока",
        errorSuspiciousHeader = "Подозрітєльну структуру заголовка файла заблокірували.",
        errorServiceTimeout = "Превишено время жданія соєдінєнія з ізолірованним сервісом рендерінга.",
        errorServiceReconnecting = "Потєрялося соєдінєніє з ізолірованним процесом. Восстанавливаєм...",
        errorCorruptedOrUnsupported = "Файл пошкодьон, зашифрован або цей формат не поддержується.",
        errorFileReadPrefix = "Ошибочка чтєнія файла",
        errorArchiveExtractFailed = "Не получилось бєзопасно достати запись з архіва",
        errorNestedFileOpenFailed = "Не получилось одкрити вложєнний файл",
        errorArchiveReadPrefix = "Ошибочка чтєнія архіва",
        errorHexOpenFailed = "Не получилось одкрити файл у режимі HEX-інспектора.",
        errorRenderFailed = "Сбой рендерінга документа.",
        bundleOfficeName = "Офісні документи, таблички, презентації і книжки",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "Векторна графіка і 3D CAD-модєлі",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "Архіви, пакєти і образи дисків",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ Повторити завантаження",
        openAsInBrowserButton = "🌐 Одкрити як у браузері",
        viewHtmlSourceButton = "📄 Ісходний код",
        checkForUpdatesButton = "Проверити обновлєнія",
        checkingUpdatesStatus = "Проверяєм обновлєнія...",
        appUpToDateStatus = "✓ Установлєна сама послєдняя версія",
        updateAvailableTitle = "Єсть обновлєніє!",
        updateNowButton = "Обновити",
        laterButton = "Позже",
        downloadingUpdateStatus = "Качаєм обновлєніє..."
    )
    AppLanguage.ZH -> AppStrings(
        appName = "FullFileStudio",
        universalReaderSubtitle = "通用安全文件阅读器",
        openFileSaf = "打开文件",
        selectLanguageTitle = "选择应用语言",
        selectLanguageSubtitle = "您可以随时在侧边抽屉菜单中更改语言和主题。",
        continueButton = "继续",
        settingsMenuTitle = "设置与外观",
        languageLabel = "界面语言",
        themeLabel = "外观主题",
        themeDark = "深色",
        themeLight = "浅色",
        themeAsInPhone = "与手机相同",
        engineCenterSubtitle = "支持的格式与附加模块",
        builtinFormatsDescription = "已内置格式：PDF、DjVu、字体 (TTF, OTF, TTC, WOFF, WOFF2)、图片 (PNG, JPG, WEBP, GIF, BMP, TIFF, HEIC, AVIF, ICO)、文本与代码 (TXT, LOG, JSON, XML, MD, INI, CFG, YAML)、数据库 (SQLite: DB, SQLITE, SQLITE3; Access: ACCDB, MDB; dBase: DBF; Parquet, DuckDB, Realm, BSON, JSONL)、工程预览 (MAX, RVT, INDD)、音视频 (MP4, MKV, WEBM, MOV, AVI, MP3, WAV, OGG, FLAC, M4A, AAC) 及 HEX。",
        selectOptionalModulesPrompt = "选择 Google Play 附加模块：",
        downloadAllOneClick = "⚡ 一键下载全部模块",
        installSelectedPrefix = "安装所选模块",
        workLightweightCoreOnly = "轻量运行（仅基础格式）",
        moduleReady = "✓ 已就绪",
        mbSuffix = "MB",
        playModuleBadge = "📦 GOOGLE PLAY 模块",
        playModuleRequiredTitle = "需要扩展模块",
        playModuleRequiredDesc = "要读取此文件，我们需要从 Google Play 服务器下载适用于此类文件的模块。它将为您提供以下功能：",
        playModuleOpensFormatsPrefix = "打开这些格式：",
        downloadModuleBtn = "下载模块",
        cancelBtn = "取消",
        closeBtn = "关闭",
        openInHexBtn = "在 HEX 中打开",
        securityBadge = "🔒 安全与权限限制",
        formatErrorBadge = "🛠 文件结构诊断",
        closedLicenseBadge = "⚖️ 专有许可证限制",
        passwordRequiredBadge = "🔑 需要访问密码",
        accessRestrictedTitle = "访问或安全限制",
        formatReadErrorTitle = "无法解析文件结构",
        closedLicenseTitle = "受闭源许可证保护的格式",
        passwordRequiredTitle = "文档已加密",
        hexFallbackHint = "您可以在内置的工程 HEX 检查器中安全地分析此文件的原始字节。",
        archiveContentsTitle = "压缩包 / 镜像内容",
        backButton = "← 返回",
        archiveEmptyMessage = "压缩包为空或结构损坏",
        searchButton = "🔍 搜索",
        closeSearchButton = "✕ 关闭",
        searchPlaceholder = "文本或 HEX (50 4B)...",
        findButton = "查找",
        foundOnPagePrefix = "已找到：第",
        notFoundMessage = "未找到匹配项",
        reset3dButton = "重置 3D",
        changeFileButton = "更换文件",
        pageLabel = "页",
        preparingMedia = "准备中...",
        playMedia = "▶ 播放",
        pauseMedia = "❚❚ 暂停",
        audioLabel = "🎵 音频流",
        legalComplianceTitle = "关于应用 / 法律信息",
        serverEndpointStatus = "服务器端点已配置",
        closeDrawerButton = "关闭侧边栏",
        aboutLegalSectionTitle = "关于应用 / 法律信息",
        frameRateLabel = "屏幕刷新率限制",
        frameRateAutoPattern = "你的手机所能推到的极限频率 (%d Hz)",
        frameRate60 = "60 Hz (省电模式)",
        frameRate25 = "25 Hz (超级省电 / 阅读模式)",
        audioQualityLabel = "音频流音质",
        audioQualityAutoPattern = "设备最高支持 (%s: %d kHz, %s)",
        audioQuality96 = "96 kHz / 24 位 (录音室级音质)",
        audioQuality48 = "48 kHz / 16 位 (高清音质)",
        audioQuality44 = "44.1 kHz / 16 位 (标准音质)",
        audioDeviceSpeaker = "手机扬声器",
        audioDeviceWired = "有线耳机 / Aux",
        audioDeviceBluetooth = "蓝牙耳机 / 音响",
        audioDeviceUsbDac = "USB DAC / 外置声卡",
        audioDeviceGeneric = "音频设备",
        termsOfUseLink = "1. 使用条款 (Terms of Use)",
        privacyPolicyLink = "2. 隐私政策 (Privacy Policy)",
        thirdPartyLicensesLink = "3. 第三方开源组件许可",
        thirdPartyLicensesDialogTitle = "开源组件许可与声明",
        copyActionButton = "📋 复制",
        selectAllActionButton = "全选",
        slideNotesButton = "📝 备注",
        bufferingCloudFile = "正在从云端缓冲文件...",
        defaultMediaTitle = "音频 / 视频文件",
        defaultMediaArtist = "未知艺术家",
        errorDescriptorAccess = "无法通过系统提供程序获取文件描述符（缺少访问权限）。",
        errorBufferingStreamPrefix = "流缓冲错误",
        errorSuspiciousHeader = "已拦截可疑或损坏的文件头结构。",
        errorServiceTimeout = "等待隔离渲染服务连接超时。",
        errorServiceReconnecting = "与隔离渲染服务的连接已断开，正在重新连接...",
        errorCorruptedOrUnsupported = "文件已损坏、已加密或格式不受支持。",
        errorFileReadPrefix = "文件读取错误",
        errorArchiveExtractFailed = "无法从压缩包中安全提取条目",
        errorNestedFileOpenFailed = "无法打开嵌套文件",
        errorArchiveReadPrefix = "压缩包读取错误",
        errorHexOpenFailed = "无法在 HEX 检查器模式下打开文件。",
        errorRenderFailed = "文档渲染失败。",
        bundleOfficeName = "办公文档、电子表格、演示文稿与电子书",
        bundleOfficeDesc = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
        bundleCadName = "矢量图形与 3D CAD 模型",
        bundleCadDesc = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX",
        bundleArchivesName = "压缩包、软件包与光盘镜像",
        bundleArchivesDesc = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
        retryDownloadButton = "↻ 重试下载",
        openAsInBrowserButton = "🌐 像浏览器一样打开",
        viewHtmlSourceButton = "📄 查看源代码",
        checkForUpdatesButton = "检查更新",
        checkingUpdatesStatus = "正在检查更新...",
        appUpToDateStatus = "✓ 已安装最新版本",
        updateAvailableTitle = "发现新版本",
        updateNowButton = "立即更新",
        laterButton = "稍后",
        downloadingUpdateStatus = "正在下载更新..."
    )
}

/**
 * Translates all diagnostic, DRM, and engine error messages from isolated parsers into the user's selected language,
 * providing accurate domain-specific translations across all 8 supported languages.
 */
fun localizeEngineMessage(rawMessage: String, lang: AppLanguage): String {
    val clean = rawMessage
        .removePrefix("PASSWORD_REQUIRED:")
        .removePrefix("CORRUPTED_PDF:")
        .trim()
    val strings = stringsForLanguage(lang)
    val lower = clean.lowercase(Locale.ROOT)

    return when {
        lower.contains("dmca") || lower.contains("kfx") || lower.contains("encryption type") || lower.contains("tpm/drm") || lower.contains("drm") -> when (lang) {
            AppLanguage.EN -> "This Kindle e-book (KFX or DRM-encrypted AZW3/MOBI) is protected by cryptographic DRM. Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            AppLanguage.RU -> "Данная книга (KFX или защищённый DRM экземпляр AZW3/MOBI) зашифрована криптографической DRM-защитой. Обход технических средств защиты авторских прав запрещён законом (DMCA § 1201 / Директива ЕС InfoSoc)."
            AppLanguage.DE -> "Dieses Kindle-E-Book (KFX oder DRM-geschütztes AZW3/MOBI) ist durch kryptografisches DRM geschützt. Das Umgehen technischer Schutzmaßnahmen (TPM/DRM) ist gesetzlich untersagt (DMCA § 1201 / EU-InfoSoc-Richtlinie)."
            AppLanguage.ES -> "Este libro Kindle (KFX o AZW3/MOBI con DRM) está protegido por DRM criptográfico. La elusión de medidas tecnológicas de protección está prohibida por la ley (DMCA § 1201 / Directiva InfoSoc de la UE)."
            AppLanguage.FR -> "Ce livre Kindle (KFX ou AZW3/MOBI protégé par DRM) est chiffré par un verrou numérique DRM. Le contournement des mesures techniques de protection est interdit par la loi (DMCA § 1201 / Directive européenne InfoSoc)."
            AppLanguage.PL -> "Ta książka Kindle (KFX lub chroniony DRM plik AZW3/MOBI) jest zabezpieczona kryptograficznym systemem DRM. Obchodzenie technicznych zabezpieczeń (TPM/DRM) jest zabronione przez prawo (DMCA § 1201 / Dyrektywa UE InfoSoc)."
            AppLanguage.UK -> "Цю книжку Kindle (KFX або защищонний DRM примірник AZW3/MOBI) зашифровано криптографічно."
            AppLanguage.ZH -> "此 Kindle 电子书（KFX 或受 DRM 保护的 AZW3/MOBI）受加密数字版权管理 (DRM) 保护。根据 DMCA § 1201 和欧盟 InfoSoc 指令，禁止绕过技术保护措施。"
        }
        lower.contains("rar 5.0") || lower.contains("rar5") || lower.contains("unrar") -> when (lang) {
            AppLanguage.EN -> "RAR 5.0 archive is empty, encrypted, or its block structure is corrupted."
            AppLanguage.RU -> "Архив RAR 5.0 пуст, зашифрован или его блочная структура повреждена."
            AppLanguage.DE -> "Das RAR 5.0-Archiv ist leer, verschlüsselt oder die Blockstruktur ist beschädigt."
            AppLanguage.ES -> "El archivo RAR 5.0 está vacío, cifrado o su estructura de bloques está dañada."
            AppLanguage.FR -> "L'archive RAR 5.0 est vide, chiffrée ou sa structure de blocs est corrompue."
            AppLanguage.PL -> "Archiwum RAR 5.0 jest puste, zaszyfrowane lub jego struktura bloków jest uszkodzona."
            AppLanguage.UK -> "Архів RAR 5.0 пустий, зашифрований або його блочна структура поламалася."
            AppLanguage.ZH -> "RAR 5.0 压缩包为空、已加密或块结构已损坏。"
        }
        lower.contains("autodesk") || lower.contains("dwg") || lower.contains("3ds max") || lower.contains("revit") -> when (lang) {
            AppLanguage.EN -> "CAD/BIM container (.dwg / .max / .rvt) is corrupted, truncated, or contains no readable preview or geometry."
            AppLanguage.RU -> "Контейнер чертежа или проекта (.dwg / .max / .rvt) повреждён, усечён или не содержит читаемого превью и геометрии."
            AppLanguage.DE -> "Die CAD/BIM-Datei (.dwg / .max / .rvt) ist beschädigt oder enthält keine lesbare Vorschau oder Geometrie."
            AppLanguage.ES -> "El contenedor CAD/BIM (.dwg / .max / .rvt) está dañado o no contiene vista previa ni geometría legible."
            AppLanguage.FR -> "Le conteneur CAO/BIM (.dwg / .max / .rvt) est corrompu ou ne contient aucun aperçu ni géométrie lisible."
            AppLanguage.PL -> "Plik CAD/BIM (.dwg / .max / .rvt) jest uszkodzony lub nie zawiera czytelnego podglądu ani geometrii."
            AppLanguage.UK -> "Контейнер чертєжа або проєкта (.dwg / .max / .rvt) пошкодьон, усечон або не містить читаємого прев'ю."
            AppLanguage.ZH -> "CAD/BIM 容器（.dwg / .max / .rvt）已损坏，或不包含可读的预览图与几何数据。"
        }
        lower.contains("indesign") || lower.contains("onenote") || lower.contains("palmdb") || lower.contains("mobi") -> when (lang) {
            AppLanguage.EN -> "Document container (.one / .indd / .mobi) is corrupted, empty, or contains no readable pages."
            AppLanguage.RU -> "Контейнер документа (.one / .indd / .mobi) повреждён, пуст или не содержит читаемых страниц."
            AppLanguage.DE -> "Der Dokumentcontainer (.one / .indd / .mobi) ist beschädigt, leer oder enthält keine lesbaren Seiten."
            AppLanguage.ES -> "El contenedor del documento (.one / .indd / .mobi) está dañado, vacío o no contiene páginas legibles."
            AppLanguage.FR -> "Le conteneur de document (.one / .indd / .mobi) est corrompu, vide ou ne contient aucune page lisible."
            AppLanguage.PL -> "Kontener dokumentu (.one / .indd / .mobi) jest uszkodzony, pusty lub nie zawiera czytelnych stron."
            AppLanguage.UK -> "Контейнер документа (.one / .indd / .mobi) пошкодьон, пустий або не містить читаємих сторінок."
            AppLanguage.ZH -> "文档容器（.one / .indd / .mobi）已损坏、为空或不包含可读页面。"
        }
        lower.contains("font") || lower.contains("sfnt") || lower.contains("ttf") || lower.contains("otf") || lower.contains("woff") -> when (lang) {
            AppLanguage.EN -> "Font file (.ttf / .otf / .ttc / .woff) is corrupted, truncated, or contains no valid SFNT glyph tables."
            AppLanguage.RU -> "Файл шрифта (.ttf / .otf / .ttc / .woff) повреждён, усечён или не содержит корректных таблиц глифов SFNT."
            AppLanguage.DE -> "Die Schriftdatei (.ttf / .otf / .ttc / .woff) ist beschädigt oder enthält keine gültigen SFNT-Glyphentabellen."
            AppLanguage.ES -> "El archivo de fuente (.ttf / .otf / .ttc / .woff) está dañado o no contiene tablas de glifos SFNT válidas."
            AppLanguage.FR -> "Le fichier de police (.ttf / .otf / .ttc / .woff) est corrompu ou ne contient aucune table de glyphes SFNT valide."
            AppLanguage.PL -> "Plik czcionki (.ttf / .otf / .ttc / .woff) jest uszkodzony lub nie zawiera prawidłowych tabel glifów SFNT."
            AppLanguage.UK -> "Файл шрифта (.ttf / .otf / .ttc / .woff) пошкодьон, усечон або не містить правилних таблиць."
            AppLanguage.ZH -> "字体文件（.ttf / .otf / .ttc / .woff）已损坏或不包含有效的 SFNT 字形表。"
        }
        lower.contains("djvu") || lower.contains("gplv2") -> when (lang) {
            AppLanguage.EN -> "DjVu document is corrupted, truncated, or contains no readable pages."
            AppLanguage.RU -> "Документ DjVu повреждён, усечён или не содержит читаемых страниц."
            AppLanguage.DE -> "Das DjVu-Dokument ist beschädigt oder enthält keine lesbaren Seiten."
            AppLanguage.ES -> "El documento DjVu está dañado o no contiene páginas legibles."
            AppLanguage.FR -> "Le document DjVu est corrompu ou ne contient aucune page lisible."
            AppLanguage.PL -> "Dokument DjVu jest uszkodzony lub nie zawiera czytelnych stron."
            AppLanguage.UK -> "Документ DjVu пошкодьон або не містить читаємих сторінок."
            AppLanguage.ZH -> "DjVu 文档已损坏或不包含可读页面。"
        }
        lower.contains("xxe") || lower.contains("dtd") -> when (lang) {
            AppLanguage.EN -> "Blocked potential XXE injection (external XML entities and external DTDs are forbidden)."
            AppLanguage.RU -> "Заблокирована попытка XXE-атаки (внешние XML-сущности и DTD запрещены)."
            AppLanguage.DE -> "Potenzielle XXE-Injektion blockiert (externe XML-Entitäten und DTDs sind verboten)."
            AppLanguage.ES -> "Intento de ataque XXE bloqueado (las entidades XML externas y DTD están prohibidas)."
            AppLanguage.FR -> "Tentative d'attaque XXE bloquée (les entités XML externes et DTD sont interdites)."
            AppLanguage.PL -> "Zablokowano próbę ataku XXE (zewnętrzne encje XML i DTD są zabronione)."
            AppLanguage.UK -> "Заблокіровано спробу XXE-атаки (внєшні XML-сущності і DTD запрєщєні)."
            AppLanguage.ZH -> "已拦截潜在的 XXE 注入攻击（禁止外部 XML 实体和 DTD）。"
        }
        lower.contains("внешний ресурс") || lower.contains("data:image") -> when (lang) {
            AppLanguage.EN -> "External <image> resource blocked by security policy (only embedded data:image resources are allowed)."
            AppLanguage.RU -> "Внешний ресурс <image> заблокирован политикой безопасности (разрешены только встроенные data:image ресурсы)."
            AppLanguage.DE -> "Externe <image>-Ressource durch Sicherheitsrichtlinie blockiert (nur eingebettete data:image-Ressourcen erlaubt)."
            AppLanguage.ES -> "Recurso <image> externo bloqueado por seguridad (solo se permiten recursos data:image incrustados)."
            AppLanguage.FR -> "Ressource <image> externe bloquée par la politique de sécurité (seules les ressources data:image intégrées sont autorisées)."
            AppLanguage.PL -> "Zewnętrzny zasób <image> zablokowany przez politykę bezpieczeństwa (dozwolone są tylko osadzone zasoby data:image)."
            AppLanguage.UK -> "Внєшній ресурс <image> заблокіровано політикою бєзопасності (дозволені тільки встроєні data:image)."
            AppLanguage.ZH -> "安全策略已拦截外部 <image> 资源（仅允许内嵌 data:image 资源）。"
        }
        lower.contains("pe/mach-o") || lower.contains("машинный код") || lower.contains("machine code") || lower.contains("executable") -> when (lang) {
            AppLanguage.EN -> "Executable binary header detected. Direct execution is blocked by security policy."
            AppLanguage.RU -> "Обнаружен исполняемый бинарный заголовок. Прямое исполнение заблокировано политикой безопасности."
            AppLanguage.DE -> "Ausführbarer Binär-Header erkannt. Direkte Ausführung ist durch die Sicherheitsrichtlinie blockiert."
            AppLanguage.ES -> "Se detectó un encabezado binario ejecutable. La ejecución directa está bloqueada por seguridad."
            AppLanguage.FR -> "En-tête binaire exécutable détecté. L'exécution directe est bloquée par sécurité."
            AppLanguage.PL -> "Wykryto wykonywalny nagłówek binarny. Bezpośrednie wykonanie zostało zablokowane."
            AppLanguage.UK -> "Знайшли ісполняємий бінарний заголовок. Пряме ісполнєніє заблокіровано політикою бєзопасності."
            AppLanguage.ZH -> "检测到可执行二进制文件头，安全策略已阻止直接执行。"
        }
        lower.contains("полиглот") || lower.contains("polyglot") -> when (lang) {
            AppLanguage.EN -> "Polyglot container injection detected inside PDF header."
            AppLanguage.RU -> "Обнаружен полиглот-контейнер внутри заголовка PDF."
            AppLanguage.DE -> "Polyglot-Container-Injektion im PDF-Header erkannt."
            AppLanguage.ES -> "Se detectó un contenedor políglota dentro del encabezado PDF."
            AppLanguage.FR -> "Conteneur polyglotte détecté dans l'en-tête PDF."
            AppLanguage.PL -> "Wykryto wstrzyknięcie kontenera poligloty w nagłówku PDF."
            AppLanguage.UK -> "Знайшли поліглот-контейнер усередині заголовка PDF."
            AppLanguage.ZH -> "在 PDF 文件头中检测到多态容器注入。"
        }
        lower.contains("zip bomb") || lower.contains("zip-бомб") || lower.contains("лимит распаковки") || lower.contains("лимит декомпрессии") || lower.contains("предел извлечения") || lower.contains("extraction limit") -> when (lang) {
            AppLanguage.EN -> "Extraction aborted: safe decompression limit exceeded (Zip Bomb protection)."
            AppLanguage.RU -> "Извлечение прервано: превышен безопасный лимит распаковки (защита от Zip-бомб)."
            AppLanguage.DE -> "Extraktion abgebrochen: Sicheres Dekomprimierungslimit überschritten (Zip-Bomben-Schutz)."
            AppLanguage.ES -> "Extracción cancelada: se superó el límite seguro de descomposición (protección contra bombas Zip)."
            AppLanguage.FR -> "Extraction interrompue : limite de décompression sécurisée dépassée (protection Zip Bomb)."
            AppLanguage.PL -> "Przerwano wypakowywanie: przekroczono bezpieczny limit dekompresji (ochrona przed Zip Bomb)."
            AppLanguage.UK -> "Распаковка прервана: превишено бєзопасний ліміт распаковки (защита від Zip-бомб)."
            AppLanguage.ZH -> "提取已中止：超出安全解压限制（Zip 炸弹防护）。"
        }
        rawMessage.startsWith("PASSWORD_REQUIRED") || lower.contains("password protected") || lower.contains("защищён паролем") || lower.contains("защищена паролем") || lower.contains("защищён шифрованием") || lower.contains("is encrypted") -> when (lang) {
            AppLanguage.EN -> "This document or archive is password-protected or encrypted."
            AppLanguage.RU -> "Документ или архив защищён паролем или шифрованием доступа."
            AppLanguage.DE -> "Dieses Dokument oder Archiv ist passwortgeschützt oder verschlüsselt."
            AppLanguage.ES -> "Este documento o archivo está protegido con contraseña o cifrado."
            AppLanguage.FR -> "Ce document ou cette archive est protégé par mot de passe ou chiffré."
            AppLanguage.PL -> "Ten dokument lub archiwum jest chronione hasłem lub zaszyfrowane."
            AppLanguage.UK -> "Документ або архів защищон паролєм чи шифрованієм доступу."
            AppLanguage.ZH -> "此文档或压缩包受密码保护或已加密。"
        }
        lower.contains("не найдено листов") || lower.contains("no data sheets") || lower.contains("missing workbook") -> when (lang) {
            AppLanguage.EN -> "No readable data sheets found in the spreadsheet workbook."
            AppLanguage.RU -> "В книге таблицы не найдено листов с данными."
            AppLanguage.DE -> "Keine lesbaren Tabellenblätter in der Arbeitsmappe gefunden."
            AppLanguage.ES -> "No se encontraron hojas de datos legibles en el libro de cálculo."
            AppLanguage.FR -> "Aucune feuille de calcul lisible n'a été trouvée dans le classeur."
            AppLanguage.PL -> "W skoroszycie nie znaleziono arkuszy z danymi."
            AppLanguage.UK -> "У книжці таблички не знайшли листи з даними."
            AppLanguage.ZH -> "在电子表格工作簿中未找到可读的数据工作表。"
        }
        lower.contains("слайдов") || lower.contains("presentation.xml") || lower.contains("no slides") || lower.contains("powerpoint document") -> when (lang) {
            AppLanguage.EN -> "No readable slides found in the presentation container."
            AppLanguage.RU -> "В презентации не обнаружено читаемых слайдов или структура контейнера повреждена."
            AppLanguage.DE -> "Keine lesbaren Folien in der Präsentation gefunden."
            AppLanguage.ES -> "No se encontraron diapositivas legibles en la presentación."
            AppLanguage.FR -> "Aucune diapositive lisible n'a été trouvée dans la présentation."
            AppLanguage.PL -> "W prezentacji nie znaleziono czytelnych slajdów."
            AppLanguage.UK -> "У презентації не знайшли читаємих слайдів."
            AppLanguage.ZH -> "在演示文稿中未找到可读的幻灯片。"
        }
        lower.contains("в архиве не найдено") || lower.contains("оглавление повреждено") || lower.contains("архива пуст") || lower.contains("формат архива") -> when (lang) {
            AppLanguage.EN -> "Archive is empty or its central directory structure is corrupted."
            AppLanguage.RU -> "В архиве не найдено файлов или оглавление повреждено."
            AppLanguage.DE -> "Das Archiv ist leer oder das Inhaltsverzeichnis ist beschädigt."
            AppLanguage.ES -> "El archivo comprimido está vacío o su índice está dañado."
            AppLanguage.FR -> "L'archive est vide ou son répertoire central est corrompu."
            AppLanguage.PL -> "Archiwum jest puste lub spis treści jest uszkodzony."
            AppLanguage.UK -> "В архіві не знайшли файлів або оглавлєніє поламалося."
            AppLanguage.ZH -> "压缩包为空或目录结构已损坏。"
        }
        lower.contains("3d/cad") || lower.contains("геометрии") || lower.contains("бинарный stl") || lower.contains("gltf") || lower.contains("glb") || lower.contains("blender") || lower.contains("usdz") || lower.contains("fbx") -> when (lang) {
            AppLanguage.EN -> "3D/CAD model is empty, corrupted, or contains no supported mesh geometry."
            AppLanguage.RU -> "3D/CAD модель пуста, повреждена или не содержит поддерживаемой геометрии."
            AppLanguage.DE -> "Das 3D/CAD-Modell ist leer, beschädigt oder enthält keine unterstützte Geometrie."
            AppLanguage.ES -> "El modelo 3D/CAD está vacío, dañado o no contiene geometría compatible."
            AppLanguage.FR -> "Le modèle 3D/CAO est vide, corrompu ou ne contient aucune géométrie prise en charge."
            AppLanguage.PL -> "Model 3D/CAD jest pusty, uszkodzony lub nie zawiera obsługiwanej geometrii."
            AppLanguage.UK -> "3D/CAD модель порожня, пошкоджена або не містить підтримуваної геометрії."
            AppLanguage.ZH -> "3D/CAD 模型为空、已损坏或不包含受支持的几何结构。"
        }
        lower.contains("<svg>") || lower.contains("svgz") || lower.contains("графических элементов") -> when (lang) {
            AppLanguage.EN -> "SVG/SVGZ vector document is corrupted or contains no supported graphic elements."
            AppLanguage.RU -> "Векторный документ SVG/SVGZ повреждён или не содержит поддерживаемых графических элементов."
            AppLanguage.DE -> "Das SVG/SVGZ-Vektordokument ist beschädigt oder enthält keine unterstützten Grafikelemente."
            AppLanguage.ES -> "El documento vectorial SVG/SVGZ está dañado o no contiene elementos gráficos compatibles."
            AppLanguage.FR -> "Le document vectoriel SVG/SVGZ est corrompu ou ne contient aucun élément graphique pris en charge."
            AppLanguage.PL -> "Dokument wektorowy SVG/SVGZ jest uszkodzony lub nie zawiera obsługiwanych elementów graficznych."
            AppLanguage.UK -> "Векторний документ SVG/SVGZ пошкоджено або він не містить підтримуваних графічних елементів."
            AppLanguage.ZH -> "SVG/SVGZ 矢量文档已损坏或不包含受支持的图形元素。"
        }
        lower.contains("медиапотока") || lower.contains("медиафайл") || lower.contains("медиаплеер") || lower.contains("аудио- или видеопотоков") || lower.contains("codec") -> when (lang) {
            AppLanguage.EN -> "Media stream decoding failed: file is corrupted or uses an unsupported codec."
            AppLanguage.RU -> "Сбой декодирования медиапотока: файл повреждён или использует неподдерживаемый кодек."
            AppLanguage.DE -> "Mediendekodierung fehlgeschlagen: Datei ist beschädigt oder verwendet einen nicht unterstützten Codec."
            AppLanguage.ES -> "Error al decodificar el flujo multimedia: el archivo está dañado o utiliza un códec no compatible."
            AppLanguage.FR -> "Échec du décodage du flux multimédia : le fichier est corrompu ou utilise un codec non pris en charge."
            AppLanguage.PL -> "Błąd dekodowania strumienia multimedialnego: plik jest uszkodzony lub używa nieobsługiwanego kodeka."
            AppLanguage.UK -> "Збій декодування медіапотоку: файл пошкоджено або використовується непідтримуваний кодек."
            AppLanguage.ZH -> "媒体流解码失败：文件已损坏或使用了不受支持的编解码器。"
        }
        lower.contains("схему таблицы") || lower.contains("строки таблицы") || lower.contains("ячейку") || lower.contains("диапазон таблицы") || lower.contains("sqlite") || lower.contains("access") || lower.contains("jet/ace") || lower.contains("dbf") || lower.contains("parquet") || lower.contains("duckdb") || lower.contains("realm") || lower.contains("bson") || lower.contains("jsonl") || lower.contains("ndjson") -> when (lang) {
            AppLanguage.EN -> "Unable to read database schema or table records (SQLite / Access / DBF / Parquet / DuckDB / Realm / BSON / JSONL file may be corrupted or encrypted)."
            AppLanguage.RU -> "Не удалось прочитать схему или записи таблицы базы данных (файл SQLite / Access / DBF / Parquet / DuckDB / Realm / BSON / JSONL повреждён или зашифрован)."
            AppLanguage.DE -> "Datenbankschema oder Tabelleneinträge konnten nicht gelesen werden (Datei beschädigt oder verschlüsselt)."
            AppLanguage.ES -> "No se pudo leer el esquema o los registros de la base de datos (archivo dañado o cifrado)."
            AppLanguage.FR -> "Impossible de lire le schéma ou les enregistrements de la base de données (fichier corrompu ou chiffré)."
            AppLanguage.PL -> "Nie udało się odczytać schematu lub rekordów bazy danych (plik uszkodzony lub zaszyfrowany)."
            AppLanguage.UK -> "Не вдалося прочитати схему або записи таблиці бази даних (файл пошкоджено або зашифровано)."
            AppLanguage.ZH -> "无法读取数据库架构或表记录（文件可能已损坏或已加密）。"
        }
        lower.contains("потеряно соединение с изолированным") || lower.contains("reconnecting") -> strings.errorServiceReconnecting
        lower.contains("превышено время ожидания соединения") || lower.contains("timed out waiting") -> strings.errorServiceTimeout
        else -> {
            val hasCyrillic = clean.any { it in '\u0400'..'\u04FF' }
            when (lang) {
                AppLanguage.RU -> if (hasCyrillic) clean else strings.errorCorruptedOrUnsupported
                AppLanguage.EN -> if (!hasCyrillic) clean else strings.errorCorruptedOrUnsupported
                else -> strings.errorCorruptedOrUnsupported
            }
        }
    }
}

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
    val noticesText = remember {
        try {
            context.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            "Unable to load third-party notices asset."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
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
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.border)
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surfaceElevated)
                        .padding(12.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = noticesText,
                        color = palette.primaryText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(scrollState)
                    )
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

fun detectMaxDisplayRefreshRate(context: Context): Int {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        try {
            val display = (context as? android.app.Activity)?.window?.decorView?.display
                ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val modes = display?.supportedModes
            if (!modes.isNullOrEmpty()) {
                val maxHz = modes.maxOf { it.refreshRate }.toInt()
                if (maxHz > 0) return maxHz
            }
        } catch (_: Throwable) {}
    }
    return 120
}

@Composable
fun FrameRateDropdownSelector(
    currentMode: AppFrameRateMode,
    onModeSelected: (AppFrameRateMode) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
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

enum class AudioDeviceCategory { SPEAKER, WIRED, BLUETOOTH, USB_DAC, GENERIC }

data class ActiveAudioDeviceCapabilities(
    val category: AudioDeviceCategory,
    val rawProductName: String,
    val deviceTypeIcon: String,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int
) {
    fun getLocalizedDeviceName(strings: AppStrings): String = when (category) {
        AudioDeviceCategory.SPEAKER -> strings.audioDeviceSpeaker
        AudioDeviceCategory.WIRED -> strings.audioDeviceWired
        AudioDeviceCategory.BLUETOOTH -> strings.audioDeviceBluetooth
        AudioDeviceCategory.USB_DAC -> strings.audioDeviceUsbDac
        AudioDeviceCategory.GENERIC -> rawProductName.ifBlank { strings.audioDeviceGeneric }
    }

    fun getLocalizedFormatDescription(): String = "$maxBitDepth-bit / ${maxSampleRateHz / 1000} kHz"
}

object AudioDeviceCapabilitiesDetector {

    fun detectActiveAudioDevice(context: Context): ActiveAudioDeviceCapabilities {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActiveAudioDeviceCapabilities(AudioDeviceCategory.SPEAKER, "", "🔊", 48000, 16)

        var category = AudioDeviceCategory.SPEAKER
        var rawName = ""
        var bestIcon = "🔊"
        var maxSampleRate = 48000
        var maxBitDepth = 16

        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val activeDevice = devices.firstOrNull { dev ->
                dev.type in setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID
                ) || dev.type == 26 || dev.type == 27
            } ?: devices.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || dev.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }

            if (activeDevice != null) {
                rawName = activeDevice.productName.toString()
                when (activeDevice.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                        category = AudioDeviceCategory.WIRED
                        bestIcon = "🎧"
                    }
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        category = AudioDeviceCategory.USB_DAC
                        bestIcon = "🔌"
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    26, 27 -> {
                        category = AudioDeviceCategory.BLUETOOTH
                        bestIcon = "📻"
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
                        category = AudioDeviceCategory.SPEAKER
                        bestIcon = "🔊"
                    }
                    else -> {
                        category = AudioDeviceCategory.GENERIC
                        bestIcon = "🎧"
                    }
                }

                val rates = activeDevice.sampleRates
                if (rates != null && rates.isNotEmpty()) {
                    maxSampleRate = rates.maxOrNull()?.coerceAtLeast(44100) ?: 48000
                }

                val encodings = activeDevice.encodings
                if (encodings != null && encodings.isNotEmpty()) {
                    if (encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) || encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) || encodings.contains(AudioFormat.ENCODING_PCM_32BIT)) {
                        maxBitDepth = 24
                    }
                }
            }
        } catch (_: Throwable) {}

        if (maxSampleRate <= 48000) {
            val propRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (propRate != null && propRate > maxSampleRate) {
                maxSampleRate = propRate
            }
        }

        return ActiveAudioDeviceCapabilities(
            category = category,
            rawProductName = rawName,
            deviceTypeIcon = bestIcon,
            maxSampleRateHz = maxSampleRate,
            maxBitDepth = maxBitDepth
        )
    }
}

@Composable
fun rememberActiveAudioDeviceCapabilities(): ActiveAudioDeviceCapabilities {
    val context = androidx.compose.ui.platform.LocalContext.current
    var activeDevice by remember {
        mutableStateOf(AudioDeviceCapabilitiesDetector.detectActiveAudioDevice(context))
    }

    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val updateDevice = {
            activeDevice = AudioDeviceCapabilitiesDetector.detectActiveAudioDevice(context)
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateDevice()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateDevice()
            }
        }

        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                updateDevice()
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
        }
        context.registerReceiver(receiver, filter)

        onDispose {
            audioManager?.unregisterAudioDeviceCallback(callback)
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    return activeDevice
}

@Composable
fun AudioQualityDropdownSelector(
    currentMode: AppAudioQualityMode,
    onModeSelected: (AppAudioQualityMode) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val activeDevice = rememberActiveAudioDeviceCapabilities()
    var expanded by remember { mutableStateOf(false) }

    fun localizedModeName(mode: AppAudioQualityMode): String = when (mode) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = activeDevice.deviceTypeIcon, fontSize = 16.sp)
                    Text(
                        text = localizedModeName(currentMode),
                        color = palette.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(text = "▼", color = palette.secondaryText, fontSize = 11.sp)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(palette.surfaceElevated)
        ) {
            val availableModes = remember(activeDevice) {
                AppAudioQualityMode.entries.filter { mode ->
                    mode == AppAudioQualityMode.AUTO || mode.sampleRateHz <= activeDevice.maxSampleRateHz
                }
            }
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
                text = strings.audioQualityLabel,
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            AudioQualityDropdownSelector(
                currentMode = currentAudioQualityMode,
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
                            uriHandler.openUri(OmniServerEndpoints.termsOfServiceWebUrl)
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
                            uriHandler.openUri(OmniServerEndpoints.privacyPolicyWebUrl)
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