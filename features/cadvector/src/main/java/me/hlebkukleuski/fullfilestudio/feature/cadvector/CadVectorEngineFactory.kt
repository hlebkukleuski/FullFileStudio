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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.DynamicEngineFactory
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.util.Locale

class CadVectorEngineFactory : DynamicEngineFactory {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf(
                "svg", "svgz", "stl", "obj", "dxf", "dwg", "ply", "off",
                "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            )
        ) {
            return true
        }
        // AutoCAD DWG magic header: "AC1012" .. "AC1032"
        if (signature.size >= 6 &&
            signature[0] == 'A'.code.toByte() &&
            signature[1] == 'C'.code.toByte() &&
            signature[2] == '1'.code.toByte() &&
            signature[3] == '0'.code.toByte()
        ) {
            return true
        }
        // Binary glTF 2.0 magic header: "glTF"
        if (signature.size >= 4 &&
            signature[0] == 'g'.code.toByte() &&
            signature[1] == 'l'.code.toByte() &&
            signature[2] == 'T'.code.toByte() &&
            signature[3] == 'F'.code.toByte()
        ) {
            return true
        }
        // Blender 3D magic header: "BLENDER"
        if (signature.size >= 7 &&
            String(signature, 0, 7, StandardCharsets.US_ASCII) == "BLENDER"
        ) {
            return true
        }
        // Pixar USD Crate binary magic header: "PXR-USDC"
        if (signature.size >= 8 &&
            String(signature, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC"
        ) {
            return true
        }
        // Autodesk FBX Binary magic header: "Kaydara FBX Binary"
        if (signature.size >= 18 &&
            String(signature, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary"
        ) {
            return true
        }
        return false
    }

    override fun createSession(fd: FileDescriptor, extension: String): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return if (ext == "svg" || ext == "svgz") {
            SvgVectorEngineImpl().createSession(fd)
        } else {
            Cad3dEngineImpl().createSessionForFormat(fd, ext)
        }
    }
}