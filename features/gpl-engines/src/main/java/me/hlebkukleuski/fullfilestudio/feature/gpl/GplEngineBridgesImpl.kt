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

package me.hlebkukleuski.fullfilestudio.feature.gpl

import android.os.ParcelFileDescriptor
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.impl.CleanRoomAccessDatabaseSession
import com.omnireader.core.engine.impl.CleanRoomDjvuSession
import com.omnireader.core.engine.impl.CleanRoomDwgReader
import com.omnireader.core.engine.impl.DatabaseSessionLocal
import com.omnireader.core.engine.impl.DwgGeometrySink
import com.omnireader.core.engine.impl.GplCoreEngineBridge
import com.omnireader.core.engine.impl.PalmDocMobiCleanRoomSession
import me.hlebkukleuski.fullfilestudio.feature.cadvector.BlendFile
import me.hlebkukleuski.fullfilestudio.feature.cadvector.CleanRoomBlend
import me.hlebkukleuski.fullfilestudio.feature.cadvector.CleanRoomFbx
import me.hlebkukleuski.fullfilestudio.feature.cadvector.FbxReader
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfOutput
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridge
import java.nio.charset.StandardCharsets

class GplCoreEngineBridgeImpl : GplCoreEngineBridge {
    override fun createDjvuSession(pfd: ParcelFileDescriptor): DocumentSession =
        CleanRoomDjvuSession(pfd)

    override fun createMobiSession(pfd: ParcelFileDescriptor, extension: String): DocumentSession =
        PalmDocMobiCleanRoomSession(pfd, extension)

    override fun createAccessSession(pfd: ParcelFileDescriptor, password: String?): DatabaseSessionLocal =
        CleanRoomAccessDatabaseSession(pfd, password)

    override fun renderDwg(bytes: ByteArray, password: String?, sink: DwgGeometrySink): String {
        val reader = CleanRoomDwgReader.open(bytes, password)
        reader.render(sink)
        return reader.version.code
    }
}

class GplCad3dBridgeImpl : GplCad3dBridge {
    override fun parseBlend(raw: ByteArray, out: GltfOutput): String? {
        val bytes = try { BlendFile.decompress(raw) } catch (_: Throwable) { return null }
        val file = try { BlendFile(bytes) } catch (_: Throwable) { return null }
        val v = file.version
        try {
            CleanRoomBlend(file, out).load()
        } catch (_: Throwable) {}
        return "Blender ${v / 100}.${v % 100}"
    }

    override fun parseFbx(bytes: ByteArray, out: GltfOutput): String? {
        if (bytes.size < 32) return null
        val root = FbxReader.parse(bytes) ?: return null
        val version = root.long(0)
        val modelType = if (String(bytes, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary") {
            "FBX Binary (v$version)"
        } else {
            "FBX ASCII (v$version)"
        }
        CleanRoomFbx(root, out).load()
        return modelType
    }
}
