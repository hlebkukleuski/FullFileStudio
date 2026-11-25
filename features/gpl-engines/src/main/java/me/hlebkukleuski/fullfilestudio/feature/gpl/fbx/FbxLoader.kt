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
 * FBX binary and ASCII node hierarchy, geometry, and animation parsing informed by
 * Open Asset Import Library (Assimp, https://github.com/assimp/assimp,
 * Copyright (c) 2006-2026 assimp team, BSD-3-Clause), ufbx (https://github.com/ufbx/ufbx,
 * Copyright (c) 2020-2026 Samuli Raivio, MIT License), and Blender FBX I/O
 * (https://www.blender.org/, Copyright (C) 2013-2026 Blender Foundation, GPL-2.0-or-later).
 * Adapted and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.fbx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import me.hlebkukleuski.fullfilestudio.feature.cadvector.AnimClip
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfOutput
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser
import me.hlebkukleuski.fullfilestudio.feature.cadvector.NormalMapHeuristic
import me.hlebkukleuski.fullfilestudio.feature.cadvector.TriAnim
import me.hlebkukleuski.fullfilestudio.feature.cadvector.UsdMath

internal class FbxNode(val name: String, val props: List<Any>, val children: List<FbxNode>) {
    fun child(n: String): FbxNode? = children.firstOrNull { it.name == n }
    fun all(n: String): List<FbxNode> = children.filter { it.name == n }
    fun prop(i: Int): Any? = props.getOrNull(i)
    fun str(i: Int): String = (props.getOrNull(i) as? String) ?: ""
    fun num(i: Int): Double = when (val v = props.getOrNull(i)) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    fun long(i: Int): Long = when (val v = props.getOrNull(i)) {
        is Long -> v
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    fun doubles(): DoubleArray {
        val p = props.firstOrNull()
        if (p is DoubleArray) return p
        if (p is IntArray) return DoubleArray(p.size) { p[it].toDouble() }
        if (p is LongArray) return DoubleArray(p.size) { p[it].toDouble() }
        val a = child("a")
        if (a != null) return a.doubles()
        return DoubleArray(props.size) { (props[it] as? Number)?.toDouble() ?: 0.0 }
    }

    fun ints(): IntArray {
        val p = props.firstOrNull()
        if (p is IntArray) return p
        if (p is LongArray) return IntArray(p.size) { p[it].toInt() }
        if (p is DoubleArray) return IntArray(p.size) { p[it].toInt() }
        val a = child("a")
        if (a != null) return a.ints()
        return IntArray(props.size) { (props[it] as? Number)?.toInt() ?: 0 }
    }
}

internal object FbxReader {

    fun parse(bytes: ByteArray): FbxNode? {
        val root = if (bytes.size >= 27 && String(bytes, 0, 18, Charsets.US_ASCII) == "Kaydara FBX Binary") {
            parseBinary(bytes)
        } else {
            parseAscii(String(bytes, Charsets.UTF_8))
        }
        val encType = root?.child("FBXHeaderExtension")?.child("EncryptionType")?.num(0)?.toInt() ?: 0
        if (encType != 0) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: Encrypted FBX 3D file (EncryptionType=$encType) is not supported.")
        }
        return root
    }

    private fun parseBinary(bytes: ByteArray): FbxNode {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(23)
        val wide = version >= 7500
        val out = ArrayList<FbxNode>()
        var pos = 27
        while (pos < bytes.size) {
            val (node, next) = readNode(bytes, bb, pos, wide) ?: break
            if (node == null) break
            out.add(node)
            pos = next
        }
        return FbxNode("", listOf(version.toLong()), out)
    }

    private fun readNode(bytes: ByteArray, bb: ByteBuffer, start: Int, wide: Boolean): Pair<FbxNode?, Int>? {
        val headerSize = if (wide) 25 else 13
        if (start + headerSize > bytes.size) return null
        val end: Long
        val numProps: Long
        val listLen: Long
        val nameLen: Int
        if (wide) {
            end = bb.getLong(start)
            numProps = bb.getLong(start + 8)
            listLen = bb.getLong(start + 16)
            nameLen = bytes[start + 24].toInt() and 0xFF
        } else {
            end = bb.getInt(start).toLong() and 0xFFFFFFFFL
            numProps = bb.getInt(start + 4).toLong() and 0xFFFFFFFFL
            listLen = bb.getInt(start + 8).toLong() and 0xFFFFFFFFL
            nameLen = bytes[start + 12].toInt() and 0xFF
        }
        if (end == 0L) return Pair(null, start + headerSize)
        if (end > bytes.size || end <= start) return null
        val name = String(bytes, start + headerSize, nameLen, Charsets.US_ASCII)
        var p = start + headerSize + nameLen
        val props = ArrayList<Any>()
        val propsEnd = p + listLen
        var k = 0L
        while (k < numProps && p < propsEnd) {
            val type = bytes[p].toInt().toChar()
            p++
            when (type) {
                'Y' -> { props.add(bb.getShort(p).toLong()); p += 2 }
                'C' -> { props.add(bytes[p].toInt() != 0); p += 1 }
                'I' -> { props.add(bb.getInt(p).toLong()); p += 4 }
                'F' -> { props.add(bb.getFloat(p).toDouble()); p += 4 }
                'D' -> { props.add(bb.getDouble(p)); p += 8 }
                'L' -> { props.add(bb.getLong(p)); p += 8 }
                'S', 'R' -> {
                    val len = bb.getInt(p)
                    p += 4
                    if (type == 'S') {
                        val raw = String(bytes, p, len, Charsets.UTF_8)
                        val sep = raw.indexOf("\u0000\u0001")
                        props.add(if (sep >= 0) raw.substring(sep + 2) + "::" + raw.substring(0, sep) else raw)
                    } else {
                        props.add(bytes.copyOfRange(p, p + len))
                    }
                    p += len
                }
                'f', 'd', 'l', 'i', 'b' -> {
                    val count = bb.getInt(p)
                    val encoding = bb.getInt(p + 4)
                    val compLen = bb.getInt(p + 8)
                    p += 12
                    val elem = when (type) { 'f', 'i' -> 4; 'b' -> 1; else -> 8 }
                    val byteLen = count.toLong() * elem.toLong()
                    require(count >= 0 && byteLen in 0L..(64L * 1024L * 1024L))
                    if (encoding == 1) {
                        require(compLen >= 0 && p + compLen <= bytes.size)
                    } else {
                        require(p + byteLen <= bytes.size)
                    }
                    val data = if (encoding == 1) inflate(bytes, p, compLen, byteLen.toInt()) else bytes.copyOfRange(p, p + byteLen.toInt())
                    p += if (encoding == 1) compLen else byteLen.toInt()
                    val ab = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    props.add(
                        when (type) {
                            'f' -> DoubleArray(count) { ab.getFloat(it * 4).toDouble() }
                            'd' -> DoubleArray(count) { ab.getDouble(it * 8) }
                            'l' -> LongArray(count) { ab.getLong(it * 8) }
                            'i' -> IntArray(count) { ab.getInt(it * 4) }
                            else -> IntArray(count) { data[it].toInt() }
                        }
                    )
                }
                else -> throw IllegalArgumentException("fbx prop $type")
            }
            k++
        }
        p = propsEnd.toInt()
        val children = ArrayList<FbxNode>()
        while (p < end) {
            val (child, next) = readNode(bytes, bb, p, wide) ?: break
            if (child == null) {
                p = next
                break
            }
            children.add(child)
            p = next
        }
        val n = name.substringBefore('\u0000')
        return Pair(FbxNode(n, props, children), end.toInt())
    }

    private fun inflate(bytes: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(bytes, off, len)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inf.finished()) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            return out
        } finally {
            inf.end()
        }
    }

    private class Lexer(val s: String) {
        var i = 0
        fun skipSpace(newlines: Boolean): Boolean {
            var sawNewline = false
            while (i < s.length) {
                val c = s[i]
                if (c == ';') {
                    while (i < s.length && s[i] != '\n') i++
                } else if (c == '\n') {
                    if (!newlines) return true
                    sawNewline = true
                    i++
                } else if (c.isWhitespace()) {
                    i++
                } else break
            }
            return sawNewline
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    }

    private fun parseAscii(text: String): FbxNode? {
        val lx = Lexer(text)
        val nodes = ArrayList<FbxNode>()
        while (true) {
            lx.skipSpace(true)
            if (lx.i >= text.length) break
            val n = parseAsciiNode(lx) ?: break
            nodes.add(n)
        }
        if (nodes.isEmpty()) return null
        val header = nodes.firstOrNull { it.name == "FBXHeaderExtension" }?.child("FBXVersion")?.long(0) ?: 6100L
        return FbxNode("", listOf(header), nodes)
    }

    private fun readKey(lx: Lexer): String? {
        val start = lx.i
        while (lx.i < lx.s.length && lx.s[lx.i] != ':' && lx.s[lx.i] != '\n' && lx.s[lx.i] != '{' && lx.s[lx.i] != '}') lx.i++
        if (lx.i >= lx.s.length || lx.s[lx.i] != ':') return null
        val key = lx.s.substring(start, lx.i).trim()
        lx.i++
        return key
    }

    private fun readValue(lx: Lexer): Any? {
        val c = lx.peek()
        if (c == '"') {
            val sb = StringBuilder()
            lx.i++
            while (lx.i < lx.s.length && lx.s[lx.i] != '"') {
                sb.append(lx.s[lx.i])
                lx.i++
            }
            lx.i++
            return sb.toString().replace("&quot;", "\"")
        }
        if (c == '*') {
            lx.i++
            while (lx.i < lx.s.length && lx.s[lx.i].isDigit()) lx.i++
            lx.skipSpace(true)
            if (lx.peek() == '{') {
                lx.i++
                val values = ArrayList<Double>()
                while (true) {
                    lx.skipSpace(true)
                    if (lx.peek() == '}' || lx.i >= lx.s.length) {
                        lx.i++
                        break
                    }
                    val save = lx.i
                    val key = readKey(lx)
                    if (key == null) {
                        lx.i = save
                    }
                    while (true) {
                        lx.skipSpace(true)
                        if (lx.peek() == '}') break
                        val v = readValue(lx)
                        if (v is Number) values.add(v.toDouble())
                        lx.skipSpace(true)
                        if (lx.peek() == ',') lx.i++ else if (lx.peek() != '}') break
                    }
                }
                return DoubleArray(values.size) { values[it] }
            }
            return null
        }
        val start = lx.i
        while (lx.i < lx.s.length) {
            val ch = lx.s[lx.i]
            if (ch == ',' || ch == '\n' || ch == '{' || ch == '}' || ch == '\r' || ch == ';') break
            lx.i++
        }
        val tok = lx.s.substring(start, lx.i).trim()
        if (tok.isEmpty()) return null
        tok.toLongOrNull()?.let { return it }
        tok.toDoubleOrNull()?.let { return it }
        return tok
    }

    private fun parseAsciiNode(lx: Lexer): FbxNode? {
        val key = readKey(lx) ?: run {
            while (lx.i < lx.s.length && lx.s[lx.i] != '\n') lx.i++
            return if (lx.i >= lx.s.length) null else FbxNode("", emptyList(), emptyList())
        }
        val props = ArrayList<Any>()
        while (true) {
            lx.skipSpace(false)
            val c = lx.peek()
            if (c == '\n' || c == '{' || c == '}' || c == '\u0000') break
            val v = readValue(lx)
            if (v != null) props.add(v)
            lx.skipSpace(false)
            if (lx.peek() == ',') {
                lx.i++
                lx.skipSpace(true)
            } else break
        }
        lx.skipSpace(false)
        val children = ArrayList<FbxNode>()
        if (lx.peek() == '{') {
            lx.i++
            while (true) {
                lx.skipSpace(true)
                if (lx.peek() == '}') {
                    lx.i++
                    break
                }
                if (lx.i >= lx.s.length) break
                val ch = parseAsciiNode(lx) ?: break
                if (ch.name.isNotEmpty()) children.add(ch)
            }
        }
        val packed = if (props.size > 16 && props.all { it is Number }) listOf<Any>(DoubleArray(props.size) { (props[it] as Number).toDouble() }) else props
        return FbxNode(key, packed, children)
    }
}

