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
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.engine.ServerEndpoints
import me.hlebkukleuski.fullfilestudio.net.Tls13Https
import org.json.JSONObject

@Stable
internal class StandaloneUpdateManager(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val MAX_APK_SIZE_BYTES = 512L * 1024L * 1024L
        private const val RELEASE_HOST = "github.com"
        private const val RELEASE_PATH_PREFIX = "/hlebkukleuski/FullFileStudio/releases/download/"
        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        internal fun isAllowedReleaseUrl(url: String): Boolean {
            val parsed = try { URI(url.trim()) } catch (_: Throwable) { return false }
            if (!parsed.scheme.equals("https", ignoreCase = true)) return false
            if (!parsed.host.equals(RELEASE_HOST, ignoreCase = true)) return false
            if (parsed.userInfo != null) return false
            val rawPath = parsed.rawPath ?: return false
            if (rawPath.contains("..") || rawPath.contains("//")) return false
            val path = parsed.normalize().path ?: return false
            if (!path.startsWith(RELEASE_PATH_PREFIX)) return false
            return path.endsWith(".apk", ignoreCase = true)
        }

        internal fun isAllowedRedirectUrl(url: String): Boolean {
            val parsed = try { URI(url.trim()) } catch (_: Throwable) { return false }
            if (!parsed.scheme.equals("https", ignoreCase = true)) return false
            if (parsed.userInfo != null) return false
            val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
            return host in ALLOWED_DOWNLOAD_HOSTS
        }

        internal fun sha256Hex(digest: ByteArray): String =
            digest.joinToString("") { "%02x".format(Locale.US, it) }

        internal fun signaturesMatch(installed: Array<Signature>?, candidate: Array<Signature>?): Boolean {
            if (installed.isNullOrEmpty() || candidate.isNullOrEmpty()) return false
            val installedSet = installed.map { it.toByteArray().contentHashCode() to it.toByteArray() }
            val candidateBytes = candidate.map { it.toByteArray() }
            return installedSet.any { (_, instBytes) ->
                candidateBytes.any { candBytes -> instBytes.contentEquals(candBytes) }
            }
        }
    }

    private val _state = MutableStateFlow(StandaloneUpdateUiState())
    val state: StateFlow<StandaloneUpdateUiState> = _state.asStateFlow()

    fun checkForUpdates(manual: Boolean = false) {
        if (!BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) return
        if (_state.value.isChecking || _state.value.isDownloading) return

        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isChecking = true, errorText = null, isUpToDate = false) }
            try {
                val conn = Tls13Https.open("${ServerEndpoints.baseUrl}/version.json").apply {
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
                val sha256 = json.optString("sha256", "").trim().lowercase(Locale.ROOT).takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

                if (remoteCode > BuildConfig.VERSION_CODE && isAllowedReleaseUrl(apkUrl)) {
                    _state.update {
                        it.copy(
                            isChecking = false,
                            isUpToDate = false,
                            availableRelease = StandaloneReleaseInfo(
                                versionCode = remoteCode,
                                versionName = remoteName,
                                apkUrl = apkUrl,
                                releaseNotes = notes,
                                sha256 = sha256
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

    private fun verifyDownloadedApk(apkFile: File, expectedSha256: String?, actualSha256: String) {
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            throw SecurityException("APK SHA-256 checksum mismatch.")
        }
        val pm = appContext.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("Downloaded file is not a valid Android APK.")
        if (archiveInfo.packageName != appContext.packageName) {
            throw SecurityException("APK package name mismatch: ${archiveInfo.packageName}")
        }
        val installedInfo = pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archiveInfo.longVersionCode <= installedInfo.longVersionCode) {
            throw SecurityException("Downloaded APK version is not newer than installed version.")
        }
        val installedSigs = installedInfo.signingInfo?.let { info ->
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }
        val archiveSigs = archiveInfo.signingInfo?.let { info ->
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }
        if (!signaturesMatch(installedSigs, archiveSigs)) {
            throw SecurityException("APK signing certificate does not match installed application.")
        }
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
            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "fullfilestudio-update.apk")
            try {
                if (!isAllowedReleaseUrl(release.apkUrl)) {
                    throw SecurityException("Untrusted release download URL.")
                }
                if (apkFile.exists()) apkFile.delete()

                var currentUrl = release.apkUrl
                var conn: HttpURLConnection? = null
                for (redirect in 0..5) {
                    if (!isAllowedRedirectUrl(currentUrl)) {
                        throw SecurityException("Redirect to untrusted host blocked.")
                    }
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
                if (totalBytes > MAX_APK_SIZE_BYTES) {
                    activeConn.disconnect()
                    throw SecurityException("APK size exceeds limit.")
                }

                val sha256Digest = MessageDigest.getInstance("SHA-256")
                activeConn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(16384)
                        var downloaded = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            downloaded += r
                            if (downloaded > MAX_APK_SIZE_BYTES) {
                                throw SecurityException("APK download exceeded maximum allowed size.")
                            }
                            sha256Digest.update(buf, 0, r)
                            output.write(buf, 0, r)
                            if (totalBytes > 0L) {
                                val pct = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                                _state.update { it.copy(downloadProgress = pct) }
                            }
                        }
                        output.flush()
                    }
                }
                activeConn.disconnect()

                verifyDownloadedApk(apkFile, release.sha256, sha256Hex(sha256Digest.digest()))

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
                try { if (apkFile.exists()) apkFile.delete() } catch (_: Throwable) {}
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
