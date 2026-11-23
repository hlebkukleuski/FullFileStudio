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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
                "3mf", "dae", "zae", "step", "stp", "iges", "igs"
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

    fun createSessionForFormat(fd: FileDescriptor, extension: String, password: String? = null): DocumentSession {
        val pfd = ParcelFileDescriptor.dup(fd)
        return Stl3dMeshSession(pfd, extension, password)
    }
}

class Stl3dMeshSession(
    private val pfd: ParcelFileDescriptor,
    private val originalExtension: String,
    private val password: String? = null
) : DocumentSession, com.omnireader.core.engine.MeshSceneSource {
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

    private var rawVertices = FloatArray(30_000)
    private var polyColors = IntArray(3_500)
    private var vertexCount = 0
    private var triUvs: FloatArray? = null
    private var triTex: IntArray? = null
    private var cornerColors: IntArray? = null
    private var cornerNormals: FloatArray? = null
    private var triNormalTex: IntArray? = null
    private var triBone: IntArray? = null
    private var skinOffsets: IntArray? = null
    private var skinJointsArr = IntArray(0)
    private var skinWeightsArr = FloatArray(0)
    private var skinBindArr = FloatArray(0)
    private var skinBindNrmArr = FloatArray(0)
    private var skinHasNormals = false
    private var skinCount = 0
    private var skinK = 4
    private var skinDqArr = IntArray(0)
    private var skinHasDq = false
    private var morphPolyArr = IntArray(0)
    private var morphTargetArr = IntArray(0)
    private var morphDeltaArr = FloatArray(0)
    private var morphNormalArr = FloatArray(0)
    private var morphHasNormals = false
    private var morphEntries = 0
    private var animClip: AnimClip? = null
    private val textures = ArrayList<Bitmap>()
    private val textureKeys = HashMap<String, Int>()
    private var textureBudgetPixels = initialTextureBudget()

    private fun initialTextureBudget(): Long = (Runtime.getRuntime().maxMemory() / 16L).coerceIn(4L * 1024L * 1024L, 48L * 1024L * 1024L)
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

    private val companionFiles = HashMap<String, ByteArray>()
    private val missingResources = LinkedHashSet<String>()

    private fun normalizeResourceRef(ref: String): String {
        val decoded = try { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { ref }
        var n = decoded.replace('\\', '/').trim().trim('"')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.removePrefix("file://").removePrefix("file:")
        return n.lowercase(Locale.ROOT)
    }

    private fun companionResource(ref: String): ByteArray? {
        if (ref.isBlank()) return null
        val n = normalizeResourceRef(ref)
        companionFiles[n]?.let { return it }
        companionFiles[n.substringAfterLast('/')]?.let { return it }
        val original = try { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { ref }
        missingResources.add(original.replace('\\', '/').trim().removePrefix("./"))
        return null
    }

    private fun companionResourceQuiet(ref: String): ByteArray? {
        val n = normalizeResourceRef(ref)
        return companionFiles[n] ?: companionFiles[n.substringAfterLast('/')]
    }

    @Synchronized
    override fun getMissingResources(): List<String> = missingResources.toList()

    @Synchronized
    override fun provideResource(name: String, fd: java.io.FileDescriptor): Boolean {
        if (isClosed) return false
        val bytes = try {
            val size = try { Os.fstat(fd).st_size } catch (_: Throwable) { -1L }
            if (size > 512L * 1024L * 1024L) return false
            java.io.FileInputStream(fd).use { it.readBytes() }
        } catch (_: Throwable) {
            return false
        }
        val n = normalizeResourceRef(name)
        val base = n.substringAfterLast('/')
        val wanted = missingResources.any { normalizeResourceRef(it).let { m -> m == n || m.substringAfterLast('/') == base } }
        if (!wanted) return false
        companionFiles[n] = bytes
        companionFiles[base] = bytes
        return true
    }

    @Synchronized
    override fun applyProvidedResources(): Boolean {
        if (isClosed) return false
        resetModel()
        parseModel()
        return vertexCount > 0
    }

    private fun resetModel() {
        rawVertices = FloatArray(30_000)
        polyColors = IntArray(3_500)
        vertexCount = 0
        triUvs = null
        triTex = null
        cornerColors = null
        cornerNormals = null
        triNormalTex = null
        triBone = null
        skinOffsets = null
        skinJointsArr = IntArray(0)
        skinWeightsArr = FloatArray(0)
        skinBindArr = FloatArray(0)
        skinBindNrmArr = FloatArray(0)
        skinHasNormals = false
        skinCount = 0
        skinK = 4
        skinDqArr = IntArray(0)
        skinHasDq = false
        morphPolyArr = IntArray(0)
        morphTargetArr = IntArray(0)
        morphDeltaArr = FloatArray(0)
        morphNormalArr = FloatArray(0)
        morphHasNormals = false
        morphEntries = 0
        animClip = null
        textures.forEach { if (!it.isRecycled) it.recycle() }
        textures.clear()
        textureKeys.clear()
        textureBudgetPixels = initialTextureBudget()
        textLabels.clear()
        usesDraco = false
        dxfEmittedOpsCount = 0
        isGeometryTruncated = false
        lastErrorMsg = null
        missingResources.clear()
        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
    }

    private fun parseModel() {
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
                val isColladaMagic = headerStr.contains("<collada") || extLower == "dae" || extLower == "zae"
                val is3mfMagic = extLower == "3mf"
                val isStepMagic = headerStr.startsWith("iso-10303-21;") ||
                        (headerStr.contains("iso-10303-21;") && headerStr.contains("header;")) ||
                        extLower in setOf("step", "stp")
                val firstIgesLine = String(header, 0, readBytes, StandardCharsets.US_ASCII)
                    .lineSequence().firstOrNull { it.isNotEmpty() }?.trimEnd('\r')
                val isIgesMagic = extLower in setOf("iges", "igs") ||
                        (firstIgesLine != null && firstIgesLine.length == 80 &&
                                (firstIgesLine[72] == 'S' || firstIgesLine[72] == 's') &&
                                firstIgesLine.substring(73).trim().let { it.isNotEmpty() && it.all(Char::isDigit) })

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
                    isZipMagic && extLower == "zae" -> {
                        modelType = "COLLADA (ZAE)"
                        parseColladaDaeCleanRoom()
                    }
                    isZipMagic -> {
                        // Inspect ZIP container: could be 3MF or USDZ opened without extension
                        parse3mfCleanRoom()
                        if (vertexCount == 0) {
                            parseUsdOrUsdzCleanRoom()
                        }
                        if (vertexCount == 0) {
                            modelType = "COLLADA (ZAE)"
                            parseColladaDaeCleanRoom()
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
        } catch (e: SecurityException) {
            lastErrorMsg = e.message
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка разбора 3D/CAD модели: ${e.localizedMessage}"
        }

        if (vertexCount == 0 && missingResources.isNotEmpty()) {
            lastErrorMsg = null
        } else if (vertexCount == 0 && textLabels.isEmpty() && dwgPreviewBitmap == null && lastErrorMsg == null) {
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
    private class Mat3ds(var name: String = "", var color: Int = 0, var alpha: Float = 1f, var texture: String? = null,
                         var uScale: Float = 1f, var vScale: Float = 1f, var uOff: Float = 0f, var vOff: Float = 0f, var bump: String? = null)

    private class Track3ds(val frames: IntArray, val values: Array<FloatArray>)

    private class Mesh3ds(val name: String) {
        var verts = FloatArray(0)
        var uvs: FloatArray? = null
        var faces = IntArray(0)
        var smoothing: IntArray? = null
        val faceMaterials = ArrayList<Pair<String, IntArray>>()
        var matrix: FloatArray? = null
    }

    private class Node3ds(var name: String, var parent: Int, val id: Int) {
        var instance: String? = null
        var pivot = floatArrayOf(0f, 0f, 0f)
        var pos: FloatArray? = null
        var rot: FloatArray? = null
        var scl: FloatArray? = null
        var posKeys: Track3ds? = null
        var rotKeys: Track3ds? = null
        var sclKeys: Track3ds? = null
    }

    private fun parse3dsCleanRoom(fileSize: Long) {
        if (fileSize > 256L * 1024L * 1024L) {
            lastErrorMsg = "Файл 3DS слишком большой для загрузки."
            return
        }
        val bytes = readDescriptorBytes(256 * 1024 * 1024)
        if (bytes.size < 16) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val materials = HashMap<String, Mat3ds>()
        val meshes = ArrayList<Mesh3ds>()
        val nodes = ArrayList<Node3ds>()

        fun cstr(at: Int, end: Int): Pair<String, Int> {
            var k = at
            while (k < end && bytes[k].toInt() != 0) k++
            return String(bytes, at, k - at, Charsets.ISO_8859_1) to minOf(k + 1, end)
        }

        fun readColor(start: Int, end: Int): Int? {
            var p = start
            var linear: Int? = null
            var gamma: Int? = null
            while (p + 6 <= end) {
                val id = bb.getShort(p).toInt() and 0xFFFF
                val len = bb.getInt(p + 2)
                if (len < 6 || p + len > end) break
                val d = p + 6
                val c = when (id) {
                    0x0010, 0x0013 -> if (d + 12 <= p + len) Color.rgb((bb.getFloat(d).coerceIn(0f, 1f) * 255).roundToInt(), (bb.getFloat(d + 4).coerceIn(0f, 1f) * 255).roundToInt(), (bb.getFloat(d + 8).coerceIn(0f, 1f) * 255).roundToInt()) else null
                    0x0011, 0x0012 -> if (d + 3 <= p + len) Color.rgb(bytes[d].toInt() and 0xFF, bytes[d + 1].toInt() and 0xFF, bytes[d + 2].toInt() and 0xFF) else null
                    else -> null
                }
                if (c != null) {
                    if (id == 0x0012 || id == 0x0013) gamma = c else if (linear == null) linear = c
                }
                p += len
            }
            return gamma ?: linear
        }

        fun readPercent(start: Int, end: Int): Float? {
            if (start + 6 > end) return null
            val id = bb.getShort(start).toInt() and 0xFFFF
            return when (id) {
                0x0030 -> (bb.getShort(start + 6).toInt()) / 100f
                0x0031 -> bb.getFloat(start + 6) / 100f
                else -> null
            }
        }

        fun readTrack(d: Int, end: Int, comps: Int): FloatArray? {
            if (d + 14 > end) return null
            val nkeys = bb.getInt(d + 10)
            var p = d + 14
            if (nkeys <= 0) return null
            p += 4
            if (p + 2 > end) return null
            val flags = bb.getShort(p).toInt() and 0xFFFF
            p += 2
            for (bit in 0 until 5) if (flags and (1 shl bit) != 0) p += 4
            if (p + comps * 4 > end) return null
            return FloatArray(comps) { bb.getFloat(p + it * 4) }
        }

        fun readTrackKeys(d: Int, end: Int, comps: Int): Track3ds? {
            if (d + 14 > end) return null
            val nkeys = bb.getInt(d + 10)
            if (nkeys <= 0 || nkeys > 100_000) return null
            var p = d + 14
            val frames = ArrayList<Int>()
            val values = ArrayList<FloatArray>()
            for (k in 0 until nkeys) {
                if (p + 6 > end) break
                val frame = bb.getInt(p)
                p += 4
                val flags = bb.getShort(p).toInt() and 0xFFFF
                p += 2
                for (bit in 0 until 5) if (flags and (1 shl bit) != 0) p += 4
                if (p + comps * 4 > end) break
                val start = p
                values.add(FloatArray(comps) { bb.getFloat(start + it * 4) })
                frames.add(frame)
                p += comps * 4
            }
            if (values.isEmpty()) return null
            return Track3ds(frames.toIntArray(), values.toTypedArray())
        }

        var segStart = 0
        var segEnd = -1

        fun walk(start: Int, end: Int, depth: Int, mesh: Mesh3ds?, mat: Mat3ds?, node: Node3ds?) {
            if (depth > 16) return
            var p = start
            while (p + 6 <= end && !isGeometryTruncated) {
                val id = bb.getShort(p).toInt() and 0xFFFF
                val len = bb.getInt(p + 2).toLong() and 0xFFFFFFFFL
                if (len < 6) break
                val next = minOf(end.toLong(), p + len).toInt()
                val d = p + 6
                when (id) {
                    0x4D4D, 0x3D3D, 0xB000 -> walk(d, next, depth + 1, null, null, null)
                    0xAFFF -> {
                        val m = Mat3ds()
                        walk(d, next, depth + 1, null, m, null)
                        if (m.name.isNotEmpty()) materials[m.name] = m
                    }
                    0xA000 -> mat?.name = cstr(d, next).first
                    0xA020 -> mat?.let { m -> readColor(d, next)?.let { m.color = it } }
                    0xA050 -> mat?.let { m -> readPercent(d, next)?.let { m.alpha = (1f - it).coerceIn(0f, 1f) } }
                    0xA200 -> walk(d, next, depth + 1, null, mat, null)
                    0xA230 -> if (mat != null) {
                        var q = d
                        while (q + 6 <= next) {
                            val sid = bb.getShort(q).toInt() and 0xFFFF
                            val slen = bb.getInt(q + 2)
                            if (slen < 6 || q + slen > next) break
                            if (sid == 0xA300) mat.bump = cstr(q + 6, q + slen).first
                            q += slen
                        }
                    }
                    0xA300 -> mat?.texture = cstr(d, next).first
                    0xA354 -> if (d + 4 <= next) mat?.uScale = bb.getFloat(d)
                    0xA356 -> if (d + 4 <= next) mat?.vScale = bb.getFloat(d)
                    0xA358 -> if (d + 4 <= next) mat?.uOff = bb.getFloat(d)
                    0xA35A -> if (d + 4 <= next) mat?.vOff = bb.getFloat(d)
                    0x4000 -> {
                        val (name, after) = cstr(d, next)
                        val m = Mesh3ds(name)
                        walk(after, next, depth + 1, m, null, null)
                        if (m.verts.isNotEmpty()) meshes.add(m)
                    }
                    0x4100 -> walk(d, next, depth + 1, mesh, null, null)
                    0x4110 -> if (mesh != null && d + 2 <= next) {
                        val n = (bb.getShort(d).toInt() and 0xFFFF).coerceAtMost((next - d - 2) / 12)
                        mesh.verts = FloatArray(n * 3) { bb.getFloat(d + 2 + it * 4) }
                    }
                    0x4140 -> if (mesh != null && d + 2 <= next) {
                        val n = (bb.getShort(d).toInt() and 0xFFFF).coerceAtMost((next - d - 2) / 8)
                        mesh.uvs = FloatArray(n * 2) { bb.getFloat(d + 2 + it * 4) }
                    }
                    0x4160 -> if (mesh != null && d + 48 <= next) {
                        val m = FloatArray(16)
                        for (c in 0 until 4) for (r in 0 until 3) m[c * 4 + r] = bb.getFloat(d + (c * 3 + r) * 4)
                        m[15] = 1f
                        mesh.matrix = m
                    }
                    0x4120 -> if (mesh != null && d + 2 <= next) {
                        val n = (bb.getShort(d).toInt() and 0xFFFF).coerceAtMost((next - d - 2) / 8)
                        mesh.faces = IntArray(n * 3) { bb.getShort(d + 2 + (it / 3) * 8 + (it % 3) * 2).toInt() and 0xFFFF }
                        walk(d + 2 + n * 8, next, depth + 1, mesh, null, null)
                    }
                    0x4150 -> if (mesh != null) {
                        val n = mesh.faces.size / 3
                        if (d + n * 4 <= next) mesh.smoothing = IntArray(n) { bb.getInt(d + it * 4) }
                    }
                    0xB008 -> if (d + 8 <= next) {
                        segStart = bb.getInt(d)
                        segEnd = bb.getInt(d + 4)
                    }
                    0x4130 -> if (mesh != null) {
                        val (name, after) = cstr(d, next)
                        if (after + 2 <= next) {
                            val n = (bb.getShort(after).toInt() and 0xFFFF).coerceAtMost((next - after - 2) / 2)
                            mesh.faceMaterials.add(name to IntArray(n) { bb.getShort(after + 2 + it * 2).toInt() and 0xFFFF })
                        }
                    }
                    0xB002 -> {
                        val n = Node3ds("", -1, nodes.size)
                        walk(d, next, depth + 1, null, null, n)
                        nodes.add(n)
                    }
                    0xB010 -> if (node != null) {
                        val (name, after) = cstr(d, next)
                        node.name = name
                        node.parent = if (after + 6 <= next) bb.getShort(after + 4).toInt() else -1
                    }
                    0xB011 -> node?.instance = cstr(d, next).first
                    0xB013 -> if (node != null && d + 12 <= next) node.pivot = floatArrayOf(bb.getFloat(d), bb.getFloat(d + 4), bb.getFloat(d + 8))
                    0xB020 -> node?.let { n -> n.pos = readTrack(d, next, 3); n.posKeys = readTrackKeys(d, next, 3) }
                    0xB021 -> node?.let { n -> n.rot = readTrack(d, next, 4); n.rotKeys = readTrackKeys(d, next, 4) }
                    0xB022 -> node?.let { n -> n.scl = readTrack(d, next, 3); n.sclKeys = readTrackKeys(d, next, 3) }
                }
                if (next <= p) break
                p = next
            }
        }

        walk(0, bytes.size, 0, null, null, null)

        val matTextures = HashMap<String, Int>()
        fun texOf(m: Mat3ds): Int {
            val t = m.texture ?: return -1
            return matTextures.getOrPut(m.name) {
                val data = companionResource(t)
                registerTexture("3ds:" + normalizeResourceRef(t), data, m.alpha >= 0.999f)
            }
        }

        val matNormals = HashMap<String, Int>()
        fun normalOf(m: Mat3ds): Int {
            val t = m.bump ?: return -1
            return matNormals.getOrPut(m.name) {
                val data = companionResourceQuiet(t) ?: companionResource(t)
                if (data != null && NormalMapHeuristic.looksLikeNormalMap(data)) registerNormalTexture("3ds:" + normalizeResourceRef(t), data, false, 1f) else -1
            }
        }

        fun axisQuat(r: FloatArray): FloatArray? {
            val ax = r[1]; val ay = r[2]; val az = r[3]
            val l = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            if (l <= 1e-8f) return null
            val om = -0.5f * r[0]
            val sn = kotlin.math.sin(om) / l
            return floatArrayOf(ax * sn, ay * sn, az * sn, kotlin.math.cos(om))
        }

        fun quatMul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        )

        fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
            var dot = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
            val b = if (dot < 0f) { dot = -dot; FloatArray(4) { -b0[it] } } else b0
            if (dot > 0.9995f) {
                val r = FloatArray(4) { a[it] + (b[it] - a[it]) * t }
                val l = kotlin.math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3])
                return FloatArray(4) { r[it] / l }
            }
            val th = kotlin.math.acos(dot.coerceIn(-1f, 1f))
            val st = kotlin.math.sin(th)
            val wa = kotlin.math.sin((1 - t) * th) / st
            val wb = kotlin.math.sin(t * th) / st
            return FloatArray(4) { wa * a[it] + wb * b[it] }
        }

        val absRot = HashMap<Int, Array<FloatArray>>()
        fun absoluteRotations(n: Node3ds): Array<FloatArray>? {
            val tr = n.rotKeys ?: return null
            return absRot.getOrPut(n.id) {
                var acc = floatArrayOf(0f, 0f, 0f, 1f)
                Array(tr.values.size) { k ->
                    val q = axisQuat(tr.values[k]) ?: floatArrayOf(0f, 0f, 0f, 1f)
                    acc = if (k == 0) q else quatMul(acc, q)
                    acc
                }
            }
        }

        fun sampleVec(tr: Track3ds?, frame: Float, def: FloatArray?): FloatArray? {
            if (tr == null) return def
            val f = tr.frames
            if (frame <= f[0] || f.size == 1) return tr.values[0]
            if (frame >= f[f.size - 1]) return tr.values[f.size - 1]
            var i = 0
            while (i + 1 < f.size && f[i + 1] <= frame) i++
            val span = (f[i + 1] - f[i]).toFloat().takeIf { it > 0f } ?: 1f
            val a = (frame - f[i]) / span
            return FloatArray(tr.values[i].size) { tr.values[i][it] + (tr.values[i + 1][it] - tr.values[i][it]) * a }
        }

        fun sampleRot(n: Node3ds, frame: Float): FloatArray? {
            val tr = n.rotKeys ?: return n.rot?.let { axisQuat(it) }
            val abs = absoluteRotations(n) ?: return null
            val f = tr.frames
            if (frame <= f[0] || f.size == 1) return abs[0]
            if (frame >= f[f.size - 1]) return abs[f.size - 1]
            var i = 0
            while (i + 1 < f.size && f[i + 1] <= frame) i++
            val span = (f[i + 1] - f[i]).toFloat().takeIf { it > 0f } ?: 1f
            return slerp(abs[i], abs[i + 1], (frame - f[i]) / span)
        }

        fun nodeMatrixAt(n: Node3ds, frame: Float?): FloatArray {
            val t = (if (frame == null) n.pos else sampleVec(n.posKeys, frame, n.pos)) ?: floatArrayOf(0f, 0f, 0f)
            val q = if (frame == null) n.rot?.let { axisQuat(it) } else sampleRot(n, frame)
            val s = (if (frame == null) n.scl else sampleVec(n.sclKeys, frame, n.scl)) ?: floatArrayOf(1f, 1f, 1f)
            val m = CleanRoomGltf.trs(t, q, s)
            return CleanRoomGltf.mul(m, CleanRoomGltf.trs(floatArrayOf(-n.pivot[0], -n.pivot[1], -n.pivot[2]), null, null))
        }

        fun nodeMatrix(n: Node3ds): FloatArray = nodeMatrixAt(n, null)

        fun invertAffine(m: FloatArray): FloatArray? {
            val a = m[0]; val b = m[4]; val c = m[8]
            val d = m[1]; val e = m[5]; val f = m[9]
            val g = m[2]; val h = m[6]; val i = m[10]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            if (kotlin.math.abs(det) < 1e-12f) return null
            val inv = FloatArray(16)
            inv[0] = (e * i - f * h) / det; inv[4] = (c * h - b * i) / det; inv[8] = (b * f - c * e) / det
            inv[1] = (f * g - d * i) / det; inv[5] = (a * i - c * g) / det; inv[9] = (c * d - a * f) / det
            inv[2] = (d * h - e * g) / det; inv[6] = (b * g - a * h) / det; inv[10] = (a * e - b * d) / det
            inv[12] = -(inv[0] * m[12] + inv[4] * m[13] + inv[8] * m[14])
            inv[13] = -(inv[1] * m[12] + inv[5] * m[13] + inv[9] * m[14])
            inv[14] = -(inv[2] * m[12] + inv[6] * m[13] + inv[10] * m[14])
            inv[15] = 1f
            return inv
        }

        val byName = nodes.groupBy { it.name }
        val worldOf = HashMap<Int, FloatArray>()
        fun world(n: Node3ds, depth: Int): FloatArray {
            worldOf[n.id]?.let { return it }
            val local = nodeMatrix(n)
            val parent = nodes.getOrNull(n.parent)?.takeIf { it !== n && depth < 32 }
            val w = if (parent != null) CleanRoomGltf.mul(world(parent, depth + 1), local) else local
            worldOf[n.id] = w
            return w
        }
        fun moving(n: Node3ds) = (n.posKeys?.frames?.size ?: 0) > 1 || (n.rotKeys?.frames?.size ?: 0) > 1 || (n.sclKeys?.frames?.size ?: 0) > 1
        val frameCount = if (segEnd > segStart && nodes.any { moving(it) }) minOf(4000, segEnd - segStart + 1) else 0
        val dynamic = HashMap<Int, Boolean>()
        fun isDynamic(n: Node3ds, depth: Int): Boolean = dynamic.getOrPut(n.id) {
            moving(n) || (nodes.getOrNull(n.parent)?.takeIf { it !== n && depth < 32 }?.let { isDynamic(it, depth + 1) } ?: false)
        }
        fun worldAt(n: Node3ds, frame: Float, depth: Int): FloatArray {
            val local = nodeMatrixAt(n, frame)
            val parent = nodes.getOrNull(n.parent)?.takeIf { it !== n && depth < 32 }
            return if (parent != null) CleanRoomGltf.mul(worldAt(parent, frame, depth + 1), local) else local
        }
        val boneFrames3ds = ArrayList<FloatArray>()
        val boneOf = HashMap<Int, Int>()
        fun boneFor(n: Node3ds?): Int {
            if (n == null || frameCount < 2 || !isDynamic(n, 0)) return -1
            return boneOf.getOrPut(n.id) {
                if ((boneFrames3ds.size + 1).toLong() * frameCount * 12 > 8_000_000L) return@getOrPut -1
                val inv = invertAffine(world(n, 0)) ?: return@getOrPut -1
                val frames = FloatArray(frameCount * 12)
                for (k in 0 until frameCount) {
                    val m = CleanRoomGltf.mul(worldAt(n, (segStart + k).toFloat(), 0), inv)
                    val at = k * 12
                    frames[at] = m[0]; frames[at + 1] = m[1]; frames[at + 2] = m[2]
                    frames[at + 3] = m[4]; frames[at + 4] = m[5]; frames[at + 5] = m[6]
                    frames[at + 6] = m[8]; frames[at + 7] = m[9]; frames[at + 8] = m[10]
                    frames[at + 9] = m[12]; frames[at + 10] = m[13]; frames[at + 11] = m[14]
                }
                boneFrames3ds.add(frames)
                boneFrames3ds.size - 1
            }
        }

        val uv = FloatArray(6)
        val nrm = FloatArray(9)
        val triAnim3ds = TriAnim()
        for (mesh in meshes) {
            val v = mesh.verts
            val vCount = v.size / 3
            val nFaces = mesh.faces.size / 3
            val faceMat = arrayOfNulls<Mat3ds>(nFaces)
            for ((name, list) in mesh.faceMaterials) {
                val m = materials[name] ?: continue
                for (f in list) if (f in faceMat.indices) faceMat[f] = m
            }
            val sg = mesh.smoothing
            val cornerN = if (sg != null && sg.any { it != 0 }) {
                val fn = FloatArray(nFaces * 3)
                val adj = Array(vCount) { ArrayList<Int>(6) }
                for (f in 0 until nFaces) {
                    val a = mesh.faces[f * 3]; val b = mesh.faces[f * 3 + 1]; val c = mesh.faces[f * 3 + 2]
                    if (a >= vCount || b >= vCount || c >= vCount) continue
                    val ux = v[b * 3] - v[a * 3]; val uy = v[b * 3 + 1] - v[a * 3 + 1]; val uz = v[b * 3 + 2] - v[a * 3 + 2]
                    val wx = v[c * 3] - v[a * 3]; val wy = v[c * 3 + 1] - v[a * 3 + 1]; val wz = v[c * 3 + 2] - v[a * 3 + 2]
                    fn[f * 3] = uy * wz - uz * wy
                    fn[f * 3 + 1] = uz * wx - ux * wz
                    fn[f * 3 + 2] = ux * wy - uy * wx
                    adj[a].add(f); adj[b].add(f); adj[c].add(f)
                }
                val out = FloatArray(nFaces * 9)
                for (f in 0 until nFaces) {
                    val g = sg.getOrElse(f) { 0 }
                    if (g == 0) continue
                    for (k in 0 until 3) {
                        val vi = mesh.faces[f * 3 + k]
                        if (vi >= vCount) continue
                        var x = 0f
                        var y = 0f
                        var z = 0f
                        for (f2 in adj[vi]) {
                            if (sg.getOrElse(f2) { 0 } and g == 0) continue
                            x += fn[f2 * 3]; y += fn[f2 * 3 + 1]; z += fn[f2 * 3 + 2]
                        }
                        val l = kotlin.math.sqrt(x * x + y * y + z * z)
                        if (l > 0f) {
                            out[f * 9 + k * 3] = x / l; out[f * 9 + k * 3 + 1] = y / l; out[f * 9 + k * 3 + 2] = z / l
                        }
                    }
                }
                out
            } else null
            val list = byName[mesh.name].orEmpty()
            val primary = list.firstOrNull { it.instance == null } ?: list.firstOrNull()
            val entries = ArrayList<Pair<FloatArray, Node3ds?>>()
            entries.add(CleanRoomGltf.IDENTITY to primary)
            if (primary != null && list.any { it.instance != null }) {
                val inv = invertAffine(world(primary, 0))
                if (inv != null) for (n in list) if (n !== primary) entries.add(CleanRoomGltf.mul(world(n, 0), inv) to n)
            }
            for ((xf, node) in entries) {
                val bone = boneFor(node)
                val pos = FloatArray(v.size)
                for (k in 0 until vCount) {
                    val x = v[k * 3]; val y = v[k * 3 + 1]; val z = v[k * 3 + 2]
                    pos[k * 3] = xf[0] * x + xf[4] * y + xf[8] * z + xf[12]
                    pos[k * 3 + 1] = xf[1] * x + xf[5] * y + xf[9] * z + xf[13]
                    pos[k * 3 + 2] = xf[2] * x + xf[6] * y + xf[10] * z + xf[14]
                }
                for (f in 0 until nFaces) {
                    if (isGeometryTruncated) return
                    val a = mesh.faces[f * 3]; val b = mesh.faces[f * 3 + 1]; val c = mesh.faces[f * 3 + 2]
                    if (a >= vCount || b >= vCount || c >= vCount) continue
                    val m = faceMat[f]
                    val tex = if (m != null) texOf(m) else -1
                    val ntex = if (m != null) normalOf(m) else -1
                    val uvs = mesh.uvs
                    val hasUv = (tex >= 0 || ntex >= 0) && uvs != null && maxOf(a, b, c) * 2 + 1 < uvs.size
                    if (hasUv) {
                        for ((slot, vi) in intArrayOf(a, b, c).withIndex()) {
                            uv[slot * 2] = uvs!![vi * 2] * m!!.uScale + m.uOff
                            uv[slot * 2 + 1] = 1f - (uvs[vi * 2 + 1] * m.vScale + m.vOff)
                        }
                    }
                    val hasN = cornerN != null && sg!!.getOrElse(f) { 0 } != 0
                    if (hasN) {
                        for (k in 0 until 3) {
                            val nx = cornerN!![f * 9 + k * 3]; val ny = cornerN[f * 9 + k * 3 + 1]; val nz = cornerN[f * 9 + k * 3 + 2]
                            val rx = xf[0] * nx + xf[4] * ny + xf[8] * nz
                            val ry = xf[1] * nx + xf[5] * ny + xf[9] * nz
                            val rz = xf[2] * nx + xf[6] * ny + xf[10] * nz
                            val l = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).takeIf { it > 0f } ?: 1f
                            nrm[k * 3] = rx / l; nrm[k * 3 + 1] = ry / l; nrm[k * 3 + 2] = rz / l
                        }
                    }
                    val alpha = ((m?.alpha ?: 1f) * 255f).roundToInt().coerceIn(0, 255)
                    val color = when {
                        m == null -> 0
                        hasUv && tex >= 0 -> Color.argb(alpha, 255, 255, 255)
                        else -> Color.argb(alpha, Color.red(m.color), Color.green(m.color), Color.blue(m.color))
                    }
                    triAnim3ds.reset()
                    triAnim3ds.bone = bone
                    addTriangle(
                        pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2],
                        pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2],
                        pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2],
                        color, if (hasUv) uv else null, 0, if (hasUv && tex >= 0) tex else -1, 0, 0, 0,
                        if (hasN) nrm else null, if (hasUv) ntex else -1, if (bone >= 0) triAnim3ds else null
                    )
                }
            }
        }
        if (boneFrames3ds.isNotEmpty() && frameCount >= 2) {
            val bones = boneFrames3ds.size
            val matrices = FloatArray(frameCount * bones * 12)
            for (bi in 0 until bones) for (k in 0 until frameCount) System.arraycopy(boneFrames3ds[bi], k * 12, matrices, (k * bones + bi) * 12, 12)
            animClip = AnimClip(30f, frameCount, bones, matrices)
        }
    }

    /**
     * Full Clean-Room Khronos glTF 2.0 (`.gltf` JSON & `.glb` Binary) 3D Scene & Mesh Parser.
     * Supports arbitrary file sizes (including `.glb` files > 100 MB) by reading binary buffer views
     * directly from the file descriptor via `Os.pread` when `customBytes == null`.
     */
    private fun parseGltfOrGlbCleanRoom(customBytes: ByteArray? = null) {
        var jsonString: String? = null
        var binSource: GltfBinSource? = null
        var binLength = 0L
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
                    } else if (chunkType == 0x004E4942 && binSource == null) {
                        val start = dataStart
                        binLength = safeLen.toLong()
                        binSource = GltfBinSource { off, len -> customBytes.copyOfRange(start + off.toInt(), start + off.toInt() + len) }
                    }
                    pos = dataStart + ((safeLen + 3) and 3.inv())
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
                var pos = 12L
                while (pos + 8L <= fileSize) {
                    val hdr = readDescriptorSliceAt(pos, 8)
                    if (hdr.size < 8) break
                    val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    val chunkLen = hb.getInt(0).toLong() and 0xFFFFFFFFL
                    val chunkType = hb.getInt(4)
                    val dataStart = pos + 8L
                    if (dataStart > fileSize) break
                    val avail = minOf(chunkLen, fileSize - dataStart)
                    if (chunkType == 0x4E4F534A && jsonString == null && avail <= 64L * 1024L * 1024L) {
                        jsonString = String(readDescriptorSliceAt(dataStart, avail.toInt()), StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942 && binSource == null) {
                        val start = dataStart
                        binLength = avail
                        binSource = GltfBinSource { off, len -> readDescriptorSliceAt(start + off, len).takeIf { it.size == len } }
                    }
                    if (chunkLen <= 0L) break
                    pos = dataStart + ((chunkLen + 3L) and 3L.inv())
                }
            } else {
                jsonString = String(readDescriptorBytes(64 * 1024 * 1024), StandardCharsets.UTF_8)
            }
        }
        val text = jsonString?.trim()?.trimStart('\uFEFF') ?: return
        if (!text.startsWith("{")) return
        val root = try { JSONObject(text) } catch (_: Throwable) { return }
        val required = root.optJSONArray("extensionsRequired")
        if (required != null) {
            for (k in 0 until required.length()) {
                if (required.optString(k) == "KHR_draco_mesh_compression") usesDraco = true
            }
        }
        CleanRoomGltf(root, binSource, binLength, sceneOutput, maxPolygonsLimit * 3).load()
        if (vertexCount == 0 && usesDraco) {
            lastErrorMsg = "Модель glTF сжата Draco, но её геометрию не удалось распаковать."
        }
    }

    private var usesDraco = false

    private val sceneOutput = object : GltfOutput {
        override val truncated: Boolean get() = isGeometryTruncated
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            addTriangle(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], color, uv, 0, texture, c0, c1, c2)
        }
        override fun triangleN(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int, normals: FloatArray?) {
            addTriangle(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], color, uv, 0, texture, c0, c1, c2, normals)
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {
            addLineSegment(x1, y1, z1, x2, y2, z2, color)
        }
        override fun triangleFull(
            p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int,
            normals: FloatArray?, normalTexture: Int, anim: TriAnim?
        ) {
            addTriangle(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], color, uv, 0, texture, c0, c1, c2, normals, normalTexture, anim)
        }
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int = registerTexture(key, bytes, opaque)
        override fun pixels(bytes: ByteArray): DecodedPixels? = try {
            CleanRoomTextureDecoders.decode(bytes) ?: run {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = false
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { b ->
                    val px = IntArray(b.width * b.height)
                    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    DecodedPixels(b.width, b.height, px).also { b.recycle() }
                }
            }
        } catch (_: Throwable) {
            null
        }
        override fun normalTexture(key: String, bytes: ByteArray?, flipGreen: Boolean, strength: Float): Int =
            registerNormalTexture(key, bytes, flipGreen, strength)
        override fun animation(clip: AnimClip) {
            animClip = clip
        }
        override fun resource(uri: String): ByteArray? = companionResource(uri)
    }
    /**
     * Clean-Room Blender 3D (`.blend`) binary parser supporting Blender 2.7x, 2.8x, 2.9x, 3.x, 4.x, and 5.x.
     * Handles uncompressed `"BLENDER"` files and GZIP-compressed `.blend` files, parses `DNA1`
     * structure definitions (`Mesh`, `MVert`, `MPoly`, `MLoop`, `MFace`, and Blender 4.0+ SOA float3/int32
     * attribute buffers), and triangulates all mesh geometry.
     */
    private fun parseBlendCleanRoom() {
        val size = resolveDescriptorFileSize()
        if (size > 512L * 1024L * 1024L) {
            lastErrorMsg = "Файл Blender слишком большой для загрузки."
            return
        }
        val bridge = GplCad3dBridgeLoader.get()
        if (bridge == null) {
            lastErrorMsg = "Blender (.blend) engine is not included in this build."
            return
        }
        val raw = readDescriptorBytes(512 * 1024 * 1024)
        bridge.parseBlend(raw, sceneOutput)?.let { modelType = it }
    }

    private fun parseUsdOrUsdzCleanRoom() {
        val head = readDescriptorSliceAt(0L, 16)
        if (head.size < 4) return
        try {
            val packageFiles = HashMap<String, ByteArray>()
            val rootId: String
            if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                modelType = "USDZ 3D Package"
                val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 256L * 1024L * 1024L }
                var first: String? = null
                var shallowest: String? = null
                openSafeStream { rawStream ->
                    ZipInputStream(rawStream).use { zis ->
                        val buf = ByteArray(65536)
                        var totalUnpacked = 0L
                        while (true) {
                            val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                            if (entry.isDirectory) continue
                            val name = entry.name.replace('\\', '/').trimStart('/')
                            val bos = ByteArrayOutputStream()
                            while (true) {
                                val r = zis.read(buf)
                                if (r <= 0) break
                                bos.write(buf, 0, r)
                                totalUnpacked += r
                                if (totalUnpacked > 512L * 1024 * 1024 || (totalUnpacked > 48L * 1024 * 1024 && totalUnpacked / fileSize.coerceAtLeast(1L) > 150L)) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия USDZ.")
                                }
                            }
                            packageFiles[name] = bos.toByteArray()
                            val lower = name.lowercase(Locale.ROOT)
                            if (lower.endsWith(".usd") || lower.endsWith(".usda") || lower.endsWith(".usdc")) {
                                if (first == null) first = name
                                if (shallowest == null || name.count { it == '/' } < shallowest!!.count { it == '/' }) shallowest = name
                            }
                        }
                    }
                }
                val isPackage = originalExtension.lowercase(Locale.ROOT).trim() == "usdz" || first?.contains('/') == false
                rootId = (if (isPackage) first else shallowest) ?: return
            } else {
                val bytes = readDescriptorBytes(512 * 1024 * 1024)
                if (bytes.size < 8) return
                modelType = if (String(bytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") "USDC (Pixar Crate)" else "USDA 3D Scene"
                rootId = "\u0000root"
                packageFiles[rootId] = bytes
            }
            val lowerIndex = packageFiles.keys.associateBy { it.lowercase(Locale.ROOT) }
            val stage = UsdStage({ id ->
                packageFiles[id] ?: lowerIndex[id.lowercase(Locale.ROOT)]?.let { packageFiles[it] } ?: companionResource(id)
            }, rootId)
            if (stage.rootLayer == null) {
                lastErrorMsg = "Не удалось разобрать сцену USD."
                return
            }
            CleanRoomUsd(stage, sceneOutput).load()
        } catch (e: SecurityException) {
            lastErrorMsg = e.message
        } catch (_: Throwable) {}
    }

    /**
     * Full Clean-Room parser for Autodesk FBX (`.fbx` Binary v7000–v7700+ and ASCII) 3D models.
     * Parses the binary FBX node hierarchy (`Objects` -> `Geometry` -> `Vertices` & `PolygonVertexIndex`),
     * decompresses zlib-deflated property arrays (`encoding == 1`), and triangulates all polygon loops.
     */
    private fun parseFbxCleanRoom() {
        val size = resolveDescriptorFileSize()
        if (size > 512L * 1024L * 1024L) {
            lastErrorMsg = "Файл FBX слишком большой для загрузки."
            return
        }
        val bridge = GplCad3dBridgeLoader.get()
        if (bridge == null) {
            lastErrorMsg = "FBX (.fbx) engine is not included in this build."
            return
        }
        val bytes = readDescriptorBytes(512 * 1024 * 1024)
        if (bytes.size < 32) return
        try {
            bridge.parseFbx(bytes, sceneOutput)?.let { modelType = it }
        } catch (e: SecurityException) {
            lastErrorMsg = e.message
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for 3D Manufacturing Format (`.3mf` ZIP + XML `<mesh><vertices><triangles>`).
     */
    private fun parse3mfCleanRoom() {
        val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 256L * 1024L * 1024L }
        try {
            val models = LinkedHashMap<String, ByteArray>()
            var rels: ByteArray? = null
            openSafeStream { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var totalUnpacked = 0L
                    val buf = ByteArray(16384)
                    while (true) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        val name = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                        val isModel = name.endsWith(".model") || (name.startsWith("3d/") && name.endsWith(".xml"))
                        val isRels = name == "_rels/.rels"
                        if (!entry.isDirectory && (isModel || isRels)) {
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
                            val data = bos.toByteArray()
                            if (isRels) rels = data else models[entry.name] = data
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
            if (models.isEmpty()) return
            modelType = "3MF 3D Model"
            com.omnireader.core.engine.impl.CleanRoomSceneFormats.parse3mf(
                models,
                com.omnireader.core.engine.impl.CleanRoomSceneFormats.find3mfRootModel(rels)
            ) { x1, y1, z1, x2, y2, z2, x3, y3, z3, color ->
                addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
                !isGeometryTruncated
            }
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for Khronos COLLADA (`.dae` XML) 3D models.
     */
    private fun parseColladaDaeCleanRoom() {
        val bytes = readDescriptorBytes(256 * 1024 * 1024)
        if (bytes.isEmpty()) return
        try {
            val packageFiles = HashMap<String, ByteArray>()
            var daeBytes = bytes
            if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                    var total = 0L
                    while (true) {
                        val e = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        if (e.isDirectory) continue
                        val data = zis.readBytes()
                        total += data.size
                        if (total > 512L * 1024 * 1024) throw SecurityException("Превышен безопасный размер распаковки ZAE.")
                        packageFiles[e.name.replace('\\', '/').trimStart('/')] = data
                    }
                }
                val manifestRoot = packageFiles["manifest.xml"]?.let { Regex("<dae_root>\\s*([^<]+?)\\s*</dae_root>").find(String(it, StandardCharsets.UTF_8))?.groupValues?.get(1) }
                    ?.let { try { java.net.URLDecoder.decode(it.removePrefix("./"), "UTF-8") } catch (_: Throwable) { it } }
                val rootName = manifestRoot?.takeIf { packageFiles.containsKey(it) } ?: packageFiles.keys.firstOrNull { it.lowercase(Locale.ROOT).endsWith(".dae") } ?: return
                daeBytes = packageFiles[rootName] ?: return
                val dir = rootName.substringBeforeLast('/', "")
                val lower = packageFiles.keys.associateBy { it.lowercase(Locale.ROOT) }
                val output = object : GltfOutput by sceneOutput {
                    override fun resource(uri: String): ByteArray? {
                        val clean = uri.replace('\\', '/').removePrefix("./")
                        val joined = if (dir.isEmpty()) clean else "$dir/$clean"
                        return packageFiles[joined] ?: packageFiles[clean]
                            ?: lower[joined.lowercase(Locale.ROOT)]?.let { packageFiles[it] }
                            ?: packageFiles.entries.firstOrNull { it.key.substringAfterLast('/').equals(clean.substringAfterLast('/'), true) }?.value
                            ?: sceneOutput.resource(uri)
                    }
                }
                val rootEl = CleanRoomCollada.parse(daeBytes) ?: return
                CleanRoomCollada(rootEl, output).load()
            } else {
                val rootEl = CleanRoomCollada.parse(daeBytes) ?: return
                CleanRoomCollada(rootEl, sceneOutput).load()
            }
            colladaYUp = true
        } catch (e: SecurityException) {
            lastErrorMsg = e.message
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for ISO 10303-21 STEP (`.step` / `.stp`) CAD models.
     * Extracts `CARTESIAN_POINT`, `VERTEX_POINT`, `EDGE_CURVE`, `POLY_LOOP`, `B_SPLINE_CURVE_WITH_KNOTS`,
     * and triangulates faceted B-Rep loops and wireframe topology.
     */
    private fun parseStepCleanRoom(stream: InputStream) {
        val text = stream.readBytes().toString(StandardCharsets.ISO_8859_1)
        val entities = try { StepParser.parse(text) } catch (_: Throwable) { return }
        try {
            CleanRoomStep(entities, sceneOutput).load()
        } catch (_: Throwable) {}
    }

    private fun parseIgesCleanRoom(stream: InputStream) {
        val text = stream.readBytes().toString(StandardCharsets.ISO_8859_1)
        val entities = try { IgesParser.parse(text) } catch (_: Throwable) { return }
        try {
            CleanRoomIges(entities, sceneOutput).load()
        } catch (_: Throwable) {}
    }

    /**
     * Clean-Room parser for AutoCAD `.dwg` files (`AC1012` R13 through `AC1032` R2018+) without RealDWG.
     */
    private fun parseDwgCleanRoom() {
        val statSize = resolveDescriptorFileSize()
        val maxRead = minOf(if (statSize > 0L) statSize else 128L * 1024L * 1024L, 128L * 1024L * 1024L).toInt()
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

        val geometryLoaded = try {
            val bridge = com.omnireader.core.engine.impl.GplCoreEngineBridgeLoader.get()
            if (bridge != null) {
                bridge.renderDwg(bytes, password, object : com.omnireader.core.engine.impl.DwgGeometrySink {
                    override val isFull: Boolean get() = isGeometryTruncated
                    override fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) {
                        addLineSegment(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), dwgColor(color))
                    }
                    override fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int) {
                        addTriangle(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), x3.toFloat(), y3.toFloat(), z3.toFloat(), dwgColor(color))
                    }
                    override fun textWidth(value: String, height: Double): Double = com.omnireader.core.engine.MeshSceneRenderer.measureLabelWidth(value, height)
                    override fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int) {
                        if (textLabels.size >= 20_000 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
                        textLabels.add(CadTextLabel3D(value, x.toFloat(), y.toFloat(), z.toFloat(), height.toFloat(), rotationDeg.toFloat(), dwgColor(color)))
                        updateBounds(x.toFloat(), y.toFloat(), z.toFloat())
                    }
                })
                vertexCount > 0 || textLabels.isNotEmpty()
            } else {
                false
            }
        } catch (e: com.omnireader.core.engine.impl.DwgFormatException) {
            val msg = e.message ?: ""
            val isSecErr = msg.startsWith("PASSWORD_REQUIRED") || msg.startsWith("UNSUPPORTED_ENCRYPTION") || msg.startsWith("DRM_PROTECTED")
            lastErrorMsg = if (isSecErr) msg else "DWG ($releaseName): $msg"
            if (isSecErr) {
                dwgPreviewBitmap = null
                return
            }
            false
        } catch (e: OutOfMemoryError) {
            lastErrorMsg = "DWG ($releaseName) is too large to load."
            false
        }
        if (geometryLoaded) {
            lastErrorMsg = null
            return
        }

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
        if (dwgPreviewBitmap != null) lastErrorMsg = null
    }

    private fun dwgColor(c: Int): Int = if (c < 0) (0xFF shl 24) or (c and 0xFFFFFF) else aciToColor(c)

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




    private fun updateBounds(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    private fun exportAnimation(): com.omnireader.core.engine.MeshAnimation? {
        val clip = animClip ?: return null
        val polys = vertexCount / 9
        if (polys == 0 || clip.frames < 2 || clip.matrices.size != clip.frames * clip.bones * 12) return null
        val tb = triBone ?: return null
        return com.omnireader.core.engine.MeshAnimation(
            clip.fps, clip.frames, clip.bones, clip.matrices,
            tb.copyOf(polys),
            skinOffsets?.copyOf(polys),
            if (skinCount > 0) skinJointsArr.copyOf(skinCount * 3 * skinK) else null,
            if (skinCount > 0) skinWeightsArr.copyOf(skinCount * 3 * skinK) else null,
            if (skinCount > 0) skinBindArr.copyOf(skinCount * 9) else null,
            if (skinCount > 0 && skinHasNormals) skinBindNrmArr.copyOf(skinCount * 9) else null,
            if (morphEntries > 0) clip.morphCount else 0,
            if (morphEntries > 0) morphPolyArr.copyOf(morphEntries) else null,
            if (morphEntries > 0) morphTargetArr.copyOf(morphEntries) else null,
            if (morphEntries > 0) morphDeltaArr.copyOf(morphEntries * 9) else null,
            clip.morphBase,
            clip.morphWeights,
            if (morphEntries > 0 && morphHasNormals) morphNormalArr.copyOf(morphEntries * 9) else null,
            clip.clipNames,
            clip.clipStarts,
            clip.clipFrames,
            skinK,
            if (skinCount > 0 && skinHasDq) skinDqArr.copyOf(skinCount) else null
        )
    }

    private fun registerNormalTexture(key: String, bytes: ByteArray?, flipGreen: Boolean, strength: Float): Int {
        val k = "normal:$key:$flipGreen:$strength"
        textureKeys[k]?.let { return it }
        if (bytes == null || bytes.size < 8) {
            textureKeys[k] = -1
            return -1
        }
        val bmp = decodeTextureBitmap(bytes, true)
        if (bmp == null) {
            textureKeys[k] = -1
            return -1
        }
        val result = if (flipGreen || strength != 1f || !bmp.isMutable) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                var x = ((c shr 16) and 0xFF) / 127.5f - 1f
                var y = ((c shr 8) and 0xFF) / 127.5f - 1f
                var z = (c and 0xFF) / 127.5f - 1f
                if (flipGreen) y = -y
                if (strength != 1f) {
                    x *= strength
                    y *= strength
                    val l = sqrt(x * x + y * y + z * z)
                    if (l > 0f) {
                        x /= l; y /= l; z /= l
                    }
                }
                fun enc(v: Float) = ((v + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (enc(x) shl 16) or (enc(y) shl 8) or enc(z)
            }
            if (bmp.isMutable) {
                bmp.setPixels(px, 0, w, 0, 0, w, h)
                bmp
            } else {
                bmp.recycle()
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
        } else bmp
        textures.add(result)
        textureBudgetPixels -= result.width.toLong() * result.height
        val idx = textures.size - 1
        textureKeys[k] = idx
        return idx
    }

    private fun registerTexture(key: String, bytes: ByteArray?, opaque: Boolean = false): Int {
        textureKeys[key]?.let { return it }
        if (bytes == null || bytes.size < 8) {
            textureKeys[key] = -1
            return -1
        }
        val bmp = decodeTextureBitmap(bytes, opaque)
        if (bmp == null) {
            textureKeys[key] = -1
            return -1
        }
        textures.add(bmp)
        textureBudgetPixels -= bmp.width.toLong() * bmp.height
        val idx = textures.size - 1
        textureKeys[key] = idx
        return idx
    }

    private fun decodeTextureBitmap(bytes: ByteArray, opaque: Boolean = false): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            CleanRoomTextureDecoders.decode(bytes)?.let { d ->
                val limit = if (textureBudgetPixels < 8L * 1024L * 1024L) 1024 else 2048
                var step = 1
                while (maxOf(d.width, d.height) / step > limit) step *= 2
                val w = d.width / step
                val h = d.height / step
                val px = if (step == 1) d.argb else IntArray(w * h) { d.argb[(it / w) * step * d.width + (it % w) * step] }
                if (opaque) for (k in px.indices) px[k] = px[k] or (0xFF shl 24)
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
        } else {
            val limit = when {
                textureBudgetPixels <= 0L -> 256
                textureBudgetPixels < 8L * 1024L * 1024L -> 1024
                else -> 2048
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (opaque) inPremultiplied = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { decoded ->
                val argb = if (decoded.config == Bitmap.Config.ARGB_8888 && !opaque) decoded else {
                    val px = IntArray(decoded.width * decoded.height)
                    decoded.getPixels(px, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                    if (opaque) for (k in px.indices) px[k] = px[k] or (0xFF shl 24)
                    Bitmap.createBitmap(px, decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { decoded.recycle() }
                }
                argb
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun ensureDetailCapacity(polyIndex: Int, withUv: Boolean, withColors: Boolean) {
        val cap = polyColors.size
        if (withUv) {
            val uvArr = triUvs
            if (uvArr == null || uvArr.size < cap * 6) triUvs = (uvArr ?: FloatArray(0)).copyOf(cap * 6)
            val texArr = triTex
            if (texArr == null || texArr.size < cap) {
                val old = texArr?.size ?: 0
                triTex = (texArr ?: IntArray(0)).copyOf(cap).also { java.util.Arrays.fill(it, old, cap, -1) }
            }
        }
        if (withColors) {
            val c = cornerColors
            if (c == null || c.size < cap * 3) cornerColors = (c ?: IntArray(0)).copyOf(cap * 3)
        }
    }

    private fun addTriangle(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        customColor: Int = 0,
        uv: FloatArray? = null,
        uvOffset: Int = 0,
        texture: Int = -1,
        c1: Int = 0,
        c2: Int = 0,
        c3: Int = 0,
        normals: FloatArray? = null,
        normalTex: Int = -1,
        anim: TriAnim? = null
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
        val hasUv = uv != null && (texture >= 0 || normalTex >= 0)
        val hasColors = c1 != 0 || c2 != 0 || c3 != 0
        if (hasUv || hasColors || triTex != null || cornerColors != null) {
            ensureDetailCapacity(polyIndex, hasUv || triTex != null, hasColors || cornerColors != null)
            triTex?.let { it[polyIndex] = if (hasUv) texture else -1 }
            if (hasUv) System.arraycopy(uv!!, uvOffset, triUvs ?: return, polyIndex * 6, 6)
            cornerColors?.let {
                it[polyIndex * 3] = c1
                it[polyIndex * 3 + 1] = c2
                it[polyIndex * 3 + 2] = c3
            }
        }
        if (normals != null || cornerNormals != null) {
            val cap = polyColors.size * 9
            val cn = cornerNormals
            if (cn == null || cn.size < cap) cornerNormals = (cn ?: FloatArray(0)).copyOf(cap)
            val dst = cornerNormals!!
            if (normals != null) System.arraycopy(normals, 0, dst, polyIndex * 9, 9) else java.util.Arrays.fill(dst, polyIndex * 9, polyIndex * 9 + 9, 0f)
        }

        val cap = polyColors.size
        if ((hasUv && normalTex >= 0) || triNormalTex != null) {
            val nt = triNormalTex
            if (nt == null || nt.size < cap) {
                val old = nt?.size ?: 0
                triNormalTex = (nt ?: IntArray(0)).copyOf(cap).also { java.util.Arrays.fill(it, old, cap, -1) }
            }
            triNormalTex!![polyIndex] = if (hasUv) normalTex else -1
        }
        if (anim != null || triBone != null) {
            val tb = triBone
            if (tb == null || tb.size < cap) {
                val old = tb?.size ?: 0
                triBone = (tb ?: IntArray(0)).copyOf(cap).also { java.util.Arrays.fill(it, old, cap, -1) }
                val so = skinOffsets
                skinOffsets = (so ?: IntArray(0)).copyOf(cap).also { java.util.Arrays.fill(it, so?.size ?: 0, cap, -1) }
            }
            val bones = triBone!!
            val joints = anim?.joints
            val weights = anim?.weights
            val bind = anim?.bindPos
            if (joints != null && weights != null && bind != null && joints.size >= 3 && joints.size % 3 == 0 && weights.size >= joints.size && bind.size >= 9) {
                val k = (joints.size / 3).coerceAtMost(64)
                if (k > skinK) {
                    val nj = IntArray(skinJointsArr.size / skinK * k) { -1 }
                    val nw = FloatArray(nj.size)
                    for (e in 0 until skinCount) for (c in 0 until 3) {
                        System.arraycopy(skinJointsArr, e * 3 * skinK + c * skinK, nj, e * 3 * k + c * k, skinK)
                        System.arraycopy(skinWeightsArr, e * 3 * skinK + c * skinK, nw, e * 3 * k + c * k, skinK)
                    }
                    skinJointsArr = nj
                    skinWeightsArr = nw
                    skinK = k
                }
                val kk = skinK
                if (skinJointsArr.size < (skinCount + 1) * 3 * kk) {
                    val n = maxOf(64, skinCount * 2)
                    val old = skinJointsArr.size
                    skinJointsArr = skinJointsArr.copyOf(n * 3 * kk).also { java.util.Arrays.fill(it, old, it.size, -1) }
                    skinWeightsArr = skinWeightsArr.copyOf(n * 3 * kk)
                    skinBindArr = skinBindArr.copyOf(n * 9)
                    skinBindNrmArr = skinBindNrmArr.copyOf(n * 9)
                    skinDqArr = skinDqArr.copyOf(n).also { java.util.Arrays.fill(it, skinCount, it.size, -1) }
                }
                for (c in 0 until 3) for (j in 0 until kk) {
                    val inside = j < k
                    skinJointsArr[skinCount * 3 * kk + c * kk + j] = if (inside) joints[c * k + j] else -1
                    skinWeightsArr[skinCount * 3 * kk + c * kk + j] = if (inside) weights[c * k + j] else 0f
                }
                skinDqArr[skinCount] = anim.dqPre
                if (anim.dqPre >= 0) skinHasDq = true
                System.arraycopy(bind, 0, skinBindArr, skinCount * 9, 9)
                val bn = anim.bindNrm
                if (bn != null && bn.size >= 9) {
                    System.arraycopy(bn, 0, skinBindNrmArr, skinCount * 9, 9)
                    skinHasNormals = true
                }
                bones[polyIndex] = -2
                skinOffsets!![polyIndex] = skinCount
                skinCount++
            } else {
                bones[polyIndex] = anim?.bone ?: -1
                skinOffsets!![polyIndex] = -1
            }
            val mt = anim?.morphTargets
            val md = anim?.morphDeltas
            val mnd = anim?.morphNormalDeltas
            if (mt != null && md != null) {
                for (k in mt.indices) {
                    if (md.size < (k + 1) * 9) break
                    if (morphPolyArr.size <= morphEntries) {
                        val n = maxOf(64, morphEntries * 2)
                        morphPolyArr = morphPolyArr.copyOf(n)
                        morphTargetArr = morphTargetArr.copyOf(n)
                        morphDeltaArr = morphDeltaArr.copyOf(n * 9)
                        morphNormalArr = morphNormalArr.copyOf(n * 9)
                    }
                    morphPolyArr[morphEntries] = polyIndex
                    morphTargetArr[morphEntries] = mt[k]
                    System.arraycopy(md, k * 9, morphDeltaArr, morphEntries * 9, 9)
                    if (mnd != null && mnd.size >= (k + 1) * 9) {
                        System.arraycopy(mnd, k * 9, morphNormalArr, morphEntries * 9, 9)
                        morphHasNormals = true
                    } else {
                        java.util.Arrays.fill(morphNormalArr, morphEntries * 9, morphEntries * 9 + 9, 0f)
                    }
                    morphEntries++
                }
            }
        }

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
    private class ObjMaterial(
        var color: Int = 0, var alpha: Float = 1f, var texture: Int = -1, var uScale: Float = 1f, var vScale: Float = 1f,
        var uOffset: Float = 0f, var vOffset: Float = 0f, var normalTex: Int = -1
    )

    private class ObjMap(val file: String?, val uScale: Float?, val vScale: Float?, val uOffset: Float?, val vOffset: Float?, val bumpMultiplier: Float?)

    private fun objMapArgs(parts: List<String>): ObjMap {
        var k = 1
        var uS: Float? = null
        var vS: Float? = null
        var uO: Float? = null
        var vO: Float? = null
        var bm: Float? = null
        while (k < parts.size) {
            val t = parts[k]
            if (t.startsWith("-") && t.length > 1 && t[1].isLetter()) {
                val nums = ArrayList<Float>()
                var q = k + 1
                while (q < parts.size && nums.size < 3) {
                    val f = parts[q].toFloatOrNull() ?: break
                    nums.add(f)
                    q++
                }
                when (t.lowercase(Locale.ROOT)) {
                    "-s" -> { uS = nums.getOrElse(0) { 1f }; vS = nums.getOrElse(1) { uS!! } }
                    "-o" -> { uO = nums.getOrElse(0) { 0f }; vO = nums.getOrElse(1) { 0f } }
                    "-bm" -> bm = nums.getOrNull(0)
                }
                k = if (nums.isEmpty() && q < parts.size && t.lowercase(Locale.ROOT) in setOf("-clamp", "-blendu", "-blendv", "-imfchan", "-type", "-texres", "-cc")) q + 1 else q
                continue
            }
            return ObjMap(parts.subList(k, parts.size).joinToString(" "), uS, vS, uO, vO, bm)
        }
        return ObjMap(null, uS, vS, uO, vO, bm)
    }

    private fun parseMtl(bytes: ByteArray, into: HashMap<String, ObjMaterial>) {
        var current: ObjMaterial? = null
        val text = String(bytes, StandardCharsets.UTF_8)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            val key = parts[0].lowercase(Locale.ROOT)
            if (key == "newmtl") {
                current = ObjMaterial().also { into[line.substringAfter(parts[0]).trim()] = it }
                continue
            }
            val m = current ?: continue
            when (key) {
                "kd" -> if (parts.size >= 4) {
                    val r = parts[1].toFloatOrNull() ?: continue
                    val g = parts[2].toFloatOrNull() ?: r
                    val b = parts[3].toFloatOrNull() ?: r
                    m.color = Color.rgb((r.coerceIn(0f, 1f) * 255f).roundToInt(), (g.coerceIn(0f, 1f) * 255f).roundToInt(), (b.coerceIn(0f, 1f) * 255f).roundToInt())
                } else if (parts.size == 2) {
                    val v = ((parts[1].toFloatOrNull() ?: continue).coerceIn(0f, 1f) * 255f).roundToInt()
                    m.color = Color.rgb(v, v, v)
                }
                "d" -> parts.getOrNull(parts.indexOfLast { it.toFloatOrNull() != null }.coerceAtLeast(1))?.toFloatOrNull()?.let { m.alpha = it.coerceIn(0f, 1f) }
                "tr" -> parts.getOrNull(1)?.toFloatOrNull()?.let { m.alpha = (1f - it).coerceIn(0f, 1f) }
                "map_kd" -> {
                    val map = objMapArgs(parts)
                    map.uScale?.let { m.uScale = it }
                    map.vScale?.let { m.vScale = it }
                    map.uOffset?.let { m.uOffset = it }
                    map.vOffset?.let { m.vOffset = it }
                    val file = map.file
                    if (file != null) {
                        val bytes2 = companionResource(file)
                        m.texture = if (bytes2 != null) registerTexture("obj:" + normalizeResourceRef(file), bytes2) else -1
                    }
                }
                "norm", "map_kn", "map_bump", "bump" -> {
                    if (m.normalTex >= 0) continue
                    val map = objMapArgs(parts)
                    val file = map.file ?: continue
                    val explicit = key == "norm" || key == "map_kn"
                    val bytes2 = if (explicit) companionResource(file) else companionResourceQuiet(file) ?: companionResource(file)
                    if (bytes2 != null && (explicit || NormalMapHeuristic.looksLikeNormalMap(bytes2))) {
                        m.normalTex = registerNormalTexture("obj:" + normalizeResourceRef(file), bytes2, false, map.bumpMultiplier ?: 1f)
                    }
                }
            }
        }
    }

    private fun parseObj(stream: InputStream) {
        var vertCoords = FloatArray(60_000)
        var vertColors = IntArray(20_000)
        var vertCount = 0
        var texCoords = FloatArray(0)
        var texCount = 0
        var normCoords = FloatArray(0)
        var normCount = 0
        val nTmp = FloatArray(9)
        val maxVerts = 3_000_000
        val materials = HashMap<String, ObjMaterial>()
        var active: ObjMaterial? = null
        val uvTmp = FloatArray(6)
        val ws = Regex("\\s+")

        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            val faceV = IntArray(256)
            val faceT = IntArray(256)
            val faceN = IntArray(256)
            var pending: String? = null

            while (!isGeometryTruncated) {
                var line = reader.readLine() ?: break
                if (pending != null) {
                    line = pending + " " + line
                    pending = null
                }
                if (line.endsWith("\\")) {
                    pending = line.dropLast(1)
                    continue
                }
                val p = line.trim()
                if (p.isEmpty() || p[0] == '#') continue
                if (p.startsWith("v ") || p.startsWith("v\t")) {
                    if (vertCount >= maxVerts) continue
                    val parts = p.split(ws)
                    if (parts.size < 4) continue
                    val vx = parts[1].toFloatOrNull() ?: continue
                    val vy = parts[2].toFloatOrNull() ?: continue
                    val vz = parts[3].toFloatOrNull() ?: continue
                    if ((vertCount + 1) * 3 > vertCoords.size) {
                        val nextCap = minOf(maxVerts * 3, vertCoords.size * 2)
                        vertCoords = vertCoords.copyOf(nextCap)
                        vertColors = vertColors.copyOf(nextCap / 3)
                    }
                    val base = vertCount * 3
                    vertCoords[base] = vx
                    vertCoords[base + 1] = vy
                    vertCoords[base + 2] = vz
                    vertColors[vertCount] = 0
                    if (parts.size >= 7) {
                        val r = parts[4].toFloatOrNull()
                        val g = parts[5].toFloatOrNull()
                        val b = parts[6].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            val scale = if (r <= 1f && g <= 1f && b <= 1f) 255f else 1f
                            vertColors[vertCount] = Color.rgb((r * scale).roundToInt().coerceIn(0, 255), (g * scale).roundToInt().coerceIn(0, 255), (b * scale).roundToInt().coerceIn(0, 255))
                        }
                    }
                    vertCount++
                } else if (p.startsWith("vt ") || p.startsWith("vt\t")) {
                    val parts = p.split(ws)
                    val u = parts.getOrNull(1)?.toFloatOrNull() ?: continue
                    val v = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    if ((texCount + 1) * 2 > texCoords.size) texCoords = texCoords.copyOf(maxOf(1024, texCoords.size * 2))
                    texCoords[texCount * 2] = u
                    texCoords[texCount * 2 + 1] = v
                    texCount++
                } else if (p.startsWith("vn ") || p.startsWith("vn\t")) {
                    val parts = p.split(ws)
                    val nx = parts.getOrNull(1)?.toFloatOrNull() ?: continue
                    val ny = parts.getOrNull(2)?.toFloatOrNull() ?: continue
                    val nz = parts.getOrNull(3)?.toFloatOrNull() ?: continue
                    if ((normCount + 1) * 3 > normCoords.size) normCoords = normCoords.copyOf(maxOf(3072, normCoords.size * 2))
                    normCoords[normCount * 3] = nx
                    normCoords[normCount * 3 + 1] = ny
                    normCoords[normCount * 3 + 2] = nz
                    normCount++
                } else if (p.startsWith("mtllib")) {
                    val names = p.substring(6).trim()
                    if (names.isNotEmpty()) {
                        val whole = companionResourceQuiet(names)
                        if (whole != null) {
                            parseMtl(whole, materials)
                        } else {
                            for (n in names.split(ws)) companionResource(n)?.let { parseMtl(it, materials) }
                        }
                    }
                } else if (p.startsWith("usemtl")) {
                    active = materials[p.substring(6).trim()]
                } else if ((p.startsWith("f ") || p.startsWith("f\t")) && vertCount >= 3) {
                    val parts = p.split(ws)
                    var fLen = 0
                    for (k in 1 until parts.size) {
                        if (fLen >= faceV.size) break
                        val tok = parts[k]
                        if (tok.isEmpty()) continue
                        val s1 = tok.indexOf('/')
                        val vi = (if (s1 >= 0) tok.substring(0, s1) else tok).toIntOrNull() ?: continue
                        val rv = if (vi < 0) vertCount + vi else vi - 1
                        if (rv !in 0 until vertCount) continue
                        var rt = -1
                        var rn = -1
                        if (s1 >= 0) {
                            val s2 = tok.indexOf('/', s1 + 1)
                            val ts = if (s2 >= 0) tok.substring(s1 + 1, s2) else tok.substring(s1 + 1)
                            ts.toIntOrNull()?.let { ti -> rt = if (ti < 0) texCount + ti else ti - 1 }
                            if (rt !in 0 until texCount) rt = -1
                            if (s2 >= 0) {
                                tok.substring(s2 + 1).toIntOrNull()?.let { ni -> rn = if (ni < 0) normCount + ni else ni - 1 }
                                if (rn !in 0 until normCount) rn = -1
                            }
                        }
                        faceV[fLen] = rv
                        faceT[fLen] = rt
                        faceN[fLen] = rn
                        fLen++
                    }
                    if (fLen < 3) continue
                    val mat = active
                    val tex = mat?.texture ?: -1
                    val ntex = mat?.normalTex ?: -1
                    val alpha = ((mat?.alpha ?: 1f) * 255f).roundToInt().coerceIn(0, 255)
                    val baseColor = when {
                        tex >= 0 -> Color.argb(alpha, 255, 255, 255)
                        mat != null && mat.color != 0 -> Color.argb(alpha, Color.red(mat.color), Color.green(mat.color), Color.blue(mat.color))
                        mat != null && alpha < 255 -> Color.argb(alpha, 200, 200, 200)
                        else -> 0
                    }
                    val i0 = faceV[0]
                    for (i in 1 until fLen - 1) {
                        val a = faceV[0]; val b = faceV[i]; val c = faceV[i + 1]
                        val useUv = (tex >= 0 || ntex >= 0) && faceT[0] >= 0 && faceT[i] >= 0 && faceT[i + 1] >= 0
                        val useN = faceN[0] >= 0 && faceN[i] >= 0 && faceN[i + 1] >= 0
                        if (useN) {
                            val ns = intArrayOf(faceN[0], faceN[i], faceN[i + 1])
                            for (k in 0 until 3) {
                                val nx = normCoords[ns[k] * 3]
                                val ny = normCoords[ns[k] * 3 + 1]
                                val nz = normCoords[ns[k] * 3 + 2]
                                val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                                nTmp[k * 3] = nx / l
                                nTmp[k * 3 + 1] = ny / l
                                nTmp[k * 3 + 2] = nz / l
                            }
                        }
                        if (useUv) {
                            val m = mat!!
                            val ts = intArrayOf(faceT[0], faceT[i], faceT[i + 1])
                            for (k in 0 until 3) {
                                uvTmp[k * 2] = texCoords[ts[k] * 2] * m.uScale + m.uOffset
                                uvTmp[k * 2 + 1] = 1f - (texCoords[ts[k] * 2 + 1] * m.vScale + m.vOffset)
                            }
                        }
                        val hasVc = vertColors[a] != 0 || vertColors[b] != 0 || vertColors[c] != 0
                        fun vc(idx: Int): Int {
                            val col = vertColors[idx]
                            return if (col == 0) 0 else Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col))
                        }
                        addTriangle(
                            vertCoords[a * 3], vertCoords[a * 3 + 1], vertCoords[a * 3 + 2],
                            vertCoords[b * 3], vertCoords[b * 3 + 1], vertCoords[b * 3 + 2],
                            vertCoords[c * 3], vertCoords[c * 3 + 1], vertCoords[c * 3 + 2],
                            if (hasVc && !useUv) vertColors[i0].let { if (it != 0) vc(i0) else baseColor } else baseColor,
                            if (useUv) uvTmp else null, 0, if (useUv) tex else -1,
                            if (hasVc) vc(a) else 0, if (hasVc) vc(b) else 0, if (hasVc) vc(c) else 0,
                            if (useN) nTmp else null, if (useUv) ntex else -1
                        )
                    }
                } else if (p.startsWith("l ") && vertCount >= 2) {
                    val parts = p.split(ws)
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
                                    vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2],
                                    active?.color ?: 0
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

    private var colladaYUp: Boolean? = null

    private val sceneRenderer = com.omnireader.core.engine.MeshSceneRenderer()

    override fun exportMeshScene(): com.omnireader.core.engine.MeshScene {
        val extLower = originalExtension.lowercase(Locale.ROOT)
        val isYUp = colladaYUp ?: (extLower in setOf("obj", "gltf", "glb", "usdz", "usd", "usda", "usdc", "fbx") ||
                modelType.contains("glTF", ignoreCase = true) ||
                modelType.contains("USD", ignoreCase = true) ||
                modelType.contains("FBX", ignoreCase = true))
        return com.omnireader.core.engine.MeshScene(
            vertices = rawVertices,
            vertexCount = vertexCount,
            polyColors = polyColors,
            minX = minX, maxX = maxX,
            minY = minY, maxY = maxY,
            minZ = minZ, maxZ = maxZ,
            isYUp = isYUp,
            modelType = modelType,
            isTruncated = isGeometryTruncated,
            maxPolygons = maxPolygonsLimit,
            labels = textLabels.map {
                com.omnireader.core.engine.MeshLabel(it.text, it.x, it.y, it.z, it.height, it.rotationDeg, it.color)
            },
            preview = dwgPreviewBitmap?.takeIf { !it.isRecycled },
            vertexColors = cornerColors,
            uvs = triUvs?.takeIf { triTex != null && textures.isNotEmpty() },
            triTextures = triTex?.takeIf { triUvs != null && textures.isNotEmpty() },
            textures = textures,
            vertexNormals = cornerNormals,
            triNormalTextures = triNormalTex?.takeIf { triTex != null && textures.isNotEmpty() },
            animation = exportAnimation()
        )
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        sceneRenderer.render(exportMeshScene(), pageIndex, tileX, tileY, zoomPermille, bitmap)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        sceneRenderer.release()
        dwgPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        dwgPreviewBitmap = null
        textures.forEach { if (!it.isRecycled) it.recycle() }
        textures.clear()
        companionFiles.clear()
        try { pfd.close() } catch (_: Exception) {}
    }

    init {
        parseModel()
    }
}
