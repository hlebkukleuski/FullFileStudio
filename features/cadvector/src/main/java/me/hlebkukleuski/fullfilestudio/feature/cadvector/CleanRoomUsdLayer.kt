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
 * licensed under the Modified Apache 2.0 License (TOST 1.0).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

internal data class UsdAsset(val path: String)
internal data class UsdPath(val path: String)
internal data class UsdRef(val asset: String, val prim: String)
internal object UsdBlocked
internal class CrateRep(val rep: Long)

internal class UsdListOp {
    var explicit: List<Any?>? = null
    val added = ArrayList<Any?>()
    val prepended = ArrayList<Any?>()
    val appended = ArrayList<Any?>()
    val deleted = ArrayList<Any?>()
    val ordered = ArrayList<Any?>()
    fun items(): List<Any?> = explicit ?: (prepended + added + appended).filter { it !in deleted }.distinct()
}

internal class UsdTimeSamples(val times: DoubleArray, private val get: (Int) -> Any?) {
    fun at(i: Int): Any? = get(i)
}

internal object UsdSpecType {
    const val ATTRIBUTE = 1
    const val PRIM = 6
    const val PSEUDO_ROOT = 7
    const val RELATIONSHIP = 8
    const val VARIANT = 10
    const val VARIANT_SET = 11
}

internal class UsdSpec(val type: Int) {
    val fields = HashMap<String, Any?>()
}

internal class UsdLayer(val id: String) {
    val specs = HashMap<String, UsdSpec>()
    val children = HashMap<String, LinkedHashSet<String>>()
    val props = HashMap<String, LinkedHashSet<String>>()
    var crate: UsdCrate? = null

    fun field(path: String, name: String): Any? = specs[path]?.let { field(it, name) }

    fun field(s: UsdSpec, name: String): Any? {
        val v = s.fields[name]
        if (v is CrateRep) {
            val d = try {
                crate?.unpack(v.rep)
            } catch (_: Throwable) {
                null
            }
            s.fields[name] = d
            return d
        }
        return v
    }

    fun index() {
        val ordered = specs.keys.sorted()
        for (p in ordered) {
            if (p == "/") continue
            val s = specs[p] ?: continue
            when (s.type) {
                UsdSpecType.PRIM -> {
                    val (parent, name) = UsdPaths.split(p)
                    children.getOrPut(parent) { LinkedHashSet() }.add(name)
                }
                UsdSpecType.ATTRIBUTE, UsdSpecType.RELATIONSHIP -> {
                    val dot = UsdPaths.propSplit(p)
                    if (dot > 0) props.getOrPut(p.substring(0, dot)) { LinkedHashSet() }.add(p.substring(dot + 1))
                }
            }
        }
        for ((parent, set) in children) {
            val order = UsdVal.tokens(field(parent, "primChildren"))
            if (order.isEmpty()) continue
            val sorted = LinkedHashSet<String>()
            for (n in order) if (n in set) sorted.add(n)
            sorted.addAll(set)
            set.clear()
            set.addAll(sorted)
        }
    }
}

internal object UsdPaths {
    fun child(parent: String, name: String): String = when {
        parent == "/" -> "/$name"
        parent.endsWith("}") -> parent + name
        else -> "$parent/$name"
    }

    fun split(p: String): Pair<String, String> {
        val i = maxOf(p.lastIndexOf('/'), p.lastIndexOf('}'))
        if (i < 0) return "/" to p
        return if (p[i] == '/') (if (i == 0) "/" else p.substring(0, i)) to p.substring(i + 1)
        else p.substring(0, i + 1) to p.substring(i + 1)
    }

    fun propSplit(p: String): Int {
        val base = maxOf(p.lastIndexOf('/'), p.lastIndexOf('}'))
        return p.indexOf('.', base + 1)
    }

    fun stripVariants(p: String): String {
        if (!p.contains('{')) return p
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '{') {
                val e = p.indexOf('}', i)
                if (e < 0) break
                i = e + 1
                if (i < p.length && p[i] != '/' && p[i] != '.') sb.append('/')
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun parent(p: String): String = split(p).first
}

internal object UsdVal {
    fun floats(v: Any?): FloatArray? = when (v) {
        is FloatArray -> v
        is DoubleArray -> FloatArray(v.size) { v[it].toFloat() }
        is IntArray -> FloatArray(v.size) { v[it].toFloat() }
        is LongArray -> FloatArray(v.size) { v[it].toFloat() }
        is Number -> floatArrayOf(v.toFloat())
        is List<*> -> doubles(v)?.let { d -> FloatArray(d.size) { d[it].toFloat() } }
        else -> null
    }

    fun doubles(v: Any?): DoubleArray? = when (v) {
        is DoubleArray -> v
        is FloatArray -> DoubleArray(v.size) { v[it].toDouble() }
        is IntArray -> DoubleArray(v.size) { v[it].toDouble() }
        is LongArray -> DoubleArray(v.size) { v[it].toDouble() }
        is Number -> doubleArrayOf(v.toDouble())
        is List<*> -> {
            val out = ArrayList<Double>()
            fun walk(x: Any?) {
                when (x) {
                    is Number -> out.add(x.toDouble())
                    is List<*> -> x.forEach { walk(it) }
                    is DoubleArray -> x.forEach { out.add(it) }
                    is FloatArray -> x.forEach { out.add(it.toDouble()) }
                    else -> {}
                }
            }
            walk(v)
            out.toDoubleArray()
        }
        else -> null
    }

    fun ints(v: Any?): IntArray? = when (v) {
        is IntArray -> v
        is LongArray -> IntArray(v.size) { v[it].toInt() }
        is DoubleArray -> IntArray(v.size) { v[it].toInt() }
        is FloatArray -> IntArray(v.size) { v[it].toInt() }
        is Number -> intArrayOf(v.toInt())
        is List<*> -> doubles(v)?.let { d -> IntArray(d.size) { d[it].toInt() } }
        else -> null
    }

    fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is DoubleArray -> v.firstOrNull()
        is FloatArray -> v.firstOrNull()?.toDouble()
        else -> null
    }

    fun str(v: Any?): String? = when (v) {
        is String -> v
        is UsdAsset -> v.path
        is List<*> -> v.firstOrNull() as? String
        else -> null
    }

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> null
    }

    fun tokens(v: Any?): List<String> = when (v) {
        is UsdListOp -> v.items().mapNotNull { str(it) }
        is List<*> -> v.mapNotNull { str(it) }
        is String -> listOf(v)
        else -> emptyList()
    }

    fun items(v: Any?): List<Any?> = when (v) {
        is UsdListOp -> v.items()
        is List<*> -> v
        null -> emptyList()
        else -> listOf(v)
    }
}

internal class UsdCrate(private val b: ByteArray, private val layer: UsdLayer) {
    private val vMaj = b[8].toInt() and 0xFF
    private val vMin = b[9].toInt() and 0xFF
    private val vPat = b[10].toInt() and 0xFF
    private var p = 0
    private var tokens: Array<String> = emptyArray()
    private var strings = IntArray(0)
    private var paths: Array<String?> = emptyArray()

    private fun atLeast(ma: Int, mi: Int, pa: Int) = vMaj > ma || (vMaj == ma && (vMin > mi || (vMin == mi && vPat >= pa)))
    private fun u8(o: Int) = b[o].toInt() and 0xFF
    private fun i16(o: Int) = (u8(o) or (u8(o + 1) shl 8)).toShort().toInt()
    private fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
    private fun i32(o: Int) = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
    private fun i64(o: Int) = (i32(o).toLong() and 0xFFFFFFFFL) or (i32(o + 4).toLong() shl 32)
    private fun f32(o: Int) = Float.fromBits(i32(o))
    private fun f64(o: Int) = Double.fromBits(i64(o))
    private fun tok(i: Int) = tokens.getOrElse(i) { "" }
    private fun str(i: Int) = tok(strings.getOrElse(i) { -1 })
    private fun path(i: Int) = paths.getOrNull(i) ?: ""

    private fun off(v: Long): Int {
        require(v >= 0 && v <= b.size)
        return v.toInt()
    }

    private fun count(): Int {
        val v = i64(p)
        p += 8
        require(v >= 0 && v <= b.size.toLong() * 64L && v < Int.MAX_VALUE / 16)
        return v.toInt()
    }

    fun load(): Boolean {
        if (b.size < 88 || !atLeast(0, 4, 0)) return false
        val toc = off(i64(16))
        val n = i64(toc)
        if (n !in 1..64) return false
        val sec = HashMap<String, Int>()
        for (k in 0 until n.toInt()) {
            val q = toc + 8 + k * 32
            sec[String(b, q, 16, Charsets.US_ASCII).substringBefore('\u0000')] = off(i64(q + 16))
        }
        p = sec["TOKENS"] ?: return false
        val nt = count()
        val unc = count()
        val comp = count()
        val tdata = tfDecompress(p, comp, unc)
        tokens = Array(nt) { "" }
        var s = 0
        for (i in 0 until nt) {
            var e = s
            while (e < tdata.size && tdata[e] != 0.toByte()) e++
            tokens[i] = String(tdata, s, e - s, Charsets.UTF_8)
            s = e + 1
        }
        sec["STRINGS"]?.let { at ->
            p = at
            val ns = count()
            strings = IntArray(ns) { i32(p + it * 4) }
        }
        p = sec["FIELDS"] ?: return false
        val nf = count()
        val fieldTok = compressedInts(nf)
        val repSize = count()
        val repRaw = tfDecompress(p, repSize, nf * 8)
        val fieldRep = LongArray(nf) { k -> le64(repRaw, k * 8) }
        p = sec["FIELDSETS"] ?: return false
        val fieldSets = compressedInts(count())
        p = sec["PATHS"] ?: return false
        paths = arrayOfNulls(count())
        val ne = count()
        val pi = compressedInts(ne)
        val et = compressedInts(ne)
        val jm = compressedInts(ne)
        buildPaths(pi, et, jm)
        p = sec["SPECS"] ?: return false
        val nsp = count()
        val sp = compressedInts(nsp)
        val sf = compressedInts(nsp)
        val st = compressedInts(nsp)
        for (k in 0 until nsp) {
            val path = paths.getOrNull(sp[k]) ?: continue
            val spec = UsdSpec(st[k])
            var j = sf[k]
            while (j in fieldSets.indices && fieldSets[j] != -1) {
                val f = fieldSets[j]
                if (f in fieldTok.indices) spec.fields[tok(fieldTok[f])] = CrateRep(fieldRep[f])
                j++
            }
            layer.specs[path] = spec
        }
        layer.crate = this
        layer.index()
        return true
    }

    private fun buildPaths(pi: IntArray, et: IntArray, jm: IntArray) {
        val stackIdx = ArrayList<Int>()
        val stackParent = ArrayList<String?>()
        stackIdx.add(0)
        stackParent.add(null)
        while (stackIdx.isNotEmpty()) {
            var cur = stackIdx.removeAt(stackIdx.size - 1)
            var parent = stackParent.removeAt(stackParent.size - 1)
            while (cur in pi.indices) {
                val me = cur++
                val target = pi[me]
                val here: String
                if (parent == null) {
                    here = "/"
                    parent = "/"
                } else {
                    val t = et[me]
                    val name = tok(kotlin.math.abs(t))
                    here = if (t < 0) "$parent.$name" else UsdPaths.child(parent, name)
                }
                if (target in paths.indices) paths[target] = here
                val hasChild = jm[me] > 0 || jm[me] == -1
                val hasSibling = jm[me] >= 0
                if (hasChild) {
                    if (hasSibling) {
                        stackIdx.add(me + jm[me])
                        stackParent.add(parent)
                    }
                    parent = here
                }
                if (!hasChild && !hasSibling) break
            }
        }
    }

