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
 * Implements Single Property Modifiers (SPRM) defined by the Microsoft Word (.doc) Binary File
 * Format ([MS-DOC] under the Microsoft Open Specifications Promise) and Apache POI HWPF
 * (https://poi.apache.org/, Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document.word

internal class DocBytes(val d: ByteArray) {
    fun u8(p: Int): Int = if (p >= 0 && p < d.size) d[p].toInt() and 0xFF else 0
    fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
    fun s16(p: Int): Int = u16(p).toShort().toInt()
    fun s32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)
    fun u32(p: Int): Long = s32(p).toLong() and 0xFFFFFFFFL
    val size get() = d.size
}

internal object DocSprms {
    private val ICO = intArrayOf(
        WShading.AUTO, 0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
        0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000080.toInt(), 0xFF008080.toInt(), 0xFF008000.toInt(), 0xFF800080.toInt(), 0xFF800000.toInt(),
        0xFF808000.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt()
    )

    fun ico(i: Int): Int = ICO.getOrElse(i) { WShading.AUTO }

    fun colorRef(b: DocBytes, p: Int): Int {
        if (b.u8(p + 3) == 0xFF) return WShading.AUTO
        return (0xFF shl 24) or (b.u8(p) shl 16) or (b.u8(p + 1) shl 8) or b.u8(p + 2)
    }

    fun brc80(b: DocBytes, p: Int): WBorder? {
        if (b.s32(p) == -1) return WBorder.NONE
        val w = b.u8(p); val t = b.u8(p + 1); val c = b.u8(p + 2); val f = b.u8(p + 3)
        return WBorder(t, w, ico(c), f and 0x1F, f and 0x20 != 0)
    }

    fun brc(b: DocBytes, p: Int): WBorder? {
        if (b.s32(p) == -1 && b.s32(p + 4) == -1) return WBorder.NONE
        val w = b.u8(p + 4); val t = b.u8(p + 5); val f = b.u8(p + 6)
        return WBorder(t, w, colorRef(b, p), f and 0x1F, f and 0x20 != 0)
    }

    fun shd80(v: Int): WShading = WShading(ico(v and 0x1F), ico((v ushr 5) and 0x1F), (v ushr 10) and 0x3F)

    fun shd(b: DocBytes, p: Int): WShading = WShading(colorRef(b, p), colorRef(b, p + 4), b.u16(p + 8))

    inline fun forEach(b: DocBytes, start: Int, len: Int, f: (op: Int, p: Int, n: Int) -> Unit) {
        var i = start
        val end = minOf(start + len, b.size)
        while (i + 2 <= end) {
            val op = b.u16(i)
            i += 2
            val n: Int
            var p = i
            when ((op ushr 13) and 7) {
                0, 1 -> n = 1
                2, 4, 5 -> n = 2
                3 -> n = 4
                7 -> n = 3
                else -> {
                    if (op == 0xD608 || op == 0xD606) {
                        n = maxOf(0, b.u16(i) - 1); p = i + 2
                    } else if (op == 0xC615) {
                        val cb = b.u8(i)
                        if (cb != 255) { n = cb; p = i + 1 } else {
                            val del = b.u8(i + 1)
                            val ins = b.u8(i + 2 + del * 4)
                            n = 1 + del * 4 + 1 + ins * 3; p = i + 1
                        }
                    } else {
                        n = b.u8(i); p = i + 1
                    }
                }
            }
            if (p + n > end) return
            f(op, p, n)
            i = p + n
        }
    }

    private fun toggle(v: Int, style: Boolean): Boolean = when (v) { 0 -> false; 1 -> true; 0x80 -> style; 0x81 -> !style; else -> v and 1 != 0 }

