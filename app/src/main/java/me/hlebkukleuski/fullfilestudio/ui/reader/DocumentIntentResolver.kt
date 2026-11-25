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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.security.StreamingCacheUtils

/**
 * Resolves incoming Android [Intent] and SAF [Uri] targets into seekable read-only file descriptors,
 * enforcing sandbox URI traversal guards against internal app storage and private FileProvider authorities.
 */
class DocumentIntentResolver(private val appContext: Context) {

    fun validateUriSecurity(uri: Uri) {
        if (uri.scheme.equals("content", ignoreCase = true) &&
            uri.authority.equals("${appContext.packageName}.fileprovider", ignoreCase = true)
        ) {
            throw SecurityException("Access to internal FileProvider URI is not permitted.")
        }

        if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrEmpty()) {
            val directFile = File(uri.path!!)
            val canonical = try { directFile.canonicalPath } catch (_: Throwable) { directFile.absolutePath }
            val appDataDir = try {
                File(appContext.applicationInfo.dataDir).canonicalPath
            } catch (_: Throwable) {
                appContext.applicationInfo.dataDir
            }
            if (canonical == appDataDir ||
                canonical.startsWith("$appDataDir/") ||
                canonical.startsWith("/data/data/") ||
                canonical.startsWith("/data/user/") ||
                canonical.startsWith("/data/user_de/") ||
                canonical == "/proc" ||
                canonical.startsWith("/proc/")
            ) {
                throw SecurityException("Access to internal application storage via file:// URI is not permitted.")
            }
        }
    }

    fun openSeekableDescriptor(
        uri: Uri,
        virtualDupProvider: () -> ParcelFileDescriptor?,
        bufferingErrorPrefix: String,
        onBufferingProgress: (Float) -> Unit
    ): ParcelFileDescriptor? {
        if (uri.scheme == "virtual") {
            return virtualDupProvider()
        }

        validateUriSecurity(uri)

        var pfd: ParcelFileDescriptor? = null
        var effectiveUri: Uri = uri
        var lastError: Exception? = null
        val resolver = appContext.contentResolver

        if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrEmpty()) {
            val directFile = File(uri.path!!)
            if (directFile.exists() && directFile.canRead()) {
                try {
                    pfd = ParcelFileDescriptor.open(directFile, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        if (pfd == null) {
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                effectiveUri = uri
                lastError = null
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        if (pfd == null) {
            try {
                pfd = resolver.openAssetFileDescriptor(effectiveUri, "r")?.parcelFileDescriptor
                if (pfd != null) lastError = null
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        if (pfd != null) {
            try {
                return StreamingCacheUtils.ensureSeekableParcelFileDescriptor(
                    contentResolver = resolver,
                    uri = effectiveUri,
                    originalPfd = pfd,
                    cacheDir = appContext.cacheDir,
                    onProgress = onBufferingProgress
                )
            } catch (e: Exception) {
                try { pfd.close() } catch (_: Exception) {}
                throw IllegalStateException("$bufferingErrorPrefix: ${e.localizedMessage ?: ""}", e)
            }
        }

        if (lastError != null) {
            throw lastError
        }

        return null
    }

    fun extractExtension(uri: Uri): String {
        if (uri.scheme == "virtual") {
            val vPath = Uri.decode(uri.toString().removePrefix("virtual://"))
            val fileName = vPath.substringAfterLast('/')
            val vExt = extractExtensionFromFileName(fileName)
            if (vExt.isNotEmpty()) return vExt
        }
        val resolver = appContext.contentResolver
        var displayName = ""
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        val dispExt = extractExtensionFromFileName(displayName)
        if (dispExt.isNotEmpty()) return dispExt

        val path = uri.lastPathSegment ?: uri.path ?: ""
        val lastName = path.substringAfterLast('/')
        val pathExt = extractExtensionFromFileName(lastName)
        if (pathExt.isNotEmpty()) return pathExt

        return try {
            extensionFromMimeType(resolver.getType(uri))
        } catch (_: Throwable) {
            ""
        }
    }

    fun extractExtensionFromFileName(fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT).trim()
        if (!lower.contains(".")) return ""
        return when {
            lower.endsWith(".fb2.zip") || lower.endsWith(".fbz") -> "fb2"
            lower.endsWith(".svg.gz") -> "svgz"
            lower.endsWith(".tar.gz") -> "tgz"
            lower.endsWith(".tar.bz2") -> "bz2"
            lower.endsWith(".tar.xz") -> "xz"
            else -> {
                val ext = lower.substringAfterLast(".", "").trim()
                if (ext.isNotEmpty() && ext.length <= 12 && ext.all { it.isLetterOrDigit() }) ext else ""
            }
        }
    }

    fun extensionFromMimeType(mimeType: String?): String {
        val cleanMime = mimeType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: return ""
        if (cleanMime.isEmpty() || cleanMime == "*/*" || cleanMime == "application/octet-stream") return ""
        return when (cleanMime) {
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-word.document.macroenabled.12" -> "docm"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" -> "dotx"
            "application/vnd.ms-word.template.macroenabled.12" -> "dotm"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel.sheet.macroenabled.12" -> "xlsm"
            "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> "xlsb"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" -> "xltx"
            "application/vnd.ms-excel.template.macroenabled.12" -> "xltm"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> "pptm"
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> "ppsx"
            "application/vnd.ms-powerpoint.slideshow.macroenabled.12" -> "ppsm"
            "application/vnd.openxmlformats-officedocument.presentationml.template" -> "potx"
            "application/vnd.ms-powerpoint.template.macroenabled.12" -> "potm"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template" -> "odt"
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template" -> "ods"
            "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template" -> "odp"
            "application/rtf", "text/rtf" -> "rtf"
            "application/onenote", "application/msonenote" -> "one"
            "application/epub+zip" -> "epub"
            "application/x-fictionbook+xml", "application/x-fb2", "application/x-zip-compressed-fb2" -> "fb2"
            "image/vnd.djvu", "image/x-djvu" -> "djvu"
            "font/ttf", "application/x-font-ttf", "application/font-sfnt" -> "ttf"
            "font/otf", "application/x-font-otf", "application/vnd.ms-opentype" -> "otf"
            "font/collection" -> "ttc"
            "font/woff", "application/font-woff" -> "woff"
            "font/woff2" -> "woff2"
            "image/svg+xml" -> "svg"
            "image/svg+xml-compressed" -> "svgz"
            "model/stl", "application/sla", "application/vnd.ms-pki.stl" -> "stl"
            "model/obj", "application/x-tgif" -> "obj"
            "model/gltf+json" -> "gltf"
            "model/gltf-binary" -> "glb"
            "model/vnd.usdz+zip", "model/vnd.pixar.usd" -> "usdz"
            "model/3mf", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" -> "3mf"
            "model/vnd.collada+xml" -> "dae"
            "application/step", "model/step" -> "step"
            "model/iges", "application/iges" -> "iges"
            "model/ply", "application/x-ply" -> "ply"
            "application/x-blender" -> "blend"
            "image/vnd.dxf", "application/dxf" -> "dxf"
            "image/vnd.dwg", "application/acad", "application/x-acad" -> "dwg"
            "application/vnd.sqlite3", "application/x-sqlite3" -> "sqlite"
            "application/x-msaccess", "application/msaccess" -> "mdb"
            "application/x-dbf", "application/dbf" -> "dbf"
            "application/vnd.apache.parquet", "application/x-parquet" -> "parquet"
            "application/x-7z-compressed" -> "7z"
            "application/vnd.rar", "application/x-rar-compressed" -> "rar"
            "application/x-tar" -> "tar"
            "application/gzip", "application/x-gzip" -> "gz"
            "application/x-bzip2" -> "bz2"
            "application/x-xz" -> "xz"
            "application/x-iso9660-image" -> "iso"
            "application/vnd.android.package-archive" -> "apk"
            "application/zip" -> "zip"
            "text/csv", "text/comma-separated-values" -> "csv"
            "text/tab-separated-values" -> "tsv"
            "text/plain" -> "txt"
            "application/json" -> "json"
            "application/x-ndjson", "application/jsonl" -> "jsonl"
            "text/xml", "application/xml" -> "xml"
            "text/markdown" -> "md"
            "image/tiff" -> "tiff"
            "image/heic", "image/heic-sequence" -> "heic"
            "image/heif", "image/heif-sequence" -> "heif"
            "image/avif", "image/avif-sequence" -> "avif"
            "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)?.lowercase(Locale.ROOT)?.trim() ?: ""
        }
    }

    fun resolveEffectiveExtension(format: DetectedFormat, rawExt: String): String {
        val cleanExt = rawExt.lowercase(Locale.ROOT).trim()
        return when (format) {
            DetectedFormat.PDF -> "pdf"
            DetectedFormat.DJVU -> "djvu"
            DetectedFormat.FONT -> if (cleanExt in setOf("ttf", "otf", "ttc", "woff", "woff2", "dfont")) cleanExt else "ttf"
            DetectedFormat.DOCX -> "docx"
            DetectedFormat.DOC -> "doc"
            DetectedFormat.XLSX -> if (cleanExt == "xlsb") "xlsb" else "xlsx"
            DetectedFormat.XLS -> "xls"
            DetectedFormat.PPTX -> "pptx"
            DetectedFormat.PPT -> "ppt"
            DetectedFormat.ODT -> "odt"
            DetectedFormat.ODS -> "ods"
            DetectedFormat.ODP -> "odp"
            DetectedFormat.RTF -> "rtf"
            DetectedFormat.EPUB -> "epub"
            DetectedFormat.FB2 -> "fb2"
            DetectedFormat.SVG -> "svg"
            DetectedFormat.SVGZ -> "svgz"
            DetectedFormat.GLTF -> "gltf"
            DetectedFormat.GLB -> "glb"
            DetectedFormat.BLEND -> "blend"
            DetectedFormat.USDZ -> if (cleanExt in setOf("usd", "usda", "usdc")) cleanExt else "usdz"
            DetectedFormat.STL -> if (cleanExt in setOf(
                    "ply", "off", "3ds", "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc",
                    "fbx", "3mf", "dae", "zae", "step", "stp", "iges", "igs"
                )
            ) cleanExt else "stl"
            DetectedFormat.OBJ -> "obj"
            DetectedFormat.DXF -> "dxf"
            DetectedFormat.DWG -> "dwg"
            DetectedFormat.SQLITE -> "sqlite"
            DetectedFormat.ACCDB -> "accdb"
            DetectedFormat.MDB -> "mdb"
            DetectedFormat.DBF -> "dbf"
            DetectedFormat.PARQUET -> if (cleanExt in setOf("duckdb", "realm", "bson", "jsonl", "ndjson")) cleanExt else "parquet"
            DetectedFormat.MHTML -> if (cleanExt == "mht") "mht" else "mhtml"
            DetectedFormat.ONE -> "one"
            DetectedFormat.AZW3 -> "azw3"
            DetectedFormat.MOBI -> "mobi"
            DetectedFormat.KFX -> "kfx"
            DetectedFormat.MAX -> "max"
            DetectedFormat.RVT -> "rvt"
            DetectedFormat.INDD -> "indd"
            DetectedFormat.PNG -> "png"
            DetectedFormat.JPEG -> if (cleanExt in setOf("jpg", "jpeg", "jpe", "jfif")) cleanExt else "jpg"
            DetectedFormat.WEBP -> "webp"
            DetectedFormat.GIF -> "gif"
            DetectedFormat.BMP -> if (cleanExt in setOf("ico", "cur", "wbmp")) cleanExt else "bmp"
            DetectedFormat.TIFF -> if (cleanExt in setOf("tif", "tiff", "dng", "cr2", "nef", "arw")) cleanExt else "tiff"
            DetectedFormat.HEIC -> if (cleanExt in setOf("heic", "heif", "hif")) cleanExt else "heic"
            DetectedFormat.AVIF -> if (cleanExt in setOf("avif", "avifs")) cleanExt else "avif"
            DetectedFormat.ISO -> "iso"
            DetectedFormat.ZIP -> when (cleanExt) {
                "docm", "dotx", "dotm" -> "docx"
                "xlsm", "xltx", "xltm" -> "xlsx"
                "xlsb" -> "xlsb"
                "pptm", "ppsx", "ppsm", "potx", "potm" -> "pptx"
                "ott" -> "odt"
                "ots" -> "ods"
                "otp" -> "odp"
                "fbz" -> "fb2"
                "usdz" -> "usdz"
                "3mf" -> "3mf"
                "zae" -> "zae"
                "apk", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "fb2" -> cleanExt
                else -> "zip"
            }
            DetectedFormat.SEVEN_ZIP -> "7z"
            DetectedFormat.RAR_V4, DetectedFormat.RAR_V5 -> "rar"
            DetectedFormat.TAR -> if (cleanExt == "tgz") "tgz" else "tar"
            DetectedFormat.GZ -> when (cleanExt) {
                "tgz" -> "tgz"
                "svgz" -> "svgz"
                "blend" -> "blend"
                else -> "gz"
            }
            DetectedFormat.BZ2 -> "bz2"
            DetectedFormat.XZ -> "xz"
            DetectedFormat.APK -> if (cleanExt == "jar") "jar" else "apk"
            DetectedFormat.PLAINTEXT -> cleanExt.ifEmpty { "txt" }
            else -> cleanExt
        }
    }

    companion object {
        val ARCHIVE_SIZE_SUFFIX_REGEX = Regex(""" \(\d+ B\)$| \(unknown size\)$""", RegexOption.IGNORE_CASE)

        fun extractIncomingIntentUri(intent: Intent?): Uri? {
            if (intent == null) return null
            intent.data?.let { return it }
            val clip = intent.clipData
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.uri?.let { return it }
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        }

        fun clearConsumedIntentPayload(intent: Intent?) {
            if (intent == null) return
            intent.data = null
            intent.clipData = null
            intent.removeExtra(Intent.EXTRA_STREAM)
        }
    }
}
