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

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.TabStopSpan
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class DmlSlideRenderer(private val pres: DmlPresentation) {

    class Ctx(
        val sheet: DmlSheet,
        val colors: DmlColorContext,
        val theme: DmlTheme,
        val scale: Float,
        val slideNumber: Int,
        val hiddenIds: Set<String>,
        val groupFill: DmlNode? = null,
        val groupCtx: Ctx? = null,
        val map: FloatArray? = null
    ) {
        fun forSheet(s: DmlSheet): Ctx {
            val th = DmlThemeHolder.themeOf(s)
            return Ctx(s, DmlColorContext(th, s.clrMap), th, scale, slideNumber, hiddenIds)
        }
    }

    private object DmlThemeHolder {
        var pres: DmlPresentation? = null
        fun themeOf(s: DmlSheet): DmlTheme = pres?.themeOf(s) ?: DmlTheme(null)
    }

    init {
        DmlThemeHolder.pres = pres
    }

    private class Xform(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float, val flipH: Boolean, val flipV: Boolean)

    fun draw(canvas: Canvas, index: Int, left: Float, top: Float, scale: Float, hiddenIds: Set<String> = emptySet()) {
        val slide = pres.slides.getOrNull(index) ?: return
        val theme = pres.themeOf(slide)
        val ctx = Ctx(slide, DmlColorContext(theme, slide.clrMap), theme, scale, index + 1, hiddenIds)
        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, pres.slideWidth * scale, pres.slideHeight * scale)
        drawBackground(canvas, slide, ctx)
        val layout = slide.parent
        val master = layout?.parent
        if (slide.showMasterShapes) {
            if (master != null && layout.showMasterShapes) drawTree(canvas, master.spTree, ctx.forSheet(master).let { Ctx(it.sheet, ctx.colors, ctx.theme, scale, ctx.slideNumber, emptySet()) }, true, 0)
            if (layout != null) drawTree(canvas, layout.spTree, Ctx(layout, ctx.colors, ctx.theme, scale, ctx.slideNumber, emptySet()), true, 0)
        }
        drawTree(canvas, slide.spTree, ctx, false, 0)
        canvas.restore()
    }

    fun drawAnchored(canvas: Canvas, element: DmlNode, partPath: String, rect: RectF, pxPerEmu: Float) {
        if (pxPerEmu <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        val sheet = DmlSheet(partPath, DmlNode("wsDr", emptyMap()), DmlSheet.KIND_SLIDE, null, emptyMap())
        val theme = pres.theme
        val ctx = Ctx(sheet, DmlColorContext(theme, emptyMap()), theme, pxPerEmu, 1, emptySet())
        val x = (rect.left / pxPerEmu).toLong()
        val y = (rect.top / pxPerEmu).toLong()
        val cx = (rect.width() / pxPerEmu).toLong()
        val cy = (rect.height() / pxPerEmu).toLong()
        canvas.save()
        drawElement(canvas, placed(element, x, y, cx, cy), ctx, 0)
        canvas.restore()
    }

    private fun placed(n: DmlNode, x: Long, y: Long, cx: Long, cy: Long): DmlNode {
        val prName = when (n.name) {
            "graphicFrame" -> null
            "grpSp" -> "grpSpPr"
            else -> "spPr"
        }
        fun newXfrm(old: DmlNode?): DmlNode {
            val attrs = HashMap<String, String>()
            old?.attrs?.let { attrs.putAll(it) }
            val kids = ArrayList<DmlNode>()
            kids.add(DmlNode("off", mapOf("x" to x.toString(), "y" to y.toString())))
            kids.add(DmlNode("ext", mapOf("cx" to cx.toString(), "cy" to cy.toString())))
            if (n.name == "grpSp") {
                val oOff = old?.child("off")
                val oExt = old?.child("ext")
                val chOff = old?.child("chOff") ?: oOff
                val chExt = old?.child("chExt") ?: oExt
                kids.add(DmlNode("chOff", mapOf("x" to (chOff?.long("x") ?: x).toString(), "y" to (chOff?.long("y") ?: y).toString())))
                kids.add(DmlNode("chExt", mapOf("cx" to (chExt?.long("cx")?.takeIf { it > 0 } ?: cx).toString(), "cy" to (chExt?.long("cy")?.takeIf { it > 0 } ?: cy).toString())))
            }
            return DmlNode("xfrm", attrs, kids)
        }
        val copy = DmlNode(n.name, n.attrs, n.children.toMutableList())
        copy.text = n.text
        if (prName == null) {
            val i = copy.children.indexOfFirst { it.name == "xfrm" }
            val nx = newXfrm(copy.children.getOrNull(i))
            if (i >= 0) copy.children[i] = nx else copy.children.add(nx)
            return copy
        }
        val pi = copy.children.indexOfFirst { it.name == prName }
        val pr = copy.children.getOrNull(pi)
        val prCopy = DmlNode(prName, pr?.attrs ?: emptyMap(), pr?.children?.toMutableList() ?: ArrayList())
        val xi = prCopy.children.indexOfFirst { it.name == "xfrm" }
        val nx = newXfrm(prCopy.children.getOrNull(xi))
        if (xi >= 0) prCopy.children[xi] = nx else prCopy.children.add(0, nx)
        if (pi >= 0) copy.children[pi] = prCopy else copy.children.add(prCopy)
        return copy
    }

    private fun drawBackground(canvas: Canvas, slide: DmlSheet, ctx: Ctx) {
        var s: DmlSheet? = slide
        var bg: DmlNode? = null
        var bgSheet: DmlSheet = slide
        var guard = 0
        while (s != null && guard++ < 4) {
            val b = s.root.path("cSld", "bg")
            if (b != null) { bg = b; bgSheet = s; break }
            s = s.parent
        }
        val rect = RectF(0f, 0f, pres.slideWidth * ctx.scale, pres.slideHeight * ctx.scale)
        canvas.drawRect(rect, Paint().apply { color = Color.WHITE })
        bg ?: return
        val bgCtx = Ctx(bgSheet, ctx.colors, ctx.theme, ctx.scale, ctx.slideNumber, emptySet())
        val path = Path().apply { addRect(rect, Path.Direction.CW) }
        val bgPr = bg.child("bgPr")
        if (bgPr != null) {
            fillOf(bgPr)?.let { paintFill(canvas, path, rect, it, bgCtx, ctx.colors, true) }
            return
        }
        val ref = bg.child("bgRef") ?: return
        val idx = ref.int("idx") ?: return
        val ph = ctx.colors.colorIn(ref)
        val style = when {
            idx >= 1001 -> ctx.theme.bgFillStyles.getOrNull(idx - 1001)
            idx >= 1 -> ctx.theme.fillStyles.getOrNull(idx - 1)
            else -> null
        } ?: return
        paintFill(canvas, path, rect, style, bgCtx, ctx.colors.withPh(ph), true)
    }

    private fun drawTree(canvas: Canvas, tree: DmlNode?, ctx: Ctx, templateOnly: Boolean, depth: Int) {
        if (tree == null || depth > 24) return
        for (c in tree.children) {
            if (templateOnly && phOf(c) != null) continue
            drawElement(canvas, c, ctx, depth)
        }
    }

    private fun nvOf(n: DmlNode): DmlNode? = n.children.firstOrNull { it.name.startsWith("nv") && it.name.endsWith("Pr") }

    private fun phOf(n: DmlNode): DmlNode? = nvOf(n)?.path("nvPr", "ph")

    private fun idOf(n: DmlNode): String? = nvOf(n)?.child("cNvPr")?.attr("id")

    private fun isHidden(n: DmlNode): Boolean = nvOf(n)?.child("cNvPr")?.bool("hidden") == true

    private fun normalizedType(t: String?): String = when (t) {
        "title", "ctrTitle" -> "title"
        null, "body", "subTitle", "obj", "chart", "tbl", "clipArt", "dgm", "media", "pic", "sldImg" -> "body"
        else -> t
    }

    private fun findPlaceholder(sheet: DmlSheet?, type: String?, idx: String?, byIdx: Boolean): DmlNode? {
        val tree = sheet?.spTree ?: return null
        val all = ArrayList<DmlNode>()
        fun collect(n: DmlNode, d: Int) {
            if (d > 16) return
            for (c in n.children) {
                if (phOf(c) != null) all.add(c)
                if (c.name == "grpSp") collect(c, d + 1)
            }
        }
        collect(tree, 0)
        if (byIdx && idx != null) {
            all.firstOrNull { phOf(it)?.attr("idx") == idx }?.let { return it }
        }
        val want = type ?: "obj"
        all.firstOrNull { (phOf(it)?.attr("type") ?: "obj") == want }?.let { return it }
        val norm = normalizedType(type)
        return all.firstOrNull { normalizedType(phOf(it)?.attr("type")) == norm }
    }

    private fun chain(n: DmlNode, sheet: DmlSheet): List<Pair<DmlNode, DmlSheet>> {
        val out = ArrayList<Pair<DmlNode, DmlSheet>>()
        out.add(n to sheet)
        val ph = phOf(n) ?: return out
        val type = ph.attr("type")
        val idx = ph.attr("idx")
        var cur = sheet
        var guard = 0
        while (guard++ < 3) {
            val parent = cur.parent ?: break
            val found = findPlaceholder(parent, type, idx, parent.kind == DmlSheet.KIND_LAYOUT) ?: run { cur = parent; null }
            if (found != null) {
                out.add(found to parent)
                cur = parent
            }
            if (parent.kind == DmlSheet.KIND_MASTER) break
        }
        return out
    }

    private fun spPrOf(n: DmlNode): DmlNode? = n.child("spPr") ?: n.child("grpSpPr")

    private fun xfrmOf(n: DmlNode, ch: List<Pair<DmlNode, DmlSheet>>, ctx: Ctx): Xform? {
        val raw = rawXfrm(n, ch) ?: return null
        val m = ctx.map ?: return raw
        return Xform(m[2] + raw.x * m[0], m[3] + raw.y * m[1], raw.w * m[0], raw.h * m[1], raw.rot, raw.flipH, raw.flipV)
    }

    private fun rawXfrm(n: DmlNode, ch: List<Pair<DmlNode, DmlSheet>>): Xform? {
        val x = if (n.name == "graphicFrame") n.child("xfrm") else null
        val node = x ?: ch.firstNotNullOfOrNull { spPrOf(it.first)?.child("xfrm")?.takeIf { xf -> xf.child("off") != null || xf.child("ext") != null } ?: if (it.first.name == "graphicFrame") it.first.child("xfrm") else null }
        node ?: return null
        val off = node.child("off")
        val ext = node.child("ext")
        return Xform(
            (off?.long("x") ?: 0L).toFloat(), (off?.long("y") ?: 0L).toFloat(),
            (ext?.long("cx") ?: 0L).toFloat(), (ext?.long("cy") ?: 0L).toFloat(),
            (node.int("rot") ?: 0) / 60000f, node.bool("flipH") == true, node.bool("flipV") == true
        )
    }

    private fun drawElement(canvas: Canvas, n: DmlNode, ctx: Ctx, depth: Int) {
        if (isHidden(n)) return
        val id = idOf(n)
        if (id != null && id in ctx.hiddenIds) return
        when (n.name) {
            "sp", "cxnSp" -> drawShape(canvas, n, ctx)
            "pic" -> drawPicture(canvas, n, ctx)
            "grpSp" -> drawGroup(canvas, n, ctx, depth)
            "graphicFrame" -> drawGraphicFrame(canvas, n, ctx, depth)
            "contentPart" -> {}
        }
    }

    private fun applyXform(canvas: Canvas, xf: Xform, s: Float): RectF {
        val r = RectF(xf.x * s, xf.y * s, (xf.x + xf.w) * s, (xf.y + xf.h) * s)
        if (xf.rot != 0f) canvas.rotate(xf.rot, r.centerX(), r.centerY())
        return r
    }

    private fun drawGroup(canvas: Canvas, n: DmlNode, ctx: Ctx, depth: Int) {
        val xn = n.path("grpSpPr", "xfrm") ?: run {
            drawTree(canvas, n, ctx, false, depth + 1)
            return
        }
        val off = xn.child("off"); val ext = xn.child("ext"); val chOff = xn.child("chOff"); val chExt = xn.child("chExt")
        val rx = (off?.long("x") ?: 0L).toFloat(); val ry = (off?.long("y") ?: 0L).toFloat()
        val rw = (ext?.long("cx") ?: 0L).toFloat(); val rh = (ext?.long("cy") ?: 0L).toFloat()
        val cx = (chOff?.long("x") ?: off?.long("x") ?: 0L).toFloat(); val cy = (chOff?.long("y") ?: off?.long("y") ?: 0L).toFloat()
        val cw = (chExt?.long("cx") ?: ext?.long("cx") ?: 0L).toFloat(); val chh = (chExt?.long("cy") ?: ext?.long("cy") ?: 0L).toFloat()
        val pm = ctx.map
        val x = if (pm != null) pm[2] + rx * pm[0] else rx
        val y = if (pm != null) pm[3] + ry * pm[1] else ry
        val w = if (pm != null) rw * pm[0] else rw
        val h = if (pm != null) rh * pm[1] else rh
        val s = ctx.scale
        canvas.save()
        val rot = (xn.int("rot") ?: 0) / 60000f
        val pcx = (x + w / 2) * s
        val pcy = (y + h / 2) * s
        if (rot != 0f) canvas.rotate(rot, pcx, pcy)
        if (xn.bool("flipH") == true) canvas.scale(-1f, 1f, pcx, pcy)
        if (xn.bool("flipV") == true) canvas.scale(1f, -1f, pcx, pcy)
        val sx = if (cw != 0f) w / cw else 1f
        val sy = if (chh != 0f) h / chh else 1f
        val map = floatArrayOf(sx, sy, x - cx * sx, y - cy * sy)
        val gFill = fillOf(n.child("grpSpPr")) ?: ctx.groupFill
        drawTree(canvas, n, Ctx(ctx.sheet, ctx.colors, ctx.theme, ctx.scale, ctx.slideNumber, ctx.hiddenIds, gFill, ctx, map), false, depth + 1)
        canvas.restore()
    }

    private fun fillOf(spPr: DmlNode?): DmlNode? = spPr?.children?.firstOrNull { it.name in FILL_NAMES }

    private fun styleRef(n: DmlNode, which: String): DmlNode? = n.path("style", which)

    private fun resolveFill(ch: List<Pair<DmlNode, DmlSheet>>, n: DmlNode, ctx: Ctx): Pair<DmlNode, DmlColorContext>? {
        for ((node, _) in ch) {
            val f = fillOf(spPrOf(node))
            if (f != null && !(f.name == "gradFill" && f.child("gsLst") == null)) return f to ctx.colors
            val ref = styleRef(node, "fillRef") ?: continue
            val idx = ref.int("idx") ?: 0
            val style = when {
                idx >= 1001 -> ctx.theme.bgFillStyles.getOrNull(idx - 1001)
                idx >= 1 -> ctx.theme.fillStyles.getOrNull(idx - 1)
                else -> null
            } ?: return null
            return style to ctx.colors.withPh(ctx.colors.colorIn(ref))
        }
        return null
    }

    private class LineSpec(val width: Float, val fill: DmlNode?, val colors: DmlColorContext, val dash: String?, val custDash: DmlNode?, val cap: String?, val join: String?, val head: DmlNode?, val tail: DmlNode?, val cmpd: String?)

    private fun resolveLine(ch: List<Pair<DmlNode, DmlSheet>>, ctx: Ctx): LineSpec? {
        val lines = ArrayList<Pair<DmlNode, DmlColorContext>>()
        for ((node, _) in ch) {
            spPrOf(node)?.child("ln")?.let { lines.add(it to ctx.colors) }
            val ref = styleRef(node, "lnRef") ?: continue
            val idx = ref.int("idx") ?: 0
            if (idx >= 1) ctx.theme.lineStyles.getOrNull(idx - 1)?.let { lines.add(it to ctx.colors.withPh(ctx.colors.colorIn(ref))) }
            else lines.add(DmlNode("ln", emptyMap(), mutableListOf(DmlNode("noFill", emptyMap()))) to ctx.colors)
        }
        if (lines.isEmpty()) return null
        var fill: DmlNode? = null
        var fillColors = ctx.colors
        for ((l, c) in lines) {
            val f = l.children.firstOrNull { it.name in FILL_NAMES }
            if (f != null) { fill = f; fillColors = c; break }
        }
        if (fill == null || fill.name == "noFill") return null
        val w = lines.firstNotNullOfOrNull { it.first.long("w") }?.toFloat() ?: 9525f
        return LineSpec(
            w, fill, fillColors,
            lines.firstNotNullOfOrNull { it.first.child("prstDash")?.attr("val") },
            lines.firstNotNullOfOrNull { it.first.child("custDash") },
            lines.firstNotNullOfOrNull { it.first.attr("cap") },
            lines.firstNotNullOfOrNull { l -> l.first.children.firstOrNull { it.name == "round" || it.name == "bevel" || it.name == "miter" }?.name },
            lines.firstNotNullOfOrNull { it.first.child("headEnd") },
            lines.firstNotNullOfOrNull { it.first.child("tailEnd") },
            lines.firstNotNullOfOrNull { it.first.attr("cmpd") }
        )
    }

    private fun resolveEffects(ch: List<Pair<DmlNode, DmlSheet>>, ctx: Ctx): Pair<DmlNode, DmlColorContext>? {
        for ((node, _) in ch) {
            spPrOf(node)?.child("effectLst")?.let { return it to ctx.colors }
            val ref = styleRef(node, "effectRef") ?: continue
            val idx = ref.int("idx") ?: 0
            return if (idx >= 1) ctx.theme.effectStyles.getOrNull(idx - 1)?.child("effectLst")?.let { it to ctx.colors.withPh(ctx.colors.colorIn(ref)) } else null
        }
        return null
    }

    private fun geometryOf(ch: List<Pair<DmlNode, DmlSheet>>): Pair<String?, DmlNode?> {
        for ((node, _) in ch) {
            val sp = spPrOf(node) ?: continue
            sp.child("prstGeom")?.let { return (it.attr("prst") ?: "rect") to it.child("avLst") }
            sp.child("custGeom")?.let { return null to it }
        }
        return "rect" to null
    }

    private fun drawShape(canvas: Canvas, n: DmlNode, ctx: Ctx) {
        val ch = chain(n, ctx.sheet)
        val xf = xfrmOf(n, ch, ctx) ?: return
        val s = ctx.scale
        canvas.save()
        val rect = applyXform(canvas, xf, s)
        val (prst, geomNode) = geometryOf(ch)
        val geo = if (prst == null) DmlGeometry.build(null, geomNode, null, rect.width(), rect.height())
        else DmlGeometry.build(prst, null, geomNode, rect.width(), rect.height())
        canvas.save()
        if (xf.flipH) canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
        if (xf.flipV) canvas.scale(1f, -1f, rect.centerX(), rect.centerY())
        canvas.translate(rect.left, rect.top)
        val local = RectF(0f, 0f, rect.width(), rect.height())
        val fill = resolveFill(ch, n, ctx)
        val line = resolveLine(ch, ctx)
        val effects = resolveEffects(ch, ctx)
        val fillPath = Path()
        for (sp in geo.paths) if (sp.fillMode != "none") fillPath.addPath(sp.path)
        effects?.let { drawShadow(canvas, fillPath, if (fill != null && fill.first.name != "noFill") fillPath else null, line, it, ctx) }
        if (fill != null && fill.first.name != "noFill") {
            for (sp in geo.paths) {
                if (sp.fillMode == "none") continue
                val shade = when (sp.fillMode) {
                    "darken" -> 0.6f
                    "darkenLess" -> 0.8f
                    "lighten" -> -0.6f
                    "lightenLess" -> -0.8f
                    else -> 0f
                }
                paintFill(canvas, sp.path, local, fill.first, ctx, fill.second, false, shade)
            }
        }
        if (line != null) {
            val lp = linePaint(line, ctx, local)
            for (sp in geo.paths) {
                if (!sp.stroke) continue
                drawStroke(canvas, sp.path, lp, line, ctx)
            }
            if (line.head != null || line.tail != null) {
                for (sp in geo.paths) if (sp.stroke) drawLineEnds(canvas, sp.path, lp, line)
            }
        }
        canvas.restore()
        val tx = n.child("txBody")
        if (tx != null) {
            val txXfrm = n.child("txXfrm")
            val tr = if (txXfrm != null) {
                val m = ctx.map
                val ox = (txXfrm.child("off")?.long("x") ?: 0L).toFloat(); val oy = (txXfrm.child("off")?.long("y") ?: 0L).toFloat()
                val ew = (txXfrm.child("ext")?.long("cx") ?: 0L).toFloat(); val eh = (txXfrm.child("ext")?.long("cy") ?: 0L).toFloat()
                val x0 = if (m != null) m[2] + ox * m[0] else ox
                val y0 = if (m != null) m[3] + oy * m[1] else oy
                val w0 = if (m != null) ew * m[0] else ew
                val h0 = if (m != null) eh * m[1] else eh
                RectF(x0 * ctx.scale, y0 * ctx.scale, (x0 + w0) * ctx.scale, (y0 + h0) * ctx.scale)
            } else RectF(rect.left + geo.textRect.left, rect.top + geo.textRect.top, rect.left + geo.textRect.right, rect.top + geo.textRect.bottom)
            if (xf.flipV) canvas.rotate(180f, rect.centerX(), rect.centerY())
            drawTextBody(canvas, tx, ch, n, tr, ctx)
        }
        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, path: Path, lp: Paint, line: LineSpec, ctx: Ctx) {
        when (line.cmpd) {
            "dbl", "thickThin", "thinThick" -> {
                val w = lp.strokeWidth
                val outer = Paint(lp).apply { strokeWidth = w }
                canvas.drawPath(path, outer)
                val inner = Paint(lp).apply {
                    strokeWidth = w / 3f
                    shader = null
                    color = Color.WHITE
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                canvas.saveLayer(null, null)
                canvas.drawPath(path, outer)
                canvas.drawPath(path, inner)
                canvas.restore()
            }
            else -> canvas.drawPath(path, lp)
        }
    }

    private fun drawShadow(canvas: Canvas, fillPath: Path, filled: Path?, line: LineSpec?, eff: Pair<DmlNode, DmlColorContext>, ctx: Ctx) {
        val sh = eff.first.child("outerShdw") ?: return
        val color = eff.second.colorIn(sh) ?: Color.argb(100, 0, 0, 0)
        val dist = (sh.long("dist") ?: 0L) * ctx.scale
        val dir = Math.toRadians((sh.int("dir") ?: 0) / 60000.0)
        val blur = (sh.long("blurRad") ?: 0L) * ctx.scale
        val sx = (sh.int("sx") ?: 100000) / 100000f
        val sy = (sh.int("sy") ?: 100000) / 100000f
        val dx = (dist * cos(dir)).toFloat()
        val dy = (dist * sin(dir)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (blur > 0.5f) maskFilter = BlurMaskFilter(blur / 2f, BlurMaskFilter.Blur.NORMAL)
        }
        val target = filled ?: if (line != null) Path(fillPath) else return
        canvas.save()
        canvas.translate(dx, dy)
        if (sx != 1f || sy != 1f) {
            val b = RectF()
            target.computeBounds(b, true)
            canvas.scale(sx, sy, b.left, b.bottom)
        }
        if (filled == null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, (line?.width ?: 9525f) * ctx.scale)
        }
        canvas.drawPath(target, paint)
        canvas.restore()
    }

    private fun linePaint(line: LineSpec, ctx: Ctx, bounds: RectF): Paint {
        val w = max(0.5f, line.width * ctx.scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        val f = line.fill
        when (f?.name) {
            "solidFill" -> p.color = line.colors.colorIn(f) ?: Color.BLACK
            "gradFill" -> {
                val stops = gradientStops(f, line.colors)
                p.color = stops.first.firstOrNull() ?: Color.BLACK
                linearShader(f, bounds, stops)?.let { p.shader = it }
            }
            else -> p.color = Color.BLACK
        }
        p.strokeCap = when (line.cap) {
            "rnd" -> Paint.Cap.ROUND
            "sq" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        p.strokeJoin = when (line.join) {
            "round" -> Paint.Join.ROUND
            "bevel" -> Paint.Join.BEVEL
            else -> Paint.Join.MITER
        }
        val pattern: FloatArray? = when (line.dash) {
            "dot" -> floatArrayOf(1f, 1f)
            "dash" -> floatArrayOf(4f, 3f)
            "lgDash" -> floatArrayOf(8f, 3f)
            "dashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
            "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
            "lgDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
            "sysDash" -> floatArrayOf(3f, 1f)
            "sysDot" -> floatArrayOf(1f, 1f)
            "sysDashDot" -> floatArrayOf(3f, 1f, 1f, 1f)
            "sysDashDotDot" -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
            else -> null
        } ?: line.custDash?.all("ds")?.flatMap { listOf((it.int("d") ?: 100000) / 100000f, (it.int("sp") ?: 100000) / 100000f) }?.toFloatArray()
        if (pattern != null && pattern.size >= 2) {
            val unit = max(w, 1f)
            val round = p.strokeCap != Paint.Cap.BUTT
            p.pathEffect = DashPathEffect(FloatArray(pattern.size) { i ->
                val v = pattern[i] * unit
                if (round) { if (i % 2 == 0) max(0.01f, v - unit) else v + unit } else v
            }, 0f)
        }
        return p
    }

    private fun drawLineEnds(canvas: Canvas, path: Path, lp: Paint, line: LineSpec) {
        val pm = PathMeasure(path, false)
        do {
            val len = pm.length
            if (len <= 0f) continue
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            line.head?.let { end ->
                if (pm.getPosTan(0f, pos, tan)) arrowHead(canvas, end, pos[0], pos[1], -tan[0], -tan[1], lp)
            }
            line.tail?.let { end ->
                if (pm.getPosTan(len, pos, tan)) arrowHead(canvas, end, pos[0], pos[1], tan[0], tan[1], lp)
            }
        } while (pm.nextContour())
    }

    private fun arrowHead(canvas: Canvas, end: DmlNode, x: Float, y: Float, dx: Float, dy: Float, lp: Paint) {
        val type = end.attr("type") ?: return
        if (type == "none") return
        val w = max(lp.strokeWidth, 1f)
        fun f(v: String?) = when (v) { "sm" -> 2f; "lg" -> 5f; else -> 3f }
        val hw = w * f(end.attr("w")) / 2f
        val hl = w * f(end.attr("len"))
        val nx = -dy
        val ny = dx
        val bx = x - dx * hl
        val by = y - dy * hl
        val paint = Paint(lp).apply { pathEffect = null; style = Paint.Style.FILL_AND_STROKE; strokeWidth = w / 2f }
        val p = Path()
        when (type) {
            "oval" -> { canvas.drawOval(RectF(x - hw, y - hw, x + hw, y + hw), paint); return }
            "diamond" -> {
                p.moveTo(x + dx * hl / 2, y + dy * hl / 2); p.lineTo(x + nx * hw, y + ny * hw)
                p.lineTo(x - dx * hl / 2, y - dy * hl / 2); p.lineTo(x - nx * hw, y - ny * hw); p.close()
            }
            "arrow" -> {
                val open = Paint(lp).apply { pathEffect = null; style = Paint.Style.STROKE }
                p.moveTo(bx + nx * hw, by + ny * hw); p.lineTo(x, y); p.lineTo(bx - nx * hw, by - ny * hw)
                canvas.drawPath(p, open)
                return
            }
            "stealth" -> {
                p.moveTo(x, y); p.lineTo(bx + nx * hw, by + ny * hw); p.lineTo(x - dx * hl * 0.6f, y - dy * hl * 0.6f)
                p.lineTo(bx - nx * hw, by - ny * hw); p.close()
            }
            else -> {
                p.moveTo(x, y); p.lineTo(bx + nx * hw, by + ny * hw); p.lineTo(bx - nx * hw, by - ny * hw); p.close()
            }
        }
        canvas.drawPath(p, paint)
    }

    private fun gradientStops(g: DmlNode, colors: DmlColorContext): Pair<IntArray, FloatArray> {
        val stops = g.child("gsLst")?.all("gs").orEmpty().mapNotNull { gs ->
            val c = colors.colorIn(gs) ?: return@mapNotNull null
            c to ((gs.int("pos") ?: 0) / 100000f).coerceIn(0f, 1f)
        }.sortedBy { it.second }
        if (stops.isEmpty()) return intArrayOf(Color.WHITE, Color.WHITE) to floatArrayOf(0f, 1f)
        val cs = ArrayList<Int>()
        val ps = ArrayList<Float>()
        if (stops.first().second > 0f) { cs.add(stops.first().first); ps.add(0f) }
        for (k in stops.indices) {
            if (k > 0) {
                val (c0, p0) = stops[k - 1]
                val (c1, p1) = stops[k]
                if (p1 > p0) for (j in 1 until 8) {
                    val t = j / 8f
                    cs.add(linearBlend(c0, c1, t)); ps.add(p0 + (p1 - p0) * t)
                }
            }
            cs.add(stops[k].first); ps.add(stops[k].second)
        }
        if (stops.last().second < 1f) { cs.add(stops.last().first); ps.add(1f) }
        return cs.toIntArray() to ps.toFloatArray()
    }

    private fun linearBlend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        DmlColors.toSrgb(DmlColors.toLinear(Color.red(a)) + (DmlColors.toLinear(Color.red(b)) - DmlColors.toLinear(Color.red(a))) * t),
        DmlColors.toSrgb(DmlColors.toLinear(Color.green(a)) + (DmlColors.toLinear(Color.green(b)) - DmlColors.toLinear(Color.green(a))) * t),
        DmlColors.toSrgb(DmlColors.toLinear(Color.blue(a)) + (DmlColors.toLinear(Color.blue(b)) - DmlColors.toLinear(Color.blue(a))) * t)
    )

    private fun linearShader(g: DmlNode, r: RectF, stops: Pair<IntArray, FloatArray>): Shader? {
        val lin = g.child("lin")
        val path = g.child("path")
        if (path != null) {
            val ftr = path.child("fillToRect")
            val l = (ftr?.int("l") ?: 0) / 100000f
            val t = (ftr?.int("t") ?: 0) / 100000f
            val rr = (ftr?.int("r") ?: 0) / 100000f
            val b = (ftr?.int("b") ?: 0) / 100000f
            val fx = r.left + r.width() * (l + (1f - l - rr) / 2f)
            val fy = r.top + r.height() * (t + (1f - t - b) / 2f)
            val radius = listOf(
                hypot(fx - r.left, fy - r.top), hypot(fx - r.right, fy - r.top),
                hypot(fx - r.left, fy - r.bottom), hypot(fx - r.right, fy - r.bottom)
            ).maxOrNull()?.coerceAtLeast(1f) ?: 1f
            val (c, p) = stops
            if (path.attr("path") != "circle") return rectGradient(r, l, t, rr, b, c, p)
            return RadialGradient(fx, fy, radius, c, p, Shader.TileMode.CLAMP)
        }
        val angDeg = (lin?.int("ang") ?: 0) / 60000.0
        val scaled = lin?.bool("scaled") == true
        val a = Math.toRadians(angDeg)
        return if (scaled) {
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            val half = (abs(dx) + abs(dy)) / 2f
            val sh = LinearGradient(0.5f - dx * half, 0.5f - dy * half, 0.5f + dx * half, 0.5f + dy * half, stops.first, stops.second, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(Matrix().apply { setScale(r.width(), r.height()); postTranslate(r.left, r.top) })
            sh
        } else {
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            val half = (abs(dx) * r.width() + abs(dy) * r.height()) / 2f
            LinearGradient(r.centerX() - dx * half, r.centerY() - dy * half, r.centerX() + dx * half, r.centerY() + dy * half, stops.first, stops.second, Shader.TileMode.CLAMP)
        }
    }

    private fun rectGradient(r: RectF, l: Float, t: Float, rr: Float, b: Float, colors: IntArray, pos: FloatArray): Shader? {
        val n = 128
        val bmp = try { Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        val px = IntArray(n * n)
        val fx0 = l.coerceIn(0f, 1f)
        val fx1 = (1f - rr).coerceIn(fx0, 1f)
        val fy0 = t.coerceIn(0f, 1f)
        val fy1 = (1f - b).coerceIn(fy0, 1f)
        fun sample(tt: Float): Int {
            if (tt <= pos[0]) return colors[0]
            for (i in 1 until colors.size) if (tt <= pos[i]) {
                val span = pos[i] - pos[i - 1]
                return linearBlend(colors[i - 1], colors[i], if (span <= 0f) 1f else (tt - pos[i - 1]) / span)
            }
            return colors.last()
        }
        for (y in 0 until n) {
            val v = (y + 0.5f) / n
            val dy = when {
                v < fy0 -> if (fy0 > 0f) (fy0 - v) / fy0 else 0f
                v > fy1 -> if (fy1 < 1f) (v - fy1) / (1f - fy1) else 0f
                else -> 0f
            }
            for (x in 0 until n) {
                val u = (x + 0.5f) / n
                val dx = when {
                    u < fx0 -> if (fx0 > 0f) (fx0 - u) / fx0 else 0f
                    u > fx1 -> if (fx1 < 1f) (u - fx1) / (1f - fx1) else 0f
                    else -> 0f
                }
                px[y * n + x] = sample(max(dx, dy))
            }
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(r.width() / n, r.height() / n); postTranslate(r.left, r.top) })
        }
    }

    fun paintFill(canvas: Canvas, path: Path, bounds: RectF, fill: DmlNode, ctx: Ctx, colors: DmlColorContext, background: Boolean, shade: Float = 0f) {
        when (fill.name) {
            "noFill" -> return
            "grpFill" -> {
                val g = ctx.groupFill ?: return
                paintFill(canvas, path, bounds, g, ctx.groupCtx ?: ctx, colors, background, shade)
                return
            }
            "solidFill" -> {
                var c = colors.colorIn(fill) ?: return
                c = applyShade(c, shade)
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
            }
            "gradFill" -> {
                val stops = gradientStops(fill, colors)
                val shaded = if (shade != 0f) stops.first.map { applyShade(it, shade) }.toIntArray() to stops.second else stops
                val pb = RectF()
                path.computeBounds(pb, true)
                val area = if (fill.bool("rotWithShape") == false || background) bounds else if (pb.width() > 0f && pb.height() > 0f) bounds else bounds
                val shader = linearShader(fill, area, shaded) ?: return
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            }
            "pattFill" -> {
                val fg = colors.colorIn(fill.child("fgClr")) ?: Color.BLACK
                val bg = colors.colorIn(fill.child("bgClr")) ?: Color.WHITE
                val bmp = DmlPatterns.bitmap(fill.attr("prst") ?: "pct50", fg, bg)
                val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val px = max(1f, 12700f * 0.75f * ctx.scale)
                sh.setLocalMatrix(Matrix().apply { setScale(px, px) })
                canvas.drawPath(path, Paint().apply { shader = sh })
            }
            "blipFill" -> drawBlipFill(canvas, path, bounds, fill, ctx, colors)
        }
    }

    private fun applyShade(c: Int, shade: Float): Int = when {
        shade > 0f -> Color.argb(Color.alpha(c), (Color.red(c) * shade).toInt(), (Color.green(c) * shade).toInt(), (Color.blue(c) * shade).toInt())
        shade < 0f -> {
            val k = -shade
            Color.argb(Color.alpha(c), (255 - (255 - Color.red(c)) * k).toInt(), (255 - (255 - Color.green(c)) * k).toInt(), (255 - (255 - Color.blue(c)) * k).toInt())
        }
        else -> c
    }

    private fun blipPaint(blip: DmlNode, colors: DmlColorContext): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var cm: ColorMatrix? = null
        for (e in blip.children) {
            when (e.name) {
                "alphaModFix" -> p.alpha = ((e.int("amt") ?: 100000) / 100000f * 255f).toInt().coerceIn(0, 255)
                "grayscl" -> cm = (cm ?: ColorMatrix()).apply { postConcat(ColorMatrix().apply { setSaturation(0f) }) }
                "lum" -> {
                    val bright = (e.int("bright") ?: 0) / 100000f * 255f
                    val contrast = 1f + (e.int("contrast") ?: 0) / 100000f
                    val t = (1f - contrast) * 127.5f + bright
                    cm = (cm ?: ColorMatrix()).apply { postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f))) }
                }
                "duotone" -> {
                    val cs = e.children.mapNotNull { colors.color(it) }
                    if (cs.size >= 2) {
                        val a = cs[0]; val b = cs[1]
                        val gray = ColorMatrix().apply { setSaturation(0f) }
                        val map = ColorMatrix(floatArrayOf(
                            (Color.red(b) - Color.red(a)) / 255f, 0f, 0f, 0f, Color.red(a).toFloat(),
                            (Color.green(b) - Color.green(a)) / 255f, 0f, 0f, 0f, Color.green(a).toFloat(),
                            (Color.blue(b) - Color.blue(a)) / 255f, 0f, 0f, 0f, Color.blue(a).toFloat(),
                            0f, 0f, 0f, 1f, 0f
                        ))
                        gray.postConcat(map)
                        cm = (cm ?: ColorMatrix()).apply { postConcat(gray) }
                    }
                }
                "biLevel" -> cm = (cm ?: ColorMatrix()).apply {
                    postConcat(ColorMatrix().apply { setSaturation(0f) })
                    postConcat(ColorMatrix(floatArrayOf(40f, 0f, 0f, 0f, -5100f, 0f, 40f, 0f, 0f, -5100f, 0f, 0f, 40f, 0f, -5100f, 0f, 0f, 0f, 1f, 0f)))
                }
            }
        }
        cm?.let { p.colorFilter = ColorMatrixColorFilter(it) }
        return p
    }

    private fun drawBlipFill(canvas: Canvas, path: Path, bounds: RectF, fill: DmlNode, ctx: Ctx, colors: DmlColorContext) {
        val blip = fill.child("blip") ?: return
        val rid = blip.attr("r:embed")
        val img = pres.image(ctx.sheet.path, rid)
            ?: pres.themePathOf(ctx.sheet)?.let { pres.image(it, rid) }
            ?: return
        val paint = blipPaint(blip, colors)
        canvas.save()
        canvas.clipPath(path)
        val tile = fill.child("tile")
        if (tile != null) {
            val bmp = img.asBitmap(1024) ?: run { canvas.restore(); return }
            val sx = (tile.int("sx") ?: 100000) / 100000f
            val sy = (tile.int("sy") ?: 100000) / 100000f
            val pxPerImg = 9525f * ctx.scale
            val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sh.setLocalMatrix(Matrix().apply {
                setScale(pxPerImg * sx * (img.width.toFloat() / bmp.width), pxPerImg * sy * (img.height.toFloat() / bmp.height))
                postTranslate(bounds.left + (tile.long("tx") ?: 0L) * ctx.scale, bounds.top + (tile.long("ty") ?: 0L) * ctx.scale)
            })
            canvas.drawRect(bounds, Paint(paint).apply { shader = sh })
        } else {
            val src = fill.child("srcRect")
            val fr = fill.path("stretch", "fillRect")
            val dst = RectF(
                bounds.left + bounds.width() * (fr?.int("l") ?: 0) / 100000f,
                bounds.top + bounds.height() * (fr?.int("t") ?: 0) / 100000f,
                bounds.right - bounds.width() * (fr?.int("r") ?: 0) / 100000f,
                bounds.bottom - bounds.height() * (fr?.int("b") ?: 0) / 100000f
            )
            val cl = (src?.int("l") ?: 0) / 100000f
            val ct = (src?.int("t") ?: 0) / 100000f
            val cr = (src?.int("r") ?: 0) / 100000f
            val cb = (src?.int("b") ?: 0) / 100000f
            val visW = (1f - cl - cr).takeIf { abs(it) > 1e-4f } ?: 1f
            val visH = (1f - ct - cb).takeIf { abs(it) > 1e-4f } ?: 1f
            val fullW = dst.width() / visW
            val fullH = dst.height() / visH
            val full = RectF(dst.left - cl * fullW, dst.top - ct * fullH, dst.left - cl * fullW + fullW, dst.top - ct * fullH + fullH)
            canvas.clipRect(dst)
            img.draw(canvas, full, paint)
        }
        canvas.restore()
    }

    private fun drawPicture(canvas: Canvas, n: DmlNode, ctx: Ctx) {
        val ch = chain(n, ctx.sheet)
        val xf = xfrmOf(n, ch, ctx) ?: return
        canvas.save()
        val rect = applyXform(canvas, xf, ctx.scale)
        if (xf.flipH) canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
        if (xf.flipV) canvas.scale(1f, -1f, rect.centerX(), rect.centerY())
        canvas.translate(rect.left, rect.top)
        val local = RectF(0f, 0f, rect.width(), rect.height())
        val (prst, geomNode) = geometryOf(ch)
        val geo = if (prst == null) DmlGeometry.build(null, geomNode, null, local.width(), local.height())
        else DmlGeometry.build(prst, null, geomNode, local.width(), local.height())
        val clip = Path()
        for (sp in geo.paths) if (sp.fillMode != "none") clip.addPath(sp.path)
        val effects = resolveEffects(ch, ctx)
        effects?.let { drawShadow(canvas, clip, clip, null, it, ctx) }
        val blipFill = n.child("blipFill")
        if (blipFill != null) drawBlipFill(canvas, clip, local, blipFill, ctx, ctx.colors)
        resolveLine(ch, ctx)?.let { line ->
            val lp = linePaint(line, ctx, local)
            for (sp in geo.paths) if (sp.stroke) drawStroke(canvas, sp.path, lp, line, ctx)
        }
        canvas.restore()
    }

    private fun drawGraphicFrame(canvas: Canvas, n: DmlNode, ctx: Ctx, depth: Int) {
        val data = n.path("graphic", "graphicData") ?: return
        val ch = chain(n, ctx.sheet)
        val xf = xfrmOf(n, ch, ctx) ?: return
        val s = ctx.scale
        canvas.save()
        val rect = applyXform(canvas, xf, s)
        when {
            data.child("tbl") != null -> DmlTables.draw(canvas, data.child("tbl")!!, rect, ctx, this, pres)
            data.child("chart") != null -> {
                val cpath = pres.pkg.relTarget(ctx.sheet.path, data.child("chart")?.attr("r:id"))
                val root = cpath?.let { pres.pkg.xml(it) }
                val cells = pres.chartCells
                if (root != null && cells != null) ChartReferenceData.fill(root, cells)
                if (root != null) CleanRoomCharts.drawDrawingMlChart(canvas, root, rect, ctx.colors, s, { rid -> pres.image(cpath, rid)?.asBitmap(1024) }, pres.spreadsheet)
            }
            data.child("relIds") != null -> drawSmartArt(canvas, data.child("relIds")!!, rect, ctx, depth)
            else -> {
                val pic = data.descendants("pic").firstOrNull()
                if (pic != null) {
                    val blip = pic.child("blipFill")
                    if (blip != null) {
                        canvas.save()
                        canvas.translate(rect.left, rect.top)
                        val local = RectF(0f, 0f, rect.width(), rect.height())
                        drawBlipFill(canvas, Path().apply { addRect(local, Path.Direction.CW) }, local, blip, ctx, ctx.colors)
                        canvas.restore()
                    }
                } else {
                    val ole = data.descendants("oleObj").firstOrNull()
                    val img = ole?.let { olePreview(it, ctx) }
                    img?.draw(canvas, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
            }
        }
        canvas.restore()
    }

    private fun olePreview(ole: DmlNode, ctx: Ctx): PptImage? {
        val spid = ole.attr("spid") ?: return null
        val vmlPath = pres.pkg.relByType(ctx.sheet.path, "/vmlDrawing") ?: return null
        val vml = pres.pkg.xml(vmlPath) ?: return null
        val shape = vml.descendants("shape").firstOrNull { it.attr("spid") == spid || it.attr("id") == spid } ?: return null
        val rel = shape.child("imagedata")?.let { it.attr("relid") ?: it.attr("r:id") } ?: return null
        val target = pres.pkg.relTarget(vmlPath, rel) ?: return null
        return pres.imageAt(target)
    }

    private fun drawSmartArt(canvas: Canvas, relIds: DmlNode, rect: RectF, ctx: Ctx, depth: Int) {
        val dataPath = pres.pkg.relTarget(ctx.sheet.path, relIds.attr("r:dm")) ?: return
        val data = pres.pkg.xml(dataPath)
        val drawingRelId = data?.descendants("dataModelExt")?.firstOrNull()?.attr("relId")
        val drawingPath = pres.pkg.relTarget(ctx.sheet.path, drawingRelId)
            ?: pres.pkg.rels(ctx.sheet.path).values.firstOrNull { it.type.endsWith("/diagramDrawing") }?.target
            ?: return
        val drawing = pres.pkg.xml(drawingPath) ?: return
        val tree = drawing.child("spTree") ?: return
        val sub = DmlSheet(drawingPath, drawing, ctx.sheet.kind, ctx.sheet.parent, ctx.sheet.clrMap)
        canvas.save()
        canvas.translate(rect.left, rect.top)
        drawTree(canvas, tree, Ctx(sub, ctx.colors, ctx.theme, ctx.scale, ctx.slideNumber, emptySet()), false, depth + 1)
        canvas.restore()
    }

    private class RunStyle(
        val sizePt: Float,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val color: Int,
        val font: String?,
        val baseline: Float,
        val caps: String?,
        val spacingPt: Float,
        val highlight: Int?,
        val symbolFont: String?,
        val shadow: DmlNode?
    )

    private inner class LevelChain(val nodes: List<DmlNode>) {
        fun attr(name: String): String? = nodes.firstNotNullOfOrNull { it.attr(name) }
        fun child(name: String): DmlNode? = nodes.firstNotNullOfOrNull { it.child(name) }
        fun firstOf(vararg names: String): DmlNode? = nodes.firstNotNullOfOrNull { n -> n.children.firstOrNull { it.name in names } }
    }

    private fun textCategory(ch: List<Pair<DmlNode, DmlSheet>>): String {
        val ph = ch.firstNotNullOfOrNull { phOf(it.first) } ?: return "otherStyle"
        return when (ph.attr("type")) {
            "title", "ctrTitle" -> "titleStyle"
            "dt", "ftr", "sldNum", "hdr" -> "otherStyle"
            else -> "bodyStyle"
        }
    }

    private class LevelSet(val pPr: DmlNode?, val lst: List<DmlNode>, val base: List<DmlNode>) {
        val all: List<DmlNode> get() = listOfNotNull(pPr) + lst + base
    }

    private fun levelNodes(level: Int, ch: List<Pair<DmlNode, DmlSheet>>, ctx: Ctx, pPr: DmlNode?): LevelSet {
        val lv = "lvl${level + 1}pPr"
        val lst = ArrayList<DmlNode>()
        for ((node, _) in ch) node.path("txBody", "lstStyle", lv)?.let { lst.add(it) }
        val base = ArrayList<DmlNode>()
        val master = ctx.sheet.master()
        val cat = textCategory(ch)
        if (ctx.sheet.kind != DmlSheet.KIND_NOTES) {
            master?.root?.path("txStyles", cat, lv)?.let { base.add(it) }
        } else {
            master?.root?.path("notesStyle", lv)?.let { base.add(it) }
        }
        pres.defaultTextStyle?.child(lv)?.let { base.add(it) }
        pres.defaultTextStyle?.child("defPPr")?.let { base.add(it) }
        return LevelSet(pPr, lst, base)
    }

    private fun bodyPrChain(ch: List<Pair<DmlNode, DmlSheet>>): LevelChain = LevelChain(ch.mapNotNull { it.first.path("txBody", "bodyPr") })

    private fun resolveRun(rPr: DmlNode?, levels: LevelSet, fontRef: DmlNode?, ctx: Ctx, category: String, fontScale: Float, isLink: Boolean): RunStyle {
        val upper = ArrayList<DmlNode>()
        if (rPr != null) upper.add(rPr)
        for (l in levels.lst) l.child("defRPr")?.let { upper.add(it) }
        val lower = levels.base.mapNotNull { it.child("defRPr") }
        val nodes = upper + lower
        fun a(name: String): String? = nodes.firstNotNullOfOrNull { it.attr(name) }
        fun c(name: String): DmlNode? = nodes.firstNotNullOfOrNull { it.child(name) }
        fun fillIn(list: List<DmlNode>): DmlNode? = list.firstNotNullOfOrNull { n -> n.children.firstOrNull { it.name == "solidFill" || it.name == "gradFill" || it.name == "noFill" } }
        val sz = (a("sz")?.toFloatOrNull() ?: 1800f) / 100f * fontScale
        val explicitRunFill = rPr?.children?.any { it.name == "solidFill" } == true
        val upperFill = fillIn(upper)
        val refColor = fontRef?.let { ctx.colors.colorIn(it) }
        fun colorOf(f: DmlNode?): Int? = when (f?.name) {
            "solidFill" -> ctx.colors.colorIn(f)
            "gradFill" -> f.child("gsLst")?.child("gs")?.let { ctx.colors.colorIn(it) }
            "noFill" -> Color.TRANSPARENT
            else -> null
        }
        val color = when {
            isLink -> ctx.colors.scheme("hlink")
            upperFill != null -> colorOf(upperFill)
            refColor != null -> refColor
            else -> colorOf(fillIn(lower))
        }
        var font = upper.firstNotNullOfOrNull { it.child("latin")?.attr("typeface") }
        if (font.isNullOrEmpty()) font = when (fontRef?.attr("idx")) {
            "major" -> "+mj-lt"
            "minor" -> "+mn-lt"
            else -> lower.firstNotNullOfOrNull { it.child("latin")?.attr("typeface") } ?: if (category == "titleStyle") "+mj-lt" else "+mn-lt"
        }
        font = when {
            font!!.startsWith("+mj") -> ctx.theme.majorLatin
            font.startsWith("+mn") -> ctx.theme.minorLatin
            else -> font
        }
        val u = a("u")
        val strike = a("strike")
        return RunStyle(
            sizePt = sz.coerceIn(1f, 4000f),
            bold = a("b")?.let { it == "1" || it == "true" } ?: false,
            italic = a("i")?.let { it == "1" || it == "true" } ?: false,
            underline = (u != null && u != "none") || isLink,
            strike = strike != null && strike != "noStrike",
            color = color ?: Color.BLACK,
            font = font,
            baseline = (a("baseline")?.toFloatOrNull() ?: 0f) / 100000f,
            caps = a("cap"),
            spacingPt = (a("spc")?.toFloatOrNull() ?: 0f) / 100f,
            highlight = c("highlight")?.let { ctx.colors.colorIn(it) },
            symbolFont = c("sym")?.attr("typeface"),
            shadow = upper.firstNotNullOfOrNull { it.path("effectLst", "outerShdw") }
        )
    }

    private class RunSpan(val sizePx: Float, val typeface: Typeface, val color: Int, val underline: Boolean, val strike: Boolean, val shift: Int, val letterSpacing: Float, val shadow: FloatArray? = null) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            apply(tp)
            tp.color = color
            tp.isUnderlineText = underline
            tp.isStrikeThruText = strike
            if (shadow != null) tp.setShadowLayer(max(0.5f, shadow[0]), shadow[1], shadow[2], shadow[3].toBits())
        }

        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        private fun apply(tp: TextPaint) {
            tp.textSize = sizePx
            tp.typeface = typeface
            tp.baselineShift += shift
            tp.letterSpacing = letterSpacing
        }
    }

    private class ExactLineHeight(val px: Float) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val h = fm.descent - fm.ascent
            if (h <= 0) return
            val extra = (px - h).toInt()
            fm.ascent -= extra
            fm.top = min(fm.top, fm.ascent)
            if (extra < 0) fm.top = fm.ascent
        }
    }

    private class LaidPara(
        val layout: StaticLayout,
        val before: Float,
        val after: Float,
        val bullet: String?,
        val bulletPaint: TextPaint?,
        val bulletX: Float,
        val bulletImage: PptImage?,
        val align: String,
        val leftPx: Float
    )

    private fun romanize(n: Int, upper: Boolean): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return if (upper) sb.toString() else sb.toString().lowercase(Locale.ROOT)
    }

    private fun alpha(n: Int, upper: Boolean): String {
        if (n <= 0) return n.toString()
        val letter = ('a' + (n - 1) % 26)
        val s = letter.toString().repeat((n - 1) / 26 + 1)
        return if (upper) s.uppercase(Locale.ROOT) else s
    }

    private fun autoNumText(type: String, n: Int): String {
        val core = when {
            type.startsWith("roman") -> romanize(n, type.startsWith("romanUc"))
            type.startsWith("alpha") -> alpha(n, type.startsWith("alphaUc"))
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($core)"
            type.endsWith("ParenR") -> "$core)"
            type.endsWith("Period") -> "$core."
            type.endsWith("Minus") -> "- $core -"
            type.endsWith("Colon") -> "$core:"
            else -> core
        }
    }

    fun drawTextBody(canvas: Canvas, tx: DmlNode, ch: List<Pair<DmlNode, DmlSheet>>, shape: DmlNode?, rect: RectF, ctx: Ctx, measureOnly: Boolean = false, forceScale: Float? = null, forceLn: Float? = null): Float {
        val paras = tx.all("p")
        if (paras.isEmpty()) return 0f
        if (paras.all { p -> p.children.none { (it.name == "r" || it.name == "fld") && !(it.child("t")?.text.isNullOrEmpty()) } }) return 0f
        val s = ctx.scale
        val bp = bodyPrChain(ch)
        val lIns = (bp.attr("lIns")?.toFloatOrNull() ?: 91440f) * s
        val tIns = (bp.attr("tIns")?.toFloatOrNull() ?: 45720f) * s
        val rIns = (bp.attr("rIns")?.toFloatOrNull() ?: 91440f) * s
        val bIns = (bp.attr("bIns")?.toFloatOrNull() ?: 45720f) * s
        val vert = bp.attr("vert") ?: "horz"
        val wrap = bp.attr("wrap") != "none"
        val anchor = bp.attr("anchor") ?: "t"
        val anchorCtr = bp.attr("anchorCtr")?.let { it == "1" || it == "true" } ?: false
        val autofit = ch.firstOrNull()?.first?.path("txBody", "bodyPr")?.children?.firstOrNull { it.name == "normAutofit" || it.name == "spAutoFit" || it.name == "noAutofit" }
        val fontScale = forceScale ?: if (autofit?.name == "normAutofit") (autofit.int("fontScale") ?: 100000) / 100000f else 1f
        val lnReduction = forceLn ?: if (autofit?.name == "normAutofit") (autofit.int("lnSpcReduction") ?: 0) / 100000f else 0f
        val bodyRot = (bp.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
        val fontRef = shape?.path("style", "fontRef")
        val category = textCategory(ch)
        canvas.save()
        var box = RectF(rect.left + lIns, rect.top + tIns, rect.right - rIns, rect.bottom - bIns)
        val rotateDeg = when (vert) {
            "vert", "eaVert", "wordArtVertRtl" -> 90f
            "vert270" -> 270f
            else -> 0f
        } + bodyRot
        if (rotateDeg != 0f) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.rotate(rotateDeg, cx, cy)
            if (((rotateDeg % 180f) + 180f) % 180f == 90f) {
                val w = rect.height()
                val h = rect.width()
                box = RectF(cx - w / 2f + tIns, cy - h / 2f + lIns, cx + w / 2f - bIns, cy + h / 2f - rIns)
            }
        }
        val width = max(1f, box.width())
        val laid = ArrayList<LaidPara>()
        val counters = IntArray(9)
        var total = 0f
        for ((pi, p) in paras.withIndex()) {
            val pPr = p.child("pPr")
            val level = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
            val levelSet = levelNodes(level, ch, ctx, pPr)
            val lv = LevelChain(levelSet.all)
            val runsInPara = p.children.filter { it.name == "r" || it.name == "fld" || it.name == "br" }
            val endRPr = p.child("endParaRPr")
            val sb = SpannableStringBuilder()
            var maxSize = 0f
            var firstStyle: RunStyle? = null
            for (r in runsInPara) {
                if (r.name == "br") {
                    sb.append('\n')
                    continue
                }
                val rPr = r.child("rPr")
                val isLink = rPr?.child("hlinkClick") != null
                val st = resolveRun(rPr, levelSet, fontRef, ctx, category, fontScale, isLink)
                var text = r.child("t")?.text ?: ""
                if (r.name == "fld") {
                    val type = r.attr("type") ?: ""
                    if (type == "slidenum") text = ctx.slideNumber.toString()
                }
                if (st.caps == "all") text = text.uppercase(Locale.getDefault())
                val symFont = st.symbolFont
                if (symFont != null && PptFonts.isSymbolFont(PptFont(symFont, 0, 0))) text = PptFonts.mapSymbols(text, PptFont(symFont, 0, 0))
                if (PptFonts.isSymbolFont(PptFont(st.font ?: "", 0, 0))) text = PptFonts.mapSymbols(text, PptFont(st.font ?: "", 0, 0))
                if (firstStyle == null && text.isNotEmpty()) firstStyle = st
                var sizePx = st.sizePt * 12700f * s
                maxSize = max(maxSize, sizePx)
                var shift = 0
                if (st.baseline != 0f) {
                    shift = -(st.baseline * sizePx).toInt()
                    sizePx *= 0.67f
                }
                if (text.isEmpty()) continue
                val start = sb.length
                sb.append(text)
                val tf = OfficeFonts.resolve(st.font, st.bold, st.italic)
                val shadowSpec = st.shadow?.let { sh ->
                    val dist = (sh.long("dist") ?: 0L) * s
                    val dir = Math.toRadians((sh.int("dir") ?: 0) / 60000.0)
                    val col = ctx.colors.colorIn(sh) ?: Color.argb(110, 0, 0, 0)
                    floatArrayOf((sh.long("blurRad") ?: 0L) * s / 2f, (dist * cos(dir)).toFloat(), (dist * sin(dir)).toFloat(), Float.fromBits(col))
                }
                sb.setSpan(RunSpan(sizePx, tf, st.color, st.underline, st.strike, shift, if (st.sizePt > 0) st.spacingPt / st.sizePt else 0f, shadowSpec), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                st.highlight?.let { sb.setSpan(BackgroundColorSpan(it), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                if (st.caps == "small") {
                    for (k in start until sb.length) if (sb[k].isLowerCase()) sb.replace(k, k + 1, sb[k].uppercaseChar().toString())
                }
            }
            val endStyle = resolveRun(endRPr, levelSet, fontRef, ctx, category, fontScale, false)
            val baseStyle = firstStyle ?: endStyle
            if (maxSize <= 0f) maxSize = baseStyle.sizePt * 12700f * s
            val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = maxSize
                typeface = OfficeFonts.resolve(baseStyle.font, baseStyle.bold, baseStyle.italic)
                color = baseStyle.color
            }
            val marL = (lv.attr("marL")?.toFloatOrNull() ?: 0f) * s
            val indent = (lv.attr("indent")?.toFloatOrNull() ?: 0f) * s
            val algn = lv.attr("algn") ?: "l"
            val hasText = sb.isNotEmpty()
            var bulletText: String? = null
            var bulletPaint: TextPaint? = null
            var bulletImage: PptImage? = null
            val bu = lv.firstOf("buNone", "buChar", "buAutoNum", "buBlip")
            for (k in level + 1 until 9) counters[k] = 0
            if (bu?.name == "buAutoNum" && hasText) {
                val start = bu.int("startAt") ?: 1
                counters[level] = if (counters[level] == 0) start else counters[level] + 1
            } else if (hasText) {
                counters[level] = 0
            }
            var firstLine = marL + indent
            if (hasText && bu != null && bu.name != "buNone") {
                val buFontNode = lv.firstOf("buFont", "buFontTx")
                val buFont = if (buFontNode?.name == "buFont") buFontNode.attr("typeface") else baseStyle.font
                val buClrNode = lv.firstOf("buClr", "buClrTx")
                val buColor = if (buClrNode?.name == "buClr") ctx.colors.colorIn(buClrNode) ?: baseStyle.color else baseStyle.color
                val buSzNode = lv.firstOf("buSzPct", "buSzPts", "buSzTx")
                val buSize = when (buSzNode?.name) {
                    "buSzPct" -> maxSize * (buSzNode.int("val") ?: 100000) / 100000f
                    "buSzPts" -> (buSzNode.int("val") ?: 1800) / 100f * 12700f * s
                    else -> maxSize
                }
                when (bu.name) {
                    "buChar" -> {
                        val raw = bu.attr("char") ?: "•"
                        val bf = PptFont(buFont ?: "", 0, 0)
                        bulletText = if (PptFonts.isSymbolFont(bf)) PptFonts.mapSymbols(raw, bf) else raw
                        bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = buSize * if (PptFonts.isSymbolFont(bf)) 1.25f else 1f
                            typeface = if (PptFonts.isSymbolFont(bf)) Typeface.DEFAULT else OfficeFonts.resolve(buFont, false, false)
                            color = buColor
                        }
                    }
                    "buAutoNum" -> {
                        bulletText = autoNumText(bu.attr("type") ?: "arabicPeriod", counters[level])
                        bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = buSize
                            typeface = OfficeFonts.resolve(buFont ?: baseStyle.font, baseStyle.bold, baseStyle.italic)
                            color = buColor
                        }
                    }
                    "buBlip" -> {
                        bulletImage = bu.child("blip")?.attr("r:embed")?.let { pres.image(ctx.sheet.path, it) }
                        bulletPaint = TextPaint().apply { textSize = buSize }
                    }
                }
                val bw = bulletPaint?.let { bp2 -> bulletText?.let { bp2.measureText(it) } ?: bp2.textSize } ?: 0f
                firstLine = if (marL > marL + indent + bw) marL else marL + indent + bw + maxSize * 0.15f
            }
            val text: CharSequence = if (hasText) sb else " "
            val sp = SpannableStringBuilder(text)
            val nl = sp.indexOf('\n')
            val firstPx = max(0, firstLine.toInt())
            val restPx = max(0, marL.toInt())
            if (nl < 0) {
                sp.setSpan(LeadingMarginSpan.Standard(firstPx, restPx), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            } else {
                sp.setSpan(LeadingMarginSpan.Standard(firstPx, restPx), 0, nl + 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                sp.setSpan(LeadingMarginSpan.Standard(restPx, restPx), nl + 1, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            val defTab = (lv.attr("defTabSz")?.toFloatOrNull() ?: 914400f) * s
            lv.child("tabLst")?.all("tab")?.forEach { t ->
                t.long("pos")?.let { pos -> sp.setSpan(TabStopSpan.Standard((pos * s).toInt()), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
            }
            if (defTab > 1f) {
                var x = defTab
                var k = 0
                while (x < width * 4 && k++ < 64) {
                    sp.setSpan(TabStopSpan.Standard(x.toInt()), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    x += defTab
                }
            }
            val lnSpc = lv.child("lnSpc")
            val lineH = when {
                lnSpc?.child("spcPts") != null -> (lnSpc.child("spcPts")?.int("val") ?: 1200) / 100f * 12700f * s
                else -> {
                    val pct = (lnSpc?.child("spcPct")?.int("val") ?: 100000) / 100000f
                    maxSize * 1.2f * max(0.1f, pct - lnReduction)
                }
            }
            sp.setSpan(ExactLineHeight(lineH), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            fun spacing(node: DmlNode?): Float = when {
                node?.child("spcPts") != null -> (node.child("spcPts")?.int("val") ?: 0) / 100f * 12700f * s
                node?.child("spcPct") != null -> (node.child("spcPct")?.int("val") ?: 0) / 100000f * maxSize * 1.2f
                else -> 0f
            }
            val alignment = when (algn) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val layoutWidth = if (wrap) width else 100_000f
            val builder = StaticLayout.Builder.obtain(sp, 0, sp.length, basePaint, max(1, layoutWidth.toInt()))
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
            if (algn == "just" || algn == "dist" || algn == "thaiDist" || algn == "justLow") builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            val layout = builder.build()
            val before = if (pi == 0) 0f else spacing(lv.child("spcBef"))
            val after = spacing(lv.child("spcAft"))
            laid.add(LaidPara(layout, before, after, bulletText, bulletPaint, marL + indent, bulletImage, algn, marL))
            total += before + layout.height + after
        }
        laid.lastOrNull()?.let { total -= it.after }
        if (measureOnly) {
            canvas.restore()
            return total + tIns + bIns
        }
        var y = when (anchor) {
            "ctr" -> box.top + (box.height() - total) / 2f
            "b" -> box.bottom - total
            else -> box.top
        }
        var blockShift = 0f
        if (anchorCtr) {
            var maxW = 0f
            for (lp in laid) for (l in 0 until lp.layout.lineCount) maxW = max(maxW, lp.layout.getLineRight(l))
            blockShift = max(0f, (width - maxW) / 2f)
        }
        for ((i, lp) in laid.withIndex()) {
            y += lp.before
            val x = if (!wrap) when (lp.align) {
                "ctr" -> box.centerX() - 50_000f
                "r" -> box.right - 100_000f
                else -> box.left
            } else box.left + blockShift
            canvas.save()
            canvas.translate(x, y)
            lp.layout.draw(canvas)
            if (lp.layout.lineCount > 0) {
                val base = lp.layout.getLineBaseline(0).toFloat()
                val bx = if (lp.align == "l" || lp.align == "just" || lp.align == "dist") lp.bulletX else lp.layout.getLineLeft(0) - (lp.bulletPaint?.let { b -> lp.bullet?.let { b.measureText(it) } } ?: 0f) * 1.3f
                if (lp.bullet != null && lp.bulletPaint != null) canvas.drawText(lp.bullet, bx, base, lp.bulletPaint)
                if (lp.bulletImage != null && lp.bulletPaint != null) {
                    val sz = lp.bulletPaint.textSize
                    lp.bulletImage.draw(canvas, RectF(bx, base - sz * 0.8f, bx + sz * 0.8f, base), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            canvas.restore()
            y += lp.layout.height + if (i < laid.size - 1) lp.after else 0f
        }
        canvas.restore()
        return total + tIns + bIns
    }

    companion object {
        val FILL_NAMES = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")
    }
}

object DmlPatterns {
    private val cache = HashMap<String, Bitmap>()

    fun bitmap(prst: String, fg: Int, bg: Int): Bitmap {
        val key = "$prst|$fg|$bg"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) bmp.setPixel(x, y, if (on(prst, x, y)) fg else bg)
        synchronized(cache) { if (cache.size > 256) cache.clear(); cache[key] = bmp }
        return bmp
    }

    private fun density(x: Int, y: Int, n: Int): Boolean {
        val bayer = intArrayOf(0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21)
        return bayer[(y % 8) * 8 + (x % 8)] < n
    }

    private fun on(p: String, x: Int, y: Int): Boolean = when (p) {
        "pct5" -> density(x, y, 3)
        "pct10" -> density(x, y, 6)
        "pct20" -> density(x, y, 13)
        "pct25" -> density(x, y, 16)
        "pct30" -> density(x, y, 19)
        "pct40" -> density(x, y, 26)
        "pct50" -> (x + y) % 2 == 0
        "pct60" -> density(x, y, 38)
        "pct70" -> density(x, y, 45)
        "pct75" -> density(x, y, 48)
        "pct80" -> density(x, y, 51)
        "pct90" -> density(x, y, 58)
        "horz", "ltHorz" -> y % 4 == 0
        "dkHorz" -> y % 4 < 2
        "narHorz" -> y % 2 == 0
        "dashHorz" -> y % 4 == 0 && x % 8 < 4
        "vert", "ltVert" -> x % 4 == 0
        "dkVert" -> x % 4 < 2
        "narVert" -> x % 2 == 0
        "dashVert" -> x % 4 == 0 && y % 8 < 4
        "cross", "smGrid" -> x % 4 == 0 || y % 4 == 0
        "lgGrid" -> x == 0 || y == 0
        "dnDiag", "ltDnDiag" -> (x - y + 8) % 4 == 0
        "upDiag", "ltUpDiag" -> (x + y) % 4 == 0
        "dkDnDiag" -> (x - y + 8) % 4 < 2
        "dkUpDiag" -> (x + y) % 4 < 2
        "wdDnDiag" -> (x - y + 8) % 8 < 3
        "wdUpDiag" -> (x + y) % 8 < 3
        "dashDnDiag" -> (x - y + 8) % 4 == 0 && y % 8 < 4
        "dashUpDiag" -> (x + y) % 4 == 0 && y % 8 < 4
        "diagCross" -> (x - y + 8) % 4 == 0 || (x + y) % 4 == 0
        "smCheck" -> ((x / 2) + (y / 2)) % 2 == 0
        "lgCheck" -> ((x / 4) + (y / 4)) % 2 == 0
        "dotGrid" -> x % 4 == 0 && y % 4 == 0
        "smConfetti", "lgConfetti" -> density(x * 3 % 8, y * 5 % 8, 16)
        "horzBrick" -> y % 4 == 0 || (x % 8 == (if (y / 4 % 2 == 0) 0 else 4))
        "diagBrick" -> (x + y) % 8 == 0 || ((x - y + 8) % 8 == 0 && x % 8 < 4)
        "solidDmnd" -> abs(x - 3.5f) + abs(y - 3.5f) < 3.5f
        "openDmnd" -> abs(abs(x - 3.5f) + abs(y - 3.5f) - 3.5f) < 0.6f
        "dotDmnd" -> (x % 4 == 0 && y % 4 == 0) || (x % 4 == 2 && y % 4 == 2)
        "plaid" -> y % 4 < 2 || x % 4 < 2 && y % 2 == 0
        "sphere" -> hypot(x - 3.5, y - 3.5) < 3.0
        "weave" -> ((x + y) % 4 == 0) || ((x - y + 8) % 8 == 2)
        "divot" -> (y == 1 && x in 2..3) || (y == 5 && x in 5..6)
        "shingle" -> y == 0 || (x == y % 8)
        "wave" -> y == ((sin(x * Math.PI / 4) * 1.5 + 3.5).toInt())
        "trellis" -> (x + y) % 2 == 0 && y % 2 == 0
        "zigZag" -> y % 4 == (if (x % 4 < 2) x % 4 else 3 - x % 4)
        else -> (x + y) % 2 == 0
    }
}