    fun applyChp(c: CharProps, b: DocBytes, start: Int, len: Int, style: CharProps, plain: (() -> CharProps)? = null) {
        forEach(b, start, len) { op, p, n ->
            when (op) {
                0x0835 -> c.bold = toggle(b.u8(p), style.bold)
                0x0836 -> c.italic = toggle(b.u8(p), style.italic)
                0x0837 -> c.strike = toggle(b.u8(p), style.strike)
                0x0838 -> c.outline = toggle(b.u8(p), style.outline)
                0x0839 -> c.shadow = toggle(b.u8(p), style.shadow)
                0x083A -> c.smallCaps = toggle(b.u8(p), style.smallCaps)
                0x083B -> c.caps = toggle(b.u8(p), style.caps)
                0x083C -> c.hidden = toggle(b.u8(p), style.hidden)
                0x2A53 -> c.dstrike = toggle(b.u8(p), style.dstrike)
                0x0854 -> c.imprint = toggle(b.u8(p), style.imprint)
                0x0858 -> c.emboss = toggle(b.u8(p), style.emboss)
                0x085C -> c.boldBi = toggle(b.u8(p), style.boldBi)
                0x085D -> c.italicBi = toggle(b.u8(p), style.italicBi)
                0x2A3E -> c.underline = b.u8(p)
                0x2A42 -> c.color = ico(b.u8(p))
                0x6870 -> c.color = colorRef(b, p)
                0x6877 -> c.underlineColor = colorRef(b, p)
                0x2A0C -> c.highlight = b.u8(p)
                0x4A43 -> c.size = b.u16(p)
                0x4A61 -> c.sizeBi = b.u16(p)
                0x2A44 -> { val v = b.u8(p); c.size = maxOf(2, c.size + ((if (v and 0x80 != 0) v - 256 else v) and -2)) }
                0x4845 -> c.position = b.s16(p)
                0x2A48 -> c.vertAlign = b.u8(p)
                0x4A4F -> c.fontAscii = b.u16(p)
                0x4A50 -> c.fontEa = b.u16(p)
                0x4A51 -> c.fontOther = b.u16(p)
                0x4A5E -> c.fontBi = b.u16(p)
                0x4A3D -> { val f = b.u16(p); c.fontAscii = f; c.fontEa = f; c.fontOther = f }
                0x8840 -> c.spacing = b.s16(p)
                0x4852 -> c.scale = b.u16(p).takeIf { it in 1..600 } ?: 100
                0x484B -> c.kern = b.u16(p)
                0x0800 -> c.deleted = b.u8(p) != 0
                0x0802 -> c.fldVanish = b.u8(p) != 0
                0x6A03 -> c.picLocation = b.s32(p)
                0x680E -> c.objectId = b.s32(p)
                0x0806 -> c.data = b.u8(p) != 0
                0x080A -> c.ole2 = b.u8(p) != 0
                0x0855 -> c.special = b.u8(p) != 0
                0x4866 -> c.shading = shd80(b.u16(p))
                0xCA71 -> if (n >= 10) c.shading = shd(b, p)
                0x6865 -> c.border = brc80(b, p)
                0xCA72 -> if (n >= 8) c.border = brc(b, p)
                0x6A09 -> { c.symbolFont = b.u16(p); c.symbolChar = b.u16(p + 2) }
                0x0882 -> c.complex = b.u8(p) != 0
                0x085A -> c.rtl = b.u8(p) != 0
                0x486D, 0x4873 -> c.lid = b.u16(p)
                0x485F -> if (c.lid == 0) c.lid = b.u16(p)
                0x2A33 -> if (plain != null) {
                    val s = plain()
                    val keepSpecial = c.special; val keepPic = c.picLocation; val keepData = c.data; val keepOle = c.ole2; val keepObj = c.objectId
                    c.fontAscii = s.fontAscii; c.fontEa = s.fontEa; c.fontOther = s.fontOther; c.fontBi = s.fontBi; c.size = s.size; c.sizeBi = s.sizeBi
                    c.bold = s.bold; c.italic = s.italic; c.strike = s.strike; c.dstrike = s.dstrike; c.caps = s.caps; c.smallCaps = s.smallCaps
                    c.hidden = s.hidden; c.underline = s.underline; c.color = s.color; c.highlight = s.highlight; c.shading = s.shading
                    c.border = s.border; c.vertAlign = s.vertAlign; c.position = s.position; c.spacing = s.spacing; c.scale = s.scale; c.kern = s.kern
                    c.special = keepSpecial; c.picLocation = keepPic; c.data = keepData; c.ole2 = keepOle; c.objectId = keepObj
                }
            }
        }
    }