    private fun le64(d: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (d[o + k].toLong() and 0xFF)
        return v
    }

    private fun le32(d: ByteArray, o: Int) =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)

    private fun le16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    private fun compressedInts(n: Int): IntArray {
        val comp = count()
        val raw = tfDecompress(p, comp, 4 + (n * 2 + 7) / 8 + n * 4)
        p += comp
        val out = IntArray(n)
        if (n == 0) return out
        val common = le32(raw, 0)
        val cp = 4
        var vp = 4 + (n * 2 + 7) / 8
        var prev = 0
        for (i in 0 until n) {
            val code = (raw[cp + (i shr 2)].toInt() shr ((i and 3) * 2)) and 3
            prev += when (code) {
                0 -> common
                1 -> raw[vp].toInt().also { vp += 1 }
                2 -> le16(raw, vp).also { vp += 2 }
                else -> le32(raw, vp).also { vp += 4 }
            }
            out[i] = prev
        }
        return out
    }

    private fun compressedLongs(n: Int): LongArray {
        val comp = count()
        val raw = tfDecompress(p, comp, 8 + (n * 2 + 7) / 8 + n * 8)
        p += comp
        val out = LongArray(n)
        if (n == 0) return out
        val common = le64(raw, 0)
        val cp = 8
        var vp = 8 + (n * 2 + 7) / 8
        var prev = 0L
        for (i in 0 until n) {
            val code = (raw[cp + (i shr 2)].toInt() shr ((i and 3) * 2)) and 3
            prev += when (code) {
                0 -> common
                1 -> le16(raw, vp).toLong().also { vp += 2 }
                2 -> le32(raw, vp).toLong().also { vp += 4 }
                else -> le64(raw, vp).also { vp += 8 }
            }
            out[i] = prev
        }
        return out
    }

    private fun tfDecompress(at: Int, len: Int, maxOut: Int): ByteArray {
        require(at >= 0 && len >= 1 && at.toLong() + len <= b.size && maxOut >= 0)
        val out = ByteArray(maxOut)
        val chunks = b[at].toInt() and 0xFF
        var total = 0
        if (chunks == 0) {
            total = lz4(at + 1, len - 1, out, 0)
        } else {
            var q = at + 1
            repeat(chunks) {
                val cs = i32(q)
                q += 4
                require(cs >= 0 && q.toLong() + cs <= at.toLong() + len)
                total += lz4(q, cs, out, total)
                q += cs
            }
        }
        return if (total == maxOut) out else out.copyOf(total)
    }

    private fun lz4(s0: Int, len: Int, dst: ByteArray, d0: Int): Int {
        var s = s0
        val sEnd = s0 + len
        var d = d0
        val dEnd = dst.size
        while (s < sEnd) {
            val token = u8(s++)
            var lit = token ushr 4
            if (lit == 15) {
                while (true) {
                    val x = u8(s++)
                    lit += x
                    if (x != 255) break
                }
            }
            require(s + lit <= sEnd && d + lit <= dEnd)
            System.arraycopy(b, s, dst, d, lit)
            s += lit
            d += lit
            if (s >= sEnd) break
            val off = u16(s)
            s += 2
            require(off in 1..(d - d0))
            var ml = token and 15
            if (ml == 15) {
                while (true) {
                    val x = u8(s++)
                    ml += x
                    if (x != 255) break
                }
            }
            ml += 4
            require(d + ml <= dEnd)
            var m = d - off
            if (off >= ml) {
                System.arraycopy(dst, m, dst, d, ml)
                d += ml
            } else {
                repeat(ml) { dst[d++] = dst[m++] }
            }
        }
        return d - d0
    }

    private fun half(h: Int): Float {
        val s = (h shr 15) and 1
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        val v = when (e) {
            0 -> m * 5.9604645E-8f
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> Float.fromBits(((e + 112) shl 23) or (m shl 13))
        }
        return if (s == 1) -v else v
    }

    private class Kind(val comps: Int, val k: Char)

    private fun kind(type: Int): Kind? = when (type) {
        1 -> Kind(1, 'b')
        2 -> Kind(1, 'c')
        3 -> Kind(1, 'i')
        4 -> Kind(1, 'u')
        5, 6 -> Kind(1, 'l')
        7 -> Kind(1, 'h')
        8 -> Kind(1, 'f')
        9, 56 -> Kind(1, 'd')
        13 -> Kind(4, 'd')
        14 -> Kind(9, 'd')
        15 -> Kind(16, 'd')
        16 -> Kind(4, 'd')
        17 -> Kind(4, 'f')
        18 -> Kind(4, 'h')
        19 -> Kind(2, 'd')
        20 -> Kind(2, 'f')
        21 -> Kind(2, 'h')
        22 -> Kind(2, 'i')
        23 -> Kind(3, 'd')
        24 -> Kind(3, 'f')
        25 -> Kind(3, 'h')
        26 -> Kind(3, 'i')
        27 -> Kind(4, 'd')
        28 -> Kind(4, 'f')
        29 -> Kind(4, 'h')
        30 -> Kind(4, 'i')
        else -> null
    }

    private fun readRaw(k: Char, n: Int): Any = when (k) {
        'd' -> DoubleArray(n) { f64(p + it * 8) }.also { p += n * 8 }
        'f' -> FloatArray(n) { f32(p + it * 4) }.also { p += n * 4 }
        'h' -> FloatArray(n) { half(u16(p + it * 2)) }.also { p += n * 2 }
        'i', 'u' -> IntArray(n) { i32(p + it * 4) }.also { p += n * 4 }
        'l' -> LongArray(n) { i64(p + it * 8) }.also { p += n * 8 }
        else -> IntArray(n) { u8(p + it) }.also { p += n }
    }

    fun unpack(rep: Long): Any? {
        val isArray = (rep ushr 63) and 1L == 1L
        val inlined = (rep ushr 62) and 1L == 1L
        val compressed = (rep ushr 61) and 1L == 1L
        val type = ((rep ushr 48) and 0xFF).toInt()
        val payload = rep and 0xFFFFFFFFFFFFL
        if (isArray) return array(type, payload, compressed)
        if (inlined) return inline(type, payload)
        val saved = p
        try {
            return scalar(type, off(payload))
        } finally {
            p = saved
        }
    }

    private fun inline(type: Int, payload: Long): Any? {
        val p32 = payload.toInt()
        return when (type) {
            1 -> p32 != 0
            2 -> (p32 and 0xFF).toLong()
            3, 5 -> p32.toLong()
            4, 6 -> p32.toLong() and 0xFFFFFFFFL
            7 -> half(p32 and 0xFFFF).toDouble()
            8, 9, 56 -> Float.fromBits(p32).toDouble()
            10 -> str(p32)
            11 -> tok(p32)
            12 -> UsdAsset(tok(p32))
            42, 43, 44 -> p32.toLong()
            13, 14, 15 -> {
                val dim = when (type) { 13 -> 2; 14 -> 3; else -> 4 }
                val m = DoubleArray(dim * dim)
                for (i in 0 until dim) m[i * dim + i] = ((payload ushr (8 * i)) and 0xFF).toByte().toDouble()
                m
            }
            in 19..30 -> {
                val dim = kind(type)!!.comps
                DoubleArray(dim) { ((payload ushr (8 * it)) and 0xFF).toByte().toDouble() }
            }
            31 -> LinkedHashMap<String, Any?>()
            51 -> UsdBlocked
            else -> null
        }
    }

    private fun arrayCount(): Int {
        val v = if (atLeast(0, 7, 0)) i64(p).also { p += 8 } else (i32(p).toLong() and 0xFFFFFFFFL).also { p += 4 }
        require(v >= 0 && v <= b.size.toLong() * 64L && v < Int.MAX_VALUE / 16)
        return v.toInt()
    }

    private fun array(type: Int, payload: Long, compressed: Boolean): Any? {
        if (payload == 0L) {
            return when (type) {
                10, 11, 12 -> emptyList<String>()
                8, 7 -> FloatArray(0)
                9 -> DoubleArray(0)
                else -> IntArray(0)
            }
        }
        val saved = p
        try {
            p = off(payload)
            val n = arrayCount()
            return when (type) {
                10 -> List(n) { str(i32(p + it * 4)) }
                11 -> List(n) { tok(i32(p + it * 4)) }
                12 -> List(n) { UsdAsset(tok(i32(p + it * 4))) }
                3, 4 -> if (compressed && n >= 16) compressedInts(n) else readRaw('i', n)
                5, 6 -> if (compressed && n >= 16) compressedLongs(n) else readRaw('l', n)
                7, 8, 9 -> {
                    val k = kind(type)!!.k
                    if (compressed && n >= 16) {
                        val code = b[p++].toInt().toChar()
                        if (code == 'i') {
                            val ints = compressedInts(n)
                            when (k) {
                                'd' -> DoubleArray(n) { ints[it].toDouble() }
                                else -> FloatArray(n) { ints[it].toFloat() }
                            }
                        } else {
                            val lutSize = i32(p)
                            p += 4
                            require(lutSize in 0..n)
                            val lut = readRaw(k, lutSize)
                            val idx = compressedInts(n)
                            when (lut) {
                                is DoubleArray -> DoubleArray(n) { lut.getOrElse(idx[it]) { 0.0 } }
                                is FloatArray -> FloatArray(n) { lut.getOrElse(idx[it]) { 0f } }
                                else -> null
                            }
                        }
                    } else readRaw(k, n)
                }
                else -> {
                    val kd = kind(type) ?: return null
                    readRaw(kd.k, n * kd.comps)
                }
            }
        } finally {
            p = saved
        }
    }

    private fun scalar(type: Int, at: Int): Any? {
        p = at
        val kd = kind(type)
        if (kd != null) {
            val raw = readRaw(kd.k, kd.comps)
            if (kd.comps == 1) {
                return when (raw) {
                    is DoubleArray -> raw[0]
                    is FloatArray -> raw[0].toDouble()
                    is LongArray -> raw[0]
                    is IntArray -> raw[0].toLong()
                    else -> null
                }
            }
            return raw
        }
        return when (type) {
            31 -> dict()
            32 -> listOp { tok(i32(p)).also { p += 4 } }
            33 -> listOp { str(i32(p)).also { p += 4 } }
            34 -> listOp { UsdPath(path(i32(p))).also { p += 4 } }
            35 -> listOp { reference() }
            55 -> listOp { payloadItem() }
            36, 38 -> listOp { i32(p).toLong().also { p += 4 } }
            37, 39 -> listOp { i64(p).also { p += 8 } }
            40 -> { val n = count(); List(n) { UsdPath(path(i32(p + it * 4))) } }
            41 -> { val n = count(); List(n) { tok(i32(p + it * 4)) } }
            50 -> { val n = count(); List(n) { str(i32(p + it * 4)) } }
            48 -> { val n = count(); DoubleArray(n) { f64(p + it * 8) } }
            45 -> {
                val n = count()
                val m = LinkedHashMap<String, String>()
                repeat(n) {
                    val k = str(i32(p))
                    val v = str(i32(p + 4))
                    p += 8
                    m[k] = v
                }
                m
            }
            47 -> payloadItem()
            46 -> timeSamples()
            else -> null
        }
    }

    private fun timeSamples(): UsdTimeSamples {
        p += i64(p).toInt()
        val timesRep = i64(p)
        p += 8
        p += i64(p).toInt()
        val n = count()
        val repsAt = p
        val times = UsdVal.doubles(unpack(timesRep)) ?: DoubleArray(0)
        return UsdTimeSamples(if (times.size > n) times.copyOf(n) else times) { i ->
            if (i in 0 until n) unpack(i64(repsAt + i * 8)) else null
        }
    }

    private fun dict(): Map<String, Any?> {
        val n = count()
        val m = LinkedHashMap<String, Any?>()
        repeat(n) {
            val key = str(i32(p))
            p += 4
            p += i64(p).toInt()
            val rep = i64(p)
            p += 8
            val after = p
            m[key] = try { unpack(rep) } catch (_: Throwable) { null }
            p = after
        }
        return m
    }

    private fun skipDict() {
        val n = count()
        repeat(n) {
            p += 4
            p += i64(p).toInt()
            p += 8
        }
    }

    private fun reference(): UsdRef {
        val asset = str(i32(p))
        val prim = path(i32(p + 4))
        p += 8 + 16
        skipDict()
        return UsdRef(asset, prim)
    }

    private fun payloadItem(): UsdRef {
        val asset = str(i32(p))
        val prim = path(i32(p + 4))
        p += 8
        if (atLeast(0, 8, 0)) p += 16
        return UsdRef(asset, prim)
    }

    private fun listOp(item: () -> Any?): UsdListOp {
        val h = u8(p++)
        val op = UsdListOp()
        fun list(): List<Any?> {
            val n = count()
            return List(n) { item() }
        }
        if (h and 1 != 0) op.explicit = emptyList()
        if (h and 2 != 0) op.explicit = list()
        if (h and 4 != 0) op.added.addAll(list())
        if (h and 32 != 0) op.prepended.addAll(list())
        if (h and 64 != 0) op.appended.addAll(list())
        if (h and 8 != 0) op.deleted.addAll(list())
        if (h and 16 != 0) op.ordered.addAll(list())
        return op
    }
}