internal class FbxLoader(private val root: FbxNode, private val out: GltfOutput) {

    private class Obj(val id: Long, val node: FbxNode, val kind: String) {
        val children = ArrayList<Pair<Long, String?>>()
        val parents = ArrayList<Pair<Long, String?>>()
    }

    private val objects = LinkedHashMap<Long, Obj>()
    private val nameToId = HashMap<String, Long>()
    private val version = root.long(0)
    private val worldCache = HashMap<Long, FloatArray>()
    private val materialCache = HashMap<Long, Triple<Int, Int, Int>>()

    private class Curve(val times: DoubleArray, val values: DoubleArray, val interp: IntArray, val right: DoubleArray, val left: DoubleArray)

    private class LayerCurves(val weight: Double, val mode: Int, val comps: Array<Curve?>, val defaults: DoubleArray?)

    private val clipCurves = ArrayList<HashMap<Long, HashMap<String, ArrayList<LayerCurves>>>>()
    private val clipNames = ArrayList<String>()
    private var clipStarts = IntArray(0)
    private var clipLens = IntArray(0)
    private var frameClip = IntArray(0)
    private var frameTimes = DoubleArray(0)
    private val fps = 30f
    private val animActive get() = frameTimes.size >= 2
    private val boneFrames = ArrayList<FloatArray>()
    private val rigidBones = HashMap<Long, Int>()
    private val clusterBones = HashMap<Long, Int>()
    private val morphFrameWeights = ArrayList<FloatArray>()
    private val morphBases = ArrayList<Float>()
    private val dynamicCache = HashMap<Long, Boolean>()
    private val frameWorld = HashMap<Long, Array<FloatArray?>>()
    private val anim = TriAnim()
    private var axis = GltfParser.IDENTITY

