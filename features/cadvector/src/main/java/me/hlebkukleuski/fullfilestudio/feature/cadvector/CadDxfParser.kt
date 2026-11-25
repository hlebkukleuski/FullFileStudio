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
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    class Polyline(val points: List<Stl3dMeshSession.DxfPoint3D>, val isClosed: Boolean, color: Int) : DxfEntityRecord(color)
    class Spline(val degree: Int, val controlPoints: List<Stl3dMeshSession.DxfPoint3D>, val fitPoints: List<Stl3dMeshSession.DxfPoint3D>, val isClosed: Boolean, color: Int) : DxfEntityRecord(color)
    class Hatch(val loops: List<List<Stl3dMeshSession.DxfPoint3D>>, color: Int) : DxfEntityRecord(color)
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

private fun Stl3dMeshSession.readBinaryDxfPairs(): List<Pair<Int, String>> {
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

internal fun Stl3dMeshSession.parseDxf(stream: InputStream) {
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
                    val resolved = trueColor ?: Stl3dMeshSession.aciToColor(aci)
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
        if (isGeometryTruncated || dxfEmittedOpsCount >= Stl3dMeshSession.MAX_DXF_EMIT_OPS) break
        emitDxfEntity(entity, blocksMap, identity, 0, visitedBlocks)
    }
}

private fun Stl3dMeshSession.resolveEntityColor(layerName: String, aciCode: Int?, trueColorRgb: Int?, layerColors: Map<String, Int>): Int {
    if (trueColorRgb != null) return (0xFF shl 24) or (trueColorRgb and 0xFFFFFF)
    if (aciCode != null && aciCode in 1..255) {
        val c = Stl3dMeshSession.aciToColor(aciCode)
        if (c != 0) return c
    }
    return layerColors[layerName.uppercase(Locale.ROOT)] ?: 0
}

private fun Stl3dMeshSession.parseSingleDxfEntity(
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
        val pts = ArrayList<Stl3dMeshSession.DxfPoint3D>()
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
                    pts.add(Stl3dMeshSession.DxfPoint3D(vx, vy, vz))
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

    val splineCtrlPts = ArrayList<Stl3dMeshSession.DxfPoint3D>()
    var curCtrlX: Float? = null; var curCtrlY: Float? = null; var curCtrlZ = 0f
    val splineFitPts = ArrayList<Stl3dMeshSession.DxfPoint3D>()
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
                        splineCtrlPts.add(Stl3dMeshSession.DxfPoint3D(curCtrlX, curCtrlY, curCtrlZ))
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
                        splineFitPts.add(Stl3dMeshSession.DxfPoint3D(curFitX, curFitY, curFitZ))
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
        if (curCtrlX != null && curCtrlY != null && splineCtrlPts.size < 10_000) splineCtrlPts.add(Stl3dMeshSession.DxfPoint3D(curCtrlX, curCtrlY, curCtrlZ))
        if (curFitX != null && curFitY != null && splineFitPts.size < 10_000) splineFitPts.add(Stl3dMeshSession.DxfPoint3D(curFitX, curFitY, curFitZ))
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
                val pts = ArrayList<Stl3dMeshSession.DxfPoint3D>(count)
                for (i in 0 until count) {
                    pts.add(Stl3dMeshSession.DxfPoint3D(lwPointsX[i], lwPointsY[i], lwElevationZ))
                }
                outList.add(DxfEntityRecord.Polyline(pts, isClosed = (flags and 1) != 0, entityColor))
            }
        }
        "HATCH" -> {
            val count = minOf(lwPointsX.size, lwPointsY.size)
            if (count >= 2) {
                val pts = ArrayList<Stl3dMeshSession.DxfPoint3D>(count)
                for (i in 0 until count) {
                    pts.add(Stl3dMeshSession.DxfPoint3D(lwPointsX[i], lwPointsY[i], z1))
                }
                outList.add(DxfEntityRecord.Hatch(listOf(pts), entityColor))
            }
        }
        "DIMENSION" -> {
            val resolvedDimText = when {
                textVal.isNotBlank() && textVal != "<>" -> textVal
                dimMeasurement != null && dimMeasurement > 0f -> String.format(Locale.US, "%.2f", dimMeasurement)
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

    fun apply(x: Float, y: Float, z: Float): Stl3dMeshSession.DxfPoint3D {
        val scaledX = x * sx
        val scaledY = y * sy
        val scaledZ = z * sz
        val rx = scaledX * cosR - scaledY * sinR
        val ry = scaledX * sinR + scaledY * cosR
        return Stl3dMeshSession.DxfPoint3D(rx + tx, ry + ty, scaledZ + tz)
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

private fun Stl3dMeshSession.emitDxfEntity(
    entity: DxfEntityRecord,
    blocks: Map<String, List<DxfEntityRecord>>,
    transform: DxfTransform,
    depth: Int,
    visitedBlocks: MutableSet<String>
) {
    if (depth > Stl3dMeshSession.MAX_INSERT_DEPTH || isGeometryTruncated) return
    if (++dxfEmittedOpsCount > Stl3dMeshSession.MAX_DXF_EMIT_OPS) {
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
                            if (isGeometryTruncated || dxfEmittedOpsCount >= Stl3dMeshSession.MAX_DXF_EMIT_OPS) break
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
                if (entity.labelText.isNotEmpty() && textLabels.size < Stl3dMeshSession.MAX_TEXT_LABELS_LIMIT) {
                    val mid = transform.apply(entity.textMidX, entity.textMidY, entity.textMidZ)
                    updateBounds(mid.x, mid.y, mid.z)
                    textLabels.add(Stl3dMeshSession.CadTextLabel3D(entity.labelText, mid.x, mid.y, mid.z, 8f, 0f, entity.color))
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
                    if (isGeometryTruncated || dxfEmittedOpsCount >= Stl3dMeshSession.MAX_DXF_EMIT_OPS) break
                    emitDxfEntity(child, blocks, childTransform, depth + 1, visitedBlocks)
                }
            } finally {
                visitedBlocks.remove(normKey)
            }
        }
        is DxfEntityRecord.Text -> {
            if (textLabels.size < Stl3dMeshSession.MAX_TEXT_LABELS_LIMIT) {
                val pos = transform.apply(entity.x, entity.y, entity.z)
                updateBounds(pos.x, pos.y, pos.z)
                textLabels.add(
                    Stl3dMeshSession.CadTextLabel3D(
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

private fun Stl3dMeshSession.emitEllipseSegments(ellipse: DxfEntityRecord.Ellipse, transform: DxfTransform) {
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

private fun Stl3dMeshSession.emitArcSegments(
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

internal fun Stl3dMeshSession.evaluateBsplineCurve(controlPoints: List<Stl3dMeshSession.DxfPoint3D>, degree: Int, isClosed: Boolean): List<Stl3dMeshSession.DxfPoint3D> {
    if (controlPoints.size <= 2) return controlPoints
    val pts = if (isClosed) controlPoints + controlPoints.take(degree.coerceAtMost(controlPoints.size)) else controlPoints
    val n = pts.size - 1
    val p = degree.coerceIn(1, n)
    val stepsPerSpan = 8
    val totalSteps = ((n - p + 1).coerceAtLeast(1) * stepsPerSpan).coerceAtMost(2048)
    val result = ArrayList<Stl3dMeshSession.DxfPoint3D>(totalSteps + 1)

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
                d[j] = Stl3dMeshSession.DxfPoint3D(
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
