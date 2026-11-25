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
 * Portions ported and adapted from Blender (https://www.blender.org/),
 * Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import me.hlebkukleuski.fullfilestudio.feature.cadvector.AnimClip
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfOutput
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser
import me.hlebkukleuski.fullfilestudio.feature.cadvector.PngDecoder
import me.hlebkukleuski.fullfilestudio.feature.cadvector.TriAnim

internal class BlendFile(val bytes: ByteArray) {
    class Block(val code: String, val old: Long, val offset: Int, val len: Int, val sdna: Int, val count: Int)
    class Field(val name: String, val type: String, val offset: Int, val size: Int, val pointer: Boolean, val dims: IntArray)
    class Struct(val type: String, val size: Int, val fields: Map<String, Field>)

    val ptrSize: Int
    val bb: ByteBuffer
    val version: Int
    val blocks = ArrayList<Block>()
    private val byAddress = TreeMap<Long, Block>()
    private val idAddresses = HashSet<Long>()
    private val regionMaps = HashMap<Int, TreeMap<Long, Int>>()
    private val blockRegion = ArrayList<Int>()
    private var synthBase = 0L
    private var synthUp = true
    val structs = ArrayList<Struct>()
    val structByName = HashMap<String, Int>()

    init {
        require(bytes.size >= 12 && String(bytes, 0, 7, Charsets.US_ASCII) == "BLENDER")
        val large = bytes[7].toInt().toChar().isDigit()
        val headerSize: Int
        val little: Boolean
        if (large) {
            headerSize = String(bytes, 7, 2, Charsets.US_ASCII).toInt()
            little = bytes[12].toInt().toChar() == 'v'
            version = String(bytes, 13, 4, Charsets.US_ASCII).toIntOrNull() ?: 0
            ptrSize = 8
        } else {
            headerSize = 12
            ptrSize = if (bytes[7].toInt().toChar() == '_') 4 else 8
            little = bytes[8].toInt().toChar() == 'v'
            version = String(bytes, 9, 3, Charsets.US_ASCII).toIntOrNull() ?: 0
        }
        bb = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        var pos = headerSize
        var region = -1
        while (pos + 16 <= bytes.size) {
            val code = String(bytes, pos, 4, Charsets.ISO_8859_1).trimEnd('\u0000')
            val sdna: Int
            val old: Long
            val len: Long
            val nr: Long
            val hdr: Int
            if (large) {
                hdr = 32
                if (pos + hdr > bytes.size) break
                sdna = bb.getInt(pos + 4)
                old = bb.getLong(pos + 8)
                len = bb.getLong(pos + 16)
                nr = bb.getLong(pos + 24)
            } else {
                hdr = 16 + ptrSize
                if (pos + hdr > bytes.size) break
                len = bb.getInt(pos + 4).toLong()
                old = if (ptrSize == 8) bb.getLong(pos + 8) else bb.getInt(pos + 8).toLong() and 0xFFFFFFFFL
                sdna = bb.getInt(pos + 8 + ptrSize)
                nr = bb.getInt(pos + 12 + ptrSize).toLong()
            }
            val data = pos + hdr
            if (len < 0 || data + len > bytes.size) break
            val b = Block(code, old, data, len.toInt(), sdna, nr.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            if (code != "DATA") {
                region = blocks.size
                if (old != 0L && code.length == 2) idAddresses.add(old)
            }
            blockRegion.add(region)
            if (code == "DATA" && region >= 0 && old != 0L) regionMaps.getOrPut(region) { TreeMap() }[old] = blocks.size
            blocks.add(b)
            if (old != 0L && (code != "DATA" || !byAddress.containsKey(old))) byAddress[old] = b
            if (code == "ENDB") break
            pos = data + len.toInt()
        }
        parseSdna(blocks.first { it.code == "DNA1" })
        var hi = Long.MIN_VALUE
        var lo = Long.MAX_VALUE
        for (b in blocks) if (b.old != 0L) {
            hi = maxOf(hi, b.old + maxOf(b.len, 1))
            lo = minOf(lo, b.old)
        }
        synthUp = hi == Long.MIN_VALUE || hi < Long.MAX_VALUE - SPAN
        synthBase = if (hi == Long.MIN_VALUE) 1L shl 40 else if (synthUp) hi + 1 else lo - SPAN - 1
    }

    private fun isSynthetic(p: Long) = p != 0L && p >= synthBase && p - synthBase < SPAN

    private fun parseSdna(b: Block) {
        var p = b.offset
        fun tag(t: String) {
            require(String(bytes, p, 4, Charsets.US_ASCII) == t)
            p += 4
        }
        fun cstrings(n: Int): Array<String> = Array(n) {
            val s = p
            while (bytes[p].toInt() != 0) p++
            val r = String(bytes, s, p - s, Charsets.US_ASCII)
            p++
            r
        }
        tag("SDNA")
        tag("NAME")
        val names = cstrings(bb.getInt(p).also { p += 4 })
        p = b.offset + ((p - b.offset + 3) and 3.inv())
        tag("TYPE")
        val types = cstrings(bb.getInt(p).also { p += 4 })
        p = b.offset + ((p - b.offset + 3) and 3.inv())
        tag("TLEN")
        val tlen = IntArray(types.size) { (bb.getShort(p + it * 2).toInt() and 0xFFFF) }
        p += types.size * 2
        p = b.offset + ((p - b.offset + 3) and 3.inv())
        tag("STRC")
        val n = bb.getInt(p)
        p += 4
        for (i in 0 until n) {
            val type = bb.getShort(p).toInt() and 0xFFFF
            val count = bb.getShort(p + 2).toInt() and 0xFFFF
            p += 4
            val fields = LinkedHashMap<String, Field>()
            var off = 0
            for (f in 0 until count) {
                val ft = bb.getShort(p).toInt() and 0xFFFF
                val fn = names[bb.getShort(p + 2).toInt() and 0xFFFF]
                p += 4
                val pointer = fn.startsWith("*") || fn.startsWith("(*")
                val dims = Regex("""\[(\d+)]""").findAll(fn).map { it.groupValues[1].toInt() }.toList().toIntArray()
                val base = fn.trimStart('*', '(').substringBefore('[').substringBefore(')').trim()
                val one = if (pointer) ptrSize else tlen[ft]
                val size = one * dims.fold(1) { a, d -> a * d }
                fields.putIfAbsent(base, Field(base, types[ft], off, size, pointer, dims))
                off += size
            }
            structByName[types[type]] = structs.size
            structs.add(Struct(types[type], tlen[type], fields))
        }
    }

    private fun blockIndexAt(at: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].offset <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun resolveAt(at: Int, raw: Long): Long {
        if (raw == 0L || idAddresses.contains(raw) || blocks.isEmpty()) return raw
        val region = blockRegion.getOrElse(blockIndexAt(at)) { -1 }
        val map = regionMaps[region] ?: return raw
        val e = map.floorEntry(raw) ?: return raw
        val b = blocks[e.value]
        if (raw >= b.old + maxOf(b.len, 1)) return raw
        return synthBase + (e.value.toLong() shl 31) + (raw - b.old)
    }

    fun block(ptr: Long): Block? {
        if (ptr == 0L) return null
        if (isSynthetic(ptr)) return blocks.getOrNull(((ptr - synthBase) ushr 31).toInt())
        val e = byAddress.floorEntry(ptr) ?: return null
        val b = e.value
        return if (ptr < b.old + maxOf(b.len, 1)) b else null
    }

    fun offsetOf(ptr: Long): Int? {
        if (isSynthetic(ptr)) {
            val b = block(ptr) ?: return null
            return b.offset + ((ptr - synthBase) and 0x7FFFFFFFL).toInt()
        }
        val b = block(ptr) ?: return null
        return b.offset + (ptr - b.old).toInt()
    }

    fun struct(name: String): Struct? = structByName[name]?.let { structs[it] }

    fun activeScene(): View? {
        val glob = blocks.firstOrNull { it.code == "GLOB" }?.let { view("FileGlobal", it.offset) }
        val at = glob?.let { offsetOf(it.ptr("curscene")) }
        val sc = at?.let { a -> blocks.firstOrNull { it.code == "SC" && it.offset == a } }?.let { view("Scene", it.offset) }
        return sc ?: blocks.firstOrNull { it.code == "SC" }?.let { view("Scene", it.offset) }
    }

    fun ptr(at: Int): Long = resolveAt(at, if (ptrSize == 8) bb.getLong(at) else bb.getInt(at).toLong() and 0xFFFFFFFFL)

    inner class View(val struct: Struct, val at: Int) {
        fun has(n: String) = struct.fields.containsKey(n)
        fun byte(off: Int): Int = bytes[at + off].toInt()
        fun field(n: String) = struct.fields[n]
        fun int(n: String, def: Int = 0): Int {
            val f = struct.fields[n] ?: return def
            return when (f.size) {
                1 -> bytes[at + f.offset].toInt()
                2 -> bb.getShort(at + f.offset).toInt()
                8 -> bb.getLong(at + f.offset).toInt()
                else -> bb.getInt(at + f.offset)
            }
        }
        fun float(n: String, def: Float = 0f): Float {
            val f = struct.fields[n] ?: return def
            return when (f.type) {
                "double" -> bb.getDouble(at + f.offset).toFloat()
                "int", "int32_t" -> bb.getInt(at + f.offset).toFloat()
                "short", "int16_t" -> bb.getShort(at + f.offset).toFloat()
                "char", "int8_t" -> bytes[at + f.offset].toFloat()
                "uchar", "uint8_t" -> (bytes[at + f.offset].toInt() and 0xFF).toFloat()
                else -> bb.getFloat(at + f.offset)
            }
        }
        fun floats(n: String, count: Int): FloatArray? {
            val f = struct.fields[n] ?: return null
            if (f.pointer) return null
            return FloatArray(count) { bb.getFloat(at + f.offset + it * 4) }
        }
        fun ptr(n: String): Long {
            val f = struct.fields[n] ?: return 0L
            return this@BlendFile.ptr(at + f.offset)
        }
        fun str(n: String): String {
            val f = struct.fields[n] ?: return ""
            var e = at + f.offset
            val end = e + f.size
            val s = e
            while (e < end && bytes[e].toInt() != 0) e++
            return String(bytes, s, e - s, Charsets.UTF_8)
        }
        fun sub(n: String): View? {
            val f = struct.fields[n] ?: return null
            val s = struct(f.type) ?: return null
            return View(s, at + f.offset)
        }
        fun deref(n: String, type: String): View? {
            val o = offsetOf(ptr(n)) ?: return null
            val s = struct(type) ?: return null
            return View(s, o)
        }
    }

    fun view(type: String, at: Int): View? = struct(type)?.let { View(it, at) }

    companion object {
        private const val SPAN = 1L shl 52

        private const val MAX_DECOMPRESSED_BLEND_BYTES = 256 * 1024 * 1024

        fun decompress(raw: ByteArray): ByteArray {
            if (raw.size >= 4 && (raw[0].toInt() and 0xFF) == 0x28 && (raw[1].toInt() and 0xFF) == 0xB5 && (raw[2].toInt() and 0xFF) == 0x2F && (raw[3].toInt() and 0xFF) == 0xFD) {
                return me.hlebkukleuski.fullfilestudio.core.security.ZstdDecoder.decompress(raw, 0, raw.size, MAX_DECOMPRESSED_BLEND_BYTES)
            }
            if (raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B) {
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                var total = 0L
                java.util.zip.GZIPInputStream(raw.inputStream()).use { gis ->
                    while (true) {
                        val r = gis.read(buf)
                        if (r <= 0) break
                        total += r
                        if (total > MAX_DECOMPRESSED_BLEND_BYTES) {
                            throw IllegalStateException("Decompressed .blend exceeds size limit")
                        }
                        out.write(buf, 0, r)
                    }
                }
                return out.toByteArray()
            }
            return raw
        }
    }
}

internal class BlendLoader(private val f: BlendFile, private val out: GltfOutput) {

    private val worldCache = HashMap<Int, FloatArray>()
    private val materialCache = HashMap<Long, Pair<Int, Int>>()

    private val animator: BlendAnimator? = try {
        BlendAnimator(f).also { an -> an.shrinkTargets = { fr, ob -> targetTree(fr, ob) } }
    } catch (_: Throwable) {
        null
    }
    private val frames = ArrayList<Pair<BlendAnimator.Clip, Float>>()
    private val clipNames = ArrayList<String>()
    private var clipStarts = IntArray(0)
    private var clipLens = IntArray(0)
    private val boneFns = ArrayList<(BlendAnimator.Frame) -> FloatArray>()
    private val boneIds = HashMap<String, Int>()
    private val morphFns = ArrayList<(Int, BlendAnimator.Frame) -> Float>()
    private val morphBases = ArrayList<Float>()

    fun load(): Int {
        val a = animator
        if (a != null && a.clips.isNotEmpty()) {
            val starts = ArrayList<Int>()
            val lens = ArrayList<Int>()
            for (c in a.clips) {
                val n = (kotlin.math.floor(c.end - c.start).toInt() + 1).coerceIn(1, 20_000)
                if (frames.size + n > 60_000) break
                starts.add(frames.size)
                lens.add(n)
                clipNames.add(c.name)
                for (k in 0 until n) frames.add(c to c.start + k)
            }
            clipStarts = starts.toIntArray()
            clipLens = lens.toIntArray()
        }
        var objects = 0
        for (b in f.blocks) {
            if (out.truncated) break
            if (b.code != "OB") continue
            val ob = f.view("Object", b.offset) ?: continue
            if (ob.int("type") != 1) continue
            if (hidden(ob)) continue
            val meBlock = f.offsetOf(ob.ptr("data")) ?: continue
            val me = f.view("Mesh", meBlock) ?: continue
            val world = animator?.staticFrame?.storedWorld(ob) ?: world(ob, 0)
            emitMesh(ob, me, world)
            objects++
        }
        try {
            finishAnimation()
        } catch (_: Throwable) {
        }
        return objects
    }

    private val sceneLayers: Int? by lazy {
        if (f.version >= 280) null else f.activeScene()?.takeIf { it.has("lay") }?.int("lay")
    }

    private val sceneObjects: Set<Int>? by lazy {
        try {
            visibleInScene()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun items(lb: BlendFile.View?, type: String): List<BlendFile.View> {
        lb ?: return emptyList()
        val out = ArrayList<BlendFile.View>()
        var p = lb.ptr("first")
        val seen = HashSet<Long>()
        while (p != 0L && seen.add(p) && out.size < 1_000_000) {
            val at = f.offsetOf(p) ?: break
            val v = f.view(type, at) ?: break
            out.add(v)
            p = v.ptr("next")
        }
        return out
    }

    private fun visibleInScene(): Set<Int>? {
        val sc = f.activeScene() ?: return null
        val res = HashSet<Int>()
        if (f.version < 280 || !sc.has("view_layers")) {
            val bases = items(sc.sub("base"), "Base")
            if (bases.isEmpty()) return null
            for (b in bases) f.offsetOf(b.ptr("object"))?.let { res.add(it) }
            return res
        }
        val layers = items(sc.sub("view_layers"), "ViewLayer")
        if (layers.isEmpty()) return null
        val glob = f.blocks.firstOrNull { it.code == "GLOB" }?.let { f.view("FileGlobal", it.offset) }
        val curLayer = glob?.let { f.offsetOf(it.ptr("cur_view_layer")) }
        val layer = layers.firstOrNull { it.at == curLayer } ?: layers.firstOrNull { it.int("flag") and 1 != 0 } ?: layers.first()
        val hiddenBases = HashSet<Int>()
        for (b in items(layer.sub("object_bases"), "Base")) {
            val o = f.offsetOf(b.ptr("object")) ?: continue
            if (b.int("flag") and (1 shl 8) != 0) hiddenBases.add(o)
        }
        val visible = HashSet<Int>()
        val guard = HashSet<Int>()
        fun walk(lc: BlendFile.View, parentVisible: Boolean) {
            if (!guard.add(lc.at)) return
            val flag = lc.int("flag")
            if (flag and (1 shl 4) != 0) return
            val coll = f.offsetOf(lc.ptr("collection"))?.let { f.view("Collection", it) }
            val cflag = coll?.int("flag") ?: 0
            val vis = parentVisible && flag and (1 shl 7) == 0 && cflag and ((1 shl 0) or (1 shl 3)) == 0
            if (vis && coll != null) for (co in items(coll.sub("gobject"), "CollectionObject")) f.offsetOf(co.ptr("ob"))?.let { visible.add(it) }
            for (child in items(lc.sub("layer_collections"), "LayerCollection")) walk(child, vis)
        }
        val top = items(layer.sub("layer_collections"), "LayerCollection")
        if (top.isEmpty()) return null
        for (lc in top) walk(lc, true)
        for (o in visible) if (o !in hiddenBases) res.add(o)
        return res
    }

    private fun hidden(ob: BlendFile.View): Boolean {
        sceneObjects?.let { if (ob.at !in it) return true }
        if (ob.int("restrictflag") and 5 != 0) return true
        val dt = ob.int("dt", 3)
        if (dt == 1 || dt == 2) return true
        val lay = sceneLayers
        if (lay != null && lay != 0 && ob.has("lay") && ob.int("lay") and lay == 0) return true
        return false
    }

    private fun pack(m: FloatArray, dst: FloatArray, at: Int) {
        dst[at] = m[0]; dst[at + 1] = m[1]; dst[at + 2] = m[2]
        dst[at + 3] = m[4]; dst[at + 4] = m[5]; dst[at + 5] = m[6]
        dst[at + 6] = m[8]; dst[at + 7] = m[9]; dst[at + 8] = m[10]
        dst[at + 9] = m[12]; dst[at + 10] = m[13]; dst[at + 11] = m[14]
    }

    private fun budgetOk(extra: Int = 1) = (boneFns.size + extra).toLong() * frames.size * 12 <= 8_000_000L

    private fun addBone(key: String, matrix: (BlendAnimator.Frame) -> FloatArray): Int {
        boneIds[key]?.let { return it }
        if (frames.isEmpty() || !budgetOk()) return -1
        boneFns.add(matrix)
        boneIds[key] = boneFns.size - 1
        return boneFns.size - 1
    }

    private fun skinMatrix(fr: BlendAnimator.Frame, ob: BlendFile.View, arm: BlendFile.View, bone: String): FloatArray {
        val armW = fr.world(arm)
        val deform = deformMatrix(fr, arm, bone)?.first ?: return fr.world(ob)
        return GltfParser.mul(GltfParser.mul(armW, GltfParser.mul(deform, BlendAnimator.invert(armW))), fr.world(ob))
    }

    private fun finishAnimation() {
        val a = animator ?: return
        val bones = boneFns.size
        val morphs = morphFns.size
        if (frames.isEmpty() || (bones == 0 && morphs == 0)) return
        val n = frames.size
        val matrices = FloatArray(n * bones * 12)
        val weights = if (morphs > 0) FloatArray(n * morphs) else null
        for ((k, ref) in frames.withIndex()) {
            val fr = a.Frame(ref.first, ref.second)
            for (b in 0 until bones) pack(boneFns[b](fr), matrices, (k * bones + b) * 12)
            if (weights != null) for (t in 0 until morphs) weights[k * morphs + t] = morphFns[t](k, fr)
        }
        out.animation(AnimClip(
            animator.fps, n, bones, matrices, morphs, weights, if (morphs > 0) morphBases.toFloatArray() else null,
            clipNames.toList(), clipStarts, clipLens
        ))
    }

    internal sealed class Deform {
        class Armature(val arm: BlendFile.View, val flag: Int, val mask: String, val invert: Boolean, val multi: Boolean = false) : Deform()
        class Lattice(val lat: BlendFile.View, val mask: String, val invert: Boolean, val strength: Float) : Deform()
        class CurveDef(val curve: BlendFile.View, val mask: String, val invert: Boolean, val axis: Int) : Deform()
        class Hook(
            val target: BlendFile.View, val sub: String, val parentInv: FloatArray, val cent: FloatArray, val falloff: Float, val falloffType: Int,
            val force: Float, val flag: Int, val mask: String, val indices: IntArray?, val curve: BlendCurveMap? = null
        ) : Deform()
        class MeshDeform(val cage: BlendFile.View, val data: BlendFile.View, val mask: String, val invert: Boolean) : Deform()
        class Mod(val type: Int, val md: BlendFile.View, val ctrl: BlendFile.View?, val mask: String, val invert: Boolean, val ctrl2: BlendFile.View? = null, val curve: BlendCurveMap? = null) : Deform()
        class Constructive(val type: Int, val md: BlendFile.View, val ctrl: BlendFile.View? = null, val ctrl2: BlendFile.View? = null, val extra: List<BlendFile.View> = emptyList()) : Deform()
    }

    internal fun stackOf(ob: BlendFile.View): List<Deform> = deformStack(ob)

    internal fun forcedWorld(ob: BlendFile.View): FloatArray? = animator?.let { it.Frame(null, it.staticFrame.frame, true).world(ob) }

    internal fun groupWeights(ob: BlendFile.View, name: String): FloatArray? {
        val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val n = (meshPositions(me, vdata) ?: return null).size / 3
        return maskWeights(groupNames(ob, me), dverts(me, vdata, n), n, name, false)
    }

    private fun deformStack(ob: BlendFile.View): List<Deform> {
        val a = animator ?: return emptyList()
        val res = ArrayList<Deform>()
        val parent = f.offsetOf(ob.ptr("parent"))?.let { a.owner(it) }
        if (parent != null && (ob.int("partype") and 15) == 4) {
            when (parent.int("type")) {
                25 -> res.add(Deform.Armature(parent, a.armatureData(parent)?.int("deformflag", 1) ?: 1, "", false))
                22 -> res.add(Deform.Lattice(parent, "", false, 1f))
                2 -> res.add(Deform.CurveDef(parent, "", false, ob.int("trackflag", 1) + 1))
            }
        }
        var p = ob.sub("modifiers")?.ptr("first") ?: 0L
        var guard = 0
        while (p != 0L && guard++ < 256) {
            val at = f.offsetOf(p) ?: break
            val md = f.view("ModifierData", at) ?: break
            p = md.ptr("next")
            if (md.int("mode") and 1 == 0) continue
            when (md.int("type")) {
                8 -> f.view("ArmatureModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    if (target != null && target.int("type") == 25) {
                        val flag = m.int("deformflag", 1)
                        res.add(Deform.Armature(target, flag, m.str("defgrp_name"), flag and 16 != 0, m.int("multi") != 0))
                    }
                }
                2 -> f.view("LatticeModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    if (target != null && target.int("type") == 22) res.add(Deform.Lattice(target, m.str("name"), m.int("flag") and 1 != 0, m.float("strength", 1f)))
                }
                3 -> f.view("CurveModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    if (target != null && target.int("type") == 2) res.add(Deform.CurveDef(target, m.str("name"), m.int("flag") and 1 != 0, m.int("defaxis", 1)))
                }
                9 -> f.view("HookModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    if (target != null) {
                        val n = m.int("indexar_num", m.int("totindex"))
                        val idxAt = f.offsetOf(m.ptr("indexar"))
                        val indices = if (idxAt != null && n > 0) IntArray(n) { f.bb.getInt(idxAt + it * 4) } else null
                        res.add(Deform.Hook(
                            target, m.str("subtarget"), m.floats("parentinv", 16) ?: GltfParser.IDENTITY, m.floats("cent", 3) ?: FloatArray(3),
                            m.float("falloff"), if (m.has("falloff_type")) m.int("falloff_type") else 5, m.float("force", 1f), m.int("flag"), m.str("name"), indices,
                            curveMap(m.ptr("curfalloff"))
                        ))
                    }
                }
                1 -> f.view("SubsurfModifierData", at)?.let { m -> res.add(Deform.Constructive(1, m)) }
                12 -> f.view("ArrayModifierData", at)?.let { m ->
                    fun obj(n: String) = f.offsetOf(m.ptr(n))?.let { a.owner(it) }?.takeIf { it.at != ob.at }
                    val offOb = if (m.int("offset_type") and 4 != 0) obj("offset_ob") else null
                    val curve = if (m.int("fit_type") == 2) obj("curve_ob")?.takeIf { it.int("type") == 2 } else null
                    res.add(Deform.Constructive(12, m, offOb, curve, listOfNotNull(obj("start_cap"), obj("end_cap")).filter { it.int("type") == 1 }))
                }
                33 -> f.view("SolidifyModifierData", at)?.let { m -> res.add(Deform.Constructive(33, m)) }
                44 -> f.view("TriangulateModifierData", at)?.let { m -> res.add(Deform.Constructive(44, m)) }
                55 -> f.view("WeldModifierData", at)?.let { m -> res.add(Deform.Constructive(55, m)) }
                4 -> f.view("BuildModifierData", at)?.let { m -> res.add(Deform.Constructive(4, m)) }
                34 -> f.view("ScrewModifierData", at)?.let { m ->
                    val axisOb = f.offsetOf(m.ptr("ob_axis"))?.let { a.owner(it) }?.takeIf { it.at != ob.at }
                    res.add(Deform.Constructive(34, m, axisOb))
                }
                54 -> f.view("WeightedNormalModifierData", at)?.let { m -> res.add(Deform.Constructive(54, m)) }
                27 -> f.view("MaskModifierData", at)?.let { m ->
                    val arm = if (m.int("mode") == 1) f.offsetOf(m.ptr("ob_arm"))?.let { a.owner(it) }?.takeIf { it.int("type") == 25 } else null
                    res.add(Deform.Constructive(27, m, arm))
                }
                13 -> f.view("EdgeSplitModifierData", at)?.let { m -> res.add(Deform.Constructive(13, m)) }
                48 -> f.view("WireframeModifierData", at)?.let { m -> res.add(Deform.Constructive(48, m)) }
                24 -> f.view("BevelModifierData", at)?.let { m -> res.add(Deform.Constructive(24, m)) }
                42 -> f.view("SkinModifierData", at)?.let { m -> res.add(Deform.Constructive(42, m)) }
                11 -> f.view("BooleanModifierData", at)?.let { m ->
                    val bFlag = m.int("flag")
                    val ops = ArrayList<BlendFile.View>()
                    val seenOps = HashSet<Int>()
                    if ((bFlag and BlendBooleanModifier.FLAG_COLLECTION) != 0 && m.ptr("collection") != 0L) {
                        val seenColls = HashSet<Int>()
                        fun collectColl(collPtr: Long) {
                            val cAt = f.offsetOf(collPtr) ?: return
                            if (!seenColls.add(cAt)) return
                            val coll = f.view("Collection", cAt) ?: return
                            for (co in items(coll.sub("gobject"), "CollectionObject")) {
                                val oAt = f.offsetOf(co.ptr("ob")) ?: continue
                                val oView = a.owner(oAt) ?: continue
                                if (oView.at != ob.at && oView.int("type") == 1 && seenOps.add(oView.at)) ops.add(oView)
                            }
                            for (ch in items(coll.sub("children"), "CollectionChild")) {
                                collectColl(ch.ptr("collection"))
                            }
                        }
                        collectColl(m.ptr("collection"))
                    } else {
                        f.offsetOf(m.ptr("object"))?.let { a.owner(it) }?.takeIf { it.at != ob.at && it.int("type") == 1 }?.let { ops.add(it) }
                    }
                    res.add(Deform.Constructive(11, m, extra = ops))
                }
                6 -> f.view("DecimateModifierData", at)?.let { m -> res.add(Deform.Constructive(6, m)) }
                29 -> f.view("MultiresModifierData", at)?.let { m -> res.add(Deform.Constructive(29, m)) }
                57 -> f.view("NodesModifierData", at)?.let { m ->
                    val ng = f.offsetOf(m.ptr("node_group"))?.let { f.view("bNodeTree", it) }
                    val nm = ng?.sub("id")?.str("name")?.removePrefix("NT") ?: ""
                    if (nm == "Smooth by Angle" || nm.startsWith("Smooth by Angle.")) res.add(Deform.Constructive(57, m))
                }
                5 -> f.view("MirrorModifierData", at)?.let { m ->
                    val mob = f.offsetOf(m.ptr("mirror_ob"))?.let { a.owner(it) }?.takeIf { it.at != ob.at }
                    res.add(Deform.Constructive(5, m, mob))
                }
                18 -> f.view("MeshDeformModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    if (target != null && target.int("type") == 1 && m.ptr("bindcagecos") != 0L) res.add(Deform.MeshDeform(target, m, m.str("defgrp_name"), m.int("flag") and 1 != 0))
                }
                17 -> f.view("CastModifierData", at)?.let { m ->
                    val ctrl = f.offsetOf(m.ptr("object"))?.let { a.owner(it) }
                    res.add(Deform.Mod(17, m, ctrl, m.str("defgrp_name"), m.int("flag") and 1 != 0))
                }
                28 -> f.view("SimpleDeformModifierData", at)?.let { m ->
                    val origin = f.offsetOf(m.ptr("origin"))?.let { a.owner(it) }?.takeIf { it.at != ob.at }
                    res.add(Deform.Mod(28, m, origin, m.str("vgroup_name"), m.int("flag") and 1 != 0))
                }
                16 -> f.view("SmoothModifierData", at)?.let { m -> res.add(Deform.Mod(16, m, null, m.str("defgrp_name"), m.int("flag") and 1 != 0)) }
                47 -> f.view("LaplacianDeformModifierData", at)?.let { m ->
                    if (m.int("flag") and 1 != 0 && m.ptr("vertexco") != 0L && m.str("anchor_grp_name").isNotEmpty()) res.add(Deform.Mod(47, m, null, m.str("anchor_grp_name"), m.int("flag") and 2 != 0))
                }
                53 -> f.view("SurfaceDeformModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("target"))?.let { a.owner(it) }?.takeIf { it.at != ob.at && it.int("type") == 1 }
                    if (target != null && m.int("flags") and 1 != 0 && m.ptr("verts") != 0L) res.add(Deform.Mod(53, m, target, m.str("defgrp_name"), m.int("flags") and 2 != 0))
                }
                25 -> f.view("ShrinkwrapModifierData", at)?.let { m ->
                    val target = f.offsetOf(m.ptr("target"))?.let { a.owner(it) }?.takeIf { it.at != ob.at && it.int("type") == 1 }
                    val aux = f.offsetOf(m.ptr("auxTarget"))?.let { a.owner(it) }?.takeIf { it.at != ob.at && it.int("type") == 1 }
                    if (target != null) res.add(Deform.Mod(25, m, target, m.str("vgroup_name"), m.int("shrinkOpts") and 64 != 0, aux))
                }
                35 -> f.view("WarpModifierData", at)?.let { m ->
                    val from = f.offsetOf(m.ptr("object_from"))?.let { a.owner(it) }
                    val to = f.offsetOf(m.ptr("object_to"))?.let { a.owner(it) }
                    if (from != null && to != null) res.add(Deform.Mod(35, m, from, m.str("defgrp_name"), m.int("flag") and 2 != 0, to, curveMap(m.ptr("curfalloff"))))
                }
                51 -> f.view("CorrectiveSmoothModifierData", at)?.let { m -> res.add(Deform.Mod(51, m, null, m.str("defgrp_name"), m.int("flag") and 1 != 0)) }
                43 -> f.view("LaplacianSmoothModifierData", at)?.let { m -> res.add(Deform.Mod(43, m, null, m.str("defgrp_name"), m.int("flag") and (1 shl 6) != 0)) }
                7 -> f.view("WaveModifierData", at)?.let { m ->
                    val center = f.offsetOf(m.ptr("objectcenter"))?.let { a.owner(it) }
                    res.add(Deform.Mod(7, m, center, m.str("defgrp_name"), m.int("flag") and 1 != 0))
                }
                14 -> f.view("DisplaceModifierData", at)?.let { m ->
                    if (!(m.ptr("texture") == 0L && m.int("direction") == 4)) res.add(Deform.Mod(14, m, null, m.str("defgrp_name"), m.int("flag") and 1 != 0))
                }
            }
        }
        return res
    }

    internal class Skin(val arm: BlendFile.View, val names: List<String>, val vJ: Array<IntArray>, val vW: Array<FloatArray>, val k: Int, val dq: Boolean)

    private fun groupNames(ob: BlendFile.View, me: BlendFile.View): List<String> {
        val a = animator ?: return emptyList()
        val groupList = me.sub("vertex_group_names")?.takeIf { it.ptr("first") != 0L } ?: ob.sub("defbase")
        return a.listItems(groupList, "bDeformGroup").map { it.str("name") }
    }

    private fun dverts(me: BlendFile.View, vdata: List<Layer>, vCount: Int): Array<List<Pair<Int, Float>>>? {
        val dvSt = f.struct("MDeformVert") ?: return null
        val dwSt = f.struct("MDeformWeight") ?: return null
        val dvAt = f.offsetOf(me.ptr("dvert")) ?: vdata.firstOrNull { it.structType == "MDeformVert" }?.dataAt ?: return null
        val dwOff = dvSt.fields["dw"]?.offset ?: 0
        val totOff = dvSt.fields["totweight"]?.offset ?: f.ptrSize
        val defOff = dwSt.fields["def_nr"]?.offset ?: 0
        val wOff = dwSt.fields["weight"]?.offset ?: 4
        return Array(vCount) { v ->
            val at = dvAt + v * dvSt.size
            if (at + dvSt.size > f.bytes.size) return@Array emptyList()
            val tot = f.bb.getInt(at + totOff).coerceIn(0, 4096)
            val dwAt = f.offsetOf(f.ptr(at + dwOff)) ?: return@Array emptyList()
            val list = ArrayList<Pair<Int, Float>>(tot)
            for (k in 0 until tot) {
                val wa = dwAt + k * dwSt.size
                if (wa + dwSt.size > f.bytes.size) break
                list.add(f.bb.getInt(wa + defOff) to f.bb.getFloat(wa + wOff))
            }
            list
        }
    }

    private fun maskWeights(groups: List<String>, dv: Array<List<Pair<Int, Float>>>?, vCount: Int, name: String, invert: Boolean): FloatArray? {
        if (name.isEmpty()) return null
        val gi = groups.indexOf(name)
        if (gi < 0 || dv == null) return if (invert) FloatArray(vCount) { 1f } else null
        return FloatArray(vCount) { v ->
            val w = dv[v].firstOrNull { it.first == gi }?.second ?: 0f
            if (invert) 1f - w else w
        }
    }

    private fun distFactor(p: FloatArray, b: BlendAnimator.BoneRest): Float {
        val h = b.head
        val t = b.tail
        var bx = t[0] - h[0]; var by = t[1] - h[1]; var bz = t[2] - h[2]
        val l = kotlin.math.sqrt(bx * bx + by * by + bz * bz)
        if (l > 0f) { bx /= l; by /= l; bz /= l }
        val px = p[0] - h[0]; val py = p[1] - h[1]; val pz = p[2] - h[2]
        val a = bx * px + by * py + bz * pz
        val hsqr = px * px + py * py + pz * pz
        val distSq: Float
        val rad: Float
        if (a < 0f) {
            distSq = hsqr
            rad = b.radHead
        } else if (a > l) {
            val qx = p[0] - t[0]; val qy = p[1] - t[1]; val qz = p[2] - t[2]
            distSq = qx * qx + qy * qy + qz * qz
            rad = b.radTail
        } else {
            distSq = hsqr - a * a
            rad = if (l != 0f) (a / l) * b.radTail + (1f - a / l) * b.radHead else b.radHead
        }
        if (distSq < rad * rad) return 1f
        val lim = (rad + b.dist) * (rad + b.dist)
        if (b.dist == 0f || distSq >= lim) return 0f
        val d = kotlin.math.sqrt(distSq) - rad
        return 1f - (d * d) / (b.dist * b.dist)
    }

    internal fun skinData(ob: BlendFile.View, me: BlendFile.View, vdata: List<Layer>, local: FloatArray, mod: Deform.Armature): Skin? =
        skinFrom(ob, groupNames(ob, me), { dverts(me, vdata, local.size / 3) }, local, mod)

    private fun skinFrom(ob: BlendFile.View, groups: List<String>, dvFn: () -> Array<List<Pair<Int, Float>>>?, local: FloatArray, mod: Deform.Armature): Skin? {
        val a = animator ?: return null
        val arm = mod.arm
        val vCount = local.size / 3
        val rest = a.bones(arm)
        val deform = rest.values.filter { it.flag and (1 shl 12) == 0 }
        if (deform.isEmpty()) return null
        val useGroups = mod.flag and 1 != 0
        val useEnvelope = mod.flag and 2 != 0
        val dv = if (useGroups || mod.mask.isNotEmpty()) dvFn() else null
        val mask = maskWeights(groups, dv, vCount, mod.mask, mod.invert)
        val st = a.staticFrame
        val pre = GltfParser.mul(BlendAnimator.invert(st.world(arm)), st.world(ob))
        val names = ArrayList<String>()
        val index = HashMap<String, Int>()
        fun idx(n: String) = index.getOrPut(n) { names.add(n); names.size - 1 }
        val curved = HashMap<String, BBoneCurvedMap?>()
        fun mapper(b: BlendAnimator.BoneRest): BBoneCurvedMap? = curved.getOrPut(b.name) {
            if (b.view?.int("bbone_mapping_mode") != 1) null
            else st.bboneRestSegments(arm, b.name)?.let { BBoneCurvedMap(b.armMat, it) }
        }
        val groupBone = Array(groups.size) { g -> rest[groups[g]]?.takeIf { it.flag and (1 shl 12) == 0 } }
        val rawJ = arrayOfNulls<IntArray>(vCount)
        val rawW = arrayOfNulls<FloatArray>(vCount)
        val acc = HashMap<Int, Float>()
        var maxK = 1
        var any = false
        for (v in 0 until vCount) {
            acc.clear()
            val mw = mask?.get(v) ?: 1f
            val co = BlendAnimator.transformPoint(pre, local[v * 3], local[v * 3 + 1], local[v * 3 + 2])
            var deformed = false
            var contrib = 0f
            if (useGroups && dv != null) {
                for ((g, w0) in dv[v]) {
                    val b = groupBone.getOrNull(g) ?: continue
                    deformed = true
                    var w = w0
                    if (b.flag and (1 shl 11) != 0) w *= distFactor(co, b)
                    if (w == 0f) continue
                    addInfluence(b, w, co, acc, ::idx, mapper(b))
                    contrib += w
                }
            }
            if (useEnvelope && !deformed) {
                for (b in deform) {
                    val fac = distFactor(co, b) * b.weight
                    if (fac <= 0f) continue
                    addInfluence(b, fac, co, acc, ::idx, mapper(b))
                    contrib += fac
                }
            }
            if (contrib <= 0.0001f || mw == 0f) continue
            val n = acc.size + if (mw < 1f) 1 else 0
            val j = IntArray(n)
            val w = FloatArray(n)
            var q = 0
            for ((bi, bw) in acc.entries.sortedByDescending { it.value }) {
                j[q] = bi
                w[q] = bw / contrib * mw
                q++
            }
            if (mw < 1f) {
                j[q] = -1
                w[q] = 1f - mw
            }
            rawJ[v] = j
            rawW[v] = w
            maxK = maxOf(maxK, n)
            any = true
        }
        if (!any) return null
        val k = maxK.coerceIn(1, 64)
        val meshIndex = names.size
        val vJ = Array(vCount) { v -> rawJ[v]?.let { j -> IntArray(j.size) { if (j[it] < 0) meshIndex else j[it] } } ?: intArrayOf(meshIndex) }
        val vW = Array(vCount) { v -> rawW[v] ?: floatArrayOf(1f) }
        return Skin(arm, names, vJ, vW, k, mod.flag and 4 != 0)
    }

    private fun addInfluence(b: BlendAnimator.BoneRest, w: Float, co: FloatArray, acc: HashMap<Int, Float>, idx: (String) -> Int, curved: BBoneCurvedMap? = null) {
        val segs = b.segments
        if (segs <= 1 || b.length <= 0f) {
            val i = idx(b.name)
            acc[i] = (acc[i] ?: 0f) + w
            return
        }
        val headTail = curved?.headTail(co) ?: (BlendAnimator.transformPoint(BlendAnimator.invert(b.armMat), co[0], co[1], co[2])[1] / b.length)
        val pos = headTail.coerceIn(0f, 1f) * segs
        val index = kotlin.math.floor(pos).toInt().coerceIn(0, segs - 1)
        val blend = (pos - index).coerceIn(0f, 1f)
        if (1f - blend > 0f) {
            val i = idx("${b.name}\u0001$index")
            acc[i] = (acc[i] ?: 0f) + w * (1f - blend)
        }
        if (blend > 0f) {
            val i = idx("${b.name}\u0001${index + 1}")
            acc[i] = (acc[i] ?: 0f) + w * blend
        }
    }

    internal fun deformMatrix(fr: BlendAnimator.Frame, arm: BlendFile.View, name: String): Pair<FloatArray, FloatArray>? {
        val a = animator ?: return null
        val sep = name.indexOf('\u0001')
        if (sep >= 0) {
            val bone = name.substring(0, sep)
            val j = name.substring(sep + 1).toIntOrNull() ?: return null
            val bb = fr.bboneDeform(arm, bone)
            if (bb != null && j < bb.first.size) return bb.first[j] to bb.second[j]
            val restM = a.boneRestMatrix(arm, bone) ?: return null
            val pose = fr.bonePose(arm, bone) ?: return null
            return GltfParser.mul(pose, BlendAnimator.invert(restM)) to restM
        }
        val restM = a.boneRestMatrix(arm, name) ?: return null
        val pose = fr.bonePose(arm, name) ?: return null
        return GltfParser.mul(pose, BlendAnimator.invert(restM)) to restM
    }

    internal fun dqSplit(fr: BlendAnimator.Frame, arm: BlendFile.View, bone: String): Pair<FloatArray, FloatArray> {
        val (d, restM) = deformMatrix(fr, arm, bone) ?: return GltfParser.IDENTITY to GltfParser.IDENTITY
        val restInv = BlendAnimator.invert(restM)
        val baseRS = GltfParser.mul(d, restM)
        var ds = 0f
        for (c in 0 until 3) {
            val l = BlendAnimator.axisLength(baseRS, c) - 1f
            ds += l * l
        }
        val det = d[0] * (d[5] * d[10] - d[9] * d[6]) - d[4] * (d[1] * d[10] - d[9] * d[2]) + d[8] * (d[1] * d[6] - d[5] * d[2])
        if (det >= 0f && ds <= 1e-8f) return d to GltfParser.IDENTITY
        val tmp = baseRS.copyOf()
        fun norm(c: Int) {
            val l = BlendAnimator.axisLength(tmp, c)
            if (l > 0f) for (r in 0 until 3) tmp[c * 4 + r] /= l
        }
        fun cross(dst: Int, x: Int, y: Int) {
            val ax = tmp[x * 4]; val ay = tmp[x * 4 + 1]; val az = tmp[x * 4 + 2]
            val bx = tmp[y * 4]; val by = tmp[y * 4 + 1]; val bz = tmp[y * 4 + 2]
            tmp[dst * 4] = ay * bz - az * by
            tmp[dst * 4 + 1] = az * bx - ax * bz
            tmp[dst * 4 + 2] = ax * by - ay * bx
        }
        norm(1)
        val dot10 = tmp[4] * tmp[0] + tmp[5] * tmp[1] + tmp[6] * tmp[2]
        if (dot10 < 1f) {
            cross(2, 0, 1)
            norm(2)
            cross(0, 1, 2)
        } else {
            cross(0, 1, 2)
            norm(0)
            cross(2, 0, 1)
        }
        norm(0)
        norm(2)
        val q = BlendAnimator.matToQuat(tmp)
        val baseR = BlendAnimator.quatMatrix(q)
        baseR[12] = baseRS[12]; baseR[13] = baseRS[13]; baseR[14] = baseRS[14]
        val r = GltfParser.mul(baseR, restInv)
        val s = GltfParser.mul(BlendAnimator.invert(baseR), baseRS)
        return r to GltfParser.mul(restM, GltfParser.mul(s, restInv))
    }

    private fun skinPositions(fr: BlendAnimator.Frame, ob: BlendFile.View, skin: Skin, local: FloatArray): FloatArray {
        val vCount = local.size / 3
        val out = FloatArray(local.size)
        if (!skin.dq) {
            val mats = skin.names.map { skinMatrix(fr, ob, skin.arm, it) } + listOf(fr.world(ob))
            for (v in 0 until vCount) {
                val x = local[v * 3]; val y = local[v * 3 + 1]; val z = local[v * 3 + 2]
                val j = skin.vJ[v]
                val w = skin.vW[v]
                for (q in j.indices) {
                    val m = mats[j[q]]
                    out[v * 3] += w[q] * (m[0] * x + m[4] * y + m[8] * z + m[12])
                    out[v * 3 + 1] += w[q] * (m[1] * x + m[5] * y + m[9] * z + m[13])
                    out[v * 3 + 2] += w[q] * (m[2] * x + m[6] * y + m[10] * z + m[14])
                }
            }
            return out
        }
        val armW = fr.world(skin.arm)
        val pre = GltfParser.mul(BlendAnimator.invert(armW), fr.world(ob))
        val pairs = skin.names.map { dqSplit(fr, skin.arm, it) } + listOf(GltfParser.IDENTITY to GltfParser.IDENTITY)
        val quats = pairs.map { (r, _) ->
            val q = BlendAnimator.matToQuat(BlendAnimator.normalizedAxes(r))
            val l = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            val w = q[0] / l; val x = q[1] / l; val y = q[2] / l; val z = q[3] / l
            val tx = r[12]; val ty = r[13]; val tz = r[14]
            floatArrayOf(
                w, x, y, z,
                0.5f * (-tx * x - ty * y - tz * z), 0.5f * (tx * w + ty * z - tz * y),
                0.5f * (-tx * z + ty * w + tz * x), 0.5f * (tx * y - ty * x + tz * w)
            )
        }
        val acc = FloatArray(8)
        val idQ = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        for (v in 0 until vCount) {
            acc.fill(0f)
            val j = skin.vJ[v]
            val wv = skin.vW[v]
            var sw = 0f
            val p0 = BlendAnimator.transformPoint(pre, local[v * 3], local[v * 3 + 1], local[v * 3 + 2])
            for (q in j.indices) {
                val d = quats.getOrNull(j[q]) ?: idQ
                val s = pairs[j[q]].second
                val m = d.copyOf()
                if (s !== GltfParser.IDENTITY) {
                    val sp = BlendAnimator.transformPoint(s, p0[0], p0[1], p0[2])
                    val d0 = sp[0] - p0[0]; val d1 = sp[1] - p0[1]; val d2 = sp[2] - p0[2]
                    m[4] -= 0.5f * (m[1] * d0 + m[2] * d1 + m[3] * d2)
                    m[5] += 0.5f * (m[0] * d0 + m[2] * d2 - m[3] * d1)
                    m[6] += 0.5f * (m[0] * d1 + m[3] * d0 - m[1] * d2)
                    m[7] += 0.5f * (m[0] * d2 + m[1] * d1 - m[2] * d0)
                }
                val dot = acc[0] * m[0] + acc[1] * m[1] + acc[2] * m[2] + acc[3] * m[3]
                val ww = if (dot < 0f) -wv[q] else wv[q]
                for (i in 0 until 8) acc[i] += ww * m[i]
                sw += wv[q]
            }
            if (sw <= 0f) continue
            val ql = kotlin.math.sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2] + acc[3] * acc[3]).takeIf { it > 0f } ?: 1f
            val dqm = BlendAnimator.quatMatrix(floatArrayOf(acc[0] / ql, acc[1] / ql, acc[2] / ql, acc[3] / ql))
            val w = acc[0] / ql; val x = acc[1] / ql; val y = acc[2] / ql; val z = acc[3] / ql
            val dw = acc[4] / ql; val dx = acc[5] / ql; val dy = acc[6] / ql; val dz = acc[7] / ql
            dqm[12] = 2f * (-dw * x + dx * w - dy * z + dz * y)
            dqm[13] = 2f * (-dw * y + dx * z + dy * w - dz * x)
            dqm[14] = 2f * (-dw * z - dx * y + dy * x + dz * w)
            val p2 = BlendAnimator.transformPoint(dqm, p0[0], p0[1], p0[2])
            val p3 = BlendAnimator.transformPoint(armW, p2[0], p2[1], p2[2])
            out[v * 3] = p3[0]; out[v * 3 + 1] = p3[1]; out[v * 3 + 2] = p3[2]
        }
        return out
    }

    private fun addBonePair(key: String, first: (BlendAnimator.Frame) -> FloatArray, second: (BlendAnimator.Frame) -> FloatArray): Int {
        boneIds[key]?.let { return it }
        if (frames.isEmpty() || !budgetOk(2)) return -1
        boneFns.add(first)
        boneFns.add(second)
        boneIds[key] = boneFns.size - 2
        return boneFns.size - 2
    }

    private var bakedFloats = 0L

    private fun keyCurve(t: Float, type: Int): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        return when (type) {
            0 -> floatArrayOf(0f, 1f - t, t, 0f)
            2 -> floatArrayOf(
                -0.16666666f * t3 + 0.5f * t2 - 0.5f * t + 0.16666666f,
                0.5f * t3 - t2 + 0.66666666f,
                -0.5f * t3 + 0.5f * t2 + 0.5f * t + 0.16666666f,
                0.16666666f * t3
            )
            else -> {
                val fc = if (type == 3) 0.5f else 0.71f
                floatArrayOf(
                    -fc * t3 + 2f * fc * t2 - fc * t,
                    (2f - fc) * t3 + (fc - 3f) * t2 + 1f,
                    (fc - 2f) * t3 + (3f - 2f * fc) * t2 + fc * t,
                    fc * t3 - fc * t2
                )
            }
        }
    }

    private fun latticePoints(fr: BlendAnimator.Frame, lt: BlendFile.View, n: Int): FloatArray? {
        val a = animator ?: return null
        val bp = f.struct("BPoint") ?: return null
        val at = f.offsetOf(lt.ptr("def")) ?: return null
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = at + i * bp.size
            if (p + 12 > f.bytes.size) return null
            pts[i * 3] = f.bb.getFloat(p); pts[i * 3 + 1] = f.bb.getFloat(p + 4); pts[i * 3 + 2] = f.bb.getFloat(p + 8)
        }
        val keyAt = f.offsetOf(lt.ptr("key")) ?: return pts
        val key = f.view("Key", keyAt) ?: return pts
        if (key.int("type") != 1) return pts
        val blocks = a.listItems(key.sub("block"), "KeyBlock")
        fun data(kb: BlendFile.View): FloatArray? {
            if (kb.int("totelem") != n) return null
            val d = f.offsetOf(kb.ptr("data")) ?: return null
            if (d + n * 12 > f.bytes.size) return null
            return FloatArray(n * 3) { f.bb.getFloat(d + it * 4) }
        }
        val refAt = f.offsetOf(key.ptr("refkey"))
        val ref = blocks.firstOrNull { it.at == refAt } ?: blocks.firstOrNull() ?: return pts
        val base = data(ref) ?: return pts
        val out = base.copyOf()
        for (kb in blocks) {
            if (kb.at == ref.at || kb.int("flag") and 1 != 0) continue
            val d = data(kb) ?: continue
            val rel = blocks.getOrNull(kb.int("relative"))?.let { data(it) } ?: base
            val min = kb.float("slidermin", 0f)
            val max = kb.float("slidermax", 1f).let { if (it <= min) min + 1f else it }
            val w = fr.prop(keyAt, a.keyPath(keyAt, kb.str("name")), 0).coerceIn(min, max)
            if (w == 0f) continue
            for (i in out.indices) out[i] += w * (d[i] - rel[i])
        }
        return out
    }

    private fun latticeAnimated(lat: BlendFile.View): Boolean {
        val a = animator ?: return false
        if (a.dynamic(lat)) return true
        val lt = f.offsetOf(lat.ptr("data"))?.let { f.view("Lattice", it) } ?: return false
        val keyAt = f.offsetOf(lt.ptr("key")) ?: return false
        val key = f.view("Key", keyAt) ?: return false
        return a.listItems(key.sub("block"), "KeyBlock").any { a.keyAnimated(keyAt, it.str("name")) }
    }

    private fun latticeDeform(fr: BlendAnimator.Frame, ob: BlendFile.View, d: Deform.Lattice, co: FloatArray, mask: FloatArray?) {
        val lt = f.offsetOf(d.lat.ptr("data"))?.let { f.view("Lattice", it) } ?: return
        val pu = lt.int("pntsu").coerceAtLeast(1)
        val pv = lt.int("pntsv").coerceAtLeast(1)
        val pw = lt.int("pntsw").coerceAtLeast(1)
        val n = pu * pv * pw
        if (n > 1_000_000) return
        val pts = latticePoints(fr, lt, n) ?: return
        val fu = lt.float("fu"); val fv = lt.float("fv"); val fw = lt.float("fw")
        val du = lt.float("du"); val dv = lt.float("dv"); val dw = lt.float("dw")
        val tu = lt.int("typeu"); val tv = lt.int("typev"); val tw = lt.int("typew")
        val latmat = GltfParser.mul(BlendAnimator.invert(fr.world(d.lat)), fr.world(ob))
        val imat = BlendAnimator.invert(latmat)
        val delta = FloatArray(n * 3)
        var i = 0
        for (w in 0 until pw) for (v in 0 until pv) for (u in 0 until pu) {
            val x = pts[i * 3] - (fu + u * du)
            val y = pts[i * 3 + 1] - (fv + v * dv)
            val z = pts[i * 3 + 2] - (fw + w * dw)
            delta[i * 3] = imat[0] * x + imat[4] * y + imat[8] * z
            delta[i * 3 + 1] = imat[1] * x + imat[5] * y + imat[9] * z
            delta[i * 3 + 2] = imat[2] * x + imat[6] * y + imat[10] * z
            i++
        }
        val wu = FloatArray(4)
        val wv = FloatArray(4)
        val ww = FloatArray(4)
        for (vi in 0 until co.size / 3) {
            val weight = d.strength * (mask?.get(vi) ?: 1f)
            if (weight == 0f) continue
            val vec = BlendAnimator.transformPoint(latmat, co[vi * 3], co[vi * 3 + 1], co[vi * 3 + 2])
            fun axis(c: Float, f0: Float, df: Float, pn: Int, type: Int, out: FloatArray): Int {
                if (pn > 1 && df != 0f) {
                    val t = (c - f0) / df
                    val idx = kotlin.math.floor(t).toInt()
                    keyCurve(t - idx, type).copyInto(out)
                    return idx
                }
                out[0] = 0f; out[1] = 1f; out[2] = 0f; out[3] = 0f
                return 0
            }
            val ui = axis(vec[0], fu, du, pu, tu, wu)
            val vI = axis(vec[1], fv, dv, pv, tv, wv)
            val wI = axis(vec[2], fw, dw, pw, tw, ww)
            var ax = 0f
            var ay = 0f
            var az = 0f
            for (k3 in 0 until 4) {
                val wf = ww[k3]
                if (wf == 0f) continue
                val wwI = (wI - 1 + k3).coerceIn(0, pw - 1)
                for (k2 in 0 until 4) {
                    val vf = wf * wv[k2]
                    if (vf == 0f) continue
                    val vvI = (vI - 1 + k2).coerceIn(0, pv - 1)
                    for (k1 in 0 until 4) {
                        val uf = vf * wu[k1]
                        if (uf == 0f) continue
                        val uuI = (ui - 1 + k1).coerceIn(0, pu - 1)
                        val idx = (wwI * pv + vvI) * pu + uuI
                        ax += uf * delta[idx * 3]; ay += uf * delta[idx * 3 + 1]; az += uf * delta[idx * 3 + 2]
                    }
                }
            }
            co[vi * 3] += weight * ax
            co[vi * 3 + 1] += weight * ay
            co[vi * 3 + 2] += weight * az
        }
    }

    private class DeformContext(val skins: Map<Deform.Armature, Skin?>, val masks: Map<Deform, FloatArray?>, val topo: MeshTopology?, val rest: FloatArray?, val mesh: BlendMeshData? = null)

    private fun deformContext(ob: BlendFile.View, me: BlendFile.View, vdata: List<Layer>, local: FloatArray, stack: List<Deform>): DeformContext =
        deformContextFrom(ob, groupNames(ob, me), { dverts(me, vdata, local.size / 3) }, local, stack, { topology(me) }, { meshPositions(me, vdata) }, { meshData(ob, local) })

    private fun deformContextFrom(
        ob: BlendFile.View, groups: List<String>, dvFn: () -> Array<List<Pair<Int, Float>>>?, local: FloatArray, stack: List<Deform>,
        topoFn: () -> MeshTopology?, restFn: () -> FloatArray?, meshFn: () -> BlendMeshData? = { null }
    ): DeformContext {
        val vCount = local.size / 3
        val dv = if (stack.any { (it is Deform.Lattice && it.mask.isNotEmpty()) || (it is Deform.CurveDef && it.mask.isNotEmpty()) || (it is Deform.Hook && it.mask.isNotEmpty()) || (it is Deform.MeshDeform && it.mask.isNotEmpty()) || (it is Deform.Mod && it.mask.isNotEmpty()) }) dvFn() else null
        val skins = HashMap<Deform.Armature, Skin?>()
        val masks = HashMap<Deform, FloatArray?>()
        for (d in stack) when (d) {
            is Deform.Armature -> if (d.multi) {
                skins[d] = skinFrom(ob, groups, dvFn, local, Deform.Armature(d.arm, d.flag, "", false))
                masks[d] = maskWeights(groups, dvFn(), vCount, d.mask, d.invert)
            } else skins[d] = skinFrom(ob, groups, dvFn, local, d)
            is Deform.Lattice -> masks[d] = maskWeights(groups, dv, vCount, d.mask, d.invert)
            is Deform.CurveDef -> masks[d] = maskWeights(groups, dv, vCount, d.mask, d.invert)
            is Deform.Hook -> masks[d] = maskWeights(groups, dv, vCount, d.mask, d.flag and 2 != 0)
            is Deform.MeshDeform -> masks[d] = maskWeights(groups, dv, vCount, d.mask, d.invert)
            is Deform.Mod -> masks[d] = maskWeights(groups, dv, vCount, d.mask, d.invert)
            is Deform.Constructive -> {}
        }
        val topo = if (stack.any { it is Deform.Mod && it.type in intArrayOf(7, 14, 16, 25, 43, 47, 51) }) topoFn() else null
        val rest = if (stack.any { it is Deform.Mod && it.type == 51 }) restFn() else null
        val mesh = if (stack.any { it is Deform.Mod && it.type == 25 && it.md.int("shrinkType") == 1 && it.md.int("subsurfLevels") > 0 && it.md.int("projAxis") and 0xFF == 0 }) meshFn() else null
        return DeformContext(skins, masks, topo, rest, mesh)
    }

    internal fun deformedLocal(ob: BlendFile.View, stack: List<Deform>): FloatArray? {
        val a = animator ?: return null
        val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val pos = meshPositions(me, vdata) ?: return null
        val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
        val local = if (refBase != null && refBase.size == pos.size) refBase.copyOf() else pos.copyOf()
        for (sh in shapes) if (sh.staticValue != 0f) for (i in local.indices) local[i] += sh.staticValue * sh.delta[i]
        return deformLocal(a.staticFrame, ob, stack, deformContext(ob, me, vdata, local, stack), local)
    }

    private fun deformLocal(fr: BlendAnimator.Frame, ob: BlendFile.View, stack: List<Deform>, ctx: DeformContext, local: FloatArray): FloatArray {
        val co = local.copyOf()
        var prevInput = co.copyOf()
        for (d in stack) {
            val input = co.copyOf()
            when (d) {
                is Deform.Armature -> {
                    val skin = ctx.skins[d] ?: continue
                    val src = if (d.multi) prevInput else co
                    val w = skinPositions(fr, ob, skin, src)
                    val inv = BlendAnimator.invert(fr.world(ob))
                    val mask = if (d.multi) ctx.masks[d] else null
                    for (v in 0 until co.size / 3) {
                        val p = BlendAnimator.transformPoint(inv, w[v * 3], w[v * 3 + 1], w[v * 3 + 2])
                        if (d.multi) {
                            val keep = mask?.let { 1f - it[v] } ?: 0f
                            for (k in 0 until 3) co[v * 3 + k] = co[v * 3 + k] * keep + p[k] * (1f - keep)
                        } else {
                            co[v * 3] = p[0]; co[v * 3 + 1] = p[1]; co[v * 3 + 2] = p[2]
                        }
                    }
                }
                is Deform.Lattice -> latticeDeform(fr, ob, d, co, ctx.masks[d])
                is Deform.CurveDef -> curveDeform(fr, ob, d, co, ctx.masks[d])
                is Deform.Hook -> hookDeform(fr, ob, d, co, ctx.masks[d])
                is Deform.MeshDeform -> meshDeform(fr, ob, d, co, ctx.masks[d])
                is Deform.Mod -> modDeform(fr, ob, d, co, ctx.masks[d], ctx.topo, ctx.rest, ctx.mesh)
                is Deform.Constructive -> {}
            }
            prevInput = input
        }
        return co
    }

    private fun deformWorld(fr: BlendAnimator.Frame, ob: BlendFile.View, stack: List<Deform>, ctx: DeformContext, local: FloatArray): FloatArray {
        val co = deformLocal(fr, ob, stack, ctx, local)
        val w = fr.world(ob)
        for (v in 0 until co.size / 3) {
            val p = BlendAnimator.transformPoint(w, co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
            co[v * 3] = p[0]; co[v * 3 + 1] = p[1]; co[v * 3 + 2] = p[2]
        }
        return co
    }

    private fun stackAnimated(ob: BlendFile.View, stack: List<Deform>, shapes: List<Shape>): Boolean {
        val a = animator ?: return false
        if (frames.isEmpty()) return false
        if (a.dynamic(ob) || shapes.any { it.animated }) return true
        return stack.any { d ->
            when (d) {
                is Deform.Armature -> a.armatureAnimated(d.arm) || a.dynamic(d.arm)
                is Deform.Lattice -> latticeAnimated(d.lat)
                is Deform.CurveDef -> curveAnimated(d.curve)
                is Deform.Hook -> a.dynamic(d.target) || (d.target.int("type") == 25 && d.sub.isNotEmpty() && a.armatureAnimated(d.target))
                is Deform.MeshDeform -> cageAnimated(d.cage)
                is Deform.Mod -> (d.type in intArrayOf(7, 14, 35) && d.md.ptr("texture") != 0L && d.md.int("texmapping") == 2 &&
                    f.offsetOf(d.md.ptr("map_object"))?.let { a.owner(it) }?.let { mo -> a.dynamic(mo) || (mo.int("type") == 25 && a.armatureAnimated(mo)) } == true) ||
                    (d.type == 53 && targetAnimated(d.ctrl!!)) || (d.type == 25 && (targetAnimated(d.ctrl!!) || (d.ctrl2 != null && targetAnimated(d.ctrl2)))) || (d.ctrl != null && (a.dynamic(d.ctrl) || (d.ctrl.int("type") == 25 && a.armatureAnimated(d.ctrl)))) ||
                    (d.ctrl2 != null && (a.dynamic(d.ctrl2) || (d.ctrl2.int("type") == 25 && a.armatureAnimated(d.ctrl2)))) || (d.type == 35 && a.dynamic(ob)) || (d.type == 7 && (d.md.float("speed") != 0f || d.md.float("lifetime") != 0f)) ||
                    a.modifierAnimated(ob, d.md.sub("modifier")?.str("name") ?: "") || (d.type == 14 && d.md.int("space") == 1 && a.dynamic(ob))
                is Deform.Constructive -> d.type == 4 || a.modifierAnimated(ob, d.md.sub("modifier")?.str("name") ?: "") ||
                    listOfNotNull(d.ctrl, d.ctrl2).any { c -> a.dynamic(c) || (c.int("type") == 25 && a.armatureAnimated(c)) } ||
                    ((d.ctrl != null || d.ctrl2 != null) && a.dynamic(ob)) || d.extra.any { targetAnimated(it) }
            }
        }
    }

    private class TargetEval(val stack: List<Deform>, val ctx: DeformContext, val local: FloatArray, val topo: MeshTopology, val sharp: BooleanArray?)

    private val targetEvals = HashMap<Int, TargetEval?>()
    private val targetBusy = HashSet<Int>()

    private fun targetEval(ob: BlendFile.View): TargetEval? {
        if (targetEvals.containsKey(ob.at)) return targetEvals[ob.at]
        if (!targetBusy.add(ob.at)) return null
        val r = try {
            val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) }
            if (me == null) null else {
                val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
                val pos = meshPositions(me, vdata)
                val topo = topology(me)
                if (pos == null || topo == null) null else {
                    val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
                    val local = if (refBase != null && refBase.size == pos.size) refBase.copyOf() else pos.copyOf()
                    for (sh in shapes) if (sh.staticValue != 0f) for (i in local.indices) local[i] += sh.staticValue * sh.delta[i]
                    val stack = deformStack(ob)
                    val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
                    val pdata = layers(me, "face_data", "pdata") + storageLayers(me, 2)
                    val smooth = faces(me, ldata, pdata, layers(me, "fdata_legacy", "fdata"))?.smooth
                    val sharp = smooth?.takeIf { s -> s.any { !it } }?.let { s -> BooleanArray(s.size) { !s[it] } }
                    TargetEval(stack, deformContext(ob, me, vdata, local, stack), local, topo, sharp)
                }
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            targetBusy.remove(ob.at)
        }
        targetEvals[ob.at] = r
        return r
    }

    private fun targetCo(fr: BlendAnimator.Frame, ob: BlendFile.View): FloatArray? {
        val te = targetEval(ob) ?: return null
        if (!targetBusy.add(ob.at)) return null
        try {
            return deformLocal(fr, ob, te.stack, te.ctx, te.local)
        } finally {
            targetBusy.remove(ob.at)
        }
    }

    private fun targetTree(fr: BlendAnimator.Frame, ob: BlendFile.View): ShrinkTree? {
        val te = targetEval(ob) ?: return null
        return ShrinkTree(targetCo(fr, ob) ?: return null, te.topo, te.sharp)
    }

    private val sdefBinds = HashMap<Int, SurfaceBind?>()

    private fun surfaceBind(m: BlendFile.View): SurfaceBind? = sdefBinds.getOrPut(m.at) {
        try {
            val vst = f.struct("SDefVert") ?: return@getOrPut null
            val bst = f.struct("SDefBind") ?: return@getOrPut null
            val vAt = f.offsetOf(m.ptr("verts")) ?: return@getOrPut null
            val n = (if (m.has("bind_verts_num")) m.int("bind_verts_num") else m.int("numverts")).coerceIn(0, 50_000_000)
            val hasIdx = vst.fields.containsKey("vertex_idx")
            val vertexIdx = IntArray(n)
            val bindStart = IntArray(n + 1)
            val inds = ArrayList<IntArray>()
            val wts = ArrayList<FloatArray>()
            val modes = ArrayList<Int>()
            val nd = ArrayList<Float>()
            val infl = ArrayList<Float>()
            for (i in 0 until n) {
                val v = f.View(vst, vAt + i * vst.size)
                vertexIdx[i] = if (hasIdx) v.int("vertex_idx") else i
                val bn = v.int(if (v.has("binds_num")) "binds_num" else "numbinds").coerceIn(0, 4096)
                val bAt = f.offsetOf(v.ptr("binds"))
                if (bAt != null) for (j in 0 until bn) {
                    val b = f.View(bst, bAt + j * bst.size)
                    val vn = b.int(if (b.has("verts_num")) "verts_num" else "numverts").coerceIn(0, 65536)
                    val iAt = f.offsetOf(b.ptr("vert_inds")) ?: continue
                    val wAt = f.offsetOf(b.ptr("vert_weights")) ?: continue
                    val mode = b.int("mode")
                    val wn = if (mode == 1) vn else 3
                    inds.add(IntArray(vn) { f.bb.getInt(iAt + it * 4) })
                    wts.add(FloatArray(wn) { f.bb.getFloat(wAt + it * 4) })
                    modes.add(mode); nd.add(b.float("normal_dist")); infl.add(b.float("influence"))
                }
                bindStart[i + 1] = inds.size
            }
            SurfaceBind(vertexIdx, bindStart, inds, wts, modes.toIntArray(), nd.toFloatArray(), infl.toFloatArray(), m.floats("mat", 16) ?: GltfParser.IDENTITY)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun targetAnimated(ob: BlendFile.View): Boolean {
        val a = animator ?: return false
        if (!targetBusy.add(ob.at)) return false
        try {
            val te = targetEval(ob) ?: return a.dynamic(ob)
            return a.dynamic(ob) || stackAnimated(ob, te.stack, emptyList())
        } finally {
            targetBusy.remove(ob.at)
        }
    }

    private val textures = HashMap<Long, BlendTexture?>()

    private fun blendTexture(ptr: Long): BlendTexture? {
        if (ptr == 0L) return null
        return textures.getOrPut(ptr) {
            try {
                val t = f.offsetOf(ptr)?.let { f.view("Tex", it) } ?: return@getOrPut null
                val coba = f.offsetOf(t.ptr("coba"))?.let { f.view("ColorBand", it) }?.let { cb ->
                    val n = cb.int("tot").coerceIn(0, 32)
                    val st = f.struct("CBData") ?: return@let null
                    val base = cb.at + (cb.field("data")?.offset ?: return@let null)
                    val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val a = FloatArray(n); val pos = FloatArray(n)
                    for (i in 0 until n) {
                        val d = f.View(st, base + i * st.size)
                        r[i] = d.float("r"); g[i] = d.float("g"); b[i] = d.float("b"); a[i] = d.float("a"); pos[i] = d.float("pos")
                    }
                    BlendColorBand(cb.int("ipotype"), cb.int("ipotype_hue"), cb.int("color_mode"), r, g, b, a, pos)
                }
                var ignoreAlpha = false
                val img = if (t.int("type") == 8) f.offsetOf(t.ptr("ima"))?.let { f.view("Image", it) }?.let { im ->
                    ignoreAlpha = im.int("alpha_mode") == 3
                    imagePixels(im)
                } else null
                BlendTexture(
                    t.int("type"), t.int("stype"), t.int("flag"), t.int("imaflag"), t.int("extend", 3),
                    t.float("noisesize", 0.25f), t.float("turbul", 5f), t.float("bright", 1f), t.float("contrast", 1f), t.float("saturation", 1f),
                    t.float("rfac", 1f), t.float("gfac", 1f), t.float("bfac", 1f), t.float("filtersize", 1f),
                    t.float("mg_H", 1f), t.float("mg_lacunarity", 2f), t.float("mg_octaves", 2f), t.float("mg_offset", 1f), t.float("mg_gain", 1f),
                    t.float("dist_amount", 1f), t.float("ns_outscale", 1f),
                    floatArrayOf(t.float("vn_w1", 1f), t.float("vn_w2"), t.float("vn_w3"), t.float("vn_w4")), t.float("vn_mexp", 2.5f), t.int("vn_distm"), t.int("vn_coltype"),
                    t.int("noisedepth", 2), t.int("noisetype"), t.int("noisebasis"), t.int("noisebasis2"),
                    floatArrayOf(t.float("cropxmin"), t.float("cropymin"), t.float("cropxmax", 1f), t.float("cropymax", 1f)),
                    t.int("xrepeat", 1), t.int("yrepeat", 1), t.float("checkerdist"), coba, img, ignoreAlpha,
                    f.version, if (t.has("texfilter")) t.int("texfilter") else 0
                )
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private fun imagePixels(im: BlendFile.View): BlendImagePixels? {
        val source = im.int("source", 1)
        val colorspace = im.sub("colorspace_settings")?.str("name") ?: ""
        val alphaMode = im.int("alpha_mode")
        val isData = BlendColorSpace.isData(colorspace)
        if (source == 4) {
            val tile = im.sub("tiles")?.ptr("first")?.let { f.offsetOf(it) }?.let { f.view("ImageTile", it) }?.takeIf { it.has("gen_x") }
            val g = tile ?: im
            val w = g.int("gen_x", 1024).coerceIn(1, 16384)
            val h = g.int("gen_y", 1024).coerceIn(1, 16384)
            val color = g.floats("gen_color", 4) ?: floatArrayOf(0f, 0f, 0f, 1f)
            if (g.int("gen_flag") and 1 != 0) {
                val fl = when (g.int("gen_type")) {
                    1 -> BlendGeneratedImage.uvGridFloat(w, h)
                    2 -> {
                        val bytes = BlendGeneratedImage.colorGrid(w, h)
                        FloatArray(bytes.size) { i -> if (i % 4 == 3) 1.0f else BlendColorSpace.srgbToLinear(bytes[i] / 255.0f) }
                    }
                    else -> BlendGeneratedImage.blankFloat(w, h, color, isData)
                }
                return BlendImagePixels(w, h, null, fl)
            }
            val px = when (g.int("gen_type")) {
                1 -> BlendGeneratedImage.uvGrid(w, h)
                2 -> BlendGeneratedImage.colorGrid(w, h)
                else -> BlendGeneratedImage.blank(w, h, color)
            }
            return byteImage(w, h, px, alphaMode)
        }
        val data = packedBytes(im) ?: im.str("filepath").ifEmpty { im.str("name") }.takeIf { it.isNotBlank() }?.let { out.resource(it.removePrefix("//").replace('\\', '/')) } ?: return null
        val png = if (PngDecoder.isPng(data)) PngDecoder.decode(data) else null
        if (png != null) {
            val w = png.width
            val h = png.height
            if (png.bitDepth == 16) {
                val fl = FloatArray(w * h * 4)
                for (y in 0 until h) for (x in 0 until w) {
                    val si = ((h - 1 - y) * w + x) * png.channels
                    val o = (y * w + x) * 4
                    fun v(k: Int) = png.samples[si + k].toFloat() / 65535.0f
                    when (png.channels) {
                        1 -> { fl[o] = v(0); fl[o + 1] = v(0); fl[o + 2] = v(0); fl[o + 3] = 1f }
                        2 -> { fl[o] = v(0); fl[o + 1] = v(0); fl[o + 2] = v(0); fl[o + 3] = v(1) }
                        3 -> { fl[o] = v(0); fl[o + 1] = v(1); fl[o + 2] = v(2); fl[o + 3] = 1f }
                        else -> { fl[o] = v(0); fl[o + 1] = v(1); fl[o + 2] = v(2); fl[o + 3] = v(3) }
                    }
                }
                val packed = isData || alphaMode == 2
                if (!packed) {
                    if (alphaMode == 3) for (i in 0 until w * h) fl[i * 4 + 3] = 1.0f
                    else if (alphaMode != 1) for (i in 0 until w * h) { val a = fl[i * 4 + 3]; for (k in 0 until 3) fl[i * 4 + k] *= a }
                }
                BlendColorSpace.toSceneLinear(fl, colorspace, !packed && alphaMode != 3)
                return BlendImagePixels(w, h, null, fl)
            }
            val px = IntArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val si = ((h - 1 - y) * w + x) * png.channels
                val o = (y * w + x) * 4
                val sm = png.samples
                when (png.channels) {
                    1 -> { px[o] = sm[si]; px[o + 1] = sm[si]; px[o + 2] = sm[si]; px[o + 3] = 255 }
                    2 -> { px[o] = sm[si]; px[o + 1] = sm[si]; px[o + 2] = sm[si]; px[o + 3] = sm[si + 1] }
                    3 -> { px[o] = sm[si]; px[o + 1] = sm[si + 1]; px[o + 2] = sm[si + 2]; px[o + 3] = 255 }
                    else -> { for (k in 0 until 4) px[o + k] = sm[si + k] }
                }
            }
            return byteImage(w, h, px, if (isData) 2 else alphaMode)
        }
        val d = out.pixels(data) ?: return null
        val w = d.width
        val h = d.height
        val px = IntArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = d.argb[(h - 1 - y) * w + x]
            val o = (y * w + x) * 4
            px[o] = (c shr 16) and 0xFF; px[o + 1] = (c shr 8) and 0xFF; px[o + 2] = c and 0xFF; px[o + 3] = (c ushr 24) and 0xFF
        }
        return byteImage(w, h, px, if (isData) 2 else alphaMode)
    }

    private fun byteImage(w: Int, h: Int, px: IntArray, alphaMode: Int): BlendImagePixels {
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            var a = px[i * 4 + 3]
            if (alphaMode == 3) a = 255
            for (k in 0 until 3) {
                var v = px[i * 4 + k]
                if (alphaMode == 1 && a != 0 && a != 255) v = minOf(255, (v * 255.0f / a + 0.5f).toInt())
                rgba[i * 4 + k] = v.toByte()
            }
            rgba[i * 4 + 3] = a.toByte()
        }
        return BlendImagePixels(w, h, rgba)
    }

    private fun textureCoords(fr: BlendAnimator.Frame, ob: BlendFile.View, m: BlendFile.View, co: FloatArray): FloatArray {
        val n = co.size / 3
        val out = FloatArray(n * 3)
        var mapping = m.int("texmapping")
        var mapInv: FloatArray? = null
        if (mapping == 2) {
            val mo = f.offsetOf(m.ptr("map_object"))?.let { animator?.owner(it) }
            if (mo != null) {
                val bone = m.str("map_bone")
                val pose = if (bone.isNotEmpty() && mo.int("type") == 25) fr.bonePose(mo, bone) else null
                mapInv = BlendAnimator.invert(if (pose != null) GltfParser.mul(fr.world(mo), pose) else fr.world(mo))
            } else mapping = 0
        }
        if (mapping == 3) {
            val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) }
            val uv = me?.let { meshUvs(it, m.str("uvlayer_name")) }
            val topo = me?.let { topology(it) }
            if (uv != null && topo != null && uv.size >= topo.corners.size * 2) {
                val done = BooleanArray(n)
                for (fc in 0 until topo.faceCount) for (k in 0 until topo.faceSize[fc]) {
                    val c = topo.faceStart[fc] + k
                    val v = topo.corners.getOrElse(c) { -1 }
                    if (v in 0 until n && !done[v]) {
                        out[v * 3] = uv[c * 2] * 2.0f - 1.0f
                        out[v * 3 + 1] = uv[c * 2 + 1] * 2.0f - 1.0f
                        done[v] = true
                    }
                }
                return out
            }
            mapping = 0
        }
        val w = if (mapping == 1 || mapping == 2) fr.world(ob) else null
        for (i in 0 until n) {
            var p = floatArrayOf(co[i * 3], co[i * 3 + 1], co[i * 3 + 2])
            if (w != null) p = RigMath.point(w, p)
            if (mapInv != null) p = RigMath.point(mapInv, p)
            p.copyInto(out, i * 3)
        }
        return out
    }

    private fun meshUvs(me: BlendFile.View, name: String): FloatArray? {
        val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
        val names = listOf(name, cstr(me.ptr("active_uv_map_attribute")), cstr(me.ptr("default_uv_map_attribute"))).filter { it.isNotEmpty() }
        val layer = names.firstNotNullOfOrNull { nm -> ldata.firstOrNull { it.name == nm && (it.elemSize == 8 || it.structType == "MLoopUV") } }
            ?: ldata.firstOrNull { (it.type == 49 || it.type <= -1000) && !it.name.startsWith(".") && it.elemSize == 8 }
            ?: ldata.firstOrNull { it.structType == "MLoopUV" } ?: return null
        val off = if (layer.structType == "MLoopUV") f.struct("MLoopUV")?.fields?.get("uv")?.offset ?: 0 else 0
        return FloatArray(layer.count * 2) { f.bb.getFloat(layer.dataAt + (it / 2) * layer.elemSize + off + (it % 2) * 4) }
    }

    private fun sampleTexture(fr: BlendAnimator.Frame, ob: BlendFile.View, m: BlendFile.View, co: FloatArray): Array<BlendTexture.Result>? {
        val tex = blendTexture(m.ptr("texture")) ?: return null
        val tc = textureCoords(fr, ob, m, co)
        val n = co.size / 3
        return Array(n) { i -> BlendTexture.Result().also { tex.evaluate(floatArrayOf(tc[i * 3], tc[i * 3 + 1], tc[i * 3 + 2]), it) } }
    }

    internal fun meshData(ob: BlendFile.View, local: FloatArray? = null): BlendMeshData? {
        val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
        val pdata = layers(me, "face_data", "pdata") + storageLayers(me, 2)
        val edata = layers(me, "edge_data", "edata") + storageLayers(me, 1)
        val fc = faces(me, ldata, pdata, layers(me, "fdata_legacy", "fdata")) ?: Faces(IntArray(0), IntArray(0), IntArray(0), null, null, false)
        val co = local ?: run {
            val pos = meshPositions(me, vdata) ?: return null
            val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
            val l = if (refBase != null && refBase.size == pos.size) refBase.copyOf() else pos.copyOf()
            for (sh in shapes) if (sh.staticValue != 0f) for (i in l.indices) l[i] += sh.staticValue * sh.delta[i]
            l
        }
        val nV = co.size / 3
        val uvRaw = if (fc.legacy) null else meshUvs(me, "")
        val uv = uvRaw?.takeIf { it.size >= fc.corners.size * 2 } ?: if (fc.corners.isEmpty() && hasUvLayer(me)) FloatArray(0) else null
        val colorNames = listOf(cstr(me.ptr("default_color_attribute")), cstr(me.ptr("active_color_attribute"))).filter { it.isNotEmpty() }
        fun isColor(l: Layer) = (l.type == 17 && l.elemSize == 4) || (l.type == 47 && l.elemSize == 16)
        fun named(list: List<Layer>) = colorNames.firstNotNullOfOrNull { n -> list.firstOrNull { it.name == n && (it.elemSize == 4 || it.elemSize == 16) } }
        fun color(l: Layer, i: Int): Int {
            val at = l.dataAt + i * l.elemSize
            return if (l.elemSize == 4) {
                ((f.bytes[at + 3].toInt() and 0xFF) shl 24) or ((f.bytes[at].toInt() and 0xFF) shl 16) or ((f.bytes[at + 1].toInt() and 0xFF) shl 8) or (f.bytes[at + 2].toInt() and 0xFF)
            } else {
                fun ch(k: Int): Int {
                    val v = f.bb.getFloat(at + k * 4).coerceIn(0f, 1f).toDouble()
                    val sv = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
                    return (sv * 255).roundToInt().coerceIn(0, 255)
                }
                ((f.bb.getFloat(at + 12).coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
        }
        val cornerLayer = if (fc.legacy) null else named(ldata) ?: ldata.firstOrNull { isColor(it) }
        val pointLayer = if (cornerLayer == null) named(vdata) ?: vdata.firstOrNull { isColor(it) } else null
        val cornerColor = cornerLayer?.let { l -> IntArray(fc.corners.size) { c -> if (c < l.count) color(l, c) else 0 } }
        val pointColor = pointLayer?.let { l -> IntArray(nV) { v -> if (v < l.count) color(l, v) else 0 } }
        val edges = fileEdges(me, edata) ?: MeshTopology.edgesFromFaces(fc.corners, fc.start, fc.size)
        val creaseMap = HashMap<Long, Float>()
        val creaseAttr = edata.firstOrNull { it.name == "crease_edge" && it.elemSize == 4 } ?: edata.firstOrNull { it.type == 30 && it.elemSize == 4 }
        if (creaseAttr != null) {
            for (e in 0 until minOf(creaseAttr.count, edges.size / 2)) {
                val c = f.bb.getFloat(creaseAttr.dataAt + e * 4)
                if (c != 0f) creaseMap[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = c
            }
        } else {
            val st = f.struct("MEdge")
            val cf = st?.fields?.get("crease") ?: st?.fields?.get("crease_legacy")
            val src = arrayBlock(me.ptr("medge"))?.let { (at, b) -> at to b.count } ?: edata.firstOrNull { it.structType == "MEdge" }?.let { it.dataAt to it.count }
            if (st != null && cf != null && src != null) for (e in 0 until src.second) {
                val c = (f.bytes[src.first + e * st.size + cf.offset].toInt() and 0xFF) / 255.0f
                if (c != 0f) creaseMap[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = c
            }
        }
        val vcreaseLayer = vdata.firstOrNull { it.name == "crease_vert" && it.elemSize == 4 } ?: vdata.firstOrNull { it.type == 30 && it.elemSize == 4 }
        val vcrease = vcreaseLayer?.let { l -> FloatArray(nV) { v -> if (v < l.count) f.bb.getFloat(l.dataAt + v * 4) else 0f } }
        val used = HashSet<Long>()
        for (fi in fc.start.indices) for (k in 0 until fc.size[fi]) used.add(BlendMeshData.key(fc.corners[fc.start[fi] + k], fc.corners[fc.start[fi] + (k + 1) % fc.size[fi]]))
        val loose = ArrayList<Int>()
        for (e in 0 until edges.size / 2) if (BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]) !in used) { loose.add(edges[e * 2]); loose.add(edges[e * 2 + 1]) }
        val groups = groupNames(ob, me)
        val dv = if (groups.isNotEmpty()) dverts(me, vdata, nV) else null
        return BlendMeshData(co, fc.start, fc.size, fc.corners, fc.mat, fc.smooth, uv, cornerColor, pointColor, creaseMap, vcrease, loose.toIntArray(), dv, groups).also { md ->
            val ns = normalSpec(me, fc.corners.size)
            md.legacyNormals = f.version < 401 && ns == null
            md.fileEdges = edges
            if (ns != null) { md.sharpEdges = ns.sharpEdges; md.customShort = ns.customShort; md.customFloat = ns.customFloat; md.autoSmooth = ns.autoSmoothAngle }
            md.seamEdges = seamEdges(me)
            val ea = HashMap<String, Map<Long, Float>>()
            for (l in edata) if (l.name.isNotEmpty() && !l.name.startsWith(".") && l.type == 10 && l.elemSize == 4) {
                val m = HashMap<Long, Float>()
                for (e in 0 until minOf(l.count, edges.size / 2)) m[BlendMeshData.key(edges[e * 2], edges[e * 2 + 1])] = f.bb.getFloat(l.dataAt + e * 4)
                ea[l.name] = m
            }
            md.edgeAttrs = ea
            val va = HashMap<String, FloatArray>()
            for (l in vdata) if (l.name.isNotEmpty() && !l.name.startsWith(".") && l.type == 10 && l.elemSize == 4) va[l.name] = FloatArray(nV) { v -> if (v < l.count) f.bb.getFloat(l.dataAt + v * 4) else 0f }
            md.vertAttrs = va
            val skinLayer = vdata.firstOrNull { it.structType == "MVertSkin" } ?: vdata.firstOrNull { it.type == 37 && it.elemSize >= 16 }
            if (skinLayer != null) {
                val st = f.struct("MVertSkin")
                val radOff = st?.fields?.get("radius")?.offset ?: 0
                val flOff = st?.fields?.get("flag")?.offset ?: 12
                val rad = FloatArray(nV * 2) { 0.25f }
                val fl = IntArray(nV)
                val cnt = minOf(nV, skinLayer.count)
                for (v in 0 until cnt) {
                    val baseAt = skinLayer.dataAt + v * skinLayer.elemSize
                    if (baseAt + flOff + 4 <= f.bytes.size) {
                        rad[v * 2] = f.bb.getFloat(baseAt + radOff)
                        rad[v * 2 + 1] = f.bb.getFloat(baseAt + radOff + 4)
                        fl[v] = f.bb.getInt(baseAt + flOff)
                    }
                }
                md.skinRadius = rad
                md.skinFlag = fl
            }
            pdata.firstOrNull { it.name == "__mod_weightednormals_faceweight" && it.type == 11 && it.elemSize == 4 }?.let { l -> md.faceStrength = IntArray(fc.start.size) { i -> if (i < l.count) f.bb.getInt(l.dataAt + i * 4) else 0 } }
        }
    }

    private fun curveMap(ptr: Long): BlendCurveMap? {
        val at = f.offsetOf(ptr) ?: return null
        val cm = f.view("CurveMapping", at) ?: return null
        val cmOff = cm.field("cm")?.offset ?: return null
        val cuma = f.view("CurveMap", at + cmOff) ?: return null
        val n = cuma.int("totpoint").coerceIn(0, 4096)
        val pst = f.struct("CurveMapPoint") ?: return null
        val pAt = f.offsetOf(cuma.ptr("curve"))
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val fl = IntArray(n)
        if (pAt != null) for (i in 0 until n) {
            val p = f.View(pst, pAt + i * pst.size)
            if (pAt + (i + 1) * pst.size > f.bytes.size) break
            xs[i] = p.float("x"); ys[i] = p.float("y"); fl[i] = p.int("flag")
        }
        val clip = cm.sub("clipr")?.let { r -> floatArrayOf(r.float("xmin"), r.float("xmax"), r.float("ymin"), r.float("ymax")) } ?: floatArrayOf(0f, 1f, 0f, 1f)
        return try { BlendCurveMap(if (pAt == null) FloatArray(0) else xs, ys, fl, cm.int("flag"), clip) } catch (e: RuntimeException) { null }
    }

    private fun modDeform(fr: BlendAnimator.Frame, ob: BlendFile.View, d: Deform.Mod, co: FloatArray, mask: FloatArray?, topo: MeshTopology?, rest: FloatArray?, srcMesh: BlendMeshData? = null) {
        val m = d.md
        val name = m.sub("modifier")?.str("name") ?: ""
        fun mp(rna: String, stored: Float, index: Int = 0) = fr.modifierValue(ob, name, rna, index, stored)
        when (d.type) {
            17 -> {
                val ctrl = d.ctrl
                val flag = m.int("flag")
                var mat: FloatArray? = null
                var imat: FloatArray? = null
                var center = floatArrayOf(0f, 0f, 0f)
                if (ctrl != null) {
                    val obW = fr.world(ob)
                    val cW = fr.world(ctrl)
                    if (flag and (1 shl 4) != 0) {
                        mat = GltfParser.mul(BlendAnimator.invert(cW), obW)
                        imat = BlendAnimator.invert(mat)
                    }
                    center = BlendAnimator.transformPoint(BlendAnimator.invert(obW), cW[12], cW[13], cW[14])
                }
                BlendModifiers.cast(co, mask, m.int("type"), mp("factor", m.float("fac")), mp("radius", m.float("radius")), mp("size", m.float("size")), flag, ctrl != null, mat, imat, center)
            }
            28 -> {
                val mode = m.int("mode")
                val to = d.ctrl?.let { GltfParser.mul(BlendAnimator.invert(fr.world(it)), fr.world(ob)) }
                val lim = m.floats("limit", 2) ?: floatArrayOf(0f, 1f)
                val factor = mp(if (mode == 1 || mode == 2) "angle" else "factor", m.float("factor"))
                BlendModifiers.simpleDeform(co, mask, mode, factor, mp("limits", lim[0], 0), mp("limits", lim[1], 1), m.int("axis"), m.int("deform_axis", 2), to, to?.let { BlendAnimator.invert(it) })
            }
            16 -> {
                val t = topo ?: return
                BlendModifiers.smooth(co, t, mask, mp("factor", m.float("fac")), mp("iterations", m.int("repeat").toFloat()).toInt(), m.int("flag"))
            }
            47 -> {
                val t = topo ?: return
                val n = co.size / 3
                val vn = if (m.has("verts_num")) m.int("verts_num") else m.int("total_verts")
                if (vn != n || mask == null) return
                val at = f.offsetOf(m.ptr("vertexco")) ?: return
                if (at + n * 12 > f.bytes.size) return
                val bind = FloatArray(n * 3) { f.bb.getFloat(at + it * 4) }
                val anchors = (0 until n).filter { mask[it] > 0f }.toIntArray()
                BlendModifiers.laplacianDeform(co, bind, t, anchors, m.int("repeat", 1))
            }
            53 -> {
                val target = d.ctrl ?: return
                if (m.int("mesh_verts_num", co.size / 3) != co.size / 3) return
                val sb = surfaceBind(m) ?: return
                val tc = targetCo(fr, target) ?: return
                val tv = m.int("target_verts_num")
                if (tv > tc.size / 3) return
                BlendModifiers.surfaceDeform(co, mask, sb, tc, mp("strength", m.float("strength", 1f)))
            }
            25 -> {
                val target = d.ctrl ?: return
                val tree = targetTree(fr, target) ?: return
                val obW = fr.world(ob)
                val l2t = GltfParser.mul(BlendAnimator.invert(fr.world(target)), obW)
                val auxOb = d.ctrl2
                val aux = auxOb?.let { targetTree(fr, it) }
                val l2a = if (aux != null) GltfParser.mul(BlendAnimator.invert(fr.world(auxOb)), obW) else null
                val type = m.int("shrinkType")
                val opts = m.int("shrinkOpts") and 0xFF
                val mode = if (m.has("shrinkMode")) m.int("shrinkMode") else if (opts and 32 != 0) 4 else 0
                val axis = m.int("projAxis") and 0xFF
                val sub = m.int("subsurfLevels")
                val mesh = srcMesh
                val srcPos = if (type == 1 && axis == 0 && sub > 0 && mesh != null && mesh.co.size == co.size) {
                    BlendMeshData.refinedCoarse(BlendMeshData(co.copyOf(), mesh.fStart, mesh.fSize, mesh.corners, null, null, null, null, null, mesh.edgeCrease, mesh.vertCrease, mesh.looseEdges, null, emptyList()), sub)
                } else null
                val normals = if (type == 1 && axis == 0) topo?.vertexNormals(co) else null
                Shrinkwrap.deform(co, mask, normals, type, opts, mode, mp("offset", m.float("keepDist")), mp("project_limit", m.float("projLimit")), axis, tree, l2t, aux, l2a, srcPos)
            }
            35 -> {
                val obInv = BlendAnimator.invert(fr.world(ob))
                fun pchan(o: BlendFile.View, bone: String): FloatArray {
                    val pose = if (bone.isNotEmpty() && o.int("type") == 25) fr.bonePose(o, bone) else null
                    return GltfParser.mul(obInv, if (pose != null) GltfParser.mul(fr.world(o), pose) else fr.world(o))
                }
                val from = pchan(d.ctrl ?: return, m.str("bone_from"))
                val to = pchan(d.ctrl2 ?: return, m.str("bone_to"))
                BlendModifiers.warp(co, mask, from, to, mp("strength", m.float("strength", 1f)), mp("falloff_radius", m.float("falloff_radius", 1f)), m.int("falloff_type"), m.int("flag"), d.curve, sampleTexture(fr, ob, m, co))
            }
            51 -> {
                val t = topo ?: return
                val restCo = if (m.int("rest_source") == 1) {
                    val n = m.int("bind_coords_num")
                    val at = f.offsetOf(m.ptr("bind_coords"))
                    if (at == null || n <= 0 || at + n * 12 > f.bytes.size) null else FloatArray(n * 3) { f.bb.getFloat(at + it * 4) }
                } else rest
                if (restCo == null && m.int("flag") and 2 == 0) return
                BlendModifiers.correctiveSmooth(co, restCo, t, mask, mp("factor", m.float("lambda")), mp("scale", m.float("scale", 1f)), mp("iterations", m.int("repeat").toFloat()).toInt(), m.int("flag"), m.int("smooth_type"))
            }
            43 -> {
                val t = topo ?: return
                BlendModifiers.laplacianSmooth(co, t, mask, mp("lambda_factor", m.float("lambda")), mp("lambda_border", m.float("lambda_border")), mp("iterations", m.int("repeat").toFloat()).toInt(), m.int("flag"))
            }
            7 -> {
                val flag = m.int("flag")
                var sx = mp("start_position_x", m.float("startx"))
                var sy = mp("start_position_y", m.float("starty"))
                d.ctrl?.let { c ->
                    val rel = GltfParser.mul(BlendAnimator.invert(fr.world(ob)), fr.world(c))
                    sx = rel[12]; sy = rel[13]
                }
                val normals = if (flag and (1 shl 4) != 0) topo?.vertexNormals(co) else null
                BlendModifiers.wave(
                    co, normals, mask, fr.frame, flag, sx, sy, mp("height", m.float("height")), mp("width", m.float("width")), mp("narrowness", m.float("narrow")),
                    mp("speed", m.float("speed")), mp("damping_time", m.float("damp")), mp("falloff_radius", m.float("falloff")), mp("time_offset", m.float("timeoffs")), mp("lifetime", m.float("lifetime")),
                    sampleTexture(fr, ob, m, co)
                )
            }
            14 -> {
                val dir = m.int("direction")
                val normals = if (dir == 3 || dir == 5) topo?.vertexNormals(co) ?: return else null
                val toWorld = if (m.int("space") == 1 && dir in 0..2 || m.int("space") == 1 && dir == 4) fr.world(ob) else null
                BlendModifiers.displace(co, normals, mask, mp("strength", m.float("strength")), mp("mid_level", m.float("midlevel")), dir, toWorld, sampleTexture(fr, ob, m, co))
            }
        }
    }

    private class Bake(val ids: IntArray, val deltas: List<FloatArray>)

    private fun bake(ob: BlendFile.View, stack: List<Deform>, ctx: DeformContext, base: FloatArray, shapes: List<Shape>, wp: FloatArray, polys: Int): Bake? {
        val a = animator ?: return null
        return bakeWith({ fr ->
            val local = base.copyOf()
            for (sh in shapes) {
                val w = if (sh.animated) fr.prop(sh.keyAt, a.keyPath(sh.keyAt, sh.name), 0).coerceIn(sh.min, sh.max) else sh.staticValue
                if (w != 0f) for (i in local.indices) local[i] += w * sh.delta[i]
            }
            deformWorld(fr, ob, stack, ctx, local)
        }, wp, polys)
    }

    private fun bakeWith(sample: (BlendAnimator.Frame) -> FloatArray?, wp: FloatArray, polys: Int): Bake? {
        val a = animator ?: return null
        val perTarget = polys.toLong() * 18
        if (perTarget <= 0) return null
        val budget = ((6_000_000L - bakedFloats) / perTarget).toInt()
        val total = frames.size
        if (budget < 2 || total < 2) return null
        val samples = ArrayList<Int>()
        val k = minOf(total, budget)
        for (c in clipStarts.indices) {
            val n = clipLens[c]
            val m = maxOf(2, minOf(n, Math.round(k.toDouble() * n / total).toInt())).coerceAtMost(n)
            var last = -1
            for (j in 0 until m) {
                val idx = clipStarts[c] + if (m > 1) Math.round(j.toDouble() * (n - 1) / (m - 1)).toInt() else 0
                if (idx != last) samples.add(idx)
                last = idx
            }
        }
        if (samples.size.toLong() * perTarget > 6_000_000L - bakedFloats) return null
        val deltas = ArrayList<FloatArray>()
        for (s in samples) {
            val (clip, frame) = frames[s]
            val fr = a.Frame(clip, frame)
            val p = sample(fr) ?: return null
            if (p.size != wp.size) return null
            deltas.add(FloatArray(p.size) { p[it] - wp[it] })
        }
        bakedFloats += samples.size * perTarget
        val sampleSet = samples.toIntArray()
        val ids = IntArray(samples.size) { j ->
            val clipOf = clipStarts.indexOfLast { it <= sampleSet[j] }
            val prev = if (j > 0 && clipStarts.indexOfLast { it <= sampleSet[j - 1] } == clipOf) sampleSet[j - 1] else -1
            val next = if (j + 1 < sampleSet.size && clipStarts.indexOfLast { it <= sampleSet[j + 1] } == clipOf) sampleSet[j + 1] else -1
            val me = sampleSet[j]
            morphFns.add { idx, _ ->
                when {
                    idx == me -> 1f
                    prev >= 0 && idx in (prev + 1) until me -> (idx - prev).toFloat() / (me - prev)
                    next >= 0 && idx in (me + 1) until next -> (next - idx).toFloat() / (next - me)
                    else -> 0f
                }
            }
            morphBases.add(0f)
            morphFns.size - 1
        }
        return Bake(ids, deltas)
    }

    private fun curveAnimated(curve: BlendFile.View): Boolean = animator?.dynamic(curve) == true

    private fun hookDeform(fr: BlendAnimator.Frame, ob: BlendFile.View, d: Deform.Hook, co: FloatArray, mask: FloatArray?) {
        if (d.force == 0f) return
        val pose = if (d.sub.isNotEmpty() && d.target.int("type") == 25) fr.bonePose(d.target, d.sub) else null
        val dmat = if (pose != null) GltfParser.mul(fr.world(d.target), pose) else fr.world(d.target)
        val mat = RigMath.mul(BlendAnimator.invert(fr.world(ob)), dmat, d.parentInv)
        val uniform = d.flag and 1 != 0
        val mu = if (uniform) RigMath.rot3(d.parentInv).let { r -> d.parentInv.copyOf().also { it[12] = 0f; it[13] = 0f; it[14] = 0f } } else null
        val cent = if (mu != null) RigMath.dir(mu, d.cent) else d.cent
        val falloff = if (d.falloffType == 0) 0f else d.falloff
        val fsq = falloff * falloff
        fun apply(v: Int) {
            if (v < 0 || v * 3 + 2 >= co.size) return
            val p = floatArrayOf(co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
            var fac = d.force
            if (fsq != 0f) {
                val q = if (mu != null) RigMath.dir(mu, p) else p
                val lsq = RigMath.dot(RigMath.sub(cent, q), RigMath.sub(cent, q))
                fac = if (lsq > fsq) 0f else if (lsq > 0f) {
                    val base = when (d.falloffType) {
                        6 -> 1f
                        8 -> 1f - lsq / fsq
                        else -> {
                            val t = 1f - kotlin.math.sqrt(lsq) / falloff
                            when (d.falloffType) {
                                1 -> d.curve?.evaluate(t) ?: t
                                2 -> t * t
                                3 -> 3f * t * t - 2f * t * t * t
                                4 -> kotlin.math.sqrt(t)
                                7 -> kotlin.math.sqrt(2 * t - t * t)
                                else -> t
                            }
                        }
                    }
                    base * d.force
                } else d.force
            }
            if (fac == 0f) return
            if (mask != null) fac *= mask[v]
            if (fac == 0f) return
            val t = BlendAnimator.transformPoint(mat, p[0], p[1], p[2])
            co[v * 3] += (t[0] - p[0]) * fac
            co[v * 3 + 1] += (t[1] - p[1]) * fac
            co[v * 3 + 2] += (t[2] - p[2]) * fac
        }
        val idx = d.indices
        if (idx != null) {
            for (v in idx) apply(v)
        } else if (mask != null) {
            for (v in 0 until co.size / 3) apply(v)
        }
    }

    private class CageEval(val stack: List<Deform>, val ctx: DeformContext, val local: FloatArray)

    private val cages = HashMap<Int, CageEval?>()

    private fun cageEval(cage: BlendFile.View): CageEval? = cages.getOrPut(cage.at) {
        try {
            val me = f.offsetOf(cage.ptr("data"))?.let { f.view("Mesh", it) } ?: return@getOrPut null
            val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
            val local = meshPositions(me, vdata) ?: return@getOrPut null
            val stack = deformStack(cage).filter { it !is Deform.MeshDeform }
            CageEval(stack, deformContext(cage, me, vdata, local, stack), local)
        } catch (_: Throwable) {
            null
        }
    }

    private fun cageAnimated(cage: BlendFile.View): Boolean {
        val a = animator ?: return false
        val ce = cageEval(cage) ?: return a.dynamic(cage)
        return stackAnimated(cage, ce.stack, emptyList())
    }

    private fun meshDeform(fr: BlendAnimator.Frame, ob: BlendFile.View, d: Deform.MeshDeform, co: FloatArray, mask: FloatArray?) {
        val m = d.data
        val ce = cageEval(d.cage) ?: return
        val n = co.size / 3
        if (m.int("totvert") != n) return
        val totCage = m.int("totcagevert")
        if (totCage * 3 != ce.local.size) return
        if (m.int("flag") and 2 != 0) return
        val cageWorld = deformWorld(fr, d.cage, ce.stack, ce.ctx, ce.local)
        val cw = fr.world(d.cage)
        val cwInv = BlendAnimator.invert(cw)
        val cageMat = GltfParser.mul(cwInv, fr.world(ob))
        val bindmat = m.floats("bindmat", 16) ?: GltfParser.IDENTITY
        val cmat = GltfParser.mul(bindmat, cageMat)
        val icage = RigMath.inv(cmat).also { it[12] = 0f; it[13] = 0f; it[14] = 0f }
        val bcAt = f.offsetOf(m.ptr("bindcagecos")) ?: return
        val infAt = f.offsetOf(m.ptr("bindinfluences")) ?: return
        val offAt = f.offsetOf(m.ptr("bindoffsets")) ?: return
        val dco = FloatArray(totCage * 3)
        for (c in 0 until totCage) {
            val lc = BlendAnimator.transformPoint(cwInv, cageWorld[c * 3], cageWorld[c * 3 + 1], cageWorld[c * 3 + 2])
            val b = BlendAnimator.transformPoint(bindmat, lc[0], lc[1], lc[2])
            for (k in 0 until 3) dco[c * 3 + k] = b[k] - f.bb.getFloat(bcAt + (c * 3 + k) * 4)
        }
        for (v in 0 until n) {
            val fac = mask?.get(v) ?: 1f
            if (fac <= 0f) continue
            val start = f.bb.getInt(offAt + v * 4)
            val end = f.bb.getInt(offAt + (v + 1) * 4)
            var tw = 0f
            var x = 0f; var y = 0f; var z = 0f
            for (i in start until end) {
                val ia = infAt + i * 8
                if (ia + 8 > f.bytes.size) break
                val cv = f.bb.getInt(ia)
                val w = f.bb.getFloat(ia + 4)
                if (cv !in 0 until totCage) continue
                x += w * dco[cv * 3]; y += w * dco[cv * 3 + 1]; z += w * dco[cv * 3 + 2]
                tw += w
            }
            if (tw <= 0f) continue
            val dl = RigMath.dir(icage, floatArrayOf(x * fac / tw, y * fac / tw, z * fac / tw))
            co[v * 3] += dl[0]; co[v * 3 + 1] += dl[1]; co[v * 3 + 2] += dl[2]
        }
    }

    private fun meshPositions(me: BlendFile.View, vdata: List<Layer>): FloatArray? {
        arrayBlock(me.ptr("mvert"))?.let { (at, b) ->
            val st = f.struct("MVert")
            val stride = st?.size ?: (b.len / b.count.coerceAtLeast(1))
            val co = st?.fields?.get("co")?.offset ?: 0
            return FloatArray(b.count * 3) { f.bb.getFloat(at + (it / 3) * stride + co + (it % 3) * 4) }
        }
        val l = vdata.firstOrNull { it.name == "position" } ?: vdata.firstOrNull { it.type == 0 && it.structType == "MVert" } ?: return null
        val co = if (l.structType == "MVert") f.struct("MVert")?.fields?.get("co")?.offset ?: 0 else 0
        return FloatArray(l.count * 3) { f.bb.getFloat(l.dataAt + (it / 3) * l.elemSize + co + (it % 3) * 4) }
    }

    private fun curveDeform(fr: BlendAnimator.Frame, ob: BlendFile.View, d: Deform.CurveDef, co: FloatArray, mask: FloatArray?) {
        val a = animator ?: return
        val path = a.curvePath(d.curve) ?: return
        val cu = a.curveData(d.curve) ?: return
        val flag = cu.int("flag")
        val objectSpace = GltfParser.mul(BlendAnimator.invert(fr.world(ob)), fr.world(d.curve))
        val curveSpace = BlendAnimator.invert(objectSpace)
        val axis = (d.axis - 1).coerceIn(0, 5)
        val neg = axis > 2
        val index = if (neg) axis - 3 else axis
        val n = co.size / 3
        val dmin = FloatArray(3)
        val dmax = FloatArray(3)
        if (flag and 64 == 0) {
            dmin.fill(Float.MAX_VALUE)
            dmax.fill(-Float.MAX_VALUE)
            var any = false
            for (v in 0 until n) {
                if ((mask?.get(v) ?: 1f) <= 0f) continue
                val c = BlendAnimator.transformPoint(curveSpace, co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
                for (k in 0 until 3) { dmin[k] = minOf(dmin[k], c[k]); dmax[k] = maxOf(dmax[k], c[k]) }
                any = true
            }
            if (!any) return
        }
        val stretch = flag and 128 != 0
        val plen = path.length
        for (v in 0 until n) {
            val w = mask?.get(v) ?: 1f
            if (w <= 0f) continue
            val c = BlendAnimator.transformPoint(curveSpace, co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
            val fac = if (neg) {
                if (stretch) -(c[index] - dmax[index]) / (dmax[index] - dmin[index]).takeIf { it != 0f }.let { it ?: 1f }
                else if (plen != 0f) -(c[index] - dmax[index]) / plen else 0f
            } else {
                if (stretch) (c[index] - dmin[index]) / (dmax[index] - dmin[index]).takeIf { it != 0f }.let { it ?: 1f }
                else if (plen != 0f) (c[index] - dmin[index]) / plen else 0f
            }
            val s = path.where(fac) ?: continue
            val q = BlendPath.normalizeQ(BlendPath.applyTrack(s.quat, axis, if (axis == 0 || axis == 2) 1 else 0))
            var cent = BlendPath.applyTrackVec(c, axis)
            cent[index] = 0f
            if (flag and (1 shl 12) != 0) cent = RigMath.scale(cent, s.radius)
            cent = BlendPath.rotate(q, cent)
            val nc = RigMath.add(cent, s.vec)
            val o = BlendAnimator.transformPoint(objectSpace, nc[0], nc[1], nc[2])
            co[v * 3] += (o[0] - co[v * 3]) * w
            co[v * 3 + 1] += (o[1] - co[v * 3 + 1]) * w
            co[v * 3 + 2] += (o[2] - co[v * 3 + 2]) * w
        }
    }

    private class Shape(val keyAt: Int, val name: String, val delta: FloatArray, val staticValue: Float, val min: Float, val max: Float, val animated: Boolean)

    private fun shapeKeys(ob: BlendFile.View, me: BlendFile.View, vdata: List<Layer>, vCount: Int): Pair<FloatArray?, List<Shape>> {
        val a = animator ?: return null to emptyList()
        val keyAt = f.offsetOf(me.ptr("key")) ?: return null to emptyList()
        val key = f.view("Key", keyAt) ?: return null to emptyList()
        if (key.int("type") != 1) return null to emptyList()
        val blocks = a.listItems(key.sub("block"), "KeyBlock")
        if (blocks.size < 2) return null to emptyList()
        fun data(kb: BlendFile.View): FloatArray? {
            if (kb.int("totelem") != vCount) return null
            val at = f.offsetOf(kb.ptr("data")) ?: return null
            if (at + vCount * 12 > f.bytes.size) return null
            return FloatArray(vCount * 3) { f.bb.getFloat(at + it * 4) }
        }
        val refAt = f.offsetOf(key.ptr("refkey"))
        val ref = blocks.firstOrNull { it.at == refAt } ?: blocks[0]
        val base = data(ref)
        val groupList = me.sub("vertex_group_names")?.takeIf { it.ptr("first") != 0L } ?: ob.sub("defbase")
        val groups = a.listItems(groupList, "bDeformGroup").map { it.str("name") }
        val shapes = ArrayList<Shape>()
        for (kb in blocks) {
            if (kb.at == ref.at || kb.int("flag") and 1 != 0) continue
            val d = data(kb) ?: continue
            val rel = blocks.getOrNull(kb.int("relative")) ?: ref
            val r = data(rel) ?: continue
            val delta = FloatArray(vCount * 3) { d[it] - r[it] }
            val vg = kb.str("vgroup")
            if (vg.isNotEmpty()) {
                val gi = groups.indexOf(vg)
                val vw = vertexGroupWeights(me, vdata, vCount, gi)
                for (v in 0 until vCount) for (c in 0 until 3) delta[v * 3 + c] *= vw[v]
            }
            if (delta.all { it == 0f }) continue
            val name = kb.str("name")
            val min = kb.float("slidermin", 0f)
            val max = kb.float("slidermax", 1f).let { if (it <= min) min + 1f else it }
            shapes.add(Shape(keyAt, name, delta, kb.float("curval").coerceIn(min, max), min, max, a.keyAnimated(keyAt, name)))
        }
        return base to shapes
    }

    private fun vertexGroupWeights(me: BlendFile.View, vdata: List<Layer>, vCount: Int, group: Int): FloatArray {
        val res = FloatArray(vCount)
        if (group < 0) return res
        val dvSt = f.struct("MDeformVert") ?: return res
        val dwSt = f.struct("MDeformWeight") ?: return res
        val dvAt = f.offsetOf(me.ptr("dvert")) ?: vdata.firstOrNull { it.structType == "MDeformVert" }?.dataAt ?: return res
        val dwOff = dvSt.fields["dw"]?.offset ?: 0
        val totOff = dvSt.fields["totweight"]?.offset ?: f.ptrSize
        val defOff = dwSt.fields["def_nr"]?.offset ?: 0
        val wOff = dwSt.fields["weight"]?.offset ?: 4
        for (v in 0 until vCount) {
            val at = dvAt + v * dvSt.size
            if (at + dvSt.size > f.bytes.size) break
            val tot = f.bb.getInt(at + totOff).coerceIn(0, 64)
            val dwAt = f.offsetOf(f.ptr(at + dwOff)) ?: continue
            for (k in 0 until tot) {
                val wa = dwAt + k * dwSt.size
                if (wa + dwSt.size > f.bytes.size) break
                if (f.bb.getInt(wa + defOff) == group) res[v] = f.bb.getFloat(wa + wOff)
            }
        }
        return res
    }

    private fun smoothNormals(p: FloatArray, cv: IntArray, fs: IntArray, fz: IntArray, smooth: BooleanArray, spec: NormalSpec?): FloatArray {
        if (f.version < 401 && spec == null) {
            val vn = MeshTopology(cv, fs, fz, IntArray(0)).vertexNormals(p)
            val fn = BlendNormals.faceNormals(p, cv, fs, fz)
            val out = FloatArray(cv.size * 3)
            for (face in fs.indices) for (k in 0 until fz[face]) {
                val c = fs[face] + k
                if (c >= cv.size) continue
                val v = cv[c]
                for (d in 0 until 3) out[c * 3 + d] = if (smooth.getOrElse(face) { false }) (if (v * 3 + 2 < vn.size) vn[v * 3 + d] else 0f) else fn[face * 3 + d]
            }
            return out
        }
        return BlendNormals.cornerNormals(p, cv, fs, fz, BooleanArray(fs.size) { !smooth.getOrElse(it) { false } }, spec)
    }

    private fun mdisps(ob: BlendFile.View, nCorners: Int, totlvl: Int): MultiresDisp? {
        val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
        val ldata = layers(me, "corner_data", "ldata")
        val layer = ldata.firstOrNull { it.type == 19 } ?: return null
        val st = f.struct("MDisps") ?: return null
        if (layer.count < nCorners) return null
        val totOff = st.fields["totdisp"]?.offset ?: 0
        val dOff = st.fields["disps"]?.offset ?: 8
        val gs = (1 shl (totlvl - 1)) + 1
        val grids = arrayOfNulls<FloatArray>(nCorners)
        for (c in 0 until nCorners) {
            val at = layer.dataAt + c * st.size
            if (at + st.size > f.bytes.size) break
            val tot = f.bb.getInt(at + totOff)
            if (tot != gs * gs) continue
            val da = f.offsetOf(f.ptr(at + dOff)) ?: continue
            if (da + tot * 12 > f.bytes.size) continue
            grids[c] = FloatArray(tot * 3) { f.bb.getFloat(da + it * 4) }
        }
        return MultiresDisp(grids, gs)
    }

    private fun curveProfile(ptr: Long): BevelCurveProfile.Profile? {
        val at = f.offsetOf(ptr) ?: return null
        val cp = f.view("CurveProfile", at) ?: return null
        val n = cp.int("path_len")
        if (n < 2 || n > 512) return null
        val pst = f.struct("CurveProfilePoint") ?: return null
        val pAt = f.offsetOf(cp.ptr("path")) ?: return null
        if (pAt + n * pst.size > f.bytes.size) return null
        val pts = Array(n) { i ->
            val p = f.View(pst, pAt + i * pst.size)
            BevelCurveProfile.Point(p.float("x"), p.float("y"), p.int("h1") and 0xFF, p.int("h2") and 0xFF).also { q ->
                p.floats("h1_loc", 2)?.copyInto(q.h1Loc)
                p.floats("h2_loc", 2)?.copyInto(q.h2Loc)
            }
        }
        return BevelCurveProfile.Profile(pts, cp.int("flag"))
    }

    private fun seamEdges(me: BlendFile.View): Set<Long> {
        val edata = layers(me, "edge_data", "edata") + storageLayers(me, 1)
        val edges = topology(me)?.edges ?: return emptySet()
        val out = HashSet<Long>()
        val attr = edata.firstOrNull { it.name == ".uv_seam" && it.elemSize == 1 }
        if (attr != null) {
            for (e in 0 until minOf(attr.count, edges.size / 2)) if (f.bytes[attr.dataAt + e].toInt() != 0) out.add(BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]))
            return out
        }
        val st = f.struct("MEdge") ?: return out
        val ff = st.fields["flag"] ?: return out
        val src = arrayBlock(me.ptr("medge"))?.let { (at, b) -> at to b.count } ?: edata.firstOrNull { it.structType == "MEdge" }?.let { it.dataAt to it.count } ?: return out
        for (e in 0 until minOf(src.second, edges.size / 2)) if (f.bb.getShort(src.first + e * st.size + ff.offset).toInt() and (1 shl 2) != 0) out.add(BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]))
        return out
    }

    internal fun normalSpec(me: BlendFile.View, nCorners: Int): NormalSpec? {
        val legacy = f.version < 401
        val auto = legacy && (me.int("flag") and (1 shl 5)) != 0
        if (legacy && !auto) return null
        val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
        val edata = layers(me, "edge_data", "edata") + storageLayers(me, 1)
        val edges = topology(me)?.edges ?: IntArray(0)
        val sharp = HashSet<Long>()
        val sharpAttr = edata.firstOrNull { it.name == "sharp_edge" && it.elemSize == 1 }
        if (sharpAttr != null) {
            for (e in 0 until minOf(sharpAttr.count, edges.size / 2)) if (f.bytes[sharpAttr.dataAt + e].toInt() != 0) sharp.add(BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]))
        } else {
            val st = f.struct("MEdge")
            val ff = st?.fields?.get("flag")
            val src = arrayBlock(me.ptr("medge"))?.let { (at, b) -> at to b.count } ?: edata.firstOrNull { it.structType == "MEdge" }?.let { it.dataAt to it.count }
            if (st != null && ff != null && src != null) for (e in 0 until minOf(src.second, edges.size / 2)) {
                if (f.bb.getShort(src.first + e * st.size + ff.offset).toInt() and (1 shl 9) != 0) sharp.add(BlendMeshData.key(edges[e * 2], edges[e * 2 + 1]))
            }
        }
        val cn = ldata.firstOrNull { it.name == "custom_normal" } ?: ldata.firstOrNull { it.type == 41 }
        val customShort = cn?.takeIf { it.elemSize == 4 && it.count >= nCorners }?.let { l -> ShortArray(nCorners * 2) { i -> f.bb.getShort(l.dataAt + (i / 2) * 4 + (i % 2) * 2) } }
        val customFloat = cn?.takeIf { it.elemSize == 12 && it.count >= nCorners }?.let { l -> FloatArray(nCorners * 3) { i -> f.bb.getFloat(l.dataAt + (i / 3) * 12 + (i % 3) * 4) } }
        val angle = if (auto) (if (me.has("smoothresh")) me.float("smoothresh") else if (me.has("smoothresh_legacy")) me.float("smoothresh_legacy") else 0.523599f) else null
        if (sharp.isEmpty() && customShort == null && customFloat == null && angle == null) return if (legacy) NormalSpec(emptySet(), null, null, null) else null
        return NormalSpec(sharp, customShort, customFloat, angle)
    }

    private fun mat4(v: BlendFile.View, n: String): FloatArray? {
        val m = v.floats(n, 16) ?: return null
        if (m.all { it == 0f }) return null
        return m
    }

    private fun local(ob: BlendFile.View): FloatArray {
        val loc = ob.floats("loc", 3) ?: floatArrayOf(0f, 0f, 0f)
        val scale = (ob.floats("scale", 3) ?: ob.floats("size", 3)) ?: floatArrayOf(1f, 1f, 1f)
        val mode = ob.int("rotmode", 1)
        val r: FloatArray = when (mode) {
            0 -> {
                val q = ob.floats("quat", 4) ?: floatArrayOf(1f, 0f, 0f, 0f)
                GltfParser.trs(null, floatArrayOf(q[1], q[2], q[3], q[0]), null)
            }
            -1 -> {
                val axis = ob.floats("rotAxis", 3) ?: floatArrayOf(0f, 1f, 0f)
                val ang = ob.float("rotAngle")
                val l = kotlin.math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]).takeIf { it > 0f } ?: 1f
                val s = sin(ang / 2)
                GltfParser.trs(null, floatArrayOf(axis[0] / l * s, axis[1] / l * s, axis[2] / l * s, cos(ang / 2)), null)
            }
            else -> euler(ob.floats("rot", 3) ?: floatArrayOf(0f, 0f, 0f), mode)
        }
        return GltfParser.mul(GltfParser.trs(loc, null, null), GltfParser.mul(r, GltfParser.trs(null, null, scale)))
    }

    private fun euler(e: FloatArray, mode: Int): FloatArray {
        fun axis(i: Int, a: Float): FloatArray {
            val c = cos(a)
            val s = sin(a)
            val m = GltfParser.IDENTITY.copyOf()
            when (i) {
                0 -> { m[5] = c; m[6] = s; m[9] = -s; m[10] = c }
                1 -> { m[0] = c; m[2] = -s; m[8] = s; m[10] = c }
                else -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c }
            }
            return m
        }
        val x = axis(0, e[0])
        val y = axis(1, e[1])
        val z = axis(2, e[2])
        val m = GltfParser::mul
        return when (mode) {
            2 -> m(y, m(z, x))
            3 -> m(z, m(x, y))
            4 -> m(x, m(z, y))
            5 -> m(y, m(x, z))
            6 -> m(x, m(y, z))
            else -> m(z, m(y, x))
        }
    }

    private fun world(ob: BlendFile.View, depth: Int): FloatArray {
        worldCache[ob.at]?.let { return it }
        val stored = mat4(ob, "object_to_world") ?: mat4(ob, "obmat")
        val w = stored ?: run {
            val parentAt = f.offsetOf(ob.ptr("parent"))
            val parent = if (parentAt != null && depth < 64) f.view("Object", parentAt) else null
            val l = local(ob)
            if (parent != null) {
                val pinv = mat4(ob, "parentinv") ?: GltfParser.IDENTITY
                GltfParser.mul(world(parent, depth + 1), GltfParser.mul(pinv, l))
            } else l
        }
        worldCache[ob.at] = w
        return w
    }

    internal class Layer(val type: Int, val name: String, val dataAt: Int, val count: Int, val elemSize: Int, val structType: String?)

    internal fun layers(me: BlendFile.View, vararg names: String): List<Layer> {
        val cd = names.firstNotNullOfOrNull { me.sub(it) } ?: return emptyList()
        val domainCount = when (names.firstOrNull()) {
            "vert_data", "vdata" -> domainSize(me, 0)
            "edge_data", "edata" -> domainSize(me, 1)
            "face_data", "pdata" -> domainSize(me, 2)
            "corner_data", "ldata" -> domainSize(me, 3)
            "fdata_legacy", "fdata" -> me.int("totface_legacy", me.int("totface"))
            else -> 0
        }
        val total = cd.int("totlayer")
        val layersAt = f.offsetOf(cd.ptr("layers")) ?: return emptyList()
        val ls = f.struct("CustomDataLayer") ?: return emptyList()
        val res = ArrayList<Layer>()
        for (i in 0 until total.coerceIn(0, 4096)) {
            val l = f.View(ls, layersAt + i * ls.size)
            val ptr = l.ptr("data")
            val blk = f.block(ptr) ?: continue
            val at = f.offsetOf(ptr) ?: continue
            var count = blk.count.coerceAtLeast(1)
            if (count == 1 && domainCount > 1 && blk.len >= domainCount && blk.len % domainCount == 0) count = domainCount
            val st = f.structs.getOrNull(blk.sdna)?.type
            res.add(Layer(l.int("type"), l.str("name"), at, count, blk.len / count, st))
        }
        return res
    }

    private fun boneSelected(arm: BlendFile.View, name: String): Boolean {
        val pose = f.offsetOf(arm.ptr("pose"))?.let { f.view("bPose", it) } ?: return false
        var p = pose.sub("chanbase")?.ptr("first") ?: 0L
        var guard = 0
        while (p != 0L && guard++ < 4096) {
            val at = f.offsetOf(p) ?: return false
            val pc = f.view("bPoseChannel", at) ?: return false
            if (pc.str("name") == name) {
                val bone = f.offsetOf(pc.ptr("bone"))?.let { f.view("Bone", it) } ?: return false
                return bone.int("flag") and 1 != 0
            }
            p = pc.ptr("next")
        }
        return false
    }

    private fun idProps(ptr: Long): Map<String, Double> {
        val res = HashMap<String, Double>()
        val at = f.offsetOf(ptr) ?: return res
        val group = f.view("IDProperty", at) ?: return res
        val data = group.sub("data") ?: return res
        var p = data.sub("group")?.ptr("first") ?: 0L
        var guard = 0
        while (p != 0L && guard++ < 1024) {
            val pa = f.offsetOf(p) ?: break
            val prop = f.view("IDProperty", pa) ?: break
            val d = prop.sub("data")
            val name = prop.str("name")
            if (d != null) {
                when (prop.int("type")) {
                    1, 10 -> res[name] = d.int("val").toDouble()
                    2 -> res[name] = java.lang.Float.intBitsToFloat(d.int("val")).toDouble()
                    8 -> res[name] = java.lang.Double.longBitsToDouble((d.int("val").toLong() and 0xFFFFFFFFL) or (d.int("val2").toLong() shl 32))
                }
            }
            p = prop.ptr("next")
        }
        return res
    }

    private fun hasUvLayer(me: BlendFile.View): Boolean {
        val cd = me.sub("corner_data") ?: me.sub("ldata") ?: return false
        val total = cd.int("totlayer").coerceIn(0, 4096)
        val layersAt = f.offsetOf(cd.ptr("layers")) ?: return false
        val ls = f.struct("CustomDataLayer") ?: return false
        for (i in 0 until total) {
            val l = f.View(ls, layersAt + i * ls.size)
            val t = l.int("type")
            if ((t == 49 || t == 16) && !l.str("name").startsWith(".")) return true
        }
        return false
    }

    private fun cstr(ptr: Long): String {
        val at = f.offsetOf(ptr) ?: return ""
        var e = at
        while (e < f.bytes.size && f.bytes[e].toInt() != 0) e++
        return String(f.bytes, at, e - at, Charsets.UTF_8)
    }

    private fun domainSize(me: BlendFile.View, domain: Int): Int = when (domain) {
        0 -> if (me.has("verts_num")) me.int("verts_num") else me.int("totvert")
        1 -> if (me.has("edges_num")) me.int("edges_num") else me.int("totedge")
        2 -> if (me.has("faces_num")) me.int("faces_num") else me.int("totpoly")
        3 -> if (me.has("corners_num")) me.int("corners_num") else me.int("totloop")
        else -> 0
    }

    internal fun storageLayers(me: BlendFile.View, domain: Int): List<Layer> {
        val st = me.sub("attribute_storage") ?: return emptyList()
        val n = st.int("dna_attributes_num").coerceIn(0, 4096)
        val base = f.offsetOf(st.ptr("dna_attributes")) ?: return emptyList()
        val attrSt = f.struct("Attribute") ?: return emptyList()
        val arrSt = f.struct("AttributeArray") ?: return emptyList()
        val res = ArrayList<Layer>()
        for (i in 0 until n) {
            val a = f.View(attrSt, base + i * attrSt.size)
            if (a.int("domain") != domain) continue
            val holderAt = f.offsetOf(a.ptr("data")) ?: continue
            val arr = f.View(arrSt, holderAt)
            val dataPtr = arr.ptr("data")
            val blk = f.block(dataPtr) ?: continue
            val at = f.offsetOf(dataPtr) ?: continue
            if (a.has("storage_type") && a.int("storage_type") == 1) {
                val n = domainSize(me, domain)
                if (n > 0) res.add(Layer(-1000 - a.int("data_type"), cstr(a.ptr("name")), at, n, 0, null))
                continue
            }
            val size = arr.int("size").takeIf { it > 0 } ?: blk.count
            if (size <= 0) continue
            res.add(Layer(-1000 - a.int("data_type"), cstr(a.ptr("name")), at, size, blk.len / size, null))
        }
        return res
    }

    private fun ints(at: Int, n: Int, stride: Int = 4, fieldOffset: Int = 0): IntArray = IntArray(n) { f.bb.getInt(at + it * stride + fieldOffset) }

    private fun arrayBlock(ptr: Long): Pair<Int, BlendFile.Block>? {
        val b = f.block(ptr) ?: return null
        val at = f.offsetOf(ptr) ?: return null
        return at to b
    }

    private class Faces(val corners: IntArray, val start: IntArray, val size: IntArray, val mat: IntArray?, val smooth: BooleanArray?, val legacy: Boolean)

    private fun faces(me: BlendFile.View, ldata: List<Layer>, pdata: List<Layer>, fdata: List<Layer>): Faces? {
        var corners: IntArray? = null
        ldata.firstOrNull { it.name == ".corner_vert" }?.let { l -> corners = ints(l.dataAt, l.count, l.elemSize) }
        if (corners == null) {
            val st = f.struct("MLoop")
            val vOff = st?.fields?.get("v")?.offset ?: 0
            val direct = arrayBlock(me.ptr("mloop"))
            if (direct != null) {
                corners = ints(direct.first, direct.second.count, st?.size ?: 8, vOff)
            } else {
                ldata.firstOrNull { it.structType == "MLoop" }?.let { ml -> corners = ints(ml.dataAt, ml.count, ml.elemSize, vOff) }
            }
        }

        var faceStart: IntArray? = null
        var faceSize: IntArray? = null
        var faceMat: IntArray? = null
        val offsetsPtr = if (me.has("face_offset_indices")) me.ptr("face_offset_indices") else me.ptr("poly_offset_indices")
        arrayBlock(offsetsPtr)?.let { (at, b) ->
            val n = b.len / 4
            val o = ints(at, n)
            faceStart = IntArray(maxOf(n - 1, 0)) { o[it] }
            faceSize = IntArray(maxOf(n - 1, 0)) { o[it + 1] - o[it] }
        }
        if (faceStart == null) {
            val mp = arrayBlock(me.ptr("mpoly"))?.let { (at, b) -> Triple(at, b.count, f.struct("MPoly")) }
                ?: pdata.firstOrNull { it.structType == "MPoly" }?.let { Triple(it.dataAt, it.count, f.struct("MPoly")) }
            if (mp != null && mp.third != null) {
                val st = mp.third!!
                val ls = st.fields["loopstart"]?.offset ?: 0
                val tl = st.fields["totloop"]?.offset ?: 4
                val mn = st.fields["mat_nr"]
                faceStart = IntArray(mp.second) { f.bb.getInt(mp.first + it * st.size + ls) }
                faceSize = IntArray(mp.second) { f.bb.getInt(mp.first + it * st.size + tl) }
                if (mn != null) faceMat = IntArray(mp.second) { f.bb.getShort(mp.first + it * st.size + mn.offset).toInt() }
            }
        }
        pdata.firstOrNull { it.name == "material_index" }?.let { l -> faceMat = ints(l.dataAt, l.count, l.elemSize) }
        var faceSmooth: BooleanArray? = null
        val legacyPoly = arrayBlock(me.ptr("mpoly"))?.let { (at, b) -> Triple(at, b.count, f.struct("MPoly")) }
            ?: pdata.firstOrNull { it.structType == "MPoly" }?.let { Triple(it.dataAt, it.count, f.struct("MPoly")) }
        val flagField = legacyPoly?.third?.fields?.get("flag")
        if (legacyPoly != null && flagField != null) {
            val st = legacyPoly.third!!
            faceSmooth = BooleanArray(legacyPoly.second) { (f.bytes[legacyPoly.first + it * st.size + flagField.offset].toInt() and 1) != 0 }
        } else if (faceStart != null) {
            val sharp = pdata.firstOrNull { it.name == "sharp_face" }
            faceSmooth = BooleanArray(faceStart.size) { i -> sharp == null || i >= sharp.count || f.bytes[sharp.dataAt + i * sharp.elemSize].toInt() == 0 }
        }

        var legacyFaces = false
        if (corners == null || faceStart == null || faceSize == null) {
            val st = f.struct("MFace") ?: return null
            val direct = arrayBlock(me.ptr("mface"))
            val legacy = fdata.firstOrNull { it.structType == "MFace" }
            val at = direct?.first ?: legacy?.dataAt ?: return null
            val count = direct?.second?.count ?: legacy?.count ?: return null
            val o1 = st.fields["v1"]?.offset ?: 0
            val o2 = st.fields["v2"]?.offset ?: 4
            val o3 = st.fields["v3"]?.offset ?: 8
            val o4 = st.fields["v4"]?.offset ?: 12
            val mo = st.fields["mat_nr"]
            val fo = st.fields["flag"]
            val cvl = ArrayList<Int>()
            val fsl = IntArray(count)
            val fzl = IntArray(count)
            val fml = IntArray(count)
            val sml = BooleanArray(count)
            for (i in 0 until count) {
                val base = at + i * st.size
                if (base + st.size > f.bytes.size) break
                val q = intArrayOf(f.bb.getInt(base + o1), f.bb.getInt(base + o2), f.bb.getInt(base + o3), f.bb.getInt(base + o4))
                fsl[i] = cvl.size
                val n = if (q[3] != 0) 4 else 3
                fzl[i] = n
                for (k in 0 until n) cvl.add(q[k])
                fml[i] = if (mo != null) f.bb.getShort(base + mo.offset).toInt() else 0
                sml[i] = fo != null && (f.bytes[base + fo.offset].toInt() and 1) != 0
            }
            corners = cvl.toIntArray()
            faceStart = fsl
            faceSize = fzl
            faceMat = fml
            faceSmooth = sml
            legacyFaces = true
        }
        return Faces(corners, faceStart, faceSize, faceMat, faceSmooth, legacyFaces)
    }

    internal fun topology(me: BlendFile.View): MeshTopology? {
        val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
        val pdata = layers(me, "face_data", "pdata") + storageLayers(me, 2)
        val fdata = layers(me, "fdata_legacy", "fdata")
        val fc = faces(me, ldata, pdata, fdata) ?: return null
        val edata = layers(me, "edge_data", "edata") + storageLayers(me, 1)
        return MeshTopology(fc.corners, fc.start, fc.size, fileEdges(me, edata) ?: MeshTopology.edgesFromFaces(fc.corners, fc.start, fc.size))
    }

    private fun fileEdges(me: BlendFile.View, edata: List<Layer>): IntArray? {
        var edges: IntArray? = null
        edata.firstOrNull { it.name == ".edge_verts" }?.let { l -> edges = IntArray(l.count * 2) { f.bb.getInt(l.dataAt + (it / 2) * l.elemSize + (it % 2) * 4) } }
        if (edges == null) {
            val st = f.struct("MEdge")
            if (st != null) {
                val o1 = st.fields["v1"]?.offset ?: 0
                val o2 = st.fields["v2"]?.offset ?: 4
                val src = arrayBlock(me.ptr("medge"))?.let { (at, b) -> at to b.count } ?: edata.firstOrNull { it.structType == "MEdge" }?.let { it.dataAt to it.count }
                if (src != null) edges = IntArray(src.second * 2) { f.bb.getInt(src.first + (it / 2) * st.size + if (it % 2 == 0) o1 else o2) }
            }
        }
        return edges
    }

    private fun emitMesh(ob: BlendFile.View, me: BlendFile.View, world: FloatArray) {
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val ldata = layers(me, "corner_data", "ldata") + storageLayers(me, 3)
        val pdata = layers(me, "face_data", "pdata") + storageLayers(me, 2)
        val fdata = layers(me, "fdata_legacy", "fdata")

        var positions: FloatArray? = null
        arrayBlock(me.ptr("mvert"))?.let { (at, b) ->
            val st = f.struct("MVert")
            val stride = st?.size ?: (b.len / b.count.coerceAtLeast(1))
            val co = st?.fields?.get("co")?.offset ?: 0
            positions = FloatArray(b.count * 3) { f.bb.getFloat(at + (it / 3) * stride + co + (it % 3) * 4) }
        }
        if (positions == null) {
            val l = vdata.firstOrNull { it.name == "position" } ?: vdata.firstOrNull { it.type == 0 && it.structType == "MVert" }
            if (l != null) {
                val co = if (l.structType == "MVert") f.struct("MVert")?.fields?.get("co")?.offset ?: 0 else 0
                positions = FloatArray(l.count * 3) { f.bb.getFloat(l.dataAt + (it / 3) * l.elemSize + co + (it % 3) * 4) }
            }
        }
        val pos = positions ?: return
        val vCount = pos.size / 3

        val aEarly = animator
        val stackEarly = if (aEarly != null) deformStack(ob) else emptyList()
        if (stackEarly.any { it is Deform.Constructive }) {
            emitEvaluated(ob, me, world, stackEarly)
            return
        }

        val fc = faces(me, ldata, pdata, fdata) ?: return
        val cv = fc.corners
        val fs = fc.start
        val fz = fc.size
        val faceMat = fc.mat
        val faceSmooth = fc.smooth
        val legacyFaces = fc.legacy

        val matPtrs = materialPtrs(ob, me)
        val mats = matPtrs.map { material(it) }
        val normalTexs = matPtrs.map { normalTexture(it) }
        val uvNames = listOf(cstr(me.ptr("default_uv_map_attribute")), cstr(me.ptr("active_uv_map_attribute"))).filter { it.isNotEmpty() }
        val uvLayer = if (legacyFaces) null else uvNames.firstNotNullOfOrNull { n -> ldata.firstOrNull { it.name == n && it.elemSize == 8 } }
            ?: ldata.firstOrNull { (it.type == 49 || it.type <= -1000) && !it.name.startsWith(".") && it.elemSize == 8 }
            ?: ldata.firstOrNull { it.structType == "MLoopUV" }
        val uvOffset = if (uvLayer?.structType == "MLoopUV") f.struct("MLoopUV")?.fields?.get("uv")?.offset ?: 0 else 0
        val colorNames = listOf(cstr(me.ptr("default_color_attribute")), cstr(me.ptr("active_color_attribute"))).filter { it.isNotEmpty() }
        fun isColor(l: Layer) = (l.type == 17 && l.elemSize == 4) || (l.type == 47 && l.elemSize == 16)
        fun named(list: List<Layer>) = colorNames.firstNotNullOfOrNull { n -> list.firstOrNull { it.name == n && (it.elemSize == 4 || it.elemSize == 16) } }
        val cornerColor = if (legacyFaces) null else named(ldata) ?: ldata.firstOrNull { isColor(it) }
        val pointColor = if (cornerColor == null) named(vdata) ?: vdata.firstOrNull { isColor(it) } else null

        fun color(l: Layer, i: Int): Int {
            val at = l.dataAt + i * l.elemSize
            return if (l.elemSize == 4) {
                val r = f.bytes[at].toInt() and 0xFF
                val g = f.bytes[at + 1].toInt() and 0xFF
                val b = f.bytes[at + 2].toInt() and 0xFF
                val a = f.bytes[at + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                fun ch(k: Int): Int {
                    val v = f.bb.getFloat(at + k * 4).coerceIn(0f, 1f).toDouble()
                    val s = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
                    return (s * 255).roundToInt().coerceIn(0, 255)
                }
                ((f.bb.getFloat(at + 12).coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
        }

        val (refBase, shapes) = shapeKeys(ob, me, vdata, vCount)
        val local = if (refBase != null && refBase.size == pos.size) refBase.copyOf() else pos.copyOf()
        for (s in shapes) if (s.staticValue != 0f) for (i in local.indices) local[i] += s.staticValue * s.delta[i]

        val a = animator
        val stack = if (a != null) deformStack(ob) else emptyList()
        if (stack.any { it is Deform.Constructive }) {
            emitEvaluated(ob, me, world, stack)
            return
        }
        val single = (stack.singleOrNull() as? Deform.Armature)?.takeIf { !it.multi }
        val skin = if (a != null && single != null) skinData(ob, me, vdata, local, single) else null
        val baseLocal = if (refBase != null && refBase.size == pos.size) refBase else pos
        val bakeCtx = if (a != null && stack.isNotEmpty() && single == null) deformContext(ob, me, vdata, local, stack) else null
        val wp = if (bakeCtx != null && a != null) deformWorld(a.staticFrame, ob, stack, bakeCtx, local)
        else if (skin != null && a != null) skinPositions(a.staticFrame, ob, skin, local) else FloatArray(local.size).also { o ->
            for (v in 0 until vCount) {
                val x = local[v * 3]; val y = local[v * 3 + 1]; val z = local[v * 3 + 2]
                o[v * 3] = world[0] * x + world[4] * y + world[8] * z + world[12]
                o[v * 3 + 1] = world[1] * x + world[5] * y + world[9] * z + world[13]
                o[v * 3 + 2] = world[2] * x + world[6] * y + world[10] * z + world[14]
            }
        }

        val baked = if (bakeCtx != null && stackAnimated(ob, stack, shapes)) bake(ob, stack, bakeCtx, baseLocal, shapes, wp, fs.indices.sumOf { maxOf(fz[it] - 2, 0) }) else null
        val animate = a != null && frames.isNotEmpty() && baked == null
        var skinIds: IntArray? = null
        var dqPre = -1
        if (animate && skin != null && (a.armatureAnimated(skin.arm) || a.dynamic(ob) || a.dynamic(skin.arm))) {
            val arm = skin.arm
            if (!skin.dq) {
                if (budgetOk(skin.names.size + 1)) {
                    val ids = IntArray(skin.names.size + 1) { i ->
                        if (i < skin.names.size) addBone("${ob.at}:${skin.names[i]}") { fr -> skinMatrix(fr, ob, arm, skin.names[i]) }
                        else addBone("${ob.at}:#mesh") { fr -> fr.world(ob) }
                    }
                    if (ids.all { it >= 0 }) skinIds = ids
                }
            } else if (budgetOk(skin.names.size * 2 + 4)) {
                val ids = IntArray(skin.names.size + 1) { i ->
                    if (i < skin.names.size) {
                        val n = skin.names[i]
                        addBonePair("${arm.at}:dq:$n", { fr -> dqSplit(fr, arm, n).first }, { fr -> dqSplit(fr, arm, n).second })
                    } else addBonePair("#dqident", { GltfParser.IDENTITY }, { GltfParser.IDENTITY })
                }
                val pp = addBonePair("${ob.at}:#prepost", { fr -> GltfParser.mul(BlendAnimator.invert(fr.world(arm)), fr.world(ob)) }, { fr -> fr.world(arm) })
                if (ids.all { it >= 0 } && pp >= 0) {
                    skinIds = ids
                    dqPre = pp
                }
            }
        }
        val skinned = skinIds != null && skin != null
        val rigid = if (animate && !skinned && a.dynamic(ob)) {
            val inv = BlendAnimator.invert(world)
            addBone("${ob.at}") { fr -> GltfParser.mul(fr.world(ob), inv) }
        } else -1

        val animShapes = if (animate) shapes.filter { it.animated } else emptyList()
        val morphIds = if (baked != null) baked.ids else IntArray(animShapes.size) { i ->
            val s = animShapes[i]
            val path = a!!.keyPath(s.keyAt, s.name)
            morphFns.add { _, fr -> fr.prop(s.keyAt, path, 0).coerceIn(s.min, s.max) }
            morphBases.add(s.staticValue)
            morphFns.size - 1
        }
        val morphDeltas = if (baked != null) baked.deltas else animShapes.map { s ->
            if (skinned) s.delta else FloatArray(s.delta.size) { i ->
                val v = i / 3
                val c = i % 3
                world[c] * s.delta[v * 3] + world[4 + c] * s.delta[v * 3 + 1] + world[8 + c] * s.delta[v * 3 + 2]
            }
        }

        val smoothFlags = faceSmooth
        val hasSmooth = fs.isNotEmpty()
        val nSpec = if (hasSmooth) normalSpec(me, cv.size) else null
        val staticN = if (hasSmooth) smoothNormals(wp, cv, fs, fz, (smoothFlags ?: BooleanArray(0)), nSpec) else null
        val bindN = if (hasSmooth && skinned) smoothNormals(local, cv, fs, fz, (smoothFlags ?: BooleanArray(0)), nSpec) else null
        val morphN = if (hasSmooth) morphDeltas.map { d ->
            val src = if (skinned) local else wp
            val n0 = if (skinned) bindN!! else staticN!!
            val n1 = smoothNormals(FloatArray(src.size) { src[it] + d[it] }, cv, fs, fz, (smoothFlags ?: BooleanArray(0)), nSpec)
            FloatArray(n1.size) { n1[it] - n0[it] }
        } else null

        val tri = FloatArray(9)
        val uv = FloatArray(6)
        val cs = IntArray(3)
        val nTri = FloatArray(9)
        val anim = TriAnim()
        val jTri = IntArray(3 * (skin?.k ?: 4))
        val wTri = FloatArray(3 * (skin?.k ?: 4))
        val bTri = FloatArray(9)
        val bnTri = FloatArray(9)
        val ids = IntArray(3)
        val (triCorners, triFace) = MeshTopology(cv, fs, fz, IntArray(0)).cornerTris(wp)
        val triStart = IntArray(fs.size + 1)
        for (t in triFace) if (t in fs.indices) triStart[t + 1]++
        for (i in fs.indices) triStart[i + 1] += triStart[i]
        for (face in fs.indices) {
            if (out.truncated) return
            val start = fs[face]
            val n = fz[face]
            if (n < 3 || start < 0 || start + n > cv.size) continue
            val matIndex = faceMat?.getOrNull(face) ?: 0
            val (color, tex) = mats.getOrNull(matIndex) ?: (0 to -1)
            val ntex = normalTexs.getOrNull(matIndex) ?: -1
            val smoothFace = staticN != null
            for (t in triStart[face] until triStart[face + 1]) {
                ids[0] = triCorners[t * 3]; ids[1] = triCorners[t * 3 + 1]; ids[2] = triCorners[t * 3 + 2]
                var ok = true
                for (slot in 0 until 3) {
                    val c = ids[slot]
                    val v = cv[c]
                    if (v !in 0 until vCount) { ok = false; break }
                    tri[slot * 3] = wp[v * 3]; tri[slot * 3 + 1] = wp[v * 3 + 1]; tri[slot * 3 + 2] = wp[v * 3 + 2]
                    if (smoothFace) {
                        nTri[slot * 3] = staticN[c * 3]; nTri[slot * 3 + 1] = staticN[c * 3 + 1]; nTri[slot * 3 + 2] = staticN[c * 3 + 2]
                    }
                    if ((tex >= 0 || ntex >= 0) && uvLayer != null && c < uvLayer.count) {
                        val at = uvLayer.dataAt + c * uvLayer.elemSize + uvOffset
                        uv[slot * 2] = f.bb.getFloat(at)
                        uv[slot * 2 + 1] = 1f - f.bb.getFloat(at + 4)
                    }
                    cs[slot] = when {
                        cornerColor != null && c < cornerColor.count -> color(cornerColor, c)
                        pointColor != null && v < pointColor.count -> color(pointColor, v)
                        else -> 0
                    }
                }
                if (!ok) continue
                anim.reset()
                var animated = false
                if (skinned) {
                    val kk = skin.k
                    for (slot in 0 until 3) {
                        val c = ids[slot]
                        val v = cv[c]
                        val j = skin.vJ[v]
                        val w = skin.vW[v]
                        for (s in 0 until kk) {
                            jTri[slot * kk + s] = if (s < j.size) skinIds[j[s]] else -1
                            wTri[slot * kk + s] = if (s < j.size) w[s] else 0f
                        }
                        bTri[slot * 3] = local[v * 3]; bTri[slot * 3 + 1] = local[v * 3 + 1]; bTri[slot * 3 + 2] = local[v * 3 + 2]
                        if (smoothFace && bindN != null) {
                            bnTri[slot * 3] = bindN[c * 3]; bnTri[slot * 3 + 1] = bindN[c * 3 + 1]; bnTri[slot * 3 + 2] = bindN[c * 3 + 2]
                        }
                    }
                    anim.dqPre = dqPre
                    anim.joints = jTri
                    anim.weights = wTri
                    anim.bindPos = bTri
                    anim.bindNrm = if (smoothFace && bindN != null) bnTri else null
                    animated = true
                } else if (rigid >= 0) {
                    anim.bone = rigid
                    animated = true
                }
                if (morphIds.isNotEmpty()) {
                    val mIds = ArrayList<Int>()
                    val mD = ArrayList<Float>()
                    val mN = ArrayList<Float>()
                    for ((ti, d) in morphDeltas.withIndex()) {
                        var nz = false
                        for (slot in 0 until 3) {
                            val v = cv[ids[slot]]
                            if (d[v * 3] != 0f || d[v * 3 + 1] != 0f || d[v * 3 + 2] != 0f) nz = true
                        }
                        if (!nz) continue
                        mIds.add(morphIds[ti])
                        val nd = morphN?.get(ti)
                        for (slot in 0 until 3) {
                            val c = ids[slot]
                            val v = cv[c]
                            mD.add(d[v * 3]); mD.add(d[v * 3 + 1]); mD.add(d[v * 3 + 2])
                            if (smoothFace && nd != null) {
                                mN.add(nd[c * 3]); mN.add(nd[c * 3 + 1]); mN.add(nd[c * 3 + 2])
                            } else {
                                mN.add(0f); mN.add(0f); mN.add(0f)
                            }
                        }
                    }
                    if (mIds.isNotEmpty()) {
                        anim.morphTargets = mIds.toIntArray()
                        anim.morphDeltas = mD.toFloatArray()
                        if (smoothFace) anim.morphNormalDeltas = mN.toFloatArray()
                        animated = true
                    }
                }
                val hasUv = (tex >= 0 || ntex >= 0) && uvLayer != null
                out.triangleFull(
                    tri, color, if (hasUv) uv else null, if (hasUv) tex else -1, cs[0], cs[1], cs[2],
                    if (smoothFace) nTri else null, if (hasUv) ntex else -1, if (animated) anim else null
                )
            }
        }
    }

    private fun shapeLocal(fr: BlendAnimator.Frame?, base: FloatArray, shapes: List<Shape>): FloatArray {
        val a = animator
        val local = base.copyOf()
        for (sh in shapes) {
            val w = if (fr != null && a != null && sh.animated) fr.prop(sh.keyAt, a.keyPath(sh.keyAt, sh.name), 0).coerceIn(sh.min, sh.max) else sh.staticValue
            if (w != 0f) for (i in local.indices) local[i] += w * sh.delta[i]
        }
        return local
    }

    private val evalBusy = HashSet<Int>()

    private fun evaluatedAt(fr: BlendAnimator.Frame, ob: BlendFile.View): BlendMeshData? {
        if (!evalBusy.add(ob.at)) return null
        try {
            val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
            val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
            val pos = meshPositions(me, vdata) ?: return null
            val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
            val baseCo = if (refBase != null && refBase.size == pos.size) refBase else pos
            val base = meshData(ob, shapeLocal(fr, baseCo, shapes)) ?: return null
            return evaluateStack(fr, ob, deformStack(ob), base)
        } catch (_: RuntimeException) {
            return null
        } finally {
            evalBusy.remove(ob.at)
        }
    }

    internal fun evaluatedCornerNormals(ob: BlendFile.View): Pair<BlendMeshData, FloatArray>? {
        val d = evaluatedMesh(ob) ?: return null
        return d to smoothNormals(d.co, d.corners, d.fStart, d.fSize, d.faceSmooth ?: BooleanArray(0), d.normalSpec())
    }

    internal fun evaluatedMesh(ob: BlendFile.View): BlendMeshData? {
        val a = animator ?: return null
        val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) } ?: return null
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val pos = meshPositions(me, vdata) ?: return null
        val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
        val baseCo = if (refBase != null && refBase.size == pos.size) refBase else pos
        val base = meshData(ob, shapeLocal(null, baseCo, shapes)) ?: return null
        return evaluateStack(a.staticFrame, ob, deformStack(ob), base)
    }

    private fun evaluateStack(fr: BlendAnimator.Frame, ob: BlendFile.View, stack: List<Deform>, base: BlendMeshData): BlendMeshData {
        var d = base
        var i = 0
        while (i < stack.size) {
            val m = stack[i]
            if (m is Deform.Constructive) {
                d = applyConstructive(fr, ob, m, d)
                i++
                continue
            }
            var j = i
            while (j < stack.size && stack[j] !is Deform.Constructive) j++
            val seg = stack.subList(i, j)
            val cur = d
            val ctx = deformContextFrom(ob, cur.groups, { cur.dverts }, cur.co, seg, { cur.topology() }, { base.co.takeIf { it.size == cur.co.size } }, { cur })
            val co = deformLocal(fr, ob, seg, ctx, cur.co)
            d = BlendMeshData(co, cur.fStart, cur.fSize, cur.corners, cur.faceMat, cur.faceSmooth, cur.uv, cur.cornerColor, cur.pointColor, cur.edgeCrease, cur.vertCrease, cur.looseEdges, cur.dverts, cur.groups).also { it.carryNormals(cur, cur.sharpEdges); it.customShort = cur.customShort; it.customFloat = cur.customFloat; it.fileEdges = cur.fileEdges; it.seamEdges = cur.seamEdges; it.edgeAttrs = cur.edgeAttrs; it.vertAttrs = cur.vertAttrs; it.faceStrength = cur.faceStrength; it.skinRadius = cur.skinRadius; it.skinFlag = cur.skinFlag }
            i = j
        }
        return d
    }

    private fun applyConstructive(fr: BlendAnimator.Frame, ob: BlendFile.View, c: Deform.Constructive, d: BlendMeshData): BlendMeshData {
        val m = c.md
        val name = m.sub("modifier")?.str("name") ?: ""
        fun mp(rna: String, stored: Float) = fr.modifierValue(ob, name, rna, 0, stored)
        return when (c.type) {
            1 -> {
                val levels = mp("levels", m.int("levels", 1).toFloat()).toInt()
                if (levels <= 0 || d.nV == 0) d else {
                    val flags = m.int("flags")
                    val uvs = intArrayOf(BlendSubdiv.FVAR_ALL, BlendSubdiv.FVAR_CORNERS_ONLY, BlendSubdiv.FVAR_CORNERS_PLUS1, BlendSubdiv.FVAR_CORNERS_PLUS2, BlendSubdiv.FVAR_BOUNDARIES, BlendSubdiv.FVAR_NONE)
                    val uvSmooth = if (m.has("uv_smooth")) uvs[m.int("uv_smooth").coerceIn(0, 5)] else if (flags and (1 shl 3) != 0) BlendSubdiv.FVAR_CORNERS_PLUS1 else BlendSubdiv.FVAR_ALL
                    val quality = if (m.has("quality")) m.int("quality", 3) else 3
                    val adaptive = flags and (1 shl 6) == 0
                    BlendMeshData.subsurf(
                        d, levels.coerceIn(1, 6), quality, adaptive, m.int("subdivType") == 1, flags and (1 shl 4) != 0 || !m.has("uv_smooth"),
                        if (m.int("boundary_smooth") == 1) BlendSubdiv.BOUNDARY_EDGE_AND_CORNER else BlendSubdiv.BOUNDARY_EDGE_ONLY, uvSmooth
                    )
                }
            }
            27 -> {
                val dv = d.dverts
                val invert = m.int("flag") and 1 != 0
                val mode = m.int("mode")
                if (dv == null) {
                    if (invert) d else BlendMeshData(FloatArray(0), IntArray(0), IntArray(0), IntArray(0), null, null, null, null, null, emptyMap(), null, IntArray(0), null, d.groups)
                } else if (d.groups.isEmpty() || d.nV == 0) d else {
                    val threshold = mp("threshold", m.float("threshold"))
                    var weights: FloatArray? = null
                    val keep: BooleanArray? = if (mode == 1) {
                        val arm = c.ctrl
                        if (arm == null) null else {
                            val selectedGroups = d.groups.map { g -> boneSelected(arm, g) }
                            BooleanArray(d.nV) { v -> dv[v].any { (gi, w) -> gi < selectedGroups.size && selectedGroups[gi] && w > threshold } }
                        }
                    } else {
                        val gi = d.groups.indexOf(m.str("vgroup"))
                        if (gi < 0) null else {
                            weights = FloatArray(d.nV) { v -> dv[v].firstOrNull { it.first == gi }?.second ?: 0f }
                            BooleanArray(d.nV) { weights[it] > threshold }
                        }
                    }
                    if (keep == null) d else {
                        if (invert) for (i in keep.indices) keep[i] = !keep[i]
                        BlendMeshOps.mask(d, keep, weights, threshold, mode == 0 && m.int("flag") and 2 != 0)
                    }
                }
            }
            29 -> {
                val lvl = m.int("lvl")
                val tot = m.int("totlvl")
                if (lvl <= 0 || tot <= 0 || d.nV == 0) d else {
                    val flags = m.int("flags")
                    val uvs = intArrayOf(BlendSubdiv.FVAR_ALL, BlendSubdiv.FVAR_CORNERS_ONLY, BlendSubdiv.FVAR_CORNERS_PLUS1, BlendSubdiv.FVAR_CORNERS_PLUS2, BlendSubdiv.FVAR_BOUNDARIES, BlendSubdiv.FVAR_NONE)
                    val uvSmooth = uvs[m.int("uv_smooth", 4).coerceIn(0, 5)]
                    val grids = mdisps(ob, d.corners.size, tot)
                    BlendMeshData.subsurf(
                        d, minOf(lvl, tot), m.int("quality", 4), true, false, flags and 4 != 0,
                        if (m.int("boundary_smooth") == 1) BlendSubdiv.BOUNDARY_EDGE_AND_CORNER else BlendSubdiv.BOUNDARY_EDGE_ONLY, uvSmooth, grids
                    )
                }
            }
            6 -> {
                val mode = m.int("mode")
                val flag = m.int("flag")
                val percent = mp("ratio", m.float("percent", 1f))
                if (d.nF <= 3) d
                else when (mode) {
                    0 -> if (percent == 1.0f) d else {
                        val factor = mp("vertex_group_factor", m.float("defgrp_factor", 1f))
                        val gi = d.groups.indexOf(m.str("defgrp_name"))
                        val dv = d.dverts
                        val weights = if (m.str("defgrp_name").isNotEmpty() && factor > 0f && gi >= 0 && dv != null) FloatArray(d.nV) { v ->
                            val w = dv.getOrNull(v)?.firstOrNull { it.first == gi }?.second ?: 0f
                            if (flag and 1 != 0) 1f - w else w
                        } else null
                        BlendDecimate.collapse(d, percent, weights, factor, flag and 2 != 0, if (flag and 8 != 0) m.int("symmetry_axis") else -1)
                    }
                    1 -> { val it = mp("iterations", m.int("iter").toFloat()).toInt(); if (it == 0) d else BlendDecimate.unsubdivide(d, it) }
                    2 -> { val ang = mp("angle_limit", m.float("angle", (5.0 * Math.PI / 180.0).toFloat())); if (ang == 0.0f) d else BlendDecimate.dissolve(d, ang, flag and 4 != 0, m.int("delimit")) }
                    else -> d
                }
            }
            24 -> {
                val value = mp("width", m.float("value", 0.1f))
                if (d.nV == 0 || value == 0.0f) d else {
                    val profilePtr = m.ptr("custom_profile")
                    BlendBevelModifier.apply(
                        d, value, m.int("res", 1), m.int("flags"), m.int("val_flags"), m.int("profile_type"), m.int("lim_flags", 1 shl 3), m.int("edge_flags"),
                        m.int("mat", -1), m.int("face_str_mode"), m.int("miter_inner"), m.int("miter_outer"), m.int("vmesh_method"), m.int("affect_type", 1),
                        mp("profile", m.float("profile", 0.5f)), mp("angle_limit", m.float("bevel_angle", 0.5235988f)), mp("spread", m.float("spread", 0.1f)),
                        m.str("defgrp_name"), if (m.has("edge_weight_name")) m.str("edge_weight_name") else "bevel_weight_edge",
                        if (m.has("vertex_weight_name")) m.str("vertex_weight_name") else "bevel_weight_vert", ob.int("totcol"), curveProfile(profilePtr)
                    )
                }
            }
            42 -> BlendSkinModifier.apply(
                d,
                mp("branch_smoothing", m.float("branch_smoothing")),
                m.int("flag"),
                m.int("symmetry_axes", 1)
            )
            11 -> {
                val opObjs = c.extra
                if (opObjs.isEmpty()) d else {
                    val obW = fr.world(ob)
                    val invObW = BlendAnimator.invert(obW)
                    val me = f.offsetOf(ob.ptr("data"))?.let { f.view("Mesh", it) }
                    val targetMats = if (me != null) materialPtrs(ob, me) else emptyList()
                    val matMode = m.int("material_mode")
                    val ops = ArrayList<BlendBooleanModifier.Operand>(opObjs.size)
                    for (opOb in opObjs) {
                        val opMesh = evaluatedAt(fr, opOb) ?: continue
                        val relMtx = GltfParser.mul(invObW, fr.world(opOb))
                        val remap = if (matMode == BlendBooleanModifier.MAT_MODE_TRANSFER && targetMats.isNotEmpty()) {
                            val opMe = f.offsetOf(opOb.ptr("data"))?.let { f.view("Mesh", it) }
                            val opMats = if (opMe != null) materialPtrs(opOb, opMe) else emptyList()
                            IntArray(opMats.size) { slot ->
                                val found = targetMats.indexOf(opMats[slot])
                                if (found >= 0) found else 0
                            }
                        } else null
                        ops.add(BlendBooleanModifier.Operand(opMesh, relMtx, remap))
                    }
                    if (ops.isEmpty()) d else BlendBooleanModifier.apply(
                        target = d,
                        operands = ops,
                        operation = m.int("operation", BlendBooleanModifier.OP_DIFFERENCE),
                        doubleThreshold = mp("double_threshold", m.float("double_threshold", 1e-6f)),
                        flag = m.int("flag"),
                        materialMode = matMode,
                        targetMatCount = maxOf(1, targetMats.size)
                    )
                }
            }
            48 -> {
                val flag = m.int("flag")
                val gi = d.groups.indexOf(m.str("defgrp_name"))
                val dv = d.dverts
                val weights = if (gi >= 0 && dv != null) FloatArray(d.nV) { v ->
                    val w = dv.getOrNull(v)?.firstOrNull { it.first == gi }?.second ?: 0f
                    if (flag and BlendWireframe.INVERT_VGROUP != 0) 1f - w else w
                } else null
                BlendWireframe.apply(d, BlendWireframe.Params(
                    mp("thickness", m.float("offset", 0.02f)), mp("offset", m.float("offset_fac")), mp("thickness_vertex_group", m.float("offset_fac_vg")),
                    mp("crease_weight", m.float("crease_weight", 1f)), flag, m.int("mat_ofs"), maxOf(ob.int("totcol") - 1, 0), weights
                ))
            }
            54 -> BlendMeshOps.weightedNormal(d, m.int("mode"), m.int("weight", 50), m.float("thresh", 0.01f), m.int("flag"), m.str("defgrp_name"))
            34 -> {
                val axisM = c.ctrl?.let { GltfParser.mul(BlendAnimator.invert(fr.world(ob)), fr.world(it)) }
                BlendScrew.apply(d, BlendScrew.Params(
                    m.int("steps", 16), m.int("iter", 1), mp("screw_offset", m.float("screw_ofs")), mp("angle", m.float("angle", (2 * Math.PI).toFloat())),
                    m.float("merge_dist", 0.01f), m.int("flag"), m.int("axis", 2), axisM
                ))
            }
            4 -> BlendMeshOps.build(d, fr.frame, mp("frame_start", m.float("start", 1f)), mp("frame_duration", m.float("length", 100f)), m.int("flag") or (if (m.int("randomize") != 0) 1 else 0), m.int("seed"))
            55 -> BlendMeshOps.weld(d, mp("merge_threshold", m.float("merge_dist", 0.001f)), m.int("mode"), m.int("flag"), m.str("defgrp_name"))
            44 -> BlendTriangulate.apply(d, m.int("quad_method", 3), m.int("ngon_method"), m.int("min_vertices", 4))
            13 -> BlendMeshOps.edgeSplit(d, mp("split_angle", m.float("split_angle", 0.5235988f)), m.int("flags", 6))
            57 -> {
                val props = idProps(m.sub("settings")?.ptr("properties") ?: 0L)
                val angle = props["Input_1"]?.toFloat() ?: 0.5235988f
                val ignore = (props["Socket_1"] ?: 0.0) != 0.0
                BlendMeshOps.smoothByAngle(d, angle, ignore)
            }
            33 -> {
                val flag = m.int("flag")
                val gi = d.groups.indexOf(m.str("defgrp_name"))
                val dv = d.dverts
                val weights = if (gi >= 0 && dv != null) FloatArray(d.nV) { v ->
                    val w = dv.getOrNull(v)?.firstOrNull { it.first == gi }?.second ?: 0f
                    if (flag and (1 shl 3) != 0) 1f - w else w
                } else null
                val params = BlendMeshOps.SolidifyParams(
                    mp("thickness", m.float("offset", 0.01f)), mp("offset", m.float("offset_fac", -1f)), mp("thickness_vertex_group", m.float("offset_fac_vg")),
                    mp("thickness_clamp", m.float("offset_clamp")), flag, m.int("mat_ofs"), m.int("mat_ofs_rim"), ob.int("totcol"),
                    m.float("crease_inner"), m.float("crease_outer"), m.float("crease_rim"), weights,
                    d.groups.indexOf(m.str("shell_defgrp_name")).takeIf { m.str("shell_defgrp_name").isNotEmpty() } ?: -1,
                    d.groups.indexOf(m.str("rim_defgrp_name")).takeIf { m.str("rim_defgrp_name").isNotEmpty() } ?: -1
                )
                if (m.int("mode") == 1) BlendSolidifyComplex.apply(d, params, m.int("nonmanifold_offset_mode", 2), m.int("nonmanifold_boundary_mode"), m.float("merge_tolerance", 0.0001f), m.float("bevel_convex"))
                else BlendMeshOps.solidify(d, params)
            }
            12 -> {
                fun obj(n: String) = f.offsetOf(m.ptr(n))?.let { animator?.owner(it) }?.takeIf { it.at != ob.at && it.int("type") == 1 }
                fun cap(n: String): BlendMeshOps.ArrayCap? {
                    val co = obj(n) ?: return null
                    val mesh = evaluatedAt(fr, co) ?: return null
                    val remap = IntArray(mesh.groups.size) { d.groups.indexOf(mesh.groups[it]) }
                    return BlendMeshOps.ArrayCap(mesh, remap)
                }
                val objOff = c.ctrl?.let { GltfParser.mul(BlendAnimator.invert(fr.world(ob)), fr.world(it)) }
                val curveLen = c.ctrl2?.let { cu ->
                    animator?.curvePath(cu)?.let { p ->
                        val w = fr.world(cu)
                        val u = 0.57735026f
                        val x = (w[0] + w[4] + w[8]) * u; val y = (w[1] + w[5] + w[9]) * u; val z = (w[2] + w[6] + w[10]) * u
                        kotlin.math.sqrt(x * x + y * y + z * z) * p.length
                    }
                }
                BlendMeshOps.array(
                    d, mp("count", m.int("count", 2).toFloat()).toInt(), m.int("fit_type"), mp("fit_length", m.float("length")), m.int("offset_type"),
                    m.floats("offset", 3) ?: floatArrayOf(1f, 0f, 0f), m.floats("scale", 3) ?: floatArrayOf(1f, 0f, 0f),
                    objOff, m.int("flags"), mp("merge_threshold", m.float("merge_dist", 0.01f)), m.floats("uv_offset", 2) ?: FloatArray(2),
                    cap("start_cap"), cap("end_cap"), curveLen
                )
            }
            5 -> {
                val flag = m.int("flag")
                var r = d
                val obW = fr.world(ob)
                val mob = c.ctrl
                for (axis in 0 until 3) {
                    if (flag and (BlendMeshOps.MIR_AXIS_X shl axis) == 0) continue
                    val s = GltfParser.IDENTITY.copyOf().also { it[axis * 5] = -1f }
                    var mtx = s
                    var planeCo = floatArrayOf(0f, 0f, 0f)
                    var planeNo = FloatArray(3).also { it[axis] = 1f }
                    if (mob != null) {
                        val tmp = GltfParser.mul(BlendAnimator.invert(fr.world(mob)), obW)
                        val itmp = BlendAnimator.invert(tmp)
                        mtx = GltfParser.mul(GltfParser.mul(itmp, s), tmp)
                        planeCo = floatArrayOf(itmp[12], itmp[13], itmp[14])
                        planeNo = floatArrayOf(itmp[axis * 4], itmp[axis * 4 + 1], itmp[axis * 4 + 2])
                        val sc = FloatArray(3) { k -> obW[k * 4] * obW[k * 4] + obW[k * 4 + 1] * obW[k * 4 + 1] + obW[k * 4 + 2] * obW[k * 4 + 2] }
                        val mx = maxOf(sc[0], sc[1], sc[2])
                        if (mx != 0f) for (k in 0 until 3) planeNo[k] *= sc[k] * (1f / mx)
                    }
                    r = BlendMeshOps.mirror(
                        r, axis, mtx, flag, m.float("tolerance", 0.001f), m.float("bisect_threshold", 0.001f),
                        m.floats("uv_offset", 2) ?: FloatArray(2), m.floats("uv_offset_copy", 2) ?: FloatArray(2),
                        m.has("use_correct_order_on_merge") && m.int("use_correct_order_on_merge") != 0, planeCo, planeNo
                    )
                }
                r
            }
            else -> d
        }
    }

    private fun emitEvaluated(ob: BlendFile.View, me: BlendFile.View, world: FloatArray, stack: List<Deform>) {
        val a = animator ?: return
        val vdata = layers(me, "vert_data", "vdata") + storageLayers(me, 0)
        val pos = meshPositions(me, vdata) ?: return
        val (refBase, shapes) = shapeKeys(ob, me, vdata, pos.size / 3)
        val baseCo = if (refBase != null && refBase.size == pos.size) refBase else pos
        val base = meshData(ob, shapeLocal(null, baseCo, shapes)) ?: return
        val fr0 = a.staticFrame
        val d = try { evaluateStack(fr0, ob, stack, base) } catch (_: RuntimeException) { base }
        fun toWorld(fr: BlendAnimator.Frame, co: FloatArray): FloatArray {
            val w = fr.world(ob)
            val o = FloatArray(co.size)
            for (v in 0 until co.size / 3) {
                val p = BlendAnimator.transformPoint(w, co[v * 3], co[v * 3 + 1], co[v * 3 + 2])
                o[v * 3] = p[0]; o[v * 3 + 1] = p[1]; o[v * 3 + 2] = p[2]
            }
            return o
        }
        val wp = toWorld(fr0, d.co)
        val polys = (0 until d.nF).sumOf { maxOf(d.fSize[it] - 2, 0) }
        val baked = if (stackAnimated(ob, stack, shapes)) bakeWith({ fr ->
            val bd = meshData(ob, shapeLocal(fr, baseCo, shapes)) ?: return@bakeWith null
            toWorld(fr, evaluateStack(fr, ob, stack, bd).co)
        }, wp, polys) else null
        val rigid = if (baked == null && frames.isNotEmpty() && a.dynamic(ob)) {
            val inv = BlendAnimator.invert(world)
            addBone("${ob.at}") { fr -> GltfParser.mul(fr.world(ob), inv) }
        } else -1
        val matPtrs = materialPtrs(ob, me)
        val mats = matPtrs.map { material(it) }
        val normalTexs = matPtrs.map { normalTexture(it) }
        val vCount = d.nV
        val smoothFlags = d.faceSmooth
        val hasSmooth = d.nF > 0
        val nSpec = d.normalSpec()
        val staticN = if (hasSmooth) smoothNormals(wp, d.corners, d.fStart, d.fSize, (smoothFlags ?: BooleanArray(0)), nSpec) else null
        val morphDeltas = baked?.deltas ?: emptyList()
        val morphN = if (hasSmooth) morphDeltas.map { dd ->
            val n1 = smoothNormals(FloatArray(wp.size) { wp[it] + dd[it] }, d.corners, d.fStart, d.fSize, (smoothFlags ?: BooleanArray(0)), nSpec)
            FloatArray(n1.size) { n1[it] - staticN!![it] }
        } else null
        val (triCorners, triFace) = MeshTopology(d.corners, d.fStart, d.fSize, IntArray(0)).cornerTris(wp)
        val tri = FloatArray(9)
        val uv = FloatArray(6)
        val nTri = FloatArray(9)
        val cs = IntArray(3)
        for (t in triFace.indices) {
            if (out.truncated) return
            val face = triFace[t]
            val matIndex = d.faceMat?.getOrNull(face) ?: 0
            val (color, tex) = mats.getOrNull(matIndex) ?: (0 to -1)
            val ntex = normalTexs.getOrNull(matIndex) ?: -1
            val smoothFace = staticN != null
            var ok = true
            for (slot in 0 until 3) {
                val c = triCorners[t * 3 + slot]
                val v = d.corners.getOrElse(c) { -1 }
                if (v !in 0 until vCount) { ok = false; break }
                for (k in 0 until 3) tri[slot * 3 + k] = wp[v * 3 + k]
                if (smoothFace) for (k in 0 until 3) nTri[slot * 3 + k] = staticN[c * 3 + k]
                val u = d.uv
                if (u != null && c * 2 + 1 < u.size) { uv[slot * 2] = u[c * 2]; uv[slot * 2 + 1] = 1f - u[c * 2 + 1] }
                cs[slot] = d.cornerColor?.getOrNull(c) ?: d.pointColor?.getOrNull(v) ?: 0
            }
            if (!ok) continue
            var anim: TriAnim? = null
            if (rigid >= 0) anim = TriAnim().also { it.bone = rigid }
            if (baked != null) {
                val mIds = ArrayList<Int>()
                val mD = ArrayList<Float>()
                val mN = ArrayList<Float>()
                for ((ti, dd) in morphDeltas.withIndex()) {
                    var nz = false
                    for (slot in 0 until 3) { val v = d.corners[triCorners[t * 3 + slot]]; if (dd[v * 3] != 0f || dd[v * 3 + 1] != 0f || dd[v * 3 + 2] != 0f) nz = true }
                    if (!nz) continue
                    mIds.add(baked.ids[ti])
                    val nd = morphN?.get(ti)
                    for (slot in 0 until 3) {
                        val c = triCorners[t * 3 + slot]
                        val v = d.corners[c]
                        mD.add(dd[v * 3]); mD.add(dd[v * 3 + 1]); mD.add(dd[v * 3 + 2])
                        if (smoothFace && nd != null) { mN.add(nd[c * 3]); mN.add(nd[c * 3 + 1]); mN.add(nd[c * 3 + 2]) } else { mN.add(0f); mN.add(0f); mN.add(0f) }
                    }
                }
                if (mIds.isNotEmpty()) anim = TriAnim().also { an ->
                    an.morphTargets = mIds.toIntArray()
                    an.morphDeltas = mD.toFloatArray()
                    if (smoothFace) an.morphNormalDeltas = mN.toFloatArray()
                }
            }
            val hasUv = (tex >= 0 || ntex >= 0) && d.uv != null
            out.triangleFull(tri, color, if (hasUv) uv.copyOf() else null, if (hasUv) tex else -1, cs[0], cs[1], cs[2], if (smoothFace) nTri.copyOf() else null, if (hasUv) ntex else -1, anim)
        }
    }

    private fun materials(ob: BlendFile.View, me: BlendFile.View): List<Pair<Int, Int>> = materialPtrs(ob, me).map { material(it) }

    private val normalCache = HashMap<Long, Int>()

    private fun normalTexture(ptr: Long): Int = normalCache.getOrPut(ptr) {
        val at = f.offsetOf(ptr) ?: return@getOrPut -1
        val m = f.view("Material", at) ?: return@getOrPut -1
        try {
            materialNormalMap(m)
        } catch (_: Throwable) {
            -1
        }
    }

    private fun materialNormalMap(m: BlendFile.View): Int {
        val (nodes, links) = nodeTree(m) ?: return -1
        val bsdf = nodes.firstOrNull { it.idname == "ShaderNodeBsdfPrincipled" || it.idname == "ShaderNodeBsdfDiffuse" } ?: return -1
        val target = bsdf.inputs.firstOrNull { it.second == "Normal" }?.first ?: return -1
        fun from(sock: Int): Node? {
            val link = links.firstOrNull { it[1] == sock } ?: return null
            return nodes.firstOrNull { it.at == link[0] }
        }
        var nm = from(target) ?: return -1
        var guard = 0
        while (nm.idname == "NodeReroute" && guard++ < 8) nm = from(nm.inputs.firstOrNull()?.first ?: return -1) ?: return -1
        if (nm.idname != "ShaderNodeNormalMap") return -1
        val colorSock = nm.inputs.firstOrNull { it.second == "Color" }?.first ?: return -1
        var img = from(colorSock) ?: return -1
        guard = 0
        while (img.idname == "NodeReroute" && guard++ < 8) img = from(img.inputs.firstOrNull()?.first ?: return -1) ?: return -1
        if (img.idname != "ShaderNodeTexImage") return -1
        var strength = 1f
        nm.inputs.firstOrNull { it.second == "Strength" }?.first?.let { sa ->
            val sv = f.view("bNodeSocket", sa) ?: return@let
            val dv = f.offsetOf(sv.ptr("default_value")) ?: return@let
            f.view("bNodeSocketValueFloat", dv)?.let { strength = it.float("value", 1f) }
        }
        val imgAt = f.offsetOf(img.idPtr) ?: return -1
        val image = f.view("Image", imgAt) ?: return -1
        val packed = packedBytes(image)
        val path = image.str("filepath").ifEmpty { image.str("name") }
        val bytes = packed ?: path.takeIf { it.isNotBlank() }?.let { out.resource(it.removePrefix("//").replace('\\', '/')) }
        return out.normalTexture("blend:${img.idPtr}", bytes, false, strength)
    }

    private fun materialPtrs(ob: BlendFile.View, me: BlendFile.View): List<Long> {
        val res = ArrayList<Long>()
        val total = maxOf(me.int("totcol"), ob.int("totcol"))
        val meMats = f.offsetOf(me.ptr("mat"))
        val obMats = f.offsetOf(ob.ptr("mat"))
        val bits = f.offsetOf(ob.ptr("matbits"))
        for (i in 0 until total.coerceIn(0, 256)) {
            val fromObject = bits != null && f.bytes.getOrElse(bits + i) { 0 }.toInt() != 0
            val listAt = if (fromObject) obMats else meMats
            val ptr = if (listAt != null) f.ptr(listAt + i * f.ptrSize) else 0L
            res.add(ptr)
        }
        return res
    }

    private fun material(ptr: Long): Pair<Int, Int> {
        materialCache[ptr]?.let { return it }
        val at = f.offsetOf(ptr)
        val m = if (at != null) f.view("Material", at) else null
        val result = if (m == null) (0 to -1) else {
            val a = m.float("a", 1f).coerceIn(0f, 1f)
            val tex = baseColorTexture(m, a >= 0.999f)
            val color = if (tex >= 0) ((a * 255).roundToInt() shl 24) or 0xFFFFFF else {
                val principled = principledBaseColor(m)
                val r = principled?.get(0) ?: m.float("r", 0.8f)
                val g = principled?.get(1) ?: m.float("g", 0.8f)
                val b = principled?.get(2) ?: m.float("b", 0.8f)
                fun ch(v: Float): Int {
                    val c = v.coerceIn(0f, 1f).toDouble()
                    val s = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
                    return (s * 255).roundToInt().coerceIn(0, 255)
                }
                ((a * 255).roundToInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            color to tex
        }
        materialCache[ptr] = result
        return result
    }

    private class Node(val at: Int, val idname: String, val name: String, val idPtr: Long, val inputs: List<Pair<Int, String>>, val outputs: List<Int>)

    private fun nodeTree(m: BlendFile.View): Pair<List<Node>, List<IntArray>>? {
        val treeAt = f.offsetOf(m.ptr("nodetree")) ?: return null
        val tree = f.view("bNodeTree", treeAt) ?: return null
        val nodeSt = f.struct("bNode") ?: return null
        val sockSt = f.struct("bNodeSocket") ?: return null
        val linkSt = f.struct("bNodeLink") ?: return null
        val nodes = ArrayList<Node>()
        fun listFirst(v: BlendFile.View, n: String): Long {
            val lb = v.sub(n) ?: return 0L
            return lb.ptr("first")
        }
        var p = listFirst(tree, "nodes")
        var guard = 0
        while (p != 0L && guard++ < 10_000) {
            val at = f.offsetOf(p) ?: break
            val nv = f.View(nodeSt, at)
            val inputs = ArrayList<Pair<Int, String>>()
            var s = listFirst(nv, "inputs")
            var g2 = 0
            while (s != 0L && g2++ < 1000) {
                val sa = f.offsetOf(s) ?: break
                val sv = f.View(sockSt, sa)
                inputs.add(sa to (sv.str("identifier").ifEmpty { sv.str("name") }))
                s = sv.ptr("next")
            }
            val outputs = ArrayList<Int>()
            s = listFirst(nv, "outputs")
            g2 = 0
            while (s != 0L && g2++ < 1000) {
                val sa = f.offsetOf(s) ?: break
                outputs.add(sa)
                s = f.View(sockSt, sa).ptr("next")
            }
            nodes.add(Node(at, nv.str("idname"), nv.str("name"), nv.ptr("id"), inputs, outputs))
            p = nv.ptr("next")
        }
        val links = ArrayList<IntArray>()
        p = listFirst(tree, "links")
        guard = 0
        while (p != 0L && guard++ < 10_000) {
            val at = f.offsetOf(p) ?: break
            val lv = f.View(linkSt, at)
            val fromNode = f.offsetOf(lv.ptr("fromnode")) ?: -1
            val toSock = f.offsetOf(lv.ptr("tosock")) ?: -1
            links.add(intArrayOf(fromNode, toSock))
            p = lv.ptr("next")
        }
        return nodes to links
    }

    private fun principledBaseColor(m: BlendFile.View): FloatArray? {
        val (nodes, _) = nodeTree(m) ?: return null
        val bsdf = nodes.firstOrNull { it.idname == "ShaderNodeBsdfPrincipled" || it.idname == "ShaderNodeBsdfDiffuse" } ?: return null
        val sockAt = bsdf.inputs.firstOrNull { it.second == "Base Color" || it.second == "Color" }?.first ?: return null
        val sv = f.view("bNodeSocket", sockAt) ?: return null
        val dvAt = f.offsetOf(sv.ptr("default_value")) ?: return null
        val rgba = f.view("bNodeSocketValueRGBA", dvAt) ?: return null
        return rgba.floats("value", 4)
    }

    private fun baseColorTexture(m: BlendFile.View, opaque: Boolean): Int {
        val (nodes, links) = nodeTree(m) ?: return -1
        val bsdf = nodes.firstOrNull { it.idname == "ShaderNodeBsdfPrincipled" || it.idname == "ShaderNodeBsdfDiffuse" } ?: return -1
        val target = bsdf.inputs.firstOrNull { it.second == "Base Color" || it.second == "Color" }?.first ?: return -1
        val passThrough = setOf("ShaderNodeMix", "ShaderNodeMixRGB", "ShaderNodeHueSaturation", "ShaderNodeBrightContrast", "ShaderNodeGamma", "ShaderNodeInvert", "ShaderNodeRGBCurve", "ShaderNodeSeparateColor", "ShaderNodeCombineColor", "NodeReroute", "ShaderNodeVertexColor")
        fun upstream(sock: Int, depth: Int): Node? {
            if (depth > 6) return null
            val link = links.firstOrNull { it[1] == sock } ?: return null
            val from = nodes.firstOrNull { it.at == link[0] } ?: return null
            if (from.idname == "ShaderNodeTexImage") return from
            if (from.idname !in passThrough) return null
            for ((inSock, _) in from.inputs) upstream(inSock, depth + 1)?.let { return it }
            return null
        }
        val node = upstream(target, 0) ?: return -1
        val imgAt = f.offsetOf(node.idPtr) ?: return -1
        val img = f.view("Image", imgAt) ?: return -1
        val packed = packedBytes(img)
        val path = img.str("filepath").ifEmpty { img.str("name") }
        val bytes = packed ?: path.takeIf { it.isNotBlank() }?.let { out.resource(it.removePrefix("//").replace('\\', '/')) }
        return out.texture("blend:${node.idPtr}:$opaque", bytes, opaque)
    }

    private fun packedBytes(img: BlendFile.View): ByteArray? {
        fun fromPackedFile(ptr: Long): ByteArray? {
            val at = f.offsetOf(ptr) ?: return null
            val pf = f.view("PackedFile", at) ?: return null
            val size = pf.int("size")
            val dAt = f.offsetOf(pf.ptr("data")) ?: return null
            if (size <= 0 || dAt + size > f.bytes.size) return null
            return f.bytes.copyOfRange(dAt, dAt + size)
        }
        fromPackedFile(img.ptr("packedfile"))?.let { return it }
        val lb = img.sub("packedfiles") ?: return null
        val first = f.offsetOf(lb.ptr("first")) ?: return null
        val ipf = f.view("ImagePackedFile", first) ?: return null
        return fromPackedFile(ipf.ptr("packedfile"))
    }
}
