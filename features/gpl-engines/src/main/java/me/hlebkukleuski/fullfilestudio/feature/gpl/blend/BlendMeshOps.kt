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

import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser

internal object BlendMeshOps {
    const val MIR_MIRROR_U = 1 shl 1
    const val MIR_MIRROR_V = 1 shl 2
    const val MIR_AXIS_X = 1 shl 3
    const val MIR_VGROUP = 1 shl 6
    const val MIR_NO_MERGE = 1 shl 7
    const val MIR_BISECT_AXIS_X = 1 shl 8
    const val MIR_BISECT_FLIP_AXIS_X = 1 shl 11
    const val MIR_MIRROR_UDIM = 1 shl 14

    private fun isSep(c: Char) = c == '.' || c == ' ' || c == '-' || c == '_'

    fun flipSideName(src: String): String {
        var name = src
        if (name.length < 3) return name
        var number = ""
        if (name.last().isDigit()) {
            val idx = name.lastIndexOf('.')
            if (idx >= 0 && idx + 1 < name.length && name[idx + 1].isDigit()) {
                number = name.substring(idx)
                name = name.substring(0, idx)
            }
        }
        val len = name.length
        var prefix = name
        var suffix = ""
        var replace: String? = null
        var set = false
        if (len > 1 && isSep(name[len - 2])) {
            set = true
            when (name[len - 1]) {
                'l' -> { prefix = name.substring(0, len - 1); replace = "r" }
                'r' -> { prefix = name.substring(0, len - 1); replace = "l" }
                'L' -> { prefix = name.substring(0, len - 1); replace = "R" }
                'R' -> { prefix = name.substring(0, len - 1); replace = "L" }
                else -> set = false
            }
        }
        if (!set && len > 1 && isSep(name[1])) {
            set = true
            when (name[0]) {
                'l' -> { replace = "r"; suffix = name.substring(1); prefix = "" }
                'r' -> { replace = "l"; suffix = name.substring(1); prefix = "" }
                'L' -> { replace = "R"; suffix = name.substring(1); prefix = "" }
                'R' -> { replace = "L"; suffix = name.substring(1); prefix = "" }
                else -> set = false
            }
        }
        if (!set && len > 5) {
            val ri = prefix.indexOf("right", ignoreCase = true)
            if (ri == 0 || (ri >= 0 && ri == len - 5)) {
                replace = if (prefix[ri] == 'r') "left" else if (prefix[ri + 1] == 'I') "LEFT" else "Left"
                suffix = prefix.substring(ri + 5)
                prefix = prefix.substring(0, ri)
            } else {
                val li = prefix.indexOf("left", ignoreCase = true)
                if (li == 0 || (li >= 0 && li == len - 4)) {
                    replace = if (prefix[li] == 'l') "right" else if (prefix[li + 1] == 'E') "RIGHT" else "Right"
                    suffix = prefix.substring(li + 4)
                    prefix = prefix.substring(0, li)
                }
            }
        }
        return prefix + (replace ?: "") + suffix + number
    }

    fun flipMap(groups: List<String>): IntArray {
        val map = IntArray(groups.size) { -1 }
        for (i in groups.indices) {
            if (map[i] != -1) continue
            val flipped = flipSideName(groups[i])
            if (flipped != groups[i]) {
                val j = groups.indexOf(flipped)
                if (j != -1) { map[i] = j; map[j] = i }
            }
        }
        return map
    }

    private fun flipDvert(dv: List<Pair<Int, Float>>, map: IntArray): List<Pair<Int, Float>> =
        dv.map { (g, w) -> (if (g < map.size && map[g] >= 0) map[g] else g) to w }

    private fun flipDvertMerged(dv: List<Pair<Int, Float>>, map: IntArray): List<Pair<Int, Float>> {
        val list = dv.map { floatArrayOf(it.first.toFloat(), it.second) }.toMutableList()
        val total = list.size
        for (i in 0 until total) {
            val g = list[i][0].toInt()
            if (g < map.size && map[g] >= 0) {
                val other = map[g]
                var cpy = list.firstOrNull { it[0].toInt() == other }
                if (cpy == null) { cpy = floatArrayOf(other.toFloat(), 0f); list.add(cpy) }
                val w = 0.5f * (cpy[1] + list[i][1])
                cpy[1] = w
                list[i][1] = w
            }
        }
        return list.map { it[0].toInt() to it[1] }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x ushr s) and 0xFF
        fun q(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
        var r = 0
        for (s in intArrayOf(24, 16, 8, 0)) r = r or (q(ch(a, s) * (1f - t) + ch(b, s) * t) shl s)
        return r
    }

