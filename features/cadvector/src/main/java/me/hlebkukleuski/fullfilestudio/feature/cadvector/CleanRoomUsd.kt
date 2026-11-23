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
 * Portions ported and adapted from OpenUSD (https://openusd.org/),
 * Copyright 2016-2026 Pixar / Alliance for OpenUSD,
 * licensed under the Modified Apache 2.0 License (Tomorrow Open Source Technology License 1.0 / TOST-1.0),
 * with standard shader node definitions referencing Academy Software Foundation MaterialX
 * (https://materialx.org/), Copyright Contributors to the MaterialX Project (Apache-2.0).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal class UsdSrc(val layer: UsdLayer, val path: String, val maps: List<Pair<String, String>>)

internal class UsdPrim(val path: String, val name: String, val srcs: List<UsdSrc>)

internal class UsdStage(private val fetchFn: (String) -> ByteArray?, rootId: String) {
    private val layers = HashMap<String, UsdLayer?>()
    private val stacks = HashMap<UsdLayer, List<UsdLayer>>()
    private val primCache = HashMap<String, UsdPrim>()
    private val childCache = HashMap<String, List<UsdPrim>>()
    val rootLayer: UsdLayer? = layer(rootId)
    val rootStack: List<UsdLayer> = rootLayer?.let { stack(it, 0) } ?: emptyList()
    val root: UsdPrim? = if (rootStack.isEmpty()) null else UsdPrim("/", "", rootStack.map { UsdSrc(it, "/", emptyList()) })
    private val startTime: Double? = rootLayer?.let { UsdVal.num(it.field("/", "startTimeCode")) }

    fun fetch(id: String): ByteArray? = fetchFn(id)

    fun layer(id: String): UsdLayer? {
        if (layers.containsKey(id)) return layers[id]
        layers[id] = null
        val bytes = fetchFn(id) ?: return null
        val l = parse(id, bytes)
        layers[id] = l
        return l
    }

    private fun parse(id: String, bytes: ByteArray): UsdLayer? {
        val l = UsdLayer(id)
        return try {
            when {
                bytes.size >= 88 && String(bytes, 0, 8, Charsets.US_ASCII) == "PXR-USDC" -> if (UsdCrate(bytes, l).load()) l else null
                id.lowercase().endsWith(".mtlx") || String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).let { it.trimStart().startsWith("<?xml") || it.contains("<materialx") } ->
                    if (UsdMtlx.build(bytes, l)) l else null
                else -> if (UsdaParser(String(bytes, Charsets.UTF_8), l).parse()) l else null
            }
        } catch (_: Throwable) {
            if (l.specs.isNotEmpty()) {
                l.index()
                l
            } else null
        }
    }

    private fun stack(l: UsdLayer, depth: Int): List<UsdLayer> = stacks.getOrPut(l) {
        val out = arrayListOf(l)
        if (depth < 16) {
            for (sub in UsdVal.items(l.field("/", "subLayers"))) {
                val a = UsdVal.str(sub) ?: continue
                val sl = layer(resolve(a, l.id)) ?: continue
                for (x in stack(sl, depth + 1)) if (x !in out) out.add(x)
            }
        }
        out
    }

    fun resolve(asset: String, from: String): String {
        var a = asset.trim()
        val br = a.indexOf('[')
        if (br >= 0 && a.endsWith("]")) a = a.substring(br + 1, a.length - 1)
        a = a.replace('\\', '/')
        if (a.startsWith("file:")) a = a.removePrefix("file:").trimStart('/')
        val joined = if (a.startsWith("/") || a.contains(":/")) a else {
            val dir = from.substringBeforeLast('/', "")
            if (dir.isEmpty()) a else "$dir/$a"
        }
        val parts = ArrayList<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1) else parts.add(seg)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun hasPrim(l: UsdLayer, p: String): Boolean {
        val t = l.specs[p]?.type ?: return false
        return t == UsdSpecType.PRIM || t == UsdSpecType.VARIANT
    }

    private fun compose(locals: List<UsdSrc>, ns: String, inherited: Map<String, String>, depth: Int): List<UsdSrc> {
        val out = ArrayList<UsdSrc>()
        val seen = HashSet<Pair<UsdLayer, String>>()
        fun add(list: List<UsdSrc>) {
            for (s in list) if (seen.add(s.layer to s.path)) out.add(s)
        }
        add(locals)
        if (depth > 24) return out
        val sel = LinkedHashMap(inherited)
        for (s in locals) {
            (s.layer.field(s.path, "variantSelection") as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && v is String && !sel.containsKey(k)) sel[k] = v
            }
        }
        for (s in locals) {
            for (it in UsdVal.items(s.layer.field(s.path, "inherits"))) {
                val tp = (it as? UsdPath)?.path ?: continue
                val targets = stackFor(s.layer).filter { hasPrim(it, tp) }.map { UsdSrc(it, tp, s.maps) }
                if (targets.isNotEmpty()) add(compose(targets, ns, sel, depth + 1))
            }
        }
        for (s in locals) {
            val names = LinkedHashSet<String>()
            names.addAll(UsdVal.tokens(s.layer.field(s.path, "variantSetNames")))
            names.addAll(sel.keys)
            for (set in names) {
                val v = sel[set] ?: continue
                val vp = "${s.path}{$set=$v}"
                if (s.layer.specs.containsKey(vp)) add(compose(listOf(UsdSrc(s.layer, vp, s.maps)), ns, sel, depth + 1))
            }
        }
        for (s in locals) {
            for (key in arrayOf("references", "payload")) {
                for (item in UsdVal.items(s.layer.field(s.path, key))) {
                    val ref = item as? UsdRef ?: continue
                    if (ref.asset.isBlank() && ref.prim.isBlank()) continue
                    val targetLayers = if (ref.asset.isBlank()) stackFor(s.layer) else {
                        val tl = layer(resolve(ref.asset, s.layer.id)) ?: continue
                        stack(tl, 0)
                    }
                    if (targetLayers.isEmpty()) continue
                    val tp = ref.prim.ifBlank {
                        UsdVal.str(targetLayers[0].field("/", "defaultPrim"))?.let { d -> if (d.startsWith("/")) d else "/$d" }
                            ?: targetLayers[0].children["/"]?.firstOrNull()?.let { "/$it" }
                            ?: ""
                    }
                    if (tp.isBlank()) continue
                    val maps = listOf(tp to ns) + s.maps
                    val targets = targetLayers.filter { hasPrim(it, tp) }.map { UsdSrc(it, tp, maps) }
                    if (targets.isNotEmpty()) add(compose(targets, ns, sel, depth + 1))
                }
            }
        }
        for (s in locals) {
            for (it in UsdVal.items(s.layer.field(s.path, "specializes"))) {
                val tp = (it as? UsdPath)?.path ?: continue
                val targets = stackFor(s.layer).filter { hasPrim(it, tp) }.map { UsdSrc(it, tp, s.maps) }
                if (targets.isNotEmpty()) add(compose(targets, ns, sel, depth + 1))
            }
        }
        return out
    }

    private fun stackFor(l: UsdLayer): List<UsdLayer> = if (l in rootStack) rootStack else stack(l, 0)

    fun children(p: UsdPrim): List<UsdPrim> = childCache.getOrPut(p.path) {
        val names = LinkedHashSet<String>()
        for (s in p.srcs) s.layer.children[s.path]?.let { names.addAll(it) }
        names.mapNotNull { name ->
            val locals = p.srcs.mapNotNull { s ->
                val cp = UsdPaths.child(s.path, name)
                if (s.layer.specs[cp]?.type == UsdSpecType.PRIM) UsdSrc(s.layer, cp, s.maps) else null
            }
            if (locals.isEmpty()) return@mapNotNull null
            val ns = UsdPaths.child(p.path, name)
            UsdPrim(ns, name, compose(locals, ns, emptyMap(), 0)).also { primCache[ns] = it }
        }
    }

    fun prim(path: String): UsdPrim? {
        val p = UsdPaths.stripVariants(path)
        if (p == "/" || p.isEmpty()) return root
        primCache[p]?.let { return it }
        val (parent, name) = UsdPaths.split(p)
        val par = prim(parent) ?: return null
        return children(par).firstOrNull { it.name == name }
    }

    fun parentOf(p: UsdPrim): UsdPrim? = if (p.path == "/") null else prim(UsdPaths.parent(p.path))

    private fun mapPath(s: UsdSrc, raw: String): String {
        val q = UsdPaths.stripVariants(raw)
        for ((from0, to) in s.maps) {
            val from = UsdPaths.stripVariants(from0)
            if (q == from) return to
            if (q.startsWith(from) && q.length > from.length && (q[from.length] == '/' || q[from.length] == '.')) {
                return if (to == "/") q.substring(from.length).let { if (it.startsWith(".")) "/$it" else it } else to + q.substring(from.length)
            }
        }
        return q
    }

    private fun sampleIndex(times: DoubleArray): Int {
        val t = startTime ?: return 0
        var best = 0
        for (k in times.indices) if (times[k] <= t + 1e-9) best = k
        return best
    }

    fun valueSrc(p: UsdPrim, name: String): Pair<Any?, UsdSrc>? {
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val ts = s.layer.field(spec, "timeSamples")
            if (ts is UsdTimeSamples && ts.times.isNotEmpty()) {
                val v = ts.at(sampleIndex(ts.times))
                return if (v == null || v === UsdBlocked) null else v to s
            }
            if (spec.fields.containsKey("default")) {
                val v = s.layer.field(spec, "default")
                return if (v == null || v === UsdBlocked) null else v to s
            }
        }
        return null
    }

    fun value(p: UsdPrim, name: String): Any? = valueSrc(p, name)?.first

    val endTime: Double? = rootLayer?.let { UsdVal.num(it.field("/", "endTimeCode")) }
    val beginTime: Double? get() = startTime
    val timeCodesPerSecond: Double = rootLayer?.let {
        UsdVal.num(it.field("/", "timeCodesPerSecond"))?.takeIf { v -> v > 0 } ?: UsdVal.num(it.field("/", "framesPerSecond"))?.takeIf { v -> v > 0 }
    } ?: 24.0

    fun sampleCount(p: UsdPrim, name: String): Int {
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val ts = s.layer.field(spec, "timeSamples")
            if (ts is UsdTimeSamples && ts.times.isNotEmpty()) return ts.times.size
            if (spec.fields.containsKey("default")) return 0
        }
        return 0
    }

    fun valueAt(p: UsdPrim, name: String, time: Double?): Any? {
        if (time == null) return value(p, name)
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val ts = s.layer.field(spec, "timeSamples")
            if (ts is UsdTimeSamples && ts.times.isNotEmpty()) {
                val times = ts.times
                fun clean(v: Any?) = if (v === UsdBlocked) null else v
                if (time <= times[0]) return clean(ts.at(0))
                if (time >= times[times.size - 1]) return clean(ts.at(times.size - 1))
                var lo = 0
                var hi = times.size - 1
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (times[mid] <= time) lo = mid else hi = mid
                }
                val v0 = clean(ts.at(lo))
                val v1 = clean(ts.at(hi))
                val span = times[hi] - times[lo]
                val a = if (span > 0) ((time - times[lo]) / span) else 0.0
                return lerp(v0, v1, a)
            }
            if (spec.fields.containsKey("default")) {
                val v = s.layer.field(spec, "default")
                return if (v === UsdBlocked) null else v
            }
        }
        return null
    }

    private fun lerp(v0: Any?, v1: Any?, a: Double): Any? = when {
        v0 is FloatArray && v1 is FloatArray && v0.size == v1.size -> FloatArray(v0.size) { (v0[it] + (v1[it] - v0[it]) * a).toFloat() }
        v0 is DoubleArray && v1 is DoubleArray && v0.size == v1.size -> DoubleArray(v0.size) { v0[it] + (v1[it] - v0[it]) * a }
        v0 is Double && v1 is Double -> v0 + (v1 - v0) * a
        v0 is List<*> && v1 is List<*> && v0.size == v1.size && v0.all { it is Number } && v1.all { it is Number } ->
            List(v0.size) { (v0[it] as Number).toDouble() + ((v1[it] as Number).toDouble() - (v0[it] as Number).toDouble()) * a }
        else -> v0
    }

    fun propMeta(p: UsdPrim, name: String, field: String): Any? {
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val v = s.layer.field(spec, field)
            if (v != null) return v
        }
        return null
    }

    fun primMeta(p: UsdPrim, field: String): Any? {
        for (s in p.srcs) {
            val spec = s.layer.specs[s.path] ?: continue
            val v = s.layer.field(spec, field)
            if (v != null) return v
        }
        return null
    }

    fun connection(p: UsdPrim, name: String): String? {
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val lo = s.layer.field(spec, "connectionPaths") ?: continue
            val first = UsdVal.items(lo).firstOrNull { it is UsdPath } as? UsdPath ?: continue
            return mapPath(s, first.path)
        }
        return null
    }

    fun targets(p: UsdPrim, name: String): List<String>? {
        for (s in p.srcs) {
            val spec = s.layer.specs["${s.path}.$name"] ?: continue
            val lo = s.layer.field(spec, "targetPaths") ?: continue
            return UsdVal.items(lo).mapNotNull { (it as? UsdPath)?.path?.let { t -> mapPath(s, t) } }
        }
        return null
    }

    fun typeName(p: UsdPrim): String {
        for (s in p.srcs) {
            val spec = s.layer.specs[s.path] ?: continue
            val t = UsdVal.str(s.layer.field(spec, "typeName"))
            if (!t.isNullOrBlank()) return t
        }
        return ""
    }

    fun isDefined(p: UsdPrim): Boolean {
        for (s in p.srcs) {
            val spec = s.layer.specs[s.path] ?: continue
            if (spec.type != UsdSpecType.PRIM) continue
            val sp = UsdVal.num(s.layer.field(spec, "specifier"))?.toInt() ?: continue
            if (sp != 1) return sp == 0
        }
        return false
    }

    fun propNames(p: UsdPrim): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in p.srcs) s.layer.props[s.path]?.let { out.addAll(it) }
        return out
    }
}

