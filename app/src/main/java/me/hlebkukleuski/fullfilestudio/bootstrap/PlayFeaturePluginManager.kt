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
import android.os.Handler
import android.os.Looper
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.omnireader.core.engine.DetectedFormat
import dalvik.system.PathClassLoader
import me.hlebkukleuski.fullfilestudio.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PlayFeatureBundle(
    val id: String,
    val name: String,
    val description: String,
    val sizeMb: Float,
    val extensions: Set<String>,
    val formats: Set<DetectedFormat>,
    val serverFallbackUrl: String? = null
)

class PlayFeaturePluginManager(private val context: Context) {

    private val splitInstallManager: SplitInstallManager = SplitInstallManagerFactory.create(context)
    private val prefs = context.getSharedPreferences("play_feature_prefs", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val splitClassLoaders = ConcurrentHashMap<String, ClassLoader>()

    val availableBundles = listOf(
        PlayFeatureBundle(
            id = "office",
            name = "Office Documents, Spreadsheets, Presentations & E-Books",
            description = "DOCX, DOCM, DOTX, DOC, ODT, RTF, ONE, XLSX, XLSM, XLSB, XLS, ODS, CSV, TSV, PPTX, PPTM, PPSX, POTX, PPT, ODP, EPUB, FB2, AZW3, MOBI",
            sizeMb = 0.6f,
            extensions = setOf(
                "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf", "one",
                "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "ods", "ots", "csv", "tsv",
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp",
                "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc"
            ),
            formats = setOf(
                DetectedFormat.DOCX, DetectedFormat.DOC,
                DetectedFormat.ODT, DetectedFormat.RTF,
                DetectedFormat.ONE,
                DetectedFormat.XLSX, DetectedFormat.XLS,
                DetectedFormat.ODS, DetectedFormat.PPTX,
                DetectedFormat.PPT, DetectedFormat.ODP,
                DetectedFormat.OFFICE_XML,
                DetectedFormat.EPUB, DetectedFormat.FB2,
                DetectedFormat.AZW3, DetectedFormat.MOBI
            )
        ),
        PlayFeatureBundle(
            id = "cadvector",
            name = "Vector Graphics & 3D CAD Models",
            description = "SVG, SVGZ, STL, OBJ, DXF, DWG, PLY, OFF, 3DS, GLTF, GLB, BLEND, USDZ, FBX, 3MF, DAE, STEP, IGES",
            sizeMb = 0.4f,
            extensions = setOf(
                "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off",
                "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            ),
            formats = setOf(
                DetectedFormat.SVG, DetectedFormat.SVGZ,
                DetectedFormat.STL, DetectedFormat.OBJ,
                DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB,
                DetectedFormat.BLEND, DetectedFormat.USDZ
            )
        ),
        PlayFeatureBundle(
            id = "archives",
            name = "Archives, Packages & Disk Images",
            description = "ZIP, 7Z, RAR4, RAR5, TAR, GZ, BZ2, XZ, TGZ, APK, JAR, ISO",
            sizeMb = 0.5f,
            extensions = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "apk", "jar", "iso"),
            formats = setOf(
                DetectedFormat.ZIP, DetectedFormat.SEVEN_ZIP,
                DetectedFormat.RAR_V4, DetectedFormat.RAR_V5,
                DetectedFormat.TAR, DetectedFormat.GZ,
                DetectedFormat.BZ2, DetectedFormat.XZ,
                DetectedFormat.APK, DetectedFormat.ISO
            )
        )
    )

    init {
        if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
            markModulesInstalledSynchronously(availableBundles.map { it.id })
        }
    }

    private fun factoryClassNameForModule(moduleName: String): String? = when (moduleName) {
        "office" -> "me.hlebkukleuski.fullfilestudio.feature.office.OfficeEngineFactory"
        "cadvector" -> "me.hlebkukleuski.fullfilestudio.feature.cadvector.CadVectorEngineFactory"
        "archives" -> "me.hlebkukleuski.fullfilestudio.feature.archives.ArchiveEngineFactory"
        else -> null
    }

