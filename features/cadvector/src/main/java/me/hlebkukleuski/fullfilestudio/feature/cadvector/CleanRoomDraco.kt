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
 * Portions ported and adapted from Google Draco
 * (https://github.com/google/draco), Copyright 2016-2026 The Draco Authors,
 * licensed under the Apache License, Version 2.0.
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.cadvector

internal class DracoAttribute(val uniqueId: Int, val components: Int, val values: FloatArray, val isInteger: Boolean)

internal class DracoMesh(val pointCount: Int, val faces: IntArray, private val attributes: List<DracoAttribute>) {
    fun attribute(uniqueId: Int): DracoAttribute? = attributes.firstOrNull { it.uniqueId == uniqueId }
}

internal object CleanRoomDraco {
    fun decode(data: ByteArray): DracoMesh? = try {
        decodeOrThrow(data)
    } catch (_: Throwable) {
        null
    }

    fun decodeOrThrow(data: ByteArray): DracoMesh = DracoDecoder(data).run()
}

private class IntList(cap: Int = 16) {
    var a = IntArray(maxOf(cap, 4))
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) {
        a[i] = v
    }
    fun last() = a[size - 1]
    fun setLast(v: Int) {
        a[size - 1] = v
    }
    fun removeLast(): Int = a[--size]
    fun isEmpty() = size == 0
}

private class DracoBuf(val d: ByteArray, var pos: Int = 0) {
    private var bitPos = -1L

    fun u8(): Int {
        require(pos < d.size)
        return d[pos++].toInt() and 0xFF
    }

    fun i8(): Int = u8().toByte().toInt()
    fun u16(): Int = u8() or (u8() shl 8)
    fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    fun leb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 64)
        }
        return result
    }

    fun varInt(): Int {
        val v = leb()
        require(v in 0..Int.MAX_VALUE.toLong())
        return v.toInt()
    }

    fun take(n: Int): Int {
        require(n >= 0 && pos + n <= d.size)
        val start = pos
        pos += n
        return start
    }

    fun startBits() {
        bitPos = pos.toLong() * 8
    }

    fun bits(n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            val byte = (bitPos ushr 3).toInt()
            val bit = if (byte < d.size) (d[byte].toInt() ushr (bitPos and 7).toInt()) and 1 else 0
            v = v or (bit shl i)
            bitPos++
        }
        return v
    }

    fun endBits() {
        pos = ((bitPos + 7) / 8).toInt()
        bitPos = -1
    }
}

private class Rans(private val buf: ByteArray, private val start: Int, size: Int, private val lBase: Int) {
    private var off: Int
    var state: Long

    init {
        require(size >= 1)
        val last = buf[start + size - 1].toInt() and 0xFF
        when (last ushr 6) {
            0 -> {
                off = size - 1
                state = (last and 0x3F).toLong()
            }
            1 -> {
                require(size >= 2)
                off = size - 2
                state = (le(size - 2, 2) and 0x3FFF).toLong()
            }
            2 -> {
                require(size >= 3)
                off = size - 3
                state = (le(size - 3, 3) and 0x3FFFFF).toLong()
            }
            else -> {
                require(size >= 4)
                off = size - 4
                state = (le(size - 4, 4) and 0x3FFFFFFF).toLong()
            }
        }
        state += lBase
    }

