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
import me.hlebkukleuski.fullfilestudio.core.document.mesh.Gles30MeshRenderer
import me.hlebkukleuski.fullfilestudio.core.document.mesh.Gles30OffscreenContext
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileEngineProvider
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import org.json.JSONObject

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
    internal val pfd: ParcelFileDescriptor,
    internal val originalExtension: String,
    internal val password: String? = null
) : DocumentSession, me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneSource {
    @Volatile private var isClosed = false
    @Volatile internal var lastErrorMsg: String? = null
    @Volatile internal var isGeometryTruncated = false

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
    internal var vertexCount = 0
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
    internal var animClip: AnimClip? = null
    private val textures = ArrayList<Bitmap>()
    private val textureKeys = HashMap<String, Int>()
    private var textureBudgetPixels = initialTextureBudget()

    private fun initialTextureBudget(): Long = (Runtime.getRuntime().maxMemory() / 16L).coerceIn(4L * 1024L * 1024L, 48L * 1024L * 1024L)
    internal val textLabels = mutableListOf<CadTextLabel3D>()
    internal var dxfEmittedOpsCount = 0
    internal var dwgPreviewBitmap: Bitmap? = null

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    internal var modelType = "Unknown"

    internal val maxPolygonsLimit: Int = computeDynamicPolygonLimit()
    internal val maxVertexFloats: Int = maxPolygonsLimit * 9


    companion object {
        internal const val MAX_INSERT_DEPTH = 8
        internal const val MAX_DXF_EMIT_OPS = 500_000
        internal const val MAX_TEXT_LABELS_LIMIT = 4_000
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

        internal val DWG_IMAGE_SENTINEL = byteArrayOf(
            0x1F.toByte(), 0x25.toByte(), 0x6D.toByte(), 0x07.toByte(),
            0xD4.toByte(), 0x36.toByte(), 0x28.toByte(), 0x28.toByte(),
            0x9D.toByte(), 0x57.toByte(), 0xCA.toByte(), 0x3F.toByte(),
            0x9D.toByte(), 0x44.toByte(), 0x10.toByte(), 0x2B.toByte()
        )

        /**
         * Maps standard DWG Color Index (ACI 1..255) to ARGB color.
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

    internal fun resolveDescriptorFileSize(): Long {
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

    internal fun readDescriptorSliceAt(offset: Long, length: Int): ByteArray {
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

    internal fun readDescriptorBytes(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val exactSize = resolveDescriptorFileSize()
        val cap = minOf(if (exactSize > 0L) exactSize else maxBytes.toLong(), maxBytes.toLong()).toInt()
        return readDescriptorSliceAt(0L, cap)
    }

    private val companionFiles = HashMap<String, ByteArray>()
    internal val missingResources = LinkedHashSet<String>()

    internal fun normalizeResourceRef(ref: String): String {
        val decoded = try { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { ref }
        var n = decoded.replace('\\', '/').trim().trim('"')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.removePrefix("file://").removePrefix("file:")
        return n.lowercase(Locale.ROOT)
    }

    internal fun companionResource(ref: String): ByteArray? {
        if (ref.isBlank()) return null
        val n = normalizeResourceRef(ref)
        companionFiles[n]?.let { return it }
        companionFiles[n.substringAfterLast('/')]?.let { return it }
        val original = try { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { ref }
        missingResources.add(original.replace('\\', '/').trim().removePrefix("./"))
        return null
    }

    internal fun companionResourceQuiet(ref: String): ByteArray? {
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
                        parseBlendScene()
                    }
                    is3mfMagic -> {
                        modelType = "3MF 3D Model"
                        parse3mfModel()
                    }
                    isUsdcMagic || isUsdaMagic || extLower in setOf("usdz", "usd", "usda", "usdc") ||
                            (isZipMagic && extLower in setOf("usdz", "usd")) -> {
                        modelType = "USDZ / USD 3D"
                        parseUsdOrUsdzScene()
                    }
                    isZipMagic && extLower == "zae" -> {
                        modelType = "COLLADA (ZAE)"
                        parseColladaDaeScene()
                    }
                    isZipMagic -> {
                        // Inspect ZIP container: could be 3MF or USDZ opened without extension
                        parse3mfModel()
                        if (vertexCount == 0) {
                            parseUsdOrUsdzScene()
                        }
                        if (vertexCount == 0) {
                            modelType = "COLLADA (ZAE)"
                            parseColladaDaeScene()
                        }
                    }
                    isGlbMagic || isGltfJson || extLower in setOf("glb", "gltf") -> {
                        modelType = if (isGlbMagic || extLower == "glb") "GLB (glTF 2.0)" else "glTF 2.0"
                        parseGltfOrGlbScene()
                    }
                    isFbxMagic -> {
                        modelType = "FBX 3D"
                        parseFbxScene()
                    }
                    isColladaMagic -> {
                        modelType = "COLLADA (.dae)"
                        parseColladaDaeScene()
                    }
                    isStepMagic -> {
                        modelType = "STEP (ISO 10303-21)"
                        openSafeStream { parseStepModel(it) }
                    }
                    isIgesMagic -> {
                        modelType = "IGES CAD"
                        openSafeStream { parseIgesModel(it) }
                    }
                    isDwgMagic || extLower == "dwg" -> {
                        modelType = "DWG"
                        parseDwgDrawing()
                    }
                    is3dsMagic -> {
                        modelType = "3DS"
                        parse3dsScene(exactFileSize)
                    }
                    headerStr.startsWith("ply") || extLower == "ply" -> {
                        modelType = "PLY"
                        parsePlyMesh()
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

    internal inline fun openSafeStream(block: (InputStream) -> Unit) {
        val fileSize = resolveDescriptorFileSize().let { if (it > 0L) it else 500_000_000L }
        BufferedInputStream(CadPreadInputStream(pfd.fileDescriptor, fileSize), 64 * 1024).use(block)
    }


    internal var usesDraco = false

    internal val sceneOutput = object : GltfOutput {
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
            TextureDecoders.decode(bytes) ?: run {
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

    internal fun updateBounds(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    private fun exportAnimation(): me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshAnimation? {
        val clip = animClip ?: return null
        val polys = vertexCount / 9
        if (polys == 0 || clip.frames < 2 || clip.matrices.size != clip.frames * clip.bones * 12) return null
        val tb = triBone ?: return null
        return me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshAnimation(
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

    internal fun registerNormalTexture(key: String, bytes: ByteArray?, flipGreen: Boolean, strength: Float): Int {
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

    internal fun registerTexture(key: String, bytes: ByteArray?, opaque: Boolean = false): Int {
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
            TextureDecoders.decode(bytes)?.let { d ->
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

    internal fun addTriangle(
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
            if (hasUv) System.arraycopy(uv, uvOffset, triUvs ?: return, polyIndex * 6, 6)
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

    internal fun addLineSegment(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        customColor: Int = 0
    ) {
        addTriangle(x1, y1, z1, x2, y2, z2, x1, y1, z1, customColor)
    }


    internal var colladaYUp: Boolean? = null

    private val sceneRenderer = me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneRenderer()

    override fun exportMeshScene(): me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshScene {
        val extLower = originalExtension.lowercase(Locale.ROOT)
        val isYUp = colladaYUp ?: (extLower in setOf("obj", "gltf", "glb", "usdz", "usd", "usda", "usdc", "fbx") ||
                modelType.contains("glTF", ignoreCase = true) ||
                modelType.contains("USD", ignoreCase = true) ||
                modelType.contains("FBX", ignoreCase = true))
        return me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshScene(
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
                me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshLabel(it.text, it.x, it.y, it.z, it.height, it.rotationDeg, it.color)
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
