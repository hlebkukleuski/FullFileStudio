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

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.hlebkukleuski.fullfilestudio.FullFileStudioApp
import me.hlebkukleuski.fullfilestudio.bootstrap.FirstLaunchEngineBootstrapper
import me.hlebkukleuski.fullfilestudio.core.database.UniversalHexInspectorImpl
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService
import me.hlebkukleuski.fullfilestudio.core.engine.ISessionStateCallback
import me.hlebkukleuski.fullfilestudio.data.settings.AppSettingsManager
import me.hlebkukleuski.fullfilestudio.di.AppContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies pure UDF [ReaderViewModel] behavior, [SavedStateHandle] process-death state restoration,
 * [AppContainer] DI isolation, and [DocumentIntentResolver] sandbox URI security boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderViewModelAndIntentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeIsolatedEngineStub : IIsolatedEngineService.Stub() {
        private val nextId = AtomicInteger(10)
        val activeSheets = HashMap<Int, Int>()
        val archiveSessions = HashSet<Int>()

        override fun openSession(pfd: ParcelFileDescriptor?, extension: String?): Int {
            pfd?.close()
            val id = nextId.getAndIncrement()
            activeSheets[id] = 0
            if (extension == "zip") {
                archiveSessions.add(id)
            }
            return id
        }

        override fun openSessionWithPassword(pfd: ParcelFileDescriptor?, extension: String?, password: String?): Int {
            return openSession(pfd, extension)
        }

        override fun renderTile(
            sessionId: Int,
            pageIndex: Int,
            tileX: Int,
            tileY: Int,
            zoomPermille: Int,
            width: Int,
            height: Int,
            sharedMemory: SharedMemory?
        ): Boolean = true

        override fun search(sessionId: Int, query: String?, startPage: Int): Long = 0L

        override fun closeSession(sessionId: Int) {
            activeSheets.remove(sessionId)
            archiveSessions.remove(sessionId)
        }

        override fun getPageCount(sessionId: Int): Int = 12
        override fun is3dSession(sessionId: Int): Boolean = false
        override fun exportMeshScene(sessionId: Int): SharedMemory? = null
        override fun getPageText(sessionId: Int, pageIndex: Int): String = "Page ${pageIndex + 1} content"
        override fun getDocumentWidth(sessionId: Int): Float = 1200f
        override fun getDocumentHeight(sessionId: Int): Float = 1600f
        override fun registerSessionStateCallback(sessionId: Int, callback: ISessionStateCallback?) {}
        override fun getStateVersion(sessionId: Int): Long = if (activeSheets.containsKey(sessionId)) 1L else -1L
        override fun getLastError(sessionId: Int): String? = null
        override fun getMissingResources(sessionId: Int): Array<String> = emptyArray()
        override fun provideResource(sessionId: Int, name: String?, pfd: ParcelFileDescriptor?): Boolean {
            pfd?.close()
            return false
        }
        override fun applyProvidedResources(sessionId: Int): Boolean = false
        override fun isArchiveSession(sessionId: Int): Boolean = archiveSessions.contains(sessionId)
        override fun getArchiveEntries(sessionId: Int): Array<String> = arrayOf("notes/report.txt (24 B)")
        override fun openVirtualFile(sessionId: Int, entryPath: String?): ParcelFileDescriptor? {
            val f = File.createTempFile("virtual_entry_", ".txt")
            f.deleteOnExit()
            f.writeBytes("Nested report body".toByteArray(StandardCharsets.UTF_8))
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getSheetNames(sessionId: Int): Array<String> = arrayOf("Summary", "Q1", "Q2")
        override fun getActiveSheetIndex(sessionId: Int): Int = activeSheets[sessionId] ?: 0
        override fun setActiveSheetIndex(sessionId: Int, index: Int) {
            activeSheets[sessionId] = index
        }
        override fun getColumnWidths(sessionId: Int): FloatArray = floatArrayOf(120f, 180f)
        override fun getCellText(sessionId: Int, row: Int, col: Int): String = "R${row}C$col"
        override fun getSelectedRangeText(sessionId: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int): String = "R0C0"
        override fun getRowHeights(sessionId: Int): FloatArray = floatArrayOf(32f, 32f)
        override fun isCopyRestricted(sessionId: Int): Boolean = false
    }

    private class FakeAppContainer(
        context: Context,
        private val stub: FakeIsolatedEngineStub
    ) : AppContainer {
        override val settingsManager = AppSettingsManager(context)
        override val bootstrapper = FirstLaunchEngineBootstrapper(context, settingsManager)
        override val documentIntentResolver = DocumentIntentResolver(context)

        override fun createIsolatedRenderClient(timeoutMessageProvider: () -> String): IsolatedRenderClient {
            return object : IsolatedRenderClient {
                override fun setConnectionListener(listener: IsolatedRenderClient.ConnectionListener?) {}
                override fun peekService(): IIsolatedEngineService = stub
                override suspend fun getService(): IIsolatedEngineService = stub
                override fun ensureBound() {}
                override fun unbindSafely() {}
            }
        }

        override fun classifyHostOrMediaFormat(
            format: DetectedFormat,
            effectiveExt: String,
            headerSignature: ByteArray
        ): Pair<Boolean, Boolean> = false to false

        override fun createHostOrMediaSession(
            uri: Uri,
            sourcePfd: ParcelFileDescriptor,
            format: DetectedFormat,
            effectiveExt: String,
            isMedia: Boolean,
            isHostDecodedImage: Boolean,
            allowMediaReuse: Boolean
        ): DocumentSession {
            throw UnsupportedOperationException("All test formats route through FakeIsolatedEngineStub")
        }

        override fun createLocalHexSession(fd: FileDescriptor): DocumentSession {
            return UniversalHexInspectorImpl().createSession(fd)
        }
    }

    @Test
    fun readerViewModelIsPureViewModelWithoutAndroidViewModelCoupling() {
        assertTrue(ViewModel::class.java.isAssignableFrom(ReaderViewModel::class.java))
        assertFalse(
            "ReaderViewModel must extend pure ViewModel, not AndroidViewModel",
            AndroidViewModel::class.java.isAssignableFrom(ReaderViewModel::class.java)
        )
    }

    @Test
    fun udfActionsAndSavedStateHandleRestoreUriPageAndSheetAcrossProcessDeath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stub = FakeIsolatedEngineStub()
        val container = FakeAppContainer(context, stub)

        val csvFile = tempFolder.newFile("financials.csv")
        csvFile.writeBytes("quarter,revenue\nQ1,125000\nQ2,148000\n".toByteArray(StandardCharsets.UTF_8))
        val fileUri = Uri.fromFile(csvFile)

        val initialHandle = SavedStateHandle()
        val vm1 = ReaderViewModel(
            savedStateHandle = initialHandle,
            container = container,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )

        vm1.dispatch(ReaderUiAction.OpenUri(fileUri))
        val openedState = withTimeout(5_000L) {
            vm1.uiState.first { it.currentSession != null }
        }
        assertNotNull(openedState.currentSession)
        assertEquals(0, openedState.savedPageIndex)
        assertEquals(0, openedState.savedSheetIndex)

        vm1.dispatch(ReaderUiAction.PageChanged(7))
        vm1.dispatch(ReaderUiAction.SheetChanged(2))

        assertEquals(fileUri.toString(), initialHandle.get<String>(ReaderViewModel.KEY_LAST_OPENED_URI))
        assertEquals(7, initialHandle.get<Int>(ReaderViewModel.KEY_SAVED_PAGE_INDEX))
        assertEquals(2, initialHandle.get<Int>(ReaderViewModel.KEY_SAVED_SHEET_INDEX))
        vm1.releaseAllSessionsAndUnbind()

        val recreatedHandle = SavedStateHandle(
            mapOf(
                ReaderViewModel.KEY_LAST_OPENED_URI to initialHandle.get<String>(ReaderViewModel.KEY_LAST_OPENED_URI),
                ReaderViewModel.KEY_SAVED_PAGE_INDEX to initialHandle.get<Int>(ReaderViewModel.KEY_SAVED_PAGE_INDEX),
                ReaderViewModel.KEY_SAVED_SHEET_INDEX to initialHandle.get<Int>(ReaderViewModel.KEY_SAVED_SHEET_INDEX)
            )
        )
        val vm2 = ReaderViewModel(
            savedStateHandle = recreatedHandle,
            container = container,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )

        assertEquals(7, vm2.uiState.value.savedPageIndex)
        assertEquals(2, vm2.uiState.value.savedSheetIndex)

        vm2.restoreSavedPendingState()
        val restoredState = withTimeout(5_000L) {
            vm2.uiState.first { it.currentSession != null }
        }
        assertEquals(fileUri, restoredState.lastOpenedUri)
        assertEquals(7, restoredState.savedPageIndex)
        assertEquals(2, restoredState.savedSheetIndex)
        assertEquals(2, restoredState.currentSession!!.getActiveSheetIndex())
        vm2.releaseAllSessionsAndUnbind()
    }

    @Test
    fun navigateBackPopsArchiveHistoryAndClearsSavedUriAtRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stub = FakeIsolatedEngineStub()
        val container = FakeAppContainer(context, stub)

        val zipFile = tempFolder.newFile("bundle.zip")
        zipFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18))
        val zipUri = Uri.fromFile(zipFile)

        val handle = SavedStateHandle()
        val vm = ReaderViewModel(
            savedStateHandle = handle,
            container = container,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )

        vm.dispatch(ReaderUiAction.OpenUri(zipUri))
        withTimeout(5_000L) {
            vm.uiState.first { it.currentSession != null && it.isArchiveSession }
        }
        assertTrue(vm.uiState.value.isArchiveSession)
        assertEquals(listOf("notes/report.txt (24 B)"), vm.uiState.value.archiveEntries)

        vm.dispatch(ReaderUiAction.OpenArchiveEntry("notes/report.txt (24 B)"))
        withTimeout(5_000L) {
            vm.uiState.first { !it.isArchiveSession && it.hasSessionHistory }
        }
        assertTrue(vm.uiState.value.hasSessionHistory)

        vm.dispatch(ReaderUiAction.NavigateBack)
        assertTrue(vm.uiState.value.isArchiveSession)
        assertFalse(vm.uiState.value.hasSessionHistory)

        vm.dispatch(ReaderUiAction.NavigateBack)
        assertNull(vm.uiState.value.currentSession)
        assertNull(handle.get<String>(ReaderViewModel.KEY_LAST_OPENED_URI))
    }

    @Test
    fun documentIntentResolverBlocksInternalAppStorageSymlinksAndPrivateFileProvider() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = DocumentIntentResolver(context)

        val blockedUris = mutableListOf(
            Uri.parse("file:///data/data/${context.packageName}/shared_prefs/fullfilestudio_ui_prefs.xml"),
            Uri.parse("file:///data/user/0/${context.packageName}/databases/secret.db"),
            Uri.parse("file:///proc/self/cmdline"),
            Uri.parse("content://${context.packageName}.fileprovider/updates/fullfilestudio-update.apk")
        )

        val secretInternalFile = File(context.applicationInfo.dataDir, "internal_token.txt").apply {
            parentFile?.mkdirs()
            writeText("TOP_SECRET")
        }
        val externalSymlink = File(tempFolder.root, "innocent_readme.txt")
        val symlinkCreated = try {
            Files.createSymbolicLink(externalSymlink.toPath(), secretInternalFile.toPath())
            true
        } catch (_: Throwable) {
            false
        }
        if (symlinkCreated) {
            blockedUris.add(Uri.fromFile(externalSymlink))
        }

        for (uri in blockedUris) {
            var threwSecurity = false
            try {
                resolver.validateUriSecurity(uri)
            } catch (_: SecurityException) {
                threwSecurity = true
            }
            assertTrue("Expected SecurityException for blocked URI: $uri", threwSecurity)
        }

        val stub = FakeIsolatedEngineStub()
        val vm = ReaderViewModel(
            savedStateHandle = SavedStateHandle(),
            container = FakeAppContainer(context, stub),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )
        vm.dispatch(ReaderUiAction.OpenUri(blockedUris.first()))
        val err = withTimeout(5_000L) {
            vm.uiState.first { it.structuredError != null }.structuredError!!
        }
        assertEquals(UiErrorCategory.ACCESS_OR_SECURITY, err.category)
        assertFalse("Internal storage security errors must never allow HEX fallback", err.allowHexFallback)
    }

    @Test
    fun fullFileStudioAppInitializesDiContainerCleanly() {
        val app = ApplicationProvider.getApplicationContext<Context>() as FullFileStudioApp
        assertTrue(app.isContainerInitialized)
        assertNotNull(app.container.settingsManager)
        assertNotNull(app.container.bootstrapper)
        assertNotNull(app.container.documentIntentResolver)
    }
}
