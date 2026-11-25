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

package me.hlebkukleuski.fullfilestudio.ui.i18n

import androidx.compose.runtime.Immutable
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage

@Immutable
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
    val passwordPlaceholder: String,
    val unlockBtn: String,
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
    val mediaQueue: String,
    val mediaAddFiles: String,
    val mediaSubtitles: String,
    val mediaSubtitlesOff: String,
    val mediaLoadSubtitles: String,
    val mediaAudioTrack: String,
    val mediaSpeed: String,
    val mediaSubtitlesFailed: String,
    val mediaClose: String,
    val missingResourcesTitle: String,
    val attachResourcesButton: String,
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

    fun localizedFrameRateAuto(maxHz: Int): String = when (frameRateAutoPattern) {
        "Максимум что может твой телефон (%d Гц)" -> "Максимум что может твой телефон ($maxHz Гц)"
        else -> String.format(Locale.US, frameRateAutoPattern, maxHz)
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
        passwordPlaceholder = "Enter password",
        unlockBtn = "Unlock",
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
        mediaQueue = "Queue",
        mediaAddFiles = "Add files",
        mediaSubtitles = "Subtitles",
        mediaSubtitlesOff = "Off",
        mediaLoadSubtitles = "Load subtitle file…",
        mediaAudioTrack = "Audio track",
        mediaSpeed = "Speed",
        mediaSubtitlesFailed = "Could not read this subtitle file",
        mediaClose = "Close",
        missingResourcesTitle = "This file refers to other files that are not available",
        attachResourcesButton = "Attach files…",
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
        passwordPlaceholder = "Введите пароль",
        unlockBtn = "Открыть",
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
        mediaQueue = "Очередь",
        mediaAddFiles = "Добавить файлы",
        mediaSubtitles = "Субтитры",
        mediaSubtitlesOff = "Выкл.",
        mediaLoadSubtitles = "Загрузить файл субтитров…",
        mediaAudioTrack = "Аудиодорожка",
        mediaSpeed = "Скорость",
        mediaSubtitlesFailed = "Не удалось прочитать файл субтитров",
        mediaClose = "Закрыть",
        missingResourcesTitle = "Файл ссылается на недоступные файлы",
        attachResourcesButton = "Указать файлы…",
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
        passwordPlaceholder = "Passwort eingeben",
        unlockBtn = "Entsperren",
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
        mediaQueue = "Warteschlange",
        mediaAddFiles = "Dateien hinzufügen",
        mediaSubtitles = "Untertitel",
        mediaSubtitlesOff = "Aus",
        mediaLoadSubtitles = "Untertiteldatei laden…",
        mediaAudioTrack = "Tonspur",
        mediaSpeed = "Tempo",
        mediaSubtitlesFailed = "Untertiteldatei konnte nicht gelesen werden",
        mediaClose = "Schließen",
        missingResourcesTitle = "Die Datei verweist auf nicht verfügbare Dateien",
        attachResourcesButton = "Dateien angeben…",
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
        passwordPlaceholder = "Introducir contraseña",
        unlockBtn = "Desbloquear",
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
        mediaQueue = "Cola",
        mediaAddFiles = "Añadir archivos",
        mediaSubtitles = "Subtítulos",
        mediaSubtitlesOff = "Desactivados",
        mediaLoadSubtitles = "Cargar archivo de subtítulos…",
        mediaAudioTrack = "Pista de audio",
        mediaSpeed = "Velocidad",
        mediaSubtitlesFailed = "No se pudo leer el archivo de subtítulos",
        mediaClose = "Cerrar",
        missingResourcesTitle = "El archivo hace referencia a archivos no disponibles",
        attachResourcesButton = "Indicar archivos…",
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
        passwordPlaceholder = "Saisir le mot de passe",
        unlockBtn = "Déverrouiller",
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
        mediaQueue = "File d’attente",
        mediaAddFiles = "Ajouter des fichiers",
        mediaSubtitles = "Sous-titres",
        mediaSubtitlesOff = "Désactivés",
        mediaLoadSubtitles = "Charger un fichier de sous-titres…",
        mediaAudioTrack = "Piste audio",
        mediaSpeed = "Vitesse",
        mediaSubtitlesFailed = "Impossible de lire ce fichier de sous-titres",
        mediaClose = "Fermer",
        missingResourcesTitle = "Le fichier fait référence à des fichiers indisponibles",
        attachResourcesButton = "Indiquer les fichiers…",
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
        passwordPlaceholder = "Wpisz hasło",
        unlockBtn = "Odblokuj",
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
        mediaQueue = "Kolejka",
        mediaAddFiles = "Dodaj pliki",
        mediaSubtitles = "Napisy",
        mediaSubtitlesOff = "Wył.",
        mediaLoadSubtitles = "Wczytaj plik napisów…",
        mediaAudioTrack = "Ścieżka audio",
        mediaSpeed = "Prędkość",
        mediaSubtitlesFailed = "Nie udało się odczytać pliku napisów",
        mediaClose = "Zamknij",
        missingResourcesTitle = "Plik odwołuje się do niedostępnych plików",
        attachResourcesButton = "Wskaż pliki…",
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
        universalReaderSubtitle = "Універсальний і безпечний переглядач файлів",
        openFileSaf = "Відкрити файл",
        selectLanguageTitle = "Оберіть мову додатка",
        selectLanguageSubtitle = "Мову та тему можна змінити в будь-який момент у боковому меню.",
        continueButton = "Продовжити",
        settingsMenuTitle = "Налаштування та вигляд",
        languageLabel = "Мова інтерфейсу",
        themeLabel = "Тема оформлення",
        themeDark = "Темна",
        themeLight = "Світла",
        themeAsInPhone = "Як у системі",
        cancelBtn = "Скасувати",
        closeBtn = "Закрити",
        openInHexBtn = "Відкрити в HEX",
        securityBadge = "🔒 БЕЗПЕКА ТА ДОСТУП",
        formatErrorBadge = "🛠 ДІАГНОСТИКА СТРУКТУРИ ФАЙЛА",
        closedLicenseBadge = "⚖️ ОБМЕЖЕННЯ ЗАКРИТОЇ ЛІЦЕНЗІЇ",
        passwordRequiredBadge = "🔑 ПОТРІБЕН ПАРОЛЬ",
        accessRestrictedTitle = "Обмеження доступу або безпеки",
        formatReadErrorTitle = "Помилка читання структури файла",
        closedLicenseTitle = "Формат захищений закритою ліцензією",
        passwordRequiredTitle = "Документ зашифрований",
        passwordPlaceholder = "Введіть пароль",
        unlockBtn = "Відкрити",
        hexFallbackHint = "Можна безпечно переглянути сирі байти цього файла в інженерному HEX-інспекторі.",
        archiveContentsTitle = "Вміст архіву / образу",
        backButton = "← Назад",
        archiveEmptyMessage = "Архів порожній або його структуру пошкоджено",
        searchButton = "🔍 Пошук",
        closeSearchButton = "✕ Закрити",
        searchPlaceholder = "Текст або HEX (50 4B)...",
        findButton = "Знайти",
        foundOnPagePrefix = "Знайдено на стор.",
        notFoundMessage = "Нічого не знайдено",
        reset3dButton = "Скинути 3D",
        changeFileButton = "Змінити файл",
        pageLabel = "Стор.",
        preparingMedia = "Підготовка...",
        playMedia = "▶ Відтворити",
        pauseMedia = "❚❚ Пауза",
        audioLabel = "🎵 Аудіопотік",
        mediaQueue = "Черга",
        mediaAddFiles = "Додати файли",
        mediaSubtitles = "Субтитри",
        mediaSubtitlesOff = "Вимк.",
        mediaLoadSubtitles = "Завантажити файл субтитрів…",
        mediaAudioTrack = "Аудіодоріжка",
        mediaSpeed = "Швидкість",
        mediaSubtitlesFailed = "Не вдалося прочитати файл субтитрів",
        mediaClose = "Закрити",
        missingResourcesTitle = "Файл посилається на недоступні ресурси",
        attachResourcesButton = "Вказати файли…",
        legalComplianceTitle = "Про додаток / Інформація",
        serverEndpointStatus = "Сервер синхронізації підключено",
        closeDrawerButton = "Закрити меню",
        aboutLegalSectionTitle = "Про додаток / Інформація",
        frameRateLabel = "Ліміт частоти кадрів екрана",
        frameRateAutoPattern = "Максимум пристрою (%d Гц)",
        frameRate60 = "60 Гц (Економія батареї)",
        frameRate25 = "25 Гц (Максимальна економія / Читання)",
        audioQualityLabel = "Якість аудіопотоку",
        audioQualityAutoPattern = "Максимум підключеного пристрою (%s: %d кГц, %s)",
        audioQuality96 = "96 кГц / 24-біт (Студійна якість)",
        audioQuality48 = "48 кГц / 16-біт (Висока якість)",
        audioQuality44 = "44.1 кГц / 16-біт (Стандартна якість)",
        audioDeviceSpeaker = "Динамік телефона",
        audioDeviceWired = "Дротові навушники / AUX",
        audioDeviceBluetooth = "Bluetooth-навушники / Колонка",
        audioDeviceUsbDac = "USB ЦАП / Зовнішня карта",
        audioDeviceGeneric = "Аудіопристрій",
        termsOfUseLink = "1. Умови використання (Terms of Use)",
        privacyPolicyLink = "2. Політика конфіденційності (Privacy Policy)",
        thirdPartyLicensesLink = "3. Сторонні компоненти та ліцензії",
        thirdPartyLicensesDialogTitle = "Сторонні компоненти та ліцензії",
        copyActionButton = "📋 Скопіювати",
        selectAllActionButton = "Виділити все",
        slideNotesButton = "📝 Нотатки",
        bufferingCloudFile = "Буферизація файла з хмари...",
        defaultMediaTitle = "Аудіо / Відео файл",
        defaultMediaArtist = "Невідомий виконавець",
        errorDescriptorAccess = "Не вдалося отримати дескриптор файла через системний провайдер (немає прав доступу).",
        errorBufferingStreamPrefix = "Помилка буферизації потоку",
        errorSuspiciousHeader = "Підозрілу структуру заголовка файла заблоковано.",
        errorServiceTimeout = "Перевищено час очікування з'єднання з ізольованим сервісом рендерингу.",
        errorServiceReconnecting = "Втрачено з'єднання з ізольованим процесом. Відновлення...",
        errorCorruptedOrUnsupported = "Файл пошкоджено, зашифровано або цей формат не підтримується.",
        errorFileReadPrefix = "Помилка читання файла",
        errorArchiveExtractFailed = "Не вдалося безпечно витягти запис з архіву",
        errorNestedFileOpenFailed = "Не вдалося відкрити вкладений файл",
        errorArchiveReadPrefix = "Помилка читання архіву",
        errorHexOpenFailed = "Не вдалося відкрити файл у режимі HEX-інспектора.",
        errorRenderFailed = "Збій рендерингу документа.",
        retryDownloadButton = "↻ Повторити завантаження",
        openAsInBrowserButton = "🌐 Відкрити як у браузері",
        viewHtmlSourceButton = "📄 Вихідний код",
        checkForUpdatesButton = "Перевірити оновлення",
        checkingUpdatesStatus = "Перевірка оновлень...",
        appUpToDateStatus = "✓ Встановлено останню версію",
        updateAvailableTitle = "Доступне оновлення",
        updateNowButton = "Оновити",
        laterButton = "Пізніше",
        downloadingUpdateStatus = "Завантаження оновлення..."
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
        passwordPlaceholder = "输入密码",
        unlockBtn = "解锁",
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
        mediaQueue = "播放队列",
        mediaAddFiles = "添加文件",
        mediaSubtitles = "字幕",
        mediaSubtitlesOff = "关闭",
        mediaLoadSubtitles = "加载字幕文件…",
        mediaAudioTrack = "音轨",
        mediaSpeed = "速度",
        mediaSubtitlesFailed = "无法读取此字幕文件",
        mediaClose = "关闭",
        missingResourcesTitle = "该文件引用了不可用的文件",
        attachResourcesButton = "选择文件…",
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
        .removePrefix("DRM_PROTECTED:")
        .removePrefix("UNSUPPORTED_ENCRYPTION:")
        .removePrefix("PASSWORD_REQUIRED:")
        .removePrefix("CORRUPTED_PDF:")
        .trim()
    val strings = stringsForLanguage(lang)
    val lower = clean.lowercase(Locale.ROOT)

    return when {
        rawMessage.startsWith("UNSUPPORTED_ENCRYPTION") || lower.contains("not supported in this version") -> {
            val isPdfApiBelow35 = lower.contains("android 15") || lower.contains("api 35")
            val isOfficeOrOdf = lower.contains("odf") || lower.contains("office") || lower.contains("openpgp") || lower.contains("argon2") || lower.contains("aes-gcm") || lower.contains("certificate") || lower.contains("extensible")
            val isAccessPageEncrypted = lower.contains("page-encrypted") || lower.contains("access") || lower.contains(".mdb") || lower.contains(".accdb")
            val isFbxEncrypted = lower.contains("fbx")
            val isDatabaseOrOneNote = lower.contains("parquet") || lower.contains("realm") || lower.contains("duckdb") || lower.contains("onenote")
            when {
                isPdfApiBelow35 -> when (lang) {
                    AppLanguage.EN -> "Password-protected PDF decryption requires Android 15 (API 35) or newer."
                    AppLanguage.RU -> "Расшифровка защищённых паролем PDF-документов требует Android 15 (API 35) или новее."
                    AppLanguage.DE -> "Die Entschlüsselung passwortgeschützter PDF-Dokumente erfordert Android 15 (API 35) oder neuer."
                    AppLanguage.ES -> "El descifrado de documentos PDF protegidos con contraseña requiere Android 15 (API 35) o superior."
                    AppLanguage.FR -> "Le déchiffrement des documents PDF protégés par mot de passe nécessite Android 15 (API 35) ou une version ultérieure."
                    AppLanguage.PL -> "Deszyfrowanie dokumentów PDF chronionych hasłem wymaga systemu Android 15 (API 35) lub nowszego."
                    AppLanguage.UK -> "Розшифрування захищених паролем PDF-документів потребує Android 15 (API 35) або новішої версії."
                    AppLanguage.ZH -> "解密受密码保护的 PDF 文档需要 Android 15 (API 35) 或更高版本。"
                }
                isOfficeOrOdf -> when (lang) {
                    AppLanguage.EN -> "This Office/ODF document uses an unsupported encryption scheme (such as OpenPGP, Argon2id/AES-GCM, X.509 certificate, or Extensible Encryption)."
                    AppLanguage.RU -> "Данный документ Office/ODF использует неподдерживаемую схему шифрования (OpenPGP, Argon2id/AES-GCM, X.509-сертификат или Extensible Encryption)."
                    AppLanguage.DE -> "Dieses Office-/ODF-Dokument verwendet ein nicht unterstütztes Verschlüsselungsverfahren (z. B. OpenPGP, Argon2id/AES-GCM, X.509-Zertifikat oder Extensible Encryption)."
                    AppLanguage.ES -> "Este documento Office/ODF utiliza un esquema de cifrado no compatible (OpenPGP, Argon2id/AES-GCM, certificado X.509 o Extensible Encryption)."
                    AppLanguage.FR -> "Ce document Office/ODF utilise un schéma de chiffrement non pris en charge (OpenPGP, Argon2id/AES-GCM, certificat X.509 ou Extensible Encryption)."
                    AppLanguage.PL -> "Ten dokument Office/ODF używa nieobsługiwanego schematu szyfrowania (OpenPGP, Argon2id/AES-GCM, certyfikat X.509 lub Extensible Encryption)."
                    AppLanguage.UK -> "Цей документ Office/ODF використовує непідтримувану схему шифрування (OpenPGP, Argon2id/AES-GCM, X.509-сертифікат або Extensible Encryption)."
                    AppLanguage.ZH -> "此 Office/ODF 文档使用了不受支持的加密方案（如 OpenPGP、Argon2id/AES-GCM、X.509 证书或可扩展加密）。"
                }
                isAccessPageEncrypted -> when (lang) {
                    AppLanguage.EN -> "Page-encrypted Microsoft Access databases (.mdb / .accdb) are not supported. Standard header-password Access databases remain supported."
                    AppLanguage.RU -> "Постранично зашифрованные базы данных Microsoft Access (.mdb / .accdb) не поддерживаются. Базы Access со стандартным паролем заголовка поддерживаются."
                    AppLanguage.DE -> "Seitenverschlüsselte Microsoft-Access-Datenbanken (.mdb / .accdb) werden nicht unterstützt. Datenbanken mit Standard-Header-Passwort werden unterstützt."
                    AppLanguage.ES -> "Las bases de datos de Microsoft Access (.mdb / .accdb) cifradas por páginas no son compatibles."
                    AppLanguage.FR -> "Les bases de données Microsoft Access (.mdb / .accdb) chiffrées par page ne sont pas prises en charge."
                    AppLanguage.PL -> "Stronicowo zaszyfrowane bazy danych Microsoft Access (.mdb / .accdb) nie są obsługiwane."
                    AppLanguage.UK -> "Посторінково зашифровані бази даних Microsoft Access (.mdb / .accdb) не підтримуються."
                    AppLanguage.ZH -> "不支持页面加密的 Microsoft Access 数据库（.mdb / .accdb）。"
                }
                isFbxEncrypted -> when (lang) {
                    AppLanguage.EN -> "Encrypted FBX 3D files are not supported."
                    AppLanguage.RU -> "Зашифрованные 3D-файлы FBX не поддерживаются."
                    AppLanguage.DE -> "Verschlüsselte FBX-3D-Dateien werden nicht unterstützt."
                    AppLanguage.ES -> "Los archivos 3D FBX cifrados no son compatibles."
                    AppLanguage.FR -> "Les fichiers 3D FBX chiffrés ne sont pas pris en charge."
                    AppLanguage.PL -> "Zaszyfrowane pliki 3D FBX nie są obsługiwane."
                    AppLanguage.UK -> "Зашифровані 3D-файли FBX не підтримуються."
                    AppLanguage.ZH -> "不支持加密的 FBX 3D 文件。"
                }
                isDatabaseOrOneNote -> when (lang) {
                    AppLanguage.EN -> "Password decryption for this encrypted format (Parquet / Realm / DuckDB / OneNote) is not supported in this version. Encrypted 7Z, PDF, Office, DWG, and MS Access files can be unlocked with your password."
                    AppLanguage.RU -> "Расшифровка данного зашифрованного формата (Parquet / Realm / DuckDB / OneNote) не поддерживается в данной версии. Зашифрованные файлы 7Z, PDF, Office, DWG и MS Access можно открыть с вашим паролем."
                    AppLanguage.DE -> "Die Entschlüsselung dieses verschlüsselten Formats (Parquet / Realm / DuckDB / OneNote) wird in dieser Version nicht unterstützt. Verschlüsselte 7Z-, PDF-, Office-, DWG- und MS-Access-Dateien lassen sich mit Ihrem Passwort öffnen."
                    AppLanguage.ES -> "El descifrado de este formato cifrado (Parquet / Realm / DuckDB / OneNote) no es compatible en esta versión. Los archivos cifrados 7Z, PDF, Office, DWG y MS Access pueden abrirse con su contraseña."
                    AppLanguage.FR -> "Le déchiffrement de ce format chiffré (Parquet / Realm / DuckDB / OneNote) n'est pas pris en charge dans cette version. Les fichiers chiffrés 7Z, PDF, Office, DWG et MS Access peuvent être ouverts avec votre mot de passe."
                    AppLanguage.PL -> "Deszyfrowanie tego zaszyfrowanego formatu (Parquet / Realm / DuckDB / OneNote) nie jest obsługiwane w tej wersji. Zaszyfrowane pliki 7Z, PDF, Office, DWG i MS Access można otworzyć za pomocą hasła."
                    AppLanguage.UK -> "Розшифрування цього зашифрованого формату (Parquet / Realm / DuckDB / OneNote) не підтримується в цій версії. Зашифровані файли 7Z, PDF, Office, DWG та MS Access можна відкрити вашим паролем."
                    AppLanguage.ZH -> "当前版本不支持解密此加密格式（Parquet / Realm / DuckDB / OneNote）。加密的 7Z、PDF、Office、DWG 和 MS Access 文件可使用您的密码解锁。"
                }
                else -> when (lang) {
                    AppLanguage.EN -> "Password decryption for encrypted ZIP/RAR archives is not supported in this version. Encrypted 7Z, PDF, Office, DWG, and MS Access files can be unlocked with your password."
                    AppLanguage.RU -> "Расшифровка запароленных архивов ZIP и RAR не поддерживается в данной версии. Зашифрованные файлы 7Z, PDF, Office, DWG и MS Access можно открыть с вашим паролем."
                    AppLanguage.DE -> "Die Entschlüsselung passwortgeschützter ZIP-/RAR-Archive wird in dieser Version nicht unterstützt. Verschlüsselte 7Z-, PDF-, Office-, DWG- und MS-Access-Dateien lassen sich mit Ihrem Passwort öffnen."
                    AppLanguage.ES -> "El descifrado de archivos ZIP/RAR protegidos con contraseña no es compatible en esta versión. Los archivos cifrados 7Z, PDF, Office, DWG y MS Access pueden abrirse con su contraseña."
                    AppLanguage.FR -> "Le déchiffrement des archives ZIP/RAR protégées par mot de passe n'est pas pris en charge dans cette version. Les fichiers chiffrés 7Z, PDF, Office, DWG et MS Access peuvent être ouverts avec votre mot de passe."
                    AppLanguage.PL -> "Deszyfrowanie archiwów ZIP/RAR chronionych hasłem nie jest obsługiwane w tej wersji. Zaszyfrowane pliki 7Z, PDF, Office, DWG i MS Access można otworzyć za pomocą hasła."
                    AppLanguage.UK -> "Розшифрування запаролених архівів ZIP і RAR не підтримується в цій версії. Зашифровані файли 7Z, PDF, Office, DWG та MS Access можна відкрити вашим паролем."
                    AppLanguage.ZH -> "当前版本不支持解密受密码保护的 ZIP/RAR 压缩包。加密的 7Z、PDF、Office、DWG 和 MS Access 文件可使用您的密码解锁。"
                }
            }
        }
        lower.contains("media") && (lower.contains("drm") || lower.contains("dmca")) -> when (lang) {
            AppLanguage.EN -> "This media file is protected by cryptographic DRM (such as Widevine, FairPlay, or PlayReady). Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            AppLanguage.RU -> "Данный медиафайл защищён криптографической DRM-защитой (Widevine, FairPlay или PlayReady). Обход технических средств защиты авторских прав запрещён законом (DMCA § 1201 / Директива ЕС InfoSoc)."
            AppLanguage.DE -> "Diese Mediendatei ist durch kryptografisches DRM (z. B. Widevine, FairPlay oder PlayReady) geschützt. Das Umgehen technischer Schutzmaßnahmen (TPM/DRM) ist gesetzlich untersagt (DMCA § 1201 / EU-InfoSoc-Richtlinie)."
            AppLanguage.ES -> "Este archivo multimedia está protegido por DRM criptográfico (Widevine, FairPlay o PlayReady). La elusión de medidas tecnológicas de protección está prohibida por la ley (DMCA § 1201 / Directiva InfoSoc de la UE)."
            AppLanguage.FR -> "Ce fichier multimédia est protégé par un verrou numérique DRM (Widevine, FairPlay ou PlayReady). Le contournement des mesures techniques de protection est interdit par la loi (DMCA § 1201 / Directive européenne InfoSoc)."
            AppLanguage.PL -> "Ten plik multimedialny jest chroniony kryptograficznym systemem DRM (Widevine, FairPlay lub PlayReady). Obchodzenie technicznych zabezpieczeń (TPM/DRM) jest zabronione przez prawo (DMCA § 1201 / Dyrektywa UE InfoSoc)."
            AppLanguage.UK -> "Цей медіафайл захищений криптографічним DRM-захистом (Widevine, FairPlay або PlayReady). Обхід технічних засобів захисту заборонений законом (DMCA § 1201 / Директива ЄС InfoSoc)."
            AppLanguage.ZH -> "此媒体文件受加密数字版权管理 (DRM)（如 Widevine、FairPlay 或 PlayReady）保护。根据 DMCA § 1201 和欧盟 InfoSoc 指令，禁止绕过技术保护措施。"
        }
        lower.contains("pdf") && (lower.contains("drm") || lower.contains("dmca")) -> when (lang) {
            AppLanguage.EN -> "This PDF document is protected by cryptographic DRM (such as Adobe ADEPT, LiveCycle, FileOpen, or Microsoft RMS). Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            AppLanguage.RU -> "Данный PDF-документ защищён криптографической DRM-защитой (Adobe ADEPT, LiveCycle, FileOpen или Microsoft RMS). Обход технических средств защиты авторских прав запрещён законом (DMCA § 1201 / Директива ЕС InfoSoc)."
            AppLanguage.DE -> "Dieses PDF-Dokument ist durch kryptografisches DRM (z. B. Adobe ADEPT, LiveCycle, FileOpen oder Microsoft RMS) geschützt. Das Umgehen technischer Schutzmaßnahmen (TPM/DRM) ist gesetzlich untersagt (DMCA § 1201 / EU-InfoSoc-Richtlinie)."
            AppLanguage.ES -> "Este documento PDF está protegido por DRM criptográfico (Adobe ADEPT, LiveCycle, FileOpen o Microsoft RMS). La elusión de medidas tecnológicas de protección está prohibida por la ley (DMCA § 1201 / Directiva InfoSoc de la UE)."
            AppLanguage.FR -> "Ce document PDF est protégé par un verrou numérique DRM (Adobe ADEPT, LiveCycle, FileOpen ou Microsoft RMS). Le contournement des mesures techniques de protection est interdit par la loi (DMCA § 1201 / Directive européenne InfoSoc)."
            AppLanguage.PL -> "Ten dokument PDF jest chroniony kryptograficznym systemem DRM (Adobe ADEPT, LiveCycle, FileOpen lub Microsoft RMS). Obchodzenie technicznych zabezpieczeń (TPM/DRM) jest zabronione przez prawo (DMCA § 1201 / Dyrektywa UE InfoSoc)."
            AppLanguage.UK -> "Цей PDF-документ захищений криптографічним DRM-захистом (Adobe ADEPT, LiveCycle, FileOpen або Microsoft RMS). Обхід технічних засобів захисту заборонений законом (DMCA § 1201 / Директива ЄС InfoSoc)."
            AppLanguage.ZH -> "此 PDF 文档受加密数字版权管理 (DRM)（如 Adobe ADEPT、LiveCycle、FileOpen 或 Microsoft RMS）保护。根据 DMCA § 1201 和欧盟 InfoSoc 指令，禁止绕过技术保护措施。"
        }
        lower.contains("epub") && (lower.contains("drm") || lower.contains("dmca")) -> when (lang) {
            AppLanguage.EN -> "This EPUB e-book is protected by cryptographic DRM (such as Adobe ADEPT, Readium LCP, or FairPlay). Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            AppLanguage.RU -> "Данная книга EPUB защищена криптографической DRM-защитой (Adobe ADEPT, Readium LCP или FairPlay). Обход технических средств защиты авторских прав запрещён законом (DMCA § 1201 / Директива ЕС InfoSoc)."
            AppLanguage.DE -> "Dieses EPUB-E-Book ist durch kryptografisches DRM (z. B. Adobe ADEPT, Readium LCP oder FairPlay) geschützt. Das Umgehen technischer Schutzmaßnahmen (TPM/DRM) ist gesetzlich untersagt (DMCA § 1201 / EU-InfoSoc-Richtlinie)."
            AppLanguage.ES -> "Este libro EPUB está protegido por DRM criptográfico (Adobe ADEPT, Readium LCP o FairPlay). La elusión de medidas tecnológicas de protección está prohibida por la ley (DMCA § 1201 / Directiva InfoSoc de la UE)."
            AppLanguage.FR -> "Ce livre EPUB est protégé par un verrou numérique DRM (Adobe ADEPT, Readium LCP ou FairPlay). Le contournement des mesures techniques de protection est interdit par la loi (DMCA § 1201 / Directive européenne InfoSoc)."
            AppLanguage.PL -> "Ten e-book EPUB jest chroniony kryptograficznym systemem DRM (np. Adobe ADEPT, Readium LCP lub FairPlay). Obchodzenie technicznych zabezpieczeń (TPM/DRM) jest zabronione przez prawo (DMCA § 1201 / Dyrektywa UE InfoSoc)."
            AppLanguage.UK -> "Ця електронна книга EPUB захищена криптографічною DRM-захистом (Adobe ADEPT, Readium LCP або FairPlay). Обхід технічних засобів захисту заборонений законом (DMCA § 1201 / Директива ЄС InfoSoc)."
            AppLanguage.ZH -> "此 EPUB 电子书受加密数字版权管理 (DRM)（如 Adobe ADEPT、Readium LCP 或 FairPlay）保护。根据 DMCA § 1201 和欧盟 InfoSoc 指令，禁止绕过技术保护措施。"
        }
        (lower.contains("irm") || lower.contains("rights management")) && (lower.contains("drm") || lower.contains("dmca")) -> when (lang) {
            AppLanguage.EN -> "This Microsoft Office document is protected by Information Rights Management (IRM/RMS) DRM. Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            AppLanguage.RU -> "Данный документ Microsoft Office защищён системой управления правами (IRM/RMS DRM). Обход технических средств защиты авторских прав запрещён законом (DMCA § 1201 / Директива ЕС InfoSoc)."
            AppLanguage.DE -> "Dieses Microsoft-Office-Dokument ist durch Information Rights Management (IRM/RMS-DRM) geschützt. Das Umgehen technischer Schutzmaßnahmen (TPM/DRM) ist gesetzlich untersagt (DMCA § 1201 / EU-InfoSoc-Richtlinie)."
            AppLanguage.ES -> "Este documento de Microsoft Office está protegido por DRM de Information Rights Management (IRM/RMS). La elusión de medidas tecnológicas de protección está prohibida por la ley (DMCA § 1201 / Directiva InfoSoc de la UE)."
            AppLanguage.FR -> "Ce document Microsoft Office est protégé par le système DRM Information Rights Management (IRM/RMS). Le contournement des mesures techniques de protection est interdit par la loi (DMCA § 1201 / Directive européenne InfoSoc)."
            AppLanguage.PL -> "Ten dokument Microsoft Office jest chroniony systemem DRM Information Rights Management (IRM/RMS). Obchodzenie technicznych zabezpieczeń (TPM/DRM) jest zabronione przez prawo (DMCA § 1201 / Dyrektywa UE InfoSoc)."
            AppLanguage.UK -> "Цей документ Microsoft Office захищений системою керування правами (IRM/RMS DRM). Обхід технічних засобів захисту заборонений законом (DMCA § 1201 / Директива ЄС InfoSoc)."
            AppLanguage.ZH -> "此 Microsoft Office 文档受信息权限管理 (IRM/RMS) DRM 保护。根据 DMCA § 1201 和欧盟 InfoSoc 指令，禁止绕过技术保护措施。"
        }
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
        lower.contains("incorrect password") -> when (lang) {
            AppLanguage.EN -> "Incorrect password. Please try again."
            AppLanguage.RU -> "Неверный пароль. Попробуйте ещё раз."
            AppLanguage.DE -> "Falsches Passwort. Bitte versuchen Sie es erneut."
            AppLanguage.ES -> "Contraseña incorrecta. Inténtelo de nuevo."
            AppLanguage.FR -> "Mot de passe incorrect. Veuillez réessayer."
            AppLanguage.PL -> "Nieprawidłowe hasło. Spróbuj ponownie."
            AppLanguage.UK -> "Неправильний пароль. Попробуйте іще раз."
            AppLanguage.ZH -> "密码不正确，请重试。"
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
