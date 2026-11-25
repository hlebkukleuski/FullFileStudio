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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

internal fun Stl3dMeshSession.parse3dsScene(fileSize: Long) {
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
        val m = GltfParser.trs(t, q, s)
        return GltfParser.mul(m, GltfParser.trs(floatArrayOf(-n.pivot[0], -n.pivot[1], -n.pivot[2]), null, null))
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
        val w = if (parent != null) GltfParser.mul(world(parent, depth + 1), local) else local
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
        return if (parent != null) GltfParser.mul(worldAt(parent, frame, depth + 1), local) else local
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
                val m = GltfParser.mul(worldAt(n, (segStart + k).toFloat(), 0), inv)
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
        entries.add(GltfParser.IDENTITY to primary)
        if (primary != null && list.any { it.instance != null }) {
            val inv = invertAffine(world(primary, 0))
            if (inv != null) for (n in list) if (n !== primary) entries.add(GltfParser.mul(world(n, 0), inv) to n)
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
                        uv[slot * 2] = uvs[vi * 2] * m!!.uScale + m.uOff
                        uv[slot * 2 + 1] = 1f - (uvs[vi * 2 + 1] * m.vScale + m.vOff)
                    }
                }
                val hasN = cornerN != null && sg!!.getOrElse(f) { 0 } != 0
                if (hasN) {
                    for (k in 0 until 3) {
                        val nx = cornerN[f * 9 + k * 3]; val ny = cornerN[f * 9 + k * 3 + 1]; val nz = cornerN[f * 9 + k * 3 + 2]
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