    fun load() {
        val objs = root.child("Objects") ?: return
        var synthetic = -1L
        for (n in objs.children) {
            val id: Long
            if (n.prop(0) is Long && version >= 7000) {
                id = n.long(0)
            } else {
                id = synthetic--
                nameToId[n.str(0)] = id
            }
            objects[id] = Obj(id, n, n.name)
        }
        nameToId["Model::Scene"] = 0L
        val conns = root.child("Connections")
        if (conns != null) {
            for (c in conns.children) {
                if (c.name != "C" && c.name != "Connect") continue
                val type = c.str(0)
                val child = if (c.prop(1) is String) nameToId[c.str(1)] ?: continue else c.long(1)
                val parent = if (c.prop(2) is String) nameToId[c.str(2)] ?: continue else c.long(2)
                val propName = if (type == "OP") c.str(3) else null
                objects[child]?.parents?.add(parent to propName)
                objects[parent]?.children?.add(child to propName)
            }
        }
        axis = axisMatrix()
        try {
            setupAnimation()
        } catch (_: Throwable) {
            frameTimes = DoubleArray(0)
        }
        for (o in objects.values) {
            if (out.truncated) break
            if (o.kind != "Model") continue
            val geometries = ArrayList<Pair<FbxNode, Obj?>>()
            if (o.node.child("Vertices") != null) geometries.add(o.node to null)
            for ((cid, _) in o.children) {
                val c = objects[cid] ?: continue
                if (c.kind == "Geometry" && c.node.child("Vertices") != null) geometries.add(c.node to c)
            }
            if (geometries.isEmpty()) continue
            val materials = o.children.mapNotNull { (cid, _) -> objects[cid]?.takeIf { it.kind == "Material" } }
            val geo = geometric(o.node)
            val world = GltfParser.mul(axis, GltfParser.mul(world(o.id, 0), geo))
            for ((g, gObj) in geometries) emitGeometry(g, gObj, o, geo, world, materials)
        }
        finishAnimation()
    }

    private fun parseCurve(n: FbxNode): Curve? {
        val kt = n.child("KeyTime") ?: return null
        val times = kt.props.firstOrNull().let { p ->
            when (p) {
                is LongArray -> DoubleArray(p.size) { p[it] / TICKS }
                else -> kt.doubles().let { d -> DoubleArray(d.size) { d[it] / TICKS } }
            }
        }
        val values = n.child("KeyValueFloat")?.doubles() ?: return null
        val count = minOf(times.size, values.size)
        if (count == 0) return null
        val flags = n.child("KeyAttrFlags")?.ints() ?: IntArray(0)
        val data = n.child("KeyAttrDataFloat")?.doubles() ?: DoubleArray(0)
        val refs = n.child("KeyAttrRefCount")?.ints() ?: IntArray(0)
        val interp = IntArray(count) { 1 }
        val right = DoubleArray(count)
        val left = DoubleArray(count)
        val auto = BooleanArray(count)
        var key = 0
        for (g in refs.indices) {
            repeat(refs[g]) {
                if (key < count) {
                    val f = flags.getOrElse(g) { 4 }
                    interp[key] = when {
                        f and 0x2 != 0 -> 0
                        f and 0x8 != 0 -> 2
                        else -> 1
                    }
                    auto[key] = f and 0x400 == 0
                    right[key] = data.getOrElse(g * 4) { 0.0 }
                    left[key] = data.getOrElse(g * 4 + 1) { 0.0 }
                    key++
                }
            }
        }
        val t = times.copyOf(count)
        val v = values.copyOf(count)
        for (i in 0 until count) if (interp[i] == 2 && auto[i]) {
            val prev = if (i > 0) i - 1 else i
            val next = if (i + 1 < count) i + 1 else i
            val dt = t[next] - t[prev]
            right[i] = if (dt > 0) (v[next] - v[prev]) / dt else 0.0
            if (i > 0) left[i - 1] = right[i]
        }
        for (i in 0 until count - 1) if (interp[i] == 2 && auto.getOrElse(i + 1) { false }) {
            val j = i + 1
            val prev = j - 1
            val next = if (j + 1 < count) j + 1 else j
            val dt = t[next] - t[prev]
            left[i] = if (dt > 0) (v[next] - v[prev]) / dt else 0.0
        }
        return Curve(t, v, interp, right, left)
    }