    private fun averageColor(c: List<Int>): Int {
        if (c.size == 1) return c[0]
        fun q(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
        var r = 0
        for (s in intArrayOf(24, 16, 8, 0)) {
            var acc = 0f
            for (x in c) acc += ((x ushr s) and 0xFF) / c.size.toFloat()
            r = r or (q(acc) shl s)
        }
        return r
    }

    fun bisect(d: BlendMeshData, planeCo: FloatArray, planeNo: FloatArray, threshold: Float): BlendMeshData {
        val off = -(planeNo[0] * planeCo[0] + planeNo[1] * planeCo[1] + planeNo[2] * planeCo[2])
        val n = d.nV
        val dist = FloatArray(n) { v -> planeNo[0] * d.co[v * 3] + planeNo[1] * d.co[v * 3 + 1] + planeNo[2] * d.co[v * 3 + 2] + off }
        val side = IntArray(n) { if (dist[it] < -threshold) -1 else if (dist[it] > threshold) 1 else 0 }
        val co = ArrayList<Float>(d.co.size + 32)
        for (x in d.co) co.add(x)
        val pointColor = d.pointColor?.toMutableList()
        val vertCrease = d.vertCrease?.toMutableList()
        val dverts = d.dverts?.toMutableList()
        val edgeVert = HashMap<Long, Int>()
        val cutFac = HashMap<Long, Float>()
        fun cut(a: Int, b: Int): Int {
            val k = BlendMeshData.key(a, b)
            edgeVert[k]?.let { return it }
            val t = dist[a] / (dist[a] - dist[b])
            val id = co.size / 3
            for (c in 0 until 3) co.add(d.co[a * 3 + c] + (d.co[b * 3 + c] - d.co[a * 3 + c]) * t)
            pointColor?.add(lerpColor(pointColor[a], pointColor[b], t))
            vertCrease?.add(vertCrease[a] + (vertCrease[b] - vertCrease[a]) * t)
            if (dverts != null) {
                val acc = LinkedHashMap<Int, Float>()
                for ((g, w) in dverts[a]) acc[g] = (acc[g] ?: 0f) + w * (1f - t)
                for ((g, w) in dverts[b]) acc[g] = (acc[g] ?: 0f) + w * t
                dverts.add(acc.entries.map { it.key to it.value })
            }
            edgeVert[k] = id
            cutFac[k] = if (a < b) t else 1f - t
            return id
        }
        val fStart = ArrayList<Int>()
        val fSize = ArrayList<Int>()
        val corners = ArrayList<Int>()
        val faceMat = d.faceMat?.let { ArrayList<Int>() }
        val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        val creases = HashMap<Long, Float>()
        for (f in 0 until d.nF) {
            val s = d.fStart[f]
            val m = d.fSize[f]
            val hasPos = (0 until m).any { side[d.corners[s + it]] > 0 }
            if (hasPos && (0 until m).none { side[d.corners[s + it]] < 0 }) continue
            val out = ArrayList<Int>()
            val outUv = ArrayList<Float>()
            val outC = ArrayList<Int>()
            for (k in 0 until m) {
                val ca = s + k
                val cb = s + (k + 1) % m
                val a = d.corners[ca]
                val b = d.corners[cb]
                if (side[a] <= 0) {
                    out.add(a)
                    d.uv?.let { outUv.add(it[ca * 2]); outUv.add(it[ca * 2 + 1]) }
                    d.cornerColor?.let { outC.add(it[ca]) }
                }
                if (side[a] * side[b] < 0) {
                    val nv = cut(a, b)
                    val t = dist[a] / (dist[a] - dist[b])
                    out.add(nv)
                    d.uv?.let { outUv.add(it[ca * 2] + (it[cb * 2] - it[ca * 2]) * t); outUv.add(it[ca * 2 + 1] + (it[cb * 2 + 1] - it[ca * 2 + 1]) * t) }
                    d.cornerColor?.let { outC.add(lerpColor(it[ca], it[cb], t)) }
                    d.edgeCrease[BlendMeshData.key(a, b)]?.let { cr -> creases[BlendMeshData.key(a, nv)] = cr; creases[BlendMeshData.key(nv, b)] = cr }
                }
            }
            if (out.size < 3) continue
            fStart.add(corners.size); fSize.add(out.size); corners.addAll(out)
            faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
            uv?.addAll(outUv); cc?.addAll(outC)
        }
        for ((k, v) in d.edgeCrease) {
            val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
            if (a < n && b < n && side[a] <= 0 && side[b] <= 0) creases.putIfAbsent(k, v)
        }
        val loose = ArrayList<Int>()
        for (i in 0 until d.looseEdges.size / 2) {
            val a = d.looseEdges[i * 2]; val b = d.looseEdges[i * 2 + 1]
            if (side[a] <= 0 && side[b] <= 0) { loose.add(a); loose.add(b) }
            else if (side[a] * side[b] < 0) { val nv = cut(a, b); if (side[a] < 0) { loose.add(a); loose.add(nv) } else { loose.add(nv); loose.add(b) } }
        }
        val kept = d.nV
        val total = co.size / 3
        val alive = BooleanArray(total) { it >= kept || side[it] <= 0 }
        val remap = IntArray(total)
        var nn = 0
        for (v in 0 until total) remap[v] = if (alive[v]) nn++ else -1
        val outCo = FloatArray(nn * 3)
        for (v in 0 until total) if (alive[v]) for (c in 0 until 3) outCo[remap[v] * 3 + c] = co[v * 3 + c]
        val outCreases = HashMap<Long, Float>()
        for ((k, v) in creases) {
            val a = remap[(k ushr 32).toInt()]; val b = remap[(k and 0xFFFFFFFFL).toInt()]
            if (a >= 0 && b >= 0) outCreases[BlendMeshData.key(a, b)] = v
        }
        return BlendMeshData(
            outCo, fStart.toIntArray(), fSize.toIntArray(), IntArray(corners.size) { remap[corners[it]] },
            faceMat?.toIntArray(), faceSmooth?.toBooleanArray(), uv?.toFloatArray(), cc?.toIntArray(),
            pointColor?.let { pc -> IntArray(nn).also { o -> for (v in 0 until total) if (alive[v]) o[remap[v]] = pc[v] } },
            outCreases,
            vertCrease?.let { vc -> FloatArray(nn).also { o -> for (v in 0 until total) if (alive[v]) o[remap[v]] = vc[v] } },
            IntArray(loose.size) { remap[loose[it]] },
            dverts?.let { dv -> Array(nn) { emptyList<Pair<Int, Float>>() }.also { o -> for (v in 0 until total) if (alive[v]) o[remap[v]] = dv[v] } },
            d.groups
        ).carryNormals(d, HashSet<Long>().also { set ->
            for (k in d.sharpEdges) {
                val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
                val cut = edgeVert[k]
                if (cut != null) { if (side[a] <= 0 && remap[a] >= 0) set.add(BlendMeshData.key(remap[a], remap[cut])); if (side[b] <= 0 && remap[b] >= 0) set.add(BlendMeshData.key(remap[b], remap[cut])) }
                else if (a < n && b < n && side[a] <= 0 && side[b] <= 0) set.add(BlendMeshData.key(remap[a], remap[b]))
            }
        })
    }

    fun mirror(
        src: BlendMeshData, axis: Int, mtx: FloatArray, flag: Int, tolerance: Float, bisectThreshold: Float,
        uvOffset: FloatArray, uvOffsetCopy: FloatArray, correctOrder: Boolean, planeCo: FloatArray?, planeNo: FloatArray?
    ): BlendMeshData {
        val doBisect = flag and (MIR_BISECT_AXIS_X shl axis) != 0
        val d = if (doBisect && planeCo != null && planeNo != null) {
            val no = planeNo.copyOf()
            if (flag and (MIR_BISECT_FLIP_AXIS_X shl axis) == 0) for (i in 0 until 3) no[i] = -no[i]
            bisect(src, planeCo, no, bisectThreshold)
        } else src
        val n = d.nV
        val doMerge = flag and MIR_NO_MERGE == 0
        val tolSq = tolerance * tolerance
        val co = FloatArray(n * 6)
        d.co.copyInto(co)
        val dest = IntArray(n * 2) { -1 }
        var merged = 0
        for (i in 0 until n) {
            val p = BlendAnimator.transformPoint(mtx, d.co[i * 3], d.co[i * 3 + 1], d.co[i * 3 + 2])
            val j = n + i
            for (c in 0 until 3) co[j * 3 + c] = p[c]
            if (doMerge) {
                val dx = co[i * 3] - co[j * 3]; val dy = co[i * 3 + 1] - co[j * 3 + 1]; val dz = co[i * 3 + 2] - co[j * 3 + 2]
                if (dx * dx + dy * dy + dz * dz < tolSq) {
                    if (correctOrder) dest[j] = i else dest[i] = j
                    merged++
                    for (c in 0 until 3) {
                        val m = (co[i * 3 + c] + co[j * 3 + c]) * 0.5f
                        co[j * 3 + c] = m
                        co[i * 3 + c] = m
                    }
                }
            }
        }
        val nf = d.nF
        val nc = d.corners.size
        val fStart = IntArray(nf * 2)
        val fSize = IntArray(nf * 2)
        val corners = IntArray(nc * 2)
        val srcCorner = IntArray(nc * 2)
        for (f in 0 until nf) {
            val s = d.fStart[f]
            val m = d.fSize[f]
            fStart[f] = s; fSize[f] = m
            fStart[nf + f] = nc + s; fSize[nf + f] = m
            for (k in 0 until m) {
                corners[s + k] = d.corners[s + k]
                srcCorner[s + k] = s + k
                val dst = if (k == 0) nc + s else nc + s + m - k
                corners[dst] = d.corners[s + k] + n
                srcCorner[dst] = s + k
            }
        }
        val uv = d.uv?.let { u ->
            val o = FloatArray(nc * 4)
            for (c in 0 until nc * 2) { o[c * 2] = u[srcCorner[c] * 2]; o[c * 2 + 1] = u[srcCorner[c] * 2 + 1] }
            if (flag and (MIR_MIRROR_U or MIR_MIRROR_V) != 0 || uvOffsetCopy[0] != 0f || uvOffsetCopy[1] != 0f) {
                val udim = flag and MIR_MIRROR_UDIM != 0
                for (c in nc until nc * 2) {
                    if (flag and MIR_MIRROR_U != 0) {
                        val x = o[c * 2]
                        o[c * 2] = if (udim) kotlin.math.ceil(x) - (x % 1.0f) + uvOffset[0] else 1.0f - x + uvOffset[0]
                    }
                    if (flag and MIR_MIRROR_V != 0) {
                        val y = o[c * 2 + 1]
                        o[c * 2 + 1] = if (udim) kotlin.math.ceil(y) - (y % 1.0f) + uvOffset[1] else 1.0f - y + uvOffset[1]
                    }
                    o[c * 2] += uvOffsetCopy[0]
                    o[c * 2 + 1] += uvOffsetCopy[1]
                }
            }
            o
        }
        val cornerColor = d.cornerColor?.let { cc -> IntArray(nc * 2) { cc[srcCorner[it]] } }
        val pointColor = d.pointColor?.let { pc -> IntArray(n * 2) { pc[it % n] } }
        val creases = HashMap<Long, Float>()
        for ((k, v) in d.edgeCrease) creases[k] = v
        for ((k, v) in d.edgeCrease) creases[BlendMeshData.key((k ushr 32).toInt() + n, (k and 0xFFFFFFFFL).toInt() + n)] = v
        val vertCrease = d.vertCrease?.let { vc -> FloatArray(n * 2) { vc[it % n] } }
        val loose = IntArray(d.looseEdges.size * 2) { if (it < d.looseEdges.size) d.looseEdges[it] else d.looseEdges[it - d.looseEdges.size] + n }
        val dverts = d.dverts?.let { dv ->
            val out = Array(n * 2) { dv[it % n] }
            if (flag and MIR_VGROUP != 0) {
                val map = flipMap(d.groups)
                if (map.isNotEmpty()) for (i in 0 until n) {
                    if (correctOrder && doMerge && dest[n + i] != -1) out[i] = flipDvertMerged(out[i], map)
                    else if (!correctOrder && doMerge && dest[i] != -1) out[n + i] = flipDvertMerged(out[n + i], map)
                    else out[n + i] = flipDvert(out[n + i], map)
                }
            }
            out
        }
        val sharp = HashSet<Long>()
        for (k in d.sharpEdges) { sharp.add(k); sharp.add(BlendMeshData.key((k ushr 32).toInt() + n, (k and 0xFFFFFFFFL).toInt() + n)) }
        val result = BlendMeshData(
            co, fStart, fSize, corners, d.faceMat?.let { m -> IntArray(nf * 2) { m[it % nf] } }, d.faceSmooth?.let { m -> BooleanArray(nf * 2) { m[it % nf] } },
            uv, cornerColor, pointColor, creases, vertCrease, loose, dverts, d.groups
        ).carryNormals(d, sharp)
        return if (merged > 0) mergeVerts(result, dest) else result
    }

    fun mergeVerts(d: BlendMeshData, destIn: IntArray, mix: Boolean = false): BlendMeshData {
        val n = d.nV
        val dest = IntArray(n) { v -> val t = destIn[v]; if (t < 0 || t == v) -1 else t }
        val finalIdx = IntArray(n)
        var nn = 0
        for (v in 0 until n) finalIdx[v] = if (dest[v] == -1) nn++ else -1
        for (v in 0 until n) if (dest[v] != -1) finalIdx[v] = finalIdx[dest[v]]
        val co = FloatArray(nn * 3)
        for (v in 0 until n) if (dest[v] == -1) for (c in 0 until 3) co[finalIdx[v] * 3 + c] = d.co[v * 3 + c]
        val groupOf = if (mix) Array(nn) { ArrayList<Int>(1) }.also { g -> for (v in 0 until n) g[finalIdx[v]].add(v) } else null
        if (groupOf != null) for (t in 0 until nn) {
            val g = groupOf[t]
            if (g.size < 2) continue
            for (c in 0 until 3) { var acc = 0f; for (v in g) acc += d.co[v * 3 + c] * (1f / g.size); co[t * 3 + c] = acc }
        }
        val fStart = ArrayList<Int>()
        val fSize = ArrayList<Int>()
        val corners = ArrayList<Int>()
        val faceMat = d.faceMat?.let { ArrayList<Int>() }
        val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        val seen = HashSet<List<Long>>()
        for (f in 0 until d.nF) {
            val s = d.fStart[f]
            val m = d.fSize[f]
            val fv = IntArray(m) { finalIdx[d.corners[s + it]] }
            val touched = (0 until m).any { dest[d.corners[s + it]] != -1 || fv.count { x -> x == fv[it] } > 1 }
            val verts = ArrayList<Int>()
            val groups = ArrayList<ArrayList<Int>>()
            if (!touched) {
                for (k in 0 until m) { verts.add(fv[k]); groups.add(arrayListOf(s + k)) }
            } else {
                for (k in 0 until m) {
                    if (verts.isNotEmpty() && verts.last() == fv[k]) continue
                    verts.add(fv[k])
                }
                while (verts.size > 1 && verts.first() == verts.last()) verts.removeAt(verts.size - 1)
                for (v in verts) groups.add(ArrayList((0 until m).filter { fv[it] == v }.map { s + it }))
            }
            if (verts.size < 3) continue
            val edgeKey = (0 until verts.size).map { BlendMeshData.key(verts[it], verts[(it + 1) % verts.size]) }.sorted()
            if (touched && !seen.add(edgeKey)) continue
            if (!touched) seen.add(edgeKey)
            fStart.add(corners.size); fSize.add(verts.size); corners.addAll(verts)
            faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
            for (g in groups) {
                d.uv?.let { u ->
                    var x = 0f; var y = 0f
                    for (c in g) { x += u[c * 2]; y += u[c * 2 + 1] }
                    uv!!.add(x / g.size); uv.add(y / g.size)
                }
                d.cornerColor?.let { col -> cc!!.add(averageColor(g.map { col[it] })) }
            }
        }
        val creases = HashMap<Long, Float>()
        for ((k, v) in d.edgeCrease) {
            val a = finalIdx[(k ushr 32).toInt()]; val b = finalIdx[(k and 0xFFFFFFFFL).toInt()]
            if (a != b) creases.putIfAbsent(BlendMeshData.key(a, b), v)
        }
        val loose = ArrayList<Int>()
        val looseSeen = HashSet<Long>()
        for (i in 0 until d.looseEdges.size / 2) {
            val a = finalIdx[d.looseEdges[i * 2]]; val b = finalIdx[d.looseEdges[i * 2 + 1]]
            if (a != b && looseSeen.add(BlendMeshData.key(a, b))) { loose.add(a); loose.add(b) }
        }
        fun <T> keep(arr: Array<T>?, mk: (Int) -> Array<T>): Array<T>? = arr?.let { a -> mk(nn).also { o -> for (v in 0 until n) if (dest[v] == -1) o[finalIdx[v]] = a[v] } }
        val sharp = HashSet<Long>()
        for (k in d.sharpEdges) { val a = finalIdx[(k ushr 32).toInt()]; val b = finalIdx[(k and 0xFFFFFFFFL).toInt()]; if (a != b) sharp.add(BlendMeshData.key(a, b)) }
        return BlendMeshData(
            co, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(), faceMat?.toIntArray(), faceSmooth?.toBooleanArray(),
            uv?.toFloatArray(), cc?.toIntArray(),
            d.pointColor?.let { pc -> IntArray(nn).also { o -> for (v in 0 until n) if (dest[v] == -1) o[finalIdx[v]] = pc[v]; if (groupOf != null) for (t in 0 until nn) if (groupOf[t].size > 1) o[t] = averageColor(groupOf[t].map { pc[it] }) } },
            creases,
            d.vertCrease?.let { vc -> FloatArray(nn).also { o -> for (v in 0 until n) if (dest[v] == -1) o[finalIdx[v]] = vc[v]; if (groupOf != null) for (t in 0 until nn) if (groupOf[t].size > 1) o[t] = groupOf[t].map { vc[it] }.sum() / groupOf[t].size } },
            loose.toIntArray(),
            keep(d.dverts) { Array(it) { emptyList() } }?.also { o ->
                if (groupOf != null) for (t in 0 until nn) if (groupOf[t].size > 1) {
                    val acc = LinkedHashMap<Int, Float>()
                    for (v in groupOf[t]) for ((gi, w) in d.dverts!![v]) acc[gi] = (acc[gi] ?: 0f) + w / groupOf[t].size
                    o[t] = acc.entries.map { it.key to it.value }
                }
            },
            d.groups
        ).carryNormals(d, sharp)
    }

    class ArrayCap(val mesh: BlendMeshData, val groupRemap: IntArray?)

    private fun mapDoubles(map: IntArray, co: FloatArray, targetStart: Int, targetNum: Int, sourceStart: Int, sourceNum: Int, dist: Float) {
        val dist3 = (Math.sqrt(3.0).toFloat() + 0.00005f) * dist
        fun sum(v: Int) = co[v * 3] + co[v * 3 + 1] + co[v * 3 + 2]
        val target = (targetStart until targetStart + targetNum).sortedBy { sum(it) }.toIntArray()
        val source = (sourceStart until sourceStart + sourceNum).sortedBy { sum(it) }.toIntArray()
        var low = 0
        var completed = false
        fun lenSq(a: Int, b: Int): Float { val x = co[a * 3] - co[b * 3]; val y = co[a * 3 + 1] - co[b * 3 + 1]; val z = co[a * 3 + 2] - co[b * 3 + 2]; return x * x + y * y + z * z }
        for (sv in source) {
            var best = -1
            var bestSq = dist * dist
            if (map[sv] != -1) continue
            if (completed) { map[sv] = -1; continue }
            val ss = sum(sv)
            while (low < targetNum && sum(target[low]) < ss - dist3) low++
            if (low >= targetNum) { map[sv] = -1; completed = true; continue }
            var i = low
            while (i < targetNum && sum(target[i]) <= ss + dist3) {
                val tv = target[i]
                val dsq = lenSq(sv, tv)
                if (dsq <= bestSq) {
                    bestSq = dsq
                    best = tv
                    while (best != -1 && map[best] != -1 && map[best] != best) {
                        best = if (lenSq(sv, map[best]) <= dist * dist) map[best] else -1
                    }
                }
                i++
            }
            map[sv] = best
        }
    }

    fun array(
        d: BlendMeshData, countIn: Int, fitType: Int, lengthIn: Float, offsetType: Int, constOffset: FloatArray, relScale: FloatArray,
        objectOffset: FloatArray?, flags: Int, mergeDist: Float, uvOffset: FloatArray, startCap: ArrayCap?, endCap: ArrayCap?, curveLength: Float?
    ): BlendMeshData {
        val chunkV = d.nV
        val chunkF = d.nF
        val chunkC = d.corners.size
        var offset = GltfParser.IDENTITY.copyOf()
        if (offsetType and 1 != 0) for (k in 0 until 3) offset[12 + k] += constOffset[k]
        if (offsetType and 2 != 0 && chunkV > 0) {
            for (j in 2 downTo 0) {
                var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
                for (v in 0 until chunkV) { val x = d.co[v * 3 + j]; if (x < mn) mn = x; if (x > mx) mx = x }
                offset[12 + j] += relScale[j] * (mx - mn)
            }
        }
        if (objectOffset != null) offset = GltfParser.mul(offset, objectOffset)
        val hasScale = (0 until 3).any { c -> kotlin.math.sqrt(offset[c * 4] * offset[c * 4] + offset[c * 4 + 1] * offset[c * 4 + 1] + offset[c * 4 + 2] * offset[c * 4 + 2]) != 1.0f }
        var length = lengthIn
        if (fitType == 2 && curveLength != null) length = curveLength
        var count = countIn
        val maxVerts = 1L shl 26
        val capV = (startCap?.mesh?.nV ?: 0) + (endCap?.mesh?.nV ?: 0)
        if (fitType == 1 || fitType == 2) {
            val dist = kotlin.math.sqrt(offset[12] * offset[12] + offset[13] * offset[13] + offset[14] * offset[14])
            if (dist > 1e-6f) {
                count = ((length + 1e-6f) / dist + 1).toInt()
                if (count.toLong() * chunkV + capV > maxVerts) count = 1
            } else count = 1
        } else if (count.toLong() * chunkV + capV > maxVerts) count = 1
        count = maxOf(count, 1)
        val sV = startCap?.mesh?.nV ?: 0
        val eV = endCap?.mesh?.nV ?: 0
        val totalV = chunkV * count + sV + eV
        val co = FloatArray(totalV * 3)
        val useMerge = flags and 1 != 0
        val dmap = IntArray(totalV) { -1 }
        val fStart = ArrayList<Int>(); val fSize = ArrayList<Int>(); val corners = ArrayList<Int>()
        val faceMat = if (d.faceMat != null || startCap?.mesh?.faceMat != null || endCap?.mesh?.faceMat != null) ArrayList<Int>() else null
        val faceSmooth = if (d.faceSmooth != null || startCap?.mesh?.faceSmooth != null || endCap?.mesh?.faceSmooth != null) ArrayList<Boolean>() else null
        val uv = if (d.uv != null) ArrayList<Float>() else null
        val cc = if (d.cornerColor != null) ArrayList<Int>() else null
        val pc = if (d.pointColor != null) IntArray(totalV) else null
        val vc = if (d.vertCrease != null) FloatArray(totalV) else null
        val dv = if (d.dverts != null) Array(totalV) { emptyList<Pair<Int, Float>>() } else null
        val creases = HashMap<Long, Float>()
        val loose = ArrayList<Int>()
        val sharpAll = HashSet<Long>()
        val customAll = if (d.customShort != null) ArrayList<Short>() else null
        fun append(src: BlendMeshData, base: Int, m: FloatArray?, uvAdd: Float, uvAddV: Float, remap: IntArray?) {
            for (v in 0 until src.nV) {
                val p = if (m == null) floatArrayOf(src.co[v * 3], src.co[v * 3 + 1], src.co[v * 3 + 2]) else BlendAnimator.transformPoint(m, src.co[v * 3], src.co[v * 3 + 1], src.co[v * 3 + 2])
                p.copyInto(co, (base + v) * 3)
                if (pc != null) pc[base + v] = src.pointColor?.getOrNull(v) ?: 0
                if (vc != null) vc[base + v] = src.vertCrease?.getOrNull(v) ?: 0f
                if (dv != null) dv[base + v] = src.dverts?.getOrNull(v)?.let { l -> if (remap == null) l else l.mapNotNull { (g, w) -> remap.getOrNull(g)?.takeIf { it >= 0 }?.let { it to w } } } ?: emptyList()
            }
            for (f in 0 until src.nF) {
                fStart.add(corners.size); fSize.add(src.fSize[f])
                for (k in 0 until src.fSize[f]) {
                    val c = src.fStart[f] + k
                    corners.add(src.corners[c] + base)
                    if (uv != null) { uv.add((src.uv?.getOrNull(c * 2) ?: 0f) + uvAdd); uv.add((src.uv?.getOrNull(c * 2 + 1) ?: 0f) + uvAddV) }
                    cc?.add(src.cornerColor?.getOrNull(c) ?: 0)
                }
                faceMat?.add(src.faceMat?.getOrNull(f) ?: 0)
                faceSmooth?.add(src.faceSmooth?.getOrNull(f) ?: false)
            }
            for ((k, x) in src.edgeCrease) creases[BlendMeshData.key((k ushr 32).toInt() + base, (k and 0xFFFFFFFFL).toInt() + base)] = x
            for (k in src.sharpEdges) sharpAll.add(BlendMeshData.key((k ushr 32).toInt() + base, (k and 0xFFFFFFFFL).toInt() + base))
            val cs = src.customShort?.takeIf { it.size == src.corners.size * 2 }
            if (customAll != null) for (c in 0 until src.corners.size * 2) customAll.add(cs?.get(c) ?: 0)
            for (e in src.looseEdges) loose.add(e + base)
        }
        append(d, 0, null, 0f, 0f, null)
        var current = GltfParser.IDENTITY.copyOf()
        for (c in 1 until count) {
            current = GltfParser.mul(current, offset)
            append(d, c * chunkV, current, uvOffset[0] * c, uvOffset[1] * c, null)
            if (useMerge) {
                if (!hasScale && c >= 2) {
                    for (k in 0 until chunkV) {
                        val thisI = c * chunkV + k
                        var target = dmap[(c - 1) * chunkV + k]
                        if (target != -1) {
                            target += chunkV
                            while (target != -1 && dmap[target] != -1 && dmap[target] != target) {
                                val t2 = dmap[target]
                                val x = co[thisI * 3] - co[t2 * 3]; val y = co[thisI * 3 + 1] - co[t2 * 3 + 1]; val z = co[thisI * 3 + 2] - co[t2 * 3 + 2]
                                target = if (x * x + y * y + z * z <= mergeDist * mergeDist) t2 else -1
                            }
                        }
                        dmap[thisI] = target
                    }
                } else mapDoubles(dmap, co, (c - 1) * chunkV, chunkV, c * chunkV, chunkV, mergeDist)
            }
        }
        val lastStart = (count - 1) * chunkV
        if (useMerge && flags and 2 != 0 && count > 1) mapDoubles(dmap, co, lastStart, chunkV, 0, chunkV, mergeDist)
        if (startCap != null) {
            val st = chunkV * count
            append(startCap.mesh, st, BlendAnimator.invert(offset), 0f, 0f, startCap.groupRemap)
            if (useMerge) mapDoubles(dmap, co, 0, chunkV, st, sV, mergeDist)
        }
        if (endCap != null) {
            val st = chunkV * count + sV
            append(endCap.mesh, st, GltfParser.mul(current, offset), 0f, 0f, endCap.groupRemap)
            if (useMerge) mapDoubles(dmap, co, lastStart, chunkV, st, eV, mergeDist)
        }
        val result = BlendMeshData(
            co, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(), faceMat?.toIntArray(), faceSmooth?.toBooleanArray(),
            uv?.toFloatArray(), cc?.toIntArray(), pc, creases, vc, loose.toIntArray(), dv, d.groups
        ).carryNormals(d, sharpAll)
        if (customAll != null) result.customShort = ShortArray(customAll.size) { customAll[it] }
        if (!useMerge) return result
        var tot = 0
        for (i in 0 until totalV) {
            var ni = dmap[i]
            if (ni != -1) {
                while (dmap[ni] != -1 && dmap[ni] != ni) ni = dmap[ni]
                if (i == ni) dmap[i] = -1 else { dmap[i] = ni; tot++ }
            }
        }
        return if (tot > 0) mergeVerts(result, dmap) else result
    }

    class SolidifyParams(
        val offset: Float, val offsetFac: Float, val offsetFacVg: Float, val offsetClamp: Float, val flag: Int,
        val matOfs: Int, val matOfsRim: Int, val totcol: Int, val creaseInner: Float, val creaseOuter: Float, val creaseRim: Float,
        val groupWeights: FloatArray?, val shellGroup: Int, val rimGroup: Int
    )

    private fun safeAsin(x: Float): Float = if (x <= -1f) (-Math.PI / 2).toFloat() else if (x >= 1f) (Math.PI / 2).toFloat() else kotlin.math.asin(x)

    private fun angleNormalized(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        if (dot >= 0f) {
            val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
            return 2.0f * safeAsin(kotlin.math.sqrt(x * x + y * y + z * z) / 2.0f)
        }
        val x = a[0] + b[0]; val y = a[1] + b[1]; val z = a[2] + b[2]
        return Math.PI.toFloat() - 2.0f * safeAsin(kotlin.math.sqrt(x * x + y * y + z * z) / 2.0f)
    }

    private fun normalizeInPlace(v: FloatArray): Float {
        val d = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
        if (d > 1.0e-35f) {
            val l = kotlin.math.sqrt(d)
            val inv = 1.0f / l
            v[0] *= inv; v[1] *= inv; v[2] *= inv
            return l
        }
        v[0] = 0f; v[1] = 0f; v[2] = 0f
        return 0f
    }

    private fun angleSignedOnAxis(v1: FloatArray, v2: FloatArray, axis: FloatArray): Float {
        fun proj(p: FloatArray): FloatArray { val m = p[0] * axis[0] + p[1] * axis[1] + p[2] * axis[2]; return floatArrayOf(p[0] - axis[0] * m, p[1] - axis[1] * m, p[2] - axis[2] * m) }
        val a = proj(v1); val b = proj(v2)
        val na = a.copyOf(); normalizeInPlace(na)
        val nb = b.copyOf(); normalizeInPlace(nb)
        var angle = angleNormalized(na, nb)
        val t = floatArrayOf(b[1] * a[2] - b[2] * a[1], b[2] * a[0] - b[0] * a[2], b[0] * a[1] - b[1] * a[0])
        if (t[0] * axis[0] + t[1] * axis[1] + t[2] * axis[2] < 0f) angle = (Math.PI * 2.0).toFloat() - angle
        return angle
    }

    fun solidify(d: BlendMeshData, p: SolidifyParams): BlendMeshData {
        val nV = d.nV
        val nF = d.nF
        val nC = d.corners.size
        val topo = MeshTopology(d.corners, d.fStart, d.fSize, IntArray(0))
        val faceNo = FloatArray(nF * 3)
        val tmp = FloatArray(3)
        for (f in 0 until nF) { topo.faceNormal(d.co, d.fStart[f], d.fSize[f], tmp); tmp.copyInto(faceNo, f * 3) }
        fun fn(f: Int) = floatArrayOf(faceNo[f * 3], faceNo[f * 3 + 1], faceNo[f * 3 + 2])
        val vertNo = topo.vertexNormals(d.co)
        val edgeIndex = LinkedHashMap<Long, Int>()
        val edgeA = ArrayList<Int>(); val edgeB = ArrayList<Int>()
        fun edgeOf(a: Int, b: Int): Int = edgeIndex.getOrPut(BlendMeshData.key(a, b)) { edgeA.add(minOf(a, b)); edgeB.add(maxOf(a, b)); edgeA.size - 1 }
        val cornerEdge = IntArray(nC)
        for (f in 0 until nF) { val s = d.fStart[f]; val m = d.fSize[f]; for (k in 0 until m) cornerEdge[s + k] = edgeOf(d.corners[s + k], d.corners[s + (k + 1) % m]) }
        for (i in 0 until d.looseEdges.size / 2) edgeOf(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1])
        val nE = edgeA.size
        val ofsOrig = -(((-p.offsetFac + 1.0f) * 0.5f) * p.offset)
        val ofsNew = p.offset + ofsOrig
        val vgInv = 1.0f - p.offsetFacVg
        val doFlip = p.flag and (1 shl 5) != 0
        val doClamp = p.offsetClamp != 0.0f
        val doAngleClamp = doClamp && p.flag and (1 shl 7) != 0
        val doRim = p.flag and 1 != 0
        val doShell = !(doRim && p.flag and (1 shl 6) != 0)
        val even = p.flag and (1 shl 1) != 0
        val hq = p.flag and (1 shl 2) != 0
        val matMax = if (p.totcol > 1) p.totcol - 1 else 0
        val matOfs = if (matMax != 0) p.matOfs else 0
        val matOfsRim = if (matMax != 0) p.matOfsRim else 0
        val unused = -1; val pair = -2
        val edgeUsers = IntArray(nE) { unused }
        val edgeOrder = IntArray(nE)
        val rimEdges = ArrayList<Int>()
        val oldVert = IntArray(nV) { -1 }
        val newVertArr = ArrayList<Int>()
        if (doRim) {
            for (f in 0 until nF) {
                val s = d.fStart[f]; val m = d.fSize[f]
                for (j in 0 until m) {
                    val prevC = s + (j + m - 1) % m
                    val e = cornerEdge[prevC]
                    if (edgeUsers[e] == unused) {
                        val prevV = d.corners[prevC]; val v = d.corners[s + j]
                        edgeUsers[e] = if ((prevV > v) == (edgeA[e] < edgeB[e])) f else f + nF
                        edgeOrder[e] = j
                    } else edgeUsers[e] = pair
                }
            }
            val tag = BooleanArray(nV)
            for (e in 0 until nE) if (edgeUsers[e] != unused && edgeUsers[e] != pair) { tag[edgeA[e]] = true; tag[edgeB[e]] = true; rimEdges.add(e) }
            for (v in 0 until nV) if (tag[v]) { oldVert[v] = newVertArr.size; newVertArr.add(v) }
        }
        val rimVerts = newVertArr.size
        val newVerts = if (doShell) 0 else rimVerts
        val nonManifoldEdge = BooleanArray(nE)
        var vertNors: FloatArray? = null
        if (hq) {
            val p1 = IntArray(nE) { Int.MIN_VALUE }; val p2 = IntArray(nE) { -1 }
            for (f in 0 until nF) for (k in 0 until d.fSize[f]) {
                val e = cornerEdge[d.fStart[f] + k]
                if (p1[e] == Int.MIN_VALUE) { p1[e] = f; p2[e] = -1 }
                else if (p1[e] != -1 && p2[e] == -1) p2[e] = f
                else { p1[e] = -1; p2[e] = -1; nonManifoldEdge[e] = true }
            }
            val acc = FloatArray(nV * 3)
            for (e in 0 until nE) {
                if (p1[e] == Int.MIN_VALUE || p1[e] == -1) continue
                val en = if (p2[e] != -1) {
                    val a = fn(p1[e]); val b = fn(p2[e])
                    val r = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
                    val ang = ((2.0 / Math.PI).toFloat() * 2.0f) * kotlin.math.acos(normalizeInPlace(r) / 2.0f)
                    floatArrayOf(r[0] * ang, r[1] * ang, r[2] * ang)
                } else fn(p1[e])
                for (k in 0 until 3) { acc[edgeA[e] * 3 + k] += en[k]; acc[edgeB[e] * 3 + k] += en[k] }
            }
            for (v in 0 until nV) {
                val n = floatArrayOf(acc[v * 3], acc[v * 3 + 1], acc[v * 3 + 2])
                if (normalizeInPlace(n) == 0.0f) for (k in 0 until 3) n[k] = vertNo[v * 3 + k]
                n.copyInto(acc, v * 3)
            }
            vertNors = acc
        }
        val stride = if (doShell) 2 else 1
        val totalV = nV * stride + newVerts
        val co = FloatArray(totalV * 3)
        d.co.copyInto(co)
        if (doShell) d.co.copyInto(co, nV * 3) else for (i in 0 until rimVerts) for (k in 0 until 3) co[(nV + i) * 3 + k] = d.co[newVertArr[i] * 3 + k]
        val weights = p.groupWeights
        fun vgFactor(i: Int) = if (weights == null) 1f else p.offsetFacVg + weights[i] * vgInv
        fun applyOffset(ofs: Float, test: Boolean, scale: (Int) -> Float, nor: FloatArray) {
            val useOrig = (((ofsNew >= ofsOrig) == doFlip) == test)
            val iEnd: Int
            val align: Boolean
            val base: Int
            if (useOrig) { iEnd = nV; align = true; base = 0 } else if (doShell) { iEnd = nV; align = true; base = nV } else { iEnd = newVerts; align = false; base = nV }
            for (io in 0 until iEnd) {
                val i = if (align) io else newVertArr[io]
                val f = scale(i)
                for (k in 0 until 3) co[(base + io) * 3 + k] += nor[i * 3 + k] * (ofs * f)
            }
        }
        val edgeLensSq = { FloatArray(nV) { Float.MAX_VALUE }.also { vl -> for (e in 0 until nE) { val a = edgeA[e]; val b = edgeB[e]; var l = 0f; for (k in 0 until 3) { val x = d.co[a * 3 + k] - d.co[b * 3 + k]; l += x * x }; vl[a] = minOf(vl[a], l); vl[b] = minOf(vl[b], l) } } }
        fun edgePairs(): Array<IntArray> {
            val pairs = Array(nE) { intArrayOf(unused, unused) }
            for (f in 0 until nF) {
                val s = d.fStart[f]; val m = d.fSize[f]
                for (j in 0 until m) {
                    val prevC = s + (j + m - 1) % m
                    val e = cornerEdge[prevC]
                    val flip = if ((d.corners[prevC] > d.corners[s + j]) == (edgeA[e] < edgeB[e])) 1 else 0
                    if (pairs[e][flip] == unused) pairs[e][flip] = f else { pairs[e][0] = pair; pairs[e][1] = pair }
                }
            }
            return pairs
        }
        if (!even) {
            val clampOffset = kotlin.math.abs(p.offset) * p.offsetClamp
            val vertLens = if (doClamp) edgeLensSq() else null
            var vertAngs: FloatArray? = null
            if (doAngleClamp) {
                vertAngs = FloatArray(nV) { (0.5 * Math.PI).toFloat() }
                val pairs = edgePairs()
                for (e in 0 until nE) {
                    val pr = pairs[e]
                    if (pr[0] < 0 || pr[1] < 0) continue
                    val ev = floatArrayOf(d.co[edgeA[e] * 3] - d.co[edgeB[e] * 3], d.co[edgeA[e] * 3 + 1] - d.co[edgeB[e] * 3 + 1], d.co[edgeA[e] * 3 + 2] - d.co[edgeB[e] * 3 + 2])
                    normalizeInPlace(ev)
                    val ang = angleSignedOnAxis(fn(pr[0]), fn(pr[1]), ev)
                    vertAngs[edgeA[e]] = maxOf(vertAngs[edgeA[e]], ang); vertAngs[edgeB[e]] = maxOf(vertAngs[edgeB[e]], ang)
                }
            }
            val nor = vertNors ?: vertNo
            for ((ofs, test) in listOf(ofsNew to false, ofsOrig to true)) {
                if (ofs == 0.0f) continue
                val iEndOrigIdx = !test
                applyOffset(1f, test, { i ->
                    var v = if (weights != null) vgFactor(i) * ofs else ofs
                    if (doClamp && clampOffset > 1.1920929E-7f) {
                        if (weights == null) v = ofs
                        if (doAngleClamp) {
                            val a = vertAngs!![i]
                            val cos = kotlin.math.cos(if (iEndOrigIdx) (((2 * Math.PI).toFloat() - a) * 0.5f) else (a * 0.5f))
                            if (cos > 0) { val mo = kotlin.math.sqrt(vertLens!![i]) * 0.5f / cos; if (mo < clampOffset * 0.5f) v *= mo / clampOffset * 2 }
                        } else if (vertLens!![i] < clampOffset * clampOffset) v *= kotlin.math.sqrt(vertLens[i]) / clampOffset
                    }
                    v
                }, nor)
            }
        } else {
            val nors = vertNors ?: vertNo.copyOf()
            val vAng = FloatArray(nV)
            val vAcc = FloatArray(nV)
            for (f in 0 until nF) {
                val s = d.fStart[f]; val m = d.fSize[f]
                fun pos(k: Int) = floatArrayOf(d.co[d.corners[s + k] * 3], d.co[d.corners[s + k] * 3 + 1], d.co[d.corners[s + k] * 3 + 2])
                var iCurr = m - 1
                var iNext = 0
                val a0 = pos(iCurr - 1); val a1 = pos(iCurr)
                var nPrev = floatArrayOf(a0[0] - a1[0], a0[1] - a1[1], a0[2] - a1[2]); normalizeInPlace(nPrev)
                val fno = fn(f)
                while (iNext < m) {
                    val c = pos(iCurr); val nx = pos(iNext)
                    val nNext = floatArrayOf(c[0] - nx[0], c[1] - nx[1], c[2] - nx[2]); normalizeInPlace(nNext)
                    val angle = maxOf(angleNormalized(nPrev, nNext), 1.1920929E-7f)
                    val v = d.corners[s + iCurr]
                    vAcc[v] += angle
                    if (!hq || (!nonManifoldEdge[cornerEdge[s + iCurr]] && !nonManifoldEdge[cornerEdge[s + iNext]])) {
                        val cosA = kotlin.math.abs(nors[v * 3] * fno[0] + nors[v * 3 + 1] * fno[1] + nors[v * 3 + 2] * fno[2])
                        vAng[v] += (if (cosA < 1e-8f) 1.0f else 1.0f / cosA) * angle
                    } else vAng[v] += angle
                    nPrev = nNext
                    iCurr = iNext
                    iNext++
                }
            }
            if (weights != null) for (i in 0 until nV) vAng[i] *= vgFactor(i)
            var vertAngs: FloatArray? = null
            if (doAngleClamp) {
                vertAngs = FloatArray(nV) { (0.5 * Math.PI).toFloat() }
                val pairs = edgePairs()
                for (e in 0 until nE) {
                    val pr = pairs[e]
                    if (pr[0] < 0 || pr[1] < 0) continue
                    val ang = Math.PI.toFloat() - angleNormalized(fn(pr[0]), fn(pr[1]))
                    vertAngs[edgeA[e]] = maxOf(vertAngs[edgeA[e]], ang); vertAngs[edgeB[e]] = maxOf(vertAngs[edgeB[e]], ang)
                }
            }
            if (doClamp) {
                val clampFac = 1 + (if (doAngleClamp) kotlin.math.abs(p.offsetFac) else 0f)
                val off = kotlin.math.abs(p.offset) * p.offsetClamp * clampFac
                if (off > 1.1920929E-7f) {
                    val vl = edgeLensSq()
                    if (doAngleClamp) {
                        for (i in 0 until nV) {
                            val cos = kotlin.math.cos(vertAngs!![i] * 0.5f)
                            if (cos > 0) { val mo = kotlin.math.sqrt(vl[i]) * 0.5f / cos; if (mo < off * 0.5f) vAng[i] *= mo / off * 2 }
                        }
                    } else for (i in 0 until nV) if (vl[i] < off * off) vAng[i] *= kotlin.math.sqrt(vl[i]) / off
                }
            }
            for ((ofs, test) in listOf(ofsNew to false, ofsOrig to true)) {
                if (ofs == 0.0f) continue
                applyOffset(ofs, test, { i -> if (vAcc[i] != 0f) vAng[i] / vAcc[i] else 0f }, nors)
            }
        }
        val fStart = ArrayList<Int>(); val fSize = ArrayList<Int>(); val corners = ArrayList<Int>()
        val faceMat = ArrayList<Int>(); val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        fun addCorner(src: Int) { d.uv?.let { u -> uv!!.add(u[src * 2]); uv.add(u[src * 2 + 1]) }; d.cornerColor?.let { cc!!.add(it[src]) } }
        for (f in 0 until nF) {
            fStart.add(corners.size); fSize.add(d.fSize[f])
            for (k in 0 until d.fSize[f]) { corners.add(d.corners[d.fStart[f] + k]); addCorner(d.fStart[f] + k) }
            faceMat.add(d.faceMat?.getOrNull(f) ?: 0); faceSmooth?.add(d.faceSmooth!![f])
        }
        if (doShell) for (f in 0 until nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            fStart.add(corners.size); fSize.add(m)
            for (k in 0 until m) { val src = s + (m - k) % m; corners.add(d.corners[src] + nV); addCorner(src) }
            faceMat.add(if (matOfs != 0) ((d.faceMat?.getOrNull(f) ?: 0) + matOfs).coerceIn(0, matMax) else d.faceMat?.getOrNull(f) ?: 0)
            faceSmooth?.add(d.faceSmooth!![f])
        }
        val creases = HashMap<Long, Float>()
        for ((k, v) in d.edgeCrease) creases[k] = v
        if (doShell) for ((k, v) in d.edgeCrease) creases[BlendMeshData.key((k ushr 32).toInt() + nV, (k and 0xFFFFFFFFL).toInt() + nV)] = v
        if (doRim) {
            for ((i, e) in rimEdges.withIndex()) {
                var pidx = edgeUsers[e]
                val flip = pidx >= nF
                if (flip) pidx -= nF
                val a = edgeA[e]; val b = edgeB[e]
                fun shellV(v: Int) = (if (doShell) v else oldVert[v]) + nV
                val vs = if (!flip) intArrayOf(a, b, shellV(b), shellV(a)) else intArrayOf(b, a, shellV(a), shellV(b))
                val m = d.fSize[pidx]
                val k1 = d.fStart[pidx] + ((edgeOrder[e] - 1) + m) % m
                val k2 = d.fStart[pidx] + edgeOrder[e]
                fStart.add(corners.size); fSize.add(4)
                for (v in vs) corners.add(v)
                addCorner(k2); addCorner(k1); addCorner(k1); addCorner(k2)
                faceMat.add(if (matOfsRim != 0) ((d.faceMat?.getOrNull(pidx) ?: 0) + matOfsRim).coerceIn(0, matMax) else d.faceMat?.getOrNull(pidx) ?: 0)
                faceSmooth?.add(d.faceSmooth!![pidx])
                if (p.creaseOuter != 0f) { val kk = BlendMeshData.key(a, b); creases[kk] = minOf((creases[kk] ?: 0f) + p.creaseOuter, 1f) }
                if (p.creaseInner != 0f) { val kk = BlendMeshData.key(shellV(a), shellV(b)); creases[kk] = minOf((creases[kk] ?: 0f) + p.creaseInner, 1f) }
            }
            if (p.creaseRim != 0f) for (i in 0 until rimVerts) creases[BlendMeshData.key(newVertArr[i], (if (doShell) newVertArr[i] else i) + nV)] = p.creaseRim
        }
        val dverts = d.dverts?.let { dv ->
            val out = Array(totalV) { v -> if (v < nV) dv[v] else if (doShell) dv[v - nV] else dv[newVertArr[v - nV]] }
            fun ensure(v: Int, g: Int) { out[v] = out[v].filter { it.first != g } + (g to 1.0f) }
            if (p.rimGroup >= 0) for (i in 0 until rimVerts) { ensure(newVertArr[i], p.rimGroup); ensure((if (doShell) newVertArr[i] else i) + nV, p.rimGroup) }
            if (p.shellGroup >= 0) for (v in nV until totalV) ensure(v, p.shellGroup)
            out
        }
        val loose = ArrayList<Int>()
        for (e in d.looseEdges) loose.add(e)
        if (doShell) for (e in d.looseEdges) loose.add(e + nV)
        return BlendMeshData(
            co, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(), faceMat.toIntArray(), faceSmooth?.toBooleanArray(),
            uv?.toFloatArray(), cc?.toIntArray(),
            d.pointColor?.let { pc -> IntArray(totalV) { v -> if (v < nV) pc[v] else if (doShell) pc[v - nV] else pc[newVertArr[v - nV]] } },
            creases,
            d.vertCrease?.let { vc -> FloatArray(totalV) { v -> if (v < nV) vc[v] else if (doShell) vc[v - nV] else vc[newVertArr[v - nV]] } },
            loose.toIntArray(), dverts, d.groups
        ).carryNormals(d, HashSet<Long>().also { set ->
            for (k in d.sharpEdges) { set.add(k); if (doShell) set.add(BlendMeshData.key((k ushr 32).toInt() + nV, (k and 0xFFFFFFFFL).toInt() + nV)) }
        })
    }

