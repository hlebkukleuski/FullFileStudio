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

import android.graphics.Color
import android.system.Os
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Clean-Room parser for Stanford Polygon `.ply` (supports both ASCII and binary_little_endian / binary_big_endian,
 * arbitrary property types, and variable face list count/index integer types).
 */
internal fun Stl3dMeshSession.parsePlyMesh() {
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

private fun Stl3dMeshSession.parsePlyAsciiStream(stream: InputStream) {
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
internal fun Stl3dMeshSession.parseOff(stream: InputStream) {
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
