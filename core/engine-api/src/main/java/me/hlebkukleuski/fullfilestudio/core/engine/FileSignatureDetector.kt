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

package me.hlebkukleuski.fullfilestudio.core.engine

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.min

enum class DetectedFormat {
    UNKNOWN, CORRUPTED_CONTAINER, PDF, EPUB, FB2, DJVU, ZIP, RAR_V4, RAR_V5, SEVEN_ZIP,
    SQLITE, JPEG, PNG, GIF, TIFF, ELF, DEX, WEBP, BMP, SVG, SVGZ,
    OFFICE_XML, DOCX, XLSX, PPTX, ODS, ODT, ODP,
    DOC, XLS, PPT, RTF, APK,
    HEIC, AVIF, MP4, MP3, STL, OBJ, DXF, ISO, TAR, GZ, BZ2, XZ, PLAINTEXT,
    ONE, DWG, ACCDB, MDB, AZW3, MOBI, KFX, KINDLE_DRM, MAX, RVT, INDD,
    FONT, DBF, PARQUET, GLTF, GLB, BLEND, USDZ, MHTML
}

object FileSignatureDetector {

    private val NESTED_CONTAINER_EXTENSIONS = setOf(
        "zip", "jar", "apk", "usdz", "3mf", "zae",
        "docx", "docm", "dotx", "dotm",
        "xlsx", "xlsm", "xlsb", "xltx", "xltm",
        "pptx", "pptm", "ppsx", "ppsm", "potx", "potm",
        "odt", "ott", "ods", "ots", "odp", "otp", "epub", "fbz"
    )

    private val ONENOTE_HEADER_GUID = byteArrayOf(
        0xE4.toByte(), 0x52.toByte(), 0x5C.toByte(), 0x7B.toByte(),
        0x8C.toByte(), 0xD8.toByte(), 0xA7.toByte(), 0x4D.toByte(),
        0xAE.toByte(), 0xB1.toByte(), 0x53.toByte(), 0x78.toByte(),
        0xD0.toByte(), 0x29.toByte(), 0x96.toByte(), 0xD3.toByte()
    )

    private val ONENOTE_LEGACY_GUID = byteArrayOf(
        0xA1.toByte(), 0x2F.toByte(), 0xFF.toByte(), 0x43.toByte(),
        0xD9.toByte(), 0xEF.toByte(), 0x76.toByte(), 0x4C.toByte(),
        0x9E.toByte(), 0xE2.toByte(), 0x10.toByte(), 0xEA.toByte(),
        0x57.toByte(), 0x22.toByte(), 0x76.toByte(), 0x5F.toByte()
    )

    private val INDD_MASTER_GUID = byteArrayOf(
        0x06.toByte(), 0x06.toByte(), 0xED.toByte(), 0xF5.toByte(),
        0xD8.toByte(), 0x1D.toByte(), 0x46.toByte(), 0xE5.toByte(),
        0xBD.toByte(), 0x31.toByte(), 0xEF.toByte(), 0xE7.toByte(),
        0xFE.toByte(), 0x74.toByte(), 0xB7.toByte(), 0x1D.toByte()
    )

    private val KNOWN_SFNT_TABLES = setOf(
        "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post",
        "OS/2", "GSUB", "GPOS", "GDEF", "CFF ", "CFF2", "cvt ", "fpgm", "prep",
        "gasp", "kern", "DSIG", "FFTM", "LTSH", "VDMX", "hdmx", "vhea", "vmtx"
    )

    private val AVIF_BRANDS = setOf(
        "avif", "avis", "avio", "ma1b", "ma1a"
    )