    fun isFactoryClassAvailable(moduleName: String): Boolean {
        val className = factoryClassNameForModule(moduleName) ?: return false

        val candidateLoaders = listOfNotNull(
            splitClassLoaders[moduleName],
            context.classLoader,
            PlayFeaturePluginManager::class.java.classLoader,
            Thread.currentThread().contextClassLoader
        )
        for (loader in candidateLoaders) {
            try {
                Class.forName(className, false, loader)
                return true
            } catch (_: Throwable) {}
        }

        try {
            val splitCtx = context.createContextForSplit(moduleName)
            val splitLoader = splitCtx.classLoader
            if (splitLoader != null) {
                Class.forName(className, false, splitLoader)
                splitClassLoaders[moduleName] = splitLoader
                return true
            }
        } catch (_: Throwable) {}

        try {
            val appInfo = context.applicationInfo
            val splitDirs = appInfo.splitSourceDirs
            if (!splitDirs.isNullOrEmpty()) {
                for (splitPath in splitDirs) {
                    val fileName = File(splitPath).name.lowercase(Locale.ROOT)
                    if (fileName.contains(moduleName.lowercase(Locale.ROOT)) || splitDirs.size <= 6) {
                        try {
                            val loader = splitClassLoaders.getOrPut(splitPath) {
                                PathClassLoader(splitPath, appInfo.nativeLibraryDir, context.classLoader)
                            }
                            Class.forName(className, false, loader)
                            splitClassLoaders[moduleName] = loader
                            return true
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        return try {
            SplitCompat.install(context)
            Class.forName(className, false, context.classLoader)
            true
        } catch (_: Throwable) {
            try {
                val pkgCtx = context.createPackageContext(
                    context.packageName,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                SplitCompat.install(pkgCtx)
                Class.forName(className, false, pkgCtx.classLoader)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun markModulesInstalledSynchronously(modules: Collection<String>) {
        val editor = prefs.edit()
        modules.forEach { editor.putBoolean("module_installed_$it", true) }
        editor.commit()
        try {
            SplitCompat.install(context)
        } catch (_: Throwable) {}
    }

    fun isModuleDownloaded(moduleName: String): Boolean {
        if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
            if (!prefs.getBoolean("module_installed_$moduleName", false)) {
                markModulesInstalledSynchronously(listOf(moduleName))
            } else {
                try { SplitCompat.install(context) } catch (_: Throwable) {}
            }
            return true
        }

        if (isFactoryClassAvailable(moduleName)) {
            if (!prefs.getBoolean("module_installed_$moduleName", false)) {
                markModulesInstalledSynchronously(listOf(moduleName))
            }
            return true
        }

        val isInstalledViaPlay = try {
            splitInstallManager.installedModules.contains(moduleName)
        } catch (_: Throwable) {
            false
        }
        val isMarkedInPrefs = prefs.getBoolean("module_installed_$moduleName", false)

        if (isInstalledViaPlay || isMarkedInPrefs) {
            if (!isMarkedInPrefs) {
                markModulesInstalledSynchronously(listOf(moduleName))
            } else {
                try { SplitCompat.install(context) } catch (_: Throwable) {}
            }
            return true
        }

        return false
    }

    fun getBundleForExtension(ext: String): PlayFeatureBundle? {
        val cleanExt = ext.lowercase(Locale.ROOT).trim()
        if (cleanExt.isEmpty()) return null
        return availableBundles.firstOrNull { cleanExt in it.extensions }
    }

    fun getBundleForFormat(format: DetectedFormat, ext: String): PlayFeatureBundle? {
        if (format != DetectedFormat.UNKNOWN &&
            format != DetectedFormat.PLAINTEXT &&
            format != DetectedFormat.CORRUPTED_CONTAINER
        ) {
            availableBundles.firstOrNull { format in it.formats }?.let { return it }
        }
        return getBundleForExtension(ext)
    }

    fun downloadModules(
        moduleNames: List<String>,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    ) {
        if (BuildConfig.ALLOW_CUSTOM_SERVER_DOWNLOADS) {
            markModulesInstalledSynchronously(moduleNames)
            onProgress(1.0f)
            onSuccess()
            return
        }

        val missing = moduleNames.filter { !isModuleDownloaded(it) }
        if (missing.isEmpty()) {
            markModulesInstalledSynchronously(moduleNames)
            onProgress(1.0f)
            onSuccess()
            return
        }

        downloadViaGooglePlaySplitInstall(missing, onProgress, onSuccess, onError)
    }

    private fun downloadViaGooglePlaySplitInstall(
        missing: List<String>,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit
    ) {
        val mySessionId = AtomicInteger(-1)
        val isCompleted = AtomicBoolean(false)
        val requestedSet = missing.toSet()
        var listener: SplitInstallStateUpdatedListener? = null

        listener = SplitInstallStateUpdatedListener { state: SplitInstallSessionState ->
            val currentSessionId = mySessionId.get()
            val matchesSession = (currentSessionId >= 0 && state.sessionId() == currentSessionId) ||
                    (currentSessionId == -1 && state.moduleNames().containsAll(requestedSet))

            if (matchesSession && !isCompleted.get()) {
                when (state.status()) {
                    SplitInstallSessionStatus.DOWNLOADING, SplitInstallSessionStatus.INSTALLING -> {
                        val total = state.totalBytesToDownload()
                        val downloaded = state.bytesDownloaded()
                        val progress = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.99f) else 0.5f
                        onProgress(progress)
                    }
                    SplitInstallSessionStatus.INSTALLED -> {
                        if (isCompleted.compareAndSet(false, true)) {
                            listener?.let { splitInstallManager.unregisterListener(it) }
                            markModulesInstalledSynchronously(missing)
                            onProgress(1.0f)
                            onSuccess()
                        }
                    }
                    SplitInstallSessionStatus.FAILED -> {
                        if (isCompleted.compareAndSet(false, true)) {
                            listener?.let { splitInstallManager.unregisterListener(it) }
                            if (missing.all { isFactoryClassAvailable(it) }) {
                                markModulesInstalledSynchronously(missing)
                                onProgress(1.0f)
                                onSuccess()
                            } else {
                                onError(state.errorCode())
                            }
                        }
                    }
                    SplitInstallSessionStatus.CANCELED, SplitInstallSessionStatus.CANCELING -> {
                        if (isCompleted.compareAndSet(false, true)) {
                            listener?.let { splitInstallManager.unregisterListener(it) }
                            onError(-1)
                        }
                    }
                    else -> {}
                }
            }
        }

        splitInstallManager.registerListener(listener)

        val request = SplitInstallRequest.newBuilder().apply {
            missing.forEach { addModule(it) }
        }.build()

        splitInstallManager.startInstall(request)
            .addOnSuccessListener { sessionId ->
                mySessionId.set(sessionId)
                if (sessionId == 0 && isCompleted.compareAndSet(false, true)) {
                    listener?.let { splitInstallManager.unregisterListener(it) }
                    markModulesInstalledSynchronously(missing)
                    onProgress(1.0f)
                    onSuccess()
                }
            }
            .addOnFailureListener { exception ->
                if (missing.all { isFactoryClassAvailable(it) } &&
                    isCompleted.compareAndSet(false, true)
                ) {
                    listener?.let { splitInstallManager.unregisterListener(it) }
                    markModulesInstalledSynchronously(missing)
                    mainHandler.post {
                        onProgress(0.65f)
                    }
                    mainHandler.postDelayed({
                        onProgress(1.0f)
                        onSuccess()
                    }, 150)
                } else if (isCompleted.compareAndSet(false, true)) {
                    listener?.let { splitInstallManager.unregisterListener(it) }
                    val errorCode = if (exception is SplitInstallException) exception.errorCode else -1
                    onError(errorCode)
                }
            }
    }

    fun isFirstLaunchCompleted(): Boolean = prefs.getBoolean("first_launch_complete", false)
    fun setFirstLaunchCompleted() = prefs.edit().putBoolean("first_launch_complete", true).apply()
}