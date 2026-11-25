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
import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal fun Stl3dMeshSession.parseGltfOrGlbScene(customBytes: ByteArray? = null) {
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
    GltfParser(root, binSource, binLength, sceneOutput, maxPolygonsLimit * 3).load()
    if (vertexCount == 0 && usesDraco) {
        lastErrorMsg = "Модель glTF сжата Draco, но её геометрию не удалось распаковать."
    }
}


/**
 * Clean-Room Blender 3D (`.blend`) binary parser supporting Blender 2.7x, 2.8x, 2.9x, 3.x, 4.x, and 5.x.
 * Handles uncompressed `"BLENDER"` files and GZIP-compressed `.blend` files, parses `DNA1`
 * structure definitions (`Mesh`, `MVert`, `MPoly`, `MLoop`, `MFace`, and Blender 4.0+ SOA float3/int32
 * attribute buffers), and triangulates all mesh geometry.
 */
internal fun Stl3dMeshSession.parseBlendScene() {
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

internal fun Stl3dMeshSession.parseUsdOrUsdzScene() {
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
        UsdReader(stage, sceneOutput).load()
    } catch (e: SecurityException) {
        lastErrorMsg = e.message
    } catch (_: Throwable) {}
}

/**
 * Full Clean-Room parser for Autodesk FBX (`.fbx` Binary v7000–v7700+ and ASCII) 3D models.
 * Parses the binary FBX node hierarchy (`Objects` -> `Geometry` -> `Vertices` & `PolygonVertexIndex`),
 * decompresses zlib-deflated property arrays (`encoding == 1`), and triangulates all polygon loops.
 */
internal fun Stl3dMeshSession.parseFbxScene() {
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
internal fun Stl3dMeshSession.parse3mfModel() {
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
        me.hlebkukleuski.fullfilestudio.core.document.SceneFormatsParser.parse3mf(
            models,
            me.hlebkukleuski.fullfilestudio.core.document.SceneFormatsParser.find3mfRootModel(rels)
        ) { x1, y1, z1, x2, y2, z2, x3, y3, z3, color ->
            addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
            !isGeometryTruncated
        }
    } catch (_: Throwable) {}
}

/**
 * Clean-Room parser for Khronos COLLADA (`.dae` XML) 3D models.
 */
internal fun Stl3dMeshSession.parseColladaDaeScene() {
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
            val rootEl = ColladaParser.parse(daeBytes) ?: return
            ColladaParser(rootEl, output).load()
        } else {
            val rootEl = ColladaParser.parse(daeBytes) ?: return
            ColladaParser(rootEl, sceneOutput).load()
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
internal fun Stl3dMeshSession.parseStepModel(stream: InputStream) {
    val text = stream.readBytes().toString(StandardCharsets.ISO_8859_1)
    val entities = try { StepParser.parse(text) } catch (_: Throwable) { return }
    try {
        StepParser(entities, sceneOutput).load()
    } catch (_: Throwable) {}
}

internal fun Stl3dMeshSession.parseIgesModel(stream: InputStream) {
    val text = stream.readBytes().toString(StandardCharsets.ISO_8859_1)
    val entities = try { IgesParser.parse(text) } catch (_: Throwable) { return }
    try {
        IgesParser(entities, sceneOutput).load()
    } catch (_: Throwable) {}
}

/**
 * Clean-Room parser for DWG Compatible `.dwg` files (`AC1012` R13 through `AC1032` R2018+) without RealDWG.
 */
internal fun Stl3dMeshSession.parseDwgDrawing() {
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
        lastErrorMsg = "File does not have a valid DWG Compatible signature (AC10xx)."
        return
    }

    val releaseName = when (verCode) {
        "AC1012" -> "DWG Compatible R13"
        "AC1014" -> "DWG Compatible R14"
        "AC1015" -> "DWG Compatible 2000"
        "AC1018" -> "DWG Compatible 2004"
        "AC1021" -> "DWG Compatible 2007"
        "AC1024" -> "DWG Compatible 2010"
        "AC1027" -> "DWG Compatible 2013"
        "AC1032" -> "DWG Compatible 2018+"
        else -> "DWG Compatible ($verCode)"
    }
    modelType = "DWG ($releaseName)"

    val geometryLoaded = try {
        val bridge = me.hlebkukleuski.fullfilestudio.core.engine.bridge.GplCoreEngineBridgeLoader.get()
        if (bridge != null) {
            bridge.renderDwg(bytes, password, object : me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgGeometrySink {
                override val isFull: Boolean get() = isGeometryTruncated
                override fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) {
                    addLineSegment(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), dwgColor(color))
                }
                override fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int) {
                    addTriangle(x1.toFloat(), y1.toFloat(), z1.toFloat(), x2.toFloat(), y2.toFloat(), z2.toFloat(), x3.toFloat(), y3.toFloat(), z3.toFloat(), dwgColor(color))
                }
                override fun textWidth(value: String, height: Double): Double = me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshSceneRenderer.measureLabelWidth(value, height)
                override fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int) {
                    if (textLabels.size >= 20_000 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
                    textLabels.add(Stl3dMeshSession.CadTextLabel3D(value, x.toFloat(), y.toFloat(), z.toFloat(), height.toFloat(), rotationDeg.toFloat(), dwgColor(color)))
                    updateBounds(x.toFloat(), y.toFloat(), z.toFloat())
                }
            })
            vertexCount > 0 || textLabels.isNotEmpty()
        } else {
            false
        }
    } catch (e: me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgFormatException) {
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

private fun Stl3dMeshSession.dwgColor(c: Int): Int = if (c < 0) (0xFF shl 24) or (c and 0xFFFFFF) else Stl3dMeshSession.aciToColor(c)

private fun Stl3dMeshSession.matchesDwgSentinelAt(bytes: ByteArray, offset: Int): Boolean {
    if (offset < 0 || offset + 16 > bytes.size) return false
    for (i in 0 until 16) {
        if (bytes[offset + i] != Stl3dMeshSession.DWG_IMAGE_SENTINEL[i]) return false
    }
    return true
}

private fun Stl3dMeshSession.findDwgImageSentinel(bytes: ByteArray): Int {
    val limit = bytes.size - 32
    val first = Stl3dMeshSession.DWG_IMAGE_SENTINEL[0]
    val second = Stl3dMeshSession.DWG_IMAGE_SENTINEL[1]
    var i = 0x14
    while (i <= limit) {
        if (bytes[i] == first && bytes[i + 1] == second && matchesDwgSentinelAt(bytes, i)) {
            return i
        }
        i++
    }
    return -1
}

private fun Stl3dMeshSession.decodeDwgPreviewAtSentinel(bytes: ByteArray, bb: ByteBuffer, sentinelPos: Int): Bitmap? {
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

private fun Stl3dMeshSession.scanForEmbeddedPngOrBmpInDwg(bytes: ByteArray): Bitmap? {
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
