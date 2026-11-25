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

import android.graphics.Bitmap
import android.net.Uri
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.util.LruCache
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshScene
import me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneCodec
import me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneRenderer
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService
import me.hlebkukleuski.fullfilestudio.core.engine.ISessionStateCallback
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey

interface ProxySessionHost {
    fun peekIsolatedService(): IIsolatedEngineService?
    suspend fun getIsolatedService(): IIsolatedEngineService
    fun launchInScope(block: suspend CoroutineScope.() -> Unit)
    fun launchImmediateMain(block: suspend CoroutineScope.() -> Unit)
    fun dupVirtualHexBackupPfd(): ParcelFileDescriptor?
    fun openPfd(uri: Uri): ParcelFileDescriptor?
    fun updateActiveSessionId(newId: Int)
    fun serviceReconnectingMessage(): String
}

/**
 * Proxy session communicating with `IsolatedRenderService` over AIDL and 16 KB-aligned [SharedMemory].
 * Caches document dimensions, page counts, and extracted page texts per state/page to avoid
 * redundant Binder IPC round-trips during 120 FPS zoom, pan, and slider scrubbing gestures.
 */
class ProxyDocumentSession(
    private val controller: ProxySessionHost,
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
        get() = extension.lowercase(Locale.ROOT) in setOf(
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp"
        )

    override fun getLastError(): String? {
        if (isClosed) return null
        return try {
            if (isServiceDisconnectedFlag) {
                val svc = controller.peekIsolatedService()
                if (svc == null || !svc.asBinder().isBinderAlive) {
                    return controller.serviceReconnectingMessage()
                }
                isServiceDisconnectedFlag = false
            }
            if (lastMetadataStateVersion < 0L) {
                refreshCachedMetadataFromService(controller.peekIsolatedService())
            }
            cachedLastError
        } catch (_: DeadObjectException) {
            controller.serviceReconnectingMessage()
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

    override fun provideResource(name: String, fd: FileDescriptor): Boolean {
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

    @Volatile private var hostMeshScene: MeshScene? = null
    private var hostMeshRenderer: MeshSceneRenderer? = null
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

    private fun loadHostMeshSceneLocked(): MeshScene? {
        hostMeshScene?.let { return it }
        val svc = controller.peekIsolatedService() ?: return null
        val memory = try { svc.exportMeshScene(sessionId) } catch (_: Exception) { null } ?: return null
        var mapped: ByteBuffer? = null
        return try {
            mapped = memory.mapReadOnly()
            MeshSceneCodec.decode(mapped).also { hostMeshScene = it }
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
                    val renderer = hostMeshRenderer ?: MeshSceneRenderer().also { hostMeshRenderer = it }
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