internal class CleanRoomUsd(private val stage: UsdStage, private val out: GltfOutput) {

    private class Mat(val color: Int, val tex: Int, val uv: String?, val xf: DoubleArray?, val colorPv: String? = null, val normalTex: Int = -1)

    private sealed class In {
        class Value(val v: Any?, val src: UsdSrc) : In()
        class Node(val prim: UsdPrim, val output: String) : In()
        object None : In()
    }

    private class Primvar(val v: FloatArray, val comps: Int, val indices: IntArray?, val interp: String) {
        fun element(face: Int, point: Int, corner: Int): Int {
            val e = when (interp) {
                "constant" -> 0
                "uniform" -> face
                "vertex", "varying" -> point
                else -> corner
            }
            return if (indices != null) indices.getOrElse(e) { -1 } else e
        }

        fun get(e: Int, k: Int): Float {
            val i = e * comps + k
            return if (e >= 0 && k < comps && i < v.size) v[i] else Float.NaN
        }
    }

    private val matCache = HashMap<String, Mat?>()
    private val texCache = HashMap<String, Int>()
    private val tri = FloatArray(9)
    private val uvb = FloatArray(6)
    private val nb = FloatArray(9)
    private var emitted = false
    private val skipTypes = setOf(
        "Material", "Shader", "NodeGraph", "GeomSubset", "Camera", "DistantLight", "DomeLight", "SphereLight", "RectLight",
        "DiskLight", "CylinderLight", "PortalLight", "GeometryLight", "Skeleton", "SkelAnimation", "BlendShape",
        "RenderSettings", "RenderProduct", "RenderVar", "Points", "PhysicsScene", "Volume", "OpenVDBAsset", "Field3DAsset"
    )

    private var base = UsdMath.identity()

    private var frameCodes = DoubleArray(0)
    private val animFps = 30f
    private val boneFrames = ArrayList<FloatArray>()
    private val rigidBones = HashMap<String, Int>()
    private val skelBones = HashMap<String, IntArray?>()
    private val morphFrameWeights = ArrayList<FloatArray>()
    private val morphBases = ArrayList<Float>()
    private val dynCache = HashMap<String, Boolean>()
    private var instancerDepth = 0
    private var morphFloats = 0L
    private val triAnim = TriAnim()
    private val animActive get() = frameCodes.size >= 2

    fun load(): Boolean {
        val root = stage.root ?: return false
        val up = UsdVal.str(stage.rootLayer?.field("/", "upAxis"))
        base = if (up == "Z") UsdMath.rotation(0, -90.0) else UsdMath.identity()
        val st = stage.beginTime
        val en = stage.endTime
        if (st != null && en != null && en > st) {
            val dur = (en - st) / stage.timeCodesPerSecond
            if (dur > 0 && dur < 7200) {
                val frames = minOf(4000, kotlin.math.ceil(dur * animFps).toInt() + 1)
                frameCodes = DoubleArray(frames) { st + minOf(it / animFps.toDouble(), dur) * stage.timeCodesPerSecond }
            }
        }
        for (c in stage.children(root)) {
            if (out.truncated) break
            traverse(c, base, 0)
        }
        finishAnimation()
        return emitted
    }

    private fun finishAnimation() {
        if (!animActive) return
        val bones = boneFrames.size
        val morphs = morphFrameWeights.size
        if (bones == 0 && morphs == 0) return
        val frames = frameCodes.size
        val matrices = FloatArray(frames * bones * 12)
        for (b in 0 until bones) {
            val src = boneFrames[b]
            for (k in 0 until frames) System.arraycopy(src, k * 12, matrices, (k * bones + b) * 12, 12)
        }
        val weights = if (morphs > 0) FloatArray(frames * morphs).also { w ->
            for (t in 0 until morphs) for (k in 0 until frames) w[k * morphs + t] = morphFrameWeights[t][k]
        } else null
        out.animation(AnimClip(animFps, frames, bones, matrices, morphs, weights, if (morphs > 0) morphBases.toFloatArray() else null))
    }

    private fun boneBudgetOk(extra: Int) = (boneFrames.size + extra).toLong() * frameCodes.size * 12 <= 8_000_000L

    private fun pack(m: DoubleArray, dst: FloatArray, at: Int) {
        dst[at] = m[0].toFloat(); dst[at + 1] = m[1].toFloat(); dst[at + 2] = m[2].toFloat()
        dst[at + 3] = m[4].toFloat(); dst[at + 4] = m[5].toFloat(); dst[at + 5] = m[6].toFloat()
        dst[at + 6] = m[8].toFloat(); dst[at + 7] = m[9].toFloat(); dst[at + 8] = m[10].toFloat()
        dst[at + 9] = m[12].toFloat(); dst[at + 10] = m[13].toFloat(); dst[at + 11] = m[14].toFloat()
    }

    private fun isDynamic(prim: UsdPrim): Boolean {
        dynCache[prim.path]?.let { return it }
        var dyn = stage.sampleCount(prim, "xformOpOrder") > 1
        if (!dyn) for (op in UsdVal.tokens(stage.value(prim, "xformOpOrder"))) {
            val name = op.removePrefix("!invert!")
            if (name.startsWith("xformOp:") && stage.sampleCount(prim, name) > 1) {
                dyn = true
                break
            }
        }
        if (!dyn) stage.parentOf(prim)?.let { if (it.path != "/") dyn = isDynamic(it) }
        dynCache[prim.path] = dyn
        return dyn
    }

    private fun rigidBone(prim: UsdPrim, staticWorld: DoubleArray): Int {
        if (!animActive || instancerDepth > 0 || !isDynamic(prim)) return -1
        rigidBones[prim.path]?.let { return it }
        if (!boneBudgetOk(1)) return -1
        val inv = UsdMath.invert(staticWorld) ?: return -1
        val frames = FloatArray(frameCodes.size * 12)
        for (k in frameCodes.indices) pack(UsdMath.mul(worldOf(prim, frameCodes[k]), inv), frames, k * 12)
        boneFrames.add(frames)
        val id = boneFrames.size - 1
        rigidBones[prim.path] = id
        return id
    }

    private fun skelBoneIds(skel: UsdPrim): IntArray? {
        if (!animActive) return null
        if (skelBones.containsKey(skel.path)) return skelBones[skel.path]
        val pose0 = pose(skel)
        val anim = pose0?.anim
        val animated = isDynamic(skel) || (anim != null && listOf("translations", "rotations", "scales").any { stage.sampleCount(anim, it) > 1 })
        val n = pose0?.joints?.size ?: 0
        val result = if (pose0 == null || !animated || n == 0 || !boneBudgetOk(n)) null else {
            val perJoint = Array(n) { FloatArray(frameCodes.size * 12) }
            for (k in frameCodes.indices) {
                val pk = computePose(skel, frameCodes[k]) ?: pose0
                for (j in 0 until n) pack(UsdMath.mul(pk.world, pk.skin.getOrElse(j) { UsdMath.identity() }), perJoint[j], k * 12)
            }
            IntArray(n) { j ->
                boneFrames.add(perJoint[j])
                boneFrames.size - 1
            }
        }
        skelBones[skel.path] = result
        return result
    }

    private fun newMorph(weights: FloatArray, base: Float): Int {
        morphFrameWeights.add(weights)
        morphBases.add(base)
        return morphFrameWeights.size - 1
    }

