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
 * Implements the Microsoft PowerPoint (.ppt) Binary File Format ([MS-PPT] and [MS-ODRAW] under
 * the Microsoft Open Specifications Promise) with record/atom IDs informed by Apache POI HSLF
 * (https://poi.apache.org/, Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package com.omnireader.core.engine.impl

import java.nio.charset.StandardCharsets

internal class PptFormatException(message: String) : Exception(message)

internal class PptEncryptedException : Exception("encrypted")

internal class PptRecord(val type: Int, val instance: Int, val version: Int, val offset: Int, val start: Int, val end: Int) {
    val isContainer: Boolean get() = version == 0xF
    val length: Int get() = end - start
}

internal class PptBytes(val data: ByteArray) {
    fun u8(p: Int): Int = if (p >= 0 && p < data.size) data[p].toInt() and 0xFF else 0
    fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
    fun s16(p: Int): Int = u16(p).toShort().toInt()
    fun s32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)

    fun record(p: Int, limit: Int): PptRecord? {
        if (p < 0 || p + 8 > limit) return null
        val vi = u16(p)
        val len = s32(p + 4).toLong() and 0xFFFFFFFFL
        val end = minOf(p + 8L + len, limit.toLong()).toInt()
        return PptRecord(u16(p + 2), vi ushr 4, vi and 0xF, p, p + 8, end)
    }

    fun children(r: PptRecord): List<PptRecord> {
        val out = ArrayList<PptRecord>()
        var p = r.start
        while (p + 8 <= r.end && out.size < 100_000) {
            val c = record(p, r.end) ?: break
            out.add(c)
            if (c.end <= p) break
            p = c.end
        }
        return out
    }

    fun child(r: PptRecord, type: Int, instance: Int = -1): PptRecord? =
        children(r).firstOrNull { it.type == type && (instance < 0 || it.instance == instance) }

    fun utf16(start: Int, end: Int): String {
        val s = start.coerceIn(0, data.size)
        val e = end.coerceIn(s, data.size)
        return String(data, s, (e - s) and -2, StandardCharsets.UTF_16LE)
    }

    fun latin1(start: Int, end: Int): String {
        val s = start.coerceIn(0, data.size)
        val e = end.coerceIn(s, data.size)
        return String(data, s, e - s, StandardCharsets.ISO_8859_1)
    }
}

internal class PptProps {
    val values = HashMap<Int, Int>()
    val complex = HashMap<Int, ByteArray>()
    var parent: PptProps? = null

    fun raw(id: Int, depth: Int = 0): Int? = values[id] ?: if (depth < 8) parent?.raw(id, depth + 1) else null
    fun has(id: Int): Boolean = raw(id) != null
    fun int(id: Int, def: Int): Int = raw(id) ?: def
    fun blob(id: Int, depth: Int = 0): ByteArray? = complex[id] ?: if (depth < 8 && !values.containsKey(id)) parent?.blob(id, depth + 1) else null

    fun bool(prop: Int, bit: Int, def: Boolean, depth: Int = 0): Boolean {
        val v = values[prop]
        if (v != null && v and (bit shl 16) != 0) return (v and bit) != 0
        return if (depth < 8) parent?.bool(prop, bit, def, depth + 1) ?: def else def
    }

    fun mergeFrom(r: PptBytes, rec: PptRecord) {
        val n = rec.instance
        var p = rec.start
        val entries = ArrayList<Triple<Int, Boolean, Int>>(n)
        for (i in 0 until n) {
            if (p + 6 > rec.end) break
            val opid = r.u16(p)
            val value = r.s32(p + 2)
            val id = opid and 0x3FFF
            val isComplex = (opid and 0x8000) != 0
            entries.add(Triple(id, isComplex, value))
            p += 6
        }
        for ((id, isComplex, value) in entries) {
            val old = values[id]
            values[id] = if (old != null && !isComplex && id and 0x3F == 0x3F) {
                val use = (value ushr 16) and 0xFFFF
                val mask = (use shl 16) or use
                (old and mask.inv()) or (value and mask)
            } else {
                value
            }
            if (isComplex) {
                val len = value.toLong() and 0xFFFFFFFFL
                if (len > 0 && p + len <= rec.end) {
                    complex[id] = r.data.copyOfRange(p, (p + len).toInt())
                    p += len.toInt()
                } else if (len > 0) {
                    p = rec.end
                }
            }
        }
    }
}

internal class PptCharProps {
    var styleMask = 0
    var styleBits = 0
    var fontRef: Int? = null
    var eaFontRef: Int? = null
    var symbolFontRef: Int? = null
    var size: Int? = null
    var color: Int? = null
    var position: Int? = null

    fun flag(bit: Int): Boolean? = if (styleMask and bit != 0) (styleBits and bit) != 0 else null
}

internal class PptParaProps {
    var bulletMask = 0
    var bulletFlags = 0
    var bulletChar: Int? = null
    var bulletFontRef: Int? = null
    var bulletSize: Int? = null
    var bulletColor: Int? = null
    var align: Int? = null
    var lineSpacing: Int? = null
    var spaceBefore: Int? = null
    var spaceAfter: Int? = null
    var leftMargin: Int? = null
    var indent: Int? = null

    fun bulletFlag(bit: Int): Boolean? = if (bulletMask and bit != 0) (bulletFlags and bit) != 0 else null
}

internal class PptMasterLevel(val pf: PptParaProps, val cf: PptCharProps)

internal class PptRulerLevels(val leftMargin: Array<Int?>, val indent: Array<Int?>)

internal class PptRunStyle(
    val sizePt: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val shadow: Boolean,
    val color: Int,
    val fontRef: Int,
    val eaFontRef: Int?,
    val symbolFontRef: Int?,
    val position: Int
)

internal class PptTextRun(val text: String, val style: PptRunStyle, val field: Int)

