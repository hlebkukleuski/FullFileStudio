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
import com.omnireader.core.engine.DetectedFormat
import java.util.Locale

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

    private val prefs = context.getSharedPreferences("play_feature_prefs", Context.MODE_PRIVATE)

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
        markModulesInstalledSynchronously(availableBundles.map { it.id })
    }

    private fun factoryClassNameForModule(moduleName: String): String? = when (moduleName) {
        "office" -> "me.hlebkukleuski.fullfilestudio.feature.office.OfficeEngineFactory"
        "cadvector" -> "me.hlebkukleuski.fullfilestudio.feature.cadvector.CadVectorEngineFactory"
        "archives" -> "me.hlebkukleuski.fullfilestudio.feature.archives.ArchiveEngineFactory"
        else -> null
    }

    fun isFactoryClassAvailable(moduleName: String): Boolean {
        val className = factoryClassNameForModule(moduleName) ?: return false
        return try {
            Class.forName(className, false, context.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun markModulesInstalledSynchronously(modules: Collection<String>) {
        val editor = prefs.edit()
        modules.forEach { editor.putBoolean("module_installed_$it", true) }
        editor.apply()
    }

    fun isModuleDownloaded(@Suppress("UNUSED_PARAMETER") moduleName: String): Boolean = true

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
        @Suppress("UNUSED_PARAMETER") onError: (Int) -> Unit
    ) {
        markModulesInstalledSynchronously(moduleNames)
        onProgress(1.0f)
        onSuccess()
    }

    fun isFirstLaunchCompleted(): Boolean = prefs.getBoolean("first_launch_complete", false)
    fun setFirstLaunchCompleted() = prefs.edit().putBoolean("first_launch_complete", true).apply()
}