    private fun traverse(prim: UsdPrim, parent: DoubleArray, depth: Int) {
        if (depth > 128 || out.truncated) return
        if (!stage.isDefined(prim)) return
        if (UsdVal.bool(stage.primMeta(prim, "active")) == false) return
        val type = stage.typeName(prim)
        if (type in skipTypes) return
        if (UsdVal.str(stage.value(prim, "visibility")) == "invisible") return
        val purpose = UsdVal.str(stage.value(prim, "purpose"))
        if (purpose == "proxy" || purpose == "guide") return
        val (local, reset) = xform(prim)
        val m = if (reset) UsdMath.mul(base, local) else UsdMath.mul(parent, local)
        when (type) {
            "Mesh" -> mesh(prim, m)
            "Cube", "Sphere", "Cylinder", "Cylinder_1", "Cone", "Capsule", "Capsule_1", "Plane" -> gprim(prim, type, m)
            "BasisCurves" -> curves(prim, m)
            "PointInstancer" -> {
                instancer(prim, m, depth)
                return
            }
        }
        for (c in stage.children(prim)) {
            if (out.truncated) return
            traverse(c, m, depth + 1)
        }
    }

    private fun xform(prim: UsdPrim, time: Double? = null): Pair<DoubleArray, Boolean> {
        val ops = UsdVal.tokens(stage.valueAt(prim, "xformOpOrder", time))
        var m = UsdMath.identity()
        var reset = false
        for (op0 in ops) {
            if (op0 == "!resetXformStack!") {
                reset = true
                m = UsdMath.identity()
                continue
            }
            val inv = op0.startsWith("!invert!")
            val name = op0.removePrefix("!invert!")
            val parts = name.split(':')
            if (parts.size < 2 || parts[0] != "xformOp") continue
            val v = stage.valueAt(prim, name, time)
            var om = opMatrix(parts[1], v) ?: continue
            if (inv) om = UsdMath.invert(om) ?: continue
            m = UsdMath.mul(m, om)
        }
        return m to reset
    }

    private fun opMatrix(kind: String, v: Any?): DoubleArray? {
        val d = UsdVal.doubles(v) ?: return null
        return when {
            kind == "translate" && d.size >= 3 -> UsdMath.identity().also { it[12] = d[0]; it[13] = d[1]; it[14] = d[2] }
            kind == "scale" && d.size >= 3 -> UsdMath.identity().also { it[0] = d[0]; it[5] = d[1]; it[10] = d[2] }
            kind == "scale" && d.size == 1 -> UsdMath.identity().also { it[0] = d[0]; it[5] = d[0]; it[10] = d[0] }
            kind == "rotateX" && d.isNotEmpty() -> UsdMath.rotation(0, d[0])
            kind == "rotateY" && d.isNotEmpty() -> UsdMath.rotation(1, d[0])
            kind == "rotateZ" && d.isNotEmpty() -> UsdMath.rotation(2, d[0])
            kind.startsWith("rotate") && kind.length == 9 && d.size >= 3 -> {
                var m = UsdMath.identity()
                for (c in kind.substring(6)) {
                    val axis = c - 'X'
                    if (axis !in 0..2) return null
                    m = UsdMath.mul(UsdMath.rotation(axis, d[axis]), m)
                }
                m
            }
            kind == "orient" && d.size >= 4 -> UsdMath.quat(d[0], d[1], d[2], d[3])
            kind == "transform" && d.size >= 16 -> d.copyOf(16)
            else -> null
        }
    }

    private fun primvar(prim: UsdPrim, name: String, defaultInterp: String = "constant"): Primvar? {
        val attr = "primvars:$name"
        val v = UsdVal.floats(stage.value(prim, attr)) ?: return null
        if (v.isEmpty()) return null
        val type = UsdVal.str(stage.propMeta(prim, attr, "typeName")) ?: ""
        val interp = UsdVal.str(stage.propMeta(prim, attr, "interpolation")) ?: defaultInterp
        val idx = UsdVal.ints(stage.value(prim, "$attr:indices"))?.takeIf { it.isNotEmpty() }
        return Primvar(v, comps(type, v.size), idx, interp)
    }

    private fun attrPrimvar(prim: UsdPrim, name: String, defaultInterp: String): Primvar? {
        val v = UsdVal.floats(stage.value(prim, name)) ?: return null
        if (v.isEmpty()) return null
        val type = UsdVal.str(stage.propMeta(prim, name, "typeName")) ?: ""
        val interp = UsdVal.str(stage.propMeta(prim, name, "interpolation")) ?: defaultInterp
        return Primvar(v, comps(type, v.size), null, interp)
    }

    private fun comps(type: String, size: Int): Int {
        val digit = type.firstOrNull { it.isDigit() }?.digitToInt()
        if (digit != null && digit in 2..4 && !type.startsWith("matrix")) return digit
        if (type.startsWith("float") || type.startsWith("double") || type.startsWith("half") || type.startsWith("int")) return 1
        return if (size % 3 == 0) 3 else 1
    }

