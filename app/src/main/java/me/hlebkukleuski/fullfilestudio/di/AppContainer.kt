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

package me.hlebkukleuski.fullfilestudio.di

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Stable
import java.io.FileDescriptor
import me.hlebkukleuski.fullfilestudio.FullFileStudioApp
import me.hlebkukleuski.fullfilestudio.bootstrap.FirstLaunchEngineBootstrapper
import me.hlebkukleuski.fullfilestudio.core.database.UniversalHexInspectorImpl
import me.hlebkukleuski.fullfilestudio.core.document.DocumentRendererEngineImpl
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.media.MediaEngineImpl
import me.hlebkukleuski.fullfilestudio.core.media.MediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.data.settings.AppSettingsManager
import me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController
import me.hlebkukleuski.fullfilestudio.ui.reader.AndroidIsolatedRenderClient
import me.hlebkukleuski.fullfilestudio.ui.reader.DocumentIntentResolver
import me.hlebkukleuski.fullfilestudio.ui.reader.IsolatedRenderClient

@Stable
interface AppContainer {
    val settingsManager: AppSettingsManager
    val bootstrapper: FirstLaunchEngineBootstrapper
    val documentIntentResolver: DocumentIntentResolver

    fun createIsolatedRenderClient(timeoutMessageProvider: () -> String): IsolatedRenderClient

    fun classifyHostOrMediaFormat(
        format: DetectedFormat,
        effectiveExt: String,
        headerSignature: ByteArray
    ): Pair<Boolean, Boolean>

    fun createHostOrMediaSession(
        uri: Uri,
        sourcePfd: ParcelFileDescriptor,
        format: DetectedFormat,
        effectiveExt: String,
        isMedia: Boolean,
        isHostDecodedImage: Boolean,
        allowMediaReuse: Boolean
    ): DocumentSession

    fun createLocalHexSession(fd: FileDescriptor): DocumentSession
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext: Context = context.applicationContext ?: context

    override val settingsManager: AppSettingsManager = AppSettingsManager(appContext)
    override val bootstrapper: FirstLaunchEngineBootstrapper = FirstLaunchEngineBootstrapper(
        context = appContext,
        settingsManager = settingsManager
    )
    override val documentIntentResolver: DocumentIntentResolver = DocumentIntentResolver(appContext)

    override fun createIsolatedRenderClient(timeoutMessageProvider: () -> String): IsolatedRenderClient {
        return AndroidIsolatedRenderClient(
            appContext = appContext,
            timeoutMessageProvider = timeoutMessageProvider
        )
    }

    override fun classifyHostOrMediaFormat(
        format: DetectedFormat,
        effectiveExt: String,
        headerSignature: ByteArray
    ): Pair<Boolean, Boolean> {
        val isGraphicFormat = format in setOf(
            DetectedFormat.PNG, DetectedFormat.JPEG, DetectedFormat.WEBP,
            DetectedFormat.GIF, DetectedFormat.BMP, DetectedFormat.TIFF,
            DetectedFormat.HEIC, DetectedFormat.AVIF, DetectedFormat.SVG, DetectedFormat.SVGZ
        )
        val mediaEngine = MediaEngineImpl()
        val isMedia = !isGraphicFormat && (
            format in setOf(DetectedFormat.MP4, DetectedFormat.MP3) ||
                mediaEngine.canHandle(headerSignature, effectiveExt)
            )
        val isHostDecodedImage = format in setOf(DetectedFormat.HEIC, DetectedFormat.AVIF) ||
            effectiveExt in setOf("heic", "heif", "hif", "avif", "avifs")
        return isMedia to isHostDecodedImage
    }

    override fun createHostOrMediaSession(
        uri: Uri,
        sourcePfd: ParcelFileDescriptor,
        format: DetectedFormat,
        effectiveExt: String,
        isMedia: Boolean,
        isHostDecodedImage: Boolean,
        allowMediaReuse: Boolean
    ): DocumentSession {
        val reused = if (isMedia && allowMediaReuse) {
            MediaPlaybackController.sessionForUri(uri.toString())
        } else {
            null
        }
        val dupPfd = if (reused == null) ParcelFileDescriptor.dup(sourcePfd.fileDescriptor) else null
        val underlyingSession = reused ?: if (isHostDecodedImage) {
            DocumentRendererEngineImpl().createSessionForFormat(dupPfd!!.fileDescriptor, format, effectiveExt)
        } else {
            MediaEngineImpl().createSession(dupPfd!!.fileDescriptor).also { session ->
                if (allowMediaReuse) {
                    (session as? MediaPlaybackSession)?.sourceUri = uri.toString()
                }
            }
        }
        return object : DocumentSession by underlyingSession {
            override fun close() {
                try {
                    val media = underlyingSession as? MediaPlaybackSession
                    if (media == null || !MediaPlaybackController.releaseFromUi(media)) {
                        underlyingSession.close()
                    }
                } finally {
                    try { dupPfd?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun createLocalHexSession(fd: FileDescriptor): DocumentSession {
        return UniversalHexInspectorImpl().createSession(fd)
    }
}

private val fallbackContainerLock = Any()
private var fallbackContainerInstance: AppContainer? = null

fun Context.appContainer(): AppContainer {
    val app = applicationContext
    if (app is FullFileStudioApp && app.isContainerInitialized) {
        return app.container
    }
    synchronized(fallbackContainerLock) {
        val existing = fallbackContainerInstance
        if (existing != null) return existing
        val created = DefaultAppContainer(app ?: this)
        fallbackContainerInstance = created
        return created
    }
}
