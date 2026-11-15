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

package me.hlebkukleuski.fullfilestudio.service

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.google.android.play.core.splitcompat.SplitCompat
import com.omnireader.core.engine.AdaptiveRingBitmapPool
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.DynamicEngineFactory
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.IIsolatedEngineService
import com.omnireader.core.engine.ISessionStateCallback
import com.omnireader.core.engine.LegalLicensePolicy
import com.omnireader.core.engine.MaliciousHeaderSanitizer
import com.omnireader.core.engine.SecurityArena
import com.omnireader.core.engine.impl.CleanRoomLegacyAndIsoEngine
import com.omnireader.core.engine.impl.DatabaseInspectorEngineImpl
import com.omnireader.core.engine.impl.DocumentRendererEngineImpl
import com.omnireader.core.engine.impl.SqliteDatabaseSession
import com.omnireader.core.engine.impl.UniversalHexInspectorImpl
import com.omnireader.core.engine.modules.ArchiveSession
import dalvik.system.PathClassLoader
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private class ErrorReportingDocumentSession(
    private val errorMessage: String
) : DocumentSession {
    override val pageCount: Int = 1
    override fun getLastError(): String = errorMessage
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        bitmap.eraseColor(Color.rgb(0x14, 0x15, 0x1C))
    }
    override fun close() {}
}

class IsolatedRenderService : Service() {

    private val sessionCounter = AtomicInteger(1)
    private val activeSessions = ConcurrentHashMap<Int, DocumentSession>()
    private val sessionRenderLocks = ConcurrentHashMap<Int, Any>()
    private val stateCallbacks = ConcurrentHashMap<Int, ISessionStateCallback>()
    private val cachedFactories = ConcurrentHashMap<String, DynamicEngineFactory>()
    private val cachedSplitLoaders = ConcurrentHashMap<String, ClassLoader>()

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        if (newBase != null) {
            try { SplitCompat.install(newBase) } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { SplitCompat.install(this) } catch (_: Throwable) {}
        try { applicationContext?.let { SplitCompat.install(it) } } catch (_: Throwable) {}
        try {
            cacheDir?.absolutePath?.let { System.setProperty("omnireader.cache.dir", it) }
        } catch (_: Throwable) {}
        SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
    }

    private fun moduleNameForFactoryClass(className: String): String? = when {
        className.contains(".office.") -> "office"
        className.contains(".cadvector.") -> "cadvector"
        className.contains(".archives.") -> "archives"
        else -> null
    }