internal fun PptRunStyle.asHyperlink(): PptRunStyle = PptRunStyle(
    sizePt, bold, italic, true, shadow, 0x06 shl 24, fontRef, eaFontRef, symbolFontRef, position
)

internal class PptParagraph(
    val runs: List<PptTextRun>,
    val level: Int,
    val align: Int,
    val hasBullet: Boolean,
    val bulletChar: Int,
    val bulletFontRef: Int?,
    val bulletSize: Int,
    val bulletColor: Int?,
    val lineSpacing: Int,
    val spaceBefore: Int,
    val spaceAfter: Int,
    val leftMargin: Int,
    val indent: Int
) {
    val plainText: String get() = runs.joinToString("") { it.text }
}

internal class PptTextBody(val textType: Int, val paragraphs: List<PptParagraph>) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText.replace('\u000B', '\n') }
}

internal class PptRawText(
    val textType: Int,
    val text: String,
    val pfRuns: List<Pair<Int, Pair<Int, PptParaProps>>>,
    val cfRuns: List<Pair<Int, PptCharProps>>,
    val ruler: PptRulerLevels?,
    val masterLevels: List<Pair<Int, Int>>,
    val fields: List<Pair<Int, Int>>,
    val links: List<Pair<Int, Int>>
)

internal class PptShape(
    val spid: Int,
    val spt: Int,
    val flags: Int,
    val anchor: FloatArray?,
    val props: PptProps,
    val groupRect: FloatArray?,
    val children: List<PptShape>,
    val placeholder: Int,
    val text: PptTextBody?
) {
    val isGroup: Boolean get() = flags and 0x1 != 0
    val flipH: Boolean get() = flags and 0x40 != 0
    val flipV: Boolean get() = flags and 0x80 != 0
    val isHidden: Boolean get() = props.bool(0x03BF, 0x2, false)
}

internal class PptHeadersFooters(
    val flags: Int,
    val formatId: Int,
    val userDate: String?,
    val header: String?,
    val footer: String?
) {
    val hasDate: Boolean get() = flags and 0x1 != 0
    val hasTodayDate: Boolean get() = flags and 0x2 != 0
    val hasUserDate: Boolean get() = flags and 0x4 != 0
    val hasSlideNumber: Boolean get() = flags and 0x8 != 0
    val hasFooter: Boolean get() = flags and 0x20 != 0
}

internal class PptSheet(
    val kind: Int,
    val slideId: Int,
    val masterIdRef: Int,
    val notesIdRef: Int,
    val slideFlags: Int,
    val scheme: IntArray?,
    val background: PptShape?,
    val shapes: List<PptShape>,
    val styles: Map<Int, List<PptMasterLevel>>,
    val headersFooters: PptHeadersFooters?
) {
    val followsMasterObjects: Boolean get() = slideFlags and 0x1 != 0
    val followsMasterScheme: Boolean get() = slideFlags and 0x2 != 0
    val followsMasterBackground: Boolean get() = slideFlags and 0x4 != 0
}

internal class PptFont(val name: String, val family: Int, val charset: Int)