    private fun applyTabs(pp: ParaProps, b: DocBytes, p: Int, n: Int, close: Boolean) {
        var q = p
        val del = b.u8(q); q++
        val dels = IntArray(del) { b.s16(q + it * 2) }
        q += del * 2
        val tolerance = if (close) IntArray(del) { b.s16(q + it * 2) } else null
        if (close) q += del * 2
        val add = b.u8(q); q++
        val adds = IntArray(add) { b.s16(q + it * 2) }
        q += add * 2
        val tbds = IntArray(add) { b.u8(q + it) }
        val list = pp.tabs
        for (k in 0 until del) {
            val d = dels[k]; val t = tolerance?.get(k) ?: 0
            list.removeAll { it.pos >= d - t && it.pos <= d + t }
        }
        for (k in 0 until add) {
            list.removeAll { it.pos == adds[k] }
            list.add(WTab(adds[k], tbds[k] and 7, (tbds[k] ushr 3) and 7))
        }
        list.sortBy { it.pos }
    }

    fun applyPap(pp: ParaProps, b: DocBytes, start: Int, len: Int, data: DocBytes?) {
        forEach(b, start, len) { op, p, n ->
            when (op) {
                0x4600 -> pp.istd = b.u16(p)
                0x2403 -> pp.jc = b.u8(p)
                0x2461 -> pp.jc = b.u8(p)
                0x2405 -> pp.keep = b.u8(p) != 0
                0x2406 -> pp.keepNext = b.u8(p) != 0
                0x2407 -> pp.pageBreakBefore = b.u8(p) != 0
                0x260A -> pp.ilvl = b.u8(p)
                0x460B -> pp.ilfo = b.s16(p)
                0x2416 -> pp.inTable = b.u8(p) != 0
                0x2417 -> pp.ttp = b.u8(p) != 0
                0x6649 -> pp.itap = b.s32(p)
                0x664A -> pp.itap += b.s32(p)
                0x244B -> pp.innerCell = b.u8(p) != 0
                0x244C -> pp.innerTtp = b.u8(p) != 0
                0x840F, 0x845E -> pp.indLeft = b.s16(p)
                0x840E, 0x845D -> pp.indRight = b.s16(p)
                0x8411, 0x8460 -> pp.indFirst = b.s16(p)
                0x4610, 0x465F -> pp.indLeft = maxOf(0, pp.indLeft + b.s16(p))
                0xA413 -> pp.spBefore = b.u16(p)
                0xA414 -> pp.spAfter = b.u16(p)
                0x245B -> pp.beforeAuto = b.u8(p) != 0
                0x245C -> pp.afterAuto = b.u8(p) != 0
                0x6412 -> { pp.lineValue = b.s16(p); pp.lineMult = b.s16(p + 2) != 0 }
                0xC60D -> applyTabs(pp, b, p, n, false)
                0xC615 -> applyTabs(pp, b, p, n, true)
                0x6424 -> pp.borders[0] = brc80(b, p)
                0x6425 -> pp.borders[1] = brc80(b, p)
                0x6426 -> pp.borders[2] = brc80(b, p)
                0x6427 -> pp.borders[3] = brc80(b, p)
                0x6428 -> pp.borders[4] = brc80(b, p)
                0xC64E -> if (n >= 8) pp.borders[0] = brc(b, p)
                0xC64F -> if (n >= 8) pp.borders[1] = brc(b, p)
                0xC650 -> if (n >= 8) pp.borders[2] = brc(b, p)
                0xC651 -> if (n >= 8) pp.borders[3] = brc(b, p)
                0xC652 -> if (n >= 8) pp.borders[4] = brc(b, p)
                0x442D -> pp.shading = shd80(b.u16(p))
                0xC64D -> if (n >= 10) pp.shading = shd(b, p)
                0x2431 -> pp.widowControl = b.u8(p) != 0
                0x242A -> pp.noAutoHyph = b.u8(p) != 0
                0x2441 -> pp.bidi = b.u8(p) != 0
                0x246D -> pp.contextualSpacing = b.u8(p) != 0
                0x2640 -> pp.outlineLvl = b.u8(p)
                0x8418 -> { pp.frameX = b.s16(p); pp.frame = true }
                0x8419 -> { pp.frameY = b.s16(p); pp.frame = true }
                0x841A -> { pp.frameW = b.u16(p); pp.frame = true }
                0x261B -> { pp.framePc = b.u8(p); pp.frame = true }
                0x2423 -> pp.frameWrap = b.u8(p)
                0x442B -> { val v = b.u16(p); pp.frameH = v and 0x7FFF; pp.frameMinH = v and 0x8000 != 0 }
                0x442C -> { val v = b.u16(p); pp.dropCap = v and 7; pp.dropLines = (v ushr 3) and 0x1F }
                0x842E -> pp.frameFromY = b.u16(p)
                0x842F -> pp.frameFromX = b.u16(p)
                0x6646 -> if (data != null) {
                    val off = b.s32(p)
                    if (off >= 0 && off + 2 <= data.size) {
                        val cb = data.u16(off)
                        applyPap(pp, data, off + 2, cb, null)
                    }
                }
                else -> if ((op ushr 10) and 7 == 5) {
                    val tp = pp.table ?: TableProps().also { pp.table = it }
                    applyTapOne(tp, op, b, p, n)
                }
            }
        }
    }

