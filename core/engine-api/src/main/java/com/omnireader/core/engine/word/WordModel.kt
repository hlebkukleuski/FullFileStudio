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

package com.omnireader.core.engine.word

import android.graphics.Bitmap

internal class WBorder(val type: Int, val width8: Int, val color: Int, val space: Int, val shadow: Boolean = false) {
    val visible get() = type != 0 && type != 0xFF && width8 > 0
    val widthPt: Float get() = when (type) { 5 -> 0.75f; 3 -> maxOf(width8 / 8f, 0.25f) * 3f; else -> width8 / 8f }

    companion object {
        val NONE = WBorder(0, 0, 0, 0)
    }
}

internal class WShading(val fore: Int, val back: Int, val pattern: Int) {
    fun resolve(): Int? {
        val f = if (fore == AUTO) 0xFF000000.toInt() else fore
        val b = if (back == AUTO) null else back
        val pct = when (pattern) {
            0 -> 0; 1 -> 1000; 2 -> 50; 3 -> 100; 4 -> 200; 5 -> 250; 6 -> 300; 7 -> 400; 8 -> 500; 9 -> 600; 10 -> 700; 11 -> 750; 12 -> 800; 13 -> 900
            in 35..62 -> (pattern - 33) * 25
            else -> if (pattern >= 14) 500 else 0
        }
        if (pct == 0) return b
        val base = b ?: 0xFFFFFFFF.toInt()
        if (pct >= 1000) return f
        fun mix(sh: Int): Int { val a = (base ushr sh) and 0xFF; val c = (f ushr sh) and 0xFF; return (a + (c - a) * pct / 1000) and 0xFF }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    companion object {
        const val AUTO = 1
    }
}

internal class WTab(val pos: Int, val jc: Int, val leader: Int)

internal class CharProps {
    var fontAscii: Int = 0
    var fontEa: Int = 0
    var fontOther: Int = 0
    var fontBi: Int = 0
    var size: Int = 20
    var sizeBi: Int = 20
    var bold = false
    var italic = false
    var boldBi = false
    var italicBi = false
    var strike = false
    var dstrike = false
    var caps = false
    var smallCaps = false
    var hidden = false
    var outline = false
    var shadow = false
    var emboss = false
    var imprint = false
    var underline = 0
    var color: Int = WShading.AUTO
    var underlineColor: Int = WShading.AUTO
    var highlight = 0
    var shading: WShading? = null
    var border: WBorder? = null
    var vertAlign = 0
    var position = 0
    var spacing = 0
    var scale = 100
    var kern = 0
    var istd = 10
    var special = false
    var picLocation = -1
    var data = false
    var ole2 = false
    var deleted = false
    var fldVanish = false
    var symbolFont = -1
    var symbolChar = 0
    var complex = false
    var rtl = false
    var objectId = -1
    var lid = 0

    fun copy(): CharProps {
        val c = CharProps()
        c.fontAscii = fontAscii; c.fontEa = fontEa; c.fontOther = fontOther; c.fontBi = fontBi; c.size = size; c.sizeBi = sizeBi
        c.bold = bold; c.italic = italic; c.boldBi = boldBi; c.italicBi = italicBi; c.strike = strike; c.dstrike = dstrike
        c.caps = caps; c.smallCaps = smallCaps; c.hidden = hidden; c.outline = outline; c.shadow = shadow; c.emboss = emboss
        c.imprint = imprint; c.underline = underline; c.color = color; c.underlineColor = underlineColor; c.highlight = highlight
        c.shading = shading; c.border = border; c.vertAlign = vertAlign; c.position = position; c.spacing = spacing; c.scale = scale; c.kern = kern
        c.istd = istd; c.special = special; c.picLocation = picLocation; c.data = data; c.ole2 = ole2; c.deleted = deleted
        c.fldVanish = fldVanish; c.symbolFont = symbolFont; c.symbolChar = symbolChar; c.complex = complex; c.rtl = rtl; c.objectId = objectId; c.lid = lid
        return c
    }

