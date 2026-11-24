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

package com.omnireader.core.engine

import android.graphics.Bitmap
import java.io.FileDescriptor
import java.util.Locale

sealed interface EngineErrorState {
    data class CorruptedOrUnsupported(val reason: String) : EngineErrorState
    data class PasswordRequiredOrEncrypted(val formatName: String, val details: String) : EngineErrorState
    data class ClosedLicenseBlocked(val formatName: String, val legalReason: String) : EngineErrorState
    data class ResourceLimitExceeded(val limitDescription: String) : EngineErrorState
    data class SecurityViolation(val violationMessage: String) : EngineErrorState
    data object OutOfMemory : EngineErrorState
    data class SandboxCrash(val details: String) : EngineErrorState
}

/**
 * Evaluates file formats against intellectual property law, cryptographic DRM anti-circumvention
 * statutes (DMCA 17 U.S.C. § 1201 / EU InfoSoc Directive 2001/29/EC), and clean-room read-only
 * interoperability principles before parsing.
 */
object LegalLicensePolicy {

    data class Verdict(
        val isLegallyPermitted: Boolean,
        val licenseCategory: String,
        val explanation: String
    )

    fun evaluate(
        format: DetectedFormat,
        extension: String,
        isDrmEncrypted: Boolean = false
    ): Verdict {
        val cleanExt = extension.lowercase(Locale.ROOT).trim()

        // 1. Strict prohibition on circumventing cryptographic DRM (DMCA § 1201 / EU InfoSoc Directive)
        if (format == DetectedFormat.KFX || cleanExt == "kfx") {
            return Verdict(
                isLegallyPermitted = false,
                licenseCategory = "DRM_ANTI_CIRCUMVENTION_RESTRICTION",
                explanation = "Amazon Kindle KFX container is protected by cryptographic DRM. Circumventing technological protection measures (TPM/DRM) is prohibited under DMCA § 1201 and the EU InfoSoc Directive."
            )
        }

        if (format == DetectedFormat.KINDLE_DRM ||
            (isDrmEncrypted && (format == DetectedFormat.AZW3 || format == DetectedFormat.MOBI || cleanExt in setOf("azw3", "azw", "mobi", "prc")))
        ) {
            return Verdict(
                isLegallyPermitted = false,
                licenseCategory = "DRM_ANTI_CIRCUMVENTION_RESTRICTION",
                explanation = "This Kindle e-book (AZW3/MOBI) is encrypted with cryptographic DRM (Encryption Type != 0). Circumventing DRM protection is prohibited by DMCA § 1201 and international copyright law."
            )
        }

        if (format == DetectedFormat.RAR_V5 || format == DetectedFormat.RAR_V4 || cleanExt == "rar") {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "OPEN_SOURCE_READ_ONLY_EXTRACTION",
                explanation = "Handled by a GPL-3.0-or-later read-only RAR4/RAR5 extractor derived from libarchive (BSD-2-Clause) and Public Domain PPMd7 without proprietary UnRAR code."
            )
        }