    fun applyTap(tp: TableProps, b: DocBytes, start: Int, len: Int) {
        forEach(b, start, len) { op, p, n -> applyTapOne(tp, op, b, p, n) }
    }

    private fun cells(tp: TableProps, first: Int, lim: Int): IntRange {
        val n = tp.cellCount
        val a = first.coerceIn(0, n)
        val z = lim.coerceIn(a, n)
        return a until z
    }

    private fun applyTapOne(tp: TableProps, op: Int, b: DocBytes, p: Int, n: Int) {
        when (op) {
            0xD608, 0xD606 -> {
                val itc = b.u8(p)
                if (n < 1 + (itc + 1) * 2) return
                tp.centers = IntArray(itc + 1) { b.s16(p + 1 + it * 2) }
                tp.cellFlags = IntArray(itc)
                tp.cellWidths = IntArray(itc)
                tp.cellBorders = Array(itc) { arrayOfNulls<WBorder>(4) }
                tp.cellShading = arrayOfNulls(itc)
                tp.cellPadding = Array(itc) { intArrayOf(-1, -1, -1, -1) }
                val tcBase = p + 1 + (itc + 1) * 2
                val avail = (n - 1 - (itc + 1) * 2) / 20
                for (i in 0 until minOf(itc, avail)) {
                    val q = tcBase + i * 20
                    tp.cellFlags[i] = b.u16(q)
                    tp.cellWidths[i] = b.u16(q + 2)
                    for (k in 0 until 4) tp.cellBorders[i][k] = if (b.s32(q + 4 + k * 4) == 0) null else brc80(b, q + 4 + k * 4)
                }
            }
            0x5400, 0x548A -> tp.jc = b.u16(p) and 0xFF
            0x9601 -> { if (tp.centers.isNotEmpty()) { val d = b.s16(p) - tp.centers[0]; for (i in tp.centers.indices) tp.centers[i] += d }; tp.dxaLeft = b.s16(p) }
            0x9602 -> { val g = b.s16(p); if (tp.centers.isNotEmpty()) tp.centers[0] += tp.gapHalf - g; tp.gapHalf = g }
            0x3403, 0x3466 -> tp.cantSplit = b.u8(p) != 0
            0x3404 -> tp.header = b.u8(p) != 0
            0x9407 -> tp.rowHeight = b.s16(p)
            0x3615 -> {}
            0x560B -> tp.bidi = b.u16(p) != 0
            0xD605 -> for (k in 0 until minOf(6, n / 4)) tp.tableBorders[k] = brc80(b, p + k * 4)
            0xD613 -> for (k in 0 until minOf(6, n / 8)) tp.tableBorders[k] = brc(b, p + k * 8)
            0xD609 -> { tp.ensure(n / 2); for (i in 0 until minOf(tp.cellShading.size, n / 2)) tp.cellShading[i] = shd80(b.u16(p + i * 2)) }
            0xD612 -> { for (i in 0 until n / 10) { if (i < tp.cellShading.size) tp.cellShading[i] = shd(b, p + i * 10) } }
            0xD616 -> { for (i in 0 until n / 10) { val c = 22 + i; if (c < tp.cellShading.size) tp.cellShading[c] = shd(b, p + i * 10) } }
            0xD60C -> { for (i in 0 until n / 10) { val c = 44 + i; if (c < tp.cellShading.size) tp.cellShading[c] = shd(b, p + i * 10) } }
            0xD620, 0xD62F -> {
                val first = b.u8(p); val lim = b.u8(p + 1); val bta = b.u8(p + 2)
                val br = if (op == 0xD620) brc80(b, p + 3) else brc(b, p + 3)
                for (i in cells(tp, first, lim)) {
                    if (bta and 1 != 0) tp.cellBorders[i][0] = br
                    if (bta and 2 != 0) tp.cellBorders[i][1] = br
                    if (bta and 4 != 0) tp.cellBorders[i][2] = br
                    if (bta and 8 != 0) tp.cellBorders[i][3] = br
                }
            }
            0xD634, 0xD632 -> {
                val first = b.u8(p); val lim = b.u8(p + 1); val grf = b.u8(p + 2); val fts = b.u8(p + 3); val w = b.u16(p + 4)
                val v = if (fts == 3 || fts == 0) w else w
                val sides = intArrayOf(1, 2, 4, 8)
                if (op == 0xD634) {
                    for (k in 0 until 4) if (grf and sides[k] != 0) tp.defaultPadding[k] = v
                } else {
                    for (i in cells(tp, first, lim)) for (k in 0 until 4) if (grf and sides[k] != 0) tp.cellPadding[i][k] = v
                }
            }
            0xD62B -> {
                val i = b.u8(p); val vm = b.u8(p + 1)
                if (i < tp.cellFlags.size) {
                    var f = tp.cellFlags[i] and (0x20 or 0x40).inv()
                    if (vm == 1) f = f or 0x20
                    if (vm == 3) f = f or 0x20 or 0x40
                    tp.cellFlags[i] = f
                }
            }
            0xD62C -> {
                val first = b.u8(p); val lim = b.u8(p + 1); val va = b.u8(p + 2)
                for (i in cells(tp, first, lim)) tp.cellFlags[i] = (tp.cellFlags[i] and (3 shl 7).inv()) or ((va and 3) shl 7)
            }
            0xD635 -> {
                val first = b.u8(p); val lim = b.u8(p + 1); val fts = b.u8(p + 2); val w = b.u16(p + 3)
                for (i in cells(tp, first, lim)) { tp.cellWidths[i] = w; tp.cellFlags[i] = (tp.cellFlags[i] and (7 shl 9).inv()) or ((fts and 7) shl 9) }
            }
            0x7621 -> {
                val at = b.u8(p); val cnt = b.u8(p + 1); val w = b.s16(p + 2)
                val old = tp.centers
                if (old.isEmpty() || cnt <= 0) return
                val a = at.coerceIn(0, old.size - 1)
                val c = IntArray(old.size + cnt)
                for (i in 0..a) c[i] = old[i]
                for (k in 1..cnt) c[a + k] = old[a] + w * k
                for (i in a + 1 until old.size) c[i + cnt] = old[i] + w * cnt
                val flags = tp.cellFlags.toMutableList(); val widths = tp.cellWidths.toMutableList()
                repeat(cnt) { flags.add(minOf(a, flags.size), 0); widths.add(minOf(a, widths.size), w) }
                tp.centers = c
                tp.cellFlags = flags.toIntArray(); tp.cellWidths = widths.toIntArray()
                val nb = tp.cellBorders.toMutableList(); val ns = tp.cellShading.toMutableList(); val np = tp.cellPadding.toMutableList()
                repeat(cnt) { nb.add(minOf(a, nb.size), arrayOfNulls(4)); ns.add(minOf(a, ns.size), null); np.add(minOf(a, np.size), intArrayOf(-1, -1, -1, -1)) }
                tp.cellBorders = nb.toTypedArray(); tp.cellShading = ns.toTypedArray(); tp.cellPadding = np.toTypedArray()
            }
            0x5622 -> {
                val r = cells(tp, b.u8(p), b.u8(p + 1))
                if (r.isEmpty()) return
                val cnt = r.last - r.first + 1
                val old = tp.centers
                val width = old[r.last + 1] - old[r.first]
                val c = IntArray(old.size - cnt)
                for (i in 0..r.first) c[i] = old[i]
                for (i in r.last + 2 until old.size) c[i - cnt] = old[i] - width
                tp.centers = c
                tp.cellFlags = tp.cellFlags.filterIndexed { i, _ -> i !in r }.toIntArray()
                tp.cellWidths = tp.cellWidths.filterIndexed { i, _ -> i !in r }.toIntArray()
                tp.cellBorders = tp.cellBorders.filterIndexed { i, _ -> i !in r }.toTypedArray()
                tp.cellShading = tp.cellShading.filterIndexed { i, _ -> i !in r }.toTypedArray()
                tp.cellPadding = tp.cellPadding.filterIndexed { i, _ -> i !in r }.toTypedArray()
            }
            0x7623 -> {
                val r = cells(tp, b.u8(p), b.u8(p + 1)); val w = b.s16(p + 2)
                for (i in r) {
                    val delta = w - (tp.centers[i + 1] - tp.centers[i])
                    for (k in i + 1 until tp.centers.size) tp.centers[k] += delta
                }
            }
            0x5624 -> {
                val r = cells(tp, b.u8(p), b.u8(p + 1))
                for (i in r) tp.cellFlags[i] = tp.cellFlags[i] or (if (i == r.first) 1 else 2)
            }
            0x5625 -> {
                val r = cells(tp, b.u8(p), b.u8(p + 1))
                for (i in r) tp.cellFlags[i] = tp.cellFlags[i] and 3.inv()
            }
            0xF617 -> tp.widthBefore = if (b.u8(p) == 3) b.u16(p + 1) else 0
            0xF618 -> tp.widthAfter = if (b.u8(p) == 3) b.u16(p + 1) else 0
            0xD61A, 0xD61B, 0xD61C, 0xD61D -> {
                val side = when (op) { 0xD61A -> 0; 0xD61B -> 1; 0xD61C -> 2; else -> 3 }
                for (i in 0 until minOf(tp.cellCount, n / 4)) {
                    val c = colorRef(b, p + i * 4)
                    val old = tp.cellBorders.getOrNull(i)?.getOrNull(side) ?: continue
                    tp.cellBorders[i][side] = WBorder(old.type, old.width8, c, old.space, old.shadow)
                }
            }
            0xD62D, 0xD62E -> {
                val first = b.u8(p); val lim = b.u8(p + 1)
                if (n >= 12) { val sh = shd(b, p + 2); for (i in cells(tp, first, lim)) if (op == 0xD62D || (i - first) % 2 == 0) tp.cellShading[i] = sh }
            }
            0xD660 -> if (n >= 10) tp.tableShading = shd(b, p)
            0xF661 -> { tp.tableIndent = if (b.u8(p) == 3) b.s16(p + 1) else 0; tp.hasIndent = true }
            0xD632, 0xD633 -> {}
        }
    }

