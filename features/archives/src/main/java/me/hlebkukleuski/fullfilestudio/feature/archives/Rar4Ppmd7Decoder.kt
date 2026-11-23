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
 * Ported from Ppmd7.c / Ppmd7.h (PPMdH codec),
 * Igor Pavlov (2010, Public Domain), based on PPMd var.H by Dmitry Shkarin (2001, Public Domain).
 */

package me.hlebkukleuski.fullfilestudio.feature.archives

internal class Rar4Ppmd7Decoder {

    private var mem = ByteArray(0)
    private var size = 0
    private var alignOffset = 0
    private var loUnit = 0
    private var hiUnit = 0
    private var text = 0
    private var unitsStart = 0
    private var glueCount = 0

    private var minContext = 0
    private var maxContext = 0
    private var foundState = 0
    private var orderFall = 0
    var initEsc = 0
    private var prevSuccess = 0
    private var maxOrder = 0
    private var hiBitsFlag = 0
    private var runLength = 0
    private var initRL = 0

    private val indx2Units = IntArray(PPMD_NUM_INDEXES)
    private val units2Indx = IntArray(128)
    private val freeList = IntArray(PPMD_NUM_INDEXES)
    private val ns2Indx = IntArray(256)
    private val ns2BsIndx = IntArray(256)
    private val hb2Flag = IntArray(256)

    private val seeSumm = IntArray(25 * 16 + 1)
    private val seeShift = IntArray(25 * 16 + 1)
    private val seeCount = IntArray(25 * 16 + 1)
    private val binSumm = Array(128) { IntArray(64) }

    private var range = 0xFFFFFFFFL
    private var code = 0L
    private var low = 0L
    private var bottom = 0x8000L

    init {
        var k = 0
        for (i in 0 until PPMD_NUM_INDEXES) {
            var step = if (i >= 12) 4 else (i ushr 2) + 1
            while (step-- > 0) units2Indx[k++] = i
            indx2Units[i] = k
        }
        ns2BsIndx[0] = 0
        ns2BsIndx[1] = 2
        for (i in 2 until 11) ns2BsIndx[i] = 4
        for (i in 11 until 256) ns2BsIndx[i] = 6

        for (i in 0 until 3) ns2Indx[i] = i
        var m = 3
        var step = 1
        for (i in 3 until 256) {
            ns2Indx[i] = m
            if (--step == 0) {
                step = (++m) - 2
            }
        }
        for (i in 0 until 0x40) hb2Flag[i] = 0
        for (i in 0x40 until 256) hb2Flag[i] = 8
    }

    fun isAllocated(): Boolean = mem.isNotEmpty() && size > 0

    fun alloc(memSize: Int): Boolean {
        val capped = memSize.coerceAtMost(64 * 1024 * 1024)
        if (capped < UNIT_SIZE) return false
        if (mem.isEmpty() || size != capped) {
            alignOffset = 4 - (capped and 3)
            mem = ByteArray(alignOffset + capped + UNIT_SIZE)
            size = capped
        }
        return true
    }

    fun initModel(order: Int) {
        maxOrder = order
        restartModel()
        seeShift[DUMMY_SEE] = PPMD_PERIOD_BITS
        seeSumm[DUMMY_SEE] = 0
        seeCount[DUMMY_SEE] = 64
    }

    fun initRangeDec(readByte: () -> Int): Boolean {
        low = 0L
        bottom = 0x8000L
        range = 0xFFFFFFFFL
        code = 0L
        for (i in 0 until 4) {
            code = ((code shl 8) or (readByte().toLong() and 0xFFL)) and 0xFFFFFFFFL
        }
        return code < 0xFFFFFFFFL
    }

    private fun u8(off: Int): Int = mem[off].toInt() and 0xFF
    private fun setU8(off: Int, v: Int) { mem[off] = v.toByte() }

    private fun u16(off: Int): Int =
        (mem[off].toInt() and 0xFF) or ((mem[off + 1].toInt() and 0xFF) shl 8)