    fun sameLook(o: CharProps): Boolean =
        fontAscii == o.fontAscii && fontEa == o.fontEa && fontOther == o.fontOther && fontBi == o.fontBi && size == o.size && sizeBi == o.sizeBi &&
            bold == o.bold && italic == o.italic && boldBi == o.boldBi && italicBi == o.italicBi && strike == o.strike && dstrike == o.dstrike &&
            caps == o.caps && smallCaps == o.smallCaps && hidden == o.hidden && underline == o.underline && color == o.color &&
            underlineColor == o.underlineColor && highlight == o.highlight && shading === o.shading && border === o.border &&
            vertAlign == o.vertAlign && position == o.position && spacing == o.spacing && scale == o.scale && kern == o.kern && complex == o.complex &&
            rtl == o.rtl && outline == o.outline && shadow == o.shadow && emboss == o.emboss && imprint == o.imprint
}

internal class TableProps {
    var centers = IntArray(0)
    var cellFlags = IntArray(0)
    var cellWidths = IntArray(0)
    var cellBorders: Array<Array<WBorder?>> = emptyArray()
    var cellShading: Array<WShading?> = emptyArray()
    var cellPadding: Array<IntArray> = emptyArray()
    var gapHalf = 0
    var dxaLeft = 0
    var jc = 0
    var rowHeight = 0
    var header = false
    var cantSplit = false
    var tableBorders: Array<WBorder?> = arrayOfNulls(6)
    var defaultPadding = intArrayOf(-1, -1, -1, -1)
    var widthBefore = 0
    var widthAfter = 0
    var tableIndent = 0
    var hasIndent = false
    var cellSpacing = 0
    var bidi = false
    var tableShading: WShading? = null

    val cellCount get() = maxOf(0, centers.size - 1)

    fun ensure(n: Int) {
        if (cellFlags.size < n) cellFlags = cellFlags.copyOf(n)
        if (cellWidths.size < n) cellWidths = cellWidths.copyOf(n)
        if (cellBorders.size < n) cellBorders = Array(n) { i -> cellBorders.getOrNull(i) ?: arrayOfNulls(4) }
        if (cellShading.size < n) cellShading = Array(n) { i -> cellShading.getOrNull(i) }
        if (cellPadding.size < n) cellPadding = Array(n) { i -> cellPadding.getOrNull(i) ?: intArrayOf(-1, -1, -1, -1) }
    }
}

internal class ParaProps {
    var istd = 0
    var jc = 0
    var indLeft = 0
    var indRight = 0
    var indFirst = 0
    var spBefore = 0
    var spAfter = 0
    var beforeAuto = false
    var afterAuto = false
    var lineValue = 240
    var lineMult = true
    var keep = false
    var keepNext = false
    var pageBreakBefore = false
    var widowControl = true
    var inTable = false
    var ttp = false
    var itap = 0
    var innerCell = false
    var innerTtp = false
    var ilfo = 0
    var ilvl = 0
    var tabs = ArrayList<WTab>()
    val borders: Array<WBorder?> = arrayOfNulls(5)
    var shading: WShading? = null
    var contextualSpacing = false
    var outlineLvl = 9
    var bidi = false
    var frame = false
    var frameX = 0
    var frameY = 0
    var frameW = 0
    var frameH = 0
    var frameMinH = false
    var framePc = 0
    var frameWrap = 0
    var frameFromX = 0
    var frameFromY = 0
    var dropCap = 0
    var dropLines = 0
    var noAutoHyph = false
    var table: TableProps? = null

    fun frameKey(): String = "$frameX|$frameY|$frameW|$frameH|$framePc|$frameWrap|$dropCap"

