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
 *
 * Implements OLE2 Compound File Binary ([MS-CFB]), Excel BIFF8/BIFF12 ([MS-XLS] / [MS-XLSB]),
 * Word Binary ([MS-DOC]), PowerPoint Binary ([MS-PPT]), OneNote ([MS-ONE] / [MS-ONESTORE])
 * under the Microsoft Open Specifications Promise (OSP), ISO-9660 (ECMA-119), and dBase DBF,
 * with BIFF8/OLE2 structure definitions informed by Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.util.Locale
import kotlin.math.max
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

object LegacyAndIsoEngine {

    fun createSession(
        fd: FileDescriptor,
        format: DetectedFormat,
        extension: String,
        password: String? = null
    ): DocumentSession {
        val ext = extension.lowercase(Locale.ROOT).trim()
        val pfd = ParcelFileDescriptor.dup(fd)
        return when {
            format in setOf(DetectedFormat.SVG, DetectedFormat.SVGZ) || ext in setOf("svg", "svgz") ->
                me.hlebkukleuski.fullfilestudio.core.document.svg.SvgDocumentSession(pfd)
            format in setOf(
                DetectedFormat.STL, DetectedFormat.OBJ, DetectedFormat.DXF, DetectedFormat.DWG,
                DetectedFormat.GLTF, DetectedFormat.GLB, DetectedFormat.BLEND, DetectedFormat.USDZ
            ) || ext in setOf(
                "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
                "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            ) -> BuiltIn3dMeshSession(pfd, ext, password)
            format in setOf(
                DetectedFormat.ISO, DetectedFormat.ZIP, DetectedFormat.SEVEN_ZIP,
                DetectedFormat.RAR_V4, DetectedFormat.RAR_V5, DetectedFormat.TAR,
                DetectedFormat.GZ, DetectedFormat.BZ2, DetectedFormat.XZ, DetectedFormat.APK
            ) || ext in setOf("iso", "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "apk", "jar") ->
                Iso9660ArchiveSession(pfd)
            format == DetectedFormat.ONE || ext == "one" -> OneNoteDocumentSession(pfd)
            format in setOf(DetectedFormat.MAX, DetectedFormat.RVT, DetectedFormat.INDD) ||
                    ext in setOf("max", "rvt", "indd") -> ProprietaryContainerPreviewSession(pfd, format, ext)
            format == DetectedFormat.RTF || ext == "rtf" -> RtfDocumentSession(pfd)
            format in setOf(DetectedFormat.XLS, DetectedFormat.XLSX, DetectedFormat.ODS) ||
                    ext in setOf("xls", "xlt", "xlsx", "xlsm", "xlsb", "xltx", "xltm", "ods", "ots", "csv", "tsv") ->
                Biff8XlsSession(pfd, password)
            format in setOf(DetectedFormat.PPT, DetectedFormat.PPTX, DetectedFormat.ODP) ||
                    ext in setOf("ppt", "pps", "pot", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp") ->
                PptDocumentSession(pfd, password)
            else -> LegacyDocSession(pfd, password)
        }
    }
}

/**
 * Built-in Clean-Room 3D/CAD Session in `:core:engine-api` supporting `glTF 2.0`, `GLB`, `Blender .blend`,
 * `USDZ / USDA / USDC`, `FBX` (Binary & ASCII), `STL` (Binary & ASCII), `OBJ`, `PLY` (Binary & ASCII),
 * `OFF`, `3DS`, `3MF`, `COLLADA .dae`, `STEP / STP`, `IGES / IGS`, `DXF` (Binary & ASCII), and `DWG`
 * with Zero-OOM `ZeroCacheReadableSource` streaming for Standalone APK builds.
 */