    private fun le(at: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or ((buf[start + at + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    fun read(precision: Int, lut: IntArray, prob: IntArray, cum: IntArray): Int {
        while (state < lBase && off > 0) state = state * 256 + (buf[start + --off].toInt() and 0xFF)
        val quo = state / precision
        val rem = (state % precision).toInt()
        val sym = lut[rem]
        state = quo * prob[sym] + rem - cum[sym]
        return sym
    }

    fun readBit(p0: Int): Boolean {
        val p = 256 - p0
        if (state < 4096 && off > 0) state = state * 256 + (buf[start + --off].toInt() and 0xFF)
        val x = state
        val quot = x / 256
        val rem = (x % 256).toInt()
        val xn = quot * p
        val v = rem < p
        state = if (v) xn + rem else x - xn - p
        return v
    }
}

private class DracoDecoder(data: ByteArray) {
    private val b = DracoBuf(data)
    private var method = 0
    private var minorVersion = 0
    private var majorVersion = 0

    private var numFaces = 0
    private var numPoints = 0
    private val ftv = IntList(1024)

    private var numEncodedVertices = 0
    private var numSplitSymbols = 0
    private var traversalType = 0
    private var numAttributeData = 0
    private var numEncodedSymbols = 0
    private lateinit var opposite: IntArray
    private lateinit var vertexCorners: IntArray
    private lateinit var isVertHole: BooleanArray
    private lateinit var valences: IntArray
    private val activeStack = IntList()
    private var lastSymbol = -1
    private var activeContext = -1
    private var lastVertAdded = -1
    private val topologySplitId = IntList()
    private val splitActiveCorners = IntList()
    private val sourceSymbolId = IntList()
    private val splitSymbolId = IntList()
    private val sourceEdgeBit = IntList()
    private var symbolBufStart = 0
    private var symbolBufSize = 0
    private var symbolBits: DracoBuf? = null
    private var startFaceProb = 0
    private var startFaceStart = 0
    private var startFaceSize = 0
    private val attrConnProb = IntList()
    private val attrConnStart = IntList()
    private val attrConnSize = IntList()
    private var contextSymbols = arrayOfNulls<IntArray>(6)
    private var contextCounters = IntArray(6)

    private var numDecoders = 0
    private var decDataId = IntArray(0)
    private var decType = IntArray(0)
    private var decTraversal = IntArray(0)
    private class AttrInfo(val type: Int, val dataType: Int, val components: Int, val normalized: Boolean, val uniqueId: Int) {
        var decoderType = 0
        var predictionScheme = -2
        var transformType = 0
        var compressed = 0
        var values: IntArray = IntArray(0)
        var floats: FloatArray? = null
        var wrapMin = 0
        var wrapMax = 0
        var creaseFlags: Array<BooleanArray?> = arrayOfNulls(4)
        var orientations: BooleanArray = BooleanArray(0)
        var orientationCount = 0
    }
    private val attrs = ArrayList<ArrayList<AttrInfo>>()

    private val cornerToVertex = ArrayList<IntArray>()
    private val attrFtv = ArrayList<IntArray>()
    private val leftMost = ArrayList<IntList>()
    private val seamEdge = ArrayList<BooleanArray>()
    private val seamVertex = ArrayList<BooleanArray>()
    private lateinit var cornerToPoint: IntArray
    private var pointCount = 0

    private val encodedIndexToCorner = ArrayList<IntList>()
    private val vertexToEncodedIndex = ArrayList<IntArray>()
    private val indicesMap = ArrayList<IntArray>()
    private lateinit var faceVisited: BooleanArray
    private lateinit var vertexVisited: BooleanArray
    private var currDec = 0

    fun run(): DracoMesh {
        val magic = String(b.d, 0, 5, Charsets.US_ASCII)
        require(magic == "DRACO")
        b.pos = 5
        majorVersion = b.u8()
        minorVersion = b.u8()
        require(majorVersion == 2 && minorVersion >= 2)
        val encoderType = b.u8()
        require(encoderType == 1)
        method = b.u8()
        val flags = b.u16()
        if (flags and 0x8000 != 0) skipMetadata()
        if (method == 0) decodeSequentialConnectivity() else decodeEdgebreakerConnectivity()
        return decodeAttributes()
    }

    private fun skipMetadata() {
        val n = b.varInt()
        repeat(n) {
            b.varInt()
            skipMetadataElement()
        }
        skipMetadataElement()
    }

    private fun skipMetadataElement() {
        val entries = b.varInt()
        repeat(entries) {
            b.take(b.u8())
            b.take(b.u8())
        }
        val subs = b.varInt()
        repeat(subs) {
            b.take(b.u8())
            skipMetadataElement()
        }
    }

    private fun decodeSequentialConnectivity() {
        numFaces = b.varInt()
        numPoints = b.varInt()
        val methodC = b.u8()
        if (methodC == 0) {
            val sym = decodeSymbols(numFaces * 3, 1)
            var last = 0
            for (i in 0 until numFaces * 3) {
                val e = sym[i]
                var diff = e ushr 1
                if (e and 1 != 0) diff = -diff
                last += diff
                ftv.add(last)
            }
        } else {
            for (i in 0 until numFaces * 3) {
                ftv.add(
                    when {
                        numPoints < 256 -> b.u8()
                        numPoints < (1 shl 16) -> b.u16()
                        numPoints < (1 shl 21) -> b.varInt()
                        else -> b.i32()
                    }
                )
            }
        }
    }

    private fun decodeSymbols(numValues: Int, numComponents: Int): IntArray {
        val out = IntArray(numValues)
        if (numValues == 0) return out
        val scheme = b.u8()
        if (scheme == 0) {
            val numSymbols = b.varInt()
            val (lut, prob, cum) = buildTables(numSymbols, 4096)
            val size = b.leb().toInt()
            val start = b.take(size)
            val rans = Rans(b.d, start, size, 16384)
            b.startBits()
            var i = 0
            while (i < numValues) {
                val bitsLen = rans.read(4096, lut, prob, cum)
                for (j in 0 until numComponents) {
                    if (i + j < numValues) out[i + j] = b.bits(bitsLen)
                }
                i += numComponents
            }
            b.endBits()
        } else {
            val maxBits = b.u8()
            val numSymbols = b.varInt()
            val precisionBits = ((3 * maxBits) / 2).coerceIn(12, 20)
            val precision = 1 shl precisionBits
            val (lut, prob, cum) = buildTables(numSymbols, precision)
            val size = b.leb().toInt()
            val start = b.take(size)
            val rans = Rans(b.d, start, size, precision * 4)
            for (i in 0 until numValues) out[i] = rans.read(precision, lut, prob, cum)
        }
        return out
    }

    private fun buildTables(numSymbols: Int, precision: Int): Triple<IntArray, IntArray, IntArray> {
        require(numSymbols in 1..(1 shl 20))
        val probs = IntArray(numSymbols)
        var i = 0
        while (i < numSymbols) {
            val pd = b.u8()
            val token = pd and 3
            if (token == 3) {
                val offset = pd ushr 2
                require(i + offset < numSymbols)
                i += offset + 1
            } else {
                var p = pd ushr 2
                for (j in 0 until token) p = p or (b.u8() shl (8 * (j + 1) - 2))
                probs[i] = p
                i++
            }
        }
        val lut = IntArray(precision)
        val cum = IntArray(numSymbols)
        var c = 0
        for (s in 0 until numSymbols) {
            cum[s] = c
            val end = c + probs[s]
            require(end <= precision)
            for (k in c until end) lut[k] = s
            c = end
        }
        require(c == precision)
        return Triple(lut, probs, cum)
    }

    private fun next(c: Int) = if (c < 0) c else if (c % 3 == 2) c - 2 else c + 1
    private fun prev(c: Int) = if (c < 0) c else if (c % 3 == 0) c + 2 else c - 1
    private fun posOpp(c: Int) = if (c < 0 || c >= opposite.size) -1 else opposite[c]
    private fun setOpp(c: Int, o: Int) {
        opposite[c] = o
        opposite[o] = c
    }

    private fun ftvAt(face: Int, k: Int) = ftv[face * 3 + k]

    private fun vertOf(table: IntArray?, corner: Int): Int {
        if (table == null) return ftv[corner]
        return table[corner]
    }

    private fun mapCorner(corner: Int, v: Int) {
        cornerToVertex[0][corner] = v
        if (v >= 0) vertexCorners[v] = corner
    }

    private fun decodeEdgebreakerConnectivity() {
        traversalType = b.u8()
        numEncodedVertices = b.varInt()
        numFaces = b.varInt()
        numAttributeData = b.u8()
        numEncodedSymbols = b.varInt()
        numSplitSymbols = b.varInt()
        val nSplits = b.varInt()
        var lastId = 0
        val srcDelta = IntArray(nSplits)
        val splitDelta = IntArray(nSplits)
        for (i in 0 until nSplits) {
            srcDelta[i] = b.varInt()
            splitDelta[i] = b.varInt()
        }
        for (i in 0 until nSplits) {
            val s = srcDelta[i] + lastId
            sourceSymbolId.add(s)
            splitSymbolId.add(s - splitDelta[i])
            lastId = s
        }
        if (nSplits > 0) {
            b.startBits()
            for (i in 0 until nSplits) sourceEdgeBit.add(b.bits(1))
            b.endBits()
        }
        val totalVerts = numEncodedVertices + numSplitSymbols
        require(numFaces in 0..50_000_000 && totalVerts in 0..150_000_000)
        opposite = IntArray(numFaces * 3) { -1 }
        vertexCorners = IntArray(totalVerts) { -1 }
        isVertHole = BooleanArray(totalVerts) { true }
        cornerToVertex.add(IntArray(numFaces * 3) { -1 })
        if (traversalType == 0) {
            val sz = b.leb().toInt()
            symbolBufStart = b.take(sz)
            symbolBufSize = sz
            parseFaceAndAttrConnectivity()
            symbolBits = DracoBuf(b.d.copyOfRange(symbolBufStart, symbolBufStart + symbolBufSize)).also { it.startBits() }
        } else if (traversalType == 2) {
            parseFaceAndAttrConnectivity()
            valences = IntArray(totalVerts)
            for (i in 0 until 6) {
                val n = b.varInt()
                contextCounters[i] = n
                if (n > 0) contextSymbols[i] = decodeSymbols(n, 1)
            }
        } else {
            throw IllegalArgumentException("traversal")
        }
        for (i in 0 until numEncodedSymbols) {
            decodeSymbol()
            newActiveCornerReached(3 * i, i)
        }
        processInteriorEdges()
    }

    private fun parseFaceAndAttrConnectivity() {
        startFaceProb = b.u8()
        startFaceSize = b.varInt()
        startFaceStart = b.take(startFaceSize)
        for (i in 0 until numAttributeData) {
            attrConnProb.add(b.u8())
            val sz = b.varInt()
            attrConnSize.add(sz)
            attrConnStart.add(b.take(sz))
        }
    }

    private fun decodeSymbol() {
        if (traversalType == 2) {
            lastSymbol = if (activeContext != -1) {
                val arr = contextSymbols[activeContext] ?: throw IllegalStateException()
                val idx = --contextCounters[activeContext]
                require(idx >= 0)
                intArrayOf(0, 1, 3, 5, 7)[arr[idx]]
            } else 7
        } else {
            val bits = symbolBits!!
            var s = bits.bits(1)
            if (s != 0) s = s or (bits.bits(2) shl 1)
            lastSymbol = s
        }
    }

    private fun replaceVerts(from: Int, to: Int) {
        for (i in 0 until ftv.size) if (ftv[i] == from) ftv[i] = to
    }


    private fun updateCornersAfterMerge(c: Int, v: Int) {
        val oc = posOpp(c)
        if (oc >= 0) {
            var cn = next(oc)
            var guard = 0
            while (cn >= 0 && guard++ < 1_000_000) {
                mapCorner(cn, v)
                cn = posSwingLeft(cn)
            }
        }
    }

    private fun newActiveCornerReached(newCorner: Int, symbolId: Int) {
        var checkSplit = false
        var next = 0
        when (lastSymbol) {
            0 -> {
                val cornerA = activeStack.last()
                var cornerB = prev(cornerA)
                while (posOpp(cornerB) >= 0) cornerB = prev(posOpp(cornerB))
                setOpp(cornerA, newCorner + 1)
                setOpp(cornerB, newCorner + 2)
                activeStack.setLast(newCorner)
                val vert = ftv[next(cornerA)]
                next = ftv[next(cornerB)]
                val prevV = ftv[prev(cornerA)]
                if (traversalType == 2) {
                    valences[next]++
                    valences[prevV]++
                }
                ftv.add(vert); ftv.add(next); ftv.add(prevV)
                isVertHole[vert] = false
                mapCorner(newCorner, vert)
                mapCorner(newCorner + 1, next)
                mapCorner(newCorner + 2, prevV)
            }
            1 -> {
                val cornerB = activeStack.removeLast()
                for (i in 0 until topologySplitId.size) {
                    if (topologySplitId[i] == symbolId) activeStack.add(splitActiveCorners[i])
                }
                val cornerA = activeStack.last()
                setOpp(cornerA, newCorner + 2)
                setOpp(cornerB, newCorner + 1)
                activeStack.setLast(newCorner)
                val vert = ftv[prev(cornerA)]
                next = ftv[next(cornerA)]
                val prevV = ftv[prev(cornerB)]
                mapCorner(newCorner, vert)
                mapCorner(newCorner + 1, next)
                mapCorner(newCorner + 2, prevV)
                val vertexN = ftv[next(cornerB)]
                if (traversalType == 2) valences[vert] += valences[vertexN]
                replaceVerts(vertexN, vert)
                if (traversalType == 2) {
                    valences[next]++
                    valences[prevV]++
                }
                ftv.add(vert); ftv.add(next); ftv.add(prevV)
                updateCornersAfterMerge(newCorner + 1, vert)
                vertexCorners[vertexN] = -1
            }
            5 -> {
                val cornerA = activeStack.last()
                setOpp(newCorner + 2, cornerA)
                activeStack.setLast(newCorner)
                checkSplit = true
                val vert = ftv[prev(cornerA)]
                next = ftv[next(cornerA)]
                val prevV = ++lastVertAdded
                if (traversalType == 2) {
                    valences[vert]++
                    valences[next]++
                    valences[prevV] += 2
                }
                ftv.add(vert); ftv.add(next); ftv.add(prevV)
                mapCorner(newCorner + 2, prevV)
                mapCorner(newCorner, vert)
                mapCorner(newCorner + 1, next)
            }
            3 -> {
                val cornerA = activeStack.last()
                setOpp(newCorner + 1, cornerA)
                activeStack.setLast(newCorner)
                checkSplit = true
                val vert = ftv[next(cornerA)]
                next = ++lastVertAdded
                val prevV = ftv[prev(cornerA)]
                if (traversalType == 2) {
                    valences[vert]++
                    valences[next] += 2
                    valences[prevV]++
                }
                ftv.add(vert); ftv.add(next); ftv.add(prevV)
                mapCorner(newCorner + 2, prevV)
                mapCorner(newCorner, vert)
                mapCorner(newCorner + 1, next)
            }
            7 -> {
                activeStack.add(newCorner)
                checkSplit = true
                val vert = lastVertAdded + 1
                next = vert + 1
                val prevV = next + 1
                if (traversalType == 2) {
                    valences[vert] += 2
                    valences[next] += 2
                    valences[prevV] += 2
                }
                ftv.add(vert); ftv.add(next); ftv.add(prevV)
                lastVertAdded = prevV
                mapCorner(newCorner, vert)
                mapCorner(newCorner + 1, next)
                mapCorner(newCorner + 2, prevV)
            }
            else -> throw IllegalStateException("symbol")
        }
        if (traversalType == 2) {
            activeContext = valences[next].coerceIn(2, 7) - 2
        }
        if (checkSplit) {
            val encoderSymbolId = numEncodedSymbols - symbolId - 1
            while (!sourceSymbolId.isEmpty() && sourceSymbolId.last() == encoderSymbolId) {
                val splitEdge = sourceEdgeBit.removeLast()
                val encSplitId = splitSymbolId.removeLast()
                sourceSymbolId.removeLast()
                val act = activeStack.last()
                val newActive = if (splitEdge == 1) next(act) else prev(act)
                topologySplitId.add(numEncodedSymbols - encSplitId - 1)
                splitActiveCorners.add(newActive)
            }
        }
    }

    private fun processInteriorEdges() {
        val rans = Rans(b.d, startFaceStart, startFaceSize, 4096)
        while (!activeStack.isEmpty()) {
            val cornerA = activeStack.removeLast()
            val interior = rans.readBit(startFaceProb)
            if (!interior) continue
            var cornerB = prev(cornerA)
            while (posOpp(cornerB) >= 0) cornerB = prev(posOpp(cornerB))
            var cornerC = next(cornerA)
            while (posOpp(cornerC) >= 0) cornerC = next(posOpp(cornerC))
            val newCorner = ftv.size
            require(newCorner + 2 < opposite.size)
            setOpp(newCorner, cornerA)
            setOpp(newCorner + 1, cornerB)
            setOpp(newCorner + 2, cornerC)
            val nextA = ftv[next(cornerA)]
            val nextB = ftv[next(cornerB)]
            val nextC = ftv[next(cornerC)]
            mapCorner(newCorner, nextB)
            mapCorner(newCorner + 1, nextC)
            mapCorner(newCorner + 2, nextA)
            ftv.add(nextB); ftv.add(nextC); ftv.add(nextA)
            isVertHole[nextB] = false
            isVertHole[nextC] = false
            isVertHole[nextA] = false
        }
    }

    private fun dataOf(dec: Int): Int {
        if (dec < 0 || dec >= numDecoders) return -1
        val d = decDataId[dec].toByte().toInt()
        return if (d >= 0 && decType[dec] == 1 && d < attrFtv.size) d else -1
    }

    private fun table(dec: Int): IntArray? = dataOf(dec).let { if (it < 0) null else attrFtv[it] }

    private fun opp(dec: Int, c: Int): Int {
        val d = dataOf(dec)
        if (d < 0) return posOpp(c)
        if (c < 0) return -1
        if (seamEdge[d][c]) return -1
        return posOpp(c)
    }

    private fun oppData(d: Int, c: Int): Int {
        if (c < 0) return -1
        if (seamEdge[d][c]) return -1
        return posOpp(c)
    }

    private fun posSwingRight(c: Int) = prev(posOpp(prev(c)))
    private fun posSwingLeft(c: Int) = next(posOpp(next(c)))
    private fun swingRight(dec: Int, c: Int) = prev(opp(dec, prev(c)))
    private fun swingLeft(dec: Int, c: Int) = next(opp(dec, next(c)))

    private fun decodeAttributeSeams() {
        val count = numAttributeData
        val ranses = Array(count) { Rans(b.d, attrConnStart[it], attrConnSize[it], 4096) }
        for (a in 0 until count) {
            seamEdge.add(BooleanArray(ftv.size))
            seamVertex.add(BooleanArray(vertexCorners.size))
        }
        for (j in 0 until numFaces) {
            for (k in 0 until 3) {
                val corner = j * 3 + k
                val n = ftv[next(corner)]
                val p = ftv[prev(corner)]
                val oc = posOpp(corner)
                if (oc >= 0) {
                    if (oc >= corner) {
                        for (a in 0 until count) {
                            if (ranses[a].readBit(attrConnProb[a])) {
                                seamEdge[a][corner] = true
                                seamEdge[a][oc] = true
                                markSeam(a, n, p)
                                markSeam(a, ftv[next(oc)], ftv[prev(oc)])
                            }
                        }
                    }
                } else {
                    for (a in 0 until count) {
                        seamEdge[a][corner] = true
                        markSeam(a, n, p)
                    }
                }
            }
        }
    }

    private fun markSeam(a: Int, v1: Int, v2: Int) {
        if (v1 >= 0) seamVertex[a][v1] = true
        if (v2 >= 0) seamVertex[a][v2] = true
    }

    private fun updateVertexToCornerMap(v: Int) {
        val first = vertexCorners[v]
        if (first < 0) return
        var act = posSwingLeft(first)
        var c = first
        var guard = 0
        while (act >= 0 && act != first && guard++ < 1_000_000) {
            c = act
            act = posSwingLeft(act)
        }
        if (act != first) vertexCorners[v] = c
    }

    private fun recomputeVertices(attr: Int) {
        val map = IntArray(ftv.size) { -1 }
        cornerToVertex.add(map)
        val lm = IntList()
        leftMost.add(lm)
        var numNew = 0
        val seamE = seamEdge[attr]
        for (v in vertexCorners.indices) {
            val c = vertexCorners[v]
            if (c < 0) continue
            var firstVert = numNew++
            var firstC = c
            if (seamVertex[attr][v]) {
                var act = next(oppData(attr, next(firstC)))
                var guard = 0
                while (act >= 0 && guard++ < 1_000_000) {
                    firstC = act
                    act = next(oppData(attr, next(act)))
                    if (act == c) break
                }
            }
            map[firstC] = firstVert
            lm.add(firstC)
            var act = posSwingRight(firstC)
            var guard = 0
            while (act >= 0 && act != firstC && guard++ < 1_000_000) {
                if (seamE[next(act)]) {
                    firstVert = numNew++
                    lm.add(act)
                }
                map[act] = firstVert
                act = posSwingRight(act)
            }
        }
        attrFtv.add(map)
    }

    private fun assignPointsToCorners() {
        cornerToPoint = IntArray(ftv.size) { -1 }
        var count = 0
        for (v in vertexCorners.indices) {
            var c = vertexCorners[v]
            if (c < 0) continue
            var dedup = c
            if (!isVertHole[v]) {
                for (i in 1..numAttributeData) {
                    if (!seamVertex[i - 1][ftv[c]]) continue
                    val vertId = cornerToVertex[i][c]
                    var act = posSwingRight(c)
                    var found = false
                    var guard = 0
                    while (act != c && act >= 0 && guard++ < 1_000_000) {
                        if (cornerToVertex[i][act] != vertId) {
                            dedup = act
                            found = true
                            break
                        }
                        act = posSwingRight(act)
                    }
                    if (found) break
                }
            }
            c = dedup
            cornerToPoint[c] = count++
            var prevC = c
            c = posSwingRight(c)
            var guard = 0
            while (c >= 0 && c != dedup && guard++ < 1_000_000) {
                var seam = false
                for (i in 1..numAttributeData) {
                    if (cornerToVertex[i][c] != cornerToVertex[i][prevC]) {
                        seam = true
                        break
                    }
                }
                cornerToPoint[c] = if (seam) count++ else cornerToPoint[prevC]
                prevC = c
                c = posSwingRight(c)
            }
        }
        pointCount = count
    }

    private fun isOnBoundary(dec: Int, vert: Int, cornerForPos: Int): Boolean {
        val d = dataOf(dec)
        if (d < 0) {
            val c = vertexCorners.getOrElse(vert) { -1 }
            if (c < 0) return true
            return posOpp(next(c)).let { next(it) } < 0
        }
        val lm = leftMost[d]
        val c = if (vert < lm.size) lm[vert] else -1
        if (c < 0) return true
        if (seamVertex[d][ftv[c]]) return true
        val pv = ftv[cornerForPos]
        val pc = vertexCorners.getOrElse(pv) { -1 }
        return pc < 0 || posSwingLeft(pc) < 0
    }

    private fun onNewVertexVisited(vertex: Int, corner: Int) {
        val list = encodedIndexToCorner[currDec]
        vertexToEncodedIndex[currDec][vertex] = list.size
        list.add(corner)
    }

    private fun cornerVert(dec: Int, c: Int): Int = vertOf(table(dec), c)

    private fun depthFirst(start: Int) {
        val dec = currDec
        if (faceVisited[start / 3]) return
        val stack = IntList()
        stack.add(start)
        val nv = cornerVert(dec, next(start))
        if (!vertexVisited[nv]) {
            vertexVisited[nv] = true
            onNewVertexVisited(nv, next(start))
        }
        val pv = cornerVert(dec, prev(start))
        if (!vertexVisited[pv]) {
            vertexVisited[pv] = true
            onNewVertexVisited(pv, prev(start))
        }
        while (!stack.isEmpty()) {
            var corner = stack.last()
            if (corner < 0 || faceVisited[corner / 3]) {
                stack.removeLast()
                continue
            }
            while (true) {
                faceVisited[corner / 3] = true
                val v = cornerVert(dec, corner)
                if (!vertexVisited[v]) {
                    val boundary = isOnBoundary(dec, v, corner)
                    vertexVisited[v] = true
                    onNewVertexVisited(v, corner)
                    if (!boundary) {
                        corner = rightCorner(dec, corner)
                        continue
                    }
                }
                val right = rightCorner(dec, corner)
                val left = leftCorner(dec, corner)
                val rf = if (right < 0) -1 else right / 3
                val lf = if (left < 0) -1 else left / 3
                if (rf < 0 || faceVisited[rf]) {
                    if (lf < 0 || faceVisited[lf]) {
                        stack.removeLast()
                        break
                    } else {
                        corner = left
                    }
                } else {
                    if (lf < 0 || faceVisited[lf]) {
                        corner = right
                    } else {
                        stack.setLast(left)
                        stack.add(right)
                        break
                    }
                }
            }
        }
    }

    private fun rightCorner(dec: Int, c: Int): Int = if (c < 0) -1 else opp(dec, next(c))
    private fun leftCorner(dec: Int, c: Int): Int = if (c < 0) -1 else opp(dec, prev(c))

    private lateinit var predictionDegree: IntArray

    private fun predictionDegreeTraverse(start: Int) {
        val dec = currDec
        if (faceVisited[start / 3]) return
        val stacks = Array(3) { IntList() }
        var best = 0
        stacks[0].add(start)
        val tip = cornerVert(dec, start)
        val nv = cornerVert(dec, next(start))
        val pv = cornerVert(dec, prev(start))
        if (!vertexVisited[nv]) {
            vertexVisited[nv] = true
            onNewVertexVisited(nv, next(start))
        }
        if (!vertexVisited[pv]) {
            vertexVisited[pv] = true
            onNewVertexVisited(pv, prev(start))
        }
        if (!vertexVisited[tip]) {
            vertexVisited[tip] = true
            onNewVertexVisited(tip, start)
        }
        fun priority(c: Int): Int {
            val vt = cornerVert(dec, c)
            var pr = 0
            if (!vertexVisited[vt]) {
                val degree = ++predictionDegree[vt]
                pr = if (degree > 1) 1 else 2
            }
            return pr.coerceAtMost(2)
        }
        while (true) {
            var corner = -1
            for (i in best until 3) {
                if (!stacks[i].isEmpty()) {
                    corner = stacks[i].removeLast()
                    best = i
                    break
                }
            }
            if (corner < 0) break
            if (faceVisited[corner / 3]) continue
            while (true) {
                faceVisited[corner / 3] = true
                val v = cornerVert(dec, corner)
                if (!vertexVisited[v]) {
                    vertexVisited[v] = true
                    onNewVertexVisited(v, corner)
                }
                val right = rightCorner(dec, corner)
                val left = leftCorner(dec, corner)
                val rVisited = right < 0 || faceVisited[right / 3]
                val lVisited = left < 0 || faceVisited[left / 3]
                if (!lVisited) {
                    val pr = priority(left)
                    if (rVisited && pr <= best) {
                        corner = left
                        continue
                    } else {
                        stacks[pr].add(left)
                        if (pr < best) best = pr
                    }
                }
                if (!rVisited) {
                    val pr = priority(right)
                    if (pr <= best) {
                        corner = right
                        continue
                    } else {
                        stacks[pr].add(right)
                        if (pr < best) best = pr
                    }
                }
                break
            }
        }
    }

    private fun parseAttributeDecoders() {
        numDecoders = b.u8()
        decDataId = IntArray(numDecoders)
        decType = IntArray(numDecoders)
        decTraversal = IntArray(numDecoders)
        if (method == 1) {
            for (i in 0 until numDecoders) {
                decDataId[i] = b.u8()
                decType[i] = b.u8()
                decTraversal[i] = b.u8()
            }
        }
        for (i in 0 until numDecoders) {
            val n = b.varInt()
            val list = ArrayList<AttrInfo>()
            for (j in 0 until n) {
                val t = b.u8()
                val dt = b.u8()
                val nc = b.u8()
                val norm = b.u8() != 0
                val uid = b.varInt()
                list.add(AttrInfo(t, dt, nc, norm, uid))
            }
            for (j in 0 until n) list[j].decoderType = b.u8()
            attrs.add(list)
        }
    }

    private fun decodeAttributes(): DracoMesh {
        parseAttributeDecoders()
        if (method == 1) {
            decodeAttributeSeams()
            for (v in isVertHole.indices) if (isVertHole[v]) updateVertexToCornerMap(v)
            for (a in 0 until numAttributeData) recomputeVertices(a)
            assignPointsToCorners()
        } else {
            pointCount = numPoints
        }
        for (i in 0 until numDecoders) {
            currDec = i
            encodedIndexToCorner.add(IntList())
            if (method == 1) {
                faceVisited = BooleanArray(numFaces)
                vertexVisited = BooleanArray(numFaces * 3)
                vertexToEncodedIndex.add(IntArray(numFaces * 3) { -1 })
                if (decTraversal[i] == 1) {
                    predictionDegree = IntArray(numFaces * 3)
                    for (f in 0 until numFaces) predictionDegreeTraverse(3 * f)
                } else {
                    for (f in 0 until numFaces) depthFirst(3 * f)
                }
                val im = IntArray(pointCount) { -1 }
                val tbl = table(i)
                for (c in 0 until numFaces * 3) {
                    val p = cornerToPoint[c]
                    if (p >= 0) im[p] = vertexToEncodedIndex[i][vertOf(tbl, c)]
                }
                indicesMap.add(im)
            } else {
                val l = encodedIndexToCorner[i]
                for (p in 0 until numPoints) l.add(p)
                vertexToEncodedIndex.add(IntArray(0))
                indicesMap.add(IntArray(numPoints) { it })
            }
        }
        for (i in 0 until numDecoders) {
            currDec = i
            val numEntries = encodedIndexToCorner[i].size
            for (a in attrs[i]) decodePortable(a, numEntries)
            for (a in attrs[i]) {
                when (a.decoderType) {
                    3 -> b.u8()
                    2 -> {
                        val mins = FloatArray(a.components) { b.f32() }
                        val range = b.f32()
                        val bits = b.u8()
                        val maxQ = (1 shl bits) - 1
                        val factor = 1f / maxQ
                        val out = FloatArray(numEntries * a.components)
                        for (k in out.indices) {
                            val q = a.values[k]
                            val nv = if (q < 0) -(-q * factor) else q * factor
                            out[k] = nv * range + mins[k % a.components]
                        }
                        a.floats = out
                    }
                }
            }
        }
        val faces = IntArray(numFaces * 3)
        if (method == 1) {
            for (c in faces.indices) faces[c] = cornerToPoint[c]
        } else {
            for (c in faces.indices) faces[c] = ftv[c]
        }
        val out = ArrayList<DracoAttribute>()
        for (i in 0 until numDecoders) {
            val im = indicesMap[i]
            for (a in attrs[i]) {
                if (a.decoderType == 3) continue
                val comps = a.components
                val src = a.floats
                val values = FloatArray(pointCount * comps)
                for (p in 0 until pointCount) {
                    val e = im.getOrElse(p) { -1 }
                    if (e < 0) continue
                    for (k in 0 until comps) {
                        val idx = e * comps + k
                        values[p * comps + k] = if (src != null) src.getOrElse(idx) { 0f } else a.values.getOrElse(idx) { 0 }.toFloat()
                    }
                }
                out.add(DracoAttribute(a.uniqueId, comps, values, src == null))
            }
        }
        return DracoMesh(pointCount, faces, out)
    }

    private fun numComponents(a: AttrInfo) = if (a.decoderType == 3) 2 else a.components

    private fun dataTypeSize(dt: Int) = when (dt) {
        1, 2, 11 -> 1
        3, 4 -> 2
        5, 6, 9 -> 4
        7, 8, 10 -> 8
        else -> 4
    }

    private fun decodePortable(a: AttrInfo, numEntries: Int) {
        if (a.decoderType == 0) {
            val sz = dataTypeSize(a.dataType)
            val floats = FloatArray(numEntries * a.components)
            for (k in floats.indices) {
                val start = b.take(sz)
                val bb = java.nio.ByteBuffer.wrap(b.d, start, sz).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                floats[k] = when (a.dataType) {
                    1 -> bb.get().toFloat()
                    2, 11 -> (bb.get().toInt() and 0xFF).toFloat()
                    3 -> bb.short.toFloat()
                    4 -> (bb.short.toInt() and 0xFFFF).toFloat()
                    5 -> bb.int.toFloat()
                    6 -> (bb.int.toLong() and 0xFFFFFFFFL).toFloat()
                    7, 8 -> bb.long.toFloat()
                    9 -> bb.float
                    10 -> bb.double.toFloat()
                    else -> 0f
                }
            }
            a.floats = floats
            return
        }
        a.predictionScheme = b.i8()
        if (a.predictionScheme != -2) a.transformType = b.i8()
        a.compressed = b.u8()
        val comps = numComponents(a)
        val numValues = numEntries * comps
        val raw: IntArray = if (a.compressed > 0) {
            decodeSymbols(numValues, comps)
        } else {
            val nb = b.u8()
            IntArray(numValues) {
                var v = 0
                for (k in 0 until nb) v = v or (b.u8() shl (8 * k))
                v
            }
        }
        val signed = if (a.transformType == 3) raw.copyOf() else IntArray(numValues) {
            val v = raw[it]
            if (v and 1 == 0) v ushr 1 else -(v ushr 1) - 1
        }
        if (a.predictionScheme == -2) {
            a.values = signed
            return
        }
        when (a.predictionScheme) {
            4 -> {
                for (i in 0 until 4) {
                    val n = b.varInt()
                    if (n > 0) {
                        val p0 = b.u8()
                        val sz = b.varInt()
                        val start = b.take(sz)
                        val r = Rans(b.d, start, sz, 4096)
                        a.creaseFlags[i] = BooleanArray(n) { r.readBit(p0) }
                    }
                }
            }
            5 -> {
                val n = b.i32()
                val p0 = b.u8()
                val sz = b.varInt()
                val start = b.take(sz)
                val r = Rans(b.d, start, sz, 4096)
                var lastO = true
                a.orientations = BooleanArray(n) {
                    if (!r.readBit(p0)) lastO = !lastO
                    lastO
                }
                a.orientationCount = n
            }
            6 -> {
                readTransformData(a)
                val p0 = b.u8()
                val sz = b.varInt()
                b.take(sz)
                a.values = signed
                return
            }
        }
        readTransformData(a)
        a.values = when (a.predictionScheme) {
            0 -> difference(a, signed, comps)
            1 -> parallelogram(a, signed, comps, numEntries)
            4 -> multiParallelogram(a, signed, comps, numEntries)
            5 -> texCoords(a, signed, numEntries)
            else -> signed
        }
    }

    private fun readTransformData(a: AttrInfo) {
        if (a.transformType == 1) {
            a.wrapMin = b.i32()
            a.wrapMax = b.i32()
        } else if (a.transformType == 3) {
            b.i32()
            b.i32()
        }
    }

    private fun wrap(a: AttrInfo, pred: IntArray, predOff: Int, corr: IntArray, corrOff: Int, out: IntArray, outOff: Int, comps: Int) {
        if (a.transformType != 1) {
            for (i in 0 until comps) out[outOff + i] = pred[predOff + i] + corr[corrOff + i]
            return
        }
        val min = a.wrapMin
        val max = a.wrapMax
        val maxDif = 1 + max - min
        for (i in 0 until comps) {
            val p = pred[predOff + i].coerceIn(min, max)
            var o = p + corr[corrOff + i]
            if (o > max) o -= maxDif else if (o < min) o += maxDif
            out[outOff + i] = o
        }
    }

    private fun difference(a: AttrInfo, signed: IntArray, comps: Int): IntArray {
        val out = signed.copyOf()
        if (signed.isEmpty()) return out
        val zero = IntArray(comps)
        wrap(a, zero, 0, signed, 0, out, 0, comps)
        var i = comps
        while (i < signed.size) {
            wrap(a, out, i - comps, signed, i, out, i, comps)
            i += comps
        }
        return out
    }

    private fun parallelogramPrediction(dataId: Int, ci: Int, data: IntArray, comps: Int, outPred: IntArray, outOff: Int): Boolean {
        val oci = opp(currDec, ci)
        if (oci < 0) return false
        val tbl = table(currDec)
        val map = vertexToEncodedIndex[currDec]
        val vOpp = map[vertOf(tbl, oci)]
        val vNext = map[vertOf(tbl, next(oci))]
        val vPrev = map[vertOf(tbl, prev(oci))]
        if (vOpp in 0 until dataId && vNext in 0 until dataId && vPrev in 0 until dataId) {
            for (c in 0 until comps) {
                outPred[outOff + c] = (data[vNext * comps + c] + data[vPrev * comps + c]) - data[vOpp * comps + c]
            }
            return true
        }
        return false
    }

    private fun parallelogram(a: AttrInfo, signed: IntArray, comps: Int, n: Int): IntArray {
        val out = signed.copyOf()
        if (n == 0) return out
        val pred = IntArray(comps)
        wrap(a, pred, 0, signed, 0, out, 0, comps)
        val corners = encodedIndexToCorner[currDec]
        for (p in 1 until n) {
            val ci = corners[p]
            val dst = p * comps
            if (!parallelogramPrediction(p, ci, out, comps, pred, 0)) {
                wrap(a, out, dst - comps, signed, dst, out, dst, comps)
            } else {
                wrap(a, pred, 0, signed, dst, out, dst, comps)
            }
        }
        return out
    }

    private fun multiParallelogram(a: AttrInfo, signed: IntArray, comps: Int, n: Int): IntArray {
        val out = signed.copyOf()
        if (n == 0) return out
        val preds = IntArray(4 * comps)
        val zero = IntArray(comps)
        wrap(a, zero, 0, signed, 0, out, 0, comps)
        val creasePos = IntArray(4)
        val corners = encodedIndexToCorner[currDec]
        val multi = LongArray(comps)
        val multiInt = IntArray(comps)
        for (p in 1 until n) {
            val start = corners[p]
            var corner = start
            var num = 0
            var firstPass = true
            var guard = 0
            while (corner >= 0 && guard++ < 100_000) {
                if (parallelogramPrediction(p, corner, out, comps, preds, num * comps)) {
                    num++
                    if (num == 4) break
                }
                corner = if (firstPass) swingLeft(currDec, corner) else swingRight(currDec, corner)
                if (corner == start) break
                if (corner < 0 && firstPass) {
                    firstPass = false
                    corner = swingRight(currDec, start)
                }
            }
            var used = 0
            if (num > 0) {
                java.util.Arrays.fill(multi, 0L)
                for (i in 0 until num) {
                    val ctx = num - 1
                    val flags = a.creaseFlags[ctx]
                    val isCrease = flags != null && flags.getOrElse(creasePos[ctx]++) { false }
                    if (!isCrease) {
                        used++
                        for (j in 0 until comps) multi[j] += preds[i * comps + j].toLong()
                    }
                }
            }
            val dst = p * comps
            if (used == 0) {
                wrap(a, out, dst - comps, signed, dst, out, dst, comps)
            } else {
                for (c in 0 until comps) multiInt[c] = (multi[c] / used).toInt()
                wrap(a, multiInt, 0, signed, dst, out, dst, comps)
            }
        }
        return out
    }

    private fun intSqrt(number: Long): Long {
        if (number <= 0L) return 0L
        var act = number
        var root = 1L
        while (act >= 2) {
            root *= 2
            act /= 4
        }
        do {
            root = (root + number / root) / 2
        } while (root * root > number)
        return root
    }

    private fun texCoords(a: AttrInfo, signed: IntArray, n: Int): IntArray {
        val out = signed.copyOf()
        val posDec = attrs.indexOfFirst { list -> list.any { it.type == 0 } }.coerceAtLeast(0)
        val posAttr = attrs[posDec].first { it.type == 0 }
        val posValues = posAttr.values
        val posMap = indicesMap[posDec]
        val corners = encodedIndexToCorner[currDec]
        val tbl = table(currDec)
        val map = vertexToEncodedIndex[currDec]
        var orientIdx = a.orientationCount
        val predicted = IntArray(2)
        fun pos(entry: Int, outArr: LongArray) {
            val corner = corners[entry]
            val point = cornerToPoint[corner]
            val mi = posMap[point]
            for (i in 0 until 3) outArr[i] = posValues[mi * 3 + i].toLong()
        }
        val tip = LongArray(3)
        val np = LongArray(3)
        val pp = LongArray(3)
        for (p in 0 until n) {
            val corner = corners[p]
            val nextId = map[vertOf(tbl, next(corner))]
            val prevId = map[vertOf(tbl, prev(corner))]
            var done = false
            if (prevId in 0 until p && nextId in 0 until p) {
                val nU = out[nextId * 2].toLong()
                val nV = out[nextId * 2 + 1].toLong()
                val pU = out[prevId * 2].toLong()
                val pV = out[prevId * 2 + 1].toLong()
                if (pU == nU && pV == nV) {
                    predicted[0] = pU.toInt()
                    predicted[1] = pV.toInt()
                    done = true
                } else {
                    pos(p, tip)
                    pos(nextId, np)
                    pos(prevId, pp)
                    val pn = LongArray(3) { pp[it] - np[it] }
                    val pnNorm2 = pn[0] * pn[0] + pn[1] * pn[1] + pn[2] * pn[2]
                    if (pnNorm2 != 0L) {
                        val cn = LongArray(3) { tip[it] - np[it] }
                        val cnDotPn = cn[0] * pn[0] + cn[1] * pn[1] + cn[2] * pn[2]
                        val pnU = pU - nU
                        val pnV = pV - nV
                        val xU = pnU * cnDotPn + nU * pnNorm2
                        val xV = pnV * cnDotPn + nV * pnNorm2
                        val xPos = LongArray(3) { np[it] + (pn[it] * cnDotPn) / pnNorm2 }
                        val d0 = tip[0] - xPos[0]
                        val d1 = tip[1] - xPos[1]
                        val d2 = tip[2] - xPos[2]
                        val cxNorm2 = d0 * d0 + d1 * d1 + d2 * d2
                        val norm = intSqrt(cxNorm2 * pnNorm2)
                        val cxU = pnV * norm
                        val cxV = -pnU * norm
                        require(orientIdx > 0)
                        val orientation = a.orientations[--orientIdx]
                        val rU = if (orientation) xU + cxU else xU - cxU
                        val rV = if (orientation) xV + cxV else xV - cxV
                        predicted[0] = (rU / pnNorm2).toInt()
                        predicted[1] = (rV / pnNorm2).toInt()
                        done = true
                    }
                }
            }
            if (!done) {
                var off = 0
                var zero = false
                if (prevId in 0 until p) off = prevId * 2
                if (nextId in 0 until p) {
                    off = nextId * 2
                } else if (p > 0) {
                    off = (p - 1) * 2
                } else {
                    zero = true
                }
                if (zero) {
                    predicted[0] = 0
                    predicted[1] = 0
                } else {
                    predicted[0] = out[off]
                    predicted[1] = out[off + 1]
                }
            }
            wrap(a, predicted, 0, out, p * 2, out, p * 2, 2)
        }
        return out
    }
}
