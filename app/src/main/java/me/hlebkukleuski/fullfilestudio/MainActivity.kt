
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
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.IIsolatedEngineService
import com.omnireader.core.engine.ISessionStateCallback
import com.omnireader.core.engine.LegalLicensePolicy
import com.omnireader.core.engine.MaliciousHeaderSanitizer
import com.omnireader.core.engine.OmniServerEndpoints
import com.omnireader.core.engine.impl.DocumentRendererEngineImpl
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.StreamingCacheUtils
import com.omnireader.core.engine.impl.UniversalHexInspectorImpl
import com.omnireader.core.engine.impl.MediaPlaybackSession
import com.omnireader.core.engine.impl.ZeroCopyMediaEngineImpl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.hlebkukleuski.fullfilestudio.bootstrap.FirstLaunchEngineBootstrapper
import me.hlebkukleuski.fullfilestudio.service.IsolatedRenderService
import me.hlebkukleuski.fullfilestudio.ui.theme.AppFrameRateMode
import me.hlebkukleuski.fullfilestudio.ui.screens.ArchiveView
import me.hlebkukleuski.fullfilestudio.ui.screens.FirstLaunchBootstrapScreen
import me.hlebkukleuski.fullfilestudio.ui.screens.TiledZoomableCanvas
import me.hlebkukleuski.fullfilestudio.ui.theme.AppSettingsDrawerSheet
import me.hlebkukleuski.fullfilestudio.ui.theme.FullFileStudioTheme
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings
import me.hlebkukleuski.fullfilestudio.ui.theme.localizeEngineMessage
import me.hlebkukleuski.fullfilestudio.ui.theme.stringsForLanguage
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private operator fun ByteBuffer.get(dst: ByteArray) {
    get(dst, 0, dst.size)
}

internal data class SessionStateFrame(
    val session: DocumentSession,
    val isArchive: Boolean,
    val archiveEntries: List<String>,
    val activeSessionId: Int
)

internal enum class UiErrorCategory {
    ACCESS_OR_SECURITY,
    CLOSED_LICENSE,
    PASSWORD_REQUIRED,
    FORMAT_STRUCTURE
}

internal data class StructuredUiError(
    val category: UiErrorCategory,
    val message: String,
    val allowHexFallback: Boolean = true
)

internal data class ReaderUiState(
    val currentSession: DocumentSession? = null,
    val isArchiveSession: Boolean = false,
    val archiveEntries: List<String> = emptyList(),
    val activeSessionId: Int = -1,
    val hasSessionHistory: Boolean = false,
    val pendingUri: Uri? = null,
    val lastOpenedUri: Uri? = null,
    val structuredError: StructuredUiError? = null,
    val isBufferingFile: Boolean = false,
    val bufferingProgress: Float = 0f,
    val hasVirtualHexBackup: Boolean = false
)

