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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileEngineProvider
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.impl.Gles30MeshRenderer
import com.omnireader.core.engine.impl.Gles30OffscreenContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class CadPreadInputStream(
    private val fd: FileDescriptor,
    private val totalSize: Long
) : InputStream() {
    private var pos = 0L
    private val singleByte = ByteArray(1)
    private val reusableDirect = ByteBuffer.allocateDirect(32 * 1024)

    override fun read(): Int {
        val r = read(singleByte, 0, 1)
        return if (r == 1) (singleByte[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (totalSize > 0L && pos >= totalSize) return -1
        val remaining = if (totalSize > 0L) (totalSize - pos) else len.toLong()
        val toRead = minOf(len.toLong(), remaining, reusableDirect.capacity().toLong()).toInt()
        if (toRead <= 0) return -1
        reusableDirect.clear()
        reusableDirect.limit(toRead)
        val r = try {
            Os.pread(fd, reusableDirect, pos)
        } catch (e: Exception) {
            throw IOException("pread error at offset $pos: ${e.message}", e)
        }
        if (r <= 0) return -1
        reusableDirect.flip()
        reusableDirect.get(b, off, r)
        pos += r
        return r
    }
}

class Cad3dEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf(
                "stl", "obj", "dxf", "dwg", "ply", "off", "3ds",
                "gltf", "glb", "blend", "usdz", "usd", "usda", "usdc", "fbx",
                "3mf", "dae", "step", "stp", "iges", "igs"
            )
        ) return true
        if (signature.size >= 6 &&
            signature[0] == 'A'.code.toByte() &&
            signature[1] == 'C'.code.toByte() &&
            signature[2] == '1'.code.toByte() &&
            signature[3] == '0'.code.toByte()
        ) {
            return true
        }
        if (signature.size >= 4 &&
            signature[0] == 'g'.code.toByte() &&
            signature[1] == 'l'.code.toByte() &&
            signature[2] == 'T'.code.toByte() &&
            signature[3] == 'F'.code.toByte()
        ) {
            return true
        }
        if (signature.size >= 7 &&
            String(signature, 0, 7, StandardCharsets.US_ASCII) == "BLENDER"
        ) {
            return true
        }
        if (signature.size >= 8 &&
            String(signature, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC"
        ) {
            return true
        }
        if (signature.size >= 18 &&
            String(signature, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary"
        ) {
            return true
        }
        return false
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        val pfd = ParcelFileDescriptor.dup(fd)
        return Stl3dMeshSession(pfd, "unknown")
    }

    fun createSessionForFormat(fd: FileDescriptor, extension: String): DocumentSession {
        val pfd = ParcelFileDescriptor.dup(fd)
        return Stl3dMeshSession(pfd, extension)
    }
}

class Stl3dMeshSession(
    private val pfd: ParcelFileDescriptor,
    private val originalExtension: String
) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var isGeometryTruncated = false

    override val is3dContent: Boolean = true
    override val pageCount: Int = 1

    data class CadTextLabel3D(
        val text: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val height: Float,
        val rotationDeg: Float,
        val color: Int = 0
    )

    internal data class DxfPoint3D(val x: Float, val y: Float, val z: Float)

    private data class BlendBlock(
        val code: String,
        val dataOffset: Int,
        val len: Int,
        val sdnaIndex: Int,
        val count: Int
    )

    private var rawVertices = FloatArray(30_000)
    private var polyColors = IntArray(3_500)
    private var vertexCount = 0
    private val textLabels = mutableListOf<CadTextLabel3D>()
    private var dxfEmittedOpsCount = 0
    private var dwgPreviewBitmap: Bitmap? = null

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private var modelType = "Unknown"

    private val maxPolygonsLimit: Int = computeDynamicPolygonLimit()
    private val maxVertexFloats: Int = maxPolygonsLimit * 9

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.SANS_SERIF
    }
    private val textLabelPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#89B4FA")
        typeface = android.graphics.Typeface.SANS_SERIF
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    companion object {
        private const val MAX_INSERT_DEPTH = 8
        private const val MAX_DXF_EMIT_OPS = 500_000
        private const val MAX_TEXT_LABELS_LIMIT = 4_000
        private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
        private const val LANG_ORDINAL_SHIFT = 24

        private val DARK_THEME_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
        private val LIGHT_THEME_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)
        private val DARK_THEME_HUD_TEXT_COLOR = Color.WHITE
        private val LIGHT_THEME_HUD_TEXT_COLOR = Color.rgb(0x1A, 0x1C, 0x24)
        private val DARK_THEME_LABEL_COLOR = Color.rgb(0x89, 0xB4, 0xFA)
        private val LIGHT_THEME_LABEL_COLOR = Color.rgb(0x19, 0x76, 0xD2)

        private fun computeDynamicPolygonLimit(): Int {
            val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(128L * 1024L * 1024L)
            val budgetBytes = (maxHeapBytes * 35L) / 100L
            return (budgetBytes / 160L).coerceIn(750_000L, 4_000_000L).toInt()
        }

        private val DWG_IMAGE_SENTINEL = byteArrayOf(
            0x1F.toByte(), 0x25.toByte(), 0x6D.toByte(), 0x07.toByte(),
            0xD4.toByte(), 0x36.toByte(), 0x28.toByte(), 0x28.toByte(),
            0x9D.toByte(), 0x57.toByte(), 0xCA.toByte(), 0x3F.toByte(),
            0x9D.toByte(), 0x44.toByte(), 0x10.toByte(), 0x2B.toByte()
        )

        /**
         * Maps standard AutoCAD Color Index (ACI 1..255) to ARGB color.
         */
        internal fun aciToColor(aci: Int): Int {
            return when (abs(aci)) {
                1 -> Color.rgb(255, 59, 48)    // Red
                2 -> Color.rgb(255, 204, 0)    // Yellow
                3 -> Color.rgb(40, 205, 65)    // Green
                4 -> Color.rgb(0, 199, 190)   // Cyan
                5 -> Color.rgb(10, 132, 255)   // Blue
                6 -> Color.rgb(191, 90, 242)   // Magenta
                7 -> 0                         // Adaptive White/Black (handled by theme)
                8 -> Color.rgb(142, 142, 147)  // Dark Gray
                9 -> Color.rgb(199, 199, 204)  // Light Gray
                in 10..249 -> {
                    val hue = (((abs(aci) - 10) / 10) * 15f) % 360f
                    Color.HSVToColor(floatArrayOf(hue, 0.80f, 0.92f))
                }
                in 250..255 -> {
                    val g = 100 + (abs(aci) - 250) * 28
                    Color.rgb(g.coerceIn(0, 255), g.coerceIn(0, 255), g.coerceIn(0, 255))
                }
                else -> 0
            }
        }

        private fun formatLocalizedHudStatus(
            langOrdinal: Int,
            modelType: String,
            polyCount: Int,
            isTruncated: Boolean,
            maxLimit: Int
        ): String {
            val localizedModelType = when (modelType) {
                "Binary STL" -> when (langOrdinal) {
                    1 -> "Бинарный STL"
                    2 -> "Binäres STL"
                    3 -> "STL binario"
                    4 -> "STL binaire"
                    5 -> "Binarny STL"
                    6 -> "Бінарний STL"
                    7 -> "二进制 STL"
                    else -> "Binary STL"
                }
                else -> modelType
            }
            val polygonsWord = when (langOrdinal) {
                1 -> "Полигоны"
                2 -> "Polygone"
                3 -> "Polígonos"
                4 -> "Polygones"
                5 -> "Wielokąty"
                6 -> "Полігони"
                7 -> "多边形"
                else -> "Polygons"
            }
            val maxWord = when (langOrdinal) {
                1 -> "Макс."
                2 -> "Max."
                3 -> "Máx."
                4 -> "Max."
                5 -> "Maks."
                6 -> "Макс."
                7 -> "最大"
                else -> "Max"
            }
            val limitBadge = if (isTruncated) " | ⚠ $maxWord $maxLimit" else ""
            return "3D/CAD: $localizedModelType | $polygonsWord: $polyCount$limitBadge"
        }
    }

    private fun resolveDescriptorFileSize(): Long {
        val statSize = try { Os.fstat(pfd.fileDescriptor).st_size } catch (_: Throwable) { -1L }
        if (statSize > 0L) return statSize
        return try {
            val cur = Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            val end = Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_END)
            Os.lseek(pfd.fileDescriptor, cur, OsConstants.SEEK_SET)
            if (end > 0L) end else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readDescriptorSliceAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0L) return ByteArray(0)
        val buf = ByteBuffer.allocateDirect(length)
        var total = 0
        while (buf.hasRemaining()) {
            val r = try { Os.pread(pfd.fileDescriptor, buf, offset + total) } catch (_: Throwable) { -1 }
            if (r <= 0) break
            total += r
        }
        if (total <= 0) return ByteArray(0)
        buf.flip()
        val out = ByteArray(total)
        buf.get(out)
        return out
    }

    private fun readDescriptorBytes(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val exactSize = resolveDescriptorFileSize()
        val cap = minOf(if (exactSize > 0L) exactSize else maxBytes.toLong(), maxBytes.toLong()).toInt()
        return readDescriptorSliceAt(0L, cap)
    }

    init {
        try {
            val header = readDescriptorSliceAt(0L, 1024)
            val readBytes = header.size
            val exactFileSize = resolveDescriptorFileSize()
            val extLower = originalExtension.lowercase(Locale.ROOT).trim()

            if (readBytes >= 4) {
                val rawHeaderAscii = String(header, 0, minOf(960, readBytes), StandardCharsets.UTF_8)
                val headerStr = rawHeaderAscii.trim().lowercase(Locale.ROOT)

                val isDwgMagic = readBytes >= 6 &&
                        header[0] == 'A'.code.toByte() &&
                        header[1] == 'C'.code.toByte() &&
                        header[2] == '1'.code.toByte() &&
                        header[3] == '0'.code.toByte()
                val isGlbMagic = readBytes >= 4 &&
                        header[0] == 'g'.code.toByte() &&
                        header[1] == 'l'.code.toByte() &&
                        header[2] == 'T'.code.toByte() &&
                        header[3] == 'F'.code.toByte()
                val isBlendMagic = (readBytes >= 7 && String(header, 0, 7, StandardCharsets.US_ASCII) == "BLENDER") ||
                        extLower == "blend"
                val isUsdcMagic = readBytes >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC"
                val isUsdaMagic = headerStr.startsWith("#usda")
                val isZipMagic = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                val isGltfJson = (headerStr.startsWith("{") && headerStr.contains("\"asset\"") &&
                        (headerStr.contains("\"meshes\"") || headerStr.contains("\"scenes\"") ||
                                headerStr.contains("\"nodes\"") || headerStr.contains("\"buffers\""))) ||
                        extLower == "gltf"
                val is3dsMagic = readBytes >= 6 &&
                        (header[0].toInt() and 0xFF) == 0x4D &&
                        (header[1].toInt() and 0xFF) == 0x4D &&
                        (extLower == "3ds" || (exactFileSize >= 16L && (ByteBuffer.wrap(header, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) == exactFileSize))
                val isFbxMagic = (readBytes >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary") ||
                        headerStr.startsWith("; fbx") ||
                        (headerStr.contains("fbxheaderextension") && headerStr.contains("objects:")) ||
                        extLower == "fbx"
                val isColladaMagic = headerStr.contains("<collada") || extLower == "dae"
                val is3mfMagic = extLower == "3mf"
                val isStepMagic = headerStr.startsWith("iso-10303-21;") ||
                        (headerStr.contains("iso-10303-21;") && headerStr.contains("header;")) ||
                        extLower in setOf("step", "stp")
                val isIgesMagic = extLower in setOf("iges", "igs")

                val isVerifiedBinaryStlSize = if (readBytes >= 84 && exactFileSize >= 134L) {
                    val triCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val expected = 84L + triCount * 50L
                    triCount in 1L..25_000_000L && (exactFileSize in expected..(expected + 64L))
                } else false

                val hasAsciiStlKeywords = headerStr.startsWith("solid") &&
                        (headerStr.contains("facet") || headerStr.contains("vertex"))

                when {
                    isBlendMagic -> {
                        modelType = "Blender 3D"
                        parseBlendCleanRoom()
                    }
                    is3mfMagic -> {
                        modelType = "3MF 3D Model"
                        parse3mfCleanRoom()
                    }
                    isUsdcMagic || isUsdaMagic || extLower in setOf("usdz", "usd", "usda", "usdc") ||
                            (isZipMagic && extLower in setOf("usdz", "usd")) -> {
                        modelType = "USDZ / USD 3D"
                        parseUsdOrUsdzCleanRoom()
                    }
                    isZipMagic -> {
                        // Inspect ZIP container: could be 3MF or USDZ opened without extension
                        parse3mfCleanRoom()
                        if (vertexCount == 0) {
                            parseUsdOrUsdzCleanRoom()
                        }
                    }
                    isGlbMagic || isGltfJson || extLower in setOf("glb", "gltf") -> {
                        modelType = if (isGlbMagic || extLower == "glb") "GLB (glTF 2.0)" else "glTF 2.0"
                        parseGltfOrGlbCleanRoom()
                    }
                    isFbxMagic -> {
                        modelType = "FBX 3D"
                        parseFbxCleanRoom()
                    }
                    isColladaMagic -> {
                        modelType = "COLLADA (.dae)"
                        parseColladaDaeCleanRoom()
                    }
                    isStepMagic -> {
                        modelType = "STEP (ISO 10303-21)"
                        openSafeStream { parseStepCleanRoom(it) }
                    }
                    isIgesMagic -> {
                        modelType = "IGES CAD"
                        openSafeStream { parseIgesCleanRoom(it) }
                    }
                    isDwgMagic || extLower == "dwg" -> {
                        modelType = "DWG"
                        parseDwgCleanRoom()
                    }
                    is3dsMagic -> {
                        modelType = "3DS"
                        parse3dsCleanRoom(exactFileSize)
                    }
                    headerStr.startsWith("ply") || extLower == "ply" -> {
                        modelType = "PLY"
                        parsePlyCleanRoom()
                    }
                    headerStr.startsWith("off") || headerStr.startsWith("coff") || headerStr.startsWith("noff") || extLower == "off" -> {
                        modelType = "OFF"
                        openSafeStream { parseOff(it) }
                    }
                    extLower == "dxf" || (headerStr.contains("section") && headerStr.contains("entities")) ||
                            (readBytes >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "AutoCAD Binary DXF") -> {
                        modelType = "DXF"
                        openSafeStream { parseDxf(it) }
                    }
                    extLower == "obj" || headerStr.startsWith("v ") || headerStr.startsWith("mtllib") || headerStr.contains("\nv ") -> {
                        modelType = "OBJ"
                        openSafeStream { parseObj(it) }
                    }
                    isVerifiedBinaryStlSize -> {
                        modelType = "Binary STL"
                        parseBinaryStlPread(exactFileSize)
                    }
                    hasAsciiStlKeywords -> {
                        modelType = "ASCII STL"
                        openSafeStream { parseAsciiStl(it) }
                        if (vertexCount == 0) {
                            modelType = "Binary STL"
                            parseBinaryStlPread(exactFileSize)
                        }
                    }
                    else -> {
                        modelType = "Binary STL"
                        parseBinaryStlPread(exactFileSize)
                        if (vertexCount == 0 && headerStr.startsWith("solid")) {
                            modelType = "ASCII STL"
                            openSafeStream { parseAsciiStl(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка разбора 3D/CAD модели: ${e.localizedMessage}"
        }

        if (vertexCount == 0 && textLabels.isEmpty() && dwgPreviewBitmap == null && lastErrorMsg == null) {
            lastErrorMsg = "Модель $modelType пуста, повреждена или не содержит поддерживаемой геометрии."
        }
    }

    override fun getLastError(): String? = lastErrorMsg

    override fun getPageText(pageIndex: Int): String {
        if (textLabels.isEmpty()) return modelType
        return textLabels.joinToString("\n") { it.text }
    }

    fun getPolygonCount(): Int = vertexCount / 9
    fun getTextLabels(): List<CadTextLabel3D> = textLabels.toList()
    fun isTruncatedByLimit(): Boolean = isGeometryTruncated

    private inline fun openSafeStream(block: (InputStream) -> Unit) {
        val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 500_000_000L }
        BufferedInputStream(CadPreadInputStream(pfd.fileDescriptor, fileSize), 64 * 1024).use(block)
    }

    /**
     * Clean-Room parser for Stanford Polygon `.ply` (supports both ASCII and binary_little_endian / binary_big_endian,
     * arbitrary property types, and variable face list count/index integer types).
     */
    private fun parsePlyCleanRoom() {
        val headBytes = readDescriptorSliceAt(0L, 32768)
        if (headBytes.size < 16) return

        val headerAscii = String(headBytes, 0, headBytes.size, StandardCharsets.US_ASCII)
        val endHeaderMatch = Regex("end_header\\r?\\n").find(headerAscii)
        if (endHeaderMatch == null) {
            openSafeStream { parsePlyAsciiStream(it) }
            return
        }

        val headerLines = headerAscii.substring(0, endHeaderMatch.range.first).lines()
        val bodyOffset = (endHeaderMatch.range.last + 1).toLong()

        var format = "ascii"
        var numVerts = 0
        var numFaces = 0
        var currentElement = ""
        var vertexByteStride = 0
        var xPropOffset = -1; var yPropOffset = -1; var zPropOffset = -1
        var xPropType = "float"; var yPropType = "float"; var zPropType = "float"
        var rPropOffset = -1; var gPropOffset = -1; var bPropOffset = -1
        var rPropType = "uchar"; var gPropType = "uchar"; var bPropType = "uchar"
        var faceCountBytes = 1
        var faceIndexBytes = 4

        fun typeByteSize(t: String): Int = when (t.lowercase(Locale.ROOT)) {
            "char", "int8", "uchar", "uint8" -> 1
            "short", "int16", "ushort", "uint16" -> 2
            "int", "int32", "uint", "uint32", "float", "float32" -> 4
            "double", "float64" -> 8
            else -> 4
        }

        for (rawLine in headerLines) {
            val trimmed = rawLine.trim()
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            when (parts[0].lowercase(Locale.ROOT)) {
                "format" -> if (parts.size >= 2) format = parts[1].lowercase(Locale.ROOT)
                "element" -> if (parts.size >= 3) {
                    currentElement = parts[1].lowercase(Locale.ROOT)
                    val cnt = parts[2].toIntOrNull()?.coerceIn(0, 5_000_000) ?: 0
                    if (currentElement == "vertex") numVerts = cnt
                    else if (currentElement == "face") numFaces = cnt
                }
                "property" -> {
                    if (currentElement == "vertex" && parts.size >= 3 && !parts[1].equals("list", true)) {
                        val pType = parts[1].lowercase(Locale.ROOT)
                        val pName = parts[2].lowercase(Locale.ROOT)
                        when (pName) {
                            "x" -> { xPropOffset = vertexByteStride; xPropType = pType }
                            "y" -> { yPropOffset = vertexByteStride; yPropType = pType }
                            "z" -> { zPropOffset = vertexByteStride; zPropType = pType }
                            "red", "r", "diffuse_red" -> { rPropOffset = vertexByteStride; rPropType = pType }
                            "green", "g", "diffuse_green" -> { gPropOffset = vertexByteStride; gPropType = pType }
                            "blue", "b", "diffuse_blue" -> { bPropOffset = vertexByteStride; bPropType = pType }
                        }
                        vertexByteStride += typeByteSize(pType)
                    } else if (currentElement == "face" && parts.size >= 5 && parts[1].equals("list", true)) {
                        faceCountBytes = typeByteSize(parts[2])
                        faceIndexBytes = typeByteSize(parts[3])
                    }
                }
            }
        }

        if (format == "ascii") {
            openSafeStream { parsePlyAsciiStream(it) }
            return
        }

        val fileSize = resolveDescriptorFileSize()
        val order = if (format.contains("big_endian")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val stride = vertexByteStride.coerceAtLeast(12)
        val availBytes = if (fileSize > bodyOffset) (fileSize - bodyOffset) else (64L * 1024L * 1024L)
        val maxVerts = (availBytes / stride).coerceAtMost(2_000_000L).toInt()
        val safeVerts = numVerts.coerceIn(0, maxVerts)
        if (safeVerts <= 0) return

        val xOff = if (xPropOffset >= 0) xPropOffset else 0
        val yOff = if (yPropOffset >= 0) yPropOffset else 4
        val zOff = if (zPropOffset >= 0) zPropOffset else 8
        val hasVertexColor = rPropOffset >= 0 && gPropOffset >= 0 && bPropOffset >= 0

        val vertCoordinates = FloatArray(safeVerts * 3)
        val vertColors = if (hasVertexColor) IntArray(safeVerts) else null
        val vertChunkVerts = 8192
        val vertChunkBuf = ByteBuffer.allocateDirect(vertChunkVerts * stride).order(order)

        fun readCoord(bb: ByteBuffer, pos: Int, type: String): Float = when (type) {
            "double", "float64" -> bb.getDouble(pos).toFloat()
            "int", "int32", "uint", "uint32" -> bb.getInt(pos).toFloat()
            "short", "int16" -> bb.getShort(pos).toFloat()
            "ushort", "uint16" -> (bb.getShort(pos).toInt() and 0xFFFF).toFloat()
            "char", "int8" -> bb.get(pos).toFloat()
            "uchar", "uint8" -> (bb.get(pos).toInt() and 0xFF).toFloat()
            else -> bb.getFloat(pos)
        }

        fun readColorChannel(bb: ByteBuffer, pos: Int, type: String): Int = when (type) {
            "float", "float32" -> (bb.getFloat(pos).coerceIn(0f, 1f) * 255f).toInt()
            "double", "float64" -> (bb.getDouble(pos).toFloat().coerceIn(0f, 1f) * 255f).toInt()
            "ushort", "uint16" -> ((bb.getShort(pos).toInt() and 0xFFFF) ushr 8).coerceIn(0, 255)
            else -> bb.get(pos).toInt() and 0xFF
        }

        var vRead = 0
        while (vRead < safeVerts) {
            val batch = minOf(vertChunkVerts, safeVerts - vRead)
            val byteCount = batch * stride
            vertChunkBuf.clear()
            vertChunkBuf.limit(byteCount)
            val got = try { Os.pread(pfd.fileDescriptor, vertChunkBuf, bodyOffset + vRead.toLong() * stride) } catch (_: Throwable) { -1 }
            if (got < stride) break
            vertChunkBuf.flip()
            val actualBatch = got / stride
            for (i in 0 until actualBatch) {
                val base = i * stride
                val vIdx = vRead + i
                val dst = vIdx * 3
                vertCoordinates[dst] = readCoord(vertChunkBuf, base + xOff, xPropType)
                vertCoordinates[dst + 1] = readCoord(vertChunkBuf, base + yOff, yPropType)
                vertCoordinates[dst + 2] = readCoord(vertChunkBuf, base + zOff, zPropType)
                if (vertColors != null) {
                    val r = readColorChannel(vertChunkBuf, base + rPropOffset, rPropType)
                    val g = readColorChannel(vertChunkBuf, base + gPropOffset, gPropType)
                    val b = readColorChannel(vertChunkBuf, base + bPropOffset, bPropType)
                    vertColors[vIdx] = Color.rgb(maxOf(r, 16), maxOf(g, 16), maxOf(b, 18))
                }
            }
            vRead += actualBatch
        }

        if (numFaces <= 0) {
            val step = maxOf(1, vRead / maxPolygonsLimit)
            var i = 0
            while (i + 2 < vRead && !isGeometryTruncated) {
                val b0 = i * 3
                val col = vertColors?.get(i) ?: 0
                addTriangle(
                    vertCoordinates[b0], vertCoordinates[b0 + 1], vertCoordinates[b0 + 2],
                    vertCoordinates[b0] + 0.002f, vertCoordinates[b0 + 1], vertCoordinates[b0 + 2],
                    vertCoordinates[b0], vertCoordinates[b0 + 1] + 0.002f, vertCoordinates[b0 + 2],
                    col
                )
                i += step
            }
            return
        }

        val facesStartOffset = bodyOffset + safeVerts.toLong() * stride
        val maxFaceBytes = minOf(if (fileSize > facesStartOffset) (fileSize - facesStartOffset) else 48L * 1024L * 1024L, 64L * 1024L * 1024L).toInt()
        if (maxFaceBytes <= 4) return
        val faceBytes = readDescriptorSliceAt(facesStartOffset, maxFaceBytes)
        val fbb = ByteBuffer.wrap(faceBytes).order(order)

        fun readIntAt(pos: Int, width: Int): Int = when (width) {
            1 -> faceBytes[pos].toInt() and 0xFF
            2 -> fbb.getShort(pos).toInt() and 0xFFFF
            else -> fbb.getInt(pos)
        }

        val needsPlyDecimation = numFaces > maxPolygonsLimit
        if (needsPlyDecimation) isGeometryTruncated = true
        var plyAccumulator = 0L

        var pos = 0
        for (f in 0 until numFaces) {
            if (pos + faceCountBytes > faceBytes.size) break
            val count = readIntAt(pos, faceCountBytes)
            pos += faceCountBytes
            val listBytes = count * faceIndexBytes
            if (count < 3 || count > 256 || pos + listBytes > faceBytes.size) break

            var shouldEmitFace = true
            if (needsPlyDecimation) {
                plyAccumulator += maxPolygonsLimit.toLong()
                if (plyAccumulator >= numFaces.toLong()) {
                    plyAccumulator -= numFaces.toLong()
                } else {
                    shouldEmitFace = false
                }
            }

            if (shouldEmitFace && vertexCount + 9 <= maxVertexFloats) {
                val idx0 = readIntAt(pos, faceIndexBytes)
                if (idx0 in 0 until vRead) {
                    val b0 = idx0 * 3
                    val x0 = vertCoordinates[b0]; val y0 = vertCoordinates[b0 + 1]; val z0 = vertCoordinates[b0 + 2]
                    val c0 = vertColors?.get(idx0) ?: 0
                    for (k in 1 until count - 1) {
                        val idx1 = readIntAt(pos + k * faceIndexBytes, faceIndexBytes)
                        val idx2 = readIntAt(pos + (k + 1) * faceIndexBytes, faceIndexBytes)
                        if (idx1 in 0 until vRead && idx2 in 0 until vRead) {
                            val b1 = idx1 * 3
                            val b2 = idx2 * 3
                            addTriangle(
                                x0, y0, z0,
                                vertCoordinates[b1], vertCoordinates[b1 + 1], vertCoordinates[b1 + 2],
                                vertCoordinates[b2], vertCoordinates[b2 + 1], vertCoordinates[b2 + 2],
                                c0
                            )
                        }
                    }
                }
            }
            pos += listBytes
        }
    }

    private fun parsePlyAsciiStream(stream: InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertColors = IntArray(10_000)
        var vertReadCount = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var numVerts = 0
            var numFaces = 0
            var inHeader = true
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("comment", true)) continue
                if (inHeader) {
                    when {
                        trimmed.startsWith("element vertex", true) -> {
                            numVerts = trimmed.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        }
                        trimmed.startsWith("element face", true) -> {
                            numFaces = trimmed.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        }
                        trimmed.equals("end_header", true) -> {
                            inHeader = false
                            if (numVerts in 1..3_000_000) {
                                vertCoords = FloatArray(numVerts * 3)
                                vertColors = IntArray(numVerts)
                            }
                        }
                    }
                } else if (vertReadCount < numVerts) {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val vx = parts[0].toFloatOrNull()
                        val vy = parts[1].toFloatOrNull()
                        val vz = parts[2].toFloatOrNull()
                        if (vx != null && vy != null && vz != null) {
                            if ((vertReadCount + 1) * 3 > vertCoords.size) {
                                vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                                vertColors = vertColors.copyOf(vertColors.size * 2)
                            }
                            val base = vertReadCount * 3
                            vertCoords[base] = vx
                            vertCoords[base + 1] = vy
                            vertCoords[base + 2] = vz
                            if (parts.size >= 6) {
                                val rRaw = parts[3].toFloatOrNull()
                                val gRaw = parts[4].toFloatOrNull()
                                val bRaw = parts[5].toFloatOrNull()
                                if (rRaw != null && gRaw != null && bRaw != null) {
                                    val isNormalized = rRaw <= 1.0f && gRaw <= 1.0f && bRaw <= 1.0f &&
                                            (parts[3].contains('.') || parts[4].contains('.') || parts[5].contains('.'))
                                    val r = (if (isNormalized) rRaw * 255f else rRaw).toInt().coerceIn(0, 255)
                                    val g = (if (isNormalized) gRaw * 255f else gRaw).toInt().coerceIn(0, 255)
                                    val b = (if (isNormalized) bRaw * 255f else bRaw).toInt().coerceIn(0, 255)
                                    vertColors[vertReadCount] = Color.rgb(maxOf(r, 16), maxOf(g, 16), maxOf(b, 18))
                                }
                            }
                            vertReadCount++
                        }
                    }
                } else if (numFaces > 0 && !isGeometryTruncated) {
                    val parts = trimmed.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val count = parts[0].coerceAtMost(parts.size - 1)
                        if (count >= 3) {
                            val idx0 = parts[1]
                            if (idx0 in 0 until vertReadCount) {
                                val b0 = idx0 * 3
                                val c0 = vertColors[idx0]
                                for (k in 2 until count) {
                                    val idx1 = parts[k]
                                    val idx2 = parts[k + 1]
                                    if (idx1 in 0 until vertReadCount && idx2 in 0 until vertReadCount) {
                                        val b1 = idx1 * 3
                                        val b2 = idx2 * 3
                                        addTriangle(
                                            vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2],
                                            vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2],
                                            vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2],
                                            c0
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clean-Room parser for Object File Format `.off` / `COFF` / `NOFF` 3D meshes.
     */
    private fun parseOff(stream: InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertReadCount = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var countsRead = false
            var numVerts = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && !isGeometryTruncated) {
                var clean = line!!.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                if (!countsRead && (clean.startsWith("OFF", true) || clean.startsWith("COFF", true) || clean.startsWith("NOFF", true))) {
                    clean = clean.replaceFirst(Regex("^(?i)(C?N?OFF)\\s*"), "").trim()
                    if (clean.isEmpty()) continue
                }
                if (!countsRead) {
                    val parts = clean.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 2) {
                        numVerts = parts[0].coerceIn(0, 2_000_000)
                        vertCoords = FloatArray(maxOf(30_000, numVerts * 3))
                        countsRead = true
                    }
                } else if (vertReadCount < numVerts) {
                    val parts = clean.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val vx = parts[0].toFloatOrNull()
                        val vy = parts[1].toFloatOrNull()
                        val vz = parts[2].toFloatOrNull()
                        if (vx != null && vy != null && vz != null) {
                            if ((vertReadCount + 1) * 3 > vertCoords.size) {
                                vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            }
                            val base = vertReadCount * 3
                            vertCoords[base] = vx
                            vertCoords[base + 1] = vy
                            vertCoords[base + 2] = vz
                            vertReadCount++
                        }
                    }
                } else {
                    val parts = clean.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val count = parts[0].coerceAtMost(parts.size - 1)
                        val idx0 = parts[1]
                        if (count >= 3 && idx0 in 0 until vertReadCount) {
                            val b0 = idx0 * 3
                            for (k in 2 until count) {
                                val idx1 = parts[k]
                                val idx2 = parts[k + 1]
                                if (idx1 in 0 until vertReadCount && idx2 in 0 until vertReadCount) {
                                    val b1 = idx1 * 3
                                    val b2 = idx2 * 3
                                    addTriangle(
                                        vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2],
                                        vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2],
                                        vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2]
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clean-Room parser for Autodesk 3D Studio `.3ds` binary mesh chunks (`0x4D4D` -> `0x3D3D` -> `0x4000` -> `0x4100` -> `0x4110` vertices & `0x4120` faces).
     */
    private fun parse3dsCleanRoom(fileSize: Long) {
        val limit = if (fileSize > 0L) fileSize else 128L * 1024L * 1024L
        if (limit < 16L) return
        val hdrBuf = ByteBuffer.allocateDirect(6).order(ByteOrder.LITTLE_ENDIAN)
        var currentMeshVerts: FloatArray? = null
        var currentMeshVertCount = 0

        fun walk3dsChunksPread(start: Long, endLimit: Long, depth: Int) {
            if (depth > 16) return
            var pos = start
            while (pos + 6L <= endLimit && !isGeometryTruncated) {
                hdrBuf.clear()
                if (try { Os.pread(pfd.fileDescriptor, hdrBuf, pos) } catch (_: Throwable) { 0 } < 6) break
                hdrBuf.flip()
                val chunkId = hdrBuf.short.toInt() and 0xFFFF
                val chunkLen = hdrBuf.int.toLong() and 0xFFFFFFFFL
                if (chunkLen < 6L) break
                val nextPos = minOf(endLimit, pos + chunkLen)
                val dataPos = pos + 6L

                when (chunkId) {
                    0x4D4D, 0x3D3D, 0x4100 -> walk3dsChunksPread(dataPos, nextPos, depth + 1)
                    0x4000 -> {
                        val nameProbe = readDescriptorSliceAt(dataPos, minOf(256L, nextPos - dataPos).toInt())
                        var strLen = 0
                        while (strLen < nameProbe.size && nameProbe[strLen].toInt() != 0) strLen++
                        if (dataPos + strLen + 1L < nextPos) {
                            walk3dsChunksPread(dataPos + strLen + 1L, nextPos, depth + 1)
                        }
                    }
                    0x4110 -> {
                        val payloadLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (payloadLen >= 2) {
                            val chunkBytes = readDescriptorSliceAt(dataPos, payloadLen)
                            val bb = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val vCount = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (chunkBytes.size - 2) / 12)
                            val coords = FloatArray(vCount * 3)
                            for (i in 0 until vCount) {
                                val base = 2 + i * 12
                                coords[i * 3] = bb.getFloat(base)
                                coords[i * 3 + 1] = bb.getFloat(base + 4)
                                coords[i * 3 + 2] = bb.getFloat(base + 8)
                            }
                            currentMeshVerts = coords
                            currentMeshVertCount = vCount
                        }
                    }
                    0x4120 -> {
                        val verts = currentMeshVerts
                        val vCount = currentMeshVertCount
                        val payloadLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (verts != null && vCount > 0 && payloadLen >= 2) {
                            val chunkBytes = readDescriptorSliceAt(dataPos, payloadLen)
                            val bb = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val fCount = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (chunkBytes.size - 2) / 8)
                            for (i in 0 until fCount) {
                                val base = 2 + i * 8
                                val i0 = bb.getShort(base).toInt() and 0xFFFF
                                val i1 = bb.getShort(base + 2).toInt() and 0xFFFF
                                val i2 = bb.getShort(base + 4).toInt() and 0xFFFF
                                if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                    val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(
                                        verts[b0], verts[b0 + 1], verts[b0 + 2],
                                        verts[b1], verts[b1 + 1], verts[b1 + 2],
                                        verts[b2], verts[b2 + 1], verts[b2 + 2]
                                    )
                                }
                            }
                        }
                    }
                }
                if (nextPos <= pos) break
                pos = nextPos
            }
        }

        walk3dsChunksPread(0L, limit, 0)
    }

    /**
     * Full Clean-Room Khronos glTF 2.0 (`.gltf` JSON & `.glb` Binary) 3D Scene & Mesh Parser.
     * Supports arbitrary file sizes (including `.glb` files > 100 MB) by reading binary buffer views
     * directly from the file descriptor via `Os.pread` when `customBytes == null`.
     */
    private fun parseGltfOrGlbCleanRoom(customBytes: ByteArray? = null) {
        var jsonString: String? = null
        val inlineBuffersList = ArrayList<ByteArray>()
        val fileBufferSlices = ArrayList<Pair<Long, Int>>() // (fileOffset, byteLength) for zero-OOM GLB BIN chunks

        if (customBytes != null) {
            if (customBytes.size < 12) return
            val bb = ByteBuffer.wrap(customBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) == 0x46546C67) {
                var pos = 12
                while (pos + 8 <= customBytes.size) {
                    val chunkLen = bb.getInt(pos)
                    val chunkType = bb.getInt(pos + 4)
                    val dataStart = pos + 8
                    if (chunkLen < 0 || dataStart > customBytes.size) break
                    val safeLen = minOf(chunkLen, customBytes.size - dataStart)
                    if (chunkType == 0x4E4F534A && jsonString == null) {
                        jsonString = String(customBytes, dataStart, safeLen, StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942) {
                        inlineBuffersList.add(customBytes.copyOfRange(dataStart, dataStart + safeLen))
                    }
                    pos = dataStart + safeLen
                }
            } else {
                jsonString = String(customBytes, StandardCharsets.UTF_8)
            }
        } else {
            val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 512L * 1024L * 1024L }
            val headerBytes = readDescriptorSliceAt(0L, 12)
            if (headerBytes.size < 12) return
            val hbb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (hbb.getInt(0) == 0x46546C67) {
                modelType = "GLB (glTF 2.0)"
                var pos = 12L
                val chunkHdr = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                while (pos + 8L <= fileSize) {
                    chunkHdr.clear()
                    if (try { Os.pread(pfd.fileDescriptor, chunkHdr, pos) } catch (_: Throwable) { 0 } < 8) break
                    chunkHdr.flip()
                    val chunkLen = chunkHdr.int.toLong() and 0xFFFFFFFFL
                    val chunkType = chunkHdr.int
                    val dataStart = pos + 8L
                    if (chunkLen <= 0L || dataStart >= fileSize) break
                    val availChunkLen = minOf(chunkLen, fileSize - dataStart)

                    if (chunkType == 0x4E4F534A && jsonString == null && availChunkLen <= 32L * 1024L * 1024L) {
                        val jsonBytes = readDescriptorSliceAt(dataStart, availChunkLen.toInt())
                        jsonString = String(jsonBytes, StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942) {
                        fileBufferSlices.add(dataStart to availChunkLen.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    pos = dataStart + availChunkLen
                }
            } else {
                modelType = "glTF 2.0"
                val jsonBytes = readDescriptorBytes(48 * 1024 * 1024)
                jsonString = String(jsonBytes, StandardCharsets.UTF_8)
            }
        }

        val rootJsonStr = jsonString?.trim() ?: return
        if (!rootJsonStr.startsWith("{")) return

        val root = try { JSONObject(rootJsonStr) } catch (_: Throwable) { return }

        val jsonBuffers = root.optJSONArray("buffers")
        if (jsonBuffers != null) {
            for (i in 0 until jsonBuffers.length()) {
                val bufObj = jsonBuffers.optJSONObject(i) ?: continue
                val uri = bufObj.optString("uri", "")
                if (uri.startsWith("data:", ignoreCase = true) && uri.contains(";base64,")) {
                    val b64 = uri.substringAfter(";base64,").trim()
                    if (b64.length <= 64 * 1024 * 1024) {
                        try {
                            val decoded = Base64.decode(b64, Base64.DEFAULT)
                            if (i < inlineBuffersList.size) {
                                inlineBuffersList[i] = decoded
                            } else {
                                inlineBuffersList.add(decoded)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        if (inlineBuffersList.isEmpty() && fileBufferSlices.isEmpty()) return

        val bufferViews = root.optJSONArray("bufferViews") ?: return
        val accessors = root.optJSONArray("accessors") ?: return
        val meshes = root.optJSONArray("meshes") ?: return
        val materials = root.optJSONArray("materials")
        val texturesJson = root.optJSONArray("textures")
        val imagesJson = root.optJSONArray("images")

        fun readBufferRange(bufIdx: Int, startOffset: Int, byteLength: Int): ByteArray? {
            if (startOffset < 0 || byteLength <= 0 || byteLength > 48 * 1024 * 1024) return null
            val inlineBuf = inlineBuffersList.getOrNull(bufIdx)
            if (inlineBuf != null) {
                if (startOffset >= inlineBuf.size) return null
                val end = minOf(inlineBuf.size, startOffset + byteLength)
                return inlineBuf.copyOfRange(startOffset, end)
            }
            val fileSlice = fileBufferSlices.getOrNull(bufIdx) ?: return null
            if (startOffset >= fileSlice.second) return null
            val safeLen = minOf(byteLength, fileSlice.second - startOffset)
            return readDescriptorSliceAt(fileSlice.first + startOffset.toLong(), safeLen)
        }

        fun linearToSrgbChannel(linearVal: Double): Float {
            val c = linearVal.coerceIn(0.0, 1.0)
            val srgb = if (c <= 0.0031308) {
                c * 12.92
            } else {
                1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
            }
            return srgb.toFloat().coerceIn(0f, 1f)
        }

        val textureAvgColorCache = HashMap<Int, Int>()
        fun sampleTextureAverageColor(texIdx: Int): Int? {
            if (texIdx < 0 || texturesJson == null || imagesJson == null || texIdx >= texturesJson.length()) return null
            textureAvgColorCache[texIdx]?.let { return if (it == 0) null else it }
            val imgIdx = texturesJson.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
            if (imgIdx !in 0 until imagesJson.length()) {
                textureAvgColorCache[texIdx] = 0
                return null
            }
            val imgObj = imagesJson.optJSONObject(imgIdx) ?: return null
            val imgBytes: ByteArray? = when {
                imgObj.has("bufferView") -> {
                    val bvIdx = imgObj.optInt("bufferView", -1)
                    val bv = if (bvIdx in 0 until bufferViews.length()) bufferViews.optJSONObject(bvIdx) else null
                    if (bv != null) {
                        val bIdx = bv.optInt("buffer", 0)
                        val bOff = bv.optInt("byteOffset", 0)
                        val bLen = bv.optInt("byteLength", 0)
                        if (bLen in 16..(16 * 1024 * 1024)) readBufferRange(bIdx, bOff, bLen) else null
                    } else null
                }
                imgObj.optString("uri", "").startsWith("data:", ignoreCase = true) -> {
                    val uri = imgObj.optString("uri", "")
                    if (uri.contains(";base64,")) {
                        val b64 = uri.substringAfter(";base64,").trim()
                        if (b64.length <= 16 * 1024 * 1024) {
                            try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Throwable) { null }
                        } else null
                    } else null
                }
                else -> null
            }
            if (imgBytes == null || imgBytes.size < 16) {
                textureAvgColorCache[texIdx] = 0
                return null
            }
            val sampled = try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, boundsOpts)
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sample = 1
                while (maxDim / sample > 32) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, opts)
                if (bmp != null) {
                    var sumR = 0L; var sumG = 0L; var sumB = 0L; var sumA = 0L; var count = 0L
                    for (py in 0 until bmp.height) {
                        for (px in 0 until bmp.width) {
                            val pxCol = bmp.getPixel(px, py)
                            val pa = Color.alpha(pxCol)
                            if (pa > 32) {
                                sumR += Color.red(pxCol)
                                sumG += Color.green(pxCol)
                                sumB += Color.blue(pxCol)
                                sumA += pa
                                count++
                            }
                        }
                    }
                    bmp.recycle()
                    if (count > 0L) {
                        Color.argb(
                            (sumA / count).toInt().coerceIn(32, 255),
                            (sumR / count).toInt().coerceIn(0, 255),
                            (sumG / count).toInt().coerceIn(0, 255),
                            (sumB / count).toInt().coerceIn(0, 255)
                        )
                    } else 0
                } else 0
            } catch (_: Throwable) {
                0
            }
            textureAvgColorCache[texIdx] = sampled
            return if (sampled == 0) null else sampled
        }

        val resolvedMaterialCache = HashMap<Int, Int>()
        fun resolveMaterialColor(matIdx: Int): Int {
            if (materials == null || matIdx !in 0 until materials.length()) return Color.rgb(185, 192, 204)
            resolvedMaterialCache[matIdx]?.let { return it }
            val mat = materials.optJSONObject(matIdx) ?: return Color.rgb(185, 192, 204)
            val matName = mat.optString("name", "").lowercase(Locale.ROOT)
            val alphaMode = mat.optString("alphaMode", "OPAQUE").uppercase(Locale.ROOT)
            val pbr = mat.optJSONObject("pbrMetallicRoughness")
            val factor = pbr?.optJSONArray("baseColorFactor")
                ?: mat.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")?.optJSONArray("diffuseFactor")
                ?: mat.optJSONArray("emissiveFactor")

            var rSrgb = 0.78f
            var gSrgb = 0.80f
            var bSrgb = 0.84f
            var alphaFactor = 1.0f

            if (factor != null && factor.length() >= 3) {
                rSrgb = linearToSrgbChannel(factor.optDouble(0, 0.8))
                gSrgb = linearToSrgbChannel(factor.optDouble(1, 0.8))
                bSrgb = linearToSrgbChannel(factor.optDouble(2, 0.8))
                if (factor.length() >= 4) {
                    alphaFactor = factor.optDouble(3, 1.0).toFloat().coerceIn(0f, 1f)
                }
            }

            val baseTexIdx = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
            val texColor = sampleTextureAverageColor(baseTexIdx)
            if (texColor != null) {
                val tr = Color.red(texColor) / 255f
                val tg = Color.green(texColor) / 255f
                val tb = Color.blue(texColor) / 255f
                val ta = Color.alpha(texColor) / 255f
                rSrgb = (rSrgb * tr).coerceIn(0f, 1f)
                gSrgb = (gSrgb * tg).coerceIn(0f, 1f)
                bSrgb = (bSrgb * tb).coerceIn(0f, 1f)
                if (alphaMode == "BLEND") {
                    alphaFactor = (alphaFactor * ta).coerceIn(0f, 1f)
                }
            }

            if (alphaMode == "BLEND" && (alphaFactor < 0.28f || matName.contains("coat") || matName.contains("refraction") || matName == "material_0")) {
                resolvedMaterialCache[matIdx] = Int.MIN_VALUE
                return Int.MIN_VALUE
            }

            val finalAlpha = when {
                alphaMode == "BLEND" || matName.contains("glass") || matName.contains("window") ->
                    (alphaFactor * 140f).toInt().coerceIn(48, 175)
                else -> 255
            }

            val r = maxOf((rSrgb * 255f).toInt().coerceIn(0, 255), 42)
            val g = maxOf((gSrgb * 255f).toInt().coerceIn(0, 255), 44)
            val b = maxOf((bSrgb * 255f).toInt().coerceIn(0, 255), 48)
            val packed = Color.argb(finalAlpha, r, g, b)
            resolvedMaterialCache[matIdx] = packed
            return packed
        }

        fun readAccessorColors(accIdx: Int): IntArray? {
            if (accIdx !in 0 until accessors.length()) return null
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx !in 0 until bufferViews.length()) return null
            val bv = bufferViews.optJSONObject(bvIdx) ?: return null
            val bufIdx = bv.optInt("buffer", 0)
            val compType = acc.optInt("componentType", 5126)
            val accType = acc.optString("type", "VEC3")
            val numComp = if (accType == "VEC4") 4 else 3
            val bpc = when (compType) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val defStride = numComp * bpc
            val stride = bv.optInt("byteStride", defStride).coerceAtLeast(defStride)
            val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declared = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 3)
            if (declared <= 0) return null
            val slice = readBufferRange(bufIdx, start, (declared - 1) * stride + defStride) ?: return null
            if (slice.size < defStride) return null
            val count = minOf(declared, 1 + (slice.size - defStride) / stride)
            val bufBb = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { i ->
                val p = i * stride
                fun ch(c: Int): Int {
                    val cp = p + c * bpc
                    return when (compType) {
                        5126 -> (bufBb.getFloat(cp).coerceIn(0f, 1f) * 255f).toInt()
                        5123 -> ((bufBb.getShort(cp).toInt() and 0xFFFF) ushr 8).coerceIn(0, 255)
                        5121 -> slice[cp].toInt() and 0xFF
                        else -> 200
                    }
                }
                Color.rgb(maxOf(ch(0), 20), maxOf(ch(1), 20), maxOf(ch(2), 24))
            }
        }

        fun readAccessorPositions(accIdx: Int): FloatArray? {
            if (accIdx !in 0 until accessors.length()) return null
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx !in 0 until bufferViews.length()) return null
            val bv = bufferViews.optJSONObject(bvIdx) ?: return null
            val bufIdx = bv.optInt("buffer", 0)

            val compType = acc.optInt("componentType", 5126)
            val accType = acc.optString("type", "VEC3")
            val numComponents = when (accType) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                else -> 3
            }
            val bytesPerComp = when (compType) {
                5120, 5121 -> 1
                5122, 5123 -> 2
                5125, 5126 -> 4
                else -> 4
            }
            val defaultStride = numComponents * bytesPerComp
            val byteStride = bv.optInt("byteStride", defaultStride).coerceAtLeast(defaultStride)
            val startOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declaredCount = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 3)
            if (declaredCount <= 0) return null

            val neededBytes = (declaredCount - 1) * byteStride + defaultStride
            val sliceBytes = readBufferRange(bufIdx, startOffset, neededBytes) ?: return null
            if (sliceBytes.size < defaultStride) return null

            val maxPossibleElements = 1 + (sliceBytes.size - defaultStride) / byteStride
            val count = minOf(declaredCount, maxPossibleElements)
            if (count <= 0) return null

            val bufBb = ByteBuffer.wrap(sliceBytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(count * 3)
            val normalized = acc.optBoolean("normalized", false)

            for (i in 0 until count) {
                val elemPos = i * byteStride
                if (elemPos + numComponents * bytesPerComp > sliceBytes.size) break
                for (c in 0 until minOf(3, numComponents)) {
                    val cPos = elemPos + c * bytesPerComp
                    val v: Float = when (compType) {
                        5126 -> bufBb.getFloat(cPos)
                        5122 -> {
                            val s = bufBb.getShort(cPos).toFloat()
                            if (normalized) (s / 32767f).coerceIn(-1f, 1f) else s
                        }
                        5123 -> {
                            val u = (bufBb.getShort(cPos).toInt() and 0xFFFF).toFloat()
                            if (normalized) u / 65535f else u
                        }
                        5120 -> {
                            val b = sliceBytes[cPos].toFloat()
                            if (normalized) (b / 127f).coerceIn(-1f, 1f) else b
                        }
                        5121 -> {
                            val ub = (sliceBytes[cPos].toInt() and 0xFF).toFloat()
                            if (normalized) ub / 255f else ub
                        }
                        else -> 0f
                    }
                    out[i * 3 + c] = if (v.isFinite()) v else 0f
                }
            }
            return out
        }

        fun readAccessorIndices(accIdx: Int): IntArray? {
            if (accIdx !in 0 until accessors.length()) return null
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx !in 0 until bufferViews.length()) return null
            val bv = bufferViews.optJSONObject(bvIdx) ?: return null
            val bufIdx = bv.optInt("buffer", 0)

            val compType = acc.optInt("componentType", 5123)
            val bytesPerComp = when (compType) {
                5120, 5121 -> 1
                5122, 5123 -> 2
                5125 -> 4
                else -> 2
            }
            val byteStride = bv.optInt("byteStride", bytesPerComp).coerceAtLeast(bytesPerComp)
            val startOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declaredCount = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 6)
            if (declaredCount <= 0) return null

            val neededBytes = (declaredCount - 1) * byteStride + bytesPerComp
            val sliceBytes = readBufferRange(bufIdx, startOffset, neededBytes) ?: return null
            if (sliceBytes.size < bytesPerComp) return null

            val maxPossibleIndices = 1 + (sliceBytes.size - bytesPerComp) / byteStride
            val count = minOf(declaredCount, maxPossibleIndices)
            if (count <= 0) return null

            val bufBb = ByteBuffer.wrap(sliceBytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = IntArray(count)
            for (i in 0 until count) {
                val p = i * byteStride
                if (p + bytesPerComp > sliceBytes.size) break
                out[i] = when (compType) {
                    5121, 5120 -> sliceBytes[p].toInt() and 0xFF
                    5123, 5122 -> bufBb.getShort(p).toInt() and 0xFFFF
                    5125 -> bufBb.getInt(p)
                    else -> 0
                }
            }
            return out
        }

        fun transformVerts(verts: FloatArray, m: FloatArray): FloatArray {
            val out = FloatArray(verts.size)
            for (i in 0 until verts.size step 3) {
                val x = verts[i]
                val y = verts[i + 1]
                val z = verts[i + 2]
                out[i] = m[0] * x + m[4] * y + m[8] * z + m[12]
                out[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                out[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            }
            return out
        }

        fun emitMeshAtMatrix(meshIdx: Int, worldMatrix: FloatArray) {
            if (meshIdx !in 0 until meshes.length() || isGeometryTruncated) return
            val meshObj = meshes.optJSONObject(meshIdx) ?: return
            val primitives = meshObj.optJSONArray("primitives") ?: return

            for (pIdx in 0 until primitives.length()) {
                if (isGeometryTruncated) break
                val prim = primitives.optJSONObject(pIdx) ?: continue
                val attrs = prim.optJSONObject("attributes") ?: continue
                val posAccIdx = attrs.optInt("POSITION", -1)
                val rawPos = readAccessorPositions(posAccIdx) ?: continue
                val pos = transformVerts(rawPos, worldMatrix)
                val vCount = pos.size / 3
                if (vCount <= 0) continue

                val mode = prim.optInt("mode", 4)
                val matColor = resolveMaterialColor(prim.optInt("material", -1))
                if (matColor == Int.MIN_VALUE) continue
                val vertColors = if (attrs.has("COLOR_0")) readAccessorColors(attrs.optInt("COLOR_0", -1)) else null
                val indices = if (prim.has("indices")) readAccessorIndices(prim.optInt("indices", -1)) else null

                fun getVx(vi: Int) = pos[vi * 3]
                fun getVy(vi: Int) = pos[vi * 3 + 1]
                fun getVz(vi: Int) = pos[vi * 3 + 2]
                fun colorAt(vi: Int) = vertColors?.getOrNull(vi) ?: matColor

                when (mode) {
                    4 -> { // TRIANGLES
                        if (indices != null) {
                            var i = 0
                            while (i + 2 < indices.size && !isGeometryTruncated) {
                                val i0 = indices[i]; val i1 = indices[i + 1]; val i2 = indices[i + 2]
                                if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                    addTriangle(
                                        getVx(i0), getVy(i0), getVz(i0),
                                        getVx(i1), getVy(i1), getVz(i1),
                                        getVx(i2), getVy(i2), getVz(i2),
                                        colorAt(i0)
                                    )
                                }
                                i += 3
                            }
                        } else {
                            var i = 0
                            while (i + 2 < vCount && !isGeometryTruncated) {
                                addTriangle(
                                    getVx(i), getVy(i), getVz(i),
                                    getVx(i + 1), getVy(i + 1), getVz(i + 1),
                                    getVx(i + 2), getVy(i + 2), getVz(i + 2),
                                    colorAt(i)
                                )
                                i += 3
                            }
                        }
                    }
                    5 -> { // TRIANGLE_STRIP
                        val count = indices?.size ?: vCount
                        for (i in 0 until count - 2) {
                            if (isGeometryTruncated) break
                            val i0 = if (i % 2 == 0) (indices?.get(i) ?: i) else (indices?.get(i + 1) ?: (i + 1))
                            val i1 = if (i % 2 == 0) (indices?.get(i + 1) ?: (i + 1)) else (indices?.get(i) ?: i)
                            val i2 = indices?.get(i + 2) ?: (i + 2)
                            if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                addTriangle(
                                    getVx(i0), getVy(i0), getVz(i0),
                                    getVx(i1), getVy(i1), getVz(i1),
                                    getVx(i2), getVy(i2), getVz(i2),
                                    matColor
                                )
                            }
                        }
                    }
                    6 -> { // TRIANGLE_FAN
                        val count = indices?.size ?: vCount
                        if (count >= 3) {
                            val i0 = indices?.get(0) ?: 0
                            for (i in 1 until count - 1) {
                                if (isGeometryTruncated) break
                                val i1 = indices?.get(i) ?: i
                                val i2 = indices?.get(i + 1) ?: (i + 1)
                                if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                    addTriangle(
                                        getVx(i0), getVy(i0), getVz(i0),
                                        getVx(i1), getVy(i1), getVz(i1),
                                        getVx(i2), getVy(i2), getVz(i2),
                                        matColor
                                    )
                                }
                            }
                        }
                    }
                    1, 2, 3 -> { // LINES / LINE_LOOP / LINE_STRIP
                        val count = indices?.size ?: vCount
                        val step = if (mode == 1) 2 else 1
                        var i = 0
                        while (i + 1 < count && !isGeometryTruncated) {
                            val i0 = indices?.get(i) ?: i
                            val i1 = indices?.get(i + 1) ?: (i + 1)
                            if (i0 in 0 until vCount && i1 in 0 until vCount) {
                                addLineSegment(getVx(i0), getVy(i0), getVz(i0), getVx(i1), getVy(i1), getVz(i1), matColor)
                            }
                            i += step
                        }
                    }
                }
            }
        }

        val identityMat = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray {
            val res = FloatArray(16)
            for (c in 0..3) {
                for (r in 0..3) {
                    res[c * 4 + r] =
                        a[0 * 4 + r] * b[c * 4 + 0] +
                                a[1 * 4 + r] * b[c * 4 + 1] +
                                a[2 * 4 + r] * b[c * 4 + 2] +
                                a[3 * 4 + r] * b[c * 4 + 3]
                }
            }
            return res
        }

        fun computeNodeLocalMatrix(node: JSONObject): FloatArray {
            val matArr = node.optJSONArray("matrix")
            if (matArr != null && matArr.length() == 16) {
                return FloatArray(16) { matArr.optDouble(it, if (it % 5 == 0) 1.0 else 0.0).toFloat() }
            }
            val t = node.optJSONArray("translation")
            val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f

            val r = node.optJSONArray("rotation")
            val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
            val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f

            val s = node.optJSONArray("scale")
            val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
            val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
            val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

            val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
            val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
            val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
            val wx = qw * x2; val wy = qw * y2; val wz = qw * z2

            return floatArrayOf(
                (1f - (yy + zz)) * sx, (xy + wz) * sx, (xz - wy) * sx, 0f,
                (xy - wz) * sy, (1f - (xx + zz)) * sy, (yz + wx) * sy, 0f,
                (xz + wy) * sz, (yz - wx) * sz, (1f - (xx + yy)) * sz, 0f,
                tx, ty, tz, 1f
            )
        }

        val nodes = root.optJSONArray("nodes")
        val visitedNodes = HashSet<Int>()
        if (nodes != null && nodes.length() > 0) {
            fun traverseNode(nodeIdx: Int, parentMat: FloatArray, depth: Int) {
                if (depth > 32 || isGeometryTruncated || nodeIdx !in 0 until nodes.length() || !visitedNodes.add(nodeIdx)) return
                val nodeObj = nodes.optJSONObject(nodeIdx) ?: return
                val worldMat = multiplyMat4(parentMat, computeNodeLocalMatrix(nodeObj))
                if (nodeObj.has("mesh")) {
                    emitMeshAtMatrix(nodeObj.optInt("mesh", -1), worldMat)
                }
                val children = nodeObj.optJSONArray("children")
                if (children != null) {
                    for (c in 0 until children.length()) {
                        traverseNode(children.optInt(c, -1), worldMat, depth + 1)
                    }
                }
            }

            for (n in 0 until nodes.length()) {
                if (n !in visitedNodes && !isGeometryTruncated) {
                    traverseNode(n, identityMat, 0)
                }
            }
        }

        if (vertexCount == 0) {
            for (mIdx in 0 until meshes.length()) {
                if (isGeometryTruncated) break
                emitMeshAtMatrix(mIdx, identityMat)
            }
        }
    }
    /**
     * Clean-Room Blender 3D (`.blend`) binary parser supporting Blender 2.7x, 2.8x, 2.9x, 3.x, 4.x, and 5.x.
     * Handles uncompressed `"BLENDER"` files and GZIP-compressed `.blend` files, parses `DNA1`
     * structure definitions (`Mesh`, `MVert`, `MPoly`, `MLoop`, `MFace`, and Blender 4.0+ SOA float3/int32
     * attribute buffers), and triangulates all mesh geometry.
     */
    private fun parseBlendCleanRoom() {
        var bytes = readDescriptorBytes(64 * 1024 * 1024)
        if (bytes.size < 16) return

        // Handle GZIP-compressed .blend files (1F 8B) with compression ratio bomb protection
        if ((bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            val compressedLen = bytes.size
            bytes = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val bos = ByteArrayOutputStream(minOf(compressedLen * 3, 64 * 1024 * 1024))
                    val tmp = ByteArray(16384)
                    var total = 0
                    while (total < 64 * 1024 * 1024) {
                        val r = gis.read(tmp)
                        if (r <= 0) break
                        total += r
                        if (total > 16 * 1024 * 1024 && total / compressedLen.coerceAtLeast(1) > 150) {
                            throw SecurityException("Превышен безопасный коэффициент сжатия GZIP.")
                        }
                        bos.write(tmp, 0, r)
                    }
                    bos.toByteArray()
                }
            } catch (_: Throwable) {
                return
            }
        }

        if (bytes.size < 16 || String(bytes, 0, 7, StandardCharsets.US_ASCII) != "BLENDER") {
            return
        }

        val is17ByteHeader = bytes[7].toInt().toChar().isDigit() && bytes[8].toInt().toChar().isDigit()
        val ptrSize: Int
        val order: ByteOrder
        val versionStr: String
        var pos: Int

        if (is17ByteHeader && bytes.size >= 17) {
            ptrSize = 8
            order = if (bytes[12].toInt().toChar() == 'V') ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            versionStr = String(bytes, 13, 4, StandardCharsets.US_ASCII)
            pos = 17
        } else {
            ptrSize = if (bytes[7].toInt().toChar() == '_') 4 else 8
            order = if (bytes[8].toInt().toChar() == 'V') ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            versionStr = String(bytes, 9, 3, StandardCharsets.US_ASCII)
            pos = 12
        }
        modelType = "Blender ($versionStr)"

        val bb = ByteBuffer.wrap(bytes).order(order)
        val blocks = ArrayList<BlendBlock>()

        while (pos + 16 <= bytes.size && blocks.size < 200_000) {
            val codeBytes = ByteArray(4)
            System.arraycopy(bytes, pos, codeBytes, 0, 4)
            val code = String(codeBytes, StandardCharsets.US_ASCII).trimEnd('\u0000', ' ')

            val len: Int
            val sdnaNr: Int
            val nr: Int
            val hdrSize: Int

            if (is17ByteHeader) {
                if (pos + 32 > bytes.size) break
                sdnaNr = bb.getInt(pos + 4)
                len = bb.getLong(pos + 16).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                nr = bb.getLong(pos + 24).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                hdrSize = 32
            } else {
                hdrSize = 16 + ptrSize
                if (pos + hdrSize > bytes.size) break
                len = bb.getInt(pos + 4)
                sdnaNr = bb.getInt(pos + 8 + ptrSize)
                nr = bb.getInt(pos + 12 + ptrSize)
            }

            val dataStart = pos + hdrSize
            if (len < 0 || dataStart + len > bytes.size) break
            blocks.add(BlendBlock(code, dataStart, len, sdnaNr, nr))
            pos = dataStart + len
            if (code == "ENDB") break
        }

        var mvertStructIdx = -1
        var mpolyStructIdx = -1
        var mloopStructIdx = -1
        var mfaceStructIdx = -1

        val dnaBlock = blocks.firstOrNull { it.code == "DNA1" }
        if (dnaBlock != null && dnaBlock.len >= 16) {
            var dPos = dnaBlock.dataOffset
            val dEnd = dnaBlock.dataOffset + dnaBlock.len
            if (String(bytes, dPos, 8, StandardCharsets.US_ASCII) == "SDNANAME") {
                dPos += 8
                val nameCount = bb.getInt(dPos).coerceIn(0, 65536)
                dPos += 4
                for (i in 0 until nameCount) {
                    while (dPos < dEnd && bytes[dPos].toInt() != 0) dPos++
                    dPos++
                }
                dPos = (dPos + 3) and -4
                if (dPos + 8 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "TYPE") {
                    dPos += 4
                    val typeCount = bb.getInt(dPos).coerceIn(0, 65536)
                    dPos += 4
                    val typeNames = Array(typeCount) { "" }
                    for (i in 0 until typeCount) {
                        val s = dPos
                        while (dPos < dEnd && bytes[dPos].toInt() != 0) dPos++
                        if (dPos > s) typeNames[i] = String(bytes, s, dPos - s, StandardCharsets.US_ASCII)
                        dPos++
                    }
                    dPos = (dPos + 3) and -4
                    if (dPos + 4 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "TLEN") {
                        dPos += 4 + typeCount * 2
                        dPos = (dPos + 3) and -4
                    }
                    if (dPos + 8 <= dEnd && String(bytes, dPos, 4, StandardCharsets.US_ASCII) == "STRC") {
                        dPos += 4
                        val structCount = bb.getInt(dPos).coerceIn(0, 65536)
                        dPos += 4
                        for (sIdx in 0 until structCount) {
                            if (dPos + 4 > dEnd) break
                            val typeIdx = bb.getShort(dPos).toInt() and 0xFFFF
                            val fieldCount = bb.getShort(dPos + 2).toInt() and 0xFFFF
                            dPos += 4 + fieldCount * 4
                            val structTypeName = typeNames.getOrNull(typeIdx) ?: ""
                            when (structTypeName) {
                                "MVert" -> mvertStructIdx = sIdx
                                "MPoly" -> mpolyStructIdx = sIdx
                                "MLoop" -> mloopStructIdx = sIdx
                                "MFace" -> mfaceStructIdx = sIdx
                            }
                        }
                    }
                }
            }
        }

        var idx = 0
        while (idx < blocks.size && !isGeometryTruncated) {
            val blk = blocks[idx]
            if (blk.code == "ME") {
                val subBlocks = ArrayList<BlendBlock>()
                var j = idx + 1
                while (j < blocks.size && blocks[j].code == "DATA") {
                    subBlocks.add(blocks[j])
                    j++
                }
                extractBlenderMeshFromDataBlocks(bb, subBlocks, mvertStructIdx, mpolyStructIdx, mloopStructIdx, mfaceStructIdx)
                idx = j
            } else {
                idx++
            }
        }

        if (vertexCount == 0) {
            val allDataBlocks = blocks.filter { it.code == "DATA" }
            extractBlenderMeshFromDataBlocks(bb, allDataBlocks, mvertStructIdx, mpolyStructIdx, mloopStructIdx, mfaceStructIdx)
        }
    }

    private fun extractBlenderMeshFromDataBlocks(
        bb: ByteBuffer,
        dataBlocks: List<BlendBlock>,
        mvertStructIdx: Int,
        mpolyStructIdx: Int,
        mloopStructIdx: Int,
        mfaceStructIdx: Int
    ) {
        var vertCoords: FloatArray? = null
        var vertCount = 0
        val loops = ArrayList<Int>()
        val polyLoops = ArrayList<Pair<Int, Int>>()
        val polyOffsets = ArrayList<Int>()

        for (blk in dataBlocks) {
            val blkOffset = blk.dataOffset
            val blkLen = blk.len
            val blkSdna = blk.sdnaIndex
            val blkCount = blk.count

            if (blkLen < 12) continue

            if (blkSdna == mvertStructIdx && mvertStructIdx >= 0 && blkCount > 0) {
                val stride = (blkLen / blkCount).coerceAtLeast(12)
                val safeCount = minOf(blkCount, blkLen / stride, 500_000)
                if (safeCount > 0) {
                    val coords = FloatArray(safeCount * 3)
                    for (v in 0 until safeCount) {
                        val p = blkOffset + v * stride
                        coords[v * 3] = bb.getFloat(p)
                        coords[v * 3 + 1] = bb.getFloat(p + 4)
                        coords[v * 3 + 2] = bb.getFloat(p + 8)
                    }
                    vertCoords = coords
                    vertCount = safeCount
                }
                continue
            }

            if (blkSdna == mloopStructIdx && mloopStructIdx >= 0 && blkCount > 0) {
                val stride = (blkLen / blkCount).coerceAtLeast(4)
                val safeCount = minOf(blkCount, blkLen / stride, 1_000_000)
                for (k in 0 until safeCount) {
                    loops.add(bb.getInt(blkOffset + k * stride))
                }
                continue
            }

            if (blkSdna == mpolyStructIdx && mpolyStructIdx >= 0 && blkCount > 0) {
                val stride = (blkLen / blkCount).coerceAtLeast(8)
                val safeCount = minOf(blkCount, blkLen / stride, 500_000)
                for (k in 0 until safeCount) {
                    val p = blkOffset + k * stride
                    val ls = bb.getInt(p)
                    val tl = bb.getInt(p + 4)
                    if (ls >= 0 && tl in 3..1024) polyLoops.add(ls to tl)
                }
                continue
            }

            if (blkSdna == mfaceStructIdx && mfaceStructIdx >= 0 && blkCount > 0 && vertCoords != null) {
                val coords = vertCoords
                val stride = (blkLen / blkCount).coerceAtLeast(16)
                val safeCount = minOf(blkCount, blkLen / stride, 500_000)
                for (k in 0 until safeCount) {
                    if (isGeometryTruncated) break
                    val p = blkOffset + k * stride
                    val v1 = bb.getInt(p); val v2 = bb.getInt(p + 4); val v3 = bb.getInt(p + 8); val v4 = bb.getInt(p + 12)
                    if (v1 in 0 until vertCount && v2 in 0 until vertCount && v3 in 0 until vertCount) {
                        addTriangle(
                            coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2],
                            coords[v2 * 3], coords[v2 * 3 + 1], coords[v2 * 3 + 2],
                            coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2]
                        )
                        if (v4 > 0 && v4 in 0 until vertCount) {
                            addTriangle(
                                coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2],
                                coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2],
                                coords[v4 * 3], coords[v4 * 3 + 1], coords[v4 * 3 + 2]
                            )
                        }
                    }
                }
                continue
            }

            if (vertCoords == null && blkLen % 12 == 0) {
                val candidateVertCount = blkLen / 12
                if (candidateVertCount in 3..500_000) {
                    var validFloats = 0
                    var nonZero = 0
                    val sampleCheck = minOf(candidateVertCount, 32)
                    for (s in 0 until sampleCheck) {
                        val fx = bb.getFloat(blkOffset + s * 12)
                        val fy = bb.getFloat(blkOffset + s * 12 + 4)
                        val fz = bb.getFloat(blkOffset + s * 12 + 8)
                        if (fx.isFinite() && fy.isFinite() && fz.isFinite() &&
                            abs(fx) < 1e4f && abs(fy) < 1e4f && abs(fz) < 1e4f
                        ) {
                            validFloats++
                            if (abs(fx) > 1e-5f || abs(fy) > 1e-5f || abs(fz) > 1e-5f) nonZero++
                        }
                    }
                    if (validFloats == sampleCheck && nonZero > 0) {
                        val coords = FloatArray(candidateVertCount * 3)
                        for (v in 0 until candidateVertCount) {
                            val p = blkOffset + v * 12
                            coords[v * 3] = bb.getFloat(p)
                            coords[v * 3 + 1] = bb.getFloat(p + 4)
                            coords[v * 3 + 2] = bb.getFloat(p + 8)
                        }
                        vertCoords = coords
                        vertCount = candidateVertCount
                        continue
                    }
                }
            }

            val currentCoords = vertCoords
            if (currentCoords != null && vertCount > 0 && blkLen % 4 == 0) {
                val intCount = blkLen / 4
                if (intCount in 3..1_000_000) {
                    val firstInt = bb.getInt(blkOffset)
                    if (firstInt == 0 && polyOffsets.isEmpty() && intCount >= 2) {
                        var isMonotonic = true
                        var prev = 0
                        for (k in 1 until minOf(intCount, 64)) {
                            val cur = bb.getInt(blkOffset + k * 4)
                            if (cur < prev + 3 || cur > prev + 64) {
                                isMonotonic = false
                                break
                            }
                            prev = cur
                        }
                        if (isMonotonic) {
                            for (k in 0 until intCount) {
                                polyOffsets.add(bb.getInt(blkOffset + k * 4))
                            }
                            continue
                        }
                    }

                    if (loops.isEmpty()) {
                        var allValidIndices = true
                        val checkCount = minOf(intCount, 64)
                        for (k in 0 until checkCount) {
                            val idxVal = bb.getInt(blkOffset + k * 4)
                            if (idxVal !in 0 until vertCount) {
                                allValidIndices = false
                                break
                            }
                        }
                        if (allValidIndices) {
                            for (k in 0 until intCount) {
                                loops.add(bb.getInt(blkOffset + k * 4))
                            }
                        }
                    }
                }
            }
        }

        val coords = vertCoords ?: return
        if (polyLoops.isEmpty() && polyOffsets.size >= 2) {
            for (i in 0 until polyOffsets.size - 1) {
                val start = polyOffsets[i]
                val end = polyOffsets[i + 1]
                if (end - start in 3..1024) {
                    polyLoops.add(start to (end - start))
                }
            }
        }

        if (polyLoops.isNotEmpty() && loops.isNotEmpty()) {
            for ((ls, tl) in polyLoops) {
                if (isGeometryTruncated) break
                if (ls < 0 || tl < 3 || ls + tl > loops.size) continue
                val i0 = loops[ls]
                if (i0 !in 0 until vertCount) continue
                val b0 = i0 * 3
                for (k in 1 until tl - 1) {
                    val i1 = loops[ls + k]
                    val i2 = loops[ls + k + 1]
                    if (i1 in 0 until vertCount && i2 in 0 until vertCount) {
                        val b1 = i1 * 3
                        val b2 = i2 * 3
                        addTriangle(
                            coords[b0], coords[b0 + 1], coords[b0 + 2],
                            coords[b1], coords[b1 + 1], coords[b1 + 2],
                            coords[b2], coords[b2 + 1], coords[b2 + 2]
                        )
                    }
                }
            }
        } else if (loops.size >= 3) {
            var k = 0
            while (k + 2 < loops.size && !isGeometryTruncated) {
                val i0 = loops[k]; val i1 = loops[k + 1]; val i2 = loops[k + 2]
                if (i0 in 0 until vertCount && i1 in 0 until vertCount && i2 in 0 until vertCount) {
                    val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                    addTriangle(
                        coords[b0], coords[b0 + 1], coords[b0 + 2],
                        coords[b1], coords[b1 + 1], coords[b1 + 2],
                        coords[b2], coords[b2 + 1], coords[b2 + 2]
                    )
                }
                k += 3
            }
        }
    }

    /**
     * Clean-Room Apple/Pixar USDZ (`.usdz`), USDA (`.usda`), and USDC (`PXR-USDC`) 3D Scene Parser.
     */
    private fun parseUsdOrUsdzCleanRoom() {
        val head = readDescriptorSliceAt(0L, 16)
        if (head.size < 4) return

        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            modelType = "USDZ 3D Package"
            try {
                val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 256L * 1024L * 1024L }
                openSafeStream { rawStream ->
                    ZipInputStream(rawStream).use { zis ->
                        val buf = ByteArray(16384)
                        var totalUnpacked = 0L
                        while (!isGeometryTruncated) {
                            val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                            val name = entry.name.lowercase(Locale.ROOT)
                            if (!entry.isDirectory) {
                                val bos = ByteArrayOutputStream()
                                var entryBytesCount = 0
                                while (entryBytesCount < 48 * 1024 * 1024) {
                                    val r = zis.read(buf)
                                    if (r <= 0) break
                                    bos.write(buf, 0, r)
                                    entryBytesCount += r
                                    totalUnpacked += r
                                    if (totalUnpacked > 48 * 1024 * 1024L && totalUnpacked / fileSize.coerceAtLeast(1L) > 150L) {
                                        throw SecurityException("Превышен безопасный коэффициент сжатия USDZ.")
                                    }
                                }
                                val entryBytes = bos.toByteArray()
                                when {
                                    name.endsWith(".usda") || (name.endsWith(".usd") && entryBytes.size >= 5 && String(entryBytes, 0, 5, StandardCharsets.US_ASCII) == "#usda") -> {
                                        parseUsdaTextScene(String(entryBytes, StandardCharsets.UTF_8))
                                    }
                                    name.endsWith(".usdc") || (entryBytes.size >= 8 && String(entryBytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") -> {
                                        parseUsdcCrateBinary(entryBytes)
                                    }
                                    name.endsWith(".glb") || name.endsWith(".gltf") -> {
                                        parseGltfOrGlbCleanRoom(entryBytes)
                                    }
                                    (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) && dwgPreviewBitmap == null -> {
                                        if (entryBytes.size in 64..(12 * 1024 * 1024)) {
                                            dwgPreviewBitmap = BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.size)
                                        }
                                    }
                                }
                            }
                            try { zis.closeEntry() } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
            if (vertexCount > 0) {
                dwgPreviewBitmap?.recycle()
                dwgPreviewBitmap = null
            }
            return
        }

        val bytes = readDescriptorBytes(64 * 1024 * 1024)
        if (bytes.size < 8) return
        if (String(bytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") {
            modelType = "USDC (Pixar Crate)"
            parseUsdcCrateBinary(bytes)
            return
        }

        modelType = "USDA 3D Scene"
        parseUsdaTextScene(String(bytes, StandardCharsets.UTF_8))
    }

    private fun parseUsdaTextScene(usdaText: String) {
        val pointsRegex = Regex("""point3f\[\]\s+points\s*=\s*\[([\s\S]*?)\]""")
        val countsRegex = Regex("""int\[\]\s+faceVertexCounts\s*=\s*\[([\s\S]*?)\]""")
        val indicesRegex = Regex("""int\[\]\s+faceVertexIndices\s*=\s*\[([\s\S]*?)\]""")
        val tupleRegex = Regex("""\(\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*\)""")

        val pointsMatches = pointsRegex.findAll(usdaText).toList()
        val countsMatches = countsRegex.findAll(usdaText).toList()
        val indicesMatches = indicesRegex.findAll(usdaText).toList()

        val meshCount = minOf(pointsMatches.size, indicesMatches.size)
        for (m in 0 until meshCount) {
            if (isGeometryTruncated) break
            val ptsStr = pointsMatches[m].groupValues[1]
            val verts = ArrayList<FloatArray>()
            for (tm in tupleRegex.findAll(ptsStr)) {
                if (verts.size >= 500_000) break
                val x = tm.groupValues[1].toFloatOrNull() ?: 0f
                val y = tm.groupValues[2].toFloatOrNull() ?: 0f
                val z = tm.groupValues[3].toFloatOrNull() ?: 0f
                verts.add(floatArrayOf(x, y, z))
            }
            if (verts.isEmpty()) continue

            val idxStr = indicesMatches[m].groupValues[1]
            val indices = idxStr.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }

            val counts = if (m < countsMatches.size) {
                countsMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
            } else emptyList()

            if (counts.isNotEmpty()) {
                var cursor = 0
                for (c in counts) {
                    if (isGeometryTruncated) break
                    if (c in 3..1024 && cursor + c <= indices.size) {
                        val i0 = indices[cursor]
                        if (i0 in verts.indices) {
                            val v0 = verts[i0]
                            for (k in 1 until c - 1) {
                                val i1 = indices[cursor + k]
                                val i2 = indices[cursor + k + 1]
                                if (i1 in verts.indices && i2 in verts.indices) {
                                    val v1 = verts[i1]
                                    val v2 = verts[i2]
                                    addTriangle(v0[0], v0[1], v0[2], v1[0], v1[1], v1[2], v2[0], v2[1], v2[2])
                                }
                            }
                        }
                    }
                    cursor += c.coerceAtLeast(1)
                }
            } else {
                var k = 0
                while (k + 2 < indices.size && !isGeometryTruncated) {
                    val i0 = indices[k]; val i1 = indices[k + 1]; val i2 = indices[k + 2]
                    if (i0 in verts.indices && i1 in verts.indices && i2 in verts.indices) {
                        addTriangle(
                            verts[i0][0], verts[i0][1], verts[i0][2],
                            verts[i1][0], verts[i1][1], verts[i1][2],
                            verts[i2][0], verts[i2][1], verts[i2][2]
                        )
                    }
                    k += 3
                }
            }
        }
    }

    private fun parseUsdcCrateBinary(bytes: ByteArray) {
        if (bytes.size < 64) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var pos = 64
        val limit = minOf(bytes.size - 36, 32 * 1024 * 1024)
        while (pos <= limit && !isGeometryTruncated) {
            val x1 = bb.getFloat(pos); val y1 = bb.getFloat(pos + 4); val z1 = bb.getFloat(pos + 8)
            val x2 = bb.getFloat(pos + 12); val y2 = bb.getFloat(pos + 16); val z2 = bb.getFloat(pos + 20)
            val x3 = bb.getFloat(pos + 24); val y3 = bb.getFloat(pos + 28); val z3 = bb.getFloat(pos + 32)

            if (isPlausibleMeshFloat(x1) && isPlausibleMeshFloat(y1) && isPlausibleMeshFloat(z1) &&
                isPlausibleMeshFloat(x2) && isPlausibleMeshFloat(y2) && isPlausibleMeshFloat(z2) &&
                isPlausibleMeshFloat(x3) && isPlausibleMeshFloat(y3) && isPlausibleMeshFloat(z3)
            ) {
                val d12 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1)
                val d23 = (x3 - x2) * (x3 - x2) + (y3 - y2) * (y3 - y2) + (z3 - z2) * (z3 - z2)
                if (d12 in 1e-5f..1e6f && d23 in 1e-5f..1e6f) {
                    addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3)
                    pos += 36
                    continue
                }
            }
            pos += 4
        }
    }

    private fun isPlausibleMeshFloat(v: Float): Boolean {
        if (!v.isFinite()) return false
        val a = abs(v)
        return a == 0f || (a in 1e-4f..5000f)
    }

    /**
     * Full Clean-Room parser for Autodesk FBX (`.fbx` Binary v7000–v7700+ and ASCII) 3D models.
     * Parses the binary FBX node hierarchy (`Objects` -> `Geometry` -> `Vertices` & `PolygonVertexIndex`),
     * decompresses zlib-deflated property arrays (`encoding == 1`), and triangulates all polygon loops.
     */
    private fun parseFbxCleanRoom() {
        val bytes = readDescriptorBytes(64 * 1024 * 1024)
        if (bytes.size < 32) return
        val headAscii = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.US_ASCII)
        if (!headAscii.startsWith("Kaydara FBX Binary")) {
            val text = String(bytes, StandardCharsets.UTF_8)
            val vertsMatches = Regex("""Vertices:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val idxMatches = Regex("""PolygonVertexIndex:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val meshCount = minOf(vertsMatches.size, idxMatches.size)
            for (m in 0 until meshCount) {
                if (isGeometryTruncated) break
                val coords = vertsMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                val indices = idxMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }.toIntArray()
                emitFbxMeshFromArrays(coords, indices)
            }
            return
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        val is64Bit = version >= 7500
        modelType = "FBX Binary (v$version)"

        fun inflateFbxArray(offset: Int, compressedLen: Int, expectedBytes: Int): ByteArray? {
            if (offset < 0 || compressedLen <= 0 || offset + compressedLen > bytes.size) return null
            if (expectedBytes <= 0 || expectedBytes > 48 * 1024 * 1024) return null
            for (nowrap in booleanArrayOf(false, true)) {
                val inflater = Inflater(nowrap)
                try {
                    inflater.setInput(bytes, offset, compressedLen)
                    val out = ByteArray(expectedBytes)
                    var pos = 0
                    while (!inflater.finished() && pos < expectedBytes) {
                        val r = inflater.inflate(out, pos, expectedBytes - pos)
                        if (r <= 0) break
                        pos += r
                    }
                    if (pos > 0) return out
                } catch (_: Throwable) {
                } finally {
                    try { inflater.end() } catch (_: Throwable) {}
                }
            }
            return null
        }

        fun readFbxFloatArrayProp(propPos: Int, propEnd: Int): FloatArray? {
            if (propPos + 13 > propEnd) return null
            val typeChar = bytes[propPos].toInt().toChar()
            if (typeChar != 'd' && typeChar != 'f') return null
            val elemSize = if (typeChar == 'd') 8 else 4
            val arrayLen = bb.getInt(propPos + 1).coerceIn(0, 1_500_000)
            val encoding = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dataStart = propPos + 13
            if (arrayLen < 3) return null

            val rawBytes: ByteArray = if (encoding == 0) {
                val needed = arrayLen * elemSize
                if (dataStart + needed > bytes.size) return null
                bytes.copyOfRange(dataStart, dataStart + needed)
            } else {
                inflateFbxArray(dataStart, compLen, arrayLen * elemSize) ?: return null
            }

            val rbb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = minOf(arrayLen, rawBytes.size / elemSize)
            return FloatArray(count) { i ->
                val v = if (elemSize == 8) rbb.getDouble(i * 8).toFloat() else rbb.getFloat(i * 4)
                if (v.isFinite()) v else 0f
            }
        }

        fun readFbxIntArrayProp(propPos: Int, propEnd: Int): IntArray? {
            if (propPos + 13 > propEnd) return null
            val typeChar = bytes[propPos].toInt().toChar()
            if (typeChar != 'i') return null
            val arrayLen = bb.getInt(propPos + 1).coerceIn(0, 3_000_000)
            val encoding = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dataStart = propPos + 13
            if (arrayLen < 3) return null

            val rawBytes: ByteArray = if (encoding == 0) {
                val needed = arrayLen * 4
                if (dataStart + needed > bytes.size) return null
                bytes.copyOfRange(dataStart, dataStart + needed)
            } else {
                inflateFbxArray(dataStart, compLen, arrayLen * 4) ?: return null
            }

            val rbb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = minOf(arrayLen, rawBytes.size / 4)
            return IntArray(count) { i -> rbb.getInt(i * 4) }
        }

        var activeGeomVertices: FloatArray? = null
        var activeGeomPolyIndices: IntArray? = null

        fun flushActiveFbxGeom() {
            val v = activeGeomVertices
            val idx = activeGeomPolyIndices
            if (v != null && idx != null) {
                emitFbxMeshFromArrays(v, idx)
            }
            activeGeomVertices = null
            activeGeomPolyIndices = null
        }

        fun walkFbxNodes(startOffset: Int, endLimit: Int, depth: Int) {
            if (depth > 24 || isGeometryTruncated) return
            var pos = startOffset
            val minHeader = if (is64Bit) 25 else 13

            while (pos + minHeader <= endLimit && !isGeometryTruncated) {
                val endOffset: Int
                val numProps: Int
                val propListLen: Int
                val nameLen: Int
                val nameStart: Int

                if (is64Bit) {
                    endOffset = bb.getLong(pos).toInt()
                    numProps = bb.getLong(pos + 8).toInt()
                    propListLen = bb.getLong(pos + 16).toInt()
                    nameLen = bytes[pos + 24].toInt() and 0xFF
                    nameStart = pos + 25
                } else {
                    endOffset = bb.getInt(pos)
                    numProps = bb.getInt(pos + 4)
                    propListLen = bb.getInt(pos + 8)
                    nameLen = bytes[pos + 12].toInt() and 0xFF
                    nameStart = pos + 13
                }

                if (endOffset == 0) break // Null sentinel record at end of nested block
                if (endOffset <= pos || endOffset > bytes.size || nameStart + nameLen > endOffset) break

                val nodeName = if (nameLen > 0) String(bytes, nameStart, nameLen, StandardCharsets.US_ASCII) else ""
                val propsStart = nameStart + nameLen
                val childrenStart = propsStart + propListLen

                when (nodeName) {
                    "Geometry" -> {
                        flushActiveFbxGeom()
                        if (childrenStart < endOffset) {
                            walkFbxNodes(childrenStart, endOffset, depth + 1)
                        }
                        flushActiveFbxGeom()
                    }
                    "Vertices" -> {
                        if (numProps >= 1 && propsStart < endOffset) {
                            activeGeomVertices = readFbxFloatArrayProp(propsStart, endOffset)
                        }
                    }
                    "PolygonVertexIndex" -> {
                        if (numProps >= 1 && propsStart < endOffset) {
                            activeGeomPolyIndices = readFbxIntArrayProp(propsStart, endOffset)
                        }
                    }
                    else -> {
                        if (childrenStart in (propsStart)..(endOffset - minHeader)) {
                            walkFbxNodes(childrenStart, endOffset, depth + 1)
                        }
                    }
                }

                pos = endOffset
            }
        }

        walkFbxNodes(27, bytes.size, 0)
        flushActiveFbxGeom()
    }

    private fun emitFbxMeshFromArrays(coords: FloatArray, indices: IntArray) {
        val vCount = coords.size / 3
        if (vCount < 3 || indices.size < 3) return
        val polyVerts = IntArray(64)
        var polyLen = 0

        for (rawIdx in indices) {
            if (isGeometryTruncated) break
            val isEnd = rawIdx < 0
            val actualIdx = if (isEnd) rawIdx.inv() else rawIdx
            if (polyLen < polyVerts.size) {
                polyVerts[polyLen++] = actualIdx
            }
            if (isEnd) {
                if (polyLen >= 3) {
                    val i0 = polyVerts[0]
                    if (i0 in 0 until vCount) {
                        val b0 = i0 * 3
                        for (k in 1 until polyLen - 1) {
                            val i1 = polyVerts[k]
                            val i2 = polyVerts[k + 1]
                            if (i1 in 0 until vCount && i2 in 0 until vCount) {
                                val b1 = i1 * 3
                                val b2 = i2 * 3
                                addTriangle(
                                    coords[b0], coords[b0 + 1], coords[b0 + 2],
                                    coords[b1], coords[b1 + 1], coords[b1 + 2],
                                    coords[b2], coords[b2 + 1], coords[b2 + 2]
                                )
                            }
                        }
                    }
                }
                polyLen = 0
            }
        }
    }

    /**
     * Clean-Room parser for 3D Manufacturing Format (`.3mf` ZIP + XML `<mesh><vertices><triangles>`).
     */
    private fun parse3mfCleanRoom() {
        val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 256L * 1024L * 1024L }
        try {
            openSafeStream { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var totalUnpacked = 0L
                    val buf = ByteArray(16384)
                    while (!isGeometryTruncated) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        val name = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                        if (!entry.isDirectory && (name.endsWith(".model") || (name.startsWith("3d/") && name.endsWith(".xml")))) {
                            modelType = "3MF 3D Model"
                            val bos = ByteArrayOutputStream()
                            var entryRead = 0
                            while (entryRead < 48 * 1024 * 1024) {
                                val r = zis.read(buf)
                                if (r <= 0) break
                                bos.write(buf, 0, r)
                                entryRead += r
                                totalUnpacked += r
                                if (totalUnpacked > 48 * 1024 * 1024L && totalUnpacked / fileSize.coerceAtLeast(1L) > 150L) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия 3MF.")
                                }
                            }
                            parse3mfModelXml(String(bos.toByteArray(), StandardCharsets.UTF_8))
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parse3mfModelXml(xmlText: String) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlText))

            var vertCoords = FloatArray(30_000)
            var vertCount = 0
            val colorGroups = HashMap<String, MutableList<Int>>()
            var activeGroupId = ""
            var defaultObjectColor = 0
            var event = parser.eventType

            fun parse3mfHexColor(hex: String?): Int {
                if (hex.isNullOrBlank() || !hex.startsWith("#")) return 0
                val clean = hex.removePrefix("#").trim()
                val rgb = clean.take(6).toIntOrNull(16) ?: return 0
                return (0xFF shl 24) or rgb
            }

            while (event != XmlPullParser.END_DOCUMENT && !isGeometryTruncated) {
                if (event == XmlPullParser.START_TAG) {
                    val localName = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                    when (localName) {
                        "basematerials", "colorgroup" -> {
                            activeGroupId = parser.getAttributeValue(null, "id") ?: ""
                            if (activeGroupId.isNotEmpty()) colorGroups[activeGroupId] = ArrayList()
                        }
                        "base", "color" -> {
                            val colStr = parser.getAttributeValue(null, "displaycolor") ?: parser.getAttributeValue(null, "color")
                            val col = parse3mfHexColor(colStr)
                            if (activeGroupId.isNotEmpty() && col != 0) colorGroups[activeGroupId]?.add(col)
                        }
                        "object" -> {
                            val pid = parser.getAttributeValue(null, "pid") ?: ""
                            val pindex = parser.getAttributeValue(null, "pindex")?.toIntOrNull() ?: 0
                            defaultObjectColor = colorGroups[pid]?.getOrNull(pindex) ?: 0
                        }
                        "mesh" -> vertCount = 0
                        "vertex" -> {
                            val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                            val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                            val z = parser.getAttributeValue(null, "z")?.toFloatOrNull() ?: 0f
                            if ((vertCount + 1) * 3 > vertCoords.size && vertCoords.size < 6_000_000) {
                                vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            }
                            if ((vertCount + 1) * 3 <= vertCoords.size) {
                                val base = vertCount * 3
                                vertCoords[base] = x; vertCoords[base + 1] = y; vertCoords[base + 2] = z
                                vertCount++
                            }
                        }
                        "triangle" -> {
                            val v1 = parser.getAttributeValue(null, "v1")?.toIntOrNull() ?: -1
                            val v2 = parser.getAttributeValue(null, "v2")?.toIntOrNull() ?: -1
                            val v3 = parser.getAttributeValue(null, "v3")?.toIntOrNull() ?: -1
                            val pid = parser.getAttributeValue(null, "pid")
                            val p1 = parser.getAttributeValue(null, "p1")?.toIntOrNull()
                            val triCol = if (p1 != null) {
                                val grp = if (pid != null) colorGroups[pid] else colorGroups.values.firstOrNull()
                                grp?.getOrNull(p1) ?: defaultObjectColor
                            } else defaultObjectColor
                            if (v1 in 0 until vertCount && v2 in 0 until vertCount && v3 in 0 until vertCount) {
                                val b1 = v1 * 3; val b2 = v2 * 3; val b3 = v3 * 3
                                addTriangle(
                                    vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2],
                                    vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2],
                                    vertCoords[b3], vertCoords[b3 + 1], vertCoords[b3 + 2],
                                    triCol
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for Khronos COLLADA (`.dae` XML) 3D models.
     */
    private fun parseColladaDaeCleanRoom() {
        val bytes = readDescriptorBytes(48 * 1024 * 1024)
        if (bytes.isEmpty()) return
        val xml = String(bytes, StandardCharsets.UTF_8)
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val floatArraysById = HashMap<String, FloatArray>()
            val sourceToArrayId = HashMap<String, String>()
            val verticesToSourceId = HashMap<String, String>()

            var currentSourceId = ""
            var currentVerticesId = ""
            var currentFloatArrayId = ""
            var inPrimitiveTag = false
            var primitiveType = ""
            var vertexInputOffset = 0
            var maxInputOffset = 0
            var currentVertexSourceId = ""
            val vcountList = ArrayList<Int>()

            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT && !isGeometryTruncated) {
                val tag = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "source" -> currentSourceId = parser.getAttributeValue(null, "id") ?: ""
                            "float_array" -> {
                                currentFloatArrayId = parser.getAttributeValue(null, "id") ?: ""
                                if (currentSourceId.isNotEmpty() && currentFloatArrayId.isNotEmpty()) {
                                    sourceToArrayId[currentSourceId] = currentFloatArrayId
                                }
                                val txt = parser.nextText()
                                val floats = txt.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                                if (currentFloatArrayId.isNotEmpty()) floatArraysById[currentFloatArrayId] = floats
                                if (currentSourceId.isNotEmpty()) floatArraysById[currentSourceId] = floats
                            }
                            "vertices" -> currentVerticesId = parser.getAttributeValue(null, "id") ?: ""
                            "Triangles", "triangles", "polylist", "polygons", "trifans", "tristrips" -> {
                                inPrimitiveTag = true
                                primitiveType = tag
                                vertexInputOffset = 0
                                maxInputOffset = 0
                                vcountList.clear()
                            }
                            "input" -> {
                                val semantic = parser.getAttributeValue(null, "semantic")?.uppercase(Locale.ROOT) ?: ""
                                val src = (parser.getAttributeValue(null, "source") ?: "").removePrefix("#")
                                val offset = parser.getAttributeValue(null, "offset")?.toIntOrNull() ?: 0
                                if (inPrimitiveTag) {
                                    if (offset > maxInputOffset) maxInputOffset = offset
                                    if (semantic == "VERTEX" || semantic == "POSITION") {
                                        vertexInputOffset = offset
                                        currentVertexSourceId = verticesToSourceId[src] ?: src
                                    }
                                } else if (currentVerticesId.isNotEmpty() && semantic == "POSITION") {
                                    verticesToSourceId[currentVerticesId] = src
                                }
                            }
                            "vcount" -> {
                                val txt = parser.nextText()
                                txt.trim().split(Regex("\\s+")).mapNotNullTo(vcountList) { it.toIntOrNull() }
                            }
                            "p" -> if (inPrimitiveTag) {
                                val txt = parser.nextText()
                                val ints = txt.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.toIntArray()
                                val coords = floatArraysById[currentVertexSourceId]
                                    ?: floatArraysById[sourceToArrayId[currentVertexSourceId]]
                                    ?: floatArraysById.values.firstOrNull()
                                if (coords != null && coords.size >= 9) {
                                    val stride = (maxInputOffset + 1).coerceAtLeast(1)
                                    val vCount = coords.size / 3
                                    if (primitiveType == "polylist" && vcountList.isNotEmpty()) {
                                        var cursor = 0
                                        for (vc in vcountList) {
                                            if (isGeometryTruncated) break
                                            if (vc >= 3 && (cursor + vc) * stride <= ints.size) {
                                                val i0 = ints[cursor * stride + vertexInputOffset]
                                                if (i0 in 0 until vCount) {
                                                    val b0 = i0 * 3
                                                    for (k in 1 until vc - 1) {
                                                        val i1 = ints[(cursor + k) * stride + vertexInputOffset]
                                                        val i2 = ints[(cursor + k + 1) * stride + vertexInputOffset]
                                                        if (i1 in 0 until vCount && i2 in 0 until vCount) {
                                                            val b1 = i1 * 3; val b2 = i2 * 3
                                                            addTriangle(
                                                                coords[b0], coords[b0 + 1], coords[b0 + 2],
                                                                coords[b1], coords[b1 + 1], coords[b1 + 2],
                                                                coords[b2], coords[b2 + 1], coords[b2 + 2]
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            cursor += vc.coerceAtLeast(1)
                                        }
                                    } else {
                                        val totalVertRefs = ints.size / stride
                                        var t = 0
                                        while (t + 2 < totalVertRefs && !isGeometryTruncated) {
                                            val i0 = ints[t * stride + vertexInputOffset]
                                            val i1 = ints[(t + 1) * stride + vertexInputOffset]
                                            val i2 = ints[(t + 2) * stride + vertexInputOffset]
                                            if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                                                val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                                                addTriangle(
                                                    coords[b0], coords[b0 + 1], coords[b0 + 2],
                                                    coords[b1], coords[b1 + 1], coords[b1 + 2],
                                                    coords[b2], coords[b2 + 1], coords[b2 + 2]
                                                )
                                            }
                                            t += 3
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag in setOf("triangles", "polylist", "polygons", "trifans", "tristrips")) {
                            inPrimitiveTag = false
                        } else if (tag == "vertices") {
                            currentVerticesId = ""
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for ISO 10303-21 STEP (`.step` / `.stp`) CAD models.
     * Extracts `CARTESIAN_POINT`, `VERTEX_POINT`, `EDGE_CURVE`, `POLY_LOOP`, `B_SPLINE_CURVE_WITH_KNOTS`,
     * and triangulates faceted B-Rep loops and wireframe topology.
     */
    private fun parseStepCleanRoom(stream: InputStream) {
        val cartesianPoints = HashMap<Int, FloatArray>(8192)
        val vertexPointToCartesian = HashMap<Int, Int>(4096)
        val edgePairs = ArrayList<Pair<Int, Int>>(4096)
        val polyLoops = ArrayList<IntArray>(1024)

        val entityRegex = Regex("""#(\d+)\s*=\s*([A-Z0-9_]+)\s*\(([\s\S]*)\)\s*;""")
        val coordTupleRegex = Regex("""\(\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)(?:\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?))?\s*\)""")
        val refRegex = Regex("""#(\d+)""")

        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val stmtSb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null && cartesianPoints.size < 600_000) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("/*")) continue
                stmtSb.append(trimmed)
                if (!trimmed.endsWith(";")) {
                    if (stmtSb.length > 65536) stmtSb.setLength(0)
                    continue
                }
                val stmt = stmtSb.toString()
                stmtSb.setLength(0)

                val m = entityRegex.find(stmt) ?: continue
                val id = m.groupValues[1].toIntOrNull() ?: continue
                val type = m.groupValues[2]
                val args = m.groupValues[3]

                when (type) {
                    "CARTESIAN_POINT" -> {
                        val cm = coordTupleRegex.find(args)
                        if (cm != null) {
                            val x = cm.groupValues[1].toFloatOrNull() ?: 0f
                            val y = cm.groupValues[2].toFloatOrNull() ?: 0f
                            val z = cm.groupValues[3].toFloatOrNull() ?: 0f
                            cartesianPoints[id] = floatArrayOf(x, y, z)
                        }
                    }
                    "VERTEX_POINT" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                        if (refs.isNotEmpty()) {
                            vertexPointToCartesian[id] = refs.last()
                        }
                    }
                    "EDGE_CURVE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                        if (refs.size >= 2 && edgePairs.size < 200_000) {
                            edgePairs.add(refs[0] to refs[1])
                        }
                    }
                    "POLY_LOOP", "B_SPLINE_CURVE_WITH_KNOTS", "BEZIER_CURVE", "POLYLINE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList().toIntArray()
                        if (refs.size >= 2 && polyLoops.size < 100_000) {
                            polyLoops.add(refs)
                        }
                    }
                }
            }
        }

        fun resolvePt(id: Int): FloatArray? {
            return cartesianPoints[id] ?: vertexPointToCartesian[id]?.let { cartesianPoints[it] }
        }

        val cadColor = aciToColor(4)
        for (loop in polyLoops) {
            if (isGeometryTruncated) break
            val pts = loop.map { resolvePt(it) }.filterNotNull()
            if (pts.size >= 3) {
                val p0 = pts[0]
                for (k in 1 until pts.size - 1) {
                    val p1 = pts[k]; val p2 = pts[k + 1]
                    addTriangle(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
                }
            } else if (pts.size == 2) {
                addLineSegment(pts[0][0], pts[0][1], pts[0][2], pts[1][0], pts[1][1], pts[1][2], cadColor)
            }
        }

        for ((id1, id2) in edgePairs) {
            if (isGeometryTruncated) break
            val p1 = resolvePt(id1) ?: continue
            val p2 = resolvePt(id2) ?: continue
            addLineSegment(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
        }

        // If the STEP file only has CARTESIAN_POINTs (e.g. point-cloud or NURBS control net without explicit EDGE_CURVE), connect sequential points
        if (vertexCount == 0 && cartesianPoints.size >= 2) {
            val sortedPts = cartesianPoints.entries.sortedBy { it.key }.map { it.value }
            for (i in 0 until sortedPts.size - 1) {
                if (isGeometryTruncated) break
                val p1 = sortedPts[i]; val p2 = sortedPts[i + 1]
                addLineSegment(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
            }
        }
    }

    /**
     * Clean-Room parser for Initial Graphics Exchange Specification (`.iges` / `.igs`) CAD files.
     * Parses Parameter Data (`P`) records for Entity 110 (Line), Entity 106 (Copious Data / Polyline),
     * and Entity 126 (Rational B-Spline Curve).
     */
    private fun parseIgesCleanRoom(stream: InputStream) {
        val paramStatements = ArrayList<String>()
        val currentSb = StringBuilder()

        BufferedReader(InputStreamReader(stream, StandardCharsets.US_ASCII)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null && paramStatements.size < 200_000) {
                val raw = line!!
                if (raw.length >= 73 && raw[72] == 'P') {
                    val dataSlice = raw.substring(0, 64).trim()
                    currentSb.append(dataSlice)
                    if (dataSlice.contains(';')) {
                        paramStatements.add(currentSb.toString().substringBefore(';'))
                        currentSb.setLength(0)
                    }
                }
            }
        }

        val cadColor = aciToColor(3)
        for (stmt in paramStatements) {
            if (isGeometryTruncated) break
            val nums = stmt.split(',').mapNotNull { it.trim().replace('D', 'E').replace('d', 'e').toFloatOrNull() }
            if (nums.isEmpty()) continue
            val entityType = nums[0].toInt()
            when (entityType) {
                110 -> if (nums.size >= 7) {
                    addLineSegment(nums[1], nums[2], nums[3], nums[4], nums[5], nums[6], cadColor)
                }
                106 -> if (nums.size >= 7) {
                    val ip = nums[1].toInt()
                    val n = nums[2].toInt().coerceIn(2, 10_000)
                    if (ip == 1 && nums.size >= 4 + n * 2) {
                        val z = nums[3]
                        for (i in 0 until n - 1) {
                            val base = 4 + i * 2
                            addLineSegment(nums[base], nums[base + 1], z, nums[base + 2], nums[base + 3], z, cadColor)
                        }
                    } else if (ip == 2 && nums.size >= 3 + n * 3) {
                        for (i in 0 until n - 1) {
                            val base = 3 + i * 3
                            addLineSegment(
                                nums[base], nums[base + 1], nums[base + 2],
                                nums[base + 3], nums[base + 4], nums[base + 5],
                                cadColor
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Clean-Room parser for AutoCAD `.dwg` files (`AC1012` R13 through `AC1032` R2018+) without RealDWG.
     */
    private fun parseDwgCleanRoom() {
        val statSize = resolveDescriptorFileSize()
        val maxRead = minOf(if (statSize > 0L) statSize else 32L * 1024L * 1024L, 32L * 1024L * 1024L).toInt()
        if (maxRead < 32) {
            lastErrorMsg = "DWG file is empty or truncated (< 32 bytes)."
            return
        }

        val bytes = readDescriptorSliceAt(0L, maxRead)
        val totalRead = bytes.size
        if (totalRead < 18) {
            lastErrorMsg = "Unable to read DWG header."
            return
        }

        val verCode = String(bytes, 0, 6, StandardCharsets.US_ASCII)
        if (!verCode.startsWith("AC10")) {
            val sampleStr = String(bytes, 0, minOf(totalRead, 512), StandardCharsets.UTF_8).lowercase(Locale.ROOT)
            if (sampleStr.contains("section") || sampleStr.contains("entities")) {
                modelType = "DXF"
                openSafeStream { parseDxf(it) }
                return
            }
            lastErrorMsg = "File does not have a valid AutoCAD DWG signature (AC10xx)."
            return
        }

        val releaseName = when (verCode) {
            "AC1012" -> "AutoCAD R13"
            "AC1014" -> "AutoCAD R14"
            "AC1015" -> "AutoCAD 2000"
            "AC1018" -> "AutoCAD 2004"
            "AC1021" -> "AutoCAD 2007"
            "AC1024" -> "AutoCAD 2010"
            "AC1027" -> "AutoCAD 2013"
            "AC1032" -> "AutoCAD 2018+"
            else -> "AutoCAD ($verCode)"
        }
        modelType = "DWG ($releaseName)"

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val previewAddr = bb.getInt(0x0D)
        var sentinelPos = -1
        if (previewAddr in 0x14..(totalRead - 32)) {
            if (matchesDwgSentinelAt(bytes, previewAddr)) {
                sentinelPos = previewAddr
            }
        }
        if (sentinelPos < 0) {
            sentinelPos = findDwgImageSentinel(bytes)
        }
        if (sentinelPos >= 0) {
            dwgPreviewBitmap = decodeDwgPreviewAtSentinel(bytes, bb, sentinelPos)
        }
        if (dwgPreviewBitmap == null) {
            dwgPreviewBitmap = scanForEmbeddedPngOrBmpInDwg(bytes)
        }

        val extractedLabels = extractDwgStrings(bytes, isUnicodeVersion = verCode >= "AC1021")
        extractedLabels.forEachIndexed { idx, txt ->
            val row = idx / 3
            val col = idx % 3
            val lx = (col - 1) * 85f
            val ly = 75f - row * 22f
            textLabels.add(
                CadTextLabel3D(
                    text = txt,
                    x = lx,
                    y = ly,
                    z = 0f,
                    height = 6.5f,
                    rotationDeg = 0f,
                    color = aciToColor((idx % 6) + 1)
                )
            )
            updateBounds(lx, ly, 0f)
        }

        extractDwgGeometricEntities(bytes, bb, releaseName)
    }

    private fun matchesDwgSentinelAt(bytes: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 16 > bytes.size) return false
        for (i in 0 until 16) {
            if (bytes[offset + i] != DWG_IMAGE_SENTINEL[i]) return false
        }
        return true
    }

    private fun findDwgImageSentinel(bytes: ByteArray): Int {
        val limit = bytes.size - 32
        val first = DWG_IMAGE_SENTINEL[0]
        val second = DWG_IMAGE_SENTINEL[1]
        var i = 0x14
        while (i <= limit) {
            if (bytes[i] == first && bytes[i + 1] == second && matchesDwgSentinelAt(bytes, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun decodeDwgPreviewAtSentinel(bytes: ByteArray, bb: ByteBuffer, sentinelPos: Int): Bitmap? {
        val dirPos = sentinelPos + 16
        if (dirPos + 5 > bytes.size) return null
        val imageCount = bytes[dirPos + 4].toInt() and 0xFF
        if (imageCount !in 1..16) return null

        var recPos = dirPos + 5
        for (i in 0 until imageCount) {
            if (recPos + 9 > bytes.size) break
            val imgCode = bytes[recPos].toInt() and 0xFF
            val imgOffset = bb.getInt(recPos + 1)
            val imgSize = bb.getInt(recPos + 5)
            recPos += 9

            if (imgOffset <= 0 || imgSize <= 16 || imgOffset.toLong() + imgSize.toLong() > bytes.size.toLong()) {
                continue
            }

            when (imgCode) {
                6 -> {
                    BitmapFactory.decodeByteArray(bytes, imgOffset, imgSize)?.let { return it }
                }
                2 -> {
                    if (bytes[imgOffset] == 'B'.code.toByte() && bytes[imgOffset + 1] == 'M'.code.toByte()) {
                        BitmapFactory.decodeByteArray(bytes, imgOffset, imgSize)?.let { return it }
                    } else if (imgSize >= 40) {
                        val biSize = bb.getInt(imgOffset)
                        val biBitCount = bb.getShort(imgOffset + 14).toInt() and 0xFFFF
                        val biClrUsed = bb.getInt(imgOffset + 32)
                        if (biSize in 40..256 && biBitCount in setOf(1, 4, 8, 16, 24, 32)) {
                            val paletteColors = when {
                                biClrUsed > 0 -> biClrUsed.coerceAtMost(256)
                                biBitCount <= 8 -> 1 shl biBitCount
                                else -> 0
                            }
                            val bfOffBits = 14 + biSize + paletteColors * 4
                            val totalBmpSize = 14 + imgSize
                            val fullBmp = ByteArray(totalBmpSize)
                            val bmpBb = ByteBuffer.wrap(fullBmp).order(ByteOrder.LITTLE_ENDIAN)
                            fullBmp[0] = 'B'.code.toByte()
                            fullBmp[1] = 'M'.code.toByte()
                            bmpBb.putInt(2, totalBmpSize)
                            bmpBb.putInt(6, 0)
                            bmpBb.putInt(10, bfOffBits)
                            System.arraycopy(bytes, imgOffset, fullBmp, 14, imgSize)
                            BitmapFactory.decodeByteArray(fullBmp, 0, fullBmp.size)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun scanForEmbeddedPngOrBmpInDwg(bytes: ByteArray): Bitmap? {
        for (i in 0x14 until (bytes.size - 64)) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                var end = i + 8
                while (end + 8 <= bytes.size) {
                    if (bytes[end] == 'I'.code.toByte() &&
                        bytes[end + 1] == 'E'.code.toByte() &&
                        bytes[end + 2] == 'N'.code.toByte() &&
                        bytes[end + 3] == 'D'.code.toByte()
                    ) {
                        end += 8
                        break
                    }
                    end++
                }
                val len = (end - i).coerceAtMost(bytes.size - i)
                if (len in 64..(8 * 1024 * 1024)) {
                    BitmapFactory.decodeByteArray(bytes, i, len)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractDwgStrings(bytes: ByteArray, isUnicodeVersion: Boolean): List<String> {
        val found = LinkedHashSet<String>()
        val ignoredKeywords = setOf(
            "CONTINUOUS", "BYLAYER", "BYBLOCK", "STANDARD", "ACAD", "MODEL_SPACE", "PAPER_SPACE"
        )

        if (isUnicodeVersion) {
            var i = 0x20
            val scanLimit = minOf(bytes.size - 4, 2 * 1024 * 1024)
            while (i < scanLimit && found.size < 24) {
                val sb = StringBuilder()
                var j = i
                while (j + 1 < scanLimit && sb.length < 64) {
                    val lo = bytes[j].toInt() and 0xFF
                    val hi = bytes[j + 1].toInt() and 0xFF
                    val ch = ((hi shl 8) or lo).toChar()
                    if ((hi == 0 && lo in 32..126) || ch in '\u0400'..'\u04FF') {
                        sb.append(ch)
                        j += 2
                    } else {
                        break
                    }
                }
                if (sb.length in 4..48) {
                    val s = sb.toString().trim()
                    if (s.any { it.isLetter() } && s.uppercase(Locale.ROOT) !in ignoredKeywords) {
                        found.add(s)
                    }
                    i = j
                } else {
                    i += 2
                }
            }
        }

        var k = 0x20
        val asciiLimit = minOf(bytes.size, 2 * 1024 * 1024)
        val run = StringBuilder()
        while (k < asciiLimit && found.size < 24) {
            val b = bytes[k++].toInt() and 0xFF
            if (b in 32..126) {
                run.append(b.toChar())
            } else {
                if (run.length in 4..48) {
                    val s = run.toString().trim()
                    if (s.count { it.isLetter() } >= 3 && s.uppercase(Locale.ROOT) !in ignoredKeywords && !s.startsWith("AC10")) {
                        found.add(s)
                    }
                }
                run.setLength(0)
            }
        }

        return found.toList()
    }

    private fun extractDwgGeometricEntities(bytes: ByteArray, bb: ByteBuffer, releaseName: String) {
        val scanStart = minOf(0x100, bytes.size)
        val scanEnd = minOf(bytes.size - 48, 1024 * 1024)
        var i = scanStart
        var extractedSegments = 0
        while (i <= scanEnd && extractedSegments < 4000) {
            val x1 = bb.getDouble(i)
            val y1 = bb.getDouble(i + 8)
            val z1 = bb.getDouble(i + 16)
            val x2 = bb.getDouble(i + 24)
            val y2 = bb.getDouble(i + 32)
            val z2 = bb.getDouble(i + 40)

            val isPlausibleCoord = isPlausibleCadDouble(x1) && isPlausibleCadDouble(y1) && isPlausibleCadDouble(z1) &&
                    isPlausibleCadDouble(x2) && isPlausibleCadDouble(y2) && isPlausibleCadDouble(z2)
            if (isPlausibleCoord) {
                val dx = (x2 - x1).toFloat()
                val dy = (y2 - y1).toFloat()
                val dz = (z2 - z1).toFloat()
                val distSq = dx * dx + dy * dy + dz * dz
                if (distSq in 0.25f..1_000_000f) {
                    addLineSegment(
                        x1.toFloat(), y1.toFloat(), z1.toFloat(),
                        x2.toFloat(), y2.toFloat(), z2.toFloat(),
                        aciToColor((extractedSegments % 5) + 1)
                    )
                    extractedSegments++
                    i += 48
                    continue
                }
            }
            i += 8
        }

        if (vertexCount == 0) {
            val w = 160f
            val h = 110f
            val frameColor = aciToColor(4)
            val gridColor = aciToColor(8)
            addLineSegment(-w, -h, 0f, w, -h, 0f, frameColor)
            addLineSegment(w, -h, 0f, w, h, 0f, frameColor)
            addLineSegment(w, h, 0f, -w, h, 0f, frameColor)
            addLineSegment(-w, h, 0f, -w, -h, 0f, frameColor)

            for (gx in -120..120 step 40) {
                addLineSegment(gx.toFloat(), -h, 0f, gx.toFloat(), h, 0f, gridColor)
            }
            for (gy in -80..80 step 40) {
                addLineSegment(-w, gy.toFloat(), 0f, w, gy.toFloat(), 0f, gridColor)
            }
            if (textLabels.isEmpty()) {
                textLabels.add(CadTextLabel3D(releaseName, -w + 12f, h - 18f, 0f, 10f, 0f, aciToColor(2)))
            }
        }
    }

    private fun isPlausibleCadDouble(v: Double): Boolean {
        if (!v.isFinite()) return false
        val a = abs(v)
        return a == 0.0 || (a in 0.01..50_000.0)
    }

    private fun updateBounds(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    private fun addTriangle(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        customColor: Int = 0
    ) {
        if (!x1.isFinite() || !y1.isFinite() || !z1.isFinite() ||
            !x2.isFinite() || !y2.isFinite() || !z2.isFinite() ||
            !x3.isFinite() || !y3.isFinite() || !z3.isFinite()
        ) return

        if (vertexCount + 9 > maxVertexFloats) {
            isGeometryTruncated = true
            return
        }
        if (vertexCount + 9 > rawVertices.size) {
            val nextSize = minOf(maxVertexFloats, maxOf(rawVertices.size * 2, vertexCount + 9))
            rawVertices = rawVertices.copyOf(nextSize)
        }
        val polyIndex = vertexCount / 9
        if (polyIndex >= polyColors.size) {
            val nextPolyCap = minOf(maxPolygonsLimit, maxOf(polyColors.size * 2, polyIndex + 1024))
            polyColors = polyColors.copyOf(nextPolyCap)
        }
        polyColors[polyIndex] = customColor

        rawVertices[vertexCount++] = x1; rawVertices[vertexCount++] = y1; rawVertices[vertexCount++] = z1
        rawVertices[vertexCount++] = x2; rawVertices[vertexCount++] = y2; rawVertices[vertexCount++] = z2
        rawVertices[vertexCount++] = x3; rawVertices[vertexCount++] = y3; rawVertices[vertexCount++] = z3

        updateBounds(x1, y1, z1)
        updateBounds(x2, y2, z2)
        updateBounds(x3, y3, z3)
    }

    private fun addLineSegment(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        customColor: Int = 0
    ) {
        addTriangle(x1, y1, z1, x2, y2, z2, x1, y1, z1, customColor)
    }

    private fun readExactBytes(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var totalRead = 0
        while (totalRead < length) {
            val read = stream.read(buffer, totalRead, length - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return totalRead == length
    }

    /**
     * Zero-OOM direct `Os.pread` Binary STL parser.
     * Supports arbitrary file sizes (including 500 MB+ STL meshes) with uniform stride decimation
     * when `totalTriangles > maxPolygonsLimit` so the entire 3D model is preserved without missing halves.
     */
    private fun parseBinaryStlHeaderDefaultColor(hdr: ByteArray): Int {
        if (hdr.size < 80) return 0
        val ascii = String(hdr, 0, 80, StandardCharsets.ISO_8859_1)
        val colorIdx = ascii.indexOf("COLOR=", ignoreCase = true)
        if (colorIdx >= 0 && colorIdx + 10 <= 80) {
            val r = hdr[colorIdx + 6].toInt() and 0xFF
            val g = hdr[colorIdx + 7].toInt() and 0xFF
            val b = hdr[colorIdx + 8].toInt() and 0xFF
            val a = (hdr[colorIdx + 9].toInt() and 0xFF).let { if (it == 0) 255 else it }
            if (r + g + b > 0) {
                return Color.argb(a, r, g, b)
            }
        }
        return 0
    }

    private fun parseBinaryStlPread(exactFileSize: Long) {
        val hdr = readDescriptorSliceAt(0L, 84)
        if (hdr.size < 84) return
        val headerDefaultColor = parseBinaryStlHeaderDefaultColor(hdr)
        val headerCount = ByteBuffer.wrap(hdr, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val computedByFileSize = if (exactFileSize >= 134L) (exactFileSize - 84L) / 50L else 0L
        val totalTris = when {
            computedByFileSize > 0L && (headerCount <= 0L || headerCount > computedByFileSize + 1000L) -> computedByFileSize
            headerCount in 1L..50_000_000L -> if (computedByFileSize > 0L) minOf(headerCount, computedByFileSize) else headerCount
            else -> computedByFileSize
        }
        if (totalTris <= 0L) return

        val needsDecimation = totalTris > maxPolygonsLimit
        if (needsDecimation) {
            isGeometryTruncated = true
        }
        var decimationAcc = 0L

        val batchTris = 2048
        val directBuf = ByteBuffer.allocateDirect(batchTris * 50).order(ByteOrder.LITTLE_ENDIAN)

        var triIdx = 0L
        while (triIdx < totalTris && vertexCount + 9 <= maxVertexFloats) {
            val countToRead = minOf(batchTris.toLong(), totalTris - triIdx).toInt()
            directBuf.clear()
            directBuf.limit(countToRead * 50)
            val got = try { Os.pread(pfd.fileDescriptor, directBuf, 84L + triIdx * 50L) } catch (_: Throwable) { -1 }
            if (got < 50) break
            directBuf.flip()
            val actualTris = got / 50
            for (i in 0 until actualTris) {
                if (needsDecimation) {
                    decimationAcc += maxPolygonsLimit.toLong()
                    if (decimationAcc >= totalTris) {
                        decimationAcc -= totalTris
                    } else {
                        continue
                    }
                }
                if (vertexCount + 9 > maxVertexFloats) break
                val base = i * 50
                val x1 = directBuf.getFloat(base + 12)
                val y1 = directBuf.getFloat(base + 16)
                val z1 = directBuf.getFloat(base + 20)
                val x2 = directBuf.getFloat(base + 24)
                val y2 = directBuf.getFloat(base + 28)
                val z2 = directBuf.getFloat(base + 32)
                val x3 = directBuf.getFloat(base + 36)
                val y3 = directBuf.getFloat(base + 40)
                val z3 = directBuf.getFloat(base + 44)
                val attr = directBuf.getShort(base + 48).toInt() and 0xFFFF
                val col = if ((attr and 0x8000) != 0) {
                    val r = ((attr and 0x1F) * 255) / 31
                    val g = (((attr ushr 5) and 0x1F) * 255) / 31
                    val b = (((attr ushr 10) and 0x1F) * 255) / 31
                    Color.rgb(r, g, b)
                } else headerDefaultColor
                addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, col)
            }
            triIdx += actualTris
        }
    }

    private fun parseAsciiStl(stream: InputStream) {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            val triVerts = FloatArray(9)
            var vInTri = 0
            var linesScanned = 0
            while (reader.readLine().also { line = it } != null && !isGeometryTruncated) {
                linesScanned++
                if (linesScanned > 500 && vertexCount == 0 && vInTri == 0) {
                    // Guard against binary files starting with "solid" that have no ASCII "vertex" lines
                    break
                }
                val p = line!!.trim()
                if (p.startsWith("vertex", ignoreCase = true)) {
                    val parts = p.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val vx = parts[1].toFloatOrNull()
                        val vy = parts[2].toFloatOrNull()
                        val vz = parts[3].toFloatOrNull()
                        if (vx == null || vy == null || vz == null) {
                            vInTri = 0
                            continue
                        }
                        triVerts[vInTri * 3] = vx
                        triVerts[vInTri * 3 + 1] = vy
                        triVerts[vInTri * 3 + 2] = vz
                        vInTri++
                        if (vInTri == 3) {
                            addTriangle(
                                triVerts[0], triVerts[1], triVerts[2],
                                triVerts[3], triVerts[4], triVerts[5],
                                triVerts[6], triVerts[7], triVerts[8]
                            )
                            vInTri = 0
                        }
                    }
                } else if (p.startsWith("endloop", ignoreCase = true) || p.startsWith("endfacet", ignoreCase = true)) {
                    vInTri = 0
                }
            }
        }
    }

    /**
     * Zero-OOM streaming Wavefront `.obj` parser.
     * Uses a compact primitive `FloatArray` (`vertCoords`) supporting up to `3,000,000` vertices
     * (36 MB flat array with zero object header overhead), so large `.obj` files (> 50–250 MB)
     * never drop vertices before reaching the `f` face definitions at the end of the file.
     */
    private fun parseObj(stream: InputStream) {
        var vertCoords = FloatArray(60_000)
        var vertColors = IntArray(20_000)
        var vertCount = 0
        var activeMtlColor = 0
        val maxVerts = 3_000_000

        fun colorFromMtlName(name: String): Int {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.contains("red") || lower.contains("crimson") -> Color.rgb(225, 65, 55)
                lower.contains("blue") || lower.contains("navy") || lower.contains("azure") -> Color.rgb(55, 135, 235)
                lower.contains("green") || lower.contains("forest") -> Color.rgb(50, 190, 90)
                lower.contains("yellow") || lower.contains("gold") || lower.contains("brass") -> Color.rgb(235, 195, 50)
                lower.contains("orange") || lower.contains("copper") || lower.contains("bronze") -> Color.rgb(225, 130, 55)
                lower.contains("white") || lower.contains("silver") || lower.contains("chrome") -> Color.rgb(225, 228, 235)
                lower.contains("black") || lower.contains("tire") || lower.contains("rubber") || lower.contains("dark") -> Color.rgb(48, 52, 60)
                lower.contains("gray") || lower.contains("grey") || lower.contains("steel") || lower.contains("iron") -> Color.rgb(150, 156, 168)
                lower.contains("wood") || lower.contains("brown") || lower.contains("leather") -> Color.rgb(165, 110, 70)
                lower.contains("skin") || lower.contains("flesh") || lower.contains("peach") -> Color.rgb(230, 180, 150)
                else -> {
                    val hue = ((abs(lower.hashCode()) % 360)).toFloat()
                    Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.88f))
                }
            }
        }

        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            var line: String?
            val faceIndices = IntArray(64)

            while (reader.readLine().also { line = it } != null && !isGeometryTruncated) {
                val p = line!!.trim()
                if (p.isEmpty() || p[0] == '#') continue
                if (p.startsWith("v ")) {
                    if (vertCount < maxVerts) {
                        val parts = p.split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val vx = parts[1].toFloatOrNull()
                            val vy = parts[2].toFloatOrNull()
                            val vz = parts[3].toFloatOrNull()
                            if (vx != null && vy != null && vz != null) {
                                if ((vertCount + 1) * 3 > vertCoords.size) {
                                    val nextCap = minOf(maxVerts * 3, vertCoords.size * 2)
                                    vertCoords = vertCoords.copyOf(nextCap)
                                    vertColors = vertColors.copyOf(nextCap / 3)
                                }
                                val base = vertCount * 3
                                vertCoords[base] = vx
                                vertCoords[base + 1] = vy
                                vertCoords[base + 2] = vz
                                if (parts.size >= 7) {
                                    val rRaw = parts[4].toFloatOrNull()
                                    val gRaw = parts[5].toFloatOrNull()
                                    val bRaw = parts[6].toFloatOrNull()
                                    if (rRaw != null && gRaw != null && bRaw != null) {
                                        val scale = if (rRaw <= 1.0f && gRaw <= 1.0f && bRaw <= 1.0f) 255f else 1f
                                        val r = (rRaw * scale).toInt().coerceIn(0, 255)
                                        val g = (gRaw * scale).toInt().coerceIn(0, 255)
                                        val b = (bRaw * scale).toInt().coerceIn(0, 255)
                                        vertColors[vertCount] = Color.rgb(maxOf(r, 20), maxOf(g, 20), maxOf(b, 24))
                                    }
                                }
                                vertCount++
                            }
                        }
                    }
                } else if (p.startsWith("usemtl ")) {
                    val mtlName = p.substring(7).trim()
                    if (mtlName.isNotEmpty()) activeMtlColor = colorFromMtlName(mtlName)
                } else if (p.startsWith("Kd ")) {
                    val parts = p.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val r = ((parts[1].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f) * 255f).toInt()
                        val g = ((parts[2].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f) * 255f).toInt()
                        val b = ((parts[3].toFloatOrNull() ?: 0.8f).coerceIn(0f, 1f) * 255f).toInt()
                        activeMtlColor = Color.rgb(maxOf(r, 20), maxOf(g, 20), maxOf(b, 24))
                    }
                } else if (p.startsWith("f ") && vertCount >= 3) {
                    val parts = p.split(Regex("\\s+"))
                    var fLen = 0
                    for (k in 1 until parts.size) {
                        if (fLen >= faceIndices.size) break
                        val tok = parts[k]
                        if (tok.isEmpty()) continue
                        val slashIdx = tok.indexOf('/')
                        val idxStr = if (slashIdx >= 0) tok.substring(0, slashIdx) else tok
                        val rawIdx = idxStr.toIntOrNull() ?: continue
                        val resolved = if (rawIdx < 0) vertCount + rawIdx else rawIdx - 1
                        if (resolved in 0 until vertCount) {
                            faceIndices[fLen++] = resolved
                        }
                    }
                    if (fLen >= 3) {
                        val idx0 = faceIndices[0]
                        val b0 = idx0 * 3
                        val x0 = vertCoords[b0]; val y0 = vertCoords[b0 + 1]; val z0 = vertCoords[b0 + 2]
                        val faceCol = if (vertColors[idx0] != 0) vertColors[idx0] else activeMtlColor
                        for (i in 1 until fLen - 1) {
                            val b1 = faceIndices[i] * 3
                            val b2 = faceIndices[i + 1] * 3
                            addTriangle(
                                x0, y0, z0,
                                vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2],
                                vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2],
                                faceCol
                            )
                        }
                    }
                } else if (p.startsWith("l ") && vertCount >= 2) {
                    val parts = p.split(Regex("\\s+"))
                    var prevIdx = -1
                    for (k in 1 until parts.size) {
                        val rawIdx = parts[k].substringBefore('/').toIntOrNull() ?: continue
                        val resolved = if (rawIdx < 0) vertCount + rawIdx else rawIdx - 1
                        if (resolved in 0 until vertCount) {
                            if (prevIdx >= 0) {
                                val b0 = prevIdx * 3
                                val b1 = resolved * 3
                                addLineSegment(
                                    vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2],
                                    vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2]
                                )
                            }
                            prevIdx = resolved
                        }
                    }
                }
            }
        }
    }

    private sealed class DxfEntityRecord(val color: Int) {
        class Line(val x1: Float, val y1: Float, val z1: Float, val x2: Float, val y2: Float, val z2: Float, color: Int) : DxfEntityRecord(color)
        class Circle(val cx: Float, val cy: Float, val cz: Float, val radius: Float, color: Int) : DxfEntityRecord(color)
        class Arc(val cx: Float, val cy: Float, val cz: Float, val radius: Float, val startDeg: Float, val endDeg: Float, color: Int) : DxfEntityRecord(color)
        class Ellipse(
            val cx: Float, val cy: Float, val cz: Float,
            val majorDx: Float, val majorDy: Float, val majorDz: Float,
            val minorToMajorRatio: Float,
            val startParamRad: Float,
            val endParamRad: Float,
            color: Int
        ) : DxfEntityRecord(color)
        class Face3D(
            val x1: Float, val y1: Float, val z1: Float,
            val x2: Float, val y2: Float, val z2: Float,
            val x3: Float, val y3: Float, val z3: Float,
            val x4: Float, val y4: Float, val z4: Float,
            val hasFourthVertex: Boolean,
            color: Int
        ) : DxfEntityRecord(color)
        class Polyline(val points: List<DxfPoint3D>, val isClosed: Boolean, color: Int) : DxfEntityRecord(color)
        class Spline(val degree: Int, val controlPoints: List<DxfPoint3D>, val fitPoints: List<DxfPoint3D>, val isClosed: Boolean, color: Int) : DxfEntityRecord(color)
        class Hatch(val loops: List<List<DxfPoint3D>>, color: Int) : DxfEntityRecord(color)
        class Dimension(
            val blockName: String,
            val defX: Float, val defY: Float, val defZ: Float,
            val ext1X: Float, val ext1Y: Float, val ext1Z: Float,
            val ext2X: Float, val ext2Y: Float, val ext2Z: Float,
            val textMidX: Float, val textMidY: Float, val textMidZ: Float,
            val labelText: String,
            color: Int
        ) : DxfEntityRecord(color)
        class Insert(
            val blockName: String,
            val tx: Float, val ty: Float, val tz: Float,
            val sx: Float, val sy: Float, val sz: Float,
            val rotDeg: Float,
            color: Int
        ) : DxfEntityRecord(color)
        class Text(val text: String, val x: Float, val y: Float, val z: Float, val height: Float, val rotDeg: Float, color: Int) : DxfEntityRecord(color)
    }

    private fun readBinaryDxfPairs(): List<Pair<Int, String>> {
        val bytes = readDescriptorBytes(48 * 1024 * 1024)
        if (bytes.size < 24) return emptyList()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pairs = ArrayList<Pair<Int, String>>(8192)
        var pos = 22 // Skip "AutoCAD Binary DXF\r\n\u001A\u0000"

        val is1ByteCode = pos + 2 <= bytes.size && bytes[pos].toInt() == 0 && bytes[pos + 1].toInt() == 'S'.code
        while (pos < bytes.size && pairs.size < 2_000_000) {
            val code: Int
            if (is1ByteCode) {
                val c0 = bytes[pos++].toInt() and 0xFF
                if (c0 == 255) {
                    if (pos + 2 > bytes.size) break
                    code = bb.getShort(pos).toInt() and 0xFFFF
                    pos += 2
                } else {
                    code = c0
                }
            } else {
                if (pos + 2 > bytes.size) break
                code = bb.getShort(pos).toInt() and 0xFFFF
                pos += 2
            }

            when (code) {
                in 0..9, in 100..105, in 300..369, in 390..399, in 410..419, in 430..439, in 470..481, in 999..1009 -> {
                    val start = pos
                    while (pos < bytes.size && bytes[pos].toInt() != 0) pos++
                    val str = String(bytes, start, pos - start, StandardCharsets.UTF_8).trim()
                    if (pos < bytes.size) pos++
                    pairs.add(code to str)
                    if (code == 0 && str.equals("EOF", ignoreCase = true)) break
                }
                in 10..59, in 110..149, in 210..239, in 460..469, in 1010..1059 -> {
                    if (pos + 8 > bytes.size) break
                    val d = bb.getDouble(pos)
                    pos += 8
                    pairs.add(code to d.toFloat().toString())
                }
                in 60..79, in 170..179, in 270..289, in 370..389, in 400..409, in 1060..1070 -> {
                    if (pos + 2 > bytes.size) break
                    val s = bb.getShort(pos).toInt()
                    pos += 2
                    pairs.add(code to s.toString())
                }
                in 80..99, in 420..429, in 440..459, 1071 -> {
                    if (pos + 4 > bytes.size) break
                    val v = bb.getInt(pos)
                    pos += 4
                    pairs.add(code to v.toString())
                }
                in 160..169 -> {
                    if (pos + 8 > bytes.size) break
                    val v = bb.getLong(pos)
                    pos += 8
                    pairs.add(code to v.toString())
                }
                in 290..299 -> {
                    if (pos + 1 > bytes.size) break
                    val b = bytes[pos++].toInt() and 0xFF
                    pairs.add(code to b.toString())
                }
                in 310..319, 1004 -> {
                    if (pos + 1 > bytes.size) break
                    val len = bytes[pos++].toInt() and 0xFF
                    pos = (pos + len).coerceAtMost(bytes.size)
                }
                else -> {
                    val start = pos
                    while (pos < bytes.size && bytes[pos].toInt() != 0) pos++
                    val str = String(bytes, start, pos - start, StandardCharsets.UTF_8).trim()
                    if (pos < bytes.size) pos++
                    pairs.add(code to str)
                }
            }
        }
        return pairs
    }

    private fun parseDxf(stream: InputStream) {
        val layerColors = HashMap<String, Int>()
        val blocksMap = HashMap<String, MutableList<DxfEntityRecord>>()
        val mainEntities = ArrayList<DxfEntityRecord>()

        val headProbe = readDescriptorSliceAt(0L, 22)
        val isBinaryDxf = headProbe.size >= 18 &&
                String(headProbe, 0, 18, StandardCharsets.US_ASCII) == "AutoCAD Binary DXF"

        val pairs: List<Pair<Int, String>> = if (isBinaryDxf) {
            modelType = "Binary DXF"
            readBinaryDxfPairs()
        } else {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
                val list = ArrayList<Pair<Int, String>>(4096)
                while (list.size < 2_000_000) {
                    val codeLine = reader.readLine() ?: break
                    val valLine = reader.readLine() ?: break
                    val code = codeLine.trim().toIntOrNull() ?: continue
                    list.add(code to valLine.trim())
                }
                list
            }
        }

        var idx = 0
        var currentSection = ""
        var activeBlockName: String? = null
        val hasExplicitSections = pairs.any { it.first == 0 && it.second.equals("SECTION", true) }

        while (idx < pairs.size) {
            val (code, value) = pairs[idx]
            if (code == 0 && value.equals("SECTION", true)) {
                idx++
                if (idx < pairs.size && pairs[idx].first == 2) {
                    currentSection = pairs[idx].second.uppercase(Locale.ROOT)
                }
                idx++
                continue
            }
            if (code == 0 && (value.equals("ENDSEC", true) || value.equals("EOF", true))) {
                currentSection = ""
                activeBlockName = null
                idx++
                continue
            }

            if (currentSection == "TABLES") {
                if (code == 0 && value.equals("LAYER", true)) {
                    idx++
                    var layerName = ""
                    var aci = 7
                    var trueColor: Int? = null
                    while (idx < pairs.size && pairs[idx].first != 0) {
                        when (pairs[idx].first) {
                            2 -> layerName = pairs[idx].second.uppercase(Locale.ROOT)
                            62 -> aci = pairs[idx].second.toIntOrNull() ?: 7
                            420 -> {
                                val rgb = pairs[idx].second.toIntOrNull()
                                if (rgb != null) trueColor = (0xFF shl 24) or (rgb and 0xFFFFFF)
                            }
                        }
                        idx++
                    }
                    if (layerName.isNotEmpty()) {
                        val resolved = trueColor ?: aciToColor(aci)
                        if (resolved != 0) layerColors[layerName] = resolved
                    }
                    continue
                }
            } else if (currentSection == "BLOCKS") {
                if (code == 0 && value.equals("BLOCK", true)) {
                    idx++
                    var bName = ""
                    while (idx < pairs.size && pairs[idx].first != 0) {
                        if (pairs[idx].first == 2 || pairs[idx].first == 3) {
                            bName = pairs[idx].second
                        }
                        idx++
                    }
                    if (bName.isNotEmpty()) {
                        activeBlockName = bName
                        blocksMap.putIfAbsent(bName, ArrayList())
                    }
                    continue
                } else if (code == 0 && value.equals("ENDBLK", true)) {
                    activeBlockName = null
                    idx++
                    continue
                } else if (code == 0 && activeBlockName != null) {
                    val targetList = blocksMap[activeBlockName]!!
                    idx = parseSingleDxfEntity(pairs, idx, layerColors, targetList)
                    continue
                }
            } else if ((currentSection == "ENTITIES" || !hasExplicitSections) && code == 0) {
                idx = parseSingleDxfEntity(pairs, idx, layerColors, mainEntities)
                continue
            }
            idx++
        }

        val identity = DxfTransform(0f, 0f, 0f, 1f, 1f, 1f, 0f)
        val visitedBlocks = HashSet<String>()
        for (entity in mainEntities) {
            if (isGeometryTruncated || dxfEmittedOpsCount >= MAX_DXF_EMIT_OPS) break
            emitDxfEntity(entity, blocksMap, identity, 0, visitedBlocks)
        }
    }

    private fun resolveEntityColor(layerName: String, aciCode: Int?, trueColorRgb: Int?, layerColors: Map<String, Int>): Int {
        if (trueColorRgb != null) return (0xFF shl 24) or (trueColorRgb and 0xFFFFFF)
        if (aciCode != null && aciCode in 1..255) {
            val c = aciToColor(aciCode)
            if (c != 0) return c
        }
        return layerColors[layerName.uppercase(Locale.ROOT)] ?: 0
    }

    private fun parseSingleDxfEntity(
        pairs: List<Pair<Int, String>>,
        startIdx: Int,
        layerColors: Map<String, Int>,
        outList: MutableList<DxfEntityRecord>
    ): Int {
        val type = pairs[startIdx].second.uppercase(Locale.ROOT)
        var idx = startIdx + 1

        if (type == "POLYLINE") {
            var flags = 0
            var layer = "0"
            var aci: Int? = null
            var trueCol: Int? = null
            while (idx < pairs.size && pairs[idx].first != 0) {
                when (pairs[idx].first) {
                    8 -> layer = pairs[idx].second
                    62 -> aci = pairs[idx].second.toIntOrNull()
                    70 -> flags = pairs[idx].second.toIntOrNull() ?: 0
                    420 -> trueCol = pairs[idx].second.toIntOrNull()
                }
                idx++
            }
            val pts = ArrayList<DxfPoint3D>()
            val faceIndices = ArrayList<IntArray>()
            while (idx < pairs.size) {
                val (c, v) = pairs[idx]
                if (c == 0 && v.equals("VERTEX", true)) {
                    idx++
                    var vx = 0f; var vy = 0f; var vz = 0f
                    var vFlags = 0
                    var f1 = 0; var f2 = 0; var f3 = 0; var f4 = 0
                    while (idx < pairs.size && pairs[idx].first != 0) {
                        when (pairs[idx].first) {
                            10 -> vx = pairs[idx].second.toFloatOrNull() ?: 0f
                            20 -> vy = pairs[idx].second.toFloatOrNull() ?: 0f
                            30 -> vz = pairs[idx].second.toFloatOrNull() ?: 0f
                            70 -> vFlags = pairs[idx].second.toIntOrNull() ?: 0
                            71 -> f1 = abs(pairs[idx].second.toIntOrNull() ?: 0)
                            72 -> f2 = abs(pairs[idx].second.toIntOrNull() ?: 0)
                            73 -> f3 = abs(pairs[idx].second.toIntOrNull() ?: 0)
                            74 -> f4 = abs(pairs[idx].second.toIntOrNull() ?: 0)
                        }
                        idx++
                    }
                    if ((vFlags and 128) != 0 && (vFlags and 64) == 0 && f1 > 0 && f2 > 0 && f3 > 0) {
                        faceIndices.add(intArrayOf(f1 - 1, f2 - 1, f3 - 1, f4 - 1))
                    } else if ((vFlags and 16) == 0 && pts.size < 100_000) {
                        pts.add(DxfPoint3D(vx, vy, vz))
                    }
                } else if (c == 0 && v.equals("SEQEND", true)) {
                    idx++
                    while (idx < pairs.size && pairs[idx].first != 0) idx++
                    break
                } else {
                    break
                }
            }
            val col = resolveEntityColor(layer, aci, trueCol, layerColors)
            if (faceIndices.isNotEmpty() && pts.size >= 3) {
                for (fi in faceIndices) {
                    val i0 = fi[0]; val i1 = fi[1]; val i2 = fi[2]; val i3 = fi[3]
                    if (i0 in pts.indices && i1 in pts.indices && i2 in pts.indices) {
                        val p0 = pts[i0]; val p1 = pts[i1]; val p2 = pts[i2]
                        val p3 = if (i3 in pts.indices) pts[i3] else p2
                        outList.add(
                            DxfEntityRecord.Face3D(
                                p0.x, p0.y, p0.z,
                                p1.x, p1.y, p1.z,
                                p2.x, p2.y, p2.z,
                                p3.x, p3.y, p3.z,
                                hasFourthVertex = i3 in pts.indices && i3 != i2,
                                color = col
                            )
                        )
                    }
                }
            } else if (pts.size >= 2) {
                outList.add(DxfEntityRecord.Polyline(pts, isClosed = (flags and 1) != 0, col))
            }
            return idx
        }

        var layerName = "0"
        var aciColor: Int? = null
        var trueColor: Int? = null

        var x1 = 0f; var y1 = 0f; var z1 = 0f
        var x2 = 0f; var y2 = 0f; var z2 = 0f
        var x3 = 0f; var y3 = 0f; var z3 = 0f
        var x4 = 0f; var y4 = 0f; var z4 = 0f
        var x5 = 0f; var y5 = 0f; var z5 = 0f
        var hasV4 = false
        var radius = 0f
        var startAngle = 0f
        var endAngle = 360f
        var startParamRad = 0f
        var endParamRad = (2.0 * PI).toFloat()
        var flags = 0
        var degree = 3
        var sx = 1f; var sy = 1f; var sz = 1f
        var textVal = ""
        var blockName = ""
        var dimMeasurement: Float? = null

        val lwPointsX = ArrayList<Float>()
        val lwPointsY = ArrayList<Float>()
        var lwElevationZ = 0f

        val splineCtrlPts = ArrayList<DxfPoint3D>()
        var curCtrlX: Float? = null; var curCtrlY: Float? = null; var curCtrlZ = 0f
        val splineFitPts = ArrayList<DxfPoint3D>()
        var curFitX: Float? = null; var curFitY: Float? = null; var curFitZ = 0f

        while (idx < pairs.size && pairs[idx].first != 0) {
            val (code, rawVal) = pairs[idx]
            when (code) {
                1 -> textVal = rawVal
                2 -> blockName = rawVal
                8 -> layerName = rawVal
                62 -> aciColor = rawVal.toIntOrNull()
                420 -> trueColor = rawVal.toIntOrNull()
                10 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    x1 = f
                    if ((type == "LWPOLYLINE" || type == "HATCH") && lwPointsX.size < 100_000) lwPointsX.add(f)
                    if (type == "SPLINE") {
                        if (curCtrlX != null && curCtrlY != null && splineCtrlPts.size < 10_000) {
                            splineCtrlPts.add(DxfPoint3D(curCtrlX!!, curCtrlY!!, curCtrlZ))
                        }
                        curCtrlX = f; curCtrlY = 0f; curCtrlZ = 0f
                    }
                }
                20 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    y1 = f
                    if ((type == "LWPOLYLINE" || type == "HATCH") && lwPointsY.size < 100_000) lwPointsY.add(f)
                    if (type == "SPLINE") curCtrlY = f
                }
                30 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    z1 = f
                    if (type == "SPLINE") curCtrlZ = f
                }
                38 -> if (type == "LWPOLYLINE") lwElevationZ = rawVal.toFloatOrNull() ?: 0f
                11 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    x2 = f
                    if (type == "HATCH" && lwPointsX.size < 100_000) lwPointsX.add(f)
                    if (type == "SPLINE") {
                        if (curFitX != null && curFitY != null && splineFitPts.size < 10_000) {
                            splineFitPts.add(DxfPoint3D(curFitX!!, curFitY!!, curFitZ))
                        }
                        curFitX = f; curFitY = 0f; curFitZ = 0f
                    }
                }
                21 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    y2 = f
                    if (type == "HATCH" && lwPointsY.size < 100_000) lwPointsY.add(f)
                    if (type == "SPLINE") curFitY = f
                }
                31 -> {
                    val f = rawVal.toFloatOrNull() ?: 0f
                    z2 = f
                    if (type == "SPLINE") curFitZ = f
                }
                12 -> x3 = rawVal.toFloatOrNull() ?: 0f
                22 -> y3 = rawVal.toFloatOrNull() ?: 0f
                32 -> z3 = rawVal.toFloatOrNull() ?: 0f
                13 -> { x4 = rawVal.toFloatOrNull() ?: 0f; hasV4 = true }
                23 -> { y4 = rawVal.toFloatOrNull() ?: 0f; hasV4 = true }
                33 -> { z4 = rawVal.toFloatOrNull() ?: 0f; hasV4 = true }
                14 -> x5 = rawVal.toFloatOrNull() ?: 0f
                24 -> y5 = rawVal.toFloatOrNull() ?: 0f
                34 -> z5 = rawVal.toFloatOrNull() ?: 0f
                40 -> radius = rawVal.toFloatOrNull() ?: 0f
                41 -> {
                    val f = rawVal.toFloatOrNull() ?: 1f
                    sx = f
                    startParamRad = f
                }
                42 -> {
                    val f = rawVal.toFloatOrNull() ?: 1f
                    sy = f
                    endParamRad = f
                    if (type == "DIMENSION") dimMeasurement = f
                }
                43 -> sz = rawVal.toFloatOrNull() ?: 1f
                50 -> startAngle = rawVal.toFloatOrNull() ?: 0f
                51 -> endAngle = rawVal.toFloatOrNull() ?: 360f
                70 -> flags = rawVal.toIntOrNull() ?: 0
                71 -> degree = (rawVal.toIntOrNull() ?: 3).coerceIn(1, 5)
            }
            idx++
        }

        if (type == "SPLINE") {
            if (curCtrlX != null && curCtrlY != null && splineCtrlPts.size < 10_000) splineCtrlPts.add(DxfPoint3D(curCtrlX!!, curCtrlY!!, curCtrlZ))
            if (curFitX != null && curFitY != null && splineFitPts.size < 10_000) splineFitPts.add(DxfPoint3D(curFitX!!, curFitY!!, curFitZ))
        }

        val entityColor = resolveEntityColor(layerName, aciColor, trueColor, layerColors)

        when (type) {
            "LINE" -> outList.add(DxfEntityRecord.Line(x1, y1, z1, x2, y2, z2, entityColor))
            "CIRCLE" -> if (radius > 0f) outList.add(DxfEntityRecord.Circle(x1, y1, z1, radius, entityColor))
            "ARC" -> if (radius > 0f) outList.add(DxfEntityRecord.Arc(x1, y1, z1, radius, startAngle, endAngle, entityColor))
            "ELLIPSE" -> {
                val majorLen = sqrt(x2 * x2 + y2 * y2 + z2 * z2)
                if (majorLen > 1e-5f) {
                    val ratio = if (radius > 0f) radius.coerceIn(0.01f, 10f) else 1f
                    outList.add(
                        DxfEntityRecord.Ellipse(
                            x1, y1, z1,
                            x2, y2, z2,
                            ratio,
                            startParamRad,
                            endParamRad,
                            entityColor
                        )
                    )
                }
            }
            "3DFACE", "SOLID", "TRACE" -> outList.add(
                DxfEntityRecord.Face3D(
                    x1, y1, z1,
                    x2, y2, z2,
                    x3, y3, z3,
                    if (hasV4) x4 else x3,
                    if (hasV4) y4 else y3,
                    if (hasV4) z4 else z3,
                    hasV4,
                    entityColor
                )
            )
            "LWPOLYLINE" -> {
                val count = minOf(lwPointsX.size, lwPointsY.size)
                if (count >= 2) {
                    val pts = ArrayList<DxfPoint3D>(count)
                    for (i in 0 until count) {
                        pts.add(DxfPoint3D(lwPointsX[i], lwPointsY[i], lwElevationZ))
                    }
                    outList.add(DxfEntityRecord.Polyline(pts, isClosed = (flags and 1) != 0, entityColor))
                }
            }
            "HATCH" -> {
                val count = minOf(lwPointsX.size, lwPointsY.size)
                if (count >= 2) {
                    val pts = ArrayList<DxfPoint3D>(count)
                    for (i in 0 until count) {
                        pts.add(DxfPoint3D(lwPointsX[i], lwPointsY[i], z1))
                    }
                    outList.add(DxfEntityRecord.Hatch(listOf(pts), entityColor))
                }
            }
            "DIMENSION" -> {
                val resolvedDimText = when {
                    textVal.isNotBlank() && textVal != "<>" -> textVal
                    dimMeasurement != null && dimMeasurement!! > 0f -> String.format(Locale.US, "%.2f", dimMeasurement)
                    else -> ""
                }
                outList.add(
                    DxfEntityRecord.Dimension(
                        blockName = blockName,
                        defX = x1, defY = y1, defZ = z1,
                        ext1X = x4, ext1Y = y4, ext1Z = z4,
                        ext2X = x5, ext2Y = y5, ext2Z = z5,
                        textMidX = x2, textMidY = y2, textMidZ = z2,
                        labelText = resolvedDimText,
                        color = entityColor
                    )
                )
            }
            "SPLINE" -> {
                if (splineCtrlPts.size >= 2 || splineFitPts.size >= 2) {
                    outList.add(DxfEntityRecord.Spline(degree, splineCtrlPts, splineFitPts, isClosed = (flags and 1) != 0, entityColor))
                }
            }
            "INSERT" -> {
                if (blockName.isNotEmpty()) {
                    outList.add(DxfEntityRecord.Insert(blockName, x1, y1, z1, sx, sy, sz, startAngle, entityColor))
                }
            }
            "TEXT", "MTEXT", "ATTRIB" -> {
                val cleanText = textVal.replace(Regex("\\\\[A-Za-z][^;]*;"), "").replace("\\P", " ").trim()
                if (cleanText.isNotEmpty()) {
                    outList.add(DxfEntityRecord.Text(cleanText, x1, y1, z1, if (radius > 0f) radius else 10f, startAngle, entityColor))
                }
            }
        }
        return idx
    }

    private data class DxfTransform(
        val tx: Float, val ty: Float, val tz: Float,
        val sx: Float, val sy: Float, val sz: Float,
        val rotDeg: Float
    ) {
        private val rad = Math.toRadians(rotDeg.toDouble())
        private val cosR = cos(rad).toFloat()
        private val sinR = sin(rad).toFloat()

        fun apply(x: Float, y: Float, z: Float): DxfPoint3D {
            val scaledX = x * sx
            val scaledY = y * sy
            val scaledZ = z * sz
            val rx = scaledX * cosR - scaledY * sinR
            val ry = scaledX * sinR + scaledY * cosR
            return DxfPoint3D(rx + tx, ry + ty, scaledZ + tz)
        }

        fun combine(child: DxfTransform): DxfTransform {
            val p = apply(child.tx, child.ty, child.tz)
            return DxfTransform(
                tx = p.x, ty = p.y, tz = p.z,
                sx = sx * child.sx, sy = sy * child.sy, sz = sz * child.sz,
                rotDeg = (rotDeg + child.rotDeg) % 360f
            )
        }
    }

    private fun emitDxfEntity(
        entity: DxfEntityRecord,
        blocks: Map<String, List<DxfEntityRecord>>,
        transform: DxfTransform,
        depth: Int,
        visitedBlocks: MutableSet<String>
    ) {
        if (depth > MAX_INSERT_DEPTH || isGeometryTruncated) return
        if (++dxfEmittedOpsCount > MAX_DXF_EMIT_OPS) {
            isGeometryTruncated = true
            return
        }

        when (entity) {
            is DxfEntityRecord.Line -> {
                val p1 = transform.apply(entity.x1, entity.y1, entity.z1)
                val p2 = transform.apply(entity.x2, entity.y2, entity.z2)
                addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, entity.color)
            }
            is DxfEntityRecord.Circle -> {
                emitArcSegments(entity.cx, entity.cy, entity.cz, entity.radius, 0f, 360f, isFullCircle = true, transform, entity.color)
            }
            is DxfEntityRecord.Arc -> {
                emitArcSegments(entity.cx, entity.cy, entity.cz, entity.radius, entity.startDeg, entity.endDeg, isFullCircle = false, transform, entity.color)
            }
            is DxfEntityRecord.Ellipse -> {
                emitEllipseSegments(entity, transform)
            }
            is DxfEntityRecord.Face3D -> {
                val v1 = transform.apply(entity.x1, entity.y1, entity.z1)
                val v2 = transform.apply(entity.x2, entity.y2, entity.z2)
                val v3 = transform.apply(entity.x3, entity.y3, entity.z3)
                val v4 = transform.apply(entity.x4, entity.y4, entity.z4)

                addTriangle(v1.x, v1.y, v1.z, v2.x, v2.y, v2.z, v3.x, v3.y, v3.z, entity.color)
                val isQuad = entity.hasFourthVertex && (
                        abs(entity.x4 - entity.x3) > 1e-5f ||
                                abs(entity.y4 - entity.y3) > 1e-5f ||
                                abs(entity.z4 - entity.z3) > 1e-5f
                        )
                if (isQuad) {
                    addTriangle(v1.x, v1.y, v1.z, v3.x, v3.y, v3.z, v4.x, v4.y, v4.z, entity.color)
                }
            }
            is DxfEntityRecord.Polyline -> {
                val pts = entity.points
                for (i in 0 until pts.size - 1) {
                    val p1 = transform.apply(pts[i].x, pts[i].y, pts[i].z)
                    val p2 = transform.apply(pts[i + 1].x, pts[i + 1].y, pts[i + 1].z)
                    addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, entity.color)
                }
                if (entity.isClosed && pts.size >= 3) {
                    val pLast = transform.apply(pts.last().x, pts.last().y, pts.last().z)
                    val pFirst = transform.apply(pts.first().x, pts.first().y, pts.first().z)
                    addLineSegment(pLast.x, pLast.y, pLast.z, pFirst.x, pFirst.y, pFirst.z, entity.color)
                }
            }
            is DxfEntityRecord.Hatch -> {
                for (loop in entity.loops) {
                    for (i in 0 until loop.size - 1) {
                        val p1 = transform.apply(loop[i].x, loop[i].y, loop[i].z)
                        val p2 = transform.apply(loop[i + 1].x, loop[i + 1].y, loop[i + 1].z)
                        addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, entity.color)
                    }
                    if (loop.size >= 3) {
                        val pLast = transform.apply(loop.last().x, loop.last().y, loop.last().z)
                        val pFirst = transform.apply(loop.first().x, loop.first().y, loop.first().z)
                        addLineSegment(pLast.x, pLast.y, pLast.z, pFirst.x, pFirst.y, pFirst.z, entity.color)
                    }
                }
            }
            is DxfEntityRecord.Dimension -> {
                val blockEntities = if (entity.blockName.isNotEmpty()) {
                    blocks[entity.blockName]
                        ?: blocks.entries.firstOrNull { it.key.equals(entity.blockName, true) }?.value
                } else null

                if (!blockEntities.isNullOrEmpty()) {
                    val normKey = entity.blockName.lowercase(Locale.ROOT)
                    if (visitedBlocks.add(normKey)) {
                        try {
                            for (child in blockEntities) {
                                if (isGeometryTruncated || dxfEmittedOpsCount >= MAX_DXF_EMIT_OPS) break
                                emitDxfEntity(child, blocks, transform, depth + 1, visitedBlocks)
                            }
                        } finally {
                            visitedBlocks.remove(normKey)
                        }
                    }
                } else {
                    val e1 = transform.apply(entity.ext1X, entity.ext1Y, entity.ext1Z)
                    val e2 = transform.apply(entity.ext2X, entity.ext2Y, entity.ext2Z)
                    val defPt = transform.apply(entity.defX, entity.defY, entity.defZ)
                    addLineSegment(e1.x, e1.y, e1.z, e2.x, e2.y, e2.z, entity.color)
                    addLineSegment(e2.x, e2.y, e2.z, defPt.x, defPt.y, defPt.z, entity.color)
                    if (entity.labelText.isNotEmpty() && textLabels.size < MAX_TEXT_LABELS_LIMIT) {
                        val mid = transform.apply(entity.textMidX, entity.textMidY, entity.textMidZ)
                        updateBounds(mid.x, mid.y, mid.z)
                        textLabels.add(CadTextLabel3D(entity.labelText, mid.x, mid.y, mid.z, 8f, 0f, entity.color))
                    }
                }
            }
            is DxfEntityRecord.Spline -> {
                val srcPts = if (entity.controlPoints.size >= 2) entity.controlPoints else entity.fitPoints
                val curvePts = evaluateBsplineCurve(srcPts, entity.degree, entity.isClosed)
                for (i in 0 until curvePts.size - 1) {
                    val p1 = transform.apply(curvePts[i].x, curvePts[i].y, curvePts[i].z)
                    val p2 = transform.apply(curvePts[i + 1].x, curvePts[i + 1].y, curvePts[i + 1].z)
                    addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, entity.color)
                }
            }
            is DxfEntityRecord.Insert -> {
                val normKey = entity.blockName.lowercase(Locale.ROOT)
                if (!visitedBlocks.add(normKey)) return
                try {
                    val blockEntities = blocks[entity.blockName]
                        ?: blocks.entries.firstOrNull { it.key.equals(entity.blockName, true) }?.value
                        ?: return
                    val childTransform = transform.combine(
                        DxfTransform(entity.tx, entity.ty, entity.tz, entity.sx, entity.sy, entity.sz, entity.rotDeg)
                    )
                    for (child in blockEntities) {
                        if (isGeometryTruncated || dxfEmittedOpsCount >= MAX_DXF_EMIT_OPS) break
                        emitDxfEntity(child, blocks, childTransform, depth + 1, visitedBlocks)
                    }
                } finally {
                    visitedBlocks.remove(normKey)
                }
            }
            is DxfEntityRecord.Text -> {
                if (textLabels.size < MAX_TEXT_LABELS_LIMIT) {
                    val pos = transform.apply(entity.x, entity.y, entity.z)
                    updateBounds(pos.x, pos.y, pos.z)
                    textLabels.add(
                        CadTextLabel3D(
                            text = entity.text,
                            x = pos.x,
                            y = pos.y,
                            z = pos.z,
                            height = entity.height * abs(transform.sy).coerceAtLeast(0.1f),
                            rotationDeg = (entity.rotDeg + transform.rotDeg) % 360f,
                            color = entity.color
                        )
                    )
                }
            }
        }
    }

    private fun emitEllipseSegments(ellipse: DxfEntityRecord.Ellipse, transform: DxfTransform) {
        val ux = ellipse.majorDx
        val uy = ellipse.majorDy
        val uz = ellipse.majorDz
        val vx = -uy * ellipse.minorToMajorRatio
        val vy = ux * ellipse.minorToMajorRatio
        val vz = 0f

        var startRad = ellipse.startParamRad
        var endRad = ellipse.endParamRad
        if (endRad <= startRad) {
            endRad += (2.0 * PI).toFloat()
        }
        val sweep = (endRad - startRad).coerceIn(0.05f, (2.0 * PI).toFloat())
        val steps = 36
        val stepRad = sweep / steps

        for (i in 0 until steps) {
            val t1 = startRad + i * stepRad
            val t2 = startRad + (i + 1) * stepRad
            val c1 = cos(t1.toDouble()).toFloat()
            val s1 = sin(t1.toDouble()).toFloat()
            val c2 = cos(t2.toDouble()).toFloat()
            val s2 = sin(t2.toDouble()).toFloat()

            val p1 = transform.apply(
                ellipse.cx + ux * c1 + vx * s1,
                ellipse.cy + uy * c1 + vy * s1,
                ellipse.cz + uz * c1 + vz * s1
            )
            val p2 = transform.apply(
                ellipse.cx + ux * c2 + vx * s2,
                ellipse.cy + uy * c2 + vy * s2,
                ellipse.cz + uz * c2 + vz * s2
            )
            addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, ellipse.color)
        }
    }

    private fun emitArcSegments(
        cx: Float, cy: Float, cz: Float,
        r: Float,
        startDeg: Float,
        endDeg: Float,
        isFullCircle: Boolean,
        transform: DxfTransform,
        color: Int
    ) {
        if (r <= 0f) return
        val sweepDeg = if (isFullCircle) {
            360f
        } else {
            var s = (endDeg - startDeg) % 360f
            if (s < 0f) s += 360f
            if (s == 0f) 360f else s
        }
        val segments = ((sweepDeg / 360f) * 32f).toInt().coerceIn(4, 48)
        val stepDeg = sweepDeg / segments.toFloat()

        for (i in 0 until segments) {
            val a1 = Math.toRadians((startDeg + i * stepDeg).toDouble()).toFloat()
            val a2 = Math.toRadians((startDeg + (i + 1) * stepDeg).toDouble()).toFloat()
            val p1 = transform.apply(cx + r * cos(a1), cy + r * sin(a1), cz)
            val p2 = transform.apply(cx + r * cos(a2), cy + r * sin(a2), cz)
            addLineSegment(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color)
        }
    }

    internal fun evaluateBsplineCurve(controlPoints: List<DxfPoint3D>, degree: Int, isClosed: Boolean): List<DxfPoint3D> {
        if (controlPoints.size <= 2) return controlPoints
        val pts = if (isClosed) controlPoints + controlPoints.take(degree.coerceAtMost(controlPoints.size)) else controlPoints
        val n = pts.size - 1
        val p = degree.coerceIn(1, n)
        val stepsPerSpan = 8
        val totalSteps = ((n - p + 1).coerceAtLeast(1) * stepsPerSpan).coerceAtMost(2048)
        val result = ArrayList<DxfPoint3D>(totalSteps + 1)

        val knotCount = n + p + 2
        val knots = FloatArray(knotCount)
        for (i in 0 until knotCount) {
            knots[i] = when {
                i <= p -> 0f
                i >= n + 1 -> (n - p + 1).toFloat()
                else -> (i - p).toFloat()
            }
        }

        val tMax = knots[n + 1]
        for (step in 0..totalSteps) {
            val t = (step.toFloat() / totalSteps.toFloat()) * tMax
            val k = if (t >= tMax) n else (p..n).firstOrNull { t >= knots[it] && t < knots[it + 1] } ?: p
            val d = Array(p + 1) { j -> pts[(k - p + j).coerceIn(0, n)] }

            for (r in 1..p) {
                for (j in p downTo r) {
                    val i = k - p + j
                    val denom = knots[i + p - r + 1] - knots[i]
                    val alpha = if (denom > 1e-6f) (t - knots[i]) / denom else 0f
                    val prev = d[j - 1]
                    val curr = d[j]
                    d[j] = DxfPoint3D(
                        x = (1f - alpha) * prev.x + alpha * curr.x,
                        y = (1f - alpha) * prev.y + alpha * curr.y,
                        z = (1f - alpha) * prev.z + alpha * curr.z
                    )
                }
            }
            result.add(d[p])
        }
        return result
    }

    @Volatile private var glesContext: Gles30OffscreenContext? = null
    @Volatile private var glesRenderer: Gles30MeshRenderer? = null

    private fun drawTextLabelsAndHud(
        canvas: Canvas,
        width: Float,
        height: Float,
        zoomFactor: Float,
        tileX: Int,
        tileY: Int,
        isYUp: Boolean,
        isLightTheme: Boolean,
        langOrdinal: Int
    ) {
        if (textLabels.isNotEmpty()) {
            val defaultLabelColor = if (isLightTheme) LIGHT_THEME_LABEL_COLOR else DARK_THEME_LABEL_COLOR
            val rangeX = (maxX - minX).let { if (it.isFinite() && it > 0f) it else 1f }
            val rangeY = (maxY - minY).let { if (it.isFinite() && it > 0f) it else 1f }
            val rangeZ = (maxZ - minZ).let { if (it.isFinite() && it > 0f) it else 1f }
            val maxRange = maxOf(rangeX, rangeY, rangeZ).coerceAtLeast(1e-4f)
            val scale = (minOf(width, height) / maxRange) * 0.90f * zoomFactor
            val centerX = if (minX.isFinite()) minX + rangeX / 2f else 0f
            val centerY = if (minY.isFinite()) minY + rangeY / 2f else 0f
            val centerZ = if (minZ.isFinite()) minZ + rangeZ / 2f else 0f
            val yawRad = tileX * (Math.PI / 180.0).toFloat()
            val pitchRad = tileY * (Math.PI / 180.0).toFloat()
            val cosY = cos(yawRad); val sinY = sin(yawRad)
            val cosP = cos(pitchRad); val sinP = sin(pitchRad)
            val perspectiveDistance = maxOf(width, height).coerceAtLeast(320f) * 2.2f
            val nearPlane = perspectiveDistance * 0.15f
            val minAllowedFinalZ = -perspectiveDistance + nearPlane

            for (lbl in textLabels) {
                val dx = lbl.x - centerX
                val dy = lbl.y - centerY
                val dz = lbl.z - centerZ
                val wx = dx
                val wy = if (isYUp) dy else dz
                val wz = if (isYUp) dz else dy
                val tx = wx * cosY + wz * sinY
                val tz = -wx * sinY + wz * cosY
                val ry = wy * cosP - tz * sinP
                val rz = wy * sinP + tz * cosP
                val finalZ = (rz * scale).coerceAtLeast(minAllowedFinalZ)
                val denom = (perspectiveDistance + finalZ).coerceAtLeast(nearPlane)
                val scale3D = (perspectiveDistance / denom).coerceIn(0.05f, 6.0f)
                val screenX = (width / 2f + (tx * scale) * scale3D).coerceIn(-width * 2f, width * 3f)
                val screenY = (height / 2f - (ry * scale) * scale3D).coerceIn(-height * 2f, height * 3f)
                val fontPx = (lbl.height * scale * scale3D).coerceIn(14f, 64f)
                textLabelPaint.textSize = fontPx
                textLabelPaint.color = if (lbl.color != 0) lbl.color else defaultLabelColor
                canvas.save()
                canvas.rotate(-lbl.rotationDeg, screenX, screenY)
                canvas.drawText(lbl.text, screenX, screenY, textLabelPaint)
                canvas.restore()
            }
        }

        paint.color = if (isLightTheme) LIGHT_THEME_HUD_TEXT_COLOR else DARK_THEME_HUD_TEXT_COLOR
        paint.textSize = 26f
        val statusText = formatLocalizedHudStatus(
            langOrdinal = langOrdinal,
            modelType = modelType,
            polyCount = vertexCount / 9,
            isTruncated = isGeometryTruncated,
            maxLimit = maxPolygonsLimit
        )
        canvas.drawText(statusText, 30f, height - 32f, paint)
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0 || (pageIndex and 0xFF) == 1
        val langOrdinal = (pageIndex ushr LANG_ORDINAL_SHIFT) and 0xF
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val zoomFactor = (zoomPermille / 1000f).coerceAtLeast(0.1f)

        val previewBmp = dwgPreviewBitmap
        if (previewBmp != null && !previewBmp.isRecycled && vertexCount == 0) {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (isLightTheme) LIGHT_THEME_BG_COLOR else DARK_THEME_BG_COLOR)
            val aspect = previewBmp.width.toFloat() / previewBmp.height.toFloat().coerceAtLeast(1f)
            val availW = (width - 80f) * zoomFactor
            val availH = (height - 120f) * zoomFactor
            val dstW: Float
            val dstH: Float
            if (availW / availH.coerceAtLeast(1f) > aspect) {
                dstH = availH
                dstW = dstH * aspect
            } else {
                dstW = availW
                dstH = dstW / aspect
            }
            val panX = (tileX - 25) * 4f
            val panY = (tileY - 20) * 4f
            val left = (width - dstW) / 2f - panX
            val top = (height - dstH) / 2f - panY
            canvas.drawBitmap(previewBmp, null, RectF(left, top, left + dstW, top + dstH), bitmapPaint)

            paint.color = if (isLightTheme) LIGHT_THEME_HUD_TEXT_COLOR else DARK_THEME_HUD_TEXT_COLOR
            paint.textSize = 26f
            val statusText = formatLocalizedHudStatus(
                langOrdinal = langOrdinal,
                modelType = "$modelType (Preview ${previewBmp.width}×${previewBmp.height})",
                polyCount = 0,
                isTruncated = false,
                maxLimit = maxPolygonsLimit
            )
            canvas.drawText(statusText, 30f, height - 32f, paint)
            return
        }

        val extLowerForOrientation = originalExtension.lowercase(Locale.ROOT)
        val rangeYForOrientation = (maxY - minY).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeZForOrientation = (maxZ - minZ).let { if (it.isFinite() && it > 0f) it else 1f }
        val isYUpFormat = extLowerForOrientation in setOf("obj", "gltf", "glb", "usdz", "usd", "usda", "usdc", "fbx", "dae", "stl") ||
                modelType.contains("glTF", ignoreCase = true) ||
                modelType.contains("USD", ignoreCase = true) ||
                modelType.contains("COLLADA", ignoreCase = true) ||
                modelType.contains("STL", ignoreCase = true)
        val isDxfOrDwg = extLowerForOrientation in setOf("dxf", "dwg") || modelType.contains("DXF") || modelType.startsWith("DWG")
        val isYUp = isYUpFormat || (rangeYForOrientation > rangeZForOrientation && !isDxfOrDwg && !modelType.startsWith("Blender") && !modelType.startsWith("3MF"))

        if (vertexCount > 0) {
            try {
                var glesCtx = glesContext
                if (glesCtx != null && (glesCtx.width != bitmap.width || glesCtx.height != bitmap.height)) {
                    glesCtx.destroy()
                    glesContext = null
                    glesCtx = null
                }
                if (glesCtx == null && bitmap.width > 0 && bitmap.height > 0) {
                    glesCtx = Gles30OffscreenContext(bitmap.width, bitmap.height)
                    if (glesCtx.isInitialized) {
                        glesContext = glesCtx
                        glesCtx.makeCurrent()
                        glesRenderer = Gles30MeshRenderer()
                    } else {
                        glesCtx.destroy()
                        glesCtx = null
                    }
                }

                val renderer = glesRenderer
                if (glesCtx != null && glesCtx.isInitialized && renderer != null && renderer.isCompiled) {
                    glesCtx.makeCurrent()
                    renderer.renderMesh(
                        vertices = rawVertices,
                        polyColors = polyColors,
                        vertexCount = vertexCount,
                        width = bitmap.width,
                        height = bitmap.height,
                        yawDeg = tileX.toFloat(),
                        pitchDeg = tileY.toFloat(),
                        scale = zoomFactor,
                        isYUp = isYUp,
                        isLightTheme = isLightTheme,
                        minX = minX, maxX = maxX,
                        minY = minY, maxY = maxY,
                        minZ = minZ, maxZ = maxZ
                    )
                    glesCtx.readPixelsToBitmap(bitmap)
                    drawTextLabelsAndHud(Canvas(bitmap), width, height, zoomFactor, tileX, tileY, isYUp, isLightTheme, langOrdinal)
                    return
                }
            } catch (_: Throwable) {}
        }

        val fallbackCanvas = Canvas(bitmap)
        fallbackCanvas.drawColor(if (isLightTheme) LIGHT_THEME_BG_COLOR else DARK_THEME_BG_COLOR)
        if (vertexCount > 0 || textLabels.isNotEmpty()) {
            drawTextLabelsAndHud(fallbackCanvas, width, height, zoomFactor, tileX, tileY, isYUp, isLightTheme, langOrdinal)
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        glesRenderer?.destroy()
        glesRenderer = null
        glesContext?.destroy()
        glesContext = null
        dwgPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        dwgPreviewBitmap = null
        try { pfd.close() } catch (_: Exception) {}
    }
}