    private val HEIC_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "mif1", "msf1", "miaf", "mihe", "mihb", "heif", "hif "
    )

    private val AUDIO_M4A_BRANDS = setOf(
        "m4a ", "m4b ", "m4p ", "aac ", "f4a ", "f4b "
    )

    fun resolveFormat(
        headerBuffer: ByteBuffer,
        extensionHint: String = "",
        fileSize: Long = -1L
    ): DetectedFormat {
        return resolveFormatInternal(null, headerBuffer, extensionHint, fileSize)
    }

    fun resolveFormat(
        fd: FileDescriptor,
        headerBuffer: ByteBuffer,
        extensionHint: String = "",
        fileSize: Long = -1L
    ): DetectedFormat {
        return resolveFormatInternal(fd, headerBuffer, extensionHint, fileSize)
    }

    private fun matchesPrefix(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun isSfntFontHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numTables = bb.getShort(4).toInt() and 0xFFFF
        if (numTables !in 1..128) return false
        val t0 = bytes[12].toInt() and 0xFF
        val t1 = bytes[13].toInt() and 0xFF
        val t2 = bytes[14].toInt() and 0xFF
        val t3 = bytes[15].toInt() and 0xFF
        if (t0 !in 0x20..0x7E || t1 !in 0x20..0x7E || t2 !in 0x20..0x7E || t3 !in 0x20..0x7E) {
            return false
        }
        val firstTag = String(bytes, 12, 4, StandardCharsets.US_ASCII)
        if (firstTag in KNOWN_SFNT_TABLES) return true
        if (bytes.size >= 32 && numTables >= 2) {
            val secondTag = String(bytes, 28, 4, StandardCharsets.US_ASCII)
            if (secondTag in KNOWN_SFNT_TABLES) return true
        }
        return true
    }

    private fun isLikelyDbfHeader(bytes: ByteArray, ext: String, fileSize: Long): Boolean {
        if (bytes.size < 32) return false
        val ver = bytes[0].toInt() and 0xFF
        val validVer = ver in setOf(0x02, 0x03, 0x04, 0x05, 0x30, 0x31, 0x32, 0x43, 0x63, 0x83, 0x8B, 0x8E, 0xF5)
        if (!validVer) return false
        val month = bytes[2].toInt() and 0xFF
        val day = bytes[3].toInt() and 0xFF
        if (month !in 1..12 || day !in 1..31) return false

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numRecords = bb.getInt(4).toLong() and 0xFFFFFFFFL
        val headerLen = bb.getShort(8).toInt() and 0xFFFF
        val recordLen = bb.getShort(10).toInt() and 0xFFFF
        if (headerLen < 32 || recordLen < 1 || numRecords > 50_000_000L) return false
        if (ext == "dbf") return true
        if (headerLen <= bytes.size && (bytes[headerLen - 1].toInt() and 0xFF) in setOf(0x0D, 0x00)) {
            if (fileSize <= 0L || (headerLen + numRecords * recordLen) in (fileSize - 512)..(fileSize + 512)) {
                return true
            }
        }
        return false
    }

    private fun isVerifiedBinaryStl(bytes: ByteArray, ext: String, fileSize: Long): Boolean {
        if (bytes.size < 84) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val triCount = bb.getInt(80).toLong() and 0xFFFFFFFFL
        if (triCount !in 1L..25_000_000L) return false
        val expectedSize = 84L + triCount * 50L
        if (fileSize >= 134L) {
            if (expectedSize == fileSize) return true
            if (ext == "stl" && fileSize in expectedSize..(expectedSize + 64L)) return true
        }
        return false
    }

    private fun isVerified3dsHeader(bytes: ByteArray, ext: String, fileSize: Long): Boolean {
        if (bytes.size < 12) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mainId = bb.getShort(0).toInt() and 0xFFFF
        if (mainId != 0x4D4D) return false
        val mainLen = bb.getInt(2).toLong() and 0xFFFFFFFFL
        val subId = bb.getShort(6).toInt() and 0xFFFF
        if (ext == "3ds") return true
        if (fileSize >= 16L && mainLen == fileSize && subId in setOf(0x0002, 0x3D3D, 0x3D3E, 0xB000)) {
            return true
        }
        return false
    }

    private fun classifyIsoBmffFtyp(bytes: ByteArray, ext: String): DetectedFormat {
        if (bytes.size < 12) return DetectedFormat.MP4
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val rawSize = bb.getInt(0).toLong() and 0xFFFFFFFFL
        val payloadOffset: Int
        val boxEnd: Int

        if (rawSize == 1L && bytes.size >= 20) {
            val largeSize = bb.getLong(8)
            payloadOffset = 16
            boxEnd = if (largeSize in 16L..bytes.size.toLong()) largeSize.toInt() else min(bytes.size, 128)
        } else if (rawSize == 0L) {
            payloadOffset = 8
            boxEnd = min(bytes.size, 128)
        } else {
            payloadOffset = 8
            boxEnd = rawSize.toInt().coerceIn(12, min(bytes.size, 128))
        }

        if (payloadOffset + 4 > boxEnd) {
            return when (ext) {
                "avif", "avifs" -> DetectedFormat.AVIF
                "heic", "heif", "hif" -> DetectedFormat.HEIC
                "m4a", "aac" -> DetectedFormat.MP3
                else -> DetectedFormat.MP4
            }
        }

        val majorBrand = String(bytes, payloadOffset, 4, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
        val compatBrands = LinkedHashSet<String>()
        var pos = payloadOffset + 8 // Skip 4-byte major_brand + 4-byte minor_version
        while (pos + 4 <= boxEnd) {
            compatBrands.add(String(bytes, pos, 4, StandardCharsets.US_ASCII).lowercase(Locale.ROOT))
            pos += 4
        }

        val hasAvifBrand = majorBrand in AVIF_BRANDS || compatBrands.any { it in AVIF_BRANDS } || ext in setOf("avif", "avifs")
        if (hasAvifBrand) {
            return DetectedFormat.AVIF
        }

        val hasHeicBrand = majorBrand in HEIC_BRANDS || compatBrands.any { it in HEIC_BRANDS } || ext in setOf("heic", "heif", "hif")
        if (hasHeicBrand) {
            return DetectedFormat.HEIC
        }

        if (majorBrand in AUDIO_M4A_BRANDS || ext in setOf("m4a", "aac")) {
            return DetectedFormat.MP3
        }

        return DetectedFormat.MP4
    }

    private fun isLikelyWavefrontObj(headText: String, ext: String): Boolean {
        if (ext == "obj") return true
        var vertexLines = 0
        var faceOrNormLines = 0
        for (rawLine in headText.lineSequence().take(80)) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("v ") -> vertexLines++
                line.startsWith("vn ") || line.startsWith("vt ") || line.startsWith("f ") ||
                        line.startsWith("mtllib ") || line.startsWith("usemtl ") -> faceOrNormLines++
            }
        }
        return vertexLines >= 2 && (faceOrNormLines >= 1 || vertexLines >= 4)
    }

    private fun isLikelyAsciiDxf(headText: String): Boolean {
        val lines = headText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(16).toList()
        for (i in 0 until lines.size - 1) {
            if (lines[i] == "0" && lines[i + 1].equals("SECTION", ignoreCase = true)) {
                return true
            }
            if (lines[i] == "999" && i + 2 < lines.size && lines[i + 2] == "0") {
                return true
            }
        }
        return false
    }

    private fun isLikelyIgesHeader(headText: String): Boolean {
        val firstLine = headText.lineSequence().firstOrNull { it.isNotEmpty() }?.trimEnd('\r') ?: return false
        if (firstLine.length != 80) return false
        val sec = firstLine[72]
        if (sec != 'S' && sec != 's') return false
        val seq = firstLine.substring(73).trim()
        return seq.isNotEmpty() && seq.all { it.isDigit() }
    }

    private fun resolveFormatInternal(
        fd: FileDescriptor?,
        headerBuffer: ByteBuffer,
        extensionHint: String,
        fileSize: Long
    ): DetectedFormat {
        val ext = extensionHint.lowercase(Locale.ROOT).trim()
        val limit = headerBuffer.limit()
        val bytes = ByteArray(limit.coerceAtLeast(0))
        if (limit > 0) {
            val pos = headerBuffer.position()
            headerBuffer.position(0)
            headerBuffer.get(bytes)
            headerBuffer.position(pos)
        }

        if (bytes.size >= 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF

            // ELF binary: 7F 'E' 'L' 'F'
            if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) {
                return DetectedFormat.ELF
            }

            // Dalvik Executable (DEX): 'd' 'e' 'x' '\n'
            if (b0 == 0x64 && b1 == 0x65 && b2 == 0x78 && b3 == 0x0A) {
                return DetectedFormat.DEX
            }

            // PDF document: %PDF
            if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) {
                return DetectedFormat.PDF
            }

            // Blender 3D (.blend): "BLENDER" magic (or Zstandard 28 B5 2F FD with .blend extension)
            if (bytes.size >= 7 &&
                b0 == 'B'.code && b1 == 'L'.code && b2 == 'E'.code && b3 == 'N'.code &&
                bytes[4] == 'D'.code.toByte() && bytes[5] == 'E'.code.toByte() && bytes[6] == 'R'.code.toByte()
            ) {
                return DetectedFormat.BLEND
            }
            if (b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD && ext == "blend") {
                return DetectedFormat.BLEND
            }

            // Pixar USD Crate binary (.usdc / .usd / .usdz): "PXR-USDC"
            if (bytes.size >= 8 &&
                b0 == 'P'.code && b1 == 'X'.code && b2 == 'R'.code && b3 == '-'.code &&
                bytes[4] == 'U'.code.toByte() && bytes[5] == 'S'.code.toByte() &&
                bytes[6] == 'D'.code.toByte() && bytes[7] == 'C'.code.toByte()
            ) {
                return DetectedFormat.USDZ
            }

            // glTF 2.0 binary 3D model ("glTF" magic 0x46546C67)
            if (b0 == 'g'.code && b1 == 'l'.code && b2 == 'T'.code && b3 == 'F'.code) {
                return DetectedFormat.GLB
            }

            // Autodesk FBX Binary ("Kaydara FBX Binary")
            if (bytes.size >= 18 && String(bytes, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary") {
                return DetectedFormat.STL
            }

            // DWG Compatible Binary DXF ("AutoCAD Binary DXF")
            if (bytes.size >= 18 && String(bytes, 0, 18, StandardCharsets.US_ASCII) == "AutoCAD Binary DXF") {
                return DetectedFormat.DXF
            }

            // Microsoft OneNote (.one) [MS-ONESTORE]
            if (matchesPrefix(bytes, ONENOTE_HEADER_GUID) || matchesPrefix(bytes, ONENOTE_LEGACY_GUID)) {
                return DetectedFormat.ONE
            }

            // Adobe InDesign (.indd)
            if (matchesPrefix(bytes, INDD_MASTER_GUID)) {
                return DetectedFormat.INDD
            }

            // DWG Compatible: "AC1012", "AC1014", "AC1015", "AC1018", "AC1021", "AC1024", "AC1027", "AC1032"
            if (bytes.size >= 6 && b0 == 'A'.code && b1 == 'C'.code && b2 == '1'.code && b3 == '0'.code &&
                bytes[4].toInt().toChar().isDigit() && bytes[5].toInt().toChar().isDigit()
            ) {
                return DetectedFormat.DWG
            }

            // Microsoft Access Jet4 (.mdb) & ACE (.accdb) — checked BEFORE TrueType 00 01 00 00
            if (bytes.size >= 20 && b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
                val magicStr = String(bytes, 4, 15, StandardCharsets.US_ASCII)
                if (magicStr.startsWith("Standard ACE DB")) return DetectedFormat.ACCDB
                if (magicStr.startsWith("Standard Jet DB")) return DetectedFormat.MDB
            }

            // TrueType / OpenType / TTC / WOFF / WOFF2 Fonts
            if ((b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00 && isSfntFontHeader(bytes)) ||
                (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code && isSfntFontHeader(bytes)) ||
                (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code) ||
                (b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code && isSfntFontHeader(bytes)) ||
                (b0 == 'w'.code && b1 == 'O'.code && b2 == 'F'.code && (b3 == 'F'.code || b3 == '2'.code))
            ) {
                return DetectedFormat.FONT
            }

            if (b0 == 'P'.code && b1 == 'A'.code && b2 == 'R'.code && (b3 == '1'.code || b3 == 'E'.code)) {
                return DetectedFormat.PARQUET
            }

            // DuckDB database ("DUCK" at offset 8)
            if (bytes.size >= 12 &&
                bytes[8] == 'D'.code.toByte() &&
                bytes[9] == 'U'.code.toByte() &&
                bytes[10] == 'C'.code.toByte() &&
                bytes[11] == 'K'.code.toByte()
            ) {
                return DetectedFormat.PARQUET
            }

            if (bytes.size >= 24 &&
                bytes[16] == 'T'.code.toByte() &&
                bytes[17] == '-'.code.toByte() &&
                bytes[18] == 'D'.code.toByte() &&
                bytes[19] == 'B'.code.toByte() &&
                (bytes[20].toInt() and 0xFF) in 0..1 &&
                (bytes[21].toInt() and 0xFF) in 0..1
            ) {
                return DetectedFormat.PARQUET
            }

            // Amazon Kindle KFX container ("CONT" magic or explicit .kfx)
            if (b0 == 'C'.code && b1 == 'O'.code && b2 == 'N'.code && b3 == 'T'.code && ext == "kfx") {
                return DetectedFormat.KFX
            }

            // PalmDB / Mobipocket / Kindle (BOOKMOBI or TEXtREAd at offset 60)
            if (bytes.size >= 68) {
                val pdbType = String(bytes, 60, 8, StandardCharsets.US_ASCII)
                if (pdbType == "BOOKMOBI" || pdbType == "TEXtREAd") {
                    return inspectKindlePalmDb(fd, bytes, ext)
                }
            }

            // ZIP-based containers: PK\x03\x04, PK\x05\x06 (empty ZIP), or PK\x07\x08
            if (b0 == 0x50 && b1 == 0x4B &&
                ((b2 == 0x03 && b3 == 0x04) || (b2 == 0x05 && b3 == 0x06) || (b2 == 0x07 && b3 == 0x08))
            ) {
                return classifyZipBasedContainer(fd, bytes, ext, fileSize)
            }

            // OLE2 Compound Document: D0 CF 11 E0
            if (b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0) {
                if (bytes.size < 512 && fileSize in 1..511) {
                    return DetectedFormat.CORRUPTED_CONTAINER
                }
                return classifyOle2Container(fd, bytes, ext, fileSize)
            }

            // RAR v4 / v5: Rar!\x1A\x07
            if (bytes.size >= 7 && b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21 &&
                (bytes[4].toInt() and 0xFF) == 0x1A && (bytes[5].toInt() and 0xFF) == 0x07
            ) {
                return if ((bytes[6].toInt() and 0xFF) == 0x01) DetectedFormat.RAR_V5 else DetectedFormat.RAR_V4
            }

            // 7z archive: 37 7A BC AF 27 1C
            if (bytes.size >= 6 && b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) {
                return DetectedFormat.SEVEN_ZIP
            }

            // DjVu: AT&TFORM
            if (bytes.size >= 8 && b0 == 0x41 && b1 == 0x54 && b2 == 0x26 && b3 == 0x54 &&
                bytes[4] == 'F'.code.toByte() && bytes[5] == 'O'.code.toByte() &&
                bytes[6] == 'R'.code.toByte() && bytes[7] == 'M'.code.toByte()
            ) {
                return DetectedFormat.DJVU
            }

            // SQLite 3 database: "SQLite format 3\0"
            if (bytes.size >= 16 && String(bytes, 0, 15, StandardCharsets.US_ASCII) == "SQLite format 3") {
                return DetectedFormat.SQLITE
            }

            // PNG image
            if (bytes.size >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                return DetectedFormat.PNG
            }

            // JPEG image (FF D8 FF or FF D8 with image extension)
            if (b0 == 0xFF && b1 == 0xD8 && (b2 == 0xFF || ext in setOf("jpg", "jpeg", "jpe", "jfif"))) {
                return DetectedFormat.JPEG
            }

            // GIF image
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) {
                return DetectedFormat.GIF
            }

            // Windows ICO / CUR image: 00 00 01 00 or 00 00 02 00
            if (bytes.size >= 6 && b0 == 0x00 && b1 == 0x00 && (b2 == 0x01 || b2 == 0x02) && b3 == 0x00) {
                val imgCount = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
                if (imgCount in 1..64 && (ext in setOf("ico", "cur") || bytes.size >= 22)) {
                    return DetectedFormat.BMP
                }
            }

            // RIFF container: WEBP, WAVE, AVI
            if (bytes.size >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
                val riffType = String(bytes, 8, 4, StandardCharsets.US_ASCII)
                when (riffType) {
                    "WEBP" -> return DetectedFormat.WEBP
                    "WAVE" -> return DetectedFormat.MP3
                    "AVI " -> return DetectedFormat.MP4
                }
            }

            // FLAC audio: "fLaC"
            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) {
                return DetectedFormat.MP3
            }

            // OGG audio: "OggS"
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) {
                return DetectedFormat.MP3
            }

            // Matroska / WebM video: 1A 45 DF A3
            if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) {
                return DetectedFormat.MP4
            }

            // BMP image
            if (b0 == 0x42 && b1 == 0x4D) {
                return DetectedFormat.BMP
            }

            // 3DS (Autodesk 3D Studio mesh): 0x4D4D Little-Endian primary chunk
            if (isVerified3dsHeader(bytes, ext, fileSize)) {
                return DetectedFormat.STL
            }

            // TIFF image (little-endian and big-endian, including DNG/RAW variants)
            if ((b0 == 0x49 && b1 == 0x49 && (b2 == 0x2A || b2 == 0x55 || b2 == 0x52) && b3 == 0x00) ||
                (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && (b3 == 0x2A || b3 == 0x2B))
            ) {
                return DetectedFormat.TIFF
            }

            // Rich Text Format: {\rtf
            if (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII) == "{\\rtf") {
                return DetectedFormat.RTF
            }

            // GZIP stream: 1F 8B (handles .svgz and gzip-compressed .blend files)
            if (b0 == 0x1F && b1 == 0x8B) {
                return when (ext) {
                    "svgz" -> DetectedFormat.SVGZ
                    "blend" -> DetectedFormat.BLEND
                    else -> DetectedFormat.GZ
                }
            }

            // BZIP2 stream: BZh
            if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) {
                return DetectedFormat.BZ2
            }

            // XZ stream: FD 37 7A 58 5A 00
            if (bytes.size >= 6 && b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58) {
                return DetectedFormat.XZ
            }

            // ISO Base Media File Format (HEIC / HEIF / AVIF / MP4 / M4A)
            if (bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            ) {
                return classifyIsoBmffFtyp(bytes, ext)
            }

            // MP3 audio (ID3v2 or MPEG frame sync)
            if ((b0 == 0x49 && b1 == 0x44 && b2 == 0x33) ||
                (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && ext in setOf("mp3", "aac", "mp2"))
            ) {
                return DetectedFormat.MP3
            }

            // Binary STL verification by exact triangle byte size
            if (isVerifiedBinaryStl(bytes, ext, fileSize)) {
                return DetectedFormat.STL
            }

            // dBase III / IV / V / FoxPro (.dbf)
            if (isLikelyDbfHeader(bytes, ext, fileSize)) {
                return DetectedFormat.DBF
            }
        }

        // USTAR tar archive signature at offset 257
        if (bytes.size >= 262 &&
            bytes[257] == 'u'.code.toByte() &&
            bytes[258] == 's'.code.toByte() &&
            bytes[259] == 't'.code.toByte() &&
            bytes[260] == 'a'.code.toByte() &&
            bytes[261] == 'r'.code.toByte()
        ) {
            return DetectedFormat.TAR
        }

        // ISO-9660 / UDF volume descriptor probe at 0x8001 ("CD001", "BEA01", "NSR02", "NSR03")
        if (fd != null && (fileSize >= 0x8006L || ext == "iso")) {
            val isoBuf = ByteBuffer.allocateDirect(5)
            val r = try { Os.pread(fd, isoBuf, 0x8001L) } catch (_: Exception) { 0 }
            if (r == 5) {
                val sigArr = ByteArray(5)
                isoBuf.flip()
                isoBuf.get(sigArr)
                val vdIdent = String(sigArr, StandardCharsets.US_ASCII)
                if (vdIdent in setOf("CD001", "BEA01", "NSR02", "NSR03")) {
                    return DetectedFormat.ISO
                }
            }
        }

        val headText = decodeHeaderSampleAsText(bytes, min(bytes.size, 2048))
        val lowerHead = headText.lowercase(Locale.ROOT)
        val trimmedHead = lowerHead.trimStart()

        if (lowerHead.contains("multipart/related") || lowerHead.contains("content-type: message/rfc822") || lowerHead.startsWith("from: <saved by blink>") || ext in setOf("mhtml", "mht")) {
            return DetectedFormat.MHTML
        }
        if (lowerHead.contains("<svg")) return DetectedFormat.SVG
        if (lowerHead.contains("<fictionbook")) return DetectedFormat.FB2
        if (lowerHead.contains("<collada")) return DetectedFormat.STL
        if (trimmedHead.startsWith("#usda")) return DetectedFormat.USDZ
        if (trimmedHead.startsWith("ply\n") ||
            trimmedHead.startsWith("ply\r\n") ||
            (trimmedHead.startsWith("ply") && lowerHead.contains("end_header"))
        ) {
            return DetectedFormat.STL
        }
        if (trimmedHead.startsWith("off\n") ||
            trimmedHead.startsWith("off\r\n") ||
            trimmedHead.startsWith("off ") ||
            trimmedHead.startsWith("coff\n") ||
            trimmedHead.startsWith("coff\r\n") ||
            trimmedHead.startsWith("noff\n") ||
            trimmedHead.startsWith("noff\r\n")
        ) {
            return DetectedFormat.STL
        }
        if (trimmedHead.startsWith("solid") && (lowerHead.contains("facet") || lowerHead.contains("vertex") || ext == "stl")) {
            return DetectedFormat.STL
        }
        if (trimmedHead.startsWith("; fbx") || (lowerHead.contains("fbxheaderextension") && lowerHead.contains("objects:"))) {
            return DetectedFormat.STL
        }
        if (trimmedHead.startsWith("iso-10303-21;") || (lowerHead.contains("iso-10303-21;") && lowerHead.contains("header;"))) {
            return DetectedFormat.STL
        }
        if (isLikelyIgesHeader(headText)) {
            return DetectedFormat.STL
        }
        if (isLikelyAsciiDxf(headText)) {
            return DetectedFormat.DXF
        }
        if (isLikelyWavefrontObj(headText, ext)) {
            return DetectedFormat.OBJ
        }

        // glTF 2.0 JSON scene detection
        if (trimmedHead.startsWith("{") && lowerHead.contains("\"asset\"") &&
            (lowerHead.contains("\"meshes\"") || lowerHead.contains("\"scenes\"") ||
                    lowerHead.contains("\"nodes\"") || lowerHead.contains("\"accessors\"") ||
                    lowerHead.contains("\"buffers\"") || ext == "gltf")
        ) {
            return DetectedFormat.GLTF
        }

        // WordprocessingML 2003 / Flat OPC XML
        if (lowerHead.contains("<?mso-application progid=\"word.document\"?>") ||
            lowerHead.contains("<w:worddocument") ||
            (lowerHead.contains("<pkg:package") && lowerHead.contains("wordprocessingml"))
        ) {
            return DetectedFormat.DOCX
        }
        // SpreadsheetML 2003 / Flat OPC XML
        if (lowerHead.contains("<?mso-application progid=\"excel.sheet\"?>") ||
            (lowerHead.contains("<workbook") && lowerHead.contains("urn:schemas-microsoft-com:office:spreadsheet")) ||
            (lowerHead.contains("<pkg:package") && lowerHead.contains("spreadsheetml"))
        ) {
            return DetectedFormat.XLSX
        }
        // PowerPoint Flat OPC XML
        if (lowerHead.contains("<?mso-application progid=\"powerpoint.show\"?>") ||
            (lowerHead.contains("<pkg:package") && lowerHead.contains("presentationml"))
        ) {
            return DetectedFormat.PPTX
        }

        return when (ext) {
            "pdf" -> DetectedFormat.PDF
            "docx", "docm", "dotx", "dotm" -> DetectedFormat.DOCX
            "doc", "dot" -> DetectedFormat.DOC
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
            "xls", "xlt" -> DetectedFormat.XLS
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> DetectedFormat.PPTX
            "ppt", "pps", "pot" -> DetectedFormat.PPT
            "odt", "ott" -> DetectedFormat.ODT
            "ods", "ots" -> DetectedFormat.ODS
            "odp", "otp" -> DetectedFormat.ODP
            "rtf" -> DetectedFormat.RTF
            "epub" -> DetectedFormat.EPUB
            "fb2", "fbz" -> DetectedFormat.FB2
            "djvu", "djv" -> DetectedFormat.DJVU
            "ttf", "otf", "ttc", "woff", "woff2", "dfont" -> DetectedFormat.FONT
            "one" -> DetectedFormat.ONE
            "dwg" -> DetectedFormat.DWG
            "accdb" -> DetectedFormat.ACCDB
            "mdb" -> DetectedFormat.MDB
            "dbf" -> DetectedFormat.DBF
            "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson" -> DetectedFormat.PARQUET
            "mhtml", "mht" -> DetectedFormat.MHTML
            "azw3", "azw" -> DetectedFormat.AZW3
            "mobi", "prc" -> DetectedFormat.MOBI
            "kfx" -> DetectedFormat.KFX
            "max" -> DetectedFormat.MAX
            "rvt" -> DetectedFormat.RVT
            "indd" -> DetectedFormat.INDD
            "svg" -> DetectedFormat.SVG
            "svgz" -> DetectedFormat.SVGZ
            "gltf" -> DetectedFormat.GLTF
            "glb" -> DetectedFormat.GLB
            "blend" -> DetectedFormat.BLEND
            "usdz", "usd", "usda", "usdc" -> DetectedFormat.USDZ
            "stl", "ply", "off", "3ds", "fbx", "3mf", "dae", "zae", "step", "stp", "iges", "igs" -> DetectedFormat.STL
            "obj" -> DetectedFormat.OBJ
            "dxf" -> DetectedFormat.DXF
            "zip", "jar" -> DetectedFormat.ZIP
            "7z" -> DetectedFormat.SEVEN_ZIP
            "rar" -> DetectedFormat.RAR_V4
            "tar", "tgz" -> DetectedFormat.TAR
            "gz" -> DetectedFormat.GZ
            "bz2" -> DetectedFormat.BZ2
            "xz" -> DetectedFormat.XZ
            "iso" -> DetectedFormat.ISO
            "apk" -> DetectedFormat.APK
            "elf", "so" -> DetectedFormat.ELF
            "dex" -> DetectedFormat.DEX
            "db", "db3", "s3db", "sl3", "sqlite", "sqlite3" -> DetectedFormat.SQLITE
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp" -> DetectedFormat.MP4
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus" -> DetectedFormat.MP3
            "png" -> DetectedFormat.PNG
            "jpg", "jpeg", "jpe", "jfif" -> DetectedFormat.JPEG
            "webp" -> DetectedFormat.WEBP
            "gif" -> DetectedFormat.GIF
            "bmp", "ico", "cur", "wbmp" -> DetectedFormat.BMP
            "tif", "tiff", "dng", "cr2", "nef", "arw" -> DetectedFormat.TIFF
            "heic", "heif", "hif" -> DetectedFormat.HEIC
            "avif", "avifs" -> DetectedFormat.AVIF
            else -> DetectedFormat.PLAINTEXT
        }
    }

    /**
     * Inspects PalmDOC / MOBI header inside Record 0 of a PalmDB container to check Encryption Type.
     * Offset +12 (UInt16 BE) inside Record 0:
     *   0 = Unencrypted
     *   1 = Old Mobipocket Encryption (DRM)
     *   2 = Mobipocket / Kindle DRM Encryption
     */
    private fun inspectKindlePalmDb(
        fd: FileDescriptor?,
        headerBytes: ByteArray,
        ext: String
    ): DetectedFormat {
        val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val numRecords = if (headerBytes.size >= 78) (bb.getShort(76).toInt() and 0xFFFF) else 0
        if (numRecords > 0 && headerBytes.size >= 86) {
            val rec0Offset = bb.getInt(78).toLong() and 0xFFFFFFFFL
            if (rec0Offset >= 78L) {
                var encryptionType = 0
                if (rec0Offset + 16L <= headerBytes.size.toLong()) {
                    encryptionType = bb.getShort(rec0Offset.toInt() + 12).toInt() and 0xFFFF
                } else if (fd != null) {
                    val rec0Buf = ByteBuffer.allocateDirect(32).order(ByteOrder.BIG_ENDIAN)
                    val r = try { Os.pread(fd, rec0Buf, rec0Offset) } catch (_: Throwable) { 0 }
                    if (r >= 16) {
                        encryptionType = rec0Buf.getShort(12).toInt() and 0xFFFF
                    }
                }
                if (encryptionType != 0) {
                    return DetectedFormat.KINDLE_DRM
                }
            }
        }
        return if (ext == "azw3" || ext == "azw") DetectedFormat.AZW3 else DetectedFormat.MOBI
    }

    private fun decodeHeaderSampleAsText(bytes: ByteArray, length: Int): String {
        if (length <= 0) return ""
        if (length >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, (length - 2) and -2, StandardCharsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, (length - 2) and -2, StandardCharsets.UTF_16BE)
            }
        }
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }

    private fun classifyOle2Container(
        fd: FileDescriptor?,
        headerBytes: ByteArray,
        ext: String,
        fileSizeHint: Long
    ): DetectedFormat {
        when (ext) {
            "max" -> return DetectedFormat.MAX
            "rvt" -> return DetectedFormat.RVT
            "xls", "xlt" -> return DetectedFormat.XLS
            "ppt", "pps", "pot" -> return DetectedFormat.PPT
            "doc", "dot" -> return DetectedFormat.DOC
        }
        if (fd != null) {
            try {
                val probeLen = min(if (fileSizeHint > 0L) fileSizeHint else 256 * 1024L, 256 * 1024L).toInt()
                if (probeLen >= 512) {
                    val buf = ByteBuffer.allocateDirect(probeLen)
                    val r = Os.pread(fd, buf, 0L)
                    if (r >= 512) {
                        buf.flip()
                        val arr = ByteArray(r)
                        buf.get(arr)
                        val utf16Str = String(arr, StandardCharsets.UTF_16LE)
                        if (utf16Str.contains("EncryptedPackage") || utf16Str.contains("EncryptionInfo")) {
                            return when (ext) {
                                "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
                                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> DetectedFormat.PPTX
                                else -> DetectedFormat.DOCX
                            }
                        }
                        when {
                            utf16Str.contains("RevitPreview") || utf16Str.contains("BasicFileInfo") -> return DetectedFormat.RVT
                            utf16Str.contains("FileAssetMetaData") || utf16Str.contains("VideoPostQueue") || utf16Str.contains("Scene") -> return DetectedFormat.MAX
                            utf16Str.contains("Workbook") || utf16Str.contains("Book") -> return DetectedFormat.XLS
                            utf16Str.contains("PowerPoint Document") -> return DetectedFormat.PPT
                            utf16Str.contains("WordDocument") -> return DetectedFormat.DOC
                        }
                    }
                }
            } catch (_: Throwable) {}
        } else if (headerBytes.size >= 512) {
            val utf16Str = String(headerBytes, StandardCharsets.UTF_16LE)
            if (utf16Str.contains("EncryptedPackage") || utf16Str.contains("EncryptionInfo")) {
                return when (ext) {
                    "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
                    "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> DetectedFormat.PPTX
                    else -> DetectedFormat.DOCX
                }
            }
            when {
                utf16Str.contains("RevitPreview") || utf16Str.contains("BasicFileInfo") -> return DetectedFormat.RVT
                utf16Str.contains("FileAssetMetaData") || utf16Str.contains("VideoPostQueue") -> return DetectedFormat.MAX
                utf16Str.contains("Workbook") || utf16Str.contains("Book") -> return DetectedFormat.XLS
                utf16Str.contains("PowerPoint Document") -> return DetectedFormat.PPT
                utf16Str.contains("WordDocument") -> return DetectedFormat.DOC
            }
        }
        return when (ext) {
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> DetectedFormat.PPTX
            "docx", "docm", "dotx", "dotm" -> DetectedFormat.DOCX
            else -> DetectedFormat.DOC
        }
    }

    private data class ZipStructureInfo(
        val entryNames: Set<String>,
        val mimetypeValue: String?
    )

    private fun classifyZipBasedContainer(
        fd: FileDescriptor?,
        headerBytes: ByteArray,
        ext: String,
        fileSizeHint: Long
    ): DetectedFormat {
        // Empty ZIP archive (EOCD only, 22+ bytes)
        if (headerBytes.size >= 22 &&
            headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
            headerBytes[2] == 0x05.toByte() && headerBytes[3] == 0x06.toByte()
        ) {
            return DetectedFormat.ZIP
        }

        // Local File Header must be at least 30 bytes with a valid entry filename
        if (headerBytes.size < 30 || (fileSizeHint in 1L..29L)) {
            return DetectedFormat.CORRUPTED_CONTAINER
        }

        val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstNameLen = bb.getShort(26).toInt() and 0xFFFF
        if (firstNameLen == 0 || (fileSizeHint > 0L && 30L + firstNameLen > fileSizeHint)) {
            return DetectedFormat.CORRUPTED_CONTAINER
        }

        val headInfo = scanTopLevelLocalHeaders(headerBytes)
        val allEntries = LinkedHashSet<String>()
        allEntries.addAll(headInfo.entryNames)
        val mimetypePayload = headInfo.mimetypeValue

        // Authoritative top-level entry resolution via End of Central Directory (EOCD).
        val cdEntries = readTopLevelCentralDirectoryEntries(fd, headerBytes, fileSizeHint)
        if (cdEntries.isNotEmpty()) {
            allEntries.addAll(cdEntries)
        }

        detectFromStructuredZipEntries(allEntries, mimetypePayload, ext)?.let { return it }

        return when (ext) {
            "usdz" -> DetectedFormat.USDZ
            "3mf", "zae" -> DetectedFormat.STL
            "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> DetectedFormat.PPTX
            "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
            "docx", "docm", "dotx", "dotm" -> DetectedFormat.DOCX
            "epub" -> DetectedFormat.EPUB
            "fb2", "fbz" -> DetectedFormat.FB2
            "odt", "ott" -> DetectedFormat.ODT
            "ods", "ots" -> DetectedFormat.ODS
            "odp", "otp" -> DetectedFormat.ODP
            "apk" -> DetectedFormat.APK
            else -> DetectedFormat.ZIP
        }
    }

    /**
     * Walks top-level Local File Headers starting at offset 0.
     * Supports both stored and deflated `mimetype` entries for ODF/EPUB containers.
     */
    private fun scanTopLevelLocalHeaders(buf: ByteArray): ZipStructureInfo {
        val entries = LinkedHashSet<String>()
        var mimetypeValue: String? = null
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 30 <= buf.size) {
            if (bb.getInt(i) != 0x04034b50) break

            val flags = bb.getShort(i + 6).toInt() and 0xFFFF
            val method = bb.getShort(i + 8).toInt() and 0xFFFF
            val compSize = bb.getInt(i + 18).toLong() and 0xFFFFFFFFL
            val nameLen = bb.getShort(i + 26).toInt() and 0xFFFF
            val extraLen = bb.getShort(i + 28).toInt() and 0xFFFF
            val nameStart = i + 30
            if (nameLen !in 1..1024 || nameStart + nameLen > buf.size) break

            val name = String(buf, nameStart, nameLen, StandardCharsets.UTF_8)
                .replace('\\', '/')
                .trimStart('/')
                .lowercase(Locale.ROOT)
            entries.add(name)

            val dataStart = nameStart + nameLen + extraLen
            if (name == "mimetype" && dataStart < buf.size) {
                if (method == 0) {
                    val maxMimeBytes = min(buf.size - dataStart, if (compSize in 1..128) compSize.toInt() else 96)
                    if (maxMimeBytes > 0) {
                        mimetypeValue = String(buf, dataStart, maxMimeBytes, StandardCharsets.US_ASCII)
                            .trim()
                            .lowercase(Locale.ROOT)
                    }
                } else if (method == 8) {
                    val maxCompBytes = min(buf.size - dataStart, if (compSize in 1..256) compSize.toInt() else 128)
                    if (maxCompBytes > 0) {
                        mimetypeValue = inflateSmallAsciiPayload(buf, dataStart, maxCompBytes)
                    }
                }
            }

            val entryExt = name.substringAfterLast('.', "")
            if (entryExt in NESTED_CONTAINER_EXTENSIONS) {
                if (compSize > 0L && compSize <= (buf.size - dataStart).toLong()) {
                    i = dataStart + compSize.toInt()
                    continue
                } else {
                    break
                }
            }

            val hasDataDescriptor = (flags and 0x08) != 0
            if (compSize > 0L) {
                if (compSize > (buf.size - dataStart).toLong()) break
                i = dataStart + compSize.toInt()
            } else if (method == 0 && !hasDataDescriptor) {
                i = dataStart
            } else {
                var nextHeaderPos = -1
                var scanPos = dataStart
                while (scanPos + 30 <= buf.size) {
                    if (bb.getInt(scanPos) == 0x04034b50) {
                        val candidateNameLen = bb.getShort(scanPos + 26).toInt() and 0xFFFF
                        if (candidateNameLen in 1..512 && scanPos + 30 + candidateNameLen <= buf.size) {
                            nextHeaderPos = scanPos
                            break
                        }
                    }
                    scanPos++
                }
                if (nextHeaderPos > dataStart) {
                    i = nextHeaderPos
                } else {
                    break
                }
            }
        }
        return ZipStructureInfo(entries, mimetypeValue)
    }

    private fun inflateSmallAsciiPayload(buf: ByteArray, offset: Int, len: Int): String? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(buf, offset, len)
            val bos = ByteArrayOutputStream(128)
            val tmp = ByteArray(64)
            var total = 0
            while (!inflater.finished() && total < 128) {
                val r = inflater.inflate(tmp)
                if (r <= 0) break
                bos.write(tmp, 0, r)
                total += r
            }
            if (total > 0) {
                String(bos.toByteArray(), StandardCharsets.US_ASCII).trim().lowercase(Locale.ROOT)
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            try { inflater.end() } catch (_: Throwable) {}
        }
    }

    /**
     * Locates the outer container's End of Central Directory (EOCD) record at the end of the file
     * and walks only its Central Directory headers (`PK\x01\x02`).
     */
    private fun readTopLevelCentralDirectoryEntries(
        fd: FileDescriptor?,
        headerBytes: ByteArray,
        fileSizeHint: Long
    ): Set<String> {
        if (fd != null) {
            val actualSize = if (fileSizeHint > 0L) fileSizeHint else try {
                val statSize = Os.fstat(fd).st_size
                if (statSize > 0L) statSize else {
                    val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
                    val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
                    Os.lseek(fd, cur, OsConstants.SEEK_SET)
                    end
                }
            } catch (_: Throwable) { -1L }

            if (actualSize >= 22L) {
                val tailLen = min(actualSize, 65557L).toInt()
                val tailFileOffset = actualSize - tailLen
                val tailBuf = ByteBuffer.allocateDirect(tailLen)
                val r = try { Os.pread(fd, tailBuf, tailFileOffset) } catch (_: Throwable) { 0 }
                if (r >= 22) {
                    tailBuf.flip()
                    val tailBytes = ByteArray(r)
                    tailBuf.get(tailBytes)
                    val eocdPos = findEocdOffset(tailBytes)
                    if (eocdPos >= 0) {
                        val tailBb = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val cdSize = tailBb.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
                        val cdOffset = tailBb.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
                        if (cdOffset >= 0L && cdSize > 0L && cdOffset + cdSize <= actualSize) {
                            if (cdOffset >= tailFileOffset && cdOffset + cdSize <= tailFileOffset + r) {
                                val relStart = (cdOffset - tailFileOffset).toInt()
                                val relEnd = (relStart + cdSize.toInt()).coerceAtMost(r)
                                return parseCentralDirectoryBuffer(tailBytes, relStart, relEnd)
                            } else if (cdSize <= 8 * 1024 * 1024L) {
                                val readLen = min(cdSize, 512 * 1024L).toInt()
                                val cdBuf = ByteBuffer.allocateDirect(readLen)
                                val cdRead = try { Os.pread(fd, cdBuf, cdOffset) } catch (_: Throwable) { 0 }
                                if (cdRead >= 46) {
                                    cdBuf.flip()
                                    val cdBytes = ByteArray(cdRead)
                                    cdBuf.get(cdBytes)
                                    return parseCentralDirectoryBuffer(cdBytes, 0, cdRead)
                                }
                            }
                        }
                    }
                }
            }
        } else if (headerBytes.size >= 22) {
            val eocdPos = findEocdOffset(headerBytes)
            if (eocdPos >= 0) {
                val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val cdSize = bb.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
                val cdOffset = bb.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
                if (cdOffset >= 0L && cdSize > 0L && cdOffset + cdSize <= headerBytes.size.toLong()) {
                    return parseCentralDirectoryBuffer(
                        headerBytes,
                        cdOffset.toInt(),
                        (cdOffset + cdSize).toInt()
                    )
                }
            }
        }
        return emptySet()
    }

    private fun findEocdOffset(buf: ByteArray): Int {
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in buf.size - 22 downTo 0) {
            if (bb.getInt(i) == 0x06054b50) {
                val commentLen = bb.getShort(i + 20).toInt() and 0xFFFF
                if (i + 22 + commentLen <= buf.size) {
                    return i
                }
            }
        }
        return -1
    }

    private fun parseCentralDirectoryBuffer(buf: ByteArray, startOffset: Int, endLimit: Int): Set<String> {
        val entries = LinkedHashSet<String>()
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var pos = startOffset
        while (pos + 46 <= endLimit) {
            if (bb.getInt(pos) != 0x02014b50) break
            val nameLen = bb.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = bb.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = bb.getShort(pos + 32).toInt() and 0xFFFF
            val nameStart = pos + 46
            if (nameStart + nameLen > endLimit) break
            if (nameLen in 1..1024) {
                val name = String(buf, nameStart, nameLen, StandardCharsets.UTF_8)
                    .replace('\\', '/')
                    .trimStart('/')
                    .lowercase(Locale.ROOT)
                entries.add(name)
            }
            pos = nameStart + nameLen + extraLen + commentLen
        }
        return entries
    }

    private fun detectFromStructuredZipEntries(
        entries: Set<String>,
        mimetypeValue: String?,
        ext: String
    ): DetectedFormat? {
        if (mimetypeValue != null) {
            when {
                mimetypeValue.contains("application/epub+zip") -> return DetectedFormat.EPUB
                mimetypeValue.contains("oasis.opendocument.text") -> return DetectedFormat.ODT
                mimetypeValue.contains("oasis.opendocument.spreadsheet") -> return DetectedFormat.ODS
                mimetypeValue.contains("oasis.opendocument.presentation") -> return DetectedFormat.ODP
            }
        }

        // USDZ 3D scene package (contains .usdc, .usda, or .usd root layer)
        if (ext == "usdz" || entries.any { it.endsWith(".usdc") || it.endsWith(".usda") || it.endsWith(".usd") }) {
            return DetectedFormat.USDZ
        }

        if (ext == "3mf" || ext == "zae" ||
            entries.any { it == "3d/3dmodel.model" || (it.startsWith("3d/") && it.endsWith(".model")) } ||
            (entries.any { it.endsWith(".dae") } && (ext == "zae" || "manifest.xml" in entries))
        ) {
            return DetectedFormat.STL
        }

        if (entries.any { it == "ppt/presentation.xml" || it.startsWith("ppt/slides/") || it.startsWith("ppt/") }) {
            return DetectedFormat.PPTX
        }
        if (entries.any {
                it == "xl/workbook.xml" ||
                        it == "xl/workbook.bin" ||
                        it.startsWith("xl/worksheets/") ||
                        it.startsWith("xl/")
            }
        ) {
            return DetectedFormat.XLSX
        }
        if (entries.any { it == "word/document.xml" || it.startsWith("word/") }) {
            return DetectedFormat.DOCX
        }

        if (entries.any { it == "meta-inf/container.xml" || it.endsWith(".opf") }) {
            return DetectedFormat.EPUB
        }

        if (entries.any { it.endsWith(".fb2") } && (ext in setOf("fb2", "fbz") || entries.size == 1)) {
            return DetectedFormat.FB2
        }

        if ("content.xml" in entries && ("styles.xml" in entries || "meta.xml" in entries || "meta-inf/manifest.xml" in entries || "mimetype" in entries)) {
            return when (ext) {
                "ods", "ots" -> DetectedFormat.ODS
                "odp", "otp" -> DetectedFormat.ODP
                else -> DetectedFormat.ODT
            }
        }

        if ("[content_types].xml" in entries) {
            return when (ext) {
                "3mf" -> DetectedFormat.STL
                "xlsx", "xlsm", "xlsb", "xltx", "xltm" -> DetectedFormat.XLSX
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "potx" -> DetectedFormat.PPTX
                "docx", "docm", "dotx", "dotm" -> DetectedFormat.DOCX
                else -> null
            }
        }

        if ("androidmanifest.xml" in entries && "classes.dex" in entries) {
            return DetectedFormat.APK
        }
        return null
    }
}