@Composable
fun EnterpriseErrorDialog(
    badgeText: String,
    badgeColor: Color,
    titleText: String,
    descriptionText: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val palette = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(
                onClick = onPrimaryClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor,
                    contentColor = Color.Black
                )
            ) {
                Text(primaryButtonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (secondaryButtonText != null && onSecondaryClick != null) {
                TextButton(onClick = onSecondaryClick) {
                    Text(secondaryButtonText, color = palette.secondaryText)
                }
            }
        },
        title = {
            Column {
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(badgeText, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(titleText, color = palette.primaryText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = descriptionText,
                    color = palette.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun PasswordUnlockDialog(
    badgeText: String,
    badgeColor: Color,
    titleText: String,
    descriptionText: String,
    placeholderText: String,
    unlockButtonText: String,
    hexButtonText: String?,
    closeButtonText: String,
    onUnlock: (String) -> Unit,
    onHexClick: (() -> Unit)?,
    onDismissRequest: () -> Unit
) {
    val palette = LocalAppPalette.current
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(
                onClick = { if (password.isNotEmpty()) onUnlock(password) },
                enabled = password.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor,
                    contentColor = Color.Black
                )
            ) {
                Text(unlockButtonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hexButtonText != null && onHexClick != null) {
                    TextButton(onClick = onHexClick) {
                        Text(hexButtonText, color = palette.secondaryText)
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(closeButtonText, color = palette.secondaryText)
                }
            }
        },
        title = {
            Column {
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(badgeText, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(titleText, color = palette.primaryText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceElevated, RoundedCornerShape(12.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = descriptionText,
                        color = palette.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text(placeholderText, color = palette.secondaryText) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.primaryText,
                        unfocusedTextColor = palette.primaryText,
                        focusedBorderColor = badgeColor,
                        unfocusedBorderColor = palette.border,
                        cursorColor = badgeColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

/**
 * Dedicated ViewModel that owns the isolated IPC service connection, document session stack,
 * stream buffering state, and format routing outside of the Activity UI class.
 */
internal class MainReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    val bootstrapper = FirstLaunchEngineBootstrapper(appContext)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val sessionHistoryStack = mutableListOf<SessionStateFrame>()
    private var lastVirtualHexPfd: ParcelFileDescriptor? = null
    private var openJob: Job? = null

    @Volatile
    private var isolatedService: IIsolatedEngineService? = null
    private var isServiceBound = false
    private val serviceLock = Any()
    private val serviceContinuations = mutableListOf<CancellableContinuation<IIsolatedEngineService>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = IIsolatedEngineService.Stub.asInterface(service)
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = binder
                isServiceBound = true
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach { if (it.isActive) it.resume(binder) }

            val activeProxy = _uiState.value.currentSession as? MainActivity.ProxyDocumentSession
            if (activeProxy != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    activeProxy.onServiceReconnected(binder)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = null
                isServiceBound = false
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach {
                if (it.isActive) {
                    it.resumeWithException(IllegalStateException("Service disconnected"))
                }
            }
            val activeProxy = _uiState.value.currentSession as? MainActivity.ProxyDocumentSession
            activeProxy?.onServiceDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = null
                if (isServiceBound) {
                    try { appContext.unbindService(this) } catch (_: Throwable) {}
                    isServiceBound = false
                }
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach {
                if (it.isActive) {
                    it.resumeWithException(IllegalStateException("Service binding died"))
                }
            }
            val activeProxy = _uiState.value.currentSession as? MainActivity.ProxyDocumentSession
            activeProxy?.onServiceDisconnected()
        }
    }

    internal fun currentStrings() = stringsForLanguage(bootstrapper.settingsManager.language.value)

    internal fun peekIsolatedService(): IIsolatedEngineService? = isolatedService

    internal fun launchInScope(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO, block = block)
    }

    internal fun launchImmediateMain(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate, block = block)
    }

    internal fun dupVirtualHexBackupPfd(): ParcelFileDescriptor? {
        return try {
            lastVirtualHexPfd?.let { ParcelFileDescriptor.dup(it.fileDescriptor) }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun updateActiveSessionId(newId: Int) {
        _uiState.update { it.copy(activeSessionId = newId) }
    }

    fun ensureServiceBound() {
        synchronized(serviceLock) {
            if (!isServiceBound) {
                isServiceBound = try {
                    appContext.bindService(
                        Intent(appContext, IsolatedRenderService::class.java),
                        serviceConnection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    fun unbindServiceSafely() {
        val conts: List<CancellableContinuation<IIsolatedEngineService>>
        synchronized(serviceLock) {
            if (isServiceBound) {
                try { appContext.unbindService(serviceConnection) } catch (_: Exception) {}
                isServiceBound = false
            }
            isolatedService = null
            conts = serviceContinuations.toList()
            serviceContinuations.clear()
        }
        conts.forEach {
            if (it.isActive) {
                it.resumeWithException(IllegalStateException("Service unbound"))
            }
        }
    }

    internal suspend fun getIsolatedService(): IIsolatedEngineService = suspendCancellableCoroutine { cont ->
        var failImmediately = false
        synchronized(serviceLock) {
            val svc = isolatedService
            if (svc != null && svc.asBinder().isBinderAlive) {
                cont.resume(svc)
                return@suspendCancellableCoroutine
            }

            val hadDeadService = svc != null
            isolatedService = null
            serviceContinuations.add(cont)
            cont.invokeOnCancellation {
                synchronized(serviceLock) {
                    serviceContinuations.remove(cont)
                    if (isolatedService == null && serviceContinuations.isEmpty() && isServiceBound) {
                        try { appContext.unbindService(serviceConnection) } catch (_: Throwable) {}
                        isServiceBound = false
                    }
                }
            }
            if (hadDeadService && isServiceBound) {
                try { appContext.unbindService(serviceConnection) } catch (_: Throwable) {}
                isServiceBound = false
            }
            if (!isServiceBound) {
                isServiceBound = try {
                    appContext.bindService(
                        Intent(appContext, IsolatedRenderService::class.java),
                        serviceConnection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )
                } catch (_: Throwable) {
                    false
                }
                if (!isServiceBound) {
                    serviceContinuations.remove(cont)
                    failImmediately = true
                }
            }
        }
        if (failImmediately && cont.isActive) {
            cont.resumeWithException(IllegalStateException(currentStrings().errorServiceTimeout))
        }
    }

    fun restoreSavedPendingState(
        savedLastUri: Uri?
    ) {
        if (savedLastUri != null && savedLastUri.scheme != "virtual" && _uiState.value.currentSession == null) {
            openUriWithSafeFallback(savedLastUri)
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
            _uiState.update {
                it.copy(
                    currentSession = lastFrame.session,
                    isArchiveSession = lastFrame.isArchive,
                    archiveEntries = lastFrame.archiveEntries,
                    activeSessionId = lastFrame.activeSessionId,
                    hasSessionHistory = sessionHistoryStack.isNotEmpty()
                )
            }
        } else {
            closeSessionAsync(current)
            _uiState.update {
                it.copy(
                    currentSession = null,
                    isArchiveSession = false,
                    archiveEntries = emptyList(),
                    activeSessionId = -1,
                    hasSessionHistory = false
                )
            }
        }
    }

    internal fun openPfd(uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme == "virtual") {
            return dupVirtualHexBackupPfd()
        }

        var pfd: ParcelFileDescriptor? = null
        var effectiveUri: Uri = uri
        var lastError: Exception? = null
        val resolver = appContext.contentResolver

        if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            val directFile = File(uri.path!!)
            val canonical = try { directFile.canonicalPath } catch (_: Throwable) { directFile.absolutePath }
            val appDataDir = try { File(appContext.applicationInfo.dataDir).canonicalPath } catch (_: Throwable) { appContext.applicationInfo.dataDir }
            if (canonical.startsWith("$appDataDir/shared_prefs") ||
                canonical.startsWith("$appDataDir/databases") ||
                canonical.startsWith("$appDataDir/files") ||
                canonical.startsWith("/data/data/") ||
                canonical.startsWith("/data/user/")
            ) {
                throw SecurityException("Access to internal application storage via file:// URI is not permitted.")
            }
            if (directFile.exists() && directFile.canRead()) {
                try {
                    pfd = ParcelFileDescriptor.open(directFile, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (pfd == null) {
                try {
                    val providerUri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        directFile
                    )
                    effectiveUri = providerUri
                    pfd = resolver.openFileDescriptor(providerUri, "r")
                    lastError = null
                } catch (e: Exception) {
                    if (lastError == null) lastError = e
                }
            }
        }

        if (pfd == null) {
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                effectiveUri = uri
                lastError = null
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        if (pfd == null) {
            try {
                pfd = resolver.openAssetFileDescriptor(effectiveUri, "r")?.parcelFileDescriptor
                if (pfd != null) lastError = null
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        if (pfd != null) {
            try {
                return StreamingCacheUtils.ensureSeekableParcelFileDescriptor(
                    contentResolver = resolver,
                    uri = effectiveUri,
                    originalPfd = pfd,
                    cacheDir = appContext.cacheDir,
                    onProgress = { progress ->
                        val normalizedProgress = if (progress < 0f) 0.5f else progress.coerceIn(0f, 1f)
                        viewModelScope.launch(Dispatchers.Main.immediate) {
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
            } catch (e: Exception) {
                try { pfd.close() } catch (_: Exception) {}
                val str = currentStrings()
                throw IllegalStateException("${str.errorBufferingStreamPrefix}: ${e.localizedMessage ?: ""}", e)
            }
        }

        if (lastError != null) {
            throw lastError
        }

        return null
    }

    private fun extensionFromMimeType(mimeType: String?): String {
        val cleanMime = mimeType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: return ""
        if (cleanMime.isEmpty() || cleanMime == "*/*" || cleanMime == "application/octet-stream") return ""
        return when (cleanMime) {
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-word.document.macroenabled.12" -> "docm"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" -> "dotx"
            "application/vnd.ms-word.template.macroenabled.12" -> "dotm"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel.sheet.macroenabled.12" -> "xlsm"
            "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> "xlsb"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" -> "xltx"
            "application/vnd.ms-excel.template.macroenabled.12" -> "xltm"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> "pptm"
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> "ppsx"
            "application/vnd.ms-powerpoint.slideshow.macroenabled.12" -> "ppsm"
            "application/vnd.openxmlformats-officedocument.presentationml.template" -> "potx"
            "application/vnd.ms-powerpoint.template.macroenabled.12" -> "potm"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template" -> "odt"
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template" -> "ods"
            "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template" -> "odp"
            "application/rtf", "text/rtf" -> "rtf"
            "application/onenote", "application/msonenote" -> "one"
            "application/epub+zip" -> "epub"
            "application/x-fictionbook+xml", "application/x-fb2", "application/x-zip-compressed-fb2" -> "fb2"
            "image/vnd.djvu", "image/x-djvu" -> "djvu"
            "font/ttf", "application/x-font-ttf", "application/font-sfnt" -> "ttf"
            "font/otf", "application/x-font-otf", "application/vnd.ms-opentype" -> "otf"
            "font/collection" -> "ttc"
            "font/woff", "application/font-woff" -> "woff"
            "font/woff2" -> "woff2"
            "image/svg+xml" -> "svg"
            "image/svg+xml-compressed" -> "svgz"
            "model/stl", "application/sla", "application/vnd.ms-pki.stl" -> "stl"
            "model/obj", "application/x-tgif" -> "obj"
            "model/gltf+json" -> "gltf"
            "model/gltf-binary" -> "glb"
            "model/vnd.usdz+zip", "model/vnd.pixar.usd" -> "usdz"
            "model/3mf", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" -> "3mf"
            "model/vnd.collada+xml" -> "dae"
            "application/step", "model/step" -> "step"
            "model/iges", "application/iges" -> "iges"
            "model/ply", "application/x-ply" -> "ply"
            "application/x-blender" -> "blend"
            "image/vnd.dxf", "application/dxf" -> "dxf"
            "image/vnd.dwg", "application/acad", "application/x-acad" -> "dwg"
            "application/vnd.sqlite3", "application/x-sqlite3" -> "sqlite"
            "application/x-msaccess", "application/msaccess" -> "mdb"
            "application/x-dbf", "application/dbf" -> "dbf"
            "application/vnd.apache.parquet", "application/x-parquet" -> "parquet"
            "application/x-7z-compressed" -> "7z"
            "application/vnd.rar", "application/x-rar-compressed" -> "rar"
            "application/x-tar" -> "tar"
            "application/gzip", "application/x-gzip" -> "gz"
            "application/x-bzip2" -> "bz2"
            "application/x-xz" -> "xz"
            "application/x-iso9660-image" -> "iso"
            "application/vnd.android.package-archive" -> "apk"
            "application/zip" -> "zip"
            "text/csv", "text/comma-separated-values" -> "csv"
            "text/tab-separated-values" -> "tsv"
            "text/plain" -> "txt"
            "application/json" -> "json"
            "application/x-ndjson", "application/jsonl" -> "jsonl"
            "text/xml", "application/xml" -> "xml"
            "text/markdown" -> "md"
            "image/tiff" -> "tiff"
            "image/heic", "image/heic-sequence" -> "heic"
            "image/heif", "image/heif-sequence" -> "heif"
            "image/avif", "image/avif-sequence" -> "avif"
            "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)?.lowercase(Locale.ROOT)?.trim() ?: ""
        }
    }

    private fun extractExtensionFromFileName(fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT).trim()
        if (!lower.contains(".")) return ""
        return when {
            lower.endsWith(".fb2.zip") || lower.endsWith(".fbz") -> "fb2"
            lower.endsWith(".svg.gz") -> "svgz"
            lower.endsWith(".tar.gz") -> "tgz"
            lower.endsWith(".tar.bz2") -> "bz2"
            lower.endsWith(".tar.xz") -> "xz"
            else -> {
                val ext = lower.substringAfterLast(".", "").trim()
                if (ext.isNotEmpty() && ext.length <= 12 && ext.all { it.isLetterOrDigit() }) ext else ""
            }
        }
    }

    private fun extractExtension(uri: Uri): String {
        if (uri.scheme == "virtual") {
            val vPath = Uri.decode(uri.toString().removePrefix("virtual://"))
            val fileName = vPath.substringAfterLast('/')
            val vExt = extractExtensionFromFileName(fileName)
            if (vExt.isNotEmpty()) return vExt
        }
        val resolver = appContext.contentResolver
        var displayName = ""
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        val dispExt = extractExtensionFromFileName(displayName)
        if (dispExt.isNotEmpty()) return dispExt

        val path = uri.lastPathSegment ?: uri.path ?: ""
        val lastName = path.substringAfterLast('/')
        val pathExt = extractExtensionFromFileName(lastName)
        if (pathExt.isNotEmpty()) return pathExt

        return try {
            extensionFromMimeType(resolver.getType(uri))
        } catch (_: Throwable) {
            ""
        }
    }

    private fun resolveEffectiveExtension(format: DetectedFormat, rawExt: String): String {
        val cleanExt = rawExt.lowercase(Locale.ROOT).trim()
        return when (format) {
            DetectedFormat.PDF -> "pdf"
            DetectedFormat.DJVU -> "djvu"
            DetectedFormat.FONT -> if (cleanExt in setOf("ttf", "otf", "ttc", "woff", "woff2", "dfont")) cleanExt else "ttf"
            DetectedFormat.DOCX -> "docx"
            DetectedFormat.DOC -> "doc"
            DetectedFormat.XLSX -> if (cleanExt == "xlsb") "xlsb" else "xlsx"
            DetectedFormat.XLS -> "xls"
            DetectedFormat.PPTX -> "pptx"
            DetectedFormat.PPT -> "ppt"
            DetectedFormat.ODT -> "odt"
            DetectedFormat.ODS -> "ods"
            DetectedFormat.ODP -> "odp"
            DetectedFormat.RTF -> "rtf"
            DetectedFormat.EPUB -> "epub"
            DetectedFormat.FB2 -> "fb2"
            DetectedFormat.SVG -> "svg"
            DetectedFormat.SVGZ -> "svgz"
            DetectedFormat.GLTF -> "gltf"
            DetectedFormat.GLB -> "glb"
            DetectedFormat.BLEND -> "blend"
            DetectedFormat.USDZ -> if (cleanExt in setOf("usd", "usda", "usdc")) cleanExt else "usdz"
            DetectedFormat.STL -> if (cleanExt in setOf(
                    "ply", "off", "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc",
                    "fbx", "3mf", "dae", "zae", "step", "stp", "iges", "igs"
                )
            ) cleanExt else "stl"
            DetectedFormat.OBJ -> "obj"
            DetectedFormat.DXF -> "dxf"
            DetectedFormat.DWG -> "dwg"
            DetectedFormat.SQLITE -> "sqlite"
            DetectedFormat.ACCDB -> "accdb"
            DetectedFormat.MDB -> "mdb"
            DetectedFormat.DBF -> "dbf"
            DetectedFormat.PARQUET -> if (cleanExt in setOf("duckdb", "realm", "bson", "jsonl", "ndjson")) cleanExt else "parquet"
            DetectedFormat.MHTML -> if (cleanExt == "mht") "mht" else "mhtml"
            DetectedFormat.ONE -> "one"
            DetectedFormat.AZW3 -> "azw3"
            DetectedFormat.MOBI -> "mobi"
            DetectedFormat.KFX -> "kfx"
            DetectedFormat.MAX -> "max"
            DetectedFormat.RVT -> "rvt"
            DetectedFormat.INDD -> "indd"
            DetectedFormat.PNG -> "png"
            DetectedFormat.JPEG -> if (cleanExt in setOf("jpg", "jpeg", "jpe", "jfif")) cleanExt else "jpg"
            DetectedFormat.WEBP -> "webp"
            DetectedFormat.GIF -> "gif"
            DetectedFormat.BMP -> if (cleanExt in setOf("ico", "cur", "wbmp")) cleanExt else "bmp"
            DetectedFormat.TIFF -> if (cleanExt in setOf("tif", "tiff", "dng", "cr2", "nef", "arw")) cleanExt else "tiff"
            DetectedFormat.HEIC -> if (cleanExt in setOf("heic", "heif", "hif")) cleanExt else "heic"
            DetectedFormat.AVIF -> if (cleanExt in setOf("avif", "avifs")) cleanExt else "avif"
            DetectedFormat.ISO -> "iso"
            DetectedFormat.ZIP -> when (cleanExt) {
                "docm", "dotx", "dotm" -> "docx"
                "xlsm", "xltx", "xltm" -> "xlsx"
                "xlsb" -> "xlsb"
                "pptm", "ppsx", "ppsm", "potx", "potm" -> "pptx"
                "ott" -> "odt"
                "ots" -> "ods"
                "otp" -> "odp"
                "fbz" -> "fb2"
                "usdz" -> "usdz"
                "3mf" -> "3mf"
                "zae" -> "zae"
                "apk", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "fb2" -> cleanExt
                else -> "zip"
            }
            DetectedFormat.SEVEN_ZIP -> "7z"
            DetectedFormat.RAR_V4, DetectedFormat.RAR_V5 -> "rar"
            DetectedFormat.TAR -> if (cleanExt == "tgz") "tgz" else "tar"
            DetectedFormat.GZ -> when (cleanExt) {
                "tgz" -> "tgz"
                "svgz" -> "svgz"
                "blend" -> "blend"
                else -> "gz"
            }
            DetectedFormat.BZ2 -> "bz2"
            DetectedFormat.XZ -> "xz"
            DetectedFormat.APK -> if (cleanExt == "jar") "jar" else "apk"
            DetectedFormat.PLAINTEXT -> cleanExt.ifEmpty { "txt" }
            else -> cleanExt
        }
    }

    private fun isBuiltInCoreFormat(format: DetectedFormat, effectiveExt: String): Boolean {
        val cleanExt = effectiveExt.lowercase(Locale.ROOT).trim()
        if (cleanExt in MainActivity.MODULE_MANAGED_EXTENSIONS) {
            return false
        }
        return format in setOf(
            DetectedFormat.PDF,
            DetectedFormat.DJVU,
            DetectedFormat.FONT,
            DetectedFormat.SQLITE,
            DetectedFormat.ACCDB,
            DetectedFormat.MDB,
            DetectedFormat.DBF,
            DetectedFormat.PARQUET,
            DetectedFormat.PNG,
            DetectedFormat.JPEG,
            DetectedFormat.WEBP,
            DetectedFormat.GIF,
            DetectedFormat.BMP,
            DetectedFormat.TIFF,
            DetectedFormat.HEIC,
            DetectedFormat.AVIF,
            DetectedFormat.MP4,
            DetectedFormat.MP3,
            DetectedFormat.MHTML,
            DetectedFormat.PLAINTEXT
        ) || cleanExt in MainActivity.CORE_TEXT_AND_BINARY_EXTENSIONS
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
                    rawMessage.contains("EACCES", ignoreCase = true) -> StructuredUiError(
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

    fun openUriWithSafeFallback(uri: Uri, password: String? = null) {
        openJob?.cancel()
        val prevSession = _uiState.value.currentSession
        closeSessionAsync(prevSession)
        sessionHistoryStack.forEach { closeSessionAsync(it.session) }
        sessionHistoryStack.clear()
        if (uri.scheme != "virtual") {
            replaceVirtualHexPfd(null)
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
                structuredError = null
            )
        }

        openJob = viewModelScope.launch(Dispatchers.IO) {
            var openedRemoteSessionId = -1
            var createdSession: DocumentSession? = null
            try {
                val ext = extractExtension(uri)
                val pfd = openPfd(uri)
                if (pfd == null) {
                    val str = currentStrings()
                    withContext(Dispatchers.Main) {
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
                    withContext(Dispatchers.Main) {
                        _uiState.update { state -> state.copy(isBufferingFile = false) }
                    }

                    val hexBackupDup = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                    if (hexBackupDup != null) {
                        replaceVirtualHexPfd(hexBackupDup)
                    }

                    val header = ByteBuffer.allocateDirect(4096)
                    val size = try { Os.fstat(pfd.fileDescriptor).st_size } catch (_: Exception) { -1L }
                    val read = try {
                        try { Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET) } catch (_: Throwable) {}
                        Os.read(pfd.fileDescriptor, header)
                    } catch (_: Exception) { 0 }
                    header.flip()
                    val sig = ByteArray(read.coerceAtLeast(0))
                    if (read > 0) header[sig]
                    header.position(0)

                    val format = FileSignatureDetector.resolveFormat(pfd.fileDescriptor, header, ext, size)
                    val effectiveExt = resolveEffectiveExtension(format, ext)

                    val legalVerdict = LegalLicensePolicy.evaluate(
                        format = format,
                        extension = effectiveExt,
                        isDrmEncrypted = (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX)
                    )
                    if (!legalVerdict.isLegallyPermitted) {
                        replaceVirtualHexPfd(null)
                        withContext(Dispatchers.Main) {
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
                        val isExecutableBinary = format == DetectedFormat.ELF || format == DetectedFormat.DEX || format == DetectedFormat.APK ||
                            threat.contains("Executable", ignoreCase = true) || threat.contains("PE/Mach-O", ignoreCase = true)
                        if (isExecutableBinary) {
                            replaceVirtualHexPfd(null)
                        }
                        withContext(Dispatchers.Main) {
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

                    val isGraphicFormat = format in setOf(
                        DetectedFormat.PNG, DetectedFormat.JPEG, DetectedFormat.WEBP,
                        DetectedFormat.GIF, DetectedFormat.BMP, DetectedFormat.TIFF,
                        DetectedFormat.HEIC, DetectedFormat.AVIF, DetectedFormat.SVG, DetectedFormat.SVGZ
                    )
                    val mediaEngine = ZeroCopyMediaEngineImpl()
                    val isMedia = !isGraphicFormat && (
                            format in setOf(DetectedFormat.MP4, DetectedFormat.MP3) ||
                                    mediaEngine.canHandle(sig, effectiveExt)
                            )
                    val isHostDecodedImage = format in setOf(DetectedFormat.HEIC, DetectedFormat.AVIF) ||
                            effectiveExt in setOf("heic", "heif", "hif", "avif", "avifs")

                    var isRealArchive = false
                    var newEntries: List<String> = emptyList()

                    val session: DocumentSession? = if (isMedia || isHostDecodedImage) {
                        val reused = if (isMedia) me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController.sessionForUri(uri.toString()) else null
                        val dupPfd = if (reused == null) ParcelFileDescriptor.dup(pfd.fileDescriptor) else null
                        val mediaSession = reused ?: if (isHostDecodedImage) {
                            DocumentRendererEngineImpl().createSessionForFormat(dupPfd!!.fileDescriptor, format, effectiveExt)
                        } else {
                            mediaEngine.createSession(dupPfd!!.fileDescriptor).also { (it as? MediaPlaybackSession)?.sourceUri = uri.toString() }
                        }
                        object : DocumentSession by mediaSession {
                            override fun close() {
                                try {
                                    val media = mediaSession as? MediaPlaybackSession
                                    if (media == null || !me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController.releaseFromUi(media)) mediaSession.close()
                                } finally {
                                    try { dupPfd?.close() } catch (_: Exception) {}
                                }
                            }
                        }
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
                            MainActivity.ProxyDocumentSession(
                                controller = this@MainReaderViewModel,
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
                        val immediateError = session.getLastError()
                        withContext(Dispatchers.Main) {
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
                        withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    _uiState.update { state -> state.copy(isBufferingFile = false) }
                }
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { state -> state.copy(isBufferingFile = false) }
                }
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                val str = currentStrings()
                val hasBackupPfd = lastVirtualHexPfd != null
                withContext(Dispatchers.Main) {
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
        return entryName.replace(MainActivity.ARCHIVE_SIZE_SUFFIX_REGEX, "").trim()
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
        openJob = viewModelScope.launch(Dispatchers.IO) {
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
                    withContext(Dispatchers.Main) {
                        _uiState.update { state ->
                            state.copy(
                                structuredError = classified
                            )
                        }
                    }
                    return@launch
                }

                vFd.use { pfd ->
                    val hexBackupPfd = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                    replaceVirtualHexPfd(hexBackupPfd)
                    val virtualUri = Uri.parse("virtual://${Uri.encode(cleanName)}")

                    val fileNameOnly = cleanName.substringAfterLast('/')
                    val rawExt = extractExtensionFromFileName(fileNameOnly)
                    val header = ByteBuffer.allocateDirect(4096)
                    val size = try { Os.fstat(pfd.fileDescriptor).st_size } catch (_: Exception) { -1L }
                    val read = try {
                        try { Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET) } catch (_: Throwable) {}
                        Os.read(pfd.fileDescriptor, header)
                    } catch (_: Exception) { 0 }
                    header.flip()
                    val sig = ByteArray(read.coerceAtLeast(0))
                    if (read > 0) header[sig]
                    header.position(0)

                    val format = FileSignatureDetector.resolveFormat(pfd.fileDescriptor, header, rawExt, size)
                    val effectiveExt = resolveEffectiveExtension(format, rawExt)

                    val legalVerdict = LegalLicensePolicy.evaluate(
                        format = format,
                        extension = effectiveExt,
                        isDrmEncrypted = (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX)
                    )
                    if (!legalVerdict.isLegallyPermitted) {
                        replaceVirtualHexPfd(null)
                        withContext(Dispatchers.Main) {
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
                        val isExecutableBinary = format == DetectedFormat.ELF || format == DetectedFormat.DEX || format == DetectedFormat.APK ||
                            threat.contains("Executable", ignoreCase = true) || threat.contains("PE/Mach-O", ignoreCase = true)
                        if (isExecutableBinary) {
                            replaceVirtualHexPfd(null)
                        }
                        withContext(Dispatchers.Main) {
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

                    val isGraphicFormat = format in setOf(
                        DetectedFormat.PNG, DetectedFormat.JPEG, DetectedFormat.WEBP,
                        DetectedFormat.GIF, DetectedFormat.BMP, DetectedFormat.TIFF,
                        DetectedFormat.HEIC, DetectedFormat.AVIF, DetectedFormat.SVG, DetectedFormat.SVGZ
                    )
                    val mediaEngine = ZeroCopyMediaEngineImpl()
                    val isMedia = !isGraphicFormat && (
                            format in setOf(DetectedFormat.MP4, DetectedFormat.MP3) ||
                                    mediaEngine.canHandle(sig, effectiveExt)
                            )
                    val isHostDecodedImage = format in setOf(DetectedFormat.HEIC, DetectedFormat.AVIF) ||
                            effectiveExt in setOf("heic", "heif", "hif", "avif", "avifs")

                    if (isMedia || isHostDecodedImage) {
                        val dupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val rawMediaSession = if (isHostDecodedImage) {
                            DocumentRendererEngineImpl().createSessionForFormat(dupPfd.fileDescriptor, format, effectiveExt)
                        } else {
                            mediaEngine.createSession(dupPfd.fileDescriptor)
                        }
                        val mediaSession = object : DocumentSession by rawMediaSession {
                            override fun close() {
                                try {
                                    val media = rawMediaSession as? MediaPlaybackSession
                                    if (media == null || !me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController.releaseFromUi(media)) rawMediaSession.close()
                                } finally {
                                    try { dupPfd.close() } catch (_: Exception) {}
                                }
                            }
                        }
                        createdSession = mediaSession
                        val mediaErr = mediaSession.getLastError()
                        coroutineContext.ensureActive()
                        withContext(Dispatchers.Main) {
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
                                        SessionStateFrame(curr, snap.isArchiveSession, snap.archiveEntries, parentSessionId)
                                    )
                                }
                                _uiState.update { state ->
                                    state.copy(
                                        lastOpenedUri = virtualUri,
                                        pendingUri = null,
                                        isArchiveSession = false,
                                        archiveEntries = emptyList(),
                                        activeSessionId = -1,
                                        currentSession = mediaSession,
                                        hasSessionHistory = sessionHistoryStack.isNotEmpty()
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
                            val proxy = MainActivity.ProxyDocumentSession(
                                controller = this@MainReaderViewModel,
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

                            withContext(Dispatchers.Main) {
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
                                            SessionStateFrame(curr, snap.isArchiveSession, snap.archiveEntries, parentSessionId)
                                        )
                                    }
                                    _uiState.update { state ->
                                        state.copy(
                                            lastOpenedUri = virtualUri,
                                            pendingUri = null,
                                            isArchiveSession = arcRes,
                                            archiveEntries = newEntries,
                                            activeSessionId = newSessionId,
                                            currentSession = proxy,
                                            hasSessionHistory = sessionHistoryStack.isNotEmpty()
                                        )
                                    }
                                    createdSession = null
                                    openedRemoteSessionId = -1
                                }
                            }
                        } else {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            withContext(Dispatchers.Main) {
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
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                createdSession?.let { closeSessionAsync(it) }
                if (createdSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
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
                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            structuredError = classified
                        )
                    }
                }
            }
        }
    }

    fun openHexFallback(uri: Uri) {
        openJob?.cancel()
        openJob = viewModelScope.launch(Dispatchers.IO) {
            var openedRemoteSessionId = -1
            var hexSession: DocumentSession? = null
            try {
                val sourcePfd: ParcelFileDescriptor? = dupVirtualHexBackupPfd() ?: if (uri.scheme != "virtual") {
                    try { openPfd(uri) } catch (_: Throwable) { null }
                } else null

                if (sourcePfd == null) {
                    val str = currentStrings()
                    withContext(Dispatchers.Main) {
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
                            hexSession = MainActivity.ProxyDocumentSession(
                                controller = this@MainReaderViewModel,
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
                        hexSession = UniversalHexInspectorImpl().createSession(pfd.fileDescriptor)
                    }

                    coroutineContext.ensureActive()
                    withContext(Dispatchers.Main) {
                        val snap = _uiState.value
                        if (snap.isArchiveSession && snap.currentSession != null) {
                            sessionHistoryStack.add(
                                SessionStateFrame(
                                    snap.currentSession,
                                    snap.isArchiveSession,
                                    snap.archiveEntries,
                                    snap.activeSessionId
                                )
                            )
                        } else {
                            closeSessionAsync(snap.currentSession)
                        }
                        _uiState.update { state ->
                            state.copy(
                                activeSessionId = if (openedRemoteSessionId >= 0) openedRemoteSessionId else state.activeSessionId,
                                isArchiveSession = false,
                                archiveEntries = emptyList(),
                                currentSession = hexSession,
                                hasSessionHistory = sessionHistoryStack.isNotEmpty()
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
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                throw e
            } catch (_: Exception) {
                hexSession?.let { closeSessionAsync(it) }
                if (hexSession == null && openedRemoteSessionId >= 0) {
                    cleanupScope.launch {
                        try { isolatedService?.closeSession(openedRemoteSessionId) } catch (_: Throwable) {}
                    }
                }
                val str = currentStrings()
                withContext(Dispatchers.Main) {
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
        val activeProxy = _uiState.value.currentSession as? MainActivity.ProxyDocumentSession
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
        releaseAllSessionsAndUnbind()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainReaderViewModel by viewModels()

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

    private fun extractIncomingIntentUri(intent: Intent?): Uri? {
        if (intent == null) return null
        intent.data?.let { return it }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.uri?.let { return it }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun clearConsumedIntentPayload(intent: Intent?) {
        if (intent == null) return
        intent.data = null
        intent.clipData = null
        intent.removeExtra(Intent.EXTRA_STREAM)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val snap = viewModel.uiState.value
        snap.pendingUri?.toString()?.let { outState.putString(KEY_PENDING_URI, it) }
        snap.lastOpenedUri?.toString()?.let { outState.putString(KEY_LAST_OPENED_URI, it) }
        consumedIntentUri?.let { outState.putString(KEY_CONSUMED_INTENT_URI, it) }
    }

    private var consumedIntentUri: String? = null

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
                        val diff = Math.abs(m.refreshRate - mode.fps)
                        if (diff.compareTo(minDiff) < 0) {
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

        OmniServerEndpoints.baseUrl = BuildConfig.SERVER_BASE_URL
        val bootstrapper = viewModel.bootstrapper

        val incomingUri = extractIncomingIntentUri(intent)
        val previouslyConsumedUri = savedInstanceState?.getString(KEY_CONSUMED_INTENT_URI)
        if (incomingUri != null && (savedInstanceState == null || incomingUri.toString() != previouslyConsumedUri)) {
            consumedIntentUri = incomingUri.toString()
            bootstrapper.skipFirstLaunch()
            clearConsumedIntentPayload(intent)
            viewModel.openUriWithSafeFallback(incomingUri)
        } else if (savedInstanceState != null) {
            consumedIntentUri = previouslyConsumedUri
            val savedLastUri = savedInstanceState.getString(KEY_LAST_OPENED_URI)?.let { Uri.parse(it) }
            viewModel.restoreSavedPendingState(savedLastUri)
        }

        setContent {
            FullFileStudioTheme(settingsManager = bootstrapper.settingsManager) {
                val palette = LocalAppPalette.current
                val strings = LocalAppStrings.current
                val currentLang by bootstrapper.settingsManager.language.collectAsState()
                val uiState by viewModel.uiState.collectAsState()
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()
                val updateManager = remember { StandaloneUpdateManager(applicationContext, drawerScope) }
                val updateState by updateManager.state.collectAsState()

                var isBootstrapComplete by rememberSaveable {
                    mutableStateOf(
                        bootstrapper.playPluginManager.isFirstLaunchCompleted() &&
                                bootstrapper.settingsManager.isInitialLanguageChosen.value
                    )
                }
                val bootstrapState by bootstrapper.state.collectAsState()

                val context = LocalContext.current
                LaunchedEffect(uiState.currentSession, uiState.isArchiveSession) {
                    val activity = context as? ComponentActivity
                    if (uiState.currentSession != null && !uiState.isArchiveSession && uiState.currentSession?.isPresentationContent == true) {
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    syncImmersiveSystemBars()
                }

                val frameRateMode by bootstrapper.settingsManager.frameRateMode.collectAsState()
                LaunchedEffect(frameRateMode) {
                    applyFrameRateMode(frameRateMode)
                }

                LaunchedEffect(bootstrapState.isComplete) {
                    if (bootstrapState.isComplete) isBootstrapComplete = true
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let {
                        try {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Throwable) {}
                        viewModel.openUriWithSafeFallback(it)
                    }
                }

                val isBackEnabled = drawerState.isOpen || uiState.currentSession != null || uiState.hasSessionHistory
                BackHandler(enabled = isBackEnabled) {
                    if (drawerState.isOpen) {
                        drawerScope.launch { drawerState.close() }
                    } else {
                        viewModel.popSessionHistory()
                        syncImmersiveSystemBars()
                    }
                }

                if (!isBootstrapComplete) {
                    FirstLaunchBootstrapScreen(
                        bootstrapper = bootstrapper,
                        onBootstrapComplete = { isBootstrapComplete = true }
                    )
                } else {
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
                                        onEntryClick = { entryName -> viewModel.openVirtualFileFromArchive(entryName) },
                                        onBackClick = {
                                            viewModel.popSessionHistory()
                                            syncImmersiveSystemBars()
                                        },
                                        onOpenSettingsClick = { drawerScope.launch { drawerState.open() } }
                                    )
                                } else {
                                    TiledZoomableCanvas(
                                        session = uiState.currentSession,
                                        onOpenFileRequest = { launcher.launch(arrayOf("*/*")) },
                                        onOpenSettingsRequest = { drawerScope.launch { drawerState.open() } },
                                        onOpenHexFallbackRequest = {
                                            val target = uiState.pendingUri ?: uiState.lastOpenedUri
                                            if (target != null) viewModel.openHexFallback(target)
                                        },
                                        onNavigateBack = {
                                            viewModel.popSessionHistory()
                                            syncImmersiveSystemBars()
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

                                    if (bootstrapState.isDownloading || uiState.isBufferingFile) {
                                        Spacer(modifier = Modifier.height(24.dp))
                                        LinearProgressIndicator(
                                            progress = {
                                                if (uiState.isBufferingFile) uiState.bufferingProgress.coerceIn(0f, 1f)
                                                else (bootstrapState.progressPercent / 100f).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.fillMaxWidth(0.75f),
                                            color = palette.accent
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (uiState.isBufferingFile) strings.bufferingCloudFile else bootstrapState.currentModule,
                                            color = palette.accent,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            updateState.availableRelease?.let { release ->
                                AlertDialog(
                                    onDismissRequest = { updateManager.dismissDialog() },
                                    containerColor = palette.surface,
                                    shape = RoundedCornerShape(20.dp),
                                    title = {
                                        Text(
                                            text = "${strings.updateAvailableTitle} (${release.versionName})",
                                            color = palette.primaryText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    },
                                    text = {
                                        Column {
                                            if (release.releaseNotes.isNotBlank()) {
                                                Text(
                                                    text = release.releaseNotes,
                                                    color = palette.secondaryText,
                                                    fontSize = 14.sp
                                                )
                                            }
                                            if (updateState.isDownloading) {
                                                Spacer(Modifier.height(14.dp))
                                                LinearProgressIndicator(
                                                    progress = { updateState.downloadProgress.coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = palette.accent
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = "${strings.downloadingUpdateStatus} ${(updateState.downloadProgress * 100).toInt()}%",
                                                    color = palette.accent,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            updateState.errorText?.let { err ->
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    text = err,
                                                    color = palette.errorBadge,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            enabled = !updateState.isDownloading,
                                            onClick = { updateManager.downloadAndInstall(this@MainActivity) },
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                                        ) {
                                            Text(
                                                text = strings.updateNowButton,
                                                color = if (palette.isDark) Color.Black else Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    dismissButton = {
                                        if (!updateState.isDownloading) {
                                            TextButton(onClick = { updateManager.dismissDialog() }) {
                                                Text(strings.laterButton, color = palette.secondaryText)
                                            }
                                        }
                                    }
                                )
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
                                            viewModel.dismissStructuredError()
                                            viewModel.openUriWithSafeFallback(targetUri, enteredPassword)
                                        },
                                        onHexClick = if (canOpenHex) {
                                            {
                                                viewModel.dismissStructuredError()
                                                viewModel.openHexFallback(targetUri)
                                            }
                                        } else null,
                                        onDismissRequest = { viewModel.dismissStructuredError() }
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
                                            viewModel.dismissStructuredError()
                                            if (canOpenHex) {
                                                viewModel.openHexFallback(uriToHex)
                                            }
                                        },
                                        secondaryButtonText = if (canOpenHex) strings.closeBtn else null,
                                        onSecondaryClick = { viewModel.dismissStructuredError() },
                                        onDismissRequest = { viewModel.dismissStructuredError() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = extractIncomingIntentUri(intent)
        clearConsumedIntentPayload(intent)
        setIntent(intent)
        if (uri != null) {
            consumedIntentUri = uri.toString()
            viewModel.bootstrapper.skipFirstLaunch()
            viewModel.openUriWithSafeFallback(uri)
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

    /**
     * Static nested proxy session communicating with [IsolatedRenderService] over AIDL + [SharedMemory].
     * Caches document dimensions, page counts, and extracted page texts per state/page to avoid
     * redundant Binder IPC round-trips during 120 FPS zoom, pan, and slider scrubbing gestures.
     */
    class ProxyDocumentSession internal constructor(
        private val controller: MainReaderViewModel,
        @Volatile private var sessionId: Int,
        private val uri: Uri,
        val extension: String,
        override val is3dContent: Boolean,
        private var hostPfd: ParcelFileDescriptor? = null,
        private val sessionPassword: String? = null
    ) : DocumentSession {

        fun getSessionId(): Int = sessionId

        private val lastAppliedVersion = AtomicLong(0L)
        @Volatile private var stateListener: ((Long) -> Unit)? = null
        @Volatile private var isServiceDisconnectedFlag = false
        private val recoveryMutex = Mutex()
        @Volatile private var cachedActiveSheetIndex = 0

        @Volatile private var cachedPageCount = 1
        @Volatile private var cachedDocWidth = 0f
        @Volatile private var cachedDocHeight = 0f
        @Volatile private var cachedColWidths: FloatArray = FloatArray(0)
        @Volatile private var cachedRowHeights: FloatArray = FloatArray(0)
        @Volatile private var cachedLastError: String? = null
        @Volatile private var lastMetadataPageIndex = -1
        @Volatile private var lastMetadataStateVersion = -1L

        private val pageTextCache = object : LruCache<Int, String>(64) {}

        private val callback = object : ISessionStateCallback.Stub() {
            override fun onSessionStateChanged(id: Int, stateVersion: Long) {
                if (isClosed || id != sessionId) return
                synchronized(pageTextCache) { pageTextCache.evictAll() }
                refreshCachedMetadataFromService(controller.peekIsolatedService())
                applyStateVersionIfNewer(stateVersion)
            }
        }

        private fun refreshCachedMetadataFromService(svc: IIsolatedEngineService?) {
            if (isClosed || svc == null) return
            try {
                val ver = svc.getStateVersion(sessionId)
                cachedPageCount = svc.getPageCount(sessionId).coerceAtLeast(1)
                cachedDocWidth = svc.getDocumentWidth(sessionId)
                cachedDocHeight = svc.getDocumentHeight(sessionId)
                cachedColWidths = try { svc.getColumnWidths(sessionId) ?: FloatArray(0) } catch (_: Throwable) { FloatArray(0) }
                cachedRowHeights = try { svc.getRowHeights(sessionId) ?: FloatArray(0) } catch (_: Throwable) { FloatArray(0) }
                cachedLastError = svc.getLastError(sessionId)
                lastMetadataStateVersion = ver
            } catch (_: Throwable) {}
        }

        private fun applyStateVersionIfNewer(newVersion: Long) {
            while (true) {
                val prev = lastAppliedVersion.get()
                if (newVersion <= prev) return
                if (lastAppliedVersion.compareAndSet(prev, newVersion)) {
                    val listener = stateListener ?: return
                    controller.launchImmediateMain {
                        if (!isClosed) {
                            listener(newVersion)
                        }
                    }
                    return
                }
            }
        }

        fun setSessionStateListener(listener: ((Long) -> Unit)?) {
            stateListener = listener
            if (listener != null && !isClosed) {
                controller.launchInScope {
                    try {
                        val svc = withTimeoutOrNull(8.seconds) {
                            controller.peekIsolatedService() ?: controller.getIsolatedService()
                        } ?: return@launchInScope
                        svc.registerSessionStateCallback(sessionId, callback)
                        refreshCachedMetadataFromService(svc)
                        val ver = svc.getStateVersion(sessionId)
                        applyStateVersionIfNewer(ver)
                    } catch (_: Exception) {}
                }
            }
        }

        fun onServiceDisconnected() {
            isServiceDisconnectedFlag = true
        }

        suspend fun onServiceReconnected(svc: IIsolatedEngineService) {
            if (isClosed) return
            recoveryMutex.withLock {
                if (isClosed) return
                isServiceDisconnectedFlag = false
                ensureRemoteSessionAliveLocked(svc)
            }
        }

        private fun ensureRemoteSessionAliveLocked(svc: IIsolatedEngineService): Boolean {
            return try {
                val ver = try { svc.getStateVersion(sessionId) } catch (_: Exception) { -1L }
                if (ver <= 0L) {
                    val sourcePfd: ParcelFileDescriptor? = try {
                        hostPfd?.let { ParcelFileDescriptor.dup(it.fileDescriptor) }
                    } catch (_: Throwable) {
                        null
                    } ?: if (uri.scheme == "virtual") {
                        controller.dupVirtualHexBackupPfd()
                    } else {
                        controller.openPfd(uri)
                    }
                    sourcePfd?.use { pfd ->
                        val detachedDupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                        val newId = if (!sessionPassword.isNullOrEmpty()) {
                            svc.openSessionWithPassword(detachedDupPfd, extension, sessionPassword)
                        } else {
                            svc.openSession(detachedDupPfd, extension)
                        }

                        if (newId >= 0) {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            sessionId = newId
                            lastAppliedVersion.set(0L)
                            lastMetadataPageIndex = -1
                            lastMetadataStateVersion = -1L
                            synchronized(pageTextCache) { pageTextCache.evictAll() }
                            controller.updateActiveSessionId(newId)
                            try { svc.setActiveSheetIndex(newId, cachedActiveSheetIndex) } catch (_: Exception) {}
                            val newHostDup = try { ParcelFileDescriptor.dup(pfd.fileDescriptor) } catch (_: Throwable) { null }
                            if (newHostDup != null) {
                                try { hostPfd?.close() } catch (_: Throwable) {}
                                hostPfd = newHostDup
                            }
                        } else {
                            try { detachedDupPfd.close() } catch (_: Exception) {}
                            return false
                        }
                    } ?: return false
                }
                svc.registerSessionStateCallback(sessionId, callback)
                refreshCachedMetadataFromService(svc)
                val latestVer = svc.getStateVersion(sessionId).coerceAtLeast(1L)
                val bumpTarget = maxOf(latestVer, lastAppliedVersion.get() + 1L)
                applyStateVersionIfNewer(bumpTarget)
                true
            } catch (_: Exception) {
                false
            }
        }

        init {
            try {
                val svc = controller.peekIsolatedService()
                svc?.registerSessionStateCallback(sessionId, callback)
                refreshCachedMetadataFromService(svc)
                val initialVer = svc?.getStateVersion(sessionId) ?: 1L
                lastAppliedVersion.set(initialVer)
            } catch (_: Exception) {}
        }

        @Volatile private var isClosed = false
        private val lock = Any()
        private var sharedMemory: SharedMemory? = null
        private var mappedBuffer: ByteBuffer? = null

        private fun ensureMemory(size: Int) {
            synchronized(lock) {
                if (isClosed) {
                    throw IllegalStateException("ProxyDocumentSession is closed")
                }
                val currentMem = sharedMemory
                val currentBuf = mappedBuffer
                if (currentMem != null && currentBuf != null && currentMem.size >= size) return

                mappedBuffer = null
                sharedMemory = null

                if (currentBuf != null) {
                    try { SharedMemory.unmap(currentBuf) } catch (_: Throwable) {}
                }
                if (currentMem != null) {
                    try { currentMem.close() } catch (_: Throwable) {}
                }

                val alignedSize = (size + 16383) and -16384
                val newMem = SharedMemory.create("proxy_tile_$sessionId", alignedSize)
                try {
                    mappedBuffer = newMem.mapReadOnly()
                    sharedMemory = newMem
                } catch (t: Throwable) {
                    try { newMem.close() } catch (_: Throwable) {}
                    throw t
                }
            }
        }

        override val pageCount: Int
            get() {
                if (isClosed) return cachedPageCount
                if (lastMetadataStateVersion < 0L) {
                    refreshCachedMetadataFromService(controller.peekIsolatedService())
                }
                return cachedPageCount.coerceAtLeast(1)
            }

        override val isPresentationContent: Boolean
            get() = extension.lowercase(Locale.ROOT) in setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp")

        override fun getLastError(): String? {
            if (isClosed) return null
            return try {
                if (isServiceDisconnectedFlag) {
                    val svc = controller.peekIsolatedService()
                    if (svc == null || !svc.asBinder().isBinderAlive) {
                        return controller.currentStrings().errorServiceReconnecting
                    }
                    isServiceDisconnectedFlag = false
                }
                if (lastMetadataStateVersion < 0L) {
                    refreshCachedMetadataFromService(controller.peekIsolatedService())
                }
                cachedLastError
            } catch (_: DeadObjectException) {
                controller.currentStrings().errorServiceReconnecting
            } catch (_: Exception) {
                cachedLastError
            }
        }

        override fun getStateVersion(): Long {
            if (isClosed) return lastAppliedVersion.get()
            return try {
                controller.peekIsolatedService()?.getStateVersion(sessionId) ?: lastAppliedVersion.get()
            } catch (_: Exception) {
                lastAppliedVersion.get()
            }
        }

        @Volatile
        private var cachedCopyRestricted: Boolean? = null

        override val isCopyRestricted: Boolean
            get() {
                cachedCopyRestricted?.let { return it }
                if (isClosed) return false
                val svc = controller.peekIsolatedService() ?: return false
                return try {
                    val restricted = svc.isCopyRestricted(sessionId)
                    cachedCopyRestricted = restricted
                    restricted
                } catch (_: Exception) {
                    false
                }
            }

        override fun getPageText(pageIndex: Int): String {
            if (isClosed) return ""
            val cacheKey = if (pageIndex == -1) -1 else (pageIndex and 0x000FFFFF)
            synchronized(pageTextCache) {
                pageTextCache.get(cacheKey)?.let { return it }
            }
            val fetched = try {
                controller.peekIsolatedService()?.getPageText(sessionId, pageIndex) ?: ""
            } catch (_: Exception) {
                ""
            }
            if (fetched.isNotEmpty() && fetched.length <= 512_000) {
                synchronized(pageTextCache) {
                    pageTextCache.put(cacheKey, fetched)
                }
            }
            return fetched
        }

        override fun getDocumentWidth(): Float {
            if (isClosed) return cachedDocWidth
            if (cachedDocWidth <= 0f && lastMetadataStateVersion < 0L) {
                refreshCachedMetadataFromService(controller.peekIsolatedService())
            }
            return cachedDocWidth
        }

        override fun getDocumentHeight(): Float {
            if (isClosed) return cachedDocHeight
            if (cachedDocHeight <= 0f && lastMetadataStateVersion < 0L) {
                refreshCachedMetadataFromService(controller.peekIsolatedService())
            }
            return cachedDocHeight
        }

        override fun getSheetNames(): List<String> {
            return try { controller.peekIsolatedService()?.getSheetNames(sessionId)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        override fun getActiveSheetIndex(): Int {
            return try {
                val idx = controller.peekIsolatedService()?.getActiveSheetIndex(sessionId) ?: cachedActiveSheetIndex
                cachedActiveSheetIndex = idx
                idx
            } catch (_: Exception) {
                cachedActiveSheetIndex
            }
        }

        override fun setActiveSheetIndex(index: Int) {
            cachedActiveSheetIndex = index
            lastMetadataPageIndex = -1
            cachedColWidths = FloatArray(0)
            cachedRowHeights = FloatArray(0)
            synchronized(pageTextCache) { pageTextCache.evictAll() }
            try {
                val svc = controller.peekIsolatedService()
                svc?.setActiveSheetIndex(sessionId, index)
                refreshCachedMetadataFromService(svc)
            } catch (_: Exception) {}
        }

        override fun getColumnWidths(): FloatArray {
            if (isClosed) return cachedColWidths
            if (cachedColWidths.isEmpty() && lastMetadataStateVersion < 0L) {
                refreshCachedMetadataFromService(controller.peekIsolatedService())
            }
            if (cachedColWidths.isEmpty()) {
                val fetched = try {
                    controller.peekIsolatedService()?.getColumnWidths(sessionId) ?: FloatArray(0)
                } catch (_: Exception) {
                    FloatArray(0)
                }
                if (fetched.isNotEmpty()) {
                    cachedColWidths = fetched
                }
            }
            return cachedColWidths
        }

        override fun getRowHeights(): FloatArray {
            if (isClosed) return cachedRowHeights
            if (cachedRowHeights.isEmpty() && lastMetadataStateVersion < 0L) {
                refreshCachedMetadataFromService(controller.peekIsolatedService())
            }
            return cachedRowHeights
        }

        override fun getCellText(row: Int, col: Int): String {
            return try { controller.peekIsolatedService()?.getCellText(sessionId, row, col) ?: "" } catch (_: Exception) { "" }
        }

        override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
            return try { controller.peekIsolatedService()?.getSelectedRangeText(sessionId, startRow, startCol, endRow, endCol) ?: "" } catch (_: Exception) { "" }
        }

        override fun getMissingResources(): List<String> {
            if (isClosed) return emptyList()
            return try { controller.peekIsolatedService()?.getMissingResources(sessionId)?.toList() } catch (_: Exception) { null } ?: emptyList()
        }

        override fun provideResource(name: String, fd: java.io.FileDescriptor): Boolean {
            if (isClosed) return false
            val svc = controller.peekIsolatedService() ?: return false
            return try {
                ParcelFileDescriptor.dup(fd).use { svc.provideResource(sessionId, name, it) }
            } catch (_: Exception) {
                false
            }
        }

        override fun applyProvidedResources(): Boolean {
            if (isClosed) return false
            val svc = controller.peekIsolatedService() ?: return false
            val ok = try { svc.applyProvidedResources(sessionId) } catch (_: Exception) { false }
            synchronized(lock) {
                hostMeshScene?.let { scene ->
                    scene.preview?.let { if (!it.isRecycled) it.recycle() }
                    scene.textures.forEach { if (!it.isRecycled) it.recycle() }
                }
                hostMeshScene = null
                try { hostMeshRenderer?.release() } catch (_: Throwable) {}
                hostMeshRenderer = null
            }
            try { refreshCachedMetadataFromService(svc) } catch (_: Exception) {}
            applyStateVersionIfNewer(lastAppliedVersion.get() + 1L)
            return ok
        }

        @Volatile private var hostMeshScene: com.omnireader.core.engine.MeshScene? = null
        private var hostMeshRenderer: com.omnireader.core.engine.MeshSceneRenderer? = null
        @Volatile private var animationTime = -1f
        @Volatile private var animationClip = 0

        override fun getAnimationDuration(): Float {
            if (isClosed || !is3dContent) return 0f
            return synchronized(lock) { loadHostMeshSceneLocked()?.animation?.duration(animationClip) ?: 0f }
        }

        override fun setAnimationTime(seconds: Float) {
            animationTime = seconds
        }

        override fun getAnimationClips(): List<String> {
            if (isClosed || !is3dContent) return emptyList()
            return synchronized(lock) {
                val a = loadHostMeshSceneLocked()?.animation ?: return@synchronized emptyList()
                List(a.clipCount) { a.clipName(it) }
            }
        }

        override fun setAnimationClip(index: Int) {
            animationClip = index.coerceAtLeast(0)
        }

        private fun loadHostMeshSceneLocked(): com.omnireader.core.engine.MeshScene? {
            hostMeshScene?.let { return it }
            val svc = controller.peekIsolatedService() ?: return null
            val memory = try { svc.exportMeshScene(sessionId) } catch (_: Exception) { null } ?: return null
            var mapped: java.nio.ByteBuffer? = null
            return try {
                mapped = memory.mapReadOnly()
                com.omnireader.core.engine.MeshSceneCodec.decode(mapped).also { hostMeshScene = it }
            } catch (_: Throwable) {
                null
            } finally {
                if (mapped != null) try { SharedMemory.unmap(mapped) } catch (_: Throwable) {}
                try { memory.close() } catch (_: Throwable) {}
            }
        }

        override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
            if (isClosed) return
            if (is3dContent) {
                synchronized(lock) {
                    if (isClosed) return
                    val scene = loadHostMeshSceneLocked()
                    if (scene != null) {
                        val renderer = hostMeshRenderer ?: com.omnireader.core.engine.MeshSceneRenderer().also { hostMeshRenderer = it }
                        renderer.animationTime = animationTime
                        renderer.animationClip = animationClip
                        try {
                            renderer.render(scene, pageIndex, tileX, tileY, zoomPermille, bitmap)
                        } catch (_: Throwable) {}
                        return
                    }
                }
            }
            synchronized(lock) {
                if (isClosed) return
                val w = bitmap.width
                val h = bitmap.height
                try {
                    ensureMemory(w * h * 4)
                } catch (_: Throwable) {
                    return
                }

                val mem = sharedMemory ?: return
                val buf = mappedBuffer ?: return

                val svc = controller.peekIsolatedService()
                val success = try {
                    if (svc != null && svc.asBinder().isBinderAlive) {
                        val ok = svc.renderTile(sessionId, pageIndex, tileX, tileY, zoomPermille, w, h, mem)
                        val cleanPage = pageIndex and 0x000FFFFF
                        if (ok && (cleanPage != lastMetadataPageIndex || lastMetadataStateVersion < 0L || cachedDocWidth <= 0f)) {
                            lastMetadataPageIndex = cleanPage
                            refreshCachedMetadataFromService(svc)
                        }
                        ok
                    } else {
                        recoverAndRender(pageIndex, tileX, tileY, zoomPermille, w, h, mem)
                    }
                } catch (_: RemoteException) {
                    recoverAndRender(pageIndex, tileX, tileY, zoomPermille, w, h, mem)
                } catch (_: Exception) {
                    false
                }

                if (success && !isClosed && !bitmap.isRecycled) {
                    try {
                        buf.position(0)
                        bitmap.copyPixelsFromBuffer(buf)
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun recoverAndRender(
            page: Int,
            tx: Int,
            ty: Int,
            zoom: Int,
            w: Int,
            h: Int,
            mem: SharedMemory
        ): Boolean {
            if (isClosed) return false
            return runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5.seconds) {
                    recoveryMutex.withLock {
                        if (isClosed) return@withLock false
                        try {
                            val svc = controller.getIsolatedService()
                            if (ensureRemoteSessionAliveLocked(svc)) {
                                val ok = svc.renderTile(sessionId, page, tx, ty, zoom, w, h, mem)
                                if (ok) {
                                    lastMetadataPageIndex = page and 0x000FFFFF
                                    refreshCachedMetadataFromService(svc)
                                }
                                return@withLock ok
                            }
                        } catch (_: Exception) {}
                        false
                    }
                } ?: false
            }
        }

        override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
            renderTile(key.zoom, key.x, key.y, 1000, bitmap)
        }

        override fun search(query: String, startPage: Int): Long {
            if (isClosed || query.isBlank()) return -1L
            return try { controller.peekIsolatedService()?.search(sessionId, query, startPage) ?: -1L } catch (_: Exception) { -1L }
        }

        override fun close() {
            if (isClosed) return
            val idToClose: Int
            val svcToCall: IIsolatedEngineService?
            synchronized(pageTextCache) { pageTextCache.evictAll() }
            synchronized(lock) {
                if (isClosed) return
                isClosed = true
                stateListener = null
                idToClose = sessionId
                svcToCall = controller.peekIsolatedService()

                val buf = mappedBuffer
                val mem = sharedMemory
                mappedBuffer = null
                sharedMemory = null
                if (buf != null) {
                    try { SharedMemory.unmap(buf) } catch (_: Throwable) {}
                }
                if (mem != null) {
                    try { mem.close() } catch (_: Throwable) {}
                }
                try { hostPfd?.close() } catch (_: Throwable) {}
                try { hostMeshRenderer?.release() } catch (_: Throwable) {}
                hostMeshRenderer = null
                hostMeshScene?.preview?.let { if (!it.isRecycled) it.recycle() }
                hostMeshScene?.textures?.forEach { if (!it.isRecycled) it.recycle() }
                hostMeshScene = null
            }
            try { svcToCall?.registerSessionStateCallback(idToClose, null) } catch (_: Throwable) {}
            try { svcToCall?.closeSession(idToClose) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_LAST_OPENED_URI = "last_opened_uri"
        private const val KEY_CONSUMED_INTENT_URI = "consumed_intent_uri"
        internal val ARCHIVE_SIZE_SUFFIX_REGEX = Regex(""" \(\d+ B\)$| \(unknown size\)$""", RegexOption.IGNORE_CASE)

        internal val MODULE_MANAGED_EXTENSIONS = setOf(
            // Office & E-Books module
            "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf", "one",
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "ods", "ots", "csv", "tsv",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp",
            "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc",
            // CAD & Vector module
            "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off",
            "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
            "3mf", "dae", "zae", "step", "stp", "iges", "igs",
            // Archives & Disk Images module
            "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "apk", "jar", "iso"
        )

        internal val CORE_TEXT_AND_BINARY_EXTENSIONS = setOf(
            "pdf", "djvu", "djv",
            "ttf", "otf", "ttc", "woff", "woff2", "dfont",
            "db", "db3", "s3db", "sl3", "sqlite", "sqlite3", "accdb", "mdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson",
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur",
            "mp4", "mkv", "webm", "mov", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus",
            "txt", "log", "json", "xml", "md", "ini", "cfg", "yaml", "yml",
            "html", "htm", "xhtml", "mhtml", "mht", "py", "js", "ts", "kt", "java", "c", "cpp", "h", "hpp",
            "sh", "sql", "css", "properties", "toml", "gradle",
            "hex", "bin", "elf", "dex", "so", "max", "rvt", "indd"
        )
    }
}