    fun copy(): ParaProps {
        val p = ParaProps()
        p.istd = istd; p.jc = jc; p.indLeft = indLeft; p.indRight = indRight; p.indFirst = indFirst; p.spBefore = spBefore; p.spAfter = spAfter
        p.beforeAuto = beforeAuto; p.afterAuto = afterAuto; p.lineValue = lineValue; p.lineMult = lineMult; p.keep = keep; p.keepNext = keepNext
        p.pageBreakBefore = pageBreakBefore; p.widowControl = widowControl; p.inTable = inTable; p.ttp = ttp; p.itap = itap; p.innerCell = innerCell
        p.innerTtp = innerTtp; p.ilfo = ilfo; p.ilvl = ilvl; p.tabs = ArrayList(tabs)
        for (i in borders.indices) p.borders[i] = borders[i]
        p.shading = shading; p.contextualSpacing = contextualSpacing; p.outlineLvl = outlineLvl; p.bidi = bidi; p.frame = frame
        p.frameX = frameX; p.frameY = frameY; p.frameW = frameW; p.frameH = frameH; p.frameMinH = frameMinH; p.framePc = framePc
        p.frameWrap = frameWrap; p.frameFromX = frameFromX; p.frameFromY = frameFromY; p.dropCap = dropCap; p.dropLines = dropLines; p.noAutoHyph = noAutoHyph
        return p
    }
}

internal class SectionProps {
    var pageWidth = 12240
    var pageHeight = 15840
    var marginLeft = 1800
    var marginRight = 1800
    var marginTop = 1440
    var marginBottom = 1440
    var headerTop = 720
    var footerBottom = 720
    var gutter = 0
    var columns = 1
    var columnSpacing = 720
    var columnWidths = IntArray(0)
    var columnSpacings = IntArray(0)
    var evenlySpaced = true
    var breakCode = 2
    var titlePage = false
    var pageNumberStart = 1
    var restartPageNumbers = false
    var pageNumberFormat = 0
    var verticalAlign = 0
    var landscape = false
    var lineBetween = false
    val pageBorders: Array<WBorder?> = arrayOfNulls(4)
}

internal class WImage(
    val widthTw: Int, val heightTw: Int, val kind: Int = 0,
    val hrPct: Int = 0, val hrAlign: Int = 1, val hrNoShade: Boolean = false, val hrColor: Int = 0xFFA0A0A0.toInt(),
    private val loader: () -> Bitmap?
) {
    @Volatile private var cached: Bitmap? = null
    @Volatile private var failed = false

    fun bitmap(): Bitmap? {
        cached?.let { if (!it.isRecycled) return it }
        if (failed) return null
        synchronized(this) {
            cached?.let { if (!it.isRecycled) return it }
            val b = try { loader() } catch (e: Throwable) { null }
            if (b == null) failed = true
            cached = b
            return b
        }
    }

    fun release() { cached?.recycle(); cached = null }
}

internal sealed class WInline {
    class Text(val text: String, val props: CharProps) : WInline()
    class Picture(val image: WImage, val props: CharProps) : WInline()
    class Field(val kind: Int, val props: CharProps, val format: Int = 0) : WInline()
    class FootnoteRef(val label: String, val props: CharProps, val noteIndex: Int, val endnote: Boolean) : WInline()
    class Anchor(val shape: WFloating, val props: CharProps) : WInline()
    class CommentRef(val commentIndex: Int, val props: CharProps) : WInline()

    companion object {
        const val FIELD_PAGE = 1
        const val FIELD_NUMPAGES = 2
        const val FIELD_SECTIONPAGES = 3
    }
}

internal class WFloating(
    val image: WImage?, val blocks: List<WBlock>, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val relX: Int, val relY: Int, val wrap: Int, val wrapSide: Int, val behind: Boolean, val fill: Int?, val line: WBorder?,
    val children: List<WFloating> = emptyList(), val insets: IntArray = intArrayOf(144, 72, 144, 72), val shapeType: Int = 1
)

internal sealed class WBlock

internal class WParagraph(val props: ParaProps, val markProps: CharProps, val inlines: List<WInline>) : WBlock() {
    var listLabel: String? = null
    var listLabelProps: CharProps? = null
    var listFollow = 0
    var listJc = 0
    var sectionBreakAfter: Int = -1
    var styleName: String? = null
}

internal class WCell(val blocks: List<WBlock>)

internal class WRow(val cells: List<WCell>, val props: TableProps)

internal class WTable(val rows: List<WRow>) : WBlock()

internal class WHeaderSet(val evenHeader: List<WBlock>?, val oddHeader: List<WBlock>?, val evenFooter: List<WBlock>?, val oddFooter: List<WBlock>?, val firstHeader: List<WBlock>?, val firstFooter: List<WBlock>?)

internal class WSection(val props: SectionProps, val blocks: List<WBlock>, val headers: WHeaderSet)

internal class WNote(val label: String, val blocks: List<WBlock>)

internal class WComment(val author: String, val initials: String, val text: String, val cpStart: Int = -1, val cpEnd: Int = -1, val refCp: Int = -1)

internal class WDocument(
    val sections: List<WSection>,
    val fonts: List<String>,
    val footnotes: List<WNote>,
    val endnotes: List<WNote>,
    val facingPages: Boolean,
    val defaultTab: Int,
    val plainText: String,
    val collapseSpacing: Boolean = false,
    val normalChar: CharProps = CharProps(),
    val normalPara: ParaProps = ParaProps(),
    val autoHyphen: Boolean = false,
    val autoHyphenCaps: Boolean = true,
    val hotZoneTw: Int = 360,
    val consecHyphLim: Int = 0,
    val lid: Int = 0x0409,
    val comments: List<WComment> = emptyList()
) {
    fun fontName(ftc: Int): String? = fonts.getOrNull(ftc)
}
