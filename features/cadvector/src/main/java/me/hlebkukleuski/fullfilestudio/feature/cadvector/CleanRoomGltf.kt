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

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class TriAnim {
    var bone = -1
    var joints: IntArray? = null
    var weights: FloatArray? = null
    var bindPos: FloatArray? = null
    var bindNrm: FloatArray? = null
    var morphTargets: IntArray? = null
    var morphDeltas: FloatArray? = null
    var morphNormalDeltas: FloatArray? = null
    var dqPre = -1

    fun reset() {
        dqPre = -1
        bone = -1
        joints = null
        weights = null
        bindPos = null
        bindNrm = null
        morphTargets = null
        morphDeltas = null
        morphNormalDeltas = null
    }
}

class AnimClip(
    val fps: Float,
    val frames: Int,
    val bones: Int,
    val matrices: FloatArray,
    val morphCount: Int = 0,
    val morphWeights: FloatArray? = null,
    val morphBase: FloatArray? = null,
    val clipNames: List<String> = emptyList(),
    val clipStarts: IntArray? = null,
    val clipFrames: IntArray? = null
)

interface GltfOutput {
    val truncated: Boolean
    fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int)
    fun triangleN(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int, normals: FloatArray?) =
        triangle(p, color, uv, texture, c0, c1, c2)
    fun triangleFull(
        p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int,
        normals: FloatArray?, normalTexture: Int, anim: TriAnim?
    ) = triangleN(p, color, uv, texture, c0, c1, c2, normals)
    fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int)
    fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int
    fun normalTexture(key: String, bytes: ByteArray?, flipGreen: Boolean, strength: Float): Int = -1
    fun animation(clip: AnimClip) {}
    fun resource(uri: String): ByteArray?
    fun pixels(bytes: ByteArray): DecodedPixels? = CleanRoomTextureDecoders.decode(bytes)
}

fun interface GltfBinSource {
    fun read(offset: Long, length: Int): ByteArray?
}

