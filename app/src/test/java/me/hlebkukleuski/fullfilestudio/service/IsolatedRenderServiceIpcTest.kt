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

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Integration test suite exercising [IsolatedRenderService] through its AIDL [IIsolatedEngineService]
 * Binder interface, 16 KB-aligned [SharedMemory] tile transport, archive virtual-FD extraction,
 * DRM/executable header gates, and 16 KB native ELF `PT_LOAD` alignment configuration.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IsolatedRenderServiceIpcTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bindIsolatedService(): IIsolatedEngineService {
        val service = Robolectric.setupService(IsolatedRenderService::class.java)
        val binder = service.onBind(Intent(service, IsolatedRenderService::class.java))
        assertNotNull("IsolatedRenderService must return a non-null IBinder", binder)
        return IIsolatedEngineService.Stub.asInterface(binder)
    }

    @Test(timeout = 20_000L)
    fun isolatedServiceOpensRendersSearchesAndClosesPlainTextAndCsvOverBinderIpc() {
        val svc = bindIsolatedService()

        val txtFile = tempFolder.newFile("architecture_notes.txt")
        txtFile.writeBytes("Zero-Copy Binder Ashmem Pipeline\nSecond line with token ALPHA42.".toByteArray(StandardCharsets.UTF_8))

        val txtSessionId = ParcelFileDescriptor.open(txtFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            svc.openSession(pfd, "txt")
        }
        assertTrue("Expected valid session ID for text file", txtSessionId > 0)

        val memSize = 256 * 256 * 4
        val alignedSize = (memSize + 16383) and -16384
        val sharedMem = SharedMemory.create("ipc_tile_test", alignedSize)
        val mapped = sharedMem.mapReadOnly()
        try {
            val rendered = svc.renderTile(txtSessionId, 0, 0, 0, 1000, 256, 256, sharedMem)
            assertTrue("Expected renderTile over SharedMemory to succeed", rendered)

            val intBuf = mapped.order(ByteOrder.nativeOrder()).asIntBuffer()
            val distinctColors = HashSet<Int>()
            for (i in 0 until (256 * 256)) {
                distinctColors.add(intBuf.get(i))
                if (distinctColors.size >= 3) break
            }
            assertTrue("Rendered tile in SharedMemory must contain visible glyph contrast", distinctColors.size >= 2)

            val fullText = svc.getPageText(txtSessionId, -1)
            assertTrue(fullText.contains("ALPHA42"))
            assertEquals(0L, svc.search(txtSessionId, "ALPHA42", 0))
        } finally {
            try { SharedMemory.unmap(mapped) } catch (_: Throwable) {}
            try { sharedMem.close() } catch (_: Throwable) {}
            svc.closeSession(txtSessionId)
        }
        assertEquals(-1L, svc.getStateVersion(txtSessionId))

        val csvFile = tempFolder.newFile("metrics.csv")
        csvFile.writeBytes("module,loc,warnings\ncore,12000,0\napp,8500,0\n".toByteArray(StandardCharsets.UTF_8))
        val csvSessionId = ParcelFileDescriptor.open(csvFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            svc.openSession(pfd, "csv")
        }
        assertTrue(csvSessionId > 0)
        try {
            assertEquals("module", svc.getCellText(csvSessionId, 0, 0))
            assertEquals("core", svc.getCellText(csvSessionId, 1, 0))
            assertEquals("0", svc.getCellText(csvSessionId, 1, 2))
        } finally {
            svc.closeSession(csvSessionId)
        }
    }

    @Test(timeout = 20_000L)
    fun isolatedServiceExtractsVirtualArchiveFileAndRejectsZipSlipEntries() {
        val svc = bindIsolatedService()
        val zipFile = tempFolder.newFile("mixed_archive.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("docs/hello.txt"))
            zos.write("Safe nested text".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("../../etc/passwd"))
            zos.write("root:x:0:0".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        val archiveId = ParcelFileDescriptor.open(zipFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            svc.openSession(pfd, "zip")
        }
        assertTrue(archiveId > 0)
        try {
            assertTrue(svc.isArchiveSession(archiveId))
            val entries = svc.getArchiveEntries(archiveId).toList()
            assertTrue("Safe entry must be listed", entries.any { it.startsWith("docs/hello.txt") })
            assertFalse("Zip-Slip traversal entry must never be listed", entries.any { it.contains("..") })

            assertNull(
                "Opening a traversal entry path directly must return null",
                svc.openVirtualFile(archiveId, "../../etc/passwd")
            )

            val virtualPfd = svc.openVirtualFile(archiveId, "docs/hello.txt")
            assertNotNull("Valid archive entry must return a virtual ParcelFileDescriptor", virtualPfd)
            virtualPfd!!.use { pfd ->
                val text = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(pfd.fileDescriptor))
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
                assertEquals("Safe nested text", text)
            }
        } finally {
            svc.closeSession(archiveId)
        }
    }

    @Test(timeout = 15_000L)
    fun isolatedServiceEnforcesLegalDrmPolicyAndRoutesExecutablesToReadOnlyHex() {
        val svc = bindIsolatedService()

        val kfxFile = tempFolder.newFile("locked_book.kfx")
        kfxFile.writeBytes(byteArrayOf(0xEA.toByte(), 0x44, 0x52, 0x4D) + ByteArray(64))
        val drmId = ParcelFileDescriptor.open(kfxFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            svc.openSession(pfd, "kfx")
        }
        try {
            val err = svc.getLastError(drmId)
            assertNotNull(err)
            assertTrue("Expected DRM/closed-license explanation, got: $err", err!!.isNotBlank())
        } finally {
            svc.closeSession(drmId)
        }

        val elfFile = tempFolder.newFile("sample_binary.elf")
        elfFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00) + ByteArray(56))
        val elfId = ParcelFileDescriptor.open(elfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            svc.openSession(pfd, "elf")
        }
        try {
            val hexDump = svc.getPageText(elfId, 0)
            assertTrue("ELF binary in isolated service must route to read-only HEX inspector", hexDump.startsWith("HEX INSPECTOR |"))
        } finally {
            svc.closeSession(elfId)
        }
    }

    @Test
    fun nativeCmakeAndBuiltSharedLibrariesEnforce16KbElfPageAlignment() {
        val cmakeCandidates = listOf(
            File("../core/security/src/main/cpp/CMakeLists.txt"),
            File("core/security/src/main/cpp/CMakeLists.txt")
        )
        val cmakeFile = cmakeCandidates.firstOrNull { it.exists() }
        assertNotNull("CMakeLists.txt must exist in :core:security", cmakeFile)
        val cmakeText = cmakeFile!!.readText()
        assertTrue(cmakeText.contains("-Wl,-z,max-page-size=16384"))
        assertTrue(cmakeText.contains("-Wl,-z,relro,-z,now"))
        assertTrue(cmakeText.contains("-Wl,-z,noexecstack"))
        assertTrue(cmakeText.contains("-fsanitize=cfi"))

        val nativeBuildDirs = listOf(
            File("../core/security/build/intermediates/cxx"),
            File("core/security/build/intermediates/cxx"),
            File("../core/security/build/intermediates/cmake"),
            File("core/security/build/intermediates/cmake")
        ).filter { it.isDirectory }

        val sharedLibs64 = nativeBuildDirs.flatMap { dir ->
            dir.walkTopDown().filter {
                it.isFile && it.name.endsWith(".so") &&
                    (it.path.contains("arm64-v8a") || it.path.contains("x86_64"))
            }.toList()
        }

        for (soFile in sharedLibs64) {
            val bytes = soFile.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x7F, bytes[0].toInt() and 0xFF)
            assertEquals('E'.code, bytes[1].toInt() and 0xFF)
            assertEquals('L'.code, bytes[2].toInt() and 0xFF)
            assertEquals('F'.code, bytes[3].toInt() and 0xFF)
            if (bytes[4].toInt() == 2) {
                val ePhoff = bb.getLong(32).toInt()
                val ePhentsize = bb.getShort(54).toInt() and 0xFFFF
                val ePhnum = bb.getShort(56).toInt() and 0xFFFF
                var checkedLoads = 0
                for (i in 0 until ePhnum) {
                    val off = ePhoff + i * ePhentsize
                    val pType = bb.getInt(off)
                    if (pType == 1) {
                        val pAlign = bb.getLong(off + 48)
                        assertTrue(
                            "${soFile.path} PT_LOAD[$i] p_align=0x${pAlign.toString(16)} must be >= 0x4000 (16 KB)",
                            pAlign >= 0x4000L
                        )
                        checkedLoads++
                    }
                }
                assertTrue(checkedLoads > 0)
            }
        }
    }
}