    private fun loadDynamicFactory(className: String): DynamicEngineFactory? {
        cachedFactories[className]?.let { return it }
        val moduleName = moduleNameForFactoryClass(className)

        val candidateLoaders = listOfNotNull(
            moduleName?.let { cachedSplitLoaders[it] },
            classLoader,
            applicationContext?.classLoader,
            IsolatedRenderService::class.java.classLoader,
            Thread.currentThread().contextClassLoader
        )

        for (loader in candidateLoaders) {
            try {
                val clazz = Class.forName(className, true, loader)
                val instance = clazz.getDeclaredConstructor().newInstance() as? DynamicEngineFactory
                if (instance != null) {
                    cachedFactories[className] = instance
                    return instance
                }
            } catch (_: Throwable) {}
        }

        if (moduleName != null) {
            for (ctx in listOfNotNull(this, applicationContext)) {
                try {
                    val splitCtx = ctx.createContextForSplit(moduleName)
                    val splitLoader = splitCtx.classLoader
                    if (splitLoader != null) {
                        val clazz = Class.forName(className, true, splitLoader)
                        val instance = clazz.getDeclaredConstructor().newInstance() as? DynamicEngineFactory
                        if (instance != null) {
                            cachedSplitLoaders[moduleName] = splitLoader
                            cachedFactories[className] = instance
                            return instance
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        try {
            val appInfo = applicationContext?.applicationInfo ?: applicationInfo
            val splitDirs = appInfo?.splitSourceDirs
            if (!splitDirs.isNullOrEmpty()) {
                for (splitPath in splitDirs) {
                    val fileName = File(splitPath).name.lowercase(Locale.ROOT)
                    if (moduleName == null || fileName.contains(moduleName) || splitDirs.size <= 6) {
                        try {
                            val loader = cachedSplitLoaders.getOrPut(splitPath) {
                                PathClassLoader(
                                    splitPath,
                                    appInfo.nativeLibraryDir,
                                    applicationContext?.classLoader ?: classLoader
                                )
                            }
                            val clazz = Class.forName(className, true, loader)
                            val instance = clazz.getDeclaredConstructor().newInstance() as? DynamicEngineFactory
                            if (instance != null) {
                                if (moduleName != null) cachedSplitLoaders[moduleName] = loader
                                cachedFactories[className] = instance
                                return instance
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            try { SplitCompat.install(this) } catch (_: Throwable) {}
            try { applicationContext?.let { SplitCompat.install(it) } } catch (_: Throwable) {}
            val loader = applicationContext?.classLoader ?: classLoader
            val clazz = Class.forName(className, true, loader)
            val instance = clazz.getDeclaredConstructor().newInstance() as? DynamicEngineFactory
            if (instance != null) {
                cachedFactories[className] = instance
                return instance
            }
        } catch (_: Throwable) {}

        return try {
            val pkgCtx = createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            try { SplitCompat.install(pkgCtx) } catch (_: Throwable) {}
            val modCtx = if (moduleName != null) {
                try { pkgCtx.createContextForSplit(moduleName) } catch (_: Throwable) { pkgCtx }
            } else pkgCtx
            val clazz = Class.forName(className, true, modCtx.classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance() as? DynamicEngineFactory
            if (instance != null) {
                cachedFactories[className] = instance
            }
            instance
        } catch (t: Throwable) {
            Log.w(TAG, "Dynamic factory $className not in split classloader, using built-in clean-room engine", t)
            null
        }
    }

    private fun resolveDescriptorSize(fd: FileDescriptor): Long {
        val statSize = try { Os.fstat(fd).st_size } catch (_: Throwable) { -1L }
        if (statSize > 0L) return statSize
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
            Os.lseek(fd, cur, OsConstants.SEEK_SET)
            if (end > 0L) end else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun normalizeFormatWithExtension(rawFormat: DetectedFormat, cleanExt: String): DetectedFormat {
        if (rawFormat == DetectedFormat.ZIP || rawFormat == DetectedFormat.OFFICE_XML || rawFormat == DetectedFormat.UNKNOWN) {
            when (cleanExt) {
                "docx", "docm", "dotx", "dotm" -> return DetectedFormat.DOCX
                "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> return DetectedFormat.XLSX
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> return DetectedFormat.PPTX
                "odt", "ott" -> return DetectedFormat.ODT
                "ods", "ots" -> return DetectedFormat.ODS
                "odp", "otp" -> return DetectedFormat.ODP
                "epub" -> return DetectedFormat.EPUB
                "fb2", "fbz" -> return DetectedFormat.FB2
                "usdz" -> return DetectedFormat.USDZ
                "3mf" -> return DetectedFormat.STL
            }
        }
        if (rawFormat == DetectedFormat.UNKNOWN || rawFormat == DetectedFormat.PLAINTEXT) {
            when (cleanExt) {
                "doc", "dot" -> return DetectedFormat.DOC
                "xls", "xlt" -> return DetectedFormat.XLS
                "ppt", "pps", "pot" -> return DetectedFormat.PPT
                "rtf" -> return DetectedFormat.RTF
                "one" -> return DetectedFormat.ONE
                "azw3", "azw" -> return DetectedFormat.AZW3
                "mobi", "prc" -> return DetectedFormat.MOBI
                "svg" -> return DetectedFormat.SVG
                "svgz" -> return DetectedFormat.SVGZ
                "stl", "ply", "off", "3ds", "fbx", "3mf", "dae", "step", "stp", "iges", "igs" -> return DetectedFormat.STL
                "obj" -> return DetectedFormat.OBJ
                "dxf" -> return DetectedFormat.DXF
                "dwg" -> return DetectedFormat.DWG
                "gltf" -> return DetectedFormat.GLTF
                "glb" -> return DetectedFormat.GLB
                "blend" -> return DetectedFormat.BLEND
                "usdz", "usd", "usda", "usdc" -> return DetectedFormat.USDZ
            }
        }
        return rawFormat
    }

    private fun routeSession(fd: FileDescriptor, ext: String): DocumentSession {
        SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
        val cleanExt = ext.lowercase(Locale.ROOT).trim()

        if (cleanExt in setOf("hex", "bin", "elf", "dex", "so")) {
            return UniversalHexInspectorImpl().createSession(fd)
        }

        val headerBuffer = ByteBuffer.allocateDirect(4096)
        val fileSize = resolveDescriptorSize(fd)

        try {
            try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
            Os.read(fd, headerBuffer)
        } catch (_: Exception) {}
        headerBuffer.flip()

        val rawDetectedFormat = FileSignatureDetector.resolveFormat(fd, headerBuffer, cleanExt, fileSize)
        val format = normalizeFormatWithExtension(rawDetectedFormat, cleanExt)

        val legalVerdict = LegalLicensePolicy.evaluate(
            format = format,
            extension = cleanExt,
            isDrmEncrypted = (format == DetectedFormat.KINDLE_DRM || format == DetectedFormat.KFX)
        )
        if (!legalVerdict.isLegallyPermitted) {
            return ErrorReportingDocumentSession(legalVerdict.explanation)
        }

        if (!MaliciousHeaderSanitizer.isSafeToParse(headerBuffer, format)) {
            return UniversalHexInspectorImpl().createSession(fd)
        }

        if (format in setOf(
                DetectedFormat.SQLITE,
                DetectedFormat.ACCDB,
                DetectedFormat.MDB,
                DetectedFormat.DBF,
                DetectedFormat.PARQUET
            ) || cleanExt in setOf(
                "db", "db3", "s3db", "sl3", "sqlite", "sqlite3",
                "accdb", "mdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson"
            )
        ) {
            return try {
                DatabaseInspectorEngineImpl().createSessionWithCacheDir(fd, cacheDir, cleanExt)
            } catch (e: Throwable) {
                ErrorReportingDocumentSession(
                    e.message ?: "Unable to read database schema or table records."
                )
            }
        }

        val cadExtensions = setOf(
            "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off",
            "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
            "3mf", "dae", "step", "stp", "iges", "igs"
        )
        if (format in setOf(
                DetectedFormat.SVG, DetectedFormat.SVGZ,
                DetectedFormat.STL, DetectedFormat.OBJ,
                DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB,
                DetectedFormat.BLEND, DetectedFormat.USDZ
            ) || cleanExt in cadExtensions
        ) {
            val resolvedCadExt = when (format) {
                DetectedFormat.SVG -> "svg"
                DetectedFormat.SVGZ -> "svgz"
                DetectedFormat.GLTF -> "gltf"
                DetectedFormat.GLB -> "glb"
                DetectedFormat.BLEND -> "blend"
                DetectedFormat.USDZ -> if (cleanExt in setOf("usd", "usda", "usdc")) cleanExt else "usdz"
                DetectedFormat.STL -> if (cleanExt in cadExtensions) cleanExt else "stl"
                DetectedFormat.OBJ -> "obj"
                DetectedFormat.DXF -> "dxf"
                DetectedFormat.DWG -> "dwg"
                else -> cleanExt
            }
            loadDynamicFactory("me.hlebkukleuski.fullfilestudio.feature.cadvector.CadVectorEngineFactory")
                ?.let { return it.createSession(fd, resolvedCadExt) }
            return CleanRoomLegacyAndIsoEngine.createSession(fd, format, resolvedCadExt)
        }

        val officeExtensions = setOf(
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot",
            "docx", "docm", "dotx", "dotm", "doc", "dot",
            "ods", "ots", "odt", "ott", "odp", "otp", "rtf", "one", "csv", "tsv",
            "epub", "fb2", "fbz", "azw3", "azw", "mobi", "prc"
        )
        if (format in setOf(
                DetectedFormat.XLSX, DetectedFormat.XLS,
                DetectedFormat.PPTX, DetectedFormat.PPT,
                DetectedFormat.DOCX, DetectedFormat.DOC,
                DetectedFormat.ODS, DetectedFormat.ODT, DetectedFormat.ODP,
                DetectedFormat.RTF, DetectedFormat.ONE, DetectedFormat.OFFICE_XML,
                DetectedFormat.EPUB, DetectedFormat.FB2,
                DetectedFormat.AZW3, DetectedFormat.MOBI
            ) || cleanExt in officeExtensions
        ) {
            val resolvedOfficeExt = when (format) {
                DetectedFormat.XLSX -> if (cleanExt == "xlsb") "xlsb" else "xlsx"
                DetectedFormat.XLS -> "xls"
                DetectedFormat.PPTX -> "pptx"
                DetectedFormat.PPT -> "ppt"
                DetectedFormat.DOCX -> "docx"
                DetectedFormat.DOC -> "doc"
                DetectedFormat.ODS -> "ods"
                DetectedFormat.ODT -> "odt"
                DetectedFormat.ODP -> "odp"
                DetectedFormat.RTF -> "rtf"
                DetectedFormat.ONE -> "one"
                DetectedFormat.EPUB -> "epub"
                DetectedFormat.FB2 -> "fb2"
                DetectedFormat.AZW3 -> "azw3"
                DetectedFormat.MOBI -> "mobi"
                else -> cleanExt
            }
            loadDynamicFactory("me.hlebkukleuski.fullfilestudio.feature.office.OfficeEngineFactory")
                ?.let { return it.createSession(fd, resolvedOfficeExt) }

            if (format in setOf(
                    DetectedFormat.DOC, DetectedFormat.XLS, DetectedFormat.PPT,
                    DetectedFormat.RTF, DetectedFormat.ONE,
                    DetectedFormat.XLSX, DetectedFormat.ODS,
                    DetectedFormat.PPTX, DetectedFormat.ODP
                ) || resolvedOfficeExt in setOf(
                    "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "rtf", "one",
                    "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv",
                    "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp"
                )
            ) {
                return CleanRoomLegacyAndIsoEngine.createSession(fd, format, resolvedOfficeExt)
            }
            return DocumentRendererEngineImpl().createSessionForFormat(fd, format, resolvedOfficeExt)
        }

        val isOfficeOrEbookOr3dZipExtension = cleanExt in setOf(
            "docx", "docm", "dotx", "dotm",
            "xlsx", "xlsm", "xlsb", "xltx", "xltm",
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm",
            "odt", "ott", "ods", "ots", "odp", "otp", "epub", "fb2", "fbz",
            "usdz", "3mf"
        )
        if ((format in setOf(
                DetectedFormat.ZIP, DetectedFormat.SEVEN_ZIP, DetectedFormat.RAR_V4, DetectedFormat.RAR_V5,
                DetectedFormat.TAR, DetectedFormat.GZ, DetectedFormat.BZ2,
                DetectedFormat.XZ, DetectedFormat.APK, DetectedFormat.ISO
            ) && !(format == DetectedFormat.ZIP && isOfficeOrEbookOr3dZipExtension)) ||
            cleanExt in setOf("7z", "rar", "tar", "zip", "gz", "bz2", "xz", "tgz", "apk", "jar", "iso")
        ) {
            val resolvedArcExt = when (format) {
                DetectedFormat.SEVEN_ZIP -> "7z"
                DetectedFormat.RAR_V4, DetectedFormat.RAR_V5 -> "rar"
                DetectedFormat.TAR -> if (cleanExt == "tgz") "tgz" else "tar"
                DetectedFormat.GZ -> if (cleanExt == "tgz") "tgz" else "gz"
                DetectedFormat.BZ2 -> "bz2"
                DetectedFormat.XZ -> "xz"
                DetectedFormat.ISO -> "iso"
                DetectedFormat.APK -> if (cleanExt == "jar") "jar" else "apk"
                DetectedFormat.ZIP -> if (cleanExt in setOf("apk", "jar")) cleanExt else "zip"
                else -> cleanExt
            }
            loadDynamicFactory("me.hlebkukleuski.fullfilestudio.feature.archives.ArchiveEngineFactory")
                ?.let { return it.createSession(fd, resolvedArcExt) }
            return CleanRoomLegacyAndIsoEngine.createSession(fd, format, resolvedArcExt)
        }

        return DocumentRendererEngineImpl().createSessionForFormat(fd, format, cleanExt)
    }

    private fun attachSessionListener(sessionId: Int, session: DocumentSession) {
        if (session is SqliteDatabaseSession) {
            val notifyCallback = Runnable {
                val cb = stateCallbacks[sessionId] ?: return@Runnable
                val ver = session.getStateVersion()
                try {
                    cb.onSessionStateChanged(sessionId, ver)
                } catch (_: Throwable) {
                    stateCallbacks.remove(sessionId)
                }
            }
            session.onMetaLoadedListener = notifyCallback
            notifyCallback.run()
        }
    }

    private val binder = object : IIsolatedEngineService.Stub() {
        override fun openSession(pfd: ParcelFileDescriptor?, extension: String?): Int {
            if (pfd == null) return -1
            return try {
                val fd = pfd.fileDescriptor ?: return -1
                val sessionId = sessionCounter.getAndIncrement()
                val session = try {
                    routeSession(fd, extension ?: "")
                } catch (e: Throwable) {
                    Log.e(TAG, "routeSession fallback error", e)
                    ErrorReportingDocumentSession(e.localizedMessage ?: "File structure parse error.")
                }
                activeSessions[sessionId] = session
                sessionRenderLocks[sessionId] = Any()
                attachSessionListener(sessionId, session)
                sessionId
            } catch (e: Throwable) {
                Log.e(TAG, "openSession error", e)
                -1
            } finally {
                try { pfd.close() } catch (_: Throwable) {}
            }
        }

        override fun registerSessionStateCallback(sessionId: Int, callback: ISessionStateCallback?) {
            val session = activeSessions[sessionId]
            if (callback != null && session != null) {
                stateCallbacks[sessionId] = callback
                attachSessionListener(sessionId, session)
                try {
                    callback.onSessionStateChanged(sessionId, session.getStateVersion())
                } catch (_: Throwable) {
                    stateCallbacks.remove(sessionId)
                }
            } else {
                stateCallbacks.remove(sessionId)
            }
        }

        override fun getStateVersion(sessionId: Int): Long {
            val session = activeSessions[sessionId] ?: return -1L
            return session.getStateVersion().coerceAtLeast(1L)
        }

        override fun getLastError(sessionId: Int): String? {
            return activeSessions[sessionId]?.getLastError()
        }

        override fun getPageCount(sessionId: Int): Int = activeSessions[sessionId]?.pageCount ?: 1
        override fun is3dSession(sessionId: Int): Boolean = activeSessions[sessionId]?.is3dContent ?: false

        override fun renderTile(
            sessionId: Int,
            pageIndex: Int,
            tileX: Int,
            tileY: Int,
            zoomPermille: Int,
            width: Int,
            height: Int,
            sharedMemory: SharedMemory?
        ): Boolean {
            if (sharedMemory == null) return false
            val session = activeSessions[sessionId]
            if (session == null || width !in 1..4096 || height !in 1..4096) {
                try { sharedMemory.close() } catch (_: Throwable) {}
                return false
            }
            val requiredBytes = width.toLong() * height.toLong() * 4L
            if (sharedMemory.size.toLong() < requiredBytes) {
                try { sharedMemory.close() } catch (_: Throwable) {}
                return false
            }

            val renderLock = sessionRenderLocks.getOrPut(sessionId) { Any() }
            synchronized(renderLock) {
                if (!activeSessions.containsKey(sessionId)) {
                    try { sharedMemory.close() } catch (_: Throwable) {}
                    return false
                }
                var mappedBuffer: ByteBuffer? = null
                val bitmap = AdaptiveRingBitmapPool.obtain(width, height, Bitmap.Config.ARGB_8888)
                return try {
                    mappedBuffer = sharedMemory.mapReadWrite()
                    session.renderTile(pageIndex, tileX, tileY, zoomPermille, bitmap)
                    mappedBuffer.position(0)
                    bitmap.copyPixelsToBuffer(mappedBuffer)
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "renderTile error", e)
                    false
                } finally {
                    AdaptiveRingBitmapPool.recycle(bitmap)
                    if (mappedBuffer != null) {
                        try { SharedMemory.unmap(mappedBuffer) } catch (_: Throwable) {}
                    }
                    try { sharedMemory.close() } catch (_: Throwable) {}
                }
            }
        }

        override fun search(sessionId: Int, query: String?, startPage: Int): Long {
            return activeSessions[sessionId]?.search(query ?: "", startPage) ?: -1L
        }

        override fun getPageText(sessionId: Int, pageIndex: Int): String {
            val raw = activeSessions[sessionId]?.getPageText(pageIndex) ?: ""
            return if (raw.length > MAX_BINDER_TEXT_CHARS) {
                raw.substring(0, MAX_BINDER_TEXT_CHARS)
            } else {
                raw
            }
        }

        override fun getDocumentWidth(sessionId: Int): Float {
            return activeSessions[sessionId]?.getDocumentWidth() ?: 0f
        }

        override fun getDocumentHeight(sessionId: Int): Float {
            return activeSessions[sessionId]?.getDocumentHeight() ?: 0f
        }

        override fun getSheetNames(sessionId: Int): Array<String> {
            return activeSessions[sessionId]?.getSheetNames()?.toTypedArray() ?: emptyArray()
        }

        override fun getActiveSheetIndex(sessionId: Int): Int {
            return activeSessions[sessionId]?.getActiveSheetIndex() ?: 0
        }

        override fun setActiveSheetIndex(sessionId: Int, index: Int) {
            activeSessions[sessionId]?.setActiveSheetIndex(index)
        }

        override fun getColumnWidths(sessionId: Int): FloatArray {
            val widths = activeSessions[sessionId]?.getColumnWidths() ?: return FloatArray(0)
            return if (widths.size > MAX_BINDER_COLUMNS) widths.copyOf(MAX_BINDER_COLUMNS) else widths
        }

        override fun getCellText(sessionId: Int, row: Int, col: Int): String {
            return activeSessions[sessionId]?.getCellText(row, col) ?: ""
        }

        override fun getSelectedRangeText(sessionId: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
            val raw = activeSessions[sessionId]?.getSelectedRangeText(startRow, startCol, endRow, endCol) ?: ""
            return if (raw.length > MAX_BINDER_TEXT_CHARS) {
                raw.substring(0, MAX_BINDER_TEXT_CHARS)
            } else {
                raw
            }
        }

        override fun closeSession(sessionId: Int) {
            stateCallbacks.remove(sessionId)
            sessionRenderLocks.remove(sessionId)
            try {
                activeSessions.remove(sessionId)?.close()
            } catch (_: Throwable) {}
            if (activeSessions.isEmpty()) {
                AdaptiveRingBitmapPool.trimToZero()
            }
        }

        override fun isArchiveSession(sessionId: Int): Boolean {
            val session = activeSessions[sessionId]
            return session is ArchiveSession || session?.javaClass?.name?.contains("ArchiveSession") == true
        }

        override fun getArchiveEntries(sessionId: Int): Array<String> {
            val session = activeSessions[sessionId]
            if (session is ArchiveSession) {
                return session.sanitizedFileEntries.toTypedArray()
            }
            if (session?.javaClass?.name?.contains("ArchiveSession") == true) {
                try {
                    val method = session.javaClass.getMethod("getSanitizedFileEntries")
                    val list = method.invoke(session) as? List<*>
                    return list?.map { it.toString() }?.toTypedArray() ?: emptyArray()
                } catch (_: Exception) {}
            }
            return emptyArray()
        }

        override fun openVirtualFile(sessionId: Int, entryPath: String?): ParcelFileDescriptor? {
            if (entryPath == null) return null
            val session = activeSessions[sessionId]
            if (session is ArchiveSession) {
                val pfd = session.openVirtualFile(entryPath)
                if (pfd != null) return pfd
            }
            if (session?.javaClass?.name?.contains("ArchiveSession") == true) {
                try {
                    val method = session.javaClass.getMethod("openVirtualFile", String::class.java)
                    val pfd = method.invoke(session, entryPath) as? ParcelFileDescriptor
                    if (pfd != null) return pfd
                } catch (_: Exception) {}
            }
            return null
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeAllActiveSessions()
        return super.onUnbind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        closeAllActiveSessions()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            AdaptiveRingBitmapPool.trimToZero()
            if (SecurityArena.isNativeLoaded) {
                try { SecurityArena.zeroBufferNeonNative() } catch (_: Throwable) {}
            }
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                SecurityArena.safeReleaseArena()
            }
        }
    }

    private fun closeAllActiveSessions() {
        stateCallbacks.clear()
        sessionRenderLocks.clear()
        val sessions = activeSessions.values.toList()
        activeSessions.clear()
        sessions.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        AdaptiveRingBitmapPool.trimToZero()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeAllActiveSessions()
        SecurityArena.safeReleaseArena()
    }

    companion object {
        private const val TAG = "IsolatedRenderService"
        private const val MAX_BINDER_TEXT_CHARS = 360_000
        private const val MAX_BINDER_COLUMNS = 4096
        private const val DEFAULT_ARENA_SIZE_BYTES = 16L * 1024L * 1024L
    }
}
