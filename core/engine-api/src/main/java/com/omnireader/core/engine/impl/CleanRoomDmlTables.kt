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

package com.omnireader.core.engine.impl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max

object DmlTables {
    private const val DEFAULT_STYLE = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"

    private fun node(name: String, attrs: Map<String, String> = emptyMap(), vararg kids: DmlNode): DmlNode =
        DmlNode(name, attrs, kids.toMutableList())

    private fun scheme(name: String, vararg mods: Pair<String, String>): DmlNode =
        node("schemeClr", mapOf("val" to name), *mods.map { node(it.first, mapOf("val" to it.second)) }.toTypedArray())

    private fun builtInDefault(): DmlNode {
        val whiteLine = node("ln", mapOf("w" to "12700"), node("solidFill", emptyMap(), scheme("lt1")))
        fun bdr() = node("tcBdr", emptyMap(),
            node("left", emptyMap(), whiteLine), node("right", emptyMap(), whiteLine),
            node("top", emptyMap(), whiteLine), node("bottom", emptyMap(), whiteLine),
            node("insideH", emptyMap(), whiteLine), node("insideV", emptyMap(), whiteLine))
        return node("tblStyle", mapOf("styleId" to DEFAULT_STYLE),
            node("wholeTbl", emptyMap(),
                node("tcTxStyle", emptyMap(), node("fontRef", mapOf("idx" to "minor")), scheme("dk1")),
                node("tcStyle", emptyMap(), bdr(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1", "tint" to "20000"))))),
            node("band1H", emptyMap(), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1", "tint" to "40000"))))),
            node("band1V", emptyMap(), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1", "tint" to "40000"))))),
            node("lastCol", mapOf(), node("tcTxStyle", mapOf("b" to "on"), scheme("lt1")), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1"))))),
            node("firstCol", mapOf(), node("tcTxStyle", mapOf("b" to "on"), scheme("lt1")), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1"))))),
            node("lastRow", mapOf(), node("tcTxStyle", mapOf("b" to "on"), scheme("lt1")), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1"))))),
            node("firstRow", mapOf(), node("tcTxStyle", mapOf("b" to "on"), scheme("lt1")), node("tcStyle", emptyMap(), node("fill", emptyMap(), node("solidFill", emptyMap(), scheme("accent1")))))
        )
    }

    private val defaultStyle by lazy { builtInDefault() }

    private fun cellBody(tc: DmlNode, tx: DmlNode, lst: DmlNode): Pair<DmlNode, DmlNode> {
        val tcPr = tc.child("tcPr")
        val bodyAttrs = HashMap<String, String>()
        bodyAttrs["lIns"] = (tcPr?.attr("marL") ?: "91440")
        bodyAttrs["rIns"] = (tcPr?.attr("marR") ?: "91440")
        bodyAttrs["tIns"] = (tcPr?.attr("marT") ?: "45720")
        bodyAttrs["bIns"] = (tcPr?.attr("marB") ?: "45720")
        bodyAttrs["anchor"] = tcPr?.attr("anchor") ?: "t"
        tcPr?.attr("vert")?.let { bodyAttrs["vert"] = it }
        val body = node("txBody", emptyMap(), node("bodyPr", bodyAttrs), lst, *tx.all("p").toTypedArray())
        return body to node("sp", emptyMap(), body)
    }

    private fun measureCell(canvas: Canvas, tc: DmlNode, tx: DmlNode, width: Float, ctx: DmlSlideRenderer.Ctx, renderer: DmlSlideRenderer): Float {
        val (body, sp) = cellBody(tc, tx, node("lstStyle"))
        return renderer.drawTextBody(canvas, body, listOf(sp to ctx.sheet), sp, RectF(0f, 0f, width, 1f), ctx, true)
    }

    fun draw(canvas: Canvas, tbl: DmlNode, rect: RectF, ctx: DmlSlideRenderer.Ctx, renderer: DmlSlideRenderer, pres: DmlPresentation) {
        val s = ctx.scale
        val cols = tbl.child("tblGrid")?.all("gridCol")?.map { (it.long("w") ?: 0L) * s }.orEmpty()
        val rows = tbl.all("tr")
        if (cols.isEmpty() || rows.isEmpty()) return
        val tblPr = tbl.child("tblPr")
        fun flag(n: String) = tblPr?.bool(n) == true
        val firstRow = flag("firstRow"); val lastRow = flag("lastRow"); val firstCol = flag("firstCol"); val lastCol = flag("lastCol")
        val bandRow = flag("bandRow"); val bandCol = flag("bandCol")
        val styleId = tblPr?.child("tableStyleId")?.text?.trim()?.uppercase(Locale.ROOT)
        val style = when {
            styleId != null -> pres.tableStyles[styleId] ?: if (styleId == DEFAULT_STYLE) defaultStyle else null
            tblPr?.child("tableStyle") != null -> tblPr.child("tableStyle")
            else -> null
        }
        val heights = rows.map { (it.long("h") ?: 0L) * s }.toMutableList()
        val xs = FloatArray(cols.size + 1).also { a -> for (i in cols.indices) a[i + 1] = a[i] + cols[i] }
        for ((ri, tr) in rows.withIndex()) {
            var ci = 0
            for (tc in tr.all("tc")) {
                if (ci >= cols.size) break
                val span = (tc.int("gridSpan") ?: 1).coerceIn(1, cols.size - ci)
                val vert = tc.child("tcPr")?.attr("vert")
                if (tc.bool("hMerge") != true && tc.bool("vMerge") != true && (tc.int("rowSpan") ?: 1) <= 1 && (vert == null || vert == "horz")) {
                    val tx = tc.child("txBody")
                    if (tx != null) {
                        val need = measureCell(canvas, tc, tx, xs[ci + span] - xs[ci], ctx, renderer)
                        if (need > heights[ri]) heights[ri] = need
                    }
                }
                ci += span
            }
        }
        val ys = FloatArray(rows.size + 1).also { a -> for (i in rows.indices) a[i + 1] = a[i] + heights[i] }
        canvas.save()
        canvas.translate(rect.left, rect.top)
        val tblFill = tblPr?.children?.firstOrNull { it.name in DmlSlideRenderer.FILL_NAMES }
        if (tblFill != null) renderer.paintFill(canvas, Path().apply { addRect(0f, 0f, xs.last(), ys.last(), Path.Direction.CW) }, RectF(0f, 0f, xs.last(), ys.last()), tblFill, ctx, ctx.colors, false)
        class Cell(val r: Int, val c: Int, val tc: DmlNode, val box: RectF, val parts: List<DmlNode>)
        val cells = ArrayList<Cell>()
        val nRows = rows.size
        val nCols = cols.size
        for ((ri, tr) in rows.withIndex()) {
            var ci = 0
            for (tc in tr.all("tc")) {
                if (ci >= nCols) break
                if (tc.bool("hMerge") == true || tc.bool("vMerge") == true) { ci++; continue }
                val span = (tc.int("gridSpan") ?: 1).coerceIn(1, nCols - ci)
                val rspan = (tc.int("rowSpan") ?: 1).coerceIn(1, nRows - ri)
                val box = RectF(xs[ci], ys[ri], xs[ci + span], ys[ri + rspan])
                val parts = ArrayList<DmlNode>()
                if (style != null) {
                    style.child("wholeTbl")?.let { parts.add(it) }
                    val bodyRow = if (firstRow) ri - 1 else ri
                    val bodyCol = if (firstCol) ci - 1 else ci
                    val isHeader = firstRow && ri == 0
                    val isTotal = lastRow && ri == nRows - 1
                    if (bandCol && bodyCol >= 0 && !(lastCol && ci == nCols - 1)) style.child(if (bodyCol % 2 == 0) "band1V" else "band2V")?.let { parts.add(it) }
                    if (bandRow && bodyRow >= 0 && !isHeader && !isTotal) style.child(if (bodyRow % 2 == 0) "band1H" else "band2H")?.let { parts.add(it) }
                    if (lastCol && ci + span - 1 == nCols - 1) style.child("lastCol")?.let { parts.add(it) }
                    if (firstCol && ci == 0) style.child("firstCol")?.let { parts.add(it) }
                    if (isTotal) style.child("lastRow")?.let { parts.add(it) }
                    if (isHeader) style.child("firstRow")?.let { parts.add(it) }
                }
                cells.add(Cell(ri, ci, tc, box, parts))
                ci += span
            }
        }
        for (cell in cells) {
            val tcPr = cell.tc.child("tcPr")
            val own = tcPr?.children?.firstOrNull { it.name in DmlSlideRenderer.FILL_NAMES }
            val path = Path().apply { addRect(cell.box, Path.Direction.CW) }
            if (own != null) {
                renderer.paintFill(canvas, path, cell.box, own, ctx, ctx.colors, false)
            } else {
                for (part in cell.parts.reversed()) {
                    val ts = part.child("tcStyle") ?: continue
                    val f = ts.path("fill")?.children?.firstOrNull { it.name in DmlSlideRenderer.FILL_NAMES }
                    if (f != null) { renderer.paintFill(canvas, path, cell.box, f, ctx, ctx.colors, false); break }
                    val ref = ts.child("fillRef")
                    if (ref != null) {
                        val idx = ref.int("idx") ?: 0
                        ctx.theme.fillStyles.getOrNull(idx - 1)?.let { renderer.paintFill(canvas, path, cell.box, it, ctx, ctx.colors.withPh(ctx.colors.colorIn(ref)), false) }
                        break
                    }
                }
            }
        }
        for (cell in cells) {
            val tcPr = cell.tc.child("tcPr")
            fun styleBorder(edge: String, inside: String, isOuter: Boolean): DmlNode? {
                for (part in cell.parts.reversed()) {
                    val b = part.path("tcStyle", "tcBdr") ?: continue
                    val n = (if (isOuter) b.child(edge) else b.child(inside) ?: b.child(edge)) ?: continue
                    return n.child("ln") ?: n.child("lnRef")?.let { ref -> ctx.theme.lineStyles.getOrNull((ref.int("idx") ?: 0) - 1) }
                }
                return null
            }
            val b = cell.box
            val edges = listOf(
                Triple("lnL", Pair("left", "insideV"), floatArrayOf(b.left, b.top, b.left, b.bottom)) to (cell.c == 0),
                Triple("lnR", Pair("right", "insideV"), floatArrayOf(b.right, b.top, b.right, b.bottom)) to (b.right >= xs.last() - 0.5f),
                Triple("lnT", Pair("top", "insideH"), floatArrayOf(b.left, b.top, b.right, b.top)) to (cell.r == 0),
                Triple("lnB", Pair("bottom", "insideH"), floatArrayOf(b.left, b.bottom, b.right, b.bottom)) to (b.bottom >= ys.last() - 0.5f)
            )
            for ((e, outer) in edges) {
                val ln = tcPr?.child(e.first) ?: styleBorder(e.second.first, e.second.second, outer) ?: continue
                val fill = ln.children.firstOrNull { it.name in DmlSlideRenderer.FILL_NAMES } ?: continue
                if (fill.name == "noFill") continue
                val color = if (fill.name == "solidFill") ctx.colors.colorIn(fill) ?: Color.BLACK else Color.BLACK
                val w = max(0.5f, (ln.long("w") ?: 12700L) * s)
                val pts = e.third
                canvas.drawLine(pts[0], pts[1], pts[2], pts[3], Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = w; this.style = Paint.Style.STROKE })
            }
        }
        for (cell in cells) {
            val tx = cell.tc.child("txBody") ?: continue
            val lst = node("lstStyle")
            val txStyle = cell.parts.reversed().firstNotNullOfOrNull { it.child("tcTxStyle") }
            val bold = cell.parts.reversed().firstNotNullOfOrNull { it.child("tcTxStyle")?.attr("b") }
            val italic = cell.parts.reversed().firstNotNullOfOrNull { it.child("tcTxStyle")?.attr("i") }
            val colorNode = txStyle?.children?.firstOrNull { it.name.endsWith("Clr") }
            val fontRef = txStyle?.child("fontRef")
            for (lvl in 1..9) {
                val attrs = HashMap<String, String>()
                if (bold == "on") attrs["b"] = "1"
                if (italic == "on") attrs["i"] = "1"
                val kids = ArrayList<DmlNode>()
                if (colorNode != null) kids.add(node("solidFill", emptyMap(), colorNode))
                else fontRef?.children?.firstOrNull { it.name.endsWith("Clr") }?.let { kids.add(node("solidFill", emptyMap(), it)) }
                lst.children.add(node("lvl${lvl}pPr", emptyMap(), node("defRPr", attrs, *kids.toTypedArray())))
            }
            val (body, sp) = cellBody(cell.tc, tx, lst)
            renderer.drawTextBody(canvas, body, listOf(sp to ctx.sheet), sp, cell.box, ctx)
        }
        canvas.restore()
    }
}