    private fun setU16(off: Int, v: Int) {
        mem[off] = (v and 0xFF).toByte()
        mem[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun u32(off: Int): Int =
        (mem[off].toInt() and 0xFF) or
            ((mem[off + 1].toInt() and 0xFF) shl 8) or
            ((mem[off + 2].toInt() and 0xFF) shl 16) or
            ((mem[off + 3].toInt() and 0xFF) shl 24)

    private fun setU32(off: Int, v: Int) {
        mem[off] = (v and 0xFF).toByte()
        mem[off + 1] = ((v ushr 8) and 0xFF).toByte()
        mem[off + 2] = ((v ushr 16) and 0xFF).toByte()
        mem[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ctxNumStats(c: Int): Int = u16(c)
    private fun setCtxNumStats(c: Int, v: Int) = setU16(c, v)
    private fun ctxSummFreq(c: Int): Int = u16(c + 2)
    private fun setCtxSummFreq(c: Int, v: Int) = setU16(c + 2, v)
    private fun ctxStats(c: Int): Int = u32(c + 4)
    private fun setCtxStats(c: Int, v: Int) = setU32(c + 4, v)
    private fun ctxSuffix(c: Int): Int = u32(c + 8)
    private fun setCtxSuffix(c: Int, v: Int) = setU32(c + 8, v)
    private fun oneState(c: Int): Int = c + 2

    private fun stSym(s: Int): Int = u8(s)
    private fun setStSym(s: Int, v: Int) = setU8(s, v)
    private fun stFreq(s: Int): Int = u8(s + 1)
    private fun setStFreq(s: Int, v: Int) = setU8(s + 1, v)
    private fun stSucc(s: Int): Int = u16(s + 2) or (u16(s + 4) shl 16)
    private fun setStSucc(s: Int, v: Int) {
        setU16(s + 2, v and 0xFFFF)
        setU16(s + 4, (v ushr 16) and 0xFFFF)
    }

    private fun copyState(dst: Int, src: Int) {
        for (i in 0 until 6) mem[dst + i] = mem[src + i]
    }

    private fun swapStates(a: Int, b: Int) {
        for (i in 0 until 6) {
            val t = mem[a + i]
            mem[a + i] = mem[b + i]
            mem[b + i] = t
        }
    }

    private fun u2b(nu: Int): Int = nu * UNIT_SIZE
    private fun u2i(nu: Int): Int = units2Indx[nu - 1]
    private fun i2u(idx: Int): Int = indx2Units[idx]

    private fun insertNode(node: Int, indx: Int) {
        setU32(node, freeList[indx])
        freeList[indx] = node
    }

    private fun removeNode(indx: Int): Int {
        val node = freeList[indx]
        freeList[indx] = u32(node)
        return node
    }

    private fun splitBlock(ptrIn: Int, oldIndx: Int, newIndx: Int) {
        val nu = i2u(oldIndx) - i2u(newIndx)
        val ptr = ptrIn + u2b(i2u(newIndx))
        var i = u2i(nu)
        if (i2u(i) != nu) {
            val k = i2u(--i)
            insertNode(ptr + u2b(k), nu - k - 1)
        }
        insertNode(ptr, i)
    }

    private fun glueFreeBlocks() {
        val head = alignOffset + size
        var n = head
        glueCount = 255
        for (i in 0 until PPMD_NUM_INDEXES) {
            val nu = i2u(i)
            var next = freeList[i]
            freeList[i] = 0
            while (next != 0) {
                val nextNode = u32(next)
                setU32(next + 4, n)
                setU32(n + 8, next)
                n = next
                setU16(next, 0)
                setU16(next + 2, nu)
                next = nextNode
            }
        }
        setU16(head, 1)
        setU32(head + 4, n)
        setU32(n + 8, head)
        if (loUnit != hiUnit) setU16(loUnit, 1)

        while (n != head) {
            var nu = u16(n + 2)
            while (true) {
                val node2 = n + nu * UNIT_SIZE
                val nu2 = u16(node2 + 2)
                if (u16(node2) != 0 || nu + nu2 >= 0x10000) break
                nu += nu2
                val prev2 = u32(node2 + 8)
                val next2 = u32(node2 + 4)
                setU32(prev2 + 4, next2)
                setU32(next2 + 8, prev2)
                setU16(n + 2, nu)
            }
            n = u32(n + 4)
        }

        n = u32(head + 4)
        while (n != head) {
            var node = n
            val next = u32(node + 4)
            var nu = u16(node + 2)
            while (nu > 128) {
                insertNode(node, PPMD_NUM_INDEXES - 1)
                nu -= 128
                node += 128 * UNIT_SIZE
            }
            var i = u2i(nu)
            if (i2u(i) != nu) {
                val k = i2u(--i)
                insertNode(node + k * UNIT_SIZE, nu - k - 1)
            }
            insertNode(node, i)
            n = next
        }
    }

    private fun allocUnitsRare(indx: Int): Int {
        if (glueCount == 0) {
            glueFreeBlocks()
            if (freeList[indx] != 0) return removeNode(indx)
        }
        var i = indx
        while (true) {
            if (++i == PPMD_NUM_INDEXES) {
                val numBytes = u2b(i2u(indx))
                glueCount--
                return if (unitsStart - text > numBytes) {
                    unitsStart -= numBytes
                    unitsStart
                } else 0
            }
            if (freeList[i] != 0) break
        }
        val ret = removeNode(i)
        splitBlock(ret, i, indx)
        return ret
    }

    private fun allocUnits(indx: Int): Int {
        if (freeList[indx] != 0) return removeNode(indx)
        val numBytes = u2b(i2u(indx))
        if (numBytes <= hiUnit - loUnit) {
            val ret = loUnit
            loUnit += numBytes
            return ret
        }
        return allocUnitsRare(indx)
    }

    private fun shrinkUnits(oldPtr: Int, oldNU: Int, newNU: Int): Int {
        val i0 = u2i(oldNU)
        val i1 = u2i(newNU)
        if (i0 == i1) return oldPtr
        if (freeList[i1] != 0) {
            val ptr = removeNode(i1)
            System.arraycopy(mem, oldPtr, mem, ptr, newNU * UNIT_SIZE)
            insertNode(oldPtr, i0)
            return ptr
        }
        splitBlock(oldPtr, i0, i1)
        return oldPtr
    }

    private fun restartModel() {
        freeList.fill(0)
        text = alignOffset
        hiUnit = text + size
        unitsStart = hiUnit - (size / 8 / UNIT_SIZE) * 7 * UNIT_SIZE
        loUnit = unitsStart
        glueCount = 0

        orderFall = maxOrder
        initRL = -(if (maxOrder < 12) maxOrder else 12) - 1
        runLength = initRL
        prevSuccess = 0

        hiUnit -= UNIT_SIZE
        minContext = hiUnit
        maxContext = hiUnit
        setCtxSuffix(minContext, 0)
        setCtxNumStats(minContext, 256)
        setCtxSummFreq(minContext, 257)
        foundState = loUnit
        loUnit += u2b(128)
        setCtxStats(minContext, foundState)
        for (i in 0 until 256) {
            val s = foundState + i * 6
            setStSym(s, i)
            setStFreq(s, 1)
            setStSucc(s, 0)
        }

        for (i in 0 until 128) {
            for (k in 0 until 8) {
                val v = (PPMD_BIN_SCALE - K_INIT_BIN_ESC[k] / (i + 2)) and 0xFFFF
                var m = k
                while (m < 64) {
                    binSumm[i][m] = v
                    m += 8
                }
            }
        }

        for (i in 0 until 25) {
            for (k in 0 until 16) {
                val idx = i * 16 + k
                val sh = PPMD_PERIOD_BITS - 4
                seeShift[idx] = sh
                seeSumm[idx] = ((5 * i + 10) shl sh) and 0xFFFF
                seeCount[idx] = 4
            }
        }
    }

    private fun createSuccessors(skip: Boolean): Int {
        var c = minContext
        val upBranch = stSucc(foundState)
        val ps = IntArray(PPMD7_MAX_ORDER)
        var numPs = 0
        if (!skip) ps[numPs++] = foundState
        val foundSym = stSym(foundState)

        while (ctxSuffix(c) != 0) {
            c = ctxSuffix(c)
            val s = if (ctxNumStats(c) != 1) {
                var p = ctxStats(c)
                while (stSym(p) != foundSym) p += 6
                p
            } else {
                oneState(c)
            }
            val successor = stSucc(s)
            if (successor != upBranch) {
                c = successor
                if (numPs == 0) return c
                break
            }
            ps[numPs++] = s
        }

        val upSym = u8(upBranch)
        val upSucc = upBranch + 1
        val upFreq = if (ctxNumStats(c) == 1) {
            stFreq(oneState(c))
        } else {
            var s = ctxStats(c)
            while (stSym(s) != upSym) s += 6
            val cf = stFreq(s) - 1
            val s0 = ctxSummFreq(c) - ctxNumStats(c) - cf
            val extra = if (2 * cf <= s0) {
                if (5 * cf > s0) 1 else 0
            } else {
                (2 * cf + 3 * s0 - 1) / (2 * s0)
            }
            (1 + extra) and 0xFF
        }

        while (numPs != 0) {
            val c1 = when {
                hiUnit != loUnit -> {
                    hiUnit -= UNIT_SIZE
                    hiUnit
                }
                freeList[0] != 0 -> removeNode(0)
                else -> {
                    val a = allocUnitsRare(0)
                    if (a == 0) return 0
                    a
                }
            }
            setCtxNumStats(c1, 1)
            val os = oneState(c1)
            setStSym(os, upSym)
            setStFreq(os, upFreq)
            setStSucc(os, upSucc)
            setCtxSuffix(c1, c)
            setStSucc(ps[--numPs], c1)
            c = c1
        }
        return c
    }

    private fun updateModel() {
        var fSuccessor = stSucc(foundState)
        if (stFreq(foundState) < MAX_FREQ / 4 && ctxSuffix(minContext) != 0) {
            val c = ctxSuffix(minContext)
            if (ctxNumStats(c) == 1) {
                val s = oneState(c)
                val f = stFreq(s)
                if (f < 32) setStFreq(s, f + 1)
            } else {
                var s = ctxStats(c)
                val sym = stSym(foundState)
                if (stSym(s) != sym) {
                    do { s += 6 } while (stSym(s) != sym)
                    if (stFreq(s) >= stFreq(s - 6)) {
                        swapStates(s, s - 6)
                        s -= 6
                    }
                }
                if (stFreq(s) < MAX_FREQ - 9) {
                    setStFreq(s, stFreq(s) + 2)
                    setCtxSummFreq(c, ctxSummFreq(c) + 2)
                }
            }
        }

        if (orderFall == 0) {
            val next = createSuccessors(true)
            minContext = next
            maxContext = next
            if (minContext == 0) {
                restartModel()
                return
            }
            setStSucc(foundState, minContext)
            return
        }

        setU8(text++, stSym(foundState))
        var successor = text
        if (text >= unitsStart) {
            restartModel()
            return
        }

        if (fSuccessor != 0) {
            if (fSuccessor <= successor) {
                val cs = createSuccessors(false)
                if (cs == 0) {
                    restartModel()
                    return
                }
                fSuccessor = cs
            }
            if (--orderFall == 0) {
                successor = fSuccessor
                if (maxContext != minContext) text--
            }
        } else {
            setStSucc(foundState, successor)
            fSuccessor = minContext
        }

        val ns = ctxNumStats(minContext)
        val s0 = ctxSummFreq(minContext) - ns - (stFreq(foundState) - 1)

        var c = maxContext
        while (c != minContext) {
            val ns1 = ctxNumStats(c)
            if (ns1 != 1) {
                if ((ns1 and 1) == 0) {
                    val oldNU = ns1 ushr 1
                    val i = u2i(oldNU)
                    if (i != u2i(oldNU + 1)) {
                        val ptr = allocUnits(i + 1)
                        if (ptr == 0) {
                            restartModel()
                            return
                        }
                        val oldPtr = ctxStats(c)
                        System.arraycopy(mem, oldPtr, mem, ptr, oldNU * UNIT_SIZE)
                        insertNode(oldPtr, i)
                        setCtxStats(c, ptr)
                    }
                }
                var sfAdd = if (2 * ns1 < ns) 1 else 0
                if (4 * ns1 <= ns && ctxSummFreq(c) <= 8 * ns1) sfAdd += 2
                setCtxSummFreq(c, (ctxSummFreq(c) + sfAdd) and 0xFFFF)
            } else {
                val s = allocUnits(0)
                if (s == 0) {
                    restartModel()
                    return
                }
                copyState(s, oneState(c))
                setCtxStats(c, s)
                val f = stFreq(s)
                val nf = if (f < MAX_FREQ / 4 - 1) f shl 1 else MAX_FREQ - 4
                setStFreq(s, nf)
                setCtxSummFreq(c, (nf + initEsc + if (ns > 3) 1 else 0) and 0xFFFF)
            }

            val cf0 = 2L * stFreq(foundState).toLong() * (ctxSummFreq(c) + 6).toLong()
            val sf = s0.toLong() + ctxSummFreq(c).toLong()
            val cf: Int
            if (cf0 < 6L * sf) {
                cf = 1 + (if (cf0 > sf) 1 else 0) + (if (cf0 >= 4L * sf) 1 else 0)
                setCtxSummFreq(c, (ctxSummFreq(c) + 3) and 0xFFFF)
            } else {
                cf = 4 + (if (cf0 >= 9L * sf) 1 else 0) + (if (cf0 >= 12L * sf) 1 else 0) + (if (cf0 >= 15L * sf) 1 else 0)
                setCtxSummFreq(c, (ctxSummFreq(c) + cf) and 0xFFFF)
            }
            val s = ctxStats(c) + ns1 * 6
            setStSucc(s, successor)
            setStSym(s, stSym(foundState))
            setStFreq(s, cf)
            setCtxNumStats(c, (ns1 + 1) and 0xFFFF)
            c = ctxSuffix(c)
        }
        maxContext = fSuccessor
        minContext = fSuccessor
    }

    private fun rescale() {
        val stats = ctxStats(minContext)
        var s = foundState
        val tmp = ByteArray(6)
        System.arraycopy(mem, s, tmp, 0, 6)
        while (s != stats) {
            copyState(s, s - 6)
            s -= 6
        }
        System.arraycopy(tmp, 0, mem, s, 6)

        var escFreq = ctxSummFreq(minContext) - stFreq(s)
        val adder = if (orderFall != 0) 1 else 0
        setStFreq(s, ((stFreq(s) + 4 + adder) ushr 1) and 0xFF)
        var sumFreq = stFreq(s)

        var i = ctxNumStats(minContext) - 1
        while (i-- > 0) {
            s += 6
            escFreq -= stFreq(s)
            setStFreq(s, ((stFreq(s) + adder) ushr 1) and 0xFF)
            sumFreq += stFreq(s)
            if (stFreq(s) > stFreq(s - 6)) {
                var s1 = s
                System.arraycopy(mem, s1, tmp, 0, 6)
                val tf = tmp[1].toInt() and 0xFF
                do {
                    copyState(s1, s1 - 6)
                    s1 -= 6
                } while (s1 != stats && tf > stFreq(s1 - 6))
                System.arraycopy(tmp, 0, mem, s1, 6)
            }
        }

        if (stFreq(s) == 0) {
            val numStats = ctxNumStats(minContext)
            var zeroCount = 0
            do {
                zeroCount++
                s -= 6
            } while (stFreq(s) == 0)
            escFreq += zeroCount
            setCtxNumStats(minContext, numStats - zeroCount)
            if (ctxNumStats(minContext) == 1) {
                System.arraycopy(mem, stats, tmp, 0, 6)
                var tf = tmp[1].toInt() and 0xFF
                do {
                    tf -= tf ushr 1
                    escFreq = escFreq ushr 1
                } while (escFreq > 1)
                tmp[1] = tf.toByte()
                insertNode(stats, u2i((numStats + 1) ushr 1))
                foundState = oneState(minContext)
                System.arraycopy(tmp, 0, mem, foundState, 6)
                return
            }
            val n0 = (numStats + 1) ushr 1
            val n1 = (ctxNumStats(minContext) + 1) ushr 1
            if (n0 != n1) setCtxStats(minContext, shrinkUnits(stats, n0, n1))
        }
        setCtxSummFreq(minContext, (sumFreq + escFreq - (escFreq ushr 1)) and 0xFFFF)
        foundState = ctxStats(minContext)
    }

    private fun makeEscFreq(numMasked: Int): Pair<Int, Int> {
        val numStats = ctxNumStats(minContext)
        val nonMasked = numStats - numMasked
        if (numStats != 256) {
            val row = ns2Indx[nonMasked - 1]
            val col = (if (nonMasked < ctxNumStats(ctxSuffix(minContext)) - numStats) 1 else 0) +
                2 * (if (ctxSummFreq(minContext) < 11 * numStats) 1 else 0) +
                4 * (if (numMasked > nonMasked) 1 else 0) +
                hiBitsFlag
            val idx = row * 16 + col
            val r = seeSumm[idx] ushr seeShift[idx]
            seeSumm[idx] = (seeSumm[idx] - r) and 0xFFFF
            return idx to (r + if (r == 0) 1 else 0)
        }
        return DUMMY_SEE to 1
    }

    private fun seeUpdate(idx: Int) {
        if (seeShift[idx] < PPMD_PERIOD_BITS && --seeCount[idx] == 0) {
            seeSumm[idx] = (seeSumm[idx] shl 1) and 0xFFFF
            seeCount[idx] = (3 shl seeShift[idx]++) and 0xFF
        }
    }

    private fun nextContext() {
        val c = stSucc(foundState)
        if (orderFall == 0 && c > text) {
            minContext = c
            maxContext = c
        } else {
            updateModel()
        }
    }

    private fun update1() {
        var s = foundState
        setStFreq(s, stFreq(s) + 4)
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4)
        if (stFreq(s) > stFreq(s - 6)) {
            swapStates(s, s - 6)
            s -= 6
            foundState = s
            if (stFreq(s) > MAX_FREQ) rescale()
        }
        nextContext()
    }

    private fun update10() {
        prevSuccess = if (2 * stFreq(foundState) > ctxSummFreq(minContext)) 1 else 0
        runLength += prevSuccess
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4)
        val nf = stFreq(foundState) + 4
        setStFreq(foundState, nf)
        if (nf > MAX_FREQ) rescale()
        nextContext()
    }

    private fun updateBin() {
        val f = stFreq(foundState)
        setStFreq(foundState, f + if (f < 128) 1 else 0)
        prevSuccess = 1
        runLength++
        nextContext()
    }

    private fun update2() {
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4)
        val nf = stFreq(foundState) + 4
        setStFreq(foundState, nf)
        if (nf > MAX_FREQ) rescale()
        runLength = initRL
        updateModel()
    }

    private fun rangeGetThreshold(total: Long): Long {
        range = (range / total) and 0xFFFFFFFFL
        return ((code - low) and 0xFFFFFFFFL) / range
    }

    private fun rangeNormalize(readByte: () -> Int) {
        while (true) {
            val xorVal = (low xor ((low + range) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            if (xorVal >= K_TOP_VALUE) {
                if (range >= bottom) break
                range = ((-low.toInt()).toLong() and 0xFFFFFFFFL) and (bottom - 1L)
            }
            code = ((code shl 8) or (readByte().toLong() and 0xFFL)) and 0xFFFFFFFFL
            range = (range shl 8) and 0xFFFFFFFFL
            low = (low shl 8) and 0xFFFFFFFFL
        }
    }

    private fun rangeDecode(start: Long, sizeVal: Long, readByte: () -> Int) {
        low = (low + start * range) and 0xFFFFFFFFL
        range = (range * sizeVal) and 0xFFFFFFFFL
        rangeNormalize(readByte)
    }

    private fun rangeDecodeBit(size0: Long, readByte: () -> Int): Int {
        val v = rangeGetThreshold(PPMD_BIN_SCALE.toLong())
        return if (v < size0) {
            rangeDecode(0L, size0, readByte)
            0
        } else {
            rangeDecode(size0, PPMD_BIN_SCALE.toLong() - size0, readByte)
            1
        }
    }

    fun decodeSymbol(readByte: () -> Int): Int {
        val charMask = IntArray(256)
        if (ctxNumStats(minContext) != 1) {
            var s = ctxStats(minContext)
            val summFreq = ctxSummFreq(minContext)
            val count = rangeGetThreshold(summFreq.toLong()).toInt()
            var hiCnt = stFreq(s)
            if (count < hiCnt) {
                rangeDecode(0L, hiCnt.toLong(), readByte)
                foundState = s
                val sym = stSym(s)
                update10()
                return sym
            }
            prevSuccess = 0
            var i = ctxNumStats(minContext) - 1
            while (i-- > 0) {
                s += 6
                val sf = stFreq(s)
                hiCnt += sf
                if (hiCnt > count) {
                    rangeDecode((hiCnt - sf).toLong(), sf.toLong(), readByte)
                    foundState = s
                    val sym = stSym(s)
                    update1()
                    return sym
                }
            }
            if (count >= summFreq) return -2
            hiBitsFlag = hb2Flag[stSym(foundState)]
            rangeDecode(hiCnt.toLong(), (summFreq - hiCnt).toLong(), readByte)
            charMask.fill(-1)
            charMask[stSym(s)] = 0
            i = ctxNumStats(minContext) - 1
            while (i-- > 0) {
                s -= 6
                charMask[stSym(s)] = 0
            }
        } else {
            val os = oneState(minContext)
            val row = stFreq(os) - 1
            hiBitsFlag = hb2Flag[stSym(foundState)]
            val col = prevSuccess +
                ns2BsIndx[ctxNumStats(ctxSuffix(minContext)) - 1] +
                hiBitsFlag +
                2 * hb2Flag[stSym(os)] +
                ((runLength ushr 26) and 0x20)
            val prob = binSumm[row][col]
            if (rangeDecodeBit(prob.toLong(), readByte) == 0) {
                binSumm[row][col] = (prob + (1 shl PPMD_INT_BITS) - getMean(prob)) and 0xFFFF
                foundState = os
                val sym = stSym(os)
                updateBin()
                return sym
            }
            val newProb = (prob - getMean(prob)) and 0xFFFF
            binSumm[row][col] = newProb
            initEsc = PPMD7_K_EXP_ESCAPE[newProb ushr 10]
            charMask.fill(-1)
            charMask[stSym(os)] = 0
            prevSuccess = 0
        }

        val ps = IntArray(256)
        while (true) {
            val numMasked = ctxNumStats(minContext)
            do {
                orderFall++
                val suf = ctxSuffix(minContext)
                if (suf == 0) return -1
                minContext = suf
            } while (ctxNumStats(minContext) == numMasked)

            var hiCnt = 0
            var s = ctxStats(minContext)
            var i = 0
            val num = ctxNumStats(minContext) - numMasked
            do {
                val k = charMask[stSym(s)]
                hiCnt += stFreq(s) and k
                ps[i] = s
                s += 6
                i -= k
            } while (i != num)

            val (seeIdx, escFreq) = makeEscFreq(numMasked)
            val freqSum = escFreq + hiCnt
            val count = rangeGetThreshold(freqSum.toLong()).toInt()
            if (count < hiCnt) {
                var idx = 0
                var acc = 0
                while (true) {
                    val cur = ps[idx]
                    val f = stFreq(cur)
                    acc += f
                    if (acc > count) {
                        rangeDecode((acc - f).toLong(), f.toLong(), readByte)
                        seeUpdate(seeIdx)
                        foundState = cur
                        val sym = stSym(cur)
                        update2()
                        return sym
                    }
                    idx++
                }
            }
            if (count >= freqSum) return -2
            rangeDecode(hiCnt.toLong(), (freqSum - hiCnt).toLong(), readByte)
            seeSumm[seeIdx] = (seeSumm[seeIdx] + freqSum) and 0xFFFF
            while (i != 0) {
                charMask[stSym(ps[--i])] = 0
            }
        }
    }

    private fun getMean(summ: Int): Int =
        (summ + (1 shl (PPMD_PERIOD_BITS - 2))) ushr PPMD_PERIOD_BITS

    companion object {
        private const val K_TOP_VALUE = 1L shl 24
        private const val MAX_FREQ = 124
        private const val UNIT_SIZE = 12
        private const val PPMD_INT_BITS = 7
        private const val PPMD_PERIOD_BITS = 7
        private const val PPMD_BIN_SCALE = 1 shl (PPMD_INT_BITS + PPMD_PERIOD_BITS)
        private const val PPMD_NUM_INDEXES = 38
        private const val PPMD7_MAX_ORDER = 64
        private const val DUMMY_SEE = 25 * 16

        private val K_INIT_BIN_ESC = intArrayOf(
            0x3CDD, 0x1F3F, 0x59BF, 0x48F3, 0x64A1, 0x5ABC, 0x6632, 0x6051
        )
        private val PPMD7_K_EXP_ESCAPE = intArrayOf(
            25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2
        )
    }
}
