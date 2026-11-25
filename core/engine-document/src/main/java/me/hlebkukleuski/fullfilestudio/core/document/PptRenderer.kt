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

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class PptImage(val bitmap: Bitmap?, val meta: MetafileImage?) {
    val width: Int get() = bitmap?.width ?: meta?.width ?: 0
    val height: Int get() = bitmap?.height ?: meta?.height ?: 0
    val byteSize: Int get() = bitmap?.allocationByteCount ?: (64 * 1024)
    private var raster: Bitmap? = null

    fun draw(canvas: Canvas, dst: RectF, paint: Paint) {
        val b = bitmap
        if (b != null) {
            canvas.drawBitmap(b, null, dst, paint)
            return
        }
        val m = meta ?: return
        if (paint.colorFilter != null) {
            val ras = asBitmap(2048) ?: return
            canvas.drawBitmap(ras, null, dst, paint)
        } else {
            m.draw(canvas, dst)
        }
    }

    fun asBitmap(maxDim: Int): Bitmap? {
        bitmap?.let { return it }
        raster?.let { if (!it.isRecycled) return it }
        return meta?.rasterize(maxDim)?.also { raster = it }
    }
}

internal class PptSlideRenderer(
    private val pres: PptPresentation,
    private val blipLoader: (Int) -> PptImage?
) {
    private class Ctx(
        val scheme: IntArray,
        val slideIndex: Int,
        val hf: PptHeadersFooters?,
        val scale: Float
    )

    private class Mapping(val sx: Float, val sy: Float, val tx: Float, val ty: Float) {
        fun x(v: Float) = tx + v * sx
        fun y(v: Float) = ty + v * sy
    }

    private val typefaceCache = HashMap<String, Typeface>()

    fun draw(canvas: Canvas, index: Int, left: Float, top: Float, scale: Float) {
        val slide = pres.slides.getOrNull(index) ?: return
        val onTitleMaster = pres.masterOf(slide)?.kind == PptPresentation.KIND_TITLE_MASTER
        val hf = if (pres.omitTitlePlace && onTitleMaster) null else slide.headersFooters ?: pres.documentHeadersFooters
        val ctx = Ctx(pres.schemeFor(slide), index, hf, scale)
        val w = pres.slideWidth * scale
        val h = pres.slideHeight * scale
        val slideRect = RectF(left, top, left + w, top + h)
        canvas.save()
        canvas.clipRect(slideRect)
        drawBackground(canvas, slide, slideRect, ctx)
        val root = Mapping(scale, scale, left, top)
        if (slide.followsMasterObjects) {
            pres.masterOf(slide)?.let { drawMasterObjects(canvas, it, slide, root, ctx, 0) }
        }
        drawShapeList(canvas, slide.shapes, root, ctx, 0)
        canvas.restore()
    }

    private fun drawMasterObjects(canvas: Canvas, master: PptSheet, slide: PptSheet, map: Mapping, ctx: Ctx, depth: Int) {
        if (depth > 4) return
        if (master.kind == PptPresentation.KIND_TITLE_MASTER && master.followsMasterObjects) {
            pres.masterOf(master)?.let { drawMasterObjects(canvas, it, slide, map, ctx, depth + 1) }
        }
        val own = HashSet<Int>()
        collectPlaceholders(slide.shapes, own)
        val hf = ctx.hf
        for (s in master.shapes) {
            when (s.placeholder) {
                0 -> drawShape(canvas, s, map, ctx, 0)
                7 -> if (hf != null && hf.hasDate && 7 !in own) drawShape(canvas, s, map, ctx, 0)
                8 -> if (hf != null && hf.hasSlideNumber && 8 !in own) drawShape(canvas, s, map, ctx, 0)
                9 -> if (hf != null && hf.hasFooter && 9 !in own) drawShape(canvas, s, map, ctx, 0)
            }
        }
    }

    private fun collectPlaceholders(list: List<PptShape>, out: MutableSet<Int>) {
        for (s in list) {
            if (s.placeholder != 0) out.add(s.placeholder)
            collectPlaceholders(s.children, out)
        }
    }

    private fun backgroundShape(sheet: PptSheet): PptShape? {
        var s: PptSheet? = sheet
        var guard = 0
        while (s != null && guard++ < 6) {
            val bg = s.background
            val inherits = s.kind != PptPresentation.KIND_MAIN_MASTER && s.followsMasterBackground
            if (bg != null && !inherits) return bg
            s = pres.masterOf(s)
        }
        return null
    }

    private fun drawBackground(canvas: Canvas, slide: PptSheet, rect: RectF, ctx: Ctx) {
        val bg = backgroundShape(slide)
        val path = Path().apply { addRect(rect, Path.Direction.CW) }
        if (bg == null) {
            canvas.drawColor(Color.WHITE)
            return
        }
        val paint = fillPaint(bg, rect, ctx, true) ?: Paint().apply { color = color(bg.props, 0x181, 0x08000000, ctx, bg) }
        canvas.drawRect(rect, Paint().apply { color = Color.WHITE })
        drawFill(canvas, path, rect, paint, bg, ctx)
    }

    private fun drawShapeList(canvas: Canvas, list: List<PptShape>, map: Mapping, ctx: Ctx, depth: Int) {
        for (s in list) drawShape(canvas, s, map, ctx, depth)
    }

    private fun drawShape(canvas: Canvas, s: PptShape, map: Mapping, ctx: Ctx, depth: Int) {
        if (s.isHidden || depth > 24) return
        val a = s.anchor ?: return
        var rect = RectF(map.x(a[0]), map.y(a[1]), map.x(a[2]), map.y(a[3]))
        rect.sort()
        val rotation = s.props.int(0x0004, 0) / 65536f
        val norm = ((rotation % 360f) + 360f) % 360f
        if ((norm >= 45f && norm < 135f) || (norm >= 225f && norm < 315f)) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val hw = rect.height() / 2f
            val hh = rect.width() / 2f
            rect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        canvas.save()
        if (rotation != 0f) canvas.rotate(rotation, rect.centerX(), rect.centerY())
        if (s.isGroup) {
            if (s.flipH) canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
            if (s.flipV) canvas.scale(1f, -1f, rect.centerX(), rect.centerY())
            val g = s.groupRect
            val child = if (g != null && g[2] != g[0] && g[3] != g[1]) {
                val sx = rect.width() / (g[2] - g[0])
                val sy = rect.height() / (g[3] - g[1])
                Mapping(sx, sy, rect.left - g[0] * sx, rect.top - g[1] * sy)
            } else {
                Mapping(map.sx, map.sy, rect.left - a[0] * map.sx, rect.top - a[1] * map.sy)
            }
            drawShapeList(canvas, s.children, child, ctx, depth + 1)
        } else {
            drawLeaf(canvas, s, rect, ctx)
        }
        canvas.restore()
    }

    private fun drawLeaf(canvas: Canvas, s: PptShape, rect: RectF, ctx: Ctx) {
        val local = PptGeometry.build(s.spt, s.props, rect.width(), rect.height())
        val path = Path(local.path).apply { offset(rect.left, rect.top) }
        canvas.save()
        if (s.flipH) canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
        if (s.flipV) canvas.scale(1f, -1f, rect.centerX(), rect.centerY())
        val isPicture = s.spt == 75
        val filled = local.fillable && s.props.bool(0x01BF, 0x10, !isPicture)
        val lined = s.props.bool(0x01FF, 0x8, !isPicture)
        if (s.props.bool(0x023F, 0x2, false) && (filled || lined)) {
            val dx = s.props.int(0x0205, 25400) / 1587.5f * ctx.scale
            val dy = s.props.int(0x0206, 25400) / 1587.5f * ctx.scale
            val shadowColor = color(s.props, 0x0201, 0x808080, ctx, s)
            val alpha = (s.props.int(0x0204, 65536) / 65536f).coerceIn(0f, 1f)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadowColor
                this.alpha = (alpha * 255).toInt()
                style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = lineWidthPx(s, ctx)
            }
            canvas.save()
            canvas.translate(dx, dy)
            canvas.drawPath(path, sp)
            canvas.restore()
        }
        if (filled) {
            fillPaint(s, rect, ctx, false)?.let { drawFill(canvas, path, rect, it, s, ctx) }
        }
        val pib = s.props.int(0x0104, 0)
        if (pib > 0) drawPicture(canvas, s, pib, rect, path)
        if (lined) {
            val lp = linePaint(s, ctx)
            canvas.drawPath(path, lp)
            if (local.isLine) drawArrows(canvas, s, rect, lp, ctx)
        }
        canvas.restore()
        s.text?.let { body ->
            canvas.save()
            if (s.flipV) canvas.rotate(180f, rect.centerX(), rect.centerY())
            drawText(canvas, body, rect, s.props, ctx)
            canvas.restore()
        }
    }

    private fun drawFill(canvas: Canvas, path: Path, rect: RectF, paint: Paint, s: PptShape, ctx: Ctx) {
        val type = s.props.int(0x0180, 0)
        if (type == 3 || type == 2) {
            val pib = s.props.int(0x0186, 0)
            val img = if (pib > 0) blipLoader(pib) else null
            val bmp = if (type == 2) img?.asBitmap(1024) else null
            if (img != null && (type == 3 || bmp != null)) {
                canvas.save()
                canvas.clipPath(path)
                if (type == 3 || bmp == null) {
                    img.draw(canvas, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                } else {
                    val tile = 6f * ctx.scale
                    val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    shader.setLocalMatrix(Matrix().apply {
                        setScale(tile, tile)
                        postTranslate(rect.left, rect.top)
                    })
                    canvas.drawRect(rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
                }
                canvas.restore()
                return
            }
        }
        if (type == 5 || type == 6) {
            val bmp = rectGradientBitmap(s, ctx)
            if (bmp != null) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
                bmp.recycle()
                return
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun fillPaint(s: PptShape, rect: RectF, ctx: Ctx, background: Boolean): Paint? {
        val p = s.props
        val type = p.int(0x0180, 0)
        val fill = color(p, 0x0181, 0xFFFFFF, ctx, s)
        val opacity = (p.int(0x0182, 65536) / 65536f).coerceIn(0f, 1f)
        val back = color(p, 0x0183, 0xFFFFFF, ctx, s)
        val backOpacity = (p.int(0x0184, 65536) / 65536f).coerceIn(0f, 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        when (type) {
            1 -> {
                val pib = p.int(0x0186, 0)
                val pattern = if (pib > 0) blipLoader(pib)?.asBitmap(64) else null
                if (pattern != null) {
                    val colored = recolorPattern(pattern, fill, back)
                    val tile = 6f * ctx.scale
                    paint.shader = BitmapShader(colored, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                        setLocalMatrix(Matrix().apply {
                            setScale(tile, tile)
                            postTranslate(rect.left, rect.top)
                        })
                    }
                } else {
                    paint.color = blend(fill, back, 0.5f)
                }
            }
            4, 7, 8 -> {
                val stops = gradientStops(p, fill, back, opacity, backOpacity, ctx, s)
                var a = p.int(0x018B, 0) / 65536f
                if (a < 0f) a += 180f
                val theta = Math.toRadians((90f - a).toDouble())
                val dx = cos(theta).toFloat()
                val dy = sin(theta).toFloat()
                val half = (abs(dx) * rect.width() + abs(dy) * rect.height()) / 2f
                val cx = rect.centerX()
                val cy = rect.centerY()
                paint.shader = LinearGradient(
                    cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    stops.first, stops.second, Shader.TileMode.CLAMP
                )
            }
            else -> {
                paint.color = fill
                paint.alpha = (Color.alpha(fill) * opacity).toInt()
            }
        }
        return paint
    }

    private fun gradientStops(
        p: PptProps, fill: Int, back: Int, opacity: Float, backOpacity: Float, ctx: Ctx, s: PptShape
    ): Pair<IntArray, FloatArray> {
        val fillA = withAlpha(fill, opacity)
        val backA = withAlpha(back, backOpacity)
        var base: List<Pair<Int, Float>> = listOf(fillA to 0f, backA to 1f)
        p.blob(0x0197)?.let { arr ->
            val r = PptBytes(arr)
            val n = r.u16(0)
            val cb = r.u16(4).let { if (it == 0xFFF0) 4 else it }
            if (cb == 8 && n in 2..64 && 6 + n * 8 <= arr.size) {
                base = (0 until n).map { i ->
                    val c = colorRef(r.s32(6 + i * 8), ctx, s)
                    c to (r.s32(10 + i * 8) / 65536f).coerceIn(0f, 1f)
                }.sortedBy { it.second }
            }
        }
        var focus = p.int(0x018C, 0)
        if (focus < 0) {
            base = base.reversed().map { it.first to 1f - it.second }
            focus = -focus
        }
        val f = (focus.coerceIn(0, 100)) / 100f
        val out = ArrayList<Pair<Int, Float>>()
        if (f > 0f) for ((c, pos) in base) out.add(c to pos * f)
        if (f < 1f) for ((c, pos) in base.reversed()) out.add(c to f + (1f - pos) * (1f - f))
        val dedup = out.distinctBy { it.second to it.first }
        val colors = ArrayList<Int>()
        val positions = ArrayList<Float>()
        for (k in dedup.indices) {
            if (k > 0) {
                val (c0, p0) = dedup[k - 1]
                val (c1, p1) = dedup[k]
                if (p1 > p0) for (j in 1 until 8) {
                    val t = j / 8f
                    colors.add(linearBlend(c0, c1, t))
                    positions.add(p0 + (p1 - p0) * t)
                }
            }
            colors.add(dedup[k].first)
            positions.add(dedup[k].second)
        }
        return colors.toIntArray() to positions.toFloatArray()
    }

    private fun rectGradientBitmap(s: PptShape, ctx: Ctx): Bitmap? {
        val p = s.props
        val fill = withAlpha(color(p, 0x0181, 0xFFFFFF, ctx, s), (p.int(0x0182, 65536) / 65536f).coerceIn(0f, 1f))
        val back = withAlpha(color(p, 0x0183, 0xFFFFFF, ctx, s), (p.int(0x0184, 65536) / 65536f).coerceIn(0f, 1f))
        val (colors, positions) = gradientStops(p, fill, back, 1f, 1f, ctx, s)
        val l = (p.int(0x018F, 0) / 65536f).coerceIn(0f, 1f)
        val t = (p.int(0x0190, 0) / 65536f).coerceIn(0f, 1f)
        val r = (p.int(0x0191, 0) / 65536f).coerceIn(0f, 1f)
        val b = (p.int(0x0192, 0) / 65536f).coerceIn(0f, 1f)
        val n = 96
        val bmp = try { Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        val px = IntArray(n * n)
        val fx0 = min(l, r)
        val fx1 = max(l, r)
        val fy0 = min(t, b)
        val fy1 = max(t, b)
        for (y in 0 until n) {
            val v = (y + 0.5f) / n
            val dyN = when {
                v < fy0 -> if (fy0 > 0f) (fy0 - v) / fy0 else 0f
                v > fy1 -> if (fy1 < 1f) (v - fy1) / (1f - fy1) else 0f
                else -> 0f
            }
            for (x in 0 until n) {
                val u = (x + 0.5f) / n
                val dxN = when {
                    u < fx0 -> if (fx0 > 0f) (fx0 - u) / fx0 else 0f
                    u > fx1 -> if (fx1 < 1f) (u - fx1) / (1f - fx1) else 0f
                    else -> 0f
                }
                val tt = if (s.props.int(0x0180, 0) == 5) max(dxN, dyN) else sqrt((dxN * dxN + dyN * dyN) / 2f).coerceAtMost(1f)
                px[y * n + x] = sampleStops(colors, positions, tt)
            }
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    private fun sampleStops(colors: IntArray, pos: FloatArray, t: Float): Int {
        if (colors.isEmpty()) return Color.WHITE
        if (t <= pos[0]) return colors[0]
        for (i in 1 until colors.size) {
            if (t <= pos[i]) {
                val span = pos[i] - pos[i - 1]
                val k = if (span <= 0f) 1f else (t - pos[i - 1]) / span
                return linearBlend(colors[i - 1], colors[i], k)
            }
        }
        return colors.last()
    }

    private fun recolorPattern(src: Bitmap, fg: Int, bg: Int): Bitmap {
        val w = src.width.coerceIn(1, 64)
        val h = src.height.coerceIn(1, 64)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src.getPixel(x % src.width, y % src.height)
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            out.setPixel(x, y, if (lum < 128) fg else bg)
        }
        return out
    }

    private fun lineWidthPx(s: PptShape, ctx: Ctx): Float =
        (s.props.int(0x01CB, 9525) / 1587.5f * ctx.scale).coerceAtLeast(0.6f)

    private fun linePaint(s: PptShape, ctx: Ctx): Paint {
        val p = s.props
        val w = lineWidthPx(s, ctx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w
            color = color(p, 0x01C0, 0x000000, ctx, s)
            alpha = (Color.alpha(color) * (p.int(0x01C1, 65536) / 65536f).coerceIn(0f, 1f)).toInt()
            strokeJoin = when (p.int(0x01D6, 2)) {
                0 -> Paint.Join.BEVEL
                1 -> Paint.Join.MITER
                else -> Paint.Join.ROUND
            }
            strokeCap = when (p.int(0x01D7, 2)) {
                0 -> Paint.Cap.ROUND
                1 -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
        }
        val dash = when (p.int(0x01CE, 0)) {
            1 -> floatArrayOf(3f, 1f)
            2 -> floatArrayOf(1f, 1f)
            3 -> floatArrayOf(3f, 1f, 1f, 1f)
            4 -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
            5 -> floatArrayOf(1f, 3f)
            6 -> floatArrayOf(4f, 3f)
            7 -> floatArrayOf(8f, 3f)
            8 -> floatArrayOf(4f, 3f, 1f, 3f)
            9 -> floatArrayOf(8f, 3f, 1f, 3f)
            10 -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
            else -> null
        }
        if (dash != null) paint.pathEffect = DashPathEffect(dash.map { it * w }.toFloatArray(), 0f)
        return paint
    }

    private fun drawArrows(canvas: Canvas, s: PptShape, rect: RectF, lp: Paint, ctx: Ctx) {
        val start = s.props.int(0x01D0, 0)
        val end = s.props.int(0x01D1, 0)
        if (start == 0 && end == 0) return
        val w = lp.strokeWidth
        val fp = Paint(lp).apply {
            style = Paint.Style.FILL_AND_STROKE
            pathEffect = null
        }
        fun head(type: Int, tipX: Float, tipY: Float, fromX: Float, fromY: Float, widthProp: Int, lengthProp: Int) {
            if (type == 0) return
            val ang = atan2(tipY - fromY, tipX - fromX)
            val wf = floatArrayOf(2f, 3f, 5f)[s.props.int(widthProp, 1).coerceIn(0, 2)]
            val lf = floatArrayOf(2f, 3f, 5f)[s.props.int(lengthProp, 1).coerceIn(0, 2)]
            val len = max(w, 1f) * lf
            val half = max(w, 1f) * wf / 2f
            val bx = tipX - cos(ang) * len
            val by = tipY - sin(ang) * len
            val nx = -sin(ang)
            val ny = cos(ang)
            val path = Path()
            when (type) {
                4 -> path.addOval(RectF(tipX - half, tipY - half, tipX + half, tipY + half), Path.Direction.CW)
                3 -> {
                    path.moveTo(tipX, tipY)
                    path.lineTo((tipX + bx) / 2f + nx * half, (tipY + by) / 2f + ny * half)
                    path.lineTo(bx, by)
                    path.lineTo((tipX + bx) / 2f - nx * half, (tipY + by) / 2f - ny * half)
                    path.close()
                }
                else -> {
                    path.moveTo(tipX, tipY)
                    path.lineTo(bx + nx * half, by + ny * half)
                    if (type == 2) path.lineTo(tipX - cos(ang) * len * 0.6f, tipY - sin(ang) * len * 0.6f)
                    path.lineTo(bx - nx * half, by - ny * half)
                    if (type != 5) path.close()
                }
            }
            canvas.drawPath(path, if (type == 5) lp else fp)
        }
        head(start, rect.left, rect.top, rect.right, rect.bottom, 0x01D2, 0x01D3)
        head(end, rect.right, rect.bottom, rect.left, rect.top, 0x01D4, 0x01D5)
    }

    private fun drawPicture(canvas: Canvas, s: PptShape, pib: Int, rect: RectF, path: Path) {
        val img = blipLoader(pib) ?: return
        val p = s.props
        val ct = p.int(0x0100, 0) / 65536f
        val cb = p.int(0x0101, 0) / 65536f
        val cl = p.int(0x0102, 0) / 65536f
        val cr = p.int(0x0103, 0) / 65536f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val grey = p.bool(0x013F, 0x4, false)
        val bilevel = p.bool(0x013F, 0x2, false)
        if (grey || bilevel) {
            val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            if (bilevel) cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                40f, 0f, 0f, 0f, -5100f, 0f, 40f, 0f, 0f, -5100f, 0f, 0f, 40f, 0f, -5100f, 0f, 0f, 0f, 1f, 0f
            )))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        canvas.save()
        canvas.clipPath(path)
        val visW = (1f - cl - cr).takeIf { abs(it) > 1e-4f } ?: 1f
        val visH = (1f - ct - cb).takeIf { abs(it) > 1e-4f } ?: 1f
        val fullW = rect.width() / visW
        val fullH = rect.height() / visH
        val dst = RectF(rect.left - cl * fullW, rect.top - ct * fullH, rect.left - cl * fullW + fullW, rect.top - ct * fullH + fullH)
        img.draw(canvas, dst, paint)
        canvas.restore()
    }

    private fun colorRef(raw: Int, ctx: Ctx, s: PptShape?, depth: Int = 0): Int {
        val flags = (raw ushr 24) and 0xFF
        val r = raw and 0xFF
        val g = (raw ushr 8) and 0xFF
        val b = (raw ushr 16) and 0xFF
        if (flags and 0x08 != 0) {
            return 0xFF000000.toInt() or (ctx.scheme.getOrElse(r) { 0 } and 0xFFFFFF)
        }
        if (flags and 0x10 != 0) {
            val idx = r or (g shl 8)
            val baseIdx = idx and 0xFF
            var c = if (s != null && depth < 3) when (baseIdx) {
                0xF0 -> color(s.props, 0x0181, 0xFFFFFF, ctx, s, depth + 1)
                0xF1 -> if (s.props.bool(0x01FF, 0x8, true)) color(s.props, 0x01C0, 0, ctx, s, depth + 1) else color(s.props, 0x0181, 0xFFFFFF, ctx, s, depth + 1)
                0xF2 -> color(s.props, 0x01C0, 0, ctx, s, depth + 1)
                0xF3 -> color(s.props, 0x0201, 0x808080, ctx, s, depth + 1)
                0xF5 -> color(s.props, 0x0183, 0xFFFFFF, ctx, s, depth + 1)
                0xF6 -> color(s.props, 0x01C2, 0xFFFFFF, ctx, s, depth + 1)
                0xF7 -> if (s.props.bool(0x01BF, 0x10, true)) color(s.props, 0x0181, 0xFFFFFF, ctx, s, depth + 1) else color(s.props, 0x01C0, 0, ctx, s, depth + 1)
                else -> systemColor(baseIdx)
            } else systemColor(baseIdx)
            val mod = (idx ushr 8) and 0xFF
            if (mod and 0x80 != 0) {
                val gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                c = Color.rgb(gray, gray, gray)
            }
            fun ch(f: (Int) -> Int) {
                c = Color.argb(Color.alpha(c), f(Color.red(c)).coerceIn(0, 255), f(Color.green(c)).coerceIn(0, 255), f(Color.blue(c)).coerceIn(0, 255))
            }
            when (mod and 0x0F) {
                1 -> ch { it * b / 255 }
                2 -> ch { 255 - (255 - it) * b / 255 }
                3 -> ch { it + b }
                4 -> ch { it - b }
                5 -> ch { b - it }
                6 -> ch { if (it < b) 0 else 255 }
            }
            if (mod and 0x20 != 0) ch { 255 - it }
            if (mod and 0x40 != 0) ch { it xor 0x80 }
            return c
        }
        return Color.rgb(r, g, b)
    }

    private fun color(p: PptProps, id: Int, def: Int, ctx: Ctx, s: PptShape?, depth: Int = 0): Int {
        val raw = p.raw(id) ?: return if (def == 0x08000000) colorRef(def, ctx, null) else 0xFF000000.toInt() or def
        return colorRef(raw, ctx, s, depth)
    }

    private fun systemColor(i: Int): Int = when (i) {
        0, 1, 3, 7, 8, 9, 13, 18 -> Color.BLACK
        2, 5, 14, 15, 23 -> Color.WHITE
        4, 10, 11, 12 -> Color.rgb(0xF0, 0xF0, 0xF0)
        6, 16, 17 -> Color.GRAY
        else -> Color.BLACK
    }

    private fun textColor(raw: Int, ctx: Ctx): Int {
        val index = (raw ushr 24) and 0xFF
        return when {
            index == 0xFE -> Color.rgb(raw and 0xFF, (raw ushr 8) and 0xFF, (raw ushr 16) and 0xFF)
            index < 8 -> 0xFF000000.toInt() or ctx.scheme[index]
            else -> 0xFF000000.toInt() or ctx.scheme[1]
        }
    }

    private fun withAlpha(c: Int, a: Float): Int = Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun linearBlend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        DmlColors.toSrgb(DmlColors.toLinear(Color.red(a)) + (DmlColors.toLinear(Color.red(b)) - DmlColors.toLinear(Color.red(a))) * t),
        DmlColors.toSrgb(DmlColors.toLinear(Color.green(a)) + (DmlColors.toLinear(Color.green(b)) - DmlColors.toLinear(Color.green(a))) * t),
        DmlColors.toSrgb(DmlColors.toLinear(Color.blue(a)) + (DmlColors.toLinear(Color.blue(b)) - DmlColors.toLinear(Color.blue(a))) * t)
    )

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun fontName(ref: Int?): PptFont? = ref?.let { pres.fonts.getOrNull(it) }

    private fun typefaceFor(font: PptFont?, bold: Boolean, italic: Boolean): Typeface {
        val family = PptFonts.androidFamily(font)
        val key = "${font?.name}|$family|$bold|$italic"
        return typefaceCache.getOrPut(key) {
            OfficeFonts.typeface(font?.name, bold, italic)?.let { return@getOrPut it }
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(family, style)
        }
    }

    private class RunSpan(
        val sizePx: Float,
        val typeface: Typeface,
        val color: Int,
        val underline: Boolean,
        val shadow: Boolean,
        val baselineShift: Int
    ) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            apply(tp)
            tp.color = color
            tp.isUnderlineText = underline
            if (shadow) tp.setShadowLayer(max(1f, sizePx * 0.03f), sizePx * 0.06f, sizePx * 0.06f, Color.argb(160, 128, 128, 128))
        }

        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        private fun apply(tp: TextPaint) {
            tp.textSize = sizePx
            tp.typeface = typeface
            tp.baselineShift += baselineShift
        }
    }

    private class LineSpacingSpan(val factor: Float, val exactPx: Float) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: android.graphics.Paint.FontMetricsInt) {
            val h = fm.descent - fm.ascent
            if (h <= 0) return
            val target = if (exactPx > 0f) exactPx else h * factor
            val extra = (target - h).toInt()
            fm.ascent -= extra
            fm.top = min(fm.top, fm.ascent)
            if (extra < 0) fm.top = fm.ascent
        }
    }

    private fun substituteField(run: PptTextRun, ctx: Ctx): String = when (run.field) {
        PptPresentation.FIELD_SLIDE_NUMBER -> (pres.firstSlideNumber + ctx.slideIndex).toString()
        PptPresentation.FIELD_FOOTER -> ctx.hf?.footer ?: ""
        PptPresentation.FIELD_HEADER -> ctx.hf?.header ?: ""
        PptPresentation.FIELD_DATE -> dateText(ctx.hf)
        else -> run.text
    }

    private fun dateText(hf: PptHeadersFooters?): String {
        if (hf != null && hf.hasUserDate && !hf.hasTodayDate) return hf.userDate.orEmpty()
        val pattern = when (hf?.formatId ?: 0) {
            1 -> "EEEE, MMMM d, yyyy"
            2 -> "d MMMM yyyy"
            3 -> "MMMM d, yyyy"
            4 -> "d-MMM-yy"
            5 -> "MMMM yy"
            6 -> "MMM-yy"
            7 -> "M/d/yyyy h:mm a"
            8 -> "M/d/yyyy h:mm:ss a"
            9 -> "H:mm"
            10 -> "H:mm:ss"
            11 -> "h:mm a"
            12 -> "h:mm:ss a"
            else -> "M/d/yyyy"
        }
        return SimpleDateFormat(pattern, Locale.US).format(Date())
    }

    private class LaidParagraph(
        val layout: StaticLayout,
        val para: PptParagraph,
        val before: Float,
        val after: Float,
        val bulletText: String?,
        val bulletPaint: TextPaint?,
        val bulletX: Float
    )

    private fun drawText(canvas: Canvas, body: PptTextBody, rect: RectF, props: PptProps, ctx: Ctx) {
        if (body.paragraphs.all { p -> p.runs.all { it.text.isEmpty() && it.field == PptPresentation.FIELD_NONE } }) return
        val sc = ctx.scale
        val il = props.int(0x0081, 91440) / 1587.5f * sc
        val it = props.int(0x0082, 45720) / 1587.5f * sc
        val ir = props.int(0x0083, 91440) / 1587.5f * sc
        val ib = props.int(0x0084, 45720) / 1587.5f * sc
        val flow = props.int(0x0088, 0)
        val vertical = flow == 1 || flow == 2 || flow == 5
        canvas.save()
        var box = RectF(rect.left + il, rect.top + it, rect.right - ir, rect.bottom - ib)
        if (vertical) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.rotate(if (flow == 2) -90f else 90f, cx, cy)
            val w = rect.height()
            val h = rect.width()
            box = RectF(cx - w / 2f + it, cy - h / 2f + il, cx + w / 2f - ib, cy + h / 2f - ir)
        }
        val wrap = props.int(0x0085, 0) != 2
        val width = max(1f, box.width())
        val laid = ArrayList<LaidParagraph>()
        var total = 0f
        body.paragraphs.forEachIndexed { idx, para ->
            val lp = layoutParagraph(para, if (wrap) width else 100_000f, ctx, idx == 0)
            laid.add(lp)
            total += lp.before + lp.layout.height + lp.after
        }
        laid.lastOrNull()?.let { total -= it.after }
        val anchor = props.int(0x0087, 0)
        var y = when (anchor) {
            1, 4 -> box.top + (box.height() - total) / 2f
            2, 5, 7, 9 -> box.bottom - total
            else -> box.top
        }
        for ((i, lp) in laid.withIndex()) {
            y += lp.before
            val layout = lp.layout
            var x = box.left
            if (!wrap) {
                var maxW = 0f
                for (l in 0 until layout.lineCount) maxW = max(maxW, layout.getLineWidth(l) + lp.para.leftMargin * sc)
                x = when (lp.para.align) {
                    1 -> box.centerX() - 50_000f
                    2 -> box.right - 100_000f
                    else -> box.left
                }
            }
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            if (lp.bulletText != null && lp.bulletPaint != null && layout.lineCount > 0) {
                val base = layout.getLineBaseline(0).toFloat()
                val textStart = if (lp.para.align == 0 || lp.para.align >= 3) lp.bulletX else layout.getLineLeft(0) - lp.bulletPaint.measureText(lp.bulletText) * 1.4f
                canvas.drawText(lp.bulletText, textStart, base, lp.bulletPaint)
            }
            canvas.restore()
            y += layout.height + if (i < laid.size - 1) lp.after else 0f
        }
        canvas.restore()
    }

    private fun layoutParagraph(para: PptParagraph, width: Float, ctx: Ctx, first: Boolean): LaidParagraph {
        val sc = ctx.scale
        val sb = SpannableStringBuilder()
        var maxSizePx = 0f
        var firstStyle: PptRunStyle? = null
        for (run in para.runs) {
            var text = substituteField(run, ctx).replace('\u000B', '\n')
            val st = run.style
            if (firstStyle == null && (text.isNotEmpty() || para.runs.size == 1)) firstStyle = st
            val symbolFont = st.symbolFontRef?.let { fontName(it) }
            val font = fontName(st.fontRef)
            text = PptFonts.mapSymbols(text, font)
            var sizePx = st.sizePt * 8f * sc
            maxSizePx = max(maxSizePx, sizePx)
            var shift = 0
            if (st.position != 0) {
                shift = -(st.position / 100f * sizePx).toInt()
                sizePx *= 0.67f
            }
            if (text.isEmpty()) continue
            val start = sb.length
            sb.append(text)
            sb.setSpan(
                RunSpan(sizePx, typefaceFor(font ?: symbolFont, st.bold, st.italic), textColor(st.color, ctx), st.underline, st.shadow, shift),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val fs = firstStyle ?: para.runs.firstOrNull()?.style
        if (maxSizePx <= 0f) maxSizePx = (fs?.sizePt ?: 18f) * 8f * sc
        val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = maxSizePx
            typeface = typefaceFor(fontName(fs?.fontRef), fs?.bold ?: false, fs?.italic ?: false)
            color = textColor(fs?.color ?: (1 shl 24), ctx)
        }
        val indentPx = para.indent * sc
        val leftPx = para.leftMargin * sc
        var bulletText: String? = null
        var bulletPaint: TextPaint? = null
        var firstLine = indentPx
        val hasText = sb.isNotEmpty()
        if (para.hasBullet && hasText) {
            val bFont = para.bulletFontRef?.let { fontName(it) } ?: fontName(fs?.fontRef)
            val ch = para.bulletChar.takeIf { it != 0 } ?: 0x2022
            bulletText = PptFonts.mapSymbols(String(Character.toChars(ch)), bFont)
            val glyphScale = if (PptFonts.isSymbolFont(bFont)) 1.25f else 1f
            val bSize = glyphScale * when {
                para.bulletSize > 0 -> maxSizePx * para.bulletSize / 100f
                para.bulletSize < 0 -> -para.bulletSize * 8f * sc
                else -> maxSizePx
            }
            bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = bSize
                typeface = typefaceFor(if (PptFonts.isSymbolFont(bFont)) null else bFont, false, false)
                color = para.bulletColor?.let { textColor(it, ctx) } ?: basePaint.color.let { c ->
                    fs?.let { textColor(it.color, ctx) } ?: c
                }
            }
            firstLine = max(leftPx, indentPx + bulletPaint.measureText(bulletText) * 1.25f)
        }
        val text: CharSequence = if (hasText) sb else " "
        val sp = SpannableStringBuilder(text)
        val nl = sp.indexOf('\n')
        if (nl < 0) {
            sp.setSpan(LeadingMarginSpan.Standard(firstLine.toInt(), leftPx.toInt()), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } else {
            sp.setSpan(LeadingMarginSpan.Standard(firstLine.toInt(), leftPx.toInt()), 0, nl + 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            sp.setSpan(LeadingMarginSpan.Standard(leftPx.toInt(), leftPx.toInt()), nl + 1, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val ls = para.lineSpacing
        val exact = if (ls < 0) -ls * sc else maxSizePx * PptFonts.LINE_FACTOR * (if (ls > 0) ls else 100) / 100f
        sp.setSpan(LineSpacingSpan(1f, exact), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val alignment = when (para.align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val w = max(1, width.toInt())
        val builder = StaticLayout.Builder.obtain(sp, 0, sp.length, basePaint, w)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
        if (para.align == 3 || para.align == 4 || para.align == 6) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        val layout = builder.build()
        val lineH = maxSizePx * PptFonts.LINE_FACTOR
        fun spacing(v: Int): Float = if (v >= 0) v / 100f * lineH else -v * sc
        return LaidParagraph(
            layout, para,
            if (first) 0f else spacing(para.spaceBefore),
            spacing(para.spaceAfter),
            bulletText, bulletPaint, indentPx
        )
    }
}

internal object PptFonts {
    const val LINE_FACTOR = 1.2f

    private val SERIF = listOf("times", "georgia", "garamond", "book antiqua", "cambria", "palatino", "bookman", "century", "baskerville", "minion", "serif")
    private val MONO = listOf("courier", "consolas", "lucida console", "monaco", "menlo", "mono")
    private val CONDENSED = listOf("narrow", "condensed", "impact")

    fun isSymbolFont(font: PptFont?): Boolean {
        val n = font?.name?.lowercase(Locale.ROOT) ?: return false
        return n.startsWith("wingdings") || n == "symbol" || n.startsWith("webdings") || n.contains("monotype sorts")
    }

    fun androidFamily(font: PptFont?): String {
        val n = font?.name?.lowercase(Locale.ROOT) ?: return "sans-serif"
        return when {
            MONO.any { n.contains(it) } -> "monospace"
            SERIF.any { n.contains(it) } -> "serif"
            CONDENSED.any { n.contains(it) } -> "sans-serif-condensed"
            n.contains("comic") -> "casual"
            n.contains("script") || n.contains("brush") -> "cursive"
            font.family == 0x10 -> "serif"
            font.family == 0x30 -> "monospace"
            else -> "sans-serif"
        }
    }

    fun mapSymbols(text: String, font: PptFont?): String {
        val n = font?.name?.lowercase(Locale.ROOT) ?: ""
        val table: Map<Int, String>? = when {
            n == "symbol" -> SYMBOL
            n.startsWith("wingdings") || n.contains("monotype sorts") -> WINGDINGS
            else -> null
        }
        var needs = false
        for (c in text) if (c.code in 0xF020..0xF0FF || (table != null && c.code in 0x20..0xFF)) { needs = true; break }
        if (!needs) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            var code = c.code
            if (code in 0xF020..0xF0FF) code -= 0xF000
            val mapped = if (table != null && code in 0x20..0xFF) table[code] else null
            if (mapped != null) sb.append(mapped) else sb.append(code.toChar())
        }
        return sb.toString()
    }

    private val SYMBOL: Map<Int, String> = buildMap {
        val upper = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ"
        upper.forEachIndexed { i, ch -> put(0x41 + i, ch.toString()) }
        val lower = "αβχδεφγηιϕκλμνοπθρστυϖωξψζ"
        lower.forEachIndexed { i, ch -> put(0x61 + i, ch.toString()) }
        put(0x22, "∀"); put(0x24, "∃"); put(0x27, "∋"); put(0x2A, "∗"); put(0x2D, "−"); put(0x40, "≅")
        put(0x5C, "∴"); put(0x5E, "⊥"); put(0x60, "‾"); put(0x7E, "∼"); put(0xA0, "€"); put(0xA1, "ϒ")
        put(0xA2, "′"); put(0xA3, "≤"); put(0xA4, "⁄"); put(0xA5, "∞"); put(0xA6, "ƒ"); put(0xA7, "♣")
        put(0xA8, "♦"); put(0xA9, "♥"); put(0xAA, "♠"); put(0xAB, "↔"); put(0xAC, "←"); put(0xAD, "↑")
        put(0xAE, "→"); put(0xAF, "↓"); put(0xB0, "°"); put(0xB1, "±"); put(0xB2, "″"); put(0xB3, "≥")
        put(0xB4, "×"); put(0xB5, "∝"); put(0xB6, "∂"); put(0xB7, "•"); put(0xB8, "÷"); put(0xB9, "≠")
        put(0xBA, "≡"); put(0xBB, "≈"); put(0xBC, "…"); put(0xBF, "↵"); put(0xC0, "ℵ"); put(0xC1, "ℑ")
        put(0xC2, "ℜ"); put(0xC3, "℘"); put(0xC4, "⊗"); put(0xC5, "⊕"); put(0xC6, "∅"); put(0xC7, "∩")
        put(0xC8, "∪"); put(0xC9, "⊃"); put(0xCA, "⊇"); put(0xCB, "⊄"); put(0xCC, "⊂"); put(0xCD, "⊆")
        put(0xCE, "∈"); put(0xCF, "∉"); put(0xD0, "∠"); put(0xD1, "∇"); put(0xD2, "®"); put(0xD3, "©")
        put(0xD4, "™"); put(0xD5, "∏"); put(0xD6, "√"); put(0xD7, "⋅"); put(0xD8, "¬"); put(0xD9, "∧")
        put(0xDA, "∨"); put(0xDB, "⇔"); put(0xDC, "⇐"); put(0xDD, "⇑"); put(0xDE, "⇒"); put(0xDF, "⇓")
        put(0xE0, "◊"); put(0xE1, "〈"); put(0xE5, "∑"); put(0xF1, "〉"); put(0xF2, "∫")
    }

    private val WINGDINGS: Map<Int, String> = mapOf(
        0x21 to "✏", 0x22 to "✂", 0x23 to "✁", 0x28 to "☎", 0x2A to "✉", 0x36 to "⌛", 0x37 to "⌨",
        0x3E to "✇", 0x3F to "✍", 0x41 to "✌", 0x42 to "👌", 0x43 to "👍", 0x44 to "👎", 0x45 to "☜",
        0x46 to "☞", 0x47 to "☝", 0x48 to "☟", 0x4A to "☺", 0x4B to "😐", 0x4C to "☹", 0x4E to "☠",
        0x4F to "⚐", 0x51 to "✈", 0x52 to "☼", 0x54 to "❄", 0x56 to "✞", 0x58 to "✠", 0x59 to "✡",
        0x5A to "☪", 0x5B to "☯", 0x5C to "ॐ", 0x5D to "☸", 0x5E to "♈", 0x6C to "●", 0x6D to "❍",
        0x6E to "■", 0x6F to "□", 0x70 to "◻", 0x71 to "❑", 0x72 to "❒", 0x73 to "⬧", 0x74 to "⧫",
        0x75 to "◆", 0x76 to "❖", 0x77 to "⬥", 0x78 to "⌧", 0x79 to "⍓", 0x7A to "⌘", 0x7B to "❀",
        0x7C to "✿", 0x7D to "❝", 0x7E to "❞", 0x9E to "·", 0x9F to "•", 0xA0 to "▪", 0xA1 to "○",
        0xA2 to "⭕", 0xA4 to "◉", 0xA5 to "◎", 0xA7 to "▪", 0xA8 to "◻", 0xAA to "✦", 0xAB to "★",
        0xAC to "✶", 0xAD to "✴", 0xAE to "✹", 0xAF to "✵", 0xB1 to "⌖", 0xB2 to "⟡", 0xB3 to "⌑",
        0xB5 to "✪", 0xB6 to "✰", 0xD5 to "⌫", 0xD6 to "⌦", 0xD8 to "➢", 0xDF to "←", 0xE0 to "→",
        0xE1 to "↑", 0xE2 to "↓", 0xE8 to "➔", 0xEF to "⇦", 0xF0 to "⇨", 0xF1 to "⇧", 0xF2 to "⇩",
        0xF3 to "⬄", 0xF4 to "⇳", 0xFB to "✗", 0xFC to "✓", 0xFD to "☒", 0xFE to "☑"
    )
}

internal object PptGeometry {
    class Result(val path: Path, val fillable: Boolean, val isLine: Boolean)

    private fun adj(p: PptProps, i: Int, def: Int): Float = p.int(0x0147 + i, def).toFloat()

    fun build(spt: Int, p: PptProps, w: Float, h: Float): Result {
        custom(p, w, h)?.let { return it }
        val path = Path()
        val ss = min(w, h)
        fun poly(vararg pts: Float) {
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) {
                path.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            path.close()
        }
        fun ax(v: Float) = v / 21600f * w
        fun ay(v: Float) = v / 21600f * h
        when (spt) {
            20, 32 -> {
                path.moveTo(0f, 0f)
                path.lineTo(w, h)
                return Result(path, false, true)
            }
            33 -> {
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.lineTo(w, h)
                return Result(path, false, true)
            }
            34, 35, 36 -> {
                val x = ax(adj(p, 0, 10800))
                path.moveTo(0f, 0f)
                path.lineTo(x, 0f)
                path.lineTo(x, h)
                path.lineTo(w, h)
                return Result(path, false, true)
            }
            37, 38, 39, 40 -> {
                val x = ax(adj(p, 0, 10800))
                path.moveTo(0f, 0f)
                path.cubicTo(x, 0f, x, h, w, h)
                return Result(path, false, true)
            }
            2 -> {
                val r = adj(p, 0, 3600) / 21600f * ss
                path.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            3, 120 -> path.addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            4, 110 -> poly(w / 2f, 0f, w, h / 2f, w / 2f, h, 0f, h / 2f)
            5, 127 -> poly(ax(adj(p, 0, 10800)), 0f, w, h, 0f, h)
            6 -> poly(0f, 0f, w, h, 0f, h)
            7, 111 -> {
                val d = ax(adj(p, 0, 5400))
                poly(d, 0f, w, 0f, w - d, h, 0f, h)
            }
            8 -> {
                val d = ax(adj(p, 0, 5400))
                poly(0f, 0f, w, 0f, w - d, h, d, h)
            }
            9 -> {
                val d = ax(adj(p, 0, 5400))
                poly(d, 0f, w - d, 0f, w, h / 2f, w - d, h, d, h, 0f, h / 2f)
            }
            10 -> {
                val d = adj(p, 0, 6326) / 21600f * ss
                poly(d, 0f, w - d, 0f, w, d, w, h - d, w - d, h, d, h, 0f, h - d, 0f, d)
            }
            11 -> {
                val d = adj(p, 0, 5400) / 21600f
                val dx = d * w
                val dy = d * h
                poly(dx, 0f, w - dx, 0f, w - dx, dy, w, dy, w, h - dy, w - dx, h - dy, w - dx, h, dx, h, dx, h - dy, 0f, h - dy, 0f, dy, dx, dy)
            }
            12, 92, 187 -> star(path, w, h, if (spt == 92) 24 else 5, if (spt == 12) 0.382f else 0.5f)
            13 -> {
                val x = ax(adj(p, 0, 16200))
                val y = ay(adj(p, 1, 5400))
                poly(0f, y, x, y, x, 0f, w, h / 2f, x, h, x, h - y, 0f, h - y)
            }
            66 -> {
                val x = ax(adj(p, 0, 5400))
                val y = ay(adj(p, 1, 5400))
                poly(w, y, x, y, x, 0f, 0f, h / 2f, x, h, x, h - y, w, h - y)
            }
            67 -> {
                val y = ay(adj(p, 0, 16200))
                val x = ax(adj(p, 1, 5400))
                poly(x, 0f, w - x, 0f, w - x, y, w, y, w / 2f, h, 0f, y, x, y)
            }
            68 -> {
                val y = ay(adj(p, 0, 5400))
                val x = ax(adj(p, 1, 5400))
                poly(w / 2f, 0f, w, y, w - x, y, w - x, h, x, h, x, y, 0f, y)
            }
            69 -> {
                val x = ax(adj(p, 0, 4320))
                val y = ay(adj(p, 1, 5400))
                poly(0f, h / 2f, x, 0f, x, y, w - x, y, w - x, 0f, w, h / 2f, w - x, h, w - x, h - y, x, h - y, x, h)
            }
            15 -> {
                val x = ax(adj(p, 0, 16200))
                poly(0f, 0f, x, 0f, w, h / 2f, x, h, 0f, h)
            }
            55 -> {
                val x = ax(adj(p, 0, 16200))
                poly(0f, 0f, x, 0f, w, h / 2f, x, h, 0f, h, w - x, h / 2f)
            }
            56 -> poly(w / 2f, 0f, w, h * 0.382f, w * 0.81f, h, w * 0.19f, h, 0f, h * 0.382f)
            16 -> {
                val d = adj(p, 0, 5400) / 21600f * ss
                poly(0f, d, d, 0f, w, 0f, w, h - d, w - d, h, 0f, h)
                path.moveTo(0f, d)
                path.lineTo(w - d, d)
                path.lineTo(w, 0f)
                path.moveTo(w - d, d)
                path.lineTo(w - d, h)
            }
            22 -> {
                val d = adj(p, 0, 5400) / 21600f * h
                path.addOval(RectF(0f, 0f, w, d * 2f), Path.Direction.CW)
                path.moveTo(0f, d)
                path.lineTo(0f, h - d)
                path.arcTo(RectF(0f, h - 2f * d, w, h), 180f, -180f)
                path.lineTo(w, d)
            }
            21 -> {
                val d = adj(p, 0, 3600) / 21600f * ss
                path.moveTo(d, 0f)
                path.lineTo(w - d, 0f)
                path.arcTo(RectF(w - d, -d, w + d, d), 180f, -90f)
                path.lineTo(w, h - d)
                path.arcTo(RectF(w - d, h - d, w + d, h + d), 270f, -90f)
                path.lineTo(d, h)
                path.arcTo(RectF(-d, h - d, d, h + d), 0f, -90f)
                path.lineTo(0f, d)
                path.arcTo(RectF(-d, -d, d, d), 90f, -90f)
                path.close()
            }
            65 -> {
                val d = ay(adj(p, 0, 18900)).let { h - it }.coerceAtLeast(0f).let { min(it, ss) }
                poly(0f, 0f, w, 0f, w, h - d, w - d, h, 0f, h)
            }
            84 -> {
                path.addRect(RectF(0f, 0f, w, h), Path.Direction.CW)
                val d = adj(p, 0, 2700) / 21600f * ss
                path.addRect(RectF(d, d, w - d, h - d), Path.Direction.CW)
            }
            114 -> {
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.lineTo(w, h * 0.83f)
                path.cubicTo(w * 0.75f, h * 0.7f, w * 0.5f, h * 1.05f, 0f, h * 0.9f)
                path.close()
            }
            116 -> {
                val r = min(h / 2f, w / 2f)
                path.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            112 -> {
                path.addRect(RectF(0f, 0f, w, h), Path.Direction.CW)
                path.moveTo(w * 0.125f, 0f)
                path.lineTo(w * 0.125f, h)
                path.moveTo(w * 0.875f, 0f)
                path.lineTo(w * 0.875f, h)
            }
            74 -> {
                path.moveTo(w / 2f, h * 0.25f)
                path.cubicTo(w * 0.5f, -h * 0.05f, -w * 0.05f, h * 0.02f, w * 0.02f, h * 0.38f)
                path.cubicTo(w * 0.08f, h * 0.65f, w * 0.4f, h * 0.8f, w / 2f, h)
                path.cubicTo(w * 0.6f, h * 0.8f, w * 0.92f, h * 0.65f, w * 0.98f, h * 0.38f)
                path.cubicTo(w * 1.05f, h * 0.02f, w * 0.5f, -h * 0.05f, w / 2f, h * 0.25f)
                path.close()
            }
            else -> path.addRect(RectF(0f, 0f, w, h), Path.Direction.CW)
        }
        return Result(path, true, false)
    }

    private fun star(path: Path, w: Float, h: Float, points: Int, inner: Float) {
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) 1f else inner
            val a = -Math.PI / 2 + i * Math.PI / points
            val x = cx + (cos(a) * r * w / 2f).toFloat()
            val y = cy + (sin(a) * r * h / 2f).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun custom(p: PptProps, w: Float, h: Float): Result? {
        val vb = p.blob(0x0145) ?: return null
        val r = PptBytes(vb)
        val n = r.u16(0)
        val cbRaw = r.u16(4)
        val cb = if (cbRaw == 0xFFF0) 4 else cbRaw
        if (n <= 0 || (cb != 4 && cb != 8) || 6 + n * cb > vb.size) return null
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val o = 6 + i * cb
            if (cb == 4) {
                xs[i] = r.s16(o).toFloat()
                ys[i] = r.s16(o + 2).toFloat()
            } else {
                xs[i] = r.s32(o).toFloat()
                ys[i] = r.s32(o + 4).toFloat()
            }
        }
        val gl = p.int(0x0140, 0).toFloat()
        val gt = p.int(0x0141, 0).toFloat()
        val gr = p.int(0x0142, 21600).toFloat()
        val gb = p.int(0x0143, 21600).toFloat()
        val sx = if (gr != gl) w / (gr - gl) else 1f
        val sy = if (gb != gt) h / (gb - gt) else 1f
        fun X(i: Int) = (xs[i.coerceIn(0, n - 1)] - gl) * sx
        fun Y(i: Int) = (ys[i.coerceIn(0, n - 1)] - gt) * sy
        val path = Path()
        var closed = false
        var noFill = false
        val seg = p.blob(0x0146)
        var vi = 0
        if (seg == null) {
            path.moveTo(X(0), Y(0))
            for (i in 1 until n) path.lineTo(X(i), Y(i))
        } else {
            val sr = PptBytes(seg)
            val sn = sr.u16(0)
            val scbRaw = sr.u16(4)
            val scb = if (scbRaw == 0xFFF0) 2 else scbRaw.coerceAtLeast(2)
            for (k in 0 until sn) {
                val o = 6 + k * scb
                if (o + 2 > seg.size) break
                val v = sr.u16(o)
                val type = v ushr 13
                val count = v and 0x1FFF
                when (type) {
                    0 -> repeat(max(1, count)) { if (vi < n) { path.lineTo(X(vi), Y(vi)); vi++ } }
                    1 -> repeat(max(1, count)) {
                        if (vi + 2 < n) {
                            path.cubicTo(X(vi), Y(vi), X(vi + 1), Y(vi + 1), X(vi + 2), Y(vi + 2))
                        }
                        vi += 3
                    }
                    2 -> if (vi < n) { path.moveTo(X(vi), Y(vi)); vi++ }
                    3 -> { path.close(); closed = true }
                    4 -> {}
                    5 -> {
                        val esc = (v ushr 8) and 0x1F
                        val c = v and 0xFF
                        when (esc) {
                            0x0A -> noFill = true
                            0x0B -> {}
                            0x01, 0x02, 0x03, 0x04, 0x05, 0x06 -> {
                                repeat(c) { if (vi < n) { path.lineTo(X(vi), Y(vi)); vi++ } }
                            }
                            0x07, 0x08 -> {
                                repeat(c) { if (vi < n) { path.lineTo(X(vi), Y(vi)); vi++ } }
                            }
                            else -> vi += c
                        }
                    }
                    else -> vi += count
                }
            }
        }
        return Result(path, closed && !noFill, !closed)
    }
}

internal object PptBlipDecoder {
    fun decodeImage(data: ByteArray, offset: Int, maxDim: Int): PptImage? {
        val r = PptBytes(data)
        val rec = r.record(offset, data.size) ?: return null
        if (rec.type == 0xF01A || rec.type == 0xF01B || rec.type == 0xF01C) {
            val inst = rec.instance
            val uids = if (inst == 0x3D5 || inst == 0x217 || inst == 0x543) 2 else 1
            val hdr = rec.start + 16 * uids
            val payload = hdr + 34
            if (payload > rec.end) return null
            val compressed = r.u8(hdr + 32) == 0
            val raw = (if (compressed) inflate(data, payload, rec.end - payload) else data.copyOfRange(payload, rec.end)) ?: return null
            if (rec.type != 0xF01C) {
                val bytes = if (rec.type == 0xF01B && !MetafileRenderer.isWmf(raw)) {
                    val w = r.s32(hdr + 4 + 8) - r.s32(hdr + 4)
                    val h = r.s32(hdr + 4 + 12) - r.s32(hdr + 4 + 4)
                    placeable(raw, r.s32(hdr + 4), r.s32(hdr + 8), w, h)
                } else raw
                MetafileRenderer.decode(bytes)?.let { return PptImage(null, it) }
            }
            return EmbeddedImageScanner.decodeMetafileOrSvgBitmap(raw)?.let { PptImage(it, null) }
        }
        return decode(data, offset, maxDim)?.let { PptImage(it, null) }
    }

    private fun placeable(raw: ByteArray, l: Int, t: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(22 + raw.size)
        fun p16(p: Int, v: Int) { out[p] = v.toByte(); out[p + 1] = (v shr 8).toByte() }
        p16(0, 0xCDD7); p16(2, 0x9AC6)
        p16(6, l); p16(8, t); p16(10, l + w); p16(12, t + h); p16(14, 1440)
        System.arraycopy(raw, 0, out, 22, raw.size)
        return out
    }

    fun decode(data: ByteArray, offset: Int, maxDim: Int): Bitmap? {
        val r = PptBytes(data)
        val rec = r.record(offset, data.size) ?: return null
        if (rec.type !in 0xF018..0xF117) return null
        val inst = rec.instance
        return try {
            when (rec.type) {
                0xF01A, 0xF01B, 0xF01C -> {
                    val uids = if (inst == 0x3D5 || inst == 0x217 || inst == 0x543) 2 else 1
                    val hdr = rec.start + 16 * uids
                    val payload = hdr + 34
                    if (payload > rec.end) return null
                    val compressed = r.u8(hdr + 32) == 0
                    val raw = if (compressed) inflate(data, payload, rec.end - payload) else data.copyOfRange(payload, rec.end)
                    raw?.let { EmbeddedImageScanner.decodeMetafileOrSvgBitmap(it) }
                }
                0xF01D, 0xF01E, 0xF029, 0xF01F -> {
                    val uids = if (inst == 0x46B || inst == 0x6E3 || inst == 0x6E1 || inst == 0x7A9 || inst == 0x6E5) 2 else 1
                    val start = rec.start + 16 * uids + 1
                    if (start >= rec.end) return null
                    if (rec.type == 0xF01F) {
                        decodeSampled(dibToBmp(data, start, rec.end), 0, -1, maxDim)
                    } else {
                        decodeSampled(data, start, rec.end - start, maxDim)
                            ?: EmbeddedImageScanner.decodeMetafileOrSvgBitmap(data, start, rec.end - start)
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun inflate(data: ByteArray, off: Int, len: Int): ByteArray? {
        val inf = java.util.zip.Inflater()
        return try {
            inf.setInput(data, off, len)
            val out = java.io.ByteArrayOutputStream(len * 3)
            val buf = ByteArray(16384)
            while (!inf.finished() && out.size() < 64 * 1024 * 1024) {
                val n = inf.inflate(buf)
                if (n <= 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            inf.end()
        }
    }

    private fun dibToBmp(data: ByteArray, start: Int, end: Int): ByteArray {
        val r = PptBytes(data)
        val biSize = r.s32(start)
        val bitCount = r.u16(start + 14)
        val compression = r.s32(start + 16)
        val clrUsed = r.s32(start + 32)
        val palette = when {
            clrUsed > 0 -> clrUsed
            bitCount <= 8 -> 1 shl bitCount
            else -> 0
        }
        val masks = if (biSize == 40 && (compression == 3 || compression == 6)) 12 else 0
        val len = end - start
        val out = ByteArray(14 + len)
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        fun put(p: Int, v: Int) {
            out[p] = v.toByte(); out[p + 1] = (v shr 8).toByte(); out[p + 2] = (v shr 16).toByte(); out[p + 3] = (v shr 24).toByte()
        }
        put(2, out.size)
        put(10, 14 + biSize + masks + palette * 4)
        System.arraycopy(data, start, out, 14, len)
        return out
    }

    private fun decodeSampled(data: ByteArray, off: Int, lenIn: Int, maxDim: Int): Bitmap? {
        val len = if (lenIn < 0) data.size - off else lenIn
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, off, len, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(data, off, len, opts)
    }
}
