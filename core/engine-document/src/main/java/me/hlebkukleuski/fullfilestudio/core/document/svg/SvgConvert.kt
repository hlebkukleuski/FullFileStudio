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
 * Portions ported and adapted from resvg, usvg, svgtypes, simplecss, roxmltree, unicode-vo,
 * ttf-parser, fontdb, tiny-skia, and kurbo (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors,
 * Copyright (c) 2011 Google Inc. (Skia / tiny-skia, BSD-3-Clause), and
 * Copyright (c) 2018 The Kurbo Authors / Raph Levien (Apache-2.0 OR MIT),
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT; Unicode UAX #50 under Unicode-3.0).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.hlebkukleuski.fullfilestudio.core.document.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlin.math.abs

internal class SvgOptions(
    val dpi: Float = 96f,
    val fontSize: Float = 12f,
    val languages: List<String> = listOf("en"),
    val defaultWidth: Float = 100f,
    val defaultHeight: Float = 100f,
    val resolveExternal: ((String) -> ByteArray?)? = null,
    val nestedDepth: Int = 0,
    val textRenderer: SvgTextConverter? = null
)

internal interface SvgTextConverter {
    fun convert(node: SvgNode, state: SvgConverter.State, conv: SvgConverter, parent: RGroup)
}

internal class SvgConverter(val doc: SvgDoc, val opt: SvgOptions) {
    class State(
        val parentClipPath: SvgNode?,
        val insideMarker: Boolean,
        val contextElement: Pair<RFill?, RStroke?>?,
        val contextPath: Pair<Ts, SRect?>?,
        val feImageLink: Boolean,
        val viewBox: SRect,
        val useSize: Pair<Float?, Float?>
    ) {
        fun copy(
            parentClipPath: SvgNode? = this.parentClipPath, insideMarker: Boolean = this.insideMarker,
            contextElement: Pair<RFill?, RStroke?>? = this.contextElement, contextPath: Pair<Ts, SRect?>? = this.contextPath,
            feImageLink: Boolean = this.feImageLink, viewBox: SRect = this.viewBox, useSize: Pair<Float?, Float?> = this.useSize
        ) = State(parentClipPath, insideMarker, contextElement, contextPath, feImageLink, viewBox, useSize)
    }

    private val clipCache = HashMap<String, RClip>()
    private val maskCache = HashMap<String, RMask>()
    private val filterCache = HashMap<String, RFilter>()
    private val paintCache = HashMap<String, RPaint>()
    private val idCounters = HashMap<String, Int>()

    fun genId(prefix: String): String {
        while (true) {
            val n = (idCounters[prefix] ?: 0) + 1
            idCounters[prefix] = n
            val id = "$prefix$n"
            if (doc.byId(id) == null) return id
        }
    }

    companion object {
        const val GRAPHIC = "circle ellipse image line path polygon polyline rect text use"
        val GRAPHIC_SET = GRAPHIC.split(' ').toSet()
        val FEATURES = setOf(
            "http://www.w3.org/TR/SVG11/feature#SVGDOM-static", "http://www.w3.org/TR/SVG11/feature#SVG-static",
            "http://www.w3.org/TR/SVG11/feature#CoreAttribute", "http://www.w3.org/TR/SVG11/feature#Structure",
            "http://www.w3.org/TR/SVG11/feature#BasicStructure", "http://www.w3.org/TR/SVG11/feature#ContainerAttribute",
            "http://www.w3.org/TR/SVG11/feature#ConditionalProcessing", "http://www.w3.org/TR/SVG11/feature#Image",
            "http://www.w3.org/TR/SVG11/feature#Style", "http://www.w3.org/TR/SVG11/feature#Shape", "http://www.w3.org/TR/SVG11/feature#Text",
            "http://www.w3.org/TR/SVG11/feature#BasicText", "http://www.w3.org/TR/SVG11/feature#PaintAttribute",
            "http://www.w3.org/TR/SVG11/feature#BasicPaintAttribute", "http://www.w3.org/TR/SVG11/feature#OpacityAttribute",
            "http://www.w3.org/TR/SVG11/feature#GraphicsAttribute", "http://www.w3.org/TR/SVG11/feature#BasicGraphicsAttribute",
            "http://www.w3.org/TR/SVG11/feature#Marker", "http://www.w3.org/TR/SVG11/feature#Gradient", "http://www.w3.org/TR/SVG11/feature#Pattern",
            "http://www.w3.org/TR/SVG11/feature#Clip", "http://www.w3.org/TR/SVG11/feature#BasicClip", "http://www.w3.org/TR/SVG11/feature#Mask",
            "http://www.w3.org/TR/SVG11/feature#Filter", "http://www.w3.org/TR/SVG11/feature#BasicFilter", "http://www.w3.org/TR/SVG11/feature#XlinkAttribute"
        )

        fun convert(doc: SvgDoc, opt: SvgOptions): SvgTree? = SvgConverter(doc, opt).convertDoc()

        fun blendMode(v: String?, mixBlend: Boolean): Int? = when (v) {
            "normal" -> 0; "multiply" -> 1; "screen" -> 2; "overlay" -> 3; "darken" -> 4; "lighten" -> 5; "color-dodge" -> 6; "color-burn" -> 7
            "hard-light" -> 8; "soft-light" -> 9; "difference" -> 10; "exclusion" -> 11; "hue" -> 12; "saturation" -> 13; "color" -> 14; "luminosity" -> 15
            "plus-lighter" -> if (mixBlend) 16 else null
            else -> null
        }

        fun viewBoxTransform(vb: SRect, aspect: SvgAspect, w: Float, h: Float): Ts {
            var sx = w / vb.w
            var sy = h / vb.h
            if (aspect.align != SvgAspect.NONE) {
                val s = if (aspect.slice) (if (sx < sy) sy else sx) else (if (sx > sy) sy else sx)
                sx = s; sy = s
            }
            val x = -vb.x * sx
            val y = -vb.y * sy
            val ww = w - vb.w * sx
            val hh = h - vb.h * sy
            val p = alignedPos(aspect.align, x, y, ww, hh)
            return Ts(sx, 0f, 0f, sy, p[0], p[1])
        }

        fun alignedPos(align: Int, x: Float, y: Float, w: Float, h: Float): FloatArray = when (align) {
            SvgAspect.X_MID_Y_MIN -> floatArrayOf(x + w / 2f, y)
            SvgAspect.X_MAX_Y_MIN -> floatArrayOf(x + w, y)
            SvgAspect.X_MIN_Y_MID -> floatArrayOf(x, y + h / 2f)
            SvgAspect.X_MID_Y_MID -> floatArrayOf(x + w / 2f, y + h / 2f)
            SvgAspect.X_MAX_Y_MID -> floatArrayOf(x + w, y + h / 2f)
            SvgAspect.X_MIN_Y_MAX -> floatArrayOf(x, y + h)
            SvgAspect.X_MID_Y_MAX -> floatArrayOf(x + w / 2f, y + h)
            SvgAspect.X_MAX_Y_MAX -> floatArrayOf(x + w, y + h)
            else -> floatArrayOf(x, y)
        }

        fun parseOpacity(v: String?): Float? {
            val l = v?.let { SvgSyntax.length(it) } ?: return null
            return when (l.unit) {
                SvgLength.PERCENT -> (l.number / 100.0).toFloat().coerceIn(0f, 1f)
                SvgLength.NONE -> l.number.toFloat().coerceIn(0f, 1f)
                else -> null
            }
        }
    }

    fun attrLength(n: SvgNode, aid: String): SvgLength? = n.attr(aid)?.let { SvgSyntax.length(it) }
    fun attrNumber(n: SvgNode, aid: String): Float? = n.attr(aid)?.let { SvgSyntax.number(it) }?.toFloat()
    fun attrNumberList(n: SvgNode, aid: String): List<Double>? = n.attr(aid)?.let { SvgSyntax.numberList(it) }
    fun attrColor(n: SvgNode, aid: String): SvgColor? {
        val v = n.attr(aid) ?: return null
        if (v != "currentColor") return SvgSyntax.color(v)
        val c = if (aid == "color") n.parentElement()?.findAttr("color")?.let { colorValue(n.parentElement()!!, it, "color") } else n.findAttrNode("color")?.let { m -> m.attr("color")?.let { colorValue(m, it, "color") } }
        return c ?: SvgColor.BLACK
    }
    private fun colorValue(owner: SvgNode, v: String, aid: String): SvgColor? = if (v == "currentColor") attrColor(owner, aid) else SvgSyntax.color(v)
    fun findColor(n: SvgNode, aid: String): SvgColor? { val o = n.findAttrNode(aid) ?: return null; if (doc.circular.contains(n to aid)) return null; return attrColor(o, aid) }

    fun units(n: SvgNode, aid: String): Int? = when (n.attr(aid)) { "userSpaceOnUse" -> RPaint.UNITS_USER; "objectBoundingBox" -> RPaint.UNITS_BBOX; else -> null }

    fun convertLength(len: SvgLength, node: SvgNode, aid: String, units: Int, state: State): Float {
        val n = len.number.toFloat()
        val dpi = opt.dpi
        return when (len.unit) {
            SvgLength.NONE, SvgLength.PX -> n
            SvgLength.EM -> n * resolveFontSize(node, state)
            SvgLength.EX -> n * resolveFontSize(node, state) / 2f
            SvgLength.IN -> n * dpi
            SvgLength.CM -> n * dpi / 2.54f
            SvgLength.MM -> n * dpi / 25.4f
            SvgLength.PT -> n * dpi / 72f
            SvgLength.PC -> n * dpi / 6f
            else -> if (units == RPaint.UNITS_BBOX) n / 100f else {
                val vb = state.viewBox
                when (aid) {
                    "cx", "dx", "fx", "markerWidth", "refX", "rx", "width", "x", "x1", "x2" -> vb.w * n / 100f
                    "cy", "dy", "fy", "height", "markerHeight", "refY", "ry", "y", "y1", "y2" -> vb.h * n / 100f
                    else -> { var l = vb.w * vb.w + vb.h * vb.h; l = Math.sqrt((l / 2f).toDouble()).toFloat(); l * n / 100f }
                }
            }
        }
    }

    fun resolveFontSize(node: SvgNode, state: State): Float {
        val nodes = node.ancestors().toList()
        var fs = opt.fontSize
        for (n in nodes.reversed().drop(1)) {
            val raw = n.tryAttr("font-size") ?: continue
            val l = SvgSyntax.length(raw)
            if (l != null) {
                val v = l.number.toFloat()
                fs = when (l.unit) {
                    SvgLength.NONE, SvgLength.PX -> v
                    SvgLength.EM -> v * fs
                    SvgLength.EX -> v * fs / 2f
                    SvgLength.IN -> v * opt.dpi
                    SvgLength.CM -> v * opt.dpi / 2.54f
                    SvgLength.MM -> v * opt.dpi / 25.4f
                    SvgLength.PT -> v * opt.dpi / 72f
                    SvgLength.PC -> v * opt.dpi / 6f
                    else -> l.number.toFloat() * fs * 0.01f
                }
            } else {
                val f = when (n.attr("font-size")) { "xx-small" -> -3; "x-small" -> -2; "small" -> -1; "medium" -> 0; "large" -> 1; "x-large" -> 2; "xx-large" -> 3; "smaller" -> -1; "larger" -> 1; else -> 0 }
                fs *= Math.pow(1.2, f.toDouble()).toFloat()
            }
        }
        return fs
    }

    fun nodeLength(n: SvgNode, aid: String, units: Int, state: State, def: SvgLength) = convertLength(attrLength(n, aid) ?: def, n, aid, units, state)
    fun userLength(n: SvgNode, aid: String, state: State, def: SvgLength) = nodeLength(n, aid, RPaint.UNITS_USER, state, def)
    fun tryLength(n: SvgNode, aid: String, units: Int, state: State): Float? = attrLength(n, aid)?.let { convertLength(it, n, aid, units, state) }

    fun resolveLength(n: SvgNode, aid: String, state: State, def: Float): Float {
        val a = n.ancestors().firstOrNull { it.hasAttr(aid) } ?: return def
        val l = attrLength(a, aid) ?: return def
        return convertLength(l, a, aid, RPaint.UNITS_USER, state)
    }

    fun parseViewBox(n: SvgNode): SRect? { val v = n.attr("viewBox")?.let { SvgSyntax.viewBox(it) } ?: return null; return SRect.nz(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat()) }
    fun aspect(n: SvgNode): SvgAspect = n.attr("preserveAspectRatio")?.let { SvgSyntax.aspect(it) } ?: SvgAspect.DEFAULT

    fun attrTransform(n: SvgNode, aid: String): Ts? {
        val v = n.attr(aid) ?: return null
        val d = SvgSyntax.transform(v) ?: return null
        val ts = Ts.of(d)
        return if (ts.isValid) ts else Ts.IDENTITY
    }

    fun hasValidTransform(n: SvgNode, aid: String): Boolean {
        val v = n.attr(aid) ?: return true
        val d = SvgSyntax.transform(v) ?: return true
        return Ts.of(d).isValid
    }

    fun resolveTransform(n: SvgNode, aid: String, state: State): Ts {
        var ts = attrTransform(n, aid) ?: Ts.IDENTITY
        var origin = n.attr("transform-origin")?.let { SvgSyntax.transformOrigin(it) }
        if (origin == null && n.tag == "svg" && n.parentElement() == null) origin = arrayOf(SvgLength.pct(50.0), SvgLength.pct(50.0))
        if (origin != null) {
            val dx = convertLength(origin[0], n, "width", RPaint.UNITS_USER, state)
            val dy = convertLength(origin[1], n, "height", RPaint.UNITS_USER, state)
            ts = Ts.translate(dx, dy).pre(ts).preTranslate(-dx, -dy)
        }
        return ts
    }

    fun isConditionPassed(n: SvgNode): Boolean {
        if (!n.isElement) return false
        if (n.hasAttr("requiredExtensions")) return false
        n.attr("requiredFeatures")?.let { f -> for (x in f.split(' ')) if (x !in FEATURES) return false }
        n.attr("systemLanguage")?.let { langs ->
            var has = false
            for (l0 in langs.split(',')) {
                val l = l0.trim()
                if (opt.languages.contains(l)) { has = true; break }
                val idx = l.indexOf('-')
                if (idx >= 0 && opt.languages.contains(l.substring(0, idx))) { has = true; break }
            }
            if (!has) return false
        }
        return true
    }

    fun isVisibleElement(n: SvgNode) = n.attr("display") != "none" && hasValidTransform(n, "transform") && isConditionPassed(n)

    private fun convertDoc(): SvgTree? {
        val svg = doc.rootElement() ?: return null
        val sizeState = State(null, false, null, null, false, SRect(0f, 0f, 100f, 100f), null to null)
        val def = SvgLength.pct(100.0)
        var width = attrLength(svg, "width") ?: def
        var height = attrLength(svg, "height") ?: def
        val vb = parseViewBox(svg)
        var restoreViewBox = false
        if ((width.unit == SvgLength.PERCENT || height.unit == SvgLength.PERCENT) && vb == null) {
            if (width.unit == SvgLength.PERCENT) width = SvgLength.num(width.number / 100.0 * opt.defaultWidth)
            if (height.unit == SvgLength.PERCENT) height = SvgLength.num(height.number / 100.0 * opt.defaultHeight)
            restoreViewBox = true
        }
        val size: Pair<Float, Float> = if (vb != null) {
            val st = sizeState.copy(viewBox = vb)
            val w = if (width.unit == SvgLength.PERCENT) vb.w * (width.number.toFloat() / 100f) else userLength(svg, "width", st, def)
            val h = if (height.unit == SvgLength.PERCENT) vb.h * (height.number.toFloat() / 100f) else userLength(svg, "height", st, def)
            val hasW = attrLength(svg, "width") != null
            val hasH = attrLength(svg, "height") != null
            when {
                hasW && !hasH -> w to vb.h * w / vb.w
                !hasW && hasH -> vb.w * h / vb.h to h
                else -> w to h
            }
        } else {
            convertLength(width, svg, "width", RPaint.UNITS_USER, sizeState) to convertLength(height, svg, "height", RPaint.UNITS_USER, sizeState)
        }
        if (!(size.first > 0f && size.second > 0f && size.first.isFinite() && size.second.isFinite())) return null
        val viewBox = vb ?: SRect(0f, 0f, size.first, size.second)
        val aspect = aspect(svg)
        val bg = svg.attr("background-color")?.let { SvgSyntax.paint(it) }?.let { (it as? SvgPaintValue.Color)?.color }
        val root = RGroup()
        var tree = SvgTree(size.first, size.second, root)
        if (!isVisibleElement(svg)) return tree
        val state = State(null, false, null, null, false, viewBox, null to null)
        val rootState = state.copy(viewBox = SRect(0f, 0f, size.first, size.second))
        val svgTs = resolveTransform(svg, "transform", rootState)
        val rootTs = svgTs.pre(viewBoxTransform(viewBox, aspect, size.first, size.second))
        if (rootTs.isIdentity && bg == null) {
            convertChildren(doc.root, state, root)
        } else {
            val g = RGroup()
            if (bg != null) {
                val p = RPathNode("", true, RFill(RPaint.Solid(bg.r, bg.g, bg.b), bg.a / 255f, false), null, false, true, SPath.rect(viewBox), Ts.IDENTITY)
                g.children.add(p)
            }
            g.transform = rootTs
            g.absTransform = rootTs
            convertChildren(doc.root, state, g)
            root.children.add(g)
        }
        clipCache.clear(); maskCache.clear(); filterCache.clear(); paintCache.clear()
        updatePaintServers(root, Ts.IDENTITY, null, null)
        if (restoreViewBox) {
            root.invalidate()
            val bb = root.absBoundingBox()
            if (bb != null && bb.right > 0f && bb.bottom > 0f) tree = SvgTree(bb.right, bb.bottom, root)
        }
        return tree
    }

    fun convertChildren(parentNode: SvgNode, state: State, parent: RGroup) {
        for (n in parentNode.children) convertElement(n, state, parent)
    }

    fun convertElement(node: SvgNode, state: State, parent: RGroup) {
        val tag = node.tag ?: return
        if (tag !in GRAPHIC_SET && tag != "g" && tag != "switch" && tag != "svg") return
        if (!isVisibleElement(node)) return
        if (tag == "use") { convertUse(node, state, parent); return }
        if (tag == "switch") { convertSwitch(node, state, parent); return }
        if (tag == "svg" && node.parentElement() != null) { convertNestedSvg(node, state, parent); return }
        convertGroup(node, state, false, parent) { g -> convertElementImpl(tag, node, state, g) }?.let { parent.children.add(it) }
    }

    private fun convertElementImpl(tag: String, node: SvgNode, state: State, parent: RGroup) {
        when (tag) {
            "rect", "circle", "ellipse", "line", "polyline", "polygon", "path" -> convertShape(node, state)?.let { convertPath(node, it, state, parent) }
            "image" -> convertImage(node, state, parent)
            "text" -> opt.textRenderer?.convert(node, state, this, parent)
            "svg", "g" -> convertChildren(node, state, parent)
        }
    }

    fun convertClipPathElements(clipNode: SvgNode, state: State, parent: RGroup) {
        for (node in clipNode.children) {
            val tag = node.tag ?: continue
            if (tag !in GRAPHIC_SET) continue
            if (!isVisibleElement(node)) continue
            if (tag == "use") { convertUse(node, state, parent); continue }
            convertGroup(node, state, false, parent) { g ->
                when (tag) {
                    "rect", "circle", "ellipse", "polyline", "polygon", "path" -> convertShape(node, state)?.let { convertPath(node, it, state, g) }
                    "text" -> opt.textRenderer?.convert(node, state, this, g)
                }
            }?.let { parent.children.add(it) }
        }
    }

    fun convertGroup(node: SvgNode, state: State, force: Boolean, parent: RGroup, collect: (RGroup) -> Unit): RGroup? {
        val opacity = if (state.parentClipPath == null) parseOpacity(node.attr("opacity")) ?: 1f else 1f
        val isRootSvg = node.tag == "svg" && node.parentElement() == null
        val transform = if (isRootSvg) Ts.IDENTITY else resolveTransform(node, "transform", state)
        val blend = blendMode(node.attr("mix-blend-mode"), true) ?: 0
        val isolate = node.attr("isolation") == "isolate"
        val isGOrUse = node.tag == "g" || node.tag == "use"
        val g = RGroup()
        g.id = if (isGOrUse && !state.insideMarker) node.elementId else ""
        g.transform = transform
        g.absTransform = parent.absTransform.pre(transform)
        g.opacity = opacity
        g.blendMode = blend
        g.isolate = isolate
        collect(g)
        val objectBbox: () -> SRect? = { g.boundingBox()?.takeIf { it.nonZero } }
        var clip: RClip? = null
        val clipLink = node.attr("clip-path")?.let { v -> SvgSyntax.funcIri(v)?.let { doc.byId(it) } }
        if (node.attr("clip-path") != null && clipLink != null) {
            clip = convertClip(clipLink, state, objectBbox) ?: return null
        }
        var mask: RMask? = null
        if (state.parentClipPath == null) {
            val maskLink = node.attr("mask")?.let { v -> SvgSyntax.funcIri(v)?.let { doc.byId(it) } }
            if (maskLink != null) mask = convertMask(maskLink, state, objectBbox) ?: return null
        }
        var filters: List<RFilter> = emptyList()
        if (state.parentClipPath == null) {
            val fv = node.attr("filter")
            if (fv == null && node.rawAttr("filter") == "none") {
            } else if (node.hasAttr("filter") && fv != null) {
                filters = convertFilters(node, state, objectBbox()) ?: return null
            }
        }
        val required = abs(opacity - 1f) > 4 * Math.ulp(1f) || clip != null || mask != null || filters.isNotEmpty() || !transform.isIdentity || blend != 0 || isolate || isGOrUse || force
        if (!required) { parent.children.addAll(g.children); return null }
        g.clipPath = clip
        g.mask = mask
        g.filters = filters
        g.invalidate()
        return g
    }

    fun convertShape(node: SvgNode, state: State): SPath? = when (node.tag) {
        "rect" -> {
            val w = userLength(node, "width", state, SvgLength.ZERO)
            val h = userLength(node, "height", state, SvgLength.ZERO)
            if (!(w > 0f && w.isFinite()) || !(h > 0f && h.isFinite())) null else {
                val x = userLength(node, "x", state, SvgLength.ZERO)
                val y = userLength(node, "y", state, SvgLength.ZERO)
                var (rx, ry) = resolveRxRy(node, state)
                if (rx > w / 2f) rx = w / 2f
                if (ry > h / 2f) ry = h / 2f
                if (abs(rx) <= 4 * Float.MIN_VALUE || rx == 0f) SRect.ltrb(x, y, x + w, y + h)?.let { SPath.rect(it) } else {
                    val b = SPathBuilder()
                    b.moveTo(x + rx, y)
                    b.lineTo(x + w - rx, y)
                    b.arcTo(rx, ry, 0f, false, true, x + w, y + ry)
                    b.lineTo(x + w, y + h - ry)
                    b.arcTo(rx, ry, 0f, false, true, x + w - rx, y + h)
                    b.lineTo(x + rx, y + h)
                    b.arcTo(rx, ry, 0f, false, true, x, y + h - ry)
                    b.lineTo(x, y + ry)
                    b.arcTo(rx, ry, 0f, false, true, x + rx, y)
                    b.close()
                    b.finish()
                }
            }
        }
        "circle" -> {
            val cx = userLength(node, "cx", state, SvgLength.ZERO)
            val cy = userLength(node, "cy", state, SvgLength.ZERO)
            val r = userLength(node, "r", state, SvgLength.ZERO)
            if (!(r > 0f && r.isFinite())) null else ellipse(cx, cy, r, r)
        }
        "ellipse" -> {
            val cx = userLength(node, "cx", state, SvgLength.ZERO)
            val cy = userLength(node, "cy", state, SvgLength.ZERO)
            val (rx, ry) = resolveRxRy(node, state)
            if (!(rx > 0f && rx.isFinite()) || !(ry > 0f && ry.isFinite())) null else ellipse(cx, cy, rx, ry)
        }
        "line" -> {
            val b = SPathBuilder()
            b.moveTo(userLength(node, "x1", state, SvgLength.ZERO), userLength(node, "y1", state, SvgLength.ZERO))
            b.lineTo(userLength(node, "x2", state, SvgLength.ZERO), userLength(node, "y2", state, SvgLength.ZERO))
            b.finish()
        }
        "polyline", "polygon" -> {
            val text = node.attr("points")
            if (text == null) null else {
                val pts = SvgSyntax.points(text)
                val b = SPathBuilder()
                var i = 0
                while (i < pts.size) { if (b.isEmpty()) b.moveTo(pts[i].toFloat(), pts[i + 1].toFloat()) else b.lineTo(pts[i].toFloat(), pts[i + 1].toFloat()); i += 2 }
                if (b.size < 2) null else { if (node.tag == "polygon") b.close(); b.finish() }
            }
        }
        "path" -> node.attr("d")?.let { SPathBuilder.fromSegments(SvgSyntax.pathData(it)) }
        else -> null
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): SPath? {
        val b = SPathBuilder()
        b.moveTo(cx + rx, cy)
        b.arcTo(rx, ry, 0f, false, true, cx, cy + ry)
        b.arcTo(rx, ry, 0f, false, true, cx - rx, cy)
        b.arcTo(rx, ry, 0f, false, true, cx, cy - ry)
        b.arcTo(rx, ry, 0f, false, true, cx + rx, cy)
        b.close()
        return b.finish()
    }

    private fun resolveRxRy(node: SvgNode, state: State): Pair<Float, Float> {
        var rx = attrLength(node, "rx")
        var ry = attrLength(node, "ry")
        if (rx != null && (rx.number < 0 || (rx.number == 0.0 && 1.0 / rx.number < 0))) rx = null
        if (ry != null && (ry.number < 0 || (ry.number == 0.0 && 1.0 / ry.number < 0))) ry = null
        return when {
            rx == null && ry == null -> 0f to 0f
            rx != null && ry == null -> convertLength(rx, node, "rx", 0, state).let { it to it }
            rx == null -> convertLength(ry!!, node, "ry", 0, state).let { it to it }
            else -> convertLength(rx, node, "rx", 0, state) to convertLength(ry!!, node, "ry", 0, state)
        }
    }

    fun resolveFill(node: SvgNode, hasBbox: Boolean, state: State): RFill? {
        if (state.parentClipPath != null) return RFill(RPaint.Solid(0, 0, 0), 1f, node.findAttr("clip-rule") == "evenodd")
        var sub = 1f
        val paintCtx: Pair<RPaint, Int>
        if (node.ancestors().any { it.hasAttr("fill") }) {
            val v = node.findAttr("fill") ?: return null
            val r = convertPaint(node, v, "fill", hasBbox, state) ?: return null
            paintCtx = r.first to r.third
            sub = r.second
        } else paintCtx = RPaint.Solid(0, 0, 0) to 0
        val fo = node.findAttr("fill-opacity")?.let { parseOpacity(it) } ?: 1f
        return RFill(paintCtx.first, sub * fo, node.findAttr("fill-rule") == "evenodd", paintCtx.second)
    }

    fun resolveStroke(node: SvgNode, hasBbox: Boolean, state: State): RStroke? {
        if (state.parentClipPath != null) return null
        val v = node.findAttr("stroke") ?: return null
        val r = convertPaint(node, v, "stroke", hasBbox, state) ?: return null
        val width = resolveLength(node, "stroke-width", state, 1f)
        if (!(width > 0f && width.isFinite())) return null
        var miter = node.findAttr("stroke-miterlimit")?.let { SvgSyntax.number(it)?.toFloat() } ?: 4f
        if (miter < 1f) miter = 1f
        val so = node.findAttr("stroke-opacity")?.let { parseOpacity(it) } ?: 1f
        val cap = when (node.findAttr("stroke-linecap")) { "round" -> 1; "square" -> 2; else -> 0 }
        val join = when (node.findAttr("stroke-linejoin")) { "miter-clip" -> 1; "round" -> 2; "bevel" -> 3; else -> 0 }
        return RStroke(r.first, dashArray(node, state), resolveLength(node, "stroke-dashoffset", state, 0f), miter, r.second * so, width, cap, join, r.third)
    }

    private fun dashArray(node: SvgNode, state: State): FloatArray? {
        val n = node.ancestors().firstOrNull { it.hasAttr("stroke-dasharray") } ?: return null
        val text = n.attr("stroke-dasharray") ?: return null
        val list = SvgSyntax.lengthListPrefix(text).map { convertLength(it, n, "stroke-dasharray", 0, state) }
        if (list.any { it < 0f || (it == 0f && 1f / it < 0) }) return null
        if (abs(list.sum()) <= 4 * Float.MIN_VALUE) return null
        return if (list.size % 2 != 0) (list + list).toFloatArray() else list.toFloatArray()
    }

    private fun convertPaint(node: SvgNode, value: String, aid: String, hasBbox: Boolean, state: State): Triple<RPaint, Float, Int>? {
        val paint = SvgSyntax.paint(value) ?: if (aid == "fill") SvgPaintValue.Color(SvgColor.BLACK) else return null
        return when (paint) {
            SvgPaintValue.None, SvgPaintValue.Inherit -> null
            SvgPaintValue.ContextFill -> state.contextElement?.first?.let { Triple(it.paint, 1f, it.context) }
            SvgPaintValue.ContextStroke -> state.contextElement?.second?.let { Triple(it.paint, 1f, it.context) }
            SvgPaintValue.CurrentColor -> { val c = findColor(node, "color") ?: SvgColor.BLACK; Triple(RPaint.Solid(c.r, c.g, c.b), c.a / 255f, 0) }
            is SvgPaintValue.Color -> Triple(RPaint.Solid(paint.color.r, paint.color.g, paint.color.b), paint.color.a / 255f, 0)
            is SvgPaintValue.FuncIri -> {
                val link = doc.byId(paint.id)
                if (link != null) {
                    val tag = link.tag
                    if (tag == "linearGradient" || tag == "radialGradient" || tag == "pattern") {
                        when (val ps = convertPaintServer(link, state)) {
                            is RPaint -> if (!hasBbox && ps.units == RPaint.UNITS_BBOX) fromFallback(node, paint.fallback)?.let { Triple(it.first, it.second, 0) } else Triple(ps, 1f, 0)
                            is SvgColor -> Triple(RPaint.Solid(ps.r, ps.g, ps.b), ps.a / 255f, 0)
                            else -> fromFallback(node, paint.fallback)?.let { Triple(it.first, it.second, 0) }
                        }
                    } else null
                } else fromFallback(node, paint.fallback)?.let { Triple(it.first, it.second, 0) }
            }
        }
    }

    private fun fromFallback(node: SvgNode, fb: SvgPaintValue?): Pair<RPaint, Float>? = when (fb) {
        null, SvgPaintValue.None -> null
        SvgPaintValue.CurrentColor -> { val c = findColor(node, "color") ?: SvgColor.BLACK; RPaint.Solid(c.r, c.g, c.b) to c.a / 255f }
        is SvgPaintValue.Color -> RPaint.Solid(fb.color.r, fb.color.g, fb.color.b) to fb.color.a / 255f
        else -> null
    }

    private fun resolveAttrNode(node: SvgNode, name: String, valid: (SvgNode) -> Boolean): SvgNode {
        if (valid(node)) return node
        val tag = node.tag
        for (link in node.hrefIter()) {
            val lt = link.tag ?: return node
            val ok = when (tag) {
                "linearGradient" -> (lt == "linearGradient" && name in setOf("x1", "y1", "x2", "y2")) || ((lt == "linearGradient" || lt == "radialGradient") && name in setOf("gradientUnits", "spreadMethod", "gradientTransform"))
                "radialGradient" -> (lt == "radialGradient" && name in setOf("cx", "cy", "r", "fx", "fy", "fr")) || ((lt == "linearGradient" || lt == "radialGradient") && name in setOf("gradientUnits", "spreadMethod", "gradientTransform"))
                "pattern" -> lt == "pattern"
                "filter" -> lt == "filter"
                else -> return node
            }
            if (!ok) break
            if (valid(link)) return link
        }
        return node
    }

    private fun lengthValid(n: SvgNode, a: String) = n.tryAttr(a)?.let { SvgSyntax.length(it) } != null
    fun resolveNumber(node: SvgNode, aid: String, units: Int, state: State, def: SvgLength): Float {
        val n = resolveAttrNode(node, aid) { lengthValid(it, aid) }
        return nodeLength(n, aid, units, state, def)
    }
    fun resolveUnits(node: SvgNode, aid: String, def: Int): Int {
        val n = resolveAttrNode(node, aid) { it.tryAttr(aid)?.let { v -> v == "userSpaceOnUse" || v == "objectBoundingBox" } == true }
        return units(n, aid) ?: def
    }

    private fun convertPaintServer(node: SvgNode, state: State): Any? {
        paintCache[node.elementId]?.let { return it }
        val r: Any? = when (node.tag) {
            "linearGradient" -> convertLinear(node, state)
            "radialGradient" -> convertRadial(node, state)
            "pattern" -> convertPattern(node, state)
            else -> null
        }
        if (r is RPaint) paintCache[node.elementId] = r
        return r
    }

    private fun spread(node: SvgNode): Int {
        val n = resolveAttrNode(node, "spreadMethod") { it.tryAttr("spreadMethod") in setOf("pad", "reflect", "repeat") }
        return when (n.attr("spreadMethod")) { "reflect" -> RPaint.SPREAD_REFLECT; "repeat" -> RPaint.SPREAD_REPEAT; else -> RPaint.SPREAD_PAD }
    }

    private fun gradientTransform(node: SvgNode, aid: String, state: State): Ts {
        val n = resolveAttrNode(node, aid) { it.tryAttr(aid)?.let { v -> SvgSyntax.transform(v) } != null }
        return resolveTransform(n, aid, state)
    }

    private fun linearRgb(node: SvgNode) = node.findAttr("color-interpolation") == "linearRGB"

    private fun findGradientWithStops(node: SvgNode): SvgNode? {
        for (link in node.hrefIter()) {
            if (link.tag != "linearGradient" && link.tag != "radialGradient") return null
            if (link.children.any { it.tag == "stop" }) return link
        }
        return null
    }

    private fun convertStops(grad: SvgNode): MutableList<SvgStop> {
        val stops = ArrayList<SvgStop>()
        var prev = SvgLength.ZERO
        for (s in grad.children) {
            if (s.tag != "stop") continue
            val off = attrLength(s, "offset") ?: prev
            val o = when (off.unit) { SvgLength.NONE -> off.number; SvgLength.PERCENT -> off.number / 100.0; else -> prev.number }
            prev = SvgLength.num(o)
            val offset = o.toFloat().coerceIn(0f, 1f)
            val c = attrColor(s, "stop-color") ?: SvgColor.BLACK
            val so = parseOpacity(s.attr("stop-opacity")) ?: 1f
            stops.add(SvgStop(offset, c.r, c.g, c.b, (c.a / 255f) * so))
        }
        fun eq(a: Float, b: Float) = abs(a - b) <= 4 * Math.ulp(maxOf(abs(a), abs(b))) || a == b
        if (stops.size >= 3) {
            var i = 0
            while (i < stops.size - 2) {
                if (eq(stops[i].offset, stops[i + 1].offset) && eq(stops[i + 1].offset, stops[i + 2].offset)) stops.removeAt(i + 1) else i++
            }
        }
        if (stops.size >= 2) {
            for (i in 0 until stops.size - 1) {
                val o1 = stops[i].offset; val o2 = stops[i + 1].offset
                if (eq(o1, 0f) && eq(o2, 0f)) stops[i + 1] = stops[i + 1].withOffset((o1 + 1.1920929E-7f).coerceIn(0f, 1f))
            }
        }
        for (i in 1 until stops.size) {
            val o1 = stops[i - 1].offset; val o2 = stops[i].offset
            if (o1 > o2 || eq(o1, o2)) {
                stops[i - 1] = stops[i - 1].withOffset((o1 - 1.1920929E-7f).coerceIn(0f, 1f))
                stops[i] = stops[i].withOffset(o1.coerceIn(0f, 1f))
            }
        }
        return stops
    }

    private fun SvgStop.withOffset(o: Float) = SvgStop(o, r, g, b, opacity)

    private fun convertLinear(node: SvgNode, state: State): Any? {
        val id = node.elementId.ifEmpty { return null }
        val stops = convertStops(findGradientWithStops(node) ?: return null)
        if (stops.size < 2) return stops.firstOrNull()?.let { SvgColor(it.r, it.g, it.b, Math.round(it.opacity * 255f)) }
        val units = resolveUnits(node, "gradientUnits", RPaint.UNITS_BBOX)
        return RPaint.Linear(
            id, resolveNumber(node, "x1", units, state, SvgLength.ZERO), resolveNumber(node, "y1", units, state, SvgLength.ZERO),
            resolveNumber(node, "x2", units, state, SvgLength.pct(100.0)), resolveNumber(node, "y2", units, state, SvgLength.ZERO),
            units, gradientTransform(node, "gradientTransform", state), spread(node), linearRgb(node), stops
        )
    }

    private fun convertRadial(node: SvgNode, state: State): Any? {
        val id = node.elementId.ifEmpty { return null }
        val stops = convertStops(findGradientWithStops(node) ?: return null)
        if (stops.size < 2) return stops.firstOrNull()?.let { SvgColor(it.r, it.g, it.b, Math.round(it.opacity * 255f)) }
        val units = resolveUnits(node, "gradientUnits", RPaint.UNITS_BBOX)
        val r = resolveNumber(node, "r", units, state, SvgLength.pct(50.0))
        val fr = resolveNumber(node, "fr", units, state, SvgLength.ZERO)
        if (!(r > 0f && r.isFinite())) { val s = stops.last(); return SvgColor(s.r, s.g, s.b, Math.round(s.opacity * 255f)) }
        val cx = resolveNumber(node, "cx", units, state, SvgLength.pct(50.0))
        val cy = resolveNumber(node, "cy", units, state, SvgLength.pct(50.0))
        val fx = resolveNumber(node, "fx", units, state, SvgLength.num(cx.toDouble()))
        val fy = resolveNumber(node, "fy", units, state, SvgLength.num(cy.toDouble()))
        return RPaint.Radial(id, cx, cy, r, fx, fy, if (fr >= 0f) fr else 0f, units, gradientTransform(node, "gradientTransform", state), spread(node), linearRgb(node), stops)
    }

    private fun convertPattern(node: SvgNode, state: State): Any? {
        var withChildren: SvgNode? = null
        for (link in node.hrefIter()) {
            if (link.tag != "pattern") return null
            if (link.children.isNotEmpty()) { withChildren = link; break }
        }
        withChildren ?: return null
        val id = node.elementId.ifEmpty { return null }
        val vbNode = resolveAttrNode(node, "viewBox") { it.tryAttr("viewBox")?.let { v -> SvgSyntax.viewBox(v) } != null }
        val arNode = resolveAttrNode(node, "preserveAspectRatio") { it.tryAttr("preserveAspectRatio")?.let { v -> SvgSyntax.aspect(v) } != null }
        val viewBox = parseViewBox(vbNode)?.let { it to aspect(arNode) }
        val units = resolveUnits(node, "patternUnits", RPaint.UNITS_BBOX)
        val contentUnits = resolveUnits(node, "patternContentUnits", RPaint.UNITS_USER)
        val transform = gradientTransform(node, "patternTransform", state)
        val rect = SRect.nz(
            resolveNumber(node, "x", units, state, SvgLength.ZERO), resolveNumber(node, "y", units, state, SvgLength.ZERO),
            resolveNumber(node, "width", units, state, SvgLength.ZERO), resolveNumber(node, "height", units, state, SvgLength.ZERO)
        ) ?: return null
        val root = RGroup()
        if (viewBox != null && units == RPaint.UNITS_USER && contentUnits == RPaint.UNITS_USER) {
            val g = RGroup()
            g.transform = viewBoxTransform(viewBox.first, viewBox.second, rect.w, rect.h)
            g.absTransform = g.transform
            convertChildren(withChildren, state, g)
            if (!g.hasChildren()) return null
            root.children.add(g)
        } else {
            convertChildren(withChildren, state, root)
            if (!root.hasChildren()) return null
        }
        updatePaintServers(root, Ts.IDENTITY, null, null)
        return RPaint.Pattern(id, units, contentUnits, transform, rect, viewBox, root)
    }

    fun updatePaintServers(group: RGroup, ctxTs: Ts, ctxBbox: SRect?, textBbox: SRect?) {
        var cts = ctxTs
        var cbb = ctxBbox
        if (group.isContextElement) {
            cbb = if (usesContextBbox(group)) group.boundingBox() else null
            cts = group.absTransform
        }
        val tb = group.textBbox ?: textBbox
        for (i in group.children.indices) {
            when (val c = group.children[i]) {
                is RGroup -> updatePaintServers(c, cts, cbb, tb)
                is RPathNode -> {
                    val fillUses = c.fill?.let { it.context != RStroke.CONTEXT_USE && usesObb(it.paint) } == true
                    val strokeUses = c.stroke?.let { it.context != RStroke.CONTEXT_USE && usesObb(it.paint) } == true
                    val bbox = if (fillUses || strokeUses) tb ?: c.boundingBox() else null
                    c.fill?.let { f -> val p = processPaint(f.paint, f.context == RStroke.CONTEXT_USE, cts, cbb, c.absTransform, bbox); if (p == null) c.fill = null else f.paint = p }
                    c.stroke?.let { s -> val p = processPaint(s.paint, s.context == RStroke.CONTEXT_USE, cts, cbb, c.absTransform, bbox); if (p == null) c.stroke = null else s.paint = p }
                }
                is RImageNode -> c.svg?.let { updatePaintServers(it.root, cts, cbb, null) }
                is RGlyphRun -> {
                    val fillUses = c.fill?.let { it.context != RStroke.CONTEXT_USE && usesObb(it.paint) } == true
                    val strokeUses = c.stroke?.let { it.context != RStroke.CONTEXT_USE && usesObb(it.paint) } == true
                    val bbox = if (fillUses || strokeUses) tb ?: c.boundingBox() else null
                    c.fill?.let { f -> val p = processPaint(f.paint, f.context == RStroke.CONTEXT_USE, cts, cbb, c.absTransform, bbox); if (p == null) c.fill = null else f.paint = p }
                    c.stroke?.let { s -> val p = processPaint(s.paint, s.context == RStroke.CONTEXT_USE, cts, cbb, c.absTransform, bbox); if (p == null) c.stroke = null else s.paint = p }
                }
                is RTextNode -> updatePaintServers(c.flattened, cts, cbb, c.bbox)
            }
        }
    }

    private fun usesObb(p: RPaint) = p.units == RPaint.UNITS_BBOX || p.contentUnits == RPaint.UNITS_BBOX

    private fun usesContextBbox(group: RGroup): Boolean = group.children.any { n ->
        when (n) {
            is RGroup -> !n.isContextElement && usesContextBbox(n)
            is RPathNode -> (n.fill?.let { it.context == RStroke.CONTEXT_USE && usesObb(it.paint) } == true) || (n.stroke?.let { it.context == RStroke.CONTEXT_USE && usesObb(it.paint) } == true)
            is RImageNode -> n.svg?.let { usesContextBbox(it.root) } == true
            is RGlyphRun -> (n.fill?.let { it.context == RStroke.CONTEXT_USE && usesObb(it.paint) } == true) || (n.stroke?.let { it.context == RStroke.CONTEXT_USE && usesObb(it.paint) } == true)
            is RTextNode -> usesContextBbox(n.flattened)
        }
    }

    fun processPaint(paintIn: RPaint, hasContext: Boolean, ctxTs: Ts, ctxBbox: SRect?, pathTs: Ts, bbox: SRect?): RPaint? {
        var paint = paintIn
        if (usesObb(paint)) {
            val bb = (if (hasContext) ctxBbox else bbox) ?: return null
            paint = toUserCoordinates(paint, bb) ?: return null
        }
        if (hasContext) {
            val rev = ctxTs.invert()?.pre(pathTs)?.invert() ?: return paint
            paint = when (paint) {
                is RPaint.Solid -> paint
                is RPaint.Linear -> RPaint.Linear(genId("linearGradient"), paint.x1, paint.y1, paint.x2, paint.y2, paint.units, paint.transform.post(rev), paint.spread, paint.linearRgb, paint.stops)
                is RPaint.Radial -> RPaint.Radial(genId("radialGradient"), paint.cx, paint.cy, paint.r, paint.fx, paint.fy, paint.fr, paint.units, paint.transform.post(rev), paint.spread, paint.linearRgb, paint.stops)
                is RPaint.Pattern -> RPaint.Pattern(genId("pattern"), paint.units, paint.contentUnits, paint.transform.post(rev), paint.rect, paint.viewBox, paint.root)
            }
        }
        return paint
    }

    private fun pushPatternTransform(root: RGroup, ts: Ts): RGroup {
        val g = RGroup()
        g.children.addAll(root.children)
        g.transform = ts
        g.absTransform = ts
        val r = RGroup()
        r.children.add(g)
        return r
    }

    private fun toUserCoordinates(p: RPaint, bboxIn: SRect): RPaint? {
        val bbox = bboxIn.takeIf { it.nonZero } ?: return null
        return when (p) {
            is RPaint.Solid -> p
            is RPaint.Linear -> RPaint.Linear(p.id, p.x1, p.y1, p.x2, p.y2, RPaint.UNITS_USER, p.transform.post(Ts.fromBbox(bbox)), p.spread, p.linearRgb, p.stops)
            is RPaint.Radial -> RPaint.Radial(p.id, p.cx, p.cy, p.r, p.fx, p.fy, p.fr, RPaint.UNITS_USER, p.transform.post(Ts.fromBbox(bbox)), p.spread, p.linearRgb, p.stops)
            is RPaint.Pattern -> {
                val rect = if (p.units == RPaint.UNITS_BBOX) p.rect.bboxTransform(bbox) else p.rect
                var root = if (p.contentUnits == RPaint.UNITS_BBOX && p.viewBox == null) pushPatternTransform(p.root, Ts.scale(bbox.w, bbox.h)) else p.root
                if (p.viewBox != null) root = pushPatternTransform(root, viewBoxTransform(p.viewBox.first, p.viewBox.second, rect.w, rect.h))
                RPaint.Pattern(p.id, RPaint.UNITS_USER, RPaint.UNITS_USER, p.transform, rect, p.viewBox, root)
            }
        }
    }

    fun convertPath(node: SvgNode, path: SPath, state: State, parent: RGroup) {
        if (path.verbs.size < 2) return
        val b = path.bounds()
        val hasBbox = b != null && b.w > 0f && b.h > 0f
        var fill = resolveFill(node, hasBbox, state)
        var stroke = resolveStroke(node, hasBbox, state)
        val visibility = node.findAttr("visibility") ?: "visible"
        var visible = visibility == "visible"
        val shapeRendering = node.findAttr("shape-rendering")
        val antiAlias = !(shapeRendering == "optimizeSpeed" || shapeRendering == "crispEdges")
        val order = node.findAttr("paint-order")?.let { SvgSyntax.paintOrder(it) } ?: intArrayOf(0, 1, 2)
        val strokeFirst = order[0] == 1 || (order[0] == 2 && order[1] == 1)
        val pathTs = parent.absTransform
        if (fill == null && stroke == null) visible = false
        val ctxPath = state.contextPath
        if (fill != null && fill.context == RStroke.CONTEXT_PATH && ctxPath != null) {
            processPaint(fill.paint, true, ctxPath.first, ctxPath.second, pathTs, path.bounds())?.let { fill.paint = it }
            fill.context = 0
        }
        if (stroke != null && stroke.context == RStroke.CONTEXT_PATH && ctxPath != null) {
            processPaint(stroke.paint, true, ctxPath.first, ctxPath.second, pathTs, path.bounds())?.let { stroke.paint = it }
            stroke.context = 0
        }
        var markerGroup: RGroup? = null
        if (markerIsValid(node) && visibility == "visible") {
            val mg = RGroup()
            mg.absTransform = parent.absTransform
            val bbox = path.tightBounds()?.takeIf { it.nonZero }
            val f2 = fill?.copy()?.also { it.context = RStroke.CONTEXT_PATH }
            val s2 = stroke?.copy()?.also { it.context = RStroke.CONTEXT_PATH }
            val ms = state.copy(contextElement = f2 to s2, contextPath = pathTs to bbox)
            convertMarkers(node, path, ms, mg)
            markerGroup = mg
        }
        val id = if (!state.insideMarker) node.elementId else ""
        val p = RPathNode(id, visible, fill, stroke, strokeFirst, antiAlias, path, pathTs)
        if (markerGroup != null) {
            when {
                order[0] == 2 -> { parent.children.add(markerGroup); parent.children.add(p) }
                order[1] == 2 -> {
                    appendSingle(order[0], p, parent)
                    parent.children.add(markerGroup)
                    appendSingle(order[2], p, parent)
                }
                else -> { parent.children.add(p); parent.children.add(markerGroup) }
            }
        } else parent.children.add(p)
    }

    private fun appendSingle(kind: Int, p: RPathNode, parent: RGroup) {
        if (kind == 0 && p.fill != null) parent.children.add(p.copyWith(p.fill, null))
        if (kind == 1 && p.stroke != null) parent.children.add(p.copyWith(null, p.stroke))
    }

    private fun findLink(node: SvgNode, aid: String): SvgNode? {
        if (doc.circular.contains(node to aid)) return null
        val v = node.findAttrNode(aid)?.rawAttr(aid) ?: return null
        if (v == "none") return null
        return SvgSyntax.funcIri(v)?.let { doc.byId(it) }
    }

    private fun markerIsValid(node: SvgNode): Boolean {
        if (node.ancestors().any { it.tag == "clipPath" }) return false
        return listOf("marker-start", "marker-mid", "marker-end").any { a -> findLink(node, a) != null }
    }

    private class Seg(val kind: Int, val x: Float, val y: Float, val x1: Float = 0f, val y1: Float = 0f, val x2: Float = 0f, val y2: Float = 0f)

    private fun convertMarkers(node: SvgNode, path: SPath, state: State, parent: RGroup) {
        for ((aid, kind) in listOf("marker-start" to 0, "marker-mid" to 1, "marker-end" to 2)) {
            val link = findLink(node, aid) ?: continue
            if (link.tag == "marker") resolveMarker(node, path, link, kind, state, parent)
        }
    }

    private fun resolveMarker(shape: SvgNode, path: SPath, marker: SvgNode, kind: Int, state: State, parent: RGroup) {
        val strokeScale = if (marker.attr("markerUnits") == "userSpaceOnUse") 1f else resolveLength(shape, "stroke-width", state, 1f).takeIf { it > 0f && it.isFinite() } ?: return
        val r = SRect.nz(
            userLength(marker, "refX", state, SvgLength.ZERO), userLength(marker, "refY", state, SvgLength.ZERO),
            userLength(marker, "markerWidth", state, SvgLength.num(3.0)), userLength(marker, "markerHeight", state, SvgLength.num(3.0))
        ) ?: return
        val vbTs = parseViewBox(marker)?.let { viewBoxTransform(it, aspect(marker), r.w, r.h) } ?: Ts.IDENTITY
        val overflow = marker.attr("overflow")
        val hasOverflow = overflow == null || overflow == "hidden" || overflow == "scroll"
        val clip = if (hasOverflow) {
            val inv = vbTs.invert() ?: return
            val clipRect = SRect(0f, 0f, r.w, r.h).transformNz(inv) ?: return
            val cg = RGroup()
            cg.children.add(RPathNode("", true, RFill(RPaint.Solid(0, 0, 0), 1f, false), null, false, true, SPath.rect(clipRect), Ts.IDENTITY))
            RClip(genId("clipPath"), Ts.IDENTITY, null, cg)
        } else null
        val segs = ArrayList<Seg>()
        var prevX = 0f; var prevY = 0f; var mvX = 0f; var mvY = 0f
        path.forEachSegment { v, b ->
            when (v) {
                SPath.MOVE -> { segs.add(Seg(0, b[0], b[1])); prevX = b[0]; prevY = b[1]; mvX = b[0]; mvY = b[1] }
                SPath.LINE -> { segs.add(Seg(1, b[0], b[1])); prevX = b[0]; prevY = b[1] }
                SPath.QUAD -> {
                    val c1x = (prevX + b[0] * 2f) / 3f; val c1y = (prevY + b[1] * 2f) / 3f
                    val c2x = (b[2] + b[0] * 2f) / 3f; val c2y = (b[3] + b[1] * 2f) / 3f
                    segs.add(Seg(2, b[2], b[3], c1x, c1y, c2x, c2y)); prevX = b[2]; prevY = b[3]
                }
                SPath.CUBIC -> { segs.add(Seg(2, b[4], b[5], b[0], b[1], b[2], b[3])); prevX = b[4]; prevY = b[5] }
                SPath.CLOSE -> { segs.add(Seg(3, 0f, 0f)); prevX = mvX; prevY = mvY }
            }
        }
        val orient = marker.attr("orient")
        val autoOrient = orient == "auto" || orient == "auto-start-reverse"
        val fixedAngle = if (!autoOrient) (orient?.let { SvgSyntax.angle(it) }?.toFloat() ?: 0f) else 0f
        val angles = if (autoOrient) vertexAngles(segs) else FloatArray(0)
        fun draw(px: Float, py: Float, idx: Int) {
            var ts = Ts.translate(px, py)
            val angle = when {
                orient == "auto-start-reverse" && idx == 0 -> (angles[idx] + 180f) % 360f
                autoOrient -> angles[idx]
                else -> fixedAngle
            }
            if (abs(angle) > 4 * Float.MIN_VALUE && angle != 0f) ts = ts.pre(Ts.rotate(angle))
            val sc = vbTs.scaleXY()
            ts = ts.preScale(sc[0] * strokeScale, sc[1] * strokeScale)
            ts = ts.preTranslate(-r.x, -r.y)
            val g = RGroup()
            g.transform = ts
            g.absTransform = parent.absTransform.pre(ts)
            g.clipPath = clip
            convertChildren(marker, state.copy(insideMarker = true), g)
            g.invalidate()
            if (g.hasChildren()) parent.children.add(g)
        }
        fun subpathStart(idx: Int): FloatArray { for (i in idx - 1 downTo 0) if (segs[i].kind == 0) return floatArrayOf(segs[i].x, segs[i].y); return floatArrayOf(0f, 0f) }
        when (kind) {
            0 -> segs.firstOrNull()?.takeIf { it.kind == 0 }?.let { draw(it.x, it.y, 0) }
            1 -> { val total = segs.size - 1; var i = 1; while (i < total) { val s = segs[i]; if (s.kind != 3) draw(s.x, s.y, i); i++ } }
            2 -> {
                val idx = segs.size - 1
                val l = segs.lastOrNull()
                when (l?.kind) { 1, 2 -> draw(l.x, l.y, idx); 3 -> { val p = subpathStart(idx); draw(p[0], p[1], idx) } }
            }
        }
    }

    private fun vertexAngles(path: List<Seg>): FloatArray {
        fun same(ax: Float, ay: Float, bx: Float, by: Float) = abs(ax - bx) <= 4 * Math.ulp(maxOf(abs(ax), abs(bx))) && abs(ay - by) <= 4 * Math.ulp(maxOf(abs(ay), abs(by)))
        fun subStart(idx: Int): FloatArray { for (i in idx - 1 downTo 0) if (path[i].kind == 0) return floatArrayOf(path[i].x, path[i].y); return floatArrayOf(0f, 0f) }
        fun prevVertex(idx: Int): FloatArray = path[idx - 1].let { if (it.kind == 3) subStart(idx) else floatArrayOf(it.x, it.y) }
        fun startTangent(idx: Int): FloatArray? {
            val s = path[idx]
            val towards = when (s.kind) {
                0 -> return null
                1 -> arrayOf(floatArrayOf(s.x, s.y), floatArrayOf(s.x, s.y), floatArrayOf(s.x, s.y))
                2 -> arrayOf(floatArrayOf(s.x1, s.y1), floatArrayOf(s.x2, s.y2), floatArrayOf(s.x, s.y))
                else -> subStart(idx).let { arrayOf(it, it, it) }
            }
            val from = prevVertex(idx)
            val p = towards.firstOrNull { !same(from[0], from[1], it[0], it[1]) } ?: return null
            return floatArrayOf(from[0], from[1], p[0], p[1])
        }
        fun endTangent(idx: Int): FloatArray? {
            val s = path[idx]
            val to: FloatArray; val from: Array<FloatArray>
            when (s.kind) {
                0 -> return null
                1 -> { to = floatArrayOf(s.x, s.y); val p = prevVertex(idx); from = arrayOf(p, p, p) }
                2 -> { to = floatArrayOf(s.x, s.y); from = arrayOf(floatArrayOf(s.x2, s.y2), floatArrayOf(s.x1, s.y1), prevVertex(idx)) }
                else -> { to = subStart(idx); val p = prevVertex(idx); from = arrayOf(p, p, p) }
            }
            val p = from.firstOrNull { !same(it[0], it[1], to[0], to[1]) } ?: return null
            return floatArrayOf(p[0], p[1], to[0], to[1])
        }
        val n = path.size
        val precEnd = arrayOfNulls<FloatArray>(n)
        var last: FloatArray? = null
        for (i in 0 until n) { if (path[i].kind == 0) { last = null; continue }; precEnd[i] = last; last = endTangent(i) ?: last }
        val follStart = arrayOfNulls<FloatArray>(n)
        var next: FloatArray? = null
        for (i in n - 1 downTo 0) { if (path[i].kind == 0) { next = null; continue }; follStart[i] = next; next = startTangent(i) ?: next }
        fun startDir(i: Int) = startTangent(i) ?: precEnd[i] ?: follStart[i]
        fun endDir(i: Int) = endTangent(i) ?: follStart[i] ?: precEnd[i]
        fun normalize(rad: Float): Float { val v = rad % (Math.PI.toFloat() * 2f); return if (v < 0f) v + Math.PI.toFloat() * 2f else v }
        fun vAngle(vx: Float, vy: Float): Float { val r = Math.atan2(vy.toDouble(), vx.toDouble()).toFloat(); return if (r.isNaN()) 0f else normalize(r) }
        fun calcAngle(a: FloatArray, b: FloatArray): Float {
            val inA = vAngle(a[2] - a[0], a[3] - a[1])
            val outA = vAngle(b[2] - b[0], b[3] - b[1])
            val d = (outA - inA) * 0.5f
            var angle = inA + d
            if (Math.PI.toFloat() / 2f < abs(d)) angle -= Math.PI.toFloat()
            return Math.toDegrees(normalize(angle).toDouble()).toFloat()
        }
        val angles = FloatArray(n)
        var start = 0
        while (start < n) {
            var end = n
            for (i in start + 1 until n) if (path[i].kind == 0) { end = i; break }
            val closed = path[end - 1].kind == 3
            for (idx in start until end) {
                val (prec, foll) = if (closed && (path[idx].kind == 0 || path[idx].kind == 3)) (end - 1) to (start + 1)
                else (if (idx > start) idx else -1) to (if (idx + 1 < end) idx + 1 else -1)
                val incoming = if (prec >= 0 && prec < n) endDir(prec) else null
                val outgoing = if (foll >= 0 && foll < n) startDir(foll) else null
                angles[idx] = when {
                    incoming != null && outgoing != null -> calcAngle(incoming, outgoing)
                    incoming != null -> calcAngle(incoming, incoming)
                    outgoing != null -> calcAngle(outgoing, outgoing)
                    else -> 0f
                }
            }
            start = end
        }
        return angles
    }

    fun convertClip(node: SvgNode, state: State, objectBbox: () -> SRect?): RClip? {
        if (node.tag != "clipPath") return null
        val tv = node.attr("transform")
        var transform = if (tv == null) Ts.IDENTITY else {
            val d = SvgSyntax.transform(tv) ?: return null
            if (!Ts.of(d).isValid) return null
            resolveTransform(node, "transform", state)
        }
        val units = units(node, "clipPathUnits") ?: RPaint.UNITS_USER
        val cacheable = units == RPaint.UNITS_USER
        if (cacheable) clipCache[node.elementId]?.let { return it }
        if (units == RPaint.UNITS_BBOX) {
            val bb = objectBbox() ?: return null
            transform = transform.pre(Ts.fromBbox(bb))
        }
        var nested: RClip? = null
        val link = node.nodeAttr("clip-path")
        if (link != null) nested = convertClip(link, state, objectBbox) ?: return null
        var id = node.elementId.ifEmpty { return null }
        if (!cacheable && clipCache.containsKey(id)) id = genId("clipPath")
        val root = RGroup()
        convertClipPathElements(node, state.copy(parentClipPath = node), root)
        if (!root.hasChildren()) return null
        val clip = RClip(id, transform, nested, root)
        clipCache[id] = clip
        return clip
    }

    fun convertMask(node: SvgNode, state: State, objectBbox: () -> SRect?): RMask? {
        if (node.tag != "mask") return null
        val units = units(node, "maskUnits") ?: RPaint.UNITS_BBOX
        val contentUnits = units(node, "maskContentUnits") ?: RPaint.UNITS_USER
        val cacheable = units == RPaint.UNITS_USER && contentUnits == RPaint.UNITS_USER
        if (cacheable) maskCache[node.elementId]?.let { return it }
        var rect = SRect.nz(
            nodeLength(node, "x", units, state, SvgLength.pct(-10.0)), nodeLength(node, "y", units, state, SvgLength.pct(-10.0)),
            nodeLength(node, "width", units, state, SvgLength.pct(120.0)), nodeLength(node, "height", units, state, SvgLength.pct(120.0))
        ) ?: return null
        var maskAll = false
        if (units == RPaint.UNITS_BBOX) { val bb = objectBbox(); if (bb != null) rect = rect.bboxTransform(bb) else maskAll = true }
        var id = node.elementId.ifEmpty { return null }
        if (!cacheable && maskCache.containsKey(id)) id = genId("mask")
        if (maskAll) { val m = RMask(id, rect, false, false, null, RGroup()); maskCache[id] = m; return m }
        var nested: RMask? = null
        val link = node.nodeAttr("mask")
        if (link != null) nested = convertMask(link, state, objectBbox) ?: return null
        val alpha = node.attr("mask-type") == "alpha"
        val root = RGroup()
        if (contentUnits == RPaint.UNITS_BBOX) {
            val bb = objectBbox() ?: return null
            val g = RGroup()
            g.transform = Ts.fromBbox(bb)
            g.absTransform = g.transform
            convertChildren(node, state, g)
            if (!g.hasChildren()) return null
            root.children.add(g)
        } else {
            convertChildren(node, state, root)
            if (!root.hasChildren()) return null
        }
        updatePaintServers(root, Ts.IDENTITY, null, null)
        val m = RMask(id, rect, alpha, linearRgb(node), nested, root)
        maskCache[id] = m
        return m
    }

    private fun convertUse(node: SvgNode, state: State, parent: RGroup) {
        val child = node.children.firstOrNull() ?: return
        if (state.parentClipPath != null && child.tag == "symbol") return
        var useState = state.copy(contextElement = resolveFill(node, true, state)?.also { it.context = RStroke.CONTEXT_USE } to resolveStroke(node, true, state)?.also { it.context = RStroke.CONTEXT_USE })
        var origTs = resolveTransform(node, "transform", state)
        val x = userLength(node, "x", useState, SvgLength.ZERO)
        val y = userLength(node, "y", useState, SvgLength.ZERO)
        var newTs = Ts.translate(x, y)
        val linkedToSymbol = child.tag == "symbol"
        if (linkedToSymbol) {
            val sx = userLength(child, "x", useState, SvgLength.ZERO)
            val sy = userLength(child, "y", useState, SvgLength.ZERO)
            val def = SvgLength.pct(100.0)
            val w = userLength(if (node.hasAttr("width")) node else child, "width", useState, def)
            val h = userLength(if (node.hasAttr("height")) node else child, "height", useState, def)
            useState = useState.copy(viewBox = SRect.nz(useState.viewBox.x, useState.viewBox.y, w, h) ?: useState.viewBox)
            newTs = newTs.preTranslate(sx, sy)
            if (w > 0f && h > 0f) parseViewBox(child)?.let { newTs = newTs.pre(viewBoxTransform(it, aspect(child), w, h)) }
            val clipRect = if (clipsContent(child)) SRect.nz(x + sx, y + sy, w, h) else null
            if (clipRect != null) {
                val g = clipElement(node, clipRect, origTs, useState)
                g.absTransform = parent.absTransform
                val st = useState
                val nts = newTs
                convertGroup(node, st, true, g) { g2 -> convertChildrenTs(child, nts, st, false, g2) }?.let { g2 ->
                    g.isContextElement = true
                    g2.id = ""
                    g2.transform = Ts.IDENTITY
                    g2.invalidate()
                    g.children.add(g2)
                }
                if (g.children.isEmpty()) return
                g.invalidate()
                parent.children.add(g)
                return
            }
        }
        origTs = origTs.pre(newTs)
        if (linkedToSymbol) {
            val st = useState
            val ots = origTs
            convertGroup(node, st, false, parent) { g -> convertChildrenTs(child, ots, st, false, g) }?.let { g ->
                g.isContextElement = true
                g.transform = Ts.IDENTITY
                g.invalidate()
                parent.children.add(g)
            }
        } else {
            if (child.tag == "svg") {
                val def = SvgLength.pct(100.0)
                useState = useState.copy(useSize = (if (node.hasAttr("width")) userLength(node, "width", useState, def) else null) to (if (node.hasAttr("height")) userLength(node, "height", useState, def) else null))
            }
            convertChildrenTs(node, origTs, useState, true, parent)
        }
    }

    private fun convertNestedSvg(node: SvgNode, state: State, parent: RGroup) {
        var origTs = resolveTransform(node, "transform", state)
        val x = userLength(node, "x", state, SvgLength.ZERO)
        val y = userLength(node, "y", state, SvgLength.ZERO)
        val def = SvgLength.pct(100.0)
        val w = state.useSize.first ?: userLength(node, "width", state, def)
        val h = state.useSize.second ?: userLength(node, "height", state, def)
        var newTs = Ts.translate(x, y)
        if (w > 0f && h > 0f) parseViewBox(node)?.let { newTs = newTs.pre(viewBoxTransform(it, aspect(node), w, h)) }
        val newState = state.copy(viewBox = parseViewBox(node) ?: SRect.nz(x, y, w, h) ?: state.viewBox)
        val clipRect = if (!clipsContent(node)) null else if (state.useSize.first == null && state.useSize.second == null && !(node.hasAttr("width") && node.hasAttr("height"))) null
            else if (!(w > 0f && w.isFinite() && h > 0f && h.isFinite())) null else SRect.nz(x, y, w, h)
        if (clipRect != null) {
            val g = clipElement(node, clipRect, origTs, state)
            g.absTransform = parent.absTransform.pre(origTs)
            convertChildrenTs(node, newTs, newState, false, g)
            g.invalidate()
            parent.children.add(g)
        } else {
            origTs = origTs.pre(newTs)
            convertChildrenTs(node, origTs, newState, false, parent)
        }
    }

    private fun clipsContent(n: SvgNode) = n.attr("overflow").let { it != "visible" && it != "auto" }

    private fun clipElement(node: SvgNode, rect: SRect, ts: Ts, state: State): RGroup {
        val cg = RGroup()
        cg.children.add(RPathNode("", true, RFill(RPaint.Solid(0, 0, 0), 1f, false), null, false, true, SPath.rect(rect), Ts.IDENTITY))
        val g = RGroup()
        g.id = if (!state.insideMarker) node.elementId else ""
        g.transform = ts
        g.clipPath = RClip(genId("clipPath"), Ts.IDENTITY, null, cg)
        return g
    }

    private fun convertChildrenTs(node: SvgNode, ts: Ts, state: State, isContext: Boolean, parent: RGroup) {
        val parentAbs = parent.absTransform
        val required = !ts.isIdentity
        convertGroup(node, state, required, parent) { g ->
            g.transform = ts
            g.absTransform = parentAbs.pre(ts)
            g.isContextElement = isContext
            if (state.parentClipPath != null) convertClipPathElements(node, state, g) else convertChildren(node, state, g)
        }?.let { parent.children.add(it) }
    }

    private fun convertSwitch(node: SvgNode, state: State, parent: RGroup) {
        val child = node.children.firstOrNull { isConditionPassed(it) } ?: return
        convertGroup(node, state, false, parent) { g -> convertElement(child, state, g) }?.let { parent.children.add(it) }
    }

    fun decodeImage(href: String): Pair<Bitmap?, SvgTree?>? {
        if (href.startsWith("data:")) {
            val comma = href.indexOf(',')
            if (comma < 0) return null
            val meta = href.substring(5, comma).trim()
            val isBase64 = meta.endsWith(";base64", true)
            val mime = meta.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
            val payload = href.substring(comma + 1)
            val data = try {
                if (isBase64) Base64.decode(payload.filter { !it.isWhitespace() }, Base64.DEFAULT) else java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
            } catch (e: Exception) { return null }
            return when (mime) {
                "image/jpg", "image/jpeg", "image/png", "image/gif", "image/webp" -> decodeRaster(data)?.let { it to null }
                "image/svg+xml" -> loadSubSvg(data)?.let { null to it }
                "text/plain" -> decodeRaster(data)?.let { it to null } ?: loadSubSvg(data)?.let { null to it }
                else -> null
            }
        }
        val data = opt.resolveExternal?.invoke(href) ?: return null
        val lower = href.lowercase()
        if (lower.endsWith(".svg") || lower.endsWith(".svgz")) return loadSubSvg(data)?.let { null to it }
        return decodeRaster(data)?.let { it to null }
    }

    private fun decodeRaster(data: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.let {
            if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    } catch (e: OutOfMemoryError) { null }

    private fun loadSubSvg(data: ByteArray): SvgTree? {
        if (opt.nestedDepth >= 1) return null
        return try {
            val bytes = SvgDocumentSession.gunzipIfNeeded(data, 32 * 1024 * 1024)
            val sub = SvgDoc.parse(SvgDocumentSession.decodeText(bytes))
            val o = SvgOptions(opt.dpi, opt.fontSize, opt.languages, opt.defaultWidth, opt.defaultHeight, null, opt.nestedDepth + 1, opt.textRenderer)
            convert(sub, o)
        } catch (e: Exception) { null }
    }

    private fun convertImage(node: SvgNode, state: State, parent: RGroup) {
        val href = node.tryAttr("href") ?: return
        val kind = decodeImage(href) ?: return
        val visible = (node.findAttr("visibility") ?: "visible") == "visible"
        val ir = node.findAttr("image-rendering")
        val smooth = !(ir == "optimizeSpeed")
        val aw: Float; val ah: Float
        if (kind.first != null) { aw = kind.first!!.width.toFloat(); ah = kind.first!!.height.toFloat() } else { aw = kind.second!!.width; ah = kind.second!!.height }
        val x = userLength(node, "x", state, SvgLength.ZERO)
        val y = userLength(node, "y", state, SvgLength.ZERO)
        var w = userLength(node, "width", state, SvgLength.num(aw.toDouble()))
        var h = userLength(node, "height", state, SvgLength.num(ah.toDouble()))
        val hasW = attrLength(node, "width") != null
        val hasH = attrLength(node, "height") != null
        if (hasW && !hasH) h = ah * (w / aw) else if (!hasW && hasH) w = aw * (h / ah)
        val rect = SRect.nz(x, y, w, h) ?: return
        val id = if (!state.insideMarker) node.elementId else ""
        imageInner(kind, id, visible, smooth, aspect(node), aw, ah, rect, parent)
    }

    fun imageInner(kind: Pair<Bitmap?, SvgTree?>, id: String, visible: Boolean, smooth: Boolean, aspect: SvgAspect, aw: Float, ah: Float, rect: SRect, parent: RGroup) {
        val (fw, fh) = if (aspect.align == SvgAspect.NONE) rect.w to rect.h else {
            val sx = rect.w / aw; val sy = rect.h / ah
            val s = if (aspect.slice) maxOf(sx, sy) else minOf(sx, sy)
            aw * s to ah * s
        }
        val pos = alignedPos(aspect.align, rect.x, rect.y, rect.w - fw, rect.h - fh)
        val imageTs = Ts(fw / aw, 0f, 0f, fh / ah, pos[0], pos[1])
        val abs = parent.absTransform.pre(imageTs)
        val g = RGroup()
        g.id = id
        g.children.add(RImageNode(visible, aw, ah, smooth, kind.first, kind.second, abs))
        g.transform = imageTs
        g.absTransform = abs
        if (aspect.slice) {
            val cg = RGroup()
            cg.children.add(RPathNode("", true, RFill(RPaint.Solid(0, 0, 0), 1f, false), null, false, true, SPath.rect(rect), Ts.IDENTITY))
            val g2 = RGroup()
            g2.id = g.id
            g.id = ""
            g2.absTransform = parent.absTransform
            g2.clipPath = RClip(genId("clipPath"), Ts.IDENTITY, null, cg)
            g2.children.add(g)
            g2.presetBbox = rect
            parent.children.add(g2)
        } else parent.children.add(g)
    }

    private fun convertFilters(node: SvgNode, state: State, objectBbox: SRect?): List<RFilter>? {
        val value = node.attr("filter") ?: return emptyList()
        val list = SvgSyntax.filterValues(value) ?: return emptyList()
        var invalidUrls = false
        val out = ArrayList<RFilter>()
        fun base(kind: FeKind) {
            var rect = if (kind is FeKind.DropShadow || kind is FeKind.GaussianBlur) SRect(-0.5f, -0.5f, 2f, 2f) else SRect(-0.1f, -0.1f, 1.2f, 1.2f)
            val bb = objectBbox ?: return
            rect = rect.bboxTransform(bb)
            out.add(RFilter(genId("filter"), rect, listOf(RPrimitive(rect, false, "result", kind))))
        }
        fun ct(r: FeTransfer, g: FeTransfer, b: FeTransfer, a: FeTransfer) = FeKind.ComponentTransfer(FeInput.SourceGraphic, r, g, b, a)
        for (f in list) {
            when (f) {
                is SvgFilterValue.Blur -> { val sd = convertLength(f.stdDev, node, "dx", 0, state).let { if (it >= 0f) it else 0f }; base(FeKind.GaussianBlur(FeInput.SourceGraphic, sd, sd)) }
                is SvgFilterValue.DropShadow -> {
                    val sd = convertLength(f.stdDev, node, "dx", 0, state).let { if (it >= 0f) it else 0f }
                    val c = f.color ?: findColor(node, "color") ?: SvgColor.BLACK
                    base(FeKind.DropShadow(FeInput.SourceGraphic, convertLength(f.dx, node, "dx", 0, state), convertLength(f.dy, node, "dy", 0, state), sd, sd, SvgColor(c.r, c.g, c.b, 255), c.a / 255f))
                }
                is SvgFilterValue.Brightness -> { val a = f.v.toFloat(); base(ct(FeTransfer.Linear(a, 0f), FeTransfer.Linear(a, 0f), FeTransfer.Linear(a, 0f), FeTransfer.Identity)) }
                is SvgFilterValue.Contrast -> { val a = f.v.toFloat(); val i = -(0.5f * a) + 0.5f; base(ct(FeTransfer.Linear(a, i), FeTransfer.Linear(a, i), FeTransfer.Linear(a, i), FeTransfer.Identity)) }
                is SvgFilterValue.Grayscale -> {
                    val a = minOf(f.v, 1.0).toFloat(); val k = 1f - a
                    base(FeKind.ColorMatrix(FeInput.SourceGraphic, 0, floatArrayOf(0.2126f + 0.7874f * k, 0.7152f - 0.7152f * k, 0.0722f - 0.0722f * k, 0f, 0f,
                        0.2126f - 0.2126f * k, 0.7152f + 0.2848f * k, 0.0722f - 0.0722f * k, 0f, 0f, 0.2126f - 0.2126f * k, 0.7152f - 0.7152f * k, 0.0722f + 0.9278f * k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
                }
                is SvgFilterValue.HueRotate -> base(FeKind.ColorMatrix(FeInput.SourceGraphic, 2, floatArrayOf(f.degrees.toFloat())))
                is SvgFilterValue.Invert -> { val a = minOf(f.v, 1.0).toFloat(); val t = FeTransfer.Table(floatArrayOf(a, 1f - a)); base(ct(t, t, t, FeTransfer.Identity)) }
                is SvgFilterValue.Opacity -> { val a = minOf(f.v, 1.0).toFloat(); base(ct(FeTransfer.Identity, FeTransfer.Identity, FeTransfer.Identity, FeTransfer.Table(floatArrayOf(0f, a)))) }
                is SvgFilterValue.Sepia -> {
                    val a = minOf(f.v, 1.0).toFloat(); val k = 1f - a
                    base(FeKind.ColorMatrix(FeInput.SourceGraphic, 0, floatArrayOf(0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k, 0f, 0f,
                        0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k, 0f, 0f, 0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
                }
                is SvgFilterValue.Saturate -> base(FeKind.ColorMatrix(FeInput.SourceGraphic, 1, floatArrayOf(maxOf(f.v.toFloat(), 0f))))
                is SvgFilterValue.Url -> {
                    val link = doc.byId(f.id)
                    if (link == null) { invalidUrls = true; continue }
                    val r = convertFilterUrl(link, state, objectBbox)
                    if (r == null) invalidUrls = true else if (r.isPresent) out.add(r.get())
                }
            }
        }
        if (out.isEmpty() && invalidUrls) return null
        return out
    }

    private fun convertFilterUrl(node: SvgNode, state: State, objectBbox: SRect?): java.util.Optional<RFilter>? {
        if (node.tag != "filter") return null
        val units = resolveUnits(node, "filterUnits", RPaint.UNITS_BBOX)
        val primUnits = resolveUnits(node, "primitiveUnits", RPaint.UNITS_USER)
        val cacheable = units == RPaint.UNITS_USER && primUnits == RPaint.UNITS_USER
        if (cacheable) filterCache[node.elementId]?.let { return java.util.Optional.of(it) }
        var rect = SRect.nz(
            resolveNumber(node, "x", units, state, SvgLength.pct(-10.0)), resolveNumber(node, "y", units, state, SvgLength.pct(-10.0)),
            resolveNumber(node, "width", units, state, SvgLength.pct(120.0)), resolveNumber(node, "height", units, state, SvgLength.pct(120.0))
        ) ?: return null
        if (units == RPaint.UNITS_BBOX) { val bb = objectBbox ?: return null; rect = rect.bboxTransform(bb) }
        var withPrims: SvgNode? = null
        for (link in node.hrefIter()) {
            if (link.tag != "filter") return null
            if (link.children.isNotEmpty()) { withPrims = link; break }
        }
        withPrims ?: return null
        val prims = SvgFilterParser(this).collect(withPrims, primUnits, state, objectBbox, rect)
        if (prims.isEmpty()) return null
        var id = node.elementId.ifEmpty { return null }
        if (!cacheable && filterCache.containsKey(id)) id = genId("filter")
        val f = RFilter(id, rect, prims)
        filterCache[id] = f
        return java.util.Optional.of(f)
    }
}

internal class SvgFilterParser(private val c: SvgConverter) {
    fun collect(filter: SvgNode, units: Int, state: SvgConverter.State, objectBbox: SRect?, region: SRect): List<RPrimitive> {
        val prims = ArrayList<RPrimitive>()
        val names = HashSet<String>()
        var idx = 1
        val scaleW: Float; val scaleH: Float
        if (units == RPaint.UNITS_BBOX) { val bb = objectBbox ?: return emptyList(); scaleW = bb.w; scaleH = bb.h } else { scaleW = 1f; scaleH = 1f }
        for (child in filter.children) {
            val tag = child.tag ?: continue
            val defRegion = defaultSubregion(child, prims, region)
            val sub = resolveRegion(child, units, state, objectBbox, defRegion) ?: break
            val kind: FeKind = when (tag) {
                "feDropShadow" -> {
                    val sd = stdDev(child, scaleW, scaleH, "2 2")
                    val c0 = c.attrColor(child, "flood-color") ?: SvgColor.BLACK
                    val fo = SvgConverter.parseOpacity(child.attr("flood-opacity")) ?: 1f
                    FeKind.DropShadow(input(child, "in", prims), (num(child, "dx") ?: 2f) * scaleW, (num(child, "dy") ?: 2f) * scaleH, sd[0], sd[1], SvgColor(c0.r, c0.g, c0.b, 255), c0.a / 255f * fo)
                }
                "feGaussianBlur" -> { val sd = stdDev(child, scaleW, scaleH, "0 0"); FeKind.GaussianBlur(input(child, "in", prims), sd[0], sd[1]) }
                "feOffset" -> FeKind.Offset(input(child, "in", prims), (num(child, "dx") ?: 0f) * scaleW, (num(child, "dy") ?: 0f) * scaleH)
                "feBlend" -> FeKind.Blend(SvgConverter.blendMode(child.attr("mode"), false) ?: 0, input(child, "in", prims), input(child, "in2", prims))
                "feFlood" -> { val c0 = c.attrColor(child, "flood-color") ?: SvgColor.BLACK; FeKind.Flood(SvgColor(c0.r, c0.g, c0.b, 255), c0.a / 255f * (SvgConverter.parseOpacity(child.attr("flood-opacity")) ?: 1f)) }
                "feComposite" -> {
                    val op = when (child.attr("operator") ?: "over") { "in" -> 1; "out" -> 2; "atop" -> 3; "xor" -> 4; "lighter" -> 5; "arithmetic" -> 6; else -> 0 }
                    FeKind.Composite(op, floatArrayOf(num(child, "k1") ?: 0f, num(child, "k2") ?: 0f, num(child, "k3") ?: 0f, num(child, "k4") ?: 0f), input(child, "in", prims), input(child, "in2", prims))
                }
                "feMerge" -> FeKind.Merge(child.children.map { input(it, "in", prims) })
                "feTile" -> FeKind.Tile(input(child, "in", prims))
                "feImage" -> image(child, sub, state)
                "feComponentTransfer" -> componentTransfer(child, prims)
                "feColorMatrix" -> colorMatrix(child, prims)
                "feConvolveMatrix" -> convolve(child, prims) ?: dummy()
                "feMorphology" -> morphology(child, scaleW, scaleH, prims)
                "feDisplacementMap" -> {
                    fun ch(a: String) = when (child.attr(a) ?: "A") { "R" -> 0; "G" -> 1; "B" -> 2; else -> 3 }
                    FeKind.DisplacementMap(input(child, "in", prims), input(child, "in2", prims), (num(child, "scale") ?: 0f) * ((scaleW + scaleH) / 2f), ch("xChannelSelector"), ch("yChannelSelector"))
                }
                "feTurbulence" -> turbulence(child)
                "feDiffuseLighting" -> light(child)?.let { FeKind.DiffuseLighting(input(child, "in", prims), num(child, "surfaceScale") ?: 1f, num(child, "diffuseConstant") ?: 1f, lightingColor(child), it) } ?: dummy()
                "feSpecularLighting" -> {
                    val l = light(child)
                    val se = num(child, "specularExponent") ?: 1f
                    if (l == null || se < 1f || se > 128f) dummy() else FeKind.SpecularLighting(input(child, "in", prims), num(child, "surfaceScale") ?: 1f, num(child, "specularConstant") ?: 1f, se, lightingColor(child), l)
                }
                else -> continue
            }
            val ci = child.findAttr("color-interpolation-filters")
            val linear = ci != "sRGB"
            val result = child.attr("result")?.let { names.add(it); idx++; it } ?: run {
                var n: String
                do { n = "result$idx"; idx++ } while (names.contains(n))
                n
            }
            prims.add(RPrimitive(sub, linear, result, kind))
        }
        return prims
    }

    private fun dummy() = FeKind.Flood(SvgColor.BLACK, 0f)
    private fun num(n: SvgNode, a: String) = c.attrNumber(n, a)

    private fun lightingColor(n: SvgNode): SvgColor = c.attrColor(n, "lighting-color")?.let { SvgColor(it.r, it.g, it.b, 255) } ?: SvgColor.WHITE

    private fun light(parent: SvgNode): FeLight? {
        val ch = parent.children.firstOrNull { it.tag == "feDistantLight" || it.tag == "fePointLight" || it.tag == "feSpotLight" } ?: return null
        return when (ch.tag) {
            "feDistantLight" -> FeLight.Distant(num(ch, "azimuth") ?: 0f, num(ch, "elevation") ?: 0f)
            "fePointLight" -> FeLight.Point(num(ch, "x") ?: 0f, num(ch, "y") ?: 0f, num(ch, "z") ?: 0f)
            else -> {
                val se = (num(ch, "specularExponent") ?: 1f).let { if (it >= 0f) it else 1f }
                FeLight.Spot(num(ch, "x") ?: 0f, num(ch, "y") ?: 0f, num(ch, "z") ?: 0f, num(ch, "pointsAtX") ?: 0f, num(ch, "pointsAtY") ?: 0f, num(ch, "pointsAtZ") ?: 0f, se, num(ch, "limitingConeAngle"))
            }
        }
    }

    private fun stdDev(fe: SvgNode, sw: Float, sh: Float, def: String): FloatArray {
        val text = fe.attr("stdDeviation") ?: def
        val list = SvgSyntax.numberListPrefix(text)
        val (x, y) = when (list.size) { 2 -> list[0] to list[1]; 1 -> list[0] to list[0]; else -> 0.0 to 0.0 }
        val sx = (x.toFloat() * sw).let { if (it >= 0f) it else 0f }
        val sy = (y.toFloat() * sh).let { if (it >= 0f) it else 0f }
        return floatArrayOf(sx, sy)
    }

    private fun defaultSubregion(fe: SvgNode, prims: List<RPrimitive>, region: SRect): SRect {
        val attrs = when (fe.tag) {
            "feBlend", "feComposite", "feDisplacementMap" -> listOf("in", "in2")
            "feColorMatrix", "feComponentTransfer", "feConvolveMatrix", "feDiffuseLighting", "feDropShadow", "feGaussianBlur", "feMorphology", "feOffset", "feSpecularLighting" -> listOf("in")
            else -> emptyList()
        }
        val inputs = ArrayList<FeInput>()
        if (fe.tag == "feMerge") for (ch in fe.children) inputs.add(input(ch, "in", prims))
        for (a in attrs) inputs.add(input(fe, a, prims))
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (i in inputs) {
            if (i !is FeInput.Ref) return region
            val rect = prims.lastOrNull { it.result == i.name }?.rect ?: return region
            any = true
            l = minOf(l, rect.x); t = minOf(t, rect.y); r = maxOf(r, rect.right); b = maxOf(b, rect.bottom)
        }
        if (!any) return region
        return SRect.ltrb(l, t, r, b)?.takeIf { it.nonZero } ?: region
    }

    private fun resolveRegion(fe: SvgNode, units: Int, state: SvgConverter.State, bbox: SRect?, def: SRect): SRect? {
        var x = c.tryLength(fe, "x", units, state)
        var y = c.tryLength(fe, "y", units, state)
        var w = c.tryLength(fe, "width", units, state)
        var h = c.tryLength(fe, "height", units, state)
        if (units == RPaint.UNITS_BBOX) {
            val bb = bbox ?: return null
            x = x?.let { bb.x + it * bb.w }; y = y?.let { bb.y + it * bb.h }; w = w?.let { it * bb.w }; h = h?.let { it * bb.h }
        }
        return SRect.nz(x ?: def.x, y ?: def.y, w ?: def.w, h ?: def.h)
    }

    fun input(node: SvgNode, aid: String, prims: List<RPrimitive>): FeInput {
        val s = node.attr(aid)
        val fallback = prims.lastOrNull()?.let { FeInput.Ref(it.result) } ?: FeInput.SourceGraphic
        if (s == null) return fallback
        val i = when (s) {
            "SourceGraphic" -> FeInput.SourceGraphic
            "SourceAlpha" -> FeInput.SourceAlpha
            "BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint" -> FeInput.SourceGraphic
            else -> FeInput.Ref(s)
        }
        if (i is FeInput.Ref && prims.none { it.result == i.name }) return fallback
        return i
    }

    private fun colorMatrix(fe: SvgNode, prims: List<RPrimitive>): FeKind {
        val inp = input(fe, "in", prims)
        val list = c.attrNumberList(fe, "values")
        when (fe.attr("type")) {
            "saturate" -> if (list != null) return FeKind.ColorMatrix(inp, 1, floatArrayOf(if (list.isNotEmpty()) list[0].toFloat().coerceIn(0f, 1f) else 1f))
            "hueRotate" -> if (list != null) return FeKind.ColorMatrix(inp, 2, floatArrayOf(if (list.isNotEmpty()) list[0].toFloat() else 0f))
            "luminanceToAlpha" -> return FeKind.ColorMatrix(inp, 3, FloatArray(0))
            else -> if (list != null && list.size == 20) return FeKind.ColorMatrix(inp, 0, FloatArray(20) { list[it].toFloat() })
        }
        return FeKind.ColorMatrix(inp, 0, floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    }

    private fun componentTransfer(fe: SvgNode, prims: List<RPrimitive>): FeKind {
        var r: FeTransfer = FeTransfer.Identity; var g: FeTransfer = FeTransfer.Identity; var b: FeTransfer = FeTransfer.Identity; var a: FeTransfer = FeTransfer.Identity
        for (ch in fe.children.filter { it.isElement }) {
            val f: FeTransfer = when (ch.attr("type") ?: continue) {
                "identity" -> FeTransfer.Identity
                "table" -> FeTransfer.Table(c.attrNumberList(ch, "tableValues")?.map { it.toFloat() }?.toFloatArray() ?: FloatArray(0))
                "discrete" -> FeTransfer.Discrete(c.attrNumberList(ch, "tableValues")?.map { it.toFloat() }?.toFloatArray() ?: FloatArray(0))
                "linear" -> FeTransfer.Linear(num(ch, "slope") ?: 1f, num(ch, "intercept") ?: 0f)
                "gamma" -> FeTransfer.Gamma(num(ch, "amplitude") ?: 1f, num(ch, "exponent") ?: 1f, num(ch, "offset") ?: 0f)
                else -> continue
            }
            when (ch.tag) { "feFuncR" -> r = f; "feFuncG" -> g = f; "feFuncB" -> b = f; "feFuncA" -> a = f }
        }
        return FeKind.ComponentTransfer(input(fe, "in", prims), r, g, b, a)
    }

    private fun convolve(fe: SvgNode, prims: List<RPrimitive>): FeKind? {
        var ox = 3; var oy = 3
        fe.attr("order")?.let { v ->
            val l = SvgSyntax.numberListPrefix(v)
            val x = l.getOrNull(0)?.toInt() ?: 3
            val y = l.getOrNull(1)?.toInt() ?: x
            if (x > 0 && y > 0) { ox = x; oy = y }
        }
        var matrix = FloatArray(0)
        c.attrNumberList(fe, "kernelMatrix")?.let { if (it.size == ox * oy) matrix = it.map { v -> v.toFloat() }.toFloatArray() }
        var sum = matrix.sum()
        sum = Math.round(sum * 1_000_000f) / 1_000_000f
        if (abs(sum) <= 4 * Float.MIN_VALUE || sum == 0f) sum = 1f
        val divisor = num(fe, "divisor") ?: sum
        if (divisor == 0f) return null
        val bias = num(fe, "bias") ?: 0f
        fun target(t: Float?, order: Int): Int? { val d = Math.floor(order / 2.0).toInt(); val v = (t ?: d.toFloat()).toInt(); return if (v < 0 || v >= order) null else v }
        val tx = target(num(fe, "targetX"), ox) ?: return null
        val ty = target(num(fe, "targetY"), oy) ?: return null
        if (matrix.size != ox * oy) return null
        val edge = when (fe.attr("edgeMode") ?: "duplicate") { "none" -> 0; "wrap" -> 2; else -> 1 }
        return FeKind.ConvolveMatrix(input(fe, "in", prims), ox, oy, tx, ty, matrix, divisor, bias, edge, (fe.attr("preserveAlpha") ?: "false") == "true")
    }

    private fun morphology(fe: SvgNode, sw: Float, sh: Float, prims: List<RPrimitive>): FeKind {
        val dilate = (fe.attr("operator") ?: "erode") == "dilate"
        var rx = sw; var ry = sh
        c.attrNumberList(fe, "radius")?.let { list ->
            var x = 0f; var y = 0f
            if (list.size == 2) { x = list[0].toFloat(); y = list[1].toFloat() } else if (list.size == 1) { x = list[0].toFloat(); y = x }
            fun z(v: Float) = abs(v) <= 4 * Float.MIN_VALUE || v == 0f
            if (z(x) && z(y)) { x = 1f; y = 1f }
            if (z(x) && !z(y)) x = 1f
            if (!z(x) && z(y)) y = 1f
            if (x >= 0f && y >= 0f) { rx = x * sw; ry = y * sh }
        }
        return FeKind.Morphology(input(fe, "in", prims), dilate, rx, ry)
    }

    private fun turbulence(fe: SvgNode): FeKind {
        var bx = 0f; var by = 0f
        c.attrNumberList(fe, "baseFrequency")?.let { list ->
            var x = 0f; var y = 0f
            if (list.size == 2) { x = list[0].toFloat(); y = list[1].toFloat() } else if (list.size == 1) { x = list[0].toFloat(); y = x }
            if (x >= 0f && y >= 0f) { bx = x; by = y }
        }
        var oct = (num(fe, "numOctaves") ?: 1f).coerceIn(0f, 10f)
        val seed = (num(fe, "seed") ?: 0f).toInt()
        return FeKind.Turbulence(bx, by, Math.round(oct), seed, fe.attr("stitchTiles") == "stitch", (fe.attr("type") ?: "turbulence") == "fractalNoise")
    }

    private fun image(fe: SvgNode, sub: SRect, state: SvgConverter.State): FeKind {
        val link = fe.tryAttr("href")?.let { v -> SvgSyntax.iri(v)?.let { c.doc.byId(it) } }
        if (link != null) {
            val root = RGroup()
            c.convertElement(link, state.copy(feImageLink = true), root)
            if (!root.hasChildren()) return dummy()
            c.updatePaintServers(root, Ts.IDENTITY, null, null)
            return FeKind.Image(root)
        }
        val href = fe.tryAttr("href") ?: return dummy()
        val kind = c.decodeImage(href) ?: return dummy()
        val aw: Float; val ah: Float
        if (kind.first != null) { aw = kind.first!!.width.toFloat(); ah = kind.first!!.height.toFloat() } else { aw = kind.second!!.width; ah = kind.second!!.height }
        val ir = fe.findAttr("image-rendering")
        val root = RGroup()
        c.imageInner(kind, c.genId("image"), true, ir != "optimizeSpeed", c.aspect(fe), aw, ah, sub.translateTo(0f, 0f), root)
        return FeKind.Image(root)
    }
}
