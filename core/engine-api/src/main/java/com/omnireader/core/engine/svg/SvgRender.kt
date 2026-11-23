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
 * Portions ported and adapted from resvg, usvg, tiny-skia, and kurbo
 * (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors,
 * Copyright (c) 2011 Google Inc. (Skia / tiny-skia, BSD-3-Clause), and
 * Copyright (c) 2018 The Kurbo Authors / Raph Levien (Apache-2.0 OR MIT),
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import java.nio.ByteBuffer

internal object SvgRender {
    class Ctx(val maxBbox: IRect, val neededBbox: IRect)

    private const val MAX_LAYER_PIXELS = 64L * 1024 * 1024

    fun newBitmap(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_LAYER_PIXELS) return null
        return try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null }
    }

    fun maxFilterBbox(w: Int, h: Int) = IRect(-2 * w, -2 * h, 5 * w, 5 * h)

    fun render(tree: SvgTree, ts: Ts, bitmap: Bitmap) {
        val ctx = Ctx(maxFilterBbox(bitmap.width, bitmap.height), IRect(0, 0, bitmap.width, bitmap.height))
        renderNodes(tree.root, ctx, ts, bitmap)
    }

    fun renderNodes(parent: RGroup, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        for (n in parent.children) renderNode(n, ctx, ts, bitmap)
    }

    fun renderNode(node: RNode, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        when (node) {
            is RGroup -> renderGroup(node, ctx, ts, bitmap)
            is RPathNode -> renderPath(node, null, ctx, ts, bitmap)
            is RImageNode -> renderImage(node, ts, bitmap)
            is RGlyphRun -> renderGlyphs(node, null, ctx, ts, bitmap)
            is RTextNode -> renderNodes(node.flattened, ctx, ts, bitmap)
        }
    }

    fun renderGlyphs(run: RGlyphRun, xfer: PorterDuff.Mode?, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        if (!run.visible) return
        if (run.strokeFirst) { glyphPass(run, true, xfer, ctx, ts, bitmap); glyphPass(run, false, xfer, ctx, ts, bitmap) }
        else { glyphPass(run, false, xfer, ctx, ts, bitmap); glyphPass(run, true, xfer, ctx, ts, bitmap) }
    }

    private fun glyphPass(run: RGlyphRun, stroke: Boolean, xfer: PorterDuff.Mode?, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        val s = if (stroke) run.stroke ?: return else null
        val f = if (!stroke) run.fill ?: return else null
        val keep = ArrayList<Bitmap>()
        val p = Paint()
        if (!shaderFor(s?.paint ?: f!!.paint, s?.opacity ?: f!!.opacity, ctx, ts, p, keep)) return
        p.isAntiAlias = run.antiAlias
        val canvas = Canvas(bitmap)
        val lp = Paint()
        if (xfer != null) lp.xfermode = PorterDuffXfermode(xfer)
        canvas.saveLayer(null, lp)
        val gp = Paint()
        gp.color = Color.BLACK
        gp.isAntiAlias = run.antiAlias
        gp.style = Paint.Style.FILL
        val colorGlyphs = ArrayList<RGlyph>()
        var lastScale = Float.NaN
        for (g in run.glyphs) {
            if (g.color) { colorGlyphs.add(g); continue }
            if (s != null && g.scale != lastScale) {
                lastScale = g.scale
                val k = if (g.scale > 0f && g.scale.isFinite()) g.scale else 1f
                s.applyTo(gp)
                gp.strokeWidth = s.width / k
                gp.pathEffect = s.dash?.let { d -> if (d.size >= 2 && d.all { it.isFinite() } && d.sum() > 0f) DashPathEffect(FloatArray(d.size) { d[it] / k }, s.dashOffset / k) else null }
            }
            drawGlyph(canvas, g, ts, gp)
        }
        canvas.setMatrix(ts.toMatrix())
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawPaint(p)
        p.xfermode = null
        for (g in colorGlyphs) {
            val cp = Paint(g.paint)
            cp.color = p.color
            cp.shader = null
            cp.alpha = Color.alpha(p.color)
            drawGlyph(canvas, g, ts, cp)
        }
        canvas.restore()
        keep.forEach { it.recycle() }
    }

    private fun androidBlend(mode: Int): BlendMode = when (mode) {
        1 -> BlendMode.MULTIPLY; 2 -> BlendMode.SCREEN; 3 -> BlendMode.OVERLAY; 4 -> BlendMode.DARKEN; 5 -> BlendMode.LIGHTEN
        6 -> BlendMode.COLOR_DODGE; 7 -> BlendMode.COLOR_BURN; 8 -> BlendMode.HARD_LIGHT; 9 -> BlendMode.SOFT_LIGHT; 10 -> BlendMode.DIFFERENCE
        11 -> BlendMode.EXCLUSION; 12 -> BlendMode.HUE; 13 -> BlendMode.SATURATION; 14 -> BlendMode.COLOR; 15 -> BlendMode.LUMINOSITY; 16 -> BlendMode.PLUS
        else -> BlendMode.SRC_OVER
    }

    fun blendPaint(mode: Int): Paint = Paint().also { if (mode != 0) it.blendMode = androidBlend(mode) }

    private fun renderGroup(group: RGroup, ctx: Ctx, tsIn: Ts, bitmap: Bitmap) {
        val ts = tsIn.pre(group.transform)
        if (!group.shouldIsolate()) { renderNodes(group, ctx, ts, bitmap); return }
        val rest = if (group.filters.isEmpty()) Ts.IDENTITY else {
            val sx = Math.hypot(ts.sx.toDouble(), ts.ky.toDouble()).toFloat()
            val sy = Math.hypot(ts.kx.toDouble(), ts.sy.toDouble()).toFloat()
            Ts(ts.sx / sx, ts.ky / sx, ts.kx / sy, ts.sy / sy, 0f, 0f)
        }
        val invRest = rest.invert() ?: return
        val layerCtx = Ctx(
            ctx.maxBbox.toRect().transform(invRest)?.roundOut() ?: return,
            ctx.neededBbox.toRect().transform(invRest)?.roundOut() ?: return
        )
        val (layer, ibox) = renderLayer(group, layerCtx, invRest.pre(ts)) ?: return
        val canvas = Canvas(bitmap)
        canvas.setMatrix(rest.toMatrix())
        val paint = blendPaint(group.blendMode)
        paint.isFilterBitmap = true
        paint.isAntiAlias = true
        paint.alpha = Math.round(group.opacity * 255f).coerceIn(0, 255)
        canvas.drawBitmap(layer, ibox.x.toFloat(), ibox.y.toFloat(), paint)
        layer.recycle()
    }

    private fun renderLayer(group: RGroup, ctx: Ctx, tsIn: Ts): Pair<Bitmap, IRect>? {
        val bbox = group.layerBoundingBox()?.transform(tsIn) ?: return null
        var ibox = if (group.filters.isEmpty()) {
            IRect(Math.floor(bbox.x.toDouble()).toInt() - 2, Math.floor(bbox.y.toDouble()).toInt() - 2, Math.ceil(bbox.w.toDouble()).toInt() + 4, Math.ceil(bbox.h.toDouble()).toInt() + 4)
        } else {
            IRect(Math.floor(bbox.x.toDouble()).toInt(), Math.floor(bbox.y.toDouble()).toInt(), maxOf(1.0, Math.ceil(bbox.w.toDouble())).toInt(), maxOf(1.0, Math.ceil(bbox.h.toDouble())).toInt()).fitTo(ctx.maxBbox) ?: return null
        }
        if (group.filters.isEmpty()) ibox = ibox.fitTo(ctx.neededBbox) ?: return null
        val shift = Ts.translate(-ibox.x.toFloat(), -ibox.y.toFloat())
        val ts = shift.pre(tsIn)
        val subCtx = Ctx(ctx.maxBbox.translate(-ibox.x, -ibox.y), IRect(0, 0, ibox.w, ibox.h))
        val sub = newBitmap(ibox.w, ibox.h) ?: return null
        renderNodes(group, subCtx, ts, sub)
        for (f in group.filters) SvgFilters.apply(f, ts, sub)
        group.clipPath?.let { applyClip(it, ts, sub) }
        group.mask?.let { applyMask(it, subCtx, ts, sub) }
        return sub to ibox
    }

    private fun premulColor(r: Int, g: Int, b: Int, a: Float): Int = Color.argb(Math.round(a * 255f).coerceIn(0, 255), r, g, b)

    private fun shaderFor(paint: RPaint, opacity: Float, ctx: Ctx, ts: Ts, out: Paint, keep: MutableList<Bitmap>): Boolean {
        when (paint) {
            is RPaint.Solid -> { out.shader = null; out.color = premulColor(paint.r, paint.g, paint.b, opacity) }
            is RPaint.Linear -> {
                val (colors, pos) = stops(paint.stops, opacity, paint.linearRgb)
                val sh = LinearGradient(paint.x1, paint.y1, paint.x2, paint.y2, colors, pos, tile(paint.spread))
                sh.setLocalMatrix(paint.transform.toMatrix())
                out.shader = sh
                out.color = Color.BLACK
            }
            is RPaint.Radial -> {
                val (colors, pos) = stops(paint.stops, opacity, paint.linearRgb)
                val sh = if (Build.VERSION.SDK_INT >= 31 && (paint.fx != paint.cx || paint.fy != paint.cy || paint.fr > 0f)) {
                    RadialGradient(paint.fx, paint.fy, paint.fr, paint.cx, paint.cy, paint.r, LongArray(colors.size) { Color.pack(colors[it]) }, pos, tile(paint.spread))
                } else RadialGradient(paint.cx, paint.cy, paint.r, colors, pos, tile(paint.spread))
                sh.setLocalMatrix(paint.transform.toMatrix())
                out.shader = sh
                out.color = Color.BLACK
            }
            is RPaint.Pattern -> {
                val (bmp, pts) = renderPattern(paint, ctx, ts) ?: return false
                keep.add(bmp)
                val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                sh.setLocalMatrix(pts.toMatrix())
                out.shader = sh
                out.color = Color.BLACK
                out.alpha = Math.round(opacity * 255f).coerceIn(0, 255)
                out.isFilterBitmap = true
            }
        }
        return true
    }

    private fun tile(spread: Int) = when (spread) { RPaint.SPREAD_REFLECT -> Shader.TileMode.MIRROR; RPaint.SPREAD_REPEAT -> Shader.TileMode.REPEAT; else -> Shader.TileMode.CLAMP }

    private fun stops(list: List<SvgStop>, opacity: Float, linear: Boolean): Pair<IntArray, FloatArray> {
        var pts = list.map { s -> s.offset to floatArrayOf(s.r / 255f, s.g / 255f, s.b / 255f, Math.round(s.opacity * opacity * 255f).coerceIn(0, 255) / 255f) }
        if (linear) pts = linearRgbStops(pts)
        pts = unpremulStops(pts)
        return IntArray(pts.size) { val c = pts[it].second; Color.argb(Math.round(c[3] * 255f), Math.round(c[0] * 255f), Math.round(c[1] * 255f), Math.round(c[2] * 255f)) } to FloatArray(pts.size) { pts[it].first }
    }

    private fun unpremulStops(stops: List<Pair<Float, FloatArray>>): List<Pair<Float, FloatArray>> {
        if (stops.size < 2) return stops
        val out = ArrayList<Pair<Float, FloatArray>>()
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]; val (p1, c1) = stops[i + 1]
            out.add(stops[i])
            if (c0[3] == c1[3] || p1 <= p0 || (c0[0] == c1[0] && c0[1] == c1[1] && c0[2] == c1[2])) continue
            val n = 64
            for (k in 1 until n) {
                val t = k.toFloat() / n
                out.add(p0 + (p1 - p0) * t to FloatArray(4) { c0[it] + (c1[it] - c0[it]) * t })
            }
        }
        out.add(stops.last())
        return out
    }

    private fun linearRgbStops(stops: List<Pair<Float, FloatArray>>): List<Pair<Float, FloatArray>> {
        fun f2l(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        fun l2f(v: Float) = if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()
        fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(4) { a[it] + (b[it] - a[it]) * t }
        fun toLin(c: FloatArray) = floatArrayOf(f2l(c[0]), f2l(c[1]), f2l(c[2]), c[3])
        fun toSrgb(c: FloatArray) = floatArrayOf(l2f(c[0]), l2f(c[1]), l2f(c[2]), c[3])
        val out = ArrayList<Pair<Float, FloatArray>>()
        fun split(a: FloatArray, b: FloatArray, pa: Float, pb: Float, t0: Float, c0: FloatArray, t1: Float, c1: FloatArray, depth: Int) {
            val t = (t0 + t1) / 2f
            val exact = toSrgb(mix(a, b, t))
            val approx = mix(c0, c1, 0.5f)
            val close = (0 until 3).all { Math.abs(exact[it] - approx[it]) <= 0.5f / 255f }
            if (close || depth == 8) return
            split(a, b, pa, pb, t0, c0, t, exact, depth + 1)
            if (exact.all { it.isFinite() && it >= 0f && it <= 1f }) out.add(pa + (pb - pa) * t to exact)
            split(a, b, pa, pb, t, exact, t1, c1, depth + 1)
        }
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]; val (p1, c1) = stops[i + 1]
            out.add(stops[i])
            if (p0 < p1) split(toLin(c0), toLin(c1), p0, p1, 0f, c0, 1f, c1, 0)
        }
        stops.lastOrNull()?.let { out.add(it) }
        return out
    }

    private fun renderPattern(p: RPaint.Pattern, ctx: Ctx, ts: Ts): Pair<Bitmap, Ts>? {
        val sc = ts.pre(p.transform).scaleXY()
        val rect = p.rect
        val iw = Math.round(rect.w * sc[0]); val ih = Math.round(rect.h * sc[1])
        val bmp = newBitmap(iw, ih) ?: return null
        val sx = iw / rect.w; val sy = ih / rect.h
        val sub = Ctx(ctx.maxBbox, IRect(0, 0, iw, ih))
        renderNodes(p.root, sub, Ts.scale(sx, sy), bmp)
        val pts = Ts.IDENTITY.pre(p.transform).preTranslate(rect.x, rect.y).preScale(1f / sx, 1f / sy)
        return bmp to pts
    }

    fun renderPath(path: RPathNode, xfer: PorterDuff.Mode?, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        if (!path.visible) return
        if (path.strokeFirst) { strokePath(path, xfer, ctx, ts, bitmap); fillPath(path, xfer, ctx, ts, bitmap) }
        else { fillPath(path, xfer, ctx, ts, bitmap); strokePath(path, xfer, ctx, ts, bitmap) }
    }

    fun fillPath(path: RPathNode, xfer: PorterDuff.Mode?, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        val fill = path.fill ?: return
        val b = path.data.bounds() ?: return
        if (b.w == 0f || b.h == 0f) return
        val keep = ArrayList<Bitmap>()
        val p = Paint()
        if (!shaderFor(fill.paint, fill.opacity, ctx, ts, p, keep)) return
        p.isAntiAlias = path.antiAlias
        p.style = Paint.Style.FILL
        if (xfer != null) p.xfermode = PorterDuffXfermode(xfer)
        val canvas = Canvas(bitmap)
        canvas.setMatrix(ts.toMatrix())
        canvas.drawPath(path.data.toAndroid(fill.evenOdd), p)
        keep.forEach { it.recycle() }
    }

    private fun strokePath(path: RPathNode, xfer: PorterDuff.Mode?, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        val stroke = path.stroke ?: return
        val keep = ArrayList<Bitmap>()
        val p = Paint()
        if (!shaderFor(stroke.paint, stroke.opacity, ctx, ts, p, keep)) return
        p.isAntiAlias = path.antiAlias
        stroke.applyTo(p)
        stroke.dash?.let { d -> if (d.size >= 2 && d.all { it.isFinite() } && d.sum() > 0f) p.pathEffect = DashPathEffect(d, stroke.dashOffset) }
        if (xfer != null) p.xfermode = PorterDuffXfermode(xfer)
        val canvas = Canvas(bitmap)
        canvas.setMatrix(ts.toMatrix())
        canvas.drawPath(path.data.toAndroid(), p)
        keep.forEach { it.recycle() }
    }

    private fun renderImage(image: RImageNode, ts: Ts, bitmap: Bitmap) {
        if (!image.visible) return
        val svg = image.svg
        if (svg != null) {
            val sub = newBitmap(bitmap.width, bitmap.height) ?: return
            render(svg, ts, sub)
            Canvas(bitmap).drawBitmap(sub, 0f, 0f, null)
            sub.recycle()
            return
        }
        val bmp = image.bitmap ?: return
        val canvas = Canvas(bitmap)
        canvas.setMatrix(ts.toMatrix())
        val p = Paint()
        p.isFilterBitmap = image.smooth
        p.isAntiAlias = true
        canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, image.width, image.height), p)
    }

    fun applyClip(clip: RClip, ts: Ts, bitmap: Bitmap) {
        val cb = newBitmap(bitmap.width, bitmap.height) ?: return
        cb.eraseColor(Color.BLACK)
        drawClipChildren(clip.root, PorterDuff.Mode.CLEAR, ts.pre(clip.transform), cb)
        clip.clipPath?.let { applyClip(it, ts, bitmap) }
        val p = Paint()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        Canvas(bitmap).drawBitmap(cb, 0f, 0f, p)
        cb.recycle()
    }

    private fun drawClipChildren(parent: RGroup, mode: PorterDuff.Mode, ts: Ts, bitmap: Bitmap) {
        val ctx = Ctx(IRect(0, 0, 1, 1), IRect(0, 0, 1, 1))
        for (c in parent.children) {
            when (c) {
                is RPathNode -> if (c.visible) fillPath(c, mode, ctx, ts, bitmap)
                is RGlyphRun -> if (c.visible) clipGlyphs(c, mode, ts, bitmap)
                is RTextNode -> drawClipChildren(c.flattened, mode, ts, bitmap)
                is RGroup -> {
                    val t = ts.pre(c.transform)
                    val cp = c.clipPath
                    if (cp != null) {
                        val tmp = newBitmap(bitmap.width, bitmap.height) ?: continue
                        drawClipChildren(c, PorterDuff.Mode.SRC_OVER, t, tmp)
                        applyClip(cp, t, tmp)
                        val p = Paint()
                        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR)
                        Canvas(bitmap).drawBitmap(tmp, 0f, 0f, p)
                        tmp.recycle()
                    } else drawClipChildren(c, mode, t, bitmap)
                }
                else -> {}
            }
        }
    }

    private fun drawGlyph(canvas: Canvas, g: RGlyph, ts: Ts, gp: Paint) {
        gp.typeface = g.paint.typeface
        gp.textSize = g.paint.textSize
        gp.fontFeatureSettings = g.paint.fontFeatureSettings
        gp.flags = gp.flags or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
        gp.hinting = Paint.HINTING_OFF
        canvas.setMatrix(ts.pre(g.ts).toMatrix())
        val font = g.font
        if (font != null && Build.VERSION.SDK_INT >= 31) {
            canvas.drawGlyphs(intArrayOf(g.id), 0, floatArrayOf(g.x, g.y), 0, 1, font, gp)
        } else {
            canvas.drawTextRun(g.text, g.start, g.end - g.start, g.ctxStart, g.ctxEnd - g.ctxStart, 0f, 0f, g.rtl, gp)
        }
    }

    private fun clipGlyphs(run: RGlyphRun, mode: PorterDuff.Mode, ts: Ts, bitmap: Bitmap) {
        if (run.fill == null) return
        val canvas = Canvas(bitmap)
        val gp = Paint()
        gp.color = Color.BLACK
        gp.isAntiAlias = run.antiAlias
        gp.style = Paint.Style.FILL
        gp.xfermode = PorterDuffXfermode(mode)
        for (g in run.glyphs) drawGlyph(canvas, g, ts, gp)
    }

    fun applyMask(mask: RMask, ctx: Ctx, ts: Ts, bitmap: Bitmap) {
        if (mask.root.children.isEmpty()) { bitmap.eraseColor(Color.TRANSPARENT); return }
        val mb = newBitmap(bitmap.width, bitmap.height) ?: return
        renderNodes(mask.root, ctx, ts, mb)
        val rectMask = newBitmap(bitmap.width, bitmap.height) ?: return
        run {
            val c = Canvas(rectMask)
            c.setMatrix(ts.toMatrix())
            val p = Paint()
            p.isAntiAlias = true
            p.color = Color.BLACK
            c.drawPath(SPath.rect(mask.rect).toAndroid(), p)
            val q = Paint()
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            Canvas(mb).drawBitmap(rectMask, 0f, 0f, q)
        }
        rectMask.recycle()
        mask.mask?.let { applyMask(it, ctx, ts, bitmap) }
        val mdata = Px.bytes(mb)
        mb.recycle()
        if (!mask.alpha && mask.linearRgb) Px.toLinear(mdata)
        val n = bitmap.width * bitmap.height
        val m = ByteArray(n)
        for (i in 0 until n) {
            val o = i * 4
            if (mask.alpha) m[i] = mdata[o + 3] else {
                var r = (mdata[o].toInt() and 0xFF) / 255f
                var g = (mdata[o + 1].toInt() and 0xFF) / 255f
                var b = (mdata[o + 2].toInt() and 0xFF) / 255f
                val ai = mdata[o + 3].toInt() and 0xFF
                val a = ai / 255f
                if (ai != 0) { r /= a; g /= a; b /= a }
                val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
                m[i] = Math.ceil(((luma * a) * 255f).coerceIn(0f, 255f).toDouble()).toInt().toByte()
            }
        }
        val data = Px.bytes(bitmap)
        for (i in 0 until n) {
            val mv = m[i].toInt() and 0xFF
            val o = i * 4
            for (k in 0 until 4) data[o + k] = Px.div255((data[o + k].toInt() and 0xFF) * mv).toByte()
        }
        Px.put(bitmap, data)
    }
}