class CleanRoomGltf(
    private val root: JSONObject,
    private val glbBin: GltfBinSource?,
    private val glbBinLength: Long,
    private val out: GltfOutput,
    private val maxElements: Int
) {
    private val buffers = root.optJSONArray("buffers") ?: JSONArray()
    private val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
    private val accessors = root.optJSONArray("accessors") ?: JSONArray()
    private val meshes = root.optJSONArray("meshes") ?: JSONArray()
    private val materials = root.optJSONArray("materials") ?: JSONArray()
    private val textures = root.optJSONArray("textures") ?: JSONArray()
    private val images = root.optJSONArray("images") ?: JSONArray()
    private val nodes = root.optJSONArray("nodes") ?: JSONArray()
    private val skins = root.optJSONArray("skins") ?: JSONArray()

    private val bufferCache = HashMap<Int, ByteArray?>()
    private val viewCache = HashMap<Int, ByteArray?>()
    private val materialCache = HashMap<Int, Material>()
    private val worldMatrices = HashMap<Int, FloatArray>()

    class Accessor(val data: FloatArray, val count: Int, val components: Int)

    private class Material(
        val color: Int,
        val texture: Int,
        val texCoord: Int,
        val transform: FloatArray?,
        val alphaMode: String,
        val alphaCutoff: Float,
        val normalTexture: Int = -1,
        val normalTexCoord: Int = 0
    )

    private class Channel(val node: Int, val path: String, val times: FloatArray, val values: FloatArray, val comps: Int, val interp: String)

    private class ClipDef(val name: String, val channels: List<Channel>, val end: Float)

    private var clips: List<ClipDef> = emptyList()
    private var clipStarts = IntArray(0)
    private var clipLens = IntArray(0)
    private var frameClip = IntArray(0)
    private var frameTimes = FloatArray(0)
    private var fps = 30f
    private var dynamicNodes = BooleanArray(0)
    private val parents = IntArray(nodes.length()) { -1 }
    private val frameWorlds = HashMap<Int, Array<FloatArray?>>()
    private val boneFrames = ArrayList<FloatArray>()
    private val rigidBones = HashMap<Int, Int>()
    private val skinBones = HashMap<Long, Int>()
    private val morphFrameWeights = ArrayList<FloatArray>()
    private val morphBaseList = ArrayList<Float>()
    private val animatedWeightNodes = HashSet<Int>()
    private val anim = TriAnim()
    private val animActive get() = frameTimes.size >= 2

    fun load(): Boolean {
        for (i in 0 until buffers.length()) {
            val uri = buffers.optJSONObject(i)?.optString("uri", "") ?: ""
            if (uri.isNotEmpty() && !uri.startsWith("data:", ignoreCase = true)) bufferBytes(i)
        }
        val usedImages = HashSet<Int>()
        for (m in 0 until materials.length()) {
            val mat = materials.optJSONObject(m) ?: continue
            val info = mat.optJSONObject("pbrMetallicRoughness")?.optJSONObject("baseColorTexture")
                ?: mat.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")?.optJSONObject("diffuseTexture")
                ?: continue
            val src = textureSource(info.optInt("index", -1))
            if (src >= 0) usedImages.add(src)
        }
        for (m in 0 until materials.length()) {
            val info = materials.optJSONObject(m)?.optJSONObject("normalTexture") ?: continue
            val src = textureSource(info.optInt("index", -1))
            if (src >= 0) usedImages.add(src)
        }
        for (i in usedImages) {
            val uri = images.optJSONObject(i)?.optString("uri", "") ?: ""
            if (uri.isNotEmpty() && !uri.startsWith("data:", ignoreCase = true)) out.resource(uri)
        }
        val sceneIdx = root.optInt("scene", 0)
        val scenes = root.optJSONArray("scenes")
        val rootsFromScene = scenes?.optJSONObject(sceneIdx.coerceIn(0, maxOf(0, scenes.length() - 1)))?.optJSONArray("nodes")
        val rootNodes = ArrayList<Int>()
        if (rootsFromScene != null) {
            for (i in 0 until rootsFromScene.length()) rootNodes.add(rootsFromScene.optInt(i, -1))
        } else if (nodes.length() > 0) {
            val children = HashSet<Int>()
            for (i in 0 until nodes.length()) {
                val c = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
                for (k in 0 until c.length()) children.add(c.optInt(k, -1))
            }
            for (i in 0 until nodes.length()) if (i !in children) rootNodes.add(i)
        }
        if (rootNodes.isEmpty()) {
            for (m in 0 until meshes.length()) {
                if (out.truncated) break
                emitMesh(m, IDENTITY, null, null, -1, -1)
            }
            return true
        }
        val visited = HashSet<Int>()
        for (n in rootNodes) computeWorld(n, IDENTITY, visited, 0)
        try {
            prepareAnimation()
        } catch (_: Throwable) {
            frameTimes = FloatArray(0)
        }
        val emitted = HashSet<Int>()
        for (n in rootNodes) emitNode(n, emitted, 0)
        finishAnimation()
        return true
    }

    private fun prepareAnimation() {
        for (i in 0 until nodes.length()) {
            val c = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
            for (k in 0 until c.length()) {
                val ch = c.optInt(k, -1)
                if (ch in parents.indices) parents[ch] = i
            }
        }
        val animations = root.optJSONArray("animations") ?: return
        val defs = ArrayList<ClipDef>()
        for (ai in 0 until animations.length()) {
            val animObj = animations.optJSONObject(ai) ?: continue
            val samplers = animObj.optJSONArray("samplers") ?: continue
            val chs = animObj.optJSONArray("channels") ?: continue
            val list = ArrayList<Channel>()
            val claimed = HashSet<String>()
            var end = 0f
            for (i in 0 until chs.length()) {
                val ch = chs.optJSONObject(i) ?: continue
                val target = ch.optJSONObject("target") ?: continue
                val node = target.optInt("node", -1)
                val path = target.optString("path", "")
                if (node !in 0 until nodes.length() || path !in setOf("translation", "rotation", "scale", "weights")) continue
                if (!claimed.add("$node/$path")) continue
                val sm = samplers.optJSONObject(ch.optInt("sampler", -1)) ?: continue
                val input = readAccessor(sm.optInt("input", -1)) ?: continue
                val output = readAccessor(sm.optInt("output", -1)) ?: continue
                val interp = sm.optString("interpolation", "LINEAR").uppercase()
                val n = input.count
                if (n == 0) continue
                val per = if (interp == "CUBICSPLINE") 3 else 1
                val comps = if (path == "weights") output.data.size / (n * per) else output.components
                if (comps <= 0 || output.data.size < n * per * comps) continue
                val times = input.data.copyOf(n)
                list.add(Channel(node, path, times, output.data, comps, interp))
                end = maxOf(end, times.maxOrNull() ?: 0f)
                if (path == "weights") animatedWeightNodes.add(node)
            }
            if (list.isEmpty() || !end.isFinite() || end < 0f) continue
            defs.add(ClipDef(animObj.optString("name", "").ifBlank { "Animation ${ai + 1}" }, list, end))
        }
        if (defs.isEmpty()) return
        val animated = defs.flatMap { d -> d.channels.filter { it.path != "weights" }.map { it.node } }.toHashSet()
        dynamicNodes = BooleanArray(nodes.length()) { i ->
            var p = i
            var hit = false
            var guard = 0
            while (p >= 0 && guard++ < 256) {
                if (p in animated) {
                    hit = true
                    break
                }
                p = parents[p]
            }
            hit
        }
        var joints = 0
        for (k in 0 until skins.length()) joints += skins.optJSONObject(k)?.optJSONArray("joints")?.length() ?: 0
        val estimate = maxOf(1, dynamicNodes.count { it } + joints)
        val totalTime = defs.sumOf { it.end.toDouble() }.toFloat()
        var f = 30f
        while (f > 1f && (totalTime * f + defs.size * 2f) * estimate * 12f > 8_000_000f) f /= 2f
        fps = f
        clips = defs
        val lens = IntArray(defs.size) { minOf(6000, kotlin.math.ceil(defs[it].end * f).toInt() + 1).coerceAtLeast(2) }
        val starts = IntArray(defs.size)
        var acc = 0
        for (i in defs.indices) {
            starts[i] = acc
            acc += lens[i]
        }
        clipStarts = starts
        clipLens = lens
        frameClip = IntArray(acc)
        frameTimes = FloatArray(acc)
        for (i in defs.indices) for (k in 0 until lens[i]) {
            frameClip[starts[i] + k] = i
            frameTimes[starts[i] + k] = minOf(k / f, defs[i].end)
        }
    }

    private fun sample(c: Channel, t: Float, out: FloatArray) {
        val n = c.times.size
        val per = if (c.interp == "CUBICSPLINE") 3 else 1
        fun value(k: Int, i: Int) = c.values[(k * per + (if (per == 3) 1 else 0)) * c.comps + i]
        if (t <= c.times[0] || n == 1) {
            for (i in 0 until c.comps) out[i] = value(0, i)
            return
        }
        if (t >= c.times[n - 1]) {
            for (i in 0 until c.comps) out[i] = value(n - 1, i)
            return
        }
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (c.times[mid] <= t) lo = mid else hi = mid
        }
        val t0 = c.times[lo]
        val t1 = c.times[hi]
        val td = (t1 - t0).takeIf { it > 0f } ?: 1f
        val a = ((t - t0) / td).coerceIn(0f, 1f)
        when (c.interp) {
            "STEP" -> for (i in 0 until c.comps) out[i] = value(lo, i)
            "CUBICSPLINE" -> {
                val a2 = a * a
                val a3 = a2 * a
                val h00 = 2 * a3 - 3 * a2 + 1
                val h10 = a3 - 2 * a2 + a
                val h01 = -2 * a3 + 3 * a2
                val h11 = a3 - a2
                for (i in 0 until c.comps) {
                    val p0 = value(lo, i)
                    val m0 = c.values[(lo * 3 + 2) * c.comps + i] * td
                    val p1 = value(hi, i)
                    val m1 = c.values[(hi * 3) * c.comps + i] * td
                    out[i] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1
                }
                if (c.path == "rotation") normalize4(out)
            }
            else -> {
                if (c.path == "rotation" && c.comps == 4) {
                    var dot = 0f
                    for (i in 0 until 4) dot += value(lo, i) * value(hi, i)
                    val sign = if (dot < 0f) -1f else 1f
                    val d = kotlin.math.abs(dot)
                    if (d > 0.9995f) {
                        for (i in 0 until 4) out[i] = value(lo, i) + (sign * value(hi, i) - value(lo, i)) * a
                    } else {
                        val theta = kotlin.math.acos(d.coerceIn(-1f, 1f))
                        val st = sin(theta)
                        val w0 = sin((1 - a) * theta) / st
                        val w1 = sign * sin(a * theta) / st
                        for (i in 0 until 4) out[i] = w0 * value(lo, i) + w1 * value(hi, i)
                    }
                    normalize4(out)
                } else {
                    for (i in 0 until c.comps) out[i] = value(lo, i) + (value(hi, i) - value(lo, i)) * a
                }
            }
        }
    }

    private fun normalize4(q: FloatArray) {
        val l = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (l > 0f) for (i in 0 until 4) q[i] /= l
    }

    private fun localAt(node: Int, k: Int): FloatArray {
        val json = nodes.optJSONObject(node) ?: return IDENTITY
        val tArr = json.optJSONArray("translation")
        val rArr = json.optJSONArray("rotation")
        val sArr = json.optJSONArray("scale")
        var t = FloatArray(3) { tArr?.optDouble(it, 0.0)?.toFloat() ?: 0f }
        var r = FloatArray(4) { rArr?.optDouble(it, if (it == 3) 1.0 else 0.0)?.toFloat() ?: (if (it == 3) 1f else 0f) }
        var sc = FloatArray(3) { sArr?.optDouble(it, 1.0)?.toFloat() ?: 1f }
        var any = false
        for (c in clips[frameClip[k]].channels) {
            if (c.node != node) continue
            when (c.path) {
                "translation" -> { t = FloatArray(3); sample(c, frameTimes[k], t); any = true }
                "rotation" -> { r = FloatArray(4); sample(c, frameTimes[k], r); any = true }
                "scale" -> { sc = FloatArray(3); sample(c, frameTimes[k], sc); any = true }
            }
        }
        if (!any) return localMatrix(json)
        return trs(t, r, sc)
    }

    private fun worldAt(node: Int, k: Int, depth: Int = 0): FloatArray {
        if (node !in dynamicNodes.indices || !dynamicNodes[node]) return worldMatrices[node] ?: IDENTITY
        val arr = frameWorlds.getOrPut(node) { arrayOfNulls(frameTimes.size) }
        arr[k]?.let { return it }
        val p = parents[node]
        val parentWorld = if (p >= 0 && depth < 256) worldAt(p, k, depth + 1) else IDENTITY
        val w = mul(parentWorld, localAt(node, k))
        arr[k] = w
        return w
    }

    private fun pack(m: FloatArray, dst: FloatArray, at: Int) {
        dst[at] = m[0]; dst[at + 1] = m[1]; dst[at + 2] = m[2]
        dst[at + 3] = m[4]; dst[at + 4] = m[5]; dst[at + 5] = m[6]
        dst[at + 6] = m[8]; dst[at + 7] = m[9]; dst[at + 8] = m[10]
        dst[at + 9] = m[12]; dst[at + 10] = m[13]; dst[at + 11] = m[14]
    }

    private fun invert(m: FloatArray): FloatArray {
        val d = UsdMath.invert(DoubleArray(16) { m[it].toDouble() }) ?: return IDENTITY
        return FloatArray(16) { d[it].toFloat() }
    }

    private fun rigidBone(node: Int): Int {
        if (!animActive || node !in dynamicNodes.indices || !dynamicNodes[node]) return -1
        return rigidBones.getOrPut(node) {
            val inv = invert(worldMatrices[node] ?: IDENTITY)
            val frames = FloatArray(frameTimes.size * 12)
            for (k in frameTimes.indices) pack(mul(worldAt(node, k), inv), frames, k * 12)
            boneFrames.add(frames)
            boneFrames.size - 1
        }
    }

    private fun skinBone(skinIdx: Int, j: Int, jointNode: Int, ibm: FloatArray): Int {
        if (!animActive) return -1
        val key = (skinIdx.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
        return skinBones.getOrPut(key) {
            val frames = FloatArray(frameTimes.size * 12)
            for (k in frameTimes.indices) pack(mul(worldAt(jointNode, k), ibm), frames, k * 12)
            boneFrames.add(frames)
            boneFrames.size - 1
        }
    }

    private fun morphTarget(node: Int, target: Int, base: Float): Int {
        val weights = FloatArray(frameTimes.size) { base }
        val buf = FloatArray(4096)
        for (k in frameTimes.indices) {
            for (c in clips[frameClip[k]].channels) {
                if (c.node != node || c.path != "weights" || target >= c.comps || c.comps > buf.size) continue
                sample(c, frameTimes[k], buf)
                weights[k] = buf[target]
            }
        }
        morphFrameWeights.add(weights)
        morphBaseList.add(base)
        return morphFrameWeights.size - 1
    }

    private fun finishAnimation() {
        if (!animActive) return
        val bones = boneFrames.size
        val morphs = morphFrameWeights.size
        if (bones == 0 && morphs == 0) return
        val frames = frameTimes.size
        val matrices = FloatArray(frames * bones * 12)
        for (b in 0 until bones) {
            val src = boneFrames[b]
            for (k in 0 until frames) System.arraycopy(src, k * 12, matrices, (k * bones + b) * 12, 12)
        }
        val weights = if (morphs > 0) FloatArray(frames * morphs).also { w ->
            for (t in 0 until morphs) for (k in 0 until frames) w[k * morphs + t] = morphFrameWeights[t][k]
        } else null
        out.animation(AnimClip(
            fps, frames, bones, matrices, morphs, weights, if (morphs > 0) morphBaseList.toFloatArray() else null,
            clips.map { it.name }, clipStarts, clipLens
        ))
    }

    private fun computeWorld(idx: Int, parent: FloatArray, visited: HashSet<Int>, depth: Int) {
        if (depth > 64 || idx !in 0 until nodes.length() || !visited.add(idx)) return
        val node = nodes.optJSONObject(idx) ?: return
        val world = mul(parent, localMatrix(node))
        worldMatrices[idx] = world
        val children = node.optJSONArray("children") ?: return
        for (k in 0 until children.length()) computeWorld(children.optInt(k, -1), world, visited, depth + 1)
    }

    private fun emitNode(idx: Int, emitted: HashSet<Int>, depth: Int) {
        if (depth > 64 || out.truncated || idx !in 0 until nodes.length() || !emitted.add(idx)) return
        val node = nodes.optJSONObject(idx) ?: return
        val world = worldMatrices[idx] ?: IDENTITY
        if (node.has("mesh")) {
            val skin = if (node.has("skin")) skins.optJSONObject(node.optInt("skin", -1)) else null
            val weights = node.optJSONArray("weights")
            val instancing = node.optJSONObject("extensions")?.optJSONObject("EXT_mesh_gpu_instancing")?.optJSONObject("attributes")
            val skinIdx = if (node.has("skin")) node.optInt("skin", -1) else -1
            if (instancing != null && skin == null) {
                val t = instancing.optInt("TRANSLATION", -1).let { if (it >= 0) readAccessor(it) else null }
                val r = instancing.optInt("ROTATION", -1).let { if (it >= 0) readAccessor(it) else null }
                val s = instancing.optInt("SCALE", -1).let { if (it >= 0) readAccessor(it) else null }
                val count = listOfNotNull(t?.count, r?.count, s?.count).minOrNull() ?: 0
                for (i in 0 until count) {
                    if (out.truncated) break
                    val m = trs(
                        t?.let { floatArrayOf(it.data[i * 3], it.data[i * 3 + 1], it.data[i * 3 + 2]) },
                        r?.let { floatArrayOf(it.data[i * 4], it.data[i * 4 + 1], it.data[i * 4 + 2], it.data[i * 4 + 3]) },
                        s?.let { floatArrayOf(it.data[i * 3], it.data[i * 3 + 1], it.data[i * 3 + 2]) }
                    )
                    emitMesh(node.optInt("mesh", -1), mul(world, m), null, weights, idx, -1)
                }
            } else {
                emitMesh(node.optInt("mesh", -1), world, skin, weights, idx, skinIdx)
            }
        }
        val children = node.optJSONArray("children") ?: return
        for (k in 0 until children.length()) emitNode(children.optInt(k, -1), emitted, depth + 1)
    }

    private fun bufferBytes(idx: Int): ByteArray? {
        if (bufferCache.containsKey(idx)) return bufferCache[idx]
        val b = buffers.optJSONObject(idx)
        val uri = b?.optString("uri", "") ?: ""
        val bytes = when {
            uri.isEmpty() -> null
            uri.startsWith("data:", ignoreCase = true) -> decodeDataUri(uri)
            else -> out.resource(uri)
        }
        bufferCache[idx] = bytes
        return bytes
    }

    private fun readBuffer(idx: Int, offset: Long, length: Int): ByteArray? {
        if (length < 0 || offset < 0) return null
        val b = buffers.optJSONObject(idx)
        val uri = b?.optString("uri", "") ?: ""
        if (uri.isEmpty() && glbBin != null && idx == 0) {
            if (offset + length > glbBinLength) return null
            return glbBin.read(offset, length)
        }
        val bytes = bufferBytes(idx) ?: return null
        if (offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun viewBytes(viewIdx: Int): ByteArray? {
        if (viewCache.containsKey(viewIdx)) return viewCache[viewIdx]
        val bv = bufferViews.optJSONObject(viewIdx)
        val result = if (bv == null) null else {
            val ext = bv.optJSONObject("extensions")
            val meshopt = ext?.optJSONObject("EXT_meshopt_compression") ?: ext?.optJSONObject("KHR_meshopt_compression")
            if (meshopt != null) {
                val src = readBuffer(meshopt.optInt("buffer", 0), meshopt.optLong("byteOffset", 0L), meshopt.optInt("byteLength", 0))
                if (src == null) null else {
                    val count = meshopt.optInt("count", 0)
                    val stride = meshopt.optInt("byteStride", 0)
                    if (count <= 0 || stride <= 0 || count.toLong() * stride > 512L * 1024 * 1024) null else try {
                        CleanRoomMeshopt.decode(
                            src, count, stride,
                            meshopt.optString("mode", "ATTRIBUTES"),
                            meshopt.optString("filter", "NONE")
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else {
                readBuffer(bv.optInt("buffer", 0), bv.optLong("byteOffset", 0L), bv.optInt("byteLength", 0))
            }
        }
        viewCache[viewIdx] = result
        return result
    }

    private fun components(type: String) = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> 1
    }

    private fun componentSize(t: Int) = when (t) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        else -> 4
    }

    private fun readComponent(bb: ByteBuffer, pos: Int, type: Int, normalized: Boolean): Float = when (type) {
        5126 -> bb.getFloat(pos)
        5120 -> bb.get(pos).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
        5121 -> (bb.get(pos).toInt() and 0xFF).toFloat().let { if (normalized) it / 255f else it }
        5122 -> bb.getShort(pos).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
        5123 -> (bb.getShort(pos).toInt() and 0xFFFF).toFloat().let { if (normalized) it / 65535f else it }
        5125 -> (bb.getInt(pos).toLong() and 0xFFFFFFFFL).toFloat()
        else -> 0f
    }

    private fun matrixColumnPadding(type: String, compSize: Int): Int = when {
        type == "MAT2" && compSize == 1 -> 2
        type == "MAT3" && compSize == 1 -> 1
        type == "MAT3" && compSize == 2 -> 2
        else -> 0
    }

    fun readAccessor(idx: Int): Accessor? {
        val acc = accessors.optJSONObject(idx) ?: return null
        val count = acc.optInt("count", 0)
        if (count <= 0 || count > maxElements) return null
        val type = acc.optString("type", "SCALAR")
        val comps = components(type)
        val compType = acc.optInt("componentType", 5126)
        val cs = componentSize(compType)
        val normalized = acc.optBoolean("normalized", false)
        val data = FloatArray(count * comps)
        if (acc.has("bufferView")) {
            val bvIdx = acc.optInt("bufferView", -1)
            val view = viewBytes(bvIdx) ?: return null
            val bv = bufferViews.optJSONObject(bvIdx)
            val pad = matrixColumnPadding(type, cs)
            val rows = when (type) { "MAT2" -> 2; "MAT3" -> 3; "MAT4" -> 4; else -> comps }
            val cols = comps / rows
            val elementSize = (rows * cs + pad) * cols
            val stride = bv?.optInt("byteStride", 0)?.takeIf { it > 0 } ?: elementSize
            val offset = acc.optInt("byteOffset", 0)
            val bb = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val base = offset + i * stride
                if (base + elementSize > view.size) return Accessor(data, i, comps)
                var k = 0
                for (c in 0 until cols) {
                    val colBase = base + c * (rows * cs + pad)
                    for (r in 0 until rows) {
                        data[i * comps + k++] = readComponent(bb, colBase + r * cs, compType, normalized)
                    }
                }
            }
        }
        val sparse = acc.optJSONObject("sparse")
        if (sparse != null) {
            val n = sparse.optInt("count", 0)
            val ind = sparse.optJSONObject("indices")
            val vals = sparse.optJSONObject("values")
            if (n > 0 && ind != null && vals != null) {
                val iView = viewBytes(ind.optInt("bufferView", -1))
                val vView = viewBytes(vals.optInt("bufferView", -1))
                if (iView != null && vView != null) {
                    val ib = ByteBuffer.wrap(iView).order(ByteOrder.LITTLE_ENDIAN)
                    val vb = ByteBuffer.wrap(vView).order(ByteOrder.LITTLE_ENDIAN)
                    val iType = ind.optInt("componentType", 5125)
                    val iOff = ind.optInt("byteOffset", 0)
                    val vOff = vals.optInt("byteOffset", 0)
                    val isz = componentSize(iType)
                    for (k in 0 until n) {
                        val ip = iOff + k * isz
                        if (ip + isz > iView.size) break
                        val target = readComponent(ib, ip, iType, false).toInt()
                        if (target !in 0 until count) continue
                        for (c in 0 until comps) {
                            val vp = vOff + (k * comps + c) * cs
                            if (vp + cs > vView.size) break
                            data[target * comps + c] = readComponent(vb, vp, compType, normalized)
                        }
                    }
                }
            }
        }
        for (i in data.indices) if (!data[i].isFinite()) data[i] = 0f
        return Accessor(data, count, comps)
    }

    private fun readIndices(idx: Int): IntArray? {
        val acc = accessors.optJSONObject(idx) ?: return null
        val count = acc.optInt("count", 0)
        if (count <= 0 || count > maxElements * 3) return null
        val compType = acc.optInt("componentType", 5125)
        val cs = componentSize(compType)
        val bvIdx = acc.optInt("bufferView", -1)
        val view = viewBytes(bvIdx) ?: return null
        val stride = bufferViews.optJSONObject(bvIdx)?.optInt("byteStride", 0)?.takeIf { it > 0 } ?: cs
        val offset = acc.optInt("byteOffset", 0)
        val bb = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN)
        val outIdx = IntArray(count)
        for (i in 0 until count) {
            val p = offset + i * stride
            if (p + cs > view.size) return outIdx.copyOf(i)
            outIdx[i] = when (cs) {
                1 -> bb.get(p).toInt() and 0xFF
                2 -> bb.getShort(p).toInt() and 0xFFFF
                else -> bb.getInt(p)
            }
        }
        return outIdx
    }

    private fun imageBytes(imgIdx: Int): ByteArray? {
        val img = images.optJSONObject(imgIdx) ?: return null
        if (img.has("bufferView")) return viewBytes(img.optInt("bufferView", -1))
        val uri = img.optString("uri", "")
        if (uri.isEmpty()) return null
        return if (uri.startsWith("data:", ignoreCase = true)) decodeDataUri(uri) else out.resource(uri)
    }

    private fun textureSource(texIdx: Int): Int {
        val tex = textures.optJSONObject(texIdx) ?: return -1
        val ext = tex.optJSONObject("extensions")
        val webp = ext?.optJSONObject("EXT_texture_webp")?.optInt("source", -1) ?: -1
        val avif = ext?.optJSONObject("EXT_texture_avif")?.optInt("source", -1) ?: -1
        return when {
            webp >= 0 -> webp
            avif >= 0 && android.os.Build.VERSION.SDK_INT >= 31 -> avif
            tex.has("source") -> tex.optInt("source", -1)
            else -> -1
        }
    }

    private fun material(idx: Int): Material {
        materialCache[idx]?.let { return it }
        val mat = materials.optJSONObject(idx)
        val result = if (mat == null) {
            Material(0, -1, 0, null, "OPAQUE", 0.5f)
        } else {
            val alphaMode = mat.optString("alphaMode", "OPAQUE").uppercase()
            val pbr = mat.optJSONObject("pbrMetallicRoughness")
            val specGloss = mat.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")
            val factor = pbr?.optJSONArray("baseColorFactor") ?: specGloss?.optJSONArray("diffuseFactor")
            val texInfo = pbr?.optJSONObject("baseColorTexture") ?: specGloss?.optJSONObject("diffuseTexture")
            val r = factor?.optDouble(0, 1.0) ?: 1.0
            val g = factor?.optDouble(1, 1.0) ?: 1.0
            val b = factor?.optDouble(2, 1.0) ?: 1.0
            val a = if (alphaMode == "OPAQUE") 1.0 else factor?.optDouble(3, 1.0) ?: 1.0
            var texture = -1
            var texCoord = 0
            var transform: FloatArray? = null
            if (texInfo != null) {
                val texIdx = texInfo.optInt("index", -1)
                val src = textureSource(texIdx)
                if (src >= 0) {
                    texture = out.texture("gltf:$src:${alphaMode == "OPAQUE"}", imageBytes(src), alphaMode == "OPAQUE")
                }
                texCoord = texInfo.optInt("texCoord", 0)
                val tt = texInfo.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform")
                if (tt != null) {
                    if (tt.has("texCoord")) texCoord = tt.optInt("texCoord", texCoord)
                    val off = tt.optJSONArray("offset")
                    val sc = tt.optJSONArray("scale")
                    val rot = tt.optDouble("rotation", 0.0).toFloat()
                    val ox = off?.optDouble(0, 0.0)?.toFloat() ?: 0f
                    val oy = off?.optDouble(1, 0.0)?.toFloat() ?: 0f
                    val sx = sc?.optDouble(0, 1.0)?.toFloat() ?: 1f
                    val sy = sc?.optDouble(1, 1.0)?.toFloat() ?: 1f
                    val c = cos(rot)
                    val s = sin(rot)
                    transform = floatArrayOf(c * sx, s * sy, ox, -s * sx, c * sy, oy)
                }
            }
            val color = argb(a, r, g, b)
            var normalTexture = -1
            var normalTexCoord = 0
            mat.optJSONObject("normalTexture")?.let { nt ->
                val src = textureSource(nt.optInt("index", -1))
                if (src >= 0) {
                    normalTexture = out.normalTexture("gltf:$src", imageBytes(src), false, nt.optDouble("scale", 1.0).toFloat())
                    normalTexCoord = nt.optInt("texCoord", 0)
                }
            }
            Material(color, texture, texCoord, transform, alphaMode, mat.optDouble("alphaCutoff", 0.5).toFloat(), normalTexture, normalTexCoord)
        }
        materialCache[idx] = result
        return result
    }

    private fun argb(a: Double, r: Double, g: Double, b: Double): Int {
        fun ch(v: Double) = (linearToSrgb(v) * 255.0).roundToInt().coerceIn(0, 255)
        val alpha = (a.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun linearToSrgb(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    private class Primitive(
        val positions: FloatArray,
        val vertexCount: Int,
        val indices: IntArray?,
        val uv: Array<FloatArray?>,
        val colors: Accessor?,
        val joints: Accessor?,
        val weights: Accessor?,
        val normals: FloatArray?,
        val targets: List<FloatArray?>,
        val targetNormals: List<FloatArray?> = emptyList()
    )

    private fun loadPrimitive(prim: JSONObject, meshWeights: JSONArray?): Primitive? {
        val attrs = prim.optJSONObject("attributes") ?: return null
        val draco = prim.optJSONObject("extensions")?.optJSONObject("KHR_draco_mesh_compression")
        if (draco != null) {
            val view = viewBytes(draco.optInt("bufferView", -1)) ?: return null
            val decoded = try { CleanRoomDraco.decode(view) } catch (_: Throwable) { null } ?: return null
            val ids = draco.optJSONObject("attributes") ?: JSONObject()
            fun attr(name: String): Accessor? {
                if (!ids.has(name)) return null
                val a = decoded.attribute(ids.optInt(name, -1)) ?: return null
                val acc = accessors.optJSONObject(attrs.optInt(name, -1))
                val normalized = acc?.optBoolean("normalized", false) ?: false
                val ct = acc?.optInt("componentType", 5126) ?: 5126
                if (normalized && a.isInteger) {
                    val scale = when (ct) { 5120 -> 127f; 5121 -> 255f; 5122 -> 32767f; 5123 -> 65535f; else -> 1f }
                    for (i in a.values.indices) a.values[i] = maxOf(a.values[i] / scale, -1f)
                }
                return Accessor(a.values, decoded.pointCount, a.components)
            }
            val pos = attr("POSITION") ?: return null
            return Primitive(
                pos.data, pos.count, decoded.faces,
                arrayOf(attr("TEXCOORD_0")?.data, attr("TEXCOORD_1")?.data),
                attr("COLOR_0"), attr("JOINTS_0"), attr("WEIGHTS_0"),
                attr("NORMAL")?.takeIf { it.components == 3 }?.data, emptyList()
            )
        }
        val pos = readAccessor(attrs.optInt("POSITION", -1)) ?: return null
        if (pos.components != 3) return null
        val positions = pos.data
        val targets = prim.optJSONArray("targets")
        val targetDeltas = ArrayList<FloatArray?>()
        val targetNormals = ArrayList<FloatArray?>()
        if (targets != null) {
            for (t in 0 until targets.length()) {
                val d = readAccessor(targets.optJSONObject(t)?.optInt("POSITION", -1) ?: -1)
                targetDeltas.add(d?.data)
                targetNormals.add(targets.optJSONObject(t)?.optInt("NORMAL", -1)?.takeIf { it >= 0 }?.let { readAccessor(it)?.data })
                val w = meshWeights?.optDouble(t, 0.0)?.toFloat() ?: 0f
                if (w == 0f || d == null) continue
                for (i in 0 until minOf(d.data.size, positions.size)) positions[i] += w * d.data[i]
            }
        }
        val indices = if (prim.has("indices")) readIndices(prim.optInt("indices", -1)) else null
        val uv0 = if (attrs.has("TEXCOORD_0")) readAccessor(attrs.optInt("TEXCOORD_0", -1))?.data else null
        val uv1 = if (attrs.has("TEXCOORD_1")) readAccessor(attrs.optInt("TEXCOORD_1", -1))?.data else null
        val colors = if (attrs.has("COLOR_0")) readAccessor(attrs.optInt("COLOR_0", -1)) else null
        val joints = if (attrs.has("JOINTS_0")) readAccessor(attrs.optInt("JOINTS_0", -1)) else null
        val weights = if (attrs.has("WEIGHTS_0")) readAccessor(attrs.optInt("WEIGHTS_0", -1)) else null
        val normals = if (attrs.has("NORMAL")) readAccessor(attrs.optInt("NORMAL", -1))?.takeIf { it.components == 3 }?.data else null
        if (normals != null && targets != null && meshWeights != null) {
            for (t in 0 until minOf(targetNormals.size, meshWeights.length())) {
                val w = meshWeights.optDouble(t, 0.0).toFloat()
                val d = targetNormals[t] ?: continue
                if (w == 0f) continue
                for (i in 0 until minOf(d.size, normals.size)) normals[i] += w * d[i]
            }
        }
        return Primitive(positions, pos.count, indices, arrayOf(uv0, uv1), colors, joints, weights, normals, targetDeltas, targetNormals)
    }

    private fun skinMatrices(skin: JSONObject): Array<FloatArray>? {
        val joints = skin.optJSONArray("joints") ?: return null
        val ibm = if (skin.has("inverseBindMatrices")) readAccessor(skin.optInt("inverseBindMatrices", -1)) else null
        return Array(joints.length()) { j ->
            val world = worldMatrices[joints.optInt(j, -1)] ?: IDENTITY
            mul(world, inverseBind(ibm, j))
        }
    }

    private fun inverseBind(ibm: Accessor?, j: Int): FloatArray =
        if (ibm != null && ibm.components == 16 && j < ibm.count) ibm.data.copyOfRange(j * 16, j * 16 + 16) else IDENTITY

    private fun normalMatrix(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val sg = if (det < 0f) -1f else 1f
        return floatArrayOf(
            sg * (e * i - f * h), sg * -(d * i - f * g), sg * (d * h - e * g),
            sg * -(b * i - c * h), sg * (a * i - c * g), sg * -(a * h - b * g),
            sg * (b * f - c * e), sg * -(a * f - c * d), sg * (a * e - b * d)
        )
    }

    private fun emitMesh(meshIdx: Int, world: FloatArray, skin: JSONObject?, nodeWeights: JSONArray?, nodeIdx: Int, skinIdx: Int) {
        val mesh = meshes.optJSONObject(meshIdx) ?: return
        val prims = mesh.optJSONArray("primitives") ?: return
        val weightsArr = nodeWeights ?: mesh.optJSONArray("weights")
        val jointMats = skin?.let { skinMatrices(it) }
        val jointList = skin?.optJSONArray("joints")
        val ibm = if (skin != null && skin.has("inverseBindMatrices")) readAccessor(skin.optInt("inverseBindMatrices", -1)) else null
        val jointBones = if (jointMats != null && jointList != null && animActive) {
            IntArray(jointList.length()) { j -> skinBone(skinIdx, j, jointList.optInt(j, -1), inverseBind(ibm, j)) }
        } else null
        val rigid = if (jointMats == null && nodeIdx >= 0) rigidBone(nodeIdx) else -1
        val morphAnimated = animActive && nodeIdx >= 0 && nodeIdx in animatedWeightNodes
        val nm = normalMatrix(world)
        for (pi in 0 until prims.length()) {
            if (out.truncated) return
            val prim = prims.optJSONObject(pi) ?: continue
            val p = loadPrimitive(prim, weightsArr) ?: continue
            val n = p.vertexCount
            val pos = FloatArray(n * 3)
            val nrm = if (p.normals != null && p.normals.size >= n * 3) FloatArray(n * 3) else null
            val joints = p.joints
            val wts = p.weights
            val skinned = BooleanArray(n)
            for (i in 0 until n) {
                val x = p.positions[i * 3]
                val y = p.positions[i * 3 + 1]
                val z = p.positions[i * 3 + 2]
                if (jointMats != null && joints != null && wts != null && i < joints.count && i < wts.count) {
                    var sx = 0f
                    var sy = 0f
                    var sz = 0f
                    var nx = 0f
                    var ny = 0f
                    var nz = 0f
                    var sw = 0f
                    for (k in 0 until minOf(4, joints.components, wts.components)) {
                        val w = wts.data[i * wts.components + k]
                        if (w == 0f) continue
                        val m = jointMats.getOrNull(joints.data[i * joints.components + k].toInt()) ?: continue
                        sx += w * (m[0] * x + m[4] * y + m[8] * z + m[12])
                        sy += w * (m[1] * x + m[5] * y + m[9] * z + m[13])
                        sz += w * (m[2] * x + m[6] * y + m[10] * z + m[14])
                        if (nrm != null) {
                            val ux = p.normals!![i * 3]
                            val uy = p.normals[i * 3 + 1]
                            val uz = p.normals[i * 3 + 2]
                            nx += w * (m[0] * ux + m[4] * uy + m[8] * uz)
                            ny += w * (m[1] * ux + m[5] * uy + m[9] * uz)
                            nz += w * (m[2] * ux + m[6] * uy + m[10] * uz)
                        }
                        sw += w
                    }
                    if (sw > 0f) {
                        pos[i * 3] = sx / sw
                        pos[i * 3 + 1] = sy / sw
                        pos[i * 3 + 2] = sz / sw
                        if (nrm != null) {
                            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                            if (l > 0f) {
                                nrm[i * 3] = nx / l
                                nrm[i * 3 + 1] = ny / l
                                nrm[i * 3 + 2] = nz / l
                            }
                        }
                        skinned[i] = true
                        continue
                    }
                }
                pos[i * 3] = world[0] * x + world[4] * y + world[8] * z + world[12]
                pos[i * 3 + 1] = world[1] * x + world[5] * y + world[9] * z + world[13]
                pos[i * 3 + 2] = world[2] * x + world[6] * y + world[10] * z + world[14]
                if (nrm != null) {
                    val ux = p.normals!![i * 3]
                    val uy = p.normals[i * 3 + 1]
                    val uz = p.normals[i * 3 + 2]
                    val nx = nm[0] * ux + nm[1] * uy + nm[2] * uz
                    val ny = nm[3] * ux + nm[4] * uy + nm[5] * uz
                    val nz = nm[6] * ux + nm[7] * uy + nm[8] * uz
                    val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                    if (l > 0f) {
                        nrm[i * 3] = nx / l
                        nrm[i * 3 + 1] = ny / l
                        nrm[i * 3 + 2] = nz / l
                    }
                }
            }
            val morphIds = if (morphAnimated && p.targets.isNotEmpty()) IntArray(p.targets.size) { t ->
                if (p.targets[t] == null) -1 else morphTarget(nodeIdx, t, weightsArr?.optDouble(t, 0.0)?.toFloat() ?: 0f)
            } else null
            val mat = material(prim.optInt("material", -1))
            val baseUv = p.uv.getOrNull(mat.texCoord)?.takeIf { it.size >= n * 2 }
            val tex = if (mat.texture >= 0 && baseUv != null) mat.texture else -1
            val normalUv = if (tex >= 0) baseUv else p.uv.getOrNull(mat.normalTexCoord)?.takeIf { it.size >= n * 2 }
            val ntex = if (mat.normalTexture >= 0 && normalUv != null) mat.normalTexture else -1
            val uvSrc = if (tex >= 0) baseUv else normalUv
            val vColors = p.colors?.let { c ->
                IntArray(n) { i ->
                    if (i >= c.count) 0 else {
                        val base = i * c.components
                        val a = if (c.components >= 4) c.data[base + 3].toDouble() else 1.0
                        val f = Color4(mat.color)
                        argb(
                            if (mat.alphaMode == "OPAQUE") 1.0 else a * f.a,
                            c.data[base].toDouble() * f.rLin, c.data[base + 1].toDouble() * f.gLin, c.data[base + 2].toDouble() * f.bLin
                        )
                    }
                }
            }
            if (mat.alphaMode == "MASK" && mat.color != 0 && (mat.color ushr 24) / 255f < mat.alphaCutoff) continue
            if (mat.alphaMode == "BLEND" && mat.color != 0 && (mat.color ushr 24) == 0) continue
            val baseColor = mat.color
            val uvTri = FloatArray(6)
            val tri = FloatArray(9)
            val nTri = FloatArray(9)
            val jTri = IntArray(12)
            val wTri = FloatArray(12)
            val bTri = FloatArray(9)
            val bnTri = FloatArray(9)
            fun uvAt(i: Int, slot: Int) {
                val u = uvSrc!![i * 2]
                val v = uvSrc[i * 2 + 1]
                val t = if (tex >= 0) mat.transform else null
                if (t != null) {
                    uvTri[slot * 2] = t[0] * u + t[1] * v + t[2]
                    uvTri[slot * 2 + 1] = t[3] * u + t[4] * v + t[5]
                } else {
                    uvTri[slot * 2] = u
                    uvTri[slot * 2 + 1] = v
                }
            }
            fun emit(a: Int, b: Int, c: Int) {
                if (a !in 0 until n || b !in 0 until n || c !in 0 until n) return
                val corners = intArrayOf(a, b, c)
                val allSkinned = jointBones != null && skinned[a] && skinned[b] && skinned[c]
                for ((k, v) in corners.withIndex()) {
                    tri[k * 3] = pos[v * 3]
                    tri[k * 3 + 1] = pos[v * 3 + 1]
                    tri[k * 3 + 2] = pos[v * 3 + 2]
                    if (nrm != null) {
                        nTri[k * 3] = nrm[v * 3]
                        nTri[k * 3 + 1] = nrm[v * 3 + 1]
                        nTri[k * 3 + 2] = nrm[v * 3 + 2]
                    }
                    if (uvSrc != null) uvAt(v, k)
                    if (allSkinned) {
                        for (j in 0 until 4) {
                            val ok = j < minOf(joints!!.components, wts!!.components)
                            val jw = if (ok) wts.data[v * wts.components + j] else 0f
                            val ji = if (ok) joints.data[v * joints.components + j].toInt() else -1
                            val bone = jointBones!!.getOrElse(ji) { -1 }
                            jTri[k * 4 + j] = bone
                            wTri[k * 4 + j] = if (bone >= 0) jw else 0f
                        }
                        bTri[k * 3] = p.positions[v * 3]
                        bTri[k * 3 + 1] = p.positions[v * 3 + 1]
                        bTri[k * 3 + 2] = p.positions[v * 3 + 2]
                        if (p.normals != null) {
                            bnTri[k * 3] = p.normals[v * 3]
                            bnTri[k * 3 + 1] = p.normals[v * 3 + 1]
                            bnTri[k * 3 + 2] = p.normals[v * 3 + 2]
                        }
                    }
                }
                anim.reset()
                var animated = false
                if (allSkinned) {
                    anim.joints = jTri
                    anim.weights = wTri
                    anim.bindPos = bTri
                    anim.bindNrm = if (p.normals != null) bnTri else null
                    animated = true
                } else if (rigid >= 0) {
                    anim.bone = rigid
                    animated = true
                }
                if (morphIds != null) {
                    val ids = ArrayList<Int>()
                    val deltas = ArrayList<Float>()
                    val nDeltas = ArrayList<Float>()
                    for (t in morphIds.indices) {
                        val id = morphIds[t]
                        val d = p.targets[t] ?: continue
                        if (id < 0) continue
                        var nonZero = false
                        val local = FloatArray(9)
                        for ((k, v) in corners.withIndex()) for (q in 0 until 3) {
                            val dv = d.getOrElse(v * 3 + q) { 0f }
                            local[k * 3 + q] = dv
                            if (dv != 0f) nonZero = true
                        }
                        val dn = p.targetNormals.getOrNull(t)
                        if (!nonZero && dn == null) continue
                        ids.add(id)
                        for ((k, v) in corners.withIndex()) {
                            val x = dn?.getOrElse(v * 3) { 0f } ?: 0f
                            val y = dn?.getOrElse(v * 3 + 1) { 0f } ?: 0f
                            val z = dn?.getOrElse(v * 3 + 2) { 0f } ?: 0f
                            if (allSkinned) {
                                nDeltas.add(x); nDeltas.add(y); nDeltas.add(z)
                            } else {
                                nDeltas.add(nm[0] * x + nm[1] * y + nm[2] * z)
                                nDeltas.add(nm[3] * x + nm[4] * y + nm[5] * z)
                                nDeltas.add(nm[6] * x + nm[7] * y + nm[8] * z)
                            }
                        }
                        if (allSkinned) {
                            for (q in 0 until 9) deltas.add(local[q])
                        } else {
                            for (k in 0 until 3) {
                                val x = local[k * 3]
                                val y = local[k * 3 + 1]
                                val z = local[k * 3 + 2]
                                deltas.add(world[0] * x + world[4] * y + world[8] * z)
                                deltas.add(world[1] * x + world[5] * y + world[9] * z)
                                deltas.add(world[2] * x + world[6] * y + world[10] * z)
                            }
                        }
                    }
                    if (ids.isNotEmpty()) {
                        anim.morphTargets = ids.toIntArray()
                        anim.morphDeltas = deltas.toFloatArray()
                        if (nrm != null && p.targetNormals.any { it != null }) anim.morphNormalDeltas = nDeltas.toFloatArray()
                        animated = true
                    }
                }
                out.triangleFull(
                    tri, baseColor, if (uvSrc != null && (tex >= 0 || ntex >= 0)) uvTri else null, tex,
                    vColors?.get(a) ?: 0, vColors?.get(b) ?: 0, vColors?.get(c) ?: 0,
                    if (nrm != null) nTri else null, ntex, if (animated) anim else null
                )
            }
            val idx = p.indices
            val count = idx?.size ?: n
            fun at(i: Int) = idx?.get(i) ?: i
            when (prim.optInt("mode", 4)) {
                4 -> {
                    var i = 0
                    while (i + 2 < count && !out.truncated) {
                        emit(at(i), at(i + 1), at(i + 2))
                        i += 3
                    }
                }
                5 -> for (i in 0 until count - 2) {
                    if (out.truncated) break
                    if (i % 2 == 0) emit(at(i), at(i + 1), at(i + 2)) else emit(at(i + 1), at(i), at(i + 2))
                }
                6 -> for (i in 1 until count - 1) {
                    if (out.truncated) break
                    emit(at(0), at(i), at(i + 1))
                }
                1, 2, 3 -> {
                    val mode = prim.optInt("mode", 4)
                    val step = if (mode == 1) 2 else 1
                    var i = 0
                    while (i + 1 < count && !out.truncated) {
                        val a = at(i)
                        val b = at(i + 1)
                        if (a in 0 until n && b in 0 until n) {
                            out.line(pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2], pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2], mat.color)
                        }
                        i += step
                    }
                    if (mode == 2 && count > 2) {
                        val a = at(count - 1)
                        val b = at(0)
                        if (a in 0 until n && b in 0 until n) {
                            out.line(pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2], pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2], mat.color)
                        }
                    }
                }
            }
        }
    }

    private class Color4(raw: Int) {
        private val c = if (raw == 0) -1 else raw
        val a = (c ushr 24) / 255.0
        val rLin = srgbToLinear(((c shr 16) and 0xFF) / 255.0)
        val gLin = srgbToLinear(((c shr 8) and 0xFF) / 255.0)
        val bLin = srgbToLinear((c and 0xFF) / 255.0)

        private fun srgbToLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        fun decodeDataUri(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (comma < 0) return null
            val meta = uri.substring(5, comma)
            val payload = uri.substring(comma + 1)
            return try {
                if (meta.endsWith(";base64", ignoreCase = true)) Base64.decode(payload, Base64.DEFAULT)
                else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
            } catch (_: Throwable) {
                null
            }
        }

        fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(16)
            for (c in 0..3) for (row in 0..3) {
                r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3]
            }
            return r
        }

        fun trs(t: FloatArray?, q: FloatArray?, s: FloatArray?): FloatArray {
            val tx = t?.get(0) ?: 0f
            val ty = t?.get(1) ?: 0f
            val tz = t?.get(2) ?: 0f
            var qx = q?.get(0) ?: 0f
            var qy = q?.get(1) ?: 0f
            var qz = q?.get(2) ?: 0f
            var qw = q?.get(3) ?: 1f
            val ql = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (ql > 0f) {
                qx /= ql; qy /= ql; qz /= ql; qw /= ql
            }
            val sx = s?.get(0) ?: 1f
            val sy = s?.get(1) ?: 1f
            val sz = s?.get(2) ?: 1f
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

        fun localMatrix(node: JSONObject): FloatArray {
            val m = node.optJSONArray("matrix")
            if (m != null && m.length() == 16) return FloatArray(16) { m.optDouble(it, if (it % 5 == 0) 1.0 else 0.0).toFloat() }
            fun arr(name: String, n: Int): FloatArray? = node.optJSONArray(name)?.let { a -> FloatArray(n) { a.optDouble(it, 0.0).toFloat() } }
            return trs(arr("translation", 3), arr("rotation", 4), arr("scale", 3))
        }
    }
}