    private fun linToSrgb(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun argb(r: Double, g: Double, b: Double, a: Double): Int =
        ((a.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (linToSrgb(r) shl 16) or (linToSrgb(g) shl 8) or linToSrgb(b)

    private fun shaderId(p: UsdPrim): String = UsdVal.str(stage.value(p, "info:id")) ?: ""

    private fun isTexture(id: String) = id == "UsdUVTexture" || id.startsWith("ND_image_") || id.startsWith("ND_tiledimage_") ||
        id.startsWith("ND_UsdUVTexture") || id.startsWith("ND_gltf_image")

    private fun input(prim: UsdPrim, name: String, depth: Int): In {
        if (depth > 24) return In.None
        val attr = "inputs:$name"
        val conn = stage.connection(prim, attr)
        if (conn == null) {
            val v = stage.valueSrc(prim, attr) ?: return In.None
            return In.Value(v.first, v.second)
        }
        return follow(conn, depth + 1)
    }

    private fun follow(target: String, depth: Int): In {
        if (depth > 24) return In.None
        val dot = UsdPaths.propSplit(target)
        if (dot < 0) return In.None
        val tp = stage.prim(target.substring(0, dot)) ?: return In.None
        val prop = target.substring(dot + 1)
        if (prop.startsWith("inputs:")) return input(tp, prop.removePrefix("inputs:"), depth + 1)
        val t = stage.typeName(tp)
        if (t == "NodeGraph" || t == "Material") {
            stage.connection(tp, prop)?.let { return follow(it, depth + 1) }
            return stage.valueSrc(tp, prop)?.let { In.Value(it.first, it.second) } ?: In.None
        }
        return In.Node(tp, prop)
    }

    private fun findTexture(p: UsdPrim, depth: Int): UsdPrim? {
        if (isTexture(shaderId(p))) return p
        if (depth > 6) return null
        for (name in stage.propNames(p)) {
            if (!name.startsWith("inputs:")) continue
            val conn = stage.connection(p, name) ?: continue
            val r = follow(conn, 0)
            if (r is In.Node) findTexture(r.prim, depth + 1)?.let { return it }
        }
        return null
    }

    private fun boundMaterial(prim: UsdPrim, inherit: Boolean): UsdPrim? {
        var p: UsdPrim? = prim
        while (p != null && p.path != "/") {
            for (rel in arrayOf("material:binding:preview", "material:binding", "material:binding:full")) {
                val t = stage.targets(p, rel)?.firstOrNull() ?: continue
                return stage.prim(t)
            }
            if (!inherit) return null
            p = stage.parentOf(p)
        }
        return null
    }

    private fun material(mp: UsdPrim?): Mat? {
        mp ?: return null
        if (matCache.containsKey(mp.path)) return matCache[mp.path]
        val m = try {
            resolveMaterial(mp)
        } catch (_: Throwable) {
            null
        }
        matCache[mp.path] = m
        return m
    }

    private fun surfaceShader(mp: UsdPrim): UsdPrim? {
        val names = stage.propNames(mp)
        val candidates = listOf("outputs:surface", "outputs:mtlx:surface") +
            names.filter { it.startsWith("outputs:") && it.endsWith(":surface") && it != "outputs:surface" && it != "outputs:mtlx:surface" }
        for (c in candidates) {
            val conn = stage.connection(mp, c) ?: continue
            var r = follow(conn, 0)
            if (r is In.Node) {
                val id = shaderId(r.prim)
                if (!id.contains("surface", ignoreCase = true) && !id.contains("UsdPreviewSurface") && stage.typeName(r.prim) == "NodeGraph") continue
                return r.prim
            }
        }
        return null
    }

    private fun resolveMaterial(mp: UsdPrim): Mat? {
        val surf = surfaceShader(mp) ?: return null
        val id = shaderId(surf)
        val standard = id.contains("standard_surface")
        val openPbr = id.contains("open_pbr")
        val gltfPbr = id.contains("gltf_pbr")
        val colorName = when {
            standard || openPbr || gltfPbr -> "base_color"
            else -> "diffuseColor"
        }
        val opacityName = when {
            openPbr -> "geometry_opacity"
            gltfPbr -> "alpha"
            else -> "opacity"
        }
        var rgb = when {
            standard -> doubleArrayOf(1.0, 1.0, 1.0)
            openPbr -> doubleArrayOf(0.8, 0.8, 0.8)
            gltfPbr -> doubleArrayOf(1.0, 1.0, 1.0)
            else -> doubleArrayOf(0.18, 0.18, 0.18)
        }
        var alpha = 1.0
        val o = input(surf, opacityName, 0)
        if (o is In.Value) UsdVal.doubles(o.v)?.firstOrNull()?.let { alpha = it }
        val alphaFromTexture = o is In.Node && findTexture(o.prim, 0) != null
        var tex = -1
        var uv: String? = null
        var xf: DoubleArray? = null
        var colorPv: String? = null
        when (val c = input(surf, colorName, 0)) {
            is In.Value -> UsdVal.doubles(c.v)?.takeIf { it.size >= 3 }?.let { rgb = it }
            is In.Node -> if (shaderId(c.prim).let { it.contains("PrimvarReader") || it.startsWith("ND_geompropvalue") }) {
                val id = shaderId(c.prim)
                colorPv = (input(c.prim, if (id.startsWith("ND_geompropvalue")) "geomprop" else "varname", 0) as? In.Value)?.let { UsdVal.str(it.v) }
                (input(c.prim, if (id.startsWith("ND_geompropvalue")) "default" else "fallback", 0) as? In.Value)?.let { UsdVal.doubles(it.v) }?.takeIf { it.size >= 3 }?.let { rgb = it }
            } else findTexture(c.prim, 0)?.let { tp ->
                val t = texture(tp, alphaFromTexture)
                tex = t.first
                uv = t.second
                xf = t.third
                val scale = (input(tp, "scale", 0) as? In.Value)?.let { UsdVal.doubles(it.v) }
                if (tex >= 0) {
                    rgb = if (scale != null && scale.size >= 3) doubleArrayOf(scale[0], scale[1], scale[2]) else doubleArrayOf(1.0, 1.0, 1.0)
                } else {
                    (input(tp, "fallback", 0) as? In.Value)?.let { UsdVal.doubles(it.v) }?.takeIf { it.size >= 3 && (it[0] + it[1] + it[2]) > 0.003 }?.let { rgb = it }
                }
            }
            else -> {}
        }
        if (standard) {
            val base = (input(surf, "base", 0) as? In.Value)?.let { UsdVal.num(it.v) } ?: 0.8
            rgb = doubleArrayOf(rgb[0] * base, rgb[1] * base, rgb[2] * base)
        }
        val color = if (tex >= 0 && rgb[0] == 1.0 && rgb[1] == 1.0 && rgb[2] == 1.0 && alpha >= 1.0) 0 else argb(rgb[0], rgb[1], rgb[2], alpha)
        var normalTex = -1
        val normalIn = input(surf, "normal", 0)
        if (normalIn is In.Node) findTexture(normalIn.prim, 0)?.let { tp ->
            val f = input(tp, "file", 0) as? In.Value
            val asset = f?.let { UsdVal.str(it.v) }
            if (asset != null && asset.isNotBlank()) {
                val path = stage.resolve(asset, f.src.layer.id)
                val scale = (input(tp, "scale", 0) as? In.Value)?.let { UsdVal.doubles(it.v) }
                val flip = scale != null && scale.size >= 2 && scale[1] < 0
                normalTex = out.normalTexture(path, stage.fetch(path), flip, 1f)
                if (uv == null) uv = texture(tp, false).second
            }
        }
        return Mat(color, tex, uv, xf, colorPv, normalTex)
    }

    private fun texture(tp: UsdPrim, alpha: Boolean): Triple<Int, String?, DoubleArray?> {
        val f = input(tp, "file", 0) as? In.Value
        val asset = f?.let { UsdVal.str(it.v) }
        var idx = -1
        if (asset != null && asset.isNotBlank()) {
            val path = stage.resolve(asset, f.src.layer.id)
            val key = "$path|$alpha"
            idx = texCache.getOrPut(key) {
                val bytes = stage.fetch(path)
                if (bytes == null) -1 else out.texture(key, bytes, !alpha)
            }
        }
        var uv: String? = null
        var xf: DoubleArray? = null
        val stIn = if (stage.propNames(tp).contains("inputs:texcoord")) "texcoord" else "st"
        var node = input(tp, stIn, 0) as? In.Node
        var guard = 0
        while (node != null && guard++ < 8) {
            val id = shaderId(node.prim)
            when {
                id.startsWith("UsdTransform2d") || id.startsWith("ND_UsdTransform2d") -> {
                    val s = (input(node.prim, "scale", 0) as? In.Value)?.let { UsdVal.doubles(it.v) } ?: doubleArrayOf(1.0, 1.0)
                    val r = (input(node.prim, "rotation", 0) as? In.Value)?.let { UsdVal.num(it.v) } ?: 0.0
                    val t = (input(node.prim, "translation", 0) as? In.Value)?.let { UsdVal.doubles(it.v) } ?: doubleArrayOf(0.0, 0.0)
                    xf = doubleArrayOf(s.getOrElse(0) { 1.0 }, s.getOrElse(1) { 1.0 }, r, t.getOrElse(0) { 0.0 }, t.getOrElse(1) { 0.0 })
                    node = input(node.prim, "in", 0) as? In.Node
                }
                id.contains("PrimvarReader") || id.startsWith("ND_geompropvalue") -> {
                    val vn = if (id.startsWith("ND_geompropvalue")) "geomprop" else "varname"
                    uv = (input(node.prim, vn, 0) as? In.Value)?.let { UsdVal.str(it.v) }
                    node = null
                }
                id.startsWith("ND_texcoord") -> {
                    uv = "st"
                    node = null
                }
                else -> node = null
            }
        }
        return Triple(idx, uv, xf)
    }

    private fun guessUv(prim: UsdPrim): String? {
        val names = stage.propNames(prim).filter { it.startsWith("primvars:") && !it.endsWith(":indices") }
        for (pref in listOf("st", "st0", "st_0", "UVMap", "uv", "uv0", "map1", "UVW")) if ("primvars:$pref" in names) return pref
        for (n in names) {
            val t = UsdVal.str(stage.propMeta(prim, n, "typeName")) ?: continue
            if (t.startsWith("texCoord2") || t == "float2[]") return n.removePrefix("primvars:")
        }
        return null
    }

    private fun mesh(prim: UsdPrim, meshWorld: DoubleArray) {
        val rawPts = UsdVal.floats(stage.value(prim, "points")) ?: return
        val skin = try {
            skin(prim, rawPts)
        } catch (_: Throwable) {
            null
        }
        val pts = skin?.points ?: rawPts
        val m = skin?.world ?: meshWorld
        val skinMats = skin?.mats
        val counts = UsdVal.ints(stage.value(prim, "faceVertexCounts")) ?: return
        val idx = UsdVal.ints(stage.value(prim, "faceVertexIndices")) ?: return
        val np = pts.size / 3
        if (np == 0) return
        val nFaces = counts.size
        val start = IntArray(nFaces + 1)
        for (f in 0 until nFaces) start[f + 1] = start[f] + maxOf(counts[f], 0)
        if (start[nFaces] > idx.size) return
        val left = UsdVal.str(stage.value(prim, "orientation")) == "leftHanded"
        val detSign = if (UsdMath.det3(m) < 0) -1.0 else 1.0
        val flip = left xor (detSign < 0)
        val holes = UsdVal.ints(stage.value(prim, "holeIndices"))?.toHashSet()
        val scheme = UsdVal.str(stage.value(prim, "subdivisionScheme")) ?: "catmullClark"
        val normals = primvar(prim, "normals", "vertex") ?: attrPrimvar(prim, "normals", "vertex")
        val display = primvar(prim, "displayColor")
        val opacity = primvar(prim, "displayOpacity")
        val baseMat = material(boundMaterial(prim, true))
        val faceMat = arrayOfNulls<Mat>(nFaces)
        var anySubset = false
        for (c in stage.children(prim)) {
            if (stage.typeName(c) != "GeomSubset") continue
            val et = UsdVal.str(stage.value(c, "elementType")) ?: "face"
            if (et != "face") continue
            val mm = material(boundMaterial(c, false)) ?: continue
            val ind = UsdVal.ints(stage.value(c, "indices")) ?: continue
            for (f in ind) if (f in 0 until nFaces) {
                faceMat[f] = mm
                anySubset = true
            }
        }
        val smooth = if (normals == null && scheme != "none") smoothNormals(pts, counts, start, idx, np) else null
        val nm = UsdMath.normalMatrix(m)
        val skelIds = skin?.skel?.let { skelBoneIds(it) }
        val skinAnimated = skelIds != null && skin?.infJ != null && skin.infW != null && skin.bindPts != null
        val rigid = if (skin?.world == null) rigidBone(prim, meshWorld) else -1
        val gbN = skin?.gb?.let { UsdMath.normalMatrix(it) }
        val bindSmooth = if (skinAnimated && normals == null && scheme != "none") smoothNormals(skin!!.bindPts!!, counts, start, idx, np) else null
        val morphSets = ArrayList<MorphSet>()
        if (animActive) {
            val trisEst = counts.sumOf { maxOf(it - 2, 0).toLong() }
            if (skin == null && stage.sampleCount(prim, "points") > 1 && morphFloats + trisEst * frameCodes.size * 9 <= 8_000_000L) {
                val frameSets = ArrayList<FloatArray>()
                val framePts = ArrayList<FloatArray>()
                for (k in frameCodes.indices) {
                    val fp = UsdVal.floats(stage.valueAt(prim, "points", frameCodes[k]))
                    if (fp == null || fp.size != rawPts.size) {
                        frameSets.clear()
                        break
                    }
                    framePts.add(fp)
                    frameSets.add(FloatArray(fp.size) { fp[it] - rawPts[it] })
                }
                if (frameSets.size == frameCodes.size) {
                    morphFloats += trisEst * frameCodes.size * 9
                    val normalsAnimated = stage.sampleCount(prim, "primvars:normals") > 1 || stage.sampleCount(prim, "normals") > 1
                    for (k in frameSets.indices) {
                        val w = FloatArray(frameCodes.size)
                        w[k] = 1f
                        val d = frameSets[k]
                        val code = frameCodes[k]
                        val frameN: ((Int, Int, Int) -> DoubleArray?)? = when {
                            normals != null && normalsAnimated -> {
                                val fv = UsdVal.floats(stage.valueAt(prim, "primvars:normals", code)) ?: UsdVal.floats(stage.valueAt(prim, "normals", code))
                                if (fv == null) null else {
                                    val pv = Primvar(fv, normals.comps, normals.indices, normals.interp);
                                    { f, pi, corner ->
                                        val e = pv.element(f, pi, corner)
                                        val x = pv.get(e, 0)
                                        if (x.isNaN()) null else doubleArrayOf(x.toDouble(), pv.get(e, 1).toDouble(), pv.get(e, 2).toDouble())
                                    }
                                }
                            }
                            normals == null && smooth != null -> {
                                val sn = smoothNormals(framePts[k], counts, start, idx, np)
                                val sg = if (left) -1.0 else 1.0;
                                { _, pi, _ -> doubleArrayOf(sg * sn[pi * 3], sg * sn[pi * 3 + 1], sg * sn[pi * 3 + 2]) }
                            }
                            else -> null
                        }
                        if (d.any { it != 0f }) morphSets.add(MorphSet(newMorph(w, 0f), toWorldDeltas(d, m), null, frameN))
                    }
                }
            }
            val skelPrim = skin?.skel
            val anim = skelPrim?.let { pose(it)?.anim }
            if (anim != null && stage.sampleCount(anim, "blendShapeWeights") > 1) {
                val names = UsdVal.tokens(stage.value(prim, "skel:blendShapes"))
                val targets = stage.targets(prim, "skel:blendShapeTargets") ?: emptyList()
                val an = UsdVal.tokens(stage.value(anim, "blendShapes"))
                for ((k, name) in names.withIndex()) {
                    val wi = an.indexOf(name)
                    if (wi < 0) continue
                    val shape = targets.getOrNull(k)?.let { stage.prim(it) } ?: continue
                    val off = UsdVal.floats(stage.value(shape, "offsets")) ?: continue
                    val pidx = UsdVal.ints(stage.value(shape, "pointIndices"))
                    val dense = FloatArray(rawPts.size)
                    val nOff = UsdVal.floats(stage.value(shape, "normalOffsets"))
                    val denseN = if (nOff != null) FloatArray(rawPts.size) else null
                    for (q in 0 until off.size / 3) {
                        val pp = pidx?.getOrElse(q) { -1 } ?: q
                        if (pp < 0 || pp * 3 + 2 >= dense.size) continue
                        dense[pp * 3] = off[q * 3]
                        dense[pp * 3 + 1] = off[q * 3 + 1]
                        dense[pp * 3 + 2] = off[q * 3 + 2]
                        if (denseN != null && q * 3 + 2 < nOff!!.size) {
                            denseN[pp * 3] = nOff[q * 3]
                            denseN[pp * 3 + 1] = nOff[q * 3 + 1]
                            denseN[pp * 3 + 2] = nOff[q * 3 + 2]
                        }
                    }
                    val weights = FloatArray(frameCodes.size) { f ->
                        UsdVal.floats(stage.valueAt(anim, "blendShapeWeights", frameCodes[f]))?.getOrElse(wi) { 0f } ?: 0f
                    }
                    val baseW = UsdVal.floats(stage.value(anim, "blendShapeWeights"))?.getOrElse(wi) { 0f } ?: 0f
                    val space = if (skinAnimated) skin!!.gb!! else m
                    val nSpace = if (skinAnimated) gbN!! else nm
                    val nWorld = denseN?.let { dn -> FloatArray(dn.size).also { o ->
                        for (i in 0 until dn.size / 3) {
                            val x = dn[i * 3].toDouble(); val y = dn[i * 3 + 1].toDouble(); val z = dn[i * 3 + 2].toDouble()
                            o[i * 3] = (nSpace[0] * x + nSpace[1] * y + nSpace[2] * z).toFloat()
                            o[i * 3 + 1] = (nSpace[3] * x + nSpace[4] * y + nSpace[5] * z).toFloat()
                            o[i * 3 + 2] = (nSpace[6] * x + nSpace[7] * y + nSpace[8] * z).toFloat()
                        }
                    } }
                    morphSets.add(MorphSet(newMorph(weights, baseW), toWorldDeltas(dense, space), nWorld, null))
                }
            }
        }
        val uvCache = HashMap<String, Primvar?>()
        fun uvFor(mat: Mat?): Primvar? {
            val name = mat?.uv ?: guessUv(prim) ?: return null
            return uvCache.getOrPut(name) { primvar(prim, name) ?: if (name != "st") primvar(prim, "st") else null }
        }
        val corners = IntArray(3)
        val local = IntArray(64)
        val sn = DoubleArray(3)
        val pvCache = HashMap<String, Primvar?>()
        for (f in 0 until nFaces) {
            if (out.truncated) return
            val n = counts[f]
            if (n < 3) continue
            if (holes != null && f in holes) continue
            val mat = (if (anySubset) faceMat[f] else null) ?: baseMat
            val uvPv = if (mat != null && (mat.tex >= 0 || mat.normalTex >= 0)) uvFor(mat) else null
            val colorPv = if (mat == null) display else if (mat.tex < 0 && mat.colorPv != null) pvCache.getOrPut(mat.colorPv) { primvar(prim, mat.colorPv) } else null
            val opacityPv = if (mat == null || mat.colorPv == "displayColor") opacity else null
            val tris = polygon(pts, idx, start[f], n, local)
            var t = 0
            while (t + 2 < tris.size) {
                corners[0] = tris[t]
                corners[1] = if (flip) tris[t + 2] else tris[t + 1]
                corners[2] = if (flip) tris[t + 1] else tris[t + 2]
                t += 3
                var ok = true
                var hasN = normals != null || smooth != null
                var c0 = 0
                var c1 = 0
                var c2 = 0
                for (k in 0 until 3) {
                    val corner = start[f] + corners[k]
                    val pi = idx[corner]
                    if (pi !in 0 until np) { ok = false; break }
                    UsdMath.point(m, pts[pi * 3].toDouble(), pts[pi * 3 + 1].toDouble(), pts[pi * 3 + 2].toDouble(), tri, k * 3)
                    if (normals != null) {
                        val e = normals.element(f, pi, corner)
                        val nx = normals.get(e, 0)
                        val ny = normals.get(e, 1)
                        val nz = normals.get(e, 2)
                        if (nx.isNaN() || ny.isNaN() || nz.isNaN()) hasN = false
                        else if (skinMats != null) {
                            skinNormal(skinMats, pi, nx.toDouble(), ny.toDouble(), nz.toDouble(), sn)
                            UsdMath.normal(nm, sn[0], sn[1], sn[2], detSign, nb, k * 3)
                        } else UsdMath.normal(nm, nx.toDouble(), ny.toDouble(), nz.toDouble(), detSign, nb, k * 3)
                    } else if (smooth != null) {
                        UsdMath.normal(nm, smooth[pi * 3].toDouble(), smooth[pi * 3 + 1].toDouble(), smooth[pi * 3 + 2].toDouble(), if (left) -detSign else detSign, nb, k * 3)
                    }
                    if (uvPv != null) {
                        val e = uvPv.element(f, pi, corner)
                        var u = uvPv.get(e, 0).toDouble()
                        var v = uvPv.get(e, 1).toDouble()
                        if (u.isNaN() || v.isNaN()) { u = 0.0; v = 0.0 }
                        mat?.xf?.let { x ->
                            val su = u * x[0]
                            val sv = v * x[1]
                            val a = x[2] * PI / 180.0
                            u = su * cos(a) - sv * sin(a) + x[3]
                            v = su * sin(a) + sv * cos(a) + x[4]
                        }
                        uvb[k * 2] = u.toFloat()
                        uvb[k * 2 + 1] = (1.0 - v).toFloat()
                    }
                    if (colorPv != null && colorPv.interp != "constant") {
                        val e = colorPv.element(f, pi, corner)
                        val a = opacityPv?.let { op -> op.get(op.element(f, pi, corner), 0).toDouble().takeIf { !it.isNaN() } } ?: 1.0
                        val r = colorPv.get(e, 0).toDouble()
                        val col = if (r.isNaN()) 0 else argb(r, colorPv.get(e, 1).toDouble(), colorPv.get(e, 2).toDouble(), a)
                        when (k) {
                            0 -> c0 = col
                            1 -> c1 = col
                            else -> c2 = col
                        }
                    }
                }
                if (!ok) continue
                val color = when {
                    colorPv != null && colorPv.interp == "constant" -> {
                        val a = opacityPv?.get(opacityPv.element(f, 0, 0), 0)?.toDouble()?.takeIf { !it.isNaN() } ?: 1.0
                        val e = colorPv.element(f, 0, 0)
                        val r = colorPv.get(e, 0).toDouble()
                        if (r.isNaN()) mat?.color ?: 0 else argb(r, colorPv.get(e, 1).toDouble(), colorPv.get(e, 2).toDouble(), a)
                    }
                    mat != null -> mat.color
                    else -> 0
                }
                val tex = if (uvPv != null && mat != null) mat.tex else -1
                val ntex = if (uvPv != null && mat != null) mat.normalTex else -1
                triAnim.reset()
                var animated = false
                if (skinAnimated) {
                    val sk = skin!!
                    for (k in 0 until 3) {
                        val pi = idx[start[f] + corners[k]]
                        for (q in 0 until 4) {
                            val j = sk.infJ!![pi * 4 + q]
                            tj[k * 4 + q] = if (j >= 0) skelIds!!.getOrElse(j) { -1 } else -1
                            tw[k * 4 + q] = if (tj[k * 4 + q] >= 0) sk.infW!![pi * 4 + q] else 0f
                        }
                        for (c in 0 until 3) tb[k * 3 + c] = sk.bindPts!![pi * 3 + c]
                        val corner = start[f] + corners[k]
                        if (normals != null && gbN != null) {
                            val e = normals.element(f, pi, corner)
                            UsdMath.normal(gbN, normals.get(e, 0).toDouble(), normals.get(e, 1).toDouble(), normals.get(e, 2).toDouble(), 1.0, tbn, k * 3)
                        } else if (bindSmooth != null) {
                            val sg = if (left) -1f else 1f
                            for (c in 0 until 3) tbn[k * 3 + c] = sg * bindSmooth[pi * 3 + c]
                        }
                    }
                    triAnim.joints = tj
                    triAnim.weights = tw
                    triAnim.bindPos = tb
                    triAnim.bindNrm = if ((normals != null && gbN != null) || bindSmooth != null) tbn else null
                    animated = true
                } else if (rigid >= 0) {
                    triAnim.bone = rigid
                    animated = true
                }
                if (morphSets.isNotEmpty()) {
                    val ids = ArrayList<Int>()
                    val deltas = ArrayList<Float>()
                    val nDeltas = ArrayList<Float>()
                    var anyN = false
                    for (ms in morphSets) {
                        val d = ms.d
                        var nz = false
                        for (k in 0 until 3) {
                            val pi = idx[start[f] + corners[k]]
                            if (d[pi * 3] != 0f || d[pi * 3 + 1] != 0f || d[pi * 3 + 2] != 0f) nz = true
                        }
                        if (!nz) continue
                        ids.add(ms.id)
                        for (k in 0 until 3) {
                            val corner = start[f] + corners[k]
                            val pi = idx[corner]
                            deltas.add(d[pi * 3]); deltas.add(d[pi * 3 + 1]); deltas.add(d[pi * 3 + 2])
                            val nd = ms.nd
                            val fnAt = ms.nAt
                            if (nd != null) {
                                anyN = true
                                nDeltas.add(nd[pi * 3]); nDeltas.add(nd[pi * 3 + 1]); nDeltas.add(nd[pi * 3 + 2])
                            } else if (fnAt != null && hasN) {
                                val fn = fnAt(f, pi, corner)
                                if (fn != null) {
                                    UsdMath.normal(nm, fn[0], fn[1], fn[2], detSign, nTmp, 0)
                                    anyN = true
                                    for (c in 0 until 3) nDeltas.add(nTmp[c] - nb[k * 3 + c])
                                } else repeat(3) { nDeltas.add(0f) }
                            } else repeat(3) { nDeltas.add(0f) }
                        }
                    }
                    if (ids.isNotEmpty()) {
                        triAnim.morphTargets = ids.toIntArray()
                        triAnim.morphDeltas = deltas.toFloatArray()
                        if (anyN && hasN) triAnim.morphNormalDeltas = nDeltas.toFloatArray()
                        animated = true
                    }
                }
                out.triangleFull(
                    tri, color, if (tex >= 0 || ntex >= 0) uvb else null, tex, c0, c1, c2, if (hasN) nb else null,
                    ntex, if (animated) triAnim else null
                )
                emitted = true
            }
        }
    }

    private class MorphSet(val id: Int, val d: FloatArray, val nd: FloatArray?, val nAt: ((Int, Int, Int) -> DoubleArray?)?)

    private val nTmp = FloatArray(3)
    private val tj = IntArray(12)
    private val tw = FloatArray(12)
    private val tb = FloatArray(9)
    private val tbn = FloatArray(9)

    private fun toWorldDeltas(d: FloatArray, m: DoubleArray): FloatArray = FloatArray(d.size).also { o ->
        for (i in 0 until d.size / 3) {
            val x = d[i * 3].toDouble()
            val y = d[i * 3 + 1].toDouble()
            val z = d[i * 3 + 2].toDouble()
            o[i * 3] = (m[0] * x + m[4] * y + m[8] * z).toFloat()
            o[i * 3 + 1] = (m[1] * x + m[5] * y + m[9] * z).toFloat()
            o[i * 3 + 2] = (m[2] * x + m[6] * y + m[10] * z).toFloat()
        }
    }

    private class Skin(
        val points: FloatArray,
        val world: DoubleArray?,
        val mats: DoubleArray?,
        val skel: UsdPrim? = null,
        val infJ: IntArray? = null,
        val infW: FloatArray? = null,
        val bindPts: FloatArray? = null,
        val gb: DoubleArray? = null,
        val blendPts: FloatArray? = null
    )

    private class SkelPose(val joints: List<String>, val skin: Array<DoubleArray>, val world: DoubleArray, val anim: UsdPrim?)

    private val poseCache = HashMap<String, SkelPose?>()

    private fun inheritedTargets(prim: UsdPrim, rel: String): List<String>? {
        var p: UsdPrim? = prim
        while (p != null && p.path != "/") {
            stage.targets(p, rel)?.let { if (it.isNotEmpty()) return it }
            p = stage.parentOf(p)
        }
        return null
    }

    private fun worldOf(p: UsdPrim, time: Double? = null): DoubleArray {
        val chain = ArrayList<UsdPrim>()
        var q: UsdPrim? = p
        while (q != null && q.path != "/") {
            chain.add(q)
            q = stage.parentOf(q)
        }
        var m = base
        for (x in chain.asReversed()) {
            val (l, r) = xform(x, time)
            m = if (r) UsdMath.mul(base, l) else UsdMath.mul(m, l)
        }
        return m
    }

    private fun mat(d: DoubleArray?, i: Int): DoubleArray? =
        if (d != null && i >= 0 && d.size >= (i + 1) * 16) d.copyOfRange(i * 16, i * 16 + 16) else null

    private fun pose(skel: UsdPrim): SkelPose? {
        if (poseCache.containsKey(skel.path)) return poseCache[skel.path]
        val r = computePose(skel)
        poseCache[skel.path] = r
        return r
    }

    private fun computePose(skel: UsdPrim, time: Double? = null): SkelPose? {
        val joints = UsdVal.tokens(stage.value(skel, "joints"))
        val n = joints.size
        if (n == 0) return null
        val bind = UsdVal.doubles(stage.value(skel, "bindTransforms"))
        val rest = UsdVal.doubles(stage.value(skel, "restTransforms"))
        val index = HashMap<String, Int>()
        joints.forEachIndexed { i, j -> index[j] = i }
        val parents = IntArray(n) { i ->
            var s = joints[i]
            var found = -1
            while (s.contains('/')) {
                s = s.substringBeforeLast('/')
                val pi = index[s]
                if (pi != null) {
                    found = pi
                    break
                }
            }
            found
        }
        val local = Array(n) { i ->
            mat(rest, i) ?: run {
                val b = mat(bind, i) ?: UsdMath.identity()
                val pb = mat(bind, parents[i])
                if (pb != null) UsdMath.mul(UsdMath.invert(pb) ?: UsdMath.identity(), b) else b
            }
        }
        val anim = inheritedTargets(skel, "skel:animationSource")?.firstOrNull()?.let { stage.prim(it) }
            ?.takeIf { stage.typeName(it) == "SkelAnimation" }
        if (anim != null) {
            val aj = UsdVal.tokens(stage.value(anim, "joints"))
            val tr = UsdVal.doubles(stage.valueAt(anim, "translations", time))
            val ro = UsdVal.doubles(stage.valueAt(anim, "rotations", time))
            val sc = UsdVal.doubles(stage.valueAt(anim, "scales", time))
            for ((k, name) in aj.withIndex()) {
                val i = index[name] ?: continue
                val l = local[i]
                val sx = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
                val sy = sqrt(l[4] * l[4] + l[5] * l[5] + l[6] * l[6])
                val sz = sqrt(l[8] * l[8] + l[9] * l[9] + l[10] * l[10])
                val t = UsdMath.identity()
                if (tr != null && tr.size >= k * 3 + 3) {
                    t[12] = tr[k * 3]; t[13] = tr[k * 3 + 1]; t[14] = tr[k * 3 + 2]
                } else {
                    t[12] = l[12]; t[13] = l[13]; t[14] = l[14]
                }
                val r = if (ro != null && ro.size >= k * 4 + 4) UsdMath.quat(ro[k * 4], ro[k * 4 + 1], ro[k * 4 + 2], ro[k * 4 + 3]) else {
                    UsdMath.identity().also { q ->
                        for (c in 0 until 3) {
                            val len = when (c) { 0 -> sx; 1 -> sy; else -> sz }.takeIf { it > 0 } ?: 1.0
                            for (row in 0 until 3) q[c * 4 + row] = l[c * 4 + row] / len
                        }
                    }
                }
                val s = UsdMath.identity()
                if (sc != null && sc.size >= k * 3 + 3) {
                    s[0] = sc[k * 3]; s[5] = sc[k * 3 + 1]; s[10] = sc[k * 3 + 2]
                } else {
                    s[0] = sx; s[5] = sy; s[10] = sz
                }
                local[i] = UsdMath.mul(UsdMath.mul(t, r), s)
            }
        }
        val skelSpace = arrayOfNulls<DoubleArray>(n)
        fun ss(i: Int, depth: Int): DoubleArray {
            skelSpace[i]?.let { return it }
            val p = parents[i]
            val r = if (p >= 0 && depth < 512) UsdMath.mul(ss(p, depth + 1), local[i]) else local[i]
            skelSpace[i] = r
            return r
        }
        val skin = Array(n) { i ->
            val b = mat(bind, i)
            if (b != null) UsdMath.mul(ss(i, 0), UsdMath.invert(b) ?: UsdMath.identity()) else UsdMath.identity()
        }
        return SkelPose(joints, skin, worldOf(skel, time), anim)
    }

    private fun blendShapes(prim: UsdPrim, pts: FloatArray, pose: SkelPose): FloatArray {
        val names = UsdVal.tokens(stage.value(prim, "skel:blendShapes"))
        if (names.isEmpty()) return pts
        val targets = stage.targets(prim, "skel:blendShapeTargets") ?: return pts
        val anim = pose.anim ?: return pts
        val an = UsdVal.tokens(stage.value(anim, "blendShapes"))
        val aw = UsdVal.floats(stage.value(anim, "blendShapeWeights")) ?: return pts
        var out: FloatArray? = null
        for ((k, name) in names.withIndex()) {
            val wi = an.indexOf(name)
            if (wi < 0) continue
            val w = aw.getOrElse(wi) { 0f }
            if (w == 0f) continue
            val shape = targets.getOrNull(k)?.let { stage.prim(it) } ?: continue
            val off = UsdVal.floats(stage.value(shape, "offsets")) ?: continue
            val pidx = UsdVal.ints(stage.value(shape, "pointIndices"))
            val o = out ?: pts.copyOf().also { out = it }
            for (q in 0 until off.size / 3) {
                val p = pidx?.getOrElse(q) { -1 } ?: q
                if (p < 0 || p * 3 + 2 >= o.size) continue
                o[p * 3] += w * off[q * 3]
                o[p * 3 + 1] += w * off[q * 3 + 1]
                o[p * 3 + 2] += w * off[q * 3 + 2]
            }
        }
        return out ?: pts
    }

    private fun skin(prim: UsdPrim, pts0: FloatArray): Skin? {
        val skelPrim = inheritedTargets(prim, "skel:skeleton")?.firstOrNull()?.let { stage.prim(it) } ?: return null
        if (stage.typeName(skelPrim) != "Skeleton") return null
        val pose = pose(skelPrim) ?: return null
        val pts = blendShapes(prim, pts0, pose)
        val ji = UsdVal.ints(stage.value(prim, "primvars:skel:jointIndices"))
        val jw = UsdVal.floats(stage.value(prim, "primvars:skel:jointWeights"))
        if (ji == null || jw == null) return if (pts !== pts0) Skin(pts, null, null) else null
        val np = pts.size / 3
        val es = (UsdVal.num(stage.propMeta(prim, "primvars:skel:jointIndices", "elementSize"))?.toInt() ?: 1).coerceAtLeast(1)
        val constant = UsdVal.str(stage.propMeta(prim, "primvars:skel:jointIndices", "interpolation")) == "constant"
        val meshJoints = UsdVal.tokens(stage.value(prim, "skel:joints"))
        val remap = if (meshJoints.isEmpty()) null else IntArray(meshJoints.size) { pose.joints.indexOf(meshJoints[it]) }
        val gb = UsdVal.doubles(stage.value(prim, "primvars:skel:geomBindTransform"))?.takeIf { it.size >= 16 }?.copyOf(16) ?: UsdMath.identity()
        val out = FloatArray(pts.size)
        val mats = DoubleArray(np * 12)
        val acc = DoubleArray(16)
        val infJ = IntArray(np * 4) { -1 }
        val infW = FloatArray(np * 4)
        val bindPts = FloatArray(pts.size)
        for (i in 0 until np) {
            val at0 = if (constant) 0 else i * es
            for (k in 0 until es) {
                val w = jw.getOrElse(at0 + k) { 0f }
                if (w <= 0f) continue
                var j = ji.getOrElse(at0 + k) { -1 }
                if (remap != null) j = remap.getOrElse(j) { -1 }
                if (j !in pose.joints.indices) continue
                var slot = -1
                for (q in 0 until 4) if (infJ[i * 4 + q] < 0) { slot = q; break }
                if (slot < 0) {
                    var minQ = 0
                    for (q in 1 until 4) if (infW[i * 4 + q] < infW[i * 4 + minQ]) minQ = q
                    if (infW[i * 4 + minQ] >= w) continue
                    slot = minQ
                }
                infJ[i * 4 + slot] = j
                infW[i * 4 + slot] = w
            }
            UsdMath.point(gb, pts[i * 3].toDouble(), pts[i * 3 + 1].toDouble(), pts[i * 3 + 2].toDouble(), bindPts, i * 3)
            acc.fill(0.0)
            var wsum = 0.0
            val at = if (constant) 0 else i * es
            for (k in 0 until es) {
                val w = jw.getOrElse(at + k) { 0f }.toDouble()
                if (w == 0.0) continue
                var j = ji.getOrElse(at + k) { -1 }
                if (remap != null) j = remap.getOrElse(j) { -1 }
                val sm = pose.skin.getOrNull(j) ?: continue
                for (q in 0 until 16) acc[q] += w * sm[q]
                wsum += w
            }
            val bm = if (wsum > 0) {
                for (q in 0 until 16) acc[q] /= wsum
                UsdMath.mul(acc, gb)
            } else gb
            val x = pts[i * 3].toDouble()
            val y = pts[i * 3 + 1].toDouble()
            val z = pts[i * 3 + 2].toDouble()
            UsdMath.point(bm, x, y, z, out, i * 3)
            for (c in 0 until 3) for (row in 0 until 3) mats[i * 12 + c * 3 + row] = bm[c * 4 + row]
        }
        return Skin(out, pose.world, mats, skelPrim, infJ, infW, bindPts, gb, pts)
    }

    private fun skinNormal(mats: DoubleArray, pi: Int, x: Double, y: Double, z: Double, o: DoubleArray) {
        val b = pi * 12
        if (pi < 0 || b + 9 > mats.size) {
            o[0] = x; o[1] = y; o[2] = z
            return
        }
        val a = mats[b]; val d = mats[b + 1]; val g = mats[b + 2]
        val bb = mats[b + 3]; val e = mats[b + 4]; val h = mats[b + 5]
        val c = mats[b + 6]; val f = mats[b + 7]; val i = mats[b + 8]
        val det = a * (e * i - f * h) - bb * (d * i - f * g) + c * (d * h - e * g)
        val sg = if (det < 0) -1.0 else 1.0
        o[0] = sg * ((e * i - f * h) * x - (d * i - f * g) * y + (d * h - e * g) * z)
        o[1] = sg * (-(bb * i - c * h) * x + (a * i - c * g) * y - (a * h - bb * g) * z)
        o[2] = sg * ((bb * f - c * e) * x - (a * f - c * d) * y + (a * e - bb * d) * z)
    }

    private fun polygon(pts: FloatArray, idx: IntArray, s: Int, n: Int, scratch: IntArray): IntArray {
        if (n == 3) return intArrayOf(0, 1, 2)
        fun px(k: Int) = idx[s + k].let { if (it * 3 + 2 < pts.size && it >= 0) it else 0 }
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (k in 0 until n) {
            val a = px(k) * 3
            val b = px((k + 1) % n) * 3
            nx += (pts[a + 1] - pts[b + 1]).toDouble() * (pts[a + 2] + pts[b + 2]).toDouble()
            ny += (pts[a + 2] - pts[b + 2]).toDouble() * (pts[a] + pts[b]).toDouble()
            nz += (pts[a] - pts[b]).toDouble() * (pts[a + 1] + pts[b + 1]).toDouble()
        }
        val ax = abs(nx)
        val ay = abs(ny)
        val az = abs(nz)
        val drop = if (ax >= ay && ax >= az) 0 else if (ay >= az) 1 else 2
        val sign = when (drop) { 0 -> nx; 1 -> ny; else -> nz }
        val loop = ArrayList<DoubleArray>(n)
        for (k in 0 until n) {
            val a = px(k) * 3
            val x = pts[a].toDouble()
            val y = pts[a + 1].toDouble()
            val z = pts[a + 2].toDouble()
            val p2 = when (drop) {
                0 -> doubleArrayOf(y, z)
                1 -> doubleArrayOf(z, x)
                else -> doubleArrayOf(x, y)
            }
            if (sign < 0) p2[0] = -p2[0]
            loop.add(p2)
        }
        val res = ArrayList<Int>()
        val ring = ArrayList<Int>((0 until n).toList())
        fun area2(a: Int, b: Int, c: Int): Double {
            val pa = loop[a]
            val pb = loop[b]
            val pc = loop[c]
            return (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0])
        }
        fun inside(p: Int, a: Int, b: Int, c: Int): Boolean {
            val d1 = area2(a, b, p)
            val d2 = area2(b, c, p)
            val d3 = area2(c, a, p)
            return d1 > 0 && d2 > 0 && d3 > 0
        }
        var guard = 0
        while (ring.size > 3 && guard++ < n * n) {
            var clipped = false
            for (i in ring.indices) {
                val a = ring[(i + ring.size - 1) % ring.size]
                val b = ring[i]
                val c = ring[(i + 1) % ring.size]
                if (area2(a, b, c) <= 0) continue
                var empty = true
                for (q in ring) {
                    if (q == a || q == b || q == c) continue
                    if (inside(q, a, b, c)) { empty = false; break }
                }
                if (!empty) continue
                res.add(a); res.add(b); res.add(c)
                ring.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (ring.size == 3) {
            res.add(ring[0]); res.add(ring[1]); res.add(ring[2])
        } else if (ring.size > 3) {
            for (k in 1 until ring.size - 1) {
                res.add(ring[0]); res.add(ring[k]); res.add(ring[k + 1])
            }
        }
        return res.toIntArray()
    }

    private fun smoothNormals(pts: FloatArray, counts: IntArray, start: IntArray, idx: IntArray, np: Int): FloatArray {
        val acc = FloatArray(np * 3)
        for (f in counts.indices) {
            val n = counts[f]
            if (n < 3) continue
            var nx = 0.0
            var ny = 0.0
            var nz = 0.0
            for (k in 0 until n) {
                val a = idx[start[f] + k]
                val b = idx[start[f] + (k + 1) % n]
                if (a !in 0 until np || b !in 0 until np) continue
                nx += (pts[a * 3 + 1] - pts[b * 3 + 1]).toDouble() * (pts[a * 3 + 2] + pts[b * 3 + 2]).toDouble()
                ny += (pts[a * 3 + 2] - pts[b * 3 + 2]).toDouble() * (pts[a * 3] + pts[b * 3]).toDouble()
                nz += (pts[a * 3] - pts[b * 3]).toDouble() * (pts[a * 3 + 1] + pts[b * 3 + 1]).toDouble()
            }
            for (k in 0 until n) {
                val a = idx[start[f] + k]
                if (a !in 0 until np) continue
                acc[a * 3] += nx.toFloat()
                acc[a * 3 + 1] += ny.toFloat()
                acc[a * 3 + 2] += nz.toFloat()
            }
        }
        for (i in 0 until np) {
            val l = sqrt(acc[i * 3] * acc[i * 3] + acc[i * 3 + 1] * acc[i * 3 + 1] + acc[i * 3 + 2] * acc[i * 3 + 2])
            if (l > 0) {
                acc[i * 3] /= l
                acc[i * 3 + 1] /= l
                acc[i * 3 + 2] /= l
            }
        }
        return acc
    }

    private fun gprimColor(prim: UsdPrim): Int {
        material(boundMaterial(prim, true))?.let { return it.color }
        val d = primvar(prim, "displayColor") ?: return 0
        val a = primvar(prim, "displayOpacity")?.get(0, 0)?.toDouble()?.takeIf { !it.isNaN() } ?: 1.0
        return argb(d.get(0, 0).toDouble(), d.get(0, 1).toDouble(), d.get(0, 2).toDouble(), a)
    }

    private fun gprim(prim: UsdPrim, type: String, m: DoubleArray) {
        fun num(name: String, def: Double) = UsdVal.num(stage.value(prim, name)) ?: def
        val axis = UsdVal.str(stage.value(prim, "axis")) ?: "Z"
        val color = gprimColor(prim)
        val nm = UsdMath.normalMatrix(m)
        val sgn = if (UsdMath.det3(m) < 0) -1.0 else 1.0
        fun perm(x: Double, y: Double, z: Double): DoubleArray = when (axis) {
            "X" -> doubleArrayOf(z, x, y)
            "Y" -> doubleArrayOf(y, z, x)
            else -> doubleArrayOf(x, y, z)
        }
        fun emit(p: Array<DoubleArray>, n: Array<DoubleArray>, permute: Boolean) {
            for (k in 0 until 3) {
                val pp = if (permute) perm(p[k][0], p[k][1], p[k][2]) else p[k]
                val nn = if (permute) perm(n[k][0], n[k][1], n[k][2]) else n[k]
                UsdMath.point(m, pp[0], pp[1], pp[2], tri, k * 3)
                UsdMath.normal(nm, nn[0], nn[1], nn[2], sgn, nb, k * 3)
            }
            if (sgn < 0) {
                for (c in 0 until 3) {
                    val t1 = tri[3 + c]; tri[3 + c] = tri[6 + c]; tri[6 + c] = t1
                    val t2 = nb[3 + c]; nb[3 + c] = nb[6 + c]; nb[6 + c] = t2
                }
            }
            out.triangleN(tri, color, null, -1, 0, 0, 0, nb)
            emitted = true
        }
        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, na: DoubleArray, nb2: DoubleArray, nc: DoubleArray, nd: DoubleArray, permute: Boolean) {
            emit(arrayOf(a, b, c), arrayOf(na, nb2, nc), permute)
            emit(arrayOf(a, c, d), arrayOf(na, nc, nd), permute)
        }
        val seg = 48
        when (type) {
            "Cube" -> {
                val h = num("size", 2.0) / 2
                val faces = arrayOf(
                    intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(2, 1), intArrayOf(2, -1)
                )
                for (fc in faces) {
                    val ax = fc[0]
                    val s = fc[1].toDouble()
                    val u = (ax + 1) % 3
                    val v = (ax + 2) % 3
                    fun pt(a: Double, b: Double): DoubleArray {
                        val r = DoubleArray(3)
                        r[ax] = s * h
                        r[u] = a * h
                        r[v] = b * h
                        return r
                    }
                    val n = DoubleArray(3).also { it[ax] = s }
                    if (s > 0) quad(pt(-1.0, -1.0), pt(1.0, -1.0), pt(1.0, 1.0), pt(-1.0, 1.0), n, n, n, n, false)
                    else quad(pt(-1.0, -1.0), pt(-1.0, 1.0), pt(1.0, 1.0), pt(1.0, -1.0), n, n, n, n, false)
                }
            }
            "Sphere" -> {
                val r = num("radius", 1.0)
                val stacks = 24
                fun sp(i: Int, j: Int): DoubleArray {
                    val th = PI * i / stacks
                    val ph = 2 * PI * j / seg
                    return doubleArrayOf(sin(th) * cos(ph), sin(th) * sin(ph), cos(th))
                }
                for (i in 0 until stacks) for (j in 0 until seg) {
                    val a = sp(i, j)
                    val b = sp(i + 1, j)
                    val c = sp(i + 1, j + 1)
                    val d = sp(i, j + 1)
                    fun sc(x: DoubleArray) = doubleArrayOf(x[0] * r, x[1] * r, x[2] * r)
                    if (i != 0) emit(arrayOf(sc(a), sc(b), sc(d)), arrayOf(a, b, d), false)
                    if (i != stacks - 1) emit(arrayOf(sc(b), sc(c), sc(d)), arrayOf(b, c, d), false)
                }
            }
            "Cylinder", "Cylinder_1", "Cone" -> {
                val h = num("height", 2.0)
                val rb = if (type == "Cone") num("radius", 1.0) else if (type == "Cylinder_1") num("radiusBottom", num("radius", 1.0)) else num("radius", 1.0)
                val rt = if (type == "Cone") 0.0 else if (type == "Cylinder_1") num("radiusTop", num("radius", 1.0)) else rb
                val slope = (rb - rt) / h
                for (j in 0 until seg) {
                    val a0 = 2 * PI * j / seg
                    val a1 = 2 * PI * (j + 1) / seg
                    fun n(a: Double): DoubleArray {
                        val l = sqrt(1 + slope * slope)
                        return doubleArrayOf(cos(a) / l, sin(a) / l, slope / l)
                    }
                    val p0 = doubleArrayOf(rb * cos(a0), rb * sin(a0), -h / 2)
                    val p1 = doubleArrayOf(rb * cos(a1), rb * sin(a1), -h / 2)
                    val p2 = doubleArrayOf(rt * cos(a1), rt * sin(a1), h / 2)
                    val p3 = doubleArrayOf(rt * cos(a0), rt * sin(a0), h / 2)
                    if (rt > 0) quad(p0, p1, p2, p3, n(a0), n(a1), n(a1), n(a0), true)
                    else emit(arrayOf(p0, p1, p3), arrayOf(n(a0), n(a1), n((a0 + a1) / 2)), true)
                    val down = doubleArrayOf(0.0, 0.0, -1.0)
                    val up = doubleArrayOf(0.0, 0.0, 1.0)
                    if (rb > 0) emit(arrayOf(doubleArrayOf(0.0, 0.0, -h / 2), p1, p0), arrayOf(down, down, down), true)
                    if (rt > 0) emit(arrayOf(doubleArrayOf(0.0, 0.0, h / 2), p3, p2), arrayOf(up, up, up), true)
                }
            }
            "Capsule", "Capsule_1" -> {
                val r = num("radius", 0.5)
                val h = num("height", 1.0)
                val stacks = 24
                fun sp(i: Int, j: Int): Pair<DoubleArray, DoubleArray> {
                    val th = PI * i / stacks
                    val ph = 2 * PI * j / seg
                    val nrm = doubleArrayOf(sin(th) * cos(ph), sin(th) * sin(ph), cos(th))
                    val off = if (i <= stacks / 2) h / 2 else -h / 2
                    return doubleArrayOf(nrm[0] * r, nrm[1] * r, nrm[2] * r + off) to nrm
                }
                for (i in 0 until stacks) for (j in 0 until seg) {
                    val a = sp(i, j)
                    val b = sp(i + 1, j)
                    val c = sp(i + 1, j + 1)
                    val d = sp(i, j + 1)
                    if (i != 0) emit(arrayOf(a.first, b.first, d.first), arrayOf(a.second, b.second, d.second), true)
                    if (i != stacks - 1) emit(arrayOf(b.first, c.first, d.first), arrayOf(b.second, c.second, d.second), true)
                    if (i == stacks / 2 - 1) {
                        val b2 = doubleArrayOf(b.first[0], b.first[1], b.first[2] - h)
                        val c2 = doubleArrayOf(c.first[0], c.first[1], c.first[2] - h)
                        quad(b.first, b2, c2, c.first, b.second, b.second, c.second, c.second, true)
                    }
                }
            }
            "Plane" -> {
                val w = num("width", 2.0) / 2
                val l = num("length", 2.0) / 2
                val n = doubleArrayOf(0.0, 0.0, 1.0)
                quad(doubleArrayOf(-w, -l, 0.0), doubleArrayOf(w, -l, 0.0), doubleArrayOf(w, l, 0.0), doubleArrayOf(-w, l, 0.0), n, n, n, n, true)
            }
        }
    }

    private fun curves(prim: UsdPrim, m: DoubleArray) {
        val pts = UsdVal.floats(stage.value(prim, "points")) ?: return
        val counts = UsdVal.ints(stage.value(prim, "curveVertexCounts")) ?: return
        val type = UsdVal.str(stage.value(prim, "type")) ?: "cubic"
        val basis = UsdVal.str(stage.value(prim, "basis")) ?: "bezier"
        val periodic = (UsdVal.str(stage.value(prim, "wrap")) ?: "nonperiodic") == "periodic"
        val color = gprimColor(prim).takeIf { it != 0 } ?: 0xFF808080.toInt()
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        val wp = FloatArray(3)
        var off = 0
        fun world(x: Double, y: Double, z: Double, o: DoubleArray) {
            UsdMath.point(m, x, y, z, wp, 0)
            o[0] = wp[0].toDouble(); o[1] = wp[1].toDouble(); o[2] = wp[2].toDouble()
        }
        for (cnt in counts) {
            if (off + cnt > pts.size / 3 || cnt < 2) {
                off += maxOf(cnt, 0)
                continue
            }
            fun p(i: Int, k: Int): Double {
                val ii = if (periodic) ((i % cnt) + cnt) % cnt else i.coerceIn(0, cnt - 1)
                return pts[(off + ii) * 3 + k].toDouble()
            }
            val samples = ArrayList<DoubleArray>()
            if (type == "linear") {
                for (i in 0 until cnt) samples.add(doubleArrayOf(p(i, 0), p(i, 1), p(i, 2)))
                if (periodic) samples.add(doubleArrayOf(p(0, 0), p(0, 1), p(0, 2)))
            } else {
                val steps = 8
                val segs = when (basis) {
                    "bezier" -> if (periodic) cnt / 3 else (cnt - 1) / 3
                    else -> if (periodic) cnt else cnt - 3
                }
                for (sIdx in 0 until maxOf(segs, 0)) {
                    val base = if (basis == "bezier") sIdx * 3 else sIdx
                    for (st in 0..steps) {
                        if (st == 0 && sIdx > 0) continue
                        val t = st.toDouble() / steps
                        val w = when (basis) {
                            "bezier" -> doubleArrayOf((1 - t).pow(3), 3 * t * (1 - t).pow(2), 3 * t * t * (1 - t), t * t * t)
                            "catmullRom" -> doubleArrayOf(
                                0.5 * (-t * t * t + 2 * t * t - t), 0.5 * (3 * t * t * t - 5 * t * t + 2),
                                0.5 * (-3 * t * t * t + 4 * t * t + t), 0.5 * (t * t * t - t * t)
                            )
                            else -> doubleArrayOf(
                                (1 - t).pow(3) / 6, (3 * t * t * t - 6 * t * t + 4) / 6, (-3 * t * t * t + 3 * t * t + 3 * t + 1) / 6, t * t * t / 6
                            )
                        }
                        samples.add(DoubleArray(3) { k -> (0 until 4).sumOf { j -> w[j] * p(base + j, k) } })
                    }
                }
                if (samples.isEmpty()) for (i in 0 until cnt) samples.add(doubleArrayOf(p(i, 0), p(i, 1), p(i, 2)))
            }
            for (i in 0 until samples.size - 1) {
                world(samples[i][0], samples[i][1], samples[i][2], a)
                world(samples[i + 1][0], samples[i + 1][1], samples[i + 1][2], b)
                out.line(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), color)
                emitted = true
            }
            off += cnt
        }
    }

    private fun instancer(prim: UsdPrim, m: DoubleArray, depth: Int) {
        val protos = (stage.targets(prim, "prototypes") ?: return).map { stage.prim(it) }
        val protoIdx = UsdVal.ints(stage.value(prim, "protoIndices")) ?: return
        val pos = UsdVal.doubles(stage.value(prim, "positions")) ?: return
        val orient = UsdVal.doubles(stage.value(prim, "orientations")) ?: UsdVal.doubles(stage.value(prim, "orientationsf"))
        val scales = UsdVal.doubles(stage.value(prim, "scales"))
        val ids = UsdVal.ints(stage.value(prim, "ids"))
        val invisible = UsdVal.ints(stage.value(prim, "invisibleIds"))?.toHashSet()
        for (i in protoIdx.indices) {
            if (out.truncated) return
            if (i * 3 + 2 >= pos.size) break
            val id = ids?.getOrNull(i) ?: i
            if (invisible != null && id in invisible) continue
            val proto = protos.getOrNull(protoIdx[i]) ?: continue
            var im = UsdMath.identity().also { it[12] = pos[i * 3]; it[13] = pos[i * 3 + 1]; it[14] = pos[i * 3 + 2] }
            if (orient != null && i * 4 + 3 < orient.size) im = UsdMath.mul(im, UsdMath.quat(orient[i * 4], orient[i * 4 + 1], orient[i * 4 + 2], orient[i * 4 + 3]))
            if (scales != null && i * 3 + 2 < scales.size) im = UsdMath.mul(im, UsdMath.identity().also { it[0] = scales[i * 3]; it[5] = scales[i * 3 + 1]; it[10] = scales[i * 3 + 2] })
            instancerDepth++
            try {
                traverse(proto, UsdMath.mul(m, im), depth + 1)
            } finally {
                instancerDepth--
            }
        }
    }
}

object UsdMath {
    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun rotation(axis: Int, deg: Double): DoubleArray {
        val a = deg * PI / 180.0
        val c = cos(a)
        val s = sin(a)
        val m = identity()
        when (axis) {
            0 -> { m[5] = c; m[9] = -s; m[6] = s; m[10] = c }
            1 -> { m[0] = c; m[8] = s; m[2] = -s; m[10] = c }
            else -> { m[0] = c; m[4] = -s; m[1] = s; m[5] = c }
        }
        return m
    }

    fun quat(x0: Double, y0: Double, z0: Double, w0: Double): DoubleArray {
        val l = sqrt(x0 * x0 + y0 * y0 + z0 * z0 + w0 * w0)
        if (l == 0.0) return identity()
        val x = x0 / l
        val y = y0 / l
        val z = z0 / l
        val w = w0 / l
        val m = identity()
        m[0] = 1 - 2 * (y * y + z * z); m[4] = 2 * (x * y - z * w); m[8] = 2 * (x * z + y * w)
        m[1] = 2 * (x * y + z * w); m[5] = 1 - 2 * (x * x + z * z); m[9] = 2 * (y * z - x * w)
        m[2] = 2 * (x * z - y * w); m[6] = 2 * (y * z + x * w); m[10] = 1 - 2 * (x * x + y * y)
        return m
    }

    fun det3(m: DoubleArray): Double =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun invert(m: DoubleArray): DoubleArray? {
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0.0 || !det.isFinite()) return null
        for (k in 0 until 16) inv[k] /= det
        return inv
    }

    fun normalMatrix(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        return doubleArrayOf(
            e * i - f * h, -(d * i - f * g), d * h - e * g,
            -(b * i - c * h), a * i - c * g, -(a * h - b * g),
            b * f - c * e, -(a * f - c * d), a * e - b * d
        )
    }

    fun point(m: DoubleArray, x: Double, y: Double, z: Double, o: FloatArray, at: Int) {
        o[at] = (m[0] * x + m[4] * y + m[8] * z + m[12]).toFloat()
        o[at + 1] = (m[1] * x + m[5] * y + m[9] * z + m[13]).toFloat()
        o[at + 2] = (m[2] * x + m[6] * y + m[10] * z + m[14]).toFloat()
    }

    fun normal(c: DoubleArray, x: Double, y: Double, z: Double, sign: Double, o: FloatArray, at: Int) {
        val nx = c[0] * x + c[1] * y + c[2] * z
        val ny = c[3] * x + c[4] * y + c[5] * z
        val nz = c[6] * x + c[7] * y + c[8] * z
        val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
        o[at] = (sign * nx / l).toFloat()
        o[at + 1] = (sign * ny / l).toFloat()
        o[at + 2] = (sign * nz / l).toFloat()
    }
}
