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

package me.hlebkukleuski.fullfilestudio.ui.reader

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.hlebkukleuski.fullfilestudio.bootstrap.FirstLaunchEngineBootstrapper
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileSignatureDetector
import me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService
import me.hlebkukleuski.fullfilestudio.core.engine.LegalLicensePolicy
import me.hlebkukleuski.fullfilestudio.core.security.MaliciousHeaderSanitizer
import me.hlebkukleuski.fullfilestudio.di.AppContainer
import me.hlebkukleuski.fullfilestudio.ui.i18n.AppStrings
import me.hlebkukleuski.fullfilestudio.ui.i18n.stringsForLanguage

/**
 * Pure Unidirectional Data Flow (UDF) [ViewModel] without any `Application` reference.
 * Persists URI, active page, and active spreadsheet sheet across OS process death via [SavedStateHandle],
 * and coordinates isolated IPC rendering through [AppContainer] and [IsolatedRenderClient].
 */
class ReaderViewModel(
    private val savedStateHandle: SavedStateHandle,
    val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel(), ProxySessionHost {

    val bootstrapper: FirstLaunchEngineBootstrapper
        get() = container.bootstrapper

    private val intentResolver: DocumentIntentResolver
        get() = container.documentIntentResolver

    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            lastOpenedUri = savedStateHandle.get<String>(KEY_LAST_OPENED_URI)?.let(Uri::parse),
            savedPageIndex = (savedStateHandle.get<Int>(KEY_SAVED_PAGE_INDEX) ?: 0).coerceAtLeast(0),
            savedSheetIndex = (savedStateHandle.get<Int>(KEY_SAVED_SHEET_INDEX) ?: 0).coerceAtLeast(0)
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val sessionHistoryStack = mutableListOf<SessionStateFrame>()
    private var lastVirtualHexPfd: ParcelFileDescriptor? = null
    private var openJob: Job? = null

    private val renderClient: IsolatedRenderClient = container.createIsolatedRenderClient {
        currentStrings().errorServiceTimeout
    }

    init {
        renderClient.setConnectionListener(
            object : IsolatedRenderClient.ConnectionListener {
                override fun onConnected(service: IIsolatedEngineService) {
                    val activeProxy = _uiState.value.currentSession as? ProxyDocumentSession
                    if (activeProxy != null) {
                        viewModelScope.launch(ioDispatcher) {
                            activeProxy.onServiceReconnected(service)
                        }
                    }
                }

                override fun onDisconnected() {
                    val activeProxy = _uiState.value.currentSession as? ProxyDocumentSession
                    activeProxy?.onServiceDisconnected()
                }
            }
        )
    }

    fun dispatch(action: ReaderUiAction) {
        when (action) {
            is ReaderUiAction.OpenUri -> openUriWithSafeFallback(action.uri, action.password, isRestoring = false)
            is ReaderUiAction.OpenArchiveEntry -> openVirtualFileFromArchive(action.entryName)
            is ReaderUiAction.OpenHexFallback -> openHexFallback(action.uri)
            ReaderUiAction.NavigateBack -> popSessionHistory()
            ReaderUiAction.DismissError -> dismissStructuredError()
            is ReaderUiAction.PageChanged -> updateSavedPageIndex(action.pageIndex)
            is ReaderUiAction.SheetChanged -> updateSavedSheetIndex(action.sheetIndex)
        }
    }

    private fun updateSavedPageIndex(pageIndex: Int) {
        val safePage = pageIndex.coerceAtLeast(0)
        savedStateHandle[KEY_SAVED_PAGE_INDEX] = safePage
        _uiState.update { state ->
            if (state.savedPageIndex == safePage) state else state.copy(savedPageIndex = safePage)
        }
    }

    private fun updateSavedSheetIndex(sheetIndex: Int) {
        val safeSheet = sheetIndex.coerceAtLeast(0)
        savedStateHandle[KEY_SAVED_SHEET_INDEX] = safeSheet
        _uiState.update { state ->
            if (state.savedSheetIndex == safeSheet) state else state.copy(savedSheetIndex = safeSheet)
        }
    }

    internal fun currentStrings(): AppStrings =
        stringsForLanguage(bootstrapper.settingsManager.language.value)

    override fun serviceReconnectingMessage(): String = currentStrings().errorServiceReconnecting

    override fun peekIsolatedService(): IIsolatedEngineService? = renderClient.peekService()

    override suspend fun getIsolatedService(): IIsolatedEngineService = renderClient.getService()

    override fun launchInScope(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(ioDispatcher, block = block)
    }

    override fun launchImmediateMain(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(mainDispatcher, block = block)
    }

    override fun dupVirtualHexBackupPfd(): ParcelFileDescriptor? {
        return try {
            lastVirtualHexPfd?.let { ParcelFileDescriptor.dup(it.fileDescriptor) }
        } catch (_: Throwable) {
            null
        }
    }

    override fun updateActiveSessionId(newId: Int) {
        _uiState.update { it.copy(activeSessionId = newId) }
    }

    fun ensureServiceBound() {
        renderClient.ensureBound()
    }

    fun unbindServiceSafely() {
        renderClient.unbindSafely()
    }

    fun restoreSavedPendingState(fallbackUri: Uri? = null) {
        val candidateUri = savedStateHandle.get<String>(KEY_LAST_OPENED_URI)?.let(Uri::parse) ?: fallbackUri
        if (candidateUri != null &&
            candidateUri.scheme != "virtual" &&
            _uiState.value.currentSession == null &&
            openJob?.isActive != true
        ) {
            openUriWithSafeFallback(candidateUri, password = null, isRestoring = true)
        }
    }

    fun dismissStructuredError() {
        _uiState.update { it.copy(structuredError = null, pendingUri = null) }
    }

    private fun closeSessionAsync(session: DocumentSession?) {
        if (session == null) return
        cleanupScope.launch {
            try { session.close() } catch (_: Throwable) {}
        }
    }

    private fun replaceVirtualHexPfd(newPfd: ParcelFileDescriptor?) {
        val old = lastVirtualHexPfd
        lastVirtualHexPfd = newPfd
        if (old != null && old != newPfd) {
            try { old.close() } catch (_: Throwable) {}
        }
        _uiState.update { it.copy(hasVirtualHexBackup = newPfd != null) }
    }

    fun popSessionHistory() {
        openJob?.cancel()
        val current = _uiState.value.currentSession
        if (sessionHistoryStack.isNotEmpty()) {
            val lastFrame = sessionHistoryStack.removeAt(sessionHistoryStack.lastIndex)
            if (current != null && current != lastFrame.session) {
                closeSessionAsync(current)
            }
            savedStateHandle[KEY_SAVED_PAGE_INDEX] = lastFrame.savedPageIndex
            savedStateHandle[KEY_SAVED_SHEET_INDEX] = lastFrame.savedSheetIndex
            _uiState.update {
                it.copy(
                    currentSession = lastFrame.session,
                    isArchiveSession = lastFrame.isArchive,
                    archiveEntries = lastFrame.archiveEntries,
                    activeSessionId = lastFrame.activeSessionId,
                    hasSessionHistory = sessionHistoryStack.isNotEmpty(),
                    savedPageIndex = lastFrame.savedPageIndex,
                    savedSheetIndex = lastFrame.savedSheetIndex
                )
            }
        } else {
            closeSessionAsync(current)
            savedStateHandle[KEY_LAST_OPENED_URI] = null
            savedStateHandle[KEY_SAVED_PAGE_INDEX] = 0
            savedStateHandle[KEY_SAVED_SHEET_INDEX] = 0
            _uiState.update {
                it.copy(
                    currentSession = null,
                    isArchiveSession = false,
                    archiveEntries = emptyList(),
                    activeSessionId = -1,
                    hasSessionHistory = false,
                    lastOpenedUri = null,
                    savedPageIndex = 0,
                    savedSheetIndex = 0
                )
            }
        }
    }

    override fun openPfd(uri: Uri): ParcelFileDescriptor? {
        return intentResolver.openSeekableDescriptor(
            uri = uri,
            virtualDupProvider = ::dupVirtualHexBackupPfd,
            bufferingErrorPrefix = currentStrings().errorBufferingStreamPrefix,
            onBufferingProgress = { progress ->
                val normalizedProgress = if (progress < 0f) 0.5f else progress.coerceIn(0f, 1f)
                viewModelScope.launch(mainDispatcher) {
                    if (progress < 1.0f) {
                        _uiState.update {
                            it.copy(isBufferingFile = true, bufferingProgress = normalizedProgress)
                        }
                    } else {
                        _uiState.update {
                            it.copy(isBufferingFile = false, bufferingProgress = 1.0f)
                        }
                    }
                }
            }
        )
    }

    private fun classifyError(rawMessage: String, hasReadableDescriptor: Boolean = true): StructuredUiError {
        return when {
            rawMessage.startsWith("DRM_PROTECTED") -> StructuredUiError(
                category = UiErrorCategory.CLOSED_LICENSE,
                message = rawMessage.removePrefix("DRM_PROTECTED:").trim(),
                allowHexFallback = false
            )
            rawMessage.startsWith("UNSUPPORTED_ENCRYPTION") -> StructuredUiError(
                category = UiErrorCategory.ACCESS_OR_SECURITY,
                message = rawMessage.removePrefix("UNSUPPORTED_ENCRYPTION:").trim(),
                allowHexFallback = false
            )
            rawMessage.startsWith("PASSWORD_REQUIRED") -> StructuredUiError(
                category = UiErrorCategory.PASSWORD_REQUIRED,
                message = rawMessage.removePrefix("PASSWORD_REQUIRED:").trim(),
                allowHexFallback = false
            )
            rawMessage.contains("права", ignoreCase = true) ||
                rawMessage.contains("permission", ignoreCase = true) ||
                rawMessage.contains("EACCES", ignoreCase = true) ||
                rawMessage.contains("not permitted", ignoreCase = true) -> StructuredUiError(
                category = UiErrorCategory.ACCESS_OR_SECURITY,
                message = rawMessage,
                allowHexFallback = hasReadableDescriptor
            )
            else -> StructuredUiError(
                category = UiErrorCategory.FORMAT_STRUCTURE,
                message = rawMessage.removePrefix("CORRUPTED_PDF:").trim(),
                allowHexFallback = hasReadableDescriptor
            )
        }
    }

    fun openUriWithSafeFallback(uri: Uri, password: String? = null, isRestoring: Boolean = false) {
        openJob?.cancel()
        val prevSession = _uiState.value.currentSession
        closeSessionAsync(prevSession)
        sessionHistoryStack.forEach { closeSessionAsync(it.session) }
        sessionHistoryStack.clear()
        if (uri.scheme != "virtual") {
            replaceVirtualHexPfd(null)
            savedStateHandle[KEY_LAST_OPENED_URI] = uri.toString()
        }
        val targetPage = if (isRestoring) _uiState.value.savedPageIndex else 0
        val targetSheet = if (isRestoring) _uiState.value.savedSheetIndex else 0
        if (!isRestoring) {
            savedStateHandle[KEY_SAVED_PAGE_INDEX] = 0
            savedStateHandle[KEY_SAVED_SHEET_INDEX] = 0
        }

        _uiState.update {
            it.copy(
                currentSession = null,
                isArchiveSession = false,
                archiveEntries = emptyList(),
                activeSessionId = -1,
                hasSessionHistory = false,
                pendingUri = uri,
                lastOpenedUri = uri,
                structuredError = null,
                savedPageIndex = targetPage,
                savedSheetIndex = targetSheet
            )
        }

        openJob = viewModelScope.launch(ioDispatcher) {
            var openedRemoteSessionId = -1
            var createdSession: DocumentSession? = null
            try {
                val ext = intentResolver.extractExtension(uri)
                val pfd = openPfd(uri)
                if (pfd == null) {
                    val str = currentStrings()
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isBufferingFile = false,
                                structuredError = StructuredUiError(
                                    category = UiErrorCategory.ACCESS_OR_SECURITY,
                                    message = str.errorDescriptorAccess,
                                    allowHexFallback = false
                                )
                            )
                        }
                    }
                    return@launch
                }

                pfd.use {
                    withContext(mainDispatcher) {
                        _uiState.update { state -> state.copy(isBufferingFile = false) }
                    }

                    val hexBackupDup = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                    if (hexBackupDup != null) {
                        replaceVirtualHexPfd(hexBackupDup)
                    }

                    val header = ByteBuffer.allocateDirect(4096)
                    val size = try { Os.fstat(pfd.fileDescriptor).st_size } catch (_: Exception) { -1L }
                    val read = try {
                        try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                        Os.read(pfd.fileDescriptor, header)
                    } catch (_: Exception) { 0 }
                    header.flip()
                    val sig = ByteArray(read.coerceAtLeast(0))
                    if (read > 0) header.get(sig, 0, sig.size)
                    header.position(0)

                    val format = FileSignatureDetector.resolveFormat(pfd.fileDescriptor, header, ext, size)
                    val effectiveExt = intentResolver.resolveEffectiveExtension(format, ext)

                    val legalVerdict = LegalLicensePolicy.evaluate(
                        format = format,
                        extension = effectiveExt,
                        isDrmEncrypted = (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX)
                    )
                    if (!legalVerdict.isLegallyPermitted) {
                        replaceVirtualHexPfd(null)
                        withContext(mainDispatcher) {
                            _uiState.update { state ->
                                state.copy(
                                    structuredError = StructuredUiError(
                                        category = UiErrorCategory.CLOSED_LICENSE,
                                        message = legalVerdict.explanation,
                                        allowHexFallback = false
                                    )
                                )
                            }
                        }
                        return@launch
                    }

                    val securityReport = MaliciousHeaderSanitizer.inspect(header, format)
                    if (!securityReport.isSafe) {
                        val str = currentStrings()
                        val threat = securityReport.threatDescription ?: str.errorSuspiciousHeader
                        val isExecutableBinary = format == DetectedFormat.ELF ||
                            format == DetectedFormat.DEX ||
                            format == DetectedFormat.APK ||
                            threat.contains("Executable", ignoreCase = true) ||
                            threat.contains("PE/Mach-O", ignoreCase = true)
                        if (isExecutableBinary) {
                            replaceVirtualHexPfd(null)
                        }
                        withContext(mainDispatcher) {
                            _uiState.update { state ->
                                state.copy(
                                    structuredError = StructuredUiError(
                                        category = UiErrorCategory.ACCESS_OR_SECURITY,
                                        message = threat,
                                        allowHexFallback = !isExecutableBinary
                                    )
                                )
                            }
                        }
                        return@launch
                    }

                    val (isMedia, isHostDecodedImage) = container.classifyHostOrMediaFormat(format, effectiveExt, sig)
                    var isRealArchive = false
                    var newEntries: List<String> = emptyList()

                    val session: DocumentSession? = if (isMedia || isHostDecodedImage) {
                        container.createHostOrMediaSession(
                            uri = uri,
                            sourcePfd = pfd,
                            format = format,
                            effectiveExt = effectiveExt,
                            isMedia = isMedia,
                            isHostDecodedImage = isHostDecodedImage,
                            allowMediaReuse = true
                        )
                    } else {
                        val str = currentStrings()
                        val svc = withTimeoutOrNull(8.seconds) { getIsolatedService() }
                            ?: throw IllegalStateException(str.errorServiceTimeout)

                        val detachedDupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val sessionId = if (!password.isNullOrEmpty()) {
                            svc.openSessionWithPassword(detachedDupPfd, effectiveExt, password)
                        } else {
                            svc.openSession(detachedDupPfd, effectiveExt)
                        }

                        if (sessionId >= 0) {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            openedRemoteSessionId = sessionId
                            coroutineContext.ensureActive()
                            val is3d = try { svc.is3dSession(sessionId) } catch (_: Exception) { false }
                            val arcRes = try { svc.isArchiveSession(sessionId) } catch (_: Exception) { false }
                            isRealArchive = arcRes

                            newEntries = if (isRealArchive) {
                                try { svc.getArchiveEntries(sessionId).toList() } catch (_: Exception) { emptyList() }
                            } else emptyList()

                            val hostDupPfd = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                            ProxyDocumentSession(
                                controller = this@ReaderViewModel,
                                sessionId = sessionId,
                                uri = uri,
                                extension = effectiveExt,
                                is3dContent = is3d,
                                hostPfd = hostDupPfd,
                                sessionPassword = password
                            )
                        } else {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            null
                        }
                    }

                    createdSession = session
                    coroutineContext.ensureActive()

                    if (session != null) {
                        if (isRestoring && targetSheet > 0) {
                            try { session.setActiveSheetIndex(targetSheet) } catch (_: Throwable) {}
                        }
                        val immediateError = session.getLastError()
                        withContext(mainDispatcher) {
                            if (immediateError != null && (!isRealArchive || newEntries.isEmpty())) {
                                val classified = classifyError(immediateError, hasReadableDescriptor = true)
                                if (!classified.allowHexFallback) {
                                    replaceVirtualHexPfd(null)
                                }
                                closeSessionAsync(session)
                                createdSession = null
                                openedRemoteSessionId = -1
                                _uiState.update { state ->
                                    state.copy(
                                        isArchiveSession = false,
                                        archiveEntries = emptyList(),
                                        structuredError = classified
                                    )
                                }
                            } else {
                                _uiState.update { state ->
                                    state.copy(
                                        activeSessionId = openedRemoteSessionId,
                                        isArchiveSession = isRealArchive,
                                        archiveEntries = newEntries,
                                        pendingUri = null,
                                        currentSession = session
                                    )
                                }
                                createdSession = null
                                openedRemoteSessionId = -1
                            }
                        }
                    } else {
                        val str = currentStrings()
                        withContext(mainDispatcher) {
                            _uiState.update { state ->
                                state.copy(
                                    structuredError = StructuredUiError(
                                        category = UiErrorCategory.FORMAT_STRUCTURE,
                                        message = str.errorCorruptedOrUnsupported,
                                        allowHexFallback = true
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(mainDispatcher) {
                    _uiState.update { state -> state.copy(isBufferingFile = false) }
                }
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    _uiState.update { state -> state.copy(isBufferingFile = false) }
                }
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                val str = currentStrings()
                val hasBackupPfd = lastVirtualHexPfd != null && e !is SecurityException
                if (e is SecurityException) {
                    replaceVirtualHexPfd(null)
                }
                withContext(mainDispatcher) {
                    _uiState.update { state ->
                        state.copy(
                            structuredError = classifyError(
                                rawMessage = "${str.errorFileReadPrefix}: ${e.localizedMessage ?: str.errorCorruptedOrUnsupported}",
                                hasReadableDescriptor = hasBackupPfd
                            )
                        )
                    }
                }
            }
        }
    }

    private fun extractCleanArchiveEntryName(entryName: String): String {
        return entryName.replace(DocumentIntentResolver.ARCHIVE_SIZE_SUFFIX_REGEX, "").trim()
    }

    private fun resolveArchiveCompanions(svc: IIsolatedEngineService, archiveId: Int, entry: String, proxy: DocumentSession) {
        val entries = try { svc.getArchiveEntries(archiveId)?.map { extractCleanArchiveEntryName(it) } } catch (_: Throwable) { null } ?: return
        val byPath = entries.associateBy { it.trimStart('/').lowercase(Locale.ROOT) }
        val byBase = entries.groupBy { it.substringAfterLast('/').lowercase(Locale.ROOT) }
        val dir = entry.substringBeforeLast('/', "")
        fun join(ref: String): String {
            val parts = ArrayList<String>()
            val combined = if (ref.startsWith("/")) ref else if (dir.isEmpty()) ref else "$dir/$ref"
            for (seg in combined.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/").lowercase(Locale.ROOT)
        }
        val tried = HashSet<String>()
        repeat(8) {
            val missing = try { proxy.getMissingResources() } catch (_: Throwable) { emptyList() }
            var provided = false
            for (ref in missing) {
                if (!tried.add(ref)) continue
                val hit = byPath[join(ref)] ?: byBase[ref.substringAfterLast('/').lowercase(Locale.ROOT)]?.firstOrNull() ?: continue
                val vfd = try { svc.openVirtualFile(archiveId, hit) } catch (_: Throwable) { null } ?: continue
                vfd.use { if (proxy.provideResource(ref, it.fileDescriptor)) provided = true }
            }
            if (!provided) return
            proxy.applyProvidedResources()
        }
    }

    fun openVirtualFileFromArchive(entryName: String) {
        val parentSessionId = _uiState.value.activeSessionId
        if (parentSessionId < 0) return
        openJob?.cancel()
        openJob = viewModelScope.launch(ioDispatcher) {
            var openedRemoteSessionId = -1
            var createdSession: DocumentSession? = null
            try {
                val str = currentStrings()
                val svc = withTimeoutOrNull(8.seconds) { getIsolatedService() }
                    ?: throw IllegalStateException(str.errorServiceTimeout)
                val cleanName = extractCleanArchiveEntryName(entryName)
                val vFd = svc.openVirtualFile(parentSessionId, cleanName)

                if (vFd == null) {
                    val parentErr = try { svc.getLastError(parentSessionId) } catch (_: Throwable) { null }
                    val classified = if (!parentErr.isNullOrBlank()) {
                        classifyError(parentErr, hasReadableDescriptor = false)
                    } else {
                        StructuredUiError(
                            category = UiErrorCategory.FORMAT_STRUCTURE,
                            message = "${str.errorArchiveExtractFailed}: «$cleanName».",
                            allowHexFallback = false
                        )
                    }
                    withContext(mainDispatcher) {
                        _uiState.update { state -> state.copy(structuredError = classified) }
                    }
                    return@launch
                }

                vFd.use { pfd ->
                    val hexBackupPfd = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                    replaceVirtualHexPfd(hexBackupPfd)
                    val virtualUri = Uri.parse("virtual://${Uri.encode(cleanName)}")

                    val fileNameOnly = cleanName.substringAfterLast('/')
                    val rawExt = intentResolver.extractExtensionFromFileName(fileNameOnly)
                    val header = ByteBuffer.allocateDirect(4096)
                    val size = try { Os.fstat(pfd.fileDescriptor).st_size } catch (_: Exception) { -1L }
                    val read = try {
                        try { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                        Os.read(pfd.fileDescriptor, header)
                    } catch (_: Exception) { 0 }
                    header.flip()
                    val sig = ByteArray(read.coerceAtLeast(0))
                    if (read > 0) header.get(sig, 0, sig.size)
                    header.position(0)

                    val format = FileSignatureDetector.resolveFormat(pfd.fileDescriptor, header, rawExt, size)
                    val effectiveExt = intentResolver.resolveEffectiveExtension(format, rawExt)

                    val legalVerdict = LegalLicensePolicy.evaluate(
                        format = format,
                        extension = effectiveExt,
                        isDrmEncrypted = (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX)
                    )
                    if (!legalVerdict.isLegallyPermitted) {
                        replaceVirtualHexPfd(null)
                        withContext(mainDispatcher) {
                            _uiState.update { state ->
                                state.copy(
                                    pendingUri = virtualUri,
                                    structuredError = StructuredUiError(
                                        category = UiErrorCategory.CLOSED_LICENSE,
                                        message = legalVerdict.explanation,
                                        allowHexFallback = false
                                    )
                                )
                            }
                        }
                        return@launch
                    }

                    val securityReport = MaliciousHeaderSanitizer.inspect(header, format)
                    if (!securityReport.isSafe) {
                        val threat = securityReport.threatDescription ?: str.errorSuspiciousHeader
                        val isExecutableBinary = format == DetectedFormat.ELF ||
                            format == DetectedFormat.DEX ||
                            format == DetectedFormat.APK ||
                            threat.contains("Executable", ignoreCase = true) ||
                            threat.contains("PE/Mach-O", ignoreCase = true)
                        if (isExecutableBinary) {
                            replaceVirtualHexPfd(null)
                        }
                        withContext(mainDispatcher) {
                            _uiState.update { state ->
                                state.copy(
                                    pendingUri = virtualUri,
                                    structuredError = StructuredUiError(
                                        category = UiErrorCategory.ACCESS_OR_SECURITY,
                                        message = threat,
                                        allowHexFallback = !isExecutableBinary && hexBackupPfd != null
                                    )
                                )
                            }
                        }
                        return@launch
                    }

                    val (isMedia, isHostDecodedImage) = container.classifyHostOrMediaFormat(format, effectiveExt, sig)

                    if (isMedia || isHostDecodedImage) {
                        val mediaSession = container.createHostOrMediaSession(
                            uri = virtualUri,
                            sourcePfd = pfd,
                            format = format,
                            effectiveExt = effectiveExt,
                            isMedia = isMedia,
                            isHostDecodedImage = isHostDecodedImage,
                            allowMediaReuse = false
                        )
                        createdSession = mediaSession
                        val mediaErr = mediaSession.getLastError()
                        coroutineContext.ensureActive()
                        withContext(mainDispatcher) {
                            if (!mediaErr.isNullOrBlank()) {
                                val classified = classifyError(mediaErr, hasReadableDescriptor = hexBackupPfd != null)
                                if (!classified.allowHexFallback) {
                                    replaceVirtualHexPfd(null)
                                }
                                closeSessionAsync(mediaSession)
                                createdSession = null
                                _uiState.update { state ->
                                    state.copy(
                                        pendingUri = virtualUri,
                                        structuredError = classified
                                    )
                                }
                            } else {
                                val snap = _uiState.value
                                val curr = snap.currentSession
                                if (curr != null) {
                                    sessionHistoryStack.add(
                                        SessionStateFrame(
                                            session = curr,
                                            isArchive = snap.isArchiveSession,
                                            archiveEntries = snap.archiveEntries,
                                            activeSessionId = parentSessionId,
                                            savedPageIndex = snap.savedPageIndex,
                                            savedSheetIndex = snap.savedSheetIndex
                                        )
                                    )
                                }
                                savedStateHandle[KEY_SAVED_PAGE_INDEX] = 0
                                savedStateHandle[KEY_SAVED_SHEET_INDEX] = 0
                                _uiState.update { state ->
                                    state.copy(
                                        lastOpenedUri = virtualUri,
                                        pendingUri = null,
                                        isArchiveSession = false,
                                        archiveEntries = emptyList(),
                                        activeSessionId = -1,
                                        currentSession = mediaSession,
                                        hasSessionHistory = sessionHistoryStack.isNotEmpty(),
                                        savedPageIndex = 0,
                                        savedSheetIndex = 0
                                    )
                                }
                                createdSession = null
                            }
                        }
                    } else {
                        val detachedDupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val newSessionId = svc.openSession(detachedDupPfd, effectiveExt)

                        if (newSessionId >= 0) {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            openedRemoteSessionId = newSessionId
                            coroutineContext.ensureActive()
                            val is3d = try { svc.is3dSession(newSessionId) } catch (_: Exception) { false }
                            val arcRes = try { svc.isArchiveSession(newSessionId) } catch (_: Exception) { false }
                            val newEntries = if (arcRes) {
                                try { svc.getArchiveEntries(newSessionId).toList() } catch (_: Exception) { emptyList() }
                            } else emptyList()

                            val hostDupPfd = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                            val proxy = ProxyDocumentSession(
                                controller = this@ReaderViewModel,
                                sessionId = newSessionId,
                                uri = virtualUri,
                                extension = effectiveExt,
                                is3dContent = is3d,
                                hostPfd = hostDupPfd
                            )
                            createdSession = proxy
                            if (is3d) resolveArchiveCompanions(svc, parentSessionId, cleanName, proxy)
                            val err = proxy.getLastError()
                            coroutineContext.ensureActive()

                            withContext(mainDispatcher) {
                                if (err != null && (!arcRes || newEntries.isEmpty())) {
                                    val classified = classifyError(err, hasReadableDescriptor = hexBackupPfd != null)
                                    if (!classified.allowHexFallback) {
                                        replaceVirtualHexPfd(null)
                                    }
                                    closeSessionAsync(proxy)
                                    createdSession = null
                                    openedRemoteSessionId = -1
                                    _uiState.update { state ->
                                        state.copy(
                                            pendingUri = virtualUri,
                                            structuredError = classified
                                        )
                                    }
                                } else {
                                    val snap = _uiState.value
                                    val curr = snap.currentSession
                                    if (curr != null) {
                                        sessionHistoryStack.add(
                                            SessionStateFrame(
                                                session = curr,
                                                isArchive = snap.isArchiveSession,
                                                archiveEntries = snap.archiveEntries,
                                                activeSessionId = parentSessionId,
                                                savedPageIndex = snap.savedPageIndex,
                                                savedSheetIndex = snap.savedSheetIndex
                                            )
                                        )
                                    }
                                    savedStateHandle[KEY_SAVED_PAGE_INDEX] = 0
                                    savedStateHandle[KEY_SAVED_SHEET_INDEX] = 0
                                    _uiState.update { state ->
                                        state.copy(
                                            lastOpenedUri = virtualUri,
                                            pendingUri = null,
                                            isArchiveSession = arcRes,
                                            archiveEntries = newEntries,
                                            activeSessionId = newSessionId,
                                            currentSession = proxy,
                                            hasSessionHistory = sessionHistoryStack.isNotEmpty(),
                                            savedPageIndex = 0,
                                            savedSheetIndex = 0
                                        )
                                    }
                                    createdSession = null
                                    openedRemoteSessionId = -1
                                }
                            }
                        } else {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            withContext(mainDispatcher) {
                                _uiState.update { state ->
                                    state.copy(
                                        pendingUri = virtualUri,
                                        structuredError = StructuredUiError(
                                            category = UiErrorCategory.FORMAT_STRUCTURE,
                                            message = "${str.errorNestedFileOpenFailed}: «$cleanName».",
                                            allowHexFallback = hexBackupPfd != null
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                val str = currentStrings()
                val rawErr = e.message ?: e.localizedMessage ?: ""
                val classified = if (rawErr.startsWith("DRM_PROTECTED") ||
                    rawErr.startsWith("UNSUPPORTED_ENCRYPTION") ||
                    rawErr.startsWith("PASSWORD_REQUIRED")
                ) {
                    replaceVirtualHexPfd(null)
                    classifyError(rawErr, hasReadableDescriptor = false)
                } else {
                    classifyError("${str.errorArchiveReadPrefix}: $rawErr", hasReadableDescriptor = lastVirtualHexPfd != null)
                }
                withContext(mainDispatcher) {
                    _uiState.update { state -> state.copy(structuredError = classified) }
                }
            }
        }
    }

    fun openHexFallback(uri: Uri) {
        openJob?.cancel()
        openJob = viewModelScope.launch(ioDispatcher) {
            var openedRemoteSessionId = -1
            var hexSession: DocumentSession? = null
            try {
                val sourcePfd: ParcelFileDescriptor? = dupVirtualHexBackupPfd() ?: if (uri.scheme != "virtual") {
                    try { openPfd(uri) } catch (_: Throwable) { null }
                } else null

                if (sourcePfd == null) {
                    val str = currentStrings()
                    withContext(mainDispatcher) {
                        _uiState.update { state ->
                            state.copy(
                                structuredError = StructuredUiError(
                                    category = UiErrorCategory.ACCESS_OR_SECURITY,
                                    message = str.errorHexOpenFailed,
                                    allowHexFallback = false
                                )
                            )
                        }
                    }
                    return@launch
                }

                sourcePfd.use { pfd ->
                    val svc = withTimeoutOrNull(8.seconds) { getIsolatedService() }
                    if (svc != null) {
                        val ipcDup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val sessionId = svc.openSession(ipcDup, "hex")
                        if (sessionId >= 0) {
                            try { ipcDup.close() } catch (_: Exception) {}
                            openedRemoteSessionId = sessionId
                            val hostDup = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                            hexSession = ProxyDocumentSession(
                                controller = this@ReaderViewModel,
                                sessionId = sessionId,
                                uri = uri,
                                extension = "hex",
                                is3dContent = false,
                                hostPfd = hostDup
                            )
                        } else {
                            try { ipcDup.close() } catch (_: Exception) {}
                        }
                    }

                    if (hexSession == null) {
                        hexSession = container.createLocalHexSession(pfd.fileDescriptor)
                    }

                    coroutineContext.ensureActive()
                    withContext(mainDispatcher) {
                        val snap = _uiState.value
                        if (snap.isArchiveSession && snap.currentSession != null) {
                            sessionHistoryStack.add(
                                SessionStateFrame(
                                    session = snap.currentSession,
                                    isArchive = snap.isArchiveSession,
                                    archiveEntries = snap.archiveEntries,
                                    activeSessionId = snap.activeSessionId,
                                    savedPageIndex = snap.savedPageIndex,
                                    savedSheetIndex = snap.savedSheetIndex
                                )
                            )
                        } else {
                            closeSessionAsync(snap.currentSession)
                        }
                        savedStateHandle[KEY_SAVED_PAGE_INDEX] = 0
                        savedStateHandle[KEY_SAVED_SHEET_INDEX] = 0
                        _uiState.update { state ->
                            state.copy(
                                activeSessionId = if (openedRemoteSessionId >= 0) openedRemoteSessionId else state.activeSessionId,
                                isArchiveSession = false,
                                archiveEntries = emptyList(),
                                currentSession = hexSession,
                                hasSessionHistory = sessionHistoryStack.isNotEmpty(),
                                savedPageIndex = 0,
                                savedSheetIndex = 0
                            )
                        }
                        hexSession = null
                        openedRemoteSessionId = -1
                    }
                }
            } catch (e: CancellationException) {
                hexSession?.let { closeSessionAsync(it) }
                if (hexSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (_: Exception) {
                hexSession?.let { closeSessionAsync(it) }
                if (hexSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { renderClient.peekService()?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                val str = currentStrings()
                withContext(mainDispatcher) {
                    _uiState.update { state ->
                        state.copy(
                            structuredError = StructuredUiError(
                                category = UiErrorCategory.ACCESS_OR_SECURITY,
                                message = str.errorHexOpenFailed,
                                allowHexFallback = false
                            )
                        )
                    }
                }
            }
        }
    }

    fun releaseAllSessionsAndUnbind() {
        openJob?.cancel()
        val activeProxy = _uiState.value.currentSession as? ProxyDocumentSession
        activeProxy?.setSessionStateListener(null)

        closeSessionAsync(_uiState.value.currentSession)
        sessionHistoryStack.forEach { closeSessionAsync(it.session) }
        sessionHistoryStack.clear()
        replaceVirtualHexPfd(null)
        _uiState.update {
            it.copy(
                currentSession = null,
                isArchiveSession = false,
                archiveEntries = emptyList(),
                activeSessionId = -1,
                hasSessionHistory = false
            )
        }
        unbindServiceSafely()
    }

    override fun onCleared() {
        renderClient.setConnectionListener(null)
        releaseAllSessionsAndUnbind()
        super.onCleared()
    }

    companion object {
        const val KEY_LAST_OPENED_URI = "last_opened_uri"
        const val KEY_SAVED_PAGE_INDEX = "saved_page_index"
        const val KEY_SAVED_SHEET_INDEX = "saved_sheet_index"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    container = container
                )
            }
        }
    }
}
