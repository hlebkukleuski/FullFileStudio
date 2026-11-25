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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgFormatException
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgGeometrySink
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridgeLoader
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource
import org.json.JSONObject

class BuiltIn3dMeshSession(
    private val pfd: ParcelFileDescriptor,
    private val originalExtension: String,
    private val password: String? = null
) : DocumentSession, me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneSource {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    @Volatile private var isGeometryTruncated = false

    override val is3dContent: Boolean = true
    override val pageCount: Int = 1

    private val readableSource = ZeroCacheReadableSource(pfd.fileDescriptor)

    private data class BlendBlock(
        val code: String,
        val dataOffset: Int,
        val len: Int,
        val sdnaIndex: Int,
        val count: Int
    )

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private var rawVertices = FloatArray(30_000)
    private var polyColors = IntArray(3_500)
    private var vertexCount = 0
    private var previewBitmap: Bitmap? = null
    private val meshLabels = ArrayList<me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshLabel>()

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private var modelType = "3D Model"

    private val maxPolygonsLimit: Int = computeDynamicPolygonLimit()
    private val maxVertexFloats: Int = maxPolygonsLimit * 9

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    companion object {
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

        private fun dwgColor(c: Int): Int = if (c < 0) (0xFF shl 24) or (c and 0xFFFFFF) else aciToColor(c)

        private fun aciToColor(aci: Int): Int {
            return when (abs(aci)) {
                1 -> Color.rgb(255, 59, 48)
                2 -> Color.rgb(255, 204, 0)
                3 -> Color.rgb(40, 205, 65)
                4 -> Color.rgb(0, 199, 190)
                5 -> Color.rgb(10, 132, 255)
                6 -> Color.rgb(191, 90, 242)
                7 -> 0
                8 -> Color.rgb(142, 142, 147)
                9 -> Color.rgb(199, 199, 204)
                in 10..249 -> Color.HSVToColor(floatArrayOf((((abs(aci) - 10) / 10) * 15f) % 360f, 0.80f, 0.92f))
                else -> 0
            }
        }
    }

    init {
        parseModel()
    }

    private fun readSliceAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0L) return ByteArray(0)
        val buf = ByteBuffer.allocateDirect(length)
        val got = readableSource.readAt(offset, buf)
        if (got <= 0) return ByteArray(0)
        buf.flip()
        val out = ByteArray(got)
        buf.get(out)
        return out
    }

    private fun readHeaderOrFullBytes(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val exactSize = readableSource.size
        val cap = minOf(if (exactSize > 0L) exactSize else maxBytes.toLong(), maxBytes.toLong()).toInt()
        return readSliceAt(0L, cap)
    }

    private inline fun openSafeStream(block: (java.io.InputStream) -> Unit) {
        val fileSize = readableSource.size.let { if (it > 0L) it else 500_000_000L }
        java.io.BufferedInputStream(PositionalPreadInputStream(pfd.fileDescriptor, fileSize), 64 * 1024).use(block)
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
            rawVertices = rawVertices.copyOf(minOf(maxVertexFloats, maxOf(rawVertices.size * 2, vertexCount + 9)))
        }
        val polyIdx = vertexCount / 9
        if (polyIdx >= polyColors.size) {
            polyColors = polyColors.copyOf(minOf(maxPolygonsLimit, maxOf(polyColors.size * 2, polyIdx + 1024)))
        }
        polyColors[polyIdx] = customColor
        rawVertices[vertexCount++] = x1; rawVertices[vertexCount++] = y1; rawVertices[vertexCount++] = z1
        rawVertices[vertexCount++] = x2; rawVertices[vertexCount++] = y2; rawVertices[vertexCount++] = z2
        rawVertices[vertexCount++] = x3; rawVertices[vertexCount++] = y3; rawVertices[vertexCount++] = z3
        updateBounds(x1, y1, z1); updateBounds(x2, y2, z2); updateBounds(x3, y3, z3)
    }

    private fun addLineSegment(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        customColor: Int = 0
    ) {
        addTriangle(x1, y1, z1, x2, y2, z2, x1, y1, z1, customColor)
    }

    private fun parseModel() {
        try {
            val exactFileSize = readableSource.size
            val header = readSliceAt(0L, 1024)
            if (header.size < 4) {
                lastErrorMsg = "3D file is empty."
                return
            }

            val ext = originalExtension.lowercase(Locale.ROOT).trim()
            val headStr = String(header, 0, minOf(header.size, 960), StandardCharsets.UTF_8).trim().lowercase(Locale.ROOT)
            val isGlb = header.size >= 4 && header[0] == 'g'.code.toByte() && header[1] == 'l'.code.toByte() && header[2] == 'T'.code.toByte() && header[3] == 'F'.code.toByte()
            val isBlend = (header.size >= 7 && String(header, 0, 7, StandardCharsets.US_ASCII) == "BLENDER") || ext == "blend"
            val isUsdc = header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC"
            val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            val isDwg = (header.size >= 6 && header[0] == 'A'.code.toByte() && header[1] == 'C'.code.toByte() && header[2] == '1'.code.toByte() && header[3] == '0'.code.toByte()) || ext == "dwg"
            val isFbx = (header.size >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "Kaydara FBX Binary") ||
                    headStr.startsWith("; fbx") || (headStr.contains("fbxheaderextension") && headStr.contains("objects:")) || ext == "fbx"
            val is3ds = header.size >= 6 && (header[0].toInt() and 0xFF) == 0x4D && (header[1].toInt() and 0xFF) == 0x4D &&
                    (ext == "3ds" || (exactFileSize >= 16L && (ByteBuffer.wrap(header, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) == exactFileSize))
            val isCollada = headStr.contains("<collada") || ext == "dae"
            val isStep = headStr.startsWith("iso-10303-21;") || (headStr.contains("iso-10303-21;") && headStr.contains("header;")) || ext in setOf("step", "stp")
            val isIges = ext in setOf("iges", "igs")

            val isVerifiedBinaryStlSize = if (header.size >= 84 && exactFileSize >= 134L) {
                val triCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val expected = 84L + triCount * 50L
                triCount in 1L..25_000_000L && (exactFileSize in expected..(expected + 64L))
            } else false

            val hasAsciiStlKeywords = headStr.startsWith("solid") && (headStr.contains("facet") || headStr.contains("vertex"))

            when {
                isBlend -> {
                    modelType = "Blender 3D"
                    parseBlendBytes(readHeaderOrFullBytes(64 * 1024 * 1024))
                }
                ext == "3mf" -> {
                    modelType = "3MF 3D Model"
                    parse3mfStream()
                }
                isUsdc || headStr.startsWith("#usda") || ext in setOf("usdz", "usd", "usda", "usdc") || (isZip && ext == "usdz") -> {
                    modelType = "USDZ / USD 3D"
                    parseUsdStream()
                }
                isZip -> {
                    parse3mfStream()
                    if (vertexCount == 0) parseUsdStream()
                }
                isGlb || (headStr.startsWith("{") && headStr.contains("\"asset\"")) || ext in setOf("glb", "gltf") -> {
                    modelType = if (isGlb || ext == "glb") "GLB (glTF 2.0)" else "glTF 2.0"
                    parseGltfOrGlbStreaming(null)
                }
                isFbx -> {
                    modelType = "FBX 3D"
                    parseFbxBytes(readHeaderOrFullBytes(64 * 1024 * 1024))
                }
                isCollada -> {
                    modelType = "COLLADA (.dae)"
                    parseColladaBytes(readHeaderOrFullBytes(48 * 1024 * 1024))
                }
                isStep -> {
                    modelType = "STEP (ISO 10303-21)"
                    openSafeStream { parseStepStream(it) }
                }
                isIges -> {
                    modelType = "IGES CAD"
                    openSafeStream { parseIgesStream(it) }
                }
                isDwg -> {
                    modelType = "DWG"
                    parseDwgBytes(readHeaderOrFullBytes(128 * 1024 * 1024))
                }
                is3ds -> {
                    modelType = "3DS"
                    parse3dsStreaming(exactFileSize)
                }
                headStr.startsWith("ply") || ext == "ply" -> {
                    modelType = "PLY"
                    parsePlyStreaming()
                }
                headStr.startsWith("off") || headStr.startsWith("coff") || headStr.startsWith("noff") || ext == "off" -> {
                    modelType = "OFF"
                    openSafeStream { parseOffStream(it) }
                }
                ext == "dxf" || (headStr.contains("section") && headStr.contains("entities")) ||
                        (header.size >= 18 && String(header, 0, 18, StandardCharsets.US_ASCII) == "AutoCAD Binary DXF") -> {
                    modelType = "DXF"
                    openSafeStream { parseDxfStream(it) }
                }
                ext == "obj" || headStr.startsWith("v ") || headStr.startsWith("mtllib") || headStr.contains("\nv ") -> {
                    modelType = "OBJ"
                    openSafeStream { parseObjStream(it) }
                }
                isVerifiedBinaryStlSize -> {
                    modelType = "Binary STL"
                    parseBinaryStlPread(exactFileSize)
                }
                hasAsciiStlKeywords -> {
                    modelType = "ASCII STL"
                    openSafeStream { parseAsciiStlStream(it) }
                    if (vertexCount == 0) {
                        modelType = "Binary STL"
                        parseBinaryStlPread(exactFileSize)
                    }
                }
                else -> {
                    modelType = "Binary STL"
                    parseBinaryStlPread(exactFileSize)
                    if (vertexCount == 0 && headStr.startsWith("solid")) {
                        modelType = "ASCII STL"
                        openSafeStream { parseAsciiStlStream(it) }
                    }
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка разбора 3D/CAD модели: ${e.localizedMessage}"
        }
        if (vertexCount == 0 && previewBitmap == null && lastErrorMsg == null) {
            lastErrorMsg = "Модель $modelType пуста, повреждена или не содержит поддерживаемой геометрии."
        }
    }

    private fun parseGltfOrGlbStreaming(customBytes: ByteArray?) {
        var jsonString: String? = null
        val inlineBuffersList = ArrayList<ByteArray>()
        val fileBufferSlices = ArrayList<Pair<Long, Int>>()

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
            val fileSize = readableSource.size.let { if (it > 0L) it else 512L * 1024L * 1024L }
            val hdr = readSliceAt(0L, 12)
            if (hdr.size < 12) return
            val hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            if (hbb.getInt(0) == 0x46546C67) {
                var pos = 12L
                while (pos + 8L <= fileSize) {
                    val ch = readSliceAt(pos, 8)
                    if (ch.size < 8) break
                    val cbb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                    val chunkLen = cbb.getInt(0).toLong() and 0xFFFFFFFFL
                    val chunkType = cbb.getInt(4)
                    val dataStart = pos + 8L
                    if (chunkLen <= 0L || dataStart >= fileSize) break
                    val availLen = minOf(chunkLen, fileSize - dataStart)
                    if (chunkType == 0x4E4F534A && jsonString == null && availLen <= 32L * 1024L * 1024L) {
                        jsonString = String(readSliceAt(dataStart, availLen.toInt()), StandardCharsets.UTF_8)
                    } else if (chunkType == 0x004E4942) {
                        fileBufferSlices.add(dataStart to availLen.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    pos = dataStart + availLen
                }
            } else {
                jsonString = String(readHeaderOrFullBytes(48 * 1024 * 1024), StandardCharsets.UTF_8)
            }
        }

        val root = try { JSONObject(jsonString?.trim() ?: return) } catch (_: Throwable) { return }
        val jsonBuffers = root.optJSONArray("buffers")
        if (jsonBuffers != null) {
            for (i in 0 until jsonBuffers.length()) {
                val uri = jsonBuffers.optJSONObject(i)?.optString("uri", "") ?: ""
                if (uri.startsWith("data:", true) && uri.contains(";base64,")) {
                    val b64 = uri.substringAfter(";base64,").trim()
                    if (b64.length <= 64 * 1024 * 1024) {
                        try {
                            val decoded = Base64.decode(b64, Base64.DEFAULT)
                            if (i < inlineBuffersList.size) inlineBuffersList[i] = decoded else inlineBuffersList.add(decoded)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        if (inlineBuffersList.isEmpty() && fileBufferSlices.isEmpty()) return
        val bufferViews = root.optJSONArray("bufferViews") ?: return
        val accessors = root.optJSONArray("accessors") ?: return
        val meshes = root.optJSONArray("meshes") ?: return

        fun readBufSlice(bufIdx: Int, startOffset: Int, byteLength: Int): ByteArray? {
            if (startOffset < 0 || byteLength <= 0 || byteLength > 48 * 1024 * 1024) return null
            val inlineBuf = inlineBuffersList.getOrNull(bufIdx)
            if (inlineBuf != null) {
                if (startOffset >= inlineBuf.size) return null
                return inlineBuf.copyOfRange(startOffset, minOf(inlineBuf.size, startOffset + byteLength))
            }
            val fileSlice = fileBufferSlices.getOrNull(bufIdx) ?: return null
            if (startOffset >= fileSlice.second) return null
            return readSliceAt(fileSlice.first + startOffset.toLong(), minOf(byteLength, fileSlice.second - startOffset))
        }

        fun readPos(accIdx: Int): FloatArray? {
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bv = bufferViews.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
            val bufIdx = bv.optInt("buffer", 0)
            val stride = bv.optInt("byteStride", 12).coerceAtLeast(12)
            val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declared = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 3)
            if (declared <= 0) return null
            val slice = readBufSlice(bufIdx, start, (declared - 1) * stride + 12) ?: return null
            if (slice.size < 12) return null
            val count = minOf(declared, 1 + (slice.size - 12) / stride)
            val bBuf = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(count * 3)
            for (i in 0 until count) {
                val p = i * stride
                if (p + 12 > slice.size) break
                out[i * 3] = bBuf.getFloat(p)
                out[i * 3 + 1] = bBuf.getFloat(p + 4)
                out[i * 3 + 2] = bBuf.getFloat(p + 8)
            }
            return out
        }

        fun readIdx(accIdx: Int): IntArray? {
            val acc = accessors.optJSONObject(accIdx) ?: return null
            val bv = bufferViews.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
            val bufIdx = bv.optInt("buffer", 0)
            val compType = acc.optInt("componentType", 5123)
            val bpc = when (compType) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val stride = bv.optInt("byteStride", bpc).coerceAtLeast(bpc)
            val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val declared = acc.optInt("count", 0).coerceIn(0, maxPolygonsLimit * 6)
            if (declared <= 0) return null
            val slice = readBufSlice(bufIdx, start, (declared - 1) * stride + bpc) ?: return null
            if (slice.size < bpc) return null
            val count = minOf(declared, 1 + (slice.size - bpc) / stride)
            val bBuf = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { i ->
                val p = i * stride
                if (p + bpc > slice.size) 0 else when (compType) {
                    5120, 5121 -> slice[p].toInt() and 0xFF
                    5122, 5123 -> bBuf.getShort(p).toInt() and 0xFFFF
                    else -> bBuf.getInt(p)
                }
            }
        }

        val materials = root.optJSONArray("materials")
        val texturesJson = root.optJSONArray("textures")
        val imagesJson = root.optJSONArray("images")

        fun linearToSrgbChannel(linearVal: Double): Float {
            val c = linearVal.coerceIn(0.0, 1.0)
            val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
            return srgb.toFloat().coerceIn(0f, 1f)
        }

        val textureAvgCache = HashMap<Int, Int>()
        fun sampleTexColor(texIdx: Int): Int? {
            if (texIdx < 0 || texturesJson == null || imagesJson == null || texIdx >= texturesJson.length()) return null
            textureAvgCache[texIdx]?.let { return if (it == 0) null else it }
            val imgIdx = texturesJson.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
            if (imgIdx !in 0 until imagesJson.length()) {
                textureAvgCache[texIdx] = 0
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
                        if (bLen in 16..(16 * 1024 * 1024)) readBufSlice(bIdx, bOff, bLen) else null
                    } else null
                }
                imgObj.optString("uri", "").startsWith("data:", true) -> {
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
                textureAvgCache[texIdx] = 0
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
            textureAvgCache[texIdx] = sampled
            return if (sampled == 0) null else sampled
        }

        val resolvedMatCache = HashMap<Int, Int>()
        fun resolveMatColor(matIdx: Int): Int {
            if (materials == null || matIdx !in 0 until materials.length()) return Color.rgb(185, 192, 204)
            resolvedMatCache[matIdx]?.let { return it }
            val mat = materials.optJSONObject(matIdx) ?: return Color.rgb(185, 192, 204)
            val matName = mat.optString("name", "").lowercase(Locale.ROOT)
            val alphaMode = mat.optString("alphaMode", "OPAQUE").uppercase(Locale.ROOT)
            val pbr = mat.optJSONObject("pbrMetallicRoughness")
            val factor = pbr?.optJSONArray("baseColorFactor")
                ?: mat.optJSONArray("emissiveFactor")
            var rSrgb = 0.78f; var gSrgb = 0.80f; var bSrgb = 0.84f; var alphaFactor = 1.0f
            if (factor != null && factor.length() >= 3) {
                rSrgb = linearToSrgbChannel(factor.optDouble(0, 0.8))
                gSrgb = linearToSrgbChannel(factor.optDouble(1, 0.8))
                bSrgb = linearToSrgbChannel(factor.optDouble(2, 0.8))
                if (factor.length() >= 4) alphaFactor = factor.optDouble(3, 1.0).toFloat().coerceIn(0f, 1f)
            }
            val baseTexIdx = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
            val texColor = sampleTexColor(baseTexIdx)
            if (texColor != null) {
                rSrgb = (rSrgb * (Color.red(texColor) / 255f)).coerceIn(0f, 1f)
                gSrgb = (gSrgb * (Color.green(texColor) / 255f)).coerceIn(0f, 1f)
                bSrgb = (bSrgb * (Color.blue(texColor) / 255f)).coerceIn(0f, 1f)
                if (alphaMode == "BLEND") alphaFactor = (alphaFactor * (Color.alpha(texColor) / 255f)).coerceIn(0f, 1f)
            }
            if (alphaMode == "BLEND" && (alphaFactor < 0.28f || matName.contains("coat") || matName.contains("refraction") || matName == "material_0")) {
                resolvedMatCache[matIdx] = Int.MIN_VALUE
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
            resolvedMatCache[matIdx] = packed
            return packed
        }

        fun transformVerts(verts: FloatArray, m: FloatArray): FloatArray {
            val out = FloatArray(verts.size)
            for (i in 0 until verts.size step 3) {
                val x = verts[i]; val y = verts[i + 1]; val z = verts[i + 2]
                out[i] = m[0] * x + m[4] * y + m[8] * z + m[12]
                out[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                out[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            }
            return out
        }

        fun emitMeshAtMatrix(meshIdx: Int, worldMat: FloatArray) {
            if (meshIdx !in 0 until meshes.length() || isGeometryTruncated) return
            val prims = meshes.optJSONObject(meshIdx)?.optJSONArray("primitives") ?: return
            for (p in 0 until prims.length()) {
                if (isGeometryTruncated) break
                val prim = prims.optJSONObject(p) ?: continue
                val matColor = resolveMatColor(prim.optInt("material", -1))
                if (matColor == Int.MIN_VALUE) continue
                val rawPos = readPos(prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1) ?: continue
                val pos = transformVerts(rawPos, worldMat)
                val vCount = pos.size / 3
                val mode = prim.optInt("mode", 4)
                val idxArr = if (prim.has("indices")) readIdx(prim.optInt("indices", -1)) else null
                if (mode == 5) {
                    val count = idxArr?.size ?: vCount
                    for (i in 0 until count - 2) {
                        if (isGeometryTruncated) break
                        val i0 = if (i % 2 == 0) (idxArr?.get(i) ?: i) else (idxArr?.get(i + 1) ?: (i + 1))
                        val i1 = if (i % 2 == 0) (idxArr?.get(i + 1) ?: (i + 1)) else (idxArr?.get(i) ?: i)
                        val i2 = idxArr?.get(i + 2) ?: (i + 2)
                        if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                            addTriangle(
                                pos[i0 * 3], pos[i0 * 3 + 1], pos[i0 * 3 + 2],
                                pos[i1 * 3], pos[i1 * 3 + 1], pos[i1 * 3 + 2],
                                pos[i2 * 3], pos[i2 * 3 + 1], pos[i2 * 3 + 2],
                                matColor
                            )
                        }
                    }
                } else if (idxArr != null) {
                    var i = 0
                    while (i + 2 < idxArr.size && !isGeometryTruncated) {
                        val i0 = idxArr[i]; val i1 = idxArr[i + 1]; val i2 = idxArr[i + 2]
                        if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                            addTriangle(
                                pos[i0 * 3], pos[i0 * 3 + 1], pos[i0 * 3 + 2],
                                pos[i1 * 3], pos[i1 * 3 + 1], pos[i1 * 3 + 2],
                                pos[i2 * 3], pos[i2 * 3 + 1], pos[i2 * 3 + 2],
                                matColor
                            )
                        }
                        i += 3
                    }
                } else {
                    var i = 0
                    while (i + 2 < vCount && !isGeometryTruncated) {
                        addTriangle(
                            pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2],
                            pos[(i + 1) * 3], pos[(i + 1) * 3 + 1], pos[(i + 1) * 3 + 2],
                            pos[(i + 2) * 3], pos[(i + 2) * 3 + 1], pos[(i + 2) * 3 + 2],
                            matColor
                        )
                        i += 3
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
            for (m in 0 until meshes.length()) {
                if (isGeometryTruncated) break
                emitMeshAtMatrix(m, identityMat)
            }
        }
    }

    private fun parseBlendBytes(rawInput: ByteArray) {
        var bytes = rawInput
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            val compLen = bytes.size.coerceAtLeast(1)
            bytes = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val bos = ByteArrayOutputStream(minOf(compLen * 3, 64 * 1024 * 1024))
                    val tmp = ByteArray(16384)
                    var total = 0
                    while (total < 64 * 1024 * 1024) {
                        val r = gis.read(tmp)
                        if (r <= 0) break
                        total += r
                        if (total > 16 * 1024 * 1024 && (total / compLen) > 150) {
                            throw SecurityException("Превышен безопасный коэффициент сжатия GZIP.")
                        }
                        bos.write(tmp, 0, r)
                    }
                    bos.toByteArray()
                }
            } catch (_: Throwable) { return }
        }
        if (bytes.size < 16 || String(bytes, 0, 7, StandardCharsets.US_ASCII) != "BLENDER") return
        val is17 = bytes[7].toInt().toChar().isDigit() && bytes[8].toInt().toChar().isDigit()
        val ptrSize = if (is17) 8 else if (bytes[7].toInt().toChar() == '_') 4 else 8
        val order = if ((if (is17) bytes[12] else bytes[8]).toInt().toChar() == 'V') ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        var pos = if (is17) 17 else 12
        val bb = ByteBuffer.wrap(bytes).order(order)
        val blocks = ArrayList<BlendBlock>()

        while (pos + 16 <= bytes.size && blocks.size < 200_000) {
            val code = String(bytes, pos, 4, StandardCharsets.US_ASCII).trimEnd('\u0000', ' ')
            val hdrSize = if (is17) 32 else 16 + ptrSize
            if (pos + hdrSize > bytes.size) break
            val len = if (is17) bb.getLong(pos + 16).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else bb.getInt(pos + 4)
            val sdna = if (is17) bb.getInt(pos + 4) else bb.getInt(pos + 8 + ptrSize)
            val nr = if (is17) bb.getLong(pos + 24).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else bb.getInt(pos + 12 + ptrSize)
            val dataStart = pos + hdrSize
            if (len < 0 || dataStart + len > bytes.size) break
            blocks.add(BlendBlock(code, dataStart, len, sdna, nr))
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
                            when (typeNames.getOrNull(typeIdx) ?: "") {
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

        fun extractFromDataBlocks(dataBlocks: List<BlendBlock>) {
            var vertCoords: FloatArray? = null
            var vertCount = 0
            val loops = ArrayList<Int>()
            val polyLoops = ArrayList<Pair<Int, Int>>()
            val polyOffsets = ArrayList<Int>()

            for (blk in dataBlocks) {
                val blkOffset = blk.dataOffset
                val blkLen = blk.len
                if (blkLen < 12) continue

                if (blk.sdnaIndex == mvertStructIdx && mvertStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(12)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    val coords = FloatArray(safeCount * 3)
                    for (v in 0 until safeCount) {
                        val p = blkOffset + v * stride
                        coords[v * 3] = bb.getFloat(p); coords[v * 3 + 1] = bb.getFloat(p + 4); coords[v * 3 + 2] = bb.getFloat(p + 8)
                    }
                    vertCoords = coords; vertCount = safeCount
                    continue
                }
                if (blk.sdnaIndex == mloopStructIdx && mloopStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(4)
                    val safeCount = minOf(blk.count, blkLen / stride, 1_000_000)
                    for (k in 0 until safeCount) loops.add(bb.getInt(blkOffset + k * stride))
                    continue
                }
                if (blk.sdnaIndex == mpolyStructIdx && mpolyStructIdx >= 0 && blk.count > 0) {
                    val stride = (blkLen / blk.count).coerceAtLeast(8)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    for (k in 0 until safeCount) {
                        val p = blkOffset + k * stride
                        val ls = bb.getInt(p); val tl = bb.getInt(p + 4)
                        if (ls >= 0 && tl in 3..1024) polyLoops.add(ls to tl)
                    }
                    continue
                }
                if (blk.sdnaIndex == mfaceStructIdx && mfaceStructIdx >= 0 && blk.count > 0 && vertCoords != null) {
                    val coords = vertCoords
                    val stride = (blkLen / blk.count).coerceAtLeast(16)
                    val safeCount = minOf(blk.count, blkLen / stride, 500_000)
                    for (k in 0 until safeCount) {
                        if (isGeometryTruncated) break
                        val p = blkOffset + k * stride
                        val v1 = bb.getInt(p); val v2 = bb.getInt(p + 4); val v3 = bb.getInt(p + 8); val v4 = bb.getInt(p + 12)
                        if (v1 in 0 until vertCount && v2 in 0 until vertCount && v3 in 0 until vertCount) {
                            addTriangle(coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2], coords[v2 * 3], coords[v2 * 3 + 1], coords[v2 * 3 + 2], coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2])
                            if (v4 > 0 && v4 in 0 until vertCount) {
                                addTriangle(coords[v1 * 3], coords[v1 * 3 + 1], coords[v1 * 3 + 2], coords[v3 * 3], coords[v3 * 3 + 1], coords[v3 * 3 + 2], coords[v4 * 3], coords[v4 * 3 + 1], coords[v4 * 3 + 2])
                            }
                        }
                    }
                    continue
                }
                if (vertCoords == null && blkLen % 12 == 0 && (blkLen / 12) in 3..500_000) {
                    val cnt = blkLen / 12
                    val fx = bb.getFloat(blkOffset); val fy = bb.getFloat(blkOffset + 4); val fz = bb.getFloat(blkOffset + 8)
                    if (fx.isFinite() && fy.isFinite() && fz.isFinite() && abs(fx) < 1e4f && abs(fy) < 1e4f && abs(fz) < 1e4f) {
                        val coords = FloatArray(cnt * 3)
                        for (v in 0 until cnt) {
                            val p = blkOffset + v * 12
                            coords[v * 3] = bb.getFloat(p); coords[v * 3 + 1] = bb.getFloat(p + 4); coords[v * 3 + 2] = bb.getFloat(p + 8)
                        }
                        vertCoords = coords; vertCount = cnt
                        continue
                    }
                }
                if (vertCoords != null && vertCount > 0 && blkLen % 4 == 0) {
                    val intCnt = blkLen / 4
                    if (intCnt in 3..1_000_000) {
                        if (bb.getInt(blkOffset) == 0 && polyOffsets.isEmpty() && intCnt >= 2 && bb.getInt(blkOffset + 4) in 3..64) {
                            for (k in 0 until intCnt) polyOffsets.add(bb.getInt(blkOffset + k * 4))
                        } else if (loops.isEmpty() && bb.getInt(blkOffset) in 0 until vertCount) {
                            for (k in 0 until intCnt) loops.add(bb.getInt(blkOffset + k * 4))
                        }
                    }
                }
            }

            val coords = vertCoords ?: return
            if (polyLoops.isEmpty() && polyOffsets.size >= 2) {
                for (i in 0 until polyOffsets.size - 1) {
                    val s = polyOffsets[i]; val e = polyOffsets[i + 1]
                    if (e - s in 3..1024) polyLoops.add(s to (e - s))
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
                        val i1 = loops[ls + k]; val i2 = loops[ls + k + 1]
                        if (i1 in 0 until vertCount && i2 in 0 until vertCount) {
                            val b1 = i1 * 3; val b2 = i2 * 3
                            addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                        }
                    }
                }
            }
        }

        var idx = 0
        while (idx < blocks.size && !isGeometryTruncated) {
            if (blocks[idx].code == "ME") {
                val sub = ArrayList<BlendBlock>()
                var j = idx + 1
                while (j < blocks.size && blocks[j].code == "DATA") {
                    sub.add(blocks[j]); j++
                }
                extractFromDataBlocks(sub)
                idx = j
            } else idx++
        }
        if (vertexCount == 0) {
            extractFromDataBlocks(blocks.filter { it.code == "DATA" })
        }
    }

    private fun parseUsdStream() {
        val head = readSliceAt(0L, 16)
        if (head.size < 4) return
        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            val compContainerLen = readableSource.size.coerceAtLeast(1L)
            openSafeStream { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    val chunk = ByteArray(16384)
                    var totalUnpacked = 0L
                    while (!isGeometryTruncated) {
                        val entry = try { zis.nextEntry } catch (_: Throwable) { null } ?: break
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory) {
                            val bos = ByteArrayOutputStream()
                            var entryRead = 0
                            while (entryRead < 48 * 1024 * 1024) {
                                val r = zis.read(chunk)
                                if (r <= 0) break
                                bos.write(chunk, 0, r)
                                entryRead += r
                                totalUnpacked += r
                                if (totalUnpacked > 48 * 1024 * 1024L && (totalUnpacked / compContainerLen) > 150L) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия USDZ.")
                                }
                            }
                            val entryBytes = bos.toByteArray()
                            when {
                                name.endsWith(".usda") || (name.endsWith(".usd") && entryBytes.size >= 5 && String(entryBytes, 0, 5, StandardCharsets.US_ASCII) == "#usda") ->
                                    parseUsdaText(String(entryBytes, StandardCharsets.UTF_8))
                                name.endsWith(".glb") || name.endsWith(".gltf") ->
                                    parseGltfOrGlbStreaming(entryBytes)
                                (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) && previewBitmap == null -> {
                                    if (entryBytes.size in 64..(12 * 1024 * 1024)) {
                                        previewBitmap = BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.size)
                                    }
                                }
                            }
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
            if (vertexCount > 0) {
                previewBitmap?.recycle()
                previewBitmap = null
            }
            return
        }
        val bytes = readHeaderOrFullBytes(64 * 1024 * 1024)
        if (bytes.size >= 8 && String(bytes, 0, 8, StandardCharsets.US_ASCII) == "PXR-USDC") {
            lastErrorMsg = "Binary USDC crate requires the full CAD/USD module."
        } else {
            parseUsdaText(String(bytes, StandardCharsets.UTF_8))
        }
    }

    private fun parseUsdaText(txt: String) {
        val pointsRegex = Regex("""point3f\[\]\s+points\s*=\s*\[([\s\S]*?)\]""")
        val countsRegex = Regex("""int\[\]\s+faceVertexCounts\s*=\s*\[([\s\S]*?)\]""")
        val indicesRegex = Regex("""int\[\]\s+faceVertexIndices\s*=\s*\[([\s\S]*?)\]""")
        val tupleRegex = Regex("""\(\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*\)""")

        val ptsList = pointsRegex.findAll(txt).toList()
        val cntList = countsRegex.findAll(txt).toList()
        val idxList = indicesRegex.findAll(txt).toList()
        val meshCount = minOf(ptsList.size, idxList.size)

        for (m in 0 until meshCount) {
            if (isGeometryTruncated) break
            val verts = ArrayList<FloatArray>()
            for (tm in tupleRegex.findAll(ptsList[m].groupValues[1])) {
                if (verts.size >= 500_000) break
                verts.add(floatArrayOf(tm.groupValues[1].toFloatOrNull() ?: 0f, tm.groupValues[2].toFloatOrNull() ?: 0f, tm.groupValues[3].toFloatOrNull() ?: 0f))
            }
            if (verts.isEmpty()) continue
            val indices = idxList[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
            val counts = if (m < cntList.size) cntList[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() } else emptyList()
            if (counts.isNotEmpty()) {
                var cur = 0
                for (c in counts) {
                    if (isGeometryTruncated) break
                    if (c in 3..1024 && cur + c <= indices.size) {
                        val i0 = indices[cur]
                        if (i0 in verts.indices) {
                            val v0 = verts[i0]
                            for (k in 1 until c - 1) {
                                val i1 = indices[cur + k]; val i2 = indices[cur + k + 1]
                                if (i1 in verts.indices && i2 in verts.indices) {
                                    addTriangle(v0[0], v0[1], v0[2], verts[i1][0], verts[i1][1], verts[i1][2], verts[i2][0], verts[i2][1], verts[i2][2])
                                }
                            }
                        }
                    }
                    cur += c.coerceAtLeast(1)
                }
            } else {
                var k = 0
                while (k + 2 < indices.size && !isGeometryTruncated) {
                    val i0 = indices[k]; val i1 = indices[k + 1]; val i2 = indices[k + 2]
                    if (i0 in verts.indices && i1 in verts.indices && i2 in verts.indices) {
                        addTriangle(verts[i0][0], verts[i0][1], verts[i0][2], verts[i1][0], verts[i1][1], verts[i1][2], verts[i2][0], verts[i2][1], verts[i2][2])
                    }
                    k += 3
                }
            }
        }
    }

    private fun parseFbxBytes(bytes: ByteArray) {
        if (bytes.size < 32) return
        val headAscii = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.US_ASCII)
        if (!headAscii.startsWith("Kaydara FBX Binary")) {
            val text = String(bytes, StandardCharsets.UTF_8)
            val vertsMatches = Regex("""Vertices:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val idxMatches = Regex("""PolygonVertexIndex:\s*(?:\*\d+\s*\{\s*a:\s*)?([\s\S]*?)\}""").findAll(text).toList()
            val mCount = minOf(vertsMatches.size, idxMatches.size)
            for (m in 0 until mCount) {
                if (isGeometryTruncated) break
                val coords = vertsMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                val indices = idxMatches[m].groupValues[1].split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }.toIntArray()
                emitFbxMesh(coords, indices)
            }
            return
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        val is64Bit = version >= 7500

        fun inflateArr(offset: Int, compLen: Int, expected: Int): ByteArray? {
            if (offset < 0 || compLen <= 0 || offset + compLen > bytes.size || expected <= 0 || expected > 48 * 1024 * 1024) return null
            for (nowrap in booleanArrayOf(false, true)) {
                val inf = Inflater(nowrap)
                try {
                    inf.setInput(bytes, offset, compLen)
                    val out = ByteArray(expected)
                    var p = 0
                    while (!inf.finished() && p < expected) {
                        val r = inf.inflate(out, p, expected - p)
                        if (r <= 0) break
                        p += r
                    }
                    if (p > 0) return out
                } catch (_: Throwable) {
                } finally {
                    try { inf.end() } catch (_: Throwable) {}
                }
            }
            return null
        }

        fun readFloatArr(propPos: Int, propEnd: Int): FloatArray? {
            if (propPos + 13 > propEnd) return null
            val tc = bytes[propPos].toInt().toChar()
            if (tc != 'd' && tc != 'f') return null
            val es = if (tc == 'd') 8 else 4
            val arrLen = bb.getInt(propPos + 1).coerceIn(0, 1_500_000)
            val enc = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dStart = propPos + 13
            if (arrLen < 3) return null
            val raw = if (enc == 0) {
                if (dStart + arrLen * es > bytes.size) return null
                bytes.copyOfRange(dStart, dStart + arrLen * es)
            } else inflateArr(dStart, compLen, arrLen * es) ?: return null
            val rbb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val cnt = minOf(arrLen, raw.size / es)
            return FloatArray(cnt) { i -> if (es == 8) rbb.getDouble(i * 8).toFloat() else rbb.getFloat(i * 4) }
        }

        fun readIntArr(propPos: Int, propEnd: Int): IntArray? {
            if (propPos + 13 > propEnd || bytes[propPos].toInt().toChar() != 'i') return null
            val arrLen = bb.getInt(propPos + 1).coerceIn(0, 3_000_000)
            val enc = bb.getInt(propPos + 5)
            val compLen = bb.getInt(propPos + 9)
            val dStart = propPos + 13
            if (arrLen < 3) return null
            val raw = if (enc == 0) {
                if (dStart + arrLen * 4 > bytes.size) return null
                bytes.copyOfRange(dStart, dStart + arrLen * 4)
            } else inflateArr(dStart, compLen, arrLen * 4) ?: return null
            val rbb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val cnt = minOf(arrLen, raw.size / 4)
            return IntArray(cnt) { i -> rbb.getInt(i * 4) }
        }

        var activeV: FloatArray? = null
        var activeI: IntArray? = null
        fun flushGeom() {
            val v = activeV; val idx = activeI
            if (v != null && idx != null) emitFbxMesh(v, idx)
            activeV = null; activeI = null
        }

        fun walkNodes(startOffset: Int, endLimit: Int, depth: Int) {
            if (depth > 24 || isGeometryTruncated) return
            var pos = startOffset
            val minHdr = if (is64Bit) 25 else 13
            while (pos + minHdr <= endLimit && !isGeometryTruncated) {
                val endOff = if (is64Bit) bb.getLong(pos).toInt() else bb.getInt(pos)
                val numProps = if (is64Bit) bb.getLong(pos + 8).toInt() else bb.getInt(pos + 4)
                val propListLen = if (is64Bit) bb.getLong(pos + 16).toInt() else bb.getInt(pos + 8)
                val nameLen = bytes[pos + (if (is64Bit) 24 else 12)].toInt() and 0xFF
                val nameStart = pos + minHdr
                if (endOff == 0) break
                if (endOff <= pos || endOff > bytes.size || nameStart + nameLen > endOff) break
                val name = if (nameLen > 0) String(bytes, nameStart, nameLen, StandardCharsets.US_ASCII) else ""
                val propsStart = nameStart + nameLen
                val childrenStart = propsStart + propListLen
                when (name) {
                    "Geometry" -> {
                        flushGeom()
                        if (childrenStart < endOff) walkNodes(childrenStart, endOff, depth + 1)
                        flushGeom()
                    }
                    "Vertices" -> if (numProps >= 1 && propsStart < endOff) activeV = readFloatArr(propsStart, endOff)
                    "PolygonVertexIndex" -> if (numProps >= 1 && propsStart < endOff) activeI = readIntArr(propsStart, endOff)
                    else -> if (childrenStart in propsStart..(endOff - minHdr)) walkNodes(childrenStart, endOff, depth + 1)
                }
                pos = endOff
            }
        }
        walkNodes(27, bytes.size, 0)
        flushGeom()
    }

    private fun emitFbxMesh(coords: FloatArray, indices: IntArray) {
        val vCount = coords.size / 3
        if (vCount < 3 || indices.size < 3) return
        val poly = IntArray(64)
        var pLen = 0
        for (raw in indices) {
            if (isGeometryTruncated) break
            val isEnd = raw < 0
            val idx = if (isEnd) raw.inv() else raw
            if (pLen < poly.size) poly[pLen++] = idx
            if (isEnd) {
                if (pLen >= 3) {
                    val i0 = poly[0]
                    if (i0 in 0 until vCount) {
                        val b0 = i0 * 3
                        for (k in 1 until pLen - 1) {
                            val i1 = poly[k]; val i2 = poly[k + 1]
                            if (i1 in 0 until vCount && i2 in 0 until vCount) {
                                val b1 = i1 * 3; val b2 = i2 * 3
                                addTriangle(coords[b0], coords[b0 + 1], coords[b0 + 2], coords[b1], coords[b1 + 1], coords[b1 + 2], coords[b2], coords[b2 + 1], coords[b2 + 2])
                            }
                        }
                    }
                }
                pLen = 0
            }
        }
    }

    private fun parse3mfStream() {
        val fileSize = readableSource.size.coerceAtLeast(1L)
        try {
            val models = LinkedHashMap<String, ByteArray>()
            var rels: ByteArray? = null
            openSafeStream { raw ->
                ZipInputStream(raw).use { zis ->
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
                                if (totalUnpacked > 48 * 1024 * 1024L && totalUnpacked / fileSize > 150L) {
                                    throw SecurityException("Превышен безопасный коэффициент сжатия 3MF.")
                                }
                            }
                            if (isRels) rels = bos.toByteArray() else models[entry.name] = bos.toByteArray()
                        }
                        try { zis.closeEntry() } catch (_: Throwable) {}
                    }
                }
            }
            if (models.isEmpty()) return
            modelType = "3MF 3D Model"
            SceneFormatsParser.parse3mf(models, SceneFormatsParser.find3mfRootModel(rels)) { x1, y1, z1, x2, y2, z2, x3, y3, z3, color ->
                addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
                !isGeometryTruncated
            }
        } catch (_: Throwable) {}
    }

    private fun parseColladaBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val result = SceneFormatsParser.parseCollada(bytes) { x1, y1, z1, x2, y2, z2, x3, y3, z3, color ->
                addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
                !isGeometryTruncated
            }
            colladaYUp = result.isYUp
        } catch (_: Throwable) {}
    }

    private fun parseStepStream(stream: java.io.InputStream) {
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
                    "CARTESIAN_POINT" -> coordTupleRegex.find(args)?.let { cm ->
                        cartesianPoints[id] = floatArrayOf(cm.groupValues[1].toFloatOrNull() ?: 0f, cm.groupValues[2].toFloatOrNull() ?: 0f, cm.groupValues[3].toFloatOrNull() ?: 0f)
                    }
                    "VERTEX_POINT" -> refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()?.let { vertexPointToCartesian[id] = it }
                    "EDGE_CURVE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                        if (refs.size >= 2 && edgePairs.size < 200_000) edgePairs.add(refs[0] to refs[1])
                    }
                    "POLY_LOOP", "B_SPLINE_CURVE_WITH_KNOTS", "BEZIER_CURVE", "POLYLINE" -> {
                        val refs = refRegex.findAll(args).mapNotNull { it.groupValues[1].toIntOrNull() }.toList().toIntArray()
                        if (refs.size >= 2 && polyLoops.size < 100_000) polyLoops.add(refs)
                    }
                }
            }
        }
        fun resolvePt(id: Int): FloatArray? = cartesianPoints[id] ?: vertexPointToCartesian[id]?.let { cartesianPoints[it] }
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
        if (vertexCount == 0 && cartesianPoints.size >= 2) {
            val sortedPts = cartesianPoints.entries.sortedBy { it.key }.map { it.value }
            for (i in 0 until sortedPts.size - 1) {
                if (isGeometryTruncated) break
                val p1 = sortedPts[i]; val p2 = sortedPts[i + 1]
                addLineSegment(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], cadColor)
            }
        }
    }

    private fun parseIgesStream(stream: java.io.InputStream) {
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
            when (nums[0].toInt()) {
                110 -> if (nums.size >= 7) addLineSegment(nums[1], nums[2], nums[3], nums[4], nums[5], nums[6], cadColor)
                106 -> if (nums.size >= 7) {
                    val ip = nums[1].toInt()
                    val n = nums[2].toInt().coerceIn(2, 10_000)
                    if (ip == 1 && nums.size >= 4 + n * 2) {
                        val z = nums[3]
                        for (i in 0 until n - 1) {
                            val b = 4 + i * 2
                            addLineSegment(nums[b], nums[b + 1], z, nums[b + 2], nums[b + 3], z, cadColor)
                        }
                    } else if (ip == 2 && nums.size >= 3 + n * 3) {
                        for (i in 0 until n - 1) {
                            val b = 3 + i * 3
                            addLineSegment(nums[b], nums[b + 1], nums[b + 2], nums[b + 3], nums[b + 4], nums[b + 5], cadColor)
                        }
                    }
                }
            }
        }
    }

    private fun parseDwgBytes(bytes: ByteArray) {
        if (bytes.size < 32) return
        try {
            val bridge = GplCoreEngineBridgeLoader.get()
            if (bridge != null) {
                val verCode = bridge.renderDwg(bytes, password, object : DwgGeometrySink {
                    override val isFull: Boolean get() = isGeometryTruncated
                    override fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) {
                        addLineSegment(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), dwgColor(color))
                    }
                    override fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int) {
                        addTriangle(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), x3.toFloat(), y3.toFloat(), z3.toFloat(), dwgColor(color))
                    }
                    override fun textWidth(value: String, height: Double): Double = me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneRenderer.measureLabelWidth(value, height)
                    override fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int) {
                        if (meshLabels.size >= 20_000 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
                        meshLabels.add(me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshLabel(value, x.toFloat(), y.toFloat(), z.toFloat(), height.toFloat(), rotationDeg.toFloat(), dwgColor(color)))
                        updateBounds(x.toFloat(), y.toFloat(), z.toFloat())
                    }
                })
                modelType = "DWG Compatible ($verCode)"
                if (vertexCount > 0 || meshLabels.isNotEmpty()) return
            }
        } catch (e: DwgFormatException) {
            lastErrorMsg = if (e.message?.startsWith("PASSWORD_REQUIRED") == true) e.message else "DWG: ${e.message}"
            if (lastErrorMsg?.startsWith("PASSWORD_REQUIRED") == true) return
        } catch (e: OutOfMemoryError) {
            lastErrorMsg = "DWG is too large to load."
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val previewAddr = bb.getInt(0x0D)
        var sentinelPos = -1
        if (previewAddr in 0x14..(bytes.size - 32) && (0 until 16).all { bytes[previewAddr + it] == DWG_IMAGE_SENTINEL[it] }) {
            sentinelPos = previewAddr
        }
        if (sentinelPos < 0) {
            for (i in 0x14..(bytes.size - 32)) {
                if (bytes[i] == DWG_IMAGE_SENTINEL[0] && (0 until 16).all { bytes[i + it] == DWG_IMAGE_SENTINEL[it] }) {
                    sentinelPos = i; break
                }
            }
        }
        if (sentinelPos >= 0 && sentinelPos + 21 <= bytes.size) {
            val dirPos = sentinelPos + 16
            val imgCount = (bytes[dirPos + 4].toInt() and 0xFF).coerceIn(0, 16)
            var recPos = dirPos + 5
            for (i in 0 until imgCount) {
                if (recPos + 9 > bytes.size) break
                val code = bytes[recPos].toInt() and 0xFF
                val off = bb.getInt(recPos + 1)
                val sz = bb.getInt(recPos + 5)
                recPos += 9
                if (off > 0 && sz > 16 && off.toLong() + sz.toLong() <= bytes.size.toLong()) {
                    if (code == 6 || code == 2) {
                        previewBitmap = EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes, off, sz)
                        if (previewBitmap != null) break
                    }
                }
            }
        }
        if (previewBitmap != null) lastErrorMsg = null
    }

    private fun parse3dsStreaming(fileSize: Long) {
        val limit = if (fileSize > 0L) fileSize else 128L * 1024L * 1024L
        if (limit < 16L) return
        var currentVerts: FloatArray? = null
        var currentVertCount = 0

        fun walk(start: Long, endLimit: Long, depth: Int) {
            if (depth > 16) return
            var pos = start
            while (pos + 6L <= endLimit && !isGeometryTruncated) {
                val hdr = readSliceAt(pos, 6)
                if (hdr.size < 6) break
                val hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val chunkId = hbb.getShort(0).toInt() and 0xFFFF
                val chunkLen = hbb.getInt(2).toLong() and 0xFFFFFFFFL
                if (chunkLen < 6L) break
                val nextPos = minOf(endLimit, pos + chunkLen)
                val dataPos = pos + 6L
                when (chunkId) {
                    0x4D4D, 0x3D3D, 0x4100 -> walk(dataPos, nextPos, depth + 1)
                    0x4000 -> {
                        val probe = readSliceAt(dataPos, minOf(256L, nextPos - dataPos).toInt())
                        var sLen = 0
                        while (sLen < probe.size && probe[sLen].toInt() != 0) sLen++
                        if (dataPos + sLen + 1L < nextPos) walk(dataPos + sLen + 1L, nextPos, depth + 1)
                    }
                    0x4110 -> {
                        val pLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (pLen >= 2) {
                            val cb = readSliceAt(dataPos, pLen)
                            val bb = ByteBuffer.wrap(cb).order(ByteOrder.LITTLE_ENDIAN)
                            val vc = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (cb.size - 2) / 12)
                            val coords = FloatArray(vc * 3)
                            for (i in 0 until vc) {
                                val b = 2 + i * 12
                                coords[i * 3] = bb.getFloat(b); coords[i * 3 + 1] = bb.getFloat(b + 4); coords[i * 3 + 2] = bb.getFloat(b + 8)
                            }
                            currentVerts = coords; currentVertCount = vc
                        }
                    }
                    0x4120 -> {
                        val verts = currentVerts; val vc = currentVertCount
                        val pLen = (nextPos - dataPos).coerceAtMost(24 * 1024 * 1024L).toInt()
                        if (verts != null && vc > 0 && pLen >= 2) {
                            val cb = readSliceAt(dataPos, pLen)
                            val bb = ByteBuffer.wrap(cb).order(ByteOrder.LITTLE_ENDIAN)
                            val fc = (bb.getShort(0).toInt() and 0xFFFF).coerceIn(0, (cb.size - 2) / 8)
                            for (i in 0 until fc) {
                                val b = 2 + i * 8
                                val i0 = bb.getShort(b).toInt() and 0xFFFF
                                val i1 = bb.getShort(b + 2).toInt() and 0xFFFF
                                val i2 = bb.getShort(b + 4).toInt() and 0xFFFF
                                if (i0 in 0 until vc && i1 in 0 until vc && i2 in 0 until vc) {
                                    val b0 = i0 * 3; val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(verts[b0], verts[b0 + 1], verts[b0 + 2], verts[b1], verts[b1 + 1], verts[b1 + 2], verts[b2], verts[b2 + 1], verts[b2 + 2])
                                }
                            }
                        }
                    }
                }
                if (nextPos <= pos) break
                pos = nextPos
            }
        }
        walk(0L, limit, 0)
    }

    private fun parsePlyStreaming() {
        val headBytes = readSliceAt(0L, 32768)
        if (headBytes.size < 16) return
        val headerAscii = String(headBytes, 0, headBytes.size, StandardCharsets.US_ASCII)
        val endHeaderMatch = Regex("end_header\\r?\\n").find(headerAscii)
        if (endHeaderMatch == null) {
            openSafeStream { parsePlyAscii(it) }
            return
        }
        val headerLines = headerAscii.substring(0, endHeaderMatch.range.first).lines()
        val bodyOffset = (endHeaderMatch.range.last + 1).toLong()
        var format = "ascii"
        var numVerts = 0
        var numFaces = 0
        var curElem = ""
        var vertexStride = 0
        var faceCountBytes = 1
        var faceIndexBytes = 4
        fun tSize(t: String): Int = when (t.lowercase(Locale.ROOT)) {
            "char", "int8", "uchar", "uint8" -> 1
            "short", "int16", "ushort", "uint16" -> 2
            "double", "float64" -> 8
            else -> 4
        }
        for (rawLine in headerLines) {
            val parts = rawLine.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            when (parts[0].lowercase(Locale.ROOT)) {
                "format" -> if (parts.size >= 2) format = parts[1].lowercase(Locale.ROOT)
                "element" -> if (parts.size >= 3) {
                    curElem = parts[1].lowercase(Locale.ROOT)
                    val cnt = parts[2].toIntOrNull()?.coerceIn(0, 5_000_000) ?: 0
                    if (curElem == "vertex") numVerts = cnt else if (curElem == "face") numFaces = cnt
                }
                "property" -> {
                    if (curElem == "vertex" && parts.size >= 3 && !parts[1].equals("list", true)) {
                        vertexStride += tSize(parts[1])
                    } else if (curElem == "face" && parts.size >= 5 && parts[1].equals("list", true)) {
                        faceCountBytes = tSize(parts[2]); faceIndexBytes = tSize(parts[3])
                    }
                }
            }
        }
        if (format == "ascii") {
            openSafeStream { parsePlyAscii(it) }
            return
        }
        val order = if (format.contains("big_endian")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val stride = vertexStride.coerceAtLeast(12)
        val avail = (readableSource.size - bodyOffset).coerceAtLeast(0L)
        val safeVerts = numVerts.coerceIn(0, (avail / stride).coerceAtMost(2_000_000L).toInt())
        if (safeVerts <= 0) return
        val vertBytes = readSliceAt(bodyOffset, safeVerts * stride)
        val vbb = ByteBuffer.wrap(vertBytes).order(order)
        val actualVerts = vertBytes.size / stride
        val vertCoords = FloatArray(actualVerts * 3)
        for (i in 0 until actualVerts) {
            val b = i * stride
            vertCoords[i * 3] = vbb.getFloat(b); vertCoords[i * 3 + 1] = vbb.getFloat(b + 4); vertCoords[i * 3 + 2] = vbb.getFloat(b + 8)
        }
        val facesStart = bodyOffset + safeVerts.toLong() * stride
        val faceBytes = readSliceAt(facesStart, minOf((readableSource.size - facesStart).coerceAtLeast(0L), 64L * 1024L * 1024L).toInt())
        val fbb = ByteBuffer.wrap(faceBytes).order(order)
        fun readIntAt(p: Int, w: Int): Int = when (w) {
            1 -> faceBytes[p].toInt() and 0xFF
            2 -> fbb.getShort(p).toInt() and 0xFFFF
            else -> fbb.getInt(p)
        }
        var pos = 0
        for (f in 0 until numFaces) {
            if (pos + faceCountBytes > faceBytes.size || isGeometryTruncated) break
            val cnt = readIntAt(pos, faceCountBytes)
            pos += faceCountBytes
            val listB = cnt * faceIndexBytes
            if (cnt < 3 || cnt > 256 || pos + listB > faceBytes.size) break
            val i0 = readIntAt(pos, faceIndexBytes)
            if (i0 in 0 until actualVerts) {
                val b0 = i0 * 3
                for (k in 1 until cnt - 1) {
                    val i1 = readIntAt(pos + k * faceIndexBytes, faceIndexBytes)
                    val i2 = readIntAt(pos + (k + 1) * faceIndexBytes, faceIndexBytes)
                    if (i1 in 0 until actualVerts && i2 in 0 until actualVerts) {
                        val b1 = i1 * 3; val b2 = i2 * 3
                        addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                    }
                }
            }
            pos += listB
        }
    }

    private fun parsePlyAscii(stream: java.io.InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertRead = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var numVerts = 0; var numFaces = 0; var inHeader = true
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val t = line!!.trim()
                if (t.isEmpty() || t.startsWith("comment", true)) continue
                if (inHeader) {
                    when {
                        t.startsWith("element vertex", true) -> numVerts = t.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        t.startsWith("element face", true) -> numFaces = t.substringAfterLast(' ').toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
                        t.equals("end_header", true) -> {
                            inHeader = false
                            if (numVerts in 1..3_000_000) vertCoords = FloatArray(numVerts * 3)
                        }
                    }
                } else if (vertRead < numVerts) {
                    val p = t.split(Regex("\\s+"))
                    if (p.size >= 3) {
                        val x = p[0].toFloatOrNull(); val y = p[1].toFloatOrNull(); val z = p[2].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            if ((vertRead + 1) * 3 > vertCoords.size) vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            val b = vertRead * 3
                            vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                            vertRead++
                        }
                    }
                } else if (numFaces > 0 && !isGeometryTruncated) {
                    val parts = t.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val cnt = parts[0].coerceAtMost(parts.size - 1)
                        val i0 = parts[1]
                        if (cnt >= 3 && i0 in 0 until vertRead) {
                            val b0 = i0 * 3
                            for (k in 2 until cnt) {
                                val i1 = parts[k]; val i2 = parts[k + 1]
                                if (i1 in 0 until vertRead && i2 in 0 until vertRead) {
                                    val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseOffStream(stream: java.io.InputStream) {
        var vertCoords = FloatArray(30_000)
        var vertRead = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var countsRead = false; var numVerts = 0; var line: String?
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
                } else if (vertRead < numVerts) {
                    val p = clean.split(Regex("\\s+"))
                    if (p.size >= 3) {
                        val x = p[0].toFloatOrNull(); val y = p[1].toFloatOrNull(); val z = p[2].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            if ((vertRead + 1) * 3 > vertCoords.size) vertCoords = vertCoords.copyOf(vertCoords.size * 2)
                            val b = vertRead * 3
                            vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                            vertRead++
                        }
                    }
                } else {
                    val parts = clean.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (parts.size >= 4) {
                        val cnt = parts[0].coerceAtMost(parts.size - 1)
                        val i0 = parts[1]
                        if (cnt >= 3 && i0 in 0 until vertRead) {
                            val b0 = i0 * 3
                            for (k in 2 until cnt) {
                                val i1 = parts[k]; val i2 = parts[k + 1]
                                if (i1 in 0 until vertRead && i2 in 0 until vertRead) {
                                    val b1 = i1 * 3; val b2 = i2 * 3
                                    addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseObjStream(stream: java.io.InputStream) {
        var vertCoords = FloatArray(60_000)
        var vertCount = 0
        val maxVerts = 3_000_000
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            var raw: String?
            val faceIdx = IntArray(64)
            while (reader.readLine().also { raw = it } != null && !isGeometryTruncated) {
                val line = raw!!.trim()
                if (line.isEmpty() || line[0] == '#') continue
                if (line.startsWith("v ")) {
                    if (vertCount < maxVerts) {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 4) {
                            val x = p[1].toFloatOrNull(); val y = p[2].toFloatOrNull(); val z = p[3].toFloatOrNull()
                            if (x != null && y != null && z != null) {
                                if ((vertCount + 1) * 3 > vertCoords.size) {
                                    vertCoords = vertCoords.copyOf(minOf(maxVerts * 3, vertCoords.size * 2))
                                }
                                val b = vertCount * 3
                                vertCoords[b] = x; vertCoords[b + 1] = y; vertCoords[b + 2] = z
                                vertCount++
                            }
                        }
                    }
                } else if (line.startsWith("f ") && vertCount >= 3) {
                    val parts = line.split(Regex("\\s+"))
                    var fLen = 0
                    for (k in 1 until parts.size) {
                        if (fLen >= faceIdx.size) break
                        val rawIdx = parts[k].substringBefore('/').toIntOrNull() ?: continue
                        val r = if (rawIdx < 0) vertCount + rawIdx else rawIdx - 1
                        if (r in 0 until vertCount) faceIdx[fLen++] = r
                    }
                    if (fLen >= 3) {
                        val b0 = faceIdx[0] * 3
                        for (i in 1 until fLen - 1) {
                            val b1 = faceIdx[i] * 3; val b2 = faceIdx[i + 1] * 3
                            addTriangle(vertCoords[b0], vertCoords[b0 + 1], vertCoords[b0 + 2], vertCoords[b1], vertCoords[b1 + 1], vertCoords[b1 + 2], vertCoords[b2], vertCoords[b2 + 1], vertCoords[b2 + 2])
                        }
                    }
                }
            }
        }
    }

    private fun parseDxfStream(stream: java.io.InputStream) {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
            val pairs = ArrayList<Pair<Int, String>>(8192)
            while (pairs.size < 1_500_000) {
                val cLine = reader.readLine() ?: break
                val vLine = reader.readLine() ?: break
                val c = cLine.trim().toIntOrNull() ?: continue
                pairs.add(c to vLine.trim())
            }
            var i = 0
            while (i < pairs.size && !isGeometryTruncated) {
                val (code, v) = pairs[i]
                if (code == 0) {
                    val ent = v.uppercase(Locale.ROOT)
                    i++
                    if (ent == "POLYLINE") {
                        var flags = 0; var aci = 7
                        while (i < pairs.size && pairs[i].first != 0) {
                            if (pairs[i].first == 62) aci = pairs[i].second.toIntOrNull() ?: 7
                            if (pairs[i].first == 70) flags = pairs[i].second.toIntOrNull() ?: 0
                            i++
                        }
                        val pts = ArrayList<Point3D>()
                        while (i < pairs.size) {
                            if (pairs[i].first == 0 && pairs[i].second.equals("VERTEX", true)) {
                                i++
                                var vx = 0f; var vy = 0f; var vz = 0f
                                while (i < pairs.size && pairs[i].first != 0) {
                                    when (pairs[i].first) {
                                        10 -> vx = pairs[i].second.toFloatOrNull() ?: 0f
                                        20 -> vy = pairs[i].second.toFloatOrNull() ?: 0f
                                        30 -> vz = pairs[i].second.toFloatOrNull() ?: 0f
                                    }
                                    i++
                                }
                                pts.add(Point3D(vx, vy, vz))
                            } else if (pairs[i].first == 0 && pairs[i].second.equals("SEQEND", true)) {
                                i++; while (i < pairs.size && pairs[i].first != 0) i++; break
                            } else break
                        }
                        val col = aciToColor(aci)
                        for (k in 0 until pts.size - 1) {
                            addLineSegment(pts[k].x, pts[k].y, pts[k].z, pts[k + 1].x, pts[k + 1].y, pts[k + 1].z, col)
                        }
                        if ((flags and 1) != 0 && pts.size >= 3) {
                            addLineSegment(pts.last().x, pts.last().y, pts.last().z, pts.first().x, pts.first().y, pts.first().z, col)
                        }
                        continue
                    }

                    var x1 = 0f; var y1 = 0f; var z1 = 0f
                    var x2 = 0f; var y2 = 0f; var z2 = 0f
                    var x3 = 0f; var y3 = 0f; var z3 = 0f
                    var x4 = 0f; var y4 = 0f; var z4 = 0f
                    var hasV4 = false
                    var radius = 0f; var startDeg = 0f; var endDeg = 360f; var flags = 0; var aci = 7
                    val lwX = ArrayList<Float>(); val lwY = ArrayList<Float>()

                    while (i < pairs.size && pairs[i].first != 0) {
                        val (c, s) = pairs[i]
                        val f = s.toFloatOrNull() ?: 0f
                        when (c) {
                            10 -> { x1 = f; if (ent == "LWPOLYLINE" || ent == "SPLINE") lwX.add(f) }
                            20 -> { y1 = f; if (ent == "LWPOLYLINE" || ent == "SPLINE") lwY.add(f) }
                            30 -> z1 = f
                            11 -> x2 = f; 21 -> y2 = f; 31 -> z2 = f
                            12 -> x3 = f; 22 -> y3 = f; 32 -> z3 = f
                            13 -> { x4 = f; hasV4 = true }; 23 -> { y4 = f; hasV4 = true }; 33 -> { z4 = f; hasV4 = true }
                            40 -> radius = f; 50 -> startDeg = f; 51 -> endDeg = f
                            62 -> aci = s.toIntOrNull() ?: 7
                            70 -> flags = s.toIntOrNull() ?: 0
                        }
                        i++
                    }
                    val col = aciToColor(aci)
                    when (ent) {
                        "LINE" -> addLineSegment(x1, y1, z1, x2, y2, z2, col)
                        "3DFACE", "SOLID", "TRACE" -> {
                            addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, col)
                            if (hasV4) addTriangle(x1, y1, z1, x3, y3, z3, x4, y4, z4, col)
                        }
                        "LWPOLYLINE", "SPLINE" -> {
                            val cnt = minOf(lwX.size, lwY.size)
                            for (k in 0 until cnt - 1) {
                                addLineSegment(lwX[k], lwY[k], z1, lwX[k + 1], lwY[k + 1], z1, col)
                            }
                            if ((flags and 1) != 0 && cnt >= 3) {
                                addLineSegment(lwX[cnt - 1], lwY[cnt - 1], z1, lwX[0], lwY[0], z1, col)
                            }
                        }
                        "CIRCLE", "ARC" -> if (radius > 0f) {
                            val isFull = ent == "CIRCLE"
                            var sweep = if (isFull) 360f else (endDeg - startDeg) % 360f
                            if (sweep <= 0f) sweep += 360f
                            val segs = 28
                            val step = sweep / segs
                            for (sIdx in 0 until segs) {
                                val a1 = Math.toRadians(((if (isFull) 0f else startDeg) + sIdx * step).toDouble()).toFloat()
                                val a2 = Math.toRadians(((if (isFull) 0f else startDeg) + (sIdx + 1) * step).toDouble()).toFloat()
                                addLineSegment(x1 + radius * cos(a1), y1 + radius * sin(a1), z1, x1 + radius * cos(a2), y1 + radius * sin(a2), z1, col)
                            }
                        }
                    }
                    continue
                }
                i++
            }
        }
    }

    private fun parseBinaryStlHeaderColor(hdr: ByteArray): Int {
        if (hdr.size < 80) return 0
        val ascii = String(hdr, 0, 80, StandardCharsets.ISO_8859_1)
        val idx = ascii.indexOf("COLOR=", ignoreCase = true)
        if (idx >= 0 && idx + 10 <= 80) {
            val r = hdr[idx + 6].toInt() and 0xFF
            val g = hdr[idx + 7].toInt() and 0xFF
            val b = hdr[idx + 8].toInt() and 0xFF
            val a = (hdr[idx + 9].toInt() and 0xFF).let { if (it == 0) 255 else it }
            if (r + g + b > 0) return Color.argb(a, r, g, b)
        }
        return 0
    }

    private fun parseBinaryStlPread(exactFileSize: Long) {
        val hdr = readSliceAt(0L, 84)
        if (hdr.size < 84) return
        val headerDefaultCol = parseBinaryStlHeaderColor(hdr)
        val headerCount = ByteBuffer.wrap(hdr, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val computedBySize = if (exactFileSize >= 134L) (exactFileSize - 84L) / 50L else 0L
        val totalTris = when {
            computedBySize > 0L && (headerCount <= 0L || headerCount > computedBySize + 1000L) -> computedBySize
            headerCount in 1L..50_000_000L -> if (computedBySize > 0L) minOf(headerCount, computedBySize) else headerCount
            else -> computedBySize
        }
        if (totalTris <= 0L) return

        val needsDecimation = totalTris > maxPolygonsLimit
        if (needsDecimation) isGeometryTruncated = true
        var decimationAcc = 0L

        val batchTris = 2048
        val directBuf = ByteBuffer.allocateDirect(batchTris * 50).order(ByteOrder.LITTLE_ENDIAN)
        var triIdx = 0L

        while (triIdx < totalTris && vertexCount + 9 <= maxVertexFloats) {
            val countToRead = minOf(batchTris.toLong(), totalTris - triIdx).toInt()
            directBuf.clear()
            directBuf.limit(countToRead * 50)
            val got = readableSource.readAt(84L + triIdx * 50L, directBuf)
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
                val rowBase = i * 50
                val base = rowBase + 12
                val attr = directBuf.getShort(rowBase + 48).toInt() and 0xFFFF
                val col = if ((attr and 0x8000) != 0) {
                    val r = ((attr and 0x1F) * 255) / 31
                    val g = (((attr ushr 5) and 0x1F) * 255) / 31
                    val b = (((attr ushr 10) and 0x1F) * 255) / 31
                    Color.rgb(r, g, b)
                } else headerDefaultCol
                addTriangle(
                    directBuf.getFloat(base), directBuf.getFloat(base + 4), directBuf.getFloat(base + 8),
                    directBuf.getFloat(base + 12), directBuf.getFloat(base + 16), directBuf.getFloat(base + 20),
                    directBuf.getFloat(base + 24), directBuf.getFloat(base + 28), directBuf.getFloat(base + 32),
                    col
                )
            }
            triIdx += actualTris
        }
    }

    private fun parseAsciiStlStream(stream: java.io.InputStream) {
        val verts = FloatArray(9)
        var vIdx = 0
        var linesScanned = 0
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            var raw: String?
            while (reader.readLine().also { raw = it } != null && !isGeometryTruncated) {
                linesScanned++
                if (linesScanned > 500 && vertexCount == 0 && vIdx == 0) break
                val p = raw!!.trim()
                if (p.startsWith("vertex", true)) {
                    val parts = p.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        verts[vIdx * 3] = parts[1].toFloatOrNull() ?: 0f
                        verts[vIdx * 3 + 1] = parts[2].toFloatOrNull() ?: 0f
                        verts[vIdx * 3 + 2] = parts[3].toFloatOrNull() ?: 0f
                        vIdx++
                        if (vIdx == 3) {
                            addTriangle(verts[0], verts[1], verts[2], verts[3], verts[4], verts[5], verts[6], verts[7], verts[8])
                            vIdx = 0
                        }
                    }
                } else if (p.startsWith("endloop", true) || p.startsWith("endfacet", true)) {
                    vIdx = 0
                }
            }
        }
    }

    private var colladaYUp: Boolean? = null

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
            labels = meshLabels.toList(),
            preview = previewBitmap?.takeIf { !it.isRecycled }
        )
    }

    override fun getPageText(pageIndex: Int): String = modelType

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        sceneRenderer.render(exportMeshScene(), pageIndex, tileX, tileY, zoomPermille, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        sceneRenderer.release()
        readableSource.close()
        previewBitmap?.recycle()
        previewBitmap = null
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Clean-Room parser for Microsoft OneNote (`.one`) revision store files based on the open
 * `[MS-ONE]` and `[MS-ONESTORE]` specifications published under the Microsoft Open Specifications Promise (OSP).
 */
