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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.omnireader.core.engine.OmniServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.net.Tls13Https
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal class StandaloneUpdateManager(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(StandaloneUpdateUiState())
    val state: StateFlow<StandaloneUpdateUiState> = _state.asStateFlow()

    fun checkForUpdates(manual: Boolean = false) {
        if (!BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) return
        if (_state.value.isChecking || _state.value.isDownloading) return

        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isChecking = true, errorText = null, isUpToDate = false) }
            try {
                val conn = Tls13Https.open(OmniServerEndpoints.appVersionManifestUrl).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    _state.update { it.copy(isChecking = false) }
                    return@launch
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val remoteCode = json.optInt("versionCode", BuildConfig.VERSION_CODE)
                val remoteName = json.optString("versionName", BuildConfig.VERSION_NAME)
                val apkUrl = json.optString("apkUrl", "").trim()
                val notes = json.optString("releaseNotes", "").trim()

                if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.startsWith("https://")) {
                    _state.update {
                        it.copy(
                            isChecking = false,
                            isUpToDate = false,
                            availableRelease = StandaloneReleaseInfo(
                                versionCode = remoteCode,
                                versionName = remoteName,
                                apkUrl = apkUrl,
                                releaseNotes = notes
                            )
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isChecking = false,
                            isUpToDate = manual
                        )
                    }
                }
            } catch (_: Throwable) {
                _state.update { it.copy(isChecking = false) }
            }
        }
    }

    fun dismissDialog() {
        if (_state.value.isDownloading) return
        _state.update { it.copy(availableRelease = null, errorText = null) }
    }

    fun downloadAndInstall(activityContext: Context) {
        val release = _state.value.availableRelease ?: return
        if (_state.value.isDownloading) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activityContext.startActivity(permIntent)
            } catch (_: Throwable) {}
            return
        }

        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isDownloading = true, downloadProgress = 0f, errorText = null) }
            try {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "fullfilestudio-update.apk")
                if (apkFile.exists()) apkFile.delete()

                var currentUrl = release.apkUrl
                var conn: HttpURLConnection? = null
                for (redirect in 0..5) {
                    val c = Tls13Https.open(currentUrl).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 10000
                        readTimeout = 20000
                        requestMethod = "GET"
                    }
                    val status = c.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        val nextLoc = c.getHeaderField("Location")
                        c.disconnect()
                        if (nextLoc.isNullOrBlank()) throw IllegalStateException("Empty redirect")
                        currentUrl = URL(URL(currentUrl), nextLoc).toString()
                    } else {
                        conn = c
                        break
                    }
                }

                val activeConn = conn ?: throw IllegalStateException("Unable to connect to release server")
                if (activeConn.responseCode !in 200..299) {
                    activeConn.disconnect()
                    throw IllegalStateException("HTTP ${activeConn.responseCode}")
                }

                val totalBytes = activeConn.contentLengthLong
                activeConn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(16384)
                        var downloaded = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            downloaded += r
                            if (totalBytes > 0L) {
                                val pct = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                                _state.update { it.copy(downloadProgress = pct) }
                            }
                        }
                        output.flush()
                    }
                }
                activeConn.disconnect()

                val apkUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            availableRelease = null
                        )
                    }
                    activityContext.startActivity(installIntent)
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        errorText = e.localizedMessage ?: "Download failed"
                    )
                }
            }
        }
    }
}
