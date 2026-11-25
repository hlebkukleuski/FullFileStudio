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

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.w3c.dom.Element

internal class ColladaParser(private val root: Element, private val out: GltfOutput) {

    companion object {
        fun parse(bytes: ByteArray): Element? {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {}
            try {
                f.setFeature("http://xml.org/sax/features/external-general-entities", false)
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Throwable) {}
            f.isExpandEntityReferences = false
            f.isNamespaceAware = false
            val doc = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))
            return doc.documentElement?.takeIf { name(it) == "COLLADA" }
        }

        fun name(e: Element): String = (e.localName ?: e.nodeName).substringAfter(':')
    }

    private val ids = HashMap<String, Element>()
    private val upMatrix: FloatArray
    private val anim = TriAnim()

    init {
        fun walk(e: Element) {
            val id = e.getAttribute("id")
            if (id.isNotEmpty()) ids.putIfAbsent(id, e)
            for (c in kids(e)) walk(c)
        }
        walk(root)
        val up = child(child(root, "asset"), "up_axis")?.textContent?.trim()
        upMatrix = when (up) {
            "Z_UP" -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)
            "X_UP" -> floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> GltfParser.IDENTITY
        }
    }

    private fun kids(e: Element?): List<Element> {
        if (e == null) return emptyList()
        val list = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { list.add(it) }
        return list
    }

    private fun kids(e: Element?, n: String) = kids(e).filter { name(it) == n }
    private fun child(e: Element?, n: String) = kids(e).firstOrNull { name(it) == n }
    private fun ref(url: String): Element? = ids[url.removePrefix("#")]

    private fun floats(e: Element?): FloatArray {
        if (e == null) return FloatArray(0)
        val s = e.textContent ?: return FloatArray(0)
        val out = FloatArray(maxOf(16, s.length / 2))
        var n = 0
        var i = 0
        val len = s.length
        while (i < len) {
            while (i < len && s[i].isWhitespace()) i++
            if (i >= len) break
            val st = i
            while (i < len && !s[i].isWhitespace()) i++
            val tok = s.substring(st, i)
            val v = tok.toFloatOrNull() ?: (if (tok.indexOf(',') >= 0 && tok.indexOf('.') < 0) tok.replace(',', '.').toFloatOrNull() else null) ?: continue
            if (n < out.size) out[n++] = v
        }
        return out.copyOf(n)
    }

    private fun ints(e: Element?): IntArray {
        val f = e?.textContent ?: return IntArray(0)
        val out = ArrayList<Int>()
        var i = 0
        val len = f.length
        var cur = 0
        var neg = false
        var inNum = false
        while (i <= len) {
            val c = if (i < len) f[i] else ' '
            if (c == '-') {
                neg = true
            } else if (c in '0'..'9') {
                cur = cur * 10 + (c - '0')
                inNum = true
            } else {
                if (inNum) out.add(if (neg) -cur else cur)
                cur = 0
                neg = false
                inNum = false
            }
            i++
        }
        return out.toIntArray()
    }

    private fun names(e: Element?): List<String> = e?.textContent?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    private class Source(val data: FloatArray, val stride: Int, val names: List<String>)

    private val sourceCache = HashMap<Element, Source>()

    private fun source(e: Element?): Source? {
        e ?: return null
        sourceCache[e]?.let { return it }
        val acc = child(child(e, "technique_common"), "accessor")
        val stride = acc?.getAttribute("stride")?.toIntOrNull() ?: 1
        val fa = child(e, "float_array")
        val na = child(e, "Name_array") ?: child(e, "IDREF_array")
        val s = Source(floats(fa), stride.coerceAtLeast(1), names(na))
        sourceCache[e] = s
        return s
    }

    private fun rowMajor(m: FloatArray, at: Int = 0): FloatArray = FloatArray(16) { m.getOrElse(at + (it % 4) * 4 + it / 4) { if (it % 5 == 0) 1f else 0f } }

    private fun rotation(x: Float, y: Float, z: Float, deg: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        if (l == 0f) return GltfParser.IDENTITY
        val a = Math.toRadians(deg.toDouble())
        val s = sin(a / 2).toFloat() / l
        return GltfParser.trs(null, floatArrayOf(x * s, y * s, z * s, cos(a / 2).toFloat()), null)
    }

    private class Channel(val node: String, val sid: String, val member: String, val times: FloatArray, val values: FloatArray, val stride: Int,
                          val interp: List<String>, val inTan: FloatArray?, val outTan: FloatArray?)

    private class Clip(val name: String, val start: Float, val end: Float, val channels: List<Channel>)

    private var clips: List<Clip> = emptyList()
    private var frameClip = IntArray(0)
    private var frameTimes = FloatArray(0)
    private var clipStarts = IntArray(0)
    private var clipLens = IntArray(0)
    private val fps = 30f
    private val animActive get() = frameTimes.size >= 2
    private val boneFrames = ArrayList<FloatArray>()
    private val nodeBones = HashMap<String, Int>()
    private val jointBones = HashMap<String, Int>()
    private val animatedNodes = HashSet<String>()

    private fun readChannels(animEl: Element, outList: ArrayList<Channel>) {
        for (sub in kids(animEl, "animation")) readChannels(sub, outList)
        val samplers = kids(animEl, "sampler")
        for (ch in kids(animEl, "channel")) {
            val sampler = ref(ch.getAttribute("source")) ?: samplers.firstOrNull { it.getAttribute("id") == ch.getAttribute("source").removePrefix("#") } ?: continue
            val target = ch.getAttribute("target")
            val slash = target.indexOf('/')
            if (slash <= 0) continue
            val node = target.substring(0, slash)
            var rest = target.substring(slash + 1)
            var member = ""
            val dot = rest.indexOf('.')
            val paren = rest.indexOf('(')
            if (dot >= 0) {
                member = rest.substring(dot + 1)
                rest = rest.substring(0, dot)
            } else if (paren >= 0) {
                member = rest.substring(paren)
                rest = rest.substring(0, paren)
            }
            fun input(sem: String) = kids(sampler, "input").firstOrNull { it.getAttribute("semantic") == sem }?.let { source(ref(it.getAttribute("source"))) }
            val inp = input("INPUT") ?: continue
            val outp = input("OUTPUT") ?: continue
            val interp = input("INTERPOLATION")?.names ?: emptyList()
            outList.add(Channel(node, rest, member, inp.data, outp.data, outp.stride, interp, input("IN_TANGENT")?.data, input("OUT_TANGENT")?.data))
        }
    }

    private fun setupAnimation() {
        val lib = kids(root, "library_animations")
        if (lib.isEmpty()) return
        val all = ArrayList<Channel>()
        val byAnim = HashMap<String, ArrayList<Channel>>()
        for (l in lib) for (a in kids(l, "animation")) {
            val list = ArrayList<Channel>()
            readChannels(a, list)
            all.addAll(list)
            val id = a.getAttribute("id")
            if (id.isNotEmpty()) byAnim[id] = list
        }
        if (all.isEmpty()) return
        val defs = ArrayList<Clip>()
        for (cl in kids(root, "library_animation_clips")) for (c in kids(cl, "animation_clip")) {
            val chans = kids(c, "instance_animation").flatMap { byAnim[it.getAttribute("url").removePrefix("#")] ?: emptyList() }
            if (chans.isEmpty()) continue
            val start = c.getAttribute("start").toFloatOrNull() ?: chans.minOf { it.times.firstOrNull() ?: 0f }
            val end = c.getAttribute("end").toFloatOrNull() ?: chans.maxOf { it.times.lastOrNull() ?: 0f }
            defs.add(Clip(c.getAttribute("name").ifBlank { c.getAttribute("id").ifBlank { "Clip ${defs.size + 1}" } }, start, maxOf(end, start), chans))
        }
        if (defs.isEmpty()) {
            val start = all.minOf { it.times.firstOrNull() ?: 0f }
            val end = all.maxOf { it.times.lastOrNull() ?: 0f }
            defs.add(Clip("", start, maxOf(start, end), all))
        }
        clips = defs
        for (d in defs) for (c in d.channels) animatedNodes.add(c.node)
        val lens = IntArray(defs.size) { minOf(4000, kotlin.math.ceil((defs[it].end - defs[it].start) * fps).toInt() + 1).coerceAtLeast(2) }
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
            frameTimes[starts[i] + k] = defs[i].start + minOf(k / fps, defs[i].end - defs[i].start)
        }
    }

    private fun bezierValue(t: Float, t0: Float, v0: Float, c0t: Float, c0v: Float, c1t: Float, c1v: Float, t1: Float, v1: Float): Float {
        var lo = 0f
        var hi = 1f
        var s = 0.5f
        repeat(30) {
            s = (lo + hi) / 2
            val u = 1 - s
            val x = u * u * u * t0 + 3 * u * u * s * c0t + 3 * u * s * s * c1t + s * s * s * t1
            if (x < t) lo = s else hi = s
        }
        val u = 1 - s
        return u * u * u * v0 + 3 * u * u * s * c0v + 3 * u * s * s * c1v + s * s * s * v1
    }

    private fun evalChannel(c: Channel, t: Float): FloatArray {
        val n = c.times.size
        val st = c.stride
        val outv = FloatArray(st)
        if (n == 0) return outv
        if (t <= c.times[0] || n == 1) {
            for (i in 0 until st) outv[i] = c.values.getOrElse(i) { 0f }
            return outv
        }
        if (t >= c.times[n - 1]) {
            for (i in 0 until st) outv[i] = c.values.getOrElse((n - 1) * st + i) { 0f }
            return outv
        }
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (c.times[mid] <= t) lo = mid else hi = mid
        }
        val t0 = c.times[lo]
        val t1 = c.times[hi]
        val a = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        val mode = c.interp.getOrElse(lo) { c.interp.firstOrNull() ?: "LINEAR" }
        for (i in 0 until st) {
            val v0 = c.values.getOrElse(lo * st + i) { 0f }
            val v1 = c.values.getOrElse(hi * st + i) { v0 }
            outv[i] = when (mode) {
                "STEP" -> v0
                "BEZIER" -> {
                    val ot = c.outTan
                    val it = c.inTan
                    if (ot != null && it != null && ot.size >= (lo + 1) * st * 2 && it.size >= (hi + 1) * st * 2) {
                        bezierValue(t, t0, v0, ot[(lo * st + i) * 2], ot[(lo * st + i) * 2 + 1], it[(hi * st + i) * 2], it[(hi * st + i) * 2 + 1], t1, v1)
                    } else if (ot != null && it != null && ot.size >= (lo + 1) * st && it.size >= (hi + 1) * st) {
                        val dt = t1 - t0
                        bezierValue(t, t0, v0, t0 + dt / 3, ot[lo * st + i], t1 - dt / 3, it[hi * st + i], t1, v1)
                    } else v0 + (v1 - v0) * a
                }
                "HERMITE" -> {
                    val ot = c.outTan
                    val it = c.inTan
                    if (ot != null && it != null && ot.size >= (lo + 1) * st && it.size >= (hi + 1) * st) {
                        val a2 = a * a
                        val a3 = a2 * a
                        (2 * a3 - 3 * a2 + 1) * v0 + (a3 - 2 * a2 + a) * ot[lo * st + i] + (-2 * a3 + 3 * a2) * v1 + (a3 - a2) * it[hi * st + i]
                    } else v0 + (v1 - v0) * a
                }
                else -> v0 + (v1 - v0) * a
            }
        }
        return outv
    }

    private fun localMatrix(node: Element, frame: Int): FloatArray {
        val nodeId = node.getAttribute("id")
        val chans = if (frame >= 0 && nodeId in animatedNodes) clips[frameClip[frame]].channels.filter { it.node == nodeId } else emptyList()
        val t = if (frame >= 0) frameTimes[frame] else 0f
        var m = GltfParser.IDENTITY
        for (x in kids(node)) {
            val sid = x.getAttribute("sid")
            val tag = name(x)
            if (tag !in setOf("matrix", "translate", "rotate", "scale", "lookat", "skew")) continue
            var v = floats(x)
            if (sid.isNotEmpty()) for (c in chans) {
                if (c.sid != sid) continue
                val value = evalChannel(c, t)
                when {
                    c.member.isEmpty() -> if (value.size >= v.size) v = value.copyOf(v.size) else if (value.size == 1 && tag == "rotate" && v.size >= 4) v = v.copyOf().also { it[3] = value[0] }
                    c.member.equals("ANGLE", true) && v.size >= 4 -> v = v.copyOf().also { it[3] = value[0] }
                    c.member in setOf("X", "Y", "Z") -> {
                        val idx = "XYZ".indexOf(c.member)
                        if (idx < v.size) v = v.copyOf().also { it[idx] = value[0] }
                    }
                    c.member.startsWith("(") -> {
                        val nums = Regex("\\((\\d+)\\)").findAll(c.member).map { it.groupValues[1].toInt() }.toList()
                        val idx = if (nums.size == 2) nums[0] * 4 + nums[1] else nums.firstOrNull() ?: 0
                        if (idx < v.size) v = v.copyOf().also { it[idx] = value[0] }
                    }
                }
            }
            val xm = when (tag) {
                "matrix" -> if (v.size >= 16) rowMajor(v) else GltfParser.IDENTITY
                "translate" -> GltfParser.trs(floatArrayOf(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f }), null, null)
                "rotate" -> if (v.size >= 4) rotation(v[0], v[1], v[2], v[3]) else GltfParser.IDENTITY
                "scale" -> GltfParser.trs(null, null, floatArrayOf(v.getOrElse(0) { 1f }, v.getOrElse(1) { 1f }, v.getOrElse(2) { 1f }))
                "lookat" -> if (v.size >= 9) lookAt(v) else GltfParser.IDENTITY
                else -> GltfParser.IDENTITY
            }
            m = GltfParser.mul(m, xm)
        }
        return m
    }

    private fun lookAt(v: FloatArray): FloatArray {
        val ex = v[0]; val ey = v[1]; val ez = v[2]
        var fx = v[3] - ex; var fy = v[4] - ey; var fz = v[5] - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 0f } ?: return GltfParser.IDENTITY
        fx /= fl; fy /= fl; fz /= fl
        var sx = fy * v[8] - fz * v[7]; var sy = fz * v[6] - fx * v[8]; var sz = fx * v[7] - fy * v[6]
        val sl = sqrt(sx * sx + sy * sy + sz * sz).takeIf { it > 0f } ?: return GltfParser.IDENTITY
        sx /= sl; sy /= sl; sz /= sl
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
        return floatArrayOf(sx, sy, sz, 0f, ux, uy, uz, 0f, -fx, -fy, -fz, 0f, ex, ey, ez, 1f)
    }

    private val parents = HashMap<Element, Element?>()
    private val staticWorld = HashMap<Element, FloatArray>()
    private val frameWorld = HashMap<Element, Array<FloatArray?>>()
    private val dynamicCache = HashMap<Element, Boolean>()

    private fun isDynamic(node: Element): Boolean = dynamicCache.getOrPut(node) {
        node.getAttribute("id") in animatedNodes || (parents[node]?.let { isDynamic(it) } ?: false)
    }

    private fun worldAt(node: Element, frame: Int): FloatArray {
        if (frame < 0 || !isDynamic(node)) return staticWorld[node] ?: GltfParser.IDENTITY
        val arr = frameWorld.getOrPut(node) { arrayOfNulls(frameTimes.size) }
        arr[frame]?.let { return it }
        val p = parents[node]
        val pw = if (p != null) worldAt(p, frame) else upMatrix
        val w = GltfParser.mul(pw, localMatrix(node, frame))
        arr[frame] = w
        return w
    }

    private fun pack(m: FloatArray, dst: FloatArray, at: Int) {
        dst[at] = m[0]; dst[at + 1] = m[1]; dst[at + 2] = m[2]
        dst[at + 3] = m[4]; dst[at + 4] = m[5]; dst[at + 5] = m[6]
        dst[at + 6] = m[8]; dst[at + 7] = m[9]; dst[at + 8] = m[10]
        dst[at + 9] = m[12]; dst[at + 10] = m[13]; dst[at + 11] = m[14]
    }

    private fun invert(m: FloatArray): FloatArray {
        val d = UsdMath.invert(DoubleArray(16) { m[it].toDouble() }) ?: return GltfParser.IDENTITY
        return FloatArray(16) { d[it].toFloat() }
    }

    private fun budgetOk() = (boneFrames.size + 1).toLong() * frameTimes.size * 12 <= 8_000_000L

    private fun rigidBone(node: Element, key: String): Int {
        if (!animActive || !isDynamic(node)) return -1
        nodeBones[key]?.let { return it }
        if (!budgetOk()) return -1
        val inv = invert(staticWorld[node] ?: GltfParser.IDENTITY)
        val frames = FloatArray(frameTimes.size * 12)
        for (k in frameTimes.indices) pack(GltfParser.mul(worldAt(node, k), inv), frames, k * 12)
        boneFrames.add(frames)
        nodeBones[key] = boneFrames.size - 1
        return boneFrames.size - 1
    }

    private fun jointBone(joint: Element, invBind: FloatArray, key: String): Int {
        if (!animActive || !isDynamic(joint)) return -1
        jointBones[key]?.let { return it }
        if (!budgetOk()) return -1
        val frames = FloatArray(frameTimes.size * 12)
        for (k in frameTimes.indices) pack(GltfParser.mul(worldAt(joint, k), invBind), frames, k * 12)
        boneFrames.add(frames)
        jointBones[key] = boneFrames.size - 1
        return boneFrames.size - 1
    }

    private class Mat(val color: Int, val texture: Int, val texcoord: String, val normalTex: Int, val normalTexcoord: String)

    private val matCache = HashMap<Element, Mat>()

    private fun imagePath(img: Element?): String? {
        img ?: return null
        val init = child(img, "init_from") ?: return null
        val refEl = child(init, "ref")
        val raw = (refEl?.textContent ?: init.textContent)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var p = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { raw }
        p = p.removePrefix("file:///").removePrefix("file://").removePrefix("file:").replace('\\', '/')
        if (p.length > 2 && p[1] == ':' && p[0].isLetter()) p = p.substring(2)
        return p.removePrefix("./")
    }

    private fun textureImage(effect: Element, texAttr: String): Element? {
        ids[texAttr]?.takeIf { name(it) == "image" }?.let { return it }
        val profile = child(effect, "profile_COMMON") ?: return null
        val params = kids(profile, "newparam") + kids(child(profile, "technique"), "newparam")
        fun param(sid: String) = params.firstOrNull { it.getAttribute("sid") == sid }
        val sampler = param(texAttr)?.let { child(it, "sampler2D") } ?: return null
        child(sampler, "instance_image")?.let { return ref(it.getAttribute("url")) }
        val srcSid = child(sampler, "source")?.textContent?.trim() ?: return null
        val surface = param(srcSid)?.let { child(it, "surface") } ?: return ids[srcSid]?.takeIf { name(it) == "image" }
        val imgId = child(surface, "init_from")?.textContent?.trim() ?: return null
        return ids[imgId]
    }

    private fun material(matEl: Element?): Mat {
        if (matEl == null) return Mat(0, -1, "", -1, "")
        matCache[matEl]?.let { return it }
        val effect = ref(child(matEl, "instance_effect")?.getAttribute("url") ?: "")
        var color = 0
        var texture = -1
        var texcoord = ""
        var normalTex = -1
        var normalTexcoord = ""
        if (effect != null) {
            val profile = child(effect, "profile_COMMON")
            val technique = child(profile, "technique")
            val shading = kids(technique).firstOrNull { name(it) in setOf("lambert", "phong", "blinn", "constant") }
            var alpha = 1f
            val transparent = child(shading, "transparent")
            val transparency = child(shading, "transparency")?.let { child(it, "float") }?.textContent?.trim()?.toFloatOrNull()
            if (transparency != null) {
                val opaqueMode = transparent?.getAttribute("opaque").orEmpty()
                val tc = child(transparent, "color")?.let { floats(it) }
                alpha = when {
                    opaqueMode == "RGB_ZERO" -> 1f - transparency * (tc?.take(3)?.average()?.toFloat() ?: 1f)
                    tc != null && tc.size >= 4 -> tc[3] * transparency
                    else -> transparency
                }.coerceIn(0f, 1f)
                if (alpha <= 0.001f) alpha = 1f
            }
            val diffuse = child(shading, "diffuse") ?: child(shading, "emission")
            val tex = child(diffuse, "texture")
            if (tex != null) {
                val img = textureImage(effect, tex.getAttribute("texture"))
                val path = imagePath(img)
                if (path != null) texture = out.texture("dae:$path:${alpha >= 0.999f}", out.resource(path), alpha >= 0.999f)
                texcoord = tex.getAttribute("texcoord")
            }
            val dc = child(diffuse, "color")?.let { floats(it) }
            val a255 = (alpha * 255f).toInt().coerceIn(0, 255)
            color = if (texture >= 0) (a255 shl 24) or 0xFFFFFF else if (dc != null && dc.size >= 3) {
                (a255 shl 24) or ((dc[0].coerceIn(0f, 1f) * 255).toInt() shl 16) or ((dc[1].coerceIn(0f, 1f) * 255).toInt() shl 8) or (dc[2].coerceIn(0f, 1f) * 255).toInt()
            } else if (a255 < 255) (a255 shl 24) or 0xCCCCCC else 0
            val bumpCandidates = ArrayList<Element>()
            fun collect(e: Element?) {
                if (e == null) return
                for (x in kids(e)) {
                    if (name(x) == "bump" || name(x) == "normal" || name(x) == "normalmap") bumpCandidates.add(x)
                    if (name(x) == "extra" || name(x) == "technique" || name(x) == "profile_COMMON") collect(x)
                }
            }
            collect(technique)
            collect(effect)
            collect(profile)
            for (b in bumpCandidates) {
                val t = child(b, "texture") ?: continue
                val path = imagePath(textureImage(effect, t.getAttribute("texture"))) ?: continue
                val bytes = out.resource(path) ?: continue
                val explicit = b.getAttribute("bumptype").equals("NORMALMAP", true) || name(b) != "bump"
                if (!explicit && !NormalMapHeuristic.looksLikeNormalMap(bytes)) continue
                normalTex = out.normalTexture("dae:$path", bytes, false, 1f)
                normalTexcoord = t.getAttribute("texcoord")
                if (normalTex >= 0) break
            }
        }
        val m = Mat(color, texture, texcoord, normalTex, normalTexcoord)
        matCache[matEl] = m
        return m
    }

    private class Input(val semantic: String, val source: Element?, val offset: Int, val set: Int)

    private class SkinData(val weights: Array<IntArray>, val values: Array<FloatArray>, val bones: IntArray, val jointMats: Array<FloatArray?>, val bindShape: FloatArray)

    fun load(): Boolean {
        try {
            setupAnimation()
        } catch (_: Throwable) {
            frameTimes = FloatArray(0)
        }
        val sceneUrl = child(child(root, "scene"), "instance_visual_scene")?.getAttribute("url")
        val scene = (sceneUrl?.let { ref(it) }) ?: kids(child(root, "library_visual_scenes"), "visual_scene").firstOrNull() ?: return false
        fun register(node: Element, parent: Element?, depth: Int) {
            if (depth > 128) return
            parents[node] = parent
            val pw = if (parent != null) staticWorld[parent] ?: upMatrix else upMatrix
            staticWorld[node] = GltfParser.mul(pw, localMatrix(node, -1))
            for (c in kids(node, "node")) register(c, node, depth + 1)
        }
        for (n in kids(scene, "node")) register(n, null, 0)
        val visited = HashSet<String>()
        for (n in kids(scene, "node")) emitNode(n, null, 0, visited, "")
        finishAnimation()
        return true
    }

    private fun emitNode(node: Element, viaInstance: FloatArray?, depth: Int, guard: HashSet<String>, path: String) {
        if (depth > 128 || out.truncated) return
        val world = if (viaInstance != null) GltfParser.mul(viaInstance, localMatrix(node, -1)) else staticWorld[node] ?: GltfParser.IDENTITY
        val key = path + "/" + node.getAttribute("id").ifEmpty { System.identityHashCode(node).toString() }
        for (x in kids(node)) {
            if (out.truncated) return
            when (name(x)) {
                "instance_geometry" -> {
                    val geom = ref(x.getAttribute("url")) ?: continue
                    emitGeometry(geom, world, bindings(x), if (viaInstance == null) node else null, key, null)
                }
                "instance_controller" -> {
                    val ctrl = ref(x.getAttribute("url")) ?: continue
                    emitController(ctrl, x, world, bindings(x), node, key)
                }
                "instance_node" -> {
                    val target = ref(x.getAttribute("url")) ?: continue
                    val id = target.getAttribute("id")
                    if (!guard.add(id)) continue
                    emitNode(target, world, depth + 1, guard, key)
                    guard.remove(id)
                }
                "node" -> emitNode(x, if (viaInstance != null) world else null, depth + 1, guard, key)
            }
        }
    }

    private fun bindings(inst: Element): Map<String, Pair<Element?, Map<String, Int>>> {
        val res = HashMap<String, Pair<Element?, Map<String, Int>>>()
        val tc = child(child(inst, "bind_material"), "technique_common") ?: return res
        for (im in kids(tc, "instance_material")) {
            val sets = HashMap<String, Int>()
            for (b in kids(im, "bind_vertex_input")) sets[b.getAttribute("semantic")] = b.getAttribute("input_set").toIntOrNull() ?: 0
            res[im.getAttribute("symbol")] = ref(im.getAttribute("target")) to sets
        }
        return res
    }

    private fun emitController(ctrl: Element, inst: Element, world: FloatArray, binds: Map<String, Pair<Element?, Map<String, Int>>>, node: Element, key: String) {
        val skin = child(ctrl, "skin")
        if (skin == null) {
            val morph = child(ctrl, "morph") ?: return
            val geom = ref(morph.getAttribute("source")) ?: return
            if (name(geom) == "geometry") emitGeometry(geom, world, binds, node, key, null)
            return
        }
        var geom = ref(skin.getAttribute("source")) ?: return
        if (name(geom) == "controller") {
            val mo = child(geom, "morph")
            geom = ref(mo?.getAttribute("source") ?: "") ?: return
        }
        val bindShape = child(skin, "bind_shape_matrix")?.let { floats(it) }?.takeIf { it.size >= 16 }?.let { rowMajor(it) } ?: GltfParser.IDENTITY
        val jointsEl = child(skin, "joints") ?: return
        val jointSrc = kids(jointsEl, "input").firstOrNull { it.getAttribute("semantic") == "JOINT" }?.let { source(ref(it.getAttribute("source"))) } ?: return
        val ibmSrc = kids(jointsEl, "input").firstOrNull { it.getAttribute("semantic") == "INV_BIND_MATRIX" }?.let { source(ref(it.getAttribute("source"))) }
        val skeletons = kids(inst, "skeleton").mapNotNull { ref(it.textContent.trim()) }
        val searchRoots = skeletons.ifEmpty { parents.keys.filter { parents[it] == null } }
        fun findJoint(nameOrSid: String): Element? {
            ids[nameOrSid]?.takeIf { name(it) == "node" }?.let { if (skeletons.isEmpty()) return it }
            fun dfs(e: Element, depth: Int): Element? {
                if (depth > 256) return null
                if (e.getAttribute("sid") == nameOrSid) return e
                for (c in kids(e, "node")) dfs(c, depth + 1)?.let { return it }
                return null
            }
            for (r in searchRoots) dfs(r, 0)?.let { return it }
            for (r in searchRoots) {
                fun byIdOrName(e: Element, depth: Int): Element? {
                    if (depth > 256) return null
                    if (e.getAttribute("id") == nameOrSid || e.getAttribute("name") == nameOrSid) return e
                    for (c in kids(e, "node")) byIdOrName(c, depth + 1)?.let { return it }
                    return null
                }
                byIdOrName(r, 0)?.let { return it }
            }
            return ids[nameOrSid]?.takeIf { name(it) == "node" }
        }
        val jointNodes = jointSrc.names.map { findJoint(it) }
        val invBinds = Array(jointNodes.size) { j -> if (ibmSrc != null && ibmSrc.data.size >= (j + 1) * 16) rowMajor(ibmSrc.data, j * 16) else GltfParser.IDENTITY }
        val jointMats = Array(jointNodes.size) { j -> jointNodes[j]?.let { GltfParser.mul(GltfParser.mul(staticWorld[it] ?: GltfParser.IDENTITY, invBinds[j]), bindShape) } }
        val bones = IntArray(jointNodes.size) { j -> jointNodes[j]?.let { jointBone(it, invBinds[j], "${ctrl.getAttribute("id")}#$j") } ?: -1 }
        val vw = child(skin, "vertex_weights") ?: return
        val vInputs = kids(vw, "input")
        val jOff = vInputs.firstOrNull { it.getAttribute("semantic") == "JOINT" }?.getAttribute("offset")?.toIntOrNull() ?: 0
        val wInput = vInputs.firstOrNull { it.getAttribute("semantic") == "WEIGHT" }
        val wOff = wInput?.getAttribute("offset")?.toIntOrNull() ?: 1
        val wSrc = wInput?.let { source(ref(it.getAttribute("source"))) }
        val stride = (vInputs.maxOfOrNull { it.getAttribute("offset").toIntOrNull() ?: 0 } ?: 1) + 1
        val vcount = ints(child(vw, "vcount"))
        val v = ints(child(vw, "v"))
        val count = vcount.size
        val wj = Array(count) { IntArray(0) }
        val ww = Array(count) { FloatArray(0) }
        var p = 0
        for (i in 0 until count) {
            val n = vcount[i]
            val js = ArrayList<Int>()
            val ws = ArrayList<Float>()
            for (k in 0 until n) {
                val base = (p + k) * stride
                if (base + maxOf(jOff, wOff) >= v.size) break
                val ji = v[base + jOff]
                val w = wSrc?.data?.getOrElse(v[base + wOff]) { 0f } ?: 0f
                if (w > 0f) {
                    js.add(ji)
                    ws.add(w)
                }
            }
            p += n
            val order = ws.indices.sortedByDescending { ws[it] }.take(4)
            wj[i] = IntArray(order.size) { js[order[it]] }
            ww[i] = FloatArray(order.size) { ws[order[it]] }
        }
        emitGeometry(geom, world, binds, null, key, SkinData(wj, ww, bones, jointMats, bindShape))
    }

    private fun emitGeometry(geom: Element, world: FloatArray, binds: Map<String, Pair<Element?, Map<String, Int>>>, rigidNode: Element?, key: String, skin: SkinData?) {
        val mesh = child(geom, "mesh") ?: return
        val vertices = child(mesh, "vertices")
        val vInputs = kids(vertices, "input")
        val posSrc = vInputs.firstOrNull { it.getAttribute("semantic") == "POSITION" }?.let { source(ref(it.getAttribute("source"))) } ?: return
        val vNormal = vInputs.firstOrNull { it.getAttribute("semantic") == "NORMAL" }?.let { source(ref(it.getAttribute("source"))) }
        val vTex = vInputs.filter { it.getAttribute("semantic") == "TEXCOORD" }.mapNotNull { source(ref(it.getAttribute("source"))) }
        val vColor = vInputs.firstOrNull { it.getAttribute("semantic") == "COLOR" }?.let { source(ref(it.getAttribute("source"))) }
        val nPos = posSrc.data.size / posSrc.stride
        val nm = normalMatrix(world)
        val rigid = if (skin == null && rigidNode != null) rigidBone(rigidNode, key) else -1
        val skinMats = if (skin != null) Array(nPos) { i ->
            val js = skin.weights.getOrNull(i)
            val ws = skin.values.getOrNull(i)
            if (js == null || ws == null || js.isEmpty()) null else {
                val acc = FloatArray(16)
                var sw = 0f
                for (k in js.indices) {
                    val m = skin.jointMats.getOrNull(js[k]) ?: continue
                    for (q in 0 until 16) acc[q] += ws[k] * m[q]
                    sw += ws[k]
                }
                if (sw > 0f) FloatArray(16) { acc[it] / sw } else null
            }
        } else null
        fun posAt(i: Int, o: FloatArray, at: Int) {
            val x = posSrc.data.getOrElse(i * posSrc.stride) { 0f }
            val y = posSrc.data.getOrElse(i * posSrc.stride + 1) { 0f }
            val z = posSrc.data.getOrElse(i * posSrc.stride + 2) { 0f }
            val m = skinMats?.getOrNull(i) ?: world
            o[at] = m[0] * x + m[4] * y + m[8] * z + m[12]
            o[at + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            o[at + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        }
        for (prim in kids(mesh)) {
            if (out.truncated) return
            val tag = name(prim)
            if (tag !in setOf("triangles", "polylist", "polygons", "trifans", "tristrips", "lines", "linestrips")) continue
            val inputs = kids(prim, "input").map {
                Input(it.getAttribute("semantic"), ref(it.getAttribute("source")), it.getAttribute("offset").toIntOrNull() ?: 0, it.getAttribute("set").toIntOrNull() ?: 0)
            }
            if (inputs.isEmpty()) continue
            val stride = inputs.maxOf { it.offset } + 1
            val vertexOff = inputs.firstOrNull { it.semantic == "VERTEX" }?.offset ?: continue
            val normalIn = inputs.firstOrNull { it.semantic == "NORMAL" }
            val normalSrc = normalIn?.let { source(it.source) }
            val texInputs = inputs.filter { it.semantic == "TEXCOORD" }
            val colorIn = inputs.firstOrNull { it.semantic == "COLOR" }
            val colorSrc = colorIn?.let { source(it.source) }
            val (matEl, sets) = binds[prim.getAttribute("material")] ?: binds.values.singleOrNull() ?: (null to emptyMap())
            val mat = material(matEl)
            fun uvInput(symbol: String): Pair<Input?, Source?> {
                val set = sets[symbol]
                val ti = (if (set != null) texInputs.firstOrNull { it.set == set } else null) ?: texInputs.firstOrNull()
                return ti to ti?.let { source(it.source) }
            }
            val (uvIn, uvSrc) = if (mat.texture >= 0) uvInput(mat.texcoord) else if (mat.normalTex >= 0) uvInput(mat.normalTexcoord) else (null to null)
            val vertexUv = if (uvIn == null && (mat.texture >= 0 || mat.normalTex >= 0)) vTex.firstOrNull() else null
            val polygons = ArrayList<IntArray>()
            val ps = kids(prim, "p").map { ints(it) } + kids(prim, "ph").mapNotNull { ph -> child(ph, "p")?.let { ints(it) } }
            when (tag) {
                "triangles" -> ps.firstOrNull()?.let { p -> var k = 0; while ((k + 3) * stride <= p.size) { polygons.add(intArrayOf(k, k + 1, k + 2)); k += 3 } }
                "polylist" -> {
                    val vc = ints(child(prim, "vcount"))
                    var k = 0
                    for (n in vc) {
                        if ((k + n) * stride > (ps.firstOrNull()?.size ?: 0)) break
                        polygons.add(IntArray(n) { k + it })
                        k += n
                    }
                }
                "polygons", "trifans", "tristrips", "lines", "linestrips" -> {}
            }
            val flat: IntArray
            if (tag == "triangles" || tag == "polylist") {
                flat = ps.firstOrNull() ?: continue
                emitPolygons(polygons, flat, stride, vertexOff, normalIn, normalSrc, uvIn, uvSrc, colorIn, colorSrc, vertexUv, vNormal, vColor, posSrc, mat, skin, rigid, nm, ::posAt)
            } else {
                for (p in ps) {
                    val n = p.size / stride
                    val polys = ArrayList<IntArray>()
                    when (tag) {
                        "polygons" -> polys.add(IntArray(n) { it })
                        "trifans" -> for (i in 1 until n - 1) polys.add(intArrayOf(0, i, i + 1))
                        "tristrips" -> for (i in 0 until n - 2) polys.add(if (i % 2 == 0) intArrayOf(i, i + 1, i + 2) else intArrayOf(i + 1, i, i + 2))
                        "lines" -> {
                            var i = 0
                            while (i + 1 < n) { emitLine(p, stride, vertexOff, i, i + 1, mat, ::posAt); i += 2 }
                        }
                        "linestrips" -> for (i in 0 until n - 1) emitLine(p, stride, vertexOff, i, i + 1, mat, ::posAt)
                    }
                    if (polys.isNotEmpty()) emitPolygons(polys, p, stride, vertexOff, normalIn, normalSrc, uvIn, uvSrc, colorIn, colorSrc, vertexUv, vNormal, vColor, posSrc, mat, skin, rigid, nm, ::posAt)
                }
            }
        }
    }

    private val lineBuf = FloatArray(6)

    private fun emitLine(p: IntArray, stride: Int, vOff: Int, a: Int, b: Int, mat: Mat, posAt: (Int, FloatArray, Int) -> Unit) {
        val ia = p.getOrElse(a * stride + vOff) { -1 }
        val ib = p.getOrElse(b * stride + vOff) { -1 }
        if (ia < 0 || ib < 0) return
        posAt(ia, lineBuf, 0)
        posAt(ib, lineBuf, 3)
        out.line(lineBuf[0], lineBuf[1], lineBuf[2], lineBuf[3], lineBuf[4], lineBuf[5], mat.color)
    }

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

    private val tri = FloatArray(9)
    private val uvTri = FloatArray(6)
    private val nTri = FloatArray(9)
    private val jTri = IntArray(12)
    private val wTri = FloatArray(12)
    private val bTri = FloatArray(9)
    private val bnTri = FloatArray(9)

    private fun emitPolygons(
        polys: List<IntArray>, p: IntArray, stride: Int, vOff: Int,
        normalIn: Input?, normalSrc: Source?, uvIn: Input?, uvSrc: Source?, colorIn: Input?, colorSrc: Source?,
        vertexUv: Source?, vNormal: Source?, vColor: Source?, posSrc: Source,
        mat: Mat, skin: SkinData?, rigid: Int, nm: FloatArray, posAt: (Int, FloatArray, Int) -> Unit
    ) {
        val corner = FloatArray(3)
        for (poly in polys) {
            if (out.truncated) return
            if (poly.size < 3) continue
            val tris = triangulate(poly, p, stride, vOff, posSrc)
            var t = 0
            while (t + 2 < tris.size) {
                val cs = intArrayOf(tris[t], tris[t + 1], tris[t + 2])
                t += 3
                var ok = true
                var hasUv = uvSrc != null || vertexUv != null
                var hasN = normalSrc != null || vNormal != null
                val cols = IntArray(3)
                var allSkinned = skin != null
                for ((k, c) in cs.withIndex()) {
                    val vi = p.getOrElse(c * stride + vOff) { -1 }
                    if (vi < 0 || vi * posSrc.stride + 2 >= posSrc.data.size) { ok = false; break }
                    posAt(vi, tri, k * 3)
                    val nSrc = normalSrc ?: vNormal
                    if (nSrc != null) {
                        val ni = if (normalSrc != null) p.getOrElse(c * stride + normalIn!!.offset) { -1 } else vi
                        if (ni < 0 || ni * nSrc.stride + 2 >= nSrc.data.size) hasN = false else {
                            val nx = nSrc.data[ni * nSrc.stride]
                            val ny = nSrc.data[ni * nSrc.stride + 1]
                            val nz = nSrc.data[ni * nSrc.stride + 2]
                            val sm = skin?.let { posSkinMatrix(it, vi) }
                            val m = if (sm != null) normalMatrix(sm) else nm
                            val rx = m[0] * nx + m[1] * ny + m[2] * nz
                            val ry = m[3] * nx + m[4] * ny + m[5] * nz
                            val rz = m[6] * nx + m[7] * ny + m[8] * nz
                            val l = sqrt(rx * rx + ry * ry + rz * rz).takeIf { it > 0f } ?: 1f
                            nTri[k * 3] = rx / l; nTri[k * 3 + 1] = ry / l; nTri[k * 3 + 2] = rz / l
                            if (skin != null) {
                                val bn = normalMatrix(skin.bindShape)
                                bnTri[k * 3] = bn[0] * nx + bn[1] * ny + bn[2] * nz
                                bnTri[k * 3 + 1] = bn[3] * nx + bn[4] * ny + bn[5] * nz
                                bnTri[k * 3 + 2] = bn[6] * nx + bn[7] * ny + bn[8] * nz
                            }
                        }
                    }
                    val uSrc = uvSrc ?: vertexUv
                    if (uSrc != null) {
                        val ui = if (uvSrc != null) p.getOrElse(c * stride + uvIn!!.offset) { -1 } else vi
                        if (ui < 0 || ui * uSrc.stride + 1 >= uSrc.data.size) hasUv = false else {
                            uvTri[k * 2] = uSrc.data[ui * uSrc.stride]
                            uvTri[k * 2 + 1] = 1f - uSrc.data[ui * uSrc.stride + 1]
                        }
                    }
                    val cSrc = colorSrc ?: vColor
                    if (cSrc != null) {
                        val ci = if (colorSrc != null) p.getOrElse(c * stride + colorIn!!.offset) { -1 } else vi
                        if (ci >= 0 && ci * cSrc.stride + 2 < cSrc.data.size) {
                            val a = if (cSrc.stride >= 4) cSrc.data[ci * cSrc.stride + 3] else 1f
                            cols[k] = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or ((cSrc.data[ci * cSrc.stride].coerceIn(0f, 1f) * 255).toInt() shl 16) or
                                ((cSrc.data[ci * cSrc.stride + 1].coerceIn(0f, 1f) * 255).toInt() shl 8) or (cSrc.data[ci * cSrc.stride + 2].coerceIn(0f, 1f) * 255).toInt()
                        }
                    }
                    if (skin != null) {
                        val js = skin.weights.getOrNull(vi)
                        val ws = skin.values.getOrNull(vi)
                        if (js == null || ws == null || js.isEmpty()) allSkinned = false else {
                            for (q in 0 until 4) {
                                val j = js.getOrElse(q) { -1 }
                                val bone = if (j >= 0) skin.bones.getOrElse(j) { -1 } else -1
                                jTri[k * 4 + q] = bone
                                wTri[k * 4 + q] = if (bone >= 0) ws.getOrElse(q) { 0f } else 0f
                            }
                            val x = posSrc.data[vi * posSrc.stride]
                            val y = posSrc.data[vi * posSrc.stride + 1]
                            val z = posSrc.data[vi * posSrc.stride + 2]
                            val bs = skin.bindShape
                            bTri[k * 3] = bs[0] * x + bs[4] * y + bs[8] * z + bs[12]
                            bTri[k * 3 + 1] = bs[1] * x + bs[5] * y + bs[9] * z + bs[13]
                            bTri[k * 3 + 2] = bs[2] * x + bs[6] * y + bs[10] * z + bs[14]
                        }
                    }
                }
                if (!ok) continue
                anim.reset()
                var animated = false
                if (allSkinned && skin != null && skin.bones.any { it >= 0 }) {
                    anim.joints = jTri
                    anim.weights = wTri
                    anim.bindPos = bTri
                    anim.bindNrm = if (hasN) bnTri else null
                    animated = true
                } else if (rigid >= 0) {
                    anim.bone = rigid
                    animated = true
                }
                val tex = if (hasUv) mat.texture else -1
                val ntex = if (hasUv) mat.normalTex else -1
                out.triangleFull(
                    tri, mat.color, if (hasUv && (tex >= 0 || ntex >= 0)) uvTri else null, tex, cols[0], cols[1], cols[2],
                    if (hasN) nTri else null, ntex, if (animated) anim else null
                )
            }
        }
    }

    private fun posSkinMatrix(skin: SkinData, vi: Int): FloatArray? {
        val js = skin.weights.getOrNull(vi) ?: return null
        val ws = skin.values.getOrNull(vi) ?: return null
        if (js.isEmpty()) return null
        val acc = FloatArray(16)
        var sw = 0f
        for (k in js.indices) {
            val m = skin.jointMats.getOrNull(js[k]) ?: continue
            for (q in 0 until 16) acc[q] += ws[k] * m[q]
            sw += ws[k]
        }
        return if (sw > 0f) FloatArray(16) { acc[it] / sw } else null
    }

    private fun triangulate(poly: IntArray, p: IntArray, stride: Int, vOff: Int, pos: Source): IntArray {
        val n = poly.size
        if (n == 3) return poly
        fun vx(k: Int, c: Int): Float {
            val vi = p.getOrElse(poly[k] * stride + vOff) { 0 }
            return pos.data.getOrElse(vi * pos.stride + c) { 0f }
        }
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (k in 0 until n) {
            val j = (k + 1) % n
            nx += (vx(k, 1) - vx(j, 1)) * (vx(k, 2) + vx(j, 2))
            ny += (vx(k, 2) - vx(j, 2)) * (vx(k, 0) + vx(j, 0))
            nz += (vx(k, 0) - vx(j, 0)) * (vx(k, 1) + vx(j, 1))
        }
        val drop = if (abs(nx) >= abs(ny) && abs(nx) >= abs(nz)) 0 else if (abs(ny) >= abs(nz)) 1 else 2
        val sign = when (drop) { 0 -> nx; 1 -> ny; else -> nz }
        val pts = Array(n) { k ->
            val a = when (drop) { 0 -> vx(k, 1); 1 -> vx(k, 2); else -> vx(k, 0) }
            val b = when (drop) { 0 -> vx(k, 2); 1 -> vx(k, 0); else -> vx(k, 1) }
            floatArrayOf(if (sign < 0) -a else a, b)
        }
        fun area2(a: Int, b: Int, c: Int) = (pts[b][0] - pts[a][0]) * (pts[c][1] - pts[a][1]) - (pts[b][1] - pts[a][1]) * (pts[c][0] - pts[a][0])
        val ring = ArrayList<Int>((0 until n).toList())
        val res = ArrayList<Int>()
        var guard = 0
        while (ring.size > 3 && guard++ < n * n) {
            var clipped = false
            for (i in ring.indices) {
                val a = ring[(i + ring.size - 1) % ring.size]
                val b = ring[i]
                val c = ring[(i + 1) % ring.size]
                if (area2(a, b, c) <= 0f) continue
                var empty = true
                for (q in ring) {
                    if (q == a || q == b || q == c) continue
                    if (area2(a, b, q) > 0f && area2(b, c, q) > 0f && area2(c, a, q) > 0f) { empty = false; break }
                }
                if (!empty) continue
                res.add(poly[a]); res.add(poly[b]); res.add(poly[c])
                ring.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break
        }
        for (k in 1 until ring.size - 1) {
            res.add(poly[ring[0]]); res.add(poly[ring[k]]); res.add(poly[ring[k + 1]])
        }
        return res.toIntArray()
    }

    private fun finishAnimation() {
        if (!animActive) return
        val bones = boneFrames.size
        if (bones == 0) return
        val frames = frameTimes.size
        val matrices = FloatArray(frames * bones * 12)
        for (b in 0 until bones) {
            val src = boneFrames[b]
            for (k in 0 until frames) System.arraycopy(src, k * 12, matrices, (k * bones + b) * 12, 12)
        }
        out.animation(AnimClip(fps, frames, bones, matrices, 0, null, null, clips.map { it.name }, clipStarts, clipLens))
    }
}