    fun applySep(sp: SectionProps, b: DocBytes, start: Int, len: Int) {
        forEach(b, start, len) { op, p, _ ->
            when (op) {
                0x3009 -> sp.breakCode = b.u8(p)
                0x300A -> sp.titlePage = b.u8(p) != 0
                0x500B -> sp.columns = b.u16(p) + 1
                0x900C -> sp.columnSpacing = b.s16(p)
                0xB01F -> sp.pageWidth = b.u16(p)
                0xB020 -> sp.pageHeight = b.u16(p)
                0xB021 -> sp.marginLeft = b.u16(p)
                0xB022 -> sp.marginRight = b.u16(p)
                0x9023 -> sp.marginTop = b.s16(p)
                0x9024 -> sp.marginBottom = b.s16(p)
                0xB017 -> sp.headerTop = b.u16(p)
                0xB018 -> sp.footerBottom = b.u16(p)
                0xB025 -> sp.gutter = b.u16(p)
                0x301D -> sp.landscape = b.u8(p) == 2
                0x3013 -> sp.verticalAlign = b.u8(p)
                0x300E -> sp.pageNumberFormat = b.u8(p)
                0x501C -> sp.pageNumberStart = b.u16(p)
                0x3011 -> sp.restartPageNumbers = b.u8(p) != 0
                0x3005 -> sp.evenlySpaced = b.u8(p) != 0
                0x3019 -> sp.lineBetween = b.u8(p) != 0
                0xF203 -> { val i = b.u8(p); if (i < 45) { if (sp.columnWidths.size <= i) sp.columnWidths = sp.columnWidths.copyOf(i + 1); sp.columnWidths[i] = b.u16(p + 1) } }
                0xF204 -> { val i = b.u8(p); if (i < 45) { if (sp.columnSpacings.size <= i) sp.columnSpacings = sp.columnSpacings.copyOf(i + 1); sp.columnSpacings[i] = b.u16(p + 1) } }
                0x702B -> sp.pageBorders[0] = brc80(b, p)
                0x702C -> sp.pageBorders[1] = brc80(b, p)
                0x702D -> sp.pageBorders[2] = brc80(b, p)
                0x702E -> sp.pageBorders[3] = brc80(b, p)
                0xD234 -> sp.pageBorders[0] = brc(b, p)
                0xD235 -> sp.pageBorders[1] = brc(b, p)
                0xD236 -> sp.pageBorders[2] = brc(b, p)
                0xD237 -> sp.pageBorders[3] = brc(b, p)
            }
        }
    }
}