    fun smoothByAngle(d: BlendMeshData, angle: Float, ignoreSharpness: Boolean): BlendMeshData {
        val fn = BlendNormals.faceNormals(d.co, d.corners, d.fStart, d.fSize)
        val edgeFaces = HashMap<Long, MutableList<Int>>()
        for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
            val key = BlendMeshData.key(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]])
            edgeFaces.getOrPut(key) { ArrayList(2) }.add(f)
        }
        val smooth = d.faceSmooth
        val sharp = HashSet<Long>()
        for ((key, fl) in edgeFaces) {
            val wasSharp = key in d.sharpEdges
            if (wasSharp && !ignoreSharpness) { sharp.add(key); continue }
            val ang = if (fl.size == 2) angleNormalized(
                floatArrayOf(fn[fl[0] * 3], fn[fl[0] * 3 + 1], fn[fl[0] * 3 + 2]),
                floatArrayOf(fn[fl[1] * 3], fn[fl[1] * 3 + 1], fn[fl[1] * 3 + 2])
            ) else 0f
            val faceSmooth = ignoreSharpness || fl.all { smooth?.getOrNull(it) == true }
            if (!(ang <= angle && faceSmooth)) sharp.add(key)
        }
        for (k in d.sharpEdges) if (k !in edgeFaces && !ignoreSharpness) sharp.add(k)
        val r = BlendMeshData(d.co, d.fStart, d.fSize, d.corners, d.faceMat, BooleanArray(d.nF) { true }, d.uv, d.cornerColor, d.pointColor, d.edgeCrease, d.vertCrease, d.looseEdges, d.dverts, d.groups)
        r.carryNormals(d, sharp)
        r.customShort = d.customShort
        r.customFloat = d.customFloat
        r.legacyNormals = false
        return r
    }

    fun edgeSplit(d: BlendMeshData, angle: Float, flags: Int): BlendMeshData {
        val threshold = kotlin.math.cos(angle + 0.000000175f)
        val doAngle = flags and 2 != 0 && angle < Math.PI.toFloat()
        val doAll = doAngle && angle < 1.1920929E-7f
        val fn = BlendNormals.faceNormals(d.co, d.corners, d.fStart, d.fSize)
        val edgeFaces = LinkedHashMap<Long, MutableList<Int>>()
        for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
            val key = BlendMeshData.key(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]])
            edgeFaces.getOrPut(key) { ArrayList(2) }.add(f)
        }
        val tagged = HashSet<Long>()
        if (doAngle) for ((key, fl) in edgeFaces) {
            if (fl.size < 2) continue
            if (fl.size > 2 || doAll || fn[fl[0] * 3] * fn[fl[1] * 3] + fn[fl[0] * 3 + 1] * fn[fl[1] * 3 + 1] + fn[fl[0] * 3 + 2] * fn[fl[1] * 3 + 2] < threshold) tagged.add(key)
        }
        if (flags and 4 != 0) for (k in d.sharpEdges) if (k in edgeFaces) tagged.add(k)
        val nV = d.nV
        val parent = IntArray(d.corners.size) { it }
        fun find(x: Int): Int { var y = x; while (parent[y] != y) { parent[y] = parent[parent[y]]; y = parent[y] }; return y }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        fun cornerOf(f: Int, v: Int): Int { val st = d.fStart[f]; for (k in 0 until d.fSize[f]) if (d.corners[st + k] == v) return st + k; return -1 }
        for ((key, fl) in edgeFaces) {
            if (key in tagged || fl.size != 2) continue
            val a = (key ushr 32).toInt(); val b = (key and 0xFFFFFFFFL).toInt()
            val ca0 = cornerOf(fl[0], a); val ca1 = cornerOf(fl[1], a)
            val cb0 = cornerOf(fl[0], b); val cb1 = cornerOf(fl[1], b)
            if (ca0 >= 0 && ca1 >= 0) union(ca0, ca1)
            if (cb0 >= 0 && cb1 >= 0) union(cb0, cb1)
        }
        val newIndex = HashMap<Int, Int>()
        val firstRoot = IntArray(nV) { -1 }
        val src = ArrayList<Int>()
        for (v in 0 until nV) src.add(v)
        val corners = IntArray(d.corners.size)
        for (c in d.corners.indices) {
            val v = d.corners[c]
            val r = find(c)
            if (firstRoot[v] == -1) firstRoot[v] = r
            corners[c] = if (r == firstRoot[v]) v else newIndex.getOrPut(r) { src.add(v); src.size - 1 }
        }
        val total = src.size
        val co = FloatArray(total * 3) { d.co[src[it / 3] * 3 + it % 3] }
        val creases = HashMap<Long, Float>()
        if (d.edgeCrease.isNotEmpty()) for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
            val c0 = d.fStart[f] + k; val c1 = d.fStart[f] + (k + 1) % d.fSize[f]
            d.edgeCrease[BlendMeshData.key(d.corners[c0], d.corners[c1])]?.let { creases[BlendMeshData.key(corners[c0], corners[c1])] = it }
        }
        for ((k, v) in d.edgeCrease) if (!edgeFaces.containsKey(k)) creases[k] = v
        val r = BlendMeshData(
            co, d.fStart, d.fSize, corners, d.faceMat, d.faceSmooth, d.uv, d.cornerColor,
            d.pointColor?.let { pc -> IntArray(total) { pc[src[it]] } }, creases,
            d.vertCrease?.let { vc -> FloatArray(total) { vc[src[it]] } }, d.looseEdges,
            d.dverts?.let { dv -> Array(total) { dv[src[it]] } }, d.groups
        )
        val sharp = HashSet<Long>()
        if (!d.legacyNormals && d.sharpEdges.isNotEmpty()) for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
            val c0 = d.fStart[f] + k; val c1 = d.fStart[f] + (k + 1) % d.fSize[f]
            if (BlendMeshData.key(d.corners[c0], d.corners[c1]) in d.sharpEdges) sharp.add(BlendMeshData.key(corners[c0], corners[c1]))
        }
        r.carryNormals(d, sharp)
        r.legacyNormals = false
        r.autoSmooth = if (d.legacyNormals) null else d.autoSmooth
        r.customShort = d.customShort
        r.customFloat = d.customFloat
        return r
    }

    fun weld(d: BlendMeshData, dist: Float, mode: Int, flag: Int, groupName: String): BlendMeshData {
        val n = d.nV
        val gi = d.groups.indexOf(groupName)
        val invert = flag and 1 != 0
        val sel = if (gi >= 0 && d.dverts != null) BooleanArray(n) { v -> ((d.dverts!![v].firstOrNull { it.first == gi }?.second ?: 0f) > 0f) != invert } else null
        val dest = IntArray(n) { -1 }
        var kill = 0
        if (mode == 0) {
            val distSq = dist * dist
            val idx = (0 until n).filter { sel == null || sel[it] }
            val cellSize = maxOf(dist, 1e-6f) * 2f
            val grid = HashMap<Long, ArrayList<Int>>()
            fun cell(x: Float) = Math.floor((x / cellSize).toDouble()).toLong()
            fun gk(a: Long, b: Long, c: Long) = (a * 73856093L) xor (b * 19349663L) xor (c * 83492791L)
            for (v in idx) grid.getOrPut(gk(cell(d.co[v * 3]), cell(d.co[v * 3 + 1]), cell(d.co[v * 3 + 2]))) { ArrayList() }.add(v)
            for (v in idx) {
                if (dest[v] != -1 && dest[v] != v) continue
                var found = false
                val cx = cell(d.co[v * 3]); val cy = cell(d.co[v * 3 + 1]); val cz = cell(d.co[v * 3 + 2])
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    val list = grid[gk(cx + dx, cy + dy, cz + dz)] ?: continue
                    for (u in list) {
                        if (u == v || dest[u] != -1) continue
                        val x = d.co[u * 3] - d.co[v * 3]; val y = d.co[u * 3 + 1] - d.co[v * 3 + 1]; val z = d.co[u * 3 + 2] - d.co[v * 3 + 2]
                        if (x * x + y * y + z * z <= distSq) { dest[u] = v; found = true; kill++ }
                    }
                }
                if (found) dest[v] = v
            }
        } else {
            val onlyLoose = flag and 2 != 0
            val edges = ArrayList<IntArray>()
            val fe = d.fileEdges
            val faceKeys = HashSet<Long>()
            for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) faceKeys.add(BlendMeshData.key(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]))
            if (fe != null) for (e in 0 until fe.size / 2) edges.add(intArrayOf(fe[e * 2], fe[e * 2 + 1]))
            else {
                val seen = HashSet<Long>()
                for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) {
                    val a = d.corners[d.fStart[f] + k]; val b = d.corners[d.fStart[f] + (k + 1) % d.fSize[f]]
                    if (seen.add(BlendMeshData.key(a, b))) edges.add(intArrayOf(a, b))
                }
                for (i in 0 until d.looseEdges.size / 2) if (seen.add(BlendMeshData.key(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))) edges.add(intArrayOf(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))
            }
            val map = IntArray(n) { it }
            val cco = d.co.copyOf()
            val merged = IntArray(n)
            val distSq = dist * dist
            for (e in edges) {
                if (onlyLoose && BlendMeshData.key(e[0], e[1]) in faceKeys) continue
                var v1 = e[0]; var v2 = e[1]
                while (v1 != map[v1]) v1 = map[v1]
                while (v2 != map[v2]) v2 = map[v2]
                if (v1 == v2) continue
                if (sel != null && (!sel[v1] || !sel[v2])) continue
                if (v1 > v2) { val t = v1; v1 = v2; v2 = t }
                val dx = cco[v2 * 3] - cco[v1 * 3]; val dy = cco[v2 * 3 + 1] - cco[v1 * 3 + 1]; val dz = cco[v2 * 3 + 2] - cco[v1 * 3 + 2]
                if (dx * dx + dy * dy + dz * dz <= distSq) {
                    val infl = (merged[v2] + 1) / (merged[v1] + merged[v2] + 2).toFloat()
                    cco[v1 * 3] += dx * infl; cco[v1 * 3 + 1] += dy * infl; cco[v1 * 3 + 2] += dz * infl
                    merged[v1] += merged[v2] + 1
                    map[v2] = v1
                    kill++
                }
            }
            for (i in 0 until n) {
                if (i == map[i]) continue
                var v = i
                while (v != map[v]) v = map[v]
                dest[i] = v
                dest[v] = v
            }
        }
        if (kill == 0) return d
        return mergeVerts(d, dest, true)
    }

    fun mask(d: BlendMeshData, keep: BooleanArray, weights: FloatArray?, threshold: Float, smooth: Boolean): BlendMeshData {
        val n = d.nV
        val vmap = IntArray(n) { -1 }
        var nn = 0
        for (v in 0 until n) if (keep[v]) vmap[v] = nn++
        val co = ArrayList<Float>()
        for (v in 0 until n) if (keep[v]) for (k in 0 until 3) co.add(d.co[v * 3 + k])
        val pc = d.pointColor?.let { p -> ArrayList<Int>().also { l -> for (v in 0 until n) if (keep[v]) l.add(p[v]) } }
        val vc = d.vertCrease?.let { p -> ArrayList<Float>().also { l -> for (v in 0 until n) if (keep[v]) l.add(p[v]) } }
        val dv = d.dverts?.let { p -> ArrayList<List<Pair<Int, Float>>>().also { l -> for (v in 0 until n) if (keep[v]) l.add(p[v]) } }
        val cutVert = HashMap<Long, Int>()
        fun fac(a: Int, b: Int): Float { val wa = weights!![a]; val wb = weights[b]; return (threshold - wa) / (wb - wa) }
        fun cut(a: Int, b: Int): Int {
            val key = BlendMeshData.key(a, b)
            cutVert[key]?.let { return it }
            val lo = minOf(a, b); val hi = maxOf(a, b)
            val t = fac(lo, hi)
            for (k in 0 until 3) co.add(d.co[lo * 3 + k] * (1f - t) + d.co[hi * 3 + k] * t)
            pc?.add(lerpColor(d.pointColor!![lo], d.pointColor!![hi], t))
            vc?.add(d.vertCrease!![lo] * (1f - t) + d.vertCrease!![hi] * t)
            if (dv != null) {
                val acc = LinkedHashMap<Int, Float>()
                for ((g, w) in d.dverts!![lo]) acc[g] = (acc[g] ?: 0f) + w * (1f - t)
                for ((g, w) in d.dverts!![hi]) acc[g] = (acc[g] ?: 0f) + w * t
                dv.add(acc.entries.map { it.key to it.value })
            }
            val id = co.size / 3 - 1
            cutVert[key] = id
            return id
        }
        val fStart = ArrayList<Int>(); val fSize = ArrayList<Int>(); val corners = ArrayList<Int>()
        val faceMat = d.faceMat?.let { ArrayList<Int>() }
        val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        fun cornerData(c0: Int, c1: Int, t: Float) {
            d.uv?.let { u -> uv!!.add(u[c0 * 2] * (1f - t) + u[c1 * 2] * t); uv.add(u[c0 * 2 + 1] * (1f - t) + u[c1 * 2 + 1] * t) }
            d.cornerColor?.let { col -> cc!!.add(if (t == 0f) col[c0] else lerpColor(col[c0], col[c1], t)) }
        }
        val partial = ArrayList<Int>()
        for (f in 0 until d.nF) {
            val s = d.fStart[f]; val m = d.fSize[f]
            val inCount = (0 until m).count { keep[d.corners[s + it]] }
            if (inCount == m) {
                fStart.add(corners.size); fSize.add(m)
                for (k in 0 until m) { corners.add(vmap[d.corners[s + k]]); cornerData(s + k, s + k, 0f) }
                faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
            } else if (smooth && inCount > 0) partial.add(f)
        }
        for (f in partial) {
            val s = d.fStart[f]; val m = d.fSize[f]
            var start = -1
            for (j in 0 until m) if (!keep[d.corners[s + j]]) { start = j; break }
            var last = start
            var lastIn = keep[d.corners[s + last]]
            var cur: ArrayList<IntArray>? = null
            for (j in 0 until m) {
                val idx = (start + 1 + j) % m
                val inM = keep[d.corners[s + idx]]
                val a = d.corners[s + last]; val b = d.corners[s + idx]
                if (inM && !lastIn) {
                    cur = ArrayList()
                    cur.add(intArrayOf(cut(a, b), s + last, s + idx, java.lang.Float.floatToRawIntBits(fac(a, b))))
                    cur.add(intArrayOf(vmap[b], s + idx, s + idx, 0))
                } else if (!inM && lastIn) {
                    cur!!.add(intArrayOf(cut(a, b), s + last, s + idx, java.lang.Float.floatToRawIntBits(fac(a, b))))
                    fStart.add(corners.size); fSize.add(cur.size)
                    for (e in cur) { corners.add(e[0]); cornerData(e[1], e[2], java.lang.Float.intBitsToFloat(e[3])) }
                    faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
                    cur = null
                } else if (inM && lastIn) cur?.add(intArrayOf(vmap[b], s + idx, s + idx, 0))
                last = idx
                lastIn = inM
            }
        }
        val total = co.size / 3
        fun remapKey(k: Long): Long? {
            val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
            return if (keep[a] && keep[b]) BlendMeshData.key(vmap[a], vmap[b])
            else if (smooth && keep[a] != keep[b]) cutVert[k]?.let { cv -> BlendMeshData.key(if (keep[a]) vmap[a] else vmap[b], cv) } else null
        }
        val creases = HashMap<Long, Float>()
        for ((k, v) in d.edgeCrease) remapKey(k)?.let { creases[it] = v }
        val sharp = HashSet<Long>()
        for (k in d.sharpEdges) remapKey(k)?.let { sharp.add(it) }
        val loose = ArrayList<Int>()
        for (i in 0 until d.looseEdges.size / 2) remapKey(BlendMeshData.key(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1]))?.let { loose.add((it ushr 32).toInt()); loose.add((it and 0xFFFFFFFFL).toInt()) }
        return BlendMeshData(
            FloatArray(co.size) { co[it] }, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(), faceMat?.toIntArray(), faceSmooth?.toBooleanArray(),
            uv?.toFloatArray(), cc?.toIntArray(), pc?.toIntArray(), creases, vc?.toFloatArray(), loose.toIntArray(),
            dv?.let { l -> Array(total) { l[it] } }, d.groups
        ).carryNormals(d, sharp)
    }

    class BlendRng(seed: Int) {
        private var x = ((seed.toLong() and 0xFFFFFFFFL) shl 16) or 0x330EL
        fun nextUInt(): Long {
            x = (0x5DEECE66DL * x + 0xBL) and 0x0000FFFFFFFFFFFFL
            return (x ushr 17) and 0xFFFFFFFFL
        }
    }

    fun shuffle(arr: IntArray, seed: Int) {
        if (arr.size <= 1) return
        val rng = BlendRng(seed)
        var i = arr.size
        while (i-- > 0) {
            val j = (rng.nextUInt() % arr.size).toInt()
            if (i != j) { val t = arr[i]; arr[i] = arr[j]; arr[j] = t }
        }
    }

    fun build(d: BlendMeshData, frame: Float, start: Float, length: Float, flag: Int, seed: Int): BlendMeshData {
        var frac = ((frame - start) / length).coerceIn(0f, 1f)
        if (frac.isNaN()) frac = 0f
        if (flag and 2 != 0) frac = 1f - frac
        val facesDst = (d.nF * frac).toInt()
        val faceMap = IntArray(d.nF) { it }
        if (facesDst > 0 && flag and 1 != 0) shuffle(faceMap, seed)
        val vmap = HashMap<Int, Int>()
        val fStart = ArrayList<Int>(); val fSize = ArrayList<Int>(); val corners = ArrayList<Int>()
        val faceMat = d.faceMat?.let { ArrayList<Int>() }
        val faceSmooth = d.faceSmooth?.let { ArrayList<Boolean>() }
        val uv = d.uv?.let { ArrayList<Float>() }
        val cc = d.cornerColor?.let { ArrayList<Int>() }
        for (i in 0 until facesDst) {
            val f = faceMap[i]
            for (k in 0 until d.fSize[f]) vmap.getOrPut(d.corners[d.fStart[f] + k]) { vmap.size }
        }
        for (i in 0 until facesDst) {
            val f = faceMap[i]
            fStart.add(corners.size); fSize.add(d.fSize[f])
            for (k in 0 until d.fSize[f]) {
                val c = d.fStart[f] + k
                corners.add(vmap[d.corners[c]]!!)
                d.uv?.let { u -> uv!!.add(u[c * 2]); uv.add(u[c * 2 + 1]) }
                d.cornerColor?.let { cc!!.add(it[c]) }
            }
            faceMat?.add(d.faceMat!![f]); faceSmooth?.add(d.faceSmooth!![f])
        }
        val nn = vmap.size
        val src = IntArray(nn)
        for ((o, nIdx) in vmap) src[nIdx] = o
        fun remap(k: Long): Long? { val a = vmap[(k ushr 32).toInt()] ?: return null; val b = vmap[(k and 0xFFFFFFFFL).toInt()] ?: return null; return BlendMeshData.key(a, b) }
        val creases = HashMap<Long, Float>()
        for ((k, v) in d.edgeCrease) remap(k)?.let { creases[it] = v }
        val sharp = HashSet<Long>()
        for (k in d.sharpEdges) remap(k)?.let { sharp.add(it) }
        val loose = ArrayList<Int>()
        val kept = HashSet<Long>()
        for (i in 0 until facesDst) { val f = faceMap[i]; for (k in 0 until d.fSize[f]) kept.add(BlendMeshData.key(corners[fStart[i] + k], corners[fStart[i] + (k + 1) % d.fSize[f]])) }
        val seenLoose = HashSet<Long>()
        fun tryLoose(a: Int, b: Int) {
            val k = remap(BlendMeshData.key(a, b)) ?: return
            if (k in kept || !seenLoose.add(k)) return
            loose.add((k ushr 32).toInt()); loose.add((k and 0xFFFFFFFFL).toInt())
        }
        for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) tryLoose(d.corners[d.fStart[f] + k], d.corners[d.fStart[f] + (k + 1) % d.fSize[f]])
        for (i in 0 until d.looseEdges.size / 2) tryLoose(d.looseEdges[i * 2], d.looseEdges[i * 2 + 1])
        val r = BlendMeshData(
            FloatArray(nn * 3) { d.co[src[it / 3] * 3 + it % 3] }, fStart.toIntArray(), fSize.toIntArray(), corners.toIntArray(),
            faceMat?.toIntArray(), faceSmooth?.toBooleanArray(), uv?.toFloatArray(), cc?.toIntArray(),
            d.pointColor?.let { pc -> IntArray(nn) { pc[src[it]] } }, creases, d.vertCrease?.let { vc -> FloatArray(nn) { vc[src[it]] } },
            loose.toIntArray(), d.dverts?.let { dv -> Array(nn) { dv[src[it]] } }, d.groups
        ).carryNormals(d, sharp)
        val cs = d.customShort
        if (cs != null && cs.size == d.corners.size * 2) {
            val o = ArrayList<Short>()
            for (i in 0 until facesDst) { val f = faceMap[i]; for (k in 0 until d.fSize[f]) { val c = d.fStart[f] + k; o.add(cs[c * 2]); o.add(cs[c * 2 + 1]) } }
            r.customShort = ShortArray(o.size) { o[it] }
        }
        return r
    }

    private fun faceArea(co: FloatArray, d: BlendMeshData, f: Int): Float {
        val s = d.fStart[f]; val m = d.fSize[f]
        fun p(k: Int) = d.corners[s + k] * 3
        if (m == 3) {
            val a = p(0); val b = p(1); val c = p(2)
            val n1x = co[a] - co[b]; val n1y = co[a + 1] - co[b + 1]; val n1z = co[a + 2] - co[b + 2]
            val n2x = co[b] - co[c]; val n2y = co[b + 1] - co[c + 1]; val n2z = co[b + 2] - co[c + 2]
            val x = n1y * n2z - n1z * n2y; val y = n1z * n2x - n1x * n2z; val z = n1x * n2y - n1y * n2x
            return kotlin.math.sqrt(x * x + y * y + z * z) * 0.5f
        }
        var x = 0f; var y = 0f; var z = 0f
        var prev = p(m - 1)
        for (k in 0 until m) {
            val cur = p(k)
            x += (co[prev + 1] - co[cur + 1]) * (co[prev + 2] + co[cur + 2])
            y += (co[prev + 2] - co[cur + 2]) * (co[prev] + co[cur])
            z += (co[prev] - co[cur]) * (co[prev + 1] + co[cur + 1])
            prev = cur
        }
        return kotlin.math.sqrt(x * x + y * y + z * z) * 0.5f
    }

    private fun faceAngles(co: FloatArray, d: BlendMeshData, f: Int): FloatArray {
        val s = d.fStart[f]; val m = d.fSize[f]
        val out = FloatArray(m)
        fun v(k: Int) = d.corners[s + k] * 3
        var iThis = m - 1
        var iNext = 0
        var prev = floatArrayOf(co[v(iThis - 1)] - co[v(iThis)], co[v(iThis - 1) + 1] - co[v(iThis) + 1], co[v(iThis - 1) + 2] - co[v(iThis) + 2])
        normalizeInPlace(prev)
        while (iNext < m) {
            val next = floatArrayOf(co[v(iThis)] - co[v(iNext)], co[v(iThis) + 1] - co[v(iNext) + 1], co[v(iThis) + 2] - co[v(iNext) + 2])
            normalizeInPlace(next)
            out[iThis] = angleNormalized(prev, next)
            prev = next
            iThis = iNext
            iNext++
        }
        return out
    }

    fun weightedNormal(d: BlendMeshData, mode: Int, weightIn: Int, thresh: Float, flag: Int, groupName: String): BlendMeshData {
        val nC = d.corners.size
        if (d.nF == 0) return d
        var weight = weightIn.toFloat() / 50.0f
        if (weightIn == 100) weight = Short.MAX_VALUE.toFloat()
        else if (weightIn == 1) weight = 1f / Short.MAX_VALUE.toFloat()
        else if ((weight - 1) * 25 > 1) weight = (weight - 1) * 25
        val keepSharp = flag and 1 != 0
        val invert = flag and 2 != 0
        val strengths = if (flag and 4 != 0) d.faceStrength?.takeIf { it.size == d.nF } else null
        val gi = d.groups.indexOf(groupName)
        val hasVg = gi >= 0 && d.dverts != null
        val sharpFaces = d.faceSmooth?.let { sm -> BooleanArray(d.nF) { !sm[it] } }
        val fn = BlendNormals.faceNormals(d.co, d.corners, d.fStart, d.fSize)
        val cornerFace = IntArray(nC)
        for (f in 0 until d.nF) for (k in 0 until d.fSize[f]) cornerFace[d.fStart[f] + k] = f
        val pairs: List<Pair<Float, Int>> = when (mode) {
            0 -> (0 until d.nF).map { faceArea(d.co, d, it) to it }
            1 -> ArrayList<Pair<Float, Int>>().also { l -> for (f in 0 until d.nF) { val a = faceAngles(d.co, d, f); for (k in a.indices) l.add((Math.PI.toFloat() - a[k]) to d.fStart[f] + k) } }
            else -> ArrayList<Pair<Float, Int>>().also { l -> for (f in 0 until d.nF) { val area = faceArea(d.co, d, f); val a = faceAngles(d.co, d, f); for (k in a.indices) l.add(((Math.PI.toFloat() - a[k]) * area) to d.fStart[f] + k) } }
        }.sortedByDescending { it.first }
        val fans = if (keepSharp) BlendNormals.computeFans(d.co, d.corners, d.fStart, d.fSize, sharpFaces, d.sharpEdges) else null
        val nItems = fans?.spaces?.size ?: d.nV
        val itemNormal = FloatArray(nItems * 3)
        val itemLoops = IntArray(nItems)
        val itemVal = FloatArray(nItems)
        val itemStrength = IntArray(nItems) { BlendBevel.FACE_STRENGTH_WEAK }
        val cache = FloatArray(128)
        fun aggregate(item: Int, v: Int, face: Int, value: Float) {
            if (hasVg) {
                val inGroup = d.dverts!![v].any { it.first == gi }
                if ((inGroup && invert) || (!inGroup && !invert)) return
            }
            if (strengths != null) {
                val s = strengths[face]
                if (s > itemStrength[item]) {
                    itemStrength[item] = s
                    itemVal[item] = 0.0f
                    itemLoops[item] = 0
                    itemNormal[item * 3] = 0f; itemNormal[item * 3 + 1] = 0f; itemNormal[item * 3 + 2] = 0f
                }
                if (s != itemStrength[item]) return
            }
            if (itemVal[item] == 0.0f) itemVal[item] = value
            if (!(kotlin.math.abs(itemVal[item] - value) <= thresh)) { itemLoops[item]++; itemVal[item] = value }
            val ln = itemLoops[item]
            if (ln < 128 && cache[ln] == 0.0f) cache[ln] = 1.0f / Math.pow(weight.toDouble(), ln.toDouble()).toFloat()
            val inv = if (ln < 128) cache[ln] else 1.0f / Math.pow(weight.toDouble(), ln.toDouble()).toFloat()
            val f = value * inv
            itemNormal[item * 3] += fn[face * 3] * f; itemNormal[item * 3 + 1] += fn[face * 3 + 1] * f; itemNormal[item * 3 + 2] += fn[face * 3 + 2] * f
        }
        if (mode == 0) {
            for ((value, face) in pairs) for (k in 0 until d.fSize[face]) {
                val c = d.fStart[face] + k
                val v = d.corners[c]
                aggregate(if (fans != null) fans.spaceOf[c] else v, v, face, value)
            }
        } else {
            for ((value, c) in pairs) {
                val v = d.corners[c]
                aggregate(if (fans != null) fans.spaceOf[c] else v, v, cornerFace[c], value)
            }
        }
        for (i in 0 until nItems) {
            val n = floatArrayOf(itemNormal[i * 3], itemNormal[i * 3 + 1], itemNormal[i * 3 + 2])
            if (normalizeInPlace(n) < 1e-6f) n.fill(0f)
            n.copyInto(itemNormal, i * 3)
        }
        fun isZero(i: Int) = itemNormal[i * 3] == 0f && itemNormal[i * 3 + 1] == 0f && itemNormal[i * 3 + 2] == 0f
        val result = if (keepSharp) {
            val cn = fans!!.normals.copyOf()
            for (c in 0 until nC) { val si = fans.spaceOf[c]; if (si >= 0 && !isZero(si)) for (k in 0 until 3) cn[c * 3 + k] = itemNormal[si * 3 + k] }
            BlendNormals.customSet(d.co, d.corners, d.fStart, d.fSize, sharpFaces, d.sharpEdges, cn, false)
        } else if (!hasVg) {
            val vn = FloatArray(d.nV * 3)
            for (c in 0 until nC) { val v = d.corners[c]; for (k in 0 until 3) vn[v * 3 + k] = itemNormal[v * 3 + k] }
            BlendNormals.customSet(d.co, d.corners, d.fStart, d.fSize, sharpFaces, d.sharpEdges, vn, true)
        } else {
            val cn = BlendNormals.computeFans(d.co, d.corners, d.fStart, d.fSize, sharpFaces, d.sharpEdges).normals.copyOf()
            for (c in 0 until nC) { val v = d.corners[c]; if (!isZero(v)) for (k in 0 until 3) cn[c * 3 + k] = itemNormal[v * 3 + k] }
            BlendNormals.customSet(d.co, d.corners, d.fStart, d.fSize, sharpFaces, d.sharpEdges, cn, false)
        }
        val r = BlendMeshData(d.co, d.fStart, d.fSize, d.corners, d.faceMat, d.faceSmooth, d.uv, d.cornerColor, d.pointColor, d.edgeCrease, d.vertCrease, d.looseEdges, d.dverts, d.groups)
        r.carryNormals(d, result.second)
        r.legacyNormals = false
        r.autoSmooth = null
        r.customShort = result.first
        r.faceStrength = d.faceStrength
        return r
    }
}