internal class PptPresentation(
    val slideWidth: Int,
    val slideHeight: Int,
    val slides: List<PptSheet>,
    val masters: Map<Int, PptSheet>,
    val notesText: Map<Int, String>,
    val fonts: List<PptFont>,
    val blips: List<PptBlipRef?>,
    val documentHeadersFooters: PptHeadersFooters?,
    val firstSlideNumber: Int,
    val omitTitlePlace: Boolean
) {
    fun masterOf(sheet: PptSheet): PptSheet? = masters[sheet.masterIdRef]?.takeIf { it !== sheet }

    fun mainMasterOf(sheet: PptSheet): PptSheet? {
        var m = masterOf(sheet) ?: return null
        var guard = 0
        while (m.kind == KIND_TITLE_MASTER && guard++ < 4) m = masterOf(m) ?: break
        return m
    }

    fun schemeFor(sheet: PptSheet): IntArray {
        var s: PptSheet? = sheet
        var guard = 0
        while (s != null && guard++ < 6) {
            if (s.scheme != null && !(s.kind == KIND_SLIDE && s.followsMasterScheme)) return s.scheme
            s = masterOf(s)
        }
        return DEFAULT_SCHEME
    }

    fun slideText(index: Int): String {
        val slide = slides.getOrNull(index) ?: return ""
        val sb = StringBuilder()
        fun walk(list: List<PptShape>) {
            for (s in list) {
                if (s.isHidden) continue
                s.text?.plainText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(it)
                }
                walk(s.children)
            }
        }
        walk(slide.shapes)
        val notes = notesText[slide.notesIdRef]?.trim().orEmpty()
        if (notes.isNotEmpty()) sb.append("\n\n[Notes]\n").append(notes)
        return sb.toString()
    }

    companion object {
        const val KIND_SLIDE = 0
        const val KIND_MAIN_MASTER = 1
        const val KIND_TITLE_MASTER = 2
        const val KIND_NOTES = 3

        const val FIELD_NONE = 0
        const val FIELD_SLIDE_NUMBER = 1
        const val FIELD_DATE = 2
        const val FIELD_FOOTER = 3
        const val FIELD_HEADER = 4

        val DEFAULT_SCHEME = intArrayOf(
            0xFFFFFF, 0x000000, 0x808080, 0x000000, 0xBBE0E3, 0x333399, 0x009999, 0x99CC00
        )

        private const val RT_DOCUMENT = 0x03E8
        private const val RT_DOCUMENT_ATOM = 0x03E9
        private const val RT_SLIDE = 0x03EE
        private const val RT_SLIDE_ATOM = 0x03EF
        private const val RT_NOTES = 0x03F0
        private const val RT_NOTES_ATOM = 0x03F1
        private const val RT_ENVIRONMENT = 0x03F2
        private const val RT_SLIDE_PERSIST_ATOM = 0x03F3
        private const val RT_MAIN_MASTER = 0x03F8
        private const val RT_DRAWING_GROUP = 0x040B
        private const val RT_DRAWING = 0x040C
        private const val RT_FONT_COLLECTION = 0x07D5
        private const val RT_COLOR_SCHEME = 0x07F0
        private const val RT_PLACEHOLDER = 0x0BC3
        private const val RT_OUTLINE_TEXT_REF = 0x0F9E
        private const val RT_TEXT_HEADER = 0x0F9F
        private const val RT_TEXT_CHARS = 0x0FA0
        private const val RT_STYLE_TEXT_PROP = 0x0FA1
        private const val RT_MASTER_TEXT_PROP = 0x0FA2
        private const val RT_TEXT_MASTER_STYLE = 0x0FA3
        private const val RT_TEXT_CF_EXCEPTION = 0x0FA4
        private const val RT_TEXT_PF_EXCEPTION = 0x0FA5
        private const val RT_TEXT_RULER = 0x0FA6
        private const val RT_TEXT_BYTES = 0x0FA8
        private const val RT_CSTRING = 0x0FBA
        private const val RT_FONT_ENTITY = 0x0FB7
        private const val RT_SLIDE_NUMBER_MC = 0x0FD8
        private const val RT_HEADERS_FOOTERS = 0x0FD9
        private const val RT_HEADERS_FOOTERS_ATOM = 0x0FDA
        private const val RT_SLIDE_LIST_WITH_TEXT = 0x0FF0
        private const val RT_USER_EDIT = 0x0FF5
        private const val RT_DATETIME_MC = 0x0FF7
        private const val RT_GENERIC_DATE_MC = 0x0FF8
        private const val RT_HEADER_MC = 0x0FF9
        private const val RT_FOOTER_MC = 0x0FFA
        private const val RT_PERSIST_DIRECTORY = 0x1772
        private const val RT_INTERACTIVE_INFO = 0x0FF2
        private const val RT_TX_INTERACTIVE_INFO = 0x0FDF

        private const val OA_DGG = 0xF000
        private const val OA_BSTORE = 0xF001
        private const val OA_DG = 0xF002
        private const val OA_SPGR = 0xF003
        private const val OA_SP = 0xF004
        private const val OA_FBSE = 0xF007
        private const val OA_FSPGR = 0xF009
        private const val OA_FSP = 0xF00A
        private const val OA_FOPT = 0xF00B
        private const val OA_CLIENT_TEXTBOX = 0xF00D
        private const val OA_CHILD_ANCHOR = 0xF00F
        private const val OA_CLIENT_ANCHOR = 0xF010
        private const val OA_CLIENT_DATA = 0xF011
        private const val OA_SECONDARY_FOPT = 0xF121
        private const val OA_TERTIARY_FOPT = 0xF122

        private const val MAX_GROUP_DEPTH = 24

        fun parse(document: ByteArray, currentUser: ByteArray?): PptPresentation =
            Parser(PptBytes(document), currentUser).run()

        fun parsePf(r: PptBytes, start: Int, limit: Int): Pair<PptParaProps, Int> {
            val pf = PptParaProps()
            var p = start
            if (p + 4 > limit) return pf to limit
            val m = r.s32(p)
            p += 4
            fun take2(): Int? = if (p + 2 <= limit) r.s16(p).also { p += 2 } else null
            fun take4(): Int? = if (p + 4 <= limit) r.s32(p).also { p += 4 } else null
            if (m and 0xF != 0) {
                val flags = take2() ?: 0
                pf.bulletMask = m and 0xF
                pf.bulletFlags = flags and 0xF
            }
            if (m and (1 shl 7) != 0) pf.bulletChar = take2()?.and(0xFFFF)
            if (m and (1 shl 4) != 0) pf.bulletFontRef = take2()?.and(0xFFFF)
            if (m and (1 shl 6) != 0) pf.bulletSize = take2()
            if (m and (1 shl 5) != 0) pf.bulletColor = take4()
            if (m and (1 shl 11) != 0) pf.align = take2()
            if (m and (1 shl 12) != 0) pf.lineSpacing = take2()
            if (m and (1 shl 13) != 0) pf.spaceBefore = take2()
            if (m and (1 shl 14) != 0) pf.spaceAfter = take2()
            if (m and (1 shl 8) != 0) pf.leftMargin = take2()
            if (m and (1 shl 10) != 0) pf.indent = take2()
            if (m and (1 shl 15) != 0) take2()
            if (m and (1 shl 20) != 0) {
                val count = (take2() ?: 0).coerceIn(0, 4096)
                p = minOf(limit, p + count * 4)
            }
            if (m and (1 shl 16) != 0) take2()
            if (m and (7 shl 17) != 0) take2()
            if (m and (1 shl 21) != 0) take2()
            return pf to p
        }

        fun parseCf(r: PptBytes, start: Int, limit: Int): Pair<PptCharProps, Int> {
            val cf = PptCharProps()
            var p = start
            if (p + 4 > limit) return cf to limit
            val m = r.s32(p)
            p += 4
            fun take2(): Int? = if (p + 2 <= limit) r.s16(p).also { p += 2 } else null
            fun take4(): Int? = if (p + 4 <= limit) r.s32(p).also { p += 4 } else null
            if (m and 0x3EB7 != 0) {
                val style = take2() ?: 0
                cf.styleMask = m and 0x3EB7
                cf.styleBits = style
            }
            if (m and (1 shl 16) != 0) cf.fontRef = take2()?.and(0xFFFF)
            if (m and (1 shl 21) != 0) cf.eaFontRef = take2()?.and(0xFFFF)
            if (m and (1 shl 22) != 0) take2()
            if (m and (1 shl 23) != 0) cf.symbolFontRef = take2()?.and(0xFFFF)
            if (m and (1 shl 17) != 0) cf.size = take2()
            if (m and (1 shl 18) != 0) cf.color = take4()
            if (m and (1 shl 19) != 0) cf.position = take2()
            return cf to p
        }
    }

    class PptBlipRef(val type: Int, val offset: Int, val size: Int, val embedded: ByteArray?)

    private class SlwtEntry(val persistRef: Int, val slideId: Int, val texts: List<PptRawText>)

    private class Parser(val r: PptBytes, val currentUser: ByteArray?) {
        val persist = HashMap<Int, Int>()
        var docRef = -1
        val fonts = ArrayList<PptFont>()
        val envStyles = HashMap<Int, List<PptMasterLevel>>()
        var envCf: PptCharProps? = null
        var envPf: PptParaProps? = null
        val blips = ArrayList<PptBlipRef?>()
        val masters = HashMap<Int, PptSheet>()
        val masterShapeProps = HashMap<Int, PptProps>()
        var parsingMaster = false

        fun run(): PptPresentation {
            buildPersistDirectory()
            val docOffset = persist[docRef] ?: throw PptFormatException("Document container not found")
            val doc = r.record(docOffset, r.data.size)?.takeIf { it.type == RT_DOCUMENT }
                ?: throw PptFormatException("Invalid document container")
            val docKids = r.children(doc)
            val docAtom = docKids.firstOrNull { it.type == RT_DOCUMENT_ATOM }
            val slideW = docAtom?.let { r.s32(it.start) }?.takeIf { it in 100..200_000 } ?: 5760
            val slideH = docAtom?.let { r.s32(it.start + 4) }?.takeIf { it in 100..200_000 } ?: 4320
            val firstSlideNumber = docAtom?.let { r.s16(it.start + 32) }?.takeIf { it in 0..10_000 } ?: 1
            val omitTitlePlace = docAtom?.let { it.length > 37 && r.u8(it.start + 37) != 0 } ?: false

            docKids.firstOrNull { it.type == RT_ENVIRONMENT }?.let { parseEnvironment(it) }
            docKids.firstOrNull { it.type == RT_DRAWING_GROUP }?.let { parseDrawingGroup(it) }
            val docHf = docKids.firstOrNull { it.type == RT_HEADERS_FOOTERS && it.instance == 3 }?.let { parseHeadersFooters(it) }

            val slwt = HashMap<Int, List<SlwtEntry>>()
            for (k in docKids) {
                if (k.type == RT_SLIDE_LIST_WITH_TEXT && k.instance in 0..2 && !slwt.containsKey(k.instance)) {
                    slwt[k.instance] = parseSlideListWithText(k)
                }
            }

            val masterEntries = slwt[1].orEmpty()
            val pending = ArrayList<Pair<SlwtEntry, PptRecord>>()
            for (e in masterEntries) {
                val rec = persist[e.persistRef]?.let { r.record(it, r.data.size) } ?: continue
                if (rec.type != RT_MAIN_MASTER && rec.type != RT_SLIDE) continue
                pending.add(e to rec)
            }
            parsingMaster = true
            for ((e, rec) in pending.sortedBy { if (it.second.type == RT_MAIN_MASTER) 0 else 1 }) {
                masters[e.slideId] = parseSheet(rec, e, if (rec.type == RT_MAIN_MASTER) KIND_MAIN_MASTER else KIND_TITLE_MASTER)
            }
            parsingMaster = false

            val notesText = HashMap<Int, String>()
            for (e in slwt[2].orEmpty()) {
                val parts = ArrayList<String>()
                e.texts.filter { it.textType == 2 }.forEach { parts.add(it.text) }
                val rec = persist[e.persistRef]?.let { r.record(it, r.data.size) }
                if (rec != null && rec.type == RT_NOTES && parts.isEmpty()) {
                    val sheet = parseSheet(rec, e, KIND_NOTES)
                    fun walk(list: List<PptShape>) {
                        for (sh in list) {
                            sh.text?.takeIf { it.textType == 2 }?.let { parts.add(it.plainText) }
                            walk(sh.children)
                        }
                    }
                    walk(sheet.shapes)
                }
                val t = parts.joinToString("\n") { it.replace('\r', '\n').replace('\u000B', '\n').trim() }.trim()
                if (t.isNotEmpty()) notesText[e.slideId] = t
            }

            val slides = ArrayList<PptSheet>()
            for (e in slwt[0].orEmpty()) {
                val rec = persist[e.persistRef]?.let { r.record(it, r.data.size) } ?: continue
                if (rec.type != RT_SLIDE) continue
                slides.add(parseSheet(rec, e, KIND_SLIDE))
                if (slides.size >= 5000) break
            }
            return PptPresentation(slideW, slideH, slides, masters, notesText, fonts, blips, docHf, firstSlideNumber, omitTitlePlace)
        }

        fun buildPersistDirectory() {
            var editOffset = -1
            if (currentUser != null && currentUser.size >= 20) {
                val cu = PptBytes(currentUser)
                val token = cu.s32(12)
                if (token == 0xF3D1C4DF.toInt()) throw PptEncryptedException()
                val off = cu.s32(16)
                if (r.record(off, r.data.size)?.type == RT_USER_EDIT) editOffset = off
            }
            if (editOffset < 0) {
                var p = 0
                while (true) {
                    val rec = r.record(p, r.data.size) ?: break
                    if (rec.type == RT_USER_EDIT) editOffset = p
                    if (rec.end <= p) break
                    p = rec.end
                }
            }
            if (editOffset < 0) throw PptFormatException("UserEditAtom not found")
            val visited = HashSet<Int>()
            var off = editOffset
            var first = true
            while (off > 0 || first) {
                if (!visited.add(off) || visited.size > 4096) break
                val ue = r.record(off, r.data.size) ?: break
                if (ue.type != RT_USER_EDIT) break
                if (first) {
                    docRef = r.s32(ue.start + 16)
                    if (ue.length >= 32 && r.s32(ue.start + 28) != 0) throw PptEncryptedException()
                    first = false
                }
                val dirOff = r.s32(ue.start + 12)
                r.record(dirOff, r.data.size)?.takeIf { it.type == RT_PERSIST_DIRECTORY }?.let { dir ->
                    var p = dir.start
                    while (p + 4 <= dir.end) {
                        val h = r.s32(p)
                        val id = h and 0xFFFFF
                        val count = (h ushr 20) and 0xFFF
                        p += 4
                        for (i in 0 until count) {
                            if (p + 4 > dir.end) break
                            persist.putIfAbsent(id + i, r.s32(p))
                            p += 4
                        }
                    }
                }
                off = r.s32(ue.start + 8)
                if (off <= 0) break
            }
        }

        fun parseEnvironment(env: PptRecord) {
            for (k in r.children(env)) {
                when (k.type) {
                    RT_FONT_COLLECTION -> for (f in r.children(k)) {
                        if (f.type != RT_FONT_ENTITY || f.length < 68) continue
                        val nameRaw = r.utf16(f.start, f.start + 64)
                        val name = nameRaw.substringBefore('\u0000').trim()
                        fonts.add(PptFont(name, r.u8(f.start + 67) and 0xF0, r.u8(f.start + 64)))
                    }
                    RT_TEXT_MASTER_STYLE -> envStyles.putIfAbsent(k.instance, parseMasterStyle(k))
                    RT_TEXT_CF_EXCEPTION -> envCf = parseCf(r, k.start, k.end).first
                    RT_TEXT_PF_EXCEPTION -> envPf = parsePf(r, k.start + 2, k.end).first
                }
            }
        }

        fun parseDrawingGroup(dg: PptRecord) {
            val dgg = r.children(dg).firstOrNull { it.type == OA_DGG } ?: return
            val store = r.children(dgg).firstOrNull { it.type == OA_BSTORE } ?: return
            for (e in r.children(store)) {
                if (blips.size >= 10_000) break
                if (e.type != OA_FBSE || e.length < 36) {
                    blips.add(null)
                    continue
                }
                val size = r.s32(e.start + 20)
                val cRef = r.s32(e.start + 24)
                val fo = r.s32(e.start + 28)
                val cbName = r.u8(e.start + 33)
                val embStart = e.start + 36 + cbName
                val embedded = if (embStart + 8 <= e.end) r.data.copyOfRange(embStart, e.end) else null
                blips.add(if (cRef == 0 && embedded == null) null else PptBlipRef(e.instance, fo, size, embedded))
            }
        }

        fun parseHeadersFooters(rec: PptRecord): PptHeadersFooters {
            var flags = 0
            var formatId = 0
            var userDate: String? = null
            var header: String? = null
            var footer: String? = null
            for (k in r.children(rec)) {
                when (k.type) {
                    RT_HEADERS_FOOTERS_ATOM -> {
                        formatId = r.s16(k.start)
                        flags = r.u16(k.start + 2)
                    }
                    RT_CSTRING -> {
                        val s = r.utf16(k.start, k.end)
                        when (k.instance) {
                            0 -> userDate = s
                            1 -> header = s
                            2 -> footer = s
                        }
                    }
                }
            }
            return PptHeadersFooters(flags, formatId, userDate, header, footer)
        }

        fun parseMasterStyle(rec: PptRecord): List<PptMasterLevel> {
            val levels = ArrayList<PptMasterLevel>()
            val count = r.u16(rec.start).coerceAtMost(5)
            var p = rec.start + 2
            for (i in 0 until count) {
                if (rec.instance >= 5) p += 2
                val (pf, p1) = parsePf(r, p, rec.end)
                val (cf, p2) = parseCf(r, p1, rec.end)
                levels.add(PptMasterLevel(pf, cf))
                p = p2
                if (p >= rec.end) break
            }
            return levels
        }

        fun parseSlideListWithText(rec: PptRecord): List<SlwtEntry> {
            val out = ArrayList<SlwtEntry>()
            var curRef = -1
            var curId = 0
            var group = ArrayList<PptRecord>()
            fun flush() {
                if (curRef >= 0) out.add(SlwtEntry(curRef, curId, splitTextBodies(group)))
                group = ArrayList()
            }
            for (k in r.children(rec)) {
                if (k.type == RT_SLIDE_PERSIST_ATOM) {
                    flush()
                    curRef = r.s32(k.start)
                    curId = r.s32(k.start + 12)
                } else {
                    group.add(k)
                }
            }
            flush()
            return out
        }

        fun splitTextBodies(records: List<PptRecord>): List<PptRawText> {
            val out = ArrayList<PptRawText>()
            var i = 0
            while (i < records.size) {
                if (records[i].type != RT_TEXT_HEADER) {
                    i++
                    continue
                }
                var j = i + 1
                while (j < records.size && records[j].type != RT_TEXT_HEADER) j++
                out.add(parseRawText(records.subList(i, j)))
                i = j
            }
            return out
        }

        fun parseRawText(recs: List<PptRecord>): PptRawText {
            val header = recs.first()
            val textType = r.s32(header.start)
            var text = ""
            for (k in recs) {
                if (k.type == RT_TEXT_CHARS) {
                    text = r.utf16(k.start, k.end)
                    break
                }
                if (k.type == RT_TEXT_BYTES) {
                    text = r.latin1(k.start, k.end)
                    break
                }
            }
            val total = text.length + 1
            val pfRuns = ArrayList<Pair<Int, Pair<Int, PptParaProps>>>()
            val cfRuns = ArrayList<Pair<Int, PptCharProps>>()
            var ruler: PptRulerLevels? = null
            val masterLevels = ArrayList<Pair<Int, Int>>()
            val fields = ArrayList<Pair<Int, Int>>()
            val links = ArrayList<Pair<Int, Int>>()
            var lastInteractive = -1
            for (k in recs) {
                when (k.type) {
                    RT_INTERACTIVE_INFO -> lastInteractive = k.instance
                    RT_TX_INTERACTIVE_INFO -> if (lastInteractive == 0) {
                        val b = r.s32(k.start)
                        val e = r.s32(k.start + 4)
                        if (e > b) links.add(b to e)
                    }
                    RT_STYLE_TEXT_PROP -> {
                        var p = k.start
                        var covered = 0
                        while (covered < total && p + 6 <= k.end) {
                            val count = r.s32(p)
                            val level = r.u16(p + 4)
                            val (pf, np) = parsePf(r, p + 6, k.end)
                            val c = count.coerceIn(1, total - covered)
                            pfRuns.add(c to (level to pf))
                            covered += c
                            p = np
                        }
                        covered = 0
                        while (covered < total && p + 4 <= k.end) {
                            val count = r.s32(p)
                            val (cf, np) = parseCf(r, p + 4, k.end)
                            val c = count.coerceIn(1, total - covered)
                            cfRuns.add(c to cf)
                            covered += c
                            p = np
                        }
                    }
                    RT_TEXT_RULER -> ruler = parseRuler(k)
                    RT_MASTER_TEXT_PROP -> {
                        var p = k.start
                        while (p + 6 <= k.end) {
                            masterLevels.add(r.s32(p) to r.u16(p + 4))
                            p += 6
                        }
                    }
                    RT_SLIDE_NUMBER_MC -> fields.add(r.s32(k.start) to FIELD_SLIDE_NUMBER)
                    RT_DATETIME_MC, RT_GENERIC_DATE_MC -> fields.add(r.s32(k.start) to FIELD_DATE)
                    RT_FOOTER_MC -> fields.add(r.s32(k.start) to FIELD_FOOTER)
                    RT_HEADER_MC -> fields.add(r.s32(k.start) to FIELD_HEADER)
                }
            }
            return PptRawText(textType, text, pfRuns, cfRuns, ruler, masterLevels, fields, links)
        }

        fun parseRuler(k: PptRecord): PptRulerLevels {
            val lm = arrayOfNulls<Int>(5)
            val ind = arrayOfNulls<Int>(5)
            var p = k.start
            val mask = r.s32(p)
            p += 4
            if (mask and 0x2 != 0) p += 2
            if (mask and 0x1 != 0) p += 2
            if (mask and 0x4 != 0) {
                val count = r.u16(p).coerceAtMost(4096)
                p += 2 + count * 4
            }
            for (i in 0 until 5) {
                if (mask and (1 shl (3 + i)) != 0) {
                    lm[i] = r.s16(p)
                    p += 2
                }
                if (mask and (1 shl (8 + i)) != 0) {
                    ind[i] = r.s16(p)
                    p += 2
                }
            }
            return PptRulerLevels(lm, ind)
        }

        fun parseSheet(rec: PptRecord, entry: SlwtEntry, kind: Int): PptSheet {
            val kids = r.children(rec)
            val atom = kids.firstOrNull { it.type == RT_SLIDE_ATOM }
            val masterIdRef = atom?.let { r.s32(it.start + 12) } ?: 0
            val notesIdRef = atom?.let { r.s32(it.start + 16) } ?: 0
            val slideFlags = atom?.let { r.u16(it.start + 20) } ?: 0
            val scheme = kids.firstOrNull { it.type == RT_COLOR_SCHEME && it.instance == 1 }?.let { cs ->
                IntArray(8) { i ->
                    val p = cs.start + i * 4
                    (r.u8(p) shl 16) or (r.u8(p + 1) shl 8) or r.u8(p + 2)
                }
            }
            val styles = HashMap<Int, List<PptMasterLevel>>()
            for (k in kids) if (k.type == RT_TEXT_MASTER_STYLE) styles.putIfAbsent(k.instance, parseMasterStyle(k))
            val hf = kids.firstOrNull { it.type == RT_HEADERS_FOOTERS }?.let { parseHeadersFooters(it) }

            val styleMaster: List<Map<Int, List<PptMasterLevel>>> = buildList {
                if (styles.isNotEmpty()) add(styles)
                var m = masters[masterIdRef]
                var guard = 0
                while (m != null && guard++ < 4) {
                    add(m.styles)
                    m = if (m.kind == KIND_TITLE_MASTER) masters[m.masterIdRef] else null
                }
                if (kind == KIND_TITLE_MASTER) {
                    masters.values.firstOrNull { it.kind == KIND_MAIN_MASTER && it.slideId == masterIdRef }?.let { add(it.styles) }
                }
            }
            val resolver = TextResolver(styleMaster, envStyles, envCf, envPf)

            var background: PptShape? = null
            val shapes = ArrayList<PptShape>()
            kids.firstOrNull { it.type == RT_DRAWING }?.let { drawing ->
                val dg = r.children(drawing).firstOrNull { it.type == OA_DG } ?: return@let
                for (k in r.children(dg)) {
                    when (k.type) {
                        OA_SPGR -> {
                            val group = parseGroup(k, entry, resolver, 0)
                            if (group != null) shapes.addAll(group.children)
                        }
                        OA_SP -> {
                            val s = parseShape(k, entry, resolver, emptyList(), 0)
                            if (s != null && s.flags and 0x400 != 0) background = s
                        }
                    }
                }
            }
            return PptSheet(kind, entry.slideId, masterIdRef, notesIdRef, slideFlags, scheme, background, shapes, styles, hf)
        }

        fun parseGroup(rec: PptRecord, entry: SlwtEntry, resolver: TextResolver, depth: Int): PptShape? {
            if (depth > MAX_GROUP_DEPTH) return null
            val kids = r.children(rec)
            if (kids.isEmpty()) return null
            val first = kids.first().takeIf { it.type == OA_SP } ?: return null
            val children = ArrayList<PptShape>()
            for (k in kids.drop(1)) {
                when (k.type) {
                    OA_SPGR -> parseGroup(k, entry, resolver, depth + 1)?.let { children.add(it) }
                    OA_SP -> parseShape(k, entry, resolver, emptyList(), depth + 1)?.let { children.add(it) }
                }
                if (children.size > 20_000) break
            }
            return parseShape(first, entry, resolver, children, depth)
        }

        fun parseShape(rec: PptRecord, entry: SlwtEntry, resolver: TextResolver, children: List<PptShape>, depth: Int): PptShape? {
            var spt = 0
            var spid = 0
            var flags = 0
            var anchor: FloatArray? = null
            var groupRect: FloatArray? = null
            val props = PptProps()
            var placeholder = 0
            var text: PptTextBody? = null
            var foundFsp = false
            for (k in r.children(rec)) {
                when (k.type) {
                    OA_FSP -> {
                        spt = k.instance
                        spid = r.s32(k.start)
                        flags = r.s32(k.start + 4)
                        foundFsp = true
                    }
                    OA_FSPGR -> groupRect = floatArrayOf(
                        r.s32(k.start).toFloat(), r.s32(k.start + 4).toFloat(),
                        r.s32(k.start + 8).toFloat(), r.s32(k.start + 12).toFloat()
                    )
                    OA_FOPT, OA_SECONDARY_FOPT, OA_TERTIARY_FOPT -> props.mergeFrom(r, k)
                    OA_CHILD_ANCHOR -> if (anchor == null) anchor = floatArrayOf(
                        r.s32(k.start).toFloat(), r.s32(k.start + 4).toFloat(),
                        r.s32(k.start + 8).toFloat(), r.s32(k.start + 12).toFloat()
                    )
                    OA_CLIENT_ANCHOR -> anchor = if (k.length >= 16) floatArrayOf(
                        r.s32(k.start + 4).toFloat(), r.s32(k.start).toFloat(),
                        r.s32(k.start + 8).toFloat(), r.s32(k.start + 12).toFloat()
                    ) else floatArrayOf(
                        r.s16(k.start + 2).toFloat(), r.s16(k.start).toFloat(),
                        r.s16(k.start + 4).toFloat(), r.s16(k.start + 6).toFloat()
                    )
                    OA_CLIENT_DATA -> for (c in r.children(k)) {
                        if (c.type == RT_PLACEHOLDER) placeholder = r.u8(c.start + 4)
                    }
                    OA_CLIENT_TEXTBOX -> {
                        val tk = r.children(k)
                        val ref = tk.firstOrNull { it.type == RT_OUTLINE_TEXT_REF }
                        var raw = if (ref != null) {
                            entry.texts.getOrNull(r.s32(ref.start))
                        } else {
                            splitTextBodies(tk).firstOrNull()
                        }
                        if (raw != null && ref != null) {
                            val extraLinks = ArrayList<Pair<Int, Int>>()
                            var lastInteractive = -1
                            for (c in tk) {
                                if (c.type == RT_INTERACTIVE_INFO) lastInteractive = c.instance
                                if (c.type == RT_TX_INTERACTIVE_INFO && lastInteractive == 0) {
                                    val lb = r.s32(c.start)
                                    val le = r.s32(c.start + 4)
                                    if (le > lb) extraLinks.add(lb to le)
                                }
                            }
                            if (extraLinks.isNotEmpty()) {
                                raw = PptRawText(raw.textType, raw.text, raw.pfRuns, raw.cfRuns, raw.ruler, raw.masterLevels, raw.fields, raw.links + extraLinks)
                            }
                        }
                        if (raw != null) {
                            val extraFields = if (ref != null) tk.filter {
                                it.type == RT_SLIDE_NUMBER_MC || it.type == RT_DATETIME_MC || it.type == RT_GENERIC_DATE_MC ||
                                    it.type == RT_FOOTER_MC || it.type == RT_HEADER_MC
                            } else emptyList()
                            text = resolver.resolve(raw, extraFields.map {
                                r.s32(it.start) to when (it.type) {
                                    RT_SLIDE_NUMBER_MC -> FIELD_SLIDE_NUMBER
                                    RT_FOOTER_MC -> FIELD_FOOTER
                                    RT_HEADER_MC -> FIELD_HEADER
                                    else -> FIELD_DATE
                                }
                            })
                        }
                    }
                }
            }
            if (!foundFsp) return null
            if (flags and 0x8 != 0) return null
            val masterSp = if (flags and 0x20 != 0) props.values[0x0301] else null
            props.parent = masterSp?.let { masterShapeProps[it] }?.takeIf { it !== props }
            if (parsingMaster && spid != 0) masterShapeProps.putIfAbsent(spid, props)
            return PptShape(spid, spt, flags, anchor, props, groupRect, children, placeholder, text)
        }
    }

    class TextResolver(
        private val masterStyles: List<Map<Int, List<PptMasterLevel>>>,
        private val envStyles: Map<Int, List<PptMasterLevel>>,
        private val envCf: PptCharProps?,
        private val envPf: PptParaProps?
    ) {
        private fun typeChain(t: Int): IntArray = when (t) {
            5, 7, 8 -> intArrayOf(t, 1)
            6 -> intArrayOf(6, 0)
            else -> intArrayOf(t)
        }

        private fun levels(textType: Int, level: Int): List<PptMasterLevel> {
            val out = ArrayList<PptMasterLevel>()
            val chain = typeChain(textType)
            for (l in level downTo 0) {
                for (t in chain) {
                    for (m in masterStyles) m[t]?.getOrNull(l)?.let { out.add(it) }
                }
            }
            for (l in level downTo 0) {
                for (t in chain) envStyles[t]?.getOrNull(l)?.let { out.add(it) }
                envStyles[4]?.getOrNull(l)?.let { out.add(it) }
            }
            return out
        }

        fun resolve(raw: PptRawText, extraFields: List<Pair<Int, Int>>): PptTextBody {
            val text = raw.text
            val fields = HashMap<Int, Int>()
            for ((pos, f) in raw.fields + extraFields) if (pos in text.indices) fields[pos] = f
            val paragraphs = ArrayList<PptParagraph>()
            var paraStart = 0
            var pfIdx = 0
            var pfEnd = raw.pfRuns.firstOrNull()?.first ?: Int.MAX_VALUE
            var cfIdx = 0
            var cfEnd = raw.cfRuns.firstOrNull()?.first ?: Int.MAX_VALUE
            var mlIdx = 0
            var mlEnd = raw.masterLevels.firstOrNull()?.first ?: Int.MAX_VALUE
            while (paraStart <= text.length) {
                var paraEnd = text.indexOf('\r', paraStart)
                if (paraEnd < 0) paraEnd = text.length
                while (paraStart >= pfEnd && pfIdx + 1 < raw.pfRuns.size) {
                    pfIdx++
                    pfEnd += raw.pfRuns[pfIdx].first
                }
                while (paraStart >= mlEnd && mlIdx + 1 < raw.masterLevels.size) {
                    mlIdx++
                    mlEnd += raw.masterLevels[mlIdx].first
                }
                val pfRun = raw.pfRuns.getOrNull(pfIdx)?.takeIf { paraStart < pfEnd || pfIdx == raw.pfRuns.size - 1 }
                val level = (pfRun?.second?.first ?: raw.masterLevels.getOrNull(mlIdx)?.second ?: 0).coerceIn(0, 4)
                val pf = pfRun?.second?.second
                val chain = levels(raw.textType, level)
                val runs = ArrayList<PptTextRun>()
                var pos = paraStart
                while (pos < paraEnd || (pos == paraEnd && runs.isEmpty())) {
                    while (pos >= cfEnd && cfIdx + 1 < raw.cfRuns.size) {
                        cfIdx++
                        cfEnd += raw.cfRuns[cfIdx].first
                    }
                    val cf = raw.cfRuns.getOrNull(cfIdx)?.second?.takeIf { pos < cfEnd || cfIdx == raw.cfRuns.size - 1 }
                    var segEnd = minOf(paraEnd, if (cfEnd > pos) cfEnd else paraEnd)
                    val fieldHere = fields[pos]
                    if (fieldHere != null) {
                        segEnd = pos + 1
                    } else {
                        for (fp in fields.keys) if (fp in (pos + 1) until segEnd) segEnd = fp
                    }
                    for ((lb, le) in raw.links) {
                        if (lb in (pos + 1) until segEnd) segEnd = lb
                        if (le in (pos + 1) until segEnd) segEnd = le
                    }
                    val inLink = raw.links.any { pos >= it.first && pos < it.second }
                    val style = resolveRun(cf, chain).let { if (inLink) it.asHyperlink() else it }
                    runs.add(PptTextRun(text.substring(pos, segEnd.coerceAtLeast(pos)), style, fieldHere ?: FIELD_NONE))
                    if (segEnd <= pos) break
                    pos = segEnd
                }
                paragraphs.add(resolvePara(pf, chain, level, raw.ruler, runs))
                if (paraEnd >= text.length) break
                paraStart = paraEnd + 1
                if (paragraphs.size > 20_000) break
            }
            return PptTextBody(raw.textType, paragraphs)
        }

        private fun resolveRun(cf: PptCharProps?, chain: List<PptMasterLevel>): PptRunStyle {
            fun <T> pick(get: (PptCharProps) -> T?): T? {
                cf?.let { get(it) }?.let { return it }
                for (l in chain) get(l.cf)?.let { return it }
                envCf?.let { get(it) }?.let { return it }
                return null
            }
            return PptRunStyle(
                sizePt = (pick { it.size } ?: 18).coerceIn(1, 4000).toFloat(),
                bold = pick { it.flag(0x1) } ?: false,
                italic = pick { it.flag(0x2) } ?: false,
                underline = pick { it.flag(0x4) } ?: false,
                shadow = pick { it.flag(0x10) } ?: false,
                color = pick { it.color } ?: (0x01 shl 24),
                fontRef = pick { it.fontRef } ?: 0,
                eaFontRef = pick { it.eaFontRef },
                symbolFontRef = pick { it.symbolFontRef },
                position = pick { it.position } ?: 0
            )
        }

        private fun resolvePara(
            pf: PptParaProps?,
            chain: List<PptMasterLevel>,
            level: Int,
            ruler: PptRulerLevels?,
            runs: List<PptTextRun>
        ): PptParagraph {
            fun <T> pick(get: (PptParaProps) -> T?): T? {
                pf?.let { get(it) }?.let { return it }
                for (l in chain) get(l.pf)?.let { return it }
                envPf?.let { get(it) }?.let { return it }
                return null
            }
            val hasBullet = pick { it.bulletFlag(0x1) } ?: false
            val hasFont = pick { it.bulletFlag(0x2) } ?: false
            val hasColor = pick { it.bulletFlag(0x4) } ?: false
            val hasSize = pick { it.bulletFlag(0x8) } ?: false
            val leftMargin = pf?.leftMargin ?: ruler?.leftMargin?.get(level) ?: pick { it.leftMargin } ?: 0
            val indent = pf?.indent ?: ruler?.indent?.get(level) ?: pick { it.indent } ?: 0
            return PptParagraph(
                runs = runs,
                level = level,
                align = pick { it.align } ?: 0,
                hasBullet = hasBullet,
                bulletChar = pick { it.bulletChar } ?: 0x2022,
                bulletFontRef = if (hasFont) pick { it.bulletFontRef } else null,
                bulletSize = if (hasSize) pick { it.bulletSize } ?: 100 else 100,
                bulletColor = if (hasColor) pick { it.bulletColor } else null,
                lineSpacing = pick { it.lineSpacing } ?: 100,
                spaceBefore = pick { it.spaceBefore } ?: 0,
                spaceAfter = pick { it.spaceAfter } ?: 0,
                leftMargin = leftMargin,
                indent = indent
            )
        }
    }
}
