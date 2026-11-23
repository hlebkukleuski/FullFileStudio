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
 * Implements the Microsoft Word (.doc) Binary File Format ([MS-DOC] under the Microsoft Open
 * Specifications Promise) with FIB/piece-table/PLCF structure constants informed by Apache POI
 * HWPF (https://poi.apache.org/, Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package com.omnireader.core.engine.word

import android.graphics.Bitmap
import com.omnireader.core.engine.impl.EmbeddedImageScanner
import com.omnireader.core.engine.impl.PptBlipDecoder
import java.nio.charset.Charset

internal class DocFormatException(msg: String) : Exception(msg)

internal class DocReader(wordBytes: ByteArray, tableBytes: ByteArray?, dataBytes: ByteArray?, private val maxImageDim: Int = 2048) {
    private val w = DocBytes(wordBytes)
    private val t: DocBytes
    private val data = dataBytes?.let { DocBytes(it) }
    private val cp1252 = Charset.forName("windows-1252")

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean, val prm: Int)
    private class FcRun(val fcStart: Int, val fcEnd: Int, val off: Int, val len: Int, val istd: Int = 0)
    private class Style(val name: String, val stk: Int, val base: Int, val papx: ByteArray?, val chpx: ByteArray?)
    private class Lvl(val start: Int, val nfc: Int, val jc: Int, val legal: Boolean, val noRestart: Boolean, val follow: Int, val papx: ByteArray, val chpx: ByteArray, val text: String, val restartLim: Int)
    private class Lst(val lsid: Int, val simple: Boolean, val levels: Array<Lvl?>)
    private class LfoOverride(val level: Int, val startAt: Int?, val lvl: Lvl?)
    private class Lfo(val lsid: Int, val overrides: List<LfoOverride>)
    private class PRec(val cpStart: Int, val cpEnd: Int, val pap: ParaProps, val mark: CharProps, val inlines: List<WInline>, val endChar: Int)

    private val nFib: Int
    private val nFibNew: Int
    private var htmlSpacing = false
    private val flags: Int
    private val ccp = IntArray(8)
    private val fcLcb: IntArray
    private val pieces = ArrayList<Piece>()
    private val prc = ArrayList<ByteArray>()
    private val chpx = ArrayList<FcRun>()
    private val papx = ArrayList<FcRun>()
    private val styles = ArrayList<Style?>()
    private val stdFonts = IntArray(3)
    val fonts = ArrayList<String>()
    private val lists = HashMap<Int, Lst>()
    private val lfos = ArrayList<Lfo>()
    private val stylePap = HashMap<Int, ParaProps>()
    private val styleChp = HashMap<Int, CharProps>()
    private val chpCache = HashMap<Long, CharProps>()
    private var facingPages = false
    private var defaultTab = 720
    private var autoHyphen = false
    private var autoHyphenCaps = true
    private var hotZoneTw = 360
    private var consecHyphLim = 0
    private val docLid: Int
    private val listCounters = HashMap<Int, IntArray>()
    private val lfoStarted = HashSet<Int>()
    private val footnoteLabels = HashMap<Int, String>()
    private val endnoteLabels = HashMap<Int, String>()
    private val commentOrder = HashMap<Int, Int>()
    private var fspaMain: Map<Int, Fspa> = emptyMap()
    private var fspaHdr: Map<Int, Fspa> = emptyMap()
    private var shapes: Map<Int, ShapeInfo> = emptyMap()
    private var blipStore: List<Int> = emptyList()
    private var txbxMain: IntArray = IntArray(0)
    private var txbxHdr: IntArray = IntArray(0)
    private var currentStoryHeader = false
    private val plain = StringBuilder()

    private class Fspa(val spid: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val bx: Int, val by: Int, val wr: Int, val wrk: Int, val below: Boolean)
    private class ShapeInfo(val spid: Int, val type: Int, val props: Map<Int, Int>, val complex: Map<Int, ByteArray>, val child: IntArray?, val groupRect: IntArray?) {
        var children: List<ShapeInfo> = emptyList()
    }

    init {
        if (w.size < 0x5C) throw DocFormatException("WordDocument stream is too short")
        val ident = w.u16(0)
        if (ident != 0xA5EC && ident != 0xA5DC) throw DocFormatException("Not a Word 97-2003 document")
        nFib = w.u16(2)
        docLid = w.u16(6)
        flags = w.u16(10)
        if (flags and 0x0100 != 0) throw DocFormatException("PASSWORD_REQUIRED: Document is encrypted or password protected.")
        if (nFib < 0xC0) throw DocFormatException("Word 6/95 and older documents are read as text only")
        t = DocBytes(tableBytes ?: throw DocFormatException("Missing table stream"))
        val csw = w.u16(32)
        val lwStart = 34 + csw * 2
        val cslw = w.u16(lwStart)
        val lw = lwStart + 2
        ccp[0] = w.s32(lw + 12); ccp[1] = w.s32(lw + 16); ccp[2] = w.s32(lw + 20); ccp[3] = w.s32(lw + 24)
        ccp[4] = w.s32(lw + 28); ccp[5] = w.s32(lw + 32); ccp[6] = w.s32(lw + 36); ccp[7] = w.s32(lw + 40)
        for (i in ccp.indices) if (ccp[i] < 0) ccp[i] = 0
        val fcStart = lw + cslw * 4 + 2
        val cb = w.u16(lw + cslw * 4)
        fcLcb = IntArray(cb * 2) { w.s32(fcStart + it * 4) }
        val newStart = fcStart + cb * 8
        nFibNew = if (w.u16(newStart) > 0) w.u16(newStart + 2) else nFib
        readClx()
        readBinTables()
        readStyles()
        readFonts()
        readLists()
        readDop()
    }

    private fun fc(i: Int): Int = if (i * 2 < fcLcb.size) fcLcb[i * 2] else 0
    private fun lcb(i: Int): Int = if (i * 2 + 1 < fcLcb.size) fcLcb[i * 2 + 1] else 0
    private fun validT(i: Int) = lcb(i) > 0 && fc(i) >= 0 && fc(i).toLong() + lcb(i) <= t.size

    private fun readClx() {
        if (validT(33)) {
            var p = fc(33)
            val end = p + lcb(33)
            while (p < end) {
                val type = t.u8(p)
                if (type == 1) {
                    val n = t.u16(p + 1)
                    prc.add(t.d.copyOfRange(p + 3, minOf(p + 3 + n, t.size)))
                    p += 3 + n
                } else if (type == 2) {
                    val len = t.s32(p + 1)
                    val base = p + 5
                    val count = (len - 4) / 12
                    if (count <= 0 || base + len > t.size) break
                    for (i in 0 until count) {
                        val a = t.s32(base + i * 4); val b = t.s32(base + (i + 1) * 4)
                        val pcd = base + (count + 1) * 4 + i * 8
                        val fcRaw = t.s32(pcd + 2)
                        val compressed = fcRaw and 0x40000000 != 0
                        val f = if (compressed) (fcRaw and 0x3FFFFFFF) / 2 else fcRaw
                        if (b > a) pieces.add(Piece(a, b, f, compressed, t.u16(pcd + 6)))
                    }
                    break
                } else break
            }
        }
        if (pieces.isEmpty()) {
            val fcMin = w.s32(24)
            val total = ccp.sum() + 1
            val ext = flags and 0x1000 != 0
            pieces.add(Piece(0, total, fcMin, !ext, 0))
        }
        pieces.sortBy { it.cpStart }
    }

    private fun readBinTables() {
        fun plc(i: Int, size: Int, f: (fcs: IntArray, k: Int, p: Int) -> Unit) {
            if (!validT(i)) return
            val base = fc(i); val n = (lcb(i) - 4) / (4 + size)
            if (n <= 0) return
            val fcs = IntArray(n + 1) { t.s32(base + it * 4) }
            for (k in 0 until n) f(fcs, k, base + (n + 1) * 4 + k * size)
        }
        plc(12, 4) { _, _, p ->
            val pn = t.s32(p) and 0x3FFFFF
            val page = pn * 512
            if (page < 0 || page + 512 > w.size) return@plc
            val crun = w.u8(page + 511)
            for (r in 0 until crun) {
                val a = w.s32(page + r * 4); val b = w.s32(page + (r + 1) * 4)
                val bOff = w.u8(page + (crun + 1) * 4 + r) * 2
                if (bOff == 0) { chpx.add(FcRun(a, b, 0, 0)); continue }
                val cb = w.u8(page + bOff)
                chpx.add(FcRun(a, b, page + bOff + 1, cb))
            }
        }
        plc(13, 4) { _, _, p ->
            val pn = t.s32(p) and 0x3FFFFF
            val page = pn * 512
            if (page < 0 || page + 512 > w.size) return@plc
            val cpara = w.u8(page + 511)
            for (r in 0 until cpara) {
                val a = w.s32(page + r * 4); val b = w.s32(page + (r + 1) * 4)
                val bOff = w.u8(page + (cpara + 1) * 4 + r * 13) * 2
                if (bOff == 0) { papx.add(FcRun(a, b, 0, 0, 0)); continue }
                var cb = w.u8(page + bOff)
                var q = page + bOff + 1
                val len = if (cb == 0) { cb = w.u8(q); q++; cb * 2 } else cb * 2 - 1
                val istd = w.u16(q)
                papx.add(FcRun(a, b, q + 2, maxOf(0, len - 2), istd))
            }
        }
        chpx.sortBy { it.fcStart }
        papx.sortBy { it.fcStart }
    }

    private fun readStyles() {
        if (!validT(1)) return
        val base = fc(1)
        val cbStshi = t.u16(base)
        val stshi = base + 2
        val cstd = t.u16(stshi)
        val cbBase = t.u16(stshi + 2)
        if (cbStshi >= 18) for (k in 0 until 3) stdFonts[k] = t.u16(stshi + 12 + k * 2)
        var p = stshi + cbStshi
        val end = base + lcb(1)
        for (i in 0 until cstd) {
            if (p + 2 > end) break
            val cb = t.u16(p)
            val s = p + 2
            p = s + cb
            if (cb == 0) { styles.add(null); continue }
            val stk = t.u16(s + 2) and 0xF
            val istdBase = t.u16(s + 2) ushr 4
            val cupx = t.u16(s + 4) and 0xF
            var q = s + cbBase
            val nameLen = t.u16(q)
            val sb = StringBuilder()
            for (k in 0 until minOf(nameLen, 256)) sb.append(t.u16(q + 2 + k * 2).toChar())
            q += 2 + (nameLen + 1) * 2
            var pap: ByteArray? = null
            var chp: ByteArray? = null
            for (u in 0 until cupx) {
                if ((q - s) and 1 != 0) q++
                val n = t.u16(q)
                val body = q + 2
                if (body + n > p) break
                when {
                    stk == 1 && u == 0 -> pap = t.d.copyOfRange(body + 2, body + maxOf(2, n))
                    stk == 1 && u == 1 -> chp = t.d.copyOfRange(body, body + n)
                    stk == 2 && u == 0 -> chp = t.d.copyOfRange(body, body + n)
                    stk == 3 && u == 1 -> pap = t.d.copyOfRange(body + 2, body + maxOf(2, n))
                    stk == 3 && u == 2 -> chp = t.d.copyOfRange(body, body + n)
                    stk == 4 && u == 0 -> pap = t.d.copyOfRange(body + 2, body + maxOf(2, n))
                }
                q = body + n
            }
            styles.add(Style(sb.toString(), stk, if (istdBase == 0xFFF) -1 else istdBase, pap, chp))
        }
    }

    private fun readFonts() {
        if (!validT(15)) return
        var p = fc(15)
        val end = p + lcb(15)
        var count = t.u16(p)
        p += 2
        if (count == 0xFFFF) { count = t.u16(p); p += 2 }
        p += 2
        for (i in 0 until count) {
            if (p >= end) break
            val cb = t.u8(p) + 1
            val ffn = p + 1
            val sb = StringBuilder()
            var q = ffn + 39
            while (q + 1 < p + cb) { val c = t.u16(q); if (c == 0) break; sb.append(c.toChar()); q += 2 }
            fonts.add(sb.toString())
            p += cb
        }
    }

    private fun readLvl(p0: Int): Pair<Lvl, Int> {
        var p = p0
        val start = t.s32(p)
        val nfc = t.u8(p + 4)
        val fl = t.u8(p + 5)
        val follow = t.u8(p + 15)
        val cbChpx = t.u8(p + 24)
        val cbPapx = t.u8(p + 25)
        val restartLim = t.u8(p + 26)
        p += 28
        val pap = t.d.copyOfRange(minOf(p, t.size), minOf(p + cbPapx, t.size)); p += cbPapx
        val chp = t.d.copyOfRange(minOf(p, t.size), minOf(p + cbChpx, t.size)); p += cbChpx
        val n = t.u16(p); p += 2
        val sb = StringBuilder()
        for (k in 0 until minOf(n, 512)) sb.append(t.u16(p + k * 2).toChar())
        p += n * 2
        return Lvl(start, nfc, fl and 3, fl and 4 != 0, fl and 8 != 0, follow, pap, chp, sb.toString(), restartLim) to p
    }

    private fun readLists() {
        val iLst = 73; val iLfo = 74
        if (validT(iLst)) {
            var p = fc(iLst)
            val n = t.s16(p); p += 2
            val heads = ArrayList<Triple<Int, Boolean, Int>>()
            for (i in 0 until maxOf(0, n)) {
                val q = p + i * 28
                heads.add(Triple(t.s32(q), t.u8(q + 26) and 1 != 0, q))
            }
            p += maxOf(0, n) * 28
            for ((lsid, simple, _) in heads) {
                val cnt = if (simple) 1 else 9
                val levels = arrayOfNulls<Lvl>(9)
                for (k in 0 until cnt) {
                    if (p + 28 > t.size) break
                    val (lvl, np) = readLvl(p)
                    levels[k] = lvl
                    p = np
                }
                lists[lsid] = Lst(lsid, simple, levels)
            }
        }
        if (validT(iLfo)) {
            var p = fc(iLfo)
            val n = t.s32(p); p += 4
            val heads = ArrayList<Pair<Int, Int>>()
            for (i in 0 until maxOf(0, minOf(n, 100000))) {
                val q = p + i * 16
                heads.add(t.s32(q) to t.u8(q + 12))
            }
            p += maxOf(0, n) * 16
            for ((lsid, clvl) in heads) {
                val ovs = ArrayList<LfoOverride>()
                if (clvl > 0) {
                    p += 4
                    for (k in 0 until clvl) {
                        val start = t.s32(p)
                        val fl = t.s32(p + 4)
                        p += 8
                        var lvl: Lvl? = null
                        if (fl and 0x20 != 0 && p + 28 <= t.size) { val r = readLvl(p); lvl = r.first; p = r.second }
                        ovs.add(LfoOverride(fl and 0xF, if (fl and 0x10 != 0) start else null, lvl))
                    }
                }
                lfos.add(Lfo(lsid, ovs))
            }
        }
    }

    private fun readDop() {
        if (!validT(31)) return
        val p = fc(31)
        val len = lcb(31)
        facingPages = t.u8(p) and 1 != 0
        if (len >= 8) {
            val f = t.s32(p + 4)
            autoHyphen = (f ushr 12) and 1 != 0
            autoHyphenCaps = (f ushr 11) and 1 != 0
        }
        if (len >= 16) {
            val hz = t.u16(p + 14)
            if (hz > 0) hotZoneTw = hz
        }
        if (len >= 18) consecHyphLim = t.u16(p + 16)
        if (len >= 88) htmlSpacing = nFibNew >= 0xD9 && (t.s32(p + 84) ushr 18) and 1 == 0
        val tab = t.u16(p + 10)
        if (tab in 1..31680) defaultTab = tab
    }

    private fun styleOf(istd: Int): Style? = styles.getOrNull(istd)

    private fun baseOf(istd: Int, s: Style): Int {
        if (s.base < 0 || s.base == istd) return -1
        val b = styleOf(s.base) ?: return -1
        if (b.stk == s.stk) return s.base
        return if (s.stk == 1 && istd != 0 && styleOf(0)?.stk == 1) 0 else -1
    }

    private fun pap(istd: Int, depth: Int = 0): ParaProps {
        stylePap[istd]?.let { return it }
        val s = styleOf(istd)
        val b = if (s != null && depth < 16) baseOf(istd, s) else -1
        val base = if (b >= 0) pap(b, depth + 1).copy() else ParaProps()
        base.istd = istd
        s?.papx?.let { DocSprms.applyPap(base, DocBytes(it), 0, it.size, data) }
        base.istd = istd
        base.table = null
        stylePap[istd] = base
        return base
    }

    private fun rootChp(): CharProps {
        val c = CharProps()
        c.fontAscii = stdFonts[0]; c.fontEa = stdFonts[1]; c.fontOther = stdFonts[2]; c.fontBi = stdFonts[2]
        return c
    }

    private fun chp(istd: Int, depth: Int = 0): CharProps {
        styleChp[istd]?.let { return it }
        val s = styleOf(istd)
        val b = if (s != null && depth < 16) baseOf(istd, s) else -1
        val base = if (b >= 0) chp(b, depth + 1).copy() else rootChp()
        s?.chpx?.let { val st = base.copy(); DocSprms.applyChp(base, DocBytes(it), 0, it.size, st) }
        styleChp[istd] = base
        return base
    }

    private fun charStyleChain(istd: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var cur = istd
        var guard = 0
        while (cur >= 0 && guard++ < 16) {
            val s = styleOf(cur) ?: break
            if (s.stk != 2) break
            s.chpx?.let { out.add(0, it) }
            cur = s.base
        }
        return out
    }


    private fun pieceAt(cp: Int): Piece? {
        var lo = 0; var hi = pieces.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val pc = pieces[m]
            if (cp < pc.cpStart) hi = m - 1 else if (cp >= pc.cpEnd) lo = m + 1 else return pc
        }
        return null
    }

    private fun fcOf(pc: Piece, cp: Int) = pc.fc + (cp - pc.cpStart) * (if (pc.compressed) 1 else 2)

    private fun runAt(runs: List<FcRun>, fc: Int): FcRun? {
        var lo = 0; var hi = runs.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val r = runs[m]
            if (fc < r.fcStart) hi = m - 1 else if (fc >= r.fcEnd) lo = m + 1 else return r
        }
        return null
    }

    private fun charAt(pc: Piece, cp: Int): Char {
        val f = fcOf(pc, cp)
        return if (pc.compressed) {
            val b = w.u8(f)
            if (b in 0x80..0x9F) String(byteArrayOf(b.toByte()), cp1252)[0] else b.toChar()
        } else w.u16(f).toChar()
    }

    private fun prmGrpprl(prm: Int): ByteArray? {
        if (prm and 1 == 0) return null
        return prc.getOrNull(prm ushr 1)
    }

    private fun charProps(istdPara: Int, run: FcRun?, prm: Int): CharProps {
        val key = (istdPara.toLong() shl 48) xor ((run?.off ?: 0).toLong() shl 16) xor prm.toLong()
        chpCache[key]?.let { return it }
        val styleBase = chp(istdPara)
        var c = styleBase.copy()
        var charStyle = -1
        val grp = run?.takeIf { it.len > 0 }
        if (grp != null) DocSprms.forEach(w, grp.off, grp.len) { op, p, _ -> if (op == 0x4A30) charStyle = w.u16(p) }
        prmGrpprl(prm)?.let { g -> DocSprms.forEach(DocBytes(g), 0, g.size) { op, p, _ -> if (op == 0x4A30) charStyle = DocBytes(g).u16(p) } }
        var ref = styleBase
        if (charStyle >= 0) {
            for (g in charStyleChain(charStyle)) { val st = c.copy(); DocSprms.applyChp(c, DocBytes(g), 0, g.size, st) }
            c.istd = charStyle
            ref = c.copy()
        }
        val refFinal = ref
        if (grp != null) DocSprms.applyChp(c, w, grp.off, grp.len, refFinal) { styleBase }
        prmGrpprl(prm)?.let { g -> DocSprms.applyChp(c, DocBytes(g), 0, g.size, refFinal) { styleBase } }
        chpCache[key] = c
        return c
    }

    private fun paraProps(markCp: Int): Pair<ParaProps, FcRun?> {
        val pc = pieceAt(markCp) ?: return ParaProps() to null
        val run = runAt(papx, fcOf(pc, markCp))
        val istd = run?.istd ?: 0
        val p = pap(istd).copy()
        if (run != null && run.len > 0) DocSprms.applyPap(p, w, run.off, run.len, data)
        prmGrpprl(pc.prm)?.let { DocSprms.applyPap(p, DocBytes(it), 0, it.size, data) }
        if (p.ilfo != 0) {
            val lvl = levelFor(p.ilfo, p.ilvl)
            if (lvl != null && lvl.papx.isNotEmpty()) {
                val q = pap(istd).copy()
                DocSprms.applyPap(q, DocBytes(lvl.papx), 0, lvl.papx.size, null)
                if (run != null && run.len > 0) DocSprms.applyPap(q, w, run.off, run.len, data)
                prmGrpprl(pc.prm)?.let { DocSprms.applyPap(q, DocBytes(it), 0, it.size, data) }
                q.table = p.table
                return q to run
            }
        }
        return p to run
    }

    private fun levelFor(ilfo: Int, ilvl: Int): Lvl? {
        val lfo = lfos.getOrNull(ilfo - 1) ?: return null
        val lv = ilvl.coerceIn(0, 8)
        lfo.overrides.firstOrNull { it.level == lv }?.lvl?.let { return it }
        val lst = lists[lfo.lsid] ?: return null
        return lst.levels[if (lst.simple) 0 else lv]
    }

    private fun roman(n0: Int, upper: Boolean): String {
        if (n0 <= 0 || n0 >= 4000) return n0.toString()
        var n = n0
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }

    private fun letters(n: Int, upper: Boolean): String {
        if (n <= 0) return n.toString()
        val ch = ((n - 1) % 26 + (if (upper) 'A'.code else 'a'.code)).toChar()
        return ch.toString().repeat((n - 1) / 26 + 1)
    }

    fun formatNumber(n: Int, nfc: Int): String = when (nfc) {
        1 -> roman(n, true)
        2 -> roman(n, false)
        3 -> letters(n, true)
        4 -> letters(n, false)
        5 -> n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
        22 -> if (n < 10) "0$n" else n.toString()
        255 -> ""
        else -> n.toString()
    }

    private fun listLabel(pr: WParagraph) {
        val p = pr.props
        if (p.ilfo <= 0) return
        val lfo = lfos.getOrNull(p.ilfo - 1) ?: return
        val lst = lists[lfo.lsid] ?: return
        val lv = if (lst.simple) 0 else p.ilvl.coerceIn(0, 8)
        val lvl = levelFor(p.ilfo, lv) ?: return
        val counters = listCounters.getOrPut(lst.lsid) { IntArray(9) { Int.MIN_VALUE } }
        if (lfoStarted.add(p.ilfo)) {
            for (o in lfo.overrides) if (o.startAt != null && o.level in 0..8) counters[o.level] = o.startAt - 1
            for (o in lfo.overrides) if (o.lvl != null && o.startAt == null && o.level in 0..8) counters[o.level] = o.lvl.start - 1
        }
        fun startOf(k: Int) = (levelFor(p.ilfo, k) ?: lst.levels[k])?.start ?: 1
        counters[lv] = if (counters[lv] == Int.MIN_VALUE) startOf(lv) else counters[lv] + 1
        for (k in lv + 1 until 9) {
            val l = levelFor(p.ilfo, k) ?: lst.levels[k]
            val lim = l?.restartLim ?: 0
            if (l?.noRestart == true) continue
            if (lim == 0 || lim > lv) counters[k] = Int.MIN_VALUE
        }
        val sb = StringBuilder()
        for (ch in lvl.text) {
            val c = ch.code
            if (c < 9) {
                val v = counters[c].let { if (it == Int.MIN_VALUE) startOf(c) else it }
                val nfcK = (levelFor(p.ilfo, c) ?: lst.levels[c])?.nfc ?: 0
                sb.append(formatNumber(v, if (lvl.legal && nfcK != 23) 0 else nfcK))
            } else sb.append(ch)
        }
        val lp = pr.markProps.copy()
        lp.underline = 0; lp.strike = false; lp.dstrike = false; lp.highlight = 0; lp.shading = null
        if (lvl.chpx.isNotEmpty()) DocSprms.applyChp(lp, DocBytes(lvl.chpx), 0, lvl.chpx.size, lp.copy())
        pr.listLabel = mapSymbolText(sb.toString(), fonts.getOrNull(lp.fontAscii))
        pr.listLabelProps = lp
        pr.listFollow = lvl.follow
        pr.listJc = lvl.jc
    }

    private class FieldState(var result: Boolean = false, val instr: StringBuilder = StringBuilder(), var suppress: Boolean = false, var kind: Int = 0, var hadObject: Boolean = false)

    private val fieldStack = ArrayList<FieldState>()

    private fun emitAllowed(): Boolean = fieldStack.all { it.result && !it.suppress }

    private fun fieldKind(instr: String): Int {
        val tok = instr.trim().split(Regex("\\s+")).firstOrNull()?.uppercase() ?: return 0
        return when (tok) { "PAGE" -> WInline.FIELD_PAGE; "NUMPAGES" -> WInline.FIELD_NUMPAGES; "SECTIONPAGES" -> WInline.FIELD_SECTIONPAGES; else -> 0 }
    }

    private fun fieldFormat(instr: String): Int {
        val u = instr.uppercase()
        return when {
            u.contains("\\* ROMAN") -> if (instr.contains("\\* roman")) 2 else 1
            u.contains("\\* ALPHABETIC") -> if (instr.contains("\\* alphabetic")) 4 else 3
            else -> 0
        }
    }

    private fun readParagraphs(from: Int, to: Int): List<PRec> {
        val out = ArrayList<PRec>()
        var cp = from
        fieldStack.clear()
        while (cp < to) {
            var end = cp
            var endChar = 0
            while (end < to) {
                val pc = pieceAt(end) ?: break
                val lim = minOf(pc.cpEnd, to)
                var found = false
                while (end < lim) {
                    val c = charAt(pc, end).code
                    end++
                    if (c == 0x0D || c == 0x07) { endChar = c; found = true; break }
                    if (c == 0x0C && (sectionEnds.contains(end) || end == to)) { endChar = c; found = true; break }
                }
                if (found) break
                if (lim == end && pieceAt(end) == null) break
            }
            if (end == cp) break
            out.add(buildParagraph(cp, end, endChar))
            cp = end
            if (out.size > 2_000_000) break
        }
        return mergeDeletedMarks(out)
    }

    private fun mergeDeletedMarks(list: List<PRec>): List<PRec> {
        if (list.none { it.endChar == 0x0D && it.mark.deleted }) return list
        val out = ArrayList<PRec>(list.size)
        var pending: PRec? = null
        for (p in list) {
            val head = pending
            pending = null
            val merged = if (head != null && head.pap.inTable == p.pap.inTable && head.pap.itap == p.pap.itap) {
                PRec(head.cpStart, p.cpEnd, p.pap, p.mark, head.inlines + p.inlines, p.endChar)
            } else {
                head?.let { out.add(it) }
                p
            }
            if (merged.endChar == 0x0D && merged.mark.deleted && !sectionEnds.contains(merged.cpEnd)) pending = merged else out.add(merged)
        }
        pending?.let { out.add(it) }
        return out
    }


    private var sectionEnds = HashSet<Int>()

    private fun buildParagraph(cpStart: Int, cpEnd: Int, endChar: Int): PRec {
        val markCp = cpEnd - 1
        val (pp, _) = paraProps(markCp)
        val markPiece = pieceAt(markCp)
        val markChp = markPiece?.let { charProps(pp.istd, runAt(chpx, fcOf(it, markCp)), it.prm) } ?: chp(pp.istd)
        val inlines = ArrayList<WInline>()
        val sb = StringBuilder()
        var sbProps: CharProps? = null
        fun flush() {
            if (sb.isNotEmpty() && sbProps != null) inlines.add(WInline.Text(sb.toString(), sbProps!!))
            sb.setLength(0)
        }
        fun emitText(s: String, c: CharProps) {
            if (sbProps != null && !sbProps!!.sameLook(c)) flush()
            sbProps = c
            sb.append(s)
        }
        var cp = cpStart
        val textEnd = if (endChar != 0) markCp else cpEnd
        while (cp < textEnd) {
            val pc = pieceAt(cp) ?: break
            val lim = minOf(pc.cpEnd, textEnd)
            while (cp < lim) {
                val f = fcOf(pc, cp)
                val run = runAt(chpx, f)
                val runEndCp = if (run != null) minOf(lim, pc.cpStart + (run.fcEnd - pc.fc + (if (pc.compressed) 0 else 1)) / (if (pc.compressed) 1 else 2)) else lim
                val segEnd = maxOf(cp + 1, minOf(lim, runEndCp))
                val c = charProps(pp.istd, run, pc.prm)
                for (k in cp until segEnd) {
                    val ch = charAt(pc, k)
                    val code = ch.code
                    if (code == 0x13) { fieldStack.add(FieldState()); continue }
                    if (code == 0x14 && fieldStack.isNotEmpty()) {
                        val fs = fieldStack.last()
                        fs.result = true
                        fs.kind = fieldKind(fs.instr.toString())
                        if (fs.kind != 0) {
                            fs.suppress = true
                            if (fieldStack.size == 1 || fieldStack.dropLast(1).all { it.result && !it.suppress }) { flush(); inlines.add(WInline.Field(fs.kind, c, fieldFormat(fs.instr.toString()))) }
                        }
                        continue
                    }
                    if (code == 0x15 && fieldStack.isNotEmpty()) {
                        val fs = fieldStack.removeAt(fieldStack.size - 1)
                        if (!fs.result) {
                            val kind = fieldKind(fs.instr.toString())
                            if (kind != 0 && emitAllowed()) { flush(); inlines.add(WInline.Field(kind, c, fieldFormat(fs.instr.toString()))) }
                        }
                        if (fs.hadObject && c.special && c.picLocation >= 0 && emitAllowed() && !c.hidden) {
                            picture(c)?.let { flush(); inlines.add(WInline.Picture(it, c)) }
                        }
                        continue
                    }
                    if (fieldStack.isNotEmpty() && !fieldStack.last().result) {
                        fieldStack.last().instr.append(ch)
                        continue
                    }
                    if (!emitAllowed()) continue
                    if (c.deleted || c.hidden) continue
                    if (c.special) {
                        when (code) {
                            0x01 -> if (c.ole2) { fieldStack.lastOrNull()?.hadObject = true } else { val img = picture(c); if (img != null) { flush(); inlines.add(WInline.Picture(img, c)) } }
                            0x08 -> { floatingAt(k)?.let { flush(); inlines.add(WInline.Anchor(it, c)) } }
                            0x02 -> {
                                flush()
                                val lab = footnoteLabels[k] ?: endnoteLabels[k]
                                if (lab != null) inlines.add(WInline.FootnoteRef(lab, c, noteIndexAt(k), endnoteLabels.containsKey(k)))
                                else currentNoteLabel?.let { inlines.add(WInline.FootnoteRef(it, c, -1, false)) }
                            }
                            0x05 -> {
                                commentOrder[k]?.let { idx -> flush(); inlines.add(WInline.CommentRef(idx, c)) }
                            }
                            0x28 -> if (c.symbolChar != 0) emitText(mapSymbol(c.symbolChar, fonts.getOrNull(c.symbolFont)).toString(), c)
                            else -> {}
                        }
                        continue
                    }
                    val s: String? = when (code) {
                        0x09 -> "\t"
                        0x0B -> "\n"
                        0x0C -> "\u000C"
                        0x0E -> "\u000E"
                        0x1E -> "‑"
                        0x1F -> "­"
                        0x07, 0x0D -> null
                        else -> if (code < 0x20) null else null
                    }
                    if (s != null) { emitText(s, c); continue }
                    if (code < 0x20) continue
                    val fontName = fonts.getOrNull(c.fontAscii)
                    val out = if (c.symbolChar != 0 && code == 0x28) mapSymbol(c.symbolChar, fonts.getOrNull(c.symbolFont)) else mapSymbol(code, fontName)
                    emitText(out.toString(), c)
                    if (plain.length < 4_000_000) plain.append(out)
                }
                cp = segEnd
            }
        }
        flush()
        if (plain.length < 4_000_000) plain.append('\n')
        return PRec(cpStart, cpEnd, pp, markChp, inlines, endChar)
    }

    private fun mapSymbolText(s: String, font: String?): String {
        val sb = StringBuilder()
        for (c in s) sb.append(mapSymbol(c.code, font))
        return sb.toString()
    }

    private fun mapSymbol(code: Int, font: String?): Char {
        val f = font?.lowercase() ?: ""
        val low = if (code in 0xF020..0xF0FF) code - 0xF000 else code
        if (f == "symbol") {
            SYMBOL_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) low.toChar() else code.toChar()
        }
        if (f.startsWith("wingdings")) {
            WINGDINGS_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) '▪' else code.toChar()
        }
        if (f == "monotype sorts" || f.startsWith("zapfdingbats") || f.startsWith("zapf dingbats")) {
            DINGBATS_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) '▪' else code.toChar()
        }
        if (code in 0xF020..0xF0FF) {
            SYMBOL_MAP[low]?.let { if (low == 0xB7 || low == 0xA7) return it }
            return low.toChar()
        }
        return code.toChar()
    }

    private fun noteIndexAt(cp: Int): Int = footnoteOrder[cp] ?: endnoteOrder[cp] ?: -1

    private var currentNoteLabel: String? = null

    private val footnoteOrder = HashMap<Int, Int>()
    private val endnoteOrder = HashMap<Int, Int>()

    private fun picture(c: CharProps): WImage? {
        val d = data ?: return null
        val o = c.picLocation
        if (o < 0 || o + 0x44 > d.size) return null
        val lcb = d.s32(o)
        val cbHeader = d.u16(o + 4)
        if (lcb < cbHeader || cbHeader < 0x44 || o.toLong() + lcb > d.size) return null
        val mm = d.s16(o + 6)
        val goalX = d.s16(o + 28); val goalY = d.s16(o + 30)
        val mx = d.u16(o + 32).takeIf { it > 0 } ?: 1000
        val my = d.u16(o + 34).takeIf { it > 0 } ?: 1000
        var cropL = d.s16(o + 36); var cropT = d.s16(o + 38); var cropR = d.s16(o + 40); var cropB = d.s16(o + 42)
        var p = o + cbHeader
        val end = o + lcb
        var loader: (() -> Bitmap?)? = null
        var fracCrop: FloatArray? = null
        var spProps: Map<Int, Int>? = null
        if (mm == 0x64 || mm == 0x66) {
            cropL = 0; cropT = 0; cropR = 0; cropB = 0
            if (mm == 0x66) p += 1 + d.u8(p)
            var q = p
            var pib = -1
            var blipAt = -1
            while (q + 8 <= end) {
                val ver = d.u16(q); val type = d.u16(q + 2); val len = d.s32(q + 4)
                if (len < 0) break
                val recEnd = q + 8 + len
                if (type == 0xF004) {
                    val props = parseSpContainer(d, q + 8, minOf(recEnd, end))
                    spProps = props.props
                    pib = props.props[0x0104] ?: pib
                    fracCrop = cropFrom(props.props)
                    q = recEnd
                    continue
                }
                if (type == 0xF007) {
                    val nameLen = d.u8(q + 8 + 33)
                    val bl = q + 8 + 36 + nameLen
                    if (bl + 8 <= recEnd) { blipAt = bl; break }
                    val fo = d.s32(q + 8 + 28)
                    if (fo >= 0 && fo < w.size) { val off = fo; loader = { PptBlipDecoder.decode(w.d, off, maxImageDim) }; break }
                    q = recEnd; continue
                }
                if (type in 0xF018..0xF117) { blipAt = q; break }
                if (ver and 0xF == 0xF) { q += 8; continue }
                q = recEnd
            }
            if (blipAt >= 0) { val at = blipAt; loader = { PptBlipDecoder.decode(d.d, at, maxImageDim) } }
            else if (loader == null && pib > 0) { val idx = pib; loader = { blipFromStore(idx) } }
        } else {
            val start = p
            if (end > start) loader = { EmbeddedImageScanner.decodeMetafileOrSvgBitmap(d.d, start, end - start) }
        }
        var wTw = goalX * mx / 1000
        var hTw = goalY * my / 1000
        if (cropL != 0 || cropR != 0 || cropT != 0 || cropB != 0) {
            wTw -= (cropL + cropR) * mx / 1000
            hTw -= (cropT + cropB) * my / 1000
        }
        val gb = spProps?.get(0x03BF) ?: 0
        val isHrProp = ((gb ushr 16) and 0x2000 != 0) && (gb and 0x2000 != 0)
        if (isHrProp && hTw <= 0) hTw = (spProps?.get(0x0395) ?: 30).coerceAtLeast(10)
        if (isHrProp && wTw <= 0) wTw = (spProps?.get(0x0396) ?: 9072).coerceAtLeast(20)
        if (wTw <= 0 || hTw <= 0) return null
        val l = loader ?: run {
            val isHr = isHrProp || (hTw <= 80 && wTw >= hTw * 20)
            if (isHr) {
                val pctRaw = spProps?.get(0x0393) ?: 0
                val isStd = ((gb ushr 16) and 0x0800 != 0) && (gb and 0x0800 != 0)
                val pct = if (pctRaw in 1..1000) pctRaw else if (isStd && (spProps?.get(0x0396) ?: 0) <= 0) 1000 else 0
                val align = (spProps?.get(0x0394) ?: 1).coerceIn(0, 2)
                val noShade = ((gb ushr 16) and 0x1000 != 0) && (gb and 0x1000 != 0)
                val col = spProps?.get(0x0181)?.let { rgbFromOfficeArt(it) } ?: 0xFFA0A0A0.toInt()
                return WImage(wTw, hTw, 2, pct, align, noShade, col) { null }
            }
            return WImage(wTw, hTw, 1) { null }
        }
        val crop = fracCrop
        return WImage(wTw, hTw) {
            val b = l()
            if (b != null && crop != null) cropBitmap(b, crop) else b
        }
    }

    private fun cropFrom(props: Map<Int, Int>): FloatArray? {
        val t = (props[0x0100] ?: 0) / 65536f; val b = (props[0x0101] ?: 0) / 65536f
        val l = (props[0x0102] ?: 0) / 65536f; val r = (props[0x0103] ?: 0) / 65536f
        return if (t == 0f && b == 0f && l == 0f && r == 0f) null else floatArrayOf(l, t, r, b)
    }

    private fun cropBitmap(b: Bitmap, c: FloatArray): Bitmap {
        val x = (c[0] * b.width).toInt().coerceIn(0, b.width - 1)
        val y = (c[1] * b.height).toInt().coerceIn(0, b.height - 1)
        val r = (b.width - c[2] * b.width).toInt().coerceIn(x + 1, b.width)
        val bt = (b.height - c[3] * b.height).toInt().coerceIn(y + 1, b.height)
        return try { Bitmap.createBitmap(b, x, y, r - x, bt - y) } catch (e: Exception) { b }
    }

    private fun parseSpContainer(d: DocBytes, start: Int, end: Int): ShapeInfo {
        val props = HashMap<Int, Int>()
        val complex = HashMap<Int, ByteArray>()
        var spid = 0; var type = 0
        var child: IntArray? = null
        var group: IntArray? = null
        var q = start
        while (q + 8 <= end) {
            val ver = d.u16(q); val rt = d.u16(q + 2); val len = d.s32(q + 4)
            if (len < 0) break
            val recEnd = minOf(q + 8 + len, end)
            when (rt) {
                0xF00A -> { spid = d.s32(q + 8); type = ver ushr 4 }
                0xF00F -> if (len >= 16) child = IntArray(4) { d.s32(q + 8 + it * 4) }
                0xF009 -> if (len >= 16) group = IntArray(4) { d.s32(q + 8 + it * 4) }
                0xF00B, 0xF121, 0xF122 -> {
                    val n = ver ushr 4
                    var cx = q + 8 + n * 6
                    for (k in 0 until n) {
                        val e = q + 8 + k * 6
                        val id = d.u16(e)
                        val v = d.s32(e + 2)
                        val pid = id and 0x3FFF
                        if (id and 0x8000 != 0) {
                            if (v > 0 && cx + v <= recEnd) complex[pid] = d.d.copyOfRange(cx, cx + v)
                            cx += maxOf(0, v)
                        } else props[pid] = v
                    }
                }
            }
            q = recEnd
        }
        return ShapeInfo(spid, type, props, complex, child, group)
    }

    private fun blipFromStore(pib: Int): Bitmap? {
        val at = blipStore.getOrNull(pib - 1) ?: return null
        if (at >= 0) return PptBlipDecoder.decode(t.d, at, maxImageDim)
        val fo = -at - 2
        return if (fo >= 0) PptBlipDecoder.decode(w.d, fo, maxImageDim) else null
    }

    private fun readDrawings() {
        if (!validT(50)) return
        var p = fc(50)
        val end = p + lcb(50)
        val store = ArrayList<Int>()
        val shapeMap = HashMap<Int, ShapeInfo>()
        fun walk(s: Int, e: Int, depth: Int) {
            var q = s
            while (q + 8 <= e) {
                val ver = t.u16(q); val rt = t.u16(q + 2); val len = t.s32(q + 4)
                if (len < 0) return
                val recEnd = minOf(q + 8 + len, e)
                when (rt) {
                    0xF007 -> {
                        val nameLen = t.u8(q + 8 + 33)
                        val bl = q + 8 + 36 + nameLen
                        if (bl + 8 <= recEnd) store.add(bl) else store.add(-(t.s32(q + 8 + 28)) - 2)
                    }
                    0xF004 -> parseSpContainer(t, q + 8, recEnd).let { if (it.spid != 0) shapeMap[it.spid] = it }
                    0xF003 -> parseGroup(q + 8, recEnd, shapeMap, 0)
                    else -> if (ver and 0xF == 0xF && depth < 8) walk(q + 8, recEnd, depth + 1)
                }
                q = recEnd
            }
        }
        val first = t.u16(p + 2)
        if (first == 0xF000) {
            val len = t.s32(p + 4)
            walk(p + 8, minOf(p + 8 + len, end), 0)
            p += 8 + len
        }
        while (p + 9 <= end) {
            val rt = t.u16(p + 3)
            if (rt != 0xF002) break
            val len = t.s32(p + 5)
            walk(p + 9, minOf(p + 9 + len, end), 0)
            p += 9 + len
        }
        blipStore = store
        shapes = shapeMap
        fun fspa(i: Int): Map<Int, Fspa> {
            if (!validT(i)) return emptyMap()
            val base = fc(i); val n = (lcb(i) - 4) / 30
            val m = HashMap<Int, Fspa>()
            for (k in 0 until maxOf(0, n)) {
                val cp = t.s32(base + k * 4)
                val q = base + (n + 1) * 4 + k * 26
                val fl = t.u16(q + 20)
                m[cp] = Fspa(t.s32(q), t.s32(q + 4), t.s32(q + 8), t.s32(q + 12), t.s32(q + 16), (fl ushr 1) and 3, (fl ushr 3) and 3, (fl ushr 5) and 0xF, (fl ushr 9) and 0xF, fl and 0x4000 != 0)
            }
            return m
        }
        fspaMain = fspa(40)
        fspaHdr = fspa(41)
        fun txbx(i: Int): IntArray {
            if (!validT(i)) return IntArray(0)
            val base = fc(i); val n = (lcb(i) - 4) / 26
            return IntArray(maxOf(0, n + 1)) { t.s32(base + it * 4) }
        }
        txbxMain = txbx(56)
        txbxHdr = txbx(58)
    }

    private fun parseGroup(s: Int, e: Int, map: HashMap<Int, ShapeInfo>, depth: Int): ShapeInfo? {
        var group: ShapeInfo? = null
        val kids = ArrayList<ShapeInfo>()
        var q = s
        while (q + 8 <= e) {
            val rt = t.u16(q + 2); val len = t.s32(q + 4)
            if (len < 0) break
            val recEnd = minOf(q + 8 + len, e)
            when (rt) {
                0xF004 -> {
                    val sh = parseSpContainer(t, q + 8, recEnd)
                    if (sh.spid != 0) map[sh.spid] = sh
                    if (group == null) group = sh else kids.add(sh)
                }
                0xF003 -> if (depth < 16) parseGroup(q + 8, recEnd, map, depth + 1)?.let { kids.add(it) }
            }
            q = recEnd
        }
        group?.children = kids
        return group
    }

    private fun storyStart(i: Int): Int { var s = 0; for (k in 0 until i) s += ccp[k]; return s }

    private val floatingDepth = intArrayOf(0)

    private fun floatingAt(cp: Int): WFloating? {
        val isHdr = currentStoryHeader
        val rel = if (isHdr) cp - storyStart(2) else cp
        val f = (if (isHdr) fspaHdr else fspaMain)[rel] ?: return null
        if (f.right - f.left <= 0 || f.bottom - f.top <= 0) return null
        val sh = shapes[f.spid]
        val props = sh?.props ?: emptyMap()
        val relH = props[0x0390] ?: when (f.bx) { 0 -> 0; 1 -> 1; else -> 2 }
        val relV = props[0x0392] ?: when (f.by) { 0 -> 0; 1 -> 1; else -> 2 }
        return shapeFloating(sh, f.left, f.top, f.right, f.bottom, relH, relV, f.wr, f.wrk, f.below, isHdr, 0)
    }

    private fun shapeFloating(sh: ShapeInfo?, l: Int, tp: Int, r: Int, b: Int, relH: Int, relV: Int, wr: Int, wrk: Int, below: Boolean, isHdr: Boolean, depth: Int): WFloating? {
        if (r - l <= 0 || b - tp <= 0) return null
        if (sh != null && sh.children.isNotEmpty() && depth < 16) {
            val g = sh.groupRect ?: intArrayOf(0, 0, r - l, b - tp)
            val gw = (g[2] - g[0]).toDouble().takeIf { it != 0.0 } ?: 1.0
            val gh = (g[3] - g[1]).toDouble().takeIf { it != 0.0 } ?: 1.0
            val kids = sh.children.mapNotNull { c ->
                val a = c.child ?: return@mapNotNull null
                val cl = l + ((a[0] - g[0]) / gw * (r - l)).toInt(); val ct = tp + ((a[1] - g[1]) / gh * (b - tp)).toInt()
                val cr = l + ((a[2] - g[0]) / gw * (r - l)).toInt(); val cb = tp + ((a[3] - g[1]) / gh * (b - tp)).toInt()
                shapeFloating(c, cl, ct, cr, cb, relH, relV, wr, wrk, below, isHdr, depth + 1)
            }
            if (kids.isEmpty()) return null
            return WFloating(null, emptyList(), l, tp, r, b, relH, relV, wr, wrk, below, null, null, kids, shapeType = 0)
        }
        val props = sh?.props ?: emptyMap()
        var image: WImage? = null
        var blocks: List<WBlock> = emptyList()
        val pib = props[0x0104]
        if (pib != null && pib > 0) {
            val crop = cropFrom(props)
            image = WImage(r - l, b - tp) { val bm = blipFromStore(pib); if (bm != null && crop != null) cropBitmap(bm, crop) else bm }
        }
        val txid = props[0x0080]
        if (txid != null && floatingDepth[0] < 2) {
            val arr = if (isHdr) txbxHdr else txbxMain
            val idx = (txid ushr 16) - 1
            if (idx >= 0 && idx + 1 < arr.size) {
                val base = storyStart(if (isHdr) 7 else 6)
                val a = base + arr[idx]; val z = base + arr[idx + 1]
                if (z > a) {
                    floatingDepth[0]++
                    val saved = ArrayList(fieldStack)
                    val savedHdr = currentStoryHeader
                    blocks = buildBlocks(readParagraphs(a, z), 0)
                    currentStoryHeader = savedHdr
                    fieldStack.clear(); fieldStack.addAll(saved)
                    floatingDepth[0]--
                }
            }
        }
        val type = sh?.type ?: 1
        val filled = props[0x01BF]?.let { v -> if (v and 0x100000 != 0) v and 0x10 != 0 else true } ?: (type == 202 || type == 1)
        val fill = if (image == null && filled && sh != null) rgbFromOfficeArt(props[0x0181] ?: 0xFFFFFF) else null
        val lined = props[0x01FF]?.let { v -> if (v and 0x80000 != 0) v and 0x8 != 0 else true } ?: (image == null && sh != null)
        val line = if (lined && sh != null && image == null) WBorder(1, ((props[0x01CB] ?: 9525) * 8 / 12700).coerceAtLeast(1), rgbFromOfficeArt(props[0x01C0] ?: 0), 0) else null
        if (image == null && blocks.isEmpty() && fill == null && line == null) return null
        val ins = intArrayOf((props[0x0081] ?: 91440) / 635, (props[0x0082] ?: 45720) / 635, (props[0x0083] ?: 91440) / 635, (props[0x0084] ?: 45720) / 635)
        return WFloating(image, blocks, l, tp, r, b, relH, relV, wr, wrk, below, fill, line, emptyList(), ins, type)
    }

    private fun rgbFromOfficeArt(v: Int): Int {
        if (v ushr 24 != 0) return 0xFF000000.toInt()
        val r = v and 0xFF; val g = (v ushr 8) and 0xFF; val b = (v ushr 16) and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readNotes(refIdx: Int, txtIdx: Int, storyIdx: Int, endnote: Boolean): List<WNote> {
        if (!validT(refIdx) || !validT(txtIdx)) return emptyList()
        val base = fc(refIdx); val n = (lcb(refIdx) - 4) / 6
        if (n <= 0) return emptyList()
        val tbase = fc(txtIdx); val tn = lcb(txtIdx) / 4
        val start = storyStart(storyIdx)
        val out = ArrayList<WNote>()
        var auto = 0
        for (k in 0 until n) {
            val cp = t.s32(base + k * 4)
            val frd = t.s16(base + (n + 1) * 4 + k * 2)
            val label = if (frd > 0) { auto++; if (endnote) roman(auto, false) else auto.toString() } else ""
            if (endnote) { endnoteLabels[cp] = label; endnoteOrder[cp] = k } else { footnoteLabels[cp] = label; footnoteOrder[cp] = k }
            if (k + 1 < tn) {
                val a = start + t.s32(tbase + k * 4); val b = start + t.s32(tbase + (k + 1) * 4)
                currentNoteLabel = label
                val blocks = if (b > a) buildBlocks(readParagraphs(a, b), 0) else emptyList()
                currentNoteLabel = null
                out.add(WNote(label, blocks))
            } else out.add(WNote(label, emptyList()))
        }
        return out
    }


    private fun readComments(): List<WComment> {
        if (ccp[4] <= 0 || !validT(4) || !validT(5)) return emptyList()
        val owners = ArrayList<String>()
        if (validT(36)) {
            var p = fc(36)
            val end = p + lcb(36)
            while (p + 2 <= end) {
                val len = t.u16(p); p += 2
                if (p + len * 2 > end) break
                val sb = StringBuilder(len)
                for (i in 0 until len) sb.append(t.u16(p + i * 2).toChar())
                owners.add(sb.toString().trim())
                p += len * 2
            }
        }
        val bkfStarts = ArrayList<Int>()
        val bkfEnds = ArrayList<Int>()
        if (validT(42) && validT(43)) {
            val bBase = fc(42); val bn = (lcb(42) - 4) / 8
            val eBase = fc(43); val en = lcb(43) / 4
            val ends = IntArray(maxOf(0, en)) { t.s32(eBase + it * 4) }
            for (k in 0 until maxOf(0, bn)) {
                val cpS = t.s32(bBase + k * 4)
                val ibkl = t.u16(bBase + (bn + 1) * 4 + k * 4)
                bkfStarts.add(cpS)
                bkfEnds.add(ends.getOrElse(ibkl) { cpS })
            }
        }
        val rBase = fc(4); val n = (lcb(4) - 4) / 34
        if (n <= 0) return emptyList()
        val tBase = fc(5); val tn = lcb(5) / 4
        val start = storyStart(4)
        val out = ArrayList<WComment>(n)
        for (k in 0 until n) {
            val refCp = t.s32(rBase + k * 4)
            val q = rBase + (n + 1) * 4 + k * 30
            val cch = t.u16(q).coerceIn(0, 9)
            val isb = StringBuilder(cch)
            for (j in 0 until cch) isb.append(t.u16(q + 2 + j * 2).toChar())
            val initials = isb.toString().trim()
            val ibst = t.u16(q + 20)
            val author = owners.getOrNull(ibst)?.takeIf { it.isNotEmpty() } ?: initials
            val text = if (k + 1 < tn) {
                val a = start + t.s32(tBase + k * 4)
                val b = start + t.s32(tBase + (k + 1) * 4)
                if (b > a) {
                    val savedPlain = plain.length
                    val paras = readParagraphs(a, b)
                    plain.setLength(savedPlain)
                    paras.joinToString("\n") { pr ->
                        pr.inlines.filterIsInstance<WInline.Text>().joinToString("") { it.text }
                    }.trim()
                } else ""
            } else ""
            commentOrder[refCp] = k
            out.add(WComment(author, initials, text, bkfStarts.getOrElse(k) { refCp }, bkfEnds.getOrElse(k) { refCp }, refCp))
        }
        return out
    }

    private fun buildBlocks(paras: List<PRec>, depth: Int): List<WBlock> = buildRange(paras, 0, paras.size, depth)

    private fun itap(p: PRec) = if (p.pap.itap > 0) p.pap.itap else if (p.pap.inTable) 1 else 0

    private fun isCellEnd(p: PRec, level: Int) = if (level == 1) p.endChar == 0x07 && !p.pap.ttp else (p.pap.innerCell && itap(p) == level)

    private fun isRowEnd(p: PRec, level: Int) = if (level == 1) p.pap.ttp && itap(p) <= 1 else (p.pap.innerTtp && itap(p) == level)

    private fun toParagraph(p: PRec): WParagraph {
        val wp = WParagraph(p.pap, p.mark, p.inlines)
        wp.styleName = styleOf(p.pap.istd)?.name
        listLabel(wp)
        return wp
    }

    private fun buildRange(paras: List<PRec>, from: Int, to: Int, depth: Int): List<WBlock> {
        val out = ArrayList<WBlock>()
        var i = from
        while (i < to) {
            val p = paras[i]
            if (itap(p) > depth && depth < 64) {
                val level = depth + 1
                val rows = ArrayList<WRow>()
                var cells = ArrayList<WCell>()
                var cellStart = i
                var j = i
                while (j < to && itap(paras[j]) >= level) {
                    val q = paras[j]
                    if (itap(q) == level && isRowEnd(q, level)) {
                        if (cellStart < j) cells.add(WCell(buildRange(paras, cellStart, j, level)))
                        val tp = q.pap.table ?: TableProps()
                        rows.add(WRow(cells, tp))
                        cells = ArrayList()
                        cellStart = j + 1
                        j++
                        if (j < to && itap(paras[j]) < level) break
                        continue
                    }
                    if (itap(q) == level && isCellEnd(q, level)) {
                        cells.add(WCell(buildRange(paras, cellStart, j + 1, level)))
                        cellStart = j + 1
                    }
                    j++
                }
                if (cells.isNotEmpty() || cellStart < j) {
                    if (cellStart < j) cells.add(WCell(buildRange(paras, cellStart, j, level)))
                    rows.add(WRow(cells, TableProps()))
                }
                if (rows.isNotEmpty()) out.add(WTable(rows))
                i = if (j > i) j else i + 1
                continue
            }
            out.add(toParagraph(p))
            i++
        }
        return out
    }


    private fun headerBlocks(storyBase: Int, cps: IntArray, idx: Int): List<WBlock>? {
        if (idx + 1 >= cps.size) return null
        val a = cps[idx]; val b = cps[idx + 1]
        if (b <= a) return null
        val saved = currentStoryHeader
        currentStoryHeader = true
        val paras = readParagraphs(storyBase + a, storyBase + b)
        val trimmed = if (paras.size > 1 && paras.last().inlines.isEmpty() && itap(paras.last()) == 0) paras.subList(0, paras.size - 1) else paras
        val r = buildBlocks(trimmed, 0)
        currentStoryHeader = saved
        return r
    }

    fun read(): WDocument {
        readDrawings()
        val sedCps: IntArray
        val sedFc: IntArray
        if (validT(6)) {
            val base = fc(6); val n = (lcb(6) - 4) / 16
            sedCps = IntArray(maxOf(0, n) + 1) { t.s32(base + it * 4) }
            sedFc = IntArray(maxOf(0, n)) { t.s32(base + (n + 1) * 4 + it * 12 + 2) }
        } else { sedCps = intArrayOf(0, ccp[0]); sedFc = intArrayOf(-1) }
        sectionEnds = HashSet(sedCps.drop(1))
        val footnotes = readNotes(2, 3, 1, false)
        val endnotes = readNotes(46, 47, 5, true)
        val comments = readComments()
        plain.setLength(0)
        val mainParas = readParagraphs(0, ccp[0])
        val hddCps = if (validT(11)) { val base = fc(11); val n = lcb(11) / 4; IntArray(n) { t.s32(base + it * 4) } } else IntArray(0)
        val hdrBase = storyStart(2)
        val sections = ArrayList<WSection>()
        var prev: WHeaderSet? = null
        var pi = 0
        for (s in 0 until maxOf(1, sedFc.size)) {
            val sp = SectionProps()
            val fcSepx = sedFc.getOrElse(s) { -1 }
            if (fcSepx >= 0 && fcSepx + 2 <= w.size) {
                val cb = w.s16(fcSepx)
                if (cb > 0) DocSprms.applySep(sp, w, fcSepx + 2, cb)
            }
            val end = sedCps.getOrElse(s + 1) { ccp[0] }
            val from = pi
            while (pi < mainParas.size && mainParas[pi].cpStart < end) pi++
            val last = s == maxOf(1, sedFc.size) - 1
            if (last) pi = mainParas.size
            val blocks = buildBlocks(mainParas.subList(from, pi), 0)
            fun hb(k: Int): List<WBlock>? = headerBlocks(hdrBase, hddCps, 6 + s * 6 + k)
            val hs = WHeaderSet(
                hb(0) ?: prev?.evenHeader, hb(1) ?: prev?.oddHeader, hb(2) ?: prev?.evenFooter,
                hb(3) ?: prev?.oddFooter, hb(4) ?: prev?.firstHeader, hb(5) ?: prev?.firstFooter
            )
            prev = hs
            sections.add(WSection(sp, blocks, hs))
        }
        return WDocument(sections, fonts, footnotes, endnotes, facingPages, defaultTab, plain.toString(), htmlSpacing, chp(0), pap(0), autoHyphen, autoHyphenCaps, hotZoneTw, consecHyphLim, docLid, comments)
    }

    companion object {
        private val SYMBOL_MAP: Map<Int, Char> = run {
            val m = HashMap<Int, Char>()
            val greekUpper = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ"
            for ((i, c) in greekUpper.withIndex()) m[0x41 + i] = c
            val greekLower = "αβχδεφγηιϕκλμνοπθρστυϖωξψζ"
            for ((i, c) in greekLower.withIndex()) m[0x61 + i] = c
            m[0x22] = '∀'; m[0x24] = '∃'; m[0x27] = '∋'; m[0x2A] = '∗'; m[0x2D] = '−'; m[0x40] = '≅'; m[0x5C] = '∴'; m[0x5E] = '⊥'; m[0x60] = '‾'
            m[0x7E] = '∼'; m[0xA0] = '€'; m[0xA1] = 'ϒ'; m[0xA2] = '′'; m[0xA3] = '≤'; m[0xA4] = '⁄'; m[0xA5] = '∞'; m[0xA6] = 'ƒ'; m[0xA7] = '♣'
            m[0xA8] = '♦'; m[0xA9] = '♥'; m[0xAA] = '♠'; m[0xAB] = '↔'; m[0xAC] = '←'; m[0xAD] = '↑'; m[0xAE] = '→'; m[0xAF] = '↓'; m[0xB0] = '°'
            m[0xB1] = '±'; m[0xB2] = '″'; m[0xB3] = '≥'; m[0xB4] = '×'; m[0xB5] = '∝'; m[0xB6] = '∂'; m[0xB7] = '•'; m[0xB8] = '÷'; m[0xB9] = '≠'
            m[0xBA] = '≡'; m[0xBB] = '≈'; m[0xBC] = '…'; m[0xC0] = 'ℵ'; m[0xC1] = 'ℑ'; m[0xC2] = 'ℜ'; m[0xC3] = '℘'; m[0xC4] = '⊗'; m[0xC5] = '⊕'
            m[0xC6] = '∅'; m[0xC7] = '∩'; m[0xC8] = '∪'; m[0xC9] = '⊃'; m[0xCA] = '⊇'; m[0xCB] = '⊄'; m[0xCC] = '⊂'; m[0xCD] = '⊆'; m[0xCE] = '∈'
            m[0xCF] = '∉'; m[0xD0] = '∠'; m[0xD1] = '∇'; m[0xD2] = '®'; m[0xD3] = '©'; m[0xD4] = '™'; m[0xD5] = '∏'; m[0xD6] = '√'; m[0xD7] = '⋅'
            m[0xD8] = '¬'; m[0xD9] = '∧'; m[0xDA] = '∨'; m[0xDB] = '⇔'; m[0xDC] = '⇐'; m[0xDD] = '⇑'; m[0xDE] = '⇒'; m[0xDF] = '⇓'; m[0xE0] = '◊'
            m[0xE1] = '〈'; m[0xE5] = '∑'; m[0xF1] = '〉'; m[0xF2] = '∫'
            m
        }
        private val WINGDINGS_MAP: Map<Int, Char> = mapOf(
            0x6C to '●', 0x6E to '■', 0x6F to '□', 0x71 to '❑', 0x75 to '◆', 0x76 to '❖', 0x77 to '◆', 0xA7 to '▪', 0xA8 to '◻',
            0xD8 to '➢', 0xE0 to '→', 0xE8 to '➔', 0xF0 to '⇨', 0xFC to '✓', 0xFB to '✗', 0xFE to '☑', 0x9F to '•', 0xA1 to '○', 0x4A to '☺',
            0x46 to '☞', 0x3F to '✍', 0x21 to '✏', 0x22 to '✂', 0x28 to '☎', 0x2A to '✉', 0x7B to '❀', 0x7C to '✿'
        )
        private val DINGBATS_MAP: Map<Int, Char> = mapOf(
            0x33 to '✓', 0x34 to '✔', 0x35 to '✕', 0x36 to '✖', 0x37 to '✗', 0x38 to '✘', 0x46 to '★', 0x48 to '★', 0x49 to '✩',
            0x6C to '●', 0x6D to '❍', 0x6E to '■', 0x6F to '❏', 0x70 to '❐', 0x71 to '❑', 0x72 to '❒', 0x73 to '▲', 0x74 to '▼',
            0x75 to '◆', 0x76 to '❖', 0x77 to '◗', 0xD4 to '→', 0xDC to '➔', 0xE4 to '➢', 0xE5 to '➣', 0xE6 to '➤'
        )
    }
}