        if (format == DetectedFormat.DJVU || cleanExt == "djvu" || cleanExt == "djv") {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "GPL_COMPATIBLE_DECODER",
                explanation = "All DjVu format patents have expired. Handled by a GPL-3.0-or-later DjVu decoder derived from DjVuLibre (GPL-2.0-or-later)."
            )
        }

        // 4. Microsoft OneNote (.one): Covered by Microsoft Open Specifications Promise (OSP) [MS-ONE] & [MS-ONESTORE].
        if (format == DetectedFormat.ONE || cleanExt == "one") {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "MICROSOFT_OSP_OPEN_SPECIFICATION",
                explanation = "Handled by a clean-room read-only parser implementing the open [MS-ONE] and [MS-ONESTORE] specifications under the Microsoft Open Specifications Promise (OSP)."
            )
        }

        // 5. DWG Compatible (.dwg): Clean-room read-only interoperability without proprietary RealDWG libraries.
        if (format == DetectedFormat.DWG || cleanExt == "dwg") {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "CLEAN_ROOM_INTEROPERABILITY_READ_ONLY",
                explanation = "Handled by an independent clean-room read-only DWG parser extracting embedded preview graphics and CAD entities without proprietary RealDWG libraries."
            )
        }

        // 6. Databases (SQLite, MS Access .accdb/.mdb, dBase .dbf, Apache Parquet .parquet, DuckDB, Realm, BSON):
        if (format in setOf(DetectedFormat.SQLITE, DetectedFormat.ACCDB, DetectedFormat.MDB, DetectedFormat.DBF, DetectedFormat.PARQUET) ||
            cleanExt in setOf("db", "db3", "s3db", "sl3", "sqlite", "sqlite3", "accdb", "mdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson")
        ) {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "CLEAN_ROOM_INTEROPERABILITY_READ_ONLY",
                explanation = "Handled by a clean-room read-only database page & columnar table inspector without bundling closed database drivers."
            )
        }

        // 7. Fonts (.ttf, .otf, .ttc, .woff, .woff2): ISO/IEC 14496-22 (Open Font Format) & TrueType specification.
        if (format == DetectedFormat.FONT || cleanExt in setOf("ttf", "otf", "ttc", "woff", "woff2", "dfont")) {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "ISO_IEC_14496_22_OPEN_FONT_FORMAT",
                explanation = "Handled by a clean-room OpenType/TrueType (ISO/IEC 14496-22) font specimen and glyph table inspector."
            )
        }

        // 8. Unencrypted Amazon Kindle e-books (.azw3 / .mobi): PalmDOC Encryption Type == 0.
        if (format == DetectedFormat.AZW3 || format == DetectedFormat.MOBI || cleanExt in setOf("azw3", "mobi", "prc")) {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "CLEAN_ROOM_UNENCRYPTED_EBOOK",
                explanation = "Unencrypted PalmDOC/MOBI/KF8 e-book (Encryption Type == 0) rendered via clean-room read-only parser."
            )
        }

        // 9. 3ds Max (.max), Revit (.rvt), InDesign (.indd): Legal extraction of standard OLE2/XMP thumbnails and metadata.
        if (format in setOf(DetectedFormat.MAX, DetectedFormat.RVT, DetectedFormat.INDD) ||
            cleanExt in setOf("max", "rvt", "indd")
        ) {
            return Verdict(
                isLegallyPermitted = true,
                licenseCategory = "CLEAN_ROOM_CONTAINER_THUMBNAIL_AND_METADATA",
                explanation = "Legally extracts standard OLE2/XMP embedded preview thumbnails and project metadata in read-only mode without proprietary rendering plugins."
            )
        }

        return Verdict(
            isLegallyPermitted = true,
            licenseCategory = "OPEN_SPECIFICATION_CLEAN_ROOM",
            explanation = "Handled by a clean-room parser built on open specifications (ISO/ECMA/OSP/W3C/IETF)."
        )
    }
}

object OmniServerEndpoints {
    @Volatile
    var baseUrl: String = "https://hlebkukleuski.github.io/FullFileStudio"

    val appVersionManifestUrl: String get() = "$baseUrl/version.json"
    val privacyPolicyWebUrl: String get() = "$baseUrl/privacy.html"
    val termsOfServiceWebUrl: String get() = "$baseUrl/terms.html"
    val openSourceLicensesWebUrl: String get() = "$baseUrl/licenses.html"
}

@JvmInline
value class PackedTileKey(val packed: Long) {
    val x: Int get() = ((packed shl 44) shr 44).toInt()
    val y: Int get() = (((packed ushr 20) shl 44) shr 44).toInt()
    val zoom: Int get() = ((packed ushr 40) and 0xFFFFFF).toInt()

    companion object {
        fun create(x: Int, y: Int, zoom: Int): PackedTileKey {
            return PackedTileKey(
                (x.toLong() and 0xFFFFF) or
                        ((y.toLong() and 0xFFFFF) shl 20) or
                        ((zoom.toLong() and 0xFFFFFFL) shl 40)
            )
        }
    }
}

interface FileEngineProvider {
    fun canHandle(signature: ByteArray, extension: String): Boolean
    fun createSession(fd: FileDescriptor): DocumentSession
}

interface DocumentSession : AutoCloseable {
    val pageCount: Int
    val is3dContent: Boolean get() = false
    val isPresentationContent: Boolean get() = false
    val isCopyRestricted: Boolean get() = false

    fun getDocumentWidth(): Float = 0f
    fun getDocumentHeight(): Float = 0f

    fun getPageText(pageIndex: Int): String = ""

    fun getPageAspectRatio(pageIndex: Int): Float = 0.707f

    fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        renderTile(PackedTileKey.create(tileX, tileY, pageIndex), bitmap)
    }

    fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    fun search(query: String, startPage: Int = 0): Long = -1L

    fun getSheetNames(): List<String> = emptyList()
    fun getActiveSheetIndex(): Int = 0
    fun setActiveSheetIndex(index: Int) {}
    fun getColumnWidths(): FloatArray = FloatArray(0)
    fun getRowHeights(): FloatArray = FloatArray(0)
    fun getCellText(row: Int, col: Int): String = ""
    fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String = ""

    fun getLastError(): String? = null

    fun getMissingResources(): List<String> = emptyList()
    fun provideResource(name: String, fd: java.io.FileDescriptor): Boolean = false
    fun applyProvidedResources(): Boolean = false
    fun getAnimationDuration(): Float = 0f
    fun setAnimationTime(seconds: Float) {}
    fun getAnimationClips(): List<String> = emptyList()
    fun setAnimationClip(index: Int) {}

    // Every active session reports a positive state version (>= 1L) so IPC health checks distinguish live sessions from missing ones (-1L).
    fun getStateVersion(): Long = 1L

    override fun close()
}