internal class UsdaParser(private val s: String, private val layer: UsdLayer) {
    private var i = 0
    private val n = s.length
    private var kind = EOF
    private var text = ""
    private var num = 0.0

    private companion object {
        const val EOF = 0
        const val ID = 1
        const val STR = 2
        const val NUM = 3
        const val ASSET = 4
        const val PATH = 5
        const val PUNCT = 6
        val LIST_OPS = setOf("prepend", "append", "add", "delete", "reorder")
    }

    private class State(val i: Int, val kind: Int, val text: String, val num: Double)

    private fun save() = State(i, kind, text, num)
    private fun restore(st: State) {
        i = st.i
        kind = st.kind
        text = st.text
        num = st.num
    }

    private fun isP(c: Char) = kind == PUNCT && text.length == 1 && text[0] == c
    private fun isId(t: String) = kind == ID && text == t

    private fun expectP(c: Char) {
        if (!isP(c)) throw IllegalStateException("USDA: expected $c")
        advance()
    }

    private fun idChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.'

    private fun advance() {
        while (i < n) {
            val c = s[i]
            if (c == '#') {
                while (i < n && s[i] != '\n') i++
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') i++
            else break
        }
        if (i >= n) {
            kind = EOF
            text = ""
            return
        }
        val c = s[i]
        when {
            c == '"' || c == '\'' -> {
                val triple = i + 2 < n && s[i + 1] == c && s[i + 2] == c
                val sb = StringBuilder()
                if (triple) {
                    i += 3
                    while (i < n && !(s[i] == c && i + 2 < n && s[i + 1] == c && s[i + 2] == c)) {
                        if (s[i] == '\\' && i + 1 < n) { sb.append(escape(s[i + 1])); i += 2 } else sb.append(s[i++])
                    }
                    i += 3
                } else {
                    i++
                    while (i < n && s[i] != c) {
                        if (s[i] == '\\' && i + 1 < n) { sb.append(escape(s[i + 1])); i += 2 } else sb.append(s[i++])
                    }
                    i++
                }
                kind = STR
                text = sb.toString()
            }
            c == '@' -> {
                if (s.startsWith("@@@", i)) {
                    val e = s.indexOf("@@@", i + 3).let { if (it < 0) n else it }
                    text = s.substring(i + 3, e)
                    i = minOf(n, e + 3)
                } else {
                    val e = s.indexOf('@', i + 1).let { if (it < 0) n else it }
                    text = s.substring(i + 1, e)
                    i = minOf(n, e + 1)
                }
                kind = ASSET
            }
            c == '<' -> {
                val e = s.indexOf('>', i + 1).let { if (it < 0) n else it }
                text = s.substring(i + 1, e).trim()
                i = minOf(n, e + 1)
                kind = PATH
            }
            c.isDigit() || ((c == '-' || c == '+' || c == '.') && i + 1 < n && (s[i + 1].isDigit() || s[i + 1] == '.' || s[i + 1] == 'i' || s[i + 1] == 'n')) -> {
                val st = i
                if (c == '-' || c == '+') i++
                if (s.startsWith("inf", i)) {
                    i += 3
                    num = if (c == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                } else if (s.startsWith("nan", i)) {
                    i += 3
                    num = Double.NaN
                } else {
                    while (i < n) {
                        val d = s[i]
                        if (d.isDigit() || d == '.' || d == 'e' || d == 'E' || ((d == '-' || d == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E'))) i++ else break
                    }
                    num = s.substring(st, i).toDoubleOrNull() ?: 0.0
                }
                kind = NUM
                text = ""
            }
            c.isLetter() || c == '_' -> {
                val st = i
                while (i < n && idChar(s[i])) i++
                text = s.substring(st, i)
                kind = ID
                if (text == "inf" || text == "nan") {
                    num = if (text == "inf") Double.POSITIVE_INFINITY else Double.NaN
                    kind = NUM
                }
            }
            else -> {
                text = c.toString()
                kind = PUNCT
                i++
            }
        }
    }

    private fun escape(c: Char) = when (c) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        else -> c
    }

    fun parse(): Boolean {
        if (!s.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("#usda")) return false
        layer.specs["/"] = UsdSpec(UsdSpecType.PSEUDO_ROOT)
        advance()
        if (isP('(')) meta(layer.specs["/"]!!)
        while (kind != EOF) {
            if (kind == ID && (text == "def" || text == "over" || text == "class")) prim("/") else advance()
        }
        layer.index()
        return true
    }

    private fun prim(parent: String) {
        val specifier = when (text) {
            "def" -> 0L
            "over" -> 1L
            else -> 2L
        }
        advance()
        var type = ""
        if (kind == ID) {
            type = text
            advance()
        }
        if (kind != STR) throw IllegalStateException("USDA: prim name")
        val path = UsdPaths.child(parent, text)
        val parentSpec = layer.specs[parent]
        if (parentSpec != null) {
            @Suppress("UNCHECKED_CAST")
            val order = parentSpec.fields["primChildren"] as? MutableList<String> ?: ArrayList<String>().also { parentSpec.fields["primChildren"] = it }
            if (text !in order) order.add(text)
        }
        advance()
        val spec = layer.specs.getOrPut(path) { UsdSpec(UsdSpecType.PRIM) }
        spec.fields["specifier"] = specifier
        if (type.isNotEmpty()) spec.fields["typeName"] = type
        if (isP('(')) meta(spec)
        expectP('{')
        body(path)
        expectP('}')
    }

    private fun body(path: String) {
        while (kind != EOF && !isP('}')) {
            when {
                kind == ID && (text == "def" || text == "over" || text == "class") -> prim(path)
                isId("variantSet") -> variantSet(path)
                isId("reorder") -> {
                    val st = save()
                    advance()
                    if (isId("nameChildren") || isId("properties")) {
                        advance()
                        expectP('=')
                        value()
                    } else {
                        restore(st)
                        property(path)
                    }
                }
                kind == ID -> property(path)
                else -> advance()
            }
        }
    }

    private fun variantSet(path: String) {
        advance()
        val setName = text
        advance()
        expectP('=')
        expectP('{')
        layer.specs.getOrPut("$path{$setName=}") { UsdSpec(UsdSpecType.VARIANT_SET) }
        while (kind == STR) {
            val vp = "$path{$setName=$text}"
            advance()
            val spec = layer.specs.getOrPut(vp) { UsdSpec(UsdSpecType.VARIANT) }
            if (isP('(')) meta(spec)
            expectP('{')
            body(vp)
            expectP('}')
        }
        expectP('}')
    }

    private fun property(path: String) {
        var op: String? = null
        if (kind == ID && text in LIST_OPS) {
            op = text
            advance()
        }
        var variability = ""
        while (kind == ID && (text == "custom" || text == "uniform" || text == "varying" || text == "config")) {
            if (text != "custom") variability = text
            advance()
        }
        if (isId("rel")) {
            advance()
            val name = text.removeSuffix(".default")
            advance()
            val spec = layer.specs.getOrPut("$path.$name") { UsdSpec(UsdSpecType.RELATIONSHIP) }
            if (isP('=')) {
                advance()
                listOp(spec, "targetPaths", op, paths(value()))
            }
            if (isP('(')) meta(spec)
            return
        }
        if (kind != ID) {
            advance()
            return
        }
        var type = text
        advance()
        if (isP('[')) {
            advance()
            expectP(']')
            type += "[]"
        }
        if (kind != ID) return
        var name = text
        advance()
        var suffix = ""
        for (sfx in arrayOf(".connect", ".timeSamples", ".spline", ".default")) {
            if (name.endsWith(sfx)) {
                suffix = sfx
                name = name.dropLast(sfx.length)
                break
            }
        }
        val spec = layer.specs.getOrPut("$path.$name") { UsdSpec(UsdSpecType.ATTRIBUTE) }
        spec.fields["typeName"] = type
        if (variability == "uniform") spec.fields["variability"] = 1L
        if (isP('=')) {
            advance()
            when (suffix) {
                ".connect" -> listOp(spec, "connectionPaths", op, paths(value()))
                ".timeSamples" -> spec.fields["timeSamples"] = timeSamples(type)
                ".spline" -> skipBalanced()
                else -> spec.fields["default"] = fixQuat(type, value())
            }
        }
        if (isP('(')) meta(spec)
    }

    private fun skipBalanced() {
        if (!(isP('{') || isP('(') || isP('['))) {
            value()
            return
        }
        var depth = 0
        do {
            if (isP('{') || isP('(') || isP('[')) depth++
            if (isP('}') || isP(')') || isP(']')) depth--
            advance()
        } while (depth > 0 && kind != EOF)
    }

    private fun fixQuat(type: String, v: Any?): Any? {
        if (!type.startsWith("quat")) return v
        val d = UsdVal.doubles(v) ?: return v
        val out = DoubleArray(d.size)
        var k = 0
        while (k + 3 < d.size) {
            out[k] = d[k + 1]
            out[k + 1] = d[k + 2]
            out[k + 2] = d[k + 3]
            out[k + 3] = d[k]
            k += 4
        }
        return out
    }

    private fun timeSamples(type: String): UsdTimeSamples {
        expectP('{')
        val times = ArrayList<Double>()
        val values = ArrayList<Any?>()
        while (kind == NUM) {
            val t = num
            advance()
            expectP(':')
            val v = value()
            times.add(t)
            values.add(if (v === UsdBlocked) UsdBlocked else fixQuat(type, v))
            if (isP(',')) advance()
        }
        expectP('}')
        val order = times.indices.sortedBy { times[it] }
        val t = DoubleArray(order.size) { times[order[it]] }
        val v = order.map { values[it] }
        return UsdTimeSamples(t) { v.getOrNull(it) }
    }

    private fun skipRefMeta() {
        if (!isP('(')) return
        val st = save()
        advance()
        val metaLike = isP(')') || (kind == ID && run {
            advance()
            isP('=')
        })
        restore(st)
        if (metaLike) skipBalanced()
    }

    private fun value(): Any? {
        when (kind) {
            NUM -> return num.also { advance() }
            STR -> return text.also { advance() }
            ASSET -> {
                val a = text
                advance()
                return if (kind == PATH) {
                    val pth = text
                    advance()
                    skipRefMeta()
                    UsdRef(a, pth)
                } else {
                    skipRefMeta()
                    UsdAsset(a)
                }
            }
            PATH -> {
                val pth = text
                advance()
                skipRefMeta()
                return UsdPath(pth)
            }
            ID -> {
                val t = text
                advance()
                return when (t) {
                    "true" -> true
                    "false" -> false
                    "None" -> UsdBlocked
                    else -> t
                }
            }
        }
        if (isP('[')) {
            advance()
            numeric(']')?.let { return it }
            val items = ArrayList<Any?>()
            while (kind != EOF && !isP(']')) {
                items.add(value())
                if (isP(',')) advance()
            }
            advance()
            return items
        }
        if (isP('(')) {
            advance()
            numeric(')')?.let { return it }
            val items = ArrayList<Any?>()
            while (kind != EOF && !isP(')')) {
                items.add(value())
                if (isP(',')) advance()
            }
            advance()
            return items
        }
        if (isP('{')) {
            advance()
            val m = LinkedHashMap<String, Any?>()
            while (kind != EOF && !isP('}')) {
                if (kind == ID && kind != STR) {
                    advance()
                    if (isP('[')) {
                        advance()
                        expectP(']')
                    }
                }
                if (kind != ID && kind != STR) {
                    advance()
                    continue
                }
                val key = text
                advance()
                if (isP('=')) {
                    advance()
                    m[key] = value()
                }
                if (isP(',') || isP(';')) advance()
            }
            advance()
            return m
        }
        advance()
        return null
    }

    private fun numeric(close: Char): DoubleArray? {
        val st = save()
        var buf = DoubleArray(16)
        var size = 0
        var depth = 0
        while (true) {
            when {
                kind == NUM -> {
                    if (size == buf.size) buf = buf.copyOf(size * 2)
                    buf[size++] = num
                }
                isP('(') -> depth++
                isP(')') && depth > 0 -> depth--
                isP(close) && depth == 0 -> {
                    advance()
                    return buf.copyOf(size)
                }
                isP(',') -> {}
                else -> {
                    restore(st)
                    return null
                }
            }
            advance()
        }
    }

    private fun paths(v: Any?): List<Any?> = UsdVal.items(v).mapNotNull {
        when (it) {
            is UsdPath -> it
            is UsdRef -> UsdPath(it.prim)
            else -> null
        }
    }

    private fun refs(v: Any?): List<Any?> = UsdVal.items(v).mapNotNull {
        when (it) {
            is UsdRef -> it
            is UsdAsset -> UsdRef(it.path, "")
            is UsdPath -> UsdRef("", it.path)
            else -> null
        }
    }

    private fun listOp(spec: UsdSpec, key: String, op: String?, items: List<Any?>) {
        val lo = spec.fields[key] as? UsdListOp ?: UsdListOp().also { spec.fields[key] = it }
        when (op) {
            null -> lo.explicit = items
            "prepend" -> lo.prepended.addAll(items)
            "append" -> lo.appended.addAll(items)
            "add" -> lo.added.addAll(items)
            "delete" -> lo.deleted.addAll(items)
            else -> lo.ordered.addAll(items)
        }
    }

    private fun meta(spec: UsdSpec) {
        expectP('(')
        while (kind != EOF && !isP(')')) {
            if (kind == STR) {
                spec.fields["documentation"] = text
                advance()
                continue
            }
            if (kind != ID) {
                if (isP('(') || isP('[') || isP('{')) skipBalanced() else advance()
                continue
            }
            var op: String? = null
            if (text in LIST_OPS) {
                op = text
                advance()
                if (kind != ID) continue
            }
            val key = text
            advance()
            if (!isP('=')) continue
            advance()
            val v = value()
            when (key) {
                "references", "payload" -> listOp(spec, key, op, refs(v))
                "inherits", "specializes" -> listOp(spec, key, op, paths(v))
                "variantSets" -> listOp(spec, "variantSetNames", op, UsdVal.items(v).mapNotNull { UsdVal.str(it) })
                "apiSchemas" -> listOp(spec, key, op, UsdVal.items(v).mapNotNull { UsdVal.str(it) })
                "variants" -> (v as? Map<*, *>)?.let { m ->
                    spec.fields["variantSelection"] = m.entries.mapNotNull { e ->
                        val k = e.key as? String ?: return@mapNotNull null
                        val sv = UsdVal.str(e.value) ?: return@mapNotNull null
                        k to sv
                    }.toMap(LinkedHashMap())
                }
                "subLayers" -> spec.fields["subLayers"] = UsdVal.items(v).mapNotNull { (it as? UsdAsset)?.path ?: (it as? UsdRef)?.asset }
                else -> spec.fields[key] = v
            }
        }
        if (isP(')')) advance()
    }
}

internal object UsdMtlx {
    fun build(bytes: ByteArray, layer: UsdLayer): Boolean {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Throwable) {}
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false)
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Throwable) {}
        f.isExpandEntityReferences = false
        val doc = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))
        val root = doc.documentElement ?: return false
        if (root.tagName != "materialx") return false
        val pseudo = UsdSpec(UsdSpecType.PSEUDO_ROOT)
        pseudo.fields["defaultPrim"] = "MaterialX"
        layer.specs["/"] = pseudo
        fun prim(path: String, type: String) {
            layer.specs.getOrPut(path) { UsdSpec(UsdSpecType.PRIM) }.apply {
                fields["specifier"] = 0L
                fields["typeName"] = type
            }
        }
        prim("/MaterialX", "Scope")
        prim("/MaterialX/Materials", "Scope")
        fun attr(path: String, type: String): UsdSpec = layer.specs.getOrPut(path) { UsdSpec(UsdSpecType.ATTRIBUTE) }.also { it.fields["typeName"] = type }
        fun connect(path: String, target: String) {
            attr(path, "token").fields["connectionPaths"] = UsdListOp().also { it.explicit = listOf(UsdPath(target)) }
        }
        fun elements(e: org.w3c.dom.Element): List<org.w3c.dom.Element> {
            val out = ArrayList<org.w3c.dom.Element>()
            val nl = e.childNodes
            for (k in 0 until nl.length) (nl.item(k) as? org.w3c.dom.Element)?.let { out.add(it) }
            return out
        }
        fun prefix(e: org.w3c.dom.Element?): String {
            var cur: org.w3c.dom.Node? = e
            while (cur is org.w3c.dom.Element) {
                if (cur.hasAttribute("fileprefix")) return cur.getAttribute("fileprefix")
                cur = cur.parentNode
            }
            return ""
        }
        fun parseValue(type: String, raw: String, input: org.w3c.dom.Element): Any? = when {
            type == "filename" -> UsdAsset(prefix(input) + raw)
            type == "string" -> raw
            type == "boolean" -> raw.trim() == "true"
            else -> {
                val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size == 1) parts[0] else if (parts.isEmpty()) raw else parts.toDoubleArray()
            }
        }
        val top = HashMap<String, org.w3c.dom.Element>()
        for (e in elements(root)) if (e.hasAttribute("name")) top[e.getAttribute("name")] = e
        lateinit var emitTop: (String, String, HashSet<String>) -> Unit
        lateinit var emitGraph: (String, String, HashSet<String>) -> Unit
        fun shader(node: org.w3c.dom.Element, scope: String, graphPath: String?, mp: String, done: HashSet<String>) {
            val name = node.getAttribute("name").ifBlank { return }
            val path = "$scope/$name"
            prim(path, "Shader")
            val id = node.getAttribute("nodedef").ifBlank { "ND_${node.tagName}_${node.getAttribute("type")}" }
            attr("$path.info:id", "token").fields["default"] = id
            for (inp in elements(node)) {
                if (inp.tagName != "input") continue
                val iname = inp.getAttribute("name")
                val type = inp.getAttribute("type")
                val ap = "$path.inputs:$iname"
                val out = inp.getAttribute("output").ifBlank { "out" }
                when {
                    inp.hasAttribute("nodegraph") -> {
                        val g = inp.getAttribute("nodegraph")
                        emitGraph(g, mp, done)
                        connect(ap, "$mp/$g.outputs:$out")
                    }
                    inp.hasAttribute("nodename") -> {
                        val nn = inp.getAttribute("nodename")
                        if (graphPath == null) emitTop(nn, mp, done)
                        connect(ap, "$scope/$nn.outputs:$out")
                    }
                    inp.hasAttribute("interfacename") && graphPath != null -> connect(ap, "$graphPath.inputs:${inp.getAttribute("interfacename")}")
                    inp.hasAttribute("value") -> attr(ap, type).fields["default"] = parseValue(type, inp.getAttribute("value"), inp)
                }
            }
        }
        emitTop = { name, mp, done ->
            val e = top[name]
            if (e != null && e.tagName != "nodegraph" && done.add("n:$name")) shader(e, mp, null, mp, done)
        }
        emitGraph = { name, mp, done ->
            val e = top[name]
            if (e != null && e.tagName == "nodegraph" && done.add("g:$name")) {
                val gp = "$mp/$name"
                prim(gp, "NodeGraph")
                for (c in elements(e)) {
                    when (c.tagName) {
                        "output" -> {
                            val target = c.getAttribute("nodename")
                            if (target.isNotBlank()) connect("$gp.outputs:${c.getAttribute("name")}", "$gp/$target.outputs:${c.getAttribute("output").ifBlank { "out" }}")
                        }
                        "input" -> if (c.hasAttribute("value")) {
                            val t = c.getAttribute("type")
                            attr("$gp.inputs:${c.getAttribute("name")}", t).fields["default"] = parseValue(t, c.getAttribute("value"), c)
                        }
                        else -> shader(c, gp, gp, mp, done)
                    }
                }
            }
        }
        for (e in elements(root)) {
            if (e.tagName != "surfacematerial" && e.tagName != "volumematerial") continue
            val name = e.getAttribute("name").ifBlank { continue }
            val mp = "/MaterialX/Materials/$name"
            prim(mp, "Material")
            val done = HashSet<String>()
            for (inp in elements(e)) {
                if (inp.tagName != "input" || inp.getAttribute("name") != "surfaceshader") continue
                val nn = inp.getAttribute("nodename")
                if (nn.isBlank()) continue
                emitTop(nn, mp, done)
                connect("$mp.outputs:mtlx:surface", "$mp/$nn.outputs:surface")
            }
        }
        layer.index()
        return true
    }
}
