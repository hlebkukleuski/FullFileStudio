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
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun Stl3dMeshSession.readExactBytes(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
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
private fun Stl3dMeshSession.parseBinaryStlHeaderDefaultColor(hdr: ByteArray): Int {
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

internal fun Stl3dMeshSession.parseBinaryStlPread(exactFileSize: Long) {
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

internal fun Stl3dMeshSession.parseAsciiStl(stream: InputStream) {
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

private fun Stl3dMeshSession.objMapArgs(parts: List<String>): ObjMap {
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
                "-s" -> { uS = nums.getOrElse(0) { 1f }; vS = nums.getOrElse(1) { uS } }
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

private fun Stl3dMeshSession.parseMtl(bytes: ByteArray, into: HashMap<String, ObjMaterial>) {
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

internal fun Stl3dMeshSession.parseObj(stream: InputStream) {
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