    private fun Curve.eval(time: Double): Double {
        val n = times.size
        if (time <= times[0] || n == 1) return values[0]
        if (time >= times[n - 1]) return values[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[mid] <= time) lo = mid else hi = mid
        }
        val dt = times[hi] - times[lo]
        if (dt <= 0) return values[hi]
        val a = (time - times[lo]) / dt
        return when (interp[lo]) {
            0 -> values[lo]
            2 -> {
                val a2 = a * a
                val a3 = a2 * a
                (2 * a3 - 3 * a2 + 1) * values[lo] + (a3 - 2 * a2 + a) * right[lo] * dt + (-2 * a3 + 3 * a2) * values[hi] + (a3 - a2) * left[lo] * dt
            }
            else -> values[lo] + (values[hi] - values[lo]) * a
        }
    }

    private fun setupAnimation() {
        val stacks = objects.values.filter { it.kind == "AnimationStack" }
        val ranges = ArrayList<Pair<Double, Double>>()
        for (stack in stacks) {
            val layers = stack.children.mapNotNull { objects[it.first]?.takeIf { o -> o.kind == "AnimationLayer" } }
            if (layers.isEmpty()) continue
            val map = HashMap<Long, HashMap<String, ArrayList<LayerCurves>>>()
            var tMin = Double.MAX_VALUE
            var tMax = -Double.MAX_VALUE
            for ((li, layer) in layers.withIndex()) {
                val lp = props70(layer.node)
                val weight = (scalar(lp, "Weight") ?: 100.0) / 100.0
                val mode = if (li == 0) 1 else scalar(lp, "BlendMode")?.toInt() ?: 0
                if (scalar(lp, "Mute")?.toInt() == 1) continue
                for ((cnId, _) in layer.children) {
                    val cn = objects[cnId] ?: continue
                    if (cn.kind != "AnimationCurveNode") continue
                    val (targetId, prop) = cn.parents.firstOrNull { it.second != null && objects[it.first]?.kind != "AnimationLayer" } ?: continue
                    if (prop == null) continue
                    val comps = arrayOfNulls<Curve>(3)
                    for ((cid, cp) in cn.children) {
                        val c = objects[cid] ?: continue
                        if (c.kind != "AnimationCurve") continue
                        val idx = when (cp) {
                            "d|X" -> 0
                            "d|Y" -> 1
                            "d|Z" -> 2
                            "d|DeformPercent" -> 0
                            else -> continue
                        }
                        val curve = parseCurve(c.node) ?: continue
                        comps[idx] = curve
                        tMin = minOf(tMin, curve.times.first())
                        tMax = maxOf(tMax, curve.times.last())
                    }
                    if (comps.all { it == null }) continue
                    val cp70 = props70(cn.node)
                    val defaults = if (prop == "DeformPercent") doubleArrayOf(scalar(cp70, "d|DeformPercent") ?: 0.0, 0.0, 0.0)
                    else doubleArrayOf(scalar(cp70, "d|X") ?: 0.0, scalar(cp70, "d|Y") ?: 0.0, scalar(cp70, "d|Z") ?: 0.0)
                    map.getOrPut(targetId) { HashMap() }.getOrPut(prop) { ArrayList() }.add(LayerCurves(weight, mode, comps, defaults))
                }
            }
            if (map.isEmpty()) continue
            val sp = props70(stack.node)
            val start = sp["LocalStart"]?.let { p -> p.long(if (p.name == "Property") 3 else 4) / TICKS } ?: tMin
            val stop = sp["LocalStop"]?.let { p -> p.long(if (p.name == "Property") 3 else 4) / TICKS }?.takeIf { it > start } ?: tMax
            val dur = stop - start
            if (!(dur >= 0) || dur > 7200) continue
            clipCurves.add(map)
            clipNames.add(stack.node.str(1).substringAfter("::").ifBlank { "Take ${clipNames.size + 1}" })
            ranges.add(start to stop)
        }
        if (clipCurves.isEmpty()) return
        val lens = IntArray(ranges.size) { minOf(4000, kotlin.math.ceil((ranges[it].second - ranges[it].first) * fps).toInt() + 1).coerceAtLeast(2) }
        val starts = IntArray(ranges.size)
        var acc = 0
        for (i in ranges.indices) {
            starts[i] = acc
            acc += lens[i]
        }
        clipStarts = starts
        clipLens = lens
        frameClip = IntArray(acc)
        frameTimes = DoubleArray(acc)
        for (i in ranges.indices) for (k in 0 until lens[i]) {
            frameClip[starts[i] + k] = i
            frameTimes[starts[i] + k] = ranges[i].first + minOf(k / fps.toDouble(), ranges[i].second - ranges[i].first)
        }
    }

    private fun isDynamic(id: Long, depth: Int = 0): Boolean {
        dynamicCache[id]?.let { return it }
        var dyn = clipCurves.any { m -> m[id]?.let { it.containsKey("Lcl Translation") || it.containsKey("Lcl Rotation") || it.containsKey("Lcl Scaling") } == true }
        if (!dyn && depth < 64) {
            val parent = objects[id]?.parents?.firstOrNull { (pid, prop) -> prop == null && objects[pid]?.kind == "Model" }?.first
            if (parent != null) dyn = isDynamic(parent, depth + 1)
        }
        dynamicCache[id] = dyn
        return dyn
    }

    private fun animated(id: Long, prop: String, k: Int, fallback: DoubleArray?): DoubleArray? {
        val layers = clipCurves.getOrNull(frameClip.getOrElse(k) { 0 })?.get(id)?.get(prop) ?: return fallback
        val v = (fallback ?: DoubleArray(3)).copyOf()
        val t = frameTimes[k]
        for (l in layers) {
            for (c in 0 until 3) {
                val curve = l.comps[c] ?: continue
                val lv = curve.eval(t)
                v[c] = when {
                    l.mode == 1 || l.mode == 2 -> v[c] + (lv - v[c]) * l.weight
                    prop == "Lcl Scaling" -> v[c] * Math.pow(lv.takeIf { it > 0 } ?: 1.0, l.weight)
                    else -> v[c] + lv * l.weight
                }
            }
        }
        return v
    }

    private fun worldAt(id: Long, k: Int, depth: Int = 0): FloatArray {
        if (!isDynamic(id)) return world(id, 0)
        val arr = frameWorld.getOrPut(id) { arrayOfNulls(frameTimes.size) }
        arr[k]?.let { return it }
        val o = objects[id] ?: return GltfParser.IDENTITY
        val parent = o.parents.firstOrNull { (pid, prop) -> prop == null && objects[pid]?.kind == "Model" }?.first
        val pw = if (parent != null && depth < 64) worldAt(parent, k, depth + 1) else GltfParser.IDENTITY
        val p = props70(o.node)
        val w = GltfParser.mul(pw, local(
            o.node,
            animated(id, "Lcl Translation", k, vec(p, "Lcl Translation", 0.0)),
            animated(id, "Lcl Rotation", k, vec(p, "Lcl Rotation", 0.0)),
            animated(id, "Lcl Scaling", k, vec(p, "Lcl Scaling", 1.0) ?: doubleArrayOf(1.0, 1.0, 1.0))
        ))
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
        val d = UsdMath.invert(DoubleArray(16) { m[it].toDouble() }) ?: return GltfParser.IDENTITY
        return FloatArray(16) { d[it].toFloat() }
    }

    private fun budgetOk() = (boneFrames.size + 1).toLong() * frameTimes.size * 12 <= 8_000_000L

    private fun rigidBone(model: Obj, geo: FloatArray, staticWorld: FloatArray): Int {
        if (!animActive || !isDynamic(model.id)) return -1
        rigidBones[model.id]?.let { return it }
        if (!budgetOk()) return -1
        val inv = invert(staticWorld)
        val frames = FloatArray(frameTimes.size * 12)
        for (k in frameTimes.indices) pack(GltfParser.mul(GltfParser.mul(axis, GltfParser.mul(worldAt(model.id, k), geo)), inv), frames, k * 12)
        boneFrames.add(frames)
        rigidBones[model.id] = boneFrames.size - 1
        return boneFrames.size - 1
    }

    private class Cluster(val id: Long, val bone: Long, val indexes: IntArray, val weights: DoubleArray, val transform: FloatArray)

    private fun matrix16(n: FbxNode?): FloatArray? {
        val d = n?.doubles() ?: return null
        if (d.size < 16) return null
        return FloatArray(16) { d[it].toFloat() }
    }

    private fun clusters(gObj: Obj?): List<Cluster> {
        gObj ?: return emptyList()
        val result = ArrayList<Cluster>()
        for ((sid, _) in gObj.children) {
            val skin = objects[sid] ?: continue
            if (skin.kind != "Deformer" || skin.node.str(2) != "Skin") continue
            for ((cid, _) in skin.children) {
                val cl = objects[cid] ?: continue
                if (cl.kind != "Deformer" || cl.node.str(2) != "Cluster") continue
                val bone = cl.children.firstOrNull { objects[it.first]?.kind == "Model" }?.first ?: continue
                val idx = cl.node.child("Indexes")?.ints() ?: continue
                val w = cl.node.child("Weights")?.doubles() ?: continue
                val t = matrix16(cl.node.child("Transform")) ?: GltfParser.IDENTITY
                result.add(Cluster(cl.id, bone, idx, w, t))
            }
        }
        return result
    }

    private fun clusterMatrix(c: Cluster, boneWorld: FloatArray): FloatArray =
        GltfParser.mul(axis, GltfParser.mul(boneWorld, c.transform))

    private fun clusterBone(c: Cluster): Int {
        if (!animActive || !isDynamic(c.bone)) return -1
        clusterBones[c.id]?.let { return it }
        if (!budgetOk()) return -1
        val frames = FloatArray(frameTimes.size * 12)
        for (k in frameTimes.indices) pack(clusterMatrix(c, worldAt(c.bone, k)), frames, k * 12)
        boneFrames.add(frames)
        clusterBones[c.id] = boneFrames.size - 1
        return boneFrames.size - 1
    }

    private class Shape(val channel: Long, val weight: Float, val indexes: IntArray, val deltas: DoubleArray)

    private fun shapes(gObj: Obj?): List<Shape> {
        gObj ?: return emptyList()
        val result = ArrayList<Shape>()
        for ((bid, _) in gObj.children) {
            val bs = objects[bid] ?: continue
            if (bs.kind != "Deformer" || bs.node.str(2) != "BlendShape") continue
            for ((chId, _) in bs.children) {
                val ch = objects[chId] ?: continue
                if (ch.kind != "Deformer" || ch.node.str(2) != "BlendShapeChannel") continue
                val pct = scalar(props70(ch.node), "DeformPercent") ?: ch.node.child("DeformPercent")?.num(0) ?: 0.0
                val shapeObj = ch.children.mapNotNull { objects[it.first] }.firstOrNull { it.kind == "Geometry" } ?: continue
                val idx = shapeObj.node.child("Indexes")?.ints() ?: continue
                val d = shapeObj.node.child("Vertices")?.doubles() ?: continue
                result.add(Shape(ch.id, (pct / 100.0).toFloat(), idx, d))
            }
        }
        return result
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
            fps, frames, bones, matrices, morphs, weights, if (morphs > 0) morphBases.toFloatArray() else null,
            clipNames.toList(), clipStarts, clipLens
        ))
    }

    private fun props70(n: FbxNode): Map<String, FbxNode> {
        val p = n.child("Properties70") ?: n.child("Properties60") ?: return emptyMap()
        val map = HashMap<String, FbxNode>()
        for (c in p.children) if (c.name == "P" || c.name == "Property") map[c.str(0)] = c
        return map
    }

    private fun vec(map: Map<String, FbxNode>, key: String, def: Double): DoubleArray? {
        val p = map[key] ?: return null
        val start = if (p.name == "Property") 3 else 4
        return DoubleArray(3) { if (p.props.size > start + it) p.num(start + it) else def }
    }

    private fun scalar(map: Map<String, FbxNode>, key: String): Double? {
        val p = map[key] ?: return null
        val start = if (p.name == "Property") 3 else 4
        return if (p.props.size > start) p.num(start) else null
    }

    private fun axisMatrix(): FloatArray {
        val gs = root.child("GlobalSettings") ?: return GltfParser.IDENTITY
        val p = props70(gs)
        val up = scalar(p, "UpAxis")?.toInt() ?: 1
        val upSign = scalar(p, "UpAxisSign")?.toInt() ?: 1
        val front = scalar(p, "FrontAxis")?.toInt() ?: 2
        val frontSign = scalar(p, "FrontAxisSign")?.toInt() ?: 1
        val coord = scalar(p, "CoordAxis")?.toInt() ?: 0
        val coordSign = scalar(p, "CoordAxisSign")?.toInt() ?: 1
        if (setOf(up, front, coord).size != 3 || listOf(up, front, coord).any { it !in 0..2 }) return GltfParser.IDENTITY
        val m = FloatArray(16)
        m[coord * 4 + 0] = coordSign.toFloat()
        m[up * 4 + 1] = upSign.toFloat()
        m[front * 4 + 2] = frontSign.toFloat()
        m[15] = 1f
        return m
    }

    private fun rot(order: Int, deg: DoubleArray): FloatArray {
        fun r(axis: Int, d: Double): FloatArray {
            val a = Math.toRadians(d)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val m = GltfParser.IDENTITY.copyOf()
            when (axis) {
                0 -> { m[5] = c; m[6] = s; m[9] = -s; m[10] = c }
                1 -> { m[0] = c; m[2] = -s; m[8] = s; m[10] = c }
                else -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c }
            }
            return m
        }
        val x = r(0, deg[0])
        val y = r(1, deg[1])
        val z = r(2, deg[2])
        val m = GltfParser::mul
        return when (order) {
            1 -> m(y, m(z, x))
            2 -> m(x, m(z, y))
            3 -> m(z, m(x, y))
            4 -> m(y, m(x, z))
            5 -> m(x, m(y, z))
            else -> m(z, m(y, x))
        }
    }

    private fun trans(v: DoubleArray?, sign: Float = 1f): FloatArray {
        val m = GltfParser.IDENTITY.copyOf()
        if (v != null) {
            m[12] = v[0].toFloat() * sign
            m[13] = v[1].toFloat() * sign
            m[14] = v[2].toFloat() * sign
        }
        return m
    }

    private fun scale(v: DoubleArray?): FloatArray {
        val m = GltfParser.IDENTITY.copyOf()
        if (v != null) {
            m[0] = v[0].toFloat()
            m[5] = v[1].toFloat()
            m[10] = v[2].toFloat()
        }
        return m
    }

    private fun transpose(a: FloatArray): FloatArray = FloatArray(16) { a[(it % 4) * 4 + it / 4] }

    private fun local(n: FbxNode, tOverride: DoubleArray? = null, rOverride: DoubleArray? = null, sOverride: DoubleArray? = null): FloatArray {
        val p = props70(n)
        val order = scalar(p, "RotationOrder")?.toInt() ?: 0
        val t = trans(tOverride ?: vec(p, "Lcl Translation", 0.0))
        val roff = trans(vec(p, "RotationOffset", 0.0))
        val rp = trans(vec(p, "RotationPivot", 0.0))
        val rpInv = trans(vec(p, "RotationPivot", 0.0), -1f)
        val pre = vec(p, "PreRotation", 0.0)?.let { rot(0, it) } ?: GltfParser.IDENTITY
        val post = vec(p, "PostRotation", 0.0)?.let { transpose(rot(0, it)) } ?: GltfParser.IDENTITY
        val r = (rOverride ?: vec(p, "Lcl Rotation", 0.0))?.let { rot(order, it) } ?: GltfParser.IDENTITY
        val soff = trans(vec(p, "ScalingOffset", 0.0))
        val sp = trans(vec(p, "ScalingPivot", 0.0))
        val spInv = trans(vec(p, "ScalingPivot", 0.0), -1f)
        val s = scale(sOverride ?: vec(p, "Lcl Scaling", 1.0))
        var m = t
        for (x in listOf(roff, rp, pre, r, post, rpInv, soff, sp, s, spInv)) m = GltfParser.mul(m, x)
        return m
    }

    private fun geometric(n: FbxNode): FloatArray {
        val p = props70(n)
        val t = trans(vec(p, "GeometricTranslation", 0.0))
        val r = vec(p, "GeometricRotation", 0.0)?.let { rot(0, it) } ?: GltfParser.IDENTITY
        val s = scale(vec(p, "GeometricScaling", 1.0))
        return GltfParser.mul(t, GltfParser.mul(r, s))
    }

    private fun world(id: Long, depth: Int): FloatArray {
        worldCache[id]?.let { return it }
        val o = objects[id] ?: return GltfParser.IDENTITY
        val parent = o.parents.firstOrNull { (pid, prop) -> prop == null && objects[pid]?.kind == "Model" }?.first
        val pw = if (parent != null && depth < 64) world(parent, depth + 1) else GltfParser.IDENTITY
        val w = GltfParser.mul(pw, local(o.node))
        worldCache[id] = w
        return w
    }

    private fun materialOf(mat: Obj): Triple<Int, Int, Int> {
        materialCache[mat.id]?.let { return it }
        val p = props70(mat.node)
        val diffuse = vec(p, "DiffuseColor", 0.8) ?: vec(p, "Diffuse", 0.8) ?: vec(p, "Maya|baseColor", 0.8)
        val opacity = scalar(p, "Opacity")
        val tf = scalar(p, "TransparencyFactor")
        val tc = vec(p, "TransparentColor", 0.0)
        val alpha = when {
            opacity != null && opacity < 1.0 -> opacity
            tf != null && tf > 0.0 && (tc == null || tc.sum() > 0.0) -> 1.0 - tf * (tc?.average() ?: 1.0)
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        var texture = -1
        for ((cid, prop) in mat.children) {
            val t = objects[cid] ?: continue
            if (t.kind != "Texture") continue
            val name = prop ?: ""
            if (prop != null && !name.contains("Diffuse", true) && !name.contains("baseColor", true) && !name.contains("Base Color", true)) continue
            texture = textureOf(t, alpha >= 0.999)
            if (texture >= 0) break
        }
        var normalTex = -1
        for ((cid, prop) in mat.children) {
            val t = objects[cid] ?: continue
            if (t.kind != "Texture" || prop == null) continue
            val explicit = prop.contains("NormalMap", true) || prop.contains("normalCamera", true) || prop.equals("Maya|normalCamera", true)
            val bump = prop.equals("Bump", true) || prop.contains("BumpMap", true) || prop.contains("3dsMax|Parameters|bump_map", true)
            if (!explicit && !bump) continue
            val (key, bytes) = textureBytes(t)
            if (bytes == null) continue
            if (!explicit && !NormalMapHeuristic.looksLikeNormalMap(bytes)) continue
            normalTex = out.normalTexture(key, bytes, false, 1f)
            if (normalTex >= 0) break
        }
        val color = if (diffuse == null && texture < 0) 0 else {
            val d = if (texture >= 0) doubleArrayOf(1.0, 1.0, 1.0) else diffuse!!
            ((alpha * 255).roundToInt() shl 24) or ((d[0].coerceIn(0.0, 1.0) * 255).roundToInt() shl 16) or
                ((d[1].coerceIn(0.0, 1.0) * 255).roundToInt() shl 8) or (d[2].coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        val result = Triple(color, texture, normalTex)
        materialCache[mat.id] = result
        return result
    }

    private fun textureBytes(t: Obj): Pair<String, ByteArray?> {
        for ((cid, _) in t.children) {
            val v = objects[cid] ?: continue
            if (v.kind != "Video") continue
            val content = v.node.child("Content")?.prop(0)
            if (content is ByteArray && content.size > 16) return "fbx:video:${v.id}" to content
            if (content is String && content.length > 16) {
                val b = try { android.util.Base64.decode(content, android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
                if (b != null) return "fbx:video:${v.id}" to b
            }
        }
        val rel = t.node.child("RelativeFilename")?.str(0)?.takeIf { it.isNotBlank() }
        val abs = t.node.child("FileName")?.str(0)?.takeIf { it.isNotBlank() }
        val ref = (rel ?: abs)?.replace('\\', '/') ?: return "fbx:${t.id}" to null
        return "fbx:file:$ref" to out.resource(ref)
    }

    private fun textureOf(t: Obj, opaque: Boolean): Int {
        var bytes: ByteArray? = null
        var key = "fbx:${t.id}"
        for ((cid, _) in t.children) {
            val v = objects[cid] ?: continue
            if (v.kind != "Video") continue
            val content = v.node.child("Content")?.prop(0) as? ByteArray
            if (content != null && content.size > 16) {
                bytes = content
                key = "fbx:video:${v.id}"
            } else if (content == null) {
                val raw = v.node.child("Content")?.prop(0) as? String
                if (raw != null && raw.length > 16) bytes = try { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
            }
            if (bytes != null) break
        }
        if (bytes == null) {
            val rel = t.node.child("RelativeFilename")?.str(0)?.takeIf { it.isNotBlank() }
            val abs = t.node.child("FileName")?.str(0)?.takeIf { it.isNotBlank() }
            val ref = (rel ?: abs)?.replace('\\', '/') ?: return -1
            bytes = out.resource(ref)
            key = "fbx:file:$ref"
        }
        return out.texture("$key:$opaque", bytes, opaque)
    }

    private class Layer(val mapping: String, val reference: String, val data: DoubleArray, val index: IntArray?, val stride: Int)

    private fun layer(g: FbxNode, name: String, dataName: String, indexName: String, stride: Int, setName: String? = null): Layer? {
        val layers = g.all(name)
        val l = (if (setName != null) layers.firstOrNull { it.child("Name")?.str(0) == setName } else null)
            ?: layers.minByOrNull { it.long(0) } ?: return null
        val data = l.child(dataName)?.doubles() ?: return null
        return Layer(
            l.child("MappingInformationType")?.str(0) ?: "ByPolygonVertex",
            l.child("ReferenceInformationType")?.str(0) ?: "Direct",
            data, l.child(indexName)?.ints(), stride
        )
    }

    private fun Layer.at(polyVertex: Int, vertex: Int, polygon: Int): Int {
        val key = when (mapping) {
            "ByPolygonVertex" -> polyVertex
            "ByVertex", "ByVertice", "ByControlPoint" -> vertex
            "ByPolygon" -> polygon
            "AllSame" -> 0
            else -> polyVertex
        }
        val idx = if ((reference == "IndexToDirect" || reference == "Index") && index != null) index.getOrElse(key) { -1 } else key
        return if (idx < 0 || (idx + 1) * stride > data.size) -1 else idx
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

    private fun xformNormal(nm: FloatArray, x: Float, y: Float, z: Float, o: FloatArray, at: Int) {
        val nx = nm[0] * x + nm[1] * y + nm[2] * z
        val ny = nm[3] * x + nm[4] * y + nm[5] * z
        val nz = nm[6] * x + nm[7] * y + nm[8] * z
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 0f) {
            o[at] = nx / l; o[at + 1] = ny / l; o[at + 2] = nz / l
        } else {
            o[at] = 0f; o[at + 1] = 0f; o[at + 2] = 0f
        }
    }

    private fun emitGeometry(g: FbxNode, gObj: Obj?, model: Obj, geo: FloatArray, world: FloatArray, materials: List<Obj>) {
        val verts = g.child("Vertices")?.doubles() ?: return
        val polyIdx = g.child("PolygonVertexIndex")?.ints() ?: return
        val vCount = verts.size / 3
        val local = FloatArray(vCount * 3)
        for (i in 0 until vCount) {
            val x = verts[i * 3].toFloat()
            val y = verts[i * 3 + 1].toFloat()
            val z = verts[i * 3 + 2].toFloat()
            local[i * 3] = geo[0] * x + geo[4] * y + geo[8] * z + geo[12]
            local[i * 3 + 1] = geo[1] * x + geo[5] * y + geo[9] * z + geo[13]
            local[i * 3 + 2] = geo[2] * x + geo[6] * y + geo[10] * z + geo[14]
        }
        val shapeList = shapes(gObj)
        for (sh in shapeList) {
            if (sh.weight == 0f) continue
            for (q in sh.indexes.indices) {
                val v = sh.indexes[q]
                if (v !in 0 until vCount || q * 3 + 2 >= sh.deltas.size) continue
                val dx = sh.deltas[q * 3].toFloat()
                val dy = sh.deltas[q * 3 + 1].toFloat()
                val dz = sh.deltas[q * 3 + 2].toFloat()
                local[v * 3] += sh.weight * (geo[0] * dx + geo[4] * dy + geo[8] * dz)
                local[v * 3 + 1] += sh.weight * (geo[1] * dx + geo[5] * dy + geo[9] * dz)
                local[v * 3 + 2] += sh.weight * (geo[2] * dx + geo[6] * dy + geo[10] * dz)
            }
        }
        val geoOnly = GltfParser.mul(axis, GltfParser.mul(world(model.id, 0), GltfParser.IDENTITY))
        val clusterList = clusters(gObj)
        val infJ = if (clusterList.isNotEmpty()) IntArray(vCount * 4) { -1 } else null
        val infW = if (clusterList.isNotEmpty()) FloatArray(vCount * 4) else null
        if (infJ != null && infW != null) {
            for ((ci, c) in clusterList.withIndex()) {
                for (q in c.indexes.indices) {
                    val v = c.indexes[q]
                    val w = c.weights.getOrElse(q) { 0.0 }.toFloat()
                    if (v !in 0 until vCount || w <= 0f) continue
                    var slot = -1
                    for (s in 0 until 4) if (infJ[v * 4 + s] < 0) { slot = s; break }
                    if (slot < 0) {
                        var minS = 0
                        for (s in 1 until 4) if (infW[v * 4 + s] < infW[v * 4 + minS]) minS = s
                        if (infW[v * 4 + minS] >= w) continue
                        slot = minS
                    }
                    infJ[v * 4 + slot] = ci
                    infW[v * 4 + slot] = w
                }
            }
        }
        val staticCluster = clusterList.map { clusterMatrix(it, world(it.bone, 0)) }
        val skinned = BooleanArray(vCount)
        val pos = FloatArray(vCount * 3)
        val skinMat = if (infJ != null) FloatArray(vCount * 16) else null
        for (i in 0 until vCount) {
            val x = local[i * 3]
            val y = local[i * 3 + 1]
            val z = local[i * 3 + 2]
            if (infJ != null && infW != null && skinMat != null) {
                var sw = 0f
                val acc = FloatArray(16)
                for (s in 0 until 4) {
                    val ci = infJ[i * 4 + s]
                    if (ci < 0) continue
                    val w = infW[i * 4 + s]
                    val m = staticCluster[ci]
                    for (q in 0 until 16) acc[q] += w * m[q]
                    sw += w
                }
                if (sw > 0f) {
                    for (q in 0 until 16) acc[q] /= sw
                    System.arraycopy(acc, 0, skinMat, i * 16, 16)
                    pos[i * 3] = acc[0] * x + acc[4] * y + acc[8] * z + acc[12]
                    pos[i * 3 + 1] = acc[1] * x + acc[5] * y + acc[9] * z + acc[13]
                    pos[i * 3 + 2] = acc[2] * x + acc[6] * y + acc[10] * z + acc[14]
                    skinned[i] = true
                    continue
                }
            }
            pos[i * 3] = geoOnly[0] * x + geoOnly[4] * y + geoOnly[8] * z + geoOnly[12]
            pos[i * 3 + 1] = geoOnly[1] * x + geoOnly[5] * y + geoOnly[9] * z + geoOnly[13]
            pos[i * 3 + 2] = geoOnly[2] * x + geoOnly[6] * y + geoOnly[10] * z + geoOnly[14]
        }
        val clusterBoneIds = if (animActive && clusterList.isNotEmpty()) IntArray(clusterList.size) { clusterBone(clusterList[it]) } else null
        val skinAnimated = clusterBoneIds != null && clusterBoneIds.any { it >= 0 }
        val rigid = if (clusterList.isEmpty()) rigidBone(model, GltfParser.IDENTITY, geoOnly) else -1
        val morphIds = IntArray(shapeList.size) { k ->
            val sh = shapeList[k]
            val hasCurve = clipCurves.any { it[sh.channel]?.containsKey("DeformPercent") == true }
            if (animActive && hasCurve) {
                morphFrameWeights.add(FloatArray(frameTimes.size) { f ->
                    ((animated(sh.channel, "DeformPercent", f, doubleArrayOf(sh.weight * 100.0, 0.0, 0.0))?.get(0) ?: (sh.weight * 100.0)) / 100.0).toFloat()
                })
                morphBases.add(sh.weight)
                morphFrameWeights.size - 1
            } else -1
        }
        val shapeDense = shapeList.mapIndexed { k, sh ->
            if (morphIds[k] < 0) null else FloatArray(vCount * 3).also { d ->
                for (q in sh.indexes.indices) {
                    val v = sh.indexes[q]
                    if (v !in 0 until vCount || q * 3 + 2 >= sh.deltas.size) continue
                    val dx = sh.deltas[q * 3].toFloat()
                    val dy = sh.deltas[q * 3 + 1].toFloat()
                    val dz = sh.deltas[q * 3 + 2].toFloat()
                    d[v * 3] = geo[0] * dx + geo[4] * dy + geo[8] * dz
                    d[v * 3 + 1] = geo[1] * dx + geo[5] * dy + geo[9] * dz
                    d[v * 3 + 2] = geo[2] * dx + geo[6] * dy + geo[10] * dz
                }
            }
        }
        fun cpNormals(p: FloatArray): FloatArray {
            val acc = FloatArray(vCount * 3)
            val ring = ArrayList<Int>()
            for (raw in polyIdx) {
                ring.add(if (raw < 0) raw.inv() else raw)
                if (raw >= 0) continue
                var nx = 0f
                var ny = 0f
                var nz = 0f
                for (k in ring.indices) {
                    val a = ring[k]
                    val b = ring[(k + 1) % ring.size]
                    if (a !in 0 until vCount || b !in 0 until vCount) continue
                    nx += (p[a * 3 + 1] - p[b * 3 + 1]) * (p[a * 3 + 2] + p[b * 3 + 2])
                    ny += (p[a * 3 + 2] - p[b * 3 + 2]) * (p[a * 3] + p[b * 3])
                    nz += (p[a * 3] - p[b * 3]) * (p[a * 3 + 1] + p[b * 3 + 1])
                }
                for (a in ring) if (a in 0 until vCount) {
                    acc[a * 3] += nx; acc[a * 3 + 1] += ny; acc[a * 3 + 2] += nz
                }
                ring.clear()
            }
            for (i in 0 until vCount) {
                val l = kotlin.math.sqrt(acc[i * 3] * acc[i * 3] + acc[i * 3 + 1] * acc[i * 3 + 1] + acc[i * 3 + 2] * acc[i * 3 + 2])
                if (l > 0f) {
                    acc[i * 3] /= l; acc[i * 3 + 1] /= l; acc[i * 3 + 2] /= l
                }
            }
            return acc
        }
        val baseCpN = if (shapeDense.any { it != null }) cpNormals(local) else null
        val shapeNormalDense = shapeDense.map { d ->
            if (d == null || baseCpN == null) null else {
                val moved = FloatArray(local.size) { local[it] + d[it] }
                val n = cpNormals(moved)
                FloatArray(n.size) { n[it] - baseCpN[it] }
            }
        }
        val matLayer = g.all("LayerElementMaterial").minByOrNull { it.long(0) }
        val matMapping = matLayer?.child("MappingInformationType")?.str(0) ?: "AllSame"
        val matArr = matLayer?.child("Materials")?.ints()
        val resolved = materials.map { materialOf(it) }
        val textureUvSet = HashMap<Int, String?>()
        val uvLayers = HashMap<String?, Layer?>()
        fun uvFor(matIdx: Int): Layer? {
            val set = textureUvSet.getOrPut(matIdx) {
                val mat = materials.getOrNull(matIdx) ?: return@getOrPut null
                mat.children.firstNotNullOfOrNull { (cid, _) -> objects[cid]?.takeIf { it.kind == "Texture" }?.let { props70(it.node)["UVSet"]?.let { p -> p.str(if (p.name == "Property") 3 else 4) } } }
            }
            return uvLayers.getOrPut(set) { layer(g, "LayerElementUV", "UV", "UVIndex", 2, set?.takeIf { it.isNotBlank() && it != "default" }) }
        }
        val colors = layer(g, "LayerElementColor", "Colors", "ColorIndex", 4)
        val normals = layer(g, "LayerElementNormal", "Normals", "NormalsIndex", 3)
        val geoNm = normalMatrix(geo)
        val worldNm = normalMatrix(geoOnly)
        val tri = FloatArray(9)
        val uv = FloatArray(6)
        val nTri = FloatArray(9)
        val jTri = IntArray(12)
        val wTri = FloatArray(12)
        val bTri = FloatArray(9)
        val bnTri = FloatArray(9)
        val poly = IntArray(256)
        val polyCorner = IntArray(256)
        var n = 0
        var polygon = 0
        var cornerIndex = 0
        for (raw in polyIdx) {
            if (out.truncated) return
            val end = raw < 0
            val v = if (end) raw.inv() else raw
            if (n < poly.size) {
                poly[n] = v
                polyCorner[n] = cornerIndex
                n++
            }
            cornerIndex++
            if (!end) continue
            val matIdx = when {
                matArr == null || matArr.isEmpty() -> 0
                matMapping == "AllSame" -> matArr[0]
                else -> matArr.getOrElse(polygon) { 0 }
            }
            val (color, tex, ntex) = resolved.getOrNull(matIdx) ?: Triple(0, -1, -1)
            val uvLayer = if (tex >= 0 || ntex >= 0) uvFor(matIdx) else null
            for (k in 1 until n - 1) {
                val ids = intArrayOf(0, k, k + 1)
                var ok = true
                var hasUv = uvLayer != null
                var hasN = normals != null
                val cs = IntArray(3)
                var allSkinned = skinAnimated
                for ((slot, pi) in ids.withIndex()) {
                    val vi = poly[pi]
                    if (vi !in 0 until vCount) { ok = false; break }
                    tri[slot * 3] = pos[vi * 3]
                    tri[slot * 3 + 1] = pos[vi * 3 + 1]
                    tri[slot * 3 + 2] = pos[vi * 3 + 2]
                    if (!skinned[vi]) allSkinned = false
                    if (uvLayer != null) {
                        val ui = uvLayer.at(polyCorner[pi], vi, polygon)
                        if (ui < 0) hasUv = false else {
                            uv[slot * 2] = uvLayer.data[ui * 2].toFloat()
                            uv[slot * 2 + 1] = 1f - uvLayer.data[ui * 2 + 1].toFloat()
                        }
                    }
                    if (normals != null) {
                        val ni = normals.at(polyCorner[pi], vi, polygon)
                        if (ni < 0) hasN = false else {
                            val nx = normals.data[ni * 3].toFloat()
                            val ny = normals.data[ni * 3 + 1].toFloat()
                            val nz = normals.data[ni * 3 + 2].toFloat()
                            if (skinned[vi] && skinMat != null) {
                                val sm = FloatArray(16)
                                System.arraycopy(skinMat, vi * 16, sm, 0, 16)
                                val gx = geoNm[0] * nx + geoNm[1] * ny + geoNm[2] * nz
                                val gy = geoNm[3] * nx + geoNm[4] * ny + geoNm[5] * nz
                                val gz = geoNm[6] * nx + geoNm[7] * ny + geoNm[8] * nz
                                xformNormal(normalMatrix(sm), gx, gy, gz, nTri, slot * 3)
                                xformNormal(geoNm, nx, ny, nz, bnTri, slot * 3)
                            } else {
                                xformNormal(worldNm, nx, ny, nz, nTri, slot * 3)
                            }
                        }
                    }
                    if (colors != null) {
                        val ci = colors.at(polyCorner[pi], vi, polygon)
                        if (ci >= 0) {
                            val a = (colors.data.getOrElse(ci * 4 + 3) { 1.0 } * ((color ushr 24).takeIf { color != 0 } ?: 255) ).roundToInt().coerceIn(0, 255)
                            cs[slot] = (a shl 24) or ((colors.data[ci * 4].coerceIn(0.0, 1.0) * 255).roundToInt() shl 16) or
                                ((colors.data[ci * 4 + 1].coerceIn(0.0, 1.0) * 255).roundToInt() shl 8) or (colors.data[ci * 4 + 2].coerceIn(0.0, 1.0) * 255).roundToInt()
                        }
                    }
                }
                if (!ok) continue
                anim.reset()
                var animated = false
                if (allSkinned && infJ != null && infW != null && clusterBoneIds != null) {
                    for ((slot, pi) in ids.withIndex()) {
                        val vi = poly[pi]
                        for (s in 0 until 4) {
                            val ci = infJ[vi * 4 + s]
                            val bone = if (ci >= 0) clusterBoneIds[ci] else -1
                            jTri[slot * 4 + s] = bone
                            wTri[slot * 4 + s] = if (bone >= 0) infW[vi * 4 + s] else 0f
                        }
                        bTri[slot * 3] = local[vi * 3]
                        bTri[slot * 3 + 1] = local[vi * 3 + 1]
                        bTri[slot * 3 + 2] = local[vi * 3 + 2]
                    }
                    anim.joints = jTri
                    anim.weights = wTri
                    anim.bindPos = bTri
                    anim.bindNrm = if (hasN) bnTri else null
                    animated = true
                } else if (rigid >= 0) {
                    anim.bone = rigid
                    animated = true
                }
                val mIds = ArrayList<Int>()
                val mDeltas = ArrayList<Float>()
                val mnDeltas = ArrayList<Float>()
                for ((si, dense) in shapeDense.withIndex()) {
                    if (dense == null) continue
                    var nz = false
                    for (pi in ids) {
                        val vi = poly[pi]
                        if (dense[vi * 3] != 0f || dense[vi * 3 + 1] != 0f || dense[vi * 3 + 2] != 0f) nz = true
                    }
                    if (!nz) continue
                    mIds.add(morphIds[si])
                    val dn = shapeNormalDense[si]
                    for (pi in ids) {
                        val vi = poly[pi]
                        val ndx = dn?.get(vi * 3) ?: 0f
                        val ndy = dn?.get(vi * 3 + 1) ?: 0f
                        val ndz = dn?.get(vi * 3 + 2) ?: 0f
                        if (allSkinned) {
                            mnDeltas.add(ndx); mnDeltas.add(ndy); mnDeltas.add(ndz)
                        } else {
                            val nmx = if (skinned[vi] && skinMat != null) normalMatrix(FloatArray(16).also { System.arraycopy(skinMat, vi * 16, it, 0, 16) }) else worldNm
                            mnDeltas.add(nmx[0] * ndx + nmx[1] * ndy + nmx[2] * ndz)
                            mnDeltas.add(nmx[3] * ndx + nmx[4] * ndy + nmx[5] * ndz)
                            mnDeltas.add(nmx[6] * ndx + nmx[7] * ndy + nmx[8] * ndz)
                        }
                        val dx = dense[vi * 3]
                        val dy = dense[vi * 3 + 1]
                        val dz = dense[vi * 3 + 2]
                        if (allSkinned) {
                            mDeltas.add(dx); mDeltas.add(dy); mDeltas.add(dz)
                        } else {
                            val m = if (skinned[vi] && skinMat != null) FloatArray(16).also { System.arraycopy(skinMat, vi * 16, it, 0, 16) } else geoOnly
                            mDeltas.add(m[0] * dx + m[4] * dy + m[8] * dz)
                            mDeltas.add(m[1] * dx + m[5] * dy + m[9] * dz)
                            mDeltas.add(m[2] * dx + m[6] * dy + m[10] * dz)
                        }
                    }
                }
                if (mIds.isNotEmpty()) {
                    anim.morphTargets = mIds.toIntArray()
                    anim.morphDeltas = mDeltas.toFloatArray()
                    if (hasN) anim.morphNormalDeltas = mnDeltas.toFloatArray()
                    animated = true
                }
                out.triangleFull(
                    tri, color, if (hasUv) uv else null, if (hasUv) tex else -1, cs[0], cs[1], cs[2],
                    if (hasN) nTri else null, if (hasUv) ntex else -1, if (animated) anim else null
                )
            }
            n = 0
            polygon++
        }
    }

    private companion object {
        const val TICKS = 46186158000.0
    }
}