internal object Px {
    fun bytes(b: Bitmap): ByteArray {
        val buf = ByteBuffer.allocate(b.width * b.height * 4)
        b.copyPixelsToBuffer(buf)
        return buf.array()
    }

    fun put(b: Bitmap, data: ByteArray) {
        b.copyPixelsFromBuffer(ByteBuffer.wrap(data))
    }

    fun div255(v: Int): Int { val t = v + 128; return (t + (t ushr 8)) ushr 8 }

    val SRGB_TO_LINEAR = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 8, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 12, 12, 12, 13,
        13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 17, 18, 18, 19, 19, 20, 20, 21, 22, 22, 23, 23, 24, 24, 25, 25, 26, 27, 27, 28, 29, 29,
        30, 30, 31, 32, 32, 33, 34, 35, 35, 36, 37, 37, 38, 39, 40, 41, 41, 42, 43, 44, 45, 45, 46, 47, 48, 49, 50, 51, 51, 52, 53, 54,
        55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 76, 77, 78, 79, 80, 81, 82, 84, 85, 86, 87, 88,
        90, 91, 92, 93, 95, 96, 97, 99, 100, 101, 103, 104, 105, 107, 108, 109, 111, 112, 114, 115, 116, 118, 119, 121, 122, 124, 125, 127, 128, 130, 131, 133,
        134, 136, 138, 139, 141, 142, 144, 146, 147, 149, 151, 152, 154, 156, 157, 159, 161, 163, 164, 166, 168, 170, 171, 173, 175, 177, 179, 181, 183, 184, 186, 188,
        190, 192, 194, 196, 198, 200, 202, 204, 206, 208, 210, 212, 214, 216, 218, 220, 222, 224, 226, 229, 231, 233, 235, 237, 239, 242, 244, 246, 248, 250, 253, 255
    )

    val LINEAR_TO_SRGB = intArrayOf(
        0, 13, 22, 28, 34, 38, 42, 46, 50, 53, 56, 59, 61, 64, 66, 69, 71, 73, 75, 77, 79, 81, 83, 85, 86, 88, 90, 92, 93, 95, 96, 98,
        99, 101, 102, 104, 105, 106, 108, 109, 110, 112, 113, 114, 115, 117, 118, 119, 120, 121, 122, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136,
        137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 148, 149, 150, 151, 152, 153, 154, 155, 155, 156, 157, 158, 159, 159, 160, 161, 162, 163, 163, 164,
        165, 166, 167, 167, 168, 169, 170, 170, 171, 172, 173, 173, 174, 175, 175, 176, 177, 178, 178, 179, 180, 180, 181, 182, 182, 183, 184, 185, 185, 186, 187, 187,
        188, 189, 189, 190, 190, 191, 192, 192, 193, 194, 194, 195, 196, 196, 197, 197, 198, 199, 199, 200, 200, 201, 202, 202, 203, 203, 204, 205, 205, 206, 206, 207,
        208, 208, 209, 209, 210, 210, 211, 212, 212, 213, 213, 214, 214, 215, 215, 216, 216, 217, 218, 218, 219, 219, 220, 220, 221, 221, 222, 222, 223, 223, 224, 224,
        225, 226, 226, 227, 227, 228, 228, 229, 229, 230, 230, 231, 231, 232, 232, 233, 233, 234, 234, 235, 235, 236, 236, 237, 237, 238, 238, 238, 239, 239, 240, 240,
        241, 241, 242, 242, 243, 243, 244, 244, 245, 245, 246, 246, 246, 247, 247, 248, 248, 249, 249, 250, 250, 251, 251, 251, 252, 252, 253, 253, 254, 254, 255, 255
    )

    private fun u8(v: Float): Int = if (v.isNaN()) 0 else v.toInt().coerceIn(0, 255)

    fun demultiply(d: ByteArray) {
        var i = 0
        while (i < d.size) {
            val a = (d[i + 3].toInt() and 0xFF) / 255f
            for (k in 0 until 3) d[i + k] = u8((d[i + k].toInt() and 0xFF) / a + 0.5f).toByte()
            i += 4
        }
    }

    fun multiply(d: ByteArray) {
        var i = 0
        while (i < d.size) {
            val a = (d[i + 3].toInt() and 0xFF) / 255f
            for (k in 0 until 3) d[i + k] = u8((d[i + k].toInt() and 0xFF) * a + 0.5f).toByte()
            i += 4
        }
    }

    fun toLinear(d: ByteArray) {
        demultiply(d)
        var i = 0
        while (i < d.size) { for (k in 0 until 3) d[i + k] = SRGB_TO_LINEAR[d[i + k].toInt() and 0xFF].toByte(); i += 4 }
        multiply(d)
    }

    fun toSrgb(d: ByteArray) {
        demultiply(d)
        var i = 0
        while (i < d.size) { for (k in 0 until 3) d[i + k] = LINEAR_TO_SRGB[d[i + k].toInt() and 0xFF].toByte(); i += 4 }
        multiply(d)
    }
}